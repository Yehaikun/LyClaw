# Agent 规划模式设计文档

## 1. 概述

为单个 Agent 引入 **LLM 驱动的任务规划能力**，Agent 收到用户消息后可先让 LLM 分解为结构化步骤（DAG），再按拓扑顺序逐节点执行。提供三种模式供用户和开发者按需选择。

### 核心目标

- Agent 不再用正则写死模板，而是让 LLM 动态生成计划
- 计划生成后，引擎按 DAG 拓扑顺序 + 并发执行节点
- 前端可实时看到规划过程和执行进度
- 向前兼容：关闭规划时行为跟现在完全一致

---

## 2. 三种规划模式

| 模式 | 行为 | LLM 调用次数 | 适用场景 |
|------|------|-------------|---------|
| **ON** | 永远先 LLM 规划，再按 DAG 执行 | 2次（规划 + 回复） | 复杂任务，需要多步推理 |
| **OFF** | 跳过规划，直接 ReAct | 1次（直接回复） | 简单对话、闲聊 |
| **AUTO** | 先快速评估复杂度，复杂则规划，简单则跳过 | 1~2次 | 通用场景，智能切换 |

### ON — 永远规划

```
用户消息 → LLM 规划 → DAG 执行 → 汇总回复
```

每次请求都调用 LLM 生成结构化计划。适合需要严格按步骤执行的场景（如代码审查、报告生成）。

### OFF — 永不规划

```
用户消息 → ReAct 循环 → 回复
```

跟现在完全一样。LLM 自由推理 + 调工具，不受计划约束。

### AUTO — 智能判断

```
用户消息 → 快速复杂度评估
              │
              ├─ 简单（一句话能答）→ ReAct → 回复
              │
              └─ 复杂（需多步/搜索）→ LLM 规划 → DAG 执行 → 汇总回复
```

复杂度评估嵌入在 LLM 规划调用中，**不增加额外 LLM 请求**。LLM 在生成计划的同时自己判断是否真的需要计划：

```
Prompt:
"分析以下用户意图。如果任务极简单（闲聊、简单事实问答、
无需多步骤），只返回 {"plan_needed": false}。
如果需要多步操作（搜索、计算、代码执行等），返回完整计划。

用户: 1+1等于几？
→ {"plan_needed": false}

用户: 帮我搜Java 21新特性，对比Python 3.13，写个报告
→ {"plan_needed": true, "steps": [...]}"
```

---

## 3. 架构设计

### 3.1 后端架构

```
ChatController
  │
  ├─ 读取请求中的 planningMode（前端可覆盖 @Agent 默认值）
  │
  ▼
AgentInvocationHandler.invoke()
  │
  ├─ 读取最终 planningMode
  │     ├─ OFF  → 跳过 PlanExecutionStage
  │     └─ ON / AUTO → 执行 PlanExecutionStage
  │
  ├─ PlanExecutionStage
  │     │
  │     ├─ AUTO 模式: LLM 评估复杂度
  │     │     ├─ plan_needed=false → 设置 ctx.skipDagExecution=true
  │     │     └─ plan_needed=true  → 生成 DAG，发送 plan_generated SSE
  │     │
  │     └─ ON 模式: 直接 LLM 生成 DAG，发送 plan_generated SSE
  │
  └─ RespondStage
        │
        ├─ ctx.skipDagExecution=true → 自由 ReAct（跟现在一样）
        │
        └─ 有 DAG → DAG 驱动执行
              │
              ├─ getReadyNodes() → 挑出依赖已满足的节点
              ├─ flatMap 并发执行就绪节点
              │     └─ 每个节点: ReAct(单次 Thought→Action→Observation)
              ├─ 发送 node_start / node_complete SSE
              └─ 循环直到全部完成
```

### 3.2 前端交互

