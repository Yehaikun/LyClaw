# LyClaw 设计模式全面分析报告

> 分析对象：`/home/lyjew/Documents/Unicom/LyClaw` — 企业级多Agent AI应用框架
> 分析日期：2026-05-22
> Java文件总数：484个（排除worktree）
> 模块数量：8个（lyclaw-framework, lyclaw-autoconfigure, lyclaw-web, lyclaw-action, lyclaw-memory, lyclaw-plan, lyclaw-protocol, lyclaw-reflect）
> 分析方法：逐文件阅读 + 3路并行Agent探索 + 交叉验证

---

## 引言

LyClaw 是一个基于 Java 21 + Spring Boot 3.x 构建的企业级多Agent AI应用框架。通过对全部 484 个 Java 源文件的系统分析，本文档提取了项目中使用的 **28种设计模式**（含14种GoF设计模式 + 14种架构级/Java特有模式），逐个讲解每种模式的：
- 定义与意图（该项目中的具体含义）
- 代码实例（文件路径 + 行号 + 关键代码片段）
- 解决的问题（为什么用它）
- 架构定位（如何与其它模式协作）

设计模式按四个层次组织：
1. **创建型模式**（5种）：如何创建对象和Agent
2. **结构型模式**（7种）：如何组织类和对象
3. **行为型模式**（10种）：如何协调对象间的交互
4. **架构级模式**（6种）：框架级别的设计范式

---

## 第一部分：创建型模式

### 模式1：工厂方法 / 简单工厂（Factory Method / Simple Factory）

#### 定义与意图
提供一个创建对象的接口，但让子类决定实例化哪个类。在 LyClaw 中，工厂方法将对象创建的复杂性集中到一处，使调用方无需了解构造细节。

#### 代码实例

**实例1：ThreadPoolFactory — 线程池简单工厂**

文件：`lyclaw-framework/src/main/java/lyjew/com/lyclaw/util/ThreadPoolFactory.java`（第11-37行）

```java
public final class ThreadPoolFactory {
    private ThreadPoolFactory() {}  // 不可实例化

    public static ExecutorService fixed(String poolName, int size) {
        return Executors.newFixedThreadPool(size, daemonFactory(poolName));
    }

    public static ExecutorService virtual(String poolName) {
        ThreadFactory factory = Thread.ofVirtual()
            .name(poolName + "-", 1)
            .factory();
        return Executors.newThreadPerTaskExecutor(factory);
    }

    private static ThreadFactory daemonFactory(String prefix) {
        AtomicInteger counter = new AtomicInteger(1);
        return r -> {
            Thread t = new Thread(r, prefix + "-" + counter.getAndIncrement());
            t.setDaemon(true);
            return t;
        };
    }
}
```

**为什么用它**：框架中多处需要线程池（异步工具执行、SSE推送、JSONL写入），如果各处自行创建会导致线程池配置不一致、线程命名混乱、难以统一管理。`ThreadPoolFactory` 集中了所有线程创建逻辑，确保所有线程都是守护线程（不阻塞JVM退出）、有统一的命名规范（便于jstack调试）、支持虚拟线程（Java 21+新特性）。

**实例2：SessionFactory — 依赖倒置的抽象工厂**

文件：`lyclaw-framework/src/main/java/lyjew/com/lyclaw/persistence/SessionFactory.java`（第1-27行）

```java
public interface SessionFactory {
    Session createSubagentSession(
        String parentSessionId, String parentAgentId,
        String childAgentId, String model);
    int getActiveCount(String agentId);
}
```

实现位于 `lyclaw-web/src/main/java/lyjew/com/lyclaw/web/session/SessionManager.java`。`StorageAutoConfiguration` 完成桥接：

```java
@Bean
SessionFactory sessionFactory(SessionManager sessionManager) {
    return sessionManager;
}
```

**为什么用它**：`SubagentSpawner`（位于 lyclaw-framework）需要创建子会话，但 `SessionManager`（位于 lyclaw-web）拥有会话生命周期管理。框架层不能依赖Web层（会造成循环依赖）。通过将工厂接口放在框架层、实现放在Web层，完美实现了**依赖倒置原则（DIP）**——高层模块不依赖低层模块，两者都依赖抽象。

#### 架构定位
`ThreadPoolFactory` 被 `ActionExecutorImpl`、`DefaultReActEngine`、`AsyncWriteQueue` 等类使用。`SessionFactory` 被 `SubagentSpawner` 使用。两个工厂互不依赖。

---

### 模式2：抽象工厂（Abstract Factory）

#### 定义与意图
提供一个创建一系列相关或相互依赖对象的接口，而无需指定它们具体的类。在 LyClaw 中，`AgentProxyFactory` 创建完整的代理Agent及其所有依赖组件。

#### 代码实例

**AgentProxyFactory — JDK动态代理工厂**

文件：`lyclaw-framework/src/main/java/lyjew/com/lyclaw/react/AgentProxyFactory.java`（第78-111行）

```java
public <T> T create(Class<T> agentInterface) {
    // 1. 读取 @Agent 注解
    Agent ann = agentInterface.getAnnotation(Agent.class);

    // 2. 3级优先级解析配置: @Agent > YAML > system defaults
    ResolvedAgentConfig resolvedConfig = configResolver != null
        ? configResolver.resolve(agentInterface)
        : ResolvedAgentConfig.fromAnnotation(ann);

    // 3. 解析模型/提供商/SystemPrompt（支持覆盖）
    String model = firstNonEmpty(modelOverride,
        resolvedConfig.getModel(), ann.model());
    String provider = firstNonEmpty(providerOverride,
        resolvedConfig.getProvider(), ann.provider());
    String systemPrompt = firstNonEmpty(defaultSystemPrompt,
        resolvedConfig.getSystemPrompt(), ann.systemPromptOverride());

    // 4. 构建调用处理器
    AgentInvocationHandler handler = new AgentInvocationHandler(
        chatFacade, reActEngine, toolRegistry, systemPrompt,
        model, provider, hooks, stages, resolvedConfig);

    // 5. 创建JDK动态代理
    return (T) Proxy.newProxyInstance(
        agentInterface.getClassLoader(),
        new Class<?>[]{agentInterface},
        handler);
}
```

这个工厂有7个重载构造函数，支持灵活配置（可选的 `defaultSystemPrompt`、`modelOverride`、`providerOverride`、`List<AgentHook>`、`List<ReactivePipelineStage>`、`AgentConfigResolver`）。

**为什么用它**：创建Agent涉及6个以上依赖组件的装配（ChatFacade、ReActEngine、ToolRegistry、SystemPrompt、Model、Provider、Hooks、Stages、Config）。如果让调用方自行组装，代码会充斥着样板式的依赖组装。抽象工厂封装了复杂的创建逻辑，对外暴露一个简单的 `create(Class<T>)` 接口。

#### 架构定位
工厂位于Agent系统的入口。`AgentInterfaceProcessor`（BeanPostProcessor）扫描所有 `@Agent` 注解的接口，对每个接口调用 `AgentProxyFactory.create()`，将返回的代理对象注册为Spring Bean。这是 LyClaw 的**核心启动机制**。

---

### 模式3：构建器（Builder）

#### 定义与意图
将一个复杂对象的构建与它的表示分离，使同样的构建过程可以创建不同的表示。在 LyClaw 中，Builder 分为两类：Lombok自动生成的和手工编写的。

#### 代码实例

**实例1：Lombok @Builder — 10+个Model类**

所有数据模型类使用 Lombok `@Builder` 注解自动生成建造器。代表：`ChatRequest`。

文件：`lyclaw-framework/src/main/java/lyjew/com/lyclaw/model/ChatRequest.java`

```java
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChatRequest {
    private String sessionId;
    private String agentId;
    @Builder.Default
    private List<Message> messages = new ArrayList<>();
    @Builder.Default
    private Map<String, Object> metadata = new HashMap<>();
    private boolean stream;
    // ... 16个字段，集合和基本类型都有 @Builder.Default
}
```

使用方式：
```java
ChatRequest request = ChatRequest.builder()
    .sessionId("sess-123")
    .agentId("agent-456")
    .messages(messages)
    .stream(true)
    .temperature(0.7)
    .build();
```

其他使用 `@Builder` 的类：`ModelResponse`、`ToolDefinition`、`Message`（使用 `@SuperBuilder`）、`Session`（使用 `@SuperBuilder`）、`MemoryConsolidationPolicy`、`TemporalProps`、`AgentEvent`、`ConsensusResult`、`VoteResult`。

**实例2：手工Builder — ResolvedAgentConfig**

文件：`lyclaw-framework/src/main/java/lyjew/com/lyclaw/config/ResolvedAgentConfig.java`

由于需要合并3个配置源（`@Agent` 注解、YAML配置、系统默认值），且结果需要是不可变的，框架手工编写了Builder：

```java
public class ResolvedAgentConfig {
    // 28+ 个 final 字段
    private final String agentId;
    private final String agentName;
    private final String model;
    private final String provider;
    // ...

    private ResolvedAgentConfig(Builder builder) {
        this.agentId = builder.agentId;
        this.agentName = builder.agentName;
        // ...
    }

    public static Builder builder() { return new Builder(); }

    public static class Builder {
        private String agentId;
        private String agentName;
        // 28+ 个字段...

        public Builder agentId(String v) { this.agentId = v; return this; }
        public Builder agentName(String v) { this.agentName = v; return this; }
        // 每个字段一个流式setter...

        public ResolvedAgentConfig build() {
            return new ResolvedAgentConfig(this);
        }
    }
}
```

**为什么不用Lombok**：`ResolvedAgentConfig` 的构建逻辑需要在 `build()` 方法中对集合字段进行 `Collections.unmodifiableList()`/`unmodifiableMap()` 封装以确保不可变性，Lombok的 `@Builder` 做不到这一点。

**实例3：Fluent Builder — DefaultChatRequestBuilder**

文件：`lyclaw-framework/src/main/java/lyjew/com/lyclaw/chat/DefaultChatFacade.java`（第172-266行）

这是嵌套在 `DefaultChatFacade` 内部的私有建造器，实现了 `ChatClient.ChatRequestBuilder` 接口：

```java
private class DefaultChatRequestBuilder implements ChatClient.ChatRequestBuilder {
    private String userMessage;
    private String systemMessage;
    private List<Message> messages;
    private List<ToolDefinition> tools;
    private double temperature = 0.7;
    private int maxTokens = 4096;
    private boolean thinking = false;

    public ChatRequestBuilder user(String message) {
        this.userMessage = message; return this;
    }
    public ChatRequestBuilder system(String prompt) {
        this.systemMessage = prompt; return this;
    }
    public ChatRequestBuilder temperature(double t) {
        this.temperature = t; return this;
    }
    public ChatRequestBuilder maxTokens(int tokens) {
        this.maxTokens = tokens; return this;
    }
    // ...

    // 终止操作
    public ModelResponse call() { /* buildRequest() → chat() */ }
    public Flux<ModelResponse> stream() { /* buildRequest() → stream() */ }
}
```

#### 架构定位
Builder 模式在 LyClaw 中无处不在。Lombok `@Builder` 用于简单数据类，手工Builder用于需要特殊构建逻辑（不可变性、配置合并、终止操作）的类，Fluent Builder用于面向用户的API。

---

### 模式4：单例（Singleton — Spring 容器管理）

#### 定义与意图
确保一个类只有一个实例，并提供一个全局访问点。在 LyClaw 中，单例完全由 Spring IoC 容器管理，没有传统的 `private 构造函数 + getInstance()`。

#### 代码实例

**实例1：组件扫描单例（@Component / @Service）**

21个关键单例Bean：

