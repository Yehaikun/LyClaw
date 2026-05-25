package lyjew.com.lyclaw.tool;

/**
 * Tool configuration properties under {@code lyclaw.tool}.
 * Provides sensible defaults so users only need to configure overrides.
 */
public class ToolProperties {

    /** Default timeout per tool invocation in milliseconds. */
    private long defaultTimeoutMs = 30_000;

    /** Max output length in characters before truncation. */
    private int maxOutputLength = 10_000;

    /** Max invocations of a single tool per session. */
    private int maxCallsPerTool = 20;

    /** Max retries on tool invocation failure. */
    private int maxRetries = 3;

    /** Max total tool rounds per ReAct cycle. */
    private int maxRounds = 10;

    /** Sandbox isolation level: DIRECT, SANDBOX, or PROCESS. */
    private String sandboxLevel = "PROCESS";

    /** Tavily search API key. */
    private String tavilyApiKey = "";

    public long getDefaultTimeoutMs() { return defaultTimeoutMs; }
    public void setDefaultTimeoutMs(long defaultTimeoutMs) { this.defaultTimeoutMs = defaultTimeoutMs; }
    public int getMaxOutputLength() { return maxOutputLength; }
    public void setMaxOutputLength(int maxOutputLength) { this.maxOutputLength = maxOutputLength; }
    public int getMaxCallsPerTool() { return maxCallsPerTool; }
    public void setMaxCallsPerTool(int maxCallsPerTool) { this.maxCallsPerTool = maxCallsPerTool; }
    public int getMaxRetries() { return maxRetries; }
    public void setMaxRetries(int maxRetries) { this.maxRetries = maxRetries; }
    public int getMaxRounds() { return maxRounds; }
    public void setMaxRounds(int maxRounds) { this.maxRounds = maxRounds; }
    public String getSandboxLevel() { return sandboxLevel; }
    public void setSandboxLevel(String sandboxLevel) { this.sandboxLevel = sandboxLevel; }
    public String getTavilyApiKey() { return tavilyApiKey; }
    public void setTavilyApiKey(String tavilyApiKey) { this.tavilyApiKey = tavilyApiKey; }
}
