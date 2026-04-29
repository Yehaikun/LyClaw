# LyClaw AI 引擎层 — 代码实现

> **模块**：lyclaw-engine
> **基础包**：`lyjew.com.lyclaw`
> **总文件数**：96 个 .java 文件
> **当前进度**：第一部分（8/96）✅

---

## 第一部分：DTO/值对象（无业务依赖）

> **设计意图**：纯数据容器，不依赖任何 engine 层的类。只在模块间传递数据，不包含业务逻辑。在其他类编写代码之前，这些类的字段签名就可以先确定下来。

---

### 第 1 块：ChatResult

#### 类介绍

**设计动机**：Engine.execute() 返回值的统一 DTO。包含 AI 回复内容、工具调用结果、Token 用量和请求耗时。

**包路径**：lyclaw-engine → lyjew.com.lyclaw.dto

**类型**：DTO 类

```java
package lyjew.com.lyclaw.dto;

import lyjew.com.lyclaw.tool.ToolResult;

import java.util.List;

/**
 * 模型对话结果 —— Engine.execute() 的统一返回值 DTO。
 *
 * <p>当 Pipeline 完成一次完整的对话处理后（包含模型调用、工具调用循环），
 * 所有产出结果被封装为这个对象返回给上层调用者（Controller / WebSocket Handler）。</p>
 *
 * <p><b>设计动机</b>：将 AI 回复的文本内容、工具调用结果、Token 用量、
 * 以及请求耗时打包为一个不可变对象，避免逐字段传递。</p>
 *
 * <p><b>使用场景</b>：
 * <ul>
 *   <li>DefaultEngine.execute() 的返回值类型</li>
 *   <li>Interceptor.postHandle() 的回调参数</li>
 *   <li>HTTP Controller 将 ChatResult 序列化为 JSON 返回给前端</li>
 * </ul>
 * </p>
 *
 * @since 1.0
 * @author LyClaw Team
 */
public class ChatResult {

    /** AI 回复的文本内容。当模型没有文本回复（只有工具调用请求）时可能为 null */
    private final String content;

    /**
     * 完成原因 —— 描述对话处理的终止状态。
     * <ul>
     *   <li>"stop" — 模型正常结束回复</li>
     *   <li>"error" — 处理过程中发生了不可恢复的错误</li>
     *   <li>"timeout" — 管道执行超时</li>
     * </ul>
     */
    private final String finishReason;

    /** Token 用量摘要，格式如 "prompt=123 completion=45 total=168" */
    private final String tokenUsage;

    /** 工具调用结果列表。如果没有工具调用，为空列表（非 null） */
    private final List<ToolResult> toolResults;

    /** 请求耗时（毫秒）。从 execute() 调用到返回结果的总耗时 */
    private final long durationMs;

    /**
     * 构造一个 ChatResult 实例。
     *
     * @param content     AI 回复的文本内容
     * @param finishReason 完成原因
     * @param tokenUsage  Token 用量摘要
     * @param toolResults 工具调用结果列表
     * @param durationMs  请求耗时（毫秒）
     */
    public ChatResult(String content, String finishReason, String tokenUsage,
                      List<ToolResult> toolResults, long durationMs) {
        this.content = content;
        this.finishReason = finishReason;
        this.tokenUsage = tokenUsage;
        this.toolResults = toolResults;
        this.durationMs = durationMs;
    }

    /** @return AI 回复的文本内容 */
    public String getContent() { return content; }

    /** @return 完成原因 */
    public String getFinishReason() { return finishReason; }

    /** @return Token 用量摘要 */
    public String getTokenUsage() { return tokenUsage; }

    /** @return 工具调用结果列表 */
    public List<ToolResult> getToolResults() { return toolResults; }

    /** @return 请求耗时（毫秒） */
    public long getDurationMs() { return durationMs; }
}
```

**核心字段**：
- content: String — AI 回复的文本内容
- finishReason: String — 完成原因（"stop" / "error" / "timeout"）
- tokenUsage: String — Token 用量
- toolResults: List\<ToolResult\> — 工具调用结果列表
- durationMs: long — 请求耗时

---

### 第 2 块：AgentResult

#### 类介绍

**设计动机**：AgentCoordinator.awaitResult() 的返回值 DTO。包含 Agent 执行状态和结果摘要。

**包路径**：lyclaw-engine → lyjew.com.lyclaw.dto

**类型**：DTO 类

```java
package lyjew.com.lyclaw.dto;

/**
 * Agent 执行结果 —— AgentCoordinator.awaitResult() 的返回值 DTO。
 *
 * <p>当 AgentCoordinator 派发一个任务给子 Agent 后，调用方通过 awaitResult()
 * 获取子 Agent 的执行结果。这个对象封装了执行状态、结果摘要和详细信息。</p>
 *
 * <p><b>设计动机</b>：子 Agent 的执行结果需要统一的数据结构，
 * 包含执行状态（成功/失败/超时）、摘要信息和详细内容，便于主 Agent 决策。</p>
 *
 * <p><b>使用场景</b>：
 * <ul>
 *   <li>AgentCoordinator.dispatch() 的 CompletableFuture 返回值类型</li>
 *   <li>AgentChannel 中传递的子 Agent 执行结果</li>
 * </ul>
 * </p>
 *
 * @since 1.0
 * @author LyClaw Team
 */
public class AgentResult {

    /** Agent ID —— 执行此任务的子 Agent 的唯一标识 */
    private final String agentId;

    /**
     * 执行状态。
     * <ul>
     *   <li>"COMPLETED" — 执行成功</li>
     *   <li>"FAILED" — 执行失败</li>
     *   <li>"TIMEOUT" — 执行超时</li>
     *   <li>"CANCELLED" — 已被取消</li>
     * </ul>
     */
    private final String status;

    /** 结果摘要 —— 简短的执行过程描述，用于主 Agent 快速了解结果 */
    private final String summary;

    /** 结果详情 —— 完整的执行结果内容，包含详细输出 */
    private final String detail;

    /** 执行耗时（毫秒） */
    private final long elapsedMs;

    /**
     * 构造一个 AgentResult 实例。
     *
     * @param agentId   Agent ID
     * @param status    执行状态
     * @param summary   结果摘要
     * @param detail    结果详情
     * @param elapsedMs 执行耗时（毫秒）
     */
    public AgentResult(String agentId, String status, String summary,
                       String detail, long elapsedMs) {
        this.agentId = agentId;
        this.status = status;
        this.summary = summary;
        this.detail = detail;
        this.elapsedMs = elapsedMs;
    }

    /** @return Agent ID */
    public String getAgentId() { return agentId; }

    /** @return 执行状态 */
    public String getStatus() { return status; }

    /** @return 结果摘要 */
    public String getSummary() { return summary; }

    /** @return 结果详情 */
    public String getDetail() { return detail; }

    /** @return 执行耗时（毫秒） */
    public long getElapsedMs() { return elapsedMs; }
}
```

**核心字段**：
- agentId: String — Agent ID
- status: String — 执行状态
- summary: String — 结果摘要
- detail: String — 结果详情
- elapsedMs: long — 执行耗时

---

### 第 3 块：SkillResult

#### 类介绍

**设计动机**：SkillExecutor.execute() 返回值 CompletableFuture\<SkillResult\> 的类型。

**包路径**：lyclaw-engine → lyjew.com.lyclaw.dto

**类型**：DTO 类

```java
package lyjew.com.lyclaw.dto;

/**
 * 技能执行结果 —— SkillExecutor.execute() 返回值 CompletableFuture&lt;SkillResult&gt; 的类型。
 *
 * <p>技能（Skill）是比工具（Tool）更高层次的抽象，一个技能内部可能包含
 * 多次模型调用和多个工具调用。SkillResult 封装了技能的整体执行结果。</p>
 *
 * <p><b>设计动机</b>：将技能执行的完成状态、输出内容、Token 消耗等信息
 * 封装为一个不可变对象，便于异步回调处理和日志记录。</p>
 *
 * <p><b>使用场景</b>：
 * <ul>
 *   <li>SkillExecutor.execute() 的 CompletableFuture 返回值类型</li>
 *   <li>SkillProgressCallback.onComplete() 的回调参数</li>
 * </ul>
 * </p>
 *
 * @since 1.0
 * @author LyClaw Team
 */
public class SkillResult {

    /** 技能 ID —— 标识是哪个技能的执行结果 */
    private final String skillId;

    /** 是否执行成功。true 表示技能正常执行完毕 */
    private final boolean success;

    /** 输出内容 —— 技能执行后的文本输出 */
    private final String output;

    /** 错误信息 —— 执行失败时的错误描述，成功时为 null */
    private final String error;

    /** Token 消耗 —— 技能执行期间所有模型调用的 Token 总和 */
    private final int tokenUsage;

    /** 执行耗时（毫秒） */
    private final long elapsedMs;

    /**
     * 构造一个 SkillResult 实例。
     *
     * @param skillId   技能 ID
     * @param success   是否成功
     * @param output    输出内容
     * @param error     错误信息
     * @param tokenUsage Token 消耗
     * @param elapsedMs 执行耗时（毫秒）
     */
    public SkillResult(String skillId, boolean success, String output,
                       String error, int tokenUsage, long elapsedMs) {
        this.skillId = skillId;
        this.success = success;
        this.output = output;
        this.error = error;
        this.tokenUsage = tokenUsage;
        this.elapsedMs = elapsedMs;
    }

    /** @return 技能 ID */
    public String getSkillId() { return skillId; }

    /** @return 是否执行成功 */
    public boolean isSuccess() { return success; }

    /** @return 输出内容 */
    public String getOutput() { return output; }

    /** @return 错误信息 */
    public String getError() { return error; }

    /** @return Token 消耗 */
    public int getTokenUsage() { return tokenUsage; }

    /** @return 执行耗时（毫秒） */
    public long getElapsedMs() { return elapsedMs; }
}
```

**核心字段**：
- skillId: String — 技能 ID
- success: boolean — 是否成功
- output: String — 输出内容
- error: String — 错误信息
- tokenUsage: int — Token 消耗
- elapsedMs: long — 执行耗时

---

### 第 4 块：EngineMetadata

#### 类介绍

**设计动机**：Engine.getMetadata() 的返回值。描述引擎的元信息：名称、版本、描述、模型列表和能力集。

**包路径**：lyclaw-engine → lyjew.com.lyclaw.engine

**类型**：类

```java
package lyjew.com.lyclaw.engine;

import java.util.List;
import java.util.Set;

/**
 * 引擎元信息 —— Engine.getMetadata() 的返回值。
 *
 * <p>描述了引擎的名称、版本、功能描述、支持的模型列表和能力集。
 * EngineSelector 可以根据这些元信息来决定哪个 Engine 最适合处理当前请求。</p>
 *
 * <p><b>设计动机</b>：当系统中有多个 Engine 实现时（DefaultEngine、ReasoningEngine、
 * PlanningEngine 等），调用方需要了解每个 Engine 的能力以做出路由选择。
 * EngineMetadata 提供了这种自描述能力。</p>
 *
 * <p><b>使用场景</b>：
 * <ul>
 *   <li>Engine.getMetadata() 的返回值</li>
 *   <li>EngineSelector 在路由决策时读取 Engine 的能力信息</li>
 *   <li>管理后台展示 Engine 列表</li>
 * </ul>
 * </p>
 *
 * @since 1.0
 * @author LyClaw Team
 */
public class EngineMetadata {

    /** 引擎名称，如 "default"、"reasoning"、"planning" */
    private final String name;

    /** 引擎版本号，遵循语义化版本规范 如 "1.0.0" */
    private final String version;

    /** 引擎功能描述，用于展示和管理 */
    private final String description;

    /** 支持的模型列表，如 ["minimax", "deepseek-openai"] */
    private final List<String> supportedModels;

    /**
     * 能力集 —— 引擎支持的高级功能。
     * 可能的值包括：
     * <ul>
     *   <li>"streaming" — 支持流式输出</li>
     *   <li>"tools" — 支持工具调用</li>
     *   <li>"thinking" — 支持模型思考模式</li>
     * </ul>
     */
    private final Set<String> capabilities;

    /**
     * 构造一个 EngineMetadata 实例。
     *
     * @param name            引擎名称
     * @param version         引擎版本
     * @param description     功能描述
     * @param supportedModels 支持的模型列表
     * @param capabilities    能力集
     */
    public EngineMetadata(String name, String version, String description,
                          List<String> supportedModels, Set<String> capabilities) {
        this.name = name;
        this.version = version;
        this.description = description;
        this.supportedModels = supportedModels;
        this.capabilities = capabilities;
    }

    /** @return 引擎名称 */
    public String getName() { return name; }

    /** @return 引擎版本号 */
    public String getVersion() { return version; }

    /** @return 引擎功能描述 */
    public String getDescription() { return description; }

    /** @return 支持的模型列表（不可变） */
    public List<String> getSupportedModels() { return supportedModels; }

    /** @return 能力集（不可变） */
    public Set<String> getCapabilities() { return capabilities; }
}
```

**核心字段**：
- name: String — 引擎名称
- version: String — 引擎版本
- description: String — 引擎描述
- supportedModels: List\<String\> — 支持的模型列表
- capabilities: Set\<String\> — 能力集（"streaming" / "tools" / "thinking"）

---

### 第 5 块：ToolResult

#### 类介绍

**设计动机**：工具执行完毕后返回的结果对象。包含执行结果或错误信息、执行耗时、Token 消耗。

**包路径**：lyclaw-engine → lyjew.com.lyclaw.tool

**类型**：类（值对象）

```java
package lyjew.com.lyclaw.tool;

/**
 * 工具执行结果 —— 工具执行完毕后的返回值。
 *
 * <p>每个 Tool.execute() 调用都会返回一个 ToolResult 对象，
 * 包含执行是否成功、结果数据、错误信息、耗时和 Token 消耗。</p>
 *
 * <p><b>设计动机</b>：统一所有工具（内置工具 / MCP 工具）的执行结果格式。
 * 上层调用方（ToolCallLoop / Pipeline）只需要处理这一种结果类型，
 * 不需要针对不同工具做不同的结果解析。</p>
 *
 * <p><b>使用场景</b>：
 * <ul>
 *   <li>Tool.execute() 的返回值</li>
 *   <li>ToolCallLoop 将 ToolResult 注入到 ChatContext 中</li>
 *   <li>ErrorPolicy.onToolError() 判断是否重试</li>
 * </ul>
 * </p>
 *
 * @since 1.0
 * @author LyClaw Team
 */
public class ToolResult {

    /** 是否执行成功。true 表示工具正常执行并返回结果 */
    private final boolean success;

    /**
     * 执行结果 —— 以 JSON 字符串格式存储。
     * 例如 web_search 工具返回：{"results": [{"title": "...", "url": "..."}]}
     */
    private final String result;

    /** 错误信息 —— 执行失败时的错误描述，成功时为 null */
    private final String error;

    /** 执行耗时（毫秒） */
    private final long elapsedMs;

    /** 工具执行消耗的 Token（如果工具调用涉及模型调用）；不消耗 Token 的工具为 0 */
    private final int tokenUsage;

    /**
     * 构造一个 ToolResult 实例。
     *
     * @param success    是否成功
     * @param result     执行结果（JSON 格式）
     * @param error      错误信息
     * @param elapsedMs  执行耗时（毫秒）
     * @param tokenUsage Token 消耗
     */
    public static ToolResult success(String result) {
        return new ToolResult(true, result, null, 0L, 0);
    }

    public static ToolResult failure(String error) {
        return new ToolResult(false, null, error, 0L, 0);
    }

    public ToolResult(boolean success, String result, String error,
                      long elapsedMs, int tokenUsage) {
        this.success = success;
        this.result = result;
        this.error = error;
        this.elapsedMs = elapsedMs;
        this.tokenUsage = tokenUsage;
    }

    /** @return 是否执行成功 */
    public boolean isSuccess() { return success; }

    /** @return 执行结果（JSON 字符串格式） */
    public String getResult() { return result; }

    /** @return 错误信息 */
    public String getError() { return error; }

    /** @return 执行耗时（毫秒） */
    public long getElapsedMs() { return elapsedMs; }

    /** @return Token 消耗 */
    public int getTokenUsage() { return tokenUsage; }
}
```

**核心字段**：
- success: boolean — 是否成功
- result: String — 执行结果（JSON 字符串格式）
- error: String — 错误信息
- elapsedMs: long — 执行耗时（毫秒）
- tokenUsage: int — 工具执行消耗的 Token

---

### 第 6 块：ToolErrorAction

#### 类介绍

**设计动机**：工具执行失败后，ToolCallPolicy 需要决定"接下来怎么做"。

**包路径**：lyclaw-engine → lyjew.com.lyclaw.tool

**类型**：枚举

**选型理由**：只有四种明确的决策路径，用枚举固化决策空间，避免 int/String 魔数。

```java
package lyjew.com.lyclaw.tool;

/**
 * 工具错误决策枚举 —— 工具执行失败后，ToolCallPolicy 决定接下来怎么做。
 *
 * <p>当工具调用抛出异常或返回错误时，ToolCallPolicy.handleToolError()
 * 根据当前上下文和错误类型返回一个 ToolErrorAction，
 * 引导 ToolCallLoop 执行相应的后续操作。</p>
 *
 * <p><b>设计动机</b>：只有四种明确的决策路径，用枚举固化决策空间，
 * 避免使用 int 常量或 String 魔数来传递错误处理策略。</p>
 *
 * <p><b>使用场景</b>：
 * <ul>
 *   <li>ToolCallPolicy.handleToolError() 的返回值</li>
 *   <li>ToolCallLoop 根据返回值决定循环行为</li>
 * </ul>
 * </p>
 *
 * @since 1.0
 * @author LyClaw Team
 */
public enum ToolErrorAction {

    /**
     * 重试当前工具调用。
     * ToolCallLoop 会重新发起相同参数的 execute() 调用。
     * 每次重试前等待时间按指数退避：1s、2s、4s...
     */
    RETRY,

    /**
     * 跳过当前工具。
     * 把错误信息作为 tool_result 注入到对话上下文中，
     * 让模型知道这个工具调用失败了并自行处理。
     */
    SKIP,

    /**
     * 终止整个工具调用循环。
     * 中断 while 循环，直接返回错误给用户，不再调用模型。
     */
    ABORT,

    /**
     * 使用备用工具/方案。
     * ToolCallPolicy 可以配置一个 fallback 工具名，
     * 循环自动切换到备用工具继续执行。
     */
    FALLBACK
}
```

**枚举值**：
- RETRY — 重试当前工具调用
- SKIP — 跳过当前工具，把错误信息注入上下文
- ABORT — 终止整个工具调用循环
- FALLBACK — 使用备用工具

---

### 第 7 块：MemoryContent

#### 类介绍

**设计动机**：MemoryManager.read() 返回值的包装对象。包含记忆正文和元数据。

**包路径**：lyclaw-engine → lyjew.com.lyclaw.memory

**类型**：类（值对象）

```java
package lyjew.com.lyclaw.memory;

import java.util.List;

/**
 * 记忆内容 —— MemoryManager.read() 的返回值包装对象。
 *
 * <p>包含记忆正文（Markdown 格式）、标题、启用状态、标签列表和相关性评分。
 * ContextBuilder 在构建模型输入时，根据 MemoryStrategy 的决策来决定
 * 是否将 MemoryContent 注入到 System Prompt 中。</p>
 *
 * <p><b>设计动机</b>：长期记忆存储在 lyclaw-common 的 Memory 实体中
 * （单例 id="global"，含 content/title/enabled/tags 字段），
 * 但引擎层需要一个专用于上下文构建的只读视图，外加相关性评分信息。</p>
 *
 * <p><b>使用场景</b>：
 * <ul>
 *   <li>MemoryManager.read() 的返回值</li>
 *   <li>MemoryStrategy.formatForContext() 的输入参数</li>
 *   <li>ContextBuilder 构建消息列表时读取</li>
 * </ul>
 * </p>
 *
 * @since 1.0
 * @author LyClaw Team
 */
public class MemoryContent {

    /** 记忆正文 —— Markdown 格式的文本内容 */
    private final String content;

    /** 人类可读的记忆标题，如 "用户偏好记忆"、"长期知识" */
    private final String title;

    /** 软开关。false 时 MemoryStrategy.shouldIncludeInContext() 返回 false */
    private final boolean enabled;

    /** 预留标签列表，用于记忆分类和检索 */
    private final List<String> tags;

    /**
     * 相关性评分 —— 用于上下文选择。
     * 0.0 表示不相关，1.0 表示完全相关。
     * 当有多条记忆时，只选择评分超过阈值的记忆注入上下文。
     */
    private final double relevanceScore;

    /**
     * 构造一个 MemoryContent 实例。
     *
     * @param content         记忆正文
     * @param title           记忆标题
     * @param enabled         是否启用
     * @param tags            标签列表
     * @param relevanceScore  相关性评分
     */
    public MemoryContent(String content, String title, boolean enabled,
                         List<String> tags, double relevanceScore) {
        this.content = content;
        this.title = title;
        this.enabled = enabled;
        this.tags = tags;
        this.relevanceScore = relevanceScore;
    }

    /** @return 记忆正文（Markdown 格式） */
    public String getContent() { return content; }

    /** @return 记忆标题 */
    public String getTitle() { return title; }

    /** @return 是否启用 */
    public boolean isEnabled() { return enabled; }

    /** @return 标签列表 */
    public List<String> getTags() { return tags; }

    /** @return 相关性评分 */
    public double getRelevanceScore() { return relevanceScore; }
}
```

**核心字段**：
- content: String — 记忆正文（Markdown 格式）
- title: String — 记忆标题
- enabled: boolean — 是否启用
- tags: List\<String\> — 标签列表
- relevanceScore: double — 相关性评分（0.0~1.0）

---

### 第 8 块：PageResult\<T\>

#### 类介绍

**设计动机**：分页查询的通用返回值。所有需要分页的场景统一使用。

**包路径**：lyclaw-engine → lyjew.com.lyclaw.common

**类型**：类（值对象），泛型参数 T 表示当前页数据的元素类型

```java
package lyjew.com.lyclaw.common;

import java.util.List;

/**
 * 分页查询返回值 —— 所有需要分页的场景统一使用。
 *
 * <p><b>设计动机</b>：当查询返回大量结果时，需要分页机制来避免一次传输过多数据。
 * PageResult 封装了当前页数据、总数、页码和每页大小，并提供便捷方法
 * hasMore() 和 getTotalPages() 供前端分页组件使用。</p>
 *
 * <p><b>使用场景</b>：
 * <ul>
 *   <li>MemoryManager.search() 的分页返回值</li>
 *   <li>TaskLedger.getRecords() 的分页查询</li>
 *   <li>SessionStorage.getAll() 的分页查询</li>
 * </ul>
 * </p>
 *
 * @param <T> 当前页数据的元素类型
 * @since 1.0
 * @author LyClaw Team
 */
public class PageResult<T> {

    /** 当前页的数据列表 */
    private final List<T> items;

    /** 总记录数 —— 满足查询条件的结果总数，不是当前页的数量 */
    private final long total;

    /** 当前页码 —— 从 1 开始 */
    private final int page;

    /** 每页大小 —— 每页最多包含的数据条数 */
    private final int size;

    /**
     * 构造一个 PageResult 实例。
     *
     * @param items 当前页数据
     * @param total 总记录数
     * @param page  当前页码（从 1 开始）
     * @param size  每页大小
     */
    public PageResult(List<T> items, long total, int page, int size) {
        this.items = items;
        this.total = total;
        this.page = page;
        this.size = size;
    }

    /**
     * 便捷工厂方法 —— 创建一页结果。
     *
     * @param <T>   元素类型
     * @param items 当前页数据
     * @param total 总记录数
     * @param page  当前页码
     * @param size  每页大小
     * @return PageResult 实例
     */
    public static <T> PageResult<T> of(List<T> items, long total, int page, int size) {
        return new PageResult<>(items, total, page, size);
    }

    /** @return 当前页数据 */
    public List<T> getItems() { return items; }

    /** @return 总记录数 */
    public long getTotal() { return total; }

    /** @return 当前页码 */
    public int getPage() { return page; }

    /** @return 每页大小 */
    public int getSize() { return size; }

    /** @return 是否还有更多数据（当前页之后还有数据） */
    public boolean hasMore() {
        return (long) page * size < total;
    }

    /** @return 总页数 */
    public long getTotalPages() {
        if (size <= 0) return 0;
        return (total + size - 1) / size;
    }
}
```

**核心字段**：
- items: List\<T\> — 当前页数据
- total: long — 总记录数
- page: int — 当前页码（从 1 开始）
- size: int — 每页大小

**便捷方法**：
- static \<T\> PageResult\<T\> of(...) — 工厂方法
- hasMore() — 是否还有更多数据
- getTotalPages() — 总页数

---

> **第一部分 DTO/值对象 完成（共 8 个文件）**

---

## 第二部分：核心接口

> **设计意图**：定义引擎层骨架 — 要做什么而非怎么做。只依赖 DTO 值对象和 common 模块的实体。各模块通过接口解耦，上层业务只和接口打交道。

---

### 第 9 块：Engine

#### 类介绍

**设计动机**：引擎顶层入口。每个 Engine 实现是一个独立的策略，EngineSelector 根据请求特征自动路由。

**包路径**：lyclaw-engine → lyjew.com.lyclaw.engine

**类型**：接口

**设计模式**：策略模式

```java
package lyjew.com.lyclaw.engine;

import lyjew.com.lyclaw.dto.ChatResult;
import lyjew.com.lyclaw.model.ChatRequest;
import reactor.core.publisher.Flux;

/**
 * 引擎顶层接口 — AI 对话处理的统一入口。
 *
 * <p>每个 Engine 实现代表一种独立的对话处理策略。EngineSelector 遍历所有
 * 注册的 Engine，调用 {@link #supports(ChatRequest)} 选择第一个匹配的引擎，
 * 然后通过 {@link #execute(ChatRequest)} 执行对话。</p>
 *
 * <p><b>设计动机</b>：不同的对话场景需要不同的处理逻辑——
 * 普通对话走 Pipeline 管道、推理任务走 Chain-of-Thought、
 * RAG 查询走检索增强流程。通过策略模式将这些逻辑解耦到独立的 Engine 实现中，
 * 新增场景只需新建类实现 Engine 接口 + {@code @Component} 自动注册。</p>
 *
 * <p><b>调用链路</b>：
 * <ol>
 *   <li>Controller 构建 ChatRequest</li>
 *   <li>EngineSelector.select(request) 返回匹配的 Engine</li>
 *   <li>Engine.execute(request) 执行对话</li>
 *   <li>返回 Flux&lt;String&gt; 流式结果给上层消费</li>
 * </ol>
 * </p>
 *
 * @since 1.0
 * @author LyClaw Team
 * @see EngineSelector
 * @see EngineMetadata
 */
public interface Engine {

    /**
     * 返回引擎的唯一标识名称，如 "default"、"reasoning"、"planning"。
     * 用于日志、监控和 EngineSelector 的调试输出。
     *
     * @return 引擎名称（非 null）
     */
    String getName();

    /**
     * 判断当前引擎是否支持处理这个请求。
     * EngineSelector 会遍历所有注册的 Engine，返回第一个 supports() 返回 true 的引擎。
     * 这就使得引擎选择逻辑完全由引擎自己决定，而不是由一个中心化的 if-else 判断。
     *
     * <p>实现示例：一个 ReasoningEngine 可以检查请求中是否包含 "reason"、"think" 关键词，
     * DefaultEngine 则始终返回 true 作为兜底。</p>
     *
     * @param request 用户发起的对话请求
     * @return true 表示当前引擎可以处理该请求
     */
    boolean supports(ChatRequest request);

    /**
     * 执行对话，返回流式响应。
     * 使用 Flux 而不是 List，是因为模型调用本身是流式的（逐 token 返回），
     * 引擎应该保持这种流式特性，让上层可以实时消费。
     *
     * <p>实现方必须保证：
     * <ul>
     *   <li>不阻塞调用线程</li>
     *   <li>内部错误通过 Flux.error() 传播</li>
     *   <li>执行完毕自动 complete</li>
     * </ul>
     * </p>
     *
     * @param request 用户发起的对话请求，包含消息列表、会话 ID、配置参数
     * @return Flux 流式响应，每个元素是一个文本块
     */
    Flux<String> execute(ChatRequest request);

    /**
     * 获取引擎的元信息，包括名称、版本、描述、支持的能力列表。
     * 用于运维监控面板展示和管理界面选择引擎。
     *
     * @return 引擎元信息（不可变，非 null）
     */
    EngineMetadata getMetadata();
}
```

---

### 第 10 块：Pipeline

#### 类介绍

**设计动机**：管道编排入口。Pipeline 是 DefaultEngine 内部的核心编排组件，将对话处理分解为多个可编排的阶段。

**包路径**：lyclaw-engine → lyjew.com.lyclaw.pipeline

**类型**：接口

**调用时序**：
1. ContextBuildStage — 构建上下文（注入记忆 + 会话历史 + 工具列表）
2. InterceptorStage — 执行拦截器链（限流/日志/脱敏）
3. ToolCallLoopStage — 模型调用 + 工具执行循环
4. MetricsStage — 采集监控指标
5. ResponseBuildStage — 构建最终响应

```java
package lyjew.com.lyclaw.pipeline;

import lyjew.com.lyclaw.context.ChatContext;

import java.util.List;

/**
 * 管道编排入口 —— 将对话处理分解为多个可编排的阶段（PipelineStage）。
 *
 * <p>Pipeline 是 DefaultEngine 内部的核心编排组件。DefaultEngine.execute()
 * 内部通过 PipelineBuilder 构建 Pipeline，然后调用 execute() 执行整个流程。</p>
 *
 * <p><b>设计动机</b>：不使用一个巨大的方法来实现对话处理流程，
 * 而是将流程拆分为 ContextBuild → Interceptor → ToolCallLoop → Metrics → ResponseBuild
 * 五个阶段。每个阶段独立实现 PipelineStage 接口，通过 PipelineBuilder 链式组装。
 * 新增阶段只需新建 PipelineStage 实现类 + addStage() 即可。</p>
 *
 * <p><b>为什么 Pipeline.execute() 不返回 ChatResult</b>：
 * Pipeline 只是编排阶段流程的容器，不直接处理返回值。
 * ChatResult 的构建由最后一个阶段（ResponseBuildStage）负责，
 * 结果存储在 ChatContext 中，后续由 Engine 消费。</p>
 *
 * @since 1.0
 * @author LyClaw Team
 * @see PipelineStage
 * @see PipelineBuilder
 */
public interface Pipeline {

    /**
     * 执行整个管道。每个 PipelineStage 按顺序依次执行，
     * 通过 {@link Chain} 控制阶段间的流转。
     *
     * <p>执行过程中出现异常时，由 ErrorPolicy 决定是重试还是终止。
     * 结果存放在 {@link ChatContext#getResult()} 中。</p>
     *
     * @param context 包含请求、会话、记忆、拦截器链的完整上下文
     */
    void execute(ChatContext context);

    /**
     * 获取当前管道的所有阶段列表，用于日志、监控和调试。
     *
     * @return 按执行顺序排列的阶段列表（不可变视图）
     */
    List<PipelineStage> getStages();
}
```

---

### 第 11 块：PipelineStage

#### 类介绍

**设计动机**：管道阶段抽象。每个 PipelineStage 是一个独立的处理单元，通过 Chain 链接。

**包路径**：lyclaw-engine → lyjew.com.lyclaw.pipeline

**类型**：接口

**设计模式**：模板方法模式 + 责任链模式

```java
package lyjew.com.lyclaw.pipeline;

import lyjew.com.lyclaw.context.ChatContext;

/**
 * 管道阶段抽象 —— Pipeline 中的一个独立处理步骤。
 *
 * <p>每个 PipelineStage 是一个处理单元，负责对话流程中一个明确定义的步骤。
 * 通过 Chain 对象链接多个阶段，每个阶段处理完后必须调用 {@link Chain#next(ChatContext)}
 * 将控制权传递给下一阶段。</p>
 *
 * <p><b>设计动机</b>：将对话处理流程拆分为多个独立阶段，
 * 每个阶段职责单一（单一职责原则），通过责任链模式串联。
 * 新增阶段只需新建类实现此接口，通过 PipelineBuilder.addStage() 加入管道。</p>
 *
 * <p><b>实现约束</b>：
 * <ul>
 *   <li>process() 内部必须调用 chain.next(context) 或 chain.breakChain(context)</li>
 *   <li>不调用 chain.next() 会导致管道卡住</li>
 *   <li>order 值越小越先执行，建议阶段间预留步长（如 100、200、300）以便后续插入</li>
 * </ul>
 * </p>
 *
 * @since 1.0
 * @author LyClaw Team
 * @see Chain
 * @see Pipeline
 */
public interface PipelineStage {

    /**
     * 执行当前阶段的处理逻辑。
     *
     * <p>典型实现模式：
     * <ol>
     *   <li>执行本阶段的业务逻辑</li>
     *   <li>调用 chain.next(context) 传递到下一阶段</li>
     * </ol>
     * </p>
     *
     * @param context 对话上下文（可读写，阶段间共享）
     * @param chain   阶段链控制器，用于传递到下一阶段或中断
     */
    void process(ChatContext context, Chain chain);

    /**
     * 获取阶段的执行顺序。值越小越先执行。
     *
     * <p>建议各阶段之间预留间隔（如 100、200、300），
     * 以便后续在已有阶段之间插入新阶段而不需要修改所有 order。</p>
     *
     * @return 执行优先级（值越小优先级越高）
     */
    int getOrder();

    /**
     * 获取阶段名称，用于日志输出、监控指标标签和调试。
     *
     * @return 阶段名称（非 null），如 "ContextBuild"、"Interceptor"、"ToolCallLoop"
     */
    String getStageName();
}
```

---

### 第 12 块：Chain

#### 类介绍

**设计动机**：阶段链控制。控制 PipelineStage 之间的流转——继续传递或中断。

**包路径**：lyclaw-engine → lyjew.com.lyclaw.pipeline

**类型**：接口

```java
package lyjew.com.lyclaw.pipeline;

import lyjew.com.lyclaw.context.ChatContext;

/**
 * 阶段链控制器 —— 控制 PipelineStage 之间的流转。
 *
 * <p>Pipeline 内部维护了一个 PipelineStage 列表和一个指向当前阶段的索引。
 * Chain 封装了索引递增、中断标记和当前阶段查询的逻辑。</p>
 *
 * <p><b>设计动机</b>：如果没有 Chain，每个 PipelineStage 都需要知道下一个 Stage 是谁，
 * 这就形成了强耦合。Chain 作为中间层解耦了相邻 Stage：每个 Stage 只调用
 * chain.next()，由 Chain 内部决定下一个 Stage 是谁。</p>
 *
 * <p><b>使用示例</b>（典型 Stage 实现）：
 * <pre>{@code
 * public void process(ChatContext ctx, Chain chain) {
 *     // 执行本阶段逻辑
 *     log.info("Stage {} 开始", getStageName());
 *     // 传给下一阶段
 *     chain.next(ctx);
 * }
 * }</pre>
 * </p>
 *
 * @since 1.0
 * @author LyClaw Team
 * @see PipelineStage
 */
public interface Chain {

    /**
     * 将控制权传递给链中的下一个 PipelineStage。
     * 如果当前已经是最后一个 Stage，调用此方法将结束管道执行。
     *
     * @param context 对话上下文
     * @throws IllegalStateException 如果管道已被中断（breakChain 后被调用）
     */
    void next(ChatContext context);

    /**
     * 中断管道执行，跳过当前 Stage 之后的所有 Stage。
     * 通常在某个前置 Stage 检测到不可继续的条件时调用
     * （如限流拦截器拒绝请求、上下文构建失败）。
     *
     * <p>调用此方法后，再调用 {@link #next(ChatContext)} 会抛出 IllegalStateException。</p>
     *
     * @param context 对话上下文
     */
    void breakChain(ChatContext context);

    /**
     * 获取当前正在执行的 Stage 序号（从 0 开始）。
     * 用于日志输出、监控指标和调试追踪。
     *
     * @return 当前 Stage 的索引
     */
    int getCurrentStage();
}
```

---

### 第 13 块：ContextBuilder

#### 类介绍

**设计动机**：上下文构建策略。将原始请求转换为模型可理解的消息列表。

**包路径**：lyclaw-engine → lyjew.com.lyclaw.context

**类型**：接口

**设计模式**：策略模式

```java
package lyjew.com.lyclaw.context;

import lyjew.com.lyclaw.memory.MemoryContent;
import lyjew.com.lyclaw.model.Session;
import lyjew.com.lyclaw.model.ToolDefinition;
import lyjew.com.lyclaw.model.Message;
import lyjew.com.lyclaw.model.ChatRequest;

import java.util.List;

/**
 * 上下文构建策略接口 —— 将原始请求、会话历史、长期记忆转换为模型可理解的消息列表。
 *
 * <p>大语言模型的输入是一个 {@code List<Message>}，包含 system 消息、历史对话消息
 * 和当前用户请求。ContextBuilder 负责组装这些消息，并决定记忆的注入方式。</p>
 *
 * <p><b>设计动机</b>：不同场景需要不同的上下文构建策略——
 * <ul>
 *   <li>全量窗口：把所有历史消息塞进去（简单但浪费 Token）</li>
 *   <li>滑动窗口：只保留最近的 N 轮对话（省 Token，但可能丢失上下文）</li>
 *   <li>摘要窗口：历史超出窗口时，用模型生成摘要替代（省 Token，保留关键信息）</li>
 * </ul>
 * 通过策略模式，ContextBuildStage 遍历所有 ContextBuilder 实现，
 * 调用 {@link #supports(ChatRequest)} 选择第一个匹配的策略。</p>
 *
 * @since 1.0
 * @author LyClaw Team
 * @see FullWindowContextBuilder
 */
public interface ContextBuilder {

    /**
     * 执行上下文构建，返回模型输入消息列表。
     *
     * <p>实现方需要保证：
     * <ol>
     *   <li>System Prompt 放在第一条（角色设定、工具描述）</li>
     *   <li>长期记忆在 System Prompt 之后注入（使用 {@code <memory>} 标签包裹）</li>
     *   <li>历史会话消息按时间顺序排列</li>
     *   <li>当前请求消息放在最后</li>
     *   <li>如果消息总长度超过模型上下文窗口，需要截断或摘要</li>
     * </ol>
     * </p>
     *
     * @param session      当前会话（含消息历史），不可为 null
     * @param memory       长期记忆内容，如果没有记忆则为 {@code MemoryContent} 空实例
     * @param toolDefinitions 当前可用的工具定义列表，模型据此了解可以调用哪些工具
     * @return 构建好的消息列表（不可变，非 null）。空列表表示构建失败
     */
    List<Message> buildContext(Session session, MemoryContent memory,
                               List<ToolDefinition> toolDefinitions);

    /**
     * 判断当前策略是否适用于这个请求。
     * 例如：SlidingWindowContextBuilder 在消息数超过阈值时返回 true，
     * FullWindowContextBuilder 在所有情况下都返回 true（作为兜底）。
     *
     * @param request 用户发起的对话请求
     * @return true 表示适用
     */
    boolean supports(ChatRequest request);
}
```

