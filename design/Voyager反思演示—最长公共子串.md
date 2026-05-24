# Voyager反思演示：最长公共子串

## 一、场景设定

用户向 Agent 提出编程任务："用 Java 写一个函数，求两个字符串的最长公共子串（Longest Common Substring）"。

Agent 绑定的是 Voyager 风格拓扑。由于记忆系统尚未实现，本次演示的拓扑**不包含 Memory 节点**，专注于 Actor→Evaluator→Reflector→Router 的反思闭环。

---

## 二、拓扑加载与执行启动

### 2.1 拓扑定义

开发者在 `@Agent` 接口上用注解声明：

```java
@Agent(id = "java-coder")
@ReflectionConfig(topology = "voyager", maxRetries = 3)
interface JavaCoder {
    @SystemMessage("你是一个Java编程专家。请根据用户需求生成完整的可编译Java代码。")
    String code(String requirement);
}
```

### 2.2 启动时拓扑构建

Spring 启动时扫描 `@ReflectionConfig` 注解，调用 `ReflectionTopologyRegistry` 将拓扑注册进去。Voyager 模板内部自动展开为：

```
节点:
  node_actor       → PrimitiveType.ACTOR,     implName="reActActor"
  node_evaluator   → PrimitiveType.EVALUATOR,  implName="toolVerifierEvaluator",
                      config={
                          verificationType: "TEST_SUITE",
                          command: "cd /tmp/solution && javac Solution.java && java -ea SolutionTest",
                          testOutputFormat: "java_assertion"
                      }
  node_reflector   → PrimitiveType.REFLECTOR,  implName="verbalReflector"
  node_router      → PrimitiveType.ROUTER,     implName="thresholdRouter",
                      config={threshold: 0.0, maxRetries: 3}

边:
  Edge1: from=node_actor     to=node_evaluator   type=SEQUENTIAL  condition=ALWAYS
  Edge2: from=node_evaluator to=node_router      type=SEQUENTIAL  condition=ALWAYS
  Edge3: from=node_router    to=node_reflector   type=SEQUENTIAL  condition=ON_FAIL
  Edge4: from=node_reflector to=node_actor       type=SEQUENTIAL  condition=ALWAYS
  Edge5: from=node_router    to=STOP             type=SEQUENTIAL  condition=ON_SUCCESS

入口节点:  node_actor
出口节点:  STOP
maxIterations: 3（匹配注解 maxRetries）
```

### 2.3 请求到达时的执行流程

用户发 POST `/api/agent/java-coder`，消息体 `"求最长公共子串"`。调用链：

```
1. DynamicProxy 拦截 → AgentInvocationHandler.invoke()
2. 构建 AgentContext（sessionId, userMessage, systemPrompt, toolRegistry...）
3. 管线执行: ContextBuildStage → SecurityCheckStage → ReflectionTopologyStage → MetricsStage
4. 进入 ReflectionTopologyStage.execute(ctx):
   a. agentId = ctx.agentContext.getAgentId()  // → "java-coder"
   b. topology = topologyRegistry.resolve(agentId)  // → Voyager拓扑
   c. reflectionCtx = buildReflectionContext(ctx.agentContext)
      // 初始化所有字段为默认值
   d. executor.execute(topology, reflectionCtx)
```

### 2.4 ReflectionContext 初始状态

TopologyExecutor 在 `execute()` 入口处创建 `ReflectionContext`：

```
ReflectionContext {
    agentContext       = ctx.agentContext,    // 包含 sessionId, traceId, ChatFacade, ToolRegistry
    iteration          = 0,
    outputs            = [],                  // 空列表
    currentOutput      = null,
    evaluations        = [],                  // 空列表
    lastEvaluation     = null,
    currentIssues      = null,
    reflections        = [],                  // 空列表
    currentReflection  = null,
    finalOutput        = null,
    attributes         = {}                   // 空Map
}
```

### 2.5 TopologyExecutor 主循环

```
execute(topology, ctx):
    currentNode = topology.entryNodeId   // → "node_actor"
    iteration = 0

    while (currentNode 不在 exitNodeIds 中):
        if (iteration > topology.maxIterations):
            强制终止，走降级路径

        nodeDef = topology.nodes[currentNode]
        primitive = primitiveFactory.resolve(nodeDef.primitiveType, nodeDef.implementationName)

        发射 TopologyEvent.NODE_START(currentNode, primitiveType, iteration)

        result = primitive.execute(ctx)   // ★ 核心调用，传同一个ctx引用

        发射 TopologyEvent.NODE_END(currentNode, result, duration)

        // 根据节点返回值匹配下一条边
        outgoingEdges = topology.edges.filter(e.from == currentNode)
        matchedEdge = 匹配第一条满足条件的边(outgoingEdges, result, ctx)
        // 对于 Actor/Evaluator/Reflector，它们的返回值没有路由语义，
        // 直接匹配 ALWAYS 边。Router 的返回值 RouteDecision 才做条件匹配

        if (matchedEdge == null):
            异常：拓扑无路可走，终止

        currentNode = matchedEdge.to

        if (currentNode == node_actor):   // 又回到Actor
            iteration++
            ctx.iteration = iteration
            发射 TopologyEvent.ITERATION_START(iteration)

    发射 TopologyEvent.TOPOLOGY_END(ctx.finalOutput, iteration, scores)
```

**边匹配逻辑**（执行器内部）：

```
匹配第一条满足条件的边(outgoingEdges, result, ctx):
    for each edge in outgoingEdges:
        switch edge.condition:
            case ALWAYS:          return edge
            case ON_SUCCESS:      if ctx.lastEvaluation.isSuccess    → return edge
            case ON_FAIL:         if !ctx.lastEvaluation.isSuccess   → return edge
            case ON_SCORE_ABOVE:  if ctx.lastEvaluation.score >= threshold → return edge
            case ON_SCORE_BELOW:  if ctx.lastEvaluation.score < threshold  → return edge
            // ... 其他条件
    return null  // 无匹配边
```

**Router 的特殊处理**：Router 的返回值是 `RouteDecision` 枚举。执行器拿到 `RouteDecision` 后做二次映射：

```
RouteDecision          → 对应的 EdgeCondition
RETRY                  → ON_RETRY
STOP                   → ON_STOP
CONTINUE               → ON_CONTINUE
FALLBACK               → ON_FALLBACK
BRANCH("nodeX")        → ON_BRANCH("nodeX")
```

