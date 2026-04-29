# 引擎-06-Tool 工具执行层

**Metadata**
- Date: 2026-04-29
- 所属模块: lyclaw-engine
- 包路径: `lyjew.com.lyclaw.tool`
- 依赖: lyclaw-common（ToolCall、ToolDefinition）、EventBus（发布 ToolCalledEvent）、ModelProvider（获取 ModelAdapter 用于循环中的模型调用）
- 并行前提: 依赖 EventBus 接口（轻量），和 Context/Interceptor 层可并行

---

## 核心职责

1. 工具的定义、注册、发现
2. 工具执行（含超时控制）
3. 模型调用 + 工具执行的循环控制（ToolCallLoop）

---

## 需要实现的类清单

### 1. ToolResult — 工具执行结果

**文件**: `tool/ToolResult.java`
**包**: `lyjew.com.lyclaw.tool`

| 元素 | 说明 |
|------|------|
| 类型 | 类（POJO） |

| 属性 | 类型 | 说明 |
|------|------|------|
| status | String | "SUCCESS" / "ERROR" / "TIMEOUT" |
| content | String | 执行结果文本 |
| errorMessage | String | 错误信息 |
| durationMs | long | 执行耗时毫秒 |

| 方法 | 返回值 | 说明 |
|------|--------|------|
| ToolResult success(String content, long durationMs) | ToolResult | 静态工厂 |
| ToolResult error(String errorMessage, long durationMs) | ToolResult | 静态工厂 |
| ToolResult timeout(long durationMs) | ToolResult | 静态工厂 |
| isSuccess() | boolean | status.equals("SUCCESS") |
| 所有属性 Getter | - | - |

---

### 2. Tool — 工具接口

**文件**: `tool/Tool.java`
**包**: `lyjew.com.lyclaw.tool`

| 元素 | 说明 |
|------|------|
| 类型 | 接口 |
| 设计模式 | 命令模式 |

**方法**:
| 方法签名 | 返回值 | 说明 |
|----------|--------|------|
| String getName() | String | 工具名称，全局唯一。如 "web_search"、"calculator" |
| String getDescription() | String | 工具描述，会被发送给模型让模型理解工具用途 |
| ToolDefinition getDefinition() | ToolDefinition | 返回工具定义（name + description + parameters JSON Schema） |
| ToolResult execute(Map\<String, Object\> arguments) | ToolResult | 执行工具 |
| long getTimeout() | long | 超时时间（毫秒），0 表示使用默认超时（默认 30 秒） |

---

### 3. ToolDefinition — 工具定义

**文件**: `lyclaw-common` 中已存在 `lyjew.com.lyclaw.model.ToolDefinition`

如果已有字段不足，补充：

| 字段 | 类型 | 说明 |
|------|------|------|
| name | String | 工具名称 |
| description | String | 工具描述 |
| parameters | Map\<String, Object\> | 参数 JSON Schema（符合 OpenAI function calling 格式） |

---

### 4. ToolRegistry — 工具注册表

**文件**: `tool/ToolRegistry.java`
**包**: `lyjew.com.lyclaw.tool`

| 元素 | 说明 |
|------|------|
| 类型 | 类，@Component |
| 设计模式 | 注册表模式 |

**属性**:
| 名称 | 类型 | 说明 |
|------|------|------|
| tools | ConcurrentHashMap\<String, Tool\> | 工具名称 → 工具实例 |

**初始化**:
- 启动时 Spring 自动注入所有 Tool 实现
- 遍历每个 Tool，调用 getName() 获取名称，调用 getDefinition() 获取定义
- 存入 tools map

**方法**:
| 方法签名 | 返回值 | 说明 |
|----------|--------|------|
| void register(Tool tool) | void | 注册工具（运行时动态注册，MCP Server 连接时用） |
| void unregister(String toolName) | void | 移除工具 |
| Tool get(String toolName) | Tool | 根据名称获取工具，不存在抛出 NoSuchToolException |
| Map\<String, Tool\> getAll() | Map\<String, Tool\> | 获取所有注册的工具 |
| List\<ToolDefinition\> getAllDefinitions() | List\<ToolDefinition\> | 获取所有工具的定义列表（用于发送给模型） |
| ToolResult execute(String toolName, Map\<String, Object\> arguments) | ToolResult | 1. get(toolName) 获取工具<br>2. 获取超时时间 getTimeout()<br>3. 用 CompletableFuture 执行，超时则返回 ToolResult.timeout()<br>4. 发布 ToolCalledEvent |

---

### 5. NoSuchToolException — 工具不存在异常

**文件**: `tool/NoSuchToolException.java`
**包**: `lyjew.com.lyclaw.tool`

| 属性 | 类型 | 说明 |
|------|------|------|
| toolName | String | 不存在的工具名 |

| 方法 | 返回值 | 说明 |
|------|--------|------|
| NoSuchToolException(String toolName) | - | 构造器 |

---

### 6. ToolCallLoop — 工具调用循环

**文件**: `tool/ToolCallLoop.java`
**包**: `lyjew.com.lyclaw.tool`

| 元素 | 说明 |
|------|------|
| 类型 | 类，@Component |
| 设计模式 | 模板方法模式 |

**属性**:
| 名称 | 类型 | 说明 |
|------|------|------|
| modelProvider | ModelProvider | 获取模型适配器（来自 lyclaw-core） |
| toolRegistry | ToolRegistry | 执行工具 |
| toolCallPolicy | ToolCallPolicy | 循环终止策略 |
| eventBus | EventBus | 发布事件 |

**方法**:
| 方法签名 | 返回值 | 说明 |
|----------|--------|------|
| ModelResponse execute(ChatContext context) | ModelResponse | 核心循环逻辑：<br>1. rounds = 0<br>2. while (rounds < toolCallPolicy.getMaxRounds()) {<br>   - ModelResponse = modelProvider.getAdapter().chat(context)<br>   - if (!response.hasToolCalls()) return response<br>   - for (ToolCall tc : response.getToolCalls()) {<br>        toolRegistry.execute(tc.getName(), tc.getArguments())<br>        context.addToolResult(tc.getId(), result)<br>     }<br>   - if (!toolCallPolicy.shouldContinue(context, rounds)) break<br>   - rounds++<br>   }<br>3. 返回最后一次 ModelResponse |

---

### 7. ToolCallPolicy — 循环终止策略接口

**文件**: `tool/ToolCallPolicy.java`
**包**: `lyjew.com.lyclaw.tool`

| 元素 | 说明 |
|------|------|
| 类型 | 接口 |
| 设计模式 | 策略模式 |

**方法**:
| 方法签名 | 返回值 | 说明 |
|----------|--------|------|
| int getMaxRounds() | int | 最大允许轮次 |
| boolean shouldContinue(ChatContext context, int currentRound) | boolean | 判断是否继续下一轮 |
| ToolResult onToolError(ToolCall toolCall, Throwable error, int currentRound) | ToolResult | 工具执行错误时的处理。返回 ToolResult 作为兜底（可返回错误信息） |

---

### 8. DefaultToolCallPolicy — 默认循环策略

**文件**: `tool/impl/DefaultToolCallPolicy.java`
**包**: `lyjew.com.lyclaw.tool.impl`
**实现**: ToolCallPolicy

| 方法 | 行为 |
|------|------|
| getMaxRounds() | 返回 10 |
| shouldContinue(context, currentRound) | 如果 currentRound < getMaxRounds() 返回 true，否则返回 false |
| onToolError(toolCall, error, currentRound) | 记录日志，返回 ToolResult.error("工具执行失败: " + error.getMessage()) |

---

### 9. WebSearchTool — 网络搜索工具

**文件**: `tool/impl/WebSearchTool.java`
**包**: `lyjew.com.lyclaw.tool.impl`
**实现**: Tool