---

### 第 14 块：FullWindowContextBuilder

#### 类介绍

**设计动机**：全量窗口策略。把所有消息塞进去，不做截断。兜底策略。

**包路径**：lyclaw-engine → lyjew.com.lyclaw.context.impl

**类型**：类

**策略说明**：1. 注入 System Prompt（含工具描述）→ 2. 注入长期记忆 → 3. 追加会话历史 → 4. 注入当前请求

```java
package lyjew.com.lyclaw.context.impl;

import lyjew.com.lyclaw.context.ContextBuilder;
import lyjew.com.lyclaw.memory.MemoryContent;
import lyjew.com.lyclaw.model.ChatRequest;
import lyjew.com.lyclaw.model.Message;
import lyjew.com.lyclaw.model.Session;
import lyjew.com.lyclaw.model.ToolDefinition;

import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;

/**
 * 全量窗口上下文构建策略 —— 把所有消息塞进去，不做截断。兜底策略。
 *
 * <p>当没有其他 ContextBuilder（如 SlidingWindowContextBuilder、SummaryContextBuilder）
 * 匹配当前请求时，FullWindowContextBuilder 作为最后的兜底策略总是返回 true。</p>
 *
 * <p><b>构建顺序</b>：
 * <ol>
 *   <li>注入 System Prompt（包含系统角色设定、当前可用工具描述）</li>
 *   <li>注入长期记忆（如果 {@link MemoryContent#isEnabled()} 为 true，
 *       将内容用 {@code <memory>} 标签包裹后插入）</li>
 *   <li>追加会话中的所有历史消息</li>
 *   <li>注入当前请求的消息</li>
 * </ol>
 * </p>
 *
 * <p><b>适用场景</b>：会话轮次较少、上下文窗口充足的场景。
 * 对于长对话，消息总长度可能超出模型上下文窗口限制，建议配合滑动窗口策略使用。</p>
 *
 * @since 1.0
 * @author LyClaw Team
 * @see ContextBuilder
 */
@Component
public class FullWindowContextBuilder implements ContextBuilder {

    /**
     * 构建全量窗口的模型输入消息列表。
     *
     * @param session          当前会话（含消息历史），不可为 null
     * @param memory           长期记忆内容
     * @param toolDefinitions  当前可用的工具定义列表
     * @return 按序排列的消息列表（System → 记忆 → 历史 → 当前请求）
     */
    @Override
    public List<Message> buildContext(Session session, MemoryContent memory,
                                      List<ToolDefinition> toolDefinitions) {
        // 使用 ArrayList 以便按序插入，初始容量预设为会话消息数 + 2（System + 用户请求）
        List<Message> messages = new ArrayList<>(session.getMessages().size() + 2);

        // 步骤1：构建包含工具描述的 System Prompt
        Message systemMessage = buildSystemMessage(toolDefinitions);
        messages.add(systemMessage);

        // 步骤2：如果记忆内容可用且启用，注入长期记忆
        if (memory != null && memory.isEnabled() && memory.getContent() != null) {
            Message memoryMessage = buildMemoryMessage(memory);
            messages.add(memoryMessage);
        }

        // 步骤3：追加会话中的所有历史消息
        messages.addAll(session.getMessages());

        // 步骤4：注入当前请求的消息
        // 注意：当前请求的消息由上层调用方负责追加到 ChatRequest 中，
        // 这个方法是给 ContextBuildStage 用的，它会先更新 Session 再调用。
        // 所以这里 session.getMessages() 已经包含了当前请求消息。

        return messages;
    }

    /**
     * 始终返回 true，作为兜底策略。
     * 当没有其他 ContextBuilder 匹配时，使用全量窗口策略。
     *
     * @param request 用户发起的对话请求
     * @return 始终返回 true
     */
    @Override
    public boolean supports(ChatRequest request) {
        return true;
    }

    /**
     * 构建包含工具描述的 System Prompt 消息。
     *
     * @param toolDefinitions 当前可用的工具定义列表
     * @return System 类型的 Message
     */
    private Message buildSystemMessage(List<ToolDefinition> toolDefinitions) {
        StringBuilder sb = new StringBuilder();
        sb.append("你是一个智能 AI 助手，可以调用以下工具来完成任务：\n");
        for (ToolDefinition def : toolDefinitions) {
            sb.append("- ").append(def.getName())
              .append(": ").append(def.getDescription()).append("\n");
        }
        return Message.builder().role("system").content(sb.toString()).build();
    }

    /**
     * 构建包含长期记忆的消息。
     * 使用 {@code <memory>} 标签包裹，让模型知道这是长期记忆而不是当前对话。
     *
     * @param memory 长期记忆内容
     * @return System 类型的 Message（记忆以 system 角色注入）
     */
    private Message buildMemoryMessage(MemoryContent memory) {
        String wrapped = "<memory>\n" + memory.getContent() + "\n</memory>";
        return Message.builder().role("system").content(wrapped).build();
    }
}
```

---

### 第 15 块：Interceptor

#### 类介绍

**设计动机**：拦截器抽象。在请求处理前后执行横切关注点（限流、日志、脱敏、缓存等）。

**包路径**：lyclaw-engine → lyjew.com.lyclaw.interceptor

**类型**：接口

```java
package lyjew.com.lyclaw.interceptor;

import lyjew.com.lyclaw.context.ChatContext;
import lyjew.com.lyclaw.dto.ChatResult;

/**
 * 拦截器抽象 —— 在请求处理前后执行横切关注点。
 *
 * <p>类似于 Spring MVC 的 HandlerInterceptor，Interceptor 在 Pipeline 的
 * InterceptorStage 中被执行。preHandle 在 ToolCallLoop 之前调用，
 * postHandle 在 ResponseBuildStage 构建完 ChatResult 之后调用。</p>
 *
 * <p><b>设计动机</b>：日志记录、限流检查、敏感数据脱敏、审计日志等横切关注点
 * 不应该散落在各业务代码中。通过拦截器机制，将这些关注点集中到 Interceptor 中，
 * 通过 InterceptorChain 统一管理。</p>
 *
 * <p><b>典型拦截器及执行顺序</b>：
 * <ul>
 *   <li>RateLimitInterceptor（order=10）：最先执行，检查请求频率</li>
 *   <li>SensitiveDataInterceptor（order=30）：对输入脱敏</li>
 *   <li>LoggingInterceptor（order=100）：记录请求响应日志</li>
 * </ul>
 * </p>
 *
 * @since 1.0
 * @author LyClaw Team
 * @see InterceptorChain
 */
public interface Interceptor {

    /**
     * 在请求处理前执行。
     *
     * <p>可以做以下事情：
     * <ul>
     *   <li>修改 ChatContext（如注入额外属性）</li>
     *   <li>检查条件（如限流检查），返回 false 中断处理流程</li>
     *   <li>记录开始时间用于后续计算耗时</li>
     * </ul>
     * </p>
     *
     * @param context 对话上下文（可读写）
     * @return true 表示继续处理，false 表示中断流程
     */
    boolean preHandle(ChatContext context);

    /**
     * 在请求处理后执行。
     *
     * <p>此时 ChatResult 已被构建，可以：
     * <ul>
     *   <li>修改响应内容（如对输出脱敏）</li>
     *   <li>记录完成日志（耗时、Token 用量）</li>
     *   <li>采集指标数据</li>
     * </ul>
     * </p>
     *
     * @param context 对话上下文
     * @param result  构建好的对话结果（可修改）
     */
    void postHandle(ChatContext context, ChatResult result);

    /**
     * 获取拦截器的执行顺序。数字越小越先执行。
     *
     * <p>建议预留步长（如 10、20、30），以便后续插入新拦截器。
     * 返回 {@link Integer#MIN_VALUE} 表示最先执行（限流拦截器使用）。</p>
     *
     * @return 执行优先级（值越小优先级越高）
     */
    int getOrder();
}
```

---

### 第 16 块：InterceptorChain

#### 类介绍

**设计动机**：拦截器链管理器。管理所有注册的 Interceptor，按 order 排序后统一执行。

**包路径**：lyclaw-engine → lyjew.com.lyclaw.interceptor.impl

**类型**：类

```java
package lyjew.com.lyclaw.interceptor.impl;

import lyjew.com.lyclaw.context.ChatContext;
import lyjew.com.lyclaw.dto.ChatResult;
import lyjew.com.lyclaw.interceptor.Interceptor;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import org.springframework.stereotype.Component;

/**
 * 拦截器链管理器 —— 管理所有注册的 Interceptor，按 order 排序后统一执行。
 *
 * <p>采用 CopyOnWriteArrayList 存储拦截器列表，确保并发注册安全。
 * preHandle 按 order 升序执行（order 小的先执行），
 * postHandle 按 order 降序执行（order 大的先执行，类似 try-catch 的嵌套语义）。</p>
 *
 * <p><b>设计动机</b>：如果每个 Pipeline 代码里都手动硬编码限流拦截器、日志拦截器的调用顺序，
 * 那么新增或移除拦截器就需要修改 Pipeline 代码。InterceptorChain 将拦截器统一管理，
 * Pipeline 中的 InterceptorStage 只需调用 chain.preHandle() 和 chain.postHandle() 即可。</p>
 *
 * <p><b>关于并发安全</b>：addInterceptor 和 removeInterceptor 可能在任何时候被调用
 * （如运行时动态注册新的工具拦截器），而 preHandle/postHandle 在每次请求时被调用。
 * 用 CopyOnWriteArrayList 确保遍历时不会抛出 ConcurrentModificationException。</p>
 *
 * @since 1.0
 * @author LyClaw Team
 * @see Interceptor
 */
@Component
public class InterceptorChain {

    /** 已排序的拦截器列表。排序在每次 add/remove 后重新计算 */
    private final List<Interceptor> interceptors = new ArrayList<>();

    /**
     * 注册一个拦截器。如果同名拦截器已经存在则覆盖。
     * 每次添加后重新排序拦截器列表。
     *
     * <p>注意：此方法不是线程安全的，建议在启动阶段通过 Spring 注入完成注册。</p>
     *
     * @param interceptor 拦截器实例，不可为 null
     */
    public void addInterceptor(Interceptor interceptor) {
        this.interceptors.add(interceptor);
        // 每次添加后重新排序，确保 preHandle 的执行顺序总是正确的
        this.interceptors.sort(Comparator.comparingInt(Interceptor::getOrder));
    }

    /**
     * 移除一个拦截器。
     *
     * @param interceptor 要移除的拦截器实例
     */
    public void removeInterceptor(Interceptor interceptor) {
        this.interceptors.remove(interceptor);
        // 移除后重新排序
        this.interceptors.sort(Comparator.comparingInt(Interceptor::getOrder));
    }

    /**
     * 按 order 升序执行所有拦截器的 preHandle 方法。
     * 如果任何一个 preHandle 返回 false，则中断执行并返回 false。
     *
     * @param context 对话上下文
     * @return true 表示所有拦截器都通过，false 表示有拦截器拒绝了请求
     */
    public boolean preHandle(ChatContext context) {
        for (Interceptor interceptor : interceptors) {
            if (!interceptor.preHandle(context)) {
                // 记录被哪个拦截器拒绝，便于后续排查
                return false;
            }
        }
        return true;
    }

    /**
     * 按 order 降序执行所有拦截器的 postHandle 方法。
     * 降序执行确保拦截器像 try-catch 嵌套那样执行：
     * 先进入的拦截器的 postHandle 最后执行。
     *
     * @param context 对话上下文
     * @param result  构建好的对话结果
     */
    public void postHandle(ChatContext context, ChatResult result) {
        // 降序排列：order 大的先执行 postHandle
        List<Interceptor> reversed = new ArrayList<>(interceptors);
        reversed.sort(Comparator.comparingInt(Interceptor::getOrder).reversed());
        for (Interceptor interceptor : reversed) {
            interceptor.postHandle(context, result);
        }
    }

    /**
     * 获取当前所有已注册且已排序的拦截器列表。
     * 返回的是不可变视图，防止外部修改内部列表。
     *
     * @return 按 order 升序排列的拦截器列表
     */
    public List<Interceptor> getInterceptors() {
        return Collections.unmodifiableList(interceptors);
    }
}
```

---

### 第 17 块：RateLimitInterceptor

#### 类介绍

**设计动机**：限流拦截器。限制单位时间内的请求次数，超过配额则拒绝请求。

**包路径**：lyclaw-engine → lyjew.com.lyclaw.interceptor.impl

**类型**：类

```java
package lyjew.com.lyclaw.interceptor.impl;

import lyjew.com.lyclaw.context.ChatContext;
import lyjew.com.lyclaw.dto.ChatResult;
import lyjew.com.lyclaw.interceptor.Interceptor;
import org.springframework.stereotype.Component;

/**
 * 限流拦截器 —— 限制单位时间内的请求次数，超过配额则拒绝请求。
 *
 * <p>使用令牌桶算法控制请求速率。每个会话有一个独立的令牌桶，
 * 每秒补充一定数量的令牌，每个请求消耗一个令牌。令牌不足时返回 false
 * 中断处理流程。</p>
 *
 * <p><b>设计动机</b>：防止单个用户的突发请求耗尽系统资源。
 * 如果不做限流，恶意用户或错误客户端可能发起大量请求导致后端模型 API
 * 调用超限（429 Too Many Requests），从而影响其他正常用户。</p>
 *
 * <p><b>执行顺序</b>：order = Integer.MIN_VALUE，确保在所有拦截器中最先执行。
 * 如果请求被限流拦截，后续的拦截器就不需要执行了，节省资源。</p>
 *
 * @since 1.0
 * @author LyClaw Team
 * @see Interceptor
 */
@Component
public class RateLimitInterceptor implements Interceptor {

    /** 每秒允许的请求数。可以通过构造函数或 setter 配置 */
    private int permitsPerSecond = 10;

    /**
     * 检查当前请求是否超过限流配额。
     * 超过配额时返回 false，中断请求处理。
     *
     * @param context 对话上下文
     * @return true 允许通过，false 拒绝请求
     */
    @Override
    public boolean preHandle(ChatContext context) {
        // 此处为简化实现，使用令牌桶算法的伪代码：
        // 1. 根据 sessionId 获取对应的令牌桶
        // 2. 尝试获取一个令牌
        // 3. 获取成功返回 true，失败返回 false
        //
        // 生产环境建议使用 Guava RateLimiter 或 Redis 分布式限流
        return true; // 简化实现，真实场景需要替换
    }

    /**
     * 请求处理完成后，更新令牌桶状态（释放资源）。
     *
     * @param context 对话上下文
     * @param result  对话结果
     */
    @Override
    public void postHandle(ChatContext context, ChatResult result) {
        // 可在此处更新令牌桶统计信息
    }

    /**
     * 返回 Integer.MIN_VALUE，确保在所有拦截器中最先执行。
     * 如果请求被限流拦截，后续的拦截器就不需要执行。
     *
     * @return Integer.MIN_VALUE
     */
    @Override
    public int getOrder() {
        return Integer.MIN_VALUE;
    }
}
```

---

### 第 18 块：SensitiveDataInterceptor

#### 类介绍

**设计动机**：敏感数据脱敏。对用户输入和模型输出中的敏感信息（手机号、身份证、密码等）进行替换或遮蔽。

**包路径**：lyclaw-engine → lyjew.com.lyclaw.interceptor.impl

**类型**：类

```java
package lyjew.com.lyclaw.interceptor.impl;

import lyjew.com.lyclaw.context.ChatContext;
import lyjew.com.lyclaw.dto.ChatResult;
import lyjew.com.lyclaw.interceptor.Interceptor;

import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * 敏感数据脱敏拦截器 —— 对用户输入和模型输出中的敏感信息进行替换或遮蔽。
 *
 * <p>支持以下脱敏规则（可通过配置文件扩展）：
 * <ul>
 *   <li>手机号：13812345678 → 138****5678</li>
 *   <li>身份证号：110101199001011234 → 110101********1234</li>
 *   <li>邮箱：user@example.com → u***@example.com</li>
 *   <li>密码/密钥：通过正则匹配 "password"、"secret"、"key" 等关键词后的值</li>
 * </ul>
 * </p>
 *
 * <p><b>设计动机</b>：用户的对话内容可能包含隐私信息，如果不做脱敏，
 * 这些信息会被发送给模型 API，也会被记录到日志中，存在数据泄露风险。</p>
 *
 * @since 1.0
 * @author LyClaw Team
 * @see Interceptor
 */
@Component
public class SensitiveDataInterceptor implements Interceptor {

    /** 手机号匹配正则：11 位数字，以 1 开头 */
    private static final Pattern PHONE_PATTERN = Pattern.compile("1[3-9]\\d{9}");

    /** 身份证号匹配正则：18 位数字（末位可能是 X） */
    private static final Pattern ID_CARD_PATTERN = Pattern.compile("\\d{17}[\\dXx]");

    /**
     * 请求处理前遍历消息列表，对匹配脱敏规则的内容进行替换。
     *
     * @param context 对话上下文
     * @return 始终返回 true（脱敏不会阻止请求处理）
     */
    @Override
    public boolean preHandle(ChatContext context) {
        // 此处为简化实现。真实场景需要：
        // 1. 遍历 context.getMessages() 中的每条消息
        // 2. 对每条消息的 content 应用脱敏规则
        // 3. 替换匹配的内容
        return true;
    }

    /**
     * 模型回复输出时，对回复内容再次脱敏（防止模型输出了敏感数据）。
     *
     * @param context 对话上下文
     * @param result  对话结果
     */
    @Override
    public void postHandle(ChatContext context, ChatResult result) {
        // 对 result.getContent() 进行同样的脱敏处理
    }

    /**
     * 返回 30，在 RateLimitInterceptor 之后执行。
     *
     * @return 30
     */
    @Override
    public int getOrder() {
        return 30;
    }
}
```

---

### 第 19 块：LoggingInterceptor

#### 类介绍

**设计动机**：日志记录。记录每次 API 请求的开始时间、请求摘要、执行耗时、Token 用量等信息。

**包路径**：lyclaw-engine → lyjew.com.lyclaw.interceptor.impl

**类型**：类

```java
package lyjew.com.lyclaw.interceptor.impl;

import lyjew.com.lyclaw.context.ChatContext;
import lyjew.com.lyclaw.dto.ChatResult;
import lyjew.com.lyclaw.interceptor.Interceptor;
import org.springframework.stereotype.Component;

/**
 * 日志记录拦截器 —— 记录每次 API 请求的开始时间、请求摘要、执行耗时、Token 用量等信息。
 *
 * <p><b>记录内容包括</b>：
 * <ul>
 *   <li>请求开始时间 + 请求摘要（消息数、用户 ID）</li>
 *   <li>请求处理耗时</li>
 *   <li>Token 用量（提示词 Token + 生成 Token）</li>
 *   <li>完成的轮次（如果涉及工具调用循环）</li>
 *   <li>最终完成原因（stop / error / timeout）</li>
 * </ul>
 * </p>
 *
 * <p><b>设计动机</b>：没有日志就无法监控和排查问题。
 * 日志记录不应该散落在各个 PipelineStage 中，
 * 而应该通过拦截器统一处理。</p>
 *
 * @since 1.0
 * @author LyClaw Team
 * @see Interceptor
 */
@Component
public class LoggingInterceptor implements Interceptor {

    /** 请求开始时间，存在 ChatContext 的 attributes 中 */
    private static final String KEY_START_TIME = "_log_start_time";

    /**
     * 记录请求开始时间和请求摘要。
     *
     * @param context 对话上下文
     * @return 始终返回 true（日志记录不会中断请求处理）
     */
    @Override
    public boolean preHandle(ChatContext context) {
        // 记录开始时间到 context 的 attributes 中，供 postHandle 使用
        context.setAttribute(KEY_START_TIME, System.currentTimeMillis());
        // 记录请求摘要：会话 ID、消息数量
        return true;
    }

    /**
     * 计算耗时 + Token 用量 + 记录完成日志。
     *
     * @param context 对话上下文
     * @param result  对话结果
     */
    @Override
    public void postHandle(ChatContext context, ChatResult result) {
        Long startTime = (Long) context.getAttribute(KEY_START_TIME);
        if (startTime != null) {
            long elapsed = System.currentTimeMillis() - startTime;
            // 记录日志内容：会话ID、耗时、完成原因、Token用量
        }
    }

    /**
     * 返回 100，在 RateLimitInterceptor 和 SensitiveDataInterceptor 之后执行。
     *
     * @return 100
     */
    @Override
    public int getOrder() {
        return 100;
    }
}
```

---

### 第 20 块：Tool

#### 类介绍

**设计动机**：工具抽象。所有工具必须实现此接口。ToolRegistry 统一管理注册和执行。

**包路径**：lyclaw-engine → lyjew.com.lyclaw.tool

**类型**：接口

```java
package lyjew.com.lyclaw.tool;

import lyjew.com.lyclaw.context.ChatContext;
import lyjew.com.lyclaw.model.ToolCall;
import lyjew.com.lyclaw.model.ToolDefinition;

/**
 * 工具抽象接口 —— 所有工具必须实现此接口。
import org.springframework.stereotype.Component;
 *
 * <p>工具是引擎中"可被模型调用的功能单元"。模型在生成回复时，
 * 如果判定需要调用某个工具，会在响应中包含 ToolCall 对象。
 * ToolCallLoop 根据 ToolCall 中的 name 找到对应的 Tool 并执行。</p>
 *
 * <p><b>设计动机</b>：将各种功能（搜索网页、计算数学、获取时间、操作数据库等）
 * 统一为 Tool 接口。ToolRegistry 管理所有 Tool 的生命周期，
 * ToolCallLoop 统一调度执行。新增工具只需新建类实现 Tool 接口并注册即可。</p>
 *
 * <p><b>与 MCP 的关系</b>：MCP（Model Context Protocol）工具通过 McpToolAdapter
 * 适配为 Tool 接口，这样引擎层不需要感知 MCP 协议的具体细节。</p>
 *
 * @since 1.0
 * @author LyClaw Team
 * @see ToolRegistry
 * @see ToolCallLoop
 */
public interface Tool {

    /**
     * 获取工具名称，全局唯一。如 "web_search"、"calculator"。
     * 模型返回的 ToolCall.name 与此名称匹配。
     *
     * @return 工具名称（非 null，全局唯一）
     */
    String getName();

    /**
     * 执行工具。
     *
     * @param toolCall 模型返回的工具调用请求，包含工具名和参数
     * @param context  当前对话上下文，可用于获取会话信息、注入结果
     * @return 工具执行结果
     */
    ToolResult execute(ToolCall toolCall, ChatContext context);

    /**
     * 获取工具定义。定义中包含名称、描述、参数 JSON Schema，
     * 会被发送给模型，让模型知道这个工具的功能和参数格式。
     *
     * @return 工具定义（非 null），来源于 lyjew.com.lyclaw.model.ToolDefinition
     */
    ToolDefinition getDefinition();
}
```

---

### 第 21 块：ToolRegistry

#### 类介绍

**设计动机**：工具注册表。管理所有 Tool 的注册、查找和执行。

**包路径**：lyclaw-engine → lyjew.com.lyclaw.tool

**类型**：接口

```java
package lyjew.com.lyclaw.tool;

import lyjew.com.lyclaw.model.ToolCall;
import lyjew.com.lyclaw.model.ToolDefinition;

import java.util.List;

/**
 * 工具注册表接口 —— 管理所有 Tool 的注册、查找和执行。
 *
 * <p>ToolRegistry 是引擎中所有工具的集中管理点。启动时由 Spring 自动扫描
 * 并注册所有 {@code @Component} 标注的 Tool 实现。
 * 运行时通过 {@link #execute(ToolCall)} 根据模型返回的 ToolCall 执行对应工具。</p>
 *
 * <p><b>设计动机</b>：如果不通过 Registry 管理，每次执行工具时都需要手动
 * if-else 判断 toolCall.getName() 来路由。通过 Registry，新增工具时
 * 只需注册，不需要修改路由代码。</p>
 *
 * <p><b>关于注册冲突</b>：同名工具第二次注册应抛出异常（如 {@code IllegalArgumentException}），
 * 防止意外覆盖导致生产环境行为不一致。</p>
 *
 * @since 1.0
 * @author LyClaw Team
 * @see Tool
 * @see DefaultToolRegistry
 */
public interface ToolRegistry {

    /**
     * 注册一个工具。同名工具第二次注册抛异常。
     *
     * @param tool 工具实例，不可为 null
     * @throws IllegalArgumentException 如果同名工具已注册
     */
    void register(Tool tool);

    /**
     * 按名称查找工具。
     *
     * @param name 工具名称
     * @return 匹配的工具，未找到返回 null
     */
    Tool get(String name);

    /**
     * 返回所有已注册工具的定义列表。
     * ContextBuilder 需要用此列表构建 System Prompt 中的工具描述。
     *
     * @return 工具定义列表（不可变，非 null）
     */
    List<ToolDefinition> getAllDefinitions();

    /**
     * 按 toolCall 中的 name 执行对应工具。
     * 如果找不到对应的工具，返回一个包含错误信息的结果。
     *
     * @param toolCall 模型返回的工具调用请求
     * @return 工具执行结果
     */
    ToolResult execute(ToolCall toolCall, ChatContext context);
}
```

---

### 第 22 块：ToolCallPolicy

#### 类介绍

**设计动机**：工具调用循环策略。控制"模型调用 + 工具执行"循环何时继续、何时停止以及在出错时做什么。

**包路径**：lyclaw-engine → lyjew.com.lyclaw.tool

**类型**：接口

```java
package lyjew.com.lyclaw.tool;

import lyjew.com.lyclaw.context.ChatContext;
import lyjew.com.lyclaw.model.ToolCall;

/**
 * 工具调用循环策略接口 —— 控制 ToolCallLoop 的行为。
 *
 * <p>ToolCallLoop 负责执行"模型调用 + 工具执行"的循环。
 * ToolCallPolicy 定义了循环的边界条件和错误处理策略，
 * 使 ToolCallLoop 的核心逻辑可以保持稳定，而循环策略可以灵活替换。</p>
 *
 * <p><b>可替换的策略实现</b>：
 * <ul>
 *   <li>DefaultToolCallPolicy：最多 10 轮，超出则终止</li>
 *   <li>BudgetAwarePolicy：根据 Token 预算动态决定是否继续</li>
 *   <li>ModelDrivenPolicy：让模型自己决定是否继续（TLS 1.3 的 max_tool_calls）</li>
 * </ul>
 * </p>
 *
 * @since 1.0
 * @author LyClaw Team
 * @see ToolCallLoop
 */
public interface ToolCallPolicy {

    /**
     * 获取最大工具调用轮次。超出此轮次后强制终止循环。
     *
     * @return 最大轮次
     */
    int getMaxRounds();

    /**
     * 判断是否继续循环。在每一轮结束后调用。
     *
     * @param context      当前对话上下文
     * @param currentRound 已完成轮次（从 0 开始）
     * @return true 表示继续下一轮，false 表示终止
     */
    boolean shouldContinue(ChatContext context, int currentRound);

    /**
     * 工具执行出错时的决策。返回不同的 ToolErrorAction 引导循环下一步行为。
     *
     * @param toolCall 出错的工具调用
     * @param e        捕获的异常
     * @param context  当前对话上下文
     * @return 错误处理动作（RETRY / SKIP / ABORT / FALLBACK）
     */
    ToolErrorAction handleToolError(ToolCall toolCall, Exception e, ChatContext context);

    /**
     * 判断是否应该重试当前工具调用。在 handleToolError 返回 RETRY 后被调用。
     *
     * @param toolCall   出错的工具调用
     * @param e          捕获的异常
     * @param retryCount 已重试次数（从 0 开始）
     * @return true 表示可以继续重试
     */
    boolean shouldRetryOnError(ToolCall toolCall, Exception e, int retryCount);
}
```

---

### 第 23 块：Skill

#### 类介绍

**设计动机**：技能抽象。技能是比工具更高层次的抽象，一个技能内部可能包含多次模型调用和工具调用。

**包路径**：lyclaw-engine → lyjew.com.lyclaw.skill

**类型**：接口

```java
package lyjew.com.lyclaw.skill;

import lyjew.com.lyclaw.context.ChatContext;
import lyjew.com.lyclaw.dto.SkillResult;

import java.util.concurrent.CompletableFuture;

/**
 * 技能抽象接口 —— 比工具更高层次的可复用能力单元。
 *
 * <p>工具（Tool）是原子操作（如搜索、计算、查时间），技能（Skill）是编排后的复合能力。
 * 一个技能内部可能包含多次模型调用和多个工具调用。例如：
 * <ul>
 *   <li>"写周报"技能：读取备忘录 → 分析本周工作 → 调用模型生成 → 格式化输出</li>
 *   <li>"竞品分析"技能：搜索竞品信息 → 调用模型分析 → 生成对比表格</li>
 * </ul>
 * </p>
 *
 * <p><b>与 Tool 的区别</b>：
 * <ul>
 *   <li>Tool 是原子操作，Skill 是复合操作</li>
 *   <li>Tool 由模型调用触发，Skill 由上层业务逻辑触发</li>
 *   <li>Tool 同步执行，Skill 异步执行（CompletableFuture）</li>
 * </ul>
 * </p>
 *
 * @since 1.0
 * @author LyClaw Team
 * @see SkillExecutor
 * @see SkillRegistry
 */
public interface Skill {

    /**
     * 获取技能唯一标识。全局唯一，用于注册和查找。
     *
     * @return 技能 ID（非 null）
     */
    String getSkillId();

    /**
     * 获取技能名称。人类可读，用于展示和管理。
     *
     * @return 技能名称
     */
    String getName();

    /**
     * 获取技能描述。说明技能的用途和适用场景。
     *
     * @return 技能描述
     */
    String getDescription();

    /**
     * 异步执行技能。
     *
     * @param context 当前对话上下文
     * @return CompletableFuture，成功时包含 SkillResult
     */
    CompletableFuture<SkillResult> execute(ChatContext context);
}
```

---

### 第 24 块：SkillType

#### 类介绍

**设计动机**：技能类型枚举。区分内置技能、用户自定义技能和复合技能。

**包路径**：lyclaw-engine → lyjew.com.lyclaw.skill

**类型**：枚举

```java
package lyjew.com.lyclaw.skill;

/**
 * 技能类型枚举 —— 区分不同来源和复杂度的技能。
 *
 * <p><b>设计动机</b>：不同类型的技能在注册、执行、展示时可能有不同的处理逻辑。
 * 用枚举固化所有技能类型，避免 String 类型的硬编码。</p>
 *
 * @since 1.0
 * @author LyClaw Team
 */
public enum SkillType {

    /** 系统内置技能，不可删除不可修改。例如系统帮助、默认行为 */
    BUILTIN,

    /** 用户自定义技能，用户可以通过 API 或 UI 创建。存储在后端的技能仓库中 */
    USER_DEFINED,

    /** 复合技能，由多个子技能按 DAG 编排而成。通过 SkillGraph 定义依赖关系 */
    COMPOSITE
}
```

---

### 第 25 块：SkillExecutor

#### 类介绍

**设计动机**：技能执行器抽象。负责技能的异步执行、取消、进度追踪。

**包路径**：lyclaw-engine → lyjew.com.lyclaw.skill

**类型**：接口

```java
package lyjew.com.lyclaw.skill;

import lyjew.com.lyclaw.context.ChatContext;
import lyjew.com.lyclaw.dto.SkillResult;

import java.util.concurrent.CompletableFuture;

/**
 * 技能执行器接口 —— 负责技能的异步执行、取消和进度追踪。
 *
 * <p>技能执行是异步的（{@link Skill#execute(ChatContext)} 返回 CompletableFuture），
 * SkillExecutor 在此基础上提供更高级的功能：取消正在执行的技能、
 * 查询执行进度、设置全局进度回调。</p>
 *
 * <p><b>设计动机</b>：一个复杂技能可能执行数秒甚至更久，
 * 调用方可能需要取消、查看进度或接收进度通知。
 * SkillExecutor 将这些能力统一封装。</p>
 *
 * @since 1.0
 * @author LyClaw Team
 * @see Skill
 * @see SkillProgressCallback
 */
public interface SkillExecutor {

    /**
     * 异步执行技能。
     *
     * @param skill   要执行的技能，不可为 null
     * @param context 当前对话上下文
     * @return CompletableFuture，完成时包含技能执行结果
     */
    CompletableFuture<SkillResult> execute(Skill skill, ChatContext context);

    /**
     * 取消正在执行的技能。
     *
     * @param skillId 要取消的技能 ID
     * @return true 表示取消成功，false 表示技能不存在或已完成
     */
    boolean cancel(String skillId);

    /**
     * 获取技能执行进度。
     *
     * @param skillId 技能 ID
     * @return 进度值（0.0 ~ 1.0），-1 表示技能不存在
     */
    double getProgress(String skillId);

    /**
     * 设置全局进度回调。
     *
     * @param callback 进度回调接口，不可为 null
     */
    void setProgressCallback(SkillProgressCallback callback);
}
```

---

### 第 26 块：SkillProgressCallback

#### 类介绍

**设计动机**：技能进度回调接口。在技能执行过程中通知调用方进度、完成或错误。

**包路径**：lyclaw-engine → lyjew.com.lyclaw.skill

**类型**：接口

```java
package lyjew.com.lyclaw.skill;

import lyjew.com.lyclaw.dto.SkillResult;

/**
 * 技能进度回调接口 —— 在技能执行过程中通知调用方。
 *
 * <p>当技能执行时，SkillExecutor 会定期调用回调方法，
 * 让调用方可以实时了解技能的执行进度和状态。
 * 适用于需要展示执行进度的场景（如 WebSocket 推送进度给前端）。</p>
 *
 * <p><b>设计动机</b>：如果不提供回调机制，调用方只能轮询
 * {@link SkillExecutor#getProgress(String)} 来获取进度。
 * 回调方式更高效、更及时。</p>
 *
 * @since 1.0
 * @author LyClaw Team
 */
public interface SkillProgressCallback {

    /**
     * 进度更新时调用。
     *
     * @param skillId  技能 ID
     * @param progress 当前进度（0.0 ~ 1.0）
     * @param message  进度描述文本
     */
    void onProgress(String skillId, double progress, String message);

    /**
     * 技能执行完成时调用。
     *
     * @param skillId 技能 ID
     * @param result  技能执行结果
     */
    void onComplete(String skillId, SkillResult result);

    /**
     * 技能执行出错时调用。
     *
     * @param skillId 技能 ID
     * @param error   捕获的异常或错误
     */
    void onError(String skillId, Throwable error);
}
```

---

### 第 27 块：SkillRegistry

#### 类介绍

**设计动机**：技能注册中心。管理所有 Skill 的注册、查找和依赖解析。

**包路径**：lyclaw-engine → lyjew.com.lyclaw.skill

**类型**：接口

```java
package lyjew.com.lyclaw.skill;

import java.util.List;

/**
 * 技能注册中心接口 —— 管理所有 Skill 的注册、查找和依赖解析。
 *
 * <p>技能之间可以存在依赖关系（如"竞品分析"技能依赖"搜索"技能）。
 * SkillRegistry 维护了技能的注册信息和依赖图（通过 {@link SkillGraph}），
 * 并提供拓扑排序的执行顺序。</p>
 *
 * <p><b>设计动机</b>：如果技能之间没有依赖管理系统，编写复合技能时
 * 需要在技能内部硬编码调用其他技能的代码，耦合严重。
 * 通过依赖声明 + 拓扑排序，引擎可以自动编排技能执行顺序。</p>
 *
 * @since 1.0
 * @author LyClaw Team
 * @see SkillGraph
 */
public interface SkillRegistry {

    /**
     * 注册一个技能。同名技能第二次注册抛异常。
     *
     * @param skill 技能实例，不可为 null
     * @throws IllegalArgumentException 如果同名技能已注册
     */
    void register(Skill skill);

    /**
     * 按 ID 查找技能。
     *
     * @param skillId 技能 ID
     * @return 匹配的技能，未找到返回 null
     */
    Skill get(String skillId);

    /**
     * 获取所有已注册的技能。
     *
     * @return 所有已注册的技能列表
     */
    List<Skill> getAll();

    /**
     * 获取指定技能的依赖 ID 列表。
     *
     * @param skillId 技能 ID
     * @return 依赖的技能 ID 列表
     */
    List<String> getDependencies(String skillId);

    /**
     * 对所有已注册技能进行拓扑排序，返回执行顺序。
     * 依赖在前、被依赖在后。
     *
     * @return 按执行顺序排列的技能 ID 列表
     * @throws IllegalStateException 如果检测到循环依赖
     */
    List<String> resolveExecutionOrder();
}
```

---

### 第 28 块：SkillGraph

#### 类介绍

**设计动机**：技能依赖关系图。维护技能之间的 DAG 依赖关系，提供拓扑排序和环检测。

**包路径**：lyclaw-engine → lyjew.com.lyclaw.skill

**类型**：接口

