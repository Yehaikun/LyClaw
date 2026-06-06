# LyClaw Session 存储层重新设计计划

## 1. 现状分析 & 行业参考

### 现有问题

当前 `lyclaw-framework` 的 session 是一个**功能不全的 v0.1 版本**：

| 问题 | 体现 |
|---|---|
| **接口粒度粗** | `SessionStore` 混入元操作(rename/list)和底层存储(save/load) |
| **消息存储破坏性写入** | `saveMessages` 每次全量删除再插入，并发不安全 |
| **分页语义错误** | `loadMessages(offset, limit)` 的 offset 基于尾部子集而非全量历史 |
| **无上下文管理** | 不支持消息裁剪、摘要压缩、滑动窗口 |
| **无生命周期** | 没有 active/archived/closed 状态管理 |
| **无会话变量** | 没有跨轮的 session state 存储 |
| **无元数据** | tags、token 用量、用户身份等缺失 |
| **写策略原始** | 每次对话轮次都全量持久化，没有批处理/节流 |

好消息是：**worktree 分支已有完整的三层存储架构设计**（StorageFacade、StoreLayer、StorageBackendRegistry、@StorageBackend 注解体系等），只是没有合入主分支，且 session 层尚未对齐该架构。

### 行业参考

| 框架 | 会话模型 | 消息存储 | 上下文管理 | 写策略 |
|---|---|---|---|---|
| **LangChain** | `BaseChatMessageHistory` 接口，按 sessionId 存取 `BaseMessage` 列表 | 追加模式，每条消息有 type/content/tool_call_id | `ConversationSummaryMemory` / `ConversationBufferWindowMemory` | 每次 append 即写 |
| **Spring AI** | `ChatMemory` 接口，`InMemoryChatMemory` | `add()` 追加，`get()` 返回列表，`clear()` 清空 | N/A | 用户自实现持久化 |
| **LlamaIndex** | `ChatMemoryBuffer` + `ChatStore` | `put()` / `get()` / `delete()` 键值模式 | token 计数 + 滑动窗口淘汰 | `ChatStore` 抽象 |
| **OpenAI Assistants API** | `Thread` → `Message` 两级，thread 是整个会话 | 流式 append + 分页 `list()` | 自动截断策略（旧消息→摘要） | 服务端自动管理 |

**LyClaw 的设计定位**：比 LangChain/Spring AI 更完整（三层存储分层 + 能力声明体系），比 OpenAI Assistants 更灵活（SPI 可插拔后端 + 自定义写策略）。

---

## 2. 总体架构

```
┌─────────────────────────────────────────────────────────────┐
│                    业务层 (AgentInvocationHandler)            │
│              sessionService.getOrCreate(id).append(msg)      │
├─────────────────────────────────────────────────────────────┤
│                     ┌───────────────────────┐               │
│                     │   SessionService       │  ← 统一门面   │
│                     │   (Framework API)      │               │
│                     └──────┬────────────────┘               │
│                            │委托                              │
│              ┌─────────────┼─────────────┐                   │
│              ▼             ▼             ▼                    │
│   ┌──────────────┐ ┌──────────┐ ┌──────────────┐            │
│   │ SessionStore  │ │ MessageStore│ │ VariableStore│  ← SPI  │
│   │ (元数据/生命周期)│ │ (追加消息)  │ │ (会话变量)   │            │
│   └──────────────┘ └──────────┘ └──────────────┘            │
│              │             │             │                    │
│              ▼             ▼             ▼                    │
│   ┌──────────────────────────────────────────────────┐       │
│   │         StorageFacade (三层路由)                   │       │
│   │  SESSION(高频低延迟) → ENTITY(持久化) → MEMORY(语义) │       │
│   └──────────┬──────────────────────┬───────────────┘       │
│              ▼                      ▼                        │
│   ┌──────────────┐         ┌──────────────┐                  │
│   │ InMemory      │         │  SQLite      │    ← 用户可切换   │
│   │ Backend       │         │  Backend     │                  │
│   └──────────────┘         └──────────────┘                  │
└─────────────────────────────────────────────────────────────┘
```

