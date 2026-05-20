package lyjew.com.lyclaw.react.subagent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import lyjew.com.lyclaw.react.AgentContext;
import lyjew.com.lyclaw.react.AgentHook;

/**
 * Sub-interface of {@link AgentHook} that defines lifecycle callbacks
 * specific to subagent spawning.
 *
 * <p>The three extension points cover the full subagent lifecycle:</p>
 * <ol>
 *   <li>{@link #subagentSpawning(AgentContext)} — before the child pipeline
 *       starts; {@code childCtx} is fully prepared (system prompt, tools,
 *       sandbox).</li>
 *   <li>{@link #subagentSpawned(AgentContext, SubagentResult)} — after the
 *       pipeline completes but before the result is returned to the parent;
 *       the {@code result} may be modified at this point.</li>
 *   <li>{@link #subagentEnded(AgentContext, SubagentResult)} — after the
 *       result has been logged but before the session is archived; intended
 *       for cleanup and audit.</li>
 * </ol>
 *
 * <p>All three methods are default (empty). <strong>Exceptions thrown by any
 * hook method are caught and logged at WARN level by the subagent
 * orchestrator; they never interrupt the subagent pipeline.</strong></p>
 *
 * <p>Since this interface extends {@link AgentHook}, an implementation
 * registered as a {@code SubagentHook} also participates in all of the
 * parent-level hook callbacks defined on that interface.</p>
 */
public interface SubagentHook extends AgentHook {

    /** Logger name used by the orchestrator when reporting hook failures. */
    String HOOK_LOGGER_NAME = "lyjew.com.lyclaw.react.subagent.SubagentHook";

    /**
     * Called before the child pipeline starts execution.
     * {@code childCtx} is fully initialised at this point.
     *
     * @param childCtx the prepared child agent context
     */
    default void subagentSpawning(AgentContext childCtx) {
        // no-op by default
    }

    /**
     * Called after the child pipeline completes, before the result is
     * returned to the parent. Implementations may mutate {@code result}
     * (e.g. to redact sensitive output or attach additional metadata).
     *
     * @param childCtx the child agent context (pipeline completed)
     * @param result   the subagent result (mutable)
     */
    default void subagentSpawned(AgentContext childCtx, SubagentResult result) {
        // no-op by default
    }

    /**
     * Called after the result has been logged but before the child session
     * is archived. Suitable for resource cleanup, audit-log writes, and
     * notification dispatch. Modifications to {@code result} at this stage
     * have no effect on what was already returned to the parent.
     *
     * @param childCtx the child agent context
     * @param result   the subagent result (read-only reference)
     */
    default void subagentEnded(AgentContext childCtx, SubagentResult result) {
        // no-op by default
    }

    /**
     * Convenience: invoke a hook method, catching and logging any exception
     * so that a misbehaving hook never disrupts the subagent pipeline.
     *
     * @param action     the hook callback to run
     * @param methodName human-readable method name for the log message
     */
    static void safelyExecute(Runnable action, String methodName) {
        try {
            action.run();
        } catch (Exception e) {
            Logger log = LoggerFactory.getLogger(HOOK_LOGGER_NAME);
            log.warn("[SubagentHook] {} threw an exception — "
                    + "pipeline continues unaffected", methodName, e);
        }
    }
}
