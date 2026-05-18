# LangChain 工具系统深度分析

## 一、概述与架构定位

LangChain 的 Tool（工具）系统是 Agent 与环境交互的核心机制。工具是 Agent 用来执行外部操作的组件——从简单的数学计算到复杂的 API 调用、数据库查询和文档检索。整个工具系统位于 `langchain_core/tools/` 包下，与 `langchain_core/runnables/`（可运行组件）、`langchain_core/messages/`（消息系统）深度集成。

工具系统本质上是一个"函数即服务"的抽象层：它将任意 Python 函数（同步/异步）或 Runnable 对象包装成一个带有描述、参数 schema、错误处理和回调机制的标准化组件，使其能够被 LLM 识别和调用。

## 二、类继承层次结构

### 2.1 核心继承链

```
RunnableSerializable (langchain_core.runnables)
    └── BaseTool                     # 所有工具的抽象基类
            ├── Tool                 # 单输入工具（简单函数包装）
            └── StructuredTool       # 多输入工具（结构化参数）

BaseModel (pydantic)
    └── BaseToolkit                  # 工具包基类（聚合多个工具）
```

`BaseTool` 继承自 `RunnableSerializable[str | dict | ToolCall, Any]`，这意味着：
- 每个工具本身就是一个 Runnable（可运行组件），可以与其他 Runnable 组合（通过 pipe `|` 操作符、链式调用等）
- 输入类型为 `str | dict | ToolCall`——工具可以接受字符串、字典或完整的 ToolCall 结构
- 输出类型为 `Any`——工具可以返回任意类型的结果

### 2.2 辅助类

| 类名 | 作用 |
|------|------|
| `InjectedToolArg` | 标记"运行时注入"参数的注解基类 |
| `InjectedToolCallId` | 继承自 `InjectedToolArg`，专门注入 `tool_call_id` |
| `_DirectlyInjectedToolArg` | 直接类型注解方式的注入标记（如 `ToolRuntime`） |
| `ToolException` | 工具执行异常（可被 `handle_tool_error` 优雅处理） |
| `SchemaAnnotationError` | args_schema 类型注解错误时抛出 |
| `_SchemaConfig` | 控制 Pydantic 模型的配置：禁止额外字段、允许任意类型 |

## 三、BaseTool：核心抽象

### 3.1 关键字段

`BaseTool` 定义的字段构成了工具的声明式接口：

- **`name: str`** —— 工具的唯一名称，LLM 用它来选择工具
- **`description: str`** —— 工具的描述，LLM 用它来判断何时/为何使用该工具
- **`args_schema: ArgsSchema | None`** —— 输入参数的 Pydantic 模型类或 JSON Schema dict
- **`return_direct: bool`** —— 是否直接返回结果（跳过 Agent 循环）
- **`handle_tool_error: bool | str | Callable`** —— 发生 `ToolException` 时的处理策略
- **`handle_validation_error: bool | str | Callable`** —— 校验失败时的处理策略
- **`response_format: Literal["content", "content_and_artifact"]`** —— 输出格式
- **`extras: dict | None`** —— 提供商特定的额外字段（如 Anthropic 的 `cache_control`）
- **`callbacks / tags / metadata`** —— 可观测性基础设施

### 3.2 工具接口声明机制

工具如何向 LLM 声明其接口是整个系统的核心设计问题。LangChain 提供三层 schema 获取机制：

**第一层：`tool_call_schema` 属性** —— 返回"仅用于 LLM 调用"的 schema，自动过滤掉 `InjectedToolArg` 类型的注入参数。这是给 LLM 看的"菜单"。

**第二层：`get_input_schema()` 方法** —— 返回完整的输入 Pydantic 模型（包含所有参数）。这是给框架内部验证用的完整规格。

**第三层：`args` 属性** —— 返回参数的 JSON Schema `properties` 字典。这是给渲染器用的简化格式。

这三个方法构成一个分层暴露模式：`tool_call_schema` 是给 LLM 看的（不含注入参数），`get_input_schema` 是给框架验证用的（包含所有参数），`args` 是给渲染器用的。

### 3.3 注入参数体系

`InjectedToolArg` 是 LangChain 工具系统的一个精妙设计。它解决了"某些参数由框架运行时注入、不应暴露给 LLM"的问题。

使用方式：

```python
from typing import Annotated
from langchain_core.tools import tool, InjectedToolArg

@tool
def my_tool(
    query: str,                              # LLM 生成此参数
    config: Annotated[dict, InjectedToolArg]  # 框架注入，LLM 不会看到
) -> str:
    ...
```

`_is_injected_arg_type()` 函数检测一个类型是否为注入参数：
- 如果类型直接继承自 `_DirectlyInjectedToolArg`（如 `ToolRuntime`)
- 如果类型被 `Annotated[X, InjectedToolArg]` 包装
- 可选的 `injected_type` 参数允许检测特定子类型（如 `InjectedToolCallId`）

### 3.4 执行流程

`BaseTool.run()` 方法是工具执行的完整管道：

```
run(tool_input, ...)
  │
  ├─ CallbackManager.configure()         # 配置回调管理器
  ├─ callback_manager.on_tool_start()    # 触发开始回调
  ├─ _to_args_and_kwargs()              # 将输入转为 (*args, **kwargs)
  │   └─ _parse_input()                 # 使用 args_schema 验证输入
  │       ├─ 字符串输入 → 单参数模式
  │       └─ 字典输入 → Pydantic 验证
  ├─ self._run(*tool_args, **tool_kwargs) # 执行实际逻辑
  │   └─ 可选的 run_manager 注入
  │   └─ 可选的 RunnableConfig 注入
  ├─ _format_output()                    # 格式化输出为 ToolMessage
  ├─ callback_manager.on_tool_end()      # 触发结束回调
  └─ 返回 output
```

