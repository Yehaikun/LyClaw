package lyjew.com.lyclaw.infra.event;

import lyjew.com.lyclaw.event.Event;

public class ReflectionCompletedEvent extends Event {

    private final String reflectionId;
    private final String sessionId;
    private final double overallScore;
    private final boolean hasErrors;

    public ReflectionCompletedEvent(String source, String reflectionId, String sessionId,
                                    double overallScore, boolean hasErrors) {
        super(source, "REFLECTION_COMPLETED");
        this.reflectionId = reflectionId;
        this.sessionId = sessionId;
        this.overallScore = overallScore;
        this.hasErrors = hasErrors;
    }

    public String getReflectionId() { return reflectionId; }
    public String getSessionId() { return sessionId; }
    public double getOverallScore() { return overallScore; }
    public boolean isHasErrors() { return hasErrors; }
}
