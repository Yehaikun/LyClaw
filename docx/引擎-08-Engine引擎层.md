# 引擎-08-Engine 引擎层

**Metadata**
- Date: 2026-04-29
- 所属模块: lyclaw-engine
- 包路径: `lyjew.com.lyclaw.engine`
- 依赖: Pipeline 层, MemoryManager, ErrorPolicy, EventBus, ModelProvider
- 并行前提: 等待 Pipeline 层及以下各层实现完成后才可实现

---

## 核心职责

AI 引擎的顶层抽象和选择。EngineSelector 作为入口门面，Engine 接口定义执行协议，DefaultEngine 是标准对话实现。

---

## 需要实现的类清单

### 1. Engine — 引擎接口

**文件**: `engine/Engine.java`
**包**: `lyjew.com.lyclaw.engine`

| 元素 | 说明 |
|------|------|
| 类型 | 接口 |
| 设计模式 | 策略模式 |

**方法**:
| 方法签名 | 返回值 | 说明 |
|----------|--------|------|
| String getName() | String | 引擎名称，如 "default"、"reasoning" |
| boolean supports(ChatRequest request) | boolean | 判断是否支持处理该请求。引擎自描述匹配条件 |
| Flux\<String\> execute(ChatRequest request) | Flux\<String\> | 执行对话，返回流式响应（Flux 支持逐 token 输出） |
| EngineMetadata getMetadata() | EngineMetadata | 返回引擎元信息 |

---

### 2. EngineMetadata — 引擎元信息

**文件**: `engine/EngineMetadata.java`
**包**: `lyjew.com.lyclaw.engine`

| 属性 | 类型 | 说明 |
|------|------|------|
| name | String | 引擎名称 |
| description | String | 引擎描述 |
| version | String | 版本号 |
| capabilities | List\<String\> | 能力列表，如 ["chat", "tool_calls", "streaming"] |
| createdAt | String | 创建日期 |

| 方法 | 返回值 | 说明 |
|------|--------|------|
| 所有属性 Getter/Setter | - | - |
| EngineMetadata(String name, String description, String version) | - | 构造器 |

---

### 3. EngineSelector — 引擎选择器

**文件**: `engine/EngineSelector.java`
**包**: `lyjew.com.lyclaw.engine`

| 元素 | 说明 |
|------|------|
| 类型 | 类，@Component |
| 设计模式 | 策略模式 + 注册表模式 |

**属性**:
| 名称 | 类型 | 说明 |
|------|------|------|
| engines | List\<Engine\> | 所有已注册的 Engine 实现（Spring 自动注入） |

**方法**:
| 方法签名 | 返回值 | 说明 |
|----------|--------|------|
| Engine select(ChatRequest request) | Engine | 遍历 engines，调用 supports(request)，返回第一个匹配的。无匹配则抛 NoEngineFoundException |
| Flux\<String\> execute(ChatRequest request) | Flux\<String\> | select(request) + engine.execute(request) 的便捷方法 |
| List\<EngineMetadata\> listEngines() | List\<EngineMetadata\> | 返回所有可用引擎的元信息列表 |

---

### 4. NoEngineFoundException — 无匹配引擎异常

**文件**: `engine/NoEngineFoundException.java`
**包**: `lyjew.com.lyclaw.engine`

| 属性 | 类型 | 说明 |
|------|------|------|
| requestId | String | 请求 ID |
| sessionId | String | 会话 ID |

| 方法 | 返回值 | 说明 |
|------|--------|------|
| NoEngineFoundException(String requestId, String sessionId) | - | 构造器 |

---

### 5. DefaultEngine — 默认引擎实现

**文件**: `engine/impl/DefaultEngine.java`
**包**: `lyjew.com.lyclaw.engine.impl`
**实现**: Engine

| 元素 | 说明 |
|------|------|
| 类型 | 类，@Component |
| 使用 | Pipeline 模式编排对话流程 |

**属性**:
| 名称 | 类型 | 说明 |
|------|------|------|
| pipeline | Pipeline | 通过 PipelineBuilder 构建的管道（懒加载） |
| memoryManager | MemoryManager | 记忆管理器 |
| errorPolicy | ErrorPolicy | 错误处理策略 |
| eventBus | EventBus | 事件总线 |
| sessionStorage | SessionStorage | 会话持久化（来自 lyclaw-storage） |
| contextBuilders | List\<ContextBuilder\> | 上下文构建器（注入） |
| interceptorChain | InterceptorChain | 拦截器链（注入） |
| toolCallLoop | ToolCallLoop | 工具调用循环（注入） |

**方法**:
| 方法签名 | 返回值 | 说明 |
|----------|--------|------|
| getName() | String | 返回 "default" |
| supports(ChatRequest request) | boolean | 任何 ChatRequest 都支持。根据 request 的特征判断：<br>- 如果没有特殊标记（如推理模式标志），返回 true |
| Flux\<String\> execute(ChatRequest request) | Flux\<String\> | 核心执行流程：<br>1. 获取或构建 Pipeline<br>2. 调用 pipeline.execute(request) 获得 ChatResult<br>3. 更新 session（追加用户消息和 AI 回复到 Session）<br>4. 持久化 session 到 SessionStorage<br>5. 触发 MemoryManager.remember()<br>6. 发布对话完成事件<br>7. 将 ChatResult.message 转为 Flux\<String\> 返回 |
| EngineMetadata getMetadata() | EngineMetadata | 返回名称/描述/版本/能力列表 |
| Pipeline buildPipeline() | Pipeline | 私有方法：使用 PipelineBuilder 构建默认管道编排 |

**execute() 的详细流程**:
```
1. pipeline = buildPipeline()
2. ChatResult result = pipeline.execute(request)
3. session = loadSession(request.getSessionId())
4. session.addMessage(userMessage)
5. session.addMessage(aiResponse)
6. sessionStorage.save(session)
7. memoryManager.remember(session)
8. eventBus.publish(对话完成事件)
9. return Flux.just(result.getMessage())
```

**buildPipeline() 的编排**:
```
Pipeline.builder()
    .addStage(new ContextBuildStage(contextBuilders, memoryManager, toolRegistry))
    .addStage(new InterceptorStage(interceptorChain))
    .addStage(new ToolCallLoopStage(toolCallLoop))
    .addStage(new MetricsStage(eventBus))
    .addStage(new ResponseBuildStage(interceptorChain))
    .build();
```

---

## 实现顺序

1. EngineMetadata（值对象）
2. Engine 接口
3. NoEngineFoundException
4. EngineSelector
5. DefaultEngine（依赖所有下层组件）

## 校验清单

- [ ] Engine 接口含 getName、supports、execute、getMetadata
- [ ] EngineSelector 遍历引擎调用 supports() 匹配
- [ ] DefaultEngine.getName() 返回 "default"
- [ ] DefaultEngine.execute() 使用 Pipeline 执行完整流程
- [ ] execute() 结束后更新 session、持久化、触发记忆管理
- [ ] Flux\<String\> 支持流式输出
