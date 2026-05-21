package lyjew.com.lyclaw.web.controller;

import java.io.File;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lyjew.com.lyclaw.config.StorageProperties;
import lyjew.com.lyclaw.persistence.repository.AgentRepository;
import lyjew.com.lyclaw.web.session.AgentCleanupService;
import org.springframework.web.bind.annotation.*;

/**
 * Agent管理REST接口——创建、查询、更新、删除Agent。
 *
 * 创建流程：生成agentId → 创建目录 → 写入agent.json → INSERT SQLite。
 * 删除流程：级联删除所有子孙临时Agent、会话和文件（委托给AgentCleanupService）。
 */
@Tag(name = "Agent", description = "Agent生命周期管理接口")
@RestController
@RequestMapping("/api/agents")
public class AgentController {

    private final AgentRepository agentRepository;
    private final AgentCleanupService cleanupService;
    private final StorageProperties storageProperties;
    private final ObjectMapper objectMapper;

    public AgentController(AgentRepository agentRepository,
                           AgentCleanupService cleanupService,
                           StorageProperties storageProperties,
                           ObjectMapper objectMapper) {
        this.agentRepository = agentRepository;
        this.cleanupService = cleanupService;
        this.storageProperties = storageProperties;
        this.objectMapper = objectMapper;
    }

    @Operation(summary = "列出所有Agent", description = "返回所有Agent的摘要列表，不含完整配置细节")
    @GetMapping
    public List<Map<String, Object>> listAgents() {
        return agentRepository.findAllSummary();
    }

    @Operation(summary = "获取Agent详情", description = "返回Agent的完整配置信息，含SQLite元数据和agent.json内容")
    @GetMapping("/{agentId}")
    public Map<String, Object> getAgent(@PathVariable String agentId) {
        Map<String, Object> agent = agentRepository.findById(agentId);
        if (agent == null) throw new RuntimeException("Agent不存在: " + agentId);
        // 补充读取agent.json中的额外字段
        File jsonFile = new File(storageProperties.getBasePath() + "/agents/" + agentId + "/agent.json");
        if (jsonFile.exists()) {
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> jsonFields = objectMapper.readValue(jsonFile, Map.class);
                jsonFields.forEach(agent::putIfAbsent);
            } catch (IOException e) {
                // agent.json读取失败不阻断，SQLite元数据已足够
            }
        }
        return agent;
    }

    @Operation(summary = "创建Agent", description = "创建新Agent，自动生成ID、创建目录、写入agent.json并入库SQLite")
    @PostMapping
    public Map<String, Object> createAgent(@RequestBody Map<String, Object> request) {
        String agentId = UUID.randomUUID().toString().substring(0,
                storageProperties.getSession().getIdLength());
        long now = System.currentTimeMillis();
        String dirPath = storageProperties.getBasePath() + "/agents/" + agentId;

        // 1. 创建目录
        new File(dirPath).mkdirs();

        // 2. 构建并写入agent.json
        Map<String, Object> agentJson = new LinkedHashMap<>(request);
        agentJson.put("agent_id", agentId);
        agentJson.put("created_at", now);
        try {
            objectMapper.writeValue(new File(dirPath + "/agent.json"), agentJson);
        } catch (IOException e) {
            throw new RuntimeException("写入agent.json失败: " + dirPath, e);
        }

        // 3. INSERT SQLite
        Map<String, Object> dbRow = new LinkedHashMap<>();
        dbRow.put("agent_id", agentId);
        dbRow.put("agent_name", request.getOrDefault("agentName", request.getOrDefault("agent_name", "Unnamed")));
        dbRow.put("lifecycle", request.getOrDefault("lifecycle", "permanent"));
        dbRow.put("created_by", request.getOrDefault("created_by", "user"));
        dbRow.put("model", request.getOrDefault("model", "deepseek-v4-flash"));
        dbRow.put("provider", request.getOrDefault("provider", "deepseek"));
        dbRow.put("description", request.getOrDefault("description", ""));
        dbRow.put("parent_agent_id", request.get("parent_agent_id"));
        dbRow.put("parent_session_id", request.get("parent_session_id"));
        dbRow.put("directory_path", dirPath);
        dbRow.put("created_at", now);
        agentRepository.insert(dbRow);

        return Map.of("agentId", agentId, "agentName",
                request.getOrDefault("agentName", "Unnamed"), "createdAt", now);
    }

    @Operation(summary = "更新Agent", description = "更新Agent的配置字段，同时更新SQLite和agent.json")
    @PutMapping("/{agentId}")
    public Map<String, Object> updateAgent(@PathVariable String agentId,
                                           @RequestBody Map<String, Object> updates) {
        agentRepository.update(agentId, updates);
        // 同步更新agent.json
        File jsonFile = new File(storageProperties.getBasePath() + "/agents/" + agentId + "/agent.json");
        if (jsonFile.exists()) {
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> existing = objectMapper.readValue(jsonFile, Map.class);
                existing.putAll(updates);
                objectMapper.writeValue(jsonFile, existing);
            } catch (IOException e) {
                throw new RuntimeException("更新agent.json失败: " + agentId, e);
            }
        }
        return Map.of("agentId", agentId, "updated", true);
    }

    @Operation(summary = "删除Agent", description = "级联删除Agent及其所有子孙临时Agent、会话和文件。永久子Agent仅断开父子关联")
    @DeleteMapping("/{agentId}")
    public Map<String, Object> deleteAgent(@PathVariable String agentId) {
        cleanupService.deleteAgent(agentId);
        return Map.of("agentId", agentId, "deleted", true);
    }
}
