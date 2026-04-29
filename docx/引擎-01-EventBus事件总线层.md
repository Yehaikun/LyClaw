# 引擎-01-EventBus 事件总线层

**Metadata**
- Date: 2026-04-29
- 所属模块: lyclaw-engine
- 包路径: `lyjew.com.lyclaw.event`
- 依赖: lyclaw-common DTO（无业务依赖）
- 并行前提: 无依赖，可最先实现

---

## 核心职责

事件总线是模块间解耦通信的基础设施。发布者不关心谁在监听，监听者不关心事件来源。

---

## 需要实现的类清单

### 1. Event — 事件基类

**文件**: `event/Event.java`
**包**: `lyjew.com.lyclaw.event`

| 元素 | 说明 |
|------|------|
| 类型 | 抽象类 |
| 设计模式 | 观察者模式的事件基类 |

**属性**:
| 名称 | 类型 | 说明 |
|------|------|------|
| eventId | String | 事件唯一ID，UUID |
| timestamp | long | 事件发生时间戳（System.currentTimeMillis()） |
| source | String | 事件来源标识（如 "tool_call_loop"、"metric_stage"） |

**方法**:
| 方法签名 | 返回值 | 说明 |
|----------|--------|------|
| Event() | - | 无参构造，自动生成 eventId 和 timestamp |
| Event(String source) | - | 带 source 构造 |
| getEventId() | String | - |
| getTimestamp() | long | - |
| getSource() | String | - |

---

### 2. TokenConsumedEvent — Token 消耗事件

**文件**: `event/TokenConsumedEvent.java`
**包**: `lyjew.com.lyclaw.event`
**继承**: Event

| 属性 | 类型 | 说明 |
|------|------|------|
| sessionId | String | 会话ID |
| model | String | 模型名称 |
| promptTokens | int | 提示词token数 |
| completionTokens | int | 生成token数 |
| totalTokens | int | 总token数 |
| durationMs | long | 本次调用耗时 |

**构造器**: `TokenConsumedEvent(String sessionId, String model, int promptTokens, int completionTokens, int totalTokens, long durationMs, String source)`

**方法**: 所有属性都有 Getter

---

### 3. ToolCalledEvent — 工具调用事件

**文件**: `event/ToolCalledEvent.java`
**包**: `lyjew.com.lyclaw.event`
**继承**: Event

| 属性 | 类型 | 说明 |
|------|------|------|
| sessionId | String | 会话ID |
| toolName | String | 工具名称 |
| arguments | Map\<String, Object\> | 调用参数 |
| result | String | 工具执行结果摘要 |
| success | boolean | 是否成功 |
| durationMs | long | 工具执行耗时 |

**构造器**: `ToolCalledEvent(String sessionId, String toolName, Map<String, Object> arguments, String result, boolean success, long durationMs, String source)`

**方法**: 所有属性都有 Getter

---

### 4. AgentStateChangedEvent — Agent 状态变更事件

**文件**: `event/AgentStateChangedEvent.java`
**包**: `lyjew.com.lyclaw.event`
**继承**: Event

| 属性 | 类型 | 说明 |
|------|------|------|
| agentId | String | Agent 唯一ID |
| sessionId | String | 所属会话ID |
| oldState | String | 旧状态（枚举名） |
| newState | String | 新状态（枚举名） |

**构造器**: `AgentStateChangedEvent(String agentId, String sessionId, String oldState, String newState, String source)`

**方法**: 所有属性都有 Getter

---

### 5. ErrorEvent — 错误事件

**文件**: `event/ErrorEvent.java`
**包**: `lyjew.com.lyclaw.event`
**继承**: Event

| 属性 | 类型 | 说明 |
|------|------|------|
| errorType | String | 错误类型（"model_error"、"tool_error"、"timeout"、"internal"） |
| message | String | 错误消息 |
| sessionId | String | 关联会话ID（可空） |
| stackTrace | String | 堆栈信息 |

**构造器**: `ErrorEvent(String errorType, String message, String sessionId, String stackTrace, String source)`