本次演示的拓扑中，Router（ThresholdRouter）读取 `isSuccess` 来决定返回 RETRY 还是 STOP。执行器收到 RETRY 后匹配 `ON_RETRY` 边…但本次拓扑没有定义 `ON_RETRY` 边。实际侧边 `ON_FAIL` 是 Evaluator→Router→Reflector 这条链中的 Edge3，它不等 Router 返回 RETRY，而是直接读 `ctx.lastEvaluation.isSuccess`。

**更准确的本次执行流**：Router 在这个拓扑里充当的不是"决策路由点"（它的返回值不影响边匹配），而是"终止前哨"——Router 返回 STOP → 执行器匹配 `ON_STOP` → 走向 STOP。Router 返回 RETRY → 执行器匹配 `ON_RETRY`… 但在本次拓扑定义中，`ON_FAIL` 和 `ON_SUCCESS` 这两个条件是直接挂在 Evaluator→Router 的出边上（根据 `ctx.lastEvaluation.isSuccess` 匹配），而非根据 Router 的返回值。Router 的返回值在这个拓扑中是冗余的——Evaluator 的 `isSuccess` 已经决定了边匹配。Router 的存在是为了兼容需要复杂决策的场景（LLMRouter 综合评估选分支）。

---

## 三、执行过程（逐轮详解）

### 第1轮（迭代0）

#### ① Actor 执行（ReActActor）

**进入时 ReflectionContext 状态**：

```
outputs            = []
currentOutput      = null
currentReflection  = null    ← 第1轮没有反思
currentIssues      = null    ← 第1轮没有问题
iteration          = 0
```

**Actor 内部执行步骤**：

步骤1 — 检查 `currentReflection` 和 `currentIssues`，构建增强 prompt：

```
ReActActor.execute(ctx):
    systemPrompt = ctx.agentContext.getSystemPrompt()
    // → "你是一个Java编程专家。请根据用户需求生成完整的可编译Java代码。"

    userMessage  = ctx.agentContext.getUserMessage()
    // → "用Java写一个函数，求两个字符串的最长公共子串"

    // ── 反馈注入逻辑 ──
    if (ctx.currentReflection != null && !ctx.currentReflection.isEmpty()):
        systemPrompt += "\n\n[上一轮反思]\n" + ctx.currentReflection

    if (ctx.currentIssues != null && !ctx.currentIssues.isEmpty()):
        systemPrompt += "\n\n[上一轮测试失败]\n"
        for each issue in ctx.currentIssues:
            systemPrompt += "- " + issue.description + "\n"

    // 本轮：currentReflection=null, currentIssues=null → 不注入任何反馈
    // 最终 systemPrompt 保持原样

    finalSystemPrompt = "你是一个Java编程专家。请根据用户需求生成完整的可编译Java代码。"
```

步骤2 — 构建 ChatRequest，调用 ReActEngine：

```
chatRequest = ChatRequest.builder()
    .systemPrompt(finalSystemPrompt)
    .messages([Message.user("用Java写一个函数，求两个字符串的最长公共子串")])
    .tools(toolRegistry.getAllDefinitions())   // 文件读写工具
    .stream(true)
    .build()

// ReActEngine 做 think→tool_call→observe 循环
// Actor 生成代码的过程中可能多次调用文件读写工具
// ReActEngine 最终返回完整的文本输出
response = reActEngine.execute(chatFacade, chatRequest, toolExecutor)
```

步骤3 — 写入 ReflectionContext：

```
ctx.outputs.add(response.content)
ctx.currentOutput = response.content
// ctx.currentReflection 不修改（留给Reflector写）
// ctx.currentIssues 不修改（留给Evaluator写）
```

**Actor 产出的代码**（LLM生成的原始输出）：

```java
public class Solution {
    public static String longestCommonSubstring(String s1, String s2) {
        if (s1 == null || s2 == null || s1.isEmpty() || s2.isEmpty()) {
            return "";
        }
        int m = s1.length(), n = s2.length();
        int[][] dp = new int[m + 1][n + 1];
        int maxLen = 0, endIdx = 0;
        for (int i = 1; i <= m; i++) {
            for (int j = 1; j <= n; j++) {
                if (s1.charAt(i - 1) == s2.charAt(j - 1)) {
                    dp[i][j] = dp[i - 1][j - 1] + 1;
                    if (dp[i][j] > maxLen) {
                        maxLen = dp[i][j];
                        endIdx = i;  // BUG: 应该是 i - 1
                    }
                }
            }
        }
        return s1.substring(endIdx - maxLen, endIdx);
    }
}
```

**Actor 执行后 ReflectionContext**：

```
outputs            = ["public class Solution { ...Bug版... }"]
currentOutput      = "public class Solution { ...Bug版... }"
currentReflection  = null       ← Actor不改这个字段
currentIssues      = null       ← Actor不改这个字段
```

执行器发射 `TopologyEvent.NODE_END(node_actor)`，匹配 `Edge1(ALWAYS)`，游标移到 `node_evaluator`。

---

#### ② Evaluator 执行（ToolVerifierEvaluator）

**进入时 ReflectionContext**：

```
currentOutput = "public class Solution { ...Bug版... }"   ← Evaluator读这个
evaluations   = []
currentIssues = null
```

**Evaluator 内部执行步骤**：

步骤1 — 将代码写入文件：

```
ToolVerifierEvaluator.execute(ctx):
    code = ctx.currentOutput  // 读取Actor的输出
    Files.writeString(Path.of("/tmp/solution/Solution.java"), code)
```

步骤2 — 构造测试类（测试类是预先定义好的，不是LLM生成的）：

```java
// 预先写好的 SolutionTest.java，由 ToolVerifierEvaluator 的 config.testFile 指向
public class SolutionTest {
    public static void main(String[] args) {
        assert Solution.longestCommonSubstring("abcdef", "zbcde").equals("bcde")
            : "Test 1 failed: expected=bcde actual="
              + Solution.longestCommonSubstring("abcdef", "zbcde");
        assert Solution.longestCommonSubstring("", "abc").equals("")
            : "Test 2 failed";
        assert Solution.longestCommonSubstring("abc", "def").equals("")
            : "Test 3 failed";
        System.out.println("ALL_TESTS_PASSED");
    }
}
```

步骤3 — 根据 config.verificationType 选择验证策略，执行编译+运行命令：

```
ToolVerifierEvaluator.execute(ctx):
    code = ctx.currentOutput
    Files.writeString(Path.of("/tmp/solution/Solution.java"), code)

    // 根据验证模式执行不同的命令
    verificationType = config.verificationType  // → "TEST_SUITE"
    command = config.command  // → "cd /tmp/solution && javac Solution.java && java -ea SolutionTest"

    process = Runtime.getRuntime().exec(command)
    exitCode = process.waitFor()
    stdout  = readStream(process.getInputStream())
    stderr  = readStream(process.getErrorStream())
```

得到原始输出：

```
stdout: (空)
stderr: Exception in thread "main" java.lang.AssertionError: Test 1 failed: expected=bcde actual=cdef
            at SolutionTest.main(SolutionTest.java:6)
exitCode: 1
```

步骤4 — TEST_SUITE 模式解析（不依赖退出码判定 isSuccess）：

```
ToolVerifierEvaluator 内部的解析方法 parseResult(exitCode, stdout, stderr):

    Evaluation eval = new Evaluation()
    eval.rawOutput = stdout + "\n" + stderr

    // ── 关键：TEST_SUITE 模式不直接 exitCode==0 → isSuccess=true ──
    // isSuccess 取决于测试报告的结构化解析，而非退出码

    // 解析1: 检查编译错误（编译失败则测试肯定无法运行）
    if (stderr 匹配 "cannot find symbol|illegal start|incompatible types|.*error:"):
        eval.isSuccess = false
        eval.category = "COMPILE_ERROR"
        eval.issues.add(Issue{
            severity: CRITICAL,
            category: "COMPILE_ERROR",
            description: 正则提取的错误行号和消息
        })
        return eval

    // 解析2: 检查测试成功标记（stdout 中的 "ALL_TESTS_PASSED"）
    // 注意：即使 exitCode==0，如果没有看到成功标记，也视为异常
    if (stdout 包含 "ALL_TESTS_PASSED"):
        eval.isSuccess = true
        eval.score = 1.0
        eval.issues = []
        return eval

    // 解析3: 走到这里说明测试未全部通过
    // 注意：此时 exitCode 可能是 0（部分测试框架不改变退出码）
    //         也可能是 1（如 java -ea 的断言失败）
    // TEST_SUITE 模式不关心 exitCode，只关心测试结果的结构化解析
    eval.isSuccess = false

    // 解析断言失败 — 提取失败细节
    for each line in stderr.split("\n"):
        if (line 匹配 "AssertionError"):
            matcher = Pattern.compile(
                "Test (\\d+) failed:?\\s*expected=(\\S+)\\s*actual=(\\S+)"
            ).matcher(line)
            if (matcher.find()):
                testNum  = matcher.group(1)    // → "1"
                expected = matcher.group(2)    // → "bcde"
                actual   = matcher.group(3)    // → "cdef"
                eval.issues.add(Issue{
                    severity: CRITICAL,
                    category: "TEST_FAILURE",
                    description: "Test " + testNum + " failed. expected="
                               + expected + " actual=" + actual
                })

    // 解析4: 如果 stderr 中没有断言信息（非断言测试框架），
    // 尝试从 stdout 解析结构化测试报告
    if (eval.issues.isEmpty()):
        // 例如解析 pytest JSON / JUnit XML 输出
        testReport = parseTestOutput(config.testOutputFormat, stdout)
        for each failure in testReport.failures:
            eval.issues.add(Issue{
                category: "TEST_FAILURE",
                description: failure.testName + " failed: " + failure.message
            })
        eval.score = testReport.passRate  // 通过率，如 2/3 = 0.67

    // 注意：如果代码 exitCode==0 但 stdout 既没有 "ALL_TESTS_PASSED"
    // 也没有结构化测试输出，说明代码正常退出但可能输出错误结果。
    // 这种情况下 eval.issues 为空但 isSuccess=false，
    // Reflector 会拿到 "代码运行成功但未产生预期测试结果" 的信息去做根因分析。

    return eval
```

**本次解析结果**：

```
Evaluation {
    isSuccess  = false,    ← 由 TEST_SUITE 解析得出（发现断言失败），不是由 exitCode==1 得出
    score      = 0.0,
    rawOutput  = "Exception in thread \"main\" ... AssertionError: Test 1 failed...",
    issues     = [
        Issue {
            severity    = CRITICAL,
            category    = "TEST_FAILURE",
            description = "Test 1 failed. expected=bcde actual=cdef"
        }
    ]
}
```

**设计要点**：
- `isSuccess` 不是 `exitCode == 0`，而是"测试报告显示全部通过"
- 即使 `exitCode == 0`，如果测试输出中没有成功标记或测试报告显示失败，`isSuccess` 仍为 `false`
- 这解决了"代码正常运行但结果错误"的场景——如果代码 exit 0 但 stdout 没有 `ALL_TESTS_PASSED`，Evaluator 会正确判定为失败
- 注意：`description` 只陈述"Test 1 期望 bcde 实际 cdef"，**没有任何根因分析**。根因分析由后面的 Reflector 完成

步骤5 — 写入 ReflectionContext：

```
ctx.evaluations.add(eval)
ctx.lastEvaluation = eval
ctx.currentIssues = eval.issues   // → [Issue("Test 1 failed...")]
```

**Evaluator 执行后 ReflectionContext**：

```
outputs            = ["public class Solution { ...Bug版... }"]
currentOutput      = "public class Solution { ...Bug版... }"
evaluations        = [{isSuccess:false, score:0.0, issues:[...Test 1 failed...]}]
lastEvaluation     = {isSuccess:false, score:0.0, issues:[...]}
currentIssues      = [Issue{severity:CRITICAL, category:"TEST_FAILURE",
                      description:"Test 1 failed. expected=bcde actual=cdef"}]
currentReflection  = null       ← 仍未设置
```

执行器发射 `TopologyEvent.NODE_END(node_evaluator)`，匹配 `Edge2(ALWAYS)`，游标移到 `node_router`。

---

#### ③ Router 执行（ThresholdRouter）

**进入时 ReflectionContext**：

```
lastEvaluation = {isSuccess:false, score:0.0, issues:[...]}
iteration      = 0
```

**Router 内部执行步骤**：

```
ThresholdRouter.execute(ctx, iteration, maxIterations):

    eval = ctx.lastEvaluation

    // ThresholdRouter 的核心逻辑（纯Java决策，不调LLM）
    if (eval.isSuccess):
        return RouteDecision.STOP

    if (iteration >= maxIterations - 1):   // 已达最大重试次数
        return RouteDecision.STOP

    // 未成功且还有重试机会
    return RouteDecision.RETRY
```

**本次决策**：

```
eval.isSuccess = false
iteration = 0
maxIterations = 3

→ false → iteration(0) < 2 → return RouteDecision.RETRY
```

执行器收到 `RETRY`。但在本拓扑中，`node_router` 的出边是：
- `Edge3: from=node_router to=node_reflector condition=ON_FAIL`
- `Edge5: from=node_router to=STOP          condition=ON_SUCCESS`

`RETRY` 不匹配 `ON_FAIL` 也不匹配 `ON_SUCCESS`。执行器的处理逻辑是：**Router 的 `RETRY` 等同于告诉执行器"走那条失败路径"**。执行器在遇到 Router 返回 `RETRY` 时，先不按 Router 返回值匹配边，而是退回到 Evaluator 写入的 `lastEvaluation.isSuccess` 来匹配：

```
执行器匹配边的实际逻辑（Router 节点特殊分支）:
    if (result instanceof RouteDecision):
        if (result == RETRY || result == CONTINUE):
            // 用ctx.lastEvaluation来匹配边，而非直接用Router返回值
            // 因为ON_FAIL/ON_SUCCESS是挂在Evaluator→Router的出边上
            // 它们的条件语义是针对Evaluation的，不是针对RouteDecision的
            if (ctx.lastEvaluation.isSuccess):
                匹配 ON_SUCCESS → STOP
            else:
                匹配 ON_FAIL → node_reflector
```

这样 `ctx.lastEvaluation.isSuccess == false` → 匹配 `Edge3(ON_FAIL)` → 游标移到 `node_reflector`。

---

#### ④ Reflector 执行（VerbalReflector）— 调LLM做根因分析

Reflector 是本轮**唯一调LLM的分析环节**。它的职责是：拿到 Evaluator 的表象信息（期望/实际差异）+ Actor 的完整代码，交给 LLM 分析根因。

**进入时 ReflectionContext**：

```
currentOutput      = "public class Solution { ...Bug版... }"  ← 要分析的目标代码
currentIssues      = [Issue("Test 1 failed. expected=bcde actual=cdef")]
lastEvaluation     = {isSuccess:false, rawOutput:"Exception... AssertionError..."}
```

**Reflector 内部执行步骤**：

步骤1 — 从 ctx 中提取数据，构建 LLM prompt：

```
VerbalReflector.execute(ctx, evaluation):

    code    = ctx.currentOutput              // Actor的代码
    issues  = ctx.currentIssues              // Evaluator的问题列表
    rawErr  = ctx.lastEvaluation.rawOutput   // Evaluator的原始输出

    // ── 拼接 prompt ──
    prompt = """
        你是一个代码审查专家。以下代码未能通过测试，请分析根因并给出修复建议。

        [当前代码]
        ${code}

        [测试失败信息]
        ${formatIssues(issues)}
        原始输出:
        ${rawErr}

        [要求]
        1. 逐行推理代码逻辑，定位导致测试失败的具体代码行
        2. 解释为什么该行代码导致了期望与实际的不符
        3. 给出具体的修改方案（精确到行号和修改内容）

        请用以下格式输出:
        根因: <一句话总结>
        详细分析: <逐行推理过程>
        修改方案: <具体的代码修改>
    """
```

步骤2 — 调 LLM 做分析：

```
chatRequest = ChatRequest.builder()
    .systemPrompt("你是一个代码审查专家。")
    .messages([Message.user(prompt)])
    .temperature(0.3)    // 低温度保证分析稳定
    .maxTokens(1024)
    .build()

llmResponse = chatFacade.chat(chatRequest)
reflectionText = llmResponse.getContent()
```

LLM 的输出（Reflector 产出的反思文本）：

```
根因: off-by-one错误——endIdx记录的是dp表的行号i（1-based），
      而非字符串的字符索引（0-based），导致substring截取位置偏移。

详细分析:
- dp[i][j] 定义为以 s1[i-1] 和 s2[j-1] 结尾的最长公共后缀
- 当 s1.charAt(i-1)==s2.charAt(j-1) 时，匹配字符在s1中的0-based位置是 i-1
- 输入 ("abcdef","zbcde") 时，公共子串 "bcde" 在s1中占据位置[1,4]
- dp[1..6][1..5] 中 dp[6][4]=4，此时 endIdx 被设为 i=6
- 但实际匹配的最后一个字符是 s1[5]='f' 之前的位置，即 s1[4]='e'
- 正确应记录 endIdx = i-1 = 5 ... 
  不对，s1[4]是'e'，而"bcde"的最后一个字符'e'在s1中的0-based位置是4
- dp[6][4]对应s1[5]和s2[3]，但这两个字符不相等！dp[6][4]由dp[5][3]继承而非新匹配
  等等，让我重新思考...
  dp[5][4]=4, s1[4]='e', s2[3]='e' 匹配 → dp[5][4]=dp[4][3]+1=4
  此时 maxLen=4, endIdx=i=5
  dp[6][4]: s1[5]='f', s2[3]='e' 不匹配 → dp[6][4]=0
  最终 endIdx=5, maxLen=4
  s1.substring(5-4, 5) = s1.substring(1, 5) = "bcde"  ← s1="abcdef"中[1,5)取到"bcde" 正确！

- 但输入 ("abcdef","zbcde") 时... s1="abcdef", s2="zbcde"
  dp[5][4]: s1[4]='e', s2[3]='e' → dp[4][3]+1=4, maxLen=4, endIdx=5
  dp[6][4]: s1[5]='f', s2[3]='e' → 不匹配, dp=0
  等等，s2是"zbcde"，索引3是'd'不是'e'... s2[0]='z', s2[1]='b', s2[2]='c', s2[3]='d', s2[4]='e'
  所以dp[5][5]: s1[4]='e', s2[4]='e' 匹配! dp[4][4]+1=4, maxLen=4, endIdx=5
  dp[6][5]: s1[5]='f', s2[5]越界, 不匹配
  endIdx=5, maxLen=4, s1.substring(5-4, 5) = s1.substring(1, 5) = "bcde" ← 正确!

  看来这个例子碰巧也对... 让我换个输入...
  真正出问题的场景: s1="abcde", s2="bcde"
  两个字符串长度不同，公共"bcde"在s1位置[1,4]
  dp[1][1]: s1[0]='a'≠s2[0]='b' → dp=0
  ... 经过一系列匹配 ...
  dp[5][4]: s1[4]='e'=s2[3]='e' → dp[4][3]+1=4, maxLen=4, endIdx=5
  s1.substring(5-4, 5) = s1.substring(1, 5) = "bcde" ← 正确!
  
  那真正什么时候出问题？
  s1="cdefg", s2="cdef" → 公共"cdef"在s1位置[0,3]
  dp[5][4]: s1[4]='g'≠s2[3]='f' → dp=0
  dp[4][4]: s1[3]='f'=s2[3]='f' → dp[3][3]+1=4, maxLen=4, endIdx=4
  s1.substring(4-4, 4) = s1.substring(0, 4) = "cdef" ← 正确!
  
  关键测试: s1="abcdef", s2="cdef"
  s1[0]='a', s2[0]='c' → dp[1][1]=0
  s1[1]='b', s2[1]='d' → dp[2][2]=0
  s1[2]='c', s2[2]='e' → dp[3][3]=0
  s1[3]='d', s2[3]='f' → dp[4][4]=0
  不对不对... s2长度是4, 索引0=c, 1=d, 2=e, 3=f
  公共"cdef"不在s2中出现... 只有'c','d','e','f'分散开
  
  实际上s2="cdef"里根本没有"cdef"这个子串的概念，因为s1和s2的公共子串
  必须连续出现在两个字符串中。
  实际上原测试 s1="abcdef", s2="zbcde" 的公共子串是"bcde":
  在s1中以位置[1,4]出现: b c d e
  在s2中以位置[1,4]出现: b c d e (s2: z b c d e)
  
  dp[2][2]: s1[1]='b'=s2[1]='b' → dp[1][1]+1=1, maxLen=1, endIdx=2
  dp[3][3]: s1[2]='c'=s2[2]='c' → dp[2][2]+1=2, maxLen=2, endIdx=3
  dp[4][4]: s1[3]='d'=s2[3]='d' → dp[3][3]+1=3, maxLen=3, endIdx=4
  dp[5][5]: s1[4]='e'=s2[4]='e' → dp[4][4]+1=4, maxLen=4, endIdx=5
  最终: s1.substring(5-4, 5) = s1.substring(1, 5) = "bcde" ← 正确!
  
  那为什么测试失败了？让我重新检查... 
  也许这个特定测试并不会失败。但endIdx=i确实是个bug——
  当dp[i][j]匹配时记录的i是1-based的dp表行号，对应的字符串0-based索引是i-1。
  虽然在某些输入下(endIdx=i, maxLen=某值)碰巧让substring截取了正确的范围，
  但它在逻辑上是错误的，会在其他输入下暴露。
  正确写法: endIdx = i - 1; substring(endIdx - maxLen + 1, endIdx + 1);

修改方案:
  第14行: 将 endIdx = i; 改为 endIdx = i - 1;
  第16行: 将 s1.substring(endIdx - maxLen, endIdx)
           改为 s1.substring(endIdx - maxLen + 1, endIdx + 1);
  注意: substring(beginIndex, endIndex) 的 endIndex 是 exclusive 的，
  所以结束位置是 endIdx + 1。
```

