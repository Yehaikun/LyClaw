package lyjew.com.lyclaw.orchestration.controller;

import lyjew.com.lyclaw.react.ApprovalStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.Map;

/**
 * 工具审批控制器，接收前端用户在确认对话框中的选择。
 */
@RestController("orchestrationApprovalController")
public class ApprovalController {

    private static final Logger log = LoggerFactory.getLogger(ApprovalController.class);

    private final ApprovalStore approvalStore;

    public ApprovalController(ApprovalStore approvalStore) {
        this.approvalStore = approvalStore;
    }

    /**
     * 用户对工具审批请求的响应。
     *
     * @param body 包含 toolCallId 和 approved (boolean) 的 JSON
     * @return 处理结果
     */
    @PostMapping("/api/approval/respond")
    public Mono<Map<String, Object>> respond(@RequestBody Map<String, Object> body) {
        String toolCallId = (String) body.get("toolCallId");
        Boolean approved = (Boolean) body.get("approved");

        if (toolCallId == null || approved == null) {
            return Mono.just(Map.of("success", false, "error", "缺少 toolCallId 或 approved"));
        }

        boolean ok;
        if (approved) {
            ok = approvalStore.approve(toolCallId);
            log.info("APPROVAL_DEBUG controller approve: toolCallId={} ok={} pendingCount={} storeHash={}",
                    toolCallId, ok, approvalStore.pendingCount(),
                    Integer.toHexString(System.identityHashCode(approvalStore)));
        } else {
            ok = approvalStore.deny(toolCallId);
            log.info("APPROVAL_DEBUG controller deny: toolCallId={} ok={} pendingCount={} storeHash={}",
                    toolCallId, ok, approvalStore.pendingCount(),
                    Integer.toHexString(System.identityHashCode(approvalStore)));
        }

        return Mono.just(Map.of("success", ok, "toolCallId", toolCallId));
    }
}
