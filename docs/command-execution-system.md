# LyClaw 命令执行与沙箱系统架构文档

## 1. 整体架构概览

命令执行系统跨越 LyClaw 的多个模块，从用户输入到最终 Shell 进程执行，经过 **安全审批 → 沙箱路由 → 命令执行** 三阶段。

```
用户消息
  │
  ▼
┌──────────────────────────────────────────────────────────────┐
│  Pipeline 流水线 (lyclaw-orchestration)                      │
│                                                              │
│  ┌─────────────────────┐     ┌─────────────────────────────┐ │
│  │ SecurityCheckStage  │ ──▶ │      RespondStage           │ │
│  │ (安全审批 & 沙箱级别) │     │ (ReAct 引擎 & 工具调用转发) │ │
│  └─────────────────────┘     └──────────┬──────────────────┘ │
└─────────────────────────────────────────┼────────────────────┘
                                          │ Feign HTTP 调用
                                          ▼
┌──────────────────────────────────────────────────────────────┐
│  Action 服务 (lyclaw-action)                                  │
│                                                              │
│  ┌────────────────┐  ┌──────────────────┐  ┌──────────────┐ │
│  │ ActionController│─▶│ActionExecutorImpl│─▶│ToolSandboxImpl│ │
│  │ (REST 端点)     │  │ (调度 & 策略检查) │  │ (沙箱路由)    │ │
│  └────────────────┘  └──────────────────┘  └──────┬───────┘ │
│                                                    │         │
│                          ┌─────────────────────────┤         │
│                          │ DIRECT    │ SANDBOX     │ PROCESS │
│                          ▼           ▼             ▼        │
│                    当前线程直接   守护线程+      独立OS进程    │
│                    执行(只读)   临时目录隔离    (command)     │
│                                                     │       │
│                                                     ▼       │
│                                            ┌──────────────┐ │
│                                            │CommandExecutor│ │
│                                            │ (sh -c 执行)  │ │
│                                            └──────────────┘ │
└──────────────────────────────────────────────────────────────┘
```

---

## 2. 安全审批与沙箱级别判定

### 2.1 SecurityCheckStage（编排模块）

**文件**: `lyclaw-orchestration/.../stage/SecurityCheckStage.java`

在流水线的第一阶段执行，职责：
1. 调用 `EnhancedSecurityManager.approve(context, "EXECUTE_CHAT")` 进行权限审批
2. 从审批结果中提取 `SandboxLevel`，写入 `ChatContext` 属性 `"sandboxLevel"`（字符串形式）
3. 审批未通过则直接终止流水线

关键代码（第114-115行）：
```java
if (approvalResult.getSandboxLevel() != null) {
    context.setAttribute("sandboxLevel", approvalResult.getSandboxLevel().name());
}
```

### 2.2 EnhancedSecurityManager（基础设施层）

**文件**: `lyclaw-infra/.../security/EnhancedSecurityManager.java`

权限级别（PermissionLevel）到沙箱级别（SandboxLevel）的映射：

| PermissionLevel | SandboxLevel | 说明 |
|-----------------|-------------|------|
| READ | DIRECT | 只读操作，当前线程直接执行 |
| EXECUTE_SAFE | SANDBOX | 安全执行，守护线程+临时目录隔离 |
| EXECUTE_MODIFY | SANDBOX | 修改操作，守护线程+临时目录隔离 |
| EXECUTE_DESTRUCTIVE | PROCESS | 破坏性操作，独立OS进程执行 |
| DENY | PROCESS | 已拒绝（不应执行） |
| ADMIN | DIRECT | 管理员，直接执行 |

默认工具权限预设（第74-86行）：
```java
toolPermissions.put("ExecuteCommand", PermissionLevel.EXECUTE_SAFE);
// EXECUTE_SAFE → SandboxLevel.SANDBOX
```

**注意**：`EXECUTE_SAFE` 映射到 `SANDBOX`，而不是 `PROCESS`。但 `command` 工具的执行最终由 `ToolSandboxImpl` 的 `executeProcess()` 根据工具名是 `"command"` 来特殊处理。