| Bean | 注解 | 作用 |
|------|------|------|
| `ExponentialDecayFunction` | `@Component` | 指数衰减函数 |
| `PowerLawDecayFunction` | `@Component` | 幂律衰减函数 |
| `EngineSelector` | `@Component` | 引擎自动发现与选择 |
| `InterceptorChain` | `@Component` | 拦截器链管理 |
| `ApprovalStore` | `@Component` | 工具审批状态存储 |
| `DefaultMemoryConsolidator` | `@Component` | 记忆整合引擎 |
| `DefaultMemoryJanitor` | `@Component` | 记忆清理引擎 |
| `SimpleEmbeddingModel` | `@Component` | 嵌入向量生成 |
| `InMemoryVectorStore` | `@Component` | 内存向量存储 |
| `HybridMemoryRetriever` | `@Component` | 混合检索器 |
| `LLMMemoryExtractor` | `@Component` | LLM记忆提取器 |
| `TieredMemorySystem` | `@Service` | 分层记忆系统 |
| `LLMTaskDecomposer` | `@Component` | LLM任务分解器 |
| `CoTPlanner` | `@Service("cotPlanner")` | 链式思考规划器 |
| `DAGTaskPlanner` | `@Service` | DAG任务规划器 |
| `ReActPlanner` | `@Service("reActPlanner")` | ReAct规划器 |
| `HierarchicalPlanner` | `@Service("hierarchicalPlanner")` | 层次化规划器 |
| `HybridPlanner` | `@Service` | 混合规划器 |

**实例2：@Bean 声明的单例**

文件：`lyclaw-web/src/main/java/lyjew/com/lyclaw/web/config/StorageAutoConfiguration.java`

```java
@Bean
SqliteConnectionManager sqliteConnectionManager() {
    return new SqliteConnectionManager(storageProperties);
}

@Bean
SessionManager sessionManager(...) { return new SessionManager(...); }

@Bean
JsonlWriter jsonlWriter() { return new DefaultJsonlWriter(); }

@Bean
JsonlReader jsonlReader() { return new DefaultJsonlReader(); }
```

**实例3：枚举单例 — StageGroup**

文件：`lyclaw-framework/src/main/java/lyjew/com/lyclaw/constant/StageGroup.java`

```java
public enum StageGroup {
    PREPROCESSING,  // 预处理阶段（上下文构建、安全检查）
    CORE,           // 核心阶段（计划执行、响应生成）
    POSTPROCESSING  // 后处理阶段（反思、指标收集）
}
```

Java枚举天生是单例。`StageGroup` 将Pipeline阶段分成三个功能组，用于 `@PipelineStage` 注解的 `group` 属性。

#### 架构定位
Spring IoC容器是 LyClaw 的单例管理基础设施。所有 `@Component`/`@Service`/`@Repository`/`@Bean` 默认都是单例。框架没有使用传统的 `getInstance()` 单例，而是通过依赖注入获取单例引用。

---

### 模式5：原型（Prototype — 间接使用）

#### 定义与意图
用原型实例指定创建对象的种类，并通过拷贝这些原型创建新的对象。在 LyClaw 中，`AgentContext.toSnapshot()` 和 `restoreFromSnapshot()` 实现了Memento模式的快照/恢复机制，同时也隐含原型模式的语义——从快照恢复出新的上下文状态。

#### 架构定位
虽然 LyClaw 没有显式的 `clone()` 原型模式，但 `AgentContext.toSnapshot()` / `restoreFromSnapshot()` 的语义与原型模式高度重叠：（详见行为型模式中的 **模式14：备忘录**）。

---

## 第二部分：结构型模式

### 模式6：适配器（Adapter）

#### 定义与意图
将一个类的接口转换成客户期望的另一个接口。适配器让原本因接口不兼容而不能一起工作的类可以协同工作。在 LyClaw 中，适配器模式是**最普遍的结构型模式**，遍布于每一个外部集成边界。

#### 代码实例

**实例1：OpenAiProtocolChatModel — 协议适配器（对象适配器）**

文件：`lyclaw-framework/src/main/java/lyjew/com/lyclaw/adapter/OpenAiProtocolChatModel.java`（第175-350行）

这是 LyClaw 中**最重要的适配器**——将 OpenAI 兼容的 HTTP+SSE 协议适配为框架内部的 `ChatModel` 接口。

```java
public class OpenAiProtocolChatModel extends AbstractChatModel {

    // 请求侧适配：框架 ChatRequest → OpenAI API JSON
    @Override
    protected Object buildNativeRequest(ChatRequest request) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", getModelName());
        body.put("messages", request.getMessages().stream()
            .map(this::convertMessage).toList());
        body.put("stream", request.isStream());
        body.put("tools", request.getTools());
        body.put("temperature", request.getTemperature());
        return body;
    }

    // 传输：HTTP POST → /chat/completions → SSE Flux<String>
    @Override
    protected Flux<String> sendNativeRequest(Object nativeRequest) {
        return webClient.post()
            .uri("/chat/completions")
            .bodyValue(nativeRequest)
            .retrieve()
            .bodyToFlux(String.class);
    }

    // 响应侧适配：SSE "data: {...}" → 统一 ModelResponse
    @Override
    protected ModelResponse parseChunk(String chunk) {
        // 解析 JSON → 提取 content / tool_calls / finish_reason / usage
        // 返回统一的 ModelResponse 对象
    }
}
```

**为什么用它**：AI行业85%以上的模型提供商（OpenAI、DeepSeek、Groq、Together AI、OpenRouter等）都采用 OpenAI 兼容的 API 格式。通过这一个适配器，所有兼容提供商无需编写Java代码——只需在YAML中配置 `baseUrl` 和 `apiKey` 即可接入。

**实例2：DeepSeekChatModel — 特化适配器（继承自 OpenAiProtocolChatModel）**

文件：`lyclaw-framework/src/main/java/lyjew/com/lyclaw/adapter/DeepSeekChatModel.java`（第1-124行）

```java
@ChatModel(name = "deepseek", defaultBaseUrl = "https://api.deepseek.com")
@ModelCapability(streaming = true, thinking = true, toolCalling = true,
    toolCallStreaming = false)
@RetryPolicy(
    strategy = RetryStrategy.EXPONENTIAL, maxAttempts = 3,
    retryOn = {429, 503, 504})
@CircuitBreaker(failureThreshold = 5, halfOpenAfterSeconds = 30)
public class DeepSeekChatModel extends OpenAiProtocolChatModel {

    @Override
    protected String getDefaultBaseUrl() {
        return "https://api.deepseek.com";
    }

    @Override
    protected String getDefaultModel() {
        return "deepseek-v4-flash";
    }
}
```

仅需重写2个方法即可完成新提供商的接入——整个协议适配由父类继承。注解驱动的声明式配置（`@ChatModel`、`@ModelCapability`、`@RetryPolicy`、`@CircuitBreaker`）由 `ChatModelPostProcessor` 在容器启动时自动处理。

**实例3：AnnotatedToolAdapter — 注解POJO到Tool接口的适配器**

文件：`lyclaw-autoconfigure/src/main/java/lyjew/com/lyclaw/autoconfigure/binding/AnnotatedToolAdapter.java`（第74-89行）

```java
public class AnnotatedToolAdapter implements Tool {
    private final Object target;
    private final Method executeMethod;
    private final ParameterBindingDescriptor bindingDescriptor;

    @Override
    public ToolExecutionResult execute(ToolCall toolCall, ChatContext context) {
        try {
            Map<String, Object> args = ParameterBinder.bindToMap(
                toolCall.getArguments());
            Object result = bindingDescriptor.bindAndInvoke(args);
            return ToolExecutionResult.success(result);
        } catch (Exception e) {
            return ToolExecutionResult.failure(e.getMessage());
        }
    }
}
```

**为什么用它**：开发者用 `@Tool` 注解标注普通的Java方法，框架通过 `AnnotatedToolAdapter` 将其适配为统一的 `Tool` 接口。`ToolAnnotationProcessor` 在启动时扫描所有 `@Tool` 方法，为每个方法创建一个 `AnnotatedToolAdapter` 实例并注册到 `ToolRegistry`，使下游Pipeline可以统一调用。

**实例4：McpToolAdapter — MCP远程工具适配器**

文件：`lyclaw-action/src/main/java/lyjew/com/lyclaw/action/impl/McpToolAdapter.java`（第96-137行）

```java
public class McpToolAdapter implements Tool {
    private final String mcpEndpoint;
    private final McpToolDescriptor descriptor;
    private final HttpClient httpClient;

    @Override
    public ToolExecutionResult execute(ToolCall toolCall, ChatContext context) {
        // 构建 JSON-RPC 2.0 请求
        String jsonRpcBody = buildJsonRpcRequest(toolCall);
        // POST 到 MCP endpoint
        HttpResponse<String> response = httpClient.send(...);
        // 解析 JSON-RPC 响应
        return parseJsonRpcResponse(response.body());
    }
}
```

**为什么用它**：MCP（Model Context Protocol）是新兴的AI工具互操作标准。通过适配器模式，LyClaw 可以透明地调用远程MCP服务器提供的工具，就像调用本地工具一样。

**实例5：ToolToSkillAdapter — 接口兼容适配器**

文件：`lyclaw-action/src/main/java/lyjew/com/lyclaw/action/skill/ToolToSkillAdapter.java`（第82-101行）

将新的 `Tool` 接口适配为旧的 `Skill` 接口，实现向后兼容：

```java
public class ToolToSkillAdapter implements Skill {
    private final Tool tool;

    @Override
    public SkillResult execute(ChatContext context) {
        ToolCall toolCall = buildToolCallFromContext(context);
        ToolExecutionResult result = tool.execute(toolCall, context);
        return new SkillResult(result.isSuccess(), result.getOutput(), ...);
    }
}
```

**附加适配器**：
- `ParameterBinder`（`autoconfigure/binding/ParameterBinder.java`）：将LLM输出的JSON字符串适配为Java Map
- `ParameterBindingDescriptor`（`autoconfigure/binding/ParameterBindingDescriptor.java`）：将命名的Map参数适配为Java方法的位置参数
- `ExternalAgentAdapter`（`agent/external/ExternalAgentAdapter.java`）：将外部Agent的HTTP/gRPC协议适配为统一的本地Agent接口
- `@Adapter` 注解（`annotation/Adapter.java`）：声明式标记适配器提供者

#### 架构定位
适配器模式在 LyClaw 中服务于"统一接口"的设计原则。框架内部只认识 `ChatModel`、`Tool`、`Skill` 等接口，外部的多样性（不同AI协议、不同工具格式、不同Agent通信协议）全部通过适配器转化为内部统一接口。

---

### 模式7：装饰器（Decorator）

#### 定义与意图
动态地给一个对象添加额外的职责。装饰器提供了一种比继承更灵活的扩展功能方式。在 LyClaw 中，装饰器模式用于两大场景：AI模型调用的弹性增强和工具执行的Hook注入。

#### 代码实例

**装饰器链组装点**

文件：`lyclaw-autoconfigure/src/main/java/lyjew/com/lyclaw/autoconfigure/processor/ChatModelPostProcessor.java`（第158-186行）

```java
private ChatModel applyDecorators(ChatModel original, Class<?> clazz) {
    ChatModel wrapped = original;

    // Layer 3 — 降级（最内层，先应用）
    if (clazz.isAnnotationPresent(Fallback.class)) {
        Fallback fb = clazz.getAnnotation(Fallback.class);
        wrapped = new FallbackChatModel(wrapped, fb, registry);
    }

    // Layer 2 — 重试（中间层）
    if (clazz.isAnnotationPresent(RetryPolicy.class)) {
        RetryPolicy rp = clazz.getAnnotation(RetryPolicy.class);
        wrapped = new RetryChatModel(wrapped, rp);
    }

    // Layer 1 — 熔断（最外层）
    if (clazz.isAnnotationPresent(CircuitBreaker.class)) {
        CircuitBreaker cb = clazz.getAnnotation(CircuitBreaker.class);
        wrapped = new CircuitBreakerChatModel(wrapped, cb);
    }

    return wrapped;  // 返回的是完整的装饰器链
}
```

最终结构：
```
CircuitBreakerChatModel       ← 最外层：熔断保护
  └─ RetryChatModel           ← 中间层：自动重试
      └─ FallbackChatModel    ← 最内层：模型降级
          └─ 原始 ChatModel   ← 核心：实际的AI调用
```

**装饰器1：CircuitBreakerChatModel — 熔断装饰器（状态机）**

文件：`lyclaw-framework/src/main/java/lyjew/com/lyclaw/decorator/CircuitBreakerChatModel.java`（第116-157行）

