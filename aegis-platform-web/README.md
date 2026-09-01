# Aegis Platform Web

> Aegis Platform 前端工程 —— 企业级通用智能体平台

## 技术栈

- React 18 + TypeScript 5.7
- Vite 6 构建工具
- Ant Design 5.x 组件库
- Zustand 状态管理
- React Router 6 路由
- Axios HTTP 客户端
- @tanstack/react-query 数据请求
- SSE 客户端（自定义 Hook）
- dayjs 时间处理
- lodash-es 工具函数

## 环境要求

- Node.js >= 18
- 包管理器：npm / pnpm / yarn

## 快速开始

```bash
# 安装依赖
npm install

# 启动开发服务（默认端口 5173，代理 /api -> http://localhost:8080）
npm run dev

# 类型检查
npm run type-check

# 生产构建
npm run build

# 预览构建产物
npm run preview
```

## 目录结构

```
src/
├── api/          API 客户端层（Axios 实例 + 拦截器 + SSE）
├── components/   通用组件层（layout / chat / common / business）
├── pages/        页面层
├── hooks/        自定义 Hooks（SSE / Auth / Tenant / Agent / Permission）
├── stores/       Zustand 状态管理
├── router/       路由配置与守卫
├── types/        TypeScript 类型定义
├── utils/        工具函数与常量
└── styles/       全局样式与 CSS 变量
```

## 工程约定

- 路径别名 `@` → `src`
- API 统一前缀 `/api`，SSE 前缀 `/sse`
- 租户标识通过请求拦截器自动注入（Header: X-Tenant-Id）
- Token 通过请求拦截器自动注入（Header: Authorization）
- 所有页面使用函数组件 + Hooks
- 状态管理统一使用 Zustand，服务端数据使用 @tanstack/react-query

## 后端契约

本工程通过 OpenAPI 契约与后端解耦，对应后端工程聚合 `aegis-platform-backend`：
- `aegis-gateway` 网关服务（认证鉴权 / 租户路由 / SSE 长连接）
- `aegis-runtime` 运行平面服务（智能体执行核心链路）
- `aegis-admin` 管理平面服务（配置管理 / 运营治理 / 租户管理）