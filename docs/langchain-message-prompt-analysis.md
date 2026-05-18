# LangChain 消息与提示词系统深度分析

> 基于 langchain-core 1.x 源码（`libs/core/langchain_core/`）的深度分析
> 重点关注：`messages/`、`prompts/`、`language_models/` 三个核心模块

---

## 一、BaseMessage 消息体系

### 1.1 类继承结构

```
Serializable
  └── BaseMessage                    # 所有消息的抽象基类
        ├── HumanMessage             # 用户消息
        ├── AIMessage                # AI 回复消息（含 tool_calls）
        ├── SystemMessage            # 系统提示词
        ├── ToolMessage              # 工具执行结果（含 artifact）
        ├── FunctionMessage          # 旧版函数调用结果（v0 遗留）
        ├── ChatMessage              # 任意角色消息（通用容器）
        └── RemoveMessage            # 删除消息标记

BaseMessageChunk(BaseMessage)        # 流式消息片段
  ├── HumanMessageChunk
  ├── AIMessageChunk                 # 额外包含 tool_call_chunks
  ├── SystemMessageChunk
  ├── ToolMessageChunk
  ├── FunctionMessageChunk
  └── ChatMessageChunk
```

继承关系展示了 LangChain 如何通过 Chunk 子类双重继承实现流式支持。例如 `AIMessageChunk` 同时继承 `AIMessage` 和 `BaseMessageChunk`，前者提供 `tool_calls`、`usage_metadata` 等属性，后者提供 `__add__` 合并能力。

### 1.2 BaseMessage 核心字段

```python
class BaseMessage(Serializable):
    content: str | list[str | dict]   # 消息内容：字符串 或 内容块列表
    additional_kwargs: dict            # 保留字段，存放提供商的额外 payload
    response_metadata: dict            # 响应元数据：headers, logprobs, token计数, model名
    type: str                          # 消息类型标识（用于反序列化鉴别）
    name: str | None = None            # 可选的人类可读名称
    id: str | None = None             # 可选唯一标识符（由提供商/模型生成）
```

**关键设计特点：**

1. **`content` 的双重角色**：既可以是纯文本字符串（简单场景），也可以是 `ContentBlock` 字典列表（多模态/工具调用场景）。这种 "联合体" 设计在 v1.0 中被进一步规范化为 `content_blocks` 属性。

2. **`additional_kwargs` 的歧义**：该字段最初用于存放提供商特定的数据（如 OpenAI 的 `tool_calls` 原始 JSON）。LangChain 逐步将这些数据迁移到标准化字段（如 `ToolCall`），但仍在此保留原始数据用于兼容，并通过 `BlockTranslator` 进行解析。

3. **`response_metadata` 的关键标志位**：
   - `"model_provider"`：触发对应 BlockTranslator 的解析；若缺失，回退到 `BaseMessage.content_blocks` 公共解析。
   - `"output_version"`：`"v1"` 表示 content 已经是 `ContentBlock` 列表，可直接使用。

4. **`TextAccessor` 的兼容设计**：`.text` 属性既可作为属性访问又可作为方法调用（`.text()`）。`.text()` 方法在 v1.0.0 被标记为 deprecated，实际实现是返回一个继承自 `str` 的 `TextAccessor` 对象。

### 1.3 BaseMessageChunk —— 流式消息片段

```python
class BaseMessageChunk(BaseMessage):
    def __add__(self, other: Any) -> BaseMessageChunk:
        # content: merge_content() 合并
        # additional_kwargs: merge_dicts() 深度合并
        # response_metadata: merge_dicts() 深度合并
```

`BaseMessageChunk` 重写了 `__add__`，使多个流式 chunk 可以拼接为完整消息。合并规则：
- **content**：纯字符串直接拼接；列表逐元素合并（最后元素是字符串则拼接，否则追加新元素）
- **additional_kwargs / response_metadata**：字典深度合并
- **id**：保留第一个非空 id

`BaseMessage.__add__` 返回 `ChatPromptTemplate`（方便模板构建），而 `BaseMessageChunk.__add__` 返回同类型 chunk（支持流式拼接）。

---

## 二、各消息类型详解

### 2.1 HumanMessage（用户消息）

