package lyjew.com.lyclaw.action.impl;

import lyjew.com.lyclaw.action.ActionExecutor;
import lyjew.com.lyclaw.action.ActionResult;
import lyjew.com.lyclaw.action.tool.ToolResult;
import lyjew.com.lyclaw.context.ChatContext;
import lyjew.com.lyclaw.dto.SkillResult;
import lyjew.com.lyclaw.security.SandboxLevel;
import lyjew.com.lyclaw.task.TaskPlan;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

@Service
public class ActionExecutorImpl implements ActionExecutor {

    @Override
    public Flux<ActionResult> execute(TaskPlan plan, ChatContext context) {
        return Flux.empty();
    }

    @Override
    public CompletableFuture<ToolResult> executeTool(String toolName, Map<String, Object> args, SandboxLevel level) {
        ToolResult result = ToolResult.builder()
                .toolName(toolName)
                .success(true)
                .output("Tool executed: " + toolName)
                .durationMs(0)
                .build();
        return CompletableFuture.completedFuture(result);
    }

    @Override
    public CompletableFuture<SkillResult> executeSkill(String skillId, ChatContext context) {
        SkillResult result = new SkillResult(skillId, true,
                "Skill executed: " + skillId, null, 0, 0);
        return CompletableFuture.completedFuture(result);
    }
}
