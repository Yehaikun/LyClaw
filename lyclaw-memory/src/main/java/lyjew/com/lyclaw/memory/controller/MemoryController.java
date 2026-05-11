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

/**
 * 记忆系统 REST API 控制器，提供记忆的检索、摄入、合并和统计接口。
 *
 * <p>端点列表：
 * <ul>
 *   <li><b>POST /api/memory/retrieve</b> — 检索记忆</li>
 *   <li><b>POST /api/memory/ingest</b> — 摄入新的感知数据</li>
 *   <li><b>POST /api/memory/consolidate</b> — 触发记忆合并</li>
 *   <li><b>GET /api/memory/stats</b> — 获取记忆统计信息</li>
 * </ul>
 * </p>
 */
@Slf4j
@RestController
@RequestMapping("/api/memory")
public class MemoryController {

    private final MemorySystem memorySystem;

    public MemoryController(MemorySystem memorySystem) {
        this.memorySystem = memorySystem;
    }

    /**
     * 检索记忆。
     *
     * @param query 记忆查询对象
     * @return 包含排序结果和统计信息的查询结果
     */
    @PostMapping("/retrieve")
    public MemoryQueryResult retrieve(@RequestBody MemoryQuery query) {
        log.debug("Memory retrieve: topK={}, layers={}", query.getTopK(), query.getLayerFilter());
        return memorySystem.retrieve(query);
    }

    /**
     * 摄入新的感知数据到记忆系统。
     *
     * @param data      感知数据
     * @param sessionId 会话ID
     * @param userId    用户ID，默认为 "default"
     * @return 包含 entryId、layer、status 的响应映射
     */
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

    /**
     * 触发记忆合并操作，将短期记忆提升为长期记忆。
     *
     * @param userId    用户ID
     * @param sessionId 会话ID
     * @return 包含合并状态的响应映射
     */
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

    /** @return 记忆系统的统计信息（各层条目数、token 数、平均重要性等） */
    @GetMapping("/stats")
    public MemoryStats stats() {
        log.debug("Memory stats requested");
        return memorySystem.getStats();
    }
}
