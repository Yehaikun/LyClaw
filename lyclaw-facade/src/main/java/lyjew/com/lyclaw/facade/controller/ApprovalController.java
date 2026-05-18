package lyjew.com.lyclaw.facade.controller;

import java.util.Map;

import lyjew.com.lyclaw.react.ApprovalStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/**
 * 工具审批控制器，接收前端用户在确认对话框中的选择。
 */
@RestController
public class ApprovalController {

    private static final Logger log = LoggerFactory.getLogger(ApprovalController.class);

    private final ApprovalStore approvalStore;

    public ApprovalController(ApprovalStore approvalStore) {
        this.approvalStore = approvalStore;
    }

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
        } else {
            ok = approvalStore.deny(toolCallId);
        }
        log.info("审批响应: toolCallId={} approved={} ok={} pendingCount={}",
                toolCallId, approved, ok, approvalStore.pendingCount());

        return Mono.just(Map.of("success", ok, "toolCallId", toolCallId));
    }
}
