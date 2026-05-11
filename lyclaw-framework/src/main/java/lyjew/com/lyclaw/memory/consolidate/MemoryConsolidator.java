package lyjew.com.lyclaw.memory.consolidate;

import lyjew.com.lyclaw.memory.ConsolidationReport;
import lyjew.com.lyclaw.memory.MemoryConsolidationPolicy;

public interface MemoryConsolidator {

    ConsolidationReport consolidate(String userId, String sessionId);
    ConsolidationReport consolidate(String userId, String sessionId, MemoryConsolidationPolicy policy);
    boolean supportsLlmDrivenSummary();
}