```python
class HumanMessage(BaseMessage):
    type: Literal["human"] = "human"
```

用户输入的消息。支持通过 `content` 传入字符串，或通过 `content_blocks` 传入多模态内容（图片、音频、视频等）。

### 2.2 AIMessage（AI 回复）

```python
class AIMessage(BaseMessage):
    type: Literal["ai"] = "ai"
    tool_calls: list[ToolCall]           # 标准化的工具调用列表
    invalid_tool_calls: list[InvalidToolCall]  # 解析失败的工具调用
    usage_metadata: UsageMetadata | None  # 标准化 token 用量
```

**`tool_calls` 和 `invalid_tool_calls` 的分离**：
- `tool_calls`：成功解析的 JSON 参数 → `{"name": "foo", "args": {"a": 1}, "id": "123"}`
- `invalid_tool_calls`：解析失败的工具调用 → `{"name": "foo", "args": "{malformed", "error": "..."}`

**向后兼容机制（`_backwards_compat_tool_calls`）**：
当 `additional_kwargs` 中存在 `tool_calls` 但 `tool_calls/invalid_tool_calls/tool_call_chunks` 均未设置时，自动从 `additional_kwargs` 解析，这是处理 OpenAI 等旧格式的桥梁。

**`content_blocks` 的重写**：
1. 首先检查 `response_metadata["output_version"]` 是否为 `"v1"`
2. 然后查询 `model_provider` 对应的 BlockTranslator
3. 如果都没有，回退到 `BaseMessage.content_blocks` 的通用解析
4. 补充仅在 `tool_calls` 字段中的工具调用
5. 从 `additional_kwargs` 提取 reasoning_content（支持 Ollama/DeepSeek 等）

### 2.3 AIMessageChunk（流式 AI 回复）

额外的专属字段：
```python
class AIMessageChunk(AIMessage, BaseMessageChunk):
    tool_call_chunks: list[ToolCallChunk]  # 增量工具调用片段
    chunk_position: Literal["last"] | None  # 标记是否为流的最后一个 chunk
```

**`__add__` 合并逻辑**：
调用 `add_ai_message_chunks()` 专有函数，额外处理：
- `tool_call_chunks`：按 `index` 合并，同名属性的字符串拼接
- `usage_metadata`：使用 `add_usage()` 进行 `input_tokens`/`output_tokens` 求和
- `id`：优先级排序选择（提供商 ID > `lc_run-*` > `lc_*`）
- `chunk_position`：任一子块为 `"last"` 则结果为 `"last"`

**`init_tool_calls` 验证器**：
当 `chunk_position="last"` 时，将 `tool_call_chunks` 解析为 `ToolCall` 对象，单个 chunk 的 `args` 通过 `parse_partial_json` 解析。在这个阶段，`tool_call_chunk` 的 type 会被更新为 `"tool_call"`（如果是 v1 输出）。

### 2.4 SystemMessage（系统提示词）

```python
class SystemMessage(BaseMessage):
    type: Literal["system"] = "system"
```

最简单的消息类型之一。用于设置 AI 的行为、角色和约束。在多轮对话中通常放在消息列表最前面。

### 2.5 ToolMessage（工具执行结果）

```python
class ToolMessage(BaseMessage, ToolOutputMixin):
    type: Literal["tool"] = "tool"
    tool_call_id: str              # 关联对应 ToolCall 的 id
    artifact: Any = None           # 不发送给模型的完整输出
    status: Literal["success", "error"] = "success"
```

**content 与 artifact 的分离**：
这是 ToolMessage 最核心的设计理念：
- **`content`**：发送给 LLM 的部分（通常是摘要或关键结果），会被格式化为文本传入下一轮对话
- **`artifact`**：完整的工具输出（可能包含大型二进制数据、图表、详细日志等），仅用于程序进一步处理，不发送给模型

```python
# 典型用法：只把摘要发给模型，完整结果保留在 artifact
tool_output = {
    "stdout": "From the graph we can see that the correlation between x and y is ...",
    "stderr": None,
    "artifacts": {"type": "image", "base64_data": "/9j/4gIcSU..."},
}
ToolMessage(
    content=tool_output["stdout"],    # 只给模型看摘要
    artifact=tool_output,             # 完整数据保留
    tool_call_id="call_Jja7J89XsjrOLA5r!MEOW!SL",
)
```

