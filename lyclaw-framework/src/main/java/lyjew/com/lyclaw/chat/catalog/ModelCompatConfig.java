package lyjew.com.lyclaw.chat.catalog;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 提供商特定的兼容性配置，用于描述不同 LLM 提供商 API 的差异。
 *
 * <p>每个提供商的 API 在字段命名、SSE 格式、Tool Calling 行为
 * 等方面存在细微差别，此类将差异集中管理，由
 * {@link ModelCatalogEntry} 引用。
 */
public class ModelCompatConfig {

    /** 请求体中模型名称字段的 key，默认 "model" */
    private String modelFieldName = "model";

    /** SSE 是否使用 "data: " 前缀，默认 true */
    private boolean sseDataPrefix = true;

    /** SSE 是否使用 "\n\n" 作为分隔符，默认 true */
    private boolean sseDoubleNewline = true;

    /** 是否支持 Tool Calling 的流式输出 */
    private boolean supportsToolCallStreaming = false;

    /** 推理/思考内容在响应中的字段名，默认 "reasoning_content" */
    private String thinkingField = "reasoning_content";

    /** 推理内容是否内联在 content 中（而非独立字段），默认 false */
    private boolean thinkingInline = false;

    /** 额外的 HTTP 请求头 */
    private Map<String, String> headers = new HashMap<>();

    /** 额外的查询参数 */
    private Map<String, String> queryParams = new HashMap<>();

    /** 系统提示词是否作为顶层字段而非 messages[0].role=system，默认 false */
    private boolean systemMessageAsField = false;

    /** 图片上传最大字节数，默认 20MB */
    private long maxImageBytes = 20 * 1024 * 1024;

    /** 是否自动缩放图片，默认 true */
    private boolean autoResizeImages = true;

    /** 图片最大宽度（像素），默认 2048 */
    private int maxImageWidth = 2048;

    /** 图片最大高度（像素），默认 2048 */
    private int maxImageHeight = 2048;

    // ---- getters / setters ----

    public String getModelFieldName() {
        return modelFieldName;
    }

    public void setModelFieldName(String modelFieldName) {
        this.modelFieldName = modelFieldName;
    }

    public boolean isSseDataPrefix() {
        return sseDataPrefix;
    }

    public void setSseDataPrefix(boolean sseDataPrefix) {
        this.sseDataPrefix = sseDataPrefix;
    }

    public boolean isSseDoubleNewline() {
        return sseDoubleNewline;
    }

    public void setSseDoubleNewline(boolean sseDoubleNewline) {
        this.sseDoubleNewline = sseDoubleNewline;
    }

    public boolean isSupportsToolCallStreaming() {
        return supportsToolCallStreaming;
    }

    public void setSupportsToolCallStreaming(boolean supportsToolCallStreaming) {
        this.supportsToolCallStreaming = supportsToolCallStreaming;
    }

    public String getThinkingField() {
        return thinkingField;
    }

    public void setThinkingField(String thinkingField) {
        this.thinkingField = thinkingField;
    }

    public boolean isThinkingInline() {
        return thinkingInline;
    }

    public void setThinkingInline(boolean thinkingInline) {
        this.thinkingInline = thinkingInline;
    }

    public Map<String, String> getHeaders() {
        return headers;
    }

    public void setHeaders(Map<String, String> headers) {
        this.headers = Objects.requireNonNull(headers, "headers must not be null");
    }

    public Map<String, String> getQueryParams() {
        return queryParams;
    }

    public void setQueryParams(Map<String, String> queryParams) {
        this.queryParams = Objects.requireNonNull(queryParams, "queryParams must not be null");
    }

    public boolean isSystemMessageAsField() {
        return systemMessageAsField;
    }

    public void setSystemMessageAsField(boolean systemMessageAsField) {
        this.systemMessageAsField = systemMessageAsField;
    }

    public long getMaxImageBytes() {
        return maxImageBytes;
    }

    public void setMaxImageBytes(long maxImageBytes) {
        this.maxImageBytes = maxImageBytes;
    }

    public boolean isAutoResizeImages() {
        return autoResizeImages;
    }

    public void setAutoResizeImages(boolean autoResizeImages) {
        this.autoResizeImages = autoResizeImages;
    }

    public int getMaxImageWidth() {
        return maxImageWidth;
    }

    public void setMaxImageWidth(int maxImageWidth) {
        this.maxImageWidth = maxImageWidth;
    }

    public int getMaxImageHeight() {
        return maxImageHeight;
    }

    public void setMaxImageHeight(int maxImageHeight) {
        this.maxImageHeight = maxImageHeight;
    }

    // ---- headers / queryParams 便捷方法 ----

    /**
     * 添加一个额外的 HTTP 请求头。
     *
     * @param key   头名称
     * @param value 头值
     * @return this（链式调用）
     */
    public ModelCompatConfig addHeader(String key, String value) {
        this.headers.put(key, value);
        return this;
    }

    /**
     * 添加一个额外的查询参数。
     *
     * @param key   参数名
     * @param value 参数值
     * @return this（链式调用）
     */
    public ModelCompatConfig addQueryParam(String key, String value) {
        this.queryParams.put(key, value);
        return this;
    }

    // ---- 工厂方法 ----

    /**
     * 返回 OpenAI 兼容 API 的默认配置。
     *
     * <p>OpenAI 使用 SSE "data: " 前缀 + "\n\n" 分隔，
     * 推理内容字段名为 "reasoning_content"，系统消息作为 messages[0] 发送。
     */
    public static ModelCompatConfig openAiDefaults() {
        ModelCompatConfig config = new ModelCompatConfig();
        config.modelFieldName = "model";
        config.sseDataPrefix = true;
        config.sseDoubleNewline = true;
        config.thinkingField = "reasoning_content";
        config.thinkingInline = false;
        config.systemMessageAsField = false;
        return config;
    }

    /**
     * 返回 Anthropic 兼容 API 的默认配置。
     *
     * <p>Anthropic 使用不同的字段约定：模型字段名为 "model"，
     * SSE 格式与 OpenAI 类似，但推理内容字段名为 "thinking"，
     * 系统消息作为顶层字段发送，不放在 messages 数组中。
     */
    public static ModelCompatConfig anthropicDefaults() {
        ModelCompatConfig config = new ModelCompatConfig();
        config.modelFieldName = "model";
        config.sseDataPrefix = true;
        config.sseDoubleNewline = true;
        config.thinkingField = "thinking";
        config.thinkingInline = true;
        config.systemMessageAsField = true;
        return config;
    }

    @Override
    public String toString() {
        return "ModelCompatConfig{" +
                "modelFieldName='" + modelFieldName + '\'' +
                ", sseDataPrefix=" + sseDataPrefix +
                ", sseDoubleNewline=" + sseDoubleNewline +
                ", supportsToolCallStreaming=" + supportsToolCallStreaming +
                ", thinkingField='" + thinkingField + '\'' +
                ", thinkingInline=" + thinkingInline +
                ", systemMessageAsField=" + systemMessageAsField +
                ", maxImageBytes=" + maxImageBytes +
                '}';
    }
}
