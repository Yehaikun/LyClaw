package lyjew.com.lyclaw.chat.catalog;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * 模型目录中的一个结构化条目，表示某个提供商提供的一个可用模型。
 *
 * <p>每个条目封装了模型的标识、能力、定价和兼容性信息。
 * 条目通过 {@link Builder} 构建，除 {@link #setAvailable(boolean)}
 * 外均为只读。
 */
public class ModelCatalogEntry {

    /** 规范 ID，格式为 "provider/name"，例如 "openai/gpt-4o" */
    private final String id;

    /** 模型名称，例如 "gpt-4o" */
    private final String name;

    /** 提供商，例如 "openai" */
    private final String provider;

    /** 可选的短别名，例如 "gpt4" */
    private final String alias;

    /** 人类可读的显示名称 */
    private final String displayName;

    /** 模型描述 */
    private final String description;

    /** 最大上下文窗口（tokens） */
    private final int contextWindow;

    /** 上下文 token 覆盖值，用于提供商预留部分上下文的场景 */
    private final int contextTokens;

    /** 是否支持扩展推理/思考 */
    private final boolean reasoning;

    /** 最大输出 token 数 */
    private final int maxOutputTokens;

    /** 接受的输入模态类型列表 */
    private final List<ModelInputType> input;

    /** 每百万输入 token 价格（美元） */
    private final double pricePerMillionInput;

    /** 每百万输出 token 价格（美元） */
    private final double pricePerMillionOutput;

    /** 提供商特定的兼容性配置 */
    private final ModelCompatConfig compat;

    /** 当前是否可用（通过健康检查） */
    private volatile boolean available;

    /** 是否为 beta/预览模型 */
    private final boolean beta;

    /** 弃用时间（epoch 毫秒），0 表示未弃用 */
    private final long deprecatedAt;

    private ModelCatalogEntry(Builder builder) {
        if (builder.provider == null || builder.provider.isBlank()) {
            throw new IllegalArgumentException("provider must not be blank");
        }
        if (builder.name == null || builder.name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }

        this.provider = builder.provider;
        this.name = builder.name;
        this.id = canonicalId(builder.provider, builder.name);
        this.alias = builder.alias;
        this.displayName = builder.displayName != null ? builder.displayName : builder.name;
        this.description = builder.description != null ? builder.description : "";
        this.contextWindow = builder.contextWindow;
        this.contextTokens = builder.contextTokens > 0 ? builder.contextTokens : builder.contextWindow;
        this.reasoning = builder.reasoning;
        this.maxOutputTokens = builder.maxOutputTokens;
        this.input = Collections.unmodifiableList(
                new ArrayList<>(builder.input != null ? builder.input : List.of()));
        this.pricePerMillionInput = builder.pricePerMillionInput;
        this.pricePerMillionOutput = builder.pricePerMillionOutput;
        this.compat = builder.compat != null ? builder.compat : new ModelCompatConfig();
        this.available = builder.available;
        this.beta = builder.beta;
        this.deprecatedAt = builder.deprecatedAt;
    }

    /**
     * 根据提供商和模型名称构造规范 ID。
     *
     * @param provider 提供商
     * @param name     模型名称
     * @return "provider/name" 格式的规范 ID
     */
    public static String canonicalId(String provider, String name) {
        return provider + "/" + name;
    }

    /**
     * 创建新的 {@link Builder} 实例。
     *
     * @return 新的 Builder
     */
    public static Builder builder() {
        return new Builder();
    }

    // ---- getters ----

    public String getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getProvider() {
        return provider;
    }

    public String getAlias() {
        return alias;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getDescription() {
        return description;
    }

    public int getContextWindow() {
        return contextWindow;
    }

    public int getContextTokens() {
        return contextTokens;
    }

    public boolean isReasoning() {
        return reasoning;
    }

    public int getMaxOutputTokens() {
        return maxOutputTokens;
    }

    public List<ModelInputType> getInput() {
        return input;
    }

    public double getPricePerMillionInput() {
        return pricePerMillionInput;
    }

    public double getPricePerMillionOutput() {
        return pricePerMillionOutput;
    }

    public ModelCompatConfig getCompat() {
        return compat;
    }

    public boolean isAvailable() {
        return available;
    }

    public boolean isBeta() {
        return beta;
    }

    public long getDeprecatedAt() {
        return deprecatedAt;
    }

    /**
     * 是否为已弃用的模型。
     *
     * @return true 如果 {@code deprecatedAt > 0}
     */
    public boolean isDeprecated() {
        return deprecatedAt > 0;
    }

    // ---- setter (仅 available 可修改) ----

    /**
     * 更新模型的可用性状态（通常由健康检查调用）。
     *
     * @param available 新的可用状态
     */
    public void setAvailable(boolean available) {
        this.available = available;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ModelCatalogEntry)) return false;
        ModelCatalogEntry that = (ModelCatalogEntry) o;
        return id.equals(that.id);
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }

    @Override
    public String toString() {
        return "ModelCatalogEntry{" +
                "id='" + id + '\'' +
                ", displayName='" + displayName + '\'' +
                ", provider='" + provider + '\'' +
                ", available=" + available +
                ", beta=" + beta +
                ", deprecated=" + isDeprecated() +
                '}';
    }

    /**
     * {@link ModelCatalogEntry} 的流式构建器。
     */
    public static class Builder {

        private String name;
        private String provider;
        private String alias;
        private String displayName;
        private String description;
        private int contextWindow;
        private int contextTokens;
        private boolean reasoning;
        private int maxOutputTokens;
        private List<ModelInputType> input;
        private double pricePerMillionInput;
        private double pricePerMillionOutput;
        private ModelCompatConfig compat;
        private boolean available = true;
        private boolean beta;
        private long deprecatedAt;

        public Builder provider(String provider) {
            this.provider = provider;
            return this;
        }

        public Builder name(String name) {
            this.name = name;
            return this;
        }

        public Builder alias(String alias) {
            this.alias = alias;
            return this;
        }

        public Builder displayName(String displayName) {
            this.displayName = displayName;
            return this;
        }

        public Builder description(String description) {
            this.description = description;
            return this;
        }

        public Builder contextWindow(int contextWindow) {
            this.contextWindow = contextWindow;
            return this;
        }

        public Builder contextTokens(int contextTokens) {
            this.contextTokens = contextTokens;
            return this;
        }

        public Builder reasoning(boolean reasoning) {
            this.reasoning = reasoning;
            return this;
        }

        public Builder maxOutputTokens(int maxOutputTokens) {
            this.maxOutputTokens = maxOutputTokens;
            return this;
        }

        public Builder input(List<ModelInputType> input) {
            this.input = input;
            return this;
        }

        public Builder pricePerMillionInput(double pricePerMillionInput) {
            this.pricePerMillionInput = pricePerMillionInput;
            return this;
        }

        public Builder pricePerMillionOutput(double pricePerMillionOutput) {
            this.pricePerMillionOutput = pricePerMillionOutput;
            return this;
        }

        public Builder compat(ModelCompatConfig compat) {
            this.compat = compat;
            return this;
        }

        public Builder available(boolean available) {
            this.available = available;
            return this;
        }

        public Builder beta(boolean beta) {
            this.beta = beta;
            return this;
        }

        public Builder deprecatedAt(long deprecatedAt) {
            this.deprecatedAt = deprecatedAt;
            return this;
        }

        public ModelCatalogEntry build() {
            return new ModelCatalogEntry(this);
        }
    }
}
