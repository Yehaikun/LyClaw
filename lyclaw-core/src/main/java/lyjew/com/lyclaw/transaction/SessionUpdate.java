package lyjew.com.lyclaw.transaction;

import java.time.Instant;

public class SessionUpdate {

    private final String sessionId;
    private final String updateType;
    private final String oldValue;
    private final String newValue;
    private final String operator;
    private final Instant timestamp;

    public SessionUpdate(String sessionId, String updateType,
                         String oldValue, String newValue,
                         String operator, Instant timestamp) {
        this.sessionId = sessionId;
        this.updateType = updateType;
        this.oldValue = oldValue;
        this.newValue = newValue;
        this.operator = operator;
        this.timestamp = timestamp;
    }

    public String getSessionId() { return sessionId; }
    public String getUpdateType() { return updateType; }
    public String getOldValue() { return oldValue; }
    public String getNewValue() { return newValue; }
    public String getOperator() { return operator; }
    public Instant getTimestamp() { return timestamp; }
}
