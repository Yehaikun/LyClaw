package lyjew.com.lyclaw.memory.controller;

import lyjew.com.lyclaw.memory.MemoryConsolidationPolicy;
import lyjew.com.lyclaw.memory.MemoryQuery;
import lyjew.com.lyclaw.memory.MemoryQueryResult;
import lyjew.com.lyclaw.memory.MemoryStats;
import lyjew.com.lyclaw.memory.MemorySystem;
import lyjew.com.lyclaw.memory.PerceptionData;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/memory")
@RequiredArgsConstructor
public class MemoryController {

    private static final Logger log = LoggerFactory.getLogger(MemoryController.class);

    private final MemorySystem memorySystem;

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
                "status", "ingested"
        );
    }

    @PostMapping("/consolidate")
    public Map<String, Object> consolidate(
            @RequestParam String userId,
            @RequestParam String sessionId) {
        log.info("Memory consolidate: user={}, session={}", userId, sessionId);
        MemoryConsolidationPolicy policy = MemoryConsolidationPolicy.builder().build();
        memorySystem.consolidate(userId, policy);
        memorySystem.evictExpiredPerceptions();
        return Map.of(
                "userId", userId,
                "sessionId", sessionId,
                "status", "consolidated"
        );
    }

    @GetMapping("/stats")
    public MemoryStats stats() {
        log.debug("Memory stats requested");
        return memorySystem.getStats();
    }
}
