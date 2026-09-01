# Aegis Platform Web - 前端构建与部署
# multi-stage: Node 构建 -> Nginx 托管静态资源
#
# 构建: docker build -t aegis-web:latest -f Dockerfile .
#   (build context 为 aegis-platform-web 目录)
# 运行: docker run -p 80:80 aegis-web:latest
#   生产通过 nginx 反代 /api、/sse 到 aegis-gateway:8080

# ===== Stage 1: 构建 =====
FROM node:20-alpine AS builder
WORKDIR /app

# 工程使用 npm（有 package-lock.json），直接用 npm 避免包管理器版本冲突
COPY package.json package-lock.json ./
RUN npm ci

# 拷贝源码并构建
COPY . .
RUN npm run build

# ===== Stage 2: Nginx 运行时 =====
FROM nginx:1.27-alpine
LABEL org.opencontainers.image.title="aegis-web"
LABEL org.opencontainers.image.description="Aegis Platform Web Frontend"

# 时区
ENV TZ=Asia/Shanghai
RUN apk add --no-cache tzdata && ln -snf /usr/share/zoneinfo/$TZ /etc/localtime

# 拷贝自定义 nginx 配置（反代到 gateway）
COPY nginx.conf /etc/nginx/conf.d/default.conf

# 拷贝构建产物
COPY --from=builder /app/dist /usr/share/nginx/html

EXPOSE 80

HEALTHCHECK --interval=15s --timeout=5s --retries=5 \
  CMD wget -qO- http://127.0.0.1/ >/dev/null 2>&1 || exit 1
