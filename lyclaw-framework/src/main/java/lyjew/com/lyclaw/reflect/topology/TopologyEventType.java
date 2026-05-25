package lyjew.com.lyclaw.reflect.topology;

/**
 * 执行事件类型枚举。
 */
public enum TopologyEventType {
    NODE_START,
    NODE_END,
    NODE_ERROR,
    FORK_START,
    JOIN_COMPLETE,
    ITERATION_START,
    MESSAGE,
    REFLECTION,
    EVALUATION,
    MEMORY_STORE,
    MEMORY_RETRIEVE,
    RETRIEVAL_DECISION,
    NESTED_TOPOLOGY_START,
    NESTED_TOPOLOGY_END,
    TOPOLOGY_START,
    TOPOLOGY_END,
    ACTOR_OUTPUT,
    EVALUATOR_COMPLETE,
    ROUTER_DECISION,
    REFLECTOR_COMPLETE,
    SYNTHESIS_COMPLETE,
    ACTOR_CHUNK,
    ACTOR_TOOL_CALL,
    REFLECTOR_CHUNK,
    EVALUATOR_CHUNK,
    ROUTER_CHUNK,
    SYNTHESIZER_CHUNK;

    /** 流式块事件仅用于SSE实时推送，不应持久化到JSONL */
    public boolean isStreamingChunk() {
        return this == ACTOR_CHUNK || this == EVALUATOR_CHUNK
            || this == REFLECTOR_CHUNK || this == ROUTER_CHUNK
            || this == SYNTHESIZER_CHUNK;
    }
}
