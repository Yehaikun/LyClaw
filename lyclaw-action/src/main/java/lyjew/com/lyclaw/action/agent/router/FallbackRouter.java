package lyjew.com.lyclaw.action.agent.router;

import lyjew.com.lyclaw.action.agent.DefaultAgentRegistry;
import lyjew.com.lyclaw.agent.AgentHandle;
import lyjew.com.lyclaw.agent.AgentRouter;
import lyjew.com.lyclaw.agent.AgentState;
import lyjew.com.lyclaw.agent.AgentTask;
import lyjew.com.lyclaw.agent.RoutingContext;
import lyjew.com.lyclaw.agent.RoutingDecision;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@Component
@Order(0)
public class FallbackRouter implements AgentRouter {

    private static final Logger log = LoggerFactory.getLogger(FallbackRouter.class);

    private final DefaultAgentRegistry registry;

    public FallbackRouter(DefaultAgentRegistry registry) {
        this.registry = registry;
    }

    @Override
    public RoutingDecision route(AgentTask task, RoutingContext context) {
        // 策略 1: 找默认 Agent（任务数最少的）
        Optional<AgentHandle> leastBusy = registry.getAllAgents().stream()
                .filter(h -> h.getState() == AgentState.IDLE || h.getState() == AgentState.RUNNING)
                .min(Comparator.comparingInt(AgentHandle::getActiveSubagentCount));

        if (leastBusy.isPresent()) {
            AgentHandle h = leastBusy.get();
            log.debug("FallbackRouter: selected least-busy agent {} (activeSubagents={})",
                    h.getAgentId(), h.getActiveSubagentCount());
            return RoutingDecision.low(h.getAgentId(),
                    "兜底路由: 负载最少的 Agent", routerName());
        }

        // 策略 2: 找任何非 DOWN 的 Agent
        Optional<AgentHandle> anyAlive = registry.getAllAgents().stream()
                .filter(h -> h.getHealth() != AgentHandle.HealthStatus.DOWN)
                .findFirst();

        if (anyAlive.isPresent()) {
            return RoutingDecision.low(anyAlive.get().getAgentId(),
                    "兜底路由: 唯一可用 Agent", routerName());
        }

        return RoutingDecision.fallback("没有可用 Agent");
    }

    @Override
    public String routerName() {
        return "fallback";
    }
}
