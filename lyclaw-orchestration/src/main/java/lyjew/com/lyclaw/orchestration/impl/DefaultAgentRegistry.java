package lyjew.com.lyclaw.orchestration.impl;

import lombok.extern.slf4j.Slf4j;
import lyjew.com.lyclaw.agent.AgentHandle;
import lyjew.com.lyclaw.agent.AgentRegistry;
import lyjew.com.lyclaw.agent.AgentState;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Agent 注册中心默认实现。
 *
 * 基于 ConcurrentHashMap 维护 Agent 的注册表。
 * 支持按 ID 查找、按能力筛选、按状态筛选以及多能力复合筛选。
 * 筛选结果按创建时间或历史准确率排序。
 */
@Slf4j
@Service
public class DefaultAgentRegistry implements AgentRegistry {

    /** Agent 存储：agentId -> AgentHandle */
    private final ConcurrentHashMap<String, AgentHandle> agentStore = new ConcurrentHashMap<>();

    /**
     * 注册 Agent。相同 agentId 会覆盖已有的。
     *
     * @param agent Agent 句柄
     */
    @Override
    public void register(AgentHandle agent) {
        if (agent == null || agent.getAgentId() == null) {
            log.warn("[AgentRegistry] Cannot register null agent or agent with null ID");
            return;
        }

        AgentHandle existing = agentStore.put(agent.getAgentId(), agent);
        if (existing != null) {
            log.info("[AgentRegistry] Replaced agent: agentId={}, oldState={}, newState={}",
                    agent.getAgentId(), existing.getState(), agent.getState());
        } else {
            log.info("[AgentRegistry] Registered agent: agentId={}, name={}, capabilities={}",
                    agent.getAgentId(), agent.getName(), agent.getCapabilities());
        }
    }

    /**
     * 按 ID 查找 Agent。
     *
     * @param agentId Agent ID
     * @return 包含 AgentHandle 的 Optional
     */
    @Override
    public Optional<AgentHandle> lookup(String agentId) {
        if (agentId == null || agentId.isEmpty()) {
            return Optional.empty();
        }
        return Optional.ofNullable(agentStore.get(agentId));
    }

    /**
     * 按能力名称查找（大小写不敏感），按创建时间升序。
     *
     * @param capability 能力名称
     * @return 匹配的 Agent 列表
     */
    @Override
    public List<AgentHandle> findByCapability(String capability) {
        if (capability == null || capability.isEmpty()) {
            return Collections.emptyList();
        }

        return agentStore.values().stream()
                .filter(a -> a.getCapabilities() != null
                        && a.getCapabilities().stream()
                                .anyMatch(c -> c.equalsIgnoreCase(capability)))
                .sorted(Comparator.comparingLong(AgentHandle::getCreatedAt))
                .collect(Collectors.toUnmodifiableList());
    }

    /**
     * 按状态筛选 Agent，按创建时间升序。
     *
     * @param state 目标状态
     * @return 匹配的 Agent 列表
     */
    @Override
    public List<AgentHandle> findByState(AgentState state) {
        if (state == null) {
            return Collections.emptyList();
        }

        return agentStore.values().stream()
                .filter(a -> a.getState() == state)
                .sorted(Comparator.comparingLong(AgentHandle::getCreatedAt))
                .collect(Collectors.toUnmodifiableList());
    }

    /**
     * 查找空闲且满足所有能力要求的 Agent。
     * 结果按历史准确率降序排列，优先选择准确率最高的 Agent。
     * 如果未指定能力要求，返回所有空闲 Agent。
     *
     * @param requiredCapabilities 所需能力列表
     * @return 可用的 Agent 列表，按准确率降序
     */
    @Override
    public List<AgentHandle> findAvailable(List<String> requiredCapabilities) {
        if (requiredCapabilities == null || requiredCapabilities.isEmpty()) {
            return findByState(AgentState.IDLE);
        }

        return agentStore.values().stream()
                .filter(a -> a.getState() == AgentState.IDLE)  // 必须空闲
                .filter(a -> {
                    if (a.getCapabilities() == null || a.getCapabilities().isEmpty()) {
                        return false;
                    }
                    // 将 Agent 能力集转为小写集合进行匹配
                    Set<String> caps = a.getCapabilities().stream()
                            .map(String::toLowerCase)
                            .collect(Collectors.toSet());
                    // 所有要求的能力都必须被 Agent 满足（全匹配）
                    return requiredCapabilities.stream()
                            .map(String::toLowerCase)
                            .allMatch(caps::contains);
                })
                .sorted(Comparator.comparingDouble(AgentHandle::getHistoricalAccuracy).reversed())
                .collect(Collectors.toUnmodifiableList());
    }

    /** @return 注册表中 Agent 总数 */
    public int size() {
        return agentStore.size();
    }

    /**
     * 注销 Agent。
     *
     * @param agentId Agent ID
     * @return 被移除的 AgentHandle，不存在返回 null
     */
    public AgentHandle unregister(String agentId) {
        AgentHandle removed = agentStore.remove(agentId);
        if (removed != null) {
            log.info("[AgentRegistry] Unregistered agent: agentId={}, name={}", agentId, removed.getName());
        }
        return removed;
    }

    /** @return 所有 Agent 的不可变列表 */
    public List<AgentHandle> listAll() {
        return List.copyOf(agentStore.values());
    }

    /** @return 各状态 Agent 的数量分布 */
    public Map<AgentState, Long> getStateDistribution() {
        return agentStore.values().stream()
                .collect(Collectors.groupingBy(AgentHandle::getState, Collectors.counting()));
    }
}
