package lyjew.com.lyclaw.task;

/**
 * 任务执行结果值对象 —— 包含执行状态、输出内容、错误信息、耗时和 Token 用量。
 *
 * @since 1.0
 * @author LyClaw Team
 */
public class TaskResult {

    private final String nodeId;
    private final boolean success;
    private final String output;
    private final String error;
    private final long elapsedMs;
    private final String tokenUsage;

    public TaskResult(String nodeId, boolean success, String output,
                      String error, long elapsedMs, String tokenUsage) {
        this.nodeId = nodeId;
        this.success = success;
        this.output = output;
        this.error = error;
        this.elapsedMs = elapsedMs;
        this.tokenUsage = tokenUsage;
    }

    public String getNodeId() { return nodeId; }
    public boolean isSuccess() { return success; }
    public String getOutput() { return output; }
    public String getError() { return error; }
    public long getElapsedMs() { return elapsedMs; }
    public String getTokenUsage() { return tokenUsage; }
}