# 第三阶段：上下文引擎与压缩 + 工作区引导 + 代理路由与身份

> **状态：** 草案
> **目标：** LyClaw Framework — lyclaw-framework、lyclaw-autoconfigure、lyclaw-web
> **前置阶段：** 第二阶段（反思与评估）
> **后续阶段：** 第四阶段（最终集成与打磨）
>
> LyClaw 目前没有压缩机制、没有上下文修剪、没有工作区引导文件、
> 没有代理路由，也没有身份系统。本阶段将填补所有这些空白。

---

## 目录

1. [架构概览](#架构概览)
2. [3.1 上下文引擎与压缩](#31-上下文引擎与压缩)
   - [3.1.1 CompactionConfig](#311-compactionconfig)
   - [3.1.2 CompactionEngine](#312-compactionengine)
   - [3.1.3 上下文修剪](#313-上下文修剪)
   - [3.1.4 AgentContextLimits](#314-agentcontextlimits)
   - [3.1.5 管道集成](#315-管道集成)
   - [3.1.6 YAML 配置](#316-yaml-配置)
3. [3.2 工作区引导](#32-工作区引导)
   - [3.2.1 引导文件结构](#321-引导文件结构)
   - [3.2.2 BootstrapConfig](#322-bootstrapconfig)
   - [3.2.3 BootstrapLoader](#323-bootstraploader)
   - [3.2.4 ContextInjectionPolicy](#324-contextinjectionpolicy)
   - [3.2.5 管道集成](#325-管道集成)
   - [3.2.6 YAML 配置](#326-yaml-配置)
4. [3.3 代理路由与绑定](#33-代理路由与绑定)
   - [3.3.1 AgentBindingMatch](#331-agentbindingmatch)
   - [3.3.2 AgentRouteBinding 与 AgentAcpBinding](#332-agentroutebinding-与-agentacpbinding)
   - [3.3.3 AgentRouter](#333-agentrouter)
   - [3.3.4 ChatController 更新](#334-chatcontroller-更新)
   - [3.3.5 YAML 配置](#335-yaml-配置)
5. [3.4 身份与头像](#34-身份与头像)
   - [3.4.1 IdentityConfig](#341-identityconfig)
   - [3.4.2 AvatarResolution](#342-avatarresolution)
   - [3.4.3 集成与 YAML](#343-集成与-yaml)
6. [完整 YAML 配置参考](#完整-yaml-配置参考)
7. [集成检查清单](#集成检查清单)

---

## 架构概览

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
              ┌─── 管道阶段 ───────────────────────────┐
              │                                                │
              │  ContextBuildStage                             │
              │    ├─ BootstrapLoader.loadBootstrap()          │
              │    ├─ IdentityConfig 注入                      │
              │    └─ SystemPromptBuilder.build()              │
              │                                                │
              │  SecurityCheckStage                             │
              │                                                │
              │  PlanExecutionStage                             │
              │                                                │
              │  RespondStage  (ReAct 循环)                    │
              │    ├─ CompactionEngine.midTurnPrecheck()       │
              │    └─ 强制执行 AgentContextLimits              │
              │                                                │
              │  ReflectionStage                                │
              │                                                │
              │  CompactionStage         ★ 新增 ★               │
              │    ├─ needsCompaction() 检查                   │
              │    ├─ memoryFlush (之前)                      │
              │    ├─ compact() 执行                         │
              │    ├─ validateCompaction() 质量把关            │
              │    └─ 注入 postCompactionSections             │
              │                                                │
              │  MetricsStage                                   │
              │                                                │
              │  ContextPruningScheduler  ★ 新增 ★              │
              │    (后台，周期性，CACHE_TTL)                   │
              └────────────────────────────────────────────────┘
```

---

## 3.1 上下文引擎与压缩

### 动机

长时间运行的代理会话会积累大量对话历史记录（工具输出、
多轮推理、内联文件内容）。如果没有压缩机制，LLM 上下文
窗口会被填满，API 成本急剧上升，并且由于早期关键指令被挤出上下文窗口，
代理的表現会退化。

CompactionEngine 通过以下方式解决此问题：
1. 检测上下文压力是否过高（`maxActiveTranscriptBytes`）。
2. 将"中间"历史记录总结为紧凑的表示形式，同时保留
   最近的对话轮次和会话启动指令。
3. 通过质量把关（LLM 重新检查）验证压缩结果。
4. 可选地在压缩之前刷新记忆，以便关键事实在跨越压缩边界
   时得以持久保留。

### 3.1.1 CompactionConfig

```java
package lyjew.com.lyclaw.compaction;

import java.util.List;

/**
 * 压缩引擎的运行时配置 POJO。
 *
 * <p>控制何时以及如何压缩会话对话记录，以防止
 * 长时间运行的代理会话出现上下文窗口溢出。</p>
 *
 * <p>通过 {@code lyclaw.compaction} YAML 前缀绑定，由
 * {@link CompactionProperties} 提供 Spring Boot 配置绑定，
 * 本类作为框架内部传递的不可变配置快照。</p>
 *
 * <p>字段默认值即框架硬编码默认值，Spring Boot 通过 setter
 * 覆盖 YAML 中显式配置的字段。</p>
 */
public class CompactionConfig {

    /** 压缩策略模式。 */
    CompactionMode mode = CompactionMode.DEFAULT;

    /**
     * 在上下文窗口顶部为此数量的 token 保留空间，
     * 用于系统提示、引导内容和工具定义。
     * 默认值：8000（按每 token 4 字符计，约 32KB）。
     */
    int reserveTokens = 8000;

    /**
     * 保留最近 N 个 token 的对话历史不被压缩。
     * 默认值：4000（约 16KB）。
     */
    int keepRecentTokens = 4000;

    /**
     * 硬性下限：即使 reserveTokens 计算结果建议进行更深的裁剪，
     * 也不会压缩到低于此剩余 token 数。
     * 默认值：2000。
     */
    int reserveTokensFloor = 2000;

    /**
     * 历史记录（非系统消息）可占用的 token 预算的最大份额。
     * 当历史记录超过此份额时，触发压缩。
     * 默认值：0.5（50%）。
     */
    double maxHistoryShare = 0.5;

    /** 注入到压缩 LLM 提示中的自定义指令。 */
    String customInstructions;

    /**
     * 保持原样保留的最近助手/用户对话轮次数。
     * 这些是紧邻当前用户消息之前的轮次。
     * 默认值：3。
     */
    int recentTurnsPreserve = 3;

    /**
     * 压缩期间如何处理标识符（文件路径、URL、函数名）
     * 的策略。
     * STRICT：标识符必须精确保留。
     * OFF：无特殊处理。
     * CUSTOM：使用 identifierInstructions 进行指导。
     */
    IdentifierPolicy identifierPolicy = IdentifierPolicy.STRICT;

    /** 标识符保留的自定义指令（仅 CUSTOM 模式）。 */
    String identifierInstructions;

    /** 质量把关配置。 */
    QualityGuard qualityGuard = new QualityGuard();

    /** 中途预检查配置。 */
    MidTurnPrecheck midTurnPrecheck = new MidTurnPrecheck();

    /** 压缩后是否同步或异步重新索引记忆。 */
    PostIndexSync postIndexSync = PostIndexSync.ASYNC;

    /** 记忆刷新配置（在压缩之前运行）。 */
    MemoryFlush memoryFlush = new MemoryFlush();

    /**
     * 压缩完成后注入到系统提示中的压缩后章节。
     * 典型值："Session Startup"、"Red Lines"。
     * 这些内容在上下文转移后重新锚定代理的行为。
     */
    List<String> postCompactionSections = List.of("Session Startup", "Red Lines");

    /**
     * 为压缩 LLM 调用覆盖使用的模型。为 null 时使用会话模型。
     * 推荐使用更便宜/更快的模型（如 "deepseek-v4-flash"）。
     */
    String model;

    /** 单次压缩 LLM 调用的超时时间。默认值：900 秒。 */
    int timeoutSeconds = 900;

    /**
     * 如果为 true，则在压缩后截断尾部内容，
     * 而不是将其与摘要一起保留。默认值：false。
     */
    boolean truncateAfterCompaction = false;

    /**
     * 触发压缩的最大活跃对话记录字节数。
     * 默认值：10 MB（10 * 1024 * 1024）。
     */
    long maxActiveTranscriptBytes = 10 * 1024 * 1024;

    /**
     * 如果为 true，则发送 SSE 事件通知用户压缩已发生。
     * 默认值：false（静默）。
     */
    boolean notifyUser = false;

    // ── getters / setters（供 Spring Boot 配置绑定） ──────────
    public CompactionMode getMode() { return mode; }
    public void setMode(CompactionMode mode) { this.mode = mode; }
    public int getReserveTokens() { return reserveTokens; }
    public void setReserveTokens(int reserveTokens) { this.reserveTokens = reserveTokens; }
    public int getKeepRecentTokens() { return keepRecentTokens; }
    public void setKeepRecentTokens(int keepRecentTokens) { this.keepRecentTokens = keepRecentTokens; }
    public int getReserveTokensFloor() { return reserveTokensFloor; }
    public void setReserveTokensFloor(int reserveTokensFloor) { this.reserveTokensFloor = reserveTokensFloor; }
    public double getMaxHistoryShare() { return maxHistoryShare; }
    public void setMaxHistoryShare(double maxHistoryShare) { this.maxHistoryShare = maxHistoryShare; }
    public String getCustomInstructions() { return customInstructions; }
    public void setCustomInstructions(String customInstructions) { this.customInstructions = customInstructions; }
    public int getRecentTurnsPreserve() { return recentTurnsPreserve; }
    public void setRecentTurnsPreserve(int recentTurnsPreserve) { this.recentTurnsPreserve = recentTurnsPreserve; }
    public IdentifierPolicy getIdentifierPolicy() { return identifierPolicy; }
    public void setIdentifierPolicy(IdentifierPolicy identifierPolicy) { this.identifierPolicy = identifierPolicy; }
    public String getIdentifierInstructions() { return identifierInstructions; }
    public void setIdentifierInstructions(String identifierInstructions) { this.identifierInstructions = identifierInstructions; }
    public QualityGuard getQualityGuard() { return qualityGuard; }
    public void setQualityGuard(QualityGuard qualityGuard) { this.qualityGuard = qualityGuard; }
    public MidTurnPrecheck getMidTurnPrecheck() { return midTurnPrecheck; }
    public void setMidTurnPrecheck(MidTurnPrecheck midTurnPrecheck) { this.midTurnPrecheck = midTurnPrecheck; }
    public PostIndexSync getPostIndexSync() { return postIndexSync; }
    public void setPostIndexSync(PostIndexSync postIndexSync) { this.postIndexSync = postIndexSync; }
    public MemoryFlush getMemoryFlush() { return memoryFlush; }
    public void setMemoryFlush(MemoryFlush memoryFlush) { this.memoryFlush = memoryFlush; }
    public List<String> getPostCompactionSections() { return postCompactionSections; }
    public void setPostCompactionSections(List<String> postCompactionSections) { this.postCompactionSections = postCompactionSections; }
    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }
    public int getTimeoutSeconds() { return timeoutSeconds; }
    public void setTimeoutSeconds(int timeoutSeconds) { this.timeoutSeconds = timeoutSeconds; }
    public boolean isTruncateAfterCompaction() { return truncateAfterCompaction; }
    public void setTruncateAfterCompaction(boolean truncateAfterCompaction) { this.truncateAfterCompaction = truncateAfterCompaction; }
    public long getMaxActiveTranscriptBytes() { return maxActiveTranscriptBytes; }
    public void setMaxActiveTranscriptBytes(long maxActiveTranscriptBytes) { this.maxActiveTranscriptBytes = maxActiveTranscriptBytes; }
    public boolean isNotifyUser() { return notifyUser; }
    public void setNotifyUser(boolean notifyUser) { this.notifyUser = notifyUser; }
}
```

#### 支持的枚举和子配置

```java
package lyjew.com.lyclaw.compaction;

public enum CompactionMode {
    /** 标准压缩：总结中间历史记录，保留两端。 */
    DEFAULT,
    /**
     * 压缩前进行扩展安全检查。使用第二次 LLM 调用
     * 验证关键指令是否在摘要中得到保留。
     * 比默认模式慢，但对高风险会话更安全。
     */
    SAFEGUARD
}

public enum IdentifierPolicy {
    /** 标识符必须精确保留。 */
    STRICT,
    /** 无特殊标识符处理。 */
    OFF,
    /** 使用 identifierInstructions 进行指导。 */
    CUSTOM
}

public enum PostIndexSync {
    /** 压缩后不重新索引记忆。 */
    OFF,
    /** 触发异步重新索引；压缩立即返回。 */
    ASYNC,
    /** 等待重新索引完成后再返回。 */
    AWAIT
}
```

```java
package lyjew.com.lyclaw.compaction;

/** 质量把关：通过第二次 LLM 调用进行压缩后验证。 */
public class QualityGuard {
    /** 启用质量把关。默认值：true。 */
    boolean enabled = true;
    /**
     * 验证失败时的最大重试次数。
     * 每次重试以更严格的指令重新运行压缩。
     * 默认值：2。
     */
    int maxRetries = 2;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public int getMaxRetries() { return maxRetries; }
    public void setMaxRetries(int maxRetries) { this.maxRetries = maxRetries; }
}

/** 中途预检查：在长时间工具循环期间，检查是否需要压缩。 */
public class MidTurnPrecheck {
    /** 启用中途预检查。默认值：true。 */
    boolean enabled = true;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
}

/**
 * 记忆刷新：在压缩丢弃原始文本之前，从即将被压缩的区域
 * 提取关键事实并将其持久化到 MemorySystem。
 */
public class MemoryFlush {
    /** 启用压缩前的记忆刷新。默认值：true。 */
    boolean enabled = true;
    /** 用于记忆提取的模型。为 null 时使用压缩模型。 */
    String model;
    /**
     * 软阈值（以 token 计）：如果待压缩区域低于此值，
     * 跳过刷新以节省成本。默认值：4000。
     */
    int softThresholdTokens = 4000;
    /**
     * 如果对话记录字节数超过此值，则无论 softThresholdTokens
     * 的值如何，强制执行记忆刷新。默认值：500KB。
     */
    long forceFlushTranscriptBytes = 500 * 1024;
    /** 记忆提取的提示覆盖。 */
    String prompt;
    /** 记忆提取的系统提示覆盖。 */
    String systemPrompt;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }
    public int getSoftThresholdTokens() { return softThresholdTokens; }
    public void setSoftThresholdTokens(int softThresholdTokens) { this.softThresholdTokens = softThresholdTokens; }
    public long getForceFlushTranscriptBytes() { return forceFlushTranscriptBytes; }
    public void setForceFlushTranscriptBytes(long forceFlushTranscriptBytes) { this.forceFlushTranscriptBytes = forceFlushTranscriptBytes; }
    public String getPrompt() { return prompt; }
    public void setPrompt(String prompt) { this.prompt = prompt; }
    public String getSystemPrompt() { return systemPrompt; }
    public void setSystemPrompt(String systemPrompt) { this.systemPrompt = systemPrompt; }
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
 * CompactionEngine 负责检测上下文窗口压力
 * 并压缩会话对话记录，使代理保持在预算范围内。
 *
 * <h3>压缩的生命周期</h3>
 * <ol>
 *   <li>{@link #needsCompaction} — 对照限制检查对话记录大小</li>
 *   <li>记忆刷新（如果启用）— 从中间区域提取事实</li>
 *   <li>压缩前钩子 — 分派到 {@link AgentHook}</li>
 *   <li>{@link #compact} — LLM 对中间历史记录进行总结</li>
 *   <li>{@link #validateCompaction} — 质量把关（SAFEGUARD 模式）</li>
 *   <li>压缩后章节注入 — 重新锚定指令</li>
 *   <li>压缩后钩子 — 分派到 {@link AgentHook}</li>
 * </ol>
 *
 * <p>该引擎原地操作 Session.messages 列表：它用包含压缩摘要的
 * 合成系统消息替换被总结的中间轮次，同时保留最近的轮次和
 * 任何会话启动系统消息。</p>
 */
public class CompactionEngine {

    private final ChatFacade chatFacade;
    private final CompactionConfig config;
    private final MemorySystem memorySystem; // 可空，仅 memoryFlush 启用时需要

    public CompactionEngine(ChatFacade chatFacade, CompactionConfig config,
                            MemorySystem memorySystem) {
        this.chatFacade = chatFacade;
        this.config = config;
        this.memorySystem = memorySystem;
    }

    /**
     * 检查会话对话记录是否超过配置的限制，
     * 是否需要压缩。
     *
     * @param session 当前会话
     * @return 如果需要压缩则返回 true
     */
    public boolean needsCompaction(Session session) {
        long transcriptBytes = estimateTranscriptBytes(session);
        if (transcriptBytes >= config.getMaxActiveTranscriptBytes()) {
            return true;
        }
        int totalTokens = estimateTokenCount(session);
        int systemTokens = estimateSystemTokens(session);
        int historyTokens = totalTokens - systemTokens;
        double share = (double) historyTokens / (double) totalTokens;
        return share > config.getMaxHistoryShare()
                || historyTokens > (totalTokens - config.getReserveTokensFloor());
    }

    /**
     * 对会话执行压缩。
     *
     * <p>如果 memoryFlush 已启用且对话记录超过阈值，
     * 则在开始总结之前，从中间区域提取事实并
     * 持久化到 MemorySystem。</p>
     *
     * @param session 要压缩的会话
     * @param ctx     当前代理上下文（用于钩子分派、追踪、模型访问）
     * @return 压缩结果
     */
    public Mono<CompactionResult> compact(Session session, AgentContext ctx) {
        return Mono.fromCallable(() -> {
            MessagePartition partition = partitionMessages(
                    session.getMessages());

            // 可选的记忆刷新
            if (config.getMemoryFlush().isEnabled() && memorySystem != null) {
                long middleBytes = estimateBytes(partition.middle());
                if (middleBytes >= config.getMemoryFlush().getForceFlushTranscriptBytes()
                        || estimateTokenCount(partition.middle())
                           >= config.getMemoryFlush().getSoftThresholdTokens()) {
                    flushMemory(partition.middle(), ctx);
                }
            }

            String summary = callCompactionLLM(partition, ctx);
            reconstructMessages(session, partition, summary);

            return new CompactionResult(
                    partition.headCount(), partition.middleCount(),
                    partition.tailCount(), summary.length(),
                    estimateTokenCount(session));
        });
    }

    /**
     * 验证压缩没有丢失关键信息。
     * 在 SAFEGUARD 模式下或 qualityGuard 启用时使用。
     */
    public Mono<Boolean> validateCompaction(CompactionResult result) {
        if (!config.getQualityGuard().isEnabled()) {
            return Mono.just(true);
        }
        // 将压缩前后的摘要与检查清单一起发送给 LLM
        // ...
        return Mono.just(true);
    }

    /**
     * 中途预检查：在长时间工具调用循环期间调用，
     * 检查上下文窗口是否处于压力之下。
     */
    public Mono<Boolean> midTurnPrecheck(AgentContext ctx) {
        if (!config.getMidTurnPrecheck().isEnabled()) {
            return Mono.just(false);
        }
        // 从工具结果和历史记录估算当前对话记录大小
        // ...
        return Mono.just(false);
    }

    // ── 内部辅助方法 ───────────────────────────────────────────

    private long estimateTranscriptBytes(Session session) {
        return session.getMessages().stream()
                .mapToLong(m -> (m.getContent() != null ? m.getContent().length() : 0)
                        + (m.getThinking() != null ? m.getThinking().length() : 0))
                .sum();
    }

    private int estimateTokenCount(Session session) {
        // 粗略估计：每 token 4 个字符
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
     * 将消息列表分为三个区域：
     * - 头部：系统消息和早期会话设置
     * - 中间：历史记录的主体（待总结）
     * - 尾部：最近的 `recentTurnsPreserve` 个轮次
     */
    private MessagePartition partitionMessages(List<Message> messages) {
        // 遍历消息列表，标识系统前缀、尾部轮次、中间部分
        // 使用 this.config 的 recentTurnsPreserve / postCompactionSections
        // ...
        return new MessagePartition(List.of(), List.of(), List.of(), 0, 0, 0);
    }

    private void flushMemory(List<Message> middle, AgentContext ctx) {
        // 通过 LLM 从中间消息中提取事实，持久化到 MemorySystem
        // ...
    }

    private String callCompactionLLM(MessagePartition partition, AgentContext ctx) {
        // 构建压缩提示，调用 LLM，返回摘要字符串
        // ...
        return "";
    }

    private void reconstructMessages(Session session, MessagePartition partition,
                                     String summary) {
        // 用包含摘要的合成系统消息替换中间消息
        // ...
    }

    /** 单次压缩运行的结果。 */
    public record CompactionResult(
            int headMessages, int middleMessages, int tailMessages,
            int summaryChars, int finalTokenEstimate) {}

    private record MessagePartition(
            List<Message> head, List<Message> middle, List<Message> tail,
            int headCount, int middleCount, int tailCount) {}
}
```

### 3.1.3 上下文修剪

上下文修剪是比压缩更轻量级的机制。它不使用 LLM 进行总结，
而是修剪或替换过时的工具结果以释放上下文空间。
它在 `mode=CACHE_TTL` 时通过后台调度器运行。

```java
package lyjew.com.lyclaw.compaction;

import java.time.Duration;
import java.util.Set;

/**
 * 上下文修剪的配置 — 在不使用 LLM 总结的情况下，
 * 轻量级地修剪会话对话记录中的过时工具结果。
 *
 * <p>映射自 application.yml 中的 {@code lyclaw.compaction.pruning}。</p>
 */
public class ContextPruningConfig {

    public enum PruningMode {
        OFF,
        /** 缓存 TTL 模式：超过 ttl 的工具结果可根据年龄和大小进行软修剪或硬清除。 */
        CACHE_TTL
    }

    /** 修剪模式。默认值：OFF。 */
    PruningMode mode = PruningMode.OFF;

    /** 工具结果内容的生存时间。默认值：30 分钟。 */
    Duration ttl = Duration.ofMinutes(30);

    /** 保留最近 N 条助手消息不被修剪。默认值：5。 */
    int keepLastAssistants = 5;

    /** 应用软修剪的上下文比例阈值。默认值：0.3（30%）。 */
    double softTrimRatio = 0.3;

    /** 触发硬清除的上下文比例阈值。默认值：0.6（60%）。 */
    double hardClearRatio = 0.6;

    /** 工具结果可被修剪的最小字符数。默认值：1000。 */
    int minPrunableToolChars = 1000;

    /** 允许列表：可以被修剪的工具名称。为空时所有工具都符合条件。 */
    Set<String> toolAllow;

    /** 拒绝列表：不能被修剪的工具名称（如 file_read、file_search）。 */
    Set<String> toolDeny;

    /** 软修剪配置。 */
    SoftTrim softTrim = new SoftTrim();

    /** 硬清除配置。 */
    HardClear hardClear = new HardClear();

    // ── getters / setters ──────────────────────────
    public PruningMode getMode() { return mode; }
    public void setMode(PruningMode mode) { this.mode = mode; }
    public Duration getTtl() { return ttl; }
    public void setTtl(Duration ttl) { this.ttl = ttl; }
    public int getKeepLastAssistants() { return keepLastAssistants; }
    public void setKeepLastAssistants(int keepLastAssistants) { this.keepLastAssistants = keepLastAssistants; }
    public double getSoftTrimRatio() { return softTrimRatio; }
    public void setSoftTrimRatio(double softTrimRatio) { this.softTrimRatio = softTrimRatio; }
    public double getHardClearRatio() { return hardClearRatio; }
    public void setHardClearRatio(double hardClearRatio) { this.hardClearRatio = hardClearRatio; }
    public int getMinPrunableToolChars() { return minPrunableToolChars; }
    public void setMinPrunableToolChars(int minPrunableToolChars) { this.minPrunableToolChars = minPrunableToolChars; }
    public Set<String> getToolAllow() { return toolAllow; }
    public void setToolAllow(Set<String> toolAllow) { this.toolAllow = toolAllow; }
    public Set<String> getToolDeny() { return toolDeny; }
    public void setToolDeny(Set<String> toolDeny) { this.toolDeny = toolDeny; }
    public SoftTrim getSoftTrim() { return softTrim; }
    public void setSoftTrim(SoftTrim softTrim) { this.softTrim = softTrim; }
    public HardClear getHardClear() { return hardClear; }
    public void setHardClear(HardClear hardClear) { this.hardClear = hardClear; }

    /** 软修剪：保留头部和尾部各 N 个字符，中间用 "..." 替换。 */
    public static class SoftTrim {
        int maxChars = 8000;
        int headChars = 2000;
        int tailChars = 2000;

        public int getMaxChars() { return maxChars; }
        public void setMaxChars(int maxChars) { this.maxChars = maxChars; }
        public int getHeadChars() { return headChars; }
        public void setHeadChars(int headChars) { this.headChars = headChars; }
        public int getTailChars() { return tailChars; }
        public void setTailChars(int tailChars) { this.tailChars = tailChars; }
    }

    /** 硬清除：用占位符消息替换整个工具结果。 */
    public static class HardClear {
        boolean enabled = true;
        String placeholder = "[earlier output trimmed for space]";

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public String getPlaceholder() { return placeholder; }
        public void setPlaceholder(String placeholder) { this.placeholder = placeholder; }
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
 * ContextPruner 对过时的工具结果消息应用轻量级修剪。
 *
 * <p>与 CompactionEngine（使用 LLM 总结）不同，ContextPruner
 * 使用简单规则：CACHE_TTL 模式检查每个工具结果的年龄，
 * 并根据配置的比例应用软修剪（头部+尾部截断）或
 * 硬清除（占位符替换）。</p>
 */
public class ContextPruner {

    private static final Logger log = LoggerFactory.getLogger(ContextPruner.class);

    private final ContextPruningConfig config;

    public ContextPruner(ContextPruningConfig config) {
        this.config = config;
    }

    /**
     * 原地修剪会话的消息，移除或修剪过时的工具结果。
     *
     * @param session 要修剪的会话
     * @param now     当前时间参考
     * @return 修改的消息数量
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

        // 反向遍历消息以跟踪助手位置
        for (int i = session.getMessages().size() - 1; i >= 0; i--) {
            Message msg = session.getMessages().get(i);

            if ("assistant".equals(msg.getRole())) {
                assistantCount++;
                if (assistantCount <= keepAssistant) {
                    continue; // 保留最近的助手及其工具结果
                }
            }

            if (!"tool".equals(msg.getRole())) {
                continue;
            }

            // 检查每个工具的允许/拒绝列表
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
                // 此工具结果已过时
                if (content.length() > config.getSoftTrim().getMaxChars() * config.getSoftTrimRatio()) {
                    // 软修剪
                    msg.setContent(softTrim(content));
                    modified++;
                }
                // TODO: 根据总比例进行硬清除
            }
        }

        log.debug("ContextPruner：在会话 {} 中修改了 {} 条消息",
                modified, session.getSessionId());
        return modified;
    }

    private String softTrim(String content) {
        var st = config.getSoftTrim();
        if (content.length() <= st.getMaxChars()) {
            return content;
        }
        return content.substring(0, st.getHeadChars())
                + "\n... [已修剪 " + (content.length() - st.getHeadChars() - st.getTailChars())
                + " 个字符] ...\n"
                + content.substring(content.length() - st.getTailChars());
    }
}
```

### 3.1.4 AgentContextLimits

在上下文构建期间强制执行的硬性限制，防止单个组件
消耗不成比例的上下文空间。

```java
package lyjew.com.lyclaw.compaction;

/**
 * 各个上下文组件的硬性限制。
 *
 * <p>这些限制在上下文构建时强制执行，在上下文到达 LLM 之前生效。
 * 它们通过提供静态上限来补充动态的 CompactionEngine。</p>
 *
 * <p>映射自 application.yml 中的 {@code lyclaw.compaction.limits}。</p>
 */
public class AgentContextLimits {

    /** 每次检索调用从 MemorySystem 返回的最大字符数。默认值：12000。 */
    int memoryGetMaxChars = 12000;

    /** 检索的默认记忆行数。默认值：120。 */
    int memoryGetDefaultLines = 120;

    /** 对话记录中任何单个工具结果的最大字符数。默认值：16000。 */
    int toolResultMaxChars = 16000;

    /** 压缩后注入章节内容的最大字符数。默认值：1800。 */
    int postCompactionMaxChars = 1800;

    // ── getters / setters ──────────────────────────
    public int getMemoryGetMaxChars() { return memoryGetMaxChars; }
    public void setMemoryGetMaxChars(int memoryGetMaxChars) { this.memoryGetMaxChars = memoryGetMaxChars; }
    public int getMemoryGetDefaultLines() { return memoryGetDefaultLines; }
    public void setMemoryGetDefaultLines(int memoryGetDefaultLines) { this.memoryGetDefaultLines = memoryGetDefaultLines; }
    public int getToolResultMaxChars() { return toolResultMaxChars; }
    public void setToolResultMaxChars(int toolResultMaxChars) { this.toolResultMaxChars = toolResultMaxChars; }
    public int getPostCompactionMaxChars() { return postCompactionMaxChars; }
    public void setPostCompactionMaxChars(int postCompactionMaxChars) { this.postCompactionMaxChars = postCompactionMaxChars; }

    /**
     * 将工具结果截断到 toolResultMaxChars。
     *
     * @param content 原始工具输出
     * @return 截断后的内容，如果发生截断则附加说明
     */
    public String truncateToolResult(String content) {
        if (content == null || content.length() <= toolResultMaxChars) {
            return content;
        }
        return content.substring(0, toolResultMaxChars)
                + "\n... [已截断 " + (content.length() - toolResultMaxChars)
                + " 个字符；原始总共有 " + content.length() + " 个字符]";
    }

    /**
     * 将压缩后章节截断到 postCompactionMaxChars。
     */
    public String truncatePostCompactionSection(String content) {
        if (content == null || content.length() <= postCompactionMaxChars) {
            return content;
        }
        return content.substring(0, postCompactionMaxChars) + "...";
    }
}
```

### 3.1.5 管道集成

#### CompactionStage

一个新的管道阶段，位于 ReflectionStage 之后、MetricsStage 之前。

```java
package lyjew.com.lyclaw.compaction;

import lyjew.com.lyclaw.annotation.PipelineStage;
import lyjew.com.lyclaw.pipeline.ReactivePipelineStage;
import lyjew.com.lyclaw.pipeline.stage.PipelineStageBase;
import lyjew.com.lyclaw.pipeline.stage.ReflectionStage;
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
 * 管道阶段，检查上下文窗口压力并在需要时触发压缩。
 *
 * <p>排在 ReflectionStage 之后执行（压缩前可以保留反思评估数据）。
 * 通过 {@code after} 声明拓扑顺序。</p>
 */
@PipelineStage(
    name = "Compaction",
    after = ReflectionStage.class,
    group = "POSTPROCESSING"
)
public class CompactionStage extends PipelineStageBase {

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
            if (!compactionEngine.needsCompaction(session)) {
                return Flux.empty();
            }

            log.info("会话 {} 触发压缩", session.getSessionId());

            // 分派压缩前钩子（AgentHook 已有 beforeCompaction 方法）
            hooks.forEach(h -> h.beforeCompaction(ctx));

            return compactionEngine.compact(session, ctx)
                    .flatMapMany(result -> {
                        return compactionEngine.validateCompaction(result)
                                .flatMapMany(valid -> {
                                    if (!valid) {
                                        log.warn("会话 {} 的压缩验证失败",
                                                session.getSessionId());
                                    }

                                    injectPostCompactionSections(ctx, session);

                                    // 分派压缩后钩子（AgentHook 已有 afterCompaction 方法）
                                    hooks.forEach(h -> h.afterCompaction(ctx));

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
                    .doOnError(e -> log.error("会话 {} 压缩失败",
                            session.getSessionId(), e))
                    .onErrorResume(e -> Flux.empty());
        });
    }

    private void injectPostCompactionSections(AgentContext ctx, Session session) {
        // 将配置的压缩后章节作为系统消息注入
        // ...
    }

    @Override
    public int getOrder() { return 500; }

    @Override
    public String getStageName() { return "Compaction"; }
}
```

#### 钩子集成

`AgentHook` 在 Phase 2 已包含 `beforeCompaction(AgentContext ctx)` 和
`afterCompaction(AgentContext ctx)` 两个压缩生命周期方法，
`CompactionStage` 直接调用即可，无需额外扩展。压缩结果通过
`AgentContext.attributes` 传递（key: `"compactionResult"`），
避免修改接口签名。
```

#### 中途压缩触发

在 `DefaultReActEngine` 中，在工具执行轮次之间检查上下文压力：

```java
// 在 DefaultReActEngine.continueReActRounds() 中：每轮工具后检查中途上下文压力
if (compactionEngine != null) {
    Boolean needsMidTurn = compactionEngine.midTurnPrecheck(ctx).block();
    if (Boolean.TRUE.equals(needsMidTurn)) {
        log.warn("需要中途压缩；暂停 ReAct 循环");
        // 发出暂停事件，压缩，然后恢复
        // ...
    }
}
```

#### ContextPruningScheduler

一个后台调度器，定期运行 ContextPruner：

```java
package lyjew.com.lyclaw.compaction;

import lyjew.com.lyclaw.model.Session;
import lyjew.com.lyclaw.react.subagent.SubagentSessionManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

import java.time.Instant;

/**
 * 后台调度器，当上下文修剪模式为 CACHE_TTL 时，
 * 定期修剪过时的工具结果。
 */
public class ContextPruningScheduler {

    private static final Logger log = LoggerFactory.getLogger(ContextPruningScheduler.class);

    private final ContextPruner pruner;
    private final ContextPruningConfig config;
    private final SubagentSessionManager sessionManager;

    public ContextPruningScheduler(ContextPruner pruner, ContextPruningConfig config,
                                   SubagentSessionManager sessionManager) {
        this.pruner = pruner;
        this.config = config;
        this.sessionManager = sessionManager;
    }

    /**
     * 每 5 分钟运行一次修剪。仅在 mode != OFF 时活跃。
     */
    @Scheduled(fixedRate = 300_000)
    public void pruneActiveSessions() {
        if (config.getMode() == ContextPruningConfig.PruningMode.OFF) {
            return;
        }

        Instant now = Instant.now();
        var sessions = sessionManager.getActiveSessions();
        int totalModified = 0;

        for (Session session : sessions.values()) {
            try {
                int modified = pruner.prune(session, now);
                totalModified += modified;
            } catch (Exception e) {
                log.warn("会话 {} 修剪失败：{}",
                        session.getSessionId(), e.getMessage());
            }
        }

        if (totalModified > 0) {
            log.info("ContextPruningScheduler：在 {} 个会话中修改了 {} 条消息",
                    totalModified, sessions.size());
        }
    }
}
```

### 3.1.6 YAML 配置

```yaml
lyclaw:
  # ── 压缩 ──────────────────────────────────────────
  compaction:
    # 完全启用/禁用压缩引擎
    enabled: true

    # 压缩模式：DEFAULT | SAFEGUARD
    mode: DEFAULT

    # Token 预留
    reserve-tokens: 8000
    keep-recent-tokens: 4000
    reserve-tokens-floor: 2000

    # 当历史记录超过此总 token 份额时触发
    max-history-share: 0.5

    # 压缩提示的自定义 LLM 指令
    custom-instructions: ""

    # 保持原样保留最近 N 个对话轮次
    recent-turns-preserve: 3

    # 标识符处理：STRICT | OFF | CUSTOM
    identifier-policy: STRICT

    # 压缩使用的模型覆盖（null = 使用会话模型）
    model: deepseek-v4-flash

    # 单次压缩 LLM 调用的超时时间（秒）
    timeout-seconds: 900

    # 压缩后截断尾部内容
    truncate-after-compaction: false

    # 触发压缩的最大活跃对话记录字节数
    max-active-transcript-bytes: 10485760  # 10MB

    # 压缩运行时通过 SSE 通知用户
    notify-user: false

    # ── 质量把关 ───────────────────────────────────
    quality-guard:
      enabled: true
      max-retries: 2

    # ── 中途预检查 ───────────────────────────────
    mid-turn-precheck:
      enabled: true

    # ── 压缩后索引同步 ──────────────────────
    # OFF | ASYNC | AWAIT
    post-index-sync: ASYNC

    # ── 记忆刷新（压缩前） ────────────────
    memory-flush:
      enabled: true
      # model: deepseek-v4-flash  # null = 使用压缩模型
      soft-threshold-tokens: 4000
      force-flush-transcript-bytes: 512000  # 500KB
      # prompt: ""
      # system-prompt: ""

    # 压缩后需要重新注入的章节
    post-compaction-sections:
      - "Session Startup"
      - "Red Lines"

    # ── 上下文修剪 ─────────────────────────────────
    pruning:
      # 修剪模式：OFF | CACHE_TTL
      mode: OFF

      # 工具结果的 TTL（ISO 8601 持续时间）
      ttl: PT30M

      # 保留最后 N 条助手消息不被修剪
      keep-last-assistants: 5

      # 软修剪比例（相对于上下文预算）
      soft-trim-ratio: 0.3

      # 硬清除比例
      hard-clear-ratio: 0.6

      # 工具结果可被修剪的最小字符数
      min-prunable-tool-chars: 1000

      # 工具允许/拒绝列表
      tool-allow: []
      tool-deny:
        - file_read
        - file_search

      # 软修剪参数
      soft-trim:
        max-chars: 8000
        head-chars: 2000
        tail-chars: 2000

      # 硬清除参数
      hard-clear:
        enabled: true
        placeholder: "[earlier output trimmed for space]"

    # ── 上下文限制 ──────────────────────────────────
    limits:
      memory-get-max-chars: 12000
      memory-get-default-lines: 120
      tool-result-max-chars: 16000
      post-compaction-max-chars: 1800
```

---

## 3.2 工作区引导

### 动机

目前 LyClaw 没有代理专用的引导文件。每个会话都以最小化的
系统提示开始。有了引导文件，每个代理都可以拥有丰富的、持久化的
身份：系统提示补充（AGENTS.md）、个性（SOUL.md）、一次性设置
（BOOTSTRAP.md）、身份描述（IDENTITY.md）、用户偏好（USER.md）
和心跳提示（HEARTBEAT.md）。

### 3.2.1 引导文件结构

```
{agentDir}/
  AGENTS.md      — 系统提示补充（始终注入）
  SOUL.md        — 代理个性、价值观、语气指南
  BOOTSTRAP.md   — 一次性设置指令（运行一次后跳过）
  IDENTITY.md    — 代理身份描述（名称、角色、背景）
  USER.md        — 用户上下文、偏好、自定义指令
  HEARTBEAT.md   — 心跳提示章节（周期性自检）
```

**文件语义：**

| 文件          | 注入方式       | 描述 |
|---------------|----------------|-------------|
| `AGENTS.md`   | 每轮都注入      | 核心系统提示增强。工具说明、安全规则、输出格式。不可跳过。 |
| `SOUL.md`     | 每轮都注入      | 个性与价值观。定义代理的"声音" — 语气、详细程度、风格偏好。 |
| `BOOTSTRAP.md`| 仅一次（在首次 `/new` 或 `/reset` 时） | 一次性初始化指令。仅在会话开始时执行。 |
| `IDENTITY.md` | 每轮都注入      | 代理是谁。名称、角色、背景故事。在 UI 中显示。 |
| `USER.md`     | 每轮都注入      | 用户特定上下文。偏好、自定义指令、关于用户的已知事实。 |
| `HEARTBEAT.md`| 每 N 分钟      | 周期性自检提示。鼓励代理反思目标进展。 |

### 3.2.2 BootstrapConfig

```java
package lyjew.com.lyclaw.bootstrap;

import java.util.List;

/**
 * 工作区引导系统的配置。
 *
 * <p>控制加载哪些引导文件、如何注入它们，
 * 以及防止上下文窗口溢出的大小限制。</p>
 *
 * <p>映射自 application.yml 中的 {@code lyclaw.bootstrap}。</p>
 */
public class BootstrapConfig {

    /** 完全跳过所有引导加载。默认值：false。 */
    boolean skipBootstrap = false;

    /** 需要跳过的可选引导文件列表（如 ["SOUL.md", "HEARTBEAT.md"]）。 */
    List<String> skipOptionalBootstrapFiles;

    /** 何时将引导内容注入上下文。默认值：ALWAYS。 */
    ContextInjectionPolicy contextInjection = ContextInjectionPolicy.ALWAYS;

    /** 每个引导文件的最大字符数。默认值：20000。 */
    int bootstrapMaxChars = 20000;

    /** 所有引导文件的总最大字符数。默认值：150000。 */
    int bootstrapTotalMaxChars = 150000;

    /** 截断警告策略：ONCE / ALWAYS / NEVER。 */
    BootstrapTruncationWarning truncationWarning = BootstrapTruncationWarning.ONCE;

    /** 启动上下文配置。 */
    StartupContextConfig startupContext = new StartupContextConfig();

    /** 代理目录路径。null 时默认 {@code ${user.dir}/agents/{agentId}}。 */
    String agentDir;

    /** 工作区目录路径。null 时默认 {@code ${user.dir}}。 */
    String workspaceDir;

    // ── getters / setters ──────────────────────────
    public boolean isSkipBootstrap() { return skipBootstrap; }
    public void setSkipBootstrap(boolean skipBootstrap) { this.skipBootstrap = skipBootstrap; }
    public List<String> getSkipOptionalBootstrapFiles() { return skipOptionalBootstrapFiles; }
    public void setSkipOptionalBootstrapFiles(List<String> skipOptionalBootstrapFiles) { this.skipOptionalBootstrapFiles = skipOptionalBootstrapFiles; }
    public ContextInjectionPolicy getContextInjection() { return contextInjection; }
    public void setContextInjection(ContextInjectionPolicy contextInjection) { this.contextInjection = contextInjection; }
    public int getBootstrapMaxChars() { return bootstrapMaxChars; }
    public void setBootstrapMaxChars(int bootstrapMaxChars) { this.bootstrapMaxChars = bootstrapMaxChars; }
    public int getBootstrapTotalMaxChars() { return bootstrapTotalMaxChars; }
    public void setBootstrapTotalMaxChars(int bootstrapTotalMaxChars) { this.bootstrapTotalMaxChars = bootstrapTotalMaxChars; }
    public BootstrapTruncationWarning getTruncationWarning() { return truncationWarning; }
    public void setTruncationWarning(BootstrapTruncationWarning truncationWarning) { this.truncationWarning = truncationWarning; }
    public StartupContextConfig getStartupContext() { return startupContext; }
    public void setStartupContext(StartupContextConfig startupContext) { this.startupContext = startupContext; }
    public String getAgentDir() { return agentDir; }
    public void setAgentDir(String agentDir) { this.agentDir = agentDir; }
    public String getWorkspaceDir() { return workspaceDir; }
    public void setWorkspaceDir(String workspaceDir) { this.workspaceDir = workspaceDir; }
}
```

```java
package lyjew.com.lyclaw.bootstrap;

public enum ContextInjectionPolicy {
    /** 每轮都注入引导文件。 */
    ALWAYS,
    /**
     * 在继续轮次中跳过引导注入。
     * 仅在 /new、/reset 或会话启动时注入。
     */
    CONTINUATION_SKIP,
    /** 从不注入引导文件（用于测试）。 */
    NEVER
}

public enum BootstrapTruncationWarning {
    /** 内容超过限制时每个会话警告一次。 */
    ONCE,
    /** 每轮都警告。 */
    ALWAYS,
    /** 从不警告。 */
    NEVER
}
```

```java
package lyjew.com.lyclaw.bootstrap;

/**
 * 启动上下文：在会话启动时注入的文件列表、目录结构、
 * 最近的更改，为代理提供态势感知。
 */
public class StartupContextConfig {

    /** 启用启动上下文注入。默认值：true。 */
    boolean enabled = true;

    /** 何时应用：FIRST_TURN / EVERY_RESET / EVERY_TURN。默认 FIRST_TURN。 */
    StartupContextApplyOn applyOn = StartupContextApplyOn.FIRST_TURN;

    /** 启动上下文中包含的每日记忆天数。默认值：3。 */
    int dailyMemoryDays = 3;

    /** 列出目录内容时的最大文件字节数。默认值：500KB。 */
    long maxFileBytes = 500 * 1024;

    /** 单个目录中列出的最大文件数。默认值：200。 */
    int maxFilesPerDir = 200;

    /** 启动上下文中目录列表的最大总字符数。默认值：8000。 */
    int maxDirListChars = 8000;

    // ── getters / setters ──────────────────────────
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public StartupContextApplyOn getApplyOn() { return applyOn; }
    public void setApplyOn(StartupContextApplyOn applyOn) { this.applyOn = applyOn; }
    public int getDailyMemoryDays() { return dailyMemoryDays; }
    public void setDailyMemoryDays(int dailyMemoryDays) { this.dailyMemoryDays = dailyMemoryDays; }
    public long getMaxFileBytes() { return maxFileBytes; }
    public void setMaxFileBytes(long maxFileBytes) { this.maxFileBytes = maxFileBytes; }
    public int getMaxFilesPerDir() { return maxFilesPerDir; }
    public void setMaxFilesPerDir(int maxFilesPerDir) { this.maxFilesPerDir = maxFilesPerDir; }
    public int getMaxDirListChars() { return maxDirListChars; }
    public void setMaxDirListChars(int maxDirListChars) { this.maxDirListChars = maxDirListChars; }
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
 * 从代理目录加载引导文件，并应用
 * 截断、上下文注入策略和大小限制。
 *
 * <p>引导文件从 {@code {agentDir}/} 加载，可选地
 * 从 {@code {workspaceDir}/} 加载（例如用于项目特定的覆盖）。</p>
 */
public class BootstrapLoader {

    private static final Logger log = LoggerFactory.getLogger(BootstrapLoader.class);

    /** 始终加载的文件（不能出现在 skipOptionalBootstrapFiles 中）。 */
    private static final Set<String> REQUIRED_FILES = Set.of("AGENTS.md");

    /** 所有已知的引导文件名。 */
    private static final List<String> ALL_FILES = List.of(
            "AGENTS.md", "SOUL.md", "BOOTSTRAP.md",
            "IDENTITY.md", "USER.md", "HEARTBEAT.md"
    );

    private final BootstrapConfig config;

    public BootstrapLoader(BootstrapConfig config) {
        this.config = config;
    }

    /**
     * 加载代理的所有引导文件。
     *
     * @param agentDir     代理目录的绝对路径（例如 /home/lyclaw/agents/coder）
     * @param workspaceDir 工作区目录的绝对路径（可选，可以为 null）
     * @param config       引导配置
     * @return 已加载的引导内容
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
            // 遵循跳过列表（但绝不跳过 AGENTS.md）
            if (skip.contains(fileName) && !REQUIRED_FILES.contains(fileName)) {
                continue;
            }

            // 首先尝试 agentDir
            Path filePath = agentPath.resolve(fileName);
            String content = readFile(filePath);

            // 回退到 workspaceDir（用于项目级别的覆盖，如 USER.md）
            if (content == null && workspacePath != null) {
                content = readFile(workspacePath.resolve(fileName));
            }

            if (content != null) {
                // 应用每个文件的截断
                content = truncate(content, config.getBootstrapMaxChars(),
                        config.getBootstrapTotalMaxChars() - totalChars);
                loaded.put(fileName, content);
                totalChars += content.length();
            }
        }

        // 应用所有文件的总限制
        if (totalChars > config.getBootstrapTotalMaxChars()) {
            loaded = enforceTotalLimit(loaded, config.getBootstrapTotalMaxChars());
        }

        boolean truncated = totalChars > config.getBootstrapTotalMaxChars();
        log.info("BootstrapLoader：为代理目录 {} 加载了 {} 个文件，共 {} 个字符{}",
                loaded.size(), totalChars, truncated ? "（已截断）" : "", agentDir);

        return new BootstrapContent(loaded, truncated);
    }

    /**
     * 构建将根据配置的 ContextInjectionPolicy 前置/追加到
     * 系统提示的注入字符串。
     *
     * @param content 已加载的引导内容
     * @param policy  注入策略
     * @return 格式化后的注入字符串
     */
    public String buildContextInjection(BootstrapContent content,
                                        ContextInjectionPolicy policy) {
        if (policy == ContextInjectionPolicy.NEVER) {
            return "";
        }

        StringBuilder sb = new StringBuilder();

        // AGENTS.md 始终放在最前面
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

        // HEARTBEAT.md（如适用）
        String heartbeat = content.getFile("HEARTBEAT.md");
        if (heartbeat != null) {
            sb.append(heartbeat).append("\n\n");
        }

        // 截断警告
        if (content.isTruncated()
                && config.getTruncationWarning() != BootstrapTruncationWarning.NEVER) {
            sb.append("> 注意：部分引导内容被截断以适配上文限制。关键指令已保留。\n\n");
        }

        return sb.toString().trim();
    }

    /**
     * 截断内容以同时遵守每个文件和总限制。
     */
    public String truncate(String content, int maxChars, int remainingBudget) {
        int limit = Math.min(maxChars, remainingBudget);
        if (content == null) return null;
        if (content.length() <= limit) return content;
        return content.substring(0, limit - 30)
                + "\n... [已截断；超出限制]\n";
    }

    // ── 内部辅助方法 ───────────────────────────────────────────

    private String readFile(Path path) {
        try {
            if (Files.exists(path) && Files.isReadable(path)) {
                return Files.readString(path, StandardCharsets.UTF_8);
            }
        } catch (IOException e) {
            log.warn("BootstrapLoader：读取 {} 失败：{}", path, e.getMessage());
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
                        + "\n... [已截断；已达到引导总限制]\n";
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
 * 已加载的引导文件内容的不可变容器。
 */
public class BootstrapContent {

    private final Map<String, String> files;
    private final boolean truncated;

    public BootstrapContent(Map<String, String> files, boolean truncated) {
        this.files = Collections.unmodifiableMap(files);
        this.truncated = truncated;
    }

    /** 获取特定引导文件的内容，如果未加载则返回 null。 */
    public String getFile(String fileName) {
        return files.get(fileName);
    }

    /** 所有已加载的文件（文件名 -> 内容）。 */
    public Map<String, String> getFiles() { return files; }

    /** 是否有任何文件被截断以适配上限制。 */
    public boolean isTruncated() { return truncated; }

    /** 已加载的文件数量。 */
    public int fileCount() { return files.size(); }

    /** 所有已加载文件的总字符数。 */
    public int totalChars() {
        return files.values().stream().mapToInt(String::length).sum();
    }

    public static BootstrapContent empty() {
        return new BootstrapContent(Map.of(), false);
    }
}
```

### 3.2.4 ContextInjectionPolicy

参见上述枚举。关键行为：

- **ALWAYS**：引导内容在每一轮都被注入到系统提示中。这确保代理始终拥有其身份和指令，代价是 token 消耗。
- **CONTINUATION_SKIP**：内容在新会话的第一轮（/new、/reset）注入，但在继续轮次中跳过。降低长时间会话的 token 成本，因为代理已经内化了其身份。
- **NEVER**：永不注入。当所有设置都直接通过 ChatRequest 中的系统提示完成时，用于测试。

### 3.2.5 管道集成

现有的 `ContextBuildStage` 被增强以加载和注入引导内容。

增强要点：
- 新增 `BootstrapLoader` 和 `IdentityService` 依赖
- 在现有记忆检索逻辑之前注入引导内容和身份前缀

```java
// 在 ContextBuildStage（增强版）中：

@PipelineStage(name = "ContextBuild", group = "PREPROCESSING")
public class ContextBuildStage extends PipelineStageBase {

    private final MemorySystem memorySystem;
    private final MetricsCollector metricsCollector;
    private final BootstrapLoader bootstrapLoader;   // 新增
    private final IdentityService identityService;   // 新增（参见 §3.4）

    public ContextBuildStage(MemorySystem memorySystem,
                              @Nullable MetricsCollector metricsCollector,
                              BootstrapLoader bootstrapLoader,
                              IdentityService identityService) {
        this.memorySystem = memorySystem;
        this.metricsCollector = metricsCollector;
        this.bootstrapLoader = bootstrapLoader;
        this.identityService = identityService;
    }

    @Override
    public Flux<ServerSentEvent<String>> execute(AgentContext ctx) {
        if (ctx.isTerminated()) return Flux.empty();

        return Flux.defer(() -> {
            String traceId = ctx.getTracing().getTraceId();
            List<ServerSentEvent<String>> events = new ArrayList<>();
            try {
                ctx.getCurrentStage().set("CONTEXT_BUILD");

                // 1. 加载引导内容并注入到系统提示
                String agentId = ctx.getAgentId();
                String agentDir = ctx.getAgentDir();
                if (agentId != null && bootstrapLoader != null) {
                    BootstrapContent bootstrap = bootstrapLoader.loadBootstrap(
                            agentDir, ctx.getWorkspaceDir());
                    String injection = bootstrapLoader.buildContextInjection(bootstrap);
                    if (injection != null && !injection.isEmpty()) {
                        String currentPrompt = ctx.getSystemPrompt();
                        ctx.setSystemPrompt(injection + "\n\n" + currentPrompt);
                    }
                }

                // 2. 解析身份并存储到 ctx（供下游 RespondStage 使用）
                if (identityService != null && agentId != null) {
                    IdentityConfig identity = identityService.resolveIdentity(agentId, agentDir);
                    ctx.setAttribute("identity", identity);
                }

                // 3. 现有的记忆检索逻辑
                // ... (保持现有 MemorySystem.retrieve 逻辑不变) ...

                // ...
            } catch (Exception e) {
                log.warn("Context build error: {}", e.getMessage(), e);
            }
            return Flux.fromIterable(events);
        });
    }
}
```

### 3.2.6 YAML 配置

```yaml
lyclaw:
  # ── 引导 ─────────────────────────────────────────
  bootstrap:
    # 跳过所有引导加载
    skip-bootstrap: false

    # 需要跳过的可选文件（AGENTS.md 永远不能跳过）
    skip-optional-bootstrap-files: []
    # 示例：["SOUL.md", "HEARTBEAT.md"]

    # 注入策略：ALWAYS | CONTINUATION_SKIP | NEVER
    context-injection: ALWAYS

    # 每个文件和总限制
    bootstrap-max-chars: 20000
    bootstrap-total-max-chars: 150000

    # 截断警告：ONCE | ALWAYS | NEVER
    truncation-warning: ONCE

    # 代理目录（null = ${user.dir}/agents/{agentId}）
    agent-dir: null
    # 工作区目录（null = ${user.dir}）
    workspace-dir: null

    # ── 启动上下文 ────────────────────────────────
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

## 3.3 代理路由与绑定

### 动机

LyClaw 目前只有一个 `ChatController`，将所有流量路由到一个
`ChatAgent`。没有机制可以根据渠道（例如 Discord #general vs #engineering）、
账户或对等体身份将传入消息路由到不同的代理。

代理路由系统增加了：
1. **AgentRouteBinding** — 将路由（渠道、账户、对等体、公会、角色）映射到代理。
2. **AgentAcpBinding** — 将路由映射到具有 ACP 特定覆盖的代理。
3. **AgentRouter** — 解析哪个代理处理传入的请求。

### 3.3.1 AgentBindingMatch

```java
package lyjew.com.lyclaw.routing;

import lombok.Builder;
import java.util.Set;

/**
 * 将传入请求路由到代理的匹配条件。
 *
 * <p>所有字段均为可选。空/为 null 的字段表示"匹配任意内容"。
 * 多个非 null 字段之间是 AND 关系。至少需要一个字段
 * 为非 null 才能使绑定被考虑。</p>
 */
public class AgentBindingMatch {

    /** 要匹配的渠道名称（例如 "general"、"engineering"）。 */
    private String channel;

    /** 要匹配的账户 ID。 */
    private String accountId;

    /** 要匹配的对等体 ID / 用户 ID。 */
    private String peer;

    /** 要匹配的公会 / 服务器 ID。 */
    private String guildId;

    /** 要匹配的团队 ID。 */
    private String teamId;

    /** 必需的角色（用户必须拥有所有这些角色）。 */
    private Set<String> roles;

    // ======== getters / setters ========

    public String getChannel() { return channel; }
    public void setChannel(String channel) { this.channel = channel; }

    public String getAccountId() { return accountId; }
    public void setAccountId(String accountId) { this.accountId = accountId; }

    public String getPeer() { return peer; }
    public void setPeer(String peer) { this.peer = peer; }

    public String getGuildId() { return guildId; }
    public void setGuildId(String guildId) { this.guildId = guildId; }

    public String getTeamId() { return teamId; }
    public void setTeamId(String teamId) { this.teamId = teamId; }

    public Set<String> getRoles() { return roles; }
    public void setRoles(Set<String> roles) { this.roles = roles; }

    /**
     * 检查此匹配条件是否是给定条件的超集。
     * 用于查找最具体（最窄）的绑定。
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
     * 检查此匹配是否与给定的请求元数据匹配。
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
 * 从传入请求中提取的元数据，用于代理路由。
 */
@Data
@Builder
public class RequestMetadata {

    String channel;       // 例如 "general"
    String accountId;     // 例如 Discord 账户 ID
    String peer;          // 用户标识符
    String guildId;       // 服务器/公会标识符
    String teamId;        // 团队标识符
    Set<String> roles;    // 用户角色

    /** 创建空的元数据（匹配默认/回退路由）。 */
    public static RequestMetadata empty() {
        return RequestMetadata.builder().build();
    }
}
```

### 3.3.2 AgentRouteBinding 与 AgentAcpBinding

```java
package lyjew.com.lyclaw.routing;

import lombok.Builder;
import lombok.Data;

/**
 * 代理绑定的基础接口。
 */
public sealed interface AgentBinding
        permits AgentRouteBinding, AgentAcpBinding {

    String getType();
    String getAgentId();
    AgentBindingMatch getMatch();
}

/**
 * 路由绑定：将一组匹配条件映射到一个代理 ID。
 *
 * <p>当请求的元数据与条件匹配时，它将被路由
 * 到指定的代理。</p>
 */
public final class AgentRouteBinding implements AgentBinding {

    private String type = "route";

    /** 目标代理 ID。 */
    private String agentId;

    /** 此绑定的人类可读注释。 */
    private String comment;

    /** 匹配条件（渠道、账户、对等体、公会、团队、角色）。 */
    private AgentBindingMatch match;

    /** 会话范围配置。 */
    private SessionScope session = new SessionScope();

    // ======== getters / setters ========

    @Override
    public String getType() { return type; }
    public void setType(String type) { this.type = type; }

    @Override
    public String getAgentId() { return agentId; }
    public void setAgentId(String agentId) { this.agentId = agentId; }

    public String getComment() { return comment; }
    public void setComment(String comment) { this.comment = comment; }

    @Override
    public AgentBindingMatch getMatch() { return match; }
    public void setMatch(AgentBindingMatch match) { this.match = match; }

    public SessionScope getSession() { return session; }
    public void setSession(SessionScope session) { this.session = session; }

    /**
     * DM 会话范围：控制私信是与渠道绑定的会话共享
     * 还是拥有自己的会话。
     */
    public static class SessionScope {
        /**
         * 私信的范围。
         * SHARED：DM 使用与渠道路由相同的会话。
         * ISOLATED：DM 拥有自己的会话。
         */
        private DmScope dmScope = DmScope.SHARED;

        public DmScope getDmScope() { return dmScope; }
        public void setDmScope(DmScope dmScope) { this.dmScope = dmScope; }
    }

    public enum DmScope { SHARED, ISOLATED }
}

/**
 * ACP（代理通信协议）绑定：类似 RouteBinding
 * 但具有额外的 ACP 特定覆盖。
 */
public final class AgentAcpBinding implements AgentBinding {

    private String type = "acp";

    /** 目标代理 ID。 */
    private String agentId;

    /** 人类可读注释。 */
    private String comment;

    /** 匹配条件。 */
    private AgentBindingMatch match;

    /** ACP 特定覆盖。 */
    private AcpOverrides acp = new AcpOverrides();

    // ======== getters / setters ========

    @Override
    public String getType() { return type; }
    public void setType(String type) { this.type = type; }

    @Override
    public String getAgentId() { return agentId; }
    public void setAgentId(String agentId) { this.agentId = agentId; }

    public String getComment() { return comment; }
    public void setComment(String comment) { this.comment = comment; }

    @Override
    public AgentBindingMatch getMatch() { return match; }
    public void setMatch(AgentBindingMatch match) { this.match = match; }

    public AcpOverrides getAcp() { return acp; }
    public void setAcp(AcpOverrides acp) { this.acp = acp; }

    public static class AcpOverrides {
        /** ACP 模式。 */
        private String mode;

        /** 用于显示的 ACP 标签。 */
        private String label;

        /** 此绑定的工作目录覆盖。 */
        private String cwd;

        /** 后端覆盖。 */
        private String backend;

        public String getMode() { return mode; }
        public void setMode(String mode) { this.mode = mode; }

        public String getLabel() { return label; }
        public void setLabel(String label) { this.label = label; }

        public String getCwd() { return cwd; }
        public void setCwd(String cwd) { this.cwd = cwd; }

        public String getBackend() { return backend; }
        public void setBackend(String backend) { this.backend = backend; }
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
 * 根据配置的绑定解析由哪个代理处理传入的请求。
 *
 * <h3>解析算法</h3>
 * <ol>
 *   <li>查找所有其 {@link AgentBindingMatch} 与请求元数据匹配的绑定。</li>
 *   <li>选择最具体的匹配（最高 specificity 分数）。</li>
 *   <li>如果没有匹配，返回默认代理 ID。</li>
 * </ol>
 *
 * <p>绑定通常从 YAML 配置加载
 * （参见 {@code lyclaw.routing.bindings}）或从注解加载。</p>
 */
public class AgentRouter {

    private static final Logger log = LoggerFactory.getLogger(AgentRouter.class);

    private final List<AgentBinding> bindings;
    private final String defaultAgentId;

    public AgentRouter(List<AgentBinding> bindings, String defaultAgentId) {
        // 按 specificity 降序排序（最具体的优先）
        this.bindings = new ArrayList<>(bindings);
        this.bindings.sort(Comparator
                .<AgentBinding>comparingInt(b -> b.getMatch() != null
                        ? b.getMatch().specificity() : 0)
                .reversed());
        this.defaultAgentId = defaultAgentId;
        log.info("AgentRouter 初始化：{} 个绑定，默认代理={}",
                bindings.size(), defaultAgentId);
    }

    /**
     * 为传入的请求解析代理 ID。
     *
     * @param metadata 请求元数据（渠道、账户、对等体等）
     * @return 处理此请求的代理 ID
     */
    public String resolveAgentId(RequestMetadata metadata) {
        if (metadata == null) {
            metadata = RequestMetadata.empty();
        }

        // 查找最具体的匹配绑定
        for (AgentBinding binding : bindings) {
            AgentBindingMatch match = binding.getMatch();
            if (match == null) continue; // 跳过没有匹配条件的绑定

            if (match.matches(metadata)) {
                log.debug("AgentRouter：匹配 {} -> {} (specificity={})",
                        metadata.getChannel() != null ? "#" + metadata.getChannel() : "default",
                        binding.getAgentId(),
                        match.specificity());
                return binding.getAgentId();
            }
        }

        // 没有匹配 — 使用默认值
        log.debug("AgentRouter：渠道={} 没有匹配，使用默认值={}",
                metadata.getChannel(), defaultAgentId);
        return defaultAgentId;
    }

    /**
     * 解析代理 ID 并返回完整的绑定信息（用于 ACP 覆盖等）。
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

        // 返回默认代理的合成路由绑定
        AgentRouteBinding fallback = new AgentRouteBinding();
        fallback.setAgentId(defaultAgentId);
        fallback.setComment("默认路由（回退）");
        fallback.setMatch(new AgentBindingMatch());
        return fallback;
    }

    /**
     * 获取默认代理 ID。
     */
    public String getDefaultAgentId() {
        return defaultAgentId;
    }

    /**
     * 对简写表示法（如 "#general" 或 "@botname"）的模式匹配支持。
     * <p>这由按渠道/提及进行路由的平台（Discord、Slack）使用。</p>
     *
     * @param pattern 简写模式（例如 "#general"、"@coder-bot"）
     * @return 解析后的代理 ID，如果未找到则返回 null
     */
    public String resolveByPattern(String pattern) {
        if (pattern == null) return null;

        // "#channel" 表示法
        if (pattern.startsWith("#")) {
            String channel = pattern.substring(1);
            return resolveAgentId(RequestMetadata.builder().channel(channel).build());
        }

        // "@agent" 表示法 — 查找 IDENTITY.md 名称匹配的代理
        // 或者检查 pattern 是否直接匹配 agentId
        for (AgentBinding binding : bindings) {
            if (pattern.equals(binding.getAgentId())) {
                return binding.getAgentId();
            }
        }

        return null;
    }

    /** 已注册的绑定数量。 */
    public int bindingCount() {
        return bindings.size();
    }
}
```

### 3.3.4 ChatController 更新

ChatController 新增 `AgentRouter` 依赖，在请求进入时从 `ChatRequest.extras` 中提取路由元数据，
解析出目标 agentId，存入 `ChatRequest.agentId` 字段。`ChatAgent` 代理内部通过 `AgentContext`
携带 agentId，从而在整个管线中生效。

```java
package lyjew.com.lyclaw.web.controller;

import lyjew.com.lyclaw.model.ChatRequest;
import lyjew.com.lyclaw.routing.AgentRouter;
import lyjew.com.lyclaw.routing.RequestMetadata;
import lyjew.com.lyclaw.web.agent.ChatAgent;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.*;

@RestController
@RequestMapping("/api")
public class ChatController {

    private final ChatAgent chatAgent;
    private final AgentRouter agentRouter;          // 新增

    public ChatController(ChatAgent chatAgent, AgentRouter agentRouter) {
        this.chatAgent = chatAgent;
        this.agentRouter = agentRouter;
    }

    /**
     * 从 ChatRequest.extras 提取路由元数据，解析 agentId 并写入 request。
     */
    private void applyRouting(ChatRequest request) {
        if (agentRouter == null || request.getExtras() == null) return;
        RequestMetadata metadata = extractMetadata(request);
        String resolvedAgentId = agentRouter.resolveAgentId(metadata);
        if (resolvedAgentId != null && !resolvedAgentId.isEmpty()) {
            request.setAgentId(resolvedAgentId);
        }
    }

    private RequestMetadata extractMetadata(ChatRequest request) {
        Map<String, Object> extras = request.getExtras();
        return RequestMetadata.builder()
                .channel(str(extras, "channel"))
                .accountId(str(extras, "accountId"))
                .peer(str(extras, "peer"))
                .guildId(str(extras, "guildId"))
                .teamId(str(extras, "teamId"))
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

    private static String str(Map<String, Object> m, String key) {
        Object v = m != null ? m.get(key) : null;
        return v instanceof String s ? s : null;
    }

    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> chatStream(@RequestBody ChatRequest request,
                                                     @RequestParam(required = false) String agentId) {
        applyRouting(request);
        String resolvedAgentId = agentId != null && !agentId.isEmpty() ? agentId
                : request.getAgentId() != null && !request.getAgentId().isEmpty() ? request.getAgentId()
                : null;
        String userMessage = request.getLastUserMessage();
        return chatAgent.chatStream(userMessage);
    }

    @PostMapping("/chat")
    public Mono<Map<String, Object>> chat(@RequestBody ChatRequest request,
                                          @RequestParam(required = false) String agentId) {
        applyRouting(request);
        String resolvedAgentId = agentId != null && !agentId.isEmpty() ? agentId
                : request.getAgentId() != null && !request.getAgentId().isEmpty() ? request.getAgentId()
                : null;
        String userMessage = request.getLastUserMessage();
        String sessionId = request.getSessionId() != null ? request.getSessionId() : "";
        return Mono.fromCallable(() -> chatAgent.chat(userMessage))
                .subscribeOn(Schedulers.boundedElastic())
                .map(reply -> Map.of("content", reply, "sessionId", sessionId));
    }

    // ... 会话端点保持不变 ...
}
```

### 3.3.5 YAML 配置

```yaml
lyclaw:
  # ── 路由 ───────────────────────────────────────────
  routing:
    # 没有绑定匹配时的默认代理
    default-agent: default

    # ── 绑定 ───────────────────────────────────────
    bindings:
      # 路由绑定：Discord #general 频道 -> "helper" 代理
      - type: route
        agent-id: helper
        comment: "通用聊天助手"
        match:
          channel: general
          guild-id: "111222333444"
        session:
          dm-scope: SHARED

      # 路由绑定：Discord #engineering 频道 -> "coder" 代理
      - type: route
        agent-id: coder
        comment: "工程代码助手"
        match:
          channel: engineering
          guild-id: "111222333444"

      # 路由绑定：特定用户获得 "admin" 代理
      - type: route
        agent-id: admin
        comment: "高级用户的管理助手"
        match:
          peer: "user-admin-001"
          roles: ["admin"]

      # ACP 绑定：带有工作目录覆盖
      - type: acp
        agent-id: coder
        comment: "coder 代理的 ACP 绑定"
        match:
          channel: dev-acp
        acp:
          mode: interactive
          label: "Dev ACP"
          cwd: /home/lyclaw/projects
          backend: openai-protocol

      # 全捕获回退（匹配任意内容，最低 specificity）
      - type: route
        agent-id: default
        comment: "默认回退代理"
        match: {}
```

---

## 3.4 身份与头像

### 动机

目前 LyClaw 代理没有可视身份。它们只是无名的文本
响应器。IdentityConfig 添加了显示名称、头像、名称前缀（例如
"[CoderBot] "）、响应前缀、消息前缀和确认回应。

### 3.4.1 IdentityConfig

```java
package lyjew.com.lyclaw.identity;

/**
 * 代理身份和展示配置。
 *
 * <p>控制代理在 UI 中的显示方式（名称、头像）以及其
 * 消息在输出流中如何添加前缀/注释。</p>
 *
 * <p>映射自 application.yml 中的 {@code lyclaw.identity}，或
 * 从代理的 IDENTITY.md 引导文件加载。</p>
 */
public class IdentityConfig {

    /** UI 中显示的显示名称。 */
    String displayName;

    /** 头像图片 URL（远程或数据 URI）。 */
    String avatarUrl;

    /** 头像图片文件路径（本地）。 */
    String avatarFilePath;

    /** 在聊天中前置到代理回复的名称前缀（如 "[CoderBot] "）。 */
    String namePrefix;

    /** 前置到每轮最终文本回复的响应前缀。 */
    String responsePrefix;

    /** 前置到所有消息（含工具调用、状态更新）的前缀。 */
    String messagePrefix;

    /** 确认消息的表情回应（如 "eyes"）。 */
    String ackReaction;

    // ── getters / setters ──────────────────────────
    public String getDisplayName() { return displayName; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }
    public String getAvatarUrl() { return avatarUrl; }
    public void setAvatarUrl(String avatarUrl) { this.avatarUrl = avatarUrl; }
    public String getAvatarFilePath() { return avatarFilePath; }
    public void setAvatarFilePath(String avatarFilePath) { this.avatarFilePath = avatarFilePath; }
    public String getNamePrefix() { return namePrefix; }
    public void setNamePrefix(String namePrefix) { this.namePrefix = namePrefix; }
    public String getResponsePrefix() { return responsePrefix; }
    public void setResponsePrefix(String responsePrefix) { this.responsePrefix = responsePrefix; }
    public String getMessagePrefix() { return messagePrefix; }
    public void setMessagePrefix(String messagePrefix) { this.messagePrefix = messagePrefix; }
    public String getAckReaction() { return ackReaction; }
    public void setAckReaction(String ackReaction) { this.ackReaction = ackReaction; }

    /**
     * 从身份配置构建显示标签。
     */
    public String getDisplayLabel() {
        if (displayName != null && !displayName.isEmpty()) {
            return displayName;
        }
        return "Agent";
    }

    /**
     * 将名称前缀应用到消息字符串。
     */
    public String applyNamePrefix(String message) {
        if (namePrefix == null || namePrefix.isEmpty()) {
            return message;
        }
        if (message == null) return namePrefix;
        return namePrefix + message;
    }

    /**
     * 将响应前缀应用到最终响应。
     */
    public String applyResponsePrefix(String response) {
        if (responsePrefix == null || responsePrefix.isEmpty()) {
            return response;
        }
        if (response == null) return responsePrefix;
        return responsePrefix + response;
    }

    /**
     * 将消息前缀应用到任何消息。
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
 * 代理头像的来源方式。
 */
public enum AvatarKind {
    /** 没有可用的头像。 */
    NONE,
    /** 头像从本地文件加载。 */
    LOCAL,
    /** 头像从远程 URL 加载。 */
    REMOTE,
    /** 头像以数据 URI 形式嵌入。 */
    DATA
}

/**
 * 已解析的头像信息，包含关于解析过程的元数据。
 */
public class AgentAvatarResolution {

    private final AvatarKind kind;
    private final String reason;      // 对于 NONE：为什么没有头像
    private final String filePath;    // 对于 LOCAL：绝对路径
    private final String url;         // 对于 REMOTE、DATA：URL/数据 URI
    private final String source;      // 头像在何处找到（例如 "IDENTITY.md"、"config"）

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
     * 从 IdentityConfig 解析头像，按以下顺序尝试每种来源：
     * avatarFilePath -> avatarUrl -> NONE。
     */
    public static AgentAvatarResolution resolve(IdentityConfig config) {
        // 1. 尝试本地文件
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
                    "文件未找到：" + config.getAvatarFilePath(),
                    null, null, "config.avatarFilePath");
        }

        // 2. 尝试 URL
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

        // 3. 没有找到任何内容
        return new AgentAvatarResolution(
                AvatarKind.NONE, "未配置头像", null, null, "none");
    }

    /** 便捷方法：是否有可用头像？ */
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
 * 解析代理身份的中心服务。
 *
 * <p>身份从三个来源加载（按优先级排序）：
 * <ol>
 *   <li>显式 YAML 配置（{@code lyclaw.identity}）</li>
 *   <li>引导文件 IDENTITY.md</li>
 *   <li>从 agentId 派生的默认值</li>
 * </ol>
 */
public class IdentityService {

    private final IdentityConfig configuredIdentity; // 来自 YAML
    private final BootstrapLoader bootstrapLoader;

    public IdentityService(IdentityConfig configuredIdentity, BootstrapLoader bootstrapLoader) {
        this.configuredIdentity = configuredIdentity;
        this.bootstrapLoader = bootstrapLoader;
    }

    /**
     * 解析代理的有效身份。
     *
     * @param agentId   代理的 ID
     * @param agentDir  代理的目录（用于加载 IDENTITY.md）
     * @return 有效的身份配置
     */
    public IdentityConfig resolveIdentity(String agentId, String agentDir) {
        IdentityConfig resolved = new IdentityConfig();

        // 第 1 层：从 YAML 配置复制
        if (configuredIdentity != null) {
            resolved.setDisplayName(configuredIdentity.getDisplayName());
            resolved.setAvatarUrl(configuredIdentity.getAvatarUrl());
            resolved.setAvatarFilePath(configuredIdentity.getAvatarFilePath());
            resolved.setNamePrefix(configuredIdentity.getNamePrefix());
            resolved.setResponsePrefix(configuredIdentity.getResponsePrefix());
            resolved.setMessagePrefix(configuredIdentity.getMessagePrefix());
            resolved.setAckReaction(configuredIdentity.getAckReaction());
        }

        // 第 2 层：如果 IDENTITY.md 可用，则用其覆盖
        // （IDENTITY.md 内容遵循简单的 key: value 格式）
        // ... 解析 IDENTITY.md 并应用覆盖 ...

        // 第 3 层：回退显示名称
        if (resolved.getDisplayName() == null) {
            resolved.setDisplayName(agentId);
        }

        return resolved;
    }

    /**
     * 将身份前缀应用到代理响应。
     */
    public String applyIdentity(String response, IdentityConfig identity) {
        String result = response;
        result = identity.applyResponsePrefix(result);
        result = identity.applyNamePrefix(result);
        return result;
    }
}
```

### 3.4.3 集成与 YAML

#### 管道集成

在 `ContextBuildStage` 中，身份被解析并存储在 `AgentContext` 中供下游阶段使用：

```java
// 在 ContextBuildStage.execute() 中：
IdentityConfig identity = identityService.resolveIdentity(agentId, agentDir);
ctx.setAttribute("identity", identity);
ctx.setAttribute("avatarResolution", AgentAvatarResolution.resolve(identity));
```

在 `RespondStage`（或最终响应发出的任何位置）中，应用身份前缀：

```java
// 在发出最终响应之前：
IdentityConfig identity = ctx.getAttribute("identity");
if (identity != null) {
    finalResponse = identityService.applyIdentity(finalResponse, identity);
}
```

#### YAML 配置

```yaml
lyclaw:
  # ── 身份 ──────────────────────────────────────────
  identity:
    # UI 中显示的显示名称
    display-name: "LyClaw Assistant"

    # 头像 URL（远程）或文件路径（本地）
    avatar-url: null
    avatar-file-path: null

    # 应用到代理输出的前缀
    name-prefix: null         # 例如 "[CoderBot] "
    response-prefix: null     # 例如 "这是我找到的内容：\n"
    message-prefix: null      # 例如 "🤖 "

    # 确认回应表情（用于 Discord/Slack 适配器）
    ack-reaction: "eyes"
```

---

## 完整 YAML 配置参考

```yaml
lyclaw:
  # ================================================================
  #  第三阶段 — 上下文引擎、引导、路由、身份
  # ================================================================

  # ── 3.1 压缩 ────────────────────────────────────
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

  # ── 3.2 引导 ─────────────────────────────────────
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

  # ── 3.3 路由 ───────────────────────────────────────
  routing:
    default-agent: default
    bindings: []
    # 示例绑定：
    # - type: route
    #   agent-id: helper
    #   comment: "通用聊天助手"
    #   match:
    #     channel: general
    #     guild-id: "111222333444"
    #   session:
    #     dm-scope: SHARED

  # ── 3.4 身份 ──────────────────────────────────────
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

## 集成检查清单

### 3.1 上下文引擎与压缩

- [ ] 创建 `lyclaw-framework/src/main/java/lyjew/com/lyclaw/compaction/` 包
- [ ] 实现 `CompactionConfig`（POJO，不使用 Lombok builder）
- [ ] 实现枚举：`CompactionMode`、`IdentifierPolicy`、`PostIndexSync`
- [ ] 实现子配置：`QualityGuard`、`MidTurnPrecheck`、`MemoryFlush`
- [ ] 实现 `CompactionEngine` 带 `needsCompaction()`、`compact()`、`validateCompaction()`、`midTurnPrecheck()`
- [ ] 实现 `CompactionResult` 记录
- [ ] 实现 `ContextPruningConfig` 带 `SoftTrim`、`HardClear`
- [ ] 实现 `ContextPruner` 带 `prune()` 方法
- [ ] 实现 `AgentContextLimits` 带截断辅助方法
- [ ] 创建 `CompactionStage`（`@PipelineStage(name = "Compaction", after = ReflectionStage.class, group = "POST_PROCESSING")`，extends PipelineStageBase）
- [ ] **修改 `MetricsStage` 的 `@PipelineStage` 注解**：`after` 从 `ReflectionStage.class` 改为 `CompactionStage.class`，以维持拓扑顺序
- [ ] `AgentHook` 的 `beforeCompaction`/`afterCompaction` 已在 Phase 2 定义，无需新增
- [ ] 实现 `ContextPruningScheduler` 带 `@Scheduled`
- [ ] 创建 `CompactionProperties`（`@ConfigurationProperties("lyclaw.compaction")`）用于 YAML 绑定
- [ ] 在 `CompactionAutoConfiguration` 中连线
- [ ] `SubagentSessionManager.getActiveSessions()` 供修剪调度器使用（已在 Phase 2 中存在）

### 3.2 工作区引导

- [ ] 创建 `lyclaw-framework/src/main/java/lyjew/com/lyclaw/bootstrap/` 包
- [ ] 实现 `BootstrapConfig`（POJO，不使用 Lombok builder）
- [ ] 实现枚举：`ContextInjectionPolicy`、`BootstrapTruncationWarning`、`StartupContextApplyOn`
- [ ] 实现 `StartupContextConfig`
- [ ] 实现 `BootstrapLoader` 带 `loadBootstrap()` 和 `buildContextInjection()`
- [ ] 实现 `BootstrapContent` 不可变容器
- [ ] 增强 `ContextBuildStage` 以调用 `BootstrapLoader` 并注入内容
- [ ] 创建 `BootstrapProperties`（`@ConfigurationProperties("lyclaw.bootstrap")`）用于 YAML 绑定
- [ ] 在 `BootstrapAutoConfiguration` 中连线
- [ ] 在 `/agents/default/` 中创建示例引导文件

### 3.3 代理路由与绑定

- [ ] 创建 `lyclaw-framework/src/main/java/lyjew/com/lyclaw/routing/` 包
- [ ] 实现 `RequestMetadata` 带 channel、accountId、peer、guildId、teamId、roles
- [ ] 实现 `AgentBindingMatch` 带 `matches()` 和 `specificity()`
- [ ] 实现密封的 `AgentBinding` 接口，以及 `AgentRouteBinding` 和 `AgentAcpBinding`
- [ ] 实现 `AgentRouter` 带 `resolveAgentId()`、`resolveBinding()`、`resolveByPattern()`
- [ ] 增强 `ChatController`：注入 `AgentRouter`，从 `ChatRequest.extras` 提取元数据，将解析出的 agentId 写入 `request.agentId`
- [ ] 创建 `RoutingProperties`（`@ConfigurationProperties("lyclaw.routing")`）用于 YAML 绑定
- [ ] 在 `RoutingAutoConfiguration` 中连线

### 3.4 身份与头像

- [ ] 创建 `lyclaw-framework/src/main/java/lyjew/com/lyclaw/identity/` 包
- [ ] 实现 `IdentityConfig` 带 displayName、avatar、prefixes、ackReaction
- [ ] 实现 `AvatarKind` 枚举和 `AgentAvatarResolution` 带 `resolve()`
- [ ] 实现 `IdentityService` 带 `resolveIdentity()` 和 `applyIdentity()`
- [ ] 增强 `ContextBuildStage` 以调用 `IdentityService` 并在 `AgentContext` 中存储身份
- [ ] 在 `RespondStage.execute()` 开头发出 `identity` SSE 事件（携带 displayName、avatarUrl），由前端渲染
- [ ] 创建 `IdentityProperties`（`@ConfigurationProperties("lyclaw.identity")`）用于 YAML 绑定
- [ ] 在 `IdentityAutoConfiguration` 中连线

### 跨领域

- [ ] 使用完整的配置参考更新 `application.yml`
- [ ] 为 `CompactionEngine`、`BootstrapLoader`、`AgentRouter`、`IdentityService` 添加单元测试
- [ ] 为带有压缩和引导的完整管道添加集成测试
- [ ] 记录新的 SSE 事件：`compaction`、身份元数据
- [ ] 在 `AutoConfiguration.imports` 中注册所有新增的 AutoConfiguration 类
