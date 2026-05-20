package lyjew.com.lyclaw.react;

/**
 * Hook 注册记录 —— 对标 OpenClaw 的 PluginHookRegistration。
 */
public class HookRegistration {

    private final String pluginId;
    private final String hookName;
    private final int priority;
    private final long timeoutMs;
    private final String source;

    public HookRegistration(String pluginId, String hookName, int priority, long timeoutMs, String source) {
        this.pluginId = pluginId;
        this.hookName = hookName;
        this.priority = priority;
        this.timeoutMs = timeoutMs;
        this.source = source;
    }

    public String getPluginId() { return pluginId; }
    public String getHookName() { return hookName; }
    public int getPriority() { return priority; }
    public long getTimeoutMs() { return timeoutMs; }
    public String getSource() { return source; }

    @Override
    public String toString() {
        return "HookRegistration{pluginId='" + pluginId + "', hookName='" + hookName +
               "', priority=" + priority + "}";
    }
}
