package lyjew.com.lyclaw.task;

/**
 * 任务执行结果实体，记录单个任务节点的最终执行结果。
 * 包含成功标志、输出内容、错误信息、耗时和Token用量。
 * 所有字段均为 final，保证不可变性。
 */
public class TaskResult {

    /** 节点ID */
    private final String nodeId;
    /** 执行是否成功 */
    private final boolean success;
    /** 执行输出内容 */
    private final String output;
    /** 错误信息（成功时为空） */
    private final String error;
    /** 执行耗时（毫秒） */
    private final long elapsedMs;
    /** Token用量信息 */
    private final String tokenUsage;

    /**
     * 构造任务执行结果，所有字段在构造时确定，之后不可变。
     */
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
