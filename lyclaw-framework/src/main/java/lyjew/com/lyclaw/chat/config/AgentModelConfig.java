package lyjew.com.lyclaw.chat.config;

/**
 * Per-agent or global default configuration for model selection by modality.
 *
 * <p>Replaces the single "model" concept with modality-specific models,
 * allowing an agent (or the system globally) to specify which model to use
 * for chat, image understanding, image generation, video generation, music
 * generation, and PDF processing.
 *
 * <p>All model fields are nullable. A {@code null} value means "inherit from
 * the parent or global default." The {@link #resolveChatModel(String)} method
 * provides the inheritance chain for the primary chat model; other modalities
 * follow the same pattern at the resolution layer ({@link ModelResolutionService}).
 *
 * <p>PDF-specific limits ({@code pdfMaxBytesMb}, {@code pdfMaxPages}) provide
 * guardrails that can be tightened per-agent relative to the system defaults.
 *
 * <h3>Inheritance chain</h3>
 * <pre>
 *   AgentConfig.chatModel &gt; global default &gt; first-available
 *   AgentConfig.imageModel &gt; AgentConfig.chatModel &gt; global default
 *   AgentConfig.imageGenerationModel &gt; global default
 * </pre>
 *
 * @see ModelResolutionService
 * @see ModelRef
 */
public class AgentModelConfig {

    /** Primary chat / text model. {@code null} means inherit from global default. */
    private String chatModel;

    /** Vision / image understanding model. {@code null} means fall back to chat model. */
    private String imageModel;

    /** Image generation model (e.g. DALL-E). {@code null} means inherit from global default. */
    private String imageGenerationModel;

    /** Video generation model (e.g. Sora). {@code null} means inherit from global default. */
    private String videoGenerationModel;

    /** Music generation model. {@code null} means inherit from global default. */
    private String musicGenerationModel;

    /** PDF reading / understanding model. {@code null} means fall back to chat model. */
    private String pdfModel;

    /** Maximum PDF file size in megabytes. Default 10. */
    private int pdfMaxBytesMb = 10;

    /** Maximum PDF pages to process. Default 20. */
    private int pdfMaxPages = 20;

    /**
     * Whether to automatically fall back to alternative providers for media
     * generation when the primary provider is unavailable. Default true.
     */
    private boolean mediaGenerationAutoProviderFallback = true;

    // ── Getters ────────────────────────────────────────────────────────────

    public String getChatModel() {
        return chatModel;
    }

    public String getImageModel() {
        return imageModel;
    }

    public String getImageGenerationModel() {
        return imageGenerationModel;
    }

    public String getVideoGenerationModel() {
        return videoGenerationModel;
    }

    public String getMusicGenerationModel() {
        return musicGenerationModel;
    }

    public String getPdfModel() {
        return pdfModel;
    }

    public int getPdfMaxBytesMb() {
        return pdfMaxBytesMb;
    }

    public int getPdfMaxPages() {
        return pdfMaxPages;
    }

    public boolean isMediaGenerationAutoProviderFallback() {
        return mediaGenerationAutoProviderFallback;
    }

    // ── Setters ────────────────────────────────────────────────────────────

    public void setChatModel(String chatModel) {
        this.chatModel = chatModel;
    }

    public void setImageModel(String imageModel) {
        this.imageModel = imageModel;
    }

    public void setImageGenerationModel(String imageGenerationModel) {
        this.imageGenerationModel = imageGenerationModel;
    }

    public void setVideoGenerationModel(String videoGenerationModel) {
        this.videoGenerationModel = videoGenerationModel;
    }

    public void setMusicGenerationModel(String musicGenerationModel) {
        this.musicGenerationModel = musicGenerationModel;
    }

    public void setPdfModel(String pdfModel) {
        this.pdfModel = pdfModel;
    }

    public void setPdfMaxBytesMb(int pdfMaxBytesMb) {
        this.pdfMaxBytesMb = pdfMaxBytesMb;
    }

    public void setPdfMaxPages(int pdfMaxPages) {
        this.pdfMaxPages = pdfMaxPages;
    }

    public void setMediaGenerationAutoProviderFallback(boolean mediaGenerationAutoProviderFallback) {
        this.mediaGenerationAutoProviderFallback = mediaGenerationAutoProviderFallback;
    }

    // ── Resolution helpers ─────────────────────────────────────────────────

    /**
     * Resolves the effective chat model for this config using the standard
     * override chain: agent config &gt; global default.
     *
     * <p>If {@code chatModel} is explicitly set (non-null, non-blank) on this
     * config, it is returned as-is. Otherwise the provided {@code globalDefault}
     * is returned, which may be null if no global default is configured.
     *
     * @param globalDefault the global default canonical model id
     *                      (e.g. {@code "deepseek/deepseek-v4-flash"}),
     *                      or {@code null} if no global default is configured
     * @return the resolved canonical model id, or {@code null} if nothing is
     *         configured at any level
     */
    public String resolveChatModel(String globalDefault) {
        if (chatModel != null && !chatModel.isBlank()) {
            return chatModel.trim();
        }
        return globalDefault;
    }
}