---

## 3. SandboxLevel 枚举

**文件**: `lyclaw-framework/.../security/SandboxLevel.java`

```java
public enum SandboxLevel {
    DIRECT,    // 当前线程直接执行，无隔离
    SANDBOX,   // 守护线程 + 临时工作目录隔离
    PROCESS    // 独立 OS 进程执行 (command/script 工具)
}
```

---

## 4. 工具执行路由（RespondStage → Action 服务）

### 4.1 RespondStage 的 ToolExecutor 桥接

**文件**: `lyclaw-orchestration/.../stage/RespondStage.java` (第144-178行)

RespondStage 构建一个 `ToolExecutor` lambda，作为 ReAct 引擎与远程 Action 服务之间的桥梁：

```java
ToolExecutor toolExecutor = (toolName, toolCallId, arguments) -> {
    // 1. 解析 LLM 返回的 arguments JSON 为 Map
    Map<String, Object> args = objectMapper.readValue(arguments, ...);

    // 2. 从 context 读取 SecurityCheckStage 写入的沙箱级别
    String sandboxLevel = (String) context.getAttribute("sandboxLevel");

    // 3. 构建远程调用请求
    ToolExecuteRequest execReq = ToolExecuteRequest.builder()
            .toolName(toolName)
            .args(args)
            .sessionId(request.getSessionId())
            .sandboxLevel(sandboxLevel)    // 字符串形式传递
            .build();

    // 4. 通过 Feign 调用远程 Action 服务
    ToolExecutionResult result = actionFeignClient.executeTool(execReq);
    return result.isSuccess() ? result.getResult() : "Error: " + result.getError();
};
```

### 4.2 ToolExecuteRequest DTO

**文件**: `lyclaw-framework/.../action/ToolExecuteRequest.java`

```java
public class ToolExecuteRequest {
    private String toolName;       // 工具名，如 "command"
    private Map<String, Object> args;   // 参数键值对，如 {"command": "ls -la"}
    private String sandboxLevel;   // 沙箱级别字符串：DIRECT/SANDBOX/PROCESS
    private String sessionId;      // 会话标识
}
```

**关键**：`sandboxLevel` 是 String 类型，传递到 Action 服务后再解析回 `SandboxLevel` 枚举。

### 4.3 ReAct 引擎启动

RespondStage 将 `ToolExecutor` 和 `toolDefs`（所有已注册工具的 JSON Schema 定义）传给 `ReActEngine.executeStream()`：

```java
request.setTools(toolDefs);       // 工具定义列表 → LLM 的 function calling
request.setToolChoice("auto");    // LLM 自行决定是否调用工具
return reActEngine.executeStream(chatFacade, request, toolExecutor);
```

---

## 5. ReAct 引擎（推理-行动循环）

**文件**: `lyclaw-framework/.../react/DefaultReActEngine.java`

### 5.1 流式执行流程（executeStream）

```
Round 0: stream=true 探测
  ├── 纯文本 → 直接逐 token SSE 推送给前端
  ├── 检测到 tool_calls → 收集流式碎片 → mergeChunks
  │     └── 进入多轮模式
  └── 思考内容 → 缓冲，可能后续转为工具调用

Round 1..MAX_TOOL_ROUNDS-1 (多轮模式):
  ├── 解析 LLM 返回的 tool_calls
  ├── 对每个 tool_call，调用 ToolExecutor (RespondStage 提供的 lambda)
  │     └── Feign → Action 服务 → ToolSandbox → 执行
  ├── 将工具执行结果以 tool 消息形式追加到消息列表
  ├── 设置 stream=false，再次调用 LLM（含工具结果）
  ├── 若 LLM 再次返回 tool_calls → 继续下一轮
  └── 若 LLM 返回纯文本 → 结束循环，SSE 推送给前端
```

### 5.2 流式碎片的合并（mergeChunks）

