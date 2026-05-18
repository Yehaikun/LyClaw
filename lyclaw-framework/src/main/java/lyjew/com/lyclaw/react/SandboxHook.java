package lyjew.com.lyclaw.react;

import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import lyjew.com.lyclaw.action.tool.ToolSandbox;
import lyjew.com.lyclaw.model.ToolCall;
import lyjew.com.lyclaw.security.SandboxLevel;
import lyjew.com.lyclaw.tool.Tool;
import lyjew.com.lyclaw.tool.ToolExecutionResult;

/**
 * 沙箱隔离 Hook，将 ToolExecutor 的执行委托给 ToolSandbox。
 *
 * <p>order=20，在安全检查之后、审批之前执行。
 * 从 AgentContext 中读取安全审核分配的 sandboxLevel。
 */
public class SandboxHook implements AgentHook {

    private static final Logger log = LoggerFactory.getLogger(SandboxHook.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();

    private final ToolSandbox toolSandbox;

    public SandboxHook(ToolSandbox toolSandbox) {
        this.toolSandbox = toolSandbox;
    }

    @Override
    public int getOrder() { return 20; }

    @Override
    public ToolExecutor wrapToolExecutor(ToolExecutor inner, AgentContext ctx) {
        if (toolSandbox == null) {
            return inner;
        }
        return (toolName, toolCallId, argumentsJson) -> {
            Tool tool = ctx.getToolRegistry().get(toolName);
            if (tool == null) {
                // 静态注册表找不到，回退到内部执行器
                return inner.execute(toolName, toolCallId, argumentsJson);
            }
            try {
                Map<String, Object> args = argumentsJson != null && !argumentsJson.isEmpty()
                        ? objectMapper.readValue(argumentsJson, new TypeReference<Map<String, Object>>() {})
                        : Map.of();
                SandboxLevel level = ctx.getSandboxLevel() != null
                        ? ctx.getSandboxLevel() : SandboxLevel.DIRECT;
                ToolExecutionResult result = toolSandbox.execute(tool, args, level);
                if (result.isSuccess()) {
                    return result.getResult() != null ? result.getResult() : "";
                }
                log.warn("沙箱执行失败: tool={} error={}", toolName, result.getError());
                return "Error: " + (result.getError() != null ? result.getError() : "unknown");
            } catch (Exception e) {
                log.error("沙箱执行异常: tool={}", toolName, e);
                return "Error: " + e.getMessage();
            }
        };
    }
}
