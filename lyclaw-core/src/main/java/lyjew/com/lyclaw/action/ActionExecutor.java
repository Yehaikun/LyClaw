package lyjew.com.lyclaw.action;

import lyjew.com.lyclaw.action.tool.ToolResult;
import lyjew.com.lyclaw.context.ChatContext;
import lyjew.com.lyclaw.dto.SkillResult;
import lyjew.com.lyclaw.security.SandboxLevel;
import lyjew.com.lyclaw.task.TaskPlan;
import reactor.core.publisher.Flux;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

public interface ActionExecutor {

    Flux<ActionResult> execute(TaskPlan plan, ChatContext context);
    CompletableFuture<ToolResult> executeTool(String toolName, Map<String, Object> args, SandboxLevel level);
    CompletableFuture<SkillResult> executeSkill(String skillId, ChatContext context);
}
