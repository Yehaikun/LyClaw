package lyjew.com.lyclaw.action.agent.router;

import lyjew.com.lyclaw.action.agent.DefaultAgentRegistry;
import lyjew.com.lyclaw.agent.AgentHandle;
import lyjew.com.lyclaw.agent.AgentRouter;
import lyjew.com.lyclaw.agent.AgentTask;
import lyjew.com.lyclaw.agent.RoutingContext;
import lyjew.com.lyclaw.agent.RoutingDecision;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Component
@Order(100)
public class CapabilityRouter implements AgentRouter {

    private static final Logger log = LoggerFactory.getLogger(CapabilityRouter.class);

    private static final Map<String, List<String>> TASK_TO_CAPABILITIES = Map.ofEntries(
        Map.entry("review", List.of("code_review", "security_audit", "quality_assurance")),
        Map.entry("refactor", List.of("code_refactoring", "java", "python", "javascript")),
        Map.entry("test", List.of("test_generation", "unit_test", "integration_test")),
        Map.entry("document", List.of("documentation", "technical_writing", "markdown")),
        Map.entry("search", List.of("web_search", "research", "information_retrieval")),
        Map.entry("analyze", List.of("data_analysis", "reasoning", "analytics")),
        Map.entry("write", List.of("content_writing", "creative_writing", "copywriting")),
        Map.entry("debug", List.of("debugging", "troubleshooting", "root_cause_analysis")),
        Map.entry("design", List.of("architecture_design", "system_design", "ui_design")),
        Map.entry("deploy", List.of("devops", "deployment", "ci_cd")),
        Map.entry("explain", List.of("explanation", "teaching", "knowledge_base")),
        Map.entry("optimize", List.of("performance", "optimization", "profiling"))
    );

    private final DefaultAgentRegistry registry;

    public CapabilityRouter(DefaultAgentRegistry registry) {
        this.registry = registry;
    }

    @Override
    public RoutingDecision route(AgentTask task, RoutingContext context) {
        String taskDesc = task.getPayload() != null ? task.getPayload().toLowerCase() : "";
        String taskType = task.getType() != null ? task.getType().toLowerCase() : "";

        List<String> requiredCaps = inferCapabilities(taskType, taskDesc);
        if (requiredCaps.isEmpty()) {
            return RoutingDecision.fallback("无法从任务中推断能力要求");
        }

        log.debug("CapabilityRouter: inferred capabilities {} from task '{}'", requiredCaps, taskDesc);

        List<AgentHandle> candidates = registry.findAvailable(requiredCaps);
        if (candidates.isEmpty()) {
            return RoutingDecision.fallback("没有找到具备能力 " + requiredCaps + " 的可用 Agent");
        }

        AgentHandle best = candidates.get(0);
        double score = 0.5 + (best.getHistoricalAccuracy() * 0.3);
        String reason = "能力匹配: " + String.join(",", requiredCaps)
                + " → Agent[" + best.getAgentId() + "]";

        if (candidates.size() == 1 && requiredCaps.size() > 1) {
            return RoutingDecision.definite(best.getAgentId(), reason, routerName());
        }
        return RoutingDecision.high(best.getAgentId(), score, reason, routerName());
    }

    @Override
    public String routerName() {
        return "capability";
    }

    private List<String> inferCapabilities(String taskType, String taskDesc) {
        for (Map.Entry<String, List<String>> entry : TASK_TO_CAPABILITIES.entrySet()) {
            if (taskType.contains(entry.getKey()) || taskDesc.contains(entry.getKey())) {
                return entry.getValue();
            }
        }
        return List.of();
    }
}
