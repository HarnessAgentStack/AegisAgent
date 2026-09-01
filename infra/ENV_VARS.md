# Aegis 环境变量清单

> 所有服务的环境变量统一参考。`prod` profile 采用 **fail-fast** 设计：关键凭据占位符不带默认值，漏注入则启动失败，杜绝静默回退弱凭据。本地开发用默认 `application.yml`（含 dev 占位值）即可，无需激活 `prod`。

## 一、基础设施（docker-compose 读取 `.env`）

| 变量 | 必填 | 默认值 | 说明 |
|---|---|---|---|
| `DB_ROOT_PASSWORD` | 是 | root123 | MySQL root 超级密码 |
| `DB_USER` | 是 | aegis | 业务库专用账号 |
| `DB_PASSWORD` | 是 | aegis123 | 业务库密码 |
| `DB_NAME` | 是 | aegis | 业务库名（与 schema 一致） |
| `REDIS_PASSWORD` | 否 | （空） | Redis 密码，生产建议设置 |
| `MINIO_ACCESS_KEY` | 是 | aegis | MinIO 接入 key |
| `MINIO_SECRET_KEY` | 是 | aegis12345 | MinIO 密钥（≥8 位） |
| `GRAFANA_PASSWORD` | 否 | admin123 | Grafana 管理员密码 |
| `JWT_SECRET` | 是 | 占位 | HS256 签名密钥，UTF-8 字节 ≥ 32 |

## 二、应用服务（gateway / admin / runtime）

三个后端服务共享以下变量（prod profile 必填）：

| 变量 | 必填(prod) | 说明 |
|---|---|---|
| `DB_HOST` | 是 | MySQL 主机（compose 网络：`mysql`） |
| `DB_PORT` | 是 | MySQL 端口（3306） |
| `DB_USER` | 是 | 业务库账号 |
| `DB_PASSWORD` | 是 | 业务库密码 |
| `DB_NAME` | 是 | 业务库名 |
| `DB_USE_SSL` | 否 | 是否启用 SSL（生产建议 true） |
| `REDIS_HOST` | 是 | Redis 主机（compose 网络：`redis`） |
| `REDIS_PORT` | 是 | Redis 端口（6379） |
| `REDIS_PASSWORD` | 否 | Redis 密码 |
| `JWT_SECRET` | 是 | JWT 签名密钥（三服务必须一致） |
| `MINIO_ENDPOINT` | 是 | MinIO 端点（compose 网络：`http://minio:9000`） |
| `MINIO_ACCESS_KEY` | 是 | MinIO 接入 key |
| `MINIO_SECRET_KEY` | 是 | MinIO 密钥 |
| `UPON_REDIS_HOST` | 否 | AgentScope 框架分布式存储 Redis 主机（缺省复用 REDIS_HOST） |
| `UPON_REDIS_PORT` | 否 | 同上端口 |
| `UPON_REDIS_PASSWORD` | 否 | 同上密码 |

### runtime 专属

| 变量 | 必填 | 说明 |
|---|---|---|
| `SPRING_PROFILES_ACTIVE` | 否 | `local` -> 沙箱 backend=docker（纯 Docker 部署默认）；不设则 `application.yml` 默认 k8s |
| `SANDBOX_DOCKER_HOST` | 否 | Docker 沙箱宿主（Linux/Mac：`unix:///var/run/docker.sock`） |
| `SANDBOX_USER_FILES_DIR` | 否 | 沙箱用户文件挂载目录 |

### admin 专属

| 变量 | 必填 | 说明 |
|---|---|---|
| `BACKUP_DIR` | 否 | 数据库备份目录（默认 /data/backups/aegis） |
| `HARBOR_PASSWORD` | 否 | Harbor 私有镜像仓库密码（仅用 Harbor 时） |

### gateway 专属

| 变量 | 必填 | 说明 |
|---|---|---|
| `GATEWAY_WHITELIST` | 否 | 免鉴权路径白名单（逗号分隔） |

## 三、Nacos 配置中心

`spring.config.import: optional:nacos:{service}.yaml` 已在 gateway/admin 配置。
本地开发默认连 `127.0.0.1:8848`，生产通过 Nacos 覆盖上方变量（环境变量优先级高于 Nacos）。
**注意**：runtime 当前本地配置（注释了 nacos import），生产可放开。

## 四、模型配置（不入环境变量）

`model_provider.api_key` 属敏感凭据，**不进 .env / 不进代码**，部署后在 admin 页面「模型管理 → 提供商」配置。模型路由/限流随之在页面维护。

## 五、最小启动变量集（prod）

拷贝 `infra/.env.example` 为 `.env`，至少修改以下三项即可启动：

```
DB_ROOT_PASSWORD=<强随机>
JWT_SECRET=<openssl rand -base64 48>
MINIO_SECRET_KEY=<强随机≥8位>
```

其余变量在 `.env` 中已有合理默认值。
