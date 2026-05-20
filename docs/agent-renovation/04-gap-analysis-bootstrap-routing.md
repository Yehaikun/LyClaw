# LyClaw vs OpenClaw 差距分析：启动引导、路由、身份、群聊、心跳、人类延迟、TTS、区块流式传输

## 执行摘要

本文档提供了 LyClaw（当前状态）与 OpenClaw（目标参考）在八个核心系统领域的全面、逐功能差距分析：工作空间启动引导、智能体路由与绑定、身份、群聊、心跳、人类延迟、TTS 和区块流式传输。每个功能都从 LyClaw 中的当前实现状态、对应的 OpenClaw 能力、差距严重程度（P0/P1/P2/P3）、实现复杂度和推荐迁移方法等方面进行了检查。

---

## 严重程度说明

| 等级 | 含义 | 需要采取的行动 |
|-------|---------|-----------------|
| **P0** | 阻塞 — 阻碍生产部署或多智能体运行 | 上线前必须实现 |
| **P1** | 关键 — 严重限制功能对等性、用户体验 | 应在接下来的 1-2 个里程碑中实现 |
| **P2** | 重要 — 锦上添花，支持高级用例 | 规划到长期路线图中 |
| **P3** | 增强 — 外观优化、小众需求或实验性功能 | 在资源允许时考虑 |

## 复杂度说明

| 等级 | 含义 | 典型工作量 |
|-------|---------|---------------|
| **高** | 需要架构变更、新建子系统、多模块协调 | 3-6 周 |
| **中** | 新建模块或对现有模块进行重大扩展 | 1-3 周 |
| **低** | 配置驱动、添加注解、独立工具类 | 2-5 天 |
| **极低** | 单文件添加、属性配置、简单透传 | < 1 天 |

---

## 1. 工作空间启动引导

### 1.1 概述

工作空间启动引导是一种机制，智能体的个性、行为准则、运行时上下文和身份通过智能体工作空间目录中的结构化文件加载。在 OpenClaw 中，这是智能体配置的基础——即智能体"知道自己是谁"的方式。

### 1.2 LyClaw 当前状态

| 方面 | 状态 | 详情 |
|--------|-------|---------|
| **机制** | 仅有 `@SystemMessage` 注解 | 在智能体接口方法上使用单个注解 `@SystemMessage("text")`。`AgentInvocationHandler.resolveSystemMessage()` 方法读取此注解，并使用 `@V` 注解的参数替换 `{{varname}}` 模板占位符。 |
| **文件加载** | 无 | 没有基于文件系统的加载。没有 AGENTS.md、SOUL.md、BOOTSTRAP.md 或任何工作空间文件概念。 |
| **上下文注入** | 无 | 没有"always"、"continuation-skip"或"never"注入策略的概念。系统提示是每个方法静态的。 |
| **最大字符数** | 无 | 没有大小限制，没有截断警告。注解中的内容直接原样发送。 |
| **紧凑后注入** | 无 | 没有紧凑化后注入段落的概念。没有从工作空间文件中提取 H2/H3 段落的概念。 |
| **可选文件** | 无 | 没有可选启动引导文件（SOUL.md、USER.md、HEARTBEAT.md、IDENTITY.md）或跳过控制的概念。 |
| **模板变量** | 仅有 `{{varname}}` | 仅支持参数级别的 `@V` 替换。没有上下文感知的模板变量（例如 `{{agentName}}`、`{{currentDate}}`、`{{userName}}`）。 |

关键代码：`AgentInvocationHandler.java` 第 353-382 行（系统消息解析），`SystemMessage.java` 注解定义。

### 1.3 OpenClaw 功能详情

OpenClaw 实现了多层启动引导系统：

```
工作空间根目录/
  AGENTS.md        # 主文件：智能体行为、系统提示、工具使用指南
  SOUL.md          # 可选：个性、语调、更深层的角色定义
  BOOTSTRAP.md     # 核心启动引导配置、钩子、初始化序列
  IDENTITY.md      # 可选：头像、显示名称、智能体如何展现自己
  USER.md          # 可选：用户特定上下文、每用户偏好设置
  HEARTBEAT.md     # 轻量级上下文，仅用于心跳会话
```

**配置键：**
- `bootstrapMaxChars`（默认 20000）：单个启动引导文件的最大字符数
- `bootstrapTotalMaxChars`（默认 150000）：所有启动引导文件的最大总字符数
- `contextInjection`：`"always"` | `"continuation-skip"` | `"never"` — 控制启动引导上下文何时注入到提示中
- `bootstrapPromptTruncationWarning`：`"off"` | `"once"` | `"always"` — 控制启动引导超出限制时的截断警告行为
- `skipBootstrap`：布尔值 — 完全禁用启动引导加载
- `skipOptionalBootstrapFiles`：要跳过的文件名数组（通常为 SOUL.md、USER.md、HEARTBEAT.md、IDENTITY.md）
- `postCompactionSections`：上下文紧凑化后需重新注入的 AGENTS.md 中的 H2/H3 段落名称列表

### 1.4 逐功能差距分析

| 功能 | LyClaw | OpenClaw | 差距 | 严重程度 | 复杂度 |
|---------|--------|----------|-----|----------|------------|
| **主系统提示** | `@SystemMessage` 注解值 | AGENTS.md 文件内容 | 注解缺乏多文件、多段落支持；无文件重载 | P0 | 中 |
| **工作空间文件加载** | 无 | 使用 glob/正则模式遍历文件系统 | 整个子系统缺失 | P0 | 中 |
| **多文件启动引导** | 每个方法只有一个字符串 | AGENTS.md + SOUL.md + BOOTSTRAP.md + IDENTITY.md + USER.md + HEARTBEAT.md | 5 种额外的文件类型，各自具有不同的语义 | P0 | 中 |
| **启动引导最大字符数控制** | 无 | `bootstrapMaxChars`（20000）、`bootstrapTotalMaxChars`（150000） | 无大小管控；存在 token 预算爆炸的风险 | P1 | 低 |
| **截断警告** | 无 | `bootstrapPromptTruncationWarning`：off/once/always | 启动引导被截断时无用户反馈 | P2 | 低 |
| **上下文注入策略** | 始终注入（如果注入了注解） | `contextInjection`：always/continuation-skip/never | 无 continuation-skip；不必要的重复注入浪费 token | P1 | 低 |
| **可选文件控制** | 无 | 每个智能体的 `skipOptionalBootstrapFiles` | 无法精细调整每个智能体加载哪些可选文件 | P2 | 低 |
| **紧凑化后段落** | 无 | `postCompactionSections`：紧凑化后重新注入 H2/H3 | 无紧凑化感知；长时间对话后智能体丢失关键上下文 | P1 | 中 |
| **模板变量引擎** | `{{varname}}` 仅参数级别 | 多来源：环境变量、渠道上下文、用户身份、时间戳 | 无法根据运行时上下文个性化系统提示 | P1 | 低 |
| **文件热重载** | 不适用（基于注解，静态） | 文件系统监视器，变更时重新读取 | 注解变更需要重新编译 + 重启；OpenClaw 可以热重载 | P2 | 中 |
| **完全跳过启动引导** | 不适用（无启动引导） | `skipBootstrap` 布尔值 | 目前已有效跳过；实现启动引导后此功能才相关 | P2 | 低 |

### 1.5 实现路线图

1. **阶段 1（P0）**：创建 `WorkspaceBootstrap` 服务，从可配置目录读取 AGENTS.md。与 `AgentInvocationHandler` 集成，将文件内容注入系统提示。支持简单的模板变量。
2. **阶段 2（P1）**：添加 BOOTSTRAP.md、IDENTITY.md 加载。实现 `contextInjection` 策略以及带有截断警告的最大字符数控制。
3. **阶段 3（P2）**：添加 SOUL.md、USER.md、HEARTBEAT.md。实现用于热重载的文件监视器。添加紧凑化后段落重新注入。

---

## 2. 启动上下文

### 2.1 概述

