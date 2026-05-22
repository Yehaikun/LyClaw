package lyjew.com.lyclaw.react;

import lyjew.com.lyclaw.config.AgentProperties;
import lyjew.com.lyclaw.persistence.repository.ApprovalRepository;
import org.springframework.stereotype.Component;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * 工具审批存储，管理待用户确认的工具调用请求。
 *
 * <p>每个待审批请求对应一个 {@link CompletableFuture}，
 * 用户通过 {@link #approve}/{@link #deny} 响应后完成 Future。
 * 超时时间由 {@link AgentProperties#getApprovalStoreTimeoutSeconds()} 控制，默认 60 秒。
 *
 * <p>审批记录同步持久化到 SQLite approvals 表（通过 {@link ApprovalRepository}）。
 * approvalId 复用 toolCallId（一对一关系，天然唯一）。
 */
@Component
public class ApprovalStore {

    private final long approvalTimeoutSeconds;
    private final ApprovalRepository approvalRepo;

    private final ConcurrentHashMap<String, CompletableFuture<Boolean>> pending =
            new ConcurrentHashMap<>();

    public ApprovalStore(AgentProperties agentProperties, ApprovalRepository approvalRepo) {
        this.approvalTimeoutSeconds = agentProperties.getApprovalStoreTimeoutSeconds();
        this.approvalRepo = approvalRepo;
    }

    /**
     * 创建一个待审批请求，同时持久化到 SQLite。
     *
     * @param toolCallId 工具调用ID（同时作为 approvalId）
     * @param sessionId  所属会话ID
     * @param agentId    所属Agent ID
     * @param toolName   工具名称
     * @param arguments  工具参数 JSON 字符串
     * @return 当用户响应时完成的 Future（true=允许 false=拒绝/超时）
     */
    public CompletableFuture<Boolean> create(String toolCallId, String sessionId,
                                              String agentId, String toolName,
                                              String arguments) {
        CompletableFuture<Boolean> future = new CompletableFuture<>();
        pending.put(toolCallId, future);

        long now = System.currentTimeMillis();
        long expiresAt = now + approvalTimeoutSeconds * 1000;

        // 持久化到 SQLite
        try {
            approvalRepo.insert(toolCallId, sessionId, agentId, toolName,
                    toolCallId, arguments, expiresAt);
        } catch (Exception e) {
            // 持久化失败不阻塞审批流程，仅日志告警
            System.err.println("[ApprovalStore] 持久化审批记录失败: " + e.getMessage());
        }

        // 超时自动拒绝
        CompletableFuture.delayedExecutor(approvalTimeoutSeconds, TimeUnit.SECONDS)
                .execute(() -> {
                    CompletableFuture<Boolean> f = pending.remove(toolCallId);
                    if (f != null) {
                        f.complete(false);
                    }
                    // 更新 SQLite 状态为 expired
                    try {
                        approvalRepo.resolve(toolCallId, "expired", "timeout");
                    } catch (Exception ignored) { /* 清理失败不影响 */ }
                });

        return future;
    }

    /** 用户允许工具执行 */
    public boolean approve(String toolCallId) {
        CompletableFuture<Boolean> future = pending.remove(toolCallId);
        if (future != null && !future.isDone()) {
            approvalRepo.resolve(toolCallId, "approved", "user");
            return future.complete(true);
        }
        return false;
    }

    /** 用户拒绝工具执行 */
    public boolean deny(String toolCallId) {
        CompletableFuture<Boolean> future = pending.remove(toolCallId);
        if (future != null && !future.isDone()) {
            approvalRepo.resolve(toolCallId, "denied", "user");
            return future.complete(false);
        }
        return false;
    }

    /** @return 当前待审批数量 */
    public int pendingCount() {
        return pending.size();
    }

    /** 拒绝所有待审批请求（会话结束时调用） */
    public void denyAll() {
        pending.forEach((id, future) -> {
            pending.remove(id);
            if (!future.isDone()) {
                future.complete(false);
                try {
                    approvalRepo.resolve(id, "denied", "system");
                } catch (Exception ignored) { /* 清理失败不影响 */ }
            }
        });
    }
}
