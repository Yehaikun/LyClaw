package lyjew.com.lyclaw.config;

/**
 * 思考深度级别枚举 — 替代原先的字符串比较和 switch 硬编码。
 *
 * <p>每个级别关联一个 token 预算，用于控制模型推理深度。
 * 线路上传输字符串（如 "minimal"、 "high"），通过 {@link #fromWireFormat(String)} 解析。</p>
 */
public enum ThinkingLevel {

    OFF("off", 0),
    MINIMAL("minimal", 512),
    LOW("low", 1024),
    MEDIUM("medium", 2048),
    HIGH("high", 4096),
    XHIGH("xhigh", 8192),
    MAX("max", 16384),
    ADAPTIVE("adaptive", 16384);

    private final String wireFormat;
    private final int budget;

    ThinkingLevel(String wireFormat, int budget) {
        this.wireFormat = wireFormat;
        this.budget = budget;
    }

    public String wireFormat() { return wireFormat; }
    public int budget() { return budget; }

    /**
     * 从线路上传输的字符串解析对应的级别。
     * @return 匹配的级别，未匹配时返回 {@link #MEDIUM}
     */
    public static ThinkingLevel fromWireFormat(String s) {
        if (s == null || s.isEmpty()) return MEDIUM;
        for (ThinkingLevel level : values()) {
            if (level.wireFormat.equalsIgnoreCase(s)) return level;
        }
        return MEDIUM;
    }
}
