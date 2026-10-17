# syntax=docker/dockerfile:1
# The web version of the desktop app (observability/desktop/README.md, "Web version"): `npm run build:web`'s dist-web/
# served by nginx at /web/, the image `frakworks-web`. Built from observability/desktop (the context):
#   docker build -f web-image/Dockerfile -t frakworks-web:main .
# --ignore-scripts: no Electron binary is downloaded, only esbuild and the renderer's packages are used.
FROM --platform=$BUILDPLATFORM node:22-alpine AS build
WORKDIR /desktop
COPY package.json package-lock.json ./
RUN npm ci --ignore-scripts --no-audit --no-fund
COPY src ./src
RUN node src/web/build.js

# Unprivileged nginx: listens on 8080 as uid 101, so the pod runs under Pod Security `restricted`.
FROM nginxinc/nginx-unprivileged:1.27-alpine
COPY web-image/nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /desktop/dist-web /usr/share/nginx/html/web
EXPOSE 8080