**核心设计模式**：
- **门面模式** — `SessionService` 是业务代码唯一入口
- **策略模式** — 写策略 (`SessionWritePolicy`)、上下文策略 (`ContextPolicy`) 可插拔
- **接口隔离** — `SessionStore` / `MessageStore` / `VariableStore` 三个独立 SPI
- **适配器模式** — 默认实现适配 `StorageFacade` 三层后端
- **观察者模式** — `SessionLifecycleListener` 事件钩子

---

## 3. SPI 接口设计

### 3.1 SessionStore — 会话元数据

```java
public interface SessionStore {

    /** 创建新会话 */
    Session create(CreateSessionRequest request);

    /** 获取会话（含基本元信息，不含消息） */
    Optional<Session> get(String sessionId);

    /** 获取或创建 */
    Session getOrCreate(String sessionId, String agentId, String model);

    /** 更新元数据（名称、标签、模型等） */
    void update(String sessionId, SessionUpdate update);

    /** 删除会话 */
    void delete(String sessionId);

    /** 列出会话，支持过滤 */
    SessionPage list(SessionQuery query);

    /** 标记会话状态 */
    void markStatus(String sessionId, SessionStatus status);

    // ── 默认方法提供组合操作 ──

    default Session getOrCreate(String sessionId) {
        return getOrCreate(sessionId, null, null);
    }
}
```

### 3.2 MessageStore — 消息存储（追加型 SPI）

```java
public interface MessageStore {

    /** 追加单条消息，返回分配的序号 */
    int append(String sessionId, Message message);

    /** 批量追加（用于流式完成后的批量写入） */
    int[] appendBatch(String sessionId, List<Message> messages);

    /** 按时间正序分页查询 */
    MessagePage load(String sessionId, int offset, int limit);

    /** 加载最近 N 条消息（用于构建 LLM context） */
    List<Message> loadLatest(String sessionId, int lastN);

    /** 从指定序号开始加载（续传场景） */
    List<Message> loadSince(String sessionId, int afterIndex);

    /** 更新指定序号的消息（如流式 content 追加） */
    void updateContent(String sessionId, int index, String content);

    /** 删除会话的所有消息 */
    void deleteBySession(String sessionId);

    /** 获取消息总数 */
    int count(String sessionId);

    /** 删除指定序号之前的消息（上下文裁剪） */
    int pruneBefore(String sessionId, int keepLastN);
}
```

### 3.3 VariableStore — 会话变量

```java
public interface VariableStore {

    /** 设置会话变量 */
    void set(String sessionId, String key, Object value);

    /** 获取会话变量 */
    <T> Optional<T> get(String sessionId, String key, Class<T> type);

    /** 获取全部变量 */
    Map<String, Object> getAll(String sessionId);

    /** 修改变量 */
    <T> Optional<T> remove(String sessionId, String key);

    /** 清空会话变量 */
    void clear(String sessionId);
}
```

### 3.4 SessionService — 统一门面（业务代码唯一入口）

```java
public interface SessionService {

    // ── 会话管理 ──
    Session getOrCreate(String sessionId, String agentId, String model);
    Session create(String agentId, String model);
    Optional<Session> get(String sessionId);
    void update(String sessionId, SessionUpdate update);
    void delete(String sessionId);
    SessionPage list(SessionQuery query);

    // ── 消息操作（最终一致性，写策略控制写入时机） ──
    int appendMessage(String sessionId, Message message);
    void appendMessages(String sessionId, List<Message> messages);
    MessagePage loadMessages(String sessionId, int offset, int limit);
    List<Message> loadLatestMessages(String sessionId, int lastN);

    // ── 会话变量 ──
    void setVariable(String sessionId, String key, Object value);
    <T> Optional<T> getVariable(String sessionId, String key, Class<T> type);

    // ── 上下文构建（LLM 调用前调用） ──
    List<Message> buildContext(String sessionId, ContextPolicy policy);

    // ── 生命周期钩子 ──
    void addListener(SessionLifecycleListener listener);
}
```

