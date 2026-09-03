# Aegis 技术架构说明

> 适用版本：0.1.0-alpha.1 ｜ 最后更新：2026-08-31

---

## 一、定位与设计哲学

Aegis 不是一个从零造轮子的 Agent 框架，而是一个**面向企业 AI Agent 场景的工程化集成平台**——底层深度整合 AgentScope 2.0.2 的 Harness 执行内核，上层叠加企业场景必备的多租户治理、安全防线、可观测链路和资源分轨装载。

核心设计决策：

| 决策 | 理由 |
|---|---|
| AgentScope 2.0.2 做执行内核 | HarnessAgent 的 ReAct 循环 + 中间件拦截链 + 权限引擎，天然适配企业级 Agent 编排需求，比 LangChain 2.x 的抽象层更轻量、比 Dify 的单体模型更可定制 |
| 网关 / 管理端 / 运行时三进程分离 | 管理端是传统 CRUD（Spring Boot Web + MyBatis-Plus），运行时是高并发 SSE + 沙箱管理（Spring Boot WebFlux），网关是纯流量转发（Spring Cloud Gateway），三者负载特征完全不同，拆开独立扩缩 |
| SPI 接口定义在 aegis-core-spi | 沙箱后端、向量存储、Embedding、分布式锁、对象存储、MCP ToolProvider 全部通过 SPI 抽象，运行时只依赖接口不依赖实现。开源版提供 Docker 沙箱 + Milvus + MinIO，生产可以替换为 K8s + Qdrant + Ceph |
| 三类智能体从运行时行为差异化 | agent_type（UNIVERSAL/APPLICATION/SYSTEM）决定资源装载轨道，governance_tier（STANDARD/ENHANCED/STRICT）决定沙箱隔离强度——两个正交字段，各司其职，从运行时资源装载、中间件配置、安全策略全链路差异化 |

---

## 二、整体架构

### 2.1 三层架构图

