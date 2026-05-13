package lyjew.com.lyclaw.action.impl;

import lyjew.com.lyclaw.action.ActionExecutor;
import lyjew.com.lyclaw.action.ActionResult;
import lyjew.com.lyclaw.action.tool.ToolSandbox;
import lyjew.com.lyclaw.context.ChatContext;
import lyjew.com.lyclaw.dto.SkillResult;
import lyjew.com.lyclaw.security.SandboxLevel;
import lyjew.com.lyclaw.skill.Skill;
import lyjew.com.lyclaw.skill.SkillExecutor;
import lyjew.com.lyclaw.skill.SkillRegistry;
import lyjew.com.lyclaw.task.TaskNode;
import lyjew.com.lyclaw.task.TaskPlan;
import lyjew.com.lyclaw.tool.Tool;
import lyjew.com.lyclaw.tool.ToolCallPolicy;
import lyjew.com.lyclaw.tool.ToolExecutionResult;
import lyjew.com.lyclaw.tool.ToolRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.*;
import java.util.concurrent.*;

/**
 * 动作执行器实现，负责调度执行 TaskPlan 中的工具节点和技能节点。
 *
 * <p>该类是工具/技能执行的核心调度器，提供以下能力：
 * <ul>
 *   <li>遍历 TaskPlan 中的 TaskNode，按类型分发到工具执行或技能执行</li>
 *   <li>通过 {@link ToolSandbox} 在不同安全级别（NONE/READ_ONLY/RESTRICTED/CONTAINER/ISOLATED）下执行工具</li>
 *   <li>在执行前通过 {@link ToolCallPolicy} 检查是否允许执行</li>
 *   <li>技能执行委托给 {@link SkillExecutor}</li>
 *   <li>所有异步任务在固定大小（4线程）的后台守护线程池中运行</li>
 * </ul>
 * </p>
 *
 * @see ToolSandbox
 * @see ToolCallPolicy
 * @see SkillExecutor
 */
@Slf4j
@Service
public class ActionExecutorImpl implements ActionExecutor {

    /** 工具注册表 */
    private final ToolRegistry toolRegistry;
    /** 技能注册表 */
    private final SkillRegistry skillRegistry;
    /** 工具沙箱执行器 */
    private final ToolSandbox toolSandbox;
    /** 工具调用策略（频率限制、黑/白名单） */
    private final ToolCallPolicy toolCallPolicy;
    /** 技能执行器 */
    private final SkillExecutor skillExecutor;

    /** 后台异步线程池，4个守护线程，专用于工具异步执行 */
    private final ExecutorService executorService = Executors.newFixedThreadPool(4, r -> {
        Thread t = new Thread(r, "action-executor");
        t.setDaemon(true);
        return t;
    });

    /**
     * 构造函数，通过依赖注入初始化各组件。
     */
    public ActionExecutorImpl(ToolRegistry toolRegistry,
                              SkillRegistry skillRegistry,
                              ToolSandbox toolSandbox,
                              ToolCallPolicy toolCallPolicy,
                              SkillExecutor skillExecutor) {
        this.toolRegistry = toolRegistry;
        this.skillRegistry = skillRegistry;
        this.toolSandbox = toolSandbox;
        this.toolCallPolicy = toolCallPolicy;
        this.skillExecutor = skillExecutor;
    }

    /**
     * 执行一个完整的任务计划。
     *
     * <p>按顺序遍历 TaskPlan 中的每个 TaskNode，依次执行。
     * 每个节点的执行结果以 Flux 流的形式逐个推送。</p>
     *
     * @param plan    任务计划，包含多个任务节点
     * @param context 当前对话上下文
     * @return 执行结果流（逐个发送 ActionResult）
     */
    @Override
    public Flux<ActionResult> execute(TaskPlan plan, ChatContext context) {
        if (plan == null || plan.getNodes() == null || plan.getNodes().isEmpty()) {
            log.warn("TaskPlan 为空，无任务执行");
            return Flux.empty();
        }

        List<TaskNode> nodes = plan.getNodes();
        log.info("开始执行 TaskPlan: nodeCount={}", nodes.size());

        return Flux.create(sink -> {
            for (TaskNode node : nodes) {
                try {
                    ActionResult result = executeNode(node, context);
                    sink.next(result);
                } catch (Exception e) {
                    log.error("节点执行异常: nodeId={}", node.getNodeId(), e);
                    sink.next(ActionResult.builder()
                            .nodeId(node.getNodeId())
                            .success(false)
                            .errorMessage("节点执行异常: " + e.getMessage())
                            .durationMs(0)
                            .build());
                }
            }
            sink.complete();
            log.info("TaskPlan 执行完成: nodeCount={}", nodes.size());
        });
    }