### 3.5 写策略 SPI

```java
/**
 * 控制消息持久化的频率和时机。
 * 避免每次 append 都落盘，支持按轮次/时间/信号批量写入。
 */
public interface SessionWritePolicy {

    /** 评估是否应该执行持久化 */
    PersistenceDecision evaluate(SessionWriteState state);

    /** 获取待 flush 的消息数 */
    default int pendingCount(SessionWriteState state) { return 0; }
}
```

内置实现：
- `ImmediateWritePolicy` — 每条消息立即写（默认，行为同当前代码）
- `ThresholdWritePolicy` — 累计 N 条或间隔 T 秒后批量写
- `TurnBoundaryWritePolicy` — 每次 LLM 调用轮次结束时写

### 3.6 上下文裁剪策略 SPI

```java
public interface ContextPolicy {

    /** 从完整消息列表中裁剪出适合 LLM 调用的子集 */
    List<Message> prune(String sessionId, List<Message> fullMessages,
                        ContextPolicyContext ctx);
}
```

内置实现：
- `SlidingWindowPolicy` — 保留最近 N 条消息
- `TokenBudgetPolicy` — 保留不超过 T 个 token
- `SummaryCompressPolicy` — 将历史消息压缩为摘要
- `CompositePolicy` — 组合多个策略

---

## 4. 领域模型增强

### Session 模型（新增字段）

```java
public class Session {
    String sessionId;
    String name;
    String agentId;
    String model;
    SessionStatus status;          // ACTIVE, ARCHIVED, CLOSED
    Map<String, String> tags;     // 自定义标签
    String userId;                // 用户身份
    long createdAt;
    long updatedAt;
    long lastActiveAt;
    int messageCount;
    int estimatedTokenCount;      // 近似 token 数
    String metadataJson;          // 扩展元数据（JSON 字符串）
}
```

### SessionStatus 枚举

```java
public enum SessionStatus {
    ACTIVE,      // 正常对话中
    ARCHIVED,    // 已归档（可恢复）
    CLOSED       // 已关闭（不可恢复）
}
```

### 消息模型增强

Message 现有字段基本够用，补充：
- `msgIndex` — 会话内递增序号（用于分页和裁剪）
- `createdAt` — 消息时间戳
- `hidden` — 标记为隐藏（不送入 LLM，但保留在存储中）

### SessionQuery 查询对象

```java
public class SessionQuery {
    String agentId;
    String userId;
    SessionStatus status;
    String keyword;              // 按名称/消息内容模糊搜索
    Long createdAfter;
    Long createdBefore;
    int offset;
    int limit;
    String sortBy;              // updatedAt, createdAt, name
    boolean ascending;
}
```

---

## 5. 实现计划（分 3 个 Phase）

### Phase 1 — 核心 SPI 落地（当前 main 分支可立即做的）

**目标**：在保持向后兼容的前提下，将当前的 `SessionStore` 拆分为清晰的 SPI 分层。

| 步骤 | 文件 | 说明 |
|---|---|---|
| 1.1 | 新增 `MessageStore` 接口 | 从 SessionStore 拆出消息追加/查询 SPI |
| 1.2 | 新增 `SessionService` 门面 | 组合 SessionStore + MessageStore + VariableStore |
| 1.3 | 新增 `SessionWritePolicy` + `ImmediateWritePolicy` | 写策略 SPI + 默认实现 |
| 1.4 | 新增 `ContextPolicy` + `SlidingWindowPolicy` | 上下文裁剪 SPI + 滑动窗口 |
| 1.5 | 增强 `Session` 模型 | 添加 status/tags/userId/tokenCount |
| 1.6 | 新增 `SessionQuery` | 查询条件对象 |
| 1.7 | 重构 `InMemorySessionStore` | 拆分消息存储逻辑，追加模式 |
| 1.8 | 重构 `SqliteSessionStore` | 增量 write 代替全量 delete+insert |
| 1.9 | 对接 `AgentInvocationHandler` | 使用 SessionService 代替直接 SessionStore |
| 1.10 | 对接 `ChatController` | 使用 SessionService 代替直接 SessionStore |