```
┌─────────────────────────────────────────────────────────────────┐
│                         前端（React 18 + Ant Design 5）          │
│                                                                 │
│  ┌────────────────────────────────────────────────────────────┐ │
│  │  工作台对话  ·  智能体管理  ·  资源治理  ·  安全中心        │ │
│  │  模型管理  ·  可观测面板  ·  沙箱管理  ·  审核中心          │ │
│  └────────────────────────────┬───────────────────────────────┘ │
│                               │ HTTP / SSE                       │
│                          ┌─────▼─────┐                           │
│                          │   Nginx   │  静态资源 + /api + /sse   │
│                          └─────┬─────┘                           │
└───────────────────────────────┼─────────────────────────────────┘
                                │
┌───────────────────────────────┼─────────────────────────────────┐
│                       应用层（三进程分离）                       │
│                               │                                  │
│  ┌───────────────────────┐    │                                  │
│  │   aegis-gateway       │    │  Spring Cloud Gateway            │
│  │   WebFlux + Nacos     │    │                                  │
│  │   ┌─ AuditEntryFilter │    │  TraceId · JWT · SessionId      │
│  │   ├─ AuthFilter       │    │  租户解析 · SSE 转发            │
│  │   ├─ SessionIdFilter  │    │                                  │
│  │   └─ TenantResolveFilter│   │                                  │
│  └──────────┬────────────┘    │                                  │
│             │                  │                                  │
│  ┌──────────▼────────────┐    │    ┌──────────────────────────┐ │
│  │   aegis-admin         │    │    │   aegis-runtime           │ │
│  │   Spring Boot 4 + MVC │    │    │   Spring Boot 4 + WebFlux │ │
│  │   MyBatis-Plus 3.5    │    │    │   ↓ AgentScope 2.0.2      │ │
│  │                       │    │    │   ↓ HarnessAgent         │ │
│  │   CRUD + RBAC + 审核  │    │    │   ↓ ReAct + 中间件链     │ │
│  │   沙箱池管理 + 快照    │    │    │   ↓ Toolkit + 权限引擎   │ │
│  │   安全策略配置 + 审计  │    │    │                          │ │
│  └──────────┬────────────┘    │    └──────────────┬───────────┘ │
│             │                  │                   │              │
│  ┌──────────▼──────────────────▼───────────────────▼──────────┐ │
│  │                    aegis-core（共享层）                      │ │
│  │  ┌─ aegis-core-domain  ─┐  ┌─ aegis-core-infra  ─┐        │ │
│  │  │ 实体 / DTO / 枚举    │  │ JWT / 租户拦截 /     │        │ │
│  │  │ 事件 / 上下文        │  │ MyBatis / Milvus     │        │ │
│  │  └──────────────────────┘  │ 自动配置              │        │ │
│  │  ┌─ aegis-core-spi  ─────┐ └─────────────────────┘        │ │
│  │  │ ISandboxBackend       │                                  │ │
│  │  │ IVectorStore          │                                  │ │
│  │  │ IObjectStorage        │                                  │ │
│  │  │ EmbeddingService      │                                  │ │
│  │  │ IDistributedLock      │                                  │ │
│  │  │ MCP ToolProvider      │                                  │ │
│  │  └───────────────────────┘                                  │ │
│  └──────────────────────────────────────────────────────────────┘ │
│                                  │                               │
│  ┌───────────────────────────────┴──────────────────────────────┐│
│  │                    aegis-dal（数据访问层）                     ││
│  │  MyBatis-Plus Mapper · MySQL TraceStore · SkillSecurityScanner││
│  └───────────────────────────────────────────────────────────────┘│
└───────────────────────────────────────────────────────────────────┘
                                │
┌───────────────────────────────┼───────────────────────────────────┐
│                         基础设施层                                  │
│                                                                   │
│  ┌─────────┐  ┌─────────┐  ┌─────────┐  ┌─────────┐              │
│  │ MySQL 8 │  │Redis 7  │  │Nacos 3  │  │ Minio   │              │
│  │ 业务数据│  │ 会话/缓存│  │服务发现│  │ 对象存储 │              │
│  └─────────┘  └─────────┘  └─────────┘  └─────────┘              │
│  ┌─────────┐  ┌─────────┐  ┌─────────┐  ┌─────────┐              │
│  │ Milvus  │  │ etcd    │  │Prometheus│  │Grafana  │              │
│  │ 向量索引 │  │ 分布式锁│  │ 指标采集 │  │ 可视化  │              │
│  └─────────┘  └─────────┘  └─────────┘  └─────────┘              │
│  ┌─────────────────────────────────────────────────────────────┐  │
│  │ 沙箱后端（SPI 实现层）                                         │  │
│  │  Docker 模式：docker-java 3.4  ─  K8s 模式：fabric8 7.4       │  │
│  └─────────────────────────────────────────────────────────────┘  │
└───────────────────────────────────────────────────────────────────┘
```

### 2.2 进程职责

| 进程 | 技术栈 | 职责 | 依赖 |
|---|---|---|---|
| **gateway** | Spring Cloud Gateway (WebFlux) | 流量入口、JWT 鉴权、TraceId 注入、租户解析、SSE 转发 | Nacos（服务发现） |
| **admin** | Spring Boot 4 (WebFlux) + MyBatis-Plus | 管理端全部 CRUD、三级 RBAC（角色/权限资源）、安全策略配置、沙箱池管理、审核中心、审计日志 | MySQL、Redis、Nacos、MinIO、fabric8 |
| **runtime** | Spring Boot 4 WebFlux + AgentScope 2.0.2 | 对话执行、AgentScope ReAct 循环、中间件链、SSE 流式、沙箱分配 | MySQL、Redis、Nacos、Milvus、MinIO、docker-java / fabric8 |
| **mcp-demo** | Spring Boot 4 | 独立演示服务，注解式暴露 MCP tools，自动向 admin 注册 | admin API |