| 方法 | 返回值 | 说明 |
|------|--------|------|
| getName() | String | 返回 "web_search" |
| getDescription() | String | 返回 "搜索网络获取最新信息" |
| getDefinition() | ToolDefinition | 参数: query (string, required, 搜索关键词) |
| execute(Map\<String, Object\> args) | ToolResult | 调用 WebSearch API（需注入搜索客户端），返回结果摘要 |
| getTimeout() | long | 返回 30000 (30秒) |

**注**: WebSearchTool 内部需要调用外部搜索 API。建议注入一个 `SearchClient` 接口，具体实现（Brave Search / Bing Search / Google Search）由外部决定。

---

### 10. CalculatorTool — 计算器工具

**文件**: `tool/impl/CalculatorTool.java`
**包**: `lyjew.com.lyclaw.tool.impl`
**实现**: Tool

| 方法 | 返回值 | 说明 |
|------|--------|------|
| getName() | String | 返回 "calculator" |
| getDescription() | String | 返回 "计算数学表达式，支持 +-*/ 和括号" |
| getDefinition() | ToolDefinition | 参数: expression (string, required, 数学表达式) |
| execute(Map\<String, Object\> args) | ToolResult | 使用 ScriptEngine 或自定义解析器计算表达式。注意安全性，只允许数学运算 |
| getTimeout() | long | 返回 5000 (5秒) |

---

### 11. CurrentTimeTool — 当前时间工具

**文件**: `tool/impl/CurrentTimeTool.java`
**包**: `lyjew.com.lyclaw.tool.impl`
**实现**: Tool

| 方法 | 返回值 | 说明 |
|------|--------|------|
| getName() | String | 返回 "current_time" |
| getDescription() | String | 返回 "获取当前日期和时间" |
| getDefinition() | ToolDefinition | 参数: format (string, optional, 时间格式，如 "yyyy-MM-dd HH:mm:ss"，默认 "yyyy-MM-dd HH:mm:ss") |
| execute(Map\<String, Object\> args) | ToolResult | 获取当前时间，按格式格式化 |
| getTimeout() | long | 返回 1000 (1秒) |

---

### 12. ModelProvider（接口引用）

**文件**: `provider/ModelProvider.java`（这实际上属于 lyclaw-core 或在 engine 中新建接口）

设计文档提到 ModelProvider 定义在 engine 或 core 中。如果 lyclaw-core 已有 ModelAdapter 但无 ModelProvider，则在 engine 中新建此接口：

**包**: `lyjew.com.lyclaw.provider`

| 方法 | 返回值 | 说明 |
|------|--------|------|
| ModelAdapter getAdapter(String provider) | ModelAdapter | 根据厂商获取适配器 |
| List\<String\> listProviders() | List\<String\> | 列出所有可用厂商 |
| ModelAdapter getDefaultAdapter() | ModelAdapter | 获取默认适配器 |

**注意**: ToolCallLoop 依赖此接口来获取 ModelAdapter，不直接依赖 lyclaw-adapter 的具体类。

---

## 实现顺序

1. ToolResult（值对象）
2. Tool 接口
3. ToolDefinition（确认已有）
4. NoSuchToolException
5. ToolRegistry
6. ToolCallPolicy 接口 + DefaultToolCallPolicy
7. WebSearchTool / CalculatorTool / CurrentTimeTool
8. ToolCallLoop（依赖 ToolRegistry + ToolCallPolicy + ModelProvider）
9. ModelProvider（若 engine 中需新建）

## 校验清单

- [ ] Tool 接口含 getName、getDescription、getDefinition、execute、getTimeout
- [ ] ToolRegistry 启动时自动注入所有 Tool，存入 name→Tool 的 Map
- [ ] ToolRegistry.execute 有超时控制（CompletableFuture）
- [ ] ToolCallLoop 实现了"调用模型→检查工具→执行→注入→循环"的骨架
- [ ] DefaultToolCallPolicy 最多 10 轮
- [ ] 三个内置工具各有完善的定义和实现
- [ ] WebSearchTool 需要外部搜索客户端注入
- [ ] ToolCallLoop 不直接依赖 lyclaw-adapter，通过 ModelProvider 获取适配器