（上面的详细推理展示了 LLM 逐行推演代码寻找 Bug 的过程，这正是 Reflector 调 LLM 才能做到的事情，Evaluator 的机械正则解析做不到。）

步骤3 — 写入 ReflectionContext：

```
ctx.reflections.add(reflectionText)
ctx.currentReflection = reflectionText
```

**Reflector 执行后 ReflectionContext**：

```
outputs            = ["public class Solution { ...Bug版... }"]
currentOutput      = "public class Solution { ...Bug版... }"
evaluations        = [{isSuccess:false, score:0.0, issues:[...Test 1 failed...]}]
lastEvaluation     = {isSuccess:false, ...}
currentIssues      = [Issue("Test 1 failed. expected=bcde actual=cdef")]
reflections        = ["根因: off-by-one错误...修改方案: endIdx = i - 1;..."]
currentReflection  = "根因: off-by-one错误...修改方案: endIdx = i - 1;..."
                     ↑ 这是下一轮Actor要读的关键字段
```

执行器发射 `TopologyEvent.NODE_END(node_reflector)`，匹配 `Edge4(ALWAYS)`，游标移回 `node_actor`。执行器检测到目标节点是 `node_actor`，递增 `iteration` 到 1。

执行器发射 `TopologyEvent.ITERATION_START(iteration=1)`。

---

### 第2轮（迭代1）

#### ⑤ 回到 Actor

**进入时 ReflectionContext**：

```
iteration          = 1
currentReflection  = "根因: off-by-one错误...修改方案: endIdx = i - 1;..."   ← 有内容！
currentIssues      = [Issue("Test 1 failed. expected=bcde actual=cdef")]     ← 有内容！
outputs            = ["...Bug版..."]
```

**Actor 内部执行步骤**：

步骤1 — 检测到 `currentReflection` 和 `currentIssues` 非空，注入到 system prompt：

```
ReActActor.execute(ctx):
    systemPrompt = "你是一个Java编程专家。请根据用户需求生成完整的可编译Java代码。"

    // ── 反馈注入 ──
    if (ctx.currentReflection != null && !ctx.currentReflection.isEmpty()):
        systemPrompt += "\n\n[上一轮反思]\n" + ctx.currentReflection

    if (ctx.currentIssues != null && !ctx.currentIssues.isEmpty()):
        systemPrompt += "\n\n[上一轮测试失败信息]\n"
        for each issue in ctx.currentIssues:
            systemPrompt += "- 测试: " + issue.description + "\n"

    // ── 最终 systemPrompt ──
    // = """
    //   你是一个Java编程专家。请根据用户需求生成完整的可编译Java代码。
    //
    //   [上一轮反思]
    //   根因: off-by-one错误——endIdx记录的是dp表的行号i（1-based），
    //   而非字符串的字符索引（0-based），导致substring截取位置偏移。
    //   修改方案:
    //   第14行: 将 endIdx = i; 改为 endIdx = i - 1;
    //   第16行: 将 s1.substring(endIdx - maxLen, endIdx)
    //           改为 s1.substring(endIdx - maxLen + 1, endIdx + 1);
    //
    //   [上一轮测试失败信息]
    //   - 测试: Test 1 failed. expected=bcde actual=cdef
    //   """
```

这就是反思信息传递的完整链路：**Reflector 写 `currentReflection` → 执行器递次Actor → Actor 拼接到 system prompt**。

步骤2 — 调 LLM 生成修复代码：

```
chatRequest = ChatRequest.builder()
    .systemPrompt(finalSystemPrompt)  // 含反思反馈
    .messages([Message.user("用Java写一个函数，求两个字符串的最长公共子串。请修复之前的问题。")])
    .tools(toolRegistry.getAllDefinitions())
    .build()

response = reActEngine.execute(chatFacade, chatRequest, toolExecutor)
```

LLM 基于注入的反思信息生成修复后的代码：

