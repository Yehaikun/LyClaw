package lyjew.com.lyclaw.action.agent;

import lyjew.com.lyclaw.action.agent.router.RouterChain;
import lyjew.com.lyclaw.agent.AgentTask;
import lyjew.com.lyclaw.agent.RoutingContext;
import lyjew.com.lyclaw.agent.RoutingDecision;
import lyjew.com.lyclaw.react.AgentContext;
import lyjew.com.lyclaw.react.subagent.SubagentResult;
import lyjew.com.lyclaw.react.subagent.SubagentSpawner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;

@Component
public class Orchestrator {

    private static final Logger log = LoggerFactory.getLogger(Orchestrator.class);

    private final RouterChain routerChain;
    private final SubagentSpawner spawner;
    private final DefaultAgentRegistry registry;

    public Orchestrator(RouterChain routerChain, SubagentSpawner spawner,
                         DefaultAgentRegistry registry) {
        this.routerChain = routerChain;
        this.spawner = spawner;
        this.registry = registry;
    }

    /**
     * Route a task to the most suitable agent and execute it.
     *
     * @param targetAgentId explicit target (may be null for auto-routing)
     * @param task          task description
     * @param mode          delegation mode (suggest/auto)
     * @param parentCtx     parent agent context
     * @return subagent execution result
     */
    public SubagentResult orchestrate(String targetAgentId, String task,
                                       String mode, AgentContext parentCtx) {
        log.info("Orchestrator: targetAgentId={}, task={}, mode={}",
                targetAgentId, task != null ? task.substring(0, Math.min(50, task.length())) : null, mode);

        // Case 1: explicit target agent
        if (targetAgentId != null && !targetAgentId.isBlank()) {
            Optional<lyjew.com.lyclaw.agent.AgentHandle> handle = registry.lookup(targetAgentId);
            if (handle.isEmpty()) {
                return SubagentResult.rejected(targetAgentId,
                        "Agent '" + targetAgentId + "' not found in registry");
            }
            return spawner.spawnSubagent(targetAgentId, task,
                            java.util.Collections.emptyMap(), parentCtx)
                    .block(java.time.Duration.ofSeconds(300));
        }

        // Case 2: auto-routing
        RoutingContext ctx = RoutingContext.from(parentCtx);
        AgentTask agentTask = AgentTask.builder()
                .taskId(java.util.UUID.randomUUID().toString().substring(0, 8))
                .type(inferTaskType(task))
                .target("")
                .payload(task)
                .metadata(Map.of("sourceAgentId", parentCtx.getAgentId()))
                .build();

        RoutingDecision decision = routerChain.route(agentTask, ctx);

        if (!decision.isRoutable()) {
            return SubagentResult.rejected("auto-router",
                    "无法自动匹配合适的 Agent: " + decision.getReason());
        }

        // "suggest" mode → return suggestion for LLM/User to decide
        if ("suggest".equals(mode)) {
            return SubagentResult.success(
                    "orchestrator", decision.getTargetAgentId(),
                    "建议委派给 Agent [" + decision.getTargetAgentId()
                    + "]，置信度 " + decision.getConfidence()
                    + "，原因: " + decision.getReason()
                    + "。如需重新指定，请明确 targetAgentId。",
                    0, 0, 0);
        }

        // "auto" mode → delegate directly
        if (!decision.isConfident()) {
            return SubagentResult.rejected("auto-router",
                    "路由置信度不足 (" + decision.getConfidence()
                    + ")，请明确指定 targetAgentId");
        }

        try {
            return spawner.spawnSubagent(decision.getTargetAgentId(), task,
                            java.util.Collections.emptyMap(), parentCtx)
                    .block(java.time.Duration.ofSeconds(300));
        } catch (Exception e) {
            log.error("Orchestrator: subagent execution failed", e);
            return SubagentResult.error("子Agent执行失败: " + e.getMessage());
        }
    }

    private String inferTaskType(String task) {
        if (task == null || task.isBlank()) return "general";
        String t = task.toLowerCase();
        if (t.contains("review") || t.contains("审查") || t.contains("审阅")) return "review";
        if (t.contains("refactor") || t.contains("重构")) return "refactor";
        if (t.contains("test") || t.contains("测试")) return "test";
        if (t.contains("document") || t.contains("文档")) return "document";
        if (t.contains("search") || t.contains("搜索")) return "search";
        if (t.contains("write") || t.contains("写") || t.contains("创作")) return "write";
        if (t.contains("debug") || t.contains("调试")) return "debug";
        if (t.contains("design") || t.contains("设计")) return "design";
        if (t.contains("deploy") || t.contains("部署")) return "deploy";
        if (t.contains("explain") || t.contains("解释")) return "explain";
        if (t.contains("optimize") || t.contains("优化")) return "optimize";
        if (t.contains("analyze") || t.contains("分析")) return "analyze";
        return "general";
    }
}
