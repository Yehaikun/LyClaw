# LyClaw Agent 改造第二阶段：子代理委派系统 + 模型管理增强

## 目录

1. [背景与分析](#1-背景与分析)
2. [2.1 子代理委派系统](#21-子代理委派系统)
   - [2.1.1 SubagentConfig](#211-subagentconfig)
   - [2.1.2 SubagentSpawner](#212-subagentspawner)
   - [2.1.3 内置 delegate_to_agent 工具](#213-内置-delegate_to_agent-工具)
   - [2.1.4 委派流程](#214-委派流程)
   - [2.1.5 子代理会话管理](#215-子代理会话管理)
   - [2.1.6 并发控制](#216-并发控制)
   - [2.1.7 AgentContext 对子代理的增强](#217-agentcontext-对子代理的增强)
   - [2.1.8 Agent 注解对子代理的增强](#218-agent-注解对子代理的增强)
   - [2.1.9 子代理钩子系统](#219-子代理钩子系统)
   - [2.1.10 子代理错误处理与超时](#2110-子代理错误处理与超时)
   - [2.1.11 配置（application.yml）](#2111-配置applicationyml)
2. [2.2 模型管理增强](#22-模型管理增强)
   - [2.2.1 模型目录](#221-模型目录)
   - [2.2.2 AgentDefaultsConfig 中的多模型支持](#222-agentdefaultsconfig-中的多模型支持)
   - [2.2.3 模型选择与解析](#223-模型选择与解析)
   - [2.2.4 思考/推理/详细程度控制](#224-思考推理详细程度控制)
   - [2.2.5 提供商发现](#225-提供商发现)
   - [2.2.6 模型回退链集成](#226-模型回退链集成)
   - [2.2.7 思考相关的 SSE 事件](#227-思考相关的-sse-事件)
   - [2.2.8 ChatRequest 与 ChatModel 增强](#228-chatrequest-与-chatmodel-增强)
   - [2.2.9 配置（application.yml）](#229-配置applicationyml)
3. [集成点汇总](#3-集成点汇总)
4. [迁移路径](#4-迁移路径)

---

## 1. 背景与分析

### 1.1 当前架构差距

LyClaw 目前存在两个平行但互不连通的世界：

**世界 A — 多代理基础设施（独立存在，核心循环中未使用）：**
- `AgentCoordinator`、`CollaborationHub`、`ConsensusEngine` — 多代理编排
- `AgentCommProtocol`、`AgentChannel` — 代理间通信
- `AgentRegistry`、`AgentHandle`、`AgentLifecycle` — 代理生命周期管理
- `AgentSpec`、`AgentState`、`AgentTask` — 代理描述和任务模型
- `AgentPoolSnapshot`、`AutoScaler`、`ScalingDecision` — 池扩缩容
- `ExternalAgentAdapter`、`AgentCard`、`TaskStatus` — 外部代理桥接

这些类位于 `lyclaw-framework/src/main/java/lyjew/com/lyclaw/agent/` 目录下，但**从未被**核心代理管道调用。它们是为一个假想的多代理世界设计的独立抽象，而实际的 ReAct 引擎对此毫无概念。

**世界 B — 核心代理循环（实际运行的部分）：**
- `AgentInvocationHandler` → 阶段管道（`ContextBuildStage` → `SecurityCheckStage` → `PlanExecutionStage` → `RespondStage` → `ReflectionStage` → `MetricsStage`）
- `RespondStage` 委托给 `ReActEngine.executeStream()`（具体为 `DefaultReActEngine`）
- `ReActEngine` 循环：LLM 调用 → 如果有 tool_calls，通过 `ToolExecutor` 执行工具 → 将结果反馈回去 → 重复
- `ToolRegistry` 提供工具定义和执行。不存在"委托给另一个代理"的工具。

**模型管理（基础）：**
- `ChatFacade`（由 `DefaultChatFacade` 实现）封装 `ChatModelRegistry` + `ModelRouter`
- `FirstAvailableRouter` — 总是选取第一个提供商中的第一个模型。没有任何智能。
- 三个装饰器：`CircuitBreakerChatModel`、`FallbackChatModel`、`RetryChatModel`
- `ChatProperties` — 基于 YAML 的配置，包含 `defaultProvider`、`defaultModel`、`models` 映射
- `AgentConfig` — 来自注解/yml/数据库的合并配置，包含 `model` 和 `provider` 字符串字段
- `@Agent` 注解具有 `model()` 和 `provider()` 字符串字段
- `ChatRequest` 具有 `thinkingEnabled`（boolean）和 `thinkingBudget`（Integer）— 非常基础
- `ModelCapabilities` — streaming、toolCalling、thinking、vision、promptCaching 标志

### 1.2 第二阶段目标

1. **将子代理委派集成到核心代理循环中** — 当 LLM 决定委派任务时，会生成一个新的代理会话，独立运行其完整管道，并将结果作为工具观察返回给父代理。
2. **增强模型管理** — 引入模型目录、多模型支持（图像、音频、视频生成模型）、思考/推理级别控制、提供商发现和模型别名。

---

## 2.1 子代理委派系统

### 2.1.1 SubagentConfig

```java
package lyjew.com.lyclaw.react.subagent;

import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.TimeUnit;

/**
 * 子代理生成的配置，合并自以下来源：
 * <ol>
 *   <li>硬编码默认值（本类的静态默认值）</li>
 *   <li>application.yml（lyclaw.subagent.*）</li>
 *   <li>@Agent 注解扩展（例如，"subagent.maxConcurrent"）</li>
 * </ol>
 *
 * <p>每个父代理持有一个 SubagentConfig，用于管控它可以生成哪些子代理以及如何生成。
 * 当调用 spawnSubagent() 时，子代理自身的 @Agent 注解配置首先被解析，然后
 * 被父代理的 SubagentConfig 覆盖，以确保安全限制（maxSpawnDepth、maxConcurrent
 * 始终受父代理设置约束）。</p>
 */
public class SubagentConfig {

    // ── 委派模式 ──

    /**
     * 该代理的委派模式：
     * <ul>
     *   <li>"suggest" — 告知 LLM 它<i>可以</i>委派但不是必须的。
     *       工具定义中包含建议可选委派的描述。</li>
     *   <li>"prefer" — 告知 LLM 在适用时<i>应该</i>委派。
     *       工具描述和系统提示会调整为鼓励委派。</li>
     * </ul>
     */
    private String delegationMode = "suggest";

    /**
     * 该父代理允许委派到的代理 ID 列表。
     * 包含 "*" 的单元素列表表示所有已注册的代理。
     * 空列表表示完全禁用委派。
     */
    private List<String> allowAgents = new ArrayList<>(List.of("*"));

    // ── 并发与深度 ──

    /** 每个父代理允许的最大并发子代理运行数。默认 1（串行）。 */
    private int maxConcurrent = 1;

    /**
     * 最大生成深度。1 表示父代理可以生成子代理，但子代理
     * 不能再生成孙代理（无递归生成）。2 表示允许生成孙代理，
     * 依此类推。深度通过 AgentContext.runMetadata.subagentDepth 追踪。
     */
    private int maxSpawnDepth = 1;

    /** 每个父代理允许的最大活跃子代理数（尚未归档）。 */
    private int maxChildrenPerAgent = 5;

    // ── 会话生命周期 ──

    /** 子代理会话在非活跃指定分钟后自动归档。 */
    private int archiveAfterMinutes = 60;

    // ── 子代理的模型覆盖 ──

    /**
     * 用于子代理的可选模型名称。如果为 null，则使用子代理自身
     * 的配置模型（来自 @Agent 注解或 yml）。
     */
    private String model;

    /**
     * 子代理的可选思考/推理级别。
     * 覆盖子代理自身的思考级别。
     */
    private String thinking;

    // ── 超时设置 ──

    /** 每个子代理运行的超时时间（秒）。默认 300（5 分钟）。 */
    private int runTimeoutSeconds = 300;

    /** 父代理等待子代理首次通告（token）的超时时间。 */
    private int announceTimeoutMs = 120_000;

    // ── 身份设置 ──

    /**
     * 当为 true 时，父代理 LLM 在调用 delegate_to_agent 时<b>必须</b>指定具体的 agentId。
     * 当为 false 时，父代理可以省略 agentId，系统将尝试通过能力/描述自动匹配。
     */
    private boolean requireAgentId = false;

    // ── 静态默认值 ──

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
     * 将另一个配置合并到本配置中。{@code other} 中的非默认值
     * 会覆盖本配置的值。用于将父配置叠加到子配置的默认值上。
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

这是生成和运行子代理的核心编排器。它被注入到 `ToolRegistry`（或新的 `ToolProvider`）中，因此当 LLM 调用 `delegate_to_agent` 工具时，执行会通过此类进行路由。

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
 * 用于生成和管理子代理执行的核心编排器。
 *
 * <h3>生命周期</h3>
 * <ol>
 *   <li>LLM 调用 {@code delegate_to_agent} 工具 → 工具执行器调用
 *       {@link #spawnSubagent(String, String, Map, AgentContext)}</li>
 *   <li>验证：检查 allowAgents 白名单、深度限制、子代理数量限制</li>
 *   <li>从 AgentConfigResolver 解析子代理配置</li>
 *   <li>为子代理构建隔离的 AgentContext</li>
 *   <li>分发 {@code subagentSpawning} 钩子</li>
 *   <li>运行子代理的完整管道（ContextBuild → ... → Metrics）</li>
 *   <li>分发 {@code subagentSpawned} 和 {@code subagentEnded} 钩子</li>
 *   <li>将 {@link SubagentResult} 作为工具观察返回给父代理</li>
 * </ol>
 *
 * <h3>并发模型</h3>
 * <p>每个父代理拥有一个 Semaphore(maxConcurrent) 来限制并发
 * 子代理运行数。深度通过父代理的
 * {@code ctx.runMetadata.subagentDepth} 追踪。活跃子代理通过
 * {@code ctx.runMetadata.activeSubagentIds} 追踪。</p>
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
     * 每个父代理的信号量映射，用于并发控制。
     * Key = 父代理 sessionKey。
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
     * 生成一个子代理来执行给定的任务。
     *
     * <p>此方法通常从支持 {@code delegate_to_agent} 内置工具的
     * 工具执行器调用。</p>
     *
     * @param targetAgentId 要委派到的代理 ID（如果 requireAgentId 为 false
     *        且启用了自动匹配，则可以为 null）
     * @param task 子代理的自然语言任务描述
     * @param options 来自工具调用的附加选项（例如，模式覆盖）
     * @param parentCtx 父代理的上下文
     * @return 返回一个 Mono，在完成时包含子代理的结果
     */
    public Mono<SubagentResult> spawnSubagent(String targetAgentId, String task,
                                               Map<String, Object> options,
                                               AgentContext parentCtx) {
        Instant startTime = Instant.now();
        String parentSessionKey = parentCtx.getSessionId();

        // ── 1. 解析父代理的 SubagentConfig ──
        SubagentConfig parentConfig = resolveSubagentConfig(parentCtx);

        // ── 2. 验证限制 ──
        // 2a. 检查委派是否启用（非空 allowAgents）
        if (parentConfig.getAllowAgents().isEmpty()) {
            return Mono.just(SubagentResult.error("该代理已禁用委派功能"));
        }

        // 2b. 检查 allowAgents 白名单
        if (!parentConfig.getAllowAgents().contains("*")
                && !parentConfig.getAllowAgents().contains(targetAgentId)) {
            return Mono.just(SubagentResult.error(
                    "代理 '" + targetAgentId + "' 不在允许的委派列表中。 "
                    + "允许的代理: " + parentConfig.getAllowAgents()));
        }

        // 2c. 检查 maxSpawnDepth
        int parentDepth = parentCtx.getRunMetadata().getSubagentDepth();
        if (parentDepth + 1 > parentConfig.getMaxSpawnDepth()) {
            return Mono.just(SubagentResult.error(
                    "超过最大生成深度。当前深度: " + parentDepth
                    + "，最大: " + parentConfig.getMaxSpawnDepth()));
        }

        // 2d. 检查 maxChildrenPerAgent
        Set<String> activeChildren = parentCtx.getRunMetadata().getActiveSubagentIds();
        if (activeChildren.size() >= parentConfig.getMaxChildrenPerAgent()) {
            return Mono.just(SubagentResult.error(
                    "超过每个代理的最大子代理数。当前活跃: " + activeChildren.size()
                    + "，最大: " + parentConfig.getMaxChildrenPerAgent()));
        }

        // 2e. 并发信号量
        Semaphore semaphore = concurrencySemaphores.computeIfAbsent(
                parentSessionKey, k -> new Semaphore(parentConfig.getMaxConcurrent()));

        return Mono.fromCallable(() -> {
            if (!semaphore.tryAcquire()) {
                return SubagentResult.error(
                        "达到最大并发子代理数 (" + parentConfig.getMaxConcurrent() + ")");
            }
            return null; // 已获取，继续
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
                return Mono.just(SubagentResult.error("子代理启动失败: " + e.getMessage()));
            }
        })
        .doFinally(signalType -> {
            // 完成时始终释放信号量
            semaphore.release();
        });
    }

    /**
     * 核心执行：构建隔离的 AgentContext，运行完整管道，返回结果。
     */
    private Mono<SubagentResult> runSubagent(String targetAgentId, String task,
                                              Map<String, Object> options,
                                              AgentContext parentCtx,
                                              SubagentConfig parentConfig,
                                              Instant startTime) {
        String childAgentId = targetAgentId;
        String childSessionKey = parentCtx.getSessionId()
                + "/subagent/" + childAgentId + "/" + UUID.randomUUID().toString().substring(0, 8);

        // ── 3. 解析子代理配置 ──
        AgentConfig childAgentConfig = agentConfigResolver.resolve(childAgentId);
        if (childAgentConfig.getName() == null) {
            return Mono.just(SubagentResult.error("未知代理: " + childAgentId));
        }

        // ── 4. 为子代理构建隔离的 AgentContext ──
        // 子代理拥有自己的 toolRegistry 子集、会话和管道
        AgentContext childCtx = buildChildContext(childSessionKey, task, childAgentConfig, parentCtx);

        // 在运行元数据中设置子代理深度
        childCtx.getRunMetadata().setSubagentDepth(
                parentCtx.getRunMetadata().getSubagentDepth() + 1);
        childCtx.getRunMetadata().setParentSessionKey(parentCtx.getSessionId());
        childCtx.getRunMetadata().setSubagentTargetAgentId(childAgentId);

        // 在父代理的活跃子代理集合中追踪
        parentCtx.getRunMetadata().getActiveSubagentIds().add(childSessionKey);

        // ── 5. 分发 subagentSpawning 钩子 ──
        dispatchHooks("subagentSpawning", childCtx, null);

        // ── 6. 运行子代理的管道 ──
        // 为子代理构建轻量级 AgentInvocationHandler。
        // 子代理运行相同的管道阶段，但使用自己的上下文。
        AgentInvocationHandler childHandler = new AgentInvocationHandler(
                chatFacade, reActEngine, toolRegistry,
                childAgentConfig.getDescription(), // 系统提示
                childAgentConfig.getModel(),
                childAgentConfig.getProvider(),
                defaultHooks,
                defaultStages
        );

        return Mono.fromCallable(() -> {
            try {
                // 以阻塞模式执行子代理管道并收集结果
                String result = childHandler.executeBlocking(childCtx);
                Duration elapsed = Duration.between(startTime, Instant.now());

                // ── 7. 构建 SubagentResult ──
                SubagentResult subagentResult = SubagentResult.success(
                        childSessionKey, childAgentId, result, elapsed.toMillis(),
                        childCtx.getSuccessCount().get(), childCtx.getFailCount().get());

                // ── 8. 分发 subagentSpawned / subagentEnded 钩子 ──
                dispatchHooks("subagentSpawned", childCtx, subagentResult);
                dispatchHooks("subagentEnded", childCtx, subagentResult);

                return subagentResult;
            } catch (Exception e) {
                log.error("子代理 '{}' 执行失败: {}", childAgentId, e.getMessage(), e);
                Duration elapsed = Duration.between(startTime, Instant.now());
                return SubagentResult.error("子代理执行失败: " + e.getMessage());
            } finally {
                // 从活跃集合中移除
                parentCtx.getRunMetadata().getActiveSubagentIds().remove(childSessionKey);
                // 如果配置了则调度会话归档
                scheduleSessionArchive(childSessionKey, parentConfig.getArchiveAfterMinutes());
            }
        })
        .subscribeOn(Schedulers.boundedElastic())
        .timeout(Duration.ofSeconds(parentConfig.getRunTimeoutSeconds()),
                 Mono.just(SubagentResult.error(
                         "子代理在 " + parentConfig.getRunTimeoutSeconds() + " 秒后超时")),
                 Schedulers.boundedElastic());
    }

    /**
     * 为子代理构建隔离的 AgentContext。
     */
    private AgentContext buildChildContext(String sessionKey, String task,
                                            AgentConfig childConfig,
                                            AgentContext parentCtx) {
        // 子代理获取独立的 sessionId，userMessage = 任务。
        // 系统提示来自子代理的描述。
        AgentContext childCtx = AgentContext.sessionScoped(
                sessionKey,
                task,  // 用户消息 = 委派的任务
                childConfig.getDescription(),  // 来自子代理 @Agent 的系统提示
                toolRegistry,
                parentCtx.getMethod(),  // 子代理的 method 为 null/占位符
                new Object[0]
        );

        // 构建仅包含任务消息的 ChatRequest
        ChatRequest request = ChatRequest.builder()
                .sessionId(sessionKey)
                .messages(new java.util.ArrayList<>(List.of(Message.user(task))))
                .stream(true)
                .build();

        // 如果子代理配置有模型覆盖，则应用
        if (childConfig.getModel() != null && !childConfig.getModel().isEmpty()) {
            request.setModel(childConfig.getModel());
        }

        // 从父代理的工具注册表（或限定子集）设置工具
        List<ToolDefinition> tools = toolRegistry.getAllDefinitions(request);
        request.setTools(tools);
        request.setToolChoice("auto");

        childCtx.setChatRequest(request);
        childCtx.setSandboxLevel(parentCtx.getSandboxLevel());

        // 从子代理配置设置思考级别
        String thinkingLevel = childConfig.getExtension("thinking.level", null);
        if (thinkingLevel != null) {
            childCtx.getRunMetadata().setThinkingLevel(thinkingLevel);
        }

        return childCtx;
    }

    /**
     * 从父代理 AgentContext 解析 SubagentConfig。
     * 优先级：AgentConfig 扩展 > application.yml > 硬编码默认值。
     */
    private SubagentConfig resolveSubagentConfig(AgentContext ctx) {
        SubagentConfig config = SubagentConfig.defaults();

        // 从 AgentContext 属性叠加（由 AgentInvocationHandler
        // 在解析 @Agent 注解扩展后设置）
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
     * 将生命周期事件分发给所有实现了 SubagentHook 的已注册钩子。
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
                    log.warn("SubagentHook '{}' 在 {} 上抛出异常: {}",
                            hook.getClass().getSimpleName(), lifecycleEvent, e.getMessage());
                }
            }
        }
    }

    private void scheduleSessionArchive(String sessionKey, int afterMinutes) {
        // 委托给会话存储，在非活跃后归档此会话。
        // 实现方式：注册一个延迟任务，检查会话是否
        // 仍然活跃，如果不活跃则将其移至冷存储。
        log.debug("已为子代理会话 {} 安排在 {} 分钟后归档",
                sessionKey, afterMinutes);
    }

    /**
     * 返回内置 delegate_to_agent 工具的工具定义。
     * 此工具由框架自动注册。
     */
    public static ToolDefinition buildDelegateToolDefinition(SubagentConfig config) {
        // 以编程方式构建 JSON Schema
        Map<String, Object> properties = new java.util.LinkedHashMap<>();

        // agentId 参数
        Map<String, Object> agentIdSchema = new java.util.LinkedHashMap<>();
        agentIdSchema.put("type", "string");
        agentIdSchema.put("description", "要委派到的专用代理的 ID");
        properties.put("agentId", agentIdSchema);

        // task 参数
        Map<String, Object> taskSchema = new java.util.LinkedHashMap<>();
        taskSchema.put("type", "string");
        taskSchema.put("description", "子代理的详细任务描述");
        properties.put("task", taskSchema);

        // mode 参数（可选覆盖）
        Map<String, Object> modeSchema = new java.util.LinkedHashMap<>();
        modeSchema.put("type", "string");
        modeSchema.put("enum", List.of("suggest", "prefer"));
        modeSchema.put("description", "本次调用的委派模式覆盖");
        properties.put("mode", modeSchema);

        // 构建完整参数 Schema
        Map<String, Object> parameters = new java.util.LinkedHashMap<>();
        parameters.put("type", "object");
        parameters.put("properties", properties);

        // 必填字段取决于配置
        List<String> required = new java.util.ArrayList<>();
        required.add("task");
        if (config.isRequireAgentId()) {
            required.add("agentId");
        }
        parameters.put("required", required);

        // 构建函数定义
        Map<String, Object> function = new java.util.LinkedHashMap<>();
        function.put("name", "delegate_to_agent");
        function.put("description",
                config.getDelegationMode().equals("prefer")
                        ? "将任务委派给另一个专用代理。"
                          + "当另一个代理专门从事该任务时，你<b>应该</b>使用此工具。"
                        : "将任务委派给另一个专用代理。"
                          + "当另一个代理专门从事该任务时，你<b>可以</b>使用此工具。");
        function.put("parameters", parameters);

        return ToolDefinition.builder()
                .name("delegate_to_agent")
                .type("function")
                .function(function)
                .build();
    }
}
```

### 2.1.3 内置 delegate_to_agent 工具

`delegate_to_agent` 工具通过 `ToolProvider` 注册为内置工具，而非静态的 `@Tool` 注解，因为它需要在运行时访问 `AgentContext`（而静态工具无法访问）。

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
 * ToolProvider，将内置的 {@code delegate_to_agent} 工具注入到
 * 每个代理的工具集中。这是 LLM 发现子代理委派的方式。
 *
 * <p>当 LLM 调用此工具时，执行会路由到
 * {@link SubagentSpawner}，它生成一个新的代理会话，运行至
 * 完成，并将结果作为工具输出返回。</p>
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
        // 基于父代理的配置动态构建工具定义
        SubagentConfig config = SubagentConfig.defaults(); // 将在运行时从上下文解析
        return List.of(SubagentSpawner.buildDelegateToolDefinition(config));
    }

    @Override
    public ToolExecutionResult execute(ToolCall toolCall, ChatRequest request, Object context) {
        if (!"delegate_to_agent".equals(toolCall.getName())) {
            return ToolExecutionResult.error("未知工具: " + toolCall.getName());
        }

        if (!(context instanceof ToolProviderContext ctx)) {
            return ToolExecutionResult.error("缺少 ToolProviderContext");
        }

        AgentContext agentCtx = ctx.getAgentContext();

        // 解析参数
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
            return ToolExecutionResult.error("解析 delegate_to_agent 参数失败: " + e.getMessage());
        }

        String targetAgentId = (String) args.getOrDefault("agentId", "");
        String task = (String) args.get("task");
        if (task == null || task.isEmpty()) {
            return ToolExecutionResult.error("delegate_to_agent 必须提供 task 参数");
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> options = (Map<String, Object>) args.getOrDefault("options", Map.of());

        // 同步执行（阻塞），因为当前 ReAct 循环中工具执行是同步的。
        // 生成器内部使用响应式类型，但为了兼容性这里采用阻塞方式。
        try {
            SubagentResult result = spawner.spawnSubagent(targetAgentId, task, options, agentCtx)
                    .block(java.time.Duration.ofSeconds(spawner.resolveSubagentConfig(agentCtx).getRunTimeoutSeconds()));

            if (result == null) {
                return ToolExecutionResult.error("子代理返回 null（可能超时）");
            }

            String output = formatSubagentOutput(result);
            return ToolExecutionResult.success(output);
        } catch (Exception e) {
            log.error("delegate_to_agent 执行失败: {}", e.getMessage(), e);
            return ToolExecutionResult.error("子代理委派失败: " + e.getMessage());
        }
    }

    private String formatSubagentOutput(SubagentResult result) {
        StringBuilder sb = new StringBuilder();
        if (result.isSuccess()) {
            sb.append("## 子代理结果 (成功)\n\n");
            sb.append("**代理:** ").append(result.getAgentId()).append("\n");
            sb.append("**耗时:** ").append(result.getDurationMs()).append("ms\n");
            sb.append("**工具:** ").append(result.getSuccessTools())
              .append(" 成功，").append(result.getFailedTools()).append(" 失败\n\n");
            sb.append("### 输出\n\n").append(result.getOutput());
        } else {
            sb.append("## 子代理结果 (失败)\n\n");
            sb.append("**代理:** ").append(result.getAgentId()).append("\n");
            sb.append("**错误:** ").append(result.getError()).append("\n");
        }
        return sb.toString();
    }
}
```

### 2.1.4 委派流程

完整的流程，逐步说明：

```
┌──────────────────────────────────────────────────────────────────┐
│ 父代理: AgentInvocationHandler                                    │
│   阶段管道: ContextBuild → SecurityCheck → PlanExecution          │
│   → RespondStage → ReflectionStage → MetricsStage               │
│                                                                  │
│ RespondStage:                                                    │
│   ├─ ReActEngine.executeStream(chatFacade, request, toolExecutor)│
│   │                                                              │
│   │   ┌─ LLM 调用（携带包含 "delegate_to_agent" 的工具列表）     │
│   │   │                                                          │
│   │   │   LLM 决定: "我应该将这次代码审查委派给                   │
│   │   │   code-reviewer 代理。"                                  │
│   │   │                                                          │
│   │   │   → toolCall: delegate_to_agent(                         │
│   │   │       agentId="code-reviewer",                           │
│   │   │       task="审查 PR #342 中的变更...",                   │
│   │   │       mode="suggest"                                     │
│   │   │     )                                                    │
│   │   │                                                          │
│   │   ├─ ToolExecutor.execute("delegate_to_agent", ...)          │
│   │   │                                                          │
│   │   │   ┌───────────────────────────────────────────────────┐ │
│   │   │   │ SubagentSpawner.spawnSubagent()                   │ │
│   │   │   │                                                   │ │
│   │   │   │   1. 验证 allowAgents 白名单                      │ │
│   │   │   │   2. 检查 maxSpawnDepth（父深度 + 1 < 最大值）    │ │
│   │   │   │   3. 检查 maxChildrenPerAgent                     │ │
│   │   │   │   4. 获取并发信号量                                │ │
│   │   │   │   5. 解析子代理 AgentConfig                        │ │
│   │   │   │   6. 为子代理构建隔离的 AgentContext              │ │
│   │   │   │   7. 分发 subagentSpawning 钩子                    │ │
│   │   │   │   8. 运行子代理的完整管道：                          │ │
│   │   │   │      ContextBuild → SecurityCheck →                │ │
│   │   │   │      PlanExecution → Respond(ReAct) →              │ │
│   │   │   │      Reflection → Metrics                          │ │
│   │   │   │   9. 分发 subagentSpawned、subagentEnded 钩子      │ │
│   │   │   │  10. 释放信号量                                    │ │
│   │   │   │  11. 返回 SubagentResult                           │ │
│   │   │   └───────────────────────────────────────────────────┘ │
│   │   │                                                          │
│   │   ├─ 工具结果作为观察返回给父代理 LLM                         │
│   │   │                                                          │
│   │   └─ 父代理 LLM 根据子代理的结果继续                          │
│   │       并生成最终回复                                           │
│   │                                                              │
│   └─ 向客户端发送最终 SSE 事件                                     │
└──────────────────────────────────────────────────────────────────┘
```

### 2.1.5 子代理会话管理

子代理会话遵循分层会话键方案：

```
父会话键:    "abc12345"
子会话键:    "abc12345/subagent/code-reviewer/a1b2c3d4"
孙会话键:    "abc12345/subagent/code-reviewer/a1b2c3d4/subagent/tester/e5f6g7h8"
```

这使得以下功能成为可能：
- **分层追踪**：任何子代理的输出都可以追溯到根会话
- **自动归档**：当父会话被归档时，会话存储可以归档该父键下的所有会话
- **级联清理**：终止父会话可以终止所有后代子代理会话

```java
package lyjew.com.lyclaw.react.subagent;

import java.util.List;

import lyjew.com.lyclaw.model.Session;

/**
 * 子代理运行的会话管理。
 *
 * <p>每次子代理运行都会创建一个新的 {@link Session}，使用分层的
 * sessionKey（parentKey + "/subagent/" + agentId + "/" + uuid片段）。
 * 会话存储在与父代理相同的会话存储中。</p>
 */
public class SubagentSessionManager {

    private final lyjew.com.lyclaw.persistence.SessionStore sessionStore;

    public SubagentSessionManager(lyjew.com.lyclaw.persistence.SessionStore sessionStore) {
        this.sessionStore = sessionStore;
    }

    /**
     * 在给定的父会话键下创建一个新的子代理会话。
     */
    public Session createSubagentSession(String parentSessionKey, String agentId,
                                          String systemPrompt) {
        String sessionId = parentSessionKey + "/subagent/" + agentId
                + "/" + java.util.UUID.randomUUID().toString().substring(0, 8);

        Session session = Session.builder()
                .sessionId(sessionId)
                .name("subagent:" + agentId)
                .model(null)  // 稍后从 AgentConfig 解析
                .build();

        sessionStore.save(session);
        return session;
    }

    /**
     * 归档一个子代理会话及其所有后代会话。
     */
    public void archiveSession(String sessionKey, int afterMinutes) {
        // 查找所有键以 sessionKey 开头的会话
        List<Session> descendants = sessionStore.findByPrefix(sessionKey);
        for (Session s : descendants) {
            s.setAttribute("archived", "true");
            s.setAttribute("archivedAt", String.valueOf(System.currentTimeMillis()));
            sessionStore.save(s);
        }
    }

    /**
     * 终止父键下所有活跃的子代理会话。
     * 在父会话被终止或取消时调用。
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

### 2.1.6 并发控制

```java
package lyjew.com.lyclaw.react;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 附加到 AgentContext 上的运行时元数据，用于追踪子代理状态。
 *
 * <p>它存储在 AgentContext.attributes 中，键为 "runMetadata"，
 * 但为了类型安全，我们将其暴露为类型化类。</p>
 */
public class RunMetadata {

    /**
     * 此代理在子代理生成树中的深度。
     * 0 = 根代理（无父代理）。1 = 由根代理直接生成。
     * 2 = 由第 1 级子代理生成，依此类推。
     */
    private int subagentDepth = 0;

    /**
     * 如果这是一个子代理，其父代理的会话键。
     * 对于根代理为 null。
     */
    private String parentSessionKey;

    /**
     * 如果这是一个子代理，它作为其生成的 agentId。
     * 对于根代理为 null。
     */
    private String subagentTargetAgentId;

    /**
     * 此代理生成的当前活跃子代理的会话键集合。
     * 用于强制执行 maxChildrenPerAgent。
     */
    private final Set<String> activeSubagentIds = ConcurrentHashMap.newKeySet();

    /**
     * 此上下文中模型调用的思考/推理级别。
     * "off" | "low" | "medium" | "high"。null 表示使用模型默认值。
     */
    private String thinkingLevel;

    /**
     * 此上下文的模型名称覆盖（从 AgentConfig + 默认值解析）。
     */
    private String resolvedModel;

    /**
     * 此上下文的提供商名称覆盖。
     */
    private String resolvedProvider;

    /**
     * 专门为图像理解配置的模型。
     */
    private String imageModel;

    /**
     * 归档存储的会话键。
     */
    private String archiveSessionKey;


    // ── 构造函数 ──

    public RunMetadata() {}

    public static RunMetadata root() {
        return new RunMetadata();
    }

    public static RunMetadata childOf(RunMetadata parent, String childAgentId) {
        RunMetadata child = new RunMetadata();
        child.subagentDepth = parent.subagentDepth + 1;
        child.parentSessionKey = null; // 稍后由生成器设置
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

    /** 此代理是否为子代理（有父代理）。 */
    public boolean isSubagent() {
        return parentSessionKey != null || subagentDepth > 0;
    }

    /** 此代理是否为生成树的根。 */
    public boolean isRoot() {
        return subagentDepth == 0 && parentSessionKey == null;
    }
}
```

### 2.1.7 AgentContext 对子代理的增强

现有的 `AgentContext` 类需要添加一个 `RunMetadata` 字段：

```java
// ── 添加到 AgentContext 的内容 ──

/** 运行时元数据，包括子代理深度、思考级别、模型解析 */
private final RunMetadata runMetadata = new RunMetadata();

public RunMetadata getRunMetadata() { return runMetadata; }


// ── 同时添加到 AgentContext.toSnapshot() ──

public Map<String, Object> toSnapshot() {
    Map<String, Object> snapshot = new HashMap<>();
    // ... 现有的字段 ...
    snapshot.put("subagentDepth", runMetadata.getSubagentDepth());
    snapshot.put("parentSessionKey", runMetadata.getParentSessionKey());
    snapshot.put("thinkingLevel", runMetadata.getThinkingLevel());
    snapshot.put("resolvedModel", runMetadata.getResolvedModel());
    return snapshot;
}


// ── 同时添加到 AgentContext.restoreFromSnapshot() ──

public void restoreFromSnapshot(Map<String, Object> snapshot) {
    if (snapshot == null) return;
    // ... 现有的字段 ...

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

### 2.1.8 Agent 注解对子代理的增强

`@Agent` 注解的 `extensions` 已经支持键值对。我们添加用于子代理配置的知名扩展键：

```
@Agent(
    name = "chat",
    description = "通用聊天助手",
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
    @SystemMessage("你是一个协调助手...")
    String chat(@UserMessage String message);
}
```

### 2.1.9 子代理钩子系统

`AgentHook` 的一个新子接口，用于子代理生命周期事件：

```java
package lyjew.com.lyclaw.react.subagent;

import lyjew.com.lyclaw.react.AgentContext;
import lyjew.com.lyclaw.react.AgentHook;

/**
 * 用于子代理生命周期事件的扩展钩子 SPI。
 *
 * <p>任何 AgentHook 实现也可以实现此接口，以接收
 * 子代理特定的生命周期回调。这些方法在 SubagentSpawner 中
 * 适当的生命周期时间点被调用。</p>
 *
 * <p>执行上下文：这些方法在子代理生成器的 boundedElastic 调度器上执行。
 * 抛出异常会记录警告但不会中断子代理管道。</p>
 */
public interface SubagentHook extends AgentHook {

    /**
     * 在子代理管道开始执行之前调用。
     * childCtx 已经完全准备好（ChatRequest、工具、系统提示已设置）。
     * 此时修改 childCtx 将影响子代理的运行。
     *
     * @param childCtx 子代理的上下文，已完全就绪
     */
    default void subagentSpawning(AgentContext childCtx) {}

    /**
     * 在子代理管道完成并产生结果后，
     * 但在结果作为工具观察返回给父代理之前调用。
     * 可以修改结果（例如，过滤敏感信息、添加元数据）。
     *
     * @param childCtx 子代理的上下文（管道已完成）
     * @param result   子代理结果（可变的；可以通过返回新结果来替换）
     */
    default void subagentSpawned(AgentContext childCtx, SubagentResult result) {}

    /**
     * 在结果被记录后、子代理会话被归档前调用。
     * 用于清理、审计或日志记录。
     *
     * @param childCtx 子代理的上下文
     * @param result   最终的子代理结果
     */
    default void subagentEnded(AgentContext childCtx, SubagentResult result) {}
}
```

### 2.1.10 子代理错误处理与超时

```java
package lyjew.com.lyclaw.react.subagent;

import lombok.Builder;
import lombok.Data;

import java.util.List;
import java.util.ArrayList;

/**
 * 子代理委派调用的结果。
 * 作为工具观察字符串返回给父代理 LLM（通过 toString/format），
 * 但也可以被钩子和指标以编程方式使用。
 */
@Data
@Builder
public class SubagentResult {

    /** 子代理是否成功完成。 */
    private boolean success;

    /** 子代理运行的会话键。 */
    private String sessionKey;

    /** 处理委派的代理 ID。 */
    private String agentId;

    /** 子代理的最终文本输出（LLM 的最终回复）。 */
    private String output;

    /** 如果 success == false，则为错误消息。 */
    private String error;

    /** 子代理运行耗时（毫秒）。 */
    private long durationMs;

    /** 成功的工具调用次数。 */
    private int successTools;

    /** 失败的工具调用次数。 */
    private int failedTools;

    /** 如果子代理自身也调用了 delegate_to_agent，这些是其结果。 */
    @Builder.Default
    private List<SubagentResult> childResults = new ArrayList<>();

    /** 子代理的反思评分（来自 ReflectionStage），如果有的话。 */
    private Double reflectionScore;

    /** 此子代理消耗的总 token 数。 */
    private int totalTokens;


    // ── 工厂方法 ──

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
                .error("子代理在 " + timeoutSeconds + " 秒后超时")
                .durationMs(timeoutSeconds * 1000)
                .build();
    }

    public static SubagentResult rejected(String agentId, String reason) {
        return SubagentResult.builder()
                .success(false)
                .agentId(agentId)
                .error("子代理委派被拒绝: " + reason)
                .build();
    }

    /**
     * 格式化为供父代理 LLM 阅读的工具观察字符串。
     */
    public String formatAsObservation() {
        StringBuilder sb = new StringBuilder();
        sb.append("[子代理结果] ");
        sb.append("agent=").append(agentId).append(" ");
        if (success) {
            sb.append("status=成功 ");
            sb.append("durationMs=").append(durationMs).append(" ");
            sb.append("toolsSucceeded=").append(successTools).append(" ");
            sb.append("toolsFailed=").append(failedTools).append("\n");
            sb.append("输出:\n").append(output);
        } else {
            sb.append("status=失败\n");
            sb.append("错误: ").append(error);
        }
        if (reflectionScore != null) {
            sb.append("\n反思评分: ").append(String.format("%.2f", reflectionScore));
        }
        return sb.toString();
    }
}
```

### 2.1.11 配置（application.yml）

```yaml
lyclaw:
  # 全局子代理默认值
  subagent:
    enabled: true
    delegation-mode: suggest           # "suggest" 或 "prefer"
    allow-agents: "*"                  # "*" 或逗号分隔的代理 ID 列表
    max-concurrent: 1
    max-spawn-depth: 1                 # 1 = 不允许递归生成
    max-children-per-agent: 5
    archive-after-minutes: 60
    run-timeout-seconds: 300
    announce-timeout-ms: 120000
    require-agent-id: false
    model:                             # 子代理的可选模型覆盖
    thinking:                          # 子代理的可选思考级别

  agent:
    # 默认 ReAct 设置（现有）
    max-tool-rounds: 30

  # 示例：通过扩展进行按代理覆盖（在 AgentConfig 或 yml 代理配置中）
  agents:
    chat:
      name: chat
      description: "具有子代理委派功能的通用聊天助手"
      model: deepseek-v4-flash
      provider: deepseek
      extensions:
        subagent.delegation-mode: prefer
        subagent.allow-agents: "code-reviewer,tester,data-analyst"
        subagent.max-concurrent: 3
        subagent.max-spawn-depth: 2
        subagent.max-children-per-agent: 10
        subagent.require-agent-id: true
        thinking.level: high            # 第二阶段 2.2 - 思考级别
        model.image: "openai/dall-e-3"
        model.pdf: "openai/gpt-4o"

    code-reviewer:
      name: code-reviewer
      description: "专用代码审查代理"
      model: deepseek-v4-flash
      provider: deepseek
      extensions:
        subagent.max-spawn-depth: 0    # 此代理不能生成子代理
        subagent.max-concurrent: 0
        thinking.level: medium
```

---

## 2.2 模型管理增强

### 2.2.1 模型目录

一个结构化的包含所有可用模型的目录，取代当前隐式的模型发现方式。

```java
package lyjew.com.lyclaw.chat.catalog;

import java.util.List;
import java.util.Map;

/**
 * 模型目录中的一个结构化条目。
 *
 * <p>每个条目表示来自特定提供商的一个可用模型。
 * 目录在启动时从以下来源构建：
 * <ol>
 *   <li>静态配置（application.yml lyclaw.chat.models.*）</li>
 *   <li>@ChatModel 注解的 bean（自动发现）</li>
 *   <li>ProviderDiscovery 响应（如果启用则自动探测）</li>
 * </ol>
 *
 * <p>ID 是规范的引用字符串："provider/modelName"
 * 例如，"openai/gpt-4o"、"deepseek/deepseek-v4-flash"、"anthropic/claude-sonnet-4-5"。
 */
public class ModelCatalogEntry {

    // ── 身份信息 ──

    /** 完整规范引用："openai/gpt-4o" */
    private String id;

    /** 模型名称："gpt-4o" */
    private String name;

    /** 提供商名称："openai" */
    private String provider;

    /** 可选简短别名，便于使用："gpt4" */
    private String alias;

    /** 人类可读的显示名称 */
    private String displayName;

    /** 此模型的自由文本描述 */
    private String description;

    // ── 能力 ──

    /** 最大上下文窗口（tokens） */
    private int contextWindow;

    /** 发送给 API 的上下文 token 覆盖（用于那些
     *  为内部使用保留部分上下文窗口的提供商） */
    private int contextTokens;

    /** 此模型是否支持扩展推理/思考 */
    private boolean reasoning;

    /** 此模型可生成的最大输出 token 数 */
    private int maxOutputTokens;

    // ── 输入模态 ──

    /** 此模型接受的输入类型 */
    private List<ModelInputType> input;

    // ── 定价（仅供参考） ──

    /** 每 1M 输入 token 美元价格 */
    private double pricePerMillionInput;

    /** 每 1M 输出 token 美元价格 */
    private double pricePerMillionOutput;

    // ── 兼容性配置 ──

    /** 提供商特定的兼容性覆盖 */
    private ModelCompatConfig compat;

    // ── 状态 ──

    /** 此模型当前是否可用（通过健康检查验证） */
    private boolean available = true;

    /** 是否为 beta/预览模型 */
    private boolean beta;

    /** 此模型被弃用的时间（epoch 毫秒），0 = 未弃用 */
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
     * 从提供商和模型名称构建规范 ID。
     */
    public static String canonicalId(String provider, String name) {
        return provider + "/" + name;
    }
}
```

```java
package lyjew.com.lyclaw.chat.catalog;

/**
 * 模型可以接受的输入类型。
 */
public enum ModelInputType {
    /** 纯文本 */
    TEXT,

    /** 图像文件（png, jpg, gif, webp） */
    IMAGE,

    /** 音频文件（mp3, wav, ogg） */
    AUDIO,

    /** 视频文件（mp4, mov） */
    VIDEO,

    /** 文档（pdf, docx, txt） */
    DOCUMENT
}
```

```java
package lyjew.com.lyclaw.chat.catalog;

import java.util.HashMap;
import java.util.Map;

/**
 * 提供商特定的兼容性配置。
 *
 * <p>不同的提供商使用不同的字段名、头部格式
 * 和 API 约定。此配置捕获这些差异，以便
 * 模型解析服务可以构建正确的原生请求。</p>
 */
public class ModelCompatConfig {

    /** 此提供商是否需要在特定字段中使用模型名称
     *  （例如，某些提供商使用 "model"，而其他使用 "model_id"） */
    private String modelFieldName = "model";

    /** 提供商是否发送带有 "data: " 前缀的 SSE 事件 */
    private boolean sseDataPrefix = true;

    /** SSE 流是否使用 "\n\n" 作为分隔符 */
    private boolean sseDoubleNewline = true;

    /** 此提供商是否支持工具调用流式传输 */
    private boolean supportsToolCallStreaming;

    /** 思考/推理内容是在单独的字段中还是内联 */
    private String thinkingField = "reasoning_content";

    /** 在流式传输中，思考内容是与内容合并还是分离 */
    private boolean thinkingInline;

    /** 提供商特定的 HTTP 头部 */
    private final Map<String, String> headers = new HashMap<>();

    /** 要附加到 API URL 的额外查询参数 */
    private final Map<String, String> queryParams = new HashMap<>();

    /** 此提供商是否支持将系统消息作为顶级字段
     *  （OpenAI 风格），还是作为 role="system" 的消息 */
    private boolean systemMessageAsField = true;

    /** 视觉模型的最大图像大小（字节） */
    private long maxImageBytes = 20 * 1024 * 1024; // 20MB

    /** 发送前是否自动调整图像大小 */
    private boolean autoResizeImages = true;

    /** 自动调整大小的最大图像尺寸 */
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

    /** OpenAI 兼容的默认值 */
    public static ModelCompatConfig openAiDefaults() {
        ModelCompatConfig c = new ModelCompatConfig();
        c.modelFieldName = "model";
        c.sseDataPrefix = true;
        c.sseDoubleNewline = true;
        c.thinkingField = "reasoning_content";
        c.systemMessageAsField = false; // messages[0].role=system
        return c;
    }

    /** Anthropic 特定的默认值 */
    public static ModelCompatConfig anthropicDefaults() {
        ModelCompatConfig c = new ModelCompatConfig();
        c.modelFieldName = "model";
        c.sseDataPrefix = true;
        c.sseDoubleNewline = true;
        c.supportsToolCallStreaming = false;
        c.thinkingField = "thinking";
        c.thinkingInline = false;
        c.systemMessageAsField = true; // 顶级 system 字段
        return c;
    }
}
```

### 2.2.2 AgentDefaultsConfig 中的多模型支持

我们引入新的 `AgentDefaultsConfig` 来取代单一模型的假设：

```java
package lyjew.com.lyclaw.chat.config;

/**
 * 按模态划分的模型选择的按代理或全局默认配置。
 *
 * <p>这将单一的 "model" 概念替换为模态特定的模型。
 * 每个字段可以是规范 ID（"openai/gpt-4o"）或别名（"gpt-4o"）。
 * 设为 null 的字段从 application.yml 中的全局默认值继承。</p>
 */
public class AgentModelConfig {

    /** 主要的聊天/文本生成模型 */
    private String chatModel;

    /** 用于图像理解（视觉）的模型 */
    private String imageModel;

    /** 用于图像生成的模型（DALL-E 等） */
    private String imageGenerationModel;

    /** 用于视频生成的模型（Sora 等） */
    private String videoGenerationModel;

    /** 用于音乐/声音生成的模型 */
    private String musicGenerationModel;

    /** 用于 PDF 阅读和理解的模型 */
    private String pdfModel;

    // ── PDF 限制 ──

    /** 最大 PDF 文件大小（MB） */
    private int pdfMaxBytesMb = 10;

    /** PDF 最大阅读页数 */
    private int pdfMaxPages = 20;

    // ── 生成设置 ──

    /** 主要图像生成模型失败时自动回退到另一个提供商 */
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
     * 解析有效的聊天模型，回退到全局默认值。
     */
    public String resolveChatModel(String globalDefault) {
        return chatModel != null ? chatModel : globalDefault;
    }
}
```

### 2.2.3 模型选择与解析

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
 * 用于解析给定代理 + 会话应使用哪个模型的中央服务。
 *
 * <h3>解析顺序</h3>
 * <ol>
 *   <li>检查 AgentContext.runMetadata 中的覆盖（由子代理生成器设置）</li>
 *   <li>检查 AgentConfig.model / AgentConfig.provider（来自注解/yml）</li>
 *   <li>检查代理扩展：thinking.level、model.image、model.pdf 等</li>
 *   <li>回退到全局默认值（ChatProperties.defaultProvider/defaultModel）</li>
 *   <li>如果没有配置，回退到 FirstAvailableRouter</li>
 * </ol>
 *
 * <h3>别名解析</h3>
 * <p>别名是短名称，如 "gpt-4o"，解析为 "openai/gpt-4o"。
 * 别名映射从 ModelCatalogEntry.alias 字段填充。</p>
 */
public class ModelResolutionService {

    private static final Logger log = LoggerFactory.getLogger(ModelResolutionService.class);

    private final ChatModelRegistry registry;
    private final ModelCatalog modelCatalog;
    private final Map<String, String> aliasMap = new ConcurrentHashMap<>();

    /** 默认回退链（按优先级排序的规范 ID 列表） */
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
     * 解析此代理上下文的主体聊天模型的有效 (provider, model) 对。
     */
    public ModelRef resolveEffectiveModel(AgentContext ctx) {
        RunMetadata meta = ctx.getRunMetadata();

        // 1. 来自 runMetadata 的覆盖
        if (meta.getResolvedModel() != null && meta.getResolvedProvider() != null) {
            return new ModelRef(meta.getResolvedProvider(), meta.getResolvedModel());
        }

        // 2. 来自 ChatRequest（由 AgentInvocationHandler 从 @Agent 注解设置）
        ChatRequest request = ctx.getChatRequest();
        if (request != null && request.getModel() != null && !request.getModel().isEmpty()) {
            // model 字段可能是规范 ID "deepseek/deepseek-v4-flash"
            // 或者只是一个模型名称，配合请求的隐式提供商
            ModelRef ref = parseModelRef(request.getModel());
            if (ref != null) return ref;
        }

        // 3. 来自 AgentConfig 扩展（由 AgentConfigResolver 设置）
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

        // 4. 回退到第一个可用模型
        return resolveFirstAvailable();
    }

    /**
     * 解析用于图像理解（视觉）的模型。
     */
    public ModelRef resolveImageModel(AgentContext ctx) {
        @SuppressWarnings("unchecked")
        Map<String, String> extensions = ctx.getAttribute("agentExtensions");
        if (extensions != null && extensions.containsKey("model.image")) {
            return parseModelRef(extensions.get("model.image"));
        }
        // 回退到主要模型（大多数现代模型都支持视觉）
        return resolveEffectiveModel(ctx);
    }

    /**
     * 解析此上下文的有效回退链。
     * 操作员覆盖 > 代理配置 > 全局默认值。
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
     * 将别名解析为其规范 ID。
     * 例如，"gpt-4o" → "openai/gpt-4o"
     */
    public String resolveAlias(String alias) {
        if (alias == null) return null;
        if (alias.contains("/")) return alias; // 已经是规范 ID
        return aliasMap.getOrDefault(alias, alias);
    }

    /**
     * 自动回退探测：测试模型是否适用于给定的会话。
     * 如果需要回退则返回探测配置，如果主要模型可用则返回 null。
     */
    public AutoFallbackProbe resolveAutoFallbackProbe(String sessionKey,
                                                        String primaryProvider,
                                                        String primaryModel) {
        // 检查模型是否最近在健康检查中失败
        if (!modelCatalog.isAvailable(primaryProvider, primaryModel)) {
            // 查找第一个可用的回退
            for (String fallbackId : defaultFallbackChain) {
                ModelRef ref = parseModelRef(fallbackId);
                if (ref != null && modelCatalog.isAvailable(ref.provider, ref.model)) {
                    return new AutoFallbackProbe(sessionKey, primaryProvider, primaryModel,
                            ref.provider, ref.model, "primary_unavailable");
                }
            }
        }
        return null; // 主要模型可用，不需要回退
    }

    /**
     * 解析模型引用字符串。
     * 接受："provider/model"、"model"（提供商从上下文推导）或别名。
     */
    public ModelRef parseModelRef(String ref) {
        if (ref == null || ref.isEmpty()) return null;

        // 首先尝试别名
        String resolved = resolveAlias(ref);

        int slash = resolved.indexOf('/');
        if (slash > 0) {
            return new ModelRef(resolved.substring(0, slash), resolved.substring(slash + 1));
        }
        // 未指定提供商：使用默认提供商
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
        throw new IllegalStateException("没有可用的 AI 模型。请至少配置一个提供商。");
    }

    private void buildAliasMap() {
        for (ModelCatalogEntry entry : modelCatalog.getAll()) {
            if (entry.getAlias() != null && !entry.getAlias().isEmpty()) {
                aliasMap.put(entry.getAlias(), entry.getId());
            }
            // 在无歧义时也将仅名称注册为别名
            aliasMap.putIfAbsent(entry.getProvider() + "/" + entry.getName(), entry.getId());
        }
    }

    // ── 内部类型 ──

    /**
     * 一个解析后的 (provider, model) 对。
     */
    public record ModelRef(String provider, String model) {
        public String canonicalId() {
            return provider + "/" + model;
        }
    }

    /**
     * 关于自动回退探测的信息。
     * 当主要模型不可用时，这告诉系统
     * 应改用哪个回退模型。
     */
    public record AutoFallbackProbe(String sessionKey,
                                     String primaryProvider, String primaryModel,
                                     String fallbackProvider, String fallbackModel,
                                     String reason) {}
}
```

### 2.2.4 思考/推理/详细程度控制

```java
package lyjew.com.lyclaw.chat.config;

/**
 * 思考/推理级别，控制在产生输出之前模型"思考"的程度。
 * 映射到提供商特定的 API 参数。
 *
 * <h3>级别</h3>
 * <ul>
 *   <li><b>OFF</b> — 思考/推理已禁用。最快，成本最低。</li>
 *   <li><b>LOW</b> — 简短推理。适合简单的工具使用任务。</li>
 *   <li><b>MEDIUM</b> — 中等推理。在大多数任务上保持平衡。</li>
 *   <li><b>HIGH</b> — 广泛推理。适用于复杂的多步骤问题。</li>
 *   <li><b>MAX</b> — 最大推理预算。最高质量，最高成本/延迟。</li>
 * </ul>
 *
 * <h3>提供商映射</h3>
 * <ul>
 *   <li>DeepSeek: "thinking" 参数，带 "enabled" + "thinking_budget"</li>
 *   <li>OpenAI o-series: "reasoning_effort": low/medium/high</li>
 *   <li>Anthropic: "thinking" 块，带 "budget_tokens"</li>
 *   <li>Gemini: "thinking_config"，带 "thinking_level"</li>
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

    /** 从字符串解析（不区分大小写）："off"、"low"、"medium"、"high"、"max" */
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

    /** 转换为 DeepSeek API 的 thinking 参数值 */
    public String toDeepSeekThinking() {
        if (this == OFF) return null; // 省略 thinking 块
        return "enabled";
    }

    /** 转换为 DeepSeek 的 thinking_budget token 数 */
    public int toDeepSeekBudget() {
        return defaultBudgetTokens;
    }

    /** 转换为 OpenAI 的 reasoning_effort */
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

思考级别在管道开始时被解析并注入到 `ChatRequest` 中：

```java
// ── 在 AgentInvocationHandler.invoke() 中，阶段执行之前 ──

// 从注解/yml 解析思考级别
String thinkingStr = resolveThinkingLevel(method, args);
ctx.getRunMetadata().setThinkingLevel(thinkingStr);

// 应用到 ChatRequest
ThinkingLevel level = ThinkingLevel.fromString(thinkingStr);
if (level != ThinkingLevel.OFF) {
    request.setThinkingEnabled(true);
    request.setThinkingBudget(level.getDefaultBudgetTokens());
}
```

### 2.2.5 提供商发现

```java
package lyjew.com.lyclaw.chat.catalog;

import java.util.List;

import reactor.core.publisher.Mono;

/**
 * 用于从提供商的 API 自动发现可用模型的 SPI。
 *
 * <p>支持 /models 端点的提供商（OpenAI、DeepSeek 等）
 * 实现此接口以在启动时填充 ModelCatalog。这取代了
 * 硬编码的模型列表，并支持动态模型可用性追踪。</p>
 */
public interface ProviderDiscovery {

    /**
     * 从提供商的 API 发现所有可用模型。
     *
     * @param provider 提供商名称（例如，"openai"）
     * @param apiKey 用于认证的 API 密钥
     * @return 返回一个 Mono，在完成时包含已发现模型条目的列表
     */
    Mono<List<ModelCatalogEntry>> discoverModels(String provider, String apiKey);

    /**
     * 验证特定模型是否可用并可响应。
     * 通常发送一个最小的请求（例如，1 token 的补全）来验证。
     *
     * @param provider 提供商名称
     * @param model 模型名称
     * @param apiKey API 密钥
     * @return 如果模型响应成功则返回 true
     */
    Mono<Boolean> validateModel(String provider, String model, String apiKey);

    /**
     * 从 /models/{model} 端点获取提供商支持的功能
     * （流式传输、工具调用、思考等）。
     */
    Mono<ModelCompatConfig> probeCapabilities(String provider, String model, String apiKey);

    /**
     * 返回此发现实现是否支持给定的提供商。
     */
    boolean supportsProvider(String provider);
}
```

一个针对 OpenAI 兼容 API 的默认实现：

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
 * 通过 /v1/models 端点实现的 OpenAI 兼容提供商发现。
 *
 * <p>适用于 OpenAI、DeepSeek、Groq 以及任何实现
 * OpenAI /v1/models API 的提供商。如果端点不可用或
 * 返回非标准响应，则优雅地回退。</p>
 */
public class OpenAICompatibleProviderDiscovery implements ProviderDiscovery {

    private static final ObjectMapper mapper = new ObjectMapper();

    private final HttpClient httpClient;

    public OpenAICompatibleProviderDiscovery() {
        this.httpClient = HttpClient.create();
    }

    @Override
    public boolean supportsProvider(String provider) {
        // 所有使用 openai-protocol 的提供商均受支持
        return true;  // ChatProperties 确定实际协议
    }

    @Override
    public Mono<List<ModelCatalogEntry>> discoverModels(String provider, String apiKey) {
        // 使用 ChatProperties 查找提供商的 baseUrl
        ChatProperties.ModelProperties props = /* 从 ChatProperties 解析 */ null;

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
        // 发送一个 max_tokens=1 的最小聊天补全请求
        return Mono.just(true);  // 简化版；真实实现会进行测试调用
    }

    @Override
    public Mono<ModelCompatConfig> probeCapabilities(String provider, String model, String apiKey) {
        return Mono.just(ModelCompatConfig.openAiDefaults());
    }
}
```

### 2.2.6 模型回退链集成

来自 `ModelResolutionService` 的回退链被集成到现有的 `FallbackChatModel` 装饰器中：

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
 * 增强的回退模型，使用 ModelResolutionService 动态
 * 解析回退候选项，而不是使用静态硬编码列表。
 *
 * <p>与现有的 FallbackChatModel 装饰器模式集成，但
 * 添加了模型目录感知的解析。</p>
 */
public class DynamicFallbackChatModel implements ChatModel {

    private static final Logger log = LoggerFactory.getLogger(DynamicFallbackChatModel.class);

    private final ChatModel primary;
    private final ModelResolutionService resolutionService;
    private final ChatModelRegistry registry;

    /** 回退链，以规范 ID 列表形式，null 表示使用解析服务 */
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
                    log.warn("主要模型 {}/{} 失败: {}。尝试回退...",
                            primary.provider(), primary.model(), error.getMessage());

                    // 按顺序尝试每个回退
                    return tryFallbacks(request, 0);
                });
    }

    private Flux<ModelResponse> tryFallbacks(ChatRequest request, int attemptIndex) {
        List<String> chain = staticFallbackChain != null
                ? staticFallbackChain
                : List.of(); // 将使用动态解析

        if (attemptIndex >= chain.size() && staticFallbackChain != null) {
            return Flux.error(new RuntimeException(
                    "所有回退模型已耗尽，对于 " + primary.provider() + "/" + primary.model()));
        }

        String fallbackId = staticFallbackChain != null
                ? chain.get(attemptIndex)
                : null;

        if (fallbackId == null) {
            // 动态回退解析 - 查找任何可用的模型
            ModelRef ref = resolutionService.parseModelRef(
                    primary.provider() + "/" + primary.model());
            if (ref == null) {
                return Flux.error(new RuntimeException("没有可用的回退模型"));
            }
            fallbackId = ref.canonicalId();
        }

        ModelRef ref = resolutionService.parseModelRef(fallbackId);
        if (ref == null) {
            return Flux.error(new RuntimeException("无效的回退 ID: " + fallbackId));
        }

        ChatModel fallback = registry.resolve(ref.provider(), ref.model());
        if (fallback == null) {
            return tryFallbacks(request, attemptIndex + 1);
        }

        log.info("正在回退到 {}/{}（第 {} 次尝试）", ref.provider(), ref.model(), attemptIndex + 1);

        return fallback.stream(request)
                .onErrorResume(err -> {
                    log.warn("回退模型 {}/{} 同样失败: {}",
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

### 2.2.7 思考相关的 SSE 事件

`DefaultReActEngine` 已经通过 `ModelResponse.getThinking()` 处理了思考内容。我们通过结构化的 SSE 事件来增强这一点：

```java
// ── 添加到 DefaultReActEngine 的内容 ──

/**
 * 用于思考/推理流式传输的 SSE 事件类型。
 *
 * <p>启用思考后，流式传输期间发出的事件：
 * <ul>
 *   <li>{@code thinking_start} — 模型开始思考时发出一次
 *       （在产生任何内容之前）</li>
 *   <li>{@code thinking_delta} — 每个思考 token/块 发出一次</li>
 *   <li>{@code thinking_end} — 模型停止思考并
 *       开始产生内容时发出</li>
 * </ul>
 */
private static final String SSE_THINKING_START = "thinking_start";
private static final String SSE_THINKING_DELTA = "thinking_delta";
private static final String SSE_THINKING_END = "thinking_end";

// 在流式 handle() 回调中，检测思考内容与正文内容：

// ...在 .handle((chunk, sink) -> { ... 内部

if (chunk.getThinking() != null && !chunk.getThinking().isEmpty()) {
    // 发出思考事件而不是消息事件
    if (!thinkingStarted.get()) {
        thinkingStarted.set(true);
        sink.next(sseEvent(SSE_THINKING_START, ""));
    }
    sink.next(sseEvent(SSE_THINKING_DELTA, chunk.getThinking()));
    return;
}

if (thinkingStarted.get() && chunk.getContent() != null) {
    // 转换：思考 → 正文内容
    thinkingStarted.set(false);
    sink.next(sseEvent(SSE_THINKING_END, ""));
}
```

### 2.2.8 ChatRequest 与 ChatModel 增强

**ChatRequest 新增的用于多模型支持的字段：**

```java
// ── ChatRequest 中的新字段 ──

/** 思考/推理级别（off/low/medium/high/max） */
private String thinkingLevel;

/** 覆盖用于图像理解的模型（与主要文本模型分离） */
private String imageModel;

/** 覆盖用于 PDF 阅读的模型 */
private String pdfModel;

/** 当为 true 时，如果主要模型失败，媒体生成请求将自动回退到
 *  替代提供商 */
@Builder.Default
private boolean mediaGenerationAutoFallback = true;
```

**ChatModel 新增的思考支持方法：**

```java
// ── ChatModel 接口上的新方法 ──

/**
 * 此模型是否支持特定级别的思考/推理。
 * 不支持思考的模型将静默忽略该参数。
 */
default boolean supportsThinkingLevel(ThinkingLevel level) {
    return capabilities().isThinking();
}

/**
 * 此模型是否支持图像输入（视觉）。
 */
default boolean supportsVision() {
    return capabilities().isVision();
}
```

**ModelCapabilities 增强：**

```java
// ── ModelCapabilities 中的新字段 ──

/** 此模型是否支持图像生成 */
private boolean imageGeneration;

/** 此模型是否支持视频生成 */
private boolean videoGeneration;

/** 此模型是否支持音乐生成 */
private boolean musicGeneration;

/** 此模型是否支持 PDF 阅读 */
private boolean pdfReading;

/** 支持的最大思考努力级别 */
private ThinkingLevel maxThinkingLevel = ThinkingLevel.OFF;

// ... 包含 getters/setters 和 builder 方法 ...
```

### 2.2.9 配置（application.yml）

```yaml
lyclaw:
  chat:
    default-provider: deepseek
    default-model: deepseek-v4-flash

    # 全局模型目录（从注解 + 此配置填充）
    catalog:
      # 从提供商 API 自动发现模型
      auto-discover: true
      # 缓存已发现模型的分钟数
      discovery-cache-minutes: 60

      # 静态目录条目（不自动发现，始终可用）
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
          price-million-output: 40.00  # 每张图片

    # 全局回退链（按优先级排序的规范 ID）
    fallback-chain:
      - deepseek/deepseek-v4-flash
      - openai/gpt-5.0-flash
      - openai/gpt-4.1

    # 按提供商的模型配置（现有配置，已增强）
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

    # 全局思考默认值
    thinking:
      default-level: medium     # off | low | medium | high | max
      fallback-level: low       # 当主要模型不支持思考时使用

  # 代理级别的模型覆盖（通过 AgentConfig）
  agent:
    default-mode: react
    max-tool-rounds: 30

  # 子代理默认值（为清晰起见重复列出）
  subagent:
    enabled: true
    max-concurrent: 1
    max-spawn-depth: 1
    archive-after-minutes: 60
```

---

## 3. 集成点汇总

### 3.1 SubagentSpawner 在以下各点集成到现有系统中：

| 集成点 | 描述 |
|---|---|
| **ToolRegistry / ToolProvider** | `DelegateToAgentToolProvider` 将 `delegate_to_agent` 注册为内置工具。它是一个 `ToolProvider`（而非静态的 `@Tool`），使其能够访问 `AgentContext` 以生成子代理。 |
| **AgentInvocationHandler** | 从 `@Agent` 注解扩展中解析 `SubagentConfig`，并将其注入到 `AgentContext.runMetadata` 中。`AgentConfig` 中现有的 `agentExtensions` 映射已经支持此模式。 |
| **AgentContext** | 获得一个 `RunMetadata` 字段，包含 `subagentDepth`、`parentSessionKey`、`activeSubagentIds`、`thinkingLevel`。在上下文构建期间设置并在整个管道中携带。 |
| **ReActEngine / DefaultReActEngine** | 无需 API 更改。`delegate_to_agent` 工具在工具列表中作为常规工具出现。当 LLM 调用它时，`ToolExecutor.execute()` 路由到 `DelegateToAgentToolProvider`，后者在 `SubagentSpawner.spawnSubagent()` 上阻塞。 |
| **RespondStage** | 无需更改。对 `ToolRegistry` 的 `registerToolProvider()` 调用（或 `getAllDefinitions()` 覆盖）将委派工具注入到每个管道调用中。 |
| **管道阶段** | 所有阶段（`ContextBuildStage`、`SecurityCheckStage`、`PlanExecutionStage`、`RespondStage`、`ReflectionStage`、`MetricsStage`）对子代理的运行完全相同。唯一的区别是子代理具有嵌套的 `sessionKey` 和 `subagentDepth > 0`。 |
| **AgentRegistry** | 由 `SubagentSpawner` 用于查找子代理配置。现有的 `lookup()`、`findByCapability()`、`findAvailable()` 方法支持此功能。 |
| **AgentConfigResolver** | 用于解析子代理的 `model`、`provider`、`systemPrompt` 和 `extensions`（包括子代理限制）。现有的多源解析（注解 > yml > 数据库）适用。 |
| **SessionStore** | 由 `SubagentSessionManager` 用于分层的会话键存储和归档。 |
| **AgentHook** | 通过 `SubagentHook` 子接口扩展，用于子代理生命周期回调（`subagentSpawning`、`subagentSpawned`、`subagentEnded`）。 |

### 3.2 模型管理在以下各点集成：

| 集成点 | 描述 |
|---|---|
| **ChatFacade / DefaultChatFacade** | 获得 `ModelResolutionService` 依赖。`route()` 委托给它进行智能模型选择。`resolveModel()` 使用目录进行别名解析。 |
| **ChatModelRegistry** | 在启动时从 `ModelCatalog` 条目填充。目录条目来自静态 YAML 配置 + `@ChatModel` 注解 + `ProviderDiscovery` 自动探测。 |
| **ChatModel** 接口 | 获得 `supportsThinkingLevel()`、`supportsVision()` 默认方法。现有实现无需更改。 |
| **ChatRequest** | 获得 `thinkingLevel`、`imageModel`、`pdfModel`、`mediaGenerationAutoFallback` 字段。 |
| **ModelCapabilities** | 获得 `imageGeneration`、`videoGeneration`、`musicGeneration`、`pdfReading`、`maxThinkingLevel` 字段。 |
| **AgentContext.runMetadata** | 获得 `thinkingLevel`、`resolvedModel`、`resolvedProvider` 字段，用于每次调用的模型解析。 |
| **AgentInvocationHandler** | 从 `@Agent` 注解解析 `thinkingLevel`，在调用前设置 `ChatRequest.thinkingLevel` 和 `thinkingBudget`。 |
| **DefaultReActEngine** | 在启用思考的情况下，流式传输期间发出 `thinking_start`、`thinking_delta`、`thinking_end` SSE 事件。 |
| **AbstractChatModel** | 子类可以读取 `ChatRequest.thinkingLevel` 并将其映射到提供商特定的 API 参数（例如，DeepSeek 的 "thinking" 块、OpenAI 的 "reasoning_effort"）。 |
| **ProviderDiscovery** | 新的 SPI。`OpenAICompatibleProviderDiscovery` 是默认实现。在启动时自动填充 `ModelCatalog`。 |
| **FirstAvailableRouter** | 被 `ModelResolutionService.resolveFirstAvailable()` 替代用于默认路由，但作为回退保留。 |

---

## 4. 迁移路径

### 4.1 阶段 2a：模型管理（非破坏性）

1. **添加 `ModelCatalogEntry`、`ModelCompatConfig`、`ModelInputType`** — 新类，不涉及现有代码更改。
2. **添加 `ThinkingLevel` 枚举** — 新类。
3. **扩展 `ModelCapabilities`** — 仅附加字段，默认值为 false/0（向后兼容）。
4. **向 `ChatRequest` 添加 `thinkingLevel`** — 新字段，默认为 null（向后兼容）。
5. **添加 `ModelResolutionService`** — 新类，尚未替换任何内容。
6. **添加 `ProviderDiscovery` SPI + `OpenAICompatibleProviderDiscovery`** — 新的，不更改现有代码。
7. **添加 `AgentModelConfig`** — 用于模态特定模型解析的新类。
8. **扩展 `@Agent` 注解扩展** — 无需代码更改，只需在 `@Extension` 值中记录新的扩展键。

### 4.2 阶段 2b：子代理系统（附加，初始禁用）

1. **添加 `SubagentConfig`、`SubagentSpawner`、`SubagentSessionManager`** — 新类。
2. **添加 `RunMetadata`** — 新类。向 `AgentContext` 添加 `runMetadata` 字段（非破坏性，该字段以默认值开始）。
3. **添加 `SubagentResult`、`SubagentHook`** — 新类。
4. **添加 `DelegateToAgentToolProvider`** — 新类。通过自动配置有条件地注册（默认禁用，直到 `lyclaw.subagent.enabled=true`）。
5. **扩展 `AgentHook`** — 添加 `SubagentHook` 子接口（非破坏性，现有钩子忽略新的回调）。

### 4.3 阶段 2c：集成（功能开关控制）

1. **将 `SubagentSpawner` 接入自动配置** — 仅当 `lyclaw.subagent.enabled=true`。
2. **将 `DelegateToAgentToolProvider` 接入 `ToolRegistry`** — 通过 `ToolProvider` SPI。
3. **将 `ModelResolutionService` 接入 `DefaultChatFacade`** — 用 `resolutionService.resolveEffectiveModel()` 替换直接的 `router.route()` 调用，但保留 `FirstAvailableRouter` 作为回退。
4. **向 `DefaultReActEngine` 添加思考 SSE 事件** — 向后兼容（新事件类型，现有客户端忽略未知事件）。
5. **通过 `@Agent` 扩展键为特定代理启用子代理** — 按代理选择性加入。

### 4.4 回滚策略

- 所有新类位于独立的包中（`lyclaw.react.subagent`、`lyclaw.chat.catalog`、`lyclaw.chat.config`），便于删除。
- application.yml 中的功能开关控制所有新行为：
  - `lyclaw.subagent.enabled=false` 完全禁用委派
  - `lyclaw.chat.catalog.auto-discover=false` 禁用提供商发现
  - 思考级别默认为 OFF（行为无变化）
- 现有的 `FirstAvailableRouter` 在未配置模型目录时继续作为默认值工作。

---

## 附录：文件清单

第二阶段创建的所有新文件：

```
lyclaw-framework/src/main/java/lyjew/com/lyclaw/
├── react/
│   ├── subagent/
│   │   ├── SubagentConfig.java          (新)
│   │   ├── SubagentSpawner.java         (新)
│   │   ├── SubagentResult.java          (新)
│   │   ├── SubagentHook.java            (新)
│   │   ├── SubagentSessionManager.java  (新)
│   │   ├── DelegateToAgentToolProvider.java (新)
│   │   └── ToolProviderContext.java     (新)
│   └── RunMetadata.java                 (新)
├── chat/
│   ├── catalog/
│   │   ├── ModelCatalogEntry.java       (新)
│   │   ├── ModelInputType.java          (新)
│   │   ├── ModelCompatConfig.java       (新)
│   │   ├── ModelCatalog.java            (新，接口)
│   │   ├── InMemoryModelCatalog.java    (新)
│   │   ├── ProviderDiscovery.java       (新，SPI)
│   │   └── OpenAICompatibleProviderDiscovery.java (新)
│   ├── config/
│   │   ├── AgentModelConfig.java        (新)
│   │   ├── ThinkingLevel.java           (新)
│   │   └── ModelResolutionService.java  (新)
│   └── DynamicFallbackChatModel.java    (新)

修改的现有文件：
├── react/
│   └── AgentContext.java                （添加 runMetadata 字段、toSnapshot/restore）
├── model/
│   └── ChatRequest.java                 （添加 thinkingLevel、imageModel、pdfModel）
├── chat/
│   ├── ChatModel.java                   （添加 supportsThinkingLevel、supportsVision）
│   ├── ModelCapabilities.java           （添加 imageGeneration、videoGeneration 等）
│   └── DefaultChatFacade.java           （集成 ModelResolutionService）
```
