package lyjew.com.lyclaw.action.agent.decomposition;

public class TaskEdge {

    private final String fromNodeId;
    private final String toNodeId;
    private final String condition;

    public TaskEdge(String fromNodeId, String toNodeId) {
        this.fromNodeId = fromNodeId;
        this.toNodeId = toNodeId;
        this.condition = "success";
    }

    public TaskEdge(String fromNodeId, String toNodeId, String condition) {
        this.fromNodeId = fromNodeId;
        this.toNodeId = toNodeId;
        this.condition = condition;
    }

    public String getFromNodeId() { return fromNodeId; }
    public String getToNodeId() { return toNodeId; }
    public String getCondition() { return condition; }

    @Override
    public String toString() {
        return fromNodeId + " → " + toNodeId + " [" + condition + "]";
    }
}