**`tool_call_id` 的作用**：关联请求和响应。当 AI 同时发起多个工具调用时（如 `get_weather` + `get_time`），每个 `ToolMessage` 通过 `tool_call_id` 精确对应到特定的 `ToolCall`。

**`ToolOutputMixin`**：如果一个自定义工具返回的对象不是 `ToolMessage` 或 `ToolOutputMixin` 子类，框架会自动将其转换为 `ToolMessage`（输出转字符串放入 content）。

**`_merge_status`**：两个 chunk 合并时，任一为 `"error"` 则结果为 `"error"`。

**`coerce_args`**：自动将非字符串/非列表的 content 转换为字符串，将 UUID/整数/浮点数的 tool_call_id 转换为字符串。

### 2.6 FunctionMessage（遗留类型）

```python
class FunctionMessage(BaseMessage):
    name: str
    type: Literal["function"] = "function"
```

这是 OpenAI 早期函数调用 API 的遗留类型。与 `ToolMessage` 的主要区别是没有 `tool_call_id`，因此无法支持并行工具调用。新代码应使用 `ToolMessage`。

### 2.7 ChatMessage（通用角色容器）

```python
class ChatMessage(BaseMessage):
    role: str                        # 任意角色字符串
    type: Literal["chat"] = "chat"
```

当需要自定义角色（超出 human/ai/system/tool 范围）时使用。例如某些模型支持 `"developer"` 角色。

### 2.8 RemoveMessage（消息删除标记）

```python
class RemoveMessage(BaseMessage):
    type: Literal["remove"] = "remove"
    def __init__(self, id: str, **kwargs): ...
```

不包含 content（content 固定为空字符串），仅通过 `id` 指定要删除的消息。在 LangGraph 等有状态框架中用于管理对话历史。

---

## 三、ContentBlock 系统

### 3.1 设计动机

不同 LLM 提供商使用互不兼容的 API 格式。ContentBlock 系统提供统一的、提供商无关的数据结构来表示 LLM 的输入输出。一条消息被建模为一组有序的 ContentBlock 列表，自然支持文本、图片、工具调用等内容的交错排列。

### 3.2 完整的 ContentBlock 类型树

```
ContentBlock (Union)
  ├── TextContentBlock               # 文本内容
  │     type: "text"
  │     text: str
  │     annotations: list[Annotation] (可选: Citation | NonStandardAnnotation)
  │     index: int | str (可选, 流式场景)
  │     extras: dict (可选, 提供商特有字段)
  │
  ├── ReasoningContentBlock          # 推理/思考内容
  │     type: "reasoning"
  │     reasoning: str
  │
  ├── ToolCall                       # 工具调用请求
  │     type: "tool_call"
  │     name: str
  │     args: dict[str, Any]
  │     id: str | None
  │
  ├── ToolCallChunk                  # 流式工具调用片段
  │     type: "tool_call_chunk"
  │     name: str | None
  │     args: str | None (JSON子串)
  │     id: str | None
  │     index: int | str (合并依据)
  │
  ├── InvalidToolCall                # 解析失败的工具调用
  │     type: "invalid_tool_call"
  │     error: str | None
  │
  ├── ServerToolCall                 # 服务端工具调用
  │     type: "server_tool_call"
  │     (代码执行、网页搜索等由服务端执行的工具)
  │
  ├── ServerToolCallChunk            # 流式服务端工具调用片段
  ├── ServerToolResult               # 服务端工具执行结果
  │     tool_call_id: str
  │     status: "success" | "error"
  │
  ├── DataContentBlock (Union)
  │   ├── ImageContentBlock          # type: "image"
  │   │     url / base64 / file_id
  │   │     mime_type (base64必需)
  │   ├── VideoContentBlock          # type: "video"
  │   ├── AudioContentBlock          # type: "audio"
  │   ├── PlainTextContentBlock      # type: "text-plain"
  │   │     text / url / base64
  │   │     (区别于 TextContentBlock: 这是文档数据，不是对话文本)
  │   └── FileContentBlock           # type: "file"
  │         (PDF、Word 等非特定媒体文件)
  │
  └── NonStandardContentBlock        # 未标准化的提供商格式
        type: "non_standard"
        value: dict[str, Any]
```

