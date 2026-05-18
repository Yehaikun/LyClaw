# LangChain 经典 Agent 模式深度源码分析

> 基于 LangChain 开源仓库 `/home/lyjew/Documents/github/langchain` 源码分析
> 分析日期：2026-05-18
> 主要源码路径：`libs/langchain/langchain_classic/agents/`

---

## 目录

1. [核心架构：AgentExecutor 执行引擎](#一核心架构agentexecutor-执行引擎)
2. [ReAct 模式](#二react-模式)
3. [MRKL / Zero-Shot ReAct 模式](#三mrkl--zero-shot-react-模式)
4. [XML Agent 模式](#四xml-agent-模式)
5. [OpenAI Functions Agent 模式](#五openai-functions-agent-模式)
6. [Tool Calling Agent 模式](#六tool-calling-agent-模式)
7. [OpenAI Tools Agent 模式](#七openai-tools-agent-模式)
8. [Structured Chat Agent 模式](#八structured-chat-agent-模式)
9. [输出解析器全景对比](#九输出解析器全景对比)
10. [模式演进路线图 (deprecated vs current)](#十模式演进路线图-deprecated-vs-current)
11. [LangChain v1 新范式：create_agent](#十一langchain-v1-新范式create_agent)
12. [模式对比与选型指南](#十二模式对比与选型指南)

---

## 一、核心架构：AgentExecutor 执行引擎

### 1.1 架构总览

所有经典 Agent 模式共享同一个执行引擎 `AgentExecutor`（定义于 `agent.py:1012`）。`AgentExecutor` 是一个 `Chain` 的子类，负责编排 Thought-Action-Observation 循环。

```
┌─────────────────────────────────────────────────────────┐
│                    AgentExecutor                         │
│                                                         │
│   ┌──────────┐    ┌──────────┐    ┌──────────┐          │
│   │  Agent    │───>│  Tool    │───>│  Agent   │───> ...  │
│   │  .plan()  │    │  .run()  │    │  .plan() │         │
│   └──────────┘    └──────────┘    └──────────┘          │
│        │               │               │                │
│    AgentAction     AgentStep       AgentAction           │
│    (tool+input)  (action+obs)    or AgentFinish          │
│                                                         │
│   循环控制：max_iterations / max_execution_time          │
│   错误处理：handle_parsing_errors                        │
│   提前停止：early_stopping_method (force/generate)       │
└─────────────────────────────────────────────────────────┘
```

### 1.2 核心数据结构

源码中使用三种核心数据类型（定义于 `langchain_core.agents`）：

```python
# 1. AgentAction — LLM 决定调用工具
class AgentAction:
    tool: str        # 工具名称
    tool_input: Any  # 工具输入
    log: str         # LLM 输出的完整文本（用于构建 scratchpad）

# 2. AgentFinish — LLM 决定返回最终答案
class AgentFinish:
    return_values: dict  # 通常包含 {"output": "最终答案"}
    log: str

# 3. AgentStep — 工具执行后的结果
class AgentStep:
    action: AgentAction  # 触发此步骤的 action
    observation: str     # 工具执行的返回结果
```

### 1.3 Agent 类层次结构

```
BaseModel
├── BaseSingleActionAgent          # 每次返回单个 action
│   ├── LLMSingleActionAgent       # [deprecated] 旧式单 action agent
│   ├── Agent                      # [deprecated] LLMChain 驱动的 agent
│   │   ├── ReActDocstoreAgent
│   │   ├── ZeroShotAgent (MRKL)
│   │   ├── ChatAgent
│   │   ├── StructuredChatAgent
│   │   └── ConversationalAgent / ConversationalChatAgent
│   ├── XMLAgent                   # [deprecated] XML 格式 agent
│   ├── OpenAIFunctionsAgent       # [deprecated] 旧式 OpenAI functions agent
│   └── RunnableAgent              # 新式 Runnable 包装器
├── BaseMultiActionAgent           # 每次可返回多个 action
│   ├── OpenAIMultiFunctionsAgent  # [deprecated]
│   └── RunnableMultiActionAgent   # 新式多 action Runnable 包装器
```

### 1.4 执行循环详解（`_call` 方法，`agent.py:1570`）

```python
def _call(self, inputs, run_manager=None):
    name_to_tool_map = {tool.name: tool for tool in self.tools}
    color_mapping = get_color_mapping([tool.name for tool in self.tools])
    intermediate_steps = []
    iterations = 0
    time_elapsed = 0.0
    start_time = time.time()

    while self._should_continue(iterations, time_elapsed):
        # 单步执行：plan -> 解析 -> 执行工具 -> 获取 observation
        next_step_output = self._take_next_step(
            name_to_tool_map, color_mapping, inputs,
            intermediate_steps, run_manager
        )

        # 检查是否 AgentFinish（最终答案）
        if isinstance(next_step_output, AgentFinish):
            return self._return(next_step_output, intermediate_steps, run_manager)

        intermediate_steps.extend(next_step_output)

        # 检查工具是否标记了 return_direct
        if len(next_step_output) == 1:
            tool_return = self._get_tool_return(next_step_output[0])
            if tool_return is not None:
                return self._return(tool_return, intermediate_steps, run_manager)

        iterations += 1
        time_elapsed = time.time() - start_time

    # 达到最大迭代次数或超时
    output = self._action_agent.return_stopped_response(
        self.early_stopping_method, intermediate_steps, **inputs
    )
    return self._return(output, intermediate_steps, run_manager)
```

### 1.5 单步执行详解（`_iter_next_step` 方法，`agent.py:1301`）

每个步骤的详细流程：

```
1. _prepare_intermediate_steps() — 可选地对中间步骤进行裁剪
2. agent.plan(intermediate_steps, **inputs) — 调用 LLM 决定下一步
   ├── 成功：得到 AgentAction 或 AgentFinish
   └── OutputParserException：
       ├── handle_parsing_errors=True  → 将错误包装为 _Exception 工具的 observation
       ├── handle_parsing_errors=str   → 将字符串作为 observation
       └── handle_parsing_errors=callable → 调用函数得到 observation
3. 如果结果是 AgentFinish → yield 并返回
4. 如果结果是 AgentAction → yield AgentAction，然后执行工具得到 AgentStep 并 yield
```

### 1.6 关键配置参数

| 参数 | 默认值 | 说明 |
|------|--------|------|
| `max_iterations` | 15 | 最大循环次数，设为 None 可能导致无限循环 |
| `max_execution_time` | None | 最大执行时间（秒） |
| `early_stopping_method` | "force" | "force"返回固定字符串；"generate"再做一次 LLM 调用生成答案 |
| `handle_parsing_errors` | False | True/字符串/可调用对象，控制解析错误如何处理 |
| `return_intermediate_steps` | False | 是否在输出中包含所有中间步骤 |
| `trim_intermediate_steps` | -1 | 裁剪中间步骤数量，避免上下文过长 |

---

## 二、ReAct 模式

### 2.1 概述

ReAct（Reasoning + Acting）是 LangChain 中最经典的 agent 模式，灵感来自论文 [ReAct: Synergizing Reasoning and Acting in Language Models](https://arxiv.org/pdf/2210.03629.pdf)。在 LangChain 中有两种实现：

1. **ReActDocstoreAgent** — 专用于文档存储查询的原始 ReAct
2. **ReAct 风格的输出解析器** — `ReActSingleInputOutputParser` 和 `ReActJsonSingleInputOutputParser`

### 2.2 ReActDocstoreAgent（源码：`react/base.py:36`）

```python
@deprecated("0.1.0", removal="2.0.0")
class ReActDocstoreAgent(Agent):
    output_parser: AgentOutputParser = Field(default_factory=ReActOutputParser)

    @property
    def observation_prefix(self) -> str:
        return "Observation: "

    @property
    def llm_prefix(self) -> str:
        return "Thought:"

    @property
    def _stop(self) -> list[str]:
        return ["\nObservation:"]
```

**特点**：
- 只能使用两个特定工具：`Search` 和 `Lookup`
- 专门用于在文档存储中查找信息
- 使用 `ReActOutputParser` 解析输出

### 2.3 ReActOutputParser（源码：`react/output_parser.py:10`）

```python
class ReActOutputParser(AgentOutputParser):
    def parse(self, text: str) -> AgentAction | AgentFinish:
        action_prefix = "Action: "
        # 取最后一行，必须以 "Action: " 开头
        if not text.strip().split("\n")[-1].startswith(action_prefix):
            raise OutputParserException(f"Could not parse LLM Output: {text}")

        action_block = text.strip().split("\n")[-1]
        action_str = action_block[len(action_prefix):]

        # 解析格式：ActionName[ActionInput]
        re_matches = re.search(r"(.*?)\[(.*?)\]", action_str)
        if re_matches is None:
            raise OutputParserException(...)

        action, action_input = re_matches.group(1), re_matches.group(2)
        if action == "Finish":
            return AgentFinish({"output": action_input}, text)
        return AgentAction(action, action_input, text)
```

**解析格式**：
```
Thought: I need to search for something
Action: Search[query text]
```

### 2.4 ReActSingleInputOutputParser（源码：`output_parsers/react_single_input.py:22`）

这是 MRKL/Zero-Shot agent 使用的输出解析器。与 `MRKLOutputParser` 几乎完全相同。

**解析格式**：
```
Thought: agent thought here
Action: search
Action Input: what is the temperature in SF?
```

**关键正则**：
```python
regex = r"Action\s*\d*\s*:[\s]*(.*?)Action\s*\d*\s*Input\s*\d*\s*:[\s]*(.*)"
```

### 2.5 ReAct 的 Thought/Action/Observation 循环

ReAct 模式的核心是交替进行的推理和行动：

```
Question: What is the weather in SF?
Thought: I need to look up the weather for San Francisco
Action: search
Action Input: weather in San Francisco
Observation: The weather in San Francisco is 64 degrees and sunny
Thought: I now know the weather in SF
Final Answer: The weather in San Francisco is 64 degrees and sunny.
```

**关键设计**：
- LLM 输出必须以 `Thought:` 开始推理
- 然后 `Action:` 指定工具，`Action Input:` 指定参数
- 工具执行结果以 `Observation:` 的形式反馈给 LLM
- 循环直到 LLM 输出 `Final Answer:`

### 2.6 Scratchpad 构建（`Agent._construct_scratchpad`，`agent.py:742`）

```python
def _construct_scratchpad(self, intermediate_steps):
    thoughts = ""
    for action, observation in intermediate_steps:
        thoughts += action.log
        thoughts += f"\n{self.observation_prefix}{observation}\n{self.llm_prefix}"
    return thoughts
```

这个 scratchpad 被插入到 prompt 的 `{agent_scratchpad}` 变量中，使 LLM 能够看到完整的推理链。

---

## 三、MRKL / Zero-Shot ReAct 模式

### 3.1 概述

MRKL（Modular Reasoning, Knowledge and Language）系统来自论文 [MRKL Systems](https://arxiv.org/pdf/2205.00445.pdf)。在 LangChain 中，`ZeroShotAgent` 是 MRKL 的实现，也是 `initialize_agent` 的默认 agent 类型。

### 3.2 ZeroShotAgent（源码：`mrkl/base.py:45`）

```python
@deprecated("0.1.0", removal="2.0.0")
class ZeroShotAgent(Agent):
    output_parser: AgentOutputParser = Field(default_factory=MRKLOutputParser)

    @property
    def observation_prefix(self) -> str:
        return "Observation: "

    @property
    def llm_prefix(self) -> str:
        return "Thought:"
```

### 3.3 Prompt 结构（源码：`mrkl/prompt.py`）

```python
PREFIX = """Answer the following questions as best you can. You have access to the following tools:"""

FORMAT_INSTRUCTIONS = """Use the following format:

Question: the input question you must answer
Thought: you should always think about what to do
Action: the action to take, should be one of [{tool_names}]
Action Input: the input to the action
Observation: the result of the action
... (this Thought/Action/Action Input/Observation can repeat N times)
Thought: I now know the final answer
Final Answer: the final answer to the original input question"""

SUFFIX = """Begin!

Question: {input}
Thought:{agent_scratchpad}"""
```

### 3.4 Prompt 模板组装（`mrkl/base.py:82`）

```python
@classmethod
def create_prompt(cls, tools, prefix=PREFIX, suffix=SUFFIX,
                  format_instructions=FORMAT_INSTRUCTIONS, input_variables=None):
    tool_strings = render_text_description(list(tools))  # 渲染工具描述
    tool_names = ", ".join([tool.name for tool in tools])
    format_instructions = format_instructions.format(tool_names=tool_names)
    template = f"{prefix}\n\n{tool_strings}\n\n{format_instructions}\n\n{suffix}"
    return PromptTemplate.from_template(template)
```

### 3.5 MRKLOutputParser（源码：`mrkl/output_parser.py:21`）

```python
class MRKLOutputParser(AgentOutputParser):
    def parse(self, text: str) -> AgentAction | AgentFinish:
        includes_answer = FINAL_ANSWER_ACTION in text  # "Final Answer:"
        regex = r"Action\s*\d*\s*:[\s]*(.*?)Action\s*\d*\s*Input\s*\d*\s*:[\s]*(.*)"
        action_match = re.search(regex, text, re.DOTALL)

        # 情况1：同时有 Action 和 Final Answer
        if action_match and includes_answer:
            if text.find(FINAL_ANSWER_ACTION) < text.find(action_match.group(0)):
                # Final Answer 在 Action 之前，返回 Final Answer
                return AgentFinish(...)
            raise OutputParserException(...)  # 否则报错

        # 情况2：只有 Action
        if action_match:
            action = action_match.group(1).strip()
            action_input = action_match.group(2).strip(" ")
            if not tool_input.startswith("SELECT "):
                tool_input = tool_input.strip('"')
            return AgentAction(action, tool_input, text)

        # 情况3：只有 Final Answer
        if includes_answer:
            return AgentFinish({"output": text.rsplit("Final Answer:", 1)[-1].strip()}, text)

        # 情况4：缺少 Action
        if not re.search(r"Action\s*\d*\s*:[\s]*(.*?)", text, re.DOTALL):
            # 返回 send_to_llm=True 的异常，让 AgentExecutor 将错误反馈给 LLM
            raise OutputParserException(..., observation="Invalid Format: Missing 'Action:' after 'Thought:'", send_to_llm=True)

        # 情况5：缺少 Action Input
        raise OutputParserException(..., observation="Invalid Format: Missing 'Action Input:' after 'Action:'", send_to_llm=True)
```

**关键设计**：MRKLOutputParser 是 `send_to_llm=True` 的典型示例。当解析失败时，它不直接抛错，而是将错误信息作为 observation 返回给 LLM，让 LLM 自我修正。

### 3.6 MRKL 与 ReAct 的关系

实际上，LangChain 中的 `ZeroShotAgent`（MRKL）使用的就是 ReAct 范式。在 `agent_types.py` 中，它的类型是 `ZERO_SHOT_REACT_DESCRIPTION`。MRKL 和 ReAct 在 LangChain 中的区别主要在于：

| 特性 | ReActDocstoreAgent | ZeroShotAgent (MRKL) |
|------|-------------------|---------------------|
| 工具数量 | 固定 2 个（Search/Lookup） | 任意数量 |
| 工具输入格式 | `Action[input]` | `Action: xxx\nAction Input: yyy` |
| 应用场景 | 文档检索 | 通用工具调用 |
| 输出解析器 | ReActOutputParser | MRKLOutputParser |

---

## 四、XML Agent 模式

### 4.1 概述

XML Agent 使用 XML 标签来结构化 LLM 的输出，提供了与 ReAct 文本格式不同的结构化方式。有两套实现：

1. **旧版 `XMLAgent` 类**（deprecated, `xml/base.py:23`）
2. **新版 `create_xml_agent` 工厂函数**（推荐, `xml/base.py:115`）

### 4.2 Prompt 格式（源码：`xml/prompt.py`）

```python
agent_instructions = """You are a helpful assistant.

You have access to the following tools:
{tools}

In order to use a tool, you can use <tool></tool> and <tool_input></tool_input> tags.
You will then get back a response in the form <observation></observation>

For example, if you have a tool called 'search':
<tool>search</tool><tool_input>weather in SF</tool_input>
<observation>64 degrees</observation>

When you are done, respond with a final answer between <final_answer></final_answer>:
<final_answer>The weather in SF is 64 degrees</final_answer>

Begin!

Question: {question}"""
```

### 4.3 旧版 XMLAgent 的 plan 方法（`xml/base.py:65`）

```python
def plan(self, intermediate_steps, callbacks=None, **kwargs):
    log = ""
    # 将中间步骤格式化为 XML
    for action, observation in intermediate_steps:
        log += (f"<tool>{action.tool}</tool><tool_input>{action.tool_input}"
                f"</tool_input><observation>{observation}</observation>")
    # 工具列表
    tools = ""
    for tool in self.tools:
        tools += f"{tool.name}: {tool.description}\n"

    inputs = {
        "intermediate_steps": log,
        "tools": tools,
        "question": kwargs["input"],
        "stop": ["</tool_input>", "</final_answer>"],
    }
    response = self.llm_chain(inputs, callbacks=callbacks)
    return response[self.llm_chain.output_key]
```

### 4.4 `create_xml_agent` — Runnable 管道模式

新版使用 LCEL (LangChain Expression Language) 管道构建：

```python
def create_xml_agent(llm, tools, prompt, tools_renderer=render_text_description,
                     *, stop_sequence=True):
    prompt = prompt.partial(tools=tools_renderer(list(tools)))

    if stop_sequence:
        stop = ["</tool_input>"] if stop_sequence is True else stop_sequence
        llm_with_stop = llm.bind(stop=stop)
    else:
        llm_with_stop = llm

    return (
        RunnablePassthrough.assign(
            agent_scratchpad=lambda x: format_xml(x["intermediate_steps"]),
        )
        | prompt
        | llm_with_stop
        | XMLAgentOutputParser()
    )
```

**管道流程**：
```
inputs ──> format_xml(intermediate_steps) ──> prompt ──> llm ──> XMLAgentOutputParser ──> AgentAction/AgentFinish
```

### 4.5 XMLAgentOutputParser（源码：`output_parsers/xml.py:26`）

```python
class XMLAgentOutputParser(AgentOutputParser):
    escape_format: Literal["minimal"] | None = Field(default="minimal")

    def parse(self, text: str) -> AgentAction | AgentFinish:
        # 1. 检查工具调用
        tool_matches = re.findall(r"<tool>(.*?)</tool>", text, re.DOTALL)
        if tool_matches:
            _tool = tool_matches[0]
            input_matches = re.findall(r"<tool_input>(.*?)</tool_input>", text, re.DOTALL)
            _tool_input = input_matches[0] if input_matches else ""

            if self.escape_format == "minimal":
                _tool = _unescape(_tool)
                _tool_input = _unescape(_tool_input)

            return AgentAction(tool=_tool, tool_input=_tool_input, log=text)

        # 2. 检查最终答案
        if "<final_answer>" in text and "</final_answer>" in text:
            matches = re.findall(r"<final_answer>(.*?)</final_answer>", text, re.DOTALL)
            answer = matches[0]
            if self.escape_format == "minimal":
                answer = _unescape(answer)
            return AgentFinish(return_values={"output": answer}, log=text)

        raise ValueError("Malformed output: expected tool invocation or final answer")
```

### 4.6 转义机制（`format_scratchpad/xml.py`）

XML Agent 有一个独特的"最小转义"（minimal escape）机制：

```python
def _escape(xml: str) -> str:
    """将 XML 标签替换为自定义安全分隔符"""
    replacements = {
        "<tool>": "[[tool]]",
        "</tool>": "[[/tool]]",
        "<tool_input>": "[[tool_input]]",
        "</tool_input>": "[[/tool_input]]",
        "<observation>": "[[observation]]",
        "</observation>": "[[/observation]]",
    }
    for orig, repl in replacements.items():
        xml = xml.replace(orig, repl)
    return xml

def format_xml(intermediate_steps, *, escape_format="minimal"):
    log = ""
    for action, observation in intermediate_steps:
        if escape_format == "minimal":
            tool = _escape(action.tool)
            tool_input = _escape(str(action.tool_input))
            observation_ = _escape(str(observation))
        log += (f"<tool>{tool}</tool><tool_input>{tool_input}"
                f"</tool_input><observation>{observation_}</observation>")
    return log
```

这个机制的原因是：如果工具名称或输入本身包含 XML 特殊字符（如 `<` 或 `>`），直接使用 XML 标签会导致解析错误。所以使用 `[[tool]]` 等自定义分隔符进行转义。

### 4.7 XML Agent 的优势与劣势

**优势**：
- XML 标签比纯文本格式更结构化，LLM 更容易遵循
- 支持嵌套和复杂结构
- 适合与支持 XML 输出的模型（如 Claude）配合

**劣势**：
- 输出 tokens 较多（XML 标签开销）
- 需要 LLM 对 XML 格式有较好的遵循能力
- 不如 JSON 格式普及

---

## 五、OpenAI Functions Agent 模式

### 5.1 概述

OpenAI Functions Agent 是第一个利用 LLM 原生函数调用能力（而非文本解析）的 agent 模式。它使用 OpenAI 的 `functions` 参数直接告诉模型可用的函数签名，模型返回结构化的 `function_call` 而不是自由文本。

### 5.2 旧版 OpenAIFunctionsAgent（源码：`openai_functions_agent/base.py:39`）

```python
@deprecated("0.1.0", alternative="create_openai_functions_agent", removal="2.0.0")
class OpenAIFunctionsAgent(BaseSingleActionAgent):
    llm: BaseLanguageModel
    tools: Sequence[BaseTool]
    prompt: BasePromptTemplate
    output_parser = OpenAIFunctionsAgentOutputParser

    @property
    def functions(self) -> list[dict]:
        return [dict(convert_to_openai_function(t)) for t in self.tools]

    def plan(self, intermediate_steps, callbacks=None, with_functions=True, **kwargs):
        # 1. 将中间步骤格式化为 FunctionMessage 列表
        agent_scratchpad = format_to_openai_function_messages(intermediate_steps)

        # 2. 构建 prompt messages
        selected_inputs = {k: kwargs[k] for k in self.prompt.input_variables
                          if k != "agent_scratchpad"}
        full_inputs = dict(**selected_inputs, agent_scratchpad=agent_scratchpad)
        prompt = self.prompt.format_prompt(**full_inputs)
        messages = prompt.to_messages()

        # 3. 调用 LLM（带 functions 参数）
        if with_functions:
            predicted_message = self.llm.invoke(messages, functions=self.functions, callbacks=callbacks)
        else:
            predicted_message = self.llm.invoke(messages, callbacks=callbacks)

        # 4. 解析 AIMessage
        return self.output_parser.parse_ai_message(predicted_message)
```

### 5.3 `create_openai_functions_agent` — 新版实现（`openai_functions_agent/base.py:287`）

```python
def create_openai_functions_agent(llm, tools, prompt):
    llm_with_tools = llm.bind(functions=[convert_to_openai_function(t) for t in tools])

    return (
        RunnablePassthrough.assign(
            agent_scratchpad=lambda x: format_to_openai_function_messages(x["intermediate_steps"]),
        )
        | prompt
        | llm_with_tools
        | OpenAIFunctionsAgentOutputParser()
    )
```

**关键变化**：
- 使用 `llm.bind(functions=[...])` 将函数绑定到 LLM
- 通过 LCEL `|` 管道构建处理链

### 5.4 OpenAIFunctionsAgentOutputParser（源码：`output_parsers/openai_functions.py:16`）

这是最关键的解析逻辑——直接从 `AIMessage.additional_kwargs["function_call"]` 提取结构化数据：

```python
class OpenAIFunctionsAgentOutputParser(AgentOutputParser):
    @staticmethod
    def parse_ai_message(message: BaseMessage) -> AgentAction | AgentFinish:
        if not isinstance(message, AIMessage):
            raise TypeError(f"Expected an AI message got {type(message)}")

        function_call = message.additional_kwargs.get("function_call", {})

        if function_call:
            # 模型返回了 function_call — 需要调用工具
            function_name = function_call["name"]

            if len(function_call["arguments"].strip()) == 0:
                _tool_input = {}  # 无参数函数
            else:
                _tool_input = json.loads(function_call["arguments"], strict=False)

            # 兼容处理：单字符串参数的工具
            if "__arg1" in _tool_input:
                tool_input = _tool_input["__arg1"]
            else:
                tool_input = _tool_input

            log = f"\nInvoking: `{function_name}` with `{tool_input}`\n"
            return AgentActionMessageLog(
                tool=function_name,
                tool_input=tool_input,
                log=log,
                message_log=[message],  # 保留原始消息
            )

        # 无 function_call — 模型的文本回复即为最终答案
        return AgentFinish(
            return_values={"output": message.content},
            log=str(message.content),
        )
```

### 5.5 中间步骤格式化（`format_scratchpad/openai_functions.py`）

```python
def format_to_openai_function_messages(intermediate_steps):
    messages = []
    for agent_action, observation in intermediate_steps:
        messages.extend(_convert_agent_action_to_messages(agent_action, observation))
    return messages

def _convert_agent_action_to_messages(agent_action, observation):
    if isinstance(agent_action, AgentActionMessageLog):
        return [
            *list(agent_action.message_log),   # 原始 AI 消息
            _create_function_message(agent_action, observation),  # FunctionMessage
        ]
    return [AIMessage(content=agent_action.log)]

def _create_function_message(agent_action, observation):
    content = observation if isinstance(observation, str) else json.dumps(observation)
    return FunctionMessage(name=agent_action.tool, content=content)
```

### 5.6 关键创新：从文本解析到结构化调用

OpenAI Functions Agent 的最大创新是**消除了文本解析的脆弱性**：

| 对比维度 | ReAct/MRKL | OpenAI Functions Agent |
|---------|-----------|----------------------|
| 工具指定方式 | 文本 `Action: tool_name` | `function_call.name` |
| 参数传递 | 文本字符串（需手动解析） | JSON 对象（自动解析） |
| 错误处理 | 正则匹配失败→重试 | 结构保证，极大减少解析失败 |
| 多参数工具 | 需要约定格式 | 原生支持 JSON schema |

### 5.7 `__arg1` 兼容性 Hack

对于不接受结构化输入、只期望单个字符串参数的旧式工具，LangChain 引入了一个兼容性处理：

```python
if "__arg1" in _tool_input:
    tool_input = _tool_input["__arg1"]
else:
    tool_input = _tool_input
```

这是因为 OpenAI 在将工具转换为 function 时，对不接受 schema 的工具使用 `__arg1` 作为参数名。

---

## 六、Tool Calling Agent 模式

### 6.1 概述

Tool Calling Agent 是 LangChain 中**当前推荐的通用 agent 模式**。它是 OpenAI Functions Agent 的进化版，使用更通用的 `tool_calls` 机制（而非 OpenAI 专有的 `functions` 参数）。它支持任何实现了 `bind_tools` 方法的聊天模型。

### 6.2 `create_tool_calling_agent`（源码：`tool_calling_agent/base.py:18`）

```python
def create_tool_calling_agent(llm, tools, prompt, *, message_formatter=format_to_tool_messages):
    if "agent_scratchpad" not in (prompt.input_variables + list(prompt.partial_variables)):
        raise ValueError(...)

    if not hasattr(llm, "bind_tools"):
        raise ValueError("This function requires a bind_tools() method be implemented on the LLM.")

    llm_with_tools = llm.bind_tools(tools)

    return (
        RunnablePassthrough.assign(
            agent_scratchpad=lambda x: message_formatter(x["intermediate_steps"]),
        )
        | prompt
        | llm_with_tools
        | ToolsAgentOutputParser()
    )
```

**关键点**：
- `llm.bind_tools(tools)` — 使用通用的 `bind_tools` 接口（而非 OpenAI 专有的 `functions`）
- `ToolsAgentOutputParser` — 通用的工具调用输出解析器
- `message_formatter` — 默认使用 `format_to_tool_messages`，将中间步骤格式化为 `ToolMessage`

### 6.3 ToolsAgentOutputParser（源码：`output_parsers/tools.py:87`）

```python
class ToolsAgentOutputParser(MultiActionAgentOutputParser):
    def parse_result(self, result, *, partial=False):
        if not isinstance(result[0], ChatGeneration):
            raise ValueError("This output parser only works on ChatGeneration output")
        message = result[0].message
        return parse_ai_message_to_tool_action(message)
```

核心解析逻辑 `parse_ai_message_to_tool_action`：

```python
def parse_ai_message_to_tool_action(message: BaseMessage):
    if not isinstance(message, AIMessage):
        raise TypeError(...)

    actions = []
    if message.tool_calls:
        tool_calls = message.tool_calls
    else:
        if not message.additional_kwargs.get("tool_calls"):
            # 无 tool_calls → 最终答案
            return AgentFinish(return_values={"output": message.content}, log=str(message.content))

        # 兼容解析 additional_kwargs 中的 tool_calls
        tool_calls = []
        for tool_call in message.additional_kwargs["tool_calls"]:
            function = tool_call["function"]
            function_name = function["name"]
            args = json.loads(function["arguments"] or "{}")
            tool_calls.append(ToolCall(type="tool_call", name=function_name, args=args, id=tool_call["id"]))

    for tool_call in tool_calls:
        function_name = tool_call["name"]
        _tool_input = tool_call["args"]
        tool_input = _tool_input.get("__arg1", _tool_input)  # 同样的 __arg1 兼容性处理

        log = f"\nInvoking: `{function_name}` with `{tool_input}`\n"
        actions.append(ToolAgentAction(
            tool=function_name,
            tool_input=tool_input,
            log=log,
            message_log=[message],
            tool_call_id=tool_call["id"],  # 保留 tool_call_id 用于匹配响应
        ))
    return actions
```

### 6.4 MultiActionAgentOutputParser — 支持多工具调用

Tool Calling Agent 实现为 `MultiActionAgentOutputParser`（而非 `AgentOutputParser`），这意味着它**支持一次返回多个 tool call**。这是对 OpenAI Functions Agent 的重要升级。

```python
class ToolsAgentOutputParser(MultiActionAgentOutputParser):
    def parse_result(self, result, *, partial=False) -> list[AgentAction] | AgentFinish:
```

### 6.5 中间步骤格式化（`format_scratchpad/tools.py`）

```python
def format_to_tool_messages(intermediate_steps):
    messages = []
    for agent_action, observation in intermediate_steps:
        if isinstance(agent_action, ToolAgentAction):
            new_messages = [
                *list(agent_action.message_log),          # 原始 AI 消息（包含 tool_calls）
                _create_tool_message(agent_action, observation),  # ToolMessage 响应
            ]
            messages.extend([new for new in new_messages if new not in messages])
        else:
            messages.append(AIMessage(content=agent_action.log))
    return messages

def _create_tool_message(agent_action, observation):
    content = observation if isinstance(observation, str) else json.dumps(observation)
    return ToolMessage(
        tool_call_id=agent_action.tool_call_id,
        content=content,
        additional_kwargs={"name": agent_action.tool},
    )
```

### 6.6 Tool Calling Agent vs OpenAI Functions Agent

| 特性 | OpenAI Functions Agent | Tool Calling Agent |
|------|----------------------|-------------------|
| 接口 | `llm.bind(functions=...)` | `llm.bind_tools(tools)` |
| 输出解析 | `AgentOutputParser`（单 action） | `MultiActionAgentOutputParser`（多 action） |
| 并行工具调用 | 不支持 | 支持 |
| 模型支持 | 仅 OpenAI 兼容模型 | 任何实现 `bind_tools` 的模型 |
| 消息格式 | `FunctionMessage` | `ToolMessage` |
| `tool_call_id` | 无 | 有（精确匹配工具调用和响应） |
| 成熟度 | Deprecated | 当前推荐 |

---

## 七、OpenAI Tools Agent 模式

### 7.1 概述

OpenAI Tools Agent（`create_openai_tools_agent`，`openai_tools/base.py:17`）是 OpenAI Functions Agent 到 Tool Calling Agent 的过渡形态。它使用 OpenAI 的 `tools` 参数（而非已弃用的 `functions` 参数），但仍使用 OpenAI 特定格式。

### 7.2 `create_openai_tools_agent`（源码：`openai_tools/base.py:17`）

```python
def create_openai_tools_agent(llm, tools, prompt, strict=None):
    llm_with_tools = llm.bind(
        tools=[convert_to_openai_tool(tool, strict=strict) for tool in tools],
    )

    return (
        RunnablePassthrough.assign(
            agent_scratchpad=lambda x: format_to_openai_tool_messages(x["intermediate_steps"]),
        )
        | prompt
        | llm_with_tools
        | OpenAIToolsAgentOutputParser()
    )
```

### 7.3 与其他模式的精确对比

```
模式                       LLM参数        输出解析器                    scratchpad格式
────────────────────────────────────────────────────────────────────────────────────
OpenAI Functions   →  functions=...    OpenAIFunctionsAgent...      FunctionMessage
OpenAI Tools       →  tools=...        OpenAIToolsAgent...          ToolMessage
Tool Calling       →  bind_tools(...)  ToolsAgentOutputParser       ToolMessage
```

---

## 八、Structured Chat Agent 模式

### 8.1 概述

Structured Chat Agent 是一种使用 JSON blob 来指定工具调用的 agent 模式，专门为**多输入参数的工具**设计。传统的 ReAct/MRKL 格式 `Action Input: some text` 只能传递单个字符串，而 JSON 可以传递任意复杂结构。

### 8.2 StructuredChatAgent（源码：`structured_chat/base.py:39`）

```python
@deprecated("0.1.0", alternative="create_structured_chat_agent", removal="2.0.0")
class StructuredChatAgent(Agent):
    output_parser: AgentOutputParser = Field(default_factory=StructuredChatOutputParserWithRetries)

    @property
    def observation_prefix(self) -> str:
        return "Observation: "

    @property
    def llm_prefix(self) -> str:
        return "Thought:"
```

### 8.3 Prompt 结构（源码：`structured_chat/prompt.py`）

```python
PREFIX = """Respond to the human as helpfully and accurately as possible. You have access to the following tools:"""

FORMAT_INSTRUCTIONS = """Use a json blob to specify a tool by providing an action key (tool name) and an action_input key (tool input).

Valid "action" values: "Final Answer" or {tool_names}

Provide only ONE action per $JSON_BLOB, as shown:

```
{{{{
  "action": $TOOL_NAME,
  "action_input": $INPUT
}}}}
```

Follow this format:

Question: input question to answer
Thought: consider previous and subsequent steps
Action:
```
$JSON_BLOB
```
Observation: action result
... (repeat Thought/Action/Observation N times)
Thought: I know what to respond
Action:
```
{{{{
  "action": "Final Answer",
  "action_input": "Final response to human"
}}}}
```"""

SUFFIX = """Begin! Reminder to ALWAYS respond with a valid json blob of a single action. Use tools if necessary. Respond directly if appropriate. Format is Action:```$JSON_BLOB```then Observation:.
Thought:"""
```

### 8.4 Tool 渲染（`structured_chat/base.py:93`）

Structured Chat Agent 在渲染工具时包含了参数 schema：

```python
tool_strings = []
for tool in tools:
    args_schema = re.sub("}", "}}", re.sub("{", "{{", str(tool.args)))
    tool_strings.append(f"{tool.name}: {tool.description}, args: {args_schema}")
```

这与其他模式显著不同——它明确告诉 LLM 每个工具有哪些参数及其类型。

### 8.5 StructuredChatOutputParser（源码：`structured_chat/output_parser.py:21`）

```python
class StructuredChatOutputParser(AgentOutputParser):
    pattern: Pattern = re.compile(r"```(?:json\s+)?(\W.*?)```", re.DOTALL)

    def parse(self, text: str) -> AgentAction | AgentFinish:
        try:
            action_match = self.pattern.search(text)
            if action_match is not None:
                response = json.loads(action_match.group(1).strip(), strict=False)
                if isinstance(response, list):
                    logger.warning("Got multiple action responses: %s", response)
                    response = response[0]  # GPT 经常忽略单 action 指令
                if response["action"] == "Final Answer":
                    return AgentFinish({"output": response["action_input"]}, text)
                return AgentAction(
                    response["action"],
                    response.get("action_input", {}),
                    text,
                )
            return AgentFinish({"output": text}, text)  # 无 JSON 块 → 视为最终答案
        except Exception as e:
            raise OutputParserException(...)
```

### 8.6 StructuredChatOutputParserWithRetries（源码：`structured_chat/output_parser.py:62`）

这是 Structured Chat Agent 的独特机制——**带重试的输出解析器**：

```python
class StructuredChatOutputParserWithRetries(AgentOutputParser):
    base_parser: AgentOutputParser = Field(default_factory=StructuredChatOutputParser)
    output_fixing_parser: OutputFixingParser | None = None

    def parse(self, text: str) -> AgentAction | AgentFinish:
        try:
            if self.output_fixing_parser is not None:
                return self.output_fixing_parser.parse(text)
            return self.base_parser.parse(text)
        except Exception as e:
            raise OutputParserException(...)

    @classmethod
    def from_llm(cls, llm=None, base_parser=None):
        if llm is not None:
            base_parser = base_parser or StructuredChatOutputParser()
            output_fixing_parser = OutputFixingParser.from_llm(llm=llm, parser=base_parser)
            return cls(output_fixing_parser=output_fixing_parser)
```

**`OutputFixingParser` 的工作原理**：当基础解析器失败时，将原始 LLM 输出和解析错误一起发送给 LLM，请求 LLM 修正格式。

### 8.7 `create_structured_chat_agent` — 新版实现（`structured_chat/base.py:166`）

```python
def create_structured_chat_agent(llm, tools, prompt, tools_renderer=render_text_description_and_args,
                                 *, stop_sequence=True):
    prompt = prompt.partial(
        tools=tools_renderer(list(tools)),
        tool_names=", ".join([t.name for t in tools]),
    )
    if stop_sequence:
        stop = ["\nObservation"] if stop_sequence is True else stop_sequence
        llm_with_stop = llm.bind(stop=stop)
    else:
        llm_with_stop = llm

    return (
        RunnablePassthrough.assign(
            agent_scratchpad=lambda x: format_log_to_str(x["intermediate_steps"]),
        )
        | prompt
        | llm_with_stop
        | JSONAgentOutputParser()
    )
```

### 8.8 Scratchpad 的独特设计

Structured Chat Agent 在 scratchpad 开头添加了提示语，模拟"无状态"的多轮交互：

```python
def _construct_scratchpad(self, intermediate_steps):
    agent_scratchpad = super()._construct_scratchpad(intermediate_steps)
    if agent_scratchpad:
        return (
            f"This was your previous work "
            f"(but I haven't seen any of it! I only see what "
            f"you return as final answer):\n{agent_scratchpad}"
        )
    return agent_scratchpad
```

这个设计是因为 Chat Model 的无状态特性——每次 API 调用是独立的，需要显式地"提醒"模型之前的上下文。

---

## 九、输出解析器全景对比

### 9.1 解析器家族

```
AgentOutputParser (基类，parse: str → AgentAction | AgentFinish)
├── ReActOutputParser              # ReActDocstoreAgent 使用 (Action[Input] 格式)
├── ReActSingleInputOutputParser   # MRKL/通用 ReAct 风格
├── ReActJsonSingleInputOutputParser # ReAct + JSON code block
├── MRKLOutputParser              # MRKL/ZeroShotAgent 使用
├── ChatOutputParser              # ChatAgent 使用 (JSON code block)
├── StructuredChatOutputParser    # StructuredChatAgent 旧版
│   └── StructuredChatOutputParserWithRetries # 带 LLM 修正重试
├── JSONAgentOutputParser         # Structured Chat 新版 (markdown JSON)
├── XMLAgentOutputParser          # XML Agent 使用
├── SelfAskOutputParser           # SelfAskWithSearch 使用
├── OpenAIFunctionsAgentOutputParser  # OpenAI Functions Agent (解析 AIMessage)
├── OpenAIToolsAgentOutputParser      # OpenAI Tools Agent (解析 AIMessage)
└── ToolsAgentOutputParser            # Tool Calling Agent (MultiAction, 解析 AIMessage)

MultiActionAgentOutputParser (基类，parse: str → list[AgentAction] | AgentFinish)
└── ToolsAgentOutputParser
```

### 9.2 解析方式分类

#### 9.2.1 纯文本正则解析

**代表**：`ReActOutputParser`, `ReActSingleInputOutputParser`, `MRKLOutputParser`

**特点**：
- 从 LLM 的纯文本输出中通过正则提取工具名和参数
- 脆弱，容易受 LLM 输出格式波动影响
- `send_to_llm=True` 机制可将解析错误反馈给 LLM 自我修正

**示例格式**：
```
Thought: I need to search
Action: search
Action Input: query text
```

#### 9.2.2 JSON 解析

**代表**：`ChatOutputParser`, `StructuredChatOutputParser`, `JSONAgentOutputParser`, `ReActJsonSingleInputOutputParser`

**特点**：
- LLM 输出 JSON blob（通常在 markdown code block 中）
- 使用 `json.loads` 解析
- 比纯文本解析更可靠，但仍可能格式错误

**示例格式**：
```json
{
  "action": "search",
  "action_input": "query text"
}
```

#### 9.2.3 结构化消息解析

**代表**：`OpenAIFunctionsAgentOutputParser`, `OpenAIToolsAgentOutputParser`, `ToolsAgentOutputParser`

**特点**：
- 不解析文本，而是从 LLM 返回的 `AIMessage.additional_kwargs` 中提取结构化数据
- 100% 结构化，无解析错误
- 这是最可靠的解析方式

**数据来源**：
```python
# OpenAI Functions
message.additional_kwargs["function_call"]  # {"name": "xxx", "arguments": "{...}"}

# Tool Calling
message.tool_calls  # [{"name": "xxx", "args": {...}, "id": "xxx"}]
```

### 9.3 解析失败的优雅降级

所有基于文本解析的 parser 都支持 `send_to_llm=True` 机制：

```python
# MRKLOutputParser 中的处理
raise OutputParserException(
    msg,
    observation="Invalid Format: Missing 'Action:' after 'Thought:'",
    llm_output=text,
    send_to_llm=True,  # ← 关键标志
)
```

当 `send_to_llm=True` 时，`AgentExecutor._iter_next_step` 不会直接抛错，而是：

```python
except OutputParserException as e:
    if e.send_to_llm:
        observation = str(e.observation)  # 将错误信息作为 observation
        text = str(e.llm_output)         # 保留原始 LLM 输出
    else:
        observation = "Invalid or incomplete response"

    # 创建 _Exception 工具调用，observation 中包含错误信息
    output = AgentAction("_Exception", observation, text)
    # 这样 LLM 在下一轮可以看到错误并尝试修正
```

---

## 十、模式演进路线图 (deprecated vs current)

### 10.1 演进全景

```
LangChain v0 (原始)
    │
    ├── ReActDocstoreAgent           [deprecated, removal 2.0.0]
    ├── SelfAskWithSearchAgent       [deprecated, removal 2.0.0]
    ├── ZeroShotAgent (MRKL)         [deprecated, removal 2.0.0]
    ├── ChatAgent                    [deprecated, removal 2.0.0]
    ├── ConversationalAgent          [deprecated, removal 2.0.0]
    ├── ConversationalChatAgent      [deprecated, removal 2.0.0]
    ├── StructuredChatAgent          [deprecated, removal 2.0.0]
    │
    ├── XMLAgent                     [deprecated, removal 2.0.0]
    │   └── create_xml_agent()       [current]
    │
    ├── OpenAIFunctionsAgent         [deprecated, removal 2.0.0]
    │   └── create_openai_functions_agent() [current, 但推荐迁移到 create_tool_calling_agent]
    │
    ├── OpenAIMultiFunctionsAgent    [deprecated, removal 2.0.0]
    │
    ├── create_openai_tools_agent()  [current, OpenAI 特定]
    │
    ├── create_tool_calling_agent()  [current, 推荐通用方案]
    │
    └── create_structured_chat_agent() [current]

LangChain v1 (新范式)
    │
    └── create_agent()               [当前推荐, LangGraph 原生]
            使用 Middleware + Structured Output
            替代所有旧的 AgentExecutor 模式
```

### 10.2 废弃层次

| 层次 | 类/函数 | 状态 |
|------|--------|------|
| L0: 已移除 | — | — |
| L1: 旧类 (class) | `ReActDocstoreAgent`, `ZeroShotAgent`, `ChatAgent`, `StructuredChatAgent`, `OpenAIFunctionsAgent`, `XMLAgent`, `Agent` | `@deprecated("0.1.0", removal="2.0.0")` |
| L2: 旧工厂函数 | `initialize_agent` | `@deprecated("0.1.0", removal="2.0.0")` |
| L3: 过渡函数 | `create_openai_functions_agent`, `create_openai_tools_agent` | 可用但推荐迁移 |
| L4: 当前推荐 | `create_tool_calling_agent`, `create_xml_agent`, `create_structured_chat_agent` | 推荐 |
| L5: 新范式 | `langchain.agents.create_agent` | 最新推荐 |

### 10.3 废弃原因

旧式 Agent 类（`Agent`, `LLMSingleActionAgent` 等）被废弃的核心原因：

1. **紧耦合**：Agent 类与 LLMChain 紧耦合，不支持 Runnable 管道
2. **不支持流式**：旧架构对流式(token streaming)支持不完善
3. **单 Action 限制**：`BaseSingleActionAgent` 不支持并行工具调用
4. **Prompt 管理复杂**：Prompt 模板分散在各个类中，不灵活
5. **无法与 LangGraph 集成**：AgentExecutor 不能直接作为 LangGraph 节点使用

新式函数式 API（`create_*_agent`）使用 LCEL 管道，具有更好的可组合性。

---

## 十一、LangChain v1 新范式：create_agent

### 11.1 概述

`create_agent`（定义于 `langchain_v1/langchain/agents/factory.py:697`）是 LangChain v1 中的核心 API，完全不同于旧的 AgentExecutor 架构。它基于 LangGraph 的 `StateGraph`，使用中间件模式。

### 11.2 架构对比

```
旧架构（AgentExecutor）:
    while loop:
        agent.plan() → AgentAction
        tool.run() → observation
        intermediate_steps.append((action, observation))

新架构（create_agent）:
    StateGraph:
        START → [before_agent middleware...] → model → [after_model middleware...]
                    ↑                                                      ↓
                    └──────────────── tools ←──────────────────────────────┘
                    ↓
        [after_agent middleware...] → END
```

### 11.3 中间件系统

新架构引入了完整的中件间系统（`middleware/types.py`）：

```python
class AgentMiddleware(Generic[StateT_co, ContextT]):
    def before_agent(self, state, runtime)      # agent 启动前
    def before_model(self, state, runtime)       # 每次模型调用前
    def after_model(self, state, runtime)        # 每次模型调用后
    def after_agent(self, state, runtime)        # agent 结束后
    def wrap_model_call(self, request, handler)  # 包装模型调用
    def wrap_tool_call(self, request, handler)   # 包装工具调用
```

内置中间件包括：
- `summarization` — 自动摘要长对话
- `human_in_the_loop` — 人工审核
- `model_retry` — 模型调用重试
- `model_fallback` — 模型降级
- `tool_retry` — 工具调用重试
- `tool_call_limit` — 限制工具调用次数
- `model_call_limit` — 限制模型调用次数
- `context_editing` — 上下文编辑
- `todo` — 任务列表管理
- `file_search` — 文件搜索
- `shell_tool` — Shell 工具
- `pii` — PII 检测

### 11.4 结构化输出

新架构原生支持三种结构化输出策略：

```python
# 1. ToolStrategy — 通过工具调用实现结构化输出
response_format = ToolStrategy(schema=MyPydanticModel)

# 2. ProviderStrategy — 使用模型原生结构化输出（如 OpenAI json_schema）
response_format = ProviderStrategy(schema=MyPydanticModel)

# 3. AutoStrategy — 自动选择最优策略
response_format = MyPydanticModel  # 自动包装为 AutoStrategy
```

### 11.5 API 签名对比

```python
# 旧 API
agent_executor = AgentExecutor(agent=agent, tools=tools)
result = agent_executor.invoke({"input": "..."})

# 新 API
graph = create_agent(
    model="anthropic:claude-sonnet-4-5-20250929",
    tools=[check_weather],
    system_prompt="You are a helpful assistant",
    middleware=[SummarizationMiddleware()],
    response_format=MyOutputSchema,
    checkpointer=SqliteSaver(...),  # 内置持久化
)
result = graph.invoke({"messages": [{"role": "user", "content": "..."}]})
graph.stream(inputs, stream_mode="updates")  # 原生流式
```

### 11.6 迁移建议

对使用旧 agent 模式的代码，迁移路径为：

1. `initialize_agent(tools, llm)` → `create_agent(model, tools, system_prompt=...)`
2. `ZeroShotAgent` → `create_tool_calling_agent` 或 `create_agent`
3. `OpenAIFunctionsAgent` → `create_tool_calling_agent` 或 `create_agent`
4. `StructuredChatAgent` → `create_structured_chat_agent` 或 `create_agent`
5. 自定义中间件 → 实现 `AgentMiddleware` 子类

---

## 十二、模式对比与选型指南

### 12.1 综合对比表

| 维度 | ReAct Docstore | MRKL ZeroShot | XML Agent | OpenAI Functions | Tool Calling | Structured Chat | LangChain v1 create_agent |
|------|:---:|:---:|:---:|:---:|:---:|:---:|:---:|
| **工具参数** | 单字符串 | 单字符串 | 任意 | 结构化 JSON | 结构化 JSON | 结构化 JSON | 结构化 JSON |
| **并行工具调用** | 否 | 否 | 否 | 否 | 是 | 否 | 是 |
| **输出格式** | `Action[Input]` | `Action:\nAction Input:` | `<tool>` XML | `function_call` | `tool_calls` | JSON blob | `tool_calls` |
| **解析方式** | 正则 | 正则 | XML 正则 | 消息结构 | 消息结构 | JSON 正则 | 消息结构 |
| **解析可靠性** | 低 | 低 | 中 | 高 | 高 | 中 | 高 |
| **模型要求** | 任意 | 任意 | 任意 | OpenAI 兼容 | `bind_tools` | 任意 Chat | `init_chat_model` |
| **流式支持** | 有限 | 有限 | 有限 | 有限 | 好 | 有限 | 原生 |
| **持久化** | 无 | 无 | 无 | 无 | 无 | 无 | 内置 Checkpointer |
| **中间件** | 无 | 无 | 无 | 无 | 无 | 无 | 完整中间件系统 |
| **状态** | Deprecated | Deprecated | 新版可用 | 过渡期 | 推荐 | 可用 | 最新推荐 |
| **适用场景** | 文档检索 | 通用（旧） | 通用 | OpenAI 生态 | 通用（推荐） | 多参数工具 | 新一代通用 |

### 12.2 选型决策树

```
需要构建 Agent？
│
├── 使用 LangChain v1 新项目？
│   └── 是 → 使用 create_agent() (LangGraph 原生，中间件，结构化输出)
│
├── 使用旧版 LangChain (langchain_classic)？
│   │
│   ├── 模型支持 bind_tools()？
│   │   ├── 是 → create_tool_calling_agent()  ← 首选
│   │   └── 否 →
│   │       ├── 模型支持 functions 参数（仅 OpenAI）？
│   │       │   └── 是 → create_openai_tools_agent() 或 create_openai_functions_agent()
│   │       │
│   │       ├── 工具需要多参数？
│   │       │   └── 是 → create_structured_chat_agent()
│   │       │
│   │       ├── 偏好 XML 格式？
│   │       │   └── 是 → create_xml_agent()
│   │       │
│   │       └── 简单场景/旧项目兼容？
│   │           └── ZeroShotAgent (MRKL) 或 ChatAgent
│   │
│   └── 需要并行工具调用？
│       ├── 是 → create_tool_calling_agent()（唯一支持并行的旧模式）
│       └── 否 → 任意模式均可
```

### 12.3 实践建议

1. **新项目一律使用 `create_agent()`**。它提供了最好的开发体验、最完整的中间件生态和 LangGraph 的原生集成。

2. **如果必须使用 `langchain_classic`**，首选 `create_tool_calling_agent()`。它支持最广泛的模型，提供并行工具调用，解析可靠性高。

3. **避免使用 `create_openai_functions_agent()`**。`functions` 参数已被 OpenAI 废弃，应迁移到 `tools` 参数。

4. **文本解析模式（ReAct/MRKL）仅用于教学和理解原理**。在生产环境中，正则解析 LLM 输出非常脆弱，应尽量避免。

5. **`create_structured_chat_agent()` 的适用场景有限**。现代模型普遍支持 `tool_calls`，不需要 JSON blob 格式。仅在模型不支持 `bind_tools` 但支持 JSON 输出时考虑。

6. **`create_xml_agent()` 在 Claude 模型上表现良好**。Claude 对 XML 格式有天然的遵循能力，如果使用 Anthropic 模型，这是 `create_tool_calling_agent` 之外的一个可选方案。

### 12.4 从 ReAct 到 Tool Calling 的技术演进总结

```
阶段1: 纯文本推理 (ReAct, MRKL)
   LLM 输出: "Thought: ... Action: search Action Input: query"
   解析: 正则表达式匹配
   问题: 格式不稳定，解析脆弱

阶段2: 半结构化 (Structured Chat, XML, Chat)
   LLM 输出: ```json {"action": "search", "action_input": "query"}```
   解析: JSON.loads 或 XML 解析
   问题: 仍依赖文本格式，code block 提取可能失败

阶段3: 结构化调用 (OpenAI Functions)
   LLM 输出: AIMessage(function_call={name: "search", arguments: "{...}"})
   解析: 直接读取消息结构
   问题: 仅 OpenAI 生态，单次单工具

阶段4: 通用结构化调用 (Tool Calling)
   LLM 输出: AIMessage(tool_calls=[{name: "search", args: {...}, id: "xxx"}])
   解析: 直接读取消息结构
   优势: 跨模型，支持并行多工具，tool_call_id 精确匹配

阶段5: 图原生 Agent (LangChain v1 create_agent)
   执行引擎: LangGraph StateGraph (而非 while 循环)
   扩展性: 中间件系统
   持久化: 内置 Checkpointer
   流式: 原生 support
```

---

## 附录：关键源文件索引

| 文件路径 | 内容 |
|---------|------|
| `langchain_classic/agents/agent.py` | AgentExecutor, Agent, BaseSingleActionAgent, BaseMultiActionAgent, RunnableAgent, AgentOutputParser |
| `langchain_classic/agents/agent_iterator.py` | AgentExecutorIterator 流式迭代器 |
| `langchain_classic/agents/types.py` | AGENT_TO_CLASS 映射表 |
| `langchain_classic/agents/agent_types.py` | AgentType 枚举定义 |
| `langchain_classic/agents/initialize.py` | initialize_agent 工厂函数 (deprecated) |
| `langchain_classic/agents/react/base.py` | ReActDocstoreAgent, ReActTextWorldAgent, ReActChain |
| `langchain_classic/agents/react/output_parser.py` | ReActOutputParser (Action[Input] 格式) |
| `langchain_classic/agents/mrkl/base.py` | ZeroShotAgent, MRKLChain |
| `langchain_classic/agents/mrkl/output_parser.py` | MRKLOutputParser |
| `langchain_classic/agents/mrkl/prompt.py` | MRKL Prompt 模板 (PREFIX, FORMAT_INSTRUCTIONS, SUFFIX) |
| `langchain_classic/agents/xml/base.py` | XMLAgent (deprecated), create_xml_agent (新版) |
| `langchain_classic/agents/xml/prompt.py` | XML Agent 指令模板 |
| `langchain_classic/agents/openai_functions_agent/base.py` | OpenAIFunctionsAgent (deprecated), create_openai_functions_agent (新版) |
| `langchain_classic/agents/openai_tools/base.py` | create_openai_tools_agent |
| `langchain_classic/agents/tool_calling_agent/base.py` | create_tool_calling_agent (推荐) |
| `langchain_classic/agents/structured_chat/base.py` | StructuredChatAgent (deprecated), create_structured_chat_agent |
| `langchain_classic/agents/structured_chat/output_parser.py` | StructuredChatOutputParser, StructuredChatOutputParserWithRetries |
| `langchain_classic/agents/structured_chat/prompt.py` | Structured Chat Prompt 模板 |
| `langchain_classic/agents/chat/base.py` | ChatAgent (deprecated) |
| `langchain_classic/agents/chat/output_parser.py` | ChatOutputParser |
| `langchain_classic/agents/output_parsers/__init__.py` | 所有输出解析器导出 |
| `langchain_classic/agents/output_parsers/json.py` | JSONAgentOutputParser |
| `langchain_classic/agents/output_parsers/tools.py` | ToolsAgentOutputParser, ToolAgentAction, parse_ai_message_to_tool_action |
| `langchain_classic/agents/output_parsers/openai_functions.py` | OpenAIFunctionsAgentOutputParser |
| `langchain_classic/agents/output_parsers/openai_tools.py` | OpenAIToolsAgentOutputParser |
| `langchain_classic/agents/output_parsers/react_single_input.py` | ReActSingleInputOutputParser |
| `langchain_classic/agents/output_parsers/react_json_single_input.py` | ReActJsonSingleInputOutputParser |
| `langchain_classic/agents/output_parsers/xml.py` | XMLAgentOutputParser |
| `langchain_classic/agents/format_scratchpad/__init__.py` | Scratchpad 格式化函数导出 |
| `langchain_classic/agents/format_scratchpad/log.py` | format_log_to_str (文本 scratchpad) |
| `langchain_classic/agents/format_scratchpad/log_to_messages.py` | format_log_to_messages (消息 scratchpad) |
| `langchain_classic/agents/format_scratchpad/openai_functions.py` | format_to_openai_function_messages |
| `langchain_classic/agents/format_scratchpad/tools.py` | format_to_tool_messages |
| `langchain_classic/agents/format_scratchpad/xml.py` | format_xml |
| `langchain_classic/_api/deprecation.py` | AGENT_DEPRECATION_WARNING 定义 |
| `langchain_v1/langchain/agents/factory.py` | create_agent (v1 新范式，LangGraph 原生) |
