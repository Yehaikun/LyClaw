# LangChain Runnable 引擎深度源码分析

## 目录

1. [整体架构概览](#1-整体架构概览)
2. [Runnable 接口设计](#2-runnable-接口设计)
3. [配置系统 (RunnableConfig)](#3-配置系统-runnableconfig)
4. [LCEL：LangChain 表达式语言](#4-lcellangchain-表达式语言)
5. [RunnableSequence：顺序链式执行](#5-runnablesequence顺序链式执行)
6. [RunnableParallel：并行执行](#6-runnableparallel并行执行)
7. [RunnableBranch：条件路由](#7-runnablebranch条件路由)
8. [RunnableLambda：函数包装器](#8-runnablelambd函数包装器)
9. [RunnableBinding：配置绑定](#9-runnablebinding配置绑定)
10. [回调系统 (CallbackManager)](#10-回调系统-callbackmanager)
11. [流事件系统 (Stream Events)](#11-流事件系统-stream-events)
12. [辅助类：RunnablePassthrough、RunnableAssign、RunnablePick](#12-辅助类runnablepassthroughrunnableassignrunnablepick)
13. [动态配置：configurable_fields 和 configurable_alternatives](#13-动态配置configurable_fields-和-configurable_alternatives)
14. [执行流程全链路追踪](#14-执行流程全链路追踪)
15. [设计模式总结](#15-设计模式总结)

---

## 1. 整体架构概览

LangChain 的 Runnable 引擎是整个 LangChain 框架的核心编排层。它的核心文件位于：

```
libs/core/langchain_core/runnables/
    base.py          (6574行) — Runnable 基类、RunnableSequence、RunnableParallel、
                                  RunnableLambda、RunnableBinding 等核心类
    config.py        (672行)  — RunnableConfig 类型定义及配置工具函数
    schema.py        (188行)  — StreamEvent、EventData 等流事件类型定义
    branch.py        (461行)  — RunnableBranch 条件路由
    passthrough.py   (841行)  — RunnablePassthrough、RunnableAssign、RunnablePick
    configurable.py  (716行)  — DynamicRunnable、RunnableConfigurableFields 等
    fallbacks.py     (664行)  — RunnableWithFallbacks 容错机制
    retry.py         (379行)  — 重试策略
    history.py       (631行)  — RunnableWithMessageHistory 对话历史
    router.py        (239行)  — RouterRunnable 路由器
    utils.py         (779行)  — AddableDict 等工具类
    graph.py         (739行)  — 图表示（用于可视化）

libs/core/langchain_core/callbacks/
    manager.py       (~2400行) — CallbackManager、AsyncCallbackManager 生命周期管理
    base.py          — BaseCallbackHandler、BaseCallbackManager 抽象基类
```

核心设计理念：

- **一切皆 Runnable**：任何可执行单元都是 `Runnable[Input, Output]`
- **声明式组合**：通过 `|` 运算符（管道）自由组合
- **自动双模**：同步/异步、单次/批量/流式全部自动支持
- **全链路追踪**：通过回调系统和 LangSmith 实现透明的执行追踪

类层次结构：

```
Runnable(ABC, Generic[Input, Output])          — 抽象基类
  ├── RunnableSerializable(Serializable)        — 可序列化 Runnable
  │     ├── RunnableSequence                    — 顺序链
  │     ├── RunnableParallel (别名 RunnableMap)  — 并行映射
  │     ├── RunnableEachBase                    — 逐元素执行
  │     ├── RunnableBindingBase                 — 绑定基类
  │     │     └── RunnableBinding              — 配置/参数绑定
  │     ├── RunnablePassthrough                — 透传
  │     ├── RunnableAssign                     — 字段追加
  │     ├── RunnablePick                        — 字段提取
  │     ├── DynamicRunnable                     — 动态配置基类
  │     │     ├── RunnableConfigurableFields   — 可配置字段
  │     │     └── RunnableConfigurableAlternatives — 可配置替代
  │     └── RunnableBranch                      — 条件分支
  ├── RunnableLambda                            — 函数包装
  └── RunnableGenerator                         — 生成器包装
```

---

## 2. Runnable 接口设计

### 2.1 核心抽象

源码位置：`base.py:125`

```python
class Runnable(ABC, Generic[Input, Output]):
    """A unit of work that can be invoked, batched, streamed, transformed and composed."""
```

`Runnable` 是一个泛型抽象基类，带有两个类型参数 `Input` 和 `Output`。这意味着每个 `Runnable` 都有明确的输入/输出类型，编译器（mypy/pyright）可以对其进行类型检查。

### 2.2 六大核心方法

| 方法 | 签名 | 说明 |
|------|------|------|
| `invoke` | `(input: Input, config?: RunnableConfig) -> Output` | 同步单次执行 |
| `ainvoke` | `(input: Input, config?: RunnableConfig) -> Awaitable[Output]` | 异步单次执行 |
| `batch` | `(inputs: list[Input], config?, *, return_exceptions?) -> list[Output]` | 同步批量执行 |
| `abatch` | `(inputs: list[Input], config?, *, return_exceptions?) -> Awaitable[list[Output]]` | 异步批量执行 |
| `stream` | `(input: Input, config?) -> Iterator[Output]` | 同步流式执行 |
| `astream` | `(input: Input, config?) -> AsyncIterator[Output]` | 异步流式执行 |

此外还有：

| 方法 | 说明 |
|------|------|
| `transform` / `atransform` | 流到流的转换（输入是 Iterator，输出是 Iterator） |
| `batch_as_completed` / `abatch_as_completed` | 批量执行，结果按完成顺序返回 |
| `astream_events` / `stream_events` | 流式事件（含中间结果、自定义事件） |
| `astream_log` | 流式日志（已弃用） |

### 2.3 默认实现策略

源码位置：`base.py:868-917`（batch）、`base.py:1003-1049`（abatch）

**invoke**: 抽象方法，子类必须实现。

**ainvoke** 的默认实现（`base.py:845-866`）：
```python
async def ainvoke(self, input, config=None, **kwargs):
    return await run_in_executor(config, self.invoke, input, config, **kwargs)
```

即将同步 `invoke` 放到线程池中执行。这是桥接同步/异步的关键模式。

**batch** 的默认实现（`base.py:868-916`）：
```python
def batch(self, inputs, config=None, *, return_exceptions=False, **kwargs):
    configs = get_config_list(config, len(inputs))
    with get_executor_for_config(configs[0]) as executor:
        return list(executor.map(invoke, inputs, configs))
```

使用 `ContextThreadPoolExecutor`（继承自 `ThreadPoolExecutor`）并行调用 `invoke`。注意这里使用了 `copy_context()` 来保持上下文变量（contextvars）的传递。

**stream** 的默认实现（`base.py:1131-1150`）：
```python
def stream(self, input, config=None, **kwargs):
    yield self.invoke(input, config, **kwargs)
```

简单地将 `invoke` 的结果作为单个 chunk yield。

### 2.4 类型推断系统

`Runnable` 提供了自动的类型推断机制，使 `InputType` 和 `OutputType` 可以从泛型参数中获取。

源码位置：`base.py:300-364`

```python
@property
def InputType(self) -> type[Input]:
    # 从 Pydantic 泛型元数据中查找
    for base in self.__class__.mro():
        if hasattr(base, "__pydantic_generic_metadata__"):
            metadata = base.__pydantic_generic_metadata__
            if "args" in metadata and len(metadata["args"]) == 2:
                return cast("type[Input]", metadata["args"][0])
    # 从 __orig_bases__ 中查找
    for cls in self.__class__.__orig_bases__:
        type_args = get_args(cls)
        if type_args and len(type_args) == 2:
            return cast("type[Input]", type_args[0])
    raise TypeError(...)
```

这使得链式组合时类型可以自动推导，例如 `RunnableSequence[RunnableLambda[int, str], RunnableLambda[str, float]]` 的 InputType 为 `int`，OutputType 为 `float`。

### 2.5 Schema 系统

每个 Runnable 还可以生成 Pydantic schema，用于输入/输出验证和可视化。

- `get_input_schema(config)` -> Pydantic Model
- `get_output_schema(config)` -> Pydantic Model
- `config_schema(include)` -> Pydantic Model (可配置参数的 schema)
- `get_graph(config)` -> Graph (用于生成可视化图)

---

## 3. 配置系统 (RunnableConfig)

源码位置：`config.py:49-121`

### 3.1 配置结构

```python
class RunnableConfig(TypedDict, total=False):
    tags: list[str]                          # 标签，用于过滤和追踪
    metadata: dict[str, Any]                 # 元数据，JSON 可序列化
    callbacks: Callbacks                     # 回调处理器列表或管理器
    run_name: str                            # 本次运行的名称
    max_concurrency: int | None              # 最大并行数
    recursion_limit: int                     # 递归深度限制（默认 25）
    configurable: dict[str, Any]             # 运行时动态配置
    run_id: uuid.UUID | None                 # 运行唯一标识
```

关键设计：`total=False` 意味着所有字段都是可选的，方便部分配置的合并。

### 3.2 配置合并机制

源码位置：`config.py:391-454`

`merge_configs(*configs)` 函数实现了智能合并：

- **tags**: 合并后排序去重
- **metadata**: 浅合并（后覆盖前）
- **configurable**: 浅合并
- **callbacks**: 支持 6 种合并情况（None/list/manager 两两组合）
- **recursion_limit**: 仅当不为默认值时才覆盖

### 3.3 配置传播：ContextVar 机制

源码位置：`config.py:166-244`

LangChain 使用 Python 的 `ContextVar` 来实现配置在调用栈中的隐式传播：

```python
var_child_runnable_config: ContextVar[RunnableConfig | None] = ContextVar(
    "child_runnable_config", default=None
)
```

当一个子 Runnable 被调用时，父 Runnable 通过 `set_config_context(config)` 将配置设置到 ContextVar 中。子 Runnable 在 `ensure_config()` 时会自动从 ContextVar 中读取父配置并合并。这意味着你在链的任意深度都可以访问到完整的配置（tags、metadata、callbacks 等），而不需要手动逐层传递。

`ensure_config(config)` 的工作流程（`config.py:247-300`）：
1. 创建包含默认值的空配置
2. 如果 ContextVar 中有值，合并进来
3. 如果传入了显式 config，再合并
4. 将未知键放入 `configurable` 字段

### 3.4 线程池执行器：ContextThreadPoolExecutor

源码位置：`config.py:567-616`

```python
class ContextThreadPoolExecutor(ThreadPoolExecutor):
    def submit(self, func, *args, **kwargs):
        return super().submit(
            partial(copy_context().run, func, *args, **kwargs)
        )
```

继承自 `ThreadPoolExecutor`，在提交任务时自动使用 `copy_context().run()` 包装，确保 ContextVar（如 `var_child_runnable_config`）能正确传播到工作线程中。

---

## 4. LCEL：LangChain 表达式语言

### 4.1 管道运算符 |

源码位置：`base.py:619-659`

```python
def __or__(self, other) -> RunnableSerializable[Input, Other]:
    return RunnableSequence(self, coerce_to_runnable(other))

def __ror__(self, other) -> RunnableSerializable[Other, Output]:
    return RunnableSequence(coerce_to_runnable(other), self)
```

`|` 运算符被重载为创建 `RunnableSequence`。Python 在处理 `runnable_a | runnable_b` 时：
1. 先尝试 `runnable_a.__or__(runnable_b)`
2. 若不支持，再尝试 `runnable_b.__ror__(runnable_a)`

这使得非 Runnable 对象（如普通函数、字典）也可以参与链式组合。

### 4.2 coerce_to_runnable：自动转换

源码位置：`base.py:6489-6513`

```python
def coerce_to_runnable(thing: RunnableLike) -> Runnable[Input, Output]:
    if isinstance(thing, Runnable):
        return thing
    if is_async_generator(thing) or inspect.isgeneratorfunction(thing):
        return RunnableGenerator(thing)
    if callable(thing):
        return RunnableLambda(cast("Callable[[Input], Output]", thing))
    if isinstance(thing, dict):
        return cast("Runnable[Input, Output]", RunnableParallel(thing))
    raise TypeError(...)
```

这是 LCEL 的"魔法"所在。当你写 `prompt | model | StrOutputParser()` 时，`model` 已经是 Runnable，`StrOutputParser()` 也是 Runnable，而当你写：

```python
chain = runnable | {
    "mul_2": RunnableLambda(lambda x: x * 2),
    "mul_5": RunnableLambda(lambda x: x * 5),
}
```

这里的 `{...}` 字典被 `coerce_to_runnable` 转换为 `RunnableParallel`。

`RunnableLike` 类型联合（`base.py:6475-6486`）：
```python
RunnableLike = (
    Runnable[Input, Output]
    | Callable[[Input], Output]
    | Callable[[Input], Awaitable[Output]]
    | Callable[[Iterator[Input]], Iterator[Output]]
    | Callable[[AsyncIterator[Input]], AsyncIterator[Output]]
    | Mapping[str, Any]
)
```

### 4.3 组合示例

```python
# 基本链式调用
chain = prompt | model | output_parser

# 并行分支
chain = prompt | model | {
    "joke": joke_chain,
    "poem": poem_chain,
}

# 混合使用
chain = (
    RunnablePassthrough.assign(question=lambda x: x["input"])
    | prompt
    | model
    | StrOutputParser()
)

# 等价于显式构造
chain = RunnableSequence(
    RunnablePassthrough.assign(question=lambda x: x["input"]),
    prompt,
    model,
    StrOutputParser(),
)
```

---

## 5. RunnableSequence：顺序链式执行

源码位置：`base.py:2995-3741`

### 5.1 数据结构

```python
class RunnableSequence(RunnableSerializable[Input, Output]):
    first: Runnable[Input, Any]              # 第一步
    middle: list[Runnable[Any, Any]]         # 中间步骤
    last: Runnable[Any, Output]              # 最后一步
```

将步骤分为 `first`、`middle`、`last` 三部分的原因是为了进行精确的类型标注：`first` 决定 `Input`，`last` 决定 `Output`。

构造函数（`base.py:3089-3128`）会自动展平嵌套的 `RunnableSequence`：

```python
def __init__(self, *steps, name=None, first=None, middle=None, last=None):
    steps_flat = []
    for step in steps:
        if isinstance(step, RunnableSequence):
            steps_flat.extend(step.steps)  # 展平嵌套链
        else:
            steps_flat.append(coerce_to_runnable(step))
```

### 5.2 invoke 执行流程

源码位置：`base.py:3308-3342`

```
1. ensure_config(config)
2. 创建 CallbackManager
3. 触发 on_chain_start 回调
4. for each step:
     a. patch_config(config, callbacks=run_manager.get_child("seq:step:{i+1}"))
     b. 设置 ContextVar (set_config_context)
     c. 调用 step.invoke(input_, config)
5. 触发 on_chain_end 回调
   或 on_chain_error（如果异常）
```

关键代码：
```python
def invoke(self, input, config=None, **kwargs):
    config = ensure_config(config)
    callback_manager = get_callback_manager_for_config(config)
    run_manager = callback_manager.on_chain_start(
        None, input, name=config.get("run_name") or self.get_name(),
        run_id=config.pop("run_id", None),
    )
    input_ = input
    try:
        for i, step in enumerate(self.steps):
            config = patch_config(
                config, callbacks=run_manager.get_child(f"seq:step:{i + 1}")
            )
            with set_config_context(config) as context:
                if i == 0:
                    input_ = context.run(step.invoke, input_, config, **kwargs)
                else:
                    input_ = context.run(step.invoke, input_, config)
    except BaseException as e:
        run_manager.on_chain_error(e)
        raise
    else:
        run_manager.on_chain_end(input_)
        return cast("Output", input_)
```

注意：kwargs 只在第一步传递，后续步骤只传递 config。

### 5.3 batch 执行流程

源码位置：`base.py:3384-3510`

`RunnableSequence.batch()` 不是简单的对每个输入调用 `invoke`，而是**对每一步调用 `batch` 方法**。这意味着如果链中的某一步（如 LLM）支持批量优化，可以直接利用：

```
for each step in sequence:
    inputs = step.batch(inputs, configs)  # 利用 step 自身的批量优化
```

这比先调用 step1.invoke 再调用 step2.invoke ... 要高效得多，因为每一步都可以做内部并行。

### 5.4 stream / transform 执行流程

源码位置：`base.py:3643-3741`

```python
def _transform(self, inputs, run_manager, config, **kwargs):
    final_pipeline = cast("Iterator[Output]", inputs)
    for idx, step in enumerate(steps):
        config = patch_config(config, callbacks=run_manager.get_child(f"seq:step:{idx + 1}"))
        if idx == 0:
            final_pipeline = step.transform(final_pipeline, config, **kwargs)
        else:
            final_pipeline = step.transform(final_pipeline, config)
    yield from final_pipeline
```

这里的关键是 `transform` 方法：它是流到流的映射（`Iterator[Input] -> Iterator[Output]`）。每个步骤的 `transform` 方法接收一个输入迭代器并返回一个输出迭代器。当所有步骤都支持 `transform` 时，整个链就是真正流式的（数据像水一样流过每个步骤）。

如果某一步不支持 `transform`（如 `RunnableLambda`），其默认 `transform` 实现会将整个输入缓冲到内存中，然后在所有输入就绪后才开始输出。这会在该步骤处产生"阻塞点"，但流式在它之后可以继续。

### 5.5 管道运算符优化

`RunnableSequence.__or__` 在遇到另一 `RunnableSequence` 时会直接展平：

```python
def __or__(self, other):
    if isinstance(other, RunnableSequence):
        return RunnableSequence(
            self.first, *self.middle, self.last,
            other.first, *other.middle, other.last,
        )
```

这避免了 `(A | B) | (C | D)` 产生嵌套的 `RunnableSequence(RunnableSequence(A, B), RunnableSequence(C, D))` 结构。

---

## 6. RunnableParallel：并行执行

源码位置：`base.py:3743-4271`

### 6.1 数据结构

```python
class RunnableParallel(RunnableSerializable[Input, dict[str, Any]]):
    steps__: Mapping[str, Runnable[Input, Any]]
```

`RunnableMap` 是 `RunnableParallel` 的别名（`base.py:4271`）。

构造函数接受两种形式：
- 位置参数：一个 dict
- 关键字参数：`key=value` 对

```python
def __init__(self, steps__=None, **kwargs):
    merged = {**steps__} if steps__ is not None else {}
    merged.update(kwargs)
    super().__init__(steps__={key: coerce_to_runnable(r) for key, r in merged.items()})
```

### 6.2 invoke 执行流程

源码位置：`base.py:4011-4069`

```
1. ensure_config(config)
2. 创建 CallbackManager 和 run_manager
3. 为每个步骤提交到线程池中并行执行
4. 等待所有 Future 完成
5. 组装结果 dict
6. 触发 on_chain_end 或 on_chain_error
```

```python
def invoke(self, input, config=None, **kwargs):
    config = ensure_config(config)
    # ...
    def _invoke_step(step, input_, config, key):
        child_config = patch_config(
            config, callbacks=run_manager.get_child(f"map:key:{key}"),
        )
        with set_config_context(child_config) as context:
            return context.run(step.invoke, input_, child_config)

    with get_executor_for_config(config) as executor:
        futures = [
            executor.submit(_invoke_step, step, input, config, key)
            for key, step in steps.items()
        ]
        output = {key: future.result()
                  for key, future in zip(steps, futures)}
```

并行度由 `config["max_concurrency"]` 控制，通过 `ContextThreadPoolExecutor` 来实现。

### 6.3 ainvoke 执行流程

异步版本（`base.py:4071-4124`）使用 `asyncio.gather` 实现真正的异步并发：

```python
async def ainvoke(self, input, config=None, **kwargs):
    # ...
    results = await asyncio.gather(
        *(_ainvoke_step(step, input, config, key)
          for key, step in steps.items())
    )
    output = dict(zip(steps, results))
```

### 6.4 stream / transform 执行流程

源码位置：`base.py:4126-4267`

并行流式是最精妙的部分。每个步骤接收一个输入迭代器的副本（通过 `safetee` / `atee` 实现），各步骤的 `transform` 方法并行推进，每当任一步骤产生一个 chunk，就立即封装为 `AddableDict` 输出：

```python
def _transform(self, inputs, run_manager, config):
    # 为每个步骤复制一份输入流
    input_copies = list(safetee(inputs, len(steps), lock=threading.Lock()))
    with get_executor_for_config(config) as executor:
        named_generators = [
            (name, step.transform(input_copies.pop(),
             patch_config(config, callbacks=run_manager.get_child(f"map:key:{name}"))))
            for name, step in steps.items()
        ]
        futures = {executor.submit(next, generator): (name, generator)
                    for name, generator in named_generators}
        while futures:
            completed, _ = wait(futures, return_when=FIRST_COMPLETED)
            for future in completed:
                name, generator = futures.pop(future)
                try:
                    chunk = AddableDict({name: future.result()})
                    yield chunk
                    futures[executor.submit(next, generator)] = (name, generator)
                except StopIteration:
                    pass
```

关键点：
- 使用 `concurrent.futures.wait(FIRST_COMPLETED)` 实现"谁先产生结果就先输出谁"
- `AddableDict` 支持 `+` 操作：`AddableDict({"a": 1}) + AddableDict({"b": 2})` = `AddableDict({"a": 1, "b": 2})`
- 这使得并行流式的结果可以被下游正确累积

---

## 7. RunnableBranch：条件路由

源码位置：`branch.py:42-461`

### 7.1 数据结构

```python
class RunnableBranch(RunnableSerializable[Input, Output]):
    branches: Sequence[tuple[Runnable[Input, bool], Runnable[Input, Output]]]
    default: Runnable[Input, Output]
```

构造函数接受变长参数，最后一个参数被自动视为 default：

```python
branch = RunnableBranch(
    (lambda x: isinstance(x, str), str_handler),
    (lambda x: isinstance(x, int), int_handler),
    default_handler,  # 最后一个是 default
)
```

### 7.2 执行逻辑

```
1. ensure_config + 创建 callback_manager
2. 触发 on_chain_start
3. 按顺序对 branches 中的每个 (condition, runnable) 对：
    a. 调用 condition.invoke(input)
    b. 如果是 True -> 调用 runnable.invoke(input) -> break
4. 如果所有 condition 都是 False -> 调用 default.invoke(input)
5. 触发 on_chain_end 或 on_chain_error
```

关键实现（`branch.py:189-245`）：

```python
def invoke(self, input, config=None, **kwargs):
    # ...
    try:
        for idx, branch in enumerate(self.branches):
            condition, runnable = branch
            expression_value = condition.invoke(
                input, config=patch_config(
                    config, callbacks=run_manager.get_child(tag=f"condition:{idx + 1}"),
                ),
            )
            if expression_value:
                output = runnable.invoke(
                    input, config=patch_config(
                        config, callbacks=run_manager.get_child(tag=f"branch:{idx + 1}"),
                    ), **kwargs,
                )
                break
        else:
            output = self.default.invoke(
                input, config=patch_config(
                    config, callbacks=run_manager.get_child(tag="branch:default")
                ), **kwargs,
            )
    except BaseException as e:
        run_manager.on_chain_error(e)
        raise
    run_manager.on_chain_end(output)
    return output
```

流式版本会累积 chunks 用于最终的 `on_chain_end` 回调。

---

## 8. RunnableLambda：函数包装器

源码位置：`base.py:4577-5447`

### 8.1 核心设计

`RunnableLambda` 将任何 Python 可调用对象转换为 `Runnable`。它支持：

- **同步函数**：`Callable[[Input], Output]`
- **异步函数**：`Callable[[Input], Awaitable[Output]]`
- **生成器函数**：`Callable[[Input], Iterator[Output]]`
- **异步生成器**：`Callable[[Input], AsyncIterator[Output]]`
- **带 config 参数的函数**：`Callable[[Input, RunnableConfig], Output]`
- **带 run_manager 参数的函数**：`Callable[[Input, CallbackManagerForChainRun], Output]`

构造函数（`base.py:4757-4830`）会自动检测函数类型：

```python
def __init__(self, func, afunc=None, name=None):
    if afunc is not None:
        self.afunc = afunc
    if is_async_callable(func) or is_async_generator(func):
        self.afunc = func  # 异步函数放入 afunc
    elif callable(func):
        self.func = cast("Callable[[Input], Output]", func)  # 同步函数
```

### 8.2 invoke 执行

源码位置：`base.py:5029-5072`

```python
def _invoke(self, input_, run_manager, config, **kwargs):
    output = call_func_with_variable_args(
        self.func, input_, config, run_manager, **kwargs
    )
    # 如果返回值本身是 Runnable，递归调用
    if isinstance(output, Runnable):
        recursion_limit = config["recursion_limit"]
        if recursion_limit <= 0:
            raise RecursionError(...)
        output = output.invoke(
            input_, patch_config(config,
                callbacks=run_manager.get_child(),
                recursion_limit=recursion_limit - 1,
            ),
        )
    return cast("Output", output)
```

关键设计：**当 `RunnableLambda` 返回一个 `Runnable` 实例时，会自动调用它！** 这允许函数内部动态返回 Runnable，并带有递归深度保护（`recursion_limit`，默认 25）。

### 8.3 依赖追踪

源码位置：`base.py:4941-4963`

`RunnableLambda` 会自动检测函数闭包中的非局部变量（通过 `get_function_nonlocals`），如果其中有 `Runnable` 实例，它们会被识别为依赖：

```python
@functools.cached_property
def deps(self) -> list[Runnable]:
    if hasattr(self, "func"):
        objects = get_function_nonlocals(self.func)
    for obj in objects:
        if isinstance(obj, Runnable):
            deps.append(obj)
    return deps
```

这使得使用 `@chain` 装饰器的函数内部的 Runnable 调用可以被正确追踪和可视化。

### 8.4 `@chain` 装饰器

源码位置：`base.py:6516-6574`

`@chain` 是一个装饰器，本质上等价于 `RunnableLambda(func)`，但额外设置了名称：

```python
def chain(func):
    if is_async_generator(func) or inspect.isgeneratorfunction(func):
        return RunnableGenerator(func)
    return RunnableLambda(func)
```

---

## 9. RunnableBinding：配置绑定

源码位置：`base.py:5716-6451`

### 9.1 设计模式：装饰器模式

`RunnableBinding` 是**装饰器模式**在 Runnable 中的应用。它包装一个底层 Runnable，并在调用时附加上下文（config、kwargs、config_factories）。

```
         ┌──────────────────────────┐
input -> │  RunnableBinding         │
         │  ┌────────────────────┐  │
         │  │ bound Runnable     │  │ -> output
         │  │ (invoke/stream...) │  │
         │  └────────────────────┘  │
         │  + kwargs                │
         │  + config                │
         │  + config_factories      │
         └──────────────────────────┘
```

### 9.2 数据结构

```python
class RunnableBindingBase(RunnableSerializable[Input, Output]):
    bound: Runnable[Input, Output]                     # 被绑定的 Runnable
    kwargs: Mapping[str, Any]                           # 要传递的额外 kwargs
    config: RunnableConfig                               # 要合并的 config
    config_factories: list[Callable[[RunnableConfig], RunnableConfig]]  # config 工厂
    custom_input_type: Any | None                       # 覆盖输入类型
    custom_output_type: Any | None                      # 覆盖输出类型
```

### 9.3 配置合并

调用时通过 `_merge_configs` 将自身 config、传入 config、config_factories 结果合并：

```python
def _merge_configs(self, *configs):
    config = merge_configs(self.config, *configs)
    return merge_configs(config, *(f(config) for f in self.config_factories))

def invoke(self, input, config=None, **kwargs):
    return self.bound.invoke(
        input, self._merge_configs(config),
        **{**self.kwargs, **kwargs},
    )
```

### 9.4 链式包装方法

`Runnable` 基类提供了一系列方法，它们内部都创建 `RunnableBinding` 实例：

| 方法 | 用途 | 实现原理 |
|------|------|---------|
| `bind(**kwargs)` | 绑定额外参数 | `RunnableBinding(bound=self, kwargs=kwargs, config={})` |
| `with_config(config, **kwargs)` | 绑定配置 | `RunnableBinding(bound=self, config=merged, kwargs={})` |
| `with_listeners(on_start, on_end, on_error)` | 绑定生命周期监听器 | `RunnableBinding(bound=self, config_factories=[listener_factory])` |
| `with_retry(**kwargs)` | 绑定重试策略 | 包装 bound.with_retry(**kwargs) |
| `with_fallbacks(fallbacks)` | 绑定容错回退 | 包装 RunnableWithFallbacks |
| `with_types(input_type, output_type)` | 覆盖类型标注 | 设置 custom_input_type / custom_output_type |

### 9.5 `__getattr__` 代理

`RunnableBinding.__getattr__`（`base.py:6416-6450`）将属性访问代理到底层 `bound`，并自动处理 config 合并：

```python
def __getattr__(self, name):
    attr = getattr(self.bound, name)
    if callable(attr) and "config" in inspect.signature(attr).parameters:
        @wraps(attr)
        def wrapper(*args, **kwargs):
            return attr(*args,
                config=merge_configs(self.config, kwargs.pop("config", None)),
                **kwargs)
        return wrapper
    return attr
```

---

## 10. 回调系统 (CallbackManager)

源码位置：`callbacks/manager.py`

### 10.1 核心类层次

```
BaseCallbackManager
  ├── CallbackManager              (同步)
  └── AsyncCallbackManager        (异步)

BaseRunManager
  ├── RunManager
  │     └── ParentRunManager
  │           ├── CallbackManagerForLLMRun
  │           ├── CallbackManagerForChainRun
  │           ├── CallbackManagerForToolRun
  │           └── CallbackManagerForRetrieverRun
  └── AsyncRunManager
        └── AsyncParentRunManager
              ├── AsyncCallbackManagerForLLMRun
              ├── AsyncCallbackManagerForChainRun
              ├── AsyncCallbackManagerForToolRun
              └── AsyncCallbackManagerForRetrieverRun
```

### 10.2 生命周期钩子

每个 Runnable 类型的执行都会触发对应的生命周期事件（以 Chain 为例）：

| 事件 | 触发时机 | 回调方法 |
|------|---------|---------|
| **start** | 执行开始 | `on_chain_start(serialized, inputs, run_id, ...)` |
| **stream** | 产生流式数据 | `on_llm_new_token` / `on_chain_stream` |
| **end** | 执行成功 | `on_chain_end(outputs, ...)` |
| **error** | 执行异常 | `on_chain_error(error, ...)` |

其他类型类似：
- LLM：`on_llm_start` / `on_llm_new_token` / `on_llm_end` / `on_llm_error`
- ChatModel：`on_chat_model_start` / `on_chat_model_stream` / `on_chat_model_end`
- Tool：`on_tool_start` / `on_tool_end` / `on_tool_error`
- Retriever：`on_retriever_start` / `on_retriever_end` / `on_retriever_error`

### 10.3 CallbackManager.configure

源码位置：`manager.py:1649-1691`

这是创建 CallbackManager 的入口，被 Runnable 在每次 invoke/stream/batch 时调用：

```python
@classmethod
def configure(cls, inheritable_callbacks=None, local_callbacks=None,
              inheritable_tags=None, local_tags=None,
              inheritable_metadata=None, local_metadata=None, ...):
    return _configure(cls, inheritable_callbacks, local_callbacks, ...)
```

`_configure` 函数负责：
1. 如果开启了 debug 模式，添加 `StdOutCallbackHandler`
2. 如果设置了 `LANGCHAIN_TRACING_V2` 环境变量，添加 LangSmith tracer
3. 合并 inheritable 和 local 的 callbacks/tags/metadata

### 10.4 ParentRunManager.get_child

源码位置：`manager.py:565-584`

```python
def get_child(self, tag=None):
    manager = CallbackManager(handlers=[], parent_run_id=self.run_id)
    manager.set_handlers(self.inheritable_handlers)
    manager.add_tags(self.inheritable_tags)
    manager.add_metadata(self.inheritable_metadata)
    if tag is not None:
        manager.add_tags([tag], inherit=False)
    return manager
```

子 manager 继承父的 handlers、tags、metadata，同时保存 `parent_run_id`，从而构建父子运行关系树。

### 10.5 事件分发：handle_event

源码位置：`manager.py:255-335`

```python
def handle_event(handlers, event_name, ignore_condition_name, *args, **kwargs):
    for handler in handlers:
        if ignore_condition_name and getattr(handler, ignore_condition_name):
            continue  # 跳过被标记为忽略的 handler
        event = getattr(handler, event_name)(*args, **kwargs)
        if asyncio.iscoroutine(event):
            coros.append(event)
    # 在 finally 中调度运行所有收集到的协程
```

关键设计：
- 异常不会被传播到其他 handler（除非 handler.raise_error=True）
- 如果事件的异步实现在 handler 中不可用，会回退到同步版本（如 `on_chat_model_start` -> `on_llm_start`）
- 异步回调在后台执行，不阻塞主流程

### 10.6 root_listeners 追踪器

`with_listeners` 方法背后使用的是 `RootListenersTracer`（`tracers/root_listeners.py`），通过 `config_factories` 机制注入到配置中：

```python
def with_listeners(self, *, on_start, on_end, on_error):
    return RunnableBinding(
        bound=self,
        config_factories=[
            lambda config: {
                "callbacks": [
                    RootListenersTracer(
                        config=config,
                        on_start=on_start,
                        on_end=on_end,
                        on_error=on_error,
                    )
                ],
            }
        ],
    )
```

---

## 11. 流事件系统 (Stream Events)

源码位置：`schema.py`（类型定义）、`tracers/event_stream.py`（实现）

### 11.1 事件类型定义

```python
class BaseStreamEvent(TypedDict):
    event: str                # "on_[type]_(start|stream|end)"
    run_id: str               # 运行唯一ID
    tags: NotRequired[list[str]]
    metadata: NotRequired[dict[str, Any]]
    parent_ids: Sequence[str] # 父运行ID链

class StandardStreamEvent(BaseStreamEvent):
    data: EventData           # {"input", "chunk", "output", "error"}
    name: str                 # Runnable 名称

class CustomStreamEvent(BaseStreamEvent):
    event: Literal["on_custom_event"]
    name: str                 # 用户自定义名称
    data: Any                 # 自由格式数据
```

### 11.2 事件类型命名规则

格式：`on_[runnable_type]_(start|stream|end)`

Runnable 类型包括：
- `chain` — 大多数 Runnable（如 RunnableSequence、RunnableLambda）
- `llm` — 非聊天模型
- `chat_model` — 聊天模型
- `prompt` — 提示模板
- `tool` — 工具
- `retriever` — 检索器

### 11.3 astream_events 实现

`astream_events`（`base.py:1305-1608`）支持三个版本：

- **v1**（已弃用）：基于 `astream_log` 的实现
- **v2**（当前默认）：使用 `_astream_events_implementation_v2`，基于 `_V2StreamingCallbackHandler` 和事件过滤
- **v3**（beta）：新的类型化流协议，目前仅 `BaseChatModel` 和 LangGraph `CompiledGraph` 支持

v2 的实现核心是一个异步回调处理器，它拦截所有的 `on_*_start` / `on_*_stream` / `on_*_end` 回调，并将它们转换为 `StreamEvent` dict，同时支持 `include_names`、`include_types`、`include_tags`、`exclude_*` 过滤。

### 11.4 自定义事件

用户可以在 Runnable 内部通过 `adispatch_custom_event` 分发自定义事件：

```python
from langchain_core.callbacks.manager import adispatch_custom_event

async def my_func(input, config):
    await adispatch_custom_event("progress", {"pct": 50}, config=config)
    return result
```

---

## 12. 辅助类：RunnablePassthrough、RunnableAssign、RunnablePick

源码位置：`passthrough.py`

### 12.1 RunnablePassthrough

最简单的 Runnable，直接返回输入。

```python
class RunnablePassthrough(RunnableSerializable[Other, Other]):
    def invoke(self, input, config=None, **kwargs):
        return self._call_with_config(identity, input, config)
```

在 `RunnableParallel` 中常用于保留原始输入：

```python
RunnableParallel(
    original=RunnablePassthrough(),
    modified=some_transform,
)
```

### 12.2 RunnablePick

从 dict 输出中提取指定 key：

```python
class RunnablePick(RunnableSerializable[dict[str, Any], Any]):
    keys: str | list[str]
```

等价于 `.pick("key_name")` 方法。

### 12.3 RunnableAssign

向 dict 输出追加新字段：

```python
class RunnableAssign(RunnableSerializable[dict[str, Any], dict[str, Any]]):
    mapper: RunnableParallel[dict[str, Any]]
```

等价于 `.assign(key=value_runnable)` 方法。内部实现是将 mapper（一个 `RunnableParallel`）与原输入做合并。

---

## 13. 动态配置：configurable_fields 和 configurable_alternatives

源码位置：`configurable.py`

### 13.1 RunnableConfigurableFields

允许在运行时修改 Runnable 的特定字段：

```python
model = ChatOpenAI(max_tokens=20).configurable_fields(
    max_tokens=ConfigurableField(
        id="output_token_number",
        name="Max tokens in the output",
        description="The maximum number of tokens in the output",
    )
)
# 运行时覆盖
model.with_config(configurable={"output_token_number": 200})
```

### 13.2 RunnableConfigurableAlternatives

允许在运行时切换到完全不同的 Runnable 实现：

```python
model = ChatAnthropic(model_name="claude-sonnet-4-5").configurable_alternatives(
    ConfigurableField(id="llm"),
    default_key="anthropic",
    openai=ChatOpenAI(),
)
# 切换到 OpenAI
model.with_config(configurable={"llm": "openai"})
```

---

## 14. 执行流程全链路追踪

让我们跟踪一个典型的 LangChain 调用的完整执行路径：

```
用户代码: chain.invoke(input, config={"tags": ["prod"]})

1. RunnableSequence.invoke()
   ├── ensure_config(config)
   │     ├── 创建默认配置
   │     ├── 从 ContextVar 读取父配置并合并
   │     └── 将传入的 config 合并
   │
   ├── get_callback_manager_for_config(config)
   │     └── CallbackManager.configure(callbacks, tags, metadata)
   │           ├── 检查 debug 模式
   │           ├── 检查 LangSmith tracing
   │           └── 合并所有 callbacks
   │
   ├── run_manager = callback_manager.on_chain_start(None, input, ...)
   │     ├── uuid7() 生成 run_id
   │     ├── handle_event(handlers, "on_chain_start", ...)
   │     │     └── 依次调用每个 handler.on_chain_start(...)
   │     └── 返回 CallbackManagerForChainRun
   │
   ├── [for each step in sequence]
   │     ├── patch_config(config, callbacks=run_manager.get_child("seq:step:1"))
   │     ├── set_config_context(child_config)  # 设置 ContextVar
   │     └── context.run(step.invoke, input_, child_config)
   │           │
   │           ├── [如果是 RunnableLambda]
   │           │     └── self._call_with_config(self._invoke, input, config)
   │           │           ├── 创建新的 callback_manager 和 run_manager
   │           │           ├── call_func_with_variable_args(func, input, config, run_manager)
   │           │           ├── run_manager.on_chain_end(output)
   │           │           └── return output
   │           │
   │           └── [如果是 ChatModel]
   │                 ├── callback_manager.on_chat_model_start(...)
   │                 ├── 调用 LLM API
   │                 │     └── [流式场景] handle_event(handlers, "on_chat_model_stream", chunk)
   │                 ├── callback_manager.on_chat_model_end(...)
   │                 └── return output
   │
   └── run_manager.on_chain_end(input_)
         └── handle_event(handlers, "on_chain_end", ...)
```

### stream 模式的区别

在 stream 模式下，流程略有不同：

```
stream() -> transform(iter([input]))
  -> _transform_stream_with_config(inputs, transformer, config)
       ├── tee(inputs)  # 分成追踪流和转换流
       ├── on_chain_start({"input": ""})  # 初始输入为空
       ├── _StreamingCallbackHandler.tap_output_iter()  # 监控流式输出
       ├── yield each chunk
       └── on_chain_end(final_output, inputs=final_input)
```

---

## 15. 设计模式总结

### 15.1 模板方法模式 (Template Method Pattern)

`Runnable` 基类定义了框架（invoke/stream/batch 的默认实现），子类实现核心逻辑。

`_call_with_config`、`_transform_stream_with_config` 等 helper 方法封装了 callbacks 和 config 管理的模板逻辑。

### 15.2 装饰器模式 (Decorator Pattern)

`RunnableBinding` 是最典型的装饰器，它在不改变接口的情况下为 Runnable 添加功能（绑定参数、绑定配置、添加监听器、添加重试策略）。

所有 `.with_*` 方法都创建新的 `RunnableBinding` 实例，形成装饰器链。

### 15.3 组合模式 (Composite Pattern)

`RunnableSequence` 和 `RunnableParallel` 都是组合模式的实现：

- **RunnableSequence**：叶子按顺序组合
- **RunnableParallel**：叶子并行组合

它们都实现了 `Runnable` 接口，因此可以无限嵌套。

### 15.4 策略模式 (Strategy Pattern)

`RunnableBranch` 根据条件选择执行策略（分支）。`RunnableConfigurableAlternatives` 根据配置选择不同的实现。

### 15.5 观察者模式 (Observer Pattern)

整个回调系统就是观察者模式。`BaseCallbackHandler` 的实现者（如 `ConsoleCallbackHandler`、`LangChainTracer`、用户自定义 handler）注册到 `CallbackManager` 中，观察到 Runnable 生命周期中的各种事件。

### 15.6 管道-过滤器模式 (Pipes and Filters)

`transform` 方法实现了管道-过滤器架构。每个 Runnable 的 `transform` 方法接收一个输入流，处理后输出一个输出流。通过 `RunnableSequence._transform`，多个过滤器被串联起来。

### 15.7 ContextVar 配置传播

这是 LangChain 特有的设计模式。利用 Python 的 `ContextVar`，配置（tags、metadata、callbacks）在调用栈中隐式传播，无需显式参数传递。`ContextThreadPoolExecutor` 通过 `copy_context().run()` 确保跨线程传播。

### 15.8 类型安全的泛型链式调用

通过 Python 泛型和 `__or__` 运算符重载，LangChain 实现了类型安全的链式 API。`RunnableSequence[Input, Output]` 的类型参数由 `first.InputType` 和 `last.OutputType` 决定，这使得 IDE 可以提供完整的类型提示和自动补全。

---

## 关键源码文件索引

| 文件 | 内容 | 关键行号 |
|------|------|---------|
| `runnables/base.py:125` | `Runnable` 基类 | 125-2763 |
| `runnables/base.py:2764` | `RunnableSerializable` | 2764-2908 |
| `runnables/base.py:2995` | `RunnableSequence` | 2995-3741 |
| `runnables/base.py:3743` | `RunnableParallel` | 3743-4271 |
| `runnables/base.py:4274` | `RunnableGenerator` | 4274-4576 |
| `runnables/base.py:4577` | `RunnableLambda` | 4577-5447 |
| `runnables/base.py:5716` | `RunnableBindingBase` | 5716-6244 |
| `runnables/base.py:6245` | `RunnableBinding` | 6245-6451 |
| `runnables/base.py:6489` | `coerce_to_runnable` | 6489-6513 |
| `runnables/base.py:2205` | `_call_with_config` | 2205-2252 |
| `runnables/base.py:2439` | `_transform_stream_with_config` | 2439-2535 |
| `runnables/config.py:49` | `RunnableConfig` TypedDict | 49-121 |
| `runnables/config.py:166` | `var_child_runnable_config` ContextVar | 166-168 |
| `runnables/config.py:247` | `ensure_config` | 247-300 |
| `runnables/config.py:391` | `merge_configs` | 391-454 |
| `runnables/config.py:567` | `ContextThreadPoolExecutor` | 567-616 |
| `runnables/branch.py:42` | `RunnableBranch` | 42-461 |
| `runnables/schema.py:13` | `EventData` / `StreamEvent` | 13-188 |
| `runnables/passthrough.py:74` | `RunnablePassthrough` | 74-841 |
| `runnables/configurable.py:49` | `DynamicRunnable` | 49-716 |
| `callbacks/manager.py:456` | `BaseRunManager` | 456-510 |
| `callbacks/manager.py:565` | `ParentRunManager.get_child` | 565-584 |
| `callbacks/manager.py:894` | `CallbackManagerForChainRun` | 894-983 |
| `callbacks/manager.py:1343` | `CallbackManager` | 1343-1692 |
| `callbacks/manager.py:1649` | `CallbackManager.configure` | 1649-1691 |
| `callbacks/manager.py:1825` | `AsyncCallbackManager` | 1825-2176 |
| `callbacks/manager.py:255` | `handle_event` | 255-335 |