### 3.3 关键设计细节

**1. TypedDict 而非类**：所有 ContentBlock 都是 `TypedDict`（继承字典），这使其可以直接序列化为 JSON，与 HTTP API 天然兼容。

**2. `extras` 字段**：每个标准块都支持 `extras: NotRequired[dict[str, Any]]`，允许传递提供商特定的额外字段（如 Google 的 `thought_signature`）而不破坏标准结构。

**3. Block ID 机制**：每个块都可选 `id`，LangChain 通过 `ensure_id()` 为缺失 id 的块自动生成 `"lc_" + UUID4` 格式的 ID。

**4. `index` 字段**：在流式场景中，每个块的 `index` 用于标识其在聚合响应中的位置，确保 ToolCallChunk 等能正确合并。

**5. 工厂函数**：每种块都提供了 `create_*_block()` 工厂函数，自动设置 `type` 和 `id`，使用更简单：
```python
from langchain_core.messages.content import create_text_block, create_image_block

blocks = [
    create_text_block("What is shown in this image?"),
    create_image_block(url="https://example.com/photo.png", mime_type="image/png"),
]
```

**6. `Annotation` / `Citation`**：`TextContentBlock` 可以选择性地携带 `annotations`，用于标注文本的来源引用（start_index / end_index 指向响应文本而非源文档）。

### 3.4 ContentBlock 的解析流程（`BaseMessage.content_blocks` 属性）

```
1. 如果 content 是字符串:
   → 转为 [{"type": "text", "text": content}]

2. 遍历 content 列表中每个元素:
   - 字符串 → {"type": "text", "text": 字符串}
   - dict，type 不在 KNOWN_BLOCK_TYPES → {"type": "non_standard", "value": dict}
   - dict，有 "source_type" → 标记为 v0 块，放入 non_standard
   - dict，type 在 KNOWN_BLOCK_TYPES → 直接作为 ContentBlock 使用

3. 多阶段解析 non_standard 块（每个阶段处理上一阶段剩余的 non_standard）:
   a. _convert_v0_multimodal_input_to_v1   (v0 格式 → v1 标准)
   b. _convert_to_v1_from_chat_completions_input (OpenAI Chat API → v1)
   c. _convert_to_v1_from_anthropic_input  (Anthropic → v1)
   d. _convert_to_v1_from_genai_input      (Google GenAI → v1)
   e. _convert_to_v1_from_converse_input   (AWS Bedrock Converse → v1)

4. 最终无法解析的保留为 NonStandardContentBlock
```

---

## 四、BlockTranslator —— 提供商翻译器

### 4.1 注册机制

```python
# 全局注册表
PROVIDER_TRANSLATORS: dict[str, dict[str, Callable]] = {}

def register_translator(provider, translate_content, translate_content_chunk): ...

# 自动注册（在 __init__.py 中调用 _register_translators()）
_register_openai_translator()
_register_anthropic_translator()
_register_bedrock_translator()
_register_bedrock_converse_translator()
_register_google_genai_translator()
_register_google_vertexai_translator()
_register_groq_translator()
```

每个提供商需要提供两个翻译函数：
- `translate_content`：将 AIMessage 的内容翻译为标准 ContentBlock 列表
- `translate_content_chunk`：将 AIMessageChunk 的内容翻译为标准 ContentBlock 列表

### 4.2 翻译器调用路径

```
AIMessage.content_blocks
  ├── response_metadata["output_version"] == "v1"
  │   → 直接返回 self.content (已经是标准块)
  │
  ├── response_metadata["model_provider"] 存在
  │   → get_translator(model_provider)
  │   → translator["translate_content"](self)  // 或 translate_content_chunk
  │   → 如果 NotImplementedError → 回退
  │
  └── 回退: BaseMessage.content_blocks (通用解析)
      ├── 补充仅在 tool_calls 字段中但未在 content 中的工具调用
      └── 从 additional_kwargs 提取 reasoning_content
```

### 4.3 第三方集成

外部包（如 `langchain-openai`）可以通过调用 `register_translator()` 注册自己的翻译器，无需修改 langchain-core。

---

## 五、消息工具函数

