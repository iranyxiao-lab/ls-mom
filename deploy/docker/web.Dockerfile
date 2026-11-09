# 前端：构建 plus-ui，用 nginx 提供静态文件并反向代理后端
FROM node:22-alpine AS build
ARG NPM_REGISTRY=https://registry.npmmirror.com
WORKDIR /src
ARG PNPM_VERSION=12.15.0
RUN corepack enable && corepack prepare pnpm@${PNPM_VERSION} --activate && pnpm config set registry "${NPM_REGISTRY:-https://registry.npmjs.org}"
COPY plus-ui/package.json plus-ui/pnpm-workspace.yaml plus-ui/pnpm-lock.yaml ./
RUN --mount=type=cache,target=/root/.local/share/pnpm/store pnpm install --frozen-lockfile
COPY plus-ui/ ./
RUN pnpm build:prod

FROM nginx:1.27-alpine
COPY deploy/docker/nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /src/dist /usr/share/nginx/html
EXPOSE 80
