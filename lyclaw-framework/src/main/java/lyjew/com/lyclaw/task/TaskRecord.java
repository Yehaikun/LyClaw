package lyjew.com.lyclaw.task;

import java.time.Instant;

/**
 * 任务记录实体，记录单个任务节点的完整执行信息。
 * 包含任务标识、状态、结果、错误信息以及开始/完成时间戳。
 * 所有字段均为 final，保证不可变性。
 */
public class TaskRecord {

    /** 所属任务ID */
    private final String taskId;
    /** 节点ID */
    private final String nodeId;
    /** 执行状态 */
    private final String status;
    /** 执行结果 */
    private final TaskResult result;
    /** 错误信息 */
    private final String error;
    /** 开始时间 */
    private final Instant startedAt;
    /** 完成时间 */
    private final Instant completedAt;

    /**
     * 构造任务记录，所有字段在构造时确定，之后不可变。
     */
    public TaskRecord(String taskId, String nodeId, String status,
                      TaskResult result, String error,
                      Instant startedAt, Instant completedAt) {
        this.taskId = taskId;
        this.nodeId = nodeId;
        this.status = status;
        this.result = result;
        this.error = error;
        this.startedAt = startedAt;
        this.completedAt = completedAt;
    }

    public String getTaskId() { return taskId; }
    public String getNodeId() { return nodeId; }
    public String getStatus() { return status; }
    public TaskResult getResult() { return result; }
    public String getError() { return error; }
    public Instant getStartedAt() { return startedAt; }
    public Instant getCompletedAt() { return completedAt; }
}