启动上下文提供了关于当前环境（日期、最近的对话历史、系统状态）的轻量级、临时的简报，在新会话或重置会话开始时注入。它与启动引导的不同之处在于，它是动态和时间敏感的，而不是静态的个性定义。

### 2.2 LyClaw 当前状态

| 方面 | 状态 | 详情 |
|--------|-------|---------|
| **机制** | 无 | 完全没有启动上下文注入。没有每日记忆加载，没有环境简报。 |
| **会话初始化** | 手动 | `ChatController.createSession()` 仅生成一个 UUID。没有上下文准备。 |
| **应用触发器** | 无 | 没有"new"与"reset"会话事件触发上下文注入的概念。 |

### 2.3 OpenClaw 功能详情

```yaml
agentStartupContext:
  enabled: true                  # 主开关
  applyOn: ["new", "reset"]     # 哪些会话事件触发注入
  dailyMemoryDays: 2            # 回顾最近 N 天的交互记录
  maxFileBytes: 16384           # 任何单个文件的最大字节数
  maxFileChars: 1200            # 任何单个来源的最大字符数
  maxTotalChars: 2800           # 最大注入总字符数
```

### 2.4 差距分析

| 功能 | LyClaw | OpenClaw | 差距 | 严重程度 | 复杂度 |
|---------|--------|----------|-----|----------|------------|
| **启动上下文注入** | 无 | `AgentStartupContextConfig` | 整个子系统缺失；新会话缺乏环境感知能力 | P1 | 中 |
| **启用/禁用控制** | 无 | `enabled: true/false` | 无法开关 | P2 | 低 |
| **应用触发器** | 无 | `applyOn: ["new","reset"]` | 需要会话生命周期感知 | P2 | 低 |
| **每日记忆加载** | 无 | `dailyMemoryDays: 2` | 会话开始没有最近历史摘要 | P1 | 中 |
| **大小控制** | 无 | maxFileBytes、maxFileChars、maxTotalChars | 无预算控制 | P2 | 低 |

---

## 3. 智能体路由与绑定

### 3.1 概述

智能体路由是一种机制，根据消息通过哪个渠道到达、谁发送了消息以及模式匹配规则来确定哪个智能体（或哪个智能体配置）处理传入消息。在多渠道、多智能体部署中，这是核心的分发层。

### 3.2 LyClaw 当前状态

| 方面 | 状态 | 详情 |
|--------|-------|---------|
| **路由模型** | 无 — 单智能体、单端点 | `ChatController` 注入一个 `ChatAgent` Bean。`POST /api/chat/stream` 直接映射到 `chatAgent.chatStream()`。没有路由逻辑。 |
| **渠道概念** | 无 | 没有渠道抽象。唯一的"渠道"是 HTTP 端点本身。没有 Telegram、Discord、WhatsApp 或任何消息平台渠道的概念。 |
| **智能体绑定** | 无 | 没有 `AgentBinding`、`AgentRouteBinding` 或 `AgentAcpBinding` 类。 |
| **匹配规则** | 无 | 没有匹配模式，没有账户/公会见/团队/角色/对等方过滤。 |
| **ACP（智能体通信协议）** | 无 | 没有外部智能体后端连接概念。 |
| **多智能体分发** | 不支持 | 每个 JVM 只有一个智能体接口。多个智能体需要单独的 Bean 和单独的端点。 |
| **基于角色的路由** | 无 | 没有 Discord 角色或任何基于角色的路由。 |

关键代码：`ChatController.java`（单个 `ChatAgent` Bean，单个端点），`ChatAgent.java`（单个智能体接口）。

### 3.3 OpenClaw 功能详情

```
AgentBinding
  ├── AgentRouteBinding
  │     type: "route"
  │     agentId: string           # 处理此匹配的智能体
  │     comment: string           # 人类可读的描述
  │     match: AgentBindingMatch  # 模式匹配规则
  │     session: session 配置      # 此路由的会话管理
  │
  └── AgentAcpBinding
        type: "acp"
        agentId: string
        comment: string
        match: AgentBindingMatch
        acp:                     # 外部智能体后端覆盖
          url: string
          timeout: duration
          headers: map

AgentBindingMatch
  channel: string (必填)          # 渠道模式："#general"、"@botname"、"*"
  accountId: string              # 特定消息账户
  peer: { chatType, id }        # 特定对等方/聊天标识符
  guildId: string               # Discord 公会
  teamId: string                # MS Teams 团队
  roles: string[]               # Discord 基于角色的路由
```

**关键路由模式：**
- **基于渠道**：匹配渠道名称/ID 模式
- **基于账户**：根据哪个消息账户收到消息进行路由
- **基于对等方**：根据发送者身份路由到特定智能体
- **基于角色**：Discord 角色权限决定哪个智能体处理消息
- **ACP 路由**：转发到外部智能体后端

### 3.4 逐功能差距分析

| 功能 | LyClaw | OpenClaw | 差距 | 严重程度 | 复杂度 |
|---------|--------|----------|-----|----------|------------|
| **智能体分发/路由** | 单智能体，无路由 | 多智能体分发，支持模式匹配 | 无法支持不同渠道上的多个智能体 | P0 | 高 |
| **渠道抽象** | 无 | 具有平台特定适配器的渠道概念（Telegram、Discord、WhatsApp 等） | 无法与消息平台集成 | P0 | 高 |
| **AgentBinding 模型** | 无 | `AgentRouteBinding` + `AgentAcpBinding` 类 | 核心领域模型缺失 | P0 | 中 |
| **绑定匹配规则** | 无 | Channel、accountId、peer、guildId、teamId、roles | 完全没有匹配语义 | P0 | 中 |
| **基于模式的匹配** | 无 | 渠道模式如 "#general"、"@botname"、通配符 | 无灵活的匹配语法 | P0 | 低 |
| **ACP（外部智能体后端）** | 无 | `AgentAcpBinding` 带有 URL、超时、请求头 | 无法代理到外部智能体服务 | P2 | 高 |
| **基于角色的路由** | 无 | Discord 角色 → 智能体选择 | 平台特定的高级功能 | P3 | 中 |
| **多账户路由** | 无 | 匹配规则中的 `accountId` | 仅支持单账户部署 | P1 | 中 |
| **每路由会话** | 无 | 每个绑定的会话配置 | 无每路由会话隔离 | P1 | 低 |
| **动态绑定重载** | 不适用 | 无需重启即可热重载绑定 | 更改智能体分配需要重启 | P2 | 中 |
| **回退/默认路由** | 隐式（只有一个智能体） | 无匹配时的显式默认路由 | 无显式回退语义 | P1 | 低 |

### 3.5 实现路线图

1. **阶段 1（P0）**：创建 `AgentRouter` 接口和 `DefaultAgentRouter`。实现带有 `AgentRouteBinding` 和 `AgentBindingMatch` 的 `AgentBinding` 领域模型。支持带有简单字符串模式的基于渠道的匹配。重构 `ChatController` 使用路由器而不是直接注入智能体。
2. **阶段 2（P1）**：添加基于对等方的匹配、基于账户的路由。实现每路由会话配置。添加显式默认路由回退。
3. **阶段 3（P2）**：添加 `AgentAcpBinding` 用于外部智能体后端代理。动态绑定重载。
4. **阶段 4（P3）**：为 Discord 和其他平台实现基于角色的路由。

---

## 4. 身份

### 4.1 概述

智能体身份定义了智能体如何向用户展示自己：其显示名称、头像、消息中的名称前缀、响应格式和确认反应。身份是一个核心 UX 概念，它使多智能体系统感觉独特和个性化。

### 4.2 LyClaw 当前状态

| 方面 | 状态 | 详情 |
|--------|-------|---------|
| **身份配置** | 无 | 没有 `IdentityConfig` 类，没有与身份相关的属性。 |
| **显示名称** | 无 | 智能体通过 `@Agent(name = "chat")` 命名，但这仅是内部的，从不显示给用户。 |
| **头像** | 无 | 没有头像概念。没有本地文件、远程 URL 或数据 URI 头像解析。 |
| **名称前缀** | 无 | 消息显示为原始内容，没有"[机器人名称]"前缀格式。 |
| **消息前缀/响应前缀** | 无 | 没有 `resolveMessagePrefix` 或 `resolveResponsePrefix` 逻辑。 |
| **确认反应** | 无 | 没有表情符号反应确认（例如，任务完成时的勾号）。 |
| **有效消息配置** | 无 | 没有每智能体消息格式覆盖。 |

