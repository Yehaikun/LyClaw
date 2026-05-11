package lyjew.com.lyclaw.orchestration;

import lombok.Builder;
import lombok.Data;

import java.util.Map;

/**
 * 智能体事件，表示编排过程中单个 Agent 执行生命周期内的关键事件。
 *
 * <p>该类用于在多智能体协作过程中传递状态变更信号，支持任务启动/完成/失败、
 * 智能体状态切换、协作开始/结束以及共识达成等多种事件类型。
 * 每个事件携带事件类型、来源 Agent、数据荷载、元数据和时间戳。
 */
@Data
@Builder
public class AgentEvent {

    /** 智能体事件类型枚举，覆盖从任务执行到协作通信的全生命周期 */
    public enum EventType {
        /** 任务已启动 */
        TASK_STARTED,
        /** 任务执行中，携带进度信息 */
        TASK_PROGRESS,
        /** 任务已成功完成 */
        TASK_COMPLETED,
        /** 任务执行失败 */
        TASK_FAILED,
        /** 智能体运行状态发生变化 */
        AGENT_STATE_CHANGED,
        /** 多智能体协作开始 */
        COLLABORATION_STARTED,
        /** 多智能体协作结束 */
        COLLABORATION_ENDED,
        /** 多智能体达成共识 */
        CONSENSUS_REACHED,
        /** 收到来自其他 Agent 的消息 */
        MESSAGE_RECEIVED,
        /** 触发告警 */
        ALERT_TRIGGERED
    }

    /** 事件类型 */
    private EventType type;
    /** 触发该事件的 Agent 标识 */
    private String agentId;
    /** 事件携带的数据荷载（JSON 格式） */
    private String data;
    /** 事件相关的扩展元数据 */
    private Map<String, Object> metadata;
    /** 事件产生的时间戳（毫秒） */
    private long timestamp;
}
