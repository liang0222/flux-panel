# 多阶段构建 - 构建阶段
FROM node:20.19.0 AS builder

WORKDIR /app

# 复制 .npmrc 以应用 legacy-peer-deps=true：
# @heroui/accordion 与 @heroui/button 对 @heroui/theme 的 peer 版本要求冲突，
# 不加该选项 npm install 会直接 ERESOLVE 失败，导致镜像构建中断。
COPY package*.json .npmrc ./
RUN npm install

COPY . .
RUN npm run build

# 生产阶段
FROM nginx:stable-alpine AS production-stage

COPY nginx.conf /etc/nginx/nginx.conf

COPY --from=builder /app/dist /usr/share/nginx/html

EXPOSE 80

CMD ["nginx", "-g", "daemon off;"] 