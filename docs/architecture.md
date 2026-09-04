# Aegis 技术架构说明

> 适用版本：0.1.0-alpha.1 ｜ 最后更新：2026-09-03（源码核实）

---

## 一、进程与职责

四个 Spring Boot 进程 + 一个前端：

| 进程 | 端口 | 技术栈 | 职责 |
|---|---|---|---|
| gateway | 8080 | Spring Cloud Gateway (WebFlux) | 流量入口、JWT 鉴权、TraceId/SessionId 生成、租户解析 |
| admin | 8082 | Spring Boot 4 WebFlux + MyBatis-Plus | 管理平面 CRUD、RBAC、审核中心、沙箱池 Reconcile、安全策略配置、审计 |
| runtime | 8081 | Spring Boot 4 WebFlux + AgentScope 2.0.2 | 对话执行、HarnessAgent 装配、中间件链、SSE、沙箱执行 |
| mcp-demo | 8084 | Spring Boot 4 (Web MVC) + Spring AI MCP | 独立 MCP Server 示例，启动后自注册到 admin |
| web | 5173 (dev) / 80 (prod) | Vite 6 + React 18 + Ant Design 5 | 工作台 + 管理后台 |

进程间经 Nacos 服务发现 + HTTP 调用协作，共享同一 MySQL/Redis，不经消息中间件通信。Nacos 配置中心在各模块均为 `config.enabled=false`，仅用于服务发现。

前端 dev 模式经 Vite 代理直连后端，不经过网关：

```
/api/admin/auth  →  http://localhost:8082
/api/runtime     →  http://localhost:8081   （SSE：timeout=0、禁用压缩、keep-alive）
/api/admin       →  http://localhost:8082
/api             →  http://localhost:8082
```

### 网关过滤器

4 个 `GlobalFilter`，按 `getOrder()` 升序执行：

| order | 类 | 作用 |
|---|---|---|
| `HIGHEST_PRECEDENCE - 100` | `AuditEntryFilter` | 生成 TraceId |
| `HIGHEST_PRECEDENCE` | `AuthFilter` | JWT 鉴权，失败 401（fail-closed） |
| `HIGHEST_PRECEDENCE + 50` | `SessionIdFilter` | SessionId 生成 / 校验，承担会话粘性 |
| `HIGHEST_PRECEDENCE + 100` | `TenantResolveFilter` | 解析租户 ID |

路由（`RouteConfig`，编程式）：`/api/admin/**`、`/api/resource/**` → `lb://aegis-admin`；`/api/runtime/**` → `lb://aegis-runtime`。

---

## 二、模块依赖

```
aegis-platform-backend（Maven 聚合，Java 21，Spring Boot 4.0.0）
│
├── aegis-core
│   ├── aegis-core-domain     实体 / DTO / 枚举 / 常量（零外部依赖）
│   ├── aegis-core-spi        SPI 接口 + SkillContentScanner（技能静态安全扫描）
│   └── aegis-core-infra      自动配置、Result 封装、租户上下文、Milvus 适配
│
├── aegis-dal                 MyBatis-Plus Mapper（按领域分包）+ MysqlTraceStore
│
├── aegis-gateway             仅依赖 core-domain + core-infra
├── aegis-admin               dal + core-infra
├── aegis-runtime             dal + core-infra + AgentScope 2.0.2
└── aegis-mcp-demo            独立，不依赖 core/dal
```

依赖方向由 ArchUnit 测试守护：`aegis-admin/src/test/java/com/aegis/admin/arch/ArchitectureGuardTest.java`。

`aegis-runtime` 内部分包：

