# 引擎-03-ErrorPolicy 错误处理层

**Metadata**
- Date: 2026-04-29
- 所属模块: lyclaw-engine
- 包路径: `lyjew.com.lyclaw.error`
- 依赖: lyclaw-common DTO（ChatRequest / ChatContext 由引擎内定义），EventBus（可选，用于发布 ErrorEvent）
- 并行前提: 仅依赖 lyclaw-common，可最优先实现

---

## 核心职责

定义模型调用失败、工具执行失败、超时等场景的处理策略。第二版可扩展重试/熔断/降级。

---

## 需要实现的类清单

### 1. ChatResult — 对话结果 DTO

**文件**: `dto/ChatResult.java`
**包**: `lyjew.com.lyclaw.dto`

| 元素 | 说明 |
|------|------|
| 类型 | 类（POJO） |

**属性**:
| 名称 | 类型 | 说明 |
|------|------|------|
| sessionId | String | 会话 ID |
| message | String | AI 回复内容 |
| finishReason | String | 结束原因（"stop"、"tool_calls"、"error"、"interrupted"） |
| toolCalls | List\<ToolCall\> | 本次执行的工具调用记录 |
| usage | Usage | Token 用量 |
| error | String | 错误信息（finishReason="error" 时填充） |
| durationMs | long | 总耗时 |

**方法**:
| 方法签名 | 返回值 | 说明 |
|----------|--------|------|
| 所有属性的 Getter/Setter | - | - |
| ChatResult success(String sessionId, String message, Usage usage) | ChatResult | 静态工厂：构建成功结果，finishReason="stop" |
| ChatResult toolCallResult(...) | ChatResult | 静态工厂：构建工具调用结果（按需） |
| ChatResult error(String sessionId, String error) | ChatResult | 静态工厂：构建错误结果，finishReason="error" |

---

### 2. ErrorPolicy — 错误处理策略接口

**文件**: `error/ErrorPolicy.java`
**包**: `lyjew.com.lyclaw.error`

| 元素 | 说明 |
|------|------|
| 类型 | 接口 |
| 设计模式 | 策略模式 |

**方法**:
| 方法签名 | 返回值 | 说明 |
|----------|--------|------|
| ChatResult onModelError(ModelCallException e, ChatContext context) | ChatResult | 模型调用失败时调用。可重试（返回重试结果）、降级（返回备用模型结果）、或返回错误信息 |
| ToolResult onToolError(ToolExecuteException e, ChatContext context) | ToolResult | 工具执行失败时调用。可返回错误 ToolResult 或降级结果 |
| ChatResult onTimeout(ChatContext context, long elapsedMs) | ChatResult | 超时时调用。可返回超时错误或部分结果 |

---

### 3. ModelCallException — 模型调用异常

**文件**: `exception/ModelCallException.java`
**包**: `lyjew.com.lyclaw.error`（或 `exception` 包）

| 属性 | 类型 | 说明 |
|------|------|------|
| provider | String | 模型厂商（"deepseek"、"minimax" 等） |
| model | String | 模型名称 |
| statusCode | int | HTTP 状态码（0 表示连接失败） |
| retryable | boolean | 是否可重试（429/5xx 可重试，401/403 不可重试） |

| 方法 | 返回值 | 说明 |
|------|--------|------|
| ModelCallException(String provider, String model, int statusCode, String message, boolean retryable) | - | 构造器 |
| isRetryable() | boolean | 返回是否可重试 |

---

### 4. ToolExecuteException — 工具执行异常

**文件**: `exception/ToolExecuteException.java`
**包**: `lyjew.com.lyclaw.error`（或 `exception` 包）

| 属性 | 类型 | 说明 |
|------|------|------|
| toolName | String | 工具名称 |
| arguments | Map\<String, Object\> | 调用参数 |
| timeout | boolean | 是否超时 |

| 方法 | 返回值 | 说明 |
|------|--------|------|
| ToolExecuteException(String toolName, Map\<String, Object\> arguments, String message) | - | 构造器 |
| ToolExecuteException(String toolName, Map\<String, Object\> arguments, String message, Throwable cause) | - | 带 cause 构造器 |
| isTimeout() | boolean | - |

---

### 5. ToolResult — 工具执行结果

**文件**: `tool/ToolResult.java`（tool 包也会用到，但 error 包需要此类型作为 ErrorPolicy.onToolError 的返回值）

将其定义在 tool 包中，error 包依赖 tool 包。或者在 dto 包中定义。

**最终位置**: `tool/ToolResult.java`（tool 包）

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
| isSuccess() | boolean | - |

---

### 6. DefaultErrorPolicy — 默认错误处理策略

**文件**: `error/impl/DefaultErrorPolicy.java`
**包**: `lyjew.com.lyclaw.error.impl`
**实现**: ErrorPolicy

| 元素 | 说明 |
|------|------|
| 类型 | 类，@Component |

**逻辑**:
| 方法 | 行为 |
|------|------|
| onModelError(ModelCallException e, ChatContext ctx) | 如果 e.isRetryable() 返回 true，重试 1 次；若重试仍失败则返回 ChatResult.error()；不可重试直接返回错误 |
| onToolError(ToolExecuteException e, ChatContext ctx) | 返回 ToolResult.error()，携带错误信息 |
| onTimeout(ChatContext ctx, long elapsedMs) | 抛出 TimeoutException（由外层捕获处理） |

**属性**: 无特殊属性，纯逻辑类。

---

## 实现顺序

1. ToolResult（工具执行结果，tool 包也需要）
2. ModelCallException
3. ToolExecuteException
4. ChatResult（dto 包）
5. ErrorPolicy 接口
6. DefaultErrorPolicy

## 校验清单

- [ ] ErrorPolicy 定义了 3 个错误处理方法
- [ ] ModelCallException 区分可重试/不可重试
- [ ] DefaultErrorPolicy 对可重试模型错误重试 1 次
- [ ] DefaultErrorPolicy 工具错误返回 error ToolResult
- [ ] ChatResult 有静态工厂方法 success() 和 error()