**文件**: `lyclaw-framework/.../chat/ChatModel.java` (第51-81行)

流式模式下，LLM 的工具调用参数分多个 SSE chunk 到达。`mergeChunks` 方法：
1. 遍历所有 chunk
2. 相同 `toolCallId` 的 chunk，通过 `appendArguments()` 拼接 arguments
3. 无 ID 的 chunk，追加到最后一条已知的 tool call

### 5.3 appendArguments 的 JSON 拼接

**文件**: `lyclaw-framework/.../model/ModelResponse.java` (第107-124行)

```java
public void appendArguments(String argsFragment) {
    // 首次到达：直接赋值
    if (this.arguments == null || this.arguments.isEmpty()) {
        this.arguments = argsFragment;
        return;
    }
    // 后续追加：去掉前一段末尾的 '}' 和当前片段开头的 '{'
    // 注意：不使用 trim()，避免丢失字符串值内部的空格（如 "ls -d" 中的空格）
    if (base.endsWith("}")) base = base.substring(0, base.length() - 1);
    if (frag.startsWith("{")) frag = frag.substring(1);
    this.arguments = base + frag;
}
```

**设计要点**：不使用 `trim()`，避免误删 "ls -d ~/*/" 中 `ls` 和 `-d` 之间的空格。

---

## 6. Action 服务端（接收 & 调度）

### 6.1 ActionController

**文件**: `lyclaw-action/.../controller/ActionController.java`

```java
@PostMapping("/execute-tool")
public Mono<ToolExecutionResult> executeTool(@RequestBody ToolExecuteRequest request) {
    // 1. 解析沙箱级别字符串 → SandboxLevel 枚举
    SandboxLevel level = SandboxLevel.DIRECT;
    if (request.getSandboxLevel() != null && !request.getSandboxLevel().isBlank()) {
        level = SandboxLevel.valueOf(request.getSandboxLevel().toUpperCase());
    }

    // 2. 异步执行工具
    return Mono.fromFuture(actionExecutor.executeTool(
            request.getToolName(),
            request.getArgs() != null ? request.getArgs() : Map.of(),
            level));
}
```

### 6.2 ActionExecutorImpl

**文件**: `lyclaw-action/.../impl/ActionExecutorImpl.java`

核心调度流程（第139-191行）：
```
executeTool(toolName, args, level)
  │
  ├── 1. toolRegistry.get(toolName)      // 查找工具
  ├── 2. toolCallPolicy.canExecute()     // 策略检查
  ├── 3. toolSandbox.execute(tool, args, level)  // 沙箱执行
  └── 4. 返回结果
```

全部在 `executorService`（4线程守护线程池）中异步执行。

---

## 7. 工具沙箱系统（核心）

### 7.1 ToolSandboxImpl

**文件**: `lyclaw-action/.../impl/ToolSandboxImpl.java`

三种执行模式的路由：

```java
public ToolExecutionResult execute(Tool tool, Map<String, Object> args, SandboxLevel level) {
    // 健康检查
    if (!healthy.get()) return failure("沙箱不可用");

    // 将 Map 参数序列化为 JSON → ToolCall 对象
    ToolCall toolCall = buildToolCall(tool.getName(), args);

    // 按沙箱级别分发
    return switch (level) {
        case DIRECT  → executeDirect(tool, toolCall, startTime);
        case SANDBOX → executeSandbox(tool, toolCall, startTime);
        case PROCESS → executeProcess(tool, toolCall, args, startTime);
    };
}
```

### 7.2 DIRECT 模式

- **当前线程直接执行**
- 无任何隔离措施
- 用于 `calculator`、`current_time`、`web_search` 等只读工具
- 实现：`executeDirect()` 直接调用 `tool.execute(toolCall, null)`

### 7.3 SANDBOX 模式

- **守护线程 + 临时工作目录隔离**
- 隔离机制：
  1. 创建临时目录 `lyclaw-sandbox-` 前缀
  2. 切换到临时目录（修改 `user.dir` 系统属性）
  3. 在 2 线程的 `sandboxExecutor` 守护线程池中执行
  4. 执行完毕后恢复 `user.dir`
  5. 递归删除临时目录
