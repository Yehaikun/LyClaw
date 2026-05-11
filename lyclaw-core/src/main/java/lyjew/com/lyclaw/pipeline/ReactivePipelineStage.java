package lyjew.com.lyclaw.pipeline;

import lyjew.com.lyclaw.context.ChatContext;
import org.springframework.http.codec.ServerSentEvent;
import reactor.core.publisher.Flux;

/**
 * Reactive counterpart to the deprecated {@link PipelineStage}.
 * <p>
 * Each stage returns a {@link Flux} of SSE events. The pipeline
 * concatenates all stage fluxes. A stage that detects early termination
 * (via {@link PipelineContext#isTerminated()}) returns {@link Flux#empty()}.
 */
public interface ReactivePipelineStage {

    /**
     * Execute this stage and emit SSE events.
     *
     * @param context       the chat context (request, session, tracing, etc.)
     * @param pipelineCtx   mutable shared state carried across stages
     * @return a Flux of SSE events; empty if the pipeline was terminated upstream
     */
    Flux<ServerSentEvent<String>> execute(ChatContext context, PipelineContext pipelineCtx);

    /** Execution order (lower = earlier). */
    int getOrder();

    /** Human-readable stage name for logging and inspection. */
    String getStageName();
}
