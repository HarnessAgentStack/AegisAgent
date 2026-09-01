# Aegis 部署指南

> 适用版本：0.1.0-alpha.1 ｜ 部署形态：Docker 全栈（推荐）/ 源码本地开发

---

## 1. 环境要求

| 项 | 要求 |
|---|---|
| Docker | 24+，含 Compose v2 |
| 内存 | ≥ 8 GB（Milvus + 3 个 Java 服务） |
| 磁盘 | ≥ 20 GB（镜像 + 数据卷） |
| 源码构建（可选） | JDK 21 / Maven 3.9 / Node 20 |

---

## 2. 快速启动（Docker 全栈）

```bash
# 1. 配置环境变量
cd infra
cp .env.example .env
# 编辑 .env，必改三项：JWT_SECRET / DB_ROOT_PASSWORD / MINIO_SECRET_KEY
#   JWT_SECRET 生成：openssl rand -base64 48

# 2. 一键全栈启动（首次构建约 10~20 分钟）
docker compose -f docker-compose.yml -f docker-compose.app.yml up -d --build

# 3. 查看状态
docker compose -f docker-compose.yml -f docker-compose.app.yml ps
```

或使用一键脚本：`./quickstart.sh`（Linux/Mac）、`.\quickstart.ps1`（Windows）。

> 社区用户拉取最新代码后，直接 `.\quickstart.ps1 all` 即可：`--build` 在容器内自动用阿里云 Maven 镜像编译，无需本地装 JDK/Maven/Node。

**访问验证**

| 入口 | 地址 |
|---|---|
| 前端控制台 | http://localhost |
| 网关 | http://localhost:8080 |
| Nacos 控制台 | http://localhost:8083 |

**初始登录**：租户 `DEFAULT` ｜ 用户名 `admin` ｜ 密码 `aegis@123`

---

## 2.1 两种运行模式与无缝切换

| 模式 | 脚本 | 定位 | 适用 |
|------|------|------|------|
| 全 Docker | `quickstart.ps1` | 容器内 Maven 构建 + 容器化运行 | 社区分发、演示、验收测试 |
| 本机开发 | `aegis-service.ps1` | 基础设施 Docker + 应用本机进程 | 日常开发、IDE 断点、热重载 |

两脚本共用同一套基础设施（`infra/docker-compose.yml`，容器名 `aegis-mysql` 等），`docker compose up` 对已存在容器幂等复用——**切换时基础设施不重建、不丢数据**，仅应用层切换。

```powershell
# 全 Docker → 本机开发（停应用容器，保留基础设施）
.\quickstart.ps1 appdown
.\aegis-service.ps1 start       # 复用基础设施，本机起 gateway/admin/runtime + vite

# 本机开发 → 全 Docker（停本机进程，保留基础设施）
.\aegis-service.ps1 appstop
.\quickstart.ps1 app            # 复用基础设施，起应用容器
```

**命令速查**

| 意图 | 命令 |
|------|------|
| 全 Docker 起全栈 | `.\quickstart.ps1 all` |
| 全 Docker 停应用留 infra | `.\quickstart.ps1 appdown` |
| 全 Docker 全停 | `.\quickstart.ps1 down` |
| 本机起全栈 | `.\aegis-service.ps1 start` |
| 本机停应用留 infra | `.\aegis-service.ps1 appstop` |
| 本机全停 | `.\aegis-service.ps1 stop` |

---

## 3. 分步部署

### 3.1 基础设施（必启，6 件套）

```bash
cd infra
docker compose up -d          # MySQL / Redis / Nacos / MinIO / etcd / Milvus
```

### 3.2 应用服务（网关 → 管理后台 → 运行时 → 前端）

```bash
cd infra
docker compose -f docker-compose.app.yml up -d --build
```

