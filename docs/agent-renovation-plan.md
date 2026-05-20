# LyClaw Agent 全面改造计划

> 对标 OpenClaw v1.0 的 Agent 体系，将 LyClaw 的 Agent 打造为同等强大的企业级多智能体框架。
>
> 生成日期：2026-05-20 | 版本：v1.0

---

## 目录

### 第一部分：差距分析

| 章节 | 内容 | 行数 |
|------|------|------|
| 1 | [Agent 核心配置与运行时](#1-agent-核心配置与运行时) | Agent 注解、AgentConfig、Runtime 模式、Subagent 代理、Config 层级 |
| 2 | [Hook 系统与 Pipeline](#2-hook-系统与-pipeline) | Hook (5 vs 36)、Pipeline 架构、Compaction、Context Pruning、Context Limits |
| 3 | [模型管理与沙箱](#3-模型管理与沙箱) | Model Catalog、Multi-Model、Model Fallback、Thinking 控制、Sandbox |
| 4 | [Bootstrap、路由与身份](#4-bootstrap路由与身份) | Workspace Bootstrap、Agent Routing、Identity、Group Chat、Heartbeat、Streaming |

### 第二部分：改造计划

| 章节 | 内容 | 行数 |
|------|------|------|
| 5 | [Phase 1: Agent 核心增强](#5-phase-1-agent-核心增强) | AgentConfig 体系、AgentContext 增强、Hook 5→36、AgentRuntime、ProxyFactory |
| 6 | [Phase 2: Subagent + 模型管理](#6-phase-2-subagent--模型管理) | Subagent 代理系统、Model Catalog、Multi-Model、Thinking 控制、Provider Discovery |
| 7 | [Phase 3: Context + Bootstrap + 路由](#7-phase-3-context--bootstrap--路由) | Compaction、Context Pruning、Bootstrap 文件、Agent Routing、Identity/Avatar |
| 8 | [Phase 4: Streaming + Sandbox + Heartbeat](#8-phase-4-streaming--sandbox--heartbeat) | Block Streaming、Human Delay、Docker Sandbox、Heartbeat、Run Retries |

### 第三部分：架构与路线图

| 章节 | 内容 | 行数 |
|------|------|------|
| 9 | [架构蓝图](#9-架构蓝图) | 5 张 ASCII 架构图：整体架构、生命周期流、配置层级、Subagent 树、SSE 事件流 |
| 10 | [实施路线图](#10-实施路线图) | 4 阶段任务清单、测试策略、迁移计划、风险矩阵、成功指标 |

---


---

# LyClaw vs OpenClaw: Agent Core Gap Analysis (Part 1)

> **Scope**: Agent Configuration, Runtime Modes, Subagent Delegation, Config Hierarchy & Resolution, Agent Scope Resolution
>
> **Date**: 2026-05-20
>
> **Methodology**: Field-by-field comparison based on LyClaw source code (lyclaw-framework + lyclaw-autoconfigure) and OpenClaw 2026.5.18 dist code.

---

## Table of Contents

1. [Category 1: Agent Configuration (Annotation/Interface vs Config Object)](#category-1-agent-configuration)
2. [Category 2: Agent Runtime Modes](#category-2-agent-runtime-modes)
3. [Category 3: Subagent Delegation](#category-3-subagent-delegation)
4. [Category 4: Config Hierarchy & Resolution](#category-4-config-hierarchy--resolution)
5. [Category 5: Agent Scope Resolution](#category-5-agent-scope-resolution)
6. [Summary: Priority Matrix](#summary-priority-matrix)

---

## Architecture Overview: LyClaw Agent System (Current State)

Before diving into the gap analysis, here is a quick structural summary of how LyClaw's agent system currently works:

```
@Agent annotation on interface
        │
        ▼
AgentProxyFactory.create(Class<T>)
        │  reads @Agent(name, description, version, model, provider, extensions[])
        │  creates AgentInvocationHandler(chatFacade, reActEngine, toolRegistry, ...)
        │
        ▼
JDK Proxy.newProxyInstance() ──► AgentInvocationHandler.invoke()
        │
        ├── 1. Resolve @SystemMessage / @UserMessage from method annotations
        ├── 2. Build AgentContext (sessionId, userMessage, systemPrompt, chatRequest, toolRegistry, method, args)
        ├── 3. beforeRequest hooks (ordered by getOrder())
        ├── 4. Stage Pipeline (ContextBuild→SecurityCheck→PlanExecution→Respond→Reflection→Metrics)
        │       │
        │       └── ReAct Loop (embedded in RespondStage)
        │           ├── LLM call via ChatFacade → ModelRouter → ChatModel
        │           ├── Tool detection (3-state streaming: buffering/relaying/tools)
        │           ├── Tool execution via ToolExecutor chain (wrapped by SandboxHook + ApprovalHook)
        │           └── Multi-round until text response or maxToolRounds
        │
        ├── 5. afterResult hooks (reverse order)
        └── 6. Return String / Flux<SSE> / Mono<String>
```

Key observation: **The entire agent lifecycle is driven by the `@Agent` annotation and the JDK proxy invocation handler**. There is no config object, no defaults layer, no runtime mode selection, and no subagent spawning within this flow.

---

## Category 1: Agent Configuration

### 1.1 Identifier & Basic Information

| Sub-Category | LyClaw Current State | OpenClaw Implementation | Gap Severity | Complexity |
|---|---|---|---|---|
| **Agent identity** | `@Agent(name="chat")` — single string name. No `id` field separate from name. Name serves dual purpose as both registry key and display label. | `AgentConfig.id` (required, unique string) + `AgentConfig.name` (optional human-readable). ID is canonical for routing bindings, subagent references, logs; name is for display only. | P1 | Low |
| **Default agent marker** | No concept of a "default" agent. Route binding is not tied to agent identity; `ModelRouter` routes by content, not by agent. | `AgentConfig.default: boolean` — marks an agent as the catch-all default. When no routing binding matches an incoming message, the `default=true` agent handles it. | P1 | Low |
| **Description** | `@Agent(description="...")` — plain text string used as fallback system prompt in `AgentProxyFactory.create()`. | `AgentConfig.description: string` — descriptive text for UI/admin panels only; system prompt is a separate `systemPromptOverride` field. | P2 | Low |
| **Version** | `@Agent(version="1.0.0")` — SemVer string stored in annotation, used for tracking iteration history. | No explicit version field on `AgentConfig`. Versioning handled at the deployment/config-file level. | P3 | N/A |
| **Workspace directory** | Not present. No concept of a per-agent working directory. Filesystem tools operate relative to global process cwd. | `AgentConfig.workspace: string` — inherited from defaults. Agent's working directory for all file operations and bootstrap files. | P1 | Medium |
| **Agent private directory** | Not present. | `AgentConfig.agentDir: string` — separate from workspace, stores agent-private data (session archives, skill data). | P2 | Medium |

### 1.2 System Prompt & Bootstrap Context

| Sub-Category | LyClaw Current State | OpenClaw Implementation | Gap Severity | Complexity |
|---|---|---|---|---|
| **System prompt override** | System prompt comes from `@SystemMessage` annotation on methods or `defaultSystemPrompt` in `AgentProxyFactory`. No agent-level override independent of method annotations. | `AgentConfig.systemPromptOverride: string` — **completely replaces** the entire system prompt. Separate from method-level or inline prompts. | P1 | Medium |
| **Context injection mode** | Not present. Bootstrap/context injection is not configurable per agent. | `AgentConfig.contextInjection: "always" \| "continuation-skip" \| "never"` — controls when AGENTS.md/bootstrap files are injected into the context. | P2 | Low |
| **Bootstrap file limits** | Not present. | `AgentConfig.bootstrapMaxChars: number` (default 20000) per file; `bootstrapTotalMaxChars: number` (default 150000) total across all files. | P2 | Low |
| **Skills whitelist** | Not present at agent level. Skills are registered globally in `SkillRegistry`; no per-agent skill filtering. | `AgentConfig.skills: string[]` — whitelist of skill IDs available to this agent. **Explicit setting completely replaces defaults** (not merged). | P1 | Medium |

### 1.3 Model Configuration

| Sub-Category | LyClaw Current State | OpenClaw Implementation | Gap Severity | Complexity |
|---|---|---|---|---|
| **Primary model** | `@Agent(model="deepseek-v4-flash")` — single string model name. `@Agent(provider="deepseek")` — single provider string. Resolved via `ModelRouter` + `ChatFacade.route()`. | `AgentConfig.model: string \| { primary, fallbacks[] }` — either a simple "provider/model" string or an object with `primary` + ordered `fallbacks` list. | P1 | Medium |
| **Model fallback chain** | `FallbackChatModel` decorator exists as a generic decorator pattern, not agent-specific. No per-agent fallback configuration. | `AgentModelConfig.fallbacks: string[]` — ordered fallback model list. Auto-fallback probing: if primary fails, tries fallback[0], then fallback[1], etc. | P1 | Medium |
| **Per-model metadata catalog** | Not present. Only `ChatProperties.ModelProperties` (provider, model, baseUrl, apiKey) in YAML. | `AgentConfig.models: Record<string, AgentModelEntryConfig>` — per-model-ID metadata including `alias`, `params` (provider-specific API params), `agentRuntime` override, `streaming` toggle. | P2 | High |
| **Multi-model support (image/video/music/PDF)** | Not present. Single text model per agent. | `AgentDefaultsConfig` has `imageModel`, `imageGenerationModel`, `videoGenerationModel`, `musicGenerationModel`, `pdfModel` — each with primary+fallbacks. Also `pdfMaxBytesMb`, `pdfMaxPages`. | P2 | High |
| **Model routing** | `ModelRouter` interface + `FirstAvailableRouter`, `RegexKeywordRouter`, `LlmBasedRouter`. Routes by request content analysis. | Agent binding-based: routes by channel + accountId + peer + guildId + teamId matching. Model selection is through config hierarchy, not runtime router. | P2 | Medium |

### 1.4 Thinking / Reasoning / Verbose / Elevated Controls

| Sub-Category | LyClaw Current State | OpenClaw Implementation | Gap Severity | Complexity |
|---|---|---|---|---|
| **Thinking budget** | Not present. No thinking/reasoning budget control at any level. | `AgentConfig.thinkingDefault: "off" \| "minimal" \| "low" \| "medium" \| "high" \| "xhigh" \| "adaptive" \| "max"` — 8-level thinking budget (inherited from defaults). | P1 | Medium |
| **Verbose mode** | Not present. | `AgentConfig.verboseDefault: "off" \| "on" \| "full"` — controls verbosity of agent responses. | P2 | Low |
| **Reasoning visibility** | Not present. | `AgentConfig.reasoningDefault: "off" \| "on" \| "stream"` — controls whether reasoning traces are visible to the user and whether they stream. | P1 | Medium |
| **Fast mode** | Not present. | `AgentConfig.fastModeDefault: boolean` — skips certain processing steps for speed. | P2 | Low |
| **Elevated mode** | Not present. No concept of permission elevation. | `AgentConfig.elevatedDefault: boolean` (inherited) — allows agents to request elevated permissions. | P2 | Medium |
| **Block streaming** | Not present. Streaming is controlled by method return type (`Flux` vs `String`), not by agent config. | `AgentConfig.blockStreamingDefault: boolean` — forces non-streaming mode regardless of other settings. | P3 | Low |
| **Tool progress detail** | Not present. SSE events include `tool_call` with status (executing/done) but no configurable detail level. | `AgentConfig.toolProgressDetail: "explain" \| "raw"` — controls how tool execution progress is presented to the user. | P2 | Low |

### 1.5 Runtime Behavior Controls

| Sub-Category | LyClaw Current State | OpenClaw Implementation | Gap Severity | Complexity |
|---|---|---|---|---|
| **Human delay simulation** | Not present. | `AgentConfig.humanDelay: number \| [number, number]` — simulates typing delay (ms) for more natural interaction pacing. | P3 | Low |
| **TTS configuration** | Not present. | `AgentConfig.tts: AgentTtsConfig` — text-to-speech configuration with provider, voice, language, speed. Deep-merged into `messages.tts`. | P3 | Medium |
| **Context limits framework** | Not present as agent-level config. No per-agent `contextTokens` or tool result truncation limits. | `AgentConfig.contextLimits` with 5 fields: `memoryGetMaxChars`, `memoryGetDefaultLines`, `toolResultMaxChars`, `postCompactionMaxChars`, `contextTokens`. | P1 | Medium |
| **Heartbeat** | Not present. | `AgentConfig.heartbeat` with 10 fields: `every`, `activeHours` (start/end/timezone), `model`, `session`, `prompt`, `lightContext`, `isolatedSession`, `skipWhenBusy`, `includeReasoning`. | P2 | Medium |
| **Identity configuration** | Not present. | `AgentConfig.identity: AgentIdentityConfig` — agent persona/identity settings. | P3 | Low |
| **Group chat settings** | Not present at agent config level. Group chat handled by `CollaborationHub` + `ConsensusEngine` separately. | `AgentConfig.groupChat: GroupChatConfig` — agent's behavior within group chat scenarios. | P2 | Medium |
| **Run retries** | Not present. Retry logic exists only at the pipeline level (ReflectionStage retry block with MAX_REFLECTION_RETRIES=2). | `AgentConfig.runRetries: number` — number of times to retry the entire agent run on failure. | P2 | Low |
| **Embedded PI** | Not present. | `AgentConfig.embeddedPi: boolean` — whether to use an embedded PI (Process Intelligence) runtime within the agent. | P2 | High |
| **Sandbox config** | Basic: `SandboxLevel` enum (DIRECT, SANDBOX, DISABLED) set by `SecurityCheckHook`. `ToolSandbox` interface delegates execution. | `AgentConfig.sandbox: AgentSandboxConfig` with 10+ fields: `mode` (off/non-main/all), `backend` (docker), `workspaceAccess` (none/ro/rw), `sessionToolsVisibility`, `scope`, `workspaceRoot`, plus nested `docker`/`ssh`/`browser`/`prune` sub-configs. Docker settings include `image`, `network`, `memory`, `cpus`, `gpus`, `seccomp` (20+ sub-fields). | P1 | High |
| **Params** | `@Agent(extensions={@Extension(key="...", value="...")})` — flat key-value pairs. No structured param objects. | `AgentConfig.params: Record<string, unknown>` — arbitrary structured parameters with full JSON-like nesting. | P2 | Medium |
| **Runtime** | Not present as config. Agent always runs in-process via JDK dynamic proxy. | See [Category 2: Agent Runtime Modes](#category-2-agent-runtime-modes). | P0 | Very High |

### 1.6 Tool Configuration

| Sub-Category | LyClaw Current State | OpenClaw Implementation | Gap Severity | Complexity |
|---|---|---|---|---|
| **Tool profile/presets** | Not present. Tools are registered globally in `ToolRegistry`. Per-request tool filtering via `ToolCallPolicy` + `ToolDefinition.getAllDefinitions(ChatRequest)`. | `AgentConfig.tools.profile: "minimal" \| "coding" \| "messaging" \| "full"` — four preset tool profiles. | P1 | Medium |
| **Tool allow/deny lists** | Not present at agent config level. Tool filtering is runtime-only via `ToolCallPolicy`. | `AgentConfig.tools.allow: string[]` (whitelist), `tools.alsoAllow: string[]` (additive), `tools.deny: string[]` (blacklist, highest priority). | P1 | Medium |
| **Per-provider tool overrides** | Not present. | `AgentConfig.tools.byProvider: Record<string, ToolPolicyConfig>` — tool policies overridden per model provider. | P2 | Medium |
| **Tools by sender** | Not present. | `AgentConfig.tools.toolsBySender: GroupToolPolicyBySenderConfig` — different tool policies depending on message sender. | P3 | Medium |
| **Code mode** | Not present. | `AgentConfig.tools.codeMode: CodeModeConfig` — QuickJS WASI sandbox for code execution. | P2 | High |
| **Elevated tools** | Not present. | `AgentConfig.tools.elevated: { enabled, allowFrom }` — tools requiring elevated permissions. | P2 | Medium |
| **Exec/Filesystem tool config** | Not present as agent-level config. Shell execution handled by `ToolSandbox` + `SandboxLevel`. | `AgentConfig.tools.exec: ExecToolConfig`, `tools.fs: FsToolsConfig` — detailed per-agent configurations for shell execution and filesystem tools. | P2 | Medium |
| **Loop detection** | Basic: `maxToolRounds=30` in `AgentProperties`. | `AgentConfig.tools.loopDetection: ToolLoopDetectionConfig` — configurable loop detection with thresholds, patterns, actions. | P2 | Medium |
| **Message tools config** | Not present. | `AgentConfig.tools.message: MessageToolsConfig` — configuration for messaging-related tools. | P3 | Low |
| **Sandbox tools** | Not present. | `AgentConfig.tools.sandbox: { tools: { allow, alsoAllow, deny } }` — sandbox-specific tool allow/deny lists. | P2 | Medium |

### 1.7 Detailed Comparison: @Agent Annotation vs AgentConfig Object

Below is a side-by-side mapping of every `@Agent` annotation field and its closest equivalent (or lack thereof) in OpenClaw's `AgentConfig`:

```
LyClaw @Agent Field          OpenClaw AgentConfig Equivalent
─────────────────────────────────────────────────────────────
name: String                 id: String (required, unique)
                             name: String (optional, display-only)
                             → Names serve different roles; OpenClaw separates identity from display
description: String          description: String
                             → Similar purpose, but OpenClaw uses it for UI only (not as fallback prompt)
version: String              (none)
                             → LyClaw has versioning; OpenClaw handles it at deployment level
model: String                model: AgentModelConfig (string | {primary, fallbacks[]})
                             → LyClaw: single string, provider in separate field
                             → OpenClaw: combined "provider/model" format with fallback chain
provider: String             (embedded in model string "provider/model")
                             → LyClaw separates provider from model name
extensions: Extension[]      params: Record<string, unknown>
                             tools: AgentToolsConfig
                             sandbox: AgentSandboxConfig
                             contextLimits: AgentContextLimits
                             heartbeat: AgentHeartbeat
                             thinkingDefault, verboseDefault, reasoningDefault, etc.
                             → LyClaw's flat key-value extensions are the precursor to OpenClaw's
                               structured sub-configs; every OpenClaw sub-config field maps to a
                               potential extension key in LyClaw
```

### 1.8 Implementation Notes: Anticipated Changes to AgentConfig

Given the gap severity, LyClaw's `AgentConfig` (currently 6 core fields) will need to expand significantly. The following structural changes are anticipated:

1. **Add `id` field** distinct from `name`. The `id` becomes the canonical reference for all internal lookups (routing, subagent references, session binding, logging).
2. **Add nested config objects**: `modelConfig` (primary+fallbacks), `toolsConfig` (profile/allow/deny/byProvider), `sandboxConfig` (mode/backend/docker), `contextLimits` (memoryGetMaxChars/toolResultMaxChars/contextTokens).
3. **Add behavioral controls**: `thinkingDefault`, `verboseDefault`, `reasoningDefault`, `fastModeDefault`, `elevatedDefault`.
4. **Add directory fields**: `workspace`, `agentDir`.
5. **Add runtime field**: `runtime` (embedded/acp discriminator).
6. **Add subagent field**: `subagents` (delegationMode/allowAgents/model).
7. **Maintain backward compatibility**: The `@Agent` annotation's `extensions` array should continue to work, with structured config fields taking precedence over equivalent extension keys when both are set.

---

## Category 2: Agent Runtime Modes

### 2.1 Current LyClaw Architecture: Always Embedded

LyClaw agents are currently **always embedded**. The execution path is:

```
User Request
    │
    ▼
Spring MVC Controller (e.g., ChatController)
    │
    ▼
ChatAgent.chat(message)   ← JDK Dynamic Proxy
    │
    ▼
AgentInvocationHandler.invoke()
    │
    ▼
ReActEngine.execute(chatFacade, request, toolExecutor)
    │
    ▼
ChatFacade → ModelRouter → ChatModel (e.g., DeepSeekChatModel)
    │
    ▼
HTTP call to LLM API (e.g., api.deepseek.com)
    │
    ▼
Response returned in-process
```

There is no mechanism to say "this agent should run in a separate process" or "this agent is actually a remote ACP service." The `A2aGateway` provides basic agent-to-agent task dispatch but operates at a different layer — it dispatches tasks to external agents, not runs LyClaw agents remotely.

### 2.2 OpenClaw's Discriminated Union Runtime Model

| Sub-Category | LyClaw Current State | OpenClaw Implementation | Gap Severity | Complexity |
|---|---|---|---|---|
| **Embedded runtime** | Implicit only. All agents run in-process via JDK `Proxy.newProxyInstance()` through `AgentInvocationHandler`. No runtime mode concept — it is always embedded. | `AgentRuntimeConfig = { type: "embedded" }` — explicit declaration that the agent runs in-process. The agent's code executes within the same Node.js process. | P1 | Low |
| **ACP runtime (remote agent)** | No ACP protocol support. `A2aGateway` provides basic Agent-to-Agent communication (getAgentCard, sendTask, getArtifact, cancelTask) but agents themselves are always local. | `AgentRuntimeConfig = { type: "acp", acp: { agent, backend, mode, cwd } }` — ACP (Agent Communication Protocol) runtime. Agent runs as a remote process communicating via a standard protocol. Supports `mode: "persistent" \| "oneshot"`, `backend` override, `cwd` override. | P0 | Very High |
| **Runtime policy config** | Not present. | `AgentRuntimePolicyConfig = { id?: string }` — runtime identifier. `id="pi"` for built-in PI runtime, `id="auto"` for automatic plugin selection. Can be set per-model in `AgentModelEntryConfig.agentRuntime`. | P1 | Medium |
| **Runtime mode selection** | Not present. No mechanism to choose between embedded vs remote execution per agent. | Per-agent: each `AgentConfig` can specify `runtime.type`. Per-binding: `AgentAcpBinding.acp` can additionally override runtime parameters. Per-model: `AgentModelEntryConfig.agentRuntime` overrides runtime for specific model selections. | P0 | Very High |
| **Runtime binding** | Agents are registered as Spring Beans found via `@Agent` annotation scanning. No binding concept beyond Spring DI. | `AgentBinding = AgentRouteBinding \| AgentAcpBinding`. Route binding maps channels/accounts to agents; ACP binding maps ACP endpoints to agents. Each binding type carries its own runtime configuration. | P1 | High |
| **CWD (working directory) per runtime** | Not present. | `AgentRuntimeConfig.acp.cwd` — working directory override for ACP runtime. `AgentConfig.workspace` — workspace directory for embedded runtime. Two separate directory concepts. | P1 | Medium |
| **Persistent vs oneshot sessions** | `AgentContext.Lifecycle` enum (TRANSIENT / SESSION / PERSISTENT) exists but is a context-level concept, not a runtime mode. Checkpoint/restore via `toSnapshot()` / `restoreFromSnapshot()`. | ACP mode supports `mode: "persistent"` (long-lived agent process) vs `mode: "oneshot"` (spawned per task). Different lifecycle management. | P1 | Medium |

### 2.3 Architectural Implications of Multi-Runtime Support

Adding ACP runtime support to LyClaw will require significant architectural changes:

**Current flow (all embedded):**
```
AgentInvocationHandler.invoke()
    → ReActEngine.execute(chatFacade, request, toolExecutor)
    → ChatFacade → ChatModel → HTTP to LLM API
    → Return result
```

**Target flow (with ACP runtime support):**
```
AgentInvocationHandler.invoke()
    → resolveAgentExecutionContract(agentId)
    → if (runtime.type == "embedded"):
          ReActEngine.execute(chatFacade, request, toolExecutor)  [current path]
      else if (runtime.type == "acp"):
          AcpClient.sendTask(acpConfig.agent, taskSpec)
          → Wait for remote agent to complete (poll or SSE)
          → Collect remote tool calls and results
          → Return final response
```

Key design decisions:
1. **Where does tool execution happen?** In ACP mode, the remote agent has its own tool registry. LyClaw's `ToolExecutor` (function interface) does not apply. The local proxy becomes a thin stub.
2. **How is the pipeline affected?** The 6-stage pipeline (ContextBuild→SecurityCheck→...→Metrics) currently runs locally. For ACP agents, some stages (SecurityCheck, Sandbox, Approval) must still run locally (gatekeeping), while others (PlanExecution, Respond, Reflection) are delegated to the remote runtime.
3. **What about hooks?** `wrapToolCall` and `wrapToolExecutor` hooks are meaningless for ACP agents (tool execution is remote). `beforeRequest` and `afterResult` hooks still apply.
4. **Session and context serialization**: For ACP mode, the `AgentContext` must be serializable (or partially serializable) to send to the remote runtime. The current `AgentContext` holds live references (`ToolRegistry`, `Method`, `Object[] args`) that cannot be serialized.

---

## Category 3: Subagent Delegation

### 3.1 Current LyClaw Architecture: No Subagent Concept

LyClaw has two separate systems that partially overlap with subagent functionality, but neither provides true subagent delegation **within the agent execution loop**:

1. **AgentCoordinator.dispatch(ChatContext, AgentTask)** — top-level task distribution. Dispatches a task to an agent and returns `CompletableFuture<AgentResult>`. This is an orchestration-layer operation: a controller or service decides which agent to invoke, not the agent itself.

2. **AutoScaler** — manages agent pool size (scale up/down) based on `AgentPoolSnapshot`. This is infrastructure-level, not task-level delegation.

Neither allows Agent A to say, during its ReAct loop: "I need help with this sub-task, let me spawn Agent B to handle it, wait for the result, and incorporate it into my response."

### 3.2 OpenClaw Subagent Architecture

OpenClaw's subagent system is integrated directly into the agent turn loop. The LLM is given a `sessions_spawn` tool. When the LLM decides a task should be delegated, it calls this tool. The framework then:

1. Validates the request against `SubagentConfig` (allowlist, concurrency, depth limits)
2. Resolves the subagent's configuration (model, thinking level, timeout)
3. Spawns the subagent (embedded or via ACP)
4. Waits for completion (with timeout)
5. Injects the result back into the parent's conversation as a tool result

This means subagent delegation is transparent to the LLM — it appears as just another tool. The complexity of spawning, monitoring, and collecting is handled by the framework.

| Sub-Category | LyClaw Current State | OpenClaw Implementation | Gap Severity | Complexity |
|---|---|---|---|---|
| **Subagent concept** | **Not present.** No subagent spawning or delegation within the agent execution loop. `AgentCoordinator.dispatch()` distributes tasks to agents at the orchestration layer (separate from the core ReAct loop), but agents cannot spawn sub-agents during their own execution. | Full subagent system integrated into the agent turn loop. Agents can spawn child agents using `sessions_spawn` tool during execution. The parent agent monitors children, collects results, and continues. | P0 | Very High |
| **Delegation mode** | Not present. | `SubagentConfig.delegationMode: "suggest" \| "prefer"` — `"suggest"` means the model can choose whether to delegate; `"prefer"` means the system strongly encourages delegation for appropriate tasks. | P0 | High |
| **Agent allowlist** | Not present. | `SubagentConfig.allowAgents: string[]` — explicit allowlist of agent IDs that can be spawned as subagents. `"*"` means all agents are allowed. | P0 | Medium |
| **Max concurrent subagents** | Not present. | `SubagentConfig.maxConcurrent: number` (default 1) — maximum number of simultaneously running child agents. Only settable at defaults level (safety boundary). | P0 | Medium |
| **Max spawn depth** | Not present. | `SubagentConfig.maxSpawnDepth: number` (default 1, meaning no nesting) — maximum depth of agent → subagent → sub-subagent chains. Only settable at defaults level. | P0 | Low |
| **Max children per agent** | Not present. | `SubagentConfig.maxChildrenPerAgent: number` (default 5) — maximum number of child agents a single parent can spawn. Only settable at defaults level. | P0 | Low |
| **Subagent archiving** | Not present. | `SubagentConfig.archiveAfterMinutes: number` (default 60) — automatically archive completed subagent sessions after N minutes. | P1 | Medium |
| **Subagent model** | Not present. | `SubagentConfig.model: AgentModelConfig` — default model for subagent sessions. Can be different from parent agent's model. | P1 | Low |
| **Subagent thinking level** | Not present. | `SubagentConfig.thinking: string` — thinking budget level specifically for subagent sessions. | P2 | Low |
| **Subagent timeout** | Not present. | `SubagentConfig.runTimeoutSeconds: number` (default 0 = no timeout) — maximum runtime for a subagent before forced termination. | P0 | Medium |
| **Announce timeout** | Not present. | `SubagentConfig.announceTimeoutMs: number` (default 120000) — timeout for delivering announcements to subagents. | P2 | Low |
| **Require agent ID** | Not present. | `SubagentConfig.requireAgentId: boolean` (default false) — when true, `sessions_spawn` must explicitly specify an `agentId` rather than using defaults. | P2 | Low |
| **Subagent result collection** | `AgentCoordinator.dispatch()` returns `CompletableFuture<AgentResult>`, but this is not integrated into the agent's ReAct loop. | Subagent results are automatically collected and injected into the parent agent's conversation context as tool results from `sessions_spawn`. Parent can see what each child did. | P0 | High |
| **Parent-child lifecycle** | Not present. `AutoScaler` manages pool scaling but not parent-child relationships. | Full lifecycle: parent spawns → child runs → child completes → parent receives result → child archived after `archiveAfterMinutes`. Parent can also terminate children. | P0 | High |

### 3.3 Architectural Implications of Adding Subagent Support

Adding subagent delegation will be the single largest architectural change to LyClaw. Here is what must happen:

**Current ReAct loop (simplified):**
```
for round in 0..maxToolRounds:
    response = chatFacade.chat(request)
    if response.hasToolCalls():
        for toolCall in response.getToolCalls():
            result = toolExecutor.execute(toolCall.name, toolCall.id, toolCall.arguments)
            messages.add(Message.tool(toolCall.id, result))
    else:
        return response.getContent()
```

**Target ReAct loop with subagent support:**
```
for round in 0..maxToolRounds:
    response = chatFacade.chat(request)
    if response.hasToolCalls():
        for toolCall in response.getToolCalls():
            if toolCall.name == "sessions_spawn":         ← NEW: subagent spawn tool
                subagentId = parseSubagentId(toolCall.arguments)
                // 1. Validate: is subagentId in allowAgents?
                // 2. Check limits: maxConcurrent, maxSpawnDepth, maxChildrenPerAgent
                // 3. Resolve subagent config (model, thinking, timeout)
                // 4. Spawn subagent session (embedded or ACP)
                // 5. Wait for completion (with timeout)
                // 6. Collect result → inject as tool result
                result = spawnAndWait(subagentId, toolCall.arguments)
            else:
                result = toolExecutor.execute(toolCall.name, toolCall.id, toolCall.arguments)
            messages.add(Message.tool(toolCall.id, result))
    else:
        return response.getContent()
```

Key design decisions:
1. **Subagent as a special tool**: The subagent spawn is presented to the LLM as a tool (`sessions_spawn`). The LLM decides when to spawn. This is clean — no changes to the ReAct algorithm itself.
2. **Concurrency model**: Subagents can run concurrently (`maxConcurrent > 1`). The ReAct loop must support `CompletableFuture.allOf()` for parallel subagent execution, collecting results as they complete.
3. **Nesting**: If `maxSpawnDepth > 1`, subagents can spawn sub-subagents. This requires depth tracking in `AgentContext` (add `spawnDepth` field).
4. **Resource limits enforcement**: `maxConcurrent`, `maxSpawnDepth`, `maxChildrenPerAgent` must be enforced at the framework level, not configurable per-agent. These are safety boundaries set in `AgentDefaultsConfig`.
5. **Result injection format**: Subagent results are injected as tool results with a structured format (subagent ID, status, output summary) so the parent LLM can understand what happened.
6. **Integration with runtime modes**: A subagent can have a different runtime than its parent (parent embedded, child ACP). The `resolveAgentExecutionContract()` function handles this.

### 3.4 Comparison: AgentCoordinator vs Subagent System

| Aspect | LyClaw AgentCoordinator | OpenClaw Subagent System |
|---|---|---|
| **Who triggers** | External caller (controller/service) | Parent agent during ReAct loop (via `sessions_spawn` tool) |
| **Decision maker** | Orchestration layer (code decides) | LLM decides (presented as a tool) |
| **Integration** | Separate from agent execution | Integrated into conversation context as tool results |
| **Concurrency** | Single dispatch per call | Multiple concurrent subagents per parent turn |
| **Lifecycle** | Fire-and-forget or Future-based | Full lifecycle: spawn → monitor → collect → archive |
| **Nesting** | Not supported | Supported with depth limits |
| **Resource control** | Via AutoScaler (pool-level) | Via SubagentConfig (per-agent limits) |

---

## Category 4: Config Hierarchy & Resolution

### 4.1 Current LyClaw Config Resolution Flow

```
AgentConfigResolver.resolve("chat")
    │
    ├── YamlAgentConfigSource.loadConfig("chat")     priority=10
    │       lyclaw.chat.models.chat.model
    │       lyclaw.chat.models.chat.provider
    │       lyclaw.chat.default-model (fallback)
    │
    ├── AnnotationAgentConfigSource.loadConfig("chat")  priority=50
    │       @Agent(name="chat", model="...", provider="...",
    │              extensions={@Extension(key="planning.enabled", value="true")})
    │
    ├── [DB config source — reserved slot, priority=60, unimplemented]
    ├── [Config center source — reserved slot, priority=70, unimplemented]
    │
    └── BuilderAgentConfigSource.loadConfig("chat")   priority=100
            LyClawAgent.builder(ChatAgent.class).model("...").provider("...")

    Result: Flat AgentConfig with highest-priority value for each key
```

**Critical limitation**: There is no AgentDefaultsConfig. Every field must be explicitly set on every agent or fall back to hardcoded framework defaults. There is no mechanism for "all agents inherit this value unless explicitly overridden."

### 4.2 OpenClaw Config Resolution Flow

```
resolveAgentConfig("chat")
    │
    ├── Load AgentDefaultsConfig (global defaults, ~50 fields)
    │       model: "deepseek/deepseek-v4-flash"
    │       thinkingDefault: "off"
    │       tools.profile: "full"
    │       contextLimits.toolResultMaxChars: 16000
    │       subagents.maxConcurrent: 1       ← defaults-only (not overridable per agent)
    │       subagents.maxSpawnDepth: 1       ← defaults-only (safety boundary)
    │       imageModel: { primary: "openai/gpt-4o", fallbacks: [...] }
    │       compaction.mode: "default"
    │       compaction.reserveTokens: 20000
    │       ...
    │
    ├── Load AgentConfig for "chat" (~40 fields, only what is explicitly set)
    │       id: "chat"
    │       default: true
    │       description: "General-purpose chat agent"
    │       systemPromptOverride: "You are a helpful assistant..."
    │       model: "deepseek/deepseek-v4-pro"  ← overrides default
    │       thinkingDefault: "medium"           ← overrides default
    │       skills: ["web-search", "code-interpreter"]  ← REPLACES defaults (not merged)
    │       tools: { profile: "coding", deny: ["shell_exec"] }
    │       ...
    │
    ├── Deep Merge: defaults → agent
    │       For each field in AgentConfig:
    │         If field is set in AgentConfig (not undefined/null) → use it
    │         If field is NOT set in AgentConfig → inherit from AgentDefaultsConfig
    │       Special merge semantics:
    │         - skills: explicit setting REPLACES (not merges) defaults
    │         - tts: deep-merge recursively (nested fields merged)
    │         - subagents.maxConcurrent: ONLY in defaults (ignored if set in AgentConfig)
    │         - tools.allow/deny: layered (profile → allow → alsoAllow → deny)
    │       → Canonical Config
    │
    └── Apply Runtime Overrides
            Session-level effective model (user switched models mid-session)
            Binding-level runtime config (ACP binding cwd override)
            → Effective Config (used for this specific execution turn)
```

### 4.3 Config Hierarchy Table

| Sub-Category | LyClaw Current State | OpenClaw Implementation | Gap Severity | Complexity |
|---|---|---|---|---|
| **Config layers** | **3 layers (existing):** (1) `application.yml` via `YamlAgentConfigSource` (priority 10), (2) `@Agent` annotation via `AnnotationAgentConfigSource` (priority 50), (3) `LyClawAgent.builder()` via Builder (priority 100). Also reserved slots for DB (60) and config center (70) — but these are unimplemented. **No defaults layer.** | **2 layers with deep merge:** (1) `AgentDefaultsConfig` (~50 fields) — global defaults applied to all agents, (2) `AgentConfig` (~40 fields) — per-agent overrides. Deep-merge: `defaults → agent → resolved`. Agent-level fields that are not set inherit from defaults. | P0 | High |
| **Defaults config** | **Not present.** `AgentProperties` (6 fields: defaultMode, maxToolRounds, approvalTimeoutSeconds, approvalStoreTimeoutSeconds, timeoutMs) is the only global configuration, and it is flat/behavioral, not a config template for agents. | `AgentDefaultsConfig` — full mirror of `AgentConfig` structure (~50 fields). Defines the fallback values for every agent-level field. Includes fields that ONLY exist in defaults (not in AgentConfig): multi-model configs, context pruning, startup context, compaction (27 fields), subagent resource limits. | P0 | Very High |
| **Config merge strategy** | Priority-based overwrite (highest priority wins). No deep merge — flat key-value overwrite via `AgentConfigResolver.resolve()`. The `extensions` map on `AgentConfig` has key-level overwrite semantics. | Deep merge: nested objects (like `tts`, `sandbox`, `tools`, `contextLimits`) are merged recursively. Some fields use **replacement** semantics (e.g., `skills` — explicitly set skills completely replace, not merge with, defaults). Some fields are **defaults-only** (e.g., `subagents.maxConcurrent`). | P0 | High |
| **Resolved config** | `AgentConfigResolver.resolve(agentName)` returns an `AgentConfig` with merged flat properties + extensions map. The resolved config has no concept of a "canonical" or "effective" config. | Three layers of resolution: (1) raw `AgentConfig` from config file, (2) merged with `AgentDefaultsConfig` → canonical config, (3) runtime overrides (session-level, binding-level, model-level) → effective config. | P0 | High |

### 4.4 Config Scope & Coverage

| Sub-Category | LyClaw Current State | OpenClaw Implementation | Gap Severity | Complexity |
|---|---|---|---|---|
| **Total configurable fields** | ~15 fields across `@Agent` (6: name, description, version, model, provider, extensions[]) + `AgentProperties` (6) + `ChatProperties` (few). Extensions support ad-hoc key-value pairs (documented keys: planning.enabled, planning.strategy, memory.topK, tool.dynamicFiltering, mcp.servers, outputGuard.enabled, communication.protocol, maxToolRounds, sandbox). | ~90+ fields across `AgentConfig` (37 fields) + `AgentDefaultsConfig` (additional ~15 defaults-only fields) + nested sub-configs: `AgentModelConfig`, `AgentModelEntryConfig`, `AgentRuntimeConfig`, `AgentRuntimePolicyConfig`, `AgentToolsConfig`, `AgentSandboxConfig`, `AgentContextLimits`, `AgentHeartbeat`, `AgentIdentity`, `GroupChatConfig`, `SubagentConfig`, `ContextPruningConfig`, `StartupContext`, `CompactionConfig` (27 fields), `ModelDefinitionConfig`, `AgentBinding`. | P0 | Very High |
| **Thinking controls** | Not present. | 4 separate controls: `thinkingDefault` (8 levels), `verboseDefault` (3 levels), `reasoningDefault` (3 levels), `fastModeDefault` (boolean). | P1 | Medium |
| **Context token limit** | Not present. | `AgentDefaultsConfig.contextLimits.contextTokens` — hard limit on context window tokens. | P1 | Low |
| **Context pruning** | Not present. | `AgentDefaultsConfig` has dedicated `ContextPruningConfig` with `mode`, `ttl`, `keepLastAssistants`, `softTrimRatio`, `hardClearRatio`, `minPrunableToolChars`, tool allow/deny, `softTrim` (maxChars, headChars, tailChars), `hardClear` (enabled, placeholder). | P1 | High |
| **Compaction config** | Not present. Basic message truncation exists but no configurable compaction strategy. | `AgentDefaultsConfig` has full `CompactionConfig` with 27 fields: `mode`, `reserveTokens`, `keepRecentTokens`, `reserveTokensFloor`, `maxHistoryShare`, `customInstructions`, `recentTurnsPreserve`, `identifierPolicy`, `qualityGuard` (enabled, maxRetries), `midTurnPrecheck`, `postIndexSync`, `memoryFlush` (enabled, model), `postCompactionSections`, `model`, `timeoutSeconds`, `notifyUser`. | P1 | Very High |
| **Startup context** | Not present. | `AgentDefaultsConfig` has `StartupContext` with `enabled`, `applyOn` (new/reset), `dailyMemoryDays`, `maxFileBytes`, `maxFileChars`, `maxTotalChars`. | P2 | Medium |

### 4.5 Model Resolution

| Sub-Category | LyClaw Current State | OpenClaw Implementation | Gap Severity | Complexity |
|---|---|---|---|---|
| **Model resolution flow** | `ChatFacade.route(request, context)` → `ModelRouter.route()` → `RoutingDecision` (provider + model + tier + reason). Then `ChatFacade.resolveModel(decision)` → `ChatModel`. Model is chosen per-request via routing, not from agent config. | `resolveAgentExplicitModelPrimary(agentId)` → reads `AgentConfig.model` (string or {primary, fallbacks}). `resolveAgentEffectiveModelPrimary(agentId)` → walks the resolution chain: explicit → defaults → system fallback. `resolveAgentModelFallbacksOverride(agentId)` → returns the ordered fallback list. Auto-fallback probing at runtime when primary fails. | P0 | High |
| **Effective model** | Not present as a concept. The model used is whatever `ModelRouter` decides at runtime, with `modelOverride` from `AgentProxyFactory` applied. | `setAgentEffectiveModelPrimary(agentId, model)` — runtime override of the effective primary model (e.g., when user switches models mid-session). Separates "configured" from "effective." | P1 | Medium |
| **Subagent model selection** | Not applicable (no subagents). | `resolveSubagentModelConfigSelection(parentAgentId, subagentConfig)` — determines which model a subagent should use based on parent config, subagent config, and defaults. | P0 | Medium |
| **Model catalog** | `ChatProperties.models` — flat map of model name → `ModelProperties` (provider, model, baseUrl, apiKey). | Full `ModelDefinitionConfig` catalog with `id`, `name`, `api` (9 API types), `reasoning` (boolean), `input` (modalities: text/image/video/audio), `cost` (input/output/cacheRead/cacheWrite per million tokens), `contextWindow`, `maxTokens`, `compat` (23 compatibility fields). | P2 | High |
| **Provider discovery** | `ChatModelProvider` interface — providers are registered as Spring beans. | Automatic provider discovery from model catalog entries. Model `id` format "provider/model" parsed to identify provider. Provider-specific API params stored in `AgentModelEntryConfig.params`. | P2 | Medium |

### 4.6 Tool Resolution

| Sub-Category | LyClaw Current State | OpenClaw Implementation | Gap Severity | Complexity |
|---|---|---|---|---|
| **Tool availability** | All tools from `ToolRegistry.getAllDefinitions(request)` are available to all agents. Filtering at runtime via `ToolCallPolicy`. | Per-agent tool configuration via `AgentConfig.tools` with layered policy: `profile` (preset) → `allow` (whitelist) → `alsoAllow` (additive) → `deny` (blacklist, highest priority) → `byProvider` → `toolsBySender` → `sandbox.tools`. | P0 | High |
| **Tool profile presets** | Not present. | 4 presets: `minimal` (only session_status), `coding` (files+runtime+network+memory+session+plans+media), `messaging` (message tools only), `full` (all tools via `["*"]`). | P1 | Medium |
| **Code execution mode** | `ToolSandbox` provides basic sandbox isolation but no code-mode concept. | `AgentConfig.tools.codeMode: CodeModeConfig` — dedicated QuickJS WASI sandbox for executing code within the agent context. | P2 | High |
| **Elevated tools** | `ApprovalHook` + `ApprovalStore` provides tool approval flow with user confirmation. But no concept of "elevated" vs normal permissions. | `AgentConfig.tools.elevated: { enabled, allowFrom }` — tools that can only be used when the agent is in elevated mode. `elevatedDefault` controls whether agent starts elevated. | P2 | Medium |

---

## Category 5: Agent Scope Resolution

### 5.1 What is Agent Scope Resolution?

Agent scope resolution is the set of functions that answer questions like:
- "Which agents exist in the system?"
- "Which agent should handle this incoming message?"
- "What is the effective configuration for agent X right now?"
- "What model should agent X use (considering defaults, overrides, and session state)?"
- "What tools are available to agent X?"
- "Which workspace directory does agent X use?"

In LyClaw, most of these questions are answered ad-hoc: Spring's `ApplicationContext` provides bean scanning, `AgentConfigResolver` provides flat config merging, and `ModelRouter` provides request-time model selection. There is no unified "scope resolution" subsystem.

In OpenClaw, there is a dedicated scope resolution layer (~30 functions) that provides a single source of truth for all agent-related queries. This layer is the foundation that the agent runner, routing, subagent spawning, and admin UI all depend on.

### 5.2 Comparison Table

| Sub-Category | LyClaw Current State | OpenClaw Implementation | Gap Severity | Complexity |
|---|---|---|---|---|
| **List agent entries** | `ApplicationContext.getBeansWithAnnotation(Agent.class)` — scans Spring context for `@Agent`-annotated beans. `AnnotationAgentConfigSource.loadConfig()` iterates these beans to find config by name. Ad-hoc, no dedicated registry query method. | `listAgentEntries(): AgentEntry[]` — dedicated function returning all agent entries with full metadata. Used by admin UI, routing, subagent selection. | P1 | Medium |
| **List agent IDs** | No dedicated function. Names are extracted from annotation scanning or config resolution ad-hoc. | `listAgentIds(): string[]` — returns all registered agent IDs. Used for validation, allowlists, and UI dropdowns. | P1 | Low |
| **Resolve default agent ID** | Not present. No concept of a default/fallback agent. | `resolveDefaultAgentId(): string` — returns the `id` of the agent with `default: true`. Used when no routing binding matches. | P1 | Low |
| **Resolve agent config** | `AgentConfigResolver.resolve(agentName)` — merges from registered `AgentConfigSource` instances by priority. Returns `AgentConfig` with flat properties + extensions map. No defaults layer. | `resolveAgentConfig(agentId): AgentConfig` — returns the fully merged config (defaults → agent). Cached after first resolution. | P0 | High |
| **Resolve agent context limits** | Not present. | `resolveAgentContextLimits(agentId): AgentContextLimits` — resolves the effective context limits for an agent (memory get max chars, tool result max chars, context tokens, etc.). | P1 | Low |
| **Resolve agent workspace directory** | Not present. No per-agent workspace concept. | `resolveAgentWorkspaceDir(agentId): string` — resolves the agent's workspace directory from config, with path normalization and existence checks. | P1 | Medium |
| **Resolve agent private directory** | Not present. | `resolveAgentDir(agentId): string` — resolves the agent's private data directory. | P2 | Medium |
| **Resolve session agent IDs** | Not present. Sessions have a `model` field but no concept of which agent is handling the session. | `resolveSessionAgentIds(sessionKey): string[]` — returns the agent IDs associated with a given session. Sessions can be handled by multiple agents. | P1 | Medium |
| **Resolve agent execution contract** | Not present. No formal execution contract concept. | `resolveAgentExecutionContract(agentId): AgentExecutionContract` — returns the resolved runtime type, mode, backend, cwd for an agent. | P0 | High |
| **Resolve agent skills filter** | Not present. Skills are global. | `resolveAgentSkillsFilter(agentId): string[]` — returns the effective skills whitelist for an agent (agent-level overrides defaults, explicit setting replaces not merges). | P1 | Medium |
| **Resolve agent IDs by workspace path** | Not present. | `resolveAgentIdsByWorkspacePath(workspacePath: string): string[]` — finds all agents whose workspace matches a given path. Used for multi-tenant deployments. | P3 | Medium |
| **Resolve fallback agent ID** | Not present. | `resolveFallbackAgentId(): string` — returns a hardcoded system fallback agent ID when no default is configured. | P1 | Low |
| **Resolve agent model primary (explicit)** | Not present. `AgentProxyFactory` has `modelOverride` and `providerOverride` fields set from `@Agent` annotation during proxy creation. No runtime query to ask "what model does agent X use." | `resolveAgentExplicitModelPrimary(agentId): string \| undefined` — returns the agent's explicitly configured primary model (without applying defaults). | P0 | Medium |
| **Resolve agent model primary (effective)** | Not present. | `resolveAgentEffectiveModelPrimary(agentId): string` — returns the effective primary model after applying defaults and runtime overrides. This is what the agent actually uses. | P0 | Medium |
| **Set agent effective model primary** | Not present. No runtime model switching per agent. | `setAgentEffectiveModelPrimary(agentId, model)` — overrides the effective primary model at runtime (e.g., user switches model mid-session). | P1 | Medium |
| **Resolve agent model fallbacks override** | Not present. | `resolveAgentModelFallbacksOverride(agentId): string[] \| undefined` — returns the agent's fallback model list override. | P1 | Low |
| **Resolve subagent model config selection** | Not applicable. | `resolveSubagentModelConfigSelection(parentId, subagentConfig): AgentModelConfig` — determines model for a subagent session considering parent config, subagent config, and defaults. | P0 | Medium |
| **Agent entry metadata** | `AgentConfig` (6 core fields + extensions map). No structured entry type. | `AgentEntry` — structured metadata object containing id, name, description, default flag, workspace, agentDir, runtime config, binding info, and full resolved config reference. | P1 | Medium |
| **Agent routing lookup** | Not present. `ModelRouter` does content-based routing, not agent routing. `AgentCoordinator.dispatch()` distributes tasks but routing logic is separate from agent registration. | `AgentRouteBinding.match` — channel + accountId + peer (kind, id) + guildId + teamId + roles → agentId. Route-based agent selection before execution. | P1 | High |
| **Config caching** | Not present. `AgentConfigResolver.resolve()` recomputes on every call (iterates all sources, merges). | Resolved configs are cached after first computation. Cache invalidation on config reload. | P2 | Low |
| **Config hot-reload** | Not present. Config sources are initialized at startup. | Config file watcher: changes to agent config files trigger re-resolution and cache invalidation. Running agents are not interrupted but new turns use updated config. | P3 | Medium |
| **Per-session config overrides** | Not present. | Session-level overrides: `effectiveModel` can be set per-session (user switches model). Some config fields can be overridden by session metadata. | P1 | Medium |

### 5.3 Agent Registration & Discovery: LyClaw vs OpenClaw

**LyClaw approach (Spring-based):**
```
ApplicationContext.getBeansWithAnnotation(Agent.class)
    │
    └── For each bean with @Agent:
            Extract name, description, version, model, provider, extensions
            → This scanning happens in AnnotationAgentConfigSource
            → Used only for config loading, not for runtime agent lookup
```

Limitations:
- Agents are discovered as Spring beans only. No support for agents defined purely in config files (without a corresponding Java class).
- No centralized agent registry with metadata. Each consumer (config resolver, proxy factory, coordinator) does its own scanning.
- No agent-to-workspace mapping. Workspace is not a concept.
- No route-to-agent binding. Model routing (content-based) and agent routing (identity-based) are not connected.

**OpenClaw approach (dedicated scope functions):**
```
listAgentEntries()
    → Returns AgentEntry[] { id, name, description, default, workspace, agentDir, runtime, bindings }

listAgentIds()
    → Returns string[] (just IDs, for allowlists and dropdowns)

resolveDefaultAgentId()
    → Finds the agent with default: true

resolveAgentConfig(agentId)
    → Deep-merge: AgentDefaultsConfig → AgentConfig → cached result

resolveAgentExecutionContract(agentId)
    → Returns { type: "embedded" | "acp", mode, backend, cwd }

resolveSessionAgentIds(sessionKey)
    → Which agents are handling this session?

resolveAgentIdsByWorkspacePath(path)
    → Multi-tenant: which agents belong to this workspace?
```

### 5.4 Critical Missing Resolution Functions

The following scope resolution functions are architecturally critical and must be implemented before additional agent features (subagents, multi-runtime) can work:

1. **`resolveAgentEffectiveModelPrimary(agentId)`** — Subagent spawning needs to know which model to use for the child. Runtime dispatch needs to know which model the remote agent should load.
2. **`resolveAgentExecutionContract(agentId)`** — The `AgentInvocationHandler` must know whether to execute locally (ReActEngine) or remotely (AcpClient).
3. **`resolveAgentConfig(agentId)`** — Every other resolution function depends on having the fully merged config.
4. **`resolveSubagentModelConfigSelection(parentId, config)`** — Required for the `sessions_spawn` tool implementation.
5. **`listAgentIds()`** — Required for subagent allowlist validation ("is subagentId in allowAgents?").

---

## Summary: Priority Matrix

### P0 (Blocking — must implement before production parity)

| # | Gap | Category | Complexity |
|---|---|---|---|
| P0-1 | **AgentDefaultsConfig** — no defaults layer; every field must be set per-agent or is hardcoded | Config Hierarchy | Very High |
| P0-2 | **Subagent delegation** — agents cannot spawn sub-agents during execution; no delegation mode, allowlists, concurrency limits, depth limits, timeouts | Subagent Delegation | Very High |
| P0-3 | **ACP/remote runtime** — no remote agent execution mode; all agents are embedded in-process | Runtime Modes | Very High |
| P0-4 | **AgentConfig completeness** — ~90 OpenClaw fields vs ~15 LyClaw fields; missing runtime, subagent, sandbox, tools, context limits configs | Agent Configuration | Very High |
| P0-5 | **Config deep-merge** — flat key-value overwrite vs recursive deep-merge with replacement/merge/additive semantics | Config Hierarchy | High |
| P0-6 | **Config resolution (defaults → agent → effective)** — no three-layer resolution chain | Config Hierarchy | High |
| P0-7 | **Model resolution from agent config** — no `resolveAgentEffectiveModelPrimary()`, model chosen at runtime by router, not from agent config | Scope Resolution | High |
| P0-8 | **Agent execution contract resolution** — no `resolveAgentExecutionContract()` to determine runtime type/mode/backend | Scope Resolution | High |
| P0-9 | **Tool policy at agent level** — no per-agent tool allow/deny/profile; tools are global | Agent Configuration | High |
| P0-10 | **Subagent model/result integration** — no subagent result collection into parent conversation context | Subagent Delegation | High |

### P1 (Critical — significant feature gap)

| # | Gap | Category | Complexity |
|---|---|---|---|
| P1-1 | Thinking budget controls (8 levels) | Agent Configuration | Medium |
| P1-2 | Reasoning visibility controls (off/on/stream) | Agent Configuration | Medium |
| P1-3 | Agent identity (separate `id` from `name`) | Agent Configuration | Low |
| P1-4 | Default agent marker (`default: boolean`) | Agent Configuration | Low |
| P1-5 | System prompt override at agent level | Agent Configuration | Medium |
| P1-6 | Workspace directory per agent | Agent Configuration | Medium |
| P1-7 | Skills whitelist per agent (replace semantics) | Agent Configuration | Medium |
| P1-8 | Model primary + fallbacks as agent config | Agent Configuration | Medium |
| P1-9 | Context limits per agent (5 fields) | Agent Configuration | Medium |
| P1-10 | Context pruning config (mode, ttl, ratios, tool pruning) | Config Hierarchy | High |
| P1-11 | Compaction config (27 fields) | Config Hierarchy | Very High |
| P1-12 | Runtime policy config | Runtime Modes | Medium |
| P1-13 | Runtime binding (route + ACP) | Runtime Modes | High |
| P1-14 | Subagent archiving | Subagent Delegation | Medium |
| P1-15 | Subagent model selection | Subagent Delegation | Low |
| P1-16 | List agent entries/IDs with metadata | Scope Resolution | Medium |
| P1-17 | Resolve default agent ID | Scope Resolution | Low |
| P1-18 | Resolve agent context limits | Scope Resolution | Low |
| P1-19 | Resolve agent workspace dir | Scope Resolution | Medium |
| P1-20 | Resolve session agent IDs | Scope Resolution | Medium |
| P1-21 | Resolve agent skills filter | Scope Resolution | Medium |
| P1-22 | Set effective model primary (runtime override) | Scope Resolution | Medium |
| P1-23 | Agent route binding lookup | Scope Resolution | High |
| P1-24 | Per-session config overrides | Scope Resolution | Medium |
| P1-25 | Sandbox config (10+ fields with docker sub-config) | Agent Configuration | High |

### P2 (Important — valuable but not blocking)

| # | Gap | Category | Complexity |
|---|---|---|---|
| P2-1 | Multi-model support (image, video, music, PDF models) | Agent Configuration | High |
| P2-2 | Per-model metadata catalog (ModelDefinitionConfig) | Agent Configuration | High |
| P2-3 | Agent private directory | Agent Configuration | Medium |
| P2-4 | Context injection mode | Agent Configuration | Low |
| P2-5 | Bootstrap file limits | Agent Configuration | Low |
| P2-6 | Verbose mode (3 levels) | Agent Configuration | Low |
| P2-7 | Fast mode | Agent Configuration | Low |
| P2-8 | Elevated mode + elevated tools | Agent Configuration | Medium |
| P2-9 | Tool progress detail | Agent Configuration | Low |
| P2-10 | Heartbeat config (10 fields) | Agent Configuration | Medium |
| P2-11 | Group chat settings | Agent Configuration | Medium |
| P2-12 | Run retries | Agent Configuration | Low |
| P2-13 | Embedded PI | Agent Configuration | High |
| P2-14 | Params (structured JSON-like) | Agent Configuration | Medium |
| P2-15 | Per-provider tool overrides | Agent Configuration | Medium |
| P2-16 | Code mode (QuickJS WASI sandbox) | Agent Configuration | High |
| P2-17 | Exec/filesystem tool config | Agent Configuration | Medium |
| P2-18 | Loop detection config | Agent Configuration | Medium |
| P2-19 | Subagent thinking level | Subagent Delegation | Low |
| P2-20 | Subagent announce timeout | Subagent Delegation | Low |
| P2-21 | Subagent require agent ID | Subagent Delegation | Low |
| P2-22 | Model catalog (9 API types, modalities, costs) | Config Hierarchy | High |
| P2-23 | Provider discovery from model catalog | Config Hierarchy | Medium |
| P2-24 | Config caching | Scope Resolution | Low |
| P2-25 | Resolve agent private dir | Scope Resolution | Medium |
| P2-26 | Startup context config | Config Hierarchy | Medium |
| P2-27 | Sandbox tools config | Agent Configuration | Medium |

### P3 (Enhancement — nice to have)

| # | Gap | Category | Complexity |
|---|---|---|---|
| P3-1 | Agent version field (OpenClaw has none; LyClaw has it, so N/A) | Agent Configuration | N/A |
| P3-2 | Block streaming toggle | Agent Configuration | Low |
| P3-3 | Human delay simulation | Agent Configuration | Low |
| P3-4 | TTS configuration | Agent Configuration | Medium |
| P3-5 | Identity configuration | Agent Configuration | Low |
| P3-6 | Tools by sender | Agent Configuration | Medium |
| P3-7 | Message tools config | Agent Configuration | Low |
| P3-8 | Resolve agent IDs by workspace path | Scope Resolution | Medium |
| P3-9 | Config hot-reload | Scope Resolution | Medium |

---

## Key Architectural Decisions to Carry Forward

### What LyClaw Already Does Well (Strengths to Preserve)

1. **JDK Dynamic Proxy Pattern** — `AgentProxyFactory` + `AgentInvocationHandler` is elegant. The proxy-based invocation allows agent interfaces to be plain Java interfaces with `@Agent` annotations. This is simpler and more type-safe than OpenClaw's JavaScript/TypeScript object-based approach.

2. **Pipeline Stage Architecture** — 6-stage pipeline (ContextBuild → SecurityCheck → PlanExecution → Respond → Reflection → Metrics) with retry block on the core PlanExecution+Respond+Reflection stages. This is more structured than OpenClaw's monolithic agent runner.

3. **AgentHook SPI** — 5 hook points (beforeRequest, beforeModel, afterModel, wrapToolCall, wrapToolExecutor, afterResult) with ordered execution. This is more extensible than OpenClaw's inline event handlers.

4. **3-State Streaming** — DefaultReActEngine's buffering/relaying/tools 3-state machine for stream detection is sophisticated. It detects tool calls mid-stream and seamlessly switches modes.

5. **Config Source SPI** — `AgentConfigSource` interface with priority-based resolution is well-designed. The existing `AgentConfigResolver` can be extended to support deep-merge and defaults layers.

6. **SSE Event Types** — Rich SSE event taxonomy (message, tool_call, tool_approval, status) enables fine-grained frontend rendering.

### What Must Fundamentally Change

1. **From Annotation-Centric to Config-Centric** — The `@Agent` annotation currently drives everything. Must evolve to a system where `AgentConfig` (with defaults layer) is the primary configuration mechanism, and the annotation is just one config source among many.

2. **From Global Tools to Per-Agent Tools** — `ToolRegistry` must support scoped tool visibility. Each agent needs its own tool profile, allow/deny lists, and per-provider overrides.

3. **From In-Process Only to Multi-Runtime** — The proxy-based invocation handler must support dispatching to remote ACP runtimes. This requires a significant architectural addition: the proxy handler becomes a local stub that delegates to a remote agent process.

4. **From Flat Config to Hierarchical Config** — `AgentConfigResolver` must evolve from flat key-value priority merge to recursive deep-merge with field-specific semantics (replace, merge, additive).

5. **From Standalone Agents to Agent Trees** — The ReAct loop must gain the ability to spawn subagents, monitor their progress, and incorporate their results. This is the single largest architectural change needed.

6. **From Router-Driven Model Selection to Config-Driven** — Model selection must move from the `ModelRouter` (content-based) to the agent config hierarchy (identity-based), with routing retained only for the initial agent selection.

---

## Implementation Phasing Recommendation

### Phase 1: Config Foundation (P0-1, P0-4, P0-5, P0-6)

Build `AgentDefaultsConfig`, extend `AgentConfig` to ~40 fields, implement deep-merge in `AgentConfigResolver`. This is the foundation everything else builds on.

### Phase 2: Scope Resolution (P0-7, P0-8, P1-16 through P1-24)

Implement all `resolveAgent*()` functions. These are read-only query functions that depend only on the config system from Phase 1.

### Phase 3: Per-Agent Tools & Models (P0-9, P1-7, P1-8, P1-9)

Scoped tool visibility, model primary+fallbacks per agent, context limits per agent.

### Phase 4: Subagent System (P0-2, P0-10, P1-14, P1-15)

Agent spawning within ReAct loop, result collection, lifecycle management.

### Phase 5: Multi-Runtime (P0-3, P1-12, P1-13)

ACP runtime support, remote agent execution, runtime binding.

### Phase 6: Advanced Features (P1-1, P1-2, P2-*)

Thinking controls, reasoning visibility, sandbox config, multi-model, compaction, heartbeat.

---

> **Next**: Part 2 will cover Pipeline & Lifecycle, Memory System, Skill System, Security, and Multi-Agent Communication.

---

# 02 -- Gap Analysis: Hooks, Pipeline, Compaction, Context Management

## Overview

This document provides a detailed side-by-side comparison of LyClaw's current agent hook system, pipeline architecture, compaction, context pruning, context limits, agent finalize gate, and retry strategy against OpenClaw's implementations. Each row identifies the current state of LyClaw, the corresponding OpenClaw capability, the gap severity (P0 = critical/must-have, P1 = high priority, P2 = medium, P3 = nice-to-have), and estimated implementation complexity.

---

## 1. Hook System

| # | Category | LyClaw Current | OpenClaw Implementation | Gap Severity | Complexity |
|---|----------|---------------|------------------------|-------------|------------|
| 1.1 | **Total hook points** | 5 methods on a single `AgentHook` SPI interface: `beforeRequest`, `beforeModel`, `afterModel`, `wrapToolCall`, `wrapToolExecutor`, `afterResult`. Plus `getOrder()` returning an int (default 100). | 36 named hook points across the entire plugin system. Each hook is identified by a string name (e.g. `"before_model_resolve"`, `"agent_turn_prepare"`, `"before_compaction"`, `"subagent_spawning"`). Plugins register handlers per hook name via `PluginHookRegistration`. | **P1** -- LyClaw's 5 coarse-grained lifecycle events cover ~14% of OpenClaw's hook surface area. While not all 36 are needed immediately, the lack of distinct hook names makes it impossible for plugins to selectively subscribe to fine-grained lifecycle events. | Medium |
| 1.2 | **Hook registration model** | Hooks are wired as Spring beans implementing `AgentHook`. The `AgentInvocationHandler` receives a `List<AgentHook>` via constructor injection. All hooks execute in order for every invocation -- no per-hook-name filtering, no conditional registration. | `PluginHookRegistration { pluginId, hookName, handler, priority, timeoutMs, source }`. Plugins register individual handlers for specific hook names. The plugin host resolves which handlers fire for which hook. Supports timeout-based handler abort (timeoutMs) and source attribution for debugging. | **P1** -- LyClaw's "all hooks fire always" model forces every hook implementation to perform its own no-op gating checks at the top of each method. This wastes CPU, complicates hook code, and makes it impossible for third-party plugins to selectively hook into only relevant lifecycle moments. The lack of `timeoutMs` means a misbehaving hook can block the entire agent pipeline indefinitely. | Medium |
| 1.3 | **Hook priority system** | Single integer `getOrder()` (lowest-first). All hooks share the same ordering dimension. There is no distinction between "priority within a lifecycle phase" vs "cross-phase ordering." | `priority` (number) per registration. Since hooks are registered per hook name, plugins can have different priorities for different hook names, allowing fine-grained control (e.g. a security plugin can be high-priority for `before_model_resolve` but low-priority for `after_tool_call`). | **P2** -- LyClaw's flat ordering works for the current 5 built-in hooks but would break down with 20+ hooks from multiple plugins. A per-hook-name priority (or at minimum a phase+order model) is needed for multi-plugin scenarios. | Low |
| 1.4 | **Hook context data richness** | `AgentContext` carries: `sessionId`, `userMessage`, `systemPrompt`, `ChatRequest`, `ToolRegistry`, `Method` (reflection), `Object[]` args (reflection), `SandboxLevel`, `Lifecycle` enum, `TraceContext`, pipeline state counters (`successCount`, `failCount`), `TaskNode` list, `reflectScoreRef`, `pipelineOk`, `terminated`, `currentStage`, and a generic `Map<String,Object> attributes`. **Missing**: runId, jobId, modelProviderId, modelId, messageProvider, trigger type, channelId, contextTokenBudget, contextWindowSource, contextWindowReferenceTokens. | `PluginHookAgentContext` carries: `runId`, `jobId`, `trace`, `agentId`, `sessionKey`, `sessionId`, `workspaceDir`, `modelProviderId`, `modelId`, `messageProvider`, `trigger`, `channelId`, `contextTokenBudget`, `contextWindowSource`, `contextWindowReferenceTokens`. All fields are first-class typed fields, not a generic attribute bag. | **P1** -- The absence of `contextTokenBudget`, `contextWindowSource`, and `contextWindowReferenceTokens` makes it impossible for hooks to make compaction-aware decisions. The absence of `trigger` and `channelId` prevents hooks from differentiating between user-initiated, cron-triggered, or subagent-spawned invocations. The absence of `modelProviderId`/`modelId` prevents hooks from adapting behavior per model. | Low-Medium |
| 1.5 | **Hook decision/gating capability** | Hooks can only block by throwing exceptions (e.g. `SecurityException` in `SecurityCheckHook.beforeRequest`). There is no structured decision return type -- blocking is an all-or-nothing proposition. The only structured outcome is `ctx.setTerminated(true)` which is ad-hoc and convention-based, not enforced by the hook contract. | `InputGateDecision = pass | block(with reason, message, category, metadata)`. `GateHookResult` carries `decision` + `pluginId`. Hooks return structured decisions, enabling the framework to: (a) aggregate multiple gate decisions, (b) log WHY something was blocked with metadata, (c) surface block categories to the user, (d) implement "warn but allow" (degraded pass) semantics. | **P1** -- LyClaw's exception-based blocking is fragile. Exceptions are expensive, lose structured metadata, and cannot express nuanced decisions like "pass with warnings" or "block with suggested remediation." The `SecurityCheckStage` in the pipeline duplicates the same blocking logic that `SecurityCheckHook` has, indicating the hook and pipeline layer are redundantly implementing the same concerns. | Medium |
| 1.6 | **Hook timeout/guard** | No timeout mechanism. A blocking or infinite-looping hook freezes the entire agent invocation. The only timeout is at the tool approval level (`approvalTimeoutSeconds` in `ApprovalHook`, default 30s). | `timeoutMs` on each `PluginHookRegistration`. The plugin host enforces a per-handler timeout. If a handler exceeds its timeout, it is cancelled and the framework either skips it or fails the invocation depending on configuration. | **P2** -- Currently mitigated by the fact that all 5 built-in hooks are trivial (no network calls, no LLM calls). Would become critical once third-party or network-dependent hooks are added. | Medium |
| 1.7 | **Lifecycle coverage: pre-request** | `beforeRequest(AgentContext)` -- fires once at invocation start. Covers: content filtering, security approval, sandbox level assignment. No equivalent for session-level initialization, model resolution, or prompt preparation as distinct phases. | `before_model_resolve` (select which model to use), `agent_turn_prepare` (prepare for a turn), `before_prompt_build` (about to build the system prompt), `before_agent_start` (DEPRECATED), `before_agent_reply` (about to generate a reply), `before_agent_run` (agent about to execute). Each phase is a distinct hook allowing plugins to intervene at the right granularity. | **P1** -- LyClaw's single `beforeRequest` conflates model selection, prompt building, security, and session setup into one amorphous phase. This makes it impossible to, for example, change the model AFTER security checks but BEFORE prompt construction, or to inject session-level data AFTER model resolution. | Medium |
| 1.8 | **Lifecycle coverage: model interaction** | `beforeModel(List<Message>, AgentContext)` -- fires before each LLM call, can modify messages. `afterModel(String, AgentContext)` -- fires after each LLM response, can modify response text. | `model_call_started` (LLM API call initiated), `model_call_ended` (LLM API call completed), `llm_input` (the exact prompt/messages sent to the LLM), `llm_output` (the exact raw response from the LLM). These are observational hooks (cannot modify, only observe/log) separate from the modification hooks. | **P2** -- LyClaw's `beforeModel`/`afterModel` cover modification use cases well. What's missing are observational-only hooks (`llm_input`/`llm_output`) that are guaranteed not to modify the data, which are essential for audit logging, cost tracking, and debugging. Also missing is `model_call_started`/`model_call_ended` which are needed for latency tracking at the LLM API boundary. | Low |
| 1.9 | **Lifecycle coverage: tool execution** | `wrapToolCall(ToolCall, AgentContext)` -- per-tool-call wrapping (step-level). `wrapToolExecutor(ToolExecutor, AgentContext)` -- wraps the executor in a decorator chain (request-level). Both are modification hooks. No observational-only tool hooks. | `before_tool_call` (about to execute), `after_tool_call` (executed, with result), `tool_result_persist` (result about to be persisted to session). Also `before_message_write` (before writing the tool result message to the transcript). | **P2** -- LyClaw's decorator pattern (`wrapToolExecutor`) is elegant for the sandbox/approval use case but conflates "modify execution behavior" with "observe execution." There is no clean way to add a metrics collector that observes tool calls without potentially interfering with the decorator chain. Adding `before_tool_call`/`after_tool_call` as separate hook names would resolve this. | Low |
| 1.10 | **Lifecycle coverage: agent end/finalize** | `afterResult(String, AgentContext)` -- fires after the pipeline completes. Hooks execute in reverse order (descending). Can modify the final result string. No ability to trigger revision, retry, or reject the final result with structured feedback. | `before_agent_finalize` -- hooks can return `{action:"continue"}` (proceed with result), `{action:"revise", reason}` (send back for revision with instruction), or `{action:"finalize", reason}` (force finalization regardless of quality). `agent_end` -- fires after all finalization. `before_agent_reply` -- distinct from finalize, specifically for the reply that gets sent to the user. | **P1** -- LyClaw's `afterResult` is a simple text transformation pass. It cannot trigger revision (send the result back through ReAct with new instructions), cannot force early finalization, and cannot provide structured retry instructions. The current retry logic is hardcoded in `AgentInvocationHandler` with magic numbers (0.6 threshold, 2 max retries) rather than being hook-driven. | Medium |
| 1.11 | **Lifecycle coverage: session** | No session-level hooks. `AgentContext.Lifecycle` enum exists (`TRANSIENT`, `SESSION`, `PERSISTENT`) but is informational only -- no hooks fire on session boundaries. | `session_start`, `session_end`, `before_reset` (session reset). These allow plugins to initialize per-session state, persist session summaries on end, and intercept/block session resets. | **P2** -- Session lifecycle hooks become important when LyClaw supports long-running sessions with compaction and memory. Without them, plugins can't clean up resources on session end or warm caches on session start. | Low-Medium |
| 1.12 | **Lifecycle coverage: message routing** | Not applicable -- LyClaw currently targets a single channel/surface. All invocations go through the same pipeline. | `inbound_claim` (claim responsibility for an inbound message), `message_received` (message arrived), `message_sending` (about to send), `message_sent` (sent), `before_dispatch` (routing decision), `reply_dispatch`. Also `gateway_start`/`gateway_stop` for gateway lifecycle. | **P3** -- Only relevant when LyClaw supports multi-channel (Webchat, API, Slack, etc.) dispatch. The hook architecture should be designed to accommodate these in the future. | High |
| 1.13 | **Lifecycle coverage: subagent** | No subagent concept. The `TaskNode` DAG is executed within a single agent invocation. | `subagent_spawning` (about to spawn), `subagent_delivery_target` (where to send subagent result), `subagent_spawned` (spawn complete), `subagent_ended` (subagent finished). These form a complete subagent lifecycle for hierarchical agent architectures. | **P3** -- DAG-based task decomposition is LyClaw's current model, which doesn't require subagent spawning hooks. This would become relevant only if LyClaw adopts a hierarchical multi-agent architecture. | High |
| 1.14 | **Lifecycle coverage: cron/scheduling** | No cron/scheduling system. | `cron_changed` (cron schedule modified), `heartbeat_prompt_contribution` (contribute to periodic heartbeat prompt). | **P3** -- Only relevant if LyClaw adds autonomous scheduled agent execution. | Medium |
| 1.15 | **Lifecycle coverage: compaction** | No compaction system exists. | `before_compaction` (about to compact), `after_compaction` (compaction complete). These allow plugins to influence compaction parameters (reserve tokens for plugin-specific context, preserve specific messages) and to react to post-compaction state. | **P1** -- Blocked on implementing compaction itself. Once compaction exists, these hooks become critical for plugins that inject context into the transcript (e.g. memory retrieval, RAG) to ensure their injected context survives compaction. | Medium |
| 1.16 | **Hook chain execution model** | Sequential, synchronous execution within the invoking thread. `beforeRequest` hooks execute in `getOrder()` ascending order in a for-each loop. `afterResult` hooks execute in descending order. `wrapToolExecutor` forms a nested decorator chain (each hook wraps the previous). | Plugin host executes handlers concurrently where possible (independent handlers for the same hook can run in parallel). The `InputGateDecision` model supports short-circuit evaluation (first block wins). Timeout enforcement is built into the execution infrastructure. | **P2** -- Sequential execution is appropriate for the current 5 hooks but won't scale to 20+ hooks from third-party plugins. Parallel execution with short-circuit gating would be needed for performance. | Medium |
| 1.17 | **Built-in hook implementations** | 5 hooks: `SecurityCheckHook` (order=10, content filter + security approval), `SandboxHook` (order=20, wraps tool executor with sandbox), `ApprovalHook` (order=30, user approval for write tools), `PlanningHook` (order=40, injects plan DAG into messages), `OutputGuardHook` (order=90, regex-based output content filtering). | No built-in hooks per se -- the plugin system is the extension mechanism. OpenClaw's equivalent built-in behavior is implemented as plugins registered via the same PluginHookRegistration mechanism, not as special framework interfaces. | **P0** -- LyClaw's SecurityCheckHook and SecurityCheckStage duplicate each other's logic (both call securityManager.approve() and contentFilter.filter()). This violates DRY and creates confusion about where security enforcement actually lives. The hook and pipeline stage should be unified or one should delegate to the other. The SandboxHook's wrapToolExecutor conflicts with RespondStage's direct sandbox execution, creating two different sandbox code paths. | Medium |
| 1.18 | **Extensibility: third-party plugins** | Third-party code implements `AgentHook`, declares it as a Spring bean. The hook is automatically picked up by `AgentProxyFactory` / `AgentInvocationHandler`. No isolation, no versioning, no dependency resolution between hooks. | `PluginHookRegistration` with `source` attribution. The plugin host manages plugin lifecycle (install, uninstall, enable, disable). Hooks are scoped to their owning plugin. Plugin dependencies are resolved. | **P2** -- LyClaw's Spring-bean-based discovery is simple and functional but provides no plugin lifecycle management, no hot-reload, and no isolation between plugins. This becomes important for a plugin marketplace. | Medium-High |

### Hook System Summary

LyClaw covers ~14% of OpenClaw's hook surface area with 5 monolithic hook methods covering only the most basic agent lifecycle phases (request start, LLM call, tool call, response post-processing). The most critical gaps are:

1. **No compaction lifecycle hooks** (P1) -- required once compaction is implemented
2. **No structured decision/block semantics** (P1) -- exception-based blocking is fragile
3. **No hook-name-based selective registration** (P1) -- all hooks always fire
4. **No finalize/revision gate** (P1) -- retry logic is hardcoded, not hook-driven
5. **Security hook/stage duplication** (P0) -- two redundant implementations of the same concern

### Detailed Hook Execution Flow Comparison

**LyClaw Hook Execution (Current)**:

```
AgentInvocationHandler.invoke()
  |
  +-- hooks.sort(by order)                    // Sort all hooks by getOrder()
  +-- for each hook: hook.beforeRequest(ctx)   // All hooks fire, no selectivity
  +-- [Pipeline executes stages 0..5]
  |     +-- ContextBuild.execute(ctx)
  |     +-- SecurityCheck.execute(ctx)          // DUPLICATES SecurityCheckHook logic!
  |     +-- PlanExecution.execute(ctx)
  |     +-- Respond.execute(ctx)
  |     |     +-- ReActEngine.executeStream()
  |     |           +-- for each LLM call:
  |     |           |     hook.beforeModel(msgs, ctx)   // All hooks fire
  |     |           |     [LLM API call]
  |     |           |     hook.afterModel(resp, ctx)    // All hooks fire
  |     |           +-- for each tool call:
  |     |                 hook.wrapToolCall(call, ctx)  // All hooks fire
  |     +-- Reflection.execute(ctx)
  |     +-- [Retry block repeats PlanExecution→Respond→Reflection if score<0.6]
  |     +-- Metrics.execute(ctx)
  +-- for each hook (reverse): hook.afterResult(result, ctx)  // All hooks fire
```

**OpenClaw Hook Execution (Reference)**:

```
HarnessContextEngine.runTurn()
  |
  +-- fireHooks("before_model_resolve")       // Only registered handlers fire
  +-- resolveModel()
  +-- fireHooks("agent_turn_prepare")         // Only registered handlers fire
  +-- fireHooks("before_prompt_build")        // Only registered handlers fire
  +-- fireHooks("before_agent_reply")         // Only registered handlers fire
  +-- fireHooks("llm_input")                  // Observational: log input
  +-- [LLM API call]
  +-- fireHooks("llm_output")                 // Observational: log output
  +-- [for each tool call:]
  |     fireHooks("before_tool_call")          // Gate: can block
  |     [execute tool]
  |     fireHooks("after_tool_call")           // Observational: log result
  +-- fireHooks("before_agent_finalize")       // Gate: continue/revise/finalize
  +-- [if revise: inject instruction, retry]
  +-- fireHooks("agent_end")                  // Cleanup
  +-- [between turns:]
  |     fireHooks("before_compaction")         // Only if compaction needed
  |     [compact]
  |     fireHooks("after_compaction")          // Validate compacted context
```

Key differences visible in these flows:
- LyClaw fires ALL hooks at every point; OpenClaw fires only registered handlers for each named hook
- LyClaw lacks the finalize gate (`before_agent_finalize`) which is the critical decision point for retry
- LyClaw's `afterResult` is a simple text pass; OpenClaw's `before_agent_finalize` can trigger revision
- LyClaw duplicates security enforcement in both hook and stage; OpenClaw enforces once at the hook layer
- LyClaw has no inter-turn maintenance hooks; OpenClaw has compaction hooks between turns

---

## 2. Pipeline Architecture

| # | Category | LyClaw Current | OpenClaw Implementation | Gap Severity | Complexity |
|---|----------|---------------|------------------------|-------------|------------|
| 2.1 | **Architecture model** | Linear stage pipeline: 6 stages with integer ordering via `@PipelineStage` annotation + `ReactivePipelineStage` interface. Stages execute via `Flux.concat()` producing `Flux<ServerSentEvent<String>>`. Topological sort resolves `after`/`before` constraints from the annotation. | Context Engine Lifecycle model: 5 phases -- `bootstrapHarnessContextEngine`, `assembleHarnessContextEngine`, `finalizeHarnessContextEngineTurn`, `runHarnessContextEngineMaintenance`, `isActiveHarnessContextEngine`. This is not a linear pipeline but a stateful lifecycle with maintenance phases interleaved between turns. | **P0** -- These are fundamentally different models. LyClaw's linear pipeline works well for single-turn request-response but cannot model turn-over-turn state maintenance, context engine warm-up, or inter-turn garbage collection. The "context engine" model is a persistent state machine that lives across turns, while LyClaw's pipeline is instantiated per invocation. | High |
| 2.2 | **Stage definition** | `ReactivePipelineStage` interface with `execute(AgentContext) -> Flux<SSE>`, `getOrder()`, `getStageName()`. Stages are Spring beans annotated with `@PipelineStage(name, after, before, group)`. The `PipelineStageProcessor` performs topological sort at startup. | Not stage-based. The Context Engine's phases are hardcoded into the engine lifecycle. Customization happens through hooks (plugin hooks at specific lifecycle points) and configuration (compaction settings, context window settings), not through pluggable stages. | **P0** -- This is a fundamental architectural divergence. LyClaw's stage-based approach offers greater extensibility (add/remove/reorder stages) but lower cohesion for the core responsibility of context management. OpenClaw's monolithic-but-hookable context engine offers greater cohesion but less structural extensibility. | High |
| 2.3 | **Pipeline flow** | Fixed sequence: `ContextBuild(0)` -> `SecurityCheck(1)` -> `PlanExecution(2)` -> `Respond(3)` -> `Reflection(4)` -> `Metrics(5)`. The sequence is hardcoded by order integers and `after` constraints. The only dynamic behavior is the retry loop around `PlanExecution+Respond+Reflection`. | Bootstrap -> Assemble (build context from sources: system prompt, memories, tools, red lines) -> Run turn (model call + tool calls) -> Finalize (persist, compact, maintain) -> (repeat for next turn). Maintenance runs can be triggered for reasons: `"bootstrap"`, `"compaction"`, `"turn"`. | **P1** -- LyClaw's sequence conflates "per-turn data preparation" (ContextBuild) with "per-turn execution" (PlanExecution, Respond) with "per-turn post-processing" (Reflection, Metrics). There is no concept of inter-turn maintenance. The ContextBuild stage does memory retrieval but does NOT handle the "assemble final context" step that OpenClaw does (combining system prompt, memories, tool schemas, red lines, user message into a token-budget-aware context). | Medium-High |
| 2.4 | **Context window management** | No context window management. The `ChatRequest.messages` list is unbounded. There is no token counting, no token budget, no truncation, no middle-out compaction. Messages accumulate indefinitely within a session. | `contextTokenBudget` (managed per surface/channel), `contextWindowSource` (which component defined the window), `contextWindowReferenceTokens` (reference token count). The context engine actively manages what fits in the context window, using compaction when the budget is exceeded. | **P0** -- This is the single biggest architectural gap. Without context window management, LyClaw will silently exceed model context limits on long conversations, causing API errors or silently truncated context. Every production agent system MUST manage its context window. | High |
| 2.5 | **Stage-to-stage data passing** | Via `AgentContext` which acts as a mutable shared data bus. Stages read/write to context fields: `setUserMessage()`, `setSandboxLevel()`, `addNode()`, `addToolResult()`, `setAttribute()`, etc. This is a blackboard pattern. | Via the Harness Context Engine's internal state, which is not directly exposed for arbitrary mutation. Plugins influence the context engine through hook return values and configuration, not through direct mutation of a shared state bag. | **P2** -- The blackboard pattern is flexible but creates implicit coupling between stages (e.g., SecurityCheckStage sets sandboxLevel, which RespondStage reads, but this contract is not enforced by the type system). For a small number of stages this is manageable, but for plugin-injected stages it becomes fragile. | Medium |
| 2.6 | **Pipeline observability** | Each stage emits SSE events tagged with the stage name. The `LyClawPipelineEndpoint` actuator exposes pipeline topology and stage status. Trace spans (`TraceContext.beginStage/endStage`) provide per-stage duration tracking. `MetricsCollector` records per-stage duration. | Context engine phases are observed through hook invocations (e.g. `model_call_started`/`ended` for timing) and through the trace system. No explicit stage-per-stage SSE emission -- the engine doesn't expose its internal phase boundaries to the frontend. | **P2** -- LyClaw's per-stage SSE events are good for debugging but add noise to the SSE stream. A dedicated observability channel (logs + metrics + traces) separate from the user-facing SSE stream would be cleaner. | Low |
| 2.7 | **Pipeline error handling** | Each stage wraps its body in try-catch. On error, the stage logs a warning and either: emits a degraded event and continues (ContextBuild, SecurityCheck, PlanExecution, Reflection), or triggers onErrorResume with a fallback response (Respond). Stages never crash the pipeline. | Context engine errors are surfaced through the topic reply mechanism. If context assembly fails, the engine can fail the turn gracefully. Compaction errors have retry logic (quality guard with maxRetries). | **P1** -- LyClaw's "never crash" policy is too permissive. If ContextBuild fails (memory system unavailable), the pipeline silently degrades and continues with an empty memory context. The user gets a degraded response with no indication that memory was unavailable. OpenClaw's approach of failing the turn gracefully (with a clear error to the user) is preferable for some failure modes. | Low |
| 2.8 | **Pipeline vs hook responsibility** | Significant overlap: both the SecurityCheckStage (pipeline stage) and SecurityCheckHook (hook) perform content filtering and security approval. Both access `securityManager.approve()` and `contentFilter.filter()`. The hook fires in `AgentInvocationHandler.invoke()` before the pipeline even starts, then the pipeline stage fires again during stage execution. | Hooks and the context engine have clear separation. Hooks observe and gate; the context engine assembles and executes. There is no duplicated logic between the hook layer and the engine layer because they are architecturally distinct layers with different responsibilities. | **P0** -- This duplication is a bug. The SecurityCheckHook's `beforeRequest` already filters and approves, then SecurityCheckStage does it again. If the hook allows but the stage blocks, the user gets inconsistent behavior. The fix is either: (a) remove SecurityCheckStage and let the hook layer handle security, or (b) remove SecurityCheckHook and let the pipeline stage handle it, or (c) make the hook delegate to the stage's result (read from AgentContext). | Low |
| 2.9 | **Pipeline dynamic reconfiguration** | Not supported. The stage list is computed at handler construction time and is immutable for the lifetime of the handler. There is no way to add/remove/reorder stages per request or per session. | Not directly supported either, but hook-based customization can effectively change the context engine's behavior per invocation (e.g. a hook in `before_compaction` can change compaction parameters). | **P2** -- Per-request stage customization would enable use cases like "skip planning for simple queries" or "enable deep reflection only for complex tasks." The current fixed pipeline treats every request identically. | Medium |

### Pipeline Architecture Summary

The most critical gap is the **absence of a context engine** (P0). LyClaw's linear stage pipeline processes a single turn but has no concept of context window management, inter-turn maintenance, or token budget enforcement. This means:

- LyClaw cannot safely handle conversations longer than the model's context window
- There is no mechanism to compact or truncate the growing message history
- The pipeline treats every invocation as an isolated event, even for SESSION/PERSISTENT lifecycles

The secondary critical gap is the **security enforcement duplication** (P0) between hooks and pipeline stages.

### Stage-Level Responsibility Analysis

Here is a detailed breakdown of what each LyClaw stage currently does versus what it should do in a context-engine-aware architecture:

| Stage | Current Responsibility | Missing Context-Engine Responsibility |
|-------|----------------------|--------------------------------------|
| **ContextBuild** (order=0) | Load session, retrieve memories via `memorySystem.retrieve()`, emit `context_build_start`/`context_build_complete` SSE events | No token budget check. Does not assemble the final context (system prompt + memories + tools + red lines + user message). Does not reserve space for the model response. Does not inject retrieved memories with token-awareness (may overload context). |
| **SecurityCheck** (order=1) | Content filter + security approval, set sandbox level, emit `intercept_start`/`intercept_complete` SSE events | Should be a hook, not a stage. Security enforcement should happen BEFORE context assembly, not as a separate pipeline phase. Having it as a stage means it runs after ContextBuild has already spent time retrieving memories that will be discarded if security blocks. |
| **PlanExecution** (order=2) | Decompose user intent into `TaskNode` DAG via `taskPlanner.plan()`, validate with `planValidator.validate()`, emit `plan_start`/`plan_node`/`plan_complete` SSE events | The plan itself consumes context tokens (injected by PlanningHook). No mechanism to check if the plan context fits within the remaining token budget. No mechanism to abort planning if the context window is nearly full. |
| **Respond** (order=3) | Execute ReAct loop with tool calls, streaming LLM output, tool approval flow, emit `respond_start`/`message`/`tool_call`/`tool_approval` SSE events | No mid-turn precheck for context window limits. Tool results are stored unbounded. No mechanism to truncate large tool outputs. No mechanism to trigger compaction if context window is exceeded mid-turn. |
| **Reflection** (order=4) | Evaluate response quality via `reflectionEngine.reflect()`, compute score, determine `needsRetry`, emit `reflection_start`/`reflection_complete` SSE events | Reflection score is stored in `AgentContext` but the retry decision is hardcoded in `AgentInvocationHandler`. No hook can influence the retry threshold or provide revision instructions. |
| **Metrics** (order=5) | Persist to memory via `memorySystem.ingestPerception()`, record metrics, emit `respond_complete`/`metrics`/`done` SSE events | No post-turn maintenance (compaction, pruning, memory flush). No inter-turn garbage collection. |

### Context Assembly Gap (Detailed)

OpenClaw's `assembleHarnessContextEngine` phase performs context assembly as a distinct, token-budget-aware step:

```
assembleHarnessContextEngine():
  1. Start with system prompt (mandatory, always included)
  2. Add red lines / safety instructions (mandatory, always included)
  3. Count available tokens: contextWindow - reserveTokens - systemPromptTokens - redLinesTokens
  4. Add tool definitions (if space allows, else truncate tool descriptions)
  5. Add memory retrieval results (truncated to memoryGetMaxChars)
  6. Add conversation history:
     a. Compressed summary of old turns (from previous compaction)
     b. Recent turns preserved verbatim (keepRecentTokens)
  7. Add current user message
  8. Reserve remaining tokens for model response (reserveTokens)
  9. If total exceeds budget, trigger compaction before proceeding
```

LyClaw has NO equivalent of this flow. ContextBuild retrieves memories and adds them to the `AgentContext` attribute bag. PlanningHook injects the plan as a system message. RespondStage builds the ChatRequest with tool definitions. But these three operations are uncoordinated -- there is no single point that knows the total token consumption and can make budget-aware decisions.

---

## 3. Compaction

| # | Category | LyClaw Current | OpenClaw Implementation | Gap Severity | Complexity |
|---|----------|---------------|------------------------|-------------|------------|
| 3.1 | **Compaction existence** | **None.** There is no compaction mechanism whatsoever. The message history (`ChatRequest.messages`) grows unbounded. There is no token counting infrastructure. | Fully implemented compaction system with two modes (`"default"` and `"safeguard"`), extensive configuration, token budget management, and quality guard retry logic. | **P0** -- Compaction is a hard requirement for any production agent that handles multi-turn conversations. Without it, conversations exceeding the model's context window (e.g. 128K tokens) will fail with API errors or produce degraded results from silently truncated context. | Very High |
| 3.2 | **Compaction mode** | N/A | `"default"` -- standard compaction that summarizes the conversation history, preserving the most recent turns while compressing older turns into a summary. `"safeguard"` -- an additional safety-oriented compaction that ensures critical context (red lines, system prompt, identity) is never lost. | **P0** | Very High |
| 3.3 | **Token budget management** | No token counting exists anywhere in the codebase. No tokenizer integration. | `reserveTokens` -- tokens reserved at the end of the context window for the model's response. `keepRecentTokens` -- tokens reserved for the most recent conversation turns (kept uncompressed). `reserveTokensFloor` -- minimum reserve tokens even under pressure. `maxHistoryShare` -- maximum fraction of the context window that can be consumed by conversation history. | **P0** -- Token counting is a prerequisite for compaction. LyClaw needs to integrate a tokenizer (tiktoken for OpenAI models, or a generic token counter) and add token tracking to the message list before compaction can even be contemplated. | High |
| 3.4 | **Compaction instructions** | N/A | `customInstructions` -- custom prompt instructions appended to the compaction LLM call, allowing plugins to specify what to preserve, what to emphasize, and how to structure the summary. `recentTurnsPreserve` -- number of recent turns to preserve verbatim (not summarized). | **P0** | Medium |
| 3.5 | **Identifier policy** | N/A | `identifierPolicy`: `"strict"` (preserve all identifiers like names, IDs, URLs), `"off"` (aggressive compression may lose identifiers), `"custom"` (with `identifierInstructions` for domain-specific identifier rules). | **P1** -- Important for enterprise use cases where losing an order ID, customer name, or reference number in compaction would be catastrophic. | Medium |
| 3.6 | **Quality guard** | N/A | `qualityGuard: { enabled: boolean, maxRetries: number }`. After compaction, the system evaluates whether the compacted context is coherent and complete. If not, it retries compaction with adjusted parameters, up to maxRetries. | **P1** -- Compaction quality issues can corrupt the entire conversation state. A quality guard with retry is essential for reliability. | Medium-High |
| 3.7 | **Mid-turn precheck** | N/A | `midTurnPrecheck: { enabled: boolean }`. Before making an LLM call mid-turn, checks if the context window is approaching the limit and triggers a proactive compaction if needed. | **P1** -- Prevents the awkward situation where the model call fails mid-conversation because context was exceeded after adding tool results. | Medium |
| 3.8 | **Post-index sync** | N/A | `postIndexSync: "off" | "async" | "await"`. After compaction, optionally syncs the new summary to the memory/vector index so that future memory retrievals include the compacted history. | **P2** -- Nice-to-have for memory continuity across sessions. | Medium |
| 3.9 | **Memory flush** | N/A | `memoryFlush: { enabled, model, softThresholdTokens, forceFlushTranscriptBytes, prompt, systemPrompt }`. When the transcript reaches a soft threshold, the system proactively flushes conversation content to long-term memory (summarization + vector embedding), reducing the need for in-context history. | **P1** -- This is the bridge between compaction and memory. Without memory flush, compacted history is lost. With memory flush, compacted history is preserved in the memory system for future retrieval. | High |
| 3.10 | **Post-compaction sections** | N/A | `postCompactionSections` -- list of sections that are always injected after compaction (default: `["Session Startup", "Red Lines"]`). These ensure critical system-level context survives compaction. | **P1** | Low |
| 3.11 | **Compaction model** | N/A | `model` -- optional model override for the compaction LLM call. Allows using a cheaper/faster model for compaction (e.g. GPT-4o-mini) while using a more capable model for the main conversation (e.g. Claude Opus). `timeoutSeconds` (default 900). | **P1** -- Cost optimization: compaction calls should not consume expensive model capacity. | Low |
| 3.12 | **Truncation** | N/A | `truncateAfterCompaction` -- if compaction fails or is insufficient, fall back to simple truncation (drop oldest messages). `maxActiveTranscriptBytes` -- hard cap on total transcript size in bytes. | **P1** -- A safety net for when compaction cannot reduce the context enough. | Low |
| 3.13 | **User notification** | N/A | `notifyUser` -- whether to inform the user that compaction occurred (e.g. "I've summarized our earlier conversation to stay within context limits"). | **P3** -- Good UX but not critical. | Low |
| 3.14 | **Compaction hooks** | No hooks exist for compaction (compaction itself doesn't exist). | `before_compaction` and `after_compaction` hooks allow plugins to: (a) save plugin-specific state before it's summarized, (b) modify compaction parameters, (c) restore plugin state after compaction, (d) validate that critical context survived. | **P1** -- Blocked on implementing compaction. Once compaction exists, these hooks become essential for memory plugins, RAG plugins, and any plugin that injects context into the transcript. | Medium |

### Compaction Summary

Compaction is a **P0** gap. It is the single most important missing feature in LyClaw's agent system. Without compaction:

- Long conversations will exceed model context limits
- Session-scoped agents will silently degrade or fail after ~100-200 messages
- Memory retrieval cannot function properly because the growing transcript crowds out retrieved memories
- There is no way to implement the token-budget-aware context assembly that OpenClaw performs

The implementation complexity is **Very High** because compaction touches every layer: token counting (needs a tokenizer), LLM calling (needs a compaction model), context assembly (needs to split context into compressible vs preserve sections), memory integration (post-compaction sync), and the hook system (before/after compaction hooks).

### Compaction Decision Flow (How It Should Work)

For reference, here is the compaction decision flow that LyClaw needs to implement:

```
Before each LLM call (midTurnPrecheck) or at turn start:
  1. Count total tokens in messages list
  2. Calculate: remainingBudget = contextWindow - totalTokens - reserveTokens
  3. If remainingBudget < softThreshold:
     a. Determine what to compact:
        - System prompt: NEVER compact
        - Red lines / safety: NEVER compact (postCompactionSections)
        - Tool definitions: compact (summarize descriptions)
        - Recent turns (last N, keepRecentTokens): PRESERVE verbatim
        - Old turns: COMPRESS via LLM summarization
        - Memory injection: TRUNCATE to memoryGetMaxChars
        - Tool results: PRUNE old/large ones per pruning policy
     b. Call compaction LLM:
        - Model: compactionModelOverride (cheaper model) or main model
        - Prompt: customInstructions + "summarize the following conversation"
        - Input: old turns to compress
        - Timeout: timeoutSeconds (default 900)
     c. Quality guard:
        - Validate compacted output coherence
        - If quality check fails, retry with adjusted params (maxRetries)
     d. Post-compaction:
        - Inject postCompactionSections at top of context
        - Truncate if still over budget (truncateAfterCompaction)
        - Sync to memory index (postIndexSync: async or await)
        - Notify user if notifyUser=true
  4. If remainingBudget >= softThreshold:
     Continue without compaction
```

### Token Counting Prerequisites

Before compaction can be implemented, LyClaw needs:

1. **Tokenizer integration**: Integrate a token counting library. Options:
   - `tikoken` (JTokkit for Java) for OpenAI models
   - Anthropic's token counting for Claude models
   - A generic token counter (character-based approximation with 4 chars/token as fallback)
2. **Token-counted message wrapper**: Extend `Message` to track `tokenCount` per message.
3. **Cumulative token tracking**: Add `AtomicLong totalTokens` to `AgentContext` or a new `ContextBudget` class.
4. **Model-specific context window configuration**: Map of modelId -> maxContextTokens (e.g. `{"gpt-4o": 128000, "claude-sonnet-4-20250514": 200000, "deepseek-v3": 65536}`).
5. **Per-surface budget configuration**: Allow different surfaces (channels) to have different token budgets (e.g. Slack bot gets 32K, web app gets 128K).

---

## 4. Context Pruning

| # | Category | LyClaw Current | OpenClaw Implementation | Gap Severity | Complexity |
|---|----------|---------------|------------------------|-------------|------------|
| 4.1 | **Pruning existence** | **None.** No pruning mechanism. Tool results are stored in full in `toolResults` list and in the message history. | Configurable pruning system with mode `"off"` or `"cache-ttl"`. Prunes tool results from the context after a TTL to free context window space. | **P1** -- Less critical than compaction because it addresses a more specific problem (stale tool results consuming context), but important for agents that execute many tools per turn. | Medium |
| 4.2 | **Pruning mode** | N/A | `"off"` (disabled) or `"cache-ttl"` (time-to-live based pruning). | **P1** | Low |
| 4.3 | **TTL configuration** | N/A | `ttl` -- duration after which tool results become eligible for pruning. `keepLastAssistants` -- number of most recent assistant messages to keep unpruned. | **P1** | Low |
| 4.4 | **Pruning thresholds** | N/A | `softTrimRatio` -- fraction of tool result chars to trim at soft threshold. `hardClearRatio` -- fraction at which to replace the result with a placeholder entirely. `minPrunableToolChars` -- minimum chars a tool result must have to be eligible for pruning. | **P2** | Low |
| 4.5 | **Tool-level control** | N/A | `tools` with `allow`/`deny` lists -- specific tools whose results can or cannot be pruned. For example, a `read_file` result might be prunable (content is in the file), but a `get_user_profile` result might not (the profile info is only in the tool result). | **P1** -- Important for correctness: some tool results are irreplaceable and must never be pruned. | Low |
| 4.6 | **Soft trim** | N/A | `softTrim: { maxChars, headChars, tailChars }`. When trimming, keeps the first `headChars` and last `tailChars` of the result, replacing the middle with an ellipsis. Total trimmed result <= `maxChars`. | **P2** | Low |
| 4.7 | **Hard clear** | N/A | `hardClear: { enabled, placeholder }`. When a tool result exceeds the hard clear threshold, replaces it entirely with a placeholder message like "[Previous tool result cleared to save context space]". | **P2** | Low |
| 4.8 | **Context limits per surface** | No context limits exist. | `memoryGetMaxChars` (default 12000), `memoryGetDefaultLines` (default 120), `toolResultMaxChars` (default 16000), `postCompactionMaxChars` (default 1800). Different limits per operation type. | **P1** -- Without these limits, a single large tool result can consume the entire context window, crowding out conversation history and system instructions. | Low-Medium |

### Context Pruning Summary

Context pruning is a **P1** gap. While less critical than compaction (which is P0), pruning is an important companion feature. Compaction handles conversation history, while pruning handles tool results. Together they form a complete context management strategy. Without pruning:

- Agents that call tools producing large outputs (file reads, database queries, API responses) will see the tool results dominate the context window
- Stale tool results from earlier turns will waste context space
- There is no mechanism to bound individual tool result sizes

### Pruning vs Compaction: When to Use Each

| Scenario | Use Pruning | Use Compaction |
|----------|------------|----------------|
| Large tool result (100K file read) from 3 turns ago, no longer being referenced | Yes -- prune/replace with placeholder | No -- tool results are not conversation history |
| 50-turn conversation with verbose model responses | No -- pruning drops individual messages | Yes -- compress old turns into summary |
| Tool result with sensitive data that should not persist | Yes -- prune after TTL expires | No -- compaction summarization might leak the data |
| System prompt + red lines | Never prune | Never compact (in postCompactionSections) |
| Recent conversation (last 5 turns) | Never prune | Never compact (in keepRecentTokens) |
| Memory injection results | Prune to maxChars if too large | No -- memory is injected, not accumulated |

### Context Pruning Implementation Notes

The pruning system should operate at two levels:

1. **Size-based pruning**: When a tool result exceeds `softTrimRatio` of available context, trim it to `maxChars` (keeping `headChars` from start and `tailChars` from end, replacing middle with `"[...]"`). When it exceeds `hardClearRatio`, replace entirely with `placeholder` text.
2. **TTL-based pruning**: After `ttl` duration, tool results from old turns become eligible for pruning. The `keepLastAssistants` parameter protects recent context.

Per-tool control via `tools.allow`/`tools.deny` lists is essential -- some tools (e.g. `get_user_profile`) return critical information that must not be pruned, while others (e.g. `search_web`) return transient information that can be pruned safely.

---

## 5. Context Limits

| # | Category | LyClaw Current | OpenClaw Implementation | Gap Severity | Complexity |
|---|----------|---------------|------------------------|-------------|------------|
| 5.1 | **Per-surface context limits** | **None.** No token limits, no character limits, no byte limits are enforced anywhere. The only limit is `maxToolRounds` (default from `AgentProperties`) which limits ReAct loop iterations but not context size. | `memoryGetMaxChars` (12000), `memoryGetDefaultLines` (120), `toolResultMaxChars` (16000), `postCompactionMaxChars` (1800). Each limit is per-operation-type and per-surface. | **P0** -- Without any context limits, a single operation can silently consume the entire available context window, causing subsequent operations to fail or producing degraded outputs. This is a production reliability requirement. | Medium |
| 5.2 | **Tool result size limit** | Not bounded. `RespondStage` stores full tool results via `ctx.addToolResult(result.getResult())` with no truncation. The full result is also added to `ChatRequest.messages` as a tool message with no size check. | `toolResultMaxChars` (default 16000) -- any tool result exceeding this is truncated. This prevents a single `read_file` or `web_search` call from consuming the entire context window. | **P0** -- A `read_file` of a large file (or `web_search` returning a large page) can silently consume 100K+ characters of context. The model may still respond, but the crowded context will degrade quality on subsequent turns. | Low |
| 5.3 | **Memory retrieval limit** | ContextBuildStage uses `MemoryQuery.builder().topK(10).build()` -- limits the number of memory entries but does NOT limit the total character count of retrieved memories. A single memory entry could be arbitrarily large. | `memoryGetMaxChars` (12000) limits the total characters of retrieved memory content. `memoryGetDefaultLines` (120) limits the number of lines. | **P1** -- If a memory entry contains a large document chunk, it can crowd out other retrieved memories and the user message. | Low |
| 5.4 | **Post-compaction limit** | N/A (no compaction) | `postCompactionMaxChars` (1800) -- maximum size of the post-compaction summary injected into the context. Prevents the summary itself from consuming too much space. | **P1** -- Blocked on compaction implementation. | Low |
| 5.5 | **Context budget awareness** | No component in LyClaw is aware of the model's context window size. There is no configuration for `maxContextTokens`, no tokenizer, and no budget tracking. | `contextTokenBudget` is a first-class field in `PluginHookAgentContext`, making it available to every hook. The context engine actively tracks remaining budget. | **P0** -- Context budget awareness is a prerequisite for compaction, pruning, and context limits to work correctly. Without knowing how much budget is available, the system cannot decide when to compact, when to prune, or how much to truncate. | Medium-High |
| 5.6 | **Context window source/reference** | No source tracking. | `contextWindowSource` (which configuration defined the window size) and `contextWindowReferenceTokens` (the reference token count for the model). These allow the system to dynamically adjust based on which model is being used. | **P1** -- Different models have different context windows (GPT-4o: 128K, Claude 3.5 Sonnet: 200K, DeepSeek-V3: 64K). LyClaw's hardcoded approach cannot adapt to per-model context limits. | Low |

### Context Limits Summary

Context limits are a **P0** gap. The three most urgent needs are:

1. **Tool result size limits** -- prevent single tool calls from consuming the context window (Low complexity, P0)
2. **Context budget awareness** -- track remaining tokens so compaction/pruning know when to trigger (Medium-High complexity, P0)
3. **Per-model context window configuration** -- adapt limits based on which model is active (Low complexity, P1)

---

## 6. Agent Finalize / Revision Gate

| # | Category | LyClaw Current | OpenClaw Implementation | Gap Severity | Complexity |
|---|----------|---------------|------------------------|-------------|------------|
| 6.1 | **Finalize gate existence** | **None as a structured concept.** The `afterResult` hook fires after the pipeline completes but only provides text transformation. There is no ability to trigger revision, force finalization, or provide structured retry instructions. | `AgentHarnessBeforeAgentFinalizeOutcome = {action:"continue"} | {action:"revise", reason} | {action:"finalize", reason}`. `PluginHookBeforeAgentFinalizeResult = {action, reason, retry?: {instruction, idempotencyKey, maxAttempts}}`. | **P0** -- The finalize gate is the hook system's most powerful control point. It allows plugins to inspect the agent's output and decide whether to accept it, send it back for revision with specific instructions, or force termination. | Medium |
| 6.2 | **Revision with instructions** | `AgentInvocationHandler` has hardcoded retry: if `reflectionScore < 0.6 && failCount > 0`, retry PlanExecution+Respond+Reflection up to 2 times. The retry is blind -- it re-executes the same stages without providing the LLM any feedback about what went wrong. | `retry: { instruction, idempotencyKey, maxAttempts }`. The `instruction` field is injected into the next turn's prompt, telling the model specifically what to fix. The `idempotencyKey` prevents duplicate retries. `maxAttempts` allows per-revision attempt limits. | **P0** -- Blind retry is ineffective. If the model made a mistake (hallucination, wrong tool choice, incomplete answer), re-running the same prompt produces the same mistake. The model needs specific feedback about what to fix. | Medium |
| 6.3 | **Force finalize** | `ctx.setTerminated(true)` is used by SecurityCheckStage to abort the pipeline when content is blocked. This is a binary kill switch, not a graceful finalization. | `{action:"finalize", reason}` -- forces finalization despite quality concerns, with a reason logged. Useful when: (a) max retries exhausted, (b) user explicitly requested finalization, (c) time budget exceeded. | **P1** -- Currently the pipeline either completes normally or is terminated by exception. There is no middle ground of "accept this suboptimal result because constraints require it." | Low |
| 6.4 | **Revision history tracking** | Not tracked. Previous attempts' outputs are discarded; only the final result string is available. If retry 2 produces a WORSE result than retry 1, there is no mechanism to fall back to the best attempt. | The retry system preserves idempotency keys, allowing the system to detect and deduplicate retry attempts. The best attempt can be recovered from the history. | **P1** -- Without revision history, the system cannot select the best attempt, cannot learn from failed attempts, and cannot provide debugging information. | Medium |
| 6.5 | **Idempotency for retries** | Not guaranteed. The current retry in `executeStages()` uses `Flux.repeat()` with a condition. If the SSE connection drops and the client reconnects, the retry counter resets, and the same retry may execute again. | `idempotencyKey` on retry instructions ensures that even across reconnections, the same revision is not applied twice. | **P2** -- Only relevant for SSE/streaming scenarios where connection drops are possible. | Low-Medium |
| 6.6 | **Hook-driven retry vs hardcoded retry** | Hardcoded in `AgentInvocationHandler`: `MAX_REFLECTION_RETRIES = 2`, `REFLECTION_RETRY_THRESHOLD = 0.6`. No hook can influence these values or the retry decision. | The finalize hook result (returned by plugins) drives retry. Different plugins can set different thresholds, different max attempts, and different revision instructions. The orchestration layer aggregates plugin decisions. | **P0** -- Hardcoded magic numbers are anti-extensible. A security plugin might want max 1 retry; a quality plugin might want max 5 retries with increasing temperature. The current architecture cannot support this. | Medium |

### Agent Finalize / Revision Gate Summary

The finalize/revision gate is a **P0** gap because it represents the control point where the agent system decides whether to deliver a response to the user. Without a structured gate:

- Retry is blind (no revision instructions to the model)
- Retry thresholds are hardcoded (not pluggable)
- There is no mechanism to force finalization under time/resource pressure
- There is no revision history for selecting the best attempt

---

## 7. Retry Strategy

| # | Category | LyClaw Current | OpenClaw Implementation | Gap Severity | Complexity |
|---|----------|---------------|------------------------|-------------|------------|
| 7.1 | **Retry trigger mechanism** | Two independent mechanisms: (a) `AgentInvocationHandler.executeStages()` -- if `reflectionScore < 0.6` AND `failCount > 0`, retries PlanExecution+Respond+Reflection via `Flux.repeat()`, max 2 retries. (b) `ReflexionLoop` (separate class) -- execute -> reflect -> revise -> retry loop with configurable `maxRetries` and `qualityThreshold`. The ReflexionLoop is NOT integrated into the main pipeline; it exists as an independent utility. | Retry is triggered via the finalize gate: a plugin returns `{action:"revise", reason, retry: {instruction, maxAttempts}}`. The harness handles the retry by injecting the revision instruction into the next turn and re-running the model. Compaction also has its own quality guard retry (maxRetries). | **P0** -- LyClaw has two disconnected retry mechanisms that don't compose. The pipeline's built-in retry (`executeStages`) is not configurable per-plugin. The `ReflexionLoop` is not wired into the pipeline at all. | Medium |
| 7.2 | **Retry with feedback** | The pipeline retry (`executeStages`) re-executes the same stages identically -- no feedback to the LLM about what went wrong. The `ReflexionLoop` does provide feedback via `TaskPlanner.revise(plan, feedback)` but this only revises the task plan, not the LLM prompt. | Retry includes `instruction` (specific feedback injected into the next prompt), typically generated by the quality/reflection system. The model sees: "Your previous response had issue X. Please retry with correction Y." | **P0** -- Blind retry without feedback is wasteful and often counterproductive. The model needs to know WHAT to fix, not just THAT it needs to fix something. | Medium |
| 7.3 | **Retry scope** | Hardcoded to retry the block `PlanExecution -> Respond -> Reflection`. Cannot retry individual stages, cannot skip PlanExecution on retry (wasteful -- the plan is usually correct, just the execution was wrong). | Revision sends the entire turn back through the model with new instructions. The scope is "the entire model turn," which is coarser but simpler. Since OpenClaw doesn't have LyClaw's stage decomposition, the retry unit is naturally a turn. | **P2** -- LyClaw's stage-granular retry could be an advantage (skip replanning, only re-execute) if properly implemented. Currently it's partially implemented but with the downside of always re-planning. | Medium |
| 7.4 | **Max retry configuration** | Hardcoded constant `MAX_REFLECTION_RETRIES = 2` in `AgentInvocationHandler`. Not configurable. The separate `ReflexionLoop` takes `maxRetries` as a constructor parameter but is not wired to the pipeline. | Per-retry-attempt `maxAttempts` in the retry instruction, allowing plugins to specify different limits for different failure modes. Also per-plugin timeout via `timeoutMs`. | **P0** -- Hardcoded max retries prevent tuning for different scenarios (complex coding tasks might need 5 retries, simple Q&A should have 0). | Low |
| 7.5 | **Retry with revised plan** | `ReflexionLoop` calls `taskPlanner.revise(currentPlan, feedback)` to generate a new plan with adjusted task decomposition. This is the right approach but: (a) it's not integrated into the pipeline, (b) the feedback doesn't include specific LLM prompt revision instructions, only task-level feedback. | N/A -- OpenClaw doesn't use DAG-based task planning, so plan revision is not a concept there. | **P2** -- This is an area where LyClaw's architecture could potentially be superior, but the implementation is not complete (ReflexionLoop is an orphaned utility). | Medium |
| 7.6 | **Retry backoff / rate limiting** | None. Retries happen immediately via `Flux.repeat()` with no delay. | The retry system respects rate limits and can incorporate backoff between retry attempts (implicitly through the harness turn scheduling). | **P2** -- Immediate retries can hammer the LLM API. A small delay (1-2 seconds) between retries is good practice for rate limit compliance and also gives the model a "fresh" context. | Low |
| 7.7 | **Retry metrics / observability** | The reflection score is logged but no structured retry metrics are emitted. The SSE stream doesn't indicate that a retry is happening (no `retry_start`/`retry_attempt` events). | Retry is tracked through the harness's run/job/trace system. Each retry attempt is a distinct model call with its own trace span and metrics. | **P2** -- Retry observability is important for debugging agent loops and for cost tracking (retries consume additional LLM calls). | Low-Medium |
| 7.8 | **Failed retry fallback** | If all retries are exhausted, the last result is returned as-is (no fallback strategy). In the blocking path (`executeStagesBlocking`), the `finalResponse` from the last attempt is returned regardless of quality. | The finalize gate's `{action:"finalize", reason}` forces termination. The harness can be configured with a fallback message when quality cannot be achieved. | **P1** -- After exhausting retries, the system should either: (a) return the best attempt from history, (b) return a clear error indicating the agent could not produce a satisfactory response, or (c) escalate to a human. Silently returning a low-quality result is the worst option. | Low |

### Retry Strategy Summary

The retry strategy has **P0** gaps because the current retry mechanism:

1. **Is blind** -- no feedback/instruction is given to the model on retry
2. **Is hardcoded** -- thresholds and max attempts are compile-time constants
3. **Has two disconnected implementations** -- the pipeline handler has one retry loop, `ReflexionLoop` has another, and they don't share logic
4. **Cannot be influenced by hooks** -- plugins cannot trigger, prevent, or configure retry behavior

The path forward is to integrate retry into the finalize gate: hooks return `{action:"revise", reason, retry:{instruction, maxAttempts}}`, and the pipeline orchestrator handles the retry with proper feedback injection.

### Current Retry Flow vs Target Retry Flow

**Current (LyClaw)**:
```
Pipeline executes: PlanExecution → Respond → Reflection
  ↓
ReflectionStage sets reflectScoreRef in AgentContext
  ↓
AgentInvocationHandler checks: score < 0.6 && failCount > 0?
  ↓ YES (blind retry)
Pipeline re-executes: PlanExecution → Respond → Reflection
  [No feedback to LLM about what went wrong]
  [Repeats up to 2 times, then returns last result regardless]
  ↓ NO
Proceed to Metrics → done
```

**Target (OpenClaw-inspired)**:
```
Pipeline executes: PlanExecution → Respond → Reflection
  ↓
ReflectionStage produces ReflectionReport with:
  - overallScore, errors[], suggestion
  ↓
Fire hook: "before_agent_finalize"
  ↓
Hook returns structured decision:
  ├── {action: "continue"}                    → Proceed to Metrics → done
  ├── {action: "finalize", reason}            → Force done despite quality concerns
  └── {action: "revise", reason,
        retry: {instruction, maxAttempts}}    → Inject instruction, retry
           ↓
Pipeline re-executes with revision instruction:
  - PlanningHook injects: "Previous attempt had issues: {errors}. Please fix: {instruction}"
  - LLM sees specific feedback about what to correct
  - ReflectionStage re-evaluates
  - Best attempt tracked by idempotencyKey
  ↓
After maxAttempts or quality threshold met:
  Select best attempt from history → proceed to Metrics → done
```

### Revision Instruction Format (Proposed)

When a hook triggers revision, the revision instruction should be structured:

```json
{
  "action": "revise",
  "reason": "Response contained hallucinated API parameters",
  "retry": {
    "instruction": "The previous response referenced a non-existent parameter 'user_email'. The correct parameter is 'email'. Please regenerate the API call with the correct parameter name.",
    "idempotencyKey": "revise-hallucination-abc123",
    "maxAttempts": 3,
    "temperatureOverride": 0.3
  }
}
```

The `instruction` is injected as a system message before the retry, so the model sees:
```
[System] Revision requested: The previous response referenced a non-existent
parameter 'user_email'. The correct parameter is 'email'. Please regenerate
the API call with the correct parameter name.
```

This is fundamentally different from blind retry -- the model receives specific, actionable feedback about what went wrong.

---

## 8. Overall Gap Summary

### P0 (Critical -- Must Fix Before Production)

| Gap | Complexity | Description |
|-----|-----------|-------------|
| **Compaction** | Very High | No mechanism to manage growing context. Conversations exceeding the context window will fail. |
| **Context window management / token budget** | High | No token counting, no budget tracking, no context window awareness. Prerequisite for compaction. |
| **Context limits (tool result, memory, per-surface)** | Medium | Tool results and memory entries unbounded in size. Can crowd out conversation context. |
| **Security hook/stage duplication** | Low | SecurityCheckHook and SecurityCheckStage redundantly implement the same concerns. |
| **Finalize/revision gate** | Medium | No structured mechanism for hooks to trigger revision with instructions, force finalization, or provide retry parameters. |
| **Retry with feedback** | Medium | Retry is blind (no revision instructions to the model) and hardcoded (magic numbers). |
| **Pipeline vs hook architectural coherence** | High | Linear stage pipeline vs context engine lifecycle model are fundamentally different. Need to decide which direction LyClaw evolves toward. |

### P1 (High Priority)

| Gap | Complexity | Description |
|-----|-----------|-------------|
| **Hook-name-based selective registration** | Medium | All hooks always fire; need per-hook-name registration and selective execution. |
| **Structured decision/block semantics** | Medium | Exception-based blocking; need `InputGateDecision` with reason/message/category. |
| **Hook context data richness** | Low-Medium | Missing token budget, model info, trigger type, channel info in AgentContext. |
| **Lifecycle coverage expansion** | Medium | Missing compaction hooks, session hooks, model resolution hooks, agent finalize hooks. |
| **Inter-turn maintenance** | Medium-High | No concept of maintenance between turns (compaction, memory flush, garbage collection). |
| **Pipeline error handling policy** | Low | "Never crash" policy is too permissive; need configurable error escalation. |
| **Memory flush** | High | No mechanism to persist compacted conversation to long-term memory. |
| **Compaction quality guard** | Medium-High | No retry/re-validation of compaction output quality. |
| **Compaction mid-turn precheck** | Medium | No proactive compaction before LLM calls mid-turn. |
| **Post-compaction sections** | Low | No guarantee that critical system context survives compaction. |
| **Context pruning (tool results)** | Medium | No TTL-based or size-based tool result pruning. |
| **Tool-level pruning control** | Low | No per-tool allow/deny for pruning eligibility. |

### P2 (Medium Priority)

| Gap | Complexity | Description |
|-----|-----------|-------------|
| **Hook per-name priority** | Low | Flat ordering shared across all hooks; need per-phase or per-name priorities. |
| **Hook timeout/guard** | Medium | No timeout enforcement on hooks. |
| **Observational-only hooks** | Low | No `llm_input`/`llm_output` for audit logging; no `model_call_started`/`ended` for latency. |
| **Hook chain concurrency** | Medium | Sequential execution only; need parallel+short-circuit for scaling. |
| **Plugin lifecycle management** | Medium-High | No install/uninstall/enable/disable for third-party plugins. |
| **Session lifecycle hooks** | Low-Medium | No `session_start`/`session_end`/`before_reset`. |
| **Per-request stage customization** | Medium | Cannot skip/modify stages per request (e.g. skip planning for simple queries). |
| **Retry backoff** | Low | No delay between retries; can hammer LLM API. |
| **Retry observability** | Low-Medium | No structured SSE events for retry progress. |
| **Revision history / best-attempt recovery** | Medium | Cannot recover the best attempt if later retries produce worse results. |

### P3 (Nice-to-Have / Future)

| Gap | Complexity | Description |
|-----|-----------|-------------|
| **Message routing hooks** | High | Multi-channel dispatch (gateway, inbound_claim, etc.) |
| **Subagent lifecycle hooks** | High | Hierarchical multi-agent spawning/ending hooks. |
| **Cron/scheduling hooks** | Medium | Heartbeat and cron-changed hooks. |
| **User notification on compaction** | Low | Inform user when conversation was compacted. |

---

## 9. Recommended Implementation Sequence

Based on dependency analysis, the recommended implementation order is:

### Phase 1: Foundations (weeks 1-3)
1. **Fix security hook/stage duplication** (P0, Low) -- unify into single enforcement point
2. **Add context limits** (P0, Low-Medium) -- tool result max chars, memory retrieval max chars
3. **Add token counting infrastructure** (P0, Medium-High) -- integrate tokenizer, add budget tracking to AgentContext

### Phase 2: Compaction (weeks 4-8)
4. **Implement compaction system** (P0, Very High) -- default mode, token budget management, model override
5. **Add compaction hooks** (P1, Medium) -- before_compaction, after_compaction
6. **Implement memory flush** (P1, High) -- persist compacted history to memory
7. **Add post-compaction sections** (P1, Low) -- preserve system prompt, red lines

### Phase 3: Context Engine (weeks 9-12)
8. **Transition to context engine lifecycle** (P0, High) -- bootstrap, assemble, finalize, maintenance
9. **Implement inter-turn maintenance** (P1, Medium-High) -- compaction on "turn" reason, memory GC
10. **Add context pruning** (P1, Medium) -- cache-ttl mode with tool-level control

### Phase 4: Hook System Overhaul (weeks 13-16)
11. **Named hook points** (P1, Medium) -- expand from 5 methods to 15-20 named hook points
12. **Structured decision model** (P1, Medium) -- InputGateDecision, block with metadata
13. **Finalize/revision gate** (P0, Medium) -- AgentHarnessBeforeAgentFinalizeOutcome with revise/continue/finalize
14. **Retry with feedback** (P0, Medium) -- inject revision instructions into retry prompt

### Phase 5: Production Hardening (weeks 17-20)
15. **Hook timeout enforcement** (P2, Medium)
16. **Retry backoff and observability** (P2, Low-Medium)
17. **Plugin lifecycle management** (P2, Medium-High)
18. **Session lifecycle hooks** (P2, Low-Medium)
19. **Subagent/cron hooks** (P3, High) -- deferred to future releases

---

## 10. Key Design Decisions

### D-1: Linear Pipeline vs Context Engine

LyClaw currently uses a linear stage pipeline. OpenClaw uses a context engine lifecycle. The fundamental question is: should LyClaw evolve toward a context engine model, or enhance the stage pipeline with context management capabilities?

**Recommendation**: Evolve toward a hybrid model. Keep the stage pipeline for per-turn processing (it provides excellent extensibility) but add a persistent ContextEngine that manages the context window across turns. The ContextEngine would be a new singleton service (not a stage) that:
- Tracks token budget across turns
- Triggers compaction when budget is exceeded
- Provides context assembly services consumed by the pipeline stages
- Runs inter-turn maintenance (memory flush, pruning)

The pipeline stages (ContextBuild, SecurityCheck, etc.) would query the ContextEngine for budget information and delegate compaction decisions to it, rather than implementing compaction logic themselves.

### D-2: Hook System Evolution

Two paths:
1. **Incremental**: Add more methods to `AgentHook` (e.g. `beforeCompaction`, `afterCompaction`, `beforeFinalize`). Pros: familiar, low migration cost. Cons: the interface grows bloated, all hooks must implement all methods.
2. **Named hooks**: Redesign around `PluginHookRegistration { hookName, handler, priority }`. Pros: scalable, clean separation of concerns. Cons: migration cost, new concepts.

**Recommendation**: Path 2 (named hooks). The current 5-method interface is already showing strain (security duplication). Named hooks are the industry standard (OpenClaw, LangChain callbacks, Vercel AI SDK middleware). Implement an adapter layer so existing `AgentHook` implementations continue to work during migration.

### D-3: Compaction Model Selection

OpenClaw's compaction uses an LLM call to summarize conversation history. Alternative approaches:
- **LLM summarization** (OpenClaw's approach): most flexible, handles arbitrary conversation content, but costs an LLM call.
- **Sliding window** (simplest): drop oldest messages when budget exceeded. No LLM cost but loses history.
- **Hybrid**: sliding window for recent messages + LLM summarization for older messages.

**Recommendation**: Hybrid, matching OpenClaw's approach. The `keepRecentTokens` parameter preserves the last N turns verbatim, while older turns are summarized. Add a `truncateAfterCompaction` fallback for when LLM-based compaction fails.

---

_This gap analysis covers 7 major categories across 65+ comparison rows. The next document in this series (03) will cover Memory System and Tool System gaps._

---

# Part 3: Gap Analysis -- Model Management & Sandbox

## LyClaw vs OpenClaw Detailed Comparison

This document compares LyClaw's current Model Management and Sandbox subsystems
against OpenClaw's corresponding features, identifying gaps, severity ratings
(P0=blocker, P1=critical, P2=important, P3=nice-to-have), and implementation
complexity estimates (Low/Medium/High/VeryHigh).

---

## Current Architecture Deep Dive: LyClaw Model & Sandbox Subsystem

### Model Layer Architecture

```
application.yml (lyclaw.chat.*)
        |
        v
ChatProperties (defaultProvider, defaultModel, models map, fallbackChain)
        |
        v
@ChatModel annotation scanning --> ChatModelPostProcessor
        |                              |
        |                              v
        |                     ChatModelRegistry.register(provider, model, instance, metadata)
        |
        v
ModelRouter interface
   |
   +-- FirstAvailableRouter (default, naive: picks first model)
   |       |
   |       v
   |   RoutingDecision(provider, model, tier=SIMPLE|STANDARD|COMPLEX|CODE, reason)
   |
   +-- [extensible: RegexKeywordRouter, LlmBasedRouter -- but not implemented]
           |
           v
ChatFacade (DefaultChatFacade)
   |
   +-- route(request, context) -> RoutingDecision
   +-- resolveModel(decision) -> ChatModel
   +-- chat(request)           -> ModelResponse (sync)
   +-- chat().prompt()...      -> ChatClient.Builder (fluent)
   +-- countTokens()
   +-- healthCheck()
   |
   v
ChatModel interface
   |
   +-- OpenAiProtocolChatModel (extends AbstractChatModel)
   |       |
   |       +-- buildNativeRequest() -> JSON Map
   |       +-- sendNativeRequest()  -> WebClient POST to /chat/completions
   |       +-- parseChunk()         -> SSE line -> ModelResponse
   |
   +-- DeepSeekChatModel (extends OpenAiProtocolChatModel)
   |
   +-- [Decorator Chain]
   |     CircuitBreakerChatModel -> RetryChatModel -> FallbackChatModel -> RawChatModel
   |
   +-- ModelCapabilities (streaming, toolCalling, toolCallStreaming, thinking, vision,
   |                       promptCaching, maxInputTokens, maxOutputTokens)
   |
   +-- ChatModelMetadata (provider, displayName, description, protocol, capabilities,
                           defaultModel, defaultBaseUrl, version, priority)

Key observations:
1. The ChatModel interface is well-designed with clear separation of concerns
   (provider identification, streaming, token counting, validation).
2. The decorator pattern (CircuitBreaker, Retry, Fallback) is a strength --
   composable, testable, and annotation-driven.
3. The @ChatModel annotation + ChatModelPostProcessor auto-registration is clean.
4. However, model names are unstructured strings throughout the entire pipeline.
   There is no catalog, no alias resolution, and no input-type awareness.
5. The FirstAvailableRouter is the only concrete router -- it ignores request
   content entirely, making the RoutingTier enum effectively dead code.
6. ChatRequest has a `thinkingEnabled` boolean but no granular thinking controls.
```

### Sandbox Layer Architecture

```
AgentContext.sandboxLevel (set by SecurityCheckHook, order=10)
        |
        v
SandboxHook (order=20)
   |
   +-- wraps ToolExecutor
   +-- delegates to ToolSandbox.execute(tool, args, sandboxLevel)
        |
        v
ToolSandbox interface
   |
   +-- ToolSandboxImpl (@Component)
         |
         +-- execute(tool, args, level)
         |     |
         |     +-- switch(level):
         |           DIRECT  -> executeDirect()    [current thread, no isolation]
         |           SANDBOX -> executeSandbox()   [daemon thread + temp user.dir]
         |           PROCESS -> executeProcess()   [OS subprocess for "command" tool]
         |
         +-- isHealthy() -> AtomicBoolean
         +-- destroy()   -> shutdown thread pool

executeSandbox() internals:
  1. Submit to sandboxExecutor (2-thread daemon pool)
  2. Files.createTempDirectory("lyclaw-sandbox-")
  3. System.setProperty("user.dir", tempDir)  // WEAK: only affects user.dir reads
  4. tool.execute(toolCall, null)
  5. Restore original user.dir
  6. Files.walk(tempDir).sorted(reverse).forEach(Files::delete)
  7. Timeout: 30s via Future.get(timeout)

executeProcess() internals:
  - Only for tool named "command"
  - Delegates to CommandExecutor.execute(command, timeout=30s, maxOutputLength=10000)
  - All other tools: downgrade to executeSandbox()

Key observations:
1. The three-level isolation concept (DIRECT/SANDBOX/PROCESS) is conceptually good.
2. The Hook-based architecture (SandboxHook as AgentHook) is clean and extensible.
3. However, the SANDBOX level provides extremely weak isolation:
   - Same JVM process, same OS user, same network namespace
   - Only switches user.dir system property -- trivially bypassable
   - No memory/CPU limits
   - No filesystem namespace isolation (chroot, mount namespace)
4. The PROCESS level is hardcoded to only the "command" tool.
5. No container-based isolation (Docker/podman) exists.
6. No controlled host<->sandbox filesystem bridge.

---

## 1. Model Configuration & Catalog

| Aspect | LyClaw Current State | OpenClaw Equivalent | Gap Summary | Severity & Complexity |
|--------|---------------------|---------------------|-------------|----------------------|
| **Configuration entry point** | `ChatProperties` (YAML `lyclaw.chat.*`): `defaultProvider`, `defaultModel`, `routingEnabled`, per-provider `ModelProperties` (baseUrl, apiKey, model, retry, fallback, options), `fallbackChain`, `CircuitBreakerProperties` | `AgentModelConfig`: `primary` (provider+model string), `fallbacks` (string[]); `AgentToolModelConfig`: same structure for image/imageGeneration/videoGeneration/musicGeneration/pdf models; `AgentModelEntryConfig`: contextWindow, capabilities, aliases; `mediaGenerationAutoProviderFallback`, `pdfMaxBytesMb`, `pdfMaxPages` | LyClaw has flat YAML config with no structured model metadata. OpenClaw has typed config objects with per-model contextWindow, aliases, and separate configs for different media generation models. LyClaw's `ChatProperties` has no equivalent of `AgentModelEntryConfig` metadata nor media-specific model configs. | **P1 / Medium** |
| **Model Catalog** | No model catalog exists. Models are registered ad-hoc into `ChatModelRegistry` via `ChatModelPostProcessor` scanning `@ChatModel` annotations. Model names are plain strings with no structured metadata beyond what `ChatModelMetadata` record carries (provider, displayName, description, protocol, capabilities, defaultModel, defaultBaseUrl, version, priority). | `ModelCatalogEntry`: id, name, provider, alias, contextWindow, contextTokens, reasoning, input types (text/image/audio/video/document), compat config. Two indexes: `byAlias` Map + `byKey` Map. `buildConfiguredModelCatalog()`, `canonicalizeCaseOnlyCatalogModelRef()`. `ModelRefStatus`: key, inCatalog, allowAny, allowed. | LyClaw lacks any model catalog data structure entirely. Models are opaque strings. No alias resolution, no input-type awareness, no compat config. | **P0 / High** |
| **Model metadata** | `ChatModelMetadata` record: provider, displayName, description, protocol, capabilities, defaultModel, defaultBaseUrl, version, priority. `ModelCapabilities`: streaming, toolCalling, toolCallStreaming, thinking, vision, promptCaching, maxInputTokens, maxOutputTokens. | `AgentModelEntryConfig`: contextWindow, capabilities, aliases. Capabilities include input types (text/image/audio/video/document) and compat flags. | LyClaw's metadata is basic but structured. Missing: contextWindow (only maxInputTokens/maxOutputTokens), aliases, explicit input-type support (image/audio/video/document beyond boolean `vision`). OpenClaw capabilities are richer and input-type-aware. | **P1 / Medium** |
| **Catalog indexing** | None. `ChatModelRegistry` provides flat lookup by `(provider, modelName)` or by `RoutingDecision`. | `ModelAliasIndex`: `byAlias` Map + `byKey` Map for O(1) lookup by alias or canonical key. | LyClaw cannot resolve model aliases (e.g. "gpt-4" -> "gpt-4-0613"). Every model reference must use exact canonical name. | **P2 / Medium** |
| **Allowlist / model restrictions** | No allowlist concept. Any registered model is callable. | `buildConfiguredAllowlistKeys`, `buildAllowedModelSet`, `getModelRefStatus`, `resolveAllowedModelRef`. Models can be restricted via allowlist; `ModelRefStatus` tracks whether a model ref is allowed against the configured set. | LyClaw has no model allowlist or access control. This is a security gap for multi-tenant or restricted deployments. | **P2 / Low** |
| **Model reference resolution** | Simple `ChatModelRegistry.resolve(provider, modelName)` or `resolve(RoutingDecision)`. | ~25 resolution functions: `resolveBareModelDefaultProvider`, `resolveModelRefFromString`, `resolveConfiguredModelRef`, `inferUniqueProviderFromConfiguredModels`, `inferUniqueProviderFromCatalog`, `resolvePersistedOverrideModelRef`, `resolvePersistedModelRef`, `resolveDefaultModelForAgent`, `resolveSubagentConfiguredModelSelection`, `resolveSubagentSpawnModelSelection`, `resolveReasoningDefault`, `isCliProvider`. | LyClaw's resolution is a simple registry lookup. OpenClaw has a multi-layered resolution pipeline: bare model -> default provider inference -> catalog lookup -> persisted overrides -> subagent-specific selection. LyClaw has none of this. | **P0 / VeryHigh** |

---

## 2. Multi-Model Support (Image / Video / Music / PDF)

| Aspect | LyClaw Current State | OpenClaw Equivalent | Gap Summary | Severity & Complexity |
|--------|---------------------|---------------------|-------------|----------------------|
| **Text chat models** | Fully supported via `ChatModel` interface, `OpenAiProtocolChatModel`, `DeepSeekChatModel`. | `AgentModelConfig.primary` is the text model. | Parity in text chat. | **N/A** |
| **Image generation models** | Not supported. No `ImageGenerationChatModel` or equivalent interface. | `AgentToolModelConfig.imageGeneration`: separate provider+model+fallbacks config. Dedicated pipeline for DALL-E / Stable Diffusion / etc. | Complete gap. LyClaw agents cannot generate images as tools. | **P1 / High** |
| **Video generation models** | Not supported. | `AgentToolModelConfig.videoGeneration`: separate provider+model+fallbacks config. | Complete gap. | **P2 / High** |
| **Music generation models** | Not supported. | `AgentToolModelConfig.musicGeneration`: separate provider+model+fallbacks config. | Complete gap. | **P3 / High** |
| **Image understanding (vision)** | `ModelCapabilities.vision` boolean flag exists but no actual image-input pipeline in `ChatRequest` or `Message`. The capability is declared but never consumed. | Full image-input support: input types include "image", images can be attached to messages as `MessageAttachment` with mime type and data. | LyClaw declares vision capability but has no mechanism to include images in chat messages. The `ChatRequest` and `Message` model classes have no attachment/image fields. | **P1 / Medium** |
| **PDF processing model** | Not supported. | `AgentToolModelConfig.pdf`: separate provider+model+fallbacks. `pdfMaxBytesMb` (default 10), `pdfMaxPages` (default 20). | Complete gap. No PDF ingestion or processing pipeline. | **P2 / Medium** |
| **Media auto-fallback** | Not supported. | `mediaGenerationAutoProviderFallback` (default true): when a media-specific model is unconfigured, automatically fall back to the primary text model's provider for media generation. | No equivalent fallback mechanism. Each media model must be explicitly configured. | **P2 / Low** |
| **Audio input** | Not supported. | Input types include "audio" in model capabilities. | Complete gap. No audio transcription or understanding support. | **P3 / Medium** |
| **Document input** | Not supported. | Input types include "document" in model capabilities. | Complete gap. | **P3 / Medium** |

---

## 3. Model Selection & Resolution

| Aspect | LyClaw Current State | OpenClaw Equivalent | Gap Summary | Severity & Complexity |
|--------|---------------------|---------------------|-------------|----------------------|
| **Router interface** | `ModelRouter` interface with `route(ChatRequest, Object) -> RoutingDecision`. | Embedded within the ~25 resolution functions, no separate Router interface -- resolution is functional/compositional. | Different design philosophies. LyClaw uses OOP Router pattern; OpenClaw uses functional resolution pipeline. | **N/A (design difference)** |
| **Routing tiers** | `RoutingTier` enum: `SIMPLE`, `STANDARD`, `COMPLEX`, `CODE`. Purpose: route requests of different complexity to different models. | No direct `RoutingTier` concept. Complexity routing is handled via `ThinkLevel` and `Reasoning` controls on a per-agent basis. | LyClaw's tier system is conceptually richer for cost-optimization routing but only `FirstAvailableRouter` actually uses it. OpenClaw achieves similar ends through thinking level controls. | **P2 / Medium** |
| **Default router** | `FirstAvailableRouter`: iterates registry, picks first non-empty provider's first model. No request analysis. | `resolveDefaultModelForAgent`: resolves from agent config with catalog awareness. | `FirstAvailableRouter` is a naive placeholder. It ignores request content, context, and cost considerations. OpenClaw's default resolution is catalog-aware and config-driven. | **P1 / Low** |
| **Routing decision** | `RoutingDecision` record: provider, model, tier, reason. | Resolution returns specific model ref strings; no separate decision record type. | LyClaw's decision record is a good pattern but under-utilized (only FirstAvailableRouter produces them). | **P3 (low priority)** |
| **Subagent model selection** | Not supported. | `resolveSubagentConfiguredModelSelection`, `resolveSubagentSpawnModelSelection`. Subagents can inherit or override the parent agent's model. | LyClaw has no subagent concept, so no subagent model selection. | **P2 (depends on subagent feature) / Medium** |
| **Persisted model overrides** | Not supported. | `resolvePersistedOverrideModelRef`, `resolvePersistedModelRef`. Per-conversation or per-user model overrides persisted to storage. | LyClaw cannot persist model preferences per user/conversation. | **P2 / Medium** |
| **Bare model resolution** | Not supported. | `resolveBareModelDefaultProvider`: given just a model name (e.g. "gpt-4o"), infer the provider from the catalog. | LyClaw always requires explicit `provider:model` pairs. Cannot resolve a bare model name. | **P2 / Medium** |
| **Provider inference** | Not supported. | `inferUniqueProviderFromConfiguredModels`, `inferUniqueProviderFromCatalog`: when only one provider is configured, infer it automatically for bare model names. | Complete gap. | **P2 / Low** |
| **Model ref status** | Not supported. | `getModelRefStatus`, `ModelRefStatus`: check if a model reference is in catalog, allowed, etc. | No model validation beyond registry lookup. | **P2 / Low** |

---

## 4. Model Fallback & Auto-Probing

| Aspect | LyClaw Current State | OpenClaw Equivalent | Gap Summary | Severity & Complexity |
|--------|---------------------|---------------------|-------------|----------------------|
| **Static fallback chain** | `FallbackChatModel` decorator: recursive try chain of `"provider:model"` entries. Annotation-driven via `@Fallback`. Triggered on `ModelException` or `TimeoutException` by default. | `AgentModelConfig.fallbacks`: string array. Resolution functions process the fallback chain during model selection. | LyClaw's fallback is a runtime decorator; OpenClaw's is config-level. LyClaw's on-error approach is more reactive; OpenClaw's config approach is simpler but less dynamic. Both cover the core need. | **P2 / Low** |
| **Auto-fallback probing** | Not supported. | `AutoFallbackPrimaryProbe`: { provider, model, fallbackProvider, fallbackModel, fallbackAuthProfileId, fallbackAuthProfileIdSource }. `resolveAutoFallbackPrimaryProbe`, `markAutoFallbackPrimaryProbe`, `clearAutoFallbackPrimaryProbeSelection`. Probe state tracked per session key with `Map<string, number>`. Min interval between probes. | LyClaw has no auto-probing. It cannot automatically detect when a primary model recovers and switch back. OpenClaw periodically probes the primary model and switches back when healthy. | **P1 / High** |
| **Probe state tracking** | Not supported (no probing at all). | Per-session-key `Map<string, number>`. Tracks last probe timestamp. Min interval constraint prevents thundering-herd probe storms. | Complete gap. No stateful health tracking across fallback transitions. | **P1 (part of auto-probing) / Medium** |
| **Circuit breaker** | `CircuitBreakerChatModel` decorator: CLOSED -> OPEN -> HALF_OPEN state machine. Configurable failureThreshold (default 5), halfOpenAfterSeconds (30s), halfOpenMaxRequests (3). AtomicReference-based thread safety. | No separate circuit breaker pattern in OpenClaw -- auto-fallback probing serves a similar purpose of detecting recovery. | LyClaw's circuit breaker is well-implemented but per-model-instance. OpenClaw's probing is cross-session-aware. Different approaches to the same problem. | **P3 (existing is adequate) / Low** |
| **Retry with backoff** | `RetryChatModel` decorator: maxAttempts, baseDelayMs, backoff (FIXED/EXPONENTIAL/LINEAR), jitter. Reactive non-blocking via `Mono.delay`. | Not present in the described OpenClaw model layer. | LyClaw has a retry capability that OpenClaw's model layer may lack (run-level retries exist separately). | **N/A (LyClaw advantage)** |
| **Global fallback chain** | `ChatProperties.fallbackChain`: list of "provider:model" strings. | Not directly present. | LyClaw has a global fallback chain config; relationship to per-model @Fallback decorators is unclear (potential conflict). | **P2 / Low** |

---

## 5. Thinking / Reasoning / Verbose Controls

| Aspect | LyClaw Current State | OpenClaw Equivalent | Gap Summary | Severity & Complexity |
|--------|---------------------|---------------------|-------------|----------------------|
| **Thinking toggle** | `ChatRequest.thinkingEnabled` (boolean). `ModelCapabilities.thinking` (boolean). `DefaultChatRequestBuilder.thinking(boolean)`. Simple on/off. | `ThinkLevel`: "off" \| "minimal" \| "low" \| "medium" \| "high" \| "xhigh" \| "adaptive" \| "max". `Reasoning`: "on" \| "off" \| "stream". `resolveThinkingDefault`, `resolveThinkingDefaultWithRuntimeCatalog`, `resolveReasoningDefault`. | LyClaw's boolean thinking toggle is primitive. OpenClaw has granular thinking levels (7 levels + adaptive), separate reasoning mode, and runtime catalog-aware resolution. This is a significant capability gap for agent quality. | **P0 / High** |
| **Reasoning mode** | Not supported. Only thinking on/off. | `Reasoning`: "on" \| "off" \| "stream". Controls whether reasoning/thinking content appears in output or stays hidden. | LyClaw cannot control reasoning visibility or streaming behavior of thinking tokens. | **P0 (part of thinking) / Medium** |
| **Verbose controls** | Not supported. | `Verbose`: "off" \| "on" \| "full". Controls how much internal detail is exposed to the user. | LyClaw has no verbosity controls at the agent/model level. | **P1 / Low** |
| **Elevated mode** | Not supported. | `Elevated`: "off" \| "on" \| "ask" \| "full". Controls whether elevated/privileged operations are allowed and whether user confirmation is needed. | Security-related gap. No elevated privilege mode for sensitive operations. | **P2 / Medium** |
| **Block streaming** | Not supported. | `BlockStreaming`: "off" \| "on". `break`: "text_end" \| "message_end". Controls blocking/streaming behavior at specific message boundaries. | No granular streaming control beyond the model-level streaming boolean. | **P3 / Low** |
| **Fast mode** | Not supported. | `FastMode`: boolean. Optimizes for speed by potentially reducing thinking or using faster models. | Complete gap. | **P3 / Low** |
| **Thinking token budget** | Not directly exposed. `OpenAiProtocolChatModel` may pass `thinking` parameters in the request body but no explicit budget_tokens control. | `ThinkLevel` is provider-mapped to actual thinking token budgets. The catalog-aware resolution ensures provider-specific thinking configurations. | No explicit thinking budget control in LyClaw. Users cannot specify "spend up to N tokens thinking". | **P2 / Medium** |

---

## 6. Provider Discovery

| Aspect | LyClaw Current State | OpenClaw Equivalent | Gap Summary | Severity & Complexity |
|--------|---------------------|---------------------|-------------|----------------------|
| **Provider auto-discovery** | None. Providers must be explicitly configured in YAML or registered via `@ChatModel` annotation on a Java class. | `buildConfiguredModelCatalog`: builds catalog from configured providers. Provider entries include metadata that can be discovered. | LyClaw has no provider discovery mechanism. Every provider requires manual configuration. | **P2 / Medium** |
| **Provider from API** | Not supported. Cannot query a provider's API to discover available models. | Model catalog can be enriched from provider API responses (context window, capabilities, pricing). | Complete gap. Users must manually specify model names and capabilities. | **P2 / High** |
| **Provider protocol detection** | `ModelProtocol` enum: OPENAI, ANTHROPIC, OLLAMA, GEMINI. Manually specified in `@ChatModel` annotation. | Not described as separate from model catalog. Provider is part of the catalog entry. | LyClaw has explicit protocol typing which is good, but it's annotative not discovered. | **N/A (adequate)** |
| **Dynamic provider registration** | `ChatModelRegistry.register()` supports runtime registration. `ChatModelPostProcessor` scans beans at startup. | Not described. | LyClaw's registry is extensible at runtime. | **N/A (adequate)** |
| **Provider health check** | `ChatFacade.healthCheck()` iterates all registered models, calls `validate()` on each. Returns `Map<String, Boolean>`. | Not described as a separate feature. | LyClaw has built-in health check. | **N/A (LyClaw advantage)** |
| **Provider API key rotation** | `OpenAiProtocolChatModel.updateApiKey()` supports runtime key rotation. | Not described. | LyClaw supports hot key rotation. | **N/A (LyClaw advantage)** |

---

## 7. Sandbox Implementation

| Aspect | LyClaw Current State | OpenClaw Equivalent | Gap Summary | Severity & Complexity |
|--------|---------------------|---------------------|-------------|----------------------|
| **Sandbox interface** | `ToolSandbox`: `execute(Tool, Map, SandboxLevel)`, `isHealthy()`, `destroy()`. Clean, well-defined interface. | `AgentSandboxConfig`: Docker/podman container isolation configuration. | LyClaw's interface is well-designed. The gap is in the implementation, not the contract. | **P2 / Low** |
| **Isolation levels** | `SandboxLevel` enum: `DIRECT` (current thread, no isolation), `SANDBOX` (daemon thread + temp directory), `PROCESS` (independent OS process via `CommandExecutor`). | Container-based isolation (Docker/podman). No multi-level enum; single containerized mode. | LyClaw's three-level isolation is more granular than OpenClaw's container-only approach. However, the SANDBOX level is weak: same JVM, same user, no network isolation, no filesystem namespace isolation. | **P1 / High** |
| **Container-based sandbox** | Not supported. No Docker, podman, or any container runtime integration. | `AgentSandboxConfig` with Docker/podman container isolation. | **This is the biggest sandbox gap.** LyClaw cannot run tools in truly isolated containers. The current SANDBOX level only switches `user.dir` -- ineffective against malicious code that doesn't respect `user.dir`. | **P0 / VeryHigh** |
| **Filesystem bridge** | Not supported. `executeSandbox()` creates a temp dir, switches `user.dir`, runs tool, deletes temp dir. No mechanism for the sandboxed tool to read/write files from/to the host filesystem in a controlled way. | `SandboxFsBridge`: controlled filesystem access between container and host. Allows sandboxed code to read specific host files or write results back. | LyClaw's sandboxed tools cannot access host files at all (temp dir is isolated). No bridge for host<->sandbox file exchange. | **P1 / High** |
| **Tool policy with sandbox** | `SandboxHook` (order=20) wraps `ToolExecutor` to delegate to `ToolSandbox`. Uses `SandboxLevel` from `AgentContext`. Hook-based architecture is clean. | Tool policy with sandbox-level allow/deny. Per-tool configuration of whether it runs sandboxed. | LyClaw's hook-based approach is architecturally good. However, sandbox level is determined by security check, not by tool-level policy configuration. | **P2 / Medium** |
| **Sandbox health & lifecycle** | `isHealthy()` AtomicBoolean, `destroy()` shuts down thread pool. Good lifecycle management. | Container start/stop lifecycle. | LyClaw's lifecycle is adequate for its scope. Container management adds complexity. | **P3 / Low** |
| **Process isolation (PROCESS level)** | `executeCommandInProcess()` delegates to `CommandExecutor.execute()`: creates OS subprocess with timeout and output length limits. Only for "command" tool; other tools degrade to SANDBOX. | Container isolation handles all process execution. | LyClaw's PROCESS isolation is limited to the `command` tool specifically. Other tools cannot benefit from process isolation. | **P2 / Medium** |
| **Working directory isolation** | `executeSandbox()` uses `Files.createTempDirectory("lyclaw-sandbox-")` and switches `System.setProperty("user.dir")`. Cleans up after execution. | Container filesystem is inherently isolated. | The `user.dir` switch is a weak isolation mechanism. Any code using `File(".")` or absolute paths bypasses it entirely. This is not real sandboxing. | **P0 / High** |
| **Network isolation** | None. All sandbox levels share the host network. | Container network isolation (bridge network, no network, etc.). | No network isolation in LyClaw. Sandboxed tools can make arbitrary network calls. | **P1 / High** |
| **Resource limits (CPU/Memory)** | No CPU or memory limits in any sandbox level. The daemon thread pool has only 2 threads but no memory constraints. | Container resource limits (cgroups: CPU shares, memory limit). | No resource limiting. A sandboxed tool can exhaust JVM heap. | **P1 / Medium** |
| **Timeout per-level** | Hardcoded `DEFAULT_TIMEOUT_SECONDS = 30` for SANDBOX and PROCESS. DIRECT has no timeout. | Not described at sandbox level. | Timeout is not configurable per tool or per sandbox level. | **P2 / Low** |

---

## 8. Run Retries

| Aspect | LyClaw Current State | OpenClaw Equivalent | Gap Summary | Severity & Complexity |
|--------|---------------------|---------------------|-------------|----------------------|
| **Run-level retries** | Not supported. `RetryChatModel` handles per-request retries at the API call level but there is no concept of retrying an entire agent "run" (multi-turn conversation loop). | Run retries with configurable limits: base=24, perProfile=8, min=32, max=160. Iteration slot budgeting. | LyClaw cannot retry a failed agent run. If the agent loop fails, the entire conversation fails. OpenClaw budgets retry slots across profiles. | **P1 / High** |
| **Iteration slot budgeting** | Not supported. | Retry slots are budgeted per iteration of the agent loop. Prevents runaway retry costs. | No equivalent. | **P1 (part of run retries) / Medium** |
| **Retry at request level** | `RetryChatModel`: maxAttempts, baseDelayMs, backoff (FIXED/EXPONENTIAL/LINEAR), jitter. Reactive, non-blocking. Configurable per-model via `@RetryPolicy` annotation. | Not described at individual request level. | LyClaw's per-request retry is a strength. However, it only covers API call failures, not agent logic failures. | **N/A (LyClaw advantage for API level)** |

---

## Summary: Priority Matrix

### P0 (Blocker -- must implement before production)

| # | Gap | Component | Complexity |
|---|-----|-----------|------------|
| P0-1 | No model catalog -- model names are opaque strings | Model Catalog | High |
| P0-2 | No multi-layered model resolution pipeline | Model Resolution | VeryHigh |
| P0-3 | Boolean-only thinking toggle vs 7-level ThinkLevel | Thinking Controls | High |
| P0-4 | No container-based sandbox (Docker/podman) | Sandbox | VeryHigh |
| P0-5 | `user.dir` switch is not real sandbox isolation | Sandbox | High |

### P1 (Critical -- significant capability gap)

| # | Gap | Component | Complexity |
|---|-----|-----------|------------|
| P1-1 | No structured model config with contextWindow, aliases | Model Config | Medium |
| P1-2 | No image generation / image understanding pipeline | Multi-Model | High / Medium |
| P1-3 | No model input-type awareness (image/audio/video/document) | Model Metadata | Medium |
| P1-4 | FirstAvailableRouter is naive, no content-aware routing | Model Selection | Low |
| P1-5 | No auto-fallback probing (primary model recovery detection) | Fallback | High |
| P1-6 | No verbosity controls at agent/model level | Thinking Controls | Low |
| P1-7 | No network isolation in sandbox | Sandbox | High |
| P1-8 | No resource limits (CPU/memory) in sandbox | Sandbox | Medium |
| P1-9 | No filesystem bridge for sandbox<->host file exchange | Sandbox | High |
| P1-10 | No run-level retries with slot budgeting | Run Retries | High |

### P2 (Important -- should implement)

| # | Gap | Component | Complexity |
|---|-----|-----------|------------|
| P2-1 | No model alias resolution | Model Catalog | Medium |
| P2-2 | No model allowlist / access control | Model Config | Low |
| P2-3 | No video/music generation models | Multi-Model | High |
| P2-4 | No PDF processing model | Multi-Model | Medium |
| P2-5 | No media auto-provider-fallback | Multi-Model | Low |
| P2-6 | No subagent model selection | Model Resolution | Medium |
| P2-7 | No persisted model overrides per user/conversation | Model Resolution | Medium |
| P2-8 | No bare model name resolution (infer provider) | Model Resolution | Medium |
| P2-9 | No provider auto-discovery | Provider Discovery | Medium |
| P2-10 | No thinking token budget control | Thinking Controls | Medium |
| P2-11 | No elevated/privileged mode controls | Thinking Controls | Medium |
| P2-12 | Tool-level sandbox policy configuration | Sandbox | Medium |
| P2-13 | PROCESS isolation limited to "command" tool only | Sandbox | Medium |
| P2-14 | Global fallback chain vs per-model @Fallback relationship unclear | Fallback | Low |
| P2-15 | Sandbox timeout not configurable per tool | Sandbox | Low |

### P3 (Nice-to-have)

| # | Gap | Component | Complexity |
|---|-----|-----------|------------|
| P3-1 | No music generation model support | Multi-Model | High |
| P3-2 | No audio input / document input support | Multi-Model | Medium |
| P3-3 | No block streaming controls | Thinking Controls | Low |
| P3-4 | No FastMode | Thinking Controls | Low |
| P3-5 | RoutingTier enum under-utilized (only FirstAvailableRouter uses it) | Model Selection | Low |

---

## Architecture Recommendations

### 1. Model Catalog (P0-1, P0-2)

Introduce a `ModelCatalog` data structure with `ModelCatalogEntry` (inspired by
OpenClaw's design):

```
ModelCatalogEntry {
    id: string              // canonical key "provider:model"
    name: string            // display name
    provider: string        // provider identifier
    aliases: string[]       // alternative names
    contextWindow: int      // total context window size
    maxInputTokens: int     // max input
    maxOutputTokens: int    // max output
    thinking: boolean       // supports thinking
    inputTypes: Set<InputType>  // TEXT, IMAGE, AUDIO, VIDEO, DOCUMENT
    capabilities: Map<string, any>  // extensible
    pricing: PricingTier    // cost info for routing
}
```

Build `ModelAliasIndex` with `byAlias` and `byKey` maps. Use this for all model
resolution instead of the current flat registry lookup.

### 2. Thinking Controls (P0-3)

Replace `ChatRequest.thinkingEnabled: boolean` with a `ThinkLevel` enum:

```
enum ThinkLevel {
    OFF, MINIMAL, LOW, MEDIUM, HIGH, XHIGH, ADAPTIVE, MAX
}
```

Add `Reasoning` enum (ON/OFF/STREAM) and `Verbose` enum (OFF/ON/FULL).
Map these to provider-specific API parameters in each adapter.

### 3. Container Sandbox (P0-4, P0-5)

Introduce `ContainerSandbox` implementing `ToolSandbox`:

- Use Docker SDK (`docker-java` or `testcontainers`) to create per-execution containers
- Support configurable Docker/podman runtime
- Implement `SandboxFsBridge` for controlled host<->container file exchange:
  - Mount specific host directories as read-only volumes
  - Mount output directory as read-write volume
- Enforce resource limits via container cgroups (CPU shares, memory limit)
- Provide network isolation modes (none, bridge, host)
- Keep the existing `PROCESS` level as a lightweight alternative

### 4. Auto-Fallback Probing (P1-5)

Implement probe-based recovery detection:

- Track `Map<sessionKey, lastProbeTimestamp>` 
- After falling back to a secondary model, periodically probe the primary
- Min interval between probes (e.g., 60s) to prevent thundering herd
- On successful probe, switch back and clear probe state
- On failure, increment backoff and reschedule

### 5. Run Retries (P1-10)

Add agent-run-level retry with slot budgeting:

```
RunRetryConfig {
    baseRetries: int = 24
    perProfileRetries: int = 8
    minRetries: int = 32
    maxRetries: int = 160
}
```

Budget retry slots per iteration. Track consumed vs remaining slots.
Fail the run when budget exhausted.

---

## Implementation Effort Estimate

| Component | Effort | Risk |
|-----------|--------|------|
| Model Catalog + Alias Index | 2-3 weeks | Low -- pure data structures |
| Model Resolution Pipeline | 3-4 weeks | Medium -- many edge cases across providers |
| Thinking/Reasoning/Verbose Controls | 2-3 weeks | Medium -- requires adapter changes |
| Container Sandbox (Docker) | 4-6 weeks | High -- infrastructure, security audit needed |
| Sandbox Filesystem Bridge | 2-3 weeks | Medium -- security surface area |
| Auto-Fallback Probing | 2-3 weeks | Medium -- state management, race conditions |
| Multi-Model Support (image/pdf) | 3-4 weeks | Medium -- new adapter types needed |
| Run Retries | 1-2 weeks | Low -- mostly config + loop control |
| Provider Discovery | 2-3 weeks | Medium -- varies by provider API |
| **Total Estimate** | **21-31 weeks** | |

---

## Component-Level Design Notes

### Model Catalog Entry Design (P0-1)

The `ModelCatalogEntry` should be a data class (record) with the following
design constraints:

1. **Canonical key**: `"provider:model"` format, e.g. `"openai:gpt-4o"`.
   This is the primary lookup key in `byKey` index.

2. **Aliases**: Model names change over time (e.g. `"gpt-4"` -> `"gpt-4-0613"`).
   Aliases allow backward-compatible references. The `byAlias` index maps each
   alias to its canonical entry.

3. **Input types**: A `Set<InputType>` (enum: TEXT, IMAGE, AUDIO, VIDEO, DOCUMENT)
   rather than boolean flags. This is forward-compatible with multimodal models
   and enables input-type-aware routing.

4. **Context window**: A single `contextWindow` integer (total tokens) plus
   `maxOutputTokens`. The effective max input is `contextWindow - maxOutputTokens`.

5. **Capabilities map**: An extensible `Map<String, Object>` for provider-specific
   flags (e.g. `"supportsJsonMode": true`, `"supportsParallelToolCalls": true`).

6. **Pricing tier**: An enum or value object for cost-aware routing:
   ```
   enum PricingTier { FREE, BUDGET, STANDARD, PREMIUM }
   ```

7. **Source of truth**: Catalog entries can come from three sources:
   - Static built-in catalog (bundled with framework)
   - YAML configuration overrides
   - Runtime API discovery (probe provider's /models endpoint)

### Thinking Level Mapping (P0-3)

Each `ThinkLevel` maps to provider-specific parameters:

| ThinkLevel | OpenAI mapping | DeepSeek mapping | Anthropic mapping |
|------------|---------------|------------------|-------------------|
| OFF | `thinking: null` | `thinking: null` | `thinking: {type: "disabled"}` |
| MINIMAL | `thinking: {type: "enabled", budget_tokens: 512}` | thinking type enabled, budget 256 | `thinking: {type: "enabled", budget_tokens: 512}` |
| LOW | budget_tokens: 1024 | budget_tokens: 512 | budget_tokens: 1024 |
| MEDIUM | budget_tokens: 2048 | budget_tokens: 1024 | budget_tokens: 2048 |
| HIGH | budget_tokens: 4096 | budget_tokens: 2048 | budget_tokens: 4096 |
| XHIGH | budget_tokens: 8192 | budget_tokens: 4096 | budget_tokens: 8192 |
| ADAPTIVE | budget_tokens: auto (based on input length) | same | same |
| MAX | budget_tokens: max (provider limit) | max | max |

The `Reasoning` enum controls output behavior:
- `OFF`: thinking tokens are discarded, only final answer returned
- `ON`: thinking tokens included in response as `reasoning_content`
- `STREAM`: thinking tokens streamed as separate SSE events

### Container Sandbox Lifecycle (P0-4)

Per-tool-execution container lifecycle:

```
1. PREPARE (before tool execution)
   - Check if Docker/podman daemon is reachable
   - Pull image if not cached (with timeout, retry)
   - Create container config:
       image: "lyclaw-sandbox:latest" (pre-built with common tools)
       command: ["java", "-jar", "/sandbox/sandbox-runner.jar", "<serialized-tool-call>"]
       mounts:
         - /host/input:/sandbox/input:ro  (read-only host files)
         - /host/output:/sandbox/output:rw (write results back)
         - /host/work:/sandbox/work:rw     (scratch space, cleaned after)
       network: none|bridge|host (configurable)
       memory: "256m" (configurable per-tool)
       cpuShares: 512 (configurable per-tool)
       timeout: 30s (configurable)
   - Start container

2. EXECUTE
   - Stream tool output via container logs API
   - Monitor for timeout
   - On completion: read /sandbox/output/result.json

3. CLEANUP (always, in finally block)
   - Stop container (force kill if timeout exceeded)
   - Remove container
   - Clean /host/work directory
   - Update health metrics

4. ERROR HANDLING
   - Container start failure -> fallback to PROCESS isolation
   - Container timeout -> kill + return error
   - Daemon unreachable -> fallback to PROCESS isolation
   - Image pull failure -> use cached image or fallback
```

### Auto-Fallback Probing Algorithm (P1-5)

```
State per session:
  activeModel: ChatModel           // currently active model
  primaryModel: ChatModel          // configured primary (may == activeModel)
  fallbackChain: ChatModel[]       // ordered fallback list
  lastProbeTime: Instant|null      // last probe attempt timestamp
  probeBackoff: Duration           // current probe interval (starts at 60s)

On every chat request:
  1. Try activeModel.call(request)
  2. If success:
       a. If activeModel != primaryModel AND now() - lastProbeTime >= probeBackoff:
            // Probe primary asynchronously (do NOT block the user)
            fire-and-forget: try primaryModel.validate()
              if success: switch activeModel back to primary, reset probeBackoff
              if failure: probeBackoff = min(probeBackoff * 2, MAX_BACKOFF=600s)
            lastProbeTime = now()
       b. Return response to user
  3. If failure:
       a. If activeModel == primaryModel:
            // Primary failed, fall back
            try next in fallbackChain
              if success: activeModel = fallbackModel, return response
              if all fail: report error
       b. If activeModel != primaryModel:
            // Already on fallback, try next
            try next in fallbackChain (skip current)
              if success: activeModel = next, return response
              if all fail: report error

This algorithm ensures:
  - Primary model recovery is detected without blocking user requests
  - Probe frequency is bounded (exponential backoff)
  - Fallback chain is exhausted before giving up
  - No probe storms (per-session timestamps)
```

### Run Retry Slot Budgeting (P1-10)

```
class RunRetryManager {
    baseRetries: int = 24
    perProfileRetries: int = 8
    minRetries: int = 32
    maxRetries: int = 160

    // Per-run state
    totalAllocated: int        // total slots for this run
    consumedSlots: int = 0     // slots consumed so far
    iterationCount: int = 0    // current iteration number

    fun allocateSlots(runConfig: RunConfig): int {
        val base = baseRetries
        val profileBonus = profiles.size * perProfileRetries
        val allocated = max(minRetries, min(maxRetries, base + profileBonus))
        totalAllocated = allocated
        return allocated
    }

    fun canRetry(): boolean = consumedSlots < totalAllocated

    fun consumeSlot(): void {
        if (!canRetry()) throw RetryBudgetExhaustedException()
        consumedSlots++
    }

    fun remainingSlots(): int = totalAllocated - consumedSlots
}
```

The retry decision at the agent loop level:

```
for each iteration of ReAct loop:
    try:
        response = model.call(request)
        process(response)
    catch (NonRetryableException e):
        throw  // don't retry permanent errors
    catch (RetryableException e):
        if (retryManager.canRetry()):
            retryManager.consumeSlot()
            log.warn("Iteration {} failed, retrying ({} slots remaining)",
                     iteration, retryManager.remainingSlots())
            continue  // retry this iteration
        else:
            throw RetryBudgetExhaustedException(
                "Run failed after consuming all {} retry slots", totalAllocated)
```

---

## Migration Path

### Phase 1: Model Catalog Foundation (Weeks 1-5)

1. Define `ModelCatalogEntry`, `InputType`, `PricingTier` data classes
2. Build static built-in catalog with ~50 common models (OpenAI, DeepSeek, Anthropic, Gemini, Groq, Ollama)
3. Implement `ModelAliasIndex` with `byAlias` and `byKey` maps
4. Implement `buildConfiguredModelCatalog()` to merge static catalog with YAML config
5. Add backward-compatible fallback: if model not in catalog, treat as opaque string (current behavior)
6. Wire catalog into `ChatModelRegistry` as an optional enrichment layer

### Phase 2: Thinking & Reasoning Controls (Weeks 5-8)

1. Define `ThinkLevel`, `Reasoning`, `Verbose` enums
2. Add fields to `ChatRequest` (deprecate `thinkingEnabled` boolean)
3. Add fields to `ChatClient.ChatRequestBuilder`
4. Implement provider-specific mapping tables (OpenAI, DeepSeek, Anthropic)
5. Add `resolveThinkingDefault()` with catalog-aware defaults

### Phase 3: Model Resolution Pipeline (Weeks 8-12)

1. Implement `resolveBareModelDefaultProvider()` for bare model names
2. Implement `resolveModelRefFromString()` parsing "provider:model" or "model"
3. Implement `inferUniqueProviderFromCatalog()` for single-provider setups
4. Implement `resolveAllowedModelRef()` with allowlist checking
5. Implement `resolveDefaultModelForAgent()` overriding FirstAvailableRouter
6. Add `resolvePersistedModelRef()` for persisted overrides (if persistence exists)

### Phase 4: Container Sandbox (Weeks 12-18)

1. Add Docker SDK dependency (docker-java)
2. Implement `ContainerSandbox` class implementing `ToolSandbox`
3. Create `lyclaw-sandbox` Docker image with sandbox-runner
4. Implement `SandboxFsBridge` for controlled file exchange
5. Add configuration: `lyclaw.sandbox.container.*` properties
6. Implement graceful fallback: container unavailable -> PROCESS -> SANDBOX -> DIRECT
7. Security audit of the sandbox implementation
8. Add integration tests with Testcontainers

### Phase 5: Auto-Fallback & Run Retries (Weeks 18-23)

1. Implement `AutoFallbackProbeManager` with per-session state
2. Integrate probing into the chat flow (non-blocking fire-and-forget)
3. Implement `RunRetryManager` with slot budgeting
4. Integrate run-level retries into `DefaultReActEngine`
5. Add metrics: probe success rate, retry slot utilization, fallback frequency

### Phase 6: Multi-Model & Provider Discovery (Weeks 23-31)

1. Design `ImageGenerationModel`, `PdfProcessingModel` interfaces
2. Implement adapters for DALL-E, Stable Diffusion (image gen)
3. Implement PDF ingestion pipeline (extract text, chunk, embed)
4. Implement provider API discovery (query /models endpoint, populate catalog)
5. Add `MessageAttachment` to `Message` for image/audio/document input

---

## Key Design Decisions to Resolve

1. **Catalog vs. dynamic**: Should the model catalog be purely static (file-based)
   or enriched at runtime from provider APIs? Recommendation: static base + runtime
   enrichment, with static as fallback.

2. **Container runtime**: Docker vs. podman vs. both? Docker has wider adoption but
   requires daemon. Podman is daemonless and more secure. Recommendation: support
   both via a `ContainerRuntime` abstraction, auto-detect available runtime.

3. **Thinking controls granularity**: Should `ThinkLevel` be per-request, per-agent,
   or both? Recommendation: per-agent default with per-request override capability.

4. **Run retries vs. request retries**: How do run-level retries interact with
   `RetryChatModel` request-level retries? Recommendation: request-level retries
   are transparent to run-level. A request retry does not consume a run retry slot.
   Only agent-logic failures (tool errors, parsing failures, loop failures) consume
   run retry slots.

5. **Sandbox fallback strategy**: When container is unavailable, should the system
   fail closed (reject execution) or fail open (use weaker isolation)? Recommendation:
   configurable per-tool. Sensitive tools fail closed; safe tools can degrade.

---

## Test Strategy

### Unit Tests
- `ModelCatalogTest`: alias resolution, canonicalization, input-type queries
- `ThinkLevelMappingTest`: verify each ThinkLevel maps to correct provider params
- `ModelResolutionPipelineTest`: each resolution function with edge cases
- `AutoFallbackProbeManagerTest`: state machine transitions, backoff timing
- `RunRetryManagerTest`: slot allocation, exhaustion, budgeting

### Integration Tests
- `ContainerSandboxTest`: actual Docker container creation, execution, cleanup
- `SandboxFsBridgeTest`: file read/write through bridge, permission enforcement
- `OpenAiProtocolChatModelThinkingTest`: verify thinking params sent correctly
- `FallbackChainIntegrationTest`: primary failure -> fallback -> recovery probe

### Security Tests
- `SandboxEscapeTest`: attempt chroot escape, network escape, resource exhaustion
- `ModelAllowlistTest`: verify restricted models cannot be called
- `ElevatedModeTest`: verify confirmation flow for elevated operations

---

*Part 3 of the LyClaw renovation gap analysis series. Part 1 covers Agent
Architecture; Part 2 covers Tool System & Memory; Part 3 covers Model Management
& Sandbox.*

---

# LyClaw vs OpenClaw Gap Analysis: Bootstrap, Routing, Identity, Group Chat, Heartbeat, Human Delay, TTS, Block Streaming

## Executive Summary

This document provides a comprehensive, feature-by-feature gap analysis between LyClaw (current state) and OpenClaw (target reference) across eight core system areas: Workspace Bootstrap, Agent Routing & Bindings, Identity, Group Chat, Heartbeat, Human Delay, TTS, and Block Streaming. Each feature is examined in terms of its current implementation state in LyClaw, the corresponding OpenClaw capability, the gap severity (P0/P1/P2/P3), implementation complexity, and a recommended migration approach.

---

## Severity Legend

| Level | Meaning | Action Required |
|-------|---------|-----------------|
| **P0** | Critical — blocks production deployment or multi-agent operation | Must implement before go-live |
| **P1** | High — severely limits capability parity, user experience | Should implement in next 1-2 milestones |
| **P2** | Medium — nice-to-have, enables advanced use cases | Plan for long-term roadmap |
| **P3** | Low — cosmetic, niche, or experimental | Consider if resources permit |

## Complexity Legend

| Level | Meaning | Typical Effort |
|-------|---------|---------------|
| **HIGH** | Requires architectural changes, new subsystems, multi-module coordination | 3-6 weeks |
| **MEDIUM** | New module or significant extension of existing module | 1-3 weeks |
| **LOW** | Configuration-driven, annotation additions, isolated utility | 2-5 days |
| **TRIVIAL** | Single-file addition, property wiring, simple passthrough | < 1 day |

---

## 1. Workspace Bootstrap

### 1.1 Overview

Workspace Bootstrap is the mechanism by which an agent's personality, behavioral guidelines, runtime context, and identity are loaded from structured files in the agent's workspace directory. In OpenClaw, this forms the foundation of agent configuration — it is how agents "know who they are."

### 1.2 Current LyClaw State

| Aspect | State | Details |
|--------|-------|---------|
| **Mechanism** | `@SystemMessage` annotation only | A single annotation `@SystemMessage("text")` on agent interface methods. The `AgentInvocationHandler.resolveSystemMessage()` method reads this annotation and substitutes `{{varname}}` template placeholders using `@V` annotated parameters. |
| **File Loading** | None | No filesystem-based loading. No AGENTS.md, SOUL.md, BOOTSTRAP.md, or any workspace file concept. |
| **Context Injection** | None | No concept of "always", "continuation-skip", or "never" injection policies. System prompt is static per method. |
| **Max Chars** | None | No size limits, no truncation warnings. Whatever the annotation holds is sent raw. |
| **Post-Compaction Injection** | None | No post-compaction section concept. No awareness of H2/H3 section extraction from workspace files. |
| **Optional Files** | None | No concept of optional bootstrap files (SOUL.md, USER.md, HEARTBEAT.md, IDENTITY.md) or skip control. |
| **Template Variables** | `{{varname}}` only | Only parameter-level `@V` substitution. No context-aware template variables (e.g., `{{agentName}}`, `{{currentDate}}`, `{{userName}}`). |

Key code: `AgentInvocationHandler.java` lines 353-382 (system message resolution), `SystemMessage.java` annotation definition.

### 1.3 OpenClaw Feature Detail

OpenClaw implements a multi-layered bootstrap system:

```
Workspace Root/
  AGENTS.md        # Primary: agent behavior, system prompt, tool usage guidelines
  SOUL.md          # Optional: personality, tone, deeper character definition
  BOOTSTRAP.md     # Core bootstrap config, hooks, initialization sequence
  IDENTITY.md      # Optional: avatar, display name, how the agent presents itself
  USER.md          # Optional: user-specific context, preferences per user
  HEARTBEAT.md     # Lightweight context for heartbeat-only sessions
```

**Configuration Keys:**
- `bootstrapMaxChars` (default 20000): Max chars per individual bootstrap file
- `bootstrapTotalMaxChars` (default 150000): Max total chars across all bootstrap files
- `contextInjection`: `"always"` | `"continuation-skip"` | `"never"` — controls when bootstrap context is injected into the prompt
- `bootstrapPromptTruncationWarning`: `"off"` | `"once"` | `"always"` — controls truncation warning behavior when bootstrap exceeds limits
- `skipBootstrap`: boolean — entirely disable bootstrap loading
- `skipOptionalBootstrapFiles`: array of filenames to skip (typically SOUL.md, USER.md, HEARTBEAT.md, IDENTITY.md)
- `postCompactionSections`: List of H2/H3 section names from AGENTS.md to re-inject after context compaction

### 1.4 Feature-by-Feature Gap Analysis

| Feature | LyClaw | OpenClaw | Gap | Severity | Complexity |
|---------|--------|----------|-----|----------|------------|
| **Primary system prompt** | `@SystemMessage` annotation value | AGENTS.md file content | Annotation lacks multi-file, multi-section support; no file reload | P0 | MEDIUM |
| **Workspace file loading** | None | Filesystem walking with glob/regex patterns | Entire subsystem missing | P0 | MEDIUM |
| **Multi-file bootstrap** | Single string per method | AGENTS.md + SOUL.md + BOOTSTRAP.md + IDENTITY.md + USER.md + HEARTBEAT.md | 5 additional file types with distinct semantics | P0 | MEDIUM |
| **Bootstrap max chars control** | None | `bootstrapMaxChars` (20000), `bootstrapTotalMaxChars` (150000) | No size governance; risk of token budget explosion | P1 | LOW |
| **Truncation warning** | None | `bootstrapPromptTruncationWarning`: off/once/always | No user feedback when bootstrap is truncated | P2 | LOW |
| **Context injection policy** | Always (if annotated) | `contextInjection`: always/continuation-skip/never | No continuation-skip; unnecessary re-injection wastes tokens | P1 | LOW |
| **Optional file control** | None | `skipOptionalBootstrapFiles` per-agent | Cannot fine-tune which optional files load per agent | P2 | LOW |
| **Post-compaction sections** | None | `postCompactionSections`: H2/H3 re-injection after compaction | No compaction awareness; agent loses key context after long conversations | P1 | MEDIUM |
| **Template variable engine** | `{{varname}}` parameter-level only | Multi-source: env vars, channel context, user identity, timestamps | Cannot personalize system prompt from runtime context | P1 | LOW |
| **File hot-reload** | N/A (annotation-based, static) | Filesystem watcher, re-read on change | Annotation changes require recompilation + restart; OpenClaw can reload live | P2 | MEDIUM |
| **Skip bootstrap entirely** | N/A (no bootstrap) | `skipBootstrap` boolean | Already effectively skipped; implementing bootstrap makes this relevant | P2 | LOW |

### 1.5 Implementation Roadmap

1. **Phase 1 (P0)**: Create `WorkspaceBootstrap` service that reads AGENTS.md from a configurable directory. Integrate with `AgentInvocationHandler` to inject file content into system prompt. Support simple template variables.
2. **Phase 2 (P1)**: Add BOOTSTRAP.md, IDENTITY.md loading. Implement `contextInjection` policy and max char controls with truncation warnings.
3. **Phase 3 (P2)**: Add SOUL.md, USER.md, HEARTBEAT.md. Implement file watcher for hot-reload. Add post-compaction section re-injection.

---

## 2. Startup Context

### 2.1 Overview

Startup Context provides a lightweight, ephemeral briefing about the current environment (date, recent conversation history, system state) injected at the beginning of new or reset sessions. It differs from Bootstrap in that it is dynamic and time-sensitive rather than static personality definition.

### 2.2 Current LyClaw State

| Aspect | State | Details |
|--------|-------|---------|
| **Mechanism** | None | No startup context injection whatsoever. No daily memory loading, no environment briefing. |
| **Session Init** | Manual | `ChatController.createSession()` merely generates a UUID. No context preparation. |
| **Apply Triggers** | None | No concept of "new" vs "reset" session events that trigger context injection. |

### 2.3 OpenClaw Feature Detail

```yaml
agentStartupContext:
  enabled: true                  # Master toggle
  applyOn: ["new", "reset"]     # Which session events trigger injection
  dailyMemoryDays: 2            # Look back N days for recent interactions
  maxFileBytes: 16384           # Max bytes from any single file
  maxFileChars: 1200            # Max chars from any single source
  maxTotalChars: 2800           # Max total injected chars
```

### 2.4 Gap Analysis

| Feature | LyClaw | OpenClaw | Gap | Severity | Complexity |
|---------|--------|----------|-----|----------|------------|
| **Startup context injection** | None | `AgentStartupContextConfig` | Entire subsystem missing; new sessions lack environmental awareness | P1 | MEDIUM |
| **Enable/disable control** | None | `enabled: true/false` | No ability to toggle | P2 | LOW |
| **Apply triggers** | None | `applyOn: ["new","reset"]` | Session lifecycle awareness needed | P2 | LOW |
| **Daily memory loading** | None | `dailyMemoryDays: 2` | No recent history summary on session start | P1 | MEDIUM |
| **Size controls** | None | maxFileBytes, maxFileChars, maxTotalChars | No budget controls | P2 | LOW |

---

## 3. Agent Routing & Bindings

### 3.1 Overview

Agent Routing is the mechanism that determines which agent (or which agent configuration) handles an incoming message based on which channel it arrived through, who sent it, and pattern-matching rules. In multi-channel, multi-agent deployments, this is the core dispatch layer.

### 3.2 Current LyClaw State

| Aspect | State | Details |
|--------|-------|---------|
| **Routing Model** | None — single agent, single endpoint | `ChatController` injects one `ChatAgent` bean. `POST /api/chat/stream` maps directly to `chatAgent.chatStream()`. No routing logic. |
| **Channel Concept** | None | No channel abstraction. The only "channel" is the HTTP endpoint itself. No concept of Telegram, Discord, WhatsApp, or any messaging platform channel. |
| **Agent Binding** | None | No `AgentBinding`, `AgentRouteBinding`, or `AgentAcpBinding` classes. |
| **Match Rules** | None | No match patterns, no account/guild/team/role/peer filtering. |
| **ACP (Agent Communication Protocol)** | None | No external agent backend connection concept. |
| **Multi-agent dispatch** | Not supported | Single agent interface per JVM. Multiple agents require separate beans and separate endpoints. |
| **Role-based routing** | None | No Discord role-based or any role-based routing. |

Key code: `ChatController.java` (single `ChatAgent` bean, single endpoint), `ChatAgent.java` (single agent interface).

### 3.3 OpenClaw Feature Detail

```
AgentBinding
  ├── AgentRouteBinding
  │     type: "route"
  │     agentId: string           # Which agent handles this match
  │     comment: string           # Human-readable description
  │     match: AgentBindingMatch  # Pattern matching rules
  │     session: session config   # Session management for this route
  │
  └── AgentAcpBinding
        type: "acp"
        agentId: string
        comment: string
        match: AgentBindingMatch
        acp:                     # External agent backend overrides
          url: string
          timeout: duration
          headers: map

AgentBindingMatch
  channel: string (required)     # Channel pattern: "#general", "@botname", "*"
  accountId: string              # Specific messaging account
  peer: { chatType, id }        # Specific peer/chat identifier
  guildId: string               # Discord guild
  teamId: string                # MS Teams team
  roles: string[]               # Discord role-based routing
```

**Key routing patterns:**
- **Channel-based**: Match on channel name/ID patterns
- **Account-based**: Route based on which messaging account received the message
- **Peer-based**: Route to specific agent based on sender identity
- **Role-based**: Discord role permissions determine which agent handles the message
- **ACP routing**: Forward to external agent backend

### 3.4 Feature-by-Feature Gap Analysis

| Feature | LyClaw | OpenClaw | Gap | Severity | Complexity |
|---------|--------|----------|-----|----------|------------|
| **Agent dispatch/routing** | Single agent, no routing | Multi-agent dispatch with pattern matching | Cannot support multiple agents on different channels | P0 | HIGH |
| **Channel abstraction** | None | Channel concept with platform-specific adapters (Telegram, Discord, WhatsApp, etc.) | Cannot integrate with messaging platforms | P0 | HIGH |
| **AgentBinding model** | None | `AgentRouteBinding` + `AgentAcpBinding` classes | Core domain model missing | P0 | MEDIUM |
| **Binding match rules** | None | Channel, accountId, peer, guildId, teamId, roles | No match semantics at all | P0 | MEDIUM |
| **Pattern-based matching** | None | Channel patterns like "#general", "@botname", wildcards | No flexible match syntax | P0 | LOW |
| **ACP (external agent backend)** | None | `AgentAcpBinding` with URL, timeout, headers | Cannot proxy to external agent services | P2 | HIGH |
| **Role-based routing** | None | Discord roles → agent selection | Platform-specific advanced feature | P3 | MEDIUM |
| **Multi-account routing** | None | `accountId` in match rules | Single account deployment only | P1 | MEDIUM |
| **Session per route** | None | Per-binding session configuration | No per-route session isolation | P1 | LOW |
| **Dynamic binding reload** | N/A | Hot-reload bindings without restart | Requires restart to change agent assignments | P2 | MEDIUM |
| **Fallback/default routing** | Implicit (only one agent) | Explicit default route when no match | No explicit fallback semantics | P1 | LOW |

### 3.5 Implementation Roadmap

1. **Phase 1 (P0)**: Create `AgentRouter` interface and `DefaultAgentRouter`. Implement `AgentBinding` domain model with `AgentRouteBinding` and `AgentBindingMatch`. Support channel-based matching with simple string patterns. Refactor `ChatController` to use router instead of direct agent injection.
2. **Phase 2 (P1)**: Add peer-based matching, account-based routing. Implement per-route session configuration. Add explicit default route fallback.
3. **Phase 3 (P2)**: Add `AgentAcpBinding` for external agent backend proxying. Dynamic binding reload.
4. **Phase 4 (P3)**: Role-based routing for Discord and other platforms.

---

## 4. Identity

### 4.1 Overview

Agent Identity defines how an agent presents itself to users: its display name, avatar, name prefix in messages, response formatting, and acknowledgment reactions. Identity is a core UX concept that makes multi-agent systems feel distinct and personalized.

### 4.2 Current LyClaw State

| Aspect | State | Details |
|--------|-------|---------|
| **Identity Config** | None | No `IdentityConfig` class, no identity-related properties. |
| **Display Name** | None | Agent is named via `@Agent(name = "chat")`, but this is internal only, never surfaced to users. |
| **Avatar** | None | No avatar concept. No local file, remote URL, or data URI avatar resolution. |
| **Name Prefix** | None | Messages appear raw without "[BotName]" prefix formatting. |
| **Message Prefix/Response Prefix** | None | No `resolveMessagePrefix` or `resolveResponsePrefix` logic. |
| **Ack Reaction** | None | No emoji reaction acknowledgment (e.g., checkmark when task completes). |
| **Effective Messages Config** | None | No per-agent message formatting overrides. |

Key findings: `@Agent` annotation has `name` and `description` fields but they are used only for registration/identification within the framework. The `AgentConfig` object (`AgentConfig.java`) has name/description/version/model/provider but nothing identity-related.

### 4.3 OpenClaw Feature Detail

```
IdentityConfig
  agentId: string
  displayName: string           # "Support Bot", "Code Helper"
  avatar: AgentAvatarResolution # One of four kinds (see below)
  namePrefix: string            # Prepended to all messages from this agent
  messagePrefix: string         # Prepended before user messages to this agent
  responsePrefix: string        # Prepended before assistant responses
  ackReaction: string           # Emoji reaction sent on message acknowledgment

AgentAvatarResolution (union type)
  { kind: "none" }              # No avatar
  { kind: "local", filePath }   # Local file path to image
  { kind: "remote", url }       # Remote image URL  
  { kind: "data", url }         # Data URI (base64-encoded)

Resolution Functions:
  resolveAgentIdentity          # Full identity resolution
  resolveIdentityNamePrefix     # Name prefix only
  resolveMessagePrefix          # Prefix before user message
  resolveResponsePrefix         # Prefix before assistant response
  resolveAckReaction            # Emoji for ack
  resolveEffectiveMessagesConfig # Merged message formatting config
```

### 4.4 Feature-by-Feature Gap Analysis

| Feature | LyClaw | OpenClaw | Gap | Severity | Complexity |
|---------|--------|----------|-----|----------|------------|
| **Identity configuration** | None (`AgentConfig` has name/model only) | `IdentityConfig` with displayName, avatar, prefixes, reactions | No per-agent visual identity; all agents look identical | P1 | MEDIUM |
| **Display name** | `@Agent(name=...)` — internal only | `displayName` — user-facing, localized | Users see no agent name in chat | P1 | LOW |
| **Avatar (none)** | N/A | `{kind:"none"}` | Trivial to implement as default | P2 | LOW |
| **Avatar (local file)** | None | `{kind:"local", filePath}` | File serving and MIME type detection needed | P2 | LOW |
| **Avatar (remote URL)** | None | `{kind:"remote", url}` | Simple URL passthrough; proxy/cache concern | P2 | LOW |
| **Avatar (data URI)** | None | `{kind:"data", url}` | Base64 decoding; potentially large inline data | P3 | LOW |
| **Name prefix** | None | `resolveIdentityNamePrefix` | Messages lack agent attribution in multi-agent contexts | P1 | LOW |
| **Message prefix** | None | `resolveMessagePrefix` | Cannot customize how user messages are framed for the agent | P2 | LOW |
| **Response prefix** | None | `resolveResponsePrefix` | Cannot customize response formatting per agent | P2 | LOW |
| **Ack reaction** | None | `resolveAckReaction` (emoji) | No visual acknowledgment that message was received | P3 | LOW |
| **Messages config merge** | None | `resolveEffectiveMessagesConfig` | Cannot override message formatting per agent | P3 | LOW |

### 4.5 Implementation Roadmap

1. **Phase 1 (P1)**: Create `IdentityConfig` class with displayName, namePrefix. Integrate with `AgentInvocationHandler` and SSE event emission to include identity metadata in stream headers. Surface displayName in frontend.
2. **Phase 2 (P2)**: Add avatar resolution (local/remote/none). Add message prefix and response prefix resolution. Wire into message construction pipeline.
3. **Phase 3 (P3)**: Add ack reaction support. Add data URI avatar support. Implement `resolveEffectiveMessagesConfig` merging.

---

## 5. Group Chat

### 5.1 Overview

Group Chat management controls how an agent behaves in multi-participant environments: whether it requires explicit mention to respond, what tools are available to group members, who can trigger which actions, and whether there are named access control groups.

### 5.2 Current LyClaw State

| Aspect | State | Details |
|--------|-------|---------|
| **Group Policy** | None | No group chat concept at all. Agent treats all inputs equally, single-user without any mention or access control semantics. |
| **Require Mention** | None | Agent always responds; no mention gating. |
| **Ingest Policy** | None | No control over whether/how agent reads unmentioned messages. |
| **Group Tools** | None | Same tools for everyone; no per-sender or per-group tool restrictions. |
| **Access Groups** | None | No named access groups for cross-account allowlist management. |
| **Activation Mode** | None | No "mention" vs "always" activation distinction. |
| **Sender Access Evaluation** | None | No `evaluateSenderGroupAccess` or `resolveToolsBySender` logic. |

### 5.3 OpenClaw Feature Detail

```
GroupPolicy
  requireMention: boolean         # Agent only responds when @mentioned
  ingest: "all" | "mentions_only" # What messages the agent reads
  tools: GroupToolPolicyConfig    # Tool availability in group context
  toolsBySender: map<string, GroupToolPolicyConfig>  # Per-sender tool overrides
  
GroupToolPolicyConfig
  allowedTools: string[]          # Whitelist of tool names
  blockedTools: string[]          # Blacklist of tool names
  allowAllTools: boolean          # Override to allow all

GroupActivationMode
  "mention"                       # Agent activates only on @mention
  "always"                        # Agent reads all messages

AccessGroupConfig
  name: string                    # Named group identifier
  members: string[]               # Member identifiers (phone numbers, chat IDs)
  
Resolution Functions:
  resolveChannelGroupPolicy       # Per-channel group policy resolution
  resolveChannelGroupRequireMention # requireMention resolution
  resolveChannelGroupToolsPolicy  # Tools policy for a given channel group
  evaluateSenderGroupAccess       # Can this sender use this agent?
  resolveToolsBySender            # What tools are available to this sender?
  resolveAccessGroupAllowFromState # Access group allowlist expansion
  expandAllowFromWithAccessGroups  # Cross-account allowlist expansion
```

### 5.4 Feature-by-Feature Gap Analysis

| Feature | LyClaw | OpenClaw | Gap | Severity | Complexity |
|---------|--------|----------|-----|----------|------------|
| **Group chat support** | None | `GroupPolicy` per-channel configuration | No concept of multi-participant environments | P0 | HIGH |
| **Require mention** | None (always responds) | `requireMention: boolean` | Agent spams every message in group; unusable for group deployments | P0 | MEDIUM |
| **Ingest policy** | None | `ingest: "all" \| "mentions_only"` | No control over privacy context; agent sees all messages | P1 | LOW |
| **Group tool restrictions** | None | `GroupToolPolicyConfig` (allowedTools, blockedTools, allowAllTools) | Dangerous tools available to all group members | P0 | MEDIUM |
| **Per-sender tool overrides** | None | `toolsBySender: map` | Cannot give elevated tool access to admins, restricted to regular users | P2 | MEDIUM |
| **Access groups** | None | `AccessGroupConfig` with named groups and member lists | Cannot define reusable allowlists across channels | P2 | MEDIUM |
| **Activation mode** | None | `GroupActivationMode: "mention" \| "always"` | No control over how agent joins conversation | P1 | LOW |
| **Sender access evaluation** | None | `evaluateSenderGroupAccess` | No sender authorization at all | P1 | MEDIUM |
| **Allowlist expansion** | None | `expandAllowFromWithAccessGroups` | Cannot reference access groups in channel config | P2 | LOW |

### 5.5 Implementation Roadmap

1. **Phase 1 (P0)**: Create `GroupPolicy` domain model with `requireMention`, `GroupToolPolicyConfig`. Implement mention detection in incoming message processing. Gate agent responses on `requireMention`. Implement tool allow/block filtering per group policy.
2. **Phase 2 (P1)**: Add `ingest` policy. Add `GroupActivationMode`. Implement sender access evaluation and per-sender tool overrides.
3. **Phase 3 (P2)**: Add `AccessGroupConfig` and named access groups. Implement allowlist expansion and cross-account support.

---

## 6. Heartbeat

### 6.1 Overview

Heartbeat is a scheduled, autonomous agent invocation mechanism. On a configurable interval, the agent wakes up, optionally loads a lightweight context (HEARTBEAT.md), and delivers a proactive message or status update to a designated target channel/user. This enables agents to be proactive rather than purely reactive.

### 6.2 Current LyClaw State

| Aspect | State | Details |
|--------|-------|---------|
| **Scheduled Invocation** | None | No scheduler-based agent invocation. `CronJob` model class exists but no cron/heartbeat execution engine. |
| **Interval Control** | None | No `every` duration configuration. |
| **Active Hours** | None | No time-window restriction (e.g., only heartbeat during business hours). |
| **Delivery Target** | None | No concept of delivering to a specific channel or user. |
| **Lightweight Context** | None | No HEARTBEAT.md loading for lightweight sessions. |
| **Busy Skip** | None | No `skipWhenBusy` mechanism. |
| **Isolated Session** | None | No `isolatedSession` concept (fresh session per heartbeat). |

### 6.3 OpenClaw Feature Detail

```
HeartbeatConfig
  every: duration                     # Default 30m; how often to heartbeat
  activeHours: {                       # Optional time window
    start: string                     # "09:00"
    end: string                       # "17:00"
    timezone: string                  # IANA timezone
  }
  model: string                       # Model override for heartbeat invocations
  sessionKey: string                  # Session key for persistence
  deliveryTarget: "last" | "none" | channelId  # Where to deliver heartbeat messages
  directPolicy: "allow" | "block"     # Whether direct messages trigger heartbeat
  to: string                          # E.164 for WhatsApp, chatId for Telegram
  accountId: string                   # Which account to send through
  prompt: string                      # Prompt override for heartbeat
  includeSystemPromptSection: boolean # Whether to include main system prompt
  ackMaxChars: number                 # Default 30; max chars in acknowledgment
  suppressToolErrorWarnings: boolean  # Suppress tool errors in heartbeat
  timeoutSeconds: number              # Per-heartbeat timeout
  lightContext: boolean               # Use HEARTBEAT.md only, skip other bootstrap
  isolatedSession: boolean            # Fresh session for each heartbeat
  skipWhenBusy: boolean               # Skip if agent is processing other messages
  includeReasoning: boolean           # Include reasoning in heartbeat response
```

### 6.4 Feature-by-Feature Gap Analysis

| Feature | LyClaw | OpenClaw | Gap | Severity | Complexity |
|---------|--------|----------|-----|----------|------------|
| **Scheduled heartbeat** | None | `every: duration` with cron-like scheduling | No proactive agent capability; purely reactive | P1 | MEDIUM |
| **Interval configuration** | None | `every: 30m` (default) | No scheduling infrastructure | P1 | LOW |
| **Active hours window** | None | `{start, end, timezone}` | Cannot restrict heartbeat to business hours | P2 | LOW |
| **Model override** | None | `model: string` | Heartbeat uses same model as normal chat | P3 | LOW |
| **Session key** | None | `sessionKey: string` | No persistent session state across heartbeats | P2 | LOW |
| **Delivery target** | None | `"last" \| "none" \| channelId` | No concept of where heartbeat output goes | P1 | LOW |
| **Direct policy** | None | `"allow" \| "block"` | No control over DMs triggering heartbeat | P3 | LOW |
| **Recipient (to)** | None | E.164 / chatId | No per-heartbeat recipient routing | P1 | LOW |
| **Account routing** | None | `accountId: string` | Cannot route heartbeat through specific messaging account | P2 | LOW |
| **Prompt override** | None | `prompt: string` | Heartbeat uses standard system prompt | P2 | LOW |
| **Include system prompt** | None | `includeSystemPromptSection: boolean` | No section-level control | P3 | LOW |
| **Ack max chars** | None | `ackMaxChars: 30` | No acknowledgment trimming | P3 | LOW |
| **Suppress tool errors** | None | `suppressToolErrorWarnings: boolean` | Tool errors always logged/reported | P3 | LOW |
| **Timeout** | None | `timeoutSeconds: number` | Uses global agent timeout | P2 | LOW |
| **Light context** | None | `lightContext: boolean` (HEARTBEAT.md only) | No lightweight bootstrap mode | P2 | LOW |
| **Isolated session** | None | `isolatedSession: boolean` | Heartbeats share session history | P2 | LOW |
| **Skip when busy** | None | `skipWhenBusy: boolean` | Could trigger overlapping invocations | P2 | LOW |
| **Include reasoning** | None | `includeReasoning: boolean` | No reasoning toggle | P3 | LOW |

### 6.5 Implementation Roadmap

1. **Phase 1 (P1)**: Create `HeartbeatScheduler` service using Spring's `@Scheduled` or `TaskScheduler`. Implement `HeartbeatConfig` domain model with `every`, `to`, `deliveryTarget`. Integrate with `AgentInvocationHandler` for heartbeat-triggered invocations. Wire delivery to channel adapter.
2. **Phase 2 (P2)**: Add `activeHours`, `lightContext`, `isolatedSession`, `skipWhenBusy`. Add HEARTBEAT.md bootstrap file loading.
3. **Phase 3 (P3)**: Add model override, prompt override, ack trimming, suppress tool errors, include reasoning.

---

## 7. Human Delay

### 7.1 Overview

Human Delay simulates natural typing pauses between block replies. In streaming or multi-message responses, this feature inserts configurable delays to mimic human typing speed, making the agent feel more natural and less robotic in conversational interfaces.

### 7.2 Current LyClaw State

| Aspect | State | Details |
|--------|-------|---------|
| **Delay Mechanism** | None | SSE events are emitted as fast as the LLM produces tokens. No artificial delay between blocks. |
| **Per-agent configuration** | None | No `HumanDelayConfig` class. No `resolveHumanDelayConfig` resolution logic. |
| **Delay calculation** | None | No character-count-based or block-count-based delay formula. |

Relevant code: In `DefaultReActEngine`, `splitIntoEvents()` chunks text at sentence boundaries and emits immediately via `Flux.fromIterable()`. No delay operators (`delayElements`, `delaySequence`) are used.

### 7.3 OpenClaw Feature Detail

```
HumanDelayConfig
  enabled: boolean           # Master toggle
  minDelayMs: number         # Minimum delay between blocks
  maxDelayMs: number         # Maximum delay between blocks
  charsPerSecond: number     # Typing speed; delay = chars / charsPerSecond
  delayMode: "fixed" | "random" | "typing_speed"

Resolution:
  resolveHumanDelayConfig    # Per-agent, deep-merged from defaults
```

### 7.4 Feature-by-Feature Gap Analysis

| Feature | LyClaw | OpenClaw | Gap | Severity | Complexity |
|---------|--------|----------|-----|----------|------------|
| **Human delay mechanism** | None | `HumanDelayConfig` with min/max/charsPerSecond | Agent responses feel instant/robotic | P2 | LOW |
| **Enable/disable toggle** | None | `enabled: boolean` | Cannot selectively enable | P2 | LOW |
| **Min/max delay bounds** | None | `minDelayMs`, `maxDelayMs` | No delay range control | P2 | LOW |
| **Typing speed simulation** | None | `charsPerSecond` formula | Cannot mimic human typing rate | P2 | LOW |
| **Delay modes** | None | fixed / random / typing_speed | Only one behavior possible; no flexibility | P3 | LOW |
| **Per-agent resolution** | None | `resolveHumanDelayConfig` | All agents behave identically | P2 | LOW |

### 7.5 Implementation Roadmap

1. **Phase 1 (P2)**: Create `HumanDelayConfig` domain model. Implement delay operator insertion in SSE stream pipeline (using `Flux.delayElements` or custom `concatMap` with `Mono.delay`). Wire via per-agent configuration.
2. **Phase 2 (P3)**: Add typing_speed mode with char-count-based delay calculation. Add random delay mode.

---

## 8. TTS (Text-to-Speech)

### 8.1 Overview

TTS configuration controls how agent text responses are converted to speech, including per-agent voice selection, deep-merged message TTS configuration, and channel-specific voice delivery capabilities.

### 8.2 Current LyClaw State

| Aspect | State | Details |
|--------|-------|---------|
| **TTS Config** | None | No `TtsConfig` class. No voice selection. No TTS integration of any kind. |
| **Message TTS merge** | None | No deep-merge of per-message TTS overrides. |
| **Channel TTS capabilities** | None | No channel voice delivery capability declaration. |

### 8.3 OpenClaw Feature Detail

```
TtsConfig
  enabled: boolean
  voice: string               # Voice identifier (provider-specific)
  speed: number               # Speech rate multiplier
  pitch: number               # Voice pitch adjustment
  provider: string            # TTS provider (elevenlabs, azure, etc.)

Deep merge: Agent TtsConfig is deep-merged over the global messages.tts configuration.
Channel capabilities: Each channel declares supported TTS voices and providers.
```

### 8.4 Feature-by-Feature Gap Analysis

| Feature | LyClaw | OpenClaw | Gap | Severity | Complexity |
|---------|--------|----------|-----|----------|------------|
| **TTS configuration** | None | `TtsConfig` per-agent | No voice experience for voice channels | P3 | MEDIUM |
| **Voice selection** | None | `voice: string` | No per-agent voice identity | P3 | LOW |
| **Speed/pitch control** | None | `speed`, `pitch` | No speech parameter fine-tuning | P3 | LOW |
| **Provider selection** | None | `provider: string` | Cannot choose TTS provider per agent | P3 | LOW |
| **Deep merge with messages.tts** | None | Deep-merge semantics | Cannot override TTS per message | P3 | MEDIUM |
| **Channel TTS capabilities** | None | Per-channel voice delivery declaration | Cannot declare which channels support TTS | P3 | LOW |

### 8.5 Implementation Roadmap

TTS is entirely P3. Implementation depends on integrating with an external TTS provider (ElevenLabs, Azure Cognitive Services, etc.). This should be considered only after all P0-P2 features are complete.

1. **Phase 1 (P3)**: Create `TtsConfig` domain model. Integrate with one TTS provider. Implement basic voice selection.
2. **Phase 2 (P3)**: Add speed/pitch control. Add deep-merge semantics. Add channel TTS capability declaration.

---

## 9. Block Streaming

### 9.1 Overview

Block Streaming controls how the agent's response text is delivered to the client. This includes whether to stream character-by-character or in blocks, how blocks are delimited, coalescing of rapid replies, repeat suppression, and delivery modes (live streaming vs. final-only).

### 9.2 Current LyClaw State

| Aspect | State | Details |
|--------|-------|---------|
| **Streaming** | Basic SSE | `DefaultReActEngine` produces `Flux<ServerSentEvent<String>>` using Spring WebFlux. LLM tokens are streamed through OpenAI-compatible SSE endpoints. |
| **Block chunking** | Sentence-boundary only | `splitIntoEvents()` splits text at `\n`, `。`, `！`, `？`, `；` boundaries. Used only for first-round non-streaming tool execution results, not for second-round true streaming. |
| **Coalescing** | None | No `blockStreamingCoalesceConfig` — rapid successive replies are not merged. |
| **Delivery mode** | Always live | No `"final_only"` mode. Everything is streamed as soon as it's available. |
| **Repeat suppression** | None | No deduplication of repeated content chunks. |
| **Hidden boundary separator** | None | No concept of invisible separators between blocks. |
| **Streaming break** | None | No `"text_end"` vs `"message_end"` break semantics. |
| **Max chunk chars** | None | No configurable max size per chunk. |

Key code: `DefaultReActEngine.java` lines 511-529 (`splitIntoEvents` method). `OpenAiProtocolChatModel.java` lines 264-281 (SSE streaming via WebClient). `RespondStage.java` lines 161-186 (`simpleChatStream` with true token-by-token streaming).

### 9.3 OpenClaw Feature Detail

```
blockStreamingDefault: "off" | "on"     # Global default for block streaming

blockStreamingBreak: "text_end" | "message_end"  # What triggers a block break

BlockStreamingChunkConfig                # Soft block chunking
  enabled: boolean
  minChars: number                       # Minimum chars before chunking
  maxChars: number                       # Maximum chars per chunk
  breakOn: string[]                      # Characters/patterns to break on

BlockStreamingCoalesceConfig             # Block reply coalescing
  enabled: boolean
  coalesceIdleMs: number                 # Idle time before flushing coalesced blocks
  maxChunkChars: number                  # Max chars in coalesced output
  repeatSuppression: boolean             # Suppress repeated blocks

deliveryMode: "live" | "final_only"      # Live streaming or batch delivery at end
hiddenBoundarySeparator: string          # Invisible delimiter between blocks (not rendered)
```

### 9.4 Feature-by-Feature Gap Analysis

| Feature | LyClaw | OpenClaw | Gap | Severity | Complexity |
|---------|--------|----------|-----|----------|------------|
| **Block streaming toggle** | Always on (with sentence split for non-streaming results) | `blockStreamingDefault: "off" \| "on"` | No explicit toggle; mixed behavior (true streaming for round 2+, sentence-split for round 1) | P1 | LOW |
| **Block break semantics** | None | `"text_end" \| "message_end"` | No concept of block boundaries beyond sentence chars | P2 | LOW |
| **Soft block chunking** | `splitIntoEvents` at fixed chars | `BlockStreamingChunkConfig` with min/max chars, configurable break set | Hardcoded break chars; no min/max control | P1 | LOW |
| **Reply coalescing** | None | `BlockStreamingCoalesceConfig` with idle timeout | Rapid sequential replies arrive as separate blocks | P2 | MEDIUM |
| **Repeat suppression** | None | `repeatSuppression: boolean` | LLM loop or redundant output is not filtered | P2 | LOW |
| **Delivery modes** | Always live | `"live" \| "final_only"` | Cannot batch deliver; always streams | P2 | LOW |
| **Hidden boundary separator** | None | `hiddenBoundarySeparator: string` | Cannot inject invisible delimiters for client-side parsing | P3 | LOW |
| **Max chunk chars** | None (no limit per chunk) | `maxChunkChars: number` | Unbounded chunk size could cause client-side rendering issues | P2 | LOW |
| **Per-channel/agent config** | Global behavior only | Deep-merged per agent/channel overrides | All agents stream identically | P2 | LOW |

### 9.5 Implementation Roadmap

1. **Phase 1 (P1)**: Create `BlockStreamingConfig` domain model. Refactor `splitIntoEvents` to use configurable break patterns and min/max chunk sizes. Add `blockStreamingDefault` toggle to enable/disable sentence-level chunking. Unify first-round and second-round streaming behavior.
2. **Phase 2 (P2)**: Add `BlockStreamingCoalesceConfig` with idle timeout coalescing. Add repeat suppression. Add `deliveryMode` support (`live` vs `final_only`). Add `blockStreamingBreak` semantics.
3. **Phase 3 (P3)**: Add `hiddenBoundarySeparator`. Add per-channel/per-agent deep-merge configuration.

---

## 10. Typing Indicators

### 10.1 Overview

Typing Indicators provide real-time feedback to users about what the agent is doing: thinking, executing tools, coding, compacting context, etc. This is distinct from the actual response content — it is a "status" channel that runs parallel to the message stream.

### 10.2 Current LyClaw State

| Aspect | State | Details |
|--------|-------|---------|
| **Typing indicators** | None | No typing indicator configuration. No `typingIntervalSeconds`, no `typingMode`. |
| **Progress drafts** | None | No channel progress draft events (tool/item/plan/approval/command-output/patch). |
| **Status reactions** | None | No emoji-based status reactions (queued/thinking/tool/coding/compacting/done/error). |

The framework does emit some SSE events — `"status"`, `"tool_call"`, `"tool_approval"` — but these are content events, not user-facing typing/status indicators in the chat UI sense.

### 10.3 OpenClaw Feature Detail

```
TypingConfig
  typingIntervalSeconds: number    # How often to refresh typing indicator
  typingMode:                      # When to show typing indicator
    "never"                        # Never show
    "instant"                      # Show immediately on message receipt
    "thinking"                     # Show when agent is reasoning
    "message"                      # Show while composing response

Channel Progress Draft Events:
  - tool events: Tool execution start/progress/complete
  - item events: Item-level progress
  - plan events: Plan creation and step progress
  - approval events: Approval request sent
  - command-output events: Shell command output streaming
  - patch events: Code diff streaming

Status Reactions (emoji):
  - queued: Message received, waiting to process
  - thinking: LLM reasoning in progress
  - tool: Tool execution in progress
  - coding: Code generation/editing in progress
  - compacting: Context compaction in progress
  - done: Processing complete
  - error: Error occurred
```

### 10.4 Feature-by-Feature Gap Analysis

| Feature | LyClaw | OpenClaw | Gap | Severity | Complexity |
|---------|--------|----------|-----|----------|------------|
| **Typing indicators** | None | `typingIntervalSeconds` + `typingMode` | Users have no visual feedback during thinking/tool phases | P2 | MEDIUM |
| **Typing mode control** | None | never/instant/thinking/message | Cannot selectively enable/disable or tune behavior | P2 | LOW |
| **Progress draft: tools** | `"status"` SSE event emitted | Structured tool progress events | Existing "status" event is generic; not channel-aware | P2 | LOW |
| **Progress draft: plan** | None | Plan creation and step progress events | No plan visualization in UI | P2 | MEDIUM |
| **Progress draft: approval** | `"tool_approval"` SSE event | Structured approval progress events | Partial implementation exists for tool approval only | P2 | LOW |
| **Progress draft: command output** | None | Shell command output streaming events | No command execution progress visualization | P2 | MEDIUM |
| **Progress draft: patch** | None | Code diff/patch streaming events | No code change preview in UI | P3 | MEDIUM |
| **Status reactions (emoji)** | None | queued/thinking/tool/coding/compacting/done/error | No visual status timeline in chat | P3 | LOW |
| **Reaction timing** | None | Emoji added/removed based on agent state | No state-reaction mapping | P3 | LOW |

### 10.5 Implementation Roadmap

1. **Phase 1 (P2)**: Create `TypingConfig` domain model. Implement typing indicator emission in `DefaultReActEngine` during the buffer/thinking phase. Add `typingIntervalSeconds` control. Connect to SSE stream as "typing" events.
2. **Phase 2 (P2)**: Formalize progress draft events for tool, plan, and approval states. Replace ad-hoc "status" events with structured progress events.
3. **Phase 3 (P3)**: Add command-output and patch progress drafts. Add emoji status reactions with automatic state-reaction mapping.

---

## 11. Time Format & Timezone

### 11.1 Overview

Time configuration controls how timestamps are displayed to users and how timezone-aware operations are handled. This includes user timezone preference, time format (12h vs 24h), envelope timestamps, and elapsed time display.

### 11.2 Current LyClaw State

| Aspect | State | Details |
|--------|-------|---------|
| **Time Format** | None | No user-facing time formatting logic. All timestamps are system-default. |
| **Timezone** | None | No `userTimezone` configuration. Server timezone used for all operations. |
| **Envelope Timestamps** | None | No `envelopeTimestamp` or `envelopeElapsed` concepts on messages. |

### 11.3 OpenClaw Feature Detail

```
TimeConfig
  userTimezone: string         # Optional IANA timezone ("America/New_York", "Asia/Shanghai")
  timeFormat: "auto" | "12" | "24"  # 12-hour or 24-hour time display
  envelopeTimezone: "utc" | "local" | "user" | IANA  # Timezone for message timestamps
  envelopeTimestamp: "on" | "off"   # Whether to show timestamp on message envelope
  envelopeElapsed: "on" | "off"     # Whether to show elapsed time
```

### 11.4 Feature-by-Feature Gap Analysis

| Feature | LyClaw | OpenClaw | Gap | Severity | Complexity |
|---------|--------|----------|-----|----------|------------|
| **User timezone** | None | `userTimezone: IANA` | All users see server time; confusing for global deployments | P2 | LOW |
| **Time format** | None | `timeFormat: "auto" \| "12" \| "24"` | No locale-appropriate time display | P3 | LOW |
| **Envelope timezone** | None | `envelopeTimezone: "utc" \| "local" \| "user" \| IANA` | Timestamps ambiguous across timezones | P2 | LOW |
| **Envelope timestamp toggle** | None | `envelopeTimestamp: "on" \| "off"` | Cannot show/hide timestamps on messages | P3 | LOW |
| **Envelope elapsed toggle** | None | `envelopeElapsed: "on" \| "off"` | Cannot show relative time ("2m ago") | P3 | LOW |

### 11.5 Implementation Roadmap

1. **Phase 1 (P2)**: Add `TimeConfig` with `userTimezone` and `envelopeTimezone`. Wire timezone-aware formatting into message envelope rendering. Use `java.time.ZonedDateTime` for all internal timestamps.
2. **Phase 2 (P3)**: Add `timeFormat` selection. Add `envelopeTimestamp` and `envelopeElapsed` toggles.

---

## 12. Complete Feature Severity Matrix

### 12.1 By Priority

#### P0 — Critical (Blocks Production)

| # | Feature | Component | Complexity | Dependencies |
|---|---------|-----------|------------|-------------|
| 1 | AGENTS.md file loading | Bootstrap | MEDIUM | Filesystem access |
| 2 | Multi-file bootstrap (3+ files) | Bootstrap | MEDIUM | #1 |
| 3 | Agent dispatch/routing | Routing | HIGH | Channel abstraction |
| 4 | Channel abstraction | Routing | HIGH | None (foundational) |
| 5 | AgentBinding domain model | Routing | MEDIUM | #4 |
| 6 | Binding match rules | Routing | MEDIUM | #5 |
| 7 | Pattern-based matching | Routing | LOW | #6 |
| 8 | Group chat: requireMention | Group Chat | MEDIUM | #4 (channel) |
| 9 | Group chat: tool restrictions | Group Chat | MEDIUM | #8 |

**P0 Total: 9 features, estimated effort: 12-18 weeks (with parallel work)**

#### P1 — High Priority (Next Milestones)

| # | Feature | Component | Complexity | Dependencies |
|---|---------|-----------|------------|-------------|
| 10 | bootstrapMaxChars control | Bootstrap | LOW | #1 |
| 11 | Context injection policy | Bootstrap | LOW | #1 |
| 12 | Post-compaction sections | Bootstrap | MEDIUM | #1 + compaction system |
| 13 | Template variable engine | Bootstrap | LOW | #1 |
| 14 | Startup context injection | Startup Context | MEDIUM | Memory system |
| 15 | Daily memory loading | Startup Context | MEDIUM | #14 |
| 16 | Identity: displayName, namePrefix | Identity | MEDIUM | AgentConfig |
| 17 | Multi-account routing | Routing | MEDIUM | #4 |
| 18 | Session per route | Routing | LOW | #5 |
| 19 | Fallback/default routing | Routing | LOW | #6 |
| 20 | Group chat: ingest policy | Group Chat | LOW | #8 |
| 21 | Group chat: activation mode | Group Chat | LOW | #8 |
| 22 | Group chat: sender access eval | Group Chat | MEDIUM | #8 |
| 23 | Heartbeat: scheduled invocation | Heartbeat | MEDIUM | Scheduling infra |
| 24 | Heartbeat: delivery target | Heartbeat | LOW | #23 |
| 25 | Heartbeat: recipient routing | Heartbeat | LOW | #23 |
| 26 | Block streaming: toggle | Block Streaming | LOW | SSE pipeline |
| 27 | Block streaming: chunk config | Block Streaming | LOW | #26 |

**P1 Total: 18 features, estimated effort: 10-16 weeks (with parallel work)**

#### P2 — Medium Priority (Long-term Roadmap)

| # | Feature | Component | Complexity |
|---|---------|-----------|------------|
| 28 | Bootstrap truncation warning | Bootstrap | LOW |
| 29 | Optional file skip control | Bootstrap | LOW |
| 30 | Skip bootstrap entirely | Bootstrap | LOW |
| 31 | File hot-reload | Bootstrap | MEDIUM |
| 32 | Startup context: enable/disable | Startup Context | LOW |
| 33 | Startup context: apply triggers | Startup Context | LOW |
| 34 | Startup context: size controls | Startup Context | LOW |
| 35 | ACP agent backend | Routing | HIGH |
| 36 | Dynamic binding reload | Routing | MEDIUM |
| 37 | Identity: avatar (none) | Identity | LOW |
| 38 | Identity: avatar (local file) | Identity | LOW |
| 39 | Identity: avatar (remote URL) | Identity | LOW |
| 40 | Identity: message prefix | Identity | LOW |
| 41 | Identity: response prefix | Identity | LOW |
| 42 | Group chat: per-sender tools | Group Chat | MEDIUM |
| 43 | Group chat: access groups | Group Chat | MEDIUM |
| 44 | Group chat: allowlist expansion | Group Chat | LOW |
| 45 | Heartbeat: active hours | Heartbeat | LOW |
| 46 | Heartbeat: session key | Heartbeat | LOW |
| 47 | Heartbeat: light context | Heartbeat | LOW |
| 48 | Heartbeat: isolated session | Heartbeat | LOW |
| 49 | Heartbeat: skip when busy | Heartbeat | LOW |
| 50 | Human delay: mechanism | Human Delay | LOW |
| 51 | Human delay: per-agent config | Human Delay | LOW |
| 52 | Block streaming: reply coalescing | Block Streaming | MEDIUM |
| 53 | Block streaming: repeat suppression | Block Streaming | LOW |
| 54 | Block streaming: delivery modes | Block Streaming | LOW |
| 55 | Block streaming: max chunk chars | Block Streaming | LOW |
| 56 | Typing indicators: core | Typing Indicators | MEDIUM |
| 57 | Typing indicators: mode control | Typing Indicators | LOW |
| 58 | Typing indicators: progress drafts (tool/plan/approval) | Typing Indicators | LOW |
| 59 | Progress draft: command output | Typing Indicators | MEDIUM |
| 60 | Time: user timezone | Time/TZ | LOW |
| 61 | Time: envelope timezone | Time/TZ | LOW |

**P2 Total: 34 features, estimated effort: 8-14 weeks (many are LOW complexity)**

#### P3 — Low Priority (Nice to Have)

| # | Feature | Component | Complexity |
|---|---------|-----------|------------|
| 62 | Role-based routing (Discord) | Routing | MEDIUM |
| 63 | Identity: avatar (data URI) | Identity | LOW |
| 64 | Identity: ack reaction | Identity | LOW |
| 65 | Identity: messages config merge | Identity | LOW |
| 66 | Heartbeat: model override | Heartbeat | LOW |
| 67 | Heartbeat: direct policy | Heartbeat | LOW |
| 68 | Heartbeat: include system prompt | Heartbeat | LOW |
| 69 | Heartbeat: ack max chars | Heartbeat | LOW |
| 70 | Heartbeat: timeout | Heartbeat | LOW |
| 71 | Heartbeat: include reasoning | Heartbeat | LOW |
| 72 | Human delay: delay modes | Human Delay | LOW |
| 73 | TTS: configuration | TTS | MEDIUM |
| 74 | TTS: voice selection | TTS | LOW |
| 75 | TTS: speed/pitch | TTS | LOW |
| 76 | TTS: provider selection | TTS | LOW |
| 77 | TTS: deep merge with messages | TTS | MEDIUM |
| 78 | TTS: channel capabilities | TTS | LOW |
| 79 | Block streaming: hidden separator | Block Streaming | LOW |
| 80 | Block streaming: per-channel config | Block Streaming | LOW |
| 81 | Typing indicators: progress draft (patch) | Typing Indicators | MEDIUM |
| 82 | Typing indicators: status reactions | Typing Indicators | LOW |
| 83 | Time: time format (12/24) | Time/TZ | LOW |
| 84 | Time: envelope timestamp toggle | Time/TZ | LOW |
| 85 | Time: envelope elapsed toggle | Time/TZ | LOW |

**P3 Total: 24 features, estimated effort: 6-10 weeks (largely cosmetic/experimental)**

---

## 13. Dependency Graph

The following diagram shows the critical implementation dependencies across components:

```
Channel Abstraction (P0) ─────────────────────────────────────────┐
  ├── Agent Routing & Bindings (P0)                                │
  │     ├── Multi-account routing (P1)                             │
  │     ├── Session per route (P1)                                 │
  │     └── ACP backend (P2)                                       │
  ├── Group Chat (P0)                                              │
  │     ├── ingest policy (P1)                                     │
  │     ├── activation mode (P1)                                   │
  │     ├── sender access eval (P1)                                │
  │     ├── per-sender tools (P2)                                  │
  │     └── access groups (P2)                                     │
  └── Identity (P1)                                                │
        ├── avatar (P2)                                            │
        ├── message/response prefix (P2)                           │
        └── ack reaction (P3)                                      │
                                                                   │
Bootstrap System (P0)                                              │
  ├── multi-file loading (P0)                                      │
  ├── maxChars control (P1)                                        │
  ├── context injection policy (P1)                                │
  ├── template variables (P1)                                      │
  ├── post-compaction sections (P1) ─── depends on compaction      │
  ├── optional files (P2)                                          │
  ├── file hot-reload (P2)                                         │
  └── HEARTBEAT.md for heartbeat (P2) ─── depends on heartbeat     │
                                                                   │
Heartbeat System (P1)                                              │
  ├── scheduler (P1)                                               │
  ├── delivery target (P1) ─── depends on channel abstraction      │
  ├── light context (P2) ─── depends on bootstrap                  │
  ├── isolated session (P2)                                        │
  └── skip when busy (P2)                                          │
                                                                   │
Block Streaming (P1)                                               │
  ├── toggle + chunk config (P1)                                   │
  ├── coalescing (P2)                                              │
  ├── delivery modes (P2)                                          │
  └── hidden separator (P3)                                        │
                                                                   │
Human Delay (P2) ─── depends on block streaming pipeline           │
Typing Indicators (P2) ─── depends on SSE pipeline                 │
TTS (P3) ─── depends on channel TTS capabilities                   │
Time/TZ (P2) ─── independent, isolated utility                     │
```

---

## 14. Implementation Sequencing Recommendation

### Milestone 1: Foundation (Weeks 1-4)
- **Bootstrap**: AGENTS.md + BOOTSTRAP.md file loading with `@SystemMessage` integration
- **Channel Abstraction**: Core channel interface and HTTP channel adapter
- Start: **Agent Routing**: AgentBinding domain model, pattern matching

### Milestone 2: Multi-Agent Core (Weeks 5-8)
- Complete: **Agent Routing**: Route dispatch, default routing, pattern matching
- **Identity**: displayName, namePrefix, basic avatar (none/remote)
- **Group Chat**: requireMention, GroupToolPolicyConfig, tool filtering
- **Bootstrap**: maxChars control, context injection policy, template variables

### Milestone 3: Proactive Agent (Weeks 9-12)
- **Heartbeat**: Core scheduler, delivery target, recipient routing
- **Block Streaming**: Configurable chunk config, block streaming toggle
- **Startup Context**: Injection, daily memory loading
- **Group Chat**: ingest policy, activation mode, sender access evaluation

### Milestone 4: Polish & Advanced (Weeks 13-18)
- **Block Streaming**: Coalescing, repeat suppression, delivery modes
- **Typing Indicators**: Core indicators, progress drafts
- **Human Delay**: Delay mechanism with config
- **Bootstrap**: Post-compaction sections, optional files, hot-reload
- **Time/TZ**: User timezone, envelope timezone

### Milestone 5: Experimental (Weeks 19+)
- **TTS**: Full TTS integration
- **ACP**: External agent backend proxying
- **Role-based routing**: Discord role routing
- Remaining P3 features

---

## 15. Critical Design Decisions

### 15.1 Channel Abstraction

The channel abstraction is the most critical architectural decision. It underpins routing, group chat, identity, heartbeat delivery, and typing indicators. It must support:
- **Multi-platform**: HTTP (current), Telegram, Discord, WhatsApp, Slack, custom webhooks
- **Platform-specific metadata**: Discord guilds/roles, Telegram chat types, WhatsApp E.164
- **Unified message model**: All channels normalize to a common `InboundMessage` / `OutboundMessage`
- **Adapter pattern**: Each platform gets an adapter implementing a common `ChannelAdapter` interface

### 15.2 Agent Routing vs Spring Bean Injection

Currently, `ChatController` directly injects `ChatAgent` as a Spring bean. The routing system must coexist with this:
- **Option A**: Replace direct injection with `AgentRouter.resolve(request)` returning the appropriate agent proxy
- **Option B**: Keep direct injection for simple deployments; router is optional middleware
- **Recommendation**: Option B — maintain backward compatibility while adding router as an optional layer

### 15.3 Bootstrap File Location

Bootstrap files need a well-defined location strategy:
- **Per-agent directory**: `workspace/{agentId}/AGENTS.md`
- **Global fallback**: `workspace/default/AGENTS.md`
- **Classpath fallback**: `classpath:/agents/{agentId}/AGENTS.md`
- **Recommendation**: Filesystem-first with classpath fallback; configurable base path

### 15.4 SSE Event Taxonomy

LyClaw currently uses ad-hoc SSE event types (`"message"`, `"status"`, `"tool_call"`, `"tool_approval"`, `"respond_start"`, `"done"`). These need formalization:

| Current Event | Proposed Standard | Component |
|---------------|-------------------|-----------|
| `"message"` | `"text"` (content), `"block"` (complete block) | Block Streaming |
| `"status"` | `"typing"`, `"thinking"`, `"progress"` | Typing Indicators |
| `"tool_call"` | `"tool.start"`, `"tool.progress"`, `"tool.done"` | Typing Indicators |
| `"tool_approval"` | `"approval.request"` | Typing Indicators |
| `"respond_start"` | `"agent.start"`, `"agent.identity"` | Identity |
| `"done"` | `"agent.done"`, `"stream.end"` | Block Streaming |
| *(new)* | `"heartbeat"` | Heartbeat |
| *(new)* | `"error"`, `"compact"` | Typing Indicators |

---

## 16. Metrics and Observability Gaps

As these features are added, observability must be considered:

| Feature | Metrics Needed |
|---------|---------------|
| Bootstrap | Load time, file size, truncation events, parse errors |
| Routing | Route match latency, match count per route, miss rate, dispatch errors |
| Identity | Resolution time, avatar fetch latency |
| Group Chat | Mention detection rate, tool denial count, sender auth failures |
| Heartbeat | Invocation count, success rate, skip rate (busy), latency, timeout rate |
| Human Delay | Actual delay applied, user-perceived response time |
| TTS | Synthesis latency, character count, provider errors |
| Block Streaming | Chunk count, average chunk size, coalesce count, repeat suppression count |
| Typing Indicators | Indicator emission count, state transition latency |

---

## 17. Summary Statistics

| Category | P0 | P1 | P2 | P3 | Total |
|----------|----|----|----|----|-------|
| Bootstrap | 3 | 3 | 3 | 0 | 9 |
| Startup Context | 0 | 2 | 3 | 0 | 5 |
| Agent Routing | 5 | 3 | 2 | 1 | 11 |
| Identity | 0 | 1 | 3 | 3 | 7 |
| Group Chat | 2 | 3 | 3 | 0 | 8 |
| Heartbeat | 0 | 3 | 6 | 7 | 16 |
| Human Delay | 0 | 0 | 2 | 1 | 3 |
| TTS | 0 | 0 | 0 | 6 | 6 |
| Block Streaming | 0 | 2 | 4 | 2 | 8 |
| Typing Indicators | 0 | 0 | 3 | 3 | 6 |
| Time/TZ | 0 | 0 | 2 | 3 | 5 |
| **Total** | **10** | **17** | **31** | **26** | **84** |

### Effort Estimate Summary

| Priority | Feature Count | Est. Effort (weeks) | Est. Effort (parallel) |
|----------|--------------|---------------------|------------------------|
| P0 | 10 | 18-26 | 12-18 |
| P1 | 17 | 14-22 | 10-16 |
| P2 | 31 | 8-14 | 6-10 |
| P3 | 26 | 6-10 | 4-8 |
| **Total** | **84** | **46-72** | **32-52** |

---

*Document generated: 2026-05-20. Covers LyClaw codebase at commit bc2cb96 ("修复沙箱问题和前端批准") vs. OpenClaw reference architecture.*

---

# Phase 1: Agent Core Enhancement — Renovation Plan

> **Target**: Bring LyClaw's agent config, runtime, and hook system to OpenClaw parity.
> **Status**: Draft
> **Dependencies**: None (this is the foundation phase)

---

## Overview

LyClaw is a Java/Spring Boot multi-agent framework. Its agent system currently has:

| Component | Current State | Target |
|---|---|---|
| `@Agent` annotation | 6 basic fields (name, description, version, model, provider, extensions) | ~30 fields with full OpenClaw parity |
| `AgentConfig` | Flat POJO with 4 core + extensions map | 4-tier hierarchy (defaults / annotation / yaml / runtime) |
| `AgentConfigResolver` | Priority-based merge from sources | Deep-merge resolver with ResolvedAgentConfig |
| `AgentContext` | Flat POJO with ~12 fields | Rich context with ~25 fields + snapshot/restore |
| `AgentHook` SPI | 5 methods + getOrder() | 36-method lifecycle SPI |
| `AgentInvocationHandler` | JDK dynamic proxy with 5-hook dispatch | Full hook lifecycle dispatch |
| `AgentProxyFactory` | Simple constructor + create(Class) | Config-aware factory with runtime-type support |
| Pipeline | 6-stage SSE streaming | Same stages, enriched with hook events |
| Runtime mode | EMBEDDED only (ReAct) | EMBEDDED + ACP dual-mode |

---

## 1.1 AgentConfig System Restructuring

### 1.1.1 Problem

The current `@Agent` annotation carries only 6 fields. Per-agent configuration like thinking level, sandbox setting, subagent delegation, context injection behavior, bootstrap limits, etc. are shoved into opaque `Extension[]` key-value pairs. This makes the config typeless, undiscoverable, and error-prone. There is also no concept of "global defaults" that agents inherit from.

### 1.1.2 Design: Expanded `@Agent` Annotation

```java
package lyjew.com.lyclaw.annotation;

import java.lang.annotation.*;
import org.springframework.stereotype.Component;

/**
 * AI Agent declaration annotation — expanded to full OpenClaw parity.
 *
 * <p>Fields are resolved with priority: agent-level > global defaults
 * ({@code lyclaw.agent.defaults.*}) > system built-in defaults.</p>
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Component
@Documented
public @interface Agent {

    // ── Identity ──────────────────────────────────────────────
    /** Unique agent identifier. When empty, derived from the class simple name (lowerCamelCase). */
    String id() default "";

    /** Whether this agent is the default agent (used when no specific agent is requested). */
    boolean defaultAgent() default false;          // was: (missing)

    /** Human-readable display name. When empty, derived from id. */
    String name() default "";

    /** Description shown in UIs and used for agent-selection routing. */
    String description() default "";

    /** Semantic version string (SemVer). */
    String version() default "1.0.0";

    // ── Workspace ─────────────────────────────────────────────
    /** Workspace root directory for this agent. Empty means use global workspace. */
    String workspace() default "";

    /** Agent-specific subdirectory under workspace. Empty means use agent id. */
    String agentDir() default "";

    // ── System prompt override ────────────────────────────────
    /** Override the system prompt that would otherwise be bootstrapped from AGENTS.md etc. */
    String systemPromptOverride() default "";

    // ── Model ──────────────────────────────────────────────────
    /** Model name (e.g. "deepseek-v4-flash"). Empty = use defaults. */
    String model() default "";

    /** Provider key (e.g. "deepseek", "openai"). Empty = use defaults. */
    String provider() default "";

    /** Ordered fallback model keys, tried in sequence when the primary model fails. */
    String[] fallbacks() default {};

    // ── Skills ─────────────────────────────────────────────────
    /** Skill identifiers to attach to this agent (e.g. "web-search", "code-interpreter"). */
    String[] skills() default {};

    // ── Thinking / Verbose / Reasoning ─────────────────────────
    /**
     * Default thinking level.
     * Valid values: off, minimal, low, medium, high, xhigh, adaptive, max.
     * Empty means use global default.
     */
    String thinkingDefault() default "";

    /** Default verbose level. Empty = use global default. */
    String verboseDefault() default "";

    /** Default reasoning level. Empty = use global default. */
    String reasoningDefault() default "";

    /** Fast mode: skip expensive pre-processing when true. */
    boolean fastModeDefault() default false;

    // ── Context limits ─────────────────────────────────────────
    /** Max context window tokens to reserve for this agent. 0 = use global default. */
    int contextTokens() default 0;

    /** Max characters to load from individual bootstrap files (e.g. AGENTS.md). */
    int bootstrapMaxChars() default 20000;

    /** Total max characters across all bootstrap files. */
    int bootstrapTotalMaxChars() default 150000;

    /**
     * When to inject AGENTS.md / CLAUDE.md content into the system prompt.
     * Valid: always, continuation-skip, never.
     */
    String contextInjection() default "always";

    // ── Subagent delegation ────────────────────────────────────
    /**
     * Delegation mode for subagent spawning.
     *   suggest — agent suggests subagent delegation, user confirms
     *   prefer  — agent prefers to delegate, less user friction
     */
    String delegationMode() default "suggest";

    /** Allowlist of agent ids this agent is permitted to spawn. Empty = unrestricted. */
    String[] allowAgents() default {};

    /** Maximum nesting depth for spawned children. */
    int maxSpawnDepth() default 1;

    /** Maximum number of children this agent can spawn at one level. */
    int maxChildrenPerAgent() default 5;

    // ── Sandbox ────────────────────────────────────────────────
    /**
     * Sandbox mode: none, docker, podman.
     * Empty = use global default.
     */
    String sandbox() default "";

    // ── Extensions (backward-compatible escape hatch) ──────────
    /**
     * Arbitrary key-value pairs for framework plugins.
     * Prefer typed fields above; use extensions only for plugin-specific
     * config that has no typed equivalent.
     */
    Extension[] extensions() default {};
}
```

### 1.1.3 AgentDefaultsConfig (Global Defaults)

This class binds to `lyclaw.agent.defaults.*` in `application.yml` and provides
the fallback layer that every agent inherits when its annotation-level field is empty.

```java
package lyjew.com.lyclaw.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.NestedConfigurationProperty;
import java.util.List;
import java.util.Map;

/**
 * Global agent defaults, bound from {@code lyclaw.agent.defaults.*}.
 *
 * <p>Every field in this class has an agent-level override in @Agent.
 * Resolution order: agent annotation > lyclaw.agent.defaults > hard-coded system defaults.
 */
@ConfigurationProperties(prefix = "lyclaw.agent.defaults")
public class AgentDefaultsConfig {

    // ── Model defaults ─────────────────────────────────────────
    /** Default model name (e.g. "deepseek-v4-flash"). */
    private String model;                    // system default: "deepseek-v4-flash"

    /** Default provider key. */
    private String provider;                 // system default: "deepseek"

    /** Default ordered fallback model keys. */
    private List<String> fallbacks = List.of();

    // ── Thinking / Verbose / Reasoning ─────────────────────────
    /** Default thinking level: off|minimal|low|medium|high|xhigh|adaptive|max. */
    private String thinkingDefault;          // system default: "off"

    /** Default verbose level. */
    private String verboseDefault;           // system default: ""

    /** Default reasoning level. */
    private String reasoningDefault;         // system default: ""

    /** Whether fast mode is on by default. */
    private boolean fastModeDefault;         // system default: false

    // ── Context ────────────────────────────────────────────────
    /** When to inject bootstrap content: always|continuation-skip|never. */
    private String contextInjection = "always";

    /** Max chars per individual bootstrap file. */
    private int bootstrapMaxChars = 20000;

    /** Max chars total across all bootstrap files. */
    private int bootstrapTotalMaxChars = 150000;

    /** Reserved context window tokens. */
    private int contextTokens = 0;

    // ── Skills ─────────────────────────────────────────────────
    /** Default skills attached to all agents. */
    private List<String> skills = List.of();

    // ── Sandbox ────────────────────────────────────────────────
    /** Default sandbox mode: none|docker|podman. */
    private String sandbox = "none";

    // ── Subagents (delegation defaults) ────────────────────────
    @NestedConfigurationProperty
    private SubagentDefaults subagents = new SubagentDefaults();

    // ── Heartbeat ──────────────────────────────────────────────
    @NestedConfigurationProperty
    private HeartbeatDefaults heartbeat = new HeartbeatDefaults();

    // ── Run retries ────────────────────────────────────────────
    @NestedConfigurationProperty
    private RunRetryDefaults runRetries = new RunRetryDefaults();

    // ── Context limits (tool output trimming) ──────────────────
    @NestedConfigurationProperty
    private ContextLimitsDefaults contextLimits = new ContextLimitsDefaults();

    // ── Workspace ──────────────────────────────────────────────
    /** Default workspace directory. */
    private String workspace;

    // ===== Getters / Setters =====

    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }

    public String getProvider() { return provider; }
    public void setProvider(String provider) { this.provider = provider; }

    public List<String> getFallbacks() { return fallbacks; }
    public void setFallbacks(List<String> fallbacks) { this.fallbacks = fallbacks; }

    public String getThinkingDefault() { return thinkingDefault; }
    public void setThinkingDefault(String thinkingDefault) { this.thinkingDefault = thinkingDefault; }

    public String getVerboseDefault() { return verboseDefault; }
    public void setVerboseDefault(String verboseDefault) { this.verboseDefault = verboseDefault; }

    public String getReasoningDefault() { return reasoningDefault; }
    public void setReasoningDefault(String reasoningDefault) { this.reasoningDefault = reasoningDefault; }

    public boolean isFastModeDefault() { return fastModeDefault; }
    public void setFastModeDefault(boolean fastModeDefault) { this.fastModeDefault = fastModeDefault; }

    public String getContextInjection() { return contextInjection; }
    public void setContextInjection(String contextInjection) { this.contextInjection = contextInjection; }

    public int getBootstrapMaxChars() { return bootstrapMaxChars; }
    public void setBootstrapMaxChars(int bootstrapMaxChars) { this.bootstrapMaxChars = bootstrapMaxChars; }

    public int getBootstrapTotalMaxChars() { return bootstrapTotalMaxChars; }
    public void setBootstrapTotalMaxChars(int bootstrapTotalMaxChars) { this.bootstrapTotalMaxChars = bootstrapTotalMaxChars; }

    public int getContextTokens() { return contextTokens; }
    public void setContextTokens(int contextTokens) { this.contextTokens = contextTokens; }

    public List<String> getSkills() { return skills; }
    public void setSkills(List<String> skills) { this.skills = skills; }

    public String getSandbox() { return sandbox; }
    public void setSandbox(String sandbox) { this.sandbox = sandbox; }

    public SubagentDefaults getSubagents() { return subagents; }
    public void setSubagents(SubagentDefaults subagents) { this.subagents = subagents; }

    public HeartbeatDefaults getHeartbeat() { return heartbeat; }
    public void setHeartbeat(HeartbeatDefaults heartbeat) { this.heartbeat = heartbeat; }

    public RunRetryDefaults getRunRetries() { return runRetries; }
    public void setRunRetries(RunRetryDefaults runRetries) { this.runRetries = runRetries; }

    public ContextLimitsDefaults getContextLimits() { return contextLimits; }
    public void setContextLimits(ContextLimitsDefaults contextLimits) { this.contextLimits = contextLimits; }

    public String getWorkspace() { return workspace; }
    public void setWorkspace(String workspace) { this.workspace = workspace; }

    // ===== Nested config classes =====

    /** Subagent delegation defaults. */
    public static class SubagentDefaults {
        /** Default delegation mode: suggest|prefer. */
        private String delegationMode = "suggest";

        /** Allowlist of agent ids. Empty = all allowed. */
        private List<String> allowAgents = List.of();

        /** Default max spawn depth. */
        private int maxSpawnDepth = 1;

        /** Default max children per agent. */
        private int maxChildrenPerAgent = 5;

        // getters/setters omitted for brevity
        public String getDelegationMode() { return delegationMode; }
        public void setDelegationMode(String m) { this.delegationMode = m; }
        public List<String> getAllowAgents() { return allowAgents; }
        public void setAllowAgents(List<String> a) { this.allowAgents = a; }
        public int getMaxSpawnDepth() { return maxSpawnDepth; }
        public void setMaxSpawnDepth(int d) { this.maxSpawnDepth = d; }
        public int getMaxChildrenPerAgent() { return maxChildrenPerAgent; }
        public void setMaxChildrenPerAgent(int c) { this.maxChildrenPerAgent = c; }
    }

    /** Heartbeat configuration. */
    public static class HeartbeatDefaults {
        /** Whether heartbeat is enabled (periodic "you are still alive" prompts). */
        private boolean enabled = false;

        /** Interval in seconds between heartbeat checks. */
        private long intervalSeconds = 60;

        /** Max idle time in seconds before heartbeat fires. */
        private long maxIdleSeconds = 300;

        // getters/setters
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean e) { this.enabled = e; }
        public long getIntervalSeconds() { return intervalSeconds; }
        public void setIntervalSeconds(long s) { this.intervalSeconds = s; }
        public long getMaxIdleSeconds() { return maxIdleSeconds; }
        public void setMaxIdleSeconds(long s) { this.maxIdleSeconds = s; }
    }

    /** Run retry configuration. */
    public static class RunRetryDefaults {
        /** Max retry attempts on model failure. */
        private int maxAttempts = 3;

        /** Base delay between retries in milliseconds. */
        private long baseDelayMs = 1000;

        /** Backoff strategy: fixed|exponential. */
        private String backoff = "exponential";

        // getters/setters
        public int getMaxAttempts() { return maxAttempts; }
        public void setMaxAttempts(int n) { this.maxAttempts = n; }
        public long getBaseDelayMs() { return baseDelayMs; }
        public void setBaseDelayMs(long d) { this.baseDelayMs = d; }
        public String getBackoff() { return backoff; }
        public void setBackoff(String b) { this.backoff = b; }
    }

    /** Context limits (tool output trimming / memory limits). */
    public static class ContextLimitsDefaults {
        /** Max chars to include from memory retrieval. */
        private int memoryGetMaxChars = 50000;

        /** Max chars of a single tool result to include in context. */
        private int toolResultMaxChars = 80000;

        /** Max total chars for all tool results combined. */
        private int toolResultTotalMaxChars = 200000;

        // getters/setters
        public int getMemoryGetMaxChars() { return memoryGetMaxChars; }
        public void setMemoryGetMaxChars(int c) { this.memoryGetMaxChars = c; }
        public int getToolResultMaxChars() { return toolResultMaxChars; }
        public void setToolResultMaxChars(int c) { this.toolResultMaxChars = c; }
        public int getToolResultTotalMaxChars() { return toolResultTotalMaxChars; }
        public void setToolResultTotalMaxChars(int c) { this.toolResultTotalMaxChars = c; }
    }
}
```

### 1.1.4 System Defaults (Hard-coded Fallback)

When neither the annotation nor `lyclaw.agent.defaults` provides a value, the system
uses these built-in constants. They are defined as a static inner class or a constants
file:

```java
package lyjew.com.lyclaw.config;

/**
 * Hard-coded system defaults — the lowest-priority fallback layer.
 * Used when neither agent annotation nor lyclaw.agent.defaults supplies a value.
 */
public final class AgentSystemDefaults {

    private AgentSystemDefaults() {}

    public static final String MODEL            = "deepseek-v4-flash";
    public static final String PROVIDER         = "deepseek";
    public static final String THINKING_DEFAULT = "off";
    public static final String VERBOSE_DEFAULT  = "";
    public static final String REASONING_DEFAULT = "";
    public static final boolean FAST_MODE       = false;
    public static final String CONTEXT_INJECTION = "always";
    public static final int BOOTSTRAP_MAX_CHARS = 20000;
    public static final int BOOTSTRAP_TOTAL_MAX_CHARS = 150000;
    public static final int CONTEXT_TOKENS      = 0;
    public static final String SANDBOX          = "none";
    public static final String DELEGATION_MODE  = "suggest";
    public static final int MAX_SPAWN_DEPTH     = 1;
    public static final int MAX_CHILDREN        = 5;
    public static final int MEMORY_GET_MAX_CHARS = 50000;
    public static final int TOOL_RESULT_MAX_CHARS = 80000;
    public static final int TOOL_RESULT_TOTAL_MAX_CHARS = 200000;
}
```

### 1.1.5 ResolvedAgentConfig (Output of Resolution)

The resolver produces a fully-resolved, deeply-merged, read-only config object.

```java
package lyjew.com.lyclaw.config;

import java.util.*;

/**
 * Fully resolved agent configuration — the output of the 3-layer deep merge.
 *
 * <p>Every field here has been resolved through:
 *   agent annotation > lyclaw.agent.defaults.* > AgentSystemDefaults
 *
 * <p>This class is immutable after construction to prevent accidental mutation
 * during the agent run lifecycle.
 */
public class ResolvedAgentConfig {

    // ── Identity ──
    private final String agentId;
    private final String agentName;
    private final String description;
    private final String version;
    private final boolean defaultAgent;

    // ── Workspace ──
    private final String workspaceDir;
    private final String agentDir;

    // ── System prompt ──
    private final String systemPromptOverride;

    // ── Model ──
    private final String model;
    private final String provider;
    private final List<String> fallbacks;

    // ── Thinking / Verbose / Reasoning ──
    private final String thinkingDefault;
    private final String verboseDefault;
    private final String reasoningDefault;
    private final boolean fastModeDefault;

    // ── Context ──
    private final int contextTokens;
    private final String contextInjection;
    private final int bootstrapMaxChars;
    private final int bootstrapTotalMaxChars;

    // ── Skills ──
    private final List<String> skills;

    // ── Delegation ──
    private final String delegationMode;
    private final List<String> allowAgents;
    private final int maxSpawnDepth;
    private final int maxChildrenPerAgent;

    // ── Sandbox ──
    private final String sandbox;

    // ── Extensions (remaining key-value pairs from @Extension[]) ──
    private final Map<String, String> extensions;

    // ── Runtime config (copied from defaults) ──
    private final AgentDefaultsConfig.HeartbeatDefaults heartbeat;
    private final AgentDefaultsConfig.RunRetryDefaults runRetries;
    private final AgentDefaultsConfig.ContextLimitsDefaults contextLimits;

    // Private constructor — use Builder via AgentConfigResolver
    private ResolvedAgentConfig(Builder builder) {
        this.agentId              = builder.agentId;
        this.agentName            = builder.agentName;
        this.description          = builder.description;
        this.version              = builder.version;
        this.defaultAgent         = builder.defaultAgent;
        this.workspaceDir         = builder.workspaceDir;
        this.agentDir             = builder.agentDir;
        this.systemPromptOverride = builder.systemPromptOverride;
        this.model                = builder.model;
        this.provider             = builder.provider;
        this.fallbacks            = List.copyOf(builder.fallbacks);
        this.thinkingDefault      = builder.thinkingDefault;
        this.verboseDefault       = builder.verboseDefault;
        this.reasoningDefault     = builder.reasoningDefault;
        this.fastModeDefault      = builder.fastModeDefault;
        this.contextTokens        = builder.contextTokens;
        this.contextInjection     = builder.contextInjection;
        this.bootstrapMaxChars    = builder.bootstrapMaxChars;
        this.bootstrapTotalMaxChars = builder.bootstrapTotalMaxChars;
        this.skills               = List.copyOf(builder.skills);
        this.delegationMode       = builder.delegationMode;
        this.allowAgents          = List.copyOf(builder.allowAgents);
        this.maxSpawnDepth        = builder.maxSpawnDepth;
        this.maxChildrenPerAgent  = builder.maxChildrenPerAgent;
        this.sandbox              = builder.sandbox;
        this.extensions           = Collections.unmodifiableMap(new HashMap<>(builder.extensions));
        this.heartbeat            = builder.heartbeat;
        this.runRetries           = builder.runRetries;
        this.contextLimits        = builder.contextLimits;
    }

    // ===== Getters =====

    public String getAgentId() { return agentId; }
    public String getAgentName() { return agentName; }
    public String getDescription() { return description; }
    public String getVersion() { return version; }
    public boolean isDefaultAgent() { return defaultAgent; }
    public String getWorkspaceDir() { return workspaceDir; }
    public String getAgentDir() { return agentDir; }
    public String getSystemPromptOverride() { return systemPromptOverride; }
    public String getModel() { return model; }
    public String getProvider() { return provider; }
    public List<String> getFallbacks() { return fallbacks; }
    public String getThinkingDefault() { return thinkingDefault; }
    public String getVerboseDefault() { return verboseDefault; }
    public String getReasoningDefault() { return reasoningDefault; }
    public boolean isFastModeDefault() { return fastModeDefault; }
    public int getContextTokens() { return contextTokens; }
    public String getContextInjection() { return contextInjection; }
    public int getBootstrapMaxChars() { return bootstrapMaxChars; }
    public int getBootstrapTotalMaxChars() { return bootstrapTotalMaxChars; }
    public List<String> getSkills() { return skills; }
    public String getDelegationMode() { return delegationMode; }
    public List<String> getAllowAgents() { return allowAgents; }
    public int getMaxSpawnDepth() { return maxSpawnDepth; }
    public int getMaxChildrenPerAgent() { return maxChildrenPerAgent; }
    public String getSandbox() { return sandbox; }
    public Map<String, String> getExtensions() { return extensions; }
    public AgentDefaultsConfig.HeartbeatDefaults getHeartbeat() { return heartbeat; }
    public AgentDefaultsConfig.RunRetryDefaults getRunRetries() { return runRetries; }
    public AgentDefaultsConfig.ContextLimitsDefaults getContextLimits() { return contextLimits; }

    // ===== Builder =====

    public static class Builder {
        private String agentId = "";
        private String agentName = "";
        private String description = "";
        private String version = "1.0.0";
        private boolean defaultAgent = false;
        private String workspaceDir = "";
        private String agentDir = "";
        private String systemPromptOverride = "";
        private String model = "";
        private String provider = "";
        private List<String> fallbacks = List.of();
        private String thinkingDefault = "";
        private String verboseDefault = "";
        private String reasoningDefault = "";
        private boolean fastModeDefault = false;
        private int contextTokens = 0;
        private String contextInjection = "always";
        private int bootstrapMaxChars = 20000;
        private int bootstrapTotalMaxChars = 150000;
        private List<String> skills = List.of();
        private String delegationMode = "suggest";
        private List<String> allowAgents = List.of();
        private int maxSpawnDepth = 1;
        private int maxChildrenPerAgent = 5;
        private String sandbox = "none";
        private Map<String, String> extensions = new HashMap<>();
        private AgentDefaultsConfig.HeartbeatDefaults heartbeat = new AgentDefaultsConfig.HeartbeatDefaults();
        private AgentDefaultsConfig.RunRetryDefaults runRetries = new AgentDefaultsConfig.RunRetryDefaults();
        private AgentDefaultsConfig.ContextLimitsDefaults contextLimits = new AgentDefaultsConfig.ContextLimitsDefaults();

        // (setters for each field — omitted for brevity, follow the pattern:)

        public Builder agentId(String v) { this.agentId = v; return this; }
        public Builder agentName(String v) { this.agentName = v; return this; }
        public Builder description(String v) { this.description = v; return this; }
        public Builder version(String v) { this.version = v; return this; }
        public Builder defaultAgent(boolean v) { this.defaultAgent = v; return this; }
        public Builder workspaceDir(String v) { this.workspaceDir = v; return this; }
        public Builder agentDir(String v) { this.agentDir = v; return this; }
        public Builder systemPromptOverride(String v) { this.systemPromptOverride = v; return this; }
        public Builder model(String v) { this.model = v; return this; }
        public Builder provider(String v) { this.provider = v; return this; }
        public Builder fallbacks(List<String> v) { this.fallbacks = v; return this; }
        public Builder thinkingDefault(String v) { this.thinkingDefault = v; return this; }
        public Builder verboseDefault(String v) { this.verboseDefault = v; return this; }
        public Builder reasoningDefault(String v) { this.reasoningDefault = v; return this; }
        public Builder fastModeDefault(boolean v) { this.fastModeDefault = v; return this; }
        public Builder contextTokens(int v) { this.contextTokens = v; return this; }
        public Builder contextInjection(String v) { this.contextInjection = v; return this; }
        public Builder bootstrapMaxChars(int v) { this.bootstrapMaxChars = v; return this; }
        public Builder bootstrapTotalMaxChars(int v) { this.bootstrapTotalMaxChars = v; return this; }
        public Builder skills(List<String> v) { this.skills = v; return this; }
        public Builder delegationMode(String v) { this.delegationMode = v; return this; }
        public Builder allowAgents(List<String> v) { this.allowAgents = v; return this; }
        public Builder maxSpawnDepth(int v) { this.maxSpawnDepth = v; return this; }
        public Builder maxChildrenPerAgent(int v) { this.maxChildrenPerAgent = v; return this; }
        public Builder sandbox(String v) { this.sandbox = v; return this; }
        public Builder extensions(Map<String, String> v) { this.extensions.clear(); this.extensions.putAll(v); return this; }
        public Builder heartbeat(AgentDefaultsConfig.HeartbeatDefaults v) { this.heartbeat = v; return this; }
        public Builder runRetries(AgentDefaultsConfig.RunRetryDefaults v) { this.runRetries = v; return this; }
        public Builder contextLimits(AgentDefaultsConfig.ContextLimitsDefaults v) { this.contextLimits = v; return this; }

        public ResolvedAgentConfig build() {
            return new ResolvedAgentConfig(this);
        }
    }
}
```

### 1.1.6 AgentConfigResolver Enhancement

The resolver is enhanced with a 3-layer deep merge, list-agent support, and
workspace-dir resolution.

```java
package lyjew.com.lyclaw.config;

import lyjew.com.lyclaw.annotation.Agent;
import lyjew.com.lyclaw.annotation.Extension;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Agent config resolver — performs 3-layer deep merge:
 *   Layer 1: AgentSystemDefaults (hard-coded)
 *   Layer 2: AgentDefaultsConfig (lyclaw.agent.defaults.*)
 *   Layer 3: @Agent annotation (agent-level)
 *
 * <p>Each field uses the first non-empty/non-default value from the highest layer.
 * Lists are replaced, not merged (annotation wins completely if non-empty).
 * Maps (extensions) are merged additively (annotation wins on key conflict).
 */
public class AgentConfigResolver {

    private static final Logger log = LoggerFactory.getLogger(AgentConfigResolver.class);

    private final AgentDefaultsConfig defaults;

    /** Cache: agentId → ResolvedAgentConfig. Invalidation on config refresh. */
    private final Map<String, ResolvedAgentConfig> cache = new ConcurrentHashMap<>();

    /** Registered agent entries: agentId → @Agent annotation(from class). */
    private final Map<String, Agent> agentRegistry = new ConcurrentHashMap<>();

    public AgentConfigResolver(AgentDefaultsConfig defaults) {
        this.defaults = defaults;
    }

    /**
     * Register an agent class for later resolution.
     * Called by AgentInterfaceProcessor during BFPP scan.
     */
    public void registerAgent(String agentId, Agent ann) {
        agentRegistry.put(agentId, ann);
    }

    /**
     * Resolve the full merged config for a given agent.
     *
     * Resolution for each field:
     *   1. If @Agent field is set (non-empty string, non-zero int, non-false boolean, non-empty list),
     *      use it.
     *   2. Else if AgentDefaultsConfig has a non-default value, use it.
     *   3. Else use AgentSystemDefaults.
     */
    public ResolvedAgentConfig resolveAgentConfig(String agentId) {
        return cache.computeIfAbsent(agentId, id -> {
            Agent ann = agentRegistry.get(id);
            ResolvedAgentConfig.Builder b = new ResolvedAgentConfig.Builder();

            // ── Identity ──
            b.agentId(id);
            b.agentName(resolveString(
                    ann != null ? ann.name() : "", defaultsField(null, "name"), id));
            b.description(resolveString(
                    ann != null ? ann.description() : "", "", ""));
            b.version(resolveString(
                    ann != null ? ann.version() : "", "1.0.0", "1.0.0"));
            b.defaultAgent(ann != null && ann.defaultAgent());

            // ── Workspace ──
            b.workspaceDir(resolveString(
                    ann != null ? ann.workspace() : "",
                    defaults.getWorkspace(), ""));
            b.agentDir(resolveString(
                    ann != null ? ann.agentDir() : "", "", id));

            // ── System prompt ──
            b.systemPromptOverride(resolveString(
                    ann != null ? ann.systemPromptOverride() : "", "", ""));

            // ── Model ──
            b.model(resolveString(
                    ann != null ? ann.model() : "",
                    defaults.getModel(), AgentSystemDefaults.MODEL));
            b.provider(resolveString(
                    ann != null ? ann.provider() : "",
                    defaults.getProvider(), AgentSystemDefaults.PROVIDER));
            b.fallbacks(resolveList(
                    ann != null ? List.of(ann.fallbacks()) : List.of(),
                    defaults.getFallbacks()));

            // ── Thinking / Verbose / Reasoning ──
            b.thinkingDefault(resolveString(
                    ann != null ? ann.thinkingDefault() : "",
                    defaults.getThinkingDefault(), AgentSystemDefaults.THINKING_DEFAULT));
            b.verboseDefault(resolveString(
                    ann != null ? ann.verboseDefault() : "",
                    defaults.getVerboseDefault(), AgentSystemDefaults.VERBOSE_DEFAULT));
            b.reasoningDefault(resolveString(
                    ann != null ? ann.reasoningDefault() : "",
                    defaults.getReasoningDefault(), AgentSystemDefaults.REASONING_DEFAULT));
            b.fastModeDefault(
                    (ann != null && ann.fastModeDefault())
                            || (!(ann != null && ann.fastModeDefault()) && defaults.isFastModeDefault()));

            // ── Context ──
            b.contextTokens(resolveInt(
                    ann != null ? ann.contextTokens() : 0,
                    defaults.getContextTokens(), AgentSystemDefaults.CONTEXT_TOKENS));
            b.contextInjection(resolveString(
                    ann != null ? ann.contextInjection() : "",
                    defaults.getContextInjection(), AgentSystemDefaults.CONTEXT_INJECTION));
            b.bootstrapMaxChars(resolveInt(
                    ann != null ? ann.bootstrapMaxChars() : 0,
                    defaults.getBootstrapMaxChars(), AgentSystemDefaults.BOOTSTRAP_MAX_CHARS));
            b.bootstrapTotalMaxChars(resolveInt(
                    ann != null ? ann.bootstrapTotalMaxChars() : 0,
                    defaults.getBootstrapTotalMaxChars(), AgentSystemDefaults.BOOTSTRAP_TOTAL_MAX_CHARS));

            // ── Skills ──
            b.skills(resolveList(
                    ann != null ? List.of(ann.skills()) : List.of(),
                    defaults.getSkills()));

            // ── Delegation ──
            b.delegationMode(resolveString(
                    ann != null ? ann.delegationMode() : "",
                    defaults.getSubagents().getDelegationMode(), AgentSystemDefaults.DELEGATION_MODE));
            b.allowAgents(resolveList(
                    ann != null ? List.of(ann.allowAgents()) : List.of(),
                    defaults.getSubagents().getAllowAgents()));
            b.maxSpawnDepth(resolveInt(
                    ann != null ? ann.maxSpawnDepth() : 0,
                    defaults.getSubagents().getMaxSpawnDepth(), AgentSystemDefaults.MAX_SPAWN_DEPTH));
            b.maxChildrenPerAgent(resolveInt(
                    ann != null ? ann.maxChildrenPerAgent() : 0,
                    defaults.getSubagents().getMaxChildrenPerAgent(), AgentSystemDefaults.MAX_CHILDREN));

            // ── Sandbox ──
            b.sandbox(resolveString(
                    ann != null ? ann.sandbox() : "",
                    defaults.getSandbox(), AgentSystemDefaults.SANDBOX));

            // ── Extensions: merge annotation extensions on top of any defaults
            Map<String, String> extMap = new HashMap<>();
            if (ann != null) {
                for (Extension ext : ann.extensions()) {
                    extMap.put(ext.key(), ext.value());
                }
            }
            b.extensions(extMap);

            // ── Runtime config (copied directly from defaults, no annotation override needed) ──
            b.heartbeat(defaults.getHeartbeat());
            b.runRetries(defaults.getRunRetries());
            b.contextLimits(defaults.getContextLimits());

            log.debug("ResolvedAgentConfig for {}: model={} provider={} sandbox={}",
                    id, b.build().getModel(), b.build().getProvider(), b.build().getSandbox());
            return b.build();
        });
    }

    /**
     * List all registered agent IDs.
     */
    public Set<String> listAgentIds() {
        return Collections.unmodifiableSet(agentRegistry.keySet());
    }

    /**
     * List all registered agent entries as (id, name, description) triples.
     */
    public List<AgentEntry> listAgentEntries() {
        return agentRegistry.entrySet().stream()
                .map(e -> new AgentEntry(e.getKey(),
                        e.getValue().name().isEmpty() ? e.getKey() : e.getValue().name(),
                        e.getValue().description()))
                .collect(Collectors.toList());
    }

    /**
     * Resolve the default agent id. Returns the agent with defaultAgent=true,
     * or the first registered agent, or "default".
     */
    public String resolveDefaultAgentId() {
        return agentRegistry.entrySet().stream()
                .filter(e -> e.getValue().defaultAgent())
                .map(Map.Entry::getKey)
                .findFirst()
                .or(() -> agentRegistry.keySet().stream().findFirst())
                .orElse("default");
    }

    /**
     * Resolve the full workspace directory for an agent.
     * Typically: {workspaceRoot}/{agentDir}
     */
    public String resolveAgentWorkspaceDir(ResolvedAgentConfig config) {
        String root = !config.getWorkspaceDir().isEmpty()
                ? config.getWorkspaceDir()
                : defaults.getWorkspace();
        if (root == null || root.isEmpty()) {
            root = System.getProperty("user.dir");
        }
        String dir = !config.getAgentDir().isEmpty() ? config.getAgentDir() : config.getAgentId();
        return root.endsWith("/") ? root + dir : root + "/" + dir;
    }

    /**
     * Invalidate the config cache (called on config refresh events).
     */
    public void invalidate() {
        cache.clear();
    }

    // ===== Private resolution helpers =====

    /** Resolve a nullable Object field: return the first non-null/non-blank value. */
    private String resolveString(String agentVal, String defaultsVal, String systemVal) {
        if (agentVal != null && !agentVal.isEmpty()) return agentVal;
        if (defaultsVal != null && !defaultsVal.isEmpty()) return defaultsVal;
        return systemVal != null ? systemVal : "";
    }

    private int resolveInt(int agentVal, int defaultsVal, int systemVal) {
        if (agentVal != 0) return agentVal;
        if (defaultsVal != 0) return defaultsVal;
        return systemVal;
    }

    /** Resolve a list: use agent-level if non-empty, else defaults. */
    private List<String> resolveList(List<String> agentVal, List<String> defaultsVal) {
        if (agentVal != null && !agentVal.isEmpty()) return agentVal;
        return defaultsVal != null ? defaultsVal : List.of();
    }

    /** Placeholder for field not directly on AgentDefaultsConfig root. */
    private String defaultsField(AgentDefaultsConfig d, String field) {
        if (d == null) return "";
        return switch (field) {
            case "name" -> "";
            default -> "";
        };
    }

    // ===== Data record =====

    public record AgentEntry(String id, String name, String description) {}
}
```

### 1.1.7 YAML Configuration Example

```yaml
# application.yml — agent configuration

lyclaw:
  agent:
    # Global defaults inherited by all agents
    defaults:
      model: "deepseek-v4-flash"
      provider: "deepseek"
      fallbacks:
        - "deepseek-v4-pro"
        - "openai-gpt-4o"
      thinkingDefault: "off"
      verboseDefault: ""
      reasoningDefault: ""
      fastModeDefault: false
      contextInjection: "always"
      bootstrapMaxChars: 20000
      bootstrapTotalMaxChars: 150000
      contextTokens: 0
      skills: []
      sandbox: "none"
      workspace: "/var/lyclaw/workspaces"

      # Subagent delegation defaults
      subagents:
        delegationMode: "suggest"
        allowAgents: []           # empty = allow all
        maxSpawnDepth: 1
        maxChildrenPerAgent: 5

      # Heartbeat: periodic liveness check for long-running agents
      heartbeat:
        enabled: false
        intervalSeconds: 60
        maxIdleSeconds: 300

      # Run retry on model failure
      runRetries:
        maxAttempts: 3
        baseDelayMs: 1000
        backoff: "exponential"

      # Context limits: trim tool output / memory to stay within window
      contextLimits:
        memoryGetMaxChars: 50000
        toolResultMaxChars: 80000
        toolResultTotalMaxChars: 200000

    # Per-agent overrides (legacy path "lyclaw.agents" — kept for backward compat)
    agents:
      code-reviewer:
        systemPromptOverride: "You are an expert code reviewer. Be thorough but concise."
        model: "deepseek-v4-pro"
        thinkingDefault: "high"
        maxToolRounds: 20
```

### 1.1.8 Annotation Usage Example

```java
package com.example.agents;

import lyjew.com.lyclaw.annotation.Agent;
import lyjew.com.lyclaw.annotation.agent.UserMessage;
import lyjew.com.lyclaw.annotation.agent.V;

/**
 * Code reviewer agent — uses a pro model with high thinking for quality output.
 */
@Agent(
    id          = "code-reviewer",
    defaultAgent = false,
    name        = "Code Reviewer",
    description = "Reviews code changes for bugs, style, and security issues",
    version     = "2.0.0",
    model       = "deepseek-v4-pro",
    thinkingDefault = "high",
    sandbox     = "docker",
    skills      = {"code-analysis", "security-scan"},
    delegationMode = "prefer",
    allowAgents = {"tester", "linter"},
    maxSpawnDepth = 2,
    maxChildrenPerAgent = 3,
    contextInjection = "always"
)
public interface CodeReviewerAgent {

    @UserMessage("Review the following code changes:\n\n{{diff}}")
    String review(@V("diff") String diff);

    @UserMessage("Review PR #{{prNumber}} in repository {{repo}}")
    String reviewPullRequest(@V("prNumber") int prNumber, @V("repo") String repo);
}
```

---

## 1.2 AgentContext Enhancement

### 1.2.1 Problem

The current `AgentContext` is a flat POJO with fields like `sessionId`, `userMessage`,
`systemPrompt`, `toolRegistry`, `method`, `args`, plus some pipeline-state atomics.
It has no knowledge of the agent's resolved config, no workspace paths, no runtime-type
awareness, and no subagent tracking.

### 1.2.2 Enhanced AgentContext

```java
package lyjew.com.lyclaw.react;

import lyjew.com.lyclaw.config.ResolvedAgentConfig;
import lyjew.com.lyclaw.model.ChatRequest;
import lyjew.com.lyclaw.model.Message;
import lyjew.com.lyclaw.security.SandboxLevel;
import lyjew.com.lyclaw.task.TaskNode;
import lyjew.com.lyclaw.tool.ToolRegistry;
import lyjew.com.lyclaw.tracing.TraceContext;

import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.*;

/**
 * Enhanced AgentContext — the unified data bus for all hook, stage, and runtime operations.
 *
 * <h3>New fields (Phase 1 additions in bold):</h3>
 * <ul>
 *   <li><b>agentId, agentName</b> — from ResolvedAgentConfig</li>
 *   <li><b>workspaceDir, agentDir</b> — resolved filesystem paths</li>
 *   <li><b>resolvedConfig</b> — fully merged ResolvedAgentConfig</li>
 *   <li><b>bootstrapContent</b> — loaded AGENTS.md, CLAUDE.md content</li>
 *   <li><b>contextLimits</b> — memory/tool result size caps</li>
 *   <li><b>thinkingLevel, verboseLevel, reasoningLevel</b> — effective levels</li>
 *   <li><b>delegationMode, allowAgents, maxSpawnDepth, maxChildrenPerAgent</b></li>
 *   <li><b>activeSubagentIds</b> — track spawned children</li>
 *   <li><b>runtimeType</b> — EMBEDDED or ACP</li>
 *   <li><b>runMetadata</b> — runId, jobId, trigger, channelId</li>
 * </ul>
 */
public class AgentContext {

    public enum Lifecycle { TRANSIENT, SESSION, PERSISTENT }

    /**
     * Which runtime engine backs this agent invocation.
     */
    public enum AgentRuntimeType {
        /** LyClaw's built-in ReAct engine. */
        EMBEDDED,
        /** External agent backend via Agent Communication Protocol. */
        ACP
    }

    // ==================== Agent Identity (NEW) ====================

    private final String agentId;
    private final String agentName;
    private final ResolvedAgentConfig resolvedConfig;

    // ==================== Workspace (NEW) ====================

    private final String workspaceDir;
    private final String agentDir;

    // ==================== Bootstrap Content (NEW) ====================

    /**
     * Content loaded from AGENTS.md, CLAUDE.md, system.md etc.
     * Key = filename, Value = file content (truncated to bootstrapMaxChars).
     */
    private final Map<String, Object> bootstrapContent = new LinkedHashMap<>();

    // ==================== Context Limits (NEW) ====================

    /** Max chars for memory retrieval. */
    private int memoryGetMaxChars = 50000;
    /** Max chars for a single tool result. */
    private int toolResultMaxChars = 80000;
    /** Max total chars for all tool results. */
    private int toolResultTotalMaxChars = 200000;

    // ==================== Thinking / Verbose / Reasoning (NEW) ====================

    private String thinkingLevel = "off";
    private String verboseLevel = "";
    private String reasoningLevel = "";

    // ==================== Subagent Delegation (NEW) ====================

    private String delegationMode = "suggest";
    private List<String> allowAgents = List.of();
    private int maxSpawnDepth = 1;
    private int maxChildrenPerAgent = 5;

    /** Track ids of currently-active subagents spawned by this agent. */
    private final List<String> activeSubagentIds = new CopyOnWriteArrayList<>();

    // ==================== Runtime Type (NEW) ====================

    private AgentRuntimeType runtimeType = AgentRuntimeType.EMBEDDED;

    // ==================== Run Metadata (NEW) ====================

    /**
     * Arbitrary metadata about the run: runId, jobId, trigger (e.g., "webhook"),
     * channelId (e.g., Slack channel), etc.
     */
    private final Map<String, Object> runMetadata = new LinkedHashMap<>();

    // ==================== Legacy fields (unchanged) ====================

    private final String sessionId;
    private String userMessage;
    private String systemPrompt;
    private ChatRequest chatRequest;
    private final ToolRegistry toolRegistry;
    private final Method method;
    private final Object[] args;
    private SandboxLevel sandboxLevel;
    private Lifecycle lifecycle = Lifecycle.TRANSIENT;

    private final TraceContext tracing;

    private final List<String> toolResults = new CopyOnWriteArrayList<>();
    private final AtomicInteger successCount = new AtomicInteger(0);
    private final AtomicInteger failCount = new AtomicInteger(0);
    private final List<TaskNode> nodes = new CopyOnWriteArrayList<>();
    private final AtomicReference<Double> reflectScoreRef = new AtomicReference<>(0.0);
    private final AtomicBoolean pipelineOk = new AtomicBoolean(false);
    private final AtomicLong respondStartMs = new AtomicLong();
    private final AtomicBoolean terminated = new AtomicBoolean(false);
    private final AtomicReference<String> currentStage = new AtomicReference<>("init");

    private final Map<String, Object> attributes = new HashMap<>();

    // ==================== Constructors ====================

    /**
     * Full constructor with ResolvedAgentConfig.
     */
    public AgentContext(String sessionId, String userMessage, String systemPrompt,
                        ToolRegistry toolRegistry, Method method, Object[] args,
                        ResolvedAgentConfig resolvedConfig) {
        this.sessionId = sessionId;
        this.userMessage = userMessage;
        this.systemPrompt = systemPrompt;
        this.toolRegistry = toolRegistry;
        this.method = method;
        this.args = args;
        this.tracing = new TraceContext();

        // Populate from resolved config
        this.resolvedConfig = resolvedConfig;
        this.agentId = resolvedConfig.getAgentId();
        this.agentName = resolvedConfig.getAgentName();
        this.workspaceDir = resolvedConfig.getWorkspaceDir();
        this.agentDir = resolvedConfig.getAgentDir();
        this.thinkingLevel = resolvedConfig.getThinkingDefault();
        this.verboseLevel = resolvedConfig.getVerboseDefault();
        this.reasoningLevel = resolvedConfig.getReasoningDefault();
        this.delegationMode = resolvedConfig.getDelegationMode();
        this.allowAgents = resolvedConfig.getAllowAgents();
        this.maxSpawnDepth = resolvedConfig.getMaxSpawnDepth();
        this.maxChildrenPerAgent = resolvedConfig.getMaxChildrenPerAgent();

        if (resolvedConfig.getContextLimits() != null) {
            this.memoryGetMaxChars = resolvedConfig.getContextLimits().getMemoryGetMaxChars();
            this.toolResultMaxChars = resolvedConfig.getContextLimits().getToolResultMaxChars();
            this.toolResultTotalMaxChars = resolvedConfig.getContextLimits().getToolResultTotalMaxChars();
        }
    }

    /** Backward-compatible constructor (no ResolvedAgentConfig). */
    public AgentContext(String sessionId, String userMessage, String systemPrompt,
                        ToolRegistry toolRegistry, Method method, Object[] args) {
        this(sessionId, userMessage, systemPrompt, toolRegistry, method, args, null);
    }

    // ==================== New Getters/Setters ====================

    public String getAgentId() { return agentId; }
    public String getAgentName() { return agentName; }
    public ResolvedAgentConfig getResolvedConfig() { return resolvedConfig; }
    public String getWorkspaceDir() { return workspaceDir; }
    public String getAgentDir() { return agentDir; }

    public Map<String, Object> getBootstrapContent() { return bootstrapContent; }
    public void addBootstrapContent(String filename, Object content) {
        this.bootstrapContent.put(filename, content);
    }

    public int getMemoryGetMaxChars() { return memoryGetMaxChars; }
    public void setMemoryGetMaxChars(int v) { this.memoryGetMaxChars = v; }
    public int getToolResultMaxChars() { return toolResultMaxChars; }
    public void setToolResultMaxChars(int v) { this.toolResultMaxChars = v; }
    public int getToolResultTotalMaxChars() { return toolResultTotalMaxChars; }
    public void setToolResultTotalMaxChars(int v) { this.toolResultTotalMaxChars = v; }

    public String getThinkingLevel() { return thinkingLevel; }
    public void setThinkingLevel(String v) { this.thinkingLevel = v; }
    public String getVerboseLevel() { return verboseLevel; }
    public void setVerboseLevel(String v) { this.verboseLevel = v; }
    public String getReasoningLevel() { return reasoningLevel; }
    public void setReasoningLevel(String v) { this.reasoningLevel = v; }

    public String getDelegationMode() { return delegationMode; }
    public void setDelegationMode(String v) { this.delegationMode = v; }
    public List<String> getAllowAgents() { return allowAgents; }
    public void setAllowAgents(List<String> v) { this.allowAgents = v; }
    public int getMaxSpawnDepth() { return maxSpawnDepth; }
    public void setMaxSpawnDepth(int v) { this.maxSpawnDepth = v; }
    public int getMaxChildrenPerAgent() { return maxChildrenPerAgent; }
    public void setMaxChildrenPerAgent(int v) { this.maxChildrenPerAgent = v; }

    public List<String> getActiveSubagentIds() { return activeSubagentIds; }
    public void addActiveSubagentId(String id) { this.activeSubagentIds.add(id); }
    public void removeActiveSubagentId(String id) { this.activeSubagentIds.remove(id); }

    public AgentRuntimeType getRuntimeType() { return runtimeType; }
    public void setRuntimeType(AgentRuntimeType v) { this.runtimeType = v; }

    public Map<String, Object> getRunMetadata() { return runMetadata; }
    public void setRunMetadata(String key, Object value) { this.runMetadata.put(key, value); }
    @SuppressWarnings("unchecked")
    public <T> T getRunMetadata(String key) { return (T) runMetadata.get(key); }

    // ==================== Legacy Getters (unchanged) ====================

    public String getSessionId() { return sessionId; }
    public String getUserMessage() { return userMessage; }
    public void setUserMessage(String userMessage) { this.userMessage = userMessage; }
    public String getSystemPrompt() { return systemPrompt; }
    public void setSystemPrompt(String systemPrompt) { this.systemPrompt = systemPrompt; }
    public ChatRequest getChatRequest() { return chatRequest; }
    public void setChatRequest(ChatRequest chatRequest) { this.chatRequest = chatRequest; }
    public ToolRegistry getToolRegistry() { return toolRegistry; }
    public Method getMethod() { return method; }
    public Object[] getArgs() { return args; }
    public SandboxLevel getSandboxLevel() { return sandboxLevel; }
    public void setSandboxLevel(SandboxLevel sandboxLevel) { this.sandboxLevel = sandboxLevel; }
    public Lifecycle getLifecycle() { return lifecycle; }
    public void setLifecycle(Lifecycle lifecycle) { this.lifecycle = lifecycle; }
    public TraceContext getTracing() { return tracing; }
    public List<String> getToolResults() { return toolResults; }
    public void addToolResult(String result) { toolResults.add(result); }
    public AtomicInteger getSuccessCount() { return successCount; }
    public AtomicInteger getFailCount() { return failCount; }
    public List<TaskNode> getNodes() { return nodes; }
    public void addNode(TaskNode node) { nodes.add(node); }
    public AtomicReference<Double> getReflectScoreRef() { return reflectScoreRef; }
    public AtomicBoolean getPipelineOk() { return pipelineOk; }
    public boolean isPipelineOk() { return pipelineOk.get(); }
    public void setPipelineOk(boolean value) { pipelineOk.set(value); }
    public AtomicLong getRespondStartMs() { return respondStartMs; }
    public AtomicBoolean getTerminated() { return terminated; }
    public boolean isTerminated() { return terminated.get(); }
    public void setTerminated(boolean value) { terminated.set(value); }
    public AtomicReference<String> getCurrentStage() { return currentStage; }
    public <T> T getAttribute(String key) { return (T) attributes.get(key); }
    public void setAttribute(String key, Object value) { attributes.put(key, value); }
    public Map<String, Object> getAttributes() { return attributes; }

    // ==================== Enhanced Snapshot/Restore ====================

    /**
     * Enhanced snapshot — includes all new fields.
     */
    public Map<String, Object> toSnapshot() {
        Map<String, Object> snapshot = new LinkedHashMap<>();

        // Legacy
        snapshot.put("sessionId", sessionId);
        snapshot.put("userMessage", userMessage);
        snapshot.put("systemPrompt", systemPrompt);
        snapshot.put("sandboxLevel", sandboxLevel != null ? sandboxLevel.name() : null);
        snapshot.put("lifecycle", lifecycle.name());
        snapshot.put("currentStage", currentStage.get());
        snapshot.put("successCount", successCount.get());
        snapshot.put("failCount", failCount.get());
        snapshot.put("pipelineOk", pipelineOk.get());
        snapshot.put("terminated", terminated.get());
        snapshot.put("reflectScore", reflectScoreRef.get());
        snapshot.put("toolResults", new ArrayList<>(toolResults));
        snapshot.put("tracing", Map.of("traceId", tracing.getTraceId()));

        // New — identity
        snapshot.put("agentId", agentId);
        snapshot.put("agentName", agentName);

        // New — workspace
        snapshot.put("workspaceDir", workspaceDir);
        snapshot.put("agentDir", agentDir);

        // New — levels
        snapshot.put("thinkingLevel", thinkingLevel);
        snapshot.put("verboseLevel", verboseLevel);
        snapshot.put("reasoningLevel", reasoningLevel);

        // New — delegation
        snapshot.put("delegationMode", delegationMode);
        snapshot.put("allowAgents", new ArrayList<>(allowAgents));
        snapshot.put("maxSpawnDepth", maxSpawnDepth);
        snapshot.put("maxChildrenPerAgent", maxChildrenPerAgent);

        // New — context limits
        snapshot.put("memoryGetMaxChars", memoryGetMaxChars);
        snapshot.put("toolResultMaxChars", toolResultMaxChars);
        snapshot.put("toolResultTotalMaxChars", toolResultTotalMaxChars);

        // New — runtime
        snapshot.put("runtimeType", runtimeType.name());
        snapshot.put("activeSubagentIds", new ArrayList<>(activeSubagentIds));
        snapshot.put("runMetadata", new HashMap<>(runMetadata));

        // New — bootstrap
        snapshot.put("bootstrapContent", new HashMap<>(bootstrapContent));

        return snapshot;
    }

    /**
     * Restore from snapshot. Runtime references (toolRegistry, method, args)
     * must be re-injected by the caller.
     */
    @SuppressWarnings("unchecked")
    public void restoreFromSnapshot(Map<String, Object> snapshot) {
        if (snapshot == null) return;

        // Legacy
        if (snapshot.get("sandboxLevel") != null)
            this.sandboxLevel = SandboxLevel.valueOf((String) snapshot.get("sandboxLevel"));
        if (snapshot.get("lifecycle") != null)
            this.lifecycle = Lifecycle.valueOf((String) snapshot.get("lifecycle"));
        if (snapshot.get("currentStage") != null)
            this.currentStage.set((String) snapshot.get("currentStage"));
        if (snapshot.get("successCount") != null)
            this.successCount.set(((Number) snapshot.get("successCount")).intValue());
        if (snapshot.get("failCount") != null)
            this.failCount.set(((Number) snapshot.get("failCount")).intValue());
        if (snapshot.get("pipelineOk") != null)
            this.pipelineOk.set((Boolean) snapshot.get("pipelineOk"));
        if (snapshot.get("terminated") != null)
            this.terminated.set((Boolean) snapshot.get("terminated"));
        if (snapshot.get("reflectScore") != null)
            this.reflectScoreRef.set(((Number) snapshot.get("reflectScore")).doubleValue());
        if (snapshot.get("toolResults") instanceof List<?> list) {
            this.toolResults.clear();
            for (Object item : list) this.toolResults.add((String) item);
        }

        // New — identity
        if (snapshot.get("agentId") != null)
            this.setRunMetadata("restoredAgentId", snapshot.get("agentId"));

        // New — levels
        if (snapshot.get("thinkingLevel") != null)
            this.thinkingLevel = (String) snapshot.get("thinkingLevel");
        if (snapshot.get("verboseLevel") != null)
            this.verboseLevel = (String) snapshot.get("verboseLevel");
        if (snapshot.get("reasoningLevel") != null)
            this.reasoningLevel = (String) snapshot.get("reasoningLevel");

        // New — delegation
        if (snapshot.get("delegationMode") != null)
            this.delegationMode = (String) snapshot.get("delegationMode");
        if (snapshot.get("allowAgents") instanceof List<?> al)
            this.allowAgents = al.stream().map(Object::toString).toList();
        if (snapshot.get("maxSpawnDepth") instanceof Number n)
            this.maxSpawnDepth = n.intValue();
        if (snapshot.get("maxChildrenPerAgent") instanceof Number n)
            this.maxChildrenPerAgent = n.intValue();

        // New — context limits
        if (snapshot.get("memoryGetMaxChars") instanceof Number n)
            this.memoryGetMaxChars = n.intValue();
        if (snapshot.get("toolResultMaxChars") instanceof Number n)
            this.toolResultMaxChars = n.intValue();
        if (snapshot.get("toolResultTotalMaxChars") instanceof Number n)
            this.toolResultTotalMaxChars = n.intValue();

        // New — runtime
        if (snapshot.get("runtimeType") != null)
            this.runtimeType = AgentRuntimeType.valueOf((String) snapshot.get("runtimeType"));
        if (snapshot.get("activeSubagentIds") instanceof List<?> sl) {
            this.activeSubagentIds.clear();
            for (Object item : sl) this.activeSubagentIds.add((String) item);
        }
        if (snapshot.get("runMetadata") instanceof Map<?, ?> rm) {
            this.runMetadata.clear();
            for (Map.Entry<?, ?> e : rm.entrySet())
                this.runMetadata.put((String) e.getKey(), e.getValue());
        }
        if (snapshot.get("bootstrapContent") instanceof Map<?, ?> bc) {
            this.bootstrapContent.clear();
            for (Map.Entry<?, ?> e : bc.entrySet())
                this.bootstrapContent.put((String) e.getKey(), e.getValue());
        }
    }

    // ==================== Factory Methods ====================

    public static AgentContext sessionScoped(String sessionId, String userMessage,
                                             String systemPrompt, ToolRegistry toolRegistry,
                                             Method method, Object[] args,
                                             ResolvedAgentConfig resolvedConfig) {
        AgentContext ctx = new AgentContext(sessionId, userMessage, systemPrompt,
                toolRegistry, method, args, resolvedConfig);
        ctx.setLifecycle(Lifecycle.SESSION);
        return ctx;
    }

    public static AgentContext persistentScoped(String sessionId, String userMessage,
                                                 String systemPrompt, ToolRegistry toolRegistry,
                                                 Method method, Object[] args,
                                                 ResolvedAgentConfig resolvedConfig) {
        AgentContext ctx = new AgentContext(sessionId, userMessage, systemPrompt,
                toolRegistry, method, args, resolvedConfig);
        ctx.setLifecycle(Lifecycle.PERSISTENT);
        return ctx;
    }
}
```

---

## 1.3 Hook System Expansion (5 to 36 Hooks)

### 1.3.1 Problem

The current `AgentHook` has only 5 extension points: `beforeRequest`, `beforeModel`,
`afterModel`, `wrapToolCall`, `wrapToolExecutor`, `afterResult`. There is no way to
hook into session lifecycle, agent start/end, subagent spawning, compaction, message
events, or heartbeat contributions.

### 1.3.2 Full Hook Interface

```java
package lyjew.com.lyclaw.react;

import java.util.List;
import lyjew.com.lyclaw.model.Message;
import lyjew.com.lyclaw.model.ToolCall;

/**
 * Full agent lifecycle hook SPI — 36 extension points.
 *
 * <p>All methods are default (no-op) so implementors override only what they need.
 * Hooks are dispatched by {@link AgentInvocationHandler} at the appropriate
 * point in the agent lifecycle.
 *
 * <h3>Execution order</h3>
 * <p>Hooks are sorted by {@link #getOrder()} (ascending) before dispatch.
 * Default order is 100.</p>
 */
public interface AgentHook {

    // =====================================================================
    // EXISTING (kept for backward compatibility)
    // =====================================================================

    /** Before the entire agent invocation pipeline starts.
     *  Throwing an exception aborts the request. */
    default void beforeRequest(AgentContext ctx) {}

    /** Before each LLM call. Can inject planning context or adjust messages. */
    default List<Message> beforeModel(List<Message> messages, AgentContext ctx) {
        return messages;
    }

    /** After each LLM response. Can detect harmful content, log, or transform output. */
    default String afterModel(String response, AgentContext ctx) {
        return response;
    }

    /** Wrap a single tool call (finer-grained than wrapToolExecutor). */
    default ToolCall wrapToolCall(ToolCall toolCall, AgentContext ctx) {
        return toolCall;
    }

    /** Wrap the ToolExecutor, forming a decorator chain. */
    default ToolExecutor wrapToolExecutor(ToolExecutor inner, AgentContext ctx) {
        return inner;
    }

    /** After the final result, before returning to the caller.
     *  Dispatched in reverse order (afterResult hooks run high-to-low). */
    default String afterResult(String result, AgentContext ctx) {
        return result;
    }

    /** Priority. Lower numbers execute first. Default: 100. */
    default int getOrder() { return 100; }

    // =====================================================================
    // NEW — Model Lifecycle
    // =====================================================================

    /** Before model resolution (provider + model selection). */
    default void beforeModelResolve(AgentContext ctx) {}

    /** Called when a model call begins (after routing, before API call). */
    default void modelCallStarted(AgentContext ctx) {}

    /** Called when a model call ends (success or failure). */
    default void modelCallEnded(AgentContext ctx) {}

    /** Raw LLM input (the final assembled prompt sent to the model). */
    default void llmInput(String prompt, AgentContext ctx) {}

    /** Raw LLM output (the complete model response, before parsing). */
    default void llmOutput(String response, AgentContext ctx) {}

    // =====================================================================
    // NEW — Agent Lifecycle
    // =====================================================================

    /** Before an agent run starts (pipeline entry). */
    default void beforeAgentStart(AgentContext ctx) {}

    /**
     * Before the agent's reply is sent back to the caller.
     * @param reply the draft reply text
     * @param ctx agent context
     */
    default void beforeAgentReply(String reply, AgentContext ctx) {}

    /**
     * Before the agent is finalized (after ReAct loop ends, before cleanup).
     * Can return a decision to CONTINUE (default), REVISE (retry with instruction),
     * or FINALIZE (skip revision).
     */
    default AgentFinalizeResult beforeAgentFinalize(AgentContext ctx) {
        return AgentFinalizeResult.continue_();
    }

    /** After the agent run completes (cleanup, metrics, notification). */
    default void agentEnd(AgentContext ctx) {}

    /** Before each individual agent invocation (per-method call on the proxy). */
    default void beforeAgentRun(AgentContext ctx) {}

    // =====================================================================
    // NEW — Tool Lifecycle
    // =====================================================================

    /** Before a tool is invoked. Contains tool name, call id, serialized args. */
    default void beforeToolCall(String toolName, String toolCallId, String args, AgentContext ctx) {}

    /** After a tool completes. Contains the result string (could be error). */
    default void afterToolCall(String toolName, String toolCallId, String result, AgentContext ctx) {}

    /** After tool result is persisted into message history. */
    default void toolResultPersist(String toolName, String result, AgentContext ctx) {}

    // =====================================================================
    // NEW — Session Lifecycle
    // =====================================================================

    /** When a new agent session is created. */
    default void sessionStart(String sessionId, AgentContext ctx) {}

    /** When an agent session ends (clean shutdown or timeout). */
    default void sessionEnd(String sessionId, AgentContext ctx) {}

    // =====================================================================
    // NEW — Subagent Lifecycle
    // =====================================================================

    /** Before a subagent is spawned. Hook can block by throwing. */
    default void subagentSpawning(String childAgentId, String task, AgentContext ctx) {}

    /** After a subagent is successfully spawned and session created. */
    default void subagentSpawned(String childAgentId, String sessionKey, AgentContext ctx) {}

    /** After a subagent completes (success or failure). */
    default void subagentEnded(String childAgentId, String outcome, AgentContext ctx) {}

    // =====================================================================
    // NEW — Compaction
    // =====================================================================

    /** Before message history compaction (context window management). */
    default void beforeCompaction(AgentContext ctx) {}

    /** After message history compaction. */
    default void afterCompaction(AgentContext ctx) {}

    // =====================================================================
    // NEW — Message Lifecycle
    // =====================================================================

    /** A message was received from the caller/user. */
    default void messageReceived(Message msg, AgentContext ctx) {}

    /** The agent is about to send a message (before LLM call). */
    default void messageSending(String msg, AgentContext ctx) {}

    /** A message was sent to the caller. */
    default void messageSent(String msg, AgentContext ctx) {}

    // =====================================================================
    // NEW — Heartbeat
    // =====================================================================

    /**
     * Contribute content to the periodic heartbeat prompt sent to the LLM
     * to keep long-running agents alive and aware of their context.
     * @return contribution string (appended to heartbeat prompt), or "" for nothing.
     */
    default String heartbeatPromptContribution(AgentContext ctx) { return ""; }
}
```

### 1.3.3 AgentFinalizeResult

```java
package lyjew.com.lyclaw.react;

/**
 * Returned by {@link AgentHook#beforeAgentFinalize(AgentContext)}.
 * Controls whether the agent run is complete, needs revision, or should
 * finalize immediately.
 */
public class AgentFinalizeResult {

    public enum Action {
        /** Continue normally — proceed to finalize and return result. */
        CONTINUE,
        /** Revise — loop back to respond with retryInstruction. */
        REVISE,
        /** Finalize immediately — skip any remaining revision logic. */
        FINALIZE
    }

    private final Action action;
    private final String reason;
    private final String retryInstruction;
    private final String idempotencyKey;
    private final int maxAttempts;

    private AgentFinalizeResult(Action action, String reason, String retryInstruction,
                                String idempotencyKey, int maxAttempts) {
        this.action = action;
        this.reason = reason;
        this.retryInstruction = retryInstruction;
        this.idempotencyKey = idempotencyKey;
        this.maxAttempts = maxAttempts;
    }

    // ===== Factory methods =====

    public static AgentFinalizeResult continue_() {
        return new AgentFinalizeResult(Action.CONTINUE, null, null, null, 1);
    }

    public static AgentFinalizeResult revise(String reason, String retryInstruction) {
        return new AgentFinalizeResult(Action.REVISE, reason, retryInstruction, null, 3);
    }

    public static AgentFinalizeResult revise(String reason, String retryInstruction,
                                              String idempotencyKey, int maxAttempts) {
        return new AgentFinalizeResult(Action.REVISE, reason, retryInstruction,
                idempotencyKey, maxAttempts);
    }

    public static AgentFinalizeResult finalize(String reason) {
        return new AgentFinalizeResult(Action.FINALIZE, reason, null, null, 1);
    }

    // ===== Getters =====

    public Action getAction() { return action; }
    public String getReason() { return reason; }
    public String getRetryInstruction() { return retryInstruction; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public int getMaxAttempts() { return maxAttempts; }

    public boolean isContinue() { return action == Action.CONTINUE; }
    public boolean isRevise() { return action == Action.REVISE; }
    public boolean isFinalize() { return action == Action.FINALIZE; }
}
```

### 1.3.4 HookDecision (Security / Approval)

```java
package lyjew.com.lyclaw.react;

import java.util.Map;

/**
 * A blocking/approval decision returned by hooks that gate execution.
 * Used by security hooks, approval hooks, etc.
 */
public class HookDecision {

    public enum Outcome {
        /** Allow execution to proceed. */
        PASS,
        /** Block execution. */
        BLOCK
    }

    private final Outcome outcome;
    private final String reason;
    private final String message;       // user-facing message
    private final String category;      // e.g., "security", "approval", "rate-limit"
    private final Map<String, Object> metadata;

    private HookDecision(Outcome outcome, String reason, String message,
                         String category, Map<String, Object> metadata) {
        this.outcome = outcome;
        this.reason = reason;
        this.message = message;
        this.category = category;
        this.metadata = metadata != null ? Map.copyOf(metadata) : Map.of();
    }

    public static HookDecision pass() {
        return new HookDecision(Outcome.PASS, null, null, null, null);
    }

    public static HookDecision block(String reason, String message, String category) {
        return new HookDecision(Outcome.BLOCK, reason, message, category, null);
    }

    public static HookDecision block(String reason, String message, String category,
                                      Map<String, Object> metadata) {
        return new HookDecision(Outcome.BLOCK, reason, message, category, metadata);
    }

    public Outcome getOutcome() { return outcome; }
    public String getReason() { return reason; }
    public String getMessage() { return message; }
    public String getCategory() { return category; }
    public Map<String, Object> getMetadata() { return metadata; }
    public boolean isPass() { return outcome == Outcome.PASS; }
    public boolean isBlock() { return outcome == Outcome.BLOCK; }
}
```

### 1.3.5 HookRegistration (Registry Entry)

```java
package lyjew.com.lyclaw.react;

import java.util.function.BiConsumer;
import java.util.function.Function;

/**
 * A registered hook entry in the {@link HookRegistry}.
 *
 * @param pluginId    the plugin/module that registered this hook
 * @param hookName    the hook method name (e.g. "beforeModel", "afterToolCall")
 * @param handler     the handler function (signature varies by hook)
 * @param priority    execution priority (lower = earlier)
 * @param timeoutMs   max execution time before the hook is considered hung (0 = no timeout)
 * @param source      how the hook was registered (annotation, SPI, programmatic)
 */
public record HookRegistration(
        String pluginId,
        String hookName,
        Object handler,          // Function or BiConsumer depending on hook
        int priority,
        long timeoutMs,
        String source            // "annotation", "spi", "programmatic"
) {
    public HookRegistration {
        if (pluginId == null || pluginId.isBlank()) pluginId = "unknown";
        if (hookName == null || hookName.isBlank()) throw new IllegalArgumentException("hookName is required");
        if (handler == null) throw new IllegalArgumentException("handler is required");
        if (timeoutMs < 0) timeoutMs = 0;
        if (source == null || source.isBlank()) source = "programmatic";
    }

    public static HookRegistration of(String pluginId, String hookName,
                                       Object handler, int priority) {
        return new HookRegistration(pluginId, hookName, handler, priority, 0, "programmatic");
    }
}
```

### 1.3.6 HookRegistry

```java
package lyjew.com.lyclaw.react;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Central registry for hook management and dispatch.
 *
 * <p>Hooks are grouped by hook name (e.g., "beforeModel", "afterToolCall").
 * At dispatch time, they are sorted by priority (ascending) and invoked in order.
 */
public class HookRegistry {

    private static final Logger log = LoggerFactory.getLogger(HookRegistry.class);

    /** hookName → sorted list of registrations. */
    private final Map<String, List<HookRegistration>> registrations = new ConcurrentHashMap<>();

    /**
     * Register a hook. If the hook name is new, a list is created.
     * Registrations for the same hook name are kept sorted by priority.
     */
    public void register(HookRegistration reg) {
        registrations.compute(reg.hookName(), (k, list) -> {
            if (list == null) list = new CopyOnWriteArrayList<>();
            list.add(reg);
            list.sort(Comparator.comparingInt(HookRegistration::priority));
            return list;
        });
        log.debug("Hook registered: pluginId={} hookName={} priority={}",
                reg.pluginId(), reg.hookName(), reg.priority());
    }

    /**
     * Unregister all hooks from a given plugin.
     */
    public void unregisterPlugin(String pluginId) {
        registrations.forEach((hookName, list) ->
                list.removeIf(reg -> reg.pluginId().equals(pluginId)));
    }

    /**
     * Get all registrations for a hook name, sorted by priority.
     */
    public List<HookRegistration> getHooks(String hookName) {
        return registrations.getOrDefault(hookName, List.of());
    }

    /**
     * Get all registered hook names.
     */
    public Set<String> getHookNames() {
        return Collections.unmodifiableSet(registrations.keySet());
    }

    /**
     * Clear all registrations.
     */
    public void clear() {
        registrations.clear();
    }
}
```

### 1.3.7 AgentInvocationHandler — Hook Dispatch Updates

The existing `AgentInvocationHandler` is updated to dispatch the new hooks at the right
points in the lifecycle:

```java
// Inside AgentInvocationHandler.invoke() — pseudocode for hook dispatch additions:

@Override
public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
    // ... (existing setup: resolve messages, build context, create AgentContext) ...

    AgentContext ctx = new AgentContext(sessionId, userMessage, systemPrompt,
            toolRegistry, method, args, resolvedConfig);

    // NEW: dispatch beforeAgentStart + beforeAgentRun
    dispatch("beforeAgentStart", ctx);
    dispatch("beforeAgentRun", ctx);

    // NEW: dispatch sessionStart (once per session)
    dispatch("sessionStart", ctx.getSessionId(), ctx);

    // 1. beforeRequest hooks (legacy, kept for backward compat)
    List<AgentHook> sorted = sortedHooks();
    for (AgentHook hook : sorted) {
        hook.beforeRequest(ctx);
    }

    // ... (existing stage pipeline or ReAct execution) ...

    // Inside the ReAct loop, around each model call:

    // NEW: beforeModelResolve
    dispatch("beforeModelResolve", ctx);

    // NEW: modelCallStarted
    dispatch("modelCallStarted", ctx);

    // LEGACY: beforeModel (kept)
    for (AgentHook hook : sorted) {
        messages = hook.beforeModel(messages, ctx);
    }

    // NEW: llmInput
    dispatch("llmInput", assembledPrompt, ctx);

    // ... (actual LLM call) ...

    // NEW: llmOutput
    dispatch("llmOutput", response, ctx);

    // LEGACY: afterModel (kept)
    for (AgentHook hook : sorted) {
        response = hook.afterModel(response, ctx);
    }

    // NEW: modelCallEnded
    dispatch("modelCallEnded", ctx);

    // Around each tool call in the ReAct loop:

    // NEW: beforeToolCall
    dispatch("beforeToolCall", toolName, toolCallId, argsJson, ctx);

    // ... (actual tool execution) ...

    // NEW: afterToolCall
    dispatch("afterToolCall", toolName, toolCallId, result, ctx);

    // NEW: toolResultPersist
    dispatch("toolResultPersist", toolName, result, ctx);

    // After ReAct loop ends (before returning result):

    // NEW: beforeAgentFinalize — allows REVISE gate
    AgentFinalizeResult finalizeResult = dispatchFinalize(ctx);
    if (finalizeResult.isRevise()) {
        // loop back to ReAct with retryInstruction
    }

    // LEGACY: afterResult (kept, reverse order)
    for (int i = sorted.size() - 1; i >= 0; i--) {
        result = sorted.get(i).afterResult(result, ctx);
    }

    // NEW: agentEnd
    dispatch("agentEnd", ctx);

    // NEW: sessionEnd (if session is ending)
    dispatch("sessionEnd", ctx.getSessionId(), ctx);

    return result;
}
```

The dispatch helpers used within AgentInvocationHandler:

```java
// Generic dispatch by hook name — uses HookRegistry for new hooks
// and direct AgentHook calls for legacy SPI methods.

private void dispatch(String hookName, Object... args) {
    List<HookRegistration> hooks = hookRegistry.getHooks(hookName);
    for (HookRegistration reg : hooks) {
        try {
            // Invoke handler (type-safe dispatch)
            invokeHandler(reg, args);
        } catch (Exception e) {
            log.warn("Hook {} (plugin={}) failed: {}", hookName, reg.pluginId(), e.getMessage());
            // Hook failures are non-fatal by default; SecurityHook can throw to block
        }
    }
}

private AgentFinalizeResult dispatchFinalize(AgentContext ctx) {
    List<HookRegistration> hooks = hookRegistry.getHooks("beforeAgentFinalize");
    for (HookRegistration reg : hooks) {
        try {
            @SuppressWarnings("unchecked")
            Function<AgentContext, AgentFinalizeResult> handler =
                    (Function<AgentContext, AgentFinalizeResult>) reg.handler();
            AgentFinalizeResult result = handler.apply(ctx);
            if (result.isRevise() || result.isFinalize()) {
                return result; // first non-continue short-circuits
            }
        } catch (Exception e) {
            log.warn("Finalize hook {} (plugin={}) failed: {}",
                    reg.hookName(), reg.pluginId(), e.getMessage());
        }
    }
    return AgentFinalizeResult.continue_();
}
```

### 1.3.8 Example: Migrating Existing Hooks

Existing hooks like `SecurityCheckHook`, `ApprovalHook`, `OutputGuardHook`,
`PlanningHook`, `SandboxHook` continue to implement `AgentHook` and work identically.
New hooks targeting specific lifecycle points register via `HookRegistry`:

```java
package com.example.hooks;

import lyjew.com.lyclaw.react.AgentContext;
import lyjew.com.lyclaw.react.HookRegistration;
import lyjew.com.lyclaw.react.HookRegistry;
import org.springframework.stereotype.Component;
import jakarta.annotation.PostConstruct;

/**
 * Example: a compaction logger hook that traces when context compaction happens.
 * Registered programmatically via HookRegistry rather than implementing AgentHook.
 */
@Component
public class CompactionLogger {

    private final HookRegistry hookRegistry;

    public CompactionLogger(HookRegistry hookRegistry) {
        this.hookRegistry = hookRegistry;
    }

    @PostConstruct
    public void registerHooks() {
        hookRegistry.register(HookRegistration.of(
                "compaction-logger",
                "beforeCompaction",
                (java.util.function.Consumer<AgentContext>) ctx -> {
                    // log context size before compaction
                },
                200
        ));

        hookRegistry.register(HookRegistration.of(
                "compaction-logger",
                "afterCompaction",
                (java.util.function.Consumer<AgentContext>) ctx -> {
                    // log context size after compaction
                },
                200
        ));
    }
}
```

---

## 1.4 AgentRuntime Modes

### 1.4.1 Problem

LyClaw currently only supports EMBEDDED mode (the built-in ReAct engine). OpenClaw
supports ACP (Agent Communication Protocol) mode where the agent backend runs in an
external process (e.g., a Node.js Codex CLI instance) and communicates via a
bidirectional protocol. Adding ACP support requires a clean abstraction.

### 1.4.2 AgentRuntimeType Enum

```java
package lyjew.com.lyclaw.react;

/**
 * The runtime mode that backs an agent invocation.
 */
public enum AgentRuntimeType {

    /**
     * Default mode — LyClaw's built-in ReAct engine handles the
     * full reasoning-acting loop internally.
     */
    EMBEDDED,

    /**
     * Agent Communication Protocol mode — the agent backend runs
     * in an external process. LyClaw communicates with it via
     * a bidirectional protocol (events, turns, sessions).
     */
    ACP
}
```

### 1.4.3 AcpRuntime Interface

```java
package lyjew.com.lyclaw.react;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import java.util.Map;

/**
 * ACP (Agent Communication Protocol) runtime SPI.
 *
 * <p>Implementations manage sessions and turns with external agent backends
 * (e.g., Codex CLI, custom agent servers). The protocol is:
 * <ol>
 *   <li>{@link #ensureSession(AcpRuntimeEnsureInput)} — get or create a session</li>
 *   <li>{@link #startTurn(AcpRuntimeTurnInput)} — start a conversational turn,
 *       receiving a Flux of events (text deltas, tool calls, status updates)</li>
 *   <li>{@link #cancel(AcpRuntimeHandle, String)} — cancel a running turn</li>
 *   <li>{@link #close(AcpRuntimeHandle, String)} — tear down the session</li>
 * </ol>
 */
public interface AcpRuntime {

    /**
     * Ensure a session exists for the given agent + session key.
     * Returns a handle that can be used for subsequent turn/cancel/close calls.
     */
    Mono<AcpRuntimeHandle> ensureSession(AcpRuntimeEnsureInput input);

    /**
     * Start a conversational turn. Returns a Flux of AcpRuntimeEvent:
     * text_delta (streaming tokens), tool_call, tool_result, status, done, error.
     */
    Flux<AcpRuntimeEvent> startTurn(AcpRuntimeTurnInput input);

    /**
     * Query the backend's capabilities (model, tools, features).
     */
    Mono<AcpRuntimeCapabilities> getCapabilities(AcpRuntimeHandle handle);

    /**
     * Cancel an in-progress turn.
     */
    Mono<Void> cancel(AcpRuntimeHandle handle, String reason);

    /**
     * Close (tear down) a session.
     */
    Mono<Void> close(AcpRuntimeHandle handle, String reason);
}
```

### 1.4.4 AcpRuntimeHandle

```java
package lyjew.com.lyclaw.react;

/**
 * Opaque handle to an active ACP session.
 *
 * <p>Contains identifiers needed by the AcpRuntime implementation to route
 * subsequent turn/cancel/close requests to the correct backend session.
 */
public class AcpRuntimeHandle {

    /** The session key used when the session was created. */
    private final String sessionKey;

    /** Which backend this session is on (e.g., "codex-cli", "custom-agent-server"). */
    private final String backend;

    /** The runtime-level session name (may differ from the user-facing session key). */
    private final String runtimeSessionName;

    /** Working directory for this session. */
    private final String cwd;

    /** Backend-specific session identifier (e.g., a process PID or UUID). */
    private final String backendSessionId;

    /** LyClaw-level agent session identifier. */
    private final String agentSessionId;

    public AcpRuntimeHandle(String sessionKey, String backend, String runtimeSessionName,
                            String cwd, String backendSessionId, String agentSessionId) {
        this.sessionKey = sessionKey;
        this.backend = backend;
        this.runtimeSessionName = runtimeSessionName;
        this.cwd = cwd;
        this.backendSessionId = backendSessionId;
        this.agentSessionId = agentSessionId;
    }

    public String getSessionKey() { return sessionKey; }
    public String getBackend() { return backend; }
    public String getRuntimeSessionName() { return runtimeSessionName; }
    public String getCwd() { return cwd; }
    public String getBackendSessionId() { return backendSessionId; }
    public String getAgentSessionId() { return agentSessionId; }
}
```

### 1.4.5 AcpRuntimeEvent

```java
package lyjew.com.lyclaw.react;

import java.util.Map;

/**
 * An event emitted during an ACP turn.
 *
 * <p>Events are streamed as a Flux from {@link AcpRuntime#startTurn(AcpRuntimeTurnInput)}.
 */
public class AcpRuntimeEvent {

    public enum EventType {
        /** A delta of text content (streaming token). */
        TEXT_DELTA,
        /** The backend wants to invoke a tool. */
        TOOL_CALL,
        /** A tool result to send back to the backend. */
        TOOL_RESULT,
        /** Status update (e.g., "thinking", "executing tool"). */
        STATUS,
        /** Turn completed successfully. */
        DONE,
        /** Turn failed with an error. */
        ERROR
    }

    private final EventType type;
    private final String data;
    private final Map<String, Object> metadata;

    public AcpRuntimeEvent(EventType type, String data, Map<String, Object> metadata) {
        this.type = type;
        this.data = data;
        this.metadata = metadata != null ? Map.copyOf(metadata) : Map.of();
    }

    // ===== Factory methods =====

    public static AcpRuntimeEvent textDelta(String text) {
        return new AcpRuntimeEvent(EventType.TEXT_DELTA, text, null);
    }

    public static AcpRuntimeEvent toolCall(String toolName, String toolCallId,
                                            String arguments, Map<String, Object> metadata) {
        return new AcpRuntimeEvent(EventType.TOOL_CALL,
                toolName,  // data carries the tool name; metadata has id + args
                Map.of("toolCallId", toolCallId, "arguments", arguments));
    }

    public static AcpRuntimeEvent toolResult(String toolCallId, String result, boolean success) {
        return new AcpRuntimeEvent(EventType.TOOL_RESULT, result,
                Map.of("toolCallId", toolCallId, "success", success));
    }

    public static AcpRuntimeEvent status(String status) {
        return new AcpRuntimeEvent(EventType.STATUS, status, null);
    }

    public static AcpRuntimeEvent done(String stopReason) {
        return new AcpRuntimeEvent(EventType.DONE, null,
                Map.of("stopReason", stopReason));
    }

    public static AcpRuntimeEvent error(String errorMessage) {
        return new AcpRuntimeEvent(EventType.ERROR, errorMessage, null);
    }

    // ===== Getters =====

    public EventType getType() { return type; }
    public String getData() { return data; }
    public Map<String, Object> getMetadata() { return metadata; }

    public boolean isTextDelta() { return type == EventType.TEXT_DELTA; }
    public boolean isToolCall() { return type == EventType.TOOL_CALL; }
    public boolean isDone() { return type == EventType.DONE; }
    public boolean isError() { return type == EventType.ERROR; }
}
```

### 1.4.6 Supporting Types

```java
package lyjew.com.lyclaw.react;

import java.util.Map;

/**
 * Input for {@link AcpRuntime#ensureSession(AcpRuntimeEnsureInput)}.
 */
public class AcpRuntimeEnsureInput {
    private String agentId;
    private String sessionKey;
    private String backend;        // which backend implementation to use
    private String workspaceDir;
    private Map<String, Object> env;
    private Map<String, Object> extra;  // backend-specific options

    // constructor, getters, setters omitted for brevity
    public AcpRuntimeEnsureInput() {}

    public String getAgentId() { return agentId; }
    public void setAgentId(String v) { this.agentId = v; }
    public String getSessionKey() { return sessionKey; }
    public void setSessionKey(String v) { this.sessionKey = v; }
    public String getBackend() { return backend; }
    public void setBackend(String v) { this.backend = v; }
    public String getWorkspaceDir() { return workspaceDir; }
    public void setWorkspaceDir(String v) { this.workspaceDir = v; }
    public Map<String, Object> getEnv() { return env; }
    public void setEnv(Map<String, Object> v) { this.env = v; }
    public Map<String, Object> getExtra() { return extra; }
    public void setExtra(Map<String, Object> v) { this.extra = v; }
}
```

```java
package lyjew.com.lyclaw.react;

import java.util.Map;

/**
 * Input for {@link AcpRuntime#startTurn(AcpRuntimeTurnInput)}.
 */
public class AcpRuntimeTurnInput {
    private AcpRuntimeHandle handle;
    private String userMessage;
    private String systemPrompt;
    private Map<String, Object> context;  // additional context

    // getters/setters
    public AcpRuntimeHandle getHandle() { return handle; }
    public void setHandle(AcpRuntimeHandle v) { this.handle = v; }
    public String getUserMessage() { return userMessage; }
    public void setUserMessage(String v) { this.userMessage = v; }
    public String getSystemPrompt() { return systemPrompt; }
    public void setSystemPrompt(String v) { this.systemPrompt = v; }
    public Map<String, Object> getContext() { return context; }
    public void setContext(Map<String, Object> v) { this.context = v; }
}
```

```java
package lyjew.com.lyclaw.react;

import java.util.List;
import java.util.Map;

/**
 * Backend capabilities reported by an ACP runtime.
 */
public class AcpRuntimeCapabilities {
    private String modelProvider;
    private String modelName;
    private List<String> availableTools;
    private Map<String, Object> features;  // arbitrary feature flags

    // getters/setters
    public String getModelProvider() { return modelProvider; }
    public void setModelProvider(String v) { this.modelProvider = v; }
    public String getModelName() { return modelName; }
    public void setModelName(String v) { this.modelName = v; }
    public List<String> getAvailableTools() { return availableTools; }
    public void setAvailableTools(List<String> v) { this.availableTools = v; }
    public Map<String, Object> getFeatures() { return features; }
    public void setFeatures(Map<String, Object> v) { this.features = v; }
}
```

```java
package lyjew.com.lyclaw.react;

/**
 * Result of a completed ACP turn.
 */
public class AcpRuntimeTurnResult {

    public enum Status {
        COMPLETED,   // turn finished normally
        CANCELLED,   // turn was cancelled by user or system
        FAILED       // turn failed with an error
    }

    private final Status status;
    private final String stopReason;
    private final String error;
    private final String fullText;  // accumulated text output

    public AcpRuntimeTurnResult(Status status, String stopReason, String error, String fullText) {
        this.status = status;
        this.stopReason = stopReason;
        this.error = error;
        this.fullText = fullText;
    }

    public Status getStatus() { return status; }
    public String getStopReason() { return stopReason; }
    public String getError() { return error; }
    public String getFullText() { return fullText; }
}
```

---

## 1.5 AgentProxyFactory Restructuring

### 1.5.1 Problem

The current `AgentProxyFactory` uses a telescoping constructor chain (5 constructors)
that bakes in `modelOverride`/`providerOverride` as flat strings. It has no awareness
of `AgentDefaultsConfig`, no `ResolvedAgentConfig` production, and no concept of
runtime-type selection.

### 1.5.2 Restructured AgentProxyFactory

```java
package lyjew.com.lyclaw.react;

import java.lang.reflect.Proxy;
import java.util.List;

import lyjew.com.lyclaw.annotation.Agent;
import lyjew.com.lyclaw.chat.ChatFacade;
import lyjew.com.lyclaw.config.AgentDefaultsConfig;
import lyjew.com.lyclaw.config.AgentConfigResolver;
import lyjew.com.lyclaw.config.ResolvedAgentConfig;
import lyjew.com.lyclaw.pipeline.ReactivePipelineStage;
import lyjew.com.lyclaw.tool.ToolRegistry;

/**
 * Agent proxy factory — creates JDK dynamic proxies for @Agent interfaces.
 *
 * <h3>Phase 1 changes:</h3>
 * <ul>
 *   <li>Accepts {@link AgentDefaultsConfig} in constructor</li>
 *   <li>{@code create(Class)} reads @Agent annotation → resolves against defaults
 *       → produces {@link ResolvedAgentConfig}</li>
 *   <li>Passes ResolvedAgentConfig to AgentInvocationHandler</li>
 *   <li>Supports creating agent proxies with different runtime types
 *       (EMBEDDED vs ACP)</li>
 * </ul>
 */
public class AgentProxyFactory {

    private final ChatFacade chatFacade;
    private final ReActEngine reActEngine;
    private final ToolRegistry toolRegistry;
    private final AgentConfigResolver configResolver;
    private final String defaultSystemPrompt;
    private final List<AgentHook> hooks;
    private final List<ReactivePipelineStage> stages;
    private final HookRegistry hookRegistry;

    /**
     * Primary constructor — accepts the full dependency set.
     *
     * @param chatFacade        Chat facade for LLM calls
     * @param reActEngine       ReAct engine for EMBEDDED runtime
     * @param toolRegistry      Tool registry
     * @param configResolver    Agent config resolver (with defaults loaded)
     * @param defaultSystemPrompt Fallback system prompt when none is specified
     * @param hooks             Global agent hooks (applied to all agents)
     * @param stages            Pipeline stages
     * @param hookRegistry      Hook registry for new-style hook dispatch
     */
    public AgentProxyFactory(ChatFacade chatFacade, ReActEngine reActEngine,
                             ToolRegistry toolRegistry,
                             AgentConfigResolver configResolver,
                             String defaultSystemPrompt,
                             List<AgentHook> hooks,
                             List<ReactivePipelineStage> stages,
                             HookRegistry hookRegistry) {
        this.chatFacade = chatFacade;
        this.reActEngine = reActEngine;
        this.toolRegistry = toolRegistry;
        this.configResolver = configResolver;
        this.defaultSystemPrompt = defaultSystemPrompt;
        this.hooks = hooks != null ? List.copyOf(hooks) : List.of();
        this.stages = stages != null ? List.copyOf(stages) : List.of();
        this.hookRegistry = hookRegistry;
    }

    /**
     * Backward-compatible constructor — no standalone config resolver.
     * Creates an inline resolver from the provided defaults.
     */
    public AgentProxyFactory(ChatFacade chatFacade, ReActEngine reActEngine,
                             ToolRegistry toolRegistry,
                             AgentDefaultsConfig defaults,
                             String defaultSystemPrompt,
                             List<AgentHook> hooks,
                             List<ReactivePipelineStage> stages) {
        this(chatFacade, reActEngine, toolRegistry,
                new AgentConfigResolver(defaults),
                defaultSystemPrompt, hooks, stages, new HookRegistry());
    }

    /**
     * Minimal backward-compatible constructor (no defaults, no hooks, no stages).
     */
    public AgentProxyFactory(ChatFacade chatFacade, ReActEngine reActEngine,
                             ToolRegistry toolRegistry) {
        this(chatFacade, reActEngine, toolRegistry,
                new AgentDefaultsConfig(), null, List.of(), List.of());
    }

    /**
     * Create a dynamic proxy for the given @Agent interface.
     *
     * <p>Resolution flow:
     * <ol>
     *   <li>Read @Agent annotation from the interface</li>
     *   <li>Extract agentId, model, provider from annotation</li>
     *   <li>Register agent with configResolver (if not already)</li>
     *   <li>resolveAgentConfig(agentId) → ResolvedAgentConfig</li>
     *   <li>Use resolved model/provider (annotation overrides defaults)</li>
     *   <li>Determine runtimeType from resolved config or system property</li>
     *   <li>Build AgentInvocationHandler with ResolvedAgentConfig</li>
     *   <li>Return proxy</li>
     * </ol>
     */
    @SuppressWarnings("unchecked")
    public <T> T create(Class<T> agentInterface) {
        if (chatFacade == null) {
            throw new IllegalStateException("ChatFacade must not be null");
        }

        Agent ann = agentInterface.getAnnotation(Agent.class);

        String agentId = resolveAgentId(agentInterface, ann);

        // Resolve system prompt: annotation override > default
        String systemPrompt = defaultSystemPrompt;
        if (ann != null && !ann.description().isEmpty() && defaultSystemPrompt == null) {
            systemPrompt = ann.description();
        }
        if (ann != null && !ann.systemPromptOverride().isEmpty()) {
            systemPrompt = ann.systemPromptOverride();
        }

        // Register agent with config resolver and resolve full config
        if (ann != null) {
            configResolver.registerAgent(agentId, ann);
        }
        ResolvedAgentConfig resolvedConfig = configResolver.resolveAgentConfig(agentId);

        // Model/provider: annotation overrides defaults
        String model = resolvedConfig.getModel();
        String provider = resolvedConfig.getProvider();

        // Determine runtime type
        AgentContext.AgentRuntimeType runtimeType = resolveRuntimeType(resolvedConfig);

        AgentInvocationHandler handler = new AgentInvocationHandler(
                chatFacade, reActEngine, toolRegistry,
                systemPrompt, model, provider,
                hooks, stages, resolvedConfig, hookRegistry, runtimeType);

        return (T) Proxy.newProxyInstance(
                agentInterface.getClassLoader(),
                new Class<?>[]{agentInterface},
                handler);
    }

    /**
     * Create a proxy with explicit runtime type override.
     */
    @SuppressWarnings("unchecked")
    public <T> T create(Class<T> agentInterface, AgentContext.AgentRuntimeType runtimeType) {
        T proxy = create(agentInterface);
        // The handler stores the runtimeType; we could also pass it through
        // a setter on the handler after creation
        return proxy;
    }

    // ===== Private helpers =====

    private String resolveAgentId(Class<?> agentInterface, Agent ann) {
        if (ann != null && !ann.id().isEmpty()) {
            return ann.id();
        }
        if (ann != null && !ann.name().isEmpty()) {
            return ann.name();
        }
        String simpleName = agentInterface.getSimpleName();
        return Character.toLowerCase(simpleName.charAt(0)) + simpleName.substring(1);
    }

    private AgentContext.AgentRuntimeType resolveRuntimeType(ResolvedAgentConfig config) {
        // Check system property override
        String sysProp = System.getProperty("lyclaw.agent.runtime");
        if ("acp".equalsIgnoreCase(sysProp)) {
            return AgentContext.AgentRuntimeType.ACP;
        }
        // Check config extension
        String extVal = config.getExtensions().get("runtimeType");
        if ("acp".equalsIgnoreCase(extVal)) {
            return AgentContext.AgentRuntimeType.ACP;
        }
        return AgentContext.AgentRuntimeType.EMBEDDED;
    }
}
```

### 1.5.3 Updated AgentInterfaceProcessor (FactoryBean)

The `AgentProxyFactoryBean` inner class in `AgentInterfaceProcessor` needs a minor
update to resolve the `AgentProxyFactory` bean and call the new `create()` signature:

```java
// Inside AgentInterfaceProcessor.AgentProxyFactoryBean:

@Override
public Object getObject() {
    DefaultListableBeanFactory registry =
            (DefaultListableBeanFactory) LazyBeanFactoryHolder.getBeanFactory();
    if (registry == null) {
        throw new IllegalStateException(
                "BeanFactory not available for @Agent proxy: " + agentInterface.getName());
    }
    AgentProxyFactory factory = registry.getBean(AgentProxyFactory.class);

    // Phase 1 change: create() now internally resolves config and passes it to handler
    Object proxy = factory.create(agentInterface);

    String beanName = resolveBeanName();
    registry.destroySingleton(beanName);
    registry.registerSingleton(beanName, proxy);
    return proxy;
}
```

### 1.5.4 Updated Autoconfiguration

```java
package lyjew.com.lyclaw.autoconfigure.autoconfigure;

import java.util.List;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

import lyjew.com.lyclaw.autoconfigure.processor.AgentInterfaceProcessor;
import lyjew.com.lyclaw.chat.ChatFacade;
import lyjew.com.lyclaw.config.AgentDefaultsConfig;
import lyjew.com.lyclaw.config.AgentConfigResolver;
import lyjew.com.lyclaw.pipeline.ReactivePipelineStage;
import lyjew.com.lyclaw.react.AgentHook;
import lyjew.com.lyclaw.react.AgentProxyFactory;
import lyjew.com.lyclaw.react.HookRegistry;
import lyjew.com.lyclaw.react.ReActEngine;
import lyjew.com.lyclaw.tool.ToolRegistry;

@AutoConfiguration
@AutoConfigureAfter({ChatAutoConfiguration.class, ReActAutoConfiguration.class, ToolAutoConfiguration.class})
@ConditionalOnClass({ReActEngine.class, ToolRegistry.class, ChatFacade.class})
@EnableConfigurationProperties(AgentDefaultsConfig.class)
public class AgentProxyAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(AgentConfigResolver.class)
    public AgentConfigResolver agentConfigResolver(AgentDefaultsConfig defaults) {
        return new AgentConfigResolver(defaults);
    }

    @Bean
    @ConditionalOnMissingBean(HookRegistry.class)
    public HookRegistry hookRegistry() {
        return new HookRegistry();
    }

    @Bean
    @ConditionalOnMissingBean(AgentProxyFactory.class)
    public AgentProxyFactory agentProxyFactory(
            ChatFacade chatFacade,
            ReActEngine reActEngine,
            ToolRegistry toolRegistry,
            AgentConfigResolver configResolver,
            HookRegistry hookRegistry,
            List<AgentHook> hooks,
            List<ReactivePipelineStage> stages) {
        List<AgentHook> hookList = hooks != null ? hooks : List.of();
        List<ReactivePipelineStage> pipelineStages = stages != null ? stages : List.of();
        return new AgentProxyFactory(chatFacade, reActEngine, toolRegistry,
                configResolver, null, hookList, pipelineStages, hookRegistry);
    }

    @Bean
    @ConditionalOnMissingBean(AgentInterfaceProcessor.class)
    public static AgentInterfaceProcessor agentInterfaceProcessor() {
        return new AgentInterfaceProcessor();
    }
}
```

---

## Summary: Phase 1 Deliverables

| # | Component | Change | Impact |
|---|---|---|---|
| 1.1a | `@Agent` annotation | 6 → ~30 fields | Typed, discoverable agent config |
| 1.1b | `AgentDefaultsConfig` | New class | Global defaults from `application.yml` |
| 1.1c | `AgentSystemDefaults` | New class | Hard-coded fallback constants |
| 1.1d | `ResolvedAgentConfig` | New immutable class | Output of 3-layer deep merge |
| 1.1e | `AgentConfigResolver` | Enhanced | resolveAgentConfig, listAgentIds, workspace dirs |
| 1.2 | `AgentContext` | +15 new fields + enhanced snapshot/restore | Rich runtime data bus |
| 1.3a | `AgentHook` | 5 → 36 methods | Full lifecycle coverage |
| 1.3b | `AgentFinalizeResult` | New class | CONTINUE/REVISE/FINALIZE gate |
| 1.3c | `HookDecision` | New class | PASS/BLOCK with reason + metadata |
| 1.3d | `HookRegistration` | New record | Typed hook registry entry |
| 1.3e | `HookRegistry` | New class | Register, dispatch, unregister hooks |
| 1.4a | `AgentRuntimeType` | New enum | EMBEDDED / ACP |
| 1.4b | `AcpRuntime` | New interface | ensureSession, startTurn, cancel, close |
| 1.4c | `AcpRuntimeHandle/Event/...` | New types | ACP protocol data objects |
| 1.5 | `AgentProxyFactory` | Restructured | Config-aware, runtime-type support |

### Backward Compatibility

- Existing `@Agent` annotation fields (`name`, `description`, `version`, `model`,
  `provider`, `extensions`) are unchanged — all new fields have sensible defaults.
- Existing `AgentHook` methods are kept as-is — new methods are `default` (no-op).
- `AgentContext` constructor overloads maintain the old signature alongside the new
  one that accepts `ResolvedAgentConfig`.
- `AgentProxyFactory` retains backward-compatible constructors.
- `LyClawAgent.Builder` continues to work for non-Spring environments.

---

# LyClaw Agent Renovation Phase 2: Subagent Delegation System + Model Management Enhancement

## Table of Contents

1. [Background and Analysis](#1-background-and-analysis)
2. [2.1 Subagent Delegation System](#21-subagent-delegation-system)
   - [2.1.1 SubagentConfig](#211-subagentconfig)
   - [2.1.2 SubagentSpawner](#212-subagentspawner)
   - [2.1.3 Built-in delegate_to_agent Tool](#213-built-in-delegate_to_agent-tool)
   - [2.1.4 Delegation Flow](#214-delegation-flow)
   - [2.1.5 Subagent Session Management](#215-subagent-session-management)
   - [2.1.6 Concurrency Control](#216-concurrency-control)
   - [2.1.7 AgentContext Enhancements for Subagents](#217-agentcontext-enhancements-for-subagents)
   - [2.1.8 Agent Annotation Enhancements for Subagents](#218-agent-annotation-enhancements-for-subagents)
   - [2.1.9 Subagent Hook System](#219-subagent-hook-system)
   - [2.1.10 Subagent Error Handling & Timeout](#2110-subagent-error-handling--timeout)
   - [2.1.11 Configuration (application.yml)](#2111-configuration-applicationyml)
2. [2.2 Model Management Enhancement](#22-model-management-enhancement)
   - [2.2.1 Model Catalog](#221-model-catalog)
   - [2.2.2 Multi-Model Support in AgentDefaultsConfig](#222-multi-model-support-in-agentdefaultsconfig)
   - [2.2.3 Model Selection and Resolution](#223-model-selection-and-resolution)
   - [2.2.4 Thinking / Reasoning / Verbose Controls](#224-thinking--reasoning--verbose-controls)
   - [2.2.5 Provider Discovery](#225-provider-discovery)
   - [2.2.6 Model Fallback Chain Integration](#226-model-fallback-chain-integration)
   - [2.2.7 SSE Events for Thinking](#227-sse-events-for-thinking)
   - [2.2.8 ChatRequest and ChatModel Enhancements](#228-chatrequest-and-chatmodel-enhancements)
   - [2.2.9 Configuration (application.yml)](#229-configuration-applicationyml)
3. [Integration Points Summary](#3-integration-points-summary)
4. [Migration Path](#4-migration-path)

---

## 1. Background and Analysis

### 1.1 Current Architecture Gap

LyClaw currently has two parallel but disconnected worlds:

**World A — Multi-Agent Infrastructure (standalone, unused in core loop):**
- `AgentCoordinator`, `CollaborationHub`, `ConsensusEngine` — multi-agent orchestration
- `AgentCommProtocol`, `AgentChannel` — inter-agent communication
- `AgentRegistry`, `AgentHandle`, `AgentLifecycle` — agent lifecycle management
- `AgentSpec`, `AgentState`, `AgentTask` — agent description and task model
- `AgentPoolSnapshot`, `AutoScaler`, `ScalingDecision` — pool scaling
- `ExternalAgentAdapter`, `AgentCard`, `TaskStatus` — external agent bridging

These exist under `lyclaw-framework/src/main/java/lyjew/com/lyclaw/agent/` but are **never invoked** from the core agent pipeline. They are standalone abstractions designed for a hypothetical multi-agent world that the actual ReAct engine has no concept of.

**World B — Core Agent Loop (what actually runs):**
- `AgentInvocationHandler` → Stage Pipeline (`ContextBuildStage` → `SecurityCheckStage` → `PlanExecutionStage` → `RespondStage` → `ReflectionStage` → `MetricsStage`)
- `RespondStage` delegates to `ReActEngine.executeStream()` (specifically `DefaultReActEngine`)
- `ReActEngine` loops: LLM call → if tool_calls, execute tools via `ToolExecutor` → feed results back → repeat
- `ToolRegistry` provides tool definitions and execution. No "delegate to another agent" tool exists.

**Model Management (basic):**
- `ChatFacade` (implemented by `DefaultChatFacade`) wraps `ChatModelRegistry` + `ModelRouter`
- `FirstAvailableRouter` — always picks the first model from the first provider. No intelligence.
- Three decorators: `CircuitBreakerChatModel`, `FallbackChatModel`, `RetryChatModel`
- `ChatProperties` — YAML-based config with `defaultProvider`, `defaultModel`, `models` map
- `AgentConfig` — merged config from annotations/yml/DB with `model` and `provider` string fields
- `@Agent` annotation has `model()` and `provider()` string fields
- `ChatRequest` has `thinkingEnabled` (boolean) and `thinkingBudget` (Integer) — very basic
- `ModelCapabilities` — streaming, toolCalling, thinking, vision, promptCaching flags

### 1.2 Phase 2 Goals

1. **Integrate subagent delegation into the core agent loop** — when the LLM decides to delegate, a new agent session is spawned, runs its full pipeline independently, and returns the result as a tool observation to the parent.
2. **Enhance model management** — introduce a model catalog, multi-model support (image, audio, video generation models), thinking/reasoning level controls, provider discovery, and model aliases.

---

## 2.1 Subagent Delegation System

### 2.1.1 SubagentConfig

```java
package lyjew.com.lyclaw.react.subagent;

import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.TimeUnit;

/**
 * Configuration for subagent spawning, merged from:
 * <ol>
 *   <li>Hardcoded defaults (this class's static defaults)</li>
 *   <li>application.yml (lyclaw.subagent.*)</li>
 *   <li>@Agent annotation extensions (e.g., "subagent.maxConcurrent")</li>
 * </ol>
 *
 * <p>Each parent agent carries a SubagentConfig that governs what children
 * it can spawn and how. When spawnSubagent() is called, the child agent's
 * own @Agent annotation config is resolved first, then overlaid by the
 * parent's SubagentConfig for safety limits (maxSpawnDepth, maxConcurrent
 * are always bounded by parent settings).</p>
 */
public class SubagentConfig {

    // ── Delegation mode ──

    /**
     * Delegation mode for this agent:
     * <ul>
     *   <li>"suggest" — the LLM is told it <i>may</i> delegate but is
     *       not required to. The tool definition includes a description
     *       suggesting optional delegation.</li>
     *   <li>"prefer" — the LLM is told it <i>should</i> delegate when
     *       applicable. The tool description and system prompt are
     *       adjusted to encourage delegation.</li>
     * </ul>
     */
    private String delegationMode = "suggest";

    /**
     * List of agent IDs that this parent is allowed to delegate to.
     * A single-element list with "*" means all registered agents.
     * An empty list disables delegation entirely.
     */
    private List<String> allowAgents = new ArrayList<>(List.of("*"));

    // ── Concurrency & depth ──

    /** Maximum concurrent sub-agent runs per parent agent. Default 1 (serial). */
    private int maxConcurrent = 1;

    /**
     * Maximum spawn depth. 1 means a parent can spawn children but children
     * cannot spawn grandchildren (no recursive spawning). 2 means grandchildren
     * are allowed, and so on. The depth is tracked in
     * AgentContext.runMetadata.subagentDepth.
     */
    private int maxSpawnDepth = 1;

    /** Maximum number of active (not yet archived) children per parent agent. */
    private int maxChildrenPerAgent = 5;

    // ── Session lifecycle ──

    /** Auto-archive subagent sessions after this many minutes of inactivity. */
    private int archiveAfterMinutes = 60;

    // ── Model overrides for sub-agents ──

    /**
     * Optional model name to use for sub-agents. If null, the child agent's
     * own configured model (from @Agent annotation or yml) is used.
     */
    private String model;

    /**
     * Optional thinking/reasoning level for sub-agents.
     * Overrides the child agent's own thinking level.
     */
    private String thinking;

    // ── Timeouts ──

    /** Per-subagent run timeout in seconds. Default 300 (5 minutes). */
    private int runTimeoutSeconds = 300;

    /** Timeout for the parent to wait for a sub-agent's first announce (token). */
    private int announceTimeoutMs = 120_000;

    // ── Identity ──

    /**
     * When true, the parent LLM MUST specify a concrete agentId when calling
     * delegate_to_agent. When false, the parent can omit agentId and the
     * system will attempt auto-matching by capability/description.
     */
    private boolean requireAgentId = false;

    // ── Static defaults ──

    public static SubagentConfig defaults() {
        return new SubagentConfig();
    }

    public static Builder builder() {
        return new Builder();
    }

    // ── Getters / Setters ──

    public String getDelegationMode() { return delegationMode; }
    public void setDelegationMode(String delegationMode) { this.delegationMode = delegationMode; }
    public List<String> getAllowAgents() { return allowAgents; }
    public void setAllowAgents(List<String> allowAgents) { this.allowAgents = allowAgents; }
    public int getMaxConcurrent() { return maxConcurrent; }
    public void setMaxConcurrent(int maxConcurrent) { this.maxConcurrent = maxConcurrent; }
    public int getMaxSpawnDepth() { return maxSpawnDepth; }
    public void setMaxSpawnDepth(int maxSpawnDepth) { this.maxSpawnDepth = maxSpawnDepth; }
    public int getMaxChildrenPerAgent() { return maxChildrenPerAgent; }
    public void setMaxChildrenPerAgent(int maxChildrenPerAgent) { this.maxChildrenPerAgent = maxChildrenPerAgent; }
    public int getArchiveAfterMinutes() { return archiveAfterMinutes; }
    public void setArchiveAfterMinutes(int archiveAfterMinutes) { this.archiveAfterMinutes = archiveAfterMinutes; }
    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }
    public String getThinking() { return thinking; }
    public void setThinking(String thinking) { this.thinking = thinking; }
    public int getRunTimeoutSeconds() { return runTimeoutSeconds; }
    public void setRunTimeoutSeconds(int runTimeoutSeconds) { this.runTimeoutSeconds = runTimeoutSeconds; }
    public int getAnnounceTimeoutMs() { return announceTimeoutMs; }
    public void setAnnounceTimeoutMs(int announceTimeoutMs) { this.announceTimeoutMs = announceTimeoutMs; }
    public boolean isRequireAgentId() { return requireAgentId; }
    public void setRequireAgentId(boolean requireAgentId) { this.requireAgentId = requireAgentId; }

    /**
     * Merge another config into this one.  Non-default values from {@code other}
     * overwrite this config's values.  Used to overlay parent config onto child
     * defaults.
     */
    public SubagentConfig merge(SubagentConfig other) {
        if (other == null) return this;
        SubagentConfig merged = new SubagentConfig();
        merged.delegationMode = other.delegationMode != null ? other.delegationMode : this.delegationMode;
        merged.allowAgents = other.allowAgents != null && !other.allowAgents.isEmpty() ? other.allowAgents : this.allowAgents;
        merged.maxConcurrent = other.maxConcurrent > 0 ? other.maxConcurrent : this.maxConcurrent;
        merged.maxSpawnDepth = other.maxSpawnDepth > 0 ? other.maxSpawnDepth : this.maxSpawnDepth;
        merged.maxChildrenPerAgent = other.maxChildrenPerAgent > 0 ? other.maxChildrenPerAgent : this.maxChildrenPerAgent;
        merged.archiveAfterMinutes = other.archiveAfterMinutes > 0 ? other.archiveAfterMinutes : this.archiveAfterMinutes;
        merged.model = other.model != null ? other.model : this.model;
        merged.thinking = other.thinking != null ? other.thinking : this.thinking;
        merged.runTimeoutSeconds = other.runTimeoutSeconds > 0 ? other.runTimeoutSeconds : this.runTimeoutSeconds;
        merged.announceTimeoutMs = other.announceTimeoutMs > 0 ? other.announceTimeoutMs : this.announceTimeoutMs;
        merged.requireAgentId = other.requireAgentId;
        return merged;
    }

    // ── Builder ──

    public static class Builder {
        private final SubagentConfig config = new SubagentConfig();

        public Builder delegationMode(String mode) { config.delegationMode = mode; return this; }
        public Builder allowAgents(List<String> agents) { config.allowAgents = agents; return this; }
        public Builder allowAllAgents() { config.allowAgents = List.of("*"); return this; }
        public Builder maxConcurrent(int n) { config.maxConcurrent = n; return this; }
        public Builder maxSpawnDepth(int n) { config.maxSpawnDepth = n; return this; }
        public Builder maxChildrenPerAgent(int n) { config.maxChildrenPerAgent = n; return this; }
        public Builder archiveAfterMinutes(int m) { config.archiveAfterMinutes = m; return this; }
        public Builder model(String model) { config.model = model; return this; }
        public Builder thinking(String thinking) { config.thinking = thinking; return this; }
        public Builder runTimeoutSeconds(int s) { config.runTimeoutSeconds = s; return this; }
        public Builder announceTimeoutMs(int ms) { config.announceTimeoutMs = ms; return this; }
        public Builder requireAgentId(boolean v) { config.requireAgentId = v; return this; }
        public SubagentConfig build() { return config; }
    }
}
```

### 2.1.2 SubagentSpawner

This is the central orchestrator for spawning and running sub-agents. It is injected into the `ToolRegistry` (or a new `ToolProvider`) so that when the LLM invokes the `delegate_to_agent` tool, execution routes through this class.

```java
package lyjew.com.lyclaw.react.subagent;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;

import lyjew.com.lyclaw.agent.AgentRegistry;
import lyjew.com.lyclaw.chat.ChatFacade;
import lyjew.com.lyclaw.config.AgentConfig;
import lyjew.com.lyclaw.config.AgentConfigResolver;
import lyjew.com.lyclaw.model.ChatRequest;
import lyjew.com.lyclaw.model.Message;
import lyjew.com.lyclaw.model.Session;
import lyjew.com.lyclaw.model.ToolDefinition;
import lyjew.com.lyclaw.pipeline.ReactivePipelineStage;
import lyjew.com.lyclaw.react.AgentContext;
import lyjew.com.lyclaw.react.AgentHook;
import lyjew.com.lyclaw.react.AgentInvocationHandler;
import lyjew.com.lyclaw.react.ReActEngine;
import lyjew.com.lyclaw.react.ToolExecutor;
import lyjew.com.lyclaw.tool.ToolRegistry;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Central orchestrator for spawning and managing sub-agent executions.
 *
 * <h3>Lifecycle</h3>
 * <ol>
 *   <li>LLM invokes {@code delegate_to_agent} tool → tool executor calls
 *       {@link #spawnSubagent(String, String, Map, AgentContext)}</li>
 *   <li>Validation: check allowAgents whitelist, depth limit, child count limit</li>
 *   <li>Resolve child agent config from AgentConfigResolver</li>
 *   <li>Build isolated AgentContext for the child</li>
 *   <li>Dispatch {@code subagentSpawning} hooks</li>
 *   <li>Run child's full pipeline (ContextBuild → ... → Metrics)</li>
 *   <li>Dispatch {@code subagentSpawned} and {@code subagentEnded} hooks</li>
 *   <li>Return {@link SubagentResult} to parent as tool observation</li>
 * </ol>
 *
 * <h3>Concurrency Model</h3>
 * <p>Each parent agent has a Semaphore(maxConcurrent) to limit concurrent
 * sub-agent runs. The depth is tracked in parent's
 * {@code ctx.runMetadata.subagentDepth}. Active children are tracked in
 * {@code ctx.runMetadata.activeSubagentIds}.</p>
 *
 * @see SubagentConfig
 * @see SubagentResult
 */
public class SubagentSpawner {

    private static final Logger log = LoggerFactory.getLogger(SubagentSpawner.class);

    private final ChatFacade chatFacade;
    private final ReActEngine reActEngine;
    private final ToolRegistry toolRegistry;
    private final AgentRegistry agentRegistry;
    private final AgentConfigResolver agentConfigResolver;
    private final List<ReactivePipelineStage> defaultStages;
    private final List<AgentHook> defaultHooks;

    /**
     * Per-parent-agent semaphore map for concurrency control.
     * Key = parent sessionKey.
     */
    private final Map<String, Semaphore> concurrencySemaphores = new ConcurrentHashMap<>();

    public SubagentSpawner(ChatFacade chatFacade, ReActEngine reActEngine,
                           ToolRegistry toolRegistry, AgentRegistry agentRegistry,
                           AgentConfigResolver agentConfigResolver,
                           List<ReactivePipelineStage> defaultStages,
                           List<AgentHook> defaultHooks) {
        this.chatFacade = chatFacade;
        this.reActEngine = reActEngine;
        this.toolRegistry = toolRegistry;
        this.agentRegistry = agentRegistry;
        this.agentConfigResolver = agentConfigResolver;
        this.defaultStages = defaultStages != null ? List.copyOf(defaultStages) : List.of();
        this.defaultHooks = defaultHooks != null ? List.copyOf(defaultHooks) : List.of();
    }

    /**
     * Spawn a sub-agent to execute the given task.
     *
     * <p>This method is typically called from the tool executor that backs the
     * {@code delegate_to_agent} built-in tool.</p>
     *
     * @param targetAgentId the agent ID to delegate to (may be null if
     *        requireAgentId is false and auto-matching is enabled)
     * @param task the natural-language task description for the sub-agent
     * @param options additional options from the tool call (e.g., mode override)
     * @param parentCtx the parent agent's context
     * @return a Mono that completes with the subagent's result
     */
    public Mono<SubagentResult> spawnSubagent(String targetAgentId, String task,
                                               Map<String, Object> options,
                                               AgentContext parentCtx) {
        Instant startTime = Instant.now();
        String parentSessionKey = parentCtx.getSessionId();

        // ── 1. Resolve parent's SubagentConfig ──
        SubagentConfig parentConfig = resolveSubagentConfig(parentCtx);

        // ── 2. Validate confinements ──
        // 2a. Check delegation is enabled (non-empty allowAgents)
        if (parentConfig.getAllowAgents().isEmpty()) {
            return Mono.just(SubagentResult.error("Delegation is disabled for this agent"));
        }

        // 2b. Check allowAgents whitelist
        if (!parentConfig.getAllowAgents().contains("*")
                && !parentConfig.getAllowAgents().contains(targetAgentId)) {
            return Mono.just(SubagentResult.error(
                    "Agent '" + targetAgentId + "' is not in the allowed delegation list. "
                    + "Allowed: " + parentConfig.getAllowAgents()));
        }

        // 2c. Check maxSpawnDepth
        int parentDepth = parentCtx.getRunMetadata().getSubagentDepth();
        if (parentDepth + 1 > parentConfig.getMaxSpawnDepth()) {
            return Mono.just(SubagentResult.error(
                    "Max spawn depth exceeded. Current depth: " + parentDepth
                    + ", max: " + parentConfig.getMaxSpawnDepth()));
        }

        // 2d. Check maxChildrenPerAgent
        Set<String> activeChildren = parentCtx.getRunMetadata().getActiveSubagentIds();
        if (activeChildren.size() >= parentConfig.getMaxChildrenPerAgent()) {
            return Mono.just(SubagentResult.error(
                    "Max children per agent exceeded. Active: " + activeChildren.size()
                    + ", max: " + parentConfig.getMaxChildrenPerAgent()));
        }

        // 2e. Concurrency semaphore
        Semaphore semaphore = concurrencySemaphores.computeIfAbsent(
                parentSessionKey, k -> new Semaphore(parentConfig.getMaxConcurrent()));

        return Mono.fromCallable(() -> {
            if (!semaphore.tryAcquire()) {
                return SubagentResult.error(
                        "Max concurrent sub-agents reached (" + parentConfig.getMaxConcurrent() + ")");
            }
            return null; // acquired, continue
        })
        .subscribeOn(Schedulers.boundedElastic())
        .flatMap(earlyError -> {
            if (earlyError != null) {
                return Mono.just(earlyError);
            }
            try {
                return runSubagent(targetAgentId, task, options, parentCtx, parentConfig, startTime);
            } catch (Exception e) {
                semaphore.release();
                return Mono.just(SubagentResult.error("Subagent launch failed: " + e.getMessage()));
            }
        })
        .doFinally(signalType -> {
            // Always release the semaphore when done
            semaphore.release();
        });
    }

    /**
     * Core execution: build isolated AgentContext, run full pipeline, return result.
     */
    private Mono<SubagentResult> runSubagent(String targetAgentId, String task,
                                              Map<String, Object> options,
                                              AgentContext parentCtx,
                                              SubagentConfig parentConfig,
                                              Instant startTime) {
        String childAgentId = targetAgentId;
        String childSessionKey = parentCtx.getSessionId()
                + "/subagent/" + childAgentId + "/" + UUID.randomUUID().toString().substring(0, 8);

        // ── 3. Resolve child agent config ──
        AgentConfig childAgentConfig = agentConfigResolver.resolve(childAgentId);
        if (childAgentConfig.getName() == null) {
            return Mono.just(SubagentResult.error("Unknown agent: " + childAgentId));
        }

        // ── 4. Build isolated AgentContext for child ──
        // The child gets its own toolRegistry subset, session, and pipeline
        AgentContext childCtx = buildChildContext(childSessionKey, task, childAgentConfig, parentCtx);

        // Set subagent depth in run metadata
        childCtx.getRunMetadata().setSubagentDepth(
                parentCtx.getRunMetadata().getSubagentDepth() + 1);
        childCtx.getRunMetadata().setParentSessionKey(parentCtx.getSessionId());
        childCtx.getRunMetadata().setSubagentTargetAgentId(childAgentId);

        // Track in parent's active subagent set
        parentCtx.getRunMetadata().getActiveSubagentIds().add(childSessionKey);

        // ── 5. Dispatch subagentSpawning hooks ──
        dispatchHooks("subagentSpawning", childCtx, null);

        // ── 6. Run child's pipeline ──
        // Build a lightweight AgentInvocationHandler for the child.
        // The child runs the same pipeline stages but with its own context.
        AgentInvocationHandler childHandler = new AgentInvocationHandler(
                chatFacade, reActEngine, toolRegistry,
                childAgentConfig.getDescription(), // system prompt
                childAgentConfig.getModel(),
                childAgentConfig.getProvider(),
                defaultHooks,
                defaultStages
        );

        return Mono.fromCallable(() -> {
            try {
                // Execute the child's pipeline in blocking mode and collect result
                String result = childHandler.executeBlocking(childCtx);
                Duration elapsed = Duration.between(startTime, Instant.now());

                // ── 7. Build SubagentResult ──
                SubagentResult subagentResult = SubagentResult.success(
                        childSessionKey, childAgentId, result, elapsed.toMillis(),
                        childCtx.getSuccessCount().get(), childCtx.getFailCount().get());

                // ── 8. Dispatch subagentSpawned / subagentEnded hooks ──
                dispatchHooks("subagentSpawned", childCtx, subagentResult);
                dispatchHooks("subagentEnded", childCtx, subagentResult);

                return subagentResult;
            } catch (Exception e) {
                log.error("Subagent '{}' execution failed: {}", childAgentId, e.getMessage(), e);
                Duration elapsed = Duration.between(startTime, Instant.now());
                return SubagentResult.error("Subagent execution failed: " + e.getMessage());
            } finally {
                // Remove from active set
                parentCtx.getRunMetadata().getActiveSubagentIds().remove(childSessionKey);
                // Archive session if configured
                scheduleSessionArchive(childSessionKey, parentConfig.getArchiveAfterMinutes());
            }
        })
        .subscribeOn(Schedulers.boundedElastic())
        .timeout(Duration.ofSeconds(parentConfig.getRunTimeoutSeconds()),
                 Mono.just(SubagentResult.error(
                         "Subagent timed out after " + parentConfig.getRunTimeoutSeconds() + "s")),
                 Schedulers.boundedElastic());
    }

    /**
     * Build an isolated AgentContext for the child subagent.
     */
    private AgentContext buildChildContext(String sessionKey, String task,
                                            AgentConfig childConfig,
                                            AgentContext parentCtx) {
        // The child gets a distinct sessionId and userMessage = the task.
        // The system prompt comes from the child's agent description.
        AgentContext childCtx = AgentContext.sessionScoped(
                sessionKey,
                task,  // user message = the delegating task
                childConfig.getDescription(),  // system prompt from child's @Agent
                toolRegistry,
                parentCtx.getMethod(),  // method is null/placeholder for subagents
                new Object[0]
        );

        // Build a ChatRequest with just the task message
        ChatRequest request = ChatRequest.builder()
                .sessionId(sessionKey)
                .messages(new java.util.ArrayList<>(List.of(Message.user(task))))
                .stream(true)
                .build();

        // If child config has model override, apply it
        if (childConfig.getModel() != null && !childConfig.getModel().isEmpty()) {
            request.setModel(childConfig.getModel());
        }

        // Set tools from the parent's tool registry (or a scoped subset)
        List<ToolDefinition> tools = toolRegistry.getAllDefinitions(request);
        request.setTools(tools);
        request.setToolChoice("auto");

        childCtx.setChatRequest(request);
        childCtx.setSandboxLevel(parentCtx.getSandboxLevel());

        // Set thinking level from child config
        String thinkingLevel = childConfig.getExtension("thinking.level", null);
        if (thinkingLevel != null) {
            childCtx.getRunMetadata().setThinkingLevel(thinkingLevel);
        }

        return childCtx;
    }

    /**
     * Resolve SubagentConfig from parent AgentContext.
     * Priority: AgentConfig extensions > application.yml > hardcoded defaults.
     */
    private SubagentConfig resolveSubagentConfig(AgentContext ctx) {
        SubagentConfig config = SubagentConfig.defaults();

        // Overlay from AgentContext attributes (set by AgentInvocationHandler
        // after resolving @Agent annotation extensions)
        @SuppressWarnings("unchecked")
        Map<String, String> extensions = ctx.getAttribute("agentExtensions");
        if (extensions != null) {
            if (extensions.containsKey("subagent.delegationMode"))
                config.setDelegationMode(extensions.get("subagent.delegationMode"));
            if (extensions.containsKey("subagent.allowAgents"))
                config.setAllowAgents(List.of(extensions.get("subagent.allowAgents").split(",")));
            if (extensions.containsKey("subagent.maxConcurrent"))
                config.setMaxConcurrent(Integer.parseInt(extensions.get("subagent.maxConcurrent")));
            if (extensions.containsKey("subagent.maxSpawnDepth"))
                config.setMaxSpawnDepth(Integer.parseInt(extensions.get("subagent.maxSpawnDepth")));
            if (extensions.containsKey("subagent.maxChildrenPerAgent"))
                config.setMaxChildrenPerAgent(Integer.parseInt(extensions.get("subagent.maxChildrenPerAgent")));
            if (extensions.containsKey("subagent.archiveAfterMinutes"))
                config.setArchiveAfterMinutes(Integer.parseInt(extensions.get("subagent.archiveAfterMinutes")));
            if (extensions.containsKey("subagent.model"))
                config.setModel(extensions.get("subagent.model"));
            if (extensions.containsKey("subagent.thinking"))
                config.setThinking(extensions.get("subagent.thinking"));
            if (extensions.containsKey("subagent.runTimeoutSeconds"))
                config.setRunTimeoutSeconds(Integer.parseInt(extensions.get("subagent.runTimeoutSeconds")));
        }

        return config;
    }

    /**
     * Dispatch lifecycle events to all registered hooks that implement SubagentHook.
     */
    private void dispatchHooks(String lifecycleEvent, AgentContext childCtx,
                                SubagentResult result) {
        for (AgentHook hook : defaultHooks) {
            if (hook instanceof SubagentHook subagentHook) {
                try {
                    switch (lifecycleEvent) {
                        case "subagentSpawning":
                            subagentHook.subagentSpawning(childCtx);
                            break;
                        case "subagentSpawned":
                            subagentHook.subagentSpawned(childCtx, result);
                            break;
                        case "subagentEnded":
                            subagentHook.subagentEnded(childCtx, result);
                            break;
                    }
                } catch (Exception e) {
                    log.warn("SubagentHook '{}' threw on {}: {}",
                            hook.getClass().getSimpleName(), lifecycleEvent, e.getMessage());
                }
            }
        }
    }

    private void scheduleSessionArchive(String sessionKey, int afterMinutes) {
        // Delegate to the session store to archive this session after inactivity.
        // Implementation: register a delayed task that checks if the session is
        // still active and, if not, moves it to cold storage.
        log.debug("Scheduled archive for subagent session {} after {} minutes",
                sessionKey, afterMinutes);
    }

    /**
     * Returns the tool definition for the built-in delegate_to_agent tool.
     * This is registered automatically by the framework.
     */
    public static ToolDefinition buildDelegateToolDefinition(SubagentConfig config) {
        // Build JSON Schema programmatically
        Map<String, Object> properties = new java.util.LinkedHashMap<>();

        // agentId parameter
        Map<String, Object> agentIdSchema = new java.util.LinkedHashMap<>();
        agentIdSchema.put("type", "string");
        agentIdSchema.put("description", "The ID of the specialized agent to delegate to");
        properties.put("agentId", agentIdSchema);

        // task parameter
        Map<String, Object> taskSchema = new java.util.LinkedHashMap<>();
        taskSchema.put("type", "string");
        taskSchema.put("description", "The detailed task description for the sub-agent");
        properties.put("task", taskSchema);

        // mode parameter (optional override)
        Map<String, Object> modeSchema = new java.util.LinkedHashMap<>();
        modeSchema.put("type", "string");
        modeSchema.put("enum", List.of("suggest", "prefer"));
        modeSchema.put("description", "Delegation mode override for this call");
        properties.put("mode", modeSchema);

        // Build full parameter schema
        Map<String, Object> parameters = new java.util.LinkedHashMap<>();
        parameters.put("type", "object");
        parameters.put("properties", properties);

        // Required fields depend on config
        List<String> required = new java.util.ArrayList<>();
        required.add("task");
        if (config.isRequireAgentId()) {
            required.add("agentId");
        }
        parameters.put("required", required);

        // Build function definition
        Map<String, Object> function = new java.util.LinkedHashMap<>();
        function.put("name", "delegate_to_agent");
        function.put("description",
                config.getDelegationMode().equals("prefer")
                        ? "Delegate a task to another specialized agent. "
                          + "You SHOULD use this whenever another agent specializes in the task."
                        : "Delegate a task to another specialized agent. "
                          + "You may use this when another agent specializes in the task.");
        function.put("parameters", parameters);

        return ToolDefinition.builder()
                .name("delegate_to_agent")
                .type("function")
                .function(function)
                .build();
    }
}
```

### 2.1.3 Built-in delegate_to_agent Tool

The `delegate_to_agent` tool is registered as a built-in tool via a `ToolProvider`, not a static `@Tool` annotation, because it needs runtime access to the `AgentContext` (which static tools don't have).

```java
package lyjew.com.lyclaw.react.subagent;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import lyjew.com.lyclaw.model.ChatRequest;
import lyjew.com.lyclaw.model.ToolCall;
import lyjew.com.lyclaw.model.ToolDefinition;
import lyjew.com.lyclaw.react.AgentContext;
import lyjew.com.lyclaw.tool.ToolExecutionResult;
import lyjew.com.lyclaw.tool.ToolProvider;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

/**
 * ToolProvider that injects the built-in {@code delegate_to_agent} tool into
 * every agent's tool set. This is how the LLM discovers subagent delegation.
 *
 * <p>When the LLM calls this tool, execution routes through the
 * {@link SubagentSpawner}, which spawns a new agent session, runs it to
 * completion, and returns the result as the tool's output.</p>
 */
public class DelegateToAgentToolProvider implements ToolProvider {

    private static final Logger log = LoggerFactory.getLogger(DelegateToAgentToolProvider.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();

    private final SubagentSpawner spawner;
    private final boolean enabled;

    public DelegateToAgentToolProvider(SubagentSpawner spawner, boolean enabled) {
        this.spawner = spawner;
        this.enabled = enabled;
    }

    @Override
    public boolean isEnabled(ChatRequest request) {
        return enabled;
    }

    @Override
    public List<ToolDefinition> getDefinitions(ChatRequest request) {
        if (!enabled) return List.of();
        // Build the tool definition dynamically based on the parent agent's config
        SubagentConfig config = SubagentConfig.defaults(); // will be resolved from context at runtime
        return List.of(SubagentSpawner.buildDelegateToolDefinition(config));
    }

    @Override
    public ToolExecutionResult execute(ToolCall toolCall, ChatRequest request, Object context) {
        if (!"delegate_to_agent".equals(toolCall.getName())) {
            return ToolExecutionResult.error("Unknown tool: " + toolCall.getName());
        }

        if (!(context instanceof ToolProviderContext ctx)) {
            return ToolExecutionResult.error("Missing ToolProviderContext");
        }

        AgentContext agentCtx = ctx.getAgentContext();

        // Parse arguments
        Map<String, Object> args;
        try {
            if (toolCall.getArguments() instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> m = (Map<String, Object>) toolCall.getArguments();
                args = m;
            } else {
                String argsStr = toolCall.getArguments() != null
                        ? toolCall.getArguments().toString() : "{}";
                args = objectMapper.readValue(argsStr,
                        new TypeReference<Map<String, Object>>() {});
            }
        } catch (Exception e) {
            return ToolExecutionResult.error("Failed to parse delegate_to_agent arguments: " + e.getMessage());
        }

        String targetAgentId = (String) args.getOrDefault("agentId", "");
        String task = (String) args.get("task");
        if (task == null || task.isEmpty()) {
            return ToolExecutionResult.error("Task is required for delegate_to_agent");
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> options = (Map<String, Object>) args.getOrDefault("options", Map.of());

        // Execute synchronously (blocking) because tool execution is synchronous
        // in the current ReAct loop. The spawner internally uses reactive types
        // but we block here for compatibility.
        try {
            SubagentResult result = spawner.spawnSubagent(targetAgentId, task, options, agentCtx)
                    .block(java.time.Duration.ofSeconds(spawner.resolveSubagentConfig(agentCtx).getRunTimeoutSeconds()));

            if (result == null) {
                return ToolExecutionResult.error("Subagent returned null (possible timeout)");
            }

            String output = formatSubagentOutput(result);
            return ToolExecutionResult.success(output);
        } catch (Exception e) {
            log.error("delegate_to_agent execution failed: {}", e.getMessage(), e);
            return ToolExecutionResult.error("Subagent delegation failed: " + e.getMessage());
        }
    }

    private String formatSubagentOutput(SubagentResult result) {
        StringBuilder sb = new StringBuilder();
        if (result.isSuccess()) {
            sb.append("## Subagent Result (success)\n\n");
            sb.append("**Agent:** ").append(result.getAgentId()).append("\n");
            sb.append("**Duration:** ").append(result.getDurationMs()).append("ms\n");
            sb.append("**Tools:** ").append(result.getSuccessTools())
              .append(" succeeded, ").append(result.getFailedTools()).append(" failed\n\n");
            sb.append("### Output\n\n").append(result.getOutput());
        } else {
            sb.append("## Subagent Result (failed)\n\n");
            sb.append("**Agent:** ").append(result.getAgentId()).append("\n");
            sb.append("**Error:** ").append(result.getError()).append("\n");
        }
        return sb.toString();
    }
}
```

### 2.1.4 Delegation Flow

The complete flow, step by step:

```
┌──────────────────────────────────────────────────────────────────┐
│ PARENT AGENT: AgentInvocationHandler                             │
│   Stage Pipeline: ContextBuild → SecurityCheck → PlanExecution   │
│   → RespondStage → ReflectionStage → MetricsStage                │
│                                                                  │
│ RespondStage:                                                    │
│   ├─ ReActEngine.executeStream(chatFacade, request, toolExecutor)│
│   │                                                              │
│   │   ┌─ LLM Call (with tools including "delegate_to_agent")     │
│   │   │                                                          │
│   │   │   LLM decides: "I should delegate this code review to    │
│   │   │   the code-reviewer agent."                              │
│   │   │                                                          │
│   │   │   → toolCall: delegate_to_agent(                        │
│   │   │       agentId="code-reviewer",                           │
│   │   │       task="Review the changes in PR #342...",           │
│   │   │       mode="suggest"                                     │
│   │   │     )                                                    │
│   │   │                                                          │
│   │   ├─ ToolExecutor.execute("delegate_to_agent", ...)          │
│   │   │                                                          │
│   │   │   ┌───────────────────────────────────────────────────┐ │
│   │   │   │ SubagentSpawner.spawnSubagent()                   │ │
│   │   │   │                                                   │ │
│   │   │   │   1. Validate allowAgents whitelist               │ │
│   │   │   │   2. Check maxSpawnDepth (parent depth + 1 < max) │ │
│   │   │   │   3. Check maxChildrenPerAgent                     │ │
│   │   │   │   4. Acquire concurrency semaphore                 │ │
│   │   │   │   5. Resolve child AgentConfig                     │ │
│   │   │   │   6. Build isolated AgentContext for child         │ │
│   │   │   │   7. Dispatch subagentSpawning hooks               │ │
│   │   │   │   8. Run child's full pipeline:                    │ │
│   │   │   │      ContextBuild → SecurityCheck →                │ │
│   │   │   │      PlanExecution → Respond(ReAct) →              │ │
│   │   │   │      Reflection → Metrics                          │ │
│   │   │   │   9. Dispatch subagentSpawned, subagentEnded       │ │
│   │   │   │  10. Release semaphore                             │ │
│   │   │   │  11. Return SubagentResult                         │ │
│   │   │   └───────────────────────────────────────────────────┘ │
│   │   │                                                          │
│   │   ├─ Tool result returned as observation to parent LLM       │
│   │   │                                                          │
│   │   └─ Parent LLM continues with the subagent's result         │
│   │       and produces final reply                               │
│   │                                                              │
│   └─ Final SSE events to client                                  │
└──────────────────────────────────────────────────────────────────┘
```

### 2.1.5 Subagent Session Management

Subagent sessions follow a hierarchical session key scheme:

```
Parent session key:  "abc12345"
Child session key:   "abc12345/subagent/code-reviewer/a1b2c3d4"
Grandchild key:      "abc12345/subagent/code-reviewer/a1b2c3d4/subagent/tester/e5f6g7h8"
```

This enables:
- **Hierarchical tracing**: any subagent's output can be traced back to the root session
- **Auto-archiving**: the session store can archive all sessions under a parent key when the parent is archived
- **Cleanup cascading**: terminating a parent session can terminate all descendant subagent sessions

```java
package lyjew.com.lyclaw.react.subagent;

import java.util.List;

import lyjew.com.lyclaw.model.Session;

/**
 * Session management for subagent runs.
 *
 * <p>Each subagent run creates a new {@link Session} with a hierarchical
 * sessionKey (parentKey + "/subagent/" + agentId + "/" + uuidFragment).
 * Sessions are stored in the same session store as the parent.</p>
 */
public class SubagentSessionManager {

    private final lyjew.com.lyclaw.persistence.SessionStore sessionStore;

    public SubagentSessionManager(lyjew.com.lyclaw.persistence.SessionStore sessionStore) {
        this.sessionStore = sessionStore;
    }

    /**
     * Create a new subagent session under the given parent session key.
     */
    public Session createSubagentSession(String parentSessionKey, String agentId,
                                          String systemPrompt) {
        String sessionId = parentSessionKey + "/subagent/" + agentId
                + "/" + java.util.UUID.randomUUID().toString().substring(0, 8);

        Session session = Session.builder()
                .sessionId(sessionId)
                .name("subagent:" + agentId)
                .model(null)  // resolved later from AgentConfig
                .build();

        sessionStore.save(session);
        return session;
    }

    /**
     * Archive a subagent session and all its descendant sessions.
     */
    public void archiveSession(String sessionKey, int afterMinutes) {
        // Find all sessions whose key starts with sessionKey
        List<Session> descendants = sessionStore.findByPrefix(sessionKey);
        for (Session s : descendants) {
            s.setAttribute("archived", "true");
            s.setAttribute("archivedAt", String.valueOf(System.currentTimeMillis()));
            sessionStore.save(s);
        }
    }

    /**
     * Terminate all active subagent sessions under a parent key.
     * Called when the parent session is terminated or cancelled.
     */
    public void terminateDescendants(String parentSessionKey) {
        List<Session> descendants = sessionStore.findByPrefix(parentSessionKey);
        for (Session s : descendants) {
            if (!"true".equals(s.getAttribute("archived"))) {
                s.setAttribute("terminated", "true");
                s.setAttribute("terminatedAt", String.valueOf(System.currentTimeMillis()));
                sessionStore.save(s);
            }
        }
    }
}
```

### 2.1.6 Concurrency Control

```java
package lyjew.com.lyclaw.react;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Run-time metadata attached to AgentContext for tracking subagent state.
 *
 * <p>This is stored in AgentContext.attributes under the key "runMetadata"
 * but we expose it as a typed class for type safety.</p>
 */
public class RunMetadata {

    /**
     * Depth of this agent in the subagent spawn tree.
     * 0 = root agent (no parent).  1 = directly spawned by root.
     * 2 = spawned by a level-1 subagent, etc.
     */
    private int subagentDepth = 0;

    /**
     * If this is a subagent, the session key of its parent.
     * null for root agents.
     */
    private String parentSessionKey;

    /**
     * If this is a subagent, the agentId it was spawned as.
     * null for root agents.
     */
    private String subagentTargetAgentId;

    /**
     * Set of session keys of currently active sub-agents spawned by this agent.
     * Used to enforce maxChildrenPerAgent.
     */
    private final Set<String> activeSubagentIds = ConcurrentHashMap.newKeySet();

    /**
     * Thinking/reasoning level for model calls in this context.
     * "off" | "low" | "medium" | "high". null means use model default.
     */
    private String thinkingLevel;

    /**
     * Model name override for this context (resolved from AgentConfig + defaults).
     */
    private String resolvedModel;

    /**
     * Provider name override for this context.
     */
    private String resolvedProvider;

    /**
     * The model specifically configured for image understanding.
     */
    private String imageModel;

    /**
     * Session key for the archive store.
     */
    private String archiveSessionKey;


    // ── Constructors ──

    public RunMetadata() {}

    public static RunMetadata root() {
        return new RunMetadata();
    }

    public static RunMetadata childOf(RunMetadata parent, String childAgentId) {
        RunMetadata child = new RunMetadata();
        child.subagentDepth = parent.subagentDepth + 1;
        child.parentSessionKey = null; // set later by spawner
        child.subagentTargetAgentId = childAgentId;
        return child;
    }

    // ── Getters / Setters ──

    public int getSubagentDepth() { return subagentDepth; }
    public void setSubagentDepth(int depth) { this.subagentDepth = depth; }

    public String getParentSessionKey() { return parentSessionKey; }
    public void setParentSessionKey(String key) { this.parentSessionKey = key; }

    public String getSubagentTargetAgentId() { return subagentTargetAgentId; }
    public void setSubagentTargetAgentId(String id) { this.subagentTargetAgentId = id; }

    public Set<String> getActiveSubagentIds() { return activeSubagentIds; }

    public String getThinkingLevel() { return thinkingLevel; }
    public void setThinkingLevel(String level) { this.thinkingLevel = level; }

    public String getResolvedModel() { return resolvedModel; }
    public void setResolvedModel(String model) { this.resolvedModel = model; }

    public String getResolvedProvider() { return resolvedProvider; }
    public void setResolvedProvider(String provider) { this.resolvedProvider = provider; }

    public String getImageModel() { return imageModel; }
    public void setImageModel(String model) { this.imageModel = model; }

    public String getArchiveSessionKey() { return archiveSessionKey; }
    public void setArchiveSessionKey(String key) { this.archiveSessionKey = key; }

    /** Whether this agent is a subagent (has a parent). */
    public boolean isSubagent() {
        return parentSessionKey != null || subagentDepth > 0;
    }

    /** Whether this agent is the root of the spawn tree. */
    public boolean isRoot() {
        return subagentDepth == 0 && parentSessionKey == null;
    }
}
```

### 2.1.7 AgentContext Enhancements for Subagents

The existing `AgentContext` class needs a `RunMetadata` field:

```java
// ── Addition to AgentContext ──

/** Run-time metadata including subagent depth, thinking level, model resolution */
private final RunMetadata runMetadata = new RunMetadata();

public RunMetadata getRunMetadata() { return runMetadata; }


// ── Also add to AgentContext.toSnapshot() ──

public Map<String, Object> toSnapshot() {
    Map<String, Object> snapshot = new HashMap<>();
    // ... existing fields ...
    snapshot.put("subagentDepth", runMetadata.getSubagentDepth());
    snapshot.put("parentSessionKey", runMetadata.getParentSessionKey());
    snapshot.put("thinkingLevel", runMetadata.getThinkingLevel());
    snapshot.put("resolvedModel", runMetadata.getResolvedModel());
    return snapshot;
}


// ── Also add to AgentContext.restoreFromSnapshot() ──

public void restoreFromSnapshot(Map<String, Object> snapshot) {
    if (snapshot == null) return;
    // ... existing fields ...

    if (snapshot.get("subagentDepth") instanceof Number n)
        runMetadata.setSubagentDepth(n.intValue());
    if (snapshot.get("parentSessionKey") instanceof String s)
        runMetadata.setParentSessionKey(s);
    if (snapshot.get("thinkingLevel") instanceof String s)
        runMetadata.setThinkingLevel(s);
    if (snapshot.get("resolvedModel") instanceof String s)
        runMetadata.setResolvedModel(s);
}
```

### 2.1.8 Agent Annotation Enhancements for Subagents

The `@Agent` annotation's `extensions` already supports key-value pairs. We add well-known extension keys for subagent configuration:

```
@Agent(
    name = "chat",
    description = "General-purpose chat assistant",
    extensions = {
        @Extension(key = "subagent.delegationMode", value = "prefer"),
        @Extension(key = "subagent.allowAgents", value = "code-reviewer,tester,data-analyst"),
        @Extension(key = "subagent.maxConcurrent", value = "3"),
        @Extension(key = "subagent.maxSpawnDepth", value = "2"),
        @Extension(key = "subagent.maxChildrenPerAgent", value = "10"),
        @Extension(key = "subagent.requireAgentId", value = "true"),
        @Extension(key = "subagent.model", value = "deepseek-v4-flash"),
        @Extension(key = "subagent.thinking", value = "medium"),
        @Extension(key = "subagent.runTimeoutSeconds", value = "600"),
        @Extension(key = "thinking.level", value = "high"),
        @Extension(key = "model.image", value = "openai/dall-e-3"),
        @Extension(key = "model.pdf", value = "openai/gpt-4o"),
        @Extension(key = "model.videoGeneration", value = "openai/sora"),
    }
)
public interface SuperChatAgent {
    @SystemMessage("You are a coordinating assistant...")
    String chat(@UserMessage String message);
}
```

### 2.1.9 Subagent Hook System

A new sub-interface of `AgentHook` for subagent lifecycle events:

```java
package lyjew.com.lyclaw.react.subagent;

import lyjew.com.lyclaw.react.AgentContext;
import lyjew.com.lyclaw.react.AgentHook;

/**
 * Extended hook SPI for sub-agent lifecycle events.
 *
 * <p>Any AgentHook implementation may also implement this interface to receive
 * subagent-specific lifecycle callbacks. The methods are called in the
 * SubagentSpawner at the appropriate lifecycle points.</p>
 *
 * <p>Execution context: these methods execute on boundedElastic schedulers
 * within the subagent spawner. Throwing an exception logs a warning but
 * does not interrupt the subagent pipeline.</p>
 */
public interface SubagentHook extends AgentHook {

    /**
     * Called before the child agent's pipeline begins execution.
     * The childCtx has been fully prepared (ChatRequest, tools, system prompt set).
     * Modifications to childCtx at this point will affect the child's run.
     *
     * @param childCtx the child agent's context, fully prepared
     */
    default void subagentSpawning(AgentContext childCtx) {}

    /**
     * Called after the child agent's pipeline has completed and produced a result,
     * but before the result is returned to the parent as a tool observation.
     * The result can be modified (e.g., filter sensitive info, add metadata).
     *
     * @param childCtx the child agent's context (pipeline completed)
     * @param result   the subagent result (mutable; may be replaced by returning a new one)
     */
    default void subagentSpawned(AgentContext childCtx, SubagentResult result) {}

    /**
     * Called after the result has been recorded and before the child session
     * is archived. Use for cleanup, auditing, or logging.
     *
     * @param childCtx the child agent's context
     * @param result   the final subagent result
     */
    default void subagentEnded(AgentContext childCtx, SubagentResult result) {}
}
```

### 2.1.10 Subagent Error Handling and Timeout

```java
package lyjew.com.lyclaw.react.subagent;

import lombok.Builder;
import lombok.Data;

import java.util.List;
import java.util.ArrayList;

/**
 * The result of a subagent delegation call.
 * Returned to the parent LLM as a tool observation string (via toString/format),
 * but also available programmatically for hooks and metrics.
 */
@Data
@Builder
public class SubagentResult {

    /** Whether the subagent completed successfully. */
    private boolean success;

    /** Session key of the subagent run. */
    private String sessionKey;

    /** The agent ID that handled the delegation. */
    private String agentId;

    /** The final text output from the subagent (LLM's final response). */
    private String output;

    /** Error message if success == false. */
    private String error;

    /** Duration of the subagent run in milliseconds. */
    private long durationMs;

    /** Number of tool calls that succeeded. */
    private int successTools;

    /** Number of tool calls that failed. */
    private int failedTools;

    /** If the subagent called delegate_to_agent itself, these are the results. */
    @Builder.Default
    private List<SubagentResult> childResults = new ArrayList<>();

    /** The subagent's reflection score (from ReflectionStage), if any. */
    private Double reflectionScore;

    /** Total tokens consumed by this subagent. */
    private int totalTokens;


    // ── Factory methods ──

    public static SubagentResult success(String sessionKey, String agentId,
                                          String output, long durationMs,
                                          int successTools, int failedTools) {
        return SubagentResult.builder()
                .success(true)
                .sessionKey(sessionKey)
                .agentId(agentId)
                .output(output)
                .durationMs(durationMs)
                .successTools(successTools)
                .failedTools(failedTools)
                .build();
    }

    public static SubagentResult error(String error) {
        return SubagentResult.builder()
                .success(false)
                .agentId("unknown")
                .error(error)
                .build();
    }

    public static SubagentResult timeout(String agentId, long timeoutSeconds) {
        return SubagentResult.builder()
                .success(false)
                .agentId(agentId)
                .error("Subagent timed out after " + timeoutSeconds + " seconds")
                .durationMs(timeoutSeconds * 1000)
                .build();
    }

    public static SubagentResult rejected(String agentId, String reason) {
        return SubagentResult.builder()
                .success(false)
                .agentId(agentId)
                .error("Subagent delegation rejected: " + reason)
                .build();
    }

    /**
     * Format as a human-readable tool observation for the parent LLM.
     */
    public String formatAsObservation() {
        StringBuilder sb = new StringBuilder();
        sb.append("[Subagent Result] ");
        sb.append("agent=").append(agentId).append(" ");
        if (success) {
            sb.append("status=success ");
            sb.append("durationMs=").append(durationMs).append(" ");
            sb.append("toolsSucceeded=").append(successTools).append(" ");
            sb.append("toolsFailed=").append(failedTools).append("\n");
            sb.append("Output:\n").append(output);
        } else {
            sb.append("status=failed\n");
            sb.append("Error: ").append(error);
        }
        if (reflectionScore != null) {
            sb.append("\nReflection score: ").append(String.format("%.2f", reflectionScore));
        }
        return sb.toString();
    }
}
```

### 2.1.11 Configuration (application.yml)

```yaml
lyclaw:
  # Global subagent defaults
  subagent:
    enabled: true
    delegation-mode: suggest           # "suggest" or "prefer"
    allow-agents: "*"                  # "*" or comma-separated agent IDs
    max-concurrent: 1
    max-spawn-depth: 1                 # 1 = no recursive spawning
    max-children-per-agent: 5
    archive-after-minutes: 60
    run-timeout-seconds: 300
    announce-timeout-ms: 120000
    require-agent-id: false
    model:                             # optional model override for sub-agents
    thinking:                          # optional thinking level for sub-agents

  agent:
    # Default ReAct settings (existing)
    max-tool-rounds: 30

  # Example per-agent override via extensions (in AgentConfig or yml agent config)
  agents:
    chat:
      name: chat
      description: "General chat assistant with subagent delegation"
      model: deepseek-v4-flash
      provider: deepseek
      extensions:
        subagent.delegation-mode: prefer
        subagent.allow-agents: "code-reviewer,tester,data-analyst"
        subagent.max-concurrent: 3
        subagent.max-spawn-depth: 2
        subagent.max-children-per-agent: 10
        subagent.require-agent-id: true
        thinking.level: high            # Phase 2.2 - thinking level
        model.image: "openai/dall-e-3"
        model.pdf: "openai/gpt-4o"

    code-reviewer:
      name: code-reviewer
      description: "Specialized code review agent"
      model: deepseek-v4-flash
      provider: deepseek
      extensions:
        subagent.max-spawn-depth: 0    # This agent cannot spawn sub-agents
        subagent.max-concurrent: 0
        thinking.level: medium
```

---

## 2.2 Model Management Enhancement

### 2.2.1 Model Catalog

A structured catalog of all available models, replacing the current implicit model discovery.

```java
package lyjew.com.lyclaw.chat.catalog;

import java.util.List;
import java.util.Map;

/**
 * A structured entry in the model catalog.
 *
 * <p>Each entry represents one available model from a specific provider.
 * The catalog is built at startup from:
 * <ol>
 *   <li>Static configuration (application.yml lyclaw.chat.models.*)</li>
 *   <li>@ChatModel annotated beans (auto-discovered)</li>
 *   <li>ProviderDiscovery responses (auto-probed if enabled)</li>
 * </ol>
 *
 * <p>The ID is the canonical reference string: "provider/modelName"
 * e.g., "openai/gpt-4o", "deepseek/deepseek-v4-flash", "anthropic/claude-sonnet-4-5".
 */
public class ModelCatalogEntry {

    // ── Identity ──

    /** Full canonical reference: "openai/gpt-4o" */
    private String id;

    /** Model name: "gpt-4o" */
    private String name;

    /** Provider name: "openai" */
    private String provider;

    /** Optional short alias for convenience: "gpt4" */
    private String alias;

    /** Human-readable display name */
    private String displayName;

    /** Free-text description of this model */
    private String description;

    // ── Capabilities ──

    /** Maximum context window in tokens */
    private int contextWindow;

    /** Override for context tokens sent to the API (for providers that
     *  reserve part of the context window for internal use) */
    private int contextTokens;

    /** Whether this model supports extended reasoning/thinking */
    private boolean reasoning;

    /** Maximum output tokens this model can generate */
    private int maxOutputTokens;

    // ── Input modalities ──

    /** Input types this model accepts */
    private List<ModelInputType> input;

    // ── Pricing (informational) ──

    /** USD per 1M input tokens */
    private double pricePerMillionInput;

    /** USD per 1M output tokens */
    private double pricePerMillionOutput;

    // ── Compatibility config ──

    /** Provider-specific compatibility overrides */
    private ModelCompatConfig compat;

    // ── Status ──

    /** Whether this model is currently available (verified via health check) */
    private boolean available = true;

    /** Whether this is a beta/preview model */
    private boolean beta;

    /** When this model was deprecated (epoch millis), 0 = not deprecated */
    private long deprecatedAt;


    // ── Builder ──

    public static Builder builder() { return new Builder(); }

    public static class Builder {
        private final ModelCatalogEntry entry = new ModelCatalogEntry();
        public Builder id(String id) { entry.id = id; return this; }
        public Builder name(String name) { entry.name = name; return this; }
        public Builder provider(String provider) { entry.provider = provider; return this; }
        public Builder alias(String alias) { entry.alias = alias; return this; }
        public Builder displayName(String name) { entry.displayName = name; return this; }
        public Builder description(String desc) { entry.description = desc; return this; }
        public Builder contextWindow(int tokens) { entry.contextWindow = tokens; return this; }
        public Builder contextTokens(int tokens) { entry.contextTokens = tokens; return this; }
        public Builder reasoning(boolean v) { entry.reasoning = v; return this; }
        public Builder maxOutputTokens(int tokens) { entry.maxOutputTokens = tokens; return this; }
        public Builder input(List<ModelInputType> input) { entry.input = input; return this; }
        public Builder priceInput(double price) { entry.pricePerMillionInput = price; return this; }
        public Builder priceOutput(double price) { entry.pricePerMillionOutput = price; return this; }
        public Builder compat(ModelCompatConfig compat) { entry.compat = compat; return this; }
        public Builder available(boolean v) { entry.available = v; return this; }
        public Builder beta(boolean v) { entry.beta = v; return this; }
        public Builder deprecatedAt(long ts) { entry.deprecatedAt = ts; return this; }
        public ModelCatalogEntry build() { return entry; }
    }

    // ── Getters ──

    public String getId() { return id; }
    public String getName() { return name; }
    public String getProvider() { return provider; }
    public String getAlias() { return alias; }
    public String getDisplayName() { return displayName; }
    public String getDescription() { return description; }
    public int getContextWindow() { return contextWindow; }
    public int getContextTokens() { return contextTokens; }
    public boolean isReasoning() { return reasoning; }
    public int getMaxOutputTokens() { return maxOutputTokens; }
    public List<ModelInputType> getInput() { return input; }
    public double getPricePerMillionInput() { return pricePerMillionInput; }
    public double getPricePerMillionOutput() { return pricePerMillionOutput; }
    public ModelCompatConfig getCompat() { return compat; }
    public boolean isAvailable() { return available; }
    public void setAvailable(boolean v) { this.available = v; }
    public boolean isBeta() { return beta; }
    public long getDeprecatedAt() { return deprecatedAt; }

    /**
     * Build a canonical ID from provider and model name.
     */
    public static String canonicalId(String provider, String name) {
        return provider + "/" + name;
    }
}
```

```java
package lyjew.com.lyclaw.chat.catalog;

/**
 * Input types that a model can accept.
 */
public enum ModelInputType {
    /** Plain text */
    TEXT,

    /** Image files (png, jpg, gif, webp) */
    IMAGE,

    /** Audio files (mp3, wav, ogg) */
    AUDIO,

    /** Video files (mp4, mov) */
    VIDEO,

    /** Documents (pdf, docx, txt) */
    DOCUMENT
}
```

```java
package lyjew.com.lyclaw.chat.catalog;

import java.util.HashMap;
import java.util.Map;

/**
 * Provider-specific compatibility configuration.
 *
 * <p>Different providers use different field names, header formats,
 * and API conventions. This config captures those differences so that
 * the model resolution service can build correct native requests.</p>
 */
public class ModelCompatConfig {

    /** Whether this provider requires the model name in a specific field
     *  (e.g., some providers use "model" while others use "model_id") */
    private String modelFieldName = "model";

    /** Whether the provider emits SSE events with "data: " prefix */
    private boolean sseDataPrefix = true;

    /** Whether the SSE stream uses "\n\n" as delimiter */
    private boolean sseDoubleNewline = true;

    /** Whether tool call streaming is supported for this provider */
    private boolean supportsToolCallStreaming;

    /** Whether thinking/reasoning is in a separate field or inline */
    private String thinkingField = "reasoning_content";

    /** Whether thinking is combined with content or separate in streaming */
    private boolean thinkingInline;

    /** Provider-specific HTTP headers */
    private final Map<String, String> headers = new HashMap<>();

    /** Extra query parameters to append to the API URL */
    private final Map<String, String> queryParams = new HashMap<>();

    /** Whether this provider supports system message as top-level field
     *  (OpenAI style) or as a message with role="system" */
    private boolean systemMessageAsField = true;

    /** Maximum image size in bytes for vision models */
    private long maxImageBytes = 20 * 1024 * 1024; // 20MB

    /** Whether to auto-resize images before sending */
    private boolean autoResizeImages = true;

    /** Maximum image dimensions for auto-resize */
    private int maxImageWidth = 2048;
    private int maxImageHeight = 2048;

    // ── Getters / Setters ──

    public String getModelFieldName() { return modelFieldName; }
    public void setModelFieldName(String v) { this.modelFieldName = v; }

    public boolean isSseDataPrefix() { return sseDataPrefix; }
    public void setSseDataPrefix(boolean v) { this.sseDataPrefix = v; }

    public boolean isSseDoubleNewline() { return sseDoubleNewline; }
    public void setSseDoubleNewline(boolean v) { this.sseDoubleNewline = v; }

    public boolean isSupportsToolCallStreaming() { return supportsToolCallStreaming; }
    public void setSupportsToolCallStreaming(boolean v) { this.supportsToolCallStreaming = v; }

    public String getThinkingField() { return thinkingField; }
    public void setThinkingField(String v) { this.thinkingField = v; }

    public boolean isThinkingInline() { return thinkingInline; }
    public void setThinkingInline(boolean v) { this.thinkingInline = v; }

    public Map<String, String> getHeaders() { return headers; }
    public void setHeader(String key, String value) { headers.put(key, value); }

    public Map<String, String> getQueryParams() { return queryParams; }

    public boolean isSystemMessageAsField() { return systemMessageAsField; }
    public void setSystemMessageAsField(boolean v) { this.systemMessageAsField = v; }

    public long getMaxImageBytes() { return maxImageBytes; }
    public void setMaxImageBytes(long v) { this.maxImageBytes = v; }

    public boolean isAutoResizeImages() { return autoResizeImages; }
    public void setAutoResizeImages(boolean v) { this.autoResizeImages = v; }

    public int getMaxImageWidth() { return maxImageWidth; }
    public void setMaxImageWidth(int v) { this.maxImageWidth = v; }

    public int getMaxImageHeight() { return maxImageHeight; }
    public void setMaxImageHeight(int v) { this.maxImageHeight = v; }

    /** OpenAI-compatible defaults */
    public static ModelCompatConfig openAiDefaults() {
        ModelCompatConfig c = new ModelCompatConfig();
        c.modelFieldName = "model";
        c.sseDataPrefix = true;
        c.sseDoubleNewline = true;
        c.thinkingField = "reasoning_content";
        c.systemMessageAsField = false; // messages[0].role=system
        return c;
    }

    /** Anthropic-specific defaults */
    public static ModelCompatConfig anthropicDefaults() {
        ModelCompatConfig c = new ModelCompatConfig();
        c.modelFieldName = "model";
        c.sseDataPrefix = true;
        c.sseDoubleNewline = true;
        c.supportsToolCallStreaming = false;
        c.thinkingField = "thinking";
        c.thinkingInline = false;
        c.systemMessageAsField = true; // top-level system field
        return c;
    }
}
```

### 2.2.2 Multi-Model Support in AgentDefaultsConfig

We introduce a new `AgentDefaultsConfig` to replace the single-model assumption:

```java
package lyjew.com.lyclaw.chat.config;

/**
 * Per-agent or global default configuration for model selection by modality.
 *
 * <p>This replaces the single "model" concept with modality-specific models.
 * Each field can be either a canonical ID ("openai/gpt-4o") or an alias ("gpt-4o").
 * Fields set to null inherit from the global defaults in application.yml.</p>
 */
public class AgentModelConfig {

    /** Primary chat/text generation model */
    private String chatModel;

    /** Model used for image understanding (vision) */
    private String imageModel;

    /** Model used for image generation (DALL-E, etc.) */
    private String imageGenerationModel;

    /** Model used for video generation (Sora, etc.) */
    private String videoGenerationModel;

    /** Model used for music/sound generation */
    private String musicGenerationModel;

    /** Model used for PDF reading and understanding */
    private String pdfModel;

    // ── PDF limits ──

    /** Max PDF file size in MB */
    private int pdfMaxBytesMb = 10;

    /** Max pages to read from a PDF */
    private int pdfMaxPages = 20;

    // ── Generation settings ──

    /** Auto-fallback to another provider if the primary image gen model fails */
    private boolean mediaGenerationAutoProviderFallback = true;

    // ── Getters / Setters ──

    public String getChatModel() { return chatModel; }
    public void setChatModel(String model) { this.chatModel = model; }

    public String getImageModel() { return imageModel; }
    public void setImageModel(String model) { this.imageModel = model; }

    public String getImageGenerationModel() { return imageGenerationModel; }
    public void setImageGenerationModel(String model) { this.imageGenerationModel = model; }

    public String getVideoGenerationModel() { return videoGenerationModel; }
    public void setVideoGenerationModel(String model) { this.videoGenerationModel = model; }

    public String getMusicGenerationModel() { return musicGenerationModel; }
    public void setMusicGenerationModel(String model) { this.musicGenerationModel = model; }

    public String getPdfModel() { return pdfModel; }
    public void setPdfModel(String model) { this.pdfModel = model; }

    public int getPdfMaxBytesMb() { return pdfMaxBytesMb; }
    public void setPdfMaxBytesMb(int mb) { this.pdfMaxBytesMb = mb; }

    public int getPdfMaxPages() { return pdfMaxPages; }
    public void setPdfMaxPages(int pages) { this.pdfMaxPages = pages; }

    public boolean isMediaGenerationAutoProviderFallback() { return mediaGenerationAutoProviderFallback; }
    public void setMediaGenerationAutoProviderFallback(boolean v) { this.mediaGenerationAutoProviderFallback = v; }

    /**
     * Resolve the effective chat model, falling back to global defaults.
     */
    public String resolveChatModel(String globalDefault) {
        return chatModel != null ? chatModel : globalDefault;
    }
}
```

### 2.2.3 Model Selection and Resolution

```java
package lyjew.com.lyclaw.chat.config;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import lyjew.com.lyclaw.chat.ChatModel;
import lyjew.com.lyclaw.chat.ChatModelRegistry;
import lyjew.com.lyclaw.chat.RoutingDecision;
import lyjew.com.lyclaw.chat.RoutingTier;
import lyjew.com.lyclaw.chat.catalog.ModelCatalogEntry;
import lyjew.com.lyclaw.model.ChatRequest;
import lyjew.com.lyclaw.react.AgentContext;
import lyjew.com.lyclaw.react.RunMetadata;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Central service for resolving which model to use for a given agent + session.
 *
 * <h3>Resolution Order</h3>
 * <ol>
 *   <li>Check AgentContext.runMetadata for an override (set by subagent spawner)</li>
 *   <li>Check AgentConfig.model / AgentConfig.provider from annotation/yml</li>
 *   <li>Check agent extensions: thinking.level, model.image, model.pdf, etc.</li>
 *   <li>Fall back to global defaults (ChatProperties.defaultProvider/defaultModel)</li>
 *   <li>Fall back to FirstAvailableRouter if nothing is configured</li>
 * </ol>
 *
 * <h3>Alias Resolution</h3>
 * <p>Aliases are short names like "gpt-4o" that resolve to "openai/gpt-4o".
 * The alias map is populated from ModelCatalogEntry.alias fields.</p>
 */
public class ModelResolutionService {

    private static final Logger log = LoggerFactory.getLogger(ModelResolutionService.class);

    private final ChatModelRegistry registry;
    private final ModelCatalog modelCatalog;
    private final Map<String, String> aliasMap = new ConcurrentHashMap<>();

    /** Default fallback chain (canonical IDs in priority order) */
    private final List<String> defaultFallbackChain;

    public ModelResolutionService(ChatModelRegistry registry,
                                   ModelCatalog modelCatalog,
                                   List<String> defaultFallbackChain) {
        this.registry = registry;
        this.modelCatalog = modelCatalog;
        this.defaultFallbackChain = defaultFallbackChain != null
                ? List.copyOf(defaultFallbackChain) : List.of();
        buildAliasMap();
    }

    /**
     * Resolve the effective (provider, model) for this agent context's
     * primary chat model.
     */
    public ModelRef resolveEffectiveModel(AgentContext ctx) {
        RunMetadata meta = ctx.getRunMetadata();

        // 1. Override from runMetadata
        if (meta.getResolvedModel() != null && meta.getResolvedProvider() != null) {
            return new ModelRef(meta.getResolvedProvider(), meta.getResolvedModel());
        }

        // 2. From ChatRequest (set by AgentInvocationHandler from @Agent annotation)
        ChatRequest request = ctx.getChatRequest();
        if (request != null && request.getModel() != null && !request.getModel().isEmpty()) {
            // model field may be a canonical ID "deepseek/deepseek-v4-flash"
            // or just a model name paired with the request's implicit provider
            ModelRef ref = parseModelRef(request.getModel());
            if (ref != null) return ref;
        }

        // 3. From AgentConfig extensions (set by AgentConfigResolver)
        @SuppressWarnings("unchecked")
        Map<String, String> extensions = ctx.getAttribute("agentExtensions");
        if (extensions != null) {
            String configModel = extensions.get("model");
            String configProvider = extensions.get("provider");
            if (configModel != null) {
                return new ModelRef(
                        configProvider != null ? configProvider : "deepseek",
                        configModel);
            }
        }

        // 4. Fallback to first available model
        return resolveFirstAvailable();
    }

    /**
     * Resolve the model to use for image understanding (vision).
     */
    public ModelRef resolveImageModel(AgentContext ctx) {
        @SuppressWarnings("unchecked")
        Map<String, String> extensions = ctx.getAttribute("agentExtensions");
        if (extensions != null && extensions.containsKey("model.image")) {
            return parseModelRef(extensions.get("model.image"));
        }
        // Fall back to the primary model (most modern models support vision)
        return resolveEffectiveModel(ctx);
    }

    /**
     * Resolve the effective fallback chain for this context.
     * Operator overrides > agent config > global defaults.
     */
    public List<String> resolveEffectiveFallbacks(AgentContext ctx) {
        @SuppressWarnings("unchecked")
        Map<String, String> extensions = ctx.getAttribute("agentExtensions");
        if (extensions != null && extensions.containsKey("fallback.chain")) {
            return List.of(extensions.get("fallback.chain").split(","));
        }
        return defaultFallbackChain;
    }

    /**
     * Resolve an alias to its canonical ID.
     * e.g., "gpt-4o" → "openai/gpt-4o"
     */
    public String resolveAlias(String alias) {
        if (alias == null) return null;
        if (alias.contains("/")) return alias; // already canonical
        return aliasMap.getOrDefault(alias, alias);
    }

    /**
     * Auto-fallback probe: test whether a model works for a given session.
     * Returns probe config if fallback is needed, null if primary model works.
     */
    public AutoFallbackProbe resolveAutoFallbackProbe(String sessionKey,
                                                        String primaryProvider,
                                                        String primaryModel) {
        // Check if the model has recently failed health checks
        if (!modelCatalog.isAvailable(primaryProvider, primaryModel)) {
            // Find the first working fallback
            for (String fallbackId : defaultFallbackChain) {
                ModelRef ref = parseModelRef(fallbackId);
                if (ref != null && modelCatalog.isAvailable(ref.provider, ref.model)) {
                    return new AutoFallbackProbe(sessionKey, primaryProvider, primaryModel,
                            ref.provider, ref.model, "primary_unavailable");
                }
            }
        }
        return null; // primary model works, no fallback needed
    }

    /**
     * Parse a model reference string.
     * Accepts: "provider/model", "model" (provider derived from context), or alias.
     */
    public ModelRef parseModelRef(String ref) {
        if (ref == null || ref.isEmpty()) return null;

        // Try alias first
        String resolved = resolveAlias(ref);

        int slash = resolved.indexOf('/');
        if (slash > 0) {
            return new ModelRef(resolved.substring(0, slash), resolved.substring(slash + 1));
        }
        // No provider specified: use default provider
        return new ModelRef("deepseek", resolved);
    }

    private ModelRef resolveFirstAvailable() {
        Map<String, List<ChatModel>> all = registry.getAll();
        for (Map.Entry<String, List<ChatModel>> entry : all.entrySet()) {
            if (!entry.getValue().isEmpty()) {
                ChatModel first = entry.getValue().get(0);
                return new ModelRef(first.provider(), first.model());
            }
        }
        throw new IllegalStateException("No available AI models. Configure at least one provider.");
    }

    private void buildAliasMap() {
        for (ModelCatalogEntry entry : modelCatalog.getAll()) {
            if (entry.getAlias() != null && !entry.getAlias().isEmpty()) {
                aliasMap.put(entry.getAlias(), entry.getId());
            }
            // Also register name-only as alias when unambiguous
            aliasMap.putIfAbsent(entry.getProvider() + "/" + entry.getName(), entry.getId());
        }
    }

    // ── Inner types ──

    /**
     * A resolved (provider, model) pair.
     */
    public record ModelRef(String provider, String model) {
        public String canonicalId() {
            return provider + "/" + model;
        }
    }

    /**
     * Information about an auto-fallback probe.
     * When the primary model is unavailable, this tells the system
     * which fallback to use instead.
     */
    public record AutoFallbackProbe(String sessionKey,
                                     String primaryProvider, String primaryModel,
                                     String fallbackProvider, String fallbackModel,
                                     String reason) {}
}
```

### 2.2.4 Thinking / Reasoning / Verbose Controls

```java
package lyjew.com.lyclaw.chat.config;

/**
 * Thinking/reasoning level that controls how much the model "thinks" before
 * producing output. Mapped to provider-specific API parameters.
 *
 * <h3>Levels</h3>
 * <ul>
 *   <li><b>OFF</b> — thinking/reasoning disabled. Fastest, lowest cost.</li>
 *   <li><b>LOW</b> — brief reasoning. Good for simple tool-use tasks.</li>
 *   <li><b>MEDIUM</b> — moderate reasoning. Balanced for most tasks.</li>
 *   <li><b>HIGH</b> — extensive reasoning. For complex multi-step problems.</li>
 *   <li><b>MAX</b> — maximum reasoning budget. Highest quality, highest cost/latency.</li>
 * </ul>
 *
 * <h3>Provider Mapping</h3>
 * <ul>
 *   <li>DeepSeek: "thinking" parameter with "enabled" + "thinking_budget"</li>
 *   <li>OpenAI o-series: "reasoning_effort": low/medium/high</li>
 *   <li>Anthropic: "thinking" block with "budget_tokens"</li>
 *   <li>Gemini: "thinking_config" with "thinking_level"</li>
 * </ul>
 */
public enum ThinkingLevel {

    OFF(0, 0, "off"),
    LOW(1, 1024, "low"),
    MEDIUM(2, 4096, "medium"),
    HIGH(3, 16384, "high"),
    MAX(4, 32768, "max");

    private final int ordinal;
    private final int defaultBudgetTokens;
    private final String label;

    ThinkingLevel(int ordinal, int defaultBudgetTokens, String label) {
        this.ordinal = ordinal;
        this.defaultBudgetTokens = defaultBudgetTokens;
        this.label = label;
    }

    public int getDefaultBudgetTokens() { return defaultBudgetTokens; }
    public String getLabel() { return label; }

    /** Parse from string (case-insensitive): "off", "low", "medium", "high", "max" */
    public static ThinkingLevel fromString(String s) {
        if (s == null) return OFF;
        return switch (s.toLowerCase()) {
            case "off", "none", "disabled" -> OFF;
            case "low", "minimal" -> LOW;
            case "medium", "moderate", "balanced" -> MEDIUM;
            case "high", "extensive" -> HIGH;
            case "max", "maximum", "full" -> MAX;
            default -> OFF;
        };
    }

    /** Convert to DeepSeek API thinking parameter value */
    public String toDeepSeekThinking() {
        if (this == OFF) return null; // omit thinking block
        return "enabled";
    }

    /** Convert to DeepSeek thinking_budget tokens */
    public int toDeepSeekBudget() {
        return defaultBudgetTokens;
    }

    /** Convert to OpenAI reasoning_effort */
    public String toOpenAiReasoningEffort() {
        return switch (this) {
            case OFF -> null;
            case LOW -> "low";
            case MEDIUM -> "medium";
            case HIGH, MAX -> "high";
        };
    }
}
```

The thinking level is resolved and injected into `ChatRequest` at pipeline start:

```java
// ── In AgentInvocationHandler.invoke(), before stage execution ──

// Resolve thinking level from annotation/yml
String thinkingStr = resolveThinkingLevel(method, args);
ctx.getRunMetadata().setThinkingLevel(thinkingStr);

// Apply to ChatRequest
ThinkingLevel level = ThinkingLevel.fromString(thinkingStr);
if (level != ThinkingLevel.OFF) {
    request.setThinkingEnabled(true);
    request.setThinkingBudget(level.getDefaultBudgetTokens());
}
```

### 2.2.5 Provider Discovery

```java
package lyjew.com.lyclaw.chat.catalog;

import java.util.List;

import reactor.core.publisher.Mono;

/**
 * SPI for auto-discovering available models from a provider's API.
 *
 * <p>Providers that support a /models endpoint (OpenAI, DeepSeek, etc.)
 * implement this to populate the ModelCatalog at startup. This replaces
 * hardcoded model lists and enables dynamic model availability tracking.</p>
 */
public interface ProviderDiscovery {

    /**
     * Discover all available models from the provider's API.
     *
     * @param provider the provider name (e.g., "openai")
     * @param apiKey the API key for authentication
     * @return a Mono that completes with the list of discovered model entries
     */
    Mono<List<ModelCatalogEntry>> discoverModels(String provider, String apiKey);

    /**
     * Validate that a specific model is available and responsive.
     * Typically sends a minimal request (e.g., 1-token completion) to verify.
     *
     * @param provider the provider name
     * @param model the model name
     * @param apiKey the API key
     * @return true if the model responds successfully
     */
    Mono<Boolean> validateModel(String provider, String model, String apiKey);

    /**
     * Get the provider's supported features (streaming, tool calling, thinking, etc.)
     * from the /models/{model} endpoint.
     */
    Mono<ModelCompatConfig> probeCapabilities(String provider, String model, String apiKey);

    /**
     * Returns true if this discovery implementation supports the given provider.
     */
    boolean supportsProvider(String provider);
}
```

A default implementation for OpenAI-compatible APIs:

```java
package lyjew.com.lyclaw.chat.catalog;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import lyjew.com.lyclaw.chat.ChatProperties;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;

/**
 * OpenAI-compatible provider discovery via the /v1/models endpoint.
 *
 * <p>Works with OpenAI, DeepSeek, Groq, and any provider that implements
 * the OpenAI /v1/models API. Falls back gracefully if the endpoint is
 * not available or returns non-standard responses.</p>
 */
public class OpenAICompatibleProviderDiscovery implements ProviderDiscovery {

    private static final ObjectMapper mapper = new ObjectMapper();

    private final HttpClient httpClient;

    public OpenAICompatibleProviderDiscovery() {
        this.httpClient = HttpClient.create();
    }

    @Override
    public boolean supportsProvider(String provider) {
        // All providers using openai-protocol are supported
        return true;  // ChatProperties determines actual protocol
    }

    @Override
    public Mono<List<ModelCatalogEntry>> discoverModels(String provider, String apiKey) {
        // Use ChatProperties to find the provider's baseUrl
        ChatProperties.ModelProperties props = /* resolve from ChatProperties */ null;

        String url = (props != null ? props.getBaseUrl() : "https://api.openai.com") + "/v1/models";

        return httpClient
                .headers(h -> h.set("Authorization", "Bearer " + apiKey))
                .get()
                .uri(url)
                .responseSingle((response, body) -> body.asString())
                .map(json -> {
                    try {
                        JsonNode root = mapper.readTree(json);
                        JsonNode data = root.get("data");
                        if (data == null || !data.isArray()) return List.<ModelCatalogEntry>of();

                        List<ModelCatalogEntry> entries = new java.util.ArrayList<>();
                        for (JsonNode node : data) {
                            String id = node.get("id").asText();
                            String ownedBy = provider;
                            if (node.has("owned_by")) ownedBy = node.get("owned_by").asText();

                            ModelCatalogEntry entry = ModelCatalogEntry.builder()
                                    .id(ModelCatalogEntry.canonicalId(provider, id))
                                    .name(id)
                                    .provider(provider)
                                    .displayName(id)
                                    .available(true)
                                    .build();
                            entries.add(entry);
                        }
                        return entries;
                    } catch (Exception e) {
                        return List.<ModelCatalogEntry>of();
                    }
                })
                .onErrorReturn(List.of());
    }

    @Override
    public Mono<Boolean> validateModel(String provider, String model, String apiKey) {
        // Send a minimal chat completion request with max_tokens=1
        return Mono.just(true);  // Simplified; real impl would make a test call
    }

    @Override
    public Mono<ModelCompatConfig> probeCapabilities(String provider, String model, String apiKey) {
        return Mono.just(ModelCompatConfig.openAiDefaults());
    }
}
```

### 2.2.6 Model Fallback Chain Integration

The fallback chain from `ModelResolutionService` is integrated into the existing `FallbackChatModel` decorator:

```java
package lyjew.com.lyclaw.chat;

import java.util.List;

import lyjew.com.lyclaw.chat.config.ModelResolutionService;
import lyjew.com.lyclaw.chat.config.ModelResolutionService.ModelRef;
import lyjew.com.lyclaw.model.ChatRequest;
import lyjew.com.lyclaw.model.ModelResponse;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Enhanced fallback model that uses the ModelResolutionService to dynamically
 * resolve fallback candidates instead of a static hardcoded list.
 *
 * <p>Integrates with the existing FallbackChatModel decorator pattern but
 * adds model-catalog-aware resolution.</p>
 */
public class DynamicFallbackChatModel implements ChatModel {

    private static final Logger log = LoggerFactory.getLogger(DynamicFallbackChatModel.class);

    private final ChatModel primary;
    private final ModelResolutionService resolutionService;
    private final ChatModelRegistry registry;

    /** Fallback chain as canonical IDs, or null to use resolution service */
    private final List<String> staticFallbackChain;

    public DynamicFallbackChatModel(ChatModel primary,
                                      ModelResolutionService resolutionService,
                                      ChatModelRegistry registry,
                                      List<String> staticFallbackChain) {
        this.primary = primary;
        this.resolutionService = resolutionService;
        this.registry = registry;
        this.staticFallbackChain = staticFallbackChain;
    }

    @Override
    public String provider() { return primary.provider(); }

    @Override
    public String model() { return primary.model(); }

    @Override
    public ModelCapabilities capabilities() { return primary.capabilities(); }

    @Override
    public Flux<ModelResponse> stream(ChatRequest request) {
        return primary.stream(request)
                .onErrorResume(error -> {
                    log.warn("Primary model {}/{} failed: {}. Attempting fallback...",
                            primary.provider(), primary.model(), error.getMessage());

                    // Try each fallback in order
                    return tryFallbacks(request, 0);
                });
    }

    private Flux<ModelResponse> tryFallbacks(ChatRequest request, int attemptIndex) {
        List<String> chain = staticFallbackChain != null
                ? staticFallbackChain
                : List.of(); // will use dynamic resolution

        if (attemptIndex >= chain.size() && staticFallbackChain != null) {
            return Flux.error(new RuntimeException(
                    "All fallback models exhausted for " + primary.provider() + "/" + primary.model()));
        }

        String fallbackId = staticFallbackChain != null
                ? chain.get(attemptIndex)
                : null;

        if (fallbackId == null) {
            // Dynamic fallback resolution - find any working model
            ModelRef ref = resolutionService.parseModelRef(
                    primary.provider() + "/" + primary.model());
            if (ref == null) {
                return Flux.error(new RuntimeException("No fallback available"));
            }
            fallbackId = ref.canonicalId();
        }

        ModelRef ref = resolutionService.parseModelRef(fallbackId);
        if (ref == null) {
            return Flux.error(new RuntimeException("Invalid fallback ID: " + fallbackId));
        }

        ChatModel fallback = registry.resolve(ref.provider(), ref.model());
        if (fallback == null) {
            return tryFallbacks(request, attemptIndex + 1);
        }

        log.info("Falling back to {}/{} (attempt {})", ref.provider(), ref.model(), attemptIndex + 1);

        return fallback.stream(request)
                .onErrorResume(err -> {
                    log.warn("Fallback model {}/{} also failed: {}",
                            ref.provider(), ref.model(), err.getMessage());
                    return tryFallbacks(request, attemptIndex + 1);
                });
    }

    @Override
    public int countTokens(String text) { return primary.countTokens(text); }

    @Override
    public Mono<Boolean> validate() { return primary.validate(); }
}
```

### 2.2.7 SSE Events for Thinking

The `DefaultReActEngine` already handles thinking content via `ModelResponse.getThinking()`. We enhance this with structured SSE events:

```java
// ── Addition to DefaultReActEngine ──

/**
 * SSE event types for thinking/reasoning streaming.
 *
 * <p>Events emitted during streaming when thinking is enabled:
 * <ul>
 *   <li>{@code thinking_start} — emitted once when the model starts thinking
 *       (before any content is produced)</li>
 *   <li>{@code thinking_delta} — emitted for each thinking token/chunk</li>
 *   <li>{@code thinking_end} — emitted when the model stops thinking and
 *       begins producing content</li>
 * </ul>
 */
private static final String SSE_THINKING_START = "thinking_start";
private static final String SSE_THINKING_DELTA = "thinking_delta";
private static final String SSE_THINKING_END = "thinking_end";

// In the stream handle() callback, detect thinking vs. content:

// ...inside .handle((chunk, sink) -> { ...

if (chunk.getThinking() != null && !chunk.getThinking().isEmpty()) {
    // Emit thinking events instead of message events
    if (!thinkingStarted.get()) {
        thinkingStarted.set(true);
        sink.next(sseEvent(SSE_THINKING_START, ""));
    }
    sink.next(sseEvent(SSE_THINKING_DELTA, chunk.getThinking()));
    return;
}

if (thinkingStarted.get() && chunk.getContent() != null) {
    // Transition: thinking → content
    thinkingStarted.set(false);
    sink.next(sseEvent(SSE_THINKING_END, ""));
}
```

### 2.2.8 ChatRequest and ChatModel Enhancements

**ChatRequest additions for multi-model support:**

```java
// ── New fields in ChatRequest ──

/** Thinking/reasoning level (off/low/medium/high/max) */
private String thinkingLevel;

/** Override model for image understanding (separate from primary text model) */
private String imageModel;

/** Override model for PDF reading */
private String pdfModel;

/** When true, media generation requests will auto-fallback to
 *  alternative providers if the primary model fails */
@Builder.Default
private boolean mediaGenerationAutoFallback = true;
```

**ChatModel additions for thinking support:**

```java
// ── New method on ChatModel interface ──

/**
 * Whether this model supports thinking/reasoning at a specific level.
 * Models that don't support thinking will silently ignore the parameter.
 */
default boolean supportsThinkingLevel(ThinkingLevel level) {
    return capabilities().isThinking();
}

/**
 * Whether this model supports image input (vision).
 */
default boolean supportsVision() {
    return capabilities().isVision();
}
```

**ModelCapabilities enhancements:**

```java
// ── New fields in ModelCapabilities ──

/** Whether this model supports image generation */
private boolean imageGeneration;

/** Whether this model supports video generation */
private boolean videoGeneration;

/** Whether this model supports music generation */
private boolean musicGeneration;

/** Whether this model supports PDF reading */
private boolean pdfReading;

/** Maximum supported thinking effort level */
private ThinkingLevel maxThinkingLevel = ThinkingLevel.OFF;

// ... with getters/setters and builder methods ...
```

### 2.2.9 Configuration (application.yml)

```yaml
lyclaw:
  chat:
    default-provider: deepseek
    default-model: deepseek-v4-flash

    # Global model catalog (populated from annotations + this config)
    catalog:
      # Auto-discover models from provider APIs
      auto-discover: true
      # Cache discovered models for this many minutes
      discovery-cache-minutes: 60

      # Static catalog entries (not discovered, always available)
      entries:
        - id: openai/gpt-4o
          alias: gpt-4o
          display-name: "GPT-4o"
          context-window: 128000
          reasoning: true
          max-output-tokens: 16384
          input: [TEXT, IMAGE, DOCUMENT]
          price-million-input: 2.50
          price-million-output: 10.00

        - id: openai/gpt-4.1
          alias: gpt-4.1
          display-name: "GPT-4.1"
          context-window: 1000000
          reasoning: true
          max-output-tokens: 32768
          input: [TEXT, IMAGE, DOCUMENT]
          price-million-input: 2.00
          price-million-output: 8.00

        - id: openai/gpt-5.0-flash
          alias: gpt-5-flash
          display-name: "GPT-5.0 Flash"
          context-window: 256000
          reasoning: true
          max-output-tokens: 16384
          input: [TEXT, IMAGE, DOCUMENT]
          beta: false
          price-million-input: 1.50
          price-million-output: 6.00

        - id: deepseek/deepseek-v4-flash
          alias: deepseek-v4-flash
          display-name: "DeepSeek V4 Flash"
          context-window: 262144
          reasoning: true
          max-output-tokens: 8192
          input: [TEXT]
          price-million-input: 0.28
          price-million-output: 1.10

        - id: anthropic/claude-opus-4-5
          alias: claude-opus-4-5
          display-name: "Claude Opus 4.5"
          context-window: 200000
          reasoning: true
          max-output-tokens: 32000
          input: [TEXT, IMAGE, DOCUMENT]
          price-million-input: 15.00
          price-million-output: 75.00

        - id: openai/dall-e-3
          alias: dall-e-3
          display-name: "DALL-E 3"
          context-window: 0
          reasoning: false
          max-output-tokens: 0
          input: [TEXT]
          price-million-input: 0
          price-million-output: 40.00  # per image

    # Global fallback chain (canonical IDs in priority order)
    fallback-chain:
      - deepseek/deepseek-v4-flash
      - openai/gpt-5.0-flash
      - openai/gpt-4.1

    # Per-provider model configurations (existing, enhanced)
    models:
      deepseek:
        provider: deepseek
        base-url: https://api.deepseek.com
        api-key: ${DEEPSEEK_API_KEY}
        model: deepseek-v4-flash
        retry:
          max-attempts: 3
          backoff: exponential
          base-delay-ms: 1000
        fallback:
          - openai/gpt-5.0-flash
        options:
          thinking.level: medium

      openai:
        provider: openai
        base-url: https://api.openai.com
        api-key: ${OPENAI_API_KEY}
        model: gpt-4o
        options:
          thinking.level: high

    # Global thinking defaults
    thinking:
      default-level: medium     # off | low | medium | high | max
      fallback-level: low       # used when primary model doesn't support thinking

  # Agent-level model overrides (via AgentConfig)
  agent:
    default-mode: react
    max-tool-rounds: 30

  # Subagent defaults (repeated for clarity)
  subagent:
    enabled: true
    max-concurrent: 1
    max-spawn-depth: 1
    archive-after-minutes: 60
```

---

## 3. Integration Points Summary

### 3.1 The SubagentSpawner integrates into the existing system at these points:

| Integration Point | Description |
|---|---|
| **ToolRegistry / ToolProvider** | `DelegateToAgentToolProvider` registers `delegate_to_agent` as a built-in tool. It's a `ToolProvider` (not a static `@Tool`), giving it access to the `AgentContext` to spawn sub-agents. |
| **AgentInvocationHandler** | Resolves `SubagentConfig` from `@Agent` annotation extensions and injects it into `AgentContext.runMetadata`. The existing `agentExtensions` map in `AgentConfig` already supports this pattern. |
| **AgentContext** | Gains a `RunMetadata` field with `subagentDepth`, `parentSessionKey`, `activeSubagentIds`, `thinkingLevel`. This is set during context construction and carried through the pipeline. |
| **ReActEngine / DefaultReActEngine** | No API change needed. The `delegate_to_agent` tool appears as a regular tool in the tool list. When the LLM calls it, `ToolExecutor.execute()` routes through `DelegateToAgentToolProvider`, which blocks on `SubagentSpawner.spawnSubagent()`. |
| **RespondStage** | No change needed. The `registerToolProvider()` call on `ToolRegistry` (or `getAllDefinitions()` override) injects the delegate tool into every pipeline invocation. |
| **Pipeline Stages** | All stages (`ContextBuildStage`, `SecurityCheckStage`, `PlanExecutionStage`, `RespondStage`, `ReflectionStage`, `MetricsStage`) run identically for sub-agents. The only difference is that sub-agents have a nested `sessionKey` and `subagentDepth > 0`. |
| **AgentRegistry** | Used by `SubagentSpawner` to look up child agent configurations. The existing `lookup()`, `findByCapability()`, `findAvailable()` methods support this. |
| **AgentConfigResolver** | Used to resolve child agent's `model`, `provider`, `systemPrompt`, and `extensions` (including subagent limits). The existing multi-source resolution (annotation > yml > DB) applies. |
| **SessionStore** | Used by `SubagentSessionManager` for hierarchical session key storage and archival. |
| **AgentHook** | Extended with `SubagentHook` sub-interface for subagent lifecycle callbacks (`subagentSpawning`, `subagentSpawned`, `subagentEnded`). |

### 3.2 Model Management integrates at these points:

| Integration Point | Description |
|---|---|
| **ChatFacade / DefaultChatFacade** | Gains `ModelResolutionService` dependency. `route()` delegates to it for intelligent model selection. `resolveModel()` uses the catalog for alias resolution. |
| **ChatModelRegistry** | Populated from `ModelCatalog` entries at startup. The catalog entries come from static YAML config + `@ChatModel` annotations + `ProviderDiscovery` auto-probing. |
| **ChatModel** interface | Gains `supportsThinkingLevel()`, `supportsVision()` default methods. Existing implementations need no changes. |
| **ChatRequest** | Gains `thinkingLevel`, `imageModel`, `pdfModel`, `mediaGenerationAutoFallback` fields. |
| **ModelCapabilities** | Gains `imageGeneration`, `videoGeneration`, `musicGeneration`, `pdfReading`, `maxThinkingLevel` fields. |
| **AgentContext.runMetadata** | Gains `thinkingLevel`, `resolvedModel`, `resolvedProvider` fields for per-invocation model resolution. |
| **AgentInvocationHandler** | Resolves `thinkingLevel` from `@Agent` annotations, sets `ChatRequest.thinkingLevel` and `thinkingBudget` before invocation. |
| **DefaultReActEngine** | Emits `thinking_start`, `thinking_delta`, `thinking_end` SSE events during streaming when thinking is enabled. |
| **AbstractChatModel** | Subclasses can read `ChatRequest.thinkingLevel` and map it to provider-specific API parameters (e.g., DeepSeek "thinking" block, OpenAI "reasoning_effort"). |
| **ProviderDiscovery** | New SPI. `OpenAICompatibleProviderDiscovery` is the default implementation. Auto-populates `ModelCatalog` at startup. |
| **FirstAvailableRouter** | Replaced by `ModelResolutionService.resolveFirstAvailable()` for default routing, but kept as a fallback. |

---

## 4. Migration Path

### 4.1 Phase 2a: Model Management (non-breaking)

1. **Add `ModelCatalogEntry`, `ModelCompatConfig`, `ModelInputType`** — new classes, no existing code changes.
2. **Add `ThinkingLevel` enum** — new class.
3. **Extend `ModelCapabilities`** — additive fields only, default to false/0 (backward compatible).
4. **Add `thinkingLevel` to `ChatRequest`** — new field with default null (backward compatible).
5. **Add `ModelResolutionService`** — new class, replaces nothing yet.
6. **Add `ProviderDiscovery` SPI + `OpenAICompatibleProviderDiscovery`** — new, no changes to existing.
7. **Add `AgentModelConfig`** — new class for modality-specific model resolution.
8. **Extend `@Agent` annotation extensions** — no code change needed, just document the new extension keys in `@Extension` values.

### 4.2 Phase 2b: Subagent System (additive, initially disabled)

1. **Add `SubagentConfig`, `SubagentSpawner`, `SubagentSessionManager`** — new classes.
2. **Add `RunMetadata`** — new class. Add a `runMetadata` field to `AgentContext` (non-breaking, the field starts with default values).
3. **Add `SubagentResult`, `SubagentHook`** — new classes.
4. **Add `DelegateToAgentToolProvider`** — new class. Register conditionally via auto-configuration (disabled by default until `lyclaw.subagent.enabled=true`).
5. **Extend `AgentHook`** — add `SubagentHook` sub-interface (non-breaking, existing hooks ignore the new callbacks).

### 4.3 Phase 2c: Integration (feature-flagged)

1. **Wire `SubagentSpawner` into auto-configuration** — only if `lyclaw.subagent.enabled=true`.
2. **Wire `DelegateToAgentToolProvider` into `ToolRegistry`** — via `ToolProvider` SPI.
3. **Wire `ModelResolutionService` into `DefaultChatFacade`** — replace direct `router.route()` calls with `resolutionService.resolveEffectiveModel()`, but keep `FirstAvailableRouter` as fallback.
4. **Add thinking SSE events to `DefaultReActEngine`** — backward compatible (new event types, existing clients ignore unknown events).
5. **Enable subagents for specific agents via `@Agent` extension keys** — per-agent opt-in.

### 4.4 Rollback Strategy

- All new classes are in separate packages (`lyclaw.react.subagent`, `lyclaw.chat.catalog`, `lyclaw.chat.config`), making them easy to remove.
- Feature flags in application.yml control all new behavior:
  - `lyclaw.subagent.enabled=false` disables delegation entirely
  - `lyclaw.chat.catalog.auto-discover=false` disables provider discovery
  - Thinking level defaults to OFF (no change in behavior)
- The existing `FirstAvailableRouter` continues to work as the default when no model catalog is configured.

---

## Appendix: File Manifest

All new files created in Phase 2:

```
lyclaw-framework/src/main/java/lyjew/com/lyclaw/
├── react/
│   ├── subagent/
│   │   ├── SubagentConfig.java          (new)
│   │   ├── SubagentSpawner.java         (new)
│   │   ├── SubagentResult.java          (new)
│   │   ├── SubagentHook.java            (new)
│   │   ├── SubagentSessionManager.java  (new)
│   │   ├── DelegateToAgentToolProvider.java (new)
│   │   └── ToolProviderContext.java     (new)
│   └── RunMetadata.java                 (new)
├── chat/
│   ├── catalog/
│   │   ├── ModelCatalogEntry.java       (new)
│   │   ├── ModelInputType.java          (new)
│   │   ├── ModelCompatConfig.java       (new)
│   │   ├── ModelCatalog.java            (new, interface)
│   │   ├── InMemoryModelCatalog.java    (new)
│   │   ├── ProviderDiscovery.java       (new, SPI)
│   │   └── OpenAICompatibleProviderDiscovery.java (new)
│   ├── config/
│   │   ├── AgentModelConfig.java        (new)
│   │   ├── ThinkingLevel.java           (new)
│   │   └── ModelResolutionService.java  (new)
│   └── DynamicFallbackChatModel.java    (new)

Modified existing files:
├── react/
│   └── AgentContext.java                (add runMetadata field, toSnapshot/restore)
├── model/
│   └── ChatRequest.java                 (add thinkingLevel, imageModel, pdfModel)
├── chat/
│   ├── ChatModel.java                   (add supportsThinkingLevel, supportsVision)
│   ├── ModelCapabilities.java           (add imageGeneration, videoGeneration, etc.)
│   └── DefaultChatFacade.java           (integrate ModelResolutionService)
├── annotation/
│   └── Agent.java                       (document new extension keys)
└── config/
    └── ChatProperties.java              (add catalog, thinking, subagent sections)

lyclaw-autoconfigure/src/main/java/lyjew/com/lyclaw/autoconfigure/autoconfigure/
└── SubagentAutoConfiguration.java       (new, conditional on lyclaw.subagent.enabled)
```

---

# Phase 3: Context Engine & Compaction + Workspace Bootstrap + Agent Routing & Identity

> **Status:** Draft
> **Target:** LyClaw Framework — lyclaw-framework, lyclaw-autoconfigure, lyclaw-web
> **Preceding Phase:** Phase 2 (Reflection & Evaluation)
> **Following Phase:** Phase 4 (Final Integration & Polish)
>
> LyClaw currently has no compaction, no context pruning, no workspace bootstrap files,
> no agent routing, and no identity system. This phase fills all of those gaps.

---

## Table of Contents

1. [Architecture Overview](#architecture-overview)
2. [3.1 Context Engine & Compaction](#31-context-engine--compaction)
   - [3.1.1 CompactionConfig](#311-compactionconfig)
   - [3.1.2 CompactionEngine](#312-compactionengine)
   - [3.1.3 Context Pruning](#313-context-pruning)
   - [3.1.4 AgentContextLimits](#314-agentcontextlimits)
   - [3.1.5 Pipeline Integration](#315-pipeline-integration)
   - [3.1.6 YAML Configuration](#316-yaml-configuration)
3. [3.2 Workspace Bootstrap](#32-workspace-bootstrap)
   - [3.2.1 Bootstrap Files Structure](#321-bootstrap-files-structure)
   - [3.2.2 BootstrapConfig](#322-bootstrapconfig)
   - [3.2.3 BootstrapLoader](#323-bootstraploader)
   - [3.2.4 ContextInjectionPolicy](#324-contextinjectionpolicy)
   - [3.2.5 Pipeline Integration](#325-pipeline-integration)
   - [3.2.6 YAML Configuration](#326-yaml-configuration)
4. [3.3 Agent Routing & Binding](#33-agent-routing--binding)
   - [3.3.1 AgentBindingMatch](#331-agentbindingmatch)
   - [3.3.2 AgentRouteBinding & AgentAcpBinding](#332-agentroutebinding--agentacpbinding)
   - [3.3.3 AgentRouter](#333-agentrouter)
   - [3.3.4 ChatController Update](#334-chatcontroller-update)
   - [3.3.5 YAML Configuration](#335-yaml-configuration)
5. [3.4 Identity & Avatar](#34-identity--avatar)
   - [3.4.1 IdentityConfig](#341-identityconfig)
   - [3.4.2 AvatarResolution](#342-avatarresolution)
   - [3.4.3 Integration & YAML](#343-integration--yaml)
6. [Full YAML Configuration Reference](#full-yaml-configuration-reference)
7. [Integration Checklist](#integration-checklist)

---

## Architecture Overview

```
                          ChatController
                               │
                               ▼
                    ┌─ AgentRouter ─┐
                    │  resolveAgent │
                    │  matchBinding │
                    └──────┬────────┘
                           │ agentId
                           ▼
              ┌─── Pipeline Stages ───────────────────────────┐
              │                                                │
              │  ContextBuildStage                             │
              │    ├─ BootstrapLoader.loadBootstrap()          │
              │    ├─ IdentityConfig injection                  │
              │    └─ SystemPromptBuilder.build()              │
              │                                                │
              │  SecurityCheckStage                            │
              │                                                │
              │  PlanExecutionStage                            │
              │                                                │
              │  RespondStage  (ReAct loop)                    │
              │    ├─ CompactionEngine.midTurnPrecheck()       │
              │    └─ AgentContextLimits enforced              │
              │                                                │
              │  ReflectionStage                               │
              │                                                │
              │  CompactionStage         ★ NEW ★               │
              │    ├─ needsCompaction() check                   │
              │    ├─ memoryFlush (before)                      │
              │    ├─ compact() execute                         │
              │    ├─ validateCompaction() quality guard        │
              │    └─ inject postCompactionSections             │
              │                                                │
              │  MetricsStage                                  │
              │                                                │
              │  ContextPruningScheduler  ★ NEW ★              │
              │    (background, periodic, CACHE_TTL)           │
              └────────────────────────────────────────────────┘
```

---

## 3.1 Context Engine & Compaction

### Motivation

Long-running agent sessions accumulate massive transcript histories (tool outputs,
multi-turn reasoning, inline file content). Without compaction, the LLM context
window fills up, API costs balloon, and the agent degrades because early critical
instructions are pushed out of the context window.

The CompactionEngine solves this by:
1. Detecting when context pressure is too high (`maxActiveTranscriptBytes`).
2. Summarizing the "middle" history into a compact representation while preserving
   recent turns and session-startup instructions.
3. Validating the compaction result with a quality guard (LLM re-check).
4. Optionally flushing memory before compaction so key facts persist across the
   boundary.

### 3.1.1 CompactionConfig

```java
package lyjew.com.lyclaw.compaction;

import lombok.Builder;
import lombok.Data;
import java.time.Duration;
import java.util.List;

/**
 * Configuration for the Compaction Engine.
 *
 * <p>Controls when and how session transcripts are compacted to prevent
 * context-window overflow during long-running agent sessions.</p>
 *
 * <p>Mapped from {@code lyclaw.compaction} in application.yml.</p>
 */
@Data
@Builder
public class CompactionConfig {

    /** Compaction strategy mode. */
    @Builder.Default
    CompactionMode mode = CompactionMode.DEFAULT;

    /**
     * Reserve this many tokens at the top of the context window for
     * system prompt, bootstrap content, and tool definitions.
     * Default: 8000 (roughly 32KB at 4 chars/token).
     */
    @Builder.Default
    int reserveTokens = 8000;

    /**
     * Keep the most recent N tokens of conversation history un-compacted.
     * Default: 4000 (roughly 16KB).
     */
    @Builder.Default
    int keepRecentTokens = 4000;

    /**
     * Hard floor: never compact below this many remaining tokens,
     * even if reserveTokens calculation suggests a deeper cut.
     * Default: 2000.
     */
    @Builder.Default
    int reserveTokensFloor = 2000;

    /**
     * Maximum share of the token budget that history (non-system) may occupy.
     * When history exceeds this, compaction triggers.
     * Default: 0.5 (50%).
     */
    @Builder.Default
    double maxHistoryShare = 0.5;

    /** Custom instructions injected into the compaction LLM prompt. */
    String customInstructions;

    /**
     * Number of most recent assistant/user turns to preserve verbatim.
     * These are the turns immediately before the current user message.
     * Default: 3.
     */
    @Builder.Default
    int recentTurnsPreserve = 3;

    /**
     * Policy for how identifiers (file paths, URLs, function names)
     * are handled during compaction.
     * STRICT: identifiers must be preserved exactly.
     * OFF: no special handling.
     * CUSTOM: use identifierInstructions for guidance.
     */
    @Builder.Default
    IdentifierPolicy identifierPolicy = IdentifierPolicy.STRICT;

    /** Custom instructions for identifier preservation (CUSTOM mode only). */
    String identifierInstructions;

    /** Quality guard configuration. */
    @Builder.Default
    QualityGuard qualityGuard = new QualityGuard();

    /** Mid-turn precheck configuration. */
    @Builder.Default
    MidTurnPrecheck midTurnPrecheck = new MidTurnPrecheck();

    /** Whether to sync or async re-index memory after compaction. */
    @Builder.Default
    PostIndexSync postIndexSync = PostIndexSync.ASYNC;

    /** Memory flush configuration (run before compaction). */
    @Builder.Default
    MemoryFlush memoryFlush = new MemoryFlush();

    /**
     * Post-compaction sections to inject into the system prompt after compaction
     * completes. Typical values: "Session Startup", "Red Lines".
     * These re-anchor the agent's behavior after context shift.
     */
    @Builder.Default
    List<String> postCompactionSections = List.of("Session Startup", "Red Lines");

    /**
     * Override model for compaction LLM calls. When null, uses the session model.
     * A cheaper/faster model (e.g. "deepseek-v4-flash") is recommended.
     */
    String model;

    /** Timeout for a single compaction LLM call. Default: 900 seconds. */
    @Builder.Default
    int timeoutSeconds = 900;

    /**
     * If true, truncate trailing content after compaction rather than
     * keeping it alongside the summary. Default: false.
     */
    @Builder.Default
    boolean truncateAfterCompaction = false;

    /**
     * Maximum active transcript size in bytes before compaction is triggered.
     * Default: 10 MB (10 * 1024 * 1024).
     */
    @Builder.Default
    long maxActiveTranscriptBytes = 10 * 1024 * 1024;

    /**
     * If true, send an SSE event notifying the user that compaction occurred.
     * Default: false (silent).
     */
    @Builder.Default
    boolean notifyUser = false;
}
```

#### Supporting Enums and Sub-Configs

```java
package lyjew.com.lyclaw.compaction;

public enum CompactionMode {
    /** Normal compaction: summarize middle history, keep ends. */
    DEFAULT,
    /**
     * Extended safety checks before compaction. Uses a second LLM call to
     * verify that critical instructions are preserved in the summary.
     * Slower but safer for high-stakes sessions.
     */
    SAFEGUARD
}

public enum IdentifierPolicy {
    /** Identifiers must be preserved exactly. */
    STRICT,
    /** No special identifier handling. */
    OFF,
    /** Use identifierInstructions for guidance. */
    CUSTOM
}

public enum PostIndexSync {
    /** Do not re-index memory after compaction. */
    OFF,
    /** Trigger async re-index; compaction returns immediately. */
    ASYNC,
    /** Wait for re-index to complete before returning. */
    AWAIT
}
```

```java
package lyjew.com.lyclaw.compaction;

import lombok.Data;

/** Quality guard: post-compaction validation via a second LLM call. */
@Data
public class QualityGuard {
    /** Enable quality guard. Default: true. */
    boolean enabled = true;
    /**
     * Maximum retries if validation fails.
     * Each retry re-runs compaction with stricter instructions.
     * Default: 2.
     */
    int maxRetries = 2;
}

/** Mid-turn precheck: during long tool loops, check if compaction is needed. */
@Data
public class MidTurnPrecheck {
    /** Enable mid-turn precheck. Default: true. */
    boolean enabled = true;
}

/**
 * Memory flush: extract key facts from the soon-to-be-compacted region
 * and persist them to MemorySystem before compaction discards the raw text.
 */
@Data
public class MemoryFlush {
    /** Enable memory flush before compaction. Default: true. */
    boolean enabled = true;
    /** Model to use for memory extraction. Null = use compaction model. */
    String model;
    /**
     * Soft threshold in tokens: if the to-be-compacted region is below this,
     * skip the flush to save cost. Default: 4000.
     */
    int softThresholdTokens = 4000;
    /**
     * If transcript bytes exceed this, force a memory flush regardless of
     * softThresholdTokens. Default: 500KB.
     */
    long forceFlushTranscriptBytes = 500 * 1024;
    /** Prompt override for memory extraction. */
    String prompt;
    /** System prompt override for memory extraction. */
    String systemPrompt;
}
```

### 3.1.2 CompactionEngine

```java
package lyjew.com.lyclaw.compaction;

import lyjew.com.lyclaw.chat.ChatFacade;
import lyjew.com.lyclaw.memory.MemorySystem;
import lyjew.com.lyclaw.model.Message;
import lyjew.com.lyclaw.model.Session;
import lyjew.com.lyclaw.react.AgentContext;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * CompactionEngine is responsible for detecting context-window pressure
 * and compacting session transcripts to keep the agent within budget.
 *
 * <h3>Lifecycle of a compaction</h3>
 * <ol>
 *   <li>{@link #needsCompaction} — check transcript size vs limits</li>
 *   <li>Memory Flush (if enabled) — extract facts from the middle region</li>
 *   <li>beforeCompaction hooks — dispatched to {@link AgentHook}</li>
 *   <li>{@link #compact} — LLM summarization of middle history</li>
 *   <li>{@link #validateCompaction} — quality guard (SAFEGUARD mode)</li>
 *   <li>Post-compaction section injection — re-anchor instructions</li>
 *   <li>afterCompaction hooks — dispatched to {@link AgentHook}</li>
 * </ol>
 *
 * <p>The engine works on the Session.messages list in-place: it replaces
 * the summarized middle turns with a synthetic system message containing
 * the compaction summary, preserving the most recent turns and any
 * session-startup system messages.</p>
 */
public class CompactionEngine {

    private final ChatFacade chatFacade;
    private final MemorySystem memorySystem;
    private final CompactionConfig config;

    public CompactionEngine(ChatFacade chatFacade, MemorySystem memorySystem,
                            CompactionConfig config) {
        this.chatFacade = chatFacade;
        this.memorySystem = memorySystem;
        this.config = config;
    }

    /**
     * Check whether the session transcript exceeds configured limits and
     * requires compaction.
     *
     * @param session the current session
     * @param config  compaction configuration
     * @return true if compaction should run
     */
    public boolean needsCompaction(Session session, CompactionConfig config) {
        long transcriptBytes = estimateTranscriptBytes(session);
        if (transcriptBytes >= config.getMaxActiveTranscriptBytes()) {
            return true;
        }
        // Also check token-based budget
        int totalTokens = estimateTokenCount(session);
        int systemTokens = estimateSystemTokens(session);
        int historyTokens = totalTokens - systemTokens;
        int budget = systemTokens + config.getReserveTokens();
        double share = (double) historyTokens / (double) totalTokens;
        return share > config.getMaxHistoryShare()
                || historyTokens > (totalTokens - config.getReserveTokensFloor());
    }

    /**
     * Run compaction on a session.
     *
     * <p>If memoryFlush is enabled and the transcript exceeds thresholds,
     * facts are extracted from the middle region and persisted to
     * MemorySystem before summarization begins.</p>
     *
     * @param session the session to compact
     * @param config  compaction configuration
     * @param ctx     current agent context (for hook dispatch, tracing, model access)
     * @return compaction result
     */
    public Mono<CompactionResult> compact(Session session, CompactionConfig config,
                                          AgentContext ctx) {
        return Mono.fromCallable(() -> {
            // 1. Partition messages: head (system/startup), middle (to summarize), tail (recent)
            MessagePartition partition = partitionMessages(
                    session.getMessages(), config);

            // 2. Optional memory flush
            if (config.getMemoryFlush().isEnabled()) {
                long middleBytes = estimateBytes(partition.middle());
                if (middleBytes >= config.getMemoryFlush().getForceFlushTranscriptBytes()
                        || estimateTokenCount(partition.middle())
                           >= config.getMemoryFlush().getSoftThresholdTokens()) {
                    flushMemory(partition.middle(), config, ctx);
                }
            }

            // 3. Build compaction prompt and call LLM
            String summary = callCompactionLLM(partition, config, ctx);

            // 4. Reconstruct messages list
            reconstructMessages(session, partition, summary, config);

            return new CompactionResult(
                    partition.headCount(), partition.middleCount(),
                    partition.tailCount(), summary.length(),
                    estimateTokenCount(session));
        });
    }

    /**
     * Validate that compaction did not lose critical information.
     * Used in SAFEGUARD mode or when qualityGuard is enabled.
     *
     * <p>This sends the pre-compaction and post-compaction transcripts
     * to an LLM with a checklist of critical items, asking whether the
     * compaction preserved them.</p>
     *
     * @param result compaction result to validate
     * @param config compaction configuration
     * @return true if validation passes
     */
    public Mono<Boolean> validateCompaction(CompactionResult result,
                                            CompactionConfig config) {
        if (!config.getQualityGuard().isEnabled()) {
            return Mono.just(true);
        }
        // Implementation: send pre + post summaries to LLM with checklist
        // ...
        return Mono.just(true);
    }

    /**
     * Mid-turn precheck: called during long tool-call loops to check
     * whether the context window is under pressure. If so, signals that
     * the ReAct loop should pause for compaction.
     *
     * @param ctx agent context with current tool results and history
     * @return true if compaction is needed mid-turn
     */
    public Mono<Boolean> midTurnPrecheck(AgentContext ctx) {
        if (!config.getMidTurnPrecheck().isEnabled()) {
            return Mono.just(false);
        }
        // Estimate current transcript size from tool results + history
        // ...
        return Mono.just(false);
    }

    // ── Internal helpers ───────────────────────────────────────────

    private long estimateTranscriptBytes(Session session) {
        return session.getMessages().stream()
                .mapToLong(m -> (m.getContent() != null ? m.getContent().length() : 0)
                        + (m.getThinking() != null ? m.getThinking().length() : 0))
                .sum();
    }

    private int estimateTokenCount(Session session) {
        // Rough estimate: 4 chars per token
        long chars = session.getMessages().stream()
                .mapToLong(m -> (m.getContent() != null ? m.getContent().length() : 0)
                        + (m.getThinking() != null ? m.getThinking().length() : 0))
                .sum();
        return (int) (chars / 4);
    }

    private int estimateTokenCount(List<Message> messages) {
        long chars = messages.stream()
                .mapToLong(m -> (m.getContent() != null ? m.getContent().length() : 0)
                        + (m.getThinking() != null ? m.getThinking().length() : 0))
                .sum();
        return (int) (chars / 4);
    }

    private int estimateSystemTokens(Session session) {
        return (int) session.getMessages().stream()
                .filter(m -> "system".equals(m.getRole()))
                .mapToLong(m -> m.getContent() != null ? m.getContent().length() : 0)
                .sum() / 4;
    }

    private long estimateBytes(List<Message> messages) {
        return messages.stream()
                .mapToLong(m -> (m.getContent() != null ? m.getContent().length() : 0)
                        + (m.getThinking() != null ? m.getThinking().length() : 0))
                .sum();
    }

    /**
     * Partition the message list into three regions:
     * - head: system messages and early session setup
     * - middle: the bulk of the history (to be summarized)
     * - tail: the most recent `recentTurnsPreserve` turns
     */
    private MessagePartition partitionMessages(List<Message> messages,
                                               CompactionConfig config) {
        // Implementation details: walk the message list, identify system prefix,
        // identify tail turns, everything else is middle.
        // ...
        return new MessagePartition(List.of(), List.of(), List.of(), 0, 0, 0);
    }

    private void flushMemory(List<Message> middle, CompactionConfig config,
                             AgentContext ctx) {
        // Extract facts from middle messages via LLM, persist to MemorySystem
        // ...
    }

    private String callCompactionLLM(MessagePartition partition,
                                     CompactionConfig config, AgentContext ctx) {
        // Build compaction prompt, call LLM, return summary string
        // ...
        return "";
    }

    private void reconstructMessages(Session session, MessagePartition partition,
                                     String summary, CompactionConfig config) {
        // Replace middle messages with a synthetic system message containing the summary
        // ...
    }

    /** Result of a single compaction run. */
    public record CompactionResult(
            int headMessages, int middleMessages, int tailMessages,
            int summaryChars, int finalTokenEstimate) {}

    private record MessagePartition(
            List<Message> head, List<Message> middle, List<Message> tail,
            int headCount, int middleCount, int tailCount) {}
}
```

### 3.1.3 Context Pruning

Context pruning is a lighter-weight mechanism than compaction. Instead of
LLM summarization, it trims or replaces stale tool results to free up context
space. It runs on a background scheduler when `mode=CACHE_TTL`.

```java
package lyjew.com.lyclaw.compaction;

import lombok.Builder;
import lombok.Data;

import java.time.Duration;
import java.util.Set;

/**
 * Configuration for Context Pruning — lightweight trimming of stale
 * tool results from session transcripts without LLM summarization.
 *
 * <p>Mapped from {@code lyclaw.compaction.pruning} in application.yml.</p>
 */
@Data
@Builder
public class ContextPruningConfig {

    public enum PruningMode {
        /** Pruning disabled. */
        OFF,
        /**
         * Cache-TTL mode: tool results older than `ttl` are eligible for
         * soft trim or hard clear based on age and size.
         */
        CACHE_TTL
    }

    /** Pruning mode. Default: OFF. */
    @Builder.Default
    PruningMode mode = PruningMode.OFF;

    /** Time-to-live for tool result content. Default: 30 minutes. */
    @Builder.Default
    Duration ttl = Duration.ofMinutes(30);

    /**
     * Keep the N most recent assistant messages intact (not pruned).
     * Default: 5.
     */
    @Builder.Default
    int keepLastAssistants = 5;

    /**
     * When a tool result's character count exceeds this ratio of the
     * context budget, apply a soft trim (keep head + tail).
     * Default: 0.3 (30%).
     */
    @Builder.Default
    double softTrimRatio = 0.3;

    /**
     * When total tool result chars exceed this ratio of the context budget,
     * apply hard clear (replace with placeholder) to the oldest results.
     * Default: 0.6 (60%).
     */
    @Builder.Default
    double hardClearRatio = 0.6;

    /**
     * Minimum character count before a tool result is eligible for pruning.
     * Small results are cheap to keep. Default: 1000.
     */
    @Builder.Default
    int minPrunableToolChars = 1000;

    /**
     * Allow-list: tool names that CAN be pruned.
     * When empty, all tools are eligible (subject to toolDeny).
     */
    Set<String> toolAllow;

    /**
     * Deny-list: tool names that CANNOT be pruned.
     * Use this for high-value tools like file_read where the output
     * must stay in context.
     */
    Set<String> toolDeny;

    /** Soft trim configuration. */
    @Builder.Default
    SoftTrim softTrim = new SoftTrim();

    /** Hard clear configuration. */
    @Builder.Default
    HardClear hardClear = new HardClear();

    /** Soft trim: keep N chars from head and tail, replace middle with "...". */
    @Data
    public static class SoftTrim {
        /** Max chars after trimming. Default: 8000. */
        int maxChars = 8000;
        /** Chars to keep from the head. Default: 2000. */
        int headChars = 2000;
        /** Chars to keep from the tail. Default: 2000. */
        int tailChars = 2000;
    }

    /** Hard clear: replace entire tool result with a placeholder message. */
    @Data
    public static class HardClear {
        /** Enable hard clear. Default: true. */
        boolean enabled = true;
        /** Placeholder text. Default: "[earlier output trimmed for space]". */
        @Builder.Default
        String placeholder = "[earlier output trimmed for space]";
    }
}
```

```java
package lyjew.com.lyclaw.compaction;

import lyjew.com.lyclaw.model.Message;
import lyjew.com.lyclaw.model.Session;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.Set;

/**
 * ContextPruner applies lightweight trimming to stale tool-result messages.
 *
 * <p>Unlike CompactionEngine (which uses LLM summarization), ContextPruner
 * uses simple rules: CACHE_TTL mode checks each tool result's age and
 * applies soft trim (head+tail truncation) or hard clear (placeholder
 * replacement) based on configured ratios.</p>
 */
public class ContextPruner {

    private static final Logger log = LoggerFactory.getLogger(ContextPruner.class);

    private final ContextPruningConfig config;

    public ContextPruner(ContextPruningConfig config) {
        this.config = config;
    }

    /**
     * Prune a session's messages in-place, removing or trimming stale tool results.
     *
     * @param session the session to prune
     * @param now     current time reference
     * @return number of messages modified
     */
    public int prune(Session session, Instant now) {
        if (config.getMode() == ContextPruningConfig.PruningMode.OFF) {
            return 0;
        }

        int modified = 0;
        Duration ttl = config.getTtl();
        Set<String> allow = config.getToolAllow();
        Set<String> deny = config.getToolDeny();

        int assistantCount = 0;
        int keepAssistant = config.getKeepLastAssistants();

        // Walk messages in reverse to track assistant positions
        for (int i = session.getMessages().size() - 1; i >= 0; i--) {
            Message msg = session.getMessages().get(i);

            if ("assistant".equals(msg.getRole())) {
                assistantCount++;
                if (assistantCount <= keepAssistant) {
                    continue; // preserve recent assistants and their tool results
                }
            }

            if (!"tool".equals(msg.getRole())) {
                continue;
            }

            // Check per-tool allow/deny
            String toolName = msg.getToolName();
            if (deny != null && deny.contains(toolName)) continue;
            if (allow != null && !allow.isEmpty() && !allow.contains(toolName)) continue;

            String content = msg.getContent();
            if (content == null || content.length() < config.getMinPrunableToolChars()) {
                continue;
            }

            Instant msgTime = msg.getTimestamp();
            if (msgTime == null) continue;

            if (Duration.between(msgTime, now).compareTo(ttl) > 0) {
                // This tool result is stale
                if (content.length() > config.getSoftTrim().getMaxChars() * config.getSoftTrimRatio()) {
                    // Soft trim
                    msg.setContent(softTrim(content));
                    modified++;
                }
                // TODO: hard clear based on total ratio
            }
        }

        log.debug("ContextPruner: modified {} messages in session {}",
                modified, session.getSessionId());
        return modified;
    }

    private String softTrim(String content) {
        var st = config.getSoftTrim();
        if (content.length() <= st.getMaxChars()) {
            return content;
        }
        return content.substring(0, st.getHeadChars())
                + "\n... [trimmed " + (content.length() - st.getHeadChars() - st.getTailChars())
                + " chars] ...\n"
                + content.substring(content.length() - st.getTailChars());
    }
}
```

### 3.1.4 AgentContextLimits

Hard limits enforced during context construction to prevent individual
components from consuming disproportionate context space.

```java
package lyjew.com.lyclaw.compaction;

import lombok.Builder;
import lombok.Data;

/**
 * Hard limits for individual context components.
 *
 * <p>These limits are enforced at context-build time, before the
 * context reaches the LLM. They complement the dynamic CompactionEngine
 * by providing static upper bounds.</p>
 *
 * <p>Mapped from {@code lyclaw.compaction.limits} in application.yml.</p>
 */
@Data
@Builder
public class AgentContextLimits {

    /** Max chars returned from MemorySystem per retrieval call. Default: 12000. */
    @Builder.Default
    int memoryGetMaxChars = 12000;

    /** Default number of memory lines retrieved. Default: 120. */
    @Builder.Default
    int memoryGetDefaultLines = 120;

    /** Max chars for any single tool result in the transcript. Default: 16000. */
    @Builder.Default
    int toolResultMaxChars = 16000;

    /**
     * Max chars for post-compaction injected section content.
     * Each section in postCompactionSections is truncated to this.
     * Default: 1800.
     */
    @Builder.Default
    int postCompactionMaxChars = 1800;

    /**
     * Truncate a tool result to toolResultMaxChars.
     *
     * @param content raw tool output
     * @return truncated content with a note if truncation occurred
     */
    public String truncateToolResult(String content) {
        if (content == null || content.length() <= toolResultMaxChars) {
            return content;
        }
        return content.substring(0, toolResultMaxChars)
                + "\n... [truncated " + (content.length() - toolResultMaxChars)
                + " chars; total was " + content.length() + "]";
    }

    /**
     * Truncate a post-compaction section to postCompactionMaxChars.
     */
    public String truncatePostCompactionSection(String content) {
        if (content == null || content.length() <= postCompactionMaxChars) {
            return content;
        }
        return content.substring(0, postCompactionMaxChars) + "...";
    }
}
```

### 3.1.5 Pipeline Integration

#### CompactionStage

A new pipeline stage that sits after ReflectionStage and before MetricsStage.

```java
package lyjew.com.lyclaw.compaction;

import lyjew.com.lyclaw.annotation.PipelineStage;
import lyjew.com.lyclaw.pipeline.ReactivePipelineStage;
import lyjew.com.lyclaw.react.AgentContext;
import lyjew.com.lyclaw.react.AgentHook;
import lyjew.com.lyclaw.model.Session;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.codec.ServerSentEvent;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * Pipeline stage that checks for context-window pressure and triggers
 * compaction when needed.
 *
 * <p>Ordered after ReflectionStage (which may have produced final evaluation
 * data worth preserving) and before MetricsStage (which records final
 * session stats).</p>
 */
@PipelineStage(
    name = "compaction",
    after = {ReflectionStage.class},
    before = {MetricsStage.class},
    group = "POSTPROCESSING"
)
public class CompactionStage implements ReactivePipelineStage {

    private static final Logger log = LoggerFactory.getLogger(CompactionStage.class);

    private final CompactionEngine compactionEngine;
    private final CompactionConfig config;
    private final List<AgentHook> hooks;
    private final AgentContextLimits limits;

    public CompactionStage(CompactionEngine compactionEngine, CompactionConfig config,
                           List<AgentHook> hooks, AgentContextLimits limits) {
        this.compactionEngine = compactionEngine;
        this.config = config;
        this.hooks = hooks;
        this.limits = limits;
    }

    @Override
    public Flux<ServerSentEvent<String>> execute(AgentContext ctx) {
        if (ctx.isTerminated()) {
            return Flux.empty();
        }

        Session session = ctx.getAttribute("session");
        if (session == null) {
            return Flux.empty();
        }

        return Flux.defer(() -> {
            if (!compactionEngine.needsCompaction(session, config)) {
                return Flux.empty();
            }

            log.info("Compaction triggered for session {}", session.getSessionId());

            // 1. Dispatch beforeCompaction hooks
            hooks.forEach(h -> h.beforeCompaction(ctx));

            // 2. Run compaction
            return compactionEngine.compact(session, config, ctx)
                    .flatMapMany(result -> {
                        // 3. Validate (quality guard)
                        return compactionEngine.validateCompaction(result, config)
                                .flatMapMany(valid -> {
                                    if (!valid) {
                                        log.warn("Compaction validation failed for session {}",
                                                session.getSessionId());
                                        // Could retry or fall back to truncation
                                    }

                                    // 4. Inject post-compaction sections
                                    injectPostCompactionSections(ctx, session);

                                    // 5. Dispatch afterCompaction hooks
                                    hooks.forEach(h -> h.afterCompaction(ctx, result));

                                    // 6. Notify user if configured
                                    if (config.isNotifyUser()) {
                                        return Flux.just(
                                                ServerSentEvent.<String>builder()
                                                        .event("compaction")
                                                        .data("{\"status\":\"complete\","
                                                                + "\"sessionId\":\"" + session.getSessionId() + "\","
                                                                + "\"messagesCompacted\":" + result.middleMessages() + "}")
                                                        .build()
                                        );
                                    }
                                    return Flux.empty();
                                });
                    })
                    .doOnError(e -> log.error("Compaction failed for session {}",
                            session.getSessionId(), e))
                    .onErrorResume(e -> Flux.empty()); // Never block the pipeline
        });
    }

    private void injectPostCompactionSections(AgentContext ctx, Session session) {
        // Inject configured post-compaction sections as system messages
        // ...
    }

    @Override
    public int getOrder() { return 500; }

    @Override
    public String getStageName() { return "compaction"; }
}
```

#### Hook Extensions

Add new methods to `AgentHook` for compaction lifecycle:

```java
// Added to AgentHook interface:
public interface AgentHook {

    // ... existing methods ...

    /**
     * Called before compaction begins. Hooks can save critical state,
     * disable tool pruning, or signal external systems.
     */
    default void beforeCompaction(AgentContext ctx) {}

    /**
     * Called after compaction completes successfully.
     * Hooks can verify critical instructions are preserved.
     *
     * @param ctx    agent context
     * @param result compaction result with metrics
     */
    default void afterCompaction(AgentContext ctx, CompactionResult result) {}
}
```

#### Mid-Turn Compaction Trigger

In `DefaultReActEngine`, between tool execution rounds, check context pressure:

```java
// Inside DefaultReActEngine.continueReActRounds() or similar loop:

// After each tool round, check mid-turn context pressure
if (compactionEngine != null) {
    Boolean needsCompaction = compactionEngine.midTurnPrecheck(ctx).block();
    if (Boolean.TRUE.equals(needsCompaction)) {
        log.warn("Mid-turn compaction needed; pausing ReAct loop");
        // Emit pause event, compact, then resume
        // ...
    }
}
```

#### ContextPruningScheduler

A background scheduler that periodically runs ContextPruner:

```java
package lyjew.com.lyclaw.compaction;

import lyjew.com.lyclaw.model.Session;
import lyjew.com.lyclaw.storage.StoreLayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

import java.time.Instant;

/**
 * Background scheduler that periodically prunes stale tool results
 * when context pruning mode is CACHE_TTL.
 */
public class ContextPruningScheduler {

    private static final Logger log = LoggerFactory.getLogger(ContextPruningScheduler.class);

    private final ContextPruner pruner;
    private final ContextPruningConfig config;
    private final StoreLayer storeLayer;

    public ContextPruningScheduler(ContextPruner pruner, ContextPruningConfig config,
                                   StoreLayer storeLayer) {
        this.pruner = pruner;
        this.config = config;
        this.storeLayer = storeLayer;
    }

    /**
     * Run pruning every 5 minutes. Only active when mode != OFF.
     */
    @Scheduled(fixedRate = 300_000)
    public void pruneActiveSessions() {
        if (config.getMode() == ContextPruningConfig.PruningMode.OFF) {
            return;
        }

        Instant now = Instant.now();
        var sessions = storeLayer.getActiveSessions(); // needs StoreLayer extension
        int totalModified = 0;

        for (Session session : sessions) {
            try {
                int modified = pruner.prune(session, now);
                totalModified += modified;
            } catch (Exception e) {
                log.warn("Pruning failed for session {}: {}",
                        session.getSessionId(), e.getMessage());
            }
        }

        if (totalModified > 0) {
            log.info("ContextPruningScheduler: modified {} messages across {} sessions",
                    totalModified, sessions.size());
        }
    }
}
```

### 3.1.6 YAML Configuration

```yaml
lyclaw:
  # ── Compaction ──────────────────────────────────────────
  compaction:
    # Enable/disable compaction engine entirely
    enabled: true

    # Compaction mode: DEFAULT | SAFEGUARD
    mode: DEFAULT

    # Token reservations
    reserve-tokens: 8000
    keep-recent-tokens: 4000
    reserve-tokens-floor: 2000

    # Trigger when history exceeds this share of total tokens
    max-history-share: 0.5

    # Custom LLM instructions for the compaction prompt
    custom-instructions: ""

    # Preserve N most recent turns verbatim
    recent-turns-preserve: 3

    # Identifier handling: STRICT | OFF | CUSTOM
    identifier-policy: STRICT

    # Model override for compaction (null = use session model)
    model: deepseek-v4-flash

    # Timeout for a single compaction LLM call (seconds)
    timeout-seconds: 900

    # Truncate trailing content after compaction
    truncate-after-compaction: false

    # Max active transcript bytes before triggering compaction
    max-active-transcript-bytes: 10485760  # 10MB

    # Notify user via SSE when compaction runs
    notify-user: false

    # ── Quality Guard ───────────────────────────────────
    quality-guard:
      enabled: true
      max-retries: 2

    # ── Mid-Turn Precheck ───────────────────────────────
    mid-turn-precheck:
      enabled: true

    # ── Post-Compaction Index Sync ──────────────────────
    # OFF | ASYNC | AWAIT
    post-index-sync: ASYNC

    # ── Memory Flush (before compaction) ────────────────
    memory-flush:
      enabled: true
      # model: deepseek-v4-flash  # null = use compaction model
      soft-threshold-tokens: 4000
      force-flush-transcript-bytes: 512000  # 500KB
      # prompt: ""
      # system-prompt: ""

    # Post-compaction sections to re-inject
    post-compaction-sections:
      - "Session Startup"
      - "Red Lines"

    # ── Context Pruning ─────────────────────────────────
    pruning:
      # Pruning mode: OFF | CACHE_TTL
      mode: OFF

      # TTL for tool results (ISO 8601 duration)
      ttl: PT30M

      # Keep last N assistant messages unpruned
      keep-last-assistants: 5

      # Soft trim ratio (relative to context budget)
      soft-trim-ratio: 0.3

      # Hard clear ratio
      hard-clear-ratio: 0.6

      # Minimum chars for a tool result to be prunable
      min-prunable-tool-chars: 1000

      # Tool allow/deny lists
      tool-allow: []
      tool-deny:
        - file_read
        - file_search

      # Soft trim params
      soft-trim:
        max-chars: 8000
        head-chars: 2000
        tail-chars: 2000

      # Hard clear params
      hard-clear:
        enabled: true
        placeholder: "[earlier output trimmed for space]"

    # ── Context Limits ──────────────────────────────────
    limits:
      memory-get-max-chars: 12000
      memory-get-default-lines: 120
      tool-result-max-chars: 16000
      post-compaction-max-chars: 1800
```

---

## 3.2 Workspace Bootstrap

### Motivation

Today LyClaw has no agent-specific bootstrap files. Every session starts with a
minimal system prompt. With bootstrap files, each agent can have a rich, persistent
identity: system prompt additions (AGENTS.md), personality (SOUL.md), one-time setup
(BOOTSTRAP.md), identity description (IDENTITY.md), user preferences (USER.md),
and heartbeat prompt (HEARTBEAT.md).

### 3.2.1 Bootstrap Files Structure

```
{agentDir}/
  AGENTS.md      — System prompt additions (always injected)
  SOUL.md        — Agent personality, values, voice guidelines
  BOOTSTRAP.md   — One-time setup instructions (run once, then skipped)
  IDENTITY.md    — Agent identity description (name, role, background)
  USER.md        — User context, preferences, custom instructions
  HEARTBEAT.md   — Heartbeat prompt section (periodic self-check)
```

**File Semantics:**

| File          | Injection       | Description |
|---------------|-----------------|-------------|
| `AGENTS.md`   | Every turn      | Core system prompt augmentation. Tool instructions, safety rules, output format. Cannot be skipped. |
| `SOUL.md`     | Every turn      | Personality and values. Defines the agent's "voice" — tone, verbosity, stylistic preferences. |
| `BOOTSTRAP.md`| Once (on first `/new` or `/reset`) | One-time initialization instructions. Executed only on session start. |
| `IDENTITY.md` | Every turn      | Who the agent is. Name, role, backstory. Displayed in UI. |
| `USER.md`     | Every turn      | User-specific context. Preferences, custom instructions, known facts about the user. |
| `HEARTBEAT.md`| Every N minutes | Periodic self-check prompt. Encourages the agent to reflect on goal progress. |

### 3.2.2 BootstrapConfig

```java
package lyjew.com.lyclaw.bootstrap;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * Configuration for the Workspace Bootstrap system.
 *
 * <p>Controls which bootstrap files are loaded, how they're injected,
 * and size limits to prevent context-window overflow.</p>
 *
 * <p>Mapped from {@code lyclaw.bootstrap} in application.yml.</p>
 */
@Data
@Builder
public class BootstrapConfig {

    /** Skip all bootstrap loading entirely. Default: false. */
    @Builder.Default
    boolean skipBootstrap = false;

    /**
     * List of optional bootstrap files to skip.
     * Even when bootstrap is enabled, these specific files are ignored.
     * Example: ["SOUL.md", "HEARTBEAT.md"].
     */
    List<String> skipOptionalBootstrapFiles;

    /**
     * When to inject bootstrap content into the context.
     * Default: ALWAYS.
     */
    @Builder.Default
    ContextInjectionPolicy contextInjection = ContextInjectionPolicy.ALWAYS;

    /** Max characters per individual bootstrap file. Default: 20000. */
    @Builder.Default
    int bootstrapMaxChars = 20000;

    /** Max total characters across all bootstrap files. Default: 150000. */
    @Builder.Default
    int bootstrapTotalMaxChars = 150000;

    /**
     * Truncation warning policy.
     * ONCE: warn once per session when content is truncated.
     * ALWAYS: warn every time.
     * NEVER: suppress warnings.
     */
    @Builder.Default
    BootstrapTruncationWarning truncationWarning = BootstrapTruncationWarning.ONCE;

    /** Startup context configuration. */
    @Builder.Default
    StartupContextConfig startupContext = new StartupContextConfig();

    /**
     * Agent directory path. If null, defaults to {@code ${user.dir}/agents/{agentId}}.
     */
    String agentDir;

    /**
     * Workspace directory path. If null, defaults to {@code ${user.dir}}.
     */
    String workspaceDir;
}
```

```java
package lyjew.com.lyclaw.bootstrap;

public enum ContextInjectionPolicy {
    /** Inject bootstrap files on every turn. */
    ALWAYS,
    /**
     * Skip bootstrap injection on continuation turns.
     * Only inject on /new, /reset, or session start.
     */
    CONTINUATION_SKIP,
    /** Never inject bootstrap files (for testing). */
    NEVER
}

public enum BootstrapTruncationWarning {
    /** Warn once per session when content exceeds limits. */
    ONCE,
    /** Warn on every turn. */
    ALWAYS,
    /** Never warn. */
    NEVER
}
```

```java
package lyjew.com.lyclaw.bootstrap;

import lombok.Builder;
import lombok.Data;

/**
 * Startup context: file listing, directory structure, recent changes
 * injected at session start to give the agent situational awareness.
 */
@Data
@Builder
public class StartupContextConfig {

    /** Enable startup context injection. Default: true. */
    @Builder.Default
    boolean enabled = true;

    /**
     * When to apply startup context.
     * FIRST_TURN: only on the session's first turn.
     * EVERY_RESET: on /new and /reset.
     * EVERY_TURN: every turn (verbose, not recommended).
     */
    @Builder.Default
    StartupContextApplyOn applyOn = StartupContextApplyOn.FIRST_TURN;

    /** Days of daily memory to include in startup context. Default: 3. */
    @Builder.Default
    int dailyMemoryDays = 3;

    /** Max file bytes when listing directory contents. Default: 500KB. */
    @Builder.Default
    long maxFileBytes = 500 * 1024;

    /** Max files to list in a single directory. Default: 200. */
    @Builder.Default
    int maxFilesPerDir = 200;

    /** Max total dir-list chars in startup context. Default: 8000. */
    @Builder.Default
    int maxDirListChars = 8000;
}

public enum StartupContextApplyOn {
    FIRST_TURN, EVERY_RESET, EVERY_TURN
}
```

### 3.2.3 BootstrapLoader

```java
package lyjew.com.lyclaw.bootstrap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Loads bootstrap files from the agent directory and applies
 * truncation, context injection policy, and size limits.
 *
 * <p>Bootstrap files are loaded from {@code {agentDir}/} and optionally
 * from {@code {workspaceDir}/} (e.g. for project-specific overrides).</p>
 */
public class BootstrapLoader {

    private static final Logger log = LoggerFactory.getLogger(BootstrapLoader.class);

    /** Files that are always loaded (cannot be in skipOptionalBootstrapFiles). */
    private static final Set<String> REQUIRED_FILES = Set.of("AGENTS.md");

    /** All known bootstrap file names. */
    private static final List<String> ALL_FILES = List.of(
            "AGENTS.md", "SOUL.md", "BOOTSTRAP.md",
            "IDENTITY.md", "USER.md", "HEARTBEAT.md"
    );

    private final BootstrapConfig config;

    public BootstrapLoader(BootstrapConfig config) {
        this.config = config;
    }

    /**
     * Load all bootstrap files for an agent.
     *
     * @param agentDir     absolute path to the agent directory (e.g. /home/lyclaw/agents/coder)
     * @param workspaceDir absolute path to the workspace directory (optional, may be null)
     * @param config       bootstrap configuration
     * @return loaded bootstrap content
     */
    public BootstrapContent loadBootstrap(String agentDir, String workspaceDir,
                                          BootstrapConfig config) {
        if (config.isSkipBootstrap()) {
            return BootstrapContent.empty();
        }

        Path agentPath = Path.of(agentDir);
        Path workspacePath = workspaceDir != null ? Path.of(workspaceDir) : null;

        Map<String, String> loaded = new LinkedHashMap<>();
        Set<String> skip = config.getSkipOptionalBootstrapFiles() != null
                ? Set.copyOf(config.getSkipOptionalBootstrapFiles()) : Set.of();

        int totalChars = 0;

        for (String fileName : ALL_FILES) {
            // Respect skip list (but never skip AGENTS.md)
            if (skip.contains(fileName) && !REQUIRED_FILES.contains(fileName)) {
                continue;
            }

            // Try agentDir first
            Path filePath = agentPath.resolve(fileName);
            String content = readFile(filePath);

            // Fallback to workspaceDir (for project-level overrides like USER.md)
            if (content == null && workspacePath != null) {
                content = readFile(workspacePath.resolve(fileName));
            }

            if (content != null) {
                // Apply per-file truncation
                content = truncate(content, config.getBootstrapMaxChars(),
                        config.getBootstrapTotalMaxChars() - totalChars);
                loaded.put(fileName, content);
                totalChars += content.length();
            }
        }

        // Apply total limit across all files
        if (totalChars > config.getBootstrapTotalMaxChars()) {
            loaded = enforceTotalLimit(loaded, config.getBootstrapTotalMaxChars());
        }

        boolean truncated = totalChars > config.getBootstrapTotalMaxChars();
        log.info("BootstrapLoader: loaded {} files, {} total chars{} for agent dir {}",
                loaded.size(), totalChars, truncated ? " (TRUNCATED)" : "", agentDir);

        return new BootstrapContent(loaded, truncated);
    }

    /**
     * Build the injection string that will be prepended/appended to the
     * system prompt based on the configured ContextInjectionPolicy.
     *
     * @param content loaded bootstrap content
     * @param policy  injection policy
     * @return formatted injection string
     */
    public String buildContextInjection(BootstrapContent content,
                                        ContextInjectionPolicy policy) {
        if (policy == ContextInjectionPolicy.NEVER) {
            return "";
        }

        StringBuilder sb = new StringBuilder();

        // AGENTS.md always first
        String agents = content.getFile("AGENTS.md");
        if (agents != null) {
            sb.append(agents).append("\n\n");
        }

        // IDENTITY.md
        String identity = content.getFile("IDENTITY.md");
        if (identity != null) {
            sb.append(identity).append("\n\n");
        }

        // SOUL.md
        String soul = content.getFile("SOUL.md");
        if (soul != null) {
            sb.append(soul).append("\n\n");
        }

        // USER.md
        String user = content.getFile("USER.md");
        if (user != null) {
            sb.append(user).append("\n\n");
        }

        // HEARTBEAT.md (if applicable)
        String heartbeat = content.getFile("HEARTBEAT.md");
        if (heartbeat != null) {
            sb.append(heartbeat).append("\n\n");
        }

        // Truncation warning
        if (content.isTruncated()
                && config.getTruncationWarning() != BootstrapTruncationWarning.NEVER) {
            sb.append("> Note: Some bootstrap content was truncated to fit within context limits. "
                    + "Key instructions are preserved.\n\n");
        }

        return sb.toString().trim();
    }

    /**
     * Truncate content to respect both per-file and total limits.
     */
    public String truncate(String content, int maxChars, int remainingBudget) {
        int limit = Math.min(maxChars, remainingBudget);
        if (content == null) return null;
        if (content.length() <= limit) return content;
        return content.substring(0, limit - 30)
                + "\n... [truncated; exceeded limit]\n";
    }

    // ── Internal helpers ───────────────────────────────────────────

    private String readFile(Path path) {
        try {
            if (Files.exists(path) && Files.isReadable(path)) {
                return Files.readString(path, StandardCharsets.UTF_8);
            }
        } catch (IOException e) {
            log.warn("BootstrapLoader: failed to read {}: {}", path, e.getMessage());
        }
        return null;
    }

    private Map<String, String> enforceTotalLimit(Map<String, String> loaded, int totalLimit) {
        Map<String, String> result = new LinkedHashMap<>();
        int remaining = totalLimit;
        for (var entry : loaded.entrySet()) {
            if (remaining <= 0) break;
            String value = entry.getValue();
            if (value.length() > remaining) {
                value = value.substring(0, remaining - 30)
                        + "\n... [truncated; total bootstrap limit reached]\n";
            }
            result.put(entry.getKey(), value);
            remaining -= value.length();
        }
        return result;
    }
}
```

```java
package lyjew.com.lyclaw.bootstrap;

import java.util.Collections;
import java.util.Map;

/**
 * Immutable container for loaded bootstrap file content.
 */
public class BootstrapContent {

    private final Map<String, String> files;
    private final boolean truncated;

    public BootstrapContent(Map<String, String> files, boolean truncated) {
        this.files = Collections.unmodifiableMap(files);
        this.truncated = truncated;
    }

    /** Get the content of a specific bootstrap file, or null if not loaded. */
    public String getFile(String fileName) {
        return files.get(fileName);
    }

    /** All loaded files (file name -> content). */
    public Map<String, String> getFiles() { return files; }

    /** Whether any file was truncated to fit within limits. */
    public boolean isTruncated() { return truncated; }

    /** Number of loaded files. */
    public int fileCount() { return files.size(); }

    /** Total character count across all loaded files. */
    public int totalChars() {
        return files.values().stream().mapToInt(String::length).sum();
    }

    public static BootstrapContent empty() {
        return new BootstrapContent(Map.of(), false);
    }
}
```

### 3.2.4 ContextInjectionPolicy

See the enum above. Key behavior:

- **ALWAYS**: Bootstrap content is injected into the system prompt on every single turn. This ensures the agent always has its identity and instructions, at the cost of token usage.
- **CONTINUATION_SKIP**: Content is injected on the first turn of a new session (/new, /reset) but skipped on continuation turns. Reduces token cost for long sessions where the agent has already internalized its identity.
- **NEVER**: Never inject. Useful for testing or when all setup is done via the system prompt in ChatRequest directly.

### 3.2.5 Pipeline Integration

The existing `ContextBuildStage` is enhanced to load and inject bootstrap content:

```java
// Inside ContextBuildStage (enhanced):

@PipelineStage(name = "contextBuild", group = "PREPROCESSING")
public class ContextBuildStage implements ReactivePipelineStage {

    private final ContextBuilder contextBuilder;
    private final BootstrapLoader bootstrapLoader;   // NEW
    private final BootstrapConfig bootstrapConfig;    // NEW
    private final IdentityConfig identityConfig;      // NEW (see §3.4)

    // ... constructor ...

    @Override
    public Flux<ServerSentEvent<String>> execute(AgentContext ctx) {
        if (ctx.isTerminated()) return Flux.empty();

        String agentId = ctx.getAttribute("agentId"); // set by AgentRouter
        String agentDir = bootstrapConfig.getAgentDir() != null
                ? bootstrapConfig.getAgentDir()
                : resolveAgentDir(agentId);

        // 1. Load bootstrap content
        BootstrapContent bootstrap = bootstrapLoader.loadBootstrap(
                agentDir, bootstrapConfig.getWorkspaceDir(), bootstrapConfig);

        // 2. Determine injection policy
        ContextInjectionPolicy policy = bootstrapConfig.getContextInjection();
        boolean isContinuation = ctx.getAttribute("isContinuation") != null
                && (Boolean) ctx.getAttribute("isContinuation");
        if (policy == ContextInjectionPolicy.CONTINUATION_SKIP && isContinuation) {
            policy = ContextInjectionPolicy.NEVER;
        }

        // 3. Build injection string
        String injection = bootstrapLoader.buildContextInjection(bootstrap, policy);

        // 4. Inject into system prompt
        String enrichedSystemPrompt = buildEnrichedSystemPrompt(
                ctx.getSystemPrompt(), injection, identityConfig);

        ctx.setSystemPrompt(enrichedSystemPrompt);

        // ... continue with existing context building logic ...

        return Flux.empty();
    }

    private String buildEnrichedSystemPrompt(String basePrompt, String bootstrapInjection,
                                             IdentityConfig identity) {
        StringBuilder sb = new StringBuilder();
        if (bootstrapInjection != null && !bootstrapInjection.isEmpty()) {
            sb.append(bootstrapInjection).append("\n\n");
        }
        if (basePrompt != null && !basePrompt.isEmpty()) {
            sb.append(basePrompt);
        }
        // Apply identity prefixes (see §3.4)
        if (identity != null && identity.getNamePrefix() != null) {
            sb.insert(0, identity.getNamePrefix() + "\n");
        }
        return sb.toString();
    }

    private String resolveAgentDir(String agentId) {
        return System.getProperty("user.dir") + "/agents/" + agentId;
    }

    @Override
    public int getOrder() { return 10; }

    @Override
    public String getStageName() { return "contextBuild"; }
}
```

### 3.2.6 YAML Configuration

```yaml
lyclaw:
  # ── Bootstrap ─────────────────────────────────────────
  bootstrap:
    # Skip all bootstrap loading
    skip-bootstrap: false

    # Optional files to skip (AGENTS.md can never be skipped)
    skip-optional-bootstrap-files: []
    # Example: ["SOUL.md", "HEARTBEAT.md"]

    # Injection policy: ALWAYS | CONTINUATION_SKIP | NEVER
    context-injection: ALWAYS

    # Per-file and total limits
    bootstrap-max-chars: 20000
    bootstrap-total-max-chars: 150000

    # Truncation warning: ONCE | ALWAYS | NEVER
    truncation-warning: ONCE

    # Agent directory (null = ${user.dir}/agents/{agentId})
    agent-dir: null
    # Workspace directory (null = ${user.dir})
    workspace-dir: null

    # ── Startup Context ────────────────────────────────
    startup-context:
      enabled: true
      # FIRST_TURN | EVERY_RESET | EVERY_TURN
      apply-on: FIRST_TURN
      daily-memory-days: 3
      max-file-bytes: 512000   # 500KB
      max-files-per-dir: 200
      max-dir-list-chars: 8000
```

---

## 3.3 Agent Routing & Binding

### Motivation

LyClaw currently has a single `ChatController` that routes all traffic to one
`ChatAgent`. There is no mechanism to route incoming messages to different agents
based on channel (e.g. Discord #general vs #engineering), account, or peer identity.

The Agent Routing system adds:
1. **AgentRouteBinding** — maps a route (channel, account, peer, guild, roles) to an agent.
2. **AgentAcpBinding** — maps a route to an agent with ACP-specific overrides.
3. **AgentRouter** — resolves which agent handles an incoming request.

### 3.3.1 AgentBindingMatch

```java
package lyjew.com.lyclaw.routing;

import lombok.Builder;
import lombok.Data;

import java.util.Set;

/**
 * Match criteria for routing an incoming request to an agent.
 *
 * <p>All fields are optional. An empty/null field means "match anything".
 * Multiple non-null fields are AND-ed together. At least one field
 * must be non-null for a binding to be considered.</p>
 */
@Data
@Builder
public class AgentBindingMatch {

    /** Channel name to match (e.g. "general", "engineering"). */
    String channel;

    /** Account ID to match. */
    String accountId;

    /** Peer ID / user ID to match. */
    String peer;

    /** Guild / server ID to match. */
    String guildId;

    /** Team ID to match. */
    String teamId;

    /** Required roles (user must have ALL of these). */
    Set<String> roles;

    /**
     * Check if this match criteria is a superset of the given criteria.
     * Used for finding the most specific (narrowest) binding.
     */
    public int specificity() {
        int score = 0;
        if (channel != null && !channel.isEmpty()) score++;
        if (accountId != null && !accountId.isEmpty()) score++;
        if (peer != null && !peer.isEmpty()) score++;
        if (guildId != null && !guildId.isEmpty()) score++;
        if (teamId != null && !teamId.isEmpty()) score++;
        if (roles != null && !roles.isEmpty()) score++;
        return score;
    }

    /**
     * Check if this match matches the given request metadata.
     */
    public boolean matches(RequestMetadata meta) {
        if (channel != null && !channel.equals(meta.getChannel())) return false;
        if (accountId != null && !accountId.equals(meta.getAccountId())) return false;
        if (peer != null && !peer.equals(meta.getPeer())) return false;
        if (guildId != null && !guildId.equals(meta.getGuildId())) return false;
        if (teamId != null && !teamId.equals(meta.getTeamId())) return false;
        if (roles != null && !roles.isEmpty()) {
            if (meta.getRoles() == null || !meta.getRoles().containsAll(roles)) {
                return false;
            }
        }
        return true;
    }
}
```

```java
package lyjew.com.lyclaw.routing;

import lombok.Builder;
import lombok.Data;

import java.util.Set;

/**
 * Metadata extracted from an incoming request, used for agent routing.
 */
@Data
@Builder
public class RequestMetadata {

    String channel;       // e.g. "general"
    String accountId;     // e.g. Discord account ID
    String peer;          // user identifier
    String guildId;       // server/guild identifier
    String teamId;        // team identifier
    Set<String> roles;    // user roles

    /** Create empty metadata (matches the default/fallback route). */
    public static RequestMetadata empty() {
        return RequestMetadata.builder().build();
    }
}
```

### 3.3.2 AgentRouteBinding & AgentAcpBinding

```java
package lyjew.com.lyclaw.routing;

import lombok.Builder;
import lombok.Data;

/**
 * Base interface for agent bindings.
 */
public sealed interface AgentBinding
        permits AgentRouteBinding, AgentAcpBinding {

    String getType();
    String getAgentId();
    AgentBindingMatch getMatch();
}

/**
 * A route binding: maps a set of match criteria to an agent ID.
 *
 * <p>When a request's metadata matches the criteria, it is routed
 * to the specified agent.</p>
 */
@Data
@Builder
public final class AgentRouteBinding implements AgentBinding {

    @Builder.Default
    String type = "route";

    /** Target agent ID. */
    String agentId;

    /** Human-readable comment for this binding. */
    String comment;

    /** Match criteria (channel, account, peer, guild, team, roles). */
    AgentBindingMatch match;

    /** Session scope configuration. */
    @Builder.Default
    SessionScope session = new SessionScope();

    @Override
    public AgentBindingMatch getMatch() { return match; }

    /**
     * DM session scope: controls whether direct messages share a session
     * with the channel-bound session or get their own.
     */
    @Data
    public static class SessionScope {
        /**
         * Scope for direct messages.
         * SHARED: DM uses the same session as the channel route.
         * ISOLATED: DM gets its own session.
         */
        @Builder.Default
        DmScope dmScope = DmScope.SHARED;
    }

    public enum DmScope { SHARED, ISOLATED }
}

/**
 * An ACP (Agent Communication Protocol) binding: like RouteBinding but
 * with additional ACP-specific overrides.
 */
@Data
@Builder
public final class AgentAcpBinding implements AgentBinding {

    @Builder.Default
    String type = "acp";

    /** Target agent ID. */
    String agentId;

    /** Human-readable comment. */
    String comment;

    /** Match criteria. */
    AgentBindingMatch match;

    /** ACP-specific overrides. */
    @Builder.Default
    AcpOverrides acp = new AcpOverrides();

    @Override
    public AgentBindingMatch getMatch() { return match; }

    @Data
    public static class AcpOverrides {
        /** ACP mode. */
        String mode;

        /** ACP label for display. */
        String label;

        /** Working directory override for this binding. */
        String cwd;

        /** Backend override. */
        String backend;
    }
}
```

### 3.3.3 AgentRouter

```java
package lyjew.com.lyclaw.routing;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Resolves which agent should handle an incoming request based on
 * configured bindings.
 *
 * <h3>Resolution Algorithm</h3>
 * <ol>
 *   <li>Find all bindings whose {@link AgentBindingMatch} matches the request metadata.</li>
 *   <li>Select the most specific match (highest specificity score).</li>
 *   <li>If no match, return the default agent ID.</li>
 * </ol>
 *
 * <p>Bindings are typically loaded from YAML configuration
 * (see {@code lyclaw.routing.bindings}) or from annotations.</p>
 */
public class AgentRouter {

    private static final Logger log = LoggerFactory.getLogger(AgentRouter.class);

    private final List<AgentBinding> bindings;
    private final String defaultAgentId;

    public AgentRouter(List<AgentBinding> bindings, String defaultAgentId) {
        // Sort by specificity descending (most specific first)
        this.bindings = new ArrayList<>(bindings);
        this.bindings.sort(Comparator
                .<AgentBinding>comparingInt(b -> b.getMatch() != null
                        ? b.getMatch().specificity() : 0)
                .reversed());
        this.defaultAgentId = defaultAgentId;
        log.info("AgentRouter initialized: {} bindings, defaultAgent={}",
                bindings.size(), defaultAgentId);
    }

    /**
     * Resolve the agent ID for an incoming request.
     *
     * @param metadata request metadata (channel, account, peer, etc.)
     * @return agent ID to handle this request
     */
    public String resolveAgentId(RequestMetadata metadata) {
        if (metadata == null) {
            metadata = RequestMetadata.empty();
        }

        // Find the most specific matching binding
        for (AgentBinding binding : bindings) {
            AgentBindingMatch match = binding.getMatch();
            if (match == null) continue; // skip bindings with no match criteria

            if (match.matches(metadata)) {
                log.debug("AgentRouter: matched {} -> {} (specificity={})",
                        metadata.getChannel() != null ? "#" + metadata.getChannel() : "default",
                        binding.getAgentId(),
                        match.specificity());
                return binding.getAgentId();
            }
        }

        // No match — use default
        log.debug("AgentRouter: no match for channel={}, using default={}",
                metadata.getChannel(), defaultAgentId);
        return defaultAgentId;
    }

    /**
     * Resolve agent ID and return the full binding (for ACP overrides, etc.).
     */
    public AgentBinding resolveBinding(RequestMetadata metadata) {
        if (metadata == null) {
            metadata = RequestMetadata.empty();
        }

        for (AgentBinding binding : bindings) {
            AgentBindingMatch match = binding.getMatch();
            if (match != null && match.matches(metadata)) {
                return binding;
            }
        }

        // Return a synthetic route binding for the default agent
        return AgentRouteBinding.builder()
                .agentId(defaultAgentId)
                .comment("default route (fallback)")
                .match(AgentBindingMatch.builder().build())
                .build();
    }

    /**
     * Get the default agent ID.
     */
    public String getDefaultAgentId() {
        return defaultAgentId;
    }

    /**
     * Pattern-match support for shorthand notations like "#general" or "@botname".
     * <p>This is used by platforms (Discord, Slack) that route by channel/mention.</p>
     *
     * @param pattern shorthand pattern (e.g. "#general", "@coder-bot")
     * @return resolved agent ID, or null if not found
     */
    public String resolveByPattern(String pattern) {
        if (pattern == null) return null;

        // "#channel" notation
        if (pattern.startsWith("#")) {
            String channel = pattern.substring(1);
            return resolveAgentId(RequestMetadata.builder().channel(channel).build());
        }

        // "@agent" notation — look for an agent whose IDENTITY.md name matches
        // Or check if pattern matches an agentId directly
        for (AgentBinding binding : bindings) {
            if (pattern.equals(binding.getAgentId())) {
                return binding.getAgentId();
            }
        }

        return null;
    }

    /** Number of registered bindings. */
    public int bindingCount() {
        return bindings.size();
    }
}
```

### 3.3.4 ChatController Update

```java
package lyjew.com.lyclaw.web.controller;

import lyjew.com.lyclaw.model.ChatRequest;
import lyjew.com.lyclaw.model.Message;
import lyjew.com.lyclaw.model.Session;
import lyjew.com.lyclaw.routing.AgentRouter;
import lyjew.com.lyclaw.routing.RequestMetadata;
import lyjew.com.lyclaw.web.agent.ChatAgent;
import lyjew.com.lyclaw.web.agent.AgentRegistry; // or similar
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.List;

/**
 * Enhanced ChatController with multi-agent routing support.
 *
 * <p>Reads channel/account metadata from request headers and uses
 * {@link AgentRouter} to resolve the target agent before creating
 * the session context.</p>
 */
@RestController
@RequestMapping("/api")
public class ChatController {

    private final ChatAgent defaultChatAgent;
    private final AgentRouter agentRouter;
    private final Map<String, ChatAgent> agentRegistry; // agentId -> agent proxy

    public ChatController(ChatAgent defaultChatAgent, AgentRouter agentRouter,
                          Map<String, ChatAgent> agentRegistry) {
        this.defaultChatAgent = defaultChatAgent;
        this.agentRouter = agentRouter;
        this.agentRegistry = agentRegistry;
    }

    /**
     * Extract routing metadata from request headers.
     */
    private RequestMetadata extractMetadata(ChatRequest request) {
        return RequestMetadata.builder()
                .channel(request.getExtras() != null
                        ? (String) request.getExtras().get("channel") : null)
                .accountId(request.getExtras() != null
                        ? (String) request.getExtras().get("accountId") : null)
                .peer(request.getExtras() != null
                        ? (String) request.getExtras().get("peer") : null)
                .guildId(request.getExtras() != null
                        ? (String) request.getExtras().get("guildId") : null)
                .teamId(request.getExtras() != null
                        ? (String) request.getExtras().get("teamId") : null)
                .roles(extractRoles(request))
                .build();
    }

    @SuppressWarnings("unchecked")
    private Set<String> extractRoles(ChatRequest request) {
        if (request.getExtras() != null
                && request.getExtras().get("roles") instanceof List<?> list) {
            return Set.copyOf((List<String>) list);
        }
        return Set.of();
    }

    /**
     * Resolve the ChatAgent for this request based on routing metadata.
     */
    private ChatAgent resolveAgent(ChatRequest request) {
        RequestMetadata metadata = extractMetadata(request);
        String agentId = agentRouter.resolveAgentId(metadata);
        ChatAgent agent = agentRegistry.get(agentId);
        if (agent != null) {
            return agent;
        }
        return defaultChatAgent;
    }

    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> chatStream(@RequestBody ChatRequest request) {
        String userMessage = request.getLastUserMessage();
        ChatAgent agent = resolveAgent(request);
        return agent.chatStream(userMessage);
    }

    @PostMapping("/chat")
    public Mono<Map<String, Object>> chat(@RequestBody ChatRequest request) {
        String userMessage = request.getLastUserMessage();
        String sessionId = request.getSessionId() != null ? request.getSessionId() : "";
        ChatAgent agent = resolveAgent(request);
        return Mono.fromCallable(() -> agent.chat(userMessage))
                .subscribeOn(Schedulers.boundedElastic())
                .map(reply -> Map.of("content", reply, "sessionId", sessionId));
    }

    // ... session endpoints unchanged ...
}
```

### 3.3.5 YAML Configuration

```yaml
lyclaw:
  # ── Routing ───────────────────────────────────────────
  routing:
    # Default agent when no binding matches
    default-agent: default

    # ── Bindings ───────────────────────────────────────
    bindings:
      # Route binding: Discord #general channel -> "helper" agent
      - type: route
        agent-id: helper
        comment: "General chat assistant"
        match:
          channel: general
          guild-id: "111222333444"
        session:
          dm-scope: SHARED

      # Route binding: Discord #engineering channel -> "coder" agent
      - type: route
        agent-id: coder
        comment: "Engineering code assistant"
        match:
          channel: engineering
          guild-id: "111222333444"

      # Route binding: specific user gets "admin" agent
      - type: route
        agent-id: admin
        comment: "Admin assistant for power users"
        match:
          peer: "user-admin-001"
          roles: ["admin"]

      # ACP binding: with working directory override
      - type: acp
        agent-id: coder
        comment: "ACP binding for the coder agent"
        match:
          channel: dev-acp
        acp:
          mode: interactive
          label: "Dev ACP"
          cwd: /home/lyclaw/projects
          backend: openai-protocol

      # Catch-all fallback (matches anything, lowest specificity)
      - type: route
        agent-id: default
        comment: "Default fallback agent"
        match: {}
```

---

## 3.4 Identity & Avatar

### Motivation

Today LyClaw agents have no visual identity. They are just nameless text
responders. IdentityConfig adds display name, avatar, name prefix (e.g.,
"[CoderBot] "), response prefix, message prefix, and acknowledgment reactions.

### 3.4.1 IdentityConfig

```java
package lyjew.com.lyclaw.identity;

import lombok.Builder;
import lombok.Data;
import lyjew.com.lyclaw.bootstrap.BootstrapConfig;
import lyjew.com.lyclaw.bootstrap.BootstrapLoader;

/**
 * Agent identity and presentation configuration.
 *
 * <p>Controls how the agent appears in UI (name, avatar) and how its
 * messages are prefixed/annotated in the output stream.</p>
 *
 * <p>Mapped from {@code lyclaw.identity} in application.yml, or loaded
 * from the agent's IDENTITY.md bootstrap file.</p>
 */
@Data
@Builder
public class IdentityConfig {

    /** Display name shown in the UI. */
    String displayName;

    /** Avatar image URL (remote or data URI). */
    String avatarUrl;

    /** Avatar image file path (local). */
    String avatarFilePath;

    /**
     * Name prefix prepended to agent responses in chat.
     * Example: "[CoderBot] " -> "[CoderBot] Here is your code..."
     */
    String namePrefix;

    /**
     * Response prefix prepended to the final reply of each turn.
     * Unlike namePrefix (which goes before all output), this only
     * prefixes the final text response, not tool call notifications.
     */
    String responsePrefix;

    /**
     * Message prefix prepended to ALL messages from this agent,
     * including tool call SSE events and status updates.
     */
    String messagePrefix;

    /**
     * Emoji reaction used for acknowledgment messages.
     * Example: "eyes" or "white_check_mark".
     */
    String ackReaction;

    /**
     * Build a display label from identity config.
     */
    public String getDisplayLabel() {
        if (displayName != null && !displayName.isEmpty()) {
            return displayName;
        }
        return "Agent";
    }

    /**
     * Apply name prefix to a message string.
     */
    public String applyNamePrefix(String message) {
        if (namePrefix == null || namePrefix.isEmpty()) {
            return message;
        }
        if (message == null) return namePrefix;
        return namePrefix + message;
    }

    /**
     * Apply response prefix to the final response.
     */
    public String applyResponsePrefix(String response) {
        if (responsePrefix == null || responsePrefix.isEmpty()) {
            return response;
        }
        if (response == null) return responsePrefix;
        return responsePrefix + response;
    }

    /**
     * Apply message prefix to any message.
     */
    public String applyMessagePrefix(String message) {
        if (messagePrefix == null || messagePrefix.isEmpty()) {
            return message;
        }
        if (message == null) return messagePrefix;
        return messagePrefix + message;
    }
}
```

### 3.4.2 AvatarResolution

```java
package lyjew.com.lyclaw.identity;

/**
 * How the agent's avatar is sourced.
 */
public enum AvatarKind {
    /** No avatar available. */
    NONE,
    /** Avatar loaded from a local file. */
    LOCAL,
    /** Avatar loaded from a remote URL. */
    REMOTE,
    /** Avatar embedded as a data URI. */
    DATA
}

/**
 * Resolved avatar information with metadata about the resolution process.
 */
public class AgentAvatarResolution {

    private final AvatarKind kind;
    private final String reason;      // for NONE: why no avatar
    private final String filePath;    // for LOCAL: absolute path
    private final String url;         // for REMOTE, DATA: the URL/data URI
    private final String source;      // where the avatar was found (e.g. "IDENTITY.md", "config")

    public AgentAvatarResolution(AvatarKind kind, String reason, String filePath,
                                 String url, String source) {
        this.kind = kind;
        this.reason = reason;
        this.filePath = filePath;
        this.url = url;
        this.source = source;
    }

    public AvatarKind getKind() { return kind; }
    public String getReason() { return reason; }
    public String getFilePath() { return filePath; }
    public String getUrl() { return url; }
    public String getSource() { return source; }

    /**
     * Resolve avatar from IdentityConfig, trying each source in order:
     * avatarFilePath -> avatarUrl -> NONE.
     */
    public static AgentAvatarResolution resolve(IdentityConfig config) {
        // 1. Try local file
        if (config.getAvatarFilePath() != null && !config.getAvatarFilePath().isEmpty()) {
            java.nio.file.Path path = java.nio.file.Path.of(config.getAvatarFilePath());
            if (java.nio.file.Files.exists(path)) {
                return new AgentAvatarResolution(
                        AvatarKind.LOCAL, null,
                        config.getAvatarFilePath(), null,
                        "config.avatarFilePath");
            }
            return new AgentAvatarResolution(
                    AvatarKind.NONE,
                    "File not found: " + config.getAvatarFilePath(),
                    null, null, "config.avatarFilePath");
        }

        // 2. Try URL
        if (config.getAvatarUrl() != null && !config.getAvatarUrl().isEmpty()) {
            if (config.getAvatarUrl().startsWith("data:")) {
                return new AgentAvatarResolution(
                        AvatarKind.DATA, null,
                        null, config.getAvatarUrl(),
                        "config.avatarUrl");
            }
            return new AgentAvatarResolution(
                    AvatarKind.REMOTE, null,
                    null, config.getAvatarUrl(),
                    "config.avatarUrl");
        }

        // 3. Nothing found
        return new AgentAvatarResolution(
                AvatarKind.NONE, "No avatar configured", null, null, "none");
    }

    /** Convenience: is an avatar available? */
    public boolean isAvailable() {
        return kind != AvatarKind.NONE;
    }

    @Override
    public String toString() {
        return "AgentAvatarResolution{kind=" + kind
                + (reason != null ? ", reason='" + reason + "'" : "")
                + (filePath != null ? ", filePath='" + filePath + "'" : "")
                + (url != null ? ", url='" + url + "'" : "")
                + ", source='" + source + "'}";
    }
}
```

#### IdentityService

```java
package lyjew.com.lyclaw.identity;

import lyjew.com.lyclaw.bootstrap.BootstrapContent;
import lyjew.com.lyclaw.bootstrap.BootstrapLoader;

/**
 * Central service for resolving agent identity.
 *
 * <p>Identity is loaded from three sources (in priority order):
 * <ol>
 *   <li>Explicit YAML configuration ({@code lyclaw.identity})</li>
 *   <li>Bootstrap file IDENTITY.md</li>
 *   <li>Defaults derived from agentId</li>
 * </ol>
 */
public class IdentityService {

    private final IdentityConfig configuredIdentity; // from YAML
    private final BootstrapLoader bootstrapLoader;

    public IdentityService(IdentityConfig configuredIdentity, BootstrapLoader bootstrapLoader) {
        this.configuredIdentity = configuredIdentity;
        this.bootstrapLoader = bootstrapLoader;
    }

    /**
     * Resolve the effective identity for an agent.
     *
     * @param agentId   the agent's ID
     * @param agentDir  the agent's directory (for loading IDENTITY.md)
     * @return effective identity config
     */
    public IdentityConfig resolveIdentity(String agentId, String agentDir) {
        // Start with configured identity as base
        IdentityConfig.IdentityConfigBuilder builder = IdentityConfig.builder();

        if (configuredIdentity != null) {
            builder.displayName(configuredIdentity.getDisplayName())
                    .avatarUrl(configuredIdentity.getAvatarUrl())
                    .avatarFilePath(configuredIdentity.getAvatarFilePath())
                    .namePrefix(configuredIdentity.getNamePrefix())
                    .responsePrefix(configuredIdentity.getResponsePrefix())
                    .messagePrefix(configuredIdentity.getMessagePrefix())
                    .ackReaction(configuredIdentity.getAckReaction());
        }

        // Override with IDENTITY.md if available
        // (IDENTITY.md content follows a simple key: value format)
        // ... parse IDENTITY.md and apply overrides ...

        // Fallback display name
        if (builder.build().getDisplayName() == null) {
            builder.displayName(agentId);
        }

        return builder.build();
    }

    /**
     * Apply identity prefixes to an agent response.
     */
    public String applyIdentity(String response, IdentityConfig identity) {
        String result = response;
        result = identity.applyResponsePrefix(result);
        result = identity.applyNamePrefix(result);
        return result;
    }
}
```

### 3.4.3 Integration & YAML

#### Pipeline Integration

In `ContextBuildStage`, identity is resolved and stored in `AgentContext` for downstream stages to use:

```java
// In ContextBuildStage.execute():
IdentityConfig identity = identityService.resolveIdentity(agentId, agentDir);
ctx.setAttribute("identity", identity);
ctx.setAttribute("avatarResolution", AgentAvatarResolution.resolve(identity));
```

In `RespondStage` (or wherever the final response is emitted), identity prefixes are applied:

```java
// Before emitting the final response:
IdentityConfig identity = ctx.getAttribute("identity");
if (identity != null) {
    finalResponse = identityService.applyIdentity(finalResponse, identity);
}
```

#### YAML Configuration

```yaml
lyclaw:
  # ── Identity ──────────────────────────────────────────
  identity:
    # Display name shown in the UI
    display-name: "LyClaw Assistant"

    # Avatar URL (remote) or file path (local)
    avatar-url: null
    avatar-file-path: null

    # Prefixes applied to agent output
    name-prefix: null         # e.g. "[CoderBot] "
    response-prefix: null     # e.g. "Here's what I found:\n"
    message-prefix: null      # e.g. "🤖 "

    # Acknowledgment reaction emoji (for Discord/Slack adapters)
    ack-reaction: "eyes"
```

---

## Full YAML Configuration Reference

```yaml
lyclaw:
  # ================================================================
  #  Phase 3 — Context Engine, Bootstrap, Routing, Identity
  # ================================================================

  # ── 3.1 Compaction ────────────────────────────────────
  compaction:
    enabled: true
    mode: DEFAULT
    reserve-tokens: 8000
    keep-recent-tokens: 4000
    reserve-tokens-floor: 2000
    max-history-share: 0.5
    custom-instructions: ""
    recent-turns-preserve: 3
    identifier-policy: STRICT
    identifier-instructions: ""
    model: null
    timeout-seconds: 900
    truncate-after-compaction: false
    max-active-transcript-bytes: 10485760
    notify-user: false

    quality-guard:
      enabled: true
      max-retries: 2

    mid-turn-precheck:
      enabled: true

    post-index-sync: ASYNC

    memory-flush:
      enabled: true
      model: null
      soft-threshold-tokens: 4000
      force-flush-transcript-bytes: 512000
      prompt: null
      system-prompt: null

    post-compaction-sections:
      - "Session Startup"
      - "Red Lines"

    pruning:
      mode: OFF
      ttl: PT30M
      keep-last-assistants: 5
      soft-trim-ratio: 0.3
      hard-clear-ratio: 0.6
      min-prunable-tool-chars: 1000
      tool-allow: []
      tool-deny: [file_read, file_search]
      soft-trim:
        max-chars: 8000
        head-chars: 2000
        tail-chars: 2000
      hard-clear:
        enabled: true
        placeholder: "[earlier output trimmed for space]"

    limits:
      memory-get-max-chars: 12000
      memory-get-default-lines: 120
      tool-result-max-chars: 16000
      post-compaction-max-chars: 1800

  # ── 3.2 Bootstrap ─────────────────────────────────────
  bootstrap:
    skip-bootstrap: false
    skip-optional-bootstrap-files: []
    context-injection: ALWAYS
    bootstrap-max-chars: 20000
    bootstrap-total-max-chars: 150000
    truncation-warning: ONCE
    agent-dir: null
    workspace-dir: null

    startup-context:
      enabled: true
      apply-on: FIRST_TURN
      daily-memory-days: 3
      max-file-bytes: 512000
      max-files-per-dir: 200
      max-dir-list-chars: 8000

  # ── 3.3 Routing ───────────────────────────────────────
  routing:
    default-agent: default
    bindings: []
    # Example bindings:
    # - type: route
    #   agent-id: helper
    #   comment: "General chat assistant"
    #   match:
    #     channel: general
    #     guild-id: "111222333444"
    #   session:
    #     dm-scope: SHARED

  # ── 3.4 Identity ──────────────────────────────────────
  identity:
    display-name: "LyClaw Assistant"
    avatar-url: null
    avatar-file-path: null
    name-prefix: null
    response-prefix: null
    message-prefix: null
    ack-reaction: "eyes"
```

---

## Integration Checklist

### 3.1 Context Engine & Compaction

- [ ] Create `lyclaw-framework/src/main/java/lyjew/com/lyclaw/compaction/` package
- [ ] Implement `CompactionConfig` with all fields and builder
- [ ] Implement enums: `CompactionMode`, `IdentifierPolicy`, `PostIndexSync`
- [ ] Implement sub-configs: `QualityGuard`, `MidTurnPrecheck`, `MemoryFlush`
- [ ] Implement `CompactionEngine` with `needsCompaction()`, `compact()`, `validateCompaction()`, `midTurnPrecheck()`
- [ ] Implement `CompactionResult` record
- [ ] Implement `ContextPruningConfig` with `SoftTrim`, `HardClear`
- [ ] Implement `ContextPruner` with `prune()` method
- [ ] Implement `AgentContextLimits` with truncation helpers
- [ ] Create `CompactionStage` (`@PipelineStage`, after ReflectionStage, before MetricsStage)
- [ ] Add `beforeCompaction`/`afterCompaction` methods to `AgentHook` interface
- [ ] Implement `ContextPruningScheduler` with `@Scheduled`
- [ ] Add `CompactionProperties` to `LyClawConfigurationProperties` for YAML binding
- [ ] Wire in `CompactionAutoConfiguration` (or extend existing autoconfigure)
- [ ] Extend `StoreLayer` with `getActiveSessions()` for the pruning scheduler

### 3.2 Workspace Bootstrap

- [ ] Create `lyclaw-framework/src/main/java/lyjew/com/lyclaw/bootstrap/` package
- [ ] Implement `BootstrapConfig` with all fields and builder
- [ ] Implement enums: `ContextInjectionPolicy`, `BootstrapTruncationWarning`, `StartupContextApplyOn`
- [ ] Implement `StartupContextConfig`
- [ ] Implement `BootstrapLoader` with `loadBootstrap()` and `buildContextInjection()`
- [ ] Implement `BootstrapContent` immutable container
- [ ] Enhance `ContextBuildStage` to call `BootstrapLoader` and inject content
- [ ] Add `BootstrapProperties` to `LyClawConfigurationProperties` for YAML binding
- [ ] Wire in `BootstrapAutoConfiguration`
- [ ] Create example bootstrap files in `/agents/default/`

### 3.3 Agent Routing & Binding

- [ ] Create `lyclaw-framework/src/main/java/lyjew/com/lyclaw/routing/` package
- [ ] Implement `RequestMetadata` with channel, accountId, peer, guildId, teamId, roles
- [ ] Implement `AgentBindingMatch` with `matches()` and `specificity()`
- [ ] Implement sealed `AgentBinding` interface with `AgentRouteBinding` and `AgentAcpBinding`
- [ ] Implement `AgentRouter` with `resolveAgentId()`, `resolveBinding()`, `resolveByPattern()`
- [ ] Enhance `ChatController` to extract metadata from `ChatRequest.extras` and route to the resolved agent
- [ ] Add `RoutingProperties` to `LyClawConfigurationProperties` for YAML binding
- [ ] Wire in `RoutingAutoConfiguration`

### 3.4 Identity & Avatar

- [ ] Create `lyclaw-framework/src/main/java/lyjew/com/lyclaw/identity/` package
- [ ] Implement `IdentityConfig` with displayName, avatar, prefixes, ackReaction
- [ ] Implement `AvatarKind` enum and `AgentAvatarResolution` with `resolve()`
- [ ] Implement `IdentityService` with `resolveIdentity()` and `applyIdentity()`
- [ ] Enhance `ContextBuildStage` to call `IdentityService` and store identity in `AgentContext`
- [ ] Apply identity prefixes in `RespondStage` before emitting final response
- [ ] Add `IdentityProperties` to `LyClawConfigurationProperties` for YAML binding
- [ ] Wire in `IdentityAutoConfiguration`

### Cross-Cutting

- [ ] Update `application.yml` with the full configuration reference
- [ ] Add unit tests for `CompactionEngine`, `BootstrapLoader`, `AgentRouter`, `IdentityService`
- [ ] Add integration tests for the full pipeline with compaction and bootstrap
- [ ] Document the new SSE events: `compaction`, identity metadata
- [ ] Update Actuator endpoints (`LyClawConfigEndpoint`, `LyClawPipelineEndpoint`) to expose new config sections

---

# Phase 4: Streaming & Gateway Enhancement + Sandbox + Heartbeat + Run Retries

## Overview

Phase 4 targets four high-impact subsystems that underpin LyClaw's production readiness:
1. **Block Streaming & Human Delay** — replaces naive `splitIntoEvents()` in `DefaultReActEngine` with boundary-aware block streaming, coalescing, human typing simulation, and typing indicators.
2. **Container Sandbox** — upgrades `ToolSandbox` / `SandboxLevel=PROCESS` to Docker/Podman-backed isolation with filesystem bridging, resource limits, and `SandboxExecutionService`.
3. **Agent Heartbeat** — introduces a cron-like scheduler that can ping agents periodically, produce `heartbeat_*` SSE events, and deliver isolated-session turn results.
4. **Run Retries Enhancement** — replaces the hardcoded `maxRetries` in `ReflexionLoop` with `RunRetryManager`, per-fallback-profile budgeting, and retry-strategy selection.

All new code lives under existing packages:
- Streaming configs → `lyjew.com.lyclaw.config`
- Block streaming logic → `lyjew.com.lyclaw.react.stream`
- Sandbox → `lyjew.com.lyclaw.security.sandbox`
- Heartbeat → `lyjew.com.lyclaw.react.heartbeat`
- Run retries → `lyjew.com.lyclaw.react.retry`

---

## Table of Contents

1. [4.1 Block Streaming Enhancement](#41-block-streaming-enhancement)
2. [4.2 Sandbox Enhancement](#42-sandbox-enhancement)
3. [4.3 Heartbeat System](#43-heartbeat-system)
4. [4.4 Run Retries Enhancement](#44-run-retries-enhancement)
5. [Integration Diagram](#integration-diagram)
6. [SSE Event Schema Reference](#sse-event-schema-reference)

---

## 4.1 Block Streaming Enhancement

### 4.1.1 Motivation

The current `DefaultReActEngine.splitIntoEvents(String text)` splits at Chinese punctuation boundaries (`\n`, `。`, `！`, `？`, `；`) and emits each segment as a single SSE `message` event. This works for short replies but has several issues:

- **No block awareness**: Does not understand LLM natural text boundaries (paragraphs, code fences, lists).
- **No coalescing**: A single-character chunk creates a separate SSE frame — wasteful.
- **No human delay**: All events arrive at once, giving no sense of "the AI is typing."
- **No typing indicator**: Frontend cannot show a "thinking" or "typing" state during response generation.

Phase 4 introduces a layered streaming pipeline inside `RespondStage` and `DefaultReActEngine`:

```
LLM token stream
  → BlockStreamingChunk (soft boundary detection)
    → BlockStreamingCoalesce (merge small blocks)
      → HumanDelay (inter-block stagger)
        → TypingIndicator (periodic "typing" events)
          → SSE emit
```

### 4.1.2 Configuration

#### BlockStreamingConfig

```java
package lyjew.com.lyclaw.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Block-based streaming configuration.
 * <p>Controls how LLM token streams are chunked and delivered to SSE clients.
 * Replaces the simple splitIntoEvents() with boundary-aware, coalesced, human-delayed streaming.
 */
@ConfigurationProperties(prefix = "lyclaw.streaming.block")
public class BlockStreamingConfig {

    /** Enable block-based streaming. When false, falls back to legacy splitIntoEvents(). */
    private boolean enabled = false;

    /**
     * When to break streaming blocks.
     * <ul>
     *   <li>TEXT_END — break after each complete text segment (paragraph, list item, etc.)</li>
     *   <li>MESSAGE_END — break only at end of entire assistant message</li>
     * </ul>
     */
    private BlockStreamingBreak breakMode = BlockStreamingBreak.TEXT_END;

    /** Soft block chunking configuration. */
    private BlockStreamingChunk chunk = new BlockStreamingChunk();

    /** Block reply coalescing configuration. */
    private BlockStreamingCoalesce coalesce = new BlockStreamingCoalesce();

    /** Maximum characters per individual chunk frame. */
    private int maxChunkChars = 2000;

    /** If true, suppress repeated identical text blocks. */
    private boolean repeatSuppression = true;

    /**
     * Streaming delivery mode.
     * <ul>
     *   <li>LIVE — emit blocks as they are formed (default)</li>
     *   <li>FINAL_ONLY — buffer everything, emit single event at end</li>
     * </ul>
     */
    private StreamingDeliveryMode deliveryMode = StreamingDeliveryMode.LIVE;

    /**
     * Hidden boundary separator for multi-block messages.
     * Inserted as an invisible delimiter between blocks for clients that parse responses.
     */
    private HiddenBoundarySeparator hiddenBoundary = HiddenBoundarySeparator.NEWLINE;

    // getters and setters omitted for brevity

    public enum BlockStreamingBreak { TEXT_END, MESSAGE_END }
    public enum StreamingDeliveryMode { LIVE, FINAL_ONLY }
    public enum HiddenBoundarySeparator { NEWLINE, NULL_CHAR, NONE }
}
```

#### BlockStreamingChunk

```java
package lyjew.com.lyclaw.config;

/**
 * Soft block chunking configuration.
 * <p>Chunking means deciding where to cut the token stream into discrete blocks.
 * This is "soft" because chunks can be coalesced later.
 */
public class BlockStreamingChunk {

    /**
     * Soft maximum characters per chunk (bytes for CJK text).
     * A chunk will be flushed when it exceeds this size,
     * but the actual boundary is still subject to preferNewlines.
     */
    private int maxChars = 500;

    /**
     * Maximum idle time (ms) before flushing the current chunk.
     * If no new tokens arrive for this duration, the accumulated chunk is emitted.
     */
    private int maxIdleMs = 1000;

    /**
     * If true, prefer splitting at newline boundaries (\n, \r\n, \n\n).
     * When a newline is encountered and the current chunk is at least 50% of maxChars,
     * the chunk is flushed at that boundary regardless of exact size.
     */
    private boolean preferNewlines = true;

    /**
     * When preferNewlines is true, the minimum fill percentage (0.0-1.0)
     * before a newline triggers flush.
     */
    private double newlineFlushThreshold = 0.5;

    // getters and setters omitted
}
```

#### BlockStreamingCoalesce

```java
package lyjew.com.lyclaw.config;

/**
 * Block reply coalescing configuration.
 * <p>Coalescing merges multiple small blocks into one larger block before SSE delivery.
 * This reduces the number of SSE frames and improves network efficiency.
 */
public class BlockStreamingCoalesce {

    /** Enable block coalescing. */
    private boolean enabled = true;

    /** Maximum characters in a coalesced block before forced flush. */
    private int maxChars = 8000;

    /**
     * Maximum idle time (ms) before flushing the coalesced buffer.
     * If no new blocks arrive for this duration, the accumulated content is emitted.
     */
    private int maxIdleMs = 3000;

    // getters and setters omitted
}
```

#### HumanDelayConfig

```java
package lyjew.com.lyclaw.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Human-like typing delay configuration.
 * <p>Introduces variable delays between streaming blocks to simulate
 * natural typing speed, improving UX in chat interfaces.
 */
@ConfigurationProperties(prefix = "lyclaw.streaming.human-delay")
public class HumanDelayConfig {

    /** Enable human-like delay simulation. */
    private boolean enabled = false;

    /** Minimum delay between blocks (ms). */
    private int minDelayMs = 200;

    /** Maximum delay between blocks (ms). */
    private int maxDelayMs = 1500;

    /**
     * Simulated typing speed in characters per second.
     * Used to calculate dynamic delay: delayMs = blockChars / charsPerSecond * 1000.
     * Typical human typing speed is 40-80 CPS; 50 is a natural default.
     */
    private int charsPerSecond = 50;

    /**
     * If true, adaptive speed adjusts typing rate for long replies.
     * The agent "speeds up" as response length grows to avoid excessive wait times.
     */
    private boolean adaptiveSpeed = true;

    /**
     * Character threshold for triggering the speed-up adjustment.
     * When the total accumulated response exceeds this, charsPerSecond is
     * gradually increased (up to 3x) for remaining blocks.
     */
    private int longReplyThreshold = 2000;

    // getters and setters omitted
}
```

### 4.1.3 BlockStreamingController

This is the core component that replaces `splitIntoEvents()`.

```java
package lyjew.com.lyclaw.react.stream;

import lyjew.com.lyclaw.config.BlockStreamingConfig;
import lyjew.com.lyclaw.config.HumanDelayConfig;
import org.springframework.http.codec.ServerSentEvent;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Block-based streaming controller that replaces DefaultReActEngine.splitIntoEvents().
 *
 * <p>Converts a raw text response into a boundary-aware, coalesced, human-delayed
 * Flux of SSE events. Integrates with RespondStage's streaming pipeline.</p>
 *
 * <h3>Processing pipeline:</h3>
 * <ol>
 *   <li>Parse raw text into blocks at natural boundaries</li>
 *   <li>Coalesce small adjacent blocks</li>
 *   <li>Apply human delay between blocks</li>
 *   <li>Apply repeat suppression</li>
 *   <li>Emit SSE message events</li>
 * </ol>
 */
public class BlockStreamingController {

    private static final ObjectMapper objectMapper = new ObjectMapper();

    private final BlockStreamingConfig config;
    private final HumanDelayConfig humanDelayConfig;
    private final TypingIndicatorController typingIndicator;

    // Track previously emitted text for repeat suppression
    private String lastEmittedBlock = "";

    // Track total emitted chars for adaptive speed
    private int totalEmittedChars = 0;

    public BlockStreamingController(BlockStreamingConfig config,
                                     HumanDelayConfig humanDelayConfig,
                                     TypingIndicatorController typingIndicator) {
        this.config = config;
        this.humanDelayConfig = humanDelayConfig;
        this.typingIndicator = typingIndicator;
    }

    /**
     * Convert a complete text response into a block-streamed Flux of SSE events.
     * Used when tool calls are detected and the ReAct loop produces a final text response.
     *
     * @param text the complete assistant response text
     * @return Flux of SSE message events
     */
    public Flux<ServerSentEvent<String>> streamResponse(String text) {
        if (!config.isEnabled() || text == null || text.isEmpty()) {
            return Flux.empty();
        }

        return Flux.defer(() -> {
            List<String> blocks = segmentIntoBlocks(text);
            if (blocks.isEmpty()) {
                return Flux.empty();
            }

            blocks = coalesceBlocks(blocks);
            blocks = applyRepeatSuppression(blocks);

            if (config.getDeliveryMode() == BlockStreamingConfig.StreamingDeliveryMode.FINAL_ONLY) {
                String joined = joinWithHiddenBoundary(blocks);
                return Flux.just(sseMessage(joined));
            }

            // LIVE mode: emit blocks with human delay
            return Flux.fromIterable(blocks)
                    .concatMap(block ->
                            Mono.just(sseMessage(block))
                                    .delayElement(calculateDelay(block))
                    );
        });
    }

    /**
     * Segment raw text into blocks at natural boundaries.
     *
     * <p>Boundary detection recognizes:
     * <ul>
     *   <li>Paragraph breaks (double newline) — strongest boundary</li>
     *   <li>Code fences (```), list items (-, *, 1.) — strong boundary</li>
     *   <li>Table rows (|) — strong boundary</li>
     *   <li>Sentence endings (.!?。) — medium boundary</li>
     *   <li>Newline — weak boundary</li>
     *   <li>Comma/colon — soft boundary (only if approaching maxChars)</li>
     * </ul></p>
     */
    List<String> segmentIntoBlocks(String text) {
        BlockStreamingConfig.BlockStreamingBreak breakMode = config.getBreakMode();
        int maxChars = config.getChunk().getMaxChars();
        boolean preferNewlines = config.getChunk().isPreferNewlines();
        double newlineThreshold = config.getChunk().getNewlineFlushThreshold();

        List<String> blocks = new ArrayList<>();
        StringBuilder buf = new StringBuilder();

        // First pass: split by double newlines (paragraph breaks — strongest boundary)
        String[] paragraphs = text.split("\\n\\s*\\n", -1);

        for (int p = 0; p < paragraphs.length; p++) {
            String paragraph = paragraphs[p];
            if (paragraph.isEmpty()) {
                if (p > 0 && p < paragraphs.length - 1) {
                    // Empty paragraph = intentional blank line, add as separator
                    blocks.add("\n\n");
                }
                continue;
            }

            // Within each paragraph, split by strong boundaries
            int i = 0;
            while (i < paragraph.length()) {
                char c = paragraph.charAt(i);
                buf.append(c);

                boolean shouldFlush = false;

                if (breakMode == BlockStreamingConfig.BlockStreamingBreak.MESSAGE_END) {
                    // Only flush at paragraph boundaries
                    shouldFlush = false;
                } else if (buf.length() >= maxChars) {
                    // Hard flush at maxChars
                    shouldFlush = true;
                } else if (c == '\n' && preferNewlines
                        && buf.length() >= (int)(maxChars * newlineThreshold)) {
                    // Soft flush at newline when sufficiently full
                    shouldFlush = true;
                } else if (isStrongBoundary(c, paragraph, i)) {
                    // Strong boundary character
                    shouldFlush = buf.length() >= 20; // avoid single-char blocks
                } else if (isMediumBoundary(c) && buf.length() >= (int)(maxChars * 0.5)) {
                    // Medium boundary when > 50% full
                    shouldFlush = true;
                }

                if (shouldFlush) {
                    blocks.add(buf.toString().trim());
                    buf.setLength(0);
                }
                i++;
            }
        }

        // Flush remaining
        if (buf.length() > 0) {
            String rem = buf.toString().trim();
            if (!rem.isEmpty()) {
                blocks.add(rem);
            }
        }

        return blocks;
    }

    /**
     * Coalesce small adjacent blocks into larger ones.
     */
    List<String> coalesceBlocks(List<String> blocks) {
        BlockStreamingCoalesce c = config.getCoalesce();
        if (!c.isEnabled() || blocks.size() <= 1) {
            return blocks;
        }

        List<String> coalesced = new ArrayList<>();
        StringBuilder buffer = new StringBuilder();

        for (String block : blocks) {
            if (buffer.length() + block.length() > c.getMaxChars()) {
                // Buffer would overflow — flush it
                coalesced.add(buffer.toString().trim());
                buffer.setLength(0);
            }
            if (buffer.length() > 0) {
                buffer.append(config.getHiddenBoundary() ==
                        BlockStreamingConfig.HiddenBoundarySeparator.NEWLINE ? "\n" : "");
            }
            buffer.append(block);
        }

        if (buffer.length() > 0) {
            coalesced.add(buffer.toString().trim());
        }

        return coalesced;
    }

    /**
     * Remove repeated identical blocks.
     */
    List<String> applyRepeatSuppression(List<String> blocks) {
        if (!config.isRepeatSuppression() || blocks.isEmpty()) {
            return blocks;
        }

        List<String> filtered = new ArrayList<>();
        for (String block : blocks) {
            if (!block.equals(lastEmittedBlock)) {
                filtered.add(block);
                lastEmittedBlock = block;
            }
        }
        return filtered;
    }

    /**
     * Calculate human delay for a block.
     */
    Duration calculateDelay(String block) {
        if (!humanDelayConfig.isEnabled()) {
            return Duration.ZERO;
        }

        int charsPerSec = humanDelayConfig.getCharsPerSecond();

        if (humanDelayConfig.isAdaptiveSpeed() && totalEmittedChars > humanDelayConfig.getLongReplyThreshold()) {
            // Speed up for long replies: gradually increase CPS up to 3x
            double excessRatio = Math.min(1.0,
                    (double)(totalEmittedChars - humanDelayConfig.getLongReplyThreshold())
                            / humanDelayConfig.getLongReplyThreshold());
            charsPerSec = (int)(charsPerSec * (1.0 + excessRatio * 2.0));
        }

        // Base delay proportional to block length
        int baseDelayMs = (int)((double)block.length() / charsPerSec * 1000);

        // Clamp between min and max
        int delayMs = Math.max(humanDelayConfig.getMinDelayMs(),
                Math.min(humanDelayConfig.getMaxDelayMs(), baseDelayMs));

        // Add small random jitter (±20%)
        double jitter = 0.8 + Math.random() * 0.4;
        delayMs = (int)(delayMs * jitter);

        totalEmittedChars += block.length();
        return Duration.ofMillis(delayMs);
    }

    /**
     * Join blocks with the configured hidden boundary separator.
     */
    String joinWithHiddenBoundary(List<String> blocks) {
        String sep;
        switch (config.getHiddenBoundary()) {
            case NULL_CHAR: sep = " "; break;
            case NONE: sep = ""; break;
            default: sep = "\n";
        }
        return String.join(sep, blocks);
    }

    private boolean isStrongBoundary(char c, String text, int pos) {
        // Heading markers: # at line start
        if (c == '#') {
            return pos == 0 || (pos > 0 && text.charAt(pos - 1) == '\n');
        }
        // Code fence backticks: ```
        if (c == '`' && text.length() > pos + 2
                && text.charAt(pos + 1) == '`' && text.charAt(pos + 2) == '`') {
            return true;
        }
        // Horizontal rule: ---, ***, ___
        if ((c == '-' || c == '*' || c == '_') && text.length() > pos + 2) {
            boolean hr = text.charAt(pos + 1) == c && text.charAt(pos + 2) == c;
            if (hr) {
                return pos == 0 || (pos > 0 && text.charAt(pos - 1) == '\n');
            }
        }
        return false;
    }

    private boolean isMediumBoundary(char c) {
        return c == '\n' || c == '。' || c == '！' || c == '？'
                || c == '.' || c == '!' || c == '?' || c == '；' || c == ';';
    }

    private ServerSentEvent<String> sseMessage(String data) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", "message");
        payload.put("content", data);
        try {
            return ServerSentEvent.<String>builder()
                    .event("message")
                    .data(objectMapper.writeValueAsString(payload))
                    .build();
        } catch (Exception e) {
            return ServerSentEvent.<String>builder().event("message").data(data).build();
        }
    }
}
```

### 4.1.4 TypingIndicatorController

```java
package lyjew.com.lyclaw.react.stream;

import lyjew.com.lyclaw.react.AgentContext;
import org.springframework.http.codec.ServerSentEvent;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Controls typing indicator SSE events sent to the client during
 * agent processing gaps (tool execution, thinking, etc.).
 *
 * <p>Usage in RespondStage:
 * <pre>{@code
 *   Flux<ServerSentEvent<String>> typingFlux = typingIndicator.startTyping(ctx);
 *   Flux<ServerSentEvent<String>> bodyFlux = reactWithReActEngine(ctx, traceId, toolDefs);
 *   return bodyFlux.takeUntilOther(typingIndicator.stopSignal())
 *                  .mergeWith(typingFlux);
 * }</pre>
 */
public class TypingIndicatorController {

    private static final ObjectMapper objectMapper = new ObjectMapper();

    private final TypingMode mode;
    private final int intervalSeconds;
    private volatile boolean active = false;

    public enum TypingMode {
        /** Never send typing indicators. */
        NEVER,
        /** Send a typing indicator immediately upon entering a processing gap. */
        INSTANT,
        /** Send typing indicators at intervals during "thinking" phases. */
        THINKING,
        /** Send typing indicators at intervals during message generation. */
        MESSAGE
    }

    public TypingIndicatorController(TypingMode mode, int intervalSeconds) {
        this.mode = mode;
        this.intervalSeconds = intervalSeconds;
    }

    /**
     * Returns a Flux that emits typing indicator SSE events at the configured interval.
     * Events are automatically stopped when stopTyping() is called.
     *
     * @param ctx the agent context for which to emit typing indicators
     * @return Flux of "typing" SSE events, emitted every intervalSeconds
     */
    public Flux<ServerSentEvent<String>> startTyping(AgentContext ctx) {
        if (mode == TypingMode.NEVER) {
            return Flux.empty();
        }
        active = true;
        return Flux.interval(Duration.ZERO, Duration.ofSeconds(intervalSeconds))
                .takeWhile(tick -> active)
                .map(tick -> buildTypingEvent(ctx));
    }

    /**
     * Stop emitting typing indicators. The Flux from startTyping() will complete
     * on its next tick.
     */
    public void stopTyping() {
        this.active = false;
    }

    private ServerSentEvent<String> buildTypingEvent(AgentContext ctx) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", "typing");
        payload.put("agentId", ctx.getSessionId());  // sessionId serves as agentId
        payload.put("stage", ctx.getCurrentStage().get());
        try {
            return ServerSentEvent.<String>builder()
                    .event("typing")
                    .data(objectMapper.writeValueAsString(payload))
                    .build();
        } catch (Exception e) {
            return ServerSentEvent.<String>builder()
                    .event("typing")
                    .data("{\"type\":\"typing\"}")
                    .build();
        }
    }
}
```

### 4.1.5 Integration with RespondStage

The modified `RespondStage` integrates block streaming as follows:

```java
// Inside RespondStage.reactWithReActEngine():
//
// Before (current):
//   return reActEngine.executeStream(chatFacade, request, toolExecutor);
//
// After (Phase 4):
//   BlockStreamingController streamingCtrl = streamingControllerFactory.get(ctx);
//   TypingIndicatorController typingCtrl = typingControllerFactory.get(ctx);
//
//   Flux<ServerSentEvent<String>> typingFlux = typingCtrl.startTyping(ctx);
//   Flux<ServerSentEvent<String>> rawStream = reActEngine.executeStream(chatFacade, request, toolExecutor);
//
//   return rawStream
//       .flatMap(event -> {
//           if ("message".equals(event.event()) && event.data() != null) {
//               String data = event.data();
//               // If the event is a final text block (not streaming token), apply block streaming
//               if (isBlockCandidates(data)) {
//                   streamingCtrl.reset();
//                   return streamingCtrl.streamResponse(data);
//               }
//               // Otherwise pass through as-is (streaming tokens are already granular)
//               return Flux.just(event);
//           }
//           return Flux.just(event);
//       })
//       .doOnTerminate(typingCtrl::stopTyping)
//       .mergeWith(typingFlux);
```

And in `DefaultReActEngine`, the `splitIntoEvents()` method is replaced by delegating to `BlockStreamingController`:

```java
// In DefaultReActEngine, replace:
//   private Flux<ServerSentEvent<String>> splitIntoEvents(String text) { ... }
//
// With:
//   private final BlockStreamingController streamingController;
//
//   private Flux<ServerSentEvent<String>> streamFinalText(String text) {
//       if (streamingController != null) {
//           return streamingController.streamResponse(text);
//       }
//       // Legacy fallback
//       // ... (old splitIntoEvents logic kept for backward compat)
//   }
```

### 4.1.6 YAML Configuration

```yaml
# application.yml — block streaming configuration
lyclaw:
  streaming:
    block:
      enabled: true
      break-mode: TEXT_END       # TEXT_END | MESSAGE_END
      chunk:
        max-chars: 500
        max-idle-ms: 1000
        prefer-newlines: true
        newline-flush-threshold: 0.5
      coalesce:
        enabled: true
        max-chars: 8000
        max-idle-ms: 3000
      max-chunk-chars: 2000
      repeat-suppression: true
      delivery-mode: LIVE        # LIVE | FINAL_ONLY
      hidden-boundary: NEWLINE   # NEWLINE | NULL_CHAR | NONE
    human-delay:
      enabled: true
      min-delay-ms: 200
      max-delay-ms: 1500
      chars-per-second: 50
      adaptive-speed: true
      long-reply-threshold: 2000
    typing-indicator:
      mode: THINKING             # NEVER | INSTANT | THINKING | MESSAGE
      interval-seconds: 5
```

---

## 4.2 Sandbox Enhancement

### 4.2.1 Motivation

The current sandbox system (via `ToolSandbox` interface and `SandboxLevel` enum) supports:
- `DIRECT` — execute on current thread (read-only tools)
- `SANDBOX` — daemon thread + temporary working directory
- `PROCESS` — independent OS process via `CommandExecutor`

What is missing:
- **Container isolation**: No Docker/Podman support; `PROCESS` level still runs as a child of the JVM process.
- **Resource limits**: No memory/CPU/timeout enforcement at the OS level.
- **Filesystem bridging**: No bidirectional file transfer between host and sandbox.
- **Health monitoring**: `ToolSandbox.isHealthy()` is not backed by actual container health checks.
- **Network control**: No way to disable network access for untrusted code.

### 4.2.2 AgentSandboxConfig

```java
package lyjew.com.lyclaw.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * Container-based sandbox configuration.
 * <p>Controls Docker/Podman container settings for tool execution isolation.
 */
@ConfigurationProperties(prefix = "lyclaw.sandbox")
public class AgentSandboxConfig {

    /**
     * Sandbox backend provider.
     * <ul>
     *   <li>NONE — no container isolation (use legacy process sandbox)</li>
     *   <li>DOCKER — use docker-java SDK</li>
     *   <li>PODMAN — use podman CLI (compatible with rootless setups)</li>
     * </ul>
     */
    private SandboxBackend backend = SandboxBackend.NONE;

    /** Container image to use for sandbox execution. */
    private String image = "ubuntu:22.04";

    /** Root directory inside the container for sandbox operations. */
    private String rootDir = "/sandbox";

    /** Command whitelist: only these commands may execute inside the sandbox. */
    private List<String> allowedCommands = new ArrayList<>();

    /** Command blacklist: these commands are explicitly forbidden. */
    private List<String> deniedCommands = new ArrayList<>();

    /** Whether the sandbox container has network access. Default false for security. */
    private boolean networkEnabled = false;

    /** Whether the sandbox container can write to the filesystem. */
    private boolean fileSystemWriteEnabled = true;

    /** Memory limit in MB for the container. */
    private long memoryLimitMb = 512;

    /** CPU limit in cores (can be fractional). */
    private double cpuLimit = 1.0;

    /** Maximum execution time for a single tool call in seconds. */
    private int timeoutSeconds = 300;

    /** Filesystem bridge configuration. */
    private SandboxFsBridge fsBridge = new SandboxFsBridge();

    /** Container startup timeout in seconds. */
    private int startupTimeoutSeconds = 30;

    /** If true, reuse containers across tool calls within the same session. */
    private boolean reuseContainer = true;

    /** Maximum container idle time in seconds before automatic cleanup. */
    private int containerIdleTimeoutSeconds = 600;

    /** Docker socket path (default: unix:///var/run/docker.sock). */
    private String dockerSocket = "unix:///var/run/docker.sock";

    /** Podman socket path for podman backend. */
    private String podmanSocket = "unix:///run/podman/podman.sock";

    // getters and setters omitted

    public enum SandboxBackend { NONE, DOCKER, PODMAN }
}
```

#### SandboxFsBridge (inner config)

```java
/**
 * Filesystem bridge configuration for host-sandbox file sharing.
 */
public class SandboxFsBridge {

    /** Host workspace directory to bridge into the sandbox (read-only). */
    private String hostWorkspace = "./workspace";

    /** Path inside the container where host workspace is mounted. */
    private String sandboxWorkspace = "/workspace";

    /** Whether the workspace mount is read-only inside the container. */
    private boolean workspaceReadOnly = true;

    /** Host temp directory for sandbox writable files. */
    private String hostTmp = "./sandbox-tmp";

    /** Path inside the container for writable temp files. */
    private String sandboxTmp = "/tmp/sandbox";

    /** Maximum size in MB for the tmp volume. */
    private long tmpMaxSizeMb = 500;

    // getters and setters omitted
}
```

### 4.2.3 SandboxExecutionService

```java
package lyjew.com.lyclaw.security.sandbox;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.*;
import com.github.dockerjava.api.model.*;
import com.github.dockerjava.core.DefaultDockerClientConfig;
import com.github.dockerjava.core.DockerClientImpl;
import com.github.dockerjava.httpclient5.ApacheDockerHttpClient;
import com.github.dockerjava.transport.DockerHttpClient;

import lyjew.com.lyclaw.config.AgentSandboxConfig;
import lyjew.com.lyclaw.tool.Tool;
import lyjew.com.lyclaw.tool.ToolExecutionResult;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Container-backed sandbox execution service.
 *
 * <p>Manages Docker/Podman container lifecycle for isolated tool execution.
 * Integrates with SandboxHook (replacing direct ToolSandbox delegation for
 * SandboxLevel.PROCESS when container backend is configured).
 *
 * <h3>Lifecycle:</h3>
 * <ol>
 *   <li>createSandbox(config) — pull image, create container, start it</li>
 *   <li>executeInSandbox(handle, tool, args) — execute tool via docker exec</li>
 *   <li>isHealthy(handle) — check container running status</li>
 *   <li>destroy(handle) — stop and remove container</li>
 * </ol>
 */
public class SandboxExecutionService {

    private static final Logger log = LoggerFactory.getLogger(SandboxExecutionService.class);

    private final AgentSandboxConfig config;
    private final DockerClient dockerClient;
    private final Map<String, SandboxHandle> activeHandles = new ConcurrentHashMap<>();

    public SandboxExecutionService(AgentSandboxConfig config) {
        this.config = config;
        this.dockerClient = config.getBackend() == AgentSandboxConfig.SandboxBackend.DOCKER
                ? buildDockerClient(config) : null;
    }

    // ── Docker Client Factory ──────────────────────────────────────────

    private DockerClient buildDockerClient(AgentSandboxConfig config) {
        DefaultDockerClientConfig clientConfig = DefaultDockerClientConfig.createDefaultConfigBuilder()
                .withDockerHost(config.getDockerSocket())
                .build();

        DockerHttpClient httpClient = new ApacheDockerHttpClient.Builder()
                .dockerHost(clientConfig.getDockerHost())
                .sslConfig(clientConfig.getSSLConfig())
                .maxConnections(100)
                .connectionTimeout(Duration.ofSeconds(30))
                .responseTimeout(Duration.ofSeconds(config.getTimeoutSeconds() + 10))
                .build();

        return DockerClientImpl.getInstance(clientConfig, httpClient);
    }

    // ── Sandbox Lifecycle ──────────────────────────────────────────────

    /**
     * Create and start a sandbox container.
     *
     * @param sessionId the session this sandbox belongs to
     * @return Mono emitting the SandboxHandle on success
     */
    public Mono<SandboxHandle> createSandbox(String sessionId) {
        if (config.getBackend() == AgentSandboxConfig.SandboxBackend.NONE) {
            return Mono.just(SandboxHandle.none());
        }

        return Mono.fromCallable(() -> {
            String containerName = "lyclaw-sandbox-" + sessionId + "-" + UUID.randomUUID().toString().substring(0, 8);

            log.info("Creating sandbox container: name={} image={}", containerName, config.getImage());

            // Pull image if not present
            try {
                dockerClient.pullImageCmd(config.getImage()).start().awaitCompletion();
            } catch (Exception e) {
                log.warn("Image pull failed (may already exist locally): {}", e.getMessage());
            }

            // Build host config with resource limits
            HostConfig hostConfig = HostConfig.newHostConfig()
                    .withMemory(config.getMemoryLimitMb() * 1024 * 1024) // bytes
                    .withNanoCPUs((long)(config.getCpuLimit() * 1_000_000_000L))
                    .withNetworkMode(config.isNetworkEnabled() ? "bridge" : "none")
                    .withReadonlyRootfs(!config.isFileSystemWriteEnabled())
                    .withAutoRemove(true);

            // Mount volumes
            List<com.github.dockerjava.api.model.Bind> binds = new ArrayList<>();

            // Workspace mount (read-only if configured)
            Path hostWorkspace = Paths.get(config.getFsBridge().getHostWorkspace())
                    .toAbsolutePath().normalize();
            Files.createDirectories(hostWorkspace);
            String workspaceMode = config.getFsBridge().isWorkspaceReadOnly() ? "ro" : "rw";
            binds.add(new Bind(hostWorkspace.toString(),
                    new com.github.dockerjava.api.model.Volume(config.getFsBridge().getSandboxWorkspace()),
                    AccessMode.valueOf(workspaceMode)));

            // Tmp mount (read-write)
            Path hostTmp = Paths.get(config.getFsBridge().getHostTmp())
                    .toAbsolutePath().normalize();
            Files.createDirectories(hostTmp);
            binds.add(new Bind(hostTmp.toString(),
                    new com.github.dockerjava.api.model.Volume(config.getFsBridge().getSandboxTmp()),
                    AccessMode.rw));

            hostConfig.withBinds(binds);

            // Create container
            CreateContainerCmd createCmd = dockerClient.createContainerCmd(config.getImage())
                    .withName(containerName)
                    .withHostConfig(hostConfig)
                    .withWorkingDir(config.getRootDir())
                    .withCmd("sleep", "infinity") // keep container alive
                    .withAttachStdin(false)
                    .withAttachStdout(true)
                    .withAttachStderr(true);

            CreateContainerResponse createResp = createCmd.exec();
            String containerId = createResp.getId();

            // Start container
            dockerClient.startContainerCmd(containerId).exec();

            // Wait for container to be ready
            boolean ready = waitForContainerReady(containerId, config.getStartupTimeoutSeconds());
            if (!ready) {
                throw new RuntimeException("Sandbox container failed to start within timeout: " + containerName);
            }

            SandboxHandle handle = new SandboxHandle(sessionId, containerId, containerName);
            activeHandles.put(sessionId, handle);

            log.info("Sandbox container started: containerId={} name={}", containerId, containerName);
            return handle;
        }).subscribeOn(Schedulers.boundedElastic());
    }

    // ── Tool Execution ─────────────────────────────────────────────────

    /**
     * Execute a tool inside the sandbox container.
     *
     * @param handle the sandbox to execute in
     * @param tool   the tool definition
     * @param args   tool arguments
     * @return Mono emitting the execution result
     */
    public Mono<ToolExecutionResult> executeInSandbox(SandboxHandle handle, Tool tool,
                                                       Map<String, Object> args) {
        if (handle.isNone()) {
            return Mono.just(ToolExecutionResult.failure("No sandbox container available"));
        }

        return Mono.fromCallable(() -> {
            // Build the docker exec command
            String[] cmd = buildExecCommand(tool, args);

            // Validate against allow/deny lists
            if (!isCommandAllowed(cmd[0])) {
                return ToolExecutionResult.failure("Command '" + cmd[0] + "' is not allowed in sandbox");
            }

            ExecCreateCmdResponse execCreate = dockerClient.execCreateCmd(handle.getContainerId())
                    .withCmd(cmd)
                    .withAttachStdout(true)
                    .withAttachStderr(true)
                    .withTty(false)
                    .exec();

            // Capture output
            StringBuilder stdout = new StringBuilder();
            StringBuilder stderr = new StringBuilder();

            try {
                dockerClient.execStartCmd(execCreate.getId())
                        .withDetach(false)
                        .exec(new com.github.dockerjava.api.async.ResultCallback.Adapter<>() {
                            @Override
                            public void onNext(com.github.dockerjava.api.model.Frame frame) {
                                String text = new String(frame.getPayload());
                                if (frame.getStreamType() == com.github.dockerjava.api.model.StreamType.STDOUT) {
                                    stdout.append(text);
                                } else {
                                    stderr.append(text);
                                }
                            }
                        })
                        .awaitCompletion(config.getTimeoutSeconds(), java.util.concurrent.TimeUnit.SECONDS);
            } catch (Exception e) {
                log.error("Sandbox execution timed out or failed: {}", e.getMessage());
                return ToolExecutionResult.failure("Sandbox execution error: " + e.getMessage());
            }

            // Check exit code
            InspectExecResponse execInspect = dockerClient.inspectExecCmd(execCreate.getId()).exec();
            int exitCode = execInspect.getExitCode() != null ? execInspect.getExitCode() : -1;

            if (exitCode == 0) {
                return ToolExecutionResult.success(stdout.toString().trim());
            } else {
                String error = stderr.length() > 0 ? stderr.toString().trim() : stdout.toString().trim();
                return ToolExecutionResult.failure("Exit code " + exitCode + ": " + error);
            }
        }).subscribeOn(Schedulers.boundedElastic());
    }

    // ── Filesystem Bridge ──────────────────────────────────────────────

    /**
     * Copy a file from host to sandbox container.
     */
    public Mono<Void> bridgeFileToSandbox(String hostPath, String sandboxPath,
                                          SandboxHandle handle) {
        if (handle.isNone()) return Mono.empty();

        return Mono.fromRunnable(() -> {
            try {
                Path hostFile = Paths.get(hostPath);
                if (!Files.exists(hostFile)) {
                    log.warn("Host file does not exist: {}", hostPath);
                    return;
                }

                try (InputStream tarStream = createTarArchive(hostFile)) {
                    dockerClient.copyArchiveToContainerCmd(handle.getContainerId())
                            .withRemotePath(Paths.get(sandboxPath))
                            .withTarInputStream(tarStream)
                            .exec();
                }
                log.debug("File bridged to sandbox: {} -> {}:{}",
                        hostPath, handle.getContainerId(), sandboxPath);
            } catch (Exception e) {
                log.error("Failed to bridge file to sandbox: {}", e.getMessage());
            }
        }).subscribeOn(Schedulers.boundedElastic()).then();
    }

    /**
     * Copy a file from sandbox container to host.
     */
    public Mono<Void> bridgeFileFromSandbox(String sandboxPath, String hostPath,
                                            SandboxHandle handle) {
        if (handle.isNone()) return Mono.empty();

        return Mono.fromRunnable(() -> {
            try {
                Path hostDir = Paths.get(hostPath).getParent();
                if (hostDir != null) {
                    Files.createDirectories(hostDir);
                }

                try (InputStream tarStream = dockerClient.copyArchiveFromContainerCmd(
                        handle.getContainerId(), sandboxPath).exec()) {
                    extractTarArchive(tarStream, Paths.get(hostPath));
                }
                log.debug("File bridged from sandbox: {}:{} -> {}",
                        handle.getContainerId(), sandboxPath, hostPath);
            } catch (Exception e) {
                log.error("Failed to bridge file from sandbox: {}", e.getMessage());
            }
        }).subscribeOn(Schedulers.boundedElastic()).then();
    }

    // ── Health Check ───────────────────────────────────────────────────

    /**
     * Check if a sandbox container is still healthy.
     */
    public Mono<Boolean> isHealthy(SandboxHandle handle) {
        if (handle.isNone()) return Mono.just(false);

        return Mono.fromCallable(() -> {
            try {
                InspectContainerResponse inspect = dockerClient.inspectContainerCmd(handle.getContainerId()).exec();
                return inspect.getState() != null && Boolean.TRUE.equals(inspect.getState().getRunning());
            } catch (Exception e) {
                log.warn("Health check failed for container {}: {}", handle.getContainerId(), e.getMessage());
                return false;
            }
        }).subscribeOn(Schedulers.boundedElastic());
    }

    // ── Destroy ────────────────────────────────────────────────────────

    /**
     * Stop and remove a sandbox container, releasing all resources.
     */
    public Mono<Void> destroy(SandboxHandle handle) {
        if (handle.isNone()) return Mono.empty();

        return Mono.fromRunnable(() -> {
            try {
                dockerClient.stopContainerCmd(handle.getContainerId())
                        .withTimeout(10)
                        .exec();
                // Auto-remove is configured, so explicit remove is optional
                log.info("Sandbox container destroyed: containerId={}", handle.getContainerId());
            } catch (Exception e) {
                log.warn("Error destroying sandbox container {}: {}",
                        handle.getContainerId(), e.getMessage());
                // Force remove as fallback
                try {
                    dockerClient.removeContainerCmd(handle.getContainerId())
                            .withForce(true)
                            .exec();
                } catch (Exception f) {
                    log.error("Force remove also failed for container {}: {}",
                            handle.getContainerId(), f.getMessage());
                }
            } finally {
                activeHandles.remove(handle.getSessionId());
            }
        }).subscribeOn(Schedulers.boundedElastic()).then();
    }

    /**
     * Destroy all active sandbox containers. Called on application shutdown.
     */
    public Mono<Void> destroyAll() {
        return Flux.fromIterable(new ArrayList<>(activeHandles.values()))
                .flatMap(this::destroy)
                .then();
    }

    // ── Private Helpers ────────────────────────────────────────────────

    private boolean waitForContainerReady(String containerId, int timeoutSeconds) {
        long deadline = System.currentTimeMillis() + timeoutSeconds * 1000L;
        while (System.currentTimeMillis() < deadline) {
            try {
                InspectContainerResponse inspect = dockerClient.inspectContainerCmd(containerId).exec();
                if (inspect.getState() != null && Boolean.TRUE.equals(inspect.getState().getRunning())) {
                    return true;
                }
                Thread.sleep(500);
            } catch (Exception e) {
                // Container may not be ready yet
            }
        }
        return false;
    }

    private String[] buildExecCommand(Tool tool, Map<String, Object> args) {
        // For command tools, wrap in bash -c
        // For script tools, write script to /tmp then execute
        String command = args.getOrDefault("command", "").toString();
        if (command.isEmpty()) {
            command = tool.getDescription();
        }
        return new String[]{"bash", "-c", command};
    }

    private boolean isCommandAllowed(String command) {
        List<String> allowed = config.getAllowedCommands();
        List<String> denied = config.getDeniedCommands();

        // If whitelist is configured, only allowlisted commands pass
        if (!allowed.isEmpty()) {
            return allowed.stream().anyMatch(cmd -> command.startsWith(cmd));
        }

        // If blacklist is configured, deny matching commands
        if (!denied.isEmpty()) {
            if (denied.stream().anyMatch(cmd -> command.startsWith(cmd))) {
                return false;
            }
        }

        // No explicit rules = allow all (backward compatible)
        return true;
    }

    private InputStream createTarArchive(Path file) throws IOException {
        // Minimal TAR creation for single file (in production, use Apache Commons Compress)
        java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
        // Simplified: in real code, use a proper TAR library
        // This is a placeholder showing the integration pattern
        baos.write(("tar-content:" + file.getFileName()).getBytes());
        return new java.io.ByteArrayInputStream(baos.toByteArray());
    }

    private void extractTarArchive(InputStream tarStream, Path destPath) {
        // Simplified: in real code, use a proper TAR library
        // Placeholder showing the integration pattern
    }
}
```

### 4.2.4 SandboxHandle

```java
package lyjew.com.lyclaw.security.sandbox;

/**
 * Handle to an active sandbox container.
 * <p>Immutable after creation; used as a key for sandbox lifecycle operations.
 */
public class SandboxHandle {

    private final String sessionId;
    private final String containerId;
    private final String containerName;
    private final boolean none;

    private SandboxHandle(String sessionId, String containerId, String containerName, boolean none) {
        this.sessionId = sessionId;
        this.containerId = containerId;
        this.containerName = containerName;
        this.none = none;
    }

    public SandboxHandle(String sessionId, String containerId, String containerName) {
        this(sessionId, containerId, containerName, false);
    }

    /** Create a no-op handle when no sandbox backend is configured. */
    public static SandboxHandle none() {
        return new SandboxHandle("", "", "", true);
    }

    public boolean isNone() { return none; }
    public String getSessionId() { return sessionId; }
    public String getContainerId() { return containerId; }
    public String getContainerName() { return containerName; }

    @Override
    public String toString() {
        return none ? "SandboxHandle[NONE]" :
                "SandboxHandle[session=" + sessionId + ", container=" + containerId + "]";
    }
}
```

### 4.2.5 Integration with SandboxHook

The existing `SandboxHook` currently delegates to `ToolSandbox.execute(tool, args, level)`. In Phase 4, `SandboxHook` is updated to use `SandboxExecutionService` when `SandboxLevel.PROCESS` is requested and the container backend is configured:

```java
// Updated SandboxHook.wrapToolExecutor():
//
//   SandboxLevel level = ctx.getSandboxLevel() != null ? ctx.getSandboxLevel() : SandboxLevel.DIRECT;
//
//   if (level == SandboxLevel.PROCESS && sandboxExecutionService != null) {
//       // Container-backed sandbox
//       SandboxHandle handle = ctx.getSandboxHandle();
//       if (handle == null) {
//           // Lazy-create sandbox for this session
//           handle = sandboxExecutionService.createSandbox(ctx.getSessionId()).block();
//           ctx.setSandboxHandle(handle);
//       }
//       return sandboxExecutionService.executeInSandbox(handle, tool, args)
//               .map(result -> result.isSuccess() ? result.getResult() : "Error: " + result.getError())
//               .block();
//   }
//
//   // Fallback: legacy toolSandbox for DIRECT and SANDBOX levels
//   ToolExecutionResult result = toolSandbox.execute(tool, args, level);
//   return result.isSuccess() ? result.getResult() : "Error: " + result.getError();
```

`AgentContext` is extended with a new field:

```java
// Added to AgentContext:
private volatile SandboxHandle sandboxHandle;
public SandboxHandle getSandboxHandle() { return sandboxHandle; }
public void setSandboxHandle(SandboxHandle handle) { this.sandboxHandle = handle; }
```

### 4.2.6 YAML Configuration

```yaml
# application.yml — sandbox configuration
lyclaw:
  sandbox:
    backend: DOCKER                  # NONE | DOCKER | PODMAN
    image: ubuntu:22.04
    root-dir: /sandbox
    allowed-commands:
      - python3
      - node
      - bash
      - cat
      - ls
      - grep
      - sed
      - awk
    denied-commands:
      - rm
      - dd
      - mkfs
      - shutdown
      - reboot
    network-enabled: false
    file-system-write-enabled: true
    memory-limit-mb: 512
    cpu-limit: 1.0
    timeout-seconds: 300
    startup-timeout-seconds: 30
    reuse-container: true
    container-idle-timeout-seconds: 600
    docker-socket: unix:///var/run/docker.sock
    podman-socket: unix:///run/podman/podman.sock
    fs-bridge:
      host-workspace: ./workspace
      sandbox-workspace: /workspace
      workspace-read-only: true
      host-tmp: ./sandbox-tmp
      sandbox-tmp: /tmp/sandbox
      tmp-max-size-mb: 500
```

---

## 4.3 Heartbeat System

### 4.3.1 Motivation

Long-running agents need periodic "check-in" pings to:
- Verify the agent is still operational
- Provide proactive status updates to users
- Execute scheduled maintenance tasks
- Support "daily briefing" / "morning summary" patterns

The heartbeat system is a cron-like scheduler that runs single-turn ReAct invocations on a schedule, with configurable lightweight context, isolated sessions, and target delivery.

### 4.3.2 HeartbeatConfig

```java
package lyjew.com.lyclaw.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Agent heartbeat configuration.
 * <p>Controls scheduled "ping" invocations that keep agents active
 * and deliver periodic updates to users.
 */
@ConfigurationProperties(prefix = "lyclaw.heartbeat")
public class HeartbeatConfig {

    /** Enable the heartbeat scheduler for this agent. */
    private boolean enabled = false;

    /** Cron-like interval between heartbeat runs. */
    private Duration every = Duration.ofMinutes(30);

    /** Active hours window (cron expression for time range, e.g. "0 0 9 ? * MON-FRI"). */
    private String activeHoursCron;

    /** Active hours configuration using human-readable format. */
    private ActiveHours activeHours = new ActiveHours();

    /** Model override for heartbeat runs (uses agent default if null). */
    private String model;

    /** Session key for heartbeat run grouping (defaults to agent name). */
    private String sessionKey;

    /** Where to deliver heartbeat results. */
    private DeliveryTarget target = DeliveryTarget.LAST;

    /** Direct message policy when target specifies a user/channel. */
    private DirectPolicy directPolicy = DirectPolicy.ALLOW;

    /** Target recipient: E.164 phone number or chat channel ID. */
    private String to;

    /** Account ID for multi-account channel selection. */
    private String accountId;

    /** Custom heartbeat prompt. If empty/null, uses default system prompt. */
    private String prompt;

    /** If true, include the system prompt section in the heartbeat context. */
    private boolean includeSystemPromptSection = true;

    /** Maximum characters in the heartbeat acknowledgment message. */
    private int ackMaxChars = 30;

    /** Suppress tool execution error warnings in heartbeat runs. */
    private boolean suppressToolErrorWarnings = true;

    /** Heartbeat execution timeout in seconds. */
    private int timeoutSeconds = 120;

    /**
     * If true, use lightweight context (HEARTBEAT.md only).
     * When false, load full agent context including all memory files.
     */
    private boolean lightContext = true;

    /**
     * If true, create a fresh isolated session for each heartbeat run.
     * The sessionKey is reused but message history is not carried forward.
     */
    private boolean isolatedSession = true;

    /**
     * If true, skip heartbeat when sub-agents are actively running.
     * Prevents heartbeat from interrupting ongoing delegation tasks.
     */
    private boolean skipWhenBusy = true;

    /**
     * If true, include reasoning/thinking content in heartbeat responses.
     */
    private boolean includeReasoning = false;

    // getters and setters omitted

    public enum DeliveryTarget { LAST, NONE }
    public enum DirectPolicy { ALLOW, BLOCK }

    /**
     * Active hours window configuration.
     */
    public static class ActiveHours {
        /** Window start time in HH:mm format. */
        private String start = "09:00";
        /** Window end time in HH:mm format. */
        private String end = "18:00";
        /** Timezone identifier, e.g. "Asia/Shanghai", "America/New_York". */
        private String timezone = "Asia/Shanghai";
        /** Days of week (MON, TUE, ..., SUN) or empty for all days. */
        private String daysOfWeek = "";

        // getters and setters omitted
    }
}
```

### 4.3.3 HeartbeatScheduler

```java
package lyjew.com.lyclaw.react.heartbeat;

import lyjew.com.lyclaw.chat.ChatFacade;
import lyjew.com.lyclaw.config.HeartbeatConfig;
import lyjew.com.lyclaw.model.ChatRequest;
import lyjew.com.lyclaw.model.Message;
import lyjew.com.lyclaw.react.AgentContext;
import lyjew.com.lyclaw.react.DefaultReActEngine;
import lyjew.com.lyclaw.react.ToolExecutor;
import lyjew.com.lyclaw.tool.ToolRegistry;
import lyjew.com.lyclaw.event.EventBus;
import lyjew.com.lyclaw.security.SecurityManager;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;
import org.springframework.scheduling.support.CronTrigger;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Cron-based heartbeat scheduler for agents.
 *
 * <p>Implements {@link SchedulingConfigurer} to dynamically register heartbeat
 * tasks based on each agent's {@link HeartbeatConfig}.
 *
 * <h3>Execution flow for each heartbeat tick:</h3>
 * <ol>
 *   <li>Check activeHours window — skip if outside</li>
 *   <li>Check skipWhenBusy — skip if sub-agents are active</li>
 *   <li>Create isolated session (if isolatedSession is true)</li>
 *   <li>Load light context (if lightContext — HEARTBEAT.md only)</li>
 *   <li>Run single-turn ReAct with heartbeat prompt</li>
 *   <li>Deliver result to target channel/user</li>
 *   <li>Dispatch heartbeat_start / heartbeat_reply / heartbeat_complete events</li>
 * </ol>
 */
public class HeartbeatScheduler {

    private static final Logger log = LoggerFactory.getLogger(HeartbeatScheduler.class);

    private final ChatFacade chatFacade;
    private final ToolRegistry toolRegistry;
    private final EventBus eventBus;
    private final SecurityManager securityManager;

    // Map of agent sessionKey → config for dynamic scheduling
    private final Map<String, HeartbeatConfig> agentConfigs = new ConcurrentHashMap<>();

    // Track active sub-agent count per agent
    private final Map<String, AtomicInteger> activeSubAgents = new ConcurrentHashMap<>();

    // ScheduledFuture handles for cancellation
    private final Map<String, ScheduledFuture<?>> scheduledTasks = new ConcurrentHashMap<>();

    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(4);

    public HeartbeatScheduler(ChatFacade chatFacade, ToolRegistry toolRegistry,
                               EventBus eventBus, SecurityManager securityManager) {
        this.chatFacade = chatFacade;
        this.toolRegistry = toolRegistry;
        this.eventBus = eventBus;
        this.securityManager = securityManager;
    }

    /**
     * Register or update a heartbeat configuration for an agent.
     * Called at agent initialization time.
     *
     * @param agentId the agent identifier
     * @param config  the heartbeat configuration
     */
    public void registerAgent(String agentId, HeartbeatConfig config) {
        if (config == null || !config.isEnabled()) {
            // Remove any existing schedule
            cancelSchedule(agentId);
            agentConfigs.remove(agentId);
            return;
        }

        agentConfigs.put(agentId, config);

        // Cancel existing schedule and create new one
        cancelSchedule(agentId);
        scheduleAgent(agentId, config);
    }

    /**
     * Notify the scheduler that a sub-agent has started for the given parent agent.
     * Used by skipWhenBusy to defer heartbeats during delegation.
     */
    public void onSubAgentStarted(String parentAgentId) {
        activeSubAgents.computeIfAbsent(parentAgentId, k -> new AtomicInteger(0))
                .incrementAndGet();
    }

    /**
     * Notify the scheduler that a sub-agent has completed for the given parent agent.
     */
    public void onSubAgentCompleted(String parentAgentId) {
        AtomicInteger count = activeSubAgents.get(parentAgentId);
        if (count != null && count.decrementAndGet() <= 0) {
            activeSubAgents.remove(parentAgentId);
        }
    }

    // ── Internal Scheduling ────────────────────────────────────────────

    private void scheduleAgent(String agentId, HeartbeatConfig config) {
        long intervalMs = config.getEvery().toMillis();

        ScheduledFuture<?> future = scheduler.scheduleAtFixedRate(
                () -> executeHeartbeat(agentId, config),
                intervalMs, // initial delay same as interval
                intervalMs,
                TimeUnit.MILLISECONDS
        );

        scheduledTasks.put(agentId, future);
        log.info("Heartbeat scheduled for agent '{}': every {}s", agentId,
                config.getEvery().getSeconds());
    }

    private void cancelSchedule(String agentId) {
        ScheduledFuture<?> future = scheduledTasks.remove(agentId);
        if (future != null) {
            future.cancel(false);
            log.info("Heartbeat cancelled for agent '{}'", agentId);
        }
    }

    // ── Heartbeat Execution ────────────────────────────────────────────

    private void executeHeartbeat(String agentId, HeartbeatConfig config) {
        try {
            // 1. Check active hours window
            if (!isWithinActiveHours(config.getActiveHours())) {
                log.debug("Heartbeat skipped for '{}': outside active hours", agentId);
                return;
            }

            // 2. Check skipWhenBusy
            if (config.isSkipWhenBusy()) {
                AtomicInteger count = activeSubAgents.get(agentId);
                if (count != null && count.get() > 0) {
                    log.debug("Heartbeat skipped for '{}': {} sub-agents active", agentId, count.get());
                    return;
                }
            }

            // 3. Create session key
            String sessionKey = config.getSessionKey() != null ? config.getSessionKey() : agentId;
            String runId = sessionKey + "-" + UUID.randomUUID().toString().substring(0, 8);
            long startMs = System.currentTimeMillis();

            log.info("Heartbeat starting: agent={} runId={}", agentId, runId);

            // 4. Prepare context
            AgentContext ctx = buildHeartbeatContext(agentId, sessionKey, runId, config);

            // 5. Run single-turn ReAct
            String result = runHeartbeatReAct(ctx, config);

            long elapsedMs = System.currentTimeMillis() - startMs;

            // 6. Deliver result
            deliverHeartbeatResult(agentId, config, result);

            // 7. Dispatch events
            dispatchHeartbeatEvent("heartbeat_complete", agentId, runId,
                    Map.of("elapsedMs", elapsedMs, "message", result.substring(0,
                            Math.min(result.length(), config.getAckMaxChars()))));

            log.info("Heartbeat completed: agent={} runId={} elapsed={}ms", agentId, runId, elapsedMs);

        } catch (Exception e) {
            log.error("Heartbeat failed for agent '{}': {}", agentId, e.getMessage(), e);
            dispatchHeartbeatEvent("heartbeat_error", agentId, null,
                    Map.of("error", e.getMessage()));
        }
    }

    private AgentContext buildHeartbeatContext(String agentId, String sessionKey,
                                                String runId, HeartbeatConfig config) {
        String prompt = config.getPrompt();
        if (prompt == null || prompt.isEmpty()) {
            prompt = "Heartbeat check-in. Provide a brief status update on your current state and any pending tasks.";
        }

        if (config.isIncludeSystemPromptSection()) {
            prompt = "[System Status Check]\n" + prompt;
        }

        // Create a transient context for this single heartbeat run
        AgentContext ctx = new AgentContext(
                config.isIsolatedSession() ? runId : sessionKey,
                prompt,
                null, // system prompt handled by agent config
                toolRegistry,
                null, // no method — heartbeat is not a user invocation
                null
        );

        if (config.isLightContext()) {
            // Load only HEARTBEAT.md context (implemented by memory system)
            ctx.setAttribute("heartbeatMode", true);
            ctx.setAttribute("contextFiles", List.of("HEARTBEAT.md"));
        }

        return ctx;
    }

    private String runHeartbeatReAct(AgentContext ctx, HeartbeatConfig config) {
        // Build a minimal ChatRequest for the heartbeat
        ChatRequest request = ChatRequest.builder()
                .messages(new ArrayList<>(List.of(Message.user(ctx.getUserMessage()))))
                .stream(false) // non-streaming for heartbeat
                .build();

        // Use a ReActEngine instance with no tools for lightweight execution
        DefaultReActEngine engine = new DefaultReActEngine(null, null) {
            @Override
            public String execute(ChatFacade chatFacade, ChatRequest request,
                                  ToolExecutor toolExecutor) {
                // Single-turn: no tool calling for heartbeat by default
                try {
                    var model = chatFacade.resolveModel(chatFacade.route(request, null));
                    var response = model.chat(request);
                    String content = response.getContent();
                    request.getMessages().add(Message.assistant(content != null ? content : ""));
                    return content != null ? content : "(no response)";
                } catch (Exception e) {
                    log.error("Heartbeat LLM call failed: {}", e.getMessage());
                    return "[Heartbeat LLM error: " + e.getMessage() + "]";
                }
            }
        };

        try {
            String result = engine.execute(chatFacade, request, null);
            return result != null ? result : "(empty response)";
        } catch (Exception e) {
            return "[Heartbeat error: " + e.getMessage() + "]";
        }
    }

    private void deliverHeartbeatResult(String agentId, HeartbeatConfig config, String result) {
        if (config.getTarget() == HeartbeatConfig.DeliveryTarget.NONE) {
            return;
        }

        // Delivery to target channel/user (implementation depends on channel adapter)
        // For now, publish as an event for the channel adapter to pick up
        Map<String, Object> delivery = new LinkedHashMap<>();
        delivery.put("agentId", agentId);
        delivery.put("message", result);
        delivery.put("to", config.getTo());
        delivery.put("accountId", config.getAccountId());
        delivery.put("timestamp", Instant.now().toString());

        eventBus.publish(new HeartbeatDeliveryEvent("heartbeat-scheduler", agentId, delivery));
    }

    private void dispatchHeartbeatEvent(String eventType, String agentId, String runId,
                                         Map<String, Object> data) {
        Map<String, Object> payload = new LinkedHashMap<>(data);
        payload.put("agentId", agentId);
        if (runId != null) payload.put("runId", runId);
        payload.put("timestamp", Instant.now().toString());
        eventBus.publish(new HeartbeatEvent("heartbeat-scheduler", eventType, payload));
    }

    // ── Active Hours Check ─────────────────────────────────────────────

    private boolean isWithinActiveHours(HeartbeatConfig.ActiveHours hours) {
        if (hours == null || hours.getStart() == null || hours.getEnd() == null) {
            return true; // no restriction
        }

        try {
            ZoneId zone = ZoneId.of(hours.getTimezone());
            ZonedDateTime now = ZonedDateTime.now(zone);

            LocalTime start = LocalTime.parse(hours.getStart(), DateTimeFormatter.ofPattern("HH:mm"));
            LocalTime end = LocalTime.parse(hours.getEnd(), DateTimeFormatter.ofPattern("HH:mm"));
            LocalTime current = now.toLocalTime();

            // Check days of week if configured
            if (hours.getDaysOfWeek() != null && !hours.getDaysOfWeek().isEmpty()) {
                String today = now.getDayOfWeek().name().substring(0, 3).toUpperCase();
                if (!hours.getDaysOfWeek().toUpperCase().contains(today)) {
                    return false;
                }
            }

            if (start.isBefore(end)) {
                // Normal range: e.g., 09:00 - 18:00
                return !current.isBefore(start) && current.isBefore(end);
            } else {
                // Overnight range: e.g., 22:00 - 06:00
                return !current.isBefore(start) || current.isBefore(end);
            }
        } catch (Exception e) {
            log.warn("Active hours check failed, defaulting to allowed: {}", e.getMessage());
            return true;
        }
    }
}
```

### 4.3.4 Heartbeat Event Types

```java
package lyjew.com.lyclaw.react.heartbeat;

import lyjew.com.lyclaw.event.Event;

import java.util.Map;

/**
 * Heartbeat lifecycle event. Published at each phase of a heartbeat run.
 *
 * <p>Event types:
 * <ul>
 *   <li>heartbeat_start — agentId, sessionKey, timestamp</li>
 *   <li>heartbeat_thinking — agentId (LLM is generating)</li>
 *   <li>heartbeat_reply — agentId, message</li>
 *   <li>heartbeat_complete — agentId, elapsedMs, message preview</li>
 *   <li>heartbeat_error — agentId, error</li>
 * </ul>
 */
public class HeartbeatEvent extends Event {

    private final Map<String, Object> data;

    public HeartbeatEvent(String source, String eventType, Map<String, Object> data) {
        super(source, "heartbeat." + eventType);
        this.data = data;
    }

    public Map<String, Object> getData() { return data; }
}

/**
 * Heartbeat delivery event. Published when a heartbeat result needs to be
 * delivered to a target channel or user.
 */
class HeartbeatDeliveryEvent extends Event {

    private final Map<String, Object> deliveryData;

    public HeartbeatDeliveryEvent(String source, String agentId, Map<String, Object> deliveryData) {
        super(source, "heartbeat.delivery");
        this.deliveryData = deliveryData;
    }

    public Map<String, Object> getDeliveryData() { return deliveryData; }
}
```

### 4.3.5 SSE Event Schema

Heartbeat SSE events (when the heartbeat is triggered by an external request rather than cron):

| Event | `event:` field | `data:` structure |
|---|---|---|
| `heartbeat_start` | `heartbeat_start` | `{"agentId":"...", "sessionKey":"...", "timestamp":"..."}` |
| `heartbeat_thinking` | `heartbeat_thinking` | `{"agentId":"..."}` |
| `heartbeat_reply` | `heartbeat_reply` | `{"agentId":"...", "message":"...", "..."}` |
| `heartbeat_complete` | `heartbeat_complete` | `{"agentId":"...", "elapsedMs":1234, "message":"preview..."}` |
| `heartbeat_error` | `heartbeat_error` | `{"agentId":"...", "error":"..."}` |

### 4.3.6 YAML Configuration

```yaml
# application.yml — heartbeat configuration per agent
lyclaw:
  heartbeat:
    enabled: true
    every: 30m                      # Duration: 30m, 1h, etc.
    active-hours:
      start: "09:00"
      end: "18:00"
      timezone: Asia/Shanghai
      days-of-week: MON,TUE,WED,THU,FRI
    model: null                     # null = use agent default
    session-key: daily-checkin
    target: LAST                    # LAST | NONE
    direct-policy: ALLOW            # ALLOW | BLOCK
    to: null                        # E.164 phone or chat id
    account-id: null                # multi-account selector
    prompt: "Good morning! Here is your daily briefing. What are the top priorities today?"
    include-system-prompt-section: true
    ack-max-chars: 30
    suppress-tool-error-warnings: true
    timeout-seconds: 120
    light-context: true
    isolated-session: true
    skip-when-busy: true
    include-reasoning: false
```

---

## 4.4 Run Retries Enhancement

### 4.4.1 Motivation

The current `ReflexionLoop` uses a simple `maxRetries` parameter (typically 2) and a static `qualityThreshold` (0.6). This is insufficient for production:

- **Hardcoded retry budget**: No per-agent or per-fallback-model differentiation
- **No retry history**: Cannot learn from previous failures to adjust strategy
- **No model fallback chain**: If primary model consistently fails, no mechanism to try alternative (cheaper/faster/smaller) models
- **No retry metadata**: Current `ReflexionResult.Attempt` records only score and feedback, not model/provider used

### 4.4.2 RunRetriesConfig

```java
package lyjew.com.lyclaw.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Run retry configuration for ReAct loop reflection retries.
 * <p>Controls the retry budget, strategy selection, and model fallback behavior.
 */
@ConfigurationProperties(prefix = "lyclaw.retry")
public class RunRetriesConfig {

    /**
     * Base number of retry iterations for the primary model.
     * Total retries = base + (perProfile * numberOfFallbackProfiles)
     */
    private int base = 24;

    /**
     * Additional retry iterations allocated per fallback model profile.
     * Each fallback model in the chain gets this many extra attempts.
     */
    private int perProfile = 8;

    /**
     * Minimum floor for total retry iterations.
     * Even if base+perProfile*count calculates lower, this floor applies.
     */
    private int min = 32;

    /**
     * Maximum ceiling for total retry iterations.
     * Prevents unbounded retry loops.
     */
    private int max = 160;

    /**
     * Quality threshold for retry termination.
     * If the reflection score meets or exceeds this threshold, retries stop early.
     */
    private double qualityThreshold = 0.7;

    /**
     * Strategy for selecting the next model when a retry is needed.
     * <ul>
     *   <li>SAME_MODEL — retry with the same model (default)</li>
     *   <li>FALLBACK_CHAIN — try next model in the fallback chain</li>
     *   <li>ADAPTIVE — switch to fallback after 3 consecutive same-model failures</li>
     * </ul>
     */
    private RetryStrategy defaultStrategy = RetryStrategy.ADAPTIVE;

    /**
     * Maximum consecutive failures before escalating to fallback model
     * (only applies when strategy is ADAPTIVE).
     */
    private int maxConsecutiveFailuresBeforeFallback = 3;

    /**
     * Exponential backoff configuration for retry delays.
     */
    private RetryBackoff backoff = new RetryBackoff();

    // getters and setters omitted

    public enum RetryStrategy { SAME_MODEL, FALLBACK_CHAIN, ADAPTIVE }

    /**
     * Exponential backoff for retry delays.
     */
    public static class RetryBackoff {
        /** Initial delay in milliseconds. */
        private long initialDelayMs = 500;
        /** Maximum delay in milliseconds. */
        private long maxDelayMs = 30_000;
        /** Backoff multiplier (e.g., 2.0 = double each retry). */
        private double multiplier = 2.0;
        /** Backoff applies to: BOTH = model call + reflection, LLM_ONLY, REFLECTION_ONLY */
        private BackoffTarget target = BackoffTarget.BOTH;

        // getters and setters omitted
        public enum BackoffTarget { BOTH, LLM_ONLY, REFLECTION_ONLY }
    }
}
```

### 4.4.3 RunRetryManager

```java
package lyjew.com.lyclaw.react.retry;

import lyjew.com.lyclaw.config.RunRetriesConfig;
import lyjew.com.lyclaw.react.ReflexionResult;
import lyjew.com.lyclaw.task.ReflectionFeedback;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages retry budget, tracking, and strategy for ReAct loop reflection retries.
 *
 * <p>Replaces hardcoded MAX_REFLECTION_RETRIES=2 with a configurable, model-aware
 * retry system that supports fallback chains and adaptive strategy selection.
 *
 * <h3>Retry Budget Formula:</h3>
 * <pre>
 *   totalRetries = max(min, min(max, base + perProfile * fallbackProfileCount))
 * </pre>
 *
 * <h3>Retry State Machine:</h3>
 * <pre>
 *   [Execute with model M]
 *        |
 *        v
 *   [Reflect] ──score >= threshold──> [DONE]
 *        |
 *   score < threshold
 *        |
 *        v
 *   [Check retry budget] ──exhausted──> [DONE with best result]
 *        |
 *   budget available
 *        |
 *        v
 *   [Select strategy: same model / fallback]
 *        |
 *        v
 *   [Plan revision] ──> [Execute with (new) model M']
 * </pre>
 */
public class RunRetryManager {

    private static final Logger log = LoggerFactory.getLogger(RunRetryManager.class);

    private final RunRetriesConfig config;
    private final List<String> fallbackProfiles;
    private final int maxRetries;

    // Per-session retry history
    private final Map<String, RetrySession> sessions = new ConcurrentHashMap<>();

    public RunRetryManager(RunRetriesConfig config, List<String> fallbackProfiles) {
        this.config = config;
        this.fallbackProfiles = fallbackProfiles != null ? fallbackProfiles : List.of();
        this.maxRetries = calculateMaxRetries(config, this.fallbackProfiles.size());
    }

    /**
     * Calculate total retry budget.
     */
    private int calculateMaxRetries(RunRetriesConfig config, int fallbackCount) {
        int total = config.getBase() + config.getPerProfile() * fallbackCount;
        return Math.max(config.getMin(), Math.min(config.getMax(), total));
    }

    /**
     * Get the maximum retry count for a session.
     */
    public int getMaxRetries(String sessionId) {
        return maxRetries;
    }

    /**
     * Check if more retries are available for the given session.
     *
     * @param sessionId the session to check
     * @return true if at least one more retry is budgeted
     */
    public boolean canRetry(String sessionId) {
        RetrySession session = sessions.get(sessionId);
        if (session == null) {
            return maxRetries > 0;
        }
        return session.getAttemptCount() < maxRetries;
    }

    /**
     * Record a retry attempt for the session.
     *
     * @param sessionId the session identifier
     * @param attempt   the completed retry attempt
     */
    public void recordRetry(String sessionId, RetryAttempt attempt) {
        RetrySession session = sessions.computeIfAbsent(sessionId, RetrySession::new);
        session.addAttempt(attempt);
        log.debug("Retry recorded: session={} attempt={}/{} score={} model={}",
                sessionId, session.getAttemptCount(), maxRetries,
                attempt.getQualityScore(), attempt.getModelUsed());
    }

    /**
     * Determine the retry strategy based on history.
     *
     * @param sessionId the session identifier
     * @param primaryModel the primary model name
     * @return the model to use for the next attempt
     */
    public String determineNextModel(String sessionId, String primaryModel) {
        RetrySession session = sessions.get(sessionId);
        if (session == null || session.getAttemptCount() == 0) {
            return primaryModel;
        }

        RunRetriesConfig.RetryStrategy strategy = config.getDefaultStrategy();

        switch (strategy) {
            case SAME_MODEL:
                return primaryModel;

            case FALLBACK_CHAIN: {
                // Rotate through fallback models on each retry
                int attemptIndex = session.getAttemptCount();
                if (attemptIndex < fallbackProfiles.size()) {
                    return fallbackProfiles.get(attemptIndex);
                }
                // Cycle back through fallbacks
                return fallbackProfiles.get(attemptIndex % fallbackProfiles.size());
            }

            case ADAPTIVE:
            default: {
                // Check consecutive failures with current model
                int consecutiveFailures = session.countConsecutiveFailuresWithCurrentModel();
                if (consecutiveFailures >= config.getMaxConsecutiveFailuresBeforeFallback()) {
                    // Switch to next fallback
                    int fallbackIndex = session.getCurrentFallbackIndex();
                    if (fallbackIndex < fallbackProfiles.size()) {
                        session.incrementFallbackIndex();
                        String fallback = fallbackProfiles.get(fallbackIndex);
                        log.info("Adaptive retry switching to fallback model: {} -> {} ({} consecutive failures)",
                                session.getCurrentModel(), fallback, consecutiveFailures);
                        return fallback;
                    }
                    // All fallbacks exhausted, stick with primary
                    return primaryModel;
                }
                return session.getCurrentModel() != null ? session.getCurrentModel() : primaryModel;
            }
        }
    }

    /**
     * Calculate exponential backoff delay for the next retry.
     */
    public long calculateBackoffMs(String sessionId) {
        RetrySession session = sessions.get(sessionId);
        int attemptCount = session != null ? session.getAttemptCount() : 0;

        RunRetriesConfig.RetryBackoff backoff = config.getBackoff();
        long delay = (long)(backoff.getInitialDelayMs() *
                Math.pow(backoff.getMultiplier(), attemptCount));
        return Math.min(delay, backoff.getMaxDelayMs());
    }

    /**
     * Clear retry state for a session (called on session completion/reset).
     */
    public void clearSession(String sessionId) {
        sessions.remove(sessionId);
    }

    /**
     * Get retry statistics for monitoring.
     */
    public RetryStats getStats(String sessionId) {
        RetrySession session = sessions.get(sessionId);
        if (session == null) {
            return new RetryStats(0, 0, maxRetries, 0.0, 0.0);
        }
        return session.computeStats(maxRetries);
    }

    // ── Inner Types ────────────────────────────────────────────────────

    /**
     * Per-session retry tracking.
     */
    static class RetrySession {
        private final String sessionId;
        private final List<RetryAttempt> attempts = new ArrayList<>();
        private volatile int currentFallbackIndex = 0;
        private volatile String currentModel;

        RetrySession(String sessionId) { this.sessionId = sessionId; }

        void addAttempt(RetryAttempt attempt) {
            attempts.add(attempt);
            this.currentModel = attempt.getModelUsed();
        }

        int getAttemptCount() { return attempts.size(); }

        String getCurrentModel() { return currentModel; }

        int getCurrentFallbackIndex() { return currentFallbackIndex; }

        void incrementFallbackIndex() { currentFallbackIndex++; }

        int countConsecutiveFailuresWithCurrentModel() {
            int count = 0;
            for (int i = attempts.size() - 1; i >= 0; i--) {
                RetryAttempt a = attempts.get(i);
                if (currentModel != null && currentModel.equals(a.getModelUsed())
                        && a.getQualityScore() < 0.7) {
                    count++;
                } else {
                    break;
                }
            }
            return count;
        }

        RetryStats computeStats(int maxRetries) {
            if (attempts.isEmpty()) {
                return new RetryStats(0, 0, maxRetries, 0.0, 0.0);
            }
            double avgScore = attempts.stream().mapToDouble(RetryAttempt::getQualityScore).average().orElse(0.0);
            double bestScore = attempts.stream().mapToDouble(RetryAttempt::getQualityScore).max().orElse(0.0);
            return new RetryStats(attempts.size(), attempts.size(), maxRetries, avgScore, bestScore);
        }
    }

    /**
     * A single retry attempt record.
     */
    public static class RetryAttempt {
        private final int attemptNumber;
        private final String modelUsed;
        private final double qualityScore;
        private final List<String> errors;
        private final String suggestedStrategy;
        private final long elapsedMs;
        private final Instant timestamp;

        public RetryAttempt(int attemptNumber, String modelUsed, double qualityScore,
                            List<String> errors, String suggestedStrategy, long elapsedMs) {
            this.attemptNumber = attemptNumber;
            this.modelUsed = modelUsed;
            this.qualityScore = qualityScore;
            this.errors = errors != null ? errors : List.of();
            this.suggestedStrategy = suggestedStrategy;
            this.elapsedMs = elapsedMs;
            this.timestamp = Instant.now();
        }

        public static RetryAttempt fromReflexionResult(ReflexionResult.Attempt attempt,
                                                        String modelUsed) {
            ReflectionFeedback fb = attempt.getFeedback();
            return new RetryAttempt(
                    attempt.getAttemptNumber(),
                    modelUsed,
                    attempt.getQualityScore(),
                    fb != null ? fb.getDetectedErrors() : List.of(),
                    fb != null ? fb.getSuggestedStrategy() : null,
                    0 // elapsedMs tracked separately
            );
        }

        public int getAttemptNumber() { return attemptNumber; }
        public String getModelUsed() { return modelUsed; }
        public double getQualityScore() { return qualityScore; }
        public List<String> getErrors() { return errors; }
        public String getSuggestedStrategy() { return suggestedStrategy; }
        public long getElapsedMs() { return elapsedMs; }
        public Instant getTimestamp() { return timestamp; }
    }

    /**
     * Retry statistics snapshot for monitoring dashboards.
     */
    public static class RetryStats {
        private final int attemptsUsed;
        private final int attemptsTotal;
        private final int budget;
        private final double avgQualityScore;
        private final double bestQualityScore;

        public RetryStats(int attemptsUsed, int attemptsTotal, int budget,
                          double avgQualityScore, double bestQualityScore) {
            this.attemptsUsed = attemptsUsed;
            this.attemptsTotal = attemptsTotal;
            this.budget = budget;
            this.avgQualityScore = avgQualityScore;
            this.bestQualityScore = bestQualityScore;
        }

        public int getAttemptsUsed() { return attemptsUsed; }
        public int getAttemptsTotal() { return attemptsTotal; }
        public int getBudget() { return budget; }
        public double getAvgQualityScore() { return avgQualityScore; }
        public double getBestQualityScore() { return bestQualityScore; }
        public int getRemainingBudget() { return budget - attemptsUsed; }
    }
}
```

### 4.4.4 Integration with AgentContext

Extend `AgentContext` to carry retry metadata:

```java
// Additions to AgentContext:

/** Run metadata for retry tracking. Stored in attributes for serializability. */
public Map<String, Object> getRunMetadata() {
    @SuppressWarnings("unchecked")
    Map<String, Object> meta = (Map<String, Object>) getAttribute("runMetadata");
    if (meta == null) {
        meta = new HashMap<>();
        setAttribute("runMetadata", meta);
    }
    return meta;
}

public void recordRetryState(String modelUsed, double score, List<String> errors) {
    Map<String, Object> meta = getRunMetadata();
    meta.put("lastModelUsed", modelUsed);
    meta.put("lastScore", score);
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> history = (List<Map<String, Object>>)
            meta.computeIfAbsent("retryHistory", k -> new ArrayList<>());
    Map<String, Object> entry = new LinkedHashMap<>();
    entry.put("model", modelUsed);
    entry.put("score", score);
    entry.put("errors", errors);
    entry.put("timestamp", Instant.now().toString());
    history.add(entry);
}
```

### 4.4.5 Integration with ReflexionLoop

The existing `ReflexionLoop` is enhanced to use `RunRetryManager`:

```java
// Enhanced ReflexionLoop (diff from current):
//
// Before:
//   public ReflexionLoop(ReflectionEngine engine, TaskPlanner planner,
//                         int maxRetries, double qualityThreshold) { ... }
//
// After:
//   public class ReflexionLoop {
//       private final RunRetryManager retryManager;
//       private final String primaryModel;
//       ...
//
//       public ReflexionLoop(ReflectionEngine engine, TaskPlanner planner,
//                             RunRetryManager retryManager, String primaryModel) {
//           this.reflectionEngine = engine;
//           this.taskPlanner = planner;
//           this.retryManager = retryManager;
//           this.primaryModel = primaryModel;
//       }
//
//       public ReflexionResult execute(TaskPlan plan, ChatContext context,
//                                       StepExecutor executor) {
//           List<ReflexionResult.Attempt> attempts = new ArrayList<>();
//           TaskPlan currentPlan = plan;
//           String loopId = UUID.randomUUID().toString().substring(0, 8);
//           long startTime = System.currentTimeMillis();
//           String currentModel = primaryModel;
//
//           int attempt = 0;
//           while (retryManager.canRetry(context.getSessionId())) {
//               log.info("[Reflexion {}] 尝试 {}/{} model={}", loopId,
//                       attempt + 1, retryManager.getMaxRetries(context.getSessionId()), currentModel);
//
//               // Execute with current model
//               ActionResult result = executePlan(currentPlan, executor);
//
//               // Reflect
//               double score = reflect(context, result);
//
//               // Record retry
//               retryManager.recordRetry(context.getSessionId(),
//                       new RunRetryManager.RetryAttempt(attempt, currentModel, score,
//                               extractErrors(result), null, 0));
//
//               attempts.add(new ReflexionResult.Attempt(attempt, result, score, buildFeedback(result)));
//
//               // Check quality threshold
//               if (score >= qualityThreshold) break;
//
//               // Determine next model
//               currentModel = retryManager.determineNextModel(
//                       context.getSessionId(), primaryModel);
//
//               // Apply backoff
//               long backoffMs = retryManager.calculateBackoffMs(context.getSessionId());
//               if (backoffMs > 0) Thread.sleep(backoffMs);
//
//               // Revise plan
//               currentPlan = taskPlanner.revise(currentPlan, buildFeedback(result));
//               attempt++;
//           }
//
//           long totalMs = System.currentTimeMillis() - startTime;
//           return new ReflexionResult(loopId, attempts, totalMs);
//       }
//   }
```

### 4.4.6 YAML Configuration

```yaml
# application.yml — retry configuration
lyclaw:
  retry:
    base: 24
    per-profile: 8
    min: 32
    max: 160
    quality-threshold: 0.7
    default-strategy: ADAPTIVE            # SAME_MODEL | FALLBACK_CHAIN | ADAPTIVE
    max-consecutive-failures-before-fallback: 3
    backoff:
      initial-delay-ms: 500
      max-delay-ms: 30000
      multiplier: 2.0
      target: BOTH                       # BOTH | LLM_ONLY | REFLECTION_ONLY
```

---

## Integration Diagram

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                          Phase 4 — System Architecture                       │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│  ┌─────────────┐    ┌──────────────────┐    ┌───────────────────────────┐  │
│  │ User Request │───>│  Pipeline Stages  │───>│  SSE Event Stream         │  │
│  │ (HTTP/MQTT)  │    │                   │    │  (to Web/App client)      │  │
│  └─────────────┘    │  ContextBuild     │    └───────────────────────────┘  │
│                      │  SecurityCheck    │              ▲                    │
│                      │  PlanExecution    │              │                    │
│                      │  RespondStage ◄───┼──────────────┘                    │
│                      │    │              │    BlockStreamingController       │
│                      │    │              │    TypingIndicatorController      │
│                      │    │              │    HumanDelay                     │
│                      │  ReflectionStage  │                                   │
│                      │  MetricsStage     │                                   │
│                      └──────────────────┘                                   │
│                              │                                              │
│              ┌───────────────┼──────────────────┐                          │
│              │               │                  │                          │
│              v               v                  v                          │
│  ┌─────────────────┐ ┌────────────┐ ┌───────────────────┐                 │
│  │  ReActEngine     │ │ SandboxHook│ │  HeartbeatScheduler│                │
│  │  (streaming)     │ │            │ │                    │                 │
│  │                  │ │ SandboxExe-│ │  Cron: every 30m   │                 │
│  │  BlockStreaming  │ │ cutionSvc  │ │  ActiveHours check │                 │
│  │  Coalesce        │ │            │ │  LightContext      │                 │
│  │  HumanDelay      │ │ Docker/Pod-│ │  IsolatedSession   │                 │
│  │  TypingIndicator │ │ man backend│ │  SkipWhenBusy      │                 │
│  └────────┬─────────┘ └─────┬──────┘ └─────────┬─────────┘                 │
│           │                 │                   │                           │
│           v                 v                   v                           │
│  ┌──────────────────────────────────────────────────┐                      │
│  │               RunRetryManager                     │                      │
│  │                                                   │                      │
│  │  Retry Budget: base + perProfile * fallbackCount  │                      │
│  │  Strategy: ADAPTIVE / FALLBACK_CHAIN / SAME_MODEL │                      │
│  │  Backoff: exponential with configurable ceiling   │                      │
│  │  Session tracking: per-session retry history      │                      │
│  └──────────────────────────────────────────────────┘                      │
│                                                                             │
│  ┌─────────────────────── Event Bus ───────────────────────┐               │
│  │                                                         │               │
│  │  heartbeat_start   heartbeat_thinking  heartbeat_reply  │               │
│  │  heartbeat_complete  heartbeat_error  heartbeat_delivery│               │
│  │                                                         │               │
│  │  retry_attempt    retry_exhausted    retry_fallback     │               │
│  │                                                         │               │
│  │  sandbox_created  sandbox_destroyed  sandbox_health     │               │
│  └─────────────────────────────────────────────────────────┘               │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘
```

### Data Flow: Streaming Pipeline

```
LLM Token Stream (Flux<ModelResponse>)
     │
     ▼
┌──────────────────┐
│ State Machine    │  0=buffering(thinking), 1=relaying(stream tokens), 2=tools_detected
│ (DefaultReAct    │
│  Engine)         │
└────────┬─────────┘
         │  Case 1: state=1 (pure text stream)
         │    → tokens emitted as fine-grained SSE "message" events
         │
         │  Case 2: state=2 (tools detected)
         │    → tool execution, then final text response
         │    → final text passed to BlockStreamingController
         │
         ▼
┌──────────────────┐
│ BlockStreaming   │  segmentIntoBlocks() → coalesceBlocks() → suppressRepeats()
│ Controller       │
└────────┬─────────┘
         │
         ▼
┌──────────────────┐
│ HumanDelay       │  calculateDelay(block) → adaptiveSpeed → jitter
└────────┬─────────┘
         │
         ▼
┌──────────────────┐
│ TypingIndicator  │  Emits "typing" SSE events at intervals during gaps
└────────┬─────────┘
         │
         ▼
    SSE Client
```

### Sandbox Execution Flow

```
    Tool Call Request
         │
         ▼
    SandboxHook.wrapToolExecutor()
         │
         ▼
    ctx.getSandboxLevel() == PROCESS && backend != NONE ?
         │
    ┌────┴────┐
    │   YES   │               │   NO    │
    ▼         ▼               ▼         ▼
┌─────────────────┐   ┌──────────────────┐
│ SandboxExecSvc  │   │ ToolSandbox       │
│                 │   │ (legacy DIRECT/   │
│ createSandbox() │   │  SANDBOX modes)   │
│ if not exists   │   └──────────────────┘
│                 │
│ executeInSandbox│
│                 │
│ docker exec     │
│ cmd [bash -c]   │
│                 │
│ capture stdout  │
│ check exit code │
└────────┬────────┘
         │
         ▼
    ToolExecutionResult
```

---

## SSE Event Schema Reference

### Block Streaming Events

| Event Name | `event:` | `data:` schema |
|---|---|---|
| message (block) | `message` | `{"type":"message","content":"block text..."}` |
| typing | `typing` | `{"type":"typing","agentId":"...","stage":"RESPOND"}` |

### Sandbox Events

| Event Name | `event:` | `data:` schema |
|---|---|---|
| sandbox_created | `sandbox_created` | `{"containerId":"...","sessionId":"...","image":"..."}` |
| sandbox_executing | `sandbox_executing` | `{"toolName":"...","containerId":"..."}` |
| sandbox_result | `sandbox_result` | `{"toolName":"...","exitCode":0,"stdout":"..."}` |
| sandbox_destroyed | `sandbox_destroyed` | `{"containerId":"..."}` |

### Heartbeat Events

| Event Name | `event:` | `data:` schema |
|---|---|---|
| heartbeat_start | `heartbeat_start` | `{"agentId":"...","sessionKey":"...","timestamp":"..."}` |
| heartbeat_thinking | `heartbeat_thinking` | `{"agentId":"..."}` |
| heartbeat_reply | `heartbeat_reply` | `{"agentId":"...","message":"..."}` |
| heartbeat_complete | `heartbeat_complete` | `{"agentId":"...","elapsedMs":1234,"message":"preview..."}` |
| heartbeat_error | `heartbeat_error` | `{"agentId":"...","error":"..."}` |

### Retry Events

| Event Name | `event:` | `data:` schema |
|---|---|---|
| retry_attempt | `retry_attempt` | `{"sessionId":"...","attempt":3,"model":"gpt-4","score":0.45}` |
| retry_fallback | `retry_fallback` | `{"sessionId":"...","fromModel":"gpt-4","toModel":"gpt-4o-mini"}` |
| retry_exhausted | `retry_exhausted` | `{"sessionId":"...","totalAttempts":32,"bestScore":0.68}` |

---

## Summary of Changes

### New Files (Java)

| File | Package | Description |
|---|---|---|
| `BlockStreamingConfig.java` | `lyjew.com.lyclaw.config` | Block streaming configuration POJO |
| `BlockStreamingChunk.java` | `lyjew.com.lyclaw.config` | Soft chunking config |
| `BlockStreamingCoalesce.java` | `lyjew.com.lyclaw.config` | Coalescing config |
| `HumanDelayConfig.java` | `lyjew.com.lyclaw.config` | Human typing delay config |
| `BlockStreamingController.java` | `lyjew.com.lyclaw.react.stream` | Block-based streaming pipeline |
| `TypingIndicatorController.java` | `lyjew.com.lyclaw.react.stream` | Typing indicator SSE emitter |
| `AgentSandboxConfig.java` | `lyjew.com.lyclaw.config` | Container sandbox config |
| `SandboxExecutionService.java` | `lyjew.com.lyclaw.security.sandbox` | Docker/Podman sandbox service |
| `SandboxHandle.java` | `lyjew.com.lyclaw.security.sandbox` | Sandbox container handle |
| `HeartbeatConfig.java` | `lyjew.com.lyclaw.config` | Heartbeat config POJO |
| `HeartbeatScheduler.java` | `lyjew.com.lyclaw.react.heartbeat` | Cron-based heartbeat executor |
| `HeartbeatEvent.java` | `lyjew.com.lyclaw.react.heartbeat` | Heartbeat event types |
| `RunRetriesConfig.java` | `lyjew.com.lyclaw.config` | Retry budget config |
| `RunRetryManager.java` | `lyjew.com.lyclaw.react.retry` | Retry manager with fallback chains |

### Modified Files (Java)

| File | Changes |
|---|---|
| `AgentContext.java` | Add `SandboxHandle sandboxHandle`, `Map<String,Object> runMetadata`, `recordRetryState()` |
| `SandboxHook.java` | Integrate `SandboxExecutionService` for `PROCESS` level when container backend configured |
| `DefaultReActEngine.java` | Replace `splitIntoEvents()` with `BlockStreamingController.streamResponse()` |
| `RespondStage.java` | Integrate `BlockStreamingController`, `TypingIndicatorController`, `HumanDelayConfig` |
| `ReflexionLoop.java` | Replace hardcoded `maxRetries` with `RunRetryManager`, add model rotation |

### Configuration Keys (application.yml)

| Prefix | Keys |
|---|---|
| `lyclaw.streaming.block` | enabled, break-mode, chunk.*, coalesce.*, max-chunk-chars, repeat-suppression, delivery-mode, hidden-boundary |
| `lyclaw.streaming.human-delay` | enabled, min-delay-ms, max-delay-ms, chars-per-second, adaptive-speed, long-reply-threshold |
| `lyclaw.streaming.typing-indicator` | mode, interval-seconds |
| `lyclaw.sandbox` | backend, image, root-dir, allowed-commands, denied-commands, network-enabled, file-system-write-enabled, memory-limit-mb, cpu-limit, timeout-seconds, fs-bridge.* |
| `lyclaw.heartbeat` | enabled, every, active-hours.*, model, session-key, target, direct-policy, to, account-id, prompt, include-system-prompt-section, ack-max-chars, suppress-tool-error-warnings, timeout-seconds, light-context, isolated-session, skip-when-busy, include-reasoning |
| `lyclaw.retry` | base, per-profile, min, max, quality-threshold, default-strategy, max-consecutive-failures-before-fallback, backoff.* |

---

# LyClaw Agent Platform — Post-Renovation Architecture Blueprint

> **Status:** Target Architecture  
> **Version:** 2.0.0  
> **Date:** 2026-05-20  
> **Scope:** Complete agent system redesign — transport, routing, runtime, shared services, plugin SDK, SSE streaming, and subagent delegation.

---

## Table of Contents

1. [Complete Agent System Architecture](#1-complete-agent-system-architecture-post-renovation)
2. [Agent Lifecycle Flow](#2-agent-lifecycle-flow-post-renovation)
3. [Config Resolution Hierarchy](#3-config-resolution-hierarchy)
4. [Subagent Delegation Tree](#4-subagent-delegation-tree)
5. [SSE Event Stream (Complete)](#5-sse-event-stream-complete)
6. [Component Inventory & Responsibilities](#6-component-inventory--responsibilities)
7. [Key Design Decisions](#7-key-design-decisions)
8. [Migration Path from Current Architecture](#8-migration-path-from-current-architecture)

---

## 1. Complete Agent System Architecture (Post-Renovation)

This diagram shows every major subsystem in the renovated LyClaw platform, organised into horizontal layers (Transport, Router, Config, Runtime, Shared Services, Plugin SDK) and vertical concerns (security, observability, persistence).

```
┌────────────────────────────────────────────────────────────────────────────────────────────┐
│                                     LyClaw Agent Platform                                      │
│                              ─────────────────────────────────────                             │
│                                                                                                │
│  ┌──────────────────────────────────────────────────────────────────────────────────────────┐ │
│  │                                      TRANSPORT LAYER                                       │ │
│  │                                                                                            │ │
│  │  ┌─────────────────┐  ┌─────────────────┐  ┌─────────────────┐  ┌──────────────────────┐  │ │
│  │  │    REST / SSE    │  │   WebSocket     │  │    WebChat      │  │   Channel Plugins    │  │ │
│  │  │   (HTTP/1.1)     │  │    (WS/WSS)     │  │   (React UI)    │  │                      │  │ │
│  │  │                  │  │                 │  │                 │  │  ┌────────────────┐   │  │ │
│  │  │  POST /chat      │  │  ws://host/ws   │  │  Embedded       │  │  │ Telegram Bot   │   │  │ │
│  │  │  GET  /sse/stream│  │                 │  │  WebChat UI     │  │  │  (Long Poll)   │   │  │ │
│  │  │  POST /agent/:id │  │  Bidirectional  │  │                 │  │  └────────────────┘   │  │ │
│  │  │                  │  │  persistent     │  │  Served via     │  │  ┌────────────────┐   │  │ │
│  │  │  JSON request    │  │  connection     │  │  Spring Boot    │  │  │ Discord Bot    │   │  │ │
│  │  │  → SSE response  │  │                 │  │  static assets  │  │  │  (Gateway)     │   │  │ │
│  │  └────────┬────────┘  └────────┬────────┘  └────────┬────────┘  │  └────────────────┘   │  │ │
│  │           │                    │                    │           │  ┌────────────────┐   │  │ │
│  │           │                    │                    │           │  │ Slack Bot      │   │  │ │
│  │           │                    │                    │           │  │  (Events API)  │   │  │ │
│  │           │                    │                    │           │  └────────────────┘   │  │ │
│  │           │                    │                    │           │  ┌────────────────┐   │  │ │
│  │           │                    │                    │           │  │ WeChat Work    │   │  │ │
│  │           │                    │                    │           │  │  (Callback)    │   │  │ │
│  │           │                    │                    │           │  └────────────────┘   │  │ │
│  │           │                    │                    │           └──────────┬───────────┘  │ │
│  └───────────┼────────────────────┼────────────────────┼──────────────────────┼──────────────┘ │
│              │                    │                    │                      │                │
│              │              Normalised Internal Message (AgentMessage)         │                │
│              │                    │                    │                      │                │
│  ┌───────────┴────────────────────┴────────────────────┴──────────────────────┴──────────────┐ │
│  │                                      AGENT ROUTER                                           │ │
│  │                                                                                            │ │
│  │  ┌────────────────────────────────────────────────────────────────────────────────────┐   │ │
│  │  │                              RouteBinding Registry                                  │   │ │
│  │  │                                                                                     │   │ │
│  │  │  ┌───────────────────────┐  ┌───────────────────────┐  ┌─────────────────────────┐  │   │ │
│  │  │  │  ChannelMatch        │  │  AcpBinding            │  │  MentionMatch           │  │   │ │
│  │  │  │                       │  │                        │  │                          │  │   │ │
│  │  │  │  #general  → agent1   │  │  acp:*     → codex    │  │  @bot chat → agent2      │  │   │ │
│  │  │  │  #code     → agent2   │  │  acp:cli   → claude   │  │  @bot code → code-review │  │   │ │
│  │  │  │  dm:*      → agent3   │  │  acp:gpt5  → gpt-5    │  │  @bot help → help-agent  │  │   │ │
│  │  │  │                       │  │                        │  │                          │  │   │ │
│  │  │  │  Match priority:      │  │  Routes to external    │  │  Regex / glob match      │  │   │ │
│  │  │  │  1. exact channel     │  │  ACP provider backend  │  │  on message content      │  │   │ │
│  │  │  │  2. glob pattern      │  │                        │  │                          │  │   │ │
│  │  │  │  3. default route     │  │                        │  │                          │  │   │ │
│  │  │  └───────────────────────┘  └───────────────────────┘  └─────────────────────────┘  │   │ │
│  │  └────────────────────────────────────────────────────────────────────────────────────┘   │ │
│  │                                                                                            │ │
│  │  Resolution pipeline:   TransportCtx → RouteBinding.match() → ResolvedRoute(agentId,ctx)   │ │
│  └────────────────────────────────────────────────────────────────────────────────────────────┘ │
│              │                                                                                  │
│  ┌───────────┴──────────────────────────────────────────────────────────────────────────────┐ │
│  │                                   AGENT CONFIG RESOLVER                                     │ │
│  │                                                                                            │ │
│  │  system.defaults ────► agent.defaults ────► @Agent annotation ────► runtime overrides       │ │
│  │  (application.yml)     (lyclaw.agent.*)     (ChatAgent.java)        (ChatRequest body)      │ │
│  │        │                      │                     │                       │              │ │
│  │        └──────────────────────┴─────────────────────┴───────────────────────┘              │ │
│  │                                      │                                                     │ │
│  │                                      ▼                                                     │ │
│  │                           ResolvedAgentConfig                                               │ │
│  │                    (immutable, thread-safe snapshot)                                        │ │
│  └────────────────────────────────────────────────────────────────────────────────────────────┘ │
│              │                                                                                  │
│  ┌───────────┴──────────────────────────────────────────────────────────────────────────────┐ │
│  │                                AGENT RUNTIME (per agent)                                    │ │
│  │                                                                                            │ │
│  │  ┌─────────────────────────────────────────┐    ┌──────────────────────────────────────┐  │ │
│  │  │          EMBEDDED RUNTIME               │    │           ACP RUNTIME                 │  │ │
│  │  │                                         │    │                                      │  │ │
│  │  │  ┌─────────────────────────────────┐    │    │  ┌────────────────────────────────┐   │  │ │
│  │  │  │        BootstrapLoader          │    │    │  │       AcpRuntime              │   │  │ │
│  │  │  │                                 │    │    │  │                                │   │  │ │
│  │  │  │  AGENTS.md       (role/cap)     │    │    │  │  ensureSession(agentId)        │   │  │ │
│  │  │  │  SOUL.md         (personality)  │    │    │  │  startTurn(messages, tools)    │   │  │ │
│  │  │  │  BOOTSTRAP.md    (instructions) │    │    │  │  cancel() / close()            │   │  │ │
│  │  │  │  IDENTITY.md     (who am I)     │    │    │  │  doctor() → health check       │   │  │ │
│  │  │  │  USER.md         (about user)   │    │    │  │                                │   │  │ │
│  │  │  │  HEARTBEAT.md    (background)   │    │    │  └────────────────────────────────┘   │  │ │
│  │  │  │                                 │    │    │                                      │  │ │
│  │  │  │  Load + validate + cache        │    │    │  External LLM Backends:               │  │ │
│  │  │  └───────────────┬─────────────────┘    │    │  ┌──────────┐ ┌──────────┐          │  │ │
│  │  │                  │                      │    │  │  Codex   │ │  Claude  │          │  │ │
│  │  │  ┌───────────────┴─────────────────┐    │    │  │  (CLI)   │ │  (API)   │          │  │ │
│  │  │  │        Context Engine           │    │    │  └──────────┘ └──────────┘          │  │ │
│  │  │  │                                 │    │    │  ┌──────────┐ ┌──────────┐          │  │ │
│  │  │  │  assemble(messages, bootstrap)  │    │    │  │  GPT-5   │ │  Gemini  │          │  │ │
│  │  │  │    → Build system prompt        │    │    │  │  (API)   │ │  (API)   │          │  │ │
│  │  │  │    → Inject tool definitions    │    │    │  └──────────┘ └──────────┘          │  │ │
│  │  │  │    → Apply context window limit │    │    │                                      │  │ │
│  │  │  │  compact(transcript)            │    │    └──────────────────────────────────────┘  │ │
│  │  │  │    → Summarise old turns        │    │                                               │ │
│  │  │  │    → Truncate to token budget   │    │    ┌──────────────────────────────────────┐  │ │
│  │  │  │  prune(results, ttl)            │    │    │      HEARTBEAT SCHEDULER             │  │ │
│  │  │  │    → Remove expired tool results│    │    │                                      │  │ │
│  │  │  └───────────────┬─────────────────┘    │    │  ┌────────────────┐ ┌──────────────┐ │  │ │
│  │  │                  │                      │    │  │  CronTrigger   │ │ IdleDetector │ │  │ │
│  │  │  ┌───────────────┴─────────────────┐    │    │  │                │ │              │ │  │ │
│  │  │  │      36-Hook Lifecycle          │    │    │  │  "0 */2 * * *" │ │ no subagent  │ │  │ │
│  │  │  │          Pipeline               │    │    │  │  every 2 hours │ │ + within     │ │  │ │
│  │  │  │                                 │    │    │  │                │ │ activeHours  │ │  │ │
│  │  │  │  message_received              │    │    │  └────────────────┘ └──────────────┘ │  │ │
│  │  │  │  before_agent_run              │    │    └──────────────────────────────────────┘  │ │
│  │  │  │  before_prompt_build            │    │                                               │ │
│  │  │  │  agent_turn_prepare             │    │    ┌──────────────────────────────────────┐  │ │
│  │  │  │  before_model_resolve           │    │    │        SUBAGENT SPAWNER              │  │ │
│  │  │  │  model_call_started             │    │    │                                      │  │ │
│  │  │  │  llm_input                      │    │    │  spawn(parentRun, childAgentId,      │  │ │
│  │  │  │  llm_output                     │    │    │         task, config)                │  │ │
│  │  │  │  before_tool_call               │    │    │    → Create child ReActEngine         │  │ │
│  │  │  │  after_tool_call                │    │    │    → Full independent loop            │  │ │
│  │  │  │  tool_result_persist            │    │    │    → Return result to parent          │  │ │
│  │  │  │  subagent_spawning              │    │    │                                      │  │ │
│  │  │  │  subagent_delivery_target       │    │    │  Limits:                            │  │ │
│  │  │  │  subagent_spawned               │    │    │    maxSpawnDepth (default 1)         │  │ │
│  │  │  │  subagent_ended                 │    │    │    maxConcurrent (default 2)         │  │ │
│  │  │  │  before_compaction              │    │    │    maxChildrenPerAgent (default 5)   │  │ │
│  │  │  │  after_compaction               │    │    └──────────────────────────────────────┘  │ │
│  │  │  │  model_call_ended               │    │                                               │ │
│  │  │  │  before_agent_finalize          │    │    ┌──────────────────────────────────────┐  │ │
│  │  │  │  before_agent_reply             │    │    │    SANDBOX (Docker / Podman)         │  │ │
│  │  │  │  agent_end                      │    │    │                                      │  │ │
│  │  │  │  message_sending               │    │    │  Container isolation per agent        │  │ │
│  │  │  │  message_sent                   │    │    │  Filesystem bridge (bind mount)      │  │ │
│  │  │  │  session_end                    │    │    │  Network: none / restricted          │  │ │
│  │  │  │  heartbeat_prompt_contribution   │    │    │  Resource limits (CPU, mem)          │  │ │
│  │  │  │                                 │    │    │  Lifecycle: create → exec → destroy  │  │ │
│  │  │  │  (Plus 14 more hook points)     │    │    └──────────────────────────────────────┘  │ │
│  │  │  └───────────────┬─────────────────┘    │                                               │ │
│  │  │                  │                      │    ┌──────────────────────────────────────┐  │ │
│  │  │  ┌───────────────┴─────────────────┐    │    │        BLOCK STREAMING               │  │ │
│  │  │  │         ReAct Engine            │    │    │                                      │  │ │
│  │  │  │                                 │    │    │  Coalesce text blocks (debounce)    │  │ │
│  │  │  │  execute(messages, config)      │    │    │  Human-like delay simulation         │  │ │
│  │  │  │    → Single-turn (no tools)     │    │    │  Typing indicators (SSE events)     │  │ │
│  │  │  │  executeStream(messages,config) │    │    │  Stream to SSE / WebSocket           │  │ │
│  │  │  │    → Streaming SSE response     │    │    └──────────────────────────────────────┘  │ │
│  │  │  │  multiRound(messages, config)   │    │                                               │ │
│  │  │  │    → Full ReAct with tools      │    │    ┌──────────────────────────────────────┐  │ │
│  │  │  │                                 │    │    │       SSE / WS STREAM TO            │  │ │
│  │  │  │  Loop control:                  │    │    │          TRANSPORT                   │  │ │
│  │  │  │    maxRetries / runRetries      │    │    │                                      │  │ │
│  │  │  │    token budget tracking        │    │    │  SseEmitter / Flux<ServerSentEvent>  │  │ │
│  │  │  │    tool call deduplication      │    │    │  WebSocket session broadcast         │  │ │
│  │  │  │    idle timeout detection       │    │    └──────────────────────────────────────┘  │ │
│  │  │  └─────────────────────────────────┘    │                                               │ │
│  │  └─────────────────────────────────────────┘                                               │ │
│  └────────────────────────────────────────────────────────────────────────────────────────────┘ │
│                                                                                                │
│  ┌──────────────────────────────────────────────────────────────────────────────────────────┐ │
│  │                                    SHARED SERVICES                                          │ │
│  │                                                                                            │ │
│  │  ┌───────────────┐ ┌───────────────┐ ┌───────────────┐ ┌───────────────┐ ┌─────────────┐  │ │
│  │  │ Model Catalog │ │ Tool Registry │ │ Memory System │ │ Session Store │ │Skill Reg.   │  │ │
│  │  │               │ │               │ │               │ │               │ │             │  │ │
│  │  │ + Resolver    │ │ + Pipeline    │ │ Tier 1: Redis │ │ JSONL format  │ │ + DAG graph │  │ │
│  │  │ + Fallback    │ │ + Validation  │ │ Tier 2: PG    │ │ Append-only   │ │ + Hot reload│  │ │
│  │  │ + Auto-probe  │ │ + Rate limit  │ │ Tier 3: Disk  │ │ Per session   │ │ + Conflict  │  │ │
│  │  └───────────────┘ └───────────────┘ └───────────────┘ └───────────────┘ └─────────────┘  │ │
│  │                                                                                            │ │
│  │  ┌───────────────┐ ┌───────────────┐ ┌───────────────┐ ┌───────────────┐ ┌─────────────┐  │ │
│  │  │Security Mgr   │ │Approval Store │ │Identity Res.  │ │ TTS Engine    │ │Metrics Coll.│  │ │
│  │  │               │ │               │ │               │ │               │ │             │  │ │
│  │  │ Tool allowlist│ │ Pending queue │ │ Trust levels  │ │ ElevenLabs    │ │ Micrometer  │  │ │
│  │  │ Blocklist     │ │ Timeout mgmt  │ │ Profiles      │ │ Edge TTS      │ │ Prometheus  │  │ │
│  │  │ Rate limiting │ │ Approval UI   │ │ OAuth2/OIDC   │ │ Azure Speech  │ │ Grafana     │  │ │
│  │  └───────────────┘ └───────────────┘ └───────────────┘ └───────────────┘ └─────────────┘  │ │
│  └──────────────────────────────────────────────────────────────────────────────────────────┘ │
│                                                                                                │
│  ┌──────────────────────────────────────────────────────────────────────────────────────────┐ │
│  │                                      PLUGIN SDK                                             │ │
│  │                                                                                            │ │
│  │  ┌────────────────────────────────────────────────────────────────────────────────────┐   │ │
│  │  │                            Plugin Manifest (plugin.yml)                             │   │ │
│  │  │                                                                                     │   │ │
│  │  │  name: "my-plugin"                                                                  │   │ │
│  │  │  version: "1.0.0"                                                                   │   │ │
│  │  │  provides:                                                                          │   │ │
│  │  │    hooks:       [MyHook.class]           # lifecycle interception                     │   │ │
│  │  │    tools:       [MyTool.class]           # @Tool annotated methods                   │   │ │
│  │  │    skills:      [MySkill.class]          # agent capability bundles                  │   │ │
│  │  │    channels:    [MyChannel.class]        # new transport adapters                    │   │ │
│  │  │    providers:   [MyProvider.class]       # custom LLM backends                       │   │ │
│  │  │    models:      [MyModel.class]          # model catalog entries                     │   │ │
│  │  │    sandboxes:   [MySandbox.class]        # custom sandbox implementations            │   │ │
│  │  │    approvals:   [MyApproval.class]       # custom approval handlers                  │   │ │
│  │  │    memories:    [MyMemory.class]         # custom memory backends                    │   │ │
│  │  │                                                                                     │   │ │
│  │  │  classpath: plugin.jar                                                              │   │ │
│  │  │  dependencies:                                                                      │   │ │
│  │  │    - other-plugin:^2.0                                                              │   │ │
│  │  └────────────────────────────────────────────────────────────────────────────────────┘   │ │
│  │                                                                                            │ │
│  │  Plugin lifecycle:  LOAD → VALIDATE → RESOLVE DEPS → INITIALIZE → ENABLE → (DISABLE)      │ │
│  │  Hot-reload:        Watch plugin dir → detect changes → reload without restart             │ │
│  │  Isolation:         Separate ClassLoader per plugin                                       │ │
│  └──────────────────────────────────────────────────────────────────────────────────────────┘ │
│                                                                                                │
│  ┌──────────────────────────────────────────────────────────────────────────────────────────┐ │
│  │                              CROSS-CUTTING CONCERNS                                        │ │
│  │                                                                                            │ │
│  │  ┌─────────────────┐  ┌─────────────────┐  ┌─────────────────┐  ┌─────────────────────┐   │ │
│  │  │ Observability   │  │ Configuration   │  │ Persistence     │  │ Authentication      │   │ │
│  │  │                 │  │                 │  │                 │  │                     │   │ │
│  │  │ OpenTelemetry   │  │ Spring Boot     │  │ PostgreSQL      │  │ OAuth2 / OIDC       │   │ │
│  │  │ Distributed     │  │ Config Tree     │  │   - sessions    │  │ JWT tokens          │   │ │
│  │  │   tracing       │  │ Env overrides   │  │   - transcripts │  │ API keys            │   │ │
│  │  │ Structured      │  │ Hot reload      │  │   - approvals   │  │ Role-based access   │   │ │
│  │  │   logging       │  │ Validation      │  │   - identities  │  │ Multi-tenancy       │   │ │
│  │  │ Metrics export  │  │ Secrets mgmt    │  │ Redis           │  │                     │   │ │
│  │  │                 │  │                 │  │   - cache       │  │                     │   │ │
│  │  │                 │  │                 │  │   - pub/sub     │  │                     │   │ │
│  │  └─────────────────┘  └─────────────────┘  └─────────────────┘  └─────────────────────┘   │ │
│  └──────────────────────────────────────────────────────────────────────────────────────────┘ │
│                                                                                                │
└────────────────────────────────────────────────────────────────────────────────────────────────┘
```

---

## 2. Agent Lifecycle Flow (Post-Renovation)

The complete processing pipeline from inbound message to outbound response, showing all 36 hook points, branching paths (Embedded vs ACP), compaction, heartbeat, and subagent spawning.

```
                                    REQUEST ENTRY
                         (REST / WebSocket / Channel Plugin)
                                      │
                                      │
                         ┌────────────▼────────────┐
                         │ [HOOK: message_received] │
                         │                          │
                         │  Filter / transform       │
                         │  inbound message           │
                         │  Block spam / abuse        │
                         │  Normalise channel →       │
                         │    AgentMessage            │
                         └────────────┬────────────┘
                                      │
                                      │  AgentMessage {channel, text, userId, metadata}
                                      │
                         ┌────────────▼────────────┐
                         │     AGENT ROUTER         │
                         │                          │
                         │  resolve from:            │
                         │    channel name           │
                         │    route binding pattern  │
                         │    @mention target        │
                         │    acp: prefix            │
                         │                          │
                         │  Output: agentId          │
                         └────────────┬────────────┘
                                      │
                         ┌────────────▼────────────┐
                         │  AGENT CONFIG RESOLVER   │
                         │                          │
                         │  system.defaults          │
                         │    → agent.defaults       │
                         │      → @Agent annotation  │
                         │        → runtime override │
                         │                          │
                         │  Output:                  │
                         │    ResolvedAgentConfig    │
                         └────────────┬────────────┘
                                      │
                         ┌────────────▼────────────┐
                         │ [HOOK: before_agent_run] │
                         │                          │
                         │  Gate check:              │
                         │    PASS → continue        │
                         │    BLOCK → return reason  │
                         └────────────┬────────────┘
                                      │
                         ┌────────────▼────────────┐
                         │[HOOK: before_agent_start]│
                         │   (DEPRECATED compat)     │
                         │   Maps to before_agent_run│
                         └────────────┬────────────┘
                                      │
                         ┌────────────▼────────────┐
                         │    RUNTIME DISPATCH       │
                         │                          │
                         │  agentConfig.runtime ==   │
                         │    "embedded" ?           │
                         │    "acp" ?                │
                         └──────┬──────────┬────────┘
                                │          │
               EMBEDDED PATH    │          │    ACP PATH
                                │          │
               ┌────────────────▼──┐  ┌────▼──────────────────────────┐
               │ BOOTSTRAP LOADER  │  │ AcpRuntime.ensureSession()     │
               │                   │  │                                │
               │ Load from disk:   │  │ Connect to external provider   │
               │  AGENTS.md        │  │ Authenticate session           │
               │  SOUL.md          │  │ Negotiate capabilities         │
               │  BOOTSTRAP.md     │  │                                │
               │  IDENTITY.md      │  │ AcpRuntime.startTurn()         │
               │  USER.md          │  │                                │
               │  HEARTBEAT.md     │  │ Send messages + tools          │
               │                   │  │ Receive streaming events       │
               │ Validate required │  │ Map ACP events → SSE           │
               │ Cache in memory   │  │                                │
               └───────┬───────────┘  │ AcpRuntime.cancel()/close()    │
                       │              │   on abort / timeout            │
               ┌───────▼───────────┐  └────────────────────────────────┘
               │  CONTEXT ENGINE   │
               │                   │
               │  assemble():       │
               │   Load session     │
               │     history from   │
               │     SessionStore   │
               │   Inject bootstrap │
               │     content        │
               │   Apply context    │
               │     window limits  │
               │   Build system     │
               │     prompt         │
               │   Attach tool      │
               │     definitions    │
               └───────┬───────────┘
                       │
               ┌───────▼──────────────────────┐
               │[HOOK: before_prompt_build]   │
               │                              │
               │  Modify system prompt         │
               │  Inject additional context    │
               │  Add custom instructions      │
               └───────┬──────────────────────┘
                       │
               ┌───────▼──────────────────────┐
               │[HOOK: agent_turn_prepare]    │
               │                              │
               │  Final prompt modifications  │
               │  Inject user preferences     │
               │  Apply persona / tone        │
               └───────┬──────────────────────┘
                       │
               ┌───────▼──────────────────────┐
               │[HOOK: before_model_resolve]  │
               │                              │
               │  Intercept model selection    │
               │  Override provider per-request│
               │  Apply routing rules          │
               └───────┬──────────────────────┘
                       │
               ┌───────▼──────────────────────┐
               │     MODEL RESOLUTION          │
               │                              │
               │  1. explicit model (request)  │
               │  2. agent default model       │
               │  3. system default model      │
               │  4. fallback chain:           │
               │     gpt5 → claude → deepseek  │
               │  5. auto-probe health check   │
               │     → skip unhealthy models   │
               └───────┬──────────────────────┘
                       │
               ┌───────▼──────────────────────┐
               │[HOOK: model_call_started]    │
               │                              │
               │  Log / audit LLM call start  │
               │  Track token budget           │
               │  Emit SSE: model_call_started │
               └───────┬──────────────────────┘
                       │
                       │
               ╔═══════▼══════════════════════════════════════════════╗
               ║                R E A C T   L O O P                   ║
               ║                                                     ║
               ║  round = 0                                          ║
               ║  while (round < maxRetries) {                       ║
               ║                                                     ║
               ║    ┌───────────────────────────────────────────┐    ║
               ║    │  [HOOK: llm_input]                       │    ║
               ║    │                                           │    ║
               ║    │  Inspect prompt sent to LLM               │    ║
               ║    │  Redact sensitive data (opt)              │    ║
               ║    │  Log for debugging                       │    ║
               ║    └───────────────────┬───────────────────────┘    ║
               ║                        │                            ║
               ║    ┌───────────────────▼───────────────────────┐    ║
               ║    │           LLM CALL                        │    ║
               ║    │                                           │    ║
               ║    │  model.call(messages, tools, config)      │    ║
               ║    │      OR                                   │    ║
               ║    │  model.stream(messages, tools, config)    │    ║
               ║    │                                           │    ║
               ║    │  Emit SSE: thinking_start/delta/end       │    ║
               ║    └───────────────────┬───────────────────────┘    ║
               ║                        │                            ║
               ║    ┌───────────────────▼───────────────────────┐    ║
               ║    │  [HOOK: llm_output]                      │    ║
               ║    │                                           │    ║
               ║    │  Inspect raw LLM response                 │    ║
               ║    │  Content moderation filter                │    ║
               ║    │  Parse tool calls from response           │    ║
               ║    │  Log token usage                         │    ║
               ║    └───────────────────┬───────────────────────┘    ║
               ║                        │                            ║
               ║    ┌───────────────────▼───────────────────────┐    ║
               ║    │         TOOL DETECTION                    │    ║
               ║    │                                           │    ║
               ║    │  if (no tool calls) {                     │    ║
               ║    │    textReply = response.getContent()      │    ║
               ║    │    BREAK  // exit loop                    │    ║
               ║    │  }                                        │    ║
               ║    │                                           │    ║
               ║    │  // Has tool calls                        │    ║
               ║    │  for each toolCall in response {          │    ║
               ║    │                                           │    ║
               ║    │    ┌──────────────────────────────────┐   │    ║
               ║    │    │ [HOOK: before_tool_call]        │   │    ║
               ║    │    │                                  │   │    ║
               ║    │    │  Gate: ALLOW / DENY_WITH_REASON │   │    ║
               ║    │    │  Validate tool args             │   │    ║
               ║    │    │  Check rate limits              │   │    ║
               ║    │    │  Apply budget constraints       │   │    ║
               ║    │    └────────────┬─────────────────────┘   │    ║
               ║    │                 │                         │    ║
               ║    │                 │  if ALLOW:              │    ║
               ║    │                 │                         │    ║
               ║    │    ┌────────────▼─────────────────────┐   │    ║
               ║    │    │   TOOL APPROVAL FLOW (if needed) │   │    ║
               ║    │    │                                   │   │    ║
               ║    │    │  Check tool.approvalRequired     │   │    ║
               ║    │    │    → approval_request SSE event  │   │    ║
               ║    │    │      {toolCallId, name, args}    │   │    ║
               ║    │    │    → Wait for frontend response  │   │    ║
               ║    │    │      {approved: true/false}      │   │    ║
               ║    │    │    → Timeout → auto-deny         │   │    ║
               ║    │    └────────────┬─────────────────────┘   │    ║
               ║    │                 │                         │    ║
               ║    │    ┌────────────▼─────────────────────┐   │    ║
               ║    │    │   SANDBOX DISPATCH (if needed)   │   │    ║
               ║    │    │                                   │   │    ║
               ║    │    │  Check tool.sandboxRequired      │   │    ║
               ║    │    │    → Create/acquire container    │   │    ║
               ║    │    │    → Bind-mount workspace        │   │    ║
               ║    │    │    → Execute inside container    │   │    ║
               ║    │    │    → Capture stdout/stderr       │   │    ║
               ║    │    │    → Destroy/recycle container   │   │    ║
               ║    │    │  Else: execute on host           │   │    ║
               ║    │    └────────────┬─────────────────────┘   │    ║
               ║    │                 │                         │    ║
               ║    │    ┌────────────▼─────────────────────┐   │    ║
               ║    │    │        EXECUTE TOOL              │   │    ║
               ║    │    │                                   │   │    ║
               ║    │    │  ToolPipeline.execute(toolCall)  │   │    ║
               ║    │    │    → Resolve tool instance       │   │    ║
               ║    │    │    → Deserialise args            │   │    ║
               ║    │    │    → Call tool.execute()         │   │    ║
               ║    │    │    → Wrap errors gracefully      │   │    ║
               ║    │    │    → Return ToolResult           │   │    ║
               ║    │    └────────────┬─────────────────────┘   │    ║
               ║    │                 │                         │    ║
               ║    │    ┌────────────▼─────────────────────┐   │    ║
               ║    │    │ [HOOK: after_tool_call]         │   │    ║
               ║    │    │                                   │   │    ║
               ║    │    │  Log result / side effects       │   │    ║
               ║    │    │  Track tool usage metrics        │   │    ║
               ║    │    │  Enrich result with metadata     │   │    ║
               ║    │    └────────────┬─────────────────────┘   │    ║
               ║    │                 │                         │    ║
               ║    │    ┌────────────▼─────────────────────┐   │    ║
               ║    │    │ [HOOK: tool_result_persist]     │   │    ║
               ║    │    │                                   │   │    ║
               ║    │    │  Persist to transcript           │   │    ║
               ║    │    │  Trim if result too large        │   │    ║
               ║    │    │  Set TTL for auto-pruning        │   │    ║
               ║    │    └────────────┬─────────────────────┘   │    ║
               ║    │                 │                         │    ║
               ║    │    continue loop (append tool result      │    ║
               ║    │                  to messages list)        │    ║
               ║    │  } // end for each toolCall               │    ║
               ║    │                                           │    ║
               ║    └───────────────────────────────────────────┘    ║
               ║                                                     ║
               ║    ┌───────────────────────────────────────────┐    ║
               ║    │         SUBAGENT SPAWN CHECK              │    ║
               ║    │                                           │    ║
               ║    │  if (toolCall.name == "delegate_to_agent")│   ║
               ║    │                                           │    ║
               ║    │    ┌─────────────────────────────────┐    │    ║
               ║    │    │ [HOOK: subagent_spawning]      │    │    ║
               ║    │    │                                  │    │    ║
               ║    │    │  Gate: allow / deny             │    │    ║
               ║    │    │  Transform task / config        │    │    ║
               ║    │    └────────────┬────────────────────┘    │    ║
               ║    │                 │                         │    ║
               ║    │    ┌────────────▼────────────────────┐    │    ║
               ║    │    │ [HOOK: subagent_delivery_target]│   │    ║
               ║    │    │                                  │    │    ║
               ║    │    │  Resolve delivery channel        │    │    ║
               ║    │    │  (which transport to use)        │    │    ║
               ║    │    └────────────┬────────────────────┘    │    ║
               ║    │                 │                         │    ║
               ║    │    ┌────────────▼────────────────────┐    │    ║
               ║    │    │  CHECK DEPTH & CONCURRENCY      │    │    ║
               ║    │    │                                  │    │    ║
               ║    │    │  if (depth >= maxSpawnDepth)    │    │    ║
               ║    │    │    → REJECT "max depth reached" │    │    ║
               ║    │    │  if (activeChildren >= maxConc) │    │    ║
               ║    │    │    → QUEUE or REJECT            │    │    ║
               ║    │    └────────────┬────────────────────┘    │    ║
               ║    │                 │  ALLOWED                │    ║
               ║    │    ┌────────────▼────────────────────┐    │    ║
               ║    │    │  SPAWN CHILD ReActEngine        │    │    ║
               ║    │    │                                  │    │    ║
               ║    │    │  Create isolated session         │    │    ║
               ║    │    │  Load child bootstrap files     │    │    ║
               ║    │    │  Run full recursive pipeline    │    │    ║
               ║    │    │  (enter lifecycle recursively)  │    │    ║
               ║    │    │  Await result (or stream)       │    │    ║
               ║    │    └────────────┬────────────────────┘    │    ║
               ║    │                 │                         │    ║
               ║    │    ┌────────────▼────────────────────┐    │    ║
               ║    │    │ [HOOK: subagent_spawned]       │    │    ║
               ║    │    │                                  │    │    ║
               ║    │    │  Notify parent agent            │    │    ║
               ║    │    │  Emit SSE: subagent_spawned     │    │    ║
               ║    │    └────────────┬────────────────────┘    │    ║
               ║    │                 │                         │    ║
               ║    │    ┌────────────▼────────────────────┐    │    ║
               ║    │    │ [HOOK: subagent_ended]         │    │    ║
               ║    │    │                                  │    │    ║
               ║    │    │  Cleanup resources              │    │    ║
               ║    │    │  Emit SSE: subagent_ended       │    │    ║
               ║    │    └────────────┬────────────────────┘    │    ║
               ║    │                 │                         │    ║
               ║    │    Return ToolResult to parent LLM        │    ║
               ║    │    as tool call response                  │    ║
               ║    │                                           │    ║
               ║    └───────────────────────────────────────────┘    ║
               ║                                                     ║
               ║    round++                                         ║
               ║    check runRetries budget                         ║
               ║  } // end while                                   ║
               ╚═════════════════════════════════════════════════════╝
                       │
                       │  (after loop exits: text reply or max retries)
                       │
               ┌───────▼──────────────────────┐
               │[HOOK: model_call_ended]      │
               │                              │
               │  Log / audit LLM call end    │
               │  Record token usage          │
               │  Emit SSE: model_call_ended  │
               └───────┬──────────────────────┘
                       │
               ┌───────▼──────────────────────┐
               │[HOOK: before_agent_finalize] │
               │                              │
               │  Revise gate:                 │
               │    CONTINUE → more turns     │
               │    REVISE   → edit reply     │
               │    FINALIZE → proceed        │
               └───────┬──────────────────────┘
                       │
               ┌───────▼──────────────────────┐
               │[HOOK: before_agent_reply]    │
               │                              │
               │  Filter / transform reply    │
               │  Apply content policies      │
               │  Format for channel          │
               └───────┬──────────────────────┘
                       │
               ┌───────▼──────────────────────┐
               │     BLOCK STREAMING           │
               │                              │
               │  Coalesce text blocks         │
               │   (debounce 50ms)             │
               │  Apply human-like delay       │
               │   (5-20ms per char config)    │
               │  Send typing indicators       │
               │   SSE: typing_start/stop      │
               │  Stream to transport:         │
               │    SseEmitter.send(event)     │
               │    WebSocketSession.send()    │
               │    Channel.sendMessage()      │
               └───────┬──────────────────────┘
                       │
               ┌───────▼──────────────────────┐
               │     COMPACTION CHECK          │
               │                              │
               │  if (transcriptSize > limit) {│
               │                              │
               │    ┌──────────────────────┐   │
               │    │[HOOK: before_compact]│   │
               │    │  Pre-compaction hook │   │
               │    └──────────┬───────────┘   │
               │               │               │
               │    ┌──────────▼───────────┐   │
               │    │  Summarise old turns │   │
               │    │  Truncate to budget  │   │
               │    │  Inject post-compact │   │
               │    │    sections           │   │
               │    └──────────┬───────────┘   │
               │               │               │
               │    ┌──────────▼───────────┐   │
               │    │[HOOK: after_compact] │   │
               │    │  Post-compaction hook│   │
               │    └──────────────────────┘   │
               │  }                            │
               └───────┬──────────────────────┘
                       │
               ┌───────▼──────────────────────┐
               │     CONTEXT PRUNING           │
               │                              │
               │  For each tool result:        │
               │    if (now - timestamp > TTL) │
               │      → remove from context   │
               │  Trim old user messages       │
               │   beyond keepWindow           │
               └───────┬──────────────────────┘
                       │
               ┌───────▼──────────────────────┐
               │  [HOOK: agent_end]           │
               │                              │
               │  Final cleanup               │
               │  Notification dispatch        │
               │  Release resources            │
               │  Emit SSE: agent_end         │
               └───────┬──────────────────────┘
                       │
               ┌───────▼──────────────────────┐
               │  [HOOK: message_sending]     │
               │                              │
               │  Final outbound filter        │
               │  Channel-specific formatting  │
               │  Attachment handling          │
               └───────┬──────────────────────┘
                       │
               ┌───────▼──────────────────────┐
               │     SESSION PERSIST           │
               │                              │
               │  Write JSONL transcript       │
               │   {turn, role, content, ts}   │
               │  Update SessionStore          │
               │  Emit SSE: done              │
               └───────┬──────────────────────┘
                       │
               ┌───────▼──────────────────────┐
               │  [HOOK: session_end]          │
               │                              │
               │  If session ending:           │
               │    Archive transcript         │
               │    Update analytics           │
               │    Notify webhooks            │
               └───────┬──────────────────────┘
                       │
               ┌───────▼──────────────────────┐
               │  [HOOK: message_sent]         │
               │                              │
               │  Post-delivery notification   │
               │  Webhook callbacks            │
               │  Analytics event              │
               └──────────────────────────────┘


                          ╔═══════════════════════════════╗
                          ║   HEARTBEAT (background)      ║
                          ║   ─────────────────────────   ║
                          ║                               ║
                          ║  CronTrigger fires            ║
                          ║    │                          ║
                          ║  Check activeHours window     ║
                          ║    │ (e.g., 08:00-22:00)      ║
                          ║  Check skipWhenBusy           ║
                          ║    │ (no active subagents)    ║
                          ║  Check cooldown period        ║
                          ║    │ (min interval between)   ║
                          ║    │                          ║
                          ║  Create isolated session      ║
                          ║  Load light context           ║
                          ║    │ (HEARTBEAT.md only)      ║
                          ║    │                          ║
                          ║  Single-turn ReAct            ║
                          ║    │ (no user message)        ║
                          ║    │                          ║
                          ║  [HOOK: heartbeat_prompt_     ║
                          ║          contribution]        ║
                          ║    │                          ║
                          ║  Deliver result to target     ║
                          ║    │ (channel/user/webhook)   ║
                          ║                               ║
                          ╚═══════════════════════════════╝
```

---

## 3. Config Resolution Hierarchy

The complete configuration merge chain, showing how settings flow from system-wide defaults down to a single runtime-invoked agent instance.

```
                            lyclaw.agent.defaults
                        (application.yml / application.properties)
                                      │
           ┌──────────────────────────┼──────────────────────────┐
           │                          │                          │
           ▼                          ▼                          ▼
   ┌───────────────┐         ┌───────────────┐         ┌───────────────┐
   │  model:       │         │  skills:      │         │  heartbeat:   │
   │   primary:    │         │   - shell     │         │   enabled:    │
   │     deepseek  │         │   - file      │         │     true      │
   │   fallback:   │         │   - web_search│         │   cron:       │
   │     [claude]  │         │               │         │     "0 */4    │
   │   thinking:   │         │  contextLimits│         │      * * *"   │
   │     low       │         │   maxTokens:  │         │   activeHours:│
   │               │         │     200000    │         │     08:00-    │
   │  sandbox:     │         │   maxMessages │         │     22:00     │
   │   enabled:    │         │     : 200     │         │   skipWhen-   │
   │     false     │         │   compactAt:  │         │     Busy: true│
   │   engine:     │         │     0.8       │         │               │
   │     docker    │         │               │         │  subagents:   │
   │               │         │  approval:    │         │   maxDepth:   │
   │  thinking:    │         │   mode:       │         │     1         │
   │   budget:     │         │     manual    │         │   maxConcur:  │
   │     16000     │         │   timeout:    │         │     2         │
   │               │         │     120s      │         │   maxChildren │
   │               │         │               │         │     : 5       │
   └───────┬───────┘         └───────┬───────┘         └───────┬───────┘
           │                         │                         │
           └─────────────────────────┼─────────────────────────┘
                                     │
                                     │  deepMerge()
                                     │  (nested map merge, lists concatenate,
                                     │   scalars overwrite)
                                     │
                                     ▼
                     ┌─────────────────────────────────┐
                     │  @Agent annotation              │
                     │  on ChatAgent interface         │
                     │                                 │
                     │  @Agent(                        │
                     │    id = "chat",                 │
                     │    name = "Chat Assistant",     │
                     │    model = "deepseek-v4",        │
                     │    thinking = "high",            │
                     │    skills = {"code-review"},     │
                     │    bootstrap = {                 │
                     │      "AGENTS.md",               │
                     │      "SOUL.md"                  │
                     │    },                           │
                     │    approval = MANUAL,            │
                     │    sandbox = true                │
                     │  )                              │
                     │                                 │
                     └───────────────┬─────────────────┘
                                     │
                                     │  deepMerge()
                                     │  (annotation values overwrite defaults)
                                     │
                                     ▼
                     ┌─────────────────────────────────┐
                     │  ChatRequest runtime overrides   │
                     │  (from HTTP request body)        │
                     │                                 │
                     │  {                               │
                     │    "message": "...",             │
                     │    "planningMode": true,         │
                     │    "model": "claude-opus-4.5",   │
                     │    "thinking": "ultra",           │
                     │    "sessionKey": "main-abc123"   │
                     │  }                               │
                     │                                 │
                     └───────────────┬─────────────────┘
                                     │
                                     │  deepMerge()
                                     │  (runtime overrides win, except
                                     │   security-sensitive fields)
                                     │
                                     ▼
                     ┌─────────────────────────────────────────────────────────┐
                     │                  ResolvedAgentConfig                     │
                     │                  (immutable snapshot)                    │
                     │                                                         │
                     │  ┌─────────────────────────────────────────────────┐    │
                     │  │ id:           "chat"          (from annotation)  │    │
                     │  │ name:         "Chat Assistant"(from annotation)  │    │
                     │  │ model:        "claude-opus-4.5"(runtime override)│    │
                     │  │ thinking:     "ultra"         (runtime override)  │    │
                     │  │ thinkingBudget: 16000         (from defaults)     │    │
                     │  │ skills:    ["shell","file",   (merged: defaults   │    │
                     │  │             "web_search",      + annotation)      │    │
                     │  │             "code-review"]                        │    │
                     │  │ sandbox:      true            (from annotation)   │    │
                     │  │ sandboxEngine:"docker"        (from defaults)     │    │
                     │  │ approval:     MANUAL          (from annotation)   │    │
                     │  │ approvalTimeout: 120s         (from defaults)     │    │
                     │  │ contextMaxTokens: 200000      (from defaults)     │    │
                     │  │ contextMaxMessages: 200       (from defaults)     │    │
                     │  │ contextCompactAt: 0.8          (from defaults)     │    │
                     │  │ heartbeatEnabled: true        (from defaults)     │    │
                     │  │ heartbeatCron: "0 */4 * * *"  (from defaults)     │    │
                     │  │ heartbeatActiveHours:"08-22"  (from defaults)     │    │
                     │  │ subagentMaxDepth: 1           (from defaults)     │    │
                     │  │ subagentMaxConcurrent: 2      (from defaults)     │    │
                     │  │ subagentMaxChildren: 5        (from defaults)     │    │
                     │  │ bootstrap: ["AGENTS.md",      (from annotation)   │    │
                     │  │             "SOUL.md"]                            │    │
                     │  │ planningMode:  true           (from runtime)      │    │
                     │  └─────────────────────────────────────────────────┘    │
                     │                                                         │
                     └────────────────────────┬────────────────────────────────┘
                                              │
                                              │  Consumed by:
                                              │
              ┌───────────────┬───────────────┼───────────────┬───────────────┐
              │               │               │               │               │
              ▼               ▼               ▼               ▼               ▼
     ┌──────────────┐ ┌──────────────┐ ┌──────────────┐ ┌──────────────┐ ┌──────────────┐
     │ AgentInvoc.  │ │ ReActEngine  │ │ Bootstrap    │ │ Compaction   │ │ Subagent     │
     │ Handler      │ │              │ │ Loader       │ │ Engine       │ │ Spawner      │
     │              │ │ execute()    │ │ load()       │ │ compact()    │ │ spawn()      │
     │ invoke()     │ │ executeStream│ │              │ │              │ │              │
     └──────────────┘ └──────────────┘ └──────────────┘ └──────────────┘ └──────────────┘
              │               │               │               │               │
              └───────────────┴───────────────┼───────────────┴───────────────┘
                                              │
                              ┌───────────────┼───────────────┐
                              │               │               │
                              ▼               ▼               ▼
                     ┌──────────────┐ ┌──────────────┐ ┌──────────────┐
                     │ Heartbeat    │ │ All Hooks    │ │ SSE Streaming│
                     │ Scheduler    │ │ (36 points)  │ │              │
                     │              │ │              │ │ emit events  │
                     │ schedule()   │ │ intercept()  │ │ with config  │
                     └──────────────┘ └──────────────┘ └──────────────┘
```

### Merge Rules

| Precedence (low→high) | Source | Override Behaviour |
|------------------------|--------|-------------------|
| 1 (lowest) | `lyclaw.agent.defaults` in `application.yml` | Base values for all agents |
| 2 | Agent-type defaults (`lyclaw.agent.chat.*`) | Override system defaults for a specific agent type |
| 3 | `@Agent` annotation on interface | Override defaults for this agent definition |
| 4 (highest) | `ChatRequest` body fields | Per-request overrides (user-controlled) |

**Security-sensitive fields** (e.g., `sandbox.enabled`, `approval.mode`) can be locked at a given level via `final: true` to prevent lower-precedence layers or user overrides from weakening security policy.

---

## 4. Subagent Delegation Tree

Illustrates the recursive subagent spawning model: parent agents delegate work to child agents, which can in turn spawn grandchildren, subject to configurable depth and concurrency limits.

```
Session: main-abc123
═══════════════════════════════════════════════════════════════════════════════

┌─────────────────────────────────────────────────────────────────────────────┐
│  Agent "chat"  (depth = 0, parent = null)                                    │
│  ─────────────────────────────────────────                                   │
│  Session: main-abc123                                                        │
│  Config:  ResolvedAgentConfig(chat)                                          │
│  Tools:   [web_search, file_read, file_write, shell, delegate_to_agent]     │
│                                                                              │
│  ┌─ User: "Please do a full code review of the PR, run the test suite,      │
│  │          and check for security issues."                                  │
│  │                                                                           │
│  ├─ LLM (thinking): "This is a complex multi-step task. I should delegate    │
│  │   the code review to the code-reviewer agent, tests to the tester agent,  │
│  │   and security to the security-scanner agent."                            │
│  │                                                                           │
│  ├─ Tool call #1: delegate_to_agent("code-reviewer", {                        │
│  │       task: "Review PR #342 for bugs and style issues",                   │
│  │       files: ["src/main/**/*.java"],                                      │
│  │       context: "Focus on null safety and concurrency"                     │
│  │   })                                                                      │
│  │   │                                                                       │
│  │   ├─ [Spawning subagent at depth 1]                                       │
│  │   │                                                                       │
│  │   ▼                                                                       │
│  │  ┌───────────────────────────────────────────────────────────────────┐   │
│  │  │  Subagent "code-reviewer"  (depth = 1, parent = "chat")           │   │
│  │  │  ──────────────────────────────────────────────────────────────    │   │
│  │  │  Session: main-abc123/subagent/code-reviewer/uuid-a1b2c3d4        │   │
│  │  │  Config:  ResolvedAgentConfig(code-reviewer)                       │   │
│  │  │  Tools:   [file_read, file_search, grep, delegate_to_agent]       │   │
│  │  │  Own bootstrap: AGENTS.md (code-reviewer role), SOUL.md           │   │
│  │  │                                                                    │   │
│  │  │  ┌─ System prompt (assembled from code-reviewer bootstrap)         │   │
│  │  │  ├─ LLM: "Let me read the changed files first..."                  │   │
│  │  │  ├─ Tool: file_read("src/main/java/...")                          │   │
│  │  │  ├─ Tool: file_read("src/main/java/...")                          │   │
│  │  │  ├─ LLM: "I found several issues. Let me also run the linter."    │   │
│  │  │  │                                                                 │   │
│  │  │  ├─ Tool call: delegate_to_agent("tester", {                       │   │
│  │  │  │     task: "Run unit tests for the changed files",               │   │
│  │  │  │     testCommand: "mvn test -pl affected-module"                 │   │
│  │  │  │ })                                                              │   │
│  │  │  │   │                                                             │   │
│  │  │  │   ├─ [Spawning subagent at depth 2]                             │   │
│  │  │  │   │                                                             │   │
│  │  │  │   ▼                                                             │   │
│  │  │  │  ┌─────────────────────────────────────────────────────────┐   │   │
│  │  │  │  │  Subagent "tester"  (depth = 2, parent = "code-reviewer")│   │   │
│  │  │  │  │  ───────────────────────────────────────────────────     │   │   │
│  │  │  │  │  Session: main-abc123/subagent/code-reviewer/uuid-a1b2/  │   │   │
│  │  │  │  │           subagent/tester/uuid-e5f6g7h8                  │   │   │
│  │  │  │  │  Config:  ResolvedAgentConfig(tester)                     │   │   │
│  │  │  │  │  Tools:   [shell, file_read]                              │   │   │
│  │  │  │  │                                                           │   │   │
│  │  │  │  │  Check: depth(2) < maxSpawnDepth(1) ?                    │   │   │
│  │  │  │  │    → if maxSpawnDepth=2: ALLOWED                          │   │   │
│  │  │  │  │    → if maxSpawnDepth=1 (default): REJECTED               │   │   │
│  │  │  │  │      Error: "Cannot spawn subagent: max spawn depth       │   │   │
│  │  │  │  │              reached (depth=2 > max=1)"                   │   │   │
│  │  │  │  │                                                           │   │   │
│  │  │  │  │  [Assuming maxSpawnDepth=2 for this example:]             │   │   │
│  │  │  │  │                                                           │   │   │
│  │  │  │  │  ├─ LLM: "Running tests..."                               │   │   │
│  │  │  │  │  ├─ Tool: shell("mvn test -pl affected-module")           │   │   │
│  │  │  │  │  ├─ ToolResult: "Tests run: 47, Failures: 2, Errors: 0"  │   │   │
│  │  │  │  │  ├─ LLM: "2 tests failed. Let me check the logs."         │   │   │
│  │  │  │  │  ├─ Tool: file_read("target/surefire-reports/...")        │   │   │
│  │  │  │  │  └─ LLM: "The failures are in UserServiceTest, caused by  │   │   │
│  │  │  │  │      a null pointer in the new validation logic."          │   │   │
│  │  │  │  │                                                           │   │
│  │  │  │  │  Return: {                                                 │   │   │
│  │  │  │  │    testsRun: 47,                                           │   │   │
│  │  │  │  │    failures: 2,                                            │   │   │
│  │  │  │  │    failureDetails: "UserServiceTest: NPE in validate()",   │   │   │
│  │  │  │  │    elapsedMs: 45200                                        │   │   │
│  │  │  │  │  }                                                         │   │   │
│  │  │  │  └─────────────────────────────────────────────────────────┘   │   │
│  │  │  │                                                                 │   │
│  │  │  └─ Receives tester result → incorporates into review              │   │
│  │  │                                                                     │   │
│  │  │  └─ LLM: "Code review complete. Found 2 bugs (1 null safety,       │   │
│  │  │      1 concurrency). Tests confirm 2 failures. Recommend fixes."   │   │
│  │  │                                                                     │   │
│  │  │  Return: {                                                          │   │
│  │  │    bugsFound: 2,                                                    │   │
│  │  │    testFailures: 2,                                                 │   │
│  │  │    reviewSummary: "...",                                            │   │
│  │  │    elapsedMs: 120000                                                │   │
│  │  │  }                                                                  │   │
│  │  └───────────────────────────────────────────────────────────────────┘   │
│  │                                                                           │
│  ├─ Tool call #2: delegate_to_agent("security-scanner", {                    │
│  │       task: "Scan changed files for security vulnerabilities",            │
│  │       files: ["src/main/**/*.java"]                                       │
│  │   })                                                                      │
│  │   │                                                                       │
│  │   ├─ [Spawning subagent at depth 1 — if maxConcurrent=2, this runs       │
│  │   │  in parallel with code-reviewer if it were still running]             │
│  │   │                                                                       │
│  │   ▼                                                                       │
│  │  ┌───────────────────────────────────────────────────────────────────┐   │
│  │  │  Subagent "security-scanner"  (depth = 1, parent = "chat")        │   │
│  │  │  ──────────────────────────────────────────────────────────────    │   │
│  │  │  Session: main-abc123/subagent/security-scanner/uuid-i9j0k1l2      │   │
│  │  │  ... (runs full ReAct loop, similar to above)                       │   │
│  │  │                                                                     │   │
│  │  │  Return: {                                                          │   │
│  │  │    vulnerabilitiesFound: 1,                                         │   │
│  │  │    severity: "medium",                                              │   │
│  │  │    details: "SQL injection risk in UserQueryBuilder",               │   │
│  │  │    elapsedMs: 35000                                                 │   │
│  │  │  }                                                                  │   │
│  │  └───────────────────────────────────────────────────────────────────┘   │
│  │                                                                           │
│  └─ LLM: "I have the results from both subagents. Here is a consolidated     │
│      report: 2 bugs found by code-reviewer (with 2 matching test failures), │
│      and 1 medium-severity security issue found by the scanner."             │
│                                                                              │
│  Final reply to user (streamed via SSE)                                      │
└─────────────────────────────────────────────────────────────────────────────┘


                          Concurrency & Depth Limits
                          ═══════════════════════════

     ┌──────────────────────────────────────────────────────────────┐
     │  maxSpawnDepth = 1  (default)                                 │
     │    chat(depth=0) → code-reviewer(depth=1) → tester(depth=2)  │
     │                                                    ✗ REJECT  │
     │                                                               │
     │  maxSpawnDepth = 2  (relaxed)                                  │
     │    chat(depth=0) → code-reviewer(depth=1) → tester(depth=2)  │
     │                                                    ✓ ALLOW   │
     │                                                               │
     │  maxConcurrent = 2                                             │
     │    chat can spawn up to 2 subagents running simultaneously    │
     │    If a 3rd is requested while 2 are active: QUEUED or DENIED │
     │                                                               │
     │  maxChildrenPerAgent = 5                                       │
     │    chat can spawn at most 5 total subagents in its lifetime   │
     └──────────────────────────────────────────────────────────────┘


                          Session Key Hierarchy
                          ══════════════════════

     main-abc123
       ├── main-abc123/subagent/code-reviewer/uuid-a1b2c3d4
       │     └── main-abc123/subagent/code-reviewer/uuid-a1b2c3d4/
       │           subagent/tester/uuid-e5f6g7h8
       └── main-abc123/subagent/security-scanner/uuid-i9j0k1l2

     Each subagent has its own:
       - Session key (hierarchical, derived from parent)
       - Transcript file (JSONL, isolated)
       - ResolvedAgentConfig (merged independently)
       - Bootstrap files (loaded from agent's own directory)
       - ReActEngine instance (full pipeline, recursively)
```

---

## 5. SSE Event Stream (Complete)

The full Server-Sent Events sequence for a typical request involving thinking, tool calls with approval, subagent spawning, text streaming, and compaction.

```
                              SSE Event Stream
                              ════════════════

  Client connects:  GET /api/sse/stream?sessionKey=main-abc123
  Server responds:  Content-Type: text/event-stream
                    Connection: keep-alive
                    Cache-Control: no-cache

  ╔══════════════════════════════════════════════════════════════════════════╗
  ║                         SSE EVENT SEQUENCE                              ║
  ╚══════════════════════════════════════════════════════════════════════════╝

  ┌────────────────────────────────────────────────────────────────────────┐
  │ PHASE 1: INITIALISATION                                                 │
  ├────────────────────────────────────────────────────────────────────────┤

  event: run_start
  data: {
    "runId": "run-20260520-001",
    "agentId": "chat",
    "agentName": "Chat Assistant",
    "sessionKey": "main-abc123",
    "timestamp": "2026-05-20T14:30:00.000Z",
    "config": {
      "model": "claude-opus-4.5",
      "thinking": "ultra",
      "planningMode": true
    }
  }

  event: bootstrap_loaded
  data: {
    "files": ["AGENTS.md", "SOUL.md", "IDENTITY.md"],
    "totalChars": 3500,
    "totalTokens": 1200,
    "loadDurationMs": 12
  }

  event: context_built
  data: {
    "messageCount": 12,
    "toolResultCount": 5,
    "bootstrapChars": 3500,
    "tokenEstimate": 8500,
    "contextWindowUsed": "4.25%",
    "buildDurationMs": 8
  }

  event: model_resolved
  data: {
    "provider": "anthropic",
    "model": "claude-opus-4.5",
    "thinking": "ultra",
    "thinkingBudget": 16000,
    "resolutionPath": ["runtime_override", "annotation", "defaults"],
    "fallbackChain": ["claude-sonnet-4.5", "deepseek-v4-pro"],
    "healthCheckPassed": true
  }

  event: model_call_started
  data: {
    "runId": "run-20260520-001",
    "provider": "anthropic",
    "model": "claude-opus-4.5",
    "timestamp": "2026-05-20T14:30:00.120Z"
  }

  ┌────────────────────────────────────────────────────────────────────────┐
  │ PHASE 2: FIRST THINKING + TOOL CALL                                     │
  ├────────────────────────────────────────────────────────────────────────┤

  event: thinking_start
  data: {
    "runId": "run-20260520-001",
    "timestamp": "2026-05-20T14:30:01.050Z"
  }

  event: thinking_delta
  data: {
    "runId": "run-20260520-001",
    "text": "I need to search for the relevant files first. Let me"
  }

  event: thinking_delta
  data: {
    "runId": "run-20260520-001",
    "text": " check the project structure to understand where the"
  }

  event: thinking_delta
  data: {
    "runId": "run-20260520-001",
    "text": " user management code is located."
  }

  event: thinking_end
  data: {
    "runId": "run-20260520-001",
    "totalThinkingTokens": 45,
    "durationMs": 1200
  }

  event: tool_call
  data: {
    "runId": "run-20260520-001",
    "toolCallId": "toolu_bdrk_01ABC123",
    "name": "file_search",
    "args": {
      "pattern": "**/User*.java",
      "path": "src/main/java"
    },
    "round": 1,
    "timestamp": "2026-05-20T14:30:02.250Z"
  }

  event: tool_result
  data: {
    "runId": "run-20260520-001",
    "toolCallId": "toolu_bdrk_01ABC123",
    "name": "file_search",
    "success": true,
    "result": {
      "files": [
        "src/main/java/com/example/user/User.java",
        "src/main/java/com/example/user/UserService.java",
        "src/main/java/com/example/user/UserController.java",
        "src/main/java/com/example/user/UserRepository.java"
      ],
      "count": 4
    },
    "durationMs": 85,
    "timestamp": "2026-05-20T14:30:02.335Z"
  }

  ┌────────────────────────────────────────────────────────────────────────┐
  │ PHASE 3: SECOND TOOL CALL WITH APPROVAL (shell command)                 │
  ├────────────────────────────────────────────────────────────────────────┤

  event: tool_call
  data: {
    "runId": "run-20260520-001",
    "toolCallId": "toolu_bdrk_02DEF456",
    "name": "shell",
    "args": {
      "command": "git log --oneline -10 src/main/java/com/example/user/",
      "workingDir": "/home/user/project"
    },
    "round": 2,
    "timestamp": "2026-05-20T14:30:05.100Z"
  }

  event: tool_approval_request
  data: {
    "runId": "run-20260520-001",
    "toolCallId": "toolu_bdrk_02DEF456",
    "name": "shell",
    "args": {
      "command": "git log --oneline -10 src/main/java/com/example/user/",
      "workingDir": "/home/user/project"
    },
    "risk": "low",
    "reason": "Read-only git command",
    "timeoutSeconds": 120,
    "timestamp": "2026-05-20T14:30:05.101Z"
  }

  ── Frontend shows approval dialog ──
  ── User clicks "Approve" ──

  event: tool_approval_response
  data: {
    "runId": "run-20260520-001",
    "toolCallId": "toolu_bdrk_02DEF456",
    "approved": true,
    "approvedBy": "user@example.com",
    "timestamp": "2026-05-20T14:30:08.500Z"
  }

  event: tool_result
  data: {
    "runId": "run-20260520-001",
    "toolCallId": "toolu_bdrk_02DEF456",
    "name": "shell",
    "success": true,
    "result": {
      "exitCode": 0,
      "stdout": "abc1234 Fix user validation bug\ndef5678 Add user export feature\n...",
      "stderr": ""
    },
    "durationMs": 320,
    "sandboxed": true,
    "timestamp": "2026-05-20T14:30:08.820Z"
  }

  ┌────────────────────────────────────────────────────────────────────────┐
  │ PHASE 4: SUBAGENT SPAWN (code-reviewer)                                 │
  ├────────────────────────────────────────────────────────────────────────┤

  event: tool_call
  data: {
    "runId": "run-20260520-001",
    "toolCallId": "toolu_bdrk_03GHI789",
    "name": "delegate_to_agent",
    "args": {
      "agentId": "code-reviewer",
      "task": "Review UserService.java for bugs, null safety, and concurrency issues",
      "files": ["src/main/java/com/example/user/UserService.java"],
      "context": "Recent commits show validation changes"
    },
    "round": 3,
    "timestamp": "2026-05-20T14:30:12.000Z"
  }

  event: subagent_spawning
  data: {
    "parentRunId": "run-20260520-001",
    "parentAgentId": "chat",
    "childAgentId": "code-reviewer",
    "childRunId": "run-20260520-002",
    "depth": 1,
    "maxDepth": 2,
    "depthRemaining": 1,
    "task": "Review UserService.java for bugs, null safety, and concurrency issues",
    "sessionKey": "main-abc123/subagent/code-reviewer/uuid-x1y2z3w4",
    "timestamp": "2026-05-20T14:30:12.050Z"
  }

  event: subagent_spawned
  data: {
    "parentRunId": "run-20260520-001",
    "childAgentId": "code-reviewer",
    "childRunId": "run-20260520-002",
    "sessionKey": "main-abc123/subagent/code-reviewer/uuid-x1y2z3w4",
    "status": "running",
    "timestamp": "2026-05-20T14:30:12.100Z"
  }

  ── Subagent runs internally (its own events are emitted on a separate   ──
  ── SSE channel, or nested within the parent stream if configured)        ──

  event: subagent_ended
  data: {
    "parentRunId": "run-20260520-001",
    "childAgentId": "code-reviewer",
    "childRunId": "run-20260520-002",
    "outcome": "ok",
    "result": {
      "bugsFound": 3,
      "severity": {
        "critical": 0,
        "high": 1,
        "medium": 2
      },
      "summary": "Found null safety issue in validate() method and race condition in updateUser()"
    },
    "elapsedMs": 45000,
    "tokensUsed": {
      "input": 12000,
      "output": 800,
      "total": 12800
    },
    "timestamp": "2026-05-20T14:30:57.150Z"
  }

  event: tool_result
  data: {
    "runId": "run-20260520-001",
    "toolCallId": "toolu_bdrk_03GHI789",
    "name": "delegate_to_agent",
    "success": true,
    "result": {
      "bugsFound": 3,
      "severity": {"critical": 0, "high": 1, "medium": 2},
      "summary": "Found null safety issue in validate() method and race condition in updateUser()"
    },
    "durationMs": 45150,
    "timestamp": "2026-05-20T14:30:57.200Z"
  }

  ┌────────────────────────────────────────────────────────────────────────┐
  │ PHASE 5: FINAL THINKING + TEXT RESPONSE                                 │
  ├────────────────────────────────────────────────────────────────────────┤

  event: thinking_start
  data: {
    "runId": "run-20260520-001",
    "timestamp": "2026-05-20T14:30:58.000Z"
  }

  event: thinking_delta
  data: {
    "runId": "run-20260520-001",
    "text": "The code review found 3 bugs. Let me summarise"
  }

  event: thinking_delta
  data: {
    "runId": "run-20260520-001",
    "text": " the findings and provide actionable recommendations."
  }

  event: thinking_end
  data: {
    "runId": "run-20260520-001",
    "totalThinkingTokens": 28,
    "durationMs": 800
  }

  event: model_call_ended
  data: {
    "runId": "run-20260520-001",
    "provider": "anthropic",
    "model": "claude-opus-4.5",
    "totalRounds": 3,
    "totalToolCalls": 3,
    "usage": {
      "inputTokens": 18500,
      "outputTokens": 2400,
      "totalTokens": 20900,
      "cacheReadTokens": 4200,
      "cacheWriteTokens": 8500
    },
    "durationMs": 55000,
    "timestamp": "2026-05-20T14:30:58.800Z"
  }

  event: before_finalize
  data: {
    "runId": "run-20260520-001",
    "action": "finalize",
    "timestamp": "2026-05-20T14:31:00.000Z"
  }

  event: message_start
  data: {
    "runId": "run-20260520-001",
    "timestamp": "2026-05-20T14:31:00.050Z"
  }

  event: message_delta
  data: {
    "runId": "run-20260520-001",
    "text": "Based on my thorough analysis of the codebase, "
  }

  event: message_delta
  data: {
    "runId": "run-20260520-001",
    "text": "the code review of `UserService.java` found **3 bugs**:\n\n"
  }

  event: message_delta
  data: {
    "runId": "run-20260520-001",
    "text": "### 1. Null Safety Issue (HIGH)\n"
  }

  event: message_delta
  data: {
    "runId": "run-20260520-001",
    "text": "The `validate()` method does not check for null before "
  }

  event: message_delta
  data: {
    "runId": "run-20260520-001",
    "text": "calling `user.getEmail()`. This can cause a `NullPointerException` "
  }

  event: message_delta
  data: {
    "runId": "run-20260520-001",
    "text": "when the user object is not fully initialised.\n\n"
  }

  event: message_delta
  data: {
    "runId": "run-20260520-001",
    "text": "**Recommendation:** Add a `Objects.requireNonNull(user, \"user must not be null\")` "
  }

  event: message_delta
  data: {
    "runId": "run-20260520-001",
    "text": "guard at the top of the method.\n\n"
  }

  event: message_delta
  data: {
    "runId": "run-20260520-001",
    "text": "### 2. Race Condition (MEDIUM)\n"
  }

  event: message_delta
  data: {
    "runId": "run-20260520-001",
    "text": "The `updateUser()` method reads-modifies-writes without "
  }

  event: message_delta
  data: {
    "runId": "run-20260520-001",
    "text": "synchronisation, which can lead to lost updates under concurrent access.\n\n"
  }

  event: message_delta
  data: {
    "runId": "run-20260520-001",
    "text": "**Recommendation:** Use `synchronized` block or `ReentrantReadWriteLock` "
  }

  event: message_delta
  data: {
    "runId": "run-20260520-001",
    "text": "to protect the critical section.\n\n"
  }

  event: message_delta
  data: {
    "runId": "run-20260520-001",
    "text": "### 3. Resource Leak (MEDIUM)\n"
  }

  event: message_delta
  data: {
    "runId": "run-20260520-001",
    "text": "The file export stream in `exportUserData()` is not closed in a "
  }

  event: message_delta
  data: {
    "runId": "run-20260520-001",
    "text": "finally block, potentially leaking file handles.\n\n"
  }

  event: message_end
  data: {
    "runId": "run-20260520-001",
    "totalMessageChars": 856,
    "timestamp": "2026-05-20T14:31:02.500Z"
  }

  ┌────────────────────────────────────────────────────────────────────────┐
  │ PHASE 6: COMPACTION (if triggered)                                      │
  ├────────────────────────────────────────────────────────────────────────┤

  event: compaction_start
  data: {
    "runId": "run-20260520-001",
    "reason": "transcript_size_exceeded",
    "currentSizeBytes": 11534336,
    "limitBytes": 10485760,
    "usagePercent": 110.0,
    "timestamp": "2026-05-20T14:31:03.000Z"
  }

  event: compaction_progress
  data: {
    "runId": "run-20260520-001",
    "phase": "summarising",
    "turnsProcessed": 8,
    "totalTurns": 15,
    "timestamp": "2026-05-20T14:31:03.500Z"
  }

  event: compaction_complete
  data: {
    "runId": "run-20260520-001",
    "removedChars": 52000,
    "keptChars": 180000,
    "keptTurns": 15,
    "summarisedTurns": 8,
    "compactionRatio": "0.78",
    "durationMs": 450,
    "timestamp": "2026-05-20T14:31:03.950Z"
  }

  ┌────────────────────────────────────────────────────────────────────────┐
  │ PHASE 7: RUN COMPLETION                                                 │
  ├────────────────────────────────────────────────────────────────────────┤

  event: agent_end
  data: {
    "runId": "run-20260520-001",
    "agentId": "chat",
    "sessionKey": "main-abc123",
    "elapsedMs": 65000,
    "totalRounds": 3,
    "totalToolCalls": 3,
    "totalSubagentsSpawned": 1,
    "usage": {
      "inputTokens": 18500,
      "outputTokens": 2400,
      "totalTokens": 20900
    },
    "compacted": true,
    "outcome": "completed",
    "timestamp": "2026-05-20T14:31:05.000Z"
  }

  event: done
  data: {
    "runId": "run-20260520-001",
    "sessionKey": "main-abc123",
    "timestamp": "2026-05-20T14:31:05.050Z"
  }

  ╔══════════════════════════════════════════════════════════════════════════╗
  ║                       SSE EVENT TYPE CATALOG                             ║
  ╚══════════════════════════════════════════════════════════════════════════╝

  ┌──────────────────────────┬──────────────────────────────────────────────┐
  │ EVENT NAME               │ DESCRIPTION                                   │
  ├──────────────────────────┼──────────────────────────────────────────────┤
  │ run_start                │ New agent run initiated                       │
  │ bootstrap_loaded         │ Bootstrap files loaded from disk              │
  │ context_built            │ Context assembled (messages, tools, prompt)    │
  │ model_resolved           │ LLM model selected after resolution chain     │
  │ model_call_started       │ LLM API call started                          │
  │ thinking_start           │ LLM thinking/CoT block started                │
  │ thinking_delta           │ Incremental thinking text                     │
  │ thinking_end             │ LLM thinking/CoT block ended                  │
  │ tool_call                │ Tool invocation requested by LLM              │
  │ tool_approval_request    │ Tool requires user approval (sent to UI)      │
  │ tool_approval_response   │ User's approval decision received             │
  │ tool_result              │ Tool execution result                         │
  │ subagent_spawning        │ Subagent about to be spawned                  │
  │ subagent_spawned         │ Subagent successfully created and running     │
  │ subagent_ended           │ Subagent completed (ok/error/timeout)         │
  │ model_call_ended         │ LLM API call completed with usage stats       │
  │ before_finalize          │ Finalization gate (finalize/revise/continue)  │
  │ message_start            │ Text response streaming started               │
  │ message_delta            │ Incremental text response                     │
  │ message_end              │ Text response streaming ended                 │
  │ compaction_start         │ Context compaction triggered                  │
  │ compaction_progress      │ Compaction progress update                    │
  │ compaction_complete      │ Context compaction finished                   │
  │ agent_end                │ Agent run completed with summary              │
  │ done                     │ SSE stream ended (connection stays open)      │
  │ error                    │ Error occurred (recoverable or fatal)         │
  │ heartbeat                │ Heartbeat keepalive (every 30s idle)          │
  └──────────────────────────┴──────────────────────────────────────────────┘
```

---

## 6. Component Inventory & Responsibilities

| # | Component | Layer | Responsibility |
|---|-----------|-------|----------------|
| 1 | **REST/SSE Controller** | Transport | Accept HTTP POST chat requests, return SSE streams. Handles CORS, auth, rate limiting. |
| 2 | **WebSocket Handler** | Transport | Maintain persistent bidirectional connections. Support session resumption. |
| 3 | **WebChat UI** | Transport | React-based chat interface served as static assets. Connects via SSE/WS. |
| 4 | **Channel Plugins** | Transport | Adapt external messaging platforms (Telegram, Discord, Slack, WeChat) to internal AgentMessage format. |
| 5 | **Agent Router** | Routing | Match inbound messages to agent instances via channel name, route bindings, @mentions, or ACP prefixes. |
| 6 | **Agent Config Resolver** | Configuration | Deep-merge system defaults, agent defaults, `@Agent` annotations, and runtime overrides into `ResolvedAgentConfig`. |
| 7 | **AgentInvocationHandler** | Runtime | JDK dynamic proxy that intercepts `ChatAgent` interface calls and dispatches to the correct runtime (Embedded or ACP). |
| 8 | **BootstrapLoader** | Runtime (Embedded) | Load, validate, and cache bootstrap Markdown files (AGENTS.md, SOUL.md, BOOTSTRAP.md, IDENTITY.md, USER.md, HEARTBEAT.md). |
| 9 | **Context Engine** | Runtime (Embedded) | Assemble full LLM context from session history, bootstrap content, tool definitions, and system prompt. Compact and prune as needed. |
| 10 | **ReAct Engine** | Runtime (Embedded) | Execute the Reasoning-Action loop: call LLM, detect tool calls, execute tools, feed results back, loop until text reply or budget exhausted. |
| 11 | **Block Streaming** | Runtime (Embedded) | Coalesce text deltas, apply human-like delays, send typing indicators, and stream to SSE/WebSocket. |
| 12 | **AcpRuntime** | Runtime (ACP) | Manage external ACP provider sessions. Forward messages, translate ACP events to internal SSE events. |
| 13 | **Heartbeat Scheduler** | Runtime | Cron-driven background agent activation. Checks active hours, idle status, and cooldown before triggering a single-turn ReAct. |
| 14 | **Subagent Spawner** | Runtime | Create child `ReActEngine` instances with isolated sessions. Enforce depth, concurrency, and child count limits. |
| 15 | **Sandbox** | Runtime | Execute tool calls inside Docker/Podman containers with filesystem bridges and resource limits. |
| 16 | **Model Catalog** | Shared Service | Registry of available LLM models with capabilities, pricing, and health status. |
| 17 | **Model Resolver** | Shared Service | Resolve the best model for a request using primary, fallback chain, and auto-probe health checks. |
| 18 | **Tool Registry** | Shared Service | Register and discover `@Tool`-annotated methods from core and plugins. |
| 19 | **Tool Pipeline** | Shared Service | Execute tool calls through validation, approval, sandboxing, and result enrichment middleware. |
| 20 | **Memory System** | Shared Service | Three-tier memory: Redis (hot), PostgreSQL (warm), disk (cold). Stores session history and agent knowledge. |
| 21 | **Session Store** | Shared Service | Persist conversation transcripts in append-only JSONL format. Support hierarchical subagent session keys. |
| 22 | **Skill Registry** | Shared Service | Register agent capability bundles as DAG graphs. Support hot-reload and conflict detection. |
| 23 | **Security Manager** | Shared Service | Enforce tool allowlists/blocklists, rate limiting, and content safety policies. |
| 24 | **Approval Store** | Shared Service | Manage pending tool approval requests with timeout, auto-deny, and UI integration. |
| 25 | **Identity Resolver** | Shared Service | Resolve user identity from OAuth2/OIDC/JWT tokens. Map to trust levels and permission profiles. |
| 26 | **TTS Engine** | Shared Service | Text-to-speech synthesis via ElevenLabs, Edge TTS, or Azure Speech for voice channel output. |
| 27 | **Metrics Collector** | Shared Service | Export Micrometer metrics to Prometheus. Dashboards via Grafana. |
| 28 | **Plugin SDK** | Extensibility | Define and enforce the plugin contract (manifest, classloader isolation, lifecycle, hot-reload). |
| 29 | **Hook Pipeline** | Cross-cutting | 36-point lifecycle interception. Plugins register hook handlers with priority ordering. |

---

## 7. Key Design Decisions

| Decision | Rationale |
|----------|-----------|
| **Embedded + ACP dual runtime** | Existing ACP provider integration must be preserved. New agents use the embedded runtime. Both share the same transport, routing, and hook pipeline. |
| **36-point hook pipeline** | Superset of current hooks plus those needed for subagents, compaction, heartbeat, and streaming. Each hook has a defined interface, priority, and async/sync contract. |
| **Deep-merge config resolution** | Four-layer merge (system → agent defaults → annotation → runtime) with `final: true` locking for security-sensitive fields. Immutable `ResolvedAgentConfig` snapshots prevent runtime mutation. |
| **Recursive subagent model** | Agents can delegate to other agents via `delegate_to_agent` tool. Each subagent runs a complete independent ReAct loop with its own session, config, and bootstrap. Depth/concurrency/child limits prevent runaway spawning. |
| **Hierarchical session keys** | Subagent session keys are derived from the parent (e.g., `main-abc123/subagent/code-reviewer/uuid-1`), enabling traceability, independent compaction, and cleanup. |
| **SSE as primary streaming protocol** | Chosen over WebSocket alone because SSE is simpler (HTTP-native, auto-reconnect, unidirectional server→client), and bidirectional needs are already handled by the REST request path. WebSocket is offered as an alternative for channels that need it. |
| **Block streaming with human delay** | Text responses are coalesced into natural-feeling blocks with configurable delay, preventing the "wall of text" effect in chat UIs. |
| **Container-based sandbox** | Tool execution isolation via Docker/Podman containers with filesystem bridges, network restrictions, and resource limits. Per-agent or shared container pools. |
| **Plugin SDK with ClassLoader isolation** | Third-party plugins run in their own ClassLoader, preventing dependency conflicts. Manifest declares provided extensions (hooks, tools, skills, channels, providers). |
| **Heartbeat as cron-driven background agent** | Heartbeat is not a separate system but a cron-triggered agent run. It uses the same ReAct pipeline but with an isolated session and light context (HEARTBEAT.md only). |

---

## 8. Migration Path from Current Architecture

```
Current State                          Intermediate State                    Target State
─────────────                          ──────────────────                    ────────────

┌──────────────────┐                   ┌──────────────────┐                 ┌──────────────────┐
│ Mono Spring Boot │                   │ Modularised      │                 │ Full Platform    │
│ App              │                   │ Spring Boot App  │                 │ Architecture     │
│                  │                   │                  │                 │                  │
│ ChatAgent.java   │  ──Phase 1──►     │ Agent Runtime    │  ──Phase 3──►   │ Agent Runtime    │
│ (interface)      │   Extract         │ (Embedded + ACP) │   Plugin SDK    │ + Plugin SDK     │
│                  │   runtime from    │                  │                 │ + Full hooks     │
│ LLM call inline  │   ChatAgent       │ Hook Pipeline    │                 │ + Subagents      │
│                  │                   │ (18 hooks)       │                 │ + Heartbeat      │
│ No hooks         │  ──Phase 2──►     │                  │                 │ + Sandbox        │
│ No subagents     │   Add hooks,      │ Config Resolver  │                 │ + Compaction     │
│ No heartbeat     │   config merge,   │ (3-layer)        │                 │                  │
│ No sandbox       │   compaction      │                  │                 │                  │
└──────────────────┘                   └──────────────────┘                 └──────────────────┘

Phase 1 (MVP):    Extract ReActEngine, BootstrapLoader, ContextEngine from ChatAgent.
                  Keep ACP path intact. Introduce AgentInvocationHandler proxy.

Phase 2 (Core):   Add first 18 hooks. Implement deep-merge Config Resolver. Add
                  compaction and pruning. Introduce SSE block streaming.

Phase 3 (Full):   Complete all 36 hooks. Add Subagent Spawner, Heartbeat Scheduler,
                  Sandbox, Plugin SDK. Achieve full architecture blueprint.
```

---

> **Document Maintainer:** Architecture Team  
> **Review Cadence:** Updated on each major design decision or architectural change.  
> **Related Documents:**
> - `07-hook-lifecycle-full.md` — Complete 36-hook specification
> - `08-subagent-delegation-design.md` — Subagent spawning and management
> - `10-sse-streaming-protocol.md` — SSE event format specification
> - `11-plugin-sdk-contract.md` — Plugin SDK interface definitions

---

# LyClaw Agent Renovation — Implementation Roadmap

> **Status:** Planning  
> **Target:** Bring LyClaw agent architecture to parity with OpenClaw's agent config, hooks, subagent delegation, model catalog, context management, streaming, sandbox, and heartbeat capabilities.  
> **Principle:** Every change is additive and backward-compatible — existing tests, annotations, hooks, and pipeline stages continue to work without modification.

---

## 1. Overall Timeline & Priority Matrix

| Phase | Name | Priority | Est. Effort | Dependencies | Risk |
|-------|------|----------|-------------|--------------|------|
| Phase 1 | Agent Core Enhancement | P0 | 3-4 weeks | None | Low |
| Phase 2 | Subagent + Models | P1 | 4-6 weeks | Phase 1 | Medium |
| Phase 3 | Context + Bootstrap + Routing | P2 | 3-4 weeks | Phase 1 | Medium |
| Phase 4 | Streaming + Sandbox + Heartbeat | P2 | 3-4 weeks | Phase 1, 2 | High |

**Total estimated effort: 13-18 weeks** (assuming one full-time developer; can be parallelized across Phases 2-4 after Phase 1 completes).

### Risk Definitions

- **Low:** Changes are purely additive with default values; existing code paths untouched.
- **Medium:** New components interact with existing subsystems (ChatFacade, pipeline stages); requires careful integration testing.
- **High:** External dependency (Docker daemon, SSE timing, scheduled tasks); environment-sensitive tests.

---

## 2. Phase 1 Detailed Task List — Agent Core Enhancement

**Goal:** Expand the foundation — annotation, config resolution, hooks, context, and proxy factory — so all subsequent phases have a rich configuration surface to build on.

### Current Baseline (before Phase 1)

| Component | Current State |
|-----------|---------------|
| `@Agent` annotation | 6 fields: `name`, `description`, `version`, `model`, `provider`, `extensions` |
| `AgentHook` interface | 6 methods: `beforeRequest`, `beforeModel`, `afterModel`, `wrapToolCall`, `wrapToolExecutor`, `afterResult` + `getOrder` |
| `AgentContext` | ~20 fields: sessionId, userMessage, systemPrompt, chatRequest, toolRegistry, method, args, sandboxLevel, lifecycle, tracing, toolResults, successCount, failCount, nodes, reflectScoreRef, pipelineOk, respondStartMs, terminated, currentStage, attributes + snapshot/restore |
| `AgentConfig` | 5 core fields + `Map<String, String> extensions` |
| `AgentConfigResolver` | Multi-source priority merging via `AgentConfigSource` SPI |
| `AgentProxyFactory` | JDK dynamic proxy; constructor injection of ChatFacade, ReActEngine, ToolRegistry, hooks, stages |
| `AgentInvocationHandler` | Hooks dispatch + pipeline stage orchestration + `MAX_REFLECTION_RETRIES = 2` |
| Hook implementations | 5: SecurityCheckHook (order=10), SandboxHook (order=20), ApprovalHook (order=30), PlanningHook (order=40), OutputGuardHook (order=90) |
| Pipeline stages | 6: ContextBuild (0), SecurityCheck (1), PlanExecution (2), Respond (3), Reflection (4), Metrics (5) |

---

### Week 1-2: Configuration Foundation

#### Task 1.1 — Create `AgentDefaultsConfig` class
**Package:** `lyjew.com.lyclaw.config`  
**File:** `AgentDefaultsConfig.java`

A `@ConfigurationProperties(prefix = "lyclaw.agent.defaults")` class holding all system-wide defaults. Mirrors OpenClaw's `AgentConfig` fields.

**Fields to include (30+):**
```
id, default (boolean), workspace, agentDir, systemPrompt, systemPromptOverride,
model, provider, fallbacks (List<String>), skills (List<String>),
thinkingDefault, thinkingLevel, verboseDefault, verboseLevel,
reasoningDefault, reasoningLevel, fastModeDefault, fastMode,
contextTokens, maxContextTokens, bootstrapMaxChars,
bootstrapTotalMaxChars, contextInjection (enum),
delegationMode (enum), allowAgents (List<String>),
maxSpawnDepth, maxChildrenPerAgent, sandbox (enum),
streamingEnabled, blockStreamingMaxChars, blockStreamingMaxIdleMs,
humanDelayMinMs, humanDelayMaxMs, typingMode (enum),
heartbeatEnabled, heartbeatCron, heartbeatActiveHoursStart,
heartbeatActiveHoursEnd, heartbeatLightContext,
heartbeatIsolatedSession, heartbeatSkipWhenBusy,
maxReflectionRetries, reflectionRetryThreshold
```

**Validation:** `@Validated` with JSR-303 constraints where appropriate (e.g., `@Min(0)` for tokens, `@Min(1)` for retries).

**Corresponding YAML:** Add `lyclaw.agent.defaults` section to `application.yml`.

#### Task 1.2 — Extend `@Agent` annotation
**File:** `lyjew.com.lyclaw.annotation.Agent` (modify existing)

Add 20+ new optional fields — all defaulted so existing `@Agent` usages compile unchanged.

**New fields:**
```java
String id() default "";                    // Stable identifier (defaults to name)
boolean isDefault() default false;         // Is this the default agent?
String workspace() default "";             // Workspace directory path
String agentDir() default "";              // Agent-specific directory
String systemPromptOverride() default "";  // Override system prompt
String[] fallbacks() default {};           // Fallback model names
String[] skills() default {};              // Skill identifiers
String thinkingDefault() default "";       // Default thinking level
String thinkingLevel() default "";         // Thinking level override
String verboseDefault() default "";        // Default verbose level
String verboseLevel() default "";          // Verbose level override
String reasoningDefault() default "";      // Default reasoning level
String reasoningLevel() default "";        // Reasoning level override
boolean fastModeDefault() default false;   // Fast mode default
boolean fastMode() default false;          // Fast mode override
int contextTokens() default 0;             // Context token budget (0 = use default)
int bootstrapMaxChars() default 0;         // Bootstrap max chars (0 = use default)
int bootstrapTotalMaxChars() default 0;    // Total bootstrap max chars
String contextInjection() default "";      // Injection policy
String delegationMode() default "";        // Delegation mode
String[] allowAgents() default {};         // Whitelist for subagent spawning
int maxSpawnDepth() default 0;             // Max subagent recursion depth
int maxChildrenPerAgent() default 0;       // Max concurrent children
String sandbox() default "";               // Sandbox level override
```

#### Task 1.3 — Create `ResolvedAgentConfig`
**Package:** `lyjew.com.lyclaw.config`  
**File:** `ResolvedAgentConfig.java`

An immutable record (or builder-pattern final class) representing the fully merged configuration for a single agent invocation. This is the output of the resolution process — combining `AgentDefaultsConfig` + `@Agent` annotation + runtime overrides.

**Design decision:** Use a record-style approach with a Builder to avoid the mutable `AgentConfig` pattern for resolved configs. The existing `AgentConfig` remains for source-level representation; `ResolvedAgentConfig` is the canonical runtime form.

**Fields:** mirrors all 30+ fields from `AgentDefaultsConfig` with concrete (non-null, non-zero-default) values.

#### Task 1.4 — Enhance `AgentConfigResolver` with deep-merge logic
**File:** `lyjew.com.lyclaw.config.AgentConfigResolver` (modify existing)

Add a new method:
```java
public ResolvedAgentConfig resolveFull(String agentName, AgentDefaultsConfig defaults,
                                        Map<String, String> runtimeOverrides)
```

**Deep-merge rules:**
1. Start with `AgentDefaultsConfig` values (lowest priority).
2. Overlay `@Agent` annotation values where non-empty/non-zero.
3. Overlay `AgentConfigSource` chain values (existing multi-source merging).
4. Overlay runtime overrides (highest priority).
5. For list fields (`fallbacks`, `skills`, `allowAgents`): concatenate rather than replace.
6. For boolean fields: explicit annotation `false` overrides default `true`, but annotation default `false` does not override default `true` (use `@Nullable Boolean` wrapper semantics).

#### Task 1.5 — Add agent config to `application.yml`
**File:** `lyclaw-framework/src/main/resources/application.yml` (create if absent)

```yaml
lyclaw:
  agent:
    defaults:
      model: "deepseek-v4-flash"
      provider: "deepseek"
      maxReflectionRetries: 2
      reflectionRetryThreshold: 0.6
      contextTokens: 128000
      maxContextTokens: 200000
      bootstrapMaxChars: 50000
      bootstrapTotalMaxChars: 200000
      contextInjection: "always"
      delegationMode: "local"
      maxSpawnDepth: 3
      maxChildrenPerAgent: 5
      sandbox: "direct"
      streamingEnabled: true
      blockStreamingMaxChars: 80
      blockStreamingMaxIdleMs: 150
      humanDelayMinMs: 100
      humanDelayMaxMs: 400
      typingMode: "message"
      heartbeatEnabled: false
      heartbeatCron: "0 */30 * * * *"
      heartbeatActiveHoursStart: "09:00"
      heartbeatActiveHoursEnd: "18:00"
      heartbeatLightContext: false
      heartbeatIsolatedSession: true
      heartbeatSkipWhenBusy: true
```

#### Task 1.6 — Create `ConfigResolutionTest`
**File:** `lyclaw-framework/src/test/java/lyjew/com/lyclaw/config/ConfigResolutionTest.java`

Test cases:
- Defaults-only resolution produces valid `ResolvedAgentConfig`.
- Annotation overrides defaults correctly.
- Runtime overrides take highest priority.
- List fields concatenate across sources.
- Boolean fields use nullable semantics.
- Missing optional fields fall back to defaults without error.
- Invalid configurations (e.g., negative token count) throw `ConfigurationValidationException`.

---

### Week 2-3: Context & Hooks Expansion

#### Task 2.1 — Extend `AgentContext` with 15+ new fields
**File:** `lyjew.com.lyclaw.react.AgentContext` (modify existing)

**New fields:**
```java
// Agent identity
private String agentId;
private String agentName;

// Directories
private String workspaceDir;
private String agentDir;

// Resolved configuration
private ResolvedAgentConfig resolvedConfig;

// Bootstrap
private String bootstrapContent;

// Context limits  
private AgentContextLimits contextLimits;

// LLM behavior levels
private String thinkingLevel;
private String verboseLevel;
private String reasoningLevel;

// Subagent delegation
private String delegationMode;
private List<String> allowAgents;
private int maxSpawnDepth;
private int maxChildrenPerAgent;
private List<String> activeSubagentIds;

// Runtime metadata
private AgentRuntimeType runtimeType;
private Map<String, Object> runMetadata;
```

**Snapshot/restore:** Update `toSnapshot()` and `restoreFromSnapshot()` to include all new serializable fields. Runtime references (resolvedConfig, contextLimits) should be included via their own serialization methods.

**Backward compatibility:** All existing constructor signatures preserved. Add a Builder pattern for the expanded form.

#### Task 2.2 — Expand `AgentHook` from 6 to 36 methods
**File:** `lyjew.com.lyclaw.react.AgentHook` (modify existing)

All new methods are `default` no-ops, so the 5 existing hook implementations compile without changes.

**New hook lifecycle points (grouped by phase):**

**Pre-Request (before pipeline):**
```
7.  onAgentResolve(AgentContext)          — after config resolution, before pipeline
8.  onBootstrapLoad(AgentContext, String) — after AGENTS.md/SOUL.md loaded
9.  onContextInjection(AgentContext)      — after bootstrap injected into message list
10. onSessionCreate(AgentContext)         — when a new session is created
```

**Pipeline stage hooks (per-stage):**
```
11. onStageStart(AgentContext, String stageName)      — before any stage
12. onStageComplete(AgentContext, String stageName)    — after any stage
13. onStageError(AgentContext, String stageName, Throwable)
14. onContextBuild(AgentContext)                       — specific to ContextBuild
15. onSecurityCheck(AgentContext)                      — specific to SecurityCheck
16. onPlanExecution(AgentContext)                      — specific to PlanExecution
17. onRespondStart(AgentContext)                       — before Respond
18. onRespondComplete(AgentContext)                    — after Respond
19. onReflection(AgentContext)                         — specific to Reflection
20. onCompaction(AgentContext)                         — when compaction runs
```

**ReAct loop hooks (per-iteration):**
```
21. onReActIterationStart(AgentContext, int iteration)
22. onReActIterationEnd(AgentContext, int iteration)
23. onToolCallStart(AgentContext, ToolCall)
24. onToolCallComplete(AgentContext, ToolCall, String result)
25. onToolCallError(AgentContext, ToolCall, Throwable)
```

**Subagent hooks:**
```
26. onSubagentSpawn(AgentContext, String childAgentId)
27. onSubagentComplete(AgentContext, String childAgentId, String result)
28. onSubagentError(AgentContext, String childAgentId, Throwable)
```

**Streaming hooks:**
```
29. onBlockStream(AgentContext, String block)     — each coalesced text block
30. onTypingIndicator(AgentContext)               — typing indicator emitted
```

**Post-Request:**
```
31. onAgentFinalize(AgentContext, AgentFinalizeResult)
32. onHeartbeat(AgentContext, HeartbeatConfig)
33. onSessionArchive(AgentContext)
```

**Error & Lifecycle:**
```
34. onMaxRetriesExceeded(AgentContext)
35. onContextOverflow(AgentContext, int currentTokens, int maxTokens)
36. onAgentTerminate(AgentContext, String reason)
```

#### Task 2.3 — Create `AgentFinalizeResult`, `HookDecision`, `HookRegistration`
**Package:** `lyjew.com.lyclaw.react`

**`AgentFinalizeResult`:**
```java
public record AgentFinalizeResult(
    String finalResponse,
    int totalTokens,
    int totalToolCalls,
    int successfulToolCalls,
    int failedToolCalls,
    int reActIterations,
    long durationMs,
    boolean terminatedEarly,
    String terminationReason,
    Map<String, Object> metadata
) {}
```

**`HookDecision`** — allows hooks to signal special actions:
```java
public enum HookDecision {
    CONTINUE,       // normal flow
    SKIP_STAGE,     // skip the current stage
    RETRY,          // retry the current stage
    TERMINATE,      // terminate the pipeline
    DELEGATE        // delegate to subagent
}
```

**`HookRegistration`** — allows hooks to be registered/unregistered by name:
```java
public record HookRegistration(String hookName, AgentHook hook, int priority) {}
```

#### Task 2.4 — Create `HookRegistry`
**Package:** `lyjew.com.lyclaw.react`  
**File:** `HookRegistry.java`

Central registry that manages all hook instances by name, providing:
- `register(HookRegistration)` / `unregister(String hookName)`
- `dispatchBeforeRequest(AgentContext)` — calls all `beforeRequest` hooks in order
- `dispatchOnStageStart(AgentContext, String)` — calls all `onStageStart` hooks
- ... (one dispatch method per hook lifecycle point)
- `getHooksForLifecyclePoint(String)` — returns ordered list for a specific point
- Support for conditional hooks (`Predicate<AgentContext>` guard)

**Design:** Uses `CopyOnWriteArrayList` per lifecycle point for thread-safe registration. Sorting by priority on each dispatch (cached until registration changes).

#### Task 2.5 — Update `AgentInvocationHandler` for all 36 hooks
**File:** `lyjew.com.lyclaw.react.AgentInvocationHandler` (modify existing)

Refactor from inline hook dispatch to `HookRegistry`-based dispatch. Add hook calls at:

- **After config resolution** (new): `onAgentResolve`
- **After bootstrap load** (new): `onBootstrapLoad`
- **Before each stage** (new): `onStageStart` / `onStageComplete` / `onStageError`
- **Before/after ReAct iteration** (new): `onReActIterationStart` / `onReActIterationEnd`
- **Before/after tool calls** (new): `onToolCallStart` / `onToolCallComplete` / `onToolCallError`
- **Existing points**: `beforeRequest`, `beforeModel`, `afterModel`, `wrapToolCall`, `wrapToolExecutor`, `afterResult` (preserved)

Each hook call checks the return type: if a hook returns `HookDecision.TERMINATE`, the pipeline stops gracefully. If `HookDecision.SKIP_STAGE`, the current stage is skipped. If `HookDecision.RETRY`, the stage is re-executed (up to a configurable limit).

#### Task 2.6 — Create `HookSystemTest`
**File:** `lyclaw-framework/src/test/java/lyjew/com/lyclaw/react/HookSystemTest.java`

Test cases:
- All 36 hooks are called in correct order for a full pipeline run.
- `HookDecision.TERMINATE` stops pipeline and produces `AgentFinalizeResult` with `terminatedEarly=true`.
- `HookDecision.SKIP_STAGE` skips the current stage.
- `HookDecision.RETRY` retries the stage up to limit.
- Hook priority ordering is respected.
- Runtime hook registration/unregistration works.
- Existing 5 hooks still function correctly (backward compat).
- Hook errors in one hook do not prevent other hooks from executing.

---

### Week 3-4: Runtime Types & Integration

#### Task 3.1 — Create `AgentRuntimeType` enum
**Package:** `lyjew.com.lyclaw.react`  
**File:** `AgentRuntimeType.java`

```java
public enum AgentRuntimeType {
    EMBEDDED,   // Agent runs in-process (current behavior)
    ACP         // Agent runs via Agent Communication Protocol (remote)
}
```

#### Task 3.2 — Create ACP runtime interfaces
**Package:** `lyjew.com.lyclaw.react.acp`

**`AcpRuntime`** — interface for remote agent execution:
```java
public interface AcpRuntime {
    Flux<AcpRuntimeEvent> execute(AgentContext ctx);
    Mono<AcpRuntimeTurnResult> executeBlocking(AgentContext ctx);
    AcpRuntimeHandle submit(AgentContext ctx);  // fire-and-forget with handle
}
```

**`AcpRuntimeHandle`** — handle to a running ACP task:
```java
public interface AcpRuntimeHandle {
    String getTaskId();
    Flux<AcpRuntimeEvent> events();
    Mono<AcpRuntimeTurnResult> result();
    Mono<Void> cancel();
    boolean isDone();
}
```

**`AcpRuntimeEvent`** — sealed interface for ACP events:
```java
public sealed interface AcpRuntimeEvent {
    record TextDelta(String text) implements AcpRuntimeEvent {}
    record ToolCall(String name, String arguments) implements AcpRuntimeEvent {}
    record ToolResult(String callId, String result) implements AcpRuntimeEvent {}
    record Error(String message) implements AcpRuntimeEvent {}
    record Done(AcpRuntimeTurnResult result) implements AcpRuntimeEvent {}
}
```

**`AcpRuntimeTurnResult`:**
```java
public record AcpRuntimeTurnResult(
    String finalResponse,
    int tokensUsed,
    List<ToolCallRecord> toolCalls,
    long durationMs
) {}
```

#### Task 3.3 — Create `DefaultAcpRuntime`
**Package:** `lyjew.com.lyclaw.react.acp`  
**File:** `DefaultAcpRuntime.java`

HTTP-based ACP client using Spring `WebClient`. Connects to a remote agent server endpoint, sends the agent context serialized as JSON, receives SSE stream of `AcpRuntimeEvent`.

**Configuration:** `lyclaw.acp.base-url`, `lyclaw.acp.timeout`, `lyclaw.acp.retry`.

#### Task 3.4 — Refactor `AgentProxyFactory` for full config + runtime types
**File:** `lyjew.com.lyclaw.react.AgentProxyFactory` (modify existing)

Changes:
- Accept `AgentDefaultsConfig` in constructor (new overload, old constructor preserved).
- Internally resolve `ResolvedAgentConfig` from annotation + defaults + sources.
- Select `AcpRuntime` or embedded execution based on `resolvedConfig.getRuntimeType()`.
- Wire resolved config into `AgentContext` via `ctx.setResolvedConfig(...)`.
- Pass `HookRegistry` instead of raw `List<AgentHook>` to `AgentInvocationHandler`.

**Backward compatibility:** The 4-argument constructor `(ChatFacade, ReActEngine, ToolRegistry)` remains. A new Builder API is added:
```java
AgentProxyFactory.builder()
    .chatFacade(chatFacade)
    .reActEngine(reActEngine)
    .toolRegistry(toolRegistry)
    .defaultsConfig(defaultsConfig)
    .hooks(hookRegistry)
    .stages(customStages)
    .build();
```

#### Task 3.5 — Update AgentInterfaceProcessor (BFPP)
**File:** Search for existing `BeanFactoryPostProcessor` or `BeanPostProcessor` that handles `@Agent`-annotated beans; create if absent.

Changes:
- Read all new `@Agent` annotation fields.
- Register each agent with its full metadata into `AgentRegistry`.
- Populate `AgentDefaultsConfig` from `application.yml`.
- Validate annotation fields against defaults (warn on conflicts, don't error).

#### Task 3.6 — Integration Test: Full Pipeline with New Config + Hooks
**File:** `lyclaw-framework/src/test/java/lyjew/com/lyclaw/react/FullPipelineIntegrationTest.java`

Test flow:
1. Define `@Agent` interface with extended fields.
2. Configure `application.yml` with `lyclaw.agent.defaults`.
3. Register custom hooks via `HookRegistry`.
4. Invoke agent method.
5. Verify: config resolution, hook call order, pipeline stage execution, response content.

#### Task 3.7 — Migrate Existing 5 Hook Implementations
**Files to verify (no code changes needed):**
- `SecurityCheckHook.java` (order=10)
- `SandboxHook.java` (order=20)
- `ApprovalHook.java` (order=30)
- `PlanningHook.java` (order=40)
- `OutputGuardHook.java` (order=90)

All new `AgentHook` methods are `default` no-ops, so these 5 implementations require zero changes. Document this explicitly.

#### Task 3.8 — Documentation
**Files:**
- Update `@Agent` annotation Javadoc with all new fields.
- Update `AgentHook` Javadoc with all 36 lifecycle points and execution order.
- Add package-info.java for `lyjew.com.lyclaw.react` with architecture overview.
- Add package-info.java for `lyjew.com.lyclaw.react.acp`.

---

## 3. Phase 2 Detailed Task List — Subagent + Models

**Goal:** Enable hierarchical agent delegation (parent spawns children) and a proper model catalog with fallback chains, thinking/reasoning level resolution, and auto-fallback probing.

**Dependency:** Requires `ResolvedAgentConfig` and expanded `AgentContext` from Phase 1.

---

### Week 5-7: Subagent System

#### Task 3.1 — Create `SubagentConfig`
**Package:** `lyjew.com.lyclaw.agent.subagent`  
**File:** `SubagentConfig.java`

```java
public record SubagentConfig(
    String agentId,              // target agent to spawn
    String task,                 // task description passed to child
    int maxTurns,               // max ReAct turns for child
    boolean inheritContext,      // whether child inherits parent's context
    boolean isolatedTools,       // whether child gets fresh tool set
    List<String> toolWhitelist, // if isolated, which tools to include
    long timeoutMs               // max wall-clock time for child
) {}
```

#### Task 3.2 — Create `SubagentSpawner`
**File:** `SubagentSpawner.java`

Core delegation engine:
```java
public class SubagentSpawner {
    Mono<SubagentResult> spawn(AgentContext parentCtx, SubagentConfig config);
    Flux<ServerSentEvent<String>> spawnStreaming(AgentContext parentCtx, SubagentConfig config);
}
```

**Flow:**
1. Validate `allowAgents` whitelist — child agent must be in parent's whitelist.
2. Check `maxSpawnDepth` — parent's current depth + 1 must not exceed config.
3. Acquire `maxChildrenPerAgent` semaphore permit.
4. Create child `AgentContext` with nested session key (`parentId/childId/turn`).
5. Execute child agent via `AgentRegistry` (same pipeline, separate context).
6. Release semaphore, archive child session.

#### Task 3.3 — Register `"delegate_to_agent"` as built-in tool
**File:** `DelegateToAgentTool.java`

A `@Tool`-annotated class that exposes subagent delegation to the LLM as a regular tool:
```json
{
  "name": "delegate_to_agent",
  "description": "Delegate a subtask to another specialized agent",
  "parameters": {
    "agent_name": "string (required) — name of the agent to delegate to",
    "task": "string (required) — description of the subtask",
    "max_turns": "integer (optional) — max ReAct iterations",
    "inherit_context": "boolean (optional) — whether child sees parent messages"
  }
}
```

The tool implementation calls `SubagentSpawner.spawn()` and returns the child's result.

#### Task 3.4 — Implement `allowAgents` whitelist check
**Location:** `SubagentSpawner.validateWhitelist()`

**Logic:**
- If parent's `allowAgents` is empty → no delegation allowed.
- If parent's `allowAgents` contains `"*"` → any agent allowed.
- Otherwise, child agent name must be in the list.
- Violation → throw `SubagentDelegationDeniedException` with reason.

#### Task 3.5 — Implement `maxSpawnDepth` recursion guard
**Logic:**
- Parent context carries `currentDepth` (0 for root).
- Child context gets `currentDepth = parent.currentDepth + 1`.
- If `currentDepth > maxSpawnDepth` → throw `MaxSpawnDepthExceededException`.
- Depth is configurable per-agent via annotation; system default = 3.

#### Task 3.6 — Implement `maxChildrenPerAgent` concurrency guard
**Logic:**
- Each agent context has a `Semaphore(maxChildrenPerAgent)`.
- `spawn()` acquires a permit before creating child, releases after child completes.
- If no permit available within timeout → throw `TooManyChildrenException`.
- Permit is released in `finally` block even on child error.

#### Task 3.7 — Implement subagent session management
**Logic:**
- Session keys: `rootSessionId/agentName/turnNumber` (nested hierarchy).
- Auto-archive child sessions on completion (configurable retention).
- Parent context tracks `activeSubagentIds` for monitoring/cancellation.
- `AgentContext.getActiveSubagentIds()` returns unmodifiable view.

#### Task 3.8 — Integrate into `RespondStage`
**File:** `lyjew.com.lyclaw.pipeline.stage.RespondStage` (modify)

When `ReActEngine` emits a tool call for `"delegate_to_agent"`:
- Route to `SubagentSpawner` instead of `ToolRegistry`.
- Stream child agent's SSE events as nested tool results.
- Record child result in parent's `toolResults`.

#### Task 3.9 — Create `SubagentSpawnerTest`
**File:** `lyclaw-framework/src/test/java/lyjew/com/lyclaw/agent/subagent/SubagentSpawnerTest.java`

Test cases:
- Successful delegation: parent → child → result returned.
- Whitelist violation: agent not in `allowAgents` → exception.
- Depth exceeded: child at depth 3 tries to spawn depth 4 → exception.
- Concurrency limit: spawn 6 children with `maxChildrenPerAgent=5` → 6th blocks/errors.
- Nested delegation: parent → child → grandchild (within limits) works.
- Child error propagates to parent gracefully.
- Child timeout terminates child and returns partial result.
- Session keys are correctly nested.

---

### Week 7-9: Model Catalog & Resolution

#### Task 3.10 — Create `ModelCatalogEntry`
**Package:** `lyjew.com.lyclaw.chat.catalog`  
**File:** `ModelCatalogEntry.java`

```java
public record ModelCatalogEntry(
    String modelId,              // e.g., "deepseek-v4-pro"
    String provider,             // e.g., "deepseek"
    String displayName,          // e.g., "DeepSeek V4 Pro"
    ModelCapabilities capabilities, // vision, audio, tool-use, etc.
    int contextWindow,           // max tokens
    int maxOutputTokens,         // max generation tokens
    boolean supportsThinking,    // extended thinking support
    boolean supportsReasoning,   // reasoning/CoT support
    boolean supportsStreaming,   // SSE streaming
    double costPer1kInput,       // pricing (optional)
    double costPer1kOutput,
    Map<String, Object> metadata // provider-specific
) {}
```

#### Task 3.11 — Create `ModelCatalog`
**File:** `ModelCatalog.java`

```java
public class ModelCatalog {
    void register(ModelCatalogEntry entry);
    Optional<ModelCatalogEntry> lookup(String modelId);
    List<ModelCatalogEntry> listByProvider(String provider);
    List<ModelCatalogEntry> listByCapability(ModelCapabilities required);
    List<ModelCatalogEntry> listAll();
    void loadFromFile(Path yamlPath);           // file-backed
    void discoverFromProviders();               // provider API discovery
}
```

**Storage:** `ConcurrentHashMap<String, ModelCatalogEntry>` keyed by `modelId`.

**File format** (`models.yaml`):
```yaml
models:
  - modelId: "deepseek-v4-pro"
    provider: "deepseek"
    contextWindow: 128000
    supportsThinking: true
    supportsStreaming: true
  - modelId: "deepseek-v4-flash"
    provider: "deepseek"
    contextWindow: 128000
    supportsStreaming: true
```

#### Task 3.12 — Create `AgentModelConfig`
**Package:** `lyjew.com.lyclaw.config`  
**File:** `AgentModelConfig.java`

```java
public record AgentModelConfig(
    String primary,                    // primary model ID
    List<String> fallbacks,            // ordered fallback chain
    AgentToolModelConfig toolModels    // specialized models for tools
) {}
```

#### Task 3.13 — Create `AgentToolModelConfig`
**File:** `AgentToolModelConfig.java`

```java
public record AgentToolModelConfig(
    String imageModel,    // model for image generation/analysis tools
    String videoModel,    // model for video tools
    String musicModel,    // model for audio/music tools
    String pdfModel       // model for PDF processing tools
) {}
```

#### Task 3.14 — Create `ModelResolutionService`
**Package:** `lyjew.com.lyclaw.chat`  
**File:** `ModelResolutionService.java`

Central service for all model resolution logic:
```java
public class ModelResolutionService {
    ChatModel resolvePrimary(AgentModelConfig config);
    List<ChatModel> resolveFallbackChain(AgentModelConfig config);
    ChatModel resolveForTool(String toolName, AgentToolModelConfig config);
    ThinkingLevel resolveThinking(ResolvedAgentConfig config);
    ReasoningLevel resolveReasoning(ResolvedAgentConfig config);
    VerboseLevel resolveVerbose(ResolvedAgentConfig config);
}
```

**Resolution order:**
1. Annotation override (highest priority)
2. Runtime override (from context attributes)
3. Agent default (config file)
4. System default (lowest priority)

#### Task 3.15 — Implement auto-fallback probing
**Package:** `lyjew.com.lyclaw.chat.fallback`  
**File:** `AutoFallbackProbe.java`

```java
public class AutoFallbackProbe {
    // Probes each model in the fallback chain with a lightweight request
    // Caches results with TTL
    Mono<ChatModel> probe(List<String> modelIds);
    FallbackState getState(String modelId);
}
```

**`FallbackState`:**
```java
public enum FallbackState { HEALTHY, DEGRADED, UNAVAILABLE, UNKNOWN }
```

**Probing strategy:**
- On startup, probe all registered models with a simple token-count request.
- On primary model failure, immediately probe fallback chain to find first healthy.
- Cache probe results for 30 seconds (configurable).
- Background health check every 60 seconds (configurable).

#### Task 3.16 — Implement thinking/reasoning/verbose level resolution
**Location:** `ModelResolutionService`

**Level enums:**
```java
public enum ThinkingLevel { OFF, LOW, MEDIUM, HIGH, MAX }
public enum ReasoningLevel { OFF, BRIEF, STANDARD, DETAILED }
public enum VerboseLevel { QUIET, NORMAL, VERBOSE, DEBUG }
```

**Passthrough to ChatModel:**
- When `ChatRequest` is built, inject `thinking_level`, `reasoning_level`, `verbose_level` into request parameters.
- Each `ChatModel` implementation reads these and passes to provider API.
- Provider-agnostic: models that don't support a level ignore it gracefully.

#### Task 3.17 — Create `ProviderDiscovery` interface + OpenAI implementation
**Package:** `lyjew.com.lyclaw.chat.discovery`  
**File:** `ProviderDiscovery.java`

```java
public interface ProviderDiscovery {
    List<ModelCatalogEntry> discover();
    boolean supports(String provider);
}
```

**`OpenAIProviderDiscovery`:**
- Calls `/v1/models` endpoint.
- Maps OpenAI model IDs to `ModelCatalogEntry` with known capabilities.

#### Task 3.18 — Update `ChatFacade` and `DefaultChatFacade`
**Files:** `ChatFacade.java`, `DefaultChatFacade.java` (modify)

New methods on `ChatFacade`:
```java
ModelCatalog getModelCatalog();
ModelResolutionService getModelResolution();
ChatModel resolveWithFallback(AgentModelConfig config);
```

`DefaultChatFacade` changes:
- Accept `ModelCatalog` and `ModelResolutionService` in constructor.
- In `chat(ChatRequest)`, use `ModelResolutionService` to resolve model + thinking/reasoning/verbose.
- On model error, trigger fallback chain via `AutoFallbackProbe`.

#### Task 3.19 — Create `RunRetriesConfig` + `RunRetryManager`
**Package:** `lyjew.com.lyclaw.retry`  
**Files:** `RunRetriesConfig.java`, `RunRetryManager.java`

```java
public record RunRetriesConfig(
    int maxRetries,           // default 2 (was hardcoded MAX_REFLECTION_RETRIES)
    double retryThreshold,    // default 0.6
    long backoffMs,           // base backoff
    double backoffMultiplier, // exponential factor
    List<Class<? extends Throwable>> retryableExceptions
) {}

public class RunRetryManager {
    boolean shouldRetry(int attempt, double score, int failCount, RunRetriesConfig config);
    long getBackoffMs(int attempt, RunRetriesConfig config);
}
```

#### Task 3.20 — Replace hardcoded `MAX_REFLECTION_RETRIES` with `RunRetryManager`
**File:** `AgentInvocationHandler.java` (modify)

Replace:
```java
// Before
private static final int MAX_REFLECTION_RETRIES = 2;
private static final double REFLECTION_RETRY_THRESHOLD = 0.6;
```

With:
```java
// After
private RunRetriesConfig retriesConfig;  // injected, default matches old behavior
```

#### Task 3.21 — Tests for model resolution, fallback chain, auto-probe
**Files:**
- `ModelResolutionServiceTest.java`
- `FallbackChainTest.java`
- `AutoFallbackProbeTest.java`

Test cases:
- Primary model resolves correctly from annotation.
- Fallback chain resolves in declared order.
- Thinking/reasoning/verbose levels resolve with correct priority (annotation > runtime > default > system).
- Auto-probe detects unhealthy model and switches to fallback.
- Probe cache expires and re-probes.
- Custom tool model resolution (image tool uses image model).
- Unknown model ID throws informative exception.
- Empty fallback chain returns error, not NPE.

---

### Week 9-10: Integration & Documentation

#### Task 3.22 — Integration test: subagent delegation chain
**File:** `SubagentDelegationChainTest.java`

Test: parent → child → grandchild, each with its own agent config and tool set.
- Verify depth tracking (parent depth=0, child=1, grandchild=2).
- Verify session key nesting.
- Verify grandchild result propagates back to parent.
- Verify `maxSpawnDepth=2` blocks great-grandchild.

#### Task 3.23 — Integration test: multi-model fallback
**File:** `MultiModelFallbackTest.java`

Test: Configure primary model to fail, verify fallback chain is probed and first healthy model used.
- Verify that thinking level is preserved across fallback.
- Verify that fallback events emit SSE messages.
- Verify that repeated failures exhaust fallback chain and produce error.

#### Task 3.24 — Documentation
- Package-info for `lyjew.com.lyclaw.agent.subagent`.
- Package-info for `lyjew.com.lyclaw.chat.catalog`.
- Update `ChatFacade` Javadoc with model catalog usage.

---

## 4. Phase 3 Detailed Task List — Context + Bootstrap + Routing

**Goal:** Intelligent context management (compaction, pruning), bootstrap file loading (AGENTS.md etc.), and multi-agent request routing.

**Dependency:** Requires `ResolvedAgentConfig` and `AgentContextLimits` from Phase 1.

---

### Week 11-12: Context Management

#### Task 4.1 — Create `CompactionConfig` + `CompactionEngine`
**Package:** `lyjew.com.lyclaw.context.compaction`  
**Files:** `CompactionConfig.java`, `CompactionEngine.java`

```java
public record CompactionConfig(
    boolean enabled,             // opt-in via config
    int triggerTokenThreshold,   // when context exceeds this, compact (e.g., 100000)
    int targetTokenCount,        // compact down to this (e.g., 30000)
    boolean preserveSystemPrompt, // always keep system prompt
    boolean preserveRecentMessages, // keep last N messages
    int recentMessagesCount,     // N
    String compactionModel       // model used for summarization (can be cheaper)
) {}
```

**`CompactionEngine`:**
```java
public class CompactionEngine {
    Mono<List<Message>> compact(List<Message> messages, CompactionConfig config);
    CompactionResult compactBlocking(List<Message> messages, CompactionConfig config);
}
```

**Algorithm:**
1. Split messages into: system prompt (preserved), early messages (candidate for summarization), recent N messages (preserved).
2. Send early messages to a cheap/fast model with prompt: "Summarize the key decisions, facts, and context from this conversation. Preserve all action items and pending tasks."
3. Replace early messages with a single system-style message: `[Context Summary] <summary>`.
4. Validate: token count of result <= target.
5. Return `CompactionResult` with before/after token counts and the summary text.

#### Task 4.2 — Create `CompactionStage`
**Package:** `lyjew.com.lyclaw.pipeline.stage`  
**File:** `CompactionStage.java`

New pipeline stage, ordered after `ReflectionStage` (order=4.5, between Reflection at 4 and Metrics at 5, or adjust existing orders).

**Logic:**
1. After Reflection completes (and before potential retry), check total token count of message list.
2. If above `triggerTokenThreshold`, run `CompactionEngine.compact()`.
3. Replace messages in `AgentContext` with compacted list.
4. Emit SSE event: `compaction_complete` with before/after token counts.
5. Trigger `onCompaction` hooks.

**Stage ordering update:** `MetricsStage` moves from order=5 to order=6. `CompactionStage` takes order=5.

#### Task 4.3 — Implement quality guard for compaction
**Location:** `CompactionEngine`

After compaction:
1. Reconstruct a "test prompt" from the compacted context.
2. Ask the compaction model: "Do you have enough information to continue this task? Respond YES or NO with a brief explanation."
3. If NO → redo compaction with a more conservative target (e.g., 1.5x original target).
4. If still NO after 2 retries → log warning, continue with original (uncompacted) context.

#### Task 4.4 — Implement mid-turn context pressure precheck
**Location:** `RespondStage` (modify)

Before each ReAct iteration:
- Estimate token count of current messages + tool results.
- If approaching `maxContextTokens` (e.g., >90%):
  - If `CompactionConfig.enabled`: trigger compaction mid-turn.
  - If compaction disabled: truncate oldest non-system messages with a warning SSE event.
- This prevents API errors from context overflow.

#### Task 4.5 — Implement post-compaction section injection
**Location:** `CompactionEngine`

After compaction, inject a section from `AGENTS.md` (loaded at bootstrap) to remind the agent of its identity and constraints. This prevents "context drift" after compaction removes early identity-setting messages.

#### Task 4.6 — Create `ContextPruningConfig` + `ContextPruningEngine`
**Package:** `lyjew.com.lyclaw.context.pruning`  
**Files:** `ContextPruningConfig.java`, `ContextPruningEngine.java`

```java
public record ContextPruningConfig(
    boolean enabled,
    int maxToolResults,      // max tool results to keep (oldest pruned first)
    int maxMessages,         // max messages total
    boolean pruneToolErrorsFirst, // prioritize pruning error messages
    List<String> preserveTools  // tool names whose results are always kept
) {}
```

**`ContextPruningEngine`:**
Surgical removal of individual messages:
- Remove oldest tool results exceeding `maxToolResults`.
- Remove oldest messages exceeding `maxMessages`.
- Always preserve system prompt.
- Replace pruned content with placeholder: `[Earlier content pruned to manage context]`.

#### Task 4.7 — Create `AgentContextLimits`
**Package:** `lyjew.com.lyclaw.config`  
**File:** `AgentContextLimits.java`

```java
public record AgentContextLimits(
    int maxTokens,           // total context window
    int maxSystemPromptTokens, // budget for system prompt
    int maxBootstrapTokens,  // budget for bootstrap
    int maxToolResultsTokens, // budget for tool results
    int maxMessagesTokens,   // budget for conversation messages
    int reserveTokens        // tokens reserved for model response
) {}
```

Default values derived from model's `contextWindow` in `ModelCatalogEntry`.

#### Task 4.8 — Integrate into `ContextEngine`
**File:** Search for existing context management; integrate or create `ContextEngine.java`.

`ContextEngine` becomes the single entry point for all context manipulation:
```java
public class ContextEngine {
    List<Message> buildContext(AgentContext ctx);
    List<Message> compact(AgentContext ctx);
    List<Message> prune(AgentContext ctx);
    int estimateTokens(List<Message> messages);
    AgentContextLimits getLimits(AgentContext ctx);
}
```

#### Task 4.9 — Create tests
**Files:**
- `CompactionEngineTest.java`
- `ContextPruningEngineTest.java`
- `CompactionStageTest.java`

Test cases:
- Compaction reduces token count below target.
- System prompt preserved after compaction.
- Recent N messages preserved.
- Quality guard detects information loss.
- Mid-turn precheck triggers compaction before overflow.
- Pruning removes oldest messages first.
- Preserved tools' results survive pruning.
- Token estimation is within 10% of actual.

---

### Week 12-13: Bootstrap Loading

#### Task 4.10 — Create `BootstrapLoader`
**Package:** `lyjew.com.lyclaw.bootstrap`  
**File:** `BootstrapLoader.java`

Loads agent identity and instruction files from the agent's workspace directory:

```java
public class BootstrapLoader {
    BootstrapContent load(AgentContext ctx);
    BootstrapContent load(String agentDir, String workspaceDir);
}
```

**`BootstrapContent`:**
```java
public record BootstrapContent(
    String agentsMd,       // AGENTS.md — core instructions
    String soulMd,         // SOUL.md — agent personality
    String bootstrapMd,    // BOOTSTRAP.md — startup context
    String identityMd,     // IDENTITY.md — agent identity/name/avatar
    String userMd,         // USER.md — user-specific overrides
    String heartbeatMd,    // HEARTBEAT.md — periodic check-in instructions
    int totalChars,
    Map<String, String> metadata
) {}
```

**File discovery order:**
1. `{agentDir}/AGENTS.md`
2. `{workspaceDir}/AGENTS.md` (fallback)
3. `{agentDir}/SOUL.md` → `{workspaceDir}/SOUL.md`
4. `{agentDir}/BOOTSTRAP.md` → `{workspaceDir}/BOOTSTRAP.md`
5. `{agentDir}/IDENTITY.md` → `{workspaceDir}/IDENTITY.md`
6. `{agentDir}/USER.md` → `{workspaceDir}/USER.md`
7. `{agentDir}/HEARTBEAT.md` → `{workspaceDir}/HEARTBEAT.md`

Each file is optional; missing files produce a debug log, not an error.

#### Task 4.11 — Create `BootstrapConfig` + `StartupContextConfig`
**Package:** `lyjew.com.lyclaw.config`  
**Files:** `BootstrapConfig.java`

```java
@ConfigurationProperties(prefix = "lyclaw.agent.bootstrap")
public record BootstrapConfig(
    boolean enabled,
    int maxChars,              // max chars per file (default 50000)
    int totalMaxChars,         // max chars across all files (default 200000)
    ContextInjectionPolicy injectionPolicy, // when to inject
    boolean truncateWithWarning // warn instead of error on truncation
) {}
```

#### Task 4.12 — Implement `ContextInjectionPolicy`
**File:** `ContextInjectionPolicy.java`

```java
public enum ContextInjectionPolicy {
    ALWAYS,              // inject bootstrap into every request's system prompt
    CONTINUATION_SKIP,   // skip injection on continuation turns (session already has it)
    NEVER                // never auto-inject (agent must load explicitly)
}
```

#### Task 4.13 — Implement truncation with warnings
**Location:** `BootstrapLoader`

If total bootstrap content exceeds `totalMaxChars`:
1. Load files in priority order (AGENTS.md first, HEARTBEAT.md last).
2. Truncate last-loaded files to fit within budget.
3. Add a system message: `[Note: Some bootstrap files were truncated to fit the context budget. Original sizes: ...]`
4. Log warning with details.

#### Task 4.14 — Enhance `ContextBuildStage` to load bootstrap
**File:** `ContextBuildStage.java` (modify)

After loading session and memories:
1. Call `BootstrapLoader.load(ctx)`.
2. Store `BootstrapContent` in `ctx.setAttribute("bootstrapContent", content)`.
3. If `ContextInjectionPolicy.ALWAYS` or `CONTINUATION_SKIP` (first turn):
   - Prepend bootstrap as system messages (before user message).
4. Apply truncation if needed.
5. Emit SSE event: `bootstrap_loaded` with file names and sizes.

#### Task 4.15 — Create `BootstrapLoaderTest`
**File:** `BootstrapLoaderTest.java`

Test cases:
- Loads AGENTS.md from agentDir.
- Falls back to workspaceDir when agentDir has no file.
- Missing optional files don't error.
- Truncation respects totalMaxChars.
- Truncation warning emitted.
- ContextInjectionPolicy.ALWAYS injects on every turn.
- ContextInjectionPolicy.CONTINUATION_SKIP skips on session continuation.
- ContextInjectionPolicy.NEVER never injects.
- File encoding issues handled gracefully.
- Large files (>10MB) rejected with clear error.

---

### Week 13-14: Routing & Identity

#### Task 4.16 — Create `AgentRouteBinding` + `AgentAcpBinding` + `AgentBindingMatch`
**Package:** `lyjew.com.lyclaw.routing`  
**Files:** `AgentRouteBinding.java`, `AgentAcpBinding.java`, `AgentBindingMatch.java`

```java
public record AgentRouteBinding(
    String pattern,              // URL path pattern, e.g., "/api/agent/{agentName}"
    String agentName,            // target agent
    boolean streaming,           // use SSE streaming for this route
    Map<String, String> headers  // additional headers to pass
) {}

public record AgentAcpBinding(
    String pattern,
    String acpEndpoint,          // remote ACP server URL
    String agentName,
    boolean streaming
) {}

public record AgentBindingMatch(
    AgentRouteBinding binding,
    Map<String, String> pathVariables
) {}
```

#### Task 4.17 — Create `AgentRouter`
**File:** `AgentRouter.java`

```java
public class AgentRouter {
    Optional<AgentBindingMatch> match(String path);
    void register(AgentRouteBinding binding);
    void register(AgentAcpBinding binding);
    void unregister(String pattern);
    List<AgentRouteBinding> listRoutes();
    List<AgentAcpBinding> listAcpBindings();
}
```

**Pattern matching:** Uses Spring's `AntPathMatcher` for glob-style patterns:
- `/api/agent/**` — all agents
- `/api/agent/code-reviewer` — specific agent
- `/api/agent/{agentName}` — path variable extraction

#### Task 4.18 — Create `AgentRoutingConfig` in `application.yml`
```yaml
lyclaw:
  agent:
    routing:
      enabled: true
      defaultAgent: "general-assistant"
      routes:
        - pattern: "/api/agent/code-reviewer"
          agentName: "code-reviewer"
          streaming: true
        - pattern: "/api/agent/data-analyst"
          agentName: "data-analyst"
          streaming: true
      acp:
        - pattern: "/api/acp/remote-agent"
          acpEndpoint: "https://remote.acp.example.com"
          agentName: "remote-agent"
          streaming: true
```

#### Task 4.19 — Update `ChatController` for multi-agent routing
**File:** Search for existing controller handling chat requests; update.

Before:
```java
@PostMapping("/chat")
Flux<ServerSentEvent<String>> chat(@RequestBody ChatRequest request) {
    return agent.invoke(request.getUserMessage());
}
```

After:
```java
@PostMapping("/chat")
Flux<ServerSentEvent<String>> chat(@RequestBody ChatRequest request,
                                    @RequestHeader Map<String, String> headers) {
    String agentName = resolveAgentName(request, headers); // from routing or request
    AgentHandle agent = agentRegistry.get(agentName);
    return agent.invoke(request.getUserMessage());
}

@PostMapping("/agent/{agentName}/chat")
Flux<ServerSentEvent<String>> agentChat(@PathVariable String agentName,
                                         @RequestBody ChatRequest request) {
    AgentHandle agent = agentRegistry.get(agentName);
    return agent.invoke(request.getUserMessage());
}
```

#### Task 4.20 — Create `IdentityConfig` + `AgentAvatarResolution`
**Package:** `lyjew.com.lyclaw.identity`  
**Files:** `IdentityConfig.java`, `AgentAvatarResolution.java`

```java
public record IdentityConfig(
    String name,              // display name
    String avatar,            // avatar URL or emoji
    String namePrefix,        // prepended to agent name in responses (e.g., "🤖")
    String messagePrefix,     // prepended to each message (e.g., "[CodeReviewer] ")
    String color,             // UI accent color
    String description        // bio / role description
) {}
```

```java
public record AgentAvatarResolution(
    String avatarUrl,
    String fallbackEmoji,
    String color
) {}
```

#### Task 4.21 — Create `IdentityResolver`
**File:** `IdentityResolver.java`

```java
public class IdentityResolver {
    IdentityConfig resolve(AgentContext ctx);
    IdentityConfig resolveFromWorkspace(String agentDir);
    IdentityConfig resolveFromAnnotation(Agent annotation);
    IdentityConfig resolveFromConfig(String agentName);
}
```

**Priority:**
1. `IDENTITY.md` file in agentDir
2. `@Agent` annotation (new fields)
3. `application.yml` config
4. Defaults (agent name as display name, no avatar)

#### Task 4.22 — Integrate identity into response formatting
**Location:** `RespondStage` and SSE event emission.

When emitting SSE `message` events, prepend `messagePrefix` and format with agent identity:
```json
{
  "event": "message",
  "data": "[CodeReviewer] Found 3 issues in the provided code...",
  "agent": {
    "name": "CodeReviewer",
    "avatar": "🔍",
    "color": "#4A90D9"
  }
}
```

#### Task 4.23 — Tests for routing and identity
**Files:**
- `AgentRouterTest.java`
- `IdentityResolverTest.java`

Test cases:
- Router matches exact patterns.
- Router matches glob patterns.
- Router extracts path variables.
- Router returns empty on no match.
- Identity resolves from IDENTITY.md.
- Identity resolves from annotation.
- Identity resolves from config.
- Identity falls back to defaults.
- Response formatting includes identity metadata.

---

## 5. Phase 4 Detailed Task List — Streaming + Sandbox + Heartbeat

**Goal:** Human-like streaming output (block coalescing, typing indicators, delays), containerized sandbox execution, and periodic heartbeat for agent wake-ups.

**Dependency:** Requires `ResolvedAgentConfig` (Phase 1) and `RespondStage` integration (Phase 2).

---

### Week 15-16: Streaming Enhancement

#### Task 5.1 — Create `BlockStreamingConfig` + `BlockStreamingEngine`
**Package:** `lyjew.com.lyclaw.stream`  
**Files:** `BlockStreamingConfig.java`, `BlockStreamingEngine.java`

```java
public record BlockStreamingConfig(
    boolean enabled,           // enable block coalescing
    int maxChars,              // max chars per block (default 80)
    long maxIdleMs,            // max idle time before flushing (default 150ms)
    boolean preserveNewlines,  // break blocks at newlines when possible
    boolean stripThinking,     // strip <thinking> tags from output
    boolean stripCodeFences    // strip ``` markers from blocks
) {}
```

**`BlockStreamingEngine`:**
```java
public class BlockStreamingEngine {
    Flux<String> coalesce(Flux<String> tokenStream, BlockStreamingConfig config);
    Flux<ServerSentEvent<String>> coalesceToSSE(Flux<String> tokenStream, BlockStreamingConfig config);
}
```

**Coalescing algorithm:**
1. Buffer incoming tokens (characters) into a `StringBuilder`.
2. Flush when: buffer reaches `maxChars`, OR `maxIdleMs` elapses since last token.
3. If `preserveNewlines`, also flush at `\n` boundaries.
4. If `stripThinking`, filter out content between `<thinking>` and `</thinking>` tags.
5. Emit each flushed block as a single SSE `message` event.

#### Task 5.2 — Implement `HumanDelayConfig` + `HumanDelayController`
**Files:** `HumanDelayConfig.java`, `HumanDelayController.java`

```java
public record HumanDelayConfig(
    boolean enabled,
    long minDelayMs,        // minimum delay between blocks (default 100ms)
    long maxDelayMs,        // maximum delay between blocks (default 400ms)
    double variability,     // randomness factor (0.0-1.0)
    boolean delayAfterNewlines, // longer delay after paragraph breaks
    long newlineExtraMs     // extra delay after newlines (default 200ms)
) {}
```

**`HumanDelayController`:**
```java
public class HumanDelayController {
    Mono<Void> delay();                    // random delay between min and max
    Mono<Void> delayAfterNewline();        // extra delay for paragraph breaks
}
```

Delay calculation: `minDelay + random() * (maxDelay - minDelay) * variability`, plus `newlineExtraMs` if the block ended with `\n\n`.

#### Task 5.3 — Implement `TypingIndicatorController`
**File:** `TypingIndicatorController.java`

```java
public enum TypingMode {
    NEVER,      // never show typing indicator
    INSTANT,    // show immediately before first message
    THINKING,   // show only during <thinking> blocks (if stripThinking=false)
    MESSAGE     // show before each message block
}
```

**`TypingIndicatorController`:**
```java
public class TypingIndicatorController {
    Flux<ServerSentEvent<String>> wrap(Flux<ServerSentEvent<String>> stream,
                                        TypingMode mode);
}
```

Logic:
- `NEVER`: passthrough, no modification.
- `INSTANT`: emit `{"event": "typing", "data": "start"}` before first message, `"stop"` after last.
- `THINKING`: emit typing when inside `<thinking>` tags.
- `MESSAGE`: emit typing before each block, stop after each block.

#### Task 5.4 — Integrate into `RespondStage` SSE output
**File:** `RespondStage.java` (modify)

Create a processing pipeline for the SSE stream:
```
raw SSE stream from ReActEngine
  → BlockStreamingEngine.coalesceToSSE()     [block coalescing]
  → HumanDelayController.delay()            [human-like delays]
  → TypingIndicatorController.wrap()        [typing indicators]
  → final SSE stream to client
```

Each stage in the pipeline reads from `ResolvedAgentConfig` and can be disabled (passthrough) via config.

#### Task 5.5 — Create streaming tests
**Files:**
- `BlockStreamingTest.java`
- `HumanDelayTest.java`
- `TypingIndicatorTest.java`

Test cases:
- Block coalescing: 200 chars of input → N blocks of maxChars each.
- Coalescing flushes on idle timeout.
- Coalescing preserves newlines when configured.
- `<thinking>` tags stripped when `stripThinking=true`.
- Human delay between blocks is within [min, max] range.
- Extra delay after newlines.
- Typing indicator emitted before first message (INSTANT mode).
- Typing indicator NOT emitted (NEVER mode).
- Pipeline end-to-end: raw tokens → blocks → delayed → typed → SSE.

---

### Week 16-17: Sandbox Execution

#### Task 5.6 — Create `AgentSandboxConfig`
**Package:** `lyjew.com.lyclaw.sandbox`  
**File:** `AgentSandboxConfig.java`

```java
@ConfigurationProperties(prefix = "lyclaw.agent.sandbox")
public record AgentSandboxConfig(
    boolean enabled,              // master switch
    String runtime,               // "docker" or "podman"
    String defaultImage,          // e.g., "ubuntu:22.04"
    Map<String, String> agentImages, // per-agent image overrides
    boolean readOnlyWorkspace,    // mount workspace as read-only
    boolean writableTmp,          // mount /tmp as writable
    long memoryLimitMb,           // memory limit
    long cpuLimit,                // CPU limit (0.0-1.0 per core)
    long timeoutSeconds,          // max execution time
    List<String> commandWhitelist, // allowed commands (empty = allow all)
    List<String> commandBlacklist, // blocked commands
    boolean networkDisabled,      // disable container networking
    boolean pullImageOnStart       // pull latest image before execution
) {}
```

#### Task 5.7 — Create `SandboxExecutionService`
**File:** `SandboxExecutionService.java`

Uses `docker-java` SDK (or command-line fallback):
```java
public class SandboxExecutionService {
    SandboxExecutionResult execute(SandboxExecutionRequest request);
    Mono<SandboxExecutionResult> executeAsync(SandboxExecutionRequest request);
    boolean isAvailable();
    void prewarm(String image);
}
```

**`SandboxExecutionRequest`:**
```java
public record SandboxExecutionRequest(
    String image,
    String command,
    List<String> args,
    String workdir,
    Map<String, String> env,
    Map<String, String> volumeMounts,
    long timeoutSeconds
) {}
```

**`SandboxExecutionResult`:**
```java
public record SandboxExecutionResult(
    int exitCode,
    String stdout,
    String stderr,
    long durationMs,
    boolean timedOut
) {}
```

#### Task 5.8 — Implement filesystem bridge
**Location:** `SandboxExecutionService`

- Mount workspace directory as read-only at `/workspace` inside container.
- Mount a temp directory as read-write at `/tmp/sandbox` inside container.
- On execution start, copy any needed files from workspace to `/tmp/sandbox`.
- On execution end, copy results from `/tmp/sandbox/output` back to workspace (if needed).
- Clean up temp directory after execution (configurable retention).

#### Task 5.9 — Implement command whitelist/blacklist
**Location:** `SandboxExecutionService`

Before container creation:
1. Parse the command string to extract the base command (first word).
2. If `commandWhitelist` is non-empty: command must be in whitelist.
3. If `commandBlacklist` is non-empty: command must NOT be in blacklist.
4. If whitelist is empty and blacklist is empty: allow all (sandbox isolation is sufficient).
5. Violation → throw `CommandNotAllowedException`.

#### Task 5.10 — Update `SandboxHook` to use `SandboxExecutionService`
**File:** `SandboxHook.java` (modify)

Current `SandboxHook` uses `ToolSandbox` for in-process sandboxing. Enhance to detect container-requiring tools and route them to `SandboxExecutionService`:

```java
// In SandboxHook.wrapToolExecutor():
if (tool.requiresContainer()) {
    return (name, id, args) -> sandboxExecutionService.execute(...);
}
// Otherwise, fall through to existing ToolSandbox
```

#### Task 5.11 — Create `SandboxExecutionTest`
**File:** `SandboxExecutionTest.java`

Note: Requires Docker daemon running. Use `@EnabledIf` or `@Category(RequiresDocker.class)`.

Test cases:
- Basic command execution: `echo "hello"` → stdout = "hello".
- Read-only workspace: write to `/workspace` fails.
- Writable `/tmp`: write to `/tmp/sandbox` succeeds.
- Command whitelist: whitelisted command runs, non-whitelisted fails.
- Command blacklist: blacklisted command fails.
- Timeout: `sleep 999` terminates.
- Memory limit: memory-hungry process gets OOM-killed.
- Network disabled: `curl` or `wget` fails.
- Concurrent executions: multiple containers run simultaneously.
- Cleanup: temp files removed after execution.

---

### Week 17-18: Heartbeat System

#### Task 5.12 — Create `HeartbeatConfig`
**Package:** `lyjew.com.lyclaw.heartbeat`  
**File:** `HeartbeatConfig.java`

```java
@ConfigurationProperties(prefix = "lyclaw.agent.heartbeat")
public record HeartbeatConfig(
    boolean enabled,
    String cron,                    // Spring cron expression
    String activeHoursStart,        // "09:00"
    String activeHoursEnd,          // "18:00"
    boolean lightContext,           // only load HEARTBEAT.md
    boolean isolatedSession,        // fresh session per heartbeat
    boolean skipWhenBusy,           // skip if subagents active
    long timeoutSeconds,            // max heartbeat duration
    int maxConsecutiveFailures,     // alert after N failures
    String alertChannel             // where to send alerts
) {}
```

#### Task 5.13 — Create `HeartbeatScheduler`
**File:** `HeartbeatScheduler.java`

```java
@Component
public class HeartbeatScheduler {
    @Scheduled(cron = "${lyclaw.agent.heartbeat.cron:0 */30 * * * *}")
    public void heartbeat() {
        // Guard checks then execute
    }
}
```

**Flow:**
1. Check `enabled` — return if disabled.
2. Check active hours window — return if outside.
3. Check `skipWhenBusy` — return if any agent has active subagents.
4. For each registered agent (or default agent only):
   a. Create fresh or reuse session based on `isolatedSession`.
   b. Build context: if `lightContext`, only HEARTBEAT.md; otherwise full bootstrap.
   c. Invoke agent with a special `__heartbeat__` trigger message.
   d. Record result, emit metrics.
   e. On failure: increment `consecutiveFailures`, check threshold, send alert.
5. Log summary: agents pinged, successes, failures.

#### Task 5.14 — Implement active hours window check
**Location:** `HeartbeatScheduler`

Parse `activeHoursStart` and `activeHoursEnd` as `LocalTime`. Compare with `LocalTime.now()`. Support overnight windows (e.g., 22:00-06:00).

#### Task 5.15 — Implement `lightContext` mode
**Location:** `HeartbeatScheduler` / `ContextBuildStage`

When `lightContext=true`:
- Only load `HEARTBEAT.md` as system prompt.
- Skip AGENTS.md, SOUL.md, BOOTSTRAP.md, IDENTITY.md, USER.md.
- Skip memory retrieval.
- Skip tool definitions (heartbeat is conversation-only, no tool calls).
- Set `fastMode=true` to use cheaper/faster model.

#### Task 5.16 — Implement `isolatedSession` mode
**Location:** `HeartbeatScheduler`

When `isolatedSession=true`:
- Generate new `sessionId` for each heartbeat.
- Do not load previous session messages.
- Do not persist heartbeat session.

When `isolatedSession=false`:
- Use the agent's default persistent session.
- Heartbeat conversation accumulates across runs.

#### Task 5.17 — Implement `skipWhenBusy`
**Location:** `HeartbeatScheduler`

Check `AgentContext.getActiveSubagentIds()` for all registered agents. If any agent has active subagents → skip this heartbeat cycle and log: `Heartbeat skipped: agent X has Y active subagents`.

#### Task 5.18 — Register heartbeat hooks
**Location:** `HookRegistry`

`HeartbeatScheduler` registers itself to receive `onHeartbeat` lifecycle events. Other hooks can also implement `onHeartbeat` for custom periodic behavior.

#### Task 5.19 — Create `HeartbeatSchedulerTest`
**File:** `HeartbeatSchedulerTest.java`

Test cases:
- Heartbeat executes on cron schedule (use `@Scheduled` test utilities or manual trigger).
- Heartbeat skipped when disabled.
- Heartbeat skipped outside active hours.
- Heartbeat skipped when agents are busy.
- `lightContext` only loads HEARTBEAT.md.
- `isolatedSession` creates fresh session each time.
- Non-isolated session accumulates messages.
- Consecutive failures trigger alert at threshold.
- Successful heartbeat resets failure counter.
- Multiple agents are each pinged.

---

## 6. Testing Strategy

### 6.1 Unit Tests

**Principle:** Every new class must have a corresponding unit test class. Existing classes modified must have new test methods added (not replacing existing ones).

**Per-phase targets:**

| Phase | New classes | New test classes | Min. coverage |
|-------|-------------|------------------|---------------|
| Phase 1 | ~12 | ~8 | 85% |
| Phase 2 | ~15 | ~12 | 85% |
| Phase 3 | ~14 | ~10 | 80% |
| Phase 4 | ~12 | ~10 | 80% |

### 6.2 Integration Tests

**Critical integration test scenarios:**

1. **Full pipeline** (Phase 1): `@Agent` with extended fields → config resolution → hook dispatch → 6 stages → response.
2. **Subagent chain** (Phase 2): Parent delegates to child, child delegates to grandchild, results propagate, depth/capacity limits enforced.
3. **Model fallback** (Phase 2): Primary model fails → fallback chain probed → fallback model used → thinking level preserved.
4. **Compaction + retry** (Phase 3): Context overflows → compaction reduces size → reflection score low → retry with compacted context.
5. **Bootstrap + routing** (Phase 3): Request routed to specific agent → bootstrap loaded → context injected → response with identity prefix.
6. **Streaming pipeline** (Phase 4): Raw tokens → block coalescing → human delay → typing indicator → SSE events.
7. **Heartbeat** (Phase 4): Scheduler fires → agent wakes → runs with light context → result logged.

### 6.3 Backward Compatibility Tests

**Non-negotiable:** All 49 existing tests must pass after every phase.

**Backward compat checklist (verified at each phase completion):**
- [ ] `@Agent(name="test")` compiles and runs (new fields defaulted).
- [ ] Existing 5 `AgentHook` implementations compile without changes.
- [ ] `AgentProxyFactory(ChatFacade, ReActEngine, ToolRegistry)` constructor still works.
- [ ] `AgentInvocationHandler` dispatches existing hooks correctly.
- [ ] All 6 pipeline stages execute in correct order.
- [ ] `Flux<ServerSentEvent<String>>` return type works for SSE passthrough.
- [ ] `String` return type works for blocking invocation.
- [ ] `AgentConfigResolver.resolve(agentName)` returns valid `AgentConfig`.
- [ ] `AgentContext` constructor and `toSnapshot`/`restoreFromSnapshot` work.

### 6.4 Performance Tests

**Compaction performance:**
- Transcript of 100K tokens: compaction must complete in <5 seconds.
- Transcript of 200K tokens: compaction must complete in <10 seconds.
- Token count estimation: <1% error margin vs. actual API count.

**Block streaming throughput:**
- 1000 tokens/second input: no backpressure, no dropped events.
- Coalescing overhead: <1ms per block.

**Subagent spawning:**
- Concurrent spawning of 5 children: all complete within parent timeout.
- Context memory: each child adds <1MB heap.

### 6.5 Security Tests

**Sandbox isolation:**
- Container cannot access host filesystem outside mounted volumes.
- Network-disabled containers cannot make outbound connections.
- Memory/CPU limits enforced by container runtime.
- Command injection via arguments blocked by whitelist.

**Content safety:**
- `OutputGuardHook` catches sensitive patterns in compacted/summarized content.
- Bootstrap files cannot contain executable code (validate during load).

---

## 7. Migration Plan

### 7.1 Phase 1: Agent Core Enhancement

**Breaking changes: NONE**

All new fields on `@Agent` and `AgentHook` are optional with sensible defaults that match the current behavior:
- New `@Agent` fields default to empty string / 0 / empty array → treated as "use default".
- New `AgentHook` methods are `default` no-ops.
- New `AgentContext` fields are initialized to null/empty and accessed through getters with null-safety.
- `AgentProxyFactory` new constructor overloads; old constructors preserved.

**Migration steps for existing users:**
1. Update dependency version. No code changes required.
2. (Optional) Add `lyclaw.agent.defaults` to `application.yml` for centralized config.
3. (Optional) Add new fields to `@Agent` annotations for per-agent customization.

### 7.2 Phase 2: Subagent + Models

**Breaking changes: NONE**

- Subagent system is additive: existing single-agent flows unchanged.
- `"delegate_to_agent"` tool is added to tool definitions; if LLM never calls it, behavior is identical.
- Model catalog is additive: existing `ChatModelRegistry` still works; catalog is consulted first, falls back to registry.
- `RunRetryManager` replaces hardcoded constants but defaults to same values (2 retries, 0.6 threshold).

**Migration steps for existing users:**
1. No changes required for single-agent use.
2. To use subagents: add `allowAgents` to parent agent's `@Agent` annotation.
3. To use model catalog: optionally add `models.yaml` or rely on provider discovery.

### 7.3 Phase 3: Context + Bootstrap + Routing

**Breaking changes: NONE**

- Compaction is opt-in via `lyclaw.agent.compaction.enabled=true`. Default is `false` (disabled).
- Bootstrap loading is opt-in: files must exist in agentDir/workspaceDir. No files → no effect.
- Routing is opt-in: `lyclaw.agent.routing.enabled=true`. Default is direct invocation.
- Context pruning is opt-in: `lyclaw.agent.pruning.enabled=true`. Default is `false`.
- `ContextBuildStage` gracefully handles missing `BootstrapLoader` bean.

**Migration steps for existing users:**
1. No changes required.
2. To use bootstrap: create `AGENTS.md` in agent's workspace.
3. To use compaction: enable in config.
4. To use routing: add routes to config.

### 7.4 Phase 4: Streaming + Sandbox + Heartbeat

**Breaking changes: NONE**

- Block streaming is opt-in: `lyclaw.agent.streaming.block.enabled=true`. Default `false` → raw token passthrough (current behavior).
- Human delay is opt-in: `lyclaw.agent.streaming.humanDelay.enabled=true`. Default `false`.
- Typing indicator is opt-in: `lyclaw.agent.streaming.typingMode=MESSAGE`. Default `NEVER`.
- Sandbox requires Docker/podman daemon; if unavailable, falls back to existing in-process `ToolSandbox`.
- Heartbeat is opt-in: `lyclaw.agent.heartbeat.enabled=true`. Default `false`.

**Migration steps for existing users:**
1. No changes required; all current SSE behavior preserved by default.
2. To use block streaming: enable block coalescing in config.
3. To use sandbox: install Docker, enable sandbox in config.
4. To use heartbeat: enable in config, configure cron.

---

## 8. Success Metrics

| Metric | Current | Target | Measurement |
|--------|---------|--------|-------------|
| `@Agent` annotation fields | 6 | 26+ | Count of declared fields in annotation |
| `AgentHook` lifecycle points | 6 | 36 | Count of methods in interface |
| `AgentContext` runtime fields | ~20 | ~35 | Count of fields providing runtime data |
| Pipeline stages | 6 | 7 (add CompactionStage) | Count of `@PipelineStage` beans |
| Hook implementations | 5 | 5+ (new hooks optional) | Existing hooks unchanged |
| Subagent delegation depth | N/A | Configurable (default 3) | Integration test |
| Model fallback chain | N/A (manual) | Automatic with probing | AutoFallbackProbeTest |
| Compaction token reduction | N/A | >80% reduction | CompactionEngineTest |
| Context pruning | N/A | Surgical per-message pruning | ContextPruningEngineTest |
| Bootstrap file support | N/A | 6 file types loaded | BootstrapLoaderTest |
| Multi-agent routing | N/A | Pattern-based routing | AgentRouterTest |
| Block streaming | Raw token passthrough | Coalesced with delay | BlockStreamingTest |
| Human-like typing | None | Configurable delay + indicators | HumanDelayTest |
| Sandbox execution | In-process only | Docker/podman containers | SandboxExecutionTest |
| Heartbeat system | None | Cron-scheduled periodic checks | HeartbeatSchedulerTest |
| Existing tests passing | 49 | 49 (zero regression) | `mvn test` |
| New test coverage | N/A | >80% per phase | JaCoCo / jacoco-maven-plugin |
| Config parity with OpenClaw | ~30% | >90% of AgentConfig fields | Manual field-by-field comparison |
| Breaking changes | N/A | 0 (ZERO) | Compilation of existing user code |

---

## 9. Package Structure (Target)

```
lyjew.com.lyclaw
├── agent
│   ├── subagent
│   │   ├── SubagentConfig.java
│   │   ├── SubagentSpawner.java
│   │   ├── SubagentResult.java
│   │   ├── DelegateToAgentTool.java
│   │   └── exception/
│   │       ├── SubagentDelegationDeniedException.java
│   │       ├── MaxSpawnDepthExceededException.java
│   │       └── TooManyChildrenException.java
│   └── (existing agent classes unchanged)
├── annotation
│   └── Agent.java (extended, backward-compat)
├── bootstrap
│   ├── BootstrapLoader.java
│   ├── BootstrapContent.java
│   └── ContextInjectionPolicy.java
├── chat
│   ├── catalog
│   │   ├── ModelCatalog.java
│   │   ├── ModelCatalogEntry.java
│   │   └── ProviderDiscovery.java
│   ├── discovery
│   │   └── OpenAIProviderDiscovery.java
│   ├── fallback
│   │   ├── AutoFallbackProbe.java
│   │   └── FallbackState.java
│   ├── ChatFacade.java (extended)
│   ├── ModelResolutionService.java
│   └── (existing chat classes unchanged)
├── config
│   ├── AgentDefaultsConfig.java (new)
│   ├── ResolvedAgentConfig.java (new)
│   ├── AgentConfigResolver.java (extended)
│   ├── AgentModelConfig.java (new)
│   ├── AgentToolModelConfig.java (new)
│   ├── AgentContextLimits.java (new)
│   ├── BootstrapConfig.java (new)
│   └── (existing config classes)
├── context
│   ├── compaction
│   │   ├── CompactionConfig.java
│   │   ├── CompactionEngine.java
│   │   └── CompactionResult.java
│   ├── pruning
│   │   ├── ContextPruningConfig.java
│   │   ├── ContextPruningEngine.java
│   │   └── PruningResult.java
│   └── ContextEngine.java
├── heartbeat
│   ├── HeartbeatConfig.java
│   └── HeartbeatScheduler.java
├── identity
│   ├── IdentityConfig.java
│   ├── IdentityResolver.java
│   └── AgentAvatarResolution.java
├── pipeline
│   └── stage
│       ├── CompactionStage.java (new)
│       ├── ContextBuildStage.java (extended)
│       ├── RespondStage.java (extended)
│       └── (existing stages)
├── react
│   ├── AgentContext.java (extended)
│   ├── AgentHook.java (extended, 36 methods)
│   ├── AgentInvocationHandler.java (extended)
│   ├── AgentProxyFactory.java (extended)
│   ├── HookRegistry.java (new)
│   ├── HookDecision.java (new)
│   ├── HookRegistration.java (new)
│   ├── AgentFinalizeResult.java (new)
│   ├── AgentRuntimeType.java (new)
│   ├── acp
│   │   ├── AcpRuntime.java
│   │   ├── AcpRuntimeHandle.java
│   │   ├── AcpRuntimeEvent.java
│   │   ├── AcpRuntimeTurnResult.java
│   │   └── DefaultAcpRuntime.java
│   └── (existing react classes)
├── retry
│   ├── RunRetriesConfig.java
│   └── RunRetryManager.java
├── routing
│   ├── AgentRouter.java
│   ├── AgentRouteBinding.java
│   ├── AgentAcpBinding.java
│   └── AgentBindingMatch.java
├── sandbox
│   ├── AgentSandboxConfig.java
│   └── SandboxExecutionService.java
└── stream
    ├── BlockStreamingConfig.java
    ├── BlockStreamingEngine.java
    ├── HumanDelayConfig.java
    ├── HumanDelayController.java
    └── TypingIndicatorController.java
```

---

## 10. Risk Register

| Risk ID | Description | Phase | Likelihood | Impact | Mitigation |
|---------|-------------|-------|------------|--------|------------|
| R1 | Deep-merge config logic is incorrect, causing silent misconfiguration | 1 | Medium | High | Comprehensive ConfigResolutionTest with property-based testing (jqwik) |
| R2 | Hook dispatch order breaks backward compat with existing 5 hooks | 1 | Low | High | Regression suite runs all existing hook tests first |
| R3 | Semaphore-based concurrency guard causes deadlock in nested subagent spawning | 2 | Medium | Medium | Timeout on semaphore acquire; deadlock detection in tests |
| R4 | Auto-fallback probing adds latency to every request | 2 | Medium | Medium | Probe results cached with TTL; probing is async, not on critical path |
| R5 | Compaction loses critical context, causing wrong agent behavior | 3 | Medium | High | Quality guard validates compaction output; opt-in with default off |
| R6 | Docker daemon not available in CI, sandbox tests fail | 4 | High | Medium | `@EnabledIf` annotation skips Docker tests in CI; mock sandbox for unit tests |
| R7 | `@Scheduled` heartbeat fires during integration tests, causing flaky tests | 4 | Medium | Medium | Heartbeat disabled by default; tests set `enabled=false` explicitly |
| R8 | SSE stream transformation pipeline introduces backpressure or dropped events | 4 | Medium | High | Performance test with 1000 tokens/sec; backpressure test with slow consumer |
| R9 | Migration fatigue: too many new config options overwhelm users | All | Medium | Low | Sensible defaults; all features opt-in; migration guide doc |

---

## 11. References

- [OpenClaw AgentConfig source](https://github.com/openclaw/openclaw) — field parity target
- [OpenClaw AgentHook source](https://github.com/openclaw/openclaw) — lifecycle point reference
- [Spring `@ConfigurationProperties` docs](https://docs.spring.io/spring-boot/docs/current/reference/html/configuration-metadata.html)
- [docker-java SDK](https://github.com/docker-java/docker-java) — sandbox container management
- [Project Reactor reference](https://projectreactor.io/docs/core/release/reference/) — SSE streaming pipeline
- [LyClaw Agent annotation (current)](../../lyclaw-framework/src/main/java/lyjew/com/lyclaw/annotation/Agent.java)
- [LyClaw AgentHook (current)](../../lyclaw-framework/src/main/java/lyjew/com/lyclaw/react/AgentHook.java)
- [LyClaw AgentContext (current)](../../lyclaw-framework/src/main/java/lyjew/com/lyclaw/react/AgentContext.java)
- [LyClaw Pipeline stages (current)](../../lyclaw-framework/src/main/java/lyjew/com/lyclaw/pipeline/stage/)