    /**
     * 异步执行单个工具。
     *
     * <p>执行流程：
     * <ol>
     *   <li>从 ToolRegistry 查找工具</li>
     *   <li>经由 ToolCallPolicy 检查执行权限</li>
     *   <li>选择合适的沙箱级别（默认 NONE）</li>
     *   <li>委派给 ToolSandbox 执行</li>
     *   <li>记录耗时和结果</li>
     * </ol>
     * </p>
     *
     * @param toolName 工具名称
     * @param args     工具参数
     * @param level    沙箱安全级别
     * @return CompletableFuture 包装的 ToolResult
     */
    @Override
    public CompletableFuture<ToolExecutionResult> executeTool(String toolName,
                                                              Map<String, Object> args,
                                                              SandboxLevel level) {
        return CompletableFuture.supplyAsync(() -> {
            long startTime = System.currentTimeMillis();
            log.info("执行工具: toolName={}, level={}", toolName, level);

            try {
                // 1. 查找工具
                Tool tool = toolRegistry.get(toolName);
                if (tool == null) {
                    log.warn("工具未找到: toolName={}", toolName);
                    return ToolExecutionResult.builder()
                            .toolName(toolName)
                            .success(false)
                            .error("工具未注册: " + toolName)
                            .elapsedMs(System.currentTimeMillis() - startTime)
                            .build();
                }

                // 2. 策略检查
                if (!toolCallPolicy.canExecute(toolName, (ChatContext) null)) {
                    log.warn("策略禁止执行: toolName={}", toolName);
                    return ToolExecutionResult.builder()
                            .toolName(toolName)
                            .success(false)
                            .error("策略禁止执行工具: " + toolName)
                            .elapsedMs(System.currentTimeMillis() - startTime)
                            .build();
                }

                // 3. 确定沙箱级别（默认 NONE）
                SandboxLevel effectiveLevel = level != null ? level : SandboxLevel.NONE;
                // 4. 在沙箱中执行
                ToolExecutionResult result = toolSandbox.execute(tool, args, effectiveLevel);

                long totalDuration = System.currentTimeMillis() - startTime;
                log.info("工具执行完成: toolName={}, success={}, duration={}ms",
                        toolName, result.isSuccess(), totalDuration);
                return result;

            } catch (Exception e) {
                log.error("工具执行异常: toolName={}", toolName, e);
                long elapsed = System.currentTimeMillis() - startTime;
                return ToolExecutionResult.builder()
                        .toolName(toolName)
                        .success(false)
                        .error("工具执行异常: " + e.getMessage())
                        .elapsedMs(elapsed)
                        .build();
            }
        }, executorService);
    }

    /**
     * 异步执行技能（Skill）。
     *
     * <p>从 SkillRegistry 查找技能，然后委托给 SkillExecutor 执行。
     * 如果技能未注册，立即返回失败的 CompletableFuture。</p>
     *
     * @param skillId  技能标识
     * @param context  对话上下文（可为 null）
     * @return CompletableFuture 包装的 SkillResult
     */
    @Override
    public CompletableFuture<SkillResult> executeSkill(String skillId, @Nullable ChatContext context) {
        log.info("执行技能: skillId={}, contextProvided={}", skillId, context != null);

        Skill skill = skillRegistry.get(skillId);
        if (skill == null) {
            log.warn("技能未找到: skillId={}", skillId);
            SkillResult errorResult = new SkillResult(skillId, false, "",
                    "技能未注册: " + skillId, 0, 0);
            return CompletableFuture.completedFuture(errorResult);
        }

        if (context == null) {
            log.warn("executeSkill 在 skillId={} 时收到 null ChatContext, 传递 null 到 executor", skillId);
        }

        return skillExecutor.execute(skill, context);
    }

    /**
     * 获取所有已注册工具的名称列表。
     *
     * @return 工具名称列表
     */
    public List<String> getRegisteredToolNames() {
        return new ArrayList<>(toolRegistry.getAllDefinitions().stream()
                .map(d -> d.getName())
                .toList());
    }