```java
package lyjew.com.lyclaw.skill;

import java.util.List;

/**
 * 技能依赖关系图接口 —— 维护技能之间的 DAG 依赖关系。
 *
 * <p>技能之间的依赖关系构成一个有向无环图（DAG）。
 * 例如：技能 A 依赖技能 B 和技能 C，技能 B 依赖技能 D。
 * SkillGraph 提供添加/移除依赖、查询依赖和依赖者、拓扑排序、环检测等功能。</p>
 *
 * <p><b>设计动机</b>：如果依赖关系管理分散在技能内部，难以统一检测循环依赖。
 * SkillGraph 将依赖关系集中管理，使用 DFS 三色标记法检测环，
 * 使用 DFS 后序遍历进行拓扑排序。</p>
 *
 * <p><b>拓扑排序算法</b>：DFS + 后序遍历 + 逆序输出。
 * 从任意一个节点开始 DFS，遍历完所有邻接节点后将该节点加入结果列表，
 * 最后反转列表得到拓扑排序结果。</p>
 *
 * @since 1.0
 * @author LyClaw Team
 * @see SkillRegistry
 */
public interface SkillGraph {

    /**
     * 添加依赖关系：from 依赖 to（from 的执行依赖于 to 先执行完成）。
     *
     * @param fromSkillId 依赖于其他技能的技能 ID
     * @param toSkillId   被依赖的技能 ID
     */
    void addDependency(String fromSkillId, String toSkillId);

    /**
     * 移除依赖关系。
     *
     * @param fromSkillId 依赖方技能 ID
     * @param toSkillId   被依赖方技能 ID
     */
    void removeDependency(String fromSkillId, String toSkillId);

    /**
     * 获取指定技能的直接依赖。
     *
     * @param skillId 技能 ID
     * @return 被依赖的技能 ID 列表（直接依赖，不含传递依赖）
     */
    List<String> getDependencies(String skillId);

    /**
     * 获取直接依赖当前技能的其他技能。
     *
     * @param skillId 技能 ID
     * @return 依赖当前技能的其他技能 ID 列表
     */
    List<String> getDependents(String skillId);

    /**
     * 拓扑排序 —— DFS + 后序遍历，返回按执行顺序排列的技能 ID 列表。
     * 依赖在前、被依赖在后。
     *
     * @return 按执行顺序排列的技能 ID 列表
     * @throws IllegalStateException 如果图中存在环
     */
    List<String> getExecutionOrder();

    /**
     * 检测是否有环。使用 DFS 三色标记法：
     * <ul>
     *   <li>白色：未访问</li>
     *   <li>灰色：正在访问（当前 DFS 路径中）</li>
     *   <li>黑色：访问完成</li>
     * </ul>
     * 如果访问到灰色节点，说明存在环。
     *
     * @return true 表示存在环
     */
    boolean hasCycle();
}
```

---

### 第 29 块：Event

#### 类介绍

**设计动机**：事件基类。所有事件的基类，包含事件 ID、时间戳、来源、类型。

**包路径**：lyclaw-engine → lyjew.com.lyclaw.event

**类型**：类

```java
package lyjew.com.lyclaw.event;

import java.time.Instant;
import java.util.UUID;

/**
 * 事件基类 —— 所有事件的基类。
 *
 * <p>EventBus 中发布的所有事件都继承此类。
 * 每个事件有唯一 ID、时间戳、来源和类型标识。</p>
 *
 * <p><b>设计动机</b>：如果事件没有统一基类，EventBus 的 subscribe 方法
 * 就无法做类型安全的事件过滤。通过泛型 {@link EventBus#subscribe(Class, java.util.function.Consumer)}，
 * 订阅者可以只接收自己关心的事件类型。</p>
 *
 * <p><b>已知的子类</b>：
 * <ul>
 *   <li>TokenConsumedEvent — Token 消耗事件</li>
 *   <li>ToolCalledEvent — 工具调用事件</li>
 *   <li>AgentStateChangedEvent — Agent 状态变更事件</li>
 * </ul>
 * </p>
 *
 * @since 1.0
 * @author LyClaw Team
 * @see EventBus
 */
public class Event {

    /** 事件唯一 ID。使用 UUID 确保全局唯一 */
    private final String eventId;

    /** 事件创建时间。使用 Instant 确保时间精度到纳秒 */
    private final Instant timestamp;

    /** 事件来源标识。通常使用发布该事件的类名，方便追踪 */
    private final String source;

    /** 事件类型标识。如 "TOKEN_CONSUMED"、"TOOL_CALLED"、"AGENT_STATE_CHANGED" */
    private final String eventType;

    /**
     * 构造一个事件实例。
     *
     * @param source    事件来源标识
     * @param eventType 事件类型标识
     */
    public Event(String source, String eventType) {
        this.eventId = UUID.randomUUID().toString();
        this.timestamp = Instant.now();
        this.source = source;
        this.eventType = eventType;
    }

    /** @return 事件唯一 ID */
    public String getEventId() { return eventId; }

    /** @return 事件创建时间 */
    public Instant getTimestamp() { return timestamp; }

    /** @return 事件来源标识 */
    public String getSource() { return source; }

    /** @return 事件类型标识 */
    public String getEventType() { return eventType; }
}
```

---

### 第 30 块：EventBus

#### 类介绍

**设计动机**：事件总线接口。解耦事件的发布者和订阅者。

**包路径**：lyclaw-engine → lyjew.com.lyclaw.event

**类型**：接口

```java
package lyjew.com.lyclaw.event;

import java.util.function.Consumer;

/**
 * 事件总线接口 —— 解耦事件的发布者和订阅者。
 *
 * <p>EventBus 使用发布-订阅模式：发布者不需要知道谁在监听事件，
 * 订阅者不需要知道谁发布了事件。这种松耦合使得新增事件监听逻辑时，
 * 不需要修改发布者的任何代码。</p>
 *
 * <p><b>设计动机</b>：在不使用 EventBus 的情况下，
 * 每次 Token 消耗后需要手动调用日志记录、指标采集等多个组件，
 * 代码耦合度高。通过 EventBus，发布者只管发布事件，
 * 各组件通过 subscribe 独立监听。</p>
 *
 * <p><b>已知实现</b>：
 * <ul>
 *   <li>InMemoryEventBus — 内存实现，适用于单节点部署</li>
 *   <li>NullEventBus — 空对象实现，禁用了事件功能</li>
 * </ul>
 * </p>
 *
 * @since 1.0
 * @author LyClaw Team
 * @see Event
 */
public interface EventBus {

    /**
     * 发布事件。所有匹配的订阅者会同步收到事件通知。
     * 如果某个订阅者抛出异常，不会影响其他订阅者的接收。
     *
     * @param event 要发布的事件，不可为 null
     */
    void publish(Event event);

    /**
     * 订阅指定类型的事件。
     *
     * @param <T>       事件类型
     * @param eventType 要订阅的事件 Class
     * @param handler   事件处理回调
     */
    <T extends Event> void subscribe(Class<T> eventType, Consumer<T> handler);

    /**
     * 取消订阅指定类型的事件。
     *
     * @param <T>       事件类型
     * @param eventType 要取消订阅的事件 Class
     * @param handler   之前注册的处理回调
     */
    <T extends Event> void unsubscribe(Class<T> eventType, Consumer<T> handler);

    /**
     * 清除所有订阅者。
     */
    void clear();
}
```

---

### 第 31 块：MemoryManager

#### 类介绍

**设计动机**：长期记忆管理器。读取、追加、重写、搜索长期记忆。

**包路径**：lyclaw-engine → lyjew.com.lyclaw.memory

**类型**：接口

**持久化说明**：实际文件读写委托给 lyjew.com.lyclaw.storage.MemoryStorage。实体来源：lyjew.com.lyclaw.model.Memory（单例 id=global）。

```java
package lyjew.com.lyclaw.memory;

import java.util.List;

/**
 * 长期记忆管理器接口 —— 管理 AI 助手的长期记忆。
 *
 * <p>长期记忆是 AI 助手在多次对话之间保持的知识。
 * 例如用户告诉 AI "我的名字是海坤"、"我住在北京"，
 * 这些信息被写入长期记忆后，在未来的对话中会自动注入上下文。</p>
 *
 * <p><b>持久化说明</b>：MemoryManager 本身不处理文件读写，
 * 实际读写委托给 {@code lyjew.com.lyclaw.storage.MemoryStorage}。
 * 实体来源：{@code lyjew.com.lyclaw.model.Memory}（单例 id="global"）。</p>
 *
 * <p><b>设计动机</b>：如果没有记忆管理器，每次对话都是独立的，
 * AI 不会记得用户之前说过的重要信息。MemoryManager 让 AI 具备了"记忆力"。
 * 通过可切换的 MemoryStrategy，还可以控制记忆注入上下文的方式。</p>
 *
 * @since 1.0
 * @author LyClaw Team
 * @see MemoryContent
 * @see MemoryStrategy
 */
public interface MemoryManager {

    /**
     * 读取长期记忆（单例 global）。
     *
     * @return 长期记忆内容包装对象
     */
    MemoryContent read();

    /**
     * 追加记忆内容。通常用于在对话结束后提取关键信息追加到记忆末尾。
     *
     * @param content 要追加的记忆内容（Markdown 格式）
     */
    void append(String content);

    /**
     * 重写整条记忆。用新的内容替换整条记忆。
     * 谨慎使用，会丢失原有记忆。
     *
     * @param content 新的记忆内容
     */
    void rewrite(String content);

    /**
     * 搜索记忆。根据查询条件匹配记忆内容中的关键字。
     *
     * @param query 搜索查询
     * @return 匹配的记忆内容列表
     */
    List<MemoryContent> search(String query);

    /**
     * 获取当前记忆策略。
     *
     * @return 当前使用的记忆策略
     */
    MemoryStrategy getStrategy();

    /**
     * 切换记忆策略。运行时动态改变记忆注入上下文的方式。
     *
     * @param strategy 新的记忆策略
     */
    void setStrategy(MemoryStrategy strategy);
}
```

---

### 第 32 块：MemoryStrategy

#### 类介绍

**设计动机**：记忆注入策略。控制记忆如何注入到对话上下文中。

**包路径**：lyclaw-engine → lyjew.com.lyclaw.memory

**类型**：接口

**与 FormatStrategy 的区别**：MemoryStorage 的 MarkdownFormatStrategy 决定文件读写格式；MemoryStrategy 决定记忆如何注入上下文。

```java
package lyjew.com.lyclaw.memory;

import lyjew.com.lyclaw.context.ChatContext;

/**
 * 记忆注入策略接口 —— 控制记忆如何注入到对话上下文中。
 *
 * <p>MemoryManager 负责记忆的存取，MemoryStrategy 负责决定"记忆如何呈现给模型"。
 * 不同的策略对记忆的格式化方式和注入条件有不同的处理。</p>
 *
 * <p><b>与 FormatStrategy 的区别</b>：
 * <ul>
 *   <li>MemoryStorage 的 MarkdownFormatStrategy 决定文件读写格式（.md / .json / .txt）</li>
 *   <li>MemoryStrategy 决定记忆如何注入上下文（标签包裹 / 摘要 / 嵌入向量相似度查询）</li>
 * </ul>
 * </p>
 *
 * @since 1.0
 * @author LyClaw Team
 * @see MemoryManager
 * @see ManualMemoryStrategy
 */
public interface MemoryStrategy {

    /**
     * 将记忆格式化为提示词片段。通常是包裹在 Memory 标签中。
     *
     * @param memory 记忆内容
     * @return 格式化后的字符串片段
     */
    String formatForContext(MemoryContent memory);

    /**
     * 判断是否应该在当前上下文中注入记忆。
     * 如果不应该注入，ContextBuilder 会跳过这条记忆。
     *
     * @param memory  记忆内容
     * @param context 当前对话上下文
     * @return true 表示注入上下文，false 表示跳过
     */
    boolean shouldIncludeInContext(MemoryContent memory, ChatContext context);

    /**
     * 获取策略优先级。当有多个策略时，优先级高的策略胜出。
     *
     * @return 优先级（值越大优先级越高）
     */
    int getPriority();
}

```

---

### 第 33 块：ChatContext

#### 类介绍

**设计动机**：对话上下文。贯穿整个管道的唯一数据载体。包含原始请求、会话信息、记忆、消息列表、工具列表、拦截器链、追踪上下文等所有 Pipeline 执行所需的状态。

**包路径**：lyclaw-engine → lyjew.com.lyclaw.context

**类型**：类

**核心作用**：ChatContext 是整个管道的唯一数据载体。所有 PipelineStage、Interceptor、Tool 都通过 ChatContext 读写数据。它替代了在方法参数中传递一堆独立对象的方式，将所有相关数据集中管理。

```java
package lyjew.com.lyclaw.context;

import lyjew.com.lyclaw.dto.ChatResult;
import lyjew.com.lyclaw.interceptor.impl.InterceptorChain;
import lyjew.com.lyclaw.memory.MemoryContent;
import lyjew.com.lyclaw.model.ChatRequest;
import lyjew.com.lyclaw.model.Message;
import lyjew.com.lyclaw.model.Session;
import lyjew.com.lyclaw.model.ToolDefinition;
import lyjew.com.lyclaw.provider.ModelProvider;
import lyjew.com.lyclaw.tracing.TraceContext;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 对话上下文 —— 贯穿整个管道的唯一数据载体。
 *
 * <p>在 Pipeline 执行过程中，所有阶段共享同一个 ChatContext 实例。
 * 包含原始请求、会话信息、记忆、消息列表、工具列表、模型提供商、
 * 拦截器链、链路追踪上下文等所有 Pipeline 执行所需的状态。</p>
 *
 * <p><b>设计动机</b>：如果不使用 ChatContext，PipelineStage 的方法签名会变成
 * {@code process(ChatRequest, Session, MemoryContent, List<ToolDefinition>,
 * InterceptorChain, Chain, ...)} —— 参数爆炸且难以扩展。
 * ChatContext 将所有相关数据集中管理，新增任何共享数据只需要在 ChatContext
 * 中加一个字段，不影响现有方法签名。</p>
 *
 * <p><b>使用场景</b>：
 * <ul>
 *   <li>Pipeline.execute(ChatContext) — 管道执行的入参</li>
 *   <li>PipelineStage.process(ChatContext, Chain) — 阶段处理参数</li>
 *   <li>Interceptor.preHandle(ChatContext) — 拦截器读写的共享上下文</li>
 *   <li>Tool.execute(ToolCall, ChatContext) — 工具执行时可读取会话和请求信息</li>
 * </ul>
 * </p>
 *
 * @since 1.0
 * @author LyClaw Team
 */
public class ChatContext {

    /** 原始对话请求，包含用户消息和配置参数 */
    private final ChatRequest request;

    /** 当前会话，包含消息历史和会话元信息。可通过 setter 更新 */
    private Session session;

    /** 长期记忆内容（从 MemoryManager 读取） */
    private final MemoryContent memory;

    /** 消息列表（从会话中提取的只读视图），用于 ContextBuilder 构建模型输入 */
    private final List<Message> messages;

    /** 当前可用的工具定义列表 */
    private final List<ToolDefinition> toolDefinitions;

    /** 拦截器链管理器，preHandle/postHandle 统一入口 */
    private final InterceptorChain interceptorChain;

    /**
     * 模型提供商 —— 提供模型适配器的获取入口。
     * TODO: ModelProvider 接口定义在 lyclaw-engine → lyjew.com.lyclaw.provider，
     * 当前为占位引用。需确保依赖注入时正确注入。
     */
    private final ModelProvider modelProvider;

    /** 对话处理结果（由 ResponseBuildStage 填充） */
    private ChatResult result;

    /** 全链路追踪上下文 */
    private final TraceContext tracing;

    /** 扩展属性映射 —— 任意阶段都可以存入自定义数据 */
    private final Map<String, Object> attributes = new HashMap<>();

    /**
     * 构造一个 ChatContext 实例。
     *
     * @param request          原始对话请求
     * @param session          当前会话
     * @param memory           长期记忆
     * @param toolDefinitions  工具定义列表
     * @param interceptorChain 拦截器链
     * @param modelProvider    模型提供商
     */
    public ChatContext(ChatRequest request, Session session,
                       MemoryContent memory, List<ToolDefinition> toolDefinitions,
                       InterceptorChain interceptorChain, ModelProvider modelProvider) {
        this.request = request;
        this.session = session;
        this.memory = memory;
        this.messages = new ArrayList<>(session.getMessages());
        this.toolDefinitions = toolDefinitions;
        this.interceptorChain = interceptorChain;
        this.modelProvider = modelProvider;
        this.tracing = new TraceContext();
    }

    /** @return 原始对话请求 */
    public ChatRequest getRequest() { return request; }

    /** @return 当前会话 */
    public Session getSession() { return session; }

    /** @param session 更新当前会话 */
    public void setSession(Session session) { this.session = session; }

    /** @return 长期记忆 */
    public MemoryContent getMemory() { return memory; }

    /** @return 消息列表（会话消息的快照） */
    public List<Message> getMessages() { return messages; }

    /** @return 工具定义列表 */
    public List<ToolDefinition> getToolDefinitions() { return toolDefinitions; }

    /** @return 拦截器链 */
    public InterceptorChain getInterceptorChain() { return interceptorChain; }

    /** @return 模型提供商 */
    public ModelProvider getModelProvider() { return modelProvider; }

    /** @return 对话处理结果 */
    public ChatResult getResult() { return result; }

    /** @param result 设置对话处理结果（由 ResponseBuildStage 调用） */
    public void setResult(ChatResult result) { this.result = result; }

    /** @return 全链路追踪上下文 */
    public TraceContext getTracing() { return tracing; }

    /**
     * 设置扩展属性。任何阶段都可以通过此方法存入自定义数据，
     * 其他阶段通过 {@link #getAttribute(String)} 读取。
     *
     * @param key   属性键
     * @param value 属性值
     */
    public void setAttribute(String key, Object value) {
        this.attributes.put(key, value);
    }

    /**
     * 获取扩展属性。
     *
     * @param key 属性键
     * @return 属性值，不存在返回 null
     */
    public Object getAttribute(String key) {
        return this.attributes.get(key);
    }
}
```

> **第二部分 核心接口 完成（共 25 个文件）**

---

## 第三部分：安全/过滤/事务接口

> **设计意图**：横切关注点，独立于核心对话流程。被 Interceptor 和 Engine 调用，不依赖 Tool/Skill/Memory 的具体实现。

---

### 第 34 块：SecurityManager

#### 类介绍

**设计动机**：安全管理器。负责前置审批、权限检查、会话撤销、安全策略管理。InterceptorStage 在调用前通过 SecurityManager 做权限校验。

**包路径**：lyclaw-engine → lyjew.com.lyclaw.security

**类型**：接口

```java
package lyjew.com.lyclaw.security;

import lyjew.com.lyclaw.context.ChatContext;

import java.util.List;

/**
 * 安全管理器接口 —— 负责前置审批、权限检查、会话撤销、安全策略管理。
 *
 * <p>InterceptorStage 在 ToolCallLoop 执行前调用 SecurityManager 做权限校验。
 * 根据业务需要，审批流程可以是同步（直接返回）或异步（触发审批流后轮询结果）。</p>
 *
 * <p><b>设计动机</b>：如果不通过 SecurityManager 统一管理安全和审批逻辑，
 * 每个 PipelineStage 和 Tool 都需要自行实现权限判断，导致安全逻辑分散在各处。
 * SecurityManager 将安全策略集中管理，通过 approve() 获得审批后才能继续执行。</p>
 *
 * <p><b>审批流程</b>：
 * <ol>
 *   <li>InterceptorStage.preHandle() 调用 securityManager.approve(context, action)</li>
 *   <li>SecurityManager 根据请求内容判断是否需要人工审批</li>
 *   <li>需要审批时返回 {@link ApprovalResult}（approved=false + 合理 reason）</li>
 *   <li>通过审批后返回 {@link ApprovalResult}（approved=true + sandboxLevel）</li>
 * </ol>
 * </p>
 *
 * @since 1.0
 * @author LyClaw Team
 * @see ApprovalResult
 * @see SandboxLevel
 */
public interface SecurityManager {

    /**
     * 前置审批。在敏感操作执行前调用，获取审批结果。
     * 如果返回 denied，InterceptorStage 将中断管道执行。
     *
     * <p>action 的常见取值："EXECUTE_TOOL"、"MODIFY_MEMORY"、"DELETE_SESSION" 等。
     * 具体动作列表由 SecurityManager 实现方自行定义。</p>
     *
     * @param context 当前对话上下文（包含会话信息和请求信息）
     * @param action  要执行的动作标识
     * @return 审批结果
     */
    ApprovalResult approve(ChatContext context, String action);

    /**
     * 撤销已批准的会话审批。比如用户在审批后改变主意，或者定时撤销。
     *
     * @param sessionId 要撤销的会话 ID
     */
    void revoke(String sessionId);

    /**
     * 检查用户是否有执行某操作的权限。
     * 与 approve() 的区别：approve() 是审批流程（可能有异步审批流），
     * checkPermission 是同步的权限判断（基于角色/策略的静态检查）。
     *
     * <p>userId 从 ChatContext.getSession().getUserId() 获取。
     * 如果没有 userId（匿名会话），默认返回 false。</p>
     *
     * @param userId 用户 ID
     * @param action 要执行的动作标识
     * @return true 表示有权限
     */
    boolean checkPermission(String userId, String action);

    /**
     * 获取当前生效的安全策略名称列表，用于日志记录和管理端展示。
     *
     * @return 策略名称列表，不可为 null
     */
    List<String> getEffectivePolicies();
}
```

---

### 第 35 块：ApprovalResult

#### 类介绍

**设计动机**：审批结果值对象。SecurityManager.approve() 的返回值。

**包路径**：lyclaw-engine → lyjew.com.lyclaw.security

**类型**：类（值对象）

```java
package lyjew.com.lyclaw.security;

import java.time.Instant;

/**
 * 审批结果值对象 —— SecurityManager.approve() 的返回值。
 *
 * <p>审批结果包含是否通过、拒绝原因、审批人信息、审批时间和执行沙箱级别。
 * 通过静态工厂方法 {@link #granted(SandboxLevel)} 和 {@link #denied(String)}
 * 快速构造常见结果。</p>
 *
 * <p><b>设计动机</b>：审批结果是跨模块传递的数据对象，用值对象确保不可变。
 * 静态工厂方法比构造器更语义化：ApprovalResult.granted(NONE) 一看就知道是通过审批。</p>
 *
 * @since 1.0
 * @author LyClaw Team
 * @see SecurityManager
 */
public class ApprovalResult {

    /** 是否通过审批 */
    private final boolean approved;

    /** 审批理由（通过时）或拒绝原因（拒绝时）。拒绝时不应为 null */
    private final String reason;

    /** 审批人标识。系统自动审批时为 "SYSTEM" */
    private final String approvedBy;

    /** 审批时间 */
    private final Instant approvedAt;

    /** 审批通过的沙箱级别。拒绝时取值为 null */
    private final SandboxLevel sandboxLevel;

    /**
     * 构造一个完整的审批结果。
     *
     * @param approved     是否通过
     * @param reason       理由或原因
     * @param approvedBy   审批人
     * @param approvedAt   审批时间
     * @param sandboxLevel 沙箱级别（拒绝时为 null）
     */
    public ApprovalResult(boolean approved, String reason, String approvedBy,
                          Instant approvedAt, SandboxLevel sandboxLevel) {
        this.approved = approved;
        this.reason = reason;
        this.approvedBy = approvedBy;
        this.approvedAt = approvedAt;
        this.sandboxLevel = sandboxLevel;
    }

    /**
     * 快速创建"审批通过"结果。
     *
     * @param level 审批通过的沙箱级别
     * @return 通过的审批结果
     */
    public static ApprovalResult granted(SandboxLevel level) {
        return new ApprovalResult(true, "Approved", "SYSTEM",
                Instant.now(), level);
    }

    /**
     * 快速创建"审批拒绝"结果。
     *
     * @param reason 拒绝原因
     * @return 拒绝的审批结果
     */
    public static ApprovalResult denied(String reason) {
        return new ApprovalResult(false, reason, "SYSTEM",
                Instant.now(), null);
    }

    /** @return 是否通过审批 */
    public boolean isApproved() { return approved; }

    /** @return 审批理由或拒绝原因 */
    public String getReason() { return reason; }

    /** @return 审批人 */
    public String getApprovedBy() { return approvedBy; }

    /** @return 审批时间 */
    public Instant getApprovedAt() { return approvedAt; }

    /** @return 审批通过的沙箱级别（拒绝时为 null） */
    public SandboxLevel getSandboxLevel() { return sandboxLevel; }
}
```

---

### 第 36 块：SandboxLevel

#### 类介绍

**设计动机**：安全沙箱级别枚举。从无限制到Docker容器到完全隔离，共 5 个级别。

**包路径**：lyclaw-engine → lyjew.com.lyclaw.security

**类型**：枚举

```java
package lyjew.com.lyclaw.security;

/**
 * 安全沙箱级别枚举 —— 从无限制到 Docker 容器到完全隔离，共 5 个级别。
 *
 * <p>沙箱级别决定了 Tool 在执行时的安全约束。SecurityManager 审批时返回
 * 一个沙箱级别，ToolCallLoop 根据该级别决定工具的执行环境：
 * <ul>
 *   <li>{@link #NONE}：无沙箱，直接执行（仅限白名单工具）</li>
 *   <li>{@link #READ_ONLY}：只读沙箱，可以读文件/查数据库，不能写</li>
 *   <li>{@link #RESTRICTED}：受限沙箱，只能操作临时目录，有内存/CPU 限制</li>
 *   <li>{@link #CONTAINER}：容器沙箱，Docker 容器中执行，独立的文件系统和网络命名空间</li>
 *   <li>{@link #ISOLATED}：完全隔离沙箱，子进程/虚拟机中执行，网络隔离</li>
 * </ul>
 * </p>
 *
 * @since 1.0
 * @author LyClaw Team
 * @see ApprovalResult
 */
public enum SandboxLevel {

    /** 无沙箱，直接执行。仅限白名单中的安全工具使用（如计算器、查时间） */
    NONE,

    /** 只读沙箱。可以读取文件、查询数据库，但不能执行写操作 */
    READ_ONLY,

    /** 受限沙箱。只能操作临时目录，有内存和 CPU 时间限制 */
    RESTRICTED,

    /** 容器沙箱。在 Docker 容器中执行，独立的文件系统和网络命名空间，宿主文件系统隔离 */
    CONTAINER,

    /** 完全隔离沙箱。在子进程或虚拟机中执行，网络隔离，资源严格限制 */
    ISOLATED
}
```

---

### 第 37 块：ContentFilter

#### 类介绍

**设计动机**：内容过滤器接口。对输入和输出内容进行安全过滤（敏感词、注入攻击、PII 脱敏等）。

**包路径**：lyclaw-engine → lyjew.com.lyclaw.filter

**类型**：接口

```java
package lyjew.com.lyclaw.filter;

import lyjew.com.lyclaw.context.ChatContext;

/**
 * 内容过滤器接口 —— 对输入和输出内容进行安全过滤。
 *
 * <p>过滤内容类型包括：
 * <ul>
 *   <li>敏感词过滤（政治敏感、暴力、色情等）</li>
 *   <li>SQL/脚本注入检测</li>
 *   <li>PII（个人隐私信息）脱敏</li>
 *   <li>自定义规则过滤</li>
 * </ul>
 * </p>
 *
 * <p><b>设计动机</b>：如果不通过统一接口管理内容过滤，每个使用场景都需要
 * 重复编写正则校验逻辑。ContentFilter + FilterResult 将过滤逻辑封装为策略，
 * 通过 SPI 或配置动态加载过滤器链。</p>
 *
 * <p><b>调用方</b>：
 * <ul>
 *   <li>SensitiveDataInterceptor — 在 preHandle 中过滤用户输入</li>
 *   <li>ResponseBuildStage — 在构建响应前过滤模型输出</li>
 * </ul>
 * </p>
 *
 * @since 1.0
 * @author LyClaw Team
 * @see FilterResult
 * @see SensitiveDataInterceptor
 */
public interface ContentFilter {

    /**
     * 过滤内容。对输入字符串做安全检查，返回过滤结果。
     * ChatContext 参数提供上下文信息（会话 ID、用户身份等），
     * 供过滤器基于场景做差异化判断。
     *
     * @param content 原始内容
     * @param context 当前对话上下文
     * @return 过滤结果
     */
    FilterResult filter(String content, ChatContext context);

    /**
     * 获取过滤器名称，用于运行时识别和管理。
     *
     * @return 过滤器名称，如 "sensitive-word-filter"、"sql-injection-filter"
     */
    String getFilterName();
}
```

---

### 第 38 块：FilterResult

#### 类介绍

**设计动机**：过滤结果值对象。包含是否通过、过滤后的内容、拒绝原因、匹配的规则列表。

**包路径**：lyclaw-engine → lyjew.com.lyclaw.filter

**类型**：类（值对象）

```java
package lyjew.com.lyclaw.filter;

import java.util.Collections;
import java.util.List;

/**
 * 过滤结果值对象 —— 包含是否通过、过滤后的内容、拒绝原因、匹配的规则列表。
 *
 * <p>使用静态工厂方法构造常见结果：
 * <ul>
 *   <li>{@link #pass(String)} — 内容通过过滤</li>
 *   <li>{@link #reject(String, String)} — 内容被拒绝</li>
 * </ul>
 * </p>
 *
 * <p><b>设计动机</b>：过滤结果需要在 filter 和 filter 链调用方之间传递，
 * 值对象的不可变性确保跨模块传递时不会被意外修改。</p>
 *
 * @since 1.0
 * @author LyClaw Team
 * @see ContentFilter
 */
public class FilterResult {

    /** 是否通过过滤 */
    private final boolean passed;

    /** 过滤后的内容。通过时为原始/脱敏后内容；拒绝时通常为原始内容 */
    private final String filteredContent;

    /** 拒绝或替换的原因。通过时为 null */
    private final String reason;

    /** 匹配的规则列表。通过时为空列表 */
    private final List<String> matchedRules;

    /**
     * 构造一个过滤结果。
     *
     * @param passed         是否通过
     * @param filteredContent 过滤后的内容
     * @param reason         原因（通过时为 null）
     * @param matchedRules   匹配的规则列表
     */
    public FilterResult(boolean passed, String filteredContent,
                        String reason, List<String> matchedRules) {
        this.passed = passed;
        this.filteredContent = filteredContent;
        this.reason = reason;
        this.matchedRules = matchedRules != null ? matchedRules : Collections.emptyList();
    }

    /**
     * 快速创建"内容通过过滤"结果。matchedRules 为空列表。
     *
     * @param content 原始或脱敏后的内容
     * @return 通过的结果
     */
    public static FilterResult pass(String content) {
        return new FilterResult(true, content, null, Collections.emptyList());
    }

    /**
     * 快速创建"内容被拒绝"结果。matchedRules 需至少包含一条。
     *
     * @param content 被拒绝的原始内容
     * @param reason  拒绝原因
     * @return 拒绝的结果
     */
    public static FilterResult reject(String content, String reason) {
        return new FilterResult(false, content, reason,
                List.of("blocked-by-" + reason));
    }

    /** @return 是否通过过滤 */
    public boolean isPassed() { return passed; }

    /** @return 过滤后的内容 */
    public String getFilteredContent() { return filteredContent; }

    /** @return 拒绝或替换原因（通过时为 null） */
    public String getReason() { return reason; }

    /** @return 匹配的规则列表 */
    public List<String> getMatchedRules() { return matchedRules; }
}
```

---

### 第 39 块：SessionTransaction

#### 类介绍

**设计动机**：对话事务抽象。确保会话数据的变更要么全部成功，要么全部回滚。

**包路径**：lyclaw-engine → lyjew.com.lyclaw.transaction

**类型**：接口

```java
package lyjew.com.lyclaw.transaction;

import lyjew.com.lyclaw.context.ChatContext;

import java.util.List;

/**
 * 对话事务抽象接口 —— 确保会话数据的变更要么全部成功，要么全部回滚。
 *
 * <p>对话过程中可能会发生多次会话状态变更（追加消息、更新记忆、修改会话元信息）。
 * 如果中间某一步失败，需要回滚到事务开始时的状态。
 * SessionTransaction 提供 begin/commit/rollback 的 ACID 语义。</p>
 *
 * <p><b>设计动机</b>：没有事务管理的情况下，工具调用链中的某个工具执行成功后
 * 将数据写入会话，但后续工具失败时，已写入的数据无法自动回滚，导致会话状态不一致。
 * SessionTransaction 确保整个请求的处理是一个原子操作。</p>
 *
 * <p><b>事务流程</b>：
 * <ol>
 *   <li>InterceptorStage 在开始前调用 begin()</li>
 *   <li>各 PipelineStage 调用 createSnapshot() 记录变更</li>
 *   <li>全部成功时调用 commit()</li>
 *   <li>任一异常时调用 rollback()</li>
 * </ol>
 * </p>
 *
 * @since 1.0
 * @author LyClaw Team
 * @see TransactionContext
 * @see SessionUpdate
 */
public interface SessionTransaction {

    /**
     * 开始一个对话事务。记录事务开始时的会话状态快照。
     *
     * @param sessionId 要开启事务的会话 ID
     * @param context   上下文描述（如请求摘要），便于日志追踪
     */
    void begin(String sessionId, String context);

    /**
     * 提交事务，将所有变更持久化。
     *
     * @param sessionId 会话 ID
     * @return true 表示提交成功
     */
    boolean commit(String sessionId);

    /**
     * 回滚事务，恢复到事务开始时的状态。
     *
     * @param sessionId 会话 ID
     * @return true 表示回滚成功
     */
    boolean rollback(String sessionId);

    /**
     * 获取事务当前状态。
     *
     * @param sessionId 会话 ID
     * @return 状态描述，如 "ACTIVE"、"COMMITTED"、"ROLLED_BACK"
     */
    String getStatus(String sessionId);

    /**
     * 创建当前会话的快照。记录在当前事务上下文中。
     * <b>改动点</b>：设计文档返回的是单个 SessionUpdate，改为返回 List，
     * 因为一次快照可能包含多条变更记录（消息追加 + 记忆更新同时发生）。
     *
     * @param sessionId 会话 ID
     * @param context   当前对话上下文
     * @return 事务期间的变更记录列表。空列表表示无变更
     */
    List<SessionUpdate> createSnapshot(String sessionId, ChatContext context);
}
```

---

### 第 40 块：TransactionContext

#### 类介绍

**设计动机**：事务上下文。记录事务 ID、关联会话、变更快照、当前状态和创建时间。

**包路径**：lyclaw-engine → lyjew.com.lyclaw.transaction

**类型**：类

```java
package lyjew.com.lyclaw.transaction;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 事务上下文 —— 记录事务 ID、关联会话、变更快照、当前状态和创建时间。
 *
 * <p>每个 begin() 调用创建一个 TransactionContext 实例，存储在 SessionTransaction
 * 的内部 Map 中，通过 transactionId 索引。commit/rollback 时通过 transactionId
 * 找到对应的 TransactionContext，读取其中的变更记录做持久化或回滚。</p>
 *
 * <p><b>设计动机</b>：事务上下文需要在 begin() 和 commit/rollback() 之间传递状态。
 * 如果不封装为独立对象，SessionTransaction 的实现类需要自己维护 Map 和状态，复用性差。
 * TransactionContext 将事务状态封装为值对象，便于序列化/反序列化和日志追踪。</p>
 *
 * @since 1.0
 * @author LyClaw Team
 * @see SessionTransaction
 * @see SessionUpdate
 */
public class TransactionContext {

    /** 关联的会话 ID */
    private final String sessionId;

    /** 事务开始时的上下文快照（消息数量、记忆内容摘要），用于日志和回滚验证 */
    private final String contextSnapshot;

    /** 事务期间的变更记录列表 */
    private final List<SessionUpdate> updates;

    /**
     * 事务状态。设计文档用 String，此处改为枚举三个常量字符串，
     * 保持与文档一致的 String 签名，但通过常量化避免拼写错误。
     */
    private String status;

    /** 事务创建时间 */
    private final Instant createdAt;

    /** 事务 ID（由 SessionTransaction.begin() 生成并返回给调用方） */
    private final String transactionId;

    /** 状态常量 */
    public static final String STATUS_ACTIVE = "ACTIVE";
    public static final String STATUS_COMMITTED = "COMMITTED";
    public static final String STATUS_ROLLED_BACK = "ROLLED_BACK";

    /**
     * 构造事务上下文。
     *
     * @param transactionId   事务 ID
     * @param sessionId       关联的会话 ID
     * @param contextSnapshot 开始时的上下文快照
     */
    public TransactionContext(String transactionId, String sessionId,
                              String contextSnapshot) {
        this.transactionId = transactionId;
        this.sessionId = sessionId;
        this.contextSnapshot = contextSnapshot;
        this.updates = new ArrayList<>();
        this.status = STATUS_ACTIVE;
        this.createdAt = Instant.now();
    }

    /**
     * 添加一条变更记录到事务上下文中。
     *
     * @param update 变更记录
     */
    public void addUpdate(SessionUpdate update) {
        if (STATUS_ACTIVE.equals(this.status)) {
            this.updates.add(update);
        }
    }

    /** @return 事务 ID */
    public String getTransactionId() { return transactionId; }

    /** @return 关联的会话 ID */
    public String getSessionId() { return sessionId; }

    /** @return 开始时的上下文快照 */
    public String getContextSnapshot() { return contextSnapshot; }

    /** @return 变更记录列表 */
    public List<SessionUpdate> getUpdates() { return updates; }

    /** @return 当前状态 */
    public String getStatus() { return status; }

    /**
     * 更新事务状态。
     *
     * @param status 新状态（建议使用 STATUS_* 常量）
     */
    public void setStatus(String status) { this.status = status; }

    /** @return 事务创建时间 */
    public Instant getCreatedAt() { return createdAt; }
}
```

---

### 第 41 块：SessionUpdate

#### 类介绍

**设计动机**：单次变更记录值对象。记录会话中某一次具体变更的内容（old → new）。

