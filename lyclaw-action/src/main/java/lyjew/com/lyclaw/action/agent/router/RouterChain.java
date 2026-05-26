package lyjew.com.lyclaw.action.agent.router;

import lyjew.com.lyclaw.agent.AgentRouter;
import lyjew.com.lyclaw.agent.AgentTask;
import lyjew.com.lyclaw.agent.RoutingContext;
import lyjew.com.lyclaw.agent.RoutingDecision;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;

@Component
public class RouterChain {

    private static final Logger log = LoggerFactory.getLogger(RouterChain.class);

    private final List<AgentRouter> routers;

    public RouterChain(List<AgentRouter> routers) {
        this.routers = routers.stream()
                .sorted(Comparator.comparingInt(AgentRouter::getOrder).reversed())
                .toList();
        log.info("RouterChain initialized with {} routers: {}",
                this.routers.size(),
                this.routers.stream().map(AgentRouter::routerName).toList());
    }

    /**
     * Route a task through the chain. Higher-order routers execute first.
     * Returns the best DEFINITE or HIGH confidence decision, or the first
     * routable decision if none is confident.
     */
    public RoutingDecision route(AgentTask task, RoutingContext context) {
        RoutingDecision bestMedium = null;

        for (AgentRouter router : routers) {
            try {
                RoutingDecision decision = router.route(task, context);
                if (decision == null || !decision.isRoutable()) {
                    log.debug("Router {}: no decision", router.routerName());
                    continue;
                }

                log.info("Router {}: decision={}", router.routerName(), decision);

                // DEFINITE → immediate return
                if (decision.getConfidence() == RoutingDecision.Confidence.DEFINITE) {
                    return decision;
                }

                // HIGH → record as best (but continue in case a higher-order router also matches)
                if (decision.getConfidence() == RoutingDecision.Confidence.HIGH) {
                    if (bestMedium == null) {
                        bestMedium = decision;
                    } else if (decision.getScore() > bestMedium.getScore()) {
                        bestMedium = decision;
                    }
                }

                // MEDIUM → record only if no better yet
                if (decision.getConfidence() == RoutingDecision.Confidence.MEDIUM && bestMedium == null) {
                    bestMedium = decision;
                }

            } catch (Exception e) {
                log.warn("Router {} threw exception: {}", router.routerName(), e.getMessage());
            }
        }

        if (bestMedium != null) {
            log.info("RouterChain: final decision from {}: {}", bestMedium.getRouterUsed(), bestMedium);
            return bestMedium;
        }

        return RoutingDecision.fallback("路由器链未产生任何路由决策");
    }

    public List<AgentRouter> getRouters() {
        return routers;
    }
}
