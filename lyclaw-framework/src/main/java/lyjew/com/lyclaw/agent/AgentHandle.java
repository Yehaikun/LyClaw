package lyjew.com.lyclaw.agent;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * 代理句柄，包含一个代理实例的元信息与运行时状态快照。
 *
 * AgentHandle 是代理在注册表中的轻量级表示，记录了代理的标识、名称、
 * 当前状态、能力列表、创建时间以及历史准确率。协调器通过句柄来发现和
 * 筛选合适的代理，而无需直接持有代理实例。历史准确率用于基于信誉的
 * 任务分配策略，让系统优先选择准确率更高的代理来执行关键任务。
 *
 * 使用 Lombok 自动生成 getter/setter/Builder 等方法。
 */
@Data
@Builder
public class AgentHandle {
    /** 代理唯一标识 */
    private String agentId;
    /** 代理的友好名称 */
    private String name;
    /** 代理当前运行时状态 */
    private AgentState state;
    /** 代理具备的能力列表，用于任务匹配 */
    private List<String> capabilities;
    /** 代理创建时间戳（毫秒） */
    private long createdAt;
    /** 代理历史任务执行准确率（0.0 ~ 1.0），用于信誉评估 */
    private double historicalAccuracy;
}