**包路径**：lyclaw-engine → lyjew.com.lyclaw.transaction

**类型**：类（值对象）

```java
package lyjew.com.lyclaw.transaction;

import java.time.Instant;

/**
 * 单次变更记录值对象 —— 记录会话中某一次具体变更的内容（old → new）。
 *
 * <p>每次对会话状态的变更（追加消息、更新记忆、修改配置）都生成一个 SessionUpdate，
 * 记录变更前后的值以及操作人信息。commit 时将所有变更持久化，
 * rollback 时根据 oldValue 恢复到变更前的状态。</p>
 *
 * @since 1.0
 * @author LyClaw Team
 * @see TransactionContext
 * @see SessionTransaction
 */
public class SessionUpdate {

    /** 关联的会话 ID */
    private final String sessionId;

    /** 更新类型。如 "MESSAGE_ADDED"、"MEMORY_UPDATED"、"CONFIG_CHANGED" */
    private final String updateType;

    /** 变更前的值（JSON 格式），用于回滚 */
    private final String oldValue;

    /** 变更后的值（JSON 格式） */
    private final String newValue;

    /** 操作人标识 */
    private final String operator;

    /** 操作时间 */
    private final Instant timestamp;

    /**
     * 构造一条变更记录。
     *
     * @param sessionId  关联的会话 ID
     * @param updateType 更新类型
     * @param oldValue   变更前的值（null 表示新增而不是修改）
     * @param newValue   变更后的值
     * @param operator   操作人
     * @param timestamp  操作时间
     */
    public SessionUpdate(String sessionId, String updateType,
                         String oldValue, String newValue,
                         String operator, Instant timestamp) {
        this.sessionId = sessionId;
        this.updateType = updateType;
        this.oldValue = oldValue;
        this.newValue = newValue;
        this.operator = operator;
        this.timestamp = timestamp;
    }

    /** @return 关联的会话 ID */
    public String getSessionId() { return sessionId; }

    /** @return 更新类型 */
    public String getUpdateType() { return updateType; }

    /** @return 变更前的值（新增时为 null） */
    public String getOldValue() { return oldValue; }

    /** @return 变更后的值 */
    public String getNewValue() { return newValue; }

    /** @return 操作人 */
    public String getOperator() { return operator; }

    /** @return 操作时间 */
    public Instant getTimestamp() { return timestamp; }
}
```

---

### 第 42 块：SessionUpdateStrategy

#### 类介绍

**设计动机**：事务变更合并策略。当多次变更对同一字段操作时，决定如何合并。

**包路径**：lyclaw-engine → lyjew.com.lyclaw.transaction

**类型**：接口

```java
package lyjew.com.lyclaw.transaction;

import java.util.List;

/**
 * 事务变更合并策略接口 —— 当多次变更对同一字段操作时，决定如何合并。
 *
 * <p>在同一个事务中，同一个字段可能被多次修改（如消息列表连续追加两条消息）。
 * 不同的合并策略有不同的合并结果：
 * <ul>
 *   <li>APPEND：追加模式。新 update 追加到列表末尾（如消息追加场景）</li>
 *   <li>OVERWRITE：覆盖模式。移除同字段的旧 update，保留最新的（如配置更新场景）</li>
 *   <li>DEDUPLICATE：去重模式。移除内容完全相同的重复 update（如幂等操作场景）</li>
 * </ul>
 * </p>
 *
 * <p><b>设计动机</b>：如果不通过策略控制合并行为，回滚时可能错误地恢复到中间状态
 * 而不是事务开始前的状态。不同的变更类型需要不同的合并方式。</p>
 *
 * @since 1.0
 * @author LyClaw Team
 */
public interface SessionUpdateStrategy {

    /**
     * 合并变更记录。根据策略决定如何将新变更插入到已有变更列表中。
     *
     * @param existing  已有的变更记录列表
     * @param newUpdate 新的变更记录
     * @return 合并后的变更记录列表
     */
    List<SessionUpdate> merge(List<SessionUpdate> existing, SessionUpdate newUpdate);

    /**
     * 获取策略名称。
     *
     * @return 策略名称，如 "APPEND"、"OVERWRITE"、"DEDUPLICATE"
     */
    String getStrategyName();
}
```

> **第三部分 安全/过滤/事务接口 完成（共 9 个文件）**


---

## 第四部分：错误/追踪/缓存/检索

> **设计意图**：错误策略和追踪工具是全局基础设施，被各个模块引用但不引用各模块。

---

### 第 43 块：ErrorPolicy

#### 类介绍

**设计动机**：错误处理策略。定义模型调用失败或工具执行失败时的处理策略（重试、跳过、中止、降级）。

**包路径**：lyclaw-engine → lyjew.com.lyclaw.error

**类型**：接口

```java
package lyjew.com.lyclaw.error;

import lyjew.com.lyclaw.context.ChatContext;
import lyjew.com.lyclaw.dto.ChatResult;
import lyjew.com.lyclaw.model.ToolCall;
import lyjew.com.lyclaw.tool.ToolErrorAction;

/**
 * 错误处理策略接口 —— 定义模型调用失败或工具执行失败时的处理策略。
 *
 * <p>ErrorPolicy 被 ToolCallLoopStage 回调，根据异常类型和上下文决定
 * 下一步动作：
 * <ul>
 *   <li>模型异常 → 返回 ToolErrorAction 决定是否重试模型调用</li>
 *   <li>工具异常 → 返回 ToolErrorAction 决定是否重试/跳过/中止</li>
 * </ul>
 * </p>
 *
 * <p><b>设计动机</b>：如果不通过 ErrorPolicy 集中管理错误处理策略，
 * ToolCallLoopStage 中会充满 if-else 异常判断逻辑，且不同场景
 * （对话/Agent/技能）的错误处理逻辑不同，需要策略可替换。</p>
 *
 * @since 1.0
 * @author LyClaw Team
 * @see ToolErrorAction
 */
public interface ErrorPolicy {

    /**
     * 模型调用异常时的处理策略。
     *
     * @param exception 模型调用异常
     * @param context   当前对话上下文
     * @param request   原始请求
     * @return 错误处理决策
     */
    ToolErrorAction onModelError(ModelException exception, ChatContext context,
                                 ChatRequest request);

    /**
     * 工具执行异常时的处理策略。
     *
     * @param toolCall   出错的工具调用
     * @param exception  工具执行异常
     * @param retryCount 已重试次数
     * @return 错误处理决策
     */
    ToolErrorAction onToolError(ToolCall toolCall, Exception exception,
                                 int retryCount);

    /**
     * 获取重试配置。
     *
     * @return 重试相关配置
     */
    RetryConfig getRetryConfig();

    /**
     * 获取熔断器当前状态。
     *
     * @return 状态描述，如 "CLOSED"、"HALF_OPEN"、"OPEN"
     */
    String getCircuitBreakerState();
}
```

---

### 第 44 块：RetryConfig

#### 类介绍

**设计动机**：重试配置值对象。ErrorPolicy 通过它定义最大重试次数、退避策略和超时。

**包路径**：lyclaw-engine → lyjew.com.lyclaw.error

**类型**：类（值对象）

```java
package lyjew.com.lyclaw.error;

/**
 * 重试配置值对象 —— ErrorPolicy 通过它定义最大重试次数、退避策略和超时。
 *
 * <p>ErrorPolicy.getRetryConfig() 的返回值。ToolCallLoop 根据 RetryConfig
 * 判断是否应该重试、重试间隔和总超时时间。</p>
 *
 * <p><b>退避策略说明</b>：
 * <ul>
 *   <li>FIXED：固定间隔，每次重试等待 fixedDelayMs</li>
 *   <li>EXPONENTIAL：指数退避，第 n 次重试等待 baseDelayMs * 2^(n-1)</li>
 *   <li>LINEAR：线性递增，第 n 次重试等待 baseDelayMs * n</li>
 * </ul>
 * </p>
 *
 * @since 1.0
 * @author LyClaw Team
 * @see ErrorPolicy
 */
public class RetryConfig {

    /** 退避策略 */
    public enum BackoffStrategy {
        FIXED,
        EXPONENTIAL,
        LINEAR
    }

    /** 最大重试次数 */
    private final int maxRetries;

    /** 基础延迟（ms），具体含义取决于退避策略 */
    private final long baseDelayMs;

    /** 固定延迟（ms），仅在 FIXED 策略下使用 */
    private final long fixedDelayMs;

    /** 退避策略 */
    private final BackoffStrategy strategy;

    /**
     * 构造重试配置。
     *
     * @param maxRetries    最大重试次数
     * @param baseDelayMs   基础延迟
     * @param fixedDelayMs  固定延迟（FIXED 策略使用）
     * @param strategy      退避策略
     */
    public RetryConfig(int maxRetries, long baseDelayMs,
                       long fixedDelayMs, BackoffStrategy strategy) {
        this.maxRetries = maxRetries;
        this.baseDelayMs = baseDelayMs;
        this.fixedDelayMs = fixedDelayMs;
        this.strategy = strategy;
    }

    /**
     * 使用默认 EXPONENTIAL 策略创建重试配置。
     *
     * @param maxRetries  最大重试次数
     * @param baseDelayMs 基础延迟（ms）
     * @return 重试配置
     */
    public static RetryConfig exponential(int maxRetries, long baseDelayMs) {
        return new RetryConfig(maxRetries, baseDelayMs, 0, BackoffStrategy.EXPONENTIAL);
    }

    /**
     * 使用 FIXED 策略创建重试配置。
     *
     * @param maxRetries   最大重试次数
     * @param fixedDelayMs 固定延迟（ms）
     * @return 重试配置
     */
    public static RetryConfig fixed(int maxRetries, long fixedDelayMs) {
        return new RetryConfig(maxRetries, 0, fixedDelayMs, BackoffStrategy.FIXED);
    }

    /** @return 最大重试次数 */
    public int getMaxRetries() { return maxRetries; }

    /** @return 基础延迟（ms） */
    public long getBaseDelayMs() { return baseDelayMs; }

    /** @return 固定延迟（ms） */
    public long getFixedDelayMs() { return fixedDelayMs; }

    /** @return 退避策略 */
    public BackoffStrategy getStrategy() { return strategy; }
}
```

---

### 第 45 块：ModelException

#### 类介绍

**设计动机**：模型调用异常。包装模型调用过程中的各种错误（超时、认证失败、限流等）。

**包路径**：lyclaw-core → lyjew.com.lyclaw.exception

**类型**：类（异常）

> **说明**：ModelException 已在 lyclaw-core 中完整定义，engine 层直接引用。
> 代码文档不重新列出，实际使用方式为 `import lyjew.com.lyclaw.exception.ModelException;`。

---

### 第 46 块：ToolExecuteException

#### 类介绍

**设计动机**：工具执行异常。包装工具执行过程中的错误，带上工具名便于定位问题。

**包路径**：lyclaw-engine → lyjew.com.lyclaw.error

**类型**：类（异常）

```java
package lyjew.com.lyclaw.error;

import lyjew.com.lyclaw.base.exception.LyClawException;

/**
 * 工具执行异常 —— 包装工具执行过程中的错误，带上工具名便于定位问题。
 *
 * <p>当 Tool.execute() 抛出异常时，ToolCallLoop 捕获后将异常包装为
 * ToolExecuteException，附带工具名称和原始异常 cause，
 * 然后回调 ErrorPolicy.onToolError() 做决策。</p>
 *
 * @since 1.0
 * @author LyClaw Team
 * @see ErrorPolicy
 */
public class ToolExecuteException extends LyClawException {

    /** 出错的工具名称 */
    private final String toolName;

    public ToolExecuteException(String toolName, String message, Throwable cause) {
        super("TOOL_EXEC_ERROR", 500, message, cause);
        this.toolName = toolName;
    }

    public static ToolExecuteException of(String toolName, String message) {
        return new ToolExecuteException(toolName, message, null);
    }

    public static ToolExecuteException of(String toolName, Throwable cause) {
        return new ToolExecuteException(toolName,
                "Tool '" + toolName + "' execution failed: " + cause.getMessage(), cause);
    }

    public String getToolName() { return toolName; }
}
```

---

### 第 47 块：TraceContext

#### 类介绍

**设计动机**：全链路追踪上下文。记录请求经过的每个阶段的耗时，支持 toJson 导出用于日志。

**包路径**：lyclaw-engine → lyjew.com.lyclaw.tracing

**类型**：类

```java
package lyjew.com.lyclaw.tracing;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 全链路追踪上下文 —— 记录请求经过的每个阶段的耗时，支持 toJson 导出用于日志。
 *
 * <p>TraceContext 贯穿 Pipeline 的整个执行过程。每个 PipelineStage
 * 在开始和结束时调用 beginStage() / endStage()，最终在 LoggingInterceptor
 * 或日志输出时调用 toJson() 输出完整的链路耗时信息。</p>
 *
 * @since 1.0
 * @author LyClaw Team
 */
public class TraceContext {

    /** 追踪 ID，全局唯一 */
    private final String traceId;

    /** 阶段耗时记录（阶段名 -> 耗时 ms），使用 LinkedHashMap 保证阶段顺序 */
    private final Map<String, Long> stageDurations = new LinkedHashMap<>();

    /** 阶段开始时间记录（阶段名 -> 开始时间戳 ms） */
    private final Map<String, Long> stageStartTimes = new LinkedHashMap<>();

    /** 请求开始时间 */
    private final long requestStartTime;

    /** 请求结束时间 */
    private long requestEndTime;

    /** 自动生成 traceId */
    public TraceContext() {
        this.traceId = UUID.randomUUID().toString().replace("-", "");
        this.requestStartTime = System.currentTimeMillis();
    }

    /** 指定 traceId（如从 HTTP 头传入） */
    public TraceContext(String traceId) {
        this.traceId = traceId;
        this.requestStartTime = System.currentTimeMillis();
    }

    public void beginStage(String stageName) {
        stageStartTimes.put(stageName, System.currentTimeMillis());
    }

    public void endStage(String stageName) {
        Long startTime = stageStartTimes.remove(stageName);
        if (startTime != null) {
            long duration = System.currentTimeMillis() - startTime;
            stageDurations.merge(stageName, duration, Long::sum);
        }
    }

    public long getStageDuration(String stageName) {
        return stageDurations.getOrDefault(stageName, -1L);
    }

    public long getTotalDuration() {
        long end = requestEndTime > 0 ? requestEndTime : System.currentTimeMillis();
        return end - requestStartTime;
    }

    public void markEnd() { this.requestEndTime = System.currentTimeMillis(); }

    public String getTraceId() { return traceId; }

    public Map<String, Long> getStageDurations() { return stageDurations; }

    public String toJson() {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"traceId\":\"").append(traceId)
          .append("\",\"stages\":{");
        boolean first = true;
        for (Map.Entry<String, Long> e : stageDurations.entrySet()) {
            if (!first) sb.append(",");
            sb.append("\"").append(e.getKey())
              .append("\":").append(e.getValue());
            first = false;
        }
        sb.append("},\"total\":").append(getTotalDuration()).append("}");
        return sb.toString();
    }
}
```

---

### 第 48 块：CacheService

#### 类介绍

**设计动机**：缓存服务接口。通用 key-value 缓存抽象，支持 TTL 和命中率统计。

**包路径**：lyclaw-engine → lyjew.com.lyclaw.cache

**类型**：接口

```java
package lyjew.com.lyclaw.cache;

import java.util.Optional;

/**
 * 缓存服务接口 —— 通用 key-value 缓存抽象，支持 TTL 和命中率统计。
 *
 * <p>缓存服务用于缓存不常变化的配置、模型会话快照和工具定义。
 * 通过接口隔离具体实现（Caffeine、Redis 或本地 Map）。</p>
 *
 * @since 1.0
 * @author LyClaw Team
 */
public interface CacheService {

    Optional<String> get(String key);

    void set(String key, String value, long ttlSeconds);

    void evict(String key);

    void clear();

    CacheStats getStats();
}
```

---

### 第 49 块：CacheStats

#### 类介绍

**设计动机**：缓存统计信息值对象。CacheService.getStats() 的返回值，包含命中/未命中次数和命中率。

**包路径**：lyclaw-engine → lyjew.com.lyclaw.cache

**类型**：类（值对象）

```java
package lyjew.com.lyclaw.cache;

/**
 * 缓存统计信息值对象 —— CacheService.getStats() 的返回值。
 *
 * <p>包含缓存命中次数、未命中次数和命中率。
 * 用于监控缓存效率和定位缓存问题。</p>
 *
 * @since 1.0
 * @author LyClaw Team
 * @see CacheService
 */
public class CacheStats {

    /** 缓存命中次数 */
    private final long hitCount;

    /** 缓存未命中次数 */
    private final long missCount;

    /**
     * 构造缓存统计信息。
     *
     * @param hitCount  命中次数
     * @param missCount 未命中次数
     */
    public CacheStats(long hitCount, long missCount) {
        this.hitCount = hitCount;
        this.missCount = missCount;
    }

    /** @return 缓存命中次数 */
    public long getHitCount() { return hitCount; }

    /** @return 缓存未命中次数 */
    public long getMissCount() { return missCount; }

    /**
     * 获取缓存命中率。
     *
     * @return 命中率（0.0 ~ 1.0），无请求时返回 0.0
     */
    public double getHitRate() {
        long total = hitCount + missCount;
        return total == 0 ? 0.0 : (double) hitCount / total;
    }

    /**
     * 快速创建一个空的统计（全为0）。
     *
     * @return 空统计
     */
    public static CacheStats empty() {
        return new CacheStats(0, 0);
    }
}
```

---

### 第 50 块：VectorStore

#### 类介绍

**设计动机**：向量检索存储接口。支持向量嵌入存储、相似度搜索和元数据过滤。

**包路径**：lyclaw-engine → lyjew.com.lyclaw.retrieval

**类型**：接口

```java
package lyjew.com.lyclaw.retrieval;

import java.util.List;
import java.util.Map;

/**
 * 向量检索存储接口 —— 支持向量嵌入存储、相似度搜索和元数据过滤。
 *
 * <p>VectorStore 为 MemoryManager 的语义检索提供底层存储支持。
 * 将记忆内容生成向量嵌入后存入，搜索时根据查询向量返回最相似的 topK 条记录。</p>
 *
 * @since 1.0
 * @author LyClaw Team
 */
public interface VectorStore {

    void store(String id, List<Float> vector, Map<String, Object> metadata);

    List<SearchResult> search(List<Float> queryVector, int topK);

    void delete(String id);

    String getCollectionName();
}
```

> **第四部分 错误/追踪/缓存/检索 完成（共 6 个文件）**

---

## 第五部分：Agent 协调

> **设计意图**：多 Agent 通信，依赖核心接口（EventBus/ContextBuilder 等），但不是核心路径必须的。DefaultEngine 选择性使用 Agent 功能。

---

### 第 51 块：SearchResult

#### 类介绍

**设计动机**：向量搜索结果值对象。VectorStore.search() 的返回值，包含匹配的记录ID、相似度和元数据。

**包路径**：lyclaw-engine → lyjew.com.lyclaw.retrieval

**类型**：类（值对象）

```java
package lyjew.com.lyclaw.retrieval;

import java.util.Map;

/**
 * 向量搜索结果值对象 —— VectorStore.search() 的返回值。
 *
 * <p>包含匹配的记录ID、相似度分数、匹配内容和关联元数据。</p>
 *
 * @since 1.0
 * @author LyClaw Team
 * @see VectorStore
 */
public class SearchResult {

    /** 匹配的记录ID */
    private final String id;

    /** 相似度分数（0.0 ~ 1.0，越高越相似） */
    private final double score;

    /** 匹配的原始内容 */
    private final String content;

    /** 关联的元数据 */
    private final Map<String, Object> metadata;

    public SearchResult(String id, double score, String content,
                        Map<String, Object> metadata) {
        this.id = id;
        this.score = score;
        this.content = content;
        this.metadata = metadata;
    }

    public String getId() { return id; }

    public double getScore() { return score; }

    public String getContent() { return content; }

    public Map<String, Object> getMetadata() { return metadata; }
}
```

---

### 第 52 块：AgentCoordinator

#### 类介绍

**设计动机**：Agent 生命周期管理者。负责 Agent 的创建、调度、取消、状态查询和事件广播。

**包路径**：lyclaw-engine → lyjew.com.lyclaw.agent

**类型**：接口

```java
package lyjew.com.lyclaw.agent;

import lyjew.com.lyclaw.context.ChatContext;
import lyjew.com.lyclaw.dto.AgentResult;
import lyjew.com.lyclaw.event.Event;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Agent 协调器接口 —— 负责 Agent 的创建、调度、取消、状态查询和事件广播。
 *
 * <p>AgentCoordinator 是引擎中多 Agent 协作的核心管理者。
 * 接收上层（Engine / TaskPlan）的 AgentTask，分派给合适的 Agent 执行，
 * 协调多个 Agent 之间的通信和状态同步。</p>
 *
 * @since 1.0
 * @author LyClaw Team
 * @see AgentChannel
 * @see AgentState
 */
public interface AgentCoordinator {

    CompletableFuture<AgentResult> dispatch(ChatContext context, AgentTask task);

    boolean cancel(String agentId);

    AgentState getState(String agentId);

    List<AgentChannel> getChannels(String agentId);

    void broadcast(Event event);
}
```

---

### 第 53 块：AgentChannel

#### 类介绍

**设计动机**：Agent 通信渠道。Agent 之间通过 Channel 发送消息，支持点对点和广播。

**包路径**：lyclaw-engine → lyjew.com.lyclaw.agent

**类型**：接口

```java
package lyjew.com.lyclaw.agent;

/**
 * Agent 通信渠道接口 —— Agent 之间通过 Channel 发送消息，支持点对点和广播。
 *
 * <p>每个 Agent 可以有多个 Channel（如内部内存队列、外部 MQ 等）。
 * AgentCoordinator 通过 Channel 在 Agent 之间传递 AgentMessage。</p>
 *
 * @since 1.0
 * @author LyClaw Team
 * @see AgentMessage
 */
public interface AgentChannel {

    void send(AgentMessage message);

    void receive(String agentId);
}
```

---

### 第 54 块：AgentMessage

#### 类介绍

**设计动机**：Agent 通信消息体。包含发送方、接收方、消息类型、内容和时间戳。

**包路径**：lyclaw-engine → lyjew.com.lyclaw.agent

**类型**：类

```java
package lyjew.com.lyclaw.agent;

import java.time.Instant;

/**
 * Agent 通信消息体 —— 包含发送方、接收方、消息类型、内容和时间戳。
 *
 * @since 1.0
 * @author LyClaw Team
 */
public class AgentMessage {

    private final String from;
    private final String to;
    private final String type;
    private final String content;
    private final Instant timestamp;

    public AgentMessage(String from, String to, String type,
                        String content, Instant timestamp) {
        this.from = from;
        this.to = to;
        this.type = type;
        this.content = content;
        this.timestamp = timestamp;
    }

    public String getFrom() { return from; }
    public String getTo() { return to; }
    public String getType() { return type; }
    public String getContent() { return content; }
    public Instant getTimestamp() { return timestamp; }
}
```

---

### 第 55 块：AgentState

#### 类介绍

**设计动机**：Agent 状态枚举。IDLE / RUNNING / WAITING / COMPLETED / FAILED / CANCELLED。

**包路径**：lyclaw-engine → lyjew.com.lyclaw.agent

**类型**：枚举

```java
package lyjew.com.lyclaw.agent;

/**
 * Agent 状态枚举 —— Agent 的完整生命周期状态。
 *
 * <p>状态流转：
 * <pre>
 * IDLE -> RUNNING -> WAITING -> RUNNING -> COMPLETED
 *                    \u21b3                    \u21b3 FAILED
 *                 CANCELLED
 * </pre>
 * </p>
 *
 * @since 1.0
 * @author LyClaw Team
 */
public enum AgentState {
    IDLE,
    RUNNING,
    WAITING,
    COMPLETED,
    FAILED,
    CANCELLED
}
```

---

### 第 56 块：AgentTask

#### 类介绍

**设计动机**：Agent 任务描述。描述 Agent 要执行的任务类型、目标、载荷和元数据。

**包路径**：lyclaw-engine → lyjew.com.lyclaw.agent

**类型**：类

```java
package lyjew.com.lyclaw.agent;

import java.util.Map;

/**
 * Agent 任务描述 —— 描述 Agent 要执行的任务类型、目标、载荷和元数据。
 *
 * @since 1.0
 * @author LyClaw Team
 * @see AgentCoordinator
 */
public class AgentTask {

    private final String taskId;
    private final String type;
    private final String target;
    private final String payload;
    private final Map<String, Object> metadata;

    public AgentTask(String taskId, String type, String target,
                     String payload, Map<String, Object> metadata) {
        this.taskId = taskId;
        this.type = type;
        this.target = target;
        this.payload = payload;
        this.metadata = metadata;
    }

    public String getTaskId() { return taskId; }
    public String getType() { return type; }
    public String getTarget() { return target; }
    public String getPayload() { return payload; }
    public Map<String, Object> getMetadata() { return metadata; }
}
```

---

### 第 57 块：TaskPlanner

#### 类介绍

**设计动机**：任务规划器。将复杂任务拆解为可执行的子任务节点（TaskNode），生成有向无环图（TaskPlan）。

**包路径**：lyclaw-engine → lyjew.com.lyclaw.task

**类型**：接口

```java
package lyjew.com.lyclaw.task;

import lyjew.com.lyclaw.context.ChatContext;
import lyjew.com.lyclaw.dto.AgentResult;

/**
 * 任务规划器接口 —— 将复杂任务拆解为可执行的子任务节点，
 * 生成有向无环图（TaskPlan）。
 *
 * @since 1.0
 * @author LyClaw Team
 * @see TaskPlan
 * @see TaskNode
 */
public interface TaskPlanner {

    TaskPlan plan(ChatContext context);

    TaskPlan optimize(AgentResult previousResult);
}
```

---

### 第 58 块：TaskPlan

#### 类介绍

**设计动机**：任务计划接口。包含所有任务节点（TaskNode），提供节点依赖关系和就绪状态判断。

**包路径**：lyclaw-engine → lyjew.com.lyclaw.task

**类型**：接口

```java
package lyjew.com.lyclaw.task;

import java.util.List;

/**
 * 任务计划接口 —— 包含所有任务节点（TaskNode），
 * 提供节点依赖关系和就绪状态判断。
 *
 * @since 1.0
 * @author LyClaw Team
 * @see TaskNode
 */
public interface TaskPlan {

    List<TaskNode> getNodes();

    List<String> getDependencies(String nodeId);

    long getEstimatedCompletionTime();

    boolean isReady();
}
```

---

### 第 59 块：TaskNode

#### 类介绍

**设计动机**：任务节点值对象。描述一个可执行的子任务。

**包路径**：lyclaw-engine → lyjew.com.lyclaw.task

**类型**：类（值对象）

```java
package lyjew.com.lyclaw.task;

import java.util.List;

/**
 * 任务节点值对象 —— 描述一个可执行的子任务。
 *
 * @since 1.0
 * @author LyClaw Team
 */
public class TaskNode {

    private final String nodeId;
    private final String type;
    private final String description;
    private final List<String> requiredTools;
    private final List<String> dependencies;
    private final long timeoutMs;

    public TaskNode(String nodeId, String type, String description,
                    List<String> requiredTools, List<String> dependencies,
                    long timeoutMs) {
        this.nodeId = nodeId;
        this.type = type;
        this.description = description;
        this.requiredTools = requiredTools;
        this.dependencies = dependencies;
        this.timeoutMs = timeoutMs;
    }

    public String getNodeId() { return nodeId; }
    public String getType() { return type; }
    public String getDescription() { return description; }
    public List<String> getRequiredTools() { return requiredTools; }
    public List<String> getDependencies() { return dependencies; }
    public long getTimeoutMs() { return timeoutMs; }
}
```

---

### 第 60 块：TaskResult

#### 类介绍

**设计动机**：任务执行结果值对象。

**包路径**：lyclaw-engine → lyjew.com.lyclaw.task

**类型**：类（值对象）

```java
package lyjew.com.lyclaw.task;

/**
 * 任务执行结果值对象 —— 包含执行状态、输出内容、错误信息、耗时和 Token 用量。
 *
 * @since 1.0
 * @author LyClaw Team
 */
public class TaskResult {

    private final String nodeId;
    private final boolean success;
    private final String output;
    private final String error;
    private final long elapsedMs;
    private final String tokenUsage;

    public TaskResult(String nodeId, boolean success, String output,
                      String error, long elapsedMs, String tokenUsage) {
        this.nodeId = nodeId;
        this.success = success;
        this.output = output;
        this.error = error;
        this.elapsedMs = elapsedMs;
        this.tokenUsage = tokenUsage;
    }

    public String getNodeId() { return nodeId; }
    public boolean isSuccess() { return success; }
    public String getOutput() { return output; }
    public String getError() { return error; }
    public long getElapsedMs() { return elapsedMs; }
    public String getTokenUsage() { return tokenUsage; }
}
```

---

### 第 61 块：TaskLedger

#### 类介绍

**设计动机**：任务账本接口。记录所有任务执行历史，支持按任务 ID 查询。

**包路径**：lyclaw-engine → lyjew.com.lyclaw.task

**类型**：接口

```java
package lyjew.com.lyclaw.task;

import java.util.List;
import java.util.Optional;

/**
 * 任务账本接口 —— 记录所有任务执行历史，支持按任务 ID 查询。
 *
 * @since 1.0
 * @author LyClaw Team
 */
public interface TaskLedger {

    void addRecord(TaskRecord record);

    List<TaskRecord> getRecords(String taskId);

    Optional<TaskRecord> getLatestRecord(String taskId);

    List<TaskRecord> getAllTasks();
}
```

---

### 第 62 块：TaskRecord

#### 类介绍

**设计动机**：任务记录值对象。记录单个任务的每次执行记录。

**包路径**：lyclaw-engine → lyjew.com.lyclaw.task

**类型**：类（值对象）

```java
package lyjew.com.lyclaw.task;

import java.time.Instant;

/**
 * 任务记录值对象 —— 记录单个任务的每次执行记录。
 *
 * @since 1.0
 * @author LyClaw Team
 */
public class TaskRecord {

    private final String taskId;
    private final String nodeId;
    private final String status;
    private final TaskResult result;
    private final String error;
    private final Instant startedAt;
    private final Instant completedAt;

    public TaskRecord(String taskId, String nodeId, String status,
                      TaskResult result, String error,
                      Instant startedAt, Instant completedAt) {
        this.taskId = taskId;
        this.nodeId = nodeId;
        this.status = status;
        this.result = result;
        this.error = error;
        this.startedAt = startedAt;
        this.completedAt = completedAt;
    }

    public String getTaskId() { return taskId; }
    public String getNodeId() { return nodeId; }
    public String getStatus() { return status; }
    public TaskResult getResult() { return result; }
    public String getError() { return error; }
    public Instant getStartedAt() { return startedAt; }
    public Instant getCompletedAt() { return completedAt; }
}
```

> **第五部分 Agent 协调 完成（共 11 个文件）**

---

## 第六部分：空对象模式实现

> **设计意图**：提供零行为的默认实现，让各组件可安全注入 null-safe 依赖。实现核心接口但不依赖任何业务实现。

---

### 第 63 块：NullEventBus

#### 类介绍

**设计动机**：EventBus 空对象实现。所有方法空操作，不产生任何副作用。

**包路径**：lyclaw-engine → lyjew.com.lyclaw.event.impl

**类型**：类

```java
package lyjew.com.lyclaw.event.impl;

import lyjew.com.lyclaw.event.Event;
import lyjew.com.lyclaw.event.EventBus;
import java.util.function.Consumer;

import org.springframework.stereotype.Component;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
/**
 * EventBus 空对象实现 —— 所有方法空操作，不产生任何副作用。
 *
 * <p>当应用不需要 EventBus 功能时，注入此实现避免 NPE。
 * publish/subscribe/unsubscribe/clear 全部空操作。</p>
 *
 * <p><b>Spring 注入</b>：@Component + @ConditionalOnMissingBean(EventBus.class)，
 * 当没有其他 EventBus 实现时自动使用此空对象。</p>
 *
 * @since 1.0
 * @author LyClaw Team
 */
@Component
@ConditionalOnMissingBean(EventBus.class)
public class NullEventBus implements EventBus {

    @Override
    public void publish(Event event) { /* 空操作 */ }

    @Override
    public <T extends Event> void subscribe(Class<T> eventType, Consumer<T> handler) {
        /* 空操作 */
    }

    @Override
    public <T extends Event> void unsubscribe(Class<T> eventType, Consumer<T> handler) {
        /* 空操作 */ 
    }

    @Override
    public void clear() { /* 空操作 */ }
}
```

---

### 第 64 块：NullMemoryManager

#### 类介绍

**设计动机**：MemoryManager 空对象实现。read 返回空 MemoryContent，append/rewrite/search 无操作。

**包路径**：lyclaw-engine → lyjew.com.lyclaw.memory.impl

**类型**：类

```java
package lyjew.com.lyclaw.memory.impl;

import lyjew.com.lyclaw.memory.MemoryContent;
import lyjew.com.lyclaw.memory.MemoryManager;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;

/**
 * MemoryManager 空对象实现 —— read 返回空 MemoryContent，
 * append/rewrite 空操作，search 返回空列表。
 *
 * <p>当应用不需要记忆功能时，注入此实现避免 NPE。</p>
 *
 * <p><b>Spring 注入</b>：@Component + @ConditionalOnMissingBean(MemoryManager.class)，
 * 当没有其他 MemoryManager 实现时自动使用此空对象。</p>
 *
 * @since 1.0
 * @author LyClaw Team
 */
@Component
@ConditionalOnMissingBean(MemoryManager.class)
public class NullMemoryManager implements MemoryManager {

    @Override
    public MemoryContent read() {
        return new MemoryContent("", "null", false, Collections.emptyList(), 0.0);
    }

    @Override
    public void append(String content) { /* 空操作 */ }

    @Override
    public void rewrite(String content) { /* 空操作 */ }

    @Override
    public List<MemoryContent> search(String query) {
        return Collections.emptyList();
    }

    @Override
    public MemoryStrategy getStrategy() {
        return null;
    }

    @Override
    public void setStrategy(MemoryStrategy strategy) {
        /* 空操作 */
    }
}
```

---

### 第 65 块：NullSecurityManager

#### 类介绍

**设计动机**：SecurityManager 空对象实现。approve 始终返回 granted(NONE)，checkPermission 始终返回 true。

**包路径**：lyclaw-engine → lyjew.com.lyclaw.security.impl

**类型**：类

```java
package lyjew.com.lyclaw.security.impl;

import lyjew.com.lyclaw.context.ChatContext;
import lyjew.com.lyclaw.security.ApprovalResult;
import lyjew.com.lyclaw.security.SandboxLevel;
import lyjew.com.lyclaw.security.SecurityManager;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;

/**
 * SecurityManager 空对象实现 —— approve 始终返回 granted(NONE)，
 * @author LyClaw Team
 */
@Component
@ConditionalOnMissingBean(SecurityManager.class)
public class NullSecurityManager implements SecurityManager {

    @Override
    public ApprovalResult approve(ChatContext context, String action) {
        return ApprovalResult.granted(SandboxLevel.NONE);
    }

    @Override
    public void revoke(String sessionId) { /* 空操作 */ }

    @Override
    public boolean checkPermission(String userId, String action) {
        return true;
    }

    @Override
    public List<String> getEffectivePolicies() {
        return Collections.emptyList();
    }
}
```

---

### 第 66 块：NullContentFilter

#### 类介绍

**设计动机**：ContentFilter 空对象实现。filter 始终返回 pass，getFilterName 返回固定名称。

**包路径**：lyclaw-engine → lyjew.com.lyclaw.filter.impl

**类型**：类

```java
package lyjew.com.lyclaw.filter.impl;

import lyjew.com.lyclaw.context.ChatContext;
import lyjew.com.lyclaw.filter.ContentFilter;
import lyjew.com.lyclaw.filter.FilterResult;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Component;

/**
 * ContentFilter 空对象实现 —— filter 始终返回 pass(content)，
 * getFilterName 返回 "NullContentFilter"。
 *
 * <p>当应用不需要内容过滤功能时，注入此实现避免 NPE。</p>
 *
 * <p><b>Spring 注入</b>：@Component + @ConditionalOnMissingBean(ContentFilter.class)，
 * 当没有其他 ContentFilter 实现时自动使用此空对象。</p>
 *
 * @since 1.0
 * @author LyClaw Team
 */
@Component
@ConditionalOnMissingBean(ContentFilter.class)
public class NullContentFilter implements ContentFilter {

    @Override
    public FilterResult filter(String content, ChatContext context) {
        return FilterResult.pass(content);
    }

    @Override
    public String getFilterName() {
        return "NullContentFilter";
    }
}
```

> **第六部分 空对象模式实现 完成（共 4 个文件）**


---

## 第八部分：ModelProvider 防腐层

> **设计意图**：engine 层和 adapter 层之间的防腐层接口 — engine 通过它获取适配器，不直接依赖 ModelAdapterFactory。

---

### 第 67 块：ModelProvider

#### 类介绍

**设计动机**：Engine↔Adapter 防腐层。engine 层通过 ModelProvider 获取 ModelAdapter，不直接依赖 lyclaw-adapter 模块。

**包路径**：lyclaw-engine → lyjew.com.lyclaw.provider

**类型**：接口

