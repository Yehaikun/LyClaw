package lyjew.com.lyclaw.agent;

import java.util.ArrayList;
import java.util.List;

public class RoutingDecision {

    public enum Confidence {
        DEFINITE,
        HIGH,
        MEDIUM,
        LOW,
        FALLBACK
    }

    private final String targetAgentId;
    private final double score;
    private final Confidence confidence;
    private final String reason;
    private final String routerUsed;
    private final boolean requiresConfirmation;
    private final List<String> alternativeAgentIds;

    private RoutingDecision(String targetAgentId, double score, Confidence confidence,
                            String reason, String routerUsed, boolean requiresConfirmation,
                            List<String> alternativeAgentIds) {
        this.targetAgentId = targetAgentId;
        this.score = score;
        this.confidence = confidence;
        this.reason = reason;
        this.routerUsed = routerUsed;
        this.requiresConfirmation = requiresConfirmation;
        this.alternativeAgentIds = alternativeAgentIds;
    }

    public static RoutingDecision definite(String agentId, String reason, String router) {
        return new RoutingDecision(agentId, 1.0, Confidence.DEFINITE, reason, router, false, List.of());
    }

    public static RoutingDecision high(String agentId, double score, String reason, String router) {
        return new RoutingDecision(agentId, score, Confidence.HIGH, reason, router, false, List.of());
    }

    public static RoutingDecision medium(String agentId, double score, String reason, String router) {
        return new RoutingDecision(agentId, score, Confidence.MEDIUM, reason, router, true, List.of());
    }

    public static RoutingDecision low(String agentId, String reason, String router) {
        return new RoutingDecision(agentId, 0.2, Confidence.LOW, reason, router, true, List.of());
    }

    public static RoutingDecision fallback(String reason) {
        return new RoutingDecision(null, 0, Confidence.FALLBACK, reason, "fallback", true, List.of());
    }

    public boolean isRoutable() { return score > 0.3 && targetAgentId != null; }

    public boolean isConfident() { return confidence == Confidence.DEFINITE || confidence == Confidence.HIGH; }

    public String getTargetAgentId() { return targetAgentId; }
    public double getScore() { return score; }
    public Confidence getConfidence() { return confidence; }
    public String getReason() { return reason; }
    public String getRouterUsed() { return routerUsed; }
    public boolean isRequiresConfirmation() { return requiresConfirmation; }
    public List<String> getAlternativeAgentIds() { return alternativeAgentIds; }

    @Override
    public String toString() {
        return "RoutingDecision{" +
                "target='" + targetAgentId + '\'' +
                ", score=" + score +
                ", confidence=" + confidence +
                ", reason='" + reason + '\'' +
                ", router='" + routerUsed + '\'' +
                '}';
    }
}
