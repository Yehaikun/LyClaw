package lyjew.com.lyclaw.agent;

import lyjew.com.lyclaw.react.AgentContext;

public interface AgentRouter {

    RoutingDecision route(AgentTask task, RoutingContext context);

    String routerName();

    default int getOrder() { return 0; }
}