```java
package lyjew.com.lyclaw.provider;

import lyjew.com.lyclaw.adapter.ModelAdapter;

import java.util.Set;

/**
 * 模型适配器提供者 —— Engine↔Adapter 防腐层接口。
 *
 * <p>engine 层通过此接口获取模型适配器，而不直接依赖 lyclaw-adapter 模块的具体类。
 * 具体实现在 lyclaw-adapter 中由 Spring 注入。</p>
 *
 * <p><b>为什么要防腐层</b>：如果 engine 直接调用 ModelAdapterFactory，那么：
 * <ul>
 *   <li>engine 层在编译期就绑死了 adapter 模块</li>
 *   <li>未来替换适配器获取方式（如改为 gRPC，或从配置中心动态获取），engine 层必须改代码</li>
 *   <li>单元测试时难以 mock 适配器创建过程</li>
 * </ul>
 * 通过 ModelProvider 接口，engine 层只依赖一个简单的接口，获取方式的变化对 engine 完全透明。</p>
 *
 * <p><b>使用场景</b>：
 * <ul>
 *   <li>ToolCallLoopStage 调用模型时需要适配器</li>
 *   <li>DefaultEngine 初始化时通过 getConfiguredAdapter() 获取默认适配器</li>
 *   <li>ChatContext 中 TODO 占位的 ModelProvider 引用</li>
 * </ul>
 * </p>
 *
 * @since 1.0
 * @author LyClaw Team
 * @see lyjew.com.lyclaw.adapter.ModelAdapter
 */
public interface ModelProvider {

    /**
     * 按厂商名获取适配器。
     *
     * @param provider 厂商标识，如 "minimax"、"deepseek"
     * @return ModelAdapter 实例
     * @throws IllegalArgumentException 如果厂商名不存在
     */
    ModelAdapter getAdapter(String provider);

    /**
     * 获取默认厂商名。
     *
     * @return 默认厂商标识
     */
    String getDefaultProvider();

    /**
     * 获取已配置的默认适配器（等价于 getAdapter(getDefaultProvider())）。
     *
     * @return 默认 ModelAdapter 实例
     */
    ModelAdapter getConfiguredAdapter();

    /**
     * 列出所有可用厂商。
     *
     * @return 厂商名集合
     */
    Set<String> listProviders();

    /**
     * 刷新适配器列表。配置变更后调用，使新增的适配器生效。
     */
    void refresh();
}
```

> **第八部分 ModelProvider 防腐层 完成（共 1 个文件）**

---

## 第九部分：Pipeline 和 Engine 实现

> **设计意图**：引擎层的核心编排逻辑，组装前面定义的所有接口和策略。
> 依赖前置部分的全部接口和空对象，是最上层的实现骨架。

---

### 第 68 块：DefaultChain

#### 类介绍

**设计动机**：Chain 接口的默认实现。DefaultPipeline.execute() 内部实例化，控制 Stage 列表的遍历和调用。

**包路径**：lyclaw-engine → lyjew.com.lyclaw.pipeline.impl

**类型**：类

```java
package lyjew.com.lyclaw.pipeline.impl;

import lyjew.com.lyclaw.context.ChatContext;
import lyjew.com.lyclaw.pipeline.Chain;
import lyjew.com.lyclaw.pipeline.PipelineStage;

import java.util.List;

/**
 * Chain 默认实现 —— 控制 PipelineStage 列表的遍历和调用。
 *
 * <p>内部维护 stage 列表和当前索引。每次调用 proceed(context) 时：
 * <ol>
 *   <li>检查是否还有未执行的 Stage</li>
 *   <li>如果有，获取当前 Stage 并递增索引</li>
 *   <li>调用 Stage.process(context, this) 执行</li>
 *   <li>Stage 内部调用 Chain.next() 或 Chain.breakChain() 控制后续流程</li>
 * </ol>
 * </p>
 *
 * <p><b>设计动机</b>：DefaultPipeline.execute() 需要一种机制来遍历 Stage 列表
 * 并按顺序调用。如果没有 DefaultChain，DefaultPipeline 就需要自己维护索引
 * 和中断标记，职责耦合。DefaultChain 将这些逻辑封装起来，让 Pipeline 只关注编排。</p>
 *
 * @since 1.0
 * @author LyClaw Team
 * @see Chain
 * @see PipelineStage
 * @see DefaultPipeline
 */
public class DefaultChain implements Chain {

    /** 按执行顺序排列的阶段列表 */
    private final List<PipelineStage> stages;

    /** 当前执行到的索引（从 0 开始） */
    private int currentIndex;

    /** 是否已被中断 */
    private boolean broken = false;

    /**
     * 构造 DefaultChain 实例。
     *
     * @param stages 按执行顺序排列的阶段列表
     * @param startIndex 起始索引，通常为 0
     */
    public DefaultChain(List<PipelineStage> stages, int startIndex) {
        this.stages = stages;
        this.currentIndex = startIndex;
    }

    /**
     * 开始执行 —— 等同于从当前索引开始逐个调用 Stage.process()。
     *
     * <p>与 next() 不同，proceed() 是入口方法：从头开始执行所有 Stage。
     * 每个 Stage 内部调用 next() 或 breakChain() 决定是否继续。</p>
     *
     * @param context 对话上下文
     */
    public void proceed(ChatContext context) {
        while (currentIndex < stages.size() && !broken) {
            PipelineStage stage = stages.get(currentIndex);
            currentIndex++;
            stage.process(context, this);
        }
    }

    @Override
    public void next(ChatContext context) {
        if (broken) {
            throw new IllegalStateException("Chain has been broken");
        }
        // 由 proceed() 的 while 循环控制推进
    }

    @Override
    public void breakChain(ChatContext context) {
        this.broken = true;
    }

    @Override
    public int getCurrentStage() {
        return currentIndex - 1;
    }
}
```

---

### 第 69 块：DefaultPipeline

#### 类介绍

**设计动机**：Pipeline 接口的默认实现。PipelineBuilder.build() 返回此实例，内部持有阶段列表并依次执行。

**包路径**：lyclaw-engine → lyjew.com.lyclaw.pipeline.impl

**类型**：类

```java
package lyjew.com.lyclaw.pipeline.impl;

import lyjew.com.lyclaw.context.ChatContext;
import lyjew.com.lyclaw.pipeline.Pipeline;
import lyjew.com.lyclaw.pipeline.PipelineStage;

import java.util.List;

/**
 * Pipeline 默认实现 —— PipelineBuilder.build() 返回此实例。
 *
 * <p>内部持有按顺序排列的 PipelineStage 列表，execute() 时通过 DefaultChain
 * 依次调用每个阶段的 process() 方法。</p>
 *
 * <p><b>设计动机</b>：Pipeline 是接口，不能直接实例化。
 * PipelineBuilder.build() 需要一个具体实现来承载阶段列表和执行逻辑。
 * DefaultPipeline 就是这个具体实现，它对 builder 以外的模块透明。</p>
 *
 * @since 1.0
 * @author LyClaw Team
 * @see Pipeline
 * @see PipelineStage
 * @see PipelineBuilder
 * @see DefaultChain
 */
public class DefaultPipeline implements Pipeline {

    /** 按执行顺序排列的阶段列表 */
    private final List<PipelineStage> stages;

    /**
     * 包级私有构造器 —— 仅由 PipelineBuilder.build() 调用。
     *
     * @param stages 按执行顺序排列的阶段列表
     */
    DefaultPipeline(List<PipelineStage> stages) {
        this.stages = List.copyOf(stages);
    }

    @Override
    public void execute(ChatContext context) {
        new DefaultChain(stages, 0).proceed(context);
    }

    @Override
    public List<PipelineStage> getStages() {
        return stages;
    }
}
```

---

### 第 70 块：PipelineBuilder

#### 类介绍

**设计动机**：Pipeline 构建器（建造者模式）。通过链式调用添加/移除阶段，最终 build() 生成 Pipeline 实例。

**包路径**：lyclaw-engine → lyjew.com.lyclaw.pipeline.impl

**类型**：类

```java
package lyjew.com.lyclaw.pipeline.impl;

import lyjew.com.lyclaw.pipeline.Pipeline;
import lyjew.com.lyclaw.pipeline.PipelineStage;

import java.util.ArrayList;
import java.util.List;

/**
 * Pipeline 构建器 —— 通过链式调用添加/移除阶段，最终 build() 生成 Pipeline 实例。
 *
 * <p>使用建造者模式：</p>
 * <pre>{@code
 * Pipeline pipeline = new PipelineBuilder()
 *     .addStage(new ContextBuildStage(...))
 *     .addStage(new InterceptorStage(...))
 *     .addStage(new ToolCallLoopStage(...))
 *     .addStage(new MetricsStage(...))
 *     .addStage(new ResponseBuildStage(...))
 *     .build();
 * }</pre>
 *
 * <p><b>设计动机</b>：如果直接在 DefaultEngine 中硬编码阶段列表，
 * 要新增阶段就需要改 execute() 方法。通过 PipelineBuilder，DefaultEngine
import org.springframework.stereotype.Component;
 * 可以从配置或条件动态编排阶段，新增阶段只需 new + addStage() 两行代码。</p>
 *
 * @since 1.0
 * @author LyClaw Team
 * @see Pipeline
 * @see PipelineStage
 */
public class PipelineBuilder {

    /** 阶段列表，保持添加顺序 */
    private final List<PipelineStage> stages = new ArrayList<>();

    /**
     * 在末尾添加一个阶段。
     *
     * @param stage 要添加的阶段
     * @return 当前 Builder（链式调用）
     */
    public PipelineBuilder addStage(PipelineStage stage) {
        stages.add(stage);
        return this;
    }

    /**
     * 按名称移除一个阶段。
     *
     * @param stageName 阶段名称（与 getStageName() 返回值匹配）
     * @return 当前 Builder（链式调用）
     */
    public PipelineBuilder removeStage(String stageName) {
        stages.removeIf(s -> s.getStageName().equals(stageName));
        return this;
    }

    /**
     * 构建 Pipeline 实例。
     *
     * @return 组装好的 Pipeline
     */
    public Pipeline build() {
        return new DefaultPipeline(stages);
    }
}
```

---

### 第 71 块：ContextBuildStage

#### 类介绍

**设计动机**：Pipeline 第一阶段。调用 ContextBuilder 构建模型输入消息列表，将构建结果写入 ChatContext。

**包路径**：lyclaw-engine → lyjew.com.lyclaw.pipeline.impl

**类型**：类

```java
package lyjew.com.lyclaw.pipeline.impl;

import lyjew.com.lyclaw.context.ChatContext;
import lyjew.com.lyclaw.context.ContextBuilder;
import lyjew.com.lyclaw.memory.MemoryContent;
import lyjew.com.lyclaw.model.Message;
import lyjew.com.lyclaw.model.ToolDefinition;
import lyjew.com.lyclaw.pipeline.Chain;
import lyjew.com.lyclaw.pipeline.PipelineStage;

import java.util.List;

/**
 * Pipeline 第一阶段 —— 上下文构建阶段。
 *
 * <p>调用 ContextBuilder.buildContext() 构建模型输入的消息列表。
 * memory 和 toolDefinitions 在 ChatContext 构造时已经注入，
 * 本阶段只需要读取它们并调用 ContextBuilder 策略。</p>
 *
 * <p><b>设计动机</b>：ChatContext 的构造器要求 memory 和 toolDefinitions
 * 在创建时传入（它们是 final 字段），所以 ContextBuildStage 不需要自己
import org.springframework.stereotype.Component;
 * 去 MemoryManager/ToolRegistry 拿数据。它的职责就是选择 ContextBuilder
 * 策略并把已有数据组装成消息列表。</p>
 *
 * @since 1.0
 * @author LyClaw Team
 * @see ContextBuilder
 */
@Component
import org.springframework.stereotype.Component;
public class ContextBuildStage implements PipelineStage {

    /** 上下文构建策略 */
    private final ContextBuilder contextBuilder;

    public ContextBuildStage(ContextBuilder contextBuilder) {
        this.contextBuilder = contextBuilder;
    }

    @Override
    public void process(ChatContext context, Chain chain) {
        // 读取 ChatContext 中已注入的数据
        MemoryContent memory = context.getMemory();
        List<ToolDefinition> toolDefinitions = context.getToolDefinitions();

        // 调用 ContextBuilder 策略构建消息列表
        List<Message> builtMessages = contextBuilder.buildContext(
                context.getSession(), memory, toolDefinitions);

        // 将构建好的消息列表写回 ChatContext（替换会话原始消息）
        context.getMessages().clear();
        context.getMessages().addAll(builtMessages);

        chain.next(context);
    }

    @Override
    public int getOrder() {
        return 0; // 第一阶段
    }

    @Override
    public String getStageName() {
        return "ContextBuild";
    }
}
```

---

### 第 72 块：InterceptorStage

#### 类介绍

**设计动机**：Pipeline 第二阶段。按 @Order 顺序执行所有拦截器的 preHandle() 进行横切处理。

**包路径**：lyclaw-engine → lyjew.com.lyclaw.pipeline.impl

**类型**：类

```java
package lyjew.com.lyclaw.pipeline.impl;

import lyjew.com.lyclaw.context.ChatContext;
import lyjew.com.lyclaw.interceptor.impl.InterceptorChain;
import lyjew.com.lyclaw.pipeline.Chain;
import lyjew.com.lyclaw.pipeline.PipelineStage;
import org.springframework.stereotype.Component;

/**
 * Pipeline 第二阶段 —— 拦截器执行阶段。
 *
 * <p>按 @Order 顺序执行所有注册的拦截器的 preHandle() 方法。
 * 如果任何一个拦截器抛出异常，流程终止并交由 ErrorPolicy 处理。</p>
 *
 * <p><b>设计动机</b>：拦截器（限流、日志、脱敏）是横切关注点，不应该散落在各个阶段中。
 * 集中在一个阶段执行，既保证了执行顺序可控，又使得新增拦截器时只需加 @Component。
 * <ul>
 *   <li>RateLimitInterceptor — 检查请求频率</li>
 *   <li>LoggingInterceptor — 记录请求日志</li>
 *   <li>SensitiveDataInterceptor — 敏感数据脱敏</li>
 * </ul>
 * </p>
 *
 * @since 1.0
 * @author LyClaw Team
 * @see InterceptorChain
 */
@Component
public class InterceptorStage implements PipelineStage {

    /** 拦截器链管理器 */
    private final InterceptorChain interceptorChain;

    public InterceptorStage(InterceptorChain interceptorChain) {
        this.interceptorChain = interceptorChain;
    }

    @Override
    public void process(ChatContext context, Chain chain) {
        // 执行所有拦截器的 preHandle
        interceptorChain.preHandle(context);
        chain.next(context);
    }

    @Override
    public int getOrder() {
        return 1; // 第二阶段
    }

    @Override
    public String getStageName() {
        return "Interceptor";
    }
}
```

---

### 第 73 块：ToolCallLoopStage

#### 类介绍

**设计动机**：Pipeline 第三阶段——核心阶段。模型调用 + 工具执行循环。每轮调用 ModelAdapter.chat(ChatRequest) 返回 ModelResponse，从中提取工具调用请求并执行，执行结果注入消息列表后再送模型，直到模型返回纯文本回复或达到循环上限。

**包路径**：lyclaw-engine → lyjew.com.lyclaw.pipeline.impl

**类型**：类

```java
package lyjew.com.lyclaw.pipeline.impl;

import lyjew.com.lyclaw.adapter.ModelAdapter;
import lyjew.com.lyclaw.context.ChatContext;
import lyjew.com.lyclaw.error.ErrorPolicy;
import lyjew.com.lyclaw.error.ToolExecuteException;
import lyjew.com.lyclaw.model.ChatRequest;
import lyjew.com.lyclaw.model.Message;
import lyjew.com.lyclaw.model.ModelResponse;
import lyjew.com.lyclaw.model.ToolCall;
import lyjew.com.lyclaw.model.ToolDefinition;
import lyjew.com.lyclaw.pipeline.Chain;
import lyjew.com.lyclaw.pipeline.PipelineStage;
import lyjew.com.lyclaw.provider.ModelProvider;
import lyjew.com.lyclaw.tool.ToolCallPolicy;
import lyjew.com.lyclaw.tool.ToolErrorAction;
import lyjew.com.lyclaw.tool.ToolRegistry;
import lyjew.com.lyclaw.tool.ToolResult;

import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Pipeline 第三阶段 —— 核心阶段：模型调用 + 工具执行循环。
 *
 * <p>循环流程：
 * <pre>
 * loop {
 *     1. ModelAdapter.chat(ChatRequest) → ModelResponse
 *     2. ModelResponse.hasToolCalls() = false → 退出循环
 *     3. 将 ModelResponse 转为 assistant 消息（含工具调用）写入 request.messages
 *     4. 对每个工具调用 → ToolRegistry.execute() → tool 消息写入 request.messages
 *     5. ToolCallPolicy.shouldContinue() → 决定是否继续
 * }
 * </pre>
 * </p>
 *
 * <p><b>核心数据流转</b>：
 * <ul>
 *   <li>adapter.chat() 接收 ChatRequest，返回 ModelResponse（不修改入参）</li>
 *   <li>本阶段负责将 ModelResponse 的内容写回 ChatRequest.getMessages()</li>
 *   <li>下一轮循环时，messages 中包含了历史 + 新的工具结果</li>
 * </ul>
 * </p>
 *
 * <p><b>ToolCallRequest 到 ToolCall 的转换</b>：
 * ModelResponse.ToolCallRequest（id/name/arguments）从模型返回，
 * 需要转换为 Message（role=assistant, toolCalls=...）写入消息列表。</p>
 *
 * @since 1.0
 * @author LyClaw Team
 * @see ModelProvider
 * @see ToolCallPolicy
 * @see ErrorPolicy
 */
@Component
public class ToolCallLoopStage implements PipelineStage {

    private final ModelProvider modelProvider;
    private final ToolRegistry toolRegistry;
    private final ToolCallPolicy toolCallPolicy;
    private final ErrorPolicy errorPolicy;

    public ToolCallLoopStage(ModelProvider modelProvider,
                             ToolRegistry toolRegistry,
                             ToolCallPolicy toolCallPolicy,
                             ErrorPolicy errorPolicy) {
        this.modelProvider = modelProvider;
        this.toolRegistry = toolRegistry;
        this.toolCallPolicy = toolCallPolicy;
        this.errorPolicy = errorPolicy;
    }

    @Override
    public void process(ChatContext context, Chain chain) {
        ModelAdapter adapter = modelProvider.getConfiguredAdapter();
        // 获取请求中的消息列表（可变引用，可直接 add）
        List<Message> messages = context.getRequest().getMessages();

        int round = 0;
        while (round < toolCallPolicy.getMaxRounds()) {
            // 调用模型 —— adapter.chat() 接收 ChatRequest，返回 ModelResponse
            ModelResponse response = adapter.chat(context.getRequest());

            // 将模型回复写入消息列表
            List<ToolCall> toolCalls = convertToolCallRequests(response);
            messages.add(Message.builder()
                    .role("assistant")
                    .content(response.getContent() != null ? response.getContent() : "")
                    .toolCalls(toolCalls)
                    .build());

            // 无工具调用 → 退出循环
            if (!response.hasToolCalls()) {
                break;
            }

            // 执行每个工具调用，将结果注入消息列表
            boolean shouldAbort = false;
            for (ModelResponse.ToolCallRequest req : response.getToolCalls()) {
                try {
                    // 将 ToolCallRequest 转为 ToolCall 执行
                    // 注：ToolRegistry.execute() 接收 lyclaw-common 的 ToolCall
                    ToolCall toolCall = ToolCall.builder()
                            
                            .name(req.getName())
                            .arguments(req.getArguments())
                            .build();

                    ToolResult result = toolRegistry.execute(toolCall, context);

                    // 将工具执行结果以 tool 角色消息写入消息列表
                    messages.add(Message.builder()
                            .role("tool")
                            .content(result.isSuccess() ? result.getResult() : result.getError())
                            .build());
                } catch (Exception e) {
                    ToolErrorAction action = errorPolicy.onToolError(
                            null, ToolExecuteException.of(req.getName(), e), round);
                    if (action == ToolErrorAction.ABORT) {
                        messages.add(Message.builder()
                                .role("tool")
                                .content("Error: " + e.getMessage())
                                .build());
                        context.setAttribute("error", e.getMessage());
                        shouldAbort = true;
                        break;
                    }
                    if (action == ToolErrorAction.RETRY) {
                        // 跳过当前工具调用，继续循环
                        break;
                    }
                    // SKIP：将错误写入消息，让模型处理
                    messages.add(Message.builder()
                            .role("tool")
                            .content("Error: " + e.getMessage())
                            .build());
                }
            }

            if (shouldAbort) {
                break;
            }

            round++;

            if (!toolCallPolicy.shouldContinue(context, round)) {
                break;
            }
        }

        chain.next(context);
    }

    /**
     * 将 ModelResponse 中的 ToolCallRequest 列表转换为 lyclaw-common 的 ToolCall 列表。
     *
     * @param response 模型响应
     * @return ToolCall 列表，无工具调用时返回空列表
     */
    private List<ToolCall> convertToolCallRequests(ModelResponse response) {
        if (!response.hasToolCalls()) {
            return null;
        }
        List<ToolCall> result = new ArrayList<>();
        for (ModelResponse.ToolCallRequest req : response.getToolCalls()) {
            result.add(ToolCall.builder()
                    
                    .name(req.getName())
                    .arguments(req.getArguments())
                    .build());
        }
        return result;
    }

    @Override
    public int getOrder() {
        return 2; // 第三阶段
    }

    @Override
    public String getStageName() {
        return "ToolCallLoop";
    }
}
```

---

### 第 74 块：MetricsStage

#### 类介绍

**设计动机**：Pipeline 第四阶段。从 TraceContext 采集指标（各阶段耗时、总耗时），发布采集事件。

**包路径**：lyclaw-engine → lyjew.com.lyclaw.pipeline.impl

**类型**：类

```java
package lyjew.com.lyclaw.pipeline.impl;

import lyjew.com.lyclaw.context.ChatContext;
import lyjew.com.lyclaw.event.Event;
import lyjew.com.lyclaw.event.EventBus;
import lyjew.com.lyclaw.pipeline.Chain;
import lyjew.com.lyclaw.pipeline.PipelineStage;

import org.springframework.stereotype.Component;

/**
 * Pipeline 第四阶段 —— 指标采集阶段。
 *
 * <p>从 TraceContext 采集各阶段耗时和总耗时，
 * 发布指标采集事件供监控模块消费。</p>
 *
 * <p><b>设计动机</b>：将指标采集独立为一个阶段。新增指标时只需修改 MetricsStage，
 * 不影响其他阶段。不需要指标时直接从 Pipeline 移除即可。</p>
 *
 * @since 1.0
 * @author LyClaw Team
 */
@Component
public class MetricsStage implements PipelineStage {

    private final EventBus eventBus;

    public MetricsStage(EventBus eventBus) {
        this.eventBus = eventBus;
    }

    @Override
    public void process(ChatContext context, Chain chain) {
        // 标记追踪结束
        context.getTracing().markEnd();

        // 发布指标采集事件
        eventBus.publish(new Event("MetricsStage", "METRICS_COLLECTED"));

        chain.next(context);
    }

    @Override
    public int getOrder() {
        return 3; // 第四阶段
    }

    @Override
    public String getStageName() {
        return "Metrics";
    }
}
```

---

### 第 75 块：ResponseBuildStage

#### 类介绍

**设计动机**：Pipeline 第五阶段（最终阶段）。提取最后一条 assistant 消息的文本内容作为 AI 回复，构建 ChatResult，执行所有拦截器的 postHandle()。

**包路径**：lyclaw-engine → lyjew.com.lyclaw.pipeline.impl

**类型**：类

```java
package lyjew.com.lyclaw.pipeline.impl;

import lyjew.com.lyclaw.context.ChatContext;
import lyjew.com.lyclaw.dto.ChatResult;
import lyjew.com.lyclaw.interceptor.impl.InterceptorChain;
import lyjew.com.lyclaw.pipeline.Chain;
import lyjew.com.lyclaw.pipeline.PipelineStage;
import lyjew.com.lyclaw.tool.ToolResult;
import lyjew.com.lyclaw.tracing.TraceContext;

import java.util.Collections;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Pipeline 第五阶段（最终阶段）—— 响应构建阶段。
 *
 * <p>负责：
 * <ol>
 *   <li>从 ChatContext 提取 AI 回复内容（最后一条 assistant 消息的 content）</li>
 *   <li>构建 ChatResult</li>
 *   <li>执行所有拦截器的 postHandle()</li>
 * </ol>
 * </p>
 *
 * @since 1.0
 * @author LyClaw Team
 * @see ChatResult
 * @see InterceptorChain
 */
@Component
public class ResponseBuildStage implements PipelineStage {

    private final InterceptorChain interceptorChain;

    public ResponseBuildStage(InterceptorChain interceptorChain) {
        this.interceptorChain = interceptorChain;
    }

    @Override
    public void process(ChatContext context, Chain chain) {
        // 提取最后一条 assistant 消息的文本内容
        String responseText = extractLastAssistantMessage(context);
        context.getTracing().markEnd();

        // 构建 ChatResult — 5 参数构造器
        ChatResult result = new ChatResult(
                responseText,
                "stop",
                "prompt=0 completion=0 total=0",
                Collections.emptyList(),
                context.getTracing().getTotalDuration()
        );

        context.setResult(result);

        // 执行所有拦截器的 postHandle
        interceptorChain.postHandle(context, result);

        chain.next(context);
    }

    /**
     * 从消息列表中提取最后一条 assistant 角色的文本内容。
     */
    private String extractLastAssistantMessage(ChatContext context) {
        for (int i = context.getRequest().getMessages().size() - 1; i >= 0; i--) {
            Message msg = context.getRequest().getMessages().get(i);
            if ("assistant".equals(msg.getRole())) {
                return msg.getContent();
            }
        }
        return "";
    }

    @Override
    public int getOrder() {
        return 4; // 第五阶段
    }

    @Override
    public String getStageName() {
        return "ResponseBuild";
    }
}
```

---

### 第 76 块：DefaultEngine

#### 类介绍

**设计动机**：默认引擎实现。使用 Pipeline 模式编排对话流程，是 engine 层最上层的入口。通过依赖注入获取所有需要的组件。

**包路径**：lyclaw-engine → lyjew.com.lyclaw.engine.impl

**类型**：类

```java
package lyjew.com.lyclaw.engine.impl;

import lyjew.com.lyclaw.context.ChatContext;
import lyjew.com.lyclaw.context.ContextBuilder;
import lyjew.com.lyclaw.dto.ChatResult;
import lyjew.com.lyclaw.engine.Engine;
import lyjew.com.lyclaw.engine.EngineMetadata;
import lyjew.com.lyclaw.error.ErrorPolicy;
import lyjew.com.lyclaw.event.EventBus;
import lyjew.com.lyclaw.interceptor.impl.InterceptorChain;
import lyjew.com.lyclaw.memory.MemoryContent;
import lyjew.com.lyclaw.memory.MemoryManager;
import lyjew.com.lyclaw.model.ChatRequest;
import lyjew.com.lyclaw.model.Message;
import lyjew.com.lyclaw.model.Session;
import lyjew.com.lyclaw.model.ToolDefinition;
import lyjew.com.lyclaw.pipeline.Pipeline;
import lyjew.com.lyclaw.pipeline.impl.ContextBuildStage;
import lyjew.com.lyclaw.pipeline.impl.InterceptorStage;
import lyjew.com.lyclaw.pipeline.impl.MetricsStage;
import lyjew.com.lyclaw.pipeline.impl.PipelineBuilder;
import lyjew.com.lyclaw.pipeline.impl.ResponseBuildStage;
import lyjew.com.lyclaw.pipeline.impl.ToolCallLoopStage;
import lyjew.com.lyclaw.provider.ModelProvider;
import lyjew.com.lyclaw.storage.SessionStorage;
import lyjew.com.lyclaw.tool.ToolCallPolicy;
import lyjew.com.lyclaw.tool.ToolRegistry;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * 默认引擎实现 —— engine 层的核心编排入口。
 *
 * <p>DefaultEngine 使用 Pipeline 模式组织对话流程：
 * <ol>
 *   <li>ContextBuildStage — 加载记忆、构建上下文（消息列表初始化）</li>
 *   <li>InterceptorStage — 拦截器预处理</li>
 *   <li>ToolCallLoopStage — 模型调用 + 工具执行循环</li>
 *   <li>MetricsStage — 指标采集</li>
 *   <li>ResponseBuildStage — 构建响应</li>
 * </ol>
 * </p>
 *
 * <p><b>Spring 注入</b>：@Component，核心组件和 PipelineStage 全部通过构造器注入。</p>
 *
 * @since 1.0
 * @author LyClaw Team
 * @see Engine
 * @see Pipeline
 */
@Component
public class DefaultEngine implements Engine {

    private final ContextBuilder contextBuilder;
    private final InterceptorChain interceptorChain;
    private final ModelProvider modelProvider;
    private final ToolRegistry toolRegistry;
    private final ToolCallPolicy toolCallPolicy;
    private final ErrorPolicy errorPolicy;
    private final EventBus eventBus;
    private final MemoryManager memoryManager;
    private final PipelineBuilder pipelineBuilder;
    private final SessionStorage sessionStorage;  // ← 新增：会话持久化

    public DefaultEngine(ContextBuilder contextBuilder,
                         InterceptorChain interceptorChain,
                         ModelProvider modelProvider,
                         ToolRegistry toolRegistry,
                         ToolCallPolicy toolCallPolicy,
                         ErrorPolicy errorPolicy,
                         EventBus eventBus,
                         MemoryManager memoryManager,
                         PipelineBuilder pipelineBuilder,
                         SessionStorage sessionStorage) {   // ← 新增参数
        this.contextBuilder = contextBuilder;
        this.interceptorChain = interceptorChain;
        this.modelProvider = modelProvider;
        this.toolRegistry = toolRegistry;
        this.toolCallPolicy = toolCallPolicy;
        this.errorPolicy = errorPolicy;
        this.eventBus = eventBus;
        this.memoryManager = memoryManager;
        this.pipelineBuilder = pipelineBuilder;
        this.sessionStorage = sessionStorage;               // ← 新增赋值
    }

    @Override
    public String getName() {
        return "default";
    }

    @Override
    public boolean supports(ChatRequest request) {
        return true;
    }

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
        // 模型永远不会看到之前说过什么，多轮对话必然"失忆"。
        Session session = sessionStorage.get(request.getSessionId()).orElse(null);

        if (session == null) {
            // 3a. 新会话：用 request.getMessages() 初始化
            // request.getMessages() 至少包含 1 条 user 消息（由调用方保证）
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

        // 5. 使用注入的 PipelineBuilder 构建 Pipeline（已经在 EngineAutoConfiguration 中装配好了5个阶段）
        Pipeline pipeline = pipelineBuilder.build();

        // 6. 执行 Pipeline
        pipeline.execute(context);

        // 7. 获取结果
        ChatResult result = context.getResult();

        // ═══════════════════════════════════════════════════════════
        // 8. 将模型回复写入 Session 并持久化（多轮对话基础）
        // ═══════════════════════════════════════════════════════════
        // 目的：
        //   a) 把 AI 回复以 assistant 角色追加到 session.messages
        //   b) sessionStorage.save() 写入 JSON 文件
        //
        // 持久化后的 session.messages = [user, assistant, user, assistant, ...]
        // 下次 execute() 加载同一个 sessionId 时，能读到完整的历史。
        //
        // 如果漏掉 save()，Session JSON 文件里永远只有 user 消息，
        // 第 3 次调用时加载到的仍然是 [user, user]，
        // 中间的 assistant 回复全部丢失，模型"失忆"。
        if (result != null) {
            memoryManager.append(result.getContent());

            // 8a. 构造 assistant 消息（模型回复），追加到 session.messages
            Message assistantMsg = Message.builder()
                    .role("assistant")
                    .content(result.getContent())
                    .build();
            session.getMessages().add(assistantMsg);

            // 8b. 持久化到 JSON 文件
            sessionStorage.save(session);
        }

        return Flux.just(result != null ? result.getContent() : "");
    }

    @Override
    public EngineMetadata getMetadata() {
        return new EngineMetadata(
                getName(),
                "1.0",
                "Default AI Engine",
                List.of("chat"),
                Set.of("chat")
        );
    }
}
```

---

### 第 77 块：EngineSelector

#### 类介绍

**设计动机**：引擎选择器。遍历所有已注册的 Engine，调用 supports() 返回第一个匹配的。新增引擎只需 @Component 自动注册。

**包路径**：lyclaw-engine → lyjew.com.lyclaw.engine

**类型**：类

```java
package lyjew.com.lyclaw.engine;

import jakarta.annotation.PostConstruct;
import lyjew.com.lyclaw.model.ChatRequest;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

/**
 * 引擎选择器 —— 遍历所有注册的 Engine，调用 supports() 返回第一个匹配的引擎。
 *
 * <p><b>自动注册机制</b>：通过 @PostConstruct 从 ApplicationContext 中
 * 发现所有 Engine 类型的 Bean 并自动注册。新增引擎只需写一个 @Component 类
 * 实现 Engine 接口，不需要手动调用 register()。</p>
 *
 * <p>引擎匹配机制：
 * <ol>
 *   <li>按注册顺序遍历内部引擎列表</li>
 *   <li>对每个引擎调用 supports(ChatRequest)</li>
 *   <li>返回第一个返回 true 的引擎</li>
 *   <li>没有匹配时返回 null（由调用方决定降级策略）</li>
 * </ol>
 * </p>
 *
 * @since 1.0
 * @author LyClaw Team
 * @see Engine
 * @see DefaultEngine
 */
@Component
public class EngineSelector {

    /** 已注册的引擎列表 */
    private final List<Engine> engines = new ArrayList<>();

    @Autowired
    private ApplicationContext applicationContext;

    /**
     * 初始化时自动扫描并注册所有 Engine Bean。
     */
    @PostConstruct
    public void init() {
        Map<String, Engine> engineBeans = applicationContext.getBeansOfType(Engine.class);
        for (Engine engine : engineBeans.values()) {
            register(engine);
        }
    }

    public Engine select(ChatRequest request) {
        for (Engine engine : engines) {
            if (engine.supports(request)) {
                return engine;
            }
        }
        return null;
    }

    public void register(Engine engine) {
        engines.add(engine);
    }

    public List<Engine> getEngines() {
        return new ArrayList<>(engines);
    }
}
```

> **第九部分 Pipeline 和 Engine 实现 完成（共 5 个文件）**
---

# 第十部分：Tool / Skill / Memory 具体实现

> **设计意图**：具体业务实现依赖接口和基础设施。放在最后让读者了解接口长什么样后再看怎么实现。

## 实现文件清单

| 序号 | 文件 | 包 | 类型 | 说明 |
|------|------|-----|------|------|
| 78 | DefaultToolRegistry.java | tool.impl | 类 | 默认工具注册表 |
| 79 | DefaultToolCallPolicy.java | tool.impl | 类 | 默认工具调用策略（10轮上限） |
| 80 | ToolCallLoop.java | tool.impl | 类 | 工具调用循环（模板方法） |
| 81 | WebSearchTool.java | tool.impl | 类 | 网络搜索工具 |
| 82 | CalculatorTool.java | tool.impl | 类 | 数学计算工具 |
| 83 | CurrentTimeTool.java | tool.impl | 类 | 当前时间工具 |
| 84 | McpToolAdapter.java | tool.impl | 类 | MCP 协议适配器 |
| 85 | DefaultSkillRegistry.java | skill.impl | 类 | 默认技能注册表 |
| 86 | SkillGraphImpl.java | skill.impl | 类 | 技能依赖图实现 |
| 87 | ToolToSkillAdapter.java | skill.impl.adapters | 类 | Tool→Skill 适配器 |
| 88 | FileMemoryManager.java | memory.impl | 类 | 基于文件的记忆管理器 |
| 89 | ManualMemoryStrategy.java | memory.impl | 类 | 手动记忆策略（始终注入） |
| 90 | InMemoryEventBus.java | event.impl | 类 | 内存事件总线 |
| 91 | TokenConsumedEvent.java | event.impl | 类 | Token 消耗事件 |
| 92 | ToolCalledEvent.java | event.impl | 类 | 工具调用事件 |
| 93 | AgentStateChangedEvent.java | event.impl | 类 | Agent 状态变更事件 |
| 94 | StarAgentChannel.java | agent.impl | 类 | 星型拓扑 Agent 通信频道 |
| 95 | DefaultErrorPolicy.java | error.impl | 类 | 默认错误处理策略 |
| 96 | DefaultSecurityManager.java | security.impl | 类 | 默认安全管理器 |
| 97 | DefaultTaskPlanner.java | task.impl | 类 | 默认任务规划器 |
| 98 | DefaultTaskLedger.java | task.impl | 类 | 默认任务账本 |
| 99 | DefaultSessionTransaction.java | transaction.impl | 类 | 默认事务管理器 |

---

### 第 78 块：DefaultToolRegistry

#### 类介绍

**设计动机**：默认工具注册表实现。使用 ConcurrentHashMap 存储工具名称到 Tool 实例的映射。支持运行时动态注册和移除工具，线程安全。所有内置工具在构造时自动注册（通过 Spring 注入 List<Tool>）。

**包路径**：lyclaw-engine → lyjew.com.lyclaw.tool.impl

**类型**：类

