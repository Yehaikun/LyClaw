# LyClaw AI 调度引擎层 — 总体设计

> **模块**：lyclaw-engine
> **基础包**：`lyjew.com.lyclaw`
> **文件数**：96 个 .java 文件
> **参考设计文档**：`AI Engine层.md`

---

## Maven 模块配置

### 1. 父 POM (`pom.xml`) 添加模块

```xml
<modules>
    ...
    <module>lyclaw-engine</module>
</modules>
```

### 2. lyclaw-engine POM (`lyclaw-engine/pom.xml`)

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <parent>
        <groupId>lyjew.com</groupId>
        <artifactId>lyclaw</artifactId>
        <version>0.0.1-SNAPSHOT</version>
        <relativePath>../pom.xml</relativePath>
    </parent>

    <artifactId>lyclaw-engine</artifactId>
    <name>lyclaw-engine</name>
    <description>LyClaw AI 调度引擎层</description>

    <dependencies>
        <!-- 模块内部依赖 -->
        <dependency>
            <groupId>lyjew.com</groupId>
            <artifactId>lyclaw-common</artifactId>
            <version>0.0.1-SNAPSHOT</version>
        </dependency>
        <dependency>
            <groupId>lyjew.com</groupId>
            <artifactId>lyclaw-core</artifactId>
            <version>0.0.1-SNAPSHOT</version>
        </dependency>
        <dependency>
            <groupId>lyjew.com</groupId>
            <artifactId>lyclaw-adapter</artifactId>
            <version>0.0.1-SNAPSHOT</version>
        </dependency>
        <dependency>
            <groupId>lyjew.com</groupId>
            <artifactId>lyclaw-storage</artifactId>
            <version>0.0.1-SNAPSHOT</version>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-webflux</artifactId>
        </dependency>
        <dependency>
            <groupId>org.projectlombok</groupId>
            <artifactId>lombok</artifactId>
            <optional>true</optional>
        </dependency>
    </dependencies>
</project>
```

---

## 模块依赖关系

### 包结构总览

```
lyjew.com.lyclaw
├── engine/          — Engine 接口 + 元信息
├── pipeline/        — Pipeline 编排
├── context/         — 上下文构建
├── interceptor/     — 拦截器
├── tool/            — 工具抽象
├── skill/           — 技能抽象
├── event/           — 事件总线
├── memory/          — 记忆管理
├── security/        — 安全审批
├── filter/          — 内容过滤
├── transaction/     — 事务管理
├── error/           — 错误处理
├── tracing/         — 链路追踪
├── cache/           — 缓存
├── retrieval/       — 向量检索
├── agent/           — Agent 协调
├── task/            — 任务规划
├── dto/             — DTO 值对象
├── common/          — 通用值对象
└── config/          — Spring 自动装配
```

### 包间依赖方向

```
dto/ common/          ← 无依赖（纯值对象）
  ↓
engine/ pipeline/ context/ interceptor/ tool/ skill/ event/ memory/ security/ filter/ transaction/
  ↓
error/ tracing/ cache/ retrieval/ agent/ task/
  ↓
*.impl/               ← 具体实现
  ↓
