package lyjew.com.lyclaw.tool;

public class ToolResult {

    private final boolean success;
    private final String result;
    private final String error;
    private final long elapsedMs;
    private final int tokenUsage;

    public ToolResult(boolean success, String result, String error,
                      long elapsedMs, int tokenUsage) {
        this.success = success;
        this.result = result;
        this.error = error;
        this.elapsedMs = elapsedMs;
        this.tokenUsage = tokenUsage;
    }

    public static ToolResult success(String result) {
        return new ToolResult(true, result, null, 0L, 0);
    }

    public static ToolResult failure(String error) {
        return new ToolResult(false, null, error, 0L, 0);
    }

    public boolean isSuccess() { return success; }

    public String getResult() { return result; }

    public String getError() { return error; }

    public long getElapsedMs() { return elapsedMs; }

    public int getTokenUsage() { return tokenUsage; }
}