> 启动顺序：建议先起基础设施（§3.1）再起应用。admin 启动时注入「通用智能体」，runtime 启动时注入「skill_creator 元技能」。
> 跨 compose 文件无法声明 depends_on；应用服务设 `restart: unless-stopped`，若基础设施未就绪会自愈重启直至连通（MySQL/Nacos 健康前可能重启数次，属正常）。
> 推荐用「全栈一次起」（§2）避免顺序问题。

### 3.3 可选组件（Profile）

```bash
docker compose --profile observability up -d   # Prometheus / Grafana / OTel
docker compose --profile ocr up -d             # PaddleOCR（首次下载模型约 10 分钟）
docker compose -f docker-compose.app.yml --profile mcp-demo up -d   # MCP 示例服务
```

---

## 4. 数据初始化

| 数据 | 来源 | 时机 |
|---|---|---|
| 表结构（58 表） | `infra/ddl/01_schema_init.sql` | MySQL 容器**首次启动**自动执行（数据卷为空时） |
| 种子数据（租户/角色/admin/工具/安全策略等 14 表） | `infra/ddl/02_seed_data.sql` | 同上，随 01 一起自动执行 |
| 通用智能体（universal） | `TenantBootstrapService` | aegis-admin 每次启动幂等注入 |
| 元技能（skill_creator） | `SkillCreatorInitializer` | aegis-runtime 每次启动幂等注入 |

**手动执行（已有空库时）**：

```bash
# macOS / Linux / Git Bash
docker exec -i aegis-mysql mysql -uroot -p'root123' aegis \
  --default-character-set=utf8mb4 \
  < infra/ddl/01_schema_init.sql
docker exec -i aegis-mysql mysql -uroot -p'root123' aegis \
  --default-character-set=utf8mb4 \
  < infra/ddl/02_seed_data.sql

# Windows PowerShell（必须显式指定 UTF-8，否则 PowerShell 按 CP936 管道导致中文变双重编码）
Get-Content -Raw -Encoding utf8 infra/ddl/01_schema_init.sql |
  docker exec -i aegis-mysql mysql -uroot -p'root123' aegis --default-character-set=utf8mb4
Get-Content -Raw -Encoding utf8 infra/ddl/02_seed_data.sql |
  docker exec -i aegis-mysql mysql -uroot -p'root123' aegis --default-character-set=utf8mb4
```

**重建数据库**（schema 变更后）：

```bash
cd infra
docker compose down
docker volume rm infra_aegis-mysql-data      # ⚠️ 清空全部数据
docker compose up -d                          # 首启重新执行 DDL/DML
```

---

## 5. 部署后必做配置

| # | 操作 | 位置 |
|---|---|---|
| 1 | 修改 admin 初始密码 | 右上角「个人中心」 |
| 2 | 配置模型 Provider（API Key / 端点 / 模型清单） | 管理端「模型管理 → 提供商」，**不配置则对话不可用** |
| 3 | 按需调整安全基线（敏感词/脱敏/出站/工具策略） | 管理端「安全治理」 |

> 模型 API Key 只存数据库（`model_provider` 表），**不进** .env / 代码。

---

## 6. 沙箱模式选择

代码执行（`aegis_execute`）依赖沙箱，二选一：

| 模式 | 配置 | 适用 |
|---|---|---|
| **Docker**（默认体验） | compose 已默认 `SPRING_PROFILES_ACTIVE=local`（激活 `application-local.yml` -> backend=docker） | 本地/开源体验，挂载 `/var/run/docker.sock` |
| **K8s**（生产） | 设 `SPRING_PROFILES_ACTIVE` 为空或非 local，走 `application.yml` 默认 `backend: k8s` + kubeconfig | 生产，多副本共享沙箱池 |

- 纯聊天不占沙箱（惰性分配），未配沙箱不影响对话。
- Windows Docker Desktop 沙箱需启用 WSL2 后端；sock 路径差异见 `infra/.env.example`。

---

## 7. 源码本地开发

