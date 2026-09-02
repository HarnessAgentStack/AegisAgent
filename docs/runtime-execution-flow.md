# 运行时全流程剖析

> 适用版本：0.1.0-alpha.1 ｜ 最后更新：2026-08-31
> 所有调用链、中间件顺序、事件类型均来自真实代码，无臆造。

---

## 序章：全局一瞥

从用户浏览器点开 SSE 连接到 Agent 返回最终回复，一共经过 6 个阶段、3 个进程、11 个中间件、N 次 ReAct 循环。这条文档把每一步拆透。

### 一句话链路

```
浏览器 → Gateway（鉴权+注入租户头）→ Runtime（校验→装配→执行→事件转换→投影持久化）→ AgentScope 内核（ReAct 循环 + 洋葱链中间件 + LLM + 工具调用 + 安全拦截 + HITL）→ SSE 流式回传
```

### 阶段划分

```
┌──────────────────────────────────────────────────────────────────────────────┐
│  阶段 1: 入口校验  │  阶段 2: 智能体装配  │  阶段 3: AgentScope 构建         │
│  ChatRequest      │  AgentAssemblySvc  │  AegisAgentInstanceManager       │
│  Validator        │  → AegisTaskCtx    │  → HarnessAgent.Builder          │
└──────────────────────────────────────────────────────────────────────────────┘
                                    ↓
┌──────────────────────────────────────────────────────────────────────────────┐
│  阶段 4: 洋葱链中间件  │  阶段 5: ReAct 循环（Think→Act→Observe）│  阶段 6: 投影持久化 │
│  11 层 Middleware     │  agent.streamEvents(msgs, rc)          │  SessionProjectionSvc │
└──────────────────────────────────────────────────────────────────────────────┘
```

---

## 一、入口：ChatRequest 进 Runtime

### 输入：HTTP 请求

```http
POST /api/runtime/task/chat
Content-Type: application/json
Accept: text/event-stream

// Gateway 自动注入的头（用户请求里没有）
X-Tenant-Id: 1
X-User-Id: 1001
X-Dept-Id: 50
X-Client-IP: 10.0.0.5
Authorization: Bearer eyJhbGci...

// 用户请求体
{
  "agentId": 42,
  "sessionId": null,              // null = 创建新会话
  "message": "帮我查一下上个月销售部门的业绩报表",
  "attachments": [
    { "name": "报表模板.xlsx", "storageRef": "tenant1/att/abc123", "mimeType": "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" }
  ],
  "skills": [],                   // @技能名 显式引用，为空=用绑定的技能
  "resources": null,              // 会话级临时资源引用
  "replyId": "req-uuid-abc123",   // 客户端重试去重用
  "isolationStrategy": null       // null = SHARED_PER_SCOPE（默认）
}
```

### 处理链路

```mermaid
flowchart TD
    A[Gateway AuthFilter] -->|"校验 JWT<br/>注入 X-Tenant-Id/UserId/Dept-Id"| B[Runtime TaskController.chat]
    B -->|"@TenantId @UserId @DeptId<br/>注解提取头 → 注入 ChatRequest.context"| C[ChatRequestValidator]
    C -->|"① agentId 必须存在<br/>② agent_def.status = PUBLISHED<br/>③ sessionId 存在时查 session 表校验归属"| D{通过?}
    D -->|否| E[返回 error 事件 + done]
    D -->|是| F["TaskExecutionService.execute(request)"]
```

ChatRequestValidator 的核心校验规则（来自 `ChatRequestValidator.java`）：

| 校验项 | 说明 |
|---|---|
| agentId | 必填，查 agent_def 表，必须 PUBLISHED |
| sessionId | 有值时查 sess_session，校验 tenant_id + user_id 归属 |
| isolationStrategy | null 或非法值 → 默认 SHARED_PER_SCOPE |
| replyId | 非空时 Redis SETNX 去重（`reply:t{tenantId}:{sessionId|new}:{replyId}`），重复请求直接返回上次结果 |

