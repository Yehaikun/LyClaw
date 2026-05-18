package lyjew.com.lyclaw.config;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Agent 配置聚合对象，从多个配置源（注解 / yml / Builder / DB）合并而来。
 *
 * <p>字段对应 {@link lyjew.com.lyclaw.annotation.Agent} 的核心属性加上
 * {@code extensions} 的 key-value 对，提供统一的 getter 读取。
 */
public class AgentConfig {

    private String name;
    private String description;
    private String version = "1.0.0";
    private String model;
    private String provider;
    private final Map<String, String> extensions = new HashMap<>();

    public AgentConfig() {}

    public AgentConfig(String name) {
        this.name = name;
    }

    // ── 核心属性 getter/setter ──

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getVersion() { return version; }
    public void setVersion(String version) { this.version = version; }

    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }

    public String getProvider() { return provider; }
    public void setProvider(String provider) { this.provider = provider; }

    // ── 扩展属性 ──

    public Map<String, String> getExtensions() { return Collections.unmodifiableMap(extensions); }

    public void addExtension(String key, String value) {
        this.extensions.put(key, value);
    }

    public void addExtensions(Map<String, String> map) {
        this.extensions.putAll(map);
    }

    /** 读取扩展配置值，未配置时返回默认值 */
    public String getExtension(String key, String defaultValue) {
        return extensions.getOrDefault(key, defaultValue);
    }

    /** 读取扩展配置的 boolean 值 */
    public boolean getExtensionBool(String key, boolean defaultValue) {
        String v = extensions.get(key);
        return v != null ? Boolean.parseBoolean(v) : defaultValue;
    }

    /** 读取扩展配置的 int 值 */
    public int getExtensionInt(String key, int defaultValue) {
        String v = extensions.get(key);
        if (v != null) {
            try { return Integer.parseInt(v); } catch (NumberFormatException ignored) {}
        }
        return defaultValue;
    }

    @Override
    public String toString() {
        return "AgentConfig{name=" + name + ", model=" + model + ", provider=" + provider
                + ", extensions=" + extensions + "}";
    }
}