错误处理的层级：
1. `ValidationError` —— 由 `handle_validation_error` 处理
2. `ToolException` —— 由 `handle_tool_error` 处理
3. 其他 `Exception` / `KeyboardInterrupt` —— 直接抛出

### 3.5 输出格式化

`_format_output()` 函数的输出适配逻辑：
- 如果 `content` 已经是 `ToolOutputMixin` 实例，直接返回
- 如果没有 `tool_call_id`，返回原始 `content`
- 否则包装成 `ToolMessage(content, artifact, tool_call_id, name, status)`

## 四、StructuredTool：多输入工具

`StructuredTool` 继承自 `BaseTool`，代表可接受多个结构化输入的工具。它通过 `from_function()` 类方法创建：

关键实现细节：
1. 如果 `args_schema` 为 `None` 且 `infer_schema=True`，调用 `create_schema_from_function()` 自动推断
2. 如果 `description` 为 `None` 且不解析 docstring，使用函数的 `__doc__`
3. 如果 `description` 仍为 `None`，尝试从 `args_schema.__doc__` 获取
4. `_run()` 方法委派给 `self.func(*args, **kwargs)`
5. `_arun()` 方法优先使用 `self.coroutine`，否则回退到线程池执行 `_run()`

## 五、Tool（SimpleTool）：单输入工具

`Tool` 是 `BaseTool` 的另一个具体子类，设计用于接受单一字符串输入的传统工具。

与 `StructuredTool` 的关键区别：
1. `args` 属性：如果 `args_schema` 为 `None`，返回 `{"tool_input": {"type": "string"}}`（向后兼容）
2. `_to_args_and_kwargs()`：强制检查输入参数数量必须为 1
3. `from_function()` 签名：`name` 和 `description` 是必需参数（不是可选）

`Tool` 类现在主要作为向后兼容层存在——新代码应优先使用 `StructuredTool`。

## 六、@tool 装饰器

`tool()` 函数是整个系统最常用的用户接口，支持 5 种调用模式：

### 6.1 五种调用模式

**模式 1：无参数装饰器** — `@tool`直接装饰函数，提取 `__name__` 作为工具名。

**模式 2：命名装饰器** — `@tool("search")`，传入字符串作为工具名。

**模式 3：带参数装饰器** — `@tool(return_direct=True, parse_docstring=True)`，通过闭包捕获参数。

**模式 4：命名+参数装饰器** — `@tool("search", return_direct=True)`。

**模式 5：Runnable 转换** — `tool("my_tool", my_runnable)`，将任何 Runnable 转为工具。

### 6.2 Google 风格 docstring 解析

当 `parse_docstring=True` 时，工具系统会解析 Google 风格的 docstring，自动提取参数描述生成完整的 JSON Schema。如果 docstring 包含函数签名中没有的参数，默认抛出 `ValueError`。

## 七、工具与消息系统的集成

### 7.1 ToolMessage

`ToolMessage` 代表工具执行结果的消息：

- `tool_call_id`：关联的 ToolCall ID
- `content`：发送给 LLM 的内容（文本或结构化数据）
- `artifact`：工具执行的全部输出（可能包含图像、调试信息等），不发送给模型但可在下游使用
- `status`：`"success"` 或 `"error"`

这种分离允许 Agent 将"给 LLM 看的内容"和"给下游代码用的内容"分开管理。

### 7.2 ToolCall

`ToolCall` 是一个 `TypedDict`，代表 LLM 调用工具的请求：

```python
class ToolCall(TypedDict):
    name: str                   # 工具名
    args: dict[str, Any]        # 参数字典
    id: str | None              # 唯一标识（用于并行调用匹配）
    type: NotRequired[Literal["tool_call"]]
```

### 7.3 AIMessage 中的工具调用集成

`AIMessage` 有三个与工具相关的字段：
- `tool_calls: list[ToolCall]`：解析成功的工具调用
- `invalid_tool_calls: list[InvalidToolCall]`：解析失败的工具调用（不抛异常，优雅降级）
- `usage_metadata: UsageMetadata | None`

## 八、设计模式总结

### 8.1 模板方法模式
`BaseTool` 的 `run()` 方法定义了工具执行的完整算法骨架（回调 → 解析 → 执行 → 格式化 → 回调），将实际业务逻辑延迟到子类的 `_run()` / `_arun()` 方法中实现。

### 8.2 策略模式
通过 `handle_tool_error` 和 `handle_validation_error` 字段，用户可以注入不同的错误处理策略（忽略、返回固定消息、调用自定义函数）。

### 8.3 依赖注入
通过 `InjectedToolArg` 和 `InjectedToolCallId` 注解实现运行时参数的注入。工具函数声明"我想要什么"（注解），框架负责在运行时"给什么"（注入）。

### 8.4 适配器模式
`_format_output()` 函数将工具执行的原始输出适配为标准化的 `ToolMessage`。不同的输出类型被适配为统一的格式。

### 8.5 工厂方法模式
`StructuredTool.from_function()` 和 `Tool.from_function()` 是工厂方法，负责从普通函数创建配置好的工具实例。

### 8.6 装饰器模式
`@tool` 装饰器将普通 Python 函数透明地"升级"为功能完备的工具对象，同时保持函数原本的签名和行为。

### 8.7 分层 schema 暴露
工具定义了三个不同层级的 schema 暴露：给 LLM 看的、给框架验证的、给渲染器用的。这是信息隐藏原则在 AI 系统中的精妙应用。

> 原始分析来源于对 LangChain `libs/core/langchain_core/tools/` 和 `libs/core/langchain_core/messages/` 目录下全部源码的深度阅读。
