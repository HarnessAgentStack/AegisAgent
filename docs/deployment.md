# Aegis 部署指南

> 适用版本：0.1.0-alpha.1 ｜ 最后更新：2026-09-04
> 部署形态：**基础设施容器化 + 应用层本机进程**。当前仓库**不提供**应用层 Docker 镜像与 compose 编排。

---

## 1. 环境要求

| 项 | 要求 |
|---|---|
| Docker Desktop / Engine | 24+，含 Compose v2 |
| K8s 集群 | 沙箱后端默认 `k8s`，本机开发用 Docker Desktop 内置 K8s 即可（`~/.kube/config`） |
| JDK | 21+ |
| Maven | 3.9+ |
| Node.js | 18+（仅前端） |
| 内存 | ≥ 8 GB（Milvus + 4 个 JVM） |
| 磁盘 | ≥ 20 GB（镜像 + 数据卷） |

---

## 2. 一键启动

```powershell
.\aegis.ps1 start              # Windows PowerShell
./aegis.sh start               # macOS / Linux bash
```

脚本执行顺序：

1. 探测 Docker / JDK / Maven / Node，缺失即报错退出
2. `docker compose up -d mysql redis nacos minio etcd milvus`
3. 等核心端口就绪（MySQL 120s / Redis 30s / Nacos 120s / Milvus 60s）
4. 预拉沙箱镜像 `python:3.11-slim`、`python:3.9-slim`
5. `mvn clean package -DskipTests`
6. 启动前端（dev 默认 `vite`；`prod` 走 `vite build` + `serve`）
7. 启动 gateway / admin / runtime / mcp-demo，统一带 `--spring.profiles.active=local --spring.cloud.nacos.config.enabled=false`

> `start` 与 `restart` 均执行 clean 构建（无增量）。

### 命令

| 命令 | 说明 |
|---|---|
| `.\aegis.ps1 start` | 起基础设施 + 本机应用（前端 dev） |
| `.\aegis.ps1 start -Frontend prod` | 前端 build + serve（:80，占用则 :8088） |
| `./aegis.sh start frontend=prod` | bash 版等价写法 |
| `.\aegis.ps1 stop` | 停全部（应用 + 容器） |
| `.\aegis.ps1 appstop` | 仅停应用，保留容器 |
| `.\aegis.ps1 restart` | appstop → clean 构建 → start |
| `.\aegis.ps1 build` | 仅构建 |
| `.\aegis.ps1 status` | 状态 |
| `.\aegis.ps1 infra` | 仅操作基础设施 |

停止时脚本按端口杀进程，再按命令行匹配 `aegis-(gateway|admin|runtime|mcp-demo)` 兜底清理，并校验 `target/*.jar` 文件锁已释放。

### 环境变量覆盖

优先级：脚本默认值 < 本机环境变量 < `aegis.conf`。`aegis.conf` 位于仓库根目录（bash 风格，PowerShell 也能解析），留空或注释 = 自动探测：

```bash
JAVA_HOME="D:\Program Files\Java\jdk-21"
MVN_CMD="D:\maven\apache-maven-3.9.16\bin\mvn.cmd"
SANDBOX_DOCKER_HOST=npipe:////./pipe/docker_engine
```

基础设施容器读取 `infra/.env`（首次 `cp infra/.env.example infra/.env`）。完整变量清单见 [infra/ENV_VARS.md](../infra/ENV_VARS.md)。

---

## 3. 手动分步启动

```bash
# 1. 基础设施
cd infra
cp .env.example .env
docker compose up -d mysql redis nacos minio etcd milvus

# 2. 后端
cd aegis-platform-backend
mvn clean package -DskipTests
java -jar aegis-gateway/target/aegis-gateway-*.jar  --spring.profiles.active=local
java -jar aegis-admin/target/aegis-admin-*.jar      --spring.profiles.active=local
java -jar aegis-runtime/target/aegis-runtime-*.jar  --spring.profiles.active=local
java -jar aegis-mcp-demo/target/aegis-mcp-demo-*.jar

# 3. 前端
cd aegis-platform-web
npm install
npm run dev           # http://localhost:5173
```

启动顺序：基础设施 → admin（注入 universal 智能体）→ runtime（注入 skill_creator 元技能）→ gateway → mcp-demo（自注册）。

> Git Bash 下 `mvn` 启动器不可用，用 PowerShell 调 `mvn.cmd`。

### 可选组件

```bash
docker compose --profile observability up -d   # Prometheus :9090 / Grafana :3000 / OTel :4317,:4318
# OCR 已内置为 ONNX Runtime 进程内推理，无需单独启动；原 --profile ocr（paddleocr）已废弃不可用
docker compose up -d aegis-searxng             # SearXNG :8888（web_search 工具后端）
```

---

## 4. 数据初始化

| 数据 | 来源 | 时机 |
|---|---|---|
| 表结构（61 表） | `infra/ddl/01_schema_init.sql` | MySQL 容器**首次启动**且数据卷为空时自动执行 |
| 种子数据 | `infra/ddl/02_seed_data.sql` | 同上，随 01 一起执行 |
| universal 智能体 | `TenantBootstrapService`（admin，ApplicationRunner） | 每次启动幂等注入 |
| skill_creator 元技能 | `SkillCreatorInitializer`（runtime，CommandLineRunner） | 每次启动幂等注入 |
| MCP 服务 | mcp-demo 自注册 | mcp-demo 启动时，重试 15 次 × 5s |

