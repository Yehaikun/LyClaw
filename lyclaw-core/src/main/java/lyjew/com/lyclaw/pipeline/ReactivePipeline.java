package lyjew.com.lyclaw.pipeline;

import lyjew.com.lyclaw.context.ChatContext;
import org.springframework.http.codec.ServerSentEvent;
import reactor.core.publisher.Flux;

import java.util.List;

/**
 * Reactive pipeline that chains {@link ReactivePipelineStage} instances.
 */
public interface ReactivePipeline {

    /**
     * Execute all stages sequentially, concatenating their SSE event
     * fluxes.
     *
     * @param context       the chat context
     * @param pipelineCtx   shared mutable state
     * @return concatenated Flux of all stage SSE events
     */
    Flux<ServerSentEvent<String>> execute(ChatContext context, PipelineContext pipelineCtx);

    /** Ordered list of stages in this pipeline. */
    List<ReactivePipelineStage> getStages();
}