关键发现：`@Agent` 注解有 `name` 和 `description` 字段，但它们仅用于框架内的注册/标识。`AgentConfig` 对象（`AgentConfig.java`）有 name/description/version/model/provider，但没有任何与身份相关的内容。

### 4.3 OpenClaw 功能详情

```
IdentityConfig
  agentId: string
  displayName: string           # "支持机器人"、"代码助手"
  avatar: AgentAvatarResolution # 四种类型之一（见下文）
  namePrefix: string            # 此智能体的所有消息前添加此前缀
  messagePrefix: string         # 发送给此智能体的用户消息前添加此前缀
  responsePrefix: string        # 助手响应前添加此前缀
  ackReaction: string           # 消息确认时发送的表情符号反应

AgentAvatarResolution（联合类型）
  { kind: "none" }              # 无头像
  { kind: "local", filePath }   # 本地文件路径的图像
  { kind: "remote", url }       # 远程图像 URL
  { kind: "data", url }         # 数据 URI（base64 编码）

解析函数：
  resolveAgentIdentity          # 完整身份解析
  resolveIdentityNamePrefix     # 仅名称前缀
  resolveMessagePrefix          # 用户消息前的前缀
  resolveResponsePrefix         # 助手响应前的前缀
  resolveAckReaction            # 确认表情符号
  resolveEffectiveMessagesConfig # 合并后的消息格式配置
```

### 4.4 逐功能差距分析

| 功能 | LyClaw | OpenClaw | 差距 | 严重程度 | 复杂度 |
|---------|--------|----------|-----|----------|------------|
| **身份配置** | 无（`AgentConfig` 仅有 name/model） | `IdentityConfig` 带有 displayName、avatar、前缀、反应 | 无每智能体视觉身份；所有智能体看起来相同 | P1 | 中 |
| **显示名称** | `@Agent(name=...)` — 仅内部使用 | `displayName` — 面向用户，可本地化 | 用户在聊天中看不到智能体名称 | P1 | 低 |
| **头像（无）** | 不适用 | `{kind:"none"}` | 作为默认实现很简单 | P2 | 低 |
| **头像（本地文件）** | 无 | `{kind:"local", filePath}` | 需要文件服务和 MIME 类型检测 | P2 | 低 |
| **头像（远程 URL）** | 无 | `{kind:"remote", url}` | 简单的 URL 透传；有代理/缓存考虑 | P2 | 低 |
| **头像（数据 URI）** | 无 | `{kind:"data", url}` | Base64 解码；可能存在大型内联数据 | P3 | 低 |
| **名称前缀** | 无 | `resolveIdentityNamePrefix` | 多智能体环境中消息缺乏智能体归属 | P1 | 低 |
| **消息前缀** | 无 | `resolveMessagePrefix` | 无法自定义如何为用户消息框定上下文 | P2 | 低 |
| **响应前缀** | 无 | `resolveResponsePrefix` | 无法自定义每智能体的响应格式 | P2 | 低 |
| **确认反应** | 无 | `resolveAckReaction`（表情符号） | 无消息已收到的视觉确认 | P3 | 低 |
| **消息配置合并** | 无 | `resolveEffectiveMessagesConfig` | 无法覆盖每智能体的消息格式 | P3 | 低 |

### 4.5 实现路线图

1. **阶段 1（P1）**：创建带有 displayName、namePrefix 的 `IdentityConfig` 类。集成到 `AgentInvocationHandler` 和 SSE 事件发送中，在流式响应头部包含身份元数据。在前端展示 displayName。
2. **阶段 2（P2）**：添加头像解析（local/remote/none）。添加消息前缀和响应前缀解析。接入消息构建管道。
3. **阶段 3（P3）**：添加确认反应支持。添加数据 URI 头像支持。实现 `resolveEffectiveMessagesConfig` 合并。

---

## 5. 群聊

### 5.1 概述

群聊管理控制智能体在多参与者环境中的行为：是否需要显式提及才能响应、群组成员可以使用哪些工具、谁可以触发哪些操作，以及是否存在命名的访问控制群组。

### 5.2 LyClaw 当前状态

| 方面 | 状态 | 详情 |
|--------|-------|---------|
| **群组策略** | 无 | 完全没有群聊概念。智能体平等对待所有输入，单用户，没有任何提及或访问控制语义。 |
| **要求提及** | 无 | 智能体始终响应；没有提及门控。 |
| **接收策略** | 无 | 无法控制智能体是否/如何读取未被提及的消息。 |
| **群组工具** | 无 | 所有人使用相同的工具；没有每发送者或每组工具限制。 |
| **访问群组** | 无 | 没有用于跨账户白名单管理的命名访问群组。 |
| **激活模式** | 无 | 没有"mention"与"always"激活区分。 |
| **发送者访问评估** | 无 | 没有 `evaluateSenderGroupAccess` 或 `resolveToolsBySender` 逻辑。 |

### 5.3 OpenClaw 功能详情

```
GroupPolicy
  requireMention: boolean         # 智能体仅在被 @提及 时响应
  ingest: "all" | "mentions_only" # 智能体读取哪些消息
  tools: GroupToolPolicyConfig    # 群组上下文中的工具可用性
  toolsBySender: map<string, GroupToolPolicyConfig>  # 每发送者工具覆盖

GroupToolPolicyConfig
  allowedTools: string[]          # 工具名称白名单
  blockedTools: string[]          # 工具名称黑名单
  allowAllTools: boolean          # 覆盖为允许全部

GroupActivationMode
  "mention"                       # 智能体仅在 @提及 时激活
  "always"                        # 智能体读取所有消息

AccessGroupConfig
  name: string                    # 命名群组标识符
  members: string[]               # 成员标识符（电话号码、聊天 ID）

解析函数：
  resolveChannelGroupPolicy       # 每渠道群组策略解析
  resolveChannelGroupRequireMention # requireMention 解析
  resolveChannelGroupToolsPolicy  # 给定渠道群组的工具策略
  evaluateSenderGroupAccess       # 此发送者可以使用此智能体吗？
  resolveToolsBySender            # 此发送者可以使用哪些工具？
  resolveAccessGroupAllowFromState # 访问群组白名单展开
  expandAllowFromWithAccessGroups  # 跨账户白名单展开
```

### 5.4 逐功能差距分析

| 功能 | LyClaw | OpenClaw | 差距 | 严重程度 | 复杂度 |
|---------|--------|----------|-----|----------|------------|
| **群聊支持** | 无 | 每渠道 `GroupPolicy` 配置 | 没有多参与者环境的概念 | P0 | 高 |
| **要求提及** | 无（始终响应） | `requireMention: boolean` | 智能体在群中回复每条消息；无法用于群组部署 | P0 | 中 |
| **接收策略** | 无 | `ingest: "all" \| "mentions_only"` | 无法控制隐私上下文；智能体看到所有消息 | P1 | 低 |
| **群组工具限制** | 无 | `GroupToolPolicyConfig`（allowedTools、blockedTools、allowAllTools） | 危险工具对所有群组成员可用 | P0 | 中 |
| **每发送者工具覆盖** | 无 | `toolsBySender: map` | 无法为管理员提供更高的工具访问权限，为普通用户提供受限权限 | P2 | 中 |
| **访问群组** | 无 | 带有命名群组和成员列表的 `AccessGroupConfig` | 无法跨渠道定义可复用的白名单 | P2 | 中 |
| **激活模式** | 无 | `GroupActivationMode: "mention" \| "always"` | 无法控制智能体如何加入对话 | P1 | 低 |
| **发送者访问评估** | 无 | `evaluateSenderGroupAccess` | 完全没有发送者授权 | P1 | 中 |
| **白名单展开** | 无 | `expandAllowFromWithAccessGroups` | 无法在渠道配置中引用访问群组 | P2 | 低 |