> 三进程通过 **Nacos 服务发现 + HTTP 调用** 协作，共享同一个 MySQL 和 Redis 实例，不通过中间件直接通信。

---

## 三、AgentScope 2.0.2 集成

### 3.1 为什么选 AgentScope Harness

AgentScope 2.0 把执行内核拆成了 `agentscope-core`（抽象层）和 `agentscope-harness`（实现层），HarnessAgent 提供了一套面向生产的 ReAct Agent 实现：

- **ReAct 循环**：Think → Act → Observe → Think，内置工具调用、多模态输入、并行工具调用
- **中间件拦截链**：**5 个拦截点**（onAgent / onReasoning / onActing / onModelCall / onSystemPrompt），比 LangChain 2.x 的回调粒度更聚焦
- **权限引擎**：`PermissionContextState` + `PermissionRule` + `PermissionBehavior`，天然支持 ALLOW / REQUIRE_APPROVAL / DENY 三种策略
- **沙箱抽象**：`SandboxFilesystemSpec` + `IsolationScope`（USER / AGENT / GLOBAL / SESSION），自带会话级沙箱生命周期管理（`RESIDENT` 非 IsolationScope 枚举值，而是 SYSTEM 智能体常驻实例的 slotKey 前缀 + 实例状态，见 sandbox-allocation-and-recycling.md §澄清）
- **SPI 扩展**：ModelProvider / VectorStore / DistributedStore 通过 `META-INF/services` 发现，和 Spring 容器可以桥接

Aegis 没有 fork AgentScope 源码，而是**通过 SPI + Spring Bean 注入**方式深度集成，AgentScope 内核保持原样，所有企业定制化逻辑通过中间件和 SPI 实现挂载。

### 3.2 依赖清单

| artifact | 作用 | 版本 |
|---|---|---|
| `agentscope-core` | 核心抽象层（MiddlewareBase / Toolkit / AgentEvent） | 2.0.2 |
| `agentscope-harness` | HarnessAgent 实现（ReAct / 权限引擎 / 沙箱 / 内存压缩） | 2.0.2 |
| `agentscope-extensions-redis` | DistributedStore 的 Redis 实现（会话状态持久化） | 2.0.2 |
| `agentscope-extensions-oss` | MinIO / S3 OSS 实现（沙箱快照） | 2.0.2 |
| `agentscope-extensions-model-openai` | OpenAI 兼容协议模型实现 | 2.0.2 |

全部在 `aegis-runtime` 模块声明，admin 和 gateway 不直接依赖。

### 3.3 SPI 实现

AgentScope 的 SPI 通过 Java `ServiceLoader` 发现，Aegis 通过 Spring Boot 自动配置桥接到 Spring 容器：

| AgentScope SPI | Aegis 实现 | 做什么 |
|---|---|---|
| `io.agentscope.core.model.spi.ModelProvider` | `AegisModelProvider`（META-INF/services 注册） | 动态模型路由：从 DB 读取租户配置的模型 Provider 和实例，按 `aegis:{tier}:{tenantId}` 路由 |
| `io.agentscope.core.spi.VectorStore` | `MilvusVectorStoreAdapter`（aegis-core-infra） | 知识库 RAG 向量存储 |
| `io.agentscope.core.spi.EmbeddingService` | `ArkEmbeddingService`（aegis-dal） | Embedding 调用（通义 Ark） |
| `io.agentscope.core.spi.TraceStore` | `MysqlTraceStore`（aegis-dal） | 链路 Span 落 MySQL，支持按会话/用户/智能体查询 |
| `io.agentscope.harness.agent.DistributedStore` | Redis 实现（agentscope-extensions-redis） | 会话状态持久化、实例池共享状态 |
| `io.agentscope.harness.agent.sandbox.SandboxSnapshotSpec` | MinIO 实现（agentscope-extensions-oss） | 沙箱快照持久化 |

### 3.4 中间件链

