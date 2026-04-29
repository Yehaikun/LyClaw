# LyClaw AI 调度引擎层 — 设计指引报告

> 简述：如何从零设计这个 AI 引擎层

---

## 一、设计起点

**已有资产**：42 个 Java 文件（4 个模块），包括 13 个公共 Model 类、ModelAdapter 接口、BaseStorage、ModelException、SessionStorage 等基础设施。

**核心约束**：不修改任何已有代码，所有扩展通过新建类 + 实现接口 + @Component 完成。

**目标**：设计一个面向 3-5 年演进的 AI 调度引擎层，具备长期可扩展性、可维护性，未来能对接 OpenClaw 生态。

---

## 二、设计步骤

### 第1步：定架构，分层次

**问题**：AI 引擎层放在系统哪一层？

**决定**：**夹在业务模块层和模型抽象层之间**。不依赖 ModelAdapter 具体实现，通过接口隔离。

```
业务模块（会话/配置/定时任务）
    ↓
AI 引擎层（对话编排） ← 新建
    ↓ 防腐层
模型抽象层（ModelAdapter/ModelProvider）
    ↓
大模型 API
```

### 第2步：找根基 — 顶层接口

**问题**：引擎层最核心的入口是什么？

**决定**：3 个核心抽象，构成执行三角。

| 抽象 | 职责 | 说明 |
|------|------|------|
| `Engine` | 对话入口 | Strategy 模式，多引擎可共存 |
| `Pipeline` | 阶段编排 | 5 个固定 Stage，Chain 传递控制权 |
| `ToolCallLoop` | 模型↔工具循环 | 模板方法固化流程 |

### 第3步：扩外围能力

引擎层不止有 Engine。围绕"AI 对话"这个核心场景，需要支撑能力：

1. **技能系统（Skill）**：把工具、异步任务、流式任务、Agent 任务、检索任务统一为 Skill 接口。Tool 是 Skill 的特例（TOOL 类型）。SkillRegistry 是权威注册表，ToolRegistry 是内部容器。
2. **记忆管理（MemoryManager）**：把长期记忆封装起来。第一版只有"记住→召回"，第二版可扩展为多种提取策略。
3. **事件总线（EventBus）**：解耦。Token 消耗发事件、工具调用发事件、Agent 状态变更发事件。MetricsStage 只监听事件，不直接依赖 ToolCallLoop。
4. **Agent 协调（AgentCoordinator）**：多 Agent 通信。AgentChannel 定义拓扑，StarAgentChannel 是第一版默认实现。
5. **错误处理（ErrorPolicy）**：401/403/429/5xx 各走各路。429 等 5 秒重试，401 不重试。

### 第4步：加安全防护（占位）

企业级项目必须考虑安全。但这些能力先占位接口，第一版实现全部放行：

- **ContentFilter**：内容安全过滤（输入/输出），第一版全部 ALLOW
- **SecurityManager**：工具执行审批、沙箱策略，第一版全部 ALLOW
- **CacheService**：响应缓存、工具结果缓存，第一版 ConcurrencyHashMap

### 第5步：补企业级基础设施

可观测性是线上排障的命根子：

- **TraceContext**：每个请求的 traceId/spanId，贯穿 Engine → Pipeline → Tool → Tool
- **MetricsStage**：记录 Token 用量、执行耗时、发布事件
- **LoggingInterceptor**：请求/响应打印含 traceId

### 第6步：控增量 — 42 + 40 = 82 文件

所有新文件分两批：

- **lyclaw-core（42 个）**：全是接口和抽象类。定义"能做什么"，不定义"怎么做"。14 个接口包。
- **lyclaw-engine（40 个）**：全是具体实现。一个接口可以有多个实现自由切换。

第二版预留接口（4个占位文件）：SessionTransaction、TransactionContext、SessionUpdate、SessionUpdateStrategy。

第一版不做，只占位，不改 core 接口签名。

### 第7步：用设计模式兜底

17 种设计模式，每种对应一个可扩展点：

| 模式 | 用在哪 | 核心价值 |
|------|--------|----------|
| 策略 | Engine、ContextBuilder、ErrorPolicy、ToolCallPolicy | 可替换算法 |
| 模板方法 | ToolCallLoop | 固化流程，子类填充细节 |
| 管道 | Pipeline | 阶段可编排、可插拔 |
| 责任链 | Interceptor | 拦截器可任意增删，不影响核心流程 |
| 观察者 | EventBus | 松耦合，新增监听器不改发送方 |
| 命令 | Tool | 工具可独立实现、独立替换 |
| 注册表 | ToolRegistry、SkillRegistry、EngineSelector | 自动发现 + 按需匹配 |
| 工厂 | PipelineBuilder、ModelProvider | 复杂对象构建 |
| 建造者 | PipelineBuilder | 链式调用构建 Pipeline |
| 状态 | AgentState | Agent 生命周期可追踪 |
| 中介者 | AgentCoordinator | Agent 间通信不直接依赖 |
| 适配器 | ToolToSkillAdapter、McpToolAdapter | 已有能力无缝接入新接口 |
| 代理 | CacheService、TraceContext | 透明叠加能力 |
| 桥接 | ContextBuilder + MemoryStrategy | 多维度组合 |
| 空对象 | NullMemoryManager、NullEventBus | 避免 NPE |
| 备忘录 | SessionTransaction（第二版） | 会话状态回滚 |
| 装饰器 | CacheDecorator、MetricsDecorator（第二版） | 运行时动态增强 |

---

## 三、设计铁律

### 扩展不修改

**任何新功能 = 新建类 + @Component，不动已有文件。** 六次审阅确认零修改。

例：加个 Skill → 实现 Skill 接口 → @Component → SkillRegistry 自动发现。

### 接口优先于实现

**core 层只定义接口，engine 层只实现接口。** 上层依赖 core 层，永远不依赖 engine 层。

### 占位先于扩展

未来需要的能力，先把接口定义好（core 层），实现留到第二版。占位接口不改签名，换成实现即可。

第一版占位的：SessionTransaction、DefaultSecurityManager（全部放行）、DefaultTaskPlanner（串行执行）、DefaultContentFilter（全部 ALLOW）

### 预见退化

不配置某个组件时系统不能崩。**每个接口都有 Null 实现兜底**。

NullMemoryManager、NullEventBus、NullSecurityManager、NullContentFilter。

---

## 四、最终产出

| 指标 | 值 |
|------|-----|
| 文档行数 | 3670 行 |
| 设计章节 | 23 章 + 1 附录 |
| 新增文件 | 82 个（core 42 + engine 40） |
| 已有文件 | 42 个（不动） |
| 全项目 | 124 个 .java 文件 |
| 设计模式 | 17 种 |
| 审阅轮次 | 7 轮（含本轮） |
| 架构约束 | 8 条（0 条被违反） |
| 已有代码兼容性 | 23 个类逐个对照确认 |
| 遗留命名错误 | 0 处 |

---

*写于 2026-04-28，第七轮审阅完成后*
