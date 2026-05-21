package lyjew.com.lyclaw.web.controller;

import java.util.Map;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lyjew.com.lyclaw.react.ApprovalStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

@Tag(name = "Approval", description = "工具审批接口，前端弹窗确认/拒绝工具调用")
@RestController
public class ApprovalController {

    private static final Logger log = LoggerFactory.getLogger(ApprovalController.class);

    private final ApprovalStore approvalStore;

    public ApprovalController(ApprovalStore approvalStore) {
        this.approvalStore = approvalStore;
    }

    @Operation(summary = "审批响应", description = "前端确认或拒绝某个工具调用，body需包含toolCallId和approved字段")
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
