package lyjew.com.lyclaw.agent;

import java.util.Map;

import lombok.Builder;
import lombok.Data;

/**
 * 代理任务，封装了分配给代理执行的一次工作单元。
 *
 * AgentTask 是 LyClaw 系统中的任务数据模型，记录了任务的唯一标识、
 * 类型分类、执行目标、负载数据以及扩展元数据。任务是不可变的（所有
 * 字段为 final），确保在并发调度过程中不会发生数据竞争。协调器根据
 * 任务的 type 字段匹配具备对应能力的代理，target 字段指定了任务的
 * 具体操作对象（如文件名、URL），payload 承载实际的任务内容（通常为
 * 提示词或结构化指令），metadata 为可选的键值对扩展信息。
 *
 * @see AgentCoordinator#dispatch
 */
public class AgentTask {

    /** 任务的全局唯一标识 */
    private final String taskId;
    /** 任务类型，如 "code_generation"、"summarization" */
    private final String type;
    /** 任务的操作目标，如文件名、URL 等 */
    private final String target;
    /** 任务负载，通常为提示词或结构化指令 */
    private final String payload;
    /** 任务的扩展元数据，键值对形式 */
    private final Map<String, Object> metadata;

    @Builder
    public AgentTask(String taskId, String type, String target,
                     String payload, Map<String, Object> metadata) {
        this.taskId = taskId;
        this.type = type;
        this.target = target;
        this.payload = payload;
        this.metadata = metadata;
    }

    /** @return 任务唯一标识 */
    public String getTaskId() { return taskId; }
    /** @return 任务类型 */
    public String getType() { return type; }
    /** @return 操作目标 */
    public String getTarget() { return target; }
    /** @return 任务负载 */
    public String getPayload() { return payload; }
    /** @return 扩展元数据 */
    public Map<String, Object> getMetadata() { return metadata; }
}