- 超时：30秒（`DEFAULT_TIMEOUT_SECONDS`）
- 用于可能有文件写入但不需要系统级别隔离的工具

### 7.4 PROCESS 模式

- **独立 OS 进程执行**
- 仅对 `toolName == "command"` 且 `args` 中包含 `"command"` 键的工具使用进程隔离
- 其他工具降级到 SANDBOX
- 实现：

```java
private ToolExecutionResult executeProcess(Tool tool, ToolCall toolCall,
                                    Map<String, Object> args, long startTime) {
    if ("command".equals(tool.getName()) && args.containsKey("command")) {
        return executeCommandInProcess(tool.getName(),
                (String) args.get("command"), startTime);
    }
    // 其他工具降级
    return executeSandbox(tool, toolCall, startTime);
}
```

**关键**：`executeCommandInProcess` 从 `args.get("command")` 提取命令字符串，委托给 `CommandExecutor.execute()`。这里命令已经是从 JSON arguments 中解析出的完整字符串（如 `"ls -la"`，来自 `{"command": "ls -la"}`）。

---

## 8. 注解驱动工具的参数解析

### 8.1 AnnotatedToolAdapter

**文件**: `lyclaw-autoconfigure/.../binding/AnnotatedToolAdapter.java`

将标注 `@Tool` 注解的 POJO 适配为 `Tool` 接口：

```java
public ToolExecutionResult execute(ToolCall toolCall, ChatContext context) {
    // 1. 将 ToolCall 的 arguments JSON 解析为 Map
    Map<String, Object> args = ParameterBinder.bindToMap(toolCall.getArguments());

    // 2. 将命名参数绑定到方法形参，反射调用
    Object result = bindingDescriptor.bindAndInvoke(target, args);
    return success(result);
}
```

### 8.2 ParameterBinder

**文件**: `lyclaw-autoconfigure/.../binding/ParameterBinder.java`

使用 Jackson `ObjectMapper.readValue(json, Map.class)` 将 JSON 字符串解析为 `Map<String, Object>`。

**标准 JSON 解析不会丢失字符串中的空格**。如果 JSON 是 `{"command": "ls -la"}`, 则 `map.get("command")` = `"ls -la"`（空格保留）。

### 8.3 ParameterBindingDescriptor

**文件**: `lyclaw-autoconfigure/.../binding/ParameterBindingDescriptor.java`

将命名参数 Map 映射到方法参数：
1. 遍历方法参数列表
2. 读取 `@Param(name=...)` 注解确定参数名
3. 按名称从 Map 取值
4. 类型转换（String→Enum, Number→int 等）
5. `method.invoke(target, paramValues)` 反射调用

---

## 9. 命令工具（AnnotatedCommandTool）

**文件**: `lyclaw-action/.../tool/AnnotatedCommandTool.java`

```java
@Tool(name = "command",
      description = "在本机环境中执行系统命令，注意不要执行危险命令",
      readonly = false,
      group = "builtin")
public class AnnotatedCommandTool {

    public String execute(
        @Param(name = "command", description = "要执行的shell命令")
        String command
    ) {
        if (command == null || command.isBlank()) {
            throw ToolExecuteException.of("command", "命令为空");
        }

        CommandExecutor.CommandResult cr = CommandExecutor.execute(
                command, TIMEOUT_SECONDS, MAX_OUTPUT_LENGTH);

        if (cr.timedOut()) {
            throw ToolExecuteException.of("command", "命令执行超时");
        }
        if (cr.isSuccess()) {
            return cr.output().isEmpty() ? "命令执行成功，无输出" : cr.output();
        }
        throw ToolExecuteException.of("command",
                "退出码 " + cr.exitCode() + ": " + cr.output());
    }
}
```