**方法**: 所有属性都有 Getter

---

### 6. EventBus — 事件总线接口

**文件**: `event/EventBus.java`
**包**: `lyjew.com.lyclaw.event`

| 元素 | 说明 |
|------|------|
| 类型 | 接口 |
| 设计模式 | 观察者模式的事件调度器 |

**方法**:
| 方法签名 | 返回值 | 说明 |
|----------|--------|------|
| \<T extends Event\> void publish(T event) | void | 发布事件，所有订阅该类型的监听器异步接收 |
| \<T extends Event\> Subscription subscribe(Class\<T\> eventType, Consumer\<T\> handler) | Subscription | 订阅事件，返回可取消订阅句柄 |
| void unsubscribe(Subscription subscription) | void | 取消订阅 |

---

### 7. Subscription — 订阅句柄

**文件**: `event/Subscription.java`
**包**: `lyjew.com.lyclaw.event`

| 元素 | 说明 |
|------|------|
| 类型 | 类（或接口，推荐类） |

| 属性 | 类型 | 说明 |
|------|------|------|
| id | String | 订阅唯一ID |
| eventType | Class\<? extends Event\> | 订阅的事件类型 |
| active | boolean | 是否仍有效 |

| 方法 | 返回值 | 说明 |
|------|--------|------|
| Subscription(Class\<? extends Event\> eventType) | - | 构造器 |
| getId() | String | 返回 id |
| getEventType() | Class\<? extends Event\> | 返回 eventType |
| isActive() | boolean | 返回是否有效 |
| cancel() | void | 标记为失效 |

---

### 8. InMemoryEventBus — 内存事件总线实现

**文件**: `event/impl/InMemoryEventBus.java`
**包**: `lyjew.com.lyclaw.event.impl`
**实现**: EventBus

| 元素 | 说明 |
|------|------|
| 类型 | 类，@Component |
| 线程模型 | 异步执行（独立线程池），不阻塞发布者 |

| 属性 | 类型 | 说明 |
|------|------|------|
| subscribers | ConcurrentHashMap\<Class\<?\>, CopyOnWriteArrayList\<SubscriberEntry\>\> | 事件类型 → 监听器列表 |
| executor | ExecutorService | 异步执行线程池（固定大小，如 4 线程） |

| 方法 | 返回值 | 说明 |
|------|--------|------|
| publish(T event) | void | 从 map 获取该事件类型的监听器列表，提交到线程池异步执行每个 handler |
| subscribe(eventType, handler) | Subscription | 创建 Subscription，封装 handler 为 SubscriberEntry 加入列表 |
| unsubscribe(subscription) | void | 标记 subscription 失效，从列表中移除对应的 SubscriberEntry |
| destroy() | void | @PreDestroy，关闭线程池 |

---

### 9. SubscriberEntry（包级私有辅助类）

**文件**: `event/impl/SubscriberEntry.java`
**包**: `lyjew.com.lyclaw.event.impl`

| 属性 | 类型 | 说明 |
|------|------|------|
| subscription | Subscription | 订阅句柄 |
| handler | Consumer\<Event\> | 事件处理回调 |

| 方法 | 返回值 | 说明 |
|------|--------|------|
| SubscriberEntry(Subscription, Consumer\<Event\>) | - | 构造器 |
| isActive() | boolean | 委托给 subscription.isActive() |
| handle(Event event) | void | 调用 handler.accept(event) |

---

## 实现顺序

1. Event（抽象基类）
2. TokenConsumedEvent、ToolCalledEvent、AgentStateChangedEvent、ErrorEvent
3. Subscription
4. EventBus 接口
5. SubscriberEntry
6. InMemoryEventBus

## 校验清单

- [ ] Event 基类含 eventId、timestamp、source
- [ ] 4 个具体事件类构造器完整
- [ ] EventBus 接口定义了 publish/subscribe/unsubscribe
- [ ] InMemoryEventBus 异步执行，不阻塞 publish 调用
- [ ] InMemoryEventBus 的 @PreDestroy 关闭线程池