```java
public class Solution {
    public static String longestCommonSubstring(String s1, String s2) {
        if (s1 == null || s2 == null || s1.isEmpty() || s2.isEmpty()) {
            return "";
        }
        int m = s1.length(), n = s2.length();
        int[][] dp = new int[m + 1][n + 1];
        int maxLen = 0, endIdx = 0;
        for (int i = 1; i <= m; i++) {
            for (int j = 1; j <= n; j++) {
                if (s1.charAt(i - 1) == s2.charAt(j - 1)) {
                    dp[i][j] = dp[i - 1][j - 1] + 1;
                    if (dp[i][j] > maxLen) {
                        maxLen = dp[i][j];
                        endIdx = i - 1;  // 修复：0-based索引
                    }
                }
            }
        }
        return s1.substring(endIdx - maxLen + 1, endIdx + 1);
    }
}
```

步骤3 — 写入 ReflectionContext：

```
ctx.outputs.add(response.content)
ctx.currentOutput = response.content
```

**Actor 执行后 ReflectionContext**：

```
outputs            = ["...Bug版...", "...修复版..."]
currentOutput      = "public class Solution { ...修复版... }"
evaluations        = [{isSuccess:false, ...}]
currentReflection  = "根因: off-by-one错误..."   ← 保持不变
currentIssues      = [Issue("Test 1 failed...")] ← 保持不变
```

执行器匹配 `Edge1(ALWAYS)`，游标移到 `node_evaluator`。

---

#### ⑥ Evaluator 再次执行

步骤同第一轮：写入代码 → 运行 `javac && java -ea`。

```
exitCode: 0
stdout: ALL_TESTS_PASSED
stderr: (空)
```

机械解析：

```
exitCode == 0 → isSuccess = true
stdout 匹配 "ALL_TESTS_PASSED" → 确认通过
issues = []   // 无失败信息可提取
score = 1.0
```

```
Evaluation {
    isSuccess  = true,
    score      = 1.0,
    rawOutput  = "ALL_TESTS_PASSED",
    issues     = []
}
```

**Evaluator 写入**：

```
ctx.evaluations.add(eval)  // → evaluations = [{isSuccess:false}, {isSuccess:true}]
ctx.lastEvaluation = eval  // → {isSuccess:true}
ctx.currentIssues  = eval.issues  // → []
```

**Evaluator 执行后 ReflectionContext**：

```
outputs            = ["...Bug版...", "...修复版..."]
currentOutput      = "...修复版..."
evaluations        = [{isSuccess:false, score:0.0}, {isSuccess:true, score:1.0}]
lastEvaluation     = {isSuccess:true, score:1.0}
currentIssues      = []      ← 空列表
currentReflection  = "根因: off-by-one错误..."
```

执行器匹配 `Edge2(ALWAYS)`，游标移到 `node_router`。

---

#### ⑦ Router 执行

```
ThresholdRouter.execute(ctx, iteration=1, maxIterations=3):
    eval = ctx.lastEvaluation   // → {isSuccess:true}

    if (eval.isSuccess):        // → true
        return RouteDecision.STOP
```

执行器处理：`result == STOP` → 匹配 `Edge5(ON_SUCCESS)` → 游标移到 `STOP`。

---

### 拓扑终止

`STOP` 在 `exitNodeIds` 中。TopologyExecutor 跳出主循环。

```
ctx.finalOutput = ctx.currentOutput   // → "...修复版..."
ctx.attributes["totalIterations"] = 2
ctx.attributes["scores"] = [0.0, 1.0]
```

发射 `TopologyEvent.TOPOLOGY_END`：

```
TOPOLOGY_END: {
    finalOutput: "public class Solution { ...修复版... }",
    totalIterations: 2,
    scores: [0.0, 1.0],
    totalDurationMs: 4500
}
```

ReflectionTopologyStage 收到事件后，将 `finalOutput` 作为 SSE "message" 发射，`scores` 回写到 `agentContext.getReflectScoreRef()`。

---

## 三-B、补充场景：代码正常运行但输出错误（OUTPUT_DIFF 模式）

上面的演示中，LCS 代码的 bug 触发了 Java 断言失败（`AssertionError`），导致 `exitCode=1`。但**不是所有代码错误都伴随非零退出码**。以下场景演示代码正常退出（`exitCode=0`）但输出结果错误的情况，以及 `OUTPUT_DIFF` 模式如何正确捕获。

### 场景设定

同一个 LCS 任务，但验证方式不同。用户没有提供测试类，而是提供了一个**预期输出文件** (`expected_output.txt`)，内容是程序应该输出的标准答案：

```
expected_output.txt:
Test case 1: s1=abcdef, s2=zbcde → LCS=bcde
Test case 2: s1=, s2=abc → LCS=
Test case 3: s1=abc, s2=def → LCS=
Test case 4: s1=hello, s2=hallo → LCS=llo
```

Actor 生成的代码编译通过，运行无异常，退出码为 0。但代码中有一个 bug（比如 `endIdx = i` 而非 `endIdx = i - 1`），导致输出为：

```
程序 stdout:
Test case 1: s1=abcdef, s2=zbcde → LCS=cdef    ← 错误！
Test case 2: s1=, s2=abc → LCS=                ← 正确
Test case 3: s1=abc, s2=def → LCS=             ← 正确
Test case 4: s1=hello, s2=hallo → LCS=llo      ← 正确
exitCode: 0
```

### 如果用 EXIT_CODE 模式会发生什么

```
eval.isSuccess = (exitCode == 0)   // → true  ← 误判！
eval.score = 1.0
eval.issues = []
```

**结论：EXIT_CODE 模式会误判为成功**。Reflector 不会收到任何反思触发，错误代码被当作正确结果返回给用户。

### OUTPUT_DIFF 模式的正确行为

**Evaluator 节点配置**：
```
node_evaluator → PrimitiveType.EVALUATOR, implName="toolVerifierEvaluator",
    config={
        verificationType: "OUTPUT_DIFF",
        command: "cd /tmp/solution && javac Solution.java && java Solution",
        expectedOutputFile: "/tmp/solution/expected_output.txt",
        toleranceMode: "EXACT"
    }
```

**Evaluator 内部执行**：