**执行路径**：
1. LLM 决定调用 `command` 工具，JSON: `{"command": "ls -la"}`
2. `ParameterBinder.bindToMap()` 解析 → `{"command": "ls -la"}`
3. `ParameterBindingDescriptor.bindAndInvoke()` → `execute("ls -la")`
4. 内部委托给 `CommandExecutor.execute("ls -la", 30, 10000)`

---

## 10. 命令执行器（CommandExecutor）

**文件**: `lyclaw-action/.../util/CommandExecutor.java`

最底层的 Shell 命令执行：

```java
public static CommandResult execute(String command, int timeoutSeconds, int maxOutputLength) {
    // 1. 通过 ProcessBuilder 启动子进程，shell: sh -c <command>
    ProcessBuilder pb = new ProcessBuilder("sh", "-c", command);
    pb.redirectErrorStream(true);  // stderr 合并到 stdout
    Process process = pb.start();

    // 2. 异步消费输出流，防止管道缓冲区写满导致死锁
    CompletableFuture<String> outputFuture = CompletableFuture.supplyAsync(() ->
        readOutput(process, maxOutputLength)
    );

    // 3. 等待进程结束，带超时
    boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
    if (!finished) {
        process.destroyForcibly();  // 超时强制杀死
        return new CommandResult(-1, "", true);
    }

    // 4. 获取输出
    String output = outputFuture.get(5, TimeUnit.SECONDS);
    return new CommandResult(process.exitValue(), output, false);
}
```

**输出截断**（`readOutput` 方法）：
- 按行读取，当累计长度超过 `maxOutputLength` 时附加 `\n...（输出已截断）`
- 使用 UTF-8 编码

**硬编码常量**：
| 常量 | 默认值 | 位置 |
|------|--------|------|
| `TIMEOUT_SECONDS` | 30 | AnnotatedCommandTool |
| `MAX_OUTPUT_LENGTH` | 10000 | AnnotatedCommandTool |
| `DEFAULT_TIMEOUT_SECONDS` | 30 | ToolSandboxImpl |
| `MAX_TOOL_ROUNDS` | 30 | DefaultReActEngine |

---

## 11. AnnotatedScriptTool（脚本执行工具）

**文件**: `lyclaw-action/.../tool/AnnotatedScriptTool.java`

支持 Python、Node.js、Bash 三种脚本语言的执行工具。与 `AnnotatedCommandTool` 不同，它将代码写入临时文件后通过对应解释器执行。

```java
@Tool(name = "execute_script",
      description = "将代码写入临时文件并通过对应解释器执行...",
      readonly = false,
      group = "builtin")
public class AnnotatedScriptTool {
    // 支持的解释器映射
    private static final Map<String, String[]> INTERPRETERS = Map.of(
        "python", new String[]{"python3", ".py"},
        "node",   new String[]{"node", ".js"},
        "bash",   new String[]{"bash", ".sh"}
    );
}
```

**执行流程**：
1. 从 `@Param` 注解的方法参数中提取 `language` 和 `code`
2. 创建临时文件（对应扩展名）
3. 写入代码内容
4. 构造命令：`python3 /tmp/xxx.py` 或 `node /tmp/xxx.js` 或 `bash /tmp/xxx.sh`
5. 委托给 `CommandExecutor.execute(command, TIMEOUT_SECONDS, MAX_OUTPUT_LENGTH)`
6. 删除临时文件

**沙箱级别**：该工具 `readonly = false`，因此 `ToolDefinition.isReadOnly()` 返回 `false`。在 RespondStage 中，这被标记为需要用户审批的工具。在沙箱系统中，它通过 `ToolSandboxImpl.executeProcess()` 被降级到 SANDBOX 模式执行（因为工具名不是 `"command"`）。

---

## 12. 完整调用链路（以 `ls -la` 为例）

