package lyjew.com.lyclaw.action;

import lyjew.com.lyclaw.action.tool.ToolResult;
import lyjew.com.lyclaw.context.ChatContext;
import lyjew.com.lyclaw.dto.SkillResult;
import lyjew.com.lyclaw.security.SandboxLevel;
import lyjew.com.lyclaw.task.TaskPlan;
import reactor.core.publisher.Flux;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * 执行器接口 —— 四元架构中的执行模块核心。
 *
 * <p>负责执行 TaskPlanner 生成的 TaskPlan,
 * 编排工具调用和技能执行。</p>
 *
 * @since 2.0
 */
public interface ActionExecutor {

    Flux<ActionResult> execute(TaskPlan plan, ChatContext context);

    CompletableFuture<ToolResult> executeTool(String toolName, Map<String, Object> args, SandboxLevel level);

    CompletableFuture<SkillResult> executeSkill(String skillId, ChatContext context);
}