---

## 二、智能体装配：AgentAssemblyService.assemble()

装配是**同步阻塞**操作，在 `boundedElastic` 线程池执行（避免阻塞 WebFlux EventLoop）。一次性完成 5 件事，输出 `AegisTaskContext`（执行上下文）。

### 装配流程

```mermaid
flowchart TD
    A["assemble(request, taskId)"] --> B["① 查 agent_def + agent_config<br/>agent_binding + agent_subscription"]
    B --> C["② 创建 sess_session<br/>version_snapshot = agent_config JSON<br/>agent_state_session_key = 组装 Redis Key"]
    C --> D["③ 实例池查找/创建 HarnessAgent<br/>AgentInstanceManager.getOrCreate(poolKey)"]
    D --> E["④ 资源装载<br/>AegisSkillRepository 按档位分轨加载"]
    E --> F["⑤ 构造 UserMessage（含附件解析 + 多模态处理）"]
    F --> G["输出 AegisTaskContext"]
```

### poolKey（实例池粒度）

```mermaid
flowchart LR
    A["agent_def.governance_tier"] -->|UNIVERSAL| B["poolKey = userId<br/>每用户一个独立 HarnessAgent"]
    A -->|APPLICATION| C["poolKey = agentId<br/>同类型多用户共享"]
    A -->|SYSTEM| C
```

实例池命中时通过 `BindingFingerprinter` 比对当前请求携带的 agent_binding 版本指纹：
- **一致** → 直接复用，跳过重装配（冷启动 500-2000ms → <50ms）
- **不一致** → 走 refreshToolkit（Toolkit.removeTool + re-register）+ 重物化 Workspace

### AegisTaskContext 结构（装配输出）

```java
AegisTaskContext {
    String taskId                    // UUID，全链路 traceId
    Long tenantId, userId
    Long agentId
    AgentDef agentDef                // 不可变骨架
    AgentConfig agentConfig          // 当前版本配置
    String sessionId                 // 会话 ID
    HarnessAgent agent               // AgentScope 内核实例
    RuntimeContext runtimeContext    // AgentScope 运行时上下文
    String userMessage               // 清洗后的用户文本
    List<ContentBlock> multimodalBlocks // 多模态（图片）块
    boolean blocked                  // 装配失败标记
    String blockReason               // 失败原因
}
```

---

## 三、AgentScope HarnessAgent 构建

AegisAgentInstanceManager 是 AgentScope 内核和 Aegis 业务层的桥。

### Builder 链式构建（来自源码）

```java
HarnessAgent agent = HarnessAgent.builder()
    .distributedStore(distributedStore)        // → RedisDistributedStore
    .isolationScope(isolationScope)            // USER / AGENT
    .memoryConfig(memoryConfig)                // 记忆策略
    .filesystemSpec(remoteFsSpec)               // → 沙箱文件系统
    .sandboxSpec(sandboxSpec)                  // 沙箱配置（含懒分配）
    .snapshotSpec(snapshotSpec)                // → MinioSnapshotClient
    .permissionContextState(permissionCtx)     // → sec_tool_policy 运行时权限
    .middlewares(middlewareChain.build())       // ← 11 层洋葱链
    .tools(toolkit)                            // → AegisSkillRepository 装载的 Toolkit
    .build();
```

### IsolationScope 和 Redis Key 映射

| governance_tier | IsolationScope | agent_state_session_key（Redis） |
|---|---|---|
| UNIVERSAL | USER | `tenantId:userId:UNIVERSAL:agentId:sessionId` |
| APPLICATION | AGENT | `tenantId:userId:APPLICATION:agentId:sessionId` |
| SYSTEM | AGENT | `tenantId:userId:SYSTEM:agentId:sessionId` |

### 懒分配沙箱机制