推荐用 `aegis-service.ps1`（自动探测 JAVA_HOME/MVN，起基础设施 + 本机应用）：

```powershell
.\aegis-service.ps1 start     # 起 Docker 基础设施 + 本机 Java 进程 + vite
.\aegis-service.ps1 status    # 查看全部状态
.\aegis-service.ps1 restart   # 重新构建并重启（代码变更后）
.\aegis-service.ps1 appstop   # 仅停应用，保留基础设施（切到全 Docker 用）
```

或手动分步：

```bash
# 后端（顺序：gateway → admin → runtime）
cd aegis-platform-backend
mvn clean install -DskipTests
cd aegis-gateway  && mvn spring-boot:run   # :8080
cd aegis-admin    && mvn spring-boot:run   # :8082
cd aegis-runtime  && mvn spring-boot:run   # :8081

# 前端（Vite 代理 /api → 8080/8081/8082）
cd aegis-platform-web
npm install
npm run dev                              # http://localhost:5173
```

本地开发无需 `prod` profile（dev 配置自带占位默认值）；`prod` 为 fail-fast，缺变量直接启动失败。

---

## 8. 端口清单

| 服务 | 端口 | 服务 | 端口 |
|---|---|---|---|
| 前端(web) | 80 | MySQL | 3306 |
| 网关 | 8080 | Redis | 6379 |
| 运行时 | 8081 | Nacos | 8848 / 控制台 8083 |
| 管理后台 | 8082 | MinIO | 9000 / 控制台 9001 |
| MCP Demo | 8084 | Milvus | 19530 / 9091 |
| Grafana | 3000 | Prometheus | 9090 |
| OCR | 8098 | OTel | 4317(gRPC) / 4318(HTTP) |

---

## 9. 环境变量速查

完整清单见 [infra/ENV_VARS.md](../infra/ENV_VARS.md)。最小必改：

```env
JWT_SECRET=<openssl rand -base64 48>   # ≥32 字节，三个后端服务必须一致
DB_ROOT_PASSWORD=<强随机>
MINIO_SECRET_KEY=<强随机 ≥8 位>
```

---

## 10. 常见问题

| 现象 | 原因与处置 |
|---|---|
| 服务启动报占位符解析失败 | prod profile 缺环境变量（fail-fast 设计），补 `.env` 后 `docker compose up -d` 重建 |
| 401 / 前端登录失败 | JWT_SECRET 三个服务不一致；或密码错（种子密码 `aegis@123`） |
| 对话报模型不可用 | 未配置模型 Provider，先做「部署后必做配置 2」 |
| MySQL 改了 DDL 不生效 | DDL 仅首启执行，按 §4 重建 volume |
| SSE 流中断/卡顿 | 经前端 nginx 已关闭缓冲；若自建代理需同样关闭 `proxy_buffering` 并加长超时 |
| 沙箱工具调用报 SERVICE_UNAVAILABLE | Docker 模式检查 sock 挂载；K8s 模式检查池与命名空间 `aegis-sbx-t1-standard` |
| Milvus 启动慢 | 依赖 etcd+MinIO 健康后拉起，首启约 60s，属正常 |

---

## 11. 停止与卸载

```bash
cd infra
docker compose -f docker-compose.app.yml down       # 停应用（保留基础设施）
docker compose down                                  # 停基础设施（数据卷保留）

docker compose down -v                               # ⚠️ 彻底清除（含全部数据）
```

或用脚本快捷命令（见 §2.1）：

```powershell
.\quickstart.ps1 appdown     # 仅停应用容器，保留基础设施（切到本机开发用）
.\aegis-service.ps1 appstop  # 仅停本机进程，保留基础设施（切到全 Docker 用）
.\quickstart.ps1 down        # 停全部（应用 + 基础设施）
```

---

*相关文档：[环境变量清单](../infra/ENV_VARS.md) ｜ [沙箱分配与回收](sandbox-allocation-and-recycling.md)*
