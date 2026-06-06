package lyjew.com.lyclaw.mesh;

/**
 * Agent Mesh 全局事件监听器 —— 观察者模式。
 *
 * <p>订阅 AgentMesh 的事件总线，接收所有 Agent 的生命周期事件
 * 和调用完成事件。</p>
 */
@FunctionalInterface
public interface AgentMeshListener {

    /** 当 Mesh 中发生事件时调用 */
    void onMeshEvent(MeshEvent event);

    /** Mesh 事件类型 */
    enum MeshEventType {
        AGENT_REGISTERED,
        AGENT_UNREGISTERED,
        AGENT_LIFECYCLE_CHANGED,
        MESSAGE_SENT,
        MESSAGE_DELIVERED,
        MESSAGE_TIMEOUT,
        MESSAGE_ERROR
    }

    /** Mesh 事件 */
    class MeshEvent {
        private final MeshEventType type;
        private final String agentId;
        private final String message;
        private final long timestamp;

        public MeshEvent(MeshEventType type, String agentId, String message) {
            this.type = type;
            this.agentId = agentId;
            this.message = message;
            this.timestamp = System.currentTimeMillis();
        }

        public static MeshEvent of(MeshEventType type, String agentId, String message) {
            return new MeshEvent(type, agentId, message);
        }

        public MeshEventType getType() { return type; }
        public String getAgentId() { return agentId; }
        public String getMessage() { return message; }
        public long getTimestamp() { return timestamp; }
    }
}
