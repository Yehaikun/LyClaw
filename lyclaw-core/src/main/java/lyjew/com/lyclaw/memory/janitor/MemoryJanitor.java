package lyjew.com.lyclaw.memory.janitor;

import lyjew.com.lyclaw.memory.JanitorReport;

public interface MemoryJanitor {

    double DEFAULT_DUP_THRESHOLD = 0.85;

    JanitorReport clean(String userId);
}