```
1. 用户输入: "列出当前目录的文件"
2. Pipeline → SecurityCheckStage
   └── EnhancedSecurityManager.approve("EXECUTE_CHAT")
       └── resolvePermissionLevel("EXECUTE_CHAT") → EXECUTE_SAFE
       └── mapToSandboxLevel(EXECUTE_SAFE) → SANDBOX
       └── context.setAttribute("sandboxLevel", "SANDBOX")

3. Pipeline → RespondStage
   ├── chatRequest.setTools([..., command工具定义, ...])
   ├── chatRequest.setToolChoice("auto")
   ├── 构建 ToolExecutor lambda:
   │     context.getAttribute("sandboxLevel") → "SANDBOX"
   │     ToolExecuteRequest { toolName, args, sandboxLevel="SANDBOX" }
   │     通过 Feign → Action 服务
   └── reActEngine.executeStream(chatFacade, request, toolExecutor)

4. LLM 推理 → 决定调用 command 工具
   └── 生成 function call: { "name": "command", "arguments": {"command": "ls -la"} }

5. ReAct 引擎接收 tool_calls
   └── 每个 tool_call → ToolExecutor.execute("command", callId, '{"command":"ls -la"}')

6. RespondStage 的 ToolExecutor lambda:
   ├── objectMapper.readValue('{"command":"ls -la"}') → {"command": "ls -la"}
   ├── context.getAttribute("sandboxLevel") → "SANDBOX"
   ├── ToolExecuteRequest { toolName:"command", args:{"command":"ls -la"}, sandboxLevel:"SANDBOX" }
   └── actionFeignClient.executeTool(req) → HTTP POST → Action 服务

7. ActionController.executeTool():
   ├── SandboxLevel.valueOf("SANDBOX") → SandboxLevel.SANDBOX
   └── actionExecutor.executeTool("command", {"command":"ls -la"}, SANDBOX)

8. ActionExecutorImpl.executeTool():
   ├── toolRegistry.get("command") → AnnotatedToolAdapter(AnnotatedCommandTool)
   ├── toolCallPolicy.canExecute("command", null) → true
   └── toolSandbox.execute(adapter, {"command":"ls -la"}, SANDBOX)

9. ToolSandboxImpl.execute(tool, args, level=SANDBOX):
   ├── buildToolCall() → ToolCall { name:"command", arguments:'{"command":"ls -la"}' }
   └── switch(SANDBOX) → executeSandbox(tool, toolCall, startTime)

10. executeSandbox():
    ├── sandboxExecutor.submit(() → {
    │     ├── 创建临时目录
    │     ├── 切换 user.dir
    │     ├── tool.execute(toolCall, null)  // 调用适配器
    │     └── 恢复 + 清理
    │   })
    └── future.get(30, SECONDS)

11. AnnotatedToolAdapter.execute(toolCall, context):
    ├── ParameterBinder.bindToMap('{"command":"ls -la"}') → {"command": "ls -la"}
    └── bindingDescriptor.bindAndInvoke(target, args)
        └── method.invoke → AnnotatedCommandTool.execute("ls -la")

12. AnnotatedCommandTool.execute("ls -la"):
    └── CommandExecutor.execute("ls -la", 30, 10000)

13. CommandExecutor:
    ├── new ProcessBuilder("sh", "-c", "ls -la")
    ├── process.start()
    ├── process.waitFor(30, SECONDS)
    └── readOutput → "total 100\ndrwxr-xr-x ..."

14. 结果原路返回:
    CommandResult → AnnotatedCommandTool → AnnotatedToolAdapter
    → ToolSandboxImpl → ActionExecutorImpl → ActionController
    → HTTP Response → RespondStage(ToolExecutor) → ReActEngine
    → LLM 继续推理 → SSE 流式输出最终答案给前端
```

---

## 13. 关键组件速查表

