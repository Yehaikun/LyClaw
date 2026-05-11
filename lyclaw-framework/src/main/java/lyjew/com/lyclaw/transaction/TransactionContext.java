package lyjew.com.lyclaw.transaction;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

public class TransactionContext {

    public static final String STATUS_ACTIVE = "ACTIVE";
    public static final String STATUS_COMMITTED = "COMMITTED";
    public static final String STATUS_ROLLED_BACK = "ROLLED_BACK";

    private final String sessionId;
    private final String contextSnapshot;
    private final List<SessionUpdate> updates;
    private String status;
    private final Instant createdAt;
    private final String transactionId;

    public TransactionContext(String transactionId, String sessionId, String contextSnapshot) {
        this.transactionId = transactionId;
        this.sessionId = sessionId;
        this.contextSnapshot = contextSnapshot;
        this.updates = new ArrayList<>();
        this.status = STATUS_ACTIVE;
        this.createdAt = Instant.now();
    }

    public void addUpdate(SessionUpdate update) {
        if (STATUS_ACTIVE.equals(this.status)) {
            this.updates.add(update);
        }
    }

    public String getTransactionId() { return transactionId; }
    public String getSessionId() { return sessionId; }
    public String getContextSnapshot() { return contextSnapshot; }
    public List<SessionUpdate> getUpdates() { return updates; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Instant getCreatedAt() { return createdAt; }
}