### 5.5 实现路线图

1. **阶段 1（P0）**：创建带有 `requireMention`、`GroupToolPolicyConfig` 的 `GroupPolicy` 领域模型。在传入消息处理中实现提及检测。根据 `requireMention` 门控智能体响应。实现每群组策略的工具允许/阻止过滤。
2. **阶段 2（P1）**：添加 `ingest` 策略。添加 `GroupActivationMode`。实现发送者访问评估和每发送者工具覆盖。
3. **阶段 3（P2）**：添加 `AccessGroupConfig` 和命名访问群组。实现白名单展开和跨账户支持。

---

## 6. 心跳

### 6.1 概述

心跳是一种按计划自动调用智能体的机制。按可配置的时间间隔，智能体会唤醒，可选地加载轻量级上下文（HEARTBEAT.md），并向指定的目标渠道/用户发送主动消息或状态更新。这使得智能体可以主动行动，而不仅仅是被动响应。

### 6.2 LyClaw 当前状态

| 方面 | 状态 | 详情 |
|--------|-------|---------|
| **计划调用** | 无 | 没有基于调度器的智能体调用。存在 `CronJob` 模型类，但没有 cron/心跳执行引擎。 |
| **时间间隔控制** | 无 | 没有 `every` 持续时间配置。 |
| **活跃时间** | 无 | 没有时间窗口限制（例如，仅在工作时间心跳）。 |
| **投递目标** | 无 | 没有投递到特定渠道或用户的概念。 |
| **轻量级上下文** | 无 | 没有用于轻量级会话的 HEARTBEAT.md 加载。 |
| **忙碌跳过** | 无 | 没有 `skipWhenBusy` 机制。 |
| **隔离会话** | 无 | 没有 `isolatedSession` 概念（每次心跳使用全新会话）。 |

### 6.3 OpenClaw 功能详情

```
HeartbeatConfig
  every: duration                     # 默认 30m；心跳间隔
  activeHours: {                       # 可选时间窗口
    start: string                     # "09:00"
    end: string                       # "17:00"
    timezone: string                  # IANA 时区
  }
  model: string                       # 心跳调用的模型覆盖
  sessionKey: string                  # 持久化会话键
  deliveryTarget: "last" | "none" | channelId  # 心跳消息投递到何处
  directPolicy: "allow" | "block"     # 私聊消息是否触发心跳
  to: string                          # 用于 WhatsApp 的 E.164，用于 Telegram 的 chatId
  accountId: string                   # 通过哪个账户发送
  prompt: string                      # 心跳的提示覆盖
  includeSystemPromptSection: boolean # 是否包含主系统提示
  ackMaxChars: number                 # 默认 30；确认中的最大字符数
  suppressToolErrorWarnings: boolean  # 抑制心跳中的工具错误
  timeoutSeconds: number              # 每次心跳的超时时间
  lightContext: boolean               # 仅使用 HEARTBEAT.md，跳过其他启动引导
  isolatedSession: boolean            # 每次心跳使用全新会话
  skipWhenBusy: boolean               # 如果智能体正在处理其他消息则跳过
  includeReasoning: boolean           # 在心跳响应中包含推理
```

### 6.4 逐功能差距分析

| 功能 | LyClaw | OpenClaw | 差距 | 严重程度 | 复杂度 |
|---------|--------|----------|-----|----------|------------|
| **计划心跳** | 无 | `every: duration` 类 cron 调度 | 无主动智能体能力；纯被动 | P1 | 中 |
| **时间间隔配置** | 无 | `every: 30m`（默认） | 无调度基础设施 | P1 | 低 |
| **活跃时间窗口** | 无 | `{start, end, timezone}` | 无法将心跳限制在工作时间内 | P2 | 低 |
| **模型覆盖** | 无 | `model: string` | 心跳使用与普通聊天相同的模型 | P3 | 低 |
| **会话键** | 无 | `sessionKey: string` | 无跨心跳的持久会话状态 | P2 | 低 |
| **投递目标** | 无 | `"last" \| "none" \| channelId` | 没有心跳输出到何处的概念 | P1 | 低 |
| **私聊策略** | 无 | `"allow" \| "block"` | 无法控制私聊触发心跳 | P3 | 低 |
| **接收方（to）** | 无 | E.164 / chatId | 无每心跳接收方路由 | P1 | 低 |
| **账户路由** | 无 | `accountId: string` | 无法通过特定消息账户路由心跳 | P2 | 低 |
| **提示覆盖** | 无 | `prompt: string` | 心跳使用标准系统提示 | P2 | 低 |
| **包含系统提示** | 无 | `includeSystemPromptSection: boolean` | 无段落级别控制 | P3 | 低 |
| **确认最大字符数** | 无 | `ackMaxChars: 30` | 无确认裁剪 | P3 | 低 |
| **抑制工具错误** | 无 | `suppressToolErrorWarnings: boolean` | 工具错误始终记录/报告 | P3 | 低 |
| **超时** | 无 | `timeoutSeconds: number` | 使用全局智能体超时 | P2 | 低 |
| **轻量上下文** | 无 | `lightContext: boolean`（仅 HEARTBEAT.md） | 无轻量级启动引导模式 | P2 | 低 |
| **隔离会话** | 无 | `isolatedSession: boolean` | 心跳共享会话历史 | P2 | 低 |
| **忙碌时跳过** | 无 | `skipWhenBusy: boolean` | 可能触发重叠调用 | P2 | 低 |
| **包含推理** | 无 | `includeReasoning: boolean` | 无推理开关 | P3 | 低 |

### 6.5 实现路线图

1. **阶段 1（P1）**：使用 Spring 的 `@Scheduled` 或 `TaskScheduler` 创建 `HeartbeatScheduler` 服务。实现带有 `every`、`to`、`deliveryTarget` 的 `HeartbeatConfig` 领域模型。与 `AgentInvocationHandler` 集成以进行心跳触发的调用。将投递接入渠道适配器。
2. **阶段 2（P2）**：添加 `activeHours`、`lightContext`、`isolatedSession`、`skipWhenBusy`。添加 HEARTBEAT.md 启动引导文件加载。
3. **阶段 3（P3）**：添加模型覆盖、提示覆盖、确认裁剪、抑制工具错误、包含推理。

---

## 7. 人类延迟

### 7.1 概述

人类延迟模拟区块回复之间的自然打字停顿。在流式或多消息响应中，此功能插入可配置的延迟以模拟人类打字速度，使智能体在对话界面中感觉更自然，减少机械感。

### 7.2 LyClaw 当前状态

| 方面 | 状态 | 详情 |
|--------|-------|---------|
| **延迟机制** | 无 | SSE 事件以 LLM 生成 token 的最快速度发送。区块之间没有人工延迟。 |
| **每智能体配置** | 无 | 没有 `HumanDelayConfig` 类。没有 `resolveHumanDelayConfig` 解析逻辑。 |
| **延迟计算** | 无 | 没有基于字符数或基于区块数的延迟公式。 |

相关代码：在 `DefaultReActEngine` 中，`splitIntoEvents()` 在句子边界处切分文本，并通过 `Flux.fromIterable()` 立即发送。没有使用任何延迟操作符（`delayElements`、`delaySequence`）。

### 7.3 OpenClaw 功能详情

```
HumanDelayConfig
  enabled: boolean           # 主开关
  minDelayMs: number         # 区块之间的最小延迟
  maxDelayMs: number         # 区块之间的最大延迟
  charsPerSecond: number     # 打字速度；延迟 = 字符数 / charsPerSecond
  delayMode: "fixed" | "random" | "typing_speed"

解析：
  resolveHumanDelayConfig    # 每智能体，从默认值深度合并
```

### 7.4 逐功能差距分析

