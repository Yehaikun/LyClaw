# LyClaw

> 轻量级多模型 AI 编排平台 — 统一接口、管道驱动、多 Agent 协作

<p align="center">
  <img src="https://img.shields.io/badge/status-alpha-e8a55a?style=flat-square" alt="status">
  <img src="https://img.shields.io/badge/version-2.0.0--SNAPSHOT-blue?style=flat-square" alt="version">
  <img src="https://img.shields.io/badge/license-MIT-yellow?style=flat-square" alt="license">
  <img src="https://img.shields.io/badge/Java-17-d34?style=flat-square" alt="java">
  <img src="https://img.shields.io/badge/Spring_Boot-3.5.14-6db33f?style=flat-square" alt="spring boot">
  <img src="https://img.shields.io/badge/Vue-3.5-4fc08d?style=flat-square" alt="vue">
</p>

---

## 架构

```
Request → Gateway → Facade ──→ Pipeline(Security → Context → ToolCall → Model → Response)
                    │   │
                    │   └──→ Adapter(DeepSeek / OpenAI / Anthropic / MiniMax ...)
                    └───→ Plan · Action · Reflect · Memory · Protocol(MCP/A2A)
```

LyClaw 采用分层管道架构：请求经网关路由至 facade 统一入口，进入 5 阶段管道处理链路。模型适配层屏蔽不同厂商 API 差异，六个领域服务环绕管道提供规划、执行、反思、记忆、协议等能力。

---

## 模块总览

### 核心链路

| 模块 | 职责 | 进度 |
|------|------|:----:|
| **lyclaw-gateway** | API 网关入口，路由转发，全局鉴权，Trace 注入 | `▸▸▸▸▸▸▸▸▸▸` 90% |
| **lyclaw-facade** | 统一门面，编排调度核心链路各服务 | `▸▸▸▸▸▸▸▸▸▸` 95% |
| **lyclaw-orchestration** | 引擎选择、管道执行、Agent 协调、多智能体共识 | `▸▸▸▸▸▸▸▸▸▸` 90% |
| **lyclaw-adapter** | 多模型适配层：DeepSeek / OpenAI / Anthropic / MiniMax | `▸▸▸▸▸▸▸▸▸▸` 95% |
| **lyclaw-core** | SPI 接口定义、领域模型、缓存、上下文契约 | `▸▸▸▸▸▸▸▸▸▸` 90% |

### 领域服务

| 模块 | 职责 | 进度 |
|------|------|:----:|
| **lyclaw-plan** | 任务规划与分解：ReAct / CoT / Hierarchical / DAG | `▸▸▸▸▸▸▸▸▸▸` 85% |
| **lyclaw-action** | 工具注册、技能执行、MCP 工具适配、多层沙箱 | `▸▸▸▸▸▸▸▸▸▸` 80% |
| **lyclaw-reflect** | 反思引擎：错误检测、质量评估、策略调整 | `▸▸▸▸▸▸▸▸▸▸` 75% |
| **lyclaw-memory** | 分层记忆系统：短期 / 长期 / 向量检索 / LLM 提取 | `▸▸▸▸▸▸▸▸▸▸` 80% |
| **lyclaw-protocol** | MCP 协议全实现 + A2A Agent 间通信网关 | `▸▸▸▸▸▸▸▸▸▸` 75% |

### 基础设施

| 模块 | 职责 | 进度 |
|------|------|:----:|
| **lyclaw-storage** | 持久化引擎：本地文件 / JSON / Markdown 多格式 | `▸▸▸▸▸▸▸▸▸▸` 90% |
| **lyclaw-common** | 共享模型、枚举、日志工具、错误码体系 | `▸▸▸▸▸▸▸▸▸▸` 95% |
| **lyclaw-infra** | 安全过滤、告警管理、指标采集、内部事件总线 | `▸▸▸▸▸▸▸▸▸▸` 70% |
| **lyclaw-ui** | Vue 3 前端：聊天界面、会话管理、Markdown 渲染 | `▸▸▸▸▸▸▸▸▸▸` 80% |

---

## 管道处理链路

```
SecurityStage  →  ContextBuildStage  →  ToolCallStage  →  ModelInvokeStage  →  ResponseProcessStage
  鉴权·过滤         上下文组装            工具循环调用         模型请求·SSE流        格式化·后处理
```

每个阶段职责单一，通过管道执行器串联。阶段可插拔，新能力通过增加 Stage 实现而非修改现有代码。

## 核心特性

- **统一模型接口** — 一套 API 调用所有模型，适配层自动处理差异
- **管道驱动** — 5 阶段管道，阶段可自由组合替换
- **多 Agent 协作** — Star 拓扑协调，共识引擎驱动 Agent 间通信
- **MCP 协议** — 完整实现 JSON-RPC 2.0 + MCP，支持 stdio / SSE 传输
- **分层记忆** — 工作记忆 → 短期 → 长期，向量检索 + LLM 提取双通路
- **SSE 流式响应** — 实时流式输出聊天内容，前端逐字渲染
- **工具生态** — 注解驱动注册，内置多层安全沙箱
- **反思机制** — 自动错误检测 + 质量评估 + 策略调整反馈

## 技术栈

| 层 | 技术 |
|----|------|
| **后端框架** | Spring Boot 3.5.14 · Spring Cloud · Spring WebFlux |
| **服务发现** | Nacos |
| **远程调用** | OpenFeign |
| **语言** | Java 17 |
| **前端框架** | Vue 3.5 · TypeScript · Vite 8 |
| **状态管理** | Pinia |
| **Markdown** | marked · DOMPurify · highlight.js · mermaid |
| **图标** | Lucide Vue Next |
| **构建** | Maven (后端) · npm (前端) |

## 快速开始

```bash
# 1. 启动后端基础设施（Nacos）
docker-compose up -d

# 2. 启动所有微服务
./count.sh        # 共 8 个服务，按依赖顺序启动
#   gateway → orchestration → plan / action / reflect / memory / protocol

# 3. 启动前端
cd lyclaw-ui
npm install
npm run dev        # http://localhost:5173
```

## 项目结构

```
LyClaw/
├── lyclaw-gateway/        # API 网关
├── lyclaw-facade/         # 统一门面
├── lyclaw-orchestration/  # 编排服务
├── lyclaw-adapter/        # 模型适配器
├── lyclaw-core/           # 核心 SPI + 领域模型
├── lyclaw-plan/           # 规划服务
├── lyclaw-action/         # 动作执行服务
├── lyclaw-reflect/        # 反思服务
├── lyclaw-memory/         # 记忆服务
├── lyclaw-protocol/       # MCP/A2A 协议服务
├── lyclaw-storage/        # 存储引擎
├── lyclaw-common/         # 共享模型·枚举·工具类
├── lyclaw-infra/          # 基础设施
├── lyclaw-ui/             # Vue 3 前端
│   └── src/
│       ├── api/           # HTTP/SSE 客户端
│       ├── components/    # 通用组件
│       ├── stores/        # Pinia 状态管理
│       ├── views/         # 页面视图
│       └── assets/        # 样式·设计令牌
├── docker-compose.yml     # 容器编排
└── ARCHITECTURE.md        # 详细架构文档
```

## 许可证

MIT · [SECURITY.md](./SECURITY.md)
