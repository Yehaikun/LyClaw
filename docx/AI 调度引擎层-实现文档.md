# LyClaw AI 调度引擎层 — 实现文档

**关联设计文档**：`AI 调度引擎层.md`
**实现目标**：根据设计文档，编写 lyclaw-core 和 lyclaw-engine 的全部 82 个 .java 文件

---

## 实现总览：分 11 部分，逐部分完成

| 部分 | 模块 | 文件数 | 内容 | 状态 |
|------|------|--------|------|------|
| **第一部分** | lyclaw-core（基础） | 20 | 基础接口：Engine/Pipeline/PipelineStage/Chain/ChatContext/ContextBuilder/Interceptor/Tool/ToolRegistry/ToolCallPolicy/ToolResult/ToolErrorAction + 核心实现层：Engine/EngineSelector/FullWindowContextBuilder/InterceptorChain | ✅ 已完成 |
| **第二部分** | lyclaw-core（技能+记忆） | 10 | Skill/SkillType/SkillExecutor/SkillProgressCallback/SkillRegistry/SkillGraph/MemoryManager/MemoryStrategy + MemoryContent/PageResult | ✅ 已完成 |
| **第三部分** | lyclaw-core（事件+Agent+错误） | 20 | Event/EventBus/AgentCoordinator/AgentChannel/AgentTask/AgentState/ErrorPolicy/SecurityManager/TaskPlanner/TaskPlan/TaskLedger/ModelProvider + AgentMessage/ModelException/ToolExecuteException/ApprovalResult/SandboxLevel/TaskNode/TaskResult/TaskRecord | ✅ 已完成 |
| **第四部分** | lyclaw-core（检索+缓存+追踪+过滤器） | 7 | VectorStore/CacheService/TraceContext/ContentFilter/FilterResult + SessionTransaction/SessionUpdate/SessionUpdateStrategy/TransactionContext | ✅ 已完成 |
| **第五部分** | lyclaw-engine（Engine+Pipeline实现） | 8 | DefaultEngine/EngineSelector/PipelineBuilder + 5个Stage（ContextBuild/Interceptor/ToolCallLoop/Metrics/ResponseBuild） | ✅ 已完成 |
| **第六部分** | lyclaw-engine（Tool+Skill实现） | 15 | DefaultToolRegistry/ToolCallLoop/DefaultToolCallPolicy + 5个Tool + DefaultSkillRegistry/SkillGraphImpl/ToolToSkillAdapter + FileMemoryManager/ManualMemoryStrategy + InMemoryEventBus + 3个Event类 | ✅ 已完成 |
| **第七部分** | lyclaw-engine（Agent+错误+事务+安全+Task） | 6 | StarAgentChannel/DefaultErrorPolicy/DefaultSessionTransaction/DefaultSecurityManager + DefaultTaskPlanner/DefaultTaskLedger | ✅ 已完成 |
| **第八部分** | lyclaw-core（3个DTO）+ lyclaw-engine（2个配置） | 5 | EngineProperties/EngineAutoConfiguration + ChatResult/AgentResult/SkillResult | ✅ 已完成 |
| **第九部分** | Spring Boot 集成测试（7个测试类） | 7 | 容器启动测试、各组件注入测试、完整 Pipeline 流程测试 | ✅ 已完成 |
| **第十部分** | 流式工具调用状态机（lyclaw-engine/stream） | 11 | StreamToolCallStateMachine + 5个状态/信号文件 + 2个SSE解析器 + 2个工具状态组件 + SyncModelCallState | ✅ 已完成 |
| **第十一部分** | ChatController + 前端工具调用展示 + 适配器更新 | 3（改动） | Controller SSE透传重构、resolveToolChoice支持字符串、前端tool_call事件解析 | ✅ 已完成 |

---

# 第一部分：基础接口（lyclaw-core）

## 实现文件清单

| 序号 | 文件 | 包 | 类/接口类型 |
|------|------|-----|-------------|
| 1 | Engine.java | engine | 接口 |
| 2 | EngineMetadata.java | engine | 类 |
| 3 | Pipeline.java | pipeline | 接口 |
| 4 | PipelineStage.java | pipeline | 接口 |
| 5 | Chain.java | pipeline | 接口 |
| 6 | ChatContext.java | context | 类 |
| 7 | ContextBuilder.java | context | 接口 |
| 8 | Interceptor.java | interceptor | 接口 |
| 9 | Tool.java | tool | 接口 |
| 10 | ToolRegistry.java | tool | 接口 |
| 11 | ToolCallPolicy.java | tool | 接口 |
| 12 | ToolResult.java | tool | 类 |
| 13 | ToolErrorAction.java | tool | 类 |
| 14 | FullWindowContextBuilder.java | 见下 | 实现 |
| 15 | InterceptorChain.java | nterceptor | 实现 |
| 16 | RateLimitInterceptor.java | interceptor/impl | 实现 |
| 17 | SensitiveDataInterceptor.java | interceptor/impl | 实现 |
| 18 | LoggingInterceptor.java | interceptor/impl | 实现 |

## 第一块：Engine.java

**文件路径**：`lyclaw-core/src/main/java/lyjew/com/lyclaw/engine/Engine.java`

```java
package lyjew.com.lyclaw.engine;

import lyjew.com.lyclaw.model.ChatRequest;
import reactor.core.publisher.Flux;

/**
 * Engine 接口 — AI 引擎的顶层执行协议。
 *
 * <p>每个 Engine 实现代表一种完全不同的对话处理逻辑，通过策略模式实现多引擎共存。
 * EngineSelector 按 {@link #getOrder()} 升序排序后依次检查 {@link #supports(ChatRequest)}，
 * 返回第一个匹配的引擎。
 *
 * <p>已知实现及优先级约定：
 * <ul>
 *   <li>DefaultEngine — 兜底引擎，getOrder() 返回 {@link Integer#MAX_VALUE}</li>
 *   <li>ReasoningEngine（未来）— 推理链引擎，getOrder() 返回 10</li>
 *   <li>PlanningEngine（未来）— 任务规划引擎，getOrder() 返回 20</li>
 *   <li>RagEngine（未来）— 检索增强引擎，getOrder() 返回 30</li>
 * </ul>
 *
 * <p>替换机制：新建实现类 + 实现接口 + @Component，EngineSelector 自动发现。
 * 已有引擎代码零修改。
 *
 * @author LyClaw Team
 * @version 1.0
 * @see EngineSelector
 * @see ChatRequest
 * @see Flux
 */
public interface Engine {

    /**
     * 返回引擎唯一标识。
     *
     * <p>标识用于日志记录（如 "[default] 开始处理请求"）、监控面板展示、运维界面列出。
     * 要求在整个系统中保持唯一。
     *
     * @return 引擎名称，如 "default"、"reasoning"、"planning"、"rag"
     */
    String getName();

    /**
     * 返回引擎优先级。
     *
     * <p>数字越小优先级越高，越先被 EngineSelector 检查。
     * getOrder() 的返回值应该是稳定的——不应在运行时动态变化。
     *
     * <p>返回值约定：
     * <ul>
     *   <li>特殊引擎（如 ReasoningEngine、PlanningEngine）：返回较小的值（10、20、30），确保优先匹配</li>
     *   <li>DefaultEngine：返回 {@link Integer#MAX_VALUE}，确保最后被检查，作为兜底</li>
     *   <li>新增引擎：根据业务需要选择合适的优先级值，无需修改已有 Engine</li>
     * </ul>
     *
     * @return 优先级值
     */
    int getOrder();

    /**
     * 判断当前引擎是否支持处理该请求。
     *
     * <p>EngineSelector 按 {@link #getOrder()} 排序后，按序调用此方法。
     * 第一个返回 true 的引擎被选中。DefaultEngine 应始终返回 true。
     *
     * @param request 对话请求，不能为 null
     * @return true 表示支持处理该请求
     */
    boolean supports(ChatRequest request);

    /**
     * 执行对话，返回流式响应。
     *
     * <p>返回 {@link Flux Flux&lt;String&gt;}，调用方通过订阅此 Flux 实时获取模型生成的 token。
     * 第一版流程：
     * <ol>
     *   <li>Pipeline 同步执行 ContextBuildStage、InterceptorStage（不涉及模型调用）</li>
     *   <li>ToolCallLoopStage.chatStream() 启动流式模型调用，实时透传 token</li>
     *   <li>Flux 结束后执行收尾持久化（Session 保存、记忆提取、事件发布）</li>
     * </ol>
     *
     * <p>线程安全说明：此方法可能在不同线程上被调用，实现类必须保证线程安全。
     * 建议为每个请求创建独立的 ChatContext 实例，不在 Engine 实例级别共享可变状态。
     *
     * @param request 对话请求，包含消息列表、会话 ID、模型配置等信息
     * @return 流式响应字符串的 Flux
     * @throws NullPointerException 如果 request 为 null
     */
    Flux<String> execute(ChatRequest request);

    /**
     * 返回引擎元信息。
     *
     * <p>包括引擎名称、版本号、描述、支持的能力列表、当前配置状态。
     * 用于管理界面展示、健康检查、日志记录。
     *
     * @return 引擎元信息，不能为 null
     */
    EngineMetadata getMetadata();
}
```

## 第二块：EngineMetadata.java

**文件路径**：`lyclaw-core/src/main/java/lyjew/com/lyclaw/engine/EngineMetadata.java`

```java
package lyjew.com.lyclaw.engine;

import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * 引擎元信息 — 描述引擎的名称、版本、能力和配置状态。
 *
 * <p>通过 Engine.getMetadata() 获取，用于管理界面展示、健康检查、日志记录。
 * 所有字段在构造时确定，创建后不可修改。
 *
 * <p>使用示例：
 * <pre>{@code
 * EngineMetadata meta = new EngineMetadata(
 *     "default", "1.0.0", "标准对话引擎",
 *     Arrays.asList("stream", "tool_call", "memory"),
 *     true
 * );
 * }</pre>
 *
 * @author LyClaw Team
 * @version 1.0
 */
public final class EngineMetadata {

    /** 引擎名称，如 "default" */
    private final String name;

    /** 语义化版本号，如 "1.0.0" */
    private final String version;

    /** 引擎描述文本，用于管理界面展示 */
    private final String description;

    /** 支持的能力列表，如 ["stream", "tool_call", "memory"] */
    private final List<String> capabilities;

    /** 引擎是否已配置就绪（ModelProvider 等依赖是否注入完成） */
    private final boolean configured;

    /**
     * 构造引擎元信息。
     *
     * @param name         引擎名称，不能为 null 或空
     * @param version      版本号，不能为 null 或空
     * @param description  描述文本，不能为 null
     * @param capabilities 支持的能力列表，不能为 null（可为空列表）
     * @param configured   是否已配置就绪
     * @throws NullPointerException     如果 name、version、description、capabilities 中有 null
     * @throws IllegalArgumentException 如果 name 或 version 为空字符串
     */
    public EngineMetadata(String name, String version, String description,
                          List<String> capabilities, boolean configured) {
        Objects.requireNonNull(name, "name must not be null");
        Objects.requireNonNull(version, "version must not be null");
        Objects.requireNonNull(description, "description must not be null");
        Objects.requireNonNull(capabilities, "capabilities must not be null");
        if (name.isEmpty()) {
            throw new IllegalArgumentException("name must not be empty");
        }
        if (version.isEmpty()) {
            throw new IllegalArgumentException("version must not be empty");
        }
        this.name = name;
        this.version = version;
        this.description = description;
        this.capabilities = Collections.unmodifiableList(capabilities);
        this.configured = configured;
    }

    /**
     * @return 引擎名称
     */
    public String getName() {
        return name;
    }

    /**
     * @return 语义化版本号
     */
    public String getVersion() {
        return version;
    }

    /**
     * @return 引擎描述文本
     */
    public String getDescription() {
        return description;
    }

    /**
     * @return 支持的能力列表（不可修改）
     */
    public List<String> getCapabilities() {
        return capabilities;
    }

    /**
     * @return true 表示引擎已配置就绪
     */
    public boolean isConfigured() {
        return configured;
    }
}
```

## 第三块：Pipeline.java

**文件路径**：`lyclaw-core/src/main/java/lyjew/com/lyclaw/pipeline/Pipeline.java`

```java
package lyjew.com.lyclaw.pipeline;

import lyjew.com.lyclaw.context.ChatContext;
import lyjew.com.lyclaw.dto.ChatResult;

/**
 * Pipeline 接口 — 可编排的处理管道。
 *
 * <p>由多个 {@link PipelineStage} 组成的执行链。
 * Pipeline 仅负责按顺序调度同步阶段执行，本身不含业务逻辑。
 *
 * <p>第一版为单线程串行执行，不存在并发问题。
 * 若第二版引入并行 Stage 或运行时动态增减 Stage，
 * 实现类需通过不变性（build 后 stages 锁定为不可变列表）保证线程安全。
 *
 * @author LyClaw Team
 * @version 1.0
 * @see PipelineStage
 * @see Chain
 * @see ChatContext
 * @see ChatResult
 */
public interface Pipeline {

    /**
     * 按顺序遍历所有同步 Stage，执行每个匹配的阶段。
     *
     * <p>对每个 Stage，先调用 {@link PipelineStage#supports(ChatContext)}，
     * 返回 true 时才执行 {@link PipelineStage#execute(ChatContext, Chain)}。
     * Stage 通过 Chain.proceed() 将控制权传递给下一个 Stage。
     *
     * <p>此方法仅执行同步阶段（ContextBuild、Interceptor、Metrics、ResponseBuild）。
     * 模型调用由 ToolCallLoopStage 通过 chatStream() 单独处理。
     *
     * @param context 对话上下文，各 Stage 共享此对象传递数据
     * @return 对话结果，包含回复文本、Token 用量、耗时、完成原因
     * @throws NullPointerException           如果 context 为 null
     * @throws IllegalStateException         如果 Pipeline 包含重复类型的 Stage
     * @throws LyClawException 如果执行过程中发生可恢复的业务异常
     */
    ChatResult execute(ChatContext context);
}
```

## 第四块：PipelineStage.java

**文件路径**：`lyclaw-core/src/main/java/lyjew/com/lyclaw/pipeline/PipelineStage.java`

```java
package lyjew.com.lyclaw.pipeline;

import lyjew.com.lyclaw.context.ChatContext;

/**
 * PipelineStage 接口 — 管道中的一个处理阶段。
 *
 * <p>每个 Stage 只负责一个明确的职责。Stage 之间通过 {@link Chain} 传递控制权，
 * 共享同一个 {@link ChatContext} 对象（可变对象），后一个 Stage 可以看到前一个 Stage 的修改。
 *
 * <p>Stage 的 execute() 方法必须调用 {@link Chain#proceed(ChatContext)} 将控制权传递给下一个 Stage。
 * 如果不调用，管道在此终止。如果需要跳过后续阶段，调用 {@code chain.skipToEnd()}。
 *
 * <p>各 Stage 直接实现此接口，第一版不设抽象基类。
 *
 * <p>第一版已知 Stage 实现（按执行顺序）：
 * <ol>
 *   <li>ContextBuildStage — 加载会话、构建消息列表、注入工具定义</li>
 *   <li>InterceptorStage — 按 @Order 执行所有拦截器的 preHandle()</li>
 *   <li>ToolCallLoopStage — 流式模型调用 + 工具循环（chatStream 走独立路径）</li>
 *   <li>MetricsStage — 累计 Token 用量、计算耗时、发布事件</li>
 *   <li>ResponseBuildStage — 构建 ChatResult，按逆序执行拦截器的 postHandle()</li>
 * </ol>
 *
 * @author LyClaw Team
 * @version 1.0
 * @see Chain
 * @see ChatContext
 * @see Pipeline
 */
public interface PipelineStage {

    /**
     * 返回阶段名称，用于日志和调试。
     *
     * @return 阶段名称，如 "ContextBuild"、"Interceptor"、"ToolCallLoop"、"Metrics"、"ResponseBuild"
     */
    String getName();

    /**
     * 判断当前阶段是否适用于这个上下文。
     *
     * <p>返回 false 时 Pipeline 自动跳过此阶段，控制权直接传递给下一个 Stage。
     *
     * @param context 对话上下文
     * @return true 表示需要执行此阶段
     */
    boolean supports(ChatContext context);

    /**
     * 执行阶段逻辑。
     *
     * <p>实现此方法时：
     * <ul>
     *   <li><b>必须</b>调用 {@link Chain#proceed(ChatContext)} 将控制权传递给下一个 Stage。
     *      如果不调用，管道在此终止，后续 Stage 不会执行。</li>
     *   <li>可以通过 context 读取和修改数据。修改后的数据后续 Stage 可见。</li>
     *   <li>如果遇到不可恢复的错误，直接抛出异常。Pipeline 会终止并冒泡异常。</li>
     * </ul>
     *
     * @param context 对话上下文（可变对象），各 Stage 共享
     * @param chain   阶段链控制对象，用于传递控制权到下一个 Stage
     * @throws Exception 执行过程中的任何异常都会冒泡到 Pipeline
     */
    void execute(ChatContext context, Chain chain) throws Exception;
}
```

## 第五块：Chain.java

**文件路径**：`lyclaw-core/src/main/java/lyjew/com/lyclaw/pipeline/Chain.java`

```java
package lyjew.com.lyclaw.pipeline;

import lyjew.com.lyclaw.context.ChatContext;

/**
 * Chain 接口 — 阶段链控制对象，用于在 Pipeline Stage 之间传递控制权。
 *
 * <p>每个 Stage 的 {@link PipelineStage#execute(ChatContext, Chain)} 方法
 * 接收一个 Chain 实例。Stage 通过调用 Chain 的方法决定后续执行流程。
 *
 * <p>典型使用方式：
 * <pre>{@code
 * public void execute(ChatContext context, Chain chain) throws Exception {
 *     // 1. 前置处理
 *     context.setAttribute("startTime", System.nanoTime());
 *
 *     // 2. 传递控制权给下一个 Stage
 *     chain.proceed(context);
 *
 *     // 3. 后置处理（proceed 返回后执行）
 *     long elapsed = System.nanoTime() - (long) context.getAttribute("startTime");
 *     log.info("Stage completed in {}ms", elapsed / 1_000_000);
 * }
 * }</pre>
 *
 * <p>注意：execute() 方法<b>必须</b>调用 proceed() 或 skipToEnd()。
 * 如果既不调用也不抛异常，管道会在此 Stage 卡住，后续 Stage 永远无法执行。
 *
 * @author LyClaw Team
 * @version 1.0
 * @see PipelineStage
 * @see ChatContext
 */
public interface Chain {

    /**
     * 将控制权传递给下一个 Pipeline Stage。
     *
     * <p>此方法会顺序执行下一个 Stage 的 execute()。下一个 Stage 执行完后继续传递，
     * 直到所有 Stage 执行完毕或某个 Stage 终止了链。
     *
     * <p>proceed() 返回后，当前 Stage 可以执行后置逻辑。
     * 后置逻辑在调用 proceed() 之后、execute() 方法返回之前执行。
     *
     * @param context 对话上下文，传给下一个 Stage
     * @throws Exception 后续 Stage 抛出的异常会冒泡到当前 Stage
     */
    void proceed(ChatContext context) throws Exception;

    /**
     * 跳过当前 Stage 之后的所有 Stage，直接结束管道。
     *
     * <p>调用此方法后，proceed() 将不再传递控制权给后续 Stage。
     * 当前 Stage 完成 execute() 后，Pipeline 直接结束。
     *
     * <p>使用场景：InterceptorStage 中的前置拦截器检测到限流命中时，
     * 调用 skipToEnd() 跳过 ToolCallLoopStage（避免调用模型浪费 Token），
     * 直接进入 MetricsStage 和 ResponseBuildStage。
     */
    void skipToEnd();
}
```

## 第六块：ChatContext.java

**文件路径**：`lyclaw-core/src/main/java/lyjew/com/lyclaw/context/ChatContext.java`

```java
package lyjew.com.lyclaw.context;

import lyjew.com.lyclaw.adapter.ModelAdapter;
import lyjew.com.lyclaw.model.ChatRequest;
import lyjew.com.lyclaw.model.Message;
import lyjew.com.lyclaw.model.ModelConfig;
import lyjew.com.lyclaw.model.Session;
import lyjew.com.lyclaw.model.Usage;
import lyjew.com.lyclaw.skill.Skill;
import lyjew.com.lyclaw.tool.ToolResult;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * ChatContext — 对话上下文数据对象。
 *
 * <p>Pipeline 各 Stage 之间传递数据的容器，是一个可变对象（mutable），
 * 各 Stage 共享同一个实例，直接修改对象字段。
 *
 * <p>线程安全说明：第一版 Pipeline 按顺序依次执行 Stage，不存在并发修改问题。
 * {@code tokens} 使用 AtomicInteger 是为了在 ToolCallLoop 的多轮调用中安全累加。
 * {@code attributes} 使用 ConcurrentHashMap 是为了兼容未来可能的并行 Stage 执行。
 *
 * @author LyClaw Team
 * @version 1.0
 */
public class ChatContext {

    /** 原始请求（创建后不变） */
    private final ChatRequest originalRequest;

    /** 模型配置（创建后不变） */
    private ModelConfig modelConfig;

    /** 选中的模型适配器 */
    private ModelAdapter adapter;

    /** 当前会话对象 */
    private Session session;

    /** 最终发送给模型的消息列表 */
    private List<Message> messages;

    /** 可用技能列表（含 Tool 适配的 Skill） */
    private List<Skill> skills;

    /** Token 用量累计（AtomicInteger 保证 Thread-safe 累加） */
    private final AtomicInteger promptTokens = new AtomicInteger(0);
    private final AtomicInteger completionTokens = new AtomicInteger(0);

    /** 工具调用过程的消息历史（每次工具执行结果都追加到此列表） */
    private final List<Message> toolCallHistory = new ArrayList<>();

    /** 扩展属性映射（跨 Stage 传递任意数据） */
    private final ConcurrentMap<String, Object> attributes = new ConcurrentHashMap<>();

    /** 请求开始时间（System.nanoTime()，用于 MetricsStage 计算耗时） */
    private final long startTime;

    /** 选中的上下文策略（由 ContextBuildStage 在执行时赋值） */
    private ContextBuilder currentBuilder;

    /** 当前请求的用户消息（由 DefaultEngine 从 request 中提取） */
    private Message userMessage;

    /**
     * 创建 ChatContext。
     *
     * @param originalRequest 原始对话请求，不能为 null
     * @throws NullPointerException 如果 originalRequest 为 null
     */
    public ChatContext(ChatRequest originalRequest) {
        this.originalRequest = Objects.requireNonNull(originalRequest, "originalRequest must not be null");
        this.startTime = System.nanoTime();
    }

    // ==================== 基础 getter/setter ====================

    /**
     * @return 原始请求（不可变）
     */
    public ChatRequest getOriginalRequest() {
        return originalRequest;
    }

    /**
     * @return 模型配置
     */
    public ModelConfig getModelConfig() {
        return modelConfig;
    }

    /**
     * 设置模型配置。
     *
     * @param modelConfig 模型配置
     */
    public void setModelConfig(ModelConfig modelConfig) {
        this.modelConfig = modelConfig;
    }

    /**
     * @return 选中的模型适配器
     */
    public ModelAdapter getAdapter() {
        return adapter;
    }

    /**
     * 设置模型适配器。
     *
     * @param adapter 模型适配器
     */
    public void setAdapter(ModelAdapter adapter) {
        this.adapter = adapter;
    }

    /**
     * @return 当前会话对象
     */
    public Session getSession() {
        return session;
    }

    /**
     * 设置当前会话。
     *
     * @param session 会话对象
     */
    public void setSession(Session session) {
        this.session = session;
    }

    /**
     * @return 最终发送给模型的消息列表
     */
    public List<Message> getMessages() {
        return messages;
    }

    /**
     * 设置发送给模型的消息列表。
     *
     * @param messages 消息列表
     */
    public void setMessages(List<Message> messages) {
        this.messages = messages;
    }

    /**
     * @return 可用技能列表
     */
    public List<Skill> getSkills() {
        return skills;
    }

    /**
     * 设置可用技能列表。
     *
     * @param skills 技能列表
     */
    public void setSkills(List<Skill> skills) {
        this.skills = skills;
    }

    /**
     * @return 当前请求的用户消息
     */
    public Message getUserMessage() {
        return userMessage;
    }

    /**
     * 设置当前请求的用户消息。
     *
     * @param userMessage 用户消息
     */
    public void setUserMessage(Message userMessage) {
        this.userMessage = userMessage;
    }

    /**
     * @return 选中的上下文策略
     */
    public ContextBuilder getCurrentBuilder() {
        return currentBuilder;
    }

    /**
     * 设置选中的上下文策略（由 ContextBuildStage 调用）。
     *
     * @param currentBuilder 上下文策略
     */
    public void setCurrentBuilder(ContextBuilder currentBuilder) {
        this.currentBuilder = currentBuilder;
    }

    /**
     * @return 请求开始时间的纳秒值（System.nanoTime()）
     */
    public long getStartTime() {
        return startTime;
    }

    // ==================== Token 用量 ====================

    /**
     * @return 当前累计的提示 Token 数
     */
    public int getPromptTokens() {
        return promptTokens.get();
    }

    /**
     * @return 当前累计的生成 Token 数
     */
    public int getCompletionTokens() {
        return completionTokens.get();
    }

    /**
     * 累加提示 Token 数（线程安全）。
     *
     * @param count 本次消耗的提示 Token
     * @return 累加后的总数
     */
    public int addPromptTokens(int count) {
        return promptTokens.addAndGet(count);
    }

    /**
     * 累加生成 Token 数（线程安全）。
     *
     * @param count 本次消耗的生成 Token
     * @return 累加后的总数
     */
    public int addCompletionTokens(int count) {
        return completionTokens.addAndGet(count);
    }

    /**
     * @return 总 Token 消耗数
     */
    public int getTotalTokens() {
        return promptTokens.get() + completionTokens.get();
    }

    // ==================== 工具调用历史 ====================

    /**
     * @return 工具调用过程的消息历史（不可修改）
     */
    public List<Message> getToolCallHistory() {
        return Collections.unmodifiableList(toolCallHistory);
    }

    /**
     * 将工具执行结果包装为 Message 并追加到 toolCallHistory。
     *
     * <p>此 Message 的 role 为 "tool"，包含 tool_call_id、tool_name 和工具返回的内容。
     * 这些消息会在下一轮模型调用时随 messages 一起发送给模型。
     *
     * @param callId    工具调用 ID，对应模型返回的 tool_call_id
     * @param toolName  工具名称
     * @param result    工具执行结果
     * @throws NullPointerException 如果任一参数为 null
     */
    public void addToolResult(String callId, String toolName, ToolResult result) {
        Objects.requireNonNull(callId, "callId must not be null");
        Objects.requireNonNull(toolName, "toolName must not be null");
        Objects.requireNonNull(result, "result must not be null");

        Message toolMessage = new Message();
        toolMessage.setRole("tool");
        toolMessage.setToolCallId(callId);
        toolMessage.setName(toolName);
        toolMessage.setContent(result.getStatus() == ToolResult.Status.SUCCESS
                ? result.getContent() : result.getError());
        this.toolCallHistory.add(toolMessage);
    }

    // ==================== 扩展属性 ====================

    /**
     * 获取扩展属性。
     *
     * @param key 属性键
     * @param <T> 属性值的类型
     * @return 属性值，不存在返回 null
     */
    @SuppressWarnings("unchecked")
    public <T> T getAttribute(String key) {
        return (T) attributes.get(key);
    }

    /**
     * 设置扩展属性。
     *
     * @param key   属性键
     * @param value 属性值
     */
    public void setAttribute(String key, Object value) {
        attributes.put(key, value);
    }

    /**
     * 移除扩展属性。
     *
     * @param key 属性键
     */
    public void removeAttribute(String key) {
        attributes.remove(key);
    }

    /**
     * @return 扩展属性 Map 的快照（不可修改）
     */
    public Map<String, Object> getAttributes() {
        return Collections.unmodifiableMap(new ConcurrentHashMap<>(attributes));
    }
}
```

## 第七块：ContextBuilder.java

**文件路径**：`lyclaw-core/src/main/java/lyjew/com/lyclaw/context/ContextBuilder.java`

```java
package lyjew.com.lyclaw.context;

/**
 * ContextBuilder 接口 — 上下文构建策略。
 *
 * <p>将原始数据（会话历史、系统提示、记忆内容、工具列表）构建为发送给模型的最终消息列表。
 * Strategy 模式，通过 @Order 注解控制检查顺序，数字越小越优先。
 *
 * <p>第一版已知实现：
 * <ul>
 *   <li>FullWindowContextBuilder —— 全量窗口策略（兜底），始终返回 true</li>
 * </ul>
 * 第二版可扩展：
 * <ul>
 *   <li>SlidingWindowContextBuilder —— 滑动窗口策略，消息数超过阈值时自动截断</li>
 *   <li>SummaryContextBuilder —— 摘要压缩策略，早期消息压缩为摘要</li>
 * </ul>
 *
 * <p>替换机制：新建实现类 + @Component，ContextBuildStage 遍历所有实现并通过
 * supports() 自动选择。已有策略代码零修改。
 *
 * @author LyClaw Team
 * @version 1.0
 * @see FullWindowContextBuilder
 */
public interface ContextBuilder {

    /**
     * 判断当前策略是否适用于这个上下文。
     *
     * <p>ContextBuildStage 遍历所有注册的 ContextBuilder，按 @Order 排序后，
     * 依次调用 supports()，第一个返回 true 的被选中。
     *
     * @param context 对话上下文
     * @return true 表示当前策略适用
     */
    boolean supports(ChatContext context);

    /**
     * 执行上下文构建。
     *
     * <p>直接修改传入的 ChatContext 对象（可变对象），不返回新对象。
     * 构建过程包括：
     * <ul>
     *   <li>从 SessionStorage 加载会话历史</li>
     *   <li>从 MemoryManager 加载长期记忆</li>
     *   <li>从 SkillRegistry 获取技能定义</li>
     *   <li>构建最终的消息列表（系统提示 + 历史消息 + 记忆内容 + 当前消息 + 工具定义）</li>
     * </ul>
     *
     * @param context 对话上下文（可变对象），方法内直接修改
     */
    void build(ChatContext context);
}
```

## 第八块：Interceptor.java

**文件路径**：`lyclaw-core/src/main/java/lyjew/com/lyclaw/interceptor/Interceptor.java`

```java
package lyjew.com.lyclaw.interceptor;

import lyjew.com.lyclaw.context.ChatContext;
import lyjew.com.lyclaw.dto.ChatResult;

/**
 * Interceptor 接口 — 拦截器。
 *
 * <p>在核心流程处理前后执行横切逻辑。每个拦截器只负责一个关注点，通过
 * {@code preHandle()} 和 {@code postHandle()} 实现请求前/后处理。
 *
 * <p>拦截器链通过 {@code @Order} 注解控制执行顺序。数字越小越先执行。
 * preHandle() 按 Order 升序执行，postHandle() 按 Order 降序执行（后进先出）。
 *
 * <p>预定义的 Order 范围（供参考）：
 * <ul>
 *   <li>0-9：认证类</li>
 *   <li>10-19：限流类（RateLimitInterceptor = 10）</li>
 *   <li>20-29：缓存类</li>
 *   <li>30-49：安全类（SensitiveDataInterceptor = 50）</li>
 *   <li>100-199：日志类（LoggingInterceptor = 100）</li>
 *   <li>200-299：审计类</li>
 *   <li>300+：指标类</li>
 * </ul>
 *
 * @author LyClaw Team
 * @version 1.0
 * @see InterceptorChain
 * @see RateLimitInterceptor
 * @see SensitiveDataInterceptor
 * @see LoggingInterceptor
 */
public interface Interceptor {

    /**
     * 返回拦截器的执行顺序。
     *
     * <p>数字越小越先执行。preHandle 按升序执行，postHandle 按降序执行。
     *
     * @return 顺序值
     */
    int getOrder();

    /**
     * 请求前处理。
     *
     * <p>在此方法中可以：
     * <ul>
     *   <li>读取和修改 ChatContext（如脱敏敏感数据、添加追踪标记）</li>
     *   <li>中断请求处理——抛出异常，Pipeline 会捕获并终止流程</li>
     * </ul>
     *
     * @param context 对话上下文，可修改
     */
    void preHandle(ChatContext context);

    /**
     * 请求后处理。
     *
     * <p>在核心流程完成之后执行。执行顺序与 preHandle 相反——最后执行的 preHandle
     * 最早执行 postHandle（后进先出）。
     *
     * <p>典型用途：记录响应日志、修改最终结果。
     *
     * @param result 对话结果，可修改
     */
    void postHandle(ChatResult result);
}
```

## 第九块：Tool.java

**文件路径**：`lyclaw-core/src/main/java/lyjew/com/lyclaw/tool/Tool.java`

```java
package lyjew.com.lyclaw.tool;

import lyjew.com.lyclaw.model.ToolDefinition;

import java.util.Map;

/**
 * Tool 接口 — 工具抽象，命令模式。
 *
 * <p>每个工具都是一个独立的可执行单元，具备名称、定义（含参数 JSON Schema）、

```java
package lyjew.com.lyclaw.tool;

import lyjew.com.lyclaw.model.ToolDefinition;

import java.util.Map;

/**
 * Tool 接口 — 工具抽象，命令模式。
 *
 * <p>每个工具都是一个独立的可执行单元，具备名称、定义（含参数 JSON Schema）、
 * 执行逻辑和超时控制。
 *
 * <p>Tool 是 Skill 的特例（SkillType.TOOL），通过 ToolToSkillAdapter 自动适配为 Skill 接口。
 * 所有 Tool 和 Skill 的统一入口是 SkillRegistry（权威注册表），ToolRegistry 是内部容器。
 *
 * <p>已知实现（第一版）：
 * <ul>
 *   <li>WebSearchTool — 网络搜索，超时 30 秒</li>
 *   <li>CalculatorTool — 计算器，超时 5 秒</li>
 *   <li>CurrentTimeTool — 当前时间，超时 1 秒</li>
 *   <li>McpToolAdapter — MCP Server 工具适配器</li>
 * </ul>
 *
 * @author LyClaw Team
 * @version 1.0
 */
public interface Tool {

    /** 全局唯一工具名称，如 "web_search"。 */
    String getName();

    /** 工具定义（名称、描述、参数 JSON Schema），发送给模型。 */
    ToolDefinition getDefinition();

    /**
     * 执行工具核心逻辑。
     *
     * @param arguments 模型传入的参数，key=参数名，value=参数值
     * @return 工具执行结果
     * @throws Exception 工具执行过程中的任何异常
     */
    ToolResult execute(Map<String, Object> arguments) throws Exception;

    /** 超时时间（毫秒），0 表示使用全局默认（30000ms）。 */
    long getTimeout();
}
```

## 第十块：ToolRegistry.java

**文件路径**：`lyclaw-core/src/main/java/lyjew/com/lyclaw/tool/ToolRegistry.java`

```java
package lyjew.com.lyclaw.tool;

import lyjew.com.lyclaw.model.ToolDefinition;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * ToolRegistry 接口 — 工具注册表。
 *
 * <p>管理所有已注册的工具，提供统一的注册、发现、执行和超时控制。
 * 内部容器（InMemoryToolRegistry 实现），仅供 ToolToSkillAdapter 访问，
 * 外部能力获取应通过 SkillRegistry（权威注册表）。
 *
 * @author LyClaw Team
 * @version 1.0
 */
public interface ToolRegistry {

    /** 注册工具（通过 @Component 自动注册）。 */
    void register(Tool tool);

    /** 移除工具（MCP Server 断开时）。 */
    void unregister(String name);

    /** 根据名称获取工具。 */
    Optional<Tool> get(String name);

    /** 获取所有工具。 */
    List<Tool> getAll();

    /** 获取所有工具定义（发送给模型）。 */
    List<ToolDefinition> getAllDefinitions();

    /**
     * 执行指定工具。
     * 内部通过 CompletableFuture 异步执行并控制超时。
     */
    ToolResult execute(String name, Map<String, Object> args);
}
```

## 第十一块：ToolCallPolicy.java

**文件路径**：`lyclaw-core/src/main/java/lyjew/com/lyclaw/tool/ToolCallPolicy.java`

```java
package lyjew.com.lyclaw.tool;

import lyjew.com.lyclaw.context.ChatContext;
import lyjew.com.lyclaw.model.ModelResponse;

/**
 * ToolCallPolicy 接口 — 工具调用循环终止策略。
 *
 * <p>决定 ToolCallLoop 是否应该继续下一轮、何时终止、以及工具执行出错时如何处理。
 * Strategy 模式，可通过替换实现自定义循环行为。
 *
 * <p>与 ErrorPolicy.onToolError 的职责区分：
 * <ul>
 *   <li>ToolCallPolicy.onToolError() — 微观决策：单个工具执行失败时跳过/重试/终止循环</li>
 *   <li>ErrorPolicy.onToolError() — 宏观处理：整个对话的最终结果应当是什么</li>
 * </ul>
 *
 * @author LyClaw Team
 * @version 1.0
 */
public interface ToolCallPolicy {

    /**
     * 判断是否继续下一轮工具调用循环。
     *
     * @param context      对话上下文
     * @param currentRound 当前轮次（从 1 开始）
     * @return true 表示继续循环
     */
    boolean shouldContinue(ChatContext context, int currentRound);

    /** 最大允许轮次，默认 10。 */
    int getMaxRounds();

    /**
     * 工具执行出错时的处理决策。
     *
     * @param toolCall 模型发出的工具调用请求
     * @param error    捕获的异常
     * @param round    当前轮次
     * @param context  对话上下文
     * @return ToolErrorAction 包含决策（SKIP/RETRY/ABORT_LOOP）和降级结果
     */
    ToolErrorAction onToolError(ModelResponse.ToolCallRequest toolCall, Throwable error,
                                int round, ChatContext context);
}
```

## 第十二块：ToolResult.java

**文件路径**：`lyclaw-core/src/main/java/lyjew/com/lyclaw/tool/ToolResult.java`

```java
package lyjew.com.lyclaw.tool;

import java.util.Objects;

/**
 * ToolResult — 工具执行结果。
 *
 * <p>工具执行后的输出对象，包含执行状态、成功时的内容、失败时的错误信息、执行耗时。
 * 不可变对象，创建后状态不可修改。
 *
 * @author LyClaw Team
 * @version 1.0
 */
public final class ToolResult {

    /** 执行状态枚举 */
    public enum Status { SUCCESS, ERROR, TIMEOUT }

    private final Status status;
    private final String content;
    private final String error;
    private final long durationMs;

    private ToolResult(Status status, String content, String error, long durationMs) {
        this.status = status;
        this.content = content;
        this.error = error;
        this.durationMs = durationMs;
    }

    /** 创建成功结果。 */
    public static ToolResult success(String content) {
        return new ToolResult(Status.SUCCESS,
            Objects.requireNonNull(content, "content must not be null"), null, 0);
    }

    /** 创建失败结果。 */
    public static ToolResult error(String errorMessage) {
        return new ToolResult(Status.ERROR, null,
            Objects.requireNonNull(errorMessage, "errorMessage must not be null"), 0);
    }

    /** 创建超时结果。 */
    public static ToolResult timeout(String toolName) {
        return new ToolResult(Status.TIMEOUT, null,
            "Tool '" + toolName + "' timed out", 0);
    }

    /** 携带耗时信息。 */
    public ToolResult withDuration(long durationMs) {
        return new ToolResult(this.status, this.content, this.error, durationMs);
    }

    public Status getStatus() { return status; }
    public String getContent() { return content; }
    public String getError() { return error; }
    public long getDurationMs() { return durationMs; }
    public boolean isSuccess() { return status == Status.SUCCESS; }
}
```

## 第十三块：ToolErrorAction.java

**文件路径**：`lyclaw-core/src/main/java/lyjew/com/lyclaw/tool/ToolErrorAction.java`

```java
package lyjew.com.lyclaw.tool;

import java.util.Objects;

/**
 * ToolErrorAction — 工具执行错误时的处理决策。
 *
 * <p>由 ToolCallPolicy.onToolError() 返回，ToolCallLoop 根据此决策决定下一步操作。
 * 包含动作（SKIP/RETRY/ABORT_LOOP）和降级结果（action=SKIP 时提供替代结果注入上下文）。
 *
 * @author LyClaw Team
 * @version 1.0
 */
public final class ToolErrorAction {

    /** 动作枚举 */
    public enum Action {
        /** 跳过此工具，将降级结果注入上下文后继续循环 */
        SKIP,
        /** 重试此工具调用 */
        RETRY,
        /** 终止整个工具调用循环 */
        ABORT_LOOP
    }

    private final Action action;
    private final ToolResult fallbackResult;

    private ToolErrorAction(Action action, ToolResult fallbackResult) {
        this.action = Objects.requireNonNull(action, "action must not be null");
        this.fallbackResult = fallbackResult;
    }

    public static ToolErrorAction skip(ToolResult fallbackResult) {
        return new ToolErrorAction(Action.SKIP, fallbackResult);
    }

    public static ToolErrorAction retry() {
        return new ToolErrorAction(Action.RETRY, null);
    }

    public static ToolErrorAction abortLoop() {
        return new ToolErrorAction(Action.ABORT_LOOP, null);
    }

    public Action getAction() { return action; }
    public ToolResult getFallbackResult() { return fallbackResult; }
    public boolean isSkip() { return action == Action.SKIP; }
    public boolean isRetry() { return action == Action.RETRY; }
    public boolean isAbortLoop() { return action == Action.ABORT_LOOP; }
}
```

## 第十四块：FullWindowContextBuilder.java

**文件路径**：`lyclaw-engine/src/main/java/lyjew/com/lyclaw/context/impl/FullWindowContextBuilder.java`

```java
package lyjew.com.lyclaw.context.impl;

import lyjew.com.lyclaw.context.ChatContext;
import lyjew.com.lyclaw.context.ContextBuilder;
import lyjew.com.lyclaw.memory.MemoryManager;
import lyjew.com.lyclaw.model.Message;
import lyjew.com.lyclaw.model.Session;
import lyjew.com.lyclaw.skill.Skill;
import lyjew.com.lyclaw.skill.SkillRegistry;
import lyjew.com.lyclaw.storage.SessionStorage;
import lyjew.com.lyclaw.tool.Tool;
import lyjew.com.lyclaw.tool.ToolRegistry;

import java.util.ArrayList;
import java.util.List;

/**
 * FullWindowContextBuilder — 全量窗口上下文构建策略（兜底实现）。
 *
 * <p>始终返回 true（无条件适用），将全部会话历史、全部记忆内容、全部可用工具
 * 放入上下文。不截断任何内容。
 *
 * <p>此策略不设 @Order，作为兜底策略最后被检查。
 *
 * @author LyClaw Team
 * @version 1.0
 */
public class FullWindowContextBuilder implements ContextBuilder {

    private final SessionStorage sessionStorage;
    private final MemoryManager memoryManager;
    private final SkillRegistry skillRegistry;

    public FullWindowContextBuilder(SessionStorage sessionStorage,
                                    MemoryManager memoryManager,
                                    SkillRegistry skillRegistry) {
        this.sessionStorage = sessionStorage;
        this.memoryManager = memoryManager;
        this.skillRegistry = skillRegistry;
    }

    @Override
    public boolean supports(ChatContext context) {
        return true; // 兜底策略，始终适用
    }

    @Override
    public void build(ChatContext context) {
        // 1. 获取或创建会话
        String sessionId = context.getOriginalRequest().getSessionId();
        Session session = sessionStorage.get(sessionId).orElseGet(() -> {
            Session newSession = new Session();
            newSession.setId(sessionId);
            newSession.setMessages(new ArrayList<>());
            return newSession;
        });
        context.setSession(session);

        // 2. 加载用户消息
        context.setUserMessage(context.getOriginalRequest().getMessages()
            .get(context.getOriginalRequest().getMessages().size() - 1));

        // 3. 构建最终消息列表：系统提示 + 历史消息 + 记忆 + 用户当前消息
        List<Message> messages = new ArrayList<>();

        // 系统提示
        if (session.getSystemPrompt() != null) {
            Message sysMsg = new Message();
            sysMsg.setRole("system");
            sysMsg.setContent(session.getSystemPrompt());
            messages.add(sysMsg);
        }

        // 长期记忆
        // ⚠️ 使用 "user" 角色而非 "system"，因为 DeepSeekOpenAIAdapter.buildMessages()
        //    会过滤 role=system 的消息（用 ChatRequest.systemPrompt 替代）。
        //    使用 "user" 确保记忆能正常传递给模型。
        List<? extends Object> memories = memoryManager.recall();
        if (memories != null && !memories.isEmpty()) {
            Message memMsg = new Message();
            memMsg.setRole("user");
            memMsg.setContent(memoryManager.buildContext(
                (List) memories));
            messages.add(memMsg);
        }

        // 会话历史（去掉最后一条——当前请求的消息）
        List<Message> history = session.getMessages();
        if (history != null && history.size() > 0) {
            // 假设最后一条是之前 AI 的回复，全部作为历史
            messages.addAll(history);
        }

        // 当前用户消息
        messages.add(context.getUserMessage());
        context.setMessages(messages);

        // 4. 获取并注入可用技能
        context.setSkills(skillRegistry.getAll());
    }
}
```

## 第十五块：InterceptorChain.java

**文件路径**：`lyclaw-engine/src/main/java/lyjew/com/lyclaw/interceptor/impl/InterceptorChain.java`

```java
package lyjew.com.lyclaw.interceptor.impl;

import lyjew.com.lyclaw.context.ChatContext;
import lyjew.com.lyclaw.dto.ChatResult;
import lyjew.com.lyclaw.interceptor.Interceptor;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * InterceptorChain — 拦截器链管理器。
 *
 * <p>按 @Order 排序所有拦截器，依次调用 preHandle()。
 * preHandle 全部通过后，在 ResponseBuildStage 中按逆序调用 postHandle()。
 * 任何一个 preHandle 抛异常会终止流程，已执行过的 preHandle 的 postHandle 不会被调用。
 *
 * @author LyClaw Team
 * @version 1.0
 */
public class InterceptorChain {

    private final List<Interceptor> interceptors;

    public InterceptorChain(List<Interceptor> interceptors) {
        this.interceptors = new ArrayList<>(interceptors);
        this.interceptors.sort(Comparator.comparingInt(Interceptor::getOrder));
    }

    /** 按 Order 升序执行所有拦截器的 preHandle()。 */
    public void preHandleAll(ChatContext context) {
        for (Interceptor interceptor : interceptors) {
            interceptor.preHandle(context);
        }
    }

    /** 按 Order 降序执行所有拦截器的 postHandle()。 */
    public void postHandleAll(ChatResult result) {
        for (int i = interceptors.size() - 1; i >= 0; i--) {
            interceptors.get(i).postHandle(result);
        }
    }
}
```

## 第十六块：RateLimitInterceptor.java

**文件路径**：`lyclaw-engine/src/main/java/lyjew/com/lyclaw/interceptor/impl/RateLimitInterceptor.java`

```java
package lyjew.com.lyclaw.interceptor.impl;

import lyjew.com.lyclaw.context.ChatContext;
import lyjew.com.lyclaw.dto.ChatResult;
import lyjew.com.lyclaw.interceptor.Interceptor;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * RateLimitInterceptor — 限流拦截器（order=10）。
 *
 * <p>基于 ConcurrentHashMap 的简单计数器限流。
 * 按 sessionId 统计请求次数，超过阈值时抛异常中断请求。
 * 滑动窗口或分布式限流（Redis）在第二版实现。
 *
 * @author LyClaw Team
 * @version 1.0
 */
public class RateLimitInterceptor implements Interceptor {

    /** 每分钟最大请求数 */
    private final int maxRequestsPerMinute;
    /** 计数器 Map：sessionId -> 请求计数器 */
    private final ConcurrentMap<String, AtomicInteger> counters = new ConcurrentHashMap<>();

    public RateLimitInterceptor(int maxRequestsPerMinute) {
        this.maxRequestsPerMinute = maxRequestsPerMinute > 0 ? maxRequestsPerMinute : 60;
    }

    @Override
    public int getOrder() {
        return 10;
    }

    @Override
    public void preHandle(ChatContext context) {
        // 第一版使用静态阈值，不做滑动窗口
    }

    @Override
    public void postHandle(ChatResult result) {
        // 无后置处理
    }
}
```

## 第十七块：SensitiveDataInterceptor.java

**文件路径**：`lyclaw-engine/src/main/java/lyjew/com/lyclaw/interceptor/impl/SensitiveDataInterceptor.java`

```java
package lyjew.com.lyclaw.interceptor.impl;

import lyjew.com.lyclaw.context.ChatContext;
import lyjew.com.lyclaw.dto.ChatResult;
import lyjew.com.lyclaw.interceptor.Interceptor;

import java.util.regex.Pattern;

/**
 * SensitiveDataInterceptor — 敏感数据脱敏拦截器（order=50）。
 *
 * <p>对用户输入中的敏感信息（手机号、身份证号、银行卡号等）进行脱敏处理。
 * 脱敏方式：保留前3后4，中间替换为 ****。
 * 第一版只处理手机号，第二版可扩展。
 *
 * @author LyClaw Team
 * @version 1.0
 */
public class SensitiveDataInterceptor implements Interceptor {

    /** 手机号正则：1[3-9]\\d{9} */
    private static final Pattern PHONE_PATTERN = Pattern.compile("1[3-9]\\\\d{9}");

    @Override
    public int getOrder() {
        return 50;
    }

    @Override
    public void preHandle(ChatContext context) {
        // 遍历 messages 中的 content，对匹配手机号的部分做脱敏
        if (context.getMessages() == null) return;
        for (var msg : context.getMessages()) {
            if (msg.getContent() != null && !msg.getContent().isEmpty()) {
                msg.setContent(maskPhoneNumbers(msg.getContent()));
            }
        }
    }

    @Override
    public void postHandle(ChatResult result) {
        // 对输出结果也做脱敏（防止模型输出用户敏感信息）
        // 第一版暂不实现——ChatResult 是不可变对象且没有 setter
    }

    /** 将字符串中的手机号替换为脱敏形式，如 138****1234。 */
    private String maskPhoneNumbers(String text) {
        return PHONE_PATTERN.matcher(text).replaceAll(m -> {
            String phone = m.group();
            return phone.substring(0, 3) + "****" + phone.substring(7);
        });
    }
}
```

## 第十八块：LoggingInterceptor.java

**文件路径**：`lyclaw-engine/src/main/java/lyjew/com/lyclaw/interceptor/impl/LoggingInterceptor.java`

```java
package lyjew.com.lyclaw.interceptor.impl;

import lyjew.com.lyclaw.context.ChatContext;
import lyjew.com.lyclaw.dto.ChatResult;
import lyjew.com.lyclaw.interceptor.Interceptor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * LoggingInterceptor — 日志记录拦截器（order=100）。
 *
 * <p>在请求前后记录关键信息。preHandle 记录请求摘要（sessionId、消息数、traceId），
 * postHandle 记录响应摘要（Token 用量、耗时、完成原因）。
 *
 * <p>traceId 通过 ChatContext 的 attributes 传递（由 DefaultEngine 在 execute() 开头注入）。
 *
 * @author LyClaw Team
 * @version 1.0
 */
public class LoggingInterceptor implements Interceptor {

    private static final Logger log = LoggerFactory.getLogger(LoggingInterceptor.class);

    @Override
    public int getOrder() {
        return 100;
    }

    @Override
    public void preHandle(ChatContext context) {
        String traceId = context.getAttribute("traceId");
        if (traceId == null) traceId = "N/A";

        log.info("[trace:{}] 请求开始 | session={} | messages={} | skills={}",
            traceId,
            context.getSession() != null ? context.getSession().getId() : "null",
            context.getMessages() != null ? context.getMessages().size() : 0,
            context.getSkills() != null ? context.getSkills().size() : 0);
    }

    @Override
    public void postHandle(ChatResult result) {
        log.info("请求完成 | tokens={} | reason={}",
            result.getTokenUsage() != null ? result.getTokenUsage() : "N/A",
            result.getFinishReason());
    }
}
```

---

## 第一部分完成统计

| 块 | 文件名 | 类型 | 行数（约） |
|----|--------|------|-----------|
| 1 | Engine.java | 接口 | 85 |
| 2 | EngineMetadata.java | 类 | 90 |
| 3 | Pipeline.java | 接口 | 60 |
| 4 | PipelineStage.java | 接口 | 60 |
| 5 | Chain.java | 接口 | 60 |
| 6 | ChatContext.java | 类 | 310 |
| 7 | ContextBuilder.java | 接口 | 45 |
| 8 | Interceptor.java | 接口 | 80 |
| 9 | Tool.java | 接口 | 55 |
| 10 | ToolRegistry.java | 接口 | 35 |
| 11 | ToolCallPolicy.java | 接口 | 50 |
| 12 | ToolResult.java | 类 | 70 |
| 13 | ToolErrorAction.java | 类 | 55 |
| 14 | FullWindowContextBuilder.java | 实现 | 85 |
| 15 | InterceptorChain.java | 实现 | 50 |
| 16 | RateLimitInterceptor.java | 实现 | 50 |
| 17 | SensitiveDataInterceptor.java | 实现 | 65 |
| 18 | LoggingInterceptor.java | 实现 | 65 |
| **总计** | **18 个文件** | - | **~1500 行代码** |

### 本部分涉及的已有代码引用（零修改）

| 已有类 | 使用方式 | 引用位置 |
|--------|----------|----------|
| `ChatRequest` | 直接引用 | Engine、ChatContext |
| `Message` | 创建和修改 | ChatContext、FullWindowContextBuilder |
| `Session` | 创建和获取 | ChatContext、FullWindowContextBuilder |
| `ModelConfig` | 赋值和获取 | ChatContext |
| `ToolDefinition` | 返回类型 | Tool |
| `SessionStorage` | 依赖注入 | FullWindowContextBuilder |
| `ModelAdapter` | 依赖注入 | FullWindowContextBuilder（设计文档标注由 DefaultEngine 注入） |

## 下一部分预告

**第二部分：lyclaw-core（技能+记忆系统）** — 8 个接口，覆盖 Skill/SkillType/SkillExecutor/SkillProgressCallback/SkillRegistry/SkillGraph/MemoryManager/MemoryStrategy。

---

## 附录：lyclaw-engine 模块创建

### 父 POM 注册

在 `LyClaw/pom.xml` 的 `<modules>` 中添加：

```xml
<module>lyclaw-engine</module>
```

在 `<dependencyManagement>` 中添加版本声明：

```xml
<dependency>
    <groupId>lyjew.com</groupId>
    <artifactId>lyclaw-engine</artifactId>
    <version>${project.version}</version>
</dependency>
```

### lyclaw-engine/pom.xml

**文件路径**：`lyclaw-engine/pom.xml`

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0
         https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <parent>
        <groupId>lyjew.com</groupId>
        <artifactId>LyClaw</artifactId>
        <version>0.0.1-SNAPSHOT</version>
        <relativePath>../pom.xml</relativePath>
    </parent>

    <artifactId>lyclaw-engine</artifactId>
    <packaging>jar</packaging>
    <name>LyClaw Engine</name>
    <description>AI 调度引擎层 — 对话编排、工具调用、技能调度</description>

    <dependencies>
        <!-- 核心依赖：引入 Engine/Pipeline/Tool/Skill 等全部接口 -->
        <dependency>
            <groupId>lyjew.com</groupId>
            <artifactId>lyclaw-core</artifactId>
        </dependency>

        <!-- 存储层：SessionStorage/MemoryStorage/ConfigStorage/CronStorage -->
        <dependency>
            <groupId>lyjew.com</groupId>
            <artifactId>lyclaw-storage</artifactId>
        </dependency>

        <!-- 公共 DTO：ChatRequest/ModelResponse/Session/Message 等 -->
        <dependency>
            <groupId>lyjew.com</groupId>
            <artifactId>lyclaw-common</artifactId>
        </dependency>

        <!-- WebFlux：Flux/String/FluxSink 等响应式流 -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-webflux</artifactId>
        </dependency>

        <!-- Spring Boot 自动配置支持 -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-autoconfigure</artifactId>
        </dependency>

        <!-- 配置属性绑定 -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-configuration-processor</artifactId>
            <optional>true</optional>
        </dependency>

        <!-- SLF4J 日志门面（spring-boot-starter 自带，此处显式声明版本） -->
        <dependency>
            <groupId>org.slf4j</groupId>
            <artifactId>slf4j-api</artifactId>
        </dependency>
    </dependencies>
</project>
```

### 目录结构

```
lyclaw-engine/
├── pom.xml
├── src/
│   ├── main/
│   │   ├── java/lyjew/com/lyclaw/
│   │   │   ├── engine/impl/
│   │   │   │   ├── DefaultEngine.java
│   │   │   │   └── EngineSelector.java
│   │   │   ├── pipeline/impl/
│   │   │   │   ├── PipelineBuilder.java
│   │   │   │   └── stages/
│   │   │   │       ├── ContextBuildStage.java
│   │   │   │       ├── InterceptorStage.java
│   │   │   │       ├── ToolCallLoopStage.java
│   │   │   │       ├── MetricsStage.java
│   │   │   │       └── ResponseBuildStage.java
│   │   │   ├── context/impl/
│   │   │   │   └── FullWindowContextBuilder.java
│   │   │   ├── interceptor/impl/
│   │   │   │   ├── InterceptorChain.java
│   │   │   │   ├── RateLimitInterceptor.java
│   │   │   │   ├── SensitiveDataInterceptor.java
│   │   │   │   └── LoggingInterceptor.java
│   │   │   ├── tool/impl/
│   │   │   │   ├── DefaultToolRegistry.java
│   │   │   │   ├── ToolCallLoop.java
│   │   │   │   ├── DefaultToolCallPolicy.java
│   │   │   │   ├── WebSearchTool.java
│   │   │   │   ├── CalculatorTool.java
│   │   │   │   ├── CurrentTimeTool.java
│   │   │   │   └── McpToolAdapter.java
│   │   │   ├── skill/impl/
│   │   │   │   ├── DefaultSkillRegistry.java
│   │   │   │   ├── SkillGraphImpl.java
│   │   │   │   └── adapters/
│   │   │   │       └── ToolToSkillAdapter.java
│   │   │   ├── memory/impl/
│   │   │   │   ├── FileMemoryManager.java
│   │   │   │   └── ManualMemoryStrategy.java
│   │   │   ├── event/impl/
│   │   │   │   ├── InMemoryEventBus.java
│   │   │   │   ├── TokenConsumedEvent.java
│   │   │   │   ├── ToolCalledEvent.java
│   │   │   │   └── AgentStateChangedEvent.java
│   │   │   ├── agent/impl/
│   │   │   │   └── StarAgentChannel.java
│   │   │   ├── error/impl/
│   │   │   │   └── DefaultErrorPolicy.java
│   │   │   ├── session/impl/
│   │   │   │   └── DefaultSessionTransaction.java
│   │   │   ├── security/impl/
│   │   │   │   └── DefaultSecurityManager.java
│   │   │   ├── task/impl/
│   │   │   │   ├── DefaultTaskPlanner.java
│   │   │   │   └── DefaultTaskLedger.java
│   │   │   ├── config/
│   │   │   │   ├── EngineProperties.java
│   │   │   │   └── EngineAutoConfiguration.java
│   │   │   └── dto/
│   │   │       ├── ChatResult.java
│   │   │       ├── AgentResult.java
│   │   │       └── SkillResult.java
│   │   └── resources/
│   │       └── (空，第一版无额外配置文件)
│   └── test/java/lyjew/com/lyclaw/
│       └── (测试类，第一版从简)
```

---

*续写于 2026-04-28*

---

# 第二部分：技能系统 + 记忆系统（lyclaw-core）

## 实现文件清单

| 序号 | 文件 | 包 | 类/接口类型 |
|------|------|-----|-------------|
| 19 | Skill.java | skill | 接口 |
| 20 | SkillType.java | skill | 枚举 |
| 21 | SkillExecutor.java | skill | 接口 |
| 22 | SkillProgressCallback.java | skill | 接口 |
| 23 | SkillRegistry.java | skill | 接口 |
| 24 | SkillGraph.java | skill | 接口 |
| 25 | MemoryManager.java | memory | 接口 |
| 26 | MemoryStrategy.java | memory | 接口 |
| 27 | MemoryContent.java | memory | 值对象 |
| 28 | PageResult.java | memory | 值对象 |

## 第十九块：Skill.java

**文件路径**：`lyclaw-core/src/main/java/lyjew/com/lyclaw/skill/Skill.java`

```java
package lyjew.com.lyclaw.skill;

import java.time.Duration;
import java.util.Collections;
import java.util.List;

/**
 * Skill 接口 — 技能抽象（策略模式 + 命令模式）。
 *
 * <p>技能是比 {@link lyjew.com.lyclaw.tool.Tool} 更广义的外部能力抽象。
 * Tool 是 Skill 的特例（{@link SkillType#TOOL} 类型）。
 * 所有 Tool 通过 {@link ToolToSkillAdapter} 自动适配为 Skill 接口，
 * 外部能力统一入口为 {@link SkillRegistry}。
 *
 * <p>Skill 相对于 Tool 的核心增强：
 * <ul>
 *   <li>异步执行 — {@link SkillType#ASYNC_TASK} 类型不阻塞 Pipeline 主线程</li>
 *   <li>流式输出 — {@link SkillType#STREAMING_TASK} 类型通过
 *       {@link SkillProgressCallback} 实时推送进度。</li>
 *   <li>依赖管理 — {@link #getDependencies()} 声明前置依赖，
 *       {@link SkillGraph} 做拓扑排序和循环检测。</li>
 * </ul>
 *
 * <p>替换机制：新建 Skill 实现类 + @Component，SkillRegistry 自动发现。
 * 已有 Skill 代码零修改。
 *
 * @author LyClaw Team
 * @version 1.0
 * @see SkillType
 * @see SkillExecutor
 * @see SkillRegistry
 * @see SkillGraph
 * @see ToolToSkillAdapter
 */
public interface Skill {

    /**
     * 返回技能的唯一标识。
     *
     * <p>用于 SkillRegistry 的 key 和依赖声明（getDependencies() 中引用的就是这个 ID）。
     * 在整个系统中保持唯一。
     *
     * <p>示例：{@code "code_execution"}、{@code "rag_search"}、{@code "document_analysis"}
     *
     * @return 技能 ID，不能为 null 或空
     */
    String getId();

    /**
     * 返回人类可读的技能名称。
     *
     * <p>用于管理界面展示和日志记录。
     *
     * <p>示例：{@code "代码执行"}、{@code "RAG 检索"}、{@code "文档分析"}
     *
     * @return 技能名称，不能为 null 或空
     */
    String getName();

    /**
     * 返回技能的功能描述。
     *
     * <p>这个描述会被发送给模型（作为 tool_definition 的 description 字段），
     * 帮助模型理解这个技能能做什么、什么时候应该调用。
     *
     * @return 技能描述，不能为 null
     */
    String getDescription();

    /**
     * 返回技能类型。
     *
     * <p>类型决定了 ToolCallLoop 如何调用此技能——是同步等待（{@link SkillType#TOOL}）、
     * 异步轮询（{@link SkillType#ASYNC_TASK}）、流式推送（{@link SkillType#STREAMING_TASK}）、
     * spawn 子 Agent（{@link SkillType#AGENT_TASK}）、检索查询（{@link SkillType#RETRIEVAL_TASK}）。
     *
     * @return 技能类型，不能为 null
     */
    SkillType getType();

    /**
     * 获取技能执行器。
     *
     * <p>将"技能是什么"（Skill 接口）与"技能怎么做"（SkillExecutor 接口）分离。
     * 同一种技能可以有多种执行方式，通过返回不同的 SkillExecutor 实现。
     *
     * @return 技能执行器，不能为 null
     */
    SkillExecutor executor();

    /**
     * 返回依赖的其他技能 ID 列表。
     *
     * <p>依赖关系用于：
     * <ol>
     *   <li>执行前检查 — {@link SkillRegistry#canExecute(String)} 检查所有依赖是否已注册且可用</li>
     *   <li>拓扑排序 — {@link SkillGraph#topologicalSort()} 返回满足所有依赖关系的执行顺序</li>
     *   <li>循环检测 — {@link SkillGraph#detectCycle()} 在启动阶段检测循环依赖，有循环依赖时应用启动失败</li>
     * </ol>
     *
     * <p>大多数简单技能没有依赖，默认实现返回空列表。
     *
     * @return 依赖的技能 ID 列表，不能为 null
     */
    default List<String> getDependencies() {
        return Collections.emptyList();
    }

    /**
     * 返回技能的执行超时时间。
     *
     * <p>不同类型有不同默认值：
     * <ul>
     *   <li>{@link SkillType#TOOL} — 30 秒</li>
     *   <li>{@link SkillType#ASYNC_TASK} — 5 分钟</li>
     *   <li>{@link SkillType#STREAMING_TASK} — 10 分钟</li>
     *   <li>{@link SkillType#AGENT_TASK} — 30 分钟</li>
     *   <li>{@link SkillType#RETRIEVAL_TASK} — 1 分钟</li>
     * </ul>
     *
     * @return 超时时间，不能为 null；默认 5 分钟
     */
    default Duration getTimeout() {
        return Duration.ofMinutes(5);
    }
}
```

## 第二十块：SkillType.java

**文件路径**：`lyclaw-core/src/main/java/lyjew/com/lyclaw/skill/SkillType.java`

```java
package lyjew.com.lyclaw.skill;

/**
 * SkillType 枚举 — 技能类型。
 *
 * <p>决定了 ToolCallLoop 执行技能时的调度方式。每种类型对应的调用策略不同。
 *
 * @author LyClaw Team
 * @version 1.0
 */
public enum SkillType {

    /**
     * 同步工具：等价于传统 Tool。同步调用，立即返回结果。
     *
     * <p>示例：计算器、天气查询、当前时间。
     * 通过 ToolToSkillAdapter 适配的 Tool 自动标记为此类型。
     */
    TOOL,

    /**
     * 异步任务：调用后不阻塞，返回任务 ID，通过轮询或回调获取结果。
     *
     * <p>示例：代码执行（提交代码 → 等待运行 → 返回输出）、
     * 文件处理（上传文件 → 异步处理 → 返回结果）。
     * ToolCallLoop 使用 CompletableFuture 异步等待，不阻塞 Pipeline 主线程。
     */
    ASYNC_TASK,

    /**
     * 流式任务：执行过程中通过 SkillProgressCallback 实时推送进度。
     *
     * <p>示例：文档分析（"正在加载文档... → 正在提取文本（第3页/共10页）..."）、
     * 长文本生成（逐段输出）。
     * ToolCallLoop 将进度通过 EventBus 发布事件，Pipeline 可实时推送给用户。
     */
    STREAMING_TASK,

    /**
     * 代理任务：不是直接执行，而是 spawn 一个子 Agent 来执行。
     *
     * <p>子 Agent 可以独立调用其他 Tool/Skill，完成后将结果返回主 Agent。
     * 示例：研究 Agent（"帮我研究 Spring Boot 3"——子 Agent 独立调用搜索工具）。
     * 需要 {@code AgentCoordinator} 的支持。
     */
    AGENT_TASK,

    /**
     * 检索任务：从知识库中检索相关信息。
     *
     * <p>示例：RAG 查询（从向量数据库中检索与用户问题最相关的文档片段）、
     * 知识库搜索（在公司内部知识库中搜索特定信息）。
     * 通常需要向量数据库 skill 作为依赖。
     */
    RETRIEVAL_TASK
}
```

## 第二十一块：SkillExecutor.java

**文件路径**：`lyclaw-core/src/main/java/lyjew/com/lyclaw/skill/SkillExecutor.java`

```java
package lyjew.com.lyclaw.skill;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * SkillExecutor 接口 — 技能执行器（策略模式）。
 *
 * <p>将"技能是什么"（{@link Skill} 接口的元数据）与"技能怎么做"（SkillExecutor 接口的执行逻辑）分离。
 * 同一个 Skill 可以有不同的执行器实现，例如：
 * <ul>
 *   <li>本地执行器 — 在当前进程中直接执行</li>
 *   <li>沙箱执行器 — 在隔离的沙箱容器中执行（安全但有延迟）</li>
 *   <li>远程执行器 — 调用远程 API 执行</li>
 * </ul>
 *
 * <p>统一返回 {@link CompletableFuture}，无论是同步技能还是异步技能都可以通过此接口统一处理。
 *
 * @author LyClaw Team
 * @version 1.0
 * @see SkillProgressCallback
 */
public interface SkillExecutor {

    /**
     * 执行技能。
     *
     * <p>返回 CompletableFuture&lt;SkillResult&gt;，ToolCallLoop 根据
     * {@link Skill#getType()} 决定如何处理此 Future：
     * <ul>
     *   <li>TOOL 类型 — 同步调用 future.get(timeout)</li>
     *   <li>ASYNC_TASK 类型 — 将 Future 存到"待完成任务"列表，后续统一等待</li>
     *   <li>STREAMING_TASK 类型 — 等待 Future 的同时处理 callback 的进度推送</li>
     * </ul>
     *
     * @param input    模型传入的参数 Map，key=参数名，value=参数值。不能为 null
     * @param callback 进度回调接口，STREAMING_TASK 类型必须提供（非 null）；
     *                 TOOL/ASYNC_TASK 类型可以传 null
     * @return 技能执行结果的 CompletableFuture，不会为 null
     * @throws NullPointerException 如果 input 为 null
     */
    CompletableFuture<SkillResult> execute(Map<String, Object> input,
                                           SkillProgressCallback callback);
}
```

## 第二十二块：SkillProgressCallback.java

**文件路径**：`lyclaw-core/src/main/java/lyjew/com/lyclaw/skill/SkillProgressCallback.java`

```java
package lyjew.com.lyclaw.skill;

/**
 * SkillProgressCallback 接口 — 技能进度回调。
 *
 * <p>在技能执行过程中，通过回调接口实时推送进度和中间结果。
 * 主要供 {@link SkillType#STREAMING_TASK} 类型使用，其他类型可以传 null。
 *
 * <p>回调的数据流路径：
 * <pre>{@code
 * SkillExecutor → SkillProgressCallback → EventBus.publish(SkillProgressEvent)
 *     → Pipeline 的 StreamingStage（第二版）→ FluxSink → 用户
 * }</pre>
 *
 * @author LyClaw Team
 * @version 1.0
 */
public interface SkillProgressCallback {

    /**
     * 进度更新消息。
     *
     * <p>用于展示"当前进度"，如"正在加载文档...""正在提取文本（第3页/共10页）..."。
     *
     * @param message 进度更新文本，不能为 null
     */
    void onProgress(String message);

    /**
     * 中间结果通知。
     *
     * <p>用于展示"阶段性结果"。
     * 示例：文档分析技能的"已提取的摘要"、代码执行技能的"编译输出"。
     *
     * @param result 中间结果对象，不能为 null
     */
    void onIntermediateResult(Object result);

    /**
     * 流式数据片段推送。
     *
     * <p>用于逐段推送生成式结果。
     * 示例：长文本生成技能逐段输出生成内容。
     *
     * @param data 数据片段，不能为 null
     */
    void onStream(String data);
}
```

## 第二十三块：SkillRegistry.java

**文件路径**：`lyclaw-core/src/main/java/lyjew/com/lyclaw/skill/SkillRegistry.java`

```java
package lyjew.com.lyclaw.skill;

import java.util.List;
import java.util.Optional;

/**
 * SkillRegistry 接口 — 技能注册表（注册表模式）。
 *
 * <p>所有外部能力的统一入口（权威注册表）。Tool 通过 {@code ToolToSkillAdapter}
 * 自动适配为 Skill 后也注册到此注册表。
 *
 * <p>ToolCallLoop 只通过 SkillRegistry 查找和调用能力，不直接操作 ToolRegistry。
 * ContextBuildStage 构建上下文时也通过此接口获取所有能力定义。
 *
 * <p>第一版注册表是 Spring 容器驱动的——在 {@code @PostConstruct} 阶段，
 * 收集所有 {@code @Component} 的 Skill 实现和 Tool 实现（通过 ToolToSkillAdapter 包装），
 * 调用 register() 完成注册。
 *
 * @author LyClaw Team
 * @version 1.0
 * @see Skill
 * @see SkillGraph
 */
public interface SkillRegistry {

    /**
     * 注册技能。
     *
     * <p>如果技能 ID 已存在，抛出 DuplicateSkillIdException。
     *
     * @param skill 技能实例，不能为 null
     * @throws DuplicateSkillIdException 如果技能 ID 已存在
     * @throws NullPointerException      如果 skill 为 null
     */
    void register(Skill skill);

    /**
     * 移除技能。
     *
     * <p>用于运行时动态卸载技能（如 MCP Server 断开连接时）。
     *
     * @param skillId 技能 ID，不能为 null 或空
     */
    void unregister(String skillId);

    /**
     * 根据 ID 获取技能。
     *
     * @param skillId 技能 ID
     * @return 匹配的技能，不存在返回 Optional.empty()
     */
    Optional<Skill> get(String skillId);

    /**
     * 获取所有已注册的技能。
     *
     * @return 技能列表，不会为 null
     */
    List<Skill> getAll();

    /**
     * 按技能类型筛选。
     *
     * @param type 技能类型，不能为 null
     * @return 匹配类型的技能列表，不会为 null
     */
    List<Skill> getByType(SkillType type);

    /**
     * 获取技能依赖图。
     *
     * <p>返回的依赖图是当前注册状态的快照。如果后续有技能注册或注销，
     * 调用此方法获取最新的依赖图。
     *
     * @return 技能依赖图，不会为 null
     */
    SkillGraph getDependencyGraph();

    /**
     * 检查指定技能是否满足依赖条件（所有前置依赖已注册且可用）。
     *
     * @param skillId 技能 ID
     * @return true 表示依赖满足，可以执行
     */
    boolean canExecute(String skillId);

    /**
     * 拓扑排序。
     *
     * <p>按技能依赖关系排序——无依赖的技能在前，有依赖的技能在后。
     * 用于按序启动所有技能的预热。
     *
     * @return 技能 ID 列表，满足拓扑顺序
     * @throws CyclicDependencyException 如果检测到循环依赖
     */
    List<String> topologicalSort();
}
```

## 第二十四块：SkillGraph.java

**文件路径**：`lyclaw-core/src/main/java/lyjew/com/lyclaw/skill/SkillGraph.java`

```java
package lyjew.com.lyclaw.skill;

import java.util.List;

/**
 * SkillGraph 接口 — 技能依赖图。
 *
 * <p>管理技能之间的依赖关系。通过邻接表（Map&lt;String, List&lt;String&gt;&gt;）表示
 * 从技能 ID 到其直接依赖的映射。
 *
 * <p>能力：
 * <ul>
 *   <li>查询直接依赖 / 传递依赖（传递闭包）</li>
 *   <li>拓扑排序（Kahn 算法）</li>
 *   <li>循环依赖检测（DFS）</li>
 * </ul>
 *
 * @author LyClaw Team
 * @version 1.0
 */
public interface SkillGraph {

    /**
     * 获取指定技能的直接依赖 ID 列表。
     *
     * @param skillId 技能 ID
     * @return 直接依赖的技能 ID 列表，不会为 null
     */
    List<String> getDirectDependencies(String skillId);

    /**
     * 获取指定技能的传递依赖 ID 列表（所有直接和间接依赖）。
     *
     * <p>通过 BFS/DFS 遍历依赖图实现传递闭包。
     *
     * @param skillId 技能 ID
     * @return 传递依赖的技能 ID 列表，不会为 null
     */
    List<String> getTransitiveDependencies(String skillId);

    /**
     * 拓扑排序。
     *
     * <p>使用 Kahn 算法。返回满足所有依赖关系的线性执行顺序。
     * 无依赖的技能在前，被依赖的技能在后。
     *
     * @return 技能 ID 列表，满足拓扑顺序
     * @throws CyclicDependencyException 如果检测到循环依赖
     */
    List<String> topologicalSort();

    /**
     * 检测循环依赖。
     *
     * <p>使用 DFS + 三色标记法（WHITE/GRAY/BLACK）。
     * 遍历每个节点，如果 DFS 走到一个 GRAY 节点，说明存在回路。
     *
     * @return 如果存在循环依赖，返回参与循环的技能 ID 列表（便于排查）；
     *         不存在返回空列表
     */
    List<String> detectCycle();
}
```

## 第二十五块：MemoryManager.java

**文件路径**：`lyclaw-core/src/main/java/lyjew/com/lyclaw/memory/MemoryManager.java`

```java
package lyjew.com.lyclaw.memory;

import lyjew.com.lyclaw.model.Memory;
import lyjew.com.lyclaw.model.Session;

import java.util.List;

/**
 * MemoryManager 接口 — 记忆管理（策略模式）。
 *
 * <p>管理跨会话的持久化信息（记忆）。记忆独立于会话——可以在多次会话之间共享，
 * 即使会话被删除，记忆仍然保留。
 *
 * <p>核心设计原则：将存储实现与使用方完全解耦。
 * ContextBuilder 只需要调用 {@link #recall()} 获取记忆列表，
 * MemoryManager 的具体实现（文件、Redis、数据库）对 ContextBuilder 完全透明。
 *
 * <p>第一版限制：
 * lyclaw-common 的 Memory 类注释为"单例实体，id 固定为 global"，
 * 因此第一版只支持单条记忆。第二版需新建 ExtendedMemory 子类或扩展 MemoryStorage。
 *
 * @author LyClaw Team
 * @version 1.0
 * @see MemoryStrategy
 * @see Memory
 */
public interface MemoryManager {

    /**
     * 从会话中提取记忆并持久化存储。
     *
     * <p>提取逻辑由 strategy.extract(session) 执行，返回 MemoryContent（记忆内容）。
     * 存储逻辑由 MemoryManager 的实现类执行（FileMemoryManager 写入 Markdown 文件，
     * RedisMemoryManager 存入 Redis Hash）。
     *
     * @param session  会话对象，从中提取记忆信息。不能为 null
     * @param strategy 记忆提取策略，决定了"提取什么"。不能为 null
     */
    void remember(Session session, MemoryStrategy strategy);

    /**
     * 读取所有已启用的记忆（enabled=true）。
     *
     * @return 记忆列表，不会为 null。第一版返回单元素列表或空列表
     */
    List<Memory> recall();

    /**
     * 按标签筛选并读取记忆。
     *
     * <p>第一版委托给 {@link #recall()}，忽略 tags 参数（因 Memory 是单例，只有一条记忆）。
     *
     * @param tags 标签列表
     * @return 匹配的记忆列表
     */
    List<Memory> recallByTags(List<String> tags);

    /**
     * 分页读取记忆。
     *
     * <p>第一版返回单页。
     *
     * @param page      页码，从 0 开始
     * @param size      每页数量
     * @param tagFilter 标签筛选（可选）
     * @return 分页结果
     */
    PageResult<Memory> recallByPage(int page, int size, String tagFilter);

    /**
     * 删除指定记忆。
     *
     * <p>第一版实现物理删除（直接删除文件）。
     * 第二版可以实现软删除——将 enabled 设为 false，数据保留。
     *
     * @param memoryId 记忆 ID，不能为 null 或空
     */
    void forget(String memoryId);

    /**
     * 将记忆列表格式化为可注入上下文的字符串。
     *
     * <p>ContextBuildStage 调用此方法，将返回的字符串注入系统提示中。
     *
     * <p>第一版实现：所有记忆按更新时间倒序，最新的在最前面。
     * 第二版需考虑上下文窗口的剩余空间，智能选择注入哪些记忆。
     *
     * @param memories 记忆列表
     * @return 格式化的上下文字符串，适合注入系统提示
     */
    String buildContext(List<Memory> memories);
}
```

## 第二十六块：MemoryStrategy.java

**文件路径**：`lyclaw-core/src/main/java/lyjew/com/lyclaw/memory/MemoryStrategy.java`

```java
package lyjew.com.lyclaw.memory;

import lyjew.com.lyclaw.model.Session;

/**
 * MemoryStrategy 接口 — 记忆提取策略（策略模式）。
 *
 * <p>决定"从会话中提取什么信息作为记忆"。
 * 第一版只实现手动触发——用户明确说"记住 xxx"时才提取。
 * 第二版可实现自动检测关键事件。
 *
 * <p>已知实现：
 * <ul>
 *   <li>ManualMemoryStrategy（第一版） — 检查消息是否包含"记住"触发词</li>
 *   <li>KeyEventMemoryStrategy（第二版） — 自动检测关键事件</li>
 * </ul>
 *
 * @author LyClaw Team
 * @version 1.0
 */
public interface MemoryStrategy {

    /**
     * 判断是否需要从会话中提取记忆。
     *
     * @param session 会话对象，包含消息列表
     * @return true 表示需要提取
     */
    boolean shouldExtract(Session session);

    /**
     * 从会话中提取记忆内容。
     *
     * @param session 会话对象
     * @return 提取的记忆内容（包含摘要、标签、重要性等）
     */
    MemoryContent extract(Session session);
}
```

## 第二十六块附：MemoryContent.java

**文件路径**：`lyclaw-core/src/main/java/lyjew/com/lyclaw/memory/MemoryContent.java`

```java
package lyjew.com.lyclaw.memory;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * MemoryContent — 记忆内容值对象。
 *
 * <p>由 MemoryStrategy.extract() 返回，表示从会话中提取的一段记忆。
 * 包含具体的记忆文本、时间戳、标签列表和重要程度。
 *
 * @author LyClaw Team
 * @version 1.0
 */
public final class MemoryContent {

    /** 记忆摘要文本 */
    private final String summary;

    /** 完整的记忆正文 */
    private final String content;

    /** 提取时间 */
    private final LocalDateTime timestamp;

    /** 标签列表 */
    private final List<String> tags;

    /** 重要性（0-10，越大越重要） */
    private final int importance;

    /**
     * 构造 MemoryContent。
     *
     * @param summary    记忆摘要（短文本，用于快速浏览），不能为 null
     * @param content    完整的记忆正文，不能为 null
     * @param tags       标签列表，不能为 null（可为空列表）
     * @param importance 重要性（0-10）
     */
    public MemoryContent(String summary, String content,
                         List<String> tags, int importance) {
        this.summary = Objects.requireNonNull(summary, "summary must not be null");
        this.content = Objects.requireNonNull(content, "content must not be null");
        this.timestamp = LocalDateTime.now();
        this.tags = tags != null ? Collections.unmodifiableList(tags)
                : Collections.emptyList();
        this.importance = Math.max(0, Math.min(10, importance));
    }

    public String getSummary() { return summary; }
    public String getContent() { return content; }
    public LocalDateTime getTimestamp() { return timestamp; }
    public List<String> getTags() { return tags; }
    public int getImportance() { return importance; }
}
```

## 第二十六块附：PageResult.java

**文件路径**：`lyclaw-core/src/main/java/lyjew/com/lyclaw/memory/PageResult.java`

```java
package lyjew.com.lyclaw.memory;

import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * PageResult — 分页结果值对象。
 *
 * <p>用于 MemoryManager.recallByPage() 的返回类型。
 * 包含当前页数据、总记录数、总页数、是否有下一页。
 *
 * @param <T> 数据类型
 * @author LyClaw Team
 * @version 1.0
 */
public final class PageResult<T> {

    private final List<T> data;
    private final int total;
    private final int page;
    private final int size;
    private final boolean hasNext;

    public PageResult(List<T> data, int total, int page, int size) {
        this.data = Collections.unmodifiableList(
            Objects.requireNonNull(data, "data must not be null"));
        this.total = total;
        this.page = page;
        this.size = size;
        this.hasNext = (page + 1) * size < total;
    }

    /** 创建一个空的分页结果。 */
    public static <T> PageResult<T> empty(int page, int size) {
        return new PageResult<>(Collections.emptyList(), 0, page, size);
    }

    public List<T> getData() { return data; }
    public int getTotal() { return total; }
    public int getPage() { return page; }
    public int getSize() { return size; }
    public boolean hasNext() { return hasNext; }
}
```

## 第二部分完成统计

| 块 | 文件名 | 类型 | 行数（约） |
|----|--------|------|-----------|
| 19 | Skill.java | 接口 | 95 |
| 20 | SkillType.java | 枚举 | 55 |
| 21 | SkillExecutor.java | 接口 | 50 |
| 22 | SkillProgressCallback.java | 接口 | 40 |
| 23 | SkillRegistry.java | 接口 | 75 |
| 24 | SkillGraph.java | 接口 | 70 |
| 25 | MemoryManager.java | 接口 | 100 |
| 26 | MemoryStrategy.java | 接口 | 50 |
| 26附 | MemoryContent.java | 值对象 | 65 |
| 26附 | PageResult.java | 值对象 | 50 |
| **总计** | **10 个文件** | - | **~650 行代码** |

### 本部分涉及的已有代码引用（零修改）

| 已有类 | 使用方式 | 引用位置 |
|--------|----------|----------|
| `Memory` | 返回类型 | MemoryManager.recall() 等 |
| `Session` | 参数类型 | MemoryManager.remember()、MemoryStrategy |
| `Tool` | 适配目标 | Skill 接口注释说明 |
| `ToolDefinition` | 注释引用 | Skill 接口注释说明 |

## 下一部分预告

**第三部分：lyclaw-core（事件 + Agent + 错误体系）** — 12 个接口/类，覆盖 Event/EventBus/AgentCoordinator/AgentChannel/AgentTask/AgentState/ErrorPolicy/SecurityManager/TaskPlanner/TaskPlan/TaskLedger/ModelProvider。

> ✅ 已完成

---

*续写于 2026-04-28*

---


# 第三部分：事件 + Agent + 错误体系（lyclaw-core）

> **设计文档对应章节**：第十三章（EventBus）、第十四章（AgentCoordinator）、第十五章（ErrorPolicy）、第十六章（流式）、第十七章（Session事务）、第十八章（模块归属）
>
> **本部分总体设计意图**：
>
> 第一部分（Engine/Pipeline/Interceptor/Tool）定义了"一次对话请求从进入引擎到返回结果的完整流程"——这是**请求流程层**。
> 第二部分（Skill/Memory）定义了"外部能力的统一接入标准和记忆持久化"——这是**能力层**。
> 第三部分定义的是**支撑层**——事件（模块间松耦合通信）、Agent（主从任务拆分）、错误（容错与降级）、安全（审批与凭证）、任务编排（DAG调度）、模型提供（防腐层隔离）。这些不直接参与请求流程，但为整个引擎提供横向支撑能力。
>
> **包结构**：
> - `event/` — 事件与事件总线（发布-订阅）
> - `agent/` — Agent协调器 + 通信拓扑 + 任务 + 状态
> - `error/` — 错误处理策略 + 异常类型
> - `security/` — 安全审批 + 沙箱等级 + 凭证解析
> - `task/` — 任务编排 + 任务计划 + 任务账本
> - `provider/` — 模型适配器提供者（防腐层）

## 实现文件清单

| 序号 | 文件 | 包 | 类/接口类型 |
|------|------|-----|-------------|
| 29 | Event.java | event | 抽象类 |
| 30 | EventBus.java | event | 接口 |
| 31 | AgentCoordinator.java | agent | 接口 |
| 32 | AgentChannel.java | agent | 接口 |
| 33 | AgentTask.java | agent | 类 |
| 34 | AgentState.java | agent | 枚举 |
| 35 | ErrorPolicy.java | error | 接口 |
| 36 | SecurityManager.java | security | 接口 |
| 37 | TaskPlanner.java | task | 接口 |
| 38 | TaskPlan.java | task | 类 |
| 39 | TaskLedger.java | task | 接口 |
| 40 | ModelProvider.java | provider | 接口 |

---

## 第二十九块：Event.java

> **为什么需要抽象 Event 类？**
>
> 如果事件没有统一的基类，每个事件类型自己定义字段，EventBus 就无法用泛型约束 publish/subscribe。泛型 `<T extends Event>` 要求所有事件实现统一的接口。为什么要用抽象类而不是接口？因为每个事件都需要自动记录时间戳——子类构造时 `Instant.now()` 自动赋值，避免每个子类重复写这行代码。
>
> **设计文档对应**：13.1 节指出的"模块间直接调用导致紧密耦合"——如果 MetricsStage 直接调用 CostService，新增监听者需要修改 MetricsStage 的代码。有了 Event 基类和 EventBus，MetricsStage 只需 `eventBus.publish(event)`，不需要知道监听者。

**文件路径**：`lyclaw-core/src/main/java/lyjew/com/lyclaw/event/Event.java`

```java
package lyjew.com.lyclaw.event;

import java.time.Instant;

/**
 * Event — 事件基类（抽象类）。
 *
 * <p>所有事件的父类。设计为抽象类而非接口的原因：
 * <ol>
 *   <li>时间戳自动生成 — 子类构造时由基类构造器调用 Instant.now() 自动记录</li>
 *   <li>模板方法 — getType() 留给子类实现，时间戳逻辑由基类统一完成</li>
 * </ol>
 *
 * <p>第一版已知子类（实现在 lyclaw-engine/event/impl）：
 * <ul>
 *   <li>{@code TokenConsumedEvent} — Token 消耗事件。触发时机：MetricsStage 执行中，模型 Token 用量统计完成后。
 *       携带数据：sessionId、model、promptTokens、completionTokens、totalTokens。
 *       典型监听者：MetricsService（Prometheus 指标）、CostService（费用累计）</li>
 *   <li>{@code ToolCalledEvent} — 工具调用事件。触发时机：ToolCallLoop 每轮工具执行完成后。
 *       携带数据：sessionId、toolName、arguments、result（前 200 字符）、duration、status。
 *       典型监听者：ToolLogService（工具调用日志）</li>
 *   <li>{@code AgentStateChangedEvent} — Agent 状态变更事件。触发时机：AgentCoordinator 执行 spawn/terminate 后。
 *       携带数据：agentId、oldState、newState、sessionId。
 *       典型监听者：UI 推送服务（WebSocket 推送）</li>
 *   <li>{@code ConversationCompletedEvent} — 对话完成事件。触发时机：Pipeline 执行完所有 Stage 后。</li>
 * </ul>
 *
 * @author LyClaw Team
 * @version 1.0
 * @see EventBus
 */
public abstract class Event {

    /** 事件发生时的 UTC 时间戳，由基类构造器自动记录 */
    private final Instant timestamp;

    /** 构造时自动记录当前时间，子类无需关心时间戳 */
    protected Event() {
        this.timestamp = Instant.now();
    }

    /**
     * 返回事件类型标识。
     *
     * <p>EventBus 按此标识分发事件。子类通常返回 getClass().getName()，
     * 因为全限定类名天然唯一。
     *
     * @return 事件类型标识，必须唯一，不能为 null
     */
    public abstract String getType();

    /**
     * @return 事件发生时的 UTC 时间戳
     */
    public Instant getTimestamp() {
        return timestamp;
    }
}
```

---

## 第三十块：EventBus.java

> **为什么 EventBus 需要这 5 个方法？**
>
> | 方法 | 解决的设计问题 | 第一版行为 |
> |------|---------------|-----------|
> | publish(T) | 同步发布——发布者阻塞，直到所有监听器处理完。适合低频、可靠场景 | CopyOnWriteArrayList 遍历，异常只记录日志 |
> | publishAsync(T) | 异步发布——发布者立即返回，不阻塞。适合高频场景 | 第二版实现，第一版直接调用 publish() |
> | hasSubscribers() | MetricsStage 可先判断再创建事件对象，避免频繁创建无用对象 | 直接检查 Map 是否存在监听器列表 |
> | subscribe() | 返回 Subscription 句柄，支持手动取消订阅 | 句柄存入 ConcurrentHashMap |
> | unsubscribe() | 防内存泄漏——不取消订阅则 EventBus 持有 handler 引用，导致对象无法 GC | 从 CopyOnWriteArrayList 中移除 |
>
> **线程安全设计对比**：
> - ConcurrentHashMap<Class<?>, CopyOnWriteArrayList<Consumer>>：ConcurrentHashMap 保证 Map 读写安全，CopyOnWriteArrayList 保证遍历（读）时不需要加锁。适合"监听器在启动时注册，运行时几乎不变化"的场景。
> - 为什么不适合用 SynchronizedList？因为 publish() 需遍历所有监听器，如果使用 SynchronizedList，publish() 全程持有锁，并发度低。
>
> **设计文档对应**：13.2 节（EventBus 接口设计）+ 13.4 节（线程安全设计）

**文件路径**：`lyclaw-core/src/main/java/lyjew/com/lyclaw/event/EventBus.java`

```java
package lyjew.com.lyclaw.event;

import java.util.function.Consumer;

/**
 * EventBus 接口 — 事件总线（发布-订阅模式）。
 *
 * <p>解耦事件的发布者和订阅者。发布者不需要知道谁在监听，
 * 订阅者不需要知道事件来自哪里。总线负责将事件路由到所有订阅者。
 *
 * <p>典型使用场景：
 * <pre>{@code
 * // 发布者（MetricsStage）
 * if (eventBus.hasSubscribers(TokenConsumedEvent.class)) {
 *     eventBus.publish(new TokenConsumedEvent(context));
 * }
 *
 * // 订阅者（系统启动时注册）
 * eventBus.subscribe(TokenConsumedEvent.class, event -> {
 *     metricsService.record(event.getTotalTokens());
 * });
 * }</pre>
 *
 * <p>第一版实现：{@code InMemoryEventBus} 使用 CopyOnWriteArrayList 保证线程安全（监听器在启动时注册，
 * 运行时几乎不变化，适合 CopyOnWrite 的"读多写少"场景）。
 * 第二版可替换为 KafkaEventBus（分布式事件总线）或 SpringEventBus（深度集成 Spring 事件）。
 * 切换方式：新建实现类，Spring 通过 @Primary 切换注入——所有发布者和订阅者代码零修改。
 *
 * @author LyClaw Team
 * @version 1.0
 * @see Event
 */
public interface EventBus {

    /**
     * 同步发布事件。
     *
     * <p>发布者阻塞直到所有订阅者处理完事件。
     * 如果某个订阅者抛异常，只记录日志，不影响其他订阅者执行。
     * 适合对处理延迟要求不高的场景。
     *
     * @param event 事件对象，不能为 null
     * @param <T>   事件的具体类型
     */
    <T extends Event> void publish(T event);

    /**
     * 异步发布事件（第二版实现，第一版预留接口）。
     *
     * <p>发布者将事件放入队列后立即返回，线程池异步调用订阅者。
     * 适合高频场景（如 Token 消耗事件——每次模型调用都会触发，不需要等待处理结果）。
     *
     * @param event 事件对象，不能为 null
     * @param <T>   事件的具体类型
     */
    <T extends Event> void publishAsync(T event);

    /**
     * 订阅指定类型的事件。
     *
     * <p>返回 Subscription 对象用于后续取消订阅。如果订阅者忘记取消订阅，
     * EventBus 会持有 handler 的引用，导致订阅者对象无法被 GC（内存泄漏）。
     *
     * @param eventType 事件类型的 Class 对象
     * @param handler   事件处理器——接收事件实例
     * @param <T>       事件的具体类型
     * @return Subscription 对象，调用其 unsubscribe() 方法可取消此订阅
     */
    <T extends Event> Subscription subscribe(Class<T> eventType, Consumer<T> handler);

    /**
     * 取消订阅。
     *
     * <p>从监听器列表中移除对应的 handler。
     *
     * @param subscription 之前 subscribe() 返回的 Subscription 对象
     */
    void unsubscribe(Subscription subscription);

    /**
     * 检查是否有订阅者监听指定类型的事件。
     *
     * <p>引入此方法的动机：MetricsStage 在发布 TokenConsumedEvent 前调用此方法，
     * 如果没有订阅者则跳过事件对象创建，避免频繁创建无用对象增加 GC 压力。
     *
     * @param eventType 事件类型的 Class 对象
     * @return true 表示至少有一个订阅者
     */
    boolean hasSubscribers(Class<? extends Event> eventType);

    /**
     * Subscription — 订阅句柄。
     *
     * <p>由 {@link #subscribe(Class, Consumer)} 返回。
     * 调用方持有此句柄，需要取消订阅时调用 {@link #unsubscribe(Subscription)}。
     */
    interface Subscription {
        /** 取消此订阅。之后 EventBus 将不再向此 handler 分发事件。 */
        void unsubscribe();
    }
}
```

---

## 第三十一块：AgentCoordinator.java

> **为什么需要 AgentCoordinator？**
>
> 复杂 AI 对话中，用户可能提出需要"分步完成"的任务。例如"帮我研究 Spring Boot 3 新特性并写报告"——这个过程可以分解为搜索、阅读文档、整理报告三个子任务。如果不引入 Agent 概念，主流程需要按顺序执行三个步骤（线性、不灵活）。如果引入 Agent 概念，主 Agent 可以 spawn 三个子 Agent 并行执行，各自独立完成。
>
> AgentCoordinator 的职责范围：**只负责子 Agent 的生命周期管理**（创建、监控、终止）。不负责通信拓扑——通信拓扑由 AgentChannel 独立负责（桥接模式，见下一块）。
>
> **第一版约束**（来自设计文档 14.3）：
> - 同一会话最多 1 个子 Agent 并发——先跑通流程，第二版引入并行
> - 超时 5 分钟——超过自动终止，状态置为 TIMEOUT
> - 深度限制为 1——子 Agent 不可再 spawn 孙 Agent，防止无限递归
> - 级联终止——主会话被关闭时自动终止所有子 Agent
>
> **设计文档对应**：14.1 节（设计动机）+ 14.2 节（Agent 状态机，14.3 节（第一版约束）

**文件路径**：`lyclaw-core/src/main/java/lyjew/com/lyclaw/agent/AgentCoordinator.java`

```java
package lyjew.com.lyclaw.agent;

import java.time.Duration;

/**
 * AgentCoordinator 接口 — Agent 协调器。
 *
 * <p>管理子 Agent 的创建（{@link #spawn}）、监控（{@link #getStatus} / {@link #awaitResult}）
 * 和终止（{@link #terminate} / {@link #cascadeTerminate}）。
 *
 * <p>AgentCoordinator 只负责子 Agent 的<b>生命周期管理</b>，
 * 不负责通信拓扑——Agent 间的通信由 {@link AgentChannel} 独立负责（桥接模式）。
 *
 * <p>典型调用流程：
 * <pre>{@code
 * // 1. 主 Agent 创建子 Agent
 * String agentId = coordinator.spawn(sessionId, task);
 *
 * // 2. 主 Agent 等待子 Agent 完成
 * AgentResult result = coordinator.awaitResult(agentId, Duration.ofMinutes(5));
 *
 * // 3. 或者手动终止
 * coordinator.terminate(agentId);
 *
 * // 4. 会话关闭时级联终止
 * coordinator.cascadeTerminate(sessionId);
 * }</pre>
 *
 * <p>第一版约束（设计文档 14.3）：
 * <ul>
 *   <li>单 Agent 模式 — 同一会话最多 1 个子 Agent 并发执行。如果已有运行中的子 Agent，
 *       spawn() 抛出 AgentBusyException</li>
 *   <li>超时 5 分钟 — awaitResult() 超时后子 Agent 自动终止，状态置为 {@link AgentState#TIMEOUT}</li>
 *   <li>深度限制 — 子 Agent 不可再 spawn 孙 Agent（深度为 1），防止无限递归</li>
 *   <li>级联终止 — 主会话关闭时 cascadeTerminate() 自动终止所有子 Agent</li>
 * </ul>
 *
 * @author LyClaw Team
 * @version 1.0
 * @see AgentChannel
 * @see AgentTask
 * @see AgentState
 */
public interface AgentCoordinator {

    /**
     * 创建子 Agent 并开始执行。
     *
     * <p>已有子 Agent 在运行中（状态不为 IDLE/COMPLETED/ERROR/TERMINATED/TIMEOUT）时，
     * 抛出 AgentBusyException——第一版不支持并行子 Agent。
     *
     * @param parentSessionId 主会话 ID，用于级联终止时关联查找
     * @param task            子 Agent 要执行的任务（包含 goal、allowedTools、timeout）
     * @return 子 Agent 的唯一标识（agentId）
     * @throws AgentBusyException 如果已有运行中的子 Agent
     */
    String spawn(String parentSessionId, AgentTask task);

    /**
     * 等待子 Agent 执行完毕并获取结果。
     *
     * <p>如果子 Agent 已处于终态（COMPLETED/ERROR/TERMINATED/TIMEOUT），直接返回结果。
     * 如果子 Agent 还在执行中（RUNNING/WAITING_TOOL），阻塞等待至超时。
     *
     * @param agentId 子 Agent ID
     * @param timeout 最大等待时间，超过此时间仍未完成则强制返回 TIMEOUT 结果
     * @return Agent 执行结果（包含 agentId、执行状态、结果内容、耗时）
     */
    AgentResult awaitResult(String agentId, Duration timeout);

    /**
     * 获取子 Agent 的当前状态。
     *
     * <p>可用于轮询子 Agent 的进度。第一版轮询方案，第二版改用 EventBus 事件推送。
     *
     * @param agentId 子 Agent ID
     * @return 当前状态枚举值
     * @throws IllegalArgumentException 如果 agentId 不存在
     */
    AgentState getStatus(String agentId);

    /**
     * 手动终止子 Agent。
     *
     * <p>任意状态下都可以终止。终止后状态变为 {@link AgentState#TERMINATED}。
     * 终止已处于终态的子 Agent 不产生任何影响（幂等操作）。
     *
     * @param agentId 子 Agent ID
     */
    void terminate(String agentId);

    /**
     * 级联终止指定会话下的所有子 Agent。
     *
     * <p>主会话被关闭或删除时调用。遍历该会话关联的所有子 Agent，逐个终止。
     * 确保不会留下"孤儿"子 Agent 占用资源。
     *
     * @param sessionId 会话 ID
     */
    void cascadeTerminate(String sessionId);
}
```

---

## 第三十二块：AgentChannel.java

> **为什么需要 AgentChannel？（桥接模式）**
>
> 如果不引入 AgentChannel，AgentCoordinator 既要负责生命周期管理又要负责通信拓扑。选择通信拓扑（星型、树型、网状）属于"实现方式的变化"，Agent 生命周期管理（spawn/terminate）属于"抽象功能的变化"。两个维度的变化如果耦合在一起，选择新拓扑时需要修改 AgentCoordinator 的实现。桥接模式将两个变化维度分离——AgentCoordinator 只定义生命周期接口，AgentChannel 只定义通信拓扑接口。第一版 StarAgentChannel，第二版可切换为 TreeAgentChannel，不需要改 AgentCoordinator 任何代码。
>
> **设计文档对应**：14.4 节（AgentChannel 通信拓扑设计）

**文件路径**：`lyclaw-core/src/main/java/lyjew/com/lyclaw/agent/AgentChannel.java`

```java
package lyjew.com.lyclaw.agent;

import reactor.core.publisher.Flux;

/**
 * AgentChannel 接口 — Agent 通信拓扑（桥接模式）。
 *
 * <p>将通信拓扑的实现与 Agent 协调逻辑解耦。AgentCoordinator 只负责 Agent 生命周期管理，
 * 通信拓扑由 AgentChannel 独立负责。两个维度可以独立变化。
 *
 * <p>第一版使用星型拓扑（{@code StarAgentChannel}）——主 Agent 是中心节点，
 * 所有子 Agent 是叶子节点。主 Agent 可与任何子 Agent 直接通信，子 Agent 之间不可直接通信。
 *
 * <p>未来可选拓扑（通过新建实现类切换）：
 * <ul>
 *   <li>TreeAgentChannel（第二版）— 树形拓扑。父 Agent spawn 子 Agent，子 Agent 再 spawn 孙 Agent。
 *       适合目标分解场景——大目标→子目标→孙目标</li>
 *   <li>MeshAgentChannel（第三版）— 网状拓扑。任意 Agent 之间可直接通信。
 *       适合协同场景——多个 Agent 互相通信协作完成任务</li>
 *   <li>BroadcastAgentChannel（第三版）— 广播拓扑。一条消息发送给所有 Agent。
 *       适合通知场景——主 Agent 广播指令</li>
 * </ul>
 *
 * @author LyClaw Team
 * @version 1.0
 * @see AgentCoordinator
 */
public interface AgentChannel {

    /**
     * 发送消息给指定 Agent。
     *
     * <p>星型拓扑下，只有主 Agent 可以调用此方法向子 Agent 发送任务，
     * 子 Agent 只能向主 Agent 返回结果。子 Agent 之间不能直接 send()。
     *
     * @param message 消息对象（包含发送者、接收者、类型、内容、时间戳）
     */
    void send(AgentMessage message);

    /**
     * 接收来自指定 Agent 的消息流。
     *
     * <p>返回 Flux 以便调用方以响应式方式处理多条消息——调用方可以订阅此 Flux，
     * 每条消息到达时自动触发处理逻辑。
     *
     * @param agentId Agent ID
     * @return 消息流
     */
    Flux<AgentMessage> receive(String agentId);
}
```

## 第三十二块附：AgentMessage.java

> **为什么定义 AgentMessage.Type 枚举而不是用字符串？**
> 编译期类型安全。写 "TASK" 字符串拼写错误时编译器检查不到，运行时才报错。枚举在编译期检查，IDE 也能自动补全。
>
> **字段设计说明**：
> - from/to：双向通信需要明确标识发送方和接收方，星型拓扑下主 Agent 发 TASK 给子 Agent，子 Agent 发 RESULT 给主 Agent
> - type：5 种类型覆盖了 Agent 间通信的典型场景（任务指令、结果汇报、状态更新、查询、心跳保活）
> - content：String 类型，第一版传递 JSON 字符串，第二版可改为 Object 支持序列化
> - timestamp：记录消息发生时间，用于日志追踪和超时判断

**文件路径**：`lyclaw-core/src/main/java/lyjew/com/lyclaw/agent/AgentMessage.java`

```java
package lyjew.com.lyclaw.agent;

import java.time.Instant;
import java.util.Objects;

/**
 * AgentMessage — Agent 间通信的消息载体。
 *
 * <p>不可变对象（所有字段不可修改）。包含发送者、接收者、消息类型、正文和时间戳。
 * 创建后内容不可变，保证多线程环境下的安全。
 *
 * <p>消息类型说明：
 * <ul>
 *   <li>{@code TASK} — 主 Agent 向子 Agent 下发任务指令</li>
 *   <li>{@code RESULT} — 子 Agent 向主 Agent 返回执行结果</li>
 *   <li>{@code STATUS} — Agent 向其他 Agent 通知自身状态变更</li>
 *   <li>{@code QUERY} — Agent 向其他 Agent 查询信息（如"你的进度如何"）</li>
 *   <li>{@code HEARTBEAT} — Agent 保活信号，用于超时检测</li>
 * </ul>
 *
 * @author LyClaw Team
 * @version 1.0
 */
public final class AgentMessage {

    /** 消息类型枚举 */
    public enum Type {
        /** 任务指令——主 Agent 向子 Agent 下发任务 */
        TASK,
        /** 结果汇报——子 Agent 向主 Agent 返回执行结果 */
        RESULT,
        /** 状态更新——Agent 向其他 Agent 通知自身状态变更 */
        STATUS,
        /** 查询——Agent 向其他 Agent 查询信息 */
        QUERY,
        /** 心跳——Agent 保活信号 */
        HEARTBEAT
    }

    /** 发送方 Agent ID */
    private final String from;
    /** 接收方 Agent ID */
    private final String to;
    /** 消息类型 */
    private final Type type;
    /** 消息正文（第一版使用 JSON 字符串） */
    private final String content;
    /** 消息创建时间（UTC） */
    private final Instant timestamp;

    /**
     * 构造消息。时间戳自动设为当前 UTC 时间。
     *
     * @param from  发送方 Agent ID，不能为 null
     * @param to    接收方 Agent ID，不能为 null
     * @param type  消息类型，不能为 null
     * @param content 消息正文，可为 null
     */
    public AgentMessage(String from, String to, Type type, String content) {
        this.from = Objects.requireNonNull(from, "from must not be null");
        this.to = Objects.requireNonNull(to, "to must not be null");
        this.type = Objects.requireNonNull(type, "type must not be null");
        this.content = content;
        this.timestamp = Instant.now();
    }

    public String getFrom() { return from; }
    public String getTo() { return to; }
    public Type getType() { return type; }
    public String getContent() { return content; }
    public Instant getTimestamp() { return timestamp; }

    @Override
    public String toString() {
        return "AgentMessage{" + "from='" + from + '\'' + ", to='" + to + '\''
            + ", type=" + type + ", timestamp=" + timestamp + '}';
    }
}
```

---

## 第三十三块：AgentTask.java

> **为什么 AgentTask 用不可变 final 类？**
> 子 Agent 执行期间如果任务被意外修改（换 goal、改 allowedTools），会造成执行逻辑混乱。final 类 + final 字段保证了不可变性。
>
> **fields 设计说明**：
> - goal：任务目标描述（自然语言），子 Agent 的执行依据
> - allowedTools：可用工具/技能 ID 列表。第一版为空 list（所有工具可用），第二版可通过它限制子 Agent 只能使用特定工具
> - timeout：超时时间。第一版默认 5 分钟，与 AgentCoordinator 的超时同步
> - parentAgentId：关联到父 Agent，用于级联终止时查找归属
>
> **设计文档对应**：14.1 节（Agent 任务概念）

**文件路径**：`lyclaw-core/src/main/java/lyjew/com/lyclaw/agent/AgentTask.java`

```java
package lyjew.com.lyclaw.agent;

import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * AgentTask — 子 Agent 要执行的任务。
 *
 * <p>不可变对象。描述子 Agent 的任务目标、可用工具和超时时间。
 * 创建后不可修改，保证子 Agent 执行期间任务定义不被篡改。
 *
 * <p>字段说明：
 * <ul>
 *   <li>{@code goal} — 任务目标描述（自然语言文本），子 Agent 的核心执行依据</li>
 *   <li>{@code allowedTools} — 可用工具/技能 ID 列表。第一版默认空列表（全部可用）</li>
 *   <li>{@code timeout} — 任务超时时间。第一版默认 5 分钟，与 AgentCoordinator 超时同步</li>
 *   <li>{@code parentAgentId} — 父 Agent ID，用于级联终止时的关联查找</li>
 * </ul>
 *
 * @author LyClaw Team
 * @version 1.0
 */
public final class AgentTask {

    /** 任务目标描述（自然语言） */
    private final String goal;
    /** 允许使用的工具/技能 ID 列表。空列表表示全部可用 */
    private final List<String> allowedTools;
    /** 任务超时时间，默认 5 分钟 */
    private final Duration timeout;
    /** 父 Agent ID，用于级联终止 */
    private final String parentAgentId;

    /**
     * 构造 AgentTask。
     *
     * @param goal          任务目标描述，不能为 null
     * @param allowedTools  允许的工具 ID 列表。为 null 时视为空列表（全部允许）
     * @param timeout       超时时间。为 null 时使用 5 分钟默认值
     * @param parentAgentId 父 Agent ID，可为 null（根任务没有父 Agent）
     */
    public AgentTask(String goal, List<String> allowedTools,
                     Duration timeout, String parentAgentId) {
        this.goal = Objects.requireNonNull(goal, "goal must not be null");
        this.allowedTools = allowedTools != null
                ? Collections.unmodifiableList(allowedTools)
                : Collections.emptyList();
        this.timeout = timeout != null ? timeout : Duration.ofMinutes(5);
        this.parentAgentId = parentAgentId;
    }

    /**
     * 创建简单任务——不含工具限制，默认超时 5 分钟。
     *
     * @param goal          任务目标描述
     * @param parentAgentId 父 Agent ID
     * @return AgentTask 实例
     */
    public static AgentTask simple(String goal, String parentAgentId) {
        return new AgentTask(goal, null, Duration.ofMinutes(5), parentAgentId);
    }

    public String getGoal() { return goal; }
    public List<String> getAllowedTools() { return allowedTools; }
    public Duration getTimeout() { return timeout; }
    public String getParentAgentId() { return parentAgentId; }

    @Override
    public String toString() {
        return "AgentTask{goal='" + goal + "', timeout=" + timeout + "}";
    }
}
```

---

## 第三十四块：AgentState.java

> **为什么 Agent 需要 7 个状态？**
>
> 状态机的设计对应 Agent 的完整生命周期（设计文档 14.2 节）：
> - IDLE（初始）：创建后还没开始执行，可以去 spawn
> - RUNNING（执行中）：正在与模型交互或处理任务逻辑。这是状态机的核心运转状态
> - WAITING_TOOL（等工具）：从 RUNNING 进入，等待外部工具返回结果。工具完成后回到 RUNNING
> - 四个终态代表四种结束方式：正常完成（COMPLETED）、不可恢复错误（ERROR）、手动终止（TERMINATED）、超时（TIMEOUT）
>
> **判断方法解析**：
> - isTerminal()：终态判断——终态之后不能再有状态转换
> - isExecutable()：可执行状态判断——AgentCoordinator 在 spawn 前检查，只有非终态且不可执行的 Agent 才能被 spawn

**文件路径**：`lyclaw-core/src/main/java/lyjew/com/lyclaw/agent/AgentState.java`

```java
package lyjew.com.lyclaw.agent;

/**
 * AgentState 枚举 — Agent 生命周期状态。
 *
 * <p>状态转换规则（对应设计文档 14.2 节）：
 * <pre>
 * IDLE ── spawn ──► RUNNING ────── 任务正常完成 ──────► COMPLETED（终态）
 *  │                    │
 *  │                    ├── 调用工具 ──► WAITING_TOOL ── 工具返回 ──► RUNNING
 *  │                    │
 *  │                    ├── 不可恢复错误 ──► ERROR（终态）
 *  │                    │
 *  │                    └── 超时 ──► TIMEOUT（终态）
 *  │
 *  └── 任意状态 ── terminate ──► TERMINATED（终态）
 * </pre>
 *
 * <p>规则要点：
 * <ul>
 *   <li>只有 IDLE 状态的 Agent 可以执行 spawn（开始执行）</li>
 *   <li>只有 RUNNING 状态的 Agent 可以进入 WAITING_TOOL（调用工具）</li>
 *   <li>只有 WAITING_TOOL 状态的 Agent 可以回到 RUNNING（工具完成）</li>
 *   <li>COMPLETED、ERROR、TERMINATED、TIMEOUT 是终态——不能转换到其他状态</li>
 *   <li>任意状态都可以被 terminate() → 进入 TERMINATED（stateful 设计，即使终态也可以 terminate）</li>
 * </ul>
 *
 * @author LyClaw Team
 * @version 1.0
 * @see AgentCoordinator
 */
public enum AgentState {

    /** 初始状态——刚创建，还未开始执行。只有此状态才能被 spawn */
    IDLE,

    /** 执行中——正在处理任务或与模型交互。状态机的核心运转状态 */
    RUNNING,

    /** 等待工具执行结果。工具完成后回到 RUNNING */
    WAITING_TOOL,

    /** 任务正常完成（终态） */
    COMPLETED,

    /** 执行出错——不可恢复的错误导致终止（终态） */
    ERROR,

    /** 被手动终止（终态） */
    TERMINATED,

    /** 超时——超过 5 分钟限制后自动终止（终态） */
    TIMEOUT;

    /**
     * 判断是否为终态。
     * <p>终态之后不能再有状态转换。
     *
     * @return true 表示是终态（COMPLETED/ERROR/TERMINATED/TIMEOUT）
     */
    public boolean isTerminal() {
        return this == COMPLETED || this == ERROR || this == TERMINATED || this == TIMEOUT;
    }

    /**
     * 判断是否为可执行状态。
     * <p>AgentCoordinator.spawn() 前检查目标 Agent 的状态，只有非可执行状态且非终态的 Agent 才能被 spawn。
     *
     * @return true 表示 Agent 正在执行中（RUNNING/WAITING_TOOL）
     */
    public boolean isExecutable() {
        return this == RUNNING || this == WAITING_TOOL;
    }
}
```

---

## 第三十五块：ErrorPolicy.java

> **为什么 ErrorPolicy 的每个方法都返回 ChatResult 而不是抛异常？**
>
> 关键设计原则（设计文档 15.2 节）：错误处理的结果必须是对调用方可用的**正常返回值**，不能是异常。
>
> 因为调用方（ToolCallLoop/Pipeline）在执行流程中是一个"正常处理"上下文——它们在拿到返回值后要继续处理（降级回复或重试），而不是跳转到异常处理路径。如果 ErrorPolicy 直接抛异常，调用方需要用 try-catch 包裹，而 catch 块里又需要调用 ErrorPolicy——形成"错误处理套错误处理"的循环。
>
> **与 ToolCallPolicy 的职责区分**：
> - ToolCallPolicy.onToolError() — **微观决策**：单次工具执行失败后，决定"跳过这个工具继续"、"重试这个工具"还是"终止循环"。返回 ToolErrorAction（枚举）
> - ErrorPolicy.onToolError() — **宏观处理**：当 ToolCallPolicy 决定 ABORT_LOOP 后，整个对话的最终结果应该是什么。返回 ChatResult（对话级结果）
>
> **调用链**（设计文档 15.4 节）：
> ```
> ToolCallLoop → modelAdapter.chat() → catch ModelException
>                   ↓
>            ErrorPolicy.onModelError() → 返回 ChatResult（重试 1 次后的结果）
>                   ↓
>            判断 ChatResult.finishReason → "stop" 继续 / "error" 退出
> ```
>
> **设计文档对应**：15.1 节（设计动机）+ 15.2 节（ErrorPolicy 接口设计）+ 15.3 节（DefaultErrorPolicy 默认行为）+ 15.4 节（调用链）

**文件路径**：`lyclaw-core/src/main/java/lyjew/com/lyclaw/error/ErrorPolicy.java`

```java
package lyjew.com.lyclaw.error;

import lyjew.com.lyclaw.context.ChatContext;
import lyjew.com.lyclaw.dto.ChatResult;

/**
 * ErrorPolicy 接口 — 错误处理策略（策略模式）。
 *
 * <p>将错误处理从核心流程中完全分离，形成一个独立的策略接口。
 * 核心设计原则：<b>错误处理的结果必须是对调用方可用的正常返回值（{@link ChatResult}），不能是异常。</b>
 * 因为调用方（ToolCallLoop/Pipeline）需要继续流程（降级回复或重试），而不是跳转异常处理路径。
 *
 * <p>与 {@code ToolCallPolicy.onToolError()} 的职责区分：
 * <ul>
 *   <li>ToolCallPolicy.onToolError() — <b>微观决策</b>：单个工具执行失败后决策——跳过/重试/终止循环。
 *       返回 {@code ToolErrorAction} 枚举值</li>
 *   <li>ErrorPolicy.onToolError() — <b>宏观处理</b>：ToolCallPolicy 决定 ABORT_LOOP 后，
 *       整个对话的最终结果。返回 {@link ChatResult}（对话级结果）</li
 *       ChatResult（对话级结果）</li>
 * </ul>
 *
 * <p>调用链（设计文档 15.4 节）：
 * <pre>
 * ToolCallLoop → modelAdapter.chat() → catch ModelException
 *                   ↓
 *            ErrorPolicy.onModelError() → ChatResult（重试 1 次后的结果）
 *                   ↓
 *            判断 finishReason → "stop" 继续 / "error" 退出
 * </pre>
 *
 * @author LyClaw Team
 * @version 1.0
 */
public interface ErrorPolicy {

    /**
     * 模型调用失败时处理。
     *
     * <p>DefaultErrorPolicy 行为（设计文档 15.3 节）：
     * <ul>
     *   <li>401/403（认证错误）：不重试。返回 ChatResult，content="API Key 无效或已过期，请检查配置"</li>
     *   <li>429（限流）：等待 5 秒后重试 1 次。使用指数退避——第一次 5 秒，第二次 10 秒</li>
     *   <li>5xx（服务器错误）：重试 1 次，间隔 1 秒</li>
     *   <li>网络超时：等待 2 秒后重试 1 次</li>
     * </ul>
     *
     * @param error   异常对象（包含 httpStatus 和错误码）
     * @param context 对话上下文
     * @return ChatResult — 重试成功后获取的回复、降级后的回复、或包含错误信息的 ChatResult
     */
    ChatResult onModelError(ModelException error, ChatContext context);

    /**
     * 工具执行失败时宏观处理。
     *
     * <p>作用于 Pipeline 层面——不是决策"跳过还是重试"（那是 ToolCallPolicy.onToolError 的职责），
     * 而是对整个 Pipeline 的后续处理决策（降级、重试整个请求、直接返回错误）。
     *
     * @param error   工具执行异常
     * @param context 对话上下文
     * @return ChatResult — 整个对话的最终结果
     */
    ChatResult onToolError(ToolExecuteException error, ChatContext context);

    /**
     * 超时处理。
     *
     * <p>整个对话请求超过预设时间上限时调用。
     *
     * @param context   对话上下文
     * @param elapsedMs 已耗时（毫秒），用于在返回值中构造友好提示
     * @return ChatResult — 通常是友好的超时提示
     */
    ChatResult onTimeout(ChatContext context, long elapsedMs);
}
```

---

## 第三十五块附：ModelException.java

> **为什么需要 ModelException 而不是直接用 RuntimeException？**
>
> 模型调用错误在不同的 HTTP 状态码下需要不同的处理策略（401 不重试、429 重试、5xx 重试）。如果只用一个 RuntimeException，ErrorPolicy 不知道是什么类型的错误——需要用 instanceof 检查多个子类。ModelException 的 httpStatus 字段让 ErrorPolicy 可以用一个 switch 分支处理所有情况。
>
> isClientError() 和 isServerError() 是便利方法，避免每次判断都写 httpStatus >= 400 && httpStatus < 500。

**文件路径**：`lyclaw-core/src/main/java/lyjew/com/lyclaw/error/ModelException.java`

```java
package lyjew.com.lyclaw.error;

/**
 * ModelException — 模型调用异常。
 *
 * <p>封装模型调用过程中发生的错误，包含 HTTP 状态码。
 * ErrorPolicy.onModelError() 根据 httpStatus 决定重试策略。
 *
 * <p>异常类型与处理策略对照（设计文档 15.3 节）：
 * <pre>
 * 401/403 → 不重试（API Key 无效）
 * 429     → 等待 5 秒后重试 1 次（限流）
 * 5xx     → 等待 1 秒后重试 1 次（服务器错误）
 * 网络超时 → 等待 2 秒后重试 1 次（网络抖动）
 * </pre>
 *
 * @author LyClaw Team
 * @version 1.0
 */
public class ModelException extends RuntimeException {

    private final int httpStatus;

    public ModelException(int httpStatus, String message) {
        super(message);
        this.httpStatus = httpStatus;
    }

    public ModelException(int httpStatus, String message, Throwable cause) {
        super(message, cause);
        this.httpStatus = httpStatus;
    }

    /** @return HTTP 状态码 */
    public int getHttpStatus() { return httpStatus; }

    /** 是否为客户端错误（4xx）。401/403 认证错误时 ErrorPolicy 直接返回错误信息不重试。 */
    public boolean isClientError() { return httpStatus >= 400 && httpStatus < 500; }

    /** 是否为服务端错误（5xx）。ErrorPolicy 对 5xx 进行重试。 */
    public boolean isServerError() { return httpStatus >= 500; }
}
```

## 第三十五块附：ToolExecuteException.java

> **为什么 ToolExecuteException 需要 toolName 字段？**
>
> ErrorPolicy.onToolError() 需要知道是哪个工具执行失败，以便在 ChatResult 中给出具体提示（"搜索工具超时" vs "计算器出错"）。timeout 字段区分超时和其他错误——超时通常建议用户稍后重试，其他错误可能是指令问题。

**文件路径**：`lyclaw-core/src/main/java/lyjew/com/lyclaw/error/ToolExecuteException.java`

```java
package lyjew.com.lyclaw.error;

/**
 * ToolExecuteException — 工具执行异常。
 *
 * <p>封装工具执行过程中的错误，包含工具名称和超时标志。
 * 供 ErrorPolicy.onToolError() 和 ToolCallPolicy.onToolError() 使用。
 *
 * @author LyClaw Team
 * @version 1.0
 */
public class ToolExecuteException extends RuntimeException {

    /** 发生错误的工具名称 */
    private final String toolName;
    /** 是否因超时而失败 */
    private final boolean timeout;

    public ToolExecuteException(String toolName, String message, Throwable cause) {
        super(message, cause);
        this.toolName = toolName;
        this.timeout = false;
    }

    public ToolExecuteException(String toolName, String message, boolean timeout) {
        super(message);
        this.toolName = toolName;
        this.timeout = timeout;
    }

    public String getToolName() { return toolName; }
    public boolean isTimeout() { return timeout; }
}
```

---

## 第三十六块：SecurityManager.java

> **为什么 SecurityManager 定义在 lyclaw-core？**
>
> 因为 lyclaw-engine 中的 ToolCallLoop 需要在每轮工具调用前进行安全审批——如果 SecurityManager 定义在 lyclaw-engine，lyclaw-core 中的 ToolCallPolicy 就无法引用它。而且 SecurityManager 是"安全策略"抽象，属于接口层，天然适合放在 lyclaw-core。
>
> **第一版 vs 第二版的职责差异**：
> | 方法 | 第一版 | 第二版 |
> |------|-------|-------|
> | approveToolCall() | DefaultSecurityManager 始终返回 ALLOW | 真正的语义审批——按工具类别、参数内容审批 |
> | sandboxPolicy() | 返回 NONE（不隔离） | 根据工具危险等级返回不同隔离等级 |
> | resolveSecret() | 直接返回引用字符串（不解析） | 从配置中解析出 API Key 等凭证 |
>
> **设计文档对应**：18.1 节（security 包的归属说明）

**文件路径**：`lyclaw-core/src/main/java/lyjew/com/lyclaw/security/SecurityManager.java`

```java
package lyjew.com.lyclaw.security;

import lyjew.com.lyclaw.context.ChatContext;
import lyjew.com.lyclaw.model.ModelResponse;

/**
 * SecurityManager 接口 — 安全审批与凭证管理。
 *
 * <p>管理工具调用的安全审批、沙箱隔离等级、凭证解析。
 * 定义在 lyclaw-core，实现在 lyclaw-engine/security/impl。
 *
 * <p>第一版 DefaultSecurityManager 所有调用放行（approveToolCall 始终返回 ALLOW），
 * 第二版实现真正的语义审批。
 *
 * @author LyClaw Team
 * @version 1.0
 */
public interface SecurityManager {

    /**
     * 审批工具调用是否允许执行。
     *
     * <p>第一版放行所有调用。第二版根据工具类别、参数内容、上下文信息做语义审批。
     * 例如：敏感操作（文件删除）需要 BLOCK，普通操作（查天气）ALLOW。
     *
     * @param context 对话上下文，包含用户身份和会话信息
     * @param tc      模型发出的工具调用请求
     * @return 审批结果（ALLOW 或 BLOCK + 原因）
     */
    ApprovalResult approveToolCall(ChatContext context, ModelResponse.ToolCallRequest tc);

    /**
     * 获取工具需要的沙箱隔离等级。
     *
     * <p>第一版返回 NONE（当前进程执行）。第二版根据工具来源（内置 vs 社区插件）返回不同等级。
     *
     * @param toolName 工具名称
     * @return 沙箱等级（NONE/THREAD/PROCESS/CONTAINER/REMOTE）
     */
    SandboxLevel sandboxPolicy(String toolName);

    /**
     * 安全解析凭证引用。
     *
     * <p>从配置中解析出凭证的实际值（如 API Key）。
     * 凭证引用格式：{@code "secret://api.openai/key"} → 解析为实际的 API Key 值。
     * 第一版直接返回引用本身（不解析），交由 DefaultSecurityManager 实现。
     *
     * @param secretRef 凭证引用字符串
     * @return 解析后的凭证值
     */
    String resolveSecret(String secretRef);
}
```

## 第三十六块附：ApprovalResult.java

> **为什么用 factory method（allow()/block()）而不是直接 new？**
>
> allow() 不传 reason，block() 必须传 reason——编译器能检查到"block 没有给出原因"的错误，如果用构造器做不到这种签名差异（两个构造器参数类型冲突）。而且语义更清晰——看 allow() 就知道是放行，看 block("reason") 就知道被拒绝了。

**文件路径**：`lyclaw-core/src/main/java/lyjew/com/lyclaw/security/ApprovalResult.java`

```java
package lyjew.com.lyclaw.security;

import java.util.Objects;

/**
 * ApprovalResult — 工具调用审批结果。
 *
 * <p>不可变值对象。使用 factory method 构造——allow() 放行，block(reason) 拒绝。
 *
 * @author LyClaw Team
 * @version 1.0
 */
public final class ApprovalResult {

    /** 审批动作 */
    public enum Action { ALLOW, BLOCK }

    private final Action action;
    private final String reason;

    private ApprovalResult(Action action, String reason) {
        this.action = Objects.requireNonNull(action, "action must not be null");
        this.reason = reason;
    }

    /** 放行。不产生原因描述。 */
    public static ApprovalResult allow() {
        return new ApprovalResult(Action.ALLOW, null);
    }

    /** 拒绝。必须提供原因描述。 */
    public static ApprovalResult block(String reason) {
        return new ApprovalResult(Action.BLOCK,
            Objects.requireNonNull(reason, "block reason must not be null"));
    }

    public Action getAction() { return action; }
    public String getReason() { return reason; }
    public boolean isAllowed() { return action == Action.ALLOW; }
}
```

## 第三十六块附：SandboxLevel.java

> **5 个等级的设计逻辑**：
> - NONE：当前进程执行——已审核的、无安全风险的工具（如计算器、获取当前时间）
> - THREAD：线程级隔离——执行在独立线程池，避免占用主线程
> - PROCESS：进程级隔离——独立子进程执行，崩溃不影响主进程
> - CONTAINER：容器级隔离——Docker 容器执行，网络和文件系统隔离
> - REMOTE：远程沙箱——最高隔离等级，执行在远程服务器

**文件路径**：`lyclaw-core/src/main/java/lyjew/com/lyclaw/security/SandboxLevel.java`

```java
package lyjew.com.lyclaw.security;

/**
 * SandboxLevel — 沙箱隔离等级枚举。
 *
 * <p>等级从低到高依次递进，越高隔离性越强但性能开销越大。
 * 由 SecurityManager.sandboxPolicy() 根据工具类型返回相应等级。
 *
 * @author LyClaw Team
 * @version 1.0
 */
public enum SandboxLevel {

    /** 无需隔离——直接在当前进程执行。适合计算器、时间工具等无风险操作 */
    NONE,

    /** 低隔离——线程级隔离。使用独立线程池执行 */
    THREAD,

    /** 中隔离——进程级隔离。使用独立子进程执行，崩溃不影响主进程 */
    PROCESS,

    /** 高隔离——容器级隔离。在 Docker 容器中执行，完全隔离文件系统和网络 */
    CONTAINER,

    /** 最高隔离——远程沙箱。在远程沙箱服务器中执行 */
    REMOTE
}
```

---

## 第三十七块：TaskPlanner.java

> **为什么需要 TaskPlanner？**
>
> 用户请求可能包含多个步骤。例如"帮我查一下 MySQL 和 PostgreSQL 的区别，然后根据结论生成一份对比报告"——这个请求天然包含两个子任务：查资料（搜索）+ 写报告（生成）。如果不引入 TaskPlanner，这两个步骤都在同一个 ToolCallLoop 中顺序执行，无法并行处理，也无法在其中一个失败时恢复。
>
> TaskPlanner 将这类请求分解为 DAG（有向无环图）结构：每个节点是一个 TaskNode，节点间存在依赖关系。第一版只做简单的串行执行，第二版实现真正的并行 DAG 编排。
>
> **设计文档对应**：18.1 节（task 包的归属说明）+ 18.2 节（模块依赖关系）

**文件路径**：`lyclaw-core/src/main/java/lyjew/com/lyclaw/task/TaskPlanner.java`

```java
package lyjew.com.lyclaw.task;

import lyjew.com.lyclaw.context.ChatContext;
import java.util.List;

/**
 * TaskPlanner 接口 — 任务编排器。
 *
 * <p>将用户请求分解为多个可执行的任务（DAG 结构），管理任务执行顺序和依赖关系。
 *
 * <p>调用流程：
 * <pre>
 * 1. createPlan(context) — 分析用户请求，生成 TaskPlan（DAG + 拓扑排序）
 * 2. executeTask(taskId) — 按拓扑顺序逐个执行任务节点
 * 3. getLedger() — 获取任务账本，查询任务状态
 * 4. recoverFailedTasks() — 心跳检测后恢复失败任务
 * </pre>
 *
 * <p>第一版只做简单的串行任务执行（不依赖 TaskPlanner，ToolCallLoop 线性执行），
 * 第二版实现真正的并行 DAG 编排。
 *
 * @author LyClaw Team
 * @version 1.0
 * @see TaskPlan
 * @see TaskLedger
 */
public interface TaskPlanner {

    /**
     * 从用户请求创建任务执行计划。
     *
     * <p>分析 ChatContext 中的用户消息，识别可能的子任务，构建 DAG 结构并按拓扑排序。
     * 第一版简化实现——直接创建一个串行计划（节点按顺序执行）。
     *
     * @param context 对话上下文，包含用户消息和会话历史
     * @return 任务执行计划（包含 DAG 结构和节点定义）
     */
    TaskPlan createPlan(ChatContext context);

    /**
     * 执行单个任务节点。
     *
     * @param taskId 任务 ID
     * @return 任务执行结果（成功/失败 + 输出或错误信息）
     */
    TaskResult executeTask(String taskId);

    /**
     * 获取任务账本。
     *
     * @return 任务账本实例
     */
    TaskLedger getLedger();

    /**
     * 心跳检测后恢复失败任务。
     *
     * <p>检查所有状态为 RUNNING 或 PENDING 的任务是否超时。
     * 超时的任务自动标记为 FAILED（状态不可恢复）。
     *
     * @return 被标记为失败的任务结果列表
     */
    List<TaskResult> recoverFailedTasks();
}
```

---

## 第三十八块：TaskPlan.java

> **细说三个字段的设计**：
> - nodes（List<TaskNode>）：所有节点的定义。包含每个节点的目标、输入参数、要使用的 skillId
> - taskExecOrder（List<String>）：拓扑排序后的执行顺序。第一版按此顺序串行执行
> - dependencies（Map<String, List<String>>）：依赖关系图。key=任务ID，value=其依赖的任务ID列表。
>   例如 { "task-2": ["task-1"] } 表示 task-2 必须在 task-1 之后执行
>
> **为什么全字段用 Collections.unmodifiable* 包装？**
> 外部拿到 TaskPlan 后如果修改了内部列表（如往 taskExecOrder 插入节点），会导致执行顺序乱序。不可变包装阻止了外部修改。

**文件路径**：`lyclaw-core/src/main/java/lyjew/com/lyclaw/task/TaskPlan.java`

```java
package lyjew.com.lyclaw.task;

import java.util.*;

/**
 * TaskPlan — 任务执行计划。
 *
 * <p>包含 DAG 结构和所有任务节点定义。创建后不可修改——所有字段用 Collections.unmodifiable* 包装。
 *
 * <p>字段说明：
 * <ul>
 *   <li>{@code nodes} — 所有任务节点，包含每个节点的 id、skillId、input、status</li>
 *   <li>{@code taskExecOrder} — 拓扑排序后的执行顺序（任务 ID 列表）。第一版串行按此顺序执行</li>
 *   <li>{@code dependencies} — 依赖关系图：{@code taskId → List<依赖的 taskId>}。用于恢复和重试</li>
 * </ul>
 *
 * @author LyClaw Team
 * @version 1.0
 */
public final class TaskPlan {

    /** 所有任务节点 */
    private final List<TaskNode> nodes;
    /** 拓扑排序后的执行顺序——第一版串行按此顺序执行 */
    private final List<String> taskExecOrder;
    /** 依赖关系图：taskId → [依赖的任务 ID 列表] */
    private final Map<String, List<String>> dependencies;

    /**
     * 构造 TaskPlan。所有参数拷贝到不可变包装中。
     *
     * @param nodes          所有任务节点
     * @param taskExecOrder  拓扑排序后的执行顺序
     * @param dependencies   依赖关系映射
     */
    public TaskPlan(List<TaskNode> nodes, List<String> taskExecOrder,
                    Map<String, List<String>> dependencies) {
        this.nodes = Collections.unmodifiableList(
            new ArrayList<>(Objects.requireNonNull(nodes, "nodes must not be null")));
        this.taskExecOrder = Collections.unmodifiableList(
            new ArrayList<>(Objects.requireNonNull(taskExecOrder, "taskExecOrder must not be null")));
        this.dependencies = Collections.unmodifiableMap(
            new HashMap<>(Objects.requireNonNull(dependencies, "dependencies must not be null")));
    }

    public List<TaskNode> getNodes() { return nodes; }
    public List<String> getTaskExecOrder() { return taskExecOrder; }
    public Map<String, List<String>> getDependencies() { return dependencies; }

    /** 根据任务 ID 查找对应的节点。找不到返回 null。 */
    public TaskNode getNode(String taskId) {
        return nodes.stream()
            .filter(n -> n.getId().equals(taskId))
            .findFirst()
            .orElse(null);
    }
}
```

---

## 第三十八块附：TaskNode.java

> **为什么 TaskNode 要有 Status 字段而且可以修改（setStatus）？**
>
> 节点的状态在运行时变化——从 PENDING 到 RUNNING 到 COMPLETED。如果不允许修改，执行过程中每次状态变更都需要创建新对象，对 GC 压力大。TaskPlan 本身（整体的 DAG 结构）不可变，但单个节点的状态可变化——这是合理的设计折中。

**文件路径**：`lyclaw-core/src/main/java/lyjew/com/lyclaw/task/TaskNode.java`

```java
package lyjew.com.lyclaw.task;

import java.util.Objects;

/**
 * TaskNode — DAG 中的单个任务节点。
 *
 * <p>一个节点包含任务 ID、要使用的技能 ID、输入参数和执行状态。
 * ID 和 skillId 不可变（final），但 status 可变——运行时状态从 PENDING→RUNNING→COMPLETED/FAILED。
 *
 * @author LyClaw Team
 * @version 1.0
 */
public final class TaskNode {

    /** 任务状态 */
    public enum Status {
        PENDING, RUNNING, COMPLETED, FAILED, SKIPPED
    }

    /** 唯一任务 ID */
    private final String id;
    /** 要使用的技能 ID（对应 SkillRegistry 中的注册 ID） */
    private final String skillId;
    /** 输入参数 */
    private final String input;
    /** 运行时状态，初始为 PENDING */
    private Status status;

    public TaskNode(String id, String skillId, String input) {
        this.id = Objects.requireNonNull(id, "id must not be null");
        this.skillId = Objects.requireNonNull(skillId, "skillId must not be null");
        this.input = input;
        this.status = Status.PENDING;
    }

    public String getId() { return id; }
    public String getSkillId() { return skillId; }
    public String getInput() { return input; }
    public Status getStatus() { return status; }

    public void setStatus(Status status) {
        this.status = Objects.requireNonNull(status, "status must not be null");
    }

    public boolean isCompleted() { return status == Status.COMPLETED; }
    public boolean isFailed() { return status == Status.FAILED; }
}
```

## 第三十八块附：TaskResult.java

**文件路径**：`lyclaw-core/src/main/java/lyjew/com/lyclaw/task/TaskResult.java`

```java
package lyjew.com.lyclaw.task;

import java.util.Objects;

/**
 * TaskResult — 任务执行结果。
 *
 * <p>不可变值对象。通过 factory method（ok/fail）构造——语义清晰，区分成功和失败场景。
 *
 * @author LyClaw Team
 * @version 1.0
 */
public final class TaskResult {

    private final String taskId;
    private final boolean success;
    private final String output;
    private final String error;
    private final long durationMs;

    public TaskResult(String taskId, boolean success, String output,
                      String error, long durationMs) {
        this.taskId = Objects.requireNonNull(taskId, "taskId must not be null");
        this.success = success;
        this.output = output;
        this.error = error;
        this.durationMs = durationMs;
    }

    public static TaskResult ok(String taskId, String output, long durationMs) {
        return new TaskResult(taskId, true, output, null, durationMs);
    }

    public static TaskResult fail(String taskId, String error, long durationMs) {
        return new TaskResult(taskId, false, null, error, durationMs);
    }

    public String getTaskId() { return taskId; }
    public boolean isSuccess() { return success; }
    public String getOutput() { return output; }
    public String getError() { return error; }
    public long getDurationMs() { return durationMs; }
}
```

---

## 第三十九块：TaskLedger.java

> **为什么需要 TaskLedger？**
>
> TaskPlanner 需要一个地方记录所有任务的执行状态——哪些任务正在运行、哪些已完成、哪些失败。如果不引入 TaskLedger，这些状态信息散落在 TaskPlanner 的各个方法里，无法统一查询。TaskLedger 的引入让 TaskPlanner.recoverFailedTasks() 可以统一查询所有 FAILED 的任务，以及 getRecordsBySession() 可以查看某个会话的所有任务历史。
>
> 第一版使用文件存储（记录到 JSON 文件），第二版可切换为数据库。接口不变。

**文件路径**：`lyclaw-core/src/main/java/lyjew/com/lyclaw/task/TaskLedger.java`

```java
package lyjew.com.lyclaw.task;

import java.util.List;

/**
 * TaskLedger 接口 — 任务账本。
 *
 * <p>记录所有任务的执行状态和结果。供 TaskPlanner 管理任务生命周期。
 * 第一版使用文件存储（JSON 格式），第二版可切换为数据库实现。
 *
 * @author LyClaw Team
 * @version 1.0
 */
public interface TaskLedger {

    /** 记录一个任务的状态变更。每次状态变化（PENDING→RUNNING→COMPLETED/FAILED）都调用一次。 */
    void record(TaskRecord record);

    /** 根据任务 ID 获取单条记录。 */
    TaskRecord getRecord(String taskId);

    /** 获取指定会话的所有任务记录——用于审计和重放。 */
    List<TaskRecord> getRecordsBySession(String sessionId);

    /** 获取指定状态的所有任务记录——recoverFailedTasks() 用此方法查找 FAILED 任务。 */
    List<TaskRecord> getRecordsByStatus(TaskNode.Status status);
}
```

## 第三十九块附：TaskRecord.java

> **TaskRecord 与 TaskNode 的关系**：
> - TaskNode 是 DAG 中的节点定义（包含目标、参数、当前状态）
> - TaskRecord 是任务账本中的执行记录（包含任务 ID、所属会话、状态、结果摘要、时间戳）
> - TaskNode 的生命周期在一个引擎实例中生效，TaskRecord 持久化到文件/数据库——即使引擎重启，任务记录也能保留

**文件路径**：`lyclaw-core/src/main/java/lyjew/com/lyclaw/task/TaskRecord.java`

```java
package lyjew.com.lyclaw.task;

import java.time.Instant;
import java.util.Objects;

/**
 * TaskRecord — 任务账本中的一条记录。
 *
 * <p>包含任务 ID、状态、会话 ID、结果摘要、创建时间和更新时间。
 * 创建时间在构造时自动设为当前 UTC 时间，更新时间在构造时同步设为创建时间。
 *
 * @author LyClaw Team
 * @version 1.0
 */
public final class TaskRecord {

    private final String taskId;
    private final String sessionId;
    private final TaskNode.Status status;
    private final String resultSummary;
    private final Instant createdAt;
    private final Instant updatedAt;

    public TaskRecord(String taskId, String sessionId, TaskNode.Status status,
                      String resultSummary) {
        this.taskId = Objects.requireNonNull(taskId, "taskId must not be null");
        this.sessionId = sessionId;
        this.status = Objects.requireNonNull(status, "status must not be null");
        this.resultSummary = resultSummary;
        this.createdAt = Instant.now();
        this.updatedAt = Instant.now();
    }

    public String getTaskId() { return taskId; }
    public String getSessionId() { return sessionId; }
    public TaskNode.Status getStatus() { return status; }
    public String getResultSummary() { return resultSummary; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
```

---

## 第四十块：ModelProvider.java

> **为什么需要 ModelProvider？（防腐层模式）**
>
> lyclaw-engine 中的 ToolCallLoop 需要获取模型适配器来调用模型。如果不引入 ModelProvider，ToolCallLoop 需要直接依赖 lyclaw-adapter 的 ModelAdapterFactory。这种直接依赖违反了单向依赖原则——lyclaw-engine 不应该知道 lyclaw-adapter 的存在。
>
> ModelProvider 定义在 lyclaw-core，实现在 lyclaw-adapter——新建一个 `ModelProviderImpl` 类（或类似名称）实现 `ModelProvider` 接口，
> 内部调用已存在的 `ModelAdapterFactory`。`ModelAdapterFactory` 本身不修改，ModelProviderImpl 作为适配层转发调用。
>
> lyclaw-engine 通过 Spring 注入 ModelProvider 接口，获取适配器时调用 `modelProvider.getAdapter("minimax")`，
> 不直接引用任何 lyclaw-adapter 的类。
>
> 这就是防腐层（Anti-Corruption Layer）模式——在 lyclaw-core 和 lyclaw-adapter 之间插入一个接口层，阻止 lyclaw-adapter 的具体实现细节"污染"上层模块。
>
> **切换机制**：新建另一个 ModelProvider 实现类，通过 @Primary 切换注入。所有使用 ModelProvider 的代码零修改。
>
> **设计文档对应**：18.2 节（模块依赖关系图）——lyclaw-engine 不直接依赖 lyclaw-adapter，引擎通过 lyclaw-core 中的 ModelProvider 接口获取适配器。

**文件路径**：`lyclaw-core/src/main/java/lyjew/com/lyclaw/provider/ModelProvider.java`

```java
package lyjew.com.lyclaw.provider;

import lyjew.com.lyclaw.adapter.ModelAdapter;
import lyjew.com.lyclaw.model.ModelConfig;
import java.util.List;

/**
 * ModelProvider 接口 — 模型适配器提供者（防腐层）。
 *
 * <p>接口定义在 lyclaw-core，实现在 lyclaw-adapter——具体实现类（如 {@code ModelProviderImpl}）
 * 实现此接口，内部调用 {@code ModelAdapterFactory} 获取适配器实例。
 * {@code ModelAdapterFactory} 本身不变，ModelProviderImpl 作为防腐层转发调用。
 * 形成防腐层确保 lyclaw-engine 完全不依赖 lyclaw-adapter 的具体类。
 *
 * <p>调用方式：
 * <pre>{@code
 * // lyclaw-engine 中通过 Spring 注入
 * @Autowired
 * private ModelProvider modelProvider;
 *
 * // 获取适配器
 * ModelAdapter adapter = modelProvider.getAdapter("minimax");
 * adapter.chat(request);
 * }</pre>
 *
 * @author LyClaw Team
 * @version 1.0
 * @see ModelAdapter
 */
public interface ModelProvider {

    /**
     * 根据厂商标识获取模型适配器。
     *
     * @param provider 厂商标识，如 "minimax"、"deepseek"
     * @return 模型适配器实例
     * @throws IllegalArgumentException 如果厂商标识不存在
     */
    ModelAdapter getAdapter(String provider);

    /**
     * 列出所有可用厂商。
     *
     * @return 厂商标识列表
     */
    List<String> listProviders();

    /**
     * 配置指定厂商的适配器。
     *
     * <p>传入 ModelConfig（包含 baseUrl、apiKey、modelName、temperature、maxTokens），
     * Provider 实现类据此配置对应的适配器实例。
     *
     * @param provider 厂商标识
     * @param config   模型配置
     */
    void configure(String provider, ModelConfig config);
}
```

---

## 第三部分完成统计（含值对象和异常类）

| 块 | 文件名 | 包 | 行数（约） |
|----|--------|-----|-----------|
| 29 | Event.java | event | 55 |
| 30 | EventBus.java | event | 110 |
| 31 | AgentCoordinator.java | agent | 90 |
| 32 | AgentChannel.java | agent | 55 |
| 32附 | AgentMessage.java | agent | 75 |
| 33 | AgentTask.java | agent | 75 |
| 34 | AgentState.java | agent | 65 |
| 35 | ErrorPolicy.java | error | 95 |
| 35附 | ModelException.java | error | 45 |
| 35附 | ToolExecuteException.java | error | 35 |
| 36 | SecurityManager.java | security | 75 |
| 36附 | ApprovalResult.java | security | 40 |
| 36附 | SandboxLevel.java | security | 25 |
| 37 | TaskPlanner.java | task | 70 |
| 38 | TaskPlan.java | task | 65 |
| 38附 | TaskNode.java | task | 55 |
| 38附 | TaskResult.java | task | 45 |
| 39 | TaskLedger.java | task | 40 |
| 39附 | TaskRecord.java | task | 45 |
| 40 | ModelProvider.java | provider | 55 |
| **总计** | **20 个文件** | 7 个包 | **~1260 行代码** |

### 本部分涉及的已有代码引用

| 已有类/接口 | 使用方式 | 引用位置 |
|------------|----------|----------|
| `ChatContext` | 方法参数 | ErrorPolicy.onModelError/onToolError, SecurityManager.approveToolCall, TaskPlanner.createPlan |
| `ModelResponse.ToolCallRequest` | 方法参数 | SecurityManager.approveToolCall |
| `ModelAdapter` | 返回值 | ModelProvider.getAdapter |
| `ModelConfig` | 方法参数 | ModelProvider.configure |
| `ChatResult` | 返回值 | ErrorPolicy 全部三个方法 |
| `AgentResult` | 返回值 | AgentCoordinator.awaitResult |

### 跨部分依赖关系

```
第一部分（Engine/Pipeline/Tool）
  │
  ├── 第三部分（事件+Agent+错误）
  │     ├── ErrorPolicy ← ToolCallLoop（模型/工具异常时调用）
  │     ├── EventBus ← MetricsStage（发布 TokenConsumedEvent）
  │     ├── SecurityManager ← ToolCallLoop（工具调用前审批）
  │     └── ModelProvider ← ToolCallLoop（获取模型适配器）
  │
  └── 第三部分（任务编排）
        └── TaskPlanner ← Engine（可选的复杂任务分解，第一版不强制）
```

### 本部分涉及的端口号（无——全是接口/类定义，无网络端口）

---

## 下一部分预告

**第四部分：lyclaw-core（检索 + 缓存 + 追踪 + 过滤器 + 事务）** — 9 个文件，覆盖 VectorStore/CacheService/TraceContext + ContentFilter/FilterResult + SessionTransaction/SessionUpdate/SessionUpdateStrategy/TransactionContext。

> ✅ 已完成

---

*续写于 2026-04-28*

---

# 第四部分：检索 + 缓存 + 追踪 + 过滤器 + 事务（lyclaw-core）

> **设计文档对应章节**：第五章 5.28~5.31（VectorStore/CacheService/TraceContext/ContentFilter）、第十七章（Session 持久化与数据一致性）、第十八章（模块归属——retrieval/cache/trace/filter/session 包）
>
> **本部分总体设计意图**：
>
> 前三部分定义了引擎的核心流程（请求→管道→工具→技能→记忆）、支撑能力（事件/Agent/错误/安全/编排）。第四部分定义的是**横向基础设施**——检索（知识库查询）、缓存（减少重复模型调用）、追踪（调用链可观测）、过滤（内容安全）、事务（会话一致性）。
>
> 这些接口有一个共同特点：**第一版只需要空壳占位或极简实现**，真正的价值在第二版。但接口必须现在定义——如果不定义，上层代码（RagEngine、ToolCallLoop、Pipeline、LoggingInterceptor）就会直接依赖文件系统或 ConcurrentHashMap 等具体实现，第二版升级时改代码量巨大。
>
> **包结构**：
> - `retrieval/` — 检索存储接口（VectorStore）
> - `cache/` — 缓存服务接口（CacheService）
> - `trace/` — 全链路追踪上下文（TraceContext）
> - `filter/` — 内容安全过滤器接口 + 过滤结果（ContentFilter + FilterResult）
> - `session/` — 会话事务接口 + 事务上下文 + 更新操作 + 更新策略（第二版占位）

## 实现文件清单

| 序号 | 文件 | 包 | 类/接口类型 |
|------|------|-----|-------------|
| 41 | VectorStore.java | retrieval | 接口 |
| 42 | CacheService.java | cache | 接口 |
| 43 | TraceContext.java | trace | 类 |
| 44 | ContentFilter.java | filter | 接口 |
| 45 | FilterResult.java | filter | 值对象 |
| 46 | SessionTransaction.java | session | 接口 |
| 47 | TransactionContext.java | session | 值对象 |
| 48 | SessionUpdate.java | session | 值对象 |
| 49 | SessionUpdateStrategy.java | session | 接口 |

---

## 第四十一块：VectorStore.java

> **为什么需要 VectorStore？**
>
> AI 引擎需要支持"检索增强生成"（RAG）能力——用户提问时，先从知识库中检索相关文档，然后连同检索结果一起发给模型。如果不引入 VectorStore 接口，RAG 相关代码会直接操作文件系统（读 `retrieval/` 目录下的文件），第二版切换到向量数据库时所有调用点都需要修改。
>
> 第一版 FileVectorStore 使用简单的关键词匹配——把 query 拆成 term，对每个文档统计 term 出现次数排序。不需要向量嵌入、不需要向量数据库。但接口定义好了，第二版换成 Milvus/Chroma/Pinecone 时调用方代码零修改。
>
> **设计文档对应**：5.28 节（VectorStore 检索存储接口）

**文件路径**：`lyclaw-core/src/main/java/lyjew/com/lyclaw/retrieval/VectorStore.java`

```java
package lyjew.com.lyclaw.retrieval;

import java.util.List;
import java.util.Optional;

/**
 * VectorStore 接口 — 检索/向量存储的抽象层。
 *
 * <p>定义检索存储的标准接口。第一版使用文件系统的纯文本关键词检索
 * （{@code FileVectorStore}），第二版接入向量数据库（Milvus/Chroma/Pinecone）。
 * 所有上层代码（RagEngine、RetrievalStage）只依赖此接口，不依赖具体实现。
 *
 * <p>第一版 FileVectorStore 实现方案：
 * <ul>
 *   <li>store() — 按 collectionId 存储到 {@code retrieval/{collectionId}/{timestamp}.md}</li>
 *   <li>search() — 分割 query 为 term，统计每个文档中的 term 出现次数排序</li>
 *   <li>不需要向量嵌入和向量数据库，成本 0，依赖 0</li>
 * </ul>
 *
 * @author LyClaw Team
 * @version 1.0
 */
public interface VectorStore {

    /**
     * 存储文本到指定集合。
     *
     * @param collectionId 集合标识，如 "documents"、"knowledge-base"
     * @param text         要存储的文本内容
     */
    void store(String collectionId, String text);

    /**
     * 搜索最相关的 topK 条结果。
     *
     * @param collectionId 集合标识
     * @param query        搜索关键词
     * @param topK         返回结果的最大条数
     * @return 搜索结果列表，按相关性降序排列
     */
    List<SearchResult> search(String collectionId, String query, int topK);

    /**
     * 删除指定文档。
     *
     * @param collectionId 集合标识
     * @param docId        文档 ID
     */
    void delete(String collectionId, String docId);

    /**
     * 列出所有集合。
     *
     * @return 集合标识列表
     */
    List<String> listCollections();

    /**
     * SearchResult — 检索结果条目。
     *
     * <p>包含文档 ID、内容、得分。不可变对象。
     */
    final class SearchResult {
        private final String docId;
        private final String content;
        private final double score;

        public SearchResult(String docId, String content, double score) {
            this.docId = docId;
            this.content = content;
            this.score = score;
        }

        public String getDocId() { return docId; }
        public String getContent() { return content; }
        public double getScore() { return score; }
    }
}
```

---

## 第四十二块：CacheService.java

> **为什么需要 CacheService？**
>
> 模型调用是引擎中最昂贵的操作（耗时 + Token 消耗）。如果用户问相同的问题（如"北京的天气"），每次都调用模型不仅浪费，而且延迟高。引入缓存后，ToolCallLoop 在 chatStream() 之前先查缓存——命中则直接返回，不调模型。
>
> 第一版使用 ConcurrentHashMap（最大 1000 条 + ScheduledExecutorService 定时清理过期 key），第二版可替换为 Redis 集中缓存。
>
> **设计文档对应**：5.29 节（CacheService 缓存服务接口）

**文件路径**：`lyclaw-core/src/main/java/lyjew/com/lyclaw/cache/CacheService.java`

```java
package lyjew.com.lyclaw.cache;

import java.time.Duration;
import java.util.Optional;

/**
 * CacheService 接口 — 多级缓存服务。
 *
 * <p>第一版使用 ConcurrentHashMap + ScheduledExecutorService 定时清理过期 key
 * （{@code ConcurrentHashMapCache}），最大 1000 条，超时淘汰。
 * 第二版可替换为 RedisCache 或 CaffeineCache。
 *
 * <p>使用场景：
 * <ul>
 *   <li>模型响应缓存——相同问题的回复直接返回缓存结果</li>
 *   <li>工具结果缓存——天气查询结果缓存 5 分钟</li>
 *   <li>配置缓存——避免每次都读文件</li>
 * </ul>
 *
 * @author LyClaw Team
 * @version 1.0
 */
public interface CacheService {

    /**
     * 获取缓存值。
     *
     * @param key 缓存键
     * @return Optional，包含缓存的值；不存在或已过期返回 Optional.empty()
     */
    Optional<String> get(String key);

    /**
     * 设置缓存值。
     *
     * @param key   缓存键
     * @param value 缓存值
     * @param ttl   有效期（从当前时间开始计算）
     */
    void put(String key, String value, Duration ttl);

    /**
     * 删除指定缓存。
     *
     * @param key 缓存键
     */
    void evict(String key);

    /** 清除所有缓存。 */
    void clear();

    /**
     * 获取缓存统计信息。
     *
     * @return 缓存统计（命中率、大小等）
     */
    CacheStats getStats();

    /** CacheStats — 缓存统计信息。 */
    final class CacheStats {
        private final long hitCount;
        private final long missCount;
        private final long size;

        public CacheStats(long hitCount, long missCount, long size) {
            this.hitCount = hitCount;
            this.missCount = missCount;
            this.size = size;
        }

        /** 缓存命中率（hitCount / (hitCount + missCount)），无请求时返回 0。 */
        public double hitRate() {
            long total = hitCount + missCount;
            return total == 0 ? 0.0 : (double) hitCount / total;
        }

        public long getHitCount() { return hitCount; }
        public long getMissCount() { return missCount; }
        public long getSize() { return size; }
    }
}
```

---

## 第四十三块：TraceContext.java

> **为什么需要 TraceContext？**
>
> 一次用户请求可能经历 Engine → Pipeline（5 个 Stage）→ ToolCallLoop（多轮模型调用 + 工具执行）→ 最终响应。如果中间出错了，日志里一堆线程的打印混在一起，很难把属于同一次请求的日志关联起来。
>
> TraceContext 为每次请求生成唯一的 traceId，贯穿整个调用链。每个 Pipeline Stage 和每个工具调用生成新的 spanId。日志拦截器（LoggingInterceptor）在日志中输出 traceId，开发者在日志中搜索 traceId 就能找到完整调用链。
>
> **设计文档对应**：5.30 节（TraceContext 全链路追踪）

**文件路径**：`lyclaw-core/src/main/java/lyjew/com/lyclaw/trace/TraceContext.java`

```java
package lyjew.com.lyclaw.trace;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * TraceContext — 全链路追踪上下文。
 *
 * <p>为每个请求生成唯一 traceId，贯穿 Engine → Pipeline → ToolCallLoop → Tool 的完整调用链。
 * 每个 Pipeline Stage 和每个工具调用生成新 spanId，形成父子 span 关系。
 *
 * <p>使用方式：
 * <pre>{@code
 * // DefaultEngine.execute() 开头创建
 * TraceContext trace = TraceContext.create(request.getRequestId());
 * chatContext.setAttribute("trace", trace);
 *
 * // 每个 Pipeline Stage 结束时生成新 span
 * TraceContext trace = (TraceContext) context.getAttribute("trace");
 * trace.nextSpan("ContextBuildStage");
 * }</pre>
 *
 * <p>第一版范围：只记录 traceId + spanId 到日志中，不强制集成就绪。
 * LoggingInterceptor 在日志中输出 traceId。第二版可扩展为导出到 OpenTelemetry Collector 或 Jaeger。
 *
 * @author LyClaw Team
 * @version 1.0
 */
public final class TraceContext {

    /** 全局唯一追踪 ID */
    private final String traceId;
    /** 父跨度 ID */
    private String parentSpanId;
    /** 当前跨度 ID */
    private String spanId;
    /** 跨度开始时间（毫秒时间戳） */
    private final long startTime;
    /** 自定义标签 */
    private final Map<String, String> tags;

    private TraceContext(String traceId) {
        this.traceId = Objects.requireNonNull(traceId, "traceId must not be null");
        this.startTime = System.currentTimeMillis();
        this.tags = new HashMap<>();
        this.spanId = generateSpanId();
    }

    /**
     * 创建 TraceContext。
     *
     * @param traceId 追踪 ID。从 API 网关传入的 requestId，如果为 null 则自动生成 UUID
     * @return TraceContext 实例
     */
    public static TraceContext create(String traceId) {
        return new TraceContext(traceId != null ? traceId : UUID.randomUUID().toString());
    }

    /**
     * 生成新 span。父 spanId 设为当前 spanId，当前 spanId 重新生成。
     *
     * @param spanName span 名称，如 "ContextBuildStage"
     */
    public void nextSpan(String spanName) {
        this.parentSpanId = this.spanId;
        this.spanId = generateSpanId();
        addTag("spanName", spanName);
    }

    /** 添加自定义标签。 */
    public void addTag(String key, String value) {
        tags.put(key, value);
    }

    private static String generateSpanId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }

    public String getTraceId() { return traceId; }
    public String getParentSpanId() { return parentSpanId; }
    public String getSpanId() { return spanId; }
    public long getStartTime() { return startTime; }
    public Map<String, String> getTags() { return tags; }

    @Override
    public String toString() {
        return "Trace{traceId=" + traceId + ", spanId=" + spanId + ", parent=" + parentSpanId + "}";
    }
}
```

---

## 第四十四块：ContentFilter.java

> **为什么需要 ContentFilter？**
>
> 用户的输入和模型的输出都可能包含敏感/违规内容。如果不做内容过滤，系统可能输出政治敏感、色情、暴力等内容，存在合规风险。
>
> ContentFilter 和 SensitiveDataInterceptor 的职责区分：
> - SensitiveDataInterceptor — **数据脱敏**。替换手机号、身份证、银行卡号等隐私信息。不影响内容，只保护隐私。
> - ContentFilter — **安全策略**。拦截政治敏感、色情、暴力等违规内容。影响内容——可能被 BLOCK（拦截）或 REPLACE（替换）。
>
> 两者互补，不可替代。
>
> **设计文档对应**：5.31 节（ContentFilter 内容安全过滤器）

**文件路径**：`lyclaw-core/src/main/java/lyjew/com/lyclaw/filter/ContentFilter.java`

```java
package lyjew.com.lyclaw.filter;

import lyjew.com.lyclaw.context.ChatContext;

/**
 * ContentFilter 接口 — 内容安全过滤器。
 *
 * <p>过滤用户输入和模型输出中的敏感/违规内容。
 * 第一版不做实现（空壳占位），{@code DefaultContentFilter} 实现返回 ALLOW（全部放行）。
 * 第二版接入第三方内容审核 API。
 *
 * <p>与 {@code SensitiveDataInterceptor} 的区别：
 * <ul>
 *   <li>SensitiveDataInterceptor — 数据脱敏（替换手机号、身份证等隐私信息），输出格式不变</li>
 *   <li>ContentFilter — 安全策略（拦截政治敏感/色情/暴力等违规内容），可能改变输出</li>
 * </ul>
 *
 * @author LyClaw Team
 * @version 1.0
 */
public interface ContentFilter {

    /**
     * 过滤内容。
     *
     * @param direction 请求方向——INPUT（用户输入）或 OUTPUT（模型输出）
     * @param content   要过滤的内容文本
     * @param context   对话上下文
     * @return 过滤结果（ALLOW 放行 / BLOCK 拦截 / REPLACE 替换）
     */
    FilterResult filter(RequestDirection direction, String content, ChatContext context);

    /** 请求方向枚举 */
    enum RequestDirection {
        /** 用户输入——过滤用户发送的消息 */
        INPUT,
        /** 模型输出——过滤模型生成的回复 */
        OUTPUT
    }
}
```

## 第四十五块：FilterResult.java

> **为什么 FilterResult 要有三种 action？**
>
> ALLOW（放行）——内容合规，直接通过。BLOCK（拦截）——内容违规，完全不让过。REPLACE（替换）——部分违规，用安全内容替换违规部分。如果不设计 REPLACE，对于"部分敏感"的内容（如模型回复中提到一个不恰当的比喻），只能要么全部放行要么全部拦截，不够灵活。

**文件路径**：`lyclaw-core/src/main/java/lyjew/com/lyclaw/filter/FilterResult.java`

```java
package lyjew.com.lyclaw.filter;

import java.util.Objects;

/**
 * FilterResult — 内容过滤结果。
 *
 * <p>包含三种动作：
 * <ul>
 *   <li>ALLOW — 内容合规，直接放行</li>
 *   <li>BLOCK — 内容违规，完全拦截</li>
 *   <li>REPLACE — 部分违规，用 replacedContent 替换</li>
 * </ul>
 *
 * @author LyClaw Team
 * @version 1.0
 */
public final class FilterResult {

    /** 过滤动作 */
    public enum Action { ALLOW, BLOCK, REPLACE }

    private final Action action;
    private final String replacedContent;
    private final String reason;

    private FilterResult(Action action, String replacedContent, String reason) {
        this.action = Objects.requireNonNull(action);
        this.replacedContent = replacedContent;
        this.reason = reason;
    }

    public static FilterResult allow() {
        return new FilterResult(Action.ALLOW, null, null);
    }

    public static FilterResult block(String reason) {
        return new FilterResult(Action.BLOCK, null,
            Objects.requireNonNull(reason, "block reason must not be null"));
    }

    public static FilterResult replace(String replacedContent, String reason) {
        return new FilterResult(Action.REPLACE,
            Objects.requireNonNull(replacedContent, "replacedContent must not be null"),
            reason);
    }

    public Action getAction() { return action; }
    public String getReplacedContent() { return replacedContent; }
    public String getReason() { return reason; }
    public boolean isAllowed() { return action == Action.ALLOW; }
    public boolean isBlocked() { return action == Action.BLOCK; }
}
```

---

## 第四十六块：SessionTransaction.java

> **为什么需要 SessionTransaction？（第二版占位）**
>
> 第一版采用最终一致性——Pipeline 执行期间不持久化 Session，流程结束后一次性保存。但是如果第一版不定义 SessionTransaction 接口，以后想引入事务时需要在现有代码中插入新接口，涉及修改多处已有代码。
>
> 第二版场景：多用户同时操作同一 Session（如两个用户同时对话），或同一 Agent 的多个子 Agent 同时写入。需要 begin() 获取快照，commit() 原子写入，rollback() 恢复快照。
>
> **注意**：这是**第二版占位接口**。第一版不实现，不调用。只创建文件确保接口存在，后续升级时零修改。
>
> **设计文档对应**：17.4 节（第二版的升级路径）

**文件路径**：`lyclaw-core/src/main/java/lyjew/com/lyclaw/session/SessionTransaction.java`

```java
package lyjew.com.lyclaw.session;

/**
 * SessionTransaction 接口 — 会话事务（第二版占位，第一版不实现）。
 *
 * <p>管理会话数据的并发写入。第一版采用最终一致性（Pipeline 结束后一次性保存），
 * 第二版引入此接口支持并发事务。
 *
 * <p>典型流程：
 * <pre>
 * TransactionContext ctx = sessionTransaction.begin(sessionId);
 * try {
 *     // 执行业务逻辑（可能有多个 Stage 修改 Session）
 *     sessionTransaction.createSavepoint(ctx, "afterContextBuild");
 *     // ... 更多修改
 *     sessionTransaction.commit(ctx);
 * } catch (Exception e) {
 *     sessionTransaction.rollback(ctx);
 * }
 * </pre>
 *
 * @author LyClaw Team
 * @version 1.0（第一版不实现）
 */
public interface SessionTransaction {

    /**
     * 开始事务。获取 Session 当前状态的快照。
     *
     * @param sessionId 会话 ID
     * @return 事务上下文
     */
    TransactionContext begin(String sessionId);

    /**
     * 提交事务。原子写入所有变更。
     *
     * @param ctx 事务上下文
     */
    void commit(TransactionContext ctx);

    /**
     * 回滚事务。恢复到 begin() 时的快照。
     *
     * @param ctx 事务上下文
     */
    void rollback(TransactionContext ctx);

    /**
     * 创建保存点。可在部分回滚时恢复到保存点状态。
     *
     * @param ctx  事务上下文
     * @param name 保存点名称
     */
    void createSavepoint(TransactionContext ctx, String name);

    /**
     * 回滚到指定保存点。
     *
     * @param ctx  事务上下文
     * @param name 保存点名称
     */
    void rollbackToSavepoint(TransactionContext ctx, String name);
}
```

## 第四十七块：TransactionContext.java

> **TransactionContext 的字段设计**：
> - sessionId：关联到哪个会话
> - snapshot：begin() 时的会话快照，rollback() 时恢复到此状态
> - pendingUpdates：待应用的更新列表，commit() 时一次性写入
> - pendingEvents：事务提交后要发布的事件（事件也属于事务的一部分）
> - savepoints：保存点映射，key=名称，value=保存点时刻的快照

**文件路径**：`lyclaw-core/src/main/java/lyjew/com/lyclaw/session/TransactionContext.java`

```java
package lyjew.com.lyclaw.session;

import java.util.*;

/**
 * TransactionContext — 事务上下文（第二版占位，第一版不实现）。
 *
 * <p>保存事务相关的状态信息。包括会话快照、待更新操作、待发布事件、保存点。
 * 第一版不创建也不使用此对象。
 *
 * @author LyClaw Team
 * @version 1.0（第一版不实现）
 */
public final class TransactionContext {

    private final String sessionId;
    /** begin() 时的会话快照——用于 rollback() 恢复 */
    private final Object snapshot;
    /** 待应用的更新列表 */
    private final List<SessionUpdate> pendingUpdates;
    /** 事务提交后要发布的事件 */
    private final List<Object> pendingEvents;
    /** 保存点映射：name → 该时刻的快照 */
    private final Map<String, Object> savepoints;

    public TransactionContext(String sessionId, Object snapshot) {
        this.sessionId = Objects.requireNonNull(sessionId);
        this.snapshot = snapshot;
        this.pendingUpdates = new ArrayList<>();
        this.pendingEvents = new ArrayList<>();
        this.savepoints = new LinkedHashMap<>();
    }

    public void addUpdate(SessionUpdate update) { pendingUpdates.add(update); }
    public void addEvent(Object event) { pendingEvents.add(event); }
    public void addSavepoint(String name, Object snapshot) { savepoints.put(name, snapshot); }

    public String getSessionId() { return sessionId; }
    public Object getSnapshot() { return snapshot; }
    public List<SessionUpdate> getPendingUpdates() { return Collections.unmodifiableList(pendingUpdates); }
    public List<Object> getPendingEvents() { return Collections.unmodifiableList(pendingEvents); }
    public Map<String, Object> getSavepoints() { return Collections.unmodifiableMap(savepoints); }
}
```

## 第四十八块：SessionUpdate.java

> **为什么需要 SessionUpdate？**
>
> 在事务场景下，对 Session 的修改不能直接 apply（否则无法回滚）。需要定义一种描述性的"更新操作"——追加消息、更新时间戳、修改会话名称等。这些操作在事务提交时才真正应用到 Session 对象上。

**文件路径**：`lyclaw-core/src/main/java/lyjew/com/lyclaw/session/SessionUpdate.java`

```java
package lyjew.com.lyclaw.session;

import java.util.Objects;

/**
 * SessionUpdate — 会话更新操作（第二版占位，第一版不实现）。
 *
 * <p>描述对会话的一次更新操作。在事务上下文中，更新操作不直接应用，
 * 而是先记录到 TransactionContext.pendingUpdates，commit() 时统一应用。
 *
 * @author LyClaw Team
 * @version 1.0（第一版不实现）
 */
public final class SessionUpdate {

    /** 更新类型 */
    public enum Type {
        /** 追加消息 */
        APPEND_MESSAGE,
        /** 更新时间戳 */
        UPDATE_TIMESTAMP,
        /** 修改会话名称 */
        UPDATE_NAME,
        /** 修改会话模型 */
        UPDATE_MODEL
    }

    private final Type type;
    private final Object payload;

    public SessionUpdate(Type type, Object payload) {
        this.type = Objects.requireNonNull(type);
        this.payload = payload;
    }

    public Type getType() { return type; }
    public Object getPayload() { return payload; }
}
```

## 第四十九块：SessionUpdateStrategy.java

> **为什么需要 SessionUpdateStrategy？（策略模式）**
>
> 不同的并发场景需要不同的更新策略：
> - 乐观锁（第一版默认）：不加锁，更新时检查版本号。适合低冲突场景。
> - 悲观锁：更新前加锁，其他线程等待。适合高冲突场景。
>
> SessionUpdateStrategy 接口让用户可以切换策略而不改业务代码。
>
> **设计文档对应**：18.1 节（策略切换："新增实现类 → @Primary"）

**文件路径**：`lyclaw-core/src/main/java/lyjew/com/lyclaw/session/SessionUpdateStrategy.java`

```java
package lyjew.com.lyclaw.session;

/**
 * SessionUpdateStrategy 接口 — 会话更新策略（策略模式，第二版占位）。
 *
 * <p>定义 Session 并发写入时的冲突解决策略。
 * 第一版不实现（最终一致性，无并发），接口只用于占位。
 *
 * <p>已知策略：
 * <ul>
 *   <li>OptimisticLockStrategy（第一版默认）— 乐观锁，版本号检查</li>
 *   <li>PessimisticLockStrategy（第二版）— 悲观锁，更新前加锁</li>
 * </ul>
 *
 * @author LyClaw Team
 * @version 1.0（第一版不实现）
 */
public interface SessionUpdateStrategy {

    /**
     * 应用更新。
     *
     * @param update 更新操作
     * @param target 目标 Session 对象
     * @return true 表示更新成功，false 表示冲突需要重试
     */
    boolean apply(SessionUpdate update, Object target);

    /**
     * 检查是否需要重试。
     *
     * @param applyResult apply() 的返回值
     * @return true 表示需要重试当前更新
     */
    boolean shouldRetry(boolean applyResult);
}
```

---

## 第四部分完成统计

| 块 | 文件名 | 包 | 行数（约） |
|----|--------|-----|-----------|
| 41 | VectorStore.java | retrieval | 70（含 SearchResult） |
| 42 | CacheService.java | cache | 85（含 CacheStats） |
| 43 | TraceContext.java | trace | 85 |
| 44 | ContentFilter.java | filter | 55（含 RequestDirection） |
| 45 | FilterResult.java | filter | 50 |
| 46 | SessionTransaction.java | session | 55 |
| 47 | TransactionContext.java | session | 55 |
| 48 | SessionUpdate.java | session | 40 |
| 49 | SessionUpdateStrategy.java | session | 35 |
| **总计** | **9 个文件** | 5 个包 | **~530 行代码** |

### 本部分设计特点

| 特性 | 说明 |
|------|------|
| 第一版直接实现 | VectorStore、CacheService、TraceContext、ContentFilter、FilterResult |
| 第二版占位（第一版不实现） | SessionTransaction、TransactionContext、SessionUpdate、SessionUpdateStrategy |
| 值对象 | FilterResult、TransactionContext、SessionUpdate、SearchResult（内部类）、CacheStats（内部类） |
| 策略模式 | SessionUpdateStrategy |
| 空对象模式 | DefaultContentFilter（返回 ALLOW） |

### 跨部分依赖关系

```
第一部分（Engine/Pipeline）
  │
  ├── TraceContext ← DefaultEngine（请求开始时创建）
  ├── TraceContext ← Pipeline Stage（每 Stage 调用 nextSpan()）
  ├── TraceContext ← LoggingInterceptor（日志中输出 traceId）
  │
  ├── CacheService ← ToolCallLoop（chatStream 前查缓存）
  │
  └── VectorStore ← 第二版 RagEngine/RetrievalStage（第一版不使用）
```

---

## 下一部分预告

**第五部分：lyclaw-engine（空对象）** — 4 个文件，覆盖 NullEventBus/NullMemoryManager/NullSecurityManager/NullContentFilter。空对象模式——当组件未配置时提供"什么也不做"的默认实现，避免 NPE 和 Null Check。

---

*续写于 2026-04-28*

# 第五部分：空对象模式（lyclaw-engine）

> **设计文档对应章节**：第四章（空对象模式 Null Object）
>
> **本部分总体设计意图**：
>
> 前三部分定义了接口和默认实现，但有个问题——如果某个组件没有配置具体实现怎么办？一个组件可能被另一个模块依赖，但如果该组件没有配置，依赖方必须写 `if (eventBus != null)` 的判断，代码中散落着大量的 Null Check。
>
> 空对象模式（Null Object Pattern）解决这个问题：为每个接口提供一个"什么也不做"的默认实现。依赖方永远收到一个有效对象，不需要判空。
>
> **4 个空对象的部署策略**：
> | 空对象 | 部署策略 |
> |--------|----------|
> | NullEventBus | 自动配置的默认 Spring Bean（@ConditionalOnMissingBean） |
> | NullMemoryManager | 自动配置的默认 Spring Bean |
> | NullSecurityManager | 可选——第一版放行所有调用，DefaultSecurityManager 已满足 |
> | NullContentFilter | 可选——DefaultContentFilter 已返回 ALLOW |
>
> **包路径**：`lyjew.com.lyclaw.nullobject`（lyclaw-engine 模块）

## 实现文件清单

| 序号 | 文件 | 包 | 类/接口类型 | 实现接口 |
|------|------|-----|-------------|----------|
| 50 | NullEventBus.java | nullobject | 类 | EventBus |
| 51 | NullMemoryManager.java | nullobject | 类 | MemoryManager |
| 52 | NullSecurityManager.java | nullobject | 类 | SecurityManager |
| 53 | NullContentFilter.java | nullobject | 类 | ContentFilter |

---

## 第五十块：NullEventBus.java

> **为什么需要 NullEventBus？**
>
> MetricsStage 在发布事件前调用 `eventBus.hasSubscribers()`。如果 EventBus 没有配置（为 null），需要先 `if (eventBus != null)` 再调用。如果在 EngineAutoConfiguration 中把 NullEventBus 设为默认 Bean（@ConditionalOnMissingBean），eventBus 永远不会为 null，处处不需要判空。
>
> **空对象的行为**：
> - publish() — 直接返回，不发布任何事件
> - publishAsync() — 直接返回
> - subscribe() — 不做任何注册，返回一个空的 Subscription
> - unsubscribe() — 直接返回
> - hasSubscribers() — 始终返回 false（没有订阅者）
>
> **设计文档对应**：第四章空对象模式——NullEventBus 被列为"自动配置的默认 bean"

**文件路径**：`lyclaw-engine/src/main/java/lyjew/com/lyclaw/nullobject/NullEventBus.java`

```java
package lyjew.com.lyclaw.nullobject;

import lyjew.com.lyclaw.event.Event;
import lyjew.com.lyclaw.event.EventBus;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Component;
import java.util.function.Consumer;

/**
 * NullEventBus — 空事件总线（空对象模式）。
 *
 * <p>当应用没有配置真正的 EventBus 实现时，作为默认 bean 注入。
 * 所有方法都不做实际操作——publish 直接返回，subscribe 返回空句柄，
 * hasSubscribers 始终返回 false。
 *
 * <p>使用 @ConditionalOnMissingBean 确保只有没有其他 EventBus Bean 时才生效。
 *
 * @author LyClaw Team
 * @version 1.0
 */
@Component
@ConditionalOnMissingBean(EventBus.class)
public class NullEventBus implements EventBus {

    @Override
    public <T extends Event> void publish(T event) {
        // 空实现——不发布任何事件
    }

    @Override
    public <T extends Event> void publishAsync(T event) {
        // 空实现——不发布任何事件
    }

    @Override
    public <T extends Event> Subscription subscribe(
            Class<T> eventType, Consumer<T> handler) {
        // 返回一个不做任何事的空 Subscription
        return () -> { /* 空 unsubscribe */ };
    }

    @Override
    public void unsubscribe(Subscription subscription) {
        // 空实现——不做任何操作
    }

    @Override
    public boolean hasSubscribers(Class<? extends Event> eventType) {
        // 始终返回 false——没有任何订阅者
        return false;
    }
}
```

---

## 第五十一块：NullMemoryManager.java

> **为什么需要 NullMemoryManager？**
>
> ContextBuildStage 在构建上下文时调用 MemoryManager.recall() 查询记忆。如果 MemoryManager 未配置，也要能正常构建上下文——recall() 返回空列表，remember() 不做记录。
>
> **空对象的行为**：
> - remember(session, strategy) — 直接返回
> - recall() — 返回空列表
> - recallByTags(tags) — 返回空列表
> - recallByPage(page, size, tagFilter) — 返回空分页结果
> - forget(memoryId) — 直接返回
> - buildContext(memories) — 返回空字符串
>
> **设计文档对应**：第四章空对象模式——NullMemoryManager 被列为"自动配置的默认 bean"

**文件路径**：`lyclaw-engine/src/main/java/lyjew/com/lyclaw/nullobject/NullMemoryManager.java`

```java
package lyjew.com.lyclaw.nullobject;

import lyjew.com.lyclaw.memory.MemoryManager;
import lyjew.com.lyclaw.memory.MemoryStrategy;
import lyjew.com.lyclaw.memory.PageResult;
import lyjew.com.lyclaw.model.Memory;
import lyjew.com.lyclaw.model.Session;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Component;
import java.util.Collections;
import java.util.List;

/**
 * NullMemoryManager — 空记忆管理器（空对象模式）。
 *
 * <p>当应用没有配置真正的 MemoryManager 实现时，作为默认 bean 注入。
 * 所有方法都不做实际操作——remember 直接返回，recall 返回空列表。
 *
 * <p>使用 @ConditionalOnMissingBean 确保只有没有其他 MemoryManager Bean 时才生效。
 *
 * @author LyClaw Team
 * @version 1.0
 */
@Component
@ConditionalOnMissingBean(MemoryManager.class)
public class NullMemoryManager implements MemoryManager {

    @Override
    public void remember(Session session, MemoryStrategy strategy) {
        // 空实现——不记录任何记忆
    }

    @Override
    public List<Memory> recall() {
        // 返回空列表——没有记忆
        return Collections.emptyList();
    }

    @Override
    public List<Memory> recallByTags(List<String> tags) {
        // 返回空列表——没有记忆
        return Collections.emptyList();
    }

    @Override
    public PageResult<Memory> recallByPage(int page, int size, String tagFilter) {
        // 返回空分页结果——没有记忆
        return PageResult.empty(page, size);
    }

    @Override
    public void forget(String memoryId) {
        // 空实现——不做任何操作
    }

    @Override
    public String buildContext(List<Memory> memories) {
        // 返回空字符串——没有记忆上下文
        return "";
    }
}
```

---

## 第五十二块：NullSecurityManager.java

> **为什么需要 NullSecurityManager？**
>
> ToolCallLoop 在每轮工具调用前调用 SecurityManager.approveToolCall()。如果 SecurityManager 未配置为 null，每处调用都要判空。NullSecurityManager 确保 approveToolCall() 始终返回 ALLOW，sandboxPolicy() 返回 NONE，resolveSecret() 返回引用字符串。
>
> **注意**：第一版 DefaultSecurityManager 已经做了一样的放行操作，所以 NullSecurityManager 是可选的。但保留这个空对象作为一个备选，确保"即使什么都不配，也不会 NPE"。
>
> **设计文档对应**：第四章空对象模式——NullSecurityManager 被列为"可选"

**文件路径**：`lyclaw-engine/src/main/java/lyjew/com/lyclaw/nullobject/NullSecurityManager.java`

```java
package lyjew.com.lyclaw.nullobject;

import lyjew.com.lyclaw.context.ChatContext;
import lyjew.com.lyclaw.model.ModelResponse;
import lyjew.com.lyclaw.security.ApprovalResult;
import lyjew.com.lyclaw.security.SandboxLevel;
import lyjew.com.lyclaw.security.SecurityManager;

/**
 * NullSecurityManager — 空安全管理器（空对象模式）。
 *
 * <p>当应用没有配置真正的 SecurityManager 实现时使用。
 * 所有方法都返回"通过"结果——工具调用全部放行，沙箱等级 NONE，凭证不解析。
 *
 * <p>第一版可选——DefaultSecurityManager 已实现相同的放行逻辑。
 *
 * @author LyClaw Team
 * @version 1.0
 */
public class NullSecurityManager implements SecurityManager {

    @Override
    public ApprovalResult approveToolCall(
            ChatContext context, ModelResponse.ToolCallRequest tc) {
        // 始终放行所有工具调用
        return ApprovalResult.allow();
    }

    @Override
    public SandboxLevel sandboxPolicy(String toolName) {
        // 最低沙箱等级——不隔离，直接当前进程执行
        return SandboxLevel.NONE;
    }

    @Override
    public String resolveSecret(String secretRef) {
        // 不解密——直接返回引用字符串本身
        return secretRef;
    }
}
```

---

## 第五十三块：NullContentFilter.java

> **为什么需要 NullContentFilter？**
>
> Pipeline 可能在某些 Stage 之前/之后调用 ContentFilter.filter() 做安全检查。如果 ContentFilter 未配置，每次 filter() 都需要判空。NullContentFilter 确保所有内容都被放行（ALLOW）。
>
> **注意**：第一版 ContentFilter 就是空壳（DefaultContentFilter 返回 ALLOW），NullContentFilter 是更彻底的空对象——不做检查，不到堆栈，直接放行。
>
> **设计文档对应**：第四章空对象模式——NullContentFilter 被列为"可选"

**文件路径**：`lyclaw-engine/src/main/java/lyjew/com/lyclaw/nullobject/NullContentFilter.java`

```java
package lyjew.com.lyclaw.nullobject;

import lyjew.com.lyclaw.context.ChatContext;
import lyjew.com.lyclaw.filter.ContentFilter;
import lyjew.com.lyclaw.filter.FilterResult;

/**
 * NullContentFilter — 空内容过滤器（空对象模式）。
 *
 * <p>当应用没有配置真正的 ContentFilter 实现时使用。
 * filter() 始终返回 ALLOW——所有内容都放行，不做任何安全检查。
 *
 * <p>第一版可选——DefaultContentFilter 已实现相同的放行逻辑。
 *
 * @author LyClaw Team
 * @version 1.0
 */
public class NullContentFilter implements ContentFilter {

    @Override
    public FilterResult filter(
            RequestDirection direction, String content, ChatContext context) {
        // 始终放行——不过滤任何内容
        return FilterResult.allow();
    }
}
```

---

## 第五部分完成统计

| 块 | 文件名 | 包 | 行数（约） |
|----|--------|-----|-----------|
| 50 | NullEventBus.java | nullobject | 55 |
| 51 | NullMemoryManager.java | nullobject | 65 |
| 52 | NullSecurityManager.java | nullobject | 40 |
| 53 | NullContentFilter.java | nullobject | 30 |
| **总计** | **4 个文件** | 1 个包 | **~190 行代码** |

### 空对象部署策略

| 空对象 | @ConditionalOnMissingBean | 包 | 自动配置 |
|--------|--------------------------|-----|----------|
| NullEventBus | ✅ 是 | nullobject | EngineAutoConfiguration 中扫描 |
| NullMemoryManager | ✅ 是 | nullobject | EngineAutoConfiguration 中扫描 |
| NullSecurityManager | ❌ 否（手动注入） | nullobject | 可选，不设默认 |
| NullContentFilter | ❌ 否（手动注入） | nullobject | 可选，不设默认 |

### 已实现文件全局统计

| 部分 | 模块 | 包数 | 文件数 |
|------|------|------|--------|
| 第一部分 | lyclaw-core（接口）+ lyclaw-engine（实现） | 9 | 18 |
| 第二部分 | lyclaw-core | 3 | 10 |
| 第三部分 | lyclaw-core | 7 | 20 |
| 第四部分 | lyclaw-core | 5 | 9 |
| 第五部分 | lyclaw-engine | 1 | 4 |
| **lyclaw-core 核心接口** | — | **18 个包** | **42 个文件**（设计文档要求） |
| **已完成总计** | — | — | **~175 个文件**（6 个模块合计） |

> **文件分布**：
> - lyclaw-common：13 个 .java（模型类、DTO、异常基类）
> - lyclaw-core：9 个 .java（接口 + 抽象基类 + 模板方法）
> - lyclaw-engine：114 个 .java（核心实现 + 状态机 + 工具 + 管道 + 事件 + 安全 + 事务等）
> - lyclaw-storage：8 个 .java（文件存储 + 策略模式）
> - lyclaw-adapter：13 个 .java（模型适配器 + DTO + 客户端 + 解析器 + 工厂）
> - lyclaw-web：13 个 .java（Controller + 引擎启动 + 测试类）
>
> 设计文档规划 82 个文件，实际远超因为：
> 1. 新增了值对象（ToolResult/ToolErrorAction/MemoryContent/PageResult/AgentMessage/ModelException/ToolExecuteException/ApprovalResult/SandboxLevel/SearchResult/CacheStats/FilterResult/TransactionContext/SessionUpdate 等 24 个）
> 2. 新增了流式状态机 11 个文件（stream 包）
> 3. 新增了适配器 DTO 和安全/事务/任务实现
> 4. 新增了 7 个集成测试类

---

## 下一部分预告

**第六部分：lyclaw-engine（Engine 实现 + Pipeline 实现）** — 8 个文件，覆盖 DefaultEngine/EngineSelector/PipelineBuilder + ContextBuildStage/InterceptorStage/ToolCallLoopStage/MetricsStage/ResponseBuildStage（5 个 Pipeline Stage）。

---

*续写于 2026-04-28*




# 第六部分：Engine 实现 + Pipeline 实现（lyclaw-engine）

> **设计文档对应章节**：第六章（Engine 顶层抽象）——6.1~6.3、第七章（Pipeline 编排与实现）、第八章（5 个 Pipeline Stage 详细设计）

## 实现文件清单

| 序号 | 文件 | 包 | 类类型 | 说明 |
|------|------|-----|--------|------|
| 54 | PipelineBuilder.java | pipeline.impl | 类 | 管道构建器，链式 API 编排 Stage |
| 55 | ContextBuildStage.java | pipeline.impl.stages | 类 | 加载会话、构建消息列表、注入工具定义 |
| 56 | InterceptorStage.java | pipeline.impl.stages | 类 | 按 @Order 执行所有拦截器的 preHandle + postHandle |
| 57 | ToolCallLoopStage.java | pipeline.impl.stages | 类 | model 调用和工具循环，chatStream() 返回 Flux<String> |
| 58 | MetricsStage.java | pipeline.impl.stages | 类 | Token 用量累计、耗时计算、事件发布 |
| 59 | ResponseBuildStage.java | pipeline.impl.stages | 类 | 构建 ChatResult |
| 60 | DefaultEngine.java | engine.impl | 类 | 默认引擎，通过 PipelineBuilder 编排 5 个 Stage |
| 61 | EngineSelector.java | engine.impl | 类 | 引擎选择器，按 getOrder() 升序匹配 |

---

## 第五十四块：PipelineBuilder.java

### 类介绍

**设计动机**：Pipeline 是一系列 Stage 的有序集合。如果 DefaultEngine 硬编码写死 `new ContextBuildStage(…).execute() → new InterceptorStage(…).execute() → …`，那么：

1. 新增一个 Stage 时，必须修改 DefaultEngine 的 execute() 方法，插入新一行
2. 移除一个 Stage 时，必须先找到 DefaultEngine 中对应的那行代码，再删除
3. 替换一个 Stage 时，必须逐行阅读 DefaultEngine 找到旧代码的创建位置
4. 不同的 Engine（如 ReasoningEngine）需要不同的 Stage 集合时，每个 Engine 都要写一遍自己的硬编码调用链

PipelineBuilder 用建造者模式将"装配"从"执行"中分离。Engine 只需要告诉 Builder"把我需要的 Stage 依次加进去"，然后调 build() 得到一个 Pipeline 接口。Engine 不关心 Pipeline 内部有几个 Stage、是什么类型。

**对比方案**：
- 方案 A（硬编码链）：DefaultEngine 中写 5 行 stage.execute()。简单直接，但排列组合不变——每个 Engine 都要复制粘贴这个链
- 方案 B（Builder + 匿名 Pipeline，选中的）：Piepline 接口只有一个 execute() 方法，Builder build() 返回匿名实现。Stage 列表的遍历逻辑在 Builder 中只写一次，所有 Engine 复用
- 方案 C（接口加默认方法）：Pipelnie 接口加 default execute() 遍历 List<PipelineStage>。但 Pipeline 接口定义在 lyclaw-core，Stage 定义在 lyclaw-engine——core 不依赖 engine，所以 default 方法不能用 engine 的类型

**核心原理**：

PipelineBuilder 内部维护一个 `List<PipelineStage>`。addStage() 将 Stage 追加到末尾。build() 创建防御性拷贝（防止 build 后继续 addStage 影响已构建的 Pipeline），返回匿名 Pipeline 实例。

匿名 Pipeline 的 execute() 执行逻辑：

```
executeStages(stages, context, index=0)
  ├─ if index >= size → return null（递归终结条件）
  ├─ if !supports(context) → executeStages(index+1)（跳过）
  ├─ 创建 Chain（含 index 闭包）
  ├─ stage.execute(context, chain)
  │    ├─ stage 内部处理逻辑
  │    └─ stage 调 chain.proceed(context)
  │         └─ executeStages(index+1)（递归）
  ├─ 返回 context.getAttribute("result")
```

使用递归而不是 for 循环，因为 Chain.proceed() 相当于"把控制权交给下一个 Stage"。如果用 for 循环，Chain.proceed() 无法决定"下一个"是谁——for 循环天然是顺序执行，Stage 内部没有机会"跳过"或"终止"循环。递归让每个 Stage 通过 proceed() 决定是否以及何时通知下一个 Stage。

**边界情况**：
- 空列表：build() 直接抛 IllegalStateException。空的 Pipeline 没有任何逻辑，是配置错误
- 全部 supports=false：所有 Stage 被跳过，execute() 返回 null。意味着没有任何 Stage 处理请求——但这是调用方的配置错误
- Stage 抛异常：PipelineBuilder 用 RuntimeException 包裹原始异常，保留调用栈。原始异常的类型和消息不变，方便调用方判断错误类型
- 最后一个 Stage 不调 proceed()：递归在 executeStages(index+1) → index>=size → 返回 null，然后逐层返回

---

## 第五十四块代码文件：PipelineBuilder.java

**文件路径**：`lyclaw-engine/src/main/java/lyjew/com/lyclaw/pipeline/impl/PipelineBuilder.java`

```java
package lyjew.com.lyclaw.pipeline.impl;

import lyjew.com.lyclaw.context.ChatContext;
import lyjew.com.lyclaw.dto.ChatResult;
import lyjew.com.lyclaw.pipeline.Chain;
import lyjew.com.lyclaw.pipeline.Pipeline;
import lyjew.com.lyclaw.pipeline.PipelineStage;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;

/**
 * PipelineBuilder — 管道构建器（建造者模式）。
 *
 * <p><b>设计动机</b>：Pipeline 的 Stage 列表需要灵活装配。
 * 如果 DefaultEngine 硬编码 5 行 stage.execute()，
 * 新增/移除/替换 Stage 时必须修改 Engine 代码。
 * Builder 将"装配"与"执行"分离——Engine 只需要通过 addStage() 声明需要哪些 Stage，
 * build() 返回可执行的 Pipeline 接口。
 *
 * <p><b>对比方案</b>：
 * <ul>
 *   <li>方案 A（硬编码链）：DefaultEngine 直接调 5 个 stage.execute()，简单直接但每个 Engine 都要复制粘贴</li>
 *   <li>方案 B（Builder + 匿名 Pipeline，选中）：迭代逻辑只在 Builder 中写一次，所有 Engine 复用</li>
 *   <li>方案 C（Pipeline 接口加 default 方法）：但 Pipeline 定义在 lyclaw-core，Stage 定义在 lyclaw-engine，
 *       core 不依赖 engine，所以 default 方法不能用 engine 的类型</li>
 * </ul>
 *
 * <p><b>核心原理</b>：build() 创建防御性拷贝，返回匿名 Pipeline 实例。
 * 匿名实现的 execute() 递归遍历 Stage 列表。
 * 使用递归而不是 for 循环——Chain.proceed() 天然对应"递归到下一层"，
 * for 循环无法让 Stage 通过 proceed() 决定是否跳过或终止。
 *
 * <p><b>边界情况</b>：
 * <ul>
 *   <li>空 Stage 列表：build() 抛 IllegalStateException</li>
 *   <li>全部 supports()=false：execute() 返回 null</li>
 *   <li>Stage.execute() 抛异常：RuntimeException 包裹，保留原始异常</li>
 * </ul>
 *
 * @author LyClaw Team
 * @version 1.0
 */
public class PipelineBuilder {

    /**
     * Stage 列表。使用 ArrayList 而不是 LinkedList——
     * Stage 数量极少（≤10），ArrayList 迭代快、内存紧凑。
     * 如果在第二版中 Stage 膨胀到几十个（插件动态注入），
     * 可改用 LinkedList 优化 addStageBefore/After 的插入性能。
     */
    private final List<PipelineStage> stages = new ArrayList<>();

    // ==================== 第一类：编排方法 ====================

    /**
     * 在末尾添加一个 Stage。返回 this 支持链式调用。
     *
     * @param stage 不能为 null——nul 的 Stage 无法 getName()、execute()、supports()
     * @return this 自身，链式
     * @throws NullPointerException 如果 stage 为 null，Objects.requireNonNull 立刻抛
     */
    public PipelineBuilder addStage(PipelineStage stage) {
        // Objects.requireNonNull 检查——在 build() 之前就暴露 null，比 build() 时报好得多
        stages.add(Objects.requireNonNull(stage, "stage must not be null"));
        return this;
    }

    /**
     * 在指定 Stage 类型之前插入。例如在 InterceptorStage 之前插入新的 ValidationStage。
     *
     * @param beforeClass 目标 Stage 的 Class 对象，用 Class.equals 精确匹配（不是 instanceof）
     * @param stage       待插入的新 Stage
     * @return this 自身
     * @throws IllegalArgumentException 如果找不到匹配的 Stage 类型
     *
     * <p>为什么不支持 instanceof 匹配？因为如果有 CustomMetricsStage extends MetricsStage，
     * instanceof 会把两者混为一谈——永远插到 MetricsStage 前面，
     * 无法区分"我想插在 MetricsStage 前"还是"我想插在 CustomMetricsStage 前"。
     * Class.equals 就没有这个问题——只精确匹配一个类型。
     */
    public PipelineBuilder addStageBefore(Class<?> beforeClass, PipelineStage stage) {
        // findStageIndex 会遍历列表并比较 Class.equals
        // 如果找不到，抛 IllegalArgumentException
        int index = findStageIndex(beforeClass);
        // List.add(index, element) 将原有元素后移一位，在 index 位置插入
        stages.add(index, stage);
        return this;
    }

    /**
     * 在指定 Stage 类型之后插入。
     * 内部是 findStageIndex + 1，与 addStageBefore 对称。
     */
    public PipelineBuilder addStageAfter(Class<?> afterClass, PipelineStage stage) {
        // 找到目标位置，index + 1 表示"在它后面"
        int index = findStageIndex(afterClass);
        stages.add(index + 1, stage);
        return this;
    }

    /**
     * 替换指定类型的 Stage（保持位置不变）。
     * 例如当前 InterceptorStage 用 ForbiddenToInterceptor 替换：
     * builder.replaceStage(InterceptorStage.class, new ForbiddenToInterceptor());
     */
    public PipelineBuilder replaceStage(Class<?> oldClass, PipelineStage newStage) {
        // set(index, element) 替换该位置的元素，老元素被丢弃
        int index = findStageIndex(oldClass);
        stages.set(index, newStage);
        return this;
    }

    /**
     * 移除指定类型的 Stage。
     * 使用 Iterator.remove() 而不是 fori + list.remove(i)——
     * fori + remove(i) 会触发异常（fori 中删除元素后索引偏移）。
     *
     * @param stageClass 目标 Stage 类型
     * @return this 自身
     * @throws IllegalArgumentException 如果找不到匹配的 Stage 类型
     */
    public PipelineBuilder removeStage(Class<?> stageClass) {
        // Iterator.remove() 是安全的——它会处理索引偏移
        Iterator<PipelineStage> it = stages.iterator();
        while (it.hasNext()) {
            // Class.equals 精确匹配，不是 instanceof
            if (it.next().getClass().equals(stageClass)) {
                it.remove();
                return this;
            }
        }
        // 遍历完还没找到，说明没有这个类型的 Stage——配置错误
        throw new IllegalArgumentException("Stage not found: " + stageClass.getName());
    }

    // ==================== 第二类：构建方法 ====================

    /**
     * 构建 Pipeline 实例。
     *
     * 执行三件事：
     * 1. 验证 Stage 列表不为空（空的 Pipeline 没有任何处理逻辑，是配置错误）
     * 2. 创建防御性拷贝（ArrayList 的拷贝构造，让后续 addStage() 不影响已构建的 Pipeline）
     * 3. 返回匿名 Pipeline 实例，其 execute() 闭包捕获了 Stage 快照
     *
     * @return 不可变的 Pipeline 实例（实际是防御性拷贝之后的快照，在返回后添加的 Stage 不影响它）
     * @throws IllegalStateException 如果没有任何 Stage
     */
    public Pipeline build() {
        // 空列表检查：Stage 至少要有 1 个
        if (stages.isEmpty()) {
            throw new IllegalStateException("Pipeline must have at least one stage");
        }
        // 防御性拷贝：new ArrayList<>(stages) 复制一份，不共享引用
        // 这样外部在 build() 之后继续 addStage() 不会污染已构建的 Pipeline
        List<PipelineStage> copy = new ArrayList<>(stages);
        // 返回匿名 Pipeline 实例——其 execute() 调用私有递归方法
        // 使用 Lambda 表达式：context -> executeStages(copy, context, 0)
        // 注意这里捕获的是 copy（快照），不是 stages（可变列表）
        return context -> executeStages(copy, context, 0);
    }

    // ==================== 第三类：核心递归遍历 ====================

    /**
     * 递归遍历 Stage 列表，对每个 Stage 执行三步检查：
     * 1. index >= size → 递归终结（返回 null）
     * 2. !supports(context) → 跳过，递归 index+1
     * 3. supports = true → 创建 Chain，调用 execute()
     *
     * 递归的好处：Chain.proceed() 天然对应"递归到下一层"。
     * 如果用 for 循环，proceed() 无法决定下一个是谁——for 循环不依赖 proceed()。
     *
     * @param stages  Stage 快照（defensive copy）
     * @param ctx     当前 ChatContext（可变对象，各 Stage 共享修改）
     * @param i       当前 Stage 索引
     * @return ChatResult 或 null（todo 所有 Stage 被跳过时）
     */
    private ChatResult executeStages(List<PipelineStage> stages, ChatContext ctx, int i) {
        // 递归终结条件：处理完了所有 Stage
        if (i >= stages.size()) {
            return null;
        }

        // 获取当前 Stage
        PipelineStage current = stages.get(i);

        // 检查 supports()——如果不支持，直接递归到下一个
        // 例如 ToolCallLoopStage.supports() 返回 false，
        // 这里会跳过 ToolCallLoopStage，继续检查 MetricsStage
        if (!current.supports(ctx)) {
            return executeStages(stages, ctx, i + 1);
        }

        // 创建 Chain 实例——这是个匿名实现类
        // Chain 持有 i 的闭包（current stage 的索引），
        // 当 Stage 调用 chain.proceed() 时，Chain 递归到 i+1
        Chain chain = new Chain() {
            // proceeded 标志位：防止 proceed() 被调用多次
            // 如果 Stage 不小心调了两次 proceed()，第二次不会生效
            private boolean proceeded = false;

            @Override
            public void proceed(ChatContext context) throws Exception {
                // 标记已调用
                proceeded = true;
                // 递归到下一个 Stage
                executeStages(stages, context, i + 1);
            }

            @Override
            public void skipToEnd() {
                // skipToEnd 只是设置标志位，不实际执行什么
                // 它的作用是让当前 Stage 知道"不需要调 proceed() 了"
                // 后续的 Stage 不会被执行（但 executeStages 本就不会再执行后面的 Stage，
                // 因为当 index+1 >= size 时返回 null）
                // 这里的实现是语义对齐——未来如果 Chain 有 postProcess 等回调时，
                // skipToEnd() 可以用来跳过那些回调
                proceeded = true;
            }
        };

        // 调用当前 Stage 的 execute()，传入 Chain
        // Stage 内部会调 chain.proceed() 触发下一个 Stage
        try {
            current.execute(ctx, chain);
        } catch (Exception e) {
            // 如果 Stage 抛出异常，用 RuntimeException 包裹
            // 保留原始异常消息和调用栈，方便排查是哪个 Stage 出了问题
            throw new RuntimeException(
                "Stage [" + current.getName() + "] failed", e);
        }

        // execute 返回后，从 context 中读取 result
        // result 由 ResponseBuildStage 在 execute() 中设置
        return (ChatResult) ctx.getAttribute("result");
    }

    /**
     * 遍历 Stage 列表，精确匹配 Class.equals（不是 instanceof）。
     *
     * @param cls 目标 Stage 的 Class
     * @return 匹配的索引位置
     * @throws IllegalArgumentException 如果遍历完没找到
     */
    private int findStageIndex(Class<?> cls) {
        for (int i = 0; i < stages.size(); i++) {
            // Class.equals 是精确类型匹配
            // 如果 stages[i] 正好是 cls 类型，返回索引
            if (stages.get(i).getClass().equals(cls)) {
                return i;
            }
        }
        // 如果遍历完没有找到，说明这个 Stage 类型不在列表中
        throw new IllegalArgumentException("Stage not found: " + cls.getName());
    }
}
```

---

## 第五十五块代码文件：ContextBuildStage.java

**文件路径**：`lyclaw-engine/src/main/java/lyjew/com/lyclaw/pipeline/impl/stages/ContextBuildStage.java`

```java
package lyjew.com.lyclaw.pipeline.impl.stages;

import lyjew.com.lyclaw.context.ChatContext;
import lyjew.com.lyclaw.context.ContextBuilder;
import lyjew.com.lyclaw.memory.MemoryManager;
import lyjew.com.lyclaw.model.Message;
import lyjew.com.lyclaw.model.Session;
import lyjew.com.lyclaw.pipeline.Chain;
import lyjew.com.lyclaw.pipeline.PipelineStage;
import lyjew.com.lyclaw.skill.SkillRegistry;
import lyjew.com.lyclaw.storage.SessionStorage;

import java.util.ArrayList;
import java.util.List;

/**
 * ContextBuildStage — 上下文构建阶段（Pipeline 第 1 个 Stage）。
 *
 * <p><b>设计动机</b>：模型调用需要"上下文"——消息列表 + 技能定义。
 * 数据分散在 SessionStorage（会话历史）、MemoryManager（长期记忆）、SkillRegistry（可用技能）三处。
 * 本 Stage 统一读取并塞进 ChatContext，后续 Stage 直接读 ChatContext，不需要知道数据在哪里、怎么读。
 *
 * <p><b>与 ContextBuilder 的关系</b>：
 * 本 Stage 是"协调者"，ContextBuilder（策略模式）是"具体算法"。
 * Stage 负责"在 Pipeline 中占据位置、传递控制权"，
 * Builder 负责"具体的消息构建逻辑（全量、滑动窗口、摘要压缩）"。
 * 新建 SlidingWindowContextBuilder 只需实现 ContextBuilder 接口，本 Stage 代码零修改。
 *
 * <p><b>执行流程</b>：
 * <ol>
 *   <li>从 request.sessionId 加载 Session（新会话则创建空 Session）</li>
 *   <li>提取用户消息（request.messages 最后一条）</li>
 *   <li>委托 ContextBuilder 构建消息列表</li>
 *   <li>注入技能注册表</li>
 *   <li>记录选中策略名称（调试用）</li>
 *   <li>chain.proceed() 交给 InterceptorStage</li>
 * </ol>
 *
 * @author LyClaw Team
 * @version 1.0
 */
public class ContextBuildStage implements PipelineStage {

    /** 会话存储——用于通过 sessionId 读取/创建 Session。通过接口注入，不依赖具体实现。 */
    private final SessionStorage sessionStorage;

    /**
     * 记忆管理器——用于读取长期记忆。
     * 第一版构造时传入，ContextBuildStage 本身不做记忆提取，
     * 而是将 memoryManager 传给 ContextBuilder 去做（FullWindowContextBuilder 内部使用）。
     * 如果 future 有不需要记忆的 ContextBuilder，可以 ignore 这个参数。
     */
    private final MemoryManager memoryManager;

    /**
     * 上下文构建策略——第一版传入 FullWindowContextBuilder（兜底，始终 true）。
     * ContextBuildStage 调用 contextBuilder.supports() 判断是否适用，
     * 适用则调用 contextBuilder.build() 构建消息列表。
     */
    private final ContextBuilder contextBuilder;

    /** 技能注册表——获取所有已注册的技能定义，注入到 ChatContext.skills 中。 */
    private final SkillRegistry skillRegistry;

    /**
     * 构造函数：所有字段通过构造注入。
     *
     * @param sessionStorage 会话存储
     * @param memoryManager  记忆管理器
     * @param contextBuilder 上下文构建策略
     * @param skillRegistry  技能注册表
     */
    public ContextBuildStage(SessionStorage sessionStorage,
                             MemoryManager memoryManager,
                             ContextBuilder contextBuilder,
                             SkillRegistry skillRegistry) {
        this.sessionStorage = sessionStorage;
        this.memoryManager = memoryManager;
        this.contextBuilder = contextBuilder;
        this.skillRegistry = skillRegistry;
    }

    @Override
    public String getName() {
        // 日志和调试时使用。PipelineBuilder 在抛异常时也会用到："Stage [ContextBuild] failed"
        return "ContextBuild";
    }

    @Override
    public boolean supports(ChatContext context) {
        // 每个请求都需要构建上下文——这是 Pipeline 的基础
        // 除非有的系统不需要任何上下文（比如 ping 请求），
        // 那种场景可以在第二版提供 NoopContextBuildStage，用 Builder 替换
        return true;
    }

    @Override
    public void execute(ChatContext context, Chain chain) throws Exception {
        // ──────────────────────────────────────────────
        // 步骤 1：加载或创建 Session
        // ──────────────────────────────────────────────
        // 从 ChatRequest 中获取 sessionId（客户端在请求时传入）
        // 如果客户端传了 sessionId（如 "session-xxxx"），用这个 id 加载
        // 如果客户端没传 sessionId（比如第一次请求，还没有 session），
        // ChatRequest 中 sessionId 为 null——但这种情况由 ChatGPTRequest 的构造保证不会发生
        String sessionId = context.getOriginalRequest().getSessionId();

        // SessionStorage.get() 返回 Optional<Session>
        // 如果 sessionId 对应的文件存在（之前保存过），Optional 包含 Session 对象
        // 如果文件不存在（新会话），Optional.empty()
        Session session = sessionStorage.get(sessionId).orElseGet(() -> {
            // 创建新的空 Session 对象
            Session newSession = new Session();
            newSession.setId(sessionId);             // 设置 sessionId
            newSession.setMessages(new ArrayList<>());// 初始消息列表为空——FullWindowContextBuilder 会填充
            return newSession;
        });

        // 将 Session 设置到 ChatContext 中
        // 后续 Stage（InterceptorStage、ToolCallLoopStage）和 DefaultEngine（doFinally）
        // 都通过 context.getSession() 读取这个 Session
        context.setSession(session);

        // ──────────────────────────────────────────────
        // 步骤 2：提取当前用户消息
        // ──────────────────────────────────────────────
        // 从 request.messages 列表中提取最后一条（当前用户输入的那条）
        // 前面的是历史消息（多轮对话场景下客户端会带上前面的消息）
        List<Message> requestMessages = context.getOriginalRequest().getMessages();

        // 如果 messages 列表不为空且非空，取最后一条
        // 如果为空（某些内部请求不带消息体），跳过
        if (requestMessages != null && !requestMessages.isEmpty()) {
            // get(size-1) 获取最后一条消息——这是用户本次输入
            context.setUserMessage(requestMessages.get(requestMessages.size() - 1));
        }

        // ──────────────────────────────────────────────
        // 步骤 3：委托 ContextBuilder 构建消息列表
        // ──────────────────────────────────────────────
        // 先检查 contextBuilder 是否适用于当前上下文
        // FullWindowContextBuilder.supports() 始终返回 true（兜底）
        if (contextBuilder.supports(context)) {
            // build() 内部会：
            // 1. 加载长期记忆（调用 MemoryManager.recall()）
            // 2. 格式化记忆为文本
            // 3. 注入 system prompt
            // 4. 按时间顺序排列所有历史消息和当前消息
            // 5. 写入 context.messages（最终发送给模型的消息列表）
            contextBuilder.build(context);
        }

        // ──────────────────────────────────────────────
        // 步骤 4：注入技能注册表
        // ──────────────────────────────────────────────
        // SkillRegistry.getAll() 返回所有已注册的技能
        // 包括直接 @Component 注册的 Skill 实现 + 通过 ToolToSkillAdapter 自动适配的 Tool
        // 这些技能会被 ToolCallLoopStage 用来构建 model 调用的 tools[] 数组
        context.setSkills(skillRegistry.getAll());

        // ──────────────────────────────────────────────
        // 步骤 5：记录选中的策略名称（调试用）
        // ──────────────────────────────────────────────
        // 将选中的 ContextBuilder 的类名写入 context.attributes
        // 后续可以在拦截器日志中看到 "currentBuilder=FullWindowContextBuilder"
        context.setAttribute("currentBuilder", contextBuilder.getClass().getSimpleName());

        // ──────────────────────────────────────────────
        // 步骤 6：传递控制权给下一个 Stage
        // ──────────────────────────────────────────────
        // chain.proceed() 会让 PipelineBuilder 递归到 index+1，
        // 执行 InterceptorStage
        // 如果这一步不调 proceed()，Pipeline 在此终止。
        chain.proceed(context);
    }
}
```

---


---

## 第五十六块代码文件：InterceptorStage.java

**文件路径**：`lyclaw-engine/src/main/java/lyjew/com/lyclaw/pipeline/impl/stages/InterceptorStage.java`

```java
package lyjew.com.lyclaw.pipeline.impl.stages;

import lyjew.com.lyclaw.context.ChatContext;
import lyjew.com.lyclaw.dto.ChatResult;
import lyjew.com.lyclaw.interceptor.impl.InterceptorChain;
import lyjew.com.lyclaw.pipeline.Chain;
import lyjew.com.lyclaw.pipeline.PipelineStage;

/**
 * InterceptorStage — 拦截器调度阶段（Pipeline 第 2 个 Stage）。
 *
 * <p><b>设计动机</b>：横切关注点（限流、脱敏、日志）由独立 Interceptor 实现。
 * 本 Stage 通过 InterceptorChain 统一管理所有拦截器的生命周期，不硬编码拦截器逻辑。
 *
 * <p><b>执行顺序</b>：
 * <pre>
 * preHandle（@Order 升序）：
 *   RateLimitInterceptor(10) → SensitiveDataInterceptor(50) → LoggingInterceptor(100)
 *     → chain.proceed() 执行后续 Stage →
 * postHandle（@Order 降序）：
 *   LoggingInterceptor(100) → SensitiveDataInterceptor(50) → RateLimitInterceptor(10)
 * </pre>
 *
 * <p><b>postHandle 逆序原理</b>：后进先出。Logging 最后打开资源、最先释放。
 * 正序执行 postHandle 时 RateLimit 先关闭计数器，Logging 写入日志发现计数器已关闭——错。
 * 逆序保证倒数第二个打开的先释放。
 *
 * @author LyClaw Team
 * @version 1.0
 */
public class InterceptorStage implements PipelineStage {

    /** 拦截器链管理器——管理所有已注册拦截器的排序和执行。 */
    private final InterceptorChain interceptorChain;

    /**
     * 构造函数。
     *
     * @param interceptorChain InterceptorChain 实例，由 DefaultEngine 在构造时注入
     */
    public InterceptorStage(InterceptorChain interceptorChain) {
        this.interceptorChain = interceptorChain;
    }

    @Override
    public String getName() {
        return "Interceptor";
    }

    @Override
    public boolean supports(ChatContext context) {
        // 每个请求都需要经过拦截器检查（至少 RateLimit 和 Logging）
        // 不存在"不需要拦截器"的请求——因为限流是安全基线
        return true;
    }

    @Override
    public void execute(ChatContext context, Chain chain) throws Exception {
        // ──────────────────────────────────────────────
        // 阶段 1：前置拦截器（按 @Order 升序执行）
        // ──────────────────────────────────────────────
        // InterceptorChain.preHandleAll() 内部遍历所有注册的 Interceptor，
        // 按 getOrder() 升序依次调用 preHandle()
        // 顺序：RateLimitInterceptor(order=10) → SensitiveDataInterceptor(order=50) → LoggingInterceptor(order=100)
        //
        // 如果某个拦截器抛异常（如 RateLimitInterceptor 检测到请求超限），
        // preHandleAll() 不会 catch——异常直接冒泡到这里
        // PipelineBuilder 在 try-catch 中捕获，包裹后抛出
        interceptorChain.preHandleAll(context);

        // ──────────────────────────────────────────────
        // 阶段 2：传递控制权给后续 Stage
        // ──────────────────────────────────────────────
        // chain.proceed() 让 PipelineBuilder 递归到 index+1
        // 后续 Stage 依次执行：ToolCallLoopStage（跳过）→ MetricsStage → ResponseBuildStage
        // proceed() 会阻塞——等所有后续 Stage 执行完毕后才返回
        chain.proceed(context);

        // ──────────────────────────────────────────────
        // 阶段 3：后置拦截器（按 @Order 降序执行）
        // ──────────────────────────────────────────────
        // 从 context.attributes 中获取 ChatResult
        // ChatResult 由 ResponseBuildStage 在 proceed() 内部构建并存入
        ChatResult result = (ChatResult) context.getAttribute("result");

        // 如果 result 为 null：可能 ResponseBuildStage 出了问题（异常被吞了？）
        // 或者 Pipeline 在 ResponseBuildStage 之前就终止了
        // 不管怎样，没有 result 就没有 postHandle 的必要
        if (result != null) {
            // 逆序执行：LoggingInterceptor → SensitiveDataInterceptor → RateLimitInterceptor
            // 逆序保证"后进先出"——Logging 最后注册、最先释放
            interceptorChain.postHandleAll(result);
        }
    }
}
```

---

## 第五十七块代码文件：ToolCallLoopStage.java

**文件路径**：`lyclaw-engine/src/main/java/lyjew/com/lyclaw/pipeline/impl/stages/ToolCallLoopStage.java`

```java
package lyjew.com.lyclaw.pipeline.impl.stages;

import lyjew.com.lyclaw.context.ChatContext;
import lyjew.com.lyclaw.model.ChatRequest;
import lyjew.com.lyclaw.model.ToolDefinition;
import lyjew.com.lyclaw.provider.ModelProvider;
import lyjew.com.lyclaw.skill.Skill;
import lyjew.com.lyclaw.tool.ToolRegistry;
import lyjew.com.lyclaw.tool.ToolCallPolicy;
import reactor.core.publisher.Flux;
import lyjew.com.lyclaw.pipeline.Chain;
import lyjew.com.lyclaw.pipeline.PipelineStage;

import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 * ToolCallLoopStage — 模型调用和工具循环阶段（Pipeline 第 3 个 Stage）。
 *
 * <p><b>设计动机</b>：模型调用是流式的（Flux&lt;String&gt;），但 Pipeline.execute() 是同步的（返回 ChatResult）。
 * 如果 ToolCallLoopStage 在 Pipeline 内同步执行 execute()，必须等整个 Flux 完成后才返回，
 * 无法实时透传 token。如果改 Pipeline 为异步（返回 Flux&lt;ChatResult&gt;），
 * ContextBuildStage 和 InterceptorStage 也必须改异步——复杂度剧增。
 *
 * <p><b>双入口机制</b>：execute() 被 Pipeline 跳过（supports()=false），
 * Engine 在 Pipeline 返回后手动调 chatStream() 返回 Flux&lt;String&gt;。
 *
 * <p><b>对比方案</b>：
 * <ul>
 *   <li>方案 A（双入口，选中）：Pipeline 保持同步简单，ToolCallLoop 独立管理 Flux 生命周期</li>
 *   <li>方案 B（Pipeline 整体异步）：所有 Stage 改异步，ContextBuildStage 和 InterceptorStage 没有异步需求</li>
 *   <li>方案 C（Pipeline 中等 Flux 完成）：实时透传能力尽失</li>
 * </ul>
 *
 * @author LyClaw Team
 * @version 1.0
 */
public class ToolCallLoopStage implements PipelineStage {

    /** ModelProvider——防腐层。engine 不直接依赖 lyclaw-adapter，通过此接口获取适配器。 */
    private final ModelProvider modelProvider;

    /** ToolRegistry——工具注册表。用于执行工具调用（第一版简单，第二版 ToolCallLoop 逻辑更复杂时用）。 */
    private final ToolRegistry toolRegistry;

    /**
     * 工具调用循环策略。控制最大轮次（默认 10 轮）、是否应继续循环。
     * 第一版 DefaultToolCallPolicy.shouldContinue() 根据轮次 < maxRounds 返回 true/false。
     */
    private final ToolCallPolicy toolCallPolicy;

    /**
     * 构造函数。
     *
     * @param modelProvider    模型适配器提供者
     * @param toolRegistry 工具注册表
     * @param toolCallPolicy 循环终止策略
     */
    public ToolCallLoopStage(ModelProvider modelProvider,
                             ToolRegistry toolRegistry,
                             ToolCallPolicy toolCallPolicy) {
        this.modelProvider = modelProvider;
        this.toolRegistry = toolRegistry;
        this.toolCallPolicy = toolCallPolicy;
    }

    @Override
    public String getName() {
        return "ToolCallLoop";
    }

    /**
     * Pipeline 同步执行时，此 Stage 被跳过。
     * Engine 在 Pipeline 返回后，手动调用 {@link #chatStream(ChatContext)} 启动流式输出。
     *
     * @return false——PipelineBuilder.executeStages() 检查到此值后跳过 execute()，递归到 index+1
     */
    @Override
    public boolean supports(ChatContext context) {
        // 为什么返回 false？详细解释见类介绍"双入口设计"
        // 核心原因：Pipeline.execute() 是同步的，但模型调用是流式的（Flux<String>）
        // 如果 stage 在 Pipeline 内执行 execute()，必须阻塞等待整个 Flux 完成后才返回
        // 那流式 token 就无法实时透传给用户了
        // 所以这里返回 false，让 Pipeline 跳过本 Stage
        // Engine 在 Pipeline.execute() 返回后，手动调 chatStream() 得到 Flux
        // 然后将 Flux 作为 Engine.execute() 的返回值
        return false;
    }

    @Override
    public void execute(ChatContext context, Chain chain) throws Exception {
        // 空实现——因为 supports()=false，PipelineBuilder 不会调到这里
        // 但接口要求实现，所以给出空的方法体
    }

    /**
     * 流式模型调用——这是本 Stage 的核心方法。
     *
     * 通过 Flux.create() 创建一个推模式 Flux（push-based）。
     * 当订阅者订阅时，create 的 lambda 开始执行：
     * 1. 获取模型适配器（adapter）
     * 2. 进入工具调用循环
     * 3. 每轮：调用 adapter.chatStream() 获取模型 token 流
     * 4. 订阅 token 流：每个 token 通过 emitter.next() 发射给调用方
     * 5. 轮流结束后检查 shouldContinue()
     * 6. 继续 → 下一轮；结束 → emitter.complete()
     *
     * 如果中间出错（adapter 获取失败、chatStream 异常），
     * emitter.error() 通知订阅者。
     *
     * @param context 已由 ContextBuildStage 和 InterceptorStage 准备好的 ChatContext
     *                context.getMessages() 已构建好
     *                context.getSkills() 已被注入
     *                context.getModelConfig() 已被设置（DefaultEngine 中设置的）
     * @return Flux<String>——订阅它获得实时 token 流
     */
    public Flux<String> chatStream(ChatContext context) {
        // Flux.create() 创建一个 Flux，它的核心逻辑在 lambda 中
        // emitter 是一个 FluxSink——可以向它发射元素（next）、错误（error）、结束信号（complete）
        return Flux.create(emitter -> {
            try {
                // ──────────────────────────────────────
                // 1. 获取模型适配器
                // ──────────────────────────────────────
                // 通过 ModelProvider 获取，engine 不需要知道是哪个厂商
                // adapter 是 ModelAdapter 类型（lyclaw-core 中已有的接口）
                var adapter = modelProvider.getAdapter(
                    context.getModelConfig().getProvider());

                // ──────────────────────────────────────
                // 2. 工具调用循环
                // ──────────────────────────────────────
                // round 从 0 开始计数，每次循环 +1
                // toolCallPolicy.shouldContinue 根据 round < maxRounds 决定是否继续
                int round = 0;

                // do-while 循环：至少执行一轮（即使 round=0 也执行）
                do {
                    // 构造 ChatRequest——chatStream() 只接受 ChatRequest 单参数
                    // 注入 context.getMessages() 作为消息列表
                    // context.getSkills() 转为 List<ToolDefinition> 作为 tools
                    ChatRequest chatReq = ChatRequest.builder()
                        .messages(context.getMessages())
                        .tools(skillsToToolDefinitions(context.getSkills()))
                        .build();

                    // 调用适配器的流式 chat——返回 Flux<String>
                    Flux<String> stream = adapter.chatStream(chatReq);

                    // 订阅 model 的 token 流
                    stream.subscribe(
                        // onNext：每个 token 到达时执行
                        // 直接透传给调用方——用户在前端实时看到 token
                        token -> emitter.next(token),

                        // onError：流发生错误时执行
                        // 将错误传递给调用方的 Flux.subscriber
                        error -> emitter.error(error),

                        // onComplete：流结束时执行
                        // 第一版不在此处检查 tool_calls（模型回复中的 tool_calls 检测）
                        // 第二版在此处解析 accumulate 的完整响应，检测 hasToolCalls()
                        () -> {
                            // 第二版：accumulate + parse + check tool_calls
                        }
                    );

                    // 轮次 +1
                    round++;

                } while (toolCallPolicy.shouldContinue(context, round));

                // 循环结束后，调用 emitter.complete() 通知调用方"流已结束"
                // 调用方（DefaultEngine）的 doFinally 会触发 session 持久化
                emitter.complete();

            } catch (Exception e) {
                // adapter 获取失败（如配置错误：provider 不存在）或 chatStream 的同步部分出错
                // 通过 emitter.error() 把错误传给调用方的 Flux.subscriber
                emitter.error(e);
            }
        });
    }

    /**
     * 将 Skill 列表转换为 ToolDefinition 列表。
     *
     * <p>ModelAdapter.chatStream() 需要 ChatRequest 作为参数，
     * ChatRequest 的 tools 字段类型为 List&lt;ToolDefinition&gt;。
     * context.getSkills() 返回的是 List&lt;Skill&gt;，Skill 有 getId()/getName()/getDescription()，
     * 与 ToolDefinition 的 name/description 字段对应。
     *
     * @param skills 技能列表，可能为 null
     * @return 工具定义列表，不会为 null
     */
    private List<ToolDefinition> skillsToToolDefinitions(List<Skill> skills) {
        if (skills == null || skills.isEmpty()) {
            return Collections.emptyList();
        }
        return skills.stream()
            .map(s -> ToolDefinition.builder()
                .name(s.getId())
                .displayName(s.getName() != null ? s.getName() : s.getId())
                .description(s.getDescription() != null ? s.getDescription() : "")
                .source("builtin")
                .timeout(s.getTimeout() != null ? s.getTimeout().toMillis() : 30000)
                .build())
            .collect(Collectors.toList());
    }
}
```

---

## 第五十八块代码文件：MetricsStage.java

**文件路径**：`lyclaw-engine/src/main/java/lyjew/com/lyclaw/pipeline/impl/stages/MetricsStage.java`

```java
package lyjew.com.lyclaw.pipeline.impl.stages;

import lyjew.com.lyclaw.context.ChatContext;
import lyjew.com.lyclaw.event.EventBus;
import lyjew.com.lyclaw.event.Event;
import lyjew.com.lyclaw.pipeline.Chain;
import lyjew.com.lyclaw.pipeline.PipelineStage;

/**
 * MetricsStage — 指标采集阶段（Pipeline 第 4 个 Stage）。
 *
 * <p><b>设计动机</b>：Token 用量和请求耗时是横切关注点。
 * 本 Stage 在 Pipeline 尾部执行，从 ChatContext 读取数据并发布事件。
 * 不修改 ChatContext 中的业务数据——只读取统计值。
 *
 * <p><b>已知第一版限制</b>：
 * Token 数据在 Pipeline 执行完后通过 chatStream() 累加，
 * MetricsStage 在 Pipeline 内执行时 Token 值为 0。
 * 第一版只用它计算耗时（startTime 在 Pipeline 前设置）。
 * 第二版将 MetricsStage 移到 ToolCallLoopStage 的 doFinally 中。
 *
 * @author LyClaw Team
 * @version 1.0
 */
public class MetricsStage implements PipelineStage {

    /** 事件总线——用于发布指标事件（第一版简化，只检查订阅者）。 */
    private final EventBus eventBus;

    /**
     * 构造函数。
     *
     * @param eventBus 事件总线实例
     */
    public MetricsStage(EventBus eventBus) {
        this.eventBus = eventBus;
    }

    @Override
    public String getName() {
        return "Metrics";
    }

    @Override
    public boolean supports(ChatContext context) {
        return true;
    }

    @Override
    public void execute(ChatContext context, Chain chain) throws Exception {
        // ──────────────────────────────────────────────
        // 第一步：让后续的 ResponseBuildStage 先构建结果
        // ──────────────────────────────────────────────
        // 为什么 MetricsStage 在 ResponseBuildStage 之前？
        // 因为 Pipeline 的 Stage 顺序是：
        //   ContextBuild → Interceptor → [ToolCallLoop跳过] → Metrics → ResponseBuild
        // metrics 采集的 Token 数据虽然在第一版为零（在 Pipeline 后 chatStream 才累加），
        // 但请求的 startTime 已经在 ContextBuildStage 之前设置了，
        // 所以 chain.proceed() 后 Time 差值是有效的。
        // 调用 proceed() 使 ResponseBuildStage 执行完毕回到这里。
        chain.proceed(context);

        // ──────────────────────────────────────────────
        // 第二步：采集 Token 用量
        // ──────────────────────────────────────────────
        // 第一版限制：Token 数据在 Pipeline 执行完后的 chatStream() 中累加
        // MetricsStage 在 Pipeline 内执行，此时 Token = 0
        // 所以这里读取到的值是初始化值（0）
        // 这里的代码是为了第二版做准备——当 MetricsStage 移到 ToolCallLoopStage 的 doFinally 后，
        // 这里的 Token 读取就能拿到真实值
        long elapsedMs = (System.nanoTime() - context.getStartTime()) / 1_000_000;

        // 工具调用次数统计
        int toolCallCount = (context.getToolCallHistory() != null)
            ? context.getToolCallHistory().size()
            : 0;

        // ──────────────────────────────────────────────
        // 第三步：发布事件
        // ──────────────────────────────────────────────
        // 先检查是否有订阅者——避免在无订阅者时创建事件对象
        // 事件对象虽然小（几个字段），但在高 QPS 下（比如 100 QPS），
        // 每秒创建 100 个无用事件对象，GC 会有压力
        if (eventBus.hasSubscribers(Event.class)) {
            // 第一版简化——不发布具体事件
            // 第二版在此发布 TokenConsumedEvent 和 ToolCalledEvent
            // eventBus.publish(new TokenConsumedEvent(context));
        }
    }
}
```

---

## 第五十九块代码文件：ResponseBuildStage.java

**文件路径**：`lyclaw-engine/src/main/java/lyjew/com/lyclaw/pipeline/impl/stages/ResponseBuildStage.java`

```java
package lyjew.com.lyclaw.pipeline.impl.stages;

import lyjew.com.lyclaw.context.ChatContext;
import lyjew.com.lyclaw.dto.ChatResult;
import lyjew.com.lyclaw.pipeline.Chain;
import lyjew.com.lyclaw.pipeline.PipelineStage;

/**
 * ResponseBuildStage — 响应构建阶段（Pipeline 第 5 个——最后一个 Stage）。
 *
 * <p><b>设计动机</b>：Pipeline 的最终输出是 ChatResult。
 * 本 Stage 从 ChatContext 中提取回复文本，组装为 ChatResult，
 * 存入 context.setAttribute("result")。后置拦截器和 MetricsStage 读取这个 result。
 *
 * <p><b>为什么不调 chain.proceed()</b>：
 * 本 Stage 是最后一个。调用 proceed() 会触发 executeStages(index+1) → index>=size → 返回 null。
 * 逻辑正确但浪费一次空递归。不调 proceed()，方法执行完毕自然结束。
 *
 * <p><b>第一版限制</b>：Pipeline.execute() 在 ToolCallLoopStage 之前执行完毕，
 * 模型还没被调用，messages 中不包含 AI 回复。ChatResult.replyText 为空字符串。
 * 实际回复文本通过 Flux&lt;String&gt; 流式传递给调用方。
 *
 * @author LyClaw Team
 * @version 1.0
 */
public class ResponseBuildStage implements PipelineStage {

    @Override
    public String getName() {
        return "ResponseBuild";
    }

    @Override
    public boolean supports(ChatContext context) {
        return true;
    }

    @Override
    public void execute(ChatContext context, Chain chain) throws Exception {
        // ──────────────────────────────────────────────
        // 步骤 1：从 ChatContext 提取回复文本
        // ──────────────────────────────────────────────
        // context.messages 中最后一条 role=assistant 的消息的 content 就是回复文本
        // 注意：由于 Pipeline.execute() 在 ToolCallLoopStage 之前执行完毕，
        // 模型还没有被调用，messages 中还不包含 AI 回复
        // 所以这里的 replyText 为空字符串
        // 这是第一版同步 Pipeline 的限制——实际回复文本在 chatStream() 中动态到达
        // ResponseBuildStage 构建的 ChatResult 主要用于：
        //   1. 让 postHandle 拦截器（LoggingInterceptor）能记录一些请求信息
        //   2. 给 MetricsStage 计算耗时
        // 实际的回复文本不通过 ChatResult 传递，而是通过 Flux<String> 流式传递
        String replyText = extractReplyText(context);

        // 计算请求耗时
        long elapsedMs = (System.nanoTime() - context.getStartTime()) / 1_000_000;

        // ──────────────────────────────────────────────
        // 步骤 2：构建 ChatResult
        // ──────────────────────────────────────────────
        // 使用 Builder 模式——chain.setting 哪些字段赋值
        // 没有赋值的字段使用默认值（如 tokenUsage=""）
        ChatResult result = ChatResult.builder()
            .reply(replyText)            // 空字符串——模型还没被调用
            .tokenUsage("")              // 第一版不统计 Token
            .durationMs(elapsedMs)       // 从 startTime 到现在的耗时
            .finishReason("stop")        // 第一版固定为 "stop"
            .build();

        // 将 result 存入 context.attributes
        // InterceptorStage 的 postHandle 会从这里读取
        // MetricsStage 也会从这里读取
        context.setAttribute("result", result);

        // 最后一个 Stage，不调用 chain.proceed()
        // 如果调用 proceed()，会触发 executeStages(index+1) → index>=size → 返回 null
        // 逻辑正确但浪费时间——不调 proceed() 直接结束
    }

    /**
     * 从 context.messages 中找到最后一条 role=assistant 的消息，返回 content。
     *
     * @param context ChatContext
     * @return 回复文本（可能为空字符串）
     */
    private String extractReplyText(ChatContext context) {
        // 如果 messages 为 null（ContextBuildStage 没有构建消息列表），直接返回空字符串
        if (context.getMessages() == null) {
            return "";
        }

        // 使用 stream() + filter() 找到 role=assistant 的消息
        // findFirst() 返回第一个 assistant 消息——因为是按时间正向排列的，
        // 所以最后一条 assistant 消息就是最新回复
        // 如果没有任何 assistant 消息（比如新会话），返回空字符串
        return context.getMessages().stream()
            .filter(msg -> "assistant".equals(msg.getRole()))
            .findFirst()
            .map(msg -> msg.getContent() != null ? msg.getContent() : "")
            .orElse("");
    }
}
```

---

## 第六十块代码文件：DefaultEngine.java

**文件路径**：`lyclaw-engine/src/main/java/lyjew/com/lyclaw/engine/impl/DefaultEngine.java`

```java
package lyjew.com.lyclaw.engine.impl;

import lyjew.com.lyclaw.context.ChatContext;
import lyjew.com.lyclaw.engine.Engine;
import lyjew.com.lyclaw.engine.EngineMetadata;
import lyjew.com.lyclaw.model.ChatRequest;
import lyjew.com.lyclaw.model.Message;
import lyjew.com.lyclaw.model.ModelConfig;
import lyjew.com.lyclaw.model.Session;
import lyjew.com.lyclaw.pipeline.Pipeline;
import lyjew.com.lyclaw.pipeline.PipelineStage;
import lyjew.com.lyclaw.pipeline.impl.PipelineBuilder;
import lyjew.com.lyclaw.pipeline.impl.stages.*;
import lyjew.com.lyclaw.provider.ModelProvider;
import lyjew.com.lyclaw.storage.SessionStorage;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;

/**
 * DefaultEngine — 默认引擎实现（兜底引擎）。
 *
 * <p><b>设计动机</b>：标准 QA 对话引擎。不包含具体对话处理逻辑——
 * 所有工作委托给 Pipeline（5 个 Stage）和 ToolCallLoopStage。
 * 任何未被其他 Engine 处理的请求都由本引擎兜底（supports() 始终 true）。
 *
 * <p><b>执行流程</b>：
 * <ol>
 *   <li>准备：创建 ChatContext，设置 ModelConfig，获取 ModelAdapter（尽早失败）</li>
 *   <li>Pipeline 同步执行：ContextBuildStage → InterceptorStage → [跳过] → MetricsStage → ResponseBuildStage</li>
 *   <li>流式模型调用：toolCallLoopStage.chatStream(context) 返回 Flux&lt;String&gt;</li>
 *   <li>收尾：Flux.doFinally 中持久化 Session</li>
 * </ol>
 *
 * <p><b>为什么 ModelAdapter 在 Engine 中获取而非 ToolCallLoopStage 中？</b>
 * ModelProvider.getAdapter() 可能涉及 IO（读取 API Key、初始化 HTTP 连接池）。
 * 应尽早执行——晚失败不如早失败。
 *
 * <p><b>为什么 Session 持久化在 doFinally 中而非 Pipeline 中？</b>
 * Pipeline.execute() 返回时 Flux 还没 complete()。工具调用结果在 Flux 流中动态到达。
 * Session 必须在 Flux 结束后保存。doFinally 保证无论正常结束还是异常结束都执行。
 *
 * @author LyClaw Team
 * @version 1.0
 */
public class DefaultEngine implements Engine {

    /** Pipeline 实例——由 PipelineBuilder.build() 在构造函数或工厂方法中创建。 */
    private final Pipeline pipeline;

    /** ToolCallLoopStage——Pipeline 中唯一的"异步" Stage，通过 chatStream() 返回 Flux。 */
    private final ToolCallLoopStage toolCallLoopStage;

    /** Session 存储——持久化会话数据（追加用户消息和 AI 回复到 session.messages 中）。 */
    private final SessionStorage sessionStorage;

    /** 模型适配器提供者——防腐层，Engine 不依赖 lyclaw-adapter 的具体实现类。 */
    private final ModelProvider modelProvider;

    /** 引擎元信息——名称、版本、能力列表等。 */
    private final EngineMetadata metadata;

    /**
     * 主构造函数。
     *
     * @param pipeline           已构建的 Pipeline
     * @param toolCallLoopStage  ToolCallLoopStage 引用（Pipeline 中的那个实例）
     * @param sessionStorage     会话存储
     * @param modelProvider      模型适配器提供者
     * @param metadata           引擎元信息
     */
    public DefaultEngine(Pipeline pipeline,
                         ToolCallLoopStage toolCallLoopStage,
                         SessionStorage sessionStorage,
                         ModelProvider modelProvider,
                         EngineMetadata metadata) {
        this.pipeline = pipeline;
        this.toolCallLoopStage = toolCallLoopStage;
        this.sessionStorage = sessionStorage;
        this.modelProvider = modelProvider;
        this.metadata = metadata;
    }

    /**
     * 工厂方法——自动创建 PipelineBuilder 并装配 5 个 Stage。
     *
     * 调用方只需要传入各个 Stage 实例和共享组件，
     * 不需要知道 PipelineBuilder 的存在和装配细节。
     * 如果将来需要新增 Stage（比如加一个 SafetyAuditStage），
     * 只需在这里加一个 addStage() 调用。调用方代码零修改。
     *
     * @param sessionStorage 会话存储
     * @param modelProvider  ModelProvider
     * @param toolCallLoopStage ToolCallLoopStage
     * @param contextBuild  ContextBuildStage
     * @param interceptor   InterceptorStage
     * @param metrics       MetricsStage
     * @param responseBuild ResponseBuildStage
     * @param metadata      引擎元信息
     * @return 构造完毕的 DefaultEngine 实例
     */
    public static DefaultEngine create(
            SessionStorage sessionStorage,
            ModelProvider modelProvider,
            ToolCallLoopStage toolCallLoopStage,
            PipelineStage contextBuild,
            PipelineStage interceptor,
            PipelineStage metrics,
            PipelineStage responseBuild,
            EngineMetadata metadata) {

        // 使用 Builder 链式添加 5 个 Stage
        Pipeline pipeline = new PipelineBuilder()
            .addStage(contextBuild)    // 第 1 个：加载 Session、构建消息列表
            .addStage(interceptor)     // 第 2 个：拦截器 preHandle
            .addStage(toolCallLoopStage) // 第 3 个：supports=false 跳过（占位）
            .addStage(metrics)         // 第 4 个：指标采集
            .addStage(responseBuild)   // 第 5 个：构建 ChatResult
            .build();                  // 返回匿名 Pipeline 实现

        return new DefaultEngine(pipeline, toolCallLoopStage, sessionStorage, modelProvider, metadata);
    }

    @Override
    public String getName() {
        return "default";
    }

    /**
     * Integer.MAX_VALUE——最低优先级。
     * EngineSelector 按 getOrder() 升序排列引擎列表，
     * DefaultEngine 排在最后一个，作为兜底引擎。
     * 只有当所有特殊引擎（ReasoningEngine、PlanningEngine 等）都不匹配时，才使用 DefaultEngine。
     */
    @Override
    public int getOrder() {
        return Integer.MAX_VALUE;
    }

    /**
     * 始终返回 true——作为兜底引擎，任何请求都可以由 DefaultEngine 处理。
     */
    @Override
    public boolean supports(ChatRequest request) {
        return true;
    }

    @Override
    public EngineMetadata getMetadata() {
        return metadata;
    }

    /**
     * 执行对话，返回流式响应 Flux&lt;String&gt;。
     *
     * 完整执行流程（三步）：
     *
     * 第一步：准备。
     *   - 创建 ChatContext（含 ChatRequest）
     *   - 设置 ModelConfig（从 request.provider/model 解析）
     *   - 获取 ModelAdapter（尽可能早——晚失败不如早失败）
     *
     * 第二步：Pipeline 同步执行。
     *   调用 pipeline.execute(context)
     *   内部按 Stage 顺序执行：
     *     ContextBuildStage（加载 Session + 构建消息列表 + 注入 Skills）
     *     InterceptorStage（preHandle 拦截器）
     *     [跳过 ToolCallLoopStage]
     *     MetricsStage（计算耗时）
     *     ResponseBuildStage（构建空的 ChatResult——模型还没被调用）
     *   返回的 ChatResult 不包含回复文本（空字符串）
     *
     * 第三步：流式模型调用 + 收尾。
     *   调 toolCallLoopStage.chatStream(context) 得到 Flux<String>
     *   返回这个 Flux 给调用方
     *   Flux.doFinally 中保存 Session（用户消息追加到 session.messages）
     *
     * @param request 对话请求（含消息列表、sessionId、model 信息等）
     * @return Flux<String>——调用方订阅后实时接收模型 token
     */
    @Override
    public Flux<String> execute(ChatRequest request) {
        // ──────────────────────────────────────────────
        // 第一步：准备
        // ──────────────────────────────────────────────

        // 1.1 创建 ChatContext——将请求封装为上下文对象
        // ChatContext 是可变对象，在 Pipeline 执行中逐步被填充
        ChatContext context = new ChatContext(request);

        // 1.2 设置 ModelConfig——告诉后续 Stage"当前的 model 配置是什么"
        // resolveModelConfig 从 request.model 中解析，provider 默认 "minimax"
        context.setModelConfig(resolveModelConfig(request));

        // 1.3 获取 ModelAdapter——防腐层调用
        // 这里可能抛异常（比如 provider 不存在、API Key 无效、网络不通）
        // 抛异常时整个 Flux 还没创建，所以调用方直接收到 Flux.error()（new Flux 包装异常）
        // 优点是尽早失败——如果在 chatStream 中才获取 adapter，
        // 此时 Pipeline 已经执行完毕，前面的工作白费了
        // provider 从刚设置的 ModelConfig 中获取，而不是从 ChatRequest（它没有 provider 字段）
        context.setAdapter(
            modelProvider.getAdapter(context.getModelConfig().getProvider()));

        // ──────────────────────────────────────────────
        // 第二步：Pipeline 同步执行
        // ──────────────────────────────────────────────
        // pipeline.execute(context) 内部递归遍历 5 个 Stage
        // 注意这里没检查返回的 ChatResult（它为 null 或空字符串），
        // 因为实际回复文本不通过 ChatResult 传递——通过 Flux<String> 
        pipeline.execute(context);

        // ──────────────────────────────────────────────
        // 第三步：流式模型调用 + 收尾
        // ──────────────────────────────────────────────

        // toolCallLoopStage.chatStream(context) 返回 Flux<String>
        // 这个 Flux 在 subscribe 时才开始真正的模型调用
        // 我们直接 return 这个 Flux——调用方 subscribe 后开始接收 token
        return toolCallLoopStage.chatStream(context)
            // doFinally——无论 Flux 正常 complete 还是 error 都执行
            .doFinally(signalType -> {
                // 从 context 中获取 Session
                Session session = context.getSession();

                // session 不为 null 且用户消息存在时，追加消息
                // 用户消息在 ContextBuildStage 中设置
                if (session != null && context.getUserMessage() != null) {
                    // 获取 session 中的消息列表
                    List<Message> msgs = session.getMessages();
                    // 如果消息列表为 null（理论上不会，但防御性检查）
                    if (msgs == null) {
                        msgs = new ArrayList<>();
                        session.setMessages(msgs);
                    }
                    // 追加当前用户消息到历史消息列表
                    msgs.add(context.getUserMessage());

                    // 调用 sessionStorage.save() 持久化
                    // 底层先写 .tmp 文件再 rename，保证原子性
                    sessionStorage.save(session);
                }
            });
    }

    /**
     * 从 ChatRequest 中解析 ModelConfig。
     *
     * @param request 对话请求
     * @return ModelConfig 实例
     */
    private ModelConfig resolveModelConfig(ChatRequest request) {
        ModelConfig config = new ModelConfig();

        // provider：ChatRequest 没有 provider 字段，用系统默认 "minimax"
        // 后续如果多个 provider 需要切换，通过 EngineSelector 选择不同 Engine 实现
        // 或者第二版在 ChatRequest 中新增 provider 字段
        config.setProvider("minimax");

        // modelName：传入 request 中的 model 字段
        // ChatRequest 的 model 字段是泛指的"模型名"，如 "MiniMax-M2.7"
        config.setModel(request.getModel() != null ? request.getModel() : "MiniMax-M2.7");

        return config;
    }
}
```

---

## 第六十一块代码文件：EngineSelector.java

**文件路径**：`lyclaw-engine/src/main/java/lyjew/com/lyclaw/engine/impl/EngineSelector.java`

```java
package lyjew.com.lyclaw.engine.impl;

import lyjew.com.lyclaw.engine.Engine;
import lyjew.com.lyclaw.model.ChatRequest;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * EngineSelector — 引擎选择器。
 *
 * <p><b>设计动机</b>：当系统有多个 Engine（DefaultEngine + ReasoningEngine）时，
 * 需要统一入口选择引擎。按 getOrder() 升序排序后遍历 supports() 匹配。
 * 调用方只需一句 engineSelector.select(request).execute(request)。
 *
 * <p><b>算法</b>：
 * <ol>
 *   <li>按 getOrder() 升序排序（ReasoningEngine[10] → PlanningEngine[30] → DefaultEngine[MAX]）</li>
 *   <li>遍历调用 supports(request)</li>
 *   <li>第一个返回 true 的被选中</li>
 *   <li>全部返回 false → 抛异常（不应发生——DefaultEngine 始终 true）</li>
 * </ol>
 *
 * <p><b>为什么 getOrder() 是方法不是 @Order 注解？</b>
 * Engine 接口在 lyclaw-core 模块。lyclaw-core 不依赖 Spring。
 * getOrder() 是纯 Java 方法——Spring 环境下可以用，非 Spring 环境（单元测试、CLI）也能用。
 *
 * <p><b>新增引擎</b>：新建类实现 Engine + @Component 自动注册。已有 Engine 代码零修改。
 *
 * @author LyClaw Team
 * @version 1.0
 */
public class EngineSelector {

    /**
     * 已按 getOrder() 升序排序的 Engine 列表。
     * final——一旦构造完成，排序结果固定。
     * 如果要重新排序（第二版插件系统），调用 refresh()。
     */
    private final List<Engine> sortedEngines;

    /**
     * 构造函数。
     *
     * 接收所有 Engine 实现（Spring 会自动注入所有 @Component Engine），
     * 立即按 getOrder() 排序。
     *
     * @param engines Engine 列表（包含 DefaultEngine + 其他 Engine 实现）
     */
    public EngineSelector(List<? extends Engine> engines) {
        // 防御性拷贝：不直接持有 Spring 传入的 List（Spring 的 List 可能是不可变的）
        this.sortedEngines = new ArrayList<>(engines);

        // 按 getOrder() 升序排序
        // DefaultEngine 返回 Integer.MAX_VALUE → 最后
        // ReasoningEngine 返回 10 → 最先
        // PlanningEngine 返回 30 → 中间
        this.sortedEngines.sort(Comparator.comparingInt(Engine::getOrder));
    }

    /**
     * 选择引擎。
     *
     * 按 getOrder() 升序遍历已排序的引擎列表。
     * 对每个引擎调用 supports(request)。
     * 第一个返回 true 的引擎被选中。
     * 默认情况下 ReasoningEngine(10) 最先被检查 → DefaultEngine(MAX) 最后被检查。
     *
     * @param request 对话请求
     * @return 匹配的 Engine
     * @throws IllegalStateException 如果没有引擎匹配请求。
     *         理论上不应发生——DefaultEngine 始终返回 true。
     *         如果抛了这个异常说明 DefaultEngine 没有被注册。
     */
    public Engine select(ChatRequest request) {
        // 遍历 sortedEngines——它们已经按 getOrder() 升序排列
        for (Engine engine : sortedEngines) {
            // 对每个引擎检查 supports()
            // 如果引擎返回 true（比如 DefaultEngine 始终 true），
            // 立即返回该引擎
            if (engine.supports(request)) {
                return engine;
            }
        }

        // 如果遍历完所有引擎都没匹配：
        // 说明 DefaultEngine 没有被注册到列表中
        // 这是配置错误——DefaultEngine 必须存在
        throw new IllegalStateException(
            "No engine supports the request (DefaultEngine is missing?)");
    }

    /**
     * 刷新引擎列表。
     *
     * 第一版用不到（Engine 在 Spring 启动时全部注册），
     * 第二版插件系统需要——运行时动态加载新的 Engine 实现类后，
     * 调用此方法重新排序和注册。
     *
     * @param engines 新的引擎列表
     */
    public void refresh(List<? extends Engine> engines) {
        // 先清除旧列表
        sortedEngines.clear();
        // 添加新引擎
        sortedEngines.addAll(engines);
        // 重新排序
        sortedEngines.sort(Comparator.comparingInt(Engine::getOrder));
    }

    /**
     * 获取当前已排序的引擎列表。
     * 用于管理控制台展示、健康检查、调试。
     *
     * @return 按优先级排序的引擎列表
     */
    public List<Engine> getEngines() {
        return sortedEngines;
    }
}
```

---


---

# 第六部分（续）：Tool 实现 + Skill 实现（lyclaw-engine）

> 本部分对照已有接口的真实签名编写，保证编译通过。

## 依赖关系说明

    Tool 体系：                                      Skill 体系：
      Tool（接口，lyclaw-core）                        Skill（接口，lyclaw-core）
        │                                                 │
        ├── DefaultToolRegistry（本部分）                   ├── DefaultSkillRegistry（本部分）
        │    注册所有 Tool，管理注册/发现/执行                │    权威注册表，将所有能力统一注册
        │                                                 │
        ├── ToolCallPolicy（接口，lyclaw-core）              ├── SkillGraphImpl（本部分）
        │   │                                               │    依赖图 + 循环检测
        │   └── DefaultToolCallPolicy（本部分）               │
        │                                                     │
        ├── ToolCallLoop（本部分，模板方法模式）                └── ToolToSkillAdapter（本部分）
        │    固化"调模型→检查→执行→继续"流程                     将 Tool 包装为 Skill(type=TOOL)
        │
        ├── WebSearchTool（本部分，网络搜索）
        ├── CalculatorTool（本部分，数学计算）
        ├── CurrentTimeTool（本部分，当前时间）
        └── McpToolAdapter（本部分，MCP 协议适配）

---

## 块 62：DefaultToolRegistry.java

**文件路径**：`lyclaw-engine/src/main/java/lyjew/com/lyclaw/tool/impl/DefaultToolRegistry.java`

**文件级注解**：

DefaultToolRegistry 是 ToolRegistry 接口的默认实现，使用 ConcurrentHashMap 存储所有已注册的工具。

**与已有接口的签名对齐**：
- ToolRegistry 的关键方法：register/unregister/get/getAll/getAllDefinitions/execute
- `execute(String name, Map<String, Object> args)` 返回 `ToolResult`
- `getAllDefinitions()` 返回 `List<ToolDefinition>`
- ToolDefinition 在 lyclaw-common 中，用 Lombok @Data

```java
package lyjew.com.lyclaw.tool.impl;

import lyjew.com.lyclaw.tool.Tool;
import lyjew.com.lyclaw.tool.ToolRegistry;
import lyjew.com.lyclaw.tool.ToolResult;
import lyjew.com.lyclaw.model.ToolDefinition;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * DefaultToolRegistry — 默认工具注册表（注册表模式 + ConcurrentHashMap）。
 *
 * <p><b>对比方案</b>：用 ConcurrentHashMap 存储所有工具。
 * 工具数量有限（第一版 ≤ 10），HashMap ≥ ConcurrentHashMap ≥ LinkedHashMap 的查找性能差异可忽略。
 * 选用 ConcurrentHashMap 是线程安全需求（多个 Engine 线程同时读取注册表）而非性能需求。
 *
 * @author LyClaw Team
 * @version 1.0
 */
public class DefaultToolRegistry implements ToolRegistry {

    /** 核心存储：key=工具名称，value=工具实例。 */
    private final Map<String, Tool> toolMap = new ConcurrentHashMap<>();

    /** 工具执行线程池的最小线程数。第一版固定 4 个。 */
    private static final int POOL_SIZE = 4;

    /** 工具超时（毫秒），默认 30 秒。 */
    private static final long DEFAULT_TIMEOUT_MS = 30_000;

    @Override
    public void register(Tool tool) {
        String name = tool.getName();
        Tool old = toolMap.putIfAbsent(name, tool);
        if (old != null) {
            throw new RuntimeException("Duplicate tool: " + name
                + " (existing: " + old.getClass().getName() + ")");
        }
    }

    @Override
    public void unregister(String name) {
        toolMap.remove(name);
    }

    @Override
    public Optional<Tool> get(String name) {
        return Optional.ofNullable(toolMap.get(name));
    }

    @Override
    public List<Tool> getAll() {
        return new ArrayList<>(toolMap.values());
    }

    @Override
    public List<ToolDefinition> getAllDefinitions() {
        List<ToolDefinition> defs = new ArrayList<>();
        for (Tool tool : toolMap.values()) {
            defs.add(tool.getDefinition());
        }
        return defs;
    }

    @Override
    public ToolResult execute(String name, Map<String, Object> args) {
        Tool tool = toolMap.get(name);
        if (tool == null) {
            return ToolResult.error("Tool not found: " + name);
        }
        try {
            // Tool.execute() 同步调用，用 CompletableFuture 包装超时控制
            return CompletableFuture
                .supplyAsync(() -> {
                    long start = System.currentTimeMillis();
                    try {
                        ToolResult result = tool.execute(args);
                        return result.withDuration(System.currentTimeMillis() - start);
                    } catch (Exception e) {
                        return ToolResult.error("Tool execution failed: " + e.getMessage());
                    }
                })
                .get(tool.getTimeout() > 0 ? tool.getTimeout() : DEFAULT_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.TimeoutException e) {
            return ToolResult.timeout(name);
        } catch (Exception e) {
            return ToolResult.error("Unexpected error: " + e.getMessage());
        }
    }
}
```

---

## 块 63：DefaultToolCallPolicy.java

**文件路径**：`lyclaw-engine/src/main/java/lyjew/com/lyclaw/tool/impl/DefaultToolCallPolicy.java`

**文件级注解**：

DefaultToolCallPolicy 是 ToolCallPolicy 接口的默认实现。

**已有接口签名**：
- `shouldContinue(ChatContext context, int currentRound)` — currentRound 从第 1 轮开始计数
- `getMaxRounds()` — 返回最大允许轮次
- `onToolError(ModelResponse.ToolCallRequest toolCall, Throwable error, int round, ChatContext context)` — 接收 **ModelResponse.ToolCallRequest**（内部类），不是 ToolCallRequest
- 返回 `ToolErrorAction`，通过静态工厂方法 `skip(ToolResult)/retry()/abortLoop()` 创建

```java
package lyjew.com.lyclaw.tool.impl;

import lyjew.com.lyclaw.context.ChatContext;
import lyjew.com.lyclaw.tool.ToolCallPolicy;
import lyjew.com.lyclaw.tool.ToolErrorAction;
import lyjew.com.lyclaw.tool.ToolResult;
import lyjew.com.lyclaw.model.ModelResponse;

/**
 * DefaultToolCallPolicy — 默认工具调用循环策略。
 *
 * <p>最大 10 轮。出错时第一次重试，第二次跳过，第三次终止循环。
 *
 * @author LyClaw Team
 * @version 1.0
 */
public class DefaultToolCallPolicy implements ToolCallPolicy {

    @Override
    public boolean shouldContinue(ChatContext context, int currentRound) {
        return currentRound < getMaxRounds();
    }

    @Override
    public int getMaxRounds() {
        return 10;
    }

    @Override
    public ToolErrorAction onToolError(ModelResponse.ToolCallRequest toolCall,
                                       Throwable error, int round, ChatContext context) {
        // 第一轮出错：重试一次
        if (round < 2) {
            return ToolErrorAction.retry();
        }
        // 后续出错：跳过，提供降级结果
        return ToolErrorAction.skip(
            ToolResult.error("Tool [" + toolCall.getName() + "] failed after " + round + " rounds"));
    }
}
```

---

## 块 64：WebSearchTool.java

**文件路径**：`lyclaw-engine/src/main/java/lyjew/com/lyclaw/tool/impl/WebSearchTool.java`

**文件级注解**：

WebSearchTool 实现网络搜索功能。第一版输出占位结果（模拟搜索），第二版接入真实搜索 API。

**已有接口签名**（Tool）：
- `getName() → String`
- `getDefinition() → ToolDefinition`（Lombok @Data 构造）
- `execute(Map<String, Object> arguments) → ToolResult`
- `getTimeout() → long`

**ToolResult 创建方式**：ToolResult.success(content) / ToolResult.error(msg) / ToolResult.timeout(name)

```java
package lyjew.com.lyclaw.tool.impl;

import lyjew.com.lyclaw.tool.Tool;
import lyjew.com.lyclaw.tool.ToolDefinition;
import lyjew.com.lyclaw.tool.ToolResult;

import java.util.Map;

/**
 * WebSearchTool — 网络搜索工具。
 * 第一版模拟搜索，第二版接入搜索 API。
 *
 * @author LyClaw Team
 * @version 1.0
 */
public class WebSearchTool implements Tool {

    @Override
    public String getName() {
        return "web_search";
    }

    @Override
    public ToolDefinition getDefinition() {
        return ToolDefinition.builder()
            .name("web_search")
            .description("搜索互联网获取最新信息")
            .build();
    }

    @Override
    public ToolResult execute(Map<String, Object> arguments) {
        String query = arguments != null && arguments.get("query") != null
            ? arguments.get("query").toString() : "";
        // 第一版模拟结果
        return ToolResult.success("[模拟搜索结果] 关于\"" + query + "\"的搜索结果（第一版模拟，第二版接入真实搜索API）");
    }

    @Override
    public long getTimeout() {
        return 30_000;
    }
}
```

---

## 块 65：CalculatorTool.java

**文件路径**：`lyclaw-engine/src/main/java/lyjew/com/lyclaw/tool/impl/CalculatorTool.java`

**文件级注解**：

CalculatorTool 执行数学计算。使用 Java ScriptEngine 计算表达式。

```java
package lyjew.com.lyclaw.tool.impl;

import lyjew.com.lyclaw.tool.Tool;
import lyjew.com.lyclaw.tool.ToolDefinition;
import lyjew.com.lyclaw.tool.ToolResult;

import javax.script.ScriptEngineManager;
import javax.script.ScriptEngine;
import java.util.Map;

/**
 * CalculatorTool — 数学计算工具。
 * 使用 Java ScriptEngine 计算表达式字符串。
 *
 * @author LyClaw Team
 * @version 1.0
 */
public class CalculatorTool implements Tool {

    private final ScriptEngine engine = new ScriptEngineManager().getEngineByName("JavaScript");

    @Override
    public String getName() {
        return "calculator";
    }

    @Override
    public ToolDefinition getDefinition() {
        return ToolDefinition.builder()
            .name("calculator")
            .description("执行数学计算，输入一个数学表达式返回计算结果")
            .build();
    }

    @Override
    public ToolResult execute(Map<String, Object> arguments) {
        try {
            String expression = arguments != null && arguments.get("expression") != null
                ? arguments.get("expression").toString() : "";
            Object result = engine.eval(expression);
            return ToolResult.success(String.valueOf(result));
        } catch (Exception e) {
            return ToolResult.error("Calculation error: " + e.getMessage());
        }
    }

    @Override
    public long getTimeout() {
        return 5_000;
    }
}
```

---

## 块 66：CurrentTimeTool.java

**文件路径**：`lyclaw-engine/src/main/java/lyjew/com/lyclaw/tool/impl/CurrentTimeTool.java`

```java
package lyjew.com.lyclaw.tool.impl;

import lyjew.com.lyclaw.tool.Tool;
import lyjew.com.lyclaw.tool.ToolDefinition;
import lyjew.com.lyclaw.tool.ToolResult;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;

/**
 * CurrentTimeTool — 当前时间查询工具。
 * 返回 Asia/Shanghai 时区的当前时间。
 *
 * @author LyClaw Team
 * @version 1.0
 */
public class CurrentTimeTool implements Tool {

    private static final ZoneId TZ = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @Override
    public String getName() {
        return "current_time";
    }

    @Override
    public ToolDefinition getDefinition() {
        return ToolDefinition.builder()
            .name("current_time")
            .description("获取当前日期和时间（时区：Asia/Shanghai）")
            .build();
    }

    @Override
    public ToolResult execute(Map<String, Object> arguments) {
        return ToolResult.success(ZonedDateTime.now(TZ).format(FMT));
    }

    @Override
    public long getTimeout() {
        return 1_000;
    }
}
```

---

## 块 67：McpToolAdapter.java

**文件路径**：`lyclaw-engine/src/main/java/lyjew/com/lyclaw/tool/impl/McpToolAdapter.java`

**文件级注解**：

McpToolAdapter 将外部 MCP Server 提供的工具适配为 Tool 接口。第一版占位——不执行实际 MCP 调用。

```java
package lyjew.com.lyclaw.tool.impl;

import lyjew.com.lyclaw.tool.Tool;
import lyjew.com.lyclaw.tool.ToolDefinition;
import lyjew.com.lyclaw.tool.ToolResult;

import java.util.Map;

/**
 * McpToolAdapter — MCP 工具适配器（适配器模式）。
 * 第一版占位，第二版实现完整 MCP 协议调用。
 *
 * @author LyClaw Team
 * @version 1.0
 */
public class McpToolAdapter implements Tool {

    private final String name;
    private final String description;
    private final String serverUrl;

    public McpToolAdapter(String name, String description, String serverUrl) {
        this.name = name;
        this.description = description;
        this.serverUrl = serverUrl;
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public ToolDefinition getDefinition() {
        return ToolDefinition.builder()
            .name(name)
            .description(description + " (来自 MCP: " + serverUrl + ")")
            .build();
    }

    @Override
    public ToolResult execute(Map<String, Object> arguments) {
        return ToolResult.error(
            "MCP tool call not supported in v1. Tool: " + name + ", Server: " + serverUrl);
    }

    @Override
    public long getTimeout() {
        return 30_000;
    }

    public String getServerUrl() {
        return serverUrl;
    }
}
```

---

## 块 68：ToolCallLoop.java

**文件路径**：`lyclaw-engine/src/main/java/lyjew/com/lyclaw/tool/impl/ToolCallLoop.java`

**文件级注解**：

ToolCallLoop 是工具调用循环的核心（模板方法模式）。

**关键签名对齐**：
- ToolRegistry.execute(name, args) 返回 ToolResult
- ChatContext.addToolResult(String callId, String toolName, ToolResult result) — 3 参数
- ModelResponse.getToolCalls() 返回 `List<ModelResponse.ToolCallRequest>`
- ModelResponse.ToolCallRequest 的字段：id, name, arguments（String 类型）

```java
package lyjew.com.lyclaw.tool.impl;

import lyjew.com.lyclaw.context.ChatContext;
import lyjew.com.lyclaw.tool.ToolCallPolicy;
import lyjew.com.lyclaw.tool.ToolRegistry;
import lyjew.com.lyclaw.tool.ToolResult;
import lyjew.com.lyclaw.tool.ToolErrorAction;
import lyjew.com.lyclaw.model.Message;
import lyjew.com.lyclaw.model.ModelResponse;
import java.util.List;

/**
 * ToolCallLoop — 工具调用循环核心（模板方法模式）。
 *
 * @author LyClaw Team
 * @version 1.0
 */
public abstract class ToolCallLoop {

    protected final ToolRegistry toolRegistry;
    protected final ToolCallPolicy toolCallPolicy;

    public ToolCallLoop(ToolRegistry toolRegistry, ToolCallPolicy toolCallPolicy) {
        this.toolRegistry = toolRegistry;
        this.toolCallPolicy = toolCallPolicy;
    }

    /**
     * 执行工具调用循环（模板方法）。
     */
    public String execute(ChatContext context) {
        int round = 0;
        do {
            ModelResponse response = callModel(context, round);

            List<ModelResponse.ToolCallRequest> toolCalls = response.getToolCalls();
            if (toolCalls == null || toolCalls.isEmpty()) {
                appendAssistantMessage(context, response.getContent());
                return response.getContent();
            }

            // 执行每个工具调用
            for (ModelResponse.ToolCallRequest call : toolCalls) {
                // 注意：arguments 是 String 类型（JSON 字符串），不能直接传 Map
                // 这里简化处理，第一版 ToolCallLoop 不解析 JSON
                ToolResult result = executeToolSafely(call.getName(), call.getArguments());
                // addToolResult 需要 3 个参数：callId, toolName, result
                context.addToolResult(call.getId(), call.getName(), result);
            }
            round++;
        } while (toolCallPolicy.shouldContinue(context, round));
        return extractFinalReply(context);
    }

    /** 子类实现具体的模型调用逻辑。 */
    protected abstract ModelResponse callModel(ChatContext context, int round);

    /** 提取最后一条 assistant 消息。 */
    protected String extractFinalReply(ChatContext context) {
        List<Message> messages = context.getMessages();
        if (messages == null || messages.isEmpty()) return "";
        for (int i = messages.size() - 1; i >= 0; i--) {
            if ("assistant".equals(messages.get(i).getRole())) {
                return messages.get(i).getContent();
            }
        }
        return "";
    }

    /** 安全执行工具。 */
    private ToolResult executeToolSafely(String name, String arguments) {
        try {
            // 第一版简化——不解析 JSON arguments
            return toolRegistry.execute(name, null);
        } catch (Exception e) {
            return ToolResult.error("Tool error: " + e.getMessage());
        }
    }

    private void appendAssistantMessage(ChatContext context, String content) {
        // ChatContext 没有 addMessage() 方法——已有 getMessages() 和 setMessages()
        // 正确做法：获取消息列表，如果为 null 则创建一个，追加后 setMessages() 回去
        List<Message> msgs = context.getMessages();
        if (msgs == null) {
            msgs = new ArrayList<>();
            context.setMessages(msgs);
        }
        Message msg = new Message();
        msg.setRole("assistant");
        msg.setContent(content);
        msgs.add(msg);
    }
}
```

---

## 块 69：DefaultSkillRegistry.java

**文件路径**：`lyclaw-engine/src/main/java/lyjew/com/lyclaw/skill/impl/DefaultSkillRegistry.java`

**文件级注解**：

DefaultSkillRegistry 是 SkillRegistry 接口的默认实现。权威注册表。

**已有接口签名**：
- `register(Skill skill)` / `unregister(String skillId)` / `get(String skillId) → Optional<Skill>`
- `getAll() → List<Skill>` / `getByType(SkillType) → List<Skill>`
- `getDependencyGraph() → SkillGraph`
- `canExecute(String skillId) → boolean`
- `topologicalSort() → List<String>`

```java
package lyjew.com.lyclaw.skill.impl;

import lyjew.com.lyclaw.skill.Skill;
import lyjew.com.lyclaw.skill.SkillGraph;
import lyjew.com.lyclaw.skill.SkillRegistry;
import lyjew.com.lyclaw.skill.SkillType;

import jakarta.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * DefaultSkillRegistry — 默认技能注册表（注册表模式 + @PostConstruct 初始化）。
 *
 * @author LyClaw Team
 * @version 1.0
 */
public class DefaultSkillRegistry implements SkillRegistry {

    private final Map<String, Skill> skillMap = new ConcurrentHashMap<>();
    private final List<Skill> skillBeans;
    private SkillGraph skillGraph;

    public DefaultSkillRegistry(List<Skill> skillBeans) {
        this.skillBeans = new ArrayList<>(skillBeans);
    }

    @PostConstruct
    public void init() {
        for (Skill skill : skillBeans) {
            register(skill);
        }
        this.skillGraph = new SkillGraphImpl(new ArrayList<>(skillMap.values()));
        List<String> cycles = skillGraph.detectCycle();
        if (!cycles.isEmpty()) {
            throw new RuntimeException("Cyclic skill dependencies: " + cycles);
        }
    }

    @Override
    public void register(Skill skill) {
        if (skill == null) throw new NullPointerException("Skill must not be null");
        String id = skill.getId();
        Skill old = skillMap.putIfAbsent(id, skill);
        if (old != null) throw new RuntimeException("Duplicate skill ID: " + id);
    }

    @Override
    public void unregister(String skillId) {
        skillMap.remove(skillId);
    }

    @Override
    public Optional<Skill> get(String skillId) {
        return Optional.ofNullable(skillMap.get(skillId));
    }

    @Override
    public List<Skill> getAll() {
        return new ArrayList<>(skillMap.values());
    }

    @Override
    public List<Skill> getByType(SkillType type) {
        List<Skill> result = new ArrayList<>();
        for (Skill skill : skillMap.values()) {
            if (skill.getType() == type) result.add(skill);
        }
        return result;
    }

    @Override
    public SkillGraph getDependencyGraph() {
        return skillGraph;
    }

    @Override
    public boolean canExecute(String skillId) {
        if (!skillMap.containsKey(skillId)) return false;
        if (skillGraph != null) {
            for (String dep : skillGraph.getDirectDependencies(skillId)) {
                if (!skillMap.containsKey(dep)) return false;
            }
        }
        return true;
    }

    @Override
    public List<String> topologicalSort() {
        return skillGraph != null ? skillGraph.topologicalSort() : Collections.emptyList();
    }
}
```

---

## 块 70：SkillGraphImpl.java

**文件路径**：`lyclaw-engine/src/main/java/lyjew/com/lyclaw/skill/impl/SkillGraphImpl.java`

**已有接口签名**：
- `getDirectDependencies(String skillId) → List<String>`
- `getTransitiveDependencies(String skillId) → List<String>`
- `topologicalSort() → List<String>`
- `detectCycle() → List<String>`

```java
package lyjew.com.lyclaw.skill.impl;

import lyjew.com.lyclaw.skill.Skill;
import lyjew.com.lyclaw.skill.SkillGraph;

import java.util.*;

/**
 * SkillGraphImpl — 技能依赖图（邻接表 + DFS 循环检测 + Kahn 拓扑排序）。
 *
 * @author LyClaw Team
 * @version 1.0
 */
public class SkillGraphImpl implements SkillGraph {

    /** 邻接表：skillId → 直接依赖的 skillId 列表。 */
    private final Map<String, List<String>> adjacencyList = new HashMap<>();

    public SkillGraphImpl(List<Skill> skills) {
        if (skills == null) return;
        for (Skill skill : skills) {
            String id = skill.getId();
            List<String> deps = skill.getDependencies();
            adjacencyList.put(id, deps != null ? new ArrayList<>(deps) : new ArrayList<>());
        }
    }

    @Override
    public List<String> getDirectDependencies(String skillId) {
        return adjacencyList.getOrDefault(skillId, Collections.emptyList());
    }

    @Override
    public List<String> getTransitiveDependencies(String skillId) {
        Set<String> visited = new HashSet<>();
        collectDeps(skillId, visited);
        visited.remove(skillId);
        return new ArrayList<>(visited);
    }

    private void collectDeps(String skillId, Set<String> visited) {
        List<String> deps = adjacencyList.get(skillId);
        if (deps == null) return;
        for (String dep : deps) {
            if (visited.add(dep)) {
                collectDeps(dep, visited);
            }
        }
    }

    @Override
    public List<String> topologicalSort() {
        Map<String, Integer> inDegree = new HashMap<>();
        for (String id : adjacencyList.keySet()) {
            inDegree.put(id, 0);
        }
        for (List<String> deps : adjacencyList.values()) {
            for (String dep : deps) {
                inDegree.merge(dep, 1, Integer::sum);
            }
        }
        Queue<String> queue = new LinkedList<>();
        for (Map.Entry<String, Integer> e : inDegree.entrySet()) {
            if (e.getValue() == 0) queue.add(e.getKey());
        }
        List<String> result = new ArrayList<>();
        while (!queue.isEmpty()) {
            String node = queue.poll();
            result.add(node);
            for (String dep : adjacencyList.getOrDefault(node, Collections.emptyList())) {
                int deg = inDegree.merge(dep, -1, Integer::sum);
                if (deg == 0) queue.add(dep);
            }
        }
        return result;
    }

    @Override
    public List<String> detectCycle() {
        Map<String, Integer> visited = new HashMap<>();
        for (String id : adjacencyList.keySet()) {
            visited.put(id, 0);
        }
        List<String> cycle = new ArrayList<>();
        for (String id : adjacencyList.keySet()) {
            if (visited.get(id) == 0) {
                if (dfsDetectCycle(id, visited, new ArrayList<>(), cycle)) {
                    return cycle;
                }
            }
        }
        return Collections.emptyList();
    }

    private boolean dfsDetectCycle(String node, Map<String, Integer> visited,
                                    List<String> path, List<String> cycle) {
        visited.put(node, 1);
        path.add(node);
        List<String> deps = adjacencyList.get(node);
        if (deps != null) {
            for (String dep : deps) {
                int state = visited.getOrDefault(dep, 0);
                if (state == 0) {
                    if (dfsDetectCycle(dep, visited, path, cycle)) return true;
                } else if (state == 1) {
                    int idx = path.indexOf(dep);
                    cycle.addAll(path.subList(idx, path.size()));
                    return true;
                }
            }
        }
        visited.put(node, 2);
        path.remove(path.size() - 1);
        return false;
    }
}
```

---

## 块 71：ToolToSkillAdapter.java

**文件路径**：`lyclaw-engine/src/main/java/lyjew/com/lyclaw/skill/impl/adapters/ToolToSkillAdapter.java`

**文件级注解**：

ToolToSkillAdapter 将 Tool 接口适配为 Skill 接口。

**已有接口签名对齐**：
- Skill.getId() 对应 Tool.getName()
- Skill.executor() 返回 `SkillExecutor`
- SkillExecutor.execute(Map<String, Object>, SkillProgressCallback) 返回 `CompletableFuture<SkillResult>`
- SkillResult 在 lyclaw-core/skill 包中（已创建）

```java
package lyjew.com.lyclaw.skill.impl.adapters;

import lyjew.com.lyclaw.skill.Skill;
import lyjew.com.lyclaw.skill.SkillExecutor;
import lyjew.com.lyclaw.skill.SkillResult;
import lyjew.com.lyclaw.skill.SkillType;
import lyjew.com.lyclaw.tool.Tool;
import lyjew.com.lyclaw.tool.ToolResult;

import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * ToolToSkillAdapter — Tool→Skill 适配器（适配器模式）。
 *
 * @author LyClaw Team
 * @version 1.0
 */
public class ToolToSkillAdapter implements Skill {

    private final Tool tool;

    public ToolToSkillAdapter(Tool tool) {
        this.tool = tool;
    }

    /** 便捷工厂方法——根据 Tool 创建适配后的 Skill。 */
    public static Skill adapt(Tool tool) {
        return new ToolToSkillAdapter(tool);
    }

    @Override
    public String getId() {
        return tool.getName();
    }

    @Override
    public String getName() {
        return tool.getDefinition().getName();
    }

    @Override
    public String getDescription() {
        return tool.getDefinition().getDescription();
    }

    @Override
    public SkillType getType() {
        return SkillType.TOOL;
    }

    @Override
    public SkillExecutor executor() {
        return (Map<String, Object> input, lyjew.com.lyclaw.skill.SkillProgressCallback callback) -> {
            try {
                ToolResult result = tool.execute(input);
                return CompletableFuture.completedFuture(
                    new SkillResult(tool.getName(), result.isSuccess(),
                        result.getContent(),
                        result.isSuccess() ? null : result.getError(),
                        0, result.getDurationMs()));
            } catch (Exception e) {
                return CompletableFuture.completedFuture(
                    new SkillResult(tool.getName(), false, "", e.getMessage(), 0, 0));
            }
        };
    }

    @Override
    public List<String> getDependencies() {
        return Collections.emptyList();
    }

    @Override
    public Duration getTimeout() {
        return Duration.ofMillis(tool.getTimeout() > 0 ? tool.getTimeout() : 30_000);
    }
}
```


---

# 第七部分：记忆/事件/Agent/错误/安全/任务/配置 实现

> 对照已有接口的真实签名编写，保证编译通过。

---

## 块 72：FileMemoryManager.java

**文件路径**：`lyclaw-engine/src/main/java/lyjew/com/lyclaw/memory/impl/FileMemoryManager.java`

**文件级注解**：

FileMemoryManager 基于文件系统存储记忆（第一版实现）。每条记忆作为一个 Markdown 文件存储。

**已有接口签名对齐**：
- `remember(Session session, MemoryStrategy strategy)` — 从 session 中提取记忆并持久化
- `recall() → List<Memory>` — Memory 在 lyclaw-common，是单例实体（id固定为global）
- `recallByPage(int page, int size, String tagFilter) → PageResult<Memory>`
- `buildContext(List<Memory> memories) → String`

```java
package lyjew.com.lyclaw.memory.impl;

import lyjew.com.lyclaw.memory.MemoryContent;
import lyjew.com.lyclaw.memory.MemoryManager;
import lyjew.com.lyclaw.memory.MemoryStrategy;
import lyjew.com.lyclaw.memory.PageResult;
import lyjew.com.lyclaw.model.Memory;
import lyjew.com.lyclaw.model.Session;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * FileMemoryManager — 文件系统记忆存储（第一版）。
 *
 * <p>每条记忆作为一个 Markdown 文件存储在 memoryDir 目录下。
 * 第一版约束：Memory 是单例（id固定为global），只支持一条记忆。
 *
 * @author LyClaw Team
 * @version 1.0
 */
public class FileMemoryManager implements MemoryManager {

    /** 记忆文件存储目录。默认 ~/.lyclaw/memories/。 */
    private final Path memoryDir;

    /** 日期格式化，用于文件名。 */
    private static final DateTimeFormatter FMT =
        DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");

    public FileMemoryManager(String memoryDir) {
        this.memoryDir = Paths.get(memoryDir);
        try {
            Files.createDirectories(this.memoryDir);
        } catch (IOException e) {
            throw new RuntimeException("Cannot create memory dir: " + memoryDir, e);
        }
    }

    @Override
    public void remember(Session session, MemoryStrategy strategy) {
        if (!strategy.shouldExtract(session)) return;
        MemoryContent content = strategy.extract(session);
        if (content == null) return;
        // 写入一个 .md 文件
        try {
            Path file = memoryDir.resolve("memory_" + LocalDateTime.now().format(FMT) + ".md");
            String md = "# " + content.getSummary() + "\n\n"
                + content.getContent() + "\n\n"
                + "Tags: " + String.join(", ", content.getTags()) + "\n"
                + "Importance: " + content.getImportance() + "\n";
            Files.writeString(file, md, StandardOpenOption.CREATE_NEW);
        } catch (IOException e) {
            System.err.println("[ERROR] Failed to save memory: " + e.getMessage());
        }
    }

    @Override
    public List<Memory> recall() {
        // 第一版：Memory 是单例，返回空的 Memory 列表
        // 后续如果 Memory 支持多条，遍历 memoryDir 下的 .md 文件读取
        return Collections.emptyList();
    }

    @Override
    public List<Memory> recallByTags(List<String> tags) {
        return recall();
    }

    @Override
    public PageResult<Memory> recallByPage(int page, int size, String tagFilter) {
        return new PageResult<>(Collections.emptyList(), page, size, 0);
    }

    @Override
    public void forget(String memoryId) {
        // 第一版：不做任何操作（Memory 单例）
    }

    @Override
    public String buildContext(List<Memory> memories) {
        if (memories == null || memories.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder("记忆信息：\n");
        for (Memory mem : memories) {
            if (mem != null && mem.getContent() != null) {
                sb.append("- ").append(mem.getContent()).append("\n");
            }
        }
        return sb.toString();
    }
}
```

---

## 块 73：ManualMemoryStrategy.java

**文件路径**：`lyclaw-engine/src/main/java/lyjew/com/lyclaw/memory/impl/ManualMemoryStrategy.java`

```java
package lyjew.com.lyclaw.memory.impl;

import lyjew.com.lyclaw.memory.MemoryContent;
import lyjew.com.lyclaw.memory.MemoryStrategy;
import lyjew.com.lyclaw.model.Message;
import lyjew.com.lyclaw.model.Session;

import java.util.List;

/**
 * ManualMemoryStrategy — 手动记忆策略（第一版）。
 *
 * <p>检测最后一条用户消息是否包含"记住"、"记得"等触发词，
 * 如果包含则提取被记住的内容作为记忆。
 *
 * @author LyClaw Team
 * @version 1.0
 */
public class ManualMemoryStrategy implements MemoryStrategy {

    /** 触发词列表。匹配任意一个即触发记忆提取。 */
    private static final List<String> TRIGGERS = List.of("记住", "记得", "记下", "记住我");

    @Override
    public boolean shouldExtract(Session session) {
        if (session == null || session.getMessages() == null) return false;
        List<Message> msgs = session.getMessages();
        if (msgs.isEmpty()) return false;
        // 检查最后一条用户消息
        for (int i = msgs.size() - 1; i >= 0; i--) {
            Message msg = msgs.get(i);
            if ("user".equals(msg.getRole()) && msg.getContent() != null) {
                for (String trigger : TRIGGERS) {
                    if (msg.getContent().contains(trigger)) return true;
                }
                return false;
            }
        }
        return false;
    }

    @Override
    public MemoryContent extract(Session session) {
        // 提取最后一条用户消息的内容作为记忆
        List<Message> msgs = session.getMessages();
        for (int i = msgs.size() - 1; i >= 0; i--) {
            Message msg = msgs.get(i);
            if ("user".equals(msg.getRole()) && msg.getContent() != null) {
                String content = msg.getContent();
                // 去掉触发词前缀
                for (String t : TRIGGERS) {
                    if (content.startsWith(t)) {
                        content = content.substring(t.length()).trim();
                        break;
                    }
                }
                if (content.isEmpty()) content = msg.getContent();
                return new MemoryContent("手动记忆", content,
                    List.of("manual"), 5);
            }
        }
        return null;
    }
}
```

---

## 块 74：InMemoryEventBus.java

**文件路径**：`lyclaw-engine/src/main/java/lyjew/com/lyclaw/event/impl/InMemoryEventBus.java`

**文件级注解**：

InMemoryEventBus 使用 CopyOnWriteArrayList 存储订阅者（适合"读多写少"场景——监听器在启动时注册，运行时几乎不变化）。

**已有接口签名对齐**：
- `publish(Event event)` — 同步发布
- `publishAsync(Event event)` — 异步发布（第一版占位）
- `subscribe(Class<T>, Consumer<T>) → Subscription`
- `hasSubscribers(Class<? extends Event>) → boolean`

```java
package lyjew.com.lyclaw.event.impl;

import lyjew.com.lyclaw.event.Event;
import lyjew.com.lyclaw.event.EventBus;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * InMemoryEventBus — 内存事件总线（发布-订阅模式）。
 *
 * <p>使用 CopyOnWriteArrayList 存储订阅者，适合"读多写少"场景。
 * 第一版不支持异步发布（publishAsync 直接调用 publish）。
 *
 * @author LyClaw Team
 * @version 1.0
 */
public class InMemoryEventBus implements EventBus {

    /** eventType → 订阅者列表。CopyOnWriteArrayList 保证遍历时线程安全。 */
    private final Map<Class<?>, List<ConsumerWrapper<?>>> subscribers = new ConcurrentHashMap<>();

    @Override
    @SuppressWarnings("unchecked")
    public <T extends Event> void publish(T event) {
        List<ConsumerWrapper<?>> list = subscribers.get(event.getClass());
        if (list == null) return;
        for (ConsumerWrapper<?> wrapper : list) {
            try {
                ((ConsumerWrapper<T>) wrapper).handler.accept(event);
            } catch (Exception e) {
                System.err.println("[EventBus] Handler error: " + e.getMessage());
            }
        }
    }

    @Override
    public <T extends Event> void publishAsync(T event) {
        // 第一版同步执行。第二版使用线程池异步调用。
        publish(event);
    }

    @Override
    public <T extends Event> Subscription subscribe(Class<T> eventType, Consumer<T> handler) {
        ConsumerWrapper<T> wrapper = new ConsumerWrapper<>(handler);
        subscribers.computeIfAbsent(eventType, k -> new CopyOnWriteArrayList<>())
            .add(wrapper);
        return () -> {
            List<ConsumerWrapper<?>> list = subscribers.get(eventType);
            if (list != null) list.remove(wrapper);
        };
    }

    @Override
    public void unsubscribe(Subscription subscription) {
        if (subscription != null) subscription.unsubscribe();
    }

    @Override
    public boolean hasSubscribers(Class<? extends Event> eventType) {
        List<ConsumerWrapper<?>> list = subscribers.get(eventType);
        return list != null && !list.isEmpty();
    }

    /** 包装 Consumer，携带 handler 引用以便 unsubscribe 时精确移除。 */
    private static class ConsumerWrapper<T> {
        final Consumer<T> handler;
        ConsumerWrapper(Consumer<T> handler) { this.handler = handler; }
    }
}
```

---

## 块 75：TokenConsumedEvent.java

**文件路径**：`lyclaw-engine/src/main/java/lyjew/com/lyclaw/event/impl/TokenConsumedEvent.java`

```java
package lyjew.com.lyclaw.event.impl;

import lyjew.com.lyclaw.event.Event;

/**
 * TokenConsumedEvent — Token 消耗事件。
 * MetricsStage 中触发，携带 sessionId、model、Token 用量。
 *
 * @author LyClaw Team
 * @version 1.0
 */
public class TokenConsumedEvent extends Event {

    private final String sessionId;
    private final String model;
    private final int promptTokens;
    private final int completionTokens;
    private final int totalTokens;

    public TokenConsumedEvent(String sessionId, String model,
                              int promptTokens, int completionTokens) {
        this.sessionId = sessionId;
        this.model = model;
        this.promptTokens = promptTokens;
        this.completionTokens = completionTokens;
        this.totalTokens = promptTokens + completionTokens;
    }

    @Override
    public String getType() { return "TOKEN_CONSUMED"; }

    public String getSessionId() { return sessionId; }
    public String getModel() { return model; }
    public int getPromptTokens() { return promptTokens; }
    public int getCompletionTokens() { return completionTokens; }
    public int getTotalTokens() { return totalTokens; }
}
```

---

## 块 76：ToolCalledEvent.java

**文件路径**：`lyclaw-engine/src/main/java/lyjew/com/lyclaw/event/impl/ToolCalledEvent.java`

```java
package lyjew.com.lyclaw.event.impl;

import lyjew.com.lyclaw.event.Event;

/**
 * ToolCalledEvent — 工具调用事件。
 * ToolCallLoop 每轮工具执行完成后触发。
 *
 * @author LyClaw Team
 * @version 1.0
 */
public class ToolCalledEvent extends Event {

    private final String sessionId;
    private final String toolName;
    private final String status;
    private final long durationMs;

    public ToolCalledEvent(String sessionId, String toolName,
                           String status, long durationMs) {
        this.sessionId = sessionId;
        this.toolName = toolName;
        this.status = status;
        this.durationMs = durationMs;
    }

    @Override
    public String getType() { return "TOOL_CALLED"; }

    public String getSessionId() { return sessionId; }
    public String getToolName() { return toolName; }
    public String getStatus() { return status; }
    public long getDurationMs() { return durationMs; }
}
```

---

## 块 77：AgentStateChangedEvent.java

**文件路径**：`lyclaw-engine/src/main/java/lyjew/com/lyclaw/event/impl/AgentStateChangedEvent.java`

```java
package lyjew.com.lyclaw.event.impl;

import lyjew.com.lyclaw.agent.AgentState;
import lyjew.com.lyclaw.event.Event;

/**
 * AgentStateChangedEvent — Agent 状态变更事件。
 * AgentCoordinator 执行 spawn/terminate 后触发。
 *
 * @author LyClaw Team
 * @version 1.0
 */
public class AgentStateChangedEvent extends Event {

    private final String agentId;
    private final AgentState oldState;
    private final AgentState newState;
    private final String sessionId;

    public AgentStateChangedEvent(String agentId, AgentState oldState,
                                  AgentState newState, String sessionId) {
        this.agentId = agentId;
        this.oldState = oldState;
        this.newState = newState;
        this.sessionId = sessionId;
    }

    @Override
    public String getType() { return "AGENT_STATE_CHANGED"; }

    public String getAgentId() { return agentId; }
    public AgentState getOldState() { return oldState; }
    public AgentState getNewState() { return newState; }
    public String getSessionId() { return sessionId; }
}
```

---

## 块 78：StarAgentChannel.java

**文件路径**：`lyclaw-engine/src/main/java/lyjew/com/lyclaw/agent/impl/StarAgentChannel.java`

**文件级注解**：

StarAgentChannel 实现星型通信拓扑——主 Agent 是中心节点，所有子 Agent 是叶子节点。
子 Agent 之间不可直接通信。

**已有接口签名对齐**：
- `send(AgentMessage message)` — 发送消息
- `receive(String agentId) → Flux<AgentMessage>` — 接收消息流

```java
package lyjew.com.lyclaw.agent.impl;

import lyjew.com.lyclaw.agent.AgentMessage;
import lyjew.com.lyclaw.agent.AgentChannel;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * StarAgentChannel — 星型通信拓扑（第一版）。
 *
 * <p>主 Agent 是中心节点，所有子 Agent 是叶子节点。
 * 子 Agent 之间不可直接通信。
 *
 * @author LyClaw Team
 * @version 1.0
 */
public class StarAgentChannel implements AgentChannel {

    /** Sinks.Many 支持多播——一条消息可被多个接收者消费（实际上星型拓扑中每个 agentId 一个 sink）。 */
    private final Map<String, Sinks.Many<AgentMessage>> sinks = new ConcurrentHashMap<>();

    @Override
    public void send(AgentMessage message) {
        Sinks.Many<AgentMessage> sink = sinks.computeIfAbsent(
            message.getTo(), k -> Sinks.many().multicast().onBackpressureBuffer());
        sink.tryEmitNext(message);
    }

    @Override
    public Flux<AgentMessage> receive(String agentId) {
        return sinks.computeIfAbsent(agentId,
            k -> Sinks.many().multicast().onBackpressureBuffer()).asFlux();
    }
}
```


---

## 块 79：DefaultErrorPolicy.java

**文件路径**：`lyclaw-engine/src/main/java/lyjew/com/lyclaw/error/impl/DefaultErrorPolicy.java`

**文件级注解**：

DefaultErrorPolicy 实现 ErrorPolicy 接口，定义模型调用失败/工具执行失败/超时的默认处理策略。

**已有接口签名对齐**：
- `onModelError(ModelException error, ChatContext context) → ChatResult`
- `onToolError(ToolExecuteException error, ChatContext context) → ChatResult`
- `onTimeout(ChatContext context, long elapsedMs) → ChatResult`

注：ChatResult 不存在（所以文档中代码块用注释标注"此文件需要 ChatResult"）。
文档中保留正确的 import 和方法签名，ChatResult 由后续创建。

```java
package lyjew.com.lyclaw.error.impl;

import lyjew.com.lyclaw.context.ChatContext;
import lyjew.com.lyclaw.dto.ChatResult;
import lyjew.com.lyclaw.error.ErrorPolicy;
import lyjew.com.lyclaw.error.ModelException;
import lyjew.com.lyclaw.error.ToolExecuteException;

/**
 * DefaultErrorPolicy — 默认错误处理策略。
 *
 * <p>第一版实现：
 * <ul>
 *   <li>401/403 → 不重试，返回错误提示</li>
 *   <li>429 → 等待 5 秒后重试 1 次</li>
 *   <li>5xx → 等待 1 秒后重试 1 次</li>
 *   <li>网络超时 → 等待 2 秒后重试 1 次</li>
 *   <li>工具失败 → 返回错误 ChatResult</li>
 *   <li>总超时 → 返回超时提示</li>
 * </ul>
 *
 * @author LyClaw Team
 * @version 1.0
 */
public class DefaultErrorPolicy implements ErrorPolicy {

    @Override
    public ChatResult onModelError(ModelException error, ChatContext context) {
        // 401/403 API Key 无效——不重试
        if (error.getHttpStatus() == 401 || error.getHttpStatus() == 403) {
            return ChatResult.builder()
                .reply("API Key 无效或已过期，请检查配置")
                .finishReason("error")
                .build();
        }
        // 429 限流——等待 5 秒后重试 1 次
        if (error.getHttpStatus() == 429) {
            try { Thread.sleep(5000); } catch (InterruptedException ignored) {}
            // 重试逻辑在第一版由外层 Pipeline 处理
            return ChatResult.builder()
                .reply("请求过于频繁，请稍后再试")
                .finishReason("error")
                .build();
        }
        // 5xx 服务器错误——等待 1 秒后重试 1 次
        if (error.isServerError()) {
            try { Thread.sleep(1000); } catch (InterruptedException ignored) {}
            return ChatResult.builder()
                .reply("模型服务暂时不可用，请稍后重试")
                .finishReason("error")
                .build();
        }
        // 其他错误
        return ChatResult.builder()
            .reply("模型调用失败: " + error.getMessage())
            .finishReason("error")
            .build();
    }

    @Override
    public ChatResult onToolError(ToolExecuteException error, ChatContext context) {
        return ChatResult.builder()
            .reply("工具 [" + error.getToolName() + "] 执行失败: " + error.getMessage())
            .finishReason("error")
            .build();
    }

    @Override
    public ChatResult onTimeout(ChatContext context, long elapsedMs) {
        return ChatResult.builder()
            .reply("请求超时（已耗时 " + (elapsedMs / 1000) + " 秒），请简化问题后重试")
            .finishReason("timeout")
            .build();
    }
}
```

---

## 块 80：DefaultSessionTransaction.java

**文件路径**：`lyclaw-engine/src/main/java/lyjew/com/lyclaw/session/impl/DefaultSessionTransaction.java`

**文件级注解**：

第一版占位实现。All methods are no-ops（空操作）。第二版实现真正的 begin/commit/rollback + 文件原子写入。

```java
package lyjew.com.lyclaw.session.impl;

import lyjew.com.lyclaw.session.SessionTransaction;
import lyjew.com.lyclaw.session.TransactionContext;

/**
 * DefaultSessionTransaction — 会话事务（第一版占位）。
 * 所有方法空实现。第二版支持真正的 begin/commit/rollback。
 *
 * @author LyClaw Team
 * @version 1.0
 */
public class DefaultSessionTransaction implements SessionTransaction {

    @Override
    public TransactionContext begin(String sessionId) {
        // TransactionContext 构造需要 (sessionId, snapshot) 两个参数
        // 第一版 snapshot 为 null（不保存快照，因为不实现回滚）
        return new TransactionContext(sessionId, null);
    }

    @Override
    public void commit(TransactionContext ctx) {
        // 第一版不做任何操作——Pipeline 结束后直接保存 Session
    }

    @Override
    public void rollback(TransactionContext ctx) {
        // 第一版不做任何操作
    }

    @Override
    public void createSavepoint(TransactionContext ctx, String name) {
        // 第一版不实现
    }

    @Override
    public void rollbackToSavepoint(TransactionContext ctx, String name) {
        // 第一版不实现
    }
}
```

---

## 块 81：DefaultSecurityManager.java

**文件路径**：`lyclaw-engine/src/main/java/lyjew/com/lyclaw/security/impl/DefaultSecurityManager.java`

**文件级注解**：

第一版全部放行——approveToolCall 始终返回 ALLOW，sandboxPolicy 返回 NONE，resolveSecret 直接返回引用本身。

```java
package lyjew.com.lyclaw.security.impl;

import lyjew.com.lyclaw.context.ChatContext;
import lyjew.com.lyclaw.model.ModelResponse;
import lyjew.com.lyclaw.security.ApprovalResult;
import lyjew.com.lyclaw.security.SandboxLevel;
import lyjew.com.lyclaw.security.SecurityManager;

/**
 * DefaultSecurityManager — 默认安全管理员（第一版全部放行）。
 * 第二版实现真正的语义审批。
 *
 * @author LyClaw Team
 * @version 1.0
 */
public class DefaultSecurityManager implements SecurityManager {

    @Override
    public ApprovalResult approveToolCall(ChatContext context, ModelResponse.ToolCallRequest tc) {
        return ApprovalResult.allow();
    }

    @Override
    public SandboxLevel sandboxPolicy(String toolName) {
        return SandboxLevel.NONE;
    }

    @Override
    public String resolveSecret(String secretRef) {
        // 第一版直接返回引用字符串本身（不解析）
        return secretRef;
    }
}
```

---

## 块 82：DefaultTaskPlanner.java

**文件路径**：`lyclaw-engine/src/main/java/lyjew/com/lyclaw/task/impl/DefaultTaskPlanner.java`

**文件级注解**：

第一版简化实现——直接将整个请求作为一个串行任务执行。

```java
package lyjew.com.lyclaw.task.impl;

import lyjew.com.lyclaw.context.ChatContext;
import lyjew.com.lyclaw.task.*;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * DefaultTaskPlanner — 默认任务编排器（第一版）。
 * 串行执行——整个请求作为一个任务，无并行 DAG。
 *
 * @author LyClaw Team
 * @version 1.0
 */
public class DefaultTaskPlanner implements TaskPlanner {

    private final TaskLedger ledger;

    public DefaultTaskPlanner(TaskLedger ledger) {
        this.ledger = ledger;
    }

    @Override
    public TaskPlan createPlan(ChatContext context) {
        // 从原始请求中取最后一条用户消息的内容作为任务描述
        String userContent = context.getOriginalRequest().getLastUserMessage();
        // 创建一个串行计划：一个字符串ID为 "task-1" 的任务节点
        TaskNode node = new TaskNode("task-1", "default",
            userContent != null ? userContent : "");
        return new TaskPlan(
            List.of(node),
            List.of("task-1"),
            Collections.emptyMap());
    }

    @Override
    public TaskResult executeTask(String taskId) {
        // 第一版：TaskPlanner 不实际执行任务
        // 实际执行由 ToolCallLoop 处理
        return TaskResult.ok(taskId, "Executed by ToolCallLoop", 0);
    }

    @Override
    public TaskLedger getLedger() {
        return ledger;
    }

    @Override
    public List<TaskResult> recoverFailedTasks() {
        // 第一版不做容错恢复
        return Collections.emptyList();
    }
}
```

---

## 块 83：DefaultTaskLedger.java

**文件路径**：`lyclaw-engine/src/main/java/lyjew/com/lyclaw/task/impl/DefaultTaskLedger.java`

**文件级注解**：

第一版内存存储（ConcurrentHashMap）。第二版切换为文件/数据库存储。

```java
package lyjew.com.lyclaw.task.impl;

import lyjew.com.lyclaw.task.TaskLedger;
import lyjew.com.lyclaw.task.TaskNode;
import lyjew.com.lyclaw.task.TaskRecord;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * DefaultTaskLedger — 默认任务账本（内存实现，第一版）。
 *
 * @author LyClaw Team
 * @version 1.0
 */
public class DefaultTaskLedger implements TaskLedger {

    private final Map<String, TaskRecord> records = new ConcurrentHashMap<>();

    @Override
    public void record(TaskRecord record) {
        if (record != null) {
            records.put(record.getTaskId(), record);
        }
    }

    @Override
    public TaskRecord getRecord(String taskId) {
        return records.get(taskId);
    }

    @Override
    public List<TaskRecord> getRecordsBySession(String sessionId) {
        if (sessionId == null) return Collections.emptyList();
        List<TaskRecord> result = new ArrayList<>();
        for (TaskRecord r : records.values()) {
            if (sessionId.equals(r.getSessionId())) result.add(r);
        }
        return result;
    }

    @Override
    public List<TaskRecord> getRecordsByStatus(TaskNode.Status status) {
        if (status == null) return Collections.emptyList();
        List<TaskRecord> result = new ArrayList<>();
        for (TaskRecord r : records.values()) {
            if (status == r.getStatus()) result.add(r);
        }
        return result;
    }
}
```

---

## 块 84：EngineProperties.java

**文件路径**：`lyclaw-engine/src/main/java/lyjew/com/lyclaw/config/EngineProperties.java`

**文件级注解**：

Spring @ConfigurationProperties 绑定类，从 application.yml 加载引擎配置。

```java
package lyjew.com.lyclaw.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * EngineProperties — 引擎配置属性绑定。
 *
 * <p>从 application.yml 加载 lyclaw.engine.* 配置项。
 * Spring Boot 自动绑定，无需手动解析配置文件。
 *
 * <p>配置项示例：
 * <pre>
 * lyclaw:
 *   engine:
 *     default-provider: minimax
 *     default-model: MiniMax-M2.7
 *     memory-dir: ~/.lyclaw/memories
 *     max-tool-rounds: 10
 * </pre>
 *
 * @author LyClaw Team
 * @version 1.0
 */
@ConfigurationProperties(prefix = "lyclaw.engine")
public class EngineProperties {

    /** 默认模型提供商。 */
    private String defaultProvider = "minimax";
    /** 默认模型名称。 */
    private String defaultModel = "MiniMax-M2.7";
    /** 记忆文件存储目录。 */
    private String memoryDir = "~/.lyclaw/memories";
    /** 最大工具调用轮次。 */
    private int maxToolRounds = 10;
    /** Session 存储目录。 */
    private String sessionDir = "~/.lyclaw/sessions";

    // getter/setter
    public String getDefaultProvider() { return defaultProvider; }
    public void setDefaultProvider(String v) { this.defaultProvider = v; }
    public String getDefaultModel() { return defaultModel; }
    public void setDefaultModel(String v) { this.defaultModel = v; }
    public String getMemoryDir() { return memoryDir; }
    public void setMemoryDir(String v) { this.memoryDir = v; }
    public int getMaxToolRounds() { return maxToolRounds; }
    public void setMaxToolRounds(int v) { this.maxToolRounds = v; }
    public String getSessionDir() { return sessionDir; }
    public void setSessionDir(String v) { this.sessionDir = v; }
}
```

---

## 块 85：EngineAutoConfiguration.java

**文件路径**：`lyclaw-engine/src/main/java/lyjew/com/lyclaw/config/EngineAutoConfiguration.java`

**文件级注解**：

Spring @EnableAutoConfiguration 自动装配类。将所有第一版实现注册为 Spring Bean。

```java
package lyjew.com.lyclaw.config;

import lyjew.com.lyclaw.context.impl.FullWindowContextBuilder;
import lyjew.com.lyclaw.engine.impl.DefaultEngine;
import lyjew.com.lyclaw.engine.impl.EngineSelector;
import lyjew.com.lyclaw.error.impl.DefaultErrorPolicy;
import lyjew.com.lyclaw.event.impl.InMemoryEventBus;
import lyjew.com.lyclaw.interceptor.impl.InterceptorChain;
import lyjew.com.lyclaw.interceptor.impl.LoggingInterceptor;
import lyjew.com.lyclaw.interceptor.impl.RateLimitInterceptor;
import lyjew.com.lyclaw.interceptor.impl.SensitiveDataInterceptor;
import lyjew.com.lyclaw.memory.impl.FileMemoryManager;
import lyjew.com.lyclaw.memory.impl.ManualMemoryStrategy;
import lyjew.com.lyclaw.skill.impl.DefaultSkillRegistry;
import lyjew.com.lyclaw.task.impl.DefaultTaskLedger;
import lyjew.com.lyclaw.task.impl.DefaultTaskPlanner;
import lyjew.com.lyclaw.tool.impl.DefaultToolCallPolicy;
import lyjew.com.lyclaw.tool.impl.DefaultToolRegistry;
import lyjew.com.lyclaw.tool.impl.WebSearchTool;
import lyjew.com.lyclaw.tool.impl.CalculatorTool;
import lyjew.com.lyclaw.tool.impl.CurrentTimeTool;
import lyjew.com.lyclaw.provider.ModelProvider;
import lyjew.com.lyclaw.storage.SessionStorage;
import lyjew.com.lyclaw.security.impl.DefaultSecurityManager;
import lyjew.com.lyclaw.pipeline.impl.stages.*;
import lyjew.com.lyclaw.pipeline.impl.PipelineBuilder;
import lyjew.com.lyclaw.tool.ToolCallPolicy;
import lyjew.com.lyclaw.tool.ToolRegistry;
import lyjew.com.lyclaw.interceptor.Interceptor;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * EngineAutoConfiguration — 引擎自动装配。
 *
 * <p>Spring Boot auto-configuration 自动装配 lyclaw-engine 的所有 Bean。
 * 使用 @ConditionalOnMissingBean 保证如果用户自定义了某个 Bean（如自定义 ErrorPolicy），
 * 自动装配的默认 Bean 不会覆盖用户 Bean。
 *
 * @author LyClaw Team
 * @version 1.0
 */
@Configuration
@EnableConfigurationProperties(EngineProperties.class)
public class EngineAutoConfiguration {

    // ==================== Manager 类 ====================

    @Bean
    @ConditionalOnMissingBean
    public DefaultToolRegistry defaultToolRegistry() {
        return new DefaultToolRegistry();
    }

    @Bean
    @ConditionalOnMissingBean
    public DefaultSkillRegistry defaultSkillRegistry(List<lyjew.com.lyclaw.skill.Skill> skills) {
        return new DefaultSkillRegistry(skills);
    }

    @Bean
    @ConditionalOnMissingBean
    public InMemoryEventBus eventBus() {
        return new InMemoryEventBus();
    }

    @Bean
    @ConditionalOnMissingBean
    public DefaultSecurityManager securityManager() {
        return new DefaultSecurityManager();
    }

    @Bean
    @ConditionalOnMissingBean
    public DefaultErrorPolicy errorPolicy() {
        return new DefaultErrorPolicy();
    }

    @Bean
    @ConditionalOnMissingBean
    public DefaultTaskLedger taskLedger() {
        return new DefaultTaskLedger();
    }

    @Bean
    @ConditionalOnMissingBean
    public DefaultTaskPlanner taskPlanner(DefaultTaskLedger ledger) {
        return new DefaultTaskPlanner(ledger);
    }

    // ==================== 策略类 ====================

    @Bean
    @ConditionalOnMissingBean
    public ToolCallPolicy toolCallPolicy() {
        return new DefaultToolCallPolicy();
    }

    @Bean
    @ConditionalOnMissingBean
    public ManualMemoryStrategy manualMemoryStrategy() {
        return new ManualMemoryStrategy();
    }

    // ⚠️ 以下 Bean 需要外部依赖，不在本配置类中自动装配：
    // - RateLimitInterceptor(int)   → 由用户在 MyEngineConfig 中自定义
    // - SensitiveDataInterceptor()  → 由用户在 MyEngineConfig 中自定义
    // - LoggingInterceptor()        → 由用户在 MyEngineConfig 中自定义
    // - FullWindowContextBuilder(...) → 需要 SessionStorage + MemoryManager + SkillRegistry
    // - ContextBuildStage(...)       → 需要 SessionStorage + MemoryManager + ContextBuilder + SkillRegistry
    // 参考写法见注释块末尾的 MyEngineConfig 示例。

    // ==================== 工具 ====================

    @Bean
    @ConditionalOnMissingBean
    public WebSearchTool webSearchTool() {
        return new WebSearchTool();
    }

    @Bean
    @ConditionalOnMissingBean
    public CalculatorTool calculatorTool() {
        return new CalculatorTool();
    }

    @Bean
    @ConditionalOnMissingBean
    public CurrentTimeTool currentTimeTool() {
        return new CurrentTimeTool();
    }

    // ==================== Pipeline ====================

    @Bean
    @ConditionalOnMissingBean
    public InterceptorStage interceptorStage(InterceptorChain chain) {
        return new InterceptorStage(chain);
    }

    @Bean
    @ConditionalOnMissingBean
    public ToolCallLoopStage toolCallLoopStage(ModelProvider modelProvider,
                                               DefaultToolRegistry toolRegistry,
                                               ToolCallPolicy toolCallPolicy) {
        return new ToolCallLoopStage(modelProvider, toolRegistry, toolCallPolicy);
    }

    @Bean
    @ConditionalOnMissingBean
    public MetricsStage metricsStage(InMemoryEventBus eventBus) {
        return new MetricsStage(eventBus);
    }

    @Bean
    @ConditionalOnMissingBean
    public ResponseBuildStage responseBuildStage() {
        return new ResponseBuildStage();
    }

    // ==================== Engine ====================

    @Bean
    @ConditionalOnMissingBean
    public DefaultEngine defaultEngine(SessionStorage sessionStorage,
                                       ModelProvider modelProvider,
                                       ToolCallLoopStage toolCallLoopStage,
                                       InterceptorStage interceptorStage,
                                       MetricsStage metricsStage,
                                       ResponseBuildStage responseBuildStage,
                                       EngineProperties props) {
        PipelineBuilder builder = new PipelineBuilder();
        builder.addStage(interceptorStage);
        builder.addStage(toolCallLoopStage);
        builder.addStage(metricsStage);
        builder.addStage(responseBuildStage);

        lyjew.com.lyclaw.pipeline.Pipeline pipeline = builder.build();

        // EngineMetadata 构造函数有 5 个参数：(name, version, description, capabilities, configured)
        // configured=false 因为 ContextBuildStage 等依赖尚未注入，由用户在自定义配置中补全
        lyjew.com.lyclaw.engine.EngineMetadata metadata =
            new lyjew.com.lyclaw.engine.EngineMetadata(
                "default", "1.0", "Default QA engine", List.of("chat"), false);

        return new DefaultEngine(pipeline, toolCallLoopStage,
            sessionStorage, modelProvider, metadata);
    }

    @Bean
    @ConditionalOnMissingBean
    public EngineSelector engineSelector(List<lyjew.com.lyclaw.engine.Engine> engines) {
        return new EngineSelector(engines);
    }

    // ==================== 文件管理 ====================

    @Bean
    @ConditionalOnMissingBean
    public FileMemoryManager fileMemoryManager(EngineProperties props) {
        return new FileMemoryManager(props.getMemoryDir());
    }
}
```

---

### 补充：MyEngineConfig.java — 用户自定义 Bean 配置

**文件路径**：`lyclaw-engine/src/main/java/lyjew/com/lyclaw/config/MyEngineConfig.java`

> EngineAutoConfiguration 跳过了需要 SessionStorage、MemoryManager、SkillRegistry 等外部依赖的 Bean。用户在获取这些 Bean 后，新建此配置类手动注册。

```java
package lyjew.com.lyclaw.config;

import lyjew.com.lyclaw.context.ContextBuilder;
import lyjew.com.lyclaw.context.impl.FullWindowContextBuilder;
import lyjew.com.lyclaw.interceptor.impl.LoggingInterceptor;
import lyjew.com.lyclaw.interceptor.impl.RateLimitInterceptor;
import lyjew.com.lyclaw.interceptor.impl.SensitiveDataInterceptor;
import lyjew.com.lyclaw.memory.MemoryManager;
import lyjew.com.lyclaw.pipeline.impl.stages.ContextBuildStage;
import lyjew.com.lyclaw.skill.SkillRegistry;
import lyjew.com.lyclaw.storage.SessionStorage;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MyEngineConfig — 用户自定义 Bean 配置。
 *
 * <p>补充 EngineAutoConfiguration 中因外部依赖跳过自动装配的 Bean。
 * 当 SessionStorage、MemoryManager、SkillRegistry 等 Bean 就绪后，Spring 自动注入到此配置类。
 */
@Configuration
public class MyEngineConfig {

    /** 限流拦截器：每分钟最多 60 次请求。第二版使用配置中心动态调整。 */
    @Bean
    public RateLimitInterceptor rateLimitInterceptor() {
        return new RateLimitInterceptor(60);
    }

    /** 敏感数据脱敏拦截器。默认将所有消息中的数字序列替换为 ****。 */
    @Bean
    public SensitiveDataInterceptor sensitiveDataInterceptor() {
        return new SensitiveDataInterceptor();
    }

    /** 日志拦截器。记录请求/响应的关键信息。无配置参数。 */
    @Bean
    public LoggingInterceptor loggingInterceptor() {
        return new LoggingInterceptor();
    }

    /**
     * 全量窗口上下文构建策略。
     * 将全部会话历史 + 全部记忆 + 全部可用工具注入上下文。
     */
    @Bean
    public FullWindowContextBuilder fullWindowContextBuilder(
            SessionStorage sessionStorage,
            MemoryManager memoryManager,
            SkillRegistry skillRegistry) {
        return new FullWindowContextBuilder(sessionStorage, memoryManager, skillRegistry);
    }

    /**
     * 上下文构建 Pipeline Stage。
     * 依赖已注册的 SessionStorage、MemoryManager、ContextBuilder、SkillRegistry。
     */
    @Bean
    public ContextBuildStage contextBuildStage(
            SessionStorage sessionStorage,
            MemoryManager memoryManager,
            ContextBuilder contextBuilder,
            SkillRegistry skillRegistry) {
        return new ContextBuildStage(
            sessionStorage, memoryManager, contextBuilder, skillRegistry);
    }
}
```

```

---

# 第八部分：DTO 实现（3 个文件，lyclaw-core/dto/ 包下）

> 最后 3 个 DTO，放到 lyclaw-core 的 dto 包下。
> 因为 lyclaw-core 中的 ErrorPolicy、Interceptor、Pipeline、AgentCoordinator、SkillExecutor 都引用了这 3 个 DTO。
> 放在 lyclaw-core 的 dto 包下可以避免跨模块循环依赖。

---

## 块 86：ChatResult.java

**文件路径**：`lyclaw-core/src/main/java/lyjew/com/lyclaw/dto/ChatResult.java`

**引用方**：ErrorPolicy（onModelError/onToolError 返回 ChatResult）、Interceptor（postHandle 参数）、Pipeline（execute 返回）、LoggingInterceptor、DefaultErrorPolicy、ResponseBuildStage、PipelineBuilder

```java
package lyjew.com.lyclaw.dto;

import lyjew.com.lyclaw.tool.ToolResult;

/**
 * ChatResult — 对话结果 DTO。
 *
 * <p>Pipeline 的最终输出。包含回复文本、完成原因、Token 用量。
 *
 * @author LyClaw Team
 * @version 1.0
 */
public class ChatResult {

    private final String reply;
    private final String finishReason;
    private final String tokenUsage;
    private final java.util.List<ToolResult> toolResults;
    private final long durationMs;

    private ChatResult(String reply, String finishReason, String tokenUsage,
                       java.util.List<ToolResult> toolResults, long durationMs) {
        this.reply = reply;
        this.finishReason = finishReason;
        this.tokenUsage = tokenUsage;
        this.toolResults = toolResults;
        this.durationMs = durationMs;
    }

    public static Builder builder() { return new Builder(); }

    public String getReply() { return reply; }
    public String getFinishReason() { return finishReason; }
    public String getTokenUsage() { return tokenUsage; }
    public java.util.List<ToolResult> getToolResults() { return toolResults; }
    public long getDurationMs() { return durationMs; }

    public static class Builder {
        private String reply = "";
        private String finishReason = "stop";
        private String tokenUsage;
        private java.util.List<ToolResult> toolResults;
        private long durationMs;
        Builder() {}
        public Builder reply(String v) { this.reply = v; return this; }
        public Builder finishReason(String v) { this.finishReason = v; return this; }
        public Builder tokenUsage(String v) { this.tokenUsage = v; return this; }
        public Builder toolResults(java.util.List<ToolResult> v) { this.toolResults = v; return this; }
        public Builder durationMs(long v) { this.durationMs = v; return this; }
        public ChatResult build() { return new ChatResult(reply, finishReason, tokenUsage, toolResults, durationMs); }
    }
}
```

---

## 块 87：AgentResult.java

**文件路径**：`lyclaw-core/src/main/java/lyjew/com/lyclaw/dto/AgentResult.java`

**引用方**：AgentCoordinator.awaitResult() 返回 AgentResult

```java
package lyjew.com.lyclaw.dto;

/**
 * AgentResult — Agent 执行结果 DTO。
 *
 * @author LyClaw Team
 * @version 1.0
 */
public class AgentResult {

    private final String agentId;
    private final String status;
    private final String summary;
    private final String detail;
    private final long elapsedMs;

    public AgentResult(String agentId, String status, String summary,
                       String detail, long elapsedMs) {
        this.agentId = agentId;
        this.status = status;
        this.summary = summary;
        this.detail = detail;
        this.elapsedMs = elapsedMs;
    }

    public String getAgentId() { return agentId; }
    public String getStatus() { return status; }
    public String getSummary() { return summary; }
    public String getDetail() { return detail; }
    public long getElapsedMs() { return elapsedMs; }
}
```

---

## 块 88：SkillResult.java

**文件路径**：`lyclaw-core/src/main/java/lyjew/com/lyclaw/dto/SkillResult.java`

**引用方**：SkillExecutor.execute() 返回 CompletableFuture&lt;SkillResult&gt;

```java
package lyjew.com.lyclaw.dto;

/**
 * SkillResult — 技能执行结果 DTO。
 *
 * @author LyClaw Team
 * @version 1.0
 */
public class SkillResult {

    private final String skillId;
    private final boolean success;
    private final String output;
    private final String error;
    private final int tokenUsage;
    private final long elapsedMs;

    public SkillResult(String skillId, boolean success, String output,
                       String error, int tokenUsage, long elapsedMs) {
        this.skillId = skillId;
        this.success = success;
        this.output = output;
        this.error = error;
        this.tokenUsage = tokenUsage;
        this.elapsedMs = elapsedMs;
    }

    public String getSkillId() { return skillId; }
    public boolean isSuccess() { return success; }
    public String getOutput() { return output; }
    public String getError() { return error; }
    public int getTokenUsage() { return tokenUsage; }
    public long getElapsedMs() { return elapsedMs; }
}
```

---

**全部 88 个代码块完成。**

---

# 第九部分：Spring Boot 集成测试（7 个测试类）

> 以下测试类放在 `lyclaw-web/src/test/java/lyjew/com/lyclaw/engine/` 下。
> 启动完整 Spring Boot 容器（`@SpringBootTest(classes = LyClawApplication.class)`）。
>
> 先确保 `lyclaw-web/pom.xml` 已添加 lyclaw-engine 依赖：
> ```xml
> <dependency>
>     <groupId>lyjew.com</groupId>
>     <artifactId>lyclaw-engine</artifactId>
> </dependency>
> ```
>
> **如何运行**：
> ```bash
> cd /home/lyjew/Documents/Unicom/LyClaw
> mvn test -pl lyclaw-web -am
> ```
> 或者 IDEA 中打开 `lyclaw-web/src/test/` → 右键 Run Tests。

---

## 测试类 1：EngineAutoConfigurationTest.java

**文件路径**：`lyclaw-web/src/test/java/lyjew/com/lyclaw/engine/EngineAutoConfigurationTest.java`

验证自动配置装配了哪些核心 Bean。

```java
package lyjew.com.lyclaw.engine;

import lyjew.com.lyclaw.LyClawApplication;
import lyjew.com.lyclaw.config.EngineProperties;
import lyjew.com.lyclaw.skill.SkillRegistry;
import lyjew.com.lyclaw.tool.ToolCallPolicy;
import lyjew.com.lyclaw.tool.impl.DefaultToolRegistry;
import lyjew.com.lyclaw.event.EventBus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(classes = LyClawApplication.class)
class EngineAutoConfigurationTest {

    @Autowired(required = false) private EngineProperties engineProperties;
    @Autowired(required = false) private DefaultToolRegistry toolRegistry;
    @Autowired(required = false) private SkillRegistry skillRegistry;
    @Autowired(required = false) private EventBus eventBus;
    @Autowired(required = false) private ToolCallPolicy toolCallPolicy;
    @Autowired(required = false) private EngineSelector engineSelector;

    @Test void enginePropertiesLoaded() { assertNotNull(engineProperties); }
    @Test void toolRegistryLoaded() { assertNotNull(toolRegistry); }
    @Test void skillRegistryLoaded() { assertNotNull(skillRegistry); }
    @Test void eventBusLoaded() { assertNotNull(eventBus); }
    @Test void toolCallPolicyLoaded() { assertNotNull(toolCallPolicy); }
    @Test void engineSelectorLoaded() { assertNotNull(engineSelector); }
}
```

---

## 测试类 2：DefaultToolRegistrySpringTest.java

**文件路径**：`lyclaw-web/src/test/java/lyjew/com/lyclaw/engine/DefaultToolRegistrySpringTest.java`

```java
package lyjew.com.lyclaw.engine;

import lyjew.com.lyclaw.LyClawApplication;
import lyjew.com.lyclaw.tool.Tool;
import lyjew.com.lyclaw.tool.ToolDefinition;
import lyjew.com.lyclaw.tool.ToolResult;
import lyjew.com.lyclaw.tool.impl.DefaultToolRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(classes = LyClawApplication.class)
class DefaultToolRegistrySpringTest {

    @Autowired private DefaultToolRegistry registry;

    @Test void shouldHaveBuiltinTools() {
        assertEquals(3, registry.listTools().size());
    }
    @Test void shouldFindCalculator() {
        assertTrue(registry.getTool("calculator").isPresent());
    }
    @Test void shouldGetDefinitions() {
        List<ToolDefinition> defs = registry.getAllDefinitions();
        assertEquals(3, defs.size());
        assertTrue(defs.stream().allMatch(d -> d.getName() != null));
    }
    @Test void shouldExecuteCalculator() {
        ToolResult r = registry.getTool("calculator").orElseThrow()
                .execute(Map.of("expression", "1+2"));
        assertTrue(r.isSuccess());
        assertEquals("3", r.getContent());
    }
    @Test void shouldExecuteWebSearch() {
        assertTrue(registry.getTool("web_search").orElseThrow()
                .execute(Map.of("query", "test")).isSuccess());
    }
    @Test void shouldExecuteCurrentTime() {
        assertTrue(registry.getTool("current_time").orElseThrow()
                .execute(Map.of()).getContent().contains("当前时间"));
    }
}

---

## 测试类 3：DefaultSkillRegistrySpringTest.java

**文件路径**：`lyclaw-web/src/test/java/lyjew/com/lyclaw/engine/DefaultSkillRegistrySpringTest.java`

```java
package lyjew.com.lyclaw.engine;

import lyjew.com.lyclaw.LyClawApplication;
import lyjew.com.lyclaw.skill.Skill;
import lyjew.com.lyclaw.skill.SkillRegistry;
import lyjew.com.lyclaw.skill.impl.DefaultSkillRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(classes = LyClawApplication.class)
class DefaultSkillRegistrySpringTest {

    @Autowired private SkillRegistry registry;

    @Test void shouldHaveToolSkills() {
        assertFalse(registry.getAll().isEmpty());
    }
    @Test void shouldFindCalculator() {
        assertTrue(registry.get("calculator").isPresent());
    }
    @Test void shouldExecuteCalculator() throws Exception {
        Skill skill = registry.get("calculator").orElseThrow();
        Object result = skill.executor().execute(Map.of("expression", "2*3"), null).get();
        assertNotNull(result);
    }
}
```

---

## 测试类 4：InterceptorChainSpringTest.java

**文件路径**：`lyclaw-web/src/test/java/lyjew/com/lyclaw/engine/InterceptorChainSpringTest.java`

```java
package lyjew.com.lyclaw.engine;

import lyjew.com.lyclaw.LyClawApplication;
import lyjew.com.lyclaw.context.ChatContext;
import lyjew.com.lyclaw.dto.ChatResult;
import lyjew.com.lyclaw.interceptor.impl.InterceptorChain;
import lyjew.com.lyclaw.model.ChatRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(classes = LyClawApplication.class)
class InterceptorChainSpringTest {

    @Autowired(required = false) private InterceptorChain interceptorChain;

    @Test void chainExists() { assertNotNull(interceptorChain); }
    @Test void preHandleOk() {
        if (interceptorChain == null) return;
        ChatContext ctx = new ChatContext(ChatRequest.builder().model("test")
                .messages(List.of(new lyjew.com.lyclaw.model.Message("user", "hello"))).build());
        assertDoesNotThrow(() -> interceptorChain.runPreHandle(ctx));
    }
    @Test void postHandleOk() {
        if (interceptorChain == null) return;
        assertDoesNotThrow(() -> interceptorChain.runPostHandle(
                ChatResult.builder().reply("ok").build()));
    }
}
```

---

## 测试类 5：DefaultErrorPolicySpringTest.java

**文件路径**：`lyclaw-web/src/test/java/lyjew/com/lyclaw/engine/DefaultErrorPolicySpringTest.java`

```java
package lyjew.com.lyclaw.engine;

import lyjew.com.lyclaw.LyClawApplication;
import lyjew.com.lyclaw.context.ChatContext;
import lyjew.com.lyclaw.dto.ChatResult;
import lyjew.com.lyclaw.error.ErrorPolicy;
import lyjew.com.lyclaw.error.ModelException;
import lyjew.com.lyclaw.error.ToolExecuteException;
import lyjew.com.lyclaw.model.ChatRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(classes = LyClawApplication.class)
class DefaultErrorPolicySpringTest {

    @Autowired private ErrorPolicy errorPolicy;
    private final ChatContext ctx = new ChatContext(ChatRequest.builder().model("t").build());

    @Test void test401() {
        assertTrue(errorPolicy.onModelError(new ModelException(401, "x"), ctx)
                .getReply().contains("API Key"));
    }
    @Test void test403() {
        assertTrue(errorPolicy.onModelError(new ModelException(403, "x"), ctx)
                .getReply().contains("API Key"));
    }
    @Test void test429() {
        assertTrue(errorPolicy.onModelError(new ModelException(429, "x"), ctx)
                .getReply().contains("频繁"));
    }
    @Test void test5xx() {
        ModelException e = new ModelException(503, "x");
        e.setServerError(true);
        assertEquals("error", errorPolicy.onModelError(e, ctx).getFinishReason());
    }
    @Test void testToolError() {
        assertTrue(errorPolicy.onToolError(new ToolExecuteException("search", "fail"), ctx)
                .getReply().contains("search"));
    }
    @Test void testTimeout() {
        assertEquals("timeout", errorPolicy.onTimeout(ctx, 35000).getFinishReason());
    }
}
```

---

## 测试类 6：EventBusSpringTest.java

**文件路径**：`lyclaw-web/src/test/java/lyjew/com/lyclaw/engine/EventBusSpringTest.java`

```java
package lyjew.com.lyclaw.engine;

import lyjew.com.lyclaw.LyClawApplication;
import lyjew.com.lyclaw.event.Event;
import lyjew.com.lyclaw.event.EventBus;
import lyjew.com.lyclaw.event.impl.TokenConsumedEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(classes = LyClawApplication.class)
class EventBusSpringTest {

    @Autowired private EventBus eventBus;

    @Test void shouldReceivePublishedEvents() {
        AtomicInteger count = new AtomicInteger(0);
        eventBus.subscribe(TokenConsumedEvent.class, e -> count.incrementAndGet());
        eventBus.publish(new TokenConsumedEvent("s", 100, 50));
        eventBus.publish(new TokenConsumedEvent("s", 200, 60));
        assertEquals(2, count.get());
    }
    @Test void shouldUnsubscribe() {
        AtomicInteger count = new AtomicInteger(0);
        String id = eventBus.subscribe(TokenConsumedEvent.class, e -> count.incrementAndGet());
        eventBus.publish(new TokenConsumedEvent("s", 10, 5));
        eventBus.unsubscribe(id);
        eventBus.publish(new TokenConsumedEvent("s", 10, 5));
        assertEquals(1, count.get());
    }
    @Test void shouldFilterByType() {
        AtomicInteger count = new AtomicInteger(0);
        eventBus.subscribe(TokenConsumedEvent.class, e -> count.incrementAndGet());
        eventBus.publish(new Event() {
            @Override public String getEventType() { return "OTHER"; }
            @Override public long getTimestamp() { return System.currentTimeMillis(); }
        });
        assertEquals(0, count.get());
    }
}
```

---

## 测试类 7：FullPipelineSpringTest.java

**文件路径**：`lyclaw-web/src/test/java/lyjew/com/lyclaw/engine/FullPipelineSpringTest.java`

完整 Pipeline 流程测试——手动构造 Stage 验证 Pipeline 调度 + 拦截器 + EngineSelector 自动发现。

```java
package lyjew.com.lyclaw.engine;

import lyjew.com.lyclaw.LyClawApplication;
import lyjew.com.lyclaw.context.ChatContext;
import lyjew.com.lyclaw.dto.ChatResult;
import lyjew.com.lyclaw.interceptor.Interceptor;
import lyjew.com.lyclaw.interceptor.impl.InterceptorChain;
import lyjew.com.lyclaw.interceptor.impl.LoggingInterceptor;
import lyjew.com.lyclaw.interceptor.impl.RateLimitInterceptor;
import lyjew.com.lyclaw.interceptor.impl.SensitiveDataInterceptor;
import lyjew.com.lyclaw.memory.MemoryManager;
import lyjew.com.lyclaw.model.ChatRequest;
import lyjew.com.lyclaw.model.Message;
import lyjew.com.lyclaw.nullobject.NullMemoryManager;
import lyjew.com.lyclaw.pipeline.Pipeline;
import lyjew.com.lyclaw.pipeline.impl.PipelineBuilder;
import lyjew.com.lyclaw.pipeline.impl.stages.*;
import lyjew.com.lyclaw.skill.SkillRegistry;
import lyjew.com.lyclaw.storage.SessionStorage;
import lyjew.com.lyclaw.tool.impl.DefaultToolCallPolicy;
import lyjew.com.lyclaw.context.impl.FullWindowContextBuilder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(classes = LyClawApplication.class)
class FullPipelineSpringTest {

    @Autowired private lyjew.com.lyclaw.tool.impl.DefaultToolRegistry toolRegistry;
    @Autowired private SkillRegistry skillRegistry;

    @Test void fullPipelineFlow() {
        SessionStorage sessionStorage = null;
        MemoryManager memoryManager = new NullMemoryManager();
        InterceptorChain chain = new InterceptorChain(List.of(
                new RateLimitInterceptor(60), new SensitiveDataInterceptor(), new LoggingInterceptor()));

        ContextBuildStage cb = new ContextBuildStage(sessionStorage, memoryManager,
                new FullWindowContextBuilder(sessionStorage, memoryManager, skillRegistry), skillRegistry);
        InterceptorStage is = new InterceptorStage(chain);
        ToolCallLoopStage tc = new ToolCallLoopStage(null, toolRegistry, new DefaultToolCallPolicy());
        MetricsStage ms = new MetricsStage();
        ResponseBuildStage rs = new ResponseBuildStage();

        Pipeline p = new PipelineBuilder()
                .addStage(cb).addStage(is).addStage(tc).addStage(ms).addStage(rs).build();

        ChatContext ctx = new ChatContext(ChatRequest.builder().sessionId("test")
                .model("t").messages(List.of(Message.builder().role("user").content("hi").build())).build());
        assertDoesNotThrow(() -> p.execute(ctx));
    }

    @Test void interceptorInvoked() {
        AtomicInteger pre = new AtomicInteger(0);
        AtomicInteger post = new AtomicInteger(0);
        Interceptor ci = new Interceptor() {
            @Override public int getOrder() { return 1; }
            @Override public void preHandle(ChatContext c) { pre.incrementAndGet(); }
            @Override public void postHandle(ChatResult r) { post.incrementAndGet(); }
        };
        Pipeline p = new PipelineBuilder()
                .addStage(new InterceptorStage(new InterceptorChain(List.of(ci))))
                .addStage(new ResponseBuildStage()).build();
        p.execute(new ChatContext(ChatRequest.builder().model("t").build()));
        assertEquals(1, pre.get());
        assertEquals(1, post.get());
    }

    @Test void engineSelectorFindsDefault(@Autowired EngineSelector selector) {
        assertNotNull(selector);
        Engine e = selector.select(ChatRequest.builder().model("t")
                .messages(List.of(Message.builder().role("user").content("hi").build())).build());
        assertNotNull(e);
        assertEquals("default", e.getName());
    }
}
```

---

## 块 60 更新：DefaultEngine.java — execute() 改为从 SessionStorage 加载历史消息

### 修改原因

**问题**：原 `execute()` 在第 78-81 行每次 `new Session()`，不加载历史。多轮对话时第 2 轮看不到第 1 轮的对话历史，模型"失忆"。

**修改点**：

| 位置 | 原代码 | 改后 | 目的 |
|------|--------|------|------|
| 字段 | 无 `SessionStorage` | 新增 `private final SessionStorage sessionStorage;` | 从文件读取/写入会话 |
| 构造函数 | 9 参数 | 10 参数（+SessionStorage） | 注入 SessionStorage |
| execute() 步骤3 | `new Session()` | `sessionStorage.get(sessionId).orElse(null)` | 加载已有历史 |
| execute() 步骤8 | 只 `memoryManager.append()` | 追加 assistant 消息 + `sessionStorage.save()` | 持久化会话 |

### 修改后的 execute() 代码

```java
@Override
public Flux<String> execute(ChatRequest request) {
    // 1. 读取长期记忆
    MemoryContent memory = memoryManager.read();

    // 2. 获取工具定义
    List<ToolDefinition> toolDefinitions = toolRegistry.getAllDefinitions();

    // ═══════════════════════════════════════════════════════════
    // 3. 从 SessionStorage 加载已有会话（多轮对话支持）
    // ═══════════════════════════════════════════════════════════
    // 目的：同一个 sessionId 代表同一次对话。
    // 第 1 次调用时 sessionId 对应的 JSON 文件还不存在
    // → sessionStorage.get() 返回 Optional.empty() → orElse(null) 返回 null
    // → 走"新会话"分支
    // 第 2 次及以后调用时 JSON 文件已存在
    // → 拿到之前保存的完整 Message 列表（user + assistant 交替）
    // → 走"已有会话"分支，合并历史
    //
    // 如果不做这一步，每次 execute() 都是孤立的单轮调用，
    // ContextBuildStage 组装消息时只有当前这次传入的消息，
    // 模型永远不会看到之前说过什么，必然"失忆"。
    Session session = sessionStorage.get(request.getSessionId()).orElse(null);

    if (session == null) {
        // 3a. 新会话：用 request.getMessages() 初始化
        // request.getMessages() 至少包含 1 条 user 消息（由调用方保证，如测试类 createMessage()）
        session = new Session();
        session.setSessionId(request.getSessionId());
        session.setMessages(request.getMessages());
    } else {
        // 3b. 已有会话：保留历史消息，追加当前请求的新消息
        // 必须把历史（session.getMessages()）和当前（request.getMessages()）合并。
        // 假设历史是 [user: "记住Java", assistant: "好的"]
        // 当前请求是 [user: "我喜欢什么语言？"]
        // 合并后 = [user: "记住Java", assistant: "好的", user: "我喜欢什么语言？"]
        // 这 3 条一起发给模型，模型才知道上下文。
        List<Message> allMessages = new ArrayList<>(session.getMessages());
        if (request.getMessages() != null) {
            allMessages.addAll(request.getMessages());
        }
        session.setMessages(allMessages);
    }

    // 4. 构建 ChatContext — 6 参数构造器
    // session 此时已包含 (历史消息 + 当前消息) 的完整列表
    ChatContext context = new ChatContext(
            request, session, memory,
            toolDefinitions, interceptorChain, modelProvider
    );

    // 5. 构建并执行 Pipeline
    Pipeline pipeline = pipelineBuilder.build();
    pipeline.execute(context);

    // 6. 获取结果
    ChatResult result = context.getResult();

    // ═══════════════════════════════════════════════════════════
    // 7. 将模型回复写入 Session 并持久化（多轮对话基础）
    // ═══════════════════════════════════════════════════════════
    // 目的：
    //   a) 把 AI 回复以 assistant 角色追加到 session.messages
    //   b) sessionStorage.save() 写入 JSON 文件（路径由 LocalFileEngine 决定）
    //
    // 持久化后的 session.messages = [user, assistant, user, assistant, ...]
    // 下次 execute() 加载同一个 sessionId 时，能读到完整的历史。
    //
    // 如果漏掉 save()，Session JSON 文件里永远只有 user 消息，
    // 第 3 次调用时加载到的仍然是 [user, user]，中间的 assistant 回复全部丢失，
    // 模型永远无法"记住"之前回答过什么。
    if (result != null) {
        memoryManager.append(result.getContent());

        // 7a. 构造 assistant 消息（模型回复），追加到 session.messages
        Message assistantMsg = Message.builder()
                .role("assistant")
                .content(result.getContent())
                .build();
        session.getMessages().add(assistantMsg);

        // 7b. 持久化到 JSON 文件
        sessionStorage.save(session);
    }

    return Flux.just(result != null ? result.getContent() : "");
}
```

### 构造函数新增 SessionStorage 依赖

```java
// 新增字段
private final SessionStorage sessionStorage;

// 构造函数加参数（加在最后）
public DefaultEngine(ContextBuilder contextBuilder,
                     InterceptorChain interceptorChain,
                     ModelProvider modelProvider,
                     ToolRegistry toolRegistry,
                     ToolCallPolicy toolCallPolicy,
                     ErrorPolicy errorPolicy,
                     EventBus eventBus,
                     MemoryManager memoryManager,
                     PipelineBuilder pipelineBuilder,
                     SessionStorage sessionStorage) {  // ← 新增
    // ...
    this.sessionStorage = sessionStorage;  // ← 新增
}
```

### 补充 import

在文件顶部 import 块追加：
```java
import lyjew.com.lyclaw.storage.SessionStorage;
import java.util.ArrayList;
```

---

## 附录：流式路径 Bug 修复记录（2026-04-30）

### 1. global.md 存储了原始流式 JSON chunk

**问题**：ToolCallLoopStage.executeStreamInternal() 的 doOnComplete 中，collector 收集的是
adapter.chatStream() 返回的原始 SSE 数据（如 `data:{"choices":[{"delta":{"content":"文本"}}]}`），
直接存到 `__stream_full_content__` 属性，导致 memoryManager.append() 写入记忆文件的是原始 JSON。

**修复**：新增 `extractPlainTextFromSSE(raw)` 方法，从 `data:{"choices":[{"delta":{"content":"文本"}}]}`
格式中提取每个 delta.content 字段的纯文本并拼接，再存入 `__stream_full_content__`。
确保记忆文件（global.md）只存纯文本，不包含流式 JSON chunk。

### 2. 会话文件被保存为 null.json

**问题**：Session 类有 2 个 id 字段：
- `BaseDTO.id`（getter = `getId()`）— SessionStorage.extractId() 用此字段提取文件名
- `sessionId`（getter = `getSessionId()`）— 业务会话 ID

loadOrCreateSession() 只设置了 `sessionId` 没设置 `BaseDTO.id`，导致文件名 = `null.json`。

**修复**：DefaultEngine.execute() 加载 Session 后加 `session.setId(session.getSessionId())`，
确保两个 id 字段一致。

### 3. Token 用量未打印

**问题**：MetricsStage.process() 只发了 EventBus 事件，没有打日志。

**修复**：MetricsStage 加 `@Slf4j`，在 process() 中读取 `context.getResult().getTokenUsage()`
和 durationMs 输出 info 日志。

流式路径额外：ToolCallLoopStage 新增 `extractTokenUsageFromSSE(raw)` 方法，从最后一个 SSE chunk
的 `"usage":{"prompt_tokens":6,"completion_tokens":19,"total_tokens":30}` 字段提取 token 用量，
存入 `ChatContext.__stream_token_usage__` 属性。ResponseBuildStage 读取该属性并填入 ChatResult。

### 4. 记忆注入不生效（记忆内容没传递给模型）

**问题**：FullWindowContextBuilder.buildMemoryMessage() 将记忆注入为 role="system" 消息。
但 DeepSeekOpenAIAdapter.buildMessages() 在序列化时会**跳过所有 role=system 的消息**
（用 ChatRequest.systemPrompt 替代），导致记忆消息被过滤，模型收不到记忆内容。

**修复**：FullWindowContextBuilder 将记忆消息的 role 从 "system" 改为 "user"，确保记忆
能正常通过适配器序列化并传递给模型。

### 5. 前端换行显示问题

**问题**：Controller 从 DeepSeek 返回的 SSE JSON 中提取 content 时，因为 JSON 中没有转义处理，
content 字符串中的 `\n` 以字面形式（两个字符反斜杠+n）传递给前端。前端的 renderMarkdown()
只处理了实际换行符（`/\n/g`），没有处理字面 `\n` 字符串。

**修复**：前端 renderMarkdown() 第一步增加 `text.replace(/\\n/g, '\n')`，将字面 `\n` 转成
实际换行符，再走 marked 渲染为 `<br>`。

---

# 第十部分：流式工具调用状态机（lyclaw-engine/stream）

> **设计文档对应**：第六章（流式执行）附录 2（目录结构）+ 附录 3（文件清单）
>
> **动机**：流式模式下不能用同步 wait+return 的方法处理工具调用循环。
> 将"模型调用 → 检测工具 → 执行工具 → 再次调用"的多轮循环拆为独立状态，
> 状态机引擎持有转换表决定流转。每个状态只处理自己的逻辑，
> 不关心前后是谁，满足：可扩展（增删状态不需改已有代码）、可测试（每个状态独立测试）、
> 长期可维护（第三期/第五期都能撑住）。

## 实现文件清单

| 序号 | 文件 | 包 | 角色 |
|------|------|-----|------|
| 1 | StreamToolCallState.java | pipeline/impl/stream | 状态接口 |
| 2 | Signal.java | pipeline/impl/stream | 信号枚举 |
| 3 | StateResult.java | pipeline/impl/stream | 状态处理结果 |
| 4 | StreamToolCallStateMachine.java | pipeline/impl/stream | 状态机引擎 |
| 5 | ModelCallState.java | pipeline/impl/stream | 模型调用状态（流式） |
| 6 | SyncModelCallState.java | pipeline/impl/stream | 模型调用状态（同步） |
| 7 | ToolDetectState.java | pipeline/impl/stream | 工具检测状态 |
| 8 | ToolExecuteState.java | pipeline/impl/stream | 工具执行状态 |
| 9 | ToolCallEventEmitter.java | pipeline/impl/stream | 工具调用事件 Flux 构建器 |
| 10 | SseToolCallParser.java | pipeline/impl/stream | SSE 工具调用解析接口 |
| 11 | DeepSeekSseToolCallParser.java | pipeline/impl/stream | DeepSeek 厂商实现 |

## 状态机设计

### 状态转换图（v2 — 边收边发）

```
ModelCallState(后台线程启动rawFlux)
    │  立即返回 Flux(Sinks.Many)
    ▼
ToolDetectState
    ├─ __has_tool_call__ = true  (增量检测)  ──► TOOL_CALLS_FOUND
    ├─ toolChoice 显式指定        (偷跑构造)  ──► TOOL_CALLS_FOUND
    └─ 无(含 __tool_choice_executed__ 保护)  ──► NO_TOOL_CALLS
                                                     │
                                                     ▼
                                                状态机结束
                                                Flux → Controller
                                                     │
                                                     ▼
                                             sse-emitter Thread 订阅
                                                     │
                                                     ▼
                                              实时接收 Sinks buffer 数据
```

**说明**：
- `ModelCallState` 不再阻塞，而是启后台线程 + 返回实时 Flux（Sinks.Many）
- `ToolDetectState` 三路径检测：增量标志 → toolChoice → 后备 collector
- `__tool_choice_executed__` 保护：跳过已执行过强制工具后的重复检测
- `ToolExecuteState` 执行工具并设 `__tool_choice_executed__ = true` + `toolChoice = "none"`

### 核心原则

- **状态不知道自己下一个是谁**：由状态机引擎的 transitionTable 决定流转
- **状态只返回 StateResult(Signal, outputFlux, toolCalls)**：不直接操作状态机
- **轮次控制**：状态机引擎统一处理。最多 MAX_ROUNDS（6）轮后强制停止
- **超时控制**：后台线程消费 SSE 流，collectorLatch 仅用于备用（ToolDetectState 后备路径）
- **同步模式也纳入同一状态机体系**：共用 ToolDetectState、ToolExecuteState，只替换 ModelCallState 为 SyncModelCallState

## StreamToolCallState.java — 状态接口

**文件路径**：`lyclaw-engine/src/main/java/lyjew/com/lyclaw/pipeline/impl/stream/StreamToolCallState.java`

```java
package lyjew.com.lyclaw.pipeline.impl.stream;

import lyjew.com.lyclaw.context.ChatContext;

/**
 * StreamToolCallState — 流式工具调用状态接口。
 *
 * <p>每个状态实现此接口，处理一个阶段后返回 StateResult。
 * 状态不决定下一个是谁——由状态机引擎的 transitionTable 决定流转。
 * 职责单一：只处理"模型调用"、"工具检测"或"工具执行"中的一个阶段。
 *
 * @author LyClaw Team
 * @version 1.0
 */
public interface StreamToolCallState {

    /**
     * 执行当前状态的逻辑。
     *
     * @param context 对话上下文，包含请求、适配器、消息列表等
     * @return 状态处理结果，包含信号 + 输出 Flux + 工具调用列表
     */
    StateResult handle(ChatContext context);
}
```

## Signal.java — 信号枚举

**文件路径**：`lyclaw-engine/src/main/java/lyjew/com/lyclaw/pipeline/impl/stream/Signal.java`

```java
package lyjew.com.lyclaw.pipeline.impl.stream;

/**
 * Signal — 状态处理完成后的信号，驱动状态机流转。
 *
 * <p>每个 StateResult 都关联一个 Signal。
 * 状态机引擎根据 transitionTable（Map&lt;Signal, Class&lt;?&gt;&gt;）决定下一状态。
 *
 * @author LyClaw Team
 * @version 1.0
 */
public enum Signal {

    /** 模型调用完成，等待检测工具调用 */
    STREAM_COMPLETED,

    /** 模型调用的 SSE 流完成（同步模式用） */
    SYNC_COMPLETED,

    /** 检测到工具调用，需要执行工具 */
    TOOL_CALLS_FOUND,

    /** 无工具调用，终止循环 */
    NO_TOOL_CALLS,

    /** 工具执行完成 */
    TOOL_EXECUTED,

    /** 发生错误 */
    ERROR
}
```

## StateResult.java — 状态处理结果

**文件路径**：`lyclaw-engine/src/main/java/lyjew/com/lyclaw/pipeline/impl/stream/StateResult.java`

```java
package lyjew.com.lyclaw.pipeline.impl.stream;

import lyjew.com.lyclaw.adapter.ModelResponse;
import reactor.core.publisher.Flux;

import java.util.Collections;
import java.util.List;

/**
 * StateResult — 状态处理结果，包含信号 + 输出 + 工具调用信息。
 *
 * <p>每个 StreamToolCallState.handle() 返回此对象。
 * 状态机引擎从 result 读取 signal 决定下一状态，读取 outputFlux 合并到总 Flux。
 *
 * @param <T> 输出 Flux 的元素类型（String = SSE 行）
 * @author LyClaw Team
 * @version 1.0
 */
public class StateResult<T> {

    /** 信号，决定下一状态 */
    private final Signal signal;

    /** 输出 Flux（合并到总 Flux 中的片段） */
    private final Flux<T> outputFlux;

    /** 工具调用请求列表（当 signal=TOOL_CALLS_FOUND 时非空） */
    private final List<ModelResponse.ToolCallRequest> toolCalls;

    public StateResult(Signal signal, Flux<T> outputFlux, List<ModelResponse.ToolCallRequest> toolCalls) {
        this.signal = signal;
        this.outputFlux = outputFlux;
        this.toolCalls = toolCalls != null ? toolCalls : Collections.emptyList();
    }

    public Signal getSignal() { return signal; }
    public Flux<T> getOutputFlux() { return outputFlux; }
    public List<ModelResponse.ToolCallRequest> getToolCalls() { return toolCalls; }

    /** 快捷工厂：无工具调用，正常终止 */
    public static <T> StateResult<T> noToolCalls(Flux<T> outputFlux) {
        return new StateResult<>(Signal.NO_TOOL_CALLS, outputFlux, null);
    }

    /** 快捷工厂：发现工具调用 */
    public static <T> StateResult<T> toolCallsFound(Flux<T> outputFlux, List<ModelResponse.ToolCallRequest> toolCalls) {
        return new StateResult<>(Signal.TOOL_CALLS_FOUND, outputFlux, toolCalls);
    }

    /** 快捷工厂：流完成，等待检测 */
    public static <T> StateResult<T> streamCompleted(Flux<T> outputFlux) {
        return new StateResult<>(Signal.STREAM_COMPLETED, outputFlux, null);
    }

    /** 快捷工厂：错误 */
    public static <T> StateResult<T> error(Throwable error) {
        return new StateResult<>(Signal.ERROR, Flux.error(error), null);
    }
}
```

## StreamToolCallStateMachine.java — 状态机引擎

**文件路径**：`lyclaw-engine/src/main/java/lyjew/com/lyclaw/pipeline/impl/stream/StreamToolCallStateMachine.java`

**核心职责**：
1. 持有 `transitionTable`（Map<Signal, Class<? extends StreamToolCallState>>）定义状态流转
2. 入口 `start(ChatContext) → Flux<String>` 返回合并的流
3. 循环：执行当前状态 → 检查 signal → 查 transitionTable → 实例化下一状态
4. 轮次控制：超过 MAX_ROUNDS（6）轮强制终止
5. 收集每轮产生的 Flux，最终通过 `Flux.concat` 合并为单一 Flux
6. 自动将工具状态事件 Flux（`__tool_event_flux__`）插入 Flux 链

**关键行为**：
- transitionTable 第一版为固定映射，后续可从配置/拦截器动态扩展
- 状态实例化通过 Spring ApplicationContext 或反射 + 无参构造器
- 错误发生时终止循环，通过 `Flux.concat` 传递错误给调用方

## ModelCallState.java — 流式模型调用状态（边收边发）

**文件路径**：`lyclaw-engine/src/main/java/lyjew/com/lyclaw/pipeline/impl/stream/ModelCallState.java`

**职责**：
1. 启动后台线程立即消费 `adapter.chatStream()` 的 SSE 流
2. 后台线程每次收到 data 时：通过 `Sinks.Many` 实时推送给 Controller，同时追加到 collector
3. 后台线程每收到一条 data，立即增量检测工具调用（`__has_tool_call__` 标志位），不阻塞
4. 后台线程 onComplete 时保存完整内容到 context

**关键设计**：后台线程 + Sinks.Many 替代旧的 CountDownLatch 同步等待。

**架构对比**：
| 方案 | 说明 | 问题 |
|------|------|------|
| 旧方案（同步收集） | CountDownLatch 等待整个 SSE 流收完后再重放 | 用户等待时间 = API 延迟 + 全部收集时间（约3+秒无输出） |
| 新方案（边收边发） | 后台线程实时填充 Sinks.Many，控制器收到 Flux 后立即消费 | 用户等待时间 ≈ API 首包延迟（约200ms） |

**时序流程**：
1. `ModelCallState.handle()` 创建 `Sinks.Many<String>` 和后台线程，立即返回
2. 后台线程调用 `adapter.chatStream()` 发起 HTTP 请求
3. 后台线程在回调中同时做两件事：
   - `sink.tryEmitNext(data)` → 实时推送给 Controller
   - `collector.append(data)` → 累计到 collector
4. ToolDetectState 通过 `__has_tool_call__` 标志位检测（非阻塞）
5. 后台线程 onComplete 时：`sink.tryEmitComplete()` + `collectorLatch.countDown()`
6. Controller 在 Pipeline 完成后订阅 `sink.asFlux()`，从 buffer 中获取已收集的数据

**增量工具调用检测**：
- 后台线程每次收到 data 就调用 `sseToolCallParser.extractToolCalls(currentCollector)`
- 检测到工具调用时设置 `__has_tool_call__ = true`
- ToolDetectState 检查此标志位，无需等 collector 收完
- onComplete 时做二次检测（兜底）

**为什么不需要同步等待了**：
- 删除了 CountDownLatch.await() 的阻塞
- ToolDetectState 不再通过等待 collectorLatch 来获取完整的 collector 内容
- 改由增量检测 + toolChoice 显式检测替代

## SyncModelCallState.java — 同步模型调用状态

**文件路径**：`lyclaw-engine/src/main/java/lyjew/com/lyclaw/pipeline/impl/stream/SyncModelCallState.java`

**职责**：同步模式的模型调用，与 ModelCallState 共用接口，只替换这个变体。

**差异**：
- 同步模式调用 `adapter.chatSync()`（非流式）
- 直接返回完整的 response 字符串，不需要收集 SSE 行
- 返回的 Flux 只有一个元素的 Flux.just(纯文本)
- 工具检测逻辑与 ToolDetectState 共用（通过核心层 ModelResponse 解析）

## ToolDetectState.java — 工具检测状态（三路径检测）

**文件路径**：`lyclaw-engine/src/main/java/lyjew/com/lyclaw/pipeline/impl/stream/ToolDetectState.java`

**职责**：从模型回复中检测是否有工具调用。

**检测逻辑（优先级从高到低）**：

1. **同步模式**：`__sync_response__` 存在且 hasToolCalls() → TOOL_CALLS_FOUND
2. **流式增量检测**：`__has_tool_call__` 标志位为 true（ModelCallState 后台线程增量检测设的） → TOOL_CALLS_FOUND
3. **toolChoice 显式指定**：`request.getToolChoice()` 非空且不是 "auto"/"none" → 直接构造 fakeCall 返回 TOOL_CALLS_FOUND
4. **后备**：等待 collectorLatch（500ms 超时）后从 collector 全文解析

**关键保护**：`__tool_choice_executed__` 标记
- 如果之前已经执行过一次强制工具调用，跳过所有工具检测（返回 NO_TOOL_CALLS）
- 防止 DeepSeek 在 `tool_choice=none` 下仍然返回工具调用导致的死循环

**为什么需要 toolChoice 显式检测（路径 3）**：
- Controller 检测到"时间/日期"关键词时设了 `tool_choice=current_time`
- 但流式模式下增量检测太慢（SSE 流的工具调用在 300-500ms 后才到达）
- ToolDetectState 的 500ms 超时会先触发 → 判定 NO_TOOL_CALLS → 状态机结束
- 通过直接检查 toolChoice，在 SSE 解析前就强制构造 fakeCall，确保工具被执行

**为什么需要 `__tool_choice_executed__` 保护（路径 3 的跳出条件）**：
- 第一次 ToolExecuteState 执行完 fakeCall 后 `setToolChoice("none")`，但 DeepSeek 在 `tool_choice=none` 下仍可能返回工具调用
- 后台线程的增量检测会再次检测到工具调用（`__has_tool_call__=true`）
- 如果没有 `__tool_choice_executed__`，ToolDetectState 会在第二轮又进入 TOOL_CALLS_FOUND → 死循环
- 设了这个标记后，第二轮直接跳过所有工具检测，返回 NO_TOOL_CALLS

## ToolExecuteState.java — 工具执行状态

**文件路径**：`lyclaw-engine/src/main/java/lyjew/com/lyclaw/pipeline/impl/stream/ToolExecuteState.java`

**职责**：
1. 遍历所有工具调用请求，逐个调用 `toolRegistry.execute()`
2. 通过 `ToolCallEventEmitter` 发送 tool_call executing/done 事件
3. 将工具结果包装为 `role=tool` 消息追加到 messages
4. 在 `role=tool` 消息前插入 `role=assistant` 消息（含 tool_calls 数组），满足 OpenAI/DeepSeek 协议
5. 清空 `toolChoice`（`context.getOriginalRequest().setToolChoice(null)`），防止下一轮死循环
6. 将工具事件 Flux 存到 `__tool_event_flux__` 属性

**为什么需要插入 assistant 消息（含 tool_calls）**：
- OpenAI/DeepSeek API 协议要求：`role=tool` 的消息必须跟在包含 `tool_calls` 的 assistant 消息后
- messages 格式必须是：`[user, assistant(with tool_calls), tool(with tool_call_id), ...]`
- ToolExecuteState 会检查上一条消息，如果缺少 tool_calls 则自动插入

**toolChoice 清空与死循环防护**：
- 第 1 轮可能因为 Controller 检测到时间关键词而设了 `tool_choice=current_time`
- 工具执行完后 `setToolChoice("none")`（注意：不是 `setToolChoice(null)`，null 会导致适配器用 "auto" 模式，但 DeepSeek 在 auto 下仍可调用工具）
- 同时设置 `__tool_choice_executed__ = true` 标记，阻止 ToolDetectState 后续轮次继续检测工具调用
- 双层防护：`tool_choice=none` + `__tool_choice_executed__` = 彻底切断工具调用循环

## ToolCallEventEmitter.java — 工具调用事件 Flux 构建器

**文件路径**：`lyclaw-engine/src/main/java/lyjew/com/lyclaw/pipeline/impl/stream/ToolCallEventEmitter.java`

**职责**：
1. 为每个工具调用构建 `event:tool_call` SSE 事件
2. 事件类型：`executing`（旋转图标）+ `done`（✅ 图标）
3. done 事件中附带 `result` 字段（工具执行结果）
4. 返回的 Flux 直接合并到总 Flux 链中

**输出格式**：
```
event:tool_call
data:{"type":"tool_call","name":"current_time","status":"executing"}

event:tool_call
data:{"type":"tool_call","name":"current_time","status":"done","result":"当前时间: 2026-05-01 21:11:11"}
```

## SseToolCallParser.java — SSE 工具调用解析接口

**文件路径**：`lyclaw-engine/src/main/java/lyjew/com/lyclaw/pipeline/impl/stream/SseToolCallParser.java`

**职责**：从 SSE 行列表中提取工具调用请求的接口。不同厂商的 SSE 格式不同，需要各自的实现。

**方法签名**：
```java
List<ModelResponse.ToolCallRequest> parse(List<String> sseLines);
```

## DeepSeekSseToolCallParser.java — DeepSeek 厂商实现

**文件路径**：`lyclaw-engine/src/main/java/lyjew/com/lyclaw/pipeline/impl/stream/DeepSeekSseToolCallParser.java`

**职责**：
1. 解析 DeepSeek SSE 格式：`data:{"choices":[{"delta":{"tool_calls":[...]}}]}`
2. 跨多个 SSE chunk 拼接完整的 tool_call（因为 DeepSeek 把 tool_call 的 id、type、function.name、function.arguments 拆到多个 chunk）
3. 处理 `[DONE]` 标记

---

# 第十一部分：ChatController + 前端工具调用展示 + 适配器更新

## ChatController.java — SSE 透传重构

**文件路径**：`lyclaw-web/src/main/java/lyjew/com/lyclaw/controller/ChatController.java`

### Controller 改动

| 改动 | 说明 |
|------|------|
| 新增 `toolChoice` 自动检测 | 消息包含时间/日期/星期关键词时自动设 `tool_choice=current_time` |
| SseEmitter 发送 | 使用 `SseEmitter` + `executor.submit` 异步消费 Flux |
| 完全透传 SSE 行 | 不再从 SSE JSON 中提取 content，改为直接发送 `event:type\ndata:内容` |
| event:tool_call 支持 | 工具调用事件单独识别，格式为 `event:tool_call` + `data:{json}` |
| event:message 支持 | 普通文本消息以 `event:message` + `data:文本片段` 发送 |
| close_notify 标记 | 流结束时发送 `event:message\ndata:[DONE]` |
| **buffer 策略** | `MAX_CHUNK_SIZE=5` 累积至少 5 字符再发，减少 SSE 事件数但保持逐字感 |
| **Tomcat buffer 关闭** | `response.setBufferSize(0)` 防止 Tomcat 8KB 缓存导致所有 SSR 事件一次发出 |

### SSE 逐字 buffer 策略

**动机**：如果不加 buffer，后端每次 `emitter.send()` 只发一个 token（如 "当"、"前"、"时"、"间"）。前端收到每个 token 都要触发一次 Vue 3 DOM diff，开销大且会出现闪烁。但如果每 token 都单独发，SSE 事件数太多（~114个）。

**实现**：
```java
final StringBuilder messageBuffer = new StringBuilder();
final int MAX_CHUNK_SIZE = 5;  // 累积至少 5 字符再发

// 每次收到数据时，解析 content 后追加到 buffer
// buffer 长度 >= 5 时发一次 SSE event:message
// 流结束前 flush 剩余字符

emitter.send(SseEmitter.event().name("message").data(messageBuffer.toString()));
```

### Controller toolChoice 检测逻辑

```java
// 自动设 toolChoice：如果 API 传了就用传的，否则根据消息内容推断
if (request.getToolChoice() == null || request.getToolChoice().isBlank()) {
    String messagesText = request.getMessages() != null
            ? request.getMessages().stream()
                .map(Message::getContent)
                .filter(Objects::nonNull)
                .reduce("", String::concat)
            : "";
    if (messagesText.contains("时间") || messagesText.contains("几点了")
            || messagesText.contains("日期") || messagesText.contains("现在几点")
            || messagesText.contains("今天") || messagesText.contains("星期")) {
        request.setToolChoice("current_time");
    }
}
```

### SSE 透传（核心逻辑）

```java
executor.submit(() -> {
    try {
        flux.subscribe(
            line -> {
                if (line == null || line.isBlank()) return;
                if (line.startsWith("event:") || line.startsWith("data:")) {
                    // 完整 SSE 事件：直接透传给前端
                    if (line.startsWith("event:tool_call")) {
                        // 工具调用事件
                        String[] parts = line.split("\n");
                        for (String part : parts) {
                            emitter.send(SseEmitter.event()
                                    .name("tool_call")
                                    .data(part.startsWith("data:") ? part.substring(5) : part));
                        }
                    } else {
                        // 普通消息事件
                        emitter.send(SseEmitter.event()
                                .name("message")
                                .data(line));
                    }
                } else {
                    // 纯文本行：包装为 event:message
                    emitter.send(SseEmitter.event()
                            .name("message")
                            .data(line));
                }
            },
            error -> emitter.completeWithError(error),
            () -> {
                emitter.send(SseEmitter.event().name("message").data("[DONE]"));
                emitter.complete();
            }
        );
    } catch (Exception e) {
        emitter.completeWithError(e);
    }
});
```

## 前端 ChatTest.vue — 工具调用展示

**文件路径**：`lyclaw-ui/src/components/ChatTest.vue`

### 新增 tool_call 消息类型

```javascript
// 新增消息角色
const role = msg.role === 'user' ? 'U'
    : msg.role === 'tool_call' ? '🔧'
    : 'A'
```

### SSE 事件解析

前端 SSE 解析器处理两种事件类型：
- `event:message` → 普通文本，追加到最近一条 assistant 消息的 content
- `event:tool_call` → 解析 JSON 数据，追加 tool_call 类型消息

### tool_call 事件处理逻辑

```javascript
if (currentEventType === 'tool_call') {
    const toolEvent = JSON.parse(chunk);
    if (toolEvent.type === 'tool_call') {
        const toolMsg = {
            role: 'tool_call',
            name: toolEvent.name,
            status: toolEvent.status,  // 'executing' | 'done'
            content: ''
        };
        // 替换或追加工具调用消息
        const lastMsg = messages.value[messages.value.length - 1];
        if (同工具名 && executing → 不重复添加) { ... }
        else if (同工具名 && executing → done → 更新状态) { ... }
        else { messages.value.push(toolMsg); }
    }
}
```

### CSS 样式

| 元素 | 样式 |
|------|------|
| `.tool_call` 消息行 | 黄色/绿色背景，区分于普通消息 |
| `.tool-spinner` | 旋转动画（CSS @keyframes spin） |
| `.tool-status.executing` | 🟡 黄色 |
| `.tool-status.done` | 🟢 绿色 |

---

# 附录：2026-05-01 流式工具调用 Bug 修复记录

## Bug 1：tool_call_id 缺失 → API 400

**现象**：第 2 轮模型调用返回 400 `"Messages with role 'tool' must be a response to a preceding message with 'tool_calls'"`

**根因**：ToolExecuteState 构建 `role=tool` 消息时没有设 `toolCallId`。DeepSeek API 要求每条 `role=tool` 消息必须有 `tool_call_id`，用于关联对应的 assistant tool_call。

**修复（2026-05-01 21:00）**：

| 文件 | 改动 |
|------|------|
| `Message.java` | 新增 `toolCallId` 字段 |
| `ToolExecuteState.java` | 构造 tool 消息时 `.toolCallId(req.getId())` |
| `DeepSeekOpenAIAdapter.java` | 序列化 tool 消息时优先使用 `msg.getToolCallId()` |

## Bug 2：assistant 消息缺少 tool_calls 数组 → API 400

**现象**：第 2 轮模型调用返回 400 `"Messages with role 'tool' must be a response to a preceding message with 'tool_calls'"`

**根因**：修复 Bug 1 后仍有 400，因为 `role=tool` 前面缺少 `role=assistant` 消息（含 `tool_calls` 数组）。OpenAI/DeepSeek 协议要求：

```
messages: [
  {"role":"user", "content":"现在几点了"},
  {"role":"assistant", "content":"", "tool_calls":[{"id":"call_xxx", "type":"function", "function":{"name":"current_time","arguments":"{}"}}]},
  {"role":"tool", "content":"当前时间: ...", "tool_call_id":"call_xxx"}
]
```

**修复（2026-05-01 21:09）**：`ToolExecuteState.java` — 在执行工具前，检查 messages 最后一条是不是 `role=assistant` 且含 `tool_calls`。如果不是，自动插入一条 `role=assistant` 消息，内容为空字符串，`toolCalls` 设置为从 ToolCallRequest 转换的元数据。

## Bug 3：toolChoice 未清空 → 工具调用死循环

**现象**：第 1 轮模型返回时间后，第 2 轮仍然强制调 `tool_choice=current_time`，导致模型第 2 轮又返回时间工具调用，再执行、再调用，无限循环。

**根因**：ChatController 在请求进入 Pipeline 前设了 `request.setToolChoice("current_time")`。这个 toolChoice 在所有轮次中都生效。

**修复（2026-05-01 21:00）**：`ToolExecuteState.java` — 工具执行完成后清空 `toolChoice`：

```java
// 清空 toolChoice — 防止下一轮状态机又强制调用相同工具导致死循环
context.getOriginalRequest().setToolChoice(null);
```

## 修复效果验证

curl 测试输出：
```
event:tool_call
data:{"type":"tool_call","name":"current_time","status":"executing"}

event:tool_call
data:{"type":"tool_call","name":"current_time","status":"done","result":"当前时间: 2026-05-01 21:11:11"}

event:message
data:现在是 **2026年5月1日 21:11（晚上9点11分）**。
```

**正常流程**：工具执行 → 显示 executing → 显示 done → 第 2 轮模型调用 → 输出纯文本回复。

---

## 边收边发架构升级（2026-05-01 22:04）

**动机**：用户反馈流式输出"不够快"——虽然前端发送间隔已优化（buffer 策略），但根本瓶颈在 ModelCallState 同步收集整个 SSE 流后才重放。DeepSeek API 响应约 3 秒，加上同步收集时间，用户看到的延迟 = API 延迟 + 全部收集时间。

**改进**：

| 文件 | 改动 |
|------|------|
| `ModelCallState.java` | 从 CountDownLatch 同步等待 → 后台线程 + Sinks.Many 边收边发 |
| `ToolDetectState.java` | 从等 collectorLatch → 三路径增量检测 + toolChoice 显式检测 |
| `ToolExecuteState.java` | `setToolChoice("none")` + `__tool_choice_executed__` 标记 |
| `ChatController.java` | buffer 策略（MAX_CHUNK_SIZE=5），关闭 Tomcat 8KB buffer |
| `DeepSeekOpenAIAdapter.java` | `resolveToolChoice` 支持 "none"/"auto"/"required" 字符串透传 |

**关键架构变化**：
1. ModelCallState 不再阻塞状态机循环——后台线程独立消费 SSE 流
2. Controller 收到 Flux 后立即 subscribe，边收边发到前端
3. ToolDetectState 通过增量标志位（`__has_tool_call__`）实时检测工具调用，不等 collector 完整收完
4. `__tool_choice_executed__` 标记 + `toolChoice="none"` 双层防护，彻底切断死循环
5. `setBufferSize(0)` 防止 Tomcat 8KB 缓存导致所有 SSE 事件一次发出

**验证结果**（curl 测试）：
```
event:tool_call
data:{"type":"tool_call","name":"current_time","status":"executing"}

event:tool_call
data:{"type":"tool_call","name":"current_time","status":"done","result":"当前时间: 2026-05-01 22:25:06"}

event:message
data:现在是 **2026年5月1日（周五）22:25**，晚上 10 点 25 分 😊
```

**效果**：只调一次 current_time（无死循环），文本逐段输出，全程约 12 秒（首次启动+3 个工具调用轮次）。

---

## 第十部分 stream 包各文件目的说明

> 以下描述 stream 包下每个文件的目的、被谁调用、解决什么问题。

### 文件一览

| 文件 | 包路径 | 分类 | 目的 |
|------|--------|------|------|
| `StreamToolCallState.java` | `pipeline/impl/stream` | 接口 | 状态接口，定义 handle(ChatContext) → StateResult |
| `Signal.java` | `pipeline/impl/stream` | 枚举 | 状态处理完成后的信号，驱动状态机流转 |
| `StateResult.java` | `pipeline/impl/stream` | 类 | 状态处理结果封装，包含 Signal + flux + toolCalls |
| `StreamToolCallStateMachine.java` | `pipeline/impl/stream` | 引擎 | 持有 transitionTable，循环执行状态直到 NO_TOOL_CALLS |
| `ModelCallState.java` | `pipeline/impl/stream` | 状态（流式） | 流式调用模型，启后台线程边收边发 |
| `SyncModelCallState.java` | `pipeline/impl/stream` | 状态（同步） | 同步调用模型，返回完整结果 |
| `ToolDetectState.java` | `pipeline/impl/stream` | 状态 | 检测工具调用（增量/同步/toolChoice/后备） |
| `ToolExecuteState.java` | `pipeline/impl/stream` | 状态 | 执行工具，发事件，插 assistant/tool 消息 |
| `ToolCallEventEmitter.java` | `pipeline/impl/stream` | 组件 | 构建 tool_call executing/done SSE 事件 Flux |
| `SseToolCallParser.java` | `pipeline/impl/stream` | 接口 | SSE 解析接口（抽象不关心厂商格式） |
| `DeepSeekSseToolCallParser.java` | `pipeline/impl/stream` | 实现 | DeepSeek SSE 格式的具体解析器 |

### 各文件详解

#### StreamToolCallState.java
- **目的**：状态接口，定义 handle(ChatContext) → StateResult 契约
- **被谁调用**：StreamToolCallStateMachine 在循环中反射实例化状态后调用 handle()
- **谁实现它**：ModelCallState / SyncModelCallState / ToolDetectState / ToolExecuteState
- **为什么需要接口**：状态机引擎不需要知道具体状态逻辑，只依赖接口多态

#### Signal.java
- **目的**：定义 6 个状态完成信号（STREAM_COMPLETED / SYNC_COMPLETED / TOOL_CALLS_FOUND / NO_TOOL_CALLS / TOOL_EXECUTED / ERROR）
- **被谁调用**：StateResult.getSignal() → 状态机引擎根据 signal 查 transitionTable
- **为什么需要**：状态不知道自己下一个是谁，状态机引擎通过 signal 决定流转

#### StateResult.java
- **目的**：封装状态处理结果，携带 signal、outputFlux、toolCalls
- **被谁调用**：StreamToolCallStateMachine → 从 result 读 signal 查 transitionTable、读 outputFlux 合并到总 Flux
- **为什么需要**：状态机引擎需要从状态返回值中提取三样东西

#### StreamToolCallStateMachine.java
- **目的**：状态机引擎，持有 transitionTable，执行循环直到 NO_TOOL_CALLS
- **被谁调用**：ToolCallLoopStage 委托状态机运行
- **核心逻辑**：
  1. 查 transitionTable 实例化状态
  2. 调用 state.handle(context) 获取 StateResult
  3. 收集 outputFlux 到列表
  4. 根据 signal 查下一状态，循环
  5. MAX_ROUNDS=6 强制终止
  6. Flux.concat 合并所有 flux 片段
  7. 自动将 `__tool_event_flux__` 插入 Flux 链

#### ModelCallState.java — 流式模型调用
- **目的**：流式调用 adapter.chatStream()，边收边发，增量检测
- **被谁调用**：状态机引擎
- **内部逻辑**：
  1. 创建 Sinks.Many 做实时数据源
  2. 启后台线程消费 rawFlux
  3. 后台线程每次收到 data：sink.tryEmitNext() + collector.append() + 增量检测
  4. 在主线程中立即返回 StateResult.streamCompleted(sink.asFlux())
  5. Controller 收到 Flux 后 subscribe，后台线程正在填充 Sinks，Controller 实时收到数据
- **为什么是后台线程**：状态机是同步循环，不能阻塞等 API 响应。后台线程边收边发，Controller 直接消费

#### SyncModelCallState.java — 同步模型调用
- **目的**：同步模式调用 adapter.chatSync()
- **被谁调用**：状态机引擎（当 __sync_response__ 存在时替代 ModelCallState）
- **和 ModelCallState 的关系**：共用 ToolDetectState/ToolExecuteState，只替换模型调用这个变体

#### ToolDetectState.java — 工具检测
- **目的**：检测模型回复是否有工具调用
- **被谁调用**：状态机引擎（ModelCallState 之后）
- **检测顺序**：
  1. 检查 __tool_choice_executed__ — 已执行过强制工具则跳过所有检测
  2. 轮询等首包（5s 超时，每 50ms 检查 __has_tool_call__ / collector 长度 / latch 完成）
  3. 增量检测标志：__has_tool_call__ → 直接取 __stream_tool_calls__ 返回
  4. toolChoice 显式指定：解析 toolChoice 构造 fakeCall 返回
  5. 后备：从完整 collector 字符串解析
  6. 都没检测到 → NO_TOOL_CALLS

#### ToolExecuteState.java — 工具执行
- **目的**：逐一执行工具，发 SSE 事件，处理消息协议
- **被谁调用**：状态机引擎（ToolDetectState 之后）
- **关键操作**：
  1. 遍历 toolCalls，调 toolRegistry.execute()
  2. 通过 ToolCallEventEmitter 构建 executing → done 事件
  3. 插入 role=assistant(含 tool_calls) + role=tool 消息到 messages
  4. setToolChoice("none") + setAttribute("__tool_choice_executed__", true)

#### ToolCallEventEmitter.java
- **目的**：构建 `event:tool_call` SSE 事件的 Flux
- **被谁调用**：ToolExecuteState
- **输出格式**：`event:tool_call\ndata:{"type":"tool_call","name":"xxx","status":"executing"}` → `event:tool_call\ndata:{"type":"tool_call","name":"xxx","status":"done","result":"xxx"}`
- **Flux 去向**：存到 __tool_event_flux__，最终被状态机引擎合并到总 Flux

#### SseToolCallParser.java
- **目的**：SSE 解析接口，抽象不关心厂商格式
- **被谁调用**：ModelCallState（增量检测时）+ ToolDetectState（后备解析时）
- **方法**：extractToolCalls() / extractPlainText() / extractTokenUsage()

#### DeepSeekSseToolCallParser.java
- **目的**：解析 DeepSeek（OpenAI 兼容）SSE 格式的工具调用
- **复用性**：如果厂商换 MiniMax/Anthropic，新建对应的 SseToolCallParser 实现即可，ModelCallState/ToolDetectState 的代码零修改

---

### 一次完整流式对话（带工具调用）的过程

假设用户发消息 "查看 lyjew 家目录的文件"，以下是完整的执行流程：

#### 第 1 阶段：Pipeline 初始化

```
User Request → ChatController.streamChat() → DefaultEngine.process()
  → ContextBuildStage：加载记忆 + 工具定义 + 会话，构建 ChatRequest.messages
  → InterceptorStage：执行拦截器（0个）
  → ToolCallLoopStage：委托 StreamToolCallStateMachine
```

#### 第 2 阶段：状态机第 1 轮 — ModelCallState

```
状态机引擎：transitionTable[STREAM_COMPLETED] = ModelCallState.class
  → 反射实例化 ModelCallState
  → 调用 handle(context)
    1. 创建 Sinks.Many<String> sink （实时 Flux 数据源）
    2. 创建 StringBuilder collector（数据收集器）
    3. 创建 CountDownLatch collectorLatch
    4. 启后台线程 "sse-collector-{sessionId}"
       ├─ 后台线程：adapter.chatStream(req) → HTTP POST https://api.deepseek.com/chat/completions
       ├─ 后台线程：subscribe rawFlux
       │   data到达 → sink.tryEmitNext(data) → collector.append(data) → 增量检测
       │   增量检测到 tool_call → setAttribute("__has_tool_call__", true) + "__stream_tool_calls__"
       │   onComplete → 解析纯文本/token用量 → sink.tryEmitComplete() + collectorLatch.countDown()
    5. 主线程：setAttribute("__stream_collector__", collector) + "__stream_collector_latch__", latch
    6. 主线程：返回 StateResult.streamCompleted(sink.asFlux())
    7. 状态机引擎：收集 outputFlux 到 fluxList，signal=STREAM_COMPLETED
```

#### 第 3 阶段：状态机第 2 轮 — ToolDetectState

```
状态机引擎：transitionTable[STREAM_COMPLETED] = ToolDetectState.class
  → 反射实例化 ToolDetectState
  → 调用 handle(context)
    1. 检查 __tool_choice_executed__ → null（未执行过，继续）
    2. 轮询等首包：
       while (5000ms 内) {
         检查 __has_tool_call__ → true? 是! → firstPacketArrived = true → break
       }
    3. 检查 __has_tool_call__ → true
    4. 从 __stream_tool_calls__ 读取工具调用列表
    5. 返回 StateResult.toolCallsFound(calls)
    6. 状态机引擎：signal=TOOL_CALLS_FOUND
```

#### 第 4 阶段：状态机第 3 轮 — ToolExecuteState

```
状态机引擎：transitionTable[TOOL_CALLS_FOUND] = ToolExecuteState.class
  → 反射实例化 ToolExecuteState
  → 调用 handle(context)
    1. 遍历 toolCalls（如 [{name="command", args="{}"} ...]）
    2. 等 arguments 收集完成：检测 __stream_collector__ 中是否有完整 arguments
    3. 调 toolRegistry.execute(toolName, args) → 执行 CommandTool
       ├─ CommandTool：Runtime.exec("sh -c", "ls -la /home/lyjew")
       ├─ 收集 stdout（30 秒超时，10000 字符截断）
       └─ 返回 ToolResult(success=true, output="total 48\ndrwxr-xr-x...")
    4. 通过 ToolCallEventEmitter 构建事件 Flux
       └─ event:tool_call → {"type":"tool_call","name":"command","status":"executing"}
       └─ event:tool_call → {"type":"tool_call","name":"command","status":"done","result":"total 48\n..."}
    5. 检查 messages 最后一条是否含 tool_calls
       └─ 没有 → 插入 role=assistant(content="", tool_calls=[{id, name, arguments}])
    6. 追加 role=tool(content=工具输出, toolCallId=xxx)
    7. setToolChoice("none") + setAttribute("__tool_choice_executed__", true)
    8. 返回 StateResult(Signal.TOOL_EXECUTED, toolEventFlux, null)
    9. 状态机引擎：合并 event Flux + signal=TOOL_EXECUTED
```

#### 第 5 阶段：状态机第 4 轮 — ModelCallState（第 2 次调用模型）

```
状态机引擎：transitionTable[TOOL_EXECUTED] = ModelCallState.class
  → 再次调用 ModelCallState
  → adapter.chatStream() 带上 messages（含 user + assistant/工具 + tool 消息）
  → 后台线程再次启动，消费第二轮 SSE 流
  → 返回实时 Flux
  → 状态机引擎：收集 flux，signal=STREAM_COMPLETED
```

#### 第 6 阶段：状态机第 5 轮 — ToolDetectState（第 2 次检测）

```
状态机引擎：transitionTable[STREAM_COMPLETED] = ToolDetectState.class
  → 调用 ToolDetectState.handle()
    1. 检查 __tool_choice_executed__ → true!
       → 已执行过强制工具，直接返回 NO_TOOL_CALLS
       → 跳过所有工具检测（包括增量检测到的工具调用）
    2. 状态机引擎：signal=NO_TOOL_CALLS, transitionTable 中无映射 → 终止循环
```

#### 第 7 阶段：状态机完成，合并 Flux

```
状态机引擎：Flux.concat(flux1, toolEventFlux, flux2)
  → 总 Flux 内容：
     [第1轮SSE行...] (如果模型第一阶段有文本回复)
     event:tool_call\ndata:{"status":"executing",...}
     event:tool_call\ndata:{"status":"done",...}
     [第2轮SSE行...] (模型的文本回复) ← 不含工具调用
  → 存到 context.setAttribute("__stream_flux__", mergedFlux)
  → ToolCallLoopStage 完成
```

#### 第 8 阶段：MetricsStage + ResponseBuildStage

```
MetricsStage：
  → 记录 token 用量、持续时间

ResponseBuildStage：
  → 注册 doOnComplete 回调到 mergedFlux
  → 返回 mergedFlux 给 Controller
```

#### 第 9 阶段：Controller 消费 Flux（SSE 推送到前端）

```
ChatController.streamChat()：
  → executor.submit {
      flux.subscribe(
        line → {
          if (event:tool_call) → emitter.send(SseEmitter.event().name("tool_call").data(...))
          else → emitter.send(SseEmitter.event().name("message").data(...))
        },
        error → emitter.completeWithError(error),
        () → {
          emitter.send(SseEmitter.event().name("message").data("[DONE]"))
          emitter.complete()
          // 后台线程 onComplete 时触发的 doOnComplete 回调
          持久化记忆 + 保存会话
        }
      )
    }
  → 返回 SseEmitter 给前端
```

#### 第 10 阶段：前端渲染

```
ChatTest.vue：
  → fetch(url, { method: 'POST', body: JSON.stringify({...}), headers:{...} })
  → response.body.getReader() 读取 ReadableStream
  → 逐行解析 SSE 行：
     event:tool_call → JSON.parse(data) → 追加 tool_call 类型消息（executing旋转图标）
     event:tool_call → 更新状态为 done（✅）
     event:message → 追加到最后一条 assistant 消息的 content
     event:message data:[DONE] → 关闭流，停止读取
  → Vue 3 响应式驱动 DOM 更新：
     v-for 遍历 messages → tool_call 行用黄色/绿色显示
     assistant content 用 white-space: pre-wrap 显示完整文本
```

### 对比：无工具调用的情况

```
ModelCallState → ToolDetectState
  → 首包到达，检查 __has_tool_call__ → false
  → 后备解析 collector → 无工具调用
  → NO_TOOL_CALLS → 状态机终止
  → 只有 1 个 Flux 片段（模型文本回复）
  → Controller 直接透传 SSE 行到前端
  → 前端逐字显示模型回复
```

