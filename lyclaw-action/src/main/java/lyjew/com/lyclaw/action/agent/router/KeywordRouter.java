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

import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Component
@Order(80)
public class KeywordRouter implements AgentRouter {

    private static final Logger log = LoggerFactory.getLogger(KeywordRouter.class);

    private final DefaultAgentRegistry registry;

    public KeywordRouter(DefaultAgentRegistry registry) {
        this.registry = registry;
    }

    @Override
    public RoutingDecision route(AgentTask task, RoutingContext context) {
        String taskDesc = task.getPayload() != null ? task.getPayload().toLowerCase() : "";
        if (taskDesc.isBlank()) {
            return RoutingDecision.fallback("任务描述为空，无法进行关键词匹配");
        }

        Set<String> taskTokens = tokenize(taskDesc);
        List<AgentHandle> candidates = registry.getAllAgents().stream()
                .filter(h -> h.getState() == lyjew.com.lyclaw.agent.AgentState.IDLE
                        || h.getState() == lyjew.com.lyclaw.agent.AgentState.RUNNING)
                .collect(Collectors.toList());

        if (candidates.isEmpty()) {
            return RoutingDecision.fallback("没有可用的 Agent 进行关键词匹配");
        }

        AgentHandle best = null;
        double bestScore = 0;
        String bestMatchField = "";

        for (AgentHandle agent : candidates) {
            double score = 0;

            // Name matching (weight 0.3)
            if (agent.getName() != null) {
                Set<String> nameTokens = tokenize(agent.getName());
                score += overlap(taskTokens, nameTokens) * 0.3;
            }

            // Description matching (weight 0.4)
            if (agent.getDescription() != null) {
                Set<String> descTokens = tokenize(agent.getDescription());
                score += overlap(taskTokens, descTokens) * 0.4;
            }

            // Capability matching (weight 0.3)
            if (agent.getCapabilities() != null) {
                for (String cap : agent.getCapabilities()) {
                    Set<String> capTokens = tokenize(cap);
                    score += overlap(taskTokens, capTokens) * 0.3;
                }
            }

            if (score > bestScore) {
                bestScore = score;
                best = agent;
                bestMatchField = "keyword_score=" + String.format("%.2f", score);
            }
        }

        if (best == null || bestScore < 0.1) {
            return RoutingDecision.fallback("关键词匹配分数过低: " + bestScore);
        }

        String reason = "关键词匹配: " + bestMatchField + " → Agent[" + best.getAgentId() + "]";

        if (bestScore > 0.6) {
            return RoutingDecision.high(best.getAgentId(), bestScore, reason, routerName());
        } else if (bestScore > 0.3) {
            return RoutingDecision.medium(best.getAgentId(), bestScore, reason, routerName());
        }
        return RoutingDecision.low(best.getAgentId(), reason, routerName());
    }

    @Override
    public String routerName() {
        return "keyword";
    }

    private Set<String> tokenize(String text) {
        if (text == null || text.isBlank()) return Set.of();
        return Arrays.stream(text.toLowerCase()
                .replaceAll("[^a-z0-9一-鿿\\s]", " ")
                .split("\\s+"))
                .filter(s -> s.length() > 1)
                .collect(Collectors.toSet());
    }

    private double overlap(Set<String> a, Set<String> b) {
        if (a.isEmpty() || b.isEmpty()) return 0;
        Set<String> intersection = new HashSet<>(a);
        intersection.retainAll(b);
        return (double) intersection.size() / Math.max(a.size(), b.size());
    }
}