| 包 | 内容 |
|---|---|
| `integration/agent` | `AegisAgentInstanceManager`（实例池 + Builder 装配）、`HarnessEventConverter`、`AegisToolBridge` |
| `integration/middleware` | 6 个中间件（`BindingSyncMiddleware` 在 `integration/workspace` 包下） |
| `integration/model` | `AegisModelProvider`（ModelProvider SPI）+ 动态模型路由 |
| `integration/tool` | `AegisBuiltinTools`、`AegisExecuteTool`、`AegisGenerateFileTool`、`AegisHttpTool`、`AegisMcpTool` |
| `integration/sandbox` | `AegisSandbox` / `AegisSandboxClient` / `AegisSandboxFilesystemSpec` |
| `integration/security` | `AegisPermissionRuleLoader` |
| `service/sandbox` | `SandboxExecutor`、`SandboxSessionHolder`、`AegisSandboxPoolExecutor`、`AegisSandboxAllocator`、`SandboxTrigger`、`SandboxPolicyResolver` |
| `service/conversation` | `TaskExecutionService`（执行管道）、`SessionProjectionService`（投影落库） |
| `infrastructure/document` | 文档解析（PDF / Office / 图片 / ONNX OCR） |

---

## 三、AgentScope 2.0.2 集成

### 3.1 依赖

| artifact | 版本 | 作用 |
|---|---|---|
| `agentscope-core` | 2.0.2 | 抽象层：`MiddlewareBase` / `Toolkit` / `AgentEvent` / `PermissionEngine` |
| `agentscope-harness` | 2.0.2 | `HarnessAgent` 实现：ReAct、权限引擎、沙箱、记忆、上下文压缩 |
| `agentscope-extensions-redis` | 2.0.2 | `DistributedStore` 的 Redis 实现 |
| `agentscope-extensions-oss` | 2.0.2 | OSS/S3 快照实现 |
| `agentscope-extensions-model-openai` | 2.0.2 | OpenAI 兼容协议模型实现 |

仅在 `aegis-runtime` 声明。

### 3.2 扩展点接入方式

只有 `ModelProvider` 走 Java `ServiceLoader`：

```
aegis-runtime/src/main/resources/META-INF/services/io.agentscope.core.model.spi.ModelProvider
  → com.aegis.runtime.integration.model.AegisModelProvider
```

其余扩展点以 Spring Bean 形式注入 `HarnessAgent.Builder`：

| AgentScope 扩展点 | Aegis 实现 | 接入方式 |
|---|---|---|
| `ModelProvider` | `AegisModelProvider` | `META-INF/services`，按模型 ID `aegis:{tier}:{tenantId}` 从 DB 路由 |
| `TraceStore` | `MysqlTraceStore`（aegis-dal） | Spring Bean |
| `VectorStore` | `MilvusVectorStoreAdapter`（aegis-core-infra） | Spring Bean |
| `DistributedStore` | Redis 实现（extensions-redis） | Spring Bean，注入 `Builder.distributedStore()` |
| 沙箱快照 | `MinioSnapshotClient` → `RemoteSnapshotSpec` | Spring Bean，注入 `DistributedStore` |
| 沙箱文件系统 | `RemoteFilesystemSpec` / `AegisSandboxFilesystemSpec` | `Builder.filesystem()` |
| 技能仓库 | `AegisSkillRepository` | `Builder.skillRepository()` |

### 3.3 HarnessAgent 实际装配

`AegisAgentInstanceManager.configureAgentBuilder()`：

```java
HarnessAgent.builder()
    .name("AegisAgent-" + agentId)
    .sysPrompt(effectiveSysPrompt)
    .model("aegis:" + modelTier.toLowerCase() + ":" + tenantId)  // 由 AegisModelProvider 解析
    .toolkit(toolkit)
    .distributedStore(distributedStore)
    .disableFilesystemTools()          // 禁用 AS 内置文件工具
    .disableShellTool()                // 禁用 AS 内置 ShellExecuteTool，强制走 aegis_execute
    .skillRepository(skillRepository)
    .skillsEnabled(true)
    .maxIters(agentConfig.maxTurns != null ? maxTurns : aegis.upon.max-iters)  // 默认 5
    .agentId(String.valueOf(agentId))
    .middlewares(standaloneMiddlewares)
    .compaction(buildCompactionConfig(agentConfig))
    .toolResultEviction(ToolResultEvictionConfig.defaults())
    .maxRetries(3)
    .maxContextTokens(100_000)
    .permissionContext(permissionContext);
// 条件装配：
//   memoryFlushStrategy == NONE  → disableMemoryHooks()
//   enablePlanMode == true       → enablePlanMode()
//   filesystem: RemoteFilesystemSpec 或 AegisSandboxFilesystemSpec
```

