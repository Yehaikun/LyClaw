package lyjew.com.lyclaw.storage;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 默认记忆写策略管理器实现。
 *
 * <p>维护策略名称到 MemoryPersistence 的映射，以及各层级的默认策略。
 * evaluate() 根据层级找到对应的默认策略并委托其判断。
 */
public class DefaultMemoryWriteManager implements MemoryWriteManager {

    private final Map<String, MemoryPersistence> policies = new ConcurrentHashMap<>();
    private final Map<String, Set<MemoryLayer>> policyLayers = new ConcurrentHashMap<>();
    private final Map<MemoryLayer, String> layerDefaults = new ConcurrentHashMap<>();

    @Override
    public void register(String name, MemoryPersistence policy, Set<MemoryLayer> layers) {
        policies.put(name, policy);
        policyLayers.put(name, layers);
    }

    /** 设置某层级的默认策略 */
    public void setLayerDefault(MemoryLayer layer, String policyName) {
        layerDefaults.put(layer, policyName);
    }

    @Override
    public PersistenceDecision evaluate(MemoryLayer layer, MemoryWriteState state) {
        String policyName = layerDefaults.get(layer);
        if (policyName == null) {
            // 选第一个声明支持该层级的策略
            for (Map.Entry<String, Set<MemoryLayer>> entry : policyLayers.entrySet()) {
                if (entry.getValue().contains(layer)) {
                    policyName = entry.getKey();
                    break;
                }
            }
        }
        if (policyName == null) {
            return new PersistenceDecision(false, "没有可用的写策略", "none");
        }
        MemoryPersistence policy = policies.get(policyName);
        if (policy == null) {
            return new PersistenceDecision(false, "写策略未注册: " + policyName, policyName);
        }
        // 简单启发式：如果有 pending 条目就建议写入
        int pending = state.getPendingCounts() != null
                ? state.getPendingCounts().getOrDefault(layer, 0) : 0;
        boolean shouldWrite = pending > 0;
        return new PersistenceDecision(shouldWrite,
                shouldWrite ? "有 " + pending + " 条待写入记忆" : "无待写入记忆",
                policyName);
    }
}
