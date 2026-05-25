package lyjew.com.lyclaw.reflect.registry;

import lyjew.com.lyclaw.reflect.topology.ReflectionTopology;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 管理 Agent 到拓扑的映射关系。支持多层解析优先级。
 */
public class ReflectionTopologyRegistry {

    private final Map<String, ReflectionTopology> registry = new ConcurrentHashMap<>();
    private ReflectionTopology defaultTopology;

    /** 注册拓扑 */
    public void register(String agentId, ReflectionTopology topology) {
        registry.put(agentId, topology);
    }

    /** 运行时注册动态生成的拓扑 */
    public void registerDynamic(String agentId, ReflectionTopology topology) {
        registry.put(agentId, topology);
    }

    /** 查找拓扑 */
    public Optional<ReflectionTopology> resolve(String agentId) {
        ReflectionTopology t = registry.get(agentId);
        if (t != null) return Optional.of(t);
        if (defaultTopology != null) return Optional.of(defaultTopology);
        return Optional.empty();
    }

    /** 注销拓扑 */
    public void unregister(String agentId) {
        registry.remove(agentId);
    }

    /** 列出所有已注册的拓扑 */
    public Map<String, ReflectionTopology> listAll() {
        return new LinkedHashMap<>(registry);
    }

    /** 设置默认拓扑（当 agentId 无匹配时使用） */
    public void setDefaultTopology(ReflectionTopology topology) {
        this.defaultTopology = topology;
    }

    public ReflectionTopology getDefaultTopology() {
        return defaultTopology;
    }
}
