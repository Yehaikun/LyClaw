package lyjew.com.lyclaw.agent;

import java.util.List;
import java.util.Optional;

/**
 * 代理注册表接口，是所有代理实例的中央目录和发现服务。
 *
 * AgentRegistry 维护系统中所有活跃代理的注册信息，提供代理的增删查改
 * 功能。它是任务调度时的核心查询入口：协调器通过注册表按能力、按状态、
 * 或按组合条件来筛选合适的代理。注册表的典型实现包括内存哈希表（单机）
 * 和分布式一致性存储（集群）。查询接口使用 Optional 容器，避免了空指针
 * 返回，调用方需要显式处理代理不存在的情况。
 */
public interface AgentRegistry {

    /**
     * 向注册表中注册一个代理句柄。
     *
     * @param agent 待注册的代理句柄，需包含唯一 agentId
     */
    void register(AgentHandle agent);

    /**
     * 根据代理标识查找代理。
     *
     * @param agentId 代理唯一标识
     * @return 如果找到则返回包含 AgentHandle 的 Optional，否则返回 empty
     */
    Optional<AgentHandle> lookup(String agentId);

    /**
     * 按指定能力查找所有具备该能力的代理。
     *
     * @param capability 能力名称，如 "code_generation"、"data_analysis"
     * @return 具备该能力的代理句柄列表，可能为空列表
     */
    List<AgentHandle> findByCapability(String capability);

    /**
     * 按指定状态查找所有处于该状态的代理。
     *
     * @param state 代理状态枚举值，如 IDLE、RUNNING
     * @return 处于该状态的代理句柄列表
     */
    List<AgentHandle> findByState(AgentState state);

    /**
     * 查找同时满足所有必需能力的空闲代理。
     *
     * @param requiredCapabilities 代理必须具备的能力列表
     * @return 满足全部能力要求且可用的代理句柄列表
     */
    List<AgentHandle> findAvailable(List<String> requiredCapabilities);
}
