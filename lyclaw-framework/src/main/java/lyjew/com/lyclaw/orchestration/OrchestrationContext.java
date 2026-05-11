package lyjew.com.lyclaw.orchestration;

import lombok.Builder;
import lombok.Data;
import lyjew.com.lyclaw.agent.AgentTask;
import lyjew.com.lyclaw.context.ChatContext;

import java.util.List;
import java.util.Map;

/**
 * 编排上下文，封装单次多智能体编排任务所需的全部运行时信息。
 *
 * <p>该类作为编排流程的核心数据载体，贯穿从任务创建到最终结果输出的整个生命周期。
 * 包含当前对话上下文、待执行的任务列表、协作模式标识以及可扩展的自定义属性映射，
 * 允许各编排节点在运行时灵活存取任意键值对数据。
 */
@Data
@Builder
public class OrchestrationContext {

    /** 当前对话上下文，包含对话历史和消息记录 */
    private ChatContext chatContext;
    /** 待编排执行的智能体任务集合 */
    private List<AgentTask> tasks;
    /** 协作模式标识符，指定多智能体间的协作策略 */
    private String collaborationModeId;
    /** 可扩展的运行时属性映射，用于节点间传递自定义数据 */
    private Map<String, Object> attributes;
    /** 本次编排协作的唯一标识 */
    private String collaborationId;

    /**
     * 根据 key 获取运行时属性值，支持泛型类型推断。
     *
     * @param key  属性键名
     * @param <T>  返回值类型
     * @return 属性值，若键不存在则返回 null
     */
    @SuppressWarnings("unchecked")
    public <T> T getAttribute(String key) {
        return (T) attributes.get(key);
    }

    /**
     * 设置运行时属性值。
     *
     * @param key   属性键名
     * @param value 属性值
     */
    public void setAttribute(String key, Object value) {
        attributes.put(key, value);
    }
}