| 功能 | LyClaw | OpenClaw | 差距 | 严重程度 | 复杂度 |
|---------|--------|----------|-----|----------|------------|
| **人类延迟机制** | 无 | 带有 min/max/charsPerSecond 的 `HumanDelayConfig` | 智能体响应感觉即时/机械 | P2 | 低 |
| **启用/禁用开关** | 无 | `enabled: boolean` | 无法选择性启用 | P2 | 低 |
| **最小/最大延迟边界** | 无 | `minDelayMs`、`maxDelayMs` | 无延迟范围控制 | P2 | 低 |
| **打字速度模拟** | 无 | `charsPerSecond` 公式 | 无法模拟人类打字速度 | P2 | 低 |
| **延迟模式** | 无 | fixed / random / typing_speed | 只有一种行为可能；无灵活性 | P3 | 低 |
| **每智能体解析** | 无 | `resolveHumanDelayConfig` | 所有智能体行为相同 | P2 | 低 |

### 7.5 实现路线图

1. **阶段 1（P2）**：创建 `HumanDelayConfig` 领域模型。在 SSE 流管道中实现延迟操作符插入（使用 `Flux.delayElements` 或自定义 `concatMap` 配合 `Mono.delay`）。通过每智能体配置接入。
2. **阶段 2（P3）**：添加基于字符数延迟计算的 typing_speed 模式。添加 random 延迟模式。

---

## 8. TTS（文本转语音）

### 8.1 概述

TTS 配置控制智能体文本响应如何转换为语音，包括每智能体语音选择、深度合并的消息 TTS 配置以及渠道特定的语音投递能力。

### 8.2 LyClaw 当前状态

| 方面 | 状态 | 详情 |
|--------|-------|---------|
| **TTS 配置** | 无 | 没有 `TtsConfig` 类。没有语音选择。完全没有 TTS 集成。 |
| **消息 TTS 合并** | 无 | 没有每消息 TTS 覆盖的深度合并。 |
| **渠道 TTS 能力** | 无 | 没有渠道语音投递能力声明。 |

### 8.3 OpenClaw 功能详情

```
TtsConfig
  enabled: boolean
  voice: string               # 语音标识符（提供商特定）
  speed: number               # 语速倍率
  pitch: number               # 音高调整
  provider: string            # TTS 提供商（elevenlabs、azure 等）

深度合并：智能体 TtsConfig 深度合并到全局 messages.tts 配置之上。
渠道能力：每个渠道声明其支持的 TTS 语音和提供商。
```

### 8.4 逐功能差距分析

| 功能 | LyClaw | OpenClaw | 差距 | 严重程度 | 复杂度 |
|---------|--------|----------|-----|----------|------------|
| **TTS 配置** | 无 | 每智能体 `TtsConfig` | 语音渠道没有语音体验 | P3 | 中 |
| **语音选择** | 无 | `voice: string` | 无每智能体语音身份 | P3 | 低 |
| **语速/音高控制** | 无 | `speed`、`pitch` | 无语音参数微调 | P3 | 低 |
| **提供商选择** | 无 | `provider: string` | 无法为每智能体选择 TTS 提供商 | P3 | 低 |
| **与 messages.tts 深度合并** | 无 | 深度合并语义 | 无法为每消息覆盖 TTS | P3 | 中 |
| **渠道 TTS 能力** | 无 | 每渠道语音投递声明 | 无法声明哪些渠道支持 TTS | P3 | 低 |

### 8.5 实现路线图

TTS 完全属于 P3。实现取决于与外部 TTS 提供商（ElevenLabs、Azure Cognitive Services 等）的集成。只有在所有 P0-P2 功能完成后才应考虑。

1. **阶段 1（P3）**：创建 `TtsConfig` 领域模型。与一个 TTS 提供商集成。实现基本语音选择。
2. **阶段 2（P3）**：添加快慢/音高控制。添加深度合并语义。添加渠道 TTS 能力声明。

---

## 9. 区块流式传输

### 9.1 概述

区块流式传输控制智能体响应文本如何投递给客户端。这包括是按字符还是按区块流式传输、区块如何分隔、快速回复的合并、重复抑制以及投递模式（实时流式传输 vs 仅最终结果）。

### 9.2 LyClaw 当前状态

| 方面 | 状态 | 详情 |
|--------|-------|---------|
| **流式传输** | 基本 SSE | `DefaultReActEngine` 使用 Spring WebFlux 生成 `Flux<ServerSentEvent<String>>`。LLM token 通过 OpenAI 兼容的 SSE 端点流式传输。 |
| **区块切割** | 仅按句子边界 | `splitIntoEvents()` 在 `\n`、`。`、`！`、`？`、`；` 边界处切分文本。仅用于第一轮非流式工具执行结果，不用于第二轮真正的流式传输。 |
| **合并** | 无 | 没有 `blockStreamingCoalesceConfig` — 快速连续回复不会合并。 |
| **投递模式** | 始终实时 | 没有 `"final_only"` 模式。所有内容一旦可用就立即流式传输。 |
| **重复抑制** | 无 | 不删除重复的内容区块。 |
| **隐藏边界分隔符** | 无 | 没有区块之间不可见分隔符的概念。 |
| **流式传输断点** | 无 | 没有 `"text_end"` 与 `"message_end"` 断点语义。 |
| **最大区块字符数** | 无 | 没有每个区块的可配置最大大小。 |

关键代码：`DefaultReActEngine.java` 第 511-529 行（`splitIntoEvents` 方法）。`OpenAiProtocolChatModel.java` 第 264-281 行（通过 WebClient 进行 SSE 流式传输）。`RespondStage.java` 第 161-186 行（`simpleChatStream` 带有真正的逐 token 流式传输）。

### 9.3 OpenClaw 功能详情

```
blockStreamingDefault: "off" | "on"     # 区块流式传输的全局默认值

blockStreamingBreak: "text_end" | "message_end"  # 什么触发区块断点

BlockStreamingChunkConfig                # 软区块切割
  enabled: boolean
  minChars: number                       # 切割前的最小字符数
  maxChars: number                       # 每个区块的最大字符数
  breakOn: string[]                      # 切割依据的字符/模式

BlockStreamingCoalesceConfig             # 区块回复合并
  enabled: boolean
  coalesceIdleMs: number                 # 刷新合并区块前的空闲时间
  maxChunkChars: number                  # 合并输出中的最大字符数
  repeatSuppression: boolean             # 抑制重复区块

deliveryMode: "live" | "final_only"      # 实时流式传输或结束时批量投递
hiddenBoundarySeparator: string          # 区块之间不可见的分隔符（不渲染）
```

### 9.4 逐功能差距分析

| 功能 | LyClaw | OpenClaw | 差距 | 严重程度 | 复杂度 |
|---------|--------|----------|-----|----------|------------|
| **区块流式传输开关** | 始终开启（对非流式结果进行句子切分） | `blockStreamingDefault: "off" \| "on"` | 无显式开关；混合行为（第二轮真正的流式传输，第一轮句子切分） | P1 | 低 |
| **区块断点语义** | 无 | `"text_end" \| "message_end"` | 除了句子字符外没有区块边界概念 | P2 | 低 |
| **软区块切割** | `splitIntoEvents` 固定字符切分 | 带有 min/max 字符数、可配置断点集的 `BlockStreamingChunkConfig` | 硬编码断点字符；无 min/max 控制 | P1 | 低 |
| **回复合并** | 无 | 带有空闲超时的 `BlockStreamingCoalesceConfig` | 快速连续回复作为单独区块到达 | P2 | 中 |
| **重复抑制** | 无 | `repeatSuppression: boolean` | LLM 循环或冗余输出未被过滤 | P2 | 低 |
| **投递模式** | 始终实时 | `"live" \| "final_only"` | 无法批量投递；始终流式传输 | P2 | 低 |
| **隐藏边界分隔符** | 无 | `hiddenBoundarySeparator: string` | 无法注入不可见分隔符供客户端解析 | P3 | 低 |
| **最大区块字符数** | 无（每个区块无限制） | `maxChunkChars: number` | 无限制的区块大小可能导致客户端渲染问题 | P2 | 低 |
| **每渠道/智能体配置** | 仅全局行为 | 每智能体/渠道深度合并覆盖 | 所有智能体流式传输行为相同 | P2 | 低 |