Aegis 中间件全部继承 `MiddlewareBase`，实现 `OrderedMiddleware` 接口声明执行顺序，经 Spring 注入 `List<MiddlewareBase>` 排序后传入 `HarnessAgent.Builder.middlewares()`。

```
洋葱链（从外到内执行，按 AgentScope 降序 = order 值大的先执行）
│
├── AegisTraceMiddleware           ← order=95  TraceId 贯穿全链路
├── AegisBindingSyncMiddleware     ← order=75  绑定指纹比对 + Workspace 重物化
├── AegisRagMiddleware             ← order=70  RAG 检索，注入相关知识片段到系统提示词
├── AegisMaskMiddleware            ← order=50  数据脱敏
└── AegisAuditLogMiddleware        ← order=30  审计日志（所有关键操作自动记录）
    │
    ▼
HarnessAgent ReAct 循环（AgentScope 2.0.2 内核）
```

> 注：Phase 2 精简后仅保留 5 层。Security/TenantIsolation/Intent/ContentFilter/SandboxHeartbeat/Memory 中间件已删除——工具安全决策收敛到 AgentScope 原生 PermissionEngine（`AegisPermissionRuleLoader` 从 sec_tool_policy 映射），记忆由 HarnessAgent 原生 MemoryConfig 承载。

每个中间件可以在 5 个拦截点独立挂载逻辑，常见挂载：

| 拦截点 | 用途 |
|---|---|
| `onSystemPrompt` | 注入租户隔离上下文、RAG 知识片段、脱敏规则 |
| `onModelCall` | 模型调用前审计、限流检查、内容过滤 |
| `onActing` | 工具调用前安全策略评估、HITL 审批、权限检查 |
| `onAgent` | 会话级资源（MCP / 附件）初始化 |

### 3.5 Agent 实例池

`AegisAgentInstanceManager` 是运行时最核心的组件——管理 HarnessAgent 实例的创建、复用和回收。

**实例粒度（对齐 AgentScope IsolationScope）**：

| 智能体类型 | 池 key | 共享粒度 | IsolationScope |
|---|---|---|---|
| UNIVERSAL（通用智能体） | `userId` | 每个用户一个独立实例 | USER |
| APPLICATION（应用智能体） | `agentId` | 同类型智能体多用户共享 | AGENT |
| SYSTEM（系统智能体） | `agentId`（RESIDENT slotKey `aegis:resident:sys:{agentId}`） | 每智能体独立 RESIDENT 常驻实例，agent_api 预绑定，永不参与动态分配/回收 | `sbx_instance.isolation_scope=GLOBAL` + `status=RESIDENT`（RESIDENT 是实例状态机状态，非 IsolationScope 枚举值；原"E-04 错误映射"论断不成立，落库 GLOBAL 为常驻实例隔离范围设计值） |

**懒刷新机制**：池命中时通过 `bindingFingerprint`（绑定资源的版本指纹）比对，一致 → 直接复用（<50ms），不一致 → 走 `refreshToolkit` 懒刷新 Toolkit 配置。

**回收机制**：
- LRU：池满时驱逐最久未使用
- TTL：空闲超过 **2 小时**自动驱逐（AgentPoolManager expireAfterAccess=2h 硬编码）
- 优雅关闭：驱逐前调用 `agent.close()`，触发 AgentScope 内核的 AgentState 落盘到 Redis

---

## 四、模块架构

### 4.1 后端模块依赖