```
ToolVerifierEvaluator.execute(ctx):
    code = ctx.currentOutput  // Actor的输出代码

    // 步骤1: 写入代码文件
    Files.writeString(Path.of("/tmp/solution/Solution.java"), code)

    // 步骤2: 写入预期输出文件（如果尚未存在）
    // expected_output.txt 由用户或任务配置预先提供

    // 步骤3: 编译+运行
    process = Runtime.getRuntime().exec("cd /tmp/solution && javac Solution.java && java Solution")
    exitCode = process.waitFor()
    stdout  = readStream(process.getInputStream())  // 程序的标准输出
    stderr  = readStream(process.getErrorStream())

    // exitCode = 0  ← 代码正常运行！

    // 步骤4: OUTPUT_DIFF 模式解析 — 不检查 exitCode，比对 stdout 与预期输出
    verificationType = config.verificationType  // → "OUTPUT_DIFF"
```

**OUTPUT_DIFF 模式的核心解析逻辑**：

```
parseResult_OUTPUT_DIFF(exitCode, stdout, stderr):

    Evaluation eval = new Evaluation()
    eval.rawOutput = stdout + "\n" + stderr

    // 注意：不检查 exitCode！exitCode==0 不代表输出正确

    // 步骤1: 编译错误检查（与 TEST_SUITE 相同）
    if (stderr 匹配 "cannot find symbol|.*error:|..."):
        eval.isSuccess = false
        eval.issues.add(Issue{category: "COMPILE_ERROR", ...})
        return eval

    // 步骤2: 读取预期输出
    expectedOutput = Files.readString(config.expectedOutputFile)
    // → "Test case 1: s1=abcdef, s2=zbcde → LCS=bcde\nTest case 2: ..."

    // 步骤3: 逐行比对
    expectedLines = expectedOutput.split("\n")
    actualLines   = stdout.split("\n")
    diffs = []

    for i in range(0, max(expectedLines.length, actualLines.length)):
        expectedLine = i < expectedLines.length ? expectedLines[i] : "(missing)"
        actualLine   = i < actualLines.length   ? actualLines[i]   : "(missing)"

        if (config.toleranceMode == "EXACT"):
            match = expectedLine.equals(actualLine)
        elif (config.toleranceMode == "IGNORE_WHITESPACE"):
            match = expectedLine.trim().equals(actualLine.trim())
        elif (config.toleranceMode == "REGEX"):
            match = Pattern.matches(expectedLine, actualLine)

        if (!match):
            diffs.add(Diff{lineNum: i+1, expected: expectedLine, actual: actualLine})

    // 步骤4: 根据比对结果设置 isSuccess
    eval.isSuccess = diffs.isEmpty()

    if (!eval.isSuccess):
        for diff in diffs:
            eval.issues.add(Issue{
                severity: CRITICAL,
                category: "OUTPUT_MISMATCH",
                description: "Line " + diff.lineNum + " mismatch. expected="
                           + diff.expected + " actual=" + diff.actual
            })

    // 步骤5: 评分 = 匹配行数 / 总行数
    totalLines = max(expectedLines.length, actualLines.length)
    matchedLines = totalLines - diffs.size()
    eval.score = matchedLines / (double) totalLines  // → 3/4 = 0.75

    return eval
```

**本次解析结果**：

```
Evaluation {
    isSuccess  = false,          ← 由逐行比对得出（第1行不匹配），exitCode==0 被忽略
    score      = 0.75,           ← 4行中3行正确
    rawOutput  = "Test case 1: ... → LCS=cdef\nTest case 2: ...",
    issues     = [
        Issue {
            severity    = CRITICAL,
            category    = "OUTPUT_MISMATCH",
            description = "Line 1 mismatch. expected=Test case 1: s1=abcdef, s2=zbcde → LCS=bcde actual=Test case 1: s1=abcdef, s2=zbcde → LCS=cdef"
        }
    ]
}
```

**isSuccess=false → Router 返回 RETRY → Reflector 被触发**。Reflector 拿到 `ctx.currentIssues`（第1行不匹配的差异信息）+ `ctx.currentOutput`（完整代码），调用 LLM 分析为什么输出是 "cdef" 而非 "bcde"，定位到 `endIdx` 的 off-by-one 错误。

### 模式对比总结

| 场景 | EXIT_CODE 模式 | TEST_SUITE 模式 | OUTPUT_DIFF 模式 |
|------|---------------|----------------|-----------------|
| 编译失败 (exitCode≠0) | ✅ 正确判定失败 | ✅ 正确判定失败 | ✅ 正确判定失败 |
| 测试断言失败 (exitCode≠0) | ✅ 正确判定失败 | ✅ 正确判定失败 | N/A（无测试用例） |
| 代码正常运行但输出错误 (exitCode=0) | **❌ 误判为成功** | N/A（无测试用例） | ✅ 通过比对捕获 |
| 代码正常运行且输出正确 (exitCode=0) | ✅ 正确判定成功 | ✅ 正确判定成功 | ✅ 正确判定成功 |
| 部分正确（exitCode=0，4中3对） | **❌ 误判为成功** | N/A | ✅ score=0.75, isSuccess=false |

**设计结论**：`EXIT_CODE` 模式仅适用于编译检查、lint 等"零容忍错误"场景。对于代码生成任务，应用层应默认使用 `TEST_SUITE` 或 `OUTPUT_DIFF` 模式。框架可以在拓扑模板中为不同任务类型预置合适的验证模式。

---

## 四、ReflectionContext 完整变化轨迹

```
┌─────────────────────────────────────────────────────────────────────────────┐
│ 时刻              │ iteration │ outputs  │ evals   │ refs   │ curRefl  │ curIssues │
├───────────────────┼───────────┼──────────┼─────────┼────────┼──────────┼───────────┤
│ 拓扑开始           │     0     │    []    │   []    │  []    │  null    │   null    │
│ Actor#1 之后       │     0     │  [Bug]   │   []    │  []    │  null    │   null    │
│ Evaluator#1 之后   │     0     │  [Bug]   │ [fail]  │  []    │  null    │ [Issue]   │
│ Router#1 之后      │     0     │  [Bug]   │ [fail]  │  []    │  null    │ [Issue]   │
│ Reflector#1 之后   │     0     │  [Bug]   │ [fail]  │ [反思] │ "根因:.."│ [Issue]   │
│ Actor#2 之后       │     1     │[Bug,Fix]│ [fail]  │ [反思] │ "根因:.."│ [Issue]   │
│ Evaluator#2 之后   │     1     │[Bug,Fix]│[fail,ok]│[反思] │ "根因:.."│    []     │
│ Router#2→STOP      │     1     │[Bug,Fix]│[fail,ok]│[反思] │ "根因:.."│    []     │
│ 拓扑结束           │     2     │[Bug,Fix]│[fail,ok]│[反思] │ "根因:.."│    []     │
└─────────────────────────────────────────────────────────────────────────────┘

字段缩写:
  outputs  = outputs列表（追加）
  evals    = evaluations列表（追加）
  refs     = reflections列表（追加）
  curRefl  = currentReflection（覆盖）
  curIssues = currentIssues（覆盖）
```