    /**
     * 获取所有已注册技能的摘要信息列表。
     *
     * @return 技能摘要列表，包含 skillId、name、description
     */
    public List<Map<String, Object>> getRegisteredSkills() {
        return skillRegistry.getAll().stream()
                .map(s -> {
                    Map<String, Object> info = new LinkedHashMap<>();
                    info.put("skillId", s.getSkillId());
                    info.put("name", s.getName());
                    info.put("description", s.getDescription());
                    return info;
                })
                .toList();
    }

    /**
     * 检查工具执行沙箱是否健康。
     *
     * @return true 表示沙箱可用
     */
    public boolean isSandboxHealthy() {
        return toolSandbox.isHealthy();
    }

    /**
     * 执行单个任务节点。
     *
     * <p>根据节点类型分发执行：
     * <ul>
     *   <li>"tool" 节点：获取 requiredTools 中的第一个工具名，构造参数（含 description），
     *       通过 executeTool 在 NONE 级别沙箱中执行，超时 30 秒</li>
     *   <li>"skill" 节点：获取技能 ID（优先 requiredTools，其次 description），
     *       通过 executeSkill 执行，超时 60 秒</li>
     *   <li>其他类型：返回失败结果</li>
     * </ul>
     * </p>
     *
     * @param node    任务节点
     * @param context 对话上下文
     * @return 执行结果
     */
    private ActionResult executeNode(TaskNode node, ChatContext context) {
        long startTime = System.currentTimeMillis();
        String nodeId = node.getNodeId();
        String type = node.getType();

        try {
            if ("tool".equalsIgnoreCase(type)) {
                // 工具节点：必须提供 requiredTools
                if (node.getRequiredTools() == null || node.getRequiredTools().isEmpty()) {
                    return ActionResult.builder()
                            .nodeId(nodeId)
                            .success(false)
                            .errorMessage("工具节点缺少 requiredTools: " + nodeId)
                            .durationMs(System.currentTimeMillis() - startTime)
                            .build();
                }

                // 取 requiredTools 列表中的第一个工具名
                String toolName = node.getRequiredTools().get(0);
                Map<String, Object> args = new HashMap<>();
                args.put("description", node.getDescription());

                CompletableFuture<ToolExecutionResult> future = executeTool(toolName, args, SandboxLevel.NONE);
                // 阻塞等待最多 30 秒
                ToolExecutionResult toolResult = future.get(30, TimeUnit.SECONDS);

                long elapsed = System.currentTimeMillis() - startTime;
                return ActionResult.builder()
                        .nodeId(nodeId)
                        .success(toolResult.isSuccess())
                        .output(toolResult.getResult())
                        .errorMessage(toolResult.getError())
                        .durationMs(elapsed)
                        .build();

            } else if ("skill".equalsIgnoreCase(type)) {
                // 技能节点：skill ID 优先取 requiredTools 第一项，其次用 description
                String skillId = node.getRequiredTools() != null
                        && !node.getRequiredTools().isEmpty()
                        ? node.getRequiredTools().get(0)
                        : node.getDescription();

                CompletableFuture<SkillResult> future = executeSkill(skillId, context);
                // 阻塞等待最多 60 秒
                SkillResult skillResult = future.get(60, TimeUnit.SECONDS);

                long elapsed = System.currentTimeMillis() - startTime;
                return ActionResult.builder()
                        .nodeId(nodeId)
                        .success(skillResult.isSuccess())
                        .output(skillResult.getOutput())
                        .errorMessage(skillResult.getError())
                        .durationMs(elapsed)
                        .build();

            } else {
                // 未知节点类型
                return ActionResult.builder()
                        .nodeId(nodeId)
                        .success(false)
                        .errorMessage("未知的节点类型: " + type)
                        .durationMs(System.currentTimeMillis() - startTime)
                        .build();
            }
        } catch (TimeoutException e) {
            long elapsed = System.currentTimeMillis() - startTime;
            return ActionResult.builder()
                    .nodeId(nodeId)
                    .success(false)
                    .errorMessage("节点执行超时: " + nodeId)
                    .durationMs(elapsed)
                    .build();
        } catch (Exception e) {
            long elapsed = System.currentTimeMillis() - startTime;
            log.error("节点执行失败: nodeId={}", nodeId, e);
            return ActionResult.builder()
                    .nodeId(nodeId)
                    .success(false)
                    .errorMessage("节点执行失败: " + e.getMessage())
                    .durationMs(elapsed)
                    .build();
        }
    }
}
