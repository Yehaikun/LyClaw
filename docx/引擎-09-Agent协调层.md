# 引擎-09-Agent 协调层

**Metadata**
- Date: 2026-04-29
- 所属模块: lyclaw-engine
- 包路径: `lyjew.com.lyclaw.agent`
- 依赖: Engine 接口（通过 EngineSelector 执行子任务）、EventBus（发布 AgentStateChangedEvent）
- 并行前提: 依赖 Engine 接口 + EventBus 接口，可在 Engine 层完成前先行定义接口，实现可滞后

---

## 核心职责

管理主 Agent 与子 Agent 的生命周期和通信。第一版支持单一子 Agent，第二版支持多 Agent 并行和各种通信拓扑。

---

## 需要实现的类清单

### 1. AgentState — Agent 状态枚举

**文件**: `agent/AgentState.java`
**包**: `lyjew.com.lyclaw.agent`

| 元素 | 说明 |
|------|------|
| 类型 | 枚举 |

**枚举值**:
| 值 | 说明 |
|----|------|
| IDLE | 初始状态，未启动 |
| RUNNING | 执行中 |
| WAITING_TOOL | 等待工具结果 |
| COMPLETED | 正常完成 |
| ERROR | 执行出错 |
| TERMINATED | 被手动终止 |

---

### 2. AgentTask — Agent 任务

**文件**: `agent/AgentTask.java`
**包**: `lyjew.com.lyclaw.agent`

| 属性 | 类型 | 说明 |
|------|------|------|
| taskId | String | 任务唯一 ID |
| sessionId | String | 所属会话 ID |
| description | String | 任务描述（发给子 Agent 的指令） |
| parentAgentId | String | 父 Agent ID（主 Agent 为空） |
| maxTimeoutMs | long | 超时时间（默认 300000 = 5 分钟） |
| createdAt | long | 创建时间戳 |

| 方法 | 返回值 | 说明 |
|------|--------|------|
| AgentTask(String sessionId, String description, long maxTimeoutMs) | - | 构造器 |
| 所有属性 Getter | - | - |

---

### 3. AgentChannel — Agent 通信拓扑接口

**文件**: `agent/AgentChannel.java`
**包**: `lyjew.com.lyclaw.agent`

| 元素 | 说明 |
|------|------|
| 类型 | 接口 |
| 设计模式 | 中介者模式 |

**方法**:
| 方法签名 | 返回值 | 说明 |
|----------|--------|------|
| String getTopologyName() | String | 拓扑名称，如 "star"、"tree"、"mesh" |
| void send(AgentMessage message) | void | 发送消息到指定 Agent |
| Flux\<AgentMessage\> receive(String agentId) | Flux\<AgentMessage\> | 接收发给指定 Agent 的消息流 |

---

### 4. AgentMessage — Agent 间消息

**文件**: `agent/AgentMessage.java`
**包**: `lyjew.com.lyclaw.agent`

| 属性 | 类型 | 说明 |
|------|------|------|
| messageId | String | 消息 ID |
| fromAgentId | String | 发送者 Agent ID |
| toAgentId | String | 接收者 Agent ID |
| type | String | 消息类型："request"、"response"、"status"、"error" |
| content | String | 消息内容 |
| timestamp | long | 时间戳 |

| 方法 | 返回值 | 说明 |
|------|--------|------|
| 所有属性 Getter | - | - |
| AgentMessage(fromAgentId, toAgentId, type, content) | - | 构造器 |

---

### 5. StarAgentChannel — 星型拓扑实现

**文件**: `agent/impl/StarAgentChannel.java`
**包**: `lyjew.com.lyclaw.agent.impl`
**实现**: AgentChannel

| 元素 | 说明 |
|------|------|
| 类型 | 类，@Component |
| 拓扑 | 主 Agent 可与任何子 Agent 通信，子 Agent 之间不可直接通信 |

**属性**:
| 名称 | 类型 | 说明 |
|------|------|------|
| messageQueues | ConcurrentHashMap\<String, BlockingQueue\<AgentMessage\>\> | AgentId → 消息队列 |

**方法**:
| 方法签名 | 返回值 | 说明 |
|----------|--------|------|
| getTopologyName() | String | 返回 "star" |
| send(AgentMessage message) | void | 验证：如果 fromAgentId 和 toAgentId 都是子 Agent，抛 ChannelException("子Agent间不允许直接通信")。否则将消息放入 toAgentId 的队列 |
| receive(String agentId) | Flux\<AgentMessage\> | 从消息队列取消息，返回 Flux（用 Sinks.Many 或 Flux.create 实现流式接收） |

