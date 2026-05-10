package lyjew.com.lyclaw.task;

import java.time.Instant;

public class TaskRecord {

    private final String taskId;
    private final String nodeId;
    private final String status;
    private final TaskResult result;
    private final String error;
    private final Instant startedAt;
    private final Instant completedAt;

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