```
┌─────────────────────────────────────────────────────────┐
│  LyClaw Chat                                    [⚙️]   │
├─────────────────────────────────────────────────────────┤
│                                                         │
│  ┌─────────────────────────────────────────────────┐   │
│  │  AI 正在规划任务...                              │   │
│  │                                                  │   │
│  │  ┌──────────┐   ┌──────────┐                    │   │
│  │  │ 1.搜索   │   │ 2.搜索   │                    │   │
│  │  │ Java 21  │   │ Python   │                    │   │
│  │  └────┬─────┘   └────┬─────┘                    │   │
│  │       └──────┬───────┘                           │   │
│  │              ▼                                    │   │
│  │       ┌──────────┐                               │   │
│  │       │ 3.对比   │ ◀ 等待中                      │   │
│  │       └────┬─────┘                               │   │
│  │            ▼                                      │   │
│  │       ┌──────────┐                               │   │
│  │       │ 4.报告   │ ◀ 等待中                      │   │
│  │       └──────────┘                               │   │
│  └─────────────────────────────────────────────────┘   │
│                                                         │
│  ┌─────────────────────────────────────────────────┐   │
│  │  用户                                          │   │
│  │  帮我搜Java 21新特性，和Python 3.13对比         │   │
│  └─────────────────────────────────────────────────┘   │
│                                                         │
│  输入消息...        [🧠 规划模式 ▼] [📎] [📤 发送]     │
│                     ├─ 常规模式                        │
│                     ├─ 规划模式  ●                     │
│                     └─ 自动判断                        │
└─────────────────────────────────────────────────────────┘
```

---

## 4. 后端设计

### 4.1 新增 PlanningMode 枚举

```java
// lyclaw-framework/.../react/PlanningMode.java
public enum PlanningMode {
    ON,    // 永远 LLM 规划 + DAG 执行
    OFF,   // 跳过规划，直接 ReAct
    AUTO   // LLM 自行判断是否需要规划
}
```

### 4.2 @Agent 注解新增属性

```java
@Agent(name = "chat", description = "通用聊天助手",
       planning = PlanningMode.AUTO)  // 默认 AUTO
public interface ChatAgent { ... }
```

### 4.3 ChatRequest 新增字段（前端覆盖）

```java
public class ChatRequest {
    // ... 现有字段 ...

    // 前端可传此字段覆盖 @Agent 的默认值
    // null 表示不覆盖，使用 @Agent 默认
    private PlanningMode planningMode;
}
```

### 4.4 SSE 事件定义

| 事件名 | 阶段 | 含义 |
|--------|------|------|
| `planning_start` | 规划开始 | AI 开始分析任务 |
| `planning_judge` | 复杂度判断(AUTO) | `{"complex": true/false}` |
| `plan_generated` | 规划完成 | 附带完整 DAG JSON |
| `plan_node_start` | 节点开始 | `{"nodeId":"1","desc":"搜索Java 21"}` |
| `plan_node_delta` | 节点执行中 | 流式输出节点执行过程 |
| `plan_node_complete` | 节点完成 | `{"nodeId":"1","result":"..."}` |
| `plan_all_complete` | 全部完成 | 所有节点执行完毕 |

### 4.5 PlanExecutionStage 改动

```java
// 核心逻辑
if (mode == OFF) {
    ctx.setSkipDagExecution(true);
    return Flux.just(sseEvent("planning_start", "Skipped"));
}

// ON 或 AUTO: 调用 LLM
String planningPrompt = buildPlanningPrompt(ctx.getUserMessage(), mode);
ModelResponse planningResult = chatFacade.chat(planningPrompt);

if (mode == AUTO) {
    PlanningJudge judge = parseJudge(planningResult);
    if (!judge.planNeeded) {
        sink.next(sseEvent("planning_judge", "{\"complex\":false}"));
        ctx.setSkipDagExecution(true);
        sink.complete();
        return;
    }
}

// 解析 LLM 输出的 JSON → TaskNode DAG
List<TaskNode> nodes = parsePlan(planningResult);
ctx.setNodes(nodes);

// 发送 SSE 给前端展示
sink.next(sseEvent("plan_generated", toPlanJson(nodes)));
```

