package lyjew.com.lyclaw.reflect.registry;

import lyjew.com.lyclaw.reflect.topology.PrimitiveType;

import java.util.*;

/**
 * 原语描述信息——供 PrimitiveCatalog 和 AI 拓扑生成使用。
 */
public class PrimitiveDescriptor {
    private PrimitiveType primitiveType;
    private String implementationName;
    private String displayName;
    private String description;
    private Map<String, ConfigParam> configSchema = new LinkedHashMap<>();
    private List<String> applicableScenarios = new ArrayList<>();
    private boolean requiresLLM;
    private long typicalLatencyMs;

    public PrimitiveDescriptor() {}

    public PrimitiveType getPrimitiveType() { return primitiveType; }
    public void setPrimitiveType(PrimitiveType v) { this.primitiveType = v; }
    public String getImplementationName() { return implementationName; }
    public void setImplementationName(String v) { this.implementationName = v; }
    public String getDisplayName() { return displayName; }
    public void setDisplayName(String v) { this.displayName = v; }
    public String getDescription() { return description; }
    public void setDescription(String v) { this.description = v; }
    public Map<String, ConfigParam> getConfigSchema() { return configSchema; }
    public void setConfigSchema(Map<String, ConfigParam> v) { this.configSchema = v; }
    public List<String> getApplicableScenarios() { return applicableScenarios; }
    public void setApplicableScenarios(List<String> v) { this.applicableScenarios = v; }
    public boolean isRequiresLLM() { return requiresLLM; }
    public void setRequiresLLM(boolean v) { this.requiresLLM = v; }
    public long getTypicalLatencyMs() { return typicalLatencyMs; }
    public void setTypicalLatencyMs(long v) { this.typicalLatencyMs = v; }

    public static class ConfigParam {
        private String type;
        private String defaultValue;
        private String description;
        private boolean required;
        private List<String> allowedValues;

        public String getType() { return type; }
        public void setType(String v) { this.type = v; }
        public String getDefaultValue() { return defaultValue; }
        public void setDefaultValue(String v) { this.defaultValue = v; }
        public String getDescription() { return description; }
        public void setDescription(String v) { this.description = v; }
        public boolean isRequired() { return required; }
        public void setRequired(boolean v) { this.required = v; }
        public List<String> getAllowedValues() { return allowedValues; }
        public void setAllowedValues(List<String> v) { this.allowedValues = v; }
    }
}