```mermaid
sequenceDiagram
    participant AAM as AegisAgentInstanceManager
    participant SC as SandboxCoordinator
    participant AS as LazyAegisSandboxClient

    AAM->>AS: new LazyAegisSandboxClient()
    Note over AS: 此时不创建任何容器/Pod<br/>只存一个空壳引用

    loop ReAct 循环
        AS->>AS: 拦截点触发
        AS->>SC: 首次需要执行代码时<br/>请求真实沙箱
        SC->>SC: SandboxPoolRouter 匹配池
        SC->>SC: SandboxLifecycleManager 分配实例
        SC-->>AS: 返回真实 sandbox handle
    end
```

---

## 四、洋葱链中间件（11 层）

AgentScope 2.0.2 的 Middleware 有 **5 个拦截点**，洋葱链对所有拦截点统一生效。order 值越大越外层，越先执行。

### order 值（从源码 `AegisMiddlewareChain.java` + 各中间件 `order()` 方法）

```mermaid
flowchart TD
    subgraph "外层（先执行）"
        M1["order=95  AegisTraceMiddleware<br/>链路追踪 + Span 创建"]
        M2["order=90  AegisSecurityMiddleware<br/>工具策略查表 + HITL 触发"]
        M3["order=80  AegisTenantIsolationMiddleware<br/>租户隔离校验"]
        M3_5["order=75  AegisBindingSyncMiddleware<br/>绑定同步校验"]
        M4["order=67  AegisIntentMiddleware<br/>意图识别 + 查询改写"]
        M5["order=65  AegisRagMiddleware<br/>知识库检索 + 知识片段注入系统提示词"]
        M6["order=60  AegisContentFilterMiddleware<br/>敏感词检测 + 内容过滤"]
    end
    subgraph "内层（后执行）"
        M7["order=20  AegisAuditLogMiddleware<br/>审计日志落库"]
        M8["order=15  AegisSandboxHeartbeatMiddleware<br/>沙箱心跳 + IdleReleaseTracker"]
        M9["order=10  AegisMemoryMiddleware<br/>跨会话记忆摘要"]
        M10["order=10  AegisMaskMiddleware<br/>输出脱敏（手机号/身份证/银行卡）"]
    end

    M1 --> M2 --> M3 --> M3_5 --> M4 --> M5 --> M6 --> M7 --> M8 --> M9 --> M10
    style M1 fill:#e3f2fd
    style M2 fill:#ffebee
    style M5 fill:#fff3e0
    style M10 fill:#f3e5f5
```

### 洋葱链在 5 个拦截点分别做什么

| 拦截点 | 触发时机 | 主要中间件 | 做什么 |
|---|---|---|---|
| **onSystemPrompt** | 发给 LLM 之前 | RagMiddleware | Milvus 检索 TOP-K 知识片段 → 拼到系统提示词末尾 |
| **onModelCall** | 每次调 LLM 时 | TraceMiddleware | 创建 MODEL_CALL Span，记录输入输出 |
| **onReasoning** | LLM 返回 CoT 思考过程时 | TraceMiddleware | 创建 REASONING Span |
| **onActing** | LLM 决定调用工具时 | SecurityMiddleware **最关键** | `(tool_type, security_level)` 查 sec_tool_policy → 无命中时按等级直映（L1/L2→ALLOW, L3→ASK, L4→REJECT） |

### 最关键：AegisSecurityMiddleware.onActing()

```mermaid
flowchart TD
    A["onActing 拦截点触发"] --> B["取 res_tool.security_level"]
    B --> C["取 res_tool.tool_type"]
    C --> D["查 sec_tool_policy<br/>(tool_type, level) → action（无命中时按等级直映）"]
    D --> E{action}
    E -->|ALLOW| F["直接放行 → 执行工具"]
    E -->|APPROVE| G["HitlFlowService 触发"]
    G --> G1["sec_hitl_node 匹配 trigger_condition"]
    G1 --> G2["Redis: aegis:hitl:req:{sessionId} = toolCall JSON"]
    G2 --> G3["抛出 HITL 异常 → 会话挂起"]
    E -->|REJECT| H["拦截 + mon_audit_log 落库 → 告诉 LLM '工具被拒绝'"]

    style F fill:#c8e6c9
    style G fill:#fff9c4
    style H fill:#ffcdd2
```