### 4.6 RespondStage 改动 — DAG 驱动执行

```java
// DAG 驱动执行路径
private Flux<ServerSentEvent<String>> executeDag(AgentContext ctx) {
    return Flux.create(sink -> {
        List<TaskNode> allNodes = ctx.getNodes();

        while (!allDone(allNodes)) {
            // 1. 拓扑选就绪节点
            List<TaskNode> ready = getReadyNodes(allNodes);

            if (ready.isEmpty() && !allDone(allNodes)) {
                // 死锁：有节点但依赖永远不满足 → 跳过
                break;
            }

            // 2. 并发执行就绪节点
            Flux.fromIterable(ready)
                .flatMap(node -> {
                    sink.next(sseEvent("plan_node_start",
                        "{\"nodeId\":\"" + node.getNodeId() + "\"}"));
                    return executeSingleNode(node, ctx)
                        .doOnNext(delta ->
                            sink.next(sseEvent("plan_node_delta", delta)))
                        .doOnComplete(() -> {
                            node.setStatus("completed");
                            sink.next(sseEvent("plan_node_complete",
                                "{\"nodeId\":\"" + node.getNodeId()
                                + "\",\"result\":\"" + node.getResult() + "\"}"));
                        });
                })
                .collectList()
                .block();
        }

        // 3. 汇总回复
        String summary = summarizeResults(allNodes, ctx);
        sink.next(sseEvent("message", summary));
        sink.next(sseEvent("plan_all_complete", "{}"));
        sink.complete();
    });
}

// 单体节点执行 = 一次 ReAct 循环
private Mono<String> executeSingleNode(TaskNode node, AgentContext ctx) {
    String prompt = "当前任务: " + node.getDescription()
        + "\n请完成此步骤，必要时使用工具。完成后给出结果。";
    return reActEngine.executeStream(chatFacade,
        buildRequest(prompt), toolExecutor);
}
```

### 4.7 并发执行

使用 Reactor 的 `flatMap` + `subscribeOn`：

```java
Flux.fromIterable(readyNodes)
    .flatMap(node ->
        executeSingleNode(node, ctx)
            .subscribeOn(Schedulers.boundedElastic())
    )  // readyNodes 内的节点并发执行
    .collectList()
    .block();  // 等待全部完成，再推进到下一层
```

拓扑保证：有依赖的节点必须等前置全完成。同一层无依赖节点并发。对外部调用者始终是一个同步调用。

---

## 5. 前端设计

### 5.1 规划模式选择器

位置：聊天输入框左侧，一个下拉按钮。

```
┌──────────────┐
│ 🧠 规划模式 ▼ │
├──────────────┤
│ ○ 常规模式   │  → planningMode=OFF，永不规划
│ ● 规划模式   │  → planningMode=ON，永远规划
│ ○ 自动判断   │  → planningMode=AUTO（默认）
└──────────────┘
```

发送请求时带上：

```json
{
  "messages": [{"role": "user", "content": "..."}],
  "planningMode": "ON"
}
```

### 5.2 规划过程展示

收到 `planning_start` 事件后，在聊天区插入一个规划卡片，显示加载动画。

### 5.3 计划 DAG 展示

收到 `plan_generated` 后，渲染为 DAG 图：

```
┌───────────────────────────────────┐
│  📋 执行计划                      │
│                                   │
│  [1.搜索Java 21] ←──┐            │
│  [2.搜索Python  ] ←──┼─→ [3.对比] │
│                     │      ↓      │
│                     │   [4.报告]  │
│  ✅ 已完成  🔄 进行中  ⏳ 等待   │
└───────────────────────────────────┘
```

### 5.4 执行进度展示

- `plan_node_start` → 对应节点高亮为 🔄 进行中，输入框上方显示 "正在执行: 搜索Java 21新特性..."
- `plan_node_complete` → 节点变为 ✅ 已完成
- `plan_all_complete` → 所有节点完成，显示最终回复

### 5.5 状态映射