种子数据只含平台最小集：租户 `DEFAULT`、配额、根部门、7 个角色、45 个权限点、admin 账号、安全基线。**模型 Provider / model_def / model_route 不含在内**（含 API Key），需部署后在管理页面配置。

**手动执行**：

```bash
docker exec -i aegis-mysql mysql -uroot -p'root123' aegis --default-character-set=utf8mb4 \
  < infra/ddl/01_schema_init.sql
docker exec -i aegis-mysql mysql -uroot -p'root123' aegis --default-character-set=utf8mb4 \
  < infra/ddl/02_seed_data.sql
```

Windows PowerShell 需显式指定 UTF-8 编码：

```powershell
Get-Content -Raw -Encoding utf8 infra/ddl/01_schema_init.sql |
  docker exec -i aegis-mysql mysql -uroot -p'root123' aegis --default-character-set=utf8mb4
```

**重建数据库**（schema 变更后）：

```bash
cd infra
docker compose down
docker volume rm infra_aegis-mysql-data      # ⚠️ 清空全部数据
docker compose up -d                          # 重新执行 DDL/DML
```

---

## 5. 部署后必做

| # | 操作 | 位置 |
|---|---|---|
| 1 | 改 admin 初始密码（种子密码 `aegis@123`，BCrypt cost=10） | 右上角「个人中心」 |
| 2 | 配置模型 Provider（Endpoint / API Key / 模型清单） | 管理端「模型管理 → 提供商」，**不配则对话不可用** |
| 3 | 按需调整安全基线（敏感词 / 脱敏 / 出站 / 工具策略） | 管理端「安全治理」 |
| 4 | 生产环境覆盖 `JWT_SECRET`（三个后端服务必须一致，≥32 字节） | Nacos 或环境变量 |

模型 API Key 只存 `model_provider` 表，不进 `.env`、不进代码。

---

## 6. 沙箱

| 后端 | 配置 | 适用 |
|---|---|---|
| k8s（**当前默认**） | `aegis.runtime.sandbox.backend=k8s`，`local` profile 同值 | 本机 Docker Desktop K8s / 生产集群 |
| docker | 改 `application-local.yml` 的 `backend: docker`，配 `SANDBOX_DOCKER_HOST` | 纯 Docker 环境 |
| process | `backend: process` | 无容器，runtime 进程内执行 |

- 纯对话不占沙箱（`lazy-allocation.enabled=true`），未配沙箱不影响对话。
- Windows Docker Desktop：启用 WSL2 后端；Docker 沙箱连接串为 `npipe:////./pipe/docker_engine`（四斜杠）。
- Docker Desktop K8s 的 containerd 不继承宿主 registry mirror，Pod 可能 `ImagePullBackOff`。`aegis.ps1` 已预拉镜像；仍失败时检查 Docker Desktop → Settings → Docker Engine → `registry-mirrors`。

---

## 7. 端口清单

| 服务 | 端口 | 服务 | 端口 |
|---|---|---|---|
| 前端 dev / prod | 5173 / 80（占用则 8088） | MySQL | 3306 |
| 网关 | 8080 | Redis | 6379 |
| 运行时 | 8081 | Nacos | 8848 / 9848 / 9849 / 控制台 8083 |
| 管理后台 | 8082 | MinIO | 9000 / 控制台 9001 |
| MCP Demo | 8084 | Milvus | 19530 / 9091 |
| SearXNG | 8888 | etcd | 2379（容器内） |
| Grafana | 3000 | Prometheus | 9090 |
| PaddleOCR（已废弃） | 8098（不使用） | — | OCR 已内置 ONNX 进程内推理，`paddleocr` Docker 服务已移除 |

---

## 8. 常见问题

| 现象 | 原因与处置 |
|---|---|
| `mvn` 不是可识别的命令（Git Bash） | 用 PowerShell 调 `mvn.cmd` 全路径，或在 `aegis.conf` 设 `MVN_CMD` |
| 脚本报端口 timeout 但服务实际已起 | 用 `curl` 直连实际端口确认 |
| 401 / 登录失败 | 三个服务 `JWT_SECRET` 不一致；或密码错（种子 `aegis@123`） |
| 对话报模型不可用 | 未配置模型 Provider，见 §5 |
| 改了 DDL 不生效 | DDL 仅数据卷为空时执行，按 §4 删 volume 重建 |
| SSE 流中断 / 卡顿 | Vite dev 代理已关超时与压缩；自建反向代理需同样关闭 `proxy_buffering` 并加长超时 |
| 沙箱工具调用 `SERVICE_UNAVAILABLE` | 检查 K8s 集群是否就绪、命名空间 `aegis-sbx-t1-*`、池是否 ENABLED |
| Pod `ImagePullBackOff` | 见 §6 containerd registry mirror 说明 |
| Milvus 首启慢 | 依赖 etcd + MinIO 健康后拉起，约 60s |
| 构建失败提示 jar 被占用 | 上轮进程未退干净；用 `.\aegis.ps1 appstop`，脚本会校验 jar 文件锁 |

---

## 9. 停止与卸载

```bash
.\aegis.ps1 appstop        # 仅停本机应用
.\aegis.ps1 stop           # 应用 + 基础设施容器

cd infra
docker compose down        # 停容器，保留数据卷
docker compose down -v     # ⚠️ 连数据卷一起删除
```

*相关文档：[环境变量清单](../infra/ENV_VARS.md) ｜ [沙箱分配与回收](sandbox-allocation-and-recycling.md) ｜ [技术架构](architecture.md)*
