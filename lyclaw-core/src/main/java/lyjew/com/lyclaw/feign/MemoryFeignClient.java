package lyjew.com.lyclaw.feign;

import lyjew.com.lyclaw.memory.MemoryQuery;
import lyjew.com.lyclaw.memory.MemoryQueryResult;
import lyjew.com.lyclaw.memory.MemoryStats;
import lyjew.com.lyclaw.memory.PerceptionData;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@FeignClient(name = "lyclaw-memory-service", path = "/api/memory")
public interface MemoryFeignClient {

    @PostMapping("/retrieve")
    MemoryQueryResult retrieve(@RequestBody MemoryQuery query);

    @PostMapping("/ingest")
    Map<String, Object> ingest(@RequestBody PerceptionData data);

    @PostMapping("/consolidate")
    Map<String, Object> consolidate(@RequestParam String userId, @RequestParam String sessionId);

    @GetMapping("/stats")
    MemoryStats getStats();
}