---

### 6. ChannelException — 信道异常

**文件**: `agent/impl/ChannelException.java`
**包**: `lyjew.com.lyclaw.agent.impl`

| 属性 | 类型 | 说明 |
|------|------|------|
| fromAgentId | String | 发送者 |
| toAgentId | String | 目标 |

| 方法 | 返回值 | 说明 |
|------|--------|------|
| ChannelException(String fromAgentId, String toAgentId, String message) | - | 构造器 |

---

### 7. AgentCoordinator — Agent 协调器

**文件**: `agent/AgentCoordinator.java`
**包**: `lyjew.com.lyclaw.agent`

| 元素 | 说明 |
|------|------|
| 类型 | 类，@Component |
| 设计模式 | 中介者模式 |

**属性**:
| 名称 | 类型 | 说明 |
|------|------|------|
| agents | ConcurrentHashMap\<String, AgentInfo\> | agentId → Agent 信息（含状态） |
| engineSelector | EngineSelector | 用于执行子任务 |
| agentChannel | AgentChannel | 通信拓扑 |
| eventBus | EventBus | 发布状态变更事件 |
| maxChildrenPerSession | int | 每会话最大子 Agent 数（默认 1） |
| maxDepth | int | 最大嵌套深度（默认 1，子 Agent 不可再 spawn） |

**方法**:
| 方法签名 | 返回值 | 说明 |
|----------|--------|------|
| String spawn(Session parentSession, AgentTask task) | String | 创建一个子 Agent：<br>1. 校验：父会话子 Agent 数 < maxChildrenPerSession<br>2. 校验：深度限制<br>3. 创建 agentId (UUID)<br>4. 初始化状态为 RUNNING<br>5. 发布 AgentStateChangedEvent(IDLE→RUNNING)<br>6. 异步执行子任务（新线程）：<br>   - 构建 ChatRequest<br>   - engineSelector.execute(request)<br>   - 状态设为 COMPLETED/ERROR<br>   - 发布状态变更事件<br>7. 返回 agentId |
| void terminate(String agentId) | void | 终止指定 Agent：设置状态为 TERMINATED，打断当前执行 |
| AgentState getStatus(String agentId) | AgentState | 查询 Agent 状态 |
| void cascadeTerminate(String sessionId) | void | 级联终止：终止该会话下所有 Agent |
| List\<AgentInfo\> listAgents(String sessionId) | List\<AgentInfo\> | 列出某会话下的所有 Agent |

---

### 8. AgentInfo — Agent 信息（辅助类）

**文件**: `agent/AgentInfo.java`
**包**: `lyjew.com.lyclaw.agent`

| 属性 | 类型 | 说明 |
|------|------|------|
| agentId | String | Agent ID |
| sessionId | String | 所属会话 ID |
| state | AgentState | 当前状态 |
| task | AgentTask | 绑定的任务 |
| startTime | long | 启动时间 |
| parentAgentId | String | 父 Agent ID（可为空） |
| depth | int | 当前嵌套深度 |

| 方法 | 返回值 | 说明 |
|------|--------|------|
| 所有属性 Getter/Setter | - | - |
| boolean isActive() | boolean | state == RUNNING \|\| state == WAITING_TOOL |
| boolean isTerminal() | boolean | state == COMPLETED \|\| state == ERROR \|\| state == TERMINATED |

---

## 第一版约束

- 同一会话最多 1 个子 Agent 并发
- 子 Agent 超时时间 5 分钟
- 子 Agent 不可再 spawn 孙 Agent（深度限制 1）
- 主会话终止时级联终止所有子 Agent

## 实现顺序

1. AgentState 枚举
2. AgentMessage
3. AgentTask
4. AgentInfo
5. ChannelException
6. AgentChannel 接口
7. StarAgentChannel
8. AgentCoordinator（依赖 EngineSelector + EventBus + AgentChannel）

## 校验清单

- [ ] AgentState 含 IDLE、RUNNING、WAITING_TOOL、COMPLETED、ERROR、TERMINATED
- [ ] AgentChannel 接口含 getTopologyName、send、receive
- [ ] StarAgentChannel 禁止子 Agent 间通信
- [ ] AgentCoordinator.spawn() 检查并发数和深度限制
- [ ] AgentCoordinator 异步执行子任务
- [ ] AgentCoordinator.cascadeTerminate() 级联终止
- [ ] AgentInfo 有 isActive() 和 isTerminal() 辅助方法
