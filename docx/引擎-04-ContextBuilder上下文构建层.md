# 引擎-04-ContextBuilder 上下文构建层

**Metadata**
- Date: 2026-04-29
- 所属模块: lyclaw-engine
- 包路径: `lyjew.com.lyclaw.context`
- 依赖: lyclaw-common（Message、Session、ToolDefinition、ChatRequest）、MemoryManager 接口（从记忆构建上下文）、ToolRegistry（获取工具定义列表）
- 并行前提: 依赖 MemoryManager 接口 + ToolRegistry 接口，可和 Tool 层、Memory 层并行

---

## 核心职责

将原始数据（会话历史、记忆、system prompt、工具列表）构建为发送给模型的最终消息列表。

---

## 需要实现的类清单

### 1. ChatContext — 对话上下文数据对象

**文件**: `context/ChatContext.java`
**包**: `lyjew.com.lyclaw.context`

| 元素 | 说明 |
|------|------|
| 类型 | 类（POJO，可变对象，贯穿整个 Pipeline） |

**属性**:
| 名称 | 类型 | 说明 |
|------|------|------|
| request | ChatRequest | 原始请求（只读） |
| session | Session | 当前会话（含历史消息） |
| messages | List\<Message\> | 最终的模型输入消息列表（ContextBuilder.build() 填充） |
| toolCallsHistory | List\<ToolCallWithResult\> | 工具调用历史记录（[{toolName, args, result}]） |
| totalPromptTokens | int | 累计 prompt token 数 |
| totalCompletionTokens | int | 累计 completion token 数 |
| totalTokens | int | 累计总 token 数 |
| metadata | Map\<String, Object\> | 可扩展的元数据（各 Stage 之间传递自定义数据） |

**方法**:
| 方法签名 | 返回值 | 说明 |
|----------|--------|------|
| ChatContext(ChatRequest request, Session session) | - | 构造器 |
| addMessage(Message message) | void | 追加消息到 messages 列表 |
| addMessages(List\<Message\> messages) | void | 批量追加 |
| addToolResult(String toolCallId, ToolResult result) | void | 添加工具调用结果到 toolCallsHistory，并转为 Message 追加到 messages |
| putMetadata(String key, Object value) | void | 设置元数据 |
| getMetadata(String key) | Object | 获取元数据 |

---

### 2. ToolCallWithResult — 工具调用记录（辅助类）

**文件**: `context/ToolCallWithResult.java`
**包**: `lyjew.com.lyclaw.context`

| 属性 | 类型 | 说明 |
|------|------|------|
| toolCallId | String | 工具调用 ID |
| toolName | String | 工具名称 |
| arguments | Map\<String, Object\> | 调用参数 |
| result | ToolResult | 执行结果 |
| round | int | 在第几轮调用的 |

| 方法 | 返回值 | 说明 |
|------|--------|------|
| ToolCallWithResult(toolCallId, toolName, arguments, result, round) | - | 构造器 |
| 所有属性 Getter | - | - |

---

### 3. ContextBuilder — 上下文构建策略接口

**文件**: `context/ContextBuilder.java`
**包**: `lyjew.com.lyclaw.context`

| 元素 | 说明 |
|------|------|
| 类型 | 接口 |
| 设计模式 | 策略模式 |

**方法**:
| 方法签名 | 返回值 | 说明 |
|----------|--------|------|
| String getName() | String | 策略名称，如 "full_window"、"sliding_window" |
| boolean supports(ChatContext context) | boolean | 判断当前策略是否适用于这个上下文 |
| ChatContext build(ChatContext context) | ChatContext | 执行构建逻辑，返回填充了 messages 的 ChatContext。build() 内部负责：加载记忆 → 选择消息窗口 → 注入 system prompt → 注入工具定义 → 填入 messages |

---

### 4. FullWindowContextBuilder — 全量窗口策略

**文件**: `context/impl/FullWindowContextBuilder.java`
**包**: `lyjew.com.lyclaw.context.impl`
**实现**: ContextBuilder

| 元素 | 说明 |
|------|------|
| 类型 | 类，@Component |

**属性**:
| 名称 | 类型 | 说明 |
|------|------|------|
| memoryManager | MemoryManager | 用于加载长期记忆 |
| maxTokens | int | 最大 token 数（配置化，默认 4096。超出时截断最早的消息） |

**方法**:
| 方法签名 | 返回值 | 说明 |
|----------|--------|------|
| getName() | String | 返回 "full_window" |
| supports(ChatContext context) | boolean | 始终返回 true（作为兜底策略） |
| build(ChatContext context) | ChatContext | 1. 从 memoryManager.recall() 读取记忆 → 用 buildContext() 格式化 → 加到 messages 列表最前面<br>2. 将 session 中所有历史消息按时间顺序追加到 messages<br>3. 在 messages 最前面插入 system prompt<br>4. 将 toolDefinitions（从 ToolRegistry 获取）加入最后一条消息<br>5. 返回 context |

---

### 5. SlidingWindowContextBuilder — 滑动窗口策略

**文件**: `context/impl/SlidingWindowContextBuilder.java`
**包**: `lyjew.com.lyclaw.context.impl`
**实现**: ContextBuilder

| 元素 | 说明 |
|------|------|
| 类型 | 类，@Component（@ConditionalOnProperty 或第二版加入） |

**属性**:
| 名称 | 类型 | 说明 |
|------|------|------|
| memoryManager | MemoryManager | - |
| maxMessages | int | 最大消息数（配置化，默认 50） |
| keepMessages | int | 保留最近消息数（配置化，默认 20） |

**方法**:
| 方法签名 | 返回值 | 说明 |
|----------|--------|------|
| getName() | String | 返回 "sliding_window" |
| supports(ChatContext context) | boolean | 当 session 的消息数 > maxMessages 时返回 true |
| build(ChatContext context) | ChatContext | 1. 加载记忆并格式化<br>2. 保留 session 中最近 keepMessages 条消息<br>3. system prompt 和记忆始终保留<br>4. 注入工具定义<br>5. 返回 context |

---

### 6. SummaryContextBuilder — 摘要压缩策略（第二版）

**文件**: `context/impl/SummaryContextBuilder.java`
**包**: `lyjew.com.lyclaw.context.impl`
**实现**: ContextBuilder

**性质**: 第二版实现，第一版仅建文件占位 + 空实现 / @ConditionalOnProperty(missing=true)

---

## ContextBuildStage（属于 Pipeline 层，此文档仅描述类签名供参考）

实际实现写在 Pipeline 层的 `stages/ContextBuildStage.java` 中。

**ContextBuildStage 逻辑**:
1. 从 SessionStorage 加载会话历史
2. 从 MemoryManager 加载长期记忆
3. 遍历所有 ContextBuilder，调用 supports()，选第一个匹配的
4. 调用选中的 ContextBuilder.build() 填充 context.messages
5. 注入可用工具列表（从 ToolRegistry.getAllDefinitions()）

---

## 实现顺序

1. ChatContext（核心数据对象）
2. ToolCallWithResult（辅助类）
3. ContextBuilder 接口
4. FullWindowContextBuilder

## 校验清单

- [ ] ChatContext 含 request、session、messages、toolCallsHistory、metadata、token 统计
- [ ] ContextBuilder 接口定义 getName、supports、build
- [ ] FullWindowContextBuilder 始终返回 true（兜底）
- [ ] FullWindowContextBuilder.build() 注入记忆、system prompt、工具定义
- [ ] SlidingWindowContextBuilder 在消息超阈值时选择
