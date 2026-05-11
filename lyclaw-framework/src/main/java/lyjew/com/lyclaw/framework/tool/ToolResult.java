package lyjew.com.lyclaw.framework.tool;

import java.util.HashMap;
import java.util.Map;

public class ToolResult {

    private final boolean success;
    private final String result;
    private final String error;
    private final long elapsedMs;
    private final int tokenUsage;
    private final String toolName;
    private final Map<String, Object> metadata;

    public ToolResult(boolean success, String result, String error,
                      long elapsedMs, int tokenUsage, String toolName,
                      Map<String, Object> metadata) {
        this.success = success;
        this.result = result != null ? result : "";
        this.error = error != null ? error : "";
        this.elapsedMs = elapsedMs;
        this.tokenUsage = tokenUsage;
        this.toolName = toolName != null ? toolName : "";
        this.metadata = metadata != null ? new HashMap<>(metadata) : new HashMap<>();
    }

    public static ToolResult success(String result) {
        return new ToolResult(true, result, "", 0L, 0, "", null);
    }

    public static ToolResult success(String result, String toolName) {
        return new ToolResult(true, result, "", 0L, 0, toolName, null);
    }

    public static ToolResult failure(String error) {
        return new ToolResult(false, "", error, 0L, 0, "", null);
    }

    public static ToolResult failure(String error, String toolName) {
        return new ToolResult(false, "", error, 0L, 0, toolName, null);
    }

    public static ToolResult fromLegacyActionResult(Object legacy) {
        if (legacy == null) {
            return failure("null legacy result");
        }
        try {
            Class<?> c = legacy.getClass();
            boolean success = (boolean) c.getMethod("isSuccess").invoke(legacy);
            String output = (String) c.getMethod("getOutput").invoke(legacy);
            String errorMsg = (String) c.getMethod("getErrorMessage").invoke(legacy);
            long duration = (long) c.getMethod("getDurationMs").invoke(legacy);
            String name = (String) c.getMethod("getToolName").invoke(legacy);
            @SuppressWarnings("unchecked")
            Map<String, Object> meta = (Map<String, Object>) c.getMethod("getMetadata").invoke(legacy);
            return new ToolResult(success,
                    output != null ? output : "",
                    errorMsg != null ? errorMsg : "",
                    duration, 0,
                    name != null ? name : "",
                    meta);
        } catch (Exception e) {
            return failure("Failed to convert legacy result: " + e.getMessage());
        }
    }

    public Object toLegacyActionResult() {
        try {
            Class<?> c = Class.forName("lyjew.com.lyclaw.action.tool.ToolResult");
            Object builder = c.getMethod("builder").invoke(null);
            builder.getClass().getMethod("toolName", String.class).invoke(builder, this.toolName);
            builder.getClass().getMethod("success", boolean.class).invoke(builder, this.success);
            builder.getClass().getMethod("output", String.class).invoke(builder, this.result);
            builder.getClass().getMethod("errorMessage", String.class).invoke(builder, this.error);
            builder.getClass().getMethod("durationMs", long.class).invoke(builder, this.elapsedMs);
            builder.getClass().getMethod("metadata", Map.class).invoke(builder, new HashMap<>(this.metadata));
            return builder.getClass().getMethod("build").invoke(builder);
        } catch (Exception e) {
            throw new RuntimeException("Failed to convert to legacy action result: " + e.getMessage(), e);
        }
    }

    public boolean isSuccess() {
        return success;
    }

    public String getResult() {
        return result;
    }

    public String getError() {
        return error;
    }

    public long getElapsedMs() {
        return elapsedMs;
    }

    public int getTokenUsage() {
        return tokenUsage;
    }

    public String getToolName() {
        return toolName;
    }

    public Map<String, Object> getMetadata() {
        return metadata;
    }

    @Override
    public String toString() {
        return "ToolResult{success=" + success
                + ", result='" + result + '\''
                + ", error='" + error + '\''
                + ", elapsedMs=" + elapsedMs
                + ", tokenUsage=" + tokenUsage
                + ", toolName='" + toolName + '\''
                + ", metadata=" + metadata + '}';
    }
}