| 组件 | 文件路径 | 角色 |
|------|---------|------|
| SecurityCheckStage | `lyclaw-orchestration/.../stage/SecurityCheckStage.java` | 安全审批，写入 sandboxLevel 到 context |
| EnhancedSecurityManager | `lyclaw-infra/.../security/EnhancedSecurityManager.java` | 权限→沙箱级别映射 |
| RespondStage | `lyclaw-orchestration/.../stage/RespondStage.java` | 构建 ToolExecutor 桥接，启动 ReAct |
| DefaultReActEngine | `lyclaw-framework/.../react/DefaultReActEngine.java` | 流式推理-行动循环 |
| ChatModel.mergeChunks | `lyclaw-framework/.../chat/ChatModel.java` | 合并流式 tool call 碎片 |
| ModelResponse.appendArguments | `lyclaw-framework/.../model/ModelResponse.java` | JSON 参数拼接（保留空格） |
| ToolExecuteRequest | `lyclaw-framework/.../action/ToolExecuteRequest.java` | Feign DTO，携带 sandboxLevel |
| ActionController | `lyclaw-action/.../controller/ActionController.java` | REST 端点，字符串→枚举 |
| ActionExecutorImpl | `lyclaw-action/.../impl/ActionExecutorImpl.java` | 工具调度 & 策略检查 |
| ToolSandboxImpl | `lyclaw-action/.../impl/ToolSandboxImpl.java` | 三模式沙箱路由 |
| AnnotatedCommandTool | `lyclaw-action/.../tool/AnnotatedCommandTool.java` | @Tool 注解的命令工具 |
| AnnotatedScriptTool | `lyclaw-action/.../tool/AnnotatedScriptTool.java` | @Tool 注解的脚本工具 |
| AnnotatedToolAdapter | `lyclaw-autoconfigure/.../binding/AnnotatedToolAdapter.java` | @Tool POJO → Tool 接口适配 |
| ParameterBinder | `lyclaw-autoconfigure/.../binding/ParameterBinder.java` | JSON→Map 参数解析 |
| ParameterBindingDescriptor | `lyclaw-autoconfigure/.../binding/ParameterBindingDescriptor.java` | Map→方法形参绑定 |
| CommandExecutor | `lyclaw-action/.../util/CommandExecutor.java` | ProcessBuilder 底层执行 |
| ToolCallLoop | `lyclaw-action/.../impl/ToolCallLoop.java` | 旧版工具调用循环（当前未被使用） |
| SandboxLevel | `lyclaw-framework/.../security/SandboxLevel.java` | DIRECT/SANDBOX/PROCESS 枚举 |
| OpenAiProtocolChatModel | `lyclaw-framework/.../adapter/OpenAiProtocolChatModel.java` | 解析 LLM 响应的 tool_calls |

---

## 14. 潜在问题与注意事项

### 14.1 沙箱级别被覆盖
虽然 `SecurityCheckStage` 根据安全审批结果设置了 `SANDBOX`（EXECUTE_SAFE → SANDBOX），但 `ToolSandboxImpl.executeProcess()` 中：
- 如果工具名是 `"command"` 且 args 中有 `"command"` 键 → 走 PROCESS（独立进程）
- 其他 → 降级到 SANDBOX

因此 command 工具**实际上以 PROCESS 级别执行**（独立子进程），不受上游传入的 SANDBOX 影响。这是因为 `ToolSandboxImpl` 在 PROCESS 分支中对 `"command"` 工具做了硬编码判断。

### 14.2 SandboxLevel 的传递链路长
SandboxLevel 从 SecurityCheckStage 到最终执行，经历了：
- context attribute（String 存储）
- Feign DTO（String 传递）
- ActionController（String→Enum 解析）

如果 context 中的属性键名不一致（如 `"sandboxLevel"` vs `ConfigKeys.SANDBOX_LEVEL`），会导致取不到值，沙箱级别回退到 DIRECT。

### 14.3 硬编码常量
多个组件存在硬编码常量（超时30秒、输出限制10000字符、最大轮数30等），目前无法通过配置文件动态调整。

### 14.4 ToolCallLoop 已废弃但未移除
`ToolCallLoop` 类有完整的工具调用循环实现（包括 `@Component` 注册为 Spring Bean），但观察代码后，RespondStage 已改用 `DefaultReActEngine` + `ToolExecutor` lambda 模式，ToolCallLoop 不再被调用。该类可能成为死代码。