### 3.4 中间件链

6 个中间件实现 `MiddlewareBase`，Spring 注入 `List<MiddlewareBase>`，由 AgentScope 内核按 `order()` 降序（大值在外层）驱动：

| order | 类 | 拦截点 | 作用 |
|---|---|---|---|
| 95 | `AegisTraceMiddleware` | onAgent / onReasoning / onActing / onModelCall | TraceId 贯穿，Span 记录（AGENT / REASONING / TOOL_CALL / MODEL_CALL） |
| 85 | `SandboxRoutingMiddleware` | onActing | 读 `sec_sandbox_policy` 判定工具是否进沙箱；仅判定 + 审计，不短路执行 |
| 75 | `BindingSyncMiddleware` | onAgent | 绑定指纹比对，不一致时 `refreshToolkit` + Workspace 重物化 |
| 70 | `AegisRagMiddleware` | onSystemPrompt | 知识库检索，片段注入系统提示词 |
| 50 | `AegisMaskMiddleware` | — | 输出脱敏 |
| 30 | `AegisAuditLogMiddleware` | onActing | 审计日志落库 |

AgentScope 2.0.2 提供 5 个拦截点：4 个洋葱型（`onAgent` / `onReasoning` / `onActing` / `onModelCall`）+ 1 个管道型变换（`onSystemPrompt`）。

安全决策不在中间件链内，由 AgentScope 原生 `PermissionEngine` 在 `onActing` 承担，规则经 `AegisPermissionRuleLoader` 从 `sec_tool_policy` 装载。

### 3.5 实例池

| agent_type | 池 key | IsolationScope |
|---|---|---|
| UNIVERSAL | `userId` | `USER` |
| APPLICATION | `agentId` | `AGENT` |
| SYSTEM | `agentId` | `AGENT` |

`AgentPoolManager` 基于 Caffeine：

| 缓存 | 配置 |
|---|---|
| `templateCache` | `maximumSize = max(agentPool.maxPerTenant × 50, 5000)`，`expireAfterWrite(idleEvictMinutes)` |
| `versionCache` | `expireAfterWrite(30s)` |

对应配置项 `aegis.runtime.agent-pool.max-size`（默认 100）与 `idle-evict-minutes`（默认 15）。

`BindingFingerprinter` 对 `agent_binding` 计算 SHA-256（`resourceType:resourceId:resourceVersion` 排序拼接）：一致则复用实例，不一致则 `refreshToolkit` + 重物化 Workspace。

工作区根路径按池键派生，与 sessionId 无关：`UNIVERSAL → /workspace/{tenantId}/{agentId}/{userId}`，`APPLICATION/SYSTEM → /workspace/{tenantId}/{agentId}`。

---

## 四、技术栈版本

**后端（Java 21）**

| 组件 | 版本 |
|---|---|
| Spring Boot | 4.0.0 |
| Spring Cloud Gateway（WebFlux） | 2025.1.0 |
| Spring Cloud Alibaba Nacos | 2025.1.0.0 |
| MyBatis-Plus | 3.5.16 |
| AgentScope | 2.0.2 |
| ArchUnit | 1.3.0 |
| Milvus SDK | 2.5.0 |
| MinIO SDK | 8.5.14 |
| docker-java / fabric8 | 3.4 / 7.4 |

**前端**