```java
public class CircuitBreakerChatModel implements ChatModel {
    private final ChatModel delegate;
    private final AtomicReference<String> state =
        new AtomicReference<>(STATE_CLOSED);
    private final AtomicInteger failureCount = new AtomicInteger(0);

    @Override
    public Flux<ModelResponse> stream(ChatRequest request) {
        String currentState = checkAndTransitionState();
        return switch (currentState) {
            case STATE_CLOSED ->
                delegate.stream(request)                // 正常转发
                    .doOnNext(chunk -> failureCount.set(0))
                    .doOnError(error -> {
                        if (failureCount.incrementAndGet() >= threshold)
                            state.set(STATE_OPEN);       // 熔断！
                    });
            case STATE_HALF_OPEN ->
                delegate.stream(request)                // 探测请求
                    .doOnNext(chunk -> {
                        state.set(STATE_CLOSED);         // 恢复
                        failureCount.set(0);
                    })
                    .doOnError(error -> /* maybe re-open */);
            default ->  // STATE_OPEN
                Flux.error(new IllegalStateException(
                    "CircuitBreaker已熔断")); // 快速失败
        };
    }
}
```

状态转换：CLOSED（正常）→ OPEN（熔断）→ HALF_OPEN（半开探测）→ CLOSED（恢复）或 OPEN（再次熔断）。使用 `AtomicReference<String>` + `compareAndSet` 保证线程安全。

**装饰器2：RetryChatModel — 重试装饰器**

文件：`lyclaw-framework/src/main/java/lyjew/com/lyclaw/decorator/RetryChatModel.java`（第124-158行）

```java
public class RetryChatModel implements ChatModel {
    private final ChatModel delegate;
    private final int maxAttempts;

    @Override
    public Flux<ModelResponse> stream(ChatRequest request) {
        return streamWithRetry(request, 1);
    }

    private Flux<ModelResponse> streamWithRetry(
            ChatRequest request, int attempt) {
        return delegate.stream(request)
            .onErrorResume(error -> {
                if (attempt < maxAttempts) {
                    long delay = computeDelay(attempt); // 指数/FIXED/LINEAR + jitter
                    return Mono.delay(Duration.ofMillis(delay))
                        .thenMany(streamWithRetry(request, attempt + 1));
                }
                return Flux.error(error);
            });
    }

    private long computeDelay(int attempt) {
        // FIXED / EXPONENTIAL / LINEAR 三种退避策略
        // 加随机 jitter 避免雷群效应
    }
}
```

**装饰器3：FallbackChatModel — 降级装饰器**

文件：`lyclaw-framework/src/main/java/lyjew/com/lyclaw/decorator/FallbackChatModel.java`（第106-173行）

```java
public class FallbackChatModel implements ChatModel {
    private final ChatModel delegate;
    private final String[] chain; // ["openai:gpt-4o", "deepseek:v4"]

    @Override
    public Flux<ModelResponse> stream(ChatRequest request) {
        return delegate.stream(request)
            .onErrorResume(error -> {
                if (shouldFallback(error))
                    return tryNextInChain(request, 0);
                return Flux.error(error);
            });
    }

    private Flux<ModelResponse> tryNextInChain(
            ChatRequest request, int index) {
        if (index >= chain.length)
            return Flux.error(/* 所有模型都不可用 */);
        ChatModel fallbackModel = registry.resolve(provider, model);
        return fallbackModel.stream(request)
            .onErrorResume(err -> tryNextInChain(request, index + 1));
    }
}
```

**为什么用它**：AI模型调用是网络I/O密集型操作，面临超时、限流、服务不可用等故障。通过装饰器链，弹性能力（降级、重试、熔断）与核心业务逻辑（AI调用）完全解耦。每个装饰器只关注一个弹性维度，符合**单一职责原则**。

**装饰器4：AgentHook.wrapToolExecutor — 运行时工具执行装饰器链**

文件：`lyclaw-framework/src/main/java/lyjew/com/lyclaw/react/AgentHook.java`（第57-63行）

```java
public interface AgentHook {
    default ToolExecutor wrapToolExecutor(
            ToolExecutor inner, AgentContext ctx) {
        return inner;  // 默认不装饰
    }
}
```

在 `AgentInvocationHandler` 中使用：
```java
ToolExecutor toolExecutor = buildToolExecutor(ctx);
for (AgentHook hook : sorted) {
    toolExecutor = hook.wrapToolExecutor(toolExecutor, ctx);
    // 每个Hook包装一层：日志、审计、计时、安全检查...
}
```

**为什么用它**：工具执行需要多层横切关注点（日志、审计、计时、安全检查），每个关注点由一个Hook实现。装饰器模式让这些关注点可以动态组合，Hook之间互不知道对方的存在。

#### 架构定位
装饰器链在容器启动时由 `ChatModelPostProcessor` 组装，运行时不可变。而 `ToolExecutor` 的装饰器链在每次Agent方法调用时动态组装。两者的共同点：**增强原始对象的功能而不修改其代码**。

---

### 模式8：门面（Facade）

#### 定义与意图
为子系统中的一组接口提供一个统一的接口。门面定义了一个高层接口，使子系统更易于使用。在 LyClaw 中，`ChatFacade` 是**最核心的门面**。

#### 代码实例

**ChatFacade / DefaultChatFacade**

文件：`lyclaw-framework/src/main/java/lyjew/com/lyclaw/chat/ChatFacade.java`（第23-61行）
文件：`lyclaw-framework/src/main/java/lyjew/com/lyclaw/chat/DefaultChatFacade.java`（第57-267行）

```java
public class DefaultChatFacade implements ChatFacade {
    private final ChatModelRegistry registry;     // 模型注册
    private final ModelRouter router;             // 路由策略
    private final ChatClient defaultClient;       // 客户端API

    // 统一的模型调用入口
    @Override
    public ChatClient chat() { return defaultClient; }

    @Override
    public ModelResponse chat(ChatRequest request) {
        RoutingDecision decision = route(request, null);
        ChatModel model = resolveModel(decision);
        return model.call(request);
    }

    // 路由
    @Override
    public RoutingDecision route(ChatRequest request, Object context) {
        return router.route(request, context);
    }

    // 模型管理
    @Override
    public ChatModel resolveModel(RoutingDecision decision) {
        return registry.resolve(decision);
    }

    // Token计数（带回退到字符估算）
    @Override
    public int countTokens(String text) { ... }

    // 健康检查：对所有已注册模型进行健康检查
    @Override
    public Map<String, Boolean> healthCheck() { ... }
}
```

**为什么用它**：AI模型调用子系统包含模型注册（`ChatModelRegistry`）、智能路由（`ModelRouter`）、协议适配（`OpenAiProtocolChatModel`）、Token计数、健康检查等组件。如果没有门面，调用方需要分别理解和组装这些组件。`ChatFacade` 提供了一个简单的统一入口。

#### 架构定位
`ChatFacade` 是 LyClaw 中访问AI模型的**唯一推荐入口**。无论是ReAct引擎、Pipeline Stage、还是用户自定义代码，都通过 `ChatFacade` 与AI模型交互。这使得横切关注点（日志、监控、限流）可以在门面层统一实施。

**其它门面**：
- `SecurityManager`：安全审批、权限检查、权限撤销的统一入口
- `AgentCoordinator`：多Agent任务分派、状态跟踪、事件广播的统一协调器

---

### 模式9：代理（Proxy — JDK 动态代理）

#### 定义与意图
为另一个对象提供一个替身或占位符以控制对这个对象的访问。在 LyClaw 中，JDK动态代理是**整个Agent系统的基石**——`@Agent` 接口的方法调用被透明代理到完整的ReAct执行管道。

#### 代码实例

**AgentInterfaceProcessor — BeanPostProcessor 扫描并创建代理**

文件：`lyclaw-autoconfigure/src/main/java/lyjew/com/lyclaw/autoconfigure/processor/AgentInterfaceProcessor.java`

```java
@Component
public class AgentInterfaceProcessor implements BeanPostProcessor {
    private final AgentProxyFactory proxyFactory;

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) {
        Class<?> clazz = bean.getClass();
        if (clazz.isAnnotationPresent(Agent.class) && clazz.isInterface()) {
            return proxyFactory.create((Class<?>) bean); // 返回JDK动态代理
        }
        return bean;
    }
}
```

**AgentInvocationHandler — 调用处理器（核心拦截逻辑）**

文件：`lyclaw-framework/src/main/java/lyjew/com/lyclaw/react/AgentInvocationHandler.java`（第122-324行）

```java
public class AgentInvocationHandler implements InvocationHandler {

    @Override
    public Object invoke(Object proxy, Method method, Object[] args)
            throws Throwable {
        // 1. 解析 @SystemMessage / @UserMessage 注解
        // 2. 构建 ChatRequest（填充 sessionId, agentId）
        // 3. 创建 AgentContext（生命周期: TRANSIENT/SESSION/PERSISTENT）
        // 4. 解析 thinking/verbose/reasoning 级别
        // 5. 解析 model/provider（支持运行时覆盖）
        // 6. dispatchBeforeAgentRun() — 触发生命周期Hook
        // 7. dispatchBeforeRequest() — 触发请求前Hook（可修改ChatRequest）
        // 8. 执行 Stage Pipeline 或 ReActEngine
        // 9. dispatchAfterResult() — 触发结果后Hook
        // 10.dispatchAgentEnd() — 触发Agent结束Hook
        // 11.返回结果
    }
}
```

**为什么用它**：开发者只需定义一个接口并标注 `@Agent`，无需写任何实现代码：

```java
@Agent(id = "chat", name = "Chat Agent", model = "deepseek-v4-flash")
public interface ChatAgent {
    @SystemMessage("你是一个有用的AI助手。")
    String chat(@UserMessage String query);

    Flux<ServerSentEvent<String>> chatStream(@UserMessage String query);
}
```

当调用 `chatAgent.chat("你好")` 时：
1. JDK代理拦截调用 → `AgentInvocationHandler.invoke()`
2. Handler自动装配 `ChatRequest`、`AgentContext`
3. 触发所有Hook（生命周期、请求前）
4. 进入Stage Pipeline（ContextBuild → SecurityCheck → PlanExecution → Respond → Reflection → Metrics）
5. 或直接进入ReAct循环（LLM → 工具调用 → LLM → ...）
6. 所有 `@SystemMessage`/`@UserMessage` 自动注入为System Prompt和用户消息
7. 返回结果（String 或 SSE Flux）

#### 架构定位
JDK动态代理是 LyClaw 与其它Agent框架的**核心差异化特性**。Claude Code和OpenClaw都需要开发者编写Agent实现代码，LyClaw通过动态代理实现了"零实现代码"的Agent定义——开发者只需声明接口和注解。

---

### 模式10：组合（Composite — 接口预备）

#### 定义与意图
将对象组合成树形结构以表示"部分-整体"的层次结构。组合使得客户对单个对象和组合对象的使用具有一致性。在 LyClaw 中，`TaskPlan` 接口为组合模式做了结构准备，但目前还没有复合实现。

#### 代码实例

文件：`lyclaw-framework/src/main/java/lyjew/com/lyclaw/task/TaskPlan.java`（第44-48行Javadoc）

```java
/**
 * 任务计划的接口定义。
 *
 * 目前唯一的实现是扁平列表形式（SimpleTaskPlan）。
 * 未来将添加 NestedPlan（复合子计划），使一个 TaskPlan
 * 可以包含子 TaskPlan，实现递归分解。
 */
public interface TaskPlan {
    List<TaskNode> getNodes();
    Map<String, List<String>> getDependencies();
    Duration getEstimatedCompletionTime();
    boolean isReady();
    TaskPlanStatus getStatus();
}
```

`SimpleTaskPlan`（第50-115行）是当前的"叶节点"实现——一个扁平的 `TaskNode` 列表。`TaskPlan` 的接口已为 `NestedPlan` 做好结构准备：
```java
// 未来的 NestedPlan:
// public class NestedPlan implements TaskPlan {
//     private List<TaskPlan> children; // 子计划
//     // getNodes() 递归收集所有叶子节点
// }
```