### 5.1 `convert_to_messages()`

将各种输入格式统一转换为 `list[BaseMessage]`。支持的输入格式：

| 输入格式 | 处理方式 |
|---------|---------|
| `PromptValue` | 调用 `.to_messages()` |
| `BaseMessage` | 直接使用 |
| `str` | 转为人 `HumanMessage` |
| `(type_str, content)` 元组 | 按 type 创建对应消息 |
| `(MessageClass, content)` 元组 | 使用指定类创建 |
| `{"role": "xxx", "content": "yyy"}` | 按 role 创建对应消息 |
| `list[...]` | 递归处理每个元素 |

### 5.2 `message_chunk_to_message()`

将 chunk 转为完整消息。利用 Python MRO：chunk 类的第一个父类总是对应的非 chunk 类。对于 `AIMessageChunk`，额外移除 `tool_call_chunks` 和 `chunk_position`。

### 5.3 `filter_messages()`

按条件过滤消息：
- **include/exclude by name**：按消息 name 过滤
- **include/exclude by type**：按消息 type（`"human"`, `"ai"` 等）过滤
- **include/exclude by id**：按消息 id 过滤
- **exclude_tool_calls**：`True` 排除所有工具相关消息；指定 id 列表则排除对应工具调用，并同步更新 AIMessage 的 tool_calls 和 content

### 5.4 `merge_message_runs()`

合并连续的**同类型**消息。例如两个连续的 `HumanMessage` 会被合并。`chunk_separator` 控制合并后的分隔符。

### 5.5 `trim_messages()`

裁剪消息以满足 token 限制：

```python
trim_messages(
    messages,
    max_tokens=4096,
    token_counter=model,          # 或 "approximate" / callable
    strategy="last",              # "first" 或 "last"
    allow_partial=False,          # 是否允许部分消息
    start_on="human",             # 确保第一条消息是 human
    include_system=True,          # 始终保留 SystemMessage
    end_on=("human", "tool"),     # 最后一条消息的类型约束
)
```

核心策略：
- `strategy="last"`：保留最近的对话，丢弃最早的
- `include_system=True`：永久保留系统消息（通常第一条）
- `start_on="human"`：确保裁剪后的第一条消息是用户消息（模型要求）

### 5.6 `get_buffer_string()`

将消息列表转为单个字符串，支持两种格式：
- `format="prefix"`（默认）：`"Human: xxx\nAI: yyy"`
- `format="xml"`：`<message type="human">xxx</message>`（XML 逸出保护）

---

## 六、Prompt 系统

### 6.1 类结构总览

```
RunnableSerializable
  └── BasePromptTemplate          # 所有 Prompt 的抽象基类
        ├── StringPromptTemplate  # 字符串模板
        │     └── PromptTemplate  # 具体实现（f-string/mustache/jinja2）
        │
        └── BaseChatPromptTemplate  # 对话 Prompt 模板（抽象）
              └── ChatPromptTemplate  # 具体实现

Serializable
  └── BaseMessagePromptTemplate
        ├── MessagesPlaceholder    # 动态消息插入
        ├── BaseStringMessagePromptTemplate
        │     └── ChatMessagePromptTemplate
        └── _StringImageMessagePromptTemplate
              ├── HumanMessagePromptTemplate
              ├── AIMessagePromptTemplate
              └── SystemMessagePromptTemplate
```

### 6.2 Prompt Pipeline：从模板到格式化消息

```
ChatPromptTemplate.invoke({"name": "Bob", "user_input": "Hi"})
  │
  ├── 1. _validate_input()
  │     检查模板所需的所有变量是否都已提供
  │
  ├── 2. _format_prompt_with_error_handling()
  │     2a. _merge_partial_and_user_variables()
  │         合并 partial_variables（预填充）和用户传入的变量
  │     2b. format_prompt() → format_messages()
  │         遍历 self.messages，对每个 message_template 调用 format_messages(**kwargs)
  │         - BaseMessage → 直接返回
  │         - BaseMessagePromptTemplate → 子类 format_messages()
  │         - BaseChatPromptTemplate → 子类 format_messages()
  │     2c. 返回 ChatPromptValue(messages=[...])
  │
  └── 3. ChatPromptValue
       ├── .to_messages() → list[BaseMessage]  (给 chat model)
       └── .to_string() → str                  (给 LLM)
```

