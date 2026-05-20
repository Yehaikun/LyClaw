package lyjew.com.lyclaw.config;

/**
 * 硬编码系统默认值 —— 最低优先级的回退层。
 * 当 @Agent 注解和 lyclaw.agent.defaults 都没有提供值时使用。
 */
public final class AgentSystemDefaults {

    private AgentSystemDefaults() {}

    public static final String MODEL              = "deepseek-v4-flash";
    public static final String PROVIDER           = "deepseek";
    public static final String THINKING_DEFAULT   = "off";
    public static final String VERBOSE_DEFAULT    = "";
    public static final String REASONING_DEFAULT  = "";
    public static final boolean FAST_MODE         = false;
    public static final String CONTEXT_INJECTION  = "always";
    public static final int BOOTSTRAP_MAX_CHARS   = 20000;
    public static final int BOOTSTRAP_TOTAL_MAX_CHARS = 150000;
    public static final int CONTEXT_TOKENS        = 0;
    public static final String SANDBOX            = "none";
    public static final String DELEGATION_MODE    = "suggest";
    public static final int MAX_SPAWN_DEPTH       = 1;
    public static final int MAX_CHILDREN          = 5;
}
