package lyjew.com.lyclaw.pipeline.stage;

import lyjew.com.lyclaw.annotation.PipelineStage;
import lyjew.com.lyclaw.react.AgentContext;
import lyjew.com.lyclaw.react.SseEventTypes;
import lyjew.com.lyclaw.react.subagent.DelegateToAgentToolProvider;
import lyjew.com.lyclaw.tool.ToolProvider;
import lyjew.com.lyclaw.tool.ToolProviderRequest;
import lyjew.com.lyclaw.tool.ToolRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.codec.ServerSentEvent;
import reactor.core.publisher.Flux;

import java.util.HashMap;
import java.util.Map;

/**
 * Pipeline stage (order=2) that intercepts the request flow between
 * SecurityCheck and Respond to provide agent routing pre-processing.
 *
 * <p>This stage is optional — it enriches the context with routing information
 * but does not block or terminate the pipeline. The actual routing happens
 * in the Orchestrator via the delegate_to_agent tool.</p>
 */
@PipelineStage(name = "AgentRoute", after = lyjew.com.lyclaw.pipeline.stage.SecurityCheckStage.class,
               group = "POSTPROCESSING")
public class AgentRouteStage extends PipelineStageBase implements lyjew.com.lyclaw.pipeline.ReactivePipelineStage {

    private static final Logger log = LoggerFactory.getLogger(AgentRouteStage.class);

    @Override
    public int getOrder() { return 2; }

    @Override
    public String getStageName() { return "AgentRoute"; }

    @Override
    public Flux<ServerSentEvent<String>> execute(AgentContext ctx) {
        if (ctx.isTerminated()) {
            log.debug("AgentRouteStage: context terminated, skipping");
            return Flux.empty();
        }

        ctx.getCurrentStage().set("AgentRoute");
        log.debug("AgentRouteStage: checking routing for agent={}", ctx.getAgentId());

        // Check if there's delegation config on the request
        Map<String, Object> delConfig = getDelegationConfig(ctx);
        if (delConfig == null || delConfig.isEmpty()) {
            log.debug("AgentRouteStage: no delegation config, skipping");
            ctx.getCurrentStage().set("done");
            return Flux.empty();
        }

        String mode = (String) delConfig.getOrDefault("delegationMode", "none");
        if ("none".equals(mode)) {
            return Flux.empty();
        }

        // Emit routing status event
        Flux<ServerSentEvent<String>> routingEvent = Flux.just(
            sseEvent(SseEventTypes.STATUS, "正在匹配合适的 Agent...")
        );

        // Set routing attributes for downstream use
        ctx.setAttribute("agent.routing.mode", mode);
        ctx.setAttribute("agent.routing.checked", true);

        ctx.getCurrentStage().set("done");
        return routingEvent;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getDelegationConfig(AgentContext ctx) {
        if (ctx.getChatRequest() == null || ctx.getChatRequest().getExtras() == null) {
            return null;
        }
        Object config = ctx.getChatRequest().getExtras().get("agent.delegation");
        if (config instanceof Map) {
            return (Map<String, Object>) config;
        }
        return null;
    }
}