### 6.3 ChatPromptTemplate —— 对话模板

```python
class ChatPromptTemplate(BaseChatPromptTemplate):
    messages: list[MessageLike]  # 消息模板或已构造的消息
```

**构造方式极其灵活**，单条消息可以用 5 种不同格式：

```python
# 1. BaseMessagePromptTemplate 实例
SystemMessagePromptTemplate.from_template("You are {role}")

# 2. BaseMessage 实例
HumanMessage(content="Hello!")

# 3. (type_str, template) 元组
("human", "{user_input}")
("system", "You are a helpful assistant.")

# 4. (MessageClass, template) 元组
(HumanMessagePromptTemplate, "{user_input}")

# 5. 字符串（简写为 human 消息）
"{user_input}"

# 全部混用示例：
ChatPromptTemplate([
    SystemMessage(content="You are an assistant."),
    ("human", "Hi, my name is {name}"),
    AIMessage(content="Hello {name}!"),
    ("human", "{question}"),
])
```

**内部转换（`_convert_to_message_template`）**：
```
dict{"role", "content"} → _create_template_from_message_type
tuple(type_str, template) → _create_template_from_message_type
    type in {"human", "user"}   → HumanMessagePromptTemplate.from_template
    type in {"ai", "assistant"} → AIMessagePromptTemplate.from_template
    type == "system"            → SystemMessagePromptTemplate.from_template
    type == "placeholder"       → MessagesPlaceholder
str → HumanMessagePromptTemplate.from_template (简写)
BaseMessage / BaseMessagePromptTemplate → 直接使用
```

**`__add__` 运算符**：
```python
# ChatPromptTemplate + ChatPromptTemplate → 合并 messages
# ChatPromptTemplate + MessageLike → 追加一条消息
# ChatPromptTemplate + str → 追加一条 HumanMessage
# BaseMessage.__add__ → 转为 ChatPromptTemplate 再合并
```

### 6.4 输入变量自动推断

`ChatPromptTemplate` 在初始化时自动扫描 `messages` 中的所有子模板，聚合 `input_variables`：

```python
for message in messages:
    if isinstance(message, MessagesPlaceholder) and message.optional:
        # 可选的 placeholder 被视为 partial_variable
        partial_vars[message.variable_name] = []
        optional_variables.add(message.variable_name)
    elif isinstance(message, (BaseChatPromptTemplate, BaseMessagePromptTemplate)):
        input_vars.update(message.input_variables)
```

这使得用户通常不需要显式声明 `input_variables`。

### 6.5 `partial()` 方法 —— 逐步填充

```python
template = ChatPromptTemplate.from_messages([
    ("system", "You are {role} named {name}."),
    ("human", "{input}"),
])

# 先填充 role
template2 = template.partial(role="assistant")
# 现在只需要 name 和 input 两个变量

# 再填充 name
template3 = template2.partial(name="Bob")
# 现在只需要 input 一个变量

template3.invoke({"input": "Hello"})
# → SystemMessage("You are an assistant named Bob.")
# → HumanMessage("Hello")
```

实现原理：`partial_variables` 在 `format_messages()` 执行时通过 `_merge_partial_and_user_variables()` 自动注入，并从 `input_variables` 中移除。

### 6.6 MessagesPlaceholder —— 动态消息插入

```python
class MessagesPlaceholder(BaseMessagePromptTemplate):
    variable_name: str
    optional: bool = False         # True 时可以不传（返回空列表）
    n_messages: int | None = None  # 限制保留最近的消息数

    def format_messages(self, **kwargs) -> list[BaseMessage]:
        value = kwargs.get(variable_name, []) if optional else kwargs[variable_name]
        value = convert_to_messages(value)  # 支持 tuple/list/dict 等格式
        if self.n_messages:
            value = value[-self.n_messages:]  # 只保留最近 n 条
        return value
```

**典型用法 —— 对话历史管理**：
```python
ChatPromptTemplate.from_messages([
    ("system", "You are a helpful assistant."),
    MessagesPlaceholder("history"),           # 对话历史
    ("human", "{question}"),                  # 当前问题
])
```