#### 架构定位
虽然组合模式在 LyClaw 中尚未完全实现，但 `TaskPlan` 接口的设计体现了**面向扩展设计**的原则——接口允许复合，即使当前实现是扁平的。

类似地，`ContentFilter` 接口（`filter/ContentFilter.java`）允许通过列表迭代模拟过滤器链，天然支持组合式的过滤器树。

---

### 模式11：桥接（Bridge）

#### 定义与意图
将抽象部分与它的实现部分分离，使它们可以独立地变化。在 LyClaw 中，桥接模式用于持久化层的实现分离。

#### 代码实例

**JsonlReader / JsonlWriter — 存储格式桥接**

文件：`lyclaw-framework/src/main/java/lyjew/com/lyclaw/persistence/jsonl/JsonlReader.java`（第1-27行）
文件：`lyclaw-framework/src/main/java/lyjew/com/lyclaw/persistence/jsonl/JsonlWriter.java`（第1-19行）

```java
// 抽象：读取器接口
public interface JsonlReader {
    List<Map<String, Object>> readAll(String filePath);
    List<Map<String, Object>> readRange(String filePath, int offset, int limit);
    // offset=-1 表示读取最新 limit 条（懒加载）
    Map<String, Object> readFirstLine(String filePath);
    long countLines(String filePath);
}

// 抽象：写入器接口
public interface JsonlWriter {
    void appendLine(String filePath, Map<String, Object> fields);
    void flush(String filePath);
}
```

`DefaultJsonlReader`（第1-120行）实现了自动格式检测：
- JSON数组格式（`[{...},{...}]`）→ Jackson tree-model解析
- JSONL格式（每行一个JSON）→ 逐行解析
- 用第一个非空白字符判断：`[` 触发数组模式，其它触发逐行模式

`DefaultJsonlWriter`（第1-59行）使用 `RandomAccessFile` 实现原子追加：
- 首次写入：`[\n<json>\n]`
- 后续追加：seek到 `length-2`，splice进 `,\n<json>\n]`

**为什么用它**：桥接模式允许在运行时切换存储格式（JSON数组 vs JSONL），而不影响上层调用方。`SessionPersistence` 只依赖 `JsonlReader`/`JsonlWriter` 接口，不关心底层格式。

**AbstractChatModel — 协议桥接**

`AbstractChatModel`（`chat/AbstractChatModel.java`）也是桥接模式的应用——模板方法 `stream()` 是抽象，5个抽象方法（`buildNativeRequest`/`sendNativeRequest`/`parseChunk`/`getDefaultBaseUrl`/`getDefaultModel`）是实现。不同AI提供商的实现可以独立变化。

#### 架构定位
LyClaw 的桥接模式偏向"轻量级"——不像经典桥接那样有独立的 Abstraction 和 Implementor 层次，而是通过接口-实现分离来实现相同的目的：解耦抽象和实现。

---

### 模式12：注册表（Registry）

#### 定义与意图
提供一个中心化的对象查找和注册机制。在 LyClaw 中，Registry 模式用于管理模型、工具、技能、Agent等多种可扩展组件。

#### 代码实例

**ChatModelRegistry — 模型注册表**

文件：`lyclaw-framework/src/main/java/lyjew/com/lyclaw/chat/ChatModelRegistry.java`（第12-37行）

```java
public interface ChatModelRegistry {
    void register(String provider, String modelName,
                  ChatModel model, ChatModelMetadata metadata);
    ChatModel resolve(String provider, String modelName);
    ChatModel resolve(RoutingDecision decision);
    List<ChatModel> listByProvider(String provider);
    List<ChatModel> getAll();
    boolean hasModel(String provider, String modelName);
    ChatModelMetadata getMetadata(String provider, String modelName);
    Set<String> getModelNames(String provider);
}
```

`DefaultChatModelRegistry` 使用 `ConcurrentHashMap` 实现线程安全的多对多映射：`provider → (modelName → ChatModel)`。

**其它注册表**：

| Registry | 作用 | 注册方式 |
|----------|------|---------|
| `ToolRegistry` | 管理所有工具实例 | `@Tool` 注解扫描 + MCP动态注册 |
| `SkillRegistry` | 管理所有技能实例 | Skill定义文件 + 反射加载 |
| `AgentRegistry` | 管理Agent实例 | `@Agent` 注解扫描 |
| `HookRegistry` | 管理Hook实例和生命周期调度 | Hook配置 + 运行时注册 |
| `ChatModelRegistry` | 管理AI模型适配器 | `@ChatModel` 注解扫描 |
| `AsyncWriteQueueRegistry` | 管理会话级异步写入队列 | 运行时按sessionId创建 |

#### 架构定位
Registry 模式是 LyClaw 插件化架构的基础。所有可扩展组件（模型、工具、技能、Agent）都通过 Registry 注册和查找，新组件通过注解或配置自动注册，无需修改核心代码。

---

## 第三部分：行为型模式

### 模式13：策略（Strategy）

#### 定义与意图
定义一系列算法，把它们一个个封装起来，并使它们可以相互替换。策略模式让算法的变化独立于使用它的客户。在 LyClaw 中，策略模式是**最普遍的行为型模式**——10多个策略接口遍布于每一层。

#### 代码实例

**实例1：ModelRouter — 模型路由策略**

文件：`lyclaw-framework/src/main/java/lyjew/com/lyclaw/chat/ModelRouter.java`（第17-27行）

```java
@FunctionalInterface
public interface ModelRouter {
    RoutingDecision route(ChatRequest request, Object context);
}
```

`FirstAvailableRouter`（`chat/FirstAvailableRouter.java`）是最简单的策略——取注册表中第一个非空的提供者的第一个模型。规划中的策略包括 `RegexKeywordRouter`（亚毫秒级本地匹配）和 `LlmBasedRouter`（LLM驱动的语义路由）。

**为什么用它**：不同场景需要不同的路由策略。简单查询用 `FirstAvailableRouter`（最快），复杂任务用 `LlmBasedRouter`（最准确）。策略模式让路由算法可以热切换（`ChatFacade.switchRouter()`）。

**实例2：TaskPlanner — 任务规划策略（5种实现）**

文件：`lyclaw-framework/src/main/java/lyjew/com/lyclaw/task/TaskPlanner.java`

```java
/**
 * 策略模式 (Strategy Pattern) — 通过接口抽象多种规划算法，
 * 运行时通过依赖注入选择具体实现，新增规划策略无需修改调用方代码。
 */
public interface TaskPlanner {
    TaskPlan plan(ChatContext context);
    TaskPlan plan(ChatContext context, String intent);
    TaskPlan revise(TaskPlan plan, ReflectionFeedback feedback);
    void optimize(AgentResult result);
    List<TaskNode> decompose(TaskNode node, DecompositionStrategy strategy);
}
```

5种策略实现：

| 实现 | 文件 | Spring Bean名 | 适用场景 |
|------|------|--------------|---------|
| `CoTPlanner` | `lyclaw-plan/.../CoTPlanner.java` | `"cotPlanner"` | 推理密集型任务 |
| `ReActPlanner` | `lyclaw-plan/.../ReActPlanner.java` | `"reActPlanner"` | 交互式任务 |
| `DAGTaskPlanner` | `lyclaw-plan/.../DAGTaskPlanner.java` | 类名派生 | 结构化任务 |
| `HierarchicalPlanner` | `lyclaw-plan/.../HierarchicalPlanner.java` | `"hierarchicalPlanner"` | 大型多层项目 |
| `HybridPlanner` | `lyclaw-plan/.../HybridPlanner.java` | 类名派生 | 混合策略 |

**实例3：DecompositionStrategy — 任务分解策略**

文件：`lyclaw-framework/src/main/java/lyjew/com/lyclaw/task/DecompositionStrategy.java`

```java
public enum DecompositionStrategy {
    SEQUENTIAL,            // 顺序分解（按步骤）
    BY_DOMAIN,             // 按领域分解
    BY_PHASE,              // 按阶段分解
    PARALLEL_INDEPENDENT,  // 并行独立子任务
    LLM_DRIVEN,            // LLM驱动的智能分解
    TREE                   // 树形递归分解
}
```

`LLMTaskDecomposer`（`lyclaw-plan/.../LLMTaskDecomposer.java`第191行）使用 `switch` 表达式实现全部6种策略：

```java
public List<TaskNode> decompose(String task, DecompositionStrategy strategy) {
    return switch (strategy) {
        case SEQUENTIAL -> decomposeSequential(task);
        case BY_DOMAIN -> decomposeByDomain(task);
        case BY_PHASE -> decomposeByPhase(task);
        case PARALLEL_INDEPENDENT -> decomposeParallel(task);
        case LLM_DRIVEN -> decomposeWithLLM(task);
        case TREE -> decomposeTree(task, 3); // max depth 3
    };
}
```

**实例4：TemporalDecayFunction — 时间衰减策略**

文件：`lyclaw-framework/src/main/java/lyjew/com/lyclaw/memory/temporal/TemporalDecayFunction.java`

```java
public interface TemporalDecayFunction {
    double compute(long daysSinceCreation, double baseDecayFactor);
    String getName();
}
```

两种实现：
- `ExponentialDecayFunction`：`e^(-baseDecayFactor * daysSinceCreation)` — 适合短期记忆（快速衰减）
- `PowerLawDecayFunction`：`(1.0 + daysSinceCreation)^(-baseDecayFactor)` — 适合长期记忆（长尾保留）

**实例5：其它策略接口**

| 策略接口 | 文件 | 算法变体 |
|---------|------|---------|
| `MemoryStrategy` | `memory/MemoryStrategy.java` | 记忆格式化/过滤策略 |
| `MemoryConsolidationPolicy` | `memory/MemoryConsolidationPolicy.java` | 何时将短期记忆提升为长期记忆 |
| `ErrorPolicy` | `error/ErrorPolicy.java` | 模型错误→RETRY/SKIP/ABORT/FALLBACK |
| `SessionUpdateStrategy` | `transaction/SessionUpdateStrategy.java` | 并发会话修改冲突解决 |
| `Engine` + `EngineSelector` | `engine/Engine.java`, `engine/EngineSelector.java` | 自动发现并选择LLM引擎 |
| `ContextBuilder` | `context/ContextBuilder.java` | 为不同模型构建不同格式的消息上下文 |
| `SystemPromptBuilder` | `prompt/SystemPromptBuilder.java` | 为不同场景构建系统提示词 |
| `ToolCallPolicy` | `tool/ToolCallPolicy.java` | 工具调用权限和频率控制 |
| `AutoScaler` | `agent/scaling/AutoScaler.java` | Agent池自动扩缩策略 |

#### 架构定位
策略模式使 LyClaw 的行为高度可配置。通过 Spring 的依赖注入，策略可以在不修改调用代码的情况下替换。新策略只需添加一个 `@Component` 标注的实现类即可生效。

---

### 模式14：责任链（Chain of Responsibility）

#### 定义与意图
使多个对象都有机会处理请求，从而避免请求的发送者和接收者之间的耦合。将这些对象连成一条链，沿着这条链传递请求，直到有一个对象处理它为止。在 LyClaw 中，责任链有三种形式。

#### 代码实例

**形式1：InterceptorChain — Servlet Filter 风格**

文件：`lyclaw-framework/src/main/java/lyjew/com/lyclaw/interceptor/InterceptorChain.java`（第49-72行）

```java
@Component
public class InterceptorChain {
    private final List<Interceptor> interceptors = new CopyOnWriteArrayList<>();

    // 正向遍历，短路型：任一拦截器返回 false 则终止请求
    public boolean preHandle(ChatContext context) {
        for (Interceptor interceptor : interceptors) {
            if (!interceptor.preHandle(context)) {
                return false;  // 链条断裂，请求被拒绝
            }
        }
        return true;
    }

    // 反向遍历：第一个执行preHandle的最后一个执行postHandle
    public void postHandle(ChatContext context, ChatResult result) {
        List<Interceptor> reversed = new ArrayList<>(interceptors);
        reversed.sort(Comparator.comparingInt(Interceptor::getOrder).reversed());
        for (Interceptor interceptor : reversed) {
            interceptor.postHandle(context, result);
        }
    }
}
```