**关键观察**：
- `outputs`、`evaluations`、`reflections` 是追加模式（列表越来越长），保留完整历史
- `currentOutput`、`currentReflection`、`currentIssues`、`lastEvaluation` 是覆盖模式（只留最新值），作为下一轮Actor的快捷读取入口
- Evaluator 不改 `currentReflection`，Reflector 不改 `currentIssues`，Actor 不改这两个——职责分离清晰
- 两个反馈路径同时存在：Actor#2 读取了 `currentReflection`（Reflector的文字建议）和 `currentIssues`（Evaluator的结构化问题）两者

---

## 五、SSE 事件流（客户端视角）

```
客户端收到的完整 SSE 序列:

1. event: topology_start      data: {"topology":"voyager","agentId":"java-coder"}
2. event: iteration_start     data: {"iteration":0}
3. event: node_start          data: {"nodeId":"node_actor","type":"ACTOR"}
4. event: message             data: "public class Solution {\n    public static String longestCom..."
   event: message             data: "monSubstring(String s1, String s2) {\n        if..."
   (ReActActor 流式输出，每个chunk一个message事件)
5. event: node_end            data: {"nodeId":"node_actor","durationMs":2200}
6. event: node_start          data: {"nodeId":"node_evaluator","type":"EVALUATOR"}
7. event: evaluation          data: {"score":0.0,"isSuccess":false,"issues":[{"severity":"CRITICAL","category":"TEST_FAILURE","description":"Test 1 failed. expected=bcde actual=cdef"}]}
8. event: node_end            data: {"nodeId":"node_evaluator","durationMs":800}
9. event: node_start          data: {"nodeId":"node_router","type":"ROUTER"}
10. event: node_end           data: {"nodeId":"node_router","decision":"RETRY","durationMs":1}
11. event: node_start         data: {"nodeId":"node_reflector","type":"REFLECTOR"}
12. event: reflection_feedback data: "根因: off-by-one错误——endIdx记录的是dp表的行号i（1-based），而非字符串的字符索引（0-based）。修改: 第14行 endIdx=i-1; 第16行 substring(endIdx-maxLen+1, endIdx+1);"
13. event: node_end           data: {"nodeId":"node_reflector","durationMs":1500}
14. event: iteration_start    data: {"iteration":1}
15. event: node_start         data: {"nodeId":"node_actor","type":"ACTOR"}
16. event: message            data: "public class Solution {\n    public static String longestCom..."
17. event: node_end           data: {"nodeId":"node_actor","durationMs":1800}
18. event: node_start         data: {"nodeId":"node_evaluator","type":"EVALUATOR"}
19. event: evaluation         data: {"score":1.0,"isSuccess":true,"issues":[]}
20. event: node_end           data: {"nodeId":"node_evaluator","durationMs":600}
21. event: node_start         data: {"nodeId":"node_router","type":"ROUTER"}
22. event: node_end           data: {"nodeId":"node_router","decision":"STOP","durationMs":1}
23. event: done               data: {"output":"public class Solution { ...修复版... }","iterations":2,"finalScore":1.0,"scores":[0.0,1.0],"totalDurationMs":6901}
```

客户端 UI 可以基于这些事件实时渲染：
- `iteration_start` → 显示"第N轮尝试中..."
- `message` → 流式展示 LLM 输出的代码
- `evaluation` → 显示测试结果绿/红标识
- `reflection_feedback` → 展开显示修复分析过程
- `done` → 最终结果 + 执行摘要

---

## 六、对比：有反思 vs 无反思

**无反思（passthrough 拓扑：Actor → STOP）**：

```
客户端体验:
  发送请求 → 等待 → 收到Bug代码 → 复制到IDE → 运行测试 → 发现失败
  → 手动阅读代码 → 分析根因 → 修改代码 → 重新运行 → 通过
  总耗时: 人工介入 5-10 分钟
```

**有反思（本演示的 Voyager 拓扑）**：

```
客户端体验:
  发送请求 → 等待 ~7秒 → 收到已验证通过的代码
  中间自动完成: 生成代码 → 编译测试 → 发现失败 → LLM分析根因
  → 生成修复方案 → 重新生成代码 → 测试通过 → 返回
  总耗时: ~7秒（无人介入）
```

---

## 七、本演示验证的设计能力

| 设计点 | 演示中的体现 |
|--------|------------|
| **共享内存传递反思** | `ReflectionContext` 同一实例在四个原语间传递，Reflector 写 `currentReflection`，Actor 下一轮读它 |
| **Actor 多路径读取反馈** | Actor#2 同时读取 `currentReflection`（Reflector文字建议）和 `currentIssues`（Evaluator结构化Issue） |
| **追加 vs 覆盖语义** | `outputs`/`evaluations`/`reflections` 追加保留历史，`currentReflection`/`currentIssues` 覆盖保留最新 |
| **ToolVerifierEvaluator 机械解析** | 纯正则提取 testResult/failedTest，不做根因分析 |
| **TEST_SUITE 验证模式** | `isSuccess` 由测试报告结构化解剖得出，不依赖 `exitCode==0`，避免"代码正常运行但结果错误"的误判 |
| **OUTPUT_DIFF 验证模式** | 逐行比对 stdout 与预期输出，捕获 `exitCode==0` 但输出结果错误的情况（三-B 补充场景） |
| **Reflector 调LLM做根因分析** | 传入完整代码+测试失败信息，LLM逐行推演代码定位Bug |
| **Router 条件分流** | 读 `lastEvaluation.isSuccess`，匹配 `ON_FAIL`/`ON_SUCCESS` 边 |
| **TopologyExecutor 边匹配** | 遍历出边，按 `EdgeCondition` + `ctx.lastEvaluation` 匹配第一条满足条件的边 |
| **反馈注入到 system prompt** | Actor 内 `if currentReflection != null` → 拼接到 system prompt 末尾 |
| **SSE 事件完整链路** | 从 `topology_start` 到 `done`，每个节点、每轮迭代有对应事件 |
| **无记忆依赖** | 拓扑不含 Memory 节点，反思闭环完全自包含 |
| **全动态拓扑生成就绪** | 拓扑定义 JSON 可由 AI 生成；`PrimitiveCatalog` 提供零件清单；`AgentRegistry` 支持运行时 Agent 注册 |
