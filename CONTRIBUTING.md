# 贡献指南

感谢你的关注！Aegis 欢迎各种形式的贡献。

## 开发环境

```bash
# 前置
- JDK 21+
- Node 20+
- Docker + Docker Compose v2
- Maven 3.9+

# 基础设施（MySQL/Redis/Nacos/Milvus/MinIO）
docker compose -f infra/docker-compose.yml up -d

# 初始化数据库
docker exec -i aegis-mysql mysql -uroot -proot aegis_platform \
  < infra/ddl/01_schema_init.sql
docker exec -i aegis-mysql mysql -uroot -proot aegis_platform \
  < infra/ddl/02_seed_data.sql

# 后端：两种方式二选一

# 方式 A：手动分模块启动（调试断点用）
cd aegis-platform-backend
mvn clean install -DskipTests
mvn spring-boot:run -pl aegis-gateway    # :8080
mvn spring-boot:run -pl aegis-admin     # :8082
mvn spring-boot:run -pl aegis-runtime   # :8081

# 方式 B：aegis-service.ps1 一键启动（PowerShell，支持 start/stop/status/restart/build）
.\aegis-service.ps1 start    # 同时起 gateway + admin + runtime
.\aegis-service.ps1 status   # 查看三个进程状态
.\aegis-service.ps1 stop     # 停止

# 前端
cd aegis-platform-web
npm install
npm run dev   # http://localhost:5173
```

## 代码规范

- **Java**：Google Java Format，4 空格缩进，所有 Public API 必须有 Javadoc
- **前端**：ESLint + Prettier，2 空格缩进，组件文件 PascalCase，工具函数 camelCase
- **SQL**：MySQL 8 语法，表名前缀按领域分组（`agent_` / `res_` / `sbx_` / `sec_` / `mon_`），字段 snake_case，必须有 `create_time` / `update_time` / `deleted`
- **命名**：Maven artifactId `aegis-{模块名}`，包名 `com.aegis.{模块}`，枚举值 UPPER_SNAKE_CASE

## PR 流程

1. Fork 后创建功能分支：`git checkout -b feat/xxx` 或 `git checkout -b fix/xxx`
2. 提交前跑完整测试：后端 `mvn verify`，前端 `npm run lint && npm run build`
3. 保持提交原子化，每个 commit 只做一件事
4. PR 描述包含：改动动机、改动清单、测试覆盖、截图（如涉及 UI）

## Issue 模板

**Bug**：复现步骤 + 预期行为 + 实际行为 + 环境信息（JDK / Node / Docker 版本）+ 相关日志

**Feature**：问题背景 + 期望行为 + 建议实现方向

## 文档

产品和技术文档统一放在 `docs/`，命名 kebab-case 小写。修改架构或数据模型时，必须同步更新对应的专项文档。
