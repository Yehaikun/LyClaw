# LangChain Agent 编排设计全景报告

> 基于 langchain 源码深度分析，覆盖 5 个子系统：Runnable 引擎、Tool 系统、消息/Prompt 系统、经典 Agent 模式、V1 中间件架构

## 目录

1. [整体架构全景图](#一整体架构全景图)
2. [Runnable 引擎：一切皆可组合](#二runnable-引擎一切皆可组合)
3. [Tool 系统：函数即服务](#三tool-系统函数即服务)
4. [消息系统：Agent 的语言](#四消息系统agent-的语言)
5. [经典 Agent 模式：从 ReAct 到 Tool Calling](#五经典-agent-模式从-react-到-tool-calling)
6. [V1 Agent 中间件：洋葱模型革命](#六v1-agent-中间件洋葱模型革命)
7. [跨系统设计模式](#七跨系统设计模式)
8. [技术演进与选型建议](#八技术演进与选型建议)

---

## 一、整体架构全景图

LangChain 的 Agent 编排系统由 5 个深度集成的子系统构成，它们形成一条完整的"LLM → Agent → Tool → 消息 → LLM"闭环管道：

```
                            ┌──────────────────────────┐
                            │   Prompt / 消息系统        │
                            │  ChatPromptTemplate      │
                            │  MessagesPlaceholder     │
                            │  ContentBlock            │
                            └──────────┬───────────────┘
                                       │ 构造 messages
                                       ▼
┌──────────────────────┐    ┌──────────────────────────┐
│  Agent 编排层         │    │   LLM 模型                │
│ ┌──────────────────┐ │    │   ChatOpenAI / ChatAnthropic
│ │ V1: create_agent │ │───▶│                           │
│ │  + 中间件管道     │ │    └──────────┬───────────────┘
│ │  + StateGraph    │ │               │ 返回 AIMessage(tool_calls)
│ ├──────────────────┤ │               ▼
│ │ Classic: AgentEx-│ │    ┌──────────────────────────┐
│ │ ecutor + Parser  │ │    │   Tool 系统               │
│ └──────────────────┘ │    │  BaseTool.run()          │
└──────────────────────┘    │  StructuredTool          │
                             │  @tool 装饰器             │
                             └──────────┬───────────────┘
                                        │ 返回 ToolMessage
                                        ▼
                             ┌──────────────────────────┐
                             │   Runnable 引擎 (底层)    │
                             │  LCEL 管道 (| 运算符)     │
                             │  RunnableSequence        │
                             │  RunnableParallel         │
                             │  回调 + 配置传播           │
                             └──────────────────────────┘
```

### 五层架构

| 层级 | 子系统 | 职责 | 核心文件位置 |
|------|--------|------|-------------|
| **编排层** | V1 中间件 / Classic Agent | Agent 循环、决策路由、中间件拦截 | `langchain/agents/` |
| **协议层** | Prompt / 消息 | LLM 通信格式、内容编码、提供商翻译 | `langchain_core/messages/`, `prompts/` |
| **能力层** | Tool 系统 | 函数→工具包装、参数验证、注入参数 | `langchain_core/tools/` |
| **执行层** | Runnable 引擎 | 可组合执行单元、并行/顺序/条件 | `langchain_core/runnables/` |
| **横切层** | Callback / Config | 可观测性、配置传播 | `langchain_core/callbacks/` |

### 数据流总览

一条典型的 Agent → Tool 调用链：

```
用户输入 (HumanMessage)
  │
  ├─ ChatPromptTemplate.format_messages()
  │    └─ 注入 system prompt + 历史消息
  │
  ├─ LLM.invoke(messages)
  │    └─ 返回 AIMessage(tool_calls=[...])
  │
  ├─ Agent 解析 AIMessage.tool_calls
  │    └─ 提取 (name, args, id)
  │
  ├─ BaseTool.run(tool_input)
  │    ├─ _parse_input() → Pydantic 验证
  │    ├─ _run() → 实际逻辑
  │    └─ _format_output() → ToolMessage
  │
  └─ ToolMessage 追加到消息列表 → 回到 LLM
```

---

## 二、Runnable 引擎：一切皆可组合

> 详细分析：`langchain-runnable-engine-analysis.md`（1215 行）

### 2.1 核心设计理念

LangChain 的 Runnable 引擎定义了一套统一的执行协议。所有组件——Agent、Tool、Prompt、LLM——都实现 `Runnable` 接口，使得它们可以通过 `|` (pipe) 运算符自由组合。

**六大核心方法**：

| 方法 | 用途 | 默认行为 |
|------|------|---------|
| `invoke(input)` | 同步单次执行 | — |
| `ainvoke(input)` | 异步单次执行 | 线程池中运行 `invoke` |
| `batch(inputs)` | 同步批量执行 | 循环调用 `invoke` |
| `abatch(inputs)` | 异步批量执行 | `asyncio.gather` |
| `stream(input)` | 同步流式执行 | 包装 `invoke` 为单元素迭代器 |
| `astream(input)` | 异步流式执行 | 包装 `ainvoke` 为单元素迭代器 |

**设计要点**：只需实现同步方法，异步方法自动获得默认实现。但子类可同时覆盖以获得真正的异步性能。线程池桥接通过 `run_in_executor` 实现，确保同步阻塞不阻塞事件循环。

### 2.2 LCEL：管道的威力

`|` 运算符是 LCEL（LangChain Expression Language）的基石：

```python
chain = prompt | llm | output_parser
```

`__or__` 不仅连接 Runnable，还会自动转换普通 Python 对象：
- `callable` → `RunnableLambda`
- `dict` → `RunnableParallel`
- `generator` → `RunnableGenerator`

这意味着 `prompt | llm | str_output_parser` 中的 `str_output_parser` 可以是一个普通 lambda，框架自动包装。这种"零摩擦"设计大幅降低了组合成本。

### 2.3 RunnableSequence：三段式顺序链

```python
class RunnableSequence(Runnable):
    first: Runnable          # 入口
    middle: list[Runnable]   # 中间层
    last: Runnable           # 出口
```

与简单的 `for` 循环不同，`RunnableSequence` 在整个执行链上管理 callback 生命周期，支持流式中继（`stream` 从第一个组件流到最后一个），且 `batch` 利用每步内部的批量优化。

### 2.4 RunnableParallel：声明式并行

```python
chain = RunnableParallel(
    context=retrieval_chain,
    question=RunnablePassthrough()
) | prompt | llm
```

并行执行的核心机制：
- **同步**：使用 `ContextThreadPoolExecutor`，自动携带 `RunnableConfig` 上下文
- **异步**：使用 `asyncio.gather`，真正的协程级并行
- **流式**：使用 `safetee` 复制输入流 + `FIRST_COMPLETED` 策略实现交错输出

`AddableDict` 累加机制允许多个并行分支的流式输出交织合并，先到先出。

### 2.5 配置系统：隐式传播

```python
class RunnableConfig(TypedDict, total=False):
    tags: list[str]
    metadata: dict
    callbacks: Callbacks
    configurable: dict
    recursion_limit: int
    max_concurrency: int
    run_name: str
    ...
```

配置通过 Python `ContextVar`（`var_child_runnable_config`）在调用链中隐式传播，无需显式透传。`ContextThreadPoolExecutor` 能跨线程保持此上下文——这是线程池中正确传播追踪信息的关键技术。

---

## 三、Tool 系统：函数即服务

> 详细分析：`langchain-tool-system-analysis.md`

### 3.1 双层类层次

```
RunnableSerializable
    └── BaseTool                    # 抽象基类
            ├── Tool                # 单字符串输入（向后兼容）
            └── StructuredTool      # 多结构化输入（推荐）
```

关键点：`BaseTool` 继承自 `RunnableSerializable`，意味着每个工具天生就是 Runnable，可直接参与 LCEL 管道。

### 3.2 三层 Schema 暴露

这是 Tool 系统最精妙的设计——信息隐藏原则在 AI 系统中的直接应用：

| 层级 | 方法 | 受众 | 内容 |
|------|------|------|------|
| 第一层 | `tool_call_schema` | LLM | 不含注入参数的简化 schema |
| 第二层 | `get_input_schema()` | 框架 | 完整 Pydantic 模型，含所有参数 |
| 第三层 | `args` | 渲染器 | JSON Schema properties 字典 |

LLM 看不到 `InjectedToolArg` 参数——它们只在运行时由框架注入。

### 3.3 注入参数体系

```python
@tool
def search(
    query: str,                                    # LLM 生成
    runtime: Annotated[ToolRuntime, InjectedToolArg] # 框架注入
) -> str: ...
```

`_is_injected_arg_type()` 函数通过检查类型的 `Annotated` 元数据或 `_DirectlyInjectedToolArg` 继承来识别注入参数。这解决了"某些参数由系统提供而非 LLM 决策"的难题——不污染 LLM 的接口，不增加 token 消耗，不引入 LLM 幻觉风险。

### 3.4 执行管道

`BaseTool.run()` 是一个经典的模板方法实现：

```
run()
  ├─ on_tool_start 回调
  ├─ _parse_input (Pydantic 验证)
  ├─ _run / _arun (子类实现)
  ├─ _format_output → ToolMessage
  └─ on_tool_end 回调
```

错误处理分三级：`ValidationError` → `handle_validation_error`，`ToolException` → `handle_tool_error`，其他异常直接抛出。

### 3.5 @tool 装饰器的 5 种模式

1. `@tool` — 裸装饰器，取函数名
2. `@tool("name")` — 命名装饰器
3. `@tool(return_direct=True)` — 带参数
4. `@tool("name", parse_docstring=True)` — 命名+参数
5. `tool("name", my_runnable)` — Runnable 转换

同一函数入口支持 5 种调用方式，通过参数类型/数量推断用户意图，极大降低使用门槛。

---

## 四、消息系统：Agent 的语言

> 详细分析：`langchain-message-prompt-analysis.md`（797 行）

### 4.1 消息层次

```
BaseMessage
├── HumanMessage          # 用户输入
├── AIMessage             # LLM 输出（含 tool_calls）
├── SystemMessage         # 系统提示
├── ToolMessage           # 工具执行结果
│   ├─ content: 给 LLM 看的摘要
│   └─ artifact: 给程序的完整数据
├── FunctionMessage       # 旧版函数调用结果
├── ChatMessage           # 通用消息（role 自由）
└── RemoveMessage         # 消息删除标记
```

每种消息都有对应的 `Chunk` 子类，用于流式场景。`AIMessageChunk.__add__` 实现了复杂的合并逻辑——累积 content、合并 tool_calls、在最后一个 chunk（`chunk_position="last"`）触发 tool_call 解析。

### 4.2 content 与 artifact 的分离

这是消息系统最巧妙的双轨设计：

- **content**：发送给 LLM 的内容。可以是摘要，可以截断。LLM 用它做下一步决策。
- **artifact**：工具执行的完整输出。对 LLM 不可见，但可以在下游的 callback、中间件、或用户界面中使用。

例如：一次数据库查询返回 10000 行。content 可能只包含"查询成功，返回 10000 行"，而 artifact 保存了完整的 DataFrame。这种分离避免了 token 浪费，同时保留了完整数据的可访问性。

### 4.3 ContentBlock 系统

消息内容不再是简单的字符串，而是结构化的 ContentBlock 列表：

```
ContentBlock (TypedDict, discriminator="type")
├── TextContentBlock          {"type": "text", "text": "..."}
├── ToolCallContentBlock      {"type": "tool_call", "name": ..., "args": ...}
├── ImageContentBlock         {"type": "image", "url": ...}
├── ReasoningContentBlock     {"type": "reasoning", "reasoning": ...}
├── AudioContentBlock         {"type": "audio", ...}
├── VideoContentBlock         {"type": "video", ...}
├── FileContentBlock          {"type": "file", ...}
├── PlainTextContentBlock     {"type": "text" (纯文本)}
├── NonStandardContentBlock   {"type": "..." (自定义)}
└── ServerToolContentBlock    {"type": "server_tool", ...}
```

使用 `TypedDict` 而非 Pydantic 模型——更轻量，兼容 `dict` 字面量，适合 LLM API 的 JSON 序列化场景。

### 4.4 BlockTranslator：提供商翻译器

不同 LLM 提供商（OpenAI、Anthropic、Google 等）对 ContentBlock 的表示不同。BlockTranslator 系统通过 `PROVIDER_TRANSLATORS` 注册表，在 LangChain 通用格式和各提供商专有格式之间桥接。

翻译优先级：`output_version` → `model_provider` → 回退。

内置 7 个提供商的翻译器，但设计上对扩展开放——新提供商只需注册翻译器即可，不改核心代码。

### 4.5 Prompt 系统

```python
prompt = ChatPromptTemplate.from_messages([
    ("system", "You are a helpful assistant."),
    MessagesPlaceholder("history"),
    ("human", "{input}"),
])
chain = prompt | llm | StrOutputParser()
```

从模板到最终消息的流水线：`invoke(input)` → `_validate_input` → `format_messages` → `ChatPromptValue` → 传给 LLM。

`MessagesPlaceholder` 的 `optional` 和 `n_messages` 参数提供了灵活的消息管理——可选插入历史、限制消息数量（滑动窗口）。

---

## 五、经典 Agent 模式：从 ReAct 到 Tool Calling

> 详细分析：`langchain-classic-agent-patterns-analysis.md`（1443 行）

### 5.1 AgentExecutor：经典的循环引擎

所有经典 Agent 模式的共同核心是 `AgentExecutor`：

```python
class AgentExecutor(Chain):
    agent: BaseSingleActionAgent | BaseMultiActionAgent
    tools: list[BaseTool]
    max_iterations: int = 15
    early_stopping_method: str = "force"
```

核心循环在 `_iter_next_step` 中：
1. 调用 Agent 的 `plan()` 方法获取下一步动作
2. 如果是 `AgentFinish` → 返回最终输出
3. 如果是 `AgentAction` → 执行对应工具，获取 observation
4. 将 (action, observation) 加入中间步骤
5. 循环回到步骤 1

### 5.2 六大经典模式对比

| 模式 | 输出格式 | 解析方式 | 工具参数 | 状态 |
|------|---------|---------|---------|------|
| **ReAct** | 文本 `Action: X\nAction Input: Y` | 正则 | 单字符串 | deprecated |
| **MRKL / Zero-Shot ReAct** | 文本 `Action: ...` | 正则 | 单字符串 | deprecated |
| **XML Agent** | XML 片段 | XML 解析 | 单字符串 | deprecated |
| **OpenAI Functions** | `additional_kwargs.function_call` | 结构化提取 | 多参数 | deprecated |
| **OpenAI Tools** | `tool_calls` 字段 | 结构化提取 | 多参数 | deprecated |
| **Tool Calling** | `AIMessage.tool_calls` | `ToolsAgentOutputParser` | 多参数 + 并行 | **current** |

### 5.3 输出解析器的三层进化

1. **纯文本正则**（ReAct/MRKL）：`re.search(r"Action:\s*(.+?)\nAction Input:\s*(.+)", text)` —— 脆弱，LLM 格式偏差即失败
2. **JSON blob 提取**（Structured Chat）：`re.search(r"```(?:json)?\s*(.*?)```", text)` 提取 JSON，`json.loads()` 解析 —— 更结构化，支持多参数
3. **结构化消息提取**（Tool Calling）：直接从 `AIMessage.tool_calls` 读取，零文本解析 —— 最可靠

`send_to_llm=True` 优雅降级：当解析失败时，将错误反馈回 LLM，让 LLM 自行修正。这是工程上务实的处理方式——不因解析失败而崩溃。

### 5.4 Tool Calling Agent：当前推荐的通用模式

```python
agent = create_tool_calling_agent(llm, tools, prompt)
```

核心特点：
- 直接从 `AIMessage.tool_calls` 读取，无需文本解析
- 支持多个工具并行调用（`MultiAction`）
- `tool_call_id` 精确匹配请求与结果
- 工具参数支持任意 JSON 结构

### 5.5 技术演进路线

```
ReAct (2022)
  → MRKL / Zero-Shot ReAct (2023)
    → Structured Chat (2023)
      → OpenAI Functions (2023)
        → OpenAI Tools (2024)
          → Tool Calling (2024) —— 当前推荐
            → V1 create_agent (2025) —— 新范式
```

每次演进都在减少对文本解析的依赖，增加对结构化输出的信任。这是整个行业的趋势——从"让 LLM 说人话"到"让 LLM 说机器可读的话"。

---

## 六、V1 Agent 中间件：洋葱模型革命

> 详细分析：`langchain-v1-agent-middleware-analysis.md`（1294 行）

### 6.1 V1 的架构飞跃

V1（`create_agent`）是对经典 AgentExecutor 的根本性重构：

| 维度 | Classic (AgentExecutor) | V1 (create_agent) |
|------|------------------------|-------------------|
| 底层引擎 | Chain (旧框架) | StateGraph (LangGraph) |
| 扩展方式 | 继承 + 覆写 | 中间件管道 |
| 工具调用 | 串行 | 并行 (Send API) |
| 输出格式 | 各模式不同 | 统一的结构化输出 |
| 状态管理 | 手动管理 | AgentState + Reducer |
| 流式支持 | 有限 | 完整的 astream_events |

### 6.2 StateGraph 驱动的 Agent

V1 Agent 编译为一个 `StateGraph`，其节点拓扑为：

```
start → call_model → [有 tool_call?] → tools → call_model → ...
                    └── [无] → END
```

关键设计：
- `add_messages` reducer：新消息追加而非覆盖，天然支持对话历史累积
- `EphemeralValue` channel：写入后读取一次自动清除，用于一次性信号（如 `jump_to`）
- 私有状态注解：`__private__` 前缀的状态字段不暴露给外部

### 6.3 中间件系统的洋葱模型

```python
class AgentMiddleware[StateT, ContextT]:
    def before_agent(self, state, runtime): ...
    def before_model(self, state, runtime): ...
    def wrap_model_call(self, request, handler): ...
    def after_model(self, state, runtime): ...
    def wrap_tool_call(self, request, handler): ...
    def before_tool(self, state, runtime): ...
    def after_tool(self, state, runtime): ...
    def after_agent(self, state, runtime): ...
```

这 8 个钩子形成一个完整的生命周期拦截网。"洋葱模型"的核心在 `wrap_model_call` 和 `wrap_tool_call`：

```python
def compose_two(outer, inner):
    """外层先执行 before，内层执行核心逻辑，外层再执行 after"""
    async def composed(request, handler):
        async def inner_handler(req):
            return await inner(req, handler)
        return await outer(request, inner_handler)
    return composed
```

所有中间件的 `wrap_*` 钩子通过 `compose_two` 递归组合成一个洋葱——请求从最外层进入，逐层穿透到核心（实际 LLM/工具调用），响应再从核心逐层返回。这类似于 HTTP 中间件或 Python 装饰器的堆叠模式。

### 6.4 18 种内置中间件

| 类别 | 中间件 | 功能 |
|------|--------|------|
| **记忆** | `AgentMemoryMiddleware` | 管理对话历史，滑动窗口裁剪 |
| **摘要** | `SummarizationMiddleware` | 长对话自动摘要 |
| **上下文** | `ContextEditingMiddleware` | 动态编辑剪枝上下文 |
| **工具** | `ToolRetryMiddleware` | 工具调用失败自动重试 |
| **限制** | `ModelLimitMiddleware` | 限制模型调用次数/频率 |
| **时间** | `TodoListMiddleware` | 注入待办事项管理 |
| **文件** | `FilesystemMiddleware` | 文件系统操作支持 |
| **代码** | `CodeSandboxMiddleware` | 代码执行沙箱 |
| **人工** | `HumanInTheLoopMiddleware` | 关键操作人工审批 |
| **错误** | `ToolEmulatorMiddleware` | 离线模拟工具 |
| **规划** | `PlanningMiddleware` | 计划→执行→反思 |
| **搜索** | `SearchMiddleware` | 自动搜索增强 |
| **中断** | `InterruptMiddleware` | 长任务中断恢复 |
| **系统** | `SystemMessageMiddleware` | 动态系统消息注入 |

还有 4 个装饰器驱动类型：
- `wrap_model_call` 装饰器 → `ModelCallMiddleware`
- `wrap_tool_call` 装饰器 → `ToolCallMiddleware`
- `before_model` 装饰器 → `BeforeModelMiddleware`
- `after_model` 装饰器 → `AfterModelMiddleware`

### 6.5 结构化输出系统

三种策略：

| 策略 | 机制 | 适用场景 |
|------|------|---------|
| `ToolStrategy` | `tool_choice="any"` 强制调用指定工具 | OpenAI/Anthropic 原生支持 |
| `ProviderStrategy` | `response_format={"type": "json_schema"}` | 提供商原生结构化输出 |
| `AutoStrategy` | 自动选择最优策略 | 通用场景 |

`ProviderStrategy` 使用 `tool_choice="any"` 方式——告诉 LLM"你必须调用这个 output 工具"但不暴露给用户——实现了声明式结构化输出。

---

## 七、跨系统设计模式

### 7.1 贯穿全栈的 12 种模式

| 设计模式 | 出现位置 | LangChain 中的应用 |
|---------|---------|-------------------|
| **模板方法** | BaseTool, AgentExecutor | 定义算法骨架，子类实现 `_run`/`_plan` |
| **策略** | OutputParser, handle_tool_error | 可注入的错误处理/解析策略 |
| **装饰器** | @tool, RunnableBinding | 透明增强功能 |
| **适配器** | _format_output, BlockTranslator | 将异构输出统一为 ToolMessage |
| **工厂方法** | from_function, create_agent | 从普通函数/Prompt 创建配置好的实例 |
| **洋葱模型** | wrap_model_call | 中间件嵌套拦截 |
| **依赖注入** | InjectedToolArg, ToolRuntime | 运行时参数注入 |
| **观察者** | CallbackManager | 生命周期事件分发 |
| **责任链** | pipe 运算符, RunnableSequence | 顺序传递数据流 |
| **组合** | RunnableParallel | 声明式并行组合 |
| **状态** | AgentState, StateGraph | 图状态管理 |
| **门面** | ChatPromptTemplate | 多格式 prompt 构建的统一入口 |

### 7.2 核心设计原则提炼

1. **"可组合优于可继承"**：LCEL 管道使组件像乐高积木一样拼接，而非通过多层继承
2. **"声明式优于命令式"**：RunnableParallel 的 `dict` 定义、中间件的装饰器创建——说"做什么"而非"怎么做"
3. **"渐进增强"**：普通函数 → `@tool` → `StructuredTool`，每个层次增加能力而不破坏向后兼容
4. **"信息隐藏"**：注入参数对 LLM 不可见、artifact 对 LLM 不可见——减少 LLM 的认知负担
5. **"失败优雅降级"**：`invalid_tool_calls` 不抛异常、`send_to_llm=True` 让 LLM 自我修正

---

## 八、技术演进与选型建议

### 8.1 五阶段演进总结

| 阶段 | 代表技术 | 核心突破 |
|------|---------|---------|
| 1. 文本解析 | ReAct, MRKL | 让 LLM 按约定格式输出动作 |
| 2. 结构化解析 | Structured Chat, XML | JSON/XML 块提取，多参数支持 |
| 3. Function Calling | OpenAI Functions | LLM 原生支持函数调用，零文本解析 |
| 4. Tool Calling | create_tool_calling_agent | 通用 tool_call 协议，并行调用 |
| 5. 中间件架构 | V1 create_agent | 洋葱模型、声明式中间件、LangGraph 原生 |

### 8.2 选型建议

| 场景 | 推荐方案 |
|------|---------|
| 新项目 | V1 `create_agent` + 中间件 |
| 简单工具调用 | `create_tool_calling_agent` |
| 需要并行工具执行 | V1（StateGraph Send API） |
| 需要人工审批 | V1 + `HumanInTheLoopMiddleware` |
| 长对话记忆管理 | V1 + `SummarizationMiddleware` + `AgentMemoryMiddleware` |
| 遗留兼容 | `AgentExecutor` + Tool Calling |

### 8.3 LyClaw 项目的借鉴意义

LyClaw 的 ReAct 引擎设计和 Agent 编排系统可以从 LangChain 学习以下要点：

1. **执行协议统一**：将 Agent、Tool、Prompt 都视为可组合的 Runnable，便于管道化
2. **工具系统**：注入参数（`InjectedToolArg`）的设计特别适合沙箱等级、权限等运行时上下文
3. **消息双轨**：content/artifact 分离可以让 LyClaw 的 ToolMessage 既给 LLM 可读摘要又保留完整数据给前端
4. **中间件模式**：当前 LyClaw 的 ReAct 循环是一段硬编码逻辑，中间件模式可以将其拆分为可组合的拦截器
5. **优雅降级**：`send_to_llm=True` 的设计——解析失败时不崩溃而是让 LLM 重试——值得在命令解析等场景借鉴

---

## 附录：五个子分析文档

| 文档 | 行数 | 重点 |
|------|------|------|
| [langchain-runnable-engine-analysis.md](./langchain-runnable-engine-analysis.md) | 1215 | LCEL、序列/并行/条件执行、回调、流事件 |
| [langchain-tool-system-analysis.md](./langchain-tool-system-analysis.md) | 220 | 工具继承、注入参数、执行管道、设计模式 |
| [langchain-v1-agent-middleware-analysis.md](./langchain-v1-agent-middleware-analysis.md) | 1294 | 洋葱模型、18 种中间件、StateGraph、结构化输出 |
| [langchain-classic-agent-patterns-analysis.md](./langchain-classic-agent-patterns-analysis.md) | 1443 | 6 种 Agent 模式对比、输出解析器、模式演进 |
| [langchain-message-prompt-analysis.md](./langchain-message-prompt-analysis.md) | 797 | 消息体系、ContentBlock、BlockTranslator、Prompt 系统 |

> 所有分析基于 `/home/lyjew/Documents/github/langchain` 源码，共计分析 5 个子系统、涵盖约 150+ 核心源文件。
