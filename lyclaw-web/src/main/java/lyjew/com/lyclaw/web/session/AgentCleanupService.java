package lyjew.com.lyclaw.web.session;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import lyjew.com.lyclaw.config.StorageProperties;
import lyjew.com.lyclaw.persistence.repository.AgentRepository;
import lyjew.com.lyclaw.persistence.repository.SessionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Agent级联删除服务——删除Agent时递归清理其所有子孙临时Agent、会话记录和文件。
 *
 * 删除顺序（从叶子到根）：
 * 1. 递归查找所有子孙临时Agent（WITH RECURSIVE CTE，深度优先）
 * 2. 对每个子孙Agent：删除JSONL文件 → 删除SQLite sessions行 → 删除SQLite agents行 → rm -rf目录
 * 3. 对本Agent：删除JSONL文件 → 删除SQLite sessions行 → 删除SQLite agents行 → rm -rf目录
 * 4. 断开子孙永久Agent的parent关系（永久Agent不删除，只解除父子关联）
 */
public class AgentCleanupService {

    private static final Logger log = LoggerFactory.getLogger(AgentCleanupService.class);

    private final AgentRepository agentRepository;
    private final SessionRepository sessionRepository;
    private final StorageProperties storageProperties;

    public AgentCleanupService(AgentRepository agentRepository,
                               SessionRepository sessionRepository,
                               StorageProperties storageProperties) {
        this.agentRepository = agentRepository;
        this.sessionRepository = sessionRepository;
        this.storageProperties = storageProperties;
    }

    /**
     * 级联删除Agent及其所有子孙临时Agent。
     * 永久Agent不会被删除，但会断开parent关联。
     */
    public void deleteAgent(String agentId) {
        // 1. 查找所有子孙临时Agent（按深度降序，叶子在前）
        List<String> descendants = agentRepository.findAllTemporaryDescendants(agentId);
        log.info("级联删除Agent {}: 找到{}个子孙临时Agent", agentId, descendants.size());

        // 2. 递归删除子孙（从叶子到根）
        for (String descId : descendants) {
            deleteAgentSessions(descId);
            agentRepository.delete(descId);
            deleteDirectory(descId);
            log.debug("已删除子孙临时Agent: {}", descId);
        }

        // 3. 删除本Agent
        deleteAgentSessions(agentId);
        agentRepository.delete(agentId);
        deleteDirectory(agentId);
        log.info("Agent {} 及其所有关联数据已级联删除", agentId);
    }

    /** 删除指定Agent的所有会话（JSONL文件 + SQLite sessions行） */
    private void deleteAgentSessions(String agentId) {
        List<Map<String, Object>> sessions = sessionRepository.findByAgentId(agentId);
        for (Map<String, Object> s : sessions) {
            String sid = (String) s.get("session_id");
            String filePath = (String) s.get("file_path");
            sessionRepository.delete(sid, filePath);
        }
        log.debug("已删除Agent {} 的{}个会话", agentId, sessions.size());
    }

    /** 删除Agent的整个文件目录（agents/{agentId}/） */
    private void deleteDirectory(String agentId) {
        Path dir = Paths.get(storageProperties.getBasePath(), "agents", agentId);
        if (Files.exists(dir)) {
            try {
                Files.walk(dir)
                        .sorted(Comparator.reverseOrder())
                        .forEach(p -> {
                            try { Files.delete(p); } catch (IOException ignored) {}
                        });
            } catch (IOException e) {
                log.warn("删除Agent目录失败: {}", dir, e);
            }
        }
    }
}
