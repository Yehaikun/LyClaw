package lyjew.com.lyclaw.action.impl;

import lyjew.com.lyclaw.action.ActionExecutor;
import lyjew.com.lyclaw.action.ActionResult;
import lyjew.com.lyclaw.action.tool.ToolResult;
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
import lyjew.com.lyclaw.tool.ToolRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.*;
import java.util.concurrent.*;

@Slf4j
@Service
public class ActionExecutorImpl implements ActionExecutor {

    private final ToolRegistry toolRegistry;
    private final SkillRegistry skillRegistry;
    private final ToolSandbox toolSandbox;
    private final ToolCallPolicy toolCallPolicy;
    private final SkillExecutor skillExecutor;

    private final ExecutorService executorService = Executors.newFixedThreadPool(4, r -> {
        Thread t = new Thread(r, "action-executor");
        t.setDaemon(true);
        return t;
    });

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

    @Override
    public CompletableFuture<ToolResult> executeTool(String toolName,
                                                      Map<String, Object> args,
                                                      SandboxLevel level) {
        return CompletableFuture.supplyAsync(() -> {
            long startTime = System.currentTimeMillis();
            log.info("执行工具: toolName={}, level={}", toolName, level);

            try {
                Tool tool = toolRegistry.get(toolName);
                if (tool == null) {
                    log.warn("工具未找到: toolName={}", toolName);
                    return ToolResult.builder()
                            .toolName(toolName)
                            .success(false)
                            .errorMessage("工具未注册: " + toolName)
                            .durationMs(System.currentTimeMillis() - startTime)
                            .build();
                }

                if (toolCallPolicy instanceof DefaultToolCallPolicy policy) {
                    String sessionId = "global";
                    if (!policy.canExecute(toolName, 0, sessionId)) {
                        log.warn("策略禁止执行: toolName={}", toolName);
                        return ToolResult.builder()
                                .toolName(toolName)
                                .success(false)
                                .errorMessage("策略禁止执行工具: " + toolName)
                                .durationMs(System.currentTimeMillis() - startTime)
                                .build();
                    }
                }

                SandboxLevel effectiveLevel = level != null ? level : SandboxLevel.NONE;
                ToolResult result = toolSandbox.execute(tool, args, effectiveLevel);

                long totalDuration = System.currentTimeMillis() - startTime;
                log.info("工具执行完成: toolName={}, success={}, duration={}ms",
                        toolName, result.isSuccess(), totalDuration);
                return result;

            } catch (Exception e) {
                log.error("工具执行异常: toolName={}", toolName, e);
                long elapsed = System.currentTimeMillis() - startTime;
                return ToolResult.builder()
                        .toolName(toolName)
                        .success(false)
                        .errorMessage("工具执行异常: " + e.getMessage())
                        .durationMs(elapsed)
                        .build();
            }
        }, executorService);
    }

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

    public List<String> getRegisteredToolNames() {
        return new ArrayList<>(toolRegistry.getAllDefinitions().stream()
                .map(d -> d.getName())
                .toList());
    }

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

    public boolean isSandboxHealthy() {
        return toolSandbox.isHealthy();
    }

    private ActionResult executeNode(TaskNode node, ChatContext context) {
        long startTime = System.currentTimeMillis();
        String nodeId = node.getNodeId();
        String type = node.getType();

        try {
            if ("tool".equalsIgnoreCase(type)) {
                if (node.getRequiredTools() == null || node.getRequiredTools().isEmpty()) {
                    return ActionResult.builder()
                            .nodeId(nodeId)
                            .success(false)
                            .errorMessage("工具节点缺少 requiredTools: " + nodeId)
                            .durationMs(System.currentTimeMillis() - startTime)
                            .build();
                }

                String toolName = node.getRequiredTools().get(0);
                Map<String, Object> args = new HashMap<>();
                args.put("description", node.getDescription());

                CompletableFuture<ToolResult> future = executeTool(toolName, args, SandboxLevel.NONE);
                ToolResult toolResult = future.get(30, TimeUnit.SECONDS);

                long elapsed = System.currentTimeMillis() - startTime;
                return ActionResult.builder()
                        .nodeId(nodeId)
                        .success(toolResult.isSuccess())
                        .output(toolResult.getOutput())
                        .errorMessage(toolResult.getErrorMessage())
                        .durationMs(elapsed)
                        .build();

            } else if ("skill".equalsIgnoreCase(type)) {
                String skillId = node.getRequiredTools() != null
                        && !node.getRequiredTools().isEmpty()
                        ? node.getRequiredTools().get(0)
                        : node.getDescription();

                CompletableFuture<SkillResult> future = executeSkill(skillId, context);
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
