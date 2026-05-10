package lyjew.com.lyclaw.infra.event;

import lyjew.com.lyclaw.event.Event;

public class TokenConsumedEvent extends Event {

    private final String provider;
    private final String model;
    private final int promptTokens;
    private final int completionTokens;
    private final long latencyMs;
    private final String sessionId;

    public TokenConsumedEvent(String source, String provider, String model,
                              int promptTokens, int completionTokens, long latencyMs,
                              String sessionId) {
        super(source, "TOKEN_CONSUMED");
        this.provider = provider;
        this.model = model;
        this.promptTokens = promptTokens;
        this.completionTokens = completionTokens;
        this.latencyMs = latencyMs;
        this.sessionId = sessionId;
    }

    public String getProvider() { return provider; }
    public String getModel() { return model; }
    public int getPromptTokens() { return promptTokens; }
    public int getCompletionTokens() { return completionTokens; }
    public long getLatencyMs() { return latencyMs; }
    public String getSessionId() { return sessionId; }
    public int getTotalTokens() { return promptTokens + completionTokens; }
}