---

## 五、ReAct 循环：Think → Act → Observe

这是 AgentScope HarnessAgent 的核心机制。一个完整对话可能经过 1-N 轮循环。

### 循环结构

```mermaid
flowchart LR
    subgraph "循环开始"
        R["UserMessage 入队"]
    end

    subgraph "Think"
        T1["中间件链 onModelCall<br/>LLM 推理"]
        T2["中间件链 onReasoning<br/>收集 CoT"]
        T3{"LLM 返回函数调用?"}
    end

    subgraph "Act"
        A1["中间件链 onActing<br/>SecurityMiddleware 拦截<br/>tool_type × security_level 查表 → 等级直映兜底"]
        A2{"安全策略决策?"}
        A2 -->|ALLOW| A3["执行工具"]
        A2 -->|APPROVE| A4["HITL 挂起 → 等审批"]
        A2 -->|REJECT| A5["拦截 → 告诉 LLM 换方式"]
    end

    subgraph "Observe"
        O1["中间件链 onToolResult<br/>工具结果 + kb_ref"]
        O2["结果回喂 LLM"]
    end

    subgraph "结束"
        E{"LLM 返回普通文本?"}
        E -->|是| END["done → SSE 关闭"]
    end

    R --> T1 --> T2 --> T3
    T3 -->|函数调用| A1 --> A2
    A2 --> A3 --> O1 --> O2 --> T1
    A2 --> A4
    A2 --> A5 --> O1 --> O2 --> T1
    T3 -->|普通文本| E
    O2 --> T1

    style R fill:#e1f5fe
    style END fill:#c8e6c9
    style A4 fill:#ffcc80
```

### 每轮循环的事件序列（SSE 输出）

以一个典型的两轮对话为例（查销售业绩 → 调用 execute 执行 SQL → 返回结果）：

```mermaid
sequenceDiagram
    participant Client as 浏览器
    participant RT as Runtime
    participant AS as HarnessAgent
    participant LLM as LLM API
    participant Tool as execute 工具
    participant SSE as SSE Stream

    Client->>RT: POST /chat { agentId:42, message:"查销售业绩" }
    RT->>AS: streamEvents([UserMessage("查销售业绩")], rc)

    Note over AS,LLM: 第 1 轮 Think
    AS->>LLM: system_prompt + history + UserMessage
    LLM-->>AS: reasoning.delta: "用户要查业绩..." + function_call: execute({sql:"SELECT..."})
    AS-->>SSE: SSE: event:reasoning  data:{delta:"用户要查业绩..."}

    Note over AS,Tool: 第 1 轮 Act (onActing 拦截)
    AS->>AS: AegisSecurityMiddleware.onActing<br/>查 sec_tool_policy → APPROVE
    alt 需要 HITL
        AS-->>SSE: SSE: event:hitl.request data:{tool:"execute", params:{...}}
        Note over Client,User: 用户审批后恢复
    else 直接通过
        AS->>Tool: execute({sql:"SELECT amount FROM sales WHERE month=..."})
        Tool-->>AS: { result: [{amount:52800}, {amount:61200}] }
        AS-->>SSE: SSE: event:tool_call data:{tool:"execute", params:{...}}
        AS-->>SSE: SSE: event:tool_result data:{result:[...]}
    end

    Note over AS,LLM: 第 2 轮 Think（Observe 结果后喂回 LLM）
    AS->>LLM: 追加 ToolResult 到 history
    LLM-->>AS: reasoning.delta + final_text: "销售部门上月业绩 114000 元..."
    AS-->>SSE: SSE: event:reasoning  data:{delta:"汇总金额是..."}
    AS-->>SSE: SSE: event:text_delta data:{delta:"销售部门上月业绩 114000 元，同比增长 12%"}

    RT-->>SSE: SSE: event:done data:{replyId:"req-uuid-abc123"}
    SSE-->>Client: SSE 连接关闭
```

