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
 * Agent 注册中心默认实现，是整个多智能体系统的 Agent 目录服务。
 *
 * <h3>核心职责</h3>
 * 本类使用线程安全的 ConcurrentHashMap 作为底层存储，维护 Agent ID 到 AgentHandle
 * 的映射关系。它提供了完整的 CRUD 和查询能力：注册（register）、按 ID 精确查找
 * （lookup）、注销（unregister）、列出全部 Agent（listAll），以及多维度的筛选功能。
 *
 * <h3>筛选与排序机制</h3>
 * 支持三种筛选策略：
 * <ol>
 *   <li><b>按能力筛选（findByCapability）</b>——大小写不敏感地匹配 Agent 的能力标签，
 *       结果按创建时间（createdAt）升序排列，优先返回资历较老的 Agent。</li>
 *   <li><b>按状态筛选（findByState）</b>——精确匹配 Agent 生命周期状态
 *      （IDLE、RUNNING、WAITING、COMPLETED、FAILED、CANCELLED），
 *       同样按创建时间升序排列。</li>
 *   <li><b>查找可用 Agent（findAvailable）</b>——这是最复杂的筛选逻辑。首先过滤出
 *       状态为 IDLE 的空闲 Agent，然后检查其能力标签集合是否包含所有 requiredCapabilities
 *       （全匹配，大小写不敏感），最终按历史准确率（historicalAccuracy）降序排列，
 *       优先将任务分配给历史表现最好的 Agent。</li>
 * </ol>
 * 所有筛选方法返回不可修改的列表（Collectors.toUnmodifiableList()），防止外部代码
 * 意外修改内部状态。
 *
 * <h3>并发安全</h3>
 * 使用 ConcurrentHashMap 保证读写线程安全，支持多线程同时注册、查找和注销 Agent。
 * getStateDistribution() 方法使用 Stream API 的 groupingBy 进行实时聚合统计，
 * 返回的是调用时刻的快照数据。
 *
 * <h3>使用场景</h3>
 * 在编排流程中，PlanExecutionStage 通过本注册中心查找具备特定能力的 Agent 来执行
 * 规划好的任务节点；AutoScalerImpl 通过 size() 和 getStateDistribution() 了解集群
 * 规模以做出扩缩容决策；OrchestratorImpl 通过本中心获取可用 Agent 列表进行任务分派。
 *
 * @see lyjew.com.lyclaw.agent.AgentRegistry
 * @see lyjew.com.lyclaw.agent.AgentHandle
 * @see lyjew.com.lyclaw.agent.AgentState
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

    /**
     * 获取注册表中当前已注册的 Agent 总数。
     *
     * <p>返回 agentStore（ConcurrentHashMap）的当前大小。该数值实时反映系统的
     * Agent 规模，包括所有状态的 Agent（IDLE、RUNNING、WAITING、COMPLETED、
     * FAILED、CANCELLED）。可用于监控面板展示、扩缩容决策中的总容量参考，
     * 以及日志中的集群规模统计。</p>
     *
     * @return 已注册 Agent 的总数，最小为 0
     */
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

    /**
     * 获取注册表中所有 Agent 的不可变列表。
     *
     * <p>返回 agentStore 中所有 AgentHandle 值的快照副本（List.copyOf），
     * 包含所有状态的所有 Agent。由于返回的是不可变列表，调用方无法修改，
     * 但每个 AgentHandle 对象本身是可变的（如 state 字段可能被其他线程更新）。
     * 该列表反映调用时刻的快照状态，之后注册或注销的 Agent 不会体现。
     * 适用于前端展示全部 Agent 列表、批量状态巡检等场景。</p>
     *
     * @return 所有 Agent 的不可变列表，永远不会为 null（最小为空列表）
     */
    public List<AgentHandle> listAll() {
        return List.copyOf(agentStore.values());
    }

    /**
     * 获取各状态 Agent 的数量分布。
     *
     * <p>使用 Stream API 的 Collectors.groupingBy 对 agentStore 中所有 Agent
     * 按状态（AgentState）进行分组统计，返回每种状态下有多少个 Agent。
     * 结果是一个 Map，key 为 AgentState 枚举值（IDLE、RUNNING、WAITING、
     * COMPLETED、FAILED、CANCELLED），value 为该状态的 Agent 计数。
     * 该统计是实时计算的快照，用于监控面板的状态分布图表展示、
     * AutoScalerImpl 的扩容评估（计算空闲率），以及运维人员了解集群健康状态。
     * 注意：只有实际存在 Agent 的状态才会出现在返回的 Map 中，
     * 若某状态下没有 Agent，对应的 key 不会出现。</p>
     *
     * @return 各状态到 Agent 数量的映射，永远不会为 null
     */
    public Map<AgentState, Long> getStateDistribution() {
        return agentStore.values().stream()
                .collect(Collectors.groupingBy(AgentHandle::getState, Collectors.counting()));
    }
}
