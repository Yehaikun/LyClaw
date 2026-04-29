# 引擎-02-MemoryManager 记忆管理层

**Metadata**
- Date: 2026-04-29
- 所属模块: lyclaw-engine
- 包路径: `lyjew.com.lyclaw.memory`
- 依赖: lyclaw-common 中的 Memory 模型、lyclaw-common 中的 Session 模型
- 注意: Memory 模型在 lyclaw-common 中已存在（`lyjew.com.lyclaw.model.Memory`），请确认其字段是否满足需求

---

## 核心职责

跨会话的长期记忆管理。第一版使用文件存储，未来可切换为 Redis/数据库。

---

## 需要实现的类清单

### 1. MemoryManager — 记忆管理接口

**文件**: `memory/MemoryManager.java`
**包**: `lyjew.com.lyclaw.memory`

| 元素 | 说明 |
|------|------|
| 类型 | 接口 |
| 设计模式 | 策略模式（存储策略由实现决定） |

**方法**:
| 方法签名 | 返回值 | 说明 |
|----------|--------|------|
| void remember(Session session) | void | 从会话中提取记忆并存储（使用当前设置的 strategy） |
| void remember(Session session, MemoryStrategy strategy) | void | 使用指定 strategy 从会话中提取记忆并存储 |
| List\<Memory\> recall() | List\<Memory\> | 读取所有已启用的 Memory（enabled=true） |
| void forget(String memoryId) | void | 删除指定记忆（物理删除或软删除） |
| void setStrategy(MemoryStrategy strategy) | void | 切换记忆提取策略 |
| String buildContext(List\<Memory\> memories) | String | 将记忆列表格式化为可注入上下文的字符串 |

---

### 2. Memory — 记忆实体

**文件**: `lyclaw-common 中已存在`（`lyjew.com.lyclaw.model.Memory`）

如果已有 Memory 字段不足，补充以下字段（**注意：DTO 实体类可以加字段**）：

| 字段 | 类型 | 说明 |
|------|------|------|
| id | String | 唯一标识 |
| title | String | 记忆标题 |
| content | String | 记忆内容（Markdown 格式） |
| enabled | boolean | 是否启用（默认 true） |
| tags | List\<String\> | 标签列表 |
| createdAt | long | 创建时间戳 |
| updatedAt | long | 更新时间戳 |

---

### 3. MemoryStrategy — 记忆提取策略接口

**文件**: `memory/MemoryStrategy.java`
**包**: `lyjew.com.lyclaw.memory`

| 元素 | 说明 |
|------|------|
| 类型 | 接口 |
| 设计模式 | 策略模式 |

**方法**:
| 方法签名 | 返回值 | 说明 |
|----------|--------|------|
| String getName() | String | 策略名称 |
| boolean shouldExtract(Session session) | boolean | 判断是否需要从该会话中提取记忆 |
| List\<Memory\> extract(Session session) | List\<Memory\> | 从会话中提取记忆内容 |

---

### 4. ManualMemoryStrategy — 手动触发策略

**文件**: `memory/impl/ManualMemoryStrategy.java`
**包**: `lyjew.com.lyclaw.memory.impl`
**实现**: MemoryStrategy

| 元素 | 说明 |
|------|------|
| 类型 | 类，@Component |

**逻辑说明**:
- getName() 返回 `"manual"`
- shouldExtract(): 扫描会话中最后一条用户消息，检测是否包含"记住"、"记住这个"、"记住xxx"等关键词，包含则返回 true
- extract(): 从最后一条用户消息中提取"记住"之后的内容作为记忆 content，当前日期作为 title

**方法**:
| 方法签名 | 返回值 | 说明 |
|----------|--------|------|
| String getName() | String | 返回 "manual" |
| boolean shouldExtract(Session session) | boolean | 检测用户消息含"记住"关键词 |
| List\<Memory\> extract(Session session) | List\<Memory\> | 提取"记住"后的内容 |

---

### 5. FileMemoryManager — 文件存储实现

**文件**: `memory/impl/FileMemoryManager.java`
**包**: `lyjew.com.lyclaw.memory.impl`
**实现**: MemoryManager

| 元素 | 说明 |
|------|------|
| 类型 | 类，@Component |
| 存储路径 | 配置化，默认 `{lyclaw.storage.base-path}/memory/` |
| 文件格式 | JSON (推荐) 或 Markdown，使用 lyclaw-storage 的 FormatStrategy |

**属性**:
| 名称 | 类型 | 说明 |
|------|------|------|
| basePath | String | 记忆文件存储根目录 |
| strategy | MemoryStrategy | 当前的记忆提取策略（默认 ManualMemoryStrategy） |
| formatStrategy | FormatStrategy | 文件格式化策略（注入 lyclaw-storage 的 FormatStrategy） |

**方法**:
| 方法签名 | 返回值 | 说明 |
|----------|--------|------|
| void remember(Session session) | void | 调用 `strategy.shouldExtract()` 判断 → 如需要调用 `strategy.extract()` → 将提取的 Memory 序列化为 JSON 写入 `basePath/{memoryId}.json` |
| void remember(Session session, MemoryStrategy strategy) | void | 使用指定策略同上 |
| List\<Memory\> recall() | List\<Memory\> | 扫描 `basePath/*.json`，反序列化，过滤 enabled=true 的返回 |
| void forget(String memoryId) | void | 删除 `basePath/{memoryId}.json`（物理删除），或在 enabled 字段改为 false（软删除） |
| void setStrategy(MemoryStrategy strategy) | void | this.strategy = strategy |
| String buildContext(List\<Memory\> memories) | String | 将 memories 拼接为 markdown 格式："以下是与用户的记忆内容：\n" + 每个 Memory 的 title + ":" + content |

---

## 实现顺序

1. MemoryStrategy 接口
2. ManualMemoryStrategy
3. MemoryManager 接口
4. FileMemoryManager

## 校验清单

- [ ] MemoryManager 接口含 remember、recall、forget、setStrategy、buildContext
- [ ] MemoryStrategy 接口含 getName、shouldExtract、extract
- [ ] ManualMemoryStrategy 能检测"记住"关键词
- [ ] FileMemoryManager 使用 JSON 文件存储，路径可配置
- [ ] recall() 只返回 enabled=true 的记忆