---

## 六、SSE 事件协议

AgentScope AgentEvent 经 HarnessEventConverter 转换后以 SSE 点分事件名推送到前端。共 **20 种**事件类型（types/session.ts 的 SseEventType 联合）。

### 事件类型字典

| 事件名 | 触发时机 | data 示例 |
|---|---|---|
| `agent_start` | 装配完成、HarnessAgent 准备好时 | `{"taskId":"uuid","sessionId":"sess-uuid","agentName":"销售助手","model":"doubao-seed-2.0-lite"}` |
| `message.delta` | LLM 流式输出文本片段 | `"销售部门上月业绩"` |
| `reasoning.delta` | LLM CoT 思考过程片段 | `"用户要查的是销售部门整体业绩..."` |
| `tool.call` | 工具调用发起 | `{"tool":"aegis_execute","params":{"code":"SELECT..."}}` |
| `tool.result` | 工具执行完成 | `{"tool":"aegis_execute","result":[{"amount":52800}],"kb_refs":[]}` |
| `kb.reference` | RAG 知识库引用（单独发出） | `[{"kbCode":"sales_kb","docName":"2025H1报表.pdf","score":0.87}]` |
| `task.status` | 任务状态变更（STARTED/THINKING/TOOL_CALLING/PAUSED/ENDED） | `{"status":"TOOL_CALLING"}` |
| `hitl.request` | 安全策略触发 APPROVE 时 | `{"tool":"aegis_execute","level":"L3","params":{...},"sessionId":"sess-uuid"}` |
| `subagent.start` | 子智能体启动 | `{"agentId":"xxx","sessionId":"sess-uuid"}` |
| `subagent.end` | 子智能体结束 | `{"replyId":"..."}` |
| `artifact.created` | 会话产物创建（generate_file） | `{"fileId":"xxx","downloadUrl":"..."}` |
| `skill.activated` | 技能激活 | `{"code":"chart_gen"}` |
| `skill.rejected` | 技能被拒绝（@技能不存在或无权限） | `[{"code":"unknown_skill","reason":"not_found"}]` |
| `skill.creator.draft.*` | Skill Creator 元技能工作流（draft 创建、schema 解析、安全扫描结果等） | 随子事件不同 |
| `skill.creator.debug.result` | 技能调试执行结果 | `{"stdout":"...","stderr":"..."}` |
| `skill.package.result` | 技能打包结果 | `{"packageUrl":"..."}` |
| `skill.draft.*` | 技能草稿相关事件 | 随子事件不同 |
| `error` | 执行异常 | `{"code":"NETWORK_TIMEOUT","message":"LLM API 连接超时"}` |
| `done` | SSE 关闭信号（**必发**，前端靠它关闭 EventSource） | `{"replyId":"req-uuid-abc123"}` |

> **不存在 `agent_end` 事件**：AgentScope 的 AGENT_END 被 HarnessEventConverter 转换为 `done`；token/成本/耗时数据落库到 mon_trace/sess_message，**不经 SSE 下发**，前端需从可观测接口查询。

### 完整 SSE 帧示例（raw text）