**形式2：AgentConfigResolver — 优先级叠加链**

文件：`lyclaw-framework/src/main/java/lyjew/com/lyclaw/config/AgentConfigResolver.java`（第13-115行）

配置源按优先级从低到高排序：

```
YamlAgentConfigSource (priority=10)    ← 全局YAML默认值
  → AnnotationAgentConfigSource (50)   ← @Agent 注解值
    → 数据库配置源 (60, 规划中)
      → 配置中心 (70, 规划中)
        → Builder手动覆盖 (100, 规划中)
```

每个源提供键值对，更高优先级的源覆盖低优先级的同键值。三层深度合并：
```java
// 第74-96行
String model = firstNonEmpty(
    annotationValue,       // 优先级1: @Agent注解
    yamlDefaultsValue,     // 优先级2: AgentDefaultsConfig
    systemBuiltinValue);   // 优先级3: AgentSystemDefaults
```

**形式3：HookRegistry — 生命周期Hook分派**

文件：`lyclaw-framework/src/main/java/lyjew/com/lyclaw/react/HookRegistry.java`（第110-191行）

三种分派模式：

```java
// Fire-and-forget：通知所有Hook，不关心返回值
public void dispatchBeforeRequest(AgentContext ctx) {
    for (AgentHook hook : getHooks("beforeRequest")) {
        try { hook.beforeRequest(ctx); }
        catch (Exception e) { log.warn("Hook failed: {}", e.getMessage()); }
    }
}

// 变换型分派：每个Hook的输入是前一个Hook的输出
public List<Message> dispatchBeforeModel(
        List<Message> messages, AgentContext ctx) {
    for (AgentHook hook : getHooks("beforeModel")) {
        messages = hook.beforeModel(messages, ctx);
    }
    return messages;
}

// 中断型分派：任一Hook返回非null结果即终止链
public AgentFinalizeResult dispatchBeforeAgentFinalize(AgentContext ctx) {
    for (AgentHook hook : getHooks("beforeAgentFinalize")) {
        AgentFinalizeResult r = hook.beforeAgentFinalize(ctx);
        if (r != null && !r.isContinue()) return r; // 中断！
    }
    return AgentFinalizeResult.continue_();
}
```

**形式4：DefaultToolExecutionPipeline — 7步固定管线**

文件：`lyclaw-framework/src/main/java/lyjew/com/lyclaw/tool/DefaultToolExecutionPipeline.java`（第44-118行）

```
Step 1: Resolve    (第50-54行)  — 从ToolRegistry查找Tool实例
Step 2: Policy     (第57-60行)  — 检查ToolCallPolicy（频率/黑名单）
Step 3: beforeHook (第63-70行)  — 执行所有ToolHook.beforeExecution
Step 4: Bind       (第73行)     — 将JSON参数绑定到Java方法参数
Step 5: Invoke     (第77-105行) — 调用Tool.execute()
Step 6: afterHook  (第108-114行)— 执行所有ToolHook.afterExecution（可变换结果）
Step 7: Format     (第117行)    — 格式化返回字符串
```

每一步都是责任链上的一个环节，Hook步骤允许动态注入额外的处理器。

#### 架构定位
责任链在 LyClaw 中无处不在——拦截器、配置解析、Hook分派、工具执行管线都是责任链的变体。共同特征：**有序的处理步骤，每个步骤可以增强、短路或透传**。

---

### 模式15：观察者（Observer — 事件与生命周期）

#### 定义与意图
定义对象间的一种一对多的依赖关系，当一个对象的状态发生改变时，所有依赖于它的对象都得到通知并被自动更新。在 LyClaw 中，观察者模式有三种形式。

#### 代码实例

**形式1：EventBus / Event — 经典发布-订阅**

文件：`lyclaw-framework/src/main/java/lyjew/com/lyclaw/event/EventBus.java`（第1-27行）
文件：`lyclaw-framework/src/main/java/lyjew/com/lyclaw/event/Event.java`（第1-28行）

```java
// 事件基类
public class Event {
    private String eventId = UUID.randomUUID().toString();
    private Instant timestamp = Instant.now();
    private String source;
    private String eventType;
}

// 事件总线接口
public interface EventBus {
    void publish(Event event);
    <T extends Event> void subscribe(Class<T> eventType, Consumer<T> handler);
    <T extends Event> void unsubscribe(Class<T> eventType, Consumer<T> handler);
    void clear();
    default void publishAsync(Event event) { /* ... */ }
}
```

**注意**：EventBus接口已定义，但仓库中未找到具体实现——这是一个**规划中的模式**，用于未来的跨模块事件通信。

**形式2：AgentHook — 30+个生命周期观察点**

文件：`lyclaw-framework/src/main/java/lyjew/com/lyclaw/react/AgentHook.java`（30+default方法）

这是 LyClaw 中**最完整的观察者实现**。AgentHook定义了覆盖Agent完整生命周期的30+个观察点：

```
Agent生命周期:
  beforeAgentRun()      → agentEnd()

请求生命周期:
  beforeRequest()       → afterResult()
  beforeModelResolve()  → modelCallStarted() → modelCallEnded()
  llmInput()            → llmOutput()

工具生命周期:
  beforeToolCall()      → afterToolCall()    → toolResultPersist()

会话生命周期:
  sessionStart()        → sessionEnd()

子Agent生命周期:
  subagentSpawning()    → subagentSpawned()  → subagentEnded()

压缩:
  beforeCompaction()    → afterCompaction()
```

所有方法都是 `default`（no-op），Hook实现只需覆盖关心的观察点。这种方法被称为"**接口默认方法 + 选择覆盖**"模式，避免了臃肿的 `AgentHookAdapter` 基类。

**Hook注册与排序**（`HookRegistry` 第40-66行）：
- 每个Hook注册到多个Hook名称下（同一个Hook可以观察多个生命周期事件）
- Hook按 `getOrder()` 排序（升序执行）
- `beforeXxx` 系列升序执行，`afterXxx` 系列降序执行（对称性）

**形式3：AgentEvent — 编排事件**

文件：`lyclaw-framework/src/main/java/lyjew/com/lyclaw/orchestration/AgentEvent.java`

```java
@Data @Builder
public class AgentEvent {
    public enum EventType {
        TASK_STARTED, TASK_PROGRESS, TASK_COMPLETED, TASK_FAILED,
        AGENT_STATE_CHANGED, COLLABORATION_STARTED, COLLABORATION_ENDED,
        CONSENSUS_REACHED, MESSAGE_RECEIVED, ALERT_TRIGGERED
    }
    private EventType type;
    private String agentId;
    private String data;        // JSON格式
    private Map<String, Object> metadata;
    private Instant timestamp;
}
```

`AgentEvent` 用于多Agent协作场景中的状态追踪——当Agent状态变化、共识达成、任务完成时，产生相应事件。注意 `AgentEvent` **不继承** `Event` 基类，它是独立的DTO。

#### 架构定位
观察者模式解耦了Agent核心逻辑与横切关注点（日志、持久化、安全、监控）。新的横切关注点只需实现 `AgentHook` 接口并注册到 `HookRegistry`，无需修改Agent核心代码。

---

### 模式16：命令（Command）

#### 定义与意图
将一个请求封装为一个对象，从而让你可以用不同的请求对客户进行参数化，对请求排队或记录请求日志，以及支持可撤销的操作。在 LyClaw 中，命令模式用于封装工具和技能的调用。

#### 代码实例

**命令对象**

文件：`lyclaw-framework/src/main/java/lyjew/com/lyclaw/action/ToolExecuteRequest.java`（第1-29行）

```java
@Data @Builder
public class ToolExecuteRequest {
    private String toolName;                // 工具名称
    private Map<String, Object> args;       // LLM提供的JSON参数
    private SandboxLevel sandboxLevel;      // 执行安全级别
    private String sessionId;               // 关联会话（审计用）
}
```

文件：`lyclaw-framework/src/main/java/lyjew/com/lyclaw/action/SkillExecuteRequest.java`（第1-27行）

```java
@Data
public class SkillExecuteRequest {
    private String skillId;
    private String sessionId;
    private Map<String, Object> params;
}
```

**命令调用者（Invoker）**

文件：`lyclaw-action/src/main/java/lyjew/com/lyclaw/action/impl/ActionExecutorImpl.java`（第59-300行）

```java
public class ActionExecutorImpl implements ActionExecutor {
    private final ExecutorService executor = ThreadPoolFactory.fixed("action", 4);

    // 执行工具命令
    @Override
    public CompletableFuture<ActionResult> executeTool(
            String toolName, Map<String, Object> args, SandboxLevel level) {
        return CompletableFuture.supplyAsync(() -> {
            // 1. 查找Tool → 2. 检查策略 → 3. 沙箱执行 → 4. 返回结果
        }, executor);
    }

    // 执行技能命令
    @Override
    public CompletableFuture<ActionResult> executeSkill(
            String skillId, ChatContext context) { ... }

    // 执行完整计划（逐个节点）
    @Override
    public Flux<ActionResult> execute(TaskPlan plan, ChatContext context) {
        return Flux.fromIterable(plan.getNodes())
            .flatMap(node -> executeNode(node, context));
    }
}
```

**命令接收者（Receiver）**

文件：`lyclaw-framework/src/main/java/lyjew/com/lyclaw/tool/Tool.java`（第1-42行）

```java
public interface Tool {
    String getName();
    ToolExecutionResult execute(ToolCall toolCall, ChatContext context);
    ToolDefinition getDefinition(); // LLM可见的工具描述
}
```

**声明式命令定义**

`@Tool`、`@Param`、`@ToolCondition` 注解形成声明式的命令定义系统（文件：`annotation/tool/`）：

```java
@Tool(name = "web_search", description = "搜索互联网获取实时信息")
public class WebSearchTool {
    @ToolCondition(requiresConfig = "tavily.api.key")  // 有API密钥才启用
    public String search(
        @Param(name = "query", description = "搜索关键词", required = true)
        String query,
        @Param(name = "max_results", description = "最大结果数", defaultValue = "5")
        int maxResults
    ) {
        // 实现搜索逻辑
    }
}
```

**为什么用它**：命令模式将"调用什么工具/技能"（由LLM决定）与"如何调用"（由框架处理）分离。`ToolExecuteRequest` 可以排队、异步执行、沙箱隔离、审计记录，而LLM只需输出JSON格式的tool_call。

#### 架构定位
`DefaultReActEngine` 是最高层的命令编排器——它在 Reasoning-Action 循环中不断调用 LLM（"思考"命令）和执行工具（"行动"命令），直到获得最终答案。

---

### 模式17：模板方法（Template Method）

#### 定义与意图
在一个方法中定义一个算法的骨架，而将一些步骤的实现延迟到子类中。模板方法让子类在不改变算法结构的情况下，重新定义算法中的某些步骤。在 LyClaw 中，模板方法是**所有AI模型适配器的基石**。

#### 代码实例

**AbstractChatModel — 教科书级模板方法**

文件：`lyclaw-framework/src/main/java/lyjew/com/lyclaw/chat/AbstractChatModel.java`（第96-105行）

```java
public abstract class AbstractChatModel implements ChatModel {

    /**
     * 模板方法（Template Method）— 固化的 AI 模型流式调用骨架。
     * 子类无需关心调用流程控制，只需实现特定协议的三个步骤。
     */
    @Override
    public Flux<ModelResponse> stream(ChatRequest request) {
        return Flux.defer(() -> {
            validateRequest(request);                     // Step 1: 验证（钩子）
            Object nativeRequest = buildNativeRequest(request); // Step 2: 构建请求（抽象）
            return sendNativeRequest(nativeRequest)            // Step 3: 发送请求（抽象）
                    .map(this::parseChunk)                     // Step 4: 解析响应（抽象）
                    .doOnComplete(() -> log.debug("{} stream completed", provider()))
                    .doOnError(this::handleError);             // Step 5: 错误处理（钩子）
        });
    }

    // 5个抽象方法 — 子类必须实现
    protected abstract Object buildNativeRequest(ChatRequest request);
    protected abstract Flux<String> sendNativeRequest(Object nativeRequest);
    protected abstract ModelResponse parseChunk(String chunk);
    protected abstract String getDefaultBaseUrl();
    protected abstract String getDefaultModel();

    // 3个钩子方法 — 子类可选覆盖
    protected void validateRequest(ChatRequest request) {
        if (request.getMessages() == null || request.getMessages().isEmpty())
            throw new IllegalArgumentException("messages must not be empty");
    }
    protected void handleError(Throwable error) { ... }
    protected String getDefaultApiKey() { ... }
}
```

