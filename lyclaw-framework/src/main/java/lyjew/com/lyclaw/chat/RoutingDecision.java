package lyjew.com.lyclaw.chat;

/**
 * 路由决策，由 {@link ModelRouter} 对请求分析后产出。
 *
 * <p>包含目标 Provider/模型、路由层级（CODE/COMPLEX/SIMPLE/STANDARD）和决策原因。
 */
public record RoutingDecision(
        String provider,
        String model,
        RoutingTier tier,
        String reason) {

    public static RoutingDecision to(String provider, String model, RoutingTier tier, String reason) {
        return new RoutingDecision(provider, model, tier, reason);
    }

    public static RoutingDecision toDefault(String provider, String model, RoutingTier tier) {
        return new RoutingDecision(provider, model, tier, "default");
    }
}