```yaml
# 帧 1: agent_start
event: agent_start
data: {"taskId":"a3f1...","sessionId":"sess-abc123","agentName":"销售助手","model":"doubao-seed-2.0-lite"}

# 帧 2: skill.activated（@技能显式引用）
event: skill.activated
data: {"code":"sql_gen"}

# 帧 3: reasoning.delta（CoT 思考）
event: reasoning.delta
data: "用户要查的是上月销售部门业绩，我需要执行 SQL 查询..."

# 帧 4: tool.call
event: tool.call
data: {"tool":"aegis_execute","params":{"code":"SELECT SUM(amount) FROM sales WHERE dept='sales' AND month='2026-07'"}}

# 帧 5: tool.result
event: tool.result
data: {"tool":"aegis_execute","result":[{"SUM(amount)":114000}],"kb_refs":[]}

# 帧 6: reasoning.delta（第二轮思考）
event: reasoning.delta
data: "查询结果是 114000 元..."

# 帧 7-9: message.delta（流式文本输出，可能拆多帧）
event: message.delta
data: "销售部门"
event: message.delta
data: "上月业绩 114,000 元"
event: message.delta
data: "，同比增长 12%"

# 帧 10: done（结束，仅 replyId）
event: done
data: {"replyId":"req-uuid-abc123"}
```

---

## 七、投影持久化：SessionProjectionService

**流式输出过程中 fire-and-forget 落库，不阻塞 SSE 主线程。** 所有 UPDATE/INSERT 操作异步执行。

### 状态流转

```mermaid
stateDiagram-v2
    [*] --> STARTED: 创建 sess_session
    STARTED --> THINKING: agent_start 事件
    THINKING --> TOOL_CALLING: tool_call 事件
    TOOL_CALLING --> TOOL_CALLING: 多工具调用场景可循环
    TOOL_CALLING --> OUTPUTTING: tool_result 事件
    OUTPUTTING --> THINKING: 新一轮 ReAct
    OUTPUTTING --> PAUSED: hitl.request 事件（用户审批中）
    PAUSED --> THINKING: 审批通过，恢复执行
    OUTPUTTING --> ENDED: agent_end 事件
    * --> EXCEPTION: 执行异常
    * --> INTERRUPTED: 用户中断 / SSE 连接断开

    note right of PAUSED
        PAUSED 状态不会被 CANCEL 信号覆盖
        用户回来审批后继续
    end note
```

### 事件驱动落库

| 事件 | 触发落库 | 写入表 |
|---|---|---|
| `agent_start` | sess_session status → THINKING | sess_session |
| `tool_call` | sess_message INSERT（TOOL_CALL） | sess_message |
| `tool_result` | sess_message INSERT（TOOL_RESULT） + mon_span INSERT | sess_message, mon_span |
| `text_delta` | 不直接落（累积到 agent_end 一次性落） | — |
| `hitl.request` | sess_session status → PAUSED + sec_hitl_history INSERT | sess_session, sec_hitl_history |
| `agent_end` | sess_message INSERT（ASSISTANT，含 reasoning + token + cost）+ sess_session status → ENDED + mon_trace INSERT | sess_message, mon_trace |
| `generate_file` 工具 | sess_artifact INSERT + MinIO PUT | sess_artifact, MinIO |

### cost_amount 计算公式

```
cost_amount = token_input × model_def.input_cost / 1000
            + token_output × model_def.output_cost / 1000
```

在 agent_end 事件触发时计算，单价取自 `model_def.input_cost` / `output_cost`（元/千 token，见 DDL model_def 表）。

---

## 八、HITL 审批流（完整闭环）

当 AegisSecurityMiddleware.onActing() 判定策略为 APPROVE 时触发。会话进入 PAUSED 状态，等待用户审批。

### 全流程时序