### 9.5 实现路线图

1. **阶段 1（P1）**：创建 `BlockStreamingConfig` 领域模型。重构 `splitIntoEvents` 以使用可配置的断点模式和最小/最大区块大小。添加 `blockStreamingDefault` 开关以启用/禁用句子级别切割。统一第一轮和第二轮流式传输行为。
2. **阶段 2（P2）**：添加带有空闲超时合并的 `BlockStreamingCoalesceConfig`。添加重复抑制。添加 `deliveryMode` 支持（`live` vs `final_only`）。添加 `blockStreamingBreak` 语义。
3. **阶段 3（P3）**：添加 `hiddenBoundarySeparator`。添加每渠道/每智能体深度合并配置。

---

## 10. 输入状态指示器

### 10.1 概述

输入状态指示器向用户提供关于智能体正在做什么的实时反馈：思考、执行工具、编码、压缩上下文等。这与实际响应内容不同——它是一个与消息流并行运行的"状态"通道。

### 10.2 LyClaw 当前状态

| 方面 | 状态 | 详情 |
|--------|-------|---------|
| **输入状态指示器** | 无 | 没有输入状态指示器配置。没有 `typingIntervalSeconds`，没有 `typingMode`。 |
| **进度草稿** | 无 | 没有渠道进度草稿事件（tool/item/plan/approval/command-output/patch）。 |
| **状态反应** | 无 | 没有基于表情符号的状态反应（queued/thinking/tool/coding/compacting/done/error）。 |

框架确实发送了一些 SSE 事件 — `"status"`、`"tool_call"`、`"tool_approval"` — 但这些是内容事件，而不是聊天 UI 意义上的面向用户的输入状态/状态指示器。

### 10.3 OpenClaw 功能详情

```
TypingConfig
  typingIntervalSeconds: number    # 刷新输入状态指示器的频率
  typingMode:                      # 何时显示输入状态指示器
    "never"                        # 从不显示
    "instant"                      # 收到消息后立即显示
    "thinking"                     # 智能体推理时显示
    "message"                      # 撰写响应时显示

渠道进度草稿事件：
  - 工具事件：工具执行开始/进度/完成
  - 条目事件：条目级进度
  - 计划事件：计划创建和步骤进度
  - 批准事件：批准请求已发送
  - 命令输出事件：Shell 命令输出流式传输
  - 补丁事件：代码差异流式传输

状态反应（表情符号）：
  - queued：已收到消息，等待处理
  - thinking：LLM 推理进行中
  - tool：工具执行进行中
  - coding：代码生成/编辑进行中
  - compacting：上下文压缩进行中
  - done：处理完成
  - error：发生错误
```

### 10.4 逐功能差距分析

| 功能 | LyClaw | OpenClaw | 差距 | 严重程度 | 复杂度 |
|---------|--------|----------|-----|----------|------------|
| **输入状态指示器** | 无 | `typingIntervalSeconds` + `typingMode` | 用户在思考/工具阶段没有视觉反馈 | P2 | 中 |
| **输入状态模式控制** | 无 | never/instant/thinking/message | 无法选择性启用/禁用或调整行为 | P2 | 低 |
| **进度草稿：工具** | 发送 `"status"` SSE 事件 | 结构化的工具进度事件 | 现有的"status"事件是通用的；不感知渠道 | P2 | 低 |
| **进度草稿：计划** | 无 | 计划创建和步骤进度事件 | UI 中无计划可视化 | P2 | 中 |
| **进度草稿：批准** | 发送 `"tool_approval"` SSE 事件 | 结构化的批准进度事件 | 仅对工具批准有部分实现 | P2 | 低 |
| **进度草稿：命令输出** | 无 | Shell 命令输出流式传输事件 | 无命令执行进度可视化 | P2 | 中 |
| **进度草稿：补丁** | 无 | 代码差异/补丁流式传输事件 | UI 中无代码变更预览 | P3 | 中 |
| **状态反应（表情符号）** | 无 | queued/thinking/tool/coding/compacting/done/error | 聊天中无视觉状态时间线 | P3 | 低 |
| **反应时机** | 无 | 基于智能体状态添加/移除表情符号 | 无状态-反应映射 | P3 | 低 |

### 10.5 实现路线图

1. **阶段 1（P2）**：创建 `TypingConfig` 领域模型。在 `DefaultReActEngine` 的缓冲/思考阶段实现输入状态指示器发送。添加 `typingIntervalSeconds` 控制。作为"typing"事件连接到 SSE 流。
2. **阶段 2（P2）**：为工具、计划和批准状态正式定义进度草稿事件。用结构化的进度事件替换临时的"status"事件。
3. **阶段 3（P3）**：添加命令输出和补丁进度草稿。添加带有自动状态-反应映射的表情符号状态反应。

---

## 11. 时间格式和时区

### 11.1 概述

时间配置控制如何向用户显示时间戳以及如何处理时区感知操作。这包括用户时区偏好、时间格式（12h vs 24h）、消息信封时间戳和经过时间显示。

### 11.2 LyClaw 当前状态

| 方面 | 状态 | 详情 |
|--------|-------|---------|
| **时间格式** | 无 | 没有面向用户的时间格式化逻辑。所有时间戳使用系统默认值。 |
| **时区** | 无 | 没有 `userTimezone` 配置。所有操作使用服务器时区。 |
| **信封时间戳** | 无 | 消息上没有 `envelopeTimestamp` 或 `envelopeElapsed` 概念。 |

### 11.3 OpenClaw 功能详情

```
TimeConfig
  userTimezone: string         # 可选 IANA 时区（"America/New_York"、"Asia/Shanghai"）
  timeFormat: "auto" | "12" | "24"  # 12 小时制或 24 小时制时间显示
  envelopeTimezone: "utc" | "local" | "user" | IANA  # 消息时间戳的时区
  envelopeTimestamp: "on" | "off"   # 是否在消息信封上显示时间戳
  envelopeElapsed: "on" | "off"     # 是否显示经过时间
```

### 11.4 逐功能差距分析

| 功能 | LyClaw | OpenClaw | 差距 | 严重程度 | 复杂度 |
|---------|--------|----------|-----|----------|------------|
| **用户时区** | 无 | `userTimezone: IANA` | 所有用户看到服务器时间；对全球部署造成困惑 | P2 | 低 |
| **时间格式** | 无 | `timeFormat: "auto" \| "12" \| "24"` | 无适合本地习惯的时间显示 | P3 | 低 |
| **信封时区** | 无 | `envelopeTimezone: "utc" \| "local" \| "user" \| IANA` | 跨时区时间戳模糊不清 | P2 | 低 |
| **信封时间戳开关** | 无 | `envelopeTimestamp: "on" \| "off"` | 无法显示/隐藏消息上的时间戳 | P3 | 低 |
| **信封经过时间开关** | 无 | `envelopeElapsed: "on" \| "off"` | 无法显示相对时间（"2分钟前"） | P3 | 低 |

### 11.5 实现路线图

1. **阶段 1（P2）**：添加带有 `userTimezone` 和 `envelopeTimezone` 的 `TimeConfig`。将时区感知格式化接入消息信封渲染。对所有内部时间戳使用 `java.time.ZonedDateTime`。
2. **阶段 2（P3）**：添加 `timeFormat` 选择。添加 `envelopeTimestamp` 和 `envelopeElapsed` 开关。

---

## 12. 完整功能严重程度矩阵

### 12.1 按优先级

#### P0 — 阻塞（阻碍生产）

