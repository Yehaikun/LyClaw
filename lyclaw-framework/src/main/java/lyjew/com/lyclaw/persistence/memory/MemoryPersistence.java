package lyjew.com.lyclaw.persistence.memory;

import lyjew.com.lyclaw.persistence.PersistenceDecision;

public interface MemoryPersistence {

    PersistenceDecision evaluate(MemoryWriteState writeState);
}