使用时：
```python
prompt.invoke({
    "history": [
        ("human", "What's 5 + 2?"),
        ("ai", "5 + 2 = 7"),
    ],
    "question": "Now multiply by 4",
})
```

**限制消息数**：
```python
MessagesPlaceholder("history", n_messages=5)  # 只保留最近 5 条
# 也可用作 "滑动窗口"，控制上下文长度
```

**optional 模式**：
```python
MessagesPlaceholder("context", optional=True)
# 首次对话时可以不传 context，自动返回空列表
```

---

## 七、消息流动：Agent、工具与 LLM 之间

### 7.1 `LanguageModelInput` → `_convert_input` → `PromptValue`

```python
LanguageModelInput = PromptValue | str | Sequence[MessageLikeRepresentation]

def _convert_input(self, model_input):
    if PromptValue → return model_input  # 已格式化
    if str         → return StringPromptValue(text=model_input)
    if Sequence    → return ChatPromptValue(messages=convert_to_messages(model_input))
```

### 7.2 `invoke()` 的完整调用链

```
BaseChatModel.invoke(input)
  │
  ├── _convert_input(input) → PromptValue
  │     str → StringPromptValue
  │     list[MessageLike] → ChatPromptValue
  │
  ├── generate_prompt([prompt_value])
  │     │
  │     ├── generate([[messages]])  # 提取 PromptValue.to_messages()
  │     │     ├── _normalize_messages(messages)
  │     │     │   将 OpenAI/v0/v1 混合格式统一为 v1 ContentBlock
  │     │     ├── _generate_with_cache(messages, ...)
  │     │     │   └── _generate(messages, ...)  # 子类实现
  │     │     └── 返回 ChatResult
  │     │
  │     └── ChatResult.generations[0][0]
  │           → ChatGeneration(message=AIMessage(...))
  │
  └── 返回 AIMessage
```

### 7.3 `stream()` 的完整调用链

```
BaseChatModel.stream(input)
  │
  ├── _convert_input(input) → .to_messages()
  │
  ├── _normalize_messages(messages)  # 统一格式
  │
  ├── self._stream(input_messages, stop, **kwargs)  # 子类实现
  │     yield ChatGenerationChunk(message=AIMessageChunk(...))
  │
  ├── 每个 chunk 处理:
  │     - 设置 id (如果缺失则使用 run_id)
  │     - 设置 response_metadata
  │     - 如果 output_version=="v1": 将 content 替换为 content_blocks，设置 index
  │     - 更新 index (不同 block type 分别计数)
  │     - on_llm_new_token 回调
  │     - yield AIMessageChunk
  │
  ├── 最后 yield 一个 chunk_position="last" 的空 chunk
  │     (触发 AIMessageChunk.init_tool_calls 解析 tool_call_chunks → tool_calls)
  │
  └── 回调 on_llm_end
```

### 7.4 消息在 Agent 循环中的流动

```
用户输入 "查天气"
  │
  ▼
ChatPromptTemplate 格式化
  SystemMessage(...)
  HumanMessage("查天气")
  │
  ▼
model.invoke(messages)
  │ (OpenAI API 返回 tool_calls)
  ▼
AIMessage(
    content="",
    tool_calls=[ToolCall(name="get_weather", args={"city": "北京"}, id="call_1")]
)
  │
  ▼
Agent 执行工具
  result = get_weather(city="北京")
  │
  ▼
ToolMessage(
    content="北京今天晴，25°C",
    tool_call_id="call_1"
)
  │
  ▼
新的 messages = 原 messages + [AIMessage, ToolMessage]
  │
  ▼
model.invoke(messages)  # 第二轮调用
  │ (模型看到工具结果，生成最终回复)
  ▼
AIMessage(content="北京今天天气晴，气温25°C，适合出行。")
```

### 7.5 `_normalize_messages()` —— 输入标准化

在模型实际调用之前，`_normalize_messages()` 会被调用，统一处理输入消息中的 3 种格式：

1. **OpenAI Chat Completions 多模态格式**（`input_audio`, `file` 类型块）→ 转为 v1 标准 DataBlock
2. **LangChain v0 多模态格式**（带 `source_type` 字段的块）→ 转为 v1 标准 DataBlock
3. **v1 标准格式** → 直接通过（无需转换）

