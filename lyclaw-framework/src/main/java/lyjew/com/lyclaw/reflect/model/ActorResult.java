package lyjew.com.lyclaw.reflect.model;

import java.util.*;

public class ActorResult {
    private String output;
    private List<ToolCallRecord> toolCalls = new ArrayList<>();
    private int successCount;
    private int failCount;
    private String taskSummary;
    private Map<String, Object> metadata = new LinkedHashMap<>();

    public ActorResult() {}
    public ActorResult(String output) { this.output = output; }

    public String getOutput() { return output; }
    public void setOutput(String v) { this.output = v; }
    public List<ToolCallRecord> getToolCalls() { return toolCalls; }
    public void setToolCalls(List<ToolCallRecord> v) { this.toolCalls = v; }
    public int getSuccessCount() { return successCount; }
    public void setSuccessCount(int v) { this.successCount = v; }
    public int getFailCount() { return failCount; }
    public void setFailCount(int v) { this.failCount = v; }
    public String getTaskSummary() { return taskSummary; }
    public void setTaskSummary(String v) { this.taskSummary = v; }
    public Map<String, Object> getMetadata() { return metadata; }
    public void setMetadata(Map<String, Object> v) { this.metadata = v; }

    @Override public String toString() { return "ActorResult{output=" + (output != null ? output.substring(0, Math.min(80, output.length())) + "..." : "null") + ", success=" + successCount + ", fail=" + failCount + "}"; }

    public static class ToolCallRecord {
        private String toolName;
        private String toolCallId;
        private String arguments;
        private String result;
        private boolean success;

        public ToolCallRecord() {}
        public ToolCallRecord(String toolName, String toolCallId, String arguments, String result, boolean success) {
            this.toolName = toolName; this.toolCallId = toolCallId; this.arguments = arguments;
            this.result = result; this.success = success;
        }
        public String getToolName() { return toolName; }
        public void setToolName(String v) { this.toolName = v; }
        public String getToolCallId() { return toolCallId; }
        public void setToolCallId(String v) { this.toolCallId = v; }
        public String getArguments() { return arguments; }
        public void setArguments(String v) { this.arguments = v; }
        public String getResult() { return result; }
        public void setResult(String v) { this.result = v; }
        public boolean isSuccess() { return success; }
        public void setSuccess(boolean v) { this.success = v; }
    }
}
