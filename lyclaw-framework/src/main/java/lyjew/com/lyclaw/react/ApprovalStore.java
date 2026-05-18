package lyjew.com.lyclaw.react;

import lyjew.com.lyclaw.config.AgentProperties;
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
 */
@Component
public class ApprovalStore {

    private final long approvalTimeoutSeconds;

    private final ConcurrentHashMap<String, CompletableFuture<Boolean>> pending =
            new ConcurrentHashMap<>();

    public ApprovalStore(AgentProperties agentProperties) {
        this.approvalTimeoutSeconds = agentProperties.getApprovalStoreTimeoutSeconds();
    }

    /**
     * 创建一个待审批请求。
     *
     * @return 当用户响应时完成的 Future（true=允许 false=拒绝/超时）
     */
    public CompletableFuture<Boolean> create(String toolCallId) {
        CompletableFuture<Boolean> future = new CompletableFuture<>();
        pending.put(toolCallId, future);

        // 超时自动拒绝
        CompletableFuture.delayedExecutor(approvalTimeoutSeconds, TimeUnit.SECONDS)
                .execute(() -> {
                    CompletableFuture<Boolean> f = pending.remove(toolCallId);
                    if (f != null) {
                        f.complete(false);
                    }
                });

        return future;
    }

    /** 用户允许工具执行 */
    public boolean approve(String toolCallId) {
        CompletableFuture<Boolean> future = pending.remove(toolCallId);
        if (future != null && !future.isDone()) {
            return future.complete(true);
        }
        return false;
    }

    /** 用户拒绝工具执行 */
    public boolean deny(String toolCallId) {
        CompletableFuture<Boolean> future = pending.remove(toolCallId);
        if (future != null && !future.isDone()) {
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
            }
        });
    }
}