输入消息的副本采用懒拷贝策略：只有当确实需要修改时才创建副本，避免不必要的性能开销。

---

## 八、UsageMetadata —— Token 用量统计

```python
class UsageMetadata(TypedDict):
    input_tokens: int            # 输入（prompt）token 总数
    output_tokens: int           # 输出（completion）token 总数
    total_tokens: int            # 总 token 数
    input_token_details: NotRequired[InputTokenDetails]   # 输入 token 细分
    output_token_details: NotRequired[OutputTokenDetails] # 输出 token 细分

class InputTokenDetails(TypedDict, total=False):
    audio: int                   # 音频输入 token
    cache_creation: int          # 缓存创建 token（cache miss）
    cache_read: int              # 缓存读取 token（cache hit）

class OutputTokenDetails(TypedDict, total=False):
    audio: int                   # 音频输出 token
    reasoning: int               # 推理/思考 token（如 o1 模型）
```

**流式场景的用法**：
- `add_usage(left, right)`：累加两个 UsageMetadata（流式 chunk 合并）
- `subtract_usage(left, right)`：相减两个 UsageMetadata（差量计算，结果不会为负）

---

## 九、Summary 设计模式总结

| 维度 | 设计决策 | 理由 |
|------|---------|------|
| **消息基类** | Pydantic Serializable | 类型安全 + 序列化 + 可追踪 |
| **content 字段** | `str \| list\[str\|dict\]` 联合体 | 兼容简单文本和多模态 |
| **Chunk 模式** | 双重继承 + `__add__` 重写 | 流式拼接的优雅实现 |
| **工具调用** | `tool_calls` + `invalid_tool_calls` 分离 | 区分成功/失败的解析 |
| **工具结果** | `content` vs `artifact` 分离 | 控制发送给模型的信息量 |
| **ContentBlock** | TypedDict 而非类 | JSON 序列化友好，与 API 天然兼容 |
| **BlockTranslator** | 提供商注册表 + 回退解析 | 支持任意提供商扩展 |
| **Prompt 构造** | 多格式输入自动转换 | 最大灵活性的开发者体验 |
| **MessagesPlaceholder** | 可选 + 数量限制 | 灵活的对话历史管理 |
| **输入标准化** | `_normalize_messages` + 懒拷贝 | 向后兼容 + 性能优化 |
| **partial 变量** | 逐步填充 + callable 支持 | 支持模板复用和动态值 |

---

## 附录：关键源码文件索引

| 文件 | 内容 |
|------|------|
| `messages/base.py` | BaseMessage, BaseMessageChunk, TextAccessor, merge_content |
| `messages/content.py` | 全部 ContentBlock TypedDict 定义 + 工厂函数 |
| `messages/human.py` | HumanMessage, HumanMessageChunk |
| `messages/ai.py` | AIMessage, AIMessageChunk, UsageMetadata, add_usage, subtract_usage |
| `messages/system.py` | SystemMessage, SystemMessageChunk |
| `messages/tool.py` | ToolMessage, ToolMessageChunk, ToolCall, ToolCallChunk, default_tool_parser |
| `messages/function.py` | FunctionMessage (遗留类型) |
| `messages/chat.py` | ChatMessage (通用角色容器) |
| `messages/modifier.py` | RemoveMessage (消息删除标记) |
| `messages/utils.py` | convert_to_messages, filter_messages, trim_messages, merge_message_runs, get_buffer_string, message_chunk_to_message |
| `messages/block_translators/__init__.py` | PROVIDER_TRANSLATORS 注册表 + get_translator |
| `prompts/base.py` | BasePromptTemplate, format_document |
| `prompts/chat.py` | ChatPromptTemplate, MessagesPlaceholder, HumanMessagePromptTemplate, AIMessagePromptTemplate, SystemMessagePromptTemplate |
| `prompts/message.py` | BaseMessagePromptTemplate (抽象基类) |
| `language_models/chat_models.py` | BaseChatModel: invoke, stream, generate, _convert_input |
| `language_models/_utils.py` | _normalize_messages, is_openai_data_block |
| `prompt_values.py` | PromptValue, StringPromptValue, ChatPromptValue, ImageURL |