```mermaid
sequenceDiagram
    participant AS as HarnessAgent
    participant MW as SecurityMiddleware
    participant Redis as Redis
    participant MySQL as MySQL
    participant FE as 前端
    participant User as 审批人

    AS->>MW: onActing 拦截点
    MW->>MySQL: sec_tool_policy 查表
    Note over MySQL: tool_type 匹配, level=L3<br/>→ action=APPROVE
    MW->>MySQL: sec_hitl_node 匹配 trigger_condition<br/>toolSecurityLevel>=3 → 命中

    MW->>Redis: SET aegis:hitl:req:{sessionId} = {toolCall JSON}
    Note right of Redis: TTL=48h（等审批）

    MW->>MySQL: INSERT sec_hitl_history (PENDING)
    MW-->>AS: 抛出 HitlRequiredException
    AS-->>FE: SSE: event:hitl.request data:{tool:"execute", level:"L3", ...}

    FE->>MySQL: UPDATE sess_session SET status=PAUSED

    Note over User: 用户在前端看到弹窗：<br/>"Agent 想调用 L3 工具 execute，<br/>参数：SELECT...，是否批准？"

    alt 审批通过
        User->>FE: 点击批准
        FE->>Redis: SET aegis:hitl:approved:{sessionId} = toolName
        Note right of Redis: TTL=1h
        FE->>MySQL: UPDATE sec_hitl_history SET action=APPROVE
        FE->>AS: 调用恢复接口
        AS->>Redis: GET aegis:hitl:req:{sessionId}
        AS->>AS: 恢复执行 → 直接执行工具
        AS-->>FE: 正常 SSE 继续推送
    else 审批拒绝
        User->>FE: 点击拒绝
        FE->>MySQL: UPDATE sec_hitl_history SET action=REJECT
        FE->>AS: 恢复但跳过工具
        AS->>AS: 告诉 LLM "工具被拒绝，请用其他方式"
    end
```

---

## 九、网络重试与异常处理

### Retry 策略（来自 `TaskExecutionService.java`）

```java
MAX_NETWORK_RETRIES = 2
RETRY_DELAY_MS = 1500  // backoff
```

只对**网络类异常**重试：ConnectException / SocketTimeoutException / UnknownHostException。业务异常（比如 AgentScope PermissionDeniedException）不重试。

### 异常分类处理

| 异常类型 | 处理方式 |
|---|---|
| 网络类（LLM API 连接超时/DNS 解析失败） | 重试 2 次 → 耗尽后返回 `error` 事件 |
| 业务类（PermissionDenied / HitlRequired） | 不重试 → 立即转成对应事件 |
| 流式中断（用户关闭页面） | `doFinally` 兜底 → `InterruptSignalManager.forceUnregister` + `onForceTerminate` |
| SSE Buffer 溢出 | `onBackpressureBuffer(256, DROP_OLDEST)` → 丢弃最旧的 event |

### SSE BufferOverflow 策略

浏览器网络抖动时 SSE 连接积压。AgentScope streamEvents Flux 使用 **DROP_OLDEST**：当缓冲区满（256 个 event），扔掉最旧的 text_delta，保证最新事件能推出去。代价是用户可能看到输出跳字。

---

## 十、中断与控制流

### 两种中断方式

```mermaid
flowchart TD
    subgraph "用户主动中断"
        A1["前端调用 POST /api/runtime/task/{sessionId}/interrupt"] --> A2["InterruptSignalManager.trigger(sessionId)"]
        A2 --> A3["interruptSink.asFlux() 触发<br/>Flux.takeUntilOther() 关闭流"]
        A3 --> A4["doFinally → onForceTerminate<br/>sess_session → INTERRUPTED"]
    end

    subgraph "系统异常中断"
        B1["AgentScope 抛异常"] --> B2["onErrorResume 捕获"]
        B2 --> B3["返回 error 事件 + done 事件"]
        B3 --> B4["SessionProjection.onTerminate<br/>sess_session → EXCEPTION"]
    end

    style A1 fill:#e1bee7
    style B1 fill:#ffcdd2
```

---

## 附录：完整事件序列速查

以一个最典型的对话（两轮 ReAct，一次 execute 工具调用）为例：