| 组件 | 版本 |
|---|---|
| React / React DOM | 18.3.1 |
| Ant Design / icons | 5.22.x / 5.5.x |
| Vite | 6.x |
| TypeScript | 5.7 |
| Zustand | 5.0.2 |
| TanStack React Query | 5.62 |
| React Router | 6.28 |
| 包管理器 | npm（仓库含 package-lock.json，CI 用 `npm ci`） |

**基础设施**

| 组件 | 镜像 / 版本 | 端口 | 说明 |
|---|---|---|---|
| MySQL | 8.0 | 3306 | `lower-case-table-names=1`，61 张业务表 |
| Redis | 7-alpine | 6379 | AOF，`maxmemory 256mb`，`allkeys-lru` |
| Nacos | v3.2.2 | 8848 / 9848 / 9849 / 8083 | 控制台在 8083（容器 8080 映射，避开网关 8080） |
| MinIO | latest | 9000 / 9001 | S3 API / 控制台 |
| etcd | v3.5.16 | 2379（内部） | Milvus 元数据存储 |
| Milvus | v2.5.0 | 19530 / 9091 | gRPC / 健康与指标 |
| SearXNG | latest | 8888 | `web_search` 工具后端 |
| Prometheus | v2.53.0 | 9090 | profile `observability` |
| Grafana | 11.1.0 | 3000 | profile `observability` |
| OTel Collector | 0.104.0 | 4317 / 4318 | profile `observability` |
| PaddleOCR | 已移除 | — | 已废弃：OCR 统一走 ONNX Runtime 进程内推理，原 `paddleocr` Docker 服务已从启动脚本移除 |

OCR 走 ONNX Runtime 进程内推理（`aegis.ocr.onnx.enabled`，默认 true，模型在 `models/ocr/`）。PaddleOCR 为已弃用 fallback（`aegis.ocr.paddle.enabled`，原 Docker 服务已移除）。

---

## 五、数据分布

### 5.1 MySQL（61 表）

DDL：`infra/ddl/01_schema_init.sql`；种子：`infra/ddl/02_seed_data.sql`。字段级说明见 [data-model.md](data-model.md)。

| 前缀 | 表数 | 表 |
|---|---|---|
| `res_` | 14 | tool, skill, skill_version, skill_subscription, knowledge_base, kb_document, kb_document_chunk, kb_process_progress, kb_subscription, session_summary, mcp_service, mcp_subscription, review, review_node |
| `agent_` | 8 | def, config, binding, api, api_key, subscription, memory, workspace_material |
| `org_` | 6 | department, user, role, user_role, permission, role_permission |
| `mon_` | 6 | trace, span, audit_log, backup_record, drill_record, failover_record |
| `sec_` | 7 | tool_policy, outbound_policy, sensitive_word, mask_rule, hitl_node, hitl_history, sandbox_policy |
| `sbx_` | 5 | pool, instance, lease, base_image, operation_log |
| `model_` | 4 | provider, def, rate_limit, route |
| `eval_` | 3 | task, test_case, test_suite |
| `sess_` | 3 | session, message, artifact |
| `ten_` | 3 | tenant, quota, usage |
| `att_` | 2 | file_meta, parse_cache |

### 5.2 Redis

| Key 模式 | 用途 |
|---|---|
| `aegis:session:{userId}/{sessionId}:agent_state` | AgentState 单值（运行时主链路） |
| `aegis:session:{userId}/{sessionId}:agent_state:list` | AgentState 历史 |
| `aegis:session:{userId}/{sessionId}:_keys` | 会话内 Key 索引 |
| `aegis:store:item:{userId}/{sessionId}\0{filename}` | Workspace 文件内容 |
| `aegis:store:idx:{userId}/{sessionId}` | Workspace 文件索引 |
| `aegis:hitl:req:{sessionId}` | HITL 原始 toolCall（TTL 48h） |
| `aegis:hitl:approved:{sessionId}` | 审批标记（TTL 1h） |
| `aegis:msg:seq:{sessionId}` | 消息序号，Lua `exists→set→incr` 原子初始化 |
| `aegis:interrupt:{sessionId}` | 中断 Pub/Sub channel |
| `reply:t{tenantId}:{sessionId\|new}:{replyId}` | 客户端重试去重 SETNX（TTL 5min） |

