package lyjew.com.lyclaw.reflect.topology;

import java.util.*;

/**
 * 节点定义——描述拓扑中一个原语实例的引用。
 */
public class NodeDef {
    private String nodeId;
    private PrimitiveType primitiveType;
    private String implementationName;
    private Map<String, Object> config = new LinkedHashMap<>();
    private ReflectionTopology subTopology;
    private long timeoutMs = 30_000L;

    public NodeDef() {}
    public NodeDef(String nodeId, PrimitiveType type, String implName) {
        this.nodeId = nodeId; this.primitiveType = type; this.implementationName = implName;
    }

    public String getNodeId() { return nodeId; }
    public void setNodeId(String v) { this.nodeId = v; }
    public PrimitiveType getPrimitiveType() { return primitiveType; }
    public void setPrimitiveType(PrimitiveType v) { this.primitiveType = v; }
    public String getImplementationName() { return implementationName; }
    public void setImplementationName(String v) { this.implementationName = v; }
    public Map<String, Object> getConfig() { return config; }
    public void setConfig(Map<String, Object> v) { this.config = v; }
    public ReflectionTopology getSubTopology() { return subTopology; }
    public void setSubTopology(ReflectionTopology v) { this.subTopology = v; }
    public long getTimeoutMs() { return timeoutMs; }
    public void setTimeoutMs(long v) { this.timeoutMs = v; }

    @Override public String toString() { return "NodeDef{" + nodeId + " " + primitiveType + ":" + implementationName + "}"; }
}
