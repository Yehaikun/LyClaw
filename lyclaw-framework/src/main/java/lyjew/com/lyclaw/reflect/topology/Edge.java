package lyjew.com.lyclaw.reflect.topology;

import java.util.*;

/**
 * 边定义——描述节点之间的有向连接及其流转条件。
 */
public class Edge {
    private String edgeId;
    private EdgeType edgeType = EdgeType.SEQUENTIAL;
    private List<String> from = new ArrayList<>();
    private List<String> to = new ArrayList<>();
    private EdgeCondition condition = EdgeCondition.ALWAYS;
    private String conditionValue;

    public Edge() {}
    public Edge(String edgeId, String from, String to, EdgeCondition condition) {
        this.edgeId = edgeId; this.from.add(from); this.to.add(to); this.condition = condition;
    }

    public String getEdgeId() { return edgeId; }
    public void setEdgeId(String v) { this.edgeId = v; }
    public EdgeType getEdgeType() { return edgeType; }
    public void setEdgeType(EdgeType v) { this.edgeType = v; }
    public List<String> getFrom() { return from; }
    public void setFrom(List<String> v) { this.from = v; }
    public List<String> getTo() { return to; }
    public void setTo(List<String> v) { this.to = v; }
    public EdgeCondition getCondition() { return condition; }
    public void setCondition(EdgeCondition v) { this.condition = v; }
    public String getConditionValue() { return conditionValue; }
    public void setConditionValue(String v) { this.conditionValue = v; }

    public String getSingleFrom() { return from.isEmpty() ? null : from.get(0); }
    public String getSingleTo() { return to.isEmpty() ? null : to.get(0); }

    @Override public String toString() { return "Edge{" + from + "->" + to + " " + condition + "}"; }
}