config/               ← 自动装配
```

---

---

# 第一部分：DTO/值对象（无业务依赖）

> **设计意图**：纯数据容器，不依赖任何 engine 层的类。只在模块间传递数据，不包含业务逻辑。在其他类编写代码之前，这些类的字段签名就可以先确定下来。

## 实现文件清单

| 序号 | 文件 | 包 | 类型 | 说明 |
|------|------|-----|------|------|
| 1 | ChatResult.java | dto | DTO 类 | Engine.execute() 返回值的统一 DTO |
| 2 | AgentResult.java | dto | DTO 类 | AgentCoordinator.awaitResult() 的返回值 DTO |
| 3 | SkillResult.java | dto | DTO 类 | SkillExecutor.execute() 返回值的 DTO |
| 4 | EngineMetadata.java | engine | 类 | Engine.getMetadata() 的返回值 |
| 5 | ToolResult.java | tool | 类（值对象） | 工具执行结果 |
| 6 | ToolErrorAction.java | tool | 枚举 | RETRY / SKIP / ABORT / FALLBACK |
| 7 | MemoryContent.java | memory | 类（值对象） | MemoryManager.read() 的返回值 |
| 8 | PageResult.java | common | 类（值对象） | 分页查询通用返回值 |

## 第 1 块：ChatResult

### 类介绍
【设计动机】Engine.execute() 返回值的统一 DTO

### 包路径
lyclaw-engine → lyjew.com.lyclaw.dto

### 类型
DTO 类
### 方法签名
- 构造器(content, finishReason, tokenUsage, toolResults, durationMs)
- 所有字段 getter

### 核心字段
- content: String — AI 回复的文本内容
- finishReason: String — 完成原因（"stop" / "error" / "timeout"）
- tokenUsage: String — Token 用量
- toolResults: List<ToolResult> — 工具调用结果列表
- durationMs: long — 请求耗时

- 构造器(content, finishReason, tokenUsage, toolResults, durationMs)
- 所有字段 getter

- content: String — AI 回复的文本内容
- finishReason: String — 完成原因（"stop" / "error" / "timeout"）
- tokenUsage: String — Token 用量
- toolResults: List<ToolResult> — 工具调用结果列表
- durationMs: long — 请求耗时



---

## 第 2 块：AgentResult

### 类介绍
【设计动机】AgentCoordinator.awaitResult() 的返回值 DTO

### 包路径
lyclaw-engine → lyjew.com.lyclaw.dto

### 类型
DTO 类
### 方法签名
- 构造器(agentId, status, summary, detail, elapsedMs)
- 所有字段 getter

### 核心字段
- agentId: String — Agent ID
- status: String — 执行状态
- summary: String — 结果摘要
- detail: String — 结果详情
- elapsedMs: long — 执行耗时

- 构造器(agentId, status, summary, detail, elapsedMs)
- 所有字段 getter

- agentId: String — Agent ID
- status: String — 执行状态
- summary: String — 结果摘要
- detail: String — 结果详情
- elapsedMs: long — 执行耗时



---

## 第 3 块：SkillResult

### 类介绍
【设计动机】SkillExecutor.execute() 返回值的 DTO

### 包路径
lyclaw-engine → lyjew.com.lyclaw.dto

### 类型
DTO 类
### 方法签名
- 构造器(skillId, success, output, error, tokenUsage, elapsedMs)
- 所有字段 getter

### 核心字段
- skillId: String — 技能 ID
- success: boolean — 是否成功
- output: String — 输出内容
- error: String — 错误信息
- tokenUsage: int — Token 消耗
- elapsedMs: long — 执行耗时

- 构造器(skillId, success, output, error, tokenUsage, elapsedMs)
- 所有字段 getter

- skillId: String — 技能 ID
- success: boolean — 是否成功
- output: String — 输出内容
- error: String — 错误信息
- tokenUsage: int — Token 消耗
- elapsedMs: long — 执行耗时



---

## 第 4 块：EngineMetadata

### 类介绍
【设计动机】Engine.getMetadata() 的返回值

### 包路径
lyclaw-engine → lyjew.com.lyclaw.engine

### 类型
类
### 方法签名
- 构造器(name, version, description, supportedModels, capabilities)
- 所有字段 getter

### 核心字段
- name: String — 引擎名称
- version: String — 引擎版本
- description: String — 引擎描述
- supportedModels: List<String> — 支持的模型列表
- capabilities: Set<String> — 能力集（"streaming" / "tools" / "thinking"）

- 构造器(name, version, description, supportedModels, capabilities)
- 所有字段 getter

- name: String — 引擎名称
- version: String — 引擎版本
- description: String — 引擎描述
- supportedModels: List<String> — 支持的模型列表
- capabilities: Set<String> — 能力集（"streaming" / "tools" / "thinking"）



---

## 第 5 块：ToolResult

### 类介绍
【设计动机】工具执行结果

### 包路径
lyclaw-engine → lyjew.com.lyclaw.tool

### 类型
类（值对象）
### 方法签名
- 构造器(success, result, error, elapsedMs, tokenUsage)
- 所有字段 getter
- isSuccess(): boolean — 是否执行成功

### 核心字段
- success: boolean — 是否成功
- result: String — 执行结果（JSON 字符串格式）
- error: String — 错误信息
- elapsedMs: long — 执行耗时毫秒
- tokenUsage: int — 工具执行消耗的 Token

- 构造器(success, result, error, elapsedMs, tokenUsage)
- 所有字段 getter
- isSuccess(): boolean — 是否执行成功

- success: boolean — 是否成功
- result: String — 执行结果（JSON 字符串格式）
- error: String — 错误信息
- elapsedMs: long — 执行耗时毫秒
- tokenUsage: int — 工具执行消耗的 Token



---

## 第 6 块：ToolErrorAction

### 类介绍
【设计动机】RETRY / SKIP / ABORT / FALLBACK

### 包路径
lyclaw-engine → lyjew.com.lyclaw.tool

### 类型
枚举
### 枚举值
- RETRY — 重试当前工具调用（每次重试前指数退避）
- SKIP — 跳过当前工具，把错误信息作为 tool_result 注入上下文
- ABORT — 终止整个工具调用循环，直接返回错误给用户
- FALLBACK — 使用备用工具/方案

### 选型理由
只有四种明确的决策路径，用枚举固化决策空间，避免 int/String 魔数。

- RETRY — 重试当前工具调用（每次重试前指数退避）
- SKIP — 跳过当前工具，把错误信息作为 tool_result 注入上下文
- ABORT — 终止整个工具调用循环，直接返回错误给用户
- FALLBACK — 使用备用工具/方案

只有四种明确的决策路径，用枚举固化决策空间，避免 int/String 魔数。



---

## 第 7 块：MemoryContent

### 类介绍
【设计动机】MemoryManager.read() 的返回值

### 包路径
lyclaw-engine → lyjew.com.lyclaw.memory

### 类型
类（值对象）
### 方法签名
- 构造器(content, title, enabled, tags, relevanceScore)
- 所有字段 getter

### 核心字段
- content: String — 记忆正文（Markdown 格式）
- title: String — 记忆标题
- enabled: boolean — 是否启用
- tags: List<String> — 标签列表
- relevanceScore: double — 相关性评分

- 构造器(content, title, enabled, tags, relevanceScore)
- 所有字段 getter

- content: String — 记忆正文（Markdown 格式）
- title: String — 记忆标题
- enabled: boolean — 是否启用
- tags: List<String> — 标签列表
- relevanceScore: double — 相关性评分



---

## 第 8 块：PageResult

### 类介绍
【设计动机】分页查询通用返回值

### 包路径
lyclaw-engine → lyjew.com.lyclaw.common

### 类型
类（值对象）
### 方法签名
- 构造器(items, total, page, size)
- 所有字段 getter
- static <T> PageResult<T> of(List<T> items, long total, int page, int size)
- hasMore(): boolean — 是否还有更多数据
- getTotalPages(): long — 总页数

### 核心字段
- items: List<T> — 当前页数据
- total: long — 总记录数
- page: int — 当前页码（从 1 开始）
- size: int — 每页大小

- 构造器(items, total, page, size)
- 所有字段 getter
- static <T> PageResult<T> of(List<T> items, long total, int page, int size)
- hasMore(): boolean — 是否还有更多数据
- getTotalPages(): long — 总页数

- items: List<T> — 当前页数据
- total: long — 总记录数
- page: int — 当前页码（从 1 开始）
- size: int — 每页大小



---


---

# 第二部分：核心接口

> **设计意图**：定义引擎层骨架 — 要做什么而非怎么做。只依赖 DTO 值对象和 common 模块的实体。各模块通过接口解耦，上层业务只和接口打交道。

## 实现文件清单

| 序号 | 文件 | 包 | 类型 | 说明 |
|------|------|-----|------|------|
| 9 | Engine.java | engine | 接口 | 引擎顶层入口 |
| 10 | Pipeline.java | pipeline | 接口 | 管道编排入口 |
| 11 | PipelineStage.java | pipeline | 接口 | 管道阶段抽象 |
| 12 | Chain.java | pipeline | 接口 | 阶段链控制 |
| 14 | ContextBuilder.java | context | 接口 | 上下文构建策略 |
| 15 | FullWindowContextBuilder.java | context.impl | 类 | 全量窗口策略（默认兜底） |
| 16 | Interceptor.java | interceptor | 接口 | 拦截器抽象 |
| 17 | InterceptorChain.java | interceptor.impl | 类 | 拦截器链管理器 |
| 18 | RateLimitInterceptor.java | interceptor.impl | 类 | 限流拦截器 |
| 19 | SensitiveDataInterceptor.java | interceptor.impl | 类 | 敏感数据脱敏 |
| 20 | LoggingInterceptor.java | interceptor.impl | 类 | 日志记录 |
| 21 | Tool.java | tool | 接口 | 工具抽象 |
| 22 | ToolRegistry.java | tool | 接口 | 工具注册表 |
| 23 | ToolCallPolicy.java | tool | 接口 | 工具调用循环策略 |
| 24 | Skill.java | skill | 接口 | 技能抽象 |
| 25 | SkillType.java | skill | 枚举 | BUILTIN / USER_DEFINED / COMPOSITE |
| 26 | SkillExecutor.java | skill | 接口 | 技能执行器抽象 |
| 27 | SkillProgressCallback.java | skill | 接口 | 技能进度回调 |
| 28 | SkillRegistry.java | skill | 接口 | 技能注册中心 |
| 29 | SkillGraph.java | skill | 接口 | 技能依赖关系图（DAG） |
| 30 | Event.java | event | 类 | 事件基类 |
| 31 | EventBus.java | event | 接口 | 事件总线 |
| 32 | MemoryManager.java | memory | 接口 | 长期记忆管理器 |
| 33 | MemoryStrategy.java | memory | 接口 | 记忆注入策略 |

## 第 9 块：Engine

### 类介绍
【设计动机】引擎顶层入口

### 包路径
lyclaw-engine → lyjew.com.lyclaw.engine

### 类型
接口
### 方法签名
- getName(): String — 引擎名称
- supports(ChatRequest): boolean — 是否支持该请求
- execute(ChatRequest): Flux<String> — 执行对话（返回流式结果）
- getMetadata(): EngineMetadata — 获取引擎元信息

### 设计模式
策略模式。每个 Engine 实现是一个独立的策略，EngineSelector 根据请求特征自动路由。

- getName(): String — 引擎名称
- supports(ChatRequest): boolean — 是否支持该请求
- execute(ChatRequest): Flux<String> — 执行对话（返回流式结果）
- getMetadata(): EngineMetadata — 获取引擎元信息

策略模式。每个 Engine 实现是一个独立的策略，EngineSelector 根据请求特征自动路由。



---

## 第 10 块：Pipeline

### 类介绍
【设计动机】管道编排入口

### 包路径
lyclaw-engine → lyjew.com.lyclaw.pipeline

### 类型
接口
### 方法签名
- execute(ChatContext): void — 执行整个管道
- getStages(): List<PipelineStage> — 获取当前管道的所有阶段

### 调用时序
1. ContextBuildStage — 构建上下文（注入记忆 + 会话历史 + 工具列表）
2. InterceptorStage — 执行拦截器链（限流/日志/脱敏）
3. ToolCallLoopStage — 模型调用 + 工具执行循环
4. MetricsStage — 采集监控指标
5. ResponseBuildStage — 构建最终响应

- execute(ChatContext): void — 执行整个管道
- getStages(): List<PipelineStage> — 获取当前管道的所有阶段

1. ContextBuildStage — 构建上下文（注入记忆 + 会话历史 + 工具列表）
2. InterceptorStage — 执行拦截器链（限流/日志/脱敏）
3. ToolCallLoopStage — 模型调用 + 工具执行循环
4. MetricsStage — 采集监控指标
5. ResponseBuildStage — 构建最终响应



---

## 第 11 块：PipelineStage

### 类介绍
【设计动机】管道阶段抽象

### 包路径
lyclaw-engine → lyjew.com.lyclaw.pipeline

### 类型
接口
### 方法签名
- process(ChatContext, Chain): void — 处理当前阶段，调用 Chain.next() 传递到下一阶段
- getOrder(): int — 阶段执行顺序（值越小越先执行）
- getStageName(): String — 阶段名称（用于日志和监控）

### 设计模式
模板方法模式 + 责任链模式。每个 Stage 处理完后必须调用 Chain.next()。

- process(ChatContext, Chain): void — 处理当前阶段，调用 Chain.next() 传递到下一阶段
- getOrder(): int — 阶段执行顺序（值越小越先执行）
- getStageName(): String — 阶段名称（用于日志和监控）

模板方法模式 + 责任链模式。每个 Stage 处理完后必须调用 Chain.next()。



---

## 第 12 块：Chain

### 类介绍
【设计动机】阶段链控制

### 包路径
lyclaw-engine → lyjew.com.lyclaw.pipeline

### 类型
接口
### 方法签名
- next(ChatContext): void — 调用链中的下一个 Stage
- breakChain(ChatContext): void — 中断管道，跳过后续所有 Stage
- getCurrentStage(): int — 当前执行的 Stage 序号（用于监控和调试）

- next(ChatContext): void — 调用链中的下一个 Stage
- breakChain(ChatContext): void — 中断管道，跳过后续所有 Stage
- getCurrentStage(): int — 当前执行的 Stage 序号（用于监控和调试）



---


## 第 13 块：DefaultChain

### 类介绍
【设计动机】Chain 接口的默认实现。DefaultPipeline.execute() 内部使用，控制 Stage 间流转。

### 包路径
lyclaw-engine → lyjew.com.lyclaw.pipeline.impl

### 类型
类

- proceed(ChatContext): void — 执行当前 Stage 的 process() 并自动推进
- getCurrentStage(): int — 当前 Stage 索引
- getStageCount(): int — 总 Stage 数
- hasNext(): boolean — 是否还有后续 Stage

---

## 第 14 块：ContextBuilder

### 类介绍
【设计动机】上下文构建策略

### 包路径
lyclaw-engine → lyjew.com.lyclaw.context

### 类型
接口
### 方法签名
- buildContext(Session, MemoryContent, List<ToolDefinition>): List<Message> — 构建模型输入
- supports(ChatRequest): boolean — 是否支持该请求（用于多策略路由）

### 设计模式
策略模式。可以有多个 ContextBuilder 实现，根据请求特征选择合适的策略。

- buildContext(Session, MemoryContent, List<ToolDefinition>): List<Message> — 构建模型输入
- supports(ChatRequest): boolean — 是否支持该请求（用于多策略路由）

策略模式。可以有多个 ContextBuilder 实现，根据请求特征选择合适的策略。



---

## 第 15 块：FullWindowContextBuilder

### 类介绍
【设计动机】全量窗口策略（默认兜底）

### 包路径
lyclaw-engine → lyjew.com.lyclaw.context.impl

### 类型
类
### 方法签名
- buildContext(Session, MemoryContent, List<ToolDefinition>): List<Message>
- supports(ChatRequest): boolean — 始终返回 true（兜底策略）

### 策略说明
1. 注入 System Prompt（包含可用工具描述）
2. 注入长期记忆（MemoryContent.content → <memory> 标签）
3. 追加会话中的所有消息历史
4. 注入当前请求的消息

- buildContext(Session, MemoryContent, List<ToolDefinition>): List<Message>
- supports(ChatRequest): boolean — 始终返回 true（兜底策略）

1. 注入 System Prompt（包含可用工具描述）
2. 注入长期记忆（MemoryContent.content → <memory> 标签）
3. 追加会话中的所有消息历史
4. 注入当前请求的消息



---

## 第 16 块：Interceptor

### 类介绍
【设计动机】拦截器抽象

### 包路径
lyclaw-engine → lyjew.com.lyclaw.interceptor

### 类型
接口
### 方法签名
- preHandle(ChatContext): boolean — 请求处理前执行，返回 false 终止流程
- postHandle(ChatContext, ChatResult): void — 请求处理后执行
- getOrder(): int — 执行顺序（值越小越先执行）

- preHandle(ChatContext): boolean — 请求处理前执行，返回 false 终止流程
- postHandle(ChatContext, ChatResult): void — 请求处理后执行
- getOrder(): int — 执行顺序（值越小越先执行）



---

## 第 17 块：InterceptorChain

### 类介绍
【设计动机】拦截器链管理器

### 包路径
lyclaw-engine → lyjew.com.lyclaw.interceptor.impl

### 类型
类
### 方法签名
- addInterceptor(Interceptor): void — 注册拦截器
- removeInterceptor(Interceptor): void — 移除拦截器
- preHandle(ChatContext): boolean — 按 @Order 顺序执行所有 preHandle
- postHandle(ChatContext, ChatResult): void — 按 @Order 逆序执行所有 postHandle
- getInterceptors(): List<Interceptor> — 获取已排序的拦截器列表

- addInterceptor(Interceptor): void — 注册拦截器
- removeInterceptor(Interceptor): void — 移除拦截器
- preHandle(ChatContext): boolean — 按 @Order 顺序执行所有 preHandle
- postHandle(ChatContext, ChatResult): void — 按 @Order 逆序执行所有 postHandle
- getInterceptors(): List<Interceptor> — 获取已排序的拦截器列表



---

## 第 18 块：RateLimitInterceptor

### 类介绍
【设计动机】限流拦截器

### 包路径
lyclaw-engine → lyjew.com.lyclaw.interceptor.impl

### 类型
类
### 方法签名
- preHandle(ChatContext): boolean — 检查当前请求是否超过配额
- postHandle(ChatContext, ChatResult): void — 更新 Token 桶
- getOrder(): int — 返回 Integer.MIN_VALUE（最先执行）

- preHandle(ChatContext): boolean — 检查当前请求是否超过配额
- postHandle(ChatContext, ChatResult): void — 更新 Token 桶
- getOrder(): int — 返回 Integer.MIN_VALUE（最先执行）



---

## 第 19 块：SensitiveDataInterceptor

### 类介绍
【设计动机】敏感数据脱敏

### 包路径
lyclaw-engine → lyjew.com.lyclaw.interceptor.impl

### 类型
类
### 方法签名
- preHandle(ChatContext): boolean — 遍历消息列表，对匹配脱敏规则的内容进行替换
- getOrder(): int

- preHandle(ChatContext): boolean — 遍历消息列表，对匹配脱敏规则的内容进行替换
- getOrder(): int



---

## 第 20 块：LoggingInterceptor

### 类介绍
【设计动机】日志记录

### 包路径
lyclaw-engine → lyjew.com.lyclaw.interceptor.impl

### 类型
类
### 方法签名
- preHandle(ChatContext): boolean — 记录请求开始时间 + 请求摘要
- postHandle(ChatContext, ChatResult): void — 计算耗时 + Token 用量 + 记录完成日志
- getOrder(): int

- preHandle(ChatContext): boolean — 记录请求开始时间 + 请求摘要
- postHandle(ChatContext, ChatResult): void — 计算耗时 + Token 用量 + 记录完成日志
- getOrder(): int



---

## 第 21 块：Tool

### 类介绍
【设计动机】工具抽象

### 包路径
lyclaw-engine → lyjew.com.lyclaw.tool

### 类型
接口
### 方法签名
- getName(): String — 工具名称
- execute(ToolCall, ChatContext): ToolResult — 执行工具
- getDefinition(): ToolDefinition — 获取工具定义

### 工具定义来源
getDefinition() 返回 lyjew.com.lyclaw.model.ToolDefinition（字段：name/displayName/description/parameters/source/serverName/timeout）

- getName(): String — 工具名称
- execute(ToolCall, ChatContext): ToolResult — 执行工具
- getDefinition(): ToolDefinition — 获取工具定义

getDefinition() 返回 lyjew.com.lyclaw.model.ToolDefinition（字段：name/displayName/description/parameters/source/serverName/timeout）



---

## 第 22 块：ToolRegistry

### 类介绍
【设计动机】工具注册表

### 包路径
lyclaw-engine → lyjew.com.lyclaw.tool

### 类型
接口
### 方法签名
- register(Tool): void — 注册工具（同名工具第二次注册抛异常）
- get(String name): Tool — 按名称查找工具
- getAllDefinitions(): List<lyjew.com.lyclaw.model.ToolDefinition> — 返回所有已注册工具的定义
- execute(ToolCall): ToolResult — 按 toolCall 中的 name 执行对应工具

- register(Tool): void — 注册工具（同名工具第二次注册抛异常）
- get(String name): Tool — 按名称查找工具
- getAllDefinitions(): List<lyjew.com.lyclaw.model.ToolDefinition> — 返回所有已注册工具的定义
- execute(ToolCall): ToolResult — 按 toolCall 中的 name 执行对应工具



---

## 第 23 块：ToolCallPolicy

### 类介绍
【设计动机】工具调用循环策略

### 包路径
lyclaw-engine → lyjew.com.lyclaw.tool

### 类型
接口
### 方法签名
- getMaxRounds(): int — 最大工具调用轮次
- shouldContinue(ChatContext, int currentRound): boolean — 判断是否继续循环
- handleToolError(ToolCall, Exception, ChatContext): ToolErrorAction — 工具执行出错决策
- shouldRetryOnError(ToolCall, Exception, int retryCount): boolean — 是否重试

- getMaxRounds(): int — 最大工具调用轮次
- shouldContinue(ChatContext, int currentRound): boolean — 判断是否继续循环
- handleToolError(ToolCall, Exception, ChatContext): ToolErrorAction — 工具执行出错决策
- shouldRetryOnError(ToolCall, Exception, int retryCount): boolean — 是否重试



---

## 第 24 块：Skill

### 类介绍
【设计动机】技能抽象

### 包路径
lyclaw-engine → lyjew.com.lyclaw.skill

### 类型
接口
### 方法签名
- getSkillId(): String — 技能唯一标识
- getName(): String — 技能名称
- getDescription(): String — 技能描述
- execute(ChatContext): CompletableFuture<SkillResult> — 执行技能

- getSkillId(): String — 技能唯一标识
- getName(): String — 技能名称
- getDescription(): String — 技能描述
- execute(ChatContext): CompletableFuture<SkillResult> — 执行技能



---

## 第 25 块：SkillType

### 类介绍
【设计动机】BUILTIN / USER_DEFINED / COMPOSITE

### 包路径
lyclaw-engine → lyjew.com.lyclaw.skill

### 类型
枚举
### 枚举值
- BUILTIN — 内置技能
- USER_DEFINED — 用户自定义技能
- COMPOSITE — 复合技能（由多个子技能编排而成）

- BUILTIN — 内置技能
- USER_DEFINED — 用户自定义技能
- COMPOSITE — 复合技能（由多个子技能编排而成）



---

## 第 26 块：SkillExecutor

### 类介绍
【设计动机】技能执行器抽象

### 包路径
lyclaw-engine → lyjew.com.lyclaw.skill

### 类型
接口
### 方法签名
- execute(Skill, ChatContext): CompletableFuture<SkillResult> — 异步执行技能
- cancel(String skillId): boolean — 取消正在执行的技能
- getProgress(String skillId): double — 获取执行进度（0.0 ~ 1.0）
- setProgressCallback(SkillProgressCallback): void — 设置全局进度回调

- execute(Skill, ChatContext): CompletableFuture<SkillResult> — 异步执行技能
- cancel(String skillId): boolean — 取消正在执行的技能
- getProgress(String skillId): double — 获取执行进度（0.0 ~ 1.0）
- setProgressCallback(SkillProgressCallback): void — 设置全局进度回调



---

## 第 27 块：SkillProgressCallback

### 类介绍
【设计动机】技能进度回调

### 包路径
lyclaw-engine → lyjew.com.lyclaw.skill

### 类型
接口
### 方法签名
- onProgress(String skillId, double progress, String message): void
- onComplete(String skillId, SkillResult result): void
- onError(String skillId, Throwable error): void

- onProgress(String skillId, double progress, String message): void
- onComplete(String skillId, SkillResult result): void
- onError(String skillId, Throwable error): void



---

## 第 28 块：SkillRegistry

### 类介绍
【设计动机】技能注册中心

### 包路径
lyclaw-engine → lyjew.com.lyclaw.skill

### 类型
接口
### 方法签名
- register(Skill): void — 注册技能
- get(String skillId): Skill — 按 ID 查找
- getAll(): List<Skill> — 获取所有已注册技能
- getDependencies(String skillId): List<String> — 获取技能依赖
- resolveExecutionOrder(): List<String> — 拓扑排序后返回执行顺序

- register(Skill): void — 注册技能
- get(String skillId): Skill — 按 ID 查找
- getAll(): List<Skill> — 获取所有已注册技能
- getDependencies(String skillId): List<String> — 获取技能依赖
- resolveExecutionOrder(): List<String> — 拓扑排序后返回执行顺序



---

## 第 29 块：SkillGraph

### 类介绍
【设计动机】技能依赖关系图（DAG）

### 包路径
lyclaw-engine → lyjew.com.lyclaw.skill

### 类型
接口
### 方法签名
- addDependency(String fromSkillId, String toSkillId): void — from 依赖 to
- removeDependency(String fromSkillId, String toSkillId): void — 移除依赖
- getDependencies(String skillId): List<String> — 获取直接依赖
- getDependents(String skillId): List<String> — 获取直接依赖者
- getExecutionOrder(): List<String> — 拓扑排序（DFS + 后序）
- hasCycle(): boolean — 检测是否有环（DFS 三色标记法）

- addDependency(String fromSkillId, String toSkillId): void — from 依赖 to
- removeDependency(String fromSkillId, String toSkillId): void — 移除依赖
- getDependencies(String skillId): List<String> — 获取直接依赖
- getDependents(String skillId): List<String> — 获取直接依赖者
- getExecutionOrder(): List<String> — 拓扑排序（DFS + 后序）
- hasCycle(): boolean — 检测是否有环（DFS 三色标记法）



---

## 第 30 块：Event

### 类介绍
【设计动机】事件基类

### 包路径
lyclaw-engine → lyjew.com.lyclaw.event

### 类型
类
### 方法签名
- 构造器(source, eventType)
- getEventId(): String — UUID
- getTimestamp(): Instant — 创建时间
- getSource(): String — 事件来源
- getEventType(): String — 事件类型标识

### 核心字段
- eventId: String — UUID
- timestamp: Instant — 事件创建时间
- source: String — 来源标识
- eventType: String — 事件类型标识

- 构造器(source, eventType)
- getEventId(): String — UUID
- getTimestamp(): Instant — 创建时间
- getSource(): String — 事件来源
- getEventType(): String — 事件类型标识

- eventId: String — UUID
- timestamp: Instant — 事件创建时间
- source: String — 来源标识
- eventType: String — 事件类型标识



---

## 第 31 块：EventBus

### 类介绍
【设计动机】事件总线

### 包路径
lyclaw-engine → lyjew.com.lyclaw.event

### 类型
接口
### 方法签名
- publish(Event): void — 发布事件
- subscribe(Class<T> eventType, Consumer<T>): void — 订阅事件（Consumer 为 java.util.function.Consumer）
- unsubscribe(Class<T> eventType, Consumer<T>): void — 取消订阅
- clear(): void — 清除所有订阅者

- publish(Event): void — 发布事件
- subscribe(Class<T> eventType, Consumer<T>): void — 订阅事件（Consumer 为 java.util.function.Consumer）
- unsubscribe(Class<T> eventType, Consumer<T>): void — 取消订阅
- clear(): void — 清除所有订阅者



---

## 第 32 块：MemoryManager

### 类介绍
【设计动机】长期记忆管理器

### 包路径
lyclaw-engine → lyjew.com.lyclaw.memory

### 类型
接口
### 方法签名
- read(): MemoryContent — 读取长期记忆（单例 global）
- append(String content): void — 追加记忆内容
- rewrite(String content): void — 重写整条记忆
- search(String query): List<MemoryContent> — 搜索记忆
- getStrategy(): MemoryStrategy — 获取当前记忆策略
- setStrategy(MemoryStrategy): void — 切换记忆策略

### 持久化说明
实际文件读写委托给 lyjew.com.lyclaw.storage.MemoryStorage。实体来源：lyjew.com.lyclaw.model.Memory（单例 id=global）。

- read(): MemoryContent — 读取长期记忆（单例 global）
- append(String content): void — 追加记忆内容
- rewrite(String content): void — 重写整条记忆
- search(String query): List<MemoryContent> — 搜索记忆
- getStrategy(): MemoryStrategy — 获取当前记忆策略
- setStrategy(MemoryStrategy): void — 切换记忆策略

实际文件读写委托给 lyjew.com.lyclaw.storage.MemoryStorage。实体来源：lyjew.com.lyclaw.model.Memory（单例 id=global）。



---

## 第 33 块：MemoryStrategy

### 类介绍
【设计动机】记忆注入策略

### 包路径
lyclaw-engine → lyjew.com.lyclaw.memory

### 类型
接口
### 方法签名
- formatForContext(MemoryContent): String — 将记忆格式化为提示词片段
- shouldIncludeInContext(MemoryContent, ChatContext): boolean — 判断是否注入记忆
- getPriority(): int — 优先级

### 与 FormatStrategy 的区别
MemoryStorage 的 MarkdownFormatStrategy 决定文件读写格式；MemoryStrategy 决定记忆如何注入上下文。

- formatForContext(MemoryContent): String — 将记忆格式化为提示词片段
- shouldIncludeInContext(MemoryContent, ChatContext): boolean — 判断是否注入记忆
- getPriority(): int — 优先级

MemoryStorage 的 MarkdownFormatStrategy 决定文件读写格式；MemoryStrategy 决定记忆如何注入上下文。



---


---

# 第三部分：安全/过滤/事务接口

> **设计意图**：横切关注点，独立于核心对话流程。被 Interceptor 和 Engine 调用，不依赖 Tool/Skill/Memory 的具体实现。

## 实现文件清单

| 序号 | 文件 | 包 | 类型 | 说明 |
|------|------|-----|------|------|
| 34 | SecurityManager.java | security | 接口 | 安全管理器 |
| 35 | ApprovalResult.java | security | 类（值对象） | 审批结果 |
| 36 | SandboxLevel.java | security | 枚举 | NONE / READ_ONLY / RESTRICTED / ISOLATED |
| 37 | ContentFilter.java | filter | 接口 | 内容过滤器 |
| 38 | FilterResult.java | filter | 类（值对象） | 过滤结果 |
| 39 | SessionTransaction.java | transaction | 接口 | 对话事务抽象 |
| 40 | TransactionContext.java | transaction | 类 | 事务上下文 |
| 41 | SessionUpdate.java | transaction | 类（值对象） | 单次更新记录 |
| 42 | SessionUpdateStrategy.java | transaction | 接口 | 事务更新策略 |

## 第 34 块：SecurityManager

### 类介绍
【设计动机】安全管理器

### 包路径
lyclaw-engine → lyjew.com.lyclaw.security

### 类型
接口
### 方法签名
- approve(ChatContext, String action): ApprovalResult — 前置审批
- revoke(String sessionId): void — 撤销审批
- checkPermission(String userId, String action): boolean — 权限检查
- getEffectivePolicies(): List<String> — 获取当前生效的安全策略列表

- approve(ChatContext, String action): ApprovalResult — 前置审批
- revoke(String sessionId): void — 撤销审批
- checkPermission(String userId, String action): boolean — 权限检查
- getEffectivePolicies(): List<String> — 获取当前生效的安全策略列表



---

## 第 35 块：ApprovalResult

### 类介绍
【设计动机】审批结果

### 包路径
lyclaw-engine → lyjew.com.lyclaw.security

### 类型
类（值对象）
### 方法签名
- 构造器(approved, reason, approvedBy, approvedAt, sandboxLevel)
- static ApprovalResult granted(SandboxLevel level)
- static ApprovalResult denied(String reason)

### 核心字段
- approved: boolean — 是否通过
- reason: String — 审批理由或拒绝原因
- approvedBy: String — 审批人
- approvedAt: Instant — 审批时间
- sandboxLevel: SandboxLevel — 审批通过的沙箱级别

- 构造器(approved, reason, approvedBy, approvedAt, sandboxLevel)
- static ApprovalResult granted(SandboxLevel level)
- static ApprovalResult denied(String reason)

- approved: boolean — 是否通过
- reason: String — 审批理由或拒绝原因
- approvedBy: String — 审批人
- approvedAt: Instant — 审批时间
- sandboxLevel: SandboxLevel — 审批通过的沙箱级别



---

## 第 36 块：SandboxLevel

### 类介绍
【设计动机】安全沙箱级别枚举

### 包路径
lyclaw-engine → lyjew.com.lyclaw.security

### 类型
枚举
### 枚举值
- NONE — 无沙箱。直接执行，仅限白名单安全工具（计算器、查时间等）
- READ_ONLY — 只读沙箱。可读文件/查数据库，不能执行写操作
- RESTRICTED — 受限沙箱。只能操作临时目录，有内存/CPU限制
- CONTAINER — 容器沙箱。Docker容器中执行，独立的文件系统和网络命名空间
- ISOLATED — 完全隔离沙箱。子进程/虚拟机中执行，网络隔离，资源严格限制



---

## 第 37 块：ContentFilter

### 类介绍
【设计动机】内容过滤器

### 包路径
lyclaw-engine → lyjew.com.lyclaw.filter

### 类型
接口
### 方法签名
- filter(String content, ChatContext): FilterResult — 过滤内容
- getFilterName(): String — 过滤器名称

- filter(String content, ChatContext): FilterResult — 过滤内容
- getFilterName(): String — 过滤器名称



---

## 第 38 块：FilterResult

### 类介绍
【设计动机】过滤结果

### 包路径
lyclaw-engine → lyjew.com.lyclaw.filter

### 类型
类（值对象）
### 方法签名
- 构造器(passed, filteredContent, reason, matchedRules)
- static FilterResult pass(String content)
- static FilterResult reject(String content, String reason)

### 核心字段
- passed: boolean — 是否通过
- filteredContent: String — 过滤后的内容
- reason: String — 拒绝/替换原因
- matchedRules: List<String> — 匹配的规则列表

- 构造器(passed, filteredContent, reason, matchedRules)
- static FilterResult pass(String content)
- static FilterResult reject(String content, String reason)

- passed: boolean — 是否通过
- filteredContent: String — 过滤后的内容
- reason: String — 拒绝/替换原因
- matchedRules: List<String> — 匹配的规则列表



---

## 第 39 块：SessionTransaction

### 类介绍
【设计动机】对话事务抽象

### 包路径
lyclaw-engine → lyjew.com.lyclaw.transaction

### 类型
接口
### 方法签名
- begin(String sessionId, String context): void — 开始事务
- commit(String transactionId): boolean — 提交事务
- rollback(String transactionId): boolean — 回滚事务
- getStatus(String transactionId): String — 获取事务状态
- createSnapshot(): List<SessionUpdate> — 创建当前会话的快照，返回变更记录列表

- begin(String sessionId, String context): void — 开始事务
- commit(String transactionId): boolean — 提交事务
- rollback(String transactionId): boolean — 回滚事务
- getStatus(String transactionId): String — 获取事务状态
- createSnapshot(String sessionId, ChatContext): SessionUpdate — 创建快照



---

## 第 40 块：TransactionContext

### 类介绍
【设计动机】事务上下文

### 包路径
lyclaw-engine → lyjew.com.lyclaw.transaction

### 类型
类
### 方法签名
- 构造器(transactionId, sessionId, contextSnapshot) — 构造器简化，updates/status/createdAt 自动初始化
- addUpdate(SessionUpdate): void — 只有在 ACTIVE 状态下才允许添加
- getTransactionId(): String
- getStatus(): String

### 核心字段
- transactionId: String — 事务 ID（由 begin() 生成）
- sessionId: String — 关联的会话 ID
- contextSnapshot: String — 事务开始时的上下文快照
- updates: List<SessionUpdate> — 事务期间的更新记录（自动初始化空列表）
- status: String — 当前状态常量（STATUS_ACTIVE / STATUS_COMMITTED / STATUS_ROLLED_BACK）
- createdAt: Instant — 创建时间（自动初始化）



---

## 第 41 块：SessionUpdate

### 类介绍
【设计动机】单次更新记录

### 包路径
lyclaw-engine → lyjew.com.lyclaw.transaction

### 类型
类（值对象）
### 方法签名
- 构造器(sessionId, updateType, oldValue, newValue, operator, timestamp)

### 核心字段
- sessionId: String — 会话 ID
- updateType: String — 更新类型
- oldValue: String — 旧值
- newValue: String — 新值
- operator: String — 操作人
- timestamp: Instant — 操作时间

- 构造器(sessionId, updateType, oldValue, newValue, operator, timestamp)

- sessionId: String — 会话 ID
- updateType: String — 更新类型
- oldValue: String — 旧值
- newValue: String — 新值
- operator: String — 操作人
- timestamp: Instant — 操作时间



---

## 第 42 块：SessionUpdateStrategy

### 类介绍
【设计动机】事务更新策略

### 包路径
lyclaw-engine → lyjew.com.lyclaw.transaction

### 类型
接口
### 方法签名
- merge(List<SessionUpdate> existing, SessionUpdate newUpdate): List<SessionUpdate>
- getStrategyName(): String

- merge(List<SessionUpdate> existing, SessionUpdate newUpdate): List<SessionUpdate>
- getStrategyName(): String



---


---

# 第四部分：错误/追踪/缓存/检索

> **设计意图**：错误策略和追踪工具是全局基础设施，被各个模块引用但不引用各模块。

## 实现文件清单

| 序号 | 文件 | 包 | 类型 | 说明 |
|------|------|-----|------|------|
| 43 | ErrorPolicy.java | error | 接口 | 错误处理策略 |
| 44 | ModelException.java | error | 类（异常） | 模型调用异常 |
| 45 | ToolExecuteException.java | error | 类（异常） | 工具执行异常 |
| 46 | TraceContext.java | tracing | 类 | 全链路追踪上下文 |
| 47 | CacheService.java | cache | 接口 | 缓存服务 |
| 48 | VectorStore.java | retrieval | 接口 | 向量检索存储 |

## 第 43 块：ErrorPolicy

### 类介绍
【设计动机】错误处理策略

### 包路径
lyclaw-engine → lyjew.com.lyclaw.error

### 类型
接口
### 方法签名
- onModelError(ModelException, ChatContext, ChatRequest): ToolErrorAction
  - ChatResult 来源：lyclaw-engine.dto.ChatResult
- onToolError(ToolCall, Exception, int retryCount): ToolErrorAction
- getRetryConfig(): RetryConfig — 获取重试配置
- getCircuitBreakerState(): String — 获取熔断器状态

- onModelError(ModelException, ChatContext, ChatRequest): ToolErrorAction
  - ChatResult 来源：lyclaw-engine.dto.ChatResult
- onToolError(ToolCall, Exception, int retryCount): ToolErrorAction
- getRetryConfig(): RetryConfig — 获取重试配置
- getCircuitBreakerState(): String — 获取熔断器状态



---

## 第 44 块：RetryConfig

### 类介绍
【设计动机】重试配置值对象

### 包路径
lyclaw-engine → lyjew.com.lyclaw.error

### 类型
类（值对象）
### 核心字段
- maxRetries: int — 最大重试次数
- baseDelayMs: long — 基础延迟（ms）
- fixedDelayMs: long — 固定延迟（ms，FIXED策略使用）
- strategy: BackoffStrategy — 退避策略枚举（FIXED / EXPONENTIAL / LINEAR）

### 方法签名
- 构造器(maxRetries, baseDelayMs, fixedDelayMs, strategy)
- static exponential(int maxRetries, long baseDelayMs): RetryConfig
- static fixed(int maxRetries, long fixedDelayMs): RetryConfig

### 被引用场景
- ErrorPolicy.getRetryConfig() 的返回值，供 ToolCallLoop 决定重试行为

- maxRetries: int — 最大重试次数
- baseDelayMs: long — 基础延迟（ms）
- fixedDelayMs: long — 固定延迟（ms，FIXED策略使用）
- strategy: BackoffStrategy — 退避策略枚举（FIXED / EXPONENTIAL / LINEAR）

- 构造器(maxRetries, baseDelayMs, fixedDelayMs, strategy)
- static exponential(int maxRetries, long baseDelayMs): RetryConfig
- static fixed(int maxRetries, long fixedDelayMs): RetryConfig

ErrorPolicy.getRetryConfig() 的返回值，供 ToolCallLoop 决定重试行为



---

## 第 45 块：ModelException

### 类介绍
【设计动机】模型调用异常

### 包路径
lyclaw-engine → lyjew.com.lyclaw.error

### 类型
类（异常）
### 继承关系
extends lyjew.com.lyclaw.exception.ModelException → extends LyClawException → extends RuntimeException

### 方法签名
- 构造器(code, httpStatus, message)
- 构造器(code, httpStatus, message, rawResponse)
- getRawResponse(): String — 获取厂商原始错误响应
- static of(ErrorCode): ModelException
- static withRawResponse(int httpStatus, String message, String rawResponse): ModelException

extends lyjew.com.lyclaw.exception.ModelException → extends LyClawException → extends RuntimeException

- 构造器(code, httpStatus, message)
- 构造器(code, httpStatus, message, rawResponse)
- getRawResponse(): String — 获取厂商原始错误响应
- static of(ErrorCode): ModelException
- static withRawResponse(int httpStatus, String message, String rawResponse): ModelException



---

## 第 46 块：ToolExecuteException

### 类介绍
【设计动机】工具执行异常

### 包路径
lyclaw-engine → lyjew.com.lyclaw.error

### 类型
类（异常）
### 继承关系
extends lyjew.com.lyclaw.base.exception.LyClawException → extends RuntimeException

### 方法签名
- 构造器(toolName, message, cause)
- getToolName(): String — 出错的工具名称
- static of(String toolName, String message): ToolExecuteException
- static of(String toolName, Throwable cause): ToolExecuteException

extends lyjew.com.lyclaw.base.exception.LyClawException → extends RuntimeException

- 构造器(toolName, message, cause)
- getToolName(): String — 出错的工具名称
- static of(String toolName, String message): ToolExecuteException
- static of(String toolName, Throwable cause): ToolExecuteException



---

## 第 47 块：TraceContext

### 类介绍
【设计动机】全链路追踪上下文

### 包路径
lyclaw-engine → lyjew.com.lyclaw.tracing

### 类型
类
### 方法签名
- 构造器(traceId)
- beginStage(String stageName): void — 开始一个阶段
- endStage(String stageName): void — 结束一个阶段
- getStageDuration(String stageName): long — 获取阶段耗时
- getTotalDuration(): long — 获取总耗时
- getTraceId(): String — 获取追踪 ID
- toJson(): String — 序列化为 JSON（用于日志输出）

- 构造器(traceId)
- beginStage(String stageName): void — 开始一个阶段
- endStage(String stageName): void — 结束一个阶段
- getStageDuration(String stageName): long — 获取阶段耗时
- getTotalDuration(): long — 获取总耗时
- getTraceId(): String — 获取追踪 ID
- toJson(): String — 序列化为 JSON（用于日志输出）



---

## 第 48 块：CacheService

### 类介绍
【设计动机】缓存服务

### 包路径
lyclaw-engine → lyjew.com.lyclaw.cache

### 类型
接口
### 方法签名
- get(String key): Optional<String> — 获取缓存
- set(String key, String value, long ttlSeconds): void — 设置缓存
- evict(String key): void — 清除缓存
- clear(): void — 清空所有缓存
- getStats(): CacheStats — 获取命中率统计

- get(String key): Optional<String> — 获取缓存
- set(String key, String value, long ttlSeconds): void — 设置缓存
- evict(String key): void — 清除缓存
- clear(): void — 清空所有缓存
- getStats(): CacheStats — 获取命中率统计



---

## 第 49 块：CacheStats

### 类介绍
【设计动机】缓存统计信息值对象

### 包路径
lyclaw-engine → lyjew.com.lyclaw.cache

### 类型
类（值对象）
### 核心字段
- hitCount: long — 命中次数
- missCount: long — 未命中次数

### 方法签名
- 构造器(hitCount, missCount)
- getHitRate(): double — 命中率
- static empty(): CacheStats — 空统计

- hitCount: long — 命中次数
- missCount: long — 未命中次数

- 构造器(hitCount, missCount)
- getHitRate(): double — 命中率
- static empty(): CacheStats — 空统计

被 CacheService.getStats() 引用。



---

## 第 50 块：VectorStore

### 类介绍
【设计动机】向量检索存储

### 包路径
lyclaw-engine → lyjew.com.lyclaw.retrieval

### 类型
接口
### 方法签名
- store(String id, List<Float> vector, Map<String, Object> metadata): void
- search(List<Float> queryVector, int topK): List<SearchResult>
- delete(String id): void
- getCollectionName(): String

- store(String id, List<Float> vector, Map<String, Object> metadata): void
- search(List<Float> queryVector, int topK): List<SearchResult>
- delete(String id): void
- getCollectionName(): String



---


---

# 第五部分：Agent 协调

> **设计意图**：多 Agent 通信，依赖核心接口（EventBus/ContextBuilder 等），但不是核心路径必须的。DefaultEngine 选择性使用 Agent 功能。

## 实现文件清单

| 序号 | 文件 | 包 | 类型 | 说明 |
|------|------|-----|------|------|
| 49 | AgentCoordinator.java | agent | 接口 | Agent 生命周期管理者 |
| 50 | AgentChannel.java | agent | 接口 | Agent 通信渠道 |
| 51 | AgentMessage.java | agent | 类 | Agent 通信消息体 |
| 52 | AgentState.java | agent | 枚举 | IDLE / RUNNING / WAITING / COMPLETED / FAILED / CANCELLED |
| 53 | AgentTask.java | agent | 类 | Agent 任务描述 |
| 54 | TaskPlanner.java | task | 接口 | 任务规划器 |
| 55 | TaskPlan.java | task | 接口 | 任务计划 |
| 56 | TaskNode.java | task | 类（值对象） | 任务节点 |
| 57 | TaskResult.java | task | 类（值对象） | 任务执行结果 |
| 58 | TaskLedger.java | task | 接口 | 任务账本 |
| 59 | TaskRecord.java | task | 类（值对象） | 任务记录 |

## 第 51 块：SearchResult

### 类介绍
【设计动机】向量搜索结果值对象

### 包路径
lyclaw-engine → lyjew.com.lyclaw.retrieval

### 类型
类（值对象）
### 核心字段
- id: String — 匹配的记录ID
- score: double — 相似度分数（0.0~1.0）
- content: String — 匹配的原始内容
- metadata: Map<String, Object> — 关联元数据

### 方法签名
- 构造器(id, score, content, metadata)
- 所有字段 getter

- id: String — 匹配的记录ID
- score: double — 相似度分数（0.0~1.0）
- content: String — 匹配的原始内容
- metadata: Map<String, Object> — 关联元数据

- 构造器(id, score, content, metadata)
- 所有字段 getter

被 VectorStore.search() 引用。



---

## 第 52 块：AgentCoordinator

### 类介绍
【设计动机】Agent 生命周期管理者

### 包路径
lyclaw-engine → lyjew.com.lyclaw.agent

### 类型
接口
### 方法签名
- dispatch(ChatContext, AgentTask): CompletableFuture<AgentResult> — 派发任务
- cancel(String agentId): boolean — 取消 Agent
- getState(String agentId): AgentState — 获取 Agent 状态
- getChannels(String agentId): List<AgentChannel> — 获取 Agent 通信渠道
- broadcast(Event): void — 广播事件

- dispatch(ChatContext, AgentTask): CompletableFuture<AgentResult> — 派发任务
- cancel(String agentId): boolean — 取消 Agent
- getState(String agentId): AgentState — 获取 Agent 状态
- getChannels(String agentId): List<AgentChannel> — 获取 Agent 通信渠道
- broadcast(Event): void — 广播事件



---

## 第 53 块：AgentChannel

### 类介绍
【设计动机】Agent 通信渠道

### 包路径
lyclaw-engine → lyjew.com.lyclaw.agent

### 类型
接口
### 方法签名
- send(String fromAgentId, String toAgentId, AgentMessage): void
- broadcast(String fromAgentId, AgentMessage): void
- subscribe(String agentId, Consumer<AgentMessage>): void

- send(String fromAgentId, String toAgentId, AgentMessage): void
- broadcast(String fromAgentId, AgentMessage): void
- subscribe(String agentId, Consumer<AgentMessage>): void



---

## 第 54 块：AgentMessage

### 类介绍
【设计动机】Agent 通信消息体

### 包路径
lyclaw-engine → lyjew.com.lyclaw.agent

### 类型
类
### 方法签名
- 构造器(from, to, type, content, timestamp)

### 核心字段
- from: String — 发送方 Agent ID
- to: String — 接收方 Agent ID（null 表示广播）
- type: String — 消息类型
- content: String — 消息内容（JSON 格式）
- timestamp: Instant — 时间戳

- 构造器(from, to, type, content, timestamp)

- from: String — 发送方 Agent ID
- to: String — 接收方 Agent ID（null 表示广播）
- type: String — 消息类型
- content: String — 消息内容（JSON 格式）
- timestamp: Instant — 时间戳



---

## 第 55 块：AgentState

### 类介绍
【设计动机】IDLE / RUNNING / WAITING / COMPLETED / FAILED / CANCELLED

### 包路径
lyclaw-engine → lyjew.com.lyclaw.agent

### 类型
枚举
### 枚举值
IDLE — 空闲 / RUNNING — 执行中 / WAITING — 等待子 Agent / COMPLETED — 完成 / FAILED — 失败 / CANCELLED — 已取消

IDLE — 空闲 / RUNNING — 执行中 / WAITING — 等待子 Agent / COMPLETED — 完成 / FAILED — 失败 / CANCELLED — 已取消



---

## 第 56 块：AgentTask

### 类介绍
【设计动机】Agent 任务描述

### 包路径
lyclaw-engine → lyjew.com.lyclaw.agent

### 类型
类
### 方法签名
- 构造器(taskId, type, target, payload, metadata)

### 核心字段
taskId/type/target/payload/metadata

- 构造器(taskId, type, target, payload, metadata)

taskId/type/target/payload/metadata



---

## 第 57 块：TaskPlanner

### 类介绍
【设计动机】任务规划器

### 包路径
lyclaw-engine → lyjew.com.lyclaw.task

### 类型
接口
### 方法签名
- plan(ChatContext): TaskPlan — 规划任务
- optimize(AgentResult): TaskPlan — 根据执行结果优化后续任务

- plan(ChatContext): TaskPlan — 规划任务
- optimize(AgentResult): TaskPlan — 根据执行结果优化后续任务



---

## 第 58 块：TaskPlan

### 类介绍
【设计动机】任务计划

### 包路径
lyclaw-engine → lyjew.com.lyclaw.task

### 类型
接口
### 方法签名
- getNodes(): List<TaskNode>
- getDependencies(String nodeId): List<String>
- getEstimatedCompletionTime(): long
- isReady(): boolean

- getNodes(): List<TaskNode>
- getDependencies(String nodeId): List<String>
- getEstimatedCompletionTime(): long
- isReady(): boolean



---

## 第 59 块：TaskNode

### 类介绍
【设计动机】任务节点

### 包路径
lyclaw-engine → lyjew.com.lyclaw.task

### 类型
类（值对象）
### 方法签名
- 构造器(nodeId, type, description, requiredTools, dependencies, timeoutMs)

### 核心字段
nodeId/type/description/requiredTools/dependencies/timeoutMs

- 构造器(nodeId, type, description, requiredTools, dependencies, timeoutMs)

nodeId/type/description/requiredTools/dependencies/timeoutMs



---

## 第 60 块：TaskResult

### 类介绍
【设计动机】任务执行结果

### 包路径
lyclaw-engine → lyjew.com.lyclaw.task

### 类型
类（值对象）
### 方法签名
- 构造器(nodeId, success, output, error, elapsedMs, tokenUsage)

### 核心字段
nodeId/success/output/error/elapsedMs/tokenUsage

- 构造器(nodeId, success, output, error, elapsedMs, tokenUsage)

nodeId/success/output/error/elapsedMs/tokenUsage



---

## 第 61 块：TaskLedger

### 类介绍
【设计动机】任务账本

### 包路径
lyclaw-engine → lyjew.com.lyclaw.task

### 类型
接口
### 方法签名
- addRecord(TaskRecord): void
- getRecords(String taskId): List<TaskRecord>
- getLatestRecord(String taskId): Optional<TaskRecord>
- getAllTasks(): List<TaskRecord>

- addRecord(TaskRecord): void
- getRecords(String taskId): List<TaskRecord>
- getLatestRecord(String taskId): Optional<TaskRecord>
- getAllTasks(): List<TaskRecord>



---

## 第 62 块：TaskRecord

### 类介绍
【设计动机】任务记录

### 包路径
lyclaw-engine → lyjew.com.lyclaw.task

### 类型
类（值对象）
### 方法签名
- 构造器(taskId, nodeId, status, result, error, startedAt, completedAt)

### 核心字段
taskId/nodeId/status/result/error/startedAt/completedAt

- 构造器(taskId, nodeId, status, result, error, startedAt, completedAt)

taskId/nodeId/status/result/error/startedAt/completedAt



---


---

# 第六部分：空对象模式实现

> **设计意图**：提供零行为的默认实现，让各组件可安全注入 null-safe 依赖。实现核心接口但不依赖任何业务实现。

## 实现文件清单

| 序号 | 文件 | 包 | 类型 | 说明 |
|------|------|-----|------|------|
| 60 | NullEventBus.java | event.impl | 类 | EventBus 空对象实现 |
| 61 | NullMemoryManager.java | memory.impl | 类 | MemoryManager 空对象实现 |
| 62 | NullSecurityManager.java | security.impl | 类 | SecurityManager 空对象实现 |
| 63 | NullContentFilter.java | filter.impl | 类 | ContentFilter 空对象实现 |

## 第 63 块：NullEventBus

### 类介绍
【设计动机】EventBus 空对象实现

### 包路径
lyclaw-engine → lyjew.com.lyclaw.event.impl

### 类型
类
### 方法签名
- publish(Event): void — 空操作
- subscribe(Class<T>, Consumer<T>): void — 空操作
- unsubscribe(Class<T>, Consumer<T>): void — 空操作
- clear(): void — 空操作

- publish(Event): void — 空操作
- subscribe(Class<T>, Consumer<T>): void — 空操作
- unsubscribe(Class<T>, Consumer<T>): void — 空操作
- clear(): void — 空操作



---

## 第 64 块：NullMemoryManager

### 类介绍
【设计动机】MemoryManager 空对象实现

### 包路径
lyclaw-engine → lyjew.com.lyclaw.memory.impl

### 类型
类
### 方法签名
- read(): MemoryContent — 返回空 MemoryContent
- append(String): void — 空操作
- rewrite(String): void — 空操作
- search(String): List<MemoryContent> — 返回空列表

- read(): MemoryContent — 返回空 MemoryContent
- append(String): void — 空操作
- rewrite(String): void — 空操作
- search(String): List<MemoryContent> — 返回空列表



---

## 第 65 块：NullSecurityManager

### 类介绍
【设计动机】SecurityManager 空对象实现

### 包路径
lyclaw-engine → lyjew.com.lyclaw.security.impl

### 类型
类
### 方法签名
- approve(ChatContext, String): ApprovalResult — 返回 granted(NONE)
- revoke(String): void — 空操作
- checkPermission(String, String): boolean — 始终返回 true

- approve(ChatContext, String): ApprovalResult — 返回 granted(NONE)
- revoke(String): void — 空操作
- checkPermission(String, String): boolean — 始终返回 true



---

## 第 66 块：NullContentFilter

### 类介绍
【设计动机】ContentFilter 空对象实现

### 包路径
lyclaw-engine → lyjew.com.lyclaw.filter.impl

### 类型
类
### 方法签名
- filter(String content, ChatContext): FilterResult — 返回 pass(content)
- getFilterName(): String — 返回 "NullContentFilter"

- filter(String content, ChatContext): FilterResult — 返回 pass(content)
- getFilterName(): String — 返回 "NullContentFilter"



---


---

# 第七部分：对话上下文（依赖核心接口）

> **设计意图**：贯穿整个管道的数据载体，持有几乎所有接口的引用。放在接口之后、具体实现之前 — 作为接口定义和实现代码之间的桥梁。

## 实现文件清单

| 序号 | 文件 | 包 | 类型 | 说明 |
|------|------|-----|------|------|
| 64 | ChatContext.java | context | 类 | 对话上下文（贯穿整个管道的数据载体） |

## 第 67 块：ChatContext

### 类介绍
【设计动机】对话上下文（贯穿整个管道的数据载体）

### 包路径
lyclaw-engine → lyjew.com.lyclaw.context

### 类型
类
### 方法签名
- 构造器(ChatRequest, Session, MemoryContent, List<ToolDefinition>, InterceptorChain, ModelProvider)
- getRequest(): ChatRequest
- getSession(): Session — 当前会话
- setSession(Session): void — 更新会话
- getMemory(): MemoryContent — 长期记忆
- getMessages(): List<Message> — 消息列表
- getToolDefinitions(): List<ToolDefinition>
- getModelProvider(): ModelProvider
- setResult(ChatResult): void — 设置处理结果
- getResult(): ChatResult
- getTracing(): TraceContext
- setAttribute(String, Object): void — 设置扩展属性
- getAttribute(String): Object — 获取扩展属性

### 核心作用
ChatContext 是整个管道的唯一数据载体。包含：原始请求、会话信息、记忆、消息列表、工具列表、模型提供商、拦截器链、追踪上下文。

- 构造器(ChatRequest, Session, MemoryContent, List<ToolDefinition>, InterceptorChain, ModelProvider)
- getRequest(): ChatRequest
- getSession(): Session — 当前会话
- setSession(Session): void — 更新会话
- getMemory(): MemoryContent — 长期记忆
- getMessages(): List<Message> — 消息列表
- getToolDefinitions(): List<ToolDefinition>
- getModelProvider(): ModelProvider
- setResult(ChatResult): void — 设置处理结果
- getResult(): ChatResult
- getTracing(): TraceContext
- setAttribute(String, Object): void — 设置扩展属性
- getAttribute(String): Object — 获取扩展属性

ChatContext 是整个管道的唯一数据载体。包含：原始请求、会话信息、记忆、消息列表、工具列表、模型提供商、拦截器链、追踪上下文。



---


---

# 第八部分：ModelProvider 防腐层

> **设计意图**：engine 层和 adapter 层之间的防腐层接口 — engine 通过它获取适配器，不直接依赖 ModelAdapterFactory。

## 实现文件清单

| 序号 | 文件 | 包 | 类型 | 说明 |
|------|------|-----|------|------|
| 65 | ModelProvider.java | provider | 接口 | Engine↔Adapter 防腐层 |

## 第 68 块：ModelProvider

### 类介绍
【设计动机】Engine↔Adapter 防腐层

### 包路径
lyclaw-engine → lyjew.com.lyclaw.provider

### 类型
接口
### 方法签名
- getAdapter(String provider): ModelAdapter — 按厂商名获取适配器
  - ModelAdapter 来源：lyjew.com.lyclaw.adapter.ModelAdapter
- getDefaultProvider(): String — 获取默认厂商名
- getConfiguredAdapter(): ModelAdapter — 获取已配置的默认适配器
- listProviders(): Set<String> — 列出所有可用厂商
- refresh(): void — 刷新适配器列表

### 防腐层作用
engine 层不直接依赖 adapter 模块的 ModelAdapterFactory。通过这个接口隔离。

- getAdapter(String provider): ModelAdapter — 按厂商名获取适配器
  - ModelAdapter 来源：lyjew.com.lyclaw.adapter.ModelAdapter
- getDefaultProvider(): String — 获取默认厂商名
- getConfiguredAdapter(): ModelAdapter — 获取已配置的默认适配器
- listProviders(): Set<String> — 列出所有可用厂商
- refresh(): void — 刷新适配器列表

engine 层不直接依赖 adapter 模块的 ModelAdapterFactory。通过这个接口隔离。



---


---

# 第九部分：Pipeline 和 Engine 实现

> **设计意图**：引擎层的核心编排逻辑，组装前面定义的所有接口和策略。依赖前置部分的全部接口和空对象，是最上层的实现骨架。

## 实现文件清单

| 序号 | 文件 | 包 | 类型 | 说明 |
|------|------|-----|------|------|
| 66 | PipelineBuilder.java | pipeline.impl | 类 | Pipeline 构建器 |
| 67 | ContextBuildStage.java | pipeline.impl | 类 | 上下文构建阶段 |
| 68 | InterceptorStage.java | pipeline.impl | 类 | 拦截器执行阶段 |
| 69 | ToolCallLoopStage.java | pipeline.impl | 类 | 模型调用+工具执行循环阶段 |
| 70 | MetricsStage.java | pipeline.impl | 类 | 指标采集阶段 |
| 71 | ResponseBuildStage.java | pipeline.impl | 类 | 响应构建阶段 |
| 72 | DefaultEngine.java | engine.impl | 类 | 默认引擎实现 |
| 73 | EngineSelector.java | engine.impl | 类 | 引擎选择器 |

## 第 69 块：PipelineBuilder

### 类介绍
【设计动机】Pipeline 构建器

### 包路径
lyclaw-engine → lyjew.com.lyclaw.pipeline.impl

### 类型
类
### 方法签名
- addStage(PipelineStage): PipelineBuilder — 添加阶段（链式调用）
- removeStage(String stageName): PipelineBuilder — 按名称移除阶段
- build(): Pipeline — 构建 Pipeline 实例

- addStage(PipelineStage): PipelineBuilder — 添加阶段（链式调用）
- removeStage(String stageName): PipelineBuilder — 按名称移除阶段
- build(): Pipeline — 构建 Pipeline 实例



---

## 第 70 块：ContextBuildStage

### 类介绍
【设计动机】上下文构建阶段

### 包路径
lyclaw-engine → lyjew.com.lyclaw.pipeline.impl

### 类型
类
### 方法签名
- process(ChatContext, Chain): void
- getOrder(): int — 第一阶段
- getStageName(): String — "ContextBuild"

- process(ChatContext, Chain): void
- getOrder(): int — 第一阶段
- getStageName(): String — "ContextBuild"



---

## 第 71 块：InterceptorStage

### 类介绍
【设计动机】拦截器执行阶段

### 包路径
lyclaw-engine → lyjew.com.lyclaw.pipeline.impl

### 类型
类
### 方法签名
- process(ChatContext, Chain): void
- getOrder(): int — 第二阶段
- getStageName(): String — "Interceptor"

- process(ChatContext, Chain): void
- getOrder(): int — 第二阶段
- getStageName(): String — "Interceptor"



---

## 第 72 块：ToolCallLoopStage

### 类介绍
【设计动机】模型调用+工具执行循环阶段

### 包路径
lyclaw-engine → lyjew.com.lyclaw.pipeline.impl

### 类型
类
### 方法签名
- process(ChatContext, Chain): void
- getOrder(): int — 第三阶段
- getStageName(): String — "ToolCallLoop"

- process(ChatContext, Chain): void
- getOrder(): int — 第三阶段
- getStageName(): String — "ToolCallLoop"



---

## 第 73 块：MetricsStage

### 类介绍
【设计动机】指标采集阶段

### 包路径
lyclaw-engine → lyjew.com.lyclaw.pipeline.impl

### 类型
类
### 方法签名
- process(ChatContext, Chain): void
- getOrder(): int — 第四阶段
- getStageName(): String — "Metrics"

- process(ChatContext, Chain): void
- getOrder(): int — 第四阶段
- getStageName(): String — "Metrics"



---

## 第 74 块：ResponseBuildStage

### 类介绍
【设计动机】响应构建阶段

### 包路径
lyclaw-engine → lyjew.com.lyclaw.pipeline.impl

### 类型
类
### 方法签名
- process(ChatContext, Chain): void
- getOrder(): int — 第五阶段
- getStageName(): String — "ResponseBuild"

- process(ChatContext, Chain): void
- getOrder(): int — 第五阶段
- getStageName(): String — "ResponseBuild"



---

## 第 75 块：DefaultEngine

### 类介绍
【设计动机】默认引擎实现

### 包路径
lyclaw-engine → lyjew.com.lyclaw.engine.impl

### 类型
类
### 方法签名
- getName(): String — 返回 "default"
- supports(ChatRequest): boolean — 始终返回 true（兜底引擎）
- execute(ChatRequest): Flux<String> — 构建 Pipeline 并执行
- getMetadata(): EngineMetadata

- getName(): String — 返回 "default"
- supports(ChatRequest): boolean — 始终返回 true（兜底引擎）
- execute(ChatRequest): Flux<String> — 构建 Pipeline 并执行
- getMetadata(): EngineMetadata



---

## 第 76 块：EngineSelector

### 类介绍
【设计动机】引擎选择器

### 包路径
lyclaw-engine → lyjew.com.lyclaw.engine.impl

### 类型
类
### 方法签名
- select(ChatRequest): Engine — 选择合适的 Engine
- register(Engine): void — 注册 Engine
- getEngines(): List<Engine> — 获取所有已注册 Engine

- select(ChatRequest): Engine — 选择合适的 Engine
- register(Engine): void — 注册 Engine
- getEngines(): List<Engine> — 获取所有已注册 Engine



---


---

# 第十部分：Tool / Skill / Memory 具体实现

> **设计意图**：具体业务实现依赖接口和基础设施。放在最后让读者了解接口长什么样后再看怎么实现。可并行开发、独立测试。

## 实现文件清单

| 序号 | 文件 | 包 | 类型 | 说明 |
|------|------|-----|------|------|
| 74 | DefaultToolRegistry.java | tool.impl | 类 | 默认工具注册表 |
| 75 | DefaultToolCallPolicy.java | tool.impl | 类 | 默认工具调用策略（10轮上限） |
| 76 | ToolCallLoop.java | tool.impl | 类 | 工具调用循环（模板方法） |
| 77 | WebSearchTool.java | tool.impl | 类 | 网络搜索工具 |
| 78 | CalculatorTool.java | tool.impl | 类 | 数学计算工具 |
| 79 | CurrentTimeTool.java | tool.impl | 类 | 当前时间工具 |
| 80 | McpToolAdapter.java | tool.impl | 类 | MCP 协议适配器 |
| 81 | DefaultSkillRegistry.java | skill.impl | 类 | 默认技能注册表 |
| 82 | SkillGraphImpl.java | skill.impl | 类 | 技能依赖图实现 |
| 83 | ToolToSkillAdapter.java | skill.impl.adapters | 类 | Tool→Skill 适配器 |
| 84 | FileMemoryManager.java | memory.impl | 类 | 基于文件的记忆管理器 |
| 85 | ManualMemoryStrategy.java | memory.impl | 类 | 手动记忆策略（始终注入） |
| 86 | InMemoryEventBus.java | event.impl | 类 | 内存事件总线 |
| 87 | TokenConsumedEvent.java | event.impl | 类 | Token 消耗事件 |
| 88 | ToolCalledEvent.java | event.impl | 类 | 工具调用事件 |
| 89 | AgentStateChangedEvent.java | event.impl | 类 | Agent 状态变更事件 |
| 90 | StarAgentChannel.java | agent.impl | 类 | 星型拓扑 Agent 通信频道 |
| 91 | DefaultErrorPolicy.java | error.impl | 类 | 默认错误处理策略 |
| 92 | DefaultSecurityManager.java | security.impl | 类 | 默认安全管理器 |
| 93 | DefaultTaskPlanner.java | task.impl | 类 | 默认任务规划器 |
| 94 | DefaultTaskLedger.java | task.impl | 类 | 默认任务账本 |
| 95 | DefaultSessionTransaction.java | transaction.impl | 类 | 默认事务管理器 |

## 第 77 块：DefaultToolRegistry

### 类介绍
【设计动机】默认工具注册表

### 包路径
lyclaw-engine → lyjew.com.lyclaw.tool.impl

### 类型
类
### 方法签名
- register(Tool): void — ConcurrentHashMap 存储
- get(String): Tool — 按名查找
- getAllDefinitions(): List<ToolDefinition>
- execute(ToolCall): ToolResult — 查找后执行

- register(Tool): void — ConcurrentHashMap 存储
- get(String): Tool — 按名查找
- getAllDefinitions(): List<ToolDefinition>
- execute(ToolCall): ToolResult — 查找后执行



---

## 第 78 块：DefaultToolCallPolicy

### 类介绍
【设计动机】默认工具调用策略（10轮上限）

### 包路径
lyclaw-engine → lyjew.com.lyclaw.tool.impl

### 类型
类
### 方法签名
- getMaxRounds(): int — 返回 10
- shouldContinue(ChatContext, int): boolean — currentRound < 10
- handleToolError(ToolCall, Exception, ChatContext): ToolErrorAction — 返回 ABORT
- shouldRetryOnError(ToolCall, Exception, int retryCount): boolean — retryCount < 3

- getMaxRounds(): int — 返回 10
- shouldContinue(ChatContext, int): boolean — currentRound < 10
- handleToolError(ToolCall, Exception, ChatContext): ToolErrorAction — 返回 ABORT
- shouldRetryOnError(ToolCall, Exception, int retryCount): boolean — retryCount < 3



---

## 第 79 块：ToolCallLoop

### 类介绍
【设计动机】工具调用循环（模板方法）

### 包路径
lyclaw-engine → lyjew.com.lyclaw.tool.impl

### 类型
类
### 方法签名
- execute(ChatContext, ToolRegistry, ModelProvider): ChatResult — 模板方法
- beforeLoop(ChatContext): void — 钩子
- afterLoop(ChatContext, ChatResult): void — 钩子
- handleModelResponse(ModelResponse): boolean — 判断是否继续循环

- execute(ChatContext, ToolRegistry, ModelProvider): ChatResult — 模板方法
- beforeLoop(ChatContext): void — 钩子
- afterLoop(ChatContext, ChatResult): void — 钩子
- handleModelResponse(ModelResponse): boolean — 判断是否继续循环



---

## 第 80 块：WebSearchTool

### 类介绍
【设计动机】网络搜索工具

### 包路径
lyclaw-engine → lyjew.com.lyclaw.tool.impl

### 类型
类
### 方法签名
- getName(): String — "web_search"
- execute(ToolCall, ChatContext): ToolResult
- getDefinition(): ToolDefinition

- getName(): String — "web_search"
- execute(ToolCall, ChatContext): ToolResult
- getDefinition(): ToolDefinition



---

## 第 81 块：CalculatorTool

### 类介绍
【设计动机】数学计算工具

### 包路径
lyclaw-engine → lyjew.com.lyclaw.tool.impl

### 类型
类
### 方法签名
- getName(): String — "calculator"
- execute(ToolCall, ChatContext): ToolResult
- getDefinition(): ToolDefinition

- getName(): String — "calculator"
- execute(ToolCall, ChatContext): ToolResult
- getDefinition(): ToolDefinition



---

## 第 82 块：CurrentTimeTool

### 类介绍
【设计动机】当前时间工具

### 包路径
lyclaw-engine → lyjew.com.lyclaw.tool.impl

### 类型
类
### 方法签名
- getName(): String — "current_time"
- execute(ToolCall, ChatContext): ToolResult
- getDefinition(): ToolDefinition

- getName(): String — "current_time"
- execute(ToolCall, ChatContext): ToolResult
- getDefinition(): ToolDefinition



---

## 第 83 块：McpToolAdapter

### 类介绍
【设计动机】MCP 协议适配器

### 包路径
lyclaw-engine → lyjew.com.lyclaw.tool.impl

### 类型
类
### 方法签名
- getName(): String — MCP 工具名
- execute(ToolCall, ChatContext): ToolResult
- getDefinition(): ToolDefinition

- getName(): String — MCP 工具名
- execute(ToolCall, ChatContext): ToolResult
- getDefinition(): ToolDefinition



---

## 第 84 块：DefaultSkillRegistry

### 类介绍
【设计动机】默认技能注册表

### 包路径
lyclaw-engine → lyjew.com.lyclaw.skill.impl

### 类型
类
### 方法签名
- register(Skill): void
- get(String): Skill
- getAll(): List<Skill>
- getDependencies(String): List<String>
- resolveExecutionOrder(): List<String>

- register(Skill): void
- get(String): Skill
- getAll(): List<Skill>
- getDependencies(String): List<String>
- resolveExecutionOrder(): List<String>



---

## 第 85 块：SkillGraphImpl

### 类介绍
【设计动机】技能依赖图实现

### 包路径
lyclaw-engine → lyjew.com.lyclaw.skill.impl

### 类型
类
### 方法签名
- addDependency(String, String): void
- removeDependency(String, String): void
- getDependencies(String): List<String>
- getDependents(String): List<String>
- getExecutionOrder(): List<String> — 拓扑排序
- hasCycle(): boolean

- addDependency(String, String): void
- removeDependency(String, String): void
- getDependencies(String): List<String>
- getDependents(String): List<String>
- getExecutionOrder(): List<String> — 拓扑排序
- hasCycle(): boolean



---

## 第 86 块：ToolToSkillAdapter

### 类介绍
【设计动机】Tool→Skill 适配器

### 包路径
lyclaw-engine → lyjew.com.lyclaw.skill.impl.adapters

### 类型
类
### 方法签名
- 构造器(Tool) — 将 Tool 包装为 Skill
- getSkillId(): String — tool.getName()
- execute(ChatContext): CompletableFuture<SkillResult>

- 构造器(Tool) — 将 Tool 包装为 Skill
- getSkillId(): String — tool.getName()
- execute(ChatContext): CompletableFuture<SkillResult>



---

## 第 87 块：FileMemoryManager

### 类介绍
【设计动机】基于文件的记忆管理器

### 包路径
lyclaw-engine → lyjew.com.lyclaw.memory.impl

### 类型
类
### 方法签名
- 构造器(MemoryStorage)
- read(): MemoryContent — 从 MemoryStorage 读取
- append(String): void — 追加内容后写入
- rewrite(String): void — 重写写入
- search(String): List<MemoryContent>

- 构造器(MemoryStorage)
- read(): MemoryContent — 从 MemoryStorage 读取
- append(String): void — 追加内容后写入
- rewrite(String): void — 重写写入
- search(String): List<MemoryContent>



---

## 第 88 块：ManualMemoryStrategy

### 类介绍
【设计动机】手动记忆策略（始终注入）

### 包路径
lyclaw-engine → lyjew.com.lyclaw.memory.impl

### 类型
类
### 方法签名
- formatForContext(MemoryContent): String — 格式化为 <memory> 标签
- shouldIncludeInContext(MemoryContent, ChatContext): boolean — 始终返回 true
- getPriority(): int — 返回 0

- formatForContext(MemoryContent): String — 格式化为 <memory> 标签
- shouldIncludeInContext(MemoryContent, ChatContext): boolean — 始终返回 true
- getPriority(): int — 返回 0



---

## 第 89 块：InMemoryEventBus

### 类介绍
【设计动机】内存事件总线

### 包路径
lyclaw-engine → lyjew.com.lyclaw.event.impl

### 类型
类
### 方法签名
- publish(Event): void — 遍历订阅者执行
- subscribe(Class<T>, Consumer<T>): void — CopyOnWriteArrayList 存储
- unsubscribe(Class<T>, Consumer<T>): void
- clear(): void

- publish(Event): void — 遍历订阅者执行
- subscribe(Class<T>, Consumer<T>): void — CopyOnWriteArrayList 存储
- unsubscribe(Class<T>, Consumer<T>): void
- clear(): void



---

## 第 90 块：TokenConsumedEvent

### 类介绍
【设计动机】Token 消耗事件

### 包路径
lyclaw-engine → lyjew.com.lyclaw.event.impl

### 类型
类
### 方法签名
- 构造器(source, provider, model, promptTokens, completionTokens, totalTokens)
- 继承 Event(source, "TOKEN_CONSUMED")
- 所有字段 getter

- 构造器(source, provider, model, promptTokens, completionTokens, totalTokens)
- 继承 Event(source, "TOKEN_CONSUMED")
- 所有字段 getter



---

## 第 91 块：ToolCalledEvent

### 类介绍
【设计动机】工具调用事件

### 包路径
lyclaw-engine → lyjew.com.lyclaw.event.impl

### 类型
类
### 方法签名
- 构造器(source, toolName, args, result, elapsedMs)
- 继承 Event(source, "TOOL_CALLED")
- 所有字段 getter

- 构造器(source, toolName, args, result, elapsedMs)
- 继承 Event(source, "TOOL_CALLED")
- 所有字段 getter



---

## 第 92 块：AgentStateChangedEvent

### 类介绍
【设计动机】Agent 状态变更事件

### 包路径
lyclaw-engine → lyjew.com.lyclaw.event.impl

### 类型
类
### 方法签名
- 构造器(source, agentId, oldState, newState)
- 继承 Event(source, "AGENT_STATE_CHANGED")
- 所有字段 getter

- 构造器(source, agentId, oldState, newState)
- 继承 Event(source, "AGENT_STATE_CHANGED")
- 所有字段 getter



---

## 第 93 块：StarAgentChannel

### 类介绍
【设计动机】星型拓扑 Agent 通信频道

### 包路径
lyclaw-engine → lyjew.com.lyclaw.agent.impl

### 类型
类
### 方法签名
- send(String, String, AgentMessage): void
- broadcast(String, AgentMessage): void
- subscribe(String, Consumer<AgentMessage>): void

- send(String, String, AgentMessage): void
- broadcast(String, AgentMessage): void
- subscribe(String, Consumer<AgentMessage>): void



---

## 第 94 块：DefaultErrorPolicy

### 类介绍
【设计动机】默认错误处理策略

### 包路径
lyclaw-engine → lyjew.com.lyclaw.error.impl

### 类型
类
### 方法签名
- onModelError(ModelException, ChatContext, ChatRequest): ToolErrorAction — 默认 RETRY（最多3次）
- onToolError(ToolCall, Exception, int retryCount): ToolErrorAction — 默认 RETRY（最多2次）
- getRetryConfig(): RetryConfig

- onModelError(ModelException, ChatContext, ChatRequest): ToolErrorAction — 默认 RETRY（最多3次）
- onToolError(ToolCall, Exception, int retryCount): ToolErrorAction — 默认 RETRY（最多2次）
- getRetryConfig(): RetryConfig



---

## 第 95 块：DefaultSecurityManager

### 类介绍
【设计动机】默认安全管理器

### 包路径
lyclaw-engine → lyjew.com.lyclaw.security.impl

### 类型
类
### 方法签名
- approve(ChatContext, String): ApprovalResult — 返回 granted(NONE)
- revoke(String): void — 空操作
- checkPermission(String, String): boolean — 返回 true

- approve(ChatContext, String): ApprovalResult — 返回 granted(NONE)
- revoke(String): void — 空操作
- checkPermission(String, String): boolean — 返回 true



---

## 第 96 块：DefaultTaskPlanner

### 类介绍
【设计动机】默认任务规划器

### 包路径
lyclaw-engine → lyjew.com.lyclaw.task.impl

### 类型
类
### 方法签名
- plan(ChatContext): TaskPlan — 贪心策略：按顺序拆解为 TaskNode
- optimize(AgentResult): TaskPlan — 根据结果调整后续节点

- plan(ChatContext): TaskPlan — 贪心策略：按顺序拆解为 TaskNode
- optimize(AgentResult): TaskPlan — 根据结果调整后续节点



---

## 第 97 块：DefaultTaskLedger

### 类介绍
【设计动机】默认任务账本

### 包路径
lyclaw-engine → lyjew.com.lyclaw.task.impl

### 类型
类
### 方法签名
- addRecord(TaskRecord): void
- getRecords(String): List<TaskRecord>
- getLatestRecord(String): Optional<TaskRecord>
- getAllTasks(): List<TaskRecord>

- addRecord(TaskRecord): void
- getRecords(String): List<TaskRecord>
- getLatestRecord(String): Optional<TaskRecord>
- getAllTasks(): List<TaskRecord>



---

## 第 98 块：DefaultSessionTransaction

### 类介绍
【设计动机】默认事务管理器

### 包路径
lyclaw-engine → lyjew.com.lyclaw.transaction.impl

### 类型
类
### 方法签名
- begin(String, String): void — 创建快照
- commit(String): boolean — 应用更新
- rollback(String): boolean — 恢复到快照
- getStatus(String): String
- createSnapshot(String, ChatContext): SessionUpdate

- begin(String, String): void — 创建快照
- commit(String): boolean — 应用更新
- rollback(String): boolean — 恢复到快照
- getStatus(String): String
- createSnapshot(String, ChatContext): SessionUpdate



---


---

# 第十一部分：配置与自动装配

> **设计意图**：配置类和自动装配类依赖所有其他组件完成装配。放在最后让读者理解所有组件后才看到如何拼装成一个整体模块。

## 实现文件清单

| 序号 | 文件 | 包 | 类型 | 说明 |
|------|------|-----|------|------|
| 96 | EngineProperties.java | config | 类 | 引擎配置属性类 |
| 97 | EngineAutoConfiguration.java | config | 类 | 引擎自动装配 |

## 第 99 块：EngineProperties

### 类介绍
【设计动机】引擎配置属性类

### 包路径
lyclaw-engine → lyjew.com.lyclaw.config

### 类型
类
### 核心配置项
- dataDir: String — 数据目录（默认 ./LyClaw）
- defaultProvider: String — 默认模型厂商
- pipeline.timeout: long — 管道超时（毫秒）
- pipeline.maxToolRounds: int — 最大工具调用轮次
- enabled: boolean — 引擎是否启用

- dataDir: String — 数据目录（默认 ./LyClaw）
- defaultProvider: String — 默认模型厂商
- pipeline.timeout: long — 管道超时（毫秒）
- pipeline.maxToolRounds: int — 最大工具调用轮次
- enabled: boolean — 引擎是否启用



---

## 第 100 块：EngineAutoConfiguration

### 类介绍
【设计动机】引擎自动装配

### 包路径
lyclaw-engine → lyjew.com.lyclaw.config

### 类型
类
### 职责
Spring Boot 自动装配类，自动扫描并注入 engine 层所有需要的 Bean：
- EngineProperties
- EngineSelector / DefaultEngine
- PipelineBuilder + PipelineStage 列表
- ToolRegistry + 内置工具
- DefaultToolCallPolicy / ToolCallLoop
- ContextBuilder / FileMemoryManager / InMemoryEventBus
- DefaultErrorPolicy / DefaultSecurityManager
- ModelProvider（注入 adapter 层的 ModelAdapterFactory）
- 等等

Spring Boot 自动装配类，自动扫描并注入 engine 层所有需要的 Bean：
- EngineProperties
- EngineSelector / DefaultEngine
- PipelineBuilder + PipelineStage 列表
- ToolRegistry + 内置工具
- DefaultToolCallPolicy / ToolCallLoop
- ContextBuilder / FileMemoryManager / InMemoryEventBus
- DefaultErrorPolicy / DefaultSecurityManager
- ModelProvider（注入 adapter 层的 ModelAdapterFactory）
- 等等



---