| # | 功能 | 组件 | 复杂度 | 依赖项 |
|---|---------|-----------|------------|-------------|
| 1 | AGENTS.md 文件加载 | 启动引导 | 中 | 文件系统访问 |
| 2 | 多文件启动引导（3+ 文件） | 启动引导 | 中 | #1 |
| 3 | 智能体分发/路由 | 路由 | 高 | 渠道抽象 |
| 4 | 渠道抽象 | 路由 | 高 | 无（基础性） |
| 5 | AgentBinding 领域模型 | 路由 | 中 | #4 |
| 6 | 绑定匹配规则 | 路由 | 中 | #5 |
| 7 | 基于模式的匹配 | 路由 | 低 | #6 |
| 8 | 群聊：要求提及 | 群聊 | 中 | #4（渠道） |
| 9 | 群聊：工具限制 | 群聊 | 中 | #8 |

**P0 总计：9 个功能，估计工作量：12-18 周（并行工作）**

#### P1 — 关键优先级（下一个里程碑）

| # | 功能 | 组件 | 复杂度 | 依赖项 |
|---|---------|-----------|------------|-------------|
| 10 | bootstrapMaxChars 控制 | 启动引导 | 低 | #1 |
| 11 | 上下文注入策略 | 启动引导 | 低 | #1 |
| 12 | 紧凑化后段落 | 启动引导 | 中 | #1 + 压缩系统 |
| 13 | 模板变量引擎 | 启动引导 | 低 | #1 |
| 14 | 启动上下文注入 | 启动上下文 | 中 | 记忆系统 |
| 15 | 每日记忆加载 | 启动上下文 | 中 | #14 |
| 16 | 身份：displayName、namePrefix | 身份 | 中 | AgentConfig |
| 17 | 多账户路由 | 路由 | 中 | #4 |
| 18 | 每路由会话 | 路由 | 低 | #5 |
| 19 | 回退/默认路由 | 路由 | 低 | #6 |
| 20 | 群聊：接收策略 | 群聊 | 低 | #8 |
| 21 | 群聊：激活模式 | 群聊 | 低 | #8 |
| 22 | 群聊：发送者访问评估 | 群聊 | 中 | #8 |
| 23 | 心跳：计划调用 | 心跳 | 中 | 调度基础设施 |
| 24 | 心跳：投递目标 | 心跳 | 低 | #23 |
| 25 | 心跳：接收方路由 | 心跳 | 低 | #23 |
| 26 | 区块流式传输：开关 | 区块流式传输 | 低 | SSE 管道 |
| 27 | 区块流式传输：区块配置 | 区块流式传输 | 低 | #26 |

**P1 总计：18 个功能，估计工作量：10-16 周（并行工作）**

#### P2 — 重要优先级（长期路线图）

| # | 功能 | 组件 | 复杂度 |
|---|---------|-----------|------------|
| 28 | 启动引导截断警告 | 启动引导 | 低 |
| 29 | 可选文件跳过控制 | 启动引导 | 低 |
| 30 | 完全跳过启动引导 | 启动引导 | 低 |
| 31 | 文件热重载 | 启动引导 | 中 |
| 32 | 启动上下文：启用/禁用 | 启动上下文 | 低 |
| 33 | 启动上下文：应用触发器 | 启动上下文 | 低 |
| 34 | 启动上下文：大小控制 | 启动上下文 | 低 |
| 35 | ACP 智能体后端 | 路由 | 高 |
| 36 | 动态绑定重载 | 路由 | 中 |
| 37 | 身份：头像（无） | 身份 | 低 |
| 38 | 身份：头像（本地文件） | 身份 | 低 |
| 39 | 身份：头像（远程 URL） | 身份 | 低 |
| 40 | 身份：消息前缀 | 身份 | 低 |
| 41 | 身份：响应前缀 | 身份 | 低 |
| 42 | 群聊：每发送者工具 | 群聊 | 中 |
| 43 | 群聊：访问群组 | 群聊 | 中 |
| 44 | 群聊：白名单展开 | 群聊 | 低 |
| 45 | 心跳：活跃时间 | 心跳 | 低 |
| 46 | 心跳：会话键 | 心跳 | 低 |
| 47 | 心跳：轻量上下文 | 心跳 | 低 |
| 48 | 心跳：隔离会话 | 心跳 | 低 |
| 49 | 心跳：忙碌时跳过 | 心跳 | 低 |
| 50 | 人类延迟：机制 | 人类延迟 | 低 |
| 51 | 人类延迟：每智能体配置 | 人类延迟 | 低 |
| 52 | 区块流式传输：回复合并 | 区块流式传输 | 中 |
| 53 | 区块流式传输：重复抑制 | 区块流式传输 | 低 |
| 54 | 区块流式传输：投递模式 | 区块流式传输 | 低 |
| 55 | 区块流式传输：最大区块字符数 | 区块流式传输 | 低 |
| 56 | 输入状态指示器：核心 | 输入状态指示器 | 中 |
| 57 | 输入状态指示器：模式控制 | 输入状态指示器 | 低 |
| 58 | 输入状态指示器：进度草稿（工具/计划/批准） | 输入状态指示器 | 低 |
| 59 | 进度草稿：命令输出 | 输入状态指示器 | 中 |
| 60 | 时间：用户时区 | 时间/时区 | 低 |
| 61 | 时间：信封时区 | 时间/时区 | 低 |

**P2 总计：34 个功能，估计工作量：8-14 周（许多为低复杂度）**

#### P3 — 低优先级（锦上添花）

| # | 功能 | 组件 | 复杂度 |
|---|---------|-----------|------------|
| 62 | 基于角色的路由（Discord） | 路由 | 中 |
| 63 | 身份：头像（数据 URI） | 身份 | 低 |
| 64 | 身份：确认反应 | 身份 | 低 |
| 65 | 身份：消息配置合并 | 身份 | 低 |
| 66 | 心跳：模型覆盖 | 心跳 | 低 |
| 67 | 心跳：私聊策略 | 心跳 | 低 |
| 68 | 心跳：包含系统提示 | 心跳 | 低 |
| 69 | 心跳：确认最大字符数 | 心跳 | 低 |
| 70 | 心跳：超时 | 心跳 | 低 |
| 71 | 心跳：包含推理 | 心跳 | 低 |
| 72 | 人类延迟：延迟模式 | 人类延迟 | 低 |
| 73 | TTS：配置 | TTS | 中 |
| 74 | TTS：语音选择 | TTS | 低 |
| 75 | TTS：语速/音高 | TTS | 低 |
| 76 | TTS：提供商选择 | TTS | 低 |
| 77 | TTS：与 messages 深度合并 | TTS | 中 |
| 78 | TTS：渠道能力 | TTS | 低 |
| 79 | 区块流式传输：隐藏分隔符 | 区块流式传输 | 低 |
| 80 | 区块流式传输：每渠道配置 | 区块流式传输 | 低 |
| 81 | 输入状态指示器：进度草稿（补丁） | 输入状态指示器 | 中 |
| 82 | 输入状态指示器：状态反应 | 输入状态指示器 | 低 |
| 83 | 时间：时间格式（12/24） | 时间/时区 | 低 |
| 84 | 时间：信封时间戳开关 | 时间/时区 | 低 |
| 85 | 时间：信封经过时间开关 | 时间/时区 | 低 |

**P3 总计：24 个功能，估计工作量：6-10 周（主要是外观优化/实验性功能）**

---

## 13. 依赖关系图

以下图表展示了各组件之间的关键实现依赖关系：

