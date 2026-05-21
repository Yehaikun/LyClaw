package lyjew.com.lyclaw.web.controller;

import java.util.List;
import java.util.Map;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lyjew.com.lyclaw.config.StorageProperties;
import lyjew.com.lyclaw.persistence.repository.SessionRepository;
import lyjew.com.lyclaw.web.session.SessionManager;
import org.springframework.web.bind.annotation.*;

/**
 * 会话管理REST接口——查询会话列表、消息历史、子会话、删除会话。
 *
 * 所有读写委托给SessionRepository（SQLite+JSONL）和SessionManager（缓存+生命周期）。
 * 消息历史分页由{@link StorageProperties.SessionProperties#pageSize}控制默认页大小。
 */
@Tag(name = "Session", description = "会话查询和历史管理接口")
@RestController
@RequestMapping("/api/agents/{agentId}/sessions")
public class SessionController {

    private final SessionRepository sessionRepository;
    private final SessionManager sessionManager;
    private final StorageProperties storageProperties;

    public SessionController(SessionRepository sessionRepository,
                             SessionManager sessionManager,
                             StorageProperties storageProperties) {
        this.sessionRepository = sessionRepository;
        this.sessionManager = sessionManager;
        this.storageProperties = storageProperties;
    }

    @Operation(summary = "列出会话", description = "返回指定Agent的所有会话，按更新时间降序排列")
    @GetMapping
    public List<Map<String, Object>> listSessions(@PathVariable String agentId) {
        return sessionRepository.findByAgentId(agentId);
    }

    @Operation(summary = "获取消息历史", description = "分页获取会话的消息记录。offset=-1表示取最新limit条")
    @GetMapping("/{sessionId}/messages")
    public List<Map<String, Object>> getMessages(
            @PathVariable String agentId,
            @PathVariable String sessionId,
            @Parameter(description = "起始偏移量，-1表示取最新") @RequestParam(defaultValue = "-1") int offset,
            @Parameter(description = "返回条数") @RequestParam(defaultValue = "0") int limit) {
        var session = sessionManager.getSession(sessionId);
        if (session == null) throw new RuntimeException("会话不存在: " + sessionId);
        int pageSize = limit > 0 ? limit : storageProperties.getSession().getPageSize();
        return sessionRepository.readMessages(session.getFilePath(), offset, pageSize);
    }

    @Operation(summary = "获取子会话", description = "查询指定父会话的所有子会话，按创建时间升序排列")
    @GetMapping("/{sessionId}/children")
    public List<Map<String, Object>> getChildSessions(
            @PathVariable String agentId,
            @PathVariable String sessionId) {
        return sessionRepository.findByParentSessionId(sessionId);
    }

    @Operation(summary = "删除会话", description = "删除指定会话的JSONL文件和SQLite记录，同时清理缓存")
    @DeleteMapping("/{sessionId}")
    public Map<String, Object> deleteSession(
            @PathVariable String agentId,
            @PathVariable String sessionId) {
        sessionManager.deleteSession(sessionId);
        return Map.of("sessionId", sessionId, "deleted", true);
    }
}