### 5.3 Milvus

集合名 = `tenant_{tenantId}_aegis_kb_v1_{kbId}`。跨租户物理隔离。

### 5.4 MinIO

| Bucket | 内容 | 归属 |
|---|---|---|
| `aegis-attachments` | 会话附件 | runtime（`aegis.minio.bucket`） |
| `aegis-resources` | 知识库原文、资源文件 | admin（`aegis.minio.bucket`） |
| `aegis-sandbox-snapshots` | 沙箱工作区快照 | runtime（`MinioSnapshotClient`） |

---

## 六、安全架构

### 6.1 三层管控

1. **接口级** — admin `SecurityConfig` 路径授权（`RoleCode.PLATFORM_ADMIN` / `TENANT_ADMIN` / `SECURITY_ADMIN` 别名）

| 路径 | 授权 |
|---|---|
| `/api/admin/auth/login`、`/auth/refresh`、`/auth/logout`、`/actuator/**`、`/resource/mcp/services/register` | 公开 |
| `/api/admin/security/**`、`/security-admin/**` | PLATFORM_ADMIN + SECURITY_ADMIN |
| `/api/admin/tenant/**`、`/ha/**` | PLATFORM_ADMIN |
| `/api/admin/audit/**`、`/review/**`、`/agent-review/**`、`/security-approval/**`、`/model/**`、`/model-admin/**`、`/role/**`、`/user/**`、`/department/**`、`/observe/**`、`/sandbox*/**`、`/budget/**`、`/hitl/**` | PLATFORM_ADMIN + TENANT_ADMIN |
| `/api/admin/agent*/**`、`/skill*/**`、`/resource/skill/**`、`/kb*/**`、`/resource/kb/**`、`/mcp*/**`、`/resource/mcp/**`、`/tool/**` | 任意已认证用户 |
| 其他 | 已认证 |

2. **资源级** — `@ResourceOwner` + `ResourceOwnerAspect`，覆盖 AGENT / SKILL / KNOWLEDGE_BASE / MCP_SERVICE / TOOL 的 VIEW / CREATE / EDIT / DELETE / PUBLISH / MANAGE。

3. **审计级** — `@Auditable` + `AuditAspect`，写操作自动落 `mon_audit_log`。

### 6.2 角色与权限

7 个种子角色，权限点字典 `org_permission` 共 45 条（`模块:子模块:操作` 格式）。`JwtAuthenticationToken.buildAuthorities()` 做角色别名映射：

| DB 角色 | Spring Security authority | 权限数 |
|---|---|---|
| SUPER_ADMIN | ROLE_SUPER_ADMIN + ROLE_PLATFORM_ADMIN + ROLE_TENANT_ADMIN + ROLE_SECURITY_ADMIN + ROLE_RESOURCE_ADMIN | 45 |
| ENTERPRISE_ADMIN | ROLE_ENTERPRISE_ADMIN + ROLE_TENANT_ADMIN | 36 |
| SECURITY_ADMIN | ROLE_SECURITY_ADMIN | 10 |
| RESOURCE_ADMIN | ROLE_RESOURCE_ADMIN | 17 |
| AGENT_REVIEWER | ROLE_AGENT_REVIEWER | 7 |
| AGENT_CREATOR | ROLE_AGENT_CREATOR | 10 |
| EMPLOYEE | ROLE_EMPLOYEE | 7 |

### 6.3 fail-closed

