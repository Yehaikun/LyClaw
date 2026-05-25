package lyjew.com.lyclaw.pipeline;

/**
 * Pipeline configuration properties under {@code lyclaw.pipeline}.
 */
public class PipelineProperties {

    /** Whether the stage pipeline is enabled. */
    private boolean enabled = true;

    /** Total pipeline execution timeout in milliseconds. */
    private long timeoutMs = 300_000;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public long getTimeoutMs() { return timeoutMs; }
    public void setTimeoutMs(long timeoutMs) { this.timeoutMs = timeoutMs; }
}
