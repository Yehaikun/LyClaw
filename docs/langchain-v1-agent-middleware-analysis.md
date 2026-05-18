# LangChain V1 Agent 中间件系统深度分析

> 源码版本: langchain_v1 (libs/langchain_v1)
> 分析日期: 2026-05-18
> 源码路径: /home/lyjew/Documents/github/langchain/libs/langchain_v1/langchain/agents/

---

## 目录

1. [整体架构概览](#1-整体架构概览)
2. [StateGraph 驱动的 Agent 架构](#2-stategraph-驱动的-agent-架构)
3. [AgentState 状态管理系统](#3-agentstate-状态管理系统)
4. [AgentMiddleware 基类设计](#4-agentmiddleware-基类设计)
5. [中间件生命周期钩子体系](#5-中间件生命周期钩子体系)
6. [洋葱模型 (Onion Model) 组合模式](#6-洋葱模型-onion-model-组合模式)
7. [wrap_model_call 拦截机制](#7-wrap_model_call-拦截机制)
8. [wrap_tool_call 拦截机制](#8-wrap_tool_call-拦截机制)
9. [Agent 工厂模式](#9-agent-工厂模式)
10. [图编译与执行流程](#10-图编译与执行流程)
11. [全部中间件类型详解 (18种)](#11-全部中间件类型详解-18种)
12. [装饰器驱动的中间件创建](#12-装饰器驱动的中间件创建)
13. [结构化输出系统](#13-结构化输出系统)
14. [设计模式与架构洞察](#14-设计模式与架构洞察)

---

## 1. 整体架构概览

LangChain V1 Agent 中间件系统是一个基于 **LangGraph StateGraph** 构建的、高度可扩展的 Agent 框架。其核心设计思想是：**将 Agent 的完整生命周期暴露为一系列可拦截的钩子点（hook points），中间件可以在这些钩子点上注入自定义逻辑**。

整个系统由以下层次组成：

```
┌─────────────────────────────────────────────────────┐
│                  create_agent()                      │
│              (Agent 工厂函数)                         │
├─────────────────────────────────────────────────────┤
│  AgentMiddleware 基类                                │
│  ├── 状态扩展 (state_schema)                         │
│  ├── 工具注册 (tools)                                │
│  ├── 生命周期钩子 (before/after hooks)               │
│  ├── 模型调用拦截 (wrap_model_call)                  │
│  └── 工具调用拦截 (wrap_tool_call)                   │
├─────────────────────────────────────────────────────┤
│  18种内置中间件                                       │
├─────────────────────────────────────────────────────┤
│  StateGraph 编译与执行引擎                            │
│  (LangGraph 提供)                                    │
└─────────────────────────────────────────────────────┘
```

核心文件结构：

| 文件 | 职责 |
|------|------|
| `langchain/agents/__init__.py` | 公开 API 入口，导出 `create_agent` 和 `AgentState` |
| `langchain/agents/factory.py` | `create_agent()` 工厂函数，约 1880 行，是整个系统的核心 |
| `langchain/agents/middleware/types.py` | `AgentMiddleware` 基类、`AgentState`、请求/响应类型定义，约 2050 行 |
| `langchain/agents/middleware/__init__.py` | 所有中间件的公开导出 |
| `langchain/agents/middleware/*.py` | 14 个具体中间件实现 |
| `langchain/agents/structured_output.py` | 结构化输出策略系统 |
| `langchain_core/agents.py` | 旧版 Agent 类型定义（向后兼容） |

---

## 2. StateGraph 驱动的 Agent 架构

### 2.1 核心设计

LangChain V1 Agent 不使用传统的 `AgentExecutor` 循环，而是基于 LangGraph 的 `StateGraph` 构建为一个**有状态图**。

Agent 本质上是以下节点组成的图：

```
START → [before_agent 钩子链] → [before_model 钩子链] → model → [after_model 钩子链]
                                                                        ↓
                                                                   [tools 节点]
                                                                        ↓
                                                              [loop back to before_model]
                                                                        ↓
                                                              [after_agent 钩子链] → END
```

### 2.2 图的节点类型

图中有以下核心节点：

1. **model 节点**: 执行 LLM 模型调用，集成 `wrap_model_call` 中间件链
2. **tools 节点**: 使用 LangGraph 的 `ToolNode` 执行工具，集成 `wrap_tool_call` 中间件链
3. **中间件钩子节点**: 每个中间件的 `before_agent`、`before_model`、`after_model`、`after_agent` 都注册为独立节点

### 2.3 边的路由逻辑

图的边路由由多个条件边函数控制：

- **`_make_model_to_tools_edge`**: 从 model/after_model 节点出发，决定进入 tools、回到 model 还是结束
  - 优先检查 `jump_to` 状态字段
  - 若无 AIMessage 则退出循环
  - 若 AIMessage 无 tool_calls 则退出循环
  - 若有 pending tool calls，通过 `Send` API 并行分发到 tools 节点
  - 若有 `structured_response` 则退出

- **`_make_tools_to_model_edge`**: 从 tools 节点出发，决定回到 model 还是结束
  - 若所有工具有 `return_direct=True` 且全部执行完成，则结束
  - 若执行了结构化输出工具，则结束
  - 否则回到 model 继续循环

- **`_add_middleware_edge`**: 中间件节点间的边，支持 `can_jump_to` 条件路由

### 2.4 Send API 与并行工具调用

当模型返回多个 tool_calls 时，系统使用 LangGraph 的 `Send` API 实现并行分发：

```python
if pending_tool_calls:
    return [Send("tools", [tool_call]) for tool_call in pending_tool_calls]
```

每个 tool_call 作为一个独立的 `Send` 发送到 tools 节点，LangGraph 并行执行它们，结果通过 `add_messages` reducer 自动合并。

---

## 3. AgentState 状态管理系统

### 3.1 核心状态定义

```python
class AgentState(TypedDict, Generic[ResponseT]):
    messages: Required[Annotated[list[AnyMessage], add_messages]]
    jump_to: NotRequired[Annotated[JumpTo | None, EphemeralValue, PrivateStateAttr]]
    structured_response: NotRequired[Annotated[ResponseT, OmitFromInput]]
```

- **messages**: 使用 `add_messages` reducer，是 LangGraph 的 Append-Only 列表，支持消息追加、更新和删除（通过 `RemoveMessage`）
- **jump_to**: 使用 `EphemeralValue` channel，每次读取后自动清除，用于中间件控制流跳转。标记为 `PrivateStateAttr`，对输入/输出不可见
- **structured_response**: 使用 `OmitFromInput`，仅输出时暴露，用于传递结构化输出结果

### 3.2 状态 Channel 类型

系统使用多种 LangGraph channel 类型：

| Channel 类型 | 用途 |
|-------------|------|
| `add_messages` | messages 字段的默认 reducer，支持追加/更新/删除消息 |
| `EphemeralValue` | jump_to 字段，一次读取后自动清除 |
| `UntrackedValue` | 每次运行重置，用于 `run_tool_call_count`、`shell_session_resources` 等 |
| `BinaryOperatorAggregate` | 默认 reducer，合并中间件状态字典 |

### 3.3 状态 Schema 合并机制

`create_agent` 支持多层状态 schema 合并：

```
最终状态 = 中间件1.state_schema + 中间件2.state_schema + ... + 用户指定的 state_schema
```

合并时有明确优先级：**后出现的覆盖先出现的**。用户的 `state_schema` 参数具有最高优先级。

```python
state_schemas: list[type] = [*(m.state_schema for m in middleware), base_state]
resolved_state_schema, input_schema, output_schema = _resolve_schemas(state_schemas)
```

`_resolve_schemas` 函数处理 `OmitFromSchema` 注解，使得中间件可以为内部使用的字段标记 `OmitFromInput`、`OmitFromOutput` 或 `PrivateStateAttr`，控制它们在输入/输出 schema 中的可见性。

### 3.4 状态注解系统

```python
@dataclass
class OmitFromSchema:
    input: bool = True    # 从输入 schema 中隐藏
    output: bool = True   # 从输出 schema 中隐藏

OmitFromInput = OmitFromSchema(input=True, output=False)
OmitFromOutput = OmitFromSchema(input=False, output=True)
PrivateStateAttr = OmitFromSchema(input=True, output=True)
```

这使得中间件可以声明私有状态字段，例如 `ToolCallLimitMiddleware` 的 `thread_tool_call_count` 和 `run_tool_call_count` 对用户完全不可见。

---

## 4. AgentMiddleware 基类设计

### 4.1 泛型参数

```python
class AgentMiddleware(Generic[StateT, ContextT, ResponseT]):
```

- **StateT**: Agent 状态类型，默认为 `AgentState[Any]`
- **ContextT**: 运行时上下文类型，默认为 `None`
- **ResponseT**: 结构化响应类型，默认为 `Any`

### 4.2 核心属性

```python
state_schema: type[StateT]    # 状态 schema，用于扩展 AgentState
tools: Sequence[BaseTool]     # 中间件注册的额外工具
name: str                      # 中间件名称，默认取类名
```

### 4.3 钩子方法体系

中间件提供 10 个可重写的钩子方法（5 对同步/异步）：

| 钩子方法 | 异步版本 | 签名 | 用途 |
|---------|---------|------|------|
| `before_agent` | `abefore_agent` | `(state, runtime) -> dict\|None` | Agent 启动前 |
| `before_model` | `abefore_model` | `(state, runtime) -> dict\|None` | 每次模型调用前 |
| `after_model` | `aafter_model` | `(state, runtime) -> dict\|None` | 每次模型调用后 |
| `after_agent` | `aafter_agent` | `(state, runtime) -> dict\|None` | Agent 完成时 |
| `wrap_model_call` | `awrap_model_call` | `(request, handler) -> response` | 模型调用拦截 |
| `wrap_tool_call` | `awrap_tool_call` | `(request, handler) -> ToolMessage\|Command` | 工具调用拦截 |

### 4.4 同步/异步双轨设计

每个钩子方法都有同步和异步两个版本。工厂函数会检测中间件重写了哪个版本：

```python
# 检测方式：比较实例方法与基类方法不是同一个对象
m.__class__.before_model is not AgentMiddleware.before_model
```

当在异步上下文中调用同步的 `wrap_model_call` 时，基类的默认实现会抛出 `NotImplementedError` 并提供清晰的错误指示，反之亦然。这种显式的错误提示是优良的 DX 设计。

---

## 5. 中间件生命周期钩子体系

### 5.1 七个拦截点

```
                          ┌─────────────────┐
                          │   before_agent   │ ← 仅执行一次，Agent 启动时
                          └────────┬────────┘
                                   ↓
                    ┌──────────────────────────┐
                    │      before_model        │ ← 每次循环迭代，模型调用前
                    └──────────┬───────────────┘
                               ↓
              ┌────────────────┼────────────────┐
              │       wrap_model_call           │ ← 模型调用本身（洋葱模型）
              └────────────────┼────────────────┘
                               ↓
                    ┌──────────────────────────┐
                    │       after_model        │ ← 每次循环迭代，模型调用后
                    └──────────┬───────────────┘
                               ↓
                    ┌──────────────────────────┐
                    │    wrap_tool_call         │ ← 每个工具调用（洋葱模型）
                    └──────────┬───────────────┘
                               ↓
                          (循环回到 before_model)
                               ↓
                    ┌──────────────────────────┐
                    │       after_agent        │ ← 仅执行一次，Agent 完成时
                    └──────────────────────────┘
```

### 5.2 钩子的图节点表达

`before_agent`、`before_model`、`after_model`、`after_agent` 每个都注册为图中的独立节点，节点命名遵循 `{middleware.name}.{hook_name}` 模式：

```python
graph.add_node(f"{m.name}.before_agent", before_agent_node, input_schema=resolved_state_schema)
graph.add_node(f"{m.name}.before_model", before_node, input_schema=resolved_state_schema)
graph.add_node(f"{m.name}.after_model", after_node, input_schema=resolved_state_schema)
graph.add_node(f"{m.name}.after_agent", after_agent_node, input_schema=resolved_state_schema)
```

### 5.3 jump_to 跳跃机制

中间件钩子可以通过返回 `{"jump_to": "model" | "tools" | "end"}` 来改变执行流的走向：

```python
@hook_config(can_jump_to=["end", "model"])
def before_model(self, state, runtime):
    if should_exit(state):
        return {"jump_to": "end"}  # 跳过模型调用，直接结束
    return None  # 正常流程
```

`jump_to` 字段使用 `EphemeralValue` channel，确保每次读取后自动清除，防止重复触发。

### 5.4 钩子执行顺序

同类型的钩子按照中间件在 `middleware` 列表中的顺序执行：

- `before_agent`: `middleware[0].before_agent` -> `middleware[1].before_agent` -> ...
- `before_model`: `middleware[0].before_model` -> `middleware[1].before_model` -> ...
- `after_model`: `middleware[-1].after_model` -> `middleware[-2].after_model` -> ... (反向)
- `after_agent`: `middleware[-1].after_agent` -> `middleware[-2].after_agent` -> ... (反向)

注意 `after_model` 和 `after_agent` 是**反向**遍历的，这与洋葱模型的 "出站" 方向一致。

---

## 6. 洋葱模型 (Onion Model) 组合模式

### 6.1 核心概念

`wrap_model_call` 和 `wrap_tool_call` 采用了经典的**洋葱模型**（也称中间件管道模式）。多个中间件层层包裹，请求从外向内穿过各层到达核心处理逻辑，响应再从内向外穿过各层返回。

```
请求方向 ────────────────────────────────────────>
         ┌─────────────────────────────────────────┐
         │  中间件1.wrap_model_call (最外层)         │
         │  ┌───────────────────────────────────┐  │
         │  │  中间件2.wrap_model_call           │  │
         │  │  ┌─────────────────────────────┐  │  │
         │  │  │  中间件3.wrap_model_call     │  │  │
         │  │  │  ┌───────────────────────┐  │  │  │
         │  │  │  │  核心: _execute_model │  │  │  │
         │  │  │  └───────────────────────┘  │  │  │
         │  │  └─────────────────────────────┘  │  │
         │  └───────────────────────────────────┘  │
         └─────────────────────────────────────────┘
响应方向 <────────────────────────────────────────
```

### 6.2 组合实现

`_chain_model_call_handlers` 函数通过闭包组合实现洋葱模型：

```python
def compose_two(outer, inner):
    def composed(request, handler):
        accumulated_commands = []

        def inner_handler(req):
            accumulated_commands.clear()
            inner_result = inner(req, handler)
            # 提取 inner 产生的 commands
            return inner_result.model_response  # 标准化为 ModelResponse

        outer_result = outer(request, inner_handler)
        # 将 inner 的 commands 累积到 outer 结果中
        return _to_composed_result(outer_result, extra_commands=accumulated_commands)

    return composed

# 从右向左组合: outer(inner(innermost(handler)))
composed_handler = compose_two(handlers[-2], handlers[-1])
for h in reversed(handlers[:-2]):
    composed_handler = compose_two(h, composed_handler)
```

关键设计：
- 每个中间件收到一个 `handler` 回调，调用它即可执行内层逻辑
- 中间件可以在调用 `handler` 之前修改 `request`（修改模型参数、替换工具列表等）
- 中间件可以在调用 `handler` 之后修改 `response`（注入额外消息、修改 AI 响应等）
- 中间件可以选择**不调用** `handler`（短路）、调用**多次**（重试逻辑）

### 6.3 同步与异步两套组合

系统同时维护同步和异步两套组合函数：

- `_chain_model_call_handlers`：组合同步 `wrap_model_call`
- `_chain_async_model_call_handlers`：组合异步 `awrap_model_call`
- `_chain_tool_call_wrappers`：组合同步 `wrap_tool_call`
- `_chain_async_tool_call_wrappers`：组合异步 `awrap_tool_call`

### 6.4 Command 累积机制

在洋葱模型中，内层中间件产生的 `Command` 会被外层捕获并累积：

```python
class _ComposedExtendedModelResponse:
    model_response: ModelResponse
    commands: list[Command]  # 累积所有层的 commands
```

这意味着如果一个重试中间件在内层，它多次调用 handler 产生多个 Command，外层中间件的逻辑仍然在这些重试之外包裹。

---

## 7. wrap_model_call 拦截机制

### 7.1 接口设计

```python
def wrap_model_call(
    self,
    request: ModelRequest[ContextT],
    handler: Callable[[ModelRequest[ContextT]], ModelResponse[ResponseT]],
) -> ModelResponse[ResponseT] | AIMessage | ExtendedModelResponse[ResponseT]:
```

### 7.2 ModelRequest 数据结构

```python
@dataclass
class ModelRequest(Generic[ContextT]):
    model: BaseChatModel
    messages: list[AnyMessage]          # 不含 system message
    system_message: SystemMessage | None
    tool_choice: Any | None
    tools: list[BaseTool | dict[str, Any]]
    response_format: ResponseFormat[Any] | None
    state: AgentState[Any]
    runtime: Runtime[ContextT]
    model_settings: dict[str, Any]
```

### 7.3 不可变模式 (Immutable Pattern)

`ModelRequest` 采用不可变模式，通过 `override()` 方法创建修改后的新实例：

```python
new_request = request.override(
    model=different_model,
    system_message=SystemMessage(content="New instructions"),
    tools=filtered_tools,
)
```

这确保了每个中间件层都有自己的 request 副本，避免层间互相干扰。

### 7.4 返回值类型

中间件可以返回三种类型：

1. **`ModelResponse`**: 包含 `result: list[BaseMessage]` 和可选的 `structured_response`
2. **`AIMessage`**: 简单场景，自动转换为 `ModelResponse`
3. **`ExtendedModelResponse`**: 携带额外的 `Command` 用于状态更新

### 7.5 核心执行逻辑

模型调用的核心逻辑 `_execute_model_sync` 做了以下事情：
1. 获取绑定好工具的模型 (通过 `_get_bound_model`)
2. 将 system_message (如果有) 前置到 messages 列表
3. 调用 `model.invoke(messages)`
4. 处理结构化输出 (`_handle_model_output`)
5. 返回 `ModelResponse`

### 7.6 模型节点与中间件的集成

在 `model_node` 中，如果没有 `wrap_model_call` 中间件，直接执行模型：

```python
if wrap_model_call_handler is None:
    model_response = _execute_model_sync(request)
    return _build_commands(model_response)
```

如果有，则将核心执行逻辑作为 handler 传递给洋葱模型组合：

```python
result = wrap_model_call_handler(request, _execute_model_sync)
return _build_commands(result.model_response, result.commands)
```

---

## 8. wrap_tool_call 拦截机制

### 8.1 接口设计

```python
def wrap_tool_call(
    self,
    request: ToolCallRequest,
    handler: Callable[[ToolCallRequest], ToolMessage | Command[Any]],
) -> ToolMessage | Command[Any]:
```

### 8.2 ToolCallRequest

`ToolCallRequest` 来自 LangGraph 的 `langgraph.prebuilt.tool_node`，包含：
- `tool_call`: 工具调用的字典（name, args, id）
- `tool`: `BaseTool` 实例
- `state`: 运行时状态（通过 LangGraph 的 `ToolRuntime` 注入）
- `runtime`: 运行时上下文

### 8.3 组合机制

工具调用 wrappers 的洋葱模型组合与 `wrap_model_call` 完全相同：

```python
def compose_two(outer, inner):
    def composed(request, execute):
        def call_inner(req):
            return inner(req, execute)
        return outer(request, call_inner)
    return composed
```

中间件列表的第一个成为最外层，包裹后续所有中间件。

### 8.4 与 ToolNode 的集成

在 `create_agent` 中，组合后的 `wrap_tool_call_wrapper` 直接传递给 `ToolNode`：

```python
tool_node = ToolNode(
    tools=available_tools,
    wrap_tool_call=wrap_tool_call_wrapper,
    awrap_tool_call=awrap_tool_call_wrapper,
)
```

`ToolNode` 在执行每个工具前调用 `wrap_tool_call_wrapper(request, execute_fn)`。

---

## 9. Agent 工厂模式

### 9.1 create_agent 签名

```python
def create_agent(
    model: str | BaseChatModel,
    tools: Sequence[BaseTool | Callable | dict] | None = None,
    *,
    system_prompt: str | SystemMessage | None = None,
    middleware: Sequence[AgentMiddleware] = (),
    response_format: ResponseFormat | type | dict | None = None,
    state_schema: type[AgentState] | None = None,
    context_schema: type[ContextT] | None = None,
    checkpointer: Checkpointer | None = None,
    store: BaseStore | None = None,
    interrupt_before: list[str] | None = None,
    interrupt_after: list[str] | None = None,
    debug: bool = False,
    name: str | None = None,
    cache: BaseCache | None = None,
    transformers: Sequence[Callable] | None = None,
) -> CompiledStateGraph:
```

### 9.2 工厂函数的核心流程

1. **模型初始化**: 字符串模型名通过 `init_chat_model` 解析
2. **工具收集**: 合并用户工具 + 中间件工具 + 结构化输出工具
3. **中间件分类**: 按钩子类型将中间件分为 5 类
4. **洋葱模型组合**: 组合 `wrap_model_call` 和 `wrap_tool_call` 处理链
5. **Schema 解析**: 合并所有状态 schema
6. **图构建**: 添加 model、tools 节点 + 中间件钩子节点
7. **边路由**: 配置条件边和中间件跳跃边
8. **编译**: 调用 `graph.compile()` 并附加配置

### 9.3 中间件验证

工厂函数强制验证：
- 不允许重复的中间件实例（按 `name` 检查）
- 动态添加的工具必须在 `create_agent` 时注册，或由中间件自己通过 `wrap_tool_call` 处理

```python
if len({m.name for m in middleware}) != len(middleware):
    msg = "Please remove duplicate middleware instances."
    raise AssertionError(msg)
```

### 9.4 工具配置

工具分为三类：

1. **用户工具** (`regular_tools`): BaseTool 实例或可调用对象
2. **中间件工具** (`middleware_tools`): 中间件注册的额外工具
3. **内置工具** (`built_in_tools`): 模型提供商原生支持的工具（以 dict 形式）

工具节点的创建条件：
```python
tool_node = ToolNode(
    tools=available_tools,
    wrap_tool_call=wrap_tool_call_wrapper,
    awrap_tool_call=awrap_tool_call_wrapper,
) if available_tools or wrap_tool_call_wrapper or awrap_tool_call_wrapper else None
```

即使没有工具，如果存在 `wrap_tool_call` 中间件（可能动态处理工具），也会创建 ToolNode。

---

## 10. 图编译与执行流程

### 10.1 图的完整拓扑

```
START
  ↓
{before_agent[0]} → {before_agent[1]} → ...
  ↓
{before_model[0]} → {before_model[1]} → ...
  ↓
[model] ←──────────────────────────┐
  ↓                                  │
{after_model[-1]} → {after_model[-2]} → ...
  ↓                                  │
[Conditional Edge: _make_model_to_tools_edge]
  ├── [tools] ──────────→ Conditional: _make_tools_to_model_edge
  │                         ↓
  │                       (back to before_model or exit)
  └── [exit to after_agent or END]
       ↓
{after_agent[-1]} → {after_agent[-2]} → ...
       ↓
      END
```

### 10.2 路由决策引擎

`_make_model_to_tools_edge` 的优先级逻辑（从上到下）：

1. **jump_to 状态**: 如果 `state["jump_to"]` 有值，解析并路由
2. **无 AIMessage**: 如果消息列表中没有 AIMessage，结束循环
3. **无 tool_calls**: 如果最后 AIMessage 没有 tool_calls，结束循环
4. **有 pending tool_calls**: 通过 Send API 并行分发到 tools 节点
5. **有 structured_response**: 结束循环
6. **有注入的人工 tool messages**: 回到 model 节点继续思考

### 10.3 配置预设

编译时自动设置：
```python
config: RunnableConfig = {"recursion_limit": 9_999}
config["metadata"] = {"ls_integration": "langchain_create_agent"}
```

递归限制设置为 9999，防止长时间运行的 Agent 被误中断。`ls_integration` 元数据用于 LangSmith 追踪。

### 10.4 LangSmith 集成

每个中间件钩子都通过 `@traceable` 装饰器集成 LangSmith 追踪：

```python
traceable(name=f"{m.name}.wrap_model_call", process_inputs=_scrub_inputs)(m.wrap_model_call)
```

`_scrub_inputs` 函数从追踪输入中移除 `runtime` 和 `handler` 等不可序列化的对象。

### 10.5 流式转换器

编译时自动注册 `ToolCallTransformer`，用于在流式输出中处理工具调用事件的分块聚合。

---

## 11. 全部中间件类型详解 (18种)

### 11.1 完整清单

系统提供 14 个具体的中间件类 + 4 个装饰器生成的钩子类型：

| # | 中间件 | 文件 | 拦截点 | 用途 |
|---|--------|------|--------|------|
| 1 | `ModelRetryMiddleware` | `model_retry.py` | `wrap_model_call` | 模型调用失败自动重试 |
| 2 | `ToolRetryMiddleware` | `tool_retry.py` | `wrap_tool_call` | 工具调用失败自动重试 |
| 3 | `ModelFallbackMiddleware` | `model_fallback.py` | `wrap_model_call` | 模型失败时切换备用模型 |
| 4 | `ModelCallLimitMiddleware` | `model_call_limit.py` | `before_model` + `after_model` | 限制模型调用次数 |
| 5 | `ToolCallLimitMiddleware` | `tool_call_limit.py` | `after_model` | 限制工具调用次数 |
| 6 | `SummarizationMiddleware` | `summarization.py` | `before_model` | 上下文超限时自动摘要 |
| 7 | `ContextEditingMiddleware` | `context_editing.py` | `wrap_model_call` | 清除旧的工具结果释放上下文 |
| 8 | `HumanInTheLoopMiddleware` | `human_in_the_loop.py` | `after_model` | 人工审批工具调用 |
| 9 | `PIIMiddleware` | `pii.py` | `before_model` + `after_model` | 检测和处理敏感信息 |
| 10 | `TodoListMiddleware` | `todo.py` | `wrap_model_call` + `after_model` | 提供 Todo 管理工具 |
| 11 | `LLMToolSelectorMiddleware` | `tool_selection.py` | `wrap_model_call` | 用 LLM 筛选相关工具 |
| 12 | `LLMToolEmulator` | `tool_emulator.py` | `wrap_tool_call` | 用 LLM 模拟工具输出(测试) |
| 13 | `ShellToolMiddleware` | `shell_tool.py` | `before_agent` + `after_agent` + 工具注册 | 持久化 Shell 会话 |
| 14 | `FilesystemFileSearchMiddleware` | `file_search.py` | 工具注册 | Glob 和 Grep 文件搜索 |
| -- | `@before_model` 装饰器 | `types.py` | `before_model` | 动态创建 before_model 中间件 |
| -- | `@after_model` 装饰器 | `types.py` | `after_model` | 动态创建 after_model 中间件 |
| -- | `@before_agent` 装饰器 | `types.py` | `before_agent` | 动态创建 before_agent 中间件 |
| -- | `@after_agent` 装饰器 | `types.py` | `after_agent` | 动态创建 after_agent 中间件 |

### 11.2 ModelRetryMiddleware

**文件**: `model_retry.py`

**拦截点**: `wrap_model_call`

**核心逻辑**:
```
for attempt in range(max_retries + 1):
    try:
        return handler(request)
    except Exception as exc:
        if not should_retry_exception(exc, retry_on):
            return handle_failure(exc)
        if attempt < max_retries:
            sleep(calculate_delay(attempt))
        else:
            return handle_failure(exc)
```

**参数**:
- `max_retries`: 最大重试次数（默认 2）
- `retry_on`: 异常类型元组或过滤函数
- `on_failure`: `'continue'`（返回 AIMessage 继续）、`'error'`（抛出异常）或自定义格式化函数
- `backoff_factor`: 指数退避乘数（默认 2.0）
- `initial_delay`: 初始延迟秒数（默认 1.0）
- `max_delay`: 最大延迟秒数（默认 60.0）
- `jitter`: 是否添加 ±25% 随机抖动

**设计亮点**:
- 指数退避 + 抖动（避免惊群效应）
- 可自定义异常过滤逻辑
- 灵活的失败处理策略

### 11.3 ToolRetryMiddleware

**文件**: `tool_retry.py`

**拦截点**: `wrap_tool_call`

与 `ModelRetryMiddleware` 共享重试基础设施（`_retry.py` 中的 `calculate_delay`、`should_retry_exception`）。

**额外特性**:
- 支持按工具名称过滤：`tools=["search_database"]` 仅对指定工具重试
- 失败时返回带 `status="error"` 的 `ToolMessage`，让 LLM 自行决定如何处理

### 11.4 ModelFallbackMiddleware

**文件**: `model_fallback.py`

**拦截点**: `wrap_model_call`

**核心逻辑**:
```
try:
    return handler(request)         # 尝试主模型
except Exception as e:
    last_exception = e

for fallback_model in self.models:
    try:
        return handler(request.override(model=fallback_model))
    except Exception as e:
        last_exception = e

raise last_exception
```

**设计亮点**:
- 简洁的顺序回退模式
- 通过 `request.override(model=...)` 切换模型，不改动其他参数
- 所有 fallback 模型都失败时才抛出异常

### 11.5 ModelCallLimitMiddleware

**文件**: `model_call_limit.py`

**拦截点**: `before_model` (检查) + `after_model` (计数)

**核心状态**:
```python
class ModelCallLimitState(AgentState):
    thread_model_call_count: int     # 线程级(跨运行)持久计数
    run_model_call_count: int        # 运行级(单次调用)计数
```

**行为选项**:
- `'end'`: 超限时注入 AIMessage 并跳转到结束
- `'error'`: 超限时抛出 `ModelCallLimitExceededError`

**设计亮点**:
- `before_model` 在模型调用前检查，避免超限调用
- `after_model` 在模型调用后递增计数
- `run_model_call_count` 使用 `UntrackedValue`，每次运行自动重置

### 11.6 ToolCallLimitMiddleware

**文件**: `tool_call_limit.py`

**拦截点**: `after_model`

**核心状态**:
```python
class ToolCallLimitState(AgentState):
    thread_tool_call_count: dict[str, int]   # 按工具名的线程级计数
    run_tool_call_count: dict[str, int]      # 按工具名的运行级计数
```

**核心逻辑**: 在 `after_model` 中：
1. 获取最后 AIMessage 的 tool_calls
2. 检查每个 tool_call 是否超限
3. 将 tool_calls 分为 `allowed_calls` 和 `blocked_calls`
4. 对于 blocked 的调用，注入带错误信息的 `ToolMessage`

**行为选项**:
- `'continue'`: 阻止超限工具但允许其他工具继续执行（默认）
- `'end'`: 立即结束执行
- `'error'`: 抛出 `ToolCallLimitExceededError`

**设计亮点**:
- 支持按工具名精细化限制：`tool_name="search"`
- 灵活的退出行为：可以阻止单个工具而不终止整个任务
- 使用 `@hook_config(can_jump_to=["end"])` 支持跳跃

### 11.7 SummarizationMiddleware

**文件**: `summarization.py`

**拦截点**: `before_model`

**核心逻辑**:
1. 统计当前消息的 token 数
2. 检查是否超过触发阈值（支持多种触发条件）
3. 如果超限，确定截断点（保留最近 N 条消息）
4. 调用摘要模型生成摘要
5. 用 `RemoveMessage` 清除旧消息，注入摘要 + 保留的消息

**触发条件配置**:
```python
ContextSize = ContextFraction | ContextTokens | ContextMessages
# ("fraction", 0.8)   - 模型最大上下文窗口的 80%
# ("tokens", 3000)    - 绝对 3000 tokens
# ("messages", 50)    - 绝对 50 条消息
```

**保留策略配置**:
- 同上，但不支持多条件（仅单值）

**设计亮点**:
- 支持多种上下文大小配置：分数（模型自适应）、绝对令牌数、绝对消息数
- 智能截断：不会在 AI/Tool 消息对中间切断
- 使用二进制搜索确定最佳截断位置
- 摘要生成使用独立的摘要模型

### 11.8 ContextEditingMiddleware

**文件**: `context_editing.py`

**拦截点**: `wrap_model_call`

**核心逻辑**: 当消息的 token 数超过阈值时，清除旧的工具使用结果（将其替换为占位符）。

**编辑策略** (`ClearToolUsesEdit`):
- 超过 `trigger` (默认 100K tokens) 时触发
- 保留最近 `keep` (默认 3) 个工具结果
- 可选清除工具输入参数 (`clear_tool_inputs`)
- 支持排除特定工具 (`exclude_tools`)

**设计亮点**:
- 模拟 Anthropic 原生 `clear_tool_uses` 功能
- 模型无关的实现（通过 `count_tokens_approximately` 或模型特定 token 计数）
- 使用 `deepcopy` 确保不影响原始消息

### 11.9 HumanInTheLoopMiddleware

**文件**: `human_in_the_loop.py`

**拦截点**: `after_model`

**核心逻辑**:
1. 在模型返回 tool_calls 后检查哪些工具需要人工审批
2. 通过 LangGraph 的 `interrupt()` API 暂停执行
3. 等待人工决策: `approve`、`edit`、`reject` 或 `respond`
4. 根据决策修改 tool_calls 或注入人工 ToolMessage

**审批决策类型**:
| 决策 | 效果 |
|------|------|
| `approve` | 允许工具调用正常执行 |
| `edit` | 修改工具名称或参数后执行 |
| `reject` | 不执行工具，返回错误 ToolMessage |
| `respond` | 跳过工具执行，直接返回人工回答 |

**配置方式**:
```python
HumanInTheLoopMiddleware(
    interrupt_on={
        "delete_file": True,  # 所有决策都允许
        "send_email": InterruptOnConfig(
            allowed_decisions=["approve", "reject"],
            description="Send email to user"
        ),
    }
)
```

**设计亮点**:
- 支持批量审批：多个工具调用在一次中断中审批
- 使用 LangGraph 原生的 `interrupt()` API，天然支持断点续传
- 审批后修改的 tool_calls 会被放回 AIMessage，让模型知道修改了什么

### 11.10 PIIMiddleware

**文件**: `pii.py`

**拦截点**: `before_model` (输入检测) + `after_model` (输出检测)

**内置 PII 类型**:
| 类型 | 检测方式 |
|------|---------|
| `email` | 正则匹配 |
| `credit_card` | 正则 + Luhn 算法验证 |
| `ip` | 正则 + stdlib `ipaddress` 验证 |
| `mac_address` | 正则匹配 |
| `url` | 正则 + `urlparse` 验证 |

**处理策略**:
| 策略 | 效果 | 示例 |
|------|------|------|
| `block` | 抛出 `PIIDetectionError` | 检测到敏感信息直接拒绝 |
| `redact` | 替换为 `[REDACTED_EMAIL]` | 完全隐藏敏感信息 |
| `mask` | 部分遮蔽 | `****@gmail.com`、`****-****-****-1234` |
| `hash` | 确定性哈希 | `<email_hash:a1b2c3d4>` (可追踪但不暴露) |

**设计亮点**:
- 可分别控制对输入/输出/Tool结果的检测
- 支持自定义检测器（正则或函数）
- 每种 PII 类型有专门的 mask 逻辑

### 11.11 TodoListMiddleware

**文件**: `todo.py`

**拦截点**: `wrap_model_call` (注入系统提示) + `after_model` (防止并行调用)

**核心功能**:
- 注册 `write_todos` 工具，允许 Agent 创建和管理任务列表
- 自动注入详细的使用指南到系统提示
- 强制约束：每次模型调用最多调用一次 `write_todos`

**状态扩展**:
```python
class PlanningState(AgentState):
    todos: list[Todo]  # Todo = {content, status: pending|in_progress|completed}
```

**设计亮点**:
- 工具本身通过 `Command` 同时更新 `todos` 和 `messages`，确保原子性
- `after_model` 检测并行 `write_todos` 调用并返回错误，防止状态冲突
- 详细的使用指南嵌入系统提示，引导模型正确使用

### 11.12 LLMToolSelectorMiddleware

**文件**: `tool_selection.py`

**拦截点**: `wrap_model_call`

**核心逻辑**:
1. 在调用主模型前，用另一个（通常更小更便宜的）LLM 评估哪些工具与当前查询相关
2. 将工具选择响应解析为过滤后的工具列表
3. 用过滤后的工具列表调用主模型

**配置参数**:
- `max_tools`: 最多选择工具数
- `always_include`: 始终包含的工具列表（不计入 max_tools）
- `model`: 用于选择的模型（默认使用主模型）

**设计亮点**:
- 使用辅助 LLM 做工具筛选，减少主模型调用的 token 消耗
- 支持 `always_include` 确保关键工具始终可用
- 辅助模型可以是更便宜的模型（如 `gpt-4o-mini`）

### 11.13 LLMToolEmulator

**文件**: `tool_emulator.py`

**拦截点**: `wrap_tool_call`

**核心逻辑**:
1. 如果被调用的工具在模拟列表中，不执行真实工具
2. 用 LLM 生成模拟的工具输出
3. 返回 `ToolMessage`（短路真实执行）

**用途**: 测试场景，用 LLM 模拟工具输出而不实际调用外部服务。

**设计亮点**:
- 可选择模拟全部工具或特定工具
- 模拟模型独立配置（默认使用 Claude Sonnet）
- 通过 `wrap_tool_call` 的短路机制实现

### 11.14 ShellToolMiddleware

**文件**: `shell_tool.py`

**拦截点**: `before_agent` + `after_agent` + 工具注册

**核心功能**:
- 注册持久化 `shell` 工具，Agent 可以交互式执行命令
- 使用后台子进程维护 shell 会话状态
- 支持三种执行策略

**执行策略** (`_execution.py`):

| 策略 | 隔离程度 | 适用场景 |
|------|---------|---------|
| `HostExecutionPolicy` | 无隔离 | 受信环境、CI/CD、开发者工作站 |
| `CodexSandboxExecutionPolicy` | syscall 沙箱 | 有 Codex CLI 的环境 |
| `DockerExecutionPolicy` | 容器隔离 | 不信任用户代码、多租户环境 |

**架构设计**:
```
ShellToolMiddleware
├── ShellSession (持久化 shell 进程)
│   ├── 子进程管理 (start/stop/restart)
│   ├── stdout/stderr 后台读取线程
│   ├── 输出队列 + 超时控制
│   └── 输出截断 (行数/字节数限制)
├── _SessionResources (资源容器)
│   ├── finalizer 自动清理
│   └── TemporaryDirectory 管理
└── 红action 规则 (输出内容过滤)
```

**设计亮点**:
- 使用 `weakref.finalize` 确保资源清理
- 使用 `UntrackedValue` channel，shell 会话每次运行重建
- 支持启动命令 (`startup_commands`) 和关闭命令 (`shutdown_commands`)
- 输出自动截断防止上下文爆炸
- 命令通过 UUID 标记 + 退出码注入确定命令执行边界

### 11.15 FilesystemFileSearchMiddleware

**文件**: `file_search.py`

**拦截点**: 工具注册

**注册工具**:
- `glob_search`: 文件模式匹配（支持 `**/*.py` 等 glob 模式）
- `grep_search`: 内容正则搜索（优先使用 ripgrep，回退到 Python）

**配置参数**:
- `root_path`: 搜索根目录
- `use_ripgrep`: 是否使用 ripgrep（默认 True）
- `max_file_size_mb`: 最大搜索文件大小

**设计亮点**:
- 路径遍历保护（拒绝 `..` 和 `~`）
- ripgrep + Python fallback 两级策略
- 三种输出模式：`files_with_matches`、`content`、`count`

---

## 12. 装饰器驱动的中间件创建

### 12.1 七个装饰器

系统提供了 7 个装饰器用于快速创建中间件，无需定义完整的类：

| 装饰器 | 生成的中间件钩子 | 函数签名 |
|--------|-----------------|---------|
| `@before_model` | `AgentMiddleware.before_model` | `(state, runtime) -> dict\|None` |
| `@after_model` | `AgentMiddleware.after_model` | `(state, runtime) -> dict\|None` |
| `@before_agent` | `AgentMiddleware.before_agent` | `(state, runtime) -> dict\|None` |
| `@after_agent` | `AgentMiddleware.after_agent` | `(state, runtime) -> dict\|None` |
| `@wrap_model_call` | `AgentMiddleware.wrap_model_call` | `(request, handler) -> response` |
| `@wrap_tool_call` | `AgentMiddleware.wrap_tool_call` | `(request, handler) -> ToolMessage` |
| `@dynamic_prompt` | `AgentMiddleware.wrap_model_call` | `(request) -> str\|SystemMessage` |

### 12.2 实现原理

每个装饰器内部使用 `type()` 动态创建 `AgentMiddleware` 子类：

```python
def decorator(func):
    is_async = iscoroutinefunction(func)

    def wrapped(_self, state, runtime):
        return func(state, runtime)

    return type(
        "MiddlewareName",
        (AgentMiddleware,),
        {
            "state_schema": state_schema or AgentState,
            "tools": tools or [],
            "before_model": wrapped,  # 或 abefore_model
        },
    )()
```

自动检测函数是否为 async，如果是则绑定到 `a*` 版本的方法。

### 12.3 使用示例

```python
# 1. before_model - 日志记录
@before_model
def log_conversation_length(state: AgentState, runtime: Runtime) -> None:
    print(f"Messages: {len(state['messages'])}")

# 2. wrap_model_call - 重试逻辑
@wrap_model_call
def retry_on_error(request, handler):
    for attempt in range(3):
        try:
            return handler(request)
        except Exception:
            if attempt == 2:
                raise

# 3. dynamic_prompt - 动态系统提示
@dynamic_prompt
def context_aware_prompt(request: ModelRequest) -> str:
    if len(request.state["messages"]) > 10:
        return "Be concise."
    return "You are a helpful assistant."

# 4. 使用
agent = create_agent(
    model="openai:gpt-4o",
    middleware=[log_conversation_length, retry_on_error, context_aware_prompt],
)
```

### 12.4 hook_config 装饰器

```python
@hook_config(can_jump_to=["end", "model"])
def before_model(self, state, runtime):
    ...
```

`hook_config` 装饰器在函数对象上设置 `__can_jump_to__` 元数据，工厂函数读取这些元数据来配置图的跳转边。

---

## 13. 结构化输出系统

### 13.1 三种策略

| 策略 | 实现方式 | 适用模型 |
|------|---------|---------|
| `ToolStrategy` | 将 schema 转为工具定义，模型通过 tool_call 返回结构化数据 | 所有支持 tool calling 的模型 |
| `ProviderStrategy` | 使用模型提供商的 native structured output API | OpenAI gpt-4o 等支持 native 模式的模型 |
| `AutoStrategy` | 自动检测模型能力，选择最佳策略 | 不确定模型是否支持 provider策略时 |

### 13.2 策略自动检测

`_supports_provider_strategy` 函数检查模型是否支持 provider native 结构化输出：

1. 检查模型的 `profile` 字典中的 `structured_output` 字段
2. 对不知道 profile 的模型，使用 `FALLBACK_MODELS_WITH_STRUCTURED_OUTPUT` 列表
3. Gemini 模型特殊处理：Gemini 2.x 不支持同时使用 tool use 和 structured output，Gemini 3.x 支持

### 13.3 错误处理

`ToolStrategy` 支持可配置的错误处理：
- `True`: 捕获所有错误，用默认错误模板重试
- `str`: 自定义错误消息
- `type[Exception]` 或 `tuple[type[Exception], ...]`: 只捕获特定异常
- `Callable[[Exception], str]`: 自定义错误格式化
- `False`: 不重试，让异常传播

### 13.4 多 schema 支持

`ToolStrategy` 支持 Union 类型和 JSON Schema 的 `oneOf`，自动拆分为多个工具：

```python
def _iter_variants(schema):
    if get_origin(schema) in {UnionType, Union}:
        for arg in get_args(schema):
            yield from _iter_variants(arg)
        return
    if isinstance(schema, dict) and "oneOf" in schema:
        for sub in schema.get("oneOf", []):
            yield from _iter_variants(sub)
        return
    yield schema
```

---

## 14. 设计模式与架构洞察

### 14.1 架构优点

**1. 洋葱模型的高度灵活性**

中间件可以通过简单的 `handler(request)` 回调获得完全控制权：短路、重试、修改请求/响应、注入额外数据。这种模式在 Express.js、ASP.NET Core 等 Web 框架中已被证明极为有效。

**2. 关注点分离**

系统将三种核心关注点拆分为正交机制：

- **生命周期钩子** (`before_model`/`after_model`)：状态级干预，如计数、日志、条件跳转
- **模型调用拦截** (`wrap_model_call`)：模型级干预，如重试、回退、提示词注入
- **工具调用拦截** (`wrap_tool_call`)：工具级干预，如模拟、重试、修改参数

**3. 不可变请求模式**

`ModelRequest.override()` 确保中间件不会意外修改其他层的数据，这也是洋葱模型的关键保证。

**4. 装饰器驱动的零样板快速开发**

7 个装饰器覆盖了最常见的中间件场景，开发者无需定义完整的类。

**5. LangGraph 深度集成**

系统充分利用了 LangGraph 的高级特性：
- `EphemeralValue` channel 实现 jump_to
- `Send` API 实现并行工具调用
- `interrupt()` API 实现人机交互
- `RunnableCallable` 实现同步/异步双轨

**6. 类型安全与泛型**

从 `AgentMiddleware[StateT, ContextT, ResponseT]` 到 `ModelRequest[ContextT]`，泛型贯穿始终，提供编译时类型检查。

**7. 持久化与检查点**

通过 LangGraph 的 `checkpointer` 和 `store`，Agent 状态天然支持持久化和跨会话恢复。

### 14.2 可改进之处

**1. 中间件加载顺序的隐含依赖**

洋葱模型的执行顺序完全取决于 `middleware` 列表顺序，但某些中间件之间存在隐含依赖（如重试中间件通常应该在最外层）。目前没有机制检测或警告不合理的顺序。

**2. Command 传递链的复杂性**

`_ComposedExtendedModelResponse.commands` 累积机制提供了灵活性，但调试中间件链中的 Command 传递可能很复杂。

**3. 异步支持的冗余**

同时维护同步和异步两套组合函数增加了代码量。虽然有明确原因（不能假设同步代码在异步环境中安全），但维护负担较高。

**4. wrap_model_call 的 Command 限制**

目前 `wrap_model_call` 中间件不支持返回 `Command(goto=...)`、`Command(resume=...)` 等类型，会显式抛出 `NotImplementedError`。

### 14.3 设计模式总结

| 模式 | 体现位置 |
|------|---------|
| **洋葱/中间件管道模式** | `_chain_model_call_handlers`, `_chain_tool_call_wrappers` |
| **工厂模式** | `create_agent()`, `@before_model` 等装饰器 |
| **策略模式** | `ToolStrategy`, `ProviderStrategy`, `AutoStrategy` |
| **模板方法模式** | `AgentMiddleware` 基类钩子方法 |
| **装饰器模式** | `@before_model`, `@wrap_model_call` 等 |
| **不可变模式** | `ModelRequest.override()` |
| **观察者模式** | 生命周期钩子 (`before_model`, `after_model` 等) |
| **命令模式** | `Command`, `ExtendedModelResponse.command` |
| **Builder 模式** | `StateGraph.add_node().add_edge().compile()` |
| **责任链模式** | 中间件节点链 (`before_agent[0] -> before_agent[1] -> ...`) |

### 14.4 关键数据流

```
User Input {messages: [...]}
  ↓
[before_agent hooks] → 可能注入初始消息、启动资源
  ↓
┌── Agent Loop ────────────────────────────┐
│  [before_model hooks] → 摘要检查、调用限制、PII清理  │
│       ↓                                    │
│  [wrap_model_call onion] → 工具选择、重试、回退、提示注入
│       ↓                                    │
│  model.invoke(messages) → AIMessage(可能有tool_calls)
│       ↓                                    │
│  [after_model hooks] → HITL审批、工具调用限制、PII检查
│       ↓                                    │
│  [condition: has tool_calls?]             │
│    YES → [wrap_tool_call onion] → tools.execute()
│           ↓                                │
│    [condition: back to before_model or exit?]
│    NO → exit loop                          │
└──────────────────────────────────────────┘
  ↓
[after_agent hooks] → 清理资源、记录日志
  ↓
Final State {messages: [...], structured_response: ...}
```

---

## 附录A: 文件行数统计

| 文件 | 行数 | 核心职责 |
|------|------|---------|
| `factory.py` | ~1886 | Agent 工厂函数、图构建、洋葱模型组合 |
| `types.py` | ~2053 | AgentMiddleware 基类、AgentState、装饰器 |
| `summarization.py` | ~679 | 上下文摘要中间件 |
| `shell_tool.py` | ~883 | 持久化 Shell 工具中间件 |
| `_execution.py` | ~386 | Shell 执行策略（Host/Docker/Codex） |
| `_redaction.py` | ~455 | PII 检测和脱敏工具 |
| `tool_call_limit.py` | ~489 | 工具调用限制中间件 |
| `tool_retry.py` | ~404 | 工具重试中间件 |
| `model_retry.py` | ~313 | 模型重试中间件 |
| `todo.py` | ~346 | Todo 列表中间件 |
| `tool_selection.py` | ~359 | LLM 工具选择中间件 |
| `context_editing.py` | ~299 | 上下文编辑中间件 |
| `human_in_the_loop.py` | ~413 | 人机交互中间件 |
| `pii.py` | ~377 | PII 检测中间件 |
| `model_call_limit.py` | ~268 | 模型调用限制中间件 |
| `tool_emulator.py` | ~210 | 工具模拟中间件 |
| `model_fallback.py` | ~139 | 模型回退中间件 |
| `file_search.py` | ~389 | 文件搜索中间件 |
| `_retry.py` | ~124 | 重试公共基础设施 |
| `structured_output.py` | ~463 | 结构化输出策略系统 |

---

## 附录B: 参考资料

- LangChain V1 源码: `/home/lyjew/Documents/github/langchain/libs/langchain_v1/`
- LangChain Core: `/home/lyjew/Documents/github/langchain/libs/core/`
- LangGraph 文档: https://langchain-ai.github.io/langgraph/
- LangChain Agent 文档: https://docs.langchain.com/oss/python/langchain/agents