**AbstractTaskPlanner — 规划层模板方法**

文件：`lyclaw-framework/src/main/java/lyjew/com/lyclaw/task/AbstractTaskPlanner.java`（第19-54行）

```java
public abstract class AbstractTaskPlanner implements TaskPlanner {

    @Override
    public TaskPlan plan(ChatContext context) {
        String intent = extractIntent(context);  // 模板方法：提取意图
        return plan(context, intent);            // 抽象步骤：子类实现
    }

    protected String extractIntent(ChatContext context) {
        // 默认实现：取最后一条用户消息
        // 子类可覆盖以实现更复杂的意图提取
    }

    // 抽象方法 — 子类必须实现
    protected abstract TaskPlan plan(ChatContext context, String intent);
    public abstract List<TaskNode> decompose(TaskNode node,
        DecompositionStrategy strategy);
}
```

**PipelineStageBase — 管线阶段基础类**

文件：`lyclaw-framework/src/main/java/lyjew/com/lyclaw/pipeline/stage/PipelineStageBase.java`（第17-55行）

提供辅助方法（`sseEvent()`、`escapeJson()`、`logJson()`），所有6个Pipeline阶段继承此类。这是轻量级的模板方法变体——提供公用方法而非严格算法骨架。

**AbstractCancellable — 可取消操作基类**

文件：`lyclaw-framework/src/main/java/lyjew/com/lyclaw/pipeline/AbstractCancellable.java`（第11-32行）

```java
public abstract class AbstractCancellable {
    private final AtomicBoolean cancelled = new AtomicBoolean(false);

    public void cancel() { cancelled.set(true); }
    public boolean isCancelled() { return cancelled.get(); }
    public void reset() { cancelled.set(false); }
}
```

#### 架构定位
模板方法模式是 LyClaw **协议适配层的核心抽象**。`AbstractChatModel` 的模板方法确保无论底层是什么AI协议（OpenAI、DeepSeek、Groq），流式调用的控制流完全一致。新增协议适配器只需实现5个抽象方法，无需理解整个流式调用骨架。

---

### 模式18：状态（State）

#### 定义与意图
允许一个对象在其内部状态改变时改变它的行为。对象看起来似乎修改了它的类。在 LyClaw 中，状态模式体现于Agent生命周期和熔断器。

#### 代码实例

**实例1：AgentState — Agent生命周期状态机**

文件：`lyclaw-framework/src/main/java/lyjew/com/lyclaw/agent/AgentState.java`（第1-25行）

```java
public enum AgentState {
    IDLE,       // 空闲，等待任务
    RUNNING,    // 执行中
    WAITING,    // 等待外部资源
    COMPLETED,  // 正常完成
    FAILED,     // 异常终止
    CANCELLED   // 被外部取消
}
```

状态转换（来自Javadoc）：
```
IDLE ──→ RUNNING ──→ COMPLETED    (正常路径)
            │
            ├──→ WAITING ──→ RUNNING  (暂停/恢复)
            ├──→ FAILED                (错误)
            └──→ CANCELLED             (取消)
```

**实例2：CircuitBreakerChatModel — 完整状态机实现**

文件：`lyclaw-framework/src/main/java/lyjew/com/lyclaw/decorator/CircuitBreakerChatModel.java`（第59-168行）

三个状态：`CLOSED`（正常）、`OPEN`（熔断）、`HALF_OPEN`（探测）

状态转换逻辑：
```
CLOSED ──(连续失败≥阈值)──→ OPEN
OPEN   ──(经过halfOpenAfterMs)──→ HALF_OPEN
HALF_OPEN ──(探测成功)──→ CLOSED        (恢复)
HALF_OPEN ──(探测全失败)──→ OPEN        (再次熔断)
```

线程安全：使用 `AtomicReference<String>` 存储状态，`compareAndSet` 进行原子状态转换：

```java
private String checkAndTransitionState() {
    String current = state.get();
    if (STATE_OPEN.equals(current)) {
        long elapsed = System.currentTimeMillis() - openedAt.get();
        if (elapsed >= halfOpenAfterMs) {
            state.compareAndSet(STATE_OPEN, STATE_HALF_OPEN); // 原子转换
            halfOpenAttempts.set(0);
            return STATE_HALF_OPEN;
        }
    }
    return current;
}
```

#### 架构定位
Agent状态机控制着Agent的调度——只有 `IDLE` 状态的Agent才能被分配新任务。熔断器状态机保护着AI服务调用——防止对已故障的服务持续发起请求（快速失败）。

---

### 模式19：备忘录（Memento）

#### 定义与意图
在不破坏封装性的前提下，捕获一个对象的内部状态，并在该对象之外保存这个状态，以便在以后恢复。在 LyClaw 中，备忘录模式用于Agent上下文快照和记忆持久化。

#### 代码实例

**AgentContext.toSnapshot() / restoreFromSnapshot()**

文件：`lyclaw-framework/src/main/java/lyjew/com/lyclaw/react/AgentContext.java`（第305-380行）

```java
public class AgentContext {
    // Originator：创建和恢复自己的状态

    public Map<String, Object> toSnapshot() {
        // 将当前状态序列化为Map（备忘录）
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("sessionId", sessionId);
        snapshot.put("userMessage", userMessage);
        snapshot.put("systemPrompt", systemPrompt);
        snapshot.put("sandboxLevel", sandboxLevel);
        snapshot.put("lifecycle", lifecycle.name());
        snapshot.put("currentStage", currentStage);
        snapshot.put("successCount", successCount);
        snapshot.put("failCount", failCount);
        snapshot.put("tracing", Map.of("traceId", tracing.getTraceId()));
        // 所有Phase 1字段...
        snapshot.put("agentId", agentId);
        snapshot.put("thinkingLevel", thinkingLevel);
        // ...
        return snapshot;
    }

    public void restoreFromSnapshot(Map<String, Object> snapshot) {
        // 从备忘录恢复状态
        this.sessionId = (String) snapshot.get("sessionId");
        this.userMessage = (String) snapshot.get("userMessage");
        this.systemPrompt = (String) snapshot.get("systemPrompt");
        // ...
        // 注意：运行时引用（toolRegistry, method, args）不恢复
        // 需要调用方重新注入
    }
}
```

**三个角色**：
- **Originator（发起人）**：`AgentContext` — 创建和恢复快照
- **Memento（备忘录）**：`Map<String, Object>` — 不透明的状态快照
- **Caretaker（负责人）**：会话持久化层 — 将快照写入JSONL文件

三种生命周期决定了快照的保存策略：
- `TRANSIENT`：方法调用结束即丢弃（不保存快照）
- `SESSION`：跨方法调用共享（会话内存中保存）
- `PERSISTENT`：跨应用重启存活（JSONL文件持久化）

**MemoryEntry — 记忆备忘录**

文件：`lyclaw-framework/src/main/java/lyjew/com/lyclaw/memory/MemoryEntry.java`（第1-88行）

```java
public class MemoryEntry {
    private String entryId;
    private String userId;
    private String sessionId;
    private MemoryLayerType layer;   // sensory/short-term/long-term/entity
    private String content;           // 原始文本
    private String summary;           // LLM生成的摘要
    private double[] embedding;       // 向量表示
    private MemoryCategory category;
    private double importance;        // 0-1 重要性评分
    private int accessCount;
    private TemporalProps temporal;   // 时间衰减属性

    // 多信号融合相关性评分
    public double computeRelevanceScore() {
        return alpha * importance
             + beta  * normalizeAccessCount()
             + gamma * temporal.computeDecay()
             + delta;
    }
}
```

#### 架构定位
备忘录模式使 LyClaw 支持会话暂停/恢复和跨重启的Agent状态持久化。`MemoryEntry` 是记忆系统的核心备忘录——每次对话轮次产生的 `PerceptionData` 被提取为 `MemoryEntry`，经过整合（Consolidation）、衰减（Decay）、清理（Janitor）后持久化为长期记忆。

---

### 模式20：守卫（Guard — Guardrail）

#### 定义与意图
在请求处理流程中设置检查点，如果条件不满足则阻止继续执行。在 LyClaw 中，守卫模式用于AI对话的安全防护。

#### 代码实例

**GuardrailController — 输入/输出守卫**

文件：`lyclaw-framework/src/main/java/lyjew/com/lyclaw/security/GuardrailController.java`（第15-34行）

```java
public interface GuardrailController {
    /**
     * 输入守卫：在用户消息进入AI模型之前运行。
     * 任何过滤器拒绝则整个内容被拦截。
     */
    FilterResult applyInputGuardrails(String content, ChatContext context);

    /**
     * 输出守卫：在AI响应返回用户之前运行。
     * 防止敏感信息泄露、有害内容等。
     */
    FilterResult applyOutputGuardrails(String content, ChatContext context);
}
```

**Supported FilterResult states:**
```java
public class FilterResult {
    boolean passed;
    String filteredContent;    // 拦截时：替换/净化后的内容
    String reason;              // 拦截原因
    List<String> matchedRules;  // 命中的规则列表

    static FilterResult pass() { /* ... */ }
    static FilterResult reject(String reason, List<String> rules) { /* ... */ }
}
```

守卫逻辑是短路型的：在有序过滤器列表中，第一个拒绝的过滤器立即终止检查并返回拦截结果。

#### 架构定位
`GuardrailController` 位于 `SecurityCheckStage`（Pipeline的第二个阶段），在AI模型调用之前和之后执行安全过滤。这是 LyClaw 多层安全防护体系（Settings → Permissions → Guardrail → Hook → Sandbox）中的一环。

---

### 模式21：共识（Consensus）

#### 定义与意图
在多个Agent可能给出不同答案的场景中，通过投票、辩论或加权评估来达成共识。在 LyClaw 中，共识模式用于多Agent协作的质量保证。

#### 代码实例

**ConsensusEngine / ConsensusResult / VoteResult**

文件：`lyclaw-framework/src/main/java/lyjew/com/lyclaw/agent/communication/ConsensusEngine.java`（第17-44行）

```java
public interface ConsensusEngine {
    /** 快速检查：是否已达成共识 */
    ConsensusResult hasConsensus(List<PeerResponse> responses);

    /** 冲突消解：当Agent意见不一致时 */
    ConsensusResult resolve(List<PeerResponse> responses);

    /** 投票：对候选答案进行多数/加权投票 */
    VoteResult vote(List<String> candidates, Map<String, Double> weights);
}
```

```java
@Data @Builder
public class ConsensusResult {
    private boolean consensusReached;
    private String decision;           // 最终决策
    private double agreementRate;      // 一致率 (0.0-1.0)
    private int roundsTaken;           // 达成共识的轮次
    private String majorityAgentId;    // 多数派Agent ID
}

@Data @Builder
public class VoteResult {
    private String winnerAgentId;
    private Map<String, Double> voteDistribution; // Agent → 票数/权重
    private double winnerScore;
    private int totalVoters;
}
```

**为什么用它**：在多Agent代码审查场景中（类似Claude Code的并行审查模式），4个Agent独立审查同一段代码可能给出不同意见。`ConsensusEngine` 通过加权投票解决分歧——Opus模型的审查意见权重高于Haiku模型。

#### 架构定位
共识模式是 LyClaw 多Agent协作能力的关键组件。它与 `CollaborationMode`（DEBATE辩论模式、VOTING投票模式、CHAIN链式模式）紧密配合。

---

### 模式22：生产者-消费者（Producer-Consumer）

#### 定义与意图
解耦数据的生产者和消费者，通过缓冲区（队列）协调两者不同的处理速度。在 LyClaw 中，此模式用于异步 JSONL 持久化。

#### 代码实例