```java
package lyjew.com.lyclaw.tool.impl;

import lyjew.com.lyclaw.context.ChatContext;
import lyjew.com.lyclaw.model.ToolCall;
import lyjew.com.lyclaw.model.ToolDefinition;
import lyjew.com.lyclaw.tool.Tool;
import lyjew.com.lyclaw.tool.ToolRegistry;
import lyjew.com.lyclaw.tool.ToolResult;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * 默认工具注册表实现 —— 使用 ConcurrentHashMap 存储，线程安全。
 *
 * <p><b>设计动机</b>：ToolRegistry 的职责是管理 Tool 的注册与查找。
 * 如果不使用统一的注册表，每个 PipelineStage 都需要自行维护工具列表，
 * 新增工具需要改多处代码。通过 DefaultToolRegistry 集中管理，
 * 调用方只需从注册表按名称获取或查询所有工具定义。</p>
 *
 * <p><b>ConcurrentHashMap 选择原因</b>：
 * <ul>
 *   <li>工具注册可能在启动阶段（主线程）和执行阶段（多请求并发）同时发生</li>
 *   <li>ConcurrentHashMap 的读操作无锁（get/containsKey），写操作分段锁</li>
 *   <li>与 CopyOnWriteArrayList 相比，随机查找 O(1) vs O(n)</li>
 * </ul>
 * </p>
 *
 * <p><b>Spring 注入</b>：@Component。所有实现了 Tool 接口且标记了 @Component 的
 * 工具会被 Spring 自动发现，通过 {@code @Autowired List<Tool>} 注入到构造器。
 * 构造器中逐个 register()，实现"新增工具只需写一个类加 @Component"的扩展目标。</p>
 *
 * @since 1.0
 * @author LyClaw Team
 * @see ToolRegistry
 * @see Tool
 */
@Component
public class DefaultToolRegistry implements ToolRegistry {

    /**
     * 工具存储映射 —— key 是工具名称（全局唯一），value 是 Tool 实例。
     *
     * <p>ConcurrentHashMap 保证并发安全。所有写操作（register/remove）通过
     * put/get 完成，读操作无锁。</p>
     */
    private final ConcurrentHashMap<String, Tool> tools = new ConcurrentHashMap<>();

    /**
     * 构造时注入所有已注册的 Tool 实例。
     *
     * <p>Spring 会自动搜集所有 @Component 的 Tool 实现类，
     * 通过此构造器注入。每种 Tool 的注册时机在构造器中完成。</p>
     *
     * @param toolList Spring 自动注入的所有 Tool 实现
     */
    public DefaultToolRegistry(List<Tool> toolList) {
        // 逐个注册所有注入的工具，确保名称唯一
        for (Tool tool : toolList) {
            register(tool);
        }
    }

    /**
     * 注册一个工具。如果同名工具已存在则覆盖。
     *
     * @param tool 工具实例，不可为 null
     */
    @Override
    public void register(Tool tool) {
        // put 返回旧值，如果旧值非 null 说明是覆盖操作
        Tool old = tools.put(tool.getName(), tool);
        if (old != null) {
            // 同名工具被覆盖 —— 记录日志
        }
    }

    /**
     * 按工具名称查找。
     *
     * @param name 工具名称
     * @return Tool 实例，不存在返回 null
     */
    @Override
    public Tool get(String name) {
        return tools.get(name);
    }

    /**
     * 获取所有已注册工具的工具定义列表。
     *
     * <p>此方法在 DefaultEngine.execute() 中被调用，
     * 返回的工具定义会被注入到 ChatRequest.tools 中发送给模型。</p>
     *
     * @return 工具定义列表（不可修改的快照）
     */
    @Override
    public List<ToolDefinition> getAllDefinitions() {
        // 收集所有工具的 ToolDefinition，包装为不可变列表
        return tools.values().stream()
                .map(Tool::getDefinition)
                .collect(Collections.toUnmodifiableList());
    }

    /**
     * 执行工具调用。
     *
     * <p>根据 ToolCall 中的工具名称查找已注册的工具并执行。</p>
     *
     * @param toolCall 模型返回的工具调用请求
     * @return 工具执行结果
     * @throws IllegalArgumentException 如果找不到指定名称的工具
     */
    @Override
    public ToolResult execute(ToolCall toolCall, ChatContext context) {
        // 1. 按名称查找工具
        Tool tool = tools.get(toolCall.getName());
        if (tool == null) {
            // 工具不存在 —— 抛出异常由 ErrorPolicy 处理
            throw new IllegalArgumentException(
                    "Tool not found: " + toolCall.getName());
        }
        // 2. 执行工具并返回结果
        return tool.execute(toolCall, context);
    }
}
```

---

### 第 79 块：DefaultToolCallPolicy

#### 类介绍

**设计动机**：默认工具调用策略。最大调用轮次 10 轮，超过则终止。默认错误策略为 ABORT（出现异常立即停止循环）。

**包路径**：lyclaw-engine → lyjew.com.lyclaw.tool.impl

**类型**：类

```java
package lyjew.com.lyclaw.tool.impl;

import lyjew.com.lyclaw.context.ChatContext;
import lyjew.com.lyclaw.model.ToolCall;
import lyjew.com.lyclaw.tool.ToolCallPolicy;
import lyjew.com.lyclaw.tool.ToolErrorAction;

import org.springframework.stereotype.Component;

/**
 * 默认工具调用策略 —— 最大 10 轮调用上限，异常时立即终止。
 *
 * <p><b>设计动机</b>：ToolCallPolicy 控制模型与工具的交互行为。
 * 如果没有轮次上限，模型可能陷入无限工具调用循环（如一直调用搜索工具）。
 * 设置上限既是保护机制也是降级策略。</p>
 *
 * <p><b>使用场景</b>：
 * <ul>
 *   <li>ToolCallLoopStage 在每次循环前调用 getMaxRounds() 和 shouldContinue()</li>
 *   <li>ErrorPolicy.onToolError() 决策后如果需要重试，回调 shouldRetryOnError()</li>
 * </ul>
 * </p>
 *
 * @since 1.0
 * @author LyClaw Team
 * @see ToolCallPolicy
 */
@Component
public class DefaultToolCallPolicy implements ToolCallPolicy {

    /** 最大工具调用轮次 */
    private static final int MAX_ROUNDS = 10;

    /** 最大重试次数 */
    private static final int MAX_RETRIES = 3;

    /**
     * 获取最大工具调用轮次。达到此上限后 ToolCallLoop 停止循环。
     *
     * @return 最大轮次数
     */
    @Override
    public int getMaxRounds() {
        return MAX_ROUNDS;
    }

    /**
     * 判断是否应该继续工具调用循环。
     *
     * @param context      当前对话上下文
     * @param currentRound 当前已执行的轮次（从 1 开始计数）
     * @return true 表示继续循环
     */
    @Override
    public boolean shouldContinue(ChatContext context, int currentRound) {
        // 当前轮次小于最大轮次时继续
        return currentRound < MAX_ROUNDS;
    }

    /**
     * 处理工具执行错误。默认返回 ABORT，立即终止循环。
     *
     * @param toolCall 出错的工具调用
     * @param error    异常信息
     * @param context  当前对话上下文
     * @return 返回 ABORT
     */
    @Override
    public ToolErrorAction handleToolError(ToolCall toolCall,
                                           Exception error,
                                           ChatContext context) {
        // 默认：工具执行出错后立即终止循环
        return ToolErrorAction.ABORT;
    }

    /**
     * 判断是否应该重试。默认允许最多重试 3 次。
     *
     * @param toolCall   出错的工具调用
     * @param error      异常信息
     * @param retryCount 已重试次数
     * @return retryCount < 3 时返回 true
     */
    @Override
    public boolean shouldRetryOnError(ToolCall toolCall,
                                      Exception error,
                                      int retryCount) {
        // 默认：最多重试 3 次
        return retryCount < MAX_RETRIES;
    }
}
```

---

### 第 80 块：ToolCallLoop

#### 类介绍

**设计动机**：工具调用循环模板方法。将"模型调用→工具执行→再次调用模型"的循环逻辑封装为模板方法，子类可以通过钩子方法自定义行为（如日志、指标采集等）。

**包路径**：lyclaw-engine → lyjew.com.lyclaw.tool.impl

**类型**：类

```java
package lyjew.com.lyclaw.tool.impl;

import lyjew.com.lyclaw.adapter.ModelAdapter;
import lyjew.com.lyclaw.context.ChatContext;
import lyjew.com.lyclaw.dto.ChatResult;
import lyjew.com.lyclaw.model.ChatRequest;
import lyjew.com.lyclaw.model.Message;
import lyjew.com.lyclaw.model.ModelResponse;
import lyjew.com.lyclaw.model.ToolCall;
import lyjew.com.lyclaw.provider.ModelProvider;
import lyjew.com.lyclaw.tool.ToolCallPolicy;
import lyjew.com.lyclaw.tool.ToolErrorAction;
import lyjew.com.lyclaw.tool.ToolRegistry;
import lyjew.com.lyclaw.tool.ToolResult;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 工具调用循环 —— 模板方法模式封装"模型调用→工具执行→再次调用模型"的循环逻辑。
 *
 * <p><b>核心流程</b>：
 * <pre>
 * beforeLoop(context)                        ← 钩子 1
 * loop {
 *     ModelResponse resp = adapter.chat(req) ← 调用模型
 *     if (!handleModelResponse(resp)) break   ← 钩子 2：无工具调用则退出
 *     对每个 toolCall：
 *         ToolResult = toolRegistry.execute(toolCall, context)
 *         将结果注入 req.messages
 *     if (!policy.shouldContinue(context, round)) break
 * }
 * afterLoop(context, result)                  ← 钩子 3
 * </pre>
 * </p>
 *
 * @since 1.0
 * @author LyClaw Team
 * @see ModelProvider
 * @see ToolRegistry
 * @see ToolCallPolicy
 */
public class ToolCallLoop {

    /** 模型提供商 —— 获取已配置的 ModelAdapter */
    protected final ModelProvider modelProvider;

    /** 工具注册表 —— 按名称查找并执行工具 */
    protected final ToolRegistry toolRegistry;

    /** 工具调用策略 —— 控制轮次上限和重试逻辑 */
    protected final ToolCallPolicy toolCallPolicy;

    /**
     * 构造工具调用循环。
     *
     * @param modelProvider   模型提供商
     * @param toolRegistry    工具注册表
     * @param toolCallPolicy  工具调用策略
     */
    public ToolCallLoop(ModelProvider modelProvider,
                        ToolRegistry toolRegistry,
                        ToolCallPolicy toolCallPolicy) {
        this.modelProvider = modelProvider;
        this.toolRegistry = toolRegistry;
        this.toolCallPolicy = toolCallPolicy;
    }

    /**
     * 模板方法 —— 执行工具调用循环。
     *
     * @param context 对话上下文
     * @return 最终的 ChatResult（由子类 afterLoop 构建）
     */
    public ChatResult execute(ChatContext context) {
        // 钩子 1：循环开始前的准备工作（子类可扩展）
        beforeLoop(context);

        // 获取模型适配器
        ModelAdapter adapter = modelProvider.getConfiguredAdapter();
        // 获取可变消息列表
        List<Message> messages = context.getRequest().getMessages();

        int round = 0;
        while (round < toolCallPolicy.getMaxRounds()) {
            // 调用模型
            ModelResponse response = adapter.chat(context.getRequest());

            // 钩子 2：处理模型响应，返回 false 表示退出循环
            if (!handleModelResponse(response)) {
                // 无工具调用 —— 将模型回复写入消息列表后退出
                messages.add(Message.builder()
                        .role("assistant")
                        .content(response.getContent() != null
                                ? response.getContent() : "")
                        .build());
                break;
            }

            // 有工具调用 —— 将模型回复（含 toolCalls）写入消息列表
            messages.add(Message.builder()
                    .role("assistant")
                    .content(response.getContent() != null
                            ? response.getContent() : "")
                    .toolCalls(convertToolCalls(response))
                    .build());

            // 执行每个工具调用
            boolean shouldAbort = false;
            for (ModelResponse.ToolCallRequest req : response.getToolCalls()) {
                try {
                    // 将 ToolCallRequest 转为 ToolCall 然后执行
                    ToolCall toolCall = new ToolCall(req.getId(), req.getName(), req.getArguments());
                    ToolResult result = toolRegistry.execute(toolCall, context);

                    // 将工具执行结果写入消息列表
                    messages.add(Message.builder()
                            .role("tool")
                            .content(result.isSuccess() ? result.getResult() : result.getError())
                            .build());
                } catch (Exception e) {
                    // 回调策略决定如何处理错误
                    ToolErrorAction action = toolCallPolicy.handleToolError(
                            null, e, context);
                    if (action == ToolErrorAction.ABORT) {
                        messages.add(Message.builder()
                                .role("tool")
                                .content("Error: " + e.getMessage())
                                .build());
                        context.setAttribute("error", e.getMessage());
                        shouldAbort = true;
                        break;
                    }
                }
            }

            if (shouldAbort) break;

            round++;

            if (!toolCallPolicy.shouldContinue(context, round)) break;
        }

        // 构建结果
        String responseText = extractLastAssistantMessage(context);
        ChatResult result = new ChatResult(
                responseText, "stop", "prompt=0 completion=0 total=0",
                Collections.emptyList(), 0L
        );

        // 钩子 3：循环结束后收尾工作
        afterLoop(context, result);

        return result;
    }

    /**
     * 钩子方法 1 —— 循环开始前调用。子类可重写以执行准备工作（如初始化追踪）。
     *
     * @param context 对话上下文
     */
    protected void beforeLoop(ChatContext context) {
        // 默认不做任何事
    }

    /**
     * 钩子方法 2 —— 循环结束后调用。子类可重写以执行收尾工作（如更新指标）。
     *
     * @param context 对话上下文
     * @param result  对话处理结果
     */
    protected void afterLoop(ChatContext context, ChatResult result) {
        // 默认不做任何事
    }

    /**
     * 钩子方法 3 —— 判断模型响应是否包含工具调用。
     * 返回 false 表示没有工具调用，应退出循环。
     *
     * @param response 模型响应
     * @return true 表示有工具调用，应继续循环
     */
    protected boolean handleModelResponse(ModelResponse response) {
        return response.hasToolCalls();
    }

    /**
     * 将 ModelResponse 中的 ToolCallRequest 转换为 ToolCall 列表。
     *
     * @param response 模型响应
     * @return ToolCall 列表，无工具调用时返回空列表
     */
    private List<ToolCall> convertToolCalls(ModelResponse response) {
        if (!response.hasToolCalls()) {
            return null;
        }
        List<ToolCall> result = new ArrayList<>();
        for (ModelResponse.ToolCallRequest req : response.getToolCalls()) {
            result.add(new ToolCall(req.getId(), req.getName(), req.getArguments()));
        }
        return result;
    }

    /**
     * 从消息列表中提取最后一条 assistant 消息的文本内容。
     *
     * @param context 对话上下文
     * @return AI 回复文本，无回复时返回空字符串
     */
    private String extractLastAssistantMessage(ChatContext context) {
        List<Message> messages = context.getRequest().getMessages();
        for (int i = messages.size() - 1; i >= 0; i--) {
            if ("assistant".equals(messages.get(i).getRole())) {
                String content = messages.get(i).getContent();
                return content != null ? content : "";
            }
        }
        return "";
    }
}
```

---

### 第 81 块：WebSearchTool

#### 类介绍

**设计动机**：网络搜索工具。调用外部搜索 API（如 Bing、Google、Brave）获取网络搜索结果。返回结果的标题、摘要和 URL 列表。

**包路径**：lyclaw-engine → lyjew.com.lyclaw.tool.impl

**类型**：类

```java
package lyjew.com.lyclaw.tool.impl;

import lyjew.com.lyclaw.context.ChatContext;
import lyjew.com.lyclaw.model.ToolCall;
import lyjew.com.lyclaw.model.ToolDefinition;
import lyjew.com.lyclaw.tool.Tool;
import lyjew.com.lyclaw.tool.ToolResult;

import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * 网络搜索工具 —— 调用外部搜索 API 获取网络搜索结果。
 *
 * <p>模型需要获取实时信息时（如新闻、天气、最新数据），调用此工具。
 * 参数通过 ToolCall 的 arguments（JSON String）传递，包含关键词 searchQuery。</p>
 *
 * <p><b>参数格式</b>：{ "searchQuery": "要搜索的关键词" }</p>
 *
 * @since 1.0
 * @author LyClaw Team
 * @see Tool
 */
@Component
public class WebSearchTool implements Tool {

    /** 工具名称常量 */
    private static final String TOOL_NAME = "web_search";

    @Override
    public String getName() {
        return TOOL_NAME;
    }

    @Override
    public ToolResult execute(ToolCall toolCall, ChatContext context) {
        try {
            // 1. 解析参数 —— 从 toolCall 的 arguments 中提取搜索关键词
            String query = parseSearchQuery(toolCall);

            // 2. 执行搜索（此处为示例，实际应调用外部搜索 API）
            // TODO: 对接 Brave Search / Bing Search API
            String result = search(query);

            // 3. 返回搜索结果
            return ToolResult.success(result);
        } catch (Exception e) {
            // 搜索失败，返回错误信息
            return ToolResult.failure("Search failed: " + e.getMessage());
        }
    }

    @Override
    public ToolDefinition getDefinition() {
        // 返回工具定义 —— 模型据此了解工具的功能和参数格式
        return ToolDefinition.builder()
                .name(TOOL_NAME)
                .description("搜索互联网获取实时信息。当需要了解新闻、天气、"
                        + "最新数据或其他实时信息时使用。")
                .parameters(Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "searchQuery", Map.of(
                                        "type", "string",
                                        "description", "要搜索的关键词"
                                )
                        ),
                        "required", java.util.List.of("searchQuery")
                ))
                .build();
    }

    /**
     * 从 toolCall 的 arguments JSON 中解析搜索关键词。
     *
     * @param toolCall 工具调用请求
     * @return 搜索关键词
     */
    private String parseSearchQuery(ToolCall toolCall) {
        // 简单解析：从 JSON 参数中提取 searchQuery 字段
        // TODO: 使用 Jackson 解析
        String args = toolCall.getArguments();
        if (args == null || args.isEmpty()) {
            return "";
        }
        // 临时简单解析方式 —— 后续替换为 Jackson 解析
        if (args.contains("\"searchQuery\"")) {
            int start = args.indexOf("\"searchQuery\"") + "\"searchQuery\"".length();
            start = args.indexOf(":", start) + 1;
            start = args.indexOf("\"", start) + 1;
            int end = args.indexOf("\"", start);
            return args.substring(start, end);
        }
        return args.replaceAll("\"", "").trim();
    }

    /**
     * 执行搜索操作。当前为模拟实现，后续对接真正的搜索 API。
     *
     * @param query 搜索关键词
     * @return 搜索结果文本
     */
    private String search(String query) {
        // 模拟搜索结果 —— 实际应调用搜索引擎 API
        return "Search results for '" + query + "':\n"
                + "- " + query + " 的相关信息（此处为模拟结果）\n"
                + "- 请参考: https://example.com/search?q=" + query;
    }
}
```

---

### 第 82 块：CalculatorTool

#### 类介绍

**设计动机**：数学计算工具。模型在需要精确计算时调用此工具（如公式推导、数值计算），避免模型自己计算可能出现的错误。支持基础四则运算和常见数学函数。

**包路径**：lyclaw-engine → lyjew.com.lyclaw.tool.impl

**类型**：类

```java
package lyjew.com.lyclaw.tool.impl;

import lyjew.com.lyclaw.context.ChatContext;
import lyjew.com.lyclaw.model.ToolCall;
import lyjew.com.lyclaw.model.ToolDefinition;
import lyjew.com.lyclaw.tool.Tool;
import lyjew.com.lyclaw.tool.ToolResult;

import javax.script.ScriptEngineManager;
import javax.script.ScriptEngine;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * 数学计算工具 —— 使用 Java ScriptEngine 执行数学表达式求值。
 *
 * <p>模型需要精确计算时调用此工具。支持基础四则运算 (+-*/)、
 * 幂运算 (Math.pow)、三角函数 (sin/cos/tan)、对数 (log) 等。</p>
 *
 * @since 1.0
 * @author LyClaw Team
 * @see Tool
 */
@Component
public class CalculatorTool implements Tool {

    /** 工具名称常量 */
    private static final String TOOL_NAME = "calculator";

    @Override
    public String getName() {
        return TOOL_NAME;
    }

    @Override
    public ToolResult execute(ToolCall toolCall, ChatContext context) {
        try {
            // 1. 从 toolCall 的 arguments 中提取数学表达式
            String expression = parseExpression(toolCall);

            // 2. 使用 Java ScriptEngine 执行表达式求值
            ScriptEngineManager manager = new ScriptEngineManager();
            ScriptEngine engine = manager.getEngineByName("JavaScript");

            // 执行求值
            Object result = engine.eval(expression);

            // 3. 格式化并返回计算结果
            return ToolResult.success("计算结果: " + result);
        } catch (Exception e) {
            // 计算失败（如表达式语法错误、除零等）
            return ToolResult.failure("计算失败: " + e.getMessage());
        }
    }

    @Override
    public ToolDefinition getDefinition() {
        // 返回工具定义 —— 模型根据 JSON Schema 知道需要传入 expression
        return ToolDefinition.builder()
                .name(TOOL_NAME)
                .description("执行数学计算。支持四则运算、幂运算、三角函数等。"
                        + "当需要精确计算数值时使用。")
                .parameters(Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "expression", Map.of(
                                        "type", "string",
                                        "description", "要计算的数学表达式，"
                                                + "如 \"2 + 3 * 4\""
                                )
                        ),
                        "required", java.util.List.of("expression")
                ))
                .build();
    }

    /**
     * 从 toolCall 参数中提取数学表达式。
     *
     * @param toolCall 工具调用请求
     * @return 数学表达式字符串
     */
    private String parseExpression(ToolCall toolCall) {
        String args = toolCall.getArguments();
        if (args == null || args.isEmpty()) {
            return "";
        }
        if (args.contains("\"expression\"")) {
            int start = args.indexOf("\"expression\"") + "\"expression\"".length();
            start = args.indexOf(":", start) + 1;
            start = args.indexOf("\"", start) + 1;
            int end = args.indexOf("\"", start);
            return args.substring(start, end);
        }
        return args.replaceAll("\"", "").trim();
    }
}
```

---

### 第 83 块：CurrentTimeTool

#### 类介绍

**设计动机**：当前时间工具。模型需要查询当前日期时间时调用，返回格式化的日期时间字符串。

**包路径**：lyclaw-engine → lyjew.com.lyclaw.tool.impl

**类型**：类

```java
package lyjew.com.lyclaw.tool.impl;

import lyjew.com.lyclaw.context.ChatContext;
import lyjew.com.lyclaw.model.ToolCall;
import lyjew.com.lyclaw.model.ToolDefinition;
import lyjew.com.lyclaw.tool.Tool;
import lyjew.com.lyclaw.tool.ToolResult;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * 当前时间工具 —— 返回当前日期和时间。
 *
 * <p>模型需要知道当前日期时间时调用此工具（如"今天星期几"、"现在几点"）。
 * 返回格式：yyyy-MM-dd HH:mm:ss。</p>
 *
 * @since 1.0
 * @author LyClaw Team
 * @see Tool
 */
@Component
public class CurrentTimeTool implements Tool {

    /** 工具名称常量 */
    private static final String TOOL_NAME = "current_time";

    /** 日期时间格式化器 */
    private static final DateTimeFormatter FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @Override
    public String getName() {
        return TOOL_NAME;
    }

    @Override
    public ToolResult execute(ToolCall toolCall, ChatContext context) {
        // 获取当前日期时间
        LocalDateTime now = LocalDateTime.now();

        // 格式化为可读字符串
        String formatted = now.format(FORMATTER);

        // 返回结果
        return ToolResult.success("当前时间: " + formatted);
    }

    @Override
    public ToolDefinition getDefinition() {
        // 当前时间工具不需要参数
        return ToolDefinition.builder()
                .name(TOOL_NAME)
                .description("获取当前的日期和时间。当需要知道当前时间、"
                        + "日期或星期几时使用。不需要任何参数。")
                .parameters(Map.of("type", "object", "properties", Map.of()))
                .build();
    }
}
```

---

### 第 84 块：McpToolAdapter

#### 类介绍

**设计动机**：MCP 协议适配器。将 MCP（Model Context Protocol）的远程工具包装为引擎内部的 Tool 接口。引擎层不感知 MCP 协议细节。

**包路径**：lyclaw-engine → lyjew.com.lyclaw.tool.impl

**类型**：类

```java
package lyjew.com.lyclaw.tool.impl;

import lyjew.com.lyclaw.context.ChatContext;
import lyjew.com.lyclaw.model.ToolCall;
import lyjew.com.lyclaw.model.ToolDefinition;
import lyjew.com.lyclaw.tool.Tool;
import lyjew.com.lyclaw.tool.ToolResult;

import java.util.Map;

/**
 * MCP 协议适配器 —— 将 MCP 远程工具适配为引擎内部的 Tool 接口。
 *
 * <p>MCP（Model Context Protocol）是 Anthropic 提出的工具通信协议。
 * 通过 MCP，引擎可以调用远程部署的工具服务（如远程数据库查询、外部 API 调用）。
 * McpToolAdapter 包装 MCP 工具的信息，使得引擎层完全不需要感知 MCP 协议细节。</p>
 *
 * <p><b>为何单独适配</b>：如果直接将 MCP 工具注册为 Tool 实现类，
 * 引擎层代码就需要引入 MCP 协议的依赖。通过 McpToolAdapter 适配，
 * 引擎层只需要 Tool 接口，MCP 的细节被隔离在适配器中。</p>
 *
 * @since 1.0
 * @author LyClaw Team
 * @see Tool
 */
public class McpToolAdapter implements Tool {

    /** MCP 工具名称 */
    private final String name;

    /** MCP 工具描述 */
    private final String description;

    /** MCP 工具的参数 JSON Schema */
    private final Map<String, Object> parameters;

    /** MCP 服务端点 URL */
    private final String endpointUrl;

    /**
     * 构造 MCP 工具适配器。
     *
     * @param name        工具名称
     * @param description 工具描述
     * @param parameters  参数的 JSON Schema
     * @param endpointUrl MCP 服务端点 URL
     */
    public McpToolAdapter(String name, String description,
                          Map<String, Object> parameters, String endpointUrl) {
        this.name = name;
        this.description = description;
        this.parameters = parameters;
        this.endpointUrl = endpointUrl;
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public ToolResult execute(ToolCall toolCall, ChatContext context) {
        try {
            // 1. 构造 MCP 协议请求体
            String requestBody = buildMcpRequest(toolCall);

            // 2. 发送 HTTP 请求到 MCP 服务端点
            // TODO: 使用 HttpClient / WebClient 发送
            String response = sendMcpRequest(requestBody);

            // 3. 解析并返回结果
            return ToolResult.success(response);
        } catch (Exception e) {
            return ToolResult.failure("MCP tool execution failed: "
                    + e.getMessage());
        }
    }

    @Override
    public ToolDefinition getDefinition() {
        return ToolDefinition.builder()
                .name(name)
                .description(description)
                .parameters(parameters)
                .build();
    }

    /**
     * 构造 MCP 协议请求体。将 ToolCall 中的参数转换为 MCP 协议格式。
     *
     * @param toolCall 工具调用请求
     * @return MCP 协议格式的请求 JSON 字符串
     */
    private String buildMcpRequest(ToolCall toolCall) {
        // 构建 MCP 调用请求
        return "{\"jsonrpc\":\"2.0\",\"method\":\"tools/call\","
                + "\"params\":{\"name\":\"" + toolCall.getName()
                + "\",\"arguments\":" + toolCall.getArguments() + "}}";
    }

    /**
     * 发送 MCP 请求到远端端点。
     *
     * @param body MCP 协议请求体
     * @return 响应内容
     */
    private String sendMcpRequest(String body) {
        // TODO: 实际使用 HTTP 客户端发送请求
        return "{}";
    }
}
```

---

### 第 85 块：DefaultSkillRegistry

#### 类介绍

**设计动机**：默认技能注册表。管理 Skill 的注册、查找和依赖关系。通过 SkillGraph 维护技能之间的依赖关系，支持拓扑排序确定执行顺序。

**包路径**：lyclaw-engine → lyjew.com.lyclaw.skill.impl

**类型**：类

```java
package lyjew.com.lyclaw.skill.impl;

import lyjew.com.lyclaw.skill.Skill;
import lyjew.com.lyclaw.skill.SkillGraph;
import lyjew.com.lyclaw.skill.SkillRegistry;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * 默认技能注册表实现 —— 使用 ConcurrentHashMap 存储，线程安全。
 *
 * <p>Skill 是比 Tool 更高层的抽象，表示一组有逻辑关联的操作集合。
 * DefaultSkillRegistry 管理所有已注册的 Skill，并维护技能的依赖图，
 * 确保在执行前能检查循环依赖并确定正确的执行顺序。</p>
 *
 * <p><b>与 DefaultToolRegistry 的区别</b>：
 * <ul>
 *   <li>Tool 是最小可执行单元，Skill 是多个 Tool/子 Skill 的编排组合</li>
 *   <li>Tool 没有依赖关系，Skill 可能有（如必须先执行 "用户认证" 才能执行 "查询订单"）</li>
 *   <li>Tool 通过 ToolRegistry 执行，Skill 通过 SkillRegistry 编排</li>
 * </ul>
 * </p>
 *
 * @since 1.0
 * @author LyClaw Team
 * @see SkillRegistry
 * @see SkillGraph
 */
@Component
public class DefaultSkillRegistry implements SkillRegistry {

    /** 技能存储映射 —— key 是技能 ID，value 是 Skill 实例 */
    private final ConcurrentHashMap<String, Skill> skills = new ConcurrentHashMap<>();

    /** 技能依赖图 —— 维护技能之间的依赖关系 */
    private final SkillGraph dependencyGraph;


    /**
     * 构造默认技能注册表。
     *
     * <p>Spring 会自动注入所有 @Component 的 Skill 实现类。</p>
     *
     * @param skillList Spring 自动注入的所有 Skill 实现
     * @param graph     技能依赖图（用于拓扑排序）
     */
    public DefaultSkillRegistry(List<Skill> skillList, SkillGraph graph) {
        this.dependencyGraph = graph;
        // 逐个注册所有注入的技能
        for (Skill skill : skillList) {
            register(skill);
        }
    }

    /**
     * 注册一个技能。
     *
     * @param skill 技能实例，不可为 null
     */
    @Override
    public void register(Skill skill) {
        skills.put(skill.getSkillId(), skill);
    }

    /**
     * 按技能 ID 查找。
     *
     * @param skillId 技能 ID
     * @return Skill 实例，不存在返回 null
     */
    @Override
    public Skill get(String skillId) {
        return skills.get(skillId);
    }

    /**
     * 获取所有已注册的技能。
     *
     * @return 技能列表
     */
    @Override
    public List<Skill> getAll() {
        return List.copyOf(skills.values());
    }

    /**
     * 获取指定技能的依赖项列表。
     *
     * @param skillId 技能 ID
     * @return 依赖的技能 ID 列表
     */
    @Override
    public List<String> getDependencies(String skillId) {
        return dependencyGraph.getDependencies(skillId);
    }

    /**
     * 解析技能的拓扑执行顺序。
     *
     * @return 按依赖顺序排列的技能 ID 列表
     */
    @Override
    public List<String> resolveExecutionOrder() {
        return dependencyGraph.getExecutionOrder();
    }
}
```

---

### 第 86 块：SkillGraphImpl

#### 类介绍

**设计动机**：技能依赖图实现。使用有向图（邻接表）维护技能间的依赖关系，支持拓扑排序检测循环依赖。

**包路径**：lyclaw-engine → lyjew.com.lyclaw.skill.impl

**类型**：类

```java
package lyjew.com.lyclaw.skill.impl;

import lyjew.com.lyclaw.skill.SkillGraph;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * 技能依赖图实现 —— 使用邻接表存储有向图，支持拓扑排序和循环依赖检测。
 *
 * <p><b>设计动机</b>：技能之间的依赖必须是无环有向图（DAG），否则无法确定执行顺序。
 * SkillGraphImpl 在每次添加/移除依赖后维护图结构，提供拓扑排序和环检测。
 * 如果检测到环，resolveExecutionOrder() 抛出异常阻止执行。</p>
 *
 * <p><b>拓扑排序算法</b>：Kahn 算法（BFS 入度法）。
 * 每次从图中移除入度为 0 的节点加入结果列表，直到所有节点被移除。
 * 如果仍有剩余节点，说明存在环。</p>
 *
 * @since 1.0
 * @author LyClaw Team
 * @see SkillGraph
 */
@Component
public class SkillGraphImpl implements SkillGraph {

    /**
     * 邻接表 —— key 是技能 ID，value 是它依赖的技能 ID 列表。
     *
     * <p>例如：adjacency.get("A") = ["B", "C"] 表示 A 依赖 B 和 C，
     * 执行顺序：B -> C -> A。</p>
     */
    private final ConcurrentHashMap<String, List<String>> adjacency = new ConcurrentHashMap<>();

    /**
     * 反向邻接表 —— key 是技能 ID，value 是依赖它的技能 ID 列表。
     *
     * <p>用于快速查找"谁依赖我"（被依赖查询）。</p>
     */
    private final ConcurrentHashMap<String, List<String>> reverseAdjacency = new ConcurrentHashMap<>();

    /**
     * 添加依赖关系。
     *
     * @param skillId     依赖方（如 "A"）
     * @param dependsOn   被依赖方（如 "B"，A 依赖 B）
     */
    @Override
    public void addDependency(String skillId, String dependsOn) {
        // 更新邻接表
        adjacency.computeIfAbsent(skillId, k -> new ArrayList<>())
                .add(dependsOn);
        // 更新反向邻接表
        reverseAdjacency.computeIfAbsent(dependsOn, k -> new ArrayList<>())
                .add(skillId);
    }

    /**
     * 移除依赖关系。
     *
     * @param skillId     依赖方
     * @param dependsOn   被依赖方
     */
    @Override
    public void removeDependency(String skillId, String dependsOn) {
        // 从邻接表中移除
        adjacency.computeIfPresent(skillId, (k, v) -> {
            v.remove(dependsOn);
            return v.isEmpty() ? null : v;
        });
        // 从反向邻接表中移除
        reverseAdjacency.computeIfPresent(dependsOn, (k, v) -> {
            v.remove(skillId);
            return v.isEmpty() ? null : v;
        });
    }

    /**
     * 获取指定技能依赖的所有技能列表。
     *
     * @param skillId 技能 ID
     * @return 依赖的技能 ID 列表，无依赖时返回空列表
     */
    @Override
    public List<String> getDependencies(String skillId) {
        return adjacency.getOrDefault(skillId, Collections.emptyList());
    }

    /**
     * 获取所有依赖指定技能的技能列表。
     *
     * @param skillId 技能 ID
     * @return 依赖此技能的技能 ID 列表
     */
    @Override
    public List<String> getDependents(String skillId) {
        return reverseAdjacency.getOrDefault(skillId, Collections.emptyList());
    }

    /**
     * 获取拓扑排序后的执行顺序。
     *
     * <p>使用 Kahn 算法：
     * <ol>
     *   <li>统计每个节点的入度</li>
     *   <li>将入度为 0 的节点加入队列</li>
     *   <li>逐一出队，将其邻居的入度减 1</li>
     *   <li>如果新的节点入度变为 0，加入队列</li>
     *   <li>如果结果列表大小不等于节点总数，说明存在环</li>
     * </ol>
     * </p>
     *
     * @return 按依赖顺序排列的技能 ID 列表（依赖的先执行）
     * @throws IllegalStateException 如果存在环
     */
    @Override
    public List<String> getExecutionOrder() {
        // 1. 收集所有节点（邻接表 key + 所有依赖项）
        Set<String> allNodes = new HashSet<>(adjacency.keySet());
        adjacency.values().forEach(allNodes::addAll);

        // 2. 统计入度并初始化队列
        Map<String, Integer> inDegree = new HashMap<>();
        for (String node : allNodes) {
            inDegree.put(node, 0);
        }
        for (List<String> deps : adjacency.values()) {
            for (String dep : deps) {
                inDegree.merge(dep, 1, Integer::sum);
            }
        }

        // 3. 入度为 0 的节点入队
        Queue<String> queue = new LinkedList<>();
        for (Map.Entry<String, Integer> entry : inDegree.entrySet()) {
            if (entry.getValue() == 0) {
                queue.offer(entry.getKey());
            }
        }

        // 4. BFS 拓扑排序
        List<String> result = new ArrayList<>();
        while (!queue.isEmpty()) {
            String node = queue.poll();
            result.add(node);

            // 将当前节点的邻居入度减 1
            List<String> neighbors = reverseAdjacency.get(node);
            if (neighbors != null) {
                for (String neighbor : neighbors) {
                    inDegree.merge(neighbor, -1, Integer::sum);
                    if (inDegree.get(neighbor) == 0) {
                        queue.offer(neighbor);
                    }
                }
            }
        }

        // 5. 检查是否存在环
        if (result.size() != allNodes.size()) {
            throw new IllegalStateException(
                    "循环依赖检测到！剩余节点数: "
                    + (allNodes.size() - result.size()));
        }

        return result;
    }

    /**
     * 判断图中是否存在环。
     *
     * <p>通过尝试拓扑排序，如果结果列表大小小于节点总数则说明有环。</p>
     *
     * @return true 表示存在环
     */
    @Override
    public boolean hasCycle() {
        try {
            getExecutionOrder();
            return false;
        } catch (IllegalStateException e) {
            return true;
        }
    }
}
```

---

### 第 87 块：ToolToSkillAdapter

#### 类介绍

**设计动机**：Tool→Skill 适配器。将 Tool 接口适配为 Skill 接口，使得已经有 Tool 实现的功能可以作为 Skill 注册并使用。

**包路径**：lyclaw-engine → lyjew.com.lyclaw.skill.impl.adapters

**类型**：类

