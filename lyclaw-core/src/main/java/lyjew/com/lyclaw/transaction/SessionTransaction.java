package lyjew.com.lyclaw.transaction;

import lyjew.com.lyclaw.context.ChatContext;

import java.util.List;

public interface SessionTransaction {

    void begin(String sessionId, String context);
    boolean commit(String sessionId);
    boolean rollback(String sessionId);
    String getStatus(String sessionId);
    List<SessionUpdate> createSnapshot(String sessionId, ChatContext context);
}
