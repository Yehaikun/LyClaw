package lyjew.com.lyclaw.action;

import lyjew.com.lyclaw.context.ChatContext;
import lyjew.com.lyclaw.dto.SkillResult;
import lyjew.com.lyclaw.security.SandboxLevel;
import lyjew.com.lyclaw.task.TaskPlan;
import lyjew.com.lyclaw.tool.ToolExecutionResult;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * 动作执行器接口，是编排流水线的执行中枢。
 *
 * <p>该接口将编排层的抽象计划（TaskPlan）落地为具体的工具调用和技能执行。
 * 支持以响应式流的方式逐步返回执行结果，也支持异步获取单次工具/技能的执行结果。
 * 沙箱执行模式参数确保敏感工具在受限环境中执行。
 */
public interface ActionExecutor {

    /**
     * 按计划逐步执行任务，以响应式流返回每个节点的执行结果。
     *
     * @param plan    任务执行计划，包含节点依赖关系和执行顺序
     * @param context 当前对话上下文
     * @return 每个节点执行结果的响应式流
     */
    Flux<ActionResult> execute(TaskPlan plan, ChatContext context);

    /**
     * 异步执行指定工具调用。
     *
     * @param toolName 工具名称
     * @param args     工具调用参数
     * @param level    沙箱执行模式
     * @return 异步返回工具执行结果
     */
    CompletableFuture<ToolExecutionResult> executeTool(String toolName, Map<String, Object> args, SandboxLevel level);

    /**
     * 异步执行指定技能。
     *
     * @param skillId 技能标识
     * @param context 当前对话上下文
     * @return 异步返回技能执行结果
     */
    CompletableFuture<SkillResult> executeSkill(String skillId, ChatContext context);

    /** @return 所有已注册技能的摘要信息列表 */
    List<Map<String, Object>> getRegisteredSkills();

    /** @return 工具执行沙箱是否健康可用 */
    boolean isSandboxHealthy();
}