```java
package lyjew.com.lyclaw.skill.impl.adapters;

import lyjew.com.lyclaw.context.ChatContext;
import lyjew.com.lyclaw.dto.SkillResult;
import lyjew.com.lyclaw.model.ToolCall;
import lyjew.com.lyclaw.skill.Skill;
import lyjew.com.lyclaw.tool.Tool;
import lyjew.com.lyclaw.tool.ToolResult;

import java.util.concurrent.CompletableFuture;

/**
 * Tool→Skill 适配器 —— 将 Tool 接口包装为 Skill 接口。
 *
 * <p><b>设计动机</b>：引擎中同时存在 Tool 和 Skill 两套抽象。
 * 一部分功能已经以 Tool 的形式实现了（如 WebSearchTool），
 * 但上层调度器（TaskPlanner）以 Skill 为最小执行单元。
 * 通过此适配器，已有的 Tool 可以直接作为 Skill 使用，无需重新实现。</p>
 *
 * <p><b>适配方式</b>：
 * <ul>
 *   <li>getSkillId() → tool.getName()</li>
 *   <li>execute(ChatContext) → 构造 ToolCall → tool.execute()</li>
 *   <li>返回 CompletableFuture 包装的 SkillResult</li>
 * </ul>
 * </p>
 *
 * @since 1.0
 * @author LyClaw Team
 * @see Skill
 * @see Tool
 */
public class ToolToSkillAdapter implements Skill {

    /** 被适配的工具实例 */
    private final Tool tool;

    /**
     * 构造适配器。
     *
     * @param tool 要包装为 Skill 的 Tool 实例
     */
    public ToolToSkillAdapter(Tool tool) {
        this.tool = tool;
    }

    @Override
    public String getSkillId() {
        return tool.getName();
    }

    @Override
    public String getName() {
        return tool.getName();
    }

    @Override
    public String getDescription() {
        return tool.getDefinition().getDescription();
    }

    /**
     * 获取技能类型。返回 TOOL（表示底层是 Tool 实现）。
     *
     * @return 技能类型枚举 TOOL
     */
    public String getType() {
        return "TOOL";
    }

    /**
     * 执行技能。将当前对话中的最后一条消息作为参数构造 ToolCall 并执行。
     *
     * @param context 当前对话上下文
     * @return 异步执行结果
     */
    @Override
    public CompletableFuture<SkillResult> execute(ChatContext context) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                // 1. 用 builder 构造 ToolCall
                ToolCall toolCall = ToolCall.builder()
                        .name(tool.getName())
                        .arguments(extractArguments(context))
                        .build();

                // 2. 执行底层的 Tool（2参数：toolCall + context）
                ToolResult result = tool.execute(toolCall, context);

                // 3. 将 ToolResult 转为 SkillResult（6参数构造器）
                return new SkillResult(
                        tool.getName(),
                        result.isSuccess(),
                        result.isSuccess() ? result.getResult() : "",
                        result.isSuccess() ? "" : result.getError(),
                        0,
                        0L
                );
            } catch (Exception e) {
                return new SkillResult(
                        tool.getName(), false, "", e.getMessage(), 0, 0L
                );
            }
        });
    }

    /**
     * 从 ChatContext 中提取工具执行参数。
     * 默认将最后一条用户消息作为参数。
     *
     * @param context 对话上下文
     * @return 参数字符串
     */
    private String extractArguments(ChatContext context) {
        // 获取最后一条用户消息作为工具参数
        for (int i = context.getRequest().getMessages().size() - 1; i >= 0; i--) {
            if ("user".equals(context.getRequest().getMessages().get(i).getRole())) {
                return context.getRequest().getMessages().get(i).getContent();
            }
        }
        return "";
    }
}
```

---

### 第 88 块：FileMemoryManager

#### 类介绍

**设计动机**：基于文件的记忆管理器。通过 lyclaw-storage 模块的 MemoryStorage 将记忆持久化到文件系统。Memory.id 固定为 `"global"`（单例）。

**包路径**：lyclaw-engine → lyjew.com.lyclaw.memory.impl

**类型**：类

```java
package lyjew.com.lyclaw.memory.impl;

import lyjew.com.lyclaw.memory.MemoryContent;
import lyjew.com.lyclaw.memory.MemoryManager;
import lyjew.com.lyclaw.memory.MemoryStrategy;
import lyjew.com.lyclaw.storage.MemoryStorage;
import lyjew.com.lyclaw.model.Memory;

import java.util.Collections;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * 基于文件的记忆管理器 —— 通过 MemoryStorage（lyclaw-storage 模块）持久化记忆。
 *
 * <p>Memory 实体 id 固定为 "global"，每次操作通过 BaseStorage.get/save 完成。
 * 启动时从文件反序列化恢复，运行时修改后序列化写回。</p>
 *
 * <p><b>为什么用 MemoryStorage 而不是直接读写文件</b>：
 * memory/impl 属于 engine 层，不应该知道文件路径、序列化格式等底层细节。
 * 通过 MemoryStorage（继承 BaseStorage），engine 层只管业务逻辑。</p>
 *
 * @since 1.0
 * @author LyClaw Team
 * @see MemoryManager
 * @see MemoryStorage
 * @see Memory
 */
@Component
public class FileMemoryManager implements MemoryManager {

    /** 全局记忆的固定 id */
    private static final String GLOBAL_MEMORY_ID = "global";

    /** 文件存储接口 —— 由 lyclaw-storage 模块提供 */
    private final MemoryStorage storage;

    /** 当前记忆内容（内存快照，由 Memory 转换而来） */
    private MemoryContent current;

    /**
     * 构造 FileMemoryManager，从文件系统加载记忆。
     *
     * @param storage MemoryStorage 实例（由 Spring 注入）
     */
    public FileMemoryManager(MemoryStorage storage) {
        this.storage = storage;
        // 启动时从文件加载记忆；无文件时创建空内容
        this.current = loadFromStorage();
    }

    /**
     * 从文件存储加载记忆，无文件时返回空内容。
     */
    private MemoryContent loadFromStorage() {
        Optional<Memory> opt = storage.get(GLOBAL_MEMORY_ID);
        if (opt.isPresent()) {
            Memory mem = opt.get();
            return new MemoryContent(
                    mem.getContent(), "file", mem.isEnabled(),
                    mem.getTags(), 0.0
            );
        }
        return new MemoryContent("", "file", true, Collections.emptyList(), 0.0);
    }

    /**
     * 将内存中的 MemoryContent 写回文件存储。
     *
     * <p>每次 append/rewrite 后调用，同步持久化。</p>
     */
    private void persist() {
        Memory mem = Memory.builder()
                .id(GLOBAL_MEMORY_ID)
                .content(current.getContent())
                .enabled(true)
                .tags(current.getTags())
                .build();
        storage.save(mem);
    }

    @Override
    public MemoryContent read() {
        return current;
    }

    @Override
    public void append(String content) {
        String newContent = current.getContent() + "\n" + content;
        current = new MemoryContent(
                newContent, "file", false, current.getTags(), 0.0
        );
        persist();
    }

    @Override
    public void rewrite(String content) {
        current = new MemoryContent(
                content, "file", false, Collections.emptyList(), 0.0
        );
        persist();
    }

    @Override
    public List<MemoryContent> search(String query) {
        if (current.getContent().contains(query)) {
            return List.of(current);
        }
        return Collections.emptyList();
    }

    @Override
    public MemoryStrategy getStrategy() {
        return null;
    }

    @Override
    public void setStrategy(MemoryStrategy strategy) {
    }
}
```

---

### 第 89 块：ManualMemoryStrategy

#### 类介绍

**设计动机**：手动记忆策略。始终将记忆内容注入上下文，不做任何截断或选择。作为兜底策略使用。

**包路径**：lyclaw-engine → lyjew.com.lyclaw.memory.impl

**类型**：类

```java
package lyjew.com.lyclaw.memory.impl;

import lyjew.com.lyclaw.context.ChatContext;
import lyjew.com.lyclaw.memory.MemoryContent;
import lyjew.com.lyclaw.memory.MemoryStrategy;

import org.springframework.stereotype.Component;

/**
 * 手动记忆策略 —— 始终将记忆注入上下文，不做任何截断。
 *
 * <p><b>设计动机</b>：某些场景下（如调试、长度可控的短记忆），
 * 不需要复杂的记忆选择逻辑。ManualMemoryStrategy 始终返回 true，
 * 相当于"全量记忆注入"。作为其他策略的兜底。</p>
 *
 * <p>在实际项目中，通常会有更复杂的策略，如：
 * <ul>
 *   <li>重要性过滤：只注入重要性高于阈值的记忆</li>
 *   <li>时间衰减：只注入最近 N 条记忆</li>
 *   <li>相关性匹配：只注入与当前 query 语义相关的记忆</li>
 * </ul>
 * </p>
 *
 * @since 1.0
 * @author LyClaw Team
 * @see MemoryStrategy
 */
@Component
public class ManualMemoryStrategy implements MemoryStrategy {

    @Override
    public String formatForContext(MemoryContent content) {
        // 用 <memory> 标签包裹记忆内容
        return "<memory>\n" + (content != null ? content.getContent() : "") + "\n</memory>";
    }

    @Override
    public boolean shouldIncludeInContext(MemoryContent content,
                                         ChatContext context) {
        // 始终注入记忆
        return true;
    }

    @Override
    public int getPriority() {
        // 最低优先级（兜底策略），始终最后被选择
        return 0;
    }
}
```

---

### 第 90 块：InMemoryEventBus

#### 类介绍

**设计动机**：内存事件总线。使用 CopyOnWriteArrayList 存储订阅者，支持按事件类型订阅/取消订阅。所有操作线程安全。

**包路径**：lyclaw-engine → lyjew.com.lyclaw.event.impl

**类型**：类

```java
package lyjew.com.lyclaw.event.impl;

import lyjew.com.lyclaw.event.Event;
import lyjew.com.lyclaw.event.EventBus;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import org.springframework.stereotype.Component;

/**
 * InMemoryEventBus —— 内存事件总线实现。
 *
 * <p>使用 ConcurrentHashMap 按事件类型（Class）存储订阅者列表，
 * 每个订阅者列表使用 CopyOnWriteArrayList 保证并发安全。
 * publish() 时遍历所有匹配类型的订阅者执行。</p>
 *
 * <p><b>设计动机</b>：事件总线是解耦组件间通信的关键机制。
 * 如果不使用事件总线，组件之间需要直接依赖对方的接口进行通信，
 * 导致代码耦合度高。通过事件总线：
 * <ul>
 *   <li>MetricsStage 发布事件 → LoggingInterceptor 消费，两者互不依赖</li>
 *   <li>新增监控模块只需订阅事件，不需修改现有代码</li>
 * </ul>
 * </p>
 *
 * <p><b>线程安全</b>：
 * <ul>
 *   <li>subscribe/unsubscribe：CopyOnWriteArrayList 写时复制，遍历线程安全</li>
 *   <li>publish：遍历快照执行，不存在 ConcurrentModificationException</li>
 *   <li>类型映射：ConcurrentHashMap，并发无锁</li>
 * </ul>
 * </p>
 *
 * @since 1.0
 * @author LyClaw Team
 * @see EventBus
 */
@Component
public class InMemoryEventBus implements EventBus {

    /**
     * 事件类型 → 订阅者列表映射。
     *
     * <p>每个事件类型（Class）对应一组订阅者（Consumer）。
     * publish(Event) 时根据 Event.getClass() 查找匹配的订阅者执行。</p>
     */
    private final ConcurrentHashMap<Class<?>, CopyOnWriteArrayList<Consumer<?>>> subscribers = new ConcurrentHashMap<>();

    /**
     * 发布事件。遍历所有订阅了该事件类型的消费者执行。
     *
     * @param event 要发布的事件
     */
    @SuppressWarnings("unchecked")
    @Override
    public void publish(Event event) {
        // 查找该事件类型对应的订阅者列表
        CopyOnWriteArrayList<Consumer<?>> list = subscribers.get(event.getClass());

        if (list != null) {
            // 遍历所有订阅者执行 —— CopyOnWriteArrayList 保证遍历安全
            for (Consumer<?> consumer : list) {
                ((Consumer<Event>) consumer).accept(event);
            }
        }
    }

    /**
     * 订阅事件。当指定类型的事件发布时，consumer 被执行。
     *
     * @param eventType 要订阅的事件类型
     * @param consumer  事件消费者
     * @param <T>       事件类型泛型
     */
    @Override
    public <T extends Event> void subscribe(Class<T> eventType, Consumer<T> consumer) {
        // computeIfAbsent：没有订阅者列表时创建新的 CopyOnWriteArrayList
        subscribers.computeIfAbsent(eventType, k -> new CopyOnWriteArrayList<>())
                .add(consumer);
    }

    /**
     * 取消订阅。从指定事件类型的订阅者列表中移除。
     *
     * @param eventType 要取消订阅的事件类型
     * @param consumer  要移除的消费者
     * @param <T>       事件类型泛型
     */
    @Override
    public <T extends Event> void unsubscribe(Class<T> eventType, Consumer<T> consumer) {
        CopyOnWriteArrayList<Consumer<?>> list = subscribers.get(eventType);
        if (list != null) {
            list.remove(consumer);
            // 如果列表为空，清理条目
            if (list.isEmpty()) {
                subscribers.remove(eventType);
            }
        }
    }

    /**
     * 清空所有订阅者。
     */
    @Override
    public void clear() {
        subscribers.clear();
    }
}
```

---

### 第 91 块：TokenConsumedEvent

#### 类介绍

**设计动机**：Token 消耗事件。当模型调用消耗了 Token 时发布此事件，包含提示 Token 数、补全 Token 数和总 Token 数。

**包路径**：lyclaw-engine → lyjew.com.lyclaw.event.impl

**类型**：类

```java
package lyjew.com.lyclaw.event.impl;

import lyjew.com.lyclaw.event.Event;

/**
 * Token 消耗事件 —— 当模型调用消耗了 Token 时发布。
 *
 * <p>MetricsStage 或 ModelProvider 在模型调用完成后发布此事件，
 * EventBus 的订阅者（如 LoggingInterceptor、计费模块）据此记录
 * Token 消耗量。</p>
 *
 * @since 1.0
 * @author LyClaw Team
 * @see Event
 */
public class TokenConsumedEvent extends Event {

    /** 模型提供商标识 */
    private final String provider;

    /** 使用的模型名称 */
    private final String model;

    /** 提示（输入）Token 数量 */
    private final int promptTokens;

    /** 补全（输出）Token 数量 */
    private final int completionTokens;

    /** 总 Token 数量 */
    private final int totalTokens;

    /**
     * 构造 Token 消耗事件。
     *
     * @param source           事件来源
     * @param provider         模型提供商标识
     * @param model            模型名称
     * @param promptTokens     提示 Token 数
     * @param completionTokens 补全 Token 数
     * @param totalTokens      总 Token 数
     */
    public TokenConsumedEvent(String source, String provider, String model,
                              int promptTokens, int completionTokens, int totalTokens) {
        super(source, "TOKEN_CONSUMED");
        this.provider = provider;
        this.model = model;
        this.promptTokens = promptTokens;
        this.completionTokens = completionTokens;
        this.totalTokens = totalTokens;
    }

    /** @return 模型提供商标识 */
    public String getProvider() { return provider; }

    /** @return 模型名称 */
    public String getModel() { return model; }

    /** @return 提示 Token 数 */
    public int getPromptTokens() { return promptTokens; }

    /** @return 补全 Token 数 */
    public int getCompletionTokens() { return completionTokens; }

    /** @return 总 Token 数 */
    public int getTotalTokens() { return totalTokens; }
}
```

---

### 第 92 块：ToolCalledEvent

#### 类介绍

**设计动机**：工具调用事件。当工具被调用时发布此事件，包含工具名称、参数、执行结果和执行耗时。

**包路径**：lyclaw-engine → lyjew.com.lyclaw.event.impl

**类型**：类

```java
package lyjew.com.lyclaw.event.impl;

import lyjew.com.lyclaw.event.Event;
import lyjew.com.lyclaw.tool.ToolResult;

/**
 * 工具调用事件 —— 当工具被执行时发布。
 *
 * <p>ToolCallLoop 在执行完每个工具后发布此事件，
 * 供日志和监控模块记录工具调用情况。</p>
 *
 * @since 1.0
 * @author LyClaw Team
 * @see Event
 */
public class ToolCalledEvent extends Event {

    /** 工具名称 */
    private final String toolName;

    /** 工具调用参数（JSON 字符串） */
    private final String arguments;

    /** 工具执行结果 */
    private final ToolResult result;

    /** 执行耗时（毫秒） */
    private final long elapsedMs;

    /**
     * 构造工具调用事件。
     *
     * @param source    事件来源
     * @param toolName  工具名称
     * @param arguments 工具参数
     * @param result    执行结果
     * @param elapsedMs 执行耗时（ms）
     */
    public ToolCalledEvent(String source, String toolName, String arguments,
                           ToolResult result, long elapsedMs) {
        super(source, "TOOL_CALLED");
        this.toolName = toolName;
        this.arguments = arguments;
        this.result = result;
        this.elapsedMs = elapsedMs;
    }

    /** @return 工具名称 */
    public String getToolName() { return toolName; }

    /** @return 工具参数 */
    public String getArguments() { return arguments; }

    /** @return 执行结果 */
    public ToolResult getResult() { return result; }

    /** @return 执行耗时（ms） */
    public long getElapsedMs() { return elapsedMs; }
}
```

---

### 第 93 块：AgentStateChangedEvent

#### 类介绍

**设计动机**：Agent 状态变更事件。当 Agent 的状态发生变化时发布，供调度器和监控模块记录和决策。

**包路径**：lyclaw-engine → lyjew.com.lyclaw.event.impl

**类型**：类

```java
package lyjew.com.lyclaw.event.impl;

import lyjew.com.lyclaw.agent.AgentState;
import lyjew.com.lyclaw.event.Event;

/**
 * Agent 状态变更事件 —— 当 Agent 的状态发生变化时发布。
 *
 * <p>AgentCoordinator 在 Agent 状态变更时发布此事件，
 * 供调度器、日志模块和 UI 监控模块消费。</p>
 *
 * @since 1.0
 * @author LyClaw Team
 * @see Event
 */
public class AgentStateChangedEvent extends Event {

    /** Agent ID */
    private final String agentId;

    /** 旧状态 */
    private final AgentState oldState;

    /** 新状态 */
    private final AgentState newState;

    /**
     * 构造 Agent 状态变更事件。
     *
     * @param source   事件来源
     * @param agentId  Agent ID
     * @param oldState 旧状态
     * @param newState 新状态
     */
    public AgentStateChangedEvent(String source, String agentId,
                                  AgentState oldState, AgentState newState) {
        super(source, "AGENT_STATE_CHANGED");
        this.agentId = agentId;
        this.oldState = oldState;
        this.newState = newState;
    }

    /** @return Agent ID */
    public String getAgentId() { return agentId; }

    /** @return 旧状态 */
    public AgentState getOldState() { return oldState; }

    /** @return 新状态 */
    public AgentState getNewState() { return newState; }
}
```

---

### 第 94 块：StarAgentChannel

#### 类介绍

**设计动机**：星型拓扑 Agent 通信频道。通过中心化的消息队列中转，每个 Agent 只需要连接到 Channel 即可收发消息。

**包路径**：lyclaw-engine → lyjew.com.lyclaw.agent.impl

**类型**：类

```java
package lyjew.com.lyclaw.agent.impl;

import lyjew.com.lyclaw.agent.AgentChannel;
import lyjew.com.lyclaw.agent.AgentMessage;

import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.function.Consumer;
import org.springframework.stereotype.Component;

/**
 * 星型拓扑 Agent 通信频道 —— 中心化消息中转。
 *
 * <p>所有 Agent 通过此 Channel 收发消息。有三种消息路由方式：
 * <ul>
 *   <li>点对点：send(agentId, message) → 直接写入目标 Agent 的消息队列</li>
 *   <li>广播：broadcast(message) → 所有 Agent 都能收到</li>
 *   <li>订阅：subscribe(agentId, consumer) → Agent 注册自己的消费者</li>
 * </ul>
 * </p>
 *
 * <p><b>设计动机</b>：如果不使用中心化的 Channel，Agent 之间需要互相知道
 * 对方的地址和通信方式，形成网状拓扑，耦合度高。星型拓扑将所有 Agent
 * 连接到中心 Channel，新增 Agent 只需注册到 Channel，零改动现有 Agent。</p>
 *
 * @since 1.0
 * @author LyClaw Team
 * @see AgentChannel
 * @see AgentMessage
 */
@Component
public class StarAgentChannel implements AgentChannel {

    /**
     * Agent 的消息队列映射 —— key 是 agentId，value 是阻塞队列。
     *
     * <p>send() 时往目标 agent 的队列投递消息。
     * receive() 时从自己的队列取出消息。</p>
     */
    private final ConcurrentHashMap<String, BlockingQueue<AgentMessage>> queues = new ConcurrentHashMap<>();

    /**
     * Agent 的消息消费者映射 —— key 是 agentId，value 是消费者列表。
     *
     * <p>subscribe() 时注册。send() 时除了写入队列，也会调用消费者的 accept()。</p>
     */
    private final ConcurrentHashMap<String, CopyOnWriteArrayList<Consumer<AgentMessage>>> consumers = new ConcurrentHashMap<>();

    /**
     * 全局广播订阅者列表。
     */
    private final CopyOnWriteArrayList<Consumer<AgentMessage>> globalConsumers = new CopyOnWriteArrayList<>();

    /**
     * 发送消息给指定 Agent。
     *
     * <p>消息会被写入目标 Agent 的阻塞队列，同时通知其消费者。</p>
     *
     * @param message 要发送的消息
     */
    @Override
    public void send(AgentMessage message) {
        // 1. 写入目标 Agent 的消息队列
        if (message.getTo() != null) {
            BlockingQueue<AgentMessage> queue = queues
                    .computeIfAbsent(message.getTo(), k -> new LinkedBlockingQueue<>());
            queue.offer(message);

            // 2. 通知目标 Agent 的消费者
            CopyOnWriteArrayList<Consumer<AgentMessage>> list = consumers.get(message.getTo());
            if (list != null) {
                for (Consumer<AgentMessage> consumer : list) {
                    consumer.accept(message);
                }
            }
        }

        // 3. 通知全局广播订阅者
        for (Consumer<AgentMessage> consumer : globalConsumers) {
            consumer.accept(message);
        }
    }

    /**
     * 接收消息（从自己的消息队列中取出一条）。
     *
     * @param agentId Agent ID
     */
    @Override
    public void receive(String agentId) {
        BlockingQueue<AgentMessage> queue = queues.get(agentId);
        if (queue != null) {
            // poll() 非阻塞取出消息
            AgentMessage message = queue.poll();
            if (message != null) {
                // 消息已取出，由调用方自行处理
            }
        }
    }

    /**
     * 广播消息给所有 Agent。
     *
     * @param message 要广播的消息
     */
    public void broadcast(AgentMessage message) {
        // 遍历所有队列，将消息写入每个 Agent 的队列
        for (BlockingQueue<AgentMessage> queue : queues.values()) {
            queue.offer(message);
        }
        // 通知所有消费者
        for (Consumer<AgentMessage> consumer : globalConsumers) {
            consumer.accept(message);
        }
    }

    /**
     * 订阅消息。注册消息消费者，当有消息到达时自动被调用。
     *
     * @param agentId  要订阅的 Agent ID
     * @param consumer 消息消费者
     */
    public void subscribe(String agentId, Consumer<AgentMessage> consumer) {
        consumers.computeIfAbsent(agentId, k -> new CopyOnWriteArrayList<>())
                .add(consumer);
    }
}
```

---

### 第 95 块：DefaultErrorPolicy

#### 类介绍

**设计动机**：默认错误处理策略。模型异常默认 RETRY（最多3次），工具异常默认 RETRY（最多2次），熔断器状态默认 CLOSED。

**包路径**：lyclaw-engine → lyjew.com.lyclaw.error.impl

**类型**：类

```java
package lyjew.com.lyclaw.error.impl;

import lyjew.com.lyclaw.context.ChatContext;
import lyjew.com.lyclaw.error.ErrorPolicy;
import lyjew.com.lyclaw.error.RetryConfig;
import lyjew.com.lyclaw.exception.ModelException;
import lyjew.com.lyclaw.model.ChatRequest;
import lyjew.com.lyclaw.model.ToolCall;
import lyjew.com.lyclaw.tool.ToolErrorAction;

import org.springframework.stereotype.Component;

/**
 * 默认错误处理策略 —— 模型异常 RETRY（最多3次），工具异常默认 ABORT，熔断器 CLOSED。
 *
 * <p><b>设计动机</b>：错误处理是引擎稳定性的保障。
 * DefaultErrorPolicy 采用"尽量重试，最坏降级"的原则：
 * <ul>
 *   <li>模型调用失败：可能是网络抖动，允许重试 3 次</li>
 *   <li>工具执行失败：可能是参数问题，默认 ABORT</li>
 *   <li>超过重试上限：记录日志，抛出异常让上层处理</li>
 * </ul>
 * </p>
 *
 * @since 1.0
 * @author LyClaw Team
 * @see ErrorPolicy
 * @see RetryConfig
 */
@Component
public class DefaultErrorPolicy implements ErrorPolicy {

    /** 模型调用最大重试次数 */
    private static final int MODEL_MAX_RETRIES = 3;

    /** 工具执行最大重试次数 */
    private static final int TOOL_MAX_RETRIES = 2;

    /** 重试基础延迟（ms） */
    private static final long BASE_DELAY_MS = 1000;

    @Override
    public ToolErrorAction onModelError(ModelException exception,
                                        ChatContext context,
                                        ChatRequest request) {
        return ToolErrorAction.RETRY;
    }

    @Override
    public ToolErrorAction onToolError(ToolCall toolCall,
                                       Exception exception,
                                       int retryCount) {
        if (retryCount < TOOL_MAX_RETRIES) {
            return ToolErrorAction.RETRY;
        }
        return ToolErrorAction.ABORT;
    }

    @Override
    public RetryConfig getRetryConfig() {
        return RetryConfig.exponential(MODEL_MAX_RETRIES, BASE_DELAY_MS);
    }

    @Override
    public String getCircuitBreakerState() {
        return "CLOSED";
    }
}
```

---

### 第 96 块：DefaultSecurityManager

#### 类介绍

**设计动机**：默认安全管理器。始终返回审批通过（granted(NONE)），不做实际安全检查。作为兜底实现使用。

**包路径**：lyclaw-engine → lyjew.com.lyclaw.security.impl

**类型**：类

```java
package lyjew.com.lyclaw.security.impl;

import lyjew.com.lyclaw.context.ChatContext;
import lyjew.com.lyclaw.security.ApprovalResult;
import lyjew.com.lyclaw.security.SandboxLevel;
import lyjew.com.lyclaw.security.SecurityManager;

import java.util.List;
import org.springframework.stereotype.Component;

/**
 * 默认安全管理器 —— 始终返回审批通过（NONE 级别），不做安全检查。
 *
 * <p><b>作为兜底使用</b>：在不需要安全功能的场景下使用。
 * 当应用需要实际的安全策略时，实现 SecurityManager 接口并 @Component
 * 替换此默认实现。</p>
 *
 * @since 1.0
 * @author LyClaw Team
 * @see SecurityManager
 * @see ApprovalResult
 */
@Component
public class DefaultSecurityManager implements SecurityManager {

    @Override
    public ApprovalResult approve(ChatContext context, String action) {
        // 始终返回审批通过，沙箱级别 NONE
        return ApprovalResult.granted(SandboxLevel.NONE);
    }

    @Override
    public void revoke(String sessionId) {
        // 空操作 —— 默认实现不跟踪已审批的会话
    }

    @Override
    public boolean checkPermission(String userId, String action) {
        // 默认允许所有操作
        return true;
    }

    @Override
    public List<String> getEffectivePolicies() {
        // 默认没有生效安全策略
        return List.of("default-permissive");
    }
}
```

---

### 第 97 块：DefaultTaskPlanner

#### 类介绍

**设计动机**：默认任务规划器。使用贪心策略将 Agent 请求拆解为有序的 TaskNode 列表。简单场景下按顺序逐个执行。

**包路径**：lyclaw-engine → lyjew.com.lyclaw.task.impl

**类型**：类

```java
package lyjew.com.lyclaw.task.impl;

import lyjew.com.lyclaw.context.ChatContext;
import lyjew.com.lyclaw.dto.AgentResult;
import lyjew.com.lyclaw.task.TaskNode;
import lyjew.com.lyclaw.task.TaskPlan;
import lyjew.com.lyclaw.task.TaskPlanner;

import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * TaskPlan 的默认实现 —— 内部类，仅在此规划器内部使用。
 *
 * <p>持有节点列表和依赖映射，通过 TaskNode 的 dependencies 字段判断就绪状态。</p>
 */
class DefaultTaskPlan implements TaskPlan {

    private final List<TaskNode> nodes;
    private final long estimatedCompletionTime;

    DefaultTaskPlan(List<TaskNode> nodes) {
        this.nodes = nodes;
        this.estimatedCompletionTime = nodes.size() * 1000L; // 每个节点估算 1s
    }

    @Override
    public List<TaskNode> getNodes() {
        return nodes;
    }

    @Override
    public List<String> getDependencies(String nodeId) {
        for (TaskNode node : nodes) {
            if (node.getNodeId().equals(nodeId)) {
                return node.getDependencies();
            }
        }
        return List.of();
    }

    @Override
    public long getEstimatedCompletionTime() {
        return estimatedCompletionTime;
    }

    @Override
    public boolean isReady() {
        // 所有节点都就绪才算就绪
        return !nodes.isEmpty();
    }
}

/**
 * 默认任务规划器 —— 贪心策略：按顺序拆解 Agent 请求为 TaskNode 列表。
 *
 * <p><b>设计动机</b>：TaskPlanner 是 Agent 的"大脑"，决定了如何将用户请求
 * 拆解为可执行的任务节点。默认实现采用简单策略——按顺序逐个执行。
 * 复杂场景可替换为更智能的规划器（如基于 DAG 的并行规划）。</p>
 *
 * @since 1.0
 * @author LyClaw Team
 * @see TaskPlanner
 * @see TaskPlan
 */
@Component
public class DefaultTaskPlanner implements TaskPlanner {

    @Override
    public TaskPlan plan(ChatContext context) {
        // 贪心策略：将请求拆解为按顺序执行的任务节点
        List<TaskNode> nodes = new ArrayList<>();

        // 1. 分析请求类型
        String userMessage = context.getRequest().getLastUserMessage();

        // 2. 创建根任务节点 —— 依赖关系在构造器中通过 List<String> 传入
        TaskNode root = new TaskNode("root", "ANALYZE", userMessage,
                List.of(), List.of(), 5000L);
        nodes.add(root);

        // 3. 创建子任务节点，依赖 root
        TaskNode execute = new TaskNode("execute", "EXECUTE", userMessage,
                List.of(), List.of(root.getNodeId()), 10000L);
        nodes.add(execute);

        // 4. 通过 DefaultTaskPlan 内部类创建 TaskPlan
        return new DefaultTaskPlan(nodes);
    }

    @Override
    public TaskPlan optimize(AgentResult result) {
        return null;
    }
}
```

---

### 第 98 块：DefaultTaskLedger

#### 类介绍

**设计动机**：默认任务账本。记录所有任务的执行记录，支持按 taskId 查询和查看最新记录。

**包路径**：lyclaw-engine → lyjew.com.lyclaw.task.impl

**类型**：类

```java
package lyjew.com.lyclaw.task.impl;

import lyjew.com.lyclaw.task.TaskLedger;
import lyjew.com.lyclaw.task.TaskRecord;
import lyjew.com.lyclaw.task.TaskResult;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * 默认任务账本 —— 基于内存的任务执行记录存储。
 *
 * <p><b>设计动机</b>：TaskLedger 是任务执行的"审计日志"。
 * 每次任务执行后都会写入一条 TaskRecord，包含执行时间、状态、结果等信息。
 * 默认实现使用内存存储（重启丢失），生产环境可替换为数据库存储。</p>
 *
 * @since 1.0
 * @author LyClaw Team
 * @see TaskLedger
 */
@Component
public class DefaultTaskLedger implements TaskLedger {

    /**
     * 任务记录存储 —— taskId → TaskRecord 列表（按时间倒序排列）。
     *
     * <p>每个 taskId 可能有多次执行记录（如重试），所有记录按时间倒序存储。</p>
     */
    private final ConcurrentHashMap<String, List<TaskRecord>> records = new ConcurrentHashMap<>();

    /**
     * 添加一条任务执行记录。
     *
     * @param record 任务执行记录
     */
    @Override
    public void addRecord(TaskRecord record) {
        records.compute(record.getTaskId(), (key, list) -> {
            if (list == null) {
                // 首次执行 —— 创建新列表
                List<TaskRecord> newList = new ArrayList<>();
                newList.add(record);
                return newList;
            }
            // 已有记录 —— 追加到列表末尾
            list.add(record);
            return list;
        });
    }

    /**
     * 获取指定 taskId 的所有执行记录。
     *
     * @param taskId 任务 ID
     * @return 执行记录列表（按执行时间倒序），无记录时返回空列表
     */
    @Override
    public List<TaskRecord> getRecords(String taskId) {
        List<TaskRecord> list = records.get(taskId);
        if (list == null) {
            return Collections.emptyList();
        }
        // 按执行时间倒序排列
        List<TaskRecord> sorted = new ArrayList<>(list);
        sorted.sort((a, b) -> b.getStartedAt().compareTo(a.getStartedAt()));
        return Collections.unmodifiableList(sorted);
    }

    /**
     * 获取指定 taskId 的最新一条执行记录。
     *
     * @param taskId 任务 ID
     * @return 最新记录，无记录时返回空
     */
    @Override
    public Optional<TaskRecord> getLatestRecord(String taskId) {
        List<TaskRecord> list = records.get(taskId);
        if (list == null || list.isEmpty()) {
            return Optional.empty();
        }
        // 返回最后一条（最新添加的）
        return Optional.of(list.get(list.size() - 1));
    }

    /**
     * 获取所有任务的执行记录（按时间倒序）。
     *
     * @return 所有任务记录列表
     */
    @Override
    public List<TaskRecord> getAllTasks() {
        return records.values().stream()
                .flatMap(List::stream)
                .sorted((a, b) -> b.getStartedAt().compareTo(a.getStartedAt()))
                .collect(Collectors.toList());
    }
}
```

---

### 第 99 块：DefaultSessionTransaction

#### 类介绍

**设计动机**：默认事务管理器。通过快照模式实现事务：begin() 时创建上下文快照，rollback() 时恢复快照，commit() 时应用变更。

**包路径**：lyclaw-engine → lyjew.com.lyclaw.transaction.impl

**类型**：类

```java
package lyjew.com.lyclaw.transaction.impl;

import lyjew.com.lyclaw.context.ChatContext;
import lyjew.com.lyclaw.transaction.*;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * 默认事务管理器 —— 基于快照的事务实现。
 *
 * <p><b>事务流程</b>：
 * <pre>
 * begin(sessionId) → 创建 TransactionContext，记录开始时间
 *     createSnapshot(context) → 捕获当前状态为 List<SessionUpdate>
 * commit(sessionId) → 标记状态为 COMMITTED
 * rollback(sessionId) → 恢复快照，标记状态为 ROLLED_BACK
 * </pre>
 * </p>
 *
 * <p><b>设计动机</b>：每次 AI 对话可能产生多个变更（追加消息、写入记忆、修改配置），
 * 这些变更需要作为一个整体要么全部成功要么全部回滚。
 * 事务管理器确保变更的原子性，避免部分变更造成的状态不一致。</p>
 *
 * @since 1.0
 * @author LyClaw Team
 * @see SessionTransaction
 * @see TransactionContext
 */
@Component
public class DefaultSessionTransaction implements SessionTransaction {

    /**
     * 当前活跃的事务上下文映射 —— sessionId → TransactionContext。
     */
    private final ConcurrentHashMap<String, TransactionContext> activeTransactions = new ConcurrentHashMap<>();

    @Override
    public void begin(String sessionId, String userId) {
        // TransactionContext 构造器：(transactionId, sessionId, contextSnapshot)
        // 第三个参数是 String（快照摘要），不是 List<SessionUpdate>
        TransactionContext context = new TransactionContext(
                UUID.randomUUID().toString(),
                sessionId,
                "snapshot: messages=" + 0
        );
        activeTransactions.put(sessionId, context);
    }

    @Override
    public boolean commit(String sessionId) {
        TransactionContext context = activeTransactions.get(sessionId);
        if (context == null) return false;
        context.setStatus(TransactionContext.STATUS_COMMITTED);
        activeTransactions.remove(sessionId);
        return true;
    }

    @Override
    public boolean rollback(String sessionId) {
        TransactionContext context = activeTransactions.get(sessionId);
        if (context == null) return false;
        context.setStatus(TransactionContext.STATUS_ROLLED_BACK);
        activeTransactions.remove(sessionId);
        return true;
    }

    @Override
    public String getStatus(String sessionId) {
        TransactionContext context = activeTransactions.get(sessionId);
        if (context == null) return "NONE";
        return context.getStatus();
    }

    @Override
    public List<SessionUpdate> createSnapshot(String sessionId, ChatContext context) {
        // SessionUpdate 构造器：(sessionId, updateType, oldValue, newValue, operator, timestamp)
        // oldValue/newValue 是 String（JSON），不能传 List<Message>
        List<SessionUpdate> updates = new ArrayList<>();
        int msgCount = context.getRequest().getMessages().size();
        updates.add(new SessionUpdate(
                sessionId,
                "MESSAGE_SNAPSHOT",
                "[]",
                "{\"count\":" + msgCount + "}",
                "system",
                Instant.now()
        ));
        return updates;
    }
}
```

> **第十部分 Tool / Skill / Memory 具体实现 完成（共 22 个文件）**

---

# 第十一部分：配置与自动装配

> **设计意图**：配置类和自动装配类依赖所有其他组件完成装配。放在最后让读者理解所有组件后才看到如何拼装成一个整体模块。

## 实现文件清单

| 序号 | 文件 | 包 | 类型 | 说明 |
|------|------|-----|------|------|
| 100 | EngineProperties.java | config | 类 | 引擎配置属性类 |
| 101 | EngineAutoConfiguration.java | config | 类 | 引擎自动装配 |

---

### 第 100 块：EngineProperties

#### 类介绍

**设计动机**：引擎配置属性类。通过 `@ConfigurationProperties(prefix = "lyclaw.engine")` 绑定 application.yml 中的配置项，所有引擎组件通过注入 EngineProperties 获取配置。

**包路径**：lyclaw-engine → lyjew.com.lyclaw.config

**类型**：类

