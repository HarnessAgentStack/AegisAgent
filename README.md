# Aegis

[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)
[![AgentScope](https://img.shields.io/badge/AgentScope-2.0.2-blue)](https://github.com/agentscope-ai/agentscope-java)

企业级多租户 AI Agent 平台。

---

## 产品概览

![工作台对话](docs/images/workbench-chat.png)
![智能体管理](docs/images/agent-list.png)
![沙箱池管理](docs/images/sandbox.png)
![安全策略](docs/images/security-policy.png)
![可观测链路追踪](docs/images/observability.png)

---

## 快速开始

### 前置依赖

**运行脚本会自动探测**（Docker → JDK → Maven → Node），无需配置即可跑通。
探测失败或有多个版本时，可通过两种方式覆盖（优先级：脚本默认 < 环境变量 < `aegis.conf`）：

| 依赖 | 最低版本 | 脚本如何发现 | 可覆盖的配置 |
|------|---------|-------------|-------------|
| Docker Desktop / Engine + Compose v2 | 24+ | `docker info` 直连 | — |
| JDK | 21+ | `$env:JAVA_HOME\bin\java` 或 PATH 中 `java` | `aegis.conf` → `JAVA_HOME` |
| Maven | 3.9+ | `$env:MVN_CMD` 或 PATH 中 `mvn` | `aegis.conf` → `MVN_CMD` |
| Node.js | 18+ | PATH 中 `node` / `npm` | — |

> **`aegis.conf`** 位于项目根目录，跨平台通用（bash 风格，Windows PowerShell 也能解析）。留空/注释掉 = 自动探测，取消注释 = 强制覆盖。常见场景：
> - JDK / Maven 没进 PATH：取消注释 `JAVA_HOME` / `MVN_CMD`
> - Sandbox 用远程 Docker：取消注释 `SANDBOX_DOCKER_HOST=tcp://127.0.0.1:2375`

### 一键启动

**一个脚本搞定**——基础设施 (Docker Compose) + 应用层 (本机进程) 全自动：

```powershell
# Windows PowerShell
.\aegis.ps1 start

# macOS / Linux bash
./aegis.sh start
```

脚本自动完成：
1. 探测前置依赖 (Docker / JDK / Maven / Node)，缺失则打印错误并退出
2. `docker compose up` 启动 MySQL / Redis / Nacos / MinIO / etcd / Milvus / PaddleOCR
3. **首次启动 MySQL 空数据卷时**，容器自动执行 `infra/ddl/01_schema_init.sql` + `02_seed_data.sql`（后续重启跳过）
4. `mvn clean package -DskipTests` 构建后端 JAR
5. 本机启动 gateway (:8080) / admin (:8082) / runtime (:8081) / mcp-demo (:8084)
6. 本机启动前端 (默认 vite dev :5173，加 `frontend=prod` 切静态 serve)
7. mcp-demo 启动时自动向 admin 注册自身（REST endpoint=`http://127.0.0.1:8084/api/mcp/tools`），**数据库中不需要预置 MCP 服务种子数据**

首次 `mvn package` 约 2-5 min，后续 `restart` 走增量构建。

### 常用命令

| 命令 | 说明 |
|------|------|
| `./aegis.ps1 start` | 全栈启动（后端增量构建 + 前端 dev） |
| `./aegis.ps1 start -Frontend prod` | 前端 build + serve 静态产物 |
| `./aegis.sh start frontend=prod` | 同上（bash 版用参数位置传） |
| `./aegis.ps1 stop` | 停全部（本机应用 + 基础设施容器） |
| `./aegis.ps1 appstop` | 仅停本机应用，**保留**基础设施容器 |
| `./aegis.ps1 restart` | appstop → clean build → start |
| `./aegis.ps1 build` | 仅构建后端 JAR + 前端 dist |
| `./aegis.ps1 status` | 查看全部容器/进程状态 |
| `./aegis.ps1 infra` | 仅起/停基础设施 |
| `./aegis.ps1 help` | 打印帮助 |

### 访问入口

| 入口 | URL |
|------|-----|
| **前端工作台** | http://localhost:5173 |
| 网关 | http://localhost:8080 |
| 管理后台 | http://localhost:8082 |
| 运行时 | http://localhost:8081 |
| MCP-Demo | http://localhost:8084/api/mcp/tools （工具列表）<br>http://localhost:8084/api/mcp/tools/{code}/invoke （执行工具） |
| Nacos | http://localhost:8848/nacos (nacos/nacos) |
| MinIO | http://localhost:9001 (aegis/aegis12345) |
| MySQL | localhost:3306 (root/root123, aegis/aegis123) |

**初始账号**：租户 `DEFAULT` / 用户名 `admin` / 密码 `aegis@123`（首次登录请修改）

---

## 定位

面向企业 AI Agent 落地，解决三个实际问题：智能体分类治理、工具/技能安全可控、运行时可追溯。

### 智能体双维度模型

Aegis 用两个正交字段描述智能体，二者各司其职：

| 维度 | 字段 | 取值 | 决定什么 |
|------|------|------|----------|
| **智能体类型**（agent_type） | agent_type | UNIVERSAL / APPLICATION / SYSTEM | 资源装载轨道（开放订阅 vs 封闭绑定）、实例池粒度、沙箱池路由 |
| **治理档位**（governance_tier） | governance_tier | STANDARD / ENHANCED / STRICT | 沙箱隔离强度、模型路由策略、系统提示词安全指令注入 |

#### 三类智能体（按 agent_type 划分）

| 类型 | 创建者 | 面向 | 运行时资源装载逻辑 |
|------|--------|------|---------------------|
| **通用智能体** (UNIVERSAL) | 系统内置 | 所有用户 | 开放轨道：平台内置能力 + **用户主动订阅和创建的**（技能/MCP/知识库，含草稿） |
| **应用智能体** (APPLICATION) | 业务人员 | 租户内业务团队 | 封闭轨道：仅限智能体显式绑定的资源，严格控制不越权 |
| **系统智能体** (SYSTEM) | 技术人员 | 系统集成方 | 严格封闭：仅限绑定的高安全等级资源，对外暴露 API 供自动化调用 |

#### 治理档位（按 governance_tier 划分）

| 档位 | 隔离强度 | 模型路由策略 |
|------|----------|--------------|
| **STANDARD** | 默认共享沙箱 | 普通模型（如 doubao-lite） |
| **ENHANCED** | 独立沙箱命名空间 | 增强模型（如 doubao-pro） |
| **STRICT** | 独立沙箱 + 网络隔离 | 严格模型（如加密推理），L4 工具必须本地加密模型 |

---

## 架构

```
┌─────────────────────────────────────────────────────────────────────┐
│                     React 18 SPA · Workbench + Admin                 │
└───────────────────────────┬─────────────────────────────────────────┘
                            │ HTTP / SSE
                            ▼
┌─────────────────────────────────────────────────────────────────────┐
│  Spring Cloud Gateway :8080                                          │  │   TraceId → Auth → Session粘性 → 租户解析 → 路由（无 Redis 依赖）      │
└───────────────┬──────────────────────┬──────────────────────────────┘
                │ WebClient             │
        ┌───────▼───────┐        ┌─────▼───────┐
        │ Runtime :8081 │        │ Admin :8082  │
        │ 运行时引擎     │        │ 管理后台      │
        └───────┬───────┘        └─────┬───────┘
                │                      │
        ┌───────▼──────────────────────▼───────┐
        │         AgentScope 2.0.2 内核          │
        │  ┌─────────────────────────────────┐  │
        │  │   洋葱链中间件（11 层）                │  │
        │  │ Trace → Security → Tenant        │  │
        │  │   → BindingSync → Intent → RAG   │  │
        │  │   → ContentFilter → Audit        │  │
        │  │   → SandboxHeartbeat → Memory    │  │
        │  │   → Mask                         │  │
        │  └─────────────────────────────────┘  │
        │  HarnessAgent · RuntimeContext SPI    │
        └───┬──────────┬──────────┬────────────┘
            │          │          │
        ┌───▼───┐ ┌───▼───┐ ┌───▼────┐
        │ MySQL │ │ Redis │ │ Milvus │  MinIO
        └───────┘ └───────┘ └────────┘
```

### 技术栈

| 层           | 选型                                                                          | 为什么                                                                 |
| ----------- | --------------------------------------------------------------------------- | ------------------------------------------------------------------- |
| AI Agent 内核 | [AgentScope 2.0.2 (Java)](https://github.com/agentscope-ai/agentscope-java) | 阿里巴巴开源 JVM 原生 Agent 框架，HarnessAgent + ReAct 循环 + 中间件扩展点天然适配企业安全拦截需求 |
| 后端          | Spring Boot 4 / WebFlux / MyBatis-Plus                                      | WebFlux 响应式适配 SSE 流式输出，多租户插件自动追加 tenant_id                          |
| 前端          | React 18 / Zustand / Ant Design 5                                           | Zustand 轻量状态管理，Ant Design 提供企业级表单表格组件                               |
| 中间件         | MySQL 8 / Redis 7 / Nacos 3 / Milvus 2.5 / MinIO                            | MySQL 58 表主存储，Redis 会话态+分布式锁，Nacos 服务发现（配置中心预留未启用），Milvus 分租户向量索引，MinIO 对象存储  |
| 可观测         | OTel Collector / Prometheus / Grafana                                       | TraceId 贯穿全链路，TraceStore 落 MySQL 支持会话级聚合查询                          |
| 部署          | Docker Compose + Maven 3.9 + Node 18 + 跨平台启动脚本 `aegis.ps1` / `aegis.sh` | 基础设施 (MySQL/Redis/Nacos/Milvus/MinIO) 用 Docker Compose，应用层 (gateway/admin/runtime/mcp-demo/web) 本机进程 — 避免沙箱 unix socket 污染和容器网络隔离问题 |

---

## 工程结构

### 模块依赖

```
aegis-core-domain   ←  所有模块依赖，零外部依赖
      ↑
aegis-core-spi      ←  SPI 接口 + 安全核心组件
      ↑
aegis-core-infra    ←  自动配置 + 通用组件
      ↑
aegis-dal           ←  MyBatis-Plus Mapper + TraceStore 实现
      ↑
┌─────┴─────┐
aegis-admin  aegis-runtime    （runtime 额外依赖 AgentScope 2.0.2）
```

### 模块职责

| 模块                | 做什么                                                             | 不做什么                     |
| ----------------- | --------------------------------------------------------------- | ------------------------ |
| aegis-core-domain | 纯 POJO + 枚举 + DTO，所有模块共享                                        | 不引入 Spring / MyBatis     |
| aegis-core-spi    | SPI 接口定义 + SkillContentScanner（技能八大维度安全扫描）                      | 不做数据库访问                  |
| aegis-core-infra  | 自动配置、Result 封装、租户上下文、安全策略二级缓存                                   | 不做业务逻辑                   |
| aegis-dal         | MyBatis-Plus Mapper + MysqlTraceStore + SkillSecurityScanner 门面 | 不包含业务 Service            |
| aegis-gateway     | JWT 鉴权、TraceId 生成、租户解析、SSE 路由、Fallback                          | 不调用 admin/runtime 内部 API |
| aegis-admin       | 管理端 CRUD、审核流程、启动注入 universal 智能体                                | 不承载对话执行                  |
| aegis-runtime     | AgentScope 内核装配、洋葱链中间件、沙箱管理、会话执行                                | 不承载管理端 CRUD              |

### 目录

```
aegis/
├── aegis-platform-backend/         Spring Boot 多模块 (Maven)
│   ├── aegis-gateway/              网关 · :8080
│   ├── aegis-admin/                管理后台 · :8082
│   ├── aegis-runtime/              运行时引擎 · :8081
│   ├── aegis-core/                 domain / spi / infra
│   ├── aegis-dal/                  MyBatis-Plus Mapper + TraceStore
│   └── aegis-mcp-demo/             MCP 协议示例
│
├── aegis-platform-web/             React 18 + Ant Design 5
│   ├── pages/workbench/            对话工作台
│   ├── pages/admin/                管理后台
│   ├── pages/resource/             资源治理（技能/工具/知识库/MCP）
│   └── pages/security/             安全治理
│
├── infra/                          基础设施
│   ├── docker-compose.yml          MySQL/Redis/Nacos/Milvus/MinIO/PaddleOCR
│   ├── ddl/                        01_schema_init.sql + 02_seed_data.sql
│   └── init/docker/                OTel / Prometheus 配置
│
├── docs/                           文档
├── aegis.ps1                       跨平台统一启动脚本 (Windows PowerShell)
├── aegis.sh                        跨平台统一启动脚本 (macOS/Linux bash)
└── README.md
```

---

## 文档

| 文档模块   | 文档                                                                                                                                                                                                      |
| ------ | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| 产品功能说明 | [product.md](docs/product.md)                                                                                                                                                                           |
| 系统部署文档 | [deployment.md](docs/deployment.md)                                                                                                                                                                     |
| 技术架构说明 | [architecture.md](docs/architecture.md)                                                                                                                                                                 |
| 核心数据模型 | [data-model.md](docs/data-model.md)                                                                                                                                                                     |
| 核心流程剖析 | [runtime-execution-flow.md](docs/runtime-execution-flow.md)<br>[sandbox-allocation-and-recycling.md](docs/sandbox-allocation-and-recycling.md)<br>[resource-management.md](docs/resource-management.md) |

---

## 版本状态

alpha 阶段，核心链路（对话执行 / 沙箱分配回收 / 安全策略引擎）已完成并通过集成测试。产品功能和企业特性（RBAC / 审核流程 / 可观测面板）持续迭代中。

---

## License

MIT — 见 [LICENSE](LICENSE)
