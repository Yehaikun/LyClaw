package lyjew.com.lyclaw.persistence.session;

import lyjew.com.lyclaw.model.Session;
import lyjew.com.lyclaw.persistence.PersistenceDecision;

public interface SessionPersistence {

    PersistenceDecision evaluate(Session session, int turnCount, long millisSinceLastWrite);
    PersistenceDecision evaluateOnClose(Session session);
}