| # | SSE 事件 | 来源 | 持久化动作 |
|---|---|---|---|
| 1 | `agent_start` | Runtime 装配完成 | sess_session → THINKING |
| 2 | `skill_ref` | SkillRepository 解析 @技能 | — |
| 3 | `reasoning` (CoT 片段) | LLM ReAct 第 1 轮 | mon_span(REASONING) |
| 4 | `tool_call` | LLM 函数调用 | sess_message INSERT(TOOL_CALL) + mon_span(TOOL_CALL) |
| 5 | `tool_result` | 工具执行完成 | sess_message INSERT(TOOL_RESULT) + mon_span(TOOL_CALL) |
| 6 | `reasoning` (CoT 片段) | LLM ReAct 第 2 轮 | mon_span(REASONING) |
| 7 | `text_delta` × N | LLM 流式输出（拆多帧） | — |
| 8 | `done` | Runtime 关闭 SSE（仅 replyId） | sess_session → ENDED |

如果中间触发 HITL，在 tool_call 之后会插入 `hitl.request` → sess_session → PAUSED → 等审批 → 恢复后续事件。

---

## 附录：Redis Key 全景

| Key Pattern | 谁写谁读 | TTL | 用途 |
|---|---|---|---|
| `aegis:session:{userId}/{sessionId}:agent_state` | AgentScope 内核自动读写 | 会话存活期 | AgentState 序列化（单值） |
| `aegis:session:{userId}/{sessionId}:agent_state:list` | AgentScope 内核 | 会话存活期 | AgentState 历史（列表） |
| `aegis:session:{userId}/{sessionId}:_keys` | AgentScope 内核 | 会话存活期 | 会话内所有 Key 的索引 |
| `aegis:store:item:{userId}/{sessionId}\0{filename}` | AgentScope Workspace | 会话存活期 | Workspace 文件内容 |
| `aegis:store:idx:{userId}/{sessionId}` | AgentScope Workspace | 会话存活期 | Workspace 文件索引 |
| `aegis:hitl:req:{sessionId}` | HITL 触发时写，恢复时读 | **48h** | 工具调用原始请求 JSON |
| `aegis:hitl:approved:{sessionId}` | 审批通过时写，恢复时读 | **1h** | 审批标记 |
| `aegis:security:policy:{tenantId}:{policyType}` | SecurityCache 读，Admin 通知时失效 | 30min | 安全策略 JSON 缓存（policyType: TOOL/CONTENT/MASK/OUTBOUND/HITL） |
| `reply:t{tenantId}:{sessionId|new}:{replyId}` | TaskController.chat 写，重试请求检查 | 5min | 客户端重试去重 SETNX |
| `aegis:rate_limit:{scopeType}:{scopeId}:{window}` | RateLimitService 读写 | 窗口级 | 限流 ZSET 计数器 |

---

## 附录：涉及的核心 Service/类速查

| 类名 | 职责 | 在哪一步 |
|---|---|---|
| `TaskController` | HTTP 入口，注解提取租户头 | 阶段 1 |
| `ChatRequestValidator` | agentId/sessionId 校验 | 阶段 1 |
| `TaskExecutionService` | 编排：装配 → 流式执行 → 异常处理 → doFinally 兜底 | 阶段 2+5+6 |
| `AgentAssemblyService` | 同步装配：查库 → 创建 session → 构建 context | 阶段 2 |
| `AegisAgentInstanceManager` | 实例池管理 + Lazy 沙箱 + Middleware 装配 | 阶段 3 |
| `AegisMiddlewareChain` | 按 order() 排序 11 层中间件 → Builder.middlewares() | 阶段 3 |
| `AegisSkillRepository` | 按档位分轨装载 Toolkit（技能/MCP/知识库） | 阶段 2+3 |
| `AegisSecurityPolicyEngine` | (tool_type, level) → action 查表，无命中时等级直映兜底 | onActing 拦截点 |
| `HitlFlowService` | Redis 存 req → 恢复时注入 ConfirmResult | onActing + 恢复 |
| `SessionProjectionService` | fire-and-forget 投影：工具消息 / 终态落库 / 状态流转 | 流式中 + 终态 |
| `InterruptSignalManager` | register → sink → trigger 中断 | 流式中 |
| `AgentPoolManager` | LRU + TTL 实例池驱逐 | AegisAgentInstanceManager 内部 |