```
aegis-platform-backend（Maven Aggregator）
│
├── aegis-core                    ← 共享层，不依赖任何业务模块
│   ├── aegis-core-domain         ← 实体 / DTO / 枚举 / 事件 / 上下文
│   ├── aegis-core-spi            ← SPI 接口（沙箱后端、向量存储、对象存储等）
│   └── aegis-core-infra          ← 自动配置（JWT、MyBatis、Milvus、租户拦截）
│
├── aegis-dal                     ← 数据访问层，依赖 core-infra
│   ├── MyBatis-Plus Mappers（按领域分目录：agent / resource / security / session / sandbox ...）
│   ├── MysqlTraceStore           ← AgentScope TraceStore SPI 实现
│   └── SkillSecurityScanner      ← 静态安全扫描（八大维度）
│
├── aegis-gateway                 ← 网关，仅依赖 core-domain + core-infra
│   └── 4 个 GlobalFilter（AuditEntry → Auth → SessionId → TenantResolve）
│
├── aegis-admin                   ← 管理端，依赖 dal + core-infra
│   └── CRUD + RBAC + 沙箱池管理 + 安全策略 + 审核中心 + 审计
│
├── aegis-runtime                 ← 运行时，依赖 dal + core-infra + AgentScope 2.0.2
│   ├── integration/agent         ← HarnessAgent 实例池、工具桥接、事件转换
│   ├── integration/middleware    ← 10 个 AegisMiddleware 实现 + Chain 装配
│   ├── integration/model         ← AegisModelProvider SPI 实现 + 动态模型路由
│   ├── integration/skill         ← AegisSkillRepository（技能分轨装载）
│   ├── integration/tool          ← 内置工具（execute / http / generate_file / mcp）
│   └── infrastructure            ← 文件解析（PDF/Office/图片/OCR）、沙箱心跳
│
└── aegis-mcp-demo                ← MCP 演示服务，独立部署
```

**依赖方向严格单向**：`gateway → core`、`admin → dal → core`、`runtime → dal → core`，不存在循环依赖。ArchUnit 测试在 `aegis-admin/src/test/java/.../ArchitectureGuardTest.java` 中强制守护。

### 4.2 前端模块结构

```
aegis-platform-web（Vite + React 18 + Ant Design 5）
│
├── src/
│   ├── api/                      ← 后端接口封装（按领域分文件）
│   ├── components/
│   │   ├── business/             ← 业务组件（ModelTierSelector / GovernanceTierTag / ...）
│   │   ├── chat/                 ← 对话组件（EnhancedMessageInput / ExecutionTimeline / ...）
│   │   ├── common/               ← 通用组件（BigTabs / PageHeader / ErrorBoundary / ...）
│   │   └── layout/               ← 布局（Header / Sidebar / MainLayout）
│   ├── pages/
│   │   ├── admin/                ← 管理端页面（observe / sandbox / ha）
│   │   ├── agent/                ← 智能体页面（list / create / detail）
│   │   ├── resource/             ← 资源治理（tool / skill / knowledge / mcp）
│   │   ├── security/             ← 安全中心（敏感词 / 工具策略 / 脱敏 / 出站）
│   │   ├── model/                ← 模型管理（provider / instance / 限流）
│   │   ├── workbench/            ← 工作台对话（核心页面）
│   │   ├── review/               ← 审核中心
│   │   ├── organization/         ← 组织架构
│   │   ├── role/ · tenant/ · audit/ · login/ · profile/
│   ├── hooks/                    ← useAuth / usePermission / useTurnStream / useExecutionTimeline
│   ├── stores/                   ← Zustand（authStore / agentStore / tenantStore）
│   ├── router/                   ← routes + AuthGuard
│   └── types/                    ← TypeScript 类型定义（按领域分）
│
└── nginx.conf                     ← 生产部署时静态资源 + /api + /sse 反代
```

### 4.3 技术栈版本速查

**后端（Java 21 + Spring Boot 4）**

