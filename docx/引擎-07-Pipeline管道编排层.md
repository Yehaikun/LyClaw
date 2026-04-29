# 引擎-07-Pipeline 管道编排层

**Metadata**
- Date: 2026-04-29
- 所属模块: lyclaw-engine
- 包路径: `lyjew.com.lyclaw.pipeline`
- 依赖: ContextBuilder 层 (context 包)、Interceptor 层 (interceptor 包)、Tool 层 (tool 包)、EventBus 层 (event 包)、MemoryManager 层 (memory 包)
- 并行前提: 等待 Context/Interceptor/Tool/EventBus/Memory 层接口完成后方可实现

---

## 核心职责

将请求处理流程分解为多个独立的处理阶段（Stage），通过 PipelineBuilder 自由编排阶段顺序。

---

## 需要实现的类清单

### 1. Pipeline — 管道接口

**文件**: `pipeline/Pipeline.java`
**包**: `lyjew.com.lyclaw.pipeline`

| 元素 | 说明 |
|------|------|
| 类型 | 接口 |
| 设计模式 | 管道模式 |

**方法**:
| 方法签名 | 返回值 | 说明 |
|----------|--------|------|
| ChatResult execute(ChatRequest request) | ChatResult | 执行管道：创建 ChatContext → 顺序执行各 Stage → 返回 ChatResult |

---

### 2. PipelineStage — 管道阶段接口

**文件**: `pipeline/PipelineStage.java`
**包**: `lyjew.com.lyclaw.pipeline`

| 元素 | 说明 |
|------|------|
| 类型 | 接口 |
| 设计模式 | 管道模式 + 责任链模式 |

**方法**:
| 方法签名 | 返回值 | 说明 |
|----------|--------|------|
| String getName() | String | 阶段名称，用于日志和调试 |
| boolean supports(ChatContext context) | boolean | 判断该阶段是否适用于此上下文。某些阶段可条件执行 |
| void execute(ChatContext context, Chain chain) | void | 执行阶段逻辑。必须调用 chain.proceed(context) 传递到下一阶段。如果不调用则管道在此中断 |

---

### 3. Chain — 阶段链

**文件**: `pipeline/Chain.java`
**包**: `lyjew.com.lyclaw.pipeline`

| 元素 | 说明 |
|------|------|
| 类型 | 类 |
| 设计模式 | 责任链模式 |

**属性**:
| 名称 | 类型 | 说明 |
|------|------|------|
| stages | List\<PipelineStage\> | 固定顺序的阶段列表 |
| currentIndex | AtomicInteger | 当前执行到的阶段索引（线程安全） |

**方法**:
| 方法签名 | 返回值 | 说明 |
|----------|--------|------|
| Chain(List\<PipelineStage\> stages) | - | 构造器 |
| void proceed(ChatContext context) | void | 1. currentIndex 自增<br>2. 获取 stages.get(currentIndex)<br>3. 调用 stage.supports() → true 则 stage.execute(context, this)，false 则此阶段跳过，递归调用 proceed() |

---

### 4. PipelineImpl — 管道实现

**文件**: `pipeline/PipelineImpl.java`
**包**: `lyjew.com.lyclaw.pipeline`
**实现**: Pipeline

| 属性 | 类型 | 说明 |
|------|------|------|
| stages | List\<PipelineStage\> | 编排好的阶段列表 |
| name | String | 管道名称 |

**方法**:
| 方法签名 | 返回值 | 说明 |
|----------|--------|------|
| PipelineImpl(String name, List\<PipelineStage\> stages) | - | 构造器 |
| ChatResult execute(ChatRequest request) | ChatResult | 1. 创建 ChatContext(request, loadSession(request.getSessionId()))<br>2. 创建 Chain(stages)<br>3. 从第一个 Stage 开始 chain.proceed(context)<br>4. 管道执行完毕后，从 context 构建 ChatResult 返回 |

---

### 5. PipelineBuilder — 管道构建器

**文件**: `pipeline/PipelineBuilder.java`
**包**: `lyjew.com.lyclaw.pipeline`

| 元素 | 说明 |
|------|------|
| 类型 | 类 |
| 设计模式 | 建造者模式 |

**属性**:
| 名称 | 类型 | 说明 |
|------|------|------|
| stages | List\<PipelineStage\> | 积累的阶段列表 |

**方法**:
| 方法签名 | 返回值 | 说明 |
|----------|--------|------|
| PipelineBuilder() | - | 构造器，初始化空列表 |
| PipelineBuilder addStage(PipelineStage stage) | PipelineBuilder | 在末尾添加阶段，返回 builder 自身（链式） |
| PipelineBuilder addStageBefore(Class\<?\> beforeClass, PipelineStage newStage) | PipelineBuilder | 在指定阶段类之前插入 |
| PipelineBuilder addStageAfter(Class\<?\> afterClass, PipelineStage newStage) | PipelineBuilder | 在指定阶段类之后插入 |
| PipelineBuilder replaceStage(Class\<?\> oldClass, PipelineStage newStage) | PipelineBuilder | 替换指定类名的阶段 |
| PipelineBuilder removeStage(Class\<?\> stageClass) | PipelineBuilder | 移除指定类名的阶段 |
| Pipeline build() | Pipeline | 构建 Pipeline 实例（返回 PipelineImpl） |

---

### 6. ContextBuildStage — 上下文构建阶段