**AsyncWriteQueue — 会话级异步写入队列**

文件：`lyclaw-framework/src/main/java/lyjew/com/lyclaw/persistence/queue/AsyncWriteQueue.java`（第23-119行）

```java
public class AsyncWriteQueue implements AutoCloseable {
    private final BlockingQueue<WriteTask> queue;
    private final Thread consumerThread;
    private volatile boolean running = true;

    // 构造函数：创建有界队列 + 守护消费者线程
    public AsyncWriteQueue(SessionRepository repo, int capacity) {
        this.queue = new LinkedBlockingQueue<>(capacity);
        this.consumerThread = new Thread(this::consumeLoop, "jsonl-writer");
        this.consumerThread.setDaemon(true);
        this.consumerThread.start();
    }

    // 生产者：ReAct主循环投递写任务（不阻塞）
    public void enqueue(Session session, Map<String, Object> fields) {
        // 先刷新上次失败的写入（重试缓冲区）
        if (!retryBuffer.isEmpty()) flushRetryBuffer(session);
        queue.offer(new WriteTask(session, fields));
    }

    // 消费者：守护线程执行实际I/O
    private void consumeLoop() {
        while (running || !queue.isEmpty()) {
            WriteTask task = queue.poll(100, TimeUnit.MILLISECONDS);
            if (task != null) {
                try {
                    sessionRepository.appendMessage(task.session, task.fields);
                    consecutiveFailures = 0;
                } catch (Exception e) {
                    consecutiveFailures++;
                    if (consecutiveFailures <= 3) {
                        retryBuffer.add(task);  // 放入重试缓冲区
                    } else {
                        log.error("写入失败超过3次，丢弃消息", e); // 降级
                    }
                }
            }
        }
    }

    record WriteTask(Session session, Map<String, Object> fields) {}
}
```

**为什么用它**：ReAct循环需要在每次LLM工具调用后立即持久化消息，但JSONL文件写入（特别是 `RandomAccessFile` 的seek+splice操作）有I/O延迟。生产者-消费者模式让ReAct循环不等待I/O完成，显著降低了端到端延迟。

#### 架构定位
`AsyncWriteQueueRegistry` 管理每个会话的 `AsyncWriteQueue` 实例。当会话创建时分配队列，会话关闭时销毁队列（graceful shutdown：等待最多5秒让队列排空）。

---

## 第四部分：架构级模式

### 模式23：依赖注入 / IoC（Dependency Injection）

#### 定义与意图
将对象的依赖关系从代码中移出，交给外部容器管理。控制反转（IoC）让框架调用你的代码，而不是你的代码调用框架。在 LyClaw 中，Spring Boot 的自动配置机制实现了完整的 IoC 容器。

#### 代码实例

**EnableLyClaw — 选择性激活**

文件：`lyclaw-framework/src/main/java/lyjew/com/lyclaw/annotation/EnableLyClaw.java`

```java
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface EnableLyClaw {
    // 对标 @EnableScheduling / @EnableAsync
    // 框架只在检测到此注解时才激活全部Agent功能
}
```

**8个自动配置类**（`lyclaw-autoconfigure/.../autoconfigure/`）

| 配置类 | Bean数 | 关键Bean |
|--------|--------|---------|
| `LyClawBaseAutoConfiguration` | 1 | `@ComponentScan` |
| `ChatAutoConfiguration` | 4 | `ChatModelRegistry`, `FirstAvailableRouter`, `ChatFacade` |
| `ToolAutoConfiguration` | 3 | `ToolAnnotationProcessor`, `ConditionFilter` |
| `PipelineAutoConfiguration` | 3 | `PipelineStageProcessor`, `ExtensionFacade` |
| `InterceptorAutoConfiguration` | 1 | `InterceptorProcessor` |
| `AgentProxyAutoConfiguration` | 8 | `AgentProxyFactory`, `SubagentSpawner`, `DelegateToAgentToolProvider` |
| `ReActAutoConfiguration` | 2 | `DefaultReActEngine` |
| `ProcessorAutoConfiguration` | 4 | `ChatModelPostProcessor`, `OpenAiProtocolAutoConfigurator` |

所有Bean使用 `@ConditionalOnMissingBean` 允许用户覆盖，使用 `@ConditionalOnClass` / `@ConditionalOnBean` 控制依赖条件。`@AutoConfigureAfter` 确保正确的装配顺序。

**6个Stereotype注解**

| 注解 | 作用 |
|------|------|
| `@Agent` | 声明AI Agent接口（`@Component`元注解） |
| `@Extension` | Agent扩展配置键值对 |
| `@LyClawPlugin` | 声明LyClaw框架插件 |
| `@Interceptor` | 声明请求拦截器 |
| `@PipelineStage` | 声明Pipeline阶段 |
| `@SupervisorAgent` | 声明监督者Agent |

全部元注解了 `@Component`，通过Spring组件扫描自动发现。

#### 架构定位
IoC容器是 LyClaw 的"骨架"。框架通过 `@EnableLyClaw` 标记激活，通过 `@AutoConfiguration` 装配所有Bean，通过 `BeanPostProcessor` 进行后处理（代理创建、注解处理、装饰器组装）。

---

### 模式24：管线（Pipeline — Reactive Stage Chain）

#### 定义与意图
将处理流程分解为一系列有序的阶段（Stage），每个阶段专注于一个职责，阶段之间通过响应式流连接。在 LyClaw 中，6阶段管线是实现 Agent 请求处理的架构主干。

#### 代码实例

文件：`lyclaw-framework/src/main/java/lyjew/com/lyclaw/pipeline/ReactivePipelineStage.java`

```java
public interface ReactivePipelineStage {
    Flux<ServerSentEvent<String>> execute(AgentContext ctx);
    int getOrder();
    String getStageName();
}
```

**6个Pipeline阶段**：

| 阶段 | 文件 | 顺序 | 职责 |
|------|------|------|------|
| `ContextBuildStage` | `pipeline/stage/ContextBuildStage.java` | 10 | 构建会话上下文、加载记忆、注入System Prompt |
| `SecurityCheckStage` | `pipeline/stage/SecurityCheckStage.java` | 20 | Guardrail检查、权限验证、输入过滤 |
| `PlanExecutionStage` | `pipeline/stage/PlanExecutionStage.java` | 30 | 任务规划、分解、工具/Skill调度 |
| `RespondStage` | `pipeline/stage/RespondStage.java` | 40 | ReAct循环、LLM调用、SSE流式输出 |
| `ReflectionStage` | `pipeline/stage/ReflectionStage.java` | 50 | 质量评估、错误检测、策略调整 |
| `MetricsStage` | `pipeline/stage/MetricsStage.java` | 60 | Token统计、延迟记录、成本核算 |

阶段串联（`AgentInvocationHandler` 第247-271行）：
```java
Flux<ServerSentEvent<String>> pipeline = Flux.concat(
    stages.stream().map(stage -> stage.execute(ctx))
);
```

`@PipelineStage` 注解支持拓扑排序：通过 `after` 和 `before` 字段声明依赖关系，`TopologySort` 使用Kahn算法自动确定执行顺序。

**Plan-Execute-Reflect-Replan 循环**：

当一个阶段发现问题时（如 `ReflectionStage` 评估质量不合格），管线可以向 `PlanExecutionStage` 发送重规划信号：
```
ContextBuild → SecurityCheck → Plan → Execute → Reflect
                                                    ↓ (不合格)
                                          Reflect → Plan → Execute → Reflect
                                                                        ↓ (合格)
                                                                  Metrics
```

#### 架构定位
Pipeline 是 LyClaw 核心控制流的实现。它比简单的 `before → execute → after` 更强大——支持流式处理（每个阶段输出SSE事件流）、动态重规划（Plan-Replan循环）、和并行阶段执行（通过 `Flux.merge`）。

---

### 模式25：插件（Plugin）

#### 定义与意图
允许第三方在无需修改核心代码的情况下扩展框架功能。在 LyClaw 中，通过 `@LyClawPlugin` 注解和 `ExtensionFacade` 实现插件化架构。

#### 代码实例

**@LyClawPlugin 注解**

文件：`lyclaw-framework/src/main/java/lyjew/com/lyclaw/annotation/LyClawPlugin.java`（第49-69行）

```java
@Component
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface LyClawPlugin {
    String name();        // 插件名称
    String version();     // 版本号
}
```

**ExtensionFacade — 扩展管理门面**

文件：`lyclaw-autoconfigure/src/main/java/lyjew/com/lyclaw/autoconfigure/facade/ExtensionFacade.java`

```java
public class ExtensionFacade {
    // 收集 → 过滤 → 注册 三阶段管线
    private final List<DeferredRegistrar> registrars;
    private final ExtensionProperties properties;

    public void processExtensions() {
        for (DeferredRegistrar registrar : registrars) {
            List<?> candidates = registrar.getPending();
            List<?> filtered = filterChain.apply(candidates, properties);
            registrar.applyFiltered(filtered);
        }
    }
}
```

**可扩展点（SPI）汇总**：

| SPI接口 | 包位置 | 扩展粒度 |
|---------|--------|---------|
| `ToolProvider` | `tool/ToolProvider.java` | 工具：自定义工具、MCP工具 |
| `AgentHook` | `react/AgentHook.java` | 生命周期：30+观察点 |
| `ToolHook` | `tool/ToolHook.java` | 工具执行：前置/后置拦截 |
| `ChatModel` | `chat/ChatModel.java` | 模型：新AI提供商 |
| `ModelRouter` | `chat/ModelRouter.java` | 路由：自定义路由策略 |
| `TaskPlanner` | `task/TaskPlanner.java` | 规划：自定义规划算法 |
| `AgentConfigSource` | `config/AgentConfigSource.java` | 配置：自定义配置来源 |
| `Interceptor` | `interceptor/Interceptor.java` | 拦截：请求前/响应后 |
| `ContentFilter` | `filter/ContentFilter.java` | 过滤：输入/输出内容 |
| `ReactivePipelineStage` | `pipeline/ReactivePipelineStage.java` | 管线：自定义处理阶段 |
| `TemporalDecayFunction` | `memory/temporal/TemporalDecayFunction.java` | 记忆：自定义衰减模型 |
| `ErrorPolicy` | `error/ErrorPolicy.java` | 错误：自定义错误处理 |

#### 架构定位
插件架构使 LyClaw 既是一个独立运行的Agent框架，又是一个可嵌入的AI能力库。用户可以通过 `@LyClawPlugin` 打包扩展（自定义工具、自定义Hook、自定义规划器），通过Spring的 `@ComponentScan` 或 `spring.factories` 机制加载。

---

### 模式26：仓库（Repository）

#### 定义与意图
在领域层和数据映射层之间进行中介，使用类似集合的接口来访问领域对象。在 LyClaw 中，仓库模式用于 SQLite 数据持久化——采用纯 JDBC 而非 ORM。

#### 代码实例

**AgentRepository — Agent仓库**

文件：`lyclaw-framework/src/main/java/lyjew/com/lyclaw/persistence/repository/AgentRepository.java`（第14-190行）

```java
/**
 * Agent 仓储 —— 纯 JDBC，无 ORM。
 * 设计决策：SQLite 仅有 3 张表，ORM 的映射成本高于收益。
 */
public class AgentRepository {
    private final SqliteConnectionManager connectionManager;

    public void insert(Map<String, Object> agent) {
        String sql = "INSERT INTO agents (agent_id, name, description, ...) "
                   + "VALUES (?, ?, ?, ...)";
        try (Connection conn = connectionManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, (String) agent.getOrDefault("agentId", ""));
            ps.setString(2, (String) agent.getOrDefault("name", ""));
            // ... 25个字段
            ps.executeUpdate();
        }
    }

    public Map<String, Object> findById(String agentId) { /* SELECT */ }
    public List<Map<String, Object>> findAllSummary() { /* SELECT 8 columns */ }
    public void update(String agentId, Map<String, Object> updates) { /* UPDATE SET */ }
    public void delete(String agentId) { /* DELETE */ }
    public int countChildren(String parentAgentId) { /* COUNT */ }
    public List<Map<String, Object>> findAllTemporaryDescendants() {
        // WITH RECURSIVE descendants AS (...)
    }
}
```

