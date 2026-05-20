# OpenClaw Agent 系统全方位深度解析

> 基于 2026.5.18 版本 dist 代码 + TypeScript 定义文件 + 12个Agent并行深度调研的综合报告
> 总计约18000+行，覆盖6大主题，含真实源码片段

---

## 目录

1. [Agent定义详细解析（上）：AgentConfig完整字段](#一agent定义详细解析上agentconfig完整字段)
2. [Agent定义详细解析（下）：全局默认值、模型体系与路由绑定](#一agent定义详细解析下全局默认值模型体系与路由绑定)
3. [Agent生命周期（上）：完整生命周期与状态机](#二agent生命周期上完整生命周期与状态机)
4. [Agent生命周期（下）：错误处理、Fallback与会话恢复](#二agent生命周期下错误处理fallback与会话恢复)
5. [多Agent通信与调度（上）：sessions_spawn完整流程与角色体系](#三多agent通信与调度上sessions_spawn完整流程与角色体系)
6. [多Agent通信与调度（下）：Push Announce、sessions_yield与子Agent管理](#三多agent通信与调度下push-announcesessions_yield与子agent管理)
7. [Skill系统实现（上）：来源架构、目录扫描与安装方式](#四skill系统实现上来源架构目录扫描与安装方式)
8. [Skill系统实现（下）：XML注入、暴露控制、安全扫描与ClawHub](#四skill系统实现下xml注入暴露控制安全扫描与clawhub)
9. [Agent工具系统实现（上）：工具分类、工厂模式与Schema规范化](#五agent工具系统实现上工具分类工厂模式与schema规范化)
10. [Agent工具系统实现（下）：策略管道、循环检测与安全机制](#五agent工具系统实现下策略管道循环检测与安全机制)
11. [Channel消息系统（上）：架构总览与ChannelPlugin接口](#六channel消息系统上架构总览与channelplugin接口)
12. [Channel消息系统（下）：8阶段流水线、55+动作与回复管线](#六channel消息系统下8阶段流水线55动作与回复管线)

---

# 一、Agent定义详细解析（上）：AgentConfig完整字段

## 1.1 概述

`AgentConfig` 是 OpenClaw 中定义单个智能体（Agent）行为的核心类型。它位于 `types.agents.d.ts`，是整个智能体配置体系中最关键的数据结构。每一个 OpenClaw 智能体的行为特征——从模型选择、工具权限到沙箱隔离、心跳巡检——都通过这个类型中的字段来描述。

### 顶层结构

```typescript
export type AgentsConfig = {
    defaults?: AgentDefaultsConfig;
    list?: AgentConfig[];
};
```

`AgentsConfig` 是用户配置的入口点。它包含两个部分：

- **`defaults`**：全局默认值（`AgentDefaultsConfig`），为所有智能体提供回退配置。
- **`list`**：智能体定义数组（`AgentConfig[]`），每个元素定义一个具体智能体。

### 级联继承（Cascading Inheritance）

OpenClaw 的配置采用**级联继承**模式。当 `AgentConfig.list[].field` 存在时，直接使用该值；当它不存在（`undefined`）时，从 `AgentDefaultsConfig.field` 继承对应的默认值。如果两者都未设置，OpenClaw 使用内置的硬编码默认值。

```
AgentConfig.list[i].字段  ──优先──▶  覆盖 AgentDefaultsConfig.字段
AgentDefaultsConfig.字段  ──次之──▶  回退默认值
内置硬编码默认值          ──最后──▶  保底值
```

这种设计允许用户在保持全局统一默认行为的同时，对特定智能体进行精细化的差异配置。

### 字段总览

`AgentConfig` 共包含 **37 个字段**，以下按功能分组逐一详解。其中仅有 `id` 为必填字段，其余全部可选。

---

## 1.2 标识与基础信息

这组字段定义了智能体的身份、外观和基础工作路径。

### 1.2.1 `id` (必填)

```typescript
id: string;
```

**类型**：`string`
**是否必填**：是
**默认值**：无（必须显式提供）

智能体的唯一标识符。这是整个 Agent 配置中唯一强制要求的字段。`id` 用于在多智能体环境中路由消息、分配会话以及跨智能体引用。

**最佳实践**：使用小写字母、数字和连字符组成的短标识符，如 `"code-reviewer"`、`"chat-assistant"`、`"data-analyst"`。避免使用空格和特殊字符。

---

### 1.2.2 `default` (可选)

```typescript
default?: boolean;
```

**类型**：`boolean`
**是否必填**：否
**默认值**：`false`

标记此智能体是否为**默认智能体**。当消息路由无法匹配到特定智能体，或者用户未显式指定目标智能体时，系统将使用标记为 `default: true` 的智能体处理请求。

**约束**：在 `AgentsConfig.list` 中，最多只应有一个智能体设置 `default: true`。如果有多个，行为取决于 OpenClaw 内部的解析顺序（通常取第一个匹配项）。

**典型用法**：将一个通用对话智能体设为主默认智能体，同时保留若干专用智能体（如代码审查、数据分析）按需调用。

---

### 1.2.3 `name` (可选)

```typescript
name?: string;
```

**类型**：`string`
**是否必填**：否
**默认值**：使用 `id` 作为显示名称

智能体的**显示名称**。当配置了此字段时，系统会在 UI 和日志中使用 `name` 而非 `id` 来呈现智能体名称。可用于提供人类友好的名称，例如 `id` 为 `"code-reviewer-v2"`，`name` 可设为 `"Code Reviewer"`。

---

### 1.2.4 `description` (可选)

```typescript
/** Optional human-authored agent description. */
description?: string;
```

**类型**：`string`
**是否必填**：否
**默认值**：无

智能体的**人类可读描述**。用于文档说明或管理界面展示，向用户解释此智能体的用途和能力范围。此字段不影响智能体的实际行为，纯粹用于认知层面的元信息。

---

### 1.2.5 `workspace` (可选)

```typescript
workspace?: string;
```

**类型**：`string`
**是否必填**：否
**默认值**：继承自 `AgentDefaultsConfig.workspace`

智能体的**工作目录**。智能体运行时的当前工作目录（CWD）将设置为此路径。该路径决定了文件系统工具（`read`、`write`、`edit`）的可见范围起点，也是 `BOOTSTRAP.md`、`AGENTS.md` 等引导文件的搜索根目录。

**注意**：`workspace` 优先于 `agentDir`。如果两者都设置，以 `workspace` 为准。

---

### 1.2.6 `agentDir` (可选)

```typescript
agentDir?: string;
```

**类型**：`string`
**是否必填**：否
**默认值**：无

智能体的**专用配置目录**。OpenClaw 会从此目录中加载智能体专用的配置片段、引导文件和插件资源。当 `workspace` 未设置时，此路径作为选择工作目录的备选。

---

### 1.2.7 `identity` (可选)

```typescript
identity?: IdentityConfig;
```

**类型**：`IdentityConfig`
**是否必填**：否
**默认值**：无智能体专属身份（使用全局身份配置）

智能体的**身份配置**，用于覆盖该智能体的对外身份信息——包括显示名称、头像等。此字段允许不同智能体以不同的"人格"面向用户，即使它们共用同一个底层模型。

---

## 1.3 系统提示词与启动上下文

这组字段控制智能体的系统提示词、启动时注入的引导内容以及上下文注入策略。

### 1.3.1 `systemPromptOverride` (可选)

```typescript
/** Optional per-agent full system prompt replacement. */
systemPromptOverride?: AgentDefaultsConfig["systemPromptOverride"];
```

**类型**：`string`
**是否必填**：否
**默认值**：继承自 `AgentDefaultsConfig.systemPromptOverride`

**完全替换**智能体的系统提示词。这是一个非常有侵入性的选项——设置后，OpenClaw 内置的系统提示词（包括工具说明、安全规则、行为准则等）将被**整个替换**为该字符串。

**使用场景**：主要用于提示词调试和受控实验。在生产环境中应谨慎使用，因为移除内置系统提示词可能导致智能体失去关键的安全约束和工具使用指导。

---

### 1.3.2 `contextInjection` (可选)

```typescript
/** Optional per-agent bootstrap/context injection mode override. */
contextInjection?: AgentDefaultsConfig["contextInjection"];
```

对应的联合类型定义：

```typescript
export type AgentContextInjection = "always" | "continuation-skip" | "never";
```

**类型**：`"always"` | `"continuation-skip"` | `"never"`
**是否必填**：否
**默认值**：继承自 `AgentDefaultsConfig.contextInjection`（最终硬编码默认为 `"always"`）

控制工作空间引导文件（`AGENTS.md`、`SOUL.md` 等）何时注入到系统提示词中：

| 值 | 行为 |
|---|---|
| `"always"` | 每轮对话都注入（默认） |
| `"continuation-skip"` | 一旦对话转录中已有一个完整的 assistant 轮次，就跳过后续安全继续轮次的注入 |
| `"never"` | 完全不注入引导上下文 |

`"continuation-skip"` 是一种优化策略：在长对话中，一旦智能体已经"理解了"引导上下文，后续的继续轮次就无需重复注入，从而节省上下文窗口空间。

---

### 1.3.3 `bootstrapMaxChars` (可选)

```typescript
/** Optional per-agent max chars for each injected bootstrap file. */
bootstrapMaxChars?: AgentDefaultsConfig["bootstrapMaxChars"];
```

**类型**：`number`
**是否必填**：否
**默认值**：继承自 `AgentDefaultsConfig.bootstrapMaxChars`（硬编码默认为 `20000`）

**单个引导文件的最大字符数**。当引导文件（如 `AGENTS.md`）超过此限制时，内容会被截断。此限制独立作用于每个引导文件。

---

### 1.3.4 `bootstrapTotalMaxChars` (可选)

```typescript
/** Optional per-agent max total chars across injected bootstrap files. */
bootstrapTotalMaxChars?: AgentDefaultsConfig["bootstrapTotalMaxChars"];
```

**类型**：`number`
**是否必填**：否
**默认值**：继承自 `AgentDefaultsConfig.bootstrapTotalMaxChars`（硬编码默认为 `150000`）

**所有引导文件的总字符数上限**。当所有引导文件的总字符数超过此限制时，部分文件内容将被截断（按顺序优先保留前面的文件）。与 `bootstrapMaxChars` 配合使用，形成双层限制：先按单文件限制，再按总字符数限制。

---

### 1.3.5 `skills` (可选)

```typescript
/** Optional allowlist of skills for this agent; omitting it inherits agents.defaults.skills
 *  when set, and an explicit list replaces defaults instead of merging. */
skills?: string[];
```

**类型**：`string[]`
**是否必填**：否
**默认值**：继承自 `AgentDefaultsConfig.skills`，如果默认值也未设置则无限制

智能体的**技能白名单**。与一般配置的合并策略不同，**此字段使用替换策略而非合并策略**：一旦在 `AgentConfig` 层面显式设置了 `skills` 数组，它**完全替换**默认值，而非追加。这意味着如果你想在默认技能的基础上增加一个技能，必须显式列出所有想要的技能（包括默认的那些）。

---

### 1.3.6 `skillsLimits` (可选)

```typescript
/** Optional per-agent skills subsystem overrides. */
skillsLimits?: Pick<SkillsLimitsConfig, "maxSkillsPromptChars">;
```

**类型**：`{ maxSkillsPromptChars?: number }`
**是否必填**：否
**默认值**：无限制（除非全局配置另有设置）

**技能子系统的字符限制**。当前仅支持一个字段：
- `maxSkillsPromptChars`：技能提示词注入的最大字符数，防止过长的技能说明占用过多上下文窗口。

---

### 1.3.7 `memorySearch` (可选)

```typescript
memorySearch?: MemorySearchConfig;
```

**类型**：`MemorySearchConfig`（详见 `types.tools.d.ts`）
**是否必填**：否
**默认值**：继承自 `AgentDefaultsConfig.memorySearch`

**向量记忆搜索配置**。控制智能体是否以及如何访问持久化的长期记忆。关键子字段包括：
- `enabled?: boolean`：启用向量记忆搜索（默认 `true`）
- `sources?: Array<"memory" | "sessions">`：索引和搜索的源（默认 `["memory"]`）
- `extraPaths?: string[]`：额外的搜索路径（目录或 `.md` 文件）
- `provider?: string`：嵌入提供者适配器 ID
- `model?: string`：嵌入模型 ID 或本地模型别名
- `local?: { modelPath, modelCacheDir, contextSize }`：本地嵌入设置（node-llama-cpp）
- `query?: { maxResults, minScore, hybrid }`：查询行为配置
- `chunking?: { tokens, overlap }`：文档分块参数
- `sync?: { onSessionStart, onSearch, watch, intervalMinutes }`：同步策略

此字段支持在智能体级别对记忆功能进行精细化覆盖。

---

## 1.4 模型配置

这组字段定义了智能体使用的大语言模型。

### 1.4.1 `model` (可选)

```typescript
model?: AgentModelConfig;
```

#### AgentModelConfig：字符串简写与对象形式

`AgentModelConfig` 是一个联合类型，是 OpenClaw 中最强大也最灵活的配置之一。

```typescript
export type AgentModelConfig = string | {
    /** Primary model (provider/model). */
    primary?: string;
    /** Per-agent model fallbacks (provider/model). */
    fallbacks?: string[];
};
```

**两种形式**：

1. **字符串简写**（`string`）：
   ```yaml
   model: "openai/gpt-5.4"
   ```
   直接指定主模型。格式为 `"provider/model"`。这是最常见、最简洁的写法。当只需要单一模型且不需要回退时使用。

2. **对象形式**（`{ primary?, fallbacks? }`）：
   ```yaml
   model:
     primary: "openai/gpt-5.4"
     fallbacks:
       - "anthropic/claude-sonnet-4-6"
       - "google/gemini-2.5-pro"
   ```
   对象形式允许指定主模型和一个有序的回退模型列表。当主模型不可用（例如 API 故障、配额耗尽）时，OpenClaw 按 `fallbacks` 数组的顺序依次尝试。

**类型**：`string | { primary?: string; fallbacks?: string[] }`
**是否必填**：否
**默认值**：继承自 `AgentDefaultsConfig.model`

**回退逻辑**：
- 先尝试 `primary`（如果对象形式），或直接使用字符串值（如果是简写）
- 对对象形式，如果没有 `primary` 但有 `fallbacks`，从 `fallbacks[0]` 开始
- 按顺序遍历 `fallbacks`，直到找到第一个可用的模型
- 如果所有模型都不可用，OpenClaw 报告错误

---

### 1.4.2 `models` (可选)

```typescript
/** Per-model metadata overrides for this agent. */
models?: Record<string, AgentModelEntryConfig>;
```

#### AgentModelEntryConfig：模型条目的详细配置

```typescript
export type AgentModelEntryConfig = {
    alias?: string;
    /** Provider-specific API parameters (e.g., GLM-4.7 thinking mode). */
    params?: Record<string, unknown>;
    /** Optional agent execution runtime for this specific provider/model entry. */
    agentRuntime?: AgentRuntimePolicyConfig;
    /** Enable streaming for this model (default: true, false for Ollama to avoid SDK issue #1205). */
    streaming?: boolean;
};
```

**类型**：`Record<string, AgentModelEntryConfig>`
**是否必填**：否
**默认值**：继承自 `AgentDefaultsConfig.models`

此字段是一个 **键值映射**，键为完整的 `"provider/model"` 字符串，值为该模型条目的元数据覆盖。

**各子字段详解**：

| 子字段 | 类型 | 默认值 | 说明 |
|---|---|---|---|
| `alias` | `string` | 无 | 模型的本地别名，用户可通过别名引用模型 |
| `params` | `Record<string, unknown>` | 无 | 传递给模型提供者的**特定 API 参数**。例如 GLM-4.7 的思维模式参数、temperature 覆盖等。这是透传参数，具体支持取决于提供者插件的实现 |
| `agentRuntime` | `AgentRuntimePolicyConfig` | 无 | 此特定模型/提供者的**运行时策略覆盖**。允许不同的模型后端使用不同的运行时（例如某些模型走 ACP 协议，另一些走嵌入式 Pi） |
| `streaming` | `boolean` | `true`（Ollama 默认为 `false`） | 是否为该模型启用流式输出。Ollama 默认为 `false` 以规避 SDK issue #1205 |

**使用示例**：

```yaml
models:
  "openai/gpt-5.4":
    alias: "gpt5"
    streaming: true
    params:
      temperature: 0.7
  "ollama/llama3.1":
    streaming: false
    params:
      num_ctx: 8192
  "anthropic/claude-opus-4-6":
    agentRuntime:
      id: "acp"
```

---

## 1.5 思维/推理/详细模式

这组字段控制智能体的"认知行为开关"——思考深度、详细程度和推理可见性。

### 1.5.1 `thinkingDefault` (可选)

```typescript
/** Optional per-agent default thinking level (overrides agents.defaults.thinkingDefault). */
thinkingDefault?: "off" | "minimal" | "low" | "medium" | "high" | "xhigh" | "adaptive" | "max";
```

**类型**：`"off" | "minimal" | "low" | "medium" | "high" | "xhigh" | "adaptive" | "max"`
**是否必填**：否
**默认值**：继承自 `AgentDefaultsConfig.thinkingDefault`

智能体的**默认思考级别**。当用户未通过 `/think` 指令显式指定时，使用此默认值。

| 级别 | 含义 |
|---|---|
| `"off"` | 完全禁用思维链，智能体直接输出回复 |
| `"minimal"` | 极简思考，尽可能少的推理 |
| `"low"` | 少量推理 |
| `"medium"` | 中等推理（平衡模式） |
| `"high"` | 深度推理 |
| `"xhigh"` | 超深推理 |
| `"adaptive"` | 自适应：模型根据任务复杂度动态调整思考深度 |
| `"max"` | 最大推理强度（可能显著增加延迟和 token 消耗） |

此设置映射到不同模型提供者的具体 API 参数（如 OpenAI 的 `reasoning_effort`、Anthropic 的 `thinking.budget_tokens`）。

---

### 1.5.2 `verboseDefault` (可选)

```typescript
/** Optional per-agent default verbosity level. */
verboseDefault?: "off" | "on" | "full";
```

**类型**：`"off" | "on" | "full"`
**是否必填**：否
**默认值**：继承自 `AgentDefaultsConfig.verboseDefault`

智能体的**默认详细程度级别**。

| 级别 | 含义 |
|---|---|
| `"off"` | 简洁模式：只输出必要信息 |
| `"on"` | 标准详细：输出适中的中间步骤信息 |
| `"full"` | 完整详细：输出所有中间步骤、工具调用详情和内部状态 |

用户可通过 `/verbose` 指令动态覆盖此设置。

---

### 1.5.3 `toolProgressDetail` (可选)

```typescript
/** Optional per-agent tool progress detail mode. */
toolProgressDetail?: AgentDefaultsConfig["toolProgressDetail"];
```

对应的类型：

```typescript
toolProgressDetail?: "explain" | "raw";
```

**类型**：`"explain" | "raw"`
**是否必填**：否
**默认值**：继承自 `AgentDefaultsConfig.toolProgressDetail`（硬编码默认值为 `"explain"`）

控制 `/verbose` 和可编辑进度草稿中用户可见的工具进度详细程度：

| 模式 | 含义 |
|---|---|
| `"explain"` | 紧凑的人类可读摘要（默认） |
| `"raw"` | 包含原始命令/详情（如果可用），适合调试 |

---

### 1.5.4 `reasoningDefault` (可选)

```typescript
/** Optional per-agent default reasoning visibility. */
reasoningDefault?: "on" | "off" | "stream";
```

**类型**：`"on" | "off" | "stream"`
**是否必填**：否
**默认值**：继承自 `AgentDefaultsConfig.reasoningDefault`

智能体的**默认推理可见性**。决定模型的内部推理（思维链）是否对用户可见。

| 模式 | 含义 |
|---|---|
| `"off"` | 推理不可见，用户只看到最终回复 |
| `"on"` | 推理可见，作为独立消息块展示 |
| `"stream"` | 推理以流式方式逐步展示 |

用户可通过 `/reasoning` 指令动态覆盖此设置。

---

### 1.5.5 `fastModeDefault` (可选)

```typescript
/** Optional per-agent default for fast mode. */
fastModeDefault?: boolean;
```

**类型**：`boolean`
**是否必填**：否
**默认值**：继承自 `AgentDefaultsConfig` 或硬编码默认值

启用**快速模式**，优先追求响应速度而非质量。此模式可能会跳过某些耗时的预处理步骤、减少上下文注入量或使用更激进的缓存策略。

---

## 1.6 运行时配置

这组字段控制智能体的**执行载体**——即哪个运行时环境负责驱动智能体的推理循环。

### 1.6.1 `agentRuntime` (可选)

```typescript
/** Optional per-agent agent runtime policy override. */
agentRuntime?: AgentRuntimePolicyConfig;
```

#### AgentRuntimePolicyConfig

```typescript
export type AgentRuntimePolicyConfig = {
    /** Agent runtime id. Omitted uses "pi"; "auto" opts into plugin harness auto-selection. */
    id?: string;
};
```

**类型**：`{ id?: string }`
**是否必填**：否
**默认值**：继承自 `AgentDefaultsConfig.agentRuntime`

指定智能体的**运行时策略**。核心字段：

| `id` 值 | 含义 |
|---|---|
| 未设置 | 使用默认运行时 `"pi"`（OpenClaw 内置的嵌入式 Pi 引擎） |
| `"pi"` | 显式使用嵌入式 Pi 运行时（与默认行为相同，显式声明提高可读性） |
| `"auto"` | 启用**插件运行时自动选择**。OpenClaw 会根据模型类型和配置的插件自动选择最合适的运行时（如 ACP 协议适配器） |

**`"auto"` 的使用场景**：当你的配置中可能有多个运行时插件注册时，`"auto"` 让系统自动协商选择最合适的那个，避免手动指定。

---

### 1.6.2 `runtime` (可选)

```typescript
/** Optional runtime descriptor for this agent. */
runtime?: AgentRuntimeConfig;
```

#### AgentRuntimeConfig：embedded vs acp

```typescript
export type AgentRuntimeConfig = {
    type: "embedded";
} | {
    type: "acp";
    acp?: AgentRuntimeAcpConfig;
};
```

`AgentRuntimeConfig` 是一个**标记联合类型**（discriminated union），通过 `type` 字段区分为两种运行模式。这是 `agentRuntime` 字段的更精细版本，提供了完整的运行时配置而非仅指定 ID。

**类型**：`{ type: "embedded" } | { type: "acp"; acp?: AgentRuntimeAcpConfig }`
**是否必填**：否
**默认值**：默认使用嵌入式运行时

#### 模式一：`embedded`—嵌入式运行时

```typescript
{
    type: "embedded";
}
```

使用 OpenClaw 内置的嵌入式 Pi 引擎。这是默认模式，运行时直接在 OpenClaw 进程内执行，不依赖外部进程。代码最少、延迟最低，适用于绝大多数场景。

**配置示例**：

```yaml
runtime:
  type: "embedded"
```

#### 模式二：`acp`—ACP 协议运行时

```typescript
{
    type: "acp";
    acp?: AgentRuntimeAcpConfig;
}

export type AgentRuntimeAcpConfig = {
    /** ACP harness adapter id (for example codex, claude). */
    agent?: string;
    /** Optional ACP backend override for this agent runtime. */
    backend?: string;
    /** Optional ACP session mode override. */
    mode?: "persistent" | "oneshot";
    /** Optional runtime working directory override. */
    cwd?: string;
};
```

通过 **ACP（Agent Communication Protocol）** 协议委托给外部智能体运行时。这允许 OpenClaw 作为编排层，将实际执行委托给外部引擎（如 Claude Code CLI、Codex CLI 等）。

| 子字段 | 类型 | 说明 |
|---|---|---|
| `agent` | `string` | ACP 适配器 ID。例如 `"codex"` 使用 Codex CLI，`"claude"` 使用 Claude Code |
| `backend` | `string` | 可选的 ACP 后端覆盖，用于选择不同的后端实现 |
| `mode` | `"persistent" \| "oneshot"` | 会话模式：`"persistent"` 保持长连接，`"oneshot"` 每次请求新建会话 |
| `cwd` | `string` | 运行时工作目录覆盖 |

**配置示例**：

```yaml
runtime:
  type: "acp"
  acp:
    agent: "codex"
    mode: "persistent"
    cwd: "/home/user/project"
```

---

### 1.6.3 `embeddedHarness` (已废弃)

```typescript
/** @deprecated Use agentRuntime. */
embeddedHarness?: AgentEmbeddedHarnessConfig;
```

```typescript
export type AgentEmbeddedHarnessConfig = {
    /** Agent runtime id. Omitted uses "pi"; "auto" opts into plugin harness auto-selection. */
    runtime?: string;
};
```

**类型**：`{ runtime?: string }`
**状态**：**已废弃**
**迁移路径**：使用 `agentRuntime` 替代

此字段在语义上与 `agentRuntime` 相同（都通过 `runtime`/`id` 字段指定运行时），但已被标记为 `@deprecated`。OpenClaw 保留此字段仅用于向后兼容，新配置应统一使用 `agentRuntime`。

---

### 1.6.4 `embeddedPi` (可选)

```typescript
/** Optional per-agent embedded Pi overrides. */
embeddedPi?: {
    /** Optional per-agent execution contract override. */
    executionContract?: EmbeddedPiExecutionContract;
};
```

对应的类型：

```typescript
export type EmbeddedPiExecutionContract = "default" | "strict-agentic";
```

**类型**：`{ executionContract?: "default" | "strict-agentic" }`
**是否必填**：否
**默认值**：继承自 `AgentDefaultsConfig.embeddedPi`

**嵌入式 Pi 的执行契约覆盖**。控制 Pi 运行时引擎的行为模式：

| 值 | 含义 |
|---|---|
| `"default"` | 标准运行行为：保持通常的运行器行为，允许智能体在完成任务后自然停止 |
| `"strict-agentic"` | 严格智能体模式：在 OpenAI/OpenAI Codex GPT-5 系列模型运行时，**持续执行直到遇到真正的阻塞点**才停止。这会促使智能体更加坚持不懈地追求目标，而非过早结束 |

**配置示例**：

```yaml
embeddedPi:
  executionContract: "strict-agentic"
```

---

### 1.6.5 `params` (可选)

```typescript
/** Optional per-agent stream params (e.g. cacheRetention, temperature). */
params?: Record<string, unknown>;
```

**类型**：`Record<string, unknown>`
**是否必填**：否
**默认值**：继承自 `AgentDefaultsConfig.params`

智能体级别的**全局提供者参数**。这些参数会被传递给模型提供者插件，影响每次 API 请求。典型的参数包括：
- `cacheRetention`：缓存保留策略
- `temperature`：生成温度
- 其他提供者特定的参数

这些参数会与模型级别的 `params`（见 `AgentModelEntryConfig.params`）合并，智能体级别参数优先。

---

### 1.6.6 `runRetries` (可选)

```typescript
/** Optional outer run loop retry boundaries. */
runRetries?: AgentDefaultsConfig["runRetries"];
```

对应的类型：

```typescript
export type AgentRunRetriesConfig = {
    /** Base number of run retry iterations (default: 24). */
    base?: number;
    /** Additional run retry iterations per fallback profile (default: 8). */
    perProfile?: number;
    /** Minimum limit for run retry iterations (default: 32). */
    min?: number;
    /** Maximum limit for run retry iterations (default: 160). */
    max?: number;
};
```

**类型**：`{ base?, perProfile?, min?, max? }`
**是否必填**：否
**默认值**：继承自 `AgentDefaultsConfig.runRetries`

控制**外部运行循环**的重试边界。当智能体的执行遇到可恢复的错误时，运行框架会根据此配置决定重试的迭代次数。

| 字段 | 默认值 | 说明 |
|---|---|---|
| `base` | `24` | 基础重试迭代次数 |
| `perProfile` | `8` | 每个回退模型的额外重试迭代次数 |
| `min` | `32` | 总重试迭代的最小限制 |
| `max` | `160` | 总重试迭代的最大限制（防止无限重试） |

总重试次数 = `base + (fallbackCount * perProfile)`，但会被 clamp 在 `[min, max]` 范围内。

---

## 1.7 心跳配置

心跳（Heartbeat）是 OpenClaw 的**定时巡检机制**。智能体可以定期主动发起一次推理，检查是否需要处理待办事项、监控状态或发送定时通知。

### 1.7.1 `heartbeat` (可选)

```typescript
/** Optional per-agent heartbeat overrides. */
heartbeat?: AgentDefaultsConfig["heartbeat"];
```

完整的 Heartbeat 配置类型：

```typescript
heartbeat?: {
    /** Heartbeat interval (duration string, default unit: minutes; default: 30m). */
    every?: string;
    /** Optional active-hours window (local time); heartbeats run only inside this window. */
    activeHours?: {
        /** Start time (24h, HH:MM). Inclusive. */
        start?: string;
        /** End time (24h, HH:MM). Exclusive. Use "24:00" for end-of-day. */
        end?: string;
        /** Timezone for the window ("user", "local", or IANA TZ id). Default: "user". */
        timezone?: string;
    };
    /** Heartbeat model override (provider/model). */
    model?: string;
    /** Session key for heartbeat runs ("main" or explicit session key). */
    session?: string;
    /** Override the heartbeat prompt body. */
    prompt?: string;
    /** If true, run heartbeat turns with lightweight bootstrap context. */
    lightContext?: boolean;
    /**
     * If true, run heartbeat turns in an isolated session with no prior
     * conversation history.
     */
    isolatedSession?: boolean;
    /**
     * If true, defer heartbeat runs while this agent's session-keyed subagent
     * or nested command lanes are busy.
     */
    skipWhenBusy?: boolean;
    /** Heartbeat delivery target ("last", "none", or a channel id). */
    target?: string;
    /** Direct/DM delivery policy. Default: "allow". */
    directPolicy?: "allow" | "block";
    /** Optional delivery override (E.164 for WhatsApp, chat id for Telegram). */
    to?: string;
    /** Optional account id for multi-account channels. */
    accountId?: string;
    /** Include the ## Heartbeats system prompt section for the default agent (default: true). */
    includeSystemPromptSection?: boolean;
    /** Max chars allowed after HEARTBEAT_OK before delivery (default: 30). */
    ackMaxChars?: number;
    /** Suppress tool error warning payloads during heartbeat runs. */
    suppressToolErrorWarnings?: boolean;
    /** Run timeout in seconds for heartbeat agent turns. */
    timeoutSeconds?: number;
    /** When enabled, deliver the model's reasoning payload for heartbeat runs. */
    includeReasoning?: boolean;
};
```

**类型**：上述复杂对象
**是否必填**：否
**默认值**：继承自 `AgentDefaultsConfig.heartbeat`

#### 核心字段详解

**`every`**（心跳间隔）

- **格式**：持续时间字符串（如 `"30m"`、`"1h"`、`"5m30s"`）
- **默认值**：`"30m"`（每 30 分钟一次）
- 支持的单位：`s`（秒）、`m`（分钟）、`h`（小时）

**`activeHours`**（活跃时间窗口）

限制心跳仅在指定的时间段内运行。如果当前时间不在窗口内，心跳被跳过。

```yaml
heartbeat:
  every: "15m"
  activeHours:
    start: "09:00"
    end: "18:00"
    timezone: "Asia/Shanghai"
```

- `start`：窗口起始时间（24 小时制，`HH:MM`，**含**该时刻）
- `end`：窗口结束时间（24 小时制，`HH:MM`，**不含**该时刻）。使用 `"24:00"` 表示午夜零点
- `timezone`：时区（`"user"` 使用用户时区、`"local"` 使用主机时区、或 IANA 时区 ID 如 `"Asia/Shanghai"`）。默认 `"user"`

**`model`**（心跳模型覆盖）

```typescript
model?: string;
```

心跳运行时的专用模型（格式：`"provider/model"`）。例如 `"openai/gpt-4.1-mini"`。因为心跳通常是轻量级的检查任务，使用更便宜/更快的模型可以大幅降低成本。

**`prompt`**（心跳提示词）

```typescript
prompt?: string;
```

覆盖心跳运行的提示词正文。默认提示词为：

```
Read HEARTBEAT.md if it exists (workspace context). Follow it strictly.
Do not infer or repeat old tasks from prior chats.
If nothing needs attention, reply HEARTBEAT_OK.
```

自定义提示词时，建议保留"无任务时回复 HEARTBEAT_OK"的约定，以便 OpenClaw 正确解析心跳结果。

**`lightContext`**（轻量引导上下文）

```typescript
lightContext?: boolean;
```

如果为 `true`，心跳运行时**仅注入 `HEARTBEAT.md`**，跳过其他工作空间引导文件（`AGENTS.md`、`SOUL.md` 等）。这显著减少每次心跳的上下文长度和 token 消耗。

**`isolatedSession`**（隔离会话）

```typescript
isolatedSession?: boolean;
```

如果为 `true`，心跳在**完全隔离的会话**中运行，无任何历史对话上下文。智能体仅能看到其引导上下文（加上 `lightContext` 时仅 `HEARTBEAT.md`）。这通过避免加载完整会话历史，大幅降低每次心跳的 token 成本。

**`skipWhenBusy`**（忙碌时跳过）

```typescript
skipWhenBusy?: boolean;
```

如果为 `true`，当此智能体的子智能体或嵌套命令通道正忙时，跳过一次心跳。Cron 通道始终被视为忙碌。此选项防止在智能体已经在处理任务时产生额外的心跳干扰。

**`session`**（会话键）

```typescript
session?: string;
```

心跳运行的会话标识。默认使用 `"main"`。可指定显式会话键以隔离不同心跳的对话状态。

**`ackMaxChars`**（确认最大字符数）

心跳回复超过此字符数时会被截断。默认为 `30`。因为心跳的正常响应只有 `HEARTBEAT_OK`（13 个字符），任何超过此限制的输出都可能是异常内容。

---

## 1.8 子Agent配置

`subagents` 控制智能体**派生和管理子智能体**的行为。

### 1.8.1 `subagents` (可选)

```typescript
subagents?: {
    /** Prompt-only guidance for how strongly this agent should delegate work. */
    delegationMode?: SubagentDelegationMode;
    /** Allow spawning sub-agents under other agent ids. Use "*" to allow any. */
    allowAgents?: string[];
    /** Per-agent default model for spawned sub-agents (string or {primary,fallbacks}). */
    model?: AgentModelConfig;
    /** Require explicit agentId in sessions_spawn (no default same-as-caller). */
    requireAgentId?: boolean;
};
```

对应的类型：

```typescript
export type SubagentDelegationMode = "suggest" | "prefer";
```

**类型**：`{ delegationMode?, allowAgents?, model?, requireAgentId? }`
**是否必填**：否
**默认值**：继承自 `AgentDefaultsConfig.subagents`

#### 子字段详解

**`delegationMode`**（委托模式）

```typescript
delegationMode?: "suggest" | "prefer";
```

这是一个**仅影响提示词**的选项。它控制智能体的系统提示词中关于委托工作的指导语气：

| 值 | 提示词语气 | 适用场景 |
|---|---|---|
| `"suggest"`（默认） | "如果任务适合，你可以考虑委托给子智能体" | 智能体自行判断何时委托 |
| `"prefer"` | "你应该优先将复杂任务委托给专业子智能体" | 希望主智能体更多扮演编排者角色 |

**注意**：`delegationMode` 不强制任何行为——它纯粹是对 LLM 的提示词指导。智能体可能（在模型能力范围内）选择遵循或忽略此指导。

**`allowAgents`**（允许派生的子智能体列表）

```typescript
allowAgents?: string[];
```

可被此智能体通过 `sessions_spawn` 工具派生的目标智能体 ID 列表。使用 `"*"` 允许派生任何已配置的智能体。

```yaml
subagents:
  allowAgents:
    - "code-reviewer"
    - "test-runner"
    - "data-analyzer"
```

如果留空，智能体无法派生任何子智能体。

**`model`**（子智能体默认模型）

```typescript
model?: AgentModelConfig;  // string | { primary?, fallbacks? }
```

子智能体的**默认模型**。与顶层的 `model` 字段一样，接受字符串简写或对象形式。

**`requireAgentId`**（要求显式指定目标智能体 ID）

```typescript
requireAgentId?: boolean;
```

如果为 `true`，在使用 `sessions_spawn` 时必须显式指定目标智能体的 `agentId`，不允许使用"与调用者相同"的默认行为。这增加了安全性，防止意外创建与主智能体相同身份的派生会话。

---

## 1.9 沙箱配置

`sandbox` 控制智能体的**执行隔离环境**。沙箱化可以为高风险操作（如代码执行）提供额外的安全边界。

### 1.9.1 `sandbox` (可选)

```typescript
/** Optional per-agent sandbox overrides. */
sandbox?: AgentSandboxConfig;
```

完整的 `AgentSandboxConfig` 类型：

```typescript
export type AgentSandboxConfig = {
    mode?: "off" | "non-main" | "all";
    /** Sandbox runtime backend id. Default: "docker". */
    backend?: string;
    /** Agent workspace access inside the sandbox. */
    workspaceAccess?: "none" | "ro" | "rw";
    /**
     * Session tools visibility for sandboxed sessions.
     * - "spawned": only allow session tools to target sessions spawned from this session (default)
     * - "all": allow session tools to target any session
     */
    sessionToolsVisibility?: "spawned" | "all";
    /** Container/workspace scope for sandbox isolation. */
    scope?: "session" | "agent" | "shared";
    workspaceRoot?: string;
    /** Docker-specific sandbox settings. */
    docker?: SandboxDockerSettings;
    /** SSH-specific sandbox settings. */
    ssh?: SandboxSshSettings;
    /** Optional sandboxed browser settings. */
    browser?: SandboxBrowserSettings;
    /** Auto-prune sandbox settings. */
    prune?: SandboxPruneSettings;
};
```

**类型**：上述复杂对象
**是否必填**：否
**默认值**：继承自 `AgentDefaultsConfig.sandbox`

#### 核心字段详解

**`mode`**（沙箱模式）

```typescript
mode?: "off" | "non-main" | "all";
```

控制哪些会话被沙箱化：

| 值 | 含义 |
|---|---|
| `"off"` | 禁用沙箱。所有会话在宿主机上直接运行（默认） |
| `"non-main"` | 仅非主会话（子智能体、派生会话）运行在沙箱中。主会话不受影响 |
| `"all"` | 所有会话（包括主会话）都运行在沙箱中 |

`"non-main"` 是最常见的生产配置：主智能体（面向用户的）保持低延迟直连，而它派生的子智能体在沙箱中安全执行。

**`backend`**（沙箱后端）

```typescript
backend?: string;
```

沙箱运行时后端 ID。默认为 `"docker"`，也支持 SSH 沙箱等。

**`workspaceAccess`**（工作空间访问权限）

```typescript
workspaceAccess?: "none" | "ro" | "rw";
```

智能体工作区在沙箱内部的访问权限：

| 值 | 含义 |
|---|---|
| `"none"` | 完全隔离：沙箱内无法访问智能体工作区 |
| `"ro"` | 只读访问：可读取工作区文件，但不能修改 |
| `"rw"` | 读写访问：可自由读写工作区文件 |

**`sessionToolsVisibility`**（会话工具可见性）

```typescript
sessionToolsVisibility?: "spawned" | "all";
```

控制沙箱化会话中会话工具（`sessions_list`、`sessions_history`、`sessions_send`）的可见范围：

| 值 | 含义 |
|---|---|
| `"spawned"`（默认） | 只能看到从此会话派生的子会话 |
| `"all"` | 可以看到所有会话 |

**`scope`**（隔离作用域）

```typescript
scope?: "session" | "agent" | "shared";
```

沙箱容器/工作空间的隔离粒度：

| 值 | 含义 |
|---|---|
| `"session"` | 每个会话一个独立沙箱（最严格隔离） |
| `"agent"` | 同一智能体的所有会话共享一个沙箱 |
| `"shared"` | 全局共享沙箱（最宽松隔离，资源开销最小） |

**其他子字段**：
- `workspaceRoot`：沙箱内的工作空间根路径
- `docker`：Docker 专用设置（镜像、网络、卷挂载等）
- `ssh`：SSH 沙箱设置（远程主机、认证等）
- `browser`：沙箱化浏览器设置（用于 Web 自动化）
- `prune`：自动清理设置（定时删除不活跃的沙箱）

---

## 1.10 上下文限制

`contextLimits` 和 `contextTokens` 控制智能体使用 LLM 上下文窗口时的各种限制。

### 1.10.1 `contextLimits` (可选)

```typescript
/** Optional per-agent overrides for selected context/token-heavy limits. */
contextLimits?: AgentContextLimitsConfig;
```

对应的类型定义：

```typescript
export type AgentContextLimitsConfig = {
    /** Default max chars returned by memory_get before truncation metadata/notice (default: 12000). */
    memoryGetMaxChars?: number;
    /** Default line window for memory_get when lines is omitted (default: 120). */
    memoryGetDefaultLines?: number;
    /** Max chars kept for a single live tool result before truncation (default: 16000). */
    toolResultMaxChars?: number;
    /** Max chars retained from post-compaction AGENTS.md context injection (default: 1800). */
    postCompactionMaxChars?: number;
};
```

**类型**：`{ memoryGetMaxChars?, memoryGetDefaultLines?, toolResultMaxChars?, postCompactionMaxChars? }`
**是否必填**：否
**默认值**：继承自 `AgentDefaultsConfig.contextLimits`

#### 子字段详解

| 字段 | 默认值 | 说明 |
|---|---|---|
| `memoryGetMaxChars` | `12000` | `memory_get` 工具返回结果的最大字符数。超过此限制时，结果会被截断并附加截断元数据/通知 |
| `memoryGetDefaultLines` | `120` | 当 `memory_get` 调用未指定行数参数时的默认行窗口大小 |
| `toolResultMaxChars` | `16000` | 单个**实时工具结果**（非历史）保留的最大字符数。超过此限制的结果在传入 LLM 上下文前会被截断 |
| `postCompactionMaxChars` | `1800` | 压缩（compaction）后从 `AGENTS.md` 注入的上下文保留的最大字符数。压缩后会话历史被摘要替代，此限制控制摘要中保留多少引导内容 |

**为什么需要这些限制**：LLM 的上下文窗口是有限且昂贵的资源。工具输出和记忆检索结果可能非常大（例如读取一个 100KB 的文件），如果不加限制地全量注入，会迅速耗尽上下文窗口并导致高额 token 费用。这些限制在**信息充分性**和**上下文经济性**之间取得平衡。

---

### 1.10.2 `contextTokens` (可选)

```typescript
contextTokens?: number;
```

**类型**：`number`
**是否必填**：否
**默认值**：继承自 `AgentDefaultsConfig.contextTokens`

智能体的**有效上下文窗口令牌数上限**。注意区分两个相关概念：
- 模型的 `contextWindow`（在 `ModelDefinitionConfig` 中定义）是模型本身的**宣传/原生上下文窗口大小**
- `contextTokens` 是 OpenClaw 实际使用的**运行时上下文预算**

`contextTokens` 允许配置小于原生窗口的值，为压缩、摘要和引导文件注入预留空间。运行时状态栏中的上下文使用百分比也基于此值计算。如果未设置，OpenClaw 使用模型的 `contextWindow` 作为默认值。

---

## 1.11 工具配置

`tools` 字段是 `AgentConfig` 中最复杂也最强大的配置组之一。它定义了智能体可以使用哪些工具、工具的运行参数和安全策略。

### 1.11.1 `tools` (可选)

```typescript
tools?: AgentToolsConfig;
```

完整的 `AgentToolsConfig` 类型：

```typescript
export type AgentToolsConfig = {
    /** Base tool profile applied before allow/deny lists. */
    profile?: ToolProfileId;
    allow?: string[];
    /** Additional allowlist entries merged into allow and/or profile allowlist. */
    alsoAllow?: string[];
    deny?: string[];
    /** Optional tool policy overrides keyed by provider id or "provider/model". */
    byProvider?: Record<string, ToolPolicyConfig>;
    /** Per-sender tool policy overrides keyed by sender identity. */
    toolsBySender?: GroupToolPolicyBySenderConfig;
    /** Per-agent code mode override; merges over the top-level tools.codeMode config. */
    codeMode?: CodeModeConfig;
    /** Per-agent elevated exec gate (can only further restrict global tools.elevated). */
    elevated?: {
        enabled?: boolean;
        allowFrom?: AgentElevatedAllowFromConfig;
    };
    /** Exec tool defaults for this agent. */
    exec?: ExecToolConfig;
    /** Filesystem tool path guards. */
    fs?: FsToolsConfig;
    /** Runtime loop detection for repetitive/stuck tool-call patterns. */
    loopDetection?: ToolLoopDetectionConfig;
    /** Message tool configuration for this agent. */
    message?: MessageToolsConfig;
    sandbox?: {
        tools?: {
            allow?: string[];
            alsoAllow?: string[];
            deny?: string[];
        };
    };
};
```

**类型**：上述复杂对象
**是否必填**：否
**默认值**：无智能体级别的工具限制（所有工具可用，除非全局配置另有限制）

---

#### 1.11.1.1 工具配置文件（Tool Profiles）

```typescript
export type ToolProfileId = "minimal" | "coding" | "messaging" | "full";
```

```typescript
profile?: ToolProfileId;
```

通过 `profile` 字段，可以快速选择一**组预设的工具白名单**，而无需逐个指定 `allow` 列表。

| Profile | 含义 | 典型工具 |
|---|---|---|
| `"minimal"` | 最小工具集 | 仅基本的 `read`、`think`、`reply` 等核心原语 |
| `"coding"` | 编码工具集 | 包含文件读写、代码执行、shell 命令等编程相关工具 |
| `"messaging"` | 消息工具集 | 包含发送消息、管理对话等通信相关工具 |
| `"full"` | 完整工具集 | 所有注册的工具均可使用（最强大的权限，也最危险） |

Profile 是**基础层**——它定义了一组默认工具。之后通过 `allow`、`alsoAllow`、`deny` 进行精细化调整。

---

#### 1.11.1.2 工具白名单与黑名单

```typescript
allow?: string[];
alsoAllow?: string[];
deny?: string[];
```

这三者共同构成工具的**精细化权限控制**：

| 字段 | 语义 | 优先级 |
|---|---|---|
| `allow` | **显式白名单**：如果设置，**替换** profile 的白名单（而非追加） | 高 |
| `alsoAllow` | **附加白名单**：**合并**到 `allow` 和/或 profile 的 whitelet 中。设计用于增量配置 | 中 |
| `deny` | **黑名单**：明确禁止的工具，即使出现在白名单中也会被移除 | 最高 |

**关键区别**：
- `allow` 是**替换型**：设置后，仅 `allow` 中的工具和 `alsoAllow` 中的工具可用
- `alsoAllow` 是**追加型**：在现有白名单基础上增加工具，无需复制整个白名单
- `deny` 是**减除型**：始终生效，无论白名单内容

**解析顺序**：
1. 从 `profile` 获取基础工具集
2. 如果设置了 `allow`，替换为基础工具集 ∩ allow
3. 将 `alsoAllow` 的工具加入白名单
4. 从白名单中移除 `deny` 中列出的所有工具

**示例**：

```yaml
tools:
  profile: "coding"
  alsoAllow:
    - "web_search"
    - "web_fetch"
  deny:
    - "exec_shell"
```

此配置从编码工具集出发，额外允许网络搜索和网页抓取，但明确禁止 shell 执行。

---

#### 1.11.1.3 按模型提供者的工具策略

```typescript
byProvider?: Record<string, ToolPolicyConfig>;
```

```typescript
export type ToolPolicyConfig = {
    allow?: string[];
    alsoAllow?: string[];
    deny?: string[];
    profile?: ToolProfileId;
};
```

为**不同的模型提供者**设置不同的工具策略。键是提供者 ID（如 `"openai"`）或完整的 `"provider/model"` 格式。

**使用场景**：某些模型不支持特定工具（例如，一个本地模型可能不支持图片理解），或者你希望更强大的模型有更多工具权限。

```yaml
tools:
  byProvider:
    "openai":
      profile: "full"
    "ollama":
      profile: "coding"
      deny:
        - "web_fetch"
        - "image_understand"
```

---

#### 1.11.1.4 按发送者的工具策略

```typescript
toolsBySender?: GroupToolPolicyBySenderConfig;
```

```typescript
export type GroupToolPolicyConfig = {
    allow?: string[];
    alsoAllow?: string[];
    deny?: string[];
};

export type GroupToolPolicyBySenderConfig = Record<string, GroupToolPolicyConfig>;
```

为**不同的消息发送者**设置不同的工具权限。键使用特定前缀标识发送者身份：

| 键前缀 | 示例 | 匹配方式 |
|---|---|---|
| `channel:<channelId>:<senderId>` | `"channel:discord:user123"` | 按频道 + 发送者 ID |
| `id:<senderId>` | `"id:user123"` | 仅按发送者 ID |
| `e164:<phone>` | `"e164:+8613800138000"` | 按手机号（E.164 格式） |
| `username:<handle>` | `"username:alice"` | 按用户名/句柄 |
| `name:<display-name>` | `"name:Alice"` | 按显示名称 |
| `*` | `"*"` | 通配符，匹配所有发送者 |

传统的无前缀键按发送者 ID 匹配（向后兼容）。

```yaml
tools:
  toolsBySender:
    "id:admin-user":
      profile: "full"
    "id:guest-user":
      profile: "minimal"
    "*":
      profile: "coding"
```

注意：`deny` 策略始终优先于 `allow` 策略。

---

#### 1.11.1.5 代码模式（Code Mode）

```typescript
codeMode?: CodeModeConfig;
```

```typescript
export type CodeModeConfig = boolean | {
    enabled?: boolean;
    runtime?: "quickjs-wasi";       // 仅支持 quickjs-wasi
    mode?: "only";                   // 仅支持 "only"
    languages?: Array<"javascript" | "typescript">;
    timeoutMs?: number;              // exec/wait 调用的墙钟时间限制（毫秒）
    memoryLimitBytes?: number;       // QuickJS 堆内存限制（字节）
    maxOutputBytes?: number;         // 最大序列化输出字节数
    maxSnapshotBytes?: number;       // 最大序列化快照字节数
    maxPendingToolCalls?: number;    // 最大并发嵌套工具调用数
    snapshotTtlSeconds?: number;     // 暂停快照的保留时间
    searchDefaultLimit?: number;     // tools.search 的默认搜索结果数
    maxSearchLimit?: number;         // tools.search 的最大搜索结果数
};
```

当 `codeMode` 启用时，智能体进入**代码执行模式**：正常的工具被隐藏，替代为一个 QuickJS WASM 运行时桥接。智能体通过编写 JavaScript/TypeScript 代码来执行操作，代码在隔离的沙箱环境中运行。

| 关键字段 | 说明 |
|---|---|
| `enabled` | 启用代码模式 |
| `runtime` | 目前仅支持 `"quickjs-wasi"`（WebAssembly 系统接口版的 QuickJS） |
| `mode` | 目前仅支持 `"only"`：暴露 `exec`/`wait`，隐藏所有正常工具 |
| `languages` | 接受的源语言：`"javascript"`、`"typescript"` |
| `timeoutMs` | 单次 `exec` 或 `wait` 调用的墙钟时间限制 |
| `memoryLimitBytes` | QuickJS 堆内存的硬限制 |
| `maxOutputBytes` | 代码执行输出的序列化大小上限 |

布尔简写（`codeMode: true`）等同于 `codeMode: { enabled: true }`。

---

#### 1.11.1.6 提权执行（Elevated）

```typescript
elevated?: {
    /** Enable or disable elevated mode for this agent (default: true). */
    enabled?: boolean;
    /** Approved senders for /elevated (per-provider allowlists). */
    allowFrom?: AgentElevatedAllowFromConfig;
};
```

控制智能体的**提权模式**。提权模式允许智能体获得更高的系统权限（如宿主机命令执行）。

| 字段 | 说明 |
|---|---|
| `enabled` | 是否启用提权模式（默认 `true`，但全局可能有更严格的覆盖） |
| `allowFrom` | 允许哪些发送者请求提权。按提供者配置允许列表 |

**重要限制**：每个智能体的 `elevated` 配置只能**进一步收紧**（而非放宽）全局 `tools.elevated` 的配置。这是单向的安全约束。

---

#### 1.11.1.7 命令执行工具（Exec）

```typescript
exec?: ExecToolConfig;
```

```typescript
export type ExecToolConfig = {
    host?: "auto" | "sandbox" | "gateway" | "node";
    security?: "deny" | "allowlist" | "full";
    ask?: "off" | "on-miss" | "always";
    node?: string;
    pathPrepend?: string[];
    safeBins?: string[];
    strictInlineEval?: boolean;
    commandHighlighting?: boolean;
    safeBinTrustedDirs?: string[];
    safeBinProfiles?: Record<string, SafeBinProfileFixture>;
    backgroundMs?: number;
    timeoutSec?: number;
    approvalRunningNoticeMs?: number;
    cleanupMs?: number;
    notifyOnExit?: boolean;
    notifyOnExitEmptySuccess?: boolean;
    applyPatch?: {
        enabled?: boolean;
        workspaceOnly?: boolean;
        allowModels?: string[];
    };
};
```

控制智能体的**命令执行能力**。

| 关键字段 | 默认值 | 说明 |
|---|---|---|
| `host` | `"auto"` | 执行宿主路由：`"auto"` 自动选择、`"sandbox"` 沙箱内执行、`"gateway"` 网关执行、`"node"` 远程节点执行 |
| `security` | `"deny"` | 安全模式：`"deny"` 禁止所有命令、`"allowlist"` 仅白名单命令、`"full"` 允许任意命令 |
| `ask` | `"on-miss"` | 批准策略：`"off"` 不询问、`"on-miss"` 白名单未命中时询问、`"always"` 每次都询问 |
| `safeBins` | `[]` | 安全二进制白名单：这些命令可以在无白名单条目的情况下运行（仅 stdin 安全操作） |
| `timeoutSec` | — | 命令自动终止前的超时秒数 |
| `backgroundMs` | — | 命令自动转入后台前的时间（毫秒） |
| `strictInlineEval` | — | 对解释器内联 eval 形式（如 `python -c`）要求显式批准 |

---

#### 1.11.1.8 文件系统工具（FS）

```typescript
fs?: FsToolsConfig;
```

```typescript
export type FsToolsConfig = {
    /**
     * Restrict filesystem tools (read/write/edit/apply_patch) to the agent workspace directory.
     * Default: false (unrestricted, matches legacy behavior).
     */
    workspaceOnly?: boolean;
};
```

**类型**：`{ workspaceOnly?: boolean }`
**默认值**：`{ workspaceOnly: false }`（不受限制）

当 `workspaceOnly` 设为 `true` 时，所有文件系统工具（`read`、`write`、`edit`、`apply_patch`）被限制为**只能访问智能体的工作区目录**。这是防止智能体读取或修改系统敏感文件的重要安全措施。

---

#### 1.11.1.9 循环检测（Loop Detection）

```typescript
loopDetection?: ToolLoopDetectionConfig;
```

```typescript
export type ToolLoopDetectionConfig = {
    enabled?: boolean;                       // 默认 false
    historySize?: number;                    // 保留用于检测的工具调用历史条目数（默认 30）
    warningThreshold?: number;               // 警告阈值（默认 10）
    unknownToolThreshold?: number;           // 不可用工具重复调用阈值（默认 10）
    criticalThreshold?: number;              // 阻塞阈值（默认 20）
    globalCircuitBreakerThreshold?: number;  // 全局无进展断路器阈值（默认 30）
    detectors?: ToolLoopDetectionDetectorConfig;
    postCompactionGuard?: ToolLoopPostCompactionGuardConfig;
};
```

检测并阻止智能体的**工具调用死循环**。

| 关键字段 | 说明 |
|---|---|
| `enabled` | 启用保护（默认 `false`） |
| `warningThreshold` | 警告级别循环分类的阈值（默认 10 次） |
| `criticalThreshold` | 阻塞级别重复循环的阈值（默认 20 次） |
| `globalCircuitBreakerThreshold` | 全局无进展断路器阈值（默认 30 次） |
| `detectors` | 检测器开关：`genericRepeat`（相同调用重复）、`knownPollNoProgress`（已知无进展轮询）、`pingPong`（乒乓交替模式） |
| `postCompactionGuard` | 压缩后循环防护：当智能体在压缩重试后立即重复相同操作时中止 |

---

#### 1.11.1.10 消息工具（Message）

```typescript
message?: MessageToolsConfig;
```

```typescript
export type MessageToolsConfig = {
    allowCrossContextSend?: boolean;  // @deprecated
    crossContext?: {
        allowWithinProvider?: boolean;    // 默认 true
        allowAcrossProviders?: boolean;   // 默认 false
        marker?: {
            enabled?: boolean;            // 默认 true
            prefix?: string;
            suffix?: string;
        };
    };
    actions?: {
        allow?: string[];
    };
    broadcast?: {
        enabled?: boolean;                // 默认 true
    };
};
```

控制智能体的**消息发送工具**。关键的跨上下文消息控制：

| 字段 | 默认值 | 说明 |
|---|---|---|
| `crossContext.allowWithinProvider` | `true` | 允许向同一提供者内的其他通道发送消息 |
| `crossContext.allowAcrossProviders` | `false` | 允许向不同提供者的通道发送消息（更危险的跨平台权限） |
| `broadcast.enabled` | `true` | 允许广播消息 |
| `actions.allow` | — | 允许的消息动作名称白名单 |

---

#### 1.11.1.11 沙箱工具

```typescript
sandbox?: {
    tools?: {
        allow?: string[];
        alsoAllow?: string[];
        deny?: string[];
    };
};
```

专门为沙箱化会话配置工具策略。当智能体或其子智能体在沙箱中运行时，这些策略控制沙箱内可用的工具。结构与顶级 `tools.allow`/`alsoAllow`/`deny` 一致。

---

### 1.11.2 其他工具相关配置

#### `compaction`（已废弃）

```typescript
/** @deprecated Legacy per-agent compaction config is kept for raw doctor migration/repair. */
compaction?: AgentDefaultsConfig["compaction"];
```

**状态**：已废弃。保留仅用于 `doctor` 工具的原始数据迁移/修复。新配置应通过 `AgentDefaultsConfig.compaction` 设置。

#### `humanDelay`（可选）

```typescript
/** Human-like delay between block replies for this agent. */
humanDelay?: HumanDelayConfig;
```

**类型**：`HumanDelayConfig`
**是否必填**：否
**默认值**：继承自 `AgentDefaultsConfig.humanDelay`

模拟人类回复延迟。在多块回复之间插入可配置的延迟，使智能体的回复节奏更自然。

#### `tts`（可选）

```typescript
/** Optional per-agent TTS overrides, deep-merged over messages.tts. */
tts?: TtsConfig;
```

**类型**：`TtsConfig`
**是否必填**：否
**默认值**：继承自全局 TTS 配置

智能体的**文本转语音覆盖**。使用**深度合并**策略覆盖全局消息 TTS 配置（而非替换）。

#### `groupChat`（可选）

```typescript
groupChat?: GroupChatConfig;
```

**类型**：`GroupChatConfig`
**是否必填**：否
**默认值**：无

智能体的**群聊配置**。控制在群组对话场景中的行为。

---

## 1.12 级联继承详解

再回OpenClaw 配置系统的核心设计原则：**级联继承（Cascading Inheritance）**。

### 优先级链

```
AgentConfig.list[i].字段
    ↓ 覆盖
AgentDefaultsConfig.字段
    ↓ 覆盖
硬编码内置默认值
```

### 字段继承行为分类

并非所有字段的继承行为都相同。根据合并策略，可分为三类：

#### 类型一：简单覆盖（大多数字段）

绝大多数字段采用**简单覆盖**策略：如果 `AgentConfig` 层面的字段有值，直接使用；如果为 `undefined`，从 `AgentDefaultsConfig` 继承。

此类型的字段包括：`workspace`、`model`、`thinkingDefault`、`verboseDefault`、`reasoningDefault`、`fastModeDefault`、`contextInjection`、`bootstrapMaxChars`、`bootstrapTotalMaxChars`、`contextLimits`、`contextTokens`、`agentRuntime`、`heartbeat`、`sandbox`、`runRetries`、`embeddedPi`、`runtime` 等。

#### 类型二：替换策略（skills）

```typescript
skills?: string[];
```

`skills` 使用**替换策略**而非合并策略。当在 `AgentConfig` 层面显式设置 `skills` 时，它**完全替换**（而非追加到）`AgentDefaultsConfig.skills`。如果你想保留默认技能并新增，必须显式列出所有需要的技能。

#### 类型三：深度合并（tts）

```typescript
tts?: TtsConfig;
```

`tts` 使用**深度合并**策略：`AgentConfig` 中的 TTS 配置与全局消息 TTS 配置进行深度合并（而非简单覆盖），允许在智能体层面仅覆盖部分 TTS 参数而保留其他默认值。

### 对象字段的继承

对于嵌套对象字段（如 `heartbeat`、`sandbox`、`subagents`），继承行为取决于 OpenClaw 内部的合并实现。通常采用**浅合并**：仅在 `AgentConfig` 中指定的子字段覆盖默认值中的对应子字段，未指定的子字段继续从默认值继承。

```yaml
# AgentDefaultsConfig
heartbeat:
  every: "30m"
  lightContext: false
  isolatedSession: false

# AgentConfig（仅覆盖 every）
heartbeat:
  every: "10m"
  # lightContext 和 isolatedSession 仍从默认值继承
```

### 字符串简写的继承

对于 `AgentModelConfig`（`string | { primary?, fallbacks? }`）：
- 字符串简写完全替换默认值中的模型配置
- 对象形式中的 `primary` 覆盖默认的 primary，`fallbacks` 覆盖默认的 fallbacks

---

## 1.13 完整字段速查表

以下是 `AgentConfig` 全部 37 个字段的汇总表。标注了每个字段的所属分组、类型、是否必填、默认值来源和简要说明。

| # | 字段 | 分组 | 类型 | 必填 | 默认值来源 | 简要说明 |
|---|---|---|---|---|---|---|
| 1 | `id` | 标识与基础信息 | `string` | **是** | 无（必须显式提供） | 智能体唯一标识符 |
| 2 | `default` | 标识与基础信息 | `boolean` | 否 | 硬编码（`false`） | 是否为默认智能体 |
| 3 | `name` | 标识与基础信息 | `string` | 否 | 使用 `id` | 显示名称 |
| 4 | `description` | 标识与基础信息 | `string` | 否 | 无 | 人类可读描述 |
| 5 | `workspace` | 标识与基础信息 | `string` | 否 | `AgentDefaultsConfig.workspace` | 工作目录路径 |
| 6 | `agentDir` | 标识与基础信息 | `string` | 否 | 无 | 智能体专用配置目录 |
| 7 | `identity` | 标识与基础信息 | `IdentityConfig` | 否 | 全局身份配置 | 智能体身份覆盖 |
| 8 | `systemPromptOverride` | 系统提示词与启动上下文 | `string` | 否 | `AgentDefaultsConfig.systemPromptOverride` | 完整替换系统提示词 |
| 9 | `contextInjection` | 系统提示词与启动上下文 | `"always" \| "continuation-skip" \| "never"` | 否 | `AgentDefaultsConfig.contextInjection`（硬编码 `"always"`） | 上下文注入时机策略 |
| 10 | `bootstrapMaxChars` | 系统提示词与启动上下文 | `number` | 否 | `AgentDefaultsConfig.bootstrapMaxChars`（硬编码 `20000`） | 单文件最大注入字符数 |
| 11 | `bootstrapTotalMaxChars` | 系统提示词与启动上下文 | `number` | 否 | `AgentDefaultsConfig.bootstrapTotalMaxChars`（硬编码 `150000`） | 总注入字符数上限 |
| 12 | `skills` | 系统提示词与启动上下文 | `string[]` | 否 | `AgentDefaultsConfig.skills`（替换策略） | 技能白名单 |
| 13 | `skillsLimits` | 系统提示词与启动上下文 | `{ maxSkillsPromptChars }` | 否 | 无限制 | 技能提示词字符限制 |
| 14 | `memorySearch` | 系统提示词与启动上下文 | `MemorySearchConfig` | 否 | `AgentDefaultsConfig.memorySearch` | 向量记忆搜索配置 |
| 15 | `model` | 模型配置 | `string \| { primary?, fallbacks? }` | 否 | `AgentDefaultsConfig.model` | 主模型与回退模型 |
| 16 | `models` | 模型配置 | `Record<string, AgentModelEntryConfig>` | 否 | `AgentDefaultsConfig.models` | 按模型条目的元数据覆盖 |
| 17 | `thinkingDefault` | 思维/推理/详细模式 | `"off" \| "minimal" \| "low" \| "medium" \| "high" \| "xhigh" \| "adaptive" \| "max"` | 否 | `AgentDefaultsConfig.thinkingDefault` | 默认思考级别 |
| 18 | `verboseDefault` | 思维/推理/详细模式 | `"off" \| "on" \| "full"` | 否 | `AgentDefaultsConfig.verboseDefault` | 默认详细程度 |
| 19 | `toolProgressDetail` | 思维/推理/详细模式 | `"explain" \| "raw"` | 否 | `AgentDefaultsConfig.toolProgressDetail`（硬编码 `"explain"`） | 工具进度详情模式 |
| 20 | `reasoningDefault` | 思维/推理/详细模式 | `"on" \| "off" \| "stream"` | 否 | `AgentDefaultsConfig.reasoningDefault` | 默认推理可见性 |
| 21 | `fastModeDefault` | 思维/推理/详细模式 | `boolean` | 否 | `AgentDefaultsConfig` 或硬编码 | 快速模式开关 |
| 22 | `agentRuntime` | 运行时配置 | `{ id?: string }` | 否 | `AgentDefaultsConfig.agentRuntime`（默认 `"pi"`） | 智能体运行时策略 |
| 23 | `runtime` | 运行时配置 | `{ type: "embedded" } \| { type: "acp"; acp? }` | 否 | 嵌入式运行时 | 运行时描述符（embedded vs acp） |
| 24 | `embeddedHarness` | 运行时配置 | `{ runtime?: string }` | 否 | — | **已废弃**，使用 `agentRuntime` |
| 25 | `embeddedPi` | 运行时配置 | `{ executionContract?: "default" \| "strict-agentic" }` | 否 | `AgentDefaultsConfig.embeddedPi` | Pi 运行时执行契约 |
| 26 | `params` | 运行时配置 | `Record<string, unknown>` | 否 | `AgentDefaultsConfig.params` | 全局提供者参数透传 |
| 27 | `runRetries` | 运行时配置 | `{ base?, perProfile?, min?, max? }` | 否 | `AgentDefaultsConfig.runRetries` | 运行循环重试边界 |
| 28 | `heartbeat` | 心跳配置 | Heartbeat 对象 | 否 | `AgentDefaultsConfig.heartbeat` | 定时心跳巡检 |
| 29 | `subagents` | 子Agent配置 | `{ delegationMode?, allowAgents?, model?, requireAgentId? }` | 否 | `AgentDefaultsConfig.subagents` | 子智能体派生策略 |
| 30 | `sandbox` | 沙箱配置 | `AgentSandboxConfig` | 否 | `AgentDefaultsConfig.sandbox` | 执行隔离沙箱配置 |
| 31 | `contextLimits` | 上下文限制 | `{ memoryGetMaxChars?, memoryGetDefaultLines?, toolResultMaxChars?, postCompactionMaxChars? }` | 否 | `AgentDefaultsConfig.contextLimits` | 上下文使用限制 |
| 32 | `contextTokens` | 上下文限制 | `number` | 否 | `AgentDefaultsConfig.contextTokens` | 有效上下文窗口令牌数 |
| 33 | `tools` | 工具配置 | `AgentToolsConfig` | 否 | 无限制（全局配置另有设置除外） | 工具权限与策略 |
| 34 | `compaction` | 工具相关（已废弃） | `AgentCompactionConfig` | 否 | — | **已废弃**，保留用于数据迁移 |
| 35 | `humanDelay` | 工具相关 | `HumanDelayConfig` | 否 | `AgentDefaultsConfig.humanDelay` | 人类回复延迟模拟 |
| 36 | `tts` | 工具相关 | `TtsConfig` | 否 | 全局 TTS 配置（深度合并） | 文本转语音覆盖 |
| 37 | `groupChat` | 工具相关 | `GroupChatConfig` | 否 | 无 | 群聊行为配置 |

---

## 1.14 配置示例

以下是一个完整的 `AgentConfig` 配置示例，展示各功能组的组合使用：

```yaml
agents:
  defaults:
    model: "openai/gpt-5.4-mini"
    thinkingDefault: "low"
    heartbeat:
      every: "30m"
    sandbox:
      mode: "non-main"
      backend: "docker"
      workspaceAccess: "ro"

  list:
    - id: "code-reviewer"
      name: "Code Reviewer"
      description: "Expert code review agent"
      default: true
      workspace: "/home/user/projects"
      model:
        primary: "anthropic/claude-sonnet-4-6"
        fallbacks:
          - "openai/gpt-5.4"
      models:
        "openai/gpt-5.4":
          alias: "gpt5"
          streaming: true
          params:
            temperature: 0.3
        "ollama/llama3.1":
          streaming: false
      thinkingDefault: "high"
      verboseDefault: "full"
      reasoningDefault: "on"
      contextLimits:
        toolResultMaxChars: 8000
        postCompactionMaxChars: 2000
      contextTokens: 100000
      heartbeat:
        every: "1h"
        lightContext: true
        isolatedSession: true
        skipWhenBusy: true
        model: "openai/gpt-4.1-mini"
      subagents:
        delegationMode: "prefer"
        allowAgents:
          - "test-runner"
          - "linter"
        model: "openai/gpt-4.1-mini"
        requireAgentId: true
      tools:
        profile: "coding"
        alsoAllow:
          - "web_search"
          - "web_fetch"
        deny:
          - "exec_shell"
        exec:
          security: "allowlist"
          ask: "on-miss"
          timeoutSec: 60
        fs:
          workspaceOnly: true
        loopDetection:
          enabled: true
          criticalThreshold: 15
      runtime:
        type: "embedded"
      embeddedPi:
        executionContract: "strict-agentic"
```

这个示例展示了一个代码审查智能体，它：
- 使用 Claude Sonnet 4.6 作为主模型，GPT-5.4 作为回退
- 启用了高思考级别、完整详细模式和推理可见
- 每小时心跳巡检（轻量上下文 + 隔离会话 + 忙碌时跳过）
- 可以派生 `test-runner` 和 `linter` 子智能体
- 使用编码工具集，额外允许网络搜索，但禁止 shell 执行
- 文件系统工具限制在 Workspace 内
- 启用了工具循环检测
- 使用嵌入式 Pi 运行时，并启用严格智能体执行契约

---

> **下一节预览**：本文档（上篇）完整覆盖了 `AgentConfig` 的全部 37 个字段定义。在后续章节中，我们将深入探讨 `AgentDefaultsConfig` 的详细字段、`AgentsConfig` 的高级拓扑、以及智能体绑定的路由匹配机制。
# 一、Agent定义详细解析（下）：全局默认值、模型体系与路由绑定

> 本文档基于 OpenClaw 源码中的类型定义文件，深入剖析 `AgentDefaultsConfig` 的全局级别专属字段、九种模型 API 类型、模型选择优先级链、路由绑定机制以及配置继承规则。
>
> 主要参考源文件：
> - `dist/plugin-sdk/src/config/types.agent-defaults.d.ts`
> - `dist/plugin-sdk/src/config/types.agents.d.ts`
> - `dist/plugin-sdk/src/config/types.agents-shared.d.ts`
> - `dist/plugin-sdk/src/config/types.models.d.ts`

---

## 1.1 AgentDefaultsConfig 全局级别专属字段

`AgentDefaultsConfig` 是 OpenClaw 中定义全局默认值的核心类型，位于 `agents.defaults` 配置路径下。该类型包含大量字段，但并非所有字段都能在单个 Agent 级别（`agents.list[].*`）被覆盖。本节重点分析那些**只能**在全局级别配置、不能在 `AgentConfig` 中进行逐 Agent 覆盖的字段，并解释其设计原因。

### 1.1.1 多模态生成模型及 PDF 处理模型

以下六个字段仅存在于 `AgentDefaultsConfig` 中，在 `AgentConfig`（即 `agents.list[]` 的每个元素）中找不到对应项：

#### imageModel

```typescript
/** Optional image-capable model and fallbacks (provider/model).
 *  Accepts string or {primary,fallbacks}. */
imageModel?: AgentToolModelConfig;
```

`imageModel` 用于指定默认的图像理解模型。当 Agent 需要分析图像内容时（例如用户发送了一张截图并要求解释），系统会使用此处配置的模型。与普通的 `model` 字段不同，`imageModel` 是 `AgentToolModelConfig` 类型，比 `AgentModelConfig` 多了 `timeoutMs` 字段，因为图像处理通常比纯文本处理需要更长的超时时间。

`AgentToolModelConfig` 的完整类型定义：

```typescript
export type AgentToolModelConfig = string | {
    primary?: string;       // 主模型 (provider/model)
    fallbacks?: string[];   // 备用模型列表
    timeoutMs?: number;     // 可选的请求超时时间（毫秒）
};
```

#### imageGenerationModel

```typescript
/** Optional image-generation model and fallbacks (provider/model).
 *  Accepts string or {primary,fallbacks}. */
imageGenerationModel?: AgentToolModelConfig;
```

`imageGenerationModel` 指定默认的图像生成模型。与 `imageModel`（图像理解）不同，此字段用于**生成**图像（例如 DALL-E、Stable Diffusion 等）。当 Agent 需要创建图像时，会使用此配置。

#### videoGenerationModel

```typescript
/** Optional video-generation model and fallbacks (provider/model).
 *  Accepts string or {primary,fallbacks}. */
videoGenerationModel?: AgentToolModelConfig;
```

`videoGenerationModel` 指定默认的视频生成模型。这是一种高度专业化的能力，通常只有少数提供商（如 Runway、Pika 等）支持。将其限制在全局级别可以避免每个 Agent 各自配置视频生成模型，从而减少配置复杂度和潜在的资源滥用。

#### musicGenerationModel

```typescript
/** Optional music-generation model and fallbacks (provider/model).
 *  Accepts string or {primary,fallbacks}. */
musicGenerationModel?: AgentToolModelConfig;
```

`musicGenerationModel` 指定默认的音乐/音频生成模型（如 Suno、Udio 等）。与视频生成类似，音乐生成也是高度专业化的能力。

#### mediaGenerationAutoProviderFallback

```typescript
/**
 * When true (default), shared image/music/video generation appends other
 * auth-backed provider defaults after explicit primary/fallback refs. Set to
 * false to disable implicit cross-provider fallback while keeping explicit
 * fallbacks.
 */
mediaGenerationAutoProviderFallback?: boolean;
```

此字段控制多模态生成模型的跨提供商自动回退行为。当设为 `true`（默认值）时，系统会在显式配置的 primary/fallback 之后自动追加其他已认证提供商的默认模型作为额外的回退选项。这提供了一层"安全网"，确保即使显式配置的模型不可用，系统仍能尝试其他可用的生成模型。

#### pdfModel、pdfMaxBytesMb、pdfMaxPages

```typescript
/** Optional PDF-capable model and fallbacks (provider/model).
 *  Accepts string or {primary,fallbacks}. */
pdfModel?: AgentToolModelConfig;

/** Maximum PDF file size in megabytes (default: 10). */
pdfMaxBytesMb?: number;

/** Maximum number of PDF pages to process (default: 20). */
pdfMaxPages?: number;
```

这三个字段共同控制 PDF 处理能力：

- **`pdfModel`**：指定能够处理 PDF 文件的默认模型。PDF 处理需要模型具备文档解析和长上下文能力，通常只有特定模型支持。
- **`pdfMaxBytesMb`**：限制 PDF 文件的最大体积（默认 10MB）。这是一个资源保护措施——PDF 文件可能包含大量嵌入图像和数据，无限制地接收大文件可能导致内存溢出或处理超时。
- **`pdfMaxPages`**：限制处理的页数上限（默认 20 页）。即使是体积较小的 PDF 也可能包含数百页，此限制确保处理时间可控。

#### 为什么这些字段只能全局配置

这些多模态生成模型和 PDF 模型字段被设计为仅全局级别的原因主要有以下几点：

1. **安全边界（Security Boundary）**：图像生成、视频生成和音乐生成通常涉及第三方 API 调用并产生费用。如果允许每个 Agent 独立配置生成模型，恶意或配置错误的 Agent 可能被用来大量生成内容，导致意外的费用开销。将这些配置集中到全局级别，管理员可以统一管控哪些生成能力可用。

2. **资源约束（Resource Constraints）**：PDF 处理（尤其是大型 PDF）是资源密集型操作。`pdfMaxBytesMb` 和 `pdfMaxPages` 是全局性的资源限制阀，确保无论哪个 Agent 在处理 PDF，系统资源消耗都在可控范围内。

3. **能力一致性（Capability Consistency）**：多模态生成是"基础设施级"能力而非"Agent 级"能力。将其放在全局级别意味着所有 Agent 共享同一套生成模型配置，避免了因 Agent 间配置不一致导致的行为差异。

4. **简化 Agent 配置（Simplified Agent Config）**：大多数 Agent 不需要关心图像/视频/音乐生成的具体模型选择。将这些字段限制在全局级别，使 Agent 配置更简洁，聚焦于 Agent 的核心行为定义。

### 1.1.2 ContextPruningConfig：上下文修剪配置

`AgentContextPruningConfig` 是仅存在于全局级别的重要配置，用于控制如何修剪过期的工具调用结果以减少 token 消耗。

```typescript
export type AgentContextPruningConfig = {
    mode?: "off" | "cache-ttl";
    /** TTL to consider cache expired (duration string, default unit: minutes). */
    ttl?: string;
    keepLastAssistants?: number;
    softTrimRatio?: number;
    hardClearRatio?: number;
    minPrunableToolChars?: number;
    tools?: {
        allow?: string[];
        deny?: string[];
    };
    softTrim?: {
        maxChars?: number;
        headChars?: number;
        tailChars?: number;
    };
    hardClear?: {
        enabled?: boolean;
        placeholder?: string;
    };
};
```

#### 字段详解

**mode**：上下文修剪模式。
- `"off"`：完全关闭上下文修剪，所有工具调用结果永久保留在上下文中。这是默认行为。
- `"cache-ttl"`：基于 TTL（Time To Live）的修剪模式。当缓存过期后，旧的工具结果会被修剪。

**ttl**：缓存过期时间，使用时长字符串格式（如 `"30m"` 表示 30 分钟，`"2h"` 表示 2 小时）。默认单位为分钟。当工具结果在上下文中存在超过此时间后，将被标记为可修剪。

**keepLastAssistants**：保留最近 N 个助手回合的上下文，即使它们的 TTL 已过期。这确保最近的对话轮次始终完整，提供连贯的对话体验。

**softTrimRatio**：软修剪比例。当上下文增长到接近窗口限制时，系统首先尝试"软修剪"——裁剪工具结果的冗余部分但保留关键信息。此比例控制多少内容会被软修剪。

**hardClearRatio**：硬清除比例。当软修剪不足以释放足够空间时，系统会执行"硬清除"——将整个工具结果替换为占位符。此比例控制触发硬清除的阈值。

**minPrunableToolChars**：工具调用结果的最小可修剪字符数。短于该长度的工具结果不会被修剪，避免修剪过于细碎的内容。

**tools.allow / tools.deny**：白名单/黑名单机制，控制哪些工具的调用结果可以被修剪。
- `allow`：仅允许修剪列表中指定的工具的结果。
- `deny`：禁止修剪列表中指定的工具的结果。

**softTrim**：软修剪的具体参数。
- `maxChars`：软修剪后保留的最大字符数。
- `headChars`：从开头保留的字符数。
- `tailChars`：从结尾保留的字符数。
软修剪的工作原理是"掐头去尾留中间"——保留工具结果的开头和结尾部分，中间部分被截断。

**hardClear**：硬清除的具体参数。
- `enabled`：是否启用硬清除。
- `placeholder`：替换被清除内容的占位符文本，例如 `"[此工具结果已被修剪以节省上下文空间]"`。

#### 为什么只能全局配置

上下文修剪直接影响 LLM 的"记忆"质量。不同的修剪策略可能导致 Agent 行为产生质的差异——如果一个 Agent 的修剪策略过于激进，它可能丢失关键上下文从而导致错误决策；而另一个 Agent 保留过多上下文则可能导致 token 消耗过高。将此项限制在全局级别确保了：

1. **一致的内存管理策略**：所有 Agent 遵守相同的修剪规则，行为可预测。
2. **统一的成本控制**：token 消耗与成本直接相关，全局统一的修剪策略有助于成本预算管理。
3. **避免 Agent 间的"军备竞赛"**：如果允许逐 Agent 配置，某些 Agent 可能被配置为保留大量上下文以提高性能，从而不公平地消耗 token 预算。

### 1.1.3 StartupContext：启动上下文配置

```typescript
export type AgentStartupContextConfig = {
    /** Enable runtime-owned startup-context prelude on bare session resets
     *  (default: true). */
    enabled?: boolean;
    /** Which bare reset commands should receive startup context
     *  (default: ["new", "reset"]). */
    applyOn?: Array<"new" | "reset">;
    /** How many dated memory files to load counting backward from today
     *  (default: 2). */
    dailyMemoryDays?: number;
    /** Max bytes to read from each daily memory file before skipping
     *  (default: 16384). */
    maxFileBytes?: number;
    /** Max characters retained from each daily memory file (default: 1200). */
    maxFileChars?: number;
    /** Max total characters retained across the startup prelude (default: 2800). */
    maxTotalChars?: number;
};
```

`StartupContext` 控制当用户执行 `/new` 或 `/reset` 命令重置会话时，系统如何在新的会话开头注入"启动上下文"。这是一种**运行时拥有的**（runtime-owned）机制，在空白会话重置时自动触发。

#### 字段详解

**enabled**：是否启用启动上下文注入。默认为 `true`。当设为 `false` 时，`/new` 和 `/reset` 后会话完全从零开始，没有任何预加载的上下文。

**applyOn**：指定哪些重置命令会触发启动上下文注入。默认为 `["new", "reset"]`。可以只对 `/new` 启用而禁用 `/reset`，反之亦然。

**dailyMemoryDays**：加载多少天的"日记"记忆文件，从今天起向前倒数。默认为 2 天。例如，如果今天是 5 月 20 日，且 `dailyMemoryDays` 设为 2，则系统会加载 5 月 19 日和 5 月 20 日的记忆文件。

**maxFileBytes**：每个日记记忆文件的最大读取字节数，超过此阈值则跳过。默认为 16384 字节（16KB）。这是防止加载过大文件的安全措施。

**maxFileChars**：从每个日记记忆文件中保留的最大字符数。默认为 1200 个字符。即使文件被完整读取，系统也只会保留开头 1200 个字符注入到上下文中。

**maxTotalChars**：跨所有启动上下文的保留字符总数上限。默认为 2800 个字符。这是一个全局性的硬限制，确保启动上下文不会过大。

#### 工作流程

当用户执行 `/new` 或 `/reset` 时：

1. 系统检查 `startupContext.enabled` 是否为 `true`。
2. 检查当前命令是否在 `applyOn` 列表中。
3. 根据 `dailyMemoryDays` 确定要加载的日期范围。
4. 对每一天，尝试读取对应的日记记忆文件，受 `maxFileBytes` 限制。
5. 从每个文件中截取前 `maxFileChars` 个字符。
6. 将所有截取内容合并，总长度不超过 `maxTotalChars`。
7. 将合并后的内容注入到新会话的系统提示开头。

#### 为什么只能全局配置

启动上下文是"会话基础设施"的一部分，而非 Agent 行为的一部分。原因如下：

1. **会话级别而非 Agent 级别**：`/new` 和 `/reset` 是作用于整个会话的操作，而非单个 Agent。会话重置后的上下文注入方式应该在全局范围内一致。
2. **资源管理**：`dailyMemoryDays`、`maxFileBytes` 等参数控制着文件 I/O 和内存使用。将这些限制放在全局级别可以确保无论哪个 Agent 处理启动时，资源使用都在可控范围内。
3. **用户体验一致性**：用户期望 `/new` 和 `/reset` 的行为在任何 Agent 下都是一致的。

### 1.1.4 Compaction 完整配置（27 个字段）

Compaction（对话压缩）是 OpenClaw 中最重要的上下文管理机制之一。当对话历史过长，接近模型上下文窗口限制时，系统会将早期对话历史压缩为摘要，释放 token 空间。`AgentCompactionConfig` 提供了精细的压缩行为控制。

```typescript
export type AgentCompactionConfig = {
    mode?: AgentCompactionMode;              // 1
    reserveTokens?: number;                  // 2
    keepRecentTokens?: number;               // 3
    reserveTokensFloor?: number;             // 4
    maxHistoryShare?: number;                // 5
    customInstructions?: string;             // 6
    recentTurnsPreserve?: number;            // 7
    identifierPolicy?: AgentCompactionIdentifierPolicy;  // 8
    identifierInstructions?: string;         // 9
    qualityGuard?: AgentCompactionQualityGuardConfig;    // 10-11
    midTurnPrecheck?: AgentCompactionMidTurnPrecheckConfig; // 12
    postIndexSync?: AgentCompactionPostIndexSyncMode;     // 13
    memoryFlush?: AgentCompactionMemoryFlushConfig;       // 14-20
    postCompactionSections?: string[];       // 21
    model?: string;                          // 22
    timeoutSeconds?: number;                 // 23
    provider?: string;                       // 24
    truncateAfterCompaction?: boolean;       // 25
    maxActiveTranscriptBytes?: number | string; // 26
    notifyUser?: boolean;                    // 27
};
```

> 注意：虽然 `AgentConfig` 中存在 `compaction?: AgentDefaultsConfig["compaction"]` 字段，但该字段被标记为 `@deprecated`，仅用于"原始配置医生迁移/修复"（raw doctor migration/repair）目的。在实际运行时，Compaction 配置是全局统一管理的，不应在单个 Agent 上设置不同的压缩策略。

#### 1. 压缩模式（mode）

```typescript
export type AgentCompactionMode = "default" | "safeguard";
```

- **`"default"`**：标准压缩模式。系统自动检测上下文是否接近窗口限制，在必要时触发压缩。
- **`"safeguard"`**：保护模式。在标准压缩的基础上增加了额外的安全措施，包括质量审计和重试机制。

#### 2. reserveTokens

Pi 预留 token 目标值。压缩后系统会确保至少有 `reserveTokens` 个 token 可用于后续的模型响应。这个值需要在"保留足够上下文"和"为响应预留空间"之间取得平衡。

#### 3. keepRecentTokens

用于剪切点选择的 Pi `keepRecentTokens` 预算。该值决定在压缩时"最近部分的上下文"保留多少 token。较大的值意味着更多近期对话被保留为原始形式（而非摘要），但可用于摘要的空间相应减少。

#### 4. reserveTokensFloor

Pi 压缩的最低预留 token 数。即使其他约束条件（如 `reserveTokens`）要求更低的预留值，系统也不会低于此底线。设为 0 可禁用底线。这是防止过度压缩导致模型"失忆"的安全机制。

#### 5. maxHistoryShare

在 safeguard 修剪期间，历史部分最多可占上下文窗口的比例。取值范围 0.1-0.9，默认 0.5（即 50%）。这意味着无论什么情况，最多只有一半的上下文窗口被历史内容占据，另一半预留给当前交互。

#### 6. customInstructions

额外的压缩摘要指令。这些自定义指令可帮助保持语言一致性（例如"请保持中文回复风格"）或角色人设连续性。这些指令会被注入到压缩摘要的生成提示中。

#### 7. recentTurnsPreserve

在压缩摘要上下文中保留为逐字原文（verbatim）的最近用户/助手回合数。设为 3 意味着最近 3 轮对话不会被压缩，保持为原始形式。这确保了压缩后的连续性——用户最近的交流内容不会被摘要化。

#### 8. identifierPolicy

标识符保留指令策略，控制在压缩摘要中如何保留关键标识符（如文件名、函数名、变量名等）。

```typescript
export type AgentCompactionIdentifierPolicy = "strict" | "off" | "custom";
```

- **`"strict"`**：严格模式下，压缩摘要会尽力保留所有识别到的标识符名称。
- **`"off"`**：关闭标识符保留，压缩摘要更简洁但可能丢失关键名称。
- **`"custom"`**：使用 `identifierInstructions` 中自定义的保留规则。

#### 9. identifierInstructions

当 `identifierPolicy` 设为 `"custom"` 时使用的自定义标识符保留指令。例如可以指定"保留所有以 `src/` 开头的文件路径"。

#### 10-11. qualityGuard（质量审计）

```typescript
export type AgentCompactionQualityGuardConfig = {
    enabled?: boolean;    // 启用压缩摘要质量审计（默认 false）
    maxRetries?: number;  // 质量审计失败后的最大重试次数（默认 1）
};
```

仅在 `mode: "safeguard"` 下生效。质量审计机制在压缩摘要生成后对其进行检查，如果摘要质量不达标（例如丢失了关键信息），则触发重新生成。这增加了压缩的可靠性，但也增加了 token 消耗（重试需要额外的 API 调用）。

#### 12. midTurnPrecheck（回合中预检）

```typescript
export type AgentCompactionMidTurnPrecheckConfig = {
    enabled?: boolean;  // 默认 false
};
```

在工具结果被追加到上下文后、下一次 Pi 模型调用前，检查上下文压力。如果上下文已接近窗口限制，即使当前回合尚未结束，也可能触发压缩。这解决了"工具密集型"回合（single turn 内调用数十个工具）导致的上下文溢出问题。

#### 13. postIndexSync

压缩后会话记忆索引同步模式。

```typescript
export type AgentCompactionPostIndexSyncMode = "off" | "async" | "await";
```

- **`"off"`**：不执行索引同步。
- **`"async"`**：异步执行索引同步，不阻塞 Agent 继续工作。压缩完成后立即恢复响应，索引同步在后台进行。
- **`"await"`**：等待索引同步完成后再继续。确保记忆系统完全更新，但会引入延迟。

#### 14-20. memoryFlush（记忆刷新）

```typescript
export type AgentCompactionMemoryFlushConfig = {
    enabled?: boolean;               // 14: 是否启用压缩前记忆刷新（默认 true）
    model?: string;                  // 15: 用于记忆刷新的模型覆盖
    softThresholdTokens?: number;    // 16: 软阈值
    forceFlushTranscriptBytes?: number | string; // 17: 强制刷新阈值
    prompt?: string;                 // 18: 记忆刷新的用户提示
    systemPrompt?: string;           // 19: 记忆刷新的系统提示
};
```

记忆刷新是压缩前的一个重要步骤：在压缩历史之前，系统会先运行一个专门的"记忆刷新"回合，让 Agent 将当前对话中的关键信息写入长期记忆（通过 `memory_set` 工具）。这样即使对话历史被压缩，重要信息也不会丢失。

- **enabled**：是否启用记忆刷新。默认为 `true`。
- **model**：用于记忆刷新的模型覆盖。有时用一个更便宜/更快的模型执行记忆刷新就足够了。
- **softThresholdTokens**：当上下文距离压缩阈值在此 token 数以内时触发记忆刷新。
- **forceFlushTranscriptBytes**：当活跃会话 JSONL 文件达到此字节数时强制触发记忆刷新。
- **prompt**：记忆刷新回合使用的用户提示。如果未设置且未提供 prompt，系统会强制执行 NO_REPLY（不产生用户可见的输出）。
- **systemPrompt**：记忆刷新回合附加的系统提示。

#### 21. postCompactionSections

压缩后从 `AGENTS.md` 注入到上下文中的 H2/H3 章节名称列表。默认值为 `["Session Startup", "Red Lines"]`。设为 `[]` 可完全禁用压缩后上下文注入。

这在压缩后是至关重要的——Agent 在压缩后"醒来"时，需要被提醒关键规则（如"Red Lines"红线性约束）和启动行为（如"Session Startup"会话启动指南），以确保行为的一致性。

#### 22. model

压缩摘要使用的模型覆盖。可以指定一个专门用于压缩的模型。例如：

```yaml
model: "openrouter/anthropic/claude-sonnet-4-6"
```

使用独立的压缩模型有几个好处：
- 可以使用更便宜的模型执行压缩以降低成本
- 可以使用专门擅长摘要的模型
- 压缩操作不会消耗主模型的速率限制配额

未设置时，回退到 Agent 的主模型。

#### 23. timeoutSeconds

单个压缩操作的最大时间（秒），默认 900 秒（15 分钟）。压缩是一个昂贵的操作——对于非常长的对话，压缩摘要可能需要多次 API 调用（分阶段摘要），每次调用可能需要数十秒。此超时防止压缩无限期运行。

#### 24. provider

注册的压缩提供者插件 ID。当设置后，系统会调用该插件的 `summarize()` 方法，而不是内置的 `summarizeInStages()`。如果插件调用失败，系统会回退到内置方法。

这允许高级用户使用自定义的压缩算法或外部压缩服务。

#### 25. truncateAfterCompaction

压缩后是否轮换活跃会话 JSONL 文件。默认为 `false`。

当设为 `true` 时，压缩成功后系统会将旧对话历史存档，下一个回合从压缩摘要和未被总结的尾部开始，使用一个新的（更小的）JSONL 文件。这有助于控制活跃会话文件的大小。

#### 26. maxActiveTranscriptBytes

活跃会话 JSONL 文件达到此字节数时触发压缩的阈值。支持纯数字（字节）或字节大小字符串（如 `"20mb"`）。设为 0 或不设置则禁用此触发条件。

需要配合 `truncateAfterCompaction` 使用，因为压缩后的轮换才能减小后继会话文件的大小。

#### 27. notifyUser

是否在压缩开始和完成时向用户发送简短通知。默认为 `false`（静默压缩）。

#### 为什么 Compaction 只能全局配置

Compaction 配置是全局统一管理的核心原因如下：

1. **行为一致性（Behavioral Consistency）**：压缩直接影响 LLM 看到什么、记住什么。如果不同 Agent 有不同的压缩策略，同一个会话中切换 Agent 时可能出现"记忆断层"——Agent A 压缩了大量历史但 Agent B 期望看到完整历史。

2. **成本可预测性（Cost Predictability）**：压缩操作本身消耗 token（生成摘要需要 API 调用），质量审计和重试机制进一步增加了 token 消耗。将压缩统一管理使得成本可预测。

3. **会话级别的操作（Session-Level Operation）**：压缩是作用于整个会话历史的操作，而非单个 Agent 的行为。Agent 使用的模型只是被压缩的上下文的消费者，压缩策略应独立于 Agent。

4. **废弃的 Agent 级别配置**：如类型定义所示，`AgentConfig` 中的 `compaction` 字段已被标记为 `@deprecated`，仅用于配置迁移/修复场景。这进一步确认了压缩配置应全局管理的设计意图。

### 1.1.5 Subagent 全局默认值

```typescript
subagents?: {
    delegationMode?: SubagentDelegationMode;
    allowAgents?: string[];
    maxConcurrent?: number;
    maxSpawnDepth?: number;
    maxChildrenPerAgent?: number;
    archiveAfterMinutes?: number;
    model?: AgentModelConfig;
    thinking?: string;
    runTimeoutSeconds?: number;
    announceTimeoutMs?: number;
    requireAgentId?: boolean;
};
```

#### 全局级别专属字段

虽然 `AgentConfig` 中也存在 `subagents` 字段，但它只包含四个字段：`delegationMode`、`allowAgents`、`model`、`requireAgentId`。以下字段只能全局配置：

**maxConcurrent**：子 Agent 的最大并发运行数（全局通道："subagent"）。默认 1。这意味着在所有会话中，同一时间只能有 1 个子 Agent 在运行。这是全局性的并发控制，防止系统被大量并发子 Agent 压垮。

**maxSpawnDepth**：`sessions_spawn` 链的最大深度。默认 1，意味着子 Agent 不能再派生子 Agent（不允许嵌套派发）。增加此值可以支持更复杂的任务分解，但增加了失控风险。

**maxChildrenPerAgent**：单个请求会话可派发的最大活跃子 Agent 数。默认 5。防止单个 Agent 无限制地创建子 Agent。

**archiveAfterMinutes**：子 Agent 会话自动归档时间（分钟）。默认 60 分钟。子 Agent 完成工作后，其会话会在指定时间后自动归档以释放资源。设为 0 可禁用自动归档。

**thinking**：派发子 Agent 的默认思考等级。

**runTimeoutSeconds**：派发子 Agent 的默认运行超时时间（秒）。0 表示无超时。

**announceTimeoutMs**：子 Agent 启动通知交付调用的网关超时时间（毫秒）。默认 120000（2 分钟）。

#### 为什么这些字段只能全局配置

1. **安全边界与资源防护**：`maxConcurrent`、`maxSpawnDepth`、`maxChildrenPerAgent` 是核心安全参数。它们防止以下攻击和滥用场景：
   - **递归派发攻击**：恶意或错误配置的 Agent 通过 `sessions_spawn` 创建无限深度的子 Agent 链，耗尽系统资源。
   - **资源耗尽**：单个 Agent 创建数百个子 Agent，占满 CPU 和内存。
   - **并发爆炸**：大量会话同时派发子 Agent 导致系统过载。

2. **基础设施级别的资源管理**：子 Agent 派发涉及进程创建、会话管理、内存分配等基础设施操作。这些资源的限制应由系统管理员统一控制，而非由各个 Agent 自行决定。

3. **全局性的服务质量（QoS）保证**：`maxConcurrent` 是全局通道级别限制，确保子 Agent 系统不会影响主 Agent 的响应质量。

### 1.1.6 全局并发控制：maxConcurrent

```typescript
/** Max concurrent agent runs across all conversations. Default: 1 (sequential). */
maxConcurrent?: number;
```

此字段位于 `AgentDefaultsConfig` 的顶层，控制跨所有会话的全局 Agent 最大并发数。默认为 1，意味着所有会话串行执行。

这适用于**除了子 Agent** 之外的所有 Agent 运行。子 Agent 有自己独立的并发通道（`subagents.maxConcurrent`）。

---

## 1.2 模型体系：九种 API 类型详解

OpenClaw 支持通过统一的配置接口对接多种模型提供商和 API 协议。系统定义了九种模型 API 类型，每种类型对应一类提供商或协议。

```typescript
export declare const MODEL_APIS: readonly [
    "openai-completions",
    "openai-responses",
    "openai-codex-responses",
    "anthropic-messages",
    "google-generative-ai",
    "github-copilot",
    "bedrock-converse-stream",
    "ollama",
    "azure-openai-responses"
];

export type ModelApi = (typeof MODEL_APIS)[number];
```

### 1.2.1 "openai-completions" — OpenAI Chat Completions API

最广泛使用的模型 API 类型。基于 OpenAI 的 `/v1/chat/completions` 端点。

**适用的提供商和服务**：
- OpenAI（GPT-4o、GPT-4.1、GPT-5 等）
- 任何兼容 OpenAI Chat Completions 格式的第三方服务（OpenRouter、Together AI、Groq、DeepSeek、GLM 等）
- 本地部署的兼容 OpenAI API 格式的服务（如 vLLM、text-generation-webui）

**特点**：
- 这是兼容性最广的 API 类型
- 支持流式（SSE）和非流式响应
- 使用标准的 messages 数组格式（system/user/assistant/tool 角色）
- 支持工具调用（function calling）
- 通过 `ModelCompatConfig` 中的细粒度字段处理不同提供商的兼容性差异

### 1.2.2 "openai-responses" — OpenAI Responses API

OpenAI 的新一代 API，即 Responses API。

**适用的提供商和服务**：
- OpenAI（新版本 API 端点）
- Azure OpenAI（通过 `azure-openai-responses` 单独配置）

**特点**：
- 是 OpenAI 推荐的新应用使用的 API
- 使用不同的请求/响应格式（与 Completions API 不兼容）
- 内置了更好的工具支持（web_search、file_search 等）
- 支持更丰富的多模态输入方式
- `ModelCompatConfig` 中有专门的兼容性字段：`sendSessionIdHeader`、`supportsLongCacheRetention`

### 1.2.3 "openai-codex-responses" — OpenAI Codex Responses API

专为 OpenAI Codex CLI 设计的 Responses API 变体。

**适用的提供商和服务**：
- OpenAI Codex CLI

**特点**：
- 与标准 Responses API 类似但针对 Codex 运行时进行了优化
- 支持 Codex CLI 特有的会话管理、推理格式等
- 在 `ModelCompatConfig` 中共享 `openai-responses` 的兼容性字段

### 1.2.4 "anthropic-messages" — Anthropic Messages API

Anthropic 的 Messages API（`/v1/messages` 端点）。

**适用的提供商和服务**：
- Anthropic（Claude Opus 4.x、Claude Sonnet 4.x、Claude Haiku 4.x 等）
- Amazon Bedrock 上的 Claude 模型（通过 `bedrock-converse-stream`）
- 部分兼容 Anthropic 格式的第三方服务

**特点**：
- Anthropic 原生 API
- 支持扩展思考（Extended Thinking），在响应中返回 `thinking` 内容块
- 支持工具调用
- 兼容性字段包括：`supportsEagerToolInputStreaming`（立即工具输入流）、`supportsLongCacheRetention`
- 流式响应格式：Server-Sent Events（SSE）

### 1.2.5 "google-generative-ai" — Google Generative AI API

Google 的 Gemini API。

**适用的提供商和服务**：
- Google AI（Gemini 2.5 Flash、Gemini 2.5 Pro 等）
- Vertex AI（Google Cloud 上的企业级 Gemini 服务）

**特点**：
- Google 原生 API 格式
- 超长上下文窗口（Gemini 2.5 Pro 支持 100 万 token）
- 原生多模态支持（文本、图像、视频、音频）
- 支持 Google Search grounding（搜索增强）
- 特有的安全过滤配置

### 1.2.6 "github-copilot" — GitHub Copilot API

GitHub Copilot 的模型 API。

**适用的提供商和服务**：
- GitHub Copilot（通过 GitHub 的 Copilot 授权）
- 支持 Copilot 代理的各种模型（GPT-4o、Claude Sonnet 等）

**特点**：
- 通过 GitHub 的认证体系访问多种模型
- API 格式基于 OpenAI Completions 但有特定差异
- 需要 GitHub Copilot 订阅和 token 配置
- 支持模型发现（Discovery）：`copilotDiscovery` 可动态发现当前可用的 Copilot 模型

### 1.2.7 "bedrock-converse-stream" — Amazon Bedrock Converse Stream API

Amazon Bedrock 的 Converse API（流式模式）。

**适用的提供商和服务**：
- Amazon Bedrock 上的各种模型（Claude、Llama、Mistral、Command R 等）

**特点**：
- 通过 AWS SDK 认证（`auth: "aws-sdk"`），无需直接管理 API key
- 支持 Bedrock 上的流式对话
- 通过统一的 Converse API 访问不同提供商的模型
- 支持模型发现（`bedrockDiscovery`）：可配置区域、提供商过滤器、刷新间隔

### 1.2.8 "ollama" — Ollama 本地模型服务

Ollama 本地 LLM 运行时的 API。

**适用的提供商和服务**：
- 本地 Ollama 实例
- 通过 Ollama 运行的任何模型（Llama、Mistral、Gemma、Qwen、DeepSeek 等）

**特点**：
- 完全本地运行，无需网络连接（隐私保护）
- 支持模型发现（`ollamaDiscovery`）
- 默认关闭流式传输以避免已知的 SDK 问题（issue #1205）
- 通常用于不需要强力云端模型的场景（脚本执行、简单分类、格式转换等）
- 无需 API key（`auth` 可选）

### 1.2.9 "azure-openai-responses" — Azure OpenAI Responses API

Microsoft Azure 上的 OpenAI Responses API。

**适用的提供商和服务**：
- Azure OpenAI Service

**特点**：
- 通过 Azure 的企业级基础设施访问 OpenAI 模型
- 享有 Azure 的 SLA、合规性、数据驻留等企业功能
- API 格式与 `openai-responses` 类似但 endpoint 和认证方式不同
- 使用 Azure 的认证体系而非 OpenAI 的直接 API key

### 1.2.10 模型提供商认证模式

与九种 API 类型配合使用的认证模式：

```typescript
export type ModelProviderAuthMode = "api-key" | "aws-sdk" | "oauth" | "token";
```

- **`"api-key"`**：标准 API Key 认证。用于 OpenAI、Anthropic、Google AI 等。API Key 通过 `apiKey` 字段配置，支持 `SecretInput` 类型（明文、环境变量引用或命令行获取）。
- **`"aws-sdk"`**：AWS SDK 认证。用于 Amazon Bedrock。系统通过本地 AWS 凭证（环境变量、`~/.aws/credentials`、IAM 角色等）进行认证。
- **`"oauth"`**：OAuth 2.0 认证流程。用于需要用户授权的服务。
- **`"token"`**：简单 Token 认证。用于 GitHub Copilot 等服务。

### 1.2.11 ModelDefinitionConfig：模型定义

每个具体的模型都通过 `ModelDefinitionConfig` 来定义：

```typescript
export type ModelDefinitionConfig = {
    id: string;                          // 模型 ID（如 "gpt-5", "claude-sonnet-4-6"）
    name: string;                        // 显示名称
    api?: ModelApi;                      // 使用的 API 类型
    baseUrl?: string;                    // 可选的自定义 Base URL
    reasoning: boolean;                  // 是否支持推理/思考
    input: Array<"text" | "image" | "video" | "audio">;  // 支持的输入类型
    cost: {                              // 定价信息（美元/百万 token）
        input: number;                   // 输入价格
        output: number;                  // 输出价格
        cacheRead: number;               // 缓存读取价格
        cacheWrite: number;              // 缓存写入价格
        tieredPricing?: Array<{          // 可选的阶梯定价
            input: number;
            output: number;
            cacheRead: number;
            cacheWrite: number;
            range: [number, number] | [number];  // 价格区间 [start, end)
        }>;
    };
    contextWindow: number;              // 上下文窗口大小（token）
    contextTokens?: number;             // 可选的有效运行时上限（用于压缩/会话预算）
    maxTokens: number;                  // 最大输出 token 数
    params?: Record<string, unknown>;   // 传递给提供商插件的额外参数
    agentRuntime?: AgentRuntimePolicyConfig;  // 可选的运行时覆盖
    headers?: Record<string, string>;   // 额外的 HTTP 请求头
    compat?: ModelCompatConfig;         // 兼容性配置
    metadataSource?: "models-add";      // 元数据来源标记
};
```

**关键字段说明**：

**id**：模型的唯一标识符。在 OpenClaw 的配置中，模型通过 `provider/modelId` 格式引用，例如 `openai/gpt-5`、`anthropic/claude-sonnet-4-6`。此处的 `id` 是 `modelId` 部分。

**reasoning**：标记模型是否支持推理/思考功能。如果为 `true`，Agent 可以要求模型展示其推理过程（思考链），这对于复杂问题求解非常有用。支持推理的模型在响应格式中包含专门的思考内容块。

**input**：模型支持的输入模态。这是影响模型选择的关键字段：
- `"text"`：纯文本输入（几乎所有模型都支持）
- `"image"`：图像输入（GPT-4o、Claude 3.5+、Gemini 等）
- `"video"`：视频输入（Gemini 2.5 系列等）
- `"audio"`：音频输入（GPT-4o-audio 等）

**cost**：定价信息用于成本估算和预算管理。支持基础的统一费率和更精细的阶梯定价。阶梯定价允许为不同 token 使用量区间设置不同的价格，反映许多提供商的实际定价模式。

**contextWindow**：提供商声明的上下文窗口大小。例如 GPT-4o 为 128K，Claude Sonnet 4 为 200K，Gemini 2.5 Pro 为 1M。

**contextTokens**：可选的有效运行时上限。有时提供商声明的 contextWindow 在实际情况中不可完全使用（例如部分被系统提示占据），或者出于成本考虑希望使用更小的窗口。此字段允许在不修改 contextWindow 元数据的情况下设置一个更小的实用窗口，用于压缩和会话预算计算。

**maxTokens**：单次请求的最大输出 token 数。

**compat**：兼容性配置，详见下一节。

### 1.2.12 ModelCompatConfig：23 个兼容性设置字段

```typescript
export type ModelCompatConfig = {
    // OpenAI Completions Compat 字段
    supportsStore?: boolean;
    supportsDeveloperRole?: boolean;
    supportsReasoningEffort?: boolean;
    supportsUsageInStreaming?: boolean;
    supportsStrictMode?: boolean;
    maxTokensField?: string;
    requiresToolResultName?: boolean;
    requiresAssistantAfterToolResult?: boolean;
    requiresThinkingAsText?: boolean;
    openRouterRouting?: string;
    vercelGatewayRouting?: string;
    zaiToolStream?: string;
    cacheControlFormat?: string;
    sendSessionAffinityHeaders?: boolean;
    supportsLongCacheRetention?: boolean;
    // OpenAI Responses Compat 字段
    sendSessionIdHeader?: boolean;
    // Anthropic Messages Compat 字段
    supportsEagerToolInputStreaming?: boolean;
    // 通用字段
    thinkingFormat?: SupportedThinkingFormat;
    supportedReasoningEfforts?: string[];
    reasoningEffortMap?: Record<string, string>;
    visibleReasoningDetailTypes?: string[];
    supportsTools?: boolean;
    supportsPromptCacheKey?: boolean;
    requiresStringContent?: boolean;
    strictMessageKeys?: boolean;
    toolSchemaProfile?: string;
    unsupportedToolSchemaKeywords?: string[];
    nativeWebSearchTool?: boolean;
    toolCallArgumentsEncoding?: string;
    requiresMistralToolIds?: boolean;
    requiresOpenAiAnthropicToolPayload?: boolean;
};
```

`ModelCompatConfig` 是 OpenClaw 中处理不同模型提供商之间细微差异的核心机制。以下是各个字段的详细说明：

#### OpenAI Completions Compat 相关字段（14 个）

1. **supportsStore**：模型是否支持 `store` 参数。OpenAI 的某些模型允许将完成结果存储到云端以供后续检索。

2. **supportsDeveloperRole**：是否支持 `developer` 角色消息。OpenAI 在 o1 系列模型中引入了 `developer` 消息角色，替代了 `system` 角色。

3. **supportsReasoningEffort**：是否支持 `reasoning_effort` 参数。OpenAI o 系列模型允许通过此参数控制推理深度。

4. **supportsUsageInStreaming**：流式响应中是否包含 token 使用量信息。某些提供商在流式模式下不会返回 usage 数据。

5. **supportsStrictMode**：是否支持 JSON Schema 的 `strict` 模式。在此模式下，模型保证输出完全符合 JSON Schema 约束。

6. **maxTokensField**：用于指定最大输出 token 的字段名。不同提供商使用不同的字段名：OpenAI 用 `max_completion_tokens`，其他一些用 `max_tokens`。

7. **requiresToolResultName**：工具调用结果是否必须包含 `name` 字段。Mistral 等提供商对此有额外要求。

8. **requiresAssistantAfterToolResult**：在工具结果后是否需要插入一个空的 assistant 消息。某些模型的消息格式有此要求。

9. **requiresThinkingAsText**：是否要求将思考内容作为文本返回。某些模型（如 DeepSeek R1）将推理内容嵌入到普通文本中而非使用专门的思考块。

10. **openRouterRouting**：OpenRouter 的路由配置。OpenRouter 允许通过特定参数控制请求被路由到哪个底层提供商。

11. **vercelGatewayRouting**：Vercel AI Gateway 的路由配置。类似于 OpenRouter，但针对 Vercel 的网关。

12. **zaiToolStream**：Z.AI 工具流格式配置。某些提供商在流式工具调用时有特殊的格式要求。

13. **cacheControlFormat**：缓存控制格式。不同提供商使用不同的方式标记可缓存内容（例如 Anthropic 使用 `cache_control` 属性，OpenAI 使用不同的机制）。

14. **sendSessionAffinityHeaders**：是否发送会话亲和性请求头，确保请求被路由到同一后端。

#### 通用兼容性字段（9 个）

15. **thinkingFormat**：思考格式配置。不同提供商对推理/思考内容的格式要求各异：
    - `"deepseek"`：DeepSeek 的思考格式
    - `"openrouter"`：OpenRouter 的思考格式
    - `"together"`：Together AI 的思考格式

16. **supportedReasoningEfforts**：支持的推理力度级别列表。例如 `["low", "medium", "high"]`。

17. **reasoningEffortMap**：推理力度映射表。将 OpenClaw 内部的推理力度值映射到特定提供商期望的值。

18. **visibleReasoningDetailTypes**：可见推理详情类型。控制哪些推理内容对用户可见。

19. **supportsTools**：模型是否支持工具调用。少数模型（尤其是一些本地/小型模型）不支持工具调用功能。

20. **supportsPromptCacheKey**：是否支持提示缓存键。提示缓存可以显著降低成本和延迟，但不是所有提供商都支持。

21. **requiresStringContent**：内容是否必须是字符串类型。某些模型不支持结构化的 content 数组，只接受纯字符串。

22. **strictMessageKeys**：是否严格检查消息对象中的键。某些提供商对额外/未知的键敏感。

23. **toolSchemaProfile**：工具 Schema 配置文件。不同模型对 JSON Schema 的支持程度不同。例如 `"openai"`、`"anthropic"`、`"google"` 等不同的 profile 代表不同的 schema 限制。

24. **unsupportedToolSchemaKeywords**：不支持的工具 Schema 关键词列表。某些模型不支持 JSON Schema 中的特定关键词（如 `$defs`、`oneOf`），需要从工具定义中移除这些关键词。

25. **nativeWebSearchTool**：模型是否原生支持网络搜索工具。OpenAI Responses API 和 Google Gemini 具有内置的搜索能力。

26. **toolCallArgumentsEncoding**：工具调用参数的编码方式。某些提供商对参数有特殊的编码要求。

27. **requiresMistralToolIds**：是否需要 Mistral 风格的工具 ID。Mistral 模型在工具调用时需要特定格式的 tool ID。

28. **requiresOpenAiAnthropicToolPayload**：是否需要 OpenAI/Anthropic 风格的工具负载格式。

#### supportsLongCacheRetention

此字段在三个 Compat 分组中都有出现（OpenAI Completions、OpenAI Responses、Anthropic Messages），表示模型是否支持长时间缓存保留。这是提示缓存的重要特性——某些提供商只保留缓存几分钟，而支持长保留的提供商可以保留数小时甚至数天。

---

## 1.3 Agent 模型选择优先级链

OpenClaw 中的模型选择遵循严格的优先级链。理解这一链条对于正确配置 Agent 行为至关重要。

### 1.3.1 主模型选择优先级

```
AgentConfig.model  →  AgentDefaultsConfig.model  →  fallbacks[0]  →  fallbacks[1]  →  ...
```

优先级从高到低：

1. **`AgentConfig.model`（Agent 级别）**：在 `agents.list[]` 中为特定 Agent 配置的模型。这是最高优先级，因为它是针对特定 Agent 的最精确配置。

2. **`AgentDefaultsConfig.model`（全局默认级别）**：在 `agents.defaults` 中配置的全局默认模型。当 Agent 没有自己的 `model` 时使用。

3. **`fallbacks[0]`、`fallbacks[1]`...（回退链）**：在 `primary` 模型不可用时（例如 API 速率限制、服务中断、上下文超出等），系统按序尝试 `fallbacks` 列表中的模型。

`AgentModelConfig` 的完整类型：

```typescript
export type AgentModelConfig = string | {
    primary?: string;       // 主模型 (provider/model)
    fallbacks?: string[];   // 回退模型列表
};
```

**实际选择流程**：

假设有以下配置：

```yaml
agents:
  defaults:
    model:
      primary: "openai/gpt-5"
      fallbacks:
        - "anthropic/claude-sonnet-4-6"
        - "google/gemini-2.5-pro"
  list:
    - id: "coder"
      model:
        primary: "openai/gpt-5-codex"
        fallbacks:
          - "anthropic/claude-sonnet-4-6-codex"
```

当 `coder` Agent 运行时：
1. 首先尝试 `"openai/gpt-5-codex"`
2. 如果不可用，尝试 `"anthropic/claude-sonnet-4-6-codex"`
3. 如果仍不可用，**不会**回退到全局的 fallbacks（`"google/gemini-2.5-pro"`），因为 Agent 级别已定义了完整的 fallback 链

如果 Agent 没有配置 `model`：
1. 使用全局 `"openai/gpt-5"`
2. 如果不可用，尝试 `"anthropic/claude-sonnet-4-6"`
3. 如果不可用，尝试 `"google/gemini-2.5-pro"`

### 1.3.2 per-model 细粒度配置：AgentConfig.models

```typescript
export type AgentModelEntryConfig = {
    alias?: string;
    params?: Record<string, unknown>;
    agentRuntime?: AgentRuntimePolicyConfig;
    streaming?: boolean;
};
```

`AgentConfig.models`（类型为 `Record<string, AgentModelEntryConfig>`）提供了对每个具体模型的细粒度配置。这是一种"元数据覆盖"机制——它不改变模型选择优先级，而是为已选择的模型附加额外行为。

**alias**：为模型设置别名。在 Agent 的上下文中，可以使用别名代替完整的 `provider/model` 标识符。

**params**：提供商特有的 API 参数。例如，为 GLM-4.7 模型设置思考模式参数：

```yaml
models:
  "zhipu/glm-4.7":
    params:
      thinking:
        type: "enabled"
```

这些参数在每次 API 调用时传递给提供商插件。

**agentRuntime**：为该特定的提供商/模型对设置执行运行时覆盖。这允许为不同模型使用不同的运行时后端。

**streaming**：控制该模型是否启用流式传输。默认大多数模型启用流式传输（`true`），但 Ollama 模型默认关闭流式传输（`false`）以避免已知的 SDK 问题 #1205。

`AgentConfig.models` 在 `AgentConfig` 和 `AgentDefaultsConfig` 中都存在。它们的合并遵循标准的配置继承规则：

- Agent 级别的 `models` 条目与全局 `models` 条目的同名键进行深度合并
- Agent 级别可以覆盖全局级别中同模型条目的字段

### 1.3.3 Subagent 模型选择

子 Agent（通过 `sessions_spawn` 工具创建）的模型选择有独立的优先级链：

```
AgentConfig.subagents.model  →  AgentConfig.model  →  AgentDefaultsConfig.subagents.model  →  AgentDefaultsConfig.model
```

优先级从高到低：

1. **Agent 级别的 `subagents.model`**：最高优先级。在特定 Agent 的配置中为子 Agent 指定的模型。

2. **Agent 级别的 `model`**：如果 Agent 没有为子 Agent 单独指定模型，则回退到 Agent 自身使用的模型。

3. **全局级别的 `subagents.model`**：如果 Agent 没有配置模型，则使用全局默认的子 Agent 专用模型。

4. **全局级别的 `model`**：最后回退到全局默认模型。

这种设计允许以下场景：

- **大多数情况**：子 Agent 使用与主 Agent 相同的模型（回退链第 2 步），无需额外配置。
- **成本优化**：为子 Agent 配置更便宜/更快的模型（回退链第 1 步），因为子 Agent 通常执行更简单的任务。
- **专业化**：为特定类型的 Agent 配置专用的子 Agent 模型。

### 1.3.4 工具模型的独立选择

除了主对话模型，特定工具还可以使用独立配置的模型：

- **图像理解**：`imageModel`（在 `AgentDefaultsConfig` 中全局配置）
- **图像生成**：`imageGenerationModel`
- **视频生成**：`videoGenerationModel`
- **音乐生成**：`musicGenerationModel`
- **PDF 处理**：`pdfModel`

这些工具模型的回退机制与主模型类似（primary → fallbacks[0] → fallbacks[1]），但有两个关键区别：

1. 它们属于 `AgentToolModelConfig` 类型，多了 `timeoutMs` 字段
2. 如果启用了 `mediaGenerationAutoProviderFallback`（默认 `true`），生成类工具模型的回退链末尾会自动追加其他已认证提供商的默认模型，提供额外的跨提供商回退

---

## 1.4 路由绑定（Route Bindings）

路由绑定是 OpenClaw 中决定"哪个 Agent 处理哪条消息"的核心机制。在多渠道、多 Agent 的部署场景中，路由绑定定义了消息分发的规则。

### 1.4.1 AgentRouteBinding：标准路由绑定

```typescript
export type AgentRouteBinding = {
    /** Missing type is interpreted as route for backward compatibility. */
    type?: "route";
    agentId: string;
    comment?: string;
    match: AgentBindingMatch;
    session?: {
        dmScope?: DmScope;
    };
};
```

路由绑定的核心是 **match（匹配条件）**——当一条消息的元数据与 match 条件匹配时，该消息被路由到 `agentId` 指定的 Agent 处理。

**省略 type 的向后兼容**：如果 `type` 字段缺失或未设置，系统将其解释为 `"route"` 类型。这是为了向后兼容旧版本配置。

**comment**：可选的人类可读注释，用于文档化绑定的目的。

**session.dmScope**：可选的会话 DM 范围覆盖。允许为匹配此绑定的对话设置私信（Direct Message）范围规则。

#### AgentBindingMatch：匹配条件

```typescript
export type AgentBindingMatch = {
    channel: string;         // 必需：渠道标识符
    accountId?: string;      // 可选：多账户渠道的特定账户
    peer?: {
        kind: ChatType;      // 对话类型
        id: string;          // 对话 ID（用户 ID、群组 ID 等）
    };
    guildId?: string;        // Discord Guild/Server ID
    teamId?: string;         // Slack/Teams 的团队 ID
    roles?: string[];        // Discord 角色 ID 列表
};
```

**channel（必需）**：渠道标识符。这是最重要的匹配条件——它指定了消息来自哪个渠道（如 `"discord"`、`"telegram"`、`"whatsapp"`、`"slack"`、`"cli"` 等）。

**accountId**：多账户渠道的特定账户 ID。某些渠道允许配置多个账户（例如多个 WhatsApp 号码、多个 Telegram Bot）。此字段用于区分同一渠道下的不同账户。

**peer.kind**：对话类型。`ChatType` 指定了对话的类型，如：
- `"dm"`：私信（Direct Message）
- `"group"`：群聊
- `"channel"`：频道（如 Telegram Channel）
- `"guild"`：公会（如 Discord Server）

**peer.id**：对话 ID。具体的用户 ID、群组 ID、频道 ID 等。这是最高精度的匹配条件——可以精确到"某个用户发来的消息由某个 Agent 处理"。

**guildId**：Discord Guild（服务器）ID。仅在 Discord 渠道中使用，用于将绑定限定到特定 Discord 服务器。

**teamId**：团队 ID。在 Slack 或 Microsoft Teams 等企业通信平台中使用，用于将绑定限定到特定团队。

**roles**：Discord 角色 ID 列表。用于基于角色的路由——具有特定 Discord 角色的用户的消息可以被路由到特定的 Agent。这允许例如"管理员角色使用更强大的 Agent"、"普通成员使用基础 Agent"的场景。

#### 匹配规则

绑定的匹配遵循**所有条件必须同时满足**的原则（AND 逻辑）：

1. `channel` 必须匹配（这是强制性的）
2. 如果指定了 `accountId`，必须匹配
3. 如果指定了 `peer`，`peer.kind` 和 `peer.id` 都必须匹配
4. 如果指定了 `guildId`，必须匹配
5. 如果指定了 `teamId`，必须匹配
6. 如果指定了 `roles`，消息发送者必须拥有至少一个指定的角色

只有所有指定条件都满足时，消息才会被路由到绑定的 Agent。

#### 路由优先级

当多个绑定同时匹配一条消息时，系统使用**最具体匹配优先**（Most Specific Match Wins）原则：

1. 具有更多匹配条件的绑定优先（例如同时指定了 `channel` 和 `peer` 的绑定优先于仅指定 `channel` 的绑定）
2. 如果具体度相同，按配置文件中绑定的声明顺序（先声明的优先）

### 1.4.2 AgentAcpBinding：ACP 绑定

```typescript
export type AgentAcpBinding = {
    type: "acp";
    agentId: string;
    comment?: string;
    match: AgentBindingMatch;
    acp?: {
        mode?: "persistent" | "oneshot";
        label?: string;
        cwd?: string;
        backend?: string;
    };
};
```

ACP（Agent Communication Protocol）绑定是一种特殊的路由绑定，用于将消息路由到通过 ACP 协议的 Agent。

**与标准路由绑定的区别**：
- `type` 必须显式设为 `"acp"`
- 多了 `acp` 配置块，定义了 ACP 会话的具体行为

**acp.mode**：
- `"persistent"`：持久会话模式。ACP Agent 保持一个长连接，消息在同一个会话中持续交流。
- `"oneshot"`：一次性模式。每条消息创建新的 ACP 会话，处理完即结束。

**acp.label**：ACP 会话的人类可读标签。用于在 UI 和日志中标识不同的 ACP 会话。

**acp.cwd**：ACP Agent 的工作目录。覆盖 Agent 配置中默认的工作目录。

**acp.backend**：ACP 后端适配器 ID（例如 `"codex"`、`"claude"`）。指定使用哪个 ACP 后端来处理消息。

#### ACP 与 AgentRuntimeConfig 的关系

`AgentConfig` 中也有 `runtime` 字段：

```typescript
export type AgentRuntimeConfig =
    { type: "embedded" } |
    { type: "acp"; acp?: AgentRuntimeAcpConfig };

export type AgentRuntimeAcpConfig = {
    agent?: string;      // ACP 适配器 id（如 "codex", "claude"）
    backend?: string;    // ACP 后端覆盖
    mode?: "persistent" | "oneshot";
    cwd?: string;        // 运行时工作目录覆盖
};
```

`AgentAcpBinding.acp` 与 `AgentRuntimeAcpConfig` 的区别：

- **AgentRuntimeConfig** 定义 Agent 的运行时引擎（在 Agent 级别配置），对所有通过该 Agent 处理的绑定都生效。
- **AgentAcpBinding.acp** 是为特定绑定设置的 ACP 会话参数（在绑定级别配置），仅对该绑定生效。
- 绑定级别的 `acp` 覆盖 Agent 级别的 `runtime.acp` 设置。

### 1.4.3 联合类型：AgentBinding

```typescript
export type AgentBinding = AgentRouteBinding | AgentAcpBinding;
```

系统在处理绑定时，根据 `type` 字段区分标准路由绑定和 ACP 绑定。

### 1.4.4 实际路由场景示例

#### 场景 1：多渠道单 Agent（最简单）

```yaml
agents:
  defaults:
    model: "openai/gpt-5"
  list:
    - id: "assistant"
      default: true
bindings:
  - agentId: "assistant"
    match:
      channel: "discord"
  - agentId: "assistant"
    match:
      channel: "telegram"
  - agentId: "assistant"
    match:
      channel: "cli"
```

所有渠道的消息都由 `assistant` Agent 处理。`default: true` 标记表示这是默认 Agent。

#### 场景 2：基于角色的 Discord 路由

```yaml
agents:
  list:
    - id: "admin-agent"
      model: "anthropic/claude-opus-4-6"
    - id: "member-agent"
      model: "openai/gpt-5-nano"
bindings:
  - agentId: "admin-agent"
    match:
      channel: "discord"
      guildId: "123456789"
      roles: ["admin-role-id", "moderator-role-id"]
  - agentId: "member-agent"
    match:
      channel: "discord"
      guildId: "123456789"
```

拥有管理员或版主角色的用户消息由功能更强的 `admin-agent` 处理，其他用户的消息由 `member-agent` 处理。

#### 场景 3：基于对话 ID 的精确路由

```yaml
agents:
  list:
    - id: "vip-agent"
      model: "anthropic/claude-opus-4-6"
    - id: "general-agent"
      model: "openai/gpt-5"
bindings:
  - agentId: "vip-agent"
    match:
      channel: "telegram"
      peer:
        kind: "dm"
        id: "123456789"    # VIP 用户的 Telegram ID
  - agentId: "general-agent"
    match:
      channel: "telegram"
```

特定的 VIP 用户的私信由专用 Agent 处理，其他 Telegram 用户的消息由通用 Agent 处理。

#### 场景 4：ACP 绑定

```yaml
bindings:
  - type: "acp"
    agentId: "codex-agent"
    match:
      channel: "cli"
    acp:
      mode: "persistent"
      backend: "codex"
      cwd: "/home/user/projects/myapp"
```

来自 CLI 的消息被路由到通过 Codex ACP 后端运行的持久会话。Agent 的工作目录被设置为特定的项目路径。

### 1.4.5 default Agent 标记

```typescript
export type AgentConfig = {
    id: string;
    default?: boolean;
    // ...
};
```

如果没有任何绑定匹配一条消息，系统将使用标记为 `default: true` 的 Agent。如果多个 Agent 被标记为 default，使用第一个。如果没有 Agent 被标记为 default 且无绑定匹配，消息将无法被处理。

---

## 1.5 配置继承规则

OpenClaw 的配置体系采用分层设计，配置从全局默认值继承到具体的 Agent。理解这些继承规则对于正确配置系统至关重要。

### 规则 1：Agent 级别覆盖全局级别（Override Rule）

这是最基本的继承规则——当 `AgentConfig`（`agents.list[]` 中的元素）中定义了某个字段，它会完全覆盖 `AgentDefaultsConfig`（`agents.defaults`）中的同名字段。

```typescript
// 示例
agents:
  defaults:
    model: "openai/gpt-5"         # 全局默认
    thinkingDefault: "medium"
  list:
    - id: "fast-agent"
      model: "openai/gpt-5-nano"  # 覆盖全局 model
      # thinkingDefault 未设置，继承全局的 "medium"
    - id: "deep-agent"
      thinkingDefault: "high"     # 覆盖全局 thinkingDefault
      # model 未设置，继承全局的 "openai/gpt-5"
```

**注意**：覆盖是"整体替换"而非"深度合并"。对于对象类型的字段，Agent 级别的配置会完全替换全局配置，而非递归合并子字段。

### 规则 2：仅全局级别的字段不可覆盖（Global-Only Rule）

如 1.1 节详述，以下字段**只能**在 `AgentDefaultsConfig` 中配置，在 `AgentConfig` 中不存在对应字段：

- 多模态生成模型：`imageModel`、`imageGenerationModel`、`videoGenerationModel`、`musicGenerationModel`、`mediaGenerationAutoProviderFallback`
- PDF 处理：`pdfModel`、`pdfMaxBytesMb`、`pdfMaxPages`
- 上下文修剪：`contextPruning`（完整配置）
- 启动上下文：`startupContext`（完整配置）
- 全局并发：`maxConcurrent`
- Subagent 安全参数：`subagents.maxConcurrent`、`subagents.maxSpawnDepth`、`subagents.maxChildrenPerAgent`、`subagents.archiveAfterMinutes`、`subagents.thinking`、`subagents.runTimeoutSeconds`、`subagents.announceTimeoutMs`

这些字段的设计意图是**系统级别的策略**，而非 Agent 级别的行为配置。任何 Agent 都不能改变这些设置。

### 规则 3：模型回退链独立完整（Model Chain Independence）

当 Agent 级别配置了包含 `primary` 和 `fallbacks` 的完整模型配置时，这个回退链是**自包含**的——系统不会在 Agent 级别的 fallbacks 用尽后继续使用全局级别的 fallbacks。

```yaml
agents:
  defaults:
    model:
      primary: "openai/gpt-5"
      fallbacks: ["google/gemini-2.5-pro"]  # 全局 fallback
  list:
    - id: "coder"
      model:
        primary: "openai/gpt-5-codex"
        fallbacks: ["openai/gpt-5"]           # Agent fallback
```

当 `coder` Agent 运行时：
1. 尝试 `"openai/gpt-5-codex"`
2. 如果失败，尝试 `"openai/gpt-5"`
3. 如果仍失败，**不会**尝试全局的 `"google/gemini-2.5-pro"`——Agent 的 fallback 链是独立完整的

为了让 Agent 级别的 fallbacks 包含全局 fallbacks，需要显式在 Agent 配置中重新声明。

### 规则 4：`models` 条目的深度合并（Models Deep Merge）

与规则 1 的"整体替换"不同，`AgentConfig.models` 与 `AgentDefaultsConfig.models` 进行**深度合并**：

```yaml
agents:
  defaults:
    models:
      "openai/gpt-5":
        params:
          temperature: 0.7
      "anthropic/claude-sonnet-4-6":
        streaming: true
  list:
    - id: "custom-agent"
      models:
        "openai/gpt-5":
          params:
            temperature: 0.2      # 覆盖全局的 temperature
            top_p: 0.9            # 新增参数
        "google/gemini-2.5-pro":   # 新增模型条目
          streaming: true
```

合并后的结果：
- `"openai/gpt-5"`：`params` 合并为 `{temperature: 0.2, top_p: 0.9}`，Agent 级别覆盖了 temperature 并新增了 top_p
- `"anthropic/claude-sonnet-4-6"`：继承全局的 `{streaming: true}`
- `"google/gemini-2.5-pro"`：从 Agent 级别新增，`{streaming: true}`

这种深度合并允许 Agent 级别对全局定义的模型进行细粒度调整，而无需完全重新定义。

### 规则 5：Subagent 配置的选择性继承（Subagent Selective Inheritance）

Subagent 的模型选择有专用的优先级链（见 1.3.3 节），但 Subagent 的其他配置字段遵循选择性继承：

- **`delegationMode`**：Agent 级别覆盖全局级别
- **`allowAgents`**：Agent 级别覆盖全局级别（完整替换，非合并）
- **`model`**：Agent 级别的 `subagents.model` 覆盖全局级别的 `subagents.model`
- **`requireAgentId`**：Agent 级别覆盖全局级别

而 `maxConcurrent`、`maxSpawnDepth`、`maxChildrenPerAgent`、`archiveAfterMinutes`、`thinking`、`runTimeoutSeconds`、`announceTimeoutMs` 只能全局配置（见规则 2）。

### 规则 6：运行时绑定的级联覆盖（Binding Cascade Override）

当一条消息同时匹配 Agent 级别的运行时配置和绑定级别的 ACP 配置时，绑定级别具有更高优先级：

```
AgentAcpBinding.acp  >  AgentRuntimeAcpConfig  >  AgentDefaultsConfig.agentRuntime
```

这意味着：
1. 绑定级别的 `acp.mode` 覆盖 Agent 级别的 `runtime.acp.mode`
2. 绑定级别的 `acp.backend` 覆盖 Agent 级别的 `runtime.acp.backend`
3. 绑定级别的 `acp.cwd` 覆盖 Agent 级别的 `runtime.acp.cwd`
4. Agent 级别的 `runtime.acp` 覆盖全局级别的 `agentRuntime`

这种设计允许同一个 Agent 在不同的绑定（不同的渠道、不同的用户群）中使用不同的运行时配置，提供最大的灵活性。

---

## 1.6 配置综合示例

以下是一个综合性的配置示例，展示了全局默认值、Agent 定义、模型配置和路由绑定如何协同工作：

```yaml
agents:
  defaults:
    # 全局默认模型及其回退链
    model:
      primary: "openai/gpt-5"
      fallbacks:
        - "anthropic/claude-sonnet-4-6"
        - "google/gemini-2.5-pro"

    # 多模态生成模型（全局级别专属）
    imageModel: "openai/gpt-5"
    imageGenerationModel: "openai/dall-e-3"
    pdfModel:
      primary: "google/gemini-2.5-pro"
      fallbacks:
        - "openai/gpt-5"
    pdfMaxBytesMb: 10
    pdfMaxPages: 20

    # Subagent 安全参数（全局级别专属）
    subagents:
      maxConcurrent: 2
      maxSpawnDepth: 2
      maxChildrenPerAgent: 5
      archiveAfterMinutes: 60
      think: "low"
      runTimeoutSeconds: 300

    # 上下文修剪（全局级别专属）
    contextPruning:
      mode: "cache-ttl"
      ttl: "30m"
      keepLastAssistants: 5
      softTrim:
        maxChars: 4000
        headChars: 800
        tailChars: 800
      hardClear:
        enabled: true
        placeholder: "[上下文已修剪以释放空间]"

    # 压缩配置（全局级别专属）
    compaction:
      mode: "safeguard"
      reserveTokens: 8192
      keepRecentTokens: 4096
      recentTurnsPreserve: 3
      postCompactionSections: ["Session Startup", "Red Lines"]
      qualityGuard:
        enabled: true
        maxRetries: 2
      memoryFlush:
        enabled: true
      timeoutSeconds: 900
      notifyUser: true

    # Per-model 精细配置
    models:
      "openai/gpt-5":
        params:
          temperature: 0.7
        streaming: true
      "anthropic/claude-sonnet-4-6":
        streaming: true
      "google/gemini-2.5-pro":
        streaming: true

    thinkingDefault: "medium"
    verboseDefault: "on"
    timeoutSeconds: 600

  list:
    # 通用助手 Agent
    - id: "general"
      default: true
      name: "通用助手"

    # 编程专用 Agent
    - id: "coder"
      name: "编程助手"
      model:
        primary: "openai/gpt-5-codex"
        fallbacks:
          - "openai/gpt-5"
      thinkingDefault: "high"
      verboseDefault: "full"
      models:
        "openai/gpt-5-codex":
          params:
            temperature: 0.2     # 编程场景使用更低温度
      skills: ["code-review", "refactoring"]
      subagents:
        delegationMode: "prefer"

    # 创意 Agent（使用全局默认模型）
    - id: "creative"
      name: "创意助手"
      thinkingDefault: "xhigh"
      skills: ["writing", "brainstorming"]

# 路由绑定
bindings:
  # Discord 通用路由
  - agentId: "general"
    match:
      channel: "discord"

  # Discord 编程频道 → coder Agent
  - agentId: "coder"
    match:
      channel: "discord"
      guildId: "123456789"
      peer:
        kind: "channel"
        id: "coding-chat-channel-id"

  # Telegram VIP 用户 → 使用 coder Agent
  - agentId: "coder"
    match:
      channel: "telegram"
      peer:
        kind: "dm"
        id: "987654321"

  # CLI → ACP 绑定
  - type: "acp"
    agentId: "general"
    match:
      channel: "cli"
    acp:
      mode: "persistent"
      backend: "claude"
      cwd: "/home/user/projects"
```

在这个配置中：

- 三个 Agent（`general`、`coder`、`creative`）共享全局的多模态模型、上下文修剪、压缩和 Subagent 安全策略
- `coder` Agent 使用专用的编程模型和更低的温度参数
- `creative` Agent 继承全局默认模型但使用更高的思考等级
- 路由绑定将不同的 Discord 频道、Telegram 用户和 CLI 会话分发到对应的 Agent
- CLI 渠道使用 ACP 持久会话模式
- 所有 Agent 共享相同的上下文修剪策略（30 分钟 TTL）、压缩策略（safeguard 模式带质量审计）和 Subagent 限制（最多 2 个并发，深度不超过 2 层）

---

## 总结

本文档深入剖析了 OpenClaw 的 `AgentDefaultsConfig` 全局级别专属字段、九种模型 API 类型、模型选择优先级链、路由绑定机制以及六条配置继承规则。关键要点：

1. **全局级别专属字段**（多模态生成模型、PDF 模型、上下文修剪、启动上下文、压缩配置、Subagent 安全参数）的设计源于安全边界、资源约束和能力一致性的需求，这些字段在任何 Agent 级别都不可覆盖。

2. **九种模型 API 类型**覆盖了从云端 API（OpenAI、Anthropic、Google）到本地部署（Ollama）的完整模型生态，通过统一的 `ModelDefinitionConfig` 和 `ModelCompatConfig`（23 个兼容性字段）屏蔽了不同提供商之间的差异。

3. **模型选择优先级链**遵循 `AgentConfig.model > AgentDefaultsConfig.model > fallbacks` 的严格顺序，其中 Agent 级别的回退链是独立完整的。Subagent 有专用的优先级链，支持为子 Agent 配置不同于主 Agent 的模型。

4. **路由绑定**通过 `AgentBindingMatch` 的多维度匹配条件（渠道、账户、对话类型、对话 ID、Guild、团队、角色）实现精确的消息分发，`AgentAcpBinding` 进一步支持 ACP 协议的外部 Agent 集成。

5. **六条配置继承规则**（覆盖规则、全局专属规则、模型链独立、深度合并、选择性继承、绑定级联覆盖）共同定义了配置的优先级和合并行为。
# 二、Agent生命周期（上）：完整生命周期与状态机

## 本章导引

OpenClaw 的 Agent 系统是整个消息代理机器人框架的核心。当一个用户消息抵达网关、经过路由和调度之后，最终会进入 Agent Runner 来执行一次完整的「回复运行」（Reply Run）。这个过程并非简单地调用一次 LLM API，而是经过一套精心设计的状态机、多层抽象、生命周期事件系统以及优雅降级（fallback）机制的编排。

本章将从架构分层、完整生命周期四阶段、核心状态机及事件系统四个维度，系统性地剖析 OpenClaw Agent 的运行时模型。文中的代码片段均提取自 OpenClaw 实际构建产物 `agent-runner.runtime-B9LwhObT.js` 和 `agent-events-DVSiKwui.js`，确保分析建立在真实实现之上。

---

## 第一部分：整体架构——七层抽象

从架构视角看，OpenClaw 的 Agent 执行体系可以划分为七个协同工作的层次，每一层都有明确的职责边界：

```
+-------------------------------------------------------------------+
|                        Agent Runner                               |
|  (最外层编排：队列策略、转向注入、回复运行的生命周期调度)            |
+-------------------------------------------------------------------+
|                        Agent Runtime                              |
|  (运行时适配层：cli vs embedded/pi 执行路径分发)                     |
+-------------------------------------------------------------------+
|                     Agent Harness Runtime                         |
|  (模型厂商网关适配：通过 plugin 机制对接 Anthropic/OpenAI/Google 等)  |
+-------------------------------------------------------------------+
|                        Agent Events                               |
|  (9 流事件总线：生命周期事件的采集、排序、分发与监听)                 |
+-------------------------------------------------------------------+
|                        Agent Scope                                |
|  (会话级别状态：模型覆盖、认证配置、fallback 来源追踪)                |
+-------------------------------------------------------------------+
|                        Agent Limits                               |
|  (资源控制：上下文窗口、token 阈值、compaction 预算、超时)            |
+-------------------------------------------------------------------+
|                        Agent Failure                              |
|  (容错与自愈：模型回退、会话重置、瞬态错误重试、优雅降级告警)          |
+-------------------------------------------------------------------+
```

### 1.1 Agent Runner（代理运行器）

**入口函数：`runReplyAgent`（`agent-runner.runtime-B9LwhObT.js` 第 4254 行）**

这是整个 Agent 执行体系的最外层编排器。它负责：

- **队列策略判定**：调用 `resolveActiveRunQueueAction` 决定当前消息是立即运行（`"run-now"`）、丢弃（`"drop"`）、入队等待（`"enqueue-followup"`）还是转向注入（steering）；
- **生命周期调度**：按顺序驱动 preflight compaction、memory flush、agent turn 执行、以及结果处理；
- **Fallback 状态管理**：追踪模型回退的转换状态（`resolveFallbackTransition`），在回退激活或清除时发送通知；
- **清理收尾**：完成 ReplyOperation 的生命周期终止（`complete()` / `fail()`），释放 typing 指示器和队列资源。

核心签名与导入（第 22-23 行）：

```javascript
import { i as emitAgentEvent, l as onAgentEvent, u as registerAgentRunContext } from "./agent-events-DVSiKwui.js";
```

```javascript
async function runReplyAgent(params) {
    const { commandBody, transcriptCommandBody, followupRun, queueKey,
            resolvedQueue, shouldSteer, shouldFollowup, isActive,
            isRunActive, isStreaming, opts, typing, sessionEntry,
            sessionStore, sessionKey, ... } = params;
    // ...
    const activeRunQueueAction = resolveActiveRunQueueAction({
        isActive, isHeartbeat, shouldFollowup: effectiveShouldFollowup,
        queueMode: activeRunQueueMode, resetTriggered: effectiveResetTriggered
    });
    // ... 根据 queue action 决定后续行为 ...
}
```

### 1.2 Agent Runtime（代理运行时）

**核心导入（第 73-81 行）**：

```javascript
import { t as runEmbeddedPiAgent } from "./pi-embedded-ydXicp1O.js";
import { t as runCliAgent } from "./cli-runner-jPUQ9KeD.js";
```

Agent Runtime 层负责将 Agent 运行请求分发到两种不同的执行路径：

- **Embedded / PI Runtime**：通过 `runEmbeddedPiAgent` 调用，使用网关内嵌的 Agent Harness 子代理绑定。这是主要执行路径，支持完整的 compaction、memory flush、lifecycle backstop 等机制。
- **CLI Runtime**：通过 `runCliAgentWithLifecycle`（第 222 行）调用，使用外部 CLI 子进程（如 Claude CLI）。该路径自行管理 assistant text bridge 和 lifecycle 事件的发送。

`runAgentTurnWithFallback`（第 953 行）是两种 runtime 的统一调度入口。它在 `while(true)` 主循环中通过 `runWithModelFallback` 执行模型回退链，内部根据 `isCliProvider(cliExecutionProvider, runtimeConfig)` 分叉到不同 runtime。

### 1.3 Agent Harness Runtime（Agent 马具运行时）

**导入（第 74 行）**：

```javascript
import { t as ensureSelectedAgentHarnessPlugin } from "./runtime-plugin-_cQprMjo.js";
```

这一层是模型网关的具体适配实现。在每次模型调用前，`ensureSelectedAgentHarnessPlugin` 会被调用以确保目标 provider/model 的 Agent Harness 插件已就绪：

```javascript
prepareAgentHarnessRuntime: async ({ provider, model, agentHarnessRuntimeOverride }) => {
    await ensureSelectedAgentHarnessPlugin({
        config: runtimeConfig,
        provider,
        modelId: model,
        agentId: params.followupRun.run.agentId,
        sessionKey: params.followupRun.run.runtimePolicySessionKey ?? params.sessionKey,
        agentHarnessRuntimeOverride,
        workspaceDir: params.followupRun.run.workspaceDir
    });
}
```

Harness Runtime 还通过 `resolveSessionRuntimeOverrideForProvider`（第 894 行）支持会话级别的运行时覆盖，允许特定 provider 强制使用 `"pi"` 或 `"codex"` 运行时。

### 1.4 Agent Events（代理事件）

**文件：`agent-events-DVSiKwui.js`（整个文件，共 124 行）**

这是事件系统的核心。它维护一个全局单例状态（通过 `Symbol.for("openclaw.agentEvents.state")`），包含：

- `seqByRun: Map<runId, number>` —— 每个 runId 的单调递增事件序号；
- `listeners: Set<listener>` —— 已注册的事件监听器集合；
- `runContextById: Map<runId, context>` —— 活跃运行的上下文信息。

核心函数 `emitAgentEvent` 展示了事件的完整处理流程：

```javascript
function emitAgentEvent(event) {
    const state = getAgentEventState();
    const nextSeq = (state.seqByRun.get(event.runId) ?? 0) + 1;
    state.seqByRun.set(event.runId, nextSeq);
    const context = state.runContextById.get(event.runId);
    if (context) context.lastActiveAt = Date.now();
    const isControlUiVisible = context?.isControlUiVisible ?? true;
    const eventSessionKey = typeof event.sessionKey === "string"
        && event.sessionKey.trim() ? event.sessionKey : void 0;
    const sessionKey = isControlUiVisible || event.stream === "lifecycle"
        ? eventSessionKey ?? context?.sessionKey : void 0;
    const enriched = {
        ...event,
        sessionKey,
        seq: nextSeq,
        ts: Date.now()
    };
    notifyListeners(state.listeners, enriched);
}
```

我们还将在第四部分对事件系统进行更深入的剖析。

### 1.5 Agent Scope（代理作用域）

**导入（第 6 行）**：

```javascript
import { C as hasSessionAutoModelFallbackProvenance,
         _ as resolveSessionAgentId,
         i as markAutoFallbackPrimaryProbe,
         n as entryMatchesAutoFallbackPrimaryProbe,
         p as resolveAutoFallbackPrimaryProbe,
         r as hasConfiguredModelFallbacks,
         t as clearAutoFallbackPrimaryProbeSelection } from "./agent-scope-Cf7T6Ju7.js";
```

Agent Scope 管理会话级别的状态，核心是 **Auto Fallback Primary Probe** 机制。当某个模型变得不可用而触发回退后，系统会保存主模型的 probe 信息。在后续运行中，`resolveRunAfterAutoFallbackPrimaryProbeRecheck`（第 902 行）会检查主模型是否已恢复可用：

```javascript
function resolveRunAfterAutoFallbackPrimaryProbeRecheck(params) {
    const probe = params.run.autoFallbackPrimaryProbe;
    if (!probe || !params.sessionKey) return params.run;
    // ...
    const refreshedProbe = resolveAutoFallbackPrimaryProbe({
        entry: params.entry,
        sessionKey: params.sessionKey,
        primaryProvider: probe.provider,
        primaryModel: probe.model
    });
    if (!refreshedProbe) return resolveEntrySelectionRun();
    return {
        ...params.run,
        provider: refreshedProbe.provider,
        model: refreshedProbe.model,
        autoFallbackPrimaryProbe: refreshedProbe
    };
}
```

### 1.6 Agent Limits（代理资源限制）

**核心定义（第 741、1836、2159-2161 行）**：

这一层控制 Agent 执行过程中的资源边界：

```javascript
// 上下文溢出时的用户提示中给出了默认保留值
const CONTEXT_OVERFLOW_RESET_HINT = "\n\nTo prevent this, increase your "
    + "compaction buffer by setting `agents.defaults.compaction.reserveTokensFloor` "
    + "to 20000 or higher in your config.";

// 默认的 token 阈值
const reserveTokensFloor = memoryFlushPlan?.reserveTokensFloor
    ?? params.cfg.agents?.defaults?.compaction?.reserveTokensFloor
    ?? 2e4;  // 20000
const softThresholdTokens = memoryFlushPlan?.softThresholdTokens ?? 4e3;  // 4000
```

关键的阈值计算公式如下（`resolveMemoryFlushGateState` 第 1845-1858 行）：

```javascript
function resolveMemoryFlushGateState(params) {
    if (!params.entry) return null;
    const totalTokens = resolvePositiveTokenCount$1(params.tokenCount)
        ?? resolveFreshSessionTotalTokens(params.entry);
    if (!totalTokens || totalTokens <= 0) return null;
    const contextWindow = Math.max(1, Math.floor(params.contextWindowTokens));
    const reserveTokens = Math.max(0, Math.floor(params.reserveTokensFloor));
    const softThreshold = Math.max(0, Math.floor(params.softThresholdTokens));
    const threshold = Math.max(0, contextWindow - reserveTokens - softThreshold);
    if (threshold <= 0) return null;
    return { entry: params.entry, totalTokens, threshold };
}
```

同时，`resolveMaxActiveTranscriptBytes`（第 1836 行）提供了基于 transcript 文件大小的第二触发路径：

```javascript
function resolveMaxActiveTranscriptBytes(cfg) {
    const compaction = cfg?.agents?.defaults?.compaction;
    if (compaction?.truncateAfterCompaction !== true) return;
    const parsed = parseNonNegativeByteSize(compaction.maxActiveTranscriptBytes);
    return typeof parsed === "number" && parsed > 0 ? parsed : void 0;
}
```

### 1.7 Agent Failure（代理容错）

**核心处理逻辑（第 1642-1747 行的主循环 catch 块）**

这一层实现了多层次的自愈策略：

| 错误类型 | 处理策略 | 代码位置 |
|---------|---------|---------|
| `LiveSessionModelSwitchError` | 应用活跃模型切换，最多重试 2 次 | 第 1643-1665 行 |
| `isReplyOperationUserAbort` | 返回静默令牌，不产生用户可见输出 | 第 1676-1679 行 |
| `isReplyOperationRestartAbort` | 返回网关重启提示 | 第 1672-1675 行 |
| `GatewayDrainingError` | fail reply operation，返回重启提示 | 第 1681-1687 行 |
| `CommandLaneClearedError` | fail reply operation，返回重启提示 | 第 1688-1694 行 |
| `isCompactionFailure` | 重置会话后重试（最多一次） | 第 1695-1708 行 |
| `providerRequestError` | 返回服务商拒绝会话状态的用户提示 | 第 1710-1716 行 |
| `isTransientHttp` | 延迟 2500ms 后重试一次 | 第 1717-1724 行 |
| `isBilling` | 返回计费错误提示 | 第 1667 行 |
| `isContextOverflow` | 返回上下文溢出提示，建议缩短消息 | 第 1668 行 |
| 通用失败 | 返回通用失败提示 | 第 1725-1747 行 |

```javascript
} catch (err) {
    if (err instanceof LiveSessionModelSwitchError) {
        liveModelSwitchRetries += 1;
        if (liveModelSwitchRetries > 2) {
            // ... 失败处理 ...
        }
        applyLiveModelSwitchToRun(params.followupRun.run, err);
        // ...
        continue;  // 重试主循环
    }
    // ... 更多错误分类处理 ...
}
```

---

## 第二部分：完整的 Agent 生命周期——四个阶段

OpenClaw 中的一次完整 Agent 回复运行经历了精确定义的四个阶段。在 `runReplyAgent` 函数中（第 4254 行），这些阶段被顺序执行：

```
Phase 0: Queue Check
Phase 1: Preflight Compaction
Phase 2: Memory Flush
Phase 3: Agent Turn Execution
Phase 4: Result Processing
```

### Phase 0：队列检查（Queue Check）

**入口：`resolveActiveRunQueueAction`（`typing-mode-D44I4YRQ.js` 第 105-111 行）**

在实际执行任何 Agent 工作之前，系统首先判定当前消息的动作类型：

```javascript
function resolveActiveRunQueueAction(params) {
    if (!params.isActive) return "run-now";
    if (params.isHeartbeat) return "drop";
    if (params.resetTriggered) return "run-now";
    if (params.shouldFollowup) return "enqueue-followup";
    return "run-now";
}
```

四个动作的含义如下：

| 动作 | 含义 | 触发条件 |
|------|------|---------|
| `"run-now"` | 立即执行完整的 Agent Run | 无活跃运行、或 reset 触发、或不应 followup |
| `"steer"` | 向当前活跃运行的 Agent 注入转向消息 | `effectiveShouldSteer && isStreaming` 时触发（第 4305 行），通过 `queueEmbeddedPiMessageWithOutcomeAsync` 注入 |
| `"enqueue-followup"` | 将消息放入 followup 队列，等当前 Run 结束后执行 | 有活跃运行且 `shouldFollowup` 为 true |
| `"drop"` | 丢弃消息（如心跳运行期间收到的心跳消息） | `isHeartbeat` 为 true |

在大入口 `runReplyAgent` 中的判断逻辑（第 4318-4349 行）：

```javascript
const activeRunQueueAction = resolveActiveRunQueueAction({
    isActive, isHeartbeat, shouldFollowup: effectiveShouldFollowup,
    queueMode: activeRunQueueMode, resetTriggered: effectiveResetTriggered
});

if (activeRunQueueAction === "drop") {
    typing.cleanup();
    return;
}

if (activeRunQueueAction === "enqueue-followup") {
    enqueueFollowupRun(queueKey, followupRun, resolvedQueue, "message-id",
        queuedRunFollowupTurn, false);
    const queuedBehindActiveRun = isRunActive?.() === true;
    if (!queuedBehindActiveRun) scheduleFollowupDrain(queueKey, queuedRunFollowupTurn);
    await touchActiveSessionEntry();
    if (queuedBehindActiveRun) await typingSignals.signalToolStart();
    else typing.cleanup();
    return;
}
```

**Steering 注入逻辑**（第 4305-4317 行）在队列检查的早期执行：

```javascript
if (effectiveShouldSteer && isStreaming) {
    const steerSessionId = (sessionKey
        ? replyRunRegistry.resolveSessionId(sessionKey) : void 0)
        ?? followupRun.run.sessionId;
    const steerOutcome = await queueEmbeddedPiMessageWithOutcomeAsync(
        steerSessionId, followupRun.prompt, {
            steeringMode: "all",
            ...resolvedQueue.debounceMs !== void 0
                ? { debounceMs: resolvedQueue.debounceMs } : {}
        });
    if (steerOutcome.queued) {
        await touchActiveSessionEntry();
        typing.cleanup();
        return;
    }
    logVerbose(`queue: active session ${steerSessionId} rejected `
        + `steering injection: ${formatEmbeddedPiQueueFailureSummary(steerOutcome)}`);
}
```

### Phase 1：Preflight Compaction（预检压缩）

**入口：`runPreflightCompactionIfNeeded`（第 2141-2271 行）**

这是在 Agent Turn 执行之前进行的上下文压缩检查。其核心目的是在发送新消息之前确认当前会话的 token 使用量是否接近模型的上下文窗口上限。

#### 1a. Token 阈值计算

```javascript
const contextWindowTokens = resolveMemoryFlushContextWindowTokens({ ... });
const memoryFlushPlan = resolveMemoryFlushPlan({ cfg: params.cfg });
const reserveTokensFloor = memoryFlushPlan?.reserveTokensFloor
    ?? params.cfg.agents?.defaults?.compaction?.reserveTokensFloor
    ?? 2e4;   // 默认 20000
const softThresholdTokens = memoryFlushPlan?.softThresholdTokens ?? 4e3;  // 默认 4000
const threshold = contextWindowTokens - reserveTokensFloor - softThresholdTokens;
```

**阈值公式**：

```
threshold = contextWindow - reserveTokensFloor(20000) - softThresholdTokens(4000)
```

对于 200K 上下文窗口的模型：
```
threshold = 200000 - 20000 - 4000 = 176000 tokens
```

也就是说，当会话 token 数达到 176000 时触发 preflight compaction。

#### 1b. `shouldRunPreflightCompaction` 的判断逻辑

```javascript
function shouldRunPreflightCompaction(params) {
    const state = resolveMemoryFlushGateState(params);
    return Boolean(state && state.totalTokens >= state.threshold);
}
```

这个判断的核心在于 `resolveMemoryFlushGateState`（第 1845-1858 行），它先计算阈值（contextWindow - reserveTokensFloor - softThresholdTokens），然后比较当前会话总 token 数。

#### 1c. 双触发器机制：Token 触发 + 文件大小触发

第 2177 行引入了基于 transcript 文件大小的第二触发路径：

```javascript
const shouldCompactByTranscriptBytes =
    typeof activeTranscriptBytes === "number"
    && typeof maxActiveTranscriptBytes === "number"
    && activeTranscriptBytes >= maxActiveTranscriptBytes;
```

这意味着 compaction 可以由两个条件中的任意一个触发：
1. **Token 数量**超过阈值（`totalTokens >= threshold`）
2. **Transcript 文件大小**超过 `maxActiveTranscriptBytes` 配置值

触发源会被记录在日志中：

```javascript
const compactionTrigger = shouldCompactByTranscriptBytes ? "transcript_bytes" : "tokens";
logVerbose(`preflightCompaction triggered: sessionKey=${params.sessionKey} `
    + `tokenCount=${...} threshold=${threshold} trigger=${compactionTrigger} ...`);
```

#### 1d. compactionCount 与 Post-Compaction Refresh Prompt

Preflight compaction 执行后，系统会：

1. **递增 compactionCount**（第 2242-2251 行）：通过 `incrementCompactionCount` 更新会话存储中的压缩计数；
2. **附加 Post-Compaction Refresh Prompt**（第 2252-2255 行）：调用 `appendPostCompactionRefreshPrompt` 将刷新上下文注入到 `extraSystemPrompt` 中。

`appendPostCompactionRefreshPrompt` 的实现（第 2025-2033 行）：

```javascript
async function appendPostCompactionRefreshPrompt(params) {
    const refreshPrompt = await readPostCompactionContext(
        params.followupRun.run.workspaceDir, {
            cfg: params.cfg,
            agentId: params.followupRun.run.agentId
        });
    if (!refreshPrompt) return;
    const existingPrompt = normalizeOptionalString(
        params.followupRun.run.extraSystemPrompt);
    if (existingPrompt?.includes(refreshPrompt)) return;
    params.followupRun.run.extraSystemPrompt =
        [existingPrompt, refreshPrompt].filter(Boolean).join("\n\n");
}
```

这确保 Agent 在上下文被压缩后，会收到一份「刷新提示」，告知它当前的会话状态发生了变更。

#### 1e. 会话 ID 更新与队列刷新

当 compaction 导致会话 ID 变更时（例如因为 compaction 创建了新的 session），系统必须同步更新所有依赖会话 ID 的组件：

```javascript
if (entry) {
    const previousSessionId = params.followupRun.run.sessionId;
    params.followupRun.run.sessionId = entry.sessionId;
    params.replyOperation.updateSessionId(entry.sessionId);
    if (entry.sessionFile) params.followupRun.run.sessionFile = entry.sessionFile;
    const queueKey = params.followupRun.run.sessionKey ?? params.sessionKey;
    if (queueKey) memoryDeps.refreshQueuedFollowupSession({
        key: queueKey,
        previousSessionId,
        nextSessionId: entry.sessionId,
        nextSessionFile: entry.sessionFile
    });
}
```

`replyOperation.updateSessionId` 还会更新 Reply Run Registry 中的全局映射（`reply-run-registry-CcD5OkAB.js` 第 146-160 行），确保 `activeKeysBySessionId` 和 `activeSessionIdsByKey` 保持一致性。

### Phase 2：Memory Flush（记忆刷新）

**入口：`runMemoryFlushIfNeeded`（第 2272-2490 行）**

Memory Flush 是比 Preflight Compaction 更进一步的优化操作。它不是在本地压缩上下文，而是调用一个独立的 Memory Flush 模型运行，将会话记忆持久化到外部文件。

#### 2a. `hasAlreadyFlushedForCurrentCompaction` 守卫

```javascript
function hasAlreadyFlushedForCurrentCompaction(entry) {
    const compactionCount = entry.compactionCount ?? 0;
    const lastFlushAt = entry.memoryFlushCompactionCount;
    return typeof lastFlushAt === "number" && lastFlushAt === compactionCount;
}
```

这个守卫确保在同一个 compaction 周期内不会重复执行 memory flush。每次 flush 完成后，系统会将 `memoryFlushCompactionCount` 设置为当前的 `compactionCount`（第 2471-2472 行）：

```javascript
update: async () => ({
    memoryFlushAt: memoryDeps.now(),
    memoryFlushCompactionCount: flushedCompactionCount
})
```

#### 2b. Memory Flush 的触发条件

`shouldRunMemoryFlush`（第 1860-1865 行）的判断逻辑：

```javascript
function shouldRunMemoryFlush(params) {
    const state = resolveMemoryFlushGateState(params);
    if (!state || state.totalTokens < state.threshold) return false;
    if (hasAlreadyFlushedForCurrentCompaction(state.entry)) return false;
    return true;
}
```

此外，还有一系列前置条件（第 2284-2285 行）：

```javascript
const isCli = isCliProvider(params.followupRun.run.provider, params.cfg);
const canAttemptFlush = memoryFlushWritable && !params.isHeartbeat && !isCli;
```

Memory flush 不会在以下场景触发：
- 心跳运行（heartbeat）
- CLI 运行
- 沙箱化运行且 workspace 不可写
- 已经在当前 compaction 周期内执行过

#### 2c. Memory Flush 模型选择

Memory Flush 使用独立配置的模型，而非主 Agent 的模型。模型选择通过 `resolveMemoryFlushModelFallbackOptions`（第 1924-1943 行）实现：

```javascript
function resolveMemoryFlushModelFallbackOptions(run, model, configOverride = run.config) {
    const options = resolveModelFallbackOptions(run, configOverride);
    const override = normalizeOptionalString(model);
    if (!override) return options;
    const slashIdx = override.indexOf("/");
    if (slashIdx > 0) {
        const overrideProvider = override.slice(0, slashIdx).trim();
        const overrideModel = override.slice(slashIdx + 1).trim();
        if (overrideProvider && overrideModel) return {
            ...options,
            provider: overrideProvider,
            model: overrideModel,
            fallbacksOverride: []
        };
    }
    return { ...options, model: override, fallbacksOverride: [] };
}
```

Memory Flush 模型在 `memoryFlushPlan` 中配置，通过 `resolveMemoryFlushPlan({ cfg })` 获取。Flush 运行使用特殊的 `trigger: "memory"` 标识：

```javascript
const result = await memoryDeps.runEmbeddedPiAgent({
    ...embeddedContext,
    ...senderContext,
    ...runBaseParams,
    sandboxSessionKey: params.runtimePolicySessionKey,
    allowGatewaySubagentBinding: true,
    silentExpected: true,      // 静默运行，不产生用户可见输出
    trigger: "memory",          // 标识为记忆刷新触发
    memoryFlushWritePath,       // 输出文件路径
    prompt: activeMemoryFlushPlan.prompt,
    transcriptPrompt: "",
    extraSystemPrompt: flushSystemPrompt,
    // ...
});
```

#### 2d. Memory Flush 回退

Memory Flush 也使用 `runWithModelFallback` 进行模型回退。如果 flush 过程中产生的内部 compaction 完成（`memoryCompactionCompleted`），系统同样会递增 compactionCount 并更新会话 ID。

### Phase 3：Agent Turn 执行

**入口：`runAgentTurnWithFallback`（第 953 行）**

这是整个生命周期中最核心的阶段。Agent Turn 的执行包含以下关键组件：

#### 3a. Run ID 与 Reply Operation

```javascript
const runId = params.opts?.runId ?? crypto.randomUUID();
```

如果调用方未提供 `runId`，系统会使用 `crypto.randomUUID()` 生成一个新的 UUID。这个 `runId` 贯穿整个 Turn 的所有事件（lifecycle、assistant、tool、compaction 等），是事件关联的主键。

#### 3b. Auto Fallback Primary Probe 的注册与重新检查

```javascript
let runnableRun = resolveRunAfterAutoFallbackPrimaryProbeRecheck({
    run: params.followupRun.run,
    entry: params.activeSessionStore?.[params.sessionKey ?? ""]
        ?? params.getActiveSessionEntry(),
    sessionKey: params.sessionKey
});
```

#### 3c. Agent Run Context 注册

在开始执行前，系统会在事件系统中注册 Run Context（第 1055-1060 行）：

```javascript
if (params.sessionKey) registerAgentRunContext(runId, {
    sessionKey: params.sessionKey,
    verboseLevel: params.resolvedVerboseLevel,
    isHeartbeat: params.isHeartbeat,
    isControlUiVisible: shouldSurfaceToControlUi
});
```

`registerAgentRunContext`（`agent-events-DVSiKwui.js` 第 12-28 行）的逻辑会处理首次注册和更新现有 context 两种场景：

```javascript
function registerAgentRunContext(runId, context) {
    if (!runId) return;
    const state = getAgentEventState();
    const existing = state.runContextById.get(runId);
    if (!existing) {
        state.runContextById.set(runId, {
            ...context,
            registeredAt: context.registeredAt ?? Date.now()
        });
        return;
    }
    if (context.sessionKey && existing.sessionKey !== context.sessionKey)
        existing.sessionKey = context.sessionKey;
    if (context.verboseLevel && existing.verboseLevel !== context.verboseLevel)
        existing.verboseLevel = context.verboseLevel;
    // ...
}
```

#### 3d. `while(true)` 主循环（第 1149 行）

这是 Turn 执行的核心循环，它包裹了 `runWithModelFallback` 调用：

```javascript
while (true) try {
    // ... 构建 normalizeStreamingText, blockReplyHandler 等内部 closures ...

    const fallbackResult = await runWithModelFallback({
        ...resolveModelFallbackOptions(effectiveRun, runtimeConfig),
        runId,
        sessionId: params.followupRun.run.sessionId,
        lane: runLane,
        // ...
        run: async (provider, model, runOptions) => {
            // CLI 路径或 Embedded/ PI 路径
            if (isCliProvider(cliExecutionProvider, runtimeConfig)) {
                // ... CLI 执行路径 ...
            }
            // Embedded / PI 路径
            const result = await runEmbeddedPiAgent({ ... });
            return result;
        }
    });

    runResult = fallbackResult.result;
    // ... 处理结果 ...
    break;  // 正常结束，退出循环
} catch (err) {
    if (err instanceof LiveSessionModelSwitchError) {
        // ... 重试逻辑 ...
        continue;  // 重试主循环，这是 continue 的主要场景
    }
    // ... 其他错误分类处理 ...
}
```

循环只有在以下情况才会重新迭代：
1. `LiveSessionModelSwitchError` 被抛出且重试次数未超过 2 次 —— `continue`
2. `isTransientHttpError` 且未重试过 —— `continue`

其他所有情况（包括成功）都会通过 `break` 或 `return` 退出循环。

#### 3e. Lifecycle Backstop

在 Embedded / PI 执行路径中，系统通过 `createEmbeddedLifecycleTerminalBackstop`（第 844-881 行）创建了一个生命周期事件的安全网：

```javascript
const lifecycleBackstop = createEmbeddedLifecycleTerminalBackstop({
    runId,
    sessionKey: params.sessionKey
});
```

这个 backstop 的工作原理是监听生命周期事件。如果 PI runtime 因为某种原因没有发出 `"end"` 或 `"error"` 事件（例如底层 provider SDK 崩溃），backstop 会在结果返回或异常抛出时强制发出终止事件，防止 UI 永远显示 "running" 状态。

```javascript
function createEmbeddedLifecycleTerminalBackstop(params) {
    let terminalEmitted = false;
    let startedAt;
    const note = (evt) => {
        if (evt.stream !== "lifecycle") return;
        const phase = readStringValue(evt.data.phase);
        if (phase === "start" && typeof evt.data.startedAt === "number")
            startedAt = evt.data.startedAt;
        if (phase === "end" || phase === "error") terminalEmitted = true;
    };
    const emit = (phase, resultOrError) => {
        if (terminalEmitted) return;
        terminalEmitted = true;
        const data = {
            phase,
            endedAt: Date.now(),
            ...startedAt !== void 0 ? { startedAt } : {}
        };
        if (phase === "error") data.error = formatErrorMessage(resultOrError);
        else { /* 处理 meta.aborted, stopReason 等 */ }
        emitAgentEvent({
            runId: params.runId,
            ...params.sessionKey ? { sessionKey: params.sessionKey } : {},
            stream: "lifecycle",
            data
        });
    };
    return { emit, note };
}
```

Backstop 的使用模式：

```javascript
try {
    const result = await runEmbeddedPiAgent({ ... });
    lifecycleBackstop.emit("end", result);   // 成功：发出 "end"
    return result;
} catch (err) {
    lifecycleBackstop.emit("error", err);    // 失败：发出 "error"
    throw err;
}
```

事件流中的 `onAgentEvent` 回调也会调用 `lifecycleBackstop.note(evt)` 来追踪 PI runtime 自身发出的事件，这样 backstop 就能判断是否已经被 PI runtime 正常终止。

### Phase 4：结果处理（Result Processing）

在 `runReplyAgent` 函数中（第 4554-5005 行），Agent Turn 执行完成后进入结果处理阶段：

#### 4a. 构建回复 Payload

`buildReplyPayloads`（第 2572-2708 行）负责将运行结果转换为面向用户的回复消息。它处理以下逻辑：
- 静默 token 剥离（HEARTBEAT_OK / NO_REPLY）
- 心跳运行的特殊清理
- 回复线程标记（reply threading）
- 媒体 URL 去重
- 块流式传输的内容抑制

#### 4b. Fallback 通知

`resolveFallbackTransition` 检测模型回退的状态变化（第 153-188 行），如果发生回退切换，系统会在回复中插入回退通知：

```javascript
if (fallbackTransition.fallbackTransitioned) {
    emitAgentEvent({
        runId, sessionKey, stream: "lifecycle",
        data: {
            phase: "fallback",
            selectedProvider, selectedModel,
            activeProvider: providerUsed, activeModel: modelUsed,
            reasonSummary: fallbackTransition.reasonSummary,
            attemptSummaries: fallbackTransition.attemptSummaries,
            attempts: fallbackAttempts
        }
    });
    const fallbackNotice = buildFallbackNotice({
        selectedProvider, selectedModel,
        activeProvider: providerUsed, activeModel: modelUsed,
        attempts: fallbackAttempts
    });
    if (fallbackNotice) fallbackNoticePayloads.push(
        markReplyPayloadForSourceSuppressionDelivery({
            text: fallbackNotice, isFallbackNotice: true
        })
    );
}
```

#### 4c. Commitment 提取

`enqueueCommitmentExtractionForTurn`（第 4216-4240 行）对回复文本中的承诺（"I'll remind you" 等）进行提取和调度：

```javascript
enqueueCommitmentExtraction({
    cfg: params.cfg,
    agentId: params.followupRun.run.agentId,
    sessionKey, channel,
    userText, assistantText,
    sourceRunId: params.runId
});
```

#### 4d. Usage Line 追加

`appendUsageLine` 在回复末尾添加 token 使用统计：

```
Usage: 12.3k in / 4.5k out · cache 2.1k cached / 0.8k new · est $0.047
```

#### 4e. Context Management 与 Post-Compaction Context

如果在 Turn 中有 auto-compaction 发生，系统会：
1. 递增 `compactionCount`
2. 读取 `PostCompactionContext` 并排入系统事件
3. 如果有 verbose 且产生了 compaction，在回复前添加 `"🧹 Auto-compaction complete (count N)."` 前缀

---

## 第三部分：状态机

OpenClaw 的 Agent 系统包含两个正交的状态机：**ReplyOperation 的状态机**（追踪单次回复运行的生命周期阶段）和**会话的运行状态机**（追踪一个会话的全局运行状态）。它们通过**生命周期事件**进行通信。

### 3.1 ReplyOperation 阶段状态机

**定义于：`reply-run-registry-CcD5OkAB.js`（第 84-215 行）**

ReplyOperation 是每次回复运行的核心状态追踪对象。它的状态转换如下：

```
                  createReplyOperation()
                         │
                         ▼
                    ┌─────────┐
                    │ queued  │ ◄── 初始状态
                    └────┬────┘
                         │
              setPhase("preflight_compacting")
                         │
                         ▼
               ┌─────────────────────┐
               │ preflight_compacting│
               └──────────┬──────────┘
                          │
              setPhase("memory_flushing")
                          │
                          ▼
                  ┌────────────────┐
                  │ memory_flushing│
                  └───────┬────────┘
                          │
                  setPhase("running")
                          │
                          ▼
                    ┌──────────┐
               ┌───►│ running  │
               │    └────┬─────┘
               │         │
               │    ┌────┴─────────────┐
               │    │                  │
               │    ▼                  ▼
               │ ┌──────────┐   ┌──────────┐
               │ │completed │   │  failed  │
               │ └──────────┘   └──────────┘
               │
               │  abortByUser() / abortForRestart()
               │         │
               └─────────┘
                         ▼
                    ┌─────────┐
                    │ aborted │
                    └─────────┘
```

**状态转换的约束**：

- 一旦 `result` 被设置（非 null），`setPhase` 就不再生效（第 143 行）：

```javascript
setPhase(next) {
    if (result) return;
    phase = next;
},
```

- `complete()` 设置 `result.kind = "completed"` 和 `phase = "completed"`（第 173-178 行）
- `fail(code, cause)` 设置 `result.kind = "failed"` 和 `phase = "failed"`（第 184-193 行）
- `abortByUser()` 和 `abortForRestart()` 设置 `phase = "aborted"`，并根据不同的中止原因设置 `result.code`（第 195-204 行）

**状态与全局注册表的关联**：

```javascript
const replyRunState = resolveGlobalSingleton(
    Symbol.for("openclaw.replyRunRegistry"), () => ({
        activeRunsByKey: new Map(),       // sessionKey -> ReplyOperation
        activeSessionIdsByKey: new Map(), // sessionKey -> sessionId
        activeKeysBySessionId: new Map(), // sessionId -> sessionKey
        waitKeysBySessionId: new Map(),   // sessionId -> sessionKey (wait)
        waitersByKey: new Map()           // sessionKey -> Set<waiter>
    })
);
```

`createReplyOperation` 在创建时注册，`complete()` / `fail()` 时通过 `clearState` 清理注册，`abortByUser()` 在 `queued` 阶段提前清理。`updateSessionId` 支持在运行时更新 sessionId → sessionKey 映射（典型场景：compaction 导致 sessionId 变更）。

**会话运行时就绪后的检查**：`isReplyRunCompacting`（第 50-54 行）通过查询 phase 状态来判定当前是否正在压缩：

```javascript
function isReplyRunCompacting(operation) {
    if (operation.phase === "preflight_compacting"
        || operation.phase === "memory_flushing") return true;
    if (operation.phase !== "running") return false;
    return getAttachedBackend(operation)?.isCompacting?.() ?? false;
}
```

### 3.2 会话 Run 状态机

相对于 ReplyOperation 追踪单次运行的详细阶段，会话级别追踪的是一个更高层次的运行状态：

```
                    (none) ─── 会话空闲，无活跃运行
                       │
            createReplyOperation()
                       │
                       ▼
                   running ─── 有活跃的 Reply Run 正在执行
                       │
          ┌────────────┼────────────┐
          │            │            │
          ▼            ▼            ▼
        done         failed       killed
   (正常完成)    (运行失败)    (被中止)
          │            │            │
          └────────────┴────────────┘
                       │
            completeFollowupRunLifecycle()
                       │
                       ▼
                    (none) ─── 回复到空闲状态
```

超时状态（timeout）由 `abortSignal` 机制处理，当 AbortController 被触发时，ReplyOperation 进入 `aborted` 状态。

### 3.3 生命周期事件状态机

生命周期事件流（`stream: "lifecycle"`）使用 `phase` 字段描述事件所处的阶段。完整的状态转换如下：

```
                          ┌─────────┐
                          │  start  │ ◄── Agent Turn 开始
                          └────┬────┘
                               │
                    ┌──────────┼──────────┐
                    │          │          │
                    ▼          ▼          ▼
              ┌──────────┐ ┌───────────┐ ┌────────┐
              │fallback_ │ │  (正常执行) │ │ error  │
              │  step    │ └───────────┘ └────────┘
              └────┬─────┘                     ▲
                   │                           │
              ┌────┴────┐                      │
              ▼         ▼                      │
        ┌──────────┐ ┌──────────────────┐      │
        │ fallback │ │ fallback_cleared │      │
        └────┬─────┘ └────────┬─────────┘      │
             │                │                │
             └────────┬───────┘                │
                      │                        │
                      ▼                        │
                  ┌───────┐                    │
                  │  end  │ ◄──────────────────┘
                  └───────┘     (error 后也进入 end)
```

**具体的事件发出位置**：

1. **`start`** —— `runCliAgentWithLifecycle` 第 227-233 行（CLI 路径），或 PI Runtime 内部发出：

```javascript
emitAgentEvent({
    runId: params.runId,
    stream: "lifecycle",
    data: { phase: "start", startedAt }
});
```

2. **`fallback_step`** —— 每次模型回退尝试一个新候选项时（第 883-892 行）：

```javascript
function emitModelFallbackStepLifecycle(params) {
    emitAgentEvent({
        runId: params.runId,
        ...params.sessionKey ? { sessionKey: params.sessionKey } : {},
        stream: "lifecycle",
        data: { phase: "fallback_step", ...params.step }
    });
}
```

3. **`fallback`** —— 模型回退被激活时（第 4674-4689 行）：

```javascript
emitAgentEvent({
    runId, sessionKey, stream: "lifecycle",
    data: {
        phase: "fallback",
        selectedProvider, selectedModel,
        activeProvider: providerUsed, activeModel: modelUsed,
        reasonSummary: fallbackTransition.reasonSummary,
        attemptSummaries: fallbackTransition.attemptSummaries,
        attempts: fallbackAttempts
    }
});
```

4. **`fallback_cleared`** —— 模型回退被清除时（第 4702-4714 行）
5. **`end`** —— 正常完成时（CLI Runner 第 259-270 行；PI Runner 通过 `lifecycleBackstop.emit("end", result)` 第 1583 行）
6. **`error`** —— 运行异常时（CLI Runner 第 278-289 行；PI Runner 通过 `lifecycleBackstop.emit("error", err)` 第 1594 行）

### 3.4 完整状态转换 ASCII 图

将上述三个状态机合并为一个全景图：

```
┌──────────────────────────────────────────────────────────────────────────┐
│                        AGENT LIFECYCLE STATE MACHINE                     │
├──────────────────────────────────────────────────────────────────────────┤
│                                                                          │
│  SESSION STATE:                                                           │
│                                                                          │
│    (none) ─────────────────────────────────────────────► (none)          │
│       │                                                     ▲            │
│       │ createReplyOperation()                               │            │
│       ▼                                                     │            │
│    running ──────► done ─────────────────────────────────────┘            │
│       │                (completeFollowupRunLifecycle)                     │
│       ├──────► failed ─────────────────────────────────────┘            │
│       │                                                                  │
│       └──────► killed/timeout ──────────────────────────────┘            │
│                                                                          │
├──────────────────────────────────────────────────────────────────────────┤
│                                                                          │
│  REPLY OPERATION PHASE STATE:                                             │
│                                                                          │
│    queued ─────────────┐                                                 │
│       │                │ (如果从 queued 直接 abort)                        │
│       │                ▼                                                 │
│       │            aborted                                               │
│       │                                                                  │
│       ▼                                                                  │
│    preflight_compacting ──► (失败) ──► failed                            │
│       │                                                                  │
│       ▼                                                                  │
│    memory_flushing ────────► (失败) ──► failed                            │
│       │                                                                  │
│       ▼                                                                  │
│    running ───────────────────────────────────────────────┐              │
│       │                                                    │              │
│       ├──► completed (正常完成)                              │              │
│       ├──► failed    (运行失败)                              │              │
│       └──► aborted   (用户中止 / 重启中止)                    │              │
│                                                                          │
├──────────────────────────────────────────────────────────────────────────┤
│                                                                          │
│  LIFECYCLE EVENT STATE:                                                   │
│                                                                          │
│    [start] ──────┐                                                       │
│                  ├──► [fallback_step] ──► [fallback_step] ──► ...        │
│                  │         │                   │                         │
│                  │         └─── (fallback 激活) ──► [fallback]           │
│                  │                                      │                │
│                  │                   (fallback 清除) ◄──┘                │
│                  │                        │                              │
│                  │                        ▼                              │
│                  │                   [fallback_cleared]                  │
│                  │                        │                              │
│                  ├──► (正常执行) ─────────┘                               │
│                  │         │                                              │
│                  │         ▼                                              │
│                  │      [end] ◄── 正常终止                                │
│                  │                                                       │
│                  └──► [error] ──► (系统处理) ──► 不再有后续事件           │
│                                                                          │
└──────────────────────────────────────────────────────────────────────────┘
```

---

## 第四部分：事件系统（9 条流 Streams）

### 4.1 九条事件流

OpenClaw 定义了九条事件流，每条流有独立的数据 schema 和用途：

| # | 流名称 | 用途 | 典型数据 |
|---|--------|------|---------|
| 1 | `lifecycle` | Agent 运行生命周期 | `phase: "start"/"end"/"error"/"fallback_step"/"fallback"/"fallback_cleared"` |
| 2 | `item` | 任务项进度（工具调用等） | `itemId, kind, title, name, phase, status, summary` |
| 3 | `tool` | 工具调用事件 | `name, phase("start"/"update"/"end"), args` |
| 4 | `plan` | Agent 计划更新 | `phase, title, explanation, steps, source` |
| 5 | `approval` | 审批请求事件 | `phase, kind, status, approvalId, command, host, scope` |
| 6 | `command_output` | 命令执行输出 | `itemId, phase, name, output, exitCode, durationMs, cwd` |
| 7 | `patch` | 文件修改摘要 | `itemId, phase, name, added[], modified[], deleted[]` |
| 8 | `compaction` | 上下文压缩事件 | `phase("start"/"end"), completed, messages[]` |
| 9 | `assistant` | Assistant 文本输出 | `text` (LLM 输出的可见文本片段) |

**事件专用发射器**（每个流有对应的发射函数）：

```javascript
emitAgentEvent({ runId, stream: "lifecycle", data: { phase, startedAt } })
emitAgentItemEvent({ runId, data: { itemId, kind, phase, ... } })
emitAgentPlanEvent({ runId, data: { phase, title, steps, ... } })
emitAgentApprovalEvent({ runId, data: { phase, kind, approvalId, ... } })
emitAgentCommandOutputEvent({ runId, data: { phase, name, output, ... } })
emitAgentPatchSummaryEvent({ runId, data: { phase, name, added, ... } })
// compaction 和 assistant 通过 emitAgentEvent 直接发出
```

### 4.2 事件发射流程

`emitAgentEvent` 函数的完整执行流程（`agent-events-DVSiKwui.js` 第 56-72 行）：

```
emitAgentEvent(event)
    │
    ├── 1. 获取全局状态单例 (getAgentEventState)
    │
    ├── 2. 计算序列号 (seq)
    │      seq = (state.seqByRun.get(event.runId) ?? 0) + 1
    │      state.seqByRun.set(event.runId, seq)
    │      → 每个 runId 独立递增，从 1 开始
    │
    ├── 3. 更新 lastActiveAt
    │      const context = state.runContextById.get(event.runId)
    │      if (context) context.lastActiveAt = Date.now()
    │      → 记录最后一次活跃时间，用于过时 context 清理
    │
    ├── 4. 决定 sessionKey
    │      const isControlUiVisible = context?.isControlUiVisible ?? true
    │      const sessionKey = (isControlUiVisible || event.stream === "lifecycle")
    │          ? eventSessionKey ?? context?.sessionKey
    │          : undefined
    │      → Control UI 可见 或 lifecycle 事件 → 附带 sessionKey
    │      → 否则 → sessionKey 为 undefined（隐私保护）
    │
    ├── 5. 附加 seq 和 ts
    │      const enriched = { ...event, sessionKey, seq, ts: Date.now() }
    │
    └── 6. 通知所有监听器
           notifyListeners(state.listeners, enriched)
           → 同步调用所有已注册的 listener
```

### 4.3 事件监听与取消

```javascript
function onAgentEvent(listener) {
    return registerListener(getAgentEventState().listeners, listener);
}
```

`registerListener` 返回一个取消订阅函数，调用它可以移除监听器。

在 Agent Runner 中，事件监听用于构建 assistant text bridge（第 200-221 行）：

```javascript
const rawUnsubscribe = onAgentEvent((evt) => {
    if (evt.runId !== params.runId || evt.stream !== "assistant") return;
    if (params.suppressed) return;
    const text = typeof evt.data.text === "string" ? evt.data.text : void 0;
    if (text === void 0 || text === lastText) return;
    lastText = text;
    delivery = delivery.then(() => deliver(text)).catch(() => void 0);
});
return { unsubscribe() { ... }, async drain() { await delivery; } };
```

### 4.4 过时 Run Context 的清理

为防止生命周期事件丢失导致的 context 泄漏，系统提供了 `sweepStaleRunContexts`（第 42-54 行）：

```javascript
function sweepStaleRunContexts(maxAgeMs = 1800 * 1e3) {
    const state = getAgentEventState();
    const now = Date.now();
    let swept = 0;
    for (const [runId, ctx] of state.runContextById.entries()) {
        const lastSeen = ctx.lastActiveAt ?? ctx.registeredAt;
        if ((lastSeen ? now - lastSeen : Infinity) > maxAgeMs) {
            state.runContextById.delete(runId);
            state.seqByRun.delete(runId);
            swept++;
        }
    }
    return swept;
}
```

默认 TTL 为 30 分钟（1800 秒）。超过此时间没有活跃事件的 Run Context 将被自动清理。

### 4.5 Lifecycle Backstop（生命周期安全网）

`createEmbeddedLifecycleTerminalBackstop`（第 844-881 行）是事件系统的重要补充机制。它的设计意图是：如果 PI Runtime 内部因为任何原因没有发出 `"end"` 或 `"error"` 生命周期事件，backstop 会在 Agent Runner 层面确保一个终止事件被发出。

工作流程：

```
PI Runtime 执行中
    │
    ├── onAgentEvent (PI 内部发出的事件)
    │       └── backstop.note(evt)
    │               ├── 如果是 lifecycle "start" → 记录 startedAt
    │               └── 如果是 lifecycle "end"/"error" → terminalEmitted = true
    │
    ├── PI Runtime 正常返回
    │       └── backstop.emit("end", result)
    │               └── 如果 terminalEmitted === false → 发出 "end" 事件
    │
    └── PI Runtime 抛出异常
            └── backstop.emit("error", err)
                    └── 如果 terminalEmitted === false → 发出 "error" 事件
```

这确保了 **UI 永远不会显示 "running" 状态直到永远**——即使底层 SDK 挂起，backstop 也会在 Runner 级别的 finally 块中发出终止事件。

### 4.6 事件系统的完整数据流

将上述所有组件组合起来，完整的事件数据流如下：

```
┌─────────────────────────────────────────────────────────────────┐
│                      EVENT DATA FLOW                            │
├─────────────────────────────────────────────────────────────────┤
│                                                                 │
│  PI Runtime / CLI Runner                                        │
│       │                                                         │
│       │ emitAgentEvent({ runId, stream, data })                 │
│       ▼                                                         │
│  ┌──────────────────────────────────────────────────────────┐   │
│  │              emitAgentEvent (agent-events.js)            │   │
│  │                                                          │   │
│  │  ① compute seq (per-runId, monotonic)                   │   │
│  │  ② update lastActiveAt                                  │   │
│  │  ③ decide sessionKey (privacy gating)                   │   │
│  │  ④ attach seq & ts                                      │   │
│  │  ⑤ notifyListeners                                      │   │
│  └──────────────────────┬───────────────────────────────────┘   │
│                         │                                       │
│          ┌──────────────┼──────────────┐                        │
│          ▼              ▼              ▼                        │
│     Listener A    Listener B    Listener C                       │
│   (assistant     (compaction   (UI progress                     │
│    text bridge)   notification)  renderer)                       │
│          │              │              │                         │
│          ▼              ▼              ▼                         │
│     ┌────────┐   ┌──────────┐   ┌──────────┐                   │
│     │deliver │   │send      │   │update    │                   │
│     │text to │   │compaction│   │progress  │                   │
│     │channel │   │notice    │   │indicator │                   │
│     └────────┘   └──────────┘   └──────────┘                   │
│                                                                 │
│  Lifecycle Backstop (safety net)                                │
│       │                                                         │
│       │ backstop.note(evt) ─── 追踪 PI 自身发出的事件             │
│       │ backstop.emit(phase) ─── 补充缺失的终止事件              │
│       ▼                                                         │
│     emitAgentEvent({ stream: "lifecycle", phase: "end"/"error" })│
│                                                                 │
│  Stale Context Sweeper (garbage collection)                     │
│       │                                                         │
│       │ sweepStaleRunContexts(maxAgeMs = 30min)                  │
│       │   ─── 清理超过 TTL 的孤立 Run Context                    │
│       ▼                                                         │
│     state.runContextById.delete(runId)                           │
│     state.seqByRun.delete(runId)                                │
│                                                                 │
└─────────────────────────────────────────────────────────────────┘
```

---

## 总结

本章从四个维度系统分析了 OpenClaw Agent 的生命周期与状态机：

1. **七层架构** —— Agent Runner 编排层、Agent Runtime 适配层、Agent Harness Runtime 网关层、Agent Events 事件总线、Agent Scope 会话状态、Agent Limits 资源控制、Agent Failure 容错自愈；
2. **四阶段生命周期** —— Queue Check（队列策略）、Preflight Compaction（预检压缩）、Memory Flush（记忆刷新）、Agent Turn Execution（代理执行）+ Result Processing（结果处理）；
3. **三层状态机** —— ReplyOperation 阶段状态机（queued -> preflight_compacting -> memory_flushing -> running -> completed/failed/aborted）、会话 Run 状态机（none -> running -> done/failed/killed）、生命周期事件状态机（start -> fallback_step/fallback/fallback_cleared/end/error）；
4. **九流事件系统** —— lifecycle / item / tool / plan / approval / command_output / patch / compaction / assistant，以及事件发射流程、监听机制、lifecycle backstop 安全网和过时 context 清理。

在下一章（二、Agent生命周期（下））中，我们将深入分析模型回退（Model Fallback）机制、会话管理、上下文压缩策略以及诊断追踪系统的实现细节。
# 二、Agent生命周期（下）：错误处理、Fallback与会话恢复

本章深入剖析 OpenClaw 的 Agent 错误处理机制、模型 Fallback 系统与会话恢复策略。整个错误处理架构围绕一个核心函数 `runAgentTurnWithFallback` 展开，该函数位于 `agent-runner.runtime-B9LwhObT.js`，通过一个 `while(true)` 无限循环将所有错误按类型分级处理，确保 Agent 在任何异常场景下都能给出合理的用户反馈或自动恢复。

---

## 2.1 核心循环错误处理（`runAgentTurnWithFallback`）

### 2.1.1 `while(true)` 循环结构

整个 Agent 执行流程被包裹在一个 `while(true)` 循环中（第 1149 行），外部是一个巨大的 `try/catch` 块（`catch` 从第 1642 行开始）。正常执行路径在 `try` 块内部通过 `break` 退出循环，而异常路径则根据错误类型选择 `continue`（重试）或 `return`（终止并返回用户消息）。

```
while (true) try {
    // === 正常执行路径 ===
    // 1. 调用 runWithModelFallback（模型 Fallback 包装器）
    // 2. 处理嵌入式错误（role_ordering, context overflow）
    // 3. break 退出循环

    const fallbackResult = await runWithModelFallback({ ... });
    runResult = fallbackResult.result;
    // ...
    break;

} catch (err) {
    // === 错误分类处理路径 ===
    // 按优先级从高到低分派处理
}
```

### 2.1.2 错误分类层级（完整优先级链）

错误在 `catch` 块中按以下优先级进行匹配和分派，顺序至关重要：

#### 第一级：LiveSessionModelSwitchError -- 模型切换重试（最多 2 次）

```javascript
if (err instanceof LiveSessionModelSwitchError) {
    liveModelSwitchRetries += 1;
    if (liveModelSwitchRetries > 2) {
        defaultRuntime.error(`Live model switch failed after 2 retries...`);
        // 返回用户错误消息
        return {
            kind: "final",
            payload: markAgentRunFailureReplyPayload({ text: ... })
        };
    }
    // 将新的 provider/model 应用到当前 run，继续循环重试
    applyLiveModelSwitchToRun(params.followupRun.run, err);
    if (runnableRun !== params.followupRun.run)
        applyLiveModelSwitchToRun(runnableRun, err);
    if (effectiveRun !== runnableRun && effectiveRun !== params.followupRun.run)
        applyLiveModelSwitchToRun(effectiveRun, err);
    fallbackProvider = err.provider;
    fallbackModel = err.model;
    continue;  // 回到 while(true) 顶部重试
}
```

`LiveSessionModelSwitchError` 定义在 `model-fallback-DPQE5cWR.js` 第 156-165 行：

```javascript
var LiveSessionModelSwitchError = class extends Error {
    constructor(selection) {
        super(`Live session model switch requested: ${selection.provider}/${selection.model}`);
        this.name = "LiveSessionModelSwitchError";
        this.provider = selection.provider;
        this.model = selection.model;
        this.authProfileId = selection.authProfileId;
        this.authProfileIdSource = selection.authProfileIdSource;
    }
};
```

这种错误发生在运行时（如 PI Agent 执行过程中）检测到需要动态切换模型的场景。重试上限为 2 次，超过后返回用户可见的错误消息。

**用户消息示例（verbose 开启时）：**
`"⚠️ Agent failed before reply: model switch could not be completed. The requested model may be temporarily unavailable. Please try again shortly."`

**用户消息示例（内部管理通道）：**
`"⚠️ Agent failed before reply: model switch could not be completed. The requested model may be temporarily unavailable. Logs: openclaw logs --follow"`

#### 第二级：用户中止 -- 静默退出

```javascript
if (isReplyOperationUserAbort(params.replyOperation)) return {
    kind: "final",
    payload: { text: SILENT_REPLY_TOKEN }
};
```

`isReplyOperationUserAbort`（第 838-840 行）检查 `replyOperation` 的结果是否为 `{ kind: "aborted", code: "aborted_by_user" }`。如果是用户主动中止，则返回静默令牌（`NO_REPLY`），不向用户展示任何错误消息。

#### 第三级：Gateway 重启 -- 重启生命周期

先检查是否是 `aborted_for_restart` 类型的操作中止：

```javascript
if (isReplyOperationRestartAbort(params.replyOperation)) return {
    kind: "final",
    payload: markAgentRunFailureReplyPayload({ text: buildRestartLifecycleReplyText() })
};
```

然后递归检查错误链中是否包含 `GatewayDrainingError` 或 `CommandLaneClearedError`：

```javascript
const restartLifecycleError = resolveRestartLifecycleError(err);
if (restartLifecycleError instanceof GatewayDrainingError) {
    params.replyOperation?.fail("gateway_draining", restartLifecycleError);
    return {
        kind: "final",
        payload: markAgentRunFailureReplyPayload({ text: buildRestartLifecycleReplyText() })
    };
}
if (restartLifecycleError instanceof CommandLaneClearedError) {
    params.replyOperation?.fail("command_lane_cleared", restartLifecycleError);
    return {
        kind: "final",
        payload: markAgentRunFailureReplyPayload({ text: buildRestartLifecycleReplyText() })
    };
}
```

`resolveRestartLifecycleError`（第 825-837 行）会递归遍历错误链（包括 `FallbackSummaryError` 的 `attempts` 和 JavaScript 原生的 `cause` 属性）：

```javascript
function resolveRestartLifecycleError(err) {
    const pending = [err];
    const seen = new Set();
    let pendingIndex = 0;
    while (pendingIndex < pending.length) {
        const candidate = pending[pendingIndex++];
        if (!candidate || seen.has(candidate)) continue;
        seen.add(candidate);
        if (candidate instanceof GatewayDrainingError ||
            candidate instanceof CommandLaneClearedError)
            return candidate;
        if (isFallbackSummaryError(candidate))
            for (const attempt of candidate.attempts)
                pending.push(attempt.error);
        if (candidate instanceof Error && "cause" in candidate)
            pending.push(candidate.cause);
    }
}
```

返回的文本由 `buildRestartLifecycleReplyText()`（第 822-823 行）生成：

```javascript
function buildRestartLifecycleReplyText() {
    return "⚠️ Gateway is restarting. Please wait a few seconds and try again.";
}
```

#### 第四级：Compaction 失败 -- 重置会话

```javascript
if (isCompactionFailure && !didResetAfterCompactionFailure &&
    await params.resetSessionAfterCompactionFailure(message)) {
    didResetAfterCompactionFailure = true;
    params.replyOperation?.fail("run_failed", err);
    return {
        kind: "final",
        payload: markAgentRunFailureReplyPayload({
            text: buildContextOverflowRecoveryText({
                duringCompaction: true,
                cfg: runtimeConfig,
                agentId: params.followupRun.run.agentId,
                primaryProvider: params.followupRun.run.provider,
                primaryModel: params.followupRun.run.model,
                activeSessionEntry: params.getActiveSessionEntry()
            })
        })
    };
}
```

注意 `didResetAfterCompactionFailure` 确保每个 Agent Turn 只尝试一次会话重置，防止无限重置循环。

`isCompactionFailureError` 定义在 `errors-Da1M4x-G.js` 第 148-154 行：

```javascript
function isCompactionFailureError(errorMessage) {
    if (!errorMessage) return false;
    const lower = normalizeLowercaseStringOrEmpty(errorMessage);
    if (!(lower.includes("summarization failed") ||
          lower.includes("auto-compaction") ||
          lower.includes("compaction failed") ||
          lower.includes("compaction"))) return false;
    if (isLikelyContextOverflowError(errorMessage)) return true;
    return lower.includes("context overflow");
}
```

#### 第五级：Provider 请求错误 -- 返回 Provider 级错误消息

```javascript
if (providerRequestError) {
    params.replyOperation?.fail("run_failed", err);
    return {
        kind: "final",
        payload: markAgentRunFailureReplyPayload({
            text: providerRequestError.userMessage
        })
    };
}
```

`classifyProviderRequestError` 定义在第 310-321 行，用于识别"对话状态不一致"类型的错误：

```javascript
function classifyProviderRequestError(err) {
    const technicalMessage = formatErrorMessage(err);
    if (isProviderConversationStateErrorMessage(technicalMessage)) return {
        code: "provider_conversation_state_error",
        userMessage: PROVIDER_CONVERSATION_STATE_ERROR_USER_MESSAGE,
        technicalMessage
    };
}
```

`isProviderConversationStateErrorMessage` 会匹配以下模式：
- `"custom tool call output is missing" + "call id"`
- `"toolresult" + "tooluse" + "exceeds the number" + "previous turn"`
- `"function call turn comes immediately after"`
- `"incorrect role information"`
- `"roles must alternate"`

**用户消息：** `"⚠️ The model provider rejected the conversation state. Please try again, or use /new to start a fresh session."`

#### 第六级：瞬时 HTTP 错误 -- 延迟 2500ms 重试一次

```javascript
if (isTransientHttp && !didRetryTransientHttpError) {
    didRetryTransientHttpError = true;
    defaultRuntime.error(
        `Transient HTTP provider error before reply (${message}). ` +
        `Retrying once in ${TRANSIENT_HTTP_RETRY_DELAY_MS}ms.`);
    await new Promise((resolve) => {
        setTimeout(resolve, TRANSIENT_HTTP_RETRY_DELAY_MS);
    });
    continue;
}
```

`TRANSIENT_HTTP_RETRY_DELAY_MS = 2500`（第 954 行），即 2.5 秒后重试一次。`didRetryTransientHttpError` 确保只重试一次。

瞬时 HTTP 错误码定义在 `errors-Da1M4x-G.js` 第 169-180 行：

```javascript
const TRANSIENT_HTTP_ERROR_CODES = new Set([
    499, 500, 502, 503, 504, 521, 522, 523, 524, 529
]);
```

#### 第七级到第十级：Billing / RateLimit / ContextOverflow / Generic Fallback -- 统一消息分发

```javascript
const genericFallbackText = params.isHeartbeat
    ? HEARTBEAT_EXTERNAL_RUN_FAILURE_TEXT
    : GENERIC_EXTERNAL_RUN_FAILURE_TEXT;

const userVisibleFallbackText = resolveExternalRunFailureTextForConversation({
    text: isBilling
        ? BILLING_ERROR_USER_MESSAGE
        : isRateLimit && !isOverloadedErrorMessage(message)
            ? buildRateLimitCooldownMessage(err)
            : rateLimitOrOverloadedCopy
                ? rateLimitOrOverloadedCopy
                : isContextOverflow
                    ? "⚠️ Context overflow — prompt too large for this model. " +
                      "Try a shorter message or a larger-context model."
                    : shouldSurfaceToControlUi
                        ? `⚠️ Agent failed before reply: ${trimmedMessage}.\n` +
                          `Logs: openclaw logs --follow`
                        : externalRunFailureReply?.text ?? genericFallbackText,
    sessionCtx: params.sessionCtx,
    isGenericRunnerFailure:
        externalRunFailureReply?.isGenericRunnerFailure ?? false,
    cfg: params.followupRun.run.config
});
```

这是一个紧凑的条件链，按优先级选择用户可见消息：

1. **Billing 错误** -> `BILLING_ERROR_USER_MESSAGE`
2. **Rate Limit**（非 Overloaded）-> `buildRateLimitCooldownMessage(err)`
3. **Overloaded 错误** -> `formatRateLimitOrOverloadedErrorCopy(message)` 的结果
4. **Context Overflow** -> 固定的上下文溢出提示
5. **内部管理控制 UI** -> 详细错误信息 + 日志路径
6. **自定义外部失败回复** -> `buildExternalRunFailureReply` 的结果
7. **泛用回退** -> Heartbeat 用 `HEARTBEAT_EXTERNAL_RUN_FAILURE_TEXT`，否则用 `GENERIC_EXTERNAL_RUN_FAILURE_TEXT`

### 2.1.3 嵌入式执行中的错误处理（try 块内部）

在 `try` 块内部，`runWithModelFallback` 成功返回后，还会对嵌入式 Agent 产生的 `meta.error` 做二次检查：

```javascript
const embeddedError = runResult.meta?.error;

// Context Overflow 在嵌入式执行中发生 -> 重置会话
if (embeddedError && isContextOverflowError(embeddedError.message) &&
    !didResetAfterCompactionFailure &&
    await params.resetSessionAfterCompactionFailure(embeddedError.message)) {
    didResetAfterCompactionFailure = true;
    params.replyOperation?.fail("run_failed", embeddedError);
    return {
        kind: "final",
        payload: markAgentRunFailureReplyPayload({
            text: buildContextOverflowRecoveryText({ ... })
        })
    };
}

// Role Ordering 冲突 -> Provider 状态错误消息
if (embeddedError?.kind === "role_ordering") {
    const providerRequestError = classifyProviderRequestError(embeddedError);
    params.replyOperation?.fail("run_failed", embeddedError);
    const embeddedErrorText = formatErrorMessage(embeddedError)
        .replace(/\.\s*$/, "");
    return {
        kind: "final",
        payload: markAgentRunFailureReplyPayload({
            text: shouldSurfaceToControlUi
                ? `⚠️ Agent failed before reply: ${embeddedErrorText}.\n` +
                  `Logs: openclaw logs --follow`
                : providerRequestError?.userMessage ??
                  "⚠️ The model provider rejected the conversation state. " +
                  "Please try again, or use /new to start a fresh session."
        })
    };
}
```

---

## 2.2 错误到用户消息的映射表

OpenClaw 为每种失败类型都提供了精确的用户可见错误消息。以下汇总了完整的 10 种消息类型及其生成逻辑。

### 2.2.1 Billing 错误

**触发条件：**
- `isBillingErrorMessage(message)` 返回 `true`
- 或 `isFallbackSummaryError(err) && isPureBillingSummary(err)` 返回 `true`

`isPureBillingSummary` 定义在 `agent-runner.runtime-B9LwhObT.js` 第 607-609 行：

```javascript
function isPureBillingSummary(err) {
    return isFallbackSummaryError(err) &&
           err.attempts.length > 0 &&
           err.attempts.every((attempt) => attempt.reason === "billing");
}
```

**用户消息：**
```
⚠️ A billing error occurred.
```
（来自 `sanitize-user-facing-text-BNhO6xXL.js` 的导出常量 `BILLING_ERROR_USER_MESSAGE`）

### 2.2.2 Rate Limit 错误（含冷却倒计时）

**触发条件：**
- `isFallbackSummaryError(err) && isPureTransientRateLimitSummary(err)`
- 或 `isRateLimitErrorMessage(message)`

**用户消息（有冷却到期时间时）：**

由 `buildRateLimitCooldownMessage`（第 565-577 行）计算：

```javascript
function buildRateLimitCooldownMessage(err) {
    // 先检查是否是 Codex 用量限制
    const codexUsageLimitMessage = extractCodexUsageLimitErrorMessage(err);
    if (codexUsageLimitMessage) return codexUsageLimitMessage;

    if (!isFallbackSummaryError(err))
        return "⚠️ All models are temporarily rate-limited. " +
               "Please try again in a few minutes.";

    const expiry = err.soonestCooldownExpiry;
    const now = Date.now();
    if (typeof expiry === "number" && expiry > now) {
        const secsLeft = Math.max(1, Math.ceil((expiry - now) / 1e3));
        if (secsLeft <= 60)
            return `⚠️ Rate-limited — ready in ~${secsLeft}s. Please wait a moment.`;
        return `⚠️ Rate-limited — ready in ~${Math.ceil(secsLeft / 60)} min. ` +
               `Please try again shortly.`;
    }
    return "⚠️ All models are temporarily rate-limited. " +
           "Please try again in a few minutes.";
}
```

**消息示例：**
- `"⚠️ Rate-limited — ready in ~30s. Please wait a moment."`
- `"⚠️ Rate-limited — ready in ~3 min. Please try again shortly."`
- `"⚠️ All models are temporarily rate-limited. Please try again in a few minutes."`

### 2.2.3 Context Overflow 错误（含恢复提示）

**触发条件：**
- `isLikelyContextOverflowError(message)` 返回 `true`

**用户消息（基本）：**
```
⚠️ Context overflow — prompt too large for this model.
Try a shorter message or a larger-context model.
```

**用户消息（会话重置后，由 `buildContextOverflowRecoveryText` 生成）：**

```javascript
function buildContextOverflowRecoveryText(params) {
    return (
        params.duringCompaction
            ? "⚠️ Context limit exceeded during compaction. " +
              "I've reset our conversation to start fresh - please try again."
            : "⚠️ Context limit exceeded. " +
              "I've reset our conversation to start fresh - please try again."
    ) + (
        resolveHeartbeatBleedHint({ ... }) ??
        CONTEXT_OVERFLOW_RESET_HINT
    );
}
```

附加提示常量（第 741 行）：
```
To prevent this, increase your compaction buffer by setting
`agents.defaults.compaction.reserveTokensFloor` to 20000
or higher in your config.
```

### 2.2.4 Provider 状态错误（对话状态被拒绝）

**触发条件：**
- `classifyProviderRequestError` 返回非空结果

**用户消息：**
```
⚠️ The model provider rejected the conversation state.
Please try again, or use /new to start a fresh session.
```

### 2.2.5 缺少 API Key

**触发条件：**
- `buildMissingApiKeyFailureText(message)` 返回非空结果（匹配 `No API key found for provider "..."` 模式）

**用户消息：**
```
⚠️ Missing API key for provider "anthropic".
Configure the gateway auth for that provider, then try again.
```
或对于 OpenAI Codex OAuth 场景：
```
⚠️ Missing API key for OpenAI on the gateway.
Use `openai/gpt-5.5` with the Codex OAuth profile,
or set `OPENAI_API_KEY` for direct OpenAI API-key runs.
```

### 2.2.6 OAuth 刷新失败

**触发条件：**
- `classifyOAuthRefreshFailure(normalizedMessage)` 返回非空

**用户消息：**
```
⚠️ Model login expired on the gateway for anthropic.
Re-auth with `openclaw auth login --provider anthropic`,
then try again.
```

### 2.2.7 CLI 后端超时

**触发条件：**
- `buildCliBackendTimeoutFailureText(message)` 匹配超时正则

**用户消息示例：**
```
⚠️ CLI subprocess (routing anthropic/claude-sonnet-4-5):
timed out after 120s (no-output stall).
The gateway may still be healthy. Try `/new`,
a lighter model, or raise `agents.defaults.timeoutSeconds`
and the watchdog `noOutputTimeoutMs` entries under
`cliBackends.<your-runtime>`.
```

### 2.2.8 Compaction 失败（会话重置后）

**触发条件：**
- `isCompactionFailure && !didResetAfterCompactionFailure && await params.resetSessionAfterCompactionFailure(message)`

**用户消息：**
```
⚠️ Context limit exceeded during compaction.
I've reset our conversation to start fresh - please try again.
```

### 2.2.9 Gateway 重启 / 生命周期重启

**触发条件：**
- `GatewayDrainingError` 或 `CommandLaneClearedError`

**用户消息：**
```
⚠️ Gateway is restarting. Please wait a few seconds and try again.
```

### 2.2.10 泛用失败（Generic Fallback）

**触发条件：**
- 所有以上类型都不匹配

**用户消息：**

普通 turn：
```
⚠️ Something went wrong while processing your request.
Please try again, or use /new to start a fresh session.
```

Heartbeat turn：
```
⚠️ Heartbeat check failed before it could produce an update.
The main chat session remains available.
```

### 2.2.11 上下文溢出（无可恢复内容）

当 `runResult` 中没有有效文本但存在 `embeddedError` 时（第 1750-1758 行）：

```
⚠️ Context overflow — this conversation is too large for the model.
Use /new to start a fresh session.
```

### 2.2.12 内部管理 UI 的错误消息

当 `shouldSurfaceToControlUi` 为 `true` 时（即消息来自内部管理通道），错误消息会附带更多技术细节：

```
⚠️ Agent failed before reply: <错误详情>.
Logs: openclaw logs --follow
```

---

## 2.3 会话重置与恢复机制

### 2.3.1 三种重置场景

OpenClaw 支持三种会话重置场景，分别对应不同的错误类型：

#### 场景一：Context Overflow 重置

在 `try` 块内部（`runWithModelFallback` 成功后）检测到 `meta.error` 为 context overflow：

```javascript
if (embeddedError && isContextOverflowError(embeddedError.message) &&
    !didResetAfterCompactionFailure &&
    await params.resetSessionAfterCompactionFailure(embeddedError.message)) {
    didResetAfterCompactionFailure = true;
    params.replyOperation?.fail("run_failed", embeddedError);
    return {
        kind: "final",
        payload: markAgentRunFailureReplyPayload({
            text: buildContextOverflowRecoveryText({ ... })
        })
    };
}
```

#### 场景二：Compaction 失败重置

在 `catch` 块中检测到 compaction 失败：

```javascript
if (isCompactionFailure && !didResetAfterCompactionFailure &&
    await params.resetSessionAfterCompactionFailure(message)) {
    didResetAfterCompactionFailure = true;
    params.replyOperation?.fail("run_failed", err);
    return {
        kind: "final",
        payload: markAgentRunFailureReplyPayload({
            text: buildContextOverflowRecoveryText({
                duringCompaction: true,
                cfg: runtimeConfig,
                agentId: params.followupRun.run.agentId,
                primaryProvider: params.followupRun.run.provider,
                primaryModel: params.followupRun.run.model,
                activeSessionEntry: params.getActiveSessionEntry()
            })
        })
    };
}
```

两者使用同一个 `resetSessionAfterCompactionFailure` 函数，但 Compaction 失败时 `duringCompaction` 为 `true`，消息以 `"⚠️ Context limit exceeded during compaction."` 开头。

#### 场景三：Role Ordering 冲突重置

通过 `resetSessionAfterRoleOrderingConflict` 处理，额外的不同之处是它会清理旧的转录文件：

```javascript
const resetSessionAfterRoleOrderingConflict = async (reason) =>
    resetSession({
        failureLabel: "role ordering conflict",
        buildLogMessage: (nextSessionId) =>
            `Role ordering conflict (${reason}). ` +
            `Restarting session ${sessionKey} -> ${nextSessionId}.`,
        cleanupTranscripts: true  // 清理旧转录文件
    });
```

### 2.3.2 `resetSessionAfterCompactionFailure` 的完整逻辑

入口点在第 4514-4517 行：

```javascript
const resetSessionAfterCompactionFailure = async (reason) =>
    resetSession({
        failureLabel: "compaction failure",
        buildLogMessage: (nextSessionId) =>
            `Auto-compaction failed (${reason}). ` +
            `Restarting session ${sessionKey} -> ${nextSessionId} and retrying.`
    });
```

内部的 `resetSession` 调用 `resetReplyRunSession`（第 2825-2901 行），这是核心的会话重置逻辑：

```javascript
async function resetReplyRunSession(params) {
    // 1. 验证前置条件
    if (!params.sessionKey || !params.activeSessionStore ||
        !params.storePath) return false;
    const prevEntry = params.activeSessionStore[params.sessionKey] ??
                      params.activeSessionEntry;
    if (!prevEntry) return false;

    // 2. 生成新的 session ID 和时间戳
    const prevSessionId = params.options.cleanupTranscripts
        ? prevEntry.sessionId : void 0;
    const nextSessionId = deps.generateSecureUuid();
    const now = Date.now();

    // 3. 构建新的 session entry
    const nextEntry = {
        ...prevEntry,
        sessionId: nextSessionId,
        updatedAt: now,
        sessionStartedAt: now,
        usageFamilyKey: prevEntry.usageFamilyKey ?? params.sessionKey,
        // 保留 usage family 历史
        usageFamilySessionIds: Array.from(new Set([
            ...prevEntry.usageFamilySessionIds ?? [],
            prevEntry.sessionId,
            nextSessionId
        ])),
        lastInteractionAt: now,
        // 清除以下状态字段
        systemSent: false,
        abortedLastRun: false,
        modelProvider: void 0,
        model: void 0,
        inputTokens: void 0,
        outputTokens: void 0,
        totalTokens: void 0,
        totalTokensFresh: false,
        estimatedCostUsd: void 0,
        cacheRead: void 0,
        cacheWrite: void 0,
        contextTokens: void 0,
        systemPromptReport: void 0,
        fallbackNoticeSelectedModel: void 0,
        fallbackNoticeActiveModel: void 0,
        fallbackNoticeReason: void 0
    };

    // 4. 生成新的会话转录文件路径
    const agentId = resolveAgentIdFromSessionKey(params.sessionKey);
    const nextSessionFile = resolveSessionTranscriptPath(
        nextSessionId, agentId, params.messageThreadId);
    nextEntry.sessionFile = nextSessionFile;

    // 5. 持久化新 entry
    params.activeSessionStore[params.sessionKey] = nextEntry;
    try {
        await deps.updateSessionStore(params.storePath, (store) => {
            store[params.sessionKey] = nextEntry;
        });
    } catch (err) {
        deps.error(`Failed to persist session reset after ` +
                   `${params.options.failureLabel}: ${String(err)}`);
    }

    // 6. 重放最近的用户/助手消息到新转录文件
    await replayRecentUserAssistantMessages({
        sourceTranscript: prevEntry.sessionFile,
        targetTranscript: nextSessionFile,
        newSessionId: nextSessionId
    });

    // 7. 更新 followup run 的 session 引用
    params.followupRun.run.sessionId = nextSessionId;
    params.followupRun.run.sessionFile = nextSessionFile;
    deps.refreshQueuedFollowupSession({
        key: params.queueKey,
        previousSessionId: prevEntry.sessionId,
        nextSessionId,
        nextSessionFile
    });

    // 8. 清理旧的转录文件（仅在 role ordering 场景）
    if (params.options.cleanupTranscripts && prevSessionId) {
        const transcriptCandidates = new Set();
        // 收集旧转录文件路径并删除
        const resolved = resolveSessionFilePath(prevSessionId, prevEntry, ...);
        if (resolved) transcriptCandidates.add(resolved);
        transcriptCandidates.add(
            resolveSessionTranscriptPath(prevSessionId, agentId));
        for (const candidate of transcriptCandidates)
            try { fs.unlinkSync(candidate); } catch {}
    }

    return true;
}
```

**关键行为总结：**

| 操作 | 说明 |
|------|------|
| 生成新 Session ID | 使用 `generateSecureUuid()` |
| 重置使用统计 | 清空 `inputTokens`, `outputTokens`, `totalTokens`, `contextTokens`, `estimatedCostUsd` 等 |
| 保留使用历史 | `usageFamilySessionIds` 数组保留所有历史 session ID |
| 重放最近对话 | `replayRecentUserAssistantMessages` 将旧转录中的关键消息复制到新转录文件 |
| 清理旧转录 | 仅在 `cleanupTranscripts=true`（role ordering 场景）时删除旧文件 |
| 防重复重置 | 通过 `didResetAfterCompactionFailure` 标志位确保每个 turn 只重置一次 |

---

## 2.4 模型 Fallback 系统

### 2.4.1 `runWithModelFallback` 包装器

`runWithModelFallback` 是整个 Fallback 系统的核心调度器，定义在 `model-fallback-DPQE5cWR.js` 第 786-1114 行。它的职责是：给定一个 primary provider/model 和 fallback 候选列表，依次尝试直至成功或全部耗尽。

```javascript
async function runWithModelFallback(params) {
    // 1. 解析 Fallback 候选列表
    const candidates = resolveFallbackCandidates({
        cfg: params.cfg,
        provider: params.provider,
        model: params.model,
        fallbacksOverride: params.fallbacksOverride,
        manifestPlugins: params.manifestPlugins
    });

    // 2. 加载 Auth Profile 运行时（用于冷却检测）
    const authRuntime = ...;
    const authStore = ...;

    const attempts = [];
    let lastError;
    const cooldownProbeUsedProviders = new Set();

    // 3. 遍历候选列表
    for (let i = 0; i < candidates.length; i += 1) {
        const candidate = candidates[i];

        // 3a. 确保 Agent Harness 可用
        await assertModelFallbackCandidateHarnessAvailable({ ... });

        // 3b. 冷却期检测与探针逻辑
        if (authRuntime && authStore) {
            const profileIds = authRuntime.resolveAuthProfileOrder({ ... });
            const isAnyProfileAvailable = profileIds.some((id) =>
                !authRuntime.isProfileInCooldown(authStore, id, ...));

            if (profileIds.length > 0 && !isAnyProfileAvailable) {
                const decision = resolveCooldownDecision({ ... });

                // 挂起通道 (suspend lanes)
                if (decision.type === "suspend_lanes") {
                    attempts.push({ ... });
                    suspendSession({ ... });
                    continue;  // 跳过此候选
                }

                // 跳过 (skip)
                if (decision.type === "skip") {
                    attempts.push({ ... });
                    continue;
                }

                // 尝试探针 (probe)
                if (decision.markProbe) markProbeAttempt(now, probeThrottleKey);
                if (shouldAllowCooldownProbeForReason(decision.reason)) {
                    runOptions = { allowTransientCooldownProbe: true };
                }
            }
        }

        // 3c. 执行 Fallback 尝试
        const attemptRun = await runFallbackAttempt({
            run: params.run,
            ...candidate,
            attempts,
            options: runOptions,
            classifyResult: params.classifyResult,
            attempt: i + 1,
            total: candidates.length,
            attribution: { sessionId: params.sessionId, lane: params.lane }
        });

        // 3d. 成功 -> 返回结果
        if ("success" in attemptRun) {
            if (i > 0 || attempts.length > 0)
                await observeDecision({ decision: "candidate_succeeded", ... });
            return attemptRun.success;
        }

        // 3e. 失败 -> 根据错误类型决定是跳过还是重抛
        const err = attemptRun.error;
        if (isLikelyContextOverflowError(formatErrorMessage(err)))
            throw err;  // 上下文溢出直接抛出，不继续 fallback
        if (isMissingAgentHarnessError(err))
            throw err;  // Harness 缺失直接抛出

        // LiveSessionModelSwitchError -> 跳转到目标候选
        if (err instanceof LiveSessionModelSwitchError) {
            const liveSwitchTargetIndex = findLiveSessionModelSwitchRedirectIndex({
                error: err, candidates, currentIndex: i
            });
            if (liveSwitchTargetIndex !== null) {
                i = liveSwitchTargetIndex - 1;
                continue;
            }
        }

        // 记录失败尝试
        await observeFailedCandidate({ ... });
        await params.onError?.({ ... });
    }

    // 4. 全部失败 -> 抛出 FallbackSummaryError
    throwFallbackFailureSummary({ ... });
}
```

### 2.4.2 Fallback 候选链的遍历策略

Primary 模型失败后，系统依次尝试 `fallbacks[0]` -> `fallbacks[1]` -> ... 直至成功或耗尽。失败通过 `FallbackSummaryError` 抛出，携带所有尝试的详细信息。

`resolveFallbackCandidates`（第 643-696 行）构建候选列表：

1. 将 primary provider/model 作为第一个候选
2. 依次添加 `agents.defaults.model.fallbacks` 中配置的 Fallback 模型
3. 如果 primary 来自全局 defaults 且未在第一步中包含，追加到末尾

每个候选在执行前会检查：
- **Agent Harness 是否注册**：通过 `assertModelFallbackCandidateHarnessAvailable` 验证
- **Auth Profile 是否在冷却期**：通过 `resolveCooldownDecision` 决策跳过/挂起/探针
- **冷却探针的频率控制**：通过 `MIN_PROBE_INTERVAL_MS = 30000`（30 秒）和 `PROBE_MARGIN_MS = 120000`（2 分钟）限制

### 2.4.3 Fallback 尝试的追踪

每次 Fallback 尝试（成功或失败）都会记录以下字段：

```javascript
fallbackAttempts = Array.isArray(fallbackResult.attempts)
    ? fallbackResult.attempts.map((attempt) => ({
        provider: attempt.provider,      // 尝试的 Provider
        model: attempt.model,             // 尝试的 Model
        error: attempt.error,             // 错误信息
        reason: attempt.reason || void 0, // 失败原因: billing|rate_limit|overloaded|...
        status: typeof attempt.status === "number"
            ? attempt.status : void 0,     // HTTP 状态码
        code: attempt.code || void 0       // 错误码
    }))
    : [];
```

失败原因的完整枚举（来自 `describeFailoverError` 的返回）：
- `"billing"` -- 计费问题
- `"rate_limit"` -- 速率限制
- `"overloaded"` -- 模型过载
- `"model_not_found"` -- 模型未找到
- `"auth"` -- 认证失败
- `"auth_permanent"` -- 永久认证失败（Key 被撤销等）
- `"format"` -- 格式错误
- `"empty_response"` -- 空响应
- `"no_error_details"` -- 无错误详情
- `"unclassified"` -- 未分类
- `"timeout"` -- 超时
- `"session_expired"` -- 会话过期
- `"unknown"` -- 未知

### 2.4.4 Fallback 选择的持久化：7 个状态键

当 Fallback 候选成功（且与原始 selected 模型不同）时，系统会通过 `persistFallbackCandidateSelection` 将选择持久化到 session store 中。涉及的状态键定义在第 407-416 行：

```javascript
const FALLBACK_SELECTION_STATE_KEYS = [
    "providerOverride",                     // 覆盖的 Provider
    "modelOverride",                         // 覆盖的 Model
    "modelOverrideSource",                   // 覆盖来源（"auto" 表示自动 fallback）
    "modelOverrideFallbackOriginProvider",   // 原始 Provider（回退来源）
    "modelOverrideFallbackOriginModel",      // 原始 Model（回退来源）
    "authProfileOverride",                   // 覆盖的 Auth Profile
    "authProfileOverrideSource",             // Auth Profile 覆盖来源
    "authProfileOverrideCompactionCount"     // Compaction 计数
];
```

持久化的逻辑（`persistFallbackCandidateSelection`，第 1083-1129 行）：

1. **跳过条件检查**：
   - 如果是单回合模型覆盖（`hasOneTurnModelOverride`），不持久化
   - 如果选中的 provider/model 与原始 selected 相同，不持久化
   - 如果用户已手动设置 `modelOverride`（`modelOverrideSource === "user"`），不持久化

2. **状态快照与回滚**：
   - 持久化前先保存 `previousState` 快照
   - 持久化失败时自动回滚到 `previousState`
   - 返回一个 `rollback` 函数供调用方在后续失败时回滚

3. **双写策略**：
   - 同时更新内存中的 `activeSessionStore` 和持久化的 `storePath`

### 2.4.5 Fallback 通知消息

当发生模型 Fallback 时，系统会生成用户可见的通知消息。

**Fallback 激活通知**（`buildFallbackNotice`，第 141-146 行）：

```javascript
function buildFallbackNotice(params) {
    const selected = formatProviderModelRef(
        params.selectedProvider, params.selectedModel);
    const active = formatProviderModelRef(
        params.activeProvider, params.activeModel);
    if (areRuntimeModelRefsEquivalent(selected, active)) return null;
    return `↪️ Model Fallback: ${active} (selected ${selected}; ` +
           `${buildFallbackReasonSummary(params.attempts)})`;
}
```

**Fallback 清除通知**（`buildFallbackClearedNotice`，第 147-152 行）：

```javascript
function buildFallbackClearedNotice(params) {
    const selected = formatProviderModelRef(
        params.selectedProvider, params.selectedModel);
    const previous = normalizeOptionalString(params.previousActiveModel);
    if (previous && previous !== selected)
        return `↪️ Model Fallback cleared: ${selected} (was ${previous})`;
    return `↪️ Model Fallback cleared: ${selected}`;
}
```

**通知示例：**
- 激活：`↪️ Model Fallback: openai/gpt-4o (selected anthropic/claude-sonnet-4-5; rate_limit)`
- 清除：`↪️ Model Fallback cleared: anthropic/claude-sonnet-4-5 (was openai/gpt-4o)`

---

## 2.5 自动 Fallback 主模型探针（Auto Fallback Primary Probe）

### 2.5.1 核心概念

当模型发生 Fallback（例如从 `anthropic/claude-sonnet-4-5` 回退到 `openai/gpt-4o`）时，系统不会永久停留在 Fallback 模型上。相反，它会：

1. **记录原始 primary** 的 provider/model 作为 `autoFallbackPrimaryProbe`
2. **每隔 300 秒** 自动探针 primary 模型是否已恢复
3. **当 primary 恢复时**，自动清除 Fallback 覆盖，切回 primary

这一机制由 `agent-scope-Cf7T6Ju7.js` 中的以下核心常量和方法驱动：

```javascript
// 探针间隔：300 秒（5 分钟）
const AUTO_FALLBACK_PRIMARY_PROBE_INTERVAL_MS = 300 * 1e3;

// 探针状态最大条目数：4096
const AUTO_FALLBACK_PRIMARY_PROBE_MAX_KEYS = 4096;

// 探针状态存储（全局 Map）
const autoFallbackPrimaryProbeState = new Map();
```

### 2.5.2 探针状态键与生命周期

每个探针状态由 `autoFallbackPrimaryProbeStateKey` 生成唯一键（第 26-28 行）：

```javascript
function autoFallbackPrimaryProbeStateKey(params) {
    return [
        normalizeOptionalString(params.sessionKey) ?? "",
        `${params.primaryProvider}/${params.primaryModel}`
    ].join("\0");
}
```

键格式：`sessionKey\0primaryProvider/primaryModel`

状态管理通过 `pruneAutoFallbackPrimaryProbeState` 维护（第 29-41 行）：

1. **TTL 过期清理**：清除距离上次探针超过 `minIntervalMs`（300 秒）的条目
2. **容量限制**：当条目数超过 `MAX_KEYS`（4096）时，删除最老的条目

### 2.5.3 `resolveAutoFallbackPrimaryProbe` -- 判断是否需要探针

该函数（第 42-85 行）决定当前 session 是否应发起一次 primary 探针：

```javascript
function resolveAutoFallbackPrimaryProbe(params) {
    const entry = params.entry;
    if (!entry) return;

    // 必须满足：modelOverrideSource === "auto"
    // 或 存在 auto-fallback provenance
    const recoveredAutoFallbackOverride =
        entry.modelOverrideSource === void 0 &&
        hasSessionAutoModelFallbackProvenance(entry);
    if (entry.modelOverrideSource !== "auto" &&
        !recoveredAutoFallbackOverride) return;

    const originProvider = normalizeOptionalString(
        entry.modelOverrideFallbackOriginProvider);
    const originModel = normalizeOptionalString(
        entry.modelOverrideFallbackOriginModel);
    const overrideProvider = normalizeOptionalString(
        entry.providerOverride);
    const overrideModel = normalizeOptionalString(
        entry.modelOverride);

    // 验证数据完整性
    if (!originProvider || !originModel ||
        !overrideProvider || !overrideModel) return;

    // 验证 origin 与 primary 匹配
    if (originProvider !== primaryProvider ||
        originModel !== primaryModel) return;

    // 验证当前覆盖与 origin 确实不同
    if (overrideProvider === originProvider &&
        overrideModel === originModel) return;

    // 检查探针频控
    const now = params.now ?? Date.now();
    const key = autoFallbackPrimaryProbeStateKey({ ... });
    const lastProbeAt = state.get(key);
    if (typeof lastProbeAt === "number" &&
        now - lastProbeAt < minIntervalMs) return;  // 未到探针间隔

    return {
        provider: originProvider,        // Primary 的 provider
        model: originModel,              // Primary 的 model
        fallbackProvider: overrideProvider,  // 当前 Fallback 的 provider
        fallbackModel: overrideModel,        // 当前 Fallback 的 model
        ...fallbackAuthProfileId ? {
            fallbackAuthProfileId,
            fallbackAuthProfileIdSource
        } : {}
    };
}
```

### 2.5.4 探针执行与自动恢复

在 `runAgentTurnWithFallback` 中，探针逻辑在多个层面被集成：

**层面一：初始化时检查**（第 958-963 行）

`runAgentTurnWithFallback` 首先调用 `resolveRunAfterAutoFallbackPrimaryProbeRecheck`（第 902-952 行）：

```javascript
function resolveRunAfterAutoFallbackPrimaryProbeRecheck(params) {
    const probe = params.run.autoFallbackPrimaryProbe;

    // 如果没有活跃的探针，但 session entry 有 auto fallback provenance
    // 就尝试从 session entry 恢复探针
    const refreshedProbe = resolveAutoFallbackPrimaryProbe({
        entry: params.entry,
        sessionKey: params.sessionKey,
        primaryProvider: probe.provider,
        primaryModel: probe.model
    });

    // 如果探针仍然在间隔期内，使用 Fallback 模型
    if (!refreshedProbe) return resolveEntrySelectionRun();

    // 如果探针到期，当前 run 切换回 primary provider/model
    return {
        ...params.run,
        provider: refreshedProbe.provider,   // 切回 primary
        model: refreshedProbe.model,
        autoFallbackPrimaryProbe: refreshedProbe
    };
}
```

**层面二：Fallback 候选执行时标记探针**（第 3472-3476 行）

当 `runWithModelFallback` 回调执行时，如果当前 provider/model 恰好是 primary 探针目标：

```javascript
const activeProbe = run.autoFallbackPrimaryProbe;
if (activeProbe &&
    provider === activeProbe.provider &&
    model === activeProbe.model)
    markAutoFallbackPrimaryProbe({
        probe: activeProbe,
        sessionKey: replySessionKey
    });
```

`markAutoFallbackPrimaryProbe`（第 86-108 行）将当前时间戳记录到探针状态 Map 中，重置 300 秒倒计时。

**层面三：成功后清除 Fallback 覆盖**（第 1613-1616 行）

当 Fallback 链成功后，如果 primary 已经恢复（即 `fallbackProvider`/`fallbackModel` 与 primary 一致），调用 `clearRecoveredAutoFallbackPrimaryProbe`：

```javascript
await clearRecoveredAutoFallbackPrimaryProbe({
    provider: fallbackProvider,
    model: fallbackModel
});
```

`clearRecoveredAutoFallbackPrimaryProbe`（第 1130-1148 行）验证 entry 确实匹配探针后，调用 `clearAutoFallbackPrimaryProbeSelection`：

```javascript
function clearAutoFallbackPrimaryProbeSelection(entry, now = Date.now()) {
    delete entry.providerOverride;
    delete entry.modelOverride;
    delete entry.modelOverrideSource;
    delete entry.modelOverrideFallbackOriginProvider;
    delete entry.modelOverrideFallbackOriginModel;
    // 如果 authProfile 也是 auto 来源的，一并清除
    if (entry.authProfileOverrideSource === "auto" ||
        entry.authProfileOverrideSource === void 0 &&
        entry.authProfileOverrideCompactionCount !== void 0) {
        delete entry.authProfileOverride;
        delete entry.authProfileOverrideSource;
        delete entry.authProfileOverrideCompactionCount;
    }
    delete entry.fallbackNoticeSelectedModel;
    delete entry.fallbackNoticeActiveModel;
    delete entry.fallbackNoticeReason;
    entry.updatedAt = now;
}
```

### 2.5.5 `resolveFallbackTransition` -- 状态转换检测

该函数（第 153-188 行）负责检测 Fallback 状态是否发生了转换，用于控制用户通知的发送：

```javascript
function resolveFallbackTransition(params) {
    const selectedModelRef = formatProviderModelRef(
        params.selectedProvider, params.selectedModel);
    const activeModelRef = formatProviderModelRef(
        params.activeProvider, params.activeModel);

    // 读取上一次的 Fallback 状态
    const previousState = {
        selectedModel: normalizeOptionalString(
            params.state?.fallbackNoticeSelectedModel),
        activeModel: normalizeOptionalString(
            params.state?.fallbackNoticeActiveModel),
        reason: normalizeOptionalString(
            params.state?.fallbackNoticeReason)
    };

    // 判断当前是否处于 Fallback 激活状态
    const fallbackActive = !areRuntimeModelRefsEquivalent(
        selectedModelRef, activeModelRef);

    // Fallback 转入了新状态（selected/active 任一改变）
    const fallbackTransitioned = fallbackActive && (
        previousState.selectedModel !== selectedModelRef ||
        previousState.activeModel !== activeModelRef);

    // Fallback 被清除（上次活跃且不同，现在相同）
    const previousStateWasRealFallback = Boolean(
        previousState.selectedModel &&
        previousState.activeModel &&
        !areRuntimeModelRefsEquivalent(
            previousState.selectedModel, previousState.activeModel));
    const fallbackCleared = !fallbackActive && previousStateWasRealFallback;

    // ...

    return {
        selectedModelRef,
        activeModelRef,
        fallbackActive,
        fallbackTransitioned,     // 用于触发 activated 通知
        fallbackCleared,          // 用于触发 cleared 通知
        reasonSummary,
        attemptSummaries,
        previousState,
        nextState,
        stateChanged
    };
}
```

上层调用（第 4595-4621 行）据此决定是否发送 `↪️ Model Fallback:` 或 `↪️ Model Fallback cleared:` 通知。

### 2.5.6 自动恢复的完整时序

```
1. Primary (anthropic/claude-sonnet-4-5) 失败
   ├── Fallback 到 openai/gpt-4o
   ├── 持久化 providerOverride/modelOverride
   └── 记录 originProvider/anthropic, originModel/claude-sonnet-4-5

2. 每次新的 Agent Turn 开始时
   ├── resolveAutoFallbackPrimaryProbe 检查
   │   ├── 距上次探针 < 300s → 继续使用 Fallback
   │   └── 距上次探针 >= 300s → 切换回 Primary 发起探针
   └── Primary 探针
       ├── 成功 → markAutoFallbackPrimaryProbe 更新探针时间
       │         clearAutoFallbackPrimaryProbeSelection 清除覆盖
       └── 失败 → 下轮继续走 Fallback 链

3. Primary 恢复后
   ├── clearAutoFallbackPrimaryProbeSelection 删除所有覆盖字段
   ├── resolveFallbackTransition 检测到 fallbackCleared
   └── 发送 "↪️ Model Fallback cleared" 通知
```

---

## 2.6 Embedded 与 CLI 执行路径的对比

### 2.6.1 架构概览

OpenClaw 的 Agent 运行时支持两种底层执行路径：

| 维度 | CLI 路径 | Embedded 路径 |
|------|---------|--------------|
| 入口函数 | `runCliAgentWithLifecycle` | `runEmbeddedPiAgent` |
| 适用场景 | `claude-cli` 等外部 CLI 进程 | PI Agent（内置嵌入式运行时） |
| 调度方式 | 派生 CLI 子进程，通过 stdin/stdout 通信 | 进程内直接调用 PI runtime |
| 生命周期事件 | 自行发射 lifecycle start/end/error | 通过 `onAgentEvent` 回调接收 |
| 错误传播 | try/catch 后 emit lifecycle error + rethrow | 嵌入式异常直接向上传播 |

### 2.6.2 CLI 执行路径（`runCliAgentWithLifecycle`）

完整的 CLI 执行函数定义在第 222-306 行：

```javascript
async function runCliAgentWithLifecycle(params) {
    const startedAt = params.startedAt ?? Date.now();
    const emitLifecycleStart = params.emitLifecycleStart ?? true;
    const emitLifecycleTerminal = params.emitLifecycleTerminal ?? true;

    // 通知启动
    params.onAgentRunStart?.();

    // 发射 lifecycle start 事件
    if (emitLifecycleStart) emitAgentEvent({
        runId: params.runId,
        stream: "lifecycle",
        data: { phase: "start", startedAt }
    });

    // 创建 Assistant 文本桥接
    const assistantBridge = createAssistantTextBridge({
        runId: params.runId,
        suppressed: params.suppressAssistantBridge,
        deliver: params.onAssistantText
    });

    // 创建 Reasoning 文本桥接（仅 claude-cli）
    const reasoningBridge = createAssistantTextBridge({
        runId: params.runId,
        suppressed: params.suppressAssistantBridge,
        deliver: shouldBridgeCliAssistantTextToReasoning(params.provider)
            ? params.onReasoningText : void 0
    });

    try {
        const rawResult = await runCliAgent(params.runParams);
        const result = params.transformResult?.(rawResult) ?? rawResult;

        // 停止桥接并等待排空
        assistantBridge.unsubscribe();
        reasoningBridge.unsubscribe();
        await assistantBridge.drain();
        await reasoningBridge.drain();

        // 从 CLI 结果提取文本并发射 assistant 事件
        const cliText = normalizeOptionalString(result.payloads?.[0]?.text);
        if (cliText) emitAgentEvent({
            runId: params.runId,
            stream: "assistant",
            data: { text: cliText }
        });

        // 发射 lifecycle end 事件
        if (emitLifecycleTerminal) {
            emitAgentEvent({
                runId: params.runId,
                stream: "lifecycle",
                data: { phase: "end", startedAt, endedAt: Date.now() }
            });
            lifecycleTerminalEmitted = true;
        }
        return result;
    } catch (err) {
        // 错误路径：停止桥接、通知外部处理器、发射 lifecycle error
        assistantBridge.unsubscribe();
        reasoningBridge.unsubscribe();
        await assistantBridge.drain();
        await reasoningBridge.drain();
        await params.onErrorBeforeLifecycle?.(err);
        if (emitLifecycleTerminal) {
            emitAgentEvent({
                runId: params.runId,
                stream: "lifecycle",
                data: {
                    phase: "error",
                    startedAt,
                    endedAt: Date.now(),
                    error: String(err)
                }
            });
            lifecycleTerminalEmitted = true;
        }
        throw err;
    } finally {
        // 保障：即使异常路径也确保桥接关闭
        assistantBridge.unsubscribe();
        reasoningBridge.unsubscribe();
        if (emitLifecycleTerminal && !lifecycleTerminalEmitted)
            emitAgentEvent({
                runId: params.runId,
                stream: "lifecycle",
                data: {
                    phase: "error",
                    startedAt,
                    endedAt: Date.now(),
                    error: "CLI run completed without lifecycle terminal event"
                }
            });
    }
}
```

**CLI 路径的关键特征：**

1. **双桥接机制**：`assistantBridge` 和 `reasoningBridge` 通过事件系统（`onAgentEvent`）将 CLI 子进程的流式输出桥接到上层
2. **生命周期三阶段**：`start` -> `end`（成功）或 `start` -> `error`（失败）
3. **`finally` 保障**：即使主流程异常，也确保生命周期终端事件被发射
4. **`onErrorBeforeLifecycle` 回调**：在发射 lifecycle error 事件之前，允许调用方执行回滚操作（如回退 Fallback 候选选择）

### 2.6.3 Embedded 执行路径（`runEmbeddedPiAgent`）

Embedded 路径的调度在第 1274-1599 行的代码块中（`isCliProvider` 为 false 时执行）：

```javascript
if (isCliProvider(cliExecutionProvider, runtimeConfig)) {
    // === CLI 路径（上节已详述） ===
    const result = await runCliAgentWithLifecycle({ ... });
    return result;
}

// === Embedded 路径 ===
const { embeddedContext, senderContext, runBaseParams } =
    buildEmbeddedRunExecutionParams({ ... });

const agentHarnessPolicy = sessionRuntimeOverride
    ? { runtime: sessionRuntimeOverride, runtimeSource: "model" }
    : resolveAgentHarnessPolicy({ ... });

const embeddedRunProvider = resolveOpenAIRuntimeProviderForPi({ ... });
const embeddedRunHarnessOverride = sessionRuntimeOverride ??
    (agentHarnessPolicy.runtime === "pi" &&
     embeddedRunProvider !== provider ? "pi" : void 0);

return (async () => {
    let attemptCompactionCount = 0;
    const lifecycleBackstop = createEmbeddedLifecycleTerminalBackstop({
        runId, sessionKey: params.sessionKey
    });

    try {
        const result = await runEmbeddedPiAgent({
            ...embeddedContext,
            ...senderContext,
            ...runBaseParams,
            provider: embeddedRunProvider,
            agentHarnessId: embeddedRunHarnessOverride,
            agentHarnessRuntimeOverride: embeddedRunHarnessOverride,
            prompt: params.commandBody,
            // ... 大量参数 ...
            onAgentEvent: async (evt) => {
                lifecycleBackstop.note(evt);

                // 处理 lifecycle 事件
                if (evt.stream === "lifecycle" &&
                    typeof evt.data.phase === "string")
                    notifyAgentRunStart();

                // 处理 tool 事件
                if (evt.stream === "tool") { /* ... */ }

                // 处理 item 事件
                if (evt.stream === "item") { /* ... */ }

                // 处理 plan 事件
                if (evt.stream === "plan") { /* ... */ }

                // 处理 approval 事件
                if (evt.stream === "approval") { /* ... */ }

                // 处理 command_output 事件
                if (evt.stream === "command_output") { /* ... */ }

                // 处理 patch 事件
                if (evt.stream === "patch") { /* ... */ }

                // 处理 compaction 事件
                if (evt.stream === "compaction") {
                    const phase = readStringValue(evt.data.phase) ?? "";
                    if (phase === "start") { /* 通知用户 */ }
                    if (phase === "end") {
                        if (evt.data?.completed === true)
                            attemptCompactionCount += 1;
                        /* 通知用户 */
                    }
                }
            },
            onBlockReply: blockReplyHandler,
            onToolResult: onToolResult ? /* 序列化处理函数 */ : void 0
        });

        // 成功后通知 lifecycle backstop
        lifecycleBackstop.emit("end", result);
        return result;
    } catch (err) {
        // 失败时回退 Fallback 候选选择
        if (rollbackFallbackCandidateSelection) try {
            await rollbackFallbackCandidateSelection();
        } catch (rollbackError) { /* 非致命 */ }

        // 通知 lifecycle backstop 错误
        lifecycleBackstop.emit("error", err);
        throw err;
    } finally {
        autoCompactionCount += attemptCompactionCount;
    }
})();
```

**Embedded 路径的关键特征：**

1. **事件驱动架构**：通过 `onAgentEvent` 回调接收 8 种事件流：
   - `lifecycle` -- 生命周期阶段（start/end/error）
   - `tool` -- 工具调用（start/update/end）
   - `item` -- 对话项状态
   - `plan` -- 执行计划更新
   - `approval` -- 审批请求
   - `command_output` -- 命令执行输出
   - `patch` -- 代码补丁摘要
   - `compaction` -- 上下文压缩（start/end/incomplete）

2. **`lifecycleBackstop` 保障**：`createEmbeddedLifecycleTerminalBackstop`（第 844-882 行）确保即使嵌入式 Agent 没有正常发射 lifecycle 事件，调用方也能在 `emit("end", result)` 或 `emit("error", err)` 时收到完整的生命周期事件。

3. **Compaction 计数器**：`attemptCompactionCount` 在 `finally` 块中累加到外部的 `autoCompactionCount`，用于最终的 compaction 统计。

4. **Rollback 机制**：在 catch 块中回退 `fallbackCandidateSelection`，确保失败时 session 状态不被污染。

### 2.6.4 两种路径的 Fallback 选择

在 `runAgentTurnWithFallback` 中（第 1268-1273 行），两个路径的选择逻辑如下：

```javascript
const cliExecutionProvider =
    sessionRuntimeOverride === "pi"
        ? provider                               // 强制 PI
        : (sessionRuntimeOverride &&
           isCliProvider(sessionRuntimeOverride, runtimeConfig)
               ? sessionRuntimeOverride          // 强制 CLI
               : void 0)
        ?? resolveCliRuntimeExecutionProvider({  // 配置驱动
            provider, cfg: runtimeConfig,
            agentId: params.followupRun.run.agentId,
            modelId: model
        })
        ?? provider;                             // 默认

if (isCliProvider(cliExecutionProvider, runtimeConfig)) {
    // -> CLI 路径
} else {
    // -> Embedded 路径
}
```

关键差异体现在事件处理、生命周期管理和 Compaction 交互上：

| 特性 | CLI 路径 | Embedded 路径 |
|------|---------|--------------|
| 事件粒度 | 粗糙（只有 assistant text 和 lifecycle） | 细粒度（8 种事件流） |
| 生命周期 | 函数自行管理（start/end/error） | 由 PI runtime 驱动，backstop 保障 |
| 流式输出 | 通过 `assistantBridge` 监听 `onAgentEvent("assistant")` | 通过 `onPartialReply` 和 `onReasoningStream` 回调 |
| Compaction 通知 | N/A（CLI 子进程自行处理） | 通过 `onAgentEvent("compaction")` 接收，可选通知用户 |
| Tool 调用 | N/A | 通过 `onAgentEvent("tool")` 接收 start/update 事件 |
| 错误回滚 | `onErrorBeforeLifecycle` 回调 | catch 块中直接执行 `rollbackFallbackCandidateSelection` |

---

## 2.7 关键设计原则总结

OpenClaw 的错误处理与 Fallback 系统体现了以下核心设计原则：

### 2.7.1 分层防御

错误处理分布在三个层次：
1. **外层 `while(true)` catch**：处理顶级生命周期错误（Gateway 重启、模型切换等）
2. **`runWithModelFallback` 内部**：处理每个候选模型的失败，自动遍历 Fallback 链
3. **Embedded/CLI 执行内部**：处理运行时细节错误（compaction、tool、role ordering 等）

### 2.7.2 优雅降级

错误消息按用户角色分级：
- 普通用户：简洁、可操作的消息（"Please try again, or use /new"）
- 内部管理员：详细的技术错误 + 日志路径（"Logs: openclaw logs --follow"）
- 群聊场景：考虑静默策略（`resolveSilentReplyPolicy`），避免在群聊中发送冗长的错误消息

### 2.7.3 防重复（Idempotency Guards）

多个关键操作都有防重复机制：
- `didResetAfterCompactionFailure`：每个 turn 只允许一次会话重置
- `didRetryTransientHttpError`：瞬时 HTTP 错误只重试一次
- `liveModelSwitchRetries`：模型切换最多重试 2 次
- `cooldownProbeUsedProviders`：同一次 run 中对同一个 provider 只允许一次冷却探针

### 2.7.4 状态可恢复性

- 会话重置时通过 `replayRecentUserAssistantMessages` 保留对话上下文
- `usageFamilySessionIds` 保留历史 session ID 以追踪使用统计
- Fallback 选择通过 7 个状态键持久化到 session store
- Primary 探针状态全局追踪（最多 4096 条），支持自动恢复

### 2.7.5 可观测性

- `fallbackAttempts` 数组记录每次 Fallback 尝试的完整元数据
- `model-fallback` 子系统日志记录每次决策（`logModelFallbackDecision`）
- 生命周期事件（`fallback_step`, `fallback`, `fallback_cleared`）发送到外部监听器
- 诊断事件（`model.usage`）记录详细的 token 使用和成本估算

---

*本节的源代码来自 OpenClaw 的编译后分发文件，实际变量名和行号可能因版本不同而有差异。核心架构逻辑与错误分类策略在所有近期版本中保持一致。*
# 三、多Agent通信与调度（上）：sessions_spawn完整流程与角色体系

## 3.1 架构总览

OpenClaw的多Agent体系采用**层次化父子架构**。整个系统的核心是Parent Agent（父Agent）通过`sessions_spawn`工具调用来创建Child Agent（即Subagent，子Agent），子Agent执行任务，完成后通过Push Announce（推送通知）将结果回报给父Agent。中间由一个**Subagent Registry（子Agent注册中心）**维护所有运行中子Agent的状态、生命周期和完成通知。

### 3.1.1 核心组件关系

```
┌─────────────────────────────────────────────────────────────────────┐
│                      OpenClaw 多Agent架构                              │
│                                                                     │
│  ┌──────────────┐                      ┌──────────────┐             │
│  │ Parent Agent │                      │ Child Agent  │             │
│  │  (main角色)   │                      │ (subagent)   │             │
│  │              │                      │              │             │
│  │  depth = 0   │   sessions_spawn     │ depth = 1    │             │
│  │  role="main" │ ──────────────────▶  │ role="leaf"  │             │
│  │  canSpawn=T  │                      │ or           │             │
│  │              │  ◀────────────────   │ "orchestrator"│            │
│  │              │   Push Announce      │              │             │
│  └──────┬───────┘   (completion)       └──────┬───────┘             │
│         │                                      │                    │
│         │          ┌──────────────────────────┐│                    │
│         └─────────▶│   Subagent Registry       │◀───────────────────│
│                    │  ┌──────────────────┐     │                    │
│                    │  │ subagentRuns Map │     │                    │
│                    │  │  runId → entry   │     │                    │
│                    │  └──────────────────┘     │                    │
│                    │  • registerSubagentRun    │                    │
│                    │  • countActiveRuns        │                    │
│                    │  • waitForCompletion      │                    │
│                    │  • emitSubagentEndedHook  │                    │
│                    └──────────────────────────┘                     │
└─────────────────────────────────────────────────────────────────────┘
```

### 3.1.2 数据流向

1. **Parent Agent** 调用`sessions_spawn`工具，传入子Agent ID、任务描述、上下文模式等参数。
2. **sessions_spawn处理器**（即`spawnSubagentDirect`函数）执行最多30+步骤的完整spawn流程：参数校验、深度检查、并发控制、权限验证、沙箱判断、session Key生成、能力解析、模型选择、session store持久化、上下文准备、子Agent Gateway调用、注册到Registry等。
3. **Subagent Registry** 接收注册，创建运行条目（entry），启动后台等待（`waitForSubagentCompletion`），监听子Agent事件。
4. **Child Agent** 运行在子Agent Gateway上，完成执行后自动推送完成通知（Auto-announce）给父Agent。
5. **Parent Agent** 收到完成事件后，可以整合子Agent的结果并继续执行或给出最终答案。

### 3.1.3 关键设计原则

- **Push-Based Announce（推送通知）**：子Agent完成后主动推送结果，父Agent不应轮询（不应调用`sessions_list`、`sessions_history`等）。
- **层次深度限制**：通过`maxSpawnDepth`（默认1）控制递归创建深度。
- **并发控制**：通过`maxChildrenPerAgent`（默认5）限制同时活跃的子Agent数量。
- **三层角色体系**：`main` → `orchestrator` → `leaf`，每层具有不同的spawn能力和控制范围。

---

## 3.2 sessions_spawn完整流程（30+步骤）

`spawnSubagentDirect` 是整个多Agent通信的核心函数，位于文件`src/agents/subagent-spawn.types.ts`和`src/agents/`相关模块中。入口参数结构如下：

```typescript
// spawnSubagentDirect(params, ctx) 的 params:
// {
//   task: string,          // 分配给子Agent的任务描述
//   taskName?: string,     // 可选的任务名称
//   agentId?: string,      // 目标Agent ID
//   label?: string,        // 标签
//   model?: string,        // 模型覆盖
//   thinking?: string,     // thinking模式覆盖
//   mode?: "run" | "session",   // 运行模式
//   context?: "isolated" | "fork", // 上下文模式
//   thread?: boolean,      // 是否绑定线程
//   sandbox?: "require" | "inherit", // 沙箱模式
//   cleanup?: "keep" | "delete",  // 清理策略
//   cwd?: string,          // 工作目录
//   attachments?: Array,   // 附件列表
//   runTimeoutSeconds?: number, // 运行超时
//   expectationsCompletionMessage?: boolean, // 是否等待完成消息
//   lightContext?: boolean, // 轻量上下文
// }
```

以下是完整的步骤分解，每个步骤包含真实源码引用。

---

### 步骤1：参数规范化（Parameter Normalization）

#### 1.1 `taskName`规范化

函数`normalizeSubagentTaskName`对用户传入的`taskName`进行正则校验：

```typescript
// 来源: src/agents/subagent-task-name.ts
const SUBAGENT_TASK_NAME_RE = /^[a-z][a-z0-9_]{0,63}$/;
const RESERVED_SUBAGENT_TASK_NAMES = new Set(["all", "last"]);

function normalizeSubagentTaskName(value) {
    const taskName = normalizeOptionalString(value);
    if (!taskName) return {};
    if (!SUBAGENT_TASK_NAME_RE.test(taskName))
        return { error: `Invalid taskName "${taskName}". Use 1-64 chars matching [a-z][a-z0-9_]*.` };
    if (RESERVED_SUBAGENT_TASK_NAMES.has(taskName))
        return { error: `Invalid taskName "${taskName}". Reserved subagent targets cannot be used as taskName values.` };
    return { taskName };
}
```

**校验规则详解**：
- 长度：1-64个字符
- 格式：必须以小写字母开头，后续可包含小写字母、数字或下划线
- 保留字：`all`和`last`不可用作taskName（因为在`subagent_target`筛选时这两个是特殊目标）

#### 1.2 `agentId`校验

使用`isValidAgentId`函数进行校验：

```typescript
// 来源: src/agents/session-key.ts
// isValidAgentId 校验 /^[a-z0-9][a-z0-9_-]{0,63}$/
```

```typescript
// 在 spawnSubagentDirect 中:
const requestedAgentId = params.agentId?.trim();
if (requestedAgentId && !isValidAgentId(requestedAgentId))
    return {
        status: "error",
        error: `Invalid agentId "${requestedAgentId}". Agent IDs must match [a-z0-9][a-z0-9_-]{0,63}.`
    };
```

**校验规则详解**：
- 长度：1-64个字符
- 格式：以字母数字开头，后续可包含字母数字、连字符或下划线

#### 1.3 `mode`解析（Spawn Mode Resolution）

```typescript
// 来源: src/agents/subagent-spawn.types.ts
const SUBAGENT_SPAWN_MODES = ["run", "session"];

function resolveSpawnMode(params) {
    if (params.requestedMode === "run" || params.requestedMode === "session")
        return params.requestedMode;
    return params.threadRequested ? "session" : "run";
}
```

- 如果明确指定`mode="run"`或`mode="session"`，直接使用。
- 如果未指定但请求了线程绑定（`thread=true`），则默认为`"session"`模式。
- 否则默认为`"run"`模式（一次性运行）。
- 特殊校验：`mode="session"`时如果没有同时请求`thread=true`，直接返回错误。

```typescript
// 在 spawnSubagentDirect 中:
if (spawnMode === "session" && !requestThreadBinding)
    return {
        status: "error",
        error: "sessions_spawn(mode=\"session\") requires thread=true ..."
    };
```

**`run` vs `session` 模式对比**：

| 维度 | `run` | `session` |
|------|-------|-----------|
| 生命周期 | 一次性任务，完成后即终止 | 持久化session，可后续发送跟进消息 |
| 线程绑定 | 可选 | 必须（`thread=true`） |
| 清理策略 | 默认清理 | 默认保留 |
| `cleanupBundleMcpOnRunEnd` | `true` | `false` |
| 完成消息投递 | 直接推送 | 可选，取决于线程绑定 |

---

### 步骤2：深度检查（Depth Check）

在`spawnSubagentDirect`函数的主体部分，第一步关键检查是深度限制：

```typescript
// 来源: src/agents/subagent-spawn.types.ts (spawnSubagentDirect 函数)
const callerDepth = getSubagentDepthFromSessionStore(requesterInternalKey, { cfg });
const maxSpawnDepth = cfg.agents?.defaults?.subagents?.maxSpawnDepth ?? 1;

if (callerDepth >= maxSpawnDepth)
    return {
        status: "forbidden",
        error: `sessions_spawn is not allowed at this depth (current depth: ${callerDepth}, max: ${maxSpawnDepth})`
    };
```

#### `getSubagentDepthFromSessionStore`深度计算逻辑

深度不是简单地从配置读取，而是**从session store中递归追溯**计算出来的：

```typescript
// 来源: src/agents/subagent-depth.ts
function getSubagentDepthFromSessionStore(sessionKey, opts) {
    const raw = (sessionKey ?? "").trim();
    const fallbackDepth = getSubagentDepth(raw); // 从sessionKey本身的格式解析
    if (!raw) return fallbackDepth;

    const cache = new Map();
    const visited = new Set();

    const depthFromStore = (key) => {
        const normalizedKey = normalizeSubagentSessionKey(key);
        if (!normalizedKey) return;
        if (visited.has(normalizedKey)) return;  // 防止循环引用
        visited.add(normalizedKey);

        const entry = resolveEntryForSessionKey({
            sessionKey: normalizedKey,
            cfg: opts?.cfg,
            store: opts?.store,
            cache
        });

        // 如果store中有明确的spawnDepth，直接返回
        const storedDepth = normalizeSpawnDepth(entry?.spawnDepth);
        if (storedDepth !== void 0) return storedDepth;

        // 否则通过spawnedBy链追溯父session
        const spawnedBy = normalizeSubagentSessionKey(entry?.spawnedBy);
        if (!spawnedBy) return;

        // 递归: 父深度 + 1
        const parentDepth = depthFromStore(spawnedBy);
        if (parentDepth !== void 0) return parentDepth + 1;
        return getSubagentDepth(spawnedBy) + 1;
    };

    return depthFromStore(raw) ?? fallbackDepth;
}
```

**深度追溯机制详解**：
1. 首先查找session store中该session key的`spawnDepth`字段。
2. 如果不存在（存量数据或旧版本），通过`spawnedBy`字段向上追溯父session。
3. 利用`visited`集合防止循环引用。
4. 利用`cache`避免重复读取同一store文件。
5. 最终fallback是从session key格式本身解析（例如`agent:xx:subagent:uuid`格式）。

**默认值**：`maxSpawnDepth`默认为`1`，意味着只允许创建一层子Agent。如果`callerDepth >= maxSpawnDepth`（例如深度为1的Agent尝试再次spawn），则被"forbidden"拒绝。

---

### 步骤3：并发检查（Concurrency Check）

```typescript
// 来源: src/agents/subagent-spawn.types.ts (spawnSubagentDirect 函数)
const maxChildren = cfg.agents?.defaults?.subagents?.maxChildrenPerAgent ?? 5;
const activeChildren = countActiveRunsForSession(requesterInternalKey);

if (activeChildren >= maxChildren)
    return {
        status: "forbidden",
        error: `sessions_spawn has reached max active children for this session (${activeChildren}/${maxChildren})`
    };
```

#### `countActiveRunsForSession`实现

```typescript
// 来源: src/agents/subagent-registry.ts
function countActiveRunsForSession(requesterSessionKey) {
    return countActiveRunsForSessionFromRuns(
        subagentRegistryDeps.getSubagentRunsSnapshotForRead(subagentRuns),
        requesterSessionKey
    );
}
```

该函数从全局`subagentRuns` Map中统计所有`requesterSessionKey`匹配且仍在活跃的（`endedAt`为空的）子Agent运行。`maxChildren`默认值为`5`，即每个会话同时最多5个活跃子Agent。

**并发控制的目的**：
- 防止Agent无限制地创建子Agent导致资源耗尽。
- 确保每个子Agent有足够的计算资源。
- 符合push-based announce模型：父Agent应等待子Agent完成后再创建新的，而不是洪水般地创建。

---

### 步骤4：Agent ID解析与权限校验（Agent ID Resolution & Authorization）

#### 4.1 Agent ID确定

```typescript
// 确定请求的Agent ID
const requesterAgentId = normalizeAgentId(
    ctx.requesterAgentIdOverride ?? parseAgentSessionKey(requesterInternalKey)?.agentId
);

// 检查是否强制要求agentId
if ((resolveAgentConfig(cfg, requesterAgentId)?.subagents?.requireAgentId
    ?? cfg.agents?.defaults?.subagents?.requireAgentId ?? false)
    && !requestedAgentId?.trim())
    return {
        status: "forbidden",
        error: "sessions_spawn requires explicit agentId when requireAgentId is configured..."
    };

// 目标Agent ID = 请求的Agent ID 或 请求者自己的Agent ID
const targetAgentId = requestedAgentId
    ? normalizeAgentId(requestedAgentId)
    : requesterAgentId;
```

**Agent ID确定逻辑**：
- 如果用户明确指定了`agentId`，使用该值（经normalize处理）。
- 否则，子Agent使用与父Agent相同的Agent ID（同Agent内spawn）。
- 可以配置`requireAgentId=true`强制要求指定Agent ID（跨Agent spawn时必须）。

#### 4.2 目标策略校验

```typescript
// 来源: src/agents/spawn-requester-origin.ts
const targetPolicy = resolveSubagentTargetPolicy({
    requesterAgentId,
    targetAgentId,
    requestedAgentId,
    allowAgents: resolveAgentConfig(cfg, requesterAgentId)?.subagents?.allowAgents
        ?? cfg?.agents?.defaults?.subagents?.allowAgents
});

if (!targetPolicy.ok)
    return {
        status: "forbidden",
        error: targetPolicy.error
    };
```

`resolveSubagentTargetPolicy`检查目标Agent是否在允许列表中。`allowAgents`配置可以限制一个Agent能够spawn的目标Agent范围。

#### 4.3 Ownership解析

```typescript
// 来源: src/agents/subagent-spawn-ownership.ts
function resolveSubagentSpawnOwnership(params) {
    const { mainKey, alias } = resolveMainSessionAlias(params.cfg);
    const controllerSessionKey = params.agentSessionKey
        ? resolveInternalSessionKey({ key: params.agentSessionKey, alias, mainKey })
        : alias;
    const completionOwnerKey = params.completionOwnerKey?.trim();
    const completionRequesterSessionKey = completionOwnerKey
        ? resolveInternalSessionKey({ key: completionOwnerKey, alias, mainKey })
        : controllerSessionKey;
    return {
        controllerSessionKey,
        threadBindingRequesterSessionKey: controllerSessionKey,
        completionRequesterSessionKey,
        completionRequesterDisplayKey: resolveDisplaySessionKey({
            key: completionRequesterSessionKey, alias, mainKey
        })
    };
}
```

**Ownership三个关键Key**：
- `controllerSessionKey`：控制者，即实际发起spawn请求的session。
- `completionRequesterSessionKey`：完成通知的目标，可能由`completionOwnerKey`覆盖。
- `threadBindingRequesterSessionKey`：线程绑定的请求者session key。

---

### 步骤5：沙箱运行时检查（Sandbox Runtime Check）

```typescript
// 来源: src/agents/subagent-spawn.types.ts (spawnSubagentDirect 函数)
const requesterRuntime = resolveSandboxRuntimeStatus({
    cfg,
    sessionKey: requesterInternalKey
});
const childRuntime = resolveSandboxRuntimeStatus({
    cfg,
    sessionKey: childSessionKey
});

if (!childRuntime.sandboxed && (requesterRuntime.sandboxed || sandboxMode === "require")) {
    if (requesterRuntime.sandboxed)
        return {
            status: "forbidden",
            error: "Sandboxed sessions cannot spawn unsandboxed subagents."
        };
    return {
        status: "forbidden",
        error: "sessions_spawn sandbox=\"require\" needs a sandboxed target runtime."
    };
}
```

**沙箱继承规则**：
- **沙箱父Agent不能spawn非沙箱子Agent**：如果父Agent在沙箱中运行，子Agent也必须在沙箱中运行。这是安全要求。
- **`sandbox="require"`**：强制要求子Agent的运行时是沙箱化的。如果目标Agent的运行时不是沙箱，spawn会被拒绝。
- **`sandbox="inherit"`**（默认）：子Agent继承运行时的默认沙箱设置。
- **同Agent spawn不改变沙箱状态**：如果targetAgentId与requesterAgentId相同，子Agent的运行时沙箱状态与父Agent一致。

---

### 步骤6：子Session Key生成（Child Session Key Generation）

```typescript
// 来源: src/agents/subagent-spawn.types.ts (spawnSubagentDirect 函数)
const childSessionKey = `agent:${targetAgentId}:subagent:${crypto.randomUUID()}`;
```

**Key格式详解**：
- 前缀：`agent:` — 标识这是一个Agent scoped的session
- Agent ID：`{targetAgentId}` — 归一化后的目标Agent ID
- 类型标签：`subagent:` — 明确标记为子Agent session
- 唯一标识：`crypto.randomUUID()` — 使用Node.js的`crypto.randomUUID()`生成v4 UUID

**示例**：
```
agent:myagent:subagent:a1b2c3d4-e5f6-7890-abcd-ef1234567890
       │        │        │
       │        │        └── UUID v4 (随机唯一)
       │        └── 标记为子Agent session
       └── Agent ID
```

这种命名约定使系统可以在session store中快速识别子Agent session，判断其类型（通过`isSubagentSessionKey`），并进行针对性的生命周期管理。

---

### 步骤7：能力与角色解析（Capabilities & Role Resolution）

```typescript
// 来源: src/agents/subagent-spawn.types.ts (spawnSubagentDirect 函数)
const childDepth = callerDepth + 1;
const childCapabilities = resolveSubagentCapabilities({
    depth: childDepth,
    maxSpawnDepth
});
```

#### `resolveSubagentCapabilities`完整实现

```typescript
// 来源: src/agents/subagent-capabilities.ts
function resolveSubagentRoleForDepth(params) {
    const depth = Number.isInteger(params.depth) ? Math.max(0, params.depth) : 0;
    const maxSpawnDepth = typeof params.maxSpawnDepth === "number" && Number.isFinite(params.maxSpawnDepth)
        ? Math.max(1, Math.floor(params.maxSpawnDepth)) : 1;
    if (depth <= 0) return "main";
    return depth < maxSpawnDepth ? "orchestrator" : "leaf";
}

function resolveSubagentControlScopeForRole(role) {
    return role === "leaf" ? "none" : "children";
}

function resolveSubagentCapabilities(params) {
    const role = resolveSubagentRoleForDepth(params);
    const controlScope = resolveSubagentControlScopeForRole(role);
    return {
        depth: Math.max(0, Math.floor(params.depth)),
        role,
        controlScope,
        canSpawn: role === "main" || role === "orchestrator",
        canControlChildren: controlScope === "children"
    };
}
```

**返回结构**：
```typescript
{
    depth: number,           // 当前深度（整数）
    role: "main" | "orchestrator" | "leaf",
    controlScope: "children" | "none",
    canSpawn: boolean,       // 是否还能继续spawn子Agent
    canControlChildren: boolean // 不能控制子Agent时将看不到子Agent相关工具
}
```

**角色判定逻辑**（详见3.4节三层角色体系）：
- `depth <= 0` → `"main"`（主Agent）
- `0 < depth < maxSpawnDepth` → `"orchestrator"`（编排者，还能继续spawn）
- `depth >= maxSpawnDepth` → `"leaf"`（叶子节点，不能spawn）

---

### 步骤8：模型与Thinking解析（Model & Thinking Plan Resolution）

```typescript
// 来源: src/agents/subagent-spawn.types.ts (spawnSubagentDirect 函数)
const plan = resolveSubagentModelAndThinkingPlan({
    cfg,
    targetAgentId,
    targetAgentConfig: resolveAgentConfig(cfg, targetAgentId),
    modelOverride,
    thinkingOverrideRaw
});

if (plan.status === "error")
    return {
        status: "error",
        error: plan.error
    };
```

#### `resolveSubagentModelAndThinkingPlan`实现

```typescript
// 来源: src/agents/subagent-spawn-plan.ts
function resolveSubagentModelAndThinkingPlan(params) {
    // 1. 解析模型选择
    const resolvedModel = resolveSubagentSpawnModelSelection({
        cfg: params.cfg,
        agentId: params.targetAgentId,
        modelOverride: params.modelOverride
    });

    // 2. 解析thinking覆盖
    const thinkingPlan = resolveSubagentThinkingOverride({
        cfg: params.cfg,
        targetAgentConfig: params.targetAgentConfig,
        thinkingOverrideRaw: params.thinkingOverrideRaw
    });

    // 3. 如果thinking解析失败，返回格式化错误信息
    if (thinkingPlan.status === "error") {
        const { provider, model } = splitModelRef(resolvedModel);
        const hint = formatThinkingLevels(provider, model);
        return {
            status: "error",
            resolvedModel,
            error: `Invalid thinking level "${thinkingPlan.thinkingCandidateRaw}". Use one of: ${hint}.`
        };
    }

    // 4. 成功返回
    return {
        status: "ok",
        resolvedModel,
        modelApplied: Boolean(resolvedModel),
        thinkingOverride: thinkingPlan.thinkingOverride,
        initialSessionPatch: {
            ...(resolvedModel ? {
                model: resolvedModel,
                modelOverrideSource: params.modelOverride?.trim() ? "user" : "auto"
            } : {}),
            ...thinkingPlan.initialSessionPatch
        }
    };
}
```

#### `resolveSubagentThinkingOverride`实现

```typescript
// 来源: src/agents/subagent-spawn-thinking.ts
function resolveSubagentThinkingOverride(params) {
    // 从targetAgent和全局默认配置中查找thinking设置
    const targetSubagents = asRecord(asRecord(params.targetAgentConfig)?.subagents);
    const defaultSubagents = asRecord(params.cfg.agents?.defaults?.subagents);
    const resolvedThinkingDefaultRaw =
        readString(targetSubagents ?? {}, "thinking")
        ?? readString(defaultSubagents ?? {}, "thinking");

    // 优先使用用户覆盖，其次配置默认值
    const thinkingCandidateRaw = params.thinkingOverrideRaw || resolvedThinkingDefaultRaw;

    if (!thinkingCandidateRaw) return {
        status: "ok",
        thinkingOverride: void 0,
        initialSessionPatch: {}
    };

    const normalizedThinking = normalizeThinkLevel(thinkingCandidateRaw);
    if (!normalizedThinking) return {
        status: "error",
        thinkingCandidateRaw
    };

    return {
        status: "ok",
        thinkingOverride: normalizedThinking,
        initialSessionPatch: {
            thinkingLevel: normalizedThinking === "off" ? null : normalizedThinking
        }
    };
}
```

**Thinking配置优先级**：
1. 用户在`sessions_spawn`调用时传入的`thinking`参数（最高优先级）
2. 目标Agent配置中的`subagents.thinking`
3. 全局默认配置中的`agents.defaults.subagents.thinking`

**模型引用解析（`splitModelRef`）**：
```typescript
function splitModelRef(ref) {
    if (!ref) return { provider: void 0, model: void 0 };
    const trimmed = ref.trim();
    if (!trimmed) return { provider: void 0, model: void 0 };
    const slash = trimmed.indexOf("/");
    if (slash > 0 && slash < trimmed.length - 1)
        return { provider: trimmed.slice(0, slash), model: trimmed.slice(slash + 1) };
    return { provider: void 0, model: trimmed };
}
```

支持格式：
- `"provider/model"` → 解析出provider和model
- `"model"` → 仅model，无provider

---

### 步骤9：Session Store持久化（Session Store Persistence）

#### 9.1 初始补丁写入

```typescript
// 构建子session的持久化补丁
const initialPatchError = await patchChildSession({
    spawnDepth: childDepth,
    subagentRole: childCapabilities.role === "main" ? null : childCapabilities.role,
    subagentControlScope: childCapabilities.controlScope,
    ...inheritedToolAllowPatch(ctx.inheritedToolAllowlist),
    ...inheritedToolDenyPatch(ctx.inheritedToolDenylist),
    ...plan.initialSessionPatch
});
```

#### `patchChildSession`实现

```typescript
const patchChildSession = async (patch) => {
    try {
        const target = resolveGatewaySessionStoreTarget({ cfg, key: childSessionKey });
        await updateSubagentSessionStore(target.storePath, (store) => {
            pruneLegacyStoreKeys({ store, canonicalKey: target.canonicalKey, candidates: target.storeKeys });
            store[target.canonicalKey] = mergeSessionEntry(
                store[target.canonicalKey],
                buildDirectChildSessionPatch(patch)
            );
        });
        return;
    } catch (err) {
        return `child session patch failed: ${err instanceof Error ? err.message : typeof err === "string" ? err : "error"}`;
    }
};
```

#### `buildDirectChildSessionPatch`字段映射

```typescript
// 来源: src/agents/subagent-spawn.types.ts
function buildDirectChildSessionPatch(patch) {
    const entry = {};

    // spawnDepth 写入
    const spawnDepth = patch.spawnDepth;
    if (typeof spawnDepth === "number" && Number.isFinite(spawnDepth) && spawnDepth >= 0)
        entry.spawnDepth = Math.floor(spawnDepth);

    // subagentRole 写入（仅orchestrator和leaf有效值）
    if (patch.subagentRole === "orchestrator" || patch.subagentRole === "leaf")
        entry.subagentRole = patch.subagentRole;

    // subagentControlScope 写入
    if (patch.subagentControlScope === "children" || patch.subagentControlScope === "none")
        entry.subagentControlScope = patch.subagentControlScope;

    // spawnedBy 父session key
    if (typeof patch.spawnedBy === "string" && patch.spawnedBy.trim())
        entry.spawnedBy = patch.spawnedBy.trim();

    // spawnedWorkspaceDir 工作目录
    if (typeof patch.spawnedWorkspaceDir === "string" && patch.spawnedWorkspaceDir.trim())
        entry.spawnedWorkspaceDir = patch.spawnedWorkspaceDir.trim();

    // 工具 deny/allow 列表
    const inheritedToolDeny = normalizeInheritedToolDenylist(patch.inheritedToolDeny);
    if (inheritedToolDeny.length > 0) entry.inheritedToolDeny = inheritedToolDeny;
    const inheritedToolAllow = normalizeInheritedToolAllowlist(patch.inheritedToolAllow);
    if (inheritedToolAllow.length > 0) entry.inheritedToolAllow = inheritedToolAllow;

    // thinking level
    if (typeof patch.thinkingLevel === "string" && patch.thinkingLevel.trim())
        entry.thinkingLevel = patch.thinkingLevel.trim();

    // 模型信息
    if (typeof patch.model === "string" && patch.model.trim()) {
        const { provider, model } = splitModelRef(patch.model.trim());
        if (model) {
            entry.model = model;
            entry.modelOverride = model;
            entry.modelOverrideSource = patch.modelOverrideSource === "auto" ? "auto" : "user";
            if (provider) {
                entry.modelProvider = provider;
                entry.providerOverride = provider;
            }
        }
    }

    return entry;
}
```

**写入的Session Store字段总览**：

| 字段 | 类型 | 含义 |
|------|------|------|
| `spawnDepth` | `number` | 子Agent的spawn深度 |
| `subagentRole` | `"orchestrator" \| "leaf"` | 子Agent角色（main不写） |
| `subagentControlScope` | `"children" \| "none"` | 子Agent控制范围 |
| `spawnedBy` | `string` | 父session key |
| `spawnedWorkspaceDir` | `string` | 工作目录路径 |
| `inheritedToolDeny` | `string[]` | 继承的工具黑名单 |
| `inheritedToolAllow` | `string[]` | 继承的工具白名单 |
| `thinkingLevel` | `string` | Thinking覆盖级别 |
| `model` | `string` | 使用的模型 |
| `modelOverride` | `string` | 模型覆盖值 |
| `modelOverrideSource` | `"user" \| "auto"` | 模型覆盖来源 |
| `modelProvider` | `string` | 模型提供商 |
| `providerOverride` | `string` | 提供商覆盖 |

#### 9.2 运行时模型持久化（额外一步）

```typescript
if (resolvedModel) {
    const runtimeModelPersistError = await persistInitialChildSessionRuntimeModel({
        cfg, childSessionKey, resolvedModel
    });
    if (runtimeModelPersistError) {
        // 失败时清理session后返回错误
        return { status: "error", error: runtimeModelPersistError, childSessionKey };
    }
    modelApplied = true;
}
```

这个额外步骤确保模型信息在子Agent启动前就写入runtime store，以便Gateway能正确读取。

---

### 步骤10：Fork/Isolated上下文准备（Context Preparation）

```typescript
// 来源: src/agents/subagent-spawn.types.ts (spawnSubagentDirect 函数)
const contextMode = resolveSubagentContextMode({
    requestedContext: params.context,
    threadRequested: requestThreadBinding,
    cfg,
    requester: { channel: ctx.agentChannel, accountId: ctx.agentAccountId }
});

const preparedSpawnContext = await prepareSubagentSessionContext({
    cfg, contextMode, requesterAgentId, targetAgentId,
    requesterInternalKey, childSessionKey
});
```

#### `resolveSubagentContextMode`逻辑

```typescript
function resolveSubagentContextMode(params) {
    if (params.requestedContext === "fork" || params.requestedContext === "isolated")
        return params.requestedContext;
    if (!params.threadRequested || !params.requester.channel)
        return "isolated";
    return resolveThreadBindingSpawnPolicy({
        cfg: params.cfg,
        channel: params.requester.channel,
        accountId: params.requester.accountId,
        kind: "subagent"
    }).defaultSpawnContext;
}
```

**上下文模式决策流程**：
1. 如果明确指定`"fork"`或`"isolated"`，使用指定模式。
2. 如果未指定且没有线程绑定请求或不在channel上，默认`"isolated"`。
3. 如果有线程绑定，则根据线程绑定策略的`defaultSpawnContext`决定。

#### `prepareSubagentSessionContext`实现

```typescript
async function prepareSubagentSessionContext(params) {
    // isolated 模式：直接返回空白上下文
    if (params.contextMode === "isolated")
        return { status: "ok", mode: "isolated" };

    // fork 模式：需要从父session复制上下文
    // ...

    // fork的限制：跨Agent spawn不支持fork
    if (params.targetAgentId !== params.requesterAgentId)
        throw new Error(
            "context=\"fork\" currently requires the same target agent as the requester; " +
            "use context=\"isolated\" for cross-agent spawns."
        );

    // 检查父session是否存在
    if (!parentEntry?.sessionId)
        throw new Error(
            "context=\"fork\" requested but the requester session transcript is not available."
        );

    // 调用fork决策逻辑
    const forkDecision = await resolveParentForkDecision({ parentEntry, storePath });
    if (forkDecision.status === "skip") {
        forkFallbackNote = forkDecision.message;
        return null;  // 跳过fork，实际会fallback到isolated
    }

    // 执行fork
    const fork = await forkSessionFromParent({ parentEntry, agentId, sessionsDir });

    // 将fork的session信息写入子session的store条目
    store[childTarget.canonicalKey] = mergeSessionEntry(store[childTarget.canonicalKey], {
        sessionId: fork.sessionId,
        sessionFile: fork.sessionFile,
        forkedFromParent: true
    });

    return { status: "ok", mode: "fork", parentEntry, childEntry, forked };
}
```

**两种上下文模式对比**：

| 维度 | `isolated` | `fork` |
|------|-----------|--------|
| 对话历史 | 空白，全新开始 | 从父Agent复制 |
| 跨Agent支持 | 支持 | 不支持（必须同Agent） |
| 父session要求 | 无 | 必须有session转录本 |
| 子Agent行为 | 独立上下文 | 继承父Agent对话语境 |
| 默认模式 | 大多数情况的默认 | 仅在明确指定时使用 |

---

### 步骤11：附件处理（Attachment Handling）

```typescript
// 来源: src/agents/subagent-spawn.types.ts (spawnSubagentDirect 函数)
const materializedAttachments = await materializeSubagentAttachments({
    config: cfg, targetAgentId, workspaceDir: spawnedWorkspaceDir,
    attachments: params.attachments, mountPathHint
});
```

#### 附件限制常量

```typescript
function resolveAttachmentLimits(config) {
    const attachmentsCfg = config.tools?.sessions_spawn?.attachments;
    return {
        enabled: attachmentsCfg?.enabled === true,
        maxTotalBytes: ... ?? 5 * 1024 * 1024,     // 默认 5MB
        maxFiles: ... ?? 50,                        // 默认 50个文件
        maxFileBytes: ... ?? 1 * 1024 * 1024,       // 默认 1MB/文件
        retainOnSessionKeep: attachmentsCfg?.retainOnSessionKeep === true
    };
}
```

#### 附件物化流程

```typescript
async function materializeSubagentAttachments(params) {
    // 1. 验证附件功能是否启用
    if (!limits.enabled) return {
        status: "forbidden",
        error: "attachments are disabled for sessions_spawn ..."
    };

    // 2. 文件数量检查
    if (requestedAttachments.length > limits.maxFiles) return {
        status: "error",
        error: `attachments_file_count_exceeded (maxFiles=${limits.maxFiles})`
    };

    // 3. 创建附件目录 (.openclaw/attachments/{uuid})
    const attachmentId = crypto.randomUUID();
    const absDir = path.join(absRootDir, attachmentId);
    await promises.mkdir(absDir, { recursive: true, mode: 448 });

    // 4. 逐个写入文件
    for (const raw of requestedAttachments) {
        // 4a. 文件名安全性校验（不能包含路径分隔符、控制字符等）
        if (!name) fail("attachments_invalid_name (empty)");
        if (name.includes("/") || name.includes("\\") || name.includes("\0"))
            fail(`attachments_invalid_name (${name})`);
        if (/[\r\n\t -]/.test(name))
            fail(`attachments_invalid_name (${name})`);

        // 4b. Base64解码或UTF-8编码
        if (encoding === "base64") {
            buf = decodeStrictBase64(contentVal, limits.maxFileBytes);
        } else {
            buf = Buffer.from(contentVal, "utf8");
        }

        // 4c. 单文件大小检查
        if (bytes > limits.maxFileBytes)
            fail(`attachments_file_bytes_exceeded ...`);

        // 4d. 累计大小检查
        totalBytes += bytes;
        if (totalBytes > limits.maxTotalBytes)
            fail(`attachments_total_bytes_exceeded ...`);

        // 4e. SHA256校验
        const sha256 = crypto.createHash("sha256").update(buf).digest("hex");
    }

    // 5. 写入manifest清单文件
    await store.writeJson(".manifest.json", { relDir, count, totalBytes, files });

    // 6. 返回结果
    return {
        status: "ok",
        receipt: { count, totalBytes, files, relDir },
        absDir,
        rootDir: absRootDir,
        retainOnSessionKeep: limits.retainOnSessionKeep,
        systemPromptSuffix: `Attachments: ${files.length} file(s), ${totalBytes} bytes...`
    };
}
```

#### Base64严格解码验证

```typescript
function decodeStrictBase64(value, maxDecodedBytes) {
    // 长度上限检查（编码后长度）
    const maxEncodedBytes = Math.ceil(maxDecodedBytes / 3) * 4;
    if (value.length > maxEncodedBytes * 2) return null;

    // 空白去除和长度对齐检查
    const normalized = value.replace(/\s+/g, "");
    if (!normalized || normalized.length % 4 !== 0) return null;

    // 字符集验证
    if (!/^[A-Za-z0-9+/]+={0,2}$/.test(normalized)) return null;

    // 编码长度检查
    if (normalized.length > maxEncodedBytes) return null;

    // 解码并检查实际大小
    const decoded = Buffer.from(normalized, "base64");
    if (decoded.byteLength > maxDecodedBytes) return null;

    return decoded;
}
```

**附件安全措施总结**：
- 文件名：禁止路径分隔符(`/`, `\`, `\0`)、控制字符、`.`、`..`、`.manifest.json`
- 去重：同一批次中不允许重复文件名
- 大小校验：单文件最大1MB，总计最大5MB（均可在配置中调整）
- 完整性：写入SHA256校验和
- Base64安全：严格验证Base64编码格式和长度
- 失败回滚：物化过程中出错则删除已创建的目录

---

### 步骤12：线程绑定（Thread Binding，session模式可选）

```typescript
// 来源: src/agents/subagent-spawn.types.ts (spawnSubagentDirect 函数)
if (requestThreadBinding) {
    const bindResult = await ensureThreadBindingForSubagentSpawn({
        hookRunner, childSessionKey, agentId: targetAgentId,
        label: label || void 0, mode: spawnMode,
        requesterSessionKey: ownership.threadBindingRequesterSessionKey,
        requester: {
            channel: childSessionOrigin?.channel,
            accountId: childSessionOrigin?.accountId,
            to: childSessionOrigin?.to,
            threadId: childSessionOrigin?.threadId
        }
    });
    // ...
    threadBindingReady = true;
    hasBoundThreadDeliveryOrigin = hasRoutableDeliveryOrigin(bindResult.deliveryOrigin);
    childSessionOrigin = mergeDeliveryContext(bindResult.deliveryOrigin, childSessionOrigin) ?? childSessionOrigin;
}
```

#### `ensureThreadBindingForSubagentSpawn`实现

```typescript
async function ensureThreadBindingForSubagentSpawn(params) {
    // 检查是否有subagent_spawning钩子
    if (!params.hookRunner?.hasHooks("subagent_spawning"))
        return {
            status: "error",
            error: buildThreadBindingUnavailableError(params.mode)
        };

    try {
        // 调用hooks系统绑定线程
        const result = await params.hookRunner.runSubagentSpawning({
            childSessionKey: params.childSessionKey,
            agentId: params.agentId,
            label: params.label,
            mode: params.mode,
            requester: params.requester,
            threadRequested: true
        }, {
            childSessionKey: params.childSessionKey,
            requesterSessionKey: params.requesterSessionKey
        });

        if (result?.status === "error")
            return { status: "error", error: result.error.trim() || "Failed to prepare thread binding..." };

        if (!result)
            return { status: "error", error: buildThreadBindingUnavailableError(params.mode) };

        if (result?.status !== "ok" || !result.threadBindingReady)
            return { status: "error", error: "Unable to create or bind a thread..." };

        const deliveryOrigin = normalizeDeliveryContext(result.deliveryOrigin);
        return { status: "ok", ...(deliveryOrigin ? { deliveryOrigin } : {}) };
    } catch (err) {
        return { status: "error", error: `Thread bind failed: ${summarizeError(err)}` };
    }
}
```

**线程绑定条件**：
- 必须配置了`subagent_spawning`钩子（由channel插件提供，如Discord、Slack、Telegram）。
- 钩子返回`{ status: "ok", threadBindingReady: true }`。
- 对于`mode="session"`，线程绑定是必需的（否则直接返回错误，不会走到这里）。

---

### 步骤13：上下文引擎准备（Context Engine Preparation）

```typescript
// 来源: src/agents/subagent-spawn.types.ts (spawnSubagentDirect 函数)
const contextEnginePrepareResult = await prepareContextEngineSubagentSpawn({
    cfg, context: preparedSpawnContext, requesterInternalKey, childSessionKey,
    runTimeoutSeconds
});
```

```typescript
async function prepareContextEngineSubagentSpawn(params) {
    try {
        subagentSpawnDeps.ensureContextEnginesInitialized();
        return {
            status: "ok",
            preparation: await (await subagentSpawnDeps.resolveContextEngine(params.cfg))
                .prepareSubagentSpawn?.({
                    parentSessionKey: params.requesterInternalKey,
                    childSessionKey: params.childSessionKey,
                    contextMode: params.context.mode,
                    parentSessionId: params.context.parentEntry?.sessionId,
                    parentSessionFile: params.context.parentEntry?.sessionFile,
                    childSessionId: params.context.mode === "fork"
                        ? params.context.forked.sessionId
                        : params.context.childEntry?.sessionId,
                    childSessionFile: params.context.mode === "fork"
                        ? params.context.forked.sessionFile
                        : params.context.childEntry?.sessionFile,
                    ttlMs: params.runTimeoutSeconds > 0 ? params.runTimeoutSeconds * 1000 : void 0
                })
        };
    } catch (err) {
        return {
            status: "error",
            error: `Context engine subagent preparation failed: ${summarizeError(err)}`
        };
    }
}
```

**上下文引擎的作用**：
- 管理对话上下文的存储和检索。
- 为子Agent的上下文环境做初始化准备。
- 如果在fork模式下，传递父session的转录信息。
- 设置TTL（Time To Live），与runTimeoutSeconds对应。
- 返回的`preparation`对象包含`rollback()`方法用于失败时的回滚。

---

### 步骤14：Gateway调用启动子Agent（Gateway Call to Child Agent）

这是将子Agent真正派发到Gateway执行的步骤。

```typescript
// 来源: src/agents/subagent-spawn.types.ts (spawnSubagentDirect 函数)
const runId = readGatewayRunId(await callSubagentGateway({
    method: "agent",
    params: {
        message: childTaskMessage,           // 给子Agent的任务消息
        sessionKey: childSessionKey,          // 子Agent的session key
        channel: childSessionOrigin?.channel,
        to: childSessionOrigin?.to ?? void 0,
        accountId: childSessionOrigin?.accountId ?? void 0,
        threadId: childSessionOrigin?.threadId != null
            ? stringifyRouteThreadId(childSessionOrigin.threadId) : void 0,
        idempotencyKey: childIdem,           // 幂等键，防止重复创建
        deliver: deliverInitialChildRunDirectly, // 是否直接投递（session模式）
        lane: AGENT_LANE_SUBAGENT,           // 通道标记为subagent
        cleanupBundleMcpOnRunEnd: spawnMode !== "session",  // run模式清理MCP
        extraSystemPrompt: childSystemPrompt, // 子Agent系统提示词
        thinking: thinkingOverride,           // thinking覆盖
        timeout: runTimeoutSeconds,           // 超时时间
        label: label || void 0,
        ...(bootstrapContextMode ? {
            bootstrapContextMode,
            bootstrapContextRunKind: "default"
        } : {}),
        ...publicSpawnedMetadata              // workspaceDir等公开元数据
    },
    timeoutMs: resolveSubagentAgentGatewayTimeoutMs(runTimeoutSeconds)
}));
```

#### `callSubagentGateway`包装器

```typescript
async function callSubagentGateway(params) {
    const scopes = params.scopes ?? (isAdminOnlyMethod(params.method) ? ["operator.admin"] : void 0);
    return await subagentSpawnDeps.callGateway({
        ...params,
        ...(scopes != null ? { scopes } : {})
    });
}
```

#### Gateway超时计算

```typescript
function resolveSubagentAgentGatewayTimeoutMs(runTimeoutSeconds) {
    const runTimeoutMs = Number.isFinite(runTimeoutSeconds) && runTimeoutSeconds > 0
        ? Math.floor(runTimeoutSeconds * 1000) : 0;
    if (runTimeoutMs <= 0) return DEFAULT_SUBAGENT_AGENT_GATEWAY_TIMEOUT_MS; // 60000ms
    return Math.min(
        MAX_SUBAGENT_AGENT_GATEWAY_TIMEOUT_MS,   // 300000ms 上限
        Math.max(
            DEFAULT_SUBAGENT_AGENT_GATEWAY_TIMEOUT_MS,  // 60000ms 下限
            runTimeoutMs + 5000  // run超时 + 5秒缓冲
        )
    );
}
```

**Gateway调用关键参数解析**：

| 参数 | 值 | 含义 |
|------|-----|------|
| `method` | `"agent"` | 告诉Gateway这是一个Agent运行 |
| `lane` | `AGENT_LANE_SUBAGENT` (`"subagent"`) | 标记通道类型 |
| `idempotencyKey` | UUID v4 | 防止重复创建 |
| `message` | `childTaskMessage` | 由`buildSubagentInitialUserMessage`构建 |
| `cleanupBundleMcpOnRunEnd` | `run`模式为`true` | 一次性运行完成后清理MCP资源 |
| `extraSystemPrompt` | 动态构建 | 包含深度、上下文、附件等信息的系统提示 |
| `timeoutMs` | 动态计算 | Gateway层面的超时 |
| `timeout`（params内） | `runTimeoutSeconds` | Agent运行层面的超时 |

#### 子Agent初始消息构建

```typescript
// 来源: src/agents/subagent-initial-user-message.ts
function buildSubagentInitialUserMessage(params) {
    const lines = [
        `[Subagent Context] You are running as a subagent (depth ${params.childDepth}/${params.maxSpawnDepth}). `
        + `Results auto-announce to your requester; do not busy-poll for status.`
    ];
    if (params.persistentSession)
        lines.push("[Subagent Context] This subagent session is persistent and remains available for thread follow-up messages.");
    const taskBody = params.task?.trim();
    if (taskBody)
        lines.push("[Subagent Task]", taskBody, "Begin. Execute the assigned task to completion.");
    else
        lines.push("Begin. Execute the assigned task to completion.");
    return lines.join("\n\n");
}
```

这条消息是子Agent接收到的第一条用户消息，包含：
- 上下文说明（深度信息、auto-announce机制）。
- 持久化session提示（如果是session模式）。
- 实际任务内容，以`[Subagent Task]`标记。

---

### 步骤15：注册到Subagent Registry

```typescript
// 来源: src/agents/subagent-spawn.types.ts (spawnSubagentDirect 函数)
registerSubagentRun({
    runId: childRunId,
    childSessionKey,
    controllerSessionKey: ownership.controllerSessionKey,
    requesterSessionKey: ownership.completionRequesterSessionKey,
    requesterOrigin,
    requesterDisplayKey: ownership.completionRequesterDisplayKey,
    task,
    taskName,
    cleanup,
    label: label || void 0,
    model: resolvedModel,
    agentDir: targetAgentDir,
    workspaceDir: spawnedMetadata.workspaceDir,
    runTimeoutSeconds,
    expectsCompletionMessage: shouldAnnounceCompletion,
    spawnMode,
    attachmentsDir: attachmentAbsDir,
    attachmentsRootDir: attachmentRootDir,
    retainAttachmentsOnKeep: retainOnSessionKeep
});
```

#### Registry内部注册逻辑

```typescript
// 来源: src/agents/subagent-registry.ts
const registerSubagentRun = (registerParams) => {
    const runId = registerParams.runId.trim();
    const childSessionKey = registerParams.childSessionKey.trim();
    const requesterSessionKey = registerParams.requesterSessionKey.trim();
    const controllerSessionKey = registerParams.controllerSessionKey?.trim() || requesterSessionKey;

    if (!runId || !childSessionKey || !requesterSessionKey) return;

    const now = Date.now();
    const spawnMode = registerParams.spawnMode === "session" ? "session" : "run";
    const archiveAtMs = spawnMode === "session" || registerParams.cleanup === "keep"
        ? void 0
        : archiveAfterMs ? now + archiveAfterMs : void 0;

    // 构建运行条目
    const entry = {
        runId,
        childSessionKey,
        controllerSessionKey,
        requesterSessionKey,
        requesterOrigin,
        requesterDisplayKey: registerParams.requesterDisplayKey,
        task: registerParams.task,
        taskName: registerParams.taskName,
        cleanup: registerParams.cleanup,
        expectsCompletionMessage: registerParams.expectsCompletionMessage,
        spawnMode,
        label: registerParams.label,
        model: registerParams.model,
        agentDir: registerParams.agentDir,
        workspaceDir: registerParams.workspaceDir,
        runTimeoutSeconds,
        createdAt: now,
        startedAt: now,
        sessionStartedAt: now,
        accumulatedRuntimeMs: 0,
        archiveAtMs,
        cleanupHandled: false,
        completionAnnouncedAt: void 0,
        wakeOnDescendantSettle: void 0,
        attachmentsDir: registerParams.attachmentsDir,
        attachmentsRootDir: registerParams.attachmentsRootDir,
        retainAttachmentsOnKeep: registerParams.retainAttachmentsOnKeep
    };

    // 写入内存Map
    params.runs.set(runId, entry);

    // 持久化到磁盘
    params.persistOrThrow();

    // 创建后台任务跟踪
    createRunningTaskRun({
        runtime: "subagent",
        sourceId: runId,
        ownerKey: requesterSessionKey,
        scopeKind: "session",
        requesterOrigin,
        childSessionKey,
        runId,
        label: registerParams.label,
        task: registerParams.task,
        deliveryStatus: registerParams.expectsCompletionMessage === false
            ? "not_applicable" : "pending",
        startedAt: now,
        lastEventAt: now
    });

    // 确保事件监听器已启动
    params.ensureListener();

    // 持久化状态
    params.persist();

    // 启动清理定时器
    params.startSweeper();

    // 启动后台等待：监听子Agent完成
    waitForSubagentCompletion(runId, waitTimeoutMs, entry);
};
```

**Registry注册的关键操作**：
1. **写入`subagentRuns` Map**：内存中的核心数据结构。
2. **持久化到磁盘**（`persistOrThrow`）：保证重启后能恢复。
3. **`createRunningTaskRun`**：创建后台任务跟踪，用于进度监控。
4. **`ensureListener`**：确保Agent事件监听器已启动，监听子Agent的运行事件。
5. **`startSweeper`**：启动清理器，定期回收过期的运行条目。
6. **`waitForSubagentCompletion`**：启动后台等待Promise，监听子Agent完成并触发完成通知。

---

### 步骤16：Hooks与生命周期事件（Hooks & Lifecycle Events）

#### 16.1 `subagent_spawned`钩子

```typescript
// 来源: src/agents/subagent-spawn.types.ts (spawnSubagentDirect 函数)
if (hookRunner?.hasHooks("subagent_spawned")) try {
    await hookRunner.runSubagentSpawned({
        runId: childRunId,
        childSessionKey,
        agentId: targetAgentId,
        label: label || void 0,
        requester: {
            channel: requesterOrigin?.channel,
            accountId: requesterOrigin?.accountId,
            to: requesterOrigin?.to,
            threadId: requesterOrigin?.threadId
        },
        threadRequested: requestThreadBinding,
        mode: spawnMode
    }, {
        runId: childRunId,
        childSessionKey,
        requesterSessionKey: requesterInternalKey
    });
} catch {}
```

当子Agent成功spawn后，通知所有注册了`subagent_spawned`事件的插件/钩子。钩子收到：
- `runId`：子Agent运行ID
- `childSessionKey`：子Agent session key
- `agentId`：目标Agent ID
- `label`：标签
- `requester`：请求者信息（channel、accountId、to、threadId）
- `threadRequested`：是否请求了线程绑定
- `mode`：运行模式

#### 16.2 `sessionLifecycle`事件

```typescript
// 来源: src/agents/subagent-spawn.types.ts (spawnSubagentDirect 函数)
emitSessionLifecycleEvent({
    sessionKey: childSessionKey,
    reason: "create",
    parentSessionKey: requesterInternalKey,
    label: label || void 0
});
```

发送session生命周期事件，reason为`"create"`，携带父子关系信息。

#### 16.3 最终返回值构建

```typescript
const acceptedNote = resolveSubagentSpawnAcceptedNote({
    spawnMode,
    agentSessionKey: ctx.agentSessionKey
});

return {
    status: "accepted",
    childSessionKey,
    runId: childRunId,
    mode: spawnMode,
    taskName,
    note: preparedSpawnContext.forkFallbackNote
        ? `${acceptedNote} ${preparedSpawnContext.forkFallbackNote}`
        : acceptedNote,
    modelApplied: resolvedModel ? modelApplied : void 0,
    attachments: attachmentsReceipt
};
```

#### Accepted Note的含义

```typescript
// 来源: src/agents/subagent-spawn-accepted-note.ts
const SUBAGENT_SPAWN_ACCEPTED_NOTE =
    "Auto-announce is push-based. After spawning children, do NOT call " +
    "sessions_list, sessions_history, exec sleep, or any polling tool. " +
    "Track expected child session keys. Continue any independent work. " +
    "If your final answer depends on child output, wait for runtime " +
    "completion events to arrive as user messages and only answer after " +
    "completion events for ALL required children arrive. " +
    "If a child completion event arrives AFTER your final answer, " +
    "reply ONLY with NO_REPLY.";

const SUBAGENT_SPAWN_SESSION_ACCEPTED_NOTE =
    "thread-bound session stays active after this task; " +
    "continue in-thread for follow-ups.";

function resolveSubagentSpawnAcceptedNote(params) {
    if (params.spawnMode === "session")
        return SUBAGENT_SPAWN_SESSION_ACCEPTED_NOTE;
    return isCronSessionKey(params.agentSessionKey)
        ? void 0
        : SUBAGENT_SPAWN_ACCEPTED_NOTE;
}
```

这条note直接注入到父Agent的工具返回结果中，指导父Agent：
1. **不要轮询**：子Agent完成后会自动推送（push-based）。
2. **不要调用polling工具**：如`sessions_list`、`sessions_history`、`exec sleep`等。
3. **追踪子session key**：记住`childSessionKey`以便匹配完成通知。
4. **继续独立工作**：在等待子Agent时可以继续其他独立工作。
5. **等待所有子Agent完成**：如果最终答案依赖子Agent输出，必须等所有子Agent完成后再给出答案。
6. **NO_REPLY规则**：如果完成通知在最终答案之后到达，只回复`NO_REPLY`。

---

## 3.3 错误处理与回滚机制（Error Handling & Rollback）

`sessions_spawn`流程在多个步骤之间实现了精密的错误处理和清理机制，确保在任何步骤失败时都不会留下"僵尸"session或资源泄漏。

### 3.3.1 错误点总览

整个流程有多个明确的错误返回点，每个错误点都有对应的清理操作：

```
┌──────────────────────────────────────────────────────────┐
│                  sessions_spawn 错误处理流程                │
│                                                          │
│  Step 2: 深度检查失败                                      │
│    └─ 直接返回 forbidden (无需清理)                         │
│                                                          │
│  Step 3: 并发检查失败                                      │
│    └─ 直接返回 forbidden (无需清理)                         │
│                                                          │
│  Step 4: Agent ID/权限失败                                │
│    └─ 直接返回 forbidden/error (无需清理)                   │
│                                                          │
│  Step 5: 沙箱检查失败                                      │
│    └─ 直接返回 forbidden (无需清理)                         │
│                                                          │
│  Step 8: Thinking解析失败                                  │
│    └─ 直接返回 error (无需清理)                             │
│                                                          │
│  Step 9: Session Store写入失败                             │
│    └─ 返回 error (session已创建但内容不完整)                 │
│                                                          │
│  Step 10: Fork上下文准备失败                                │
│    └─ cleanupProvisionalSession(childSessionKey)          │
│       emitLifecycleHooks: false                           │
│       deleteTranscript: true                              │
│                                                          │
│  Step 9b: Runtime模型持久化失败                             │
│    └─ sessions.delete(childSessionKey)                    │
│       emitLifecycleHooks: false                           │
│                                                          │
│  Step 11: 附件物化失败                                     │
│    └─ cleanupProvisionalSession(childSessionKey)          │
│       emitLifecycleHooks: threadBindingReady              │
│       deleteTranscript: true                              │
│                                                          │
│  Step 12: 线程绑定失败                                     │
│    └─ sessions.delete(childSessionKey)                    │
│       deleteTranscript: true                              │
│       emitLifecycleHooks: false                           │
│                                                          │
│  Step 13: 上下文引擎准备失败                                │
│    └─ cleanupFailedSpawnBeforeAgentStart({...})           │
│       清理附件 + cleanupProvisionalSession                 │
│                                                          │
│  Step 14: Gateway调用失败                                  │
│    └─ rollbackPreparedContextEngine                       │
│       清理附件目录                                          │
│       emit subagent_ended hook (error outcome)            │
│       sessions.delete(emitLifecycleHooks)                 │
│                                                          │
│  Step 15: Registry注册失败                                 │
│    └─ rollbackPreparedContextEngine                       │
│       清理附件目录                                          │
│       sessions.delete(emitLifecycleHooks)                 │
│                                                          │
└──────────────────────────────────────────────────────────┘
```

### 3.3.2 清理函数详解

#### `cleanupFailedSpawnBeforeAgentStart`

```typescript
// 来源: src/agents/subagent-spawn.types.ts
async function cleanupFailedSpawnBeforeAgentStart(params) {
    // 1. 清理附件目录
    if (params.attachmentAbsDir) try {
        await promises.rm(params.attachmentAbsDir, {
            recursive: true,
            force: true
        });
    } catch {}

    // 2. 清理临时session
    await cleanupProvisionalSession(params.childSessionKey, {
        emitLifecycleHooks: params.emitLifecycleHooks,
        deleteTranscript: params.deleteTranscript
    });
}
```

#### `cleanupProvisionalSession`

```typescript
async function cleanupProvisionalSession(childSessionKey, options) {
    try {
        await callSubagentGateway({
            method: "sessions.delete",
            params: {
                key: childSessionKey,
                emitLifecycleHooks: options?.emitLifecycleHooks === true,
                deleteTranscript: options?.deleteTranscript === true
            },
            timeoutMs: SUBAGENT_CONTROL_GATEWAY_TIMEOUT_MS  // 60000ms
        });
    } catch {}
}
```

注意这里使用了`SUBAGENT_CONTROL_GATEWAY_TIMEOUT_MS`（60秒）作为Gateway调用的超时，这是控制类操作（delete session）的超时，不同于Agent运行的超时。

#### Gateway调用失败的特殊处理

这是最复杂的回滚场景（步骤14），因为此时子Agent可能已经开始运行：

```typescript
catch (err) {
    // 1. 回滚上下文引擎
    await rollbackPreparedContextEngine(contextEnginePreparation);

    // 2. 清理附件目录
    if (attachmentAbsDir) try {
        await promises.rm(attachmentAbsDir, { recursive: true, force: true });
    } catch {}

    // 3. 发送subagent_ended钩子（如果已绑定线程）
    let emitLifecycleHooks = false;
    if (threadBindingReady) {
        const hasEndedHook = hookRunner?.hasHooks("subagent_ended") === true;
        let endedHookEmitted = false;
        if (hasEndedHook) try {
            await hookRunner?.runSubagentEnded({
                targetSessionKey: childSessionKey,
                targetKind: "subagent",
                reason: "spawn-failed",
                sendFarewell: true,
                accountId: childSessionOrigin?.accountId,
                runId: childRunId,
                outcome: "error",
                error: "Session failed to start"
            }, {
                runId: childRunId,
                childSessionKey,
                requesterSessionKey: requesterInternalKey
            });
            endedHookEmitted = true;
        } catch {}
        emitLifecycleHooks = !endedHookEmitted;
    }

    // 4. 删除临时session
    try {
        await callSubagentGateway({
            method: "sessions.delete",
            params: {
                key: childSessionKey,
                deleteTranscript: true,
                emitLifecycleHooks
            },
            timeoutMs: SUBAGENT_CONTROL_GATEWAY_TIMEOUT_MS
        });
    } catch {}

    // 5. 返回错误
    return {
        status: "error",
        error: summarizeError(err),
        childSessionKey,
        runId: childRunId
    };
}
```

**关键回滚决策**：
- `threadBindingReady`为`true`时才需要`emitLifecycleHooks`，因为只有在成功绑定了线程时，外部channel才知道这个session的存在。
- 如果`subagent_ended`钩子已成功发送，则不重复发送lifecycle hooks。
- 无论钩子是否成功，都会尝试删除临时session（best-effort）。

#### 上下文引擎回滚

```typescript
async function rollbackPreparedContextEngine(preparation) {
    try {
        await preparation?.rollback();
    } catch {}
}
```

由上下文引擎自行实现具体的回滚逻辑。

---

## 3.4 三层角色体系（3-Tier Role System）

OpenClaw的子Agent系统设计了一个精巧的三层角色体系，根据`depth`（深度）自动分配角色，每个角色具有不同的权限和能力。

### 3.4.1 角色定义

```typescript
// 来源: src/agents/subagent-capabilities-types.ts
type SubagentSessionRole = "main" | "orchestrator" | "leaf";
const SUBAGENT_SESSION_ROLES = ["main", "orchestrator", "leaf"];
```

```typescript
// 来源: src/agents/subagent-capabilities.ts
function resolveSubagentRoleForDepth(params) {
    const depth = Number.isInteger(params.depth) ? Math.max(0, params.depth) : 0;
    const maxSpawnDepth = typeof params.maxSpawnDepth === "number"
        && Number.isFinite(params.maxSpawnDepth)
        ? Math.max(1, Math.floor(params.maxSpawnDepth)) : 1;
    if (depth <= 0) return "main";
    return depth < maxSpawnDepth ? "orchestrator" : "leaf";
}

function resolveSubagentControlScopeForRole(role) {
    return role === "leaf" ? "none" : "children";
}
```

### 3.4.2 角色能力矩阵

| 深度（depth） | 角色（role） | 控制范围（controlScope） | 能否spawn（canSpawn） | 能否控制子Agent（canControlChildren） |
|:---:|:---:|:---:|:---:|:---:|
| `depth = 0` | `"main"` | `"children"` | 是 | 是 |
| `0 < depth < maxSpawnDepth` | `"orchestrator"` | `"children"` | 是 | 是 |
| `depth >= maxSpawnDepth` | `"leaf"` | `"none"` | 否 | 否 |

### 3.4.3 角色流转示例

以默认`maxSpawnDepth = 1`为例：

```
depth=0 | role="main"
  │
  │ sessions_spawn
  ▼
depth=1 | role="leaf"       (depth=1 >= maxSpawnDepth=1)
  │
  │ sessions_spawn → 被拒绝（callerDepth=1 >= maxSpawnDepth=1）
  ✗ forbidden
```

以`maxSpawnDepth = 2`为例：

```
depth=0 | role="main"
  │
  │ sessions_spawn
  ▼
depth=1 | role="orchestrator"    (0 < depth=1 < 2)
  │
  │ sessions_spawn
  ▼
depth=2 | role="leaf"            (depth=2 >= maxSpawnDepth=2)
  │
  │ sessions_spawn → 被拒绝
  ✗ forbidden
```

### 3.4.4 `resolveSubagentCapabilities`完整输出

```typescript
function resolveSubagentCapabilities(params) {
    const role = resolveSubagentRoleForDepth(params);
    const controlScope = resolveSubagentControlScopeForRole(role);
    return {
        depth: Math.max(0, Math.floor(params.depth)),
        role,                           // "main" | "orchestrator" | "leaf"
        controlScope,                   // "children" | "none"
        canSpawn: role === "main" || role === "orchestrator",  // boolean
        canControlChildren: controlScope === "children"         // boolean
    };
}
```

返回的`canSpawn`和`canControlChildren`直接控制子Agent的工具可用性：
- `canSpawn = false` → `sessions_spawn`工具不可用
- `canControlChildren = false` → 子Agent管理工具不可用（如`sessions_list`、`sessions_send`等）

### 3.4.5 角色持久化与恢复

角色信息不仅实时计算，还会持久化到session store：

```typescript
// 在 patchChildSession 中:
entry.subagentRole = patch.subagentRole;         // "orchestrator" 或 "leaf"
entry.subagentControlScope = patch.subagentControlScope;  // "children" 或 "none"
```

这意味着即使系统重启，也能通过读取session store恢复子Agent的角色信息。

```typescript
// 来源: 恢复存储的角色能力
function resolveStoredSubagentCapabilities(sessionKey, opts) {
    // ...
    const storedRole = normalizeSubagentRole(entry?.subagentRole);
    const storedControlScope = normalizeSubagentControlScope(entry?.subagentControlScope);
    const fallback = resolveSubagentCapabilities({ depth, maxSpawnDepth });
    const role = storedRole ?? fallback.role;
    const controlScope = storedControlScope ?? resolveSubagentControlScopeForRole(role);
    return {
        depth,
        role,
        controlScope,
        canSpawn: role === "main" || role === "orchestrator",
        canControlChildren: controlScope === "children"
    };
}
```

### 3.4.6 角色对工具可用性的影响

**`main`角色（depth=0）**：
- 拥有完整的工具集。
- 可以调用`sessions_spawn`创建子Agent。
- 可以调用`sessions_list`查看所有子session。
- 可以调用`sessions_send`向子session发送消息。
- 可以调用`sessions_history`查看任何session的历史。

**`orchestrator`角色（0 < depth < maxSpawnDepth）**：
- 可以继续spawn更深层的子Agent。
- 可以控制自己的子Agent。
- 但自身作为子Agent时，被视作父Agent的child对待。
- 可以接收来自父Agent的后续消息（如果是session模式）。

**`leaf`角色（depth >= maxSpawnDepth）**：
- **不能**再spawn任何子Agent（`sessions_spawn`被拒绝）。
- **不能**查看或控制子Agent（因为没有children）。
- 专注于执行分配的任务。
- 任务完成后自动push结果给父Agent。

---

## 3.5 关键常量汇总（Key Constants）

以下是源代码中定义的与`sessions_spawn`和子Agent通信相关的关键常量：

### 3.5.1 Gateway超时常量

```typescript
// 来源: src/agents/subagent-spawn.types.ts

// 控制类Gateway操作超时（如sessions.delete）
const SUBAGENT_CONTROL_GATEWAY_TIMEOUT_MS = 6e4;  // 60,000ms = 60秒

// Agent运行Gateway默认超时
const DEFAULT_SUBAGENT_AGENT_GATEWAY_TIMEOUT_MS = 6e4;  // 60,000ms = 60秒

// Agent运行Gateway最大超时
const MAX_SUBAGENT_AGENT_GATEWAY_TIMEOUT_MS = 3e5;  // 300,000ms = 5分钟
```

**超时计算策略**：
```
gatewayTimeout = clamp(
    runTimeoutSeconds * 1000 + 5000,
    DEFAULT (60s),
    MAX (300s)
)
```
若`runTimeoutSeconds`为0或无效，使用默认60秒。正常范围在60秒到300秒之间。

### 3.5.2 Agent Lane常量

```typescript
// 来源: src/agents/lanes.ts
const AGENT_LANE_SUBAGENT = "subagent";     // 子Agent专用通道
const AGENT_LANE_NESTED = "nested";         // 嵌套Agent通道
const AGENT_LANE_CRON_NESTED = "cron-nested"; // Cron触发的嵌套Agent通道
```

`lane`字段在Gateway调用中扮演路由角色。当`lane = "subagent"`时，Gateway知道这是一个子Agent spawn请求，会应用特定的处理逻辑。

### 3.5.3 Spawn模式常量

```typescript
const SUBAGENT_SPAWN_MODES = ["run", "session"];         // 允许的spawn模式
const SUBAGENT_SPAWN_CONTEXT_MODES = ["isolated", "fork"]; // 允许的上下文模式
```

### 3.5.4 角色与控制范围常量

```typescript
const SUBAGENT_SESSION_ROLES = ["main", "orchestrator", "leaf"];
const SUBAGENT_CONTROL_SCOPES = ["children", "none"];
```

### 3.5.5 Task Name验证常量

```typescript
const SUBAGENT_TASK_NAME_RE = /^[a-z][a-z0-9_]{0,63}$/;
const RESERVED_SUBAGENT_TASK_NAMES = new Set(["all", "last"]);
```

`"all"`和`"last"`被保留是因为在`subagent_target`工具中，这两个值有特殊含义：
- `"all"`：向所有子Agent发送消息
- `"last"`：向最近创建的子Agent发送消息

### 3.5.6 Agent ID验证常量

```typescript
// isValidAgentId 正则: /^[a-z0-9][a-z0-9_-]{0,63}$/
```

### 3.5.7 附件限制默认值

```typescript
// maxTotalBytes 默认: 5 * 1024 * 1024 = 5,242,880 bytes (5MB)
// maxFiles 默认: 50
// maxFileBytes 默认: 1 * 1024 * 1024 = 1,048,576 bytes (1MB)
```

### 3.5.8 子Agent Announce相关常量

```typescript
// 来源: src/agents/subagent-registry.ts
const MIN_ANNOUNCE_RETRY_DELAY_MS = 1000;        // 1秒
const MAX_ANNOUNCE_RETRY_DELAY_MS = 8000;        // 8秒
const ANNOUNCE_EXPIRY_MS = 5 * 60 * 1000;        // 5分钟
const ANNOUNCE_COMPLETION_HARD_EXPIRY_MS = 30 * 60 * 1000; // 30分钟
const FROZEN_RESULT_TEXT_MAX_BYTES = 100 * 1024;  // 100KB
```

这些常量控制子Agent完成通知的重试和超时行为：
- 重试延迟从1秒起，指数退避，最大8秒。
- Announce在5分钟内未成功则放弃。
- 硬过期时间为30分钟。
- 结果文本超过100KB会被截断。

---

## 3.6 子Agent系统提示词构建（Subagent System Prompt）

子Agent在启动时会收到一个专门构建的系统提示词，该提示词通过`buildSubagentSystemPrompt`生成，包含以下关键信息：

```typescript
// 系统提示词包含的信息:
// - requesterSessionKey: 请求者的session key
// - requesterOrigin: 请求者的来源信息（channel, thread等）
// - childSessionKey: 子Agent自己的session key
// - label: 标签
// - task: 任务内容
// - acpEnabled: ACP运行时是否可用
// - nativeCommandGuidanceLines: 注册的插件Agent提示指导
// - childDepth: 子Agent深度
// - maxSpawnDepth: 最大spawn深度
```

结合`buildSubagentInitialUserMessage`中的第一条用户消息，子Agent清楚知道：
1. 自己作为子Agent运行在哪个深度。
2. 结果会自动推送（auto-announce），无需轮询。
3. 如果是持久化session，会持续可用。
4. 需要执行的具体任务。
5. 可用的工具和native命令。
6. 附件的位置和安全警告。

---

## 3.7 子Agent请求者起源追踪（Requester Origin Tracking）

```typescript
// 来源: src/agents/spawn-requester-origin.ts
let childSessionOrigin = resolveRequesterOriginForChild({
    cfg, targetAgentId, requesterAgentId,
    requesterChannel: ctx.agentChannel,
    requesterAccountId: ctx.agentAccountId,
    requesterTo: ctx.agentTo,
    requesterThreadId: ctx.agentThreadId,
    requesterGroupSpace: ctx.agentGroupSpace,
    requesterMemberRoleIds: ctx.agentMemberRoleIds
});
```

这个函数负责为子Agent确定其delivery origin：
- 如果跨Agent spawn，子Agent的origin可能会被调整。
- 如果是同Agent spawn，子Agent继承父Agent的origin。
- Origin信息包括：channel、accountId、to（目标地址）、threadId、groupSpace、memberRoleIds等。

这个信息对于后续的消息路由和完成通知（Push Announce）至关重要，它确保了子Agent完成时结果能够正确地投递回请求者。

---

## 3.8 工作空间继承（Workspace Inheritance）

```typescript
// 来源: src/agents/spawn-requester-origin.ts
const inheritedWorkspaceDir = targetAgentId !== requesterAgentId
    ? void 0
    : toolSpawnMetadata.workspaceDir;

const spawnedWorkspaceDir = resolveSpawnedWorkspaceInheritance({
    config: cfg,
    targetAgentId,
    explicitWorkspaceDir: explicitWorkspaceDir ?? inheritedWorkspaceDir
});
```

**工作空间继承规则**：
- **跨Agent spawn**：不继承工作空间（`inheritedWorkspaceDir = undefined`），子Agent使用目标Agent的默认工作空间。
- **同Agent spawn**：默认继承父Agent的工作空间目录，除非用户明确指定了`cwd`参数。
- **明确指定的`cwd`**：始终优先于继承值。

---

## 3.9 接受后行为指南（Accepted Note Behavior Guide）

当`sessions_spawn`成功返回时，父Agent的工具调用结果中会包含一条`note`字段，这条消息指导父Agent在子Agent运行期间的正确行为：

### Run模式下的note

```
Auto-announce is push-based. After spawning children, do NOT call
sessions_list, sessions_history, exec sleep, or any polling tool.
Track expected child session keys. Continue any independent work.
If your final answer depends on child output, wait for runtime
completion events to arrive as user messages and only answer after
completion events for ALL required children arrive. If a child
completion event arrives AFTER your final answer, reply ONLY with NO_REPLY.
```

### Session模式下的note

```
thread-bound session stays active after this task;
continue in-thread for follow-ups.
```

### 行为约束总结

| 约束 | 原因 |
|------|------|
| 禁止调用`sessions_list` | 轮询违反push-based设计，增加系统负载 |
| 禁止调用`sessions_history` | 同上，且子Agent可能尚未开始执行 |
| 禁止调用`exec sleep`或任何polling工具 | 不应忙等待 |
| 追踪`childSessionKey` | 需要匹配完成通知中的session key |
| 继续独立工作 | push-based设计允许并行执行 |
| 等待所有子Agent完成后才回答 | 确保最终答案的完整性 |
| 完成通知在最终答案之后到达时回复`NO_REPLY` | 防止上下文污染 |

---

## 3.10 完整流程图总结

```
sessions_spawn(params, ctx)
│
├── Step 1: normalizeSubagentTaskName(params.taskName)
│           isValidAgentId(params.agentId)
│           resolveSpawnMode → "run" | "session"
│
├── Step 2: getSubagentDepthFromSessionStore(requesterInternalKey)
│           if callerDepth >= maxSpawnDepth → forbidden
│
├── Step 3: countActiveRunsForSession(requesterInternalKey)
│           if activeChildren >= maxChildren → forbidden
│
├── Step 4: resolveSubagentTargetPolicy → allowAgents检查
│           resolveSubagentSpawnOwnership → 3个key
│
├── Step 5: resolveSandboxRuntimeStatus(parent & child)
│           沙箱继承规则检查
│
├── Step 6: childSessionKey = `agent:{id}:subagent:{uuid}`
│
├── Step 7: childDepth = callerDepth + 1
│           resolveSubagentCapabilities → role, controlScope
│
├── Step 8: resolveSubagentModelAndThinkingPlan
│           → resolvedModel, thinkingOverride, initialSessionPatch
│
├── Step 9: patchChildSession → session store持久化
│           persistInitialChildSessionRuntimeModel → runtime store
│
├── Step 10: prepareSubagentSessionContext
│            "isolated" → 空白上下文
│            "fork" → 从父Agent复制转录本
│
├── Step 11: materializeSubagentAttachments
│            文件名校验、大小限制、Base64解码、SHA256校验
│
├── Step 12: (可选) ensureThreadBindingForSubagentSpawn
│            subagent_spawning钩子 → 线程绑定
│
├── Step 9b: patchChildSession (spawnedBy + workspaceDir)
│
├── Step 13: prepareContextEngineSubagentSpawn
│            上下文引擎初始化 → 返回preparation (含rollback)
│
├── Step 14: callSubagentGateway({
│                method: "agent",
│                lane: AGENT_LANE_SUBAGENT,
│                message: childTaskMessage,
│                extraSystemPrompt: childSystemPrompt
│            })
│
├── Step 15: registerSubagentRun → subagentRuns Map + 磁盘持久化
│            createRunningTaskRun → 后台任务跟踪
│            waitForSubagentCompletion → 启动后台等待
│
├── Step 16: hookRunner.runSubagentSpawned → 通知插件
│            emitSessionLifecycleEvent("create")
│
└── 返回 { status: "accepted", childSessionKey, runId, mode, note }
```

---

## 3.11 总结

`sessions_spawn`是OpenClaw多Agent系统的核心机制，通过精心设计的30+步骤流程，实现了：

1. **安全的参数校验**：taskName正则、agentId正则、保留字检查。
2. **层次深度控制**：通过maxSpawnDepth限制递归创建深度，防止无限递归。
3. **并发管控**：通过maxChildrenPerAgent限制同时活跃的子Agent数量。
4. **三层角色体系**：main → orchestrator → leaf，自动分配角色和控制范围。
5. **灵活的沙箱策略**：支持inherit和require两种模式，确保沙箱安全性。
6. **两种上下文模式**：isolated（独立）和fork（继承），满足不同场景需求。
7. **附件安全传输**：严格的文件名验证、大小限制、Base64校验、SHA256完整性保护。
8. **完整的错误回滚**：每个步骤失败都有对应的清理逻辑，不留下僵尸session。
9. **Push-based通信**：子Agent完成后推送给父Agent，避免轮询。
10. **生命周期追踪**：从创建到完成到清理，完整的事件钩子体系。

这个系统的设计体现了分布式Agent编排的核心思想：在保证安全和控制的前提下，最大化Agent的自主性和并行执行能力。
# 三、多Agent通信与调度（下）：Push Announce、sessions_yield与子Agent管理

本章深入剖析OpenClaw子Agent（subagent）生态系统的下半部分：子Agent如何将完成结果主动推送给调用者（Push Announce机制）、子Agent如何优雅地等待后代任务完成（sessions_yield机制），以及完整的子Agent控制与管理体系（生命周期状态机、控制工具、孤儿恢复、可见性范围）。本章是理解OpenClaw分布式Agent协作模型核心通信协议的关键。

---

## 3.8 Push Announce机制：核心通信协议

当一个子Agent运行结束（正常完成、超时或出错），它的运行结果需要传递回调用者。OpenClaw采用**Push（推）模型**而非Poll（轮询）模型，这一设计决策贯穿整个系统提示词和运行时实现。

### 3.8.1 为什么是Push而不是Poll

在传统的子任务调度中，调用者通常会周期性检查子任务的状态（Poll模型）。OpenClaw明确指出这种模式是不可取的。系统提示词中反复强调：

```
Auto-announce is push-based. After spawning children, do NOT
call sessions_list, sessions_history, exec sleep, or any
polling tool. Track expected child session keys. Continue any
independent work. If your final answer depends on child output,
wait for runtime completion events to arrive as user messages...
```

原因如下：

1. **Token浪费**：Poll需要反复发起LLM调用，每次调用都要消耗输入/输出token。对于可能长时间运行的子任务（如代码生成、搜索、数据处理），Poll的开销远大于实际工作。

2. **信号延迟**：Poll间隔意味着完成信号最多有间隔时间的延迟。Push模型在子任务完成时立即触发通知，延迟仅为网络传输和消息排队时间。

3. **并发安全**：在多个子Agent并发的场景下，Poll会导致调用者会话的对话历史被大量轮询消息污染，这些消息对后续的状态追踪和上下文理解造成干扰。

4. **Agent行为规范化**：LLM天然倾向于"主动检查"，但轮询模式容易退化为无限循环，Push模型通过运行时的硬约束保证了行为可预测性。

### 3.8.2 runSubagentAnnounceFlow：Announce流程总览

`runSubagentAnnounceFlow`（位于`subagent-announce.ts`）是子Agent完成通知的主入口函数，完整执行了七步流程：

```javascript
// 源码: src/agents/subagent-announce.ts
async function runSubagentAnnounceFlow(params) {
    let didAnnounce = false;
    const expectsCompletionMessage = params.expectsCompletionMessage === true;
    const announceType = params.announceType ?? "subagent task";
    let shouldDeleteChildSession = params.cleanup === "delete";

    // 第一步: 等待Embedded PI Run结束
    if (childSessionId && isEmbeddedPiRunActive(childSessionId)) {
        if (!await waitForEmbeddedPiRunEnd(childSessionId, settleTimeoutMs)
            && isEmbeddedPiRunActive(childSessionId)) {
            shouldDeleteChildSession = false;
            return false;
        }
    }

    // 第二步: 等待子Agent运行结果(waitForSubagentRunOutcome)
    if (!reply && params.waitForCompletion !== false) {
        const applied = applySubagentWaitOutcome({
            wait: await waitForSubagentRunOutcome(params.childRunId, settleTimeoutMs),
            outcome, startedAt: params.startedAt, endedAt: params.endedAt
        });
        outcome = applied.outcome;
    }

    // 第三步: 读取子Agent输出(readSubagentOutput)
    if (!reply && allowFailedOutputCapture)
        reply = await readSubagentOutput(params.childSessionKey, outcome);

    // 第四步: 检查后代子Agent(descendant subagents)
    // ... (见下文3.10节)

    // 第五步: 构建内部事件(internal events)
    const internalEvents = [{
        type: "task_completion",
        source: announceType === "cron job" ? "cron" : "subagent",
        childSessionKey, childSessionId, taskLabel,
        status: outcome.status, statusLabel, result: findings,
        statsLine, replyInstruction
    }];

    // 第六步: 解析目标请求者
    // ... (requester resolution)

    // 第七步: 投递Announcement
    const delivery = await deliverSubagentAnnouncement({
        requesterSessionKey, announceId, triggerMessage,
        internalEvents, ...
    });

    didAnnounce = delivery.delivered;
}
```

### 3.8.3 第一步：waitForEmbeddedPiRunEnd

子Agent运行在一个Embedded PI (Process Instance)上下文中。Announce流程首先需要确认该PI是否已经结束。`waitForEmbeddedPiRunEnd` 等待嵌入的运行实例完成，超时时间由 `settleTimeoutMs` 控制（最大120秒，即 `Math.min(Math.max(params.timeoutMs, 1), 12e4)`）。

如果PI在超时后仍未结束，并且子Agent会话设置为 `cleanup === "delete"`，则放弃Announce流程，保留子会话等待后续处理。

### 3.8.4 第二步：waitForSubagentRunOutcome

`waitForSubagentRunOutcome` 通过gateway调用 `agent.wait` 方法等待子Agent运行的最终状态：

```javascript
// 源码: src/agents/subagent-announce-output.ts
async function waitForSubagentRunOutcome(runId, timeoutMs) {
    const waitMs = Math.max(0, Math.floor(timeoutMs));
    return await subagentAnnounceOutputDeps.callGateway({
        method: "agent.wait",
        params: { runId, timeoutMs: waitMs },
        timeoutMs: waitMs + 2e3
    });
}
```

`agent.wait` 返回的结果可能包含以下状态：

- `status: "ok"` -- 子Agent正常完成
- `status: "timeout"` -- 子Agent运行超时
- `status: "error"` -- 子Agent运行出错
- `yielded: true` -- 子Agent调用了 `sessions_yield` 主动暂停（详见3.10节）

`applySubagentWaitOutcome` 将等待结果与已有outcome合并，补充时间信息（`startedAt`, `endedAt`, `elapsedMs`）。

### 3.8.5 第三步：readSubagentOutput -- 子Agent输出收集

`readSubagentOutput` 是核心的输出提取函数，它从子Agent的会话历史中提取有效文本：

```javascript
// 源码: src/agents/subagent-announce-output.ts
async function readSubagentOutput(sessionKey, outcome) {
    // 1. 调用gateway获取聊天历史(最多100条消息)
    const history = await subagentAnnounceOutputDeps.callGateway({
        method: "chat.history",
        params: { sessionKey, limit: 100 }
    });
    // 2. 摘要分析历史消息
    const snapshot = summarizeSubagentOutputHistory(
        Array.isArray(history?.messages) ? history.messages : []
    );
    // 3. 选择合适的输出文本
    const selected = selectSubagentOutputText(snapshot, outcome);
    if (selected?.trim()) return selected;

    // 4. 回退: 读取最新assistant回复
    if (snapshot.waitingForContinuation) return;
    const latestAssistant = await subagentAnnounceOutputDeps.readLatestAssistantReply({
        sessionKey, limit: 100
    });
    return latestAssistant?.trim() ? latestAssistant : void 0;
}
```

#### summarizeSubagentOutputHistory：消息摘要分析

对每条消息逐一分析，维护一个快照对象：

```javascript
function summarizeSubagentOutputHistory(messages) {
    const snapshot = {
        assistantFragments: [],    // assistant文本片段列表
        toolCallCount: 0,          // 工具调用计数
        // 动态跟踪字段:
        // latestAssistantText      // 最新有效assistant文本
        // latestSilentText         // 最新静默回复(SILENT_REPLY/ANNOUNCE_SKIP/NO_REPLY)
        // latestRawText           // 最新原始文本
        // waitingForContinuation   // 是否等待yield后继续
    };
```

处理逻辑：

1. **跳过yield调用和yield结果**：如果assistant消息调用了 `sessions_yield`，则重置快照（清空assistantFragments，设置 `waitingForContinuation = true`）。如果toolResult包含 `status: "yielded"`，同样重置快照。

2. **跳过静默回复**：如果文本匹配 `ANNOUNCE_SKIP` 或 `NO_REPLY`，记录到 `latestSilentText` 但不加入fragments。

3. **提取assistant文本**：使用 `extractSubagentOutputText` 从消息中提取文本内容，累积到 `assistantFragments` 数组中。

4. **统计工具调用**：`countAssistantToolCalls` 统计content block中类型为 `toolCall`/`tool_use`/`toolUse`/`functionCall` 的块数量。

#### selectSubagentOutputText：输出文本选择策略

```javascript
function selectSubagentOutputText(snapshot, outcome) {
    if (snapshot.waitingForContinuation) return;      // 等待继续，无输出
    if (snapshot.latestSilentText) return snapshot.latestSilentText;
    if (snapshot.latestAssistantText) return snapshot.latestAssistantText;
    const partialProgress = formatSubagentPartialProgress(snapshot, outcome);
    if (partialProgress) return partialProgress;
    return snapshot.latestRawText;                     // 最后的兜底选择
}
```

选择优先级：`waitingForContinuation跳过 > latestSilentText > latestAssistantText > partialProgress > latestRawText`。

其中 `formatSubagentPartialProgress` 在超时且有工具调用时生成部分进度摘要：
```
[Partial progress: N tool call(s) executed before timeout]
(最后3段assistant文本片段)
```

### 3.8.6 第五步：构建内部事件 -- Completion Notification结构

Announce流程中最关键的产出是 `internalEvents` 数组，它承载了子Agent完成的全部信息：

```javascript
const internalEvents = [{
    type: "task_completion",
    source: "subagent" | "cron",
    childSessionKey: params.childSessionKey,
    childSessionId: announceSessionId,
    announceType,
    taskLabel,
    status: outcome.status,
    statusLabel,
    result: findings,
    statsLine,
    replyInstruction
}];
```

字段详解：

| 字段 | 类型 | 说明 |
|------|------|------|
| `type` | string | 固定为 `"task_completion"`，标识这是一个任务完成事件 |
| `source` | string | `"subagent"` 表示普通子Agent完成，`"cron"` 表示定时任务完成 |
| `childSessionKey` | string | 子Agent的会话键，形如 `agent:{agentId}:subagent:{uuid}` |
| `childSessionId` | string | 子Agent的会话ID |
| `taskLabel` | string | 任务标签（来自 `label` 参数或任务文本前缀） |
| `status` | string | 完成状态：`"ok"`、`"timeout"`、`"error"`、`"unknown"` |
| `statusLabel` | string | 人类可读的状态描述 |
| `result` | string | 子Agent的输出结果（文本） |
| `statsLine` | string | 统计信息行，格式如 `"Stats: runtime 42s * tokens 12.5k (in 8.2k / out 4.3k)"` |
| `replyInstruction` | string | 指示接收者如何响应此通知的指令 |

#### replyInstruction：上下文感知的回复指令

回复指令根据接收者类型分两种场景：

**场景A -- 接收者是子Agent（内部编排）：**
```
Convert this completion into a concise internal orchestration update
for your parent agent in your own words. Keep this internal context
private (don't mention system/log/stats/session details or announce
type). If this result is duplicate or no update is needed, reply
ONLY: NO_REPLY.
```

**场景B -- 接收者是主Agent（用户面向）：**
```
A completed subagent task is ready for parent review. Review/verify
the result above before deciding whether the original task is done.
If additional action is required, continue the task or record a
follow-up; otherwise send a truthful user-facing update. Keep this
internal context private (don't mention system/log/stats/session
details or announce type), and do not copy the internal event text
verbatim. Reply ONLY: NO_REPLY if this exact result was already
delivered to the user in this same turn.
```

### 3.8.7 第七步：deliverSubagentAnnouncement -- 双路径投递

Announce的投递是两条路径的编排，由 `runSubagentAnnounceDispatch` 控制：

```javascript
// 源码: src/agents/subagent-announce-dispatch.ts
async function runSubagentAnnounceDispatch(params) {
    // 路径一: steer优先(如果在期望完成消息的上下文中则direct优先)
    if (!params.expectsCompletionMessage) {
        // 不需要完成消息: 先steer，失败则direct
        const primarySteer = await params.steer();
        if (primarySteer.delivered) return primarySteer;
        if (primarySteerOutcome.status === "dropped") return primarySteer;
        const primaryDirect = await params.direct();
        return primaryDirect;
    }
    // 需要完成消息: 先direct，失败则steer
    const primaryDirect = await params.direct();
    if (primaryDirect.delivered) return primaryDirect;
    const fallbackSteer = await params.steer();
    if (fallbackSteer.delivered) return fallbackSteer;
    return primaryDirect;
}
```

投递顺序总结：

| 场景 | 首选路径 | 备选路径 |
|------|---------|---------|
| 不需要完成消息 (`expectsCompletionMessage: false`) | Steer | Direct |
| 需要完成消息 (`expectsCompletionMessage: true`) | Direct | Steer |

#### 3.8.7.1 Steer路径：queueEmbeddedPiMessageWithOutcome

Steer路径通过 `maybeSteerSubagentAnnounce` 将Announce消息注入到父会话的活动PI中：

```javascript
async function maybeSteerSubagentAnnounce(params) {
    const { sessionId } = resolveRequesterSessionActivity(canonicalKey);
    if (!sessionId) return { status: "none" };

    const queueOutcome = await resolveQueueEmbeddedPiMessageOutcome(
        sessionId, params.steerMessage, queueOptions
    );

    if (queueOutcome.queued) return {
        status: "steered",
        deliveredAt: queueOutcome.deliveredAtMs,
        enqueuedAt: queueOutcome.enqueuedAtMs
    };

    return {
        status: resolveRequesterSessionActivity(canonicalKey).isActive
            ? "dropped" : "none"
    };
}
```

返回状态有三种：

- `"steered"` -- 消息成功入队到活动父会话
- `"dropped"` -- 父会话活跃但消息无法入队（队列已满/超时）
- `"none"` -- 父会话不活跃

`mapSteerOutcomeToDeliveryResult` 将steer结果映射为统一格式：
```javascript
function mapSteerOutcomeToDeliveryResult(outcome) {
    if (outcome.status === "steered") return {
        delivered: true, path: "steered",
        deliveredAt: outcome.deliveredAt, enqueuedAt: outcome.enqueuedAt
    };
    return { delivered: false, path: "none" };
}
```

#### 3.8.7.2 Direct路径：sendSubagentAnnounceDirectly

Direct路径是一个更复杂的投递流程：

```javascript
async function sendSubagentAnnounceDirectly(params) {
    // 1. 首先尝试通过wake注入活动父会话
    if (params.expectsCompletionMessage && requesterActivity.sessionId) {
        const wakeOutcome = await resolveQueueEmbeddedPiMessageOutcome(
            requesterActivity.sessionId, params.triggerMessage, ...);
        if (wakeOutcome.queued) return {
            delivered: true, path: "steered",
            deliveredAt: wakeOutcome.deliveredAtMs, ...
        };
    }

    // 2. 解析投递目标
    const deliveryTarget = !params.requesterIsSubagent
        ? resolveExternalBestEffortDeliveryTarget({
            channel, to, accountId, threadId
          })
        : { deliver: false };

    // 3. 构建完整的agent调用参数
    const directAgentParams = {
        sessionKey: canonicalRequesterSessionKey,
        message: params.triggerMessage,
        deliver: shouldDeliverAgentFinal,
        internalEvents: params.internalEvents,
        channel, accountId, to, threadId,
        inputProvenance: {
            kind: "inter_session",
            sourceSessionKey: params.sourceSessionKey,
            sourceChannel: params.sourceChannel ?? "webchat",
            sourceTool: params.sourceTool ?? "subagent_announce"
        },
        idempotencyKey: params.directIdempotencyKey
    };

    // 4. 带重试的agent调用
    directAnnounceResponse = await runAnnounceDeliveryWithRetry({
        operation: "direct announce agent call",
        signal: params.signal,
        run: async () => await runAnnounceAgentCall({
            agentParams: directAgentParams,
            expectFinal: true,
            timeoutMs: announceTimeoutMs
        })
    });

    // 5. 验证投递结果
    // - 检查是否pending
    // - 检查message tool delivery
    // - 检查media delivery
    // - 检查可见payload
}
```

Direct路径的验证包括：

1. **Agent run pending检查**：如果response状态为非终态（accepted/started/in_flight）则认为已投递
2. **消息工具投递验证**：如果要求 `sourceReplyDeliveryMode: "message_tool_only"`，必须检查agent是否通过消息工具发送了内容
3. **媒体投递验证**：如果internal events包含 `mediaUrls`，验证agent是否成功投递了这些媒体
4. **可见payload验证**：如果要求 `deliver: true`（外部投递），必须有可见的回复内容

#### 3.8.7.3 错误分类与重试策略

投递错误分为两类：

**临时错误（可重试）：**
```javascript
const TRANSIENT_ANNOUNCE_DELIVERY_ERROR_PATTERNS = [
    /errorcode=unavailable/i,
    /status\s*[:=]\s*"?unavailable\b/i,
    /UNAVAILABLE/,
    /no active .* listener/i,
    /gateway not connected/i,
    /gateway closed \(1006/i,
    /gateway timeout/i,
    /all models failed\b/i,
    /overloaded\b/i,
    /(econnreset|econnrefused|etimedout|enotfound|ehostunreach|network error)\b/i
];
```

**永久错误（不可重试）：**
```javascript
const PERMANENT_ANNOUNCE_DELIVERY_ERROR_PATTERNS = [
    /unsupported channel/i,
    /unknown channel/i,
    /chat not found/i,
    /user not found/i,
    /bot.*not.*member/i,
    /bot was blocked by the user/i,
    /forbidden: bot was kicked/i,
    /recipient is not a valid/i,
    /outbound not configured for channel/i
];
```

重试采用指数退避策略（生产环境：5秒、10秒、20秒；测试环境：8ms、16ms、32ms），最多3次尝试：

```javascript
function resolveDirectAnnounceTransientRetryDelaysMs() {
    return process.env.OPENCLAW_TEST_FAST === "1"
        ? [8, 16, 32] : [5000, 10000, 20000];
}
```

### 3.8.8 Announce Idempotency Keys

为确保Announce的幂等性，每次Announce都带有唯一标识：

```javascript
// 源码: src/agents/announce-idempotency.ts
function buildAnnounceIdFromChildRun(params) {
    return `v1:${params.childSessionKey}:${params.childRunId}`;
}

function buildAnnounceIdempotencyKey(announceId) {
    return `announce:${announceId}`;
}
```

- **announceId**格式：`v1:{childSessionKey}:{childRunId}` -- 版本前缀+会话键+运行ID组合，全局唯一
- **idempotencyKey**格式：`announce:{announceId}` -- 加前缀以便在gateway中识别为Announce专用幂等键

对于wake场景，额外添加后缀：
```javascript
idempotencyKey: buildAnnounceIdempotencyKey(`${params.announceId}:wake`)
```

这样即使同一次运行被多次wake（如后代完成后反复唤醒），每次使用的幂等键也是不同的。

### 3.8.9 接收者解析：resolveAnnounceOrigin

Announce的接收者原始信息（origin）需要根据实际投递场景进行修正：

```javascript
function resolveAnnounceOrigin(entry, requesterOrigin) {
    const normalizedRequester = normalizeDeliveryContext(requesterOrigin);
    const normalizedEntry = deliveryContextFromSession(entry);

    // 内部消息通道：仅保留accountId和threadId
    if (normalizedRequester?.channel && isInternalMessageChannel(normalizedRequester.channel))
        return mergeDeliveryContext(
            { accountId: normalizedRequester.accountId,
              threadId: normalizedRequester.threadId },
            normalizedEntry
        );

    // 外部通道：合并请求者和会话上下文，必要时剥离thread信息
    return mergeDeliveryContext(normalizedRequester,
        normalizedEntry && shouldStripThreadFromAnnounceEntry(
            normalizedRequester, normalizedEntry)
        ? (({ threadId: _, ...rest }) => rest)(normalizedEntry)
        : normalizedEntry
    );
}
```

此外，`resolveSubagentCompletionOrigin` 还尝试通过**绑定投递路由器**（Bound Delivery Router）为完成消息找到精确的会话绑定投递目标（如Discord线程、Slack频道），这是"persistent session"模式下子Agent投递的关键路径。

### 3.8.10 wakeSubagentRunAfterDescendants

当子Agent因为等待后代而调用 `sessions_yield` 暂停后，后代完成时需要唤醒它。这正是 `wakeSubagentRunAfterDescendants` 的职责：

```javascript
async function wakeSubagentRunAfterDescendants(params) {
    // 1. 检查子Agent会话是否有效
    if (!hasUsableSessionEntry(loadSessionEntryByKey(params.childSessionKey)))
        return false;

    // 2. 构建wake消息
    const wakeMessage = buildDescendantWakeMessage({
        findings: params.findings,
        taskLabel: params.taskLabel
    });

    // 3. 发起新的agent调用(带幂等键)
    wakeRunId = normalizeOptionalString(
        (await runAnnounceDeliveryWithRetry({
            operation: "descendant wake agent call",
            run: async () => await dispatchGatewayMethodInProcess("agent", {
                sessionKey: params.childSessionKey,
                message: wakeMessage,
                deliver: false,
                inputProvenance: {
                    kind: "inter_session",
                    sourceSessionKey: params.childSessionKey,
                    sourceChannel: "webchat",
                    sourceTool: "subagent_announce"
                },
                idempotencyKey: buildAnnounceIdempotencyKey(
                    `${params.announceId}:wake`
                )
            }, { timeoutMs: announceTimeoutMs })
        }))?.runId
    ) ?? "";

    // 4. 用新runId替换旧run
    return replaceSubagentRunAfterSteer({
        previousRunId: params.runId,
        nextRunId: wakeRunId,
        preserveFrozenResultFallback: true
    });
}
```

Wake消息的结构：
```
[Subagent Context] Your prior run ended while waiting for descendant
subagent completions.
[Subagent Context] All pending descendants for that run have now settled.
[Subagent Context] Continue your workflow using these results. Spawn more
subagents if needed, otherwise send your final answer.

Task: {taskLabel}

{findings}
```

Wake机制的关键特征：
- 使用幂等键 `announce:{announceId}:wake` 防止重复wake
- 通过 `replaceSubagentRunAfterSteer` 原子地替换run记录
- `WAKE_RUN_SUFFIX = ":wake"` 作为runId后缀，`isWakeContinuationRun` 检测是否已被wake过，防止无限wake循环

---

## 3.9 sessions_yield 机制

### 3.9.1 概念与目的

`sessions_yield` 是子Agent在等待后代完成时使用的一个特殊工具。当子Agent spawned了多个子子Agent（后代），但发现自己的输出依赖于这些后代的完成结果时，它可以调用 `sessions_yield` 主动结束当前turn，将控制权交还给运行时，声明自己"正在等待后代"。

系统提示词明确指示：

```
If required completions have not arrived yet and sessions_yield
is available, call it to end the turn and wait for completion
events as user messages. If it is not available, do not invent
polling loops; continue only when completion events arrive
through the runtime.
```

### 3.9.2 检测sessions_yield调用

`assistantCallsSessionsYield` 检测assistant消息是否包含 `sessions_yield` 工具调用：

```javascript
function assistantCallsSessionsYield(message) {
    const record = asRecord(message);
    if (!record || record.role !== "assistant"
        || !Array.isArray(record.content)) return false;
    return record.content.some(
        (block) => isToolCallBlock(block)
            && readToolName(block) === "sessions_yield"
    );
}
```

`isSessionsYieldToolResult` 检测工具结果是否确认了yield：
```javascript
function isSessionsYieldToolResult(message, previousAssistantCalledYield) {
    // 1. role必须是toolResult/tool
    // 2. tool名直接是sessions_yield
    // 3. 或者details.content中包含status:"yielded"
    return readStructuredToolPayload(record.content)?.status === "yielded";
}
```

### 3.9.3 完整工作流程

`sessions_yield` 的完整生命周期如下：

```
步骤1: 子Agent spawned后代
  └─ 子Agent调用sessions_spawn创建后代子Agent

步骤2: 后代尚未完成
  └─ 子Agent检测到需要等待后代结果

步骤3: 子Agent调用sessions_yield
  └─ assistant消息包含toolCall: sessions_yield
  └─ 当前turn结束，控制权交还运行时

步骤4: 运行时标记暂停状态
  └─ markSubagentRunPausedAfterYield设置:
      pauseReason = "sessions_yield"
      outcome被清除(因为还不是最终状态)
      endedReason被清除
      cleanupHandled重置为false

步骤5: 后代完成时唤醒
  └─ 后代announce流程检查父Agent是否有pending descendants
  └─ 调用wakeSubagentRunAfterDescendants
  └─ 子Agent收到 "[Subagent Context]" wake消息

步骤6: 子Agent继续执行
  └─ 子Agent收到后代完成结果
  └─ 可以spawn更多后代或生成最终答案
```

### 3.9.4 markSubagentRunPausedAfterYield

当agent.wait返回 `yielded: true` 时，运行时调用此函数标记暂停：

```javascript
function markSubagentRunPausedAfterYield(params) {
    const { entry } = params;
    let mutated = false;

    // 记录开始时间
    if (typeof params.startedAt === "number") {
        entry.startedAt = params.startedAt;
        if (typeof entry.sessionStartedAt !== "number")
            entry.sessionStartedAt = params.startedAt;
        mutated = true;
    }

    // 记录结束时间
    const endedAt = typeof params.endedAt === "number"
        ? params.endedAt : params.now ?? Date.now();
    if (entry.endedAt !== endedAt) {
        entry.endedAt = endedAt;
        mutated = true;
    }

    // 设置暂停原因
    if (entry.pauseReason !== "sessions_yield") {
        entry.pauseReason = "sessions_yield";
        mutated = true;
    }

    // 清除outcome(因为还没有最终结果)
    if (entry.outcome !== void 0) {
        entry.outcome = void 0;
        mutated = true;
    }

    // 清除endedReason
    if (entry.endedReason !== void 0) {
        entry.endedReason = void 0;
        mutated = true;
    }

    // 允许后续cleanup
    if (entry.cleanupHandled === true) {
        entry.cleanupHandled = false;
        mutated = true;
    }

    // 清除冻结结果(因为结果会变)
    if (entry.frozenResultText !== void 0) {
        entry.frozenResultText = void 0;
        entry.frozenResultCapturedAt = void 0;
        mutated = true;
    }

    return mutated;
}
```

### 3.9.5 输出收集中的yield感知

`summarizeSubagentOutputHistory` 在处理消息时特别处理yield：

- 遇到 `sessions_yield` 调用消息时：重置所有已收集的文本，设置 `waitingForContinuation = true`
- 遇到yield结果消息时：同上重置
- `selectSubagentOutputText` 在 `snapshot.waitingForContinuation` 为true时返回 `undefined`，表示"还没有可用的输出"

这意味着，如果一个子Agent在最终输出前调用了 `sessions_yield`，announce系统不会错误地提取到不完整的中间输出。

---

## 3.10 子Agent生命周期状态机

### 3.10.1 核心状态

子Agent的完整生命周期可以用以下状态机描述：

```
 REGISTERED ──► RUNNING ──► ENDED ──► DELIVERED/SUSPENDED ──► CLEANED/ARCHIVED
      │            │           │              │
      │            │           │              │
      ▼            ▼           ▼              ▼
   (失败)      (killed)    (orphan)      (give-up)
```

状态详解：

| 状态 | 判定条件 | 说明 |
|------|---------|------|
| **REGISTERED** | `createdAt`存在，`startedAt`不存在 | run已注册但尚未开始执行 |
| **RUNNING** | `startedAt`存在，`endedAt`不存在，`isLiveUnendedSubagentRun`为true | 正在执行中 |
| **ENDED** | `endedAt`存在 | 运行已结束(正常/超时/错误) |
| **YIELDED** | `pauseReason === "sessions_yield"` | 主动yield等待后代 |
| **DELIVERED** | `completionDeliveredAt` 或 `completionAnnouncedAt`存在 | 完成结果已投递给请求者 |
| **SUSPENDED** | `deliverySuspendedAt`存在 | 投递暂停(cleanup=keep模式下等待后续) |
| **CLEANED** | `cleanupCompletedAt`存在 | cleanup流程完成 |
| **ARCHIVED** | 超过 `archiveAfterMinutes`(默认60分钟) | 从内存运行注册表中移除 |

### 3.10.2 会话状态映射

`resolveSubagentSessionStatus` 将会话映射为用户可见的状态：

```javascript
function resolveSubagentSessionStatus(entry) {
    if (!entry.endedAt) return "running";
    if (entry.endedReason === "subagent-killed") return "killed";
    const status = entry.outcome?.status;
    if (status === "error") return "failed";
    if (status === "timeout") return "timeout";
    return "done";
}
```

### 3.10.3 结束原因分类

```javascript
const SUBAGENT_ENDED_REASON_COMPLETE = "subagent-complete";  // 正常完成
const SUBAGENT_ENDED_REASON_ERROR    = "subagent-error";      // 运行错误
const SUBAGENT_ENDED_REASON_KILLED   = "subagent-killed";     // 被kill
```

对应的outcome：
```javascript
const SUBAGENT_ENDED_OUTCOME_ERROR   = "error";
const SUBAGENT_ENDED_OUTCOME_TIMEOUT = "timeout";
const SUBAGENT_ENDED_OUTCOME_KILLED  = "killed";
```

### 3.10.4 Timeout和Killed路径

**Kill路径**（`killSubagentRun`）：

1. 通过 `abortEmbeddedPiRun(sessionId)` 终止嵌入PI
2. 清除会话队列（followup和lane队列）
3. 在会话store中设置 `abortedLastRun = true`
4. 调用 `markSubagentRunTerminated` 标记run记录
5. 递归kill所有后代（`cascadeKillChildren`）

**Timeout路径**：

1. `agent.wait` 返回 `status: "timeout"`
2. 设置 `outcome: { status: "timeout" }`
3. 进入正常的announce/cleanup流程
4. cleanup时如果最终状态不确定，调度延迟重试

### 3.10.5 Archive机制

运行结束后，子Agent的run记录不会立即从内存中清除。archive机制在注册的run记录上设置过期时间：

```javascript
function resolveArchiveAfterMs(cfg) {
    const minutes = cfg.agents?.defaults?.subagents?.archiveAfterMinutes ?? 60;
    if (!Number.isFinite(minutes) || minutes < 0) return;
    if (minutes === 0) return;  // 0表示不archive(如session模式)
    return Math.max(1, Math.floor(minutes)) * 60000;
}
```

当run被steer替换或cleanup完成时，计算archive时间：
```javascript
const archiveAtMs = spawnMode === "session" || source.cleanup === "keep"
    ? void 0
    : archiveAfterMs ? now + archiveAfterMs : void 0;
```

### 3.10.6 cleanup流程完整路径

`finalizeSubagentCleanup` 处理运行结束后的最终清理：

```
finalizeSubagentCleanup(runId, cleanup, didAnnounce)
│
├─ didAnnounce == true (Announce成功)
│   ├─ 记录completionDeliveredAt/completionAnnouncedAt
│   ├─ 清除pendingFinalDelivery
│   ├─ 设置deliveryStatus为"delivered"
│   ├─ 如果cleanup === "delete": 删除attachments
│   └─ completeCleanupBookkeeping (CLEANED状态)
│
└─ didAnnounce == false (Announce失败)
    │
    ├─ defer-descendants (有活跃后代)
    │   ├─ 设置wakeOnDescendantSettle = true
    │   └─ 调度延迟resume
    │
    ├─ retry (未超过重试上限)
    │   ├─ 设置announceRetryCount
    │   ├─ 标记pendingFinalDelivery
    │   └─ 调度延迟resume
    │
    └─ give-up (超出重试上限或超过expiry)
        ├─ suspend (如果是cleanup=keep且有completion期望)
        │   └─ SUSPENDED状态
        │
        └─ 最终cleanup
            ├─ 删除attachments
            ├─ completeCleanupBookkeeping (CLEANED状态)
            └─ emitSubagentEndedHook
```

### 3.10.7 Stale检测

长时间未结束的run会被标记为stale：

```javascript
const STALE_UNENDED_SUBAGENT_RUN_MS = 7200 * 1000;  // 2小时
const EXPLICIT_TIMEOUT_STALE_GRACE_MS = 60000;       // 额外1分钟宽限期

function isStaleUnendedSubagentRun(entry, now = Date.now()) {
    if (hasSubagentRunEnded(entry)) return false;
    const startedAt = getSubagentSessionStartedAt(entry);
    if (typeof startedAt !== "number") return false;
    return now - startedAt > resolveStaleCutoffMs(entry);
}
```

Stale run在处理孤儿恢复时作为"缺少session-entry"的后备检测条件。

---

## 3.11 子Agent控制工具

### 3.11.1 工具总览

OpenClaw提供四个子Agent控制工具，通过 `subagents` 工具族暴露给Agent：

| 工具 | 功能 | 命令格式 |
|------|------|---------|
| `subagents list` | 列出受控的子Agent运行 | `subagents list` |
| `subagents kill` | 终止子Agent运行（含递归kill后代） | `subagents kill <target>` |
| `subagents steer` | 向子Agent注入新指令并重启 | `subagents steer <target> <message>` |
| `subagents send` | 向子Agent发送消息并等待回复 | `subagents send <target> <message>` |

### 3.11.2 控制范围（Control Scope）

每个子Agent都有 `controlScope` 属性，由 `resolveSubagentCapabilities` 根据depth层级决定：

- **`"children"`**：可以控制自己的子Agent（orchestrator角色）
- **`"none"`**：不能控制任何子Agent（leaf角色）

```javascript
// 源码: src/agents/subagent-control.ts
function resolveSubagentController(params) {
    // ...
    return {
        controllerSessionKey: callerSessionKey,
        callerSessionKey,
        callerIsSubagent: true,
        controlScope: resolveStoredSubagentCapabilities(
            callerSessionKey, { cfg: params.cfg }
        ).controlScope
    };
}
```

当 `controlScope === "none"` 时，所有控制操作返回 `"Leaf subagents cannot control other sessions."` 错误。

### 3.11.3 subagents list: listControlledSubagentRuns

列出当前会话控制的所有子Agent运行：

```javascript
function listControlledSubagentRuns(controllerSessionKey) {
    const key = controllerSessionKey.trim();
    if (!key) return [];

    const latestByChildSessionKey = buildLatestSubagentRunIndex(
        getSubagentRunsSnapshotForRead(subagentRuns)
    ).latestByChildSessionKey;

    return sortSubagentRuns(
        Array.from(latestByChildSessionKey.values()).filter((entry) => {
            return (entry.controllerSessionKey?.trim()
                || entry.requesterSessionKey?.trim()) === key;
        })
    );
}
```

每个条目包含：`runId`、`childSessionKey`、`task`、`label`、`taskName`、`status`、`outcome`、时间信息等。

### 3.11.4 subagents kill: killControlledSubagentRun + 级联Kill

`killControlledSubagentRun` 的具体流程：

```javascript
async function killControlledSubagentRun(params) {
    // 1. 所有权检查
    const ownershipError = ensureControllerOwnsRun({ controller, entry });
    if (ownershipError) return { status: "forbidden", ... };

    // 2. 控制范围检查
    if (params.controller.controlScope !== "children")
        return { status: "forbidden", error: "Leaf subagents cannot..." };

    // 3. 终止目标子Agent
    const stopResult = await killSubagentRun({ cfg, entry: currentEntry, cache });

    // 4. 递归kill所有后代
    const cascade = await cascadeKillChildren({
        cfg,
        parentChildSessionKey: params.entry.childSessionKey,
        cache, seenChildSessionKeys
    });

    // 5. 返回含cascade信息的结果
    return {
        status: "ok",
        text: `killed ${label}${cascadeText}.`
    };
}
```

#### cascadeKillChildren：递归杀后代

```javascript
async function cascadeKillChildren(params) {
    // 1. 查找父Agent的所有直接子运行
    const childRunsBySessionKey = new Map();
    for (const run of listSubagentRunsForController(params.parentChildSessionKey)) {
        // ...过滤和去重...
    }

    // 2. 逐个kill并递归
    for (const run of childRuns) {
        if (!run.endedAt) {
            await killSubagentRun({ cfg, entry: run, cache });
            killed += 1;
        }
        // 递归处理这个子Agent的后代
        const cascade = await cascadeKillChildren({
            cfg,
            parentChildSessionKey: childKey,
            cache, seenChildSessionKeys
        });
        killed += cascade.killed;
    }
    return { killed, labels };
}
```

`killSubagentRun` 的具体操作：
1. 通过 `abortEmbeddedPiRun(sessionId)` 终止PI
2. 通过 `clearSessionQueues` 清除队列
3. 在session store中设置 `abortedLastRun = true`
4. 调用 `markSubagentRunTerminated` 标记terminated

### 3.11.5 subagents steer: steerControlledSubagentRun

Steer是最复杂的控制操作，它终止子Agent当前运行并用新消息重启：

```javascript
async function steerControlledSubagentRun(params) {
    // 1. 所有权+控制范围检查
    // 2. 速限检查(同一caller-target对2秒内只允许1次)
    const rateKey = `${controller.callerSessionKey}:${entry.childSessionKey}`;
    if (now - (steerRateLimit.get(rateKey) ?? 0) < STEER_RATE_LIMIT_MS)
        return { status: "rate_limited", ... };

    // 3. 标记steer restart(抑制此期间的announce)
    markSubagentRunForSteerRestart(params.entry.runId);

    // 4. 终止当前运行的PI
    abortEmbeddedPiRun(sessionId);

    // 5. 清除队列
    clearSessionQueues([entry.childSessionKey, sessionId]);

    // 6. 等待abort settlement(最多5秒)
    callGateway({ method: "agent.wait", params: { runId, timeoutMs: 5000 } });

    // 7. 发起新的agent调用
    const response = await callGateway({
        method: "agent",
        params: {
            message: params.message,
            sessionKey: entry.childSessionKey,
            idempotencyKey: crypto.randomUUID(),
            deliver: false,
            lane: AGENT_LANE_SUBAGENT,  // "subagent" lane
            timeout: 0
        }
    });

    // 8. 用新runId替换旧run
    replaceSubagentRunAfterSteer({
        previousRunId: params.entry.runId,
        nextRunId: runId,
        runTimeoutSeconds: params.entry.runTimeoutSeconds ?? 0
    });
}
```

速率限制防止Agent在循环中反复steer：

- 同一个caller-target对的steer间隔至少2秒
- 如果被限速，返回 `status: "rate_limited"` 错误

不能steer的条件：
- 子Agent已经完成且没有pending descendants
- caller不是子Agent的controller
- 子Agent不能steer自己

### 3.11.6 subagents send: sendControlledSubagentMessage

与steer不同，send不会终止当前运行，而是在子Agent的会话中注入新消息并等待回复：

```javascript
async function sendControlledSubagentMessage(params) {
    // 1. 所有权+控制范围检查
    // 2. 读取baseline reply(用于检测新回复)
    const baselineReply = await readLatestAssistantReplySnapshot({
        sessionKey: targetSessionKey,
        limit: 50  // SUBAGENT_REPLY_HISTORY_LIMIT
    });

    // 3. 发起内部agent调用
    const response = await callGateway({
        method: "agent",
        params: {
            message: params.message,
            sessionKey: targetSessionKey,
            idempotencyKey: crypto.randomUUID(),
            deliver: false,
            channel: INTERNAL_MESSAGE_CHANNEL,
            lane: AGENT_LANE_SUBAGENT,
            timeout: 0
        }
    });

    // 4. 等待并读取更新的assistant回复
    const result = await waitForAgentRunAndReadUpdatedAssistantReply({
        runId, sessionKey: targetSessionKey,
        timeoutMs: 30000,  // 30秒
        baseline: baselineReply
    });

    // 5. 返回回复文本
    return {
        status: "ok",
        runId,
        replyText: result.replyText
    };
}
```

send的超时时间为30秒（`SUBAGENT_REPLY_HISTORY_LIMIT`相关的超时），处理三种结果：

- `status: "ok"` -- 成功获取子Agent的回复文本
- `status: "timeout"` -- 子Agent在30秒内未生成新回复
- `status: "error"` -- 调用失败

---

## 3.12 孤儿恢复机制

### 3.12.1 什么是孤儿子Agent

孤儿子Agent是指那些由于gateway重启（如SIGUSR1 reload）导致LLM调用被中断，但在子Agent运行注册表中仍然标记为active的会话。特征是：

1. 运行注册表中有尚未ended的active记录
2. 会话store中 `abortedLastRun === true`
3. 会话的LLM调用已被中断，需要恢复

### 3.12.2 recoverOrphanedSubagentSessions

```javascript
async function recoverOrphanedSubagentSessions(params) {
    const result = { recovered: 0, failed: 0, skipped: 0, failedRuns: [] };

    const activeRuns = params.getActiveRuns();
    for (const [runId, runRecord] of activeRuns.entries()) {
        // 1. 检查是否为孤儿
        const entry = store[childSessionKey];
        if (!entry) { result.skipped++; continue; }
        if (!entry.abortedLastRun) { result.skipped++; continue; }

        // 2. 恢复门控检查
        const recoveryGate = evaluateSubagentRecoveryGate(entry, now);
        if (!recoveryGate.allowed) {
            // 标记为wedged(卡住)
            markSubagentRecoveryWedged({ entry, now, runId, reason });
            result.skipped++; continue;
        }

        // 3. 读取会话历史，找到最后一条用户消息
        const messages = await readSessionMessagesAsync(entry.sessionId,
            storePath, entry.sessionFile,
            { mode: "recent", maxMessages: 200, maxBytes: 1024 * 1024 });

        // 4. 构建resume消息并发送
        const resumeResult = await resumeOrphanedSession({
            sessionKey: childSessionKey,
            task: runRecord.task,
            lastHumanMessage,
            configChangeHint,  // 如果检测到配置变更
            originalRunId: runId
        });

        if (resumeResult.resumed) {
            // 清除abortedLastRun标记，记录恢复尝试
            await updateSessionStore(storePath, (currentStore) => {
                current.abortedLastRun = false;
                markSubagentRecoveryAttempt({ entry: current, now, runId, attempt });
            });
            result.recovered++;
        }
    }
}
```

### 3.12.3 Resume消息格式

```javascript
function buildResumeMessage(task, lastHumanMessage) {
    let message = `[System] Your previous turn was interrupted by a gateway
reload. Your original task was:\n\n${task}\n\n`;

    if (lastHumanMessage)
        message += `The last message from the user before the interruption
was:\n\n${lastHumanMessage}\n\n`;

    message += `Please continue where you left off.`;
    return message;
}
```

如果检测到会话历史中有配置变更（如openclaw.json被修改），还会追加：
```
[config changes from your previous run were already applied — do not
re-modify openclaw.json or restart the gateway]
```

### 3.12.4 恢复进度通知

恢复过程中，如果不成功，会向请求者发送进度通知：

```javascript
function buildRecoveryProgressPrompt(params) {
    return `A spawned subagent task was interrupted by a gateway restart
or connection loss. Automatic recovery is already in progress for
"${params.task}" (retry ${params.attemptNumber}/${params.maxAttempts}).
Send one brief update now in your normal voice: say the task was
interrupted, you are automatically resuming/retrying it, and you will
report back when it either continues or truly fails. Do not say the
task has failed.`;
}
```

### 3.12.5 调度策略

孤儿恢复使用 `scheduleOrphanRecovery` 进行延迟调度：

- **初始延迟**：5秒（`DEFAULT_RECOVERY_DELAY_MS`），等待gateway完全启动
- **最大重试次数**：3次（`MAX_RECOVERY_RETRIES`）
- **退避策略**：指数退避，每次延迟乘以2（`RETRY_BACKOFF_MULTIPLIER`）

```javascript
function scheduleOrphanRecovery(params) {
    const initialDelay = params.delayMs ?? 5000;  // 5秒初始延迟
    const maxRetries = params.maxRetries ?? 3;    // 最多3次重试

    const attemptRecovery = (attempt, delay) => {
        setTimeout(() => {
            recoverOrphanedSubagentSessions({ ... }).then((result) => {
                if (result.failed > 0 && attempt < maxRetries) {
                    const nextDelay = delay * 2;  // 指数退避
                    attemptRecovery(attempt + 1, nextDelay);
                }
            });
        }, delay).unref?.();
    };

    attemptRecovery(0, initialDelay);
}
```

### 3.12.6 recoveryState管理

`evaluateSubagentRecoveryGate` 检查恢复是否允许：

- 检查之前的恢复尝试次数
- 检查恢复尝试的时间间隔
- 如果尝试次数超过上限，标记为 `wedged`（卡住），停止恢复

`markSubagentRecoveryAttempt` 记录每次恢复尝试的时间戳和次数。
`markSubagentRecoveryWedged` 标记子Agent为不可恢复状态。

### 3.12.7 恢复失败的最终处理

如果所有恢复尝试都失败，调用 `finalizeInterruptedSubagentRun` 终结运行：

```javascript
function buildRecoveryFailureMessage(params) {
    return `Subagent run was interrupted by a gateway restart or connection
loss. Automatic recovery failed after ${params.attempts} attempt(s).
Please retry.`;
}
```

---

## 3.13 控制范围与可见性

### 3.13.1 spawnedBy链与会话存储

子Agent的父子关系通过session store中的两个关键字段维护：

```javascript
function buildDirectChildSessionPatch(patch) {
    const entry = {};

    if (typeof patch.spawnDepth === "number" && patch.spawnDepth >= 0)
        entry.spawnDepth = Math.floor(patch.spawnDepth);

    if (patch.subagentRole === "orchestrator" || patch.subagentRole === "leaf")
        entry.subagentRole = patch.subagentRole;

    if (patch.subagentControlScope === "children"
        || patch.subagentControlScope === "none")
        entry.subagentControlScope = patch.subagentControlScope;

    if (typeof patch.spawnedBy === "string" && patch.spawnedBy.trim())
        entry.spawnedBy = patch.spawnedBy.trim();

    // ... inheritance: inheritedToolDeny, inheritedToolAllow,
    // thinkingLevel, model
}
```

- **`spawnedBy`**：指向直接父会话的sessionKey
- **`spawnDepth`**：在生成树中的深度（0 = root/main，1 = 直接子，N = N层后代）
- **`subagentRole`**：`"orchestrator"`（可以spawn后代）或 `"leaf"`（不能spawn）
- **`subagentControlScope`**：`"children"`（可控制子Agent）或 `"none"`（不可控制）

### 3.13.2 会话树遍历

运行时通过 `forEachDescendantRun` 遍历完整的后代树：

```javascript
function forEachDescendantRun(runs, rootSessionKey, visitor) {
    const pending = [rootSessionKey];
    const visited = new Set([rootSessionKey]);

    for (let index = 0; index < pending.length; index += 1) {
        const requester = pending[index];

        // 找到该requester的所有直接子运行的"最新版"
        const latestByChildSessionKey = new Map();
        for (const [runId, entry] of runs.entries()) {
            if (entry.requesterSessionKey !== requester) continue;
            const childKey = entry.childSessionKey.trim();
            const existing = latestByChildSessionKey.get(childKey);
            if (!existing || entry.createdAt > existing[1].createdAt)
                latestByChildSessionKey.set(childKey, [runId, entry]);
        }

        // 验证并访问每个子节点
        for (const [runId, entry] of latestByChildSessionKey.values()) {
            // 确认这是该childSessionKey的最新run
            const latestForChild = findLatestRunForChildSession(
                runs, entry.childSessionKey);
            if (!latestForChild || latestForChild.runId !== runId
                || latestForChild.requesterSessionKey !== requester) continue;

            visitor(runId, entry);

            // 将子节点加入待访问队列（BFS）
            const childKey = entry.childSessionKey.trim();
            if (!visited.has(childKey)) {
                visited.add(childKey);
                pending.push(childKey);
            }
        }
    }
}
```

这是一个BFS（广度优先）遍历，从根会话开始，逐层访问所有后代子Agent运行。

### 3.13.3 pending descendants计数

用于判断一个子Agent是否还有待完成的后代，这在announce流程中决定是否defer：

```javascript
function countActiveDescendantRuns(rootSessionKey) {
    let count = 0;
    const pending = [rootSessionKey];
    const visited = new Set([rootSessionKey]);

    for (let index = 0; index < pending.length; index += 1) {
        const requester = pending[index];
        const latestByChild = latestRunByRequesterAndChildSessionKey.get(requester);

        for (const [childSessionKey, pair] of latestByChild.entries()) {
            // 确认是最新run
            if (latestForChildSession.runId !== pair.runId
                || latestForChildSession.entry.requesterSessionKey !== requester)
                continue;

            if (isLiveUnendedSubagentRun(pair.entry, now))
                count += 1;

            if (!visited.has(childSessionKey)) {
                visited.add(childSessionKey);
                pending.push(childSessionKey);
            }
        }
    }
    return count;
}
```

### 3.13.4 父Agent发现子Agent的方式

父Agent通过以下机制感知子Agent：

1. **sessions_spawn返回值**：spawn子Agent后立即获得 `childSessionKey` 和 `runId`
2. **Auto-announce通知**：子Agent完成时自动推送 `task_completion` 内部事件
3. **subagents list**：主动列出自己控制的所有子Agent
4. **session store中的spawnedBy链**：运行时通过遍历spawnedBy关系构建完整的父子图谱

### 3.13.5 可见性边界

子Agent的可见性遵循以下规则：

1. **父可以看见子**：父Agent可以通过 `subagents list` 列出自己的直接子Agent
2. **子无法看见父的后代**：子Agent只能看到自己的直接子Agent，无法穿透到兄弟节点
3. **后代向上传递结果**：后代完成结果自动向上announce，逐层传递
4. **跨Agent不可见**：不同agentId之间的子Agent默认不可见（除非通过spawnedBy链关联）

### 3.13.6 sessions_list的可见性

每个会话的 `sessions_list` 可见性由session store中的元数据控制：

- 会话在store中保存 `spawnedBy`、`spawnDepth`、`subagentRole` 等元数据
- `getSubagentDepthFromSessionStore` 从store中读取sid的depth
- `isCronSessionKey` 检测cron会话，cron的depth被视为 >= 1（内部会话）

---

## 3.14 总结

本章深入剖析了OpenClaw子Agent管理系统的三大核心机制：

**Push Announce机制**是子Agent通信的基础设施。它采用推模型替代轮询，通过七步流程（等待PI结束、获取运行结果、读取输出、检查后代、构建内部事件、解析目标、双路径投递）将子Agent完成结果可靠地传递给父Agent。双路径投递（Steer优先或Direct优先，取决于是否需要完成消息）提供了灵活性和容错性。幂等键机制（`v1:{childSessionKey}:{childRunId}`）确保消息投递的精确一次语义。

**sessions_yield机制**优雅地解决了"等待后代"这一分布式Agent编排的核心问题。子Agent通过调用yield主动暂停，运行时通过`markSubagentRunPausedAfterYield`标记暂停状态，后代完成后通过`wakeSubagentRunAfterDescendants`唤醒。输出收集系统对yield感知，避免提取不完整的中间结果。

**子Agent控制与管理**体系提供了完整的生命周期管理：从`REGISTERED`到`RUNNING`到`ENDED`再到`DELIVERED/SUSPENDED/CLEANED/ARCHIVED`的状态机、kill/steer/send/list四个控制工具、级联kill递归处理整个子树、孤儿恢复机制（检测aborted会话、延迟调度恢复、指数退避重试）、以及基于spawnedBy链的可见性控制。

这些机制共同构成了一个健壮的、可扩展的多Agent通信与调度基础设施，使OpenClaw能够支持复杂的、多层的Agent协作场景（如代码审查Agent spawn build Agent和test Agent，二者再各自spawn更细粒度的子Agent）。
# 四、Skill系统实现（上）：来源架构、目录扫描与安装方式

## 概述

OpenClaw的Skill系统是一套完整的能力扩展框架，允许用户、插件开发者和OpenClaw自身通过声明式的`SKILL.md`文件向AI代理注入专业化的领域知识、工具安装指令和运行时配置。本章（上篇）聚焦于Skill系统的底层基础设施：核心数据结构定义、六层来源架构的加载与合并策略、递归目录扫描算法、五种安装类型的安全验证机制，以及技能资格过滤的完整逻辑链路。

理解本章的内容后，读者将能够：

- 在任意目录创建符合规范的`SKILL.md`为代理添加自定义技能
- 理解技能的优先级覆盖关系，预测同名技能在多层来源中的最终表现
- 掌握从目录扫描到加载、过滤、合并、安装的全链路实现细节
- 为自定义技能编写安全的安装清单，确保`install`块通过正则校验

在开始阅读源码细节之前，让我们先明确两个贯穿全文的重要概念：

- **Skill（技能）**：Skill的本质是通过与一个目录相关联的`SKILL.md`文件来定义的。这个Markdown文件包含YAML frontmatter元数据区和正文说明区。每个Skill都有一个唯一的`name`、一个面向模型的`description`、一个`filePath`（SKILL.md的绝对路径）和一个`baseDir`（Skill的工作目录）。模型中提到的"读取技能文件"实际上就是读取这个`SKILL.md`文件。

- **Skill Record（技能记录）**：Skill Record是Skill的"包装器"，除了Skill本身（包含name、description、filePath等字段）外，还捆绑了frontmatter原始数据、metadata（OpenClaw扩展元数据）、invocation（调用策略）、exposure（曝露策略）等附加信息。后面我们会详细展开这些数据结构。

---

## 4.1 核心数据结构

OpenClaw的Skill系统基于一套精心设计的TypeScript类型体系，这些类型在Webpack打包后的JavaScript模块中表现为运行时对象形状。我们从四个核心数据结构入手：

- **SkillEntry**：技能条目的完整运行时表示
- **OpenClawSkillMetadata**：从SKILL.md frontmatter中解析的OpenClaw扩展元数据
- **SkillExposure**：控制技能在运行时注册表、系统提示和用户调用中的可见性
- **SkillInvocationPolicy**：控制模型和用户是否可以调用该技能

### 4.1.1 SkillEntry -- 技能条目的完整表示

`SkillEntry`是Skill系统中最核心的复合类型，它将从目录扫描加载的原始Skill与其frontmatter、metadata、invocation策略和exposure策略捆绑在一起。以下是`loadSkillEntries`函数（位于`workspace-WZoAbmov.js`第741-762行）中构造`SkillEntry`的实际代码：

```javascript
// workspace-WZoAbmov.js, 第741-762行
return Array.from(merged.values())
    .sort((a, b) => a.skill.name.localeCompare(b.skill.name, "en"))
    .map((record) => {
        const skill = record.skill;
        const frontmatter = record.frontmatter
            ?? readSkillFrontmatterSafe({
                rootDir: skill.baseDir,
                filePath: skill.filePath,
                maxBytes: limits.maxSkillFileBytes
            })
            ?? {};
        const invocation = resolveSkillInvocationPolicy(frontmatter);
        return {
            skill,
            frontmatter,
            metadata: resolveOpenClawMetadata(frontmatter),
            invocation,
            ...record.syncSourceDir !== void 0 ? { syncSourceDir: record.syncSourceDir } : {},
            ...record.syncDirName !== void 0 ? { syncDirName: record.syncDirName } : {},
            exposure: {
                includeInRuntimeRegistry: true,
                includeInAvailableSkillsPrompt: invocation.disableModelInvocation !== true,
                userInvocable: invocation.userInvocable !== false
            }
        };
    });
```

从这段代码中可以清晰地提取出`SkillEntry`的字段结构：

| 字段 | 类型 | 来源 | 说明 |
|------|------|------|------|
| `skill` | `LoadedSkill` | 目录扫描加载 | 技能的核心数据：name、description、filePath、baseDir、source、sourceInfo、disableModelInvocation |
| `frontmatter` | `Record<string, unknown>` | SKILL.md YAML解析 | 所有YAML frontmatter键值对的原始对象；如果record中已有则复用，否则通过`readSkillFrontmatterSafe`重新读取并解析 |
| `metadata` | `OpenClawSkillMetadata \| undefined` | frontmatter解析 | 从frontmatter中提取的OpenClaw扩展字段（always、os、requires、install等） |
| `invocation` | `SkillInvocationPolicy` | frontmatter解析 | 控制调用行为：userInvocable（用户能否手动调用）、disableModelInvocation（是否禁止模型自动调用） |
| `exposure` | `SkillExposure` | 动态构造 | 控制曝露策略：三个布尔字段根据invocation策略计算得出 |
| `syncSourceDir` | `string \| undefined` | 仅受管技能 | 当技能的来源是`openclaw-managed`且经过了规范化处理时，记录其同步源目录 |
| `syncDirName` | `string \| undefined` | 仅受管技能 | 原始baseDir的basename，用于同步时去重和命名 |

#### Skill核心对象

Skill对象本身（`record.skill`）在目录扫描阶段由`loadSingleSkillDirectory`函数（第74-112行）创建：

```javascript
// workspace-WZoAbmov.js, 第95-112行
return {
    skill: {
        name,
        description,
        filePath,
        baseDir,
        source: params.source,
        sourceInfo: createSyntheticSourceInfo(filePath, {
            source: params.source,
            baseDir,
            scope: "project",
            origin: "top-level"
        }),
        disableModelInvocation: invocation.disableModelInvocation
    },
    frontmatter
};
```

Skill对象的字段解析：

- **`name`**：优先使用frontmatter中的`name`字段；如果为空，则回退到技能目录的basename。这意味着即使frontmatter中没有显式写`name`，技能仍然会以目录名作为标识被加载。

- **`description`**：必须从frontmatter中获取且不能为空。如果description缺失或为空，该技能目录将被静默跳过（返回null），因为它无法向模型描述自己的用途。

- **`filePath`**：`SKILL.md`文件的绝对路径（经过`path.resolve`规范化）。

- **`baseDir`**：技能目录的绝对路径。模型在执行技能相关任务时，这个路径会被用作工作目录的参考。

- **`source`**：技能来源标识符（如`"openclaw-workspace"`、`"openclaw-bundled"`、`"openclaw-managed"`等六个值之一）。

- **`sourceInfo`**：通过`createSyntheticSourceInfo`（第20-28行）创建的合成来源信息，包含`path`、`source`、`scope`、`origin`和`baseDir`。

- **`disableModelInvocation`**：从invocation策略中提取的布尔值，控制模型是否能够自主调用此技能。

#### syncSourceDir与syncDirName

这两个字段仅在经过`canonicalizeLoadedSkillRecord`（第411-431行）处理后的受管技能上出现，用于支持技能同步到工作区时的路径规范化和命名冲突解决：

```javascript
// workspace-WZoAbmov.js, 第411-431行
function canonicalizeLoadedSkillRecord(record, canonicalSkillDir) {
    const originalBaseDir = path.resolve(record.skill.baseDir);
    const canonicalBaseDir = path.resolve(canonicalSkillDir);
    if (originalBaseDir === canonicalBaseDir) return record;
    const filePath = path.join(canonicalBaseDir, path.relative(originalBaseDir, record.skill.filePath));
    return {
        ...record,
        syncSourceDir: canonicalBaseDir,
        syncDirName: path.basename(originalBaseDir),
        skill: {
            ...record.skill,
            filePath,
            baseDir: canonicalBaseDir,
            sourceInfo: record.skill.sourceInfo ? {
                ...record.skill.sourceInfo,
                path: filePath,
                baseDir: canonicalBaseDir
            } : record.skill.sourceInfo
        }
    };
}
```

这个函数的调用条件是`source`为`"openclaw-managed"`或`"agents-skills-personal"`，即不受根目录容器约束的来源。当技能的原始baseDir与实际存放目录不一致时（例如ClawHub安装的技能存储在不同的路径下），函数会重新映射所有路径，并记录`syncSourceDir`和`syncDirName`供后续同步使用。

### 4.1.2 OpenClawSkillMetadata -- 扩展元数据

`OpenClawSkillMetadata`从SKILL.md的YAML frontmatter中解析，由`resolveOpenClawMetadata`函数（位于`config-DzI9AwO1.js`第93-109行）负责提取：

```javascript
// config-DzI9AwO1.js, 第93-109行
function resolveOpenClawMetadata(frontmatter) {
    const metadataObj = resolveOpenClawManifestBlock({ frontmatter });
    if (!metadataObj) return;
    const requires = resolveOpenClawManifestRequires(metadataObj);
    const install = resolveOpenClawManifestInstall(metadataObj, parseInstallSpec);
    const osRaw = resolveOpenClawManifestOs(metadataObj);
    return {
        always: typeof metadataObj.always === "boolean" ? metadataObj.always : void 0,
        emoji: readStringValue(metadataObj.emoji),
        homepage: readStringValue(metadataObj.homepage),
        skillKey: readStringValue(metadataObj.skillKey),
        primaryEnv: readStringValue(metadataObj.primaryEnv),
        os: osRaw.length > 0 ? osRaw : void 0,
        requires,
        install: install.length > 0 ? install : void 0
    };
}
```

| 字段 | 类型 | frontmatter来源 | 说明 |
|------|------|-----------------|------|
| `always` | `boolean \| undefined` | `always` | 如果为`true`，技能总是显示，跳过所有运行时资格检查（os、requires、config等全部忽略） |
| `emoji` | `string \| undefined` | `emoji` | 技能的emoji图标，用于UI展示 |
| `homepage` | `string \| undefined` | `homepage` | 技能的主页URL |
| `skillKey` | `string \| undefined` | `skillKey` | 用于配置查找的唯一键，若未设置则回退到`skill.name` |
| `primaryEnv` | `string \| undefined` | `primaryEnv` | 主环境变量名（如`GITHUB_TOKEN`），关联到配置中的API密钥 |
| `os` | `string[] \| undefined` | `os` | 限定的操作系统列表（如`["darwin", "linux"]`），不匹配则技能被过滤 |
| `requires` | `object \| undefined` | `requires` | 运行时依赖声明（bins、anyBins、env、config） |
| `install` | `InstallSpec[] \| undefined` | `install` | 安装指令数组，支持五种安装类型 |

#### requires 子结构

`requires`对象通过`resolveOpenClawManifestRequires`解析，包含四个可选字段：

- **`bins: string[]`** -- 必需的可执行文件列表（全部必须存在）
- **`anyBins: string[]`** -- 可选的二进制文件列表（至少一个必须存在）
- **`env: string[]`** -- 必需的环境变量列表（全部必须设置）
- **`config: string[]`** -- 必需的配置路径列表（全部必须为真值）

这些字段在运行时资格检查中被`evaluateRuntimeRequires`函数逐一验证。

#### install 子结构

`install`数组中的每个元素都是一个安装规范，由`parseInstallSpec`函数解析后规范化。支持的`kind`包括`"brew"`、`"node"`、`"go"`、`"uv"`和`"download"`。每个规范的通用字段包括：

- `kind`: 安装类型
- `id`: 可选的安装唯一标识符
- `label`: 可选的显示标签
- `bins`: 可选的二进制文件列表（安装后检查）
- `os`: 可选的操作系统限制

### 4.1.3 SkillExposure -- 曝露策略

`SkillExposure`控制技能在系统不同层面中的可见性。它不是在frontmatter中声明的，而是从invocation策略和Skill自身属性动态推导出来的（第756-760行）：

```javascript
// workspace-WZoAbmov.js, 第756-760行
exposure: {
    includeInRuntimeRegistry: true,
    includeInAvailableSkillsPrompt: invocation.disableModelInvocation !== true,
    userInvocable: invocation.userInvocable !== false
}
```

| 字段 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| `includeInRuntimeRegistry` | `boolean` | `true` | 是否将技能注册到运行时注册表中（所有技能默认注册） |
| `includeInAvailableSkillsPrompt` | `boolean` | `!disableModelInvocation` | 是否在系统提示的`<available_skills>`块中列出此技能。如果模型的自动调用被禁用，则不暴露给模型 |
| `userInvocable` | `boolean` | `userInvocable !== false` | 用户是否可以通过斜杠命令手动调用此技能 |

这里有一个重要的设计选择：`includeInAvailableSkillsPrompt`和`userInvocable`的默认值是从相反的布尔值推导的。`includeInAvailableSkillsPrompt`默认是`true`（除非`disableModelInvocation`为`true`），而`userInvocable`默认也是`true`（除非显式设为`false`）。这意味着默认情况下，技能既可以被模型看到和调用，也可以被用户手动调用。

在`isSkillVisibleInAvailableSkillsPrompt`函数（第241-245行）中还有一个补充检查，确保exposure字段存在时以它为准：

```javascript
// workspace-WZoAbmov.js, 第241-245行
function isSkillVisibleInAvailableSkillsPrompt(entry) {
    if (entry.exposure) return entry.exposure.includeInAvailableSkillsPrompt !== false;
    if (entry.invocation) return entry.invocation.disableModelInvocation !== true;
    return entry.skill.disableModelInvocation !== true;
}
```

这个三层回退设计保证了向后兼容性：优先使用`exposure`（最新的入口），其次使用`invocation`（中间层），最后回退到`skill.disableModelInvocation`（原始的字段）。

### 4.1.4 SkillInvocationPolicy -- 调用策略

`SkillInvocationPolicy`从SKILL.md的frontmatter中直接读取，由`resolveSkillInvocationPolicy`函数（`config-DzI9AwO1.js`第110-115行）解析：

```javascript
// config-DzI9AwO1.js, 第110-115行
function resolveSkillInvocationPolicy(frontmatter) {
    return {
        userInvocable: parseFrontmatterBool(
            getFrontmatterString(frontmatter, "user-invocable"), true),
        disableModelInvocation: parseFrontmatterBool(
            getFrontmatterString(frontmatter, "disable-model-invocation"), false)
    };
}
```

两个字段的语义和默认值：

| 字段 | frontmatter键 | 默认值 | 语义 |
|------|--------------|--------|------|
| `userInvocable` | `user-invocable` | `true` | 当设为`false`时，用户无法通过斜杠命令调用此技能。前台仍然显示但调用会失败 |
| `disableModelInvocation` | `disable-model-invocation` | `false` | 当设为`true`时，模型不能自主调用此技能（但仍显示在系统提示的可用技能列表中供read工具查阅） |

注意这两个字段的前端映射：`userInvocable`默认为`true`（除非显式设为`false`），而`disableModelInvocation`默认为`false`（除非显式设为`true`）。这种设计体现了"默认开放"的原则：技能默认既可以被用户调用也可以被模型调用，只有显式声明才会限制。

在SKILL.md的frontmatter中，可以这样使用这些字段：

```yaml
---
name: my-custom-skill
description: A custom skill for specialized tasks
disable-model-invocation: true   # 模型不能自动调用
user-invocable: false            # 用户不能手动调用
---
```

---

## 4.2 六层来源架构

OpenClaw的Skill来源被组织为一个六层优先级体系，这是整个Skill系统的核心设计。每一层对应一个不同的来源标识符（source ID）和一个物理目录。当同名技能出现在多个层级时，优先级更高的层会覆盖优先级较低的层。

### 4.2.1 六层架构总览

| 优先级 | 来源标识符 | 物理目录 | 描述 |
|--------|-----------|---------|------|
| 1 (最低) | `openclaw-extra` | Extra dirs + Plugin skills | 用户通过`skills.load.extraDirs`配置的额外目录，以及插件自动生成的技能目录（通过symlink发布到`~/.openclaw/plugin-skills/`） |
| 2 | `openclaw-bundled` | Bundled skills dir | OpenClaw内置的捆绑技能（随发行版一起提供） |
| 3 | `openclaw-managed` | `~/.openclaw/skills` | 通过ClawHub包注册表安装的受管技能 |
| 4 | `agents-skills-personal` | `~/.agents/skills` | 个人全局Agent Skills（来自上游pi-coding-agent生态） |
| 5 | `agents-skills-project` | `<workspace>/.agents/skills` | 项目级Agent Skills（来自上游pi-coding-agent生态） |
| 6 (最高) | `openclaw-workspace` | `<workspace>/skills` | 工作区本地技能（项目根目录下的`skills/`目录） |

这个优先级体系的设计哲学是：**越接近用户的覆盖越优先**。工作区本地技能具有最高优先级，因为它最能代表当前项目的具体需求；捆绑技能虽然经过精心设计但优先级较低，方便用户和项目级别进行覆盖。

### 4.2.2 各层实现细节

让我们逐层分析每个来源在`loadSkillEntries`函数（第522-762行）中的加载方式。

#### 第1层：openclaw-extra（最低优先级）

Extra层结合了两种技能贡献者：

**A. 用户额外目录**（第695-701行）：

```javascript
// workspace-WZoAbmov.js, 第695-701行
const extraDirs = (opts?.config?.skills?.load?.extraDirs ?? [])
    .map((d) => normalizeOptionalString(d) ?? "")
    .filter(Boolean);
const pluginSkillDirs = resolvePluginSkillDirs({
    workspaceDir,
    config: opts?.config,
    pluginSkillsDir
});
const mergedExtraDirs = [...extraDirs, ...pluginSkillDirs];
```

用户可以通过配置`skills.load.extraDirs`指定额外的技能搜索路径。这些路径与插件技能目录合并后，统一以`"openclaw-extra"`来源加载。

**B. 插件生成的技能**（第711-716行）：

```javascript
// workspace-WZoAbmov.js, 第711-716行
...loadGeneratedPluginSkillRecords({
    pluginSkillsDir,
    pluginSkillDirs,
    source: "openclaw-extra",
    limits
})
```

`loadGeneratedPluginSkillRecords`函数（第468-521行）处理插件发布到`~/.openclaw/plugin-skills/`的symlink。这个目录的每个条目都是插件系统自动生成的软链接。函数的关键逻辑包括：

1. 列出`pluginSkillsDir`下的所有子目录
2. 只处理**符号链接**类型的条目（非symlink的条目被跳过）
3. 验证symlink目标是否在允许的插件技能根目录内
4. 检查`SKILL.md`是否为**常规文件**（排除symlink以避免路径逃逸）
5. 验证`SKILL.md`的大小不超过`maxSkillFileBytes`限制
6. 加载符合条件的技能条目

这里值得注意的是第500行的检查：`if (!skillMdStat.isFile() || skillMdStat.isSymbolicLink()) continue;` -- 插件生成的技能中的`SKILL.md`本身不允许是symlink，这防止了通过symlink逃逸插件技能根目录的可能性。

#### 第2层：openclaw-bundled

```javascript
// workspace-WZoAbmov.js, 第693行, 第702-705行
const bundledSkillsDir = opts?.bundledSkillsDir ?? resolveBundledSkillsDir();
const bundledSkills = bundledSkillsDir
    ? loadSkills({ dir: bundledSkillsDir, source: "openclaw-bundled" })
    : [];
```

捆绑技能目录由`resolveBundledSkillsDir`函数解析，指向随OpenClaw安装的捆绑技能所在位置。所有捆绑技能共享`"openclaw-bundled"`来源标识。如果bundledSkillsDir不存在（例如在开发环境中），则返回空数组。

捆绑技能在资格过滤中有特殊处理：通过`isBundledSkill`函数和`skills.allowBundled`配置，用户可以选择性地允许或禁止特定的捆绑技能。`BUNDLED_SOURCES`集合只包含`"openclaw-bundled"`这一个值：

```javascript
// config-DzI9AwO1.js, 第141-145行
const BUNDLED_SOURCES = new Set(["openclaw-bundled"]);
function isBundledSkill(entry) {
    return BUNDLED_SOURCES.has(resolveSkillSource(entry.skill));
}
```

#### 第3层：openclaw-managed

```javascript
// workspace-WZoAbmov.js, 第691行, 第717-720行
const managedSkillsDir = opts?.managedSkillsDir ?? path.join(CONFIG_DIR, "skills");
const managedSkills = loadSkills({
    dir: managedSkillsDir,
    source: "openclaw-managed"
});
```

受管技能存储在`~/.openclaw/skills`目录下，这是ClawHub包注册表安装技能的位置。受管技能的一个重要特征是它们不受根目录容器约束（见`shouldEnforceConfiguredSkillRootContainment`，第435-437行）：

```javascript
// workspace-WZoAbmov.js, 第435-437行
function shouldEnforceConfiguredSkillRootContainment(source) {
    return source !== "openclaw-managed" && source !== "agents-skills-personal";
}
```

这意味着对于受管技能，路径逃逸检查被放宽，允许技能实际存放在容器目录之外的某个位置。同时，受管技能会被`canonicalizeLoadedSkillRecord`处理，将路径规范化到`canonicalSkillDir`，并在entry上设置`syncSourceDir`和`syncDirName`字段。

#### 第4层：agents-skills-personal

```javascript
// workspace-WZoAbmov.js, 第721-725行
const osHomeDir = resolveUserHomeDir();
const personalAgentsSkills = loadSkills({
    dir: osHomeDir
        ? path.resolve(osHomeDir, ".agents", "skills")
        : path.resolve(".agents", "skills"),
    source: "agents-skills-personal"
});
```

个人全局Agent Skills来自上游pi-coding-agent生态。目录位于用户主目录下的`.agents/skills`。这一层同样不受根目录容器约束（第437行），允许Agent Skills自由存放在用户主目录下。

与agent技能目录路径相比，`osHomeDir`的获取经过了`resolveOsHomeDir`处理，实际上是通过检查`process.env.OPENCLAW_HOME`、`process.env.HOME`等多个候选变量来解析的。

#### 第5层：agents-skills-project

```javascript
// workspace-WZoAbmov.js, 第726-729行
const projectAgentsSkills = loadSkills({
    dir: path.resolve(workspaceDir, ".agents", "skills"),
    source: "agents-skills-project"
});
```

项目级Agent Skills存放在工作区根目录下的`.agents/skills`中。这一层与项目紧密绑定，优先级高于个人全局技能。项目级skills受根目录容器约束（需要通过`resolveContainedSkillPath`检查）。

#### 第6层：openclaw-workspace（最高优先级）

```javascript
// workspace-WZoAbmov.js, 第692行, 第730-733行
const workspaceSkillsDir = path.resolve(workspaceDir, "skills");
const workspaceSkills = loadSkills({
    dir: workspaceSkillsDir,
    source: "openclaw-workspace"
});
```

工作区技能目录是项目根目录下的`skills/`子目录。这是优先级最高的来源，允许项目作者为当前项目精确定制技能。工作区技能受根目录容器约束，并且可以使用配置的`allowSymlinkTargets`：

```javascript
// workspace-WZoAbmov.js, 第438-440行
function shouldUseConfiguredSymlinkTargets(source) {
    return source === "openclaw-workspace"
        || source === "openclaw-extra"
        || source === "agents-skills-project";
}
```

### 4.2.3 merge策略：Map按名去重，后加载覆盖先加载

所有六层技能加载完毕后，通过一个Map进行合并，使用技能名称作为键确保唯一性。这是整个系统最简洁也最关键的设计之一（第734-741行）：

```javascript
// workspace-WZoAbmov.js, 第734-741行
const merged = new Map();
for (const record of extraSkills) merged.set(record.skill.name, record);
for (const record of bundledSkills) merged.set(record.skill.name, record);
for (const record of managedSkills) merged.set(record.skill.name, record);
for (const record of personalAgentsSkills) merged.set(record.skill.name, record);
for (const record of projectAgentsSkills) merged.set(record.skill.name, record);
for (const record of workspaceSkills) merged.set(record.skill.name, record);
```

这个合并策略的核心规则：

1. **键为`skill.name`**：同名技能在Map中只有一个条目。
2. **后加载的覆盖先加载的**：由于`Map.set`会覆盖已有键，for-of循环的顺序决定了覆盖方向。extraSkills最先被设置，workspaceSkills最后被设置。因此工作区技能具有最高优先级（最后写入），extra技能具有最低优先级（最先写入）。
3. **覆盖粒度为整个record**：不仅是技能内容，连frontmatter、metadata、invocation、exposure等全部被覆盖。这意味着工作区中的同名技能可以完全替代extra或bundled中的对应技能。

这个设计的精妙之处在于它的简洁性和可预测性：

- 无需复杂的版本比较或语义合并
- 开发者可以直观地理解：只需在工作区`skills/`目录下创建一个同名的`SKILL.md`，就能完全覆盖内置技能的所有行为
- 合并逻辑只有简单的6行for-of循环，易于测试和维护

### 4.2.4 loadSkills闭合函数

所有六层来源共享同一个`loadSkills`闭合函数（第525-690行），它在`loadSkillEntries`函数内部定义，捕获了外部的`limits`和`allowedSymlinkTargetRealPaths`变量。这个闭合函数封装了完整的目录扫描和技能加载逻辑，我们将在下一节详细分析其内部实现。

---

## 4.3 目录扫描算法

`loadSkills`闭合函数实现了递归的目录扫描算法，这是整个Skill加载流程中最复杂的部分。算法的核心逻辑是：先检查根目录本身是否是技能，如果不是，则递归探查子目录。

### 4.3.1 入口：两层检查

```javascript
// workspace-WZoAbmov.js, 第525-567行
const loadSkills = (params) => {
    const rootDir = path.resolve(params.dir);
    if (!fs.existsSync(rootDir)) return [];
    const rootRealPath = tryRealpath(rootDir) ?? rootDir;
    const baseDir = resolveNestedSkillsRoot(params.dir, {
        maxEntriesToScan: limits.maxCandidatesPerRoot
    }).baseDir;
    // ... 后续扫描逻辑
```

扫描开始前有三个预处理步骤：

1. **解析根目录**：`path.resolve(params.dir)`确保路径是绝对路径。
2. **快速返回**：如果目录不存在，直接返回空数组。
3. **Nested skills检测**：调用`resolveNestedSkillsRoot`检查是否在子目录中存在`skills/`目录。

### 4.3.2 Nested Skills根目录自动检测

`resolveNestedSkillsRoot`（第369-385行）实现了一项重要功能：自动检测`skills/`子目录：

```javascript
// workspace-WZoAbmov.js, 第369-385行
function resolveNestedSkillsRoot(dir, opts) {
    const nested = path.join(dir, "skills");
    try {
        if (!fs.existsSync(nested) || !fs.statSync(nested).isDirectory())
            return { baseDir: dir };
    } catch {
        return { baseDir: dir };
    }
    const nestedDirs = listChildDirectories(nested, {
        maxCandidateDirs: Math.max(0, opts?.maxEntriesToScan ?? 100)
    }).dirs;
    for (const name of nestedDirs) {
        const skillMd = path.join(nested, name, "SKILL.md");
        if (fs.existsSync(skillMd)) return {
            baseDir: nested,
            note: `Detected nested skills root at ${nested}`
        };
    }
    return { baseDir: dir };
}
```

这个函数的行为分为两种情况：

- 如果目录下的`skills/`子目录存在，并且其中至少有一个子目录包含`SKILL.md`，则将扫描基础目录切换到`<dir>/skills/`。
- 否则，保持原目录不变。

这个设计兼容了两种常见的项目布局：

1. **直接布局**：`<workspace>/skills/my-skill/SKILL.md`
2. **嵌套布局**：`<workspace>/skills/my-skill/SKILL.md`（如果`<workspace>/skills/`下已有一个包含SKILL.md的目录，则使用该目录作为baseDir）

### 4.3.3 第一遍扫描：根目录即是技能

在确定了`baseDir`之后，算法首先检查根目录自己是否就是一个技能：

```javascript
// workspace-WZoAbmov.js, 第538-567行
const rootSkillMd = path.join(baseDir, "SKILL.md");
if (fs.existsSync(rootSkillMd)) {
    const rootSkillRealPath = resolveSkillFilePath({
        source: params.source,
        skillDir: baseDir,
        skillDirRealPath: baseDirRealPath,
        candidatePath: rootSkillMd
    });
    if (!rootSkillRealPath) return [];
    try {
        const size = fs.statSync(rootSkillRealPath).size;
        if (size > limits.maxSkillFileBytes) {
            skillsLogger.warn(
                "Skipping skills root due to oversized SKILL.md.", {
                    dir: baseDir,
                    filePath: rootSkillMd,
                    size,
                    maxSkillFileBytes: limits.maxSkillFileBytes
                });
            return [];
        }
    } catch {
        return [];
    }
    return loadContainedSkillRecords({
        skillDir: baseDir,
        source: params.source,
        maxSkillFileBytes: limits.maxSkillFileBytes,
        canonicalSkillDir: canonicalSkillDirForSource(params.source, baseDirRealPath)
    });
}
```

如果`baseDir/SKILL.md`存在，这表示整个目录本身就是一个技能（而不是包含多个技能的目录）。这种情况下：

1. 验证SKILL.md路径没有逃逸容器边界
2. 检查文件大小不超过`maxSkillFileBytes`（默认256KB）
3. 加载并返回这个单技能目录中的技能

这是"直根模式"（root-is-skill mode），跳过所有子目录遍历。

### 4.3.4 第二遍扫描：遍历子目录

如果根目录没有`SKILL.md`，算法进入子目录遍历模式。这一过程包含两个子遍历：

**Pass 1: 第一层子目录的直接搜索**（第568-637行核心逻辑）：

```javascript
// workspace-WZoAbmov.js, 第568-637行（简化提取）
const maxCandidatesPerRoot = Math.max(0, limits.maxCandidatesPerRoot);
const maxSkillsLoadedPerSource = Math.max(0, limits.maxSkillsLoadedPerSource);
const childDirScan = listChildDirectories(baseDir, {
    maxCandidateDirs: maxCandidatesPerRoot
});
const limitedChildren = maxSkillsLoadedPerSource === 0
    ? []
    : childDirs.toSorted().slice(0, maxCandidatesPerRoot);
// 截断警告: 如果目录条目过多或扫描被截断，记录warn日志

for (const name of limitedChildren) {
    const skillDir = path.join(baseDir, name);
    // 验证skillDir不逃逸容器
    const skillDirRealPath = resolveSkillRootCandidatePath({
        source: params.source,
        rootDir, rootRealPath: baseDirRealPath,
        candidatePath: skillDir,
        allowedSymlinkTargetRealPaths
    });
    if (!skillDirRealPath) continue;

    const skillMd = path.join(skillDir, "SKILL.md");
    if (fs.existsSync(skillMd)) {
        // 子目录有SKILL.md → 加载该技能
        // 验证SKILL.md文件的路径安全性
        const skillMdRealPath = resolveSkillFilePath({...});
        if (skillMdRealPath) loadCandidateSkill({...});
    } else {
        // 子目录没有SKILL.md → 进入Pass 2
        // ...
    }
    if (loadedSkills.length >= maxSkillsLoadedPerSource) break;
}
```

第一遍扫描遍历`baseDir`下的所有直接子目录，排除点文件（`.`前缀）和`node_modules`。对于每个子目录，检查其是否直接包含`SKILL.md`。如果包含，则加载该技能。

**Pass 2: 嵌套子目录的深度搜索**（第638-684行）：

```javascript
// workspace-WZoAbmov.js, 第638-684行（简化提取）
else {
    const nestedChildScan = listChildDirectories(skillDir, {
        maxCandidateDirs: maxCandidatesPerRoot
    });
    const nestedChildren = nestedChildScan.dirs;
    // 截断警告: 如果嵌套目录条目过多...

    const limitedNested = nestedChildren.toSorted()
        .slice(0, maxCandidatesPerRoot);
    for (const nestedName of limitedNested) {
        const nestedDir = path.join(skillDir, nestedName);
        const nestedSkillMd = path.join(nestedDir, "SKILL.md");
        if (fs.existsSync(nestedSkillMd)) {
            // 验证嵌套目录不逃逸容器
            const nestedDirRealPath = resolveSkillRootCandidatePath({...});
            const nestedSkillMdRealPath = nestedDirRealPath
                ? resolveSkillFilePath({...})
                : null;
            if (nestedDirRealPath && nestedSkillMdRealPath)
                loadCandidateSkill({
                    skillDir: nestedDir,
                    skillDirRealPath: nestedDirRealPath,
                    name: `${name}/${nestedName}`,
                    skillMdRealPath: nestedSkillMdRealPath
                });
        }
        if (loadedSkills.length >= maxSkillsLoadedPerSource) break;
    }
}
```

如果第一层子目录没有`SKILL.md`，算法会进入其子目录（第二层）查找。这一层的技能名称格式为`"父目录名/子目录名"`，例如`claude-code/github`。这个两层扫描算法是OpenClaw Skill系统的一个独特设计，支持组织化的技能布局。

最终的`loadCandidateSkill`闭合函数（第591-611行）处理实际操作：

```javascript
// workspace-WZoAbmov.js, 第591-611行
const loadCandidateSkill = ({ skillDir, skillDirRealPath, name, skillMdRealPath }) => {
    try {
        const size = fs.statSync(skillMdRealPath).size;
        if (size > limits.maxSkillFileBytes) {
            skillsLogger.warn("Skipping skill due to oversized SKILL.md.", {
                skill: name, filePath: path.join(skillDir, "SKILL.md"),
                size, maxSkillFileBytes: limits.maxSkillFileBytes
            });
            return;
        }
    } catch { return; }
    loadedSkills.push(...loadContainedSkillRecords({
        skillDir, source: params.source,
        maxSkillFileBytes: limits.maxSkillFileBytes,
        canonicalSkillDir: canonicalSkillDirForSource(params.source, skillDirRealPath)
    }));
};
```

### 4.3.5 目录列表与数量限制

`listChildDirectories`函数（第279-296行）基于`walkDirectorySync`实现安全的目录遍历：

```javascript
// workspace-WZoAbmov.js, 第279-296行
function listChildDirectories(dir, opts) {
    const scan = walkDirectorySync(dir, {
        maxDepth: 1,
        maxEntries: opts?.maxRawEntriesToScan === void 0
            ? resolveRawEntryScanLimit(opts?.maxCandidateDirs)
            : Math.max(0, opts.maxRawEntriesToScan),
        symlinks: "follow",
        include: (entry) =>
            entry.kind === "directory"
            && !entry.name.startsWith(".")
            && entry.name !== "node_modules"
    });
    if (scan.scannedEntryCount === 0 && scan.entries.length === 0)
        return { dirs: [], scannedEntryCount: 0, truncated: false };
    return {
        dirs: scan.entries.map((entry) => entry.name),
        scannedEntryCount: scan.scannedEntryCount,
        truncated: scan.truncated
    };
}
```

这个函数有几个关键的安全措施：

1. **深度限制**：`maxDepth: 1`确保只扫描当前目录的直接子目录。
2. **数量限制**：通过`maxEntries`参数限制扫描的总条目数。原始条目限制通过`resolveRawEntryScanLimit`计算，它是`maxCandidateDirs * 10`，但限制在1000到10000之间。
3. **过滤规则**：排除以`.`开头的隐藏目录（如`.git`、`.DS_Store`）和`node_modules`。
4. **Symlink处理**：`symlinks: "follow"`表示跟随symlink，但在后续的路径逃逸检查中会被验证。
5. **截断标记**：如果目录中的条目超过限制，`truncated`标记被设为`true`，触发警告日志。

### 4.3.6 数量限制配置

所有的数量限制都通过`resolveSkillsLimits`函数（第268-278行）解析，支持配置覆盖：

```javascript
// workspace-WZoAbmov.js, 第261-278行
const DEFAULT_MAX_CANDIDATES_PER_ROOT = 300;
const DEFAULT_MAX_SKILLS_LOADED_PER_SOURCE = 200;
const DEFAULT_MAX_SKILLS_IN_PROMPT = 150;
const DEFAULT_MAX_SKILLS_PROMPT_CHARS = 18e3;      // 18000字符
const DEFAULT_MAX_SKILL_FILE_BYTES = 256e3;         // 256KB
const DEFAULT_MIN_RAW_ENTRIES_PER_DIRECTORY_SCAN = 1e3;    // 1000
const DEFAULT_MAX_RAW_ENTRIES_PER_DIRECTORY_SCAN = 1e4;    // 10000
```

| 限制项 | 默认值 | 配置路径 | 说明 |
|--------|--------|---------|------|
| `maxCandidatesPerRoot` | 300 | `skills.limits.maxCandidatesPerRoot` | 每个技能根目录最多扫描的候选子目录数量 |
| `maxSkillsLoadedPerSource` | 200 | `skills.limits.maxSkillsLoadedPerSource` | 每个来源最多加载的技能数量，设为0可禁用某来源 |
| `maxSkillsInPrompt` | 150 | `skills.limits.maxSkillsInPrompt` | 系统提示中最多包含的技能数量 |
| `maxSkillsPromptChars` | 18000 | `skills.limits.maxSkillsPromptChars` | 系统提示中技能部分的最大字符数 |
| `maxSkillFileBytes` | 256KB | `skills.limits.maxSkillFileBytes` | 单个SKILL.md文件的最大字节数，超大文件被跳过 |
| 原始条目扫描下限 | 1000 | 内部计算 | 扫描目录时至少扫描1000个条目 |
| 原始条目扫描上限 | 10000 | 内部计算 | 扫描目录时最多扫描10000个条目 |

### 4.3.7 Symlink处理与路径安全

Symlink处理是扫描算法中安全设计的核心。OpenClaw实现了多层路径安全验证，防止技能通过symlink逃逸其声明的根目录。

#### resolveContainedSkillPath -- 路径容器化验证

```javascript
// workspace-WZoAbmov.js, 第356-368行
function resolveContainedSkillPath(params) {
    const candidateRealPath = tryRealpath(params.candidatePath);
    if (!candidateRealPath) return null;
    if (isPathInside(params.rootRealPath, candidateRealPath)
        || isPathInsideAnyRoot(
            params.allowedSymlinkTargetRealPaths ?? [],
            candidateRealPath))
        return candidateRealPath;
    warnEscapedSkillPath({
        source: params.source,
        rootDir: params.rootDir,
        rootRealPath: params.rootRealPath,
        candidatePath: path.resolve(params.candidatePath),
        candidateRealPath
    });
    return null;
}
```

这个函数执行两步检查：

1. **主容器检查**：通过`isPathInside`验证技能候选路径的真实路径（resolved through symlinks）是否在根目录的真实路径内。
2. **允许列表检查**：如果主容器检查失败，再检查路径是否在`allowedSymlinkTargetRealPaths`（用户通过`skills.load.allowSymlinkTargets`配置的允许列表）中的某个路径内。

如果两步检查都失败，则调用`warnEscapedSkillPath`记录警告日志，并返回`null`（该候选路径被丢弃）。

#### isPathInsideAnyRoot -- 多根目录检查

```javascript
// workspace-WZoAbmov.js, 第432-434行
function isPathInsideAnyRoot(rootRealPaths, candidateRealPath) {
    return rootRealPaths.some((rootRealPath) =>
        isPathInside(rootRealPath, candidateRealPath));
}
```

#### buildEscapedSkillPathReason -- 逃逸原因分类

```javascript
// workspace-WZoAbmov.js, 第317-335行
function buildEscapedSkillPathReason(params) {
    const candidateIsSymlink = isSymlinkPath(params.candidatePath);
    if (params.source === "openclaw-bundled" && candidateIsSymlink)
        return {
            reason: "bundled-symlink-escape",
            consoleHint: "reason=bundled-symlink-escape "
                + "hint=likely-stray-local-symlink-or-checkout-mutation"
        };
    if (candidateIsSymlink)
        return { reason: "symlink-escape", consoleHint: "reason=symlink-escape" };
    if (params.source === "openclaw-bundled")
        return {
            reason: "bundled-root-escape",
            consoleHint: "reason=bundled-root-escape "
                + "hint=likely-stray-local-symlink-or-checkout-mutation"
        };
    return { reason: "path-escape", consoleHint: "reason=path-escape" };
}
```

逃逸原因分为四种类型，每种都有不同的诊断提示：

- `bundled-symlink-escape`：捆绑技能目录中存在symlink指向外部。提示可能是"杂散的本地symlink或checkout突变"。
- `symlink-escape`：非捆绑来源中的symlink逃逸。
- `bundled-root-escape`：捆绑技能中的路径直接逃逸（非symlink）。同样提示可能是checkout突变。
- `path-escape`：通用的路径逃逸。

#### Plugin Skills的Symlink安全

插件生成的技能symlink由`loadGeneratedPluginSkillRecords`函数专门处理（第468-521行），它有自己的安全检查链：

1. **仅symlink条目**：函数遍历`pluginSkillsDir`下的所有条目，但使用`isSymlinkPath(skillDir)`检查，只处理symlink类型（第481行）。
2. **允许根验证**：symlink的目标必须位于允许的插件技能根目录（`allowedRootRealPaths`）内（第483行）。
3. **SKILL.md非symlink**：即使技能目录本身是symlink，其内的`SKILL.md`必须是一个常规文件而非symlink（第500行）：`if (!skillMdStat.isFile() || skillMdStat.isSymbolicLink()) continue;`。
4. **SKILL.md容器化**：`SKILL.md`的真实路径必须在技能目录的真实路径内（第502行）。

这个四层安全保障确保即使插件系统生成的symlink也不会引入路径逃逸漏洞。

### 4.3.8 路径紧凑化

加载完成后，技能路径通过`compactSkillPaths`函数（第218-225行）进行紧凑化处理。这一步将用户主目录路径替换为`~`前缀，旨在减少系统提示中的token消耗：

```javascript
// workspace-WZoAbmov.js, 第218-237行
function compactSkillPaths(skills) {
    const homes = resolveCompactHomePrefixes();
    if (homes.length === 0) return skills;
    return skills.map((s) => ({
        ...s,
        filePath: compactHomePath(s.filePath, homes)
    }));
}
function compactHomePath(filePath, homes) {
    for (const home of homes)
        for (const prefix of compactHomePrefixesForHome(home))
            if (filePath.startsWith(prefix))
                return "~/" + normalizeCompactedSkillPath(
                    filePath.slice(prefix.length), prefix);
    return filePath;
}
```

这个紧凑化过程在构建系统提示时被调用，与模型token预算直接相关。根据注释（第189-198行），每个技能路径约节省5-6个token，乘以N个技能总共约节省400-600个token。这对于严格遵守token预算的应用场景非常重要。

---

## 4.4 五种安装类型与安全正则验证

OpenClaw的Skill系统支持五种安装类型，每种类型都有特定的安全验证正则表达式，防止命令注入和参数扩展攻击。所有安装规范和验证逻辑集中在`skills-install-Byp8_mSa.js`文件中。

### 4.4.1 安装类型总览

| Kind | 命令模板 | 安全正则 | 说明 |
|------|---------|---------|------|
| `brew` | `brew install <formula>` | `SAFE_BREW_FORMULA` | macOS Homebrew包管理器 |
| `node` | `npm/pnpm/yarn/bun install -g <package>` | `SAFE_NODE_PACKAGE` | Node.js包管理器（支持四种包管理器） |
| `go` | `go install <module>` | `SAFE_GO_MODULE` | Go模块安装 |
| `uv` | `uv tool install <package>` | `SAFE_UV_PACKAGE` | Python uv包管理器 |
| `download` | HTTP/HTTPS下载 + 可选解压 | URL协议验证 | 文件下载安装，支持压缩包自动解压 |

### 4.4.2 buildInstallCommand -- 命令构建与安全验证

`buildInstallCommand`函数（第328-400行）将安装规范转换为可执行的命令行参数数组。每个case都先进行安全验证，如果验证失败则返回error而不是argv：

```javascript
// skills-install-Byp8_mSa.js, 第328-400行
function buildInstallCommand(spec, prefs) {
    switch (spec.kind) {
        case "brew": {
            if (!spec.formula)
                return { argv: null, error: "missing brew formula" };
            const err = assertSafeInstallerValue(
                spec.formula, "brew formula", SAFE_BREW_FORMULA);
            if (err) return { argv: null, error: err };
            return { argv: ["brew", "install", spec.formula.trim()] };
        }
        case "node": {
            if (!spec.package)
                return { argv: null, error: "missing node package" };
            const err = assertSafeInstallerValue(
                spec.package, "node package", SAFE_NODE_PACKAGE);
            if (err) return { argv: null, error: err };
            return { argv: buildNodeInstallCommand(spec.package.trim(), prefs) };
        }
        case "go": {
            if (!spec.module)
                return { argv: null, error: "missing go module" };
            const err = assertSafeInstallerValue(
                spec.module, "go module", SAFE_GO_MODULE);
            if (err) return { argv: null, error: err };
            return { argv: ["go", "install", spec.module.trim()] };
        }
        case "uv": {
            if (!spec.package)
                return { argv: null, error: "missing uv package" };
            const err = assertSafeInstallerValue(
                spec.package, "uv package", SAFE_UV_PACKAGE);
            if (err) return { argv: null, error: err };
            return { argv: ["uv", "tool", "install", spec.package.trim()] };
        }
        case "download":
            return { argv: null, error: "download install handled separately" };
        default:
            return { argv: null, error: "unsupported installer" };
    }
}
```

在进入switch之前，`installSkill`函数（第539-624行）已经进行了来源安全性扫描。如果技能来源不是`"openclaw-bundled"`、`"openclaw-managed"`或`"openclaw-extra"`这三个可信来源之一，会显示警告（第572-575行）：

```javascript
// skills-install-Byp8_mSa.js, 第572-575行
if (!new Set(["openclaw-bundled", "openclaw-managed", "openclaw-extra"])
    .has(skillSource))
    warnings.push(
        `WARNING: Skill "${params.skillName}" install triggered `
        + `from non-bundled source "${skillSource}". `
        + `Verify the install recipe is trusted.`);
```

### 4.4.3 brew -- Homebrew包管理器

**命令**：`brew install <formula>`

**安全正则**：`SAFE_BREW_FORMULA`（第318行）：

```javascript
// skills-install-Byp8_mSa.js, 第318行
const SAFE_BREW_FORMULA = /^[a-z0-9][a-z0-9+._@-]*(\/[a-z0-9][a-z0-9+._@-]*){0,2}$/;
```

**安全原理**：

- 公式名必须以小写字母或数字开头（防止以`-`开头引入选项标志）
- 中间字符仅限于`[a-z0-9+._@-]`
- 可选的分段部分（最多2个）允许形如`user/repo`或`tap/formula/extra`的格式
- 不允许空格、分号、管道符等shell特殊字符
- 最多支持三级路径（`a/b/c`），覆盖了大多数Homebrew tap和cask公式的命名模式

**特殊处理**：在命令执行前，会检查brew是否实际可用。如果brew不存在，会根据操作系统环境提供针对性的安装建议。在Linux容器环境中，brew缺失的消息特别强调了容器限制：

```javascript
// skills-install-Byp8_mSa.js, 第451-455行
function resolveBrewMissingFailure(spec) {
    const formula = spec.formula ?? "this package";
    if (process.platform === "linux" && getSkillsInstallDeps().isContainerEnvironment())
        return createInstallFailure({
            message: `brew not installed — Homebrew is not installed in this `
                + `Linux container. Build a custom image with Homebrew `
                + `or install "${formula}" manually using a supported system `
                + `package before enabling this skill.`
        });
    return createInstallFailure({
        message: `brew not installed — `
            + `${process.platform === "linux"
                ? `Homebrew is not installed. Install it from https://brew.sh `
                    + `or install "${formula}" manually using your system `
                    + `package manager (e.g. apt, dnf, pacman).`
                : "Homebrew is not installed. Install it from https://brew.sh"}`
    });
}
```

`brewExe`的解析也很有讲究：如果有`brew`在PATH中，则使用`"brew"`；否则通过`resolveBrewExecutable`函数查找。如果最终没有找到brew，则返回适合当前环境的错误消息。

### 4.4.4 node -- Node.js包管理器

**命令**：`npm/pnpm/yarn/bun install -g --ignore-scripts <package>`

**安全正则**：`SAFE_NODE_PACKAGE`（第319行）：

```javascript
// skills-install-Byp8_mSa.js, 第319行
const SAFE_NODE_PACKAGE = /^(@[a-z0-9._-]+\/)?[a-z0-9._-]+(@[a-z0-9^~>=<.*|-]+)?$/;
```

**安全原理**：

- 可选的scope部分：`@scope/`格式（如`@anthropic/sdk`）
- 包名由字母、数字、点、下划线和连字符组成
- 可选的版本约束：`@version`格式，允许npm semver范围字符（`^~>=<.*|-`）
- 不允许空格、分号、反引号等可导致命令注入的字符
- 所有安装命令统一使用`--ignore-scripts`标志，防止npm包的post-install脚本造成安全隐患

**四种包管理器支持**：通过`buildNodeInstallCommand`函数（第269-300行），根据用户配置的`nodeManager`偏好选择具体的包管理器：

```javascript
// skills-install-Byp8_mSa.js, 第269-300行
function buildNodeInstallCommand(packageName, prefs) {
    switch (prefs.nodeManager) {
        case "pnpm": return ["pnpm", "add", "-g", "--ignore-scripts", packageName];
        case "yarn": return ["yarn", "global", "add", "--ignore-scripts", packageName];
        case "bun":  return ["bun", "add", "-g", "--ignore-scripts", packageName];
        default:     return ["npm", "install", "-g", "--ignore-scripts", packageName];
    }
}
```

**npm前缀路径处理**：当使用npm作为包管理器时，`buildNodeInstallEnv`函数设置`NPM_CONFIG_PREFIX`环境变量，将全局安装路径指向OpenClaw管理的工具目录而不是系统全局位置：

```javascript
// skills-install-Byp8_mSa.js, 第301-317行
function resolveDefaultNodeInstallStateDir({ cwd, getuid, homedir, platform } = {}) {
    if (platform !== "win32" && getuid?.() === 0)
        return path.join(path.parse(cwd).root, "var", "lib", "openclaw");
    return path.join(homedir(), ".openclaw");
}
async function buildNodeInstallEnv(prefs) {
    if (prefs.nodeManager !== "npm") return {};
    const stateDir = getSkillsInstallDeps().resolveNodeInstallStateDir();
    const prefix = path.join(stateDir, "tools", "node", "npm");
    await fs.promises.mkdir(prefix, { recursive: true, mode: 0o700 });
    return {
        NPM_CONFIG_PREFIX: prefix,
        npm_config_prefix: prefix
    };
}
```

在root用户下，安装状态目录指向`/var/lib/openclaw`，而非root用户的home目录。这避免了权限问题和路径混淆。

### 4.4.5 go -- Go模块安装

**命令**：`go install <module>`

**安全正则**：`SAFE_GO_MODULE`（第320行）：

```javascript
// skills-install-Byp8_mSa.js, 第320行
const SAFE_GO_MODULE = /^[a-zA-Z0-9][a-zA-Z0-9._\/-]*@[a-z0-9v._-]+$/;
```

**安全原理**：

- 模块路径必须以字母或数字开头
- 模块路径中间可以包含字母、数字、点、下划线、斜杠和连字符
- 必须包含`@version`部分（版本标识符）
- 版本标识符只能包含小写字母、数字、`v`、点、下划线和连字符
- 不允许空格、分号、反引号等其他字符
- `@`符号后只能是版本信息，不能是其他命令

**自动安装Go**：如果系统中没有Go二进制文件，`ensureGoInstalled`函数会尝试自动安装：

```javascript
// skills-install-Byp8_mSa.js, 第510-526行
async function ensureGoInstalled(params) {
    if (params.spec.kind !== "go" || getSkillsInstallDeps().hasBinary("go"))
        return;
    if (params.brewExe) {
        const brewResult = await runCommandSafely(
            [params.brewExe, "install", "go"],
            { timeoutMs: params.timeoutMs });
        if (brewResult.code === 0) return;
        return createInstallFailure({
            message: "Failed to install go (brew)", ...brewResult
        });
    }
    if (getSkillsInstallDeps().hasBinary("apt-get"))
        return installGoViaApt(params.timeoutMs);
    return createInstallFailure({
        message: "go not installed — install manually: https://go.dev/doc/install"
    });
}
```

自动安装的优先级顺序为：Homebrew（如果可用）→ apt-get（仅在Linux上，支持sudo和非sudo两种情况）→ 提示手动安装。

`installGoViaApt`函数（第470-509行）对Linux容器环境特别友好，会检查是否为root用户、sudo是否可用且不需要密码等情况，然后才尝试执行apt安装。

### 4.4.6 uv -- Python uv工具安装

**命令**：`uv tool install <package>`

**安全正则**：`SAFE_UV_PACKAGE`（第321行）：

```javascript
// skills-install-Byp8_mSa.js, 第321行
const SAFE_UV_PACKAGE = /^[a-z0-9][a-z0-9._-]*(\[[a-z0-9,._-]+\])?(([><=!~]=?|===?)[a-z0-9.*_-]+)?$/i;
```

**安全原理**：

- 包名必须以字母或数字开头（case-insensitive匹配）
- 包名主体由字母、数字、点、下划线和连字符组成
- 可选的extras部分：`[extra1,extra2]`格式
- 可选的版本约束：`>=1.0.0`、`==2.1`、`~=3.0`等uv/pep440版本约束格式
- 不允许shell元字符、路径分隔符、URL协议字符串等

**自动安装uv**：如果安装类型是uv且系统中没有uv二进制文件，`ensureUvInstalled`函数会尝试通过brew自动安装uv：

```javascript
// skills-install-Byp8_mSa.js, 第456-469行
async function ensureUvInstalled(params) {
    if (params.spec.kind !== "uv" || getSkillsInstallDeps().hasBinary("uv"))
        return;
    if (!params.brewExe)
        return createInstallFailure({
            message: "uv not installed — install manually: "
                + "https://docs.astral.sh/uv/getting-started/installation/"
        });
    const brewResult = await runCommandSafely(
        [params.brewExe, "install", "uv"],
        { timeoutMs: params.timeoutMs });
    if (brewResult.code === 0) return;
    return createInstallFailure({
        message: "Failed to install uv (brew)", ...brewResult
    });
}
```

### 4.4.7 download -- 文件下载安装

**命令**：HTTP/HTTPS下载 + 可选的压缩包解压

**安全验证**：URL协议必须严格为`http:`或`https:`。这是通过`normalizeSafeDownloadUrl`函数在frontmatter解析阶段强制校验的（`config-DzI9AwO1.js`第42-53行）：

```javascript
// config-DzI9AwO1.js, 第42-53行
function normalizeSafeDownloadUrl(raw) {
    if (typeof raw !== "string") return;
    const value = raw.trim();
    if (!value || /\s/.test(value)) return;
    try {
        const parsed = new URL(value);
        if (parsed.protocol !== "http:" && parsed.protocol !== "https:") return;
        return parsed.toString();
    } catch {
        return;
    }
}
```

download类型的安装不由`buildInstallCommand`处理，而是有专门的处理逻辑（第391-394行case和`installDownloadSpec`函数）：

```javascript
// skills-install-Byp8_mSa.js, 第391-394行
case "download":
    return { argv: null, error: "download install handled separately" };
```

`installDownloadSpec`函数（第104-226行）实现了完整的下载安装流程：

1. **URL验证**：检查URL是否为空或不合法
2. **文件名提取**：从URL路径中提取basename作为下载文件名
3. **目标目录解析**：通过`resolveDownloadTargetDir`解析目标目录，验证其在技能工具目录内
4. **SSRF防护**：使用`fetchWithSsrFGuard`进行HTTP请求，防止服务端请求伪造
5. **管道下载**：使用Node.js的`stream/promises`的`pipeline`函数进行高效的流式下载
6. **安全写入**：先写入临时staging目录，再通过安全的`copyIn`机制移动到最终位置
7. **解压检测**：根据文件名后缀自动检测压缩类型（`.tar.gz`、`.tgz`、`.tar.bz2`、`.tbz2`、`.zip`）
8. **可选解压**：如果`extract`设为`true`或有可检测的压缩类型，执行`extractArchive`解压
9. **stripComponents支持**：解压时可以跳过顶层目录组件

下载安装还有一个重要的安全检查：目标目录必须在技能工具目录内。如果`targetDir`试图指向工具目录之外，抛出错误：

```javascript
// skills-install-Byp8_mSa.js, 第61-68行
function resolveDownloadTargetDir(entry, spec) {
    const root = resolveSkillToolsRootDir(entry);
    const raw = spec.targetDir?.trim();
    if (!raw) return root;
    const resolved = raw.startsWith("~") || path.isAbsolute(raw)
        || isWindowsDrivePath(raw)
        ? resolveUserPath(raw)
        : path.resolve(root, raw);
    if (!isWithinDir(root, resolved))
        throw new Error(
            `Refusing to install outside the skill tools directory. `
            + `targetDir="${raw}" resolves to "${resolved}". `
            + `Allowed root: "${root}".`);
    return resolved;
}
```

### 4.4.8 assertSafeInstallerValue -- 统一的安全验证入口

所有安装类型的安全验证共享同一个辅助函数：

```javascript
// skills-install-Byp8_mSa.js, 第322-327行
function assertSafeInstallerValue(value, kind, pattern) {
    const trimmed = value.trim();
    if (!trimmed || trimmed.startsWith("-"))
        return `${kind} value is empty or starts with a dash`;
    if (!pattern.test(trimmed))
        return `${kind} value contains invalid characters: ${trimmed}`;
    return null;
}
```

这个函数执行两项独立检查：

1. **空值/选项保护**：如果值为空或以`-`开头，拒绝执行（防止参数注入，如`--option`或`-rf /`）
2. **正则匹配**：使用各类型特定的正则表达式验证字符集

只有当两项检查都通过时，安装命令才会被构建并执行。如果任何一项失败，错误消息会被传播到上层，导致安装失败但不执行危险的shell命令。

### 4.4.9 安装流程总览

`installSkill`函数（第539-624行）实现了完整的安装流程：

```javascript
// skills-install-Byp8_mSa.js, 第539-624行（简化版本，展示核心流程）
async function installSkill(params) {
    const timeoutMs = Math.min(Math.max(params.timeoutMs ?? 3e5, 1e3), 9e5);
    const entry = deps.loadWorkspaceSkillEntries(workspaceDir)
        .find((item) => item.skill.name === params.skillName);
    // 1. 查找技能条目
    // 2. 安全扫描（来源验证）
    const scanResult = await scanSkillInstallSource({...});
    if (scanResult?.blocked) return withWarnings({...}, warnings);
    // 3. 来源警告（非可信来源）
    // 4. 查找安装规范spec
    // 5. 如果是download类型，调用installDownloadSpec
    // 6. 构建安装命令（安全验证）
    const command = buildInstallCommand(spec, prefs);
    // 7. 工具链检查（brew/go/uv）
    // 8. 执行安装命令
    return withWarnings(await executeInstallCommand({...}), warnings);
}
```

安装流程的核心安全保障总结如下：

1. **来源验证**：通过`scanSkillInstallSource`扫描技能安装来源，防止恶意来源执行危险的安装命令
2. **可信来源检查**：非捆绑来源的技能安装会触发警告
3. **正则字符集验证**：每种安装类型的包名/公式/模块都通过严格的正则表达式验证
4. **命令行构造**：使用数组形式的argv（而非字符串模板），杜绝shell注入
5. **超时控制**：安装命令的超时限制在1秒到900秒之间（默认300秒）
6. **环境隔离**：npm安装使用独立的prefix目录，避免污染系统环境

---

## 4.5 技能资格过滤

Skill系统加载完所有原始技能条目后，需要通过资格过滤确定哪些技能对当前环境实际可用。过滤逻辑由三个核心函数组成：`shouldIncludeSkill`、`filterSkillEntries`和`evaluateRuntimeEligibility`。

### 4.5.1 shouldIncludeSkill -- 技能资格判断

`shouldIncludeSkill`函数（位于`config-DzI9AwO1.js`第154-171行）负责判断单个技能在给定运行时环境中是否应该被包含：

```javascript
// config-DzI9AwO1.js, 第154-171行
function shouldIncludeSkill(params) {
    const { entry, config, eligibility } = params;
    const skillConfig = resolveSkillConfig(
        config, resolveSkillKey(entry.skill, entry));
    const allowBundled = normalizeAllowlist(config?.skills?.allowBundled);
    if (skillConfig?.enabled === false) return false;
    if (!isBundledSkillAllowed(entry, allowBundled)) return false;
    return evaluateRuntimeEligibility({
        os: entry.metadata?.os,
        remotePlatforms: eligibility?.remote?.platforms,
        always: entry.metadata?.always,
        requires: entry.metadata?.requires,
        hasBin: hasBinary,
        hasRemoteBin: eligibility?.remote?.hasBin,
        hasAnyRemoteBin: eligibility?.remote?.hasAnyBin,
        hasEnv: (envName) => Boolean(
            process.env[envName]
            || skillConfig?.env?.[envName]
            || skillConfig?.apiKey
               && entry.metadata?.primaryEnv === envName),
        isConfigPathTruthy: (configPath) =>
            isConfigPathTruthy(config, configPath)
    });
}
```

过滤逻辑分为四个阶段：

**阶段1：显式禁用检查**

如果该技能在配置`skills.entries[skillKey]`中的`enabled`设为`false`，直接返回false。这是用户对特定技能的最强控制手段。

**阶段2：捆绑技能白名单检查**

如果用户配置了`skills.allowBundled`白名单，只有白名单中列出的技能名称（或skillKey）才会被允许。未列出的捆绑技能被静默排除。

**阶段3：OS匹配检查**

如果技能声明了`os`列表（如`["darwin", "linux"]`），并且当前操作系统不在列表中，同时也未匹配任何远程平台，则返回false。

**阶段4：always标志检查**

如果`always`为`true`，跳过所有运行时依赖检查，直接返回true。这允许某些特定技能无条件显示。

**阶段5：运行时依赖检查**

通过`evaluateRuntimeRequires`检查`requires`数组中声明的所有依赖。

### 4.5.2 evaluateRuntimeEligibility -- 运行时资格评估

`evaluateRuntimeEligibility`函数（位于`config-eval-BzYHHbOk.js`第48-61行）将OS检查、always标志检查和requires依赖检查组合在一起：

```javascript
// config-eval-BzYHHbOk.js, 第48-61行
function evaluateRuntimeEligibility(params) {
    const osList = params.os ?? [];
    const remotePlatforms = params.remotePlatforms ?? [];
    if (osList.length > 0
        && !osList.includes(resolveRuntimePlatform())
        && !remotePlatforms.some((platform) => osList.includes(platform)))
        return false;
    if (params.always === true) return true;
    return evaluateRuntimeRequires({
        requires: params.requires,
        hasBin: params.hasBin,
        hasRemoteBin: params.hasRemoteBin,
        hasAnyRemoteBin: params.hasAnyRemoteBin,
        hasEnv: params.hasEnv,
        isConfigPathTruthy: params.isConfigPathTruthy
    });
}
```

OS检查考虑了两种场景：
1. **本地场景**：`resolveRuntimePlatform()`返回`process.platform`（如`"linux"`、`"darwin"`、`"win32"`）
2. **远程场景**：如果OpenClaw在远程网关上运行，远程平台信息通过`eligibility.remote.platforms`传入，允许远程能力的技能也被包含

### 4.5.3 evaluateRuntimeRequires -- 依赖验证

`evaluateRuntimeRequires`函数（位于`config-eval-BzYHHbOk.js`第25-47行）是最核心的运行时依赖验证逻辑：

```javascript
// config-eval-BzYHHbOk.js, 第25-47行
function evaluateRuntimeRequires(params) {
    const requires = params.requires;
    if (!requires) return true;

    const requiredBins = requires.bins ?? [];
    if (requiredBins.length > 0)
        for (const bin of requiredBins) {
            if (params.hasBin(bin)) continue;
            if (params.hasRemoteBin?.(bin)) continue;
            return false;
        }

    const requiredAnyBins = requires.anyBins ?? [];
    if (requiredAnyBins.length > 0) {
        if (!requiredAnyBins.some((bin) => params.hasBin(bin))
            && !params.hasAnyRemoteBin?.(requiredAnyBins))
            return false;
    }

    const requiredEnv = requires.env ?? [];
    if (requiredEnv.length > 0) {
        for (const envName of requiredEnv)
            if (!params.hasEnv(envName)) return false;
    }

    const requiredConfig = requires.config ?? [];
    if (requiredConfig.length > 0) {
        for (const configPath of requiredConfig)
            if (!params.isConfigPathTruthy(configPath)) return false;
    }

    return true;
}
```

四个验证维度的详细逻辑：

| 验证维度 | 逻辑 | 失败后果 |
|---------|------|---------|
| `bins` | ALL必须存在。遍历每个bin，本地通过`hasBinary`检查PATH，远程通过`hasRemoteBin`回调检查。任何一项缺失都失败 | 技能被排除 |
| `anyBins` | AT LEAST ONE必须存在。本地检查PATH，远程通过`hasAnyRemoteBin`回调检查。全部缺失才失败 | 技能被排除 |
| `env` | ALL必须设置。环境变量来源有三个：`process.env`、`skills.entries[skillKey].env`配置、`skills.entries[skillKey].apiKey`（如果变量名匹配`primaryEnv`） | 技能被排除 |
| `config` | ALL必须为真值。通过`isConfigPathTruthy`检查OpenClaw配置中的路径值 | 技能被排除 |

注意`hasEnv`的特殊实现（在`shouldIncludeSkill`中的第169行）：
```javascript
hasEnv: (envName) => Boolean(
    process.env[envName]
    || skillConfig?.env?.[envName]
    || skillConfig?.apiKey && entry.metadata?.primaryEnv === envName)
```

这提供了三层环境变量来源：
1. **系统环境变量**：`process.env[envName]`
2. **技能配置中的env覆盖**：`skillConfig.env[envName]`（在`skills.entries[skillKey].env`中定义）
3. **API密钥的隐式env**：如果技能有`primaryEnv`（如`GITHUB_TOKEN`），并且配置中设置了对应的`apiKey`，则视为该环境变量存在

### 4.5.4 hasBinary -- PATH二进制查找

`hasBinary`函数（位于`config-eval-BzYHHbOk.js`第77-98行）通过遍历PATH环境变量来检查二进制文件是否存在且可执行：

```javascript
// config-eval-BzYHHbOk.js, 第77-98行
function hasBinary(bin) {
    const pathEnv = process.env.PATH ?? "";
    const pathExt = process.platform === "win32"
        ? process.env.PATHEXT ?? "" : "";
    if (cachedHasBinaryPath !== pathEnv
        || cachedHasBinaryPathExt !== pathExt) {
        cachedHasBinaryPath = pathEnv;
        cachedHasBinaryPathExt = pathExt;
        hasBinaryCache.clear();
    }
    if (hasBinaryCache.has(bin)) return hasBinaryCache.get(bin);
    const parts = pathEnv.split(path.delimiter).filter(Boolean);
    const extensions = process.platform === "win32"
        ? windowsPathExtensions() : [""];
    for (const part of parts)
        for (const ext of extensions) {
            const candidate = path.join(part, bin + ext);
            try {
                fs.accessSync(candidate, fs.constants.X_OK);
                hasBinaryCache.set(bin, true);
                return true;
            } catch {}
        }
    hasBinaryCache.set(bin, false);
    return false;
}
```

这个函数包含一个PATH感知的缓存机制：当`PATH`或`PATHEXT`（Windows）变化时，缓存自动失效。在Windows上，还会尝试`.EXE`、`.CMD`、`.BAT`、`.COM`等可执行扩展名。

### 4.5.5 filterSkillEntries -- 技能过滤入口

`filterSkillEntries`函数（第246-260行）组合了`shouldIncludeSkill`检查和可选的`skillFilter`白名单过滤：

```javascript
// workspace-WZoAbmov.js, 第246-260行
function filterSkillEntries(entries, config, skillFilter, eligibility) {
    let filtered = entries.filter((entry) => shouldIncludeSkill({
        entry,
        config,
        eligibility
    }));
    if (skillFilter !== void 0) {
        const normalized = normalizeSkillFilter(skillFilter) ?? [];
        const label = normalized.length > 0
            ? normalized.join(", ") : "(none)";
        skillsLogger.debug(`Applying skill filter: ${label}`);
        filtered = normalized.length > 0
            ? filtered.filter((entry) =>
                normalized.includes(entry.skill.name))
            : [];
        skillsLogger.debug(
            `After skill filter: `
            + `${filtered.map((entry) => entry.skill.name).join(", ") || "(none)"}`);
    }
    return filtered;
}
```

两层过滤逻辑：

1. **shouldIncludeSkill**：运行时资格检查（OS、二进制文件、环境变量、配置等）
2. **Skill Filter**：如果提供了`skillFilter`数组，只保留名称在数组中的技能。空数组意味着过滤掉所有技能（`(none)`模式）

### 4.5.6 远程资格（Remote Eligibility）

远程资格通过`eligibility.remote`对象传递给过滤函数。这个对象包含：

- **`platforms: string[]`**：远程网关的操作系统平台（如`["linux"]`）
- **`hasBin: (bin: string) => boolean`**：在远程环境中检查二进制文件是否存在的回调
- **`hasAnyBin: (bins: string[]) => boolean`**：在远程环境中检查是否有任何二进制文件存在
- **`note: string`**：远程环境的描述信息（可选），用于在系统提示中添加说明

远程资格使同一套技能定义能够适应本地和远程两种运行模式。例如，一个包含`os: ["darwin"]`的Homebrew技能在macOS本地开发环境中可用，但如果远程网关报告`remotePlatforms`包含`"darwin"`，则该技能在远程也会被包含。

### 4.5.7 过滤后的可见性检查

资格过滤完成后，还有一层"可见性"检查，用于决定技能是否出现在系统提示中。`isSkillVisibleInAvailableSkillsPrompt`函数（第241-245行）通过回退链检查技能是否应该对模型可见：

```javascript
// workspace-WZoAbmov.js, 第241-245行
function isSkillVisibleInAvailableSkillsPrompt(entry) {
    if (entry.exposure)
        return entry.exposure.includeInAvailableSkillsPrompt !== false;
    if (entry.invocation)
        return entry.invocation.disableModelInvocation !== true;
    return entry.skill.disableModelInvocation !== true;
}
```

这确保了即使技能通过了资格过滤（`shouldIncludeSkill`返回true），如果其frontmatter中设置了`disable-model-invocation: true`，它仍然不会出现在模型可用的技能列表中，但可能仍可被用户手动调用。

---

## 4.6 系统提示中的技能格式化

技能加载和过滤完成后，需要将可用技能格式化为XML格式插入到系统提示中。OpenClaw提供两种格式化方式。

### 4.6.1 完整格式

`formatSkillsForPrompt`函数（第38-56行）生成完整的技能目录XML：

```javascript
// workspace-WZoAbmov.js, 第38-56行
function formatSkillsForPrompt(skills) {
    if (skills.length === 0) return "";
    const lines = [
        "\n\nThe following skills provide specialized instructions "
            + "for specific tasks.",
        "Use the read tool to load a skill's file when the task "
            + "matches its description.",
        "When a skill file references a relative path, resolve it "
            + "against the skill directory (parent of SKILL.md / dirname "
            + "of the path) and use that absolute path in tool commands.",
        "",
        "<available_skills>"
    ];
    for (const skill of skills) {
        lines.push("  <skill>");
        lines.push(`    <name>${escapeXml$1(skill.name)}</name>`);
        lines.push(`    <description>${escapeXml$1(skill.description)}</description>`);
        lines.push(`    <location>${escapeXml$1(skill.filePath)}</location>`);
        lines.push("  </skill>");
    }
    lines.push("</available_skills>");
    return lines.join("\n");
}
```

生成的XML结构如下：

```xml
<available_skills>
  <skill>
    <name>github</name>
    <description>Interact with GitHub repositories, issues, and PRs</description>
    <location>~/.openclaw/skills/github/SKILL.md</location>
  </skill>
  <skill>
    <name>docker</name>
    <description>Build, run, and manage Docker containers</description>
    <location>/home/user/project/skills/docker/SKILL.md</location>
  </skill>
</available_skills>
```

### 4.6.2 紧凑格式

当完整格式超出字符预算时，`formatSkillsCompact`函数（第772-789行）提供了一个省略描述的紧凑版本：

```javascript
// workspace-WZoAbmov.js, 第772-789行
function formatSkillsCompact(skills) {
    if (skills.length === 0) return "";
    const lines = [
        "\n\nThe following skills provide specialized instructions "
            + "for specific tasks.",
        "Use the read tool to load a skill's file when the task "
            + "matches its name.",  // 注意这里从"description"变为"name"
        "When a skill file references a relative path, resolve it "
            + "against the skill directory (parent of SKILL.md / dirname "
            + "of the path) and use that absolute path in tool commands.",
        "",
        "<available_skills>"
    ];
    for (const skill of skills) {
        lines.push("  <skill>");
        lines.push(`    <name>${escapeXml(skill.name)}</name>`);
        lines.push(`    <location>${escapeXml(skill.filePath)}</location>`);
        // 注意：这里省略了 <description>
        lines.push("  </skill>");
    }
    lines.push("</available_skills>");
    return lines.join("\n");
}
```

紧凑格式与完整格式的关键区别在于：提示文本从"Use the read tool to load a skill's file when the task matches its description"变为"Use the read tool to load a skill's file when the task matches its name"，并且省略了`<description>`元素。

### 4.6.3 自适应截断：applySkillsPromptLimits

`applySkillsPromptLimits`函数（第791-818行）实现了自适应截断策略，确保技能列表不会超出token预算：

```javascript
// workspace-WZoAbmov.js, 第790-819行（简化）
function applySkillsPromptLimits(params) {
    const limits = resolveSkillsLimits(params.config, params.agentId);
    const total = params.skills.length;
    const byCount = params.skills.slice(0,
        Math.max(0, limits.maxSkillsInPrompt));
    let skillsForPrompt = byCount;
    let truncated = total > byCount.length;
    let compact = false;

    const fitsFull = (skills) =>
        formatSkillsForPrompt(skills).length <= limits.maxSkillsPromptChars;
    const compactBudget = limits.maxSkillsPromptChars - COMPACT_WARNING_OVERHEAD;
    const fitsCompact = (skills) =>
        formatSkillsCompact(skills).length <= compactBudget;

    if (!fitsFull(skillsForPrompt))
        if (fitsCompact(skillsForPrompt)) compact = true;
        else {
            compact = true;
            // 通过二分查找找到紧凑格式下最大的技能子集
            let lo = 0;
            let hi = skillsForPrompt.length;
            while (lo < hi) {
                const mid = Math.ceil((lo + hi) / 2);
                if (fitsCompact(skillsForPrompt.slice(0, mid))) lo = mid;
                else hi = mid - 1;
            }
            skillsForPrompt = skillsForPrompt.slice(0, lo);
            truncated = true;
        }
    return { skillsForPrompt, truncated, compact };
}
```

截断策略的三层回退：

1. **完整格式直接放行**：如果完整格式不超过`maxSkillsPromptChars`（默认18000），直接使用
2. **切换紧凑格式**：如果完整格式超限但紧凑格式合适，切换到紧凑格式（省略描述）
3. **二分查找截断**：如果紧凑格式也超限，使用二分查找找到能放入预算的最大技能子集

`COMPACT_WARNING_OVERHEAD`常量（第790行）预留了150个字符用于截断警告消息。截断时会在系统提示的开头插入警告信息：
```
⚠️ Skills truncated: included 23 of 67 (compact format, descriptions omitted). Run `openclaw skills check` to audit.
```

---

## 4.7 技能同步机制

OpenClaw支持将技能从一个工作区同步到另一个工作区的`skills/`目录。`syncSkillsToWorkspace`函数（第919-971行）实现了这一功能：

```javascript
// workspace-WZoAbmov.js, 第919-971行（简化）
async function syncSkillsToWorkspace(params) {
    const sourceDir = resolveUserPath(params.sourceWorkspaceDir);
    const targetDir = resolveUserPath(params.targetWorkspaceDir);
    if (sourceDir === targetDir) return;
    await serializeByKey(`syncSkills:${targetDir}`, async () => {
        const targetSkillsDir = path.join(targetDir, "skills");
        const entries = loadWorkspaceSkillEntries(sourceDir, {
            config: params.config,
            skillFilter: params.skillFilter,
            agentId: params.agentId,
            eligibility: params.eligibility,
            managedSkillsDir: params.managedSkillsDir,
            bundledSkillsDir: params.bundledSkillsDir
        });
        // 清空目标目录
        await fsp.rm(targetSkillsDir, { recursive: true, force: true });
        await fsp.mkdir(targetSkillsDir, { recursive: true });
        const usedDirNames = new Set();
        for (const entry of entries) {
            // 解析唯一的目标目录名称（防止名称冲突）
            let dest = resolveSyncedSkillDestinationPath({
                targetSkillsDir, entry, usedDirNames
            });
            if (!dest) continue;
            // 拷贝技能目录（排除.git和node_modules）
            await fsp.cp(entry.syncSourceDir ?? entry.skill.baseDir, dest, {
                recursive: true, force: true,
                filter: (src) => {
                    const name = path.basename(src);
                    return !(name === ".git" || name === "node_modules");
                }
            });
        }
    });
}
```

同步的关键特性：

1. **序列化执行**：使用`serializeByKey`确保同一目标目录的同步操作串行化，防止并发冲突
2. **同名自身跳过**：如果源目录和目标目录相同，不执行任何操作
3. **递归清理**：先删除目标`skills/`目录的全部内容，然后重新创建
4. **防止名称冲突**：`resolveSyncedSkillDestinationPath`使用`resolveUniqueSyncedSkillDirName`（第889-909行）确保唯一的目标目录名
5. **排除版本控制和大依赖**：拷贝时过滤掉`.git`和`node_modules`目录
6. **使用syncSourceDir**：优先使用规范化后的源目录路径（如果entry有`syncSourceDir`字段），否则回退到`skill.baseDir`

---

## 4.8 Plugin Skills生成机制

Plugin Skills是由启用的插件生成并自动发布到`~/.openclaw/plugin-skills/`目录的技能。整个生命期由`plugin-skills-CHZ5z3WZ.js`管理。

### 4.8.1 插件的技能发现

`resolvePluginSkillDirs`函数（第15-79行）遍历所有已激活的插件，收集它们的技能目录：

1. **插件元数据快照**：通过`getCurrentPluginMetadataSnapshot`或`loadPluginMetadataSnapshot`获取插件的清单注册表
2. **激活状态过滤**：只考虑通过`resolveEffectivePluginActivationState`验证为激活的插件
3. **ACPruntime可用性**：如果ACP runtime未启用，跳过`acpx`插件
4. **Memory Slot决策**：处理内存插槽竞争，确保只有一个内存插件被选为活跃
5. **路径安全验证**：对每个技能路径进行`isPathInsideWithRealpath`检查，防止逃逸

### 4.8.2 Symlink发布

`publishPluginSkills`函数（第151-193行）将插件技能以symlink的形式发布到`~/.openclaw/plugin-skills/`：

1. **技能目标收集**：通过`collectSkillTargets`收集每个插件技能目录的发布目标
2. **Symlink管理**：创建、更新或删除symlink。如果symlink已存在且目标正确则跳过；如果symlink指向不同的目标则先删除再重建
3. **名称冲突检测**：当两个插件声明同名的技能时，第一个声明的优先，后续的触发警告日志
4. **过期清理**：删除不再需要的旧symlink（没有对应插件管理的symlink条目）

### 4.8.3 技能目标收集

`collectSkillTargets`函数（第91-124行）决定每个插件目录如何映射到发布目标：

**情况A -- 直根模式**：插件目录本身包含`SKILL.md`，则该目录直接作为发布目标：

```javascript
if (hasPublishableSkillFile({ skillDir: dir, rootDir: dir })) {
    const basename = path.basename(dir);
    // 名称冲突检测...
    targets.set(basename, dir);
    return;
}
```

**情况B -- 子目录展开模式**：插件目录没有直接的`SKILL.md`，但子目录中有。遍历所有直接子目录，将包含`SKILL.md`的子目录发布：

```javascript
const entries = walkDirectorySync(dir, {
    maxDepth: 1,
    symlinks: "skip",
    include: (entry) => entry.kind === "directory"
}).entries;
for (const entry of entries) {
    const childPath = entry.path;
    if (!hasPublishableSkillFile({ skillDir: childPath, rootDir: dir }))
        continue;
    // 名称冲突检测...
    targets.set(basename, childPath);
}
```

### 4.8.4 安全性保证

`hasPublishableSkillFile`函数（第125-142行）执行三层安全检查：

1. **SKILL.md存在且是常规文件**：排除目录、symlink和其他文件类型
2. **SKILL.md不是symlink**：`if (!skillMdStat.isFile() || skillMdStat.isSymbolicLink())` -- 防止通过symlink引入外部恶意内容
3. **SKILL.md在声明的根目录内**：使用`isPathInsideWithRealpath`检查，确保文件不会因为symlink绕行而逃逸声明的根目录

---

## 4.9 小结

本章（上篇）涵盖了OpenClaw Skill系统的底层实现细节，从核心数据结构到六层来源架构，从递归目录扫描算法到五种安全安装方式，以及完整的技能资格过滤逻辑。

关键设计要点：

1. **六层优先级合并**：通过`Map.set`的覆盖语义，后加载的层覆盖先加载的同名技能，工作区技能具有最高优先级
2. **两层递归扫描**：先检查根目录是否为技能，若否则递归遍历子目录（最深2层）
3. **多层路径安全**：symlink真实路径解析、容器内验证、允许列表机制和逃逸检测，共同构成纵深防御
4. **正则安全验证**：五种安装类型各有专属的正则表达式和前置验证函数，确保安装命令的安全性
5. **运行时资格过滤**：OS匹配→always标志→依赖检查（bins/anyBins/env/config）的递进式过滤链
6. **自适应截断**：根据token预算从完整格式→紧凑格式→二分截断的三层退化策略

在下一章（下篇）中，我们将深入探讨Skill系统的运行时行为：技能的调用机制、SKILL.md文件的安全读取、frontmatter块的动态解析、以及技能在代理执行流程中的生命周期管理。
# 四、Skill系统实现（下）：XML注入、暴露控制、安全扫描与ClawHub

本章继续深入分析OpenClaw Skill系统的核心实现机制。在上一章（四、上）中，我们探讨了Skill的加载流程、多层目录发现、路径包容性校验以及从SKILL.md到内存数据结构的完整转换链路。本章聚焦于五个关键子系统：**XML提示注入格式**（如何将Skill信息嵌入LLM上下文）、**暴露控制策略**（四种权限组合的精确语义）、**安全扫描引擎**（双层规则引擎的检测机制）、**ClawHub技能市场**（远程技能的发现、安装与更新），以及**远程技能与快照持久化**（macOS远程节点探测和会话状态快照）。

---

## 4.1 XML提示注入格式

OpenClaw采用XML格式将已加载的Skill信息注入到系统提示词中。这种格式由`formatSkillsForPrompt`函数（位于`workspace-WZoAbmov.js`第38行）生成，其设计目标是在不引入额外依赖的前提下，让LLM能够准确理解并自行决定何时加载SKILL.md文件。

### 4.1.1 完整的XML格式

`formatSkillsForPrompt`函数接收一个已排序的Skill数组，生成如下格式的提示文本：

```xml

The following skills provide specialized instructions for specific tasks.
Use the read tool to load a skill's file when the task matches its description.
When a skill file references a relative path, resolve it against the skill directory (parent of SKILL.md / dirname of the path) and use that absolute path in tool commands.

<available_skills>
  <skill>
    <name>skill-name</name>
    <description>What this skill does</description>
    <location>~/path/to/SKILL.md</location>
  </skill>
</available_skills>
```

核心代码（`workspace-WZoAbmov.js` 第38-56行）：

```javascript
function formatSkillsForPrompt(skills) {
    if (skills.length === 0) return "";
    const lines = [
        "\n\nThe following skills provide specialized instructions for specific tasks.",
        "Use the read tool to load a skill's file when the task matches its description.",
        "When a skill file references a relative path, resolve it against the skill directory (parent of SKILL.md / dirname of the path) and use that absolute path in tool commands.",
        "",
        "<available_skills>"
    ];
    for (const skill of skills) {
        lines.push("  <skill>");
        lines.push(`    <name>${escapeXml$1(skill.name)}</name>`);
        lines.push(`    <description>${escapeXml$1(skill.description)}</description>`);
        lines.push(`    <location>${escapeXml$1(skill.filePath)}</location>`);
        lines.push("  </skill>");
    }
    lines.push("</available_skills>");
    return lines.join("\n");
}
```

**设计要点分析：**

1. **前缀引导文本**：在`<available_skills>`标签之前插入三段英文引导说明，告诉LLM如何使用Skills系统。第一句说明Skills提供专项任务指令；第二句提示LLM通过`read`工具加载匹配的SKILL.md；第三句特别强调相对路径的解析规则——必须基于SKILL.md所在目录（或其父目录）来解析相对路径，并使用绝对路径进行工具调用。这是防止路径解析错误的关键提示。

2. **空列表短路**：`if (skills.length === 0) return ""`——当没有任何可用Skill时返回空字符串，避免注入无意义的空白标签块，节省上下文窗口。

3. **XML结构调整**：注意`lines.join("\n")`而非`lines.join("\n\n")`，这是因为前缀文本第2行与第3行之间的"空行"实际上对应到数组末尾的`""`空字符串。实际生成的文本中，`<available_skills>`前面会有两行独立的前缀文本，中间用空行分隔。

### 4.1.2 XML转义函数（escapeXml）

为了防止Skill的名称、描述或文件路径中的特殊字符破坏XML结构，`escapeXml`函数（`workspace-WZoAbmov.js`第764行）对五种XML特殊字符进行标准转义：

```javascript
function escapeXml(str) {
    return str.replace(/&/g, "&amp;")
              .replace(/</g, "&lt;")
              .replace(/>/g, "&gt;")
              .replace(/"/g, "&quot;")
              .replace(/'/g, "&apos;");
}
```

| 原始字符 | 转义结果 | 说明 |
|---------|---------|------|
| `&` | `&amp;` | 必须先转义&，否则会破坏后续转义结果 |
| `<` | `&lt;` | 防止被误解析为XML标签开始 |
| `>` | `&gt;` | 防止被误解析为XML标签结束 |
| `"` | `&quot;` | 防止破坏属性值边界 |
| `'` | `&apos;` | 防止破坏属性值边界 |

**关键细节**：`&`必须最先转义，因为如果先转义`<`为`&lt;`，再转义`&`会将`&lt;`错误地二次转义为`&amp;lt;`。当前代码使用链式调用，由于`String.prototype.replace`每次返回新字符串，第一个`.replace(/&/g, "&amp;")`处理后，后续的`.replace`不会再看到原始输入中的`&`，但也不会错误匹配`&amp;`中的`&`（因为`&amp;`不匹配`/&/g`的正则）。

**注意**：代码中实际存在两个XML转义函数——`escapeXml$1`（第29行，用于`formatSkillsForPrompt`内部）和`escapeXml`（第764行，用于`formatSkillsCompact`内部）。两者实现完全相同，这是代码打包过程中产生的重复定义。

### 4.1.3 用户主目录压缩（compactHomePath）

Skill的文件路径通常位于用户主目录下，例如：

```
/Users/alice/.openclaw/skills/github/SKILL.md
/Users/alice/.claude/skills/my-skill/SKILL.md
```

这些绝对路径长度可达50-80个字符，在提示词中占用大量token。OpenClaw通过`compactHomePath`函数将用户主目录前缀替换为`~`符号，利用LLM对`~`的普遍理解来节省约5-6个token每条路径（按英文tokenizer估算）。考虑到一个典型项目可能有30-100个Skill，总计可节省约400-600个token。

**核心机制**：`resolveCompactHomePrefixes`函数（第209-217行）收集所有可能的用户主目录路径变体：

```javascript
function resolveCompactHomePrefixes() {
    const resolvedHomes = [
        resolveHomeDir(),          // OpenClaw配置的主目录
        resolveUserHomeDir(),      // 系统环境变量中的HOME
        resolveNativeUserHomeDir() // os.homedir()返回值
    ].filter((home) => !!home)
     .map((home) => path.resolve(home));

    const realHomes = resolvedHomes
        .map((home) => tryRealpath(home))   // 解析符号链接后的真实路径
        .filter((home) => !!home);

    return [...resolvedHomes, ...realHomes]
        .filter((home, index, all) => all.indexOf(home) === index) // 去重
        .sort((a, b) => b.length - a.length);  // 按长度降序排列
}
```

**设计理由**：

1. **三种来源**：`resolveHomeDir()`对应OpenClaw自身的配置目录（通常是`~/.openclaw`的父级），`resolveUserHomeDir()`从环境变量`HOME`获取，`resolveNativeUserHomeDir()`调用`os.homedir()`。三者可能不同——例如在macOS上，`os.homedir()`返回`/Users/alice`，但环境变量可能被覆盖。

2. **真实路径解析**：`tryRealpath`调用`fs.realpathSync`解析符号链接。例如，如果`/Users/alice`是指向`/Volumes/Data/alice`的符号链接，则两个路径都需要被识别，因为代码中存储的路径可能是原始路径，而`realpath`返回的是实际路径。

3. **去重与排序**：`filter((home, index, all) => all.indexOf(home) === index)`去除重复路径（例如`resolveHomeDir()`和`os.homedir()`可能返回相同路径）。随后`sort((a, b) => b.length - a.length)`按路径长度降序排列，确保最长匹配优先——如果`/Users/alice`和`/Users/al`同时存在，必须先尝试匹配更长的`/Users/alice`。

**compactHomePath函数**（第226-229行）：

```javascript
function compactHomePath(filePath, homes) {
    for (const home of homes)
        for (const prefix of compactHomePrefixesForHome(home))
            if (filePath.startsWith(prefix))
                return "~/" + normalizeCompactedSkillPath(
                    filePath.slice(prefix.length), prefix);
    return filePath;
}
```

**路径分隔符兼容性**：`compactHomePrefixesForHome`函数（第230-233行）处理Windows路径分隔符：

```javascript
function compactHomePrefixesForHome(home) {
    const prefixes = [home.endsWith(path.sep) ? home : home + path.sep];
    if (home.includes("\\") && !home.endsWith("\\"))
        prefixes.push(home + "\\");
    return prefixes;
}
```

在Linux/macOS上，`path.sep`为`/`，生成`/Users/alice/`前缀。在Windows上，`path.sep`为`\`，生成`C:\Users\alice\`前缀。同时检测原始路径中是否包含`\\`，如果有则添加带`\\`的前缀变体。

**normalizeCompactedSkillPath**（第235-237行）：

```javascript
function normalizeCompactedSkillPath(filePath, matchedHomePrefix) {
    return matchedHomePrefix.includes("\\")
        ? filePath.replace(/\\/g, "/")
        : filePath;
}
```

如果匹配到的前缀包含Windows风格反斜杠，则将路径剩余部分的反斜杠统一转换为正斜杠，确保最终路径在任何平台上都以一致的`~/.../...`格式呈现给LLM。

**紧凑路径示例**：

| 原始路径 | 压缩后 | 节省字符 |
|---------|-------|---------|
| `/Users/alice/.openclaw/skills/github/SKILL.md` | `~/.openclaw/skills/github/SKILL.md` | 11 |
| `/home/ubuntu/.claude/skills/my-tool/SKILL.md` | `~/.claude/skills/my-tool/SKILL.md` | 12 |
| `/root/.openclaw/skills/extra/SKILL.md` | `~/.openclaw/skills/extra/SKILL.md` | 4 |

### 4.1.4 整体路径压缩流程（compactSkillPaths）

`compactSkillPaths`函数（第218-225行）对整个Skill数组执行主目录压缩：

```javascript
function compactSkillPaths(skills) {
    const homes = resolveCompactHomePrefixes();
    if (homes.length === 0) return skills;
    return skills.map((s) => ({
        ...s,
        filePath: compactHomePath(s.filePath, homes)
    }));
}
```

**设计特点**：如果`resolveCompactHomePrefixes()`返回空数组（极端情况，如没有任何可识别的主目录），则返回原始Skill数组不做任何修改，确保不会丢失路径信息。

---

## 4.2 紧凑格式（formatSkillsCompact）

当完整格式（含name、description、location）的提示文本超过`maxSkillsPromptChars`（默认18000字符）限制时，OpenClaw自动切换到省略了`<description>`标签的紧凑格式。

### 4.2.1 紧凑格式的定义

`formatSkillsCompact`函数（`workspace-WZoAbmov.js`第772-789行）：

```javascript
function formatSkillsCompact(skills) {
    if (skills.length === 0) return "";
    const lines = [
        "\n\nThe following skills provide specialized instructions for specific tasks.",
        "Use the read tool to load a skill's file when the task matches its name.",
        "When a skill file references a relative path, resolve it against the skill directory (parent of SKILL.md / dirname of the path) and use that absolute path in tool commands.",
        "",
        "<available_skills>"
    ];
    for (const skill of skills) {
        lines.push("  <skill>");
        lines.push(`    <name>${escapeXml(skill.name)}</name>`);
        lines.push(`    <location>${escapeXml(skill.filePath)}</location>`);
        lines.push("  </skill>");
    }
    lines.push("</available_skills>");
    return lines.join("\n");
}
```

**与完整格式的结构对比：**

| 特征 | 完整格式 (formatSkillsForPrompt) | 紧凑格式 (formatSkillsCompact) |
|------|------|------|
| 前缀引导文本 | 相同（第一句和第三句不变） | 第二句改为 `matches its name`（强调匹配名称而非描述） |
| `<description>` | 包含 | **省略** |
| `<name>` | 包含 | 包含 |
| `<location>` | 包含 | 包含 |
| 结尾标签 | `</available_skills>` | `</available_skills>` |
| 每个skill节省 | 基准 | 约`<description>...</description>`标签及其内容长度 |

紧凑格式的语义变化在于引导文本第二句：从"Use the read tool to load a skill's file when the **task matches its description**"变为"Use the read tool to load a skill's file when the **task matches its name**"。因为在紧凑格式下LLM看不到description字段，只能根据skill名称进行模糊匹配。

### 4.2.2 二进制搜索算法（applySkillsPromptLimits）

`applySkillsPromptLimits`函数（第791-818行）是提示词截断决策的核心，它实现了从完整格式到紧凑格式的优雅降级：

```javascript
function applySkillsPromptLimits(params) {
    const limits = resolveSkillsLimits(params.config, params.agentId);
    const total = params.skills.length;
    const byCount = params.skills.slice(0, Math.max(0, limits.maxSkillsInPrompt));
    let skillsForPrompt = byCount;
    let truncated = total > byCount.length;
    let compact = false;

    const fitsFull = (skills) => formatSkillsForPrompt(skills).length <= limits.maxSkillsPromptChars;
    const compactBudget = limits.maxSkillsPromptChars - COMPACT_WARNING_OVERHEAD;
    const fitsCompact = (skills) => formatSkillsCompact(skills).length <= compactBudget;

    if (!fitsFull(skillsForPrompt))
        if (fitsCompact(skillsForPrompt)) compact = true;
        else {
            compact = true;
            let lo = 0;
            let hi = skillsForPrompt.length;
            while (lo < hi) {
                const mid = Math.ceil((lo + hi) / 2);
                if (fitsCompact(skillsForPrompt.slice(0, mid)))
                    lo = mid;
                else
                    hi = mid - 1;
            }
            skillsForPrompt = skillsForPrompt.slice(0, lo);
            truncated = true;
        }

    return { skillsForPrompt, truncated, compact };
}
```

**算法步骤详解：**

1. **第一阶段：数量截断**。首先通过`limits.maxSkillsInPrompt`（默认150）按数量初步截断，确保skill数量不超过上限。`byCount = params.skills.slice(0, Math.max(0, limits.maxSkillsInPrompt))`。

2. **第二阶段：大小检测**。尝试用完整格式（`fitsFull`）校验已截断的skill列表的字符长度是否在限制内。

3. **第三阶段：紧凑格式切换**。如果完整格式超限但紧凑格式（`fitsCompact`）可容纳——即`formatSkillsCompact`输出长度不超过`maxSkillsPromptChars - 150`（其中150是警告消息的预估长度`COMPACT_WARNING_OVERHEAD`）——则切换到紧凑格式，不做进一步截断。

4. **第四阶段：二进制搜索**。如果紧凑格式仍然超限，进入二分查找：
   - 搜索空间：`lo = 0`, `hi = skillsForPrompt.length`
   - 取中点：`mid = Math.ceil((lo + hi) / 2)`（**向上取整**）
   - 如果前mid个skill的紧凑格式仍可容纳：`lo = mid`（扩大窗口）
   - 如果前mid个skill的紧凑格式已超限：`hi = mid - 1`（缩小窗口）
   - 循环结束时，`lo`是紧凑格式下可容纳的最大skill数量

   **关键细节——为什么使用`Math.ceil`向上取整：** 在二分查找中，如果使用`Math.floor`，当`lo=0, hi=1`时`mid=0`，`lo`始终为0，导致死循环。使用`Math.ceil`确保`mid >= 1`，能够向前推进。以`lo=0, hi=3`为例：第一次迭代`mid=Math.ceil(1.5)=2`，若`fitsCompact(前2个)===true`则`lo=2`；第二次迭代`mid=Math.ceil(2.5)=3`，若`fitsCompact(前3个)===true`则`lo=3`，`lo===hi`循环结束。这样保证在`O(log n)`复杂度下找到最大可容纳数量。

5. **结果组装**：返回三元组`{ skillsForPrompt, truncated, compact }`，分别表示最终展示的skill列表、是否发生了截断、是否使用紧凑格式。

### 4.2.3 截断警告消息

在`resolveWorkspaceSkillPromptState`函数（第843-864行）中，截断信息被组装为警告消息插入到提示文本之前：

```javascript
return {
    eligible,
    prompt: [
        remoteNote,
        truncated
            ? `⚠️ Skills truncated: included ${skillsForPrompt.length} of ${resolvedSkills.length}${compact ? " (compact format, descriptions omitted)" : ""}. Run \`openclaw skills check\` to audit.`
            : compact
                ? `⚠️ Skills catalog using compact format (descriptions omitted). Run \`openclaw skills check\` to audit.`
                : "",
        compact ? formatSkillsCompact(skillsForPrompt) : formatSkillsForPrompt(skillsForPrompt)
    ].filter(Boolean).join("\n"),
    resolvedSkills
};
```

**警告消息的三种情况：**

| 场景 | truncated | compact | 警告消息 |
|------|-----------|---------|---------|
| 完整格式容纳所有skill | false | false | 无警告，正常展示 |
| 紧凑格式容纳所有skill | false | true | `Skills catalog using compact format (descriptions omitted)` |
| 连紧凑格式都不够 | true | true | `Skills truncated: included X of Y (compact format, descriptions omitted)` |

---

## 4.3 四种暴露控制组合

OpenClaw为每个Skill定义了三个暴露层面的权限位，形成四种有意义的组合。这个机制由`isSkillVisibleInAvailableSkillsPrompt`函数和`exposure`对象的构建逻辑共同实现。

### 4.3.1 核心判断逻辑

`isSkillVisibleInAvailableSkillsPrompt`函数（`workspace-WZoAbmov.js`第241-245行）：

```javascript
function isSkillVisibleInAvailableSkillsPrompt(entry) {
    if (entry.exposure)
        return entry.exposure.includeInAvailableSkillsPrompt !== false;
    if (entry.invocation)
        return entry.invocation.disableModelInvocation !== true;
    return entry.skill.disableModelInvocation !== true;
}
```

**三层回退逻辑：**
1. 优先读取`entry.exposure.includeInAvailableSkillsPrompt`（由`loadSkillEntries`计算得出）
2. 如果没有`exposure`字段，回退到`entry.invocation.disableModelInvocation`（取自SKILL.md的frontmatter）
3. 如果连`invocation`也没有，回退到最内层的`entry.skill.disableModelInvocation`

### 4.3.2 Exposure对象的构建

在`loadSkillEntries`函数（第522-762行）的末尾，每个skill条目被组装为包含完整exposure信息的对象：

```javascript
return {
    skill,
    frontmatter,
    metadata: resolveOpenClawMetadata(frontmatter),
    invocation,
    ...record.syncSourceDir !== void 0 ? { syncSourceDir: record.syncSourceDir } : {},
    ...record.syncDirName !== void 0 ? { syncDirName: record.syncDirName } : {},
    exposure: {
        includeInRuntimeRegistry: true,
        includeInAvailableSkillsPrompt: invocation.disableModelInvocation !== true,
        userInvocable: invocation.userInvocable !== false
    }
};
```

`exposure`对象的三个字段：
- `includeInRuntimeRegistry`：**始终为`true`**——无论其他权限如何，Skill始终注册在运行时注册表中。
- `includeInAvailableSkillsPrompt`：当`disableModelInvocation !== true`时（即未禁用模型调用）为`true`。控制Skill是否出现在`<available_skills>`XML块中。
- `userInvocable`：当`invocation.userInvocable !== false`时（即未明确禁止用户调用）为`true`。控制用户是否可以通过命令显式调用该Skill。

### 4.3.3 四种权限组合

`invocation`对象由`resolveSkillInvocationPolicy(frontmatter)`函数从前置元数据（YAML frontmatter）解析而来。两个关键布尔字段 `disableModelInvocation` 和 `userInvocable` 形成四种有意义的组合：

| disableModelInvocation | userInvocable | 行为描述 |
|------------------------|--------------|---------|
| `false`（默认） | `true`（默认） | **全公开模式**：Skill出现在`<available_skills>`提示词中，LLM可自动调用；用户也可通过命令显式调用。 |
| `false`（默认） | `false` | **仅LLM模式**：Skill出现在提示词中，LLM可自动加载；但用户无法通过命令显式触发。 |
| `true` | `true`（默认） | **仅命令模式**：Skill **不出现在提示词中**，LLM无法自动感知；只有用户通过命令显式调用时才会加载。 |
| `true` | `false` | **完全隐藏模式**：Skill既不出现在提示词中，也不响应用户命令。只能通过代码/API直接访问。 |

**第四种组合的用途**：隐藏Skill是系统中被其他Skill内部引用的工具Skill，不直接面向用户或LLM。例如，某些底层数据处理Skill可能只被workspace中的其他Skill通过编程方式调用，无需在提示词或命令系统中暴露。

**默认值设计哲学**：`disableModelInvocation`默认为`false`（允许LLM调用），`userInvocable`默认为`true`（允许用户调用）。这意味着开发者不需要在SKILL.md中声明任何权限字段即可获得"全公开"行为，降低了入门门槛。权限收紧是主动行为（设置为`true`或`false`），体现了"默认开放、显式限制"的设计原则。

### 4.3.4 过滤管道的集成

在`filterSkillEntries`函数（第246-260行）中，暴露控制与其他过滤条件协同工作：

```javascript
function filterSkillEntries(entries, config, skillFilter, eligibility) {
    let filtered = entries.filter((entry) => shouldIncludeSkill({
        entry, config, eligibility
    }));
    if (skillFilter !== void 0) {
        const normalized = normalizeSkillFilter(skillFilter) ?? [];
        // ... 按filter名称筛选
    }
    return filtered;
}
```

`shouldIncludeSkill`函数检查skill是否满足当前环境条件（如node版本、操作系统平台等），而`isSkillVisibleInAvailableSkillsPrompt`在后续步骤中决定是否将该skill放入提示词。两者是独立的过滤维度。

---

## 4.4 Skill安全扫描引擎（双层规则引擎）

OpenClaw内置了一个两层的安全扫描引擎（位于`skill-scanner-CQsVFfux.js`），在Skill安装前对源代码执行静态分析。第一层为**逐行规则**（LINE_RULES），对每一行独立匹配；第二层为**源级规则**（SOURCE_RULES），需要整个文件或上下文窗口的匹配。

### 4.4.1 扫描范围与缓存

**可扫描的文件扩展名**（第7-16行）：

```javascript
const SCANNABLE_EXTENSIONS = new Set([
    ".js", ".ts", ".mjs", ".cjs", ".mts", ".cts", ".jsx", ".tsx"
]);
```

扫描引擎仅处理JavaScript/TypeScript系列的源文件。`.json`、`.md`、`.yaml`等文件不在扫描范围内——这基于一个合理假设：可执行的恶意代码几乎总是出现在JS/TS文件中。

**扫描上限**：
- `DEFAULT_MAX_SCAN_FILES = 500`：单个Skill目录最多扫描500个文件
- `DEFAULT_MAX_FILE_BYTES = 1024 * 1024`（1MB）：单个文件超过1MB则跳过，防止内存溢出
- `FILE_SCAN_CACHE_MAX = 5000`：缓存最多5000个文件的扫描结果
- `DIR_ENTRY_CACHE_MAX = 5000`：缓存最多5000个目录条目

**缓存机制**：`FILE_SCAN_CACHE`和`DIR_ENTRY_CACHE`使用LRU驱逐策略（移除最先插入的条目），缓存键基于文件路径+大小+mtime+maxFileBytes，任何一个维度变化都会导致缓存失效。

**测试文件排除**：扫描时默认跳过测试目录（`__fixtures__`, `__mocks__`, `__tests__`, `test`, `tests`）和测试文件（文件名匹配`/\.(?:mock|spec|test)\.[^.]+$/i`）。这避免了将测试fixture中的恶意代码样本误报为安全风险。

### 4.4.2 第一层：逐行规则（LINE_RULES）

```javascript
const LINE_RULES = [
    {
        ruleId: "dangerous-exec",
        severity: "critical",
        message: "Shell command execution detected (child_process)",
        pattern: /\b(exec|execSync|spawn|spawnSync|execFile|execFileSync)\s*\(/,
        requiresContext: /child_process/
    },
    {
        ruleId: "dynamic-code-execution",
        severity: "critical",
        message: "Dynamic code execution detected",
        pattern: /\beval\s*\(|new\s+Function\s*\(/
    },
    {
        ruleId: "crypto-mining",
        severity: "critical",
        message: "Possible crypto-mining reference detected",
        pattern: /stratum\+tcp|stratum\+ssl|coinhive|cryptonight|xmrig/i
    },
    {
        ruleId: "suspicious-network",
        severity: "warn",
        message: "WebSocket connection to non-standard port",
        pattern: /new\s+WebSocket\s*\(\s*["']wss?:\/\/[^"']*:(\d+)/
    }
];
```

**每条规则的详细分析：**

#### 规则1: `dangerous-exec` — Shell命令执行检测

- **严重级别**：critical
- **正则**：`/\b(exec|execSync|spawn|spawnSync|execFile|execFileSync)\s*\(/`
- **上下文要求**：文件中必须同时包含`child_process`引用（`requiresContext: /child_process/`）

这个规则能捕获Node.js的`child_process`模块的所有主要执行方法。但直接匹配`exec(`会导致误报——例如`/regex/.exec(str)`是RegExp的合法方法。为此，专门实现了`isBenignMemberExecMatch`函数（第129-134行）：

```javascript
function isBenignMemberExecMatch(line, match) {
    if (match[1] !== "exec") return false;
    const matchIndex = match.index;
    if (matchIndex <= 0 || line[matchIndex - 1] !== ".") return false;
    return !/\b(?:cp|childProcess|child_process)\s*\.\s*exec\s*\(/.test(line);
}
```

该函数通过三个条件排除了对RegExp.exec()的误报：
1. 只有当匹配到的函数名恰好是`exec`时才进入判断（spawn、execFile等不需要）
2. 检查`exec(`前面是否有`.`前缀——`regex.exec()`有`.`前缀，`exec()`没有
3. **最关键**：即使有`.`前缀，如果前面是`cp.exec(`或`child_process.exec(`或`childProcess.exec(`，则仍然视为真正的危险调用

#### 规则2: `dynamic-code-execution` — 动态代码执行

- **严重级别**：critical
- **正则**：`/\beval\s*\(|new\s+Function\s*\(/`

捕获`eval()`和`new Function()`——JavaScript中最危险的代码执行方式。`eval()`可以执行任意字符串代码，`new Function()`创建的函数体也是字符串参数。两者都是典型的代码注入入口。

这个规则没有`requiresContext`，意味着无论在什么上下文中出现`eval()`或`new Function()`都会触发警告——这是有意为之的严格策略。

#### 规则3: `crypto-mining` — 加密挖矿检测

- **严重级别**：critical
- **正则**：`/stratum\+tcp|stratum\+ssl|coinhive|cryptonight|xmrig/i`

检测加密挖矿相关的字符串特征：
- `stratum+tcp` / `stratum+ssl`：Stratum挖矿协议URL前缀
- `coinhive`：著名的浏览器挖矿服务（已关闭但代码仍在流通）
- `cryptonight`：CryptoNight挖矿算法
- `xmrig`：XMRig矿工程序

这是**完全基于字符串特征**的检测，不依赖代码结构分析。即使挖矿代码被注释掉或仅作为字符串常量出现，也会触发警告——这是有意为之的高敏感性策略。

#### 规则4: `suspicious-network` — 非标准端口WebSocket

- **严重级别**：warn
- **正则**：`/new\s+WebSocket\s*\(\s*["']wss?:\/\/[^"']*:(\d+)/`

检测WebSocket连接到非标准端口。代码中定义了标准端口白名单：

```javascript
const STANDARD_PORTS = new Set([80, 443, 8080, 8443, 3000]);
```

如果WebSocket连接的端口在标准端口中，则不产生警告。端口3000被列入白名单是因为它是Node.js开发服务器的常用端口。

### 4.4.3 第二层：源级规则（SOURCE_RULES）

```javascript
const NETWORK_SEND_CONTEXT_PATTERN = /\bfetch\s*\(|\bpost\s*\(|\.\s*post\s*\(|http\.request\s*\(/i;

const SOURCE_RULES = [
    {
        ruleId: "potential-exfiltration",
        severity: "warn",
        message: "File read combined with network send — possible data exfiltration",
        pattern: /readFileSync|readFile/,
        requiresContext: NETWORK_SEND_CONTEXT_PATTERN
    },
    {
        ruleId: "obfuscated-code",
        severity: "warn",
        message: "Hex-encoded string sequence detected (possible obfuscation)",
        pattern: /(\\x[0-9a-fA-F]{2}){6,}/
    },
    {
        ruleId: "obfuscated-code",
        severity: "warn",
        message: "Large base64 payload with decode call detected (possible obfuscation)",
        pattern: /(?:atob|Buffer\.from)\s*\(\s*["'][A-Za-z0-9+/=]{200,}["']/
    },
    {
        ruleId: "env-harvesting",
        severity: "critical",
        message: "Environment variable access combined with network send — possible credential harvesting",
        pattern: /process\.env/,
        requiresContext: NETWORK_SEND_CONTEXT_PATTERN,
        requiresContextWindowLines: 8
    }
];
```

**第二层规则的独特之处：** 源级规则不逐行匹配，而是需要整个文件（或在指定窗口内）同时满足两个条件（`pattern`和`requiresContext`）。这模拟了"行为组合检测"的安全分析思路。

#### 规则1: `potential-exfiltration` — 潜在数据外泄

- **正则（primary）**：`/readFileSync|readFile/` —— 检测文件读取
- **上下文要求**：文件中必须同时存在网络发送操作（`fetch(`, `post(`, `.post(`, `http.request(`）

**设计原理**：单独读取文件本身不危险，单独发送网络请求也正常。但当两者同时出现在同一个Skill文件中时，意味着该Skill可能读取本地文件后通过网络发送出去——这正是数据外泄的典型行为模式。

#### 规则2: `obfuscated-code` — 混淆代码检测（十六进制）

- **正则**：`/(\\x[0-9a-fA-F]{2}){6,}/`

检测连续6个及以上的十六进制转义序列（如`\x48\x65\x6c\x6c\x6f\x20`）。正常的应用程序代码中极少出现长串的十六进制转义字符，这种模式通常是恶意代码用来隐藏字符串内容的混淆技术。

**为什么是6个**：单一的`\xNN`可能是合法的Unicode转义（如`\x1b`表示ESC），2-3个可能是偶然的（如`\x00\x00`），但当连续出现6个及以上时，几乎可以肯定是故意的混淆行为。

#### 规则3: `obfuscated-code` — 混淆代码检测（Base64）

- **正则**：`/(?:atob|Buffer\.from)\s*\(\s*["'][A-Za-z0-9+/=]{200,}["']/`

检测使用`atob()`或`Buffer.from()`解码长度超过200个字符的Base64字符串。正常的应用程序很少需要解码如此长的Base64字符串，这通常是用来隐藏恶意payload的混淆技术。

**注意**：两个混淆检测规则共享同一个`ruleId`（`"obfuscated-code"`），所以即使两个规则都匹配同一文件，也只会产生一条告警。`matchedSourceRules` Set使用`"obfuscated-code::<message>"`作为键来进行去重。

#### 规则4: `env-harvesting` — 环境变量收集

- **正则（primary）**：`/process\.env/` —— 检测环境变量访问
- **上下文要求**：在8行窗口内同时存在网络发送模式
- **窗口定义**：`requiresContextWindowLines: 8`

这是所有规则中唯一使用**上下文窗口**的规则。在第181-198行的`findSourceRuleMatch`函数中：

```javascript
if (params.rule.requiresContext && params.rule.requiresContextWindowLines !== void 0) {
    const start = Math.max(0, i - params.rule.requiresContextWindowLines);
    const end = Math.min(params.lines.length, i + params.rule.requiresContextWindowLines + 1);
    const windowSource = params.lines.slice(start, end).join("\n");
    if (!params.rule.requiresContext.test(windowSource)) continue;
}
```

**窗口范围**：以`process.env`所在行为中心，上下各取8行（总共17行），将该窗口连接后检测是否存在网络发送模式。这种窗口设计基于一个观察：在同一函数的短代码段内读取环境变量并通过网络发送，是高风险的凭据外泄行为。

### 4.4.4 注释剥离（stripCommentsForHeuristics）

源级规则在匹配前会对源代码执行**启发式注释剥离**，由`stripCommentsForHeuristics`函数（第135-177行）实现。这不是一个完整的JavaScript解析器，而是一个轻量的状态机：

```javascript
function stripCommentsForHeuristics(source) {
    let stripped = "";
    let quote = null;
    let escaped = false;
    let inBlockComment = false;
    for (let i = 0; i < source.length; i++) {
        const ch = source[i] ?? "";
        const next = source[i + 1] ?? "";
        // 处理块注释
        if (inBlockComment) {
            if (ch === "*" && next === "/") { inBlockComment = false; i++; continue; }
            if (ch === "\n") stripped += "\n";  // 保留换行以保持行号对齐
            continue;
        }
        // 处理字符串字面量
        if (quote) {
            stripped += ch;
            if (escaped) escaped = false;
            else if (ch === "\\") escaped = true;
            else if (ch === quote) quote = null;
            continue;
        }
        // 字符串开始
        if (ch === "'" || ch === "\"" || ch === "`") { quote = ch; stripped += ch; continue; }
        // 单行注释
        if (ch === "/" && next === "/") {
            while (i < source.length && source[i] !== "\n") i++;
            if (source[i] === "\n") stripped += "\n";
            continue;
        }
        // 块注释开始
        if (ch === "/" && next === "*") { inBlockComment = true; i++; continue; }
        stripped += ch;
    }
    return stripped;
}
```

**状态机行为：**

1. **块注释（`/* ... */`）**：内容被完全剥离，但保留其中的换行符以维护行号对应关系。这对于源级规则的行号报告至关重要。

2. **单行注释（`// ...`）**：从`//`到行尾的内容被剥离，保留末尾的换行符。

3. **字符串字面量**：`'...'`、`"..."`、`` `...` `` 中的内容被保留。这很重要——字符串中的`eval`可以安全忽略（因为它是字符串字面量，不会被执行），但字符串中的`process.env`可能通过某种间接方式被使用。

4. **转义序列**：在字符串内部正确处理`\`转义，避免将`\'`误判为字符串结束。

**这个启发式剥离器的设计权衡**：它不是完整的JavaScript解析器（无法处理模板字符串嵌套、正则字面量等复杂情况），但在大多数实际场景下足够准确，且执行速度极快（O(n)单次扫描）。

### 4.4.5 扫描时机：安装前

安全扫描的核心使用时机是**Skill安装前**，而不是加载时。在`skills-clawhub-Drelw-fl.js`的`installExtractedSkillRoot`函数中（第73-112行）：

```javascript
if (params.scan) {
    const scanResult = await scanSkillInstallSource({
        dangerouslyForceUnsafeInstall: params.scan.dangerouslyForceUnsafeInstall,
        installId: params.scan.installId ?? "archive",
        logger: params.logger ?? {},
        origin: params.scan.origin,
        skillName: params.slug,
        sourceDir: params.extractedRoot
    });
    if (scanResult?.blocked)
        return installFailure(scanResult.blocked.reason,
            scanBlockedFailureKind(scanResult.blocked));
}
```

`scanSkillInstallSourceRuntime`函数（`install-security-scan.runtime-BQl37o2K.js`第913-949行）调用`scanDirectoryTarget`执行实际扫描，如果发现critical级别告警则返回`{ blocked: { code: "security_scan_blocked", reason: "..." } }`，阻止安装。

**注意**：在ClawHub的安装流程中（`performClawHubSkillInstall`），`scan`参数被**显式设置为`false`**：

```javascript
scan: false,  // 第224行
rootMarkers: CLAWHUB_SKILL_ARCHIVE_ROOT_MARKERS
```

这意味着ClawHub来源的Skill**不经过安全扫描**。这一设计决策可能基于对ClawHub注册表已做安全审核的信任假设。

---

## 4.5 ClawHub技能市场

ClawHub是OpenClaw的官方Skill分发平台，类似于npm之于Node.js或GitHub Marketplace之于GitHub Actions。它提供了Skill的搜索、安装、版本管理和更新功能。

### 4.5.1 搜索API（searchClawHubSkills）

底层调用封装在`clawhub-fpZxNGuO.js`中：

```javascript
async function searchClawHubSkills(params) {
    return (await fetchJson({
        baseUrl: params.baseUrl,
        path: "/api/v1/search",
        token: params.token,
        timeoutMs: params.timeoutMs,
        fetchImpl: params.fetchImpl,
        search: {
            q: params.query.trim(),
            limit: params.limit ? String(params.limit) : void 0
        }
    })).results ?? [];
}
```

- **端点**：`/api/v1/search`
- **查询参数**：`q`（搜索关键词）、`limit`（结果数量限制）
- **认证**：通过Bearer Token（从环境变量`OPENCLAW_CLAWHUB_TOKEN`或配置文件`~/.config/clawhub/config.json`读取）
- **基地址**：默认为`https://clawhub.ai`，可通过环境变量覆盖

**ClawHub基地址的解析优先级**（`normalizeBaseUrl`函数）：
1. 显式传入的`baseUrl`参数
2. 环境变量`OPENCLAW_CLAWHUB_URL`
3. 环境变量`CLAHUB_URL`
4. 默认值`https://clawhub.ai`

### 4.5.2 Skill安装流程

完整的ClawHub安装流程涉及五个主要步骤：

#### 步骤1：Slug验证

`validateRequestedSkillSlug`函数（`skills-clawhub-Drelw-fl.js`第27-31行）：

```javascript
function validateRequestedSkillSlug(raw) {
    const slug = normalizeTrackedSkillSlug(raw);
    if (hasNonAscii(slug) || !VALID_SLUG_PATTERN.test(slug))
        throw new Error(`Invalid skill slug: ${raw}`);
    return slug;
}
```

- `normalizeTrackedSkillSlug`：去除空白，检查是否包含`/`、`\`、`..`等路径穿越字符
- `hasNonAscii`：禁止非ASCII字符
- `VALID_SLUG_PATTERN = /^[a-z0-9](?:[a-z0-9-]*[a-z0-9])?$/i`：slug必须以字母数字开头和结尾，中间可包含连字符

#### 步骤2：获取版本信息

`resolveInstallVersion`函数（第181-193行）：

```javascript
async function resolveInstallVersion(params) {
    const detail = await fetchClawHubSkillDetail({
        slug: params.slug,
        baseUrl: params.baseUrl
    });
    if (!detail.skill)
        throw new Error(`Skill "${params.slug}" not found on ClawHub.`);
    const resolvedVersion = params.version ?? detail.latestVersion?.version;
    if (!resolvedVersion)
        throw new Error(`Skill "${params.slug}" has no installable version.`);
    return { detail, version: resolvedVersion };
}
```

调用`/api/v1/skills/{slug}` API获取Skill详情，解析出最新版本号。

#### 步骤3：下载Tarball

`downloadClawHubSkillArchive`函数（`clawhub-fpZxNGuO.js`第366-399行）：

- **端点**：`/api/v1/download?slug={slug}&version={version}`
- **下载**：通过`readResponseWithLimit`流式读取响应体，有下载超时保护
- **SHA256校验**：计算下载内容的SHA256哈希，生成完整性校验信息
- **临时文件**：写入系统临时目录（`os.tmpdir()`），文件名为`{slug}.zip`

#### 步骤4：解压并验证

`withExtractedArchiveRoot`函数解压下载的zip档案到临时目录，然后通过`installExtractedSkillRoot`进行验证：

1. 检查解压根目录是否包含`SKILL.md`（支持多种大小写变体：`SKILL.md`、`skill.md`、`skills.md`、`SKILL.MD`）
2. 如果缺失SKILL.md，返回错误`"archive is missing SKILL.md"`
3. 检查目标安装路径是否已存在（非force模式下阻止覆盖）
4. （可选）执行安全扫描

#### 步骤5：写入元数据

安装成功后，写入两套持久化元数据：

**a) origin.json**（记录安装来源）：

```javascript
await writeClawHubSkillOrigin(install.targetDir, {
    version: 1,
    registry: resolveClawHubBaseUrl(params.baseUrl),
    slug: params.slug,
    installedVersion: version,
    installedAt
});
```

`origin.json`存储在skill目录下的`.clawhub/origin.json`中。读取时会同时检查旧版路径`.clawdhub/origin.json`。

**b) lock.json**（记录所有已安装skill）：

```javascript
lock.skills[params.slug] = { version, installedAt };
await writeClawHubSkillsLockfile(params.workspaceDir, lock);
```

`lock.json`存储在workspace根目录的`.clawhub/lock.json`中，是一个集中式的版本记录文件。

### 4.5.3 Skill更新流程

`updateSkillsFromClawHub`函数（第306-349行）处理批量或单个Skill的更新：

```javascript
async function updateSkillsFromClawHub(params) {
    const lock = await readClawHubSkillsLockfile(params.workspaceDir);
    const slugs = params.slug
        ? [await resolveRequestedUpdateSlug({
            workspaceDir: params.workspaceDir,
            requestedSlug: params.slug,
            lock
        })]
        : Object.keys(lock.skills).map((slug) => normalizeTrackedSkillSlug(slug));

    const results = [];
    for (const slug of slugs) {
        const tracked = await resolveTrackedUpdateTarget({
            workspaceDir: params.workspaceDir, slug, lock,
            baseUrl: params.baseUrl
        });
        if (!tracked.ok) { results.push({ ok: false, error: tracked.error }); continue; }

        const install = await installTrackedSkillFromClawHub({
            workspaceDir: params.workspaceDir, slug: tracked.slug,
            baseUrl: tracked.baseUrl, force: true, logger: params.logger
        });

        if (!install.ok) { results.push(install); continue; }

        results.push({
            ok: true,
            slug: tracked.slug,
            previousVersion: tracked.previousVersion,
            version: install.version,
            changed: tracked.previousVersion !== install.version,
            targetDir: install.targetDir
        });
    }
    return results;
}
```

**更新流程特点：**

1. **批量更新**：如果不指定`slug`，则更新`lock.json`中记录的所有已安装Skill。
2. **版本对比**：`resolveTrackedUpdateTarget`读取已安装版本的`origin.json`，获取`previousVersion`；安装后再与`install.version`对比，计算`changed`字段。
3. **force模式**：更新时强制传入`force: true`，允许覆盖已有安装。
4. **原子性**：每个Skill的更新是独立的，一个失败不影响其他。

### 4.5.4 安全扫描与安装的关系

值得注意的是，**ClawHub来源的Skill不经过内置安全扫描**（`scan: false`），但**从本地archive文件安装的Skill会经过安全扫描**。这一区分在`installSkillArchiveFromPath`和`performClawHubSkillInstall`中清晰可见：

| 安装来源 | 是否安全扫描 | 原因 |
|---------|------------|------|
| ClawHub | 否（`scan: false`） | 假定ClawHub注册表已做安全审核 |
| 本地archive | 是（除非显式关闭） | 本地文件来源不受信任 |
| `--dangerously-force-unsafe-install` | 扫描但不阻止 | 用户明确接受风险 |

---

## 4.6 远程Skill系统

OpenClaw的远程Skill系统（`skills-remote-DxHdZ133.js`）专门处理**macOS特定Skill在远程macOS节点上的可用性判断**。它解决了这样一个问题：当用户从Linux/Windows机器连接到一个macOS远程节点时，某些Skill（如Xcode构建相关）仅在macOS上可用。

### 4.6.1 远程节点信息管理

系统维护一个全局的`remoteNodes` Map，键为节点ID，值为包含以下字段的记录：

```javascript
{
    nodeId: string,          // 节点唯一标识
    displayName: string,     // 显示名称
    platform: string,        // 操作系统平台（"darwin"等）
    deviceFamily: string,    // 设备系列（"mac"等）
    commands: string[],      // 节点支持的MCP命令列表
    remoteIp: string,        // 远程IP地址
    bins: Set<string>,       // 节点上可用的二进制工具集合
    connected: boolean       // 当前连接状态
}
```

**macOS平台识别**（`isMacPlatform`函数）：

```javascript
function isMacPlatform(platform, deviceFamily) {
    const platformNorm = normalizeLowercaseStringOrEmpty(platform);
    const familyNorm = normalizeLowercaseStringOrEmpty(deviceFamily);
    if (platformNorm.includes("mac")) return true;
    if (platformNorm.includes("darwin")) return true;
    if (familyNorm === "mac") return true;
    return false;
}
```

通过三个维度交叉验证平台身份：`platform`字段（如"darwin"）、`platform`中的子串匹配（如包含"mac"）、以及`deviceFamily`（如"mac"）。

### 4.6.2 远程节点连接时的自动二进制探测

当远程节点连接时，`recordRemoteNodeInfo`函数被调用：

```javascript
function recordRemoteNodeInfo(node) {
    upsertNode({ ...node, connected: true });
}
```

随后，`refreshRemoteBinsForConnectedNodes`对所有已连接的节点执行二进制探测。探测过程(`refreshRemoteNodeBinsUncoalesced`)的核心逻辑：

1. **过滤macOS节点**：只对`isMacPlatform()`返回true的节点执行探测
2. **检查命令能力**：节点必须支持`system.which`或`system.run`命令
3. **收集所需二进制**：扫描所有workspace skill条目，收集`metadata.requires.bins`和`metadata.requires.anyBins`中声明的、平台为darwin的二进制工具
4. **构建探测脚本**（`buildBinProbeScript`）：

```javascript
function buildBinProbeScript(bins) {
    return `for b in ${bins.map((bin) =>
        `'${bin.replace(/'/g, `'\\''`)}'`
    ).join(" ")}; do
        if command -v "$b" >/dev/null 2>&1; then echo "$b"; fi;
    done`;
}
```

这是一个POSIX shell脚本，遍历所有需要的二进制名称，通过`command -v`检查其是否在PATH中。Shell引用处理：`bin.replace(/'/g, `'\\''`)`确保即使二进制名称包含单引号也能正确转义。

5. **通过MCP远程执行**：
   - 如果节点支持`system.which`：直接传递二进制列表，由远程的which命令解析
   - 如果节点仅支持`system.run`：通过`/bin/sh -lc`执行上面的探测脚本

6. **解析探测结果**：`parseBinProbePayload`支持三种返回格式——JSON格式的bins数组、JSON格式的bins对象（键为二进制名）、或stdout纯文本格式。

### 4.6.3 连接性预检查（2秒超时）

在执行二进制探测之前，系统先进行一个快速的连接性检查：

```javascript
const connectivityTimeoutMs = Math.min(timeoutMs, 2000);  // 2秒超时
if (typeof remoteRegistry.checkConnectivity === "function") {
    const connectivity = await remoteRegistry.checkConnectivity(
        params.nodeId, connectivityTimeoutMs);
    if (!connectivity.ok) {
        // ... 清理bins并跳过探测
        return;
    }
}
```

**设计理由**：如果远程节点网络不稳定或已断开，2秒的超时可以快速失败而不是阻塞15秒（默认的`timeoutMs`）。这是一个经典的**双阶段超时**模式——快速失败用于连接性检查，更长的超时用于实际数据收集。

**会话变更检测**：如果预检查失败但最新的会话ID（`connId`）与启动检查时不同，则递归调用自身使用最新会话重试。这处理了"节点重连导致旧会话失效"的竞态条件。

### 4.6.4 远程Skill资格判定（getRemoteSkillEligibility）

`getRemoteSkillEligibility`函数（第266-279行）构建一个eligibility上下文对象：

```javascript
function getRemoteSkillEligibility(options) {
    const macNodes = [...remoteNodes.values()]
        .filter((node) => node.connected
            && isMacPlatform(node.platform, node.deviceFamily)
            && supportsSystemRun(node.commands));

    if (macNodes.length === 0) return;

    const bins = new Set();
    for (const node of macNodes)
        for (const bin of node.bins)
            bins.add(bin);

    const labels = macNodes.map((node) => node.displayName ?? node.nodeId)
        .filter(Boolean);
    const note = options?.advertiseExecNode === false
        ? void 0
        : labels.length > 0
            ? `Remote macOS node available (${labels.join(", ")}). Run macOS-only skills via exec host=node on that node.`
            : "Remote macOS node available. Run macOS-only skills via exec host=node on that node.";

    return {
        platforms: ["darwin"],
        hasBin: (bin) => bins.has(bin),
        hasAnyBin: (required) => required.some((bin) => bins.has(bin)),
        ...note ? { note } : {}
    };
}
```

**eligibility对象的语义**：

| 字段 | 类型 | 说明 |
|------|------|------|
| `platforms` | `string[]` | 固定为`["darwin"]`，表示远程Skill资格仅适用于macOS平台 |
| `hasBin` | `(bin: string) => boolean` | 闭包函数，检查某个二进制是否在任一远程节点上可用 |
| `hasAnyBin` | `(required: string[]) => boolean` | 闭包函数，检查是否至少有一个所需二进制在远程节点上可用 |
| `note` | `string?` | 注入到提示词中的说明消息，告知LLM远程macOS节点可用 |

**提示词注入**：`note`字段会被注入到系统提示词中（在`resolveWorkspaceSkillPromptState`的`remoteNote`部分），告知LLM存在远程macOS节点并可通过`exec host=node`执行命令。

### 4.6.5 快照版本追踪

每当远程节点状态发生变化时，系统通过`bumpSkillsSnapshotVersion({ reason: "remote-node" })`触发快照版本更新：

- 首次检测到macOS节点（`primeRemoteSkillsCache`）
- 节点断开连接（`removeRemoteNodeInfo`）
- 二进制探测结果发生变化（`refreshRemoteNodeBinsUncoalesced`）
- 连接性检查失败（清除旧bins时）

这确保了skill快照在远程能力发生变化时能够及时刷新。

---

## 4.7 Skills快照与会话持久化

为了让LLM在长对话中始终拥有一致的skill视图，同时避免每次对话回合都重新加载和扫描skill文件，OpenClaw实现了skills快照机制。

### 4.7.1 buildWorkspaceSkillSnapshot

`buildWorkspaceSkillSnapshot`函数（`workspace-WZoAbmov.js`第820-834行）：

```javascript
function buildWorkspaceSkillSnapshot(workspaceDir, opts) {
    const { eligible, prompt, resolvedSkills } =
        resolveWorkspaceSkillPromptState(workspaceDir, opts);
    const skillFilter = resolveEffectiveWorkspaceSkillFilter(opts);
    return {
        prompt,
        skills: eligible.map((entry) => ({
            name: entry.skill.name,
            primaryEnv: entry.metadata?.primaryEnv,
            requiredEnv: entry.metadata?.requires?.env?.slice()
        })),
        ...skillFilter === void 0 ? {} : { skillFilter },
        resolvedSkills,
        version: opts?.snapshotVersion
    };
}
```

**快照存储的字段：**

| 字段 | 类型 | 说明 |
|------|------|------|
| `prompt` | `string` | 已格式化的完整提示词文本（含XML和截断警告） |
| `skills` | `Array<{name, primaryEnv, requiredEnv}>` | 精简的skill元数据列表，每个skill仅保留名称、主环境变量、必需环境变量 |
| `skillFilter` | `string[]?` | 应用中的skill过滤器（如果有） |
| `resolvedSkills` | `Skill[]` | 完整解析后的skill对象数组 |
| `version` | `number?` | 快照版本号，用于检测是否需要刷新 |

**设计决策分析：**

1. **`prompt`预计算**：提示词文本在快照创建时即完整生成，后续使用时无需重新执行`formatSkillsForPrompt`/`formatSkillsCompact`和截断逻辑。这避免了对大量skill路径执行XML转义和主目录压缩的重复开销。

2. **`skills`数组的精简设计**：只存储`name`、`primaryEnv`和`requiredEnv`三个字段，而非完整的skill对象。这是因为快照中存储的是**eligible**（符合条件但可能不可见）的skill列表，而不仅是最终展示的skill。`primaryEnv`和`requiredEnv`用于后续的环境过滤决策。

3. **`requiredEnv`使用`.slice()`进行浅拷贝**：`entry.metadata?.requires?.env?.slice()`创建环境变量数组的副本，防止原始数据被意外修改。

4. **`skillFilter`条件包含**：只有当filter存在时才包含在快照中，避免存储冗余的`undefined`。

### 4.7.2 resolveSkillsPromptForRun

`resolveSkillsPromptForRun`函数（第865-877行）是对话运行时获取skill提示词的入口：

```javascript
function resolveSkillsPromptForRun(params) {
    const snapshotPrompt = params.skillsSnapshot?.prompt?.trim();
    if (snapshotPrompt) return snapshotPrompt;

    if (params.entries && params.entries.length > 0) {
        const prompt = buildWorkspaceSkillsPrompt(params.workspaceDir, {
            entries: params.entries,
            config: params.config,
            agentId: params.agentId
        });
        return prompt.trim() ? prompt : "";
    }
    return "";
}
```

**优先级策略（三级回退）：**

1. **快照优先**：如果`skillsSnapshot.prompt`存在且非空，直接返回。这意味着在整个对话会话中，即使worktree中的skill文件发生变化，LLM看到的skill列表也保持不变，确保一致性。

2. **传入条目回退**：如果没有快照但有预加载的entries，则使用这些entries现场构建提示词。这发生在snapshot尚未生成或已失效的短暂窗口期。

3. **完全不可用**：既无快照也无entries时返回空字符串。这可能发生在skill目录不存在或全部被过滤掉的情况下。

### 4.7.3 快照的生命周期

快照的创建、使用和失效遵循以下流程：

1. **创建时机**：在agent启动或对话开始时，由调用方（agent runtime）调用`buildWorkspaceSkillSnapshot`创建快照。快照存储在内存中（通常是agent的会话状态）。

2. **使用时机**：每次准备注入系统提示词时，调用`resolveSkillsPromptForRun`获取提示文本。如果快照存在，只需一次`.trim()`检查即可返回。

3. **失效条件**：快照版本号机制用于检测是否需要重建：
   - 远程节点状态变化（`bumpSkillsSnapshotVersion({ reason: "remote-node" })`）
   - skill文件被修改或添加/删除
   - 用户手动修改skill过滤器
   - 环境变量变化影响skill的eligibility判断

4. **与workspace同步**：快照引用的`resolvedSkills`包含所有eligible的skill，而不仅是prompt中展示的。这意味着即使某些skill被截断而未出现在提示词中，快照仍保留其完整信息，可用于后续的skill查找和命令调用。

---

## 本章小结

本章深入剖析了OpenClaw Skill系统的五个核心子系统，完整呈现了从skill加载到LLM可用的每一步细节：

1. **XML提示注入**采用分层格式——完整格式包含name/description/location三元素，紧凑格式仅保留name/location，通过用户主目录压缩（`~`替代绝对路径）节省5-6 token/路径。XML转义处理五种特殊字符，`&`最先转义以避免二次转义损坏。

2. **紧凑格式**通过`applySkillsPromptLimits`中的**二进制搜索算法**在O(log n)复杂度下找到能在`maxSkillsPromptChars`（默认18000字符）限制内展示的最大skill数量。该算法使用`Math.ceil`向上取整避免死循环。

3. **暴露控制策略**定义了四种语义精确的权限组合，由`disableModelInvocation`和`userInvocable`两个布尔位控制。最极端的是"完全隐藏模式"（两者均为true/false），skill只能通过代码/API访问，既不出现在提示词中也不响应用户命令。

4. **双层安全扫描引擎**在Skill安装前执行静态分析。Layer 1（逐行规则）检测`child_process`执行、`eval()`/`new Function()`、加密挖矿字符串和非标准端口WebSocket。Layer 2（源级规则）通过"行为组合"检测数据外泄（读文件+网络发送）、代码混淆（连续十六进制序列或大段Base64）、以及凭据收集（`process.env`+8行窗口内的网络发送）。关键创新包括对RegExp.exec()的误报排除和启发式注释剥离状态机。

5. **ClawHub技能市场**提供完整的生命周期管理。安装流程经过slug验证（禁止路径穿越和非ASCII）、版本解析、tarball下载（含SHA256校验）、解压验证（检查SKILL.md存在性）、元数据写入（origin.json+lock.json双重记录）五个步骤。更新流程支持批量更新，通过版本对比计算变更状态。

6. **远程Skill系统**为macOS特定Skill跨平台使用提供了支持。系统自动探测远程节点的可用二进制工具（通过`command -v`脚本），构建`RemoteSkillEligibility`闭包对象，并将远程节点信息注入提示词。2秒连接性预检查实现了快速失败策略，避免在不可达节点上浪费15秒超时。

7. **Skills快照**通过在会话启动时预计算并缓存提示词文本，避免了每次对话回合重复执行XML格式化、路径压缩和截断决策。三级回退策略（快照 > 传入条目 > 空字符串）确保了不同场景下的正确行为，版本号机制用于检测远程节点变化等触发刷新的条件。
# 五、Agent工具系统实现（上）：工具分类、工厂模式与Schema规范化

## 概述

OpenClaw的工具系统是整个Agent框架中最核心的子系统之一。它负责定义、注册、序列化和执行Agent可以调用的所有工具。本章分为五个部分，深入分析工具系统的设计与实现：

1. **工具分类与完整清单**：11大类、40+工具的详细说明及UI分组机制
2. **工厂函数模式**：顶层工厂 `createOpenClawTools` 和子工厂的协作模式
3. **TypeBox DSL**：使用TypeBox定义JSON Schema，避免手写原始Schema的错误与冗余
4. **三层序列化管线**：从TypeBox对象到LLM可接受的最终Schema的完整转换流程
5. **工具结果格式**：统一的 `ToolResult` 结构、图片结果、辅助函数

---

## 5.1 工具分类与完整清单

### 5.1.1 分类体系概述

OpenClaw将工具按功能领域分为11个大类，这一分类体系定义在 `tool-policy-shared` 模块的 `CORE_TOOL_DEFINITIONS` 数组中（位于 `/tool-policy-shared-Bana6VmY.js`）：

```javascript
// 源码位置: src/agents/tool-catalog.ts (编译后: tool-policy-shared-Bana6VmY.js)
const CORE_TOOL_SECTION_ORDER = [
    { id: "fs",         label: "Files" },
    { id: "runtime",    label: "Runtime" },
    { id: "web",        label: "Web" },
    { id: "memory",     label: "Memory" },
    { id: "sessions",   label: "Sessions" },
    { id: "ui",         label: "UI" },
    { id: "messaging",  label: "Messaging" },
    { id: "automation", label: "Automation" },
    { id: "nodes",      label: "Nodes" },
    { id: "agents",     label: "Agents" },
    { id: "media",      label: "Media" }
];
```

每个工具的定义包含以下字段：

```javascript
{
    id: "read",                          // 工具唯一标识
    label: "read",                       // 人类可读标签
    description: "Read file contents",   // 简短描述
    sectionId: "fs",                     // 所属UI分区
    profiles: ["coding"],                // 适用的工具配置文件
    includeInOpenClawGroup: true         // 是否包含在 openclaw 分组中
}
```

### 5.1.2 sectionId 与 UI 分组机制

`sectionId` 字段是工具分类的核心，决定了工具在UI界面（如配置面板、工具选择器）中的分组显示。每个 sectionId 对应一个人类友好的 `label`，构成视觉上的分区。

分组还用于策略继承：`CORE_TOOL_GROUPS` 通过 `buildCoreToolGroupMap()` 自动生成 `group:fs`、`group:runtime` 等形式的分组标识，允许策略配置按分组整体授权（如 `allow: ["group:fs"]` 表示允许所有文件系统工具）。

完整的工具分组对照表如下：

| 分类 | sectionId | 中文名称 | UI标签 |
|------|-----------|---------|--------|
| 文件系统 | `fs` | 文件操作 | Files |
| 运行时 | `runtime` | 命令执行 | Runtime |
| 网络 | `web` | 网络访问 | Web |
| 记忆 | `memory` | 语义记忆 | Memory |
| 会话管理 | `sessions` | 会话控制 | Sessions |
| UI控制 | `ui` | 界面交互 | UI |
| 消息 | `messaging` | 消息发送 | Messaging |
| 自动化 | `automation` | 定时与网关 | Automation |
| 节点设备 | `nodes` | 节点管理 | Nodes |
| Agent管理 | `agents` | Agent配置 | Agents |
| 媒体 | `media` | 多媒体处理 | Media |

---

### 5.1.3 第一类：文件系统（sectionId: fs）

文件系统工具是Agent与本地文件交互的基础，包含4个核心工具，均属于 `coding` 配置档案。

#### 5.1.3.1 read — 读取文件

**功能**：读取指定路径的文件内容，支持偏移量和行数限制。

**典型使用场景**：
- 查看源代码文件内容
- 检查配置文件
- 阅读日志输出
- 探索项目结构

**关键参数**：

```javascript
// TypeBox Schema定义 (编译自 @earendil-works/pi-coding-agent 的 createReadTool)
Type.Object({
    path: Type.String({ description: "File path to read" }),
    offset: Type.Optional(Type.Number({ description: "Line number (1-indexed)" })),
    limit: Type.Optional(Type.Number({ description: "Max lines to read" }))
})
```

**执行核心逻辑**（位于 `createOpenClawReadTool`）：

源码中读取工具有三种变体：
1. **Sandbox模式**（`createSandboxedReadTool`）：通过sandbox文件系统桥读取，支持图片内联检测
2. **沙盒工作区只读挂载**：额外的 `readOnlyAgentWorkspaceMount` 挂载点
3. **主机模式**（`createOpenClawReadTool`）：在主机文件系统上使用 workspace root guard 包装

在 `createOpenClawCodingTools` 中（`pi-tools-BdpK2b88.js` 第2031-2051行），读取工具根据 sandbox/host 环境动态选择实现：

```javascript
if (sandboxRoot) {
    const sandboxed = createSandboxedReadTool({
        root: sandboxRoot,
        bridge: sandboxFsBridge,
        modelContextWindowTokens: options?.modelContextWindowTokens,
        imageSanitization
    });
    base.push(workspaceOnly ? wrapToolWorkspaceRootGuardWithOptions(sandboxed, sandboxRoot, {
        additionalContainerMounts: readOnlyAgentWorkspaceMount(sandbox),
        containerWorkdir: sandbox.containerWorkdir
    }) : sandboxed);
} else {
    const wrapped = createOpenClawReadTool(createReadTool(workspaceRoot), {
        modelContextWindowTokens: options?.modelContextWindowTokens,
        imageSanitization
    });
    base.push(workspaceOnly ? wrapToolWorkspaceRootGuardWithOptions(wrapped, workspaceRoot, {
        additionalRoots: skillReadRoots
    }) : wrapped);
}
```

#### 5.1.3.2 write — 写入文件

**功能**：创建新文件或覆盖已有文件。

**典型使用场景**：
- 创建新的源代码文件
- 生成配置文件
- 写入脚本输出
- 持久化处理结果

**实现变体**：
- `createHostWorkspaceWriteTool(workspaceRoot, { workspaceOnly })`：主机写工具
- `createSandboxedWriteTool({ root, bridge })`：沙箱写工具
- `wrapToolMemoryFlushAppendOnlyWrite(tool, options)`：memory flush场景下的只追加写封装

#### 5.1.3.3 edit — 精确编辑

**功能**：对文件进行精确的局部修改，基于 `old_string`/`new_string` 替换模式。

**典型使用场景**：
- 修改函数实现
- 更新配置项
- 修复代码错误
- 局部重构

**实现变体**：
- `createHostWorkspaceEditTool(workspaceRoot, { workspaceOnly })`：主机编辑工具
- `createSandboxedEditTool({ root, bridge })`：沙箱编辑工具

#### 5.1.3.4 apply_patch — 批量补丁

**功能**：通过结构化的patch格式对多个文件进行批量修改（增、删、改）。

**典型使用场景**：
- 应用多文件重构
- 批量创建/修改项目骨架
- `/ask` 风格的多文件操作

**Patch格式标记**（源码 `pi-tools-BdpK2b88.js` 第1178-1188行）：

```javascript
const BEGIN_PATCH_MARKER = "*** Begin Patch";
const END_PATCH_MARKER = "*** End Patch";
const ADD_FILE_MARKER = "*** Add File: ";
const DELETE_FILE_MARKER = "*** Delete File: ";
const UPDATE_FILE_MARKER = "*** Update File: ";
const MOVE_TO_MARKER = "*** Move to: ";
const EOF_MARKER = "*** End of File";
const CHANGE_CONTEXT_MARKER = "@@ ";
```

**Schema定义**：

```javascript
const applyPatchSchema = Type.Object({
    input: Type.String({
        description: "Patch content using the *** Begin Patch/End Patch format."
    })
});
```

**限制条件**（`createOpenClawCodingTools` 第2021-2026行）：`apply_patch` 仅在满足以下全部条件时才注册：
1. `execConfig.applyPatch` 的 `enabled` 不为 `false`
2. 模型provider是OpenAI（`isOpenAIProvider`）
3. 模型ID在 `allowModels` 列表内（如已配置）

---

### 5.1.4 第二类：运行时（sectionId: runtime）

运行时工具提供命令执行和进程管理能力。

#### 5.1.4.1 exec — 执行Shell命令

**功能**：同步/异步执行shell命令，支持后台运行、安全沙箱、命令高亮、超时控制。

**displaySummary**：`"Run shell now."`

**Schema来源**（懒加载 `bash-tools` 模块）：

```javascript
// execSchema 定义在 bash-tools.schemas 模块中
// 核心字段包括: command（必填）, cwd, timeout, env, background 等
```

**懒加载模式**（`createLazyExecTool`，第1783-1805行）：exec工具使用懒加载模式，首次执行时才动态导入bash工具模块：

```javascript
function createLazyExecTool(defaults) {
    let loadedTool;
    const loadTool = async () => {
        if (!loadedTool) {
            const { createExecTool } = await loadBashToolsModule();
            loadedTool = createExecTool(defaults);
        }
        return loadedTool;
    };
    return {
        name: "exec",
        label: "exec",
        displaySummary: EXEC_TOOL_DISPLAY_SUMMARY,
        get description() { return describeExecTool({ agentId, hasCronTool }); },
        parameters: execSchema,
        execute: async (...args) => (await loadTool()).execute(...args)
    };
}
```

**Exec配置解析**（`resolveExecConfig` 第1865-1893行）：exec工具的配置来源融合了全局配置和Agent级配置，包括：
- `host`：执行目标（本地/sandbox/SSH）
- `security`：安全策略
- `ask`：是否需要用户批准
- `safeBins`：安全二进制白名单
- `strictInlineEval`：严格内联求值
- `commandHighlighting`：命令高亮配置
- `backgroundMs`/`timeoutSec`：超时策略
- `safeBinProfiles`：合并的安全二进制配置文件（`resolveMergedSafeBinProfileFixtures`）

#### 5.1.4.2 process — 进程管理

**功能**：检查和控制exec启动的后台进程会话。

**displaySummary**：`"Inspect/control exec sessions."`

**Schema来源**（懒加载）：

```javascript
// processSchema 定义在 bash-tools.schemas 模块中
// 支持 list、kill、send、output 等action
```

**懒加载模式**（`createLazyProcessTool`，第1806-1823行）：与exec相同，process工具也使用懒加载。

**进程范围键**（`resolveProcessToolScopeKey` 第1824-1833行）：进程作用域由优先级链决定：

```javascript
function resolveProcessToolScopeKey(params) {
    const explicitScopeKey = params.scopeKey?.trim();
    if (explicitScopeKey) return explicitScopeKey;    // 1. 显式指定的scopeKey
    const sessionKey = params.sessionKey?.trim();
    if (sessionKey) return sessionKey;                  // 2. 会话Key
    const sessionId = params.sessionId?.trim();
    if (sessionId) return sessionId;                    // 3. 会话ID
    const agentId = params.agentId?.trim();
    return agentId ? `agent:${agentId}` : undefined;    // 4. Agent ID降级
}
```

#### 5.1.4.3 code_execution — 远程代码执行

**功能**：在沙箱化远程环境中执行分析代码。

---

### 5.1.5 第三类：网络（sectionId: web）

#### 5.1.5.1 web_search — 网络搜索

**功能**：使用配置的搜索provider执行网络搜索，返回标准化结果。

**Schema定义**（`openclaw-tools-wLbjLILX.js` 第10858-10917行）：

```javascript
const WebSearchSchema = {
    type: "object",
    required: ["query"],
    properties: {
        query:       { type: "string", description: "Search query." },
        count:       { type: "number", description: "Result count.", minimum: 1, maximum: 10 },
        country:     { type: "string", description: "2-letter country code." },
        language:    { type: "string", description: "ISO 639-1 language." },
        freshness:   { type: "string", description: "Time filter: day/week/month/year." },
        date_after:  { type: "string", description: "Published after YYYY-MM-DD." },
        date_before: { type: "string", description: "Published before YYYY-MM-DD." },
        search_lang: { type: "string", description: "Brave result language." },
        ui_lang:     { type: "string", description: "Brave UI locale." },
        domain_filter: { type: "array", items: { type: "string" },
                         description: "Perplexity domain filter." },
        max_tokens:  { type: "number", description: "Perplexity total token budget.",
                       minimum: 1, maximum: 1e6 },
        max_tokens_per_page: { type: "number", description: "Perplexity tokens per page.",
                               minimum: 1 }
    }
};
```

**注意**：此Schema使用原始JSON Schema对象而非TypeBox定义，这是源码中少数几个直接使用原始Schema的地方。

**运行上下文解析**（`createWebSearchTool` 第10922-10950行）：web_search支持运行时provider切换：

```javascript
function createWebSearchTool(options) {
    if (isWebSearchDisabled(options?.config)) return null;
    return {
        label: "Web Search",
        name: "web_search",
        description: "Search web for current info; returns normalized provider results.",
        parameters: WebSearchSchema,
        execute: async (_toolCallId, args, signal) => {
            const { config, preferRuntimeProviders, runtimeWebSearch } =
                resolveWebSearchToolRuntimeContext({
                    config: options?.config,
                    lateBindRuntimeConfig: options?.lateBindRuntimeConfig,
                    runtimeWebSearch: options?.runtimeWebSearch
                });
            if (isWebSearchDisabled(config)) throw new Error("web_search is disabled.");
            const result = await runWebSearch({
                config, sandboxed: options?.sandboxed,
                runtimeWebSearch, preferRuntimeProviders,
                args: asToolParamsRecord(args), signal
            });
            return jsonResult({ ...result.result, provider: result.provider });
        }
    };
}
```

**managed web search 抑制逻辑**（`applyModelProviderToolPolicy`，第1834-1849行）：当模型提供商（如Codex/Gemini）本身支持原生web搜索时，OpenClaw会抑制自己的web_search工具以避免重复：

```javascript
if (shouldSuppressManagedWebSearchTool({
    config: params?.config,
    modelProvider: params?.modelProvider,
    modelApi: params?.modelApi,
    agentDir: params?.agentDir
})) return tools.filter((tool) => tool.name !== "web_search");
```

#### 5.1.5.2 web_fetch — 网页抓取

**功能**：获取URL内容并提取可读的Markdown/纯文本。轻量级页面访问，不使用浏览器自动化。

**Schema主要字段**：`url`（必填）、`extractMode`（"markdown"/"text"）、`maxChars`。

**执行核心流程**（第10801-10854行）：
1. 解析fetch配置（`resolveFetchConfig`）
2. 检查fetch是否启用（`resolveFetchEnabled`）
3. 确定provider（`providerCacheKey`）
4. 解析readability启用状态和user agent
5. 调用 `runWebFetch` 执行抓取（支持SSRF防护、代理、缓存TTL、重定向控制）
6. 返回 `jsonResult`

**关键配置项**（源码中可见，第10808-10848行）：
- `maxChars`：返回文本的最大字符数
- `maxResponseBytes`：HTTP响应体的最大字节数
- `maxRedirects`：重定向次数限制
- `timeoutSeconds`：请求超时（默认30秒）
- `cacheTtlMs`：缓存TTL（默认15分钟）
- `userAgent`：自定义User-Agent
- `readabilityEnabled`：是否启用可读性提取
- `useTrustedEnvProxy`：是否使用受信环境代理
- `ssrfPolicy`：SSRF防护策略

#### 5.1.5.3 x_search — X平台搜索

**功能**：搜索X（Twitter）平台上的帖子。

---

### 5.1.6 第四类：记忆（sectionId: memory）

#### 5.1.6.1 memory_search — 语义搜索

**功能**：对持久化记忆进行语义搜索。

**描述**：`"Semantic search"`

#### 5.1.6.2 memory_get — 读取记忆

**功能**：读取具体的记忆文件内容。

**描述**：`"Read memory files"`

**测试环境限制**（`tools-invoke-shared-BDAzYswu.js` 第80-91行）：在VITEST环境下，memory工具有特殊的禁用逻辑：

```javascript
if (process.env.VITEST && MEMORY_TOOL_NAMES.has(toolName)) {
    const reasons = resolveMemoryToolDisableReasons(params.cfg);
    if (reasons.length > 0) return {
        ok: false, status: 400, toolName,
        error: { type: "invalid_request",
                 message: `memory tools are disabled in tests (${reasons.join(", ")}).` }
    };
}
```

`MEMORY_TOOL_NAMES` 定义为：`new Set(["memory_search", "memory_get"])`

**Memory Flush特殊处理**（`createOpenClawCodingTools` 第2280-2296行）：当触发器为 `"memory"` 时，只暴露 `read` 和 `write` 工具，且 `write` 被封装为仅追加模式：

```javascript
const toolsForMemoryFlush = isMemoryFlushRun && memoryFlushWritePath ? [] : tools;
if (isMemoryFlushRun && memoryFlushWritePath) for (const tool of tools) {
    if (!MEMORY_FLUSH_ALLOWED_TOOL_NAMES.has(tool.name)) continue;
    if (tool.name === "write") {
        toolsForMemoryFlush.push(wrapToolMemoryFlushAppendOnlyWrite(tool, {
            root: sandboxRoot ?? workspaceRoot,
            relativePath: memoryFlushWritePath,
            // ...
        }));
        continue;
    }
    toolsForMemoryFlush.push(tool);
}
```

---

### 5.1.7 第五类：会话管理（sectionId: sessions）

会话管理类包含7个工具，是工具数量最多的分类。所有工具均属于 `coding` 配置档案，其中 `sessions_list`、`sessions_history`、`sessions_send` 还属于 `messaging` 配置档案。

#### 5.1.7.1 sessions_list — 列出会话

**功能**：列出可见的会话列表，支持过滤（kind、label、agentId、search）、预览和活跃时间筛选。

**displaySummary**：`"List visible sessions; filters/previews."`

**描述**（`describeSessionsListTool`）：
> "List visible sessions; filter by kind, label, agentId, search, activity. Use before sessions_history or sessions_send target selection."

**支持的过滤选项**（`createSessionsListTool` 第8039-8062行）：
- `kinds`：会话种类过滤（main / group / cron / hook / node / other）
- `limit`：最大返回数
- `activeMinutes`：活跃时间过滤
- `label`：标签过滤
- `agentId`：Agent ID过滤
- `search`：搜索文本
- `includeDerivedTitles`：是否包含派生标题
- `includeLastMessage`：是否包含最后一条消息

**可见性守卫**：会话列表通过 `createSessionVisibilityRowChecker` 执行可见性检查，在Agent-to-Agent（A2A）策略下保护跨Agent会话访问。

#### 5.1.7.2 sessions_history — 会话历史

**功能**：获取指定会话的已脱敏历史记录。

**displaySummary**：`"Read sanitized session history."`

**描述**（`describeSessionsHistoryTool`）：
> "Fetch sanitized history for visible session. Use before replying, debugging, resuming; supports limits/tool messages."

#### 5.1.7.3 sessions_send — 发送消息

**功能**：向可见会话发送消息（通过sessionKey/label），或向已配置的Agent发送（通过agentId）。

**displaySummary**：`"Message session or configured agent."`

**描述**（`describeSessionsSendTool`）：
> "Send message to visible session by sessionKey/label, or configured agent by agentId. Thread-scoped chats rejected; target parent channel session. Creates missing configured-agent main session; waits for reply when available."

**特殊限制**：在嵌入模式（embedded mode）下，`sessions_send` 仅在以下情况可见：
- 源回复投递模式为 `"message_tool_only"`
- 通过工厂策略显式允许

#### 5.1.7.4 sessions_spawn — 派生子会话

**功能**：派生子Agent会话或ACP（Agent Communication Protocol）会话。

**displaySummary**：ACP可用时为 `"Spawn subagent or ACP session."`，否则为 `"Spawn subagent session."`

**Schema定义**（`createSessionsSpawnToolSchema` 第8951-8982行）：

```javascript
function createSessionsSpawnToolSchema(params) {
    const spawnModes = params.threadAvailable ? SUBAGENT_SPAWN_MODES : ["run"];
    const schema = {
        task: Type.String(),
        taskName: Type.Optional(Type.String({
            description: "Stable alias for later targeting; "
                + "lowercase letters/digits/underscores, starts letter."
        })),
        label: Type.Optional(Type.String()),
        runtime: optionalStringEnum(
            params.acpAvailable ? SESSIONS_SPAWN_RUNTIMES : ["subagent"]
        ),
        agentId: Type.Optional(Type.String()),
        model: Type.Optional(Type.String()),
        thinking: Type.Optional(Type.String()),
        cwd: Type.Optional(Type.String()),
        runTimeoutSeconds: Type.Optional(Type.Number({ minimum: 0 })),
        timeoutSeconds: Type.Optional(Type.Number({ minimum: 0 })),
        mode: optionalStringEnum(spawnModes),
        cleanup: optionalStringEnum(["delete", "keep"]),
        sandbox: optionalStringEnum(SESSIONS_SPAWN_SANDBOX_MODES),
        context: optionalStringEnum(SUBAGENT_SPAWN_CONTEXT_MODES, {
            description: 'Native context. Omit/"isolated" for clean child; '
                + '"fork" only when child needs requester transcript.'
        }),
        lightContext: Type.Optional(Type.Boolean({
            description: "Light bootstrap context; runtime=\"subagent\" only."
        })),
        attachments: Type.Optional(Type.Array(Type.Object({
            name: Type.String(),
            content: Type.String(),
            encoding: Type.Optional(optionalStringEnum(["utf8", "base64"])),
            mimeType: Type.Optional(Type.String())
        }), { maxItems: 50 })),
        // ... ACP特有字段
    };
    return Type.Object(schema);
}
```

**设计亮点**：
- `taskName` 支持稳定的别名（字母/数字/下划线），便于后续通过别名定位子Agent
- `runtime` 可选 `"subagent"` 或 `"acp"`，ACP提供了更强的隔离性和跨Agent通信
- `mode` 可选 `"run"`（一次性执行）或 `"session"`（持久会话，支持thread绑定）
- `context` 可选 `"fork"`（继承当前transcript）或 `"isolated"`（干净上下文）
- `attachments` 支持最多50个附件，每个附件可指定编码（utf8/base64）和MIME类型

#### 5.1.7.5 sessions_yield — 让出控制权

**功能**：结束当前turn，等待子Agent结果返回。

**displaySummary**：无明确展示概览

**描述**：`"End turn to receive sub-agent results"`

#### 5.1.7.6 subagents — 子Agent管理

**功能**：管理子Agent的生命周期。

**描述**：`"Manage sub-agents"`

#### 5.1.7.7 session_status — 会话状态

**功能**：显示当前/可见会话的状态卡片：模型、用量、时间、费用、任务列表。

**displaySummary**：`"Show session status/model/usage."`

**描述**（`describeSessionStatusTool`）：
> "Show /status-like card for current/visible session: model, usage, time, cost, tasks. Use `sessionKey="current"` for current session; UI labels like `openclaw-tui` are not keys. `model` sets session override; `model=default` resets. Use for active model/session config questions."

**所属配置档案**：minimal、coding、messaging（唯一一个同时属于三个配置档案的工具）

**可见性守卫**（`createSessionStatusTool` 第7501-7509行）：会话状态通过 `createSessionVisibilityGuard` 执行访问控制，支持A2A策略和 `resolveEffectiveSessionToolsVisibility` 的全局可见性配置。

---

### 5.1.8 第六类：UI控制（sectionId: ui）

#### 5.1.8.1 browser — 浏览器控制

**功能**：通过Playwright控制web浏览器执行各种操作。

**支持的Actions**（`browser-tool.schema-Dz1_1VNp.js` 第19-37行）：

```javascript
const BROWSER_TOOL_ACTIONS = [
    "doctor",     // 诊断浏览器状态
    "status",     // 查看浏览器状态
    "start",      // 启动浏览器
    "stop",       // 停止浏览器
    "profiles",   // 管理配置文件
    "tabs",       // 标签页管理
    "open",       // 打开URL
    "focus",      // 聚焦标签页
    "close",      // 关闭标签页
    "snapshot",   // 捕获页面结构快照（aria/ai格式）
    "screenshot", // 截取页面截图
    "navigate",   // 导航
    "console",    // 控制台输出
    "pdf",        // 导出PDF
    "upload",     // 上传文件
    "dialog",     // 对话框交互
    "act"         // 执行交互动作
];
```

**支持的交互动作**（act子命令，`BROWSER_ACT_KINDS` 第5-18行）：

```javascript
const BROWSER_ACT_KINDS = [
    "click", "clickCoords", "type", "press", "hover", "drag",
    "select", "fill", "resize", "wait", "evaluate", "close"
];
```

**执行目标**（`BROWSER_TARGETS`）：
```javascript
const BROWSER_TARGETS = ["sandbox", "host", "node"];
```

支持三种目标：沙箱浏览器、主机浏览器、节点设备上的浏览器。

#### 5.1.8.2 canvas — 画布控制

**功能**：控制节点Canvas表面（呈现/隐藏/导航/eval/快照/A2UI）。使用snapshot捕获渲染的UI。

**支持的Actions**（`tool-CpVHrasV.js` 第71-133行）：

```javascript
switch (action) {
    case "present":     // 呈现Canvas（可选url、placement x/y/width/height）
    case "hide":        // 隐藏Canvas
    case "navigate":    // 导航到URL
    case "eval":        // 在Canvas中执行JavaScript
    case "snapshot":    // 截图（支持png/jpeg，可控制maxWidth/quality）
    case "a2ui_push":   // 推送A2UI JSONL（通过jsonlPath或直接jsonl）
    case "a2ui_reset":  // 重置A2UI
}
```

**设计亮点**：
- **节点路由**：通过 `resolveNodeId` 和 `callGatewayTool("node.invoke", ...)` 将canvas命令路由到指定节点设备
- **幂等性**：每次调用通过 `randomUUID()` 生成 `idempotencyKey`，保证操作幂等
- **快照存储**：截图base64先写入临时文件（路径格式 `openclaw-canvas-snapshot-{uuid}.{ext}`），再通过 `imageResultFromFile` 返回
- **A2UI JSONL安全**：`readJsonlFromPath` 会通过 `fs.realpath` 验证文件确实在workspace内部
- **图片消毒**：snapshot结果经过 `imageSanitization` 处理（最大尺寸限制）

---

### 5.1.9 第七类：消息（sectionId: messaging）

#### 5.1.9.1 message — 发送消息

**功能**：统一的跨渠道消息发送工具。支持多种action：发送消息、回复、编辑、删除、反应（emoji）、读取thread历史等。

**所属配置档案**：`messaging`

**消息工具可见性决策**（`createOpenClawCodingTools` 第11103-11108行）：消息工具是否可见由以下因素决定：

```javascript
const includeMessageTool = !embedded
    || options?.sourceReplyDeliveryMode === "message_tool_only"
    || messageExplicitlyAllowed;
```

- 非嵌入模式：始终可见
- 嵌入模式 + `message_tool_only`：可见
- 嵌入模式 + 显式allowlist：可见
- 其他嵌入模式：隐藏

**强制消息工具场景**（第1950-1955行）：以下情况会强制添加message工具到运行时allowlist：

```javascript
const runtimeProfileAlsoAllow = [
    ...options?.forceMessageTool || options?.sourceReplyDeliveryMode === "message_tool_only"
        ? ["message"] : [],
    ...runtimeToolAllowlistIncludesMessage ? ["message"] : [],
    ...forceHeartbeatTool ? [HEARTBEAT_RESPONSE_TOOL_NAME] : [],
    ...toolSearchControlAllowlist
];
```

---

### 5.1.10 第八类：自动化（sectionId: automation）

#### 5.1.10.1 heartbeat_respond — 心跳响应

**功能**：记录心跳检测的结果。支持 `notify=false`（无可见发送）和 `notify=true`（需要简洁的notificationText）。

**描述**（`createHeartbeatResponseTool` 第2959行）：
> "Record heartbeat result. `notify=false` no visible send. `notify=true` needs concise notificationText."

**启用条件**（`createOpenClawCodingTools` 第1935-1936行）：
```javascript
const enableHeartbeatTool = options?.enableHeartbeatTool === true
    || options?.trigger === "heartbeat"
        && options?.config?.messages?.visibleReplies === "message_tool";
const forceHeartbeatTool = options?.forceHeartbeatTool === true || enableHeartbeatTool;
```

#### 5.1.10.2 cron — 定时任务

**功能**：管理Gateway的cron定时任务和唤醒事件：提醒、稍后检查、延迟跟进、定期重复工作。

**displaySummary**：`"Schedule reminders, cron, wake events."`

**描述**（第2284行）：
> "Manage Gateway cron jobs and wake events: reminders, check-back-later, delayed follow-ups, recurring work. Do not emulate scheduling with exec sleep/process polling."

**Schema结构**（第2160-2180行）包含以下核心部分：

```javascript
const CronToolSchema = Type.Object({
    // 列表/读取操作
    gatewayUrl: Type.Optional(Type.String()),
    gatewayToken: Type.Optional(Type.String()),
    timeoutMs: Type.Optional(Type.Number()),
    includeDisabled: Type.Optional(Type.Boolean()),

    // 创建/更新操作
    jobId: Type.Optional(Type.String()),
    id: Type.Optional(Type.String()),
    // CronJobObjectSchema（创建）或 CronPatchObjectSchema（更新）
    // CronJobObjectSchema 包含:
    //   name, schedule (at/everyMs/expr/tz/staggerMs),
    //   payload (kind=systemEvent/agentTurn, text/message/model/thinking),
    //   delivery (mode=message/reply, channel/to/threadId),
    //   failureAlert (after/channel/to/cooldownMs),
    //   sessionTarget, wakeMode, description, deleteAfterRun

    text: Type.Optional(Type.String()),
    contextMessages: Type.Optional(Type.Number({ minimum: 0, maximum: 100 })),
    agentId: Type.Optional(Type.String({ description: "List filter: agent id" }))

**Payload类型**（`cronPayloadObjectSchema` 第2081-2093行）：
- `kind`：systemEvent / agentTurn
- `text`：systemEvent的文本内容
- `message`：agentTurn的提示词
- `model`：模型覆盖
- `thinking`：thinking覆盖
- `timeoutSeconds`：超时
- `lightContext`：轻量上下文
- `fallbacks`：降级模型列表

**调度类型**（`CronScheduleSchema` 第2094-2101行）：
- `kind="at"`：ISO-8601绝对时间
- `kind="every"`：固定间隔（毫秒）+ 锚点时间
- `kind="cron"`：cron表达式（按tz时区的墙上时钟时间，不转换UTC）+ staggerMs抖动

**时区说明**：cron表达式中的重要注释（第2099行）：
> "Cron expr in tz wall-clock time; do not convert to UTC. Omitted tz => Gateway host local timezone. Example 6pm Shanghai daily: expr \"0 18 * * *\", tz \"Asia/Shanghai\"."

#### 5.1.10.3 gateway — 网关控制

**功能**：Gateway重启、配置更新。支持 `config.schema.lookup`（点路径查询schema）、`config.patch`（部分合并）、`config.apply`（全量替换）。

**描述**（第2798行）：
> "Gateway restart/config/update. Before config edits, use config.schema.lookup with targeted dot path. Prefer config.patch for partial merge; config.apply only full replace. Writes hot-reload or restart as needed. Always pass human `note` for post-restart delivery. If still owe the user a reply, pass one-shot `continuationMessage`; do not write restart sentinel files directly."

---

### 5.1.11 第九类：节点设备（sectionId: nodes）

#### 5.1.11.1 nodes — 节点与设备管理

**功能**：管理连接的节点和设备。

**描述**：`"Nodes + devices"`

**工作区守卫**（`createOpenClawTools` 第11084-11098行）：nodes工具在创建后通过 `applyNodesToolWorkspaceGuard` 包装，注入工作区策略和sandbox信息：

```javascript
const nodesTool = applyNodesToolWorkspaceGuard(createNodesTool({
    agentSessionKey: options?.agentSessionKey,
    agentChannel: options?.agentChannel,
    agentAccountId: options?.agentAccountId,
    currentChannelId: options?.currentChannelId,
    currentThreadTs: options?.currentThreadTs,
    config: options?.config,
    modelHasVision: options?.modelHasVision,
    allowMediaInvokeCommands: options?.allowMediaInvokeCommands
}), {
    fsPolicy: options?.fsPolicy,
    sandboxContainerWorkdir: options?.sandboxContainerWorkdir,
    sandboxRoot: options?.sandboxRoot,
    workspaceDir
});
```

---

### 5.1.12 第十类：Agent管理（sectionId: agents）

#### 5.1.12.1 agents_list — Agent列表

**功能**：列出允许 `sessions_spawn runtime="subagent"` 的Agent ID。

**描述**（第1559行）：
> "List agent ids allowed for `sessions_spawn runtime=\"subagent\"`."

**Schema**（第1554行）：
```javascript
const AgentsListToolSchema = Type.Object({});
```
不需要任何参数，直接返回已配置的Agent列表及名称映射。

#### 5.1.12.2 update_plan — 更新执行计划

**功能**：更新当前run的执行计划。用于非平凡的多步骤工作，在执行过程中保持计划最新。

**displaySummary**：`"Track short work plan."`

**描述**（`describeUpdatePlanTool`）：
> "Update current run plan. Use for non-trivial multi-step work; keep plan current while executing. Short steps; max one `in_progress`; skip for simple one-step work."

**执行特点**（`createUpdatePlanTool` 第9463-9483行）：update_plan是唯一返回 `content: []` 的工具——它不产生任何文本输出，仅在 `details` 中记录计划变更：

```javascript
return {
    content: [],
    details: {
        status: "updated",
        ...explanation ? { explanation } : {},
        plan
    }
};
```

**启用条件**（`createOpenClawTools` 第11111-11121行）：

```javascript
const includeUpdatePlanTool = isToolExplicitlyAllowedByFactoryPolicy({
    toolName: "update_plan",
    allowlist: explicitFactoryAllowlist,
    denylist: explicitFactoryDenylist
}) || isUpdatePlanToolEnabledForOpenClawTools({
    config: resolvedConfig,
    agentSessionKey: options?.agentSessionKey,
    agentId: options?.requesterAgentIdOverride,
    modelProvider: options?.modelProvider,
    modelId: options?.modelId
});
```

---

### 5.1.13 第十一类：媒体（sectionId: media）

媒体类是功能最丰富的分类，涵盖图像、音乐、视频和语音。

#### 5.1.13.1 image — 图像理解

**功能**：使用视觉模型分析图像。支持单路径/URL（`image`参数）或批量（`images`参数，最多20个）。

**描述动态生成**（第4693行）：根据模型能力动态选择描述：
- 有Vision能力：`"Analyze images with vision model. Use image for one path/URL, images for max 20. Only use this tool when images were NOT already provided; prompt images already visible."`
- 有显式图像模型配置：`"Analyze images with configured image model..."`
- 降级描述：`"Analyze images with available vision model..."`

**Schema定义**（第4694-4701行）：

```javascript
Type.Object({
    prompt: Type.Optional(Type.String()),
    image: Type.Optional(Type.String({ description: "One image path/URL." })),
    images: Type.Optional(Type.Array(Type.String(), {
        description: "Image paths/URLs; maxImages default 20."
    })),
    model: Type.Optional(Type.String()),
    maxBytesMb: Type.Optional(Type.Number()),
    maxImages: Type.Optional(Type.Number())
})
```

**图像加载流程**（第4738-4797行）：
1. 解析图像引用（支持 `@` 前缀引用、file:// URL、data: URL、http(s) URL、相对路径）
2. 分类引用来源（`classifyMediaReferenceSource`）
3. 根据环境（sandbox/host）选择解析路径
4. 加载媒体（`loadWebMedia` / `decodeDataUrl` / sandbox bridge）
5. 处理 `@` 前缀的去引用
6. 验证MIME类型
7. 调用 `runImagePrompt` 执行视觉模型推理

#### 5.1.13.2 image_generate — 图像生成

**功能**：创建/编辑图像。后台异步任务模式：调用后不要重复调用；等待完成后通过message工具发送附件。

**描述**（第4269行）：
> "Create/edit images. Session chats: background task; do not call image_generate again for same request; wait completion, then send attachments via message tool. Transparent: outputFormat=\"png\" or \"webp\" + background=\"transparent\"; OpenAI also supports openai.background and routes default model to gpt-image-1.5. Use action=\"list\" for providers/models/readiness/auth, \"status\" for active task."

**支持的Actions**：
- `action="generate"`（默认）：创建图像
- `action="list"`：列出可用的provider/模型/就绪状态/认证
- `action="status"`：查询活动任务状态

**去重保护**（`createImageGenerateDuplicateGuardResult`）：防止同一会话中的重复生成请求。

**SSRF防护**（第4288行）：
```javascript
const remoteMediaSsrfPolicy = resolveRemoteMediaSsrfPolicy(effectiveCfg);
```
加载参考图像时应用 `remoteMediaSsrfPolicy` 防止SSRF攻击。

#### 5.1.13.3 music_generate — 音乐生成

**功能**：生成音乐/音频。同样采用后台异步任务模式。

**描述**：与image_generate类似的后台任务模式，完成后通过message工具发送。

#### 5.1.13.4 video_generate — 视频生成

**功能**：生成视频。同样采用后台异步任务模式。

**特殊参数**：包含 `audioReferences`（音频参考）能力检测。

#### 5.1.13.5 tts — 文本转语音

**功能**：将文本转换为语音输出。

**渠道限制**（`pi-tools-BdpK2b88.js` 第1670-1694行中的 `TOOL_DENY_BY_MESSAGE_PROVIDER`）：tts在特定消息provider下被禁用：

```javascript
const TOOL_DENY_BY_MESSAGE_PROVIDER = {
    "discord-voice": ["tts"],
    voice: ["tts"]
};
```

语音渠道禁止tts（避免语音嵌套），而node渠道明确允许tts：

```javascript
const TOOL_ALLOW_BY_MESSAGE_PROVIDER = {
    node: ["canvas", "image", "pdf", "tts", "web_fetch", "web_search"]
};
```

---

## 5.2 工厂函数模式

OpenClaw的工具创建采用了多层工厂函数嵌套的设计模式。顶层工厂负责协调全局配置和子工厂调用，每个子工厂负责创建单一工具。

### 5.2.1 顶层工厂：createOpenClawTools

`createOpenClawTools(options)` 是整个OpenClaw工具系统的入口，负责创建所有"OpenClaw级别"的工具（区别于pi-coding-agent的基础编程工具）。

**完整执行流程**（源码 `openclaw-tools-wLbjLILX.js` 第10952-11240行）：

```javascript
function createOpenClawTools(options) {
    // === 阶段1：解析配置和运行时快照 ===
    const resolvedConfig = options?.config ?? openClawToolsDeps.config;
    const runtimeSnapshot = getActiveSecretsRuntimeSnapshot();
    const availabilityConfig = selectApplicableRuntimeConfig({
        inputConfig: resolvedConfig,
        runtimeConfig: runtimeSnapshot?.config,
        runtimeSourceConfig: runtimeSnapshot?.sourceConfig
    });

    // === 阶段2：确定会话Agent和工作目录 ===
    const { sessionAgentId } = resolveSessionAgentIds({
        sessionKey: options?.agentSessionKey,
        config: resolvedConfig,
        agentId: options?.requesterAgentIdOverride
    });
    const workspaceDir = resolveWorkspaceRoot(options?.workspaceDir ?? inferredWorkspaceDir);

    // === 阶段3：解析媒体工具工厂计划 ===
    const optionalMediaTools = resolveOptionalMediaToolFactoryPlan({
        config: availabilityConfig ?? resolvedConfig,
        workspaceDir,
        authStore: options?.authProfileStore,
        toolAllowlist: options?.pluginToolAllowlist,
        toolDenylist: options?.pluginToolDenylist
    });

    // === 阶段4：调用各子工厂创建工具 ===
    const imageTool = resolveImageToolFactoryAvailable(...)
        ? createImageTool({ config, agentDir, ... }) : null;
    const imageGenerateTool = optionalMediaTools.imageGenerate
        ? createImageGenerateTool({ config, ... }) : null;
    const videoGenerateTool = optionalMediaTools.videoGenerate
        ? createVideoGenerateTool({ config, ... }) : null;
    const musicGenerateTool = optionalMediaTools.musicGenerate
        ? createMusicGenerateTool({ config, ... }) : null;
    const webSearchTool = createWebSearchTool({ config, ... });
    const webFetchTool = createWebFetchTool({ config, ... });
    const messageTool = options?.disableMessageTool ? null
        : createMessageTool({ config, ... });
    const nodesTool = applyNodesToolWorkspaceGuard(
        createNodesTool({ config, ... }), { fsPolicy, ... });
    const heartbeatTool = options?.enableHeartbeatTool
        ? createHeartbeatResponseTool() : null;

    // === 阶段5：收集所有工具 ===
    const tools = [
        ...embedded ? [] : [nodesTool, createCronTool({...})],
        ...messageTool && includeMessageTool ? [messageTool] : [],
        ...collectPresentOpenClawTools([heartbeatTool]),
        createTtsTool({...}),
        ...collectPresentOpenClawTools([imageGenerateTool, musicGenerateTool,
                                         videoGenerateTool]),
        ...embedded ? [] : [createGatewayTool({...})],
        createAgentsListTool({...}),
        ...includeUpdatePlanTool ? [createUpdatePlanTool()] : [],
        createSessionsListTool({...}), createSessionsHistoryTool({...}),
        ...embedded ? [] : [createSessionsSendTool({...})],
        ...includeSubagentSpawnTool ? [createSessionsSpawnTool({...})] : [],
        createSessionsYieldTool({...}),
        createSubagentsTool({...}),
        createSessionStatusTool({...}),
        ...collectPresentOpenClawTools([webSearchTool, webFetchTool,
                                         imageTool, pdfTool])
    ];

    // === 阶段6：合并Plugin工具 ===
    if (!options?.disablePluginTools) {
        allTools = [...tools, ...resolveOpenClawPluginToolsForOptions({
            options, resolvedConfig, existingToolNames
        })];
    }

    // === 阶段7：包裹before_tool_call钩子 ===
    if (options?.wrapBeforeToolCallHook === false) return allTools;
    const hookContext = { agentId, config, sessionKey, sessionId, channelId,
                          loopDetection, ... };
    return allTools.map((tool) =>
        isToolWrappedWithBeforeToolCallHook(tool) ? tool
            : wrapToolWithBeforeToolCallHook(tool, hookContext)
    );
}
```

### 5.2.2 更高级别的编排：createOpenClawCodingTools

`createOpenClawCodingTools` 是比 `createOpenClawTools` 更高一层的编排函数，负责组合基础编程工具（`@earendil-works/pi-coding-agent`）和OpenClaw工具。这是最终暴露给Agent运行时的工具集合。

**流程概览**（`pi-tools-BdpK2b88.js` 第1895-2381行）：

```
createOpenClawCodingTools(options)
  |
  |-- 1. 解析工具策略（多层策略融合）
  |     - profile/global/agent/group/sender/sandbox/subagent/inherited
  |     - 共11层策略按优先级叠加
  |
  |-- 2. 创建基础编程工具 (createCodingTools)
  |     - read / write / edit (通过 createCodingTools API)
  |     - 根据 sandbox/host 环境选择对应实现
  |
  |-- 3. 创建执行工具 (exec/process/apply_patch)
  |     - exec: lazy load bash-tools 模块
  |     - process: lazy load 进程管理
  |     - apply_patch: 条件性创建（OpenAI+白名单模型）
  |
  |-- 4. 创建OpenClaw工具 (createOpenClawTools)
  |     - 所有web/session/message/media等工具
  |
  |-- 5. 创建Tool Search工具
  |     - tool_search_code / tool_search / tool_describe / tool_call
  |
  |-- 6. 多层策略过滤管线
  |     - Memory flush过滤
  |     - Message provider过滤
  |     - Model provider过滤
  |     - Owner-only策略
  |     - 多级allow/deny管线（8步pipeline）
  |
  |-- 7. Schema规范化 (normalizeToolParameters)
  |     - TypeBox → JSON Schema 转换
  |     - Provider特定清理
  |
  |-- 8. 包裹钩子和Abort信号
  |     - before_tool_call hook
  |     - AbortSignal wrapper
  |     - 延迟followup描述更新
```

**多层策略融合的关键代码**（第1902-1990行）：

```javascript
// 11个策略源按顺序融合
const { agentId, globalPolicy, globalProviderPolicy,
        agentPolicy, agentProviderPolicy, profile, providerProfile,
        profileAlsoAllow, providerProfileAlsoAllow }
    = resolveEffectiveToolPolicy({ config, sessionKey, agentId,
        modelProvider, modelId });

const sandboxToolPolicy = sandbox?.tools;
const groupPolicy = resolveGroupToolPolicy({ config, messageProvider, ... });
const senderPolicy = resolveSenderToolPolicy({ config, agentId, ... });
const subagentPolicy = resolveSubagentToolPolicyForSession(...);
const inheritedToolPolicy = resolveInheritedToolPolicyForSession(...);

// 策略管线按顺序应用
const subagentFiltered = applyToolPolicyPipeline({
    tools, toolMeta, warn, steps: [
        // Step 1: profilePolicy
        // Step 2: providerProfilePolicy
        // Step 3: globalPolicy
        // Step 4: globalProviderPolicy
        // Step 5: agentPolicy
        // Step 6: agentProviderPolicy
        // Step 7: groupPolicy
        // Step 8: senderPolicy
        // Step 9: sandboxToolPolicy
        // Step 10: subagentPolicy
        // Step 11: inheritedToolPolicy
    ]
});
```

### 5.2.3 子工厂模式

每个工具的子工厂都返回一个标准化的工具对象。所有工具的"形状"统一为以下接口：

```javascript
{
    label: string,              // 人类可读标签，用于UI显示
    name: string,               // 内部工具名称，LLM通过此名称调用
    description: string,        // LLM可读的描述，被注入到system prompt
    displaySummary?: string,    // 简短的UI展示概览（可选）
    parameters: TSchema,        // TypeBox JSON Schema对象
    execute: (toolCallId, args, signal?, onUpdate?) => Promise<ToolResult>
}
```

**字段说明**：

| 字段 | 类型 | 必须 | 说明 |
|------|------|------|------|
| `label` | `string` | 是 | 面向人类的标签，如"Web Search"、"Read" |
| `name` | `string` | 是 | 工具内部名称，如"web_search"、"read"，是LLM调用的标识符 |
| `description` | `string` | 是 | 工具的详细描述，被注入到模型的system prompt中指导模型何时使用 |
| `displaySummary` | `string` | 否 | 简短的UI展示文本，如exec的"Run shell now." |
| `parameters` | `TSchema` | 是 | TypeBox Schema对象，描述工具的输入参数 |
| `execute` | `Function` | 是 | 工具的执行函数，接受toolCallId、参数、AbortSignal和进度回调 |

**具体示例1：createHeartbeatResponseTool（最简工具）**

```javascript
function createHeartbeatResponseTool() {
    return {
        label: "Heartbeat",
        name: HEARTBEAT_RESPONSE_TOOL_NAME,
        description: "Record heartbeat result. `notify=false` no visible send. "
            + "`notify=true` needs concise notificationText.",
        parameters: HeartbeatResponseToolSchema,
        execute: async (_toolCallId, args) => { /* ... */ }
    };
}
```

**具体示例2：createWebSearchTool（带条件创建）**

```javascript
function createWebSearchTool(options) {
    if (isWebSearchDisabled(options?.config)) return null;  // 条件性返回null
    return {
        label: "Web Search",
        name: "web_search",
        description: "Search web for current info; returns normalized provider results.",
        parameters: WebSearchSchema,
        execute: async (_toolCallId, args, signal) => {
            // 运行时上下文解析
            const { config, preferRuntimeProviders, runtimeWebSearch } =
                resolveWebSearchToolRuntimeContext({ ... });
            // 实际执行
            const result = await runWebSearch({ ... });
            return jsonResult({ ...result.result, provider: result.provider });
        }
    };
}
```

**具体示例3：createImageTool（复杂工具，含条件描述）**

```javascript
function createImageTool(options) {
    // 动态描述
    const description = options?.modelHasVision
        ? "Analyze images with vision model. ..."
        : explicitImageModelConfig
            ? "Analyze images with configured image model. ..."
            : "Analyze images with available vision model. ...";

    return {
        label: "Image",
        name: "image",
        description,
        parameters: Type.Object({
            prompt: Type.Optional(Type.String()),
            image: Type.Optional(Type.String({ description: "One image path/URL." })),
            images: Type.Optional(Type.Array(Type.String(),
                { description: "Image paths/URLs; maxImages default 20." })),
            model: Type.Optional(Type.String()),
            maxBytesMb: Type.Optional(Type.Number()),
            maxImages: Type.Optional(Type.Number())
        }),
        execute: async (_toolCallId, args) => { /* 复杂的图像加载和推理 */ }
    };
}
```

**懒加载工厂示例**：exec和process工具使用getter模式实现懒加载：

```javascript
function createLazyExecTool(defaults) {
    let loadedTool;
    return {
        name: "exec",
        label: "exec",
        displaySummary: EXEC_TOOL_DISPLAY_SUMMARY,
        get description() {
            return describeExecTool({ agentId: defaults?.agentId,
                hasCronTool: defaults?.hasCronTool === true });
        },
        parameters: execSchema,    // 静态schema（无需加载模块）
        execute: async (...args) => (await loadTool()).execute(...args)
    };
}
```

这里的 `description` 使用了getter属性，每次访问时动态计算（基于当前是否有cron工具），而 `execute` 在首次调用时懒加载完整的bash工具模块。

---

## 5.3 TypeBox DSL

### 5.3.1 为什么选择TypeBox

OpenClaw大规模使用[TypeBox](https://github.com/sinclairzx81/typebox)来定义工具参数Schema，而非手写原始JSON Schema对象。原因如下：

1. **类型安全**：TypeBox在TypeScript/JavaScript中提供编译时类型检查，避免拼写错误和结构错误
2. **链式组合**：可以像搭积木一样组合Schema（`Type.Object`、`Type.Array`、`Type.Optional`等），避免深嵌套的原始JSON
3. **代码即文档**：TypeBox的定义本身即是可读的文档，参数的 `description` 被注入到LLM的system prompt中
4. **统一到原始Schema**：TypeBox定义了 `.toString()` 和序列化方法，可以无痛转为标准JSON Schema
5. **复用能力**：提取通用的Schema片段（如 `optionalStringEnum` 辅助函数），避免重复定义

### 5.3.2 基本用法

```javascript
import { Type } from "typebox";

// 基本类型
Type.String({ description: "A string field" })
Type.Number({ minimum: 0, maximum: 100 })
Type.Boolean()

// 可选字段
Type.Optional(Type.String())
Type.Optional(Type.Number({ description: "Optional number" }))

// 对象
Type.Object({
    name: Type.String(),
    age: Type.Optional(Type.Number({ minimum: 0 }))
})

// 数组
Type.Array(Type.String())
Type.Array(Type.Object({ key: Type.String() }), { maxItems: 50 })

// 联合类型
Type.Union([Type.String(), Type.Number()])

// 枚举（使用辅助函数 stringEnum / optionalStringEnum）
stringEnum(["click", "type", "press", "hover"])
optionalStringEnum(["delete", "keep"], { description: "Cleanup mode" })

// 记录类型
Type.Record(Type.String(), Type.Unknown())

// Unsafe（自由格式）
Type.Unsafe({ description: "Arbitrary object" })
```

### 5.3.3 实际Schema示例

**示例1：read工具Schema**

```javascript
const ReadToolSchema = Type.Object({
    path: Type.String({ description: "File path to read" }),
    offset: Type.Optional(Type.Number({ description: "Line number (1-indexed)" })),
    limit: Type.Optional(Type.Number({ description: "Max lines to read" }))
});
```

**示例2：write工具Schema**

```javascript
const WriteToolSchema = Type.Object({
    path: Type.String({ description: "File path to write" }),
    content: Type.String({ description: "File content" })
});
```

**示例3：edit工具Schema**

```javascript
const EditToolSchema = Type.Object({
    path: Type.String({ description: "File path to edit" }),
    edits: Type.Array(Type.Object({
        old_string: Type.String({ description: "Text to find and replace" }),
        new_string: Type.String({ description: "Replacement text" })
    }))
});
```

**示例4：sessions_spawn工具Schema**

```javascript
function createSessionsSpawnToolSchema(params) {
    return Type.Object({
        task: Type.String(),
        taskName: Type.Optional(Type.String({
            description: "Stable alias for later targeting; "
                + "lowercase letters/digits/underscores, starts letter."
        })),
        runtime: optionalStringEnum(
            params.acpAvailable ? SESSIONS_SPAWN_RUNTIMES : ["subagent"]
        ),
        mode: optionalStringEnum(params.threadAvailable
            ? SUBAGENT_SPAWN_MODES : ["run"]),
        context: optionalStringEnum(SUBAGENT_SPAWN_CONTEXT_MODES, {
            description: 'Omit/"isolated" for clean child; '
                + '"fork" only when child needs requester transcript.'
        }),
        attachments: Type.Optional(Type.Array(Type.Object({
            name: Type.String(),
            content: Type.String(),
            encoding: Type.Optional(optionalStringEnum(["utf8", "base64"])),
            mimeType: Type.Optional(Type.String())
        }), { maxItems: 50 })),
        // ... 更多字段
    });
}
```

**示例5：Browser工具Schema（带Enum和Act子Schema）**

```javascript
const BrowserActSchema = Type.Object({
    kind: stringEnum(BROWSER_ACT_KINDS),  // click/type/press/hover等12种
    targetId: Type.Optional(Type.String()),
    ref: Type.Optional(Type.String()),
    doubleClick: Type.Optional(Type.Boolean()),
    button: Type.Optional(Type.String()),
    modifiers: Type.Optional(Type.Array(Type.String())),
    x: Type.Optional(Type.Number()),
    y: Type.Optional(Type.Number()),
    text: Type.Optional(Type.String()),
    submit: Type.Optional(Type.Boolean()),
    slowly: Type.Optional(Type.Boolean()),
    key: Type.Optional(Type.String()),
    delayMs: Type.Optional(Type.Number()),
    // ... 更多act参数
});

const BrowserToolSchema = Type.Object({
    action: stringEnum(BROWSER_TOOL_ACTIONS),  // doctor/status/start/stop等17种
    target: optionalStringEnum(BROWSER_TARGETS),  // sandbox/host/node
    node: Type.Optional(Type.String()),
    // ... 更多字段
});
```

**示例6：image工具Schema**

```javascript
Type.Object({
    prompt: Type.Optional(Type.String()),
    image: Type.Optional(Type.String({ description: "One image path/URL." })),
    images: Type.Optional(Type.Array(Type.String(), {
        description: "Image paths/URLs; maxImages default 20."
    })),
    model: Type.Optional(Type.String()),
    maxBytesMb: Type.Optional(Type.Number()),
    maxImages: Type.Optional(Type.Number())
})
```

---

## 5.4 三层序列化管线

TypeBox Schema不能直接发送给LLM——LLM只接受普通JSON Schema。OpenClaw实现了一个三层序列化管线，将TypeBox对象逐步转换为各模型提供商可接受的最终形态。

### 5.4.1 管线总览

```
Layer 1: TypeBox Schema → 原生JavaScript对象
           (TypeBox内置序列化)
              |
              v
Layer 2: normalizeToolParameterSchema
         ├─ inlineLocalToolSchemaRefs     → 内联 $ref 引用
         ├─ normalizeArraySchemasMissingItems → 补齐缺失的 items
         ├─ stripEmptyArrayItemsFromArraySchemas → 清理空items
         ├─ cleanSchemaForGemini          → 移除Gemini不支持的keywords
         └─ stripUnsupportedSchemaKeywords → 通用keyword清理
              |
              v
Layer 3: normalizeAgentRuntimeTools / normalizeProviderToolSchemas
         ├─ 委托给Provider Plugin的 normalizeToolSchemas hook
         ├─ 限制参数数量
         └─ 移除不支持的keywords
```

### 5.4.2 Layer 1：TypeBox → 原生JavaScript对象

TypeBox自身提供了将Schema对象序列化为标准JSON Schema的能力。当TypeBox Schema对象被传给 `normalizeToolParameterSchema` 或最终被编码为JSON发送给LLM时，TypeBox的默认JSON序列化会自动将其转为普通JSON Schema对象。

这一层对开发者是透明的，但理解它很重要——`Type.Object({...})` 返回的是一个TypeBox内部对象，不是普通JSON，但可以被序列化。

### 5.4.3 Layer 2：normalizeToolParameterSchema

`normalizeToolParameterSchema` 是整个规范化管线的核心函数，定义在 `/pi-tools-parameter-schema-DDIhQRGt.js` 中。

**完整流程**（第295-363行）：

```javascript
function normalizeToolParameterSchema(schema, options) {
    // === 步骤1: $ref 内联化 ===
    const inlinedSchema = inlineLocalToolSchemaRefs(schema);

    // === 步骤2: 确定Provider ===
    const normalizedProvider = normalizeLowercaseStringOrEmpty(options?.modelProvider);
    const isGeminiProvider = normalizedProvider.includes("google")
                          || normalizedProvider.includes("gemini");
    const isAnthropicProvider = normalizedProvider.includes("anthropic");

    // === 步骤3: 获取模型兼容性配置 ===
    const unsupportedToolSchemaKeywords =
        resolveUnsupportedToolSchemaKeywords(options?.modelCompat);
    const omitEmptyArrayItems = shouldOmitEmptyArrayItems(options?.modelCompat);

    // === 步骤4: 定义Provider清理函数 ===
    function applyProviderCleaning(s) {
        const normalizedSchema = normalizeArraySchemasMissingItems(s);
        const arrayItemsCompatibleSchema = omitEmptyArrayItems
            ? stripEmptyArrayItemsFromArraySchemas(normalizedSchema)
            : normalizedSchema;
        if (isGeminiProvider && !isAnthropicProvider)
            return cleanSchemaForGemini(arrayItemsCompatibleSchema);
        if (unsupportedToolSchemaKeywords.size > 0)
            return stripUnsupportedSchemaKeywords(
                arrayItemsCompatibleSchema, unsupportedToolSchemaKeywords);
        return arrayItemsCompatibleSchema;
    }

    // === 步骤5: Schema形状修复 ===
    // 5a: 标准object schema → 直接清理
    if (hasTopLevelObjectSchema(schemaRecord, conditionalKey))
        return applyProviderCleaning(schemaRecord);

    // 5b: 缺少type的对象 → 补齐type: "object"
    if (isObjectLikeSchemaMissingType(schemaRecord, conditionalKey))
        return applyProviderCleaning({
            ...schemaRecord,
            type: "object",
            properties: isSchemaRecord(schemaRecord.properties)
                ? schemaRecord.properties : {}
        });

    // 5c: type为object但无properties → 补齐空properties
    if (isTypedObjectSchemaMissingValidProperties(schemaRecord, conditionalKey))
        return applyProviderCleaning({ ...schemaRecord, properties: {} });

    // 5d: anyOf/oneOf扁平化 → 合并properties和required
    if (flattenableVariantKey) {
        // 遍历anyOf/oneOf的每个变体
        // 合并properties（mergePropertySchemas处理enum合并）
        // 计算required的交集（在所有变体中都出现的required字段）
        // 生成扁平化的type: "object" schema
        return applyProviderCleaning({
            type: "object",
            title, description,
            properties: mergedProperties,
            required: mergedRequired,
            additionalProperties
        });
    }

    // 5e: 完全空Schema → 降级为 { type: "object", properties: {} }
    return applyProviderCleaning({ type: "object", properties: {} });
}
```

#### 5.4.3.1 $ref 内联化（inlineLocalToolSchemaRefs）

`$ref` 引用在TypeBox中常用于复用Schema片段，但LLM通常不支持 `$ref`。`inlineLocalToolSchemaRefs` 通过DFS递归遍历整个Schema树，将所有 `#/$defs/...` 或 `#/definitions/...` 引用内联展开：

```javascript
function inlineLocalToolSchemaRefs(schema) {
    if (!schema || typeof schema !== "object") return schema;
    return inlineLocalSchemaRefsWithDefs(
        schema,
        extendSchemaDefs(undefined, schema),
        undefined,
        { unresolvedLocalRefs: false }
    );
}

function inlineLocalSchemaRefsWithDefs(schema, defs, refStack, state) {
    // 如果是 $ref → 解析并递归内联
    const refValue = typeof obj.$ref === "string" ? obj.$ref : undefined;
    if (refValue) {
        if (refStack?.has(refValue)) return {};  // 循环引用保护
        const resolved = tryResolveLocalRef(refValue, nextDefs);
        if (resolved === undefined) {
            if (refValue.startsWith("#/")) state.unresolvedLocalRefs = true;
            return { ...obj };
        }
        const nextRefStack = new Set(refStack);
        nextRefStack.add(refValue);
        const inlined = inlineLocalSchemaRefsWithDefs(resolved, nextDefs,
                                                       nextRefStack, state);
        const result = { ...inlined };
        copySchemaMeta(obj, result);  // 保留 title/description/default
        return result;
    }
    // 否则 → 递归处理所有子属性
    const result = {};
    for (const [key, value] of Object.entries(obj)) {
        if (key === "$defs" || key === "definitions") continue;
        result[key] = inlineLocalSchemaRefsWithDefs(value, nextDefs,
                                                     refStack, state);
    }
    return result;
}
```

**JSON Pointer解析**（第229-258行）：支持JSON Pointer路径，如 `#/$defs/address/street`：

```javascript
function resolveJsonPointerPath(value, segments) {
    let current = value;
    for (const segment of segments) {
        const key = decodeJsonPointerSegment(segment);  // ~0 → ~, ~1 → /
        if (Array.isArray(current)) {
            const index = Number(key);
            if (!Number.isInteger(index) || index < 0 || index >= current.length)
                return;
            current = current[index];
        } else {
            if (!Object.prototype.hasOwnProperty.call(current, key)) return;
            current = current[key];
        }
    }
    return current;
}
```

#### 5.4.3.2 cleanSchemaForGemini

Gemini对JSON Schema的限制非常严格（位于 `provider-tools-CWUkDwCA.js` 第2-250行）。不被支持的keywords包括：

```javascript
const GEMINI_UNSUPPORTED_SCHEMA_KEYWORDS = new Set([
    "patternProperties", "additionalProperties",
    "$schema", "$id", "$ref", "$defs", "definitions",
    "examples",
    "minLength", "maxLength", "minimum", "maximum",
    "multipleOf", "pattern", "format",
    "minItems", "maxItems", "uniqueItems",
    "minProperties", "maxProperties",
    "not"
]);
```

**处理策略**：
1. **$ref**：解析并内联引用（如果不可解析则返回空对象+元数据）
2. **const**：转换为 `{ enum: [value] }`
3. **anyOf/oneOf**：尝试通过 `simplifyUnionVariants` 简化
   - `stripNullVariants`：移除 `{ type: "null" }` 或 `{ const: null }` 变体
   - `tryFlattenLiteralAnyOf`：将纯字面量anyOf合并为单个enum
   - 如果简化后只剩一个变体，直接返回该变体
4. **type去null**：`["string", "null"]` → `"string"`
5. **空required**：移除空的required数组
6. **type/"null"**：从type数组中过滤掉
7. **无法简化的anyOf/oneOf**：通过 `flattenUnionFallback` 降级处理
   - 同类type → 只保留type
   - 否则 → 取第一个变体的type
8. **递归清理**：遍历 `properties`、`items`、`anyOf`、`oneOf`、`allOf`

#### 5.4.3.3 stripUnsupportedSchemaKeywords

通用keyword移除函数（第253-275行），移除指定的不支持Schema关键字：

```javascript
function stripUnsupportedSchemaKeywords(schema, unsupportedKeywords) {
    if (!schema || typeof schema !== "object") return schema;
    const obj = schema;
    const cleaned = {};
    for (const [key, value] of Object.entries(obj)) {
        if (unsupportedKeywords.has(key)) continue;
        if (key === "properties" && value && typeof value === "object")
            cleaned[key] = Object.fromEntries(
                Object.entries(value).map(([k, v]) =>
                    [k, stripUnsupportedSchemaKeywords(v, unsupportedKeywords)])
            );
        else if (key === "items" && value && typeof value === "object")
            cleaned[key] = Array.isArray(value)
                ? value.map(entry =>
                    stripUnsupportedSchemaKeywords(entry, unsupportedKeywords))
                : stripUnsupportedSchemaKeywords(value, unsupportedKeywords);
        else if (["anyOf","oneOf","allOf"].includes(key) && Array.isArray(value))
            cleaned[key] = value.map(entry =>
                stripUnsupportedSchemaKeywords(entry, unsupportedKeywords));
        else cleaned[key] = value;
    }
    return cleaned;
}
```

#### 5.4.3.4 Schema形状修复

**补全缺失的type字段**：如果Schema有properties或required但没有type字段，自动添加 `type: "object"`：

```javascript
if (isObjectLikeSchemaMissingType(schemaRecord, conditionalKey))
    return applyProviderCleaning({
        ...schemaRecord,
        type: "object",
        properties: isSchemaRecord(schemaRecord.properties)
            ? schemaRecord.properties : {}
    });
```

**补全缺失的properties**：如果Schema有 `type: "object"` 但缺乏有效的properties，补全空对象：

```javascript
if (isTypedObjectSchemaMissingValidProperties(schemaRecord, conditionalKey))
    return applyProviderCleaning({ ...schemaRecord, properties: {} });
```

**完全空Schema降级**：一个没有任何字段的Schema被降级为标准空对象Schema：

```javascript
if (isTrulyEmpty(schemaRecord))
    return applyProviderCleaning({ type: "object", properties: {} });
```

**anyOf/oneOf扁平化**：这是最复杂的转换。当顶层Schema使用 `anyOf` 或 `oneOf` 描述多个变体时（例如"field可以是string或number"），系统会：

1. 遍历所有变体，收集properties
2. 对同名property进行合并（`mergePropertySchemas`）
   - 合并enum值集合
   - 统一type（如果所有值类型相同）
   - 保留第一个源的 `title`/`description`/`default`
3. 计算required交集：仅在**所有**变体中都标记为required的字段才设为required
4. 生成扁平化的单一 `type: "object"` Schema

**数组Schema修复**（`normalizeArraySchemasMissingItems`，第76-136行）：对于 `type: "array"` 但缺少 `items` 定义的Schema，自动添加 `items: {}`。递归处理嵌套的 `items`、`contains`、`additionalProperties`、`not`、`if/then/else`、`anyOf`/`oneOf`/`allOf`、`prefixItems`、`properties`、`patternProperties`、`dependentSchemas` 等。

**空数组items清理**（`stripEmptyArrayItemsFromArraySchemas`，第166-206行）：对于某些模型兼容性配置，移除 `items: {}`（完全空对象的items）：

```javascript
function stripEmptyArrayItemsFromArraySchemas(schema) {
    // ...
    if (key === "items" && schemaAllowsArrayType(schema)
        && isSchemaRecord(value) && isTrulyEmptySchema(value)) {
        changed = true;
        return [];  // 跳过此key
    }
    // ...
}
```

### 5.4.4 Layer 3：normalizeAgentRuntimeTools

顶层规范化函数负责将Layer 2处理后的Schema进一步适配特定Provider的要求。这一层通过委托Provider Plugin实现：

1. **参数数量限制**：某些Provider对工具参数数量有硬性限制（如限制properties数量）
2. **Provider Plugin Hook**：调用Provider Plugin的 `normalizeToolSchemas` 钩子，允许插件做最终Schema转换
3. **Gemini特殊处理**（第290-297行）：
   ```javascript
   function normalizeGeminiToolSchemas(ctx) {
       return ctx.tools.map((tool) => {
           if (!tool.parameters || typeof tool.parameters !== "object")
               return tool;
           return { ...tool, parameters: cleanSchemaForGemini(tool.parameters) };
       });
   }
   ```

**完整调用链**（`pi-tools-BdpK2b88.js` 中的 `createOpenClawCodingTools` 第2350-2354行）：

```javascript
const normalized = subagentFiltered.map((tool) =>
    normalizeToolParameters(tool, {
        modelProvider: options?.modelProvider,
        modelId: options?.modelId,
        modelCompat: options?.modelCompat
    })
);
```

`normalizeToolParameters` 封装了Layer 2的调用：

```javascript
function normalizeToolParameters(tool, options) {
    const schema = tool.parameters && typeof tool.parameters === "object"
        ? tool.parameters : undefined;
    if (!schema) return tool;
    const parameters = normalizeToolParameterSchema(schema, options);
    return preserveToolMeta({
        ...tool,
        ...addEmptyObjectArgumentPreparation(tool, parameters),
        parameters
    });
}
```

**空对象参数准备**（`addEmptyObjectArgumentPreparation`，第1728-1737行）：对于无必填字段的object schema，自动添加参数预备处理，确保 `null`/`undefined` 参数被转为空对象 `{}`：

```javascript
function addEmptyObjectArgumentPreparation(tool, parameters) {
    if (!isObjectSchemaWithNoRequiredParams(parameters)) return tool;
    return {
        ...tool,
        prepareArguments: (args) => {
            const prepared = tool.prepareArguments
                ? tool.prepareArguments(args) : args;
            return prepared === null || prepared === undefined ? {} : prepared;
        }
    };
}
```

---

## 5.5 工具结果格式

### 5.5.1 标准ToolResult结构

所有工具执行后返回统一的 `ToolResult` 格式：

```javascript
{
    content: [
        { type: "text", text: "..." },     // 文本内容块
        // 或
        { type: "image", data: "<base64>", mimeType: "image/png" }  // 图像块
    ],
    details: {
        // 结构化的执行详情，用于日志/UI展示/后续处理
    }
}
```

**设计原则**：
- `content` 数组中的每个块都包含一个 `type` 字段（`"text"` 或 `"image"`），模型可以直接消费
- `details` 包含结构化的元数据，供中间件、UI和日志使用
- 两者分离：content面向LLM，details面向系统

### 5.5.2 辅助函数

**buildTextToolResult / textResult**（`common-Co_iMEX5.js` 第147-154行）：

```javascript
function textResult(text, details) {
    return {
        content: [{ type: "text", text }],
        details
    };
}
```

**jsonResult**（第162-164行）：

```javascript
function jsonResult(payload) {
    return textResult(JSON.stringify(payload, null, 2), payload);
}
```

`jsonResult` 是使用最广泛的辅助函数——它将任意对象序列化为格式化的JSON字符串作为text content，同时保留原始对象在details中：

```javascript
// 典型用法
execute: async (_toolCallId, args) => jsonResult(await runtime.search(query));
```

**imageResult**（第174-195行）：构建包含图像content块的结果：

```javascript
async function imageResult(params) {
    const content = [
        ...params.extraText ? [{ type: "text", text: params.extraText }] : [],
        { type: "image", data: params.base64, mimeType: params.mimeType }
    ];
    return await sanitizeToolResultImages({
        content,
        details: {
            path: params.path,
            ...params.details,
            media: { ...detailsMedia, mediaUrl: params.path }
        }
    }, params.label, params.imageSanitization);
}
```

**imageResultFromFile**（第196-208行）：从文件读取图像并构建结果：

```javascript
async function imageResultFromFile(params) {
    const buf = (await readLocalFileSafely({ filePath: params.path })).buffer;
    const mimeType = await detectMime({ buffer: buf.slice(0, 256) })
                  ?? "image/png";
    return await imageResult({
        label: params.label,
        path: params.path,
        base64: buf.toString("base64"),
        mimeType,
        extraText: params.extraText,
        details: params.details,
        imageSanitization: params.imageSanitization
    });
}
```

**buildMediaGenerationStartedToolResult**：媒体生成（image_generate、music_generate、video_generate）启动后返回的结果，包含任务句柄和状态信息。

**buildBlockedToolResult**：当工具因权限或策略被阻止时的返回结果。

**payloadTextResult**（第159-161行）：将payload序列化为text并保留在details中：

```javascript
function payloadTextResult(payload) {
    return textResult(stringifyToolPayload(payload), payload);
}
```

### 5.5.3 图片消毒（sanitizeToolResultImages）

所有包含图片content的结果都经过 `sanitizeToolResultImages` 处理（定义在 `tool-images-CM7YOmsC.js`），接受 `imageSanitization` 配置应用限制：
- `maxDimensionPx`：最大像素尺寸
- 其他安全限制

### 5.5.4 apply_patch 的结果格式

`apply_patch` 返回特殊的结构化摘要（第1213-1220行）：

```javascript
return {
    content: [{ type: "text", text: result.text }],  // "Success. Updated...\nA ...\nM ..."
    details: {
        summary: {
            added: ["file1.txt"],
            modified: ["file2.txt"],
            deleted: ["file3.txt"]
        }
    }
};
```

### 5.5.5 update_plan 的空结果

`update_plan` 是唯一返回空 `content` 的工具（第9474-9481行）：

```javascript
return {
    content: [],          // 不产生文本输出
    details: {
        status: "updated",
        ...explanation ? { explanation } : {},
        plan  // 结构化的计划步骤
    }
};
```

### 5.5.6 错误处理

工具通过抛出异常来报告错误，异常被框架捕获并转换为错误格式的tool result：

```javascript
var ToolInputError = class extends Error {
    constructor(message) {
        super(message);
        this.status = 400;
        this.name = "ToolInputError";
    }
};

var ToolAuthorizationError = class extends ToolInputError {
    constructor(message) {
        super(message);
        this.status = 403;
        this.name = "ToolAuthorizationError";
    }
};
```

`ToolInputError` (status 400) 用于参数错误，`ToolAuthorizationError` (status 403) 用于权限拒绝。

**Owner-only工具错误**（`OWNER_ONLY_TOOL_ERROR`）：
```javascript
const OWNER_ONLY_TOOL_ERROR = "Tool restricted to owner senders.";
```

通过 `wrapOwnerOnlyToolExecution` 函数包装非owner发送者的调用：

```javascript
function wrapOwnerOnlyToolExecution(tool, senderIsOwner) {
    if (tool.ownerOnly !== true || senderIsOwner || !tool.execute) return tool;
    return {
        ...tool,
        execute: async () => { throw new Error(OWNER_ONLY_TOOL_ERROR); }
    };
}
```

---

## 5.6 总结

本章全面分析了OpenClaw工具系统的上半部分，涵盖以下核心要点：

1. **11大工具分类**按功能领域（文件、运行时、网络、记忆、会话、UI、消息、自动化、节点、Agent、媒体）组织，通过 `sectionId` 字段实现UI分组和策略继承。共40+个核心工具，每个工具都有明确的职责、限制和启用条件。

2. **工厂函数模式**采用"顶层编排（`createOpenClawCodingTools`）→ OpenClaw工厂（`createOpenClawTools`）→ 子工厂（如 `createWebSearchTool`）"的三层结构。子工厂返回统一的接口 `{ label, name, description, displaySummary, parameters, execute }`。懒加载模式减少了模块初始化开销。

3. **TypeBox DSL**为工具Schema定义提供了类型安全的声明式API。`Type.Object`、`Type.Optional`、`Type.Array`、`stringEnum` 等组合子显著优于手写原始JSON Schema，且代码即文档，description被直接注入LLM system prompt。

4. **三层序列化管线**确保TypeBox Schema能无损转换为各Provider可接受的JSON Schema。$ref内联化消除了Schema复用带来的兼容性问题，Gemini专用清理处理了20+个不支持的keywords，anyOf/oneOf扁平化将多态Schema归一化为LLM能理解的形式。

5. **统一的ToolResult格式**通过 `content` + `details` 分离设计，同时满足LLM消费和系统处理的需求。丰富的辅助函数（`jsonResult`、`imageResult`、`textResult`等）减少了重复代码。

下一章将深入探讨工具的运行时执行、策略评估、权限控制、before_tool_call钩子系统、以及Tool Search Catalog机制。
# 五、Agent工具系统实现（下）：策略管道、循环检测与安全机制

## 概述

OpenClaw的工具系统不仅仅是将一组功能暴露给AI Agent使用，它通过一套精密的多层策略管道、实时循环检测引擎和纵深安全机制，确保Agent在受控、安全、可审计的环境中运行。本章深入分析这三个核心子系统的源代码实现，揭示其架构设计、关键数据结构和运行机制。

---

## 5.1 十一层工具策略管道

工具策略管道的核心职责是：**在Agent调用工具之前，判定该工具是否被允许使用**。OpenClaw将其构建为一个**分层叠加、逐级过滤**的管道模型，共11层，每一层都可以独立配置allow/deny规则。管道按优先级从高到低逐层应用，deny优先于allow，最后一层生效的策略决定工具的最终可用性。

### 5.1.1 管道层级全景

```
Global tools config (全局工具配置层)
  ├── 第1层: tools.profile         (工具简档: minimal / coding / messaging / full)
  ├── 第2层: tools.alsoAllow       (附加工具白名单, 与profile合并)
  ├── 第3层: tools.allow           (全局allow列表)
  ├── 第4层: tools.deny            (全局deny列表 — 最高优先级)
  ├── 第5层: tools.byProvider      (按模型提供商区分的工具策略)
  ├── 第6层: tools.toolsBySender   (按发送者区分的工具策略)
  └── Agent级工具配置层
       ├── 第7层: agent.tools.profile      (Agent级别工具简档)
       ├── 第8层: agent.tools.allow/deny  (Agent级别allow/deny)
       ├── 第9层: agent.tools.byProvider  (Agent级别按提供商策略)
       ├── 第10层: agent.tools.subagents.tools (子Agent工具策略)
       └── 第11层: agent.tools.sandbox.tools   (沙盒工具策略)
```

### 5.1.2 核心规则：deny永远优先于allow

在`tool-policy-match-Cclzh8C-.js`中，策略匹配器的实现清晰地体现了这一点：

```javascript
function makeToolPolicyMatcher(policy) {
    const deny = compileGlobPatterns({
        raw: expandToolGroups(policy.deny ?? []),
        normalize: normalizeToolName
    });
    const allow = compileGlobPatterns({
        raw: expandToolGroups(policy.allow ?? []),
        normalize: normalizeToolName
    });
    return (name) => {
        const normalized = normalizeToolName(name);
        if (matchesAnyGlobPattern(normalized, deny)) return false;  // deny优先
        if (allow.length === 0) return true;                         // 空allow=全部允许
        if (matchesAnyGlobPattern(normalized, allow)) return true;   // 匹配allow=允许
        if (normalized === "apply_patch" && matchesAnyGlobPattern("write", allow))
            return true;  // apply_patch 继承 write 的权限
        return false;                                               // 默认拒绝
    };
}
```

关键逻辑解析：
1. **先检查deny列表**：无论allow列表如何配置，只要工具名匹配deny中的任一glob模式，直接返回`false`（拒绝）。
2. **空allow = 全通过**：当allow列表为空时，所有未被deny的工具都允许。
3. **`apply_patch`继承`write`权限**：`apply_patch`工具在底层会修改文件，因此在策略上自动继承`write`的allow权限。
4. **默认拒绝**：如果既不在deny中也不在allow中，则拒绝——遵循最小权限原则。

### 5.1.3 四种工具简档（Tool Profiles）

在`tool-policy-shared-Bana6VmY.js`中，定义了四种内建的工具简档，每种简档精确控制Agent可用的核心工具集合：

```javascript
const CORE_TOOL_PROFILES = {
    minimal:   { allow: listCoreToolIdsForProfile("minimal") },
    coding:    { allow: [...listCoreToolIdsForProfile("coding"), "bundle-mcp"] },
    messaging: { allow: [...listCoreToolIdsForProfile("messaging"), "bundle-mcp"] },
    full:      { allow: ["*"] }
};
```

#### minimal简档（仅session_status）
最严格的安全配置，仅允许以下工具：

| 工具ID | 所属分组 | 描述 |
|--------|---------|------|
| `session_status` | sessions | 显示当前会话状态/模型/用量 |

这意味着使用minimal简档的Agent**不能读写文件、不能执行命令、不能搜索网页、不能管理会话**。它几乎是一个"只读观察者"角色，适合不需要任何实际操作的纯对话场景。

#### coding简档（files + runtime + network + memory + sessions + update_plan + media）

这是最常用的开发Agent标准配置，包含以下工具类别：

**Files（文件操作）**: `read`, `write`, `edit`, `apply_patch`
**Runtime（运行时）**: `exec`, `process`, `code_execution`
**Web（网络）**: `web_search`, `web_fetch`, `x_search`
**Memory（记忆）**: `memory_search`, `memory_get`
**Sessions（会话管理）**: `sessions_list`, `sessions_history`, `sessions_send`, `sessions_spawn`, `sessions_yield`, `subagents`, `session_status`
**Automation（自动化）**: `cron`
**Agents**: `update_plan`
**Media（媒体）**: `image`, `image_generate`, `music_generate`, `video_generate`
**额外**: `bundle-mcp`

#### messaging简档（消息与会话通信）
专注于消息传递场景，包含：

- `message` — 发送消息
- `sessions_list`, `sessions_history`, `sessions_send` — 会话查询与通信
- `session_status` — 会话状态
- `bundle-mcp` — MCP捆绑工具

messaging简档不包含任何文件操作、命令执行或网络搜索工具，适合纯通信类Agent。

#### full简档（所有工具）
`allow: ["*"]` 通配符匹配所有工具。这提供最大灵活性，但也意味着安全风险最高，通常只用于受信任的管理场景。

### 5.1.4 管道执行的完整流程

在`effective-tool-policy-N-bAXOsL.js`中，`applyFinalEffectiveToolPolicy`函数是策略管道的编排者：

```javascript
function applyFinalEffectiveToolPolicy(params) {
    // 1. 解析所有策略源
    const { agentId, globalPolicy, globalProviderPolicy, agentPolicy,
            agentProviderPolicy, profile, providerProfile,
            profileAlsoAllow, providerProfileAlsoAllow }
        = resolveEffectiveToolPolicy({ config, sessionKey, agentId,
            modelProvider, modelId });

    // 2. 解析分组策略和发送者策略
    const groupPolicy = resolveGroupToolPolicy({...});
    const senderPolicy = resolveSenderToolPolicy({...});

    // 3. 解析子Agent和继承策略
    const subagentPolicy = ...;
    const inheritedToolPolicy = ...;

    // 4. 构建11层管道步骤
    const pipelineSteps = [
        ...buildDefaultToolPolicyPipelineSteps({...}),
        { policy: params.sandboxToolPolicy, label: "sandbox tools.allow" },
        { policy: subagentPolicy,          label: "subagent tools.allow" },
        { policy: inheritedToolPolicy,     label: "inherited tools" }
    ];

    // 5. 应用管道
    return applyToolPolicyPipeline({ tools, toolMeta, warn, steps: pipelineSteps });
}
```

在`tool-policy-pipeline-BiNLZkR6.js`中，`applyToolPolicyPipeline`函数逐层应用策略：

```javascript
function applyToolPolicyPipeline(params) {
    let filtered = params.tools;
    for (const step of params.steps) {
        if (!step.policy) continue;
        // 展开插件组引用（如 group:plugins）
        const expanded = expandPolicyWithPluginGroups(policy, pluginGroups);
        // 应用当前层的过滤
        filtered = expanded ? filterToolsByPolicy(filtered, expanded) : filtered;
    }
    return filtered;
}
```

管道还包含智能的**未知条目警告机制**：当allow列表包含不存在的工具名或核心工具ID但在当前运行时不可用时，系统会发出警告，帮助运维人员发现配置错误。

### 5.1.5 策略解析的优先级与来源

`resolveEffectiveToolPolicy`函数展示了策略的优先级来源：

1. **Agent级别优先于全局**：`agentTools?.profile ?? globalTools?.profile`
2. **providerProfle的优先级**：`agentProviderPolicy?.profile ?? providerPolicy?.profile`
3. **alsoAllow合并**：Agent级和全局级的alsoAllow都会被收集并去重合并

特别地，系统还会检测**隐式授权冲突**：如果用户配置了`tools.exec`或`tools.fs`的详细设置，但使用了一个限制性的profile（如minimal），系统会发出警告，提示这些详细配置不会因为profile的存在而自动生效，需要显式添加`alsoAllow`。

---

## 5.2 工具调用生命周期

每个工具调用的完整生命周期包括四个阶段：

```
创建 → 策略检查 → BeforeHook → 执行 → 结果
```

### 5.2.1 wrapToolWithBeforeToolCallHook 包装器

在`pi-tools.before-tool-call-CPvWc0ME.js`中，所有工具在接入Agent运行时都会被`wrapToolWithBeforeToolCallHook`包装。这个包装器是在工具注册到Agent时调用的（位于`openclaw-tools-wLbjLILX.js`第11239行）：

```javascript
// 所有工具统一包装
return allTools.map((tool) => isToolWrappedWithBeforeToolCallHook(tool)
    ? tool
    : wrapToolWithBeforeToolCallHook(tool, hookContext));
```

> **注意**：使用Symbol标记`BEFORE_TOOL_CALL_WRAPPED`防止重复包装：
> ```javascript
> Object.defineProperty(wrappedTool, BEFORE_TOOL_CALL_WRAPPED, {
>     value: true, enumerable: true
> });
> ```

### 5.2.2 包装器的执行流程

```javascript
function wrapToolWithBeforeToolCallHook(tool, ctx) {
    const execute = tool.execute;
    const toolName = tool.name || "tool";
    const wrappedTool = {
        ...tool,
        execute: async (toolCallId, params, signal, onUpdate) => {
            // ===== 阶段1: 运行before钩子 =====
            const outcome = await runBeforeToolCallHook({
                toolName, params, toolCallId, ctx, signal
            });

            // ===== 阶段2: 处理阻塞结果 =====
            if (outcome.blocked) {
                if (outcome.kind !== "veto") throw new Error(outcome.reason);
                // 发送诊断事件
                emitTrustedDiagnosticEvent({ type: "tool.execution.blocked", ... });
                // 记录循环检测结果
                await recordLoopOutcome({ ctx, toolName, toolParams, toolCallId, result: blockedResult });
                return buildBlockedToolResult({...});
            }

            // ===== 阶段3: 发送"开始执行"诊断事件 =====
            emitTrustedDiagnosticEvent({ type: "tool.execution.started", ... });

            // ===== 阶段4: 执行原始工具逻辑 =====
            const startedAt = Date.now();
            try {
                const result = await execute(toolCallId, outcome.params, signal, onUpdate);
                const durationMs = Date.now() - startedAt;
                await recordLoopOutcome({ ctx, toolName, toolParams, toolCallId, result });
                emitTrustedDiagnosticEvent({ type: "tool.execution.completed", ... });
                return result;
            } catch (err) {
                // ===== 阶段4b: 错误处理 =====
                emitTrustedDiagnosticEvent({ type: "tool.execution.error", ... });
                await recordLoopOutcome({ ctx, toolName, toolParams, toolCallId, error: err });
                throw err;
            }
        }
    };
    // 复制插件元数据和通道元数据
    copyPluginToolMeta(tool, wrappedTool);
    copyChannelAgentToolMeta(tool, wrappedTool);
    return wrappedTool;
}
```

### 5.2.3 runBeforeToolCallHook 详细子流程

`runBeforeToolCallHook`是before钩子的核心，按以下顺序执行四个独立的检查模块：

```
runBeforeToolCallHook(args)
  │
  ├── 1. 工具循环检测 (detectToolCallLoop)
  │     - 检查未知工具重复调用 (unknown_tool_repeat)
  │     - 检查全局熔断器 (global_circuit_breaker)
  │     - 检查已知轮询无进展 (known_poll_no_progress)
  │     - 检查乒乓循环 (ping_pong)
  │     - 检查通用重复 (generic_repeat)
  │     - 如果是 critical 级别 → 直接返回 {blocked: true}
  │     - 如果是 warning 级别 → 发出警告但继续
  │     - 记录工具调用到历史 (recordToolCall)
  │
  ├── 2. 受信插件策略 (runTrustedToolPolicies)
  │     - 遍历所有已注册的 trustedToolPolicies
  │     - 每个策略可以: 拒绝(block)、修改参数(params)、请求审批(requireApproval)
  │     - 如果被拒绝 → 返回 blocked
  │     - 如果需要审批 → 进入审批流程
  │
  ├── 3. before_tool_call 钩子 (hookRunner.runBeforeToolCall)
  │     - 运行所有注册的 before_tool_call 全局钩子
  │     - 钩子可以: 拒绝、修改参数、请求审批
  │     - 同样支持审批模式检测
  │
  └── 4. 审批模式检查
        - 如果 approvalMode === "report" 且钩子请求审批
        - → 直接拒绝（report模式下不允许审批交互）
```

### 5.2.4 插件审批流程

当受信策略或钩子请求审批时，系统通过Gateway进入两阶段审批流程：

```
requestPluginToolApproval(params)
  │
  ├── 阶段1: 发送审批请求
  │     - 调用 callGatewayTool("plugin.approval.request", ...)
  │     - 超时时间: (审批超时 + 10s)
  │     - 支持两阶段审批 (twoPhase: true)
  │
  ├── 阶段2a: 如果有立即决策
  │     - 检查 requestResult.decision
  │     - null → 审批路由不可用，返回 blocked
  │     - ALLOW_ONCE / ALLOW_ALWAYS → 允许执行
  │     - DENY → 拒绝
  │
  ├── 阶段2b: 等待用户决策
  │     - 调用 callGatewayTool("plugin.approval.waitDecision", ...)
  │     - 支持 AbortSignal 取消（run abort 时触发）
  │     - 超时行为: 默认 deny，可配置为 allow
  │
  └── 回调: safeOnResolution
        - 调用审批提供方的 onResolution 回调
        - 回调失败不影响主流程
```

### 5.2.5 诊断事件体系

在整个工具生命周期中，系统发出三种诊断事件：

| 事件类型 | 触发时机 | 关键字段 |
|---------|---------|---------|
| `tool.execution.blocked` | 工具被拒绝时 | reason, deniedReason, paramsSummary |
| `tool.execution.started` | 工具开始执行时 | toolName, paramsSummary |
| `tool.execution.completed` | 工具执行成功时 | durationMs |
| `tool.execution.error` | 工具执行失败时 | errorCategory, errorCode, durationMs |

---

## 5.3 工具循环检测系统

工具循环检测系统是整个工具安全框架的"免疫系统"，它持续监控Agent的工具调用模式，在检测到异常重复、死循环或无进展行为时及时介入，防止资源浪费和系统崩溃。

### 5.3.1 默认配置

在`tool-loop-detection-jwWWnVz-.js`中定义了完整的默认配置：

```javascript
const DEFAULT_LOOP_DETECTION_CONFIG = {
    enabled: false,                          // 默认关闭，需显式开启
    historySize: 30,                          // 滑动窗口：保留最近30次工具调用
    warningThreshold: 10,                     // 警告阈值：10次重复
    unknownToolThreshold: 10,                 // 未知工具阈值：10次
    criticalThreshold: 20,                    // 严重阈值：20次重复
    globalCircuitBreakerThreshold: 30,        // 全局熔断器：30次无进展
    detectors: {
        genericRepeat: true,                  // 通用重复检测器
        knownPollNoProgress: true,            // 轮询无进展检测器
        pingPong: true                       // 乒乓检测器
    }
};
```

**配置解析中的阈值强制约束**：

```javascript
function resolveLoopDetectionConfig(config) {
    let warningThreshold = asPositiveInt(config?.warningThreshold, 10);
    let criticalThreshold = asPositiveInt(config?.criticalThreshold, 20);
    let globalCircuitBreakerThreshold = asPositiveInt(
        config?.globalCircuitBreakerThreshold, 30);
    // 确保阈值严格递增
    if (criticalThreshold <= warningThreshold)
        criticalThreshold = warningThreshold + 1;
    if (globalCircuitBreakerThreshold <= criticalThreshold)
        globalCircuitBreakerThreshold = criticalThreshold + 1;
    // ...
}
```

`asPositiveInt`函数确保传入值必须是正整数，否则回退到默认值。这防止了无效配置（如负数阈值）导致的安全漏洞。

### 5.3.2 工具调用哈希算法

循环检测的核心是对每次工具调用生成唯一的标识哈希，包含工具名和参数的摘要：

```javascript
function hashToolCall(toolName, params) {
    return `${toolName}:${digestStable(params)}`;
}

function digestStable(value) {
    const serialized = stableStringify(value);
    return createHash("sha256").update(serialized).digest("hex");
}
```

`stableStringify`确保JSON对象的键按字典序排列，保证相同语义的参数在不同序列化顺序下产生相同的哈希。SHA-256提供足够的哈希空间来避免碰撞。

### 5.3.3 四个核心检测器详解

#### 检测器1: genericRepeat（通用重复检测）

**核心函数**: `getNoProgressStreak`

```javascript
function getNoProgressStreak(history, toolName, argsHash) {
    let streak = 0;
    let latestResultHash;
    for (let i = history.length - 1; i >= 0; i -= 1) {
        const record = history[i];
        if (!record || record.toolName !== toolName
            || record.argsHash !== argsHash) continue;
        if (typeof record.resultHash !== "string" || !record.resultHash) continue;
        if (!latestResultHash) {
            latestResultHash = record.resultHash;
            streak = 1;
            continue;
        }
        if (record.resultHash !== latestResultHash) break;
        streak += 1;
    }
    return { count: streak, latestResultHash };
}
```

**工作原理**：
1. 从历史记录末尾反向遍历
2. 只统计工具名相同**且**参数哈希相同的记录
3. 比较每次调用的**结果哈希**：只有当连续多次调用的结果完全相同时，streak才递增
4. 一旦发现某次调用的结果与之前不同（说明产生了进展），立即停止计数
5. 忽略没有结果哈希的记录（可能还在执行中）

**触发条件**：
- 非轮询工具 + `noProgressStreak >= criticalThreshold(20)` → critical级别，阻止执行
- 非轮询工具 + `recentCount >= warningThreshold(10)` → warning级别

**critical消息示例**：
```
CRITICAL: Called <toolName> with identical arguments and identical outcomes
20 times. Session execution blocked to prevent runaway loops.
```

**warning消息示例**：
```
WARNING: You have called <toolName> 10 times with identical arguments.
If this is not making progress, stop retrying and report the task as failed.
```

#### 检测器2: knownPollNoProgress（轮询无进展检测）

**识别轮询工具**: `isKnownPollToolCall`函数

```javascript
function isKnownPollToolCall(toolName, params) {
    if (toolName === "command_status") return true;  // 命令状态查询
    if (toolName !== "process" || !isPlainObject(params)) return false;
    const action = params.action;
    return action === "poll" || action === "log";     // process的poll/log操作
}
```

**特殊处理**：
- `command_status`工具：专门用于查询长时间运行命令的状态
- `process`工具 + `action=poll`：轮询进程状态
- `process`工具 + `action=log`：轮询进程日志

轮询工具使用与`genericRepeat`相同的`getNoProgressStreak`函数进行检测，但**阈值更低**（因为轮询是预期行为，不应频繁触发）：

- `noProgressStreak >= criticalThreshold(20)` → critical级别
- `noProgressStreak >= warningThreshold(10)` → warning级别

**critical消息示例**：
```
CRITICAL: Called <toolName> with identical arguments and no progress
20 times. This appears to be a stuck polling loop.
Session execution blocked to prevent resource waste.
```

**warning消息对轮询工具的特别建议**：
```
WARNING: You have called <toolName> 10 times with identical arguments
and no progress. Stop polling and either (1) increase wait time between
checks, or (2) report the task as failed if the process is stuck.
```

#### 检测器3: pingPong（乒乓循环检测）

这是最复杂的一个检测器，用于检测两个工具交替调用形成死循环的模式（A→B→A→B→...）。

**核心函数**: `getPingPongStreak`

完整源码分析：

```javascript
function getPingPongStreak(history, currentSignature) {
    const last = history.at(-1);
    if (!last) return { count: 0, noProgressEvidence: false };

    // 第一步：找到配对工具
    let otherSignature;
    let otherToolName;
    for (let i = history.length - 2; i >= 0; i -= 1) {
        const call = history[i];
        if (!call) continue;
        if (call.argsHash !== last.argsHash) {
            otherSignature = call.argsHash;
            otherToolName = call.toolName;
            break;
        }
    }

    if (!otherSignature || !otherToolName)
        return { count: 0, noProgressEvidence: false };

    // 第二步：计算交替模式的长度
    let alternatingTailCount = 0;
    for (let i = history.length - 1; i >= 0; i -= 1) {
        const call = history[i];
        if (!call) continue;
        const expected = alternatingTailCount % 2 === 0
            ? last.argsHash : otherSignature;
        if (call.argsHash !== expected) break;
        alternatingTailCount += 1;
    }

    if (alternatingTailCount < 2)
        return { count: 0, noProgressEvidence: false };

    // 第三步：验证当前调用是交替模式的一部分
    if (currentSignature !== otherSignature)
        return { count: 0, noProgressEvidence: false };

    // 第四步：验证交替双方都没有产生进展
    // (双方各自的结果哈希在所有交替轮次中保持不变)
    let firstHashA, firstHashB;
    let noProgressEvidence = true;
    const tailStart = Math.max(0, history.length - alternatingTailCount);
    for (let i = tailStart; i < history.length; i += 1) {
        const call = history[i];
        if (!call) continue;
        if (!call.resultHash) { noProgressEvidence = false; break; }
        if (call.argsHash === last.argsHash) {
            if (!firstHashA) firstHashA = call.resultHash;
            else if (firstHashA !== call.resultHash) {
                noProgressEvidence = false; break;
            }
        } else if (call.argsHash === otherSignature) {
            if (!firstHashB) firstHashB = call.resultHash;
            else if (firstHashB !== call.resultHash) {
                noProgressEvidence = false; break;
            }
        } else { noProgressEvidence = false; break; }
    }

    if (!firstHashA || !firstHashB) noProgressEvidence = false;

    return {
        count: alternatingTailCount + 1,
        pairedToolName: last.toolName,
        pairedSignature: last.argsHash,
        noProgressEvidence
    };
}
```

**检测逻辑详解**：
1. **识别配对**：从历史末尾向前查找第一个参数哈希不同于最后一次调用的记录，作为配对工具
2. **验证交替模式**：从末尾向前统计严格交替的模式长度（奇偶校验）
3. **当前调用验证**：只有当前调用匹配配对工具签名时才触发（避免双向都返回相同检测）
4. **无进展验证**：检查交替双方各自的结果在所有轮次中是否完全一致——如果双方结果都没变化，说明是真正的死循环

**`canonicalPairKey`用于生成唯一标识**：

```javascript
function canonicalPairKey(signatureA, signatureB) {
    return [signatureA, signatureB].toSorted().join("|");
}
```

排序后拼接确保A→B和B→A被识别为同一对。

**触发条件**：
- `pingPong.count >= criticalThreshold(20)` AND `noProgressEvidence === true` → critical
- `pingPong.count >= warningThreshold(10)` → warning

**消息示例**：
```
CRITICAL: You are alternating between repeated tool-call patterns
(20 consecutive calls) with no progress. This appears to be a stuck
ping-pong loop. Session execution blocked to prevent resource waste.
```

#### 检测器4: globalCircuitBreaker（全局熔断器）

这是最后的一道防线，基于**最粗暴但最有效**的逻辑：

```javascript
if (noProgressStreak >= resolvedConfig.globalCircuitBreakerThreshold) {
    return {
        stuck: true,
        level: "critical",
        detector: "global_circuit_breaker",
        count: noProgressStreak,
        message: `CRITICAL: ${toolName} has repeated identical no-progress
            outcomes ${noProgressStreak} times. Session execution blocked
            by global circuit breaker to prevent runaway loops.`,
        warningKey: `global:${toolName}:${currentHash}:${noProgress.latestResultHash ?? "none"}`
    };
}
```

**关键点**：
- 不看工具类型，不看参数变化，只看**结果是否一直相同**
- 阈值最高：30次（默认）
- 触发后直接阻止整个会话的执行——这是不可恢复的致命错误

#### 检测器5（隐式检测）: unknownToolRepeat（未知工具重复）

```javascript
function getUnknownToolRepeatStreak(history, toolName) {
    let streak = 0;
    let repeatedUnknownToolName;
    for (let i = history.length - 1; i >= 0; i -= 1) {
        const record = history[i];
        if (!record || record.toolName !== toolName
            || !record.unknownToolName) break;
        if (!repeatedUnknownToolName) {
            repeatedUnknownToolName = record.unknownToolName;
            streak = 1;
            continue;
        }
        if (record.unknownToolName !== repeatedUnknownToolName) break;
        streak += 1;
    }
    return { count: streak, unknownToolName: repeatedUnknownToolName };
}
```

这是最低级也是最危险的错误：Agent持续调用一个不存在的工具。`unknownToolName`由`extractUnknownToolName`函数从错误信息中提取：

```javascript
function extractUnknownToolName(error) {
    const raw = formatErrorForHash(error).trim();
    if (!raw) return;
    const toolName = (
        raw.match(/unknown tool[:\s]+["']?([a-z0-9_.-]+)["']?/i)
        ?? raw.match(/tool\s+["']?([a-z0-9_.-]+)["']?\s+
            (?:not found|is not available)/i)
    )?.[1]?.trim();
    return toolName ? toolName.toLowerCase() : void 0;
}
```

支持两种错误信息格式的正则匹配：
- `"unknown tool: <toolname>"` / `"unknown tool '<toolname>'"`
- `"tool '<toolname>' not found"` / `"tool '<toolname>' is not available"`

### 5.3.4 检测顺序与优先级

在`detectToolCallLoop`主函数中，检测器按**固定的优先级顺序**执行：

```
1. unknown_tool_repeat      (未知工具重复)
   └─ 条件: count >= unknownToolThreshold(10)
   └─ 级别: critical
   └─ 行为: 阻止执行

2. global_circuit_breaker   (全局熔断器)
   └─ 条件: noProgressStreak >= globalCircuitBreakerThreshold(30)
   └─ 级别: critical
   └─ 行为: 阻止执行

3. known_poll_no_progress   (轮询无进展)
   ├─ 条件: noProgressStreak >= criticalThreshold(20) → critical
   └─ 条件: noProgressStreak >= warningThreshold(10)  → warning

4. ping_pong                 (乒乓循环)
   ├─ 条件: count >= criticalThreshold(20) AND noProgressEvidence → critical
   └─ 条件: count >= warningThreshold(10)                          → warning

5. generic_repeat            (通用重复)
   ├─ 条件: noProgressStreak >= criticalThreshold(20) → critical
   └─ 条件: recentCount >= warningThreshold(10)       → warning
```

**关键设计决策**：
- **Critical级别直接阻断**：`stuck === true` + `level === "critical"` → 工具调用被拒绝，Agent收到错误消息
- **Warning级别仅通知**：发出警告日志但允许工具继续执行
- **Warning的节流机制**：`shouldEmitLoopWarning`使用10次为桶大小进行降频，防止日志洪水
- **优先级递减**：最严重的问题（未知工具、熔断器）优先处理，轮询无进展优先于通用重复（因为轮询更容易被误判）

### 5.3.5 运行范围隔离

循环检测支持按`runId`隔离历史记录：

```javascript
function selectHistoryForScope(history, scope) {
    const runId = normalizeRunId(scope?.runId);
    return history.filter((record) =>
        normalizeRunId(record.runId) === runId);
}
```

这意味着：
- 如果提供了`runId`，只考虑当前运行的工具调用历史
- 不提供`runId`时，考虑所有历史（跨运行累计）
- 这防止了跨不同任务的调用被误判为循环

### 5.3.6 工具结果哈希（hashToolOutcome）

为了让检测器能比较"结果是否变化"，需要为每次工具调用的输出计算结果哈希：

```javascript
function hashToolOutcome(toolName, params, result, error) {
    // 错误结果
    if (error !== void 0) {
        const unknownToolName = extractUnknownToolName(error);
        return {
            resultHash: `error:${digestStable(formatErrorForHash(error))}`,
            unknownToolName
        };
    }

    // 非对象结果
    if (!isPlainObject(result))
        return { resultHash: result === void 0 ? void 0 : digestStable(result) };

    // exec工具特殊处理
    if (toolName === "exec") {
        const execHash = hashExecToolOutcome(details, text);
        if (execHash) return { resultHash: execHash };
    }

    // process轮询特殊处理
    if (isKnownPollToolCall(toolName, params) && toolName === "process") {
        if (action === "poll") return { resultHash: digestStable({
            action, status, exitCode, exitSignal, aggregated, text
        })};
        if (action === "log") return { resultHash: digestStable({
            action, status, totalLines, totalChars, truncated,
            exitCode, exitSignal, text
        })};
    }

    // 默认处理
    return { resultHash: digestStable({ details, text }) };
}
```

**`hashExecToolOutcome`的细粒度处理**：

```javascript
function hashExecToolOutcome(details, text) {
    const status = stringField(details.status);
    if (!status) return;
    if (status === "running")
        return digestStable({ status, tail: stringField(details.tail) ?? "" });
    if (status === "completed" || status === "failed")
        return digestStable({ status,
            exitCode: typeof details.exitCode === "number"
                ? details.exitCode : null,
            timedOut: details.timedOut === true,
            output: nonEmptyStringField(details.aggregated) ?? text });
    if (status === "approval-pending" || status === "approval-unavailable")
        return digestStable({ status,
            reason: stringField(details.reason),
            host: stringField(details.host),
            command: stringField(details.command) ?? "",
            warningText: stringField(details.warningText) ?? "" });
}
```

**设计要点**：
- **按status分类哈希**：不同状态包含不同字段，确保状态变化能被检测到
- **running状态**：只包含tail文本（输出末尾），因为完整输出会不断增长
- **completed/failed状态**：包含exitCode、timedOut、aggregated输出——这些是区分不同执行结果的关键字段
- **approval状态**：包含reason、host、command——确保不同审批场景不被混淆

### 5.3.7 工具调用记录与结果回溯

**记录调用**: `recordToolCall`

```javascript
function recordToolCall(state, toolName, params, toolCallId, config, scope) {
    const resolvedConfig = resolveLoopDetectionConfig(config);
    const runId = normalizeRunId(scope?.runId);
    if (!state.toolCallHistory) state.toolCallHistory = [];
    state.toolCallHistory.push({
        toolName,
        argsHash: hashToolCall(toolName, params),
        toolCallId,
        ...runId && { runId },
        timestamp: Date.now()
    });
    // 维护滑动窗口
    if (state.toolCallHistory.length > resolvedConfig.historySize)
        state.toolCallHistory.splice(0,
            state.toolCallHistory.length - resolvedConfig.historySize);
}
```

**记录结果**: `recordToolCallOutcome`

这个函数在工具执行完成后被调用，它**回溯匹配**之前记录的调用并将`resultHash`和`unknownToolName`填入：

```javascript
function recordToolCallOutcome(state, params) {
    const outcome = hashToolOutcome(params.toolName, params.toolParams,
        params.result, params.error);
    const resultHash = outcome.resultHash;
    if (!resultHash) return;

    const argsHash = hashToolCall(params.toolName, params.toolParams);
    let matched = false;

    // 回溯查找匹配的调用记录（从最新到最旧）
    for (let i = state.toolCallHistory.length - 1; i >= 0; i -= 1) {
        const call = state.toolCallHistory[i];
        if (!call) continue;
        if (normalizeRunId(call.runId) !== runId) continue;
        if (params.toolCallId && call.toolCallId !== params.toolCallId) continue;
        if (call.toolName !== params.toolName
            || call.argsHash !== argsHash) continue;
        if (call.resultHash !== void 0) continue;  // 跳过已有结果的
        call.resultHash = resultHash;
        call.unknownToolName = outcome.unknownToolName;
        matched = true;
        break;
    }

    // 如果没找到匹配记录，创建一个新的
    if (!matched) {
        const record = { toolName: params.toolName, argsHash, ... };
        state.toolCallHistory.push(record);
    }

    // 维护滑动窗口
    if (state.toolCallHistory.length > resolvedConfig.historySize)
        state.toolCallHistory.splice(0, ...);
    return recordedOutcome;
}
```

**匹配策略**：
1. 优先通过`toolCallId`精确匹配
2. 其次通过`toolName + argsHash`匹配
3. 通过`runId`进行运行范围隔离
4. 只填充还未有结果哈希的记录（防止重复填充）

---

## 5.4 安全机制

严格的安全约束是OpenClaw工具系统的基石。本节详细介绍四个核心安全维度。

### 5.4.1 工作区限制

OpenClaw通过严格的路径边界检查来防止Agent访问工作区之外的文件系统。

**`toRelativeWorkspacePath`函数**：

```javascript
function toRelativeWorkspacePath(root, candidate, options) {
    return toRelativeBoundaryPath({
        root, candidate, options,
        boundaryLabel: "workspace root"
    });
}
```

**`validateRelativePathWithinBoundary`核心验证**：

```javascript
function validateRelativePathWithinBoundary(params) {
    // 允许空路径或"." 当allowRoot为true时
    if (params.relativePath === "" || params.relativePath === ".") {
        if (params.options?.allowRoot) return "";
        throwPathEscapesBoundary({...});
    }
    // 拒绝".."和所有外部路径
    if (params.relativePath === ".."
        || params.relativePath.startsWith("../")
        || params.relativePath.startsWith("..\\")
        || params.isAbsolutePath(params.relativePath))
        throwPathEscapesBoundary({...});
    return params.relativePath;
}
```

**路径逃逸错误**：

```javascript
function throwPathEscapesBoundary(params) {
    const boundary = params.options?.boundaryLabel ?? "workspace root";
    const suffix = params.options?.includeRootInError
        ? ` (${params.rootResolved})` : "";
    throw new Error(`Path escapes ${boundary}${suffix}: ${params.candidate}`);
}
```

在底层，OpenClaw还使用`FsSafeError`类精确区分不同类型的文件系统安全错误：
- `"invalid-path"` — 路径格式无效（非绝对路径、含NUL字节）
- `"not-found"` — 文件不存在
- `"symlink"` — 路径穿越符号链接
- `"outside-workspace"` — 路径超出工作区边界

**跨平台Windows路径规范化**：系统在win32平台上使用`path.win32.resolve`和`path.win32.relative`进行路径解析，并通过`normalizeWindowsPathForComparison`统一比较：

```javascript
if (process.platform === "win32") {
    const rootResolved = path.win32.resolve(params.root);
    const resolvedCandidate = path.win32.resolve(resolvedInput);
    const rootForCompare = normalizeWindowsPathForComparison(rootResolved);
    const targetForCompare = normalizeWindowsPathForComparison(resolvedCandidate);
    // ...
}
```

### 5.4.2 沙盒路径验证

多根沙盒提供更细粒度的路径控制，通过`assertSandboxPath`实现：

```javascript
async function assertSandboxPath(params) {
    const resolved = resolveSandboxPath(params);
    const policy = {
        allowFinalSymlinkForUnlink: params.allowFinalSymlinkForUnlink,
        allowFinalHardlinkForUnlink: params.allowFinalHardlinkForUnlink
    };
    await assertNoPathAliasEscape({
        absolutePath: resolved.resolved,
        rootPath: params.root,
        boundaryLabel: "sandbox root",
        policy
    });
    return resolved;
}
```

**沙盒媒体路径处理**：`resolveSandboxedMediaSource`支持多层路径解析：

1. **Pass-through远程URL**：直接放行HTTP/HTTPS媒体URL
2. **file:// URL处理**：
   - 先尝试映射容器工作区路径（`mapContainerWorkspaceFileUrl`）
   - 再尝试`safeFileURLToPath`转换
   - 检查编码的分隔符（`hasEncodedFileUrlSeparator`）防止路径注入
3. **容器工作区映射**（`mapContainerWorkspacePath`）：
   - `/workspace` → 沙盒根路径
   - `/workspace/xxx` → 沙盒根路径/xxx
4. **临时媒体路径验证**：在openclaw临时目录内的文件需额外验证
5. **托管媒体路径验证**：在托管media目录下的outbound和tool-*子目录
6. **最终沙盒路径验证**：调用`assertSandboxPath`进行边界检查

### 5.4.3 所有者专用工具

某些高度敏感的工具只允许"所有者"身份的发送者使用：

```javascript
const OWNER_ONLY_TOOL_APPROVAL_CLASS_FALLBACKS = new Map([
    ["cron",    "control_plane"],   // 定时任务
    ["gateway", "control_plane"],   // 网关控制
    ["nodes",   "exec_capable"],    // 节点设备管理
]);
```

**`applyOwnerOnlyToolPolicy`应用逻辑**：

```javascript
function applyOwnerOnlyToolPolicy(tools, senderIsOwner, ownerOnlyToolAllowlist) {
    const allowedOwnerOnlyTools = new Set(
        ownerOnlyToolAllowlist?.map((name) => normalizeToolName(name)) ?? []);
    const isAuthorized = (tool) => senderIsOwner
        || allowedOwnerOnlyTools.has(normalizeToolName(tool.name));

    const withGuard = tools.map((tool) => {
        if (!isOwnerOnlyTool(tool)) return tool;
        return wrapOwnerOnlyToolExecution(tool, isAuthorized(tool));
    });

    // 非所有者：过滤掉未经授权的所有者专用工具
    if (senderIsOwner) return withGuard;
    return withGuard.filter((tool) =>
        !isOwnerOnlyTool(tool) || isAuthorized(tool));
}
```

**两层保护机制**：
1. **包装层**：即使工具通过了过滤，其`execute`也被替换为抛异常的函数："Tool restricted to owner senders."
2. **过滤层**：非所有者发送者完全看不到这些工具

**`cron`和`gateway`映射到`control_plane`审批类别**：这意味着控制平面可以单独授权这些工具。`nodes`映射到`exec_capable`类别：需要执行能力审批。

### 5.4.4 命令执行安全

命令执行（exec工具）是最具风险的Agent操作，OpenClaw提供了多层安全控制。

**safeBins机制**：

`tools.exec.safeBins`定义了一组"安全二进制文件"列表，这些二进制文件可以走快速通道免审批执行：

```javascript
function normalizeConfiguredSafeBins(entries) {
    if (!Array.isArray(entries)) return [];
    return Array.from(new Set(entries
        .map((entry) => normalizeOptionalLowercaseString(entry) ?? "")
        .filter((entry) => entry.length > 0)))
        .toSorted();
}
```

**safeBinTrustedDirs**：限制安全二进制文件的解析路径必须位于受信任目录内：

```javascript
function isTrustedSafeBinPath({ resolvedPath, trustedDirs }) {
    // 检查解析后的路径是否在任一受信任目录内
    // 不在 → 触发警告："resolves outside trusted safe-bin dirs"
}
```

**safeBinProfiles**：为每个安全二进制文件详细配置允许的标志和位置参数，防止危险的参数注入：

```javascript
// 扫描缺少profile的safeBin条目
if (scope.mergedProfiles[bin]) continue;  // 有profile → 安全
hits.push({
    scopePath: scope.scopePath,
    bin,
    kind: "missingProfile",
    isInterpreter: interpreterBins.has(bin)
});
```

**execApprovals审批框架**：

审批配置支持安全级别（security）和询问策略（ask）两个维度的配置：

```javascript
const resolved = resolveExecApprovalsFromFile({
    file: params.approvals,
    agentId: params.agentId,
    overrides: {
        security: requestedSecurity.value,  // full / reduced / off
        ask: requestedAsk.value              // always / once / off
    }
});
const effectiveSecurity = minSecurity(requestedSecurity.value,
    resolved.agent.security);  // 更严格的安全级别生效
const effectiveAsk = maxAsk(requestedAsk.value,
    resolved.agent.ask);      // 更频繁的询问策略生效
```

这实现了**"最严格者胜"**原则：无论配置如何，host级别的审批策略可以收紧但不能放宽。

**strictInlineEval**：在运行时的配置中强制执行更严格的代码执行限制，防止Agent通过exec或其他方式绕过安全检查。

### 5.4.5 文件系统安全（fs-safe模块）

在`fs-safe-DpJlqO1z.js`中，`@openclaw/fs-safe`模块提供了底层文件系统安全原语：

**符号链接防护**：

```javascript
async function resolveAbsolutePathForRead(filePath, options = {}) {
    const normalized = assertAbsolutePathInput(filePath);
    let canonicalPath;
    try {
        canonicalPath = await fs$1.realpath(normalized);  // 解析真实路径
    } catch (err) {
        if (err.code === "ENOENT")
            throw new FsSafeError("not-found", "path not found", { cause: err });
        throw err;
    }
    if ((options.symlinks ?? "reject") === "reject"
        && canonicalPath !== normalized)
        throw new FsSafeError("symlink", "path traverses a symlink",
            { cause: { canonicalPath } });
    return { path: normalized, canonicalPath };
}
```

默认拒绝符号链接（`symlinks: "reject"`），防止通过符号链接绕过工作区限制。

**file:// URL安全验证**：

```javascript
function safeFileURLToPath(fileUrl) {
    let parsed;
    try { parsed = new URL(fileUrl); } catch {
        throw new Error(`Invalid file:// URL: ${fileUrl}`);
    }
    if (parsed.protocol !== "file:")
        throw new Error(`Invalid file:// URL: ${fileUrl}`);
    if (!isLocalFileUrlHost(parsed.hostname))
        throw new Error(`file:// URLs with remote hosts are not allowed`);
    if (hasEncodedFileUrlSeparator(parsed.pathname))
        throw new Error(`file:// URLs cannot encode path separators`);
    const filePath = fileURLToPath(parsed);
    assertNoWindowsNetworkPath(filePath, "Local file URL");
    return filePath;
}
```

三重防护：
1. 拒绝远程主机file:// URL（如`file://evil.com/share`）
2. 拒绝编码的路径分隔符（如`%2f`、`%5c`）
3. 拒绝Windows网络路径（UNC路径）

**文件名清理**：

```javascript
function sanitizeUntrustedFileName(fileName, fallbackName) {
    const trimmed = typeof fileName === "string" ? fileName.trim() : "";
    if (!trimmed) return fallbackName;
    let base = path.posix.basename(trimmed);
    base = path.win32.basename(base);
    let cleaned = "";
    for (let i = 0; i < base.length; i++) {
        const code = base.charCodeAt(i);
        if (code < 32 || code === 127) continue;  // 删除控制字符
        cleaned += base[i];
    }
    base = cleaned.trim();
    if (!base || base === "." || base === "..") return fallbackName;
    if (base.length > 200) base = base.slice(0, 200);
    return base;
}
```

### 5.4.6 子Agent工具限制

子Agent（Subagent）在默认情况下会受到额外工具限制：

```javascript
const SUBAGENT_TOOL_DENY_ALWAYS = [
    "gateway",           // 网关控制
    "agents_list",       // Agent列表
    "session_status",    // 会话状态（修改模型等敏感操作）
    "cron",              // 定时任务
    "sessions_send"      // 向其他会话发送消息
];

const SUBAGENT_TOOL_DENY_LEAF = [
    "subagents",         // 不能再创建子Agent
    "sessions_list",     // 不能列举会话
    "sessions_history",  // 不能查看其他会话历史
    "sessions_spawn"     // 不能生成新会话
];
```

**角色区分**：
- **普通子Agent**：使用`SUBAGENT_TOOL_DENY_ALWAYS` — 不能访问系统级工具
- **叶子子Agent**（depth >= maxSpawnDepth）：使用全部限制 — 也不能创建下级Agent

配置允许显式覆盖：如果用户在`tools.subagents.tools.allow`中显式加入某个工具名，则该工具不会出现在合并后的deny列表中。

---

## 5.5 Code Mode：QuickJS WASI沙盒

Code Mode是OpenClaw提供的一种安全代码执行环境，允许Agent在受控的沙盒中运行JavaScript代码。底层使用QuickJS WASI运行时。

### 5.5.1 QuickJS WASI Worker架构

Code Mode运行在独立的Worker线程中（`code-mode.worker.js`），与主Agent进程完全隔离：

```javascript
import { EvalFlags, Intrinsics, JSException, QuickJS } from "quickjs-wasi";

async function createVm(params) {
    const startedAt = Date.now();
    let timedOut = false;
    const vm = await QuickJS.create({
        memoryLimit: params.config.memoryLimitBytes,
        intrinsics: Intrinsics.ALL,
        timezoneOffset: 0,
        interruptHandler: () => {
            timedOut = Date.now() - startedAt > params.config.timeoutMs;
            return timedOut;  // 超时时返回true触发中断
        }
    });
    // 注入工具目录和主机请求处理器
    // ...
    return { vm, didTimeout: () => timedOut };
}
```

### 5.5.2 安全限制

**内存限制**：
```javascript
memoryLimit: params.config.memoryLimitBytes
```
QuickJS WASI的堆内存受限，防止代码耗尽系统内存。

**执行时间限制**：
```javascript
interruptHandler: () => {
    timedOut = Date.now() - startedAt > params.config.timeoutMs;
    return timedOut;
}
```
每次VM操作（包括eval、函数调用、Promise resolve等）都会检查中断处理器。超时后VM操作会抛出异常。

**快照大小限制**：
```javascript
if (snapshotBytes.byteLength > params.config.maxSnapshotBytes)
    throw new Error("code mode snapshot limit exceeded");
```
当Code Mode需要暂停等待嵌套工具调用结果时，VM状态被序列化为快照。快照大小受限以防止内存膨胀。

**待处理请求限制**：
```javascript
if (params.pendingRequests.length >= params.config.maxPendingToolCalls)
    throw new Error("too many pending code mode tool calls");
```
限制Code Mode可以同时发起的嵌套工具调用数量，防止资源耗尽。

**VM dispose**：
```javascript
} finally {
    vm.dispose();
}
```
每次执行完成后在finally块中释放VM资源，确保不会泄漏。

### 5.5.3 控制器注入

在用户代码执行之前，系统注入一个控制器脚本（`CONTROLLER_SOURCE`），提供以下沙盒API：

- `tools.search(query, options)` — 搜索可用工具
- `tools.describe(id)` — 获取工具描述
- `tools.<toolName>(input)` — 调用特定工具（自动从catalog注册）
- `text(value)` — 输出文本结果
- `json(value)` — 输出JSON结果
- `yield_control(reason)` — 让出控制权（用于异步等待）

**工具名安全过滤**：
```javascript
for (const tool of catalog) {
    const name = typeof tool?.name === "string" ? tool.name : "";
    if (!/^[A-Za-z_$][A-Za-z0-9_$]*$/.test(name)) continue;
    safeNameCounts.set(name, (safeNameCounts.get(name) ?? 0) + 1);
}
```
只有符合JavaScript标识符规范且唯一名称的工具才会被挂载到`tools`对象上。

### 5.5.4 执行与恢复流程

**首次执行** (`runExec`):
1. 创建QuickJS VM
2. 注入catalog和host request处理器
3. Eval控制器脚本
4. Eval用户源代码（包装在async IIFE中）
5. 排空pending jobs
6. 提取输出和结果
7. 如果有pending requests → 序列化快照，返回`{status: "waiting"}`
8. 否则 → 返回`{status: "completed", value, output}`

**恢复执行** (`runResume`):
1. 反序列化快照恢复VM
2. 注册host request处理器
3. 结算（settle）已完成的嵌套请求
4. 排空pending jobs
5. 提取输出和结果
6. 同样支持pending requests的等待状态

### 5.5.5 严格的桥接方法限制

```javascript
if (method !== "search" && method !== "describe"
    && method !== "call" && method !== "yield")
    throw new Error("unsupported code mode bridge method");
```

只允许四种桥接方法，沙盒代码无法通过桥接执行任意操作。所有桥接调用都经过序列化/反序列化（JSON），防止引用泄漏。

---

## 5.6 安全防护总结

OpenClaw的工具系统通过以下**多层纵深防御**确保安全：

| 层级 | 机制 | 防御目标 |
|------|------|---------|
| 配置层 | 11层策略管道 + deny优先 | 最小权限原则，精确控制工具可用性 |
| 运行时层 | wrapToolWithBeforeToolCallHook | 统一的钩子前置拦截点 |
| 行为层 | 5种循环检测器 + 全局熔断器 | 防止Agent死循环和资源浪费 |
| 路径层 | 工作区限制 + 沙盒边界检查 | 文件系统访问隔离 |
| 文件层 | 符号链接拒绝、file://安全验证 | 路径穿越和注入攻击 |
| 执行层 | safeBins + execApprovals | 命令执行的安全审批 |
| 权限层 | 所有者专用工具 + 子Agent限制 | 敏感操作的身份校验 |
| 沙盒层 | QuickJS WASI + 内存/时间限制 | 代码执行的完全隔离 |
| 审计层 | 诊断事件体系 | 全链路可观测和可追溯 |

这种设计确保即使在Agent表现出不可预测行为时，系统也能在多个维度上兜底，将风险控制在可接受的范围内。
# 六、Channel消息系统（上）：架构总览与ChannelPlugin接口

## 6.1 引言

OpenClaw的消息系统是整个框架的核心基础设施。它通过一套统一的`ChannelPlugin`接口，将Discord、LINE、Telegram、Slack、Signal、WhatsApp、iMessage、Matrix、Mattermost等十余种即时通讯平台对接到同一个Agent路由与Turn Pipeline处理管线中。本章聚焦于ChannelPlugin接口的架构设计、消息流转路径以及安全模型，从源码层面逐一剖析各模块的职责与交互方式。

在深入细节之前，需要理解OpenClaw的设计哲学：**所有平台差异被封装在ChannelPlugin的适配器（Adapter）接口背后，核心引擎只看到统一的Channel抽象**。无论是Discord的WebSocket Gateway、LINE的Webhook被动接收、还是Signal通过signal-cli本地进程主动拉取消息，从核心角度看，它们都是实现了相同接口的"Channel"。

---

## 6.2 总体架构

### 6.2.1 架构分层

OpenClaw的整体架构可以看作三层：核心引擎层、Channel插件接口层、平台实现层。

```
+---------------------------------------------------------------------+
|                        OpenClaw Core                                |
|  +------------------+  +------------------+  +------------------+  |
|  |   Agent Router   |  |  Turn Pipeline   |  | Session Manager  |  |
|  +--------+---------+  +--------+---------+  +--------+---------+  |
|           |                    |                    |               |
|  +--------v--------------------v--------------------v---------+    |
|  |              Channel Plugin Interface (ChannelPlugin)       |    |
|  |  +----------+ +----------+ +--------+ +--------+ +-------+ |    |
|  |  | gateway  | | message  | |security| | pairing | |config | |    |
|  |  +----------+ +----------+ +--------+ +--------+ +-------+ |    |
|  |  +----------+ +----------+ +--------+ +--------+ +-------+ |    |
|  |  |messaging | | outbound | |actions | |threading| |status | |    |
|  |  +----------+ +----------+ +--------+ +--------+ +-------+ |    |
|  +-----+--------+--------+--------+--------+--------+---------+    |
|        |        |        |        |        |        |              |
|   Discord  LINE  Telegram  Slack  Signal  WhatsApp  ...           |
|  (Gateway (Webhook (Bot API  (Socket (signal- (Baileys (Matrix)   |
|  WebSocket) passive)  polling)  Mode)  cli)     lib)              |
+---------------------------------------------------------------------+
```

### 6.2.2 核心组件职责

| 组件 | 职责 |
|------|------|
| **Agent Router** | 根据`channel + accountId + conversationId`三元组将入站消息路由到正确的Agent |
| **Turn Pipeline** | 8阶段的消息处理管线（见下文），包括上下文准备、模型推理、回复生成 |
| **Session Manager** | 管理对话会话生命周期，包括会话键生成、线程绑定、空闲超时 |
| **ChannelPlugin接口** | 20+模块的统一抽象，每个模块是一个可选的适配器接口 |
| **平台实现** | Discord/Telegram/Signal等具体实现，注入平台特有的认证、传输、消息格式 |

### 6.2.3 关键代码入口

从源码中可以看到，ChannelPlugin的注册是通过`defineChannelPluginEntry`函数完成的（见`/dist/core-B_GhzhOy.js`）：

```typescript
// 源码: core-B_GhzhOy.js (第144行)
function defineChannelPluginEntry({
    id, name, description, plugin,
    configSchema, setRuntime,
    registerCliMetadata, registerFull
}) {
    return {
        id, name, description,
        configSchema: typeof configSchema === "function"
            ? configSchema()
            : configSchema ?? emptyChannelConfigSchema(),
        register(api) {
            if (api.registrationMode === "cli-metadata") {
                registerCliMetadata?.(api);
                return;
            }
            if (api.registrationMode === "tool-discovery") {
                registerFull?.(api);
                return;
            }
            api.registerChannel({ plugin });
            setRuntime?.(api.runtime);
            // ... 其余注册逻辑
        },
        channelPlugin: plugin,
        ...setRuntime ? { setChannelRuntime: setRuntime } : {}
    };
}
```

这段代码揭示了几个关键设计：
1. **分阶段注册**：插件注册有多种模式（`cli-metadata`、`tool-discovery`、`discovery`、`full`），不同模式下暴露的功能不同
2. **ChannelPlugin是核心**：真正的插件实现在`plugin`字段中，它是一个`ChannelPlugin<T>`类型的对象
3. **配置Schema分离**：`configSchema`可以是函数或静态值，允许延迟生成

---

## 6.3 ChannelPlugin核心接口详解

`ChannelPlugin<ResolvedAccount, Probe, Audit>`是OpenClaw中最重要的接口，定义在`/dist/types.public-lNnJ5wLZ.d.ts`中。它包含20+个可选的适配器模块，每个模块负责一类功能。下面逐一详解。

### 6.3.1 `id: ChannelId`

**类型**：`ChannelId`（字符串字面量联合类型）

**说明**：频道插件的全局唯一标识符。典型的ChannelId值包括：

- `"discord"` — Discord
- `"telegram"` — Telegram
- `"line"` — LINE
- `"signal"` — Signal
- `"slack"` — Slack
- `"whatsapp"` — WhatsApp
- `"imessage"` — iMessage
- `"matrix"` — Matrix
- `"mattermost"` — Mattermost
- `"googlechat"` — Google Chat
- `"zalouser"` — Zalo
- `"clickclack"` — ClickClack (自定义)

**源码位置**：`/dist/channel-id.types-COdJCz0S.js`

### 6.3.2 `meta: ChannelMeta`

**类型**：`ChannelMeta`

**说明**：用户可见的频道元数据，用于文档生成、平台选择器、配置表面。来自源码（`types.core-BdWvYpdc.d.ts`）：

```typescript
type ChannelMeta = {
    id: ChannelId;                    // 唯一标识
    label: string;                    // 显示标签 (如 "Discord")
    selectionLabel: string;           // 在平台选择器中的标签
    docsPath: string;                 // 文档路径
    docsLabel?: string;               // 文档标签
    blurb: string;                    // 简短描述
    order?: number;                   // 排序权重
    aliases?: readonly string[];      // 别名 (如 "dc", "disc")
    selectionDocsPrefix?: string;     // 选择器文档前缀
    selectionDocsOmitLabel?: boolean;  // 是否在文档中省略标签
    selectionExtras?: readonly string[]; // 额外展示信息
    detailLabel?: string;             // 详情标签
    systemImage?: string;             // 系统图标路径
    markdownCapable?: boolean;        // 是否支持Markdown
    exposure?: ChannelExposure;       // 暴露策略 {configured, setup, docs}
    showConfigured?: boolean;         // 是否展示已配置状态
    showInSetup?: boolean;            // 是否在设置界面显示
    quickstartAllowFrom?: boolean;    // 快速启动allowFrom
    forceAccountBinding?: boolean;    // 强制账户绑定
    preferSessionLookupForAnnounceTarget?: boolean;
    preferOver?: readonly string[];   // 优先于哪些频道
};
```

**关键设计**：`meta`信息被缓存在`resolveSdkChatChannelMeta`函数中，通过`buildChatChannelMetaById()`从bundled插件目录中自动发现并加载。

### 6.3.3 `capabilities: ChannelCapabilities`

**类型**：`ChannelCapabilities`

**说明**：频道的静态能力矩阵，描述该平台原生支持哪些功能。来自源码：

```typescript
type ChannelCapabilities = {
    chatTypes: Array<ChatType | "thread">;  // 支持的聊天类型
    polls?: boolean;              // 投票
    reactions?: boolean;          // 表情反应
    edit?: boolean;               // 编辑消息
    unsend?: boolean;             // 撤回消息
    reply?: boolean;              // 回复引用
    effects?: boolean;            // 特效
    groupManagement?: boolean;    // 群组管理
    threads?: boolean;            // 话题/线程
    media?: boolean;              // 媒体发送
    nativeCommands?: boolean;     // 原生斜杠命令
    blockStreaming?: boolean;     // 禁止流式传输
    tts?: {
        voice?: {
            synthesisTarget: "audio-file" | "voice-note";
            transcodesAudio?: boolean;
            audioFileFormats?: readonly string[];
            preferAudioFileFormat?: "caf";
        };
    };
};
```

**各能力详解**：

- **chatTypes**：决定频道支持哪种会话模式。`ChatType`可以是`"direct"`（私聊）、`"group"`（群聊）、`"channel"`（频道/广播）加上特殊的`"thread"`类型
- **polls**：是否可以发起投票。需要同时支持`outbound.sendPoll(ctx)`
- **reactions**：是否支持消息表情反应。对应55+个actions中的`"react"`和`"reactions"`
- **edit**：是否支持编辑已发送消息，对应action `"edit"`
- **unsend**：是否支持撤回已发送消息，对应action `"unsend"`
- **threads**：是否支持话题/线程功能，对应`"thread-create"`、`"thread-list"`、`"thread-reply"`等action
- **blockStreaming**：当设为`true`时，禁止流式输出（如LINE的webhook模式下不支持流式）
- **media**：是否支持媒体发送

### 6.3.4 `config: ChannelConfigAdapter<ResolvedAccount>`

**类型**：`ChannelConfigAdapter<ResolvedAccount>`

**说明**：频道配置管理适配器，负责解析和操作`OpenClawConfig`中的频道配置。这是**唯一必须实现**的核心适配器。

```typescript
type ChannelConfigAdapter<ResolvedAccount> = {
    // 列出所有已配置的账户ID
    listAccountIds: (cfg: OpenClawConfig) => string[];

    // 解析单个账户为ResolvedAccount类型
    resolveAccount: (cfg: OpenClawConfig, accountId?: string | null) => ResolvedAccount;

    // 检查账户（用于调试/诊断）
    inspectAccount?: (cfg: OpenClawConfig, accountId?: string | null) => unknown;

    // 获取默认账户ID
    defaultAccountId?: (cfg: OpenClawConfig) => string;

    // 启用/禁用账户
    setAccountEnabled?: (params: { cfg; accountId; enabled }) => OpenClawConfig;

    // 删除账户
    deleteAccount?: (params: { cfg; accountId }) => OpenClawConfig;

    // 判断是否已启用
    isEnabled?: (account: ResolvedAccount, cfg: OpenClawConfig) => boolean;

    // 未启用的原因描述
    disabledReason?: (account: ResolvedAccount, cfg: OpenClawConfig) => string;

    // 判断是否已配置
    isConfigured?: (account: ResolvedAccount, cfg: OpenClawConfig) => boolean | Promise<boolean>;

    // 未配置的原因描述
    unconfiguredReason?: (account: ResolvedAccount, cfg: OpenClawConfig) => string;

    // 生成账户快照（用于status展示）
    describeAccount?: (account: ResolvedAccount, cfg: OpenClawConfig) => ChannelAccountSnapshot;

    // 解析allowFrom白名单
    resolveAllowFrom?: (params: { cfg; accountId? }) => Array<string | number> | undefined;

    // 格式化allowFrom条目
    formatAllowFrom?: (params: { cfg; accountId?; allowFrom }) => string[];

    // 检查是否有已配置状态
    hasConfiguredState?: (params: { cfg; env? }) => boolean;

    // 检查是否有持久化的认证状态
    hasPersistedAuthState?: (params: { cfg; env? }) => boolean;

    // 解析默认目标地址
    resolveDefaultTo?: (params: { cfg; accountId? }) => string | undefined;
};
```

**设计要点**：
- `ResolvedAccount`是一个泛型参数，每个频道可以定义自己的账户结构
- `listAccountIds`和`resolveAccount`是最核心的两个方法
- `ChannelAccountSnapshot`包含60+个字段，涵盖连接状态、认证状态、最后一次活动时间、策略信息等

### 6.3.5 `setup: ChannelSetupAdapter`

**类型**：`ChannelSetupAdapter`

**说明**：初始化向导适配器，负责处理新账户的配置写入。

```typescript
type ChannelSetupAdapter = {
    resolveAccountId?: (params: { cfg; accountId?; input? }) => string;
    resolveBindingAccountId?: (params: { cfg; agentId; accountId? }) => string | undefined;
    applyAccountName?: (params: { cfg; accountId; name? }) => OpenClawConfig;
    // 将用户输入（token、webhookUrl等）应用到配置
    applyAccountConfig: (params: { cfg; accountId; input: ChannelSetupInput }) => OpenClawConfig;
    afterAccountConfigWritten?: (params: { previousCfg; cfg; accountId; input; runtime }) => Promise<void> | void;
    validateInput?: (params: { cfg; accountId; input }) => string | null;
    singleAccountKeysToMove?: readonly string[];
    namedAccountPromotionKeys?: readonly string[];
    resolveSingleAccountPromotionTarget?: (params: { channel }) => string | undefined;
};
```

`ChannelSetupInput`是一个包含30+个可选字段的输入Bag，涵盖各种认证方式：
```typescript
type ChannelSetupInput = {
    name?: string; token?: string; secret?: string; botToken?: string;
    appToken?: string; signalNumber?: string; cliPath?: string;
    webhookPath?: string; webhookUrl?: string; httpUrl?: string;
    httpHost?: string; httpPort?: string; useEnv?: boolean;
    homeserver?: string; proxy?: string; userId?: string;
    accessToken?: string; password?: string; code?: string;
    // ... 更多字段
};
```

### 6.3.6 `security: ChannelSecurityAdapter<ResolvedAccount>`

**类型**：`ChannelSecurityAdapter<ResolvedAccount>`

**说明**：安全策略适配器，实现DM白名单、群组策略、安全审计等功能。详见第6.6节的三层安全模型。

```typescript
type ChannelSecurityAdapter<ResolvedAccount> = {
    applyConfigFixes?: (params: { cfg; env }) => ChannelDoctorConfigMutation | Promise<...>;
    // 核心方法：解析DM安全策略（返回policy、allowFrom、approveHint等）
    resolveDmPolicy?: (ctx: ChannelSecurityContext<ResolvedAccount>) => ChannelSecurityDmPolicy | null;
    // 收集安全警告
    collectWarnings?: (ctx: ChannelSecurityContext<ResolvedAccount>) => Promise<string[]> | string[];
    // 收集安全审计发现
    collectAuditFindings?: (ctx: ChannelSecurityContext<ResolvedAccount> & {
        sourceConfig; orderedAccountIds; hasExplicitAccountPath;
    }) => Promise<Array<{ checkId; severity; title; detail; remediation? }>>;
};
```

**源码中的`resolveChatChannelSecurity`辅助函数**（来自`core-B_GhzhOy.js`）展示了如何从声明式配置构建安全适配器：

```typescript
function resolveChatChannelSecurity(security) {
    if (!security) return;
    if (!("dm" in security)) return security;
    return {
        resolveDmPolicy: ({ cfg, accountId, account }) =>
            buildAccountScopedDmSecurityPolicy({
                cfg,
                channelKey: security.dm.channelKey,
                accountId,
                // ... 多层策略回退参数
                policy: security.dm.resolvePolicy(account),
                allowFrom: security.dm.resolveAllowFrom(account) ?? [],
                defaultPolicy: security.dm.defaultPolicy,
                // ...
            }),
        ...security.collectWarnings ? { collectWarnings: security.collectWarnings } : {},
        ...security.collectAuditFindings ? { collectAuditFindings: security.collectAuditFindings } : {}
    };
}
```

### 6.3.7 `gateway: ChannelGatewayAdapter<ResolvedAccount>`

**类型**：`ChannelGatewayAdapter<ResolvedAccount>`

**说明**：Gateway生命周期管理，控制频道的启动、停止、登出。

```typescript
type ChannelGatewayAdapter<ResolvedAccount> = {
    // 启动单个账户的gateway（WebSocket连接、polling循环等）
    startAccount?: (ctx: ChannelGatewayContext<ResolvedAccount>) => Promise<unknown>;

    // 停止单个账户
    stopAccount?: (ctx: ChannelGatewayContext<ResolvedAccount>) => Promise<void>;

    // Gateway认证绕过路径解析
    resolveGatewayAuthBypassPaths?: (params: { cfg }) => string[];

    // 扫码登录（如QQ/WeChat风格的QR码登录）
    loginWithQrStart?: (params: { accountId?; force?; timeoutMs?; verbose? }) => Promise<ChannelLoginWithQrStartResult>;
    loginWithQrWait?: (params: { accountId?; timeoutMs?; currentQrDataUrl? }) => Promise<ChannelLoginWithQrWaitResult>;

    // 登出/清理认证数据
    logoutAccount?: (ctx: ChannelLogoutContext<ResolvedAccount>) => Promise<ChannelLogoutResult>;
};
```

`ChannelGatewayContext`的`channelRuntime`字段提供了丰富的运行时能力：

```typescript
type ChannelGatewayContext<ResolvedAccount> = {
    cfg: OpenClawConfig;
    accountId: string;
    account: ResolvedAccount;
    runtime: RuntimeEnv;
    abortSignal: AbortSignal;
    log?: ChannelLogSink;
    getStatus: () => ChannelAccountSnapshot;
    setStatus: (next: ChannelAccountSnapshot) => void;
    // 可选的运行时辅助功能
    channelRuntime?: ChannelRuntimeSurface;  // 包含reply、routing、text、session、media、commands、groups、pairing等
};
```

### 6.3.8 `message: ChannelMessageAdapterShape`

**类型**：`ChannelMessageAdapterShape<TConfig, TSendResult>`

**说明**：消息发送/接收适配器，是新架构下推荐的统一消息接口。

```typescript
type ChannelMessageAdapterShape<TConfig, TSendResult> = {
    id?: string;
    // 持久化最终消息能力
    durableFinal?: {
        capabilities?: DurableFinalDeliveryRequirementMap;
        reconcileUnknownSend?: (...) => Promise<...>;
    };
    // 发送适配器（text/media/payload三种通道）
    send?: {
        text?: (ctx: ChannelMessageSendTextContext) => Promise<TSendResult>;
        media?: (ctx: ChannelMessageSendMediaContext) => Promise<TSendResult>;
        payload?: (ctx: ChannelMessageSendPayloadContext) => Promise<TSendResult>;
        lifecycle?: ChannelMessageSendLifecycleAdapter;  // beforeSendAttempt, afterSendSuccess, afterSendFailure, afterCommit
    };
    // 实时预览能力
    live?: {
        capabilities?: Partial<Record<ChannelMessageLiveCapability, boolean>>;
        finalizer?: {
            capabilities?: LivePreviewFinalizerCapabilityMap;
        };
    };
    // 接收确认策略
    receive?: {
        defaultAckPolicy?: ChannelMessageReceiveAckPolicy;
        supportedAckPolicies?: readonly ChannelMessageReceiveAckPolicy[];
    };
};
```

**关键函数**（来自`channel-message-A0qNE-jd.js`）：

```typescript
// 从outbound适配器创建channel message适配器
function createChannelMessageAdapterFromOutbound(params) {
    const send = {};
    if (params.outbound.sendText)
        send.text = async (ctx) => toMessageSendResult(
            await params.outbound.sendText(ctx), { kind: "text", threadId: ctx.threadId, replyToId: ctx.replyToId }
        );
    if (params.outbound.sendMedia)
        send.media = async (ctx) => toMessageSendResult(
            await params.outbound.sendMedia(ctx), { kind: ctx.audioAsVoice ? "voice" : "media", ... }
        );
    if (params.outbound.sendPayload)
        send.payload = async (ctx) => toMessageSendResult(
            await params.outbound.sendPayload(ctx), { kind: resolvePayloadReceiptKind(ctx), ... }
        );
    return {
        ...params.id ? { id: params.id } : {},
        durableFinal: { capabilities: params.capabilities ?? params.outbound.deliveryCapabilities?.durableFinal },
        send,
        ...params.live ? { live: params.live } : {},
        receive: params.receive ?? defaultManualReceiveAdapter
    };
}
```

### 6.3.9 `messaging: ChannelMessagingAdapter`

**类型**：`ChannelMessagingAdapter`

**说明**：消息路由帮助器，是ChannelPlugin中最复杂的适配器之一（30+个方法）。它处理：
- 目标解析（`normalizeTarget`、`parseExplicitTarget`）
- 会话管理（`resolveSessionConversation`、`resolveInboundConversation`）
- 路由构建（`resolveOutboundSessionRoute`）
- 格式转换（`transformReplyPayload`、`buildCrossContextPresentation`）
- 附件处理（`resolveInboundAttachmentRoots`）
- 表格模式（`defaultMarkdownTableMode`）

```typescript
type ChannelMessagingAdapter = {
    targetPrefixes?: readonly string[];  // 如 ["discord", "dc"]
    normalizeTarget?: (raw: string) => string | undefined;
    defaultMarkdownTableMode?: MarkdownTableMode;
    // 会话解析
    resolveInboundConversation?: (params: { from?; to?; conversationId?; threadId?; isGroup }) => {...} | null;
    resolveSessionConversation?: (params: { kind; rawId }) => {...} | null;
    resolveDeliveryTarget?: (params: { conversationId; parentConversationId? }) => {...} | null;
    // 目标解析
    parseExplicitTarget?: (params: { raw }) => { to; threadId?; chatType? } | null;
    inferTargetChatType?: (params: { to }) => ChatType | undefined;
    targetResolver?: { looksLikeId?; hint?; resolveTarget? };
    // 路由
    resolveOutboundSessionRoute?: (params: {...}) => ChannelOutboundSessionRoute | Promise<...> | null;
    // 格式化
    transformReplyPayload?: (params: { payload; cfg; accountId? }) => ReplyPayload | null;
    buildCrossContextPresentation?: (params: { originLabel; message; cfg; accountId? }) => MessagePresentation;
    enableInteractiveReplies?: (params: { cfg; accountId? }) => boolean;
    // 附件
    resolveInboundAttachmentRoots?: (params: { cfg; accountId? }) => string[];
    // ... 更多方法
};
```

### 6.3.10 `actions: ChannelMessageActionAdapter`

**类型**：`ChannelMessageActionAdapter`

**说明**：处理55+种消息动作，是Agent工具调用的转发枢纽。

**源码中定义的动作列表**（来自`types.core-BdWvYpdc.d.ts`）：

```typescript
const CHANNEL_MESSAGE_ACTION_NAMES = [
    "send", "broadcast", "poll", "poll-vote", "react", "reactions",
    "read", "edit", "unsend", "reply", "sendWithEffect",
    "renameGroup", "setGroupIcon", "addParticipant", "removeParticipant",
    "leaveGroup", "sendAttachment", "delete", "pin", "unpin", "list-pins",
    "permissions", "thread-create", "thread-list", "thread-reply",
    "search", "sticker", "sticker-search", "member-info", "role-info",
    "emoji-list", "emoji-upload", "sticker-upload",
    "role-add", "role-remove", "channel-info", "channel-list",
    "channel-create", "channel-edit", "channel-delete",
    "channel-move", "category-create", "category-edit", "category-delete",
    "topic-create", "topic-edit", "voice-status",
    "event-list", "event-create", "timeout", "kick", "ban",
    "set-profile", "set-presence", "download-file", "upload-file"
] as const;
```

`ChannelMessageActionAdapter`的核心方法：

```typescript
type ChannelMessageActionAdapter = {
    // 向核心描述该频道支持哪些动作
    describeMessageTool: (params: ChannelMessageActionDiscoveryContext) => ChannelMessageToolDiscovery | null;

    // 检查是否支持某个动作
    supportsAction?: (params: { action }) => boolean;

    // 决定动作在本地执行还是通过gateway执行
    resolveExecutionMode?: (params: { action }) => "local" | "gateway";

    // 将通用的send动作参数转化为持久化负载
    prepareSendPayload?: (params: ChannelMessagePreparedSendPayloadContext) => ReplyPayload | null | Promise<...>;

    // 执行特定动作
    handleAction?: (ctx: ChannelMessageActionContext) => Promise<AgentToolResult<unknown>>;

    // 提取send目标
    extractToolSend?: (params: { args }) => ChannelToolSend | null;

    // 哪些动作需要可信发送者ID
    requiresTrustedRequesterSender?: (params: { action; toolContext? }) => boolean;
};
```

### 6.3.11 `status: ChannelStatusAdapter<ResolvedAccount, Probe, Audit>`

**类型**：`ChannelStatusAdapter`

**说明**：健康探测和状态监控适配器。Probe和Audit是泛型参数，允许每个频道定义自己的探测和审计数据结构。

```typescript
type ChannelStatusAdapter<ResolvedAccount, Probe, Audit> = {
    // 默认运行时快照
    defaultRuntime?: ChannelAccountSnapshot;
    // 构建频道摘要
    buildChannelSummary?: (params: { account; cfg; defaultAccountId; snapshot }) => Record<string, unknown>;
    // 探测账户健康状态（网络连通性、认证有效性等）
    probeAccount?: (params: { account; timeoutMs; cfg }) => Promise<Probe>;
    // 格式化能力探测结果
    formatCapabilitiesProbe?: (params: { probe }) => ChannelCapabilitiesDisplayLine[];
    // 审计账户（权限检查、安全检查）
    auditAccount?: (params: { account; timeoutMs; cfg; probe? }) => Promise<Audit>;
    // 构建能力诊断
    buildCapabilitiesDiagnostics?: (params: { account; timeoutMs; cfg; probe?; audit?; target? }) => Promise<ChannelCapabilitiesDiagnostics>;
    // 构建账户快照（合并配置、运行时、探测、审计信息）
    buildAccountSnapshot?: (params: { account; cfg; runtime?; probe?; audit? }) => ChannelAccountSnapshot | Promise<...>;
    // 记录自身ID（用于调试）
    logSelfId?: (params: { account; cfg; runtime; includeChannelPrefix? }) => void;
    // 解析账户状态
    resolveAccountState?: (params: { account; cfg; configured; enabled }) => ChannelAccountState;
    // 收集状态问题
    collectStatusIssues?: (accounts: ChannelAccountSnapshot[]) => ChannelStatusIssue[];
};
```

### 6.3.12 `pairing: ChannelPairingAdapter`

**类型**：`ChannelPairingAdapter`

**说明**：用户配对/授权适配器，处理新用户与Agent的配对流程。

```typescript
type ChannelPairingAdapter = {
    idLabel: string;  // 配对标识的标签（如 "LINE UID"）
    // 标准化白名单条目
    normalizeAllowEntry?: (entry: string) => string;
    // 通知审批结果
    notifyApproval?: (params: { cfg; id; accountId?; runtime? }) => Promise<void>;
};
```

**源码中的实现**（`core-B_GhzhOy.js`）：

```typescript
function createInlineTextPairingAdapter(params) {
    return {
        idLabel: params.idLabel,
        normalizeAllowEntry: params.normalizeAllowEntry,
        notifyApproval: async (ctx) => {
            await params.notify({ ...ctx, message: params.message });
        }
    };
}
```

### 6.3.13 `groups: ChannelGroupAdapter`

**类型**：`ChannelGroupAdapter`

**说明**：群组策略适配器，决定群聊中的行为规则。

```typescript
type ChannelGroupAdapter = {
    // 是否需要@提及才能触发Agent
    resolveRequireMention?: (params: ChannelGroupContext) => boolean | undefined;

    // 群组介绍提示
    resolveGroupIntroHint?: (params: ChannelGroupContext) => string | undefined;

    // 群组工具策略
    resolveToolPolicy?: (params: ChannelGroupContext) => GroupToolPolicyConfig | undefined;
};
```

`ChannelGroupContext`包含群组上下文信息：`groupId`、`groupChannel`、`groupSpace`、`senderId`、`senderName`、`senderUsername`、`senderE164`等。

### 6.3.14 `outbound: ChannelOutboundAdapter`

**类型**：`ChannelOutboundAdapter`

**说明**：外发消息发送适配器，处理文本/媒体/poll的发送、分块、格式化等。

```typescript
type ChannelOutboundAdapter = {
    deliveryMode: "direct" | "gateway" | "hybrid";  // 发送模式

    // 发送方法
    sendText?: (ctx: ChannelOutboundContext) => Promise<OutboundDeliveryResult>;
    sendMedia?: (ctx: ChannelOutboundContext) => Promise<OutboundDeliveryResult>;
    sendPayload?: (ctx: ChannelOutboundPayloadContext) => Promise<OutboundDeliveryResult>;
    sendPoll?: (ctx: ChannelPollContext) => Promise<ChannelPollResult>;
    sendFormattedText?: (ctx: ChannelOutboundFormattedContext) => Promise<OutboundDeliveryResult[]>;

    // 分块
    chunker?: ((text: string, limit: number, ctx?) => string[]) | null;
    chunkerMode?: "text" | "markdown";
    textChunkLimit?: number;
    chunkedTextFormatting?: OutboundDeliveryFormattingOptions;

    // 负载标准化
    normalizePayload?: (params: {...}) => ReplyPayload | null;
    extractMarkdownImages?: boolean;

    // 发送钩子
    beforeDeliverPayload?: (params: {...}) => Promise<void> | void;
    afterDeliverPayload?: (params: {...}) => Promise<void> | void;

    // 展示能力
    presentationCapabilities?: ChannelPresentationCapabilities;
    deliveryCapabilities?: ChannelDeliveryCapabilities;
    renderPresentation?: (params: {...}) => Promise<ReplyPayload | null>;

    // 固定消息
    pinDeliveredMessage?: (params: {...}) => Promise<void> | void;
};
```

### 6.3.15 `directory: ChannelDirectoryAdapter`

**类型**：`ChannelDirectoryAdapter`

**说明**：联系人/群组目录适配器，提供好友列表、群组列表、群成员列表的查询能力。

```typescript
type ChannelDirectoryAdapter = {
    self?: (params: ChannelDirectorySelfParams) => Promise<ChannelDirectoryEntry | null>;
    listPeers?: (params: ChannelDirectoryListParams) => Promise<ChannelDirectoryEntry[]>;
    listPeersLive?: (params: ChannelDirectoryListParams) => Promise<ChannelDirectoryEntry[]>;
    listGroups?: (params: ChannelDirectoryListParams) => Promise<ChannelDirectoryEntry[]>;
    listGroupsLive?: (params: ChannelDirectoryListParams) => Promise<ChannelDirectoryEntry[]>;
    listGroupMembers?: (params: ChannelDirectoryListGroupMembersParams) => Promise<ChannelDirectoryEntry[]>;
};
```

`ChannelDirectoryEntry`结构：
```typescript
type ChannelDirectoryEntry = {
    kind: "user" | "group" | "channel";
    id: string;
    name?: string;
    handle?: string;
    avatarUrl?: string;
    rank?: number;
    raw?: unknown;  // 平台原生数据
};
```

### 6.3.16 `threading: ChannelThreadingAdapter`

**类型**：`ChannelThreadingAdapter`

**说明**：线程/话题管理适配器，控制回复模式（`"off"`、`"first"`、`"all"`、`"batched"`）。

```typescript
type ChannelThreadingAdapter = {
    // 解析回复模式：off=不回复引用, first=仅首条回复引用, all=全部回复引用, batched=批量引用
    resolveReplyToMode?: (params: { cfg; accountId?; chatType? }) => "off" | "first" | "all" | "batched";

    // 当replyToMode为"off"时，是否允许显式回复标签
    allowExplicitReplyTagsWhenOff?: boolean;

    // 构建AI Tool的线程上下文
    buildToolContext?: (params: { cfg; accountId?; context; hasRepliedRef? }) => ChannelThreadingToolContext;

    // 自动解析Thread ID
    resolveAutoThreadId?: (params: { cfg; accountId?; to; toolContext?; replyToId? }) => string | undefined;

    // 解析回复传输信息
    resolveReplyTransport?: (params: { cfg; accountId?; threadId?; replyToId? }) => ChannelReplyTransport | null;

    // 解析聚焦绑定
    resolveFocusedBinding?: (params: { cfg; accountId?; context }) => ChannelFocusedBindingContext | null;
};
```

**源码中的`buildThreadAwareOutboundSessionRoute`**（`core-B_GhzhOy.js`）展示了线程感知路由构建：

```typescript
function buildThreadAwareOutboundSessionRoute(params) {
    // 1. 尝试恢复当前线程的会话ID
    const recoveredThreadId = recoverCurrentThreadSessionId({...});

    // 2. 按优先级选择线程候选 (replyToId > threadId > currentSession)
    const candidates = {
        replyToId: resolveThreadAwareOutboundCandidate(params.replyToId),
        threadId: resolveThreadAwareOutboundCandidate(params.threadId),
        currentSession: resolveThreadAwareOutboundCandidate(recoveredThreadId)
    };
    const candidate = (params.precedence ?? ["replyToId", "threadId", "currentSession"])
        .map(source => candidates[source]).find(Boolean);

    // 3. 构建最终的sessionKey
    const threadKeys = resolveThreadSessionKeys({...});
    return { ...params.route, sessionKey: threadKeys.sessionKey, ... }
}
```

### 6.3.17 `heartbeat: ChannelHeartbeatAdapter`

**类型**：`ChannelHeartbeatAdapter`

**说明**：心跳/在线状态适配器。

```typescript
type ChannelHeartbeatAdapter = {
    // 检查是否就绪
    checkReady?: (params: { cfg; accountId?; deps? }) => Promise<{ ok: boolean; reason: string }>;
    // 发送"正在输入..."状态
    sendTyping?: (params: { cfg; to; accountId?; threadId?; deps? }) => Promise<void> | void;
    // 清除"正在输入..."状态
    clearTyping?: (params: { cfg; to; accountId?; threadId?; deps? }) => Promise<void> | void;
};
```

### 6.3.18 `auth: ChannelAuthAdapter`

**类型**：`ChannelAuthAdapter`

**说明**：认证适配器，处理交互式登录流程。

```typescript
type ChannelAuthAdapter = {
    login?: (params: {
        cfg: OpenClawConfig;
        accountId?: string | null;
        runtime: RuntimeEnv;
        verbose?: boolean;
        channelInput?: string | null;
    }) => Promise<void>;
};
```

### 6.3.19 `lifecycle: ChannelLifecycleAdapter`

**类型**：`ChannelLifecycleAdapter`

**说明**：插件生命周期钩子，处理配置变更、账户删除、启动维护、旧版状态迁移等。

```typescript
type ChannelLifecycleAdapter = {
    // 账户配置变更时触发
    onAccountConfigChanged?: (params: { prevCfg; nextCfg; accountId; runtime }) => Promise<void> | void;

    // 账户被删除时触发
    onAccountRemoved?: (params: { prevCfg; accountId; runtime }) => Promise<void> | void;

    // 启动时运行维护
    runStartupMaintenance?: (params: { cfg; env?; log; trigger?; logPrefix? }) => Promise<void> | void;

    // 检测旧版状态迁移
    detectLegacyStateMigrations?: (params: { cfg; env; stateDir; oauthDir }) => ChannelLegacyStateMigrationPlan[];
};
```

### 6.3.20 其他适配器模块

除了上述19个核心模块，`ChannelPlugin`还包含以下辅助适配器：

| 模块 | 类型 | 说明 |
|------|------|------|
| **mentions** | `ChannelMentionAdapter` | @提及处理，剥离消息中的@提及文本 |
| **streaming** | `ChannelStreamingAdapter` | 流式传输配置，如`blockStreamingCoalesceDefaults` |
| **agentPrompt** | `ChannelAgentPromptAdapter` | Agent提示词增强，如`messageToolHints`、`reactionGuidance` |
| **commands** | `ChannelCommandAdapter` | 频道特有命令配置，如`enforceOwnerForCommands` |
| **elevated** | `ChannelElevatedAdapter` | 提升权限的allowFrom回退 |
| **secrets** | `ChannelSecretsAdapter` | Secret目标注册表 |
| **allowlist** | `ChannelAllowlistAdapter` | 白名单编辑接口（添加/删除/查看） |
| **doctor** | `ChannelDoctorAdapter` | 配置诊断和修复 |
| **bindings** | `ChannelConfiguredBindingProvider` | 配置绑定（ACP）提供者 |
| **conversationBindings** | `ChannelConversationBindingSupport` | 会话绑定支持 |
| **resolver** | `ChannelResolverAdapter` | 目标解析器 |
| **agentTools** | `ChannelAgentToolFactory \| ChannelAgentTool[]` | Agent工具注册 |
| **approvalCapability** | `ChannelApprovalCapability` | 审批能力 |
| **gatewayMethods** | `string[]` | Gateway方法列表 |
| **gatewayMethodDescriptors** | `ChannelGatewayMethodDescriptor[]` | Gateway方法描述符 |
| **setupWizard** | `ChannelPluginSetupWizard` | 设置向导 |
| **configSchema** | `ChannelConfigSchema` | 配置JSON Schema |
| **reload** | `{ configPrefixes; noopPrefixes? }` | 热重载配置 |
| **defaults** | `{ queue?: { debounceMs? } }` | 默认队列设置 |

---

## 6.4 消息能力矩阵（Capabilities Matrix）

ChannelPlugin在消息层面定义了三套能力体系：`durableFinal`（持久最终消息）、`live`（实时消息）、以及`chatTypes`（聊天类型）。每套体系都有自己的验证契约（Contract Proofs）。

### 6.4.1 durableFinal能力

源码定义（`channel-message-A0qNE-jd.js`，第65-78行）：

```typescript
const durableFinalDeliveryCapabilities = [
    "text",          // 文本发送
    "media",         // 媒体发送
    "payload",       // 结构化负载发送
    "silent",        // 静默发送
    "replyTo",       // 回复引用
    "thread",        // 线程支持
    "nativeQuote",   // 原生引用格式
    "messageSendingHooks",  // 消息发送钩子（before/after）
    "batch",         // 批量发送
    "reconcileUnknownSend", // 未知发送状态恢复
    "afterSendSuccess",     // 发送成功后回调
    "afterCommit"     // 提交后回调
];
```

各能力详解：

- **text**：频道能否发送纯文本消息。这是最基础的能力，如果不支持text，那么`message`工具对Agent不可见。
- **media**：频道能否发送媒体消息（图片、视频、音频、文件）。对应`outbound.sendMedia(ctx)`。
- **payload**：频道能否发送结构化负载。对应`outbound.sendPayload(ctx)`，支持`ReplyPayload`中的`presentation`和`interactive`组件。
- **silent**：频道是否支持静默发送（不触发通知）。
- **replyTo**：频道是否支持消息回复引用。对应`ctx.replyToId`参数。
- **thread**：频道是否支持线程概念（如Slack的Thread、Telegram的Topic）。
- **nativeQuote**：频道是否支持原生引用格式（而非纯文本模拟）。
- **messageSendingHooks**：频道是否支持在发送前后插入钩子函数。这允许插件在消息发送前修改负载，或在发送后进行额外操作。
- **batch**：频道是否支持批量发送。核心可以将多条消息合并为一次批量操作。
- **reconcileUnknownSend**：频道是否能恢复未知状态的发送。当发送操作中断（如进程崩溃），重启后能否查询消息是否实际已送达。
- **afterSendSuccess**：发送成功后是否触发回调。用于更新本地状态。
- **afterCommit**：消息提交（commit）后是否触发回调。commit是确认发送完成的最后一步。

### 6.4.2 live能力

```typescript
const channelMessageLiveCapabilities = [
    "draftPreview",       // 草稿预览
    "previewFinalization", // 预览最终化
    "progressUpdates",    // 进度更新
    "nativeStreaming",    // 原生流式传输
    "quietFinalization"   // 静默最终化
];
```

- **draftPreview**：是否支持在Agent生成回复过程中，向用户展示草稿/临时消息。
- **previewFinalization**：是否支持将草稿最终化为正式消息。与`live.finalizer`配合使用。
- **progressUpdates**：是否支持进度更新（如"正在思考..."、"已完成30%"等）。
- **nativeStreaming**：是否支持平台原生的流式传输。与`blockStreaming`相反--当平台原生支持流式时，如Discord的HTTP Interaction可以边生成边推送。
- **quietFinalization**：是否支持安静地将预览转化为最终消息（不触发额外通知）。

### 6.4.3 live.finalizer能力

```typescript
const livePreviewFinalizerCapabilities = [
    "finalEdit",            // 最终编辑（原地替换预览消息）
    "normalFallback",       // 普通回退（删除预览，发送新消息）
    "discardPending",       // 丢弃待处理的预览
    "previewReceipt",       // 预览回执（获取预览消息的receipt）
    "retainOnAmbiguousFailure"  // 模糊失败时保留消息
];
```

- **finalEdit**：能否编辑预览消息来展示最终结果（而非删除+重新发送）。
- **normalFallback**：当finalEdit不支持时，是否支持删除预览+发送新消息的标准回退方式。
- **discardPending**：能否丢弃正在等待的预览。
- **previewReceipt**：能否获取预览消息的receipt（消息ID等）。
- **retainOnAmbiguousFailure**：发送结果不确定时（如网络超时但消息可能已送达），是否保留消息而非标记为失败。

### 6.4.4 chatTypes能力

`chatTypes`定义在`ChannelCapabilities`中，值类型为`Array<ChatType | "thread">`：

| chatType | 含义 | 示例平台 |
|----------|------|----------|
| `"direct"` | 一对一私聊 | 所有平台 |
| `"group"` | 群组对话 | Discord/LINE/Telegram/Signal |
| `"channel"` | 频道/广播 | Discord Server Channel |
| `"thread"` | 主题/话题 | Slack Thread、Telegram Topic |
| `"space"` | 空间（Google Chat） | Google Chat |

两种特殊chatType：
- **thread**：表示该频道有独立的线程概念。在Discord中，线程是频道下的子对话。在Telegram中，Topic是超级群组下的子话题。
- **space**（Google Chat）：Google Chat特有的空间概念，类似Discord的Server。

### 6.4.5 能力契约验证

OpenClaw实现了**契约证明（Contract Proofs）机制**来确保声明的能力与实现一致。源码中（`channel-message-A0qNE-jd.js`）：

```typescript
async function verifyDurableFinalCapabilityProofs(params) {
    const results = [];
    for (const capability of durableFinalDeliveryCapabilities) {
        if (params.capabilities?.[capability] !== true) {
            results.push({ capability, status: "not_declared" });
            continue;
        }
        const proof = params.proofs[capability];
        if (!proof)
            throw new Error(
                `${params.adapterName} declares durable final capability ` +
                `"${capability}" without a contract proof`
            );
        await proof();
        results.push({ capability, status: "verified" });
    }
    return results;
}
```

这意味着：如果一个频道声明了`text: true`能力，它必须提供对应的验证函数，否则启动时会抛出错误。

---

## 6.5 消息入站流转路径

一条外部平台消息从到达OpenClaw到最终回复，经历了如下流转过程：

```
外部平台消息到达
       ↓
[1] Gateway Adapter接收 → 解析平台特有格式
       ↓
[2] Inbound Bindings → resolveInboundConversation
       ↓
[3] Security Check → DM policy / Group policy / 白名单检查
       ↓
[4] MessageReceiveContext创建 → { id, channel, accountId, message, ackPolicy }
       ↓
[5] Agent Route Resolution → 匹配 channel+accountId+conversationId
       ↓
[6] Turn Pipeline (8阶段处理) → 详见下文
       ↓
[7] Outbound Delivery → sendDurableMessageBatch
```

### 6.5.1 Step 1: Gateway Adapter接收

不同平台的消息接收方式不同：

- **Discord**：Gateway WebSocket 连接，事件驱动的消息推送
- **LINE**：Webhook（被动接收），LINE服务器HTTP POST消息到OpenClaw
- **Telegram**：长轮询（getUpdates）或Webhook
- **Signal**：通过signal-cli本地进程主动拉取
- **Slack**：Socket Mode（WebSocket）或HTTP Events API

Gateway Adapter负责将平台特有的消息格式解析为标准化的内部格式。

### 6.5.2 Step 2: Inbound Bindings

`messaging.resolveInboundConversation`被调用来确定消息属于哪个会话：

```typescript
resolveInboundConversation?: (params: {
    from?: string;          // 发送者标识
    to?: string;            // 接收者标识（Bot自身）
    conversationId?: string; // 平台会话ID
    threadId?: string | number; // Thread/Topic ID
    isGroup: boolean;       // 是否群组消息
}) => {
    conversationId?: string;
    parentConversationId?: string;
} | null;
```

这一步将平台特有的会话标识转化为OpenClaw内部的统一conversationId。

### 6.5.3 Step 3: Security Check

安全策略检查（详见第6.6节三层安全模型）：
1. **私聊消息**：检查`dmPolicy`是否为`"open"`或发送者在`allowFrom`白名单中
2. **群组消息**：检查`groupPolicy`和`groupAllowFrom`
3. **名称匹配**：检查`dangerouslyAllowNameMatching`配置

安全检查失败的消息将被静默丢弃或返回拒绝提示。

### 6.5.4 Step 4: MessageReceiveContext创建

```typescript
// 源码: channel-message-A0qNE-jd.js (第238-263行)
function createMessageReceiveContext(params) {
    const ctx = {
        id: params.id,                // 消息唯一ID
        channel: params.channel,      // 频道标识 ("discord", "line", ...)
        accountId: params.accountId,  // 账户ID
        message: params.message,       // 解析后的消息对象
        ackPolicy: params.ackPolicy ?? "after_receive_record",
        ackState: "pending",          // ack状态: pending → acked / nacked
        receivedAt: params.receivedAt ?? Date.now(),
        signal: params.signal ?? neverAbortedSignal,
        shouldAckAfter: (stage) => shouldAckMessageAfterStage(ctx.ackPolicy, stage),
        ack: async () => {
            if (ctx.ackState === "acked") return;
            await params.onAck?.();
            ctx.ackState = "acked";
            ctx.ackedAt = Date.now();
        },
        nack: async (error) => {
            await params.onNack?.(error);
            ctx.ackState = "nacked";
            ctx.nackErrorMessage = normalizeAckErrorMessage(error);
        }
    };
    return ctx;
}
```

`ackPolicy`有四种模式：

| 策略 | 含义 | ACK时机 |
|------|------|---------|
| `"after_receive_record"` | 接收记录后确认 | 消息记录到本地后立即ACK |
| `"after_agent_dispatch"` | Agent调度后确认 | 消息被路由到Agent管线后ACK |
| `"after_durable_send"` | 持久发送后确认 | 回复消息成功送达后ACK |
| `"manual"` | 手动确认 | 插件自行控制ACK时机 |

`shouldAckAfter`函数判断当前stage是否应该触发ACK：

```typescript
function shouldAckMessageAfterStage(policy, stage) {
    switch (policy) {
        case "after_receive_record": return stage === "receive_record";
        case "after_agent_dispatch": return stage === "agent_dispatch";
        case "after_durable_send": return stage === "durable_send";
        case "manual": return false;
    }
}
```

### 6.5.5 Step 5: Agent Route Resolution

核心通过三元组`(channel, accountId, conversationId)`匹配到正确的Agent：

- **channel**：`"discord"`, `"line"` 等
- **accountId**：Bot账户标识（如Discord Bot的Token对应账户）
- **conversationId**：统一会话标识（如`"discord:channel:123456"`）

匹配到的Agent如果有活跃会话，则复用现有会话；否则创建新会话。

### 6.5.6 Step 6: Turn Pipeline (8阶段)

Turn Pipeline是OpenClaw核心的8阶段消息处理管线：

1. **输入预处理**：消息文本清洗、@提及剥离（`mentions.stripMentions`）、格式标准化
2. **上下文装配**：加载会话历史、Agent配置、Channel专属Prompt增强（`agentPrompt`）
3. **安全再次检查**：对Agent生成前的输入做最终安全校验
4. **模型推理**：将装配好的上下文发送给LLM
5. **响应解析**：解析LLM输出，提取text、tool calls、presentation等
6. **工具执行**：执行Agent调用的工具（如`message(action=send)`)
7. **响应格式化**：将原始响应转化为`ReplyPayload`格式
8. **后处理**：敏感词过滤、长度截断、Markdown转义等

### 6.5.7 Step 7: Outbound Delivery

最终的回复消息通过`sendDurableMessageBatch`函数发送：

```typescript
// 源码: channel-message-A0qNE-jd.js (第332-334行)
async function sendDurableMessageBatch(params) {
    return await (await import("./runtime-D1yJLQPy.js")).sendDurableMessageBatch(params);
}
```

发送流程涉及：
1. 消息分块（chunking）：根据`textChunkLimit`和`chunkMode`分割长文本
2. 负载标准化（normalizePayload）：将通用格式适配为平台特有格式
3. 展示渲染（renderPresentation）：将Markdown/结构化组件转为平台UI组件
4. 实际发送：调用`outbound.sendText`/`sendMedia`/`sendPayload`
5. Receipt生成：生成`MessageReceipt`（包含platformMessageIds、threadId等）
6. 持久状态记录：记录发送状态用于恢复和去重

---

## 6.6 三层安全模型

OpenClaw实现了三层递进的安全控制模型，防止未授权的用户触发Agent。

### 6.6.1 第一层：DM/私聊策略（dmPolicy）

`ChannelSecurityDmPolicy`结构（来自`types.core-BdWvYpdc.d.ts`）：

```typescript
type ChannelSecurityDmPolicy = {
    policy: string;                          // "open" | "allowlist" | "disabled"
    allowFrom?: Array<string | number> | null;  // 白名单列表
    policyPath?: string;                     // 策略在配置中的路径
    allowFromPath: string;                   // allowFrom在配置中的路径
    approveHint: string;                     // 审批提示文本
    normalizeEntry?: (raw: string) => string;  // 标准化白名单条目
};
```

**三种DM策略**：

| 策略 | 含义 | 行为 |
|------|------|------|
| `"open"` | 开放模式 | 任何用户都可以私聊Agent |
| `"allowlist"` | 白名单模式 | 只有`allowFrom`中的用户才能私聊 |
| `"disabled"` | 禁用模式 | 不接受任何私聊请求 |
| `"pairing"` | 配对模式（继承自`allowlist`） | 用户需要先通过配对审批才能使用 |

**源码中的策略构建函数**（`helpers-DrdxqwF3.js`）：

```typescript
function buildAccountScopedDmSecurityPolicy(params) {
    const resolvedAccountId = params.accountId ?? params.fallbackAccountId ?? "default";
    // 多层配置查找：账户级 → 默认账户级 → 频道根级
    const channelConfig = params.cfg.channels?.[params.channelKey];
    const accountConfig = channelConfig?.accounts?.[resolvedAccountId];
    const defaultAccountConfig = /* ... */;

    // 确定allowFrom路径
    const basePath = /* 按优先级查找 */;
    const allowFromPath = `${basePath}${params.allowFromPathSuffix ?? ""}`;
    const policyPath = /* ... */;

    return {
        policy: params.policy ?? params.defaultPolicy ?? "pairing",
        allowFrom: params.allowFrom ?? [],
        policyPath,
        allowFromPath,
        approveHint: params.approveHint ?? formatPairingApproveHint(
            params.approveChannelId ?? params.channelKey
        ),
        normalizeEntry: params.normalizeEntry
    };
}
```

`formatPairingApproveHint`生成的提示文本格式为：
```
Approve via: openclaw pairing list line / openclaw pairing approve line <code>
```

### 6.6.2 第二层：群组/频道策略（groupPolicy）

群组策略与DM策略类似，但增加了额外控制：

- **groupPolicy**：`"open"` | `"allowlist"` | `"disabled"`
- **groupAllowFrom**：群组白名单（群组ID列表）
- **requireMention**：是否需要@提及Agent才能触发回复
- **routeAllowlist**：是否对路由也应用白名单

`ChannelGroupAdapter`中的`resolveRequireMention`决定了在群组中是否需要@提及：

```typescript
type ChannelGroupAdapter = {
    resolveRequireMention?: (params: ChannelGroupContext) => boolean | undefined;
    resolveGroupIntroHint?: (params: ChannelGroupContext) => string | undefined;
    resolveToolPolicy?: (params: ChannelGroupContext) => GroupToolPolicyConfig | undefined;
};
```

### 6.6.3 第三层：Name Matching安全

`dangerouslyAllowNameMatching`是OpenClaw中的高风险配置项。当启用时，允许Agent通过用户名（而非ID）匹配目标。这带来了安全风险，因为用户名不是唯一的，可能被伪造。

**源码中的实现**（`dangerous-name-matching-C7LPLBcy.js`）：

```typescript
function isDangerousNameMatchingEnabled(config) {
    return config?.dangerouslyAllowNameMatching === true;
}

function resolveDangerousNameMatchingEnabled(input) {
    // 账户级配置优先于频道级配置
    if (typeof input.accountConfig?.dangerouslyAllowNameMatching === "boolean")
        return input.accountConfig.dangerouslyAllowNameMatching;
    return isDangerousNameMatchingEnabled(input.providerConfig);
}

function collectProviderDangerousNameMatchingScopes(cfg, provider) {
    // 收集所有启用了dangerous name matching的配置范围
    const scopes = [];
    // 遍历频道级和账户级的配置
    // 为每个scope记录：prefix, account, dangerousNameMatchingEnabled, dangerousFlagPath
    return scopes;
}
```

**典型配置路径**：
```
channels.discord.dangerouslyAllowNameMatching
channels.discord.accounts.default.dangerouslyAllowNameMatching
channels.telegram.accounts.bot1.dangerouslyAllowNameMatching
```

### 6.6.4 Doctor诊断中的安全警告

`ChannelDoctorAdapter`负责检测和报告配置中的安全问题：

- `dmAllowFromMode`：DM白名单的查找模式（`"topOnly"` | `"topOrNested"` | `"nestedOnly"`）
- `groupModel`：群组白名单模型（`"sender"` | `"route"` | `"hybrid"`）
- `collectPreviewWarnings`：预览配置时收集的警告
- `collectMutableAllowlistWarnings`：可变更白名单的警告（如空allowlist）
- `warnOnEmptyGroupSenderAllowlist`：是否在空群组白名单时发出警告

---

## 6.7 平台适配器对比

下表对比了各主要平台在ChannelPlugin接口下的实现差异：

| 维度 | Discord | LINE | Telegram | Signal | Google Chat | Slack |
|------|---------|------|----------|--------|-------------|-------|
| **通信模式** | Gateway WebSocket | Webhook（被动） | Bot API长轮询/Webhook | signal-cli本地进程 | Webhook（被动） | Socket Mode / Events API |
| **认证方式** | Discord Bot Token | LINE Channel Token + Secret | Telegram Bot Token | signal-cli本地注册 | Google Service Account | Slack Bot Token + Signing Secret |
| **支持的chatTypes** | direct + group + thread | direct + group | direct + group + thread(topic) | direct + group | direct + group + thread(space) | direct + group + thread |
| **流式传输** | 支持（原生streaming） | 不支持（blockStreaming=true） | 支持 | 支持 | 支持 | 支持 |
| **原生特性** | Embed, Component, Poll, Modal | Flex Message, Quick Reply, Rich Menu | Inline Keyboard, Web App | 富文本样式 | Thread, Space, Card | Block Kit, Modal, Shortcut |
| **目标ID格式** | `discord:channel:<id>` | `line:user:<Uxxx>` | `telegram:chat:<id>` | E.164 / UUID | `spaces/<id>` | `slack:channel:<id>` |
| **DM默认策略** | 可配置 | pairing（配对模式） | 可配置 | 可配置 | pairing（配对模式） | 可配置 |
| **群聊@提及** | 默认需@提及 | 需解析mention token | Bot默认接收所有消息 | 无原生@机制 | 需@提及 | 需@提及 |
| **消息编辑** | 支持（edit action） | 不支持 | 支持 | 支持（部分） | 支持 | 支持 |
| **消息撤回** | 支持（unsend action） | 不支持 | 支持 | 支持（部分） | 支持 | 支持 |
| **表情反应** | 支持（原生emoji） | 不支持 | 不支持原生reaction | 支持（emoji reaction） | 不支持 | 支持 |
| **投票** | 支持（原生Poll） | 不支持 | 支持（Poll 2.0） | 不支持 | 不支持 | 不支持 |
| **媒体发送** | 支持（图片/视频/文件） | 支持（受限于LINE API） | 支持（50MB限制） | 支持 | 支持 | 支持 |
| **Gateway方法** | startAccount/stopAccount | Webhook接收+outbound发送 | startAccount/stopAccount | 进程管理 | Webhook接收+outbound发送 | startAccount/stopAccount |
| **目录查询** | 支持（Guild成员列表） | 不支持 | 部分支持（getChat） | 支持（联系人列表） | 支持（Google Directory） | 支持（users.list） |
| **扫码登录** | 不支持 | 不支持 | 不支持 | 支持（QR码配对） | 不支持 | 不支持 |
| **Target Prefixes** | `["discord", "dc"]` | `["line"]` | `["telegram", "tg"]` | `["signal"]` | `["googlechat", "gchat"]` | `["slack"]` |

### 6.7.1 Webhook模式的特殊性

**LINE**和**Google Chat**采用Webhook被动接收模式，这意味着：
- 消息到达由外部平台发起HTTP POST，OpenClaw不需要维持长连接
- `gateway`适配器可能只包含`startAccount`来做初始化，真正的消息接收在HTTP handler中
- **LINE的blockStreaming**：由于LINE使用Webhook，回复需要在单个HTTP响应中完成，不支持流式传输，因此设置`blockStreaming: true`

### 6.7.2 Gateway WebSocket模式

**Discord**和**Slack**通过WebSocket接收消息：
- `gateway.startAccount(ctx)`建立WebSocket连接
- 连接建立后由事件驱动，核心通过`channelRuntime`调用AI功能
- `gateway.stopAccount(ctx)`关闭连接
- 支持完整的实时能力（typing indicator、心跳保活等）

### 6.7.3 本地进程模式

**Signal**通过本地signal-cli进程与Signal网络通信：
- 不需要服务端认证（使用本地密钥）
- Gateway管理signal-cli进程的生命周期
- 支持`loginWithQrStart/loginWithQrWait`进行设备配对

### 6.7.4 Bot API模式

**Telegram**通过Bot API（HTTP）进行通信：
- 可以使用长轮询（getUpdates）或设置Webhook
- `gateway.startAccount`启动轮询循环
- 支持Poll 2.0、Inline Keyboard等丰富的交互组件
- `sendTyping/clearTyping`支持"正在输入..."状态

---

## 6.8 ChannelPlugin注册流程

### 6.8.1 完整注册链路

从`defineChannelPluginEntry`到运行时可用，经历了以下步骤：

```
defineChannelPluginEntry({ id, plugin, ... })
       ↓
api.registerChannel({ plugin })
       ↓
ChannelPluginRegistry.add(channelPlugin)
       ↓
运行时Bootstrap
       ↓
[1] 加载配置 → resolveAccount
[2] 启动gateway → startAccount(ctx)
[3] 注册actions → describeMessageTool
[4] 注册消息适配器 → createChannelMessageAdapterFromOutbound
[5] 建立入站处理链 → security + routing + pipeline
```

### 6.8.2 创建ChannelPlugin的辅助函数

源码提供了三个层次的建设辅助函数：

1. **`createChannelPluginBase(params)`**：创建最基础的plugin对象
   ```typescript
   function createChannelPluginBase(params) {
       return {
           id: params.id,
           meta: { ...resolveSdkChatChannelMeta(params.id), ...params.meta },
           setup: params.setup,
           // ... 可选适配器的展开
       };
   }
   ```

2. **`createChatChannelPlugin(params)`**：在base之上添加标准化处理
   ```typescript
   function createChatChannelPlugin(params) {
       return {
           ...params.base,
           conversationBindings: {
               supportsCurrentConversationBinding: true,
               ...params.base.conversationBindings
           },
           ...params.security ? { security: resolveChatChannelSecurity(params.security) } : {},
           ...params.pairing ? { pairing: resolveChatChannelPairing(params.pairing) } : {},
           ...params.threading ? { threading: resolveChatChannelThreading(params.threading) } : {},
           ...params.outbound ? { outbound: resolveChatChannelOutbound(params.outbound) } : {}
       };
   }
   ```

3. **`defineChannelPluginEntry(params)`**：包装为可注册的插件入口
   - 处理多阶段注册（cli-metadata / tool-discovery / discovery / full）
   - 自动注入SDK元数据（通过`resolveSdkChatChannelMeta`）
   - 支持`setRuntime`回调，在注册时建立运行时绑定

### 6.8.3 兼容桥接

源码中包含多个标记为`@deprecated`的兼容桥接函数，用于支持旧版插件：

```typescript
// 旧版：基于turn的reply pipeline
const createChannelTurnReplyPipeline = createChannelReplyPipeline;

// 新版：基于message adapter的发送
async function sendDurableMessageBatch(params) {
    return await import("./runtime-D1yJLQPy.js").sendDurableMessageBatch(params);
}

// 旧版兼容：dispatchChannelMessageReplyWithBase
const dispatchChannelMessageReplyWithBase = async (...args) => {
    return await import("./plugin-sdk/inbound-reply-dispatch.js")
        .dispatchChannelMessageReplyWithBase(...args);
};
```

这些兼容桥接的存在说明OpenClaw正在从基于"Turn/Reply Pipeline"的旧架构过渡到基于"ChannelMessageAdapter / DurableMessageBatch"的新架构。

---

## 6.9 关键源码文件索引

| 文件 | 内容 | 行数 |
|------|------|------|
| `types.public-lNnJ5wLZ.d.ts` | `ChannelPlugin<T>`完整接口定义 | 65行 |
| `types.core-BdWvYpdc.d.ts` | 核心类型（Capabilities, Meta, Actions, Threading等） | 759行 |
| `types.adapters-IpMCms83.d.ts` | 22个适配器接口定义 | 836行 |
| `types-4cKgiLCG.d.ts` | 消息类型（DurableFinal, Live, Send, Receipt等） | 245行 |
| `outbound.types-Djrbiefw.d.ts` | Outbound适配器与交付类型 | 288行 |
| `pairing.types-Bva8gGIE.d.ts` | Pairing适配器 | 16行 |
| `core-B_GhzhOy.js` | 核心辅助函数（createChannelPluginBase, defineChannelPluginEntry等） | 282行 |
| `channel-message-A0qNE-jd.js` | 消息适配器实现（createMessageReceiveContext, sendDurableMessageBatch等） | 349行 |
| `channel-access-OyXTEy9U.js` | Discord频道属性安全访问 | 62行 |
| `helpers-DrdxqwF3.js` | 安全策略构建（buildAccountScopedDmSecurityPolicy等） | 42行 |
| `dangerous-name-matching-C7LPLBcy.js` | Name matching安全配置 | 49行 |

---

## 6.10 总结

本章深入剖析了OpenClaw Channel消息系统的架构层次和ChannelPlugin接口设计。核心要点总结如下：

1. **三层架构**：核心引擎（Agent Router + Turn Pipeline + Session Manager）通过ChannelPlugin接口与20+个平台实现解耦。

2. **模块化适配器**：ChannelPlugin包含20+个独立适配器模块（gateway、message、messaging、security、outbound、threading等），每个模块负责一类明确定义的功能。插件只需实现需要的模块。

3. **能力契约**：通过capabilities矩阵和contract proofs机制，确保声明的功能与实现一致。三套能力体系（durableFinal / live / chatTypes）覆盖了从简单文本发送到流式预览的全场景。

4. **消息流转**：7步标准流程（Gateway接收 -> Inbound Bindings -> 安全检查 -> ReceiveContext -> Route匹配 -> Turn Pipeline -> Outbound发送），每一步都有明确定义的接口和扩展点。

5. **三层安全模型**：DM策略 + 群组策略 + Name Matching安全，提供了从开放到严格的多级访问控制。`buildAccountScopedDmSecurityPolicy`实现了配置优先级回退的复杂查找逻辑。

6. **平台差异封装**：Webhook模式（LINE/Google Chat）、Gateway WebSocket模式（Discord/Slack）、本地进程模式（Signal）、Bot API模式（Telegram）等完全不同的通信范式被统一封装在相同的适配器接口后面。

下一章将继续深入Channel消息系统的下发篇，重点分析Outbound的发送链路、DurableMessage持久化、以及Live Preview机制的实现细节。
# 六、Channel消息系统（下）：8阶段流水线、55+动作与回复管线

> 本章深入剖析 OpenClaw Channel 消息系统的核心架构：Turn Pipeline 生命周期、ACK 策略控制、丰富的 Channel Actions 集合、回复管线（Reply Pipeline），以及消息发送的持久化状态机。

---

## 6.1 8阶段 Turn Pipeline

每条入站消息在 OpenClaw 中经历一个严格编排的 **8 阶段流水线**（Turn Pipeline）。这个流水线保证了消息处理的可靠性、安全性和可追溯性。流水线的核心入口函数是 `runChannelTurn`，定义于 `kernel-D6BSEmnc.js` 中的 `src/channels/turn/kernel.ts`。

### 流水线概览

```
┌─────────────────────────────────────────────────────────────────┐
│                     Turn Pipeline (8 Stages)                     │
├──────────┬──────────┬──────────┬──────────┬──────────┬──────────┤
│ 1.RECEIVE│ 2.DEDUP │ 3.SECURITY│ 4.ROUTE │ 5.AGENT  │ 6.REPLY  │
│  _RECORD │  LICATE  │  _CHECK   │_RESOLVE │_DISPATCH │_GENERATE │
├──────────┴──────────┴──────────┴──────────┴──────────┴──────────┤
│          7.DURABLE_SEND          │          8.COMMIT              │
└─────────────────────────────────────────────────────────────────┘
```

每个阶段由 `emit()` 函数发出生命周期事件（包含 `stage`、`event`、`messageId`、`sessionKey`），实现全链路可观测。

---

### Stage 1: RECEIVE_RECORD（消息接收与记录）

这是流水线的入口阶段。Channel 适配器从底层平台接收原始消息 (`raw`)，通过 `adapter.ingest(raw)` 提取规范化消息输入。

**核心代码**（摘自 `kernel-D6BSEmnc.js`）：

```js
async function runChannelTurn(params) {
    emit({...params, event: { stage: "ingest", event: "start" }});
    const input = await params.adapter.ingest(params.raw);
    if (!input) {
        // 消息无法提取 → drop
        const admission = { kind: "drop", reason: "ingest-null" };
        emit({...params, event: { stage: "ingest", event: "drop",
            admission: admission.kind, reason: admission.reason }});
        return { admission, dispatched: false };
    }
    emit({...params, event: { stage: "ingest", event: "done",
        messageId: input.id }});
```

**关键操作**：
- 从原始 Webhook/WebSocket 数据中提取 `id`、`rawText`、`textForAgent`、`timestamp` 等信息
- 为消息生成全局唯一 `messageId`（如 LINE 的 `message.id`、Discord 的 `message.name`）
- **ACK 触发点**：`ackPolicy="after_receive_record"` 在此阶段结束后立即发送 ACK 给平台，告知"消息已收到"（详见下文 ACK 策略表）

**ACK 在此阶段触发的实例**（摘自 LINE webhook handler，`bot-BRuINTsq.js`）：

```js
receiveContext = createMessageReceiveContext({
    id: `${Date.now()}:line:webhook`,
    channel: "line",
    message: body,
    ackPolicy: "after_receive_record",
    onAck: () => {
        res.statusCode = 200;
        res.setHeader("Content-Type", "application/json");
        res.end(JSON.stringify({ status: "ok" }));
    }
});
if (receiveContext.shouldAckAfter("receive_record")) await receiveContext.ack();
```

此阶段向 `Session Store` 记录入站 Session 元数据，为后续路由和会话管理提供基础。

---

### Stage 2: DEDUPLICATE（消息去重）

消息去重确保同一条消息不会被重复处理。系统基于消息 ID 进行幂等性检查。

**核心实现**（摘自 `dispatch-BlRYQnj0.js`）：

```js
const inboundDedupeClaim = claimInboundDedupe(ctx);
if (inboundDedupeClaim.status === "duplicate" ||
    inboundDedupeClaim.status === "inflight") {
    recordProcessed("skipped", { reason: "duplicate" });
    return attachSourceReplyDeliveryMode({
        queuedFinal: false,
        counts: dispatcher.getQueuedCounts()
    });
}
```

**去重机制层次**：

1. **内存级去重**（`inbound-dedupe`）：在单次 dispatch 周期内，通过 `claimInboundDedupe` / `commitInboundDedupe` / `releaseInboundDedupe` 管理消息声明周期。三种状态：
   - `claimed`：首次声明，正常处理
   - `duplicate`：已提交的重复消息，跳过
   - `inflight`：正在处理中的消息，跳过（防止并发重复）

2. **持久化去重**（`createClaimableDedupe`）：用于 Webhook 重放防护。LINE 和 Telegram 等平台均使用此机制，TTL 可达 24 小时，支持文件持久化：

```js
// LINE 重放防护 (bot-BRuINTsq.js)
const LINE_WEBHOOK_REPLAY_WINDOW_MS = 600 * 1e3;  // 10分钟
function createLineWebhookReplayCache() {
    return createClaimableDedupe({
        ttlMs: LINE_WEBHOOK_REPLAY_WINDOW_MS,
        memoryMaxSize: LINE_WEBHOOK_REPLAY_MAX_ENTRIES
    });
}
```

3. **幂等性 Key 机制**：每个消息的去重 key 由 `channel + accountId + messageId` 构成，确保跨账号不冲突。

**去重与重放恢复**：当 `inboundDedupeReplayUnsafe` 标记为 `true` 时（即产生了对外可见的回复），系统不会 `release` 去重锁，而是 `commit` 它——因为回复已经送出，重放将导致重复发送。

---

### Stage 3: SECURITY_CHECK（安全检查）

安全检查阶段验证消息是否应该被处理。包含三层检查：

**3.1 事件分类（Classify）**

```js
const eventClass = await params.adapter.classify?.(input) ?? DEFAULT_EVENT_CLASS;
if (!eventClass.canStartAgentTurn) {
    const admission = { kind: "handled", reason: `event:${eventClass.kind}` };
    return { admission, dispatched: false };
}
```

非 `message` 类型的事件（如 `follow`、`unfollow`、`join`、`leave`）不会启动 Agent Turn。

**3.2 预检（Preflight）**

```js
const preflight = normalizePreflight(await params.adapter.preflight?.(input, eventClass));
const preflightAdmission = preflight.admission;
if (preflightAdmission && preflightAdmission.kind !== "dispatch" &&
    preflightAdmission.kind !== "observeOnly") {
    // handled 或 drop
    await recordDroppedChannelTurnHistory({ input, preflight, admission: preflightAdmission });
    return { admission: preflightAdmission, dispatched: false };
}
```

预检阶段负责：
- **DM 策略检查**：`dmPolicy`（`"pairing"` / `"open"` / `"allowlist"` / `"disabled"`）
- **群组策略检查**：`groupPolicy`（`"open"` / `"allowlist"` / `"disabled"`）
- **allowFrom 白名单校验**：检查发送者 ID 是否在允许列表中
- **@提及检查**：群聊中是否需要 `@bot` 才能触发
- **Bot 循环保护**：防止两个 bot 互相触发无限对话

**3.3 Bot 循环保护**

```js
function resolveBotLoopProtectionDrop(params) {
    if (!params.botLoopProtection) return;
    if (!recordChannelBotPairLoopAndCheckSuppression(
        params.botLoopProtection).suppressed) return;
    // 检测到 bot 循环 → drop
    const admission = { kind: "drop", reason: "bot-loop-protection" };
    emit({...params, event: { stage: "authorize", event: "drop", ... }});
    return { admission, dispatched: false, ctxPayload: params.ctxPayload };
}
```

**3.4 准入决策（Admission Decision）**

安全检查的结果有四种准入决策：

| 决策 | 含义 | 后续行为 |
|------|------|----------|
| `dispatch` | 正常分发 | 继续流水线 |
| `observeOnly` | 仅观察不回复 | 记录但不生成回复 |
| `handled` | 已处理 | 记录历史但不启动 Agent |
| `drop` | 丢弃 | 记录历史（如果配置了 `recordOnDrop`） |

---

### Stage 4: ROUTE_RESOLUTION（路由解析）

路由解析确定消息应该由哪个 Agent 处理、绑定到哪个 Session。

**核心操作**（摘自 `kernel-D6BSEmnc.js`）：

```js
const resolved = await params.adapter.resolveTurn(input, eventClass, preflight);
emit({...params, accountId: resolved.accountId ?? params.accountId,
    event: { stage: "assemble", event: "done", messageId: input.id,
        sessionKey: resolved.routeSessionKey,
        admission: resolved.admission?.kind ?? "dispatch" }});
```

**4.1 绑定匹配（Binding Matching）**

系统通过 `channel + accountId + conversationId` 三元组解析会话绑定：

```js
// 摘自 dispatch-BlRYQnj0.js
const bindingContext = resolveConversationBindingContextFromMessage({
    cfg: params.cfg, ctx: params.ctx
});
const binding = getSessionBindingService().resolveByConversation({
    channel: bindingContext.channel,
    accountId: bindingContext.accountId,
    conversationId: bindingContext.conversationId,
    ...bindingContext.parentConversationId ?
        { parentConversationId: bindingContext.parentConversationId } : {}
});
```

绑定类型：
- **ACP Session 绑定**：跨 Channel 的持久绑定（通过 `isAcpSessionKey` 检测）
- **Plugin 拥有的绑定**：由 Channel Plugin 管理的绑定（`isPluginOwnedSessionBindingRecord`）
- **配置绑定**：通过 `resolveConfiguredBindingRoute` 解析的静态绑定

**4.2 Session 解析**

```js
const { route, buildEnvelope } = resolveInboundRouteEnvelopeBuilderWithRuntime({
    cfg: config, channel: "googlechat", accountId: account.accountId,
    peer: { kind: isGroup ? "group" : "direct", id: peerId },
    runtime: core.channel, sessionStore: config.session?.store
});
```

- **新 Session**：首次对话 → 创建新 Session，分配独立 Session Key
- **已有 Session**：返回对话 → 复用已有 Session Key，加载历史上下文

**4.3 目标 Agent 确定**

```js
const sessionAgentId = resolveSessionAgentId({
    sessionKey: acpDispatchSessionKey, config: cfg
});
```

---

### Stage 5: AGENT_DISPATCH（Agent 分发）

Agent 分发阶段将消息放入 Agent 处理队列，准备启动 AI 推理。

**核心代码**（摘自 `kernel-D6BSEmnc.js`）：

```js
emit({...params, event: { stage: "record", event: "start",
    messageId: params.messageId,
    sessionKey: params.ctxPayload.SessionKey ?? params.routeSessionKey,
    admission: admission.kind }});
try {
    await params.recordInboundSession({
        storePath: params.storePath,
        sessionKey: params.ctxPayload.SessionKey ?? params.routeSessionKey,
        ctx: params.ctxPayload, ...
    });
    emit({...params, event: { stage: "record", event: "done", ... }});
} catch (err) { ... }

emit({...params, event: { stage: "dispatch", event: "start", ... }});
dispatchResult = options.suppressObserveOnlyDispatch &&
    admission.kind === "observeOnly" ?
    resolveObserveOnlyDispatchResult(params) :
    await params.runDispatch();
```

**5.1 入站 Session 记录（Record）**

```js
await params.recordInboundSession({
    storePath: params.storePath,
    sessionKey: params.ctxPayload.SessionKey ?? params.routeSessionKey,
    ctx: params.ctxPayload,
    groupResolution: params.record?.groupResolution,
    createIfMissing: params.record?.createIfMissing,
    updateLastRoute: params.record?.updateLastRoute,
    onRecordError: params.record?.onRecordError ?? (() => void 0),
    trackSessionMetaTask: params.record?.trackSessionMetaTask
});
```

此步骤：
- 在 `Session Store` 中写入 `/ 更新入站消息记录
- 更新 `lastRoute` 信息（DM 场景，用于下次对话路由）
- 记录群组上下文（`GroupSubject`、`GroupSpace`）
- 触发诊断事件：`logMessageQueued`、`logSessionStateChange("processing")`

**5.2 队列分配**

消息通过 `dispatchReplyWithBufferedBlockDispatcher` 进入处理队列。

**5.3 Lane 分配**

OpenClaw 使用 Lane 机制对并发消息进行分类管理：

| Lane | 用途 |
|------|------|
| `AGENT_LANE_DEFAULT` | 常规对话处理 |
| `AGENT_LANE_SUBAGENT` | 子 Agent 任务 |
| 其他 | 特殊场景（如 ACP 子会话） |

**5.4 ACK 触发点**：`ackPolicy="after_agent_dispatch"` 在此阶段发送 ACK，表示"消息已进入 Agent 处理队列"。

---

### Stage 6: REPLY_GENERATION（回复生成）

回复生成是流水线的核心阶段，Agent 在此进行 AI 推理并生成回复。

**核心入口**（摘自 `dispatch-BlRYQnj0.js`）：

```js
const replyResolver = params.replyResolver ??
    (await traceReplyPhase("reply.load_reply_resolver",
        () => loadGetReplyFromConfigRuntime())).getReplyFromConfig;

const replyConfig = withFullRuntimeReplyConfig(
    params.configOverride ? applyMergePatch(cfg, params.configOverride) : cfg);

const replyResult = await traceReplyPhase("reply.run_reply_resolver",
    () => replyResolver(ctx, {
        ...params.replyOptions,
        sourceReplyDeliveryMode,
        typingPolicy: typing.typingPolicy,
        suppressTyping: typing.suppressTyping,
        onPartialReply: wrapProgressCallback(params.replyOptions?.onPartialReply),
        onReasoningStream: wrapProgressCallback(params.replyOptions?.onReasoningStream),
        onToolStart: wrapProgressCallback(params.replyOptions?.onToolStart, ...),
        onToolResult: (payload) => { ... },
        onBlockReply: (payload, context) => { ... },
        // ...更多回调
    }, replyConfig));
```

**6.1 回复生成流程**

```
Agent 推理
   │
   ├── onReasoningStream  → 推理流输出
   ├── onReasoningEnd     → 推理结束
   ├── onToolStart        → 工具调用开始
   ├── onToolResult       → 工具调用结果（媒体、exec 审批等）
   ├── onBlockReply       → 流式块回复（逐块发送 typing → live preview → final）
   ├── onPartialReply     → 部分回复
   └── onAssistantMessageStart → Assistant 消息开始
```

**6.2 工具调用处理（Tool Calls）**

Agent 可以调用多种工具，`onToolResult` 回调将结果通过 dispatcher 发送：

```js
onToolResult: (payload) => {
    markProgress();
    const run = async () => {
        markInboundDedupeReplayUnsafe();
        if (shouldSuppressProgressDelivery()) return;
        const deliveryPayload = resolveToolDeliveryPayload(
            await normalizeReplyMediaPayload(
                await maybeApplyTtsToReplyPayload({ payload, ... })));
        if (!deliveryPayload) return;
        if (shouldRouteToOriginating)
            await sendPayloadAsync(deliveryPayload, void 0, false);
        else {
            markInboundDedupeReplayUnsafe();
            dispatcher.sendToolResult(deliveryPayload);
        }
    };
    return run();
},
```

**6.3 Block Reply 流式输出机制**

流式回复通过 `onBlockReply` 逐块发送。系统会累积文本块以合并发送：

```js
onBlockReply: (payload, context) => {
    markProgress();
    const run = async () => {
        if (payload.isReasoning !== true &&
            hasOutboundReplyContent(payload, { trimText: true }))
            markInboundDedupeReplayUnsafe();
        if (suppressDelivery) return;
        if (payload.isReasoning === true) return;
        // 累积文本块用于 TTS 合并
        if (payload.text && !isStatusNotice) {
            if (accumulatedBlockText.length > 0) accumulatedBlockText += "\n";
            accumulatedBlockText += payload.text;
            blockCount++;
        }
        // 通过 dispatcher 发送 block
        if (shouldRouteToOriginating)
            await sendPayloadAsync(normalizedPayload, context?.abortSignal, false);
        else {
            markInboundDedupeReplayUnsafe();
            dispatcher.sendBlockReply(normalizedPayload);
        }
    };
    return run();
},
```

**6.4 Typing Indicator（输入状态指示器）**

系统支持在回复生成期间发送 typing 指示：

```js
const typing = resolveRunTypingPolicy({
    requestedPolicy: params.replyOptions?.typingPolicy,
    suppressTyping: sourceReplyPolicy.suppressTyping,
    originatingChannel: routeReplyChannel,
    systemEvent: shouldRouteToOriginating
});
```

**6.5 Fast Abort（快速终止）**

在启动 Agent 推理之前，系统先检查是否可以直接 abort：

```js
const fastAbort = await fastAbortResolver({ ctx, cfg });
if (fastAbort.handled) {
    // 直接发送 abort 消息，跳过完整 Agent 运行
    const payload = { text: formatAbortReplyTextResolver(fastAbort.stoppedSubagents) };
    // ...
    return attachSourceReplyDeliveryMode({ queuedFinal, counts });
}
```

---

### Stage 7: DURABLE_SEND（持久化发送）

持久化发送阶段将 Agent 生成的回复写入持久化队列，确保消息不丢失。

**7.1 持久化发送上下文**

```js
const durableOptions = typeof params.delivery.durable === "function" ?
    await params.delivery.durable(preparedPayload, info) :
    params.delivery.durable;

if (durableOptions) {
    const durable = await deliverInboundReplyWithMessageSendContext({
        cfg: params.cfg,
        channel: params.channel,
        accountId: params.accountId,
        agentId: params.agentId,
        ctxPayload: params.ctxPayload,
        payload: preparedPayload,
        info,
        ...durableOptions
    });
    throwIfDurableInboundReplyDeliveryFailed(durable);
    if (isDurableInboundReplyDeliveryHandled(durable)) {
        await params.delivery.onDelivered?.(preparedPayload, info, durable.delivery);
        return durable.delivery;
    }
}
// Fallback 到直接 delivery
const result = await params.delivery.deliver(preparedPayload, info);
```

**7.2 持久化发送能力（Durable Final Capabilities）**

系统定义了 12 种持久化发送能力（`channel-message-A0qNE-jd.js`）：

```js
const durableFinalDeliveryCapabilities = [
    "text",                   // 文本消息
    "media",                  // 媒体消息
    "payload",                // 完整 payload 消息
    "silent",                 // 静默发送（不触发通知）
    "replyTo",                // 回复引用
    "thread",                 // 线程支持
    "nativeQuote",            // 原生引用
    "messageSendingHooks",    // 发送钩子
    "batch",                  // 批量发送
    "reconcileUnknownSend",   // 未知状态恢复
    "afterSendSuccess",       // 发送成功回调
    "afterCommit"             // 提交回调
];
```

**7.3 Durable Send 状态机**

```
                        send
           ┌──────────┐  →  ┌─────────────────┐
           │ pending   │     │      sent        │
           └────+──────┘     └─────────────────┘
                │
       ┌────────┼────────┐
       │        │        │
       ▼        ▼        ▼
  suppressed  failed  unknown_after_send
 (被Hook取消) (发送失败)  (发送后状态未知需重试恢复)
```

状态定义（`channel-message-A0qNE-jd.js`）：

```js
function classifyDurableSendRecoveryState(params) {
    if (params.failed) return "failed";
    if (params.suppressed) return "suppressed";
    if (params.hasReceipt) return "sent";
    if (params.hasIntent && params.platformSendMayHaveStarted)
        return "unknown_after_send";
    return "pending";
}
```

**状态转换说明**：

| 状态 | 含义 | 触发条件 |
|------|------|----------|
| `pending` | 等待发送 | 消息已入队，尚未发送 |
| `sent` | 已发送 | 收到平台回执（receipt） |
| `suppressed` | 被抑制 | Hook 取消（如 `message_sending` hook）或空消息 |
| `failed` | 发送失败 | 平台返回错误 |
| `unknown_after_send` | 发送后状态未知 | 请求已发送但未收到响应（网络超时等），需重试恢复 |

**7.4 消息发送上下文（MessageSendContext）**

```typescript
type MessageSendContext<TPayload, TSendResult> = {
    id: string;              // 发送上下文 ID
    channel: string;         // Channel 标识
    to: string;              // 目标地址
    accountId?: string;      // 账号 ID
    durability: MessageDurabilityPolicy;  // 持久化策略
    attempt: number;         // 尝试次数
    signal: AbortSignal;     // 取消信号
    intent?: DurableMessageSendIntent;  // 发送意图
    previousReceipt?: MessageReceipt;   // 前次回执（重试时）
    preview?: LiveMessageState;         // 实时预览状态
    render(): Promise<RenderedMessageBatch>;   // 渲染消息
    send(rendered): Promise<TSendResult>;      // 发送消息
    edit(receipt, rendered): Promise<MessageReceipt>;  // 编辑消息
    delete(receipt): Promise<void>;          // 删除消息
    commit(receipt): Promise<void>;          // 提交确认
    fail(error): Promise<void>;             // 标记失败
};
```

**7.5 ACK 触发点**：`ackPolicy="after_durable_send"` 在此阶段回复写入持久化队列后发送 ACK。

---

### Stage 8: COMMIT（最终确认）

Commit 阶段执行最终的清理和确认操作。

**8.1 去重提交**

```js
const commitInboundDedupeIfClaimed = () => {
    if (inboundDedupeClaim.status === "claimed")
        commitInboundDedupe(inboundDedupeClaim.key);
};
```

**8.2 诊断记录**

```js
recordProcessed("completed",
    pluginFallbackReason ? { reason: pluginFallbackReason } : void 0);
markIdle("message_completed");
```

**8.3 Session 状态更新**

```js
// 清理 pending final delivery 记录
async function clearPendingFinalDeliveryAfterSuccess(params) {
    await updateSessionStoreEntry({
        storePath: params.storePath,
        sessionKey: params.sessionKey,
        update: async (entry) => {
            if (!entry.pendingFinalDelivery && !entry.pendingFinalDeliveryText)
                return null;
            return {
                pendingFinalDelivery: undefined,
                pendingFinalDeliveryText: undefined,
                pendingFinalDeliveryCreatedAt: undefined,
                pendingFinalDeliveryLastAttemptAt: undefined,
                pendingFinalDeliveryAttemptCount: undefined,
                pendingFinalDeliveryLastError: undefined,
                pendingFinalDeliveryContext: undefined,
                updatedAt: Date.now()
            };
        }
    });
}
```

**8.4 群组历史清理**

```js
function clearPendingHistoryAfterTurn(params) {
    if (!params?.isGroup || !params.historyKey ||
        !params.historyMap || params.limit === undefined) return;
    clearHistoryEntriesIfEnabled({
        historyMap: params.historyMap,
        historyKey: params.historyKey,
        limit: params.limit
    });
}
```

**8.5 Session 空闲标记**

```js
const markIdle = (reason) => {
    if (!canTrackSession || !sessionKey) return;
    logSessionStateChange({ sessionKey, state: "idle", reason });
};
```

---

## 6.2 ACK 策略（Acknowledgment Strategies）

OpenClaw 支持四种消息确认（ACK）策略，控制向平台返回确认信号的时机。

### ACK 策略定义

```typescript
// 摘自 types-4cKgiLCG.d.ts
type ChannelMessageReceiveAckPolicy =
    | "after_receive_record"    // Stage 1 后
    | "after_agent_dispatch"    // Stage 5 后
    | "after_durable_send"      // Stage 7 后
    | "manual";                 // Channel 自行控制
```

### 策略对照表

| 策略 | 触发阶段 | 延迟 | 可靠性 | 适用场景 |
|------|----------|------|--------|----------|
| `after_receive_record` | Stage 1 | 最低 | 最低 | 简单 webhook，快速响应 |
| `after_agent_dispatch` | Stage 5 | 中等 | 中等 | 需要确保消息进入队列 |
| `after_durable_send` | Stage 7 | 较高 | 最高 | 需要确保回复已发送 |
| `manual` | 手动 | 无限制 | 由 Channel 决定 | 自定义 Channel 实现 |

### shouldAckAfter 核心逻辑

```js
// 摘自 channel-message-A0qNE-jd.js
function shouldAckMessageAfterStage(policy, stage) {
    switch (policy) {
        case "after_receive_record":
            return stage === "receive_record";
        case "after_agent_dispatch":
            return stage === "agent_dispatch";
        case "after_durable_send":
            return stage === "durable_send";
        case "manual":
            return false;
    }
    return false;
}
```

### ACK 声明与验证

每个 Channel Adapter 需要声明其支持的 ACK 策略，并进行合约验证：

```js
// 摘自 channel-message-A0qNE-jd.js
const channelMessageReceiveAckPolicies = [
    "after_receive_record",
    "after_agent_dispatch",
    "after_durable_send",
    "manual"
];

function listDeclaredReceiveAckPolicies(receive) {
    const declared = receive?.supportedAckPolicies?.length
        ? receive.supportedAckPolicies
        : receive?.defaultAckPolicy ? [receive.defaultAckPolicy] : [];
    return channelMessageReceiveAckPolicies.filter(
        (policy) => declared.includes(policy));
}

async function verifyChannelMessageReceiveAckPolicyProofs(params) {
    const declared = new Set(listDeclaredReceiveAckPolicies(params.receive));
    for (const policy of channelMessageReceiveAckPolicies) {
        if (!declared.has(policy)) {
            results.push({ policy, status: "not_declared" });
            continue;
        }
        const proof = params.proofs[policy];
        if (!proof) throw new Error(
            `${params.adapterName} declares ack policy "${policy}" without proof`);
        await proof();
        results.push({ policy, status: "verified" });
    }
    return results;
}
```

### 默认 ACK 策略

```js
// 摘自 channel-message-A0qNE-jd.js
const defaultManualReceiveAdapter = {
    defaultAckPolicy: "manual",
    supportedAckPolicies: ["manual"]
};
```

当 Channel 未明确指定 ACK 策略时，默认使用 `manual` 策略。

---

## 6.3 消息接收确认生命周期

OpenClaw 提供了一套完整的消息接收确认生命周期管理机制。

### createMessageReceiveContext

```js
// 摘自 channel-message-A0qNE-jd.js
function createMessageReceiveContext(params) {
    const ctx = {
        id: params.id,
        channel: params.channel,
        ...params.accountId ? { accountId: params.accountId } : {},
        message: params.message,
        ackPolicy: params.ackPolicy ?? "after_receive_record",
        ackState: "pending",
        receivedAt: params.receivedAt ?? Date.now(),
        signal: params.signal ?? neverAbortedSignal,
        shouldAckAfter: (stage) => shouldAckMessageAfterStage(ctx.ackPolicy, stage),
        ack: async () => {
            if (ctx.ackState === "acked") return;
            await params.onAck?.();
            ctx.ackState = "acked";
            ctx.ackedAt = Date.now();
            delete ctx.nackErrorMessage;
        },
        nack: async (error) => {
            await params.onNack?.(error);
            ctx.ackState = "nacked";
            ctx.nackErrorMessage = normalizeAckErrorMessage(error);
        }
    };
    return ctx;
}
```

### 生命周期状态转换

```
createMessageReceiveContext:
  state: "pending" ── ack() ──▶ "acked"
         │
         └── nack(error) ──▶ "nacked" (携带 errorMessage)
```

**状态说明**：

| 状态 | 含义 | 触发 |
|------|------|------|
| `pending` | 初始状态，等待确认 | 创建 ReceiveContext 时 |
| `acked` | 已确认 | 调用 `ack()`，记录 `ackedAt` 时间戳 |
| `nacked` | 确认失败 | 调用 `nack(error)`，记录 `nackErrorMessage` |

**使用示例**（LINE Webhook Handler）：

```js
receiveContext = createMessageReceiveContext({
    id: `${Date.now()}:line:webhook`,
    channel: "line",
    message: body,
    ackPolicy: "after_receive_record",
    onAck: () => {
        res.statusCode = 200;
        res.setHeader("Content-Type", "application/json");
        res.end(JSON.stringify({ status: "ok" }));
    }
});

// Stage 1 后立即 ACK
if (receiveContext.shouldAckAfter("receive_record"))
    await receiveContext.ack();

// 错误处理
try {
    await params.bot.handleWebhook(body);
} catch (err) {
    await receiveContext?.nack(err);
    // ...错误响应
}
```

---

## 6.4 55+ Channel Actions（通道动作全集）

OpenClaw 的 Channel 系统提供了超过 55 种标准化动作（Actions），允许 Agent 对各个消息平台执行丰富的操作。这些动作通过统一的 `message` 工具暴露给 AI Agent。

### 动作分类

### 6.4.1 基础消息动作

| 动作 | 功能 | 核心参数 |
|------|------|----------|
| `send` | 发送消息 | `to`, `message`, `media`, `presentation` |
| `broadcast` | 广播消息到多个目标 | `targets`, `channel`, `message` |
| `reply` | 回复消息 | `to`, `message`, `media` |
| `edit` | 编辑已发送消息 | `channelId`, `messageId`, `message` |
| `delete` | 删除已发送消息 | `channelId`, `messageId` |
| `unsend` | 撤回消息 | `messageId` |
| `read` | 读取消息历史 | `channelId`, `limit`, `before`, `after` |
| `search` | 搜索消息 | `query`, `channelId` |

### 6.4.2 交互与反馈动作

| 动作 | 功能 | 核心参数 |
|------|------|----------|
| `react` | 添加 / 移除表情反应 | `channelId`, `messageId`, `emoji`, `remove` |
| `reactions` | 获取消息反应列表 | `channelId`, `messageId`, `limit` |
| `poll` | 创建投票 | `to`, `pollQuestion`, `pollOption`, `pollDurationHours`, `pollMulti` |
| `poll-vote` | 参与投票 | `pollId`, `pollOptionId` |

### 6.4.3 附件与媒体动作

| 动作 | 功能 | 核心参数 |
|------|------|----------|
| `sendAttachment` | 发送附件 | `to`, `media`, `message`, `filename`, `caption` |
| `upload-file` | 上传文件 | `to`, `filePath`, `filename`, `message` |
| `download-file` | 下载文件 | `fileUrl`, `messageId` |
| `sendWithEffect` | 带特效发送 | `to`, `message`, `effect` |

### 6.4.4 频道管理动作

| 动作 | 功能 | 核心参数 |
|------|------|----------|
| `channel-info` | 获取频道信息 | `channelId` |
| `channel-list` | 列出频道列表 | - |
| `channel-create` | 创建频道 | `channelName`, `categoryId` |
| `channel-edit` | 编辑频道 | `channelId`, `channelName` |
| `channel-delete` | 删除频道 | `channelId` |
| `channel-move` | 移动频道 | `channelId`, `categoryId` |
| `category-create` | 创建分类 | `categoryName` |
| `category-edit` | 编辑分类 | `categoryId`, `categoryName` |
| `category-delete` | 删除分类 | `categoryId` |
| `topic-create` | 创建话题 | `channelId`, `topicName` |
| `topic-edit` | 编辑话题 | `topicId`, `topicName` |

### 6.4.5 置顶与权限

| 动作 | 功能 | 核心参数 |
|------|------|----------|
| `pin` | 置顶消息 | `channelId`, `messageId` |
| `unpin` | 取消置顶 | `channelId`, `messageId` |
| `list-pins` | 列出置顶消息 | `channelId` |
| `permissions` | 查看权限 | `channelId` |

### 6.4.6 线程管理

| 动作 | 功能 | 核心参数 |
|------|------|----------|
| `thread-create` | 创建线程 | `channelId`, `threadName`, `messageId`, `content` |
| `thread-list` | 列出线程 | `channelId` |
| `thread-reply` | 回复线程 | `threadId`, `message`, `channelId` |

### 6.4.7 成员管理

| 动作 | 功能 | 核心参数 |
|------|------|----------|
| `member-info` | 获取成员信息 | `userId` |
| `role-info` | 获取角色信息 | `roleId` |
| `role-add` | 添加角色 | `userId`, `roleId` |
| `role-remove` | 移除角色 | `userId`, `roleId` |
| `addParticipant` | 添加参与者 | `channelId`, `userId` |
| `removeParticipant` | 移除参与者 | `channelId`, `userId` |
| `timeout` | 禁言成员 | `userId`, `duration` |
| `kick` | 踢出成员 | `userId` |
| `ban` | 封禁成员 | `userId` |
| `leaveGroup` | 离开群组 | `channelId` |

### 6.4.8 群组设置

| 动作 | 功能 | 核心参数 |
|------|------|----------|
| `renameGroup` | 重命名群组 | `channelId`, `groupName` |
| `setGroupIcon` | 设置群组图标 | `channelId`, `media` |

### 6.4.9 表情与贴纸

| 动作 | 功能 | 核心参数 |
|------|------|----------|
| `sticker` | 发送贴纸 | `to`, `stickerId` |
| `sticker-search` | 搜索贴纸 | `query` |
| `sticker-upload` | 上传贴纸 | `stickerFile`, `stickerName` |
| `emoji-list` | 列出表情 | `channelId` |
| `emoji-upload` | 上传表情 | `emojiFile`, `emojiName` |

### 6.4.10 在线状态

| 动作 | 功能 | 核心参数 |
|------|------|----------|
| `set-presence` | 设置在线状态 | `status`, `activityType`, `activityName` |
| `set-profile` | 设置个人信息 | `displayName`, `avatar` |
| `voice-status` | 设置语音状态 | `channelId`, `status` |

### 6.4.11 事件

| 动作 | 功能 | 核心参数 |
|------|------|----------|
| `event-list` | 列出事件 | `channelId` |
| `event-create` | 创建事件 | `channelId`, `eventName`, `startTime` |

### 动作发现机制

并非所有 Channel 都支持所有动作。OpenClaw 使用动作发现（Action Discovery）机制来动态确定每个 Channel 的可用动作：

```js
// 摘自 channel-actions-D-apl_0h.js (Discord 动作发现示例)
function describeDiscordMessageTool({ cfg, accountId }) {
    const discovery = resolveScopedDiscordActionDiscovery({ cfg, accountId });
    if (!discovery) return { actions: [], capabilities: [], schema: null };

    const actions = new Set(["send"]);  // send 始终可用
    if (discovery.isEnabled("polls")) actions.add("poll");
    if (discovery.isEnabled("reactions")) {
        actions.add("react");
        actions.add("reactions");
        actions.add("emoji-list");
    }
    if (discovery.isEnabled("messages")) {
        actions.add("upload-file");
        actions.add("read");
        actions.add("edit");
        actions.add("delete");
    }
    if (discovery.isEnabled("pins")) {
        actions.add("pin");
        actions.add("unpin");
        actions.add("list-pins");
    }
    if (discovery.isEnabled("permissions")) actions.add("permissions");
    if (discovery.isEnabled("threads")) {
        actions.add("thread-create");
        actions.add("thread-list");
        actions.add("thread-reply");
    }
    if (discovery.isEnabled("search")) actions.add("search");
    if (discovery.isEnabled("stickers")) actions.add("sticker");
    if (discovery.isEnabled("memberInfo")) actions.add("member-info");
    if (discovery.isEnabled("roleInfo")) actions.add("role-info");
    if (discovery.isEnabled("emojiUploads")) actions.add("emoji-upload");
    if (discovery.isEnabled("stickerUploads")) actions.add("sticker-upload");
    if (discovery.isEnabled("roles", false)) {
        actions.add("role-add");
        actions.add("role-remove");
    }
    if (discovery.isEnabled("channelInfo")) {
        actions.add("channel-info");
        actions.add("channel-list");
    }
    if (discovery.isEnabled("channels")) {
        actions.add("channel-create");
        actions.add("channel-edit");
        actions.add("channel-delete");
        actions.add("channel-move");
        actions.add("category-create");
        actions.add("category-edit");
        actions.add("category-delete");
    }
    if (discovery.isEnabled("voiceStatus")) actions.add("voice-status");
    if (discovery.isEnabled("events")) {
        actions.add("event-list");
        actions.add("event-create");
    }
    if (discovery.isEnabled("moderation", false)) {
        actions.add("timeout");
        actions.add("kick");
        actions.add("ban");
    }
    if (discovery.isEnabled("presence", false)) actions.add("set-presence");

    return { actions: Array.from(actions), capabilities: ["presentation"] };
}
```

### 动作执行流程

每个消息动作都经过统一的执行流程：

```
runMessageAction(input)
  │
  ├── 1. parseJsonMessageParam    (解析 JSON 字段：presentation, delivery, interactive)
  ├── 2. enforceMessageActionAllowlist  (检查动作白名单)
  ├── 3. normalizeMessageActionInput    (标准化参数)
  ├── 4. resolveChannel               (解析目标 Channel)
  ├── 5. resolveTargetBoundAccountId   (解析绑定账号)
  ├── 6. resolveAttachmentMediaPolicy  (解析媒体策略)
  ├── 7. hydrateAttachmentParamsForAction (处理附件参数)
  ├── 8. enforceCrossContextPolicy     (跨上下文策略检查)
  └── 9. 分发到具体处理器:
        ├── handleSendAction
        ├── handlePollAction
        ├── handleBroadcastAction
        └── handlePluginAction (通用 Plugin 动作)
```

### 执行模式

```js
// 摘自 channel-actions-D-apl_0h.js
const discordMessageActions = {
    resolveExecutionMode: ({ action }) =>
        action === "read" || action === "search" ? "gateway" : "local",
    // ...
};
```

- **`local`** 模式：动作在本地运行时直接执行（如 `send`、`edit`、`delete`）
- **`gateway`** 模式：动作通过 Gateway 代理执行（如 `read`、`search`），适合需要访问平台 API 且本地不可达的场景

### 消息动作白名单

通过 `tools.message.actions.allow` 配置可以精细控制 Agent 可用的动作：

```js
function enforceMessageActionAllowlist(params) {
    const allowed = resolveAllowedMessageActions(params);
    if (!allowed || allowed.includes(params.action)) return;
    throw new Error(
        `Message action "${params.action}" is disabled for this agent.`);
}
```

### 跨上下文策略

当 Agent 在某个对话上下文中尝试向另一个对话发送消息时，系统执行跨上下文策略检查：

```js
const CONTEXT_GUARDED_ACTIONS = new Set([
    "send", "poll", "reply", "sendWithEffect",
    "sendAttachment", "upload-file", "thread-create",
    "thread-reply", "sticker"
]);

function enforceCrossContextPolicy(params) {
    // 检查 allowCrossContextSend
    // 检查 allowWithinProvider
    // 检查 allowAcrossProviders
    // ...
}
```

跨上下文标记（Cross-Context Marker）会在消息前添加来源标识，例如 `[from Telegram] `。

---

## 6.5 Reply Pipeline（回复管线）

Reply Pipeline 是消息回复的格式化、转换和投递管道。

### createChannelReplyPipeline

```js
// 摘自 reply-pipeline-CzSKhl9A.js
function createChannelReplyPipeline(params) {
    const channelId = params.channel
        ? normalizeAnyChannelId(params.channel) ?? params.channel
        : undefined;

    let plugin;
    let pluginTransformResolved = false;
    const resolvePluginTransform = () => {
        if (pluginTransformResolved)
            return plugin?.messaging?.transformReplyPayload;
        pluginTransformResolved = true;
        plugin = channelId
            ? getLoadedChannelPluginForRead(channelId)
            : undefined;
        return plugin?.messaging?.transformReplyPayload;
    };

    const transformReplyPayload = params.transformReplyPayload
        ? params.transformReplyPayload
        : channelId
            ? (payload) => resolvePluginTransform()?.({
                payload, cfg: params.cfg, accountId: params.accountId
            }) ?? payload
            : undefined;

    return {
        // 1. 回复前缀选项
        ...createReplyPrefixOptions({
            cfg: params.cfg,
            agentId: params.agentId,
            channel: params.channel,
            accountId: params.accountId
        }),
        // 2. 平台特定转换
        ...transformReplyPayload
            ? { transformReplyPayload }
            : {},
        // 3. Typing 回调
        ...params.typingCallbacks
            ? { typingCallbacks: params.typingCallbacks }
            : params.typing
                ? { typingCallbacks: createTypingCallbacks(params.typing) }
                : {}
    };
}
```

### 管线组件详解

#### 6.5.1 Prefix Options（回复前缀）

```js
// 摘自 reply-prefix-DRk1pK2P.js
function createReplyPrefixContext(params) {
    const { cfg, agentId } = params;
    const prefixContext = {
        identityName: normalizeOptionalString(
            resolveAgentIdentity(cfg, agentId)?.name)
    };
    const onModelSelected = (ctx) => {
        prefixContext.provider = ctx.provider;
        prefixContext.model = extractShortModelName(ctx.model);
        prefixContext.modelFull = `${ctx.provider}/${ctx.model}`;
        prefixContext.thinkingLevel = ctx.thinkLevel ?? "off";
    };
    return {
        prefixContext,
        responsePrefix: resolveEffectiveMessagesConfig(cfg, agentId, {
            channel: params.channel,
            accountId: params.accountId
        }).responsePrefix,
        responsePrefixContextProvider: () => prefixContext,
        onModelSelected
    };
}
```

`responsePrefix` 配置控制是否在回复中添加 `[From XXX platform]` 样式的前缀。模板变量包括 `{identityName}`、`{provider}`、`{model}` 等。

#### 6.5.2 transformReplyPayload（平台特定转换）

每个 Channel Plugin 可以提供 `messaging.transformReplyPayload` 来对回复内容进行平台特定转换：

```js
const transformReplyPayload = channelId
    ? (payload) => resolvePluginTransform()?.({
        payload,
        cfg: params.cfg,
        accountId: params.accountId
    }) ?? payload
    : undefined;
```

#### 6.5.3 Platform-Specific Directives（平台特定指令）

OpenClaw 的回复系统支持多种平台特定的富媒体指令格式（Directives），通过 `parseReplyDirectives` 解析。这些指令允许 Agent 在纯文本消息中嵌入结构化的交互元素：

**LINE 平台支持的特殊指令**：

| 指令 | 格式 | 功能 |
|------|------|------|
| `[[quick_replies:...]]` | `[[quick_replies:选项1,选项2,选项3]]` | 快速回复按钮 |
| `[[location:...]]` | `[[location:lat,lng,title,address]]` | 位置消息 |
| `[[confirm:...]]` | `[[confirm:标题,消息,确认文本,取消文本]]` | 确认对话框 |
| `[[buttons:...]]` | `[[buttons:标题,文本,按钮1,按钮2,...]]` | 按钮菜单 |
| `[[flex:...]]` | `[[flex:JSON]]` | Flex Message（自定义布局） |

LINE 平台的处理示例（`bot-BRuINTsq.js` 中的 `deliverLineAutoReply`）：

```js
async function deliverLineAutoReply(params) {
    const { payload, lineData, replyToken, to } = params;

    // 处理 Flex Message
    if (lineData.flexMessage)
        richMessages.push(deps.createFlexMessage(
            lineData.flexMessage.altText.slice(0, 400),
            lineData.flexMessage.contents));

    // 处理 Template Message
    if (lineData.templateMessage) {
        const templateMsg = deps.buildTemplateMessageFromPayload(
            lineData.templateMessage);
        if (templateMsg) richMessages.push(templateMsg);
    }

    // 处理位置消息
    if (lineData.location)
        richMessages.push(deps.createLocationMessage(lineData.location));

    // 处理 Markdown 文本（转换为 LINE Flex Message）
    const processed = payload.text
        ? deps.processLineMessage(payload.text)
        : { text: "", flexMessages: [] };

    // 解析后的文本用 chunkMarkdownText 分块
    const chunks = processed.text
        ? deps.chunkMarkdownText(processed.text, textLimit)
        : [];

    // 先发送 rich/媒体消息（不使用 reply token）
    if (chunks.length > 0 && hasQuickReplies && hasRichOrMedia)
        await sendLineMessages([...richMessages, ...mediaMessages], false);

    // 再发送文本块（优先使用 reply token）
    const { replyTokenUsed } = await deps.sendLineReplyChunks({
        to, chunks, quickReplies, replyToken, replyTokenUsed, ...
    });

    // Quick Reply 追加到最后一条消息
    if (hasQuickReplies && combined.length > 0) {
        const quickReply = deps.createQuickReplyItems(lineData.quickReplies);
        combined[combined.length - 1] = {
            ...combined[combined.length - 1],
            quickReply
        };
    }
}
```

#### 6.5.4 Typing Callbacks（输入状态回调）

Typing 回调系统管理发送 typing indicator 的生命周期：

```js
// 摘自 reply-pipeline-CzSKhl9A.js
...params.typingCallbacks
    ? { typingCallbacks: params.typingCallbacks }
    : params.typing
        ? { typingCallbacks: createTypingCallbacks(params.typing) }
        : {}

// 集成到 ReplyDispatcher
function createReplyDispatcherWithTyping(options) {
    const { typingCallbacks, onReplyStart, onIdle, onCleanup, ...rest } = options;
    let typingController;
    return {
        dispatcher: createReplyDispatcher({
            ...rest,
            onIdle: () => {
                typingController?.markDispatchIdle();
                resolvedOnIdle?.();
            }
        }),
        replyOptions: {
            onReplyStart: resolvedOnReplyStart,
            onTypingCleanup: resolvedOnCleanup,
            onTypingController: (typing) => {
                typingController = typing;
            }
        },
        markDispatchIdle: () => {
            typingController?.markDispatchIdle();
            resolvedOnIdle?.();
        },
        markRunComplete: () => {
            typingController?.markRunComplete();
        }
    };
}
```

Typing 策略由 `resolveRunTypingPolicy` 决定，可以根据 Channel 类型和配置选择不同的 typing 行为。

#### 6.5.5 Delivery（投递）

投递组件负责将最终回复发送到目标平台：

- **Durable Delivery**：通过持久化队列发送（`deliverInboundReplyWithMessageSendContext`）
- **Direct Delivery**：直接通过 Channel Adapter 发送
- **Route Reply**：当需要路由到不同平台时使用：

```js
const routeReplyToOriginating = async (payload, options) => {
    if (!shouldRouteToOriginating || !routeReplyChannel ||
        !routeReplyTo || !routeReplyRuntime) return null;
    markInboundDedupeReplayUnsafe();
    return await routeReplyRuntime.routeReply({
        payload, channel: routeReplyChannel, to: routeReplyTo,
        sessionKey: ctx.SessionKey, accountId: replyRoute.accountId,
        cfg, abortSignal: options?.abortSignal, ...
    });
};
```

---

## 6.6 Reply Dispatcher（回复调度器）

Reply Dispatcher 是管理回复消息排队和发送的核心组件。

### createReplyDispatcher

```js
// 摘自 dispatch-BlRYQnj0.js
function createReplyDispatcher(options) {
    let sendChain = Promise.resolve();
    let pending = 1;
    let completeCalled = false;
    let sentFirstBlock = false;
    const queuedCounts = { tool: 0, block: 0, final: 0 };
    const failedCounts = { tool: 0, block: 0, final: 0 };
    const cancelledCounts = { tool: 0, block: 0, final: 0 };

    const enqueue = (kind, payload) => {
        const normalized = normalizeReplyPayloadInternal(payload, { ... });
        if (!normalized) return false;
        queuedCounts[kind] += 1;
        pending += 1;
        const shouldDelay = kind === "block" && sentFirstBlock;
        if (kind === "block") sentFirstBlock = true;
        sendChain = sendChain.then(async () => {
            if (shouldDelay) {
                const delayMs = getHumanDelay(options.humanDelay);
                if (delayMs > 0) await sleep(delayMs);
            }
            let deliverPayload = normalized;
            if (options.beforeDeliver) {
                deliverPayload = await options.beforeDeliver(normalized, { kind });
                if (!deliverPayload) {
                    cancelledCounts[kind] += 1;
                    return;
                }
            }
            await options.deliver(deliverPayload, { kind });
        }).catch((err) => {
            failedCounts[kind] += 1;
            options.onError?.(err, { kind });
        }).finally(() => {
            pending -= 1;
            if (pending === 0) {
                unregister();
                options.onIdle?.();
            }
        });
        return true;
    };

    return {
        sendToolResult: (payload) => enqueue("tool", payload),
        sendBlockReply: (payload) => enqueue("block", payload),
        sendFinalReply: (payload) => enqueue("final", payload),
        waitForIdle: () => sendChain,
        getQueuedCounts: () => ({ ...queuedCounts }),
        getCancelledCounts: () => ({ ...cancelledCounts }),
        getFailedCounts: () => ({ ...failedCounts }),
        markComplete
    };
}
```

### 三种发送类型

| 类型 | 方法 | 说明 |
|------|------|------|
| `tool` | `sendToolResult` | 工具执行结果（如 exec 审批、命令输出） |
| `block` | `sendBlockReply` | 流式回复块（支持 human-like 延迟） |
| `final` | `sendFinalReply` | 最终回复（Agent 推理完成后的最终输出） |

### Human-Like Delay

Block 回复之间的延迟模拟真人输入节奏：

```js
const DEFAULT_HUMAN_DELAY_MIN_MS = 800;
const DEFAULT_HUMAN_DELAY_MAX_MS = 2500;

function getHumanDelay(config) {
    const mode = config?.mode ?? "off";
    if (mode === "off") return 0;
    const min = mode === "custom"
        ? config?.minMs ?? DEFAULT_HUMAN_DELAY_MIN_MS
        : DEFAULT_HUMAN_DELAY_MIN_MS;
    const max = mode === "custom"
        ? config?.maxMs ?? DEFAULT_HUMAN_DELAY_MAX_MS
        : DEFAULT_HUMAN_DELAY_MAX_MS;
    if (max <= min) return min;
    return min + generateSecureInt(max - min + 1);
}
```

### 消息发送钩子（message_sending hook）

在消息真正发送前，通过 `message_sending` Hook 可以对内容进行拦截或修改：

```js
function buildMessageSendingBeforeDeliver(ctx) {
    const hookRunner = getGlobalHookRunner();
    if (!hookRunner?.hasHooks("message_sending")) return;
    const finalized = finalizeInboundContext(ctx);
    const hookCtx = deriveInboundMessageHookContext(finalized);
    const replyTarget = resolveInboundReplyHookTarget(finalized, hookCtx);
    return async (payload) => {
        if (!payload.text) return payload;
        const result = await hookRunner.runMessageSending({
            content: payload.text,
            to: replyTarget
        }, toPluginMessageContext(hookCtx));
        if (result?.cancel) return null;       // 取消发送
        if (result?.content != null) return {  // 修改内容
            ...payload,
            text: result.content
        };
        return payload;
    };
}
```

---

## 6.7 Source Reply 可见性策略

Source Reply（源回复）指的是 Agent 生成的回复是否对用户可见，以及以什么形式可见。

### 策略定义

```js
const sourceReplyPolicy = resolveSourceReplyVisibilityPolicy({
    cfg, ctx,
    requested: params.replyOptions?.sourceReplyDeliveryMode,
    strictMessageToolOnly: ctx.InboundEventKind === "room_event",
    sendPolicy,
    suppressAcpChildUserDelivery,
    explicitSuppressTyping: params.replyOptions?.suppressTyping === true,
    shouldSuppressTyping,
    messageToolAvailable,
    defaultVisibleReplies: harnessDefaultVisibleReplies
});
```

### 可见性模式

| 模式 | 说明 |
|------|------|
| `message_tool_only` | 仅通过 message 工具发送，不自动投递到用户 |
| `visible` | 回复对用户可见（默认） |
| `suppressed` | 回复被抑制（如 ACP 子会话） |

### Delivery Suppression

```js
const { sourceReplyDeliveryMode, suppressAutomaticSourceDelivery,
    suppressDelivery, sendPolicyDenied,
    deliverySuppressionReason } = sourceReplyPolicy;

if (suppressDelivery)
    logVerbose(`Delivery suppressed by ${deliverySuppressionReason}`);
// Agent 仍然处理消息，但回复不投递给用户
```

---

## 6.8 Foreground Reply Fence（前台回复栅栏）

为防止同一 Session/Channel 的多个并发回复造成混乱，OpenClaw 实现了 Foreground Reply Fence 机制：

```js
function beginForegroundReplyFence(finalized) {
    const key = resolveForegroundReplyFenceKey(finalized);
    if (!key) return;
    const state = foregroundReplyFenceByKey.get(key) ?? {
        generation: 0,
        activeDispatches: 0
    };
    state.generation += 1;
    state.activeDispatches += 1;
    foregroundReplyFenceByKey.set(key, state);
    return { key, generation: state.generation };
}

function isForegroundReplyFenceSuperseded(snapshot) {
    return Boolean(snapshot &&
        (foregroundReplyFenceByKey.get(snapshot.key)?.generation ?? 0)
        !== snapshot.generation);
}

function endForegroundReplyFence(snapshot) {
    const state = foregroundReplyFenceByKey.get(snapshot.key);
    if (!state) return;
    state.activeDispatches -= 1;
    if (state.activeDispatches <= 0)
        foregroundReplyFenceByKey.delete(snapshot.key);
}
```

**栅栏 Key 构成**：

```js
function resolveForegroundReplyFenceKey(finalized) {
    const sessionKey = normalizeForegroundReplyFencePart(finalized.SessionKey);
    const channel = normalizeForegroundReplyFencePart(finalized.OriginatingChannel)
        ?? normalizeForegroundReplyFencePart(finalized.Surface)
        ?? normalizeForegroundReplyFencePart(finalized.Provider);
    const target = normalizeForegroundReplyFencePart(finalized.OriginatingTo)
        ?? normalizeForegroundReplyFencePart(finalized.NativeChannelId)
        ?? normalizeForegroundReplyFencePart(finalized.From)
        ?? normalizeForegroundReplyFencePart(finalized.To);
    if (!sessionKey || !channel || !target) return;
    return JSON.stringify([
        "foreground", channel,
        normalizeForegroundReplyFencePart(finalized.AccountId) ?? "default",
        sessionKey,
        normalizeChatType(finalized.ChatType) ?? "unknown",
        target
    ]);
}
```

当新的回复 Dispatch 开始时，`generation` 递增，所有旧的正在进行的回复会被 `isForegroundReplyFenceSuperseded` 检测出来并中止。

---

## 6.9 持久化消息状态记录

### createDurableMessageStateRecord

```js
// 摘自 channel-message-A0qNE-jd.js
function createDurableMessageStateRecord(params) {
    return {
        intent: params.intent,
        state: params.state ?? (params.receipt ? "sent" : "pending"),
        ...params.receipt ? { receipt: params.receipt } : {},
        updatedAt: params.updatedAt ?? Date.now(),
        ...params.error === undefined
            ? {}
            : { errorMessage: normalizeErrorMessage(params.error) }
    };
}
```

### 消息回执（MessageReceipt）

```typescript
type MessageReceipt = {
    primaryPlatformMessageId?: string;  // 主平台消息 ID
    platformMessageIds: string[];       // 所有平台消息 ID
    parts: MessageReceiptPart[];        // 消息分部（文本、媒体、语音等）
    threadId?: string;                  // 线程 ID
    replyToId?: string;                 // 回复引用 ID
    editToken?: string;                 // 编辑令牌
    deleteToken?: string;               // 删除令牌
    sentAt: number;                     // 发送时间戳
    raw?: readonly MessageReceiptSourceResult[];  // 原始结果
};
```

---

## 6.10 实时消息能力（Live Message Capabilities）

除了持久化发送，OpenClaw 还支持实时消息能力，用于流式回复的渐进式渲染：

```js
const channelMessageLiveCapabilities = [
    "draftPreview",       // 草稿预览
    "previewFinalization",// 预览最终化
    "progressUpdates",    // 进度更新
    "nativeStreaming",    // 原生流式输出
    "quietFinalization"   // 静默最终化（不发送额外消息）
];

const livePreviewFinalizerCapabilities = [
    "finalEdit",          // 最终编辑
    "normalFallback",     // 普通回退
    "discardPending",     // 丢弃待发送
    "previewReceipt",     // 预览回执
    "retainOnAmbiguousFailure"  // 模糊失败时保留
];
```

---

## 6.11 完整流水线示例：LINE 消息处理

以下是 LINE Channel 处理一条入站消息的完整流程示例（摘自 `bot-BRuINTsq.js` 中的 `monitorLineProvider`）：

```js
// 1. 创建 ReceiveContext（Stage 1: RECEIVE_RECORD）
receiveContext = createMessageReceiveContext({
    id: `${Date.now()}:line:webhook`,
    channel: "line",
    message: body,
    ackPolicy: "after_receive_record",
    onAck: () => {
        res.statusCode = 200;
        res.setHeader("Content-Type", "application/json");
        res.end(JSON.stringify({ status: "ok" }));
    }
});

// Stage 1: 立即 ACK
if (receiveContext.shouldAckAfter("receive_record"))
    await receiveContext.ack();

// 2. 后台异步处理消息
Promise.resolve().then(() => params.bot.handleWebhook(body))
    .catch((err) => logLineWebhookDispatchError(params.runtime, err));

// 3. onMessage 回调中 → 启动 Turn Pipeline
const turnResult = await core.channel.turn.run({
    channel: "line",
    accountId: route.accountId,
    raw: ctx,
    adapter: {
        ingest: () => ({                          // Stage 1
            id: ctxPayload.MessageSid ?? `${ctxPayload.From}:${Date.now()}`,
            rawText: ctxPayload.RawBody ?? ctxPayload.BodyForAgent ?? ""
        }),
        resolveTurn: () => ({                     // Stage 4 & 5
            cfg: config,
            channel: "line",
            accountId: route.accountId,
            agentId: route.agentId,
            routeSessionKey: route.sessionKey,
            storePath: ctx.turn.storePath,
            ctxPayload,
            recordInboundSession: core.channel.session.recordInboundSession,
            dispatchReplyWithBufferedBlockDispatcher:
                core.channel.reply.dispatchReplyWithBufferedBlockDispatcher,
            record: ctx.turn.record,
            replyPipeline: {},
            delivery: {                           // Stage 6 & 7
                durable: (payload, info) =>
                    resolveLineDurableReplyOptions({...}),
                deliver: async (payload) => {
                    // 发送 typing 动画
                    if (ctx.userId && !ctx.isGroup)
                        showLoadingAnimation(ctx.userId, {...});
                    // 通过 LINE API 投递回复
                    const { replyTokenUsed } = await deliverLineAutoReply({
                        payload, lineData, to, replyToken, ...
                    });
                    replyTokenUsed = nextReplyTokenUsed;
                },
                onError: (err, info) => {
                    runtime.error?.(danger(
                        `line ${info.kind} reply failed: ${String(err)}`));
                }
            }
        })
    }
});

// Stage 8: 检查结果
if (!hasFinalChannelTurnDispatch(
    turnResult.dispatched ? turnResult.dispatchResult : undefined))
    logVerbose(`line: no response generated`);
```

---

## 6.12 小结

OpenClaw 的 Channel 消息系统是一个高度工程化的架构：

1. **8 阶段 Turn Pipeline** 提供了从消息接受到回复投递的全生命周期管理，每个阶段有明确的事件发出，支持全链路诊断
2. **4 种 ACK 策略** 允许不同 Channel 根据自身需求选择最适合的确认时机，同时提供了 `createMessageReceiveContext` 统一接口管理 ACK/NACK 生命周期
3. **55+ 标准化 Channel Actions** 覆盖了消息、媒体、频道管理、成员管理、表情、在线状态等全场景，通过动作发现机制动态适配不同平台的能力
4. **Reply Pipeline** 通过 `createChannelReplyPipeline` 组装了前缀选项、平台特定转换、typing 回调、投递四大组件，支持富媒体指令（如 LINE 的 quick_replies、location、confirm、buttons）
5. **Durable Send 状态机** 保证了消息投递的可靠性，支持 pending/sent/suppressed/failed/unknown_after_send 五种状态的精确追踪
6. **Reply Dispatcher** 提供了 tool/block/final 三种发送类型的串行化排队，支持 human-like delay 和 message_sending hook 的内容修改
7. **Foreground Reply Fence** 防止并发回复冲突，保证同一会话中只有一个"活跃"回复流

这一架构设计使得 OpenClaw 能够同时支持 LINE、Discord、Telegram、Google Chat、IRC、Nextcloud Talk 等多个消息平台，并保证每条消息都经过一致且可靠的处理流程。