| 位置 | 行为 |
|---|---|
| gateway `AuthFilter` | 无 JWT / 非法 → 401 |
| admin `JwtServerAuthenticationConverter` | 只信 JWT 解析结果，不信网关注入的身份 Header |
| `CoreTenantLineHandler` | tenantId 缺失 → 抛异常，不静默回退 0 |
| 沙箱不可用 | `aegis_execute` 返回结构化错误，**不降级宿主执行** |
| 工具策略未配置 | 等级直映兜底：L1/L2→ALLOW，L3→ASK，L4→DENY |

### 6.4 技能静态扫描

`SkillContentScanner`（aegis-core-spi）8 个维度，无 HIGH 级风险才允许通过：

| 维度 | 风险 |
|---|---|
| PROMPT_INJECTION | HIGH |
| DESTRUCTIVE | HIGH |
| EXFILTRATION | HIGH |
| OBFUSCATION | HIGH |
| SENSITIVE_CONTENT | HIGH / MEDIUM（按命中模式） |
| TOOL_PRIVILEGE | HIGH / MEDIUM |
| PERSISTENCE | MEDIUM |
| NETWORK | MEDIUM |

---

## 七、沙箱

后端 SPI `ISandboxBackend`（Docker / K8s / Process），当前配置 `aegis.runtime.sandbox.backend=k8s`，命名空间前缀 `aegis-sbx-t`。

关键配置（`aegis-runtime/src/main/resources/application.yml`）：

| 配置 | 值 | 说明 |
|---|---|---|
| `aegis.runtime.sandbox.enabled` | `true` | |
| `aegis.runtime.sandbox.backend` | `k8s` | `local` profile 同为 k8s |
| `aegis.runtime.sandbox.framework-drive.enabled` | `true` | `AegisExecuteTool` 走 `SandboxSessionHolder` + `SandboxManager` |
| `aegis.runtime.sandbox.lazy-allocation.enabled` | `true` | 构建期不分配，首次沙箱工具调用才分配 |
| `aegis.runtime.sandbox.idle-release-minutes` | `5` | 空闲释放阈值 |
| `aegis.upon.sandbox.snapshot.enabled` | `true` | 快照存储 `minio` |
| `aegis.upon.sandbox.execution-guard.enabled` | `true` | 执行互斥锁，超时 30s |

> 配置键不一致：`AegisAgentInstanceManager` 读 `aegis.sandbox.framework-drive.enabled`（未配置，默认 false）；`AegisExecuteTool` 读 `aegis.runtime.sandbox.framework-drive.enabled`（yml 为 true）。切换全框架驱动需同时改两处。

`SandboxTrigger` 用工具能力白名单判定是否需要沙箱：`aegis_execute`、`execute`、`shell`、`sh`、`bash`、`run_script`、`build_test`、`exec_attachment`、`generate_file`。

详情见 [sandbox-allocation-and-recycling.md](sandbox-allocation-and-recycling.md)。

---

## 八、关键链路

**对话执行**：`TaskController` → `ChatRequestValidator` → `TaskExecutionService`（装配 → 流式执行 → 投影 → doFinally）→ `AgentAssemblyService`（查 def/config/binding → 建 session 快照 → 取/建实例 → 装载资源）→ `HarnessAgent.streamEvents` → `HarnessEventConverter` → SSE。详见 [runtime-execution-flow.md](runtime-execution-flow.md)。

**RAG**：admin 端上传 → `FileParseEngine` 解析 → 分块（默认 500 字符 / 重叠 50）→ Embedding → Milvus 集合 `tenant_{tenantId}_aegis_kb_v1_{kbId}`；运行时 `AegisRagMiddleware.onSystemPrompt` 检索（默认 topK=5）后注入系统提示词。

**安全策略热更新**：admin 改配置 → `AdminSecurityConfigPublisher` 发 Redis Pub/Sub `aegis:config:change` → runtime 订阅后删缓存，下次请求回源 MySQL。

**资源变更感知**：三条路径，详见 [resource-management.md](resource-management.md)。