**SessionRepository — 双存储仓库**

文件：`lyclaw-framework/src/main/java/lyjew/com/lyclaw/persistence/repository/SessionRepository.java`（第17-243行）

```java
/**
 * 双存储模式：
 * - JSONL 文件：消息数据的真实来源（完整、可重放）
 * - SQLite：元数据索引（快速列表、筛选、统计）
 *
 * 写入顺序：先写 JSONL，再更新 SQLite。
 * 两步不在同一事务中 —— 文件系统不能参与 JDBC 事务。
 */
public class SessionRepository {
    public void create(Session session) {
        // Step 1: 向 JSONL 文件追加 session_created 事件
        jsonlWriter.appendLine(filePath, sessionData);
        // Step 2: 向 SQLite INSERT 元数据行
        insertSqlite(session);
    }

    public void appendMessage(Session session, Map<String, Object> fields) {
        // Step 1: 向 JSONL 文件追加消息行
        jsonlWriter.appendLine(filePath, fields);
        // Step 2: 原子更新 SQLite 统计摘要
        updateStats(session.getSessionId(), fields);
    }

    public List<Map<String, Object>> findByAgentId(String agentId) { /* ... */ }
    public long countByAgent(String agentId) { /* ... */ }
    public void delete(String sessionId, String filePath) {
        Files.deleteIfExists(Path.of(filePath)); // 删除 JSONL
        deleteSqlite(sessionId);                  // 删除 SQLite 行
    }
}
```

**SqliteConnectionManager — 连接管理**

文件：`lyclaw-framework/src/main/java/lyjew/com/lyclaw/persistence/sqlite/SqliteConnectionManager.java`（第15-42行）

```java
public class SqliteConnectionManager implements AutoCloseable {
    private final SQLiteDataSource dataSource;

    public SqliteConnectionManager(StorageProperties props) {
        // 创建父目录 → 启用 WAL 模式 → 设置 5s busy timeout
        this.dataSource = new SQLiteDataSource();
        dataSource.setUrl("jdbc:sqlite:" + props.getDbPath());
        // WAL: 读写不互斥
        dataSource.getConnection()
            .createStatement().execute("PRAGMA journal_mode=WAL");
    }
}
```

**为什么不用ORM**：项目注释中明确说明了设计决策——"SQLite仅有3张表，ORM的映射成本高于收益"。当表数量很少且SQL相对简单时，直接JDBC比Hibernate/MyBatis更轻量。

#### 架构定位
仓库模式隔离了业务逻辑与存储细节。如果未来需要从SQLite迁移到PostgreSQL或MySQL，只需替换Repository实现——上层Agent逻辑完全不受影响。

---

### 模式27：DAG / 拓扑排序（DAG & Topological Sort）

#### 定义与意图
使用有向无环图表示任务间的依赖关系，并通过拓扑排序确定执行顺序。在 LyClaw 中，DAG模式用于任务计划、技能依赖和组件排序。

#### 代码实例

**TopologySort — 通用拓扑排序器**

文件：`lyclaw-autoconfigure/src/main/java/lyjew/com/lyclaw/autoconfigure/ordering/TopologySort.java`（第41-88行）

```java
public class TopologySort {
    /**
     * 对给定集合进行拓扑排序，使用 Kahn 算法。
     * @param nodes 待排序节点
     * @param dependencyResolver 获取节点依赖的函数
     * @return 拓扑排序后的节点列表
     * @throws IllegalStateException 如果检测到循环依赖
     */
    public static <T> List<T> sort(Collection<T> nodes,
            Function<T, Collection<T>> dependencyResolver) {
        // Kahn算法：计算入度 → 找到入度为0的节点 → BFS → 检测剩余节点(循环)
    }
}
```

**使用场景**：
- `@PipelineStage` 的 `after`/`before` 属性 → 阶段排序
- `@Interceptor` 的 `after`/`before` 属性 → 拦截器排序
- `SkillGraphImpl` 的技能依赖 → 执行顺序
- `PlanGraph` 的任务依赖 → 执行顺序

**PlanGraph — 任务图**

文件：`lyclaw-framework/src/main/java/lyjew/com/lyclaw/task/PlanGraph.java`（第9-138行）

```java
public class PlanGraph {
    private final Map<String, TaskNode> nodes;
    private final Map<String, Set<String>> adjacencyList;
    private final Map<String, TaskNodeStatus> statuses;

    // BFS级联失败：节点失败 → 所有后继节点SKIPPED
    public void cascadeSkip(String failedNodeId) {
        Queue<String> queue = new LinkedList<>();
        queue.add(failedNodeId);
        while (!queue.isEmpty()) {
            String nodeId = queue.poll();
            for (String successor : adjacencyList.get(nodeId)) {
                statuses.put(successor, TaskNodeStatus.SKIPPED);
                queue.add(successor);
            }
        }
    }

    // 进度计算
    public double getProgress() {
        long completed = statuses.values().stream()
            .filter(s -> s == COMPLETED || s == SKIPPED).count();
        return (double) completed / nodes.size();
    }
}
```

#### 架构定位
DAG模式确保了多步任务的正确执行顺序，防止循环依赖导致的死锁。拓扑排序是 LyClaw 自动装配正确性的保障——无论是内部组件排序还是用户定义的任务依赖。

---

### 模式28：延迟注册（Deferred Registration）

#### 定义与意图
将组件的发现和注册解耦为两个步骤，允许在发现和注册之间插入过滤、排序和验证逻辑。在 LyClaw 中，此模式用于扩展组件的批量处理。

#### 代码实例

**DeferredRegistrar**

文件：`lyclaw-autoconfigure/src/main/java/lyjew/com/lyclaw/autoconfigure/facade/DeferredRegistrar.java`（第21-30行）

```java
/**
 * 三阶段管线：收集 → 过滤 → 注册
 */
public interface DeferredRegistrar<T> {
    /** 阶段1：获取所有待注册的候选项 */
    List<T> getPending();

    /** 阶段3：将过滤后的候选项正式注册 */
    void applyFiltered(List<T> filtered);
}
```

**工作流程**：
```
1. BeanPostProcessor 发现候选组件 → 存入 DeferredRegistrar
2. ExtensionFacade 运行过滤链（基于 ExtensionProperties）
3. 过滤后的候选项 → applyFiltered() 正式注册
4. ExtensionWiring 建立组件间的依赖关系
```

**为什么用它**：传统的 `BeanPostProcessor` 是逐个Bean处理的——每发现一个Bean就立即注册。但有些组件需要在所有Bean发现完成后，基于全局信息（如配置开关、环境变量、Feature Flag）批量过滤。延迟注册将发现和注册解耦，使批量决策成为可能。

---

## 综合对比：LyClaw vs Claude Code vs OpenClaw 设计模式

| 设计模式 | LyClaw | Claude Code | OpenClaw |
|---------|--------|-------------|----------|
| **动态代理** | JDK Proxy（核心差异化特性） | 无 | 无 |
| **适配器** | 8+个适配器（协议、工具、技能） | Hook协议适配 | npm包适配 |
| **策略** | 10+个策略接口 | 规则引擎策略 | ContextEngine插件 |
| **装饰器** | 弹性装饰器链 + ToolExecutor链 | Hook规则链 | npm中间件链 |
| **门面** | ChatFacade（AI调用唯一入口） | Command编排 | assemble/compact |
| **观察者** | AgentHook（30+生命周期点） | 4 Hook事件 | 插件事件 |
| **模板方法** | AbstractChatModel（协议骨架） | 无显式模板方法 | Context模板 |
| **命令** | ToolExecuteRequest + @Tool注解 | /slash命令 | 函数调用 |
| **责任链** | 4种形式（拦截器/配置/Hook/管线） | Hook规则引擎 | 中间件链 |
| **状态** | Agent生命周期 + 熔断器 | Hook状态文件 | 会话状态 |
| **备忘录** | AgentContext快照 + 记忆Entity | JSONL Transcript | compact/snapshot |
| **仓库** | 纯JDBC（无ORM） | 文件系统（.local.md） | 文件系统 |
| **管线** | 6阶段Reactive Pipeline | Command 7阶段 | Context Pipeline |
| **注册表** | ChatModel/Tool/Skill/Agent/Hook | Plugin marketplace | npm registry |
| **生产者-消费者** | AsyncWriteQueue | 无显式队列 | 无 |
| **DAG** | TopologySort + PlanGraph | Agent依赖（命令级） | 会话DAG |
| **守卫** | GuardrailController | 安全Hook规则 | 无 |
| **共识** | ConsensusEngine（三阶段共识） | 冗余+验证+置信度 | 无 |
| **插件** | @LyClawPlugin + SPI | .claude-plugin/ + hooks | npm生态 |
| **IoC** | Spring Boot AutoConfiguration | 无容器（脚本式） | ContextEngine |

---

## 设计质量评估

### 优秀之处

1. **JDK动态代理创新**：将声明式注解 + JDK代理应用于AI Agent定义，实现了"零实现代码"的Agent开发体验（行业首创，Claude Code和OpenClaw均无此特性）

2. **策略模式渗透全栈**：10+策略接口分布在每一层（路由、规划、记忆、错误处理、会话冲突），实现了"一切皆可替换"的灵活性

3. **装饰器链的弹性设计**：三层装饰器（熔断→重试→降级）通过注解声明式配置，启动时自动组装，运行时透明生效

4. **Hook观察者体系完整**：30+生命周期观察点覆盖Agent运行的每个时刻，接口默认方法设计避免了臃肿的适配器基类

5. **门面模式降低复杂度**：ChatFacade将模型注册、路由、协议适配、Token计数、健康检查统一为一个简单入口

6. **纯JDBC的务实选择**：在只有3张SQLite表的场景下，不引入ORM是正确的工程判断（避免过度设计）

### 可改进之处

1. **组合模式不完整**：`TaskPlan` 接口已为 `NestedPlan` 做好准备，但复合实现尚未编写——目前所有任务计划都是扁平的

2. **EventBus无实现**：接口定义完善，但仓库中找不到具体的 `EventBus` 实现——跨模块事件通信能力暂缺

3. **部分策略接口无实现**：`MemoryStrategy`、`ErrorPolicy`、`SessionUpdateStrategy` 接口只定义了SPI契约，尚未提供具体实现

4. **共识引擎缺少加权算法**：`ConsensusEngine` 接口完整但具体加权投票算法尚未实现

5. **Repository层无泛型抽象**：三个Repository类有大量重复的JDBC样板代码（连接管理、异常处理、PreparedStatement设置），可抽取公共基类

---

## 总结

通过对 LyClaw 全部 484 个 Java 源文件的系统分析，本文档识别了 **28 种设计模式**：

**创建型模式（5种）**：工厂方法、抽象工厂、构建器、单例、原型（间接）

**结构型模式（7种）**：适配器、装饰器、门面、代理、组合（接口预备）、桥接、注册表

**行为型模式（10种）**：策略、责任链、观察者、命令、模板方法、状态、备忘录、守卫、共识、生产者-消费者

**架构级模式（6种）**：依赖注入/IoC、管线、插件、仓库、DAG/拓扑排序、延迟注册

LyClaw 的设计哲学可以概括为三个核心原则：

1. **声明式优于命令式**：Agent、Tool、Plugin、Hook 全部通过注解声明，框架自动处理生命周期（区别于Claude Code的脚本式配置和OpenClaw的编程式API）

2. **策略驱动优于硬编码**：从模型路由到任务规划到记忆衰减，所有算法决策点都通过策略接口实现可替换（区别于Claude Code的单一实现）

3. **JDK代理优于实现类**：通过JDK动态代理实现"零实现代码"的Agent开发，注解+接口即可定义完整Agent行为（LyClaw独有的创新点）

这些设计模式共同构建了一个高度解耦、极度可扩展、声明式编程体验的企业级多Agent AI应用框架。

---

> **分析完成日期**：2026-05-22
> **分析方法**：逐文件静态代码分析 + 3路并行Agent探索 + 交叉验证
> **覆盖范围**：484个Java源文件，8个模块，28种设计模式