```
渠道抽象 (P0) ────────────────────────────────────────────────────────┐
  ├── 智能体路由与绑定 (P0)                                            │
  │     ├── 多账户路由 (P1)                                            │
  │     ├── 每路由会话 (P1)                                            │
  │     └── ACP 后端 (P2)                                              │
  ├── 群聊 (P0)                                                        │
  │     ├── 接收策略 (P1)                                              │
  │     ├── 激活模式 (P1)                                              │
  │     ├── 发送者访问评估 (P1)                                        │
  │     ├── 每发送者工具 (P2)                                          │
  │     └── 访问群组 (P2)                                              │
  └── 身份 (P1)                                                        │
        ├── 头像 (P2)                                                  │
        ├── 消息/响应前缀 (P2)                                         │
        └── 确认反应 (P3)                                              │
                                                                       │
启动引导系统 (P0)                                                      │
  ├── 多文件加载 (P0)                                                  │
  ├── maxChars 控制 (P1)                                               │
  ├── 上下文注入策略 (P1)                                              │
  ├── 模板变量 (P1)                                                    │
  ├── 紧凑化后段落 (P1) ─── 依赖于压缩系统                             │
  ├── 可选文件 (P2)                                                    │
  ├── 文件热重载 (P2)                                                  │
  └── HEARTBEAT.md 用于心跳 (P2) ─── 依赖于心跳                        │
                                                                       │
心跳系统 (P1)                                                          │
  ├── 调度器 (P1)                                                      │
  ├── 投递目标 (P1) ─── 依赖于渠道抽象                                 │
  ├── 轻量上下文 (P2) ─── 依赖于启动引导                               │
  ├── 隔离会话 (P2)                                                    │
  └── 忙碌时跳过 (P2)                                                  │
                                                                       │
区块流式传输 (P1)                                                      │
  ├── 开关 + 区块配置 (P1)                                             │
  ├── 合并 (P2)                                                        │
  ├── 投递模式 (P2)                                                    │
  └── 隐藏分隔符 (P3)                                                  │
                                                                       │
人类延迟 (P2) ─── 依赖于区块流式传输管道                               │
输入状态指示器 (P2) ─── 依赖于 SSE 管道                                │
TTS (P3) ─── 依赖于渠道 TTS 能力                                       │
时间/时区 (P2) ─── 独立，隔离的工具类                                  │
```

---

## 14. 实现顺序建议

### 里程碑 1：基础（第 1-4 周）
- **启动引导**：AGENTS.md + BOOTSTRAP.md 文件加载，与 `@SystemMessage` 集成
- **渠道抽象**：核心渠道接口和 HTTP 渠道适配器
- 开始：**智能体路由**：AgentBinding 领域模型、模式匹配

### 里程碑 2：多智能体核心（第 5-8 周）
- 完成：**智能体路由**：路由分发、默认路由、模式匹配
- **身份**：displayName、namePrefix、基本头像（none/remote）
- **群聊**：requireMention、GroupToolPolicyConfig、工具过滤
- **启动引导**：maxChars 控制、上下文注入策略、模板变量

### 里程碑 3：主动智能体（第 9-12 周）
- **心跳**：核心调度器、投递目标、接收方路由
- **区块流式传输**：可配置的区块配置、区块流式传输开关
- **启动上下文**：注入、每日记忆加载
- **群聊**：接收策略、激活模式、发送者访问评估

### 里程碑 4：打磨与高级功能（第 13-18 周）
- **区块流式传输**：合并、重复抑制、投递模式
- **输入状态指示器**：核心指示器、进度草稿
- **人类延迟**：带有配置的延迟机制
- **启动引导**：紧凑化后段落、可选文件、热重载
- **时间/时区**：用户时区、信封时区

### 里程碑 5：实验性功能（第 19 周以上）
- **TTS**：完整的 TTS 集成
- **ACP**：外部智能体后端代理
- **基于角色的路由**：Discord 角色路由
- 剩余的 P3 功能

---

## 15. 关键设计决策

### 15.1 渠道抽象

渠道抽象是最关键的架构决策。它是路由、群聊、身份、心跳投递和输入状态指示器的基础。它必须支持：
- **多平台**：HTTP（当前）、Telegram、Discord、WhatsApp、Slack、自定义 webhook
- **平台特定元数据**：Discord 公会/角色、Telegram 聊天类型、WhatsApp E.164
- **统一消息模型**：所有渠道归一化为通用的 `InboundMessage` / `OutboundMessage`
- **适配器模式**：每个平台获得一个实现通用 `ChannelAdapter` 接口的适配器

### 15.2 智能体路由 vs Spring Bean 注入

当前，`ChatController` 直接注入 `ChatAgent` 作为 Spring Bean。路由系统必须与之共存：
- **选项 A**：用返回适当智能体代理的 `AgentRouter.resolve(request)` 替换直接注入
- **选项 B**：为简单部署保留直接注入；路由器作为可选的中间件
- **建议**：选项 B — 保持向后兼容性，同时将路由器作为可选层添加

### 15.3 启动引导文件位置

启动引导文件需要一个明确定义的位置策略：
- **每智能体目录**：`workspace/{agentId}/AGENTS.md`
- **全局回退**：`workspace/default/AGENTS.md`
- **类路径回退**：`classpath:/agents/{agentId}/AGENTS.md`
- **建议**：文件系统优先，类路径回退；可配置的基础路径

### 15.4 SSE 事件分类

LyClaw 目前使用临时的 SSE 事件类型（`"message"`、`"status"`、`"tool_call"`、`"tool_approval"`、`"respond_start"`、`"done"`）。这些需要规范化：

| 当前事件 | 建议标准 | 组件 |
|---------------|-------------------|-----------|
| `"message"` | `"text"`（内容）、`"block"`（完整区块） | 区块流式传输 |
| `"status"` | `"typing"`、`"thinking"`、`"progress"` | 输入状态指示器 |
| `"tool_call"` | `"tool.start"`、`"tool.progress"`、`"tool.done"` | 输入状态指示器 |
| `"tool_approval"` | `"approval.request"` | 输入状态指示器 |
| `"respond_start"` | `"agent.start"`、`"agent.identity"` | 身份 |
| `"done"` | `"agent.done"`、`"stream.end"` | 区块流式传输 |
| *（新增）* | `"heartbeat"` | 心跳 |
| *（新增）* | `"error"`、`"compact"` | 输入状态指示器 |

---

## 16. 指标和可观测性差距

在添加这些功能时，必须考虑可观测性：

| 功能 | 需要的指标 |
|---------|---------------|
| 启动引导 | 加载时间、文件大小、截断事件、解析错误 |
| 路由 | 路由匹配延迟、每路由匹配计数、未命中率、分发错误 |
| 身份 | 解析时间、头像获取延迟 |
| 群聊 | 提及检测率、工具拒绝计数、发送者授权失败 |
| 心跳 | 调用次数、成功率、跳过率（忙碌）、延迟、超时率 |
| 人类延迟 | 实际应用的延迟、用户感知的响应时间 |
| TTS | 合成延迟、字符数、提供商错误 |
| 区块流式传输 | 区块数、平均区块大小、合并计数、重复抑制计数 |
| 输入状态指示器 | 指示器发送次数、状态转换延迟 |

---

## 17. 汇总统计

| 分类 | P0 | P1 | P2 | P3 | 总计 |
|----------|----|----|----|----|-------|
| 启动引导 | 3 | 3 | 3 | 0 | 9 |
| 启动上下文 | 0 | 2 | 3 | 0 | 5 |
| 智能体路由 | 5 | 3 | 2 | 1 | 11 |
| 身份 | 0 | 1 | 3 | 3 | 7 |
| 群聊 | 2 | 3 | 3 | 0 | 8 |
| 心跳 | 0 | 3 | 6 | 7 | 16 |
| 人类延迟 | 0 | 0 | 2 | 1 | 3 |
| TTS | 0 | 0 | 0 | 6 | 6 |
| 区块流式传输 | 0 | 2 | 4 | 2 | 8 |
| 输入状态指示器 | 0 | 0 | 3 | 3 | 6 |
| 时间/时区 | 0 | 0 | 2 | 3 | 5 |
| **总计** | **10** | **17** | **31** | **26** | **84** |

### 工作量估算汇总

| 优先级 | 功能数量 | 估算工作量（周） | 估算工作量（并行） |
|----------|--------------|---------------------|------------------------|
| P0 | 10 | 18-26 | 12-18 |
| P1 | 17 | 14-22 | 10-16 |
| P2 | 31 | 8-14 | 6-10 |
| P3 | 26 | 6-10 | 4-8 |
| **总计** | **84** | **46-72** | **32-52** |

---

*文档生成日期：2026-05-20。涵盖 LyClaw 代码库在提交 bc2cb96（"修复沙箱问题和前端批准"）时的状态 vs OpenClaw 参考架构。*