| SSE 事件 | 前端动作 |
|---------|---------|
| `planning_start` | 显示 "AI 正在规划..." |
| `planning_judge` | complex=false → 隐藏规划UI，直接等回复 |
| `plan_generated` | 渲染 DAG 图，所有节点 ⏳ |
| `plan_node_start` | 节点变 🔄，显示当前步骤名 |
| `plan_node_delta` | 节点执行内容流式追加 |
| `plan_node_complete` | 节点变 ✅ |
| `plan_all_complete` | DAG 图收折，显示最终结果 |

---

## 6. 数据流总览

```
                 前端                           后端
                  │                              │
                  │  POST /api/chat/stream        │
                  │  {"planningMode":"ON", ...}    │
                  ├──────────────────────────────►│
                  │                              │
                  │                     读取 @Agent(planning)
                  │                     前端传了 → 用前端的
                  │                     前端没传 → 用 @Agent 默认
                  │                              │
                  │  SSE: planning_start          │
                  │ ◄─────────────────────────────┤
                  │                              │
                  │                     调 LLM 生成计划
                  │                     ON  → 直接生成
                  │                     AUTO → 先判断再生成
                  │                              │
                  │  SSE: plan_generated          │
                  │  {nodes: [{id:1,...}, ...]}   │
                  │ ◄─────────────────────────────┤
                  │                              │
                  │  渲染 DAG 图                  │
                  │                              │
                  │  SSE: plan_node_start         │
                  │  {nodeId:"1"}                 │
                  │ ◄─────────────────────────────┤
                  │                              │
                  │  高亮节点1 🔄                 │
                  │                              │
                  │  SSE: plan_node_delta         │
                  │  "搜索中..."                  │
                  │ ◄─────────────────────────────┤
                  │                              │
                  │  SSE: plan_node_complete      │
                  │  {nodeId:"1", result:"..."}   │
                  │ ◄─────────────────────────────┤
                  │                              │
                  │  节点1变 ✅                    │
                  │  ... 重复直到全部完成 ...      │
                  │                              │
                  │  SSE: message (最终回复)       │
                  │  SSE: plan_all_complete       │
                  │ ◄─────────────────────────────┤
                  │                              │
                  ▼                              ▼
```

---

## 7. 改动清单

### 后端

| 文件 | 改动 |
|------|------|
| `react/PlanningMode.java` | **新建** 枚举：ON / OFF / AUTO |
| `annotation/Agent.java` | 新增 `PlanningMode planning() default AUTO` |
| `model/ChatRequest.java` | 新增 `PlanningMode planningMode` 字段 |
| `react/AgentInvocationHandler.java` | 读取 planningMode，传给 Stage |
| `pipeline/stage/PlanExecutionStage.java` | LLM 规划 + AUTO 复杂度判断 |
| `pipeline/stage/RespondStage.java` | DAG 驱动执行路径 + 自由 ReAct 降级 |

### 前端

| 文件 | 改动 |
|------|------|
| `ChatInput.vue` | 新增规划模式下拉选择器 |
| `ChatMessage.vue` | 新增 DAG 计划展示组件 |
| `chat store` | 新增 planningMode 状态，请求时带上 |
| `sse handler` | 新增 7 个 SSE 事件处理 |

### 不改动的

- `ToolRegistry` / 所有 `@Tool` 类 — 不变
- `Hook` 体系 — 不变
- `ReActEngine` — 不变
- `ChatFacade` / `ChatModel` — 不变
- 其他 Stage（ContextBuild / SecurityCheck / Metrics）— 不变

---

## 8. 兼容性

- `@Agent` 不显式设置 `planning` → 默认 `AUTO`
- 前端不传 `planningMode` → 使用 `@Agent` 默认值
- `OFF` 模式 → 行为与当前版本完全一致（跳过 PlanExecutionStage，直接 ReAct）
- 现有接口和测试不受影响
- SSE 事件为**追加**，不影响现有 `message` / `tool_call` 事件的消费
