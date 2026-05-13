# LyClaw GitHub Actions CI/CD 从零配置全记录

> 本文档完整记录了 LyClaw 分布式微服务项目从零到全自动 CI/CD 的配置过程，
> 包含每一段 YAML 代码的语法解释、排错记录、以及架构决策的理由。

---

## 目录

1. [项目背景与架构](#1-项目背景与架构)
2. [GitHub Actions 基础概念](#2-github-actions-基础概念)
3. [CI 工作流：持续集成](#3-ci-工作流持续集成)
4. [CD 工作流：分布式部署](#4-cd-工作流分布式部署)
5. [Docker 镜像构建体系](#5-docker-镜像构建体系)
6. [Docker Compose 分布式编排](#6-docker-compose-分布式编排)
7. [GitHub Secrets 配置指南](#7-github-secrets-配置指南)
8. [排错全记录](#8-排错全记录)
9. [附录：常用命令速查](#9-附录常用命令速查)

---

## 1. 项目背景与架构

### 1.1 项目简介

LyClaw 是一个 AI Agent 框架，采用 Java 21 + Spring Boot 3.5 + Spring Cloud 微服务架构。
前后端分离：后端为 Maven 多模块 Java 项目，前端为 Vite + Vue 3。

### 1.2 微服务拆分（8 个模块）

| 模块 | 目录 | 职责 |
|---|---|---|
| lyclaw-gateway | lyclaw-gateway | Spring Cloud Gateway，唯一对外入口 |
| lyclaw-orchestration | lyclaw-orchestration | 编排引擎，多阶段管线执行 |
| lyclaw-action | lyclaw-action | 工具执行服务（Shell、搜索等） |
| lyclaw-plan | lyclaw-plan | 任务规划服务 |
| lyclaw-memory | lyclaw-memory | 记忆存储与检索 |
| lyclaw-reflect | lyclaw-reflect | 质量反思 |
| lyclaw-facade | lyclaw-facade | API 门面 |
| lyclaw-ui | lyclaw-ui | Vue 3 前端（Vite 构建） |

### 1.3 三台云服务器分布

```
阿里云 47.104.212.121  ─  gateway (8080) + orchestration (8081) + ui (80)
华为云 113.45.200.224  ─  action (8084) + plan (8083)
腾讯云 81.71.120.92    ─  memory (8082) + reflect (8085) + facade (8086)
```

每台服务器独立运行一个 Nacos 实例（服务注册与发现）。

### 1.4 用户访问流程

```
用户浏览器 → http://47.104.212.121:80 (nginx/Vue前端)
                  ↓ /api/*
           http://lyclaw-gateway:8080 (网关)
                  ↓ Feign/Nacos 服务发现
           orchestration → plan → action / memory / reflect / facade
```

---

## 2. GitHub Actions 基础概念

### 2.1 什么是 GitHub Actions

GitHub Actions 是 GitHub 内置的 CI/CD 平台。在仓库根目录下
`.github/workflows/*.yml` 文件中定义工作流，当触发事件发生时，
GitHub 会在临时虚拟机（Runner）上自动执行。

### 2.2 核心概念

| 概念 | 说明 |
|---|---|
| **Workflow**（工作流） | 一个 `.yml` 文件，定义完整的自动化流程 |
| **Event**（事件） | 触发工作流的行为，如 `push`、`pull_request` |
| **Job**（作业） | Workflow 中的一个独立执行单元，由多个 Step 组成 |
| **Step**（步骤） | Job 中的单个操作，可以是 shell 命令或 Action |
| **Action**（动作） | 可复用的步骤封装，如 `actions/checkout@v4` |
| **Runner**（运行器） | 执行 Job 的虚拟机，GitHub 提供 Ubuntu/Windows/macOS |
| **Matrix**（矩阵） | 同一 Job 的多组参数并行执行 |
| **Secret**（密钥） | 加密存储的敏感信息（API Key、Token 等） |

### 2.3 YAML 语法要点

```yaml
# 键值对（字符串可以不加引号）
name: LyClaw CI

# 多行字符串：| 保留换行，> 折叠换行
script: |
  第一行
  第二行   # 最终是两行

# 列表（两种写法等价）
services:
  - gateway
  - orchestration

services: [gateway, orchestration]

# 环境变量引用：${{ env.VAR_NAME }} 或 $VAR_NAME
# 密钥引用：${{ secrets.SECRET_NAME }}
# 输出引用：${{ needs.JOB_ID.outputs.OUTPUT_NAME }}
```

### 2.4 表达式语法 `${{ }}`

`${{ }}` 是 GitHub Actions 的表达式求值语法，在工作流解析阶段就会被替换为实际值。

```yaml
# 条件判断
if: ${{ !inputs.skip_tests }}          # 输入参数 skip_tests 为 false 时执行
if: matrix.service != 'ui'             # 矩阵变量 service 不等于 'ui' 时执行

# 三元运算（使用 && || 短路求值）
file: ${{ matrix.service == 'ui' && './Dockerfile.ui' || './Dockerfile' }}
#      如果 service=='ui' → './Dockerfile.ui'，否则 → './Dockerfile'

# 字符串拼接
tags: ${{ env.DOCKER_REGISTRY }}/lyclaw-${{ matrix.service }}:latest
#      docker.io/haikunye/lyclaw-gateway:latest
```

### 2.5 工作流触发（on）

```yaml
on:
  push:                    # 代码推送时
    branches:
      - main               # 只监听 main 分支
      - '**'               # 所有分支

  pull_request:            # PR 时
    branches: [main, dev]

  schedule:                # 定时任务
    - cron: '0 2 * * *'    # UTC 凌晨2点（北京时间10点）

  workflow_dispatch:       # 手动触发（GitHub UI 按钮）
    inputs:                # 手动触发时可传的参数
      skip_tests:
        description: '跳过测试'
        type: boolean
        default: false
```

**Cron 语法**：`分 时 日 月 周`（UTC 时间，非本地时间）

### 2.6 Job 间依赖（needs）与并行

```yaml
jobs:
  build:        # Job 1：无依赖，最先执行
    ...

  docker:       # Job 2：依赖 build，build 完成后所有 matrix 并行
    needs: build
    strategy:
      matrix:
        service: [gateway, orchestration, action, plan, memory, reflect, facade, ui]

  deploy-aliyun:   # Job 3：依赖 docker，三个 deploy 并行
    needs: docker  # docker 全部 matrix 完成后才启动
```

执行顺序：
```
build → docker (8个并行) → deploy-aliyun
                         → deploy-huaweiyun   （3个并行）
                         → deploy-tengxunyun
```

### 2.7 timeout-minutes（超时保护）

```yaml
jobs:
  build:
    timeout-minutes: 30   # 超过 30 分钟自动终止，防止无限等待
```

### 2.8 构建产物传递（artifact）

```yaml
# 上传（Job A）
- uses: actions/upload-artifact@v4
  with:
    name: service-jars          # 产物名
    path: |
      lyclaw-gateway/target/*.jar
      lyclaw-orchestration/target/*.jar

# 下载（Job B）
- uses: actions/download-artifact@v4
  with:
    name: service-jars          # 和上传时的名字一致
```

产物的目录结构会被保留。例如上传了 `lyclaw-gateway/target/app.jar`，
下载后在同路径解压。

---

## 3. CI 工作流：持续集成

**文件**：`.github/workflows/ci.yml`

### 3.1 完整代码逐段解析

```yaml
name: LyClaw CI
```
工作流名称，显示在 GitHub Actions 页面上。

```yaml
on:
  push:
    branches:
      - '**'           # 所有分支推送都触发
    tags-ignore:
      - 'v*'           # tag 推送不触发（语义化版本标签）
  pull_request:
    branches:
      - main
      - dev
  schedule:
    - cron: '0 2 * * *'   # 每天 UTC 2:00 定时全量测试
```

三个触发条件：
1. 任何分支 `push` 立刻跑 CI（不含 tag）
2. 向 main/dev 发起 PR 时跑 CI
3. 每天凌晨自动跑一次全量测试

```yaml
env:
  JAVA_VERSION: '21'
  MAVEN_OPTS: '-Xmx2g -XX:MaxMetaspaceSize=512m'
```
`env` 定义整个工作流的环境变量。`MAVEN_OPTS` 是 Maven 的 JVM 参数，分配 2GB 堆内存。

### 3.2 Job 1：编译与单元测试

```yaml
build-and-test:
  name: Build & Test (JDK ${{ env.JAVA_VERSION }})
  runs-on: ubuntu-latest       # 使用最新 Ubuntu 虚拟机
  timeout-minutes: 30          # 30 分钟超时
```

**Step 1：检出代码**
```yaml
- uses: actions/checkout@v4
```
GitHub 官方 Action，将仓库代码克隆到 Runner 的当前工作目录。
`@v4` 是 Action 的大版本号，表示使用 v4 的最新小版本。

**Step 2：设置 JDK**
```yaml
- uses: actions/setup-java@v4
  with:
    java-version: ${{ env.JAVA_VERSION }}   # → '21'
    distribution: 'temurin'                 # Eclipse Temurin 发行版
    cache: 'maven'                          # 自动缓存 Maven 依赖
```
`cache: 'maven'` 会自动缓存 `~/.m2/repository`，下次运行无需重新下载依赖。

**Step 3：Maven 缓存**
```yaml
- uses: actions/cache@v4
  with:
    path: ~/.m2/repository
    key: ${{ runner.os }}-maven-${{ hashFiles('**/pom.xml') }}
    restore-keys: |
      ${{ runner.os }}-maven-
```
`hashFiles('**/pom.xml')` 对所有 pom.xml 文件计算 SHA-256 哈希。
只有当 pom.xml 变化时才会重建缓存。`restore-keys` 提供模糊匹配的降级方案。

**Step 4：编译**
```yaml
- run: mvn compile -q -DskipTests
```
`-q` 安静模式，`-DskipTests` 不执行测试（只编译）。

**Step 5：单元测试**
```yaml
- run: mvn test -q
```
只运行单元测试（不包含集成测试）。

**Step 6：上传测试报告**
```yaml
- if: always()
  uses: actions/upload-artifact@v4
  with:
    name: test-reports
    path: |
      **/target/surefire-reports/
      **/target/site/jacoco/
```
`if: always()` 很重要——即使前面步骤失败，也上传报告用于排错。

### 3.3 Job 2：代码风格检查

```yaml
checkstyle:
  name: Code Style Check
  runs-on: ubuntu-latest
  timeout-minutes: 5
  steps:
    - uses: actions/checkout@v4
    - uses: actions/setup-java@v4
      with:
        java-version: ${{ env.JAVA_VERSION }}
        distribution: 'temurin'
        cache: 'maven'
    - name: Checkstyle 检查
      run: mvn checkstyle:check -q || true
      continue-on-error: true
```
`|| true` 和 `continue-on-error: true` 双重保险，确保风格检查不会阻塞 CI。

### 3.4 Job 3：漏洞扫描

```yaml
dependency-check:
  name: Dependency Vulnerability Scan
  steps:
    - name: OWASP Dependency Check
      run: mvn org.owasp:dependency-check-maven:check -q || true
      continue-on-error: true
```
使用 OWASP 依赖检查插件扫描已知 CVE 漏洞。同样不阻塞 CI。

---

## 4. CD 工作流：分布式部署

**文件**：`.github/workflows/cd-distributed.yml`

### 4.1 触发条件

```yaml
on:
  push:
    branches:
      - main                   # 只 main 分支触发 CD
  workflow_dispatch:           # 支持手动触发
    inputs:
      skip_tests:
        description: '跳过测试'
        required: false
        default: false
        type: boolean
      deploy_services:
        description: '要部署的服务（逗号分隔，留空=全部）'
        required: false
        default: ''
        type: string
```
`workflow_dispatch` 允许在 GitHub UI 上点按钮手动触发，
并提供可选的输入参数。

### 4.2 Job 1：全量编译 + 测试（build）

```yaml
build:
  name: Build All Modules
  runs-on: ubuntu-latest
  timeout-minutes: 30
  outputs:
    image-tag: ${{ steps.tag.outputs.tag }}
```

**outputs**：此 Job 计算出的镜像标签，供下游 Job 使用。
`steps.tag.outputs.tag` 引用了 id 为 `tag` 的步骤的输出。

**生成镜像标签**：
```yaml
- name: 生成镜像标签
  id: tag
  run: echo "tag=$(date +%Y%m%d-%H%M%S)-${GITHUB_SHA::7}" >> $GITHUB_OUTPUT
```
通过 `echo "key=value" >> $GITHUB_OUTPUT` 设置步骤输出。
示例结果：`20260513-155129-ed09092`

`${GITHUB_SHA::7}` 是 Bash 参数扩展，取提交 SHA 的前 7 位。
`$GITHUB_SHA` 是 GitHub Actions 内置环境变量。

**上传所有 JAR**：
```yaml
- uses: actions/upload-artifact@v4
  with:
    name: service-jars
    path: |
      lyclaw-gateway/target/*.jar
      lyclaw-orchestration/target/*.jar
      lyclaw-action/target/*.jar
      lyclaw-plan/target/*.jar
      lyclaw-memory/target/*.jar
      lyclaw-reflect/target/*.jar
      lyclaw-facade/target/*.jar
```
`path:` 使用 `|` 多行字符串，每行一个 glob 模式。
注意这里是 `lyclaw-reflect` 而非 `lyclaw-reflection`（早期排错修正）。

### 4.3 Job 2：矩阵构建 Docker 镜像（docker）

```yaml
docker:
  name: Build & Push ${{ matrix.service }}
  runs-on: ubuntu-latest
  needs: build                    # 等待 build 完成
  timeout-minutes: 15
  strategy:
    fail-fast: false              # 一个失败不取消其他
    matrix:
      service:
        - gateway
        - orchestration
        - action
        - plan
        - memory
        - reflect
        - facade
        - ui
```

**matrix**：此 Job 会产生 8 个并行实例，每个实例 `matrix.service` 的值不同。
`fail-fast: false` 确保某个镜像构建失败时，其他镜像继续构建。

**条件下载产物**：
```yaml
- name: 下载构建产物
  if: matrix.service != 'ui'
  uses: actions/download-artifact@v4
  with:
    name: service-jars
```
`if: matrix.service != 'ui'`：前端不需要 JAR 文件（它用 Node.js 构建）。

**Docker 登录**：
```yaml
- name: 登录 Docker Hub
  uses: docker/login-action@v3
  with:
    username: ${{ secrets.DOCKERHUB_USERNAME }}
    password: ${{ secrets.DOCKERHUB_TOKEN }}
```
使用 GitHub Secrets 中存储的 Docker Hub 凭证。
`secrets.XXX` 在日志中会被自动遮蔽为 `***`。

**构建并推送**：
```yaml
- name: 构建并推送服务镜像
  uses: docker/build-push-action@v6
  with:
    context: .                 # Docker 构建上下文为仓库根目录
    file: ${{ matrix.service == 'ui' && './Dockerfile.ui' || './Dockerfile' }}
    build-args: |
      SERVICE_MODULE=lyclaw-${{ matrix.service }}
    push: true                 # 构建完成后推送到 registry
    tags: |
      ${{ env.DOCKER_REGISTRY }}/lyclaw-${{ matrix.service }}:${{ needs.build.outputs.image-tag }}
      ${{ env.DOCKER_REGISTRY }}/lyclaw-${{ matrix.service }}:latest
```

`file:` 行解析：
- `matrix.service == 'ui'` → 使用 `Dockerfile.ui`（Node.js 构建 + nginx）
- 其他 → 使用 `Dockerfile`（JRE + JAR）

`build-args:` 传入 `SERVICE_MODULE=lyclaw-gateway`，Dockerfile 中用
`ARG SERVICE_MODULE` 接收，决定复制哪个模块的 JAR。

`tags:` 推送两个标签：
- `lyclaw-gateway:20260513-155129-ed09092`（带时间戳，可回滚）
- `lyclaw-gateway:latest`（最新版本，部署用）

`needs.build.outputs.image-tag` 引用上游 Job 的输出。

### 4.4 Job 3：并行部署到三台服务器

三个部署 Job（`deploy-aliyun`、`deploy-huaweiyun`、`deploy-tengxunyun`）
结构相同，都使用 SSH Action 连接服务器执行部署脚本。

#### SSH Action 详解

```yaml
- uses: appleboy/ssh-action@v1
  with:
    host: 47.104.212.121              # 服务器 IP
    username: ${{ secrets.SSH_USER }}    # SSH 用户名（如 root）
    key: ${{ secrets.SSH_PRIVATE_KEY }}  # SSH 私钥
    script: |
      # 要执行的 Shell 脚本
```

`key` 的内容应对应服务器 `~/.ssh/authorized_keys` 中的公钥。

#### 阿里云部署脚本

```yaml
script: |
  cd /opt/lyclaw

  # 拉取最新镜像
  docker pull ${{ env.DOCKER_REGISTRY }}/lyclaw-gateway:latest
  docker pull ${{ env.DOCKER_REGISTRY }}/lyclaw-orchestration:latest
  docker pull ${{ env.DOCKER_REGISTRY }}/lyclaw-ui:latest

  # 写入环境变量（从 GitHub Secrets 读取）
  echo "DEEPSEEK_API_KEY=${{ secrets.DEEPSEEK_API_KEY }}" > .env
  echo "TAVILY_API_KEY=${{ secrets.TAVILY_API_KEY }}" >> .env

  # 前端覆盖文件 (80 端口)
  cat > docker-compose.ui.yml << 'UIEOF'
  services:
    lyclaw-ui:
      image: ${{ env.DOCKER_REGISTRY }}/lyclaw-ui:latest
      container_name: lyclaw-ui
      ports:
        - "80:80"
      depends_on:
        - lyclaw-gateway
      restart: unless-stopped
  UIEOF

  # 重启服务（compose 会先 down 再 up）
  docker compose -f docker-compose.aliyun.yml -f docker-compose.ui.yml down
  docker compose -f docker-compose.aliyun.yml -f docker-compose.ui.yml up -d

  # 创建 Nacos 命名空间（幂等操作，重复调用不报错）
  sleep 5
  curl -s -X POST 'http://localhost:8848/nacos/v1/console/namespaces' \
    -d 'customNamespaceId=lyclaw&namespaceName=lyclaw' || true

  docker image prune -f       # 清理无用的旧镜像
```

**关于 heredoc（`<< 'UIEOF'`）**：
- 分隔符用单引号包裹（`'UIEOF'`）表示禁止 shell 变量展开
- 但 `${{ }}` 是 GitHub Actions 表达式，在工作流解析阶段就已替换
- 所以最终的 shell 脚本中，`${{ env.DOCKER_REGISTRY }}` 已经是 `docker.io/haikunye`

**多 compose 文件合并**：
`docker compose -f a.yml -f b.yml up -d` 会将两个文件合并。
对于相同的 service，b.yml 会覆盖 a.yml 的定义。

#### 健康检查

```yaml
- name: 阿里云健康检查
  run: |
    sleep 10
    curl -f http://47.104.212.121:80 || exit 1        # 前端
    curl -f http://47.104.212.121:8080/actuator/health || exit 1  # 网关
```
`curl -f`：HTTP 状态码 ≥400 时返回非零退出码（触发 failure）。
`sleep 10`：等待 Spring Boot 应用完全启动。

---

## 5. Docker 镜像构建体系

### 5.1 后端 Dockerfile（JRE + JAR）

**文件**：`Dockerfile`

```dockerfile
FROM eclipse-temurin:21-jre-alpine
```
使用 Eclipse Temurin JDK 21 的 JRE 精简版（alpine linux，体积小）。

```dockerfile
ARG SERVICE_MODULE
ENV SERVICE_MODULE=${SERVICE_MODULE}
```
`ARG` 在构建时通过 `--build-arg` 传入，`ENV` 将其转为环境变量（运行时可用）。

```dockerfile
RUN addgroup -S appgroup && adduser -S appuser -G appgroup
```
创建非 root 用户，安全最佳实践。

```dockerfile
COPY ${SERVICE_MODULE}/target/*.jar /app/app.jar
```
关键步骤：`${SERVICE_MODULE}` 会被替换为 `lyclaw-gateway` 等具体模块名，
从而复制对应模块的 JAR 文件。

```dockerfile
WORKDIR /app
USER appuser
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
```
`USER appuser`：以非 root 用户运行。
`EXPOSE 8080`：声明容器监听 8080 端口（文档性质，实际端口映射在 compose 中定义）。
`ENTRYPOINT` 使用 JSON 数组格式，不会启动 shell 进程。

### 5.2 前端 Dockerfile（Node 构建 + nginx）

**文件**：`Dockerfile.ui`

```dockerfile
# ===== 构建阶段 =====
FROM node:22-alpine AS build
WORKDIR /app
COPY lyclaw-ui/package*.json ./
RUN npm ci
COPY lyclaw-ui/ ./
RUN npm run build
```
多阶段构建（multi-stage build）的第一阶段：编译 Vue 项目。
`npm ci` 比 `npm install` 更快且严格遵守 lock 文件。
`npm run build` 执行 `vite build`，输出到 `dist/`。

```dockerfile
# ===== 运行阶段 =====
FROM nginx:alpine
COPY lyclaw-ui/nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /app/dist /usr/share/nginx/html
EXPOSE 80
CMD ["nginx", "-g", "daemon off;"]
```
`--from=build` 从构建阶段复制产物。
`nginx -g "daemon off;"`：前台运行 nginx（Docker 需要前台进程）。
最终镜像只包含 nginx + 静态文件，不包含 Node.js，体积大幅减小。

### 5.3 nginx 配置

**文件**：`lyclaw-ui/nginx.conf`

```nginx
server {
    listen 80;
    server_name _;

    root /usr/share/nginx/html;
    index index.html;

    # API 代理到 gateway（Docker 内部网络，用服务名访问）
    location /api {
        proxy_pass http://lyclaw-gateway:8080;
        proxy_http_version 1.1;
        proxy_set_header Upgrade $http_upgrade;
        proxy_set_header Connection "upgrade";
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_buffering off;           # 关键：SSE 流式响应必须关闭缓冲
        proxy_cache off;
        proxy_read_timeout 300s;       # SSE 长连接 5 分钟超时
    }

    # Vue Router history 模式
    location / {
        try_files $uri $uri/ /index.html;
    }

    # 静态资源长期缓存
    location /assets {
        expires 1y;
        add_header Cache-Control "public, immutable";
    }
}
```

关键配置说明：
- `proxy_http_version 1.1` + `Upgrade`/`Connection` headers：支持 WebSocket 升级
- `proxy_buffering off`：**SSE（Server-Sent Events）的本质是 HTTP 长连接流式响应**，
  如果 nginx 开启缓冲，SSE 事件会被缓存直到连接关闭才一次性返回，
  **前端将收不到任何实时消息**
- `proxy_read_timeout 300s`：后端 orchestration 执行可能很慢（LLM 调用），
  默认 60s 不够

---

## 6. Docker Compose 分布式编排

### 6.1 设计原则

每台服务器一份 compose 文件，各自包含该服务器上的服务和独立的 Nacos 实例。

### 6.2 阿里云 compose

**文件**：`docker-compose.aliyun.yml`

```yaml
version: '3.8'
services:
  nacos:
    image: nacos/nacos-server:v2.5.0
    container_name: lyclaw-nacos
    ports:
      - "8848:8848"
      - "9848:9848"
    environment:
      MODE: standalone          # 单机模式（生产可改为集群）
    healthcheck:
      test: curl -s http://localhost:8848/nacos/v1/console/health/readiness || exit 1
      interval: 10s
      timeout: 5s
      retries: 30

  lyclaw-gateway:
    image: haikunye/lyclaw-gateway:latest
    container_name: lyclaw-gateway
    ports:
      - "8080:8080"
    environment:
      SPRING_CLOUD_NACOS_DISCOVERY_SERVER_ADDR: nacos:8848   # ← 覆盖配置文件中的 127.0.0.1
    env_file:
      - .env                      # 从文件加载 DEEPSEEK_API_KEY 等
    depends_on:
      nacos:
        condition: service_healthy   # 等 Nacos 健康检查通过后才启动
    restart: unless-stopped

  lyclaw-orchestration:
    image: haikunye/lyclaw-orchestration:latest
    container_name: lyclaw-orchestration
    ports:
      - "8081:8081"
    environment:
      SPRING_CLOUD_NACOS_DISCOVERY_SERVER_ADDR: nacos:8848
    env_file:
      - .env
    depends_on:
      nacos:
        condition: service_healthy
    restart: unless-stopped
```

**关键设计决策：`SPRING_CLOUD_NACOS_DISCOVERY_SERVER_ADDR: nacos:8848`**

项目 `application.yml` 中写的是：
```yaml
spring.cloud.nacos.discovery.server-addr: 127.0.0.1:8848
```

在 Docker 容器中，`127.0.0.1` 指向容器自身，而 Nacos 运行在另一个容器。
Docker Compose 会自动创建网络，容器间可以通过**服务名**互相访问。
因此必须用环境变量覆盖为 `nacos:8848`。

Spring Boot 的属性绑定规则将 `spring.cloud.nacos.discovery.server-addr`
映射为环境变量 `SPRING_CLOUD_NACOS_DISCOVERY_SERVER_ADDR`。

### 6.3 华为云 compose

**文件**：`docker-compose.huaweiyun.yml`

结构与阿里云类似，部署 `lyclaw-action`（8084）和 `lyclaw-plan`（8083）。

### 6.4 腾讯云 compose

**文件**：`docker-compose.tengxunyun.yml`

结构与阿里云类似，部署 `lyclaw-memory`（8082）、`lyclaw-reflect`（8085）、
`lyclaw-facade`（8086）。

### 6.5 前端覆盖文件（动态生成）

前端服务通过 CD 脚本中的 heredoc 动态写入 `docker-compose.ui.yml`，
然后用 `-f a.yml -f ui.yml` 方式合并启动：

```yaml
services:
  lyclaw-ui:
    image: haikunye/lyclaw-ui:latest
    container_name: lyclaw-ui
    ports:
      - "80:80"
    depends_on:
      - lyclaw-gateway
    restart: unless-stopped
```

### 6.6 Nacos 命名空间

LyClaw 所有服务配置了 `spring.cloud.nacos.discovery.namespace: lyclaw`。
默认情况下 Nacos 没有这个命名空间，服务会注册失败。

解决方法：在 CD 脚本中通过 Nacos API 创建命名空间（幂等操作）：

```bash
curl -s -X POST 'http://localhost:8848/nacos/v1/console/namespaces' \
  -d 'customNamespaceId=lyclaw&namespaceName=lyclaw' || true
```

`|| true` 确保即使创建失败（如已存在）也不中断脚本。

---

## 7. GitHub Secrets 配置指南

### 7.1 什么是 GitHub Secrets

GitHub Secrets 是仓库级别的加密环境变量，存储在 GitHub 服务器端。
一旦设置，值不可在 UI 中查看（只能更新或删除），在 Action 日志中自动遮蔽。

### 7.2 配置路径

```
仓库主页 → Settings → Secrets and variables → Actions → Repository secrets
```

### 7.3 LyClaw 需要的 Secrets

| Secret 名称 | 用途 | 示例值 | 获取方式 |
|---|---|---|---|
| `DOCKERHUB_USERNAME` | Docker Hub 用户名 | `haikunye` | Docker Hub 注册用户名 |
| `DOCKERHUB_TOKEN` | Docker Hub 访问令牌 | `dckr_pat_xxx...` | hub.docker.com → Settings → Security → Access Tokens |
| `SSH_USER` | 服务器 SSH 用户名 | `root` | 服务器管理员账户 |
| `SSH_PRIVATE_KEY` | 服务器 SSH 私钥 | `-----BEGIN OPENSSH PRIVATE KEY-----...` | 本地 `~/.ssh/id_ed25519` |
| `DEEPSEEK_API_KEY` | DeepSeek 大模型 API Key | `sk-xxx...` | platform.deepseek.com |
| `TAVILY_API_KEY` | Tavily 搜索 API Key | `tvly-xxx...` | tavily.com |

### 7.4 SSH 密钥配置流程

```bash
# 1. 本地生成 SSH 密钥对（如果还没有）
ssh-keygen -t ed25519 -C "github-actions"

# 2. 将公钥复制到所有服务器（三台都要做）
ssh-copy-id root@47.104.212.121
ssh-copy-id root@113.45.200.224
ssh-copy-id root@81.71.120.92

# 3. 查看私钥内容（整个文件，包括开头和结尾的 ----- 行）
cat ~/.ssh/id_ed25519

# 4. 复制全部内容 → 粘贴到 GitHub Secrets 的 SSH_PRIVATE_KEY
```

### 7.5 Docker Hub Token 创建

1. 登录 hub.docker.com
2. 右上角头像 → Account Settings → Security → Personal Access Tokens
3. Generate new token → 勾选 Read & Write → 复制 token
4. 粘贴到 GitHub Secrets 的 `DOCKERHUB_TOKEN`

**安全提醒**：Token 只显示一次，刷新页面后无法再查看。如泄漏需立即删除并重新生成。

### 7.6 Repository secrets vs Environment secrets

```
Repository secrets        ← ✅ CD 工作流能读到的（不需要声明 environment）
Environment secrets       ← ❌ 需要 job 显式声明 environment: xxx 才能读
```

LyClaw 的 CD 工作流没有声明 `environment`，所以 Secrets 必须放在
**Repository secrets** 下。

---

## 8. 排错全记录

### 8.1 Docker Hub 登录失败：Username and password required

**现象**：
```
Run docker/login-action@v3
Error: Username and password required
```

**原因**：GitHub Secrets 中 `DOCKERHUB_TOKEN` 值在实际工作流执行时读到的是空值。

**排查步骤**：
1. 确认 Secret 名大小写完全一致（`DOCKERHUB_USERNAME` 和 `DOCKERHUB_TOKEN`）
2. 确认 Secret 在 **Repository secrets** 下而不是 Environment secrets
3. 确认 Docker Hub 上的 token 没有被撤销

**最终解决**：用户在聊天中不慎明文粘贴了 Docker token 导致曝光，
需要去 Docker Hub 删除旧 token 并生成新的，更新 GitHub Secret。

### 8.2 构建失败：Dockerfile not found

**现象**：
```
ERROR: failed to build: failed to solve: failed to read dockerfile:
open Dockerfile: no such file or directory
```

**原因**：项目根目录没有 Dockerfile。

**解决**：创建 `Dockerfile`（后端 JRE + JAR）和 `Dockerfile.ui`（前端 Node + nginx）。

### 8.3 模块名不匹配：reflection → reflect

**现象**：`Build & Push reflection` 的 JAR 复制步骤失败。

**原因**：CD 工作流矩阵使用 `reflection`，但 Maven 模块目录是 `lyclaw-reflect`。
`COPY lyclaw-reflection/target/*.jar` 找不到文件。

**解决**：统一改为 `reflect`，包括矩阵、upload-artifact 路径、部署脚本中的 pull 命令。

### 8.4 Nacos 连接失败：容器反复重启

**现象**：Spring Boot 启动后 Nacos 线程报 `InterruptedException`，容器反复重启。

**日志**：
```
ERROR c.a.nacos.common.notify.NotifyCenter - Event listener exception :
java.lang.InterruptedException
```

**原因**：`application.yml` 中 `server-addr: 127.0.0.1:8848` 在 Docker 容器内指向自身，
而 Nacos 运行在另一个容器中。

**解决**：在 compose 中注入环境变量 `SPRING_CLOUD_NACOS_DISCOVERY_SERVER_ADDR=nacos:8848`，
覆盖配置文件中的值。

### 8.5 阿里云部署超时

**现象**：SSH 脚本执行到 `docker compose down/up` 时超时。

**原因**：服务器上没有 `docker-compose.aliyun.yml` 文件，
`docker compose` 因找不到文件而阻塞。

**解决**：创建 compose 文件并通过 SCP 上传到 `/opt/lyclaw/`。

### 8.6 华为云健康检查端口错误

**现象**：`curl -f http://113.45.200.224:8081/actuator/health` 连接拒绝。

**原因**：健康检查端口 `8081` 是 orchestration 的端口，
但华为云部署的是 action（8084）和 plan（8083）。

**解决**：改为检查 8083（plan）。

### 8.7 `${{ env.JAVA_VERSION }}` 如何解析

这是一个常见误解。`env.JAVA_VERSION` 不是 GitHub Secret，而是**工作流文件内定义的** `env` 块：

```yaml
env:
  JAVA_VERSION: '21'
```

它在工作流解析阶段就被替换，无需在 GitHub UI 中配置。GitHub Secret 只用于
`${{ secrets.XXX }}` 语法。

---

## 9. 附录：常用命令速查

### 9.1 GitHub CLI

```bash
# 查看最近的 workflow 运行
gh run list --workflow=cd-distributed.yml --limit=5

# 查看某个运行的详细信息
gh run view <run-id>

# 查看运行日志
gh run view <run-id> --log

# 重新运行失败的 job
gh run rerun <run-id> --failed
```

### 9.2 Docker 运维

```bash
# 查看运行的容器
docker ps --format 'table {{.Names}}\t{{.Status}}\t{{.Ports}}'

# 查看容器日志（最近 50 行 + 跟踪）
docker logs --tail 50 -f lyclaw-gateway

# 查看镜像
docker images | grep lyclaw

# 清理悬挂镜像
docker image prune -f

# 手动拉取最新镜像
docker pull haikunye/lyclaw-gateway:latest

# 进入容器调试
docker exec -it lyclaw-gateway sh
```

### 9.3 Docker Compose

```bash
# 启动所有服务（后台）
docker compose -f docker-compose.aliyun.yml up -d

# 停止并删除所有容器
docker compose -f docker-compose.aliyun.yml down

# 查看日志
docker compose -f docker-compose.aliyun.yml logs --tail 50 -f

# 重启单个服务
docker compose -f docker-compose.aliyun.yml restart lyclaw-gateway

# 合并多个 compose 文件
docker compose -f docker-compose.aliyun.yml -f docker-compose.ui.yml up -d
```

### 9.4 Nacos 运维

```bash
# 健康检查
curl http://localhost:8848/nacos/v1/console/health/readiness

# 查看已注册的服务
curl http://localhost:8848/nacos/v1/ns/service/list?pageNo=1&pageSize=20

# 查看命名空间
curl http://localhost:8848/nacos/v1/console/namespaces

# 创建命名空间（幂等）
curl -X POST 'http://localhost:8848/nacos/v1/console/namespaces' \
  -d 'customNamespaceId=lyclaw&namespaceName=lyclaw'
```

### 9.5 GitHub Actions 内置环境变量

| 变量 | 含义 | 示例 |
|---|---|---|
| `GITHUB_SHA` | 触发 commit 的完整 SHA | `ed09092a1b2c...` |
| `GITHUB_REF` | 触发分支/tag 引用 | `refs/heads/main` |
| `GITHUB_REPOSITORY` | 仓库全名 | `Yehaikun/LyClaw` |
| `GITHUB_RUN_ID` | 本次运行唯一 ID | `25810239559` |
| `GITHUB_WORKFLOW` | 工作流名称 | `CD - Distributed Deploy` |
| `runner.os` | 运行器操作系统 | `Linux` |

### 9.6 工作流调试技巧

```yaml
# 1. 打印调试信息（注意值会被遮蔽如果是 secret）
- run: echo "Username length is ${#USERNAME}"   # 打印长度而非值

# 2. 条件跳过
- if: false   # 临时禁用某个步骤

# 3. 手动触发时打印输入
- run: echo "skip_tests=${{ inputs.skip_tests }}"

# 4. 设置失败后继续
- run: flaky-command
  continue-on-error: true
```

---

> **版本记录**：2026-05-14 初稿，覆盖从零配置到完整 CD 上线的全过程。
> 与之配套的操作文档见 `LyClaw-GitHub-CICD全流程指南.md`（侧重于使用和运维）。
