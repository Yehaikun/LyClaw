package lyjew.com.lyclaw.chat;

/**
 * 模型能力声明，对应 {@code @ModelCapability} 注解的数据载体。
 *
 * <p>框架根据此能力声明自动决定：
 * streaming=false 时自动降级为同步调用，
 * toolCalling=false 时跳过 Function Calling 场景，等等。
 */
public class ModelCapabilities {

    private boolean streaming = true;
    private boolean toolCalling;
    private boolean toolCallStreaming;
    private boolean thinking;
    private boolean vision;
    private boolean promptCaching;
    private int maxInputTokens = 8192;
    private int maxOutputTokens = 4096;

    public static Builder builder() { return new Builder(); }

    public boolean isStreaming() { return streaming; }
    public void setStreaming(boolean streaming) { this.streaming = streaming; }
    public boolean isToolCalling() { return toolCalling; }
    public void setToolCalling(boolean toolCalling) { this.toolCalling = toolCalling; }
    public boolean isToolCallStreaming() { return toolCallStreaming; }
    public void setToolCallStreaming(boolean toolCallStreaming) { this.toolCallStreaming = toolCallStreaming; }
    public boolean isThinking() { return thinking; }
    public void setThinking(boolean thinking) { this.thinking = thinking; }
    public boolean isVision() { return vision; }
    public void setVision(boolean vision) { this.vision = vision; }
    public boolean isPromptCaching() { return promptCaching; }
    public void setPromptCaching(boolean promptCaching) { this.promptCaching = promptCaching; }
    public int getMaxInputTokens() { return maxInputTokens; }
    public void setMaxInputTokens(int maxInputTokens) { this.maxInputTokens = maxInputTokens; }
    public int getMaxOutputTokens() { return maxOutputTokens; }
    public void setMaxOutputTokens(int maxOutputTokens) { this.maxOutputTokens = maxOutputTokens; }

    public static class Builder {
        private final ModelCapabilities caps = new ModelCapabilities();
        public Builder streaming(boolean v) { caps.streaming = v; return this; }
        public Builder toolCalling(boolean v) { caps.toolCalling = v; return this; }
        public Builder toolCallStreaming(boolean v) { caps.toolCallStreaming = v; return this; }
        public Builder thinking(boolean v) { caps.thinking = v; return this; }
        public Builder vision(boolean v) { caps.vision = v; return this; }
        public Builder promptCaching(boolean v) { caps.promptCaching = v; return this; }
        public Builder maxInputTokens(int v) { caps.maxInputTokens = v; return this; }
        public Builder maxOutputTokens(int v) { caps.maxOutputTokens = v; return this; }
        public ModelCapabilities build() { return caps; }
    }

    public static ModelCapabilities openAiDefaults() {
        return builder().streaming(true).toolCalling(true)
                .maxInputTokens(128000).maxOutputTokens(8192).build();
    }
}