```java
package lyjew.com.lyclaw.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 引擎配置属性类 —— 绑定 application.yml 中 lyclaw.engine.* 配置项。
 *
 * <p>所有引擎组件通过注入 EngineProperties 获取配置，
 * 不需要各自从 Environment 手动读取。配置变更只需改 yml 文件，
 * 不需要改代码。</p>
 *
 * <p><b>配置示例</b>：
 * <pre>
 * lyclaw:
 *   engine:
 *     data-dir: ./LyClaw
 *     default-provider: minimax
 *     enabled: true
 *     pipeline:
 *       timeout: 30000
 *       max-tool-rounds: 10
 * </pre>
 * </p>
 *
 * @since 1.0
 * @author LyClaw Team
 */
@Component
@ConfigurationProperties(prefix = "lyclaw.engine")
public class EngineProperties {

    /** 数据目录 —— 记忆、会话、日志等文件的存储根目录，默认 ./LyClaw */
    private String dataDir = "./LyClaw";

    /** 默认模型厂商 —— ToolCallLoopStage 通过 ModelProvider.getAdapter(defaultProvider) 获取适配器 */
    private String defaultProvider = "minimax";

    /** 引擎是否启用 —— false 时 EngineSelector 返回 fallback 结果，不执行完整管道 */
    private boolean enabled = true;

    /** 管道相关配置 */
    private final Pipeline pipeline = new Pipeline();

    public String getDataDir() { return dataDir; }
    public void setDataDir(String dataDir) { this.dataDir = dataDir; }

    public String getDefaultProvider() { return defaultProvider; }
    public void setDefaultProvider(String defaultProvider) { this.defaultProvider = defaultProvider; }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public Pipeline getPipeline() { return pipeline; }

    /**
     * 管道配置 —— 嵌套静态类，对应 lyclaw.engine.pipeline.*。
     */
    public static class Pipeline {

        /** 管道执行超时（毫秒），默认 30 秒 */
        private long timeout = 30000L;

        /** 最大工具调用轮次，默认 10 */
        private int maxToolRounds = 10;

        public long getTimeout() { return timeout; }
        public void setTimeout(long timeout) { this.timeout = timeout; }

        public int getMaxToolRounds() { return maxToolRounds; }
        public void setMaxToolRounds(int maxToolRounds) { this.maxToolRounds = maxToolRounds; }
    }
}
```

---

### 第 101 块：EngineAutoConfiguration

#### 类介绍

**设计动机**：引擎自动装配。Spring Boot 启动时自动配置 engine 层所有 Bean，不需要用户手动声明 `@Import`。某些底层接口（如 ToolRegistry、PipelineBuilder）通过 `@ConditionalOnMissingBean` 确保用户可替换。

**包路径**：lyclaw-engine → lyjew.com.lyclaw.config

**类型**：类

```java
package lyjew.com.lyclaw.config;

import lyjew.com.lyclaw.context.impl.ContextBuildStage;
import lyjew.com.lyclaw.context.impl.FullWindowContextBuilder;
import lyjew.com.lyclaw.engine.DefaultEngine;
import lyjew.com.lyclaw.engine.EngineSelector;
import lyjew.com.lyclaw.event.impl.InMemoryEventBus;
import lyjew.com.lyclaw.memory.impl.FileMemoryManager;
import lyjew.com.lyclaw.pipeline.PipelineBuilder;
import lyjew.com.lyclaw.pipeline.impl.InterceptorStage;
import lyjew.com.lyclaw.pipeline.impl.MetricsStage;
import lyjew.com.lyclaw.pipeline.impl.ResponseBuildStage;
import lyjew.com.lyclaw.pipeline.impl.ToolCallLoopStage;
import lyjew.com.lyclaw.tool.impl.DefaultToolRegistry;
import lyjew.com.lyclaw.tool.impl.ToolCallLoop;
import lyjew.com.lyclaw.provider.ModelProvider;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;

/**
 * 引擎自动装配类 —— Spring Boot 启动时自动配置 engine 层所有 Bean。
 *
 * <p>通过 @ComponentScan 扫描 lyjew.com.lyclaw 包下所有 @Component 类，
 * 同时通过 @Bean 方法显式声明需要特殊配置的 Bean。</p>
 *
 * <p><b>启用方式</b>：在 spring.factories 中配置即可：
 * <pre>
 * org.springframework.boot.autoconfigure.EnableAutoConfiguration=\
 *   lyjew.com.lyclaw.config.EngineAutoConfiguration
 * </pre>
 * </p>
 *
 * @since 1.0
 * @author LyClaw Team
 */
@Configuration
@ComponentScan(basePackages = "lyjew.com.lyclaw")
public class EngineAutoConfiguration {

    private final EngineProperties properties;

    public EngineAutoConfiguration(EngineProperties properties) {
        this.properties = properties;
    }

    /**
     * ToolCallLoop —— 工具调用循环模板方法。
     * 传入的 ModelProvider 由 DefaultModelProvider 兜底。
     */
    @Bean
    @ConditionalOnMissingBean
    public ToolCallLoop toolCallLoop(ModelProvider modelProvider,
                                     DefaultToolRegistry toolRegistry) {
        return new ToolCallLoop(modelProvider, toolRegistry, null);
    }

    /**
     * PipelineBuilder —— 管道构建器。
     * PipelineBuilder 只有无参构造器，通过 addStage() 链式添加。
     */
    @Bean
    @ConditionalOnMissingBean
    public PipelineBuilder pipelineBuilder(ContextBuildStage ctxStage,
                                           InterceptorStage interceptorStage,
                                           ToolCallLoopStage toolStage,
                                           MetricsStage metricsStage,
                                           ResponseBuildStage respStage) {
        PipelineBuilder builder = new PipelineBuilder();
        builder.addStage(ctxStage);
        builder.addStage(interceptorStage);
        builder.addStage(toolStage);
        builder.addStage(metricsStage);
        builder.addStage(respStage);
        return builder;
    }

    // EngineSelector 已通过 @Component + @PostConstruct 自动注册，此处不再 @Bean
}
```

> **第十一部分 配置与自动装配 完成（共 2 个文件）**

---

### 第 102 块：DefaultModelProvider

#### 类介绍

**设计动机**：ModelProvider 的默认实现。`getConfiguredAdapter()` 返回 null 并抛异常提示用户在 adapter 模块配置具体模型。这样 engine 层编译和启动都不会报错，只有在调用模型时才会提示配置。

**包路径**：lyclaw-engine → lyjew.com.lyclaw.provider.impl

**类型**：类

```java
package lyjew.com.lyclaw.provider.impl;

import lyjew.com.lyclaw.adapter.ModelAdapter;
import lyjew.com.lyclaw.provider.ModelProvider;

import java.util.Collections;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * ModelProvider 的默认实现 —— 无 adapter 模块时的兜底。
 *
 * <p><b>设计动机</b>：ModelProvider 接口定义在 engine 层，但实现类应该在
 * lyclaw-adapter 模块中（依赖具体的 ModelAdapter）。然而如果 engine 层没有
 * 默认实现，Spring 启动时 ToolCallLoopStage 注入 ModelProvider 会报
 * "找不到 Bean" 错误。</p>
 *
 * <p>DefaultModelProvider 作为兜底实现，getConfiguredAdapter() 抛出清晰异常，
 * 提示用户需要在 adapter 模块提供有效的 ModelProvider 实现。
 * 当 adapter 模块的 ModelProvider 实现被 @Component 扫描到后，
 * DefaultModelProvider 不会被注入（Spring 多候选时需要 @Primary 解决）。
 * 建议在 adapter 模块的实现上加 @Primary。</p>
 *
 * @since 1.0
 * @author LyClaw Team
 * @see ModelProvider
 */
@Component
public class DefaultModelProvider implements ModelProvider {

    @Override
    public ModelAdapter getAdapter(String provider) {
        throw new UnsupportedOperationException(
                "默认 ModelProvider 不提供具体适配器。请在 lyclaw-adapter 模块中"
                + "实现 ModelProvider 接口并注册为 @Component，"
                + "或在 application.yml 中配置 lyclaw.engine.default-provider。");
    }

    @Override
    public String getDefaultProvider() {
        return "未配置";
    }

    @Override
    public ModelAdapter getConfiguredAdapter() {
        throw new UnsupportedOperationException(
                "默认 ModelProvider 不提供具体适配器。请在 lyclaw-adapter 模块中"
                + "实现 ModelProvider 接口并注册为 @Component，"
                + "或在 application.yml 中配置 lyclaw.engine.default-provider。");
    }

    @Override
    public Set<String> listProviders() {
        return Collections.emptySet();
    }

    @Override
    public void refresh() {
        // 默认实现不做任何事
    }
}
```

### 第 103 块：ModelProvider 的 Web 层实现（lyclaw-web 模块）

> **位置说明**：这个类放在 web 模块。web 模块同时依赖 engine 和 adapter，能引用两边的类，没有循环依赖问题。

#### 类介绍

**设计动机**：将 lyclaw-adapter 模块的 ModelAdapterFactory 适配为 lyclaw-engine 模块的 ModelProvider 接口。这样 ToolCallLoopStage 注入 ModelProvider 时，Spring 会注入这个真正的实现（而不是 DefaultModelProvider 兜底）。

**包路径**：lyclaw-web → lyjew.com.lyclaw.web.provider

**类型**：类

```java
package lyjew.com.lyclaw.web.provider;

import lyjew.com.lyclaw.adapter.ModelAdapter;
import lyjew.com.lyclaw.adapter.factory.ModelAdapterFactory;
import lyjew.com.lyclaw.model.ModelConfig;
import lyjew.com.lyclaw.provider.ModelProvider;
import lyjew.com.lyclaw.storage.ConfigStorage;

import java.util.Set;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

/**
 * ModelProvider 的适配器实现 —— 将 ModelAdapterFactory 适配为 ModelProvider 接口。
 *
 * <p>加了 @Primary，Spring 在多个 ModelProvider 候选 Bean 中优先注入此实现。</p>
 *
 * <p><b>设计动机</b>：ModelProvider 接口定义在 lyclaw-engine 模块，
 * 但具体实现需要依赖 lyclaw-adapter 模块的 ModelAdapterFactory 和
 * lyclaw-storage 模块的 ConfigStorage。这三个模块都被 lyclaw-web 依赖，
 * 所以放在 web 模块没有循环依赖问题。</p>
 *
 * <p><b>工作流程</b>：
 * <ol>
 *   <li>ToolCallLoopStage 调用 modelProvider.getConfiguredAdapter()</li>
 *   <li>ModelProviderImpl 从 ConfigStorage 读取 provider 的 ModelConfig</li>
 *   <li>调用 adapterFactory.getConfiguredAdapter(config) 完成配置</li>
 *   <li>返回已配置好的 ModelAdapter 实例</li>
 * </ol>
 * </p>
 *
 * @since 1.0
 * @author LyClaw Team
 * @see ModelProvider
 * @see ModelAdapterFactory
 * @see ConfigStorage
 */
@Primary
@Component
public class ModelProviderImpl implements ModelProvider {

    private final ModelAdapterFactory adapterFactory;
    private final ConfigStorage configStorage;

    public ModelProviderImpl(ModelAdapterFactory adapterFactory,
                             ConfigStorage configStorage) {
        this.adapterFactory = adapterFactory;
        this.configStorage = configStorage;
    }

    @Override
    public ModelAdapter getAdapter(String provider) {
        return adapterFactory.getAdapter(provider);
    }

    @Override
    public String getDefaultProvider() {
        return "minimax";
    }

    @Override
    public ModelAdapter getConfiguredAdapter() {
        // 1. 获取默认厂商的配置
        String provider = getDefaultProvider();
        // 2. 从 ConfigStorage 读取配置（name 是 provider 名）
        ModelConfig config = configStorage.get(provider)
                .orElseThrow(() -> new RuntimeException(
                        "未找到 [" + provider + "] 的模型配置，"
                        + "请先调用 ConfigStorage.save() 写入配置"));
        // 3. 通过工厂完成配置
        return adapterFactory.getConfiguredAdapter(config);
    }

    @Override
    public Set<String> listProviders() {
        return adapterFactory.listProviders();
    }

    @Override
    public void refresh() {
        adapterFactory.refresh();
    }
}
```

---

### 第 104 块：EngineIntegrationTest 集成测试类

> **位置说明**：这个测试类放在 lyclaw-web 模块的测试目录下。
> 包路径：`lyclaw-web/src/test/java/lyjew/com/lyclaw/engine/EngineIntegrationTest.java`

#### 类介绍

**设计动机**：AI 引擎层全链路集成测试。覆盖引擎注册、模型配置、单轮对话、多轮对话、流式输出、System Prompt、记忆功能等场景。

**包路径**：lyclaw-web → lyjew.com.lyclaw.engine

**类型**：测试类

**关键改动说明**：
- `testConfigureModels()` 中 `name("minimax")` 和 `name("deepseek-openai")` 与 `ConfigStorage.extractId()` 返回的 key 一致
- `ModelProviderImpl.getConfiguredAdapter()` 通过 `configStorage.get("minimax")` 读取配置

```java
package lyjew.com.lyclaw.engine;

import lyjew.com.lyclaw.LyClawApplication;
import lyjew.com.lyclaw.adapter.ModelAdapter;
import lyjew.com.lyclaw.adapter.factory.ModelAdapterFactory;
import lyjew.com.lyclaw.engine.impl.DefaultEngine;
import lyjew.com.lyclaw.memory.MemoryManager;
import lyjew.com.lyclaw.model.*;
import lyjew.com.lyclaw.provider.ModelProvider;
import lyjew.com.lyclaw.storage.ConfigStorage;
import lyjew.com.lyclaw.storage.MemoryStorage;
import lyjew.com.lyclaw.storage.SessionStorage;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import reactor.core.publisher.Flux;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

@Slf4j
@SpringBootTest(classes = LyClawApplication.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class EngineIntegrationTest {

    private static final String MINIMAX_API_KEY = "sk-cp-f77oYRQUTcc0axeEVGq2KymcFp6mHEHhJD_uO1yUWEotBGhI90-zDwnJBAQIvlaoRzhL_vcrlVS_D4VqX2yFBkMNrTOcamt5_YscyumkPxJckbw1erj9vyI";
    private static final String MINIMAX_MODEL = "MiniMax-M2.7";
    private static final String MINIMAX_BASE_URL = "https://api.minimaxi.com";

    private static final String DEEPSEEK_API_KEY = "sk-b1da578246114c2383616f49b5651f1d";
    private static final String DEEPSEEK_MODEL = "deepseek-chat";
    private static final String DEEPSEEK_BASE_URL = "https://api.deepseek.com";

    @Autowired
    private DefaultEngine defaultEngine;

    @Autowired
    private EngineSelector engineSelector;

    @Autowired
    private ModelAdapterFactory adapterFactory;

    @Autowired
    private ModelProvider modelProvider;

    @Autowired
    private SessionStorage sessionStorage;

    @Autowired
    private MemoryStorage memoryStorage;

    @Autowired
    private ConfigStorage configStorage;

    @Autowired
    private MemoryManager memoryManager;

    private static String minimaxSessionId;
    private static String deepseekSessionId;
    private static String memorySessionId;
    private static final List<String> createdSessions = new ArrayList<>();
    private static final List<String> createdMemories = new ArrayList<>();

    @BeforeAll
    static void globalSetUp() {
        log.info("\n");
        log.info("╔══════════════════════════════════════════════════════════════════════════╗");
        log.info("║ AI 引擎层集成测试开始 ║");
        log.info("╚══════════════════════════════════════════════════════════════════════════╝");
    }

    @AfterAll
    static void globalTearDown() {
        log.info("╔══════════════════════════════════════════════════════════════════════════╗");
        log.info("║ AI 引擎层集成测试结束 ║");
        log.info("╚══════════════════════════════════════════════════════════════════════════╝");
    }

    // ─── 第1组：引擎基础功能 ──────────────────────────────────

    @Test
    @Order(1)
    @DisplayName("【基础】1.1 引擎注册和选择验证")
    void testEngineRegistration() {
        log.info("📋 测试：引擎注册和选择验证");

        List<Engine> engines = engineSelector.getEngines();
        assertFalse(engines.isEmpty(), "应该至少有一个引擎注册");

        assertEquals("default", defaultEngine.getName());
        assertNotNull(defaultEngine.getMetadata());

        EngineMetadata metadata = defaultEngine.getMetadata();
        log.info(" 引擎: {} v{}", metadata.getName(), metadata.getVersion());
        log.info(" 能力: {}", metadata.getCapabilities());

        // supports() 验证
        ChatRequest testRequest = ChatRequest.builder()
                .messages(List.of(createMessage("user", "测试")))
                .build();
        assertTrue(defaultEngine.supports(testRequest));

        // 引擎选择器验证
        Engine selected = engineSelector.select(testRequest);
        assertNotNull(selected);
        assertEquals("default", selected.getName());

        log.info("✅ 引擎注册验证通过");
    }

    @Test
    @Order(2)
    @DisplayName("【基础】1.2 模型配置写入存储层")
    void testConfigureModels() {
        log.info("📋 测试：模型配置写入存储层");

        // ⚠️ name 必须与 ModelProviderImpl.getDefaultProvider() 返回值一致！
        ModelConfig minimaxConfig = ModelConfig.builder()
                .id("cfg-minimax-engine-test")
                .name("minimax")
                .provider("minimax")
                .apiKey(MINIMAX_API_KEY)
                .model(MINIMAX_MODEL)
                .baseUrl(MINIMAX_BASE_URL)
                .enabled(true)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
        configStorage.save(minimaxConfig);

        ModelConfig deepseekConfig = ModelConfig.builder()
                .id("cfg-deepseek-engine-test")
                .name("deepseek-openai")
                .provider("deepseek-openai")
                .apiKey(DEEPSEEK_API_KEY)
                .model(DEEPSEEK_MODEL)
                .baseUrl(DEEPSEEK_BASE_URL)
                .enabled(true)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
        configStorage.save(deepseekConfig);

        assertTrue(configStorage.exists("minimax"));
        assertTrue(configStorage.exists("deepseek-openai"));
        assertTrue(adapterFactory.hasProvider("minimax"));
        assertTrue(adapterFactory.hasProvider("deepseek-openai"));

        log.info(" 已注册适配器: {}", adapterFactory.listProviders());
        log.info("✅ 模型配置写入验证通过");
    }

    @Test
    @Order(3)
    @DisplayName("【基础】1.3 模型连接验证")
    void testModelConnectivity() {
        log.info("📋 测试：模型连接验证");

        ModelAdapter minimaxAdapter = adapterFactory.getConfiguredAdapter(
                configStorage.get("minimax").get());
        assertTrue(minimaxAdapter.isConfigured());
        log.info(" MiniMax 连接: {}", minimaxAdapter.validate() ? "✅" : "❌");

        ModelAdapter deepseekAdapter = adapterFactory.getConfiguredAdapter(
                configStorage.get("deepseek-openai").get());
        assertTrue(deepseekAdapter.isConfigured());
        log.info(" DeepSeek 连接: {}", deepseekAdapter.validate() ? "✅" : "❌");

        log.info("✅ 模型连接验证完成");
    }

    // ─── 第2组：单模型对话 ──────────────────────────────────────

    @Test
    @Order(4)
    @DisplayName("【对话】2.1 MiniMax 简单问候")
    void testMinimaxSimpleChat() throws Exception {
        log.info("📋 测试：MiniMax 简单问候");

        minimaxSessionId = UUID.randomUUID().toString();
        createdSessions.add(minimaxSessionId);

        Session session = Session.builder()
                .id(minimaxSessionId).sessionId(minimaxSessionId)
                .name("MiniMax 引擎测试").model("minimax")
                .messages(new ArrayList<>())
                .createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now())
                .build();
        sessionStorage.save(session);

        ChatRequest request = ChatRequest.builder()
                .sessionId(minimaxSessionId)
                .messages(List.of(createMessage("user", "你好！请用一句话介绍你自己")))
                .temperature(0.7).maxTokens(200)
                .build();

        String response = executeSync(defaultEngine, request);
        assertNotNull(response);
        assertFalse(response.isEmpty());

        log.info(" 📥 响应: {}", truncate(response, 150));
        log.info(" ✅ 对话成功，{} 字符", response.length());

        Optional<Session> updated = sessionStorage.get(minimaxSessionId);
        assertTrue(updated.isPresent());
        log.info(" 📊 会话消息数: {}", updated.get().getMessages().size());
        log.info("✅ MiniMax 简单问候测试通过");
    }

    @Test
    @Order(5)
    @DisplayName("【对话】2.2 DeepSeek 简单问候")
    void testDeepseekSimpleChat() throws Exception {
        log.info("📋 测试：DeepSeek 简单问候");

        deepseekSessionId = UUID.randomUUID().toString();
        createdSessions.add(deepseekSessionId);

        Session session = Session.builder()
                .id(deepseekSessionId).sessionId(deepseekSessionId)
                .name("DeepSeek 引擎测试").model("deepseek-openai")
                .messages(new ArrayList<>())
                .createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now())
                .build();
        sessionStorage.save(session);

        ChatRequest request = ChatRequest.builder()
                .sessionId(deepseekSessionId)
                .messages(List.of(createMessage("user", "Hello! Please introduce yourself in one sentence.")))
                .temperature(0.7).maxTokens(200)
                .build();

        String response = executeSync(defaultEngine, request);
        assertNotNull(response);
        assertFalse(response.isEmpty());

        log.info(" 📥 响应: {}", truncate(response, 150));
        log.info(" ✅ 对话成功，{} 字符", response.length());
        log.info("✅ DeepSeek 简单问候测试通过");
    }

    // ─── 第3组：System Prompt ─────────────────────────────────────

    @Test
    @Order(6)
    @DisplayName("【对话】3.1 MiniMax System Prompt")
    void testMinimaxSystemPrompt() throws Exception {
        log.info("📋 测试：MiniMax System Prompt");

        String systemPrompt = "你是一个名叫'小爪'的AI助手，你非常喜欢猫咪，说话时会带'喵~'。";

        ChatRequest request = ChatRequest.builder()
                .sessionId(minimaxSessionId).systemPrompt(systemPrompt)
                .messages(List.of(createMessage("user", "你是谁？你喜欢什么动物？")))
                .temperature(0.8).maxTokens(300)
                .build();

        String response = executeSync(defaultEngine, request);
        boolean hasCat = response.contains("猫") || response.contains("喵") || response.contains("小爪");
        log.info(" 角色扮演体现: {}", hasCat ? "✅" : "⚠️");
        log.info("✅ MiniMax System Prompt 测试通过");
    }

    @Test
    @Order(7)
    @DisplayName("【对话】3.2 DeepSeek System Prompt")
    void testDeepseekSystemPrompt() throws Exception {
        log.info("📋 测试：DeepSeek System Prompt");
        Thread.sleep(2000);

        ChatRequest request = ChatRequest.builder()
                .sessionId(deepseekSessionId)
                .systemPrompt("You are a helpful Python expert. Always recommend Python first.")
                .messages(List.of(createMessage("user", "What language for AI development?")))
                .temperature(0.5).maxTokens(300)
                .build();

        String response = executeSync(defaultEngine, request);
        boolean mentionsPython = response.toLowerCase().contains("python");
        log.info(" 推荐Python: {}", mentionsPython ? "✅" : "⚠️");
        log.info("✅ DeepSeek System Prompt 测试通过");
    }

    // ─── 第4组：多轮对话 ───────────────────────────────────────

    @Test
    @Order(8)
    @DisplayName("【对话】4.1 MiniMax 多轮对话")
    void testMinimaxMultiTurn() throws Exception {
        log.info("📋 测试：MiniMax 多轮对话");

        String sid = createSession("MiniMax 多轮对话");

        String resp1 = executeSync(defaultEngine, ChatRequest.builder()
                .sessionId(sid)
                .messages(List.of(createMessage("user", "请记住：我最喜欢的编程语言是 Java，我的家乡是河南郑州")))
                .temperature(0.7).maxTokens(200).build());
        log.info(" 📥 第1轮: {}", truncate(resp1, 100));
        Thread.sleep(1000);

        Session session = sessionStorage.get(sid).get();
        List<Message> messages = new ArrayList<>(session.getMessages());
        messages.add(createMessage("user", "根据之前的对话，我喜欢的编程语言是什么？我的家乡在哪里？"));

        String resp2 = executeSync(defaultEngine, ChatRequest.builder()
                .sessionId(sid).messages(messages).temperature(0.7).maxTokens(300).build());
        log.info(" 📥 第2轮: {}", truncate(resp2, 200));

        boolean remembersJava = resp2.contains("Java") || resp2.contains("java");
        boolean remembersZhengzhou = resp2.contains("郑州") || resp2.contains("河南");
        log.info(" 记住 Java: {}  记住 郑州: {}", remembersJava ? "✅" : "❌", remembersZhengzhou ? "✅" : "❌");
        assertTrue(remembersJava || remembersZhengzhou);
        log.info("✅ MiniMax 多轮对话测试通过");
    }

    @Test
    @Order(9)
    @DisplayName("【对话】4.2 DeepSeek 多轮对话")
    void testDeepseekMultiTurn() throws Exception {
        log.info("📋 测试：DeepSeek 多轮对话");
        Thread.sleep(2000);

        String sid = createSession("DeepSeek 多轮对话");

        String resp1 = executeSync(defaultEngine, ChatRequest.builder()
                .sessionId(sid)
                .messages(List.of(createMessage("user", "Remember: my favorite framework is Spring Boot, and I develop on Ubuntu.")))
                .temperature(0.7).maxTokens(200).build());
        log.info(" 📥 第1轮: {}", truncate(resp1, 100));
        Thread.sleep(1000);

        Session session = sessionStorage.get(sid).get();
        List<Message> messages = new ArrayList<>(session.getMessages());
        messages.add(createMessage("user", "What framework do I like and what OS do I use?"));

        String resp2 = executeSync(defaultEngine, ChatRequest.builder()
                .sessionId(sid).messages(messages).temperature(0.7).maxTokens(300).build());
        log.info(" 📥 第2轮: {}", truncate(resp2, 200));

        boolean remembersSpring = resp2.toLowerCase().contains("spring");
        boolean remembersUbuntu = resp2.toLowerCase().contains("ubuntu");
        log.info(" 记住 Spring: {}  记住 Ubuntu: {}", remembersSpring ? "✅" : "❌", remembersUbuntu ? "✅" : "❌");
        assertTrue(remembersSpring || remembersUbuntu);
        log.info("✅ DeepSeek 多轮对话测试通过");
    }

    // ─── 第5组：知识问答 ────────────────────────────────────────

    @Test
    @Order(10)
    @DisplayName("【对话】5.1 MiniMax 知识问答")
    void testMinimaxKnowledgeQA() throws Exception {
        log.info("📋 测试：MiniMax 知识问答");

        String response = executeSync(defaultEngine, ChatRequest.builder()
                .sessionId(minimaxSessionId)
                .messages(List.of(createMessage("user", "请简单介绍一下 Java 中的 HashMap 实现原理，包括数据结构和扩容机制")))
                .temperature(0.5).maxTokens(800).build());
        log.info(" 📥 {} 字符", response.length());
        assertTrue(response.length() > 100);
        log.info("✅ MiniMax 知识问答测试通过");
    }

    @Test
    @Order(11)
    @DisplayName("【对话】5.2 DeepSeek 知识问答")
    void testDeepseekKnowledgeQA() throws Exception {
        log.info("📋 测试：DeepSeek 知识问答");
        Thread.sleep(2000);

        String response = executeSync(defaultEngine, ChatRequest.builder()
                .sessionId(deepseekSessionId)
                .messages(List.of(createMessage("user", "Explain the CAP theorem in distributed systems with examples.")))
                .temperature(0.5).maxTokens(800).build());
        log.info(" 📥 {} 字符", response.length());
        assertTrue(response.length() > 100);
        log.info("✅ DeepSeek 知识问答测试通过");
    }

    // ─── 第6组：流式输出 ────────────────────────────────────────

    @Test
    @Order(12)
    @DisplayName("【流式】6.1 MiniMax 流式输出")
    void testMinimaxStreaming() throws Exception {
        log.info("📋 测试：MiniMax 流式输出");

        Flux<String> flux = defaultEngine.execute(ChatRequest.builder()
                .sessionId(minimaxSessionId)
                .messages(List.of(createMessage("user", "用三句话介绍 Spring Boot")))
                .temperature(0.7).maxTokens(200).build());

        StringBuilder full = new StringBuilder();
        CountDownLatch latch = new CountDownLatch(1);
        flux.doOnNext(full::append)
                .doOnComplete(latch::countDown)
                .doOnError(e -> latch.countDown())
                .subscribe();
        latch.await(30, TimeUnit.SECONDS);
        assertFalse(full.toString().isEmpty());
        log.info(" 📥 {} 字符, 流式输出 ✅", full.length());
        log.info("✅ MiniMax 流式输出测试通过");
    }

    @Test
    @Order(13)
    @DisplayName("【流式】6.2 DeepSeek 流式输出")
    void testDeepseekStreaming() throws Exception {
        log.info("📋 测试：DeepSeek 流式输出");
        Thread.sleep(2000);

        Flux<String> flux = defaultEngine.execute(ChatRequest.builder()
                .sessionId(deepseekSessionId)
                .messages(List.of(createMessage("user", "Explain RESTful API in 3 sentences.")))
                .temperature(0.7).maxTokens(200).build());

        StringBuilder full = new StringBuilder();
        CountDownLatch latch = new CountDownLatch(1);
        flux.doOnNext(full::append)
                .doOnComplete(latch::countDown)
                .doOnError(e -> latch.countDown())
                .subscribe();
        latch.await(30, TimeUnit.SECONDS);
        assertFalse(full.toString().isEmpty());
        log.info(" 📥 {} 字符, 流式输出 ✅", full.length());
        log.info("✅ DeepSeek 流式输出测试通过");
    }

    // ─── 第7组：记忆功能 ────────────────────────────────────────

    @Test
    @Order(14)
    @DisplayName("【记忆】7.1 记忆写入和恢复")
    void testMemoryPersistence() throws Exception {
        log.info("📋 测试：记忆写入和恢复");

        String memId = "engine-memory-" + UUID.randomUUID().toString().substring(0, 8);
        createdMemories.add(memId);
        memorySessionId = UUID.randomUUID().toString();
        createdSessions.add(memorySessionId);

        String memoryContent = "## 用户偏好\n- 用户名：海坤\n- 项目：LyClaw AI 网关";
        Memory memory = Memory.builder()
                .id(memId).title("用户偏好 - 引擎测试")
                .content(memoryContent).enabled(true)
                .tags(List.of("偏好", "项目信息"))
                .createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now())
                .build();
        memoryStorage.save(memory);
        log.info(" ✅ 记忆已写入: {}", memId);
        assertTrue(memoryStorage.exists(memId));

        Session session = Session.builder().id(memorySessionId).sessionId(memorySessionId)
                .name("记忆恢复测试").model("minimax").messages(new ArrayList<>())
                .createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now()).build();
        sessionStorage.save(session);

        String response = executeSync(defaultEngine, ChatRequest.builder()
                .sessionId(memorySessionId)
                .systemPrompt("以下是之前记住的用户信息，请基于这些信息回答问题：\n" + memoryContent)
                .messages(List.of(createMessage("user", "根据你的记忆，我的名字叫什么？我在开发什么项目？")))
                .temperature(0.5).maxTokens(300).build());

        boolean remembersName = response.contains("海坤");
        boolean remembersProject = response.contains("LyClaw");
        log.info(" 记住用户名: {}  记住项目名: {}", remembersName ? "✅" : "❌", remembersProject ? "✅" : "❌");
        assertTrue(remembersName);
        log.info("✅ 记忆功能测试通过");
    }

    // ─── 第8组：综合验证 ────────────────────────────────────────

    @Test
    @Order(15)
    @DisplayName("【验证】8.1 会话完整性验证")
    void testSessionIntegrity() {
        log.info("📋 测试：会话完整性验证");

        for (String sid : createdSessions) {
            sessionStorage.get(sid).ifPresent(s ->
                    log.info("  {}: {} 条消息", s.getName(), s.getMessages().size()));
        }
        log.info("✅ 会话完整性验证通过");
    }

    @Test
    @Order(16)
    @DisplayName("【验证】8.2 适配器状态验证")
    void testAdapterStatus() {
        log.info("📋 测试：适配器状态验证");
        Set<String> providers = adapterFactory.listProviders();
        log.info(" 已注册适配器 ({} 个): {}", providers.size(), providers);
        assertTrue(providers.contains("minimax"));
        assertTrue(providers.contains("deepseek-openai"));
        log.info("✅ 适配器状态验证通过");
    }

    // ─── 辅助方法 ─────────────────────────────────────────────────────

    private Message createMessage(String role, String content) {
        return Message.builder()
                .id("msg-" + UUID.randomUUID().toString().substring(0, 8))
                .role(role).content(content)
                .createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now())
                .build();
    }

    private String createSession(String name) {
        String sid = UUID.randomUUID().toString();
        createdSessions.add(sid);
        sessionStorage.save(Session.builder().id(sid).sessionId(sid).name(name)
                .model("minimax").messages(new ArrayList<>())
                .createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now()).build());
        return sid;
    }

    private String executeSync(Engine engine, ChatRequest request) throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        StringBuilder result = new StringBuilder();
        AtomicReference<Throwable> error = new AtomicReference<>();

        engine.execute(request)
                .doOnNext(result::append)
                .doOnComplete(latch::countDown)
                .doOnError(e -> { error.set(e); latch.countDown(); })
                .subscribe();

        if (!latch.await(30, TimeUnit.SECONDS)) throw new RuntimeException("超时");
        if (error.get() != null) throw new RuntimeException(error.get());
        return result.toString();
    }

    private String truncate(String text, int maxLen) {
        if (text == null) return "null";
        return text.length() <= maxLen ? text : text.substring(0, maxLen) + "...（共" + text.length() + "字符）";
    }
}
```

---

### 第 105 块：ModelProviderImpl 修复

> **文件路径**：`lyclaw-web/src/main/java/lyjew/com/lyclaw/provider/ModelProviderImpl.java`
> **已有文件**：有，需要替换

#### 修复内容（3 项）

| 原代码 | 问题 | 修复后 |
|--------|------|--------|
| `getDefaultProvider()` 返回写死的 `"minimax"` | 所有 DeepSeek 测试实际走 MiniMax | 从 ConfigStorage 读取第一个启用配置 |
| `getConfiguredAdapter()` 调 `getAdapter()` | 未注入 API Key，`isConfigured()` 为 false | 调 `getConfiguredAdapter(ModelConfig)` |
| `getConfiguredAdapter()` 缺参数重载 | ToolCallLoopStage 只能用默认 provider | 新增 `getConfiguredAdapter(String)` |

#### 完整代码

```java
package lyjew.com.lyclaw.provider;

import lyjew.com.lyclaw.adapter.ModelAdapter;
import lyjew.com.lyclaw.adapter.factory.ModelAdapterFactory;
import lyjew.com.lyclaw.model.ModelConfig;
import lyjew.com.lyclaw.provider.ModelProvider;
import lyjew.com.lyclaw.storage.ConfigStorage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.Set;

/**
 * ModelProvider 的实现 —— 从 ConfigStorage 读取模型配置，适配为 ModelProvider 接口。
 *
 * <p><b>修复说明</b>：
 * <ul>
 *   <li>v1 → v2：getDefaultProvider() 改为从 ConfigStorage 读取，不再写死 "minimax"</li>
 *   <li>v1 → v2：getConfiguredAdapter() 改用 ConfigStorage + getConfiguredAdapter(ModelConfig)，注入 API Key</li>
 *   <li>v2 新增：getConfiguredAdapter(String providerName) 支持按 provider 名称选择不同模型</li>
 * </ul>
 * </p>
 *
 * <p><b>工作流程</b>：
 * <ol>
 *   <li>ToolCallLoopStage 调用 getConfiguredAdapter() 或 getConfiguredAdapter(providerName)</li>
 *   <li>从 ConfigStorage 读取对应 provider 的 ModelConfig（name=providerName）</li>
 *   <li>调用 adapterFactory.getConfiguredAdapter(config) → 内部执行 configure() 注入 API Key</li>
 *   <li>返回已配置的 ModelAdapter</li>
 * </ol>
 * </p>
 *
 * @since 1.0
 * @author LyClaw Team
 */
@Primary
@Component
public class ModelProviderImpl implements ModelProvider {

    private static final Logger log = LoggerFactory.getLogger(ModelProviderImpl.class);

    private final ModelAdapterFactory adapterFactory;
    private final ConfigStorage configStorage;

    public ModelProviderImpl(ModelAdapterFactory adapterFactory, ConfigStorage configStorage) {
        this.adapterFactory = adapterFactory;
        this.configStorage = configStorage;
    }

    @Override
    public String getDefaultProvider() {
        // 从 ConfigStorage 中查找第一个 enabled=true 的配置
        return configStorage.getAll().stream()
                .filter(ModelConfig::isEnabled)
                .findFirst()
                .map(ModelConfig::getName)
                .orElse(null);
    }

    @Override
    public ModelAdapter getAdapter(String provider) {
        return adapterFactory.getAdapter(provider);
    }

    @Override
    public ModelAdapter getConfiguredAdapter() {
        String defaultProvider = getDefaultProvider();
        if (defaultProvider == null) {
            throw new IllegalStateException(
                    "没有可用的模型配置。请先在 ConfigStorage 中保存模型配置");
        }
        return getConfiguredAdapter(defaultProvider);
    }

    /**
     * 根据 provider 名称获取已配置的适配器。
     * <p>从 ConfigStorage 读取 ModelConfig，调用 getConfiguredAdapter(config) 注入 API Key。</p>
     *
     * @param providerName provider 名称，如 "minimax"、"deepseek-openai"
     * @return 已配置的 ModelAdapter
     */
    public ModelAdapter getConfiguredAdapter(String providerName) {
        Optional<ModelConfig> configOpt = configStorage.get(providerName);
        if (configOpt.isEmpty()) {
            throw new IllegalStateException(
                    "未找到 provider=" + providerName + " 的配置");
        }
        ModelConfig config = configOpt.get();
        if (!config.isEnabled()) {
            throw new IllegalStateException(
                    "provider=" + providerName + " 的配置已禁用");
        }
        ModelAdapter adapter = adapterFactory.getConfiguredAdapter(config);
        log.info("已配置适配器: provider={}, model={}", config.getProvider(), config.getModel());
        return adapter;
    }

    @Override
    public Set<String> listProviders() {
        return adapterFactory.listProviders();
    }

    @Override
    public void refresh() {
        adapterFactory.refresh();
    }
}
```