| 分类 | 组件 | 版本 | 选型理由 |
|---|---|---|---|
| Agent 执行内核 | AgentScope Harness | 2.0.2 | ReAct + 中间件 + 权限引擎，生产级 |
| Web 框架 | Spring Boot | 4.0.0 | runtime 用 WebFlux（适配 SSE），admin 用 MVC（CRUD 简单高效） |
| 微服务 | Spring Cloud Alibaba Nacos | 2025.1.0.0 | 服务发现 + 配置中心，比 Eureka 更轻量 |
| 网关 | Spring Cloud Gateway | 2025.1.0 | 基于 WebFlux，和 runtime 同技术栈 |
| ORM | MyBatis-Plus | 3.5.16 | 多租户插件 + 代码生成器 + 自动填充，和 Spring Boot 4 兼容 |
| 缓存 | Redis + Redisson | 7 / 3.37 | 会话状态、分布式锁、AgentScope DistributedStore |
| 向量存储 | Milvus SDK | 2.5.0 | 生产级向量数据库，比 pgvector 性能高一个数量级 |
| 对象存储 | MinIO | 8.5.14 | 沙箱快照、附件存储，AgentScope OSS SPI 原生支持 |
| 沙箱后端 | docker-java / fabric8 | 3.4 / 7.4 | Docker 模式本地开发，K8s 模式生产 |
| 可观测 | Micrometer + Prometheus | — | Spring Boot Actuator 内置，指标自动采集 |
| 架构守护测试 | ArchUnit | 1.3.0 | 架构守护测试，强制依赖方向 |

**前端**

| 组件 | 版本 | 选型理由 |
|---|---|---|
| React | 18.3.1 | 生态成熟 |
| Ant Design | 5.22.0 | 企业级 UI 组件库，表格/表单/抽屉开箱即用 |
| Vite | latest | 热更新快 |
| Zustand | 5.0.2 | 轻量状态管理，比 Redux 简单 |
| React Query | 5.62.0 | 服务端状态管理，自动缓存 + 轮询 |

**基础设施**

| 组件 | 版本 | 说明 |
|---|---|---|
| MySQL | 8.0 | 业务数据（lower-case-table-names=1） |
| Redis | 7-alpine | 缓存 + 分布式锁 + AgentScope DistributedStore |
| Nacos | 3.2.2 | 服务发现（配置中心预留未启用，runtime config.enabled=false） |
| MinIO | latest | 对象存储 |
| Milvus | 2.5.0 | 向量索引 |
| etcd | 3.5 | Milvus 元数据存储 |
| Prometheus / Grafana / OTel Collector | latest | 可观测三件套（profile 可选） |

---

## 五、核心能力概览

### 5.1 运行时对话链路

```
前端发 ChatRequest
    │
    ▼
Gateway 过滤器链（4 层，按 getOrder() 升序执行）
    │  AuditEntryFilter → 生成 TraceId
    │  AuthFilter → JWT 鉴权
    │  SessionIdFilter → SessionId 生成 / 校验
    │  TenantResolveFilter → 解析租户 ID
    ▼
Runtime AgentAssemblyService
    │  加载智能体模板 → 版本快照 → 构建运行时上下文
    ▼
AegisAgentInstanceManager.acquireOrBuild
    │  算 poolKey → 实例池查询 → 懒刷新判定 → 构建 / 复用 HarnessAgent
    ▼
Spring 注入 List<MiddlewareBase>
    │  5 个中间件按 order 排序装配
    ▼
HarnessAgent.replyAsync()
    │  ReAct 循环 → AgentEvent 事件流
    ▼
HarnessEventConverter.convertMany()
    │  AgentScope 事件 → Aegis SSE 事件
    ▼
SseEmitter 推送到前端
```

### 5.2 沙箱分配链路

```
ToolCall: aegis_execute（需要沙箱）
    │
    ▼
AegisExecuteTool（Python 脚本包装 + Base64 传输）
    │
    ▼
AegisSandboxPoolExecutor
    │  查池（sbx_pool: 租户 STANDARD ENABLED）→ 探活复用实例（sbx_instance）
    │  无可用实例时池内扩容（createInPool）
    ▼
ISandboxBackend（SPI）
    │  Docker: docker-java create container
    │  K8s: fabric8 create pod in namespace
    ▼
执行代码 → 成功回写心跳 / 失败标记 ABNORMAL → 返回结果
    │
    ▼
admin SandboxReconcileScheduler（预热补充/缩容销毁/异常修复）
```

### 5.3 安全策略评估链路

