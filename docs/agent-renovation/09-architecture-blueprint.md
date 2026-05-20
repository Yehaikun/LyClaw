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
