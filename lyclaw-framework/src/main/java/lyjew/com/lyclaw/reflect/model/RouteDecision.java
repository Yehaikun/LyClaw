package lyjew.com.lyclaw.reflect.model;

public enum RouteDecision {
    CONTINUE,
    RETRY,
    STOP,
    FALLBACK;

    public static RouteDecision BRANCH(String targetNodeId) { return CONTINUE; }
    public static RouteDecision FORK(java.util.List<String> targetNodeIds) { return CONTINUE; }
}