```
AgentScope PermissionEngine（原生内核）
    │  评估序: deny → ask → 工具自检 → allow
    │  规则来源: AegisPermissionRuleLoader（sec_tool_policy → PermissionRule）
    ▼
策略评估
    │  ToolPolicy（工具类型 × 安全等级 → ALLOW/ASK/DENY）
    │  OutboundPolicy（出站域名黑白名单 + SSRF）
    ▼
决策
    │  ALLOW → 继续执行
    │  ASK → HitlFlowService 会话挂起 + 前端弹窗（HITL）
    │  DENY → 拦截 + 审计日志
```

### 5.4 安全与权限架构

Admin 服务采用三层 RBAC + fail-closed 安全策略：

#### 三层权限架构

1. **接口级**：SecurityConfig 路径授权（角色粒度，hasRole/hasAnyRole）
   - 公开路径：/api/admin/auth/**、/api/admin/actuator/**、/api/admin/resource/mcp/services/register
   - 安全管控（PLATFORM_ADMIN + SECURITY_ADMIN）：/api/admin/security/**
   - 租户管理（仅 PLATFORM_ADMIN = SUPER_ADMIN）：/api/admin/tenant/**
   - 角色/用户/部门/审计/模型/沙箱/HITL（PLATFORM_ADMIN + TENANT_ADMIN）
   - 资源管理（所有已认证用户）：/api/admin/agent/**、skill/**、kb/**、mcp/**

2. **资源级**：@ResourceOwner 注解 + ResourceOwnerAspect AOP
   - 资源类型：AGENT / SKILL / KNOWLEDGE_BASE / MCP_SERVICE / TOOL
   - 权限级别：VIEW / CREATE / EDIT / DELETE / PUBLISH / MANAGE
   - 覆盖全部资源 Controller（Agent/Skill/KB/Mcp 共 30+ 注解）

3. **审计级**：@Auditable 注解 + AuditAspect AOP
   - 写操作自动落 mon_audit_log（CREATE/UPDATE/DELETE/APPROVE/PUBLISH）

#### 角色编码统一（RoleCode 常量类）

DB 种子定义 7 个权威角色，JwtAuthenticationToken 自动注入 Spring Security 别名：

| 种子角色 | Spring Security Authority | 语义 |
|---|---|---|
| SUPER_ADMIN | ROLE_SUPER_ADMIN + ROLE_PLATFORM_ADMIN + ROLE_TENANT_ADMIN + ROLE_SECURITY_ADMIN + ROLE_RESOURCE_ADMIN | 全平台最高权限 |
| ENTERPRISE_ADMIN | ROLE_ENTERPRISE_ADMIN + ROLE_TENANT_ADMIN | 租户内最高权限 |
| SECURITY_ADMIN | ROLE_SECURITY_ADMIN | 安全策略/审计/脱敏 |
| RESOURCE_ADMIN | ROLE_RESOURCE_ADMIN | 资源审核发布 |
| AGENT_REVIEWER | ROLE_AGENT_REVIEWER | 智能体审核 |
| AGENT_CREATOR | ROLE_AGENT_CREATOR | 智能体创建编辑 |
| EMPLOYEE | ROLE_EMPLOYEE | 基础业务操作 |

SecurityConfig 使用 PLATFORM_ADMIN/TENANT_ADMIN/SECURITY_ADMIN 别名，由 JwtAuthenticationToken.buildAuthorities() 自动完成映射。

#### fail-closed 安全策略

| 组件 | 策略 | 说明 |
|---|---|---|
| Gateway AuthFilter | fail-closed | 无 JWT / JWT 非法 → 401 |
| Admin JwtServerAuthenticationConverter | fail-closed | 仅信任 JWT 解析结果，不再信任网关注入的身份 Header |
| CoreTenantLineHandler | fail-closed | tenantId 缺失 → 抛 IllegalStateException（防止静默回退 tenant_id=0 造成越权） |
| AuthService.login() | 先定位再绑定 | ten_tenant（ignoreTables）→ bind TenantContext → Mapper 查询 → clear |

### 5.5 RAG 链路

```
知识库创建（admin 端）
    │  文档上传 → 分块 → Embedding → Milvus 建索引（tenant_{id}_{kbCode}）
    ▼
运行时对话
    │  AegisRagMiddleware（onSystemPrompt 拦截点）
    │  提取用户 query → Embedding → Milvus 检索 TOP-K
    ▼
注入系统提示词
    │  相关片段拼接到系统提示词末尾，格式化标记来源
    ▼
HarnessAgent ReAct 循环使用增强后的系统提示词
```

---

## 六、数据库与存储

按领域前缀分模块，共 58 张表。DDL 在 `infra/ddl/01_schema_init.sql`，种子数据在 `infra/ddl/02_seed_data.sql`。详细模型关系与字段说明见 [data-model.md](data-model.md)。

### 6.1 MySQL 领域全景

| 领域前缀 | 表数 | 核心表 |
|---|---|---|
| `org_` | 6 | department / user / role / user_role / **permission** / **role_permission** |
| `ten_` | 3 | tenant / quota / usage |
| `agent_` | 8 | agent_def / agent_config / agent_binding / agent_api / agent_subscription |
| `res_` | 14 | tool / skill(含 version+subscription) / knowledge_base(含 document+chunk+process_progress+session_summary+subscription) / mcp_service(含 subscription) / review(+review_node) |
| `model_` | 4 | model_provider / model_def / model_rate_limit / model_route |
| `sess_` | 3 | session / session_message / session_artifact |
| `sbx_` | 5 | sandbox_pool / sandbox_instance / sandbox_lease / sandbox_base_image / sandbox_operation_log |
| `sec_` | 6 | tool_policy / outbound_policy / sensitive_word / mask_rule / hitl_node / hitl_history |
| `mon_` | 6 | trace / span / audit_log / backup_record / drill_record / failover_record |
| `att_` | 2 | file_meta / parse_cache |
| `eval_` | 3 | task / test_case / test_suite |

> RBAC 采用**数据驱动的三级模型**：用户 → 角色（org_user_role）→ 权限（org_role_permission INNER JOIN org_permission）。org_permission 是平台共享权限字典（tenant_id=0 全租户可见，共 45 个预置权限点覆盖 7 大模块），org_role_permission 是角色-权限关联（SUPER_ADMIN 拥有全部 45 个权限）。预置 7 个种子角色由 RoleCode 常量类统一：SUPER_ADMIN / ENTERPRISE_ADMIN / SECURITY_ADMIN / RESOURCE_ADMIN / AGENT_REVIEWER / AGENT_CREATOR / EMPLOYEE。登录时 AuthService.computePermissionsFromDb() 聚合 DB 权限编码写入 JWT payload。

### 6.2 Redis（会话态 + 缓存）

| Key Pattern | 用途 | TTL |
|---|---|---|
| `agentscope:distributed:state:*` | AgentScope 会话状态持久化（DistributedStore 实现） | 会话级 |
| `agentscope:memory:*` | AgentScope 跨会话记忆摘要 | 会话级 |
| `aegis:rate_limit:*` | 限流计数器 | 窗口级 |
| `aegis:tenant:{id}:cache:*` | 租户维度配置缓存 | 30min |

### 6.3 Milvus（向量索引）

按租户 + 知识库编码分索引，避免跨租户检索：

```
索引名: tenant_{tenantId}_{kbCode}
集合字段: chunk_id / embedding / content / doc_id / tenant_id
```

### 6.4 MinIO（对象存储）

| Bucket | 内容 | 生命周期 |
|---|---|---|
| `aegis-attachments` | 对话附件（图片 / 文档原始文件） | 规划目标 90 天（当前版本未落地 ILM 规则） |
| `sandbox-snapshots` | 沙箱会话快照（AgentScope OSS SPI） | 会话级 |
| `artifacts` | 智能体工作区产物（generate_file 工具输出） | 永久 |
