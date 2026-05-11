package lyjew.com.lyclaw.agent.scaling;

import java.util.concurrent.CompletableFuture;

public interface AutoScaler {

    ScalingDecision evaluate(AgentPoolSnapshot snapshot);
    CompletableFuture<ScalingResult> apply(ScalingDecision decision);
}