### Phase 2 — 上下文管理（深入框架核心）

**目标**：LLM 上下文窗口管理 — 裁剪、摘要、滑动窗口。

| 步骤 | 说明 |
|---|---|
| 2.1 | `TokenBudgetPolicy` — 按 token 预算从最新消息回溯，超出则裁剪旧消息 |
| 2.2 | `SummaryCompressPolicy` — 将旧轮次压缩为摘要消息插入上下文 |
| 2.3 | `CompositePolicy` — 策略组合器，先摘要再滑动窗口 |
| 2.4 | 在 `AgentInvocationHandler.prepareRequestForRun()` 中集成上下文构建 |
| 2.5 | 暴露 `lyclaw.agent.context.policy` 配置项 |

### Phase 3 — 三层存储对齐（合入 worktree 存储架构）

**目标**：将 session 存储对齐到已有的 StorageFacade 三层架构。

| 步骤 | 说明 |
|---|---|
| 3.1 | 合入 `StorageFacade` / `StoreLayer` / `StorageBackendRegistry` 核心接口 |
| 3.2 | 合入 `@StorageBackend` / `@SessionStore` 注解体系 |
| 3.3 | 合入 `InMemoryBackend` / `FileBackend` |
| 3.4 | 合入 `StorageAutoConfiguration` / `StorageBackendPostProcessor` |
| 3.5 | `DefaultSessionStore` 适配 `StorageFacade`（默认存 SESSION 层） |
| 3.6 | session 归档时自动迁移 SESSION→ENTITY 层（冷热分离） |
| 3.7 | SQLite 后端对齐 `StorageBackend` SPI |
| 3.8 | 配置文档：`lyclaw.storage.stores.session=inmemory|file|sqlite` |

### 设计决策说明

1. **MessageStore 为什么独立于 SessionStore？**
   - 消息的读写模式与元数据完全不同：消息是追加密集型，元数据是查询密集型
   - 消息可能需要独立的后端（如时序数据库/分离表）
   - 便于实现流式消息中间状态管理

2. **写策略为什么是 SPI 而不是硬编码？**
   - Demo/测试场景：每次 append 都写（即时可见）
   - 生产场景：批量/节流写入（减少 I/O）
   - 流式场景：消息完成时写（避免写入半成品）

3. **上下文裁剪为什么是策略模式？**
   - 不同模型的上下文窗口不同（4K/8K/128K）
   - 不同场景需要不同策略（对话保留最新、分析保留首尾）
   - 用户可能需要自定义裁剪逻辑

4. **为什么分层三层存储？**
   - SESSION 层：高频读写，低延迟（InMemory/Redis），TTL 自动清理
   - ENTITY 层：长期持久化（SQLite/PostgreSQL），事务保证
   - MEMORY 层：语义检索（PGVector/Milvus），跨 session 的知识沉淀
   - 会话数据随生命周期自动在层间迁移：活跃期→SESSION，关闭后→ENTITY

---

## 6. 迁移路径（向后兼容）

为确保当前用户不受影响：

1. **SessionStore 接口保留但标记 `@Deprecated`**，默认实现委托到 SessionService
2. **所有新增接口都有默认的 InMemory 实现**，开箱即用
3. **`ReActAutoConfiguration` 默认提供 `SessionService` Bean**，行为与当前完全一致
4. **配置项新增但不破坏现有配置**，`lyclaw.session.*` 前缀

---

## 7. 与现有代码的集成点

```java
// AgentInvocationHandler 现在的代码：
sessionStore.getOrCreate(sessionId, ...);
sessionStore.saveMessages(sessionId, request.getMessages());

// 改为：
sessionService.getOrCreate(sessionId, ...);
sessionService.appendMessages(sessionId, request.getMessages());
// 底层写策略自动控制持久化时机
```
