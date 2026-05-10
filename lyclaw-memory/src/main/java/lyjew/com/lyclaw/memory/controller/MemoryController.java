package lyjew.com.lyclaw.memory.controller;

import lombok.extern.slf4j.Slf4j;
import lyjew.com.lyclaw.memory.MemoryConsolidationPolicy;
import lyjew.com.lyclaw.memory.MemoryQuery;
import lyjew.com.lyclaw.memory.MemoryQueryResult;
import lyjew.com.lyclaw.memory.MemoryStats;
import lyjew.com.lyclaw.memory.MemorySystem;
import lyjew.com.lyclaw.memory.PerceptionData;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/memory")
public class MemoryController {

    private final MemorySystem memorySystem;

    public MemoryController(MemorySystem memorySystem) {
        this.memorySystem = memorySystem;
    }

    @PostMapping("/retrieve")
    public MemoryQueryResult retrieve(@RequestBody MemoryQuery query) {
        log.debug("Memory retrieve: topK={}, layers={}", query.getTopK(), query.getLayerFilter());
        return memorySystem.retrieve(query);
    }

    @PostMapping("/ingest")
    public Map<String, Object> ingest(
            @RequestBody PerceptionData data,
            @RequestParam String sessionId,
            @RequestParam(required = false, defaultValue = "default") String userId) {
        log.debug("Memory ingest: session={}, user={}", sessionId, userId);
        var entry = memorySystem.ingestPerception(sessionId, data);
        entry.setUserId(userId);
        return Map.of(
                "entryId", entry.getEntryId(),
                "layer", entry.getLayer().name(),
                "status", "ingested");
    }

    @PostMapping("/consolidate")
    public Map<String, Object> consolidate(
            @RequestParam String userId,
            @RequestParam String sessionId) {
        log.info("Memory consolidate: user={}, session={}", userId, sessionId);
        MemoryConsolidationPolicy policy = MemoryConsolidationPolicy.builder().build();
        memorySystem.consolidate(userId, policy);
        memorySystem.evictExpiredPerceptions();
        return Map.of("userId", userId, "sessionId", sessionId, "status", "consolidated");
    }

    @GetMapping("/stats")
    public MemoryStats stats() {
        log.debug("Memory stats requested");
        return memorySystem.getStats();
    }
}
