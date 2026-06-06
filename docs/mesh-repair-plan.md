# Agent Mesh 全面修复计划

## 扫描结果

| 类型 | 数量 |
|------|------|
| Mesh 源文件 | 33 |
| 有测试的文件 | 12 (36%) |
| 零测试的文件 | 21 (64%) |
| 编译报错 | 0 ✅ |

### 零测试的核心文件

```
DefaultAgentFactory.java     — 工厂创建 Agent 的核心
DefaultAgentMesh.java        — Mesh 核心实现（register/send/lifecycle）
LLMAgentInstance.java        — LLM Agent 运行时（调 LLM + 调工具）
ToolAgentInstance.java       — 工具 Agent
ProxyAgentInstance.java      — @Agent 向后兼容
DefaultOrchestrationEngine.java — 6种编排模式引擎
DefaultAgentMeshMetrics.java — 指标收集器
DefaultAgentMeshTest.java    — 已有但不完整
AgentMeshIntegrationTest.java — 已有但不完整
```

### 根本问题

1. **测试缺口 64%** → 核心运行时类没有单元测试，运行时错误只能在集成测试中发现
2. **Spring 依赖注入顺序不确定** → `agentFactory()` 和 `agentMesh()` 创建顺序未定义
3. **`DefaultAgentFactory.create()` 运行时使用 `DefaultAgentMesh.getDefault()`** → 静态回退方案脆弱
4. **`MeshAutoConfiguration` 注册需要 `mvn clean compile`** → 否则 `.imports` 文件不更新

## 修复步骤

### Step 1: 补全测试（先写测试再修代码）

为以下 10 个文件编写完整单元测试（Listed by priority）:

| 优先级 | 文件 | 测试重点 |
|--------|------|---------|
| P0 | `DefaultAgentFactory` | create(LLM), create(TOOL), null/missing deps |
| P0 | `LLMAgentInstance` | send() with/without chatFacade, sendStream, lifecycle |
| P0 | `ToolAgentInstance` | send() with tool, lifecycle, error handling |
| P1 | `DefaultOrchestrationEngine` | executeSINGLE, CHAIN, FAN_OUT with mock mesh |
| P1 | `DefaultAgentMeshMetrics` | record metrics, snapshot, reset |
| P1 | `DefaultAgentMesh` | lifecycle, supervision, message routing edge cases |
| P2 | `ProxyAgentInstance` | send wrapping proxy call |
| P2 | `OrchestrationSpec` | builder, validation |
| P2 | `AgentSpec` | builder, toRef, validation |
| P2 | `AgentHandle` | state transitions, health |

### Step 2: 修复 Spring Wiring

将 `MeshAutoConfiguration` 中的 static `getDefault()` 方案替换为：
- `DefaultAgentFactory` 在 `create()` 方法中延迟连接 Mesh
- 如果 `this.mesh == null` 且 `DefaultAgentMesh.getDefault() == null` → 抛出清晰的错误
- 或者使用 **`@DependsOn`** 确保 `agentMesh` 先于 `agentFactory` 创建

```java
@Bean
@DependsOn("agentMesh")
public DefaultAgentFactory agentFactory(...) { ... }
```

### Step 3: 修复 AutoConfiguration.imports

确保：
- `MeshAutoConfiguration` 在 imports 文件中
- `mvn clean compile` 后 imports 生效

### Step 4: 全量测试验证

```
mvn clean test -pl lyclaw-framework -Dtest="lyjew.com.lyclaw.mesh.*"
```

### Step 5: 端到端验证

```
1. 启动后端
2. 注册 Agent → 确认 LLM 可调用
3. 执行编排 → 确认 6 种模式正常
4. 前端联调 → 确认 MeshView 展示
```