**文件**: `pipeline/stages/ContextBuildStage.java`
**包**: `lyjew.com.lyclaw.pipeline.stages`
**实现**: PipelineStage

| 属性 | 类型 | 说明 |
|------|------|------|
| contextBuilders | List\<ContextBuilder\> | 所有已注册的 ContextBuilder（Spring 注入） |
| memoryManager | MemoryManager | 记忆管理器 |
| toolRegistry | ToolRegistry | 工具注册表 |

**方法**:
| 方法签名 | 返回值 | 说明 |
|----------|--------|------|
| getName() | String | 返回 "context_build" |
| supports(context) | boolean | 始终返回 true |
| execute(context, chain) | void | 1. 从 memoryManager 加载记忆<br>2. 遍历 contextBuilders，调用 supports()，选第一个匹配的<br>3. 调用选中的 builder.build(context) 填充 context.messages<br>4. 注入 toolDefinitions（从 toolRegistry.getAllDefinitions()）<br>5. chain.proceed(context) |

---

### 7. InterceptorStage — 拦截器阶段

**文件**: `pipeline/stages/InterceptorStage.java`
**包**: `lyjew.com.lyclaw.pipeline.stages`
**实现**: PipelineStage

| 属性 | 类型 | 说明 |
|------|------|------|
| interceptorChain | InterceptorChain | 拦截器链 |

**方法**:
| 方法签名 | 返回值 | 说明 |
|----------|--------|------|
| getName() | String | 返回 "interceptor" |
| supports(context) | boolean | 始终返回 true |
| execute(context, chain) | void | 1. interceptorChain.doPreHandle(context)<br>2. 如抛异常则中断，不再 chain.proceed()<br>3. chain.proceed(context) |

---

### 8. ToolCallLoopStage — 工具调用循环阶段

**文件**: `pipeline/stages/ToolCallLoopStage.java`
**包**: `lyjew.com.lyclaw.pipeline.stages`
**实现**: PipelineStage

| 属性 | 类型 | 说明 |
|------|------|------|
| toolCallLoop | ToolCallLoop | 工具调用循环 |

**方法**:
| 方法签名 | 返回值 | 说明 |
|----------|--------|------|
| getName() | String | 返回 "tool_call_loop" |
| supports(context) | boolean | 始终返回 true |
| execute(context, chain) | void | 1. toolCallLoop.execute(context) 执行循环<br>2. 将最终 ModelResponse 存入 context.metadata<br>3. chain.proceed(context) |

---

### 9. MetricsStage — 指标采集阶段

**文件**: `pipeline/stages/MetricsStage.java`
**包**: `lyjew.com.lyclaw.pipeline.stages`
**实现**: PipelineStage

| 属性 | 类型 | 说明 |
|------|------|------|
| eventBus | EventBus | 事件总线 |

**方法**:
| 方法签名 | 返回值 | 说明 |
|----------|--------|------|
| getName() | String | 返回 "metrics" |
| supports(context) | boolean | 始终返回 true |
| execute(context, chain) | void | 1. 从 context 读取 token 统计<br>2. 发布 TokenConsumedEvent<br>3. chain.proceed(context) |

---

### 10. ResponseBuildStage — 响应构建阶段

**文件**: `pipeline/stages/ResponseBuildStage.java`
**包**: `lyjew.com.lyclaw.pipeline.stages`
**实现**: PipelineStage

| 属性 | 类型 | 说明 |
|------|------|------|
| interceptorChain | InterceptorChain | 拦截器链（用于 postHandle） |

**方法**:
| 方法签名 | 返回值 | 说明 |
|----------|--------|------|
| getName() | String | 返回 "response_build" |
| supports(context) | boolean | 始终返回 true |
| execute(context, chain) | void | 1. 从 context 构建 ChatResult<br>2. interceptorChain.doPostHandle(result)<br>3. 将 result 存入 context.metadata["result"]<br>4. 注意：这是最后一个 Stage，不调用 chain.proceed() |

---

## 默认管道编排

DefaultEngine 使用的默认编排：

```
Pipeline.builder()
    .addStage(new ContextBuildStage(contextBuilders, memoryManager, toolRegistry))
    .addStage(new InterceptorStage(interceptorChain))
    .addStage(new ToolCallLoopStage(toolCallLoop))
    .addStage(new MetricsStage(eventBus))
    .addStage(new ResponseBuildStage(interceptorChain))
    .build();
```

## 实现顺序

1. Chain（阶段链核心）
2. PipelineStage 接口
3. Pipeline 接口 + PipelineImpl
4. PipelineBuilder
5. ContextBuildStage（依赖 ContextBuilder 接口）
6. InterceptorStage（依赖 InterceptorChain）
7. ToolCallLoopStage（依赖 ToolCallLoop）
8. MetricsStage（依赖 EventBus）
9. ResponseBuildStage（依赖 InterceptorChain）

## 校验清单

- [ ] PipelineStage 接口含 getName、supports、execute
- [ ] Chain 驱动阶段执行，supports=false 的自动跳过
- [ ] Stage 不调用 chain.proceed() 可中断管道
- [ ] PipelineBuilder 支持 addStage、addStageBefore、addStageAfter、replaceStage、removeStage
- [ ] 5 个默认 Stage 各司其职
- [ ] PipelineImpl 从 ChatRequest 开始，到 ChatResult 结束
