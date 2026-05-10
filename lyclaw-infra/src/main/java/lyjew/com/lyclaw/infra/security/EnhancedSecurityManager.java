package lyjew.com.lyclaw.infra.security;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import lyjew.com.lyclaw.context.ChatContext;
import lyjew.com.lyclaw.filter.ContentFilter;
import lyjew.com.lyclaw.filter.FilterResult;
import lyjew.com.lyclaw.security.ApprovalResult;
import lyjew.com.lyclaw.security.AuditLog;
import lyjew.com.lyclaw.security.PermissionLevel;
import lyjew.com.lyclaw.security.SandboxLevel;
import lyjew.com.lyclaw.security.SecurityManager;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

@Slf4j
@Component
public class EnhancedSecurityManager implements SecurityManager {

    private final List<ContentFilter> inputFilters = new CopyOnWriteArrayList<>();
    private final List<ContentFilter> outputFilters = new CopyOnWriteArrayList<>();
    private final Map<String, ApprovalResult> approvedSessions = new ConcurrentHashMap<>();
    private final Map<String, PendingApproval> pendingApprovals = new ConcurrentHashMap<>();
    private final Map<String, PermissionLevel> toolPermissions = new ConcurrentHashMap<>();
    private final List<AuditLog> auditLog = new ArrayList<>();
    private String previousHash = "GENESIS";

    @Data
    public static class PendingApproval {
        private final String sessionId;
        private final String action;
        private final PermissionLevel requiredLevel;
        private final Instant requestedAt = Instant.now();
    }

    public EnhancedSecurityManager() {
        initToolPermissions();
    }

    private void initToolPermissions() {
        toolPermissions.put("ReadFile", PermissionLevel.READ);
        toolPermissions.put("WriteFile", PermissionLevel.EXECUTE_MODIFY);
        toolPermissions.put("DeleteFile", PermissionLevel.EXECUTE_DESTRUCTIVE);
        toolPermissions.put("ExecuteCommand", PermissionLevel.EXECUTE_SAFE);
        toolPermissions.put("WebSearch", PermissionLevel.READ);
        toolPermissions.put("MemoryModify", PermissionLevel.EXECUTE_MODIFY);
        toolPermissions.put("SystemConfig", PermissionLevel.ADMIN);
        toolPermissions.put("DatabaseQuery", PermissionLevel.READ);
        toolPermissions.put("DatabaseUpdate", PermissionLevel.EXECUTE_MODIFY);
        toolPermissions.put("DatabaseDelete", PermissionLevel.EXECUTE_DESTRUCTIVE);
        toolPermissions.put("SendEmail", PermissionLevel.EXECUTE_SAFE);
        toolPermissions.put("CreateFile", PermissionLevel.EXECUTE_MODIFY);
        toolPermissions.put("RunScript", PermissionLevel.EXECUTE_DESTRUCTIVE);
    }

    // ======================== Guardrail Chain ========================

    public void addInputFilter(ContentFilter filter) {
        inputFilters.add(filter);
        log.info("Added input filter: {} (total: {})", filter.getFilterName(), inputFilters.size());
    }

    public void addOutputFilter(ContentFilter filter) {
        outputFilters.add(filter);
        log.info("Added output filter: {} (total: {})", filter.getFilterName(), outputFilters.size());
    }

    public FilterResult applyInputGuardrails(String content, ChatContext context) {
        if (content == null || content.isEmpty()) return FilterResult.pass(content);
        String current = content;
        for (ContentFilter filter : inputFilters) {
            FilterResult result = filter.filter(current, context);
            if (!result.isPassed()) {
                log.warn("Input rejected by filter '{}': {}", filter.getFilterName(), result.getReason());
                writeAuditLog(context, "INPUT_FILTER_REJECT", filter.getFilterName(),
                        PermissionLevel.DENY, false, result.getReason());
                return result;
            }
            current = result.getFilteredContent();
        }
        return FilterResult.pass(current);
    }

    public FilterResult applyOutputGuardrails(String content, ChatContext context) {
        if (content == null || content.isEmpty()) return FilterResult.pass(content);
        String current = content;
        for (ContentFilter filter : outputFilters) {
            FilterResult result = filter.filter(current, context);
            if (!result.isPassed()) {
                log.warn("Output rejected by filter '{}': {}", filter.getFilterName(), result.getReason());
                writeAuditLog(context, "OUTPUT_FILTER_REJECT", filter.getFilterName(),
                        PermissionLevel.READ, false, result.getReason());
                return result;
            }
            current = result.getFilteredContent();
        }
        return FilterResult.pass(current);
    }

    public List<ContentFilter> getInputFilters() { return List.copyOf(inputFilters); }
    public List<ContentFilter> getOutputFilters() { return List.copyOf(outputFilters); }

    // ======================== Approval ========================

    @Override
    public ApprovalResult approve(ChatContext context, String action) {
        String sessionId = extractSessionId(context);
        if (sessionId == null) return ApprovalResult.denied("No session ID");

        ApprovalResult existing = approvedSessions.get(sessionId);
        if (existing != null && existing.isApproved()) return existing;

        PermissionLevel required = resolvePermissionLevel(action);
        if (required == PermissionLevel.DENY) {
            ApprovalResult result = ApprovalResult.denied("Action denied: " + action);
            writeAuditLog(context, action, "approve", required, false, "Action explicitly denied");
            return result;
        }

        if (required == PermissionLevel.READ || required == PermissionLevel.EXECUTE_SAFE) {
            ApprovalResult result = ApprovalResult.granted(mapToSandboxLevel(required));
            approvedSessions.put(sessionId, result);
            writeAuditLog(context, action, "auto-approve", required, true, "Auto-approved safe operation");
            return result;
        }

        log.info("Action '{}' requires {} level, session={}", action, required, sessionId);
        pendingApprovals.put(sessionId, new PendingApproval(sessionId, action, required));

        ApprovalResult result = ApprovalResult.granted(mapToSandboxLevel(required));
        approvedSessions.put(sessionId, result);
        pendingApprovals.remove(sessionId);
        writeAuditLog(context, action, "approved", required, true,
                "Approved with sandbox: " + result.getSandboxLevel());
        return result;
    }

    @Override
    public void revoke(String sessionId) {
        approvedSessions.remove(sessionId);
        pendingApprovals.remove(sessionId);
        log.info("Session revoked: {}", sessionId);
    }

    // ======================== Permissions ========================

    @Override
    public boolean checkPermission(String userId, String action, PermissionLevel requiredLevel) {
        if (userId == null) return false;
        if ("anonymous".equalsIgnoreCase(userId))
            return requiredLevel.getLevel() <= PermissionLevel.READ.getLevel();
        if ("admin".equalsIgnoreCase(userId)) return true;
        return requiredLevel.getLevel() <= PermissionLevel.EXECUTE_SAFE.getLevel();
    }

    @Override
    public boolean checkPermission(String userId, String action) {
        return checkPermission(userId, action, resolvePermissionLevel(action));
    }

    @Override
    public List<String> getEffectivePolicies() {
        return List.of(
                "RBAC-default", "Tool-level-permissions", "Session-scoped-approval",
                "GuardrailChain-" + inputFilters.stream().map(ContentFilter::getFilterName)
                        .collect(Collectors.joining(",")),
                "AuditLog-hashchain-verified");
    }

    // ======================== Pending Approvals ========================

    public List<PendingApproval> getPendingApprovals() {
        return List.copyOf(pendingApprovals.values());
    }

    public boolean hasPendingApproval(String sessionId) {
        return pendingApprovals.containsKey(sessionId);
    }

    public ApprovalResult approvePending(String sessionId) {
        PendingApproval pending = pendingApprovals.remove(sessionId);
        if (pending == null) return ApprovalResult.denied("No pending approval: " + sessionId);
        ApprovalResult result = ApprovalResult.granted(mapToSandboxLevel(pending.getRequiredLevel()));
        approvedSessions.put(sessionId, result);
        log.info("Approved pending: session={}, action={}", sessionId, pending.getAction());
        return result;
    }

    public ApprovalResult denyPending(String sessionId, String reason) {
        PendingApproval pending = pendingApprovals.remove(sessionId);
        if (pending == null) return ApprovalResult.denied("No pending approval: " + sessionId);
        log.info("Denied pending: session={}, action={}, reason={}",
                sessionId, pending.getAction(), reason);
        return ApprovalResult.denied(reason);
    }

    // ======================== Tool Permissions ========================

    public void setToolPermission(String toolName, PermissionLevel level) {
        toolPermissions.put(toolName, level);
        log.info("Tool permission set: {} -> {}", toolName, level);
    }

    public PermissionLevel getToolPermission(String toolName) {
        return toolPermissions.getOrDefault(toolName, PermissionLevel.EXECUTE_SAFE);
    }

    // ======================== Audit Log ========================

    public List<AuditLog> exportAuditLog() {
        synchronized (auditLog) { return List.copyOf(auditLog); }
    }

    public List<AuditLog> exportAuditLogByUser(String userId) {
        synchronized (auditLog) {
            return auditLog.stream().filter(e -> userId.equals(e.getUserId())).toList();
        }
    }

    public List<AuditLog> exportAuditLogBySession(String sessionId) {
        synchronized (auditLog) {
            return auditLog.stream().filter(e -> sessionId.equals(e.getSessionId())).toList();
        }
    }

    public List<AuditLog> exportAuditLogByTimeRange(Instant from, Instant to) {
        synchronized (auditLog) {
            return auditLog.stream()
                    .filter(e -> !e.getTimestamp().isBefore(from) && !e.getTimestamp().isAfter(to))
                    .toList();
        }
    }

    public int getAuditLogSize() {
        synchronized (auditLog) { return auditLog.size(); }
    }

    public void clearAuditLog() {
        synchronized (auditLog) { auditLog.clear(); previousHash = "GENESIS"; }
        log.info("Audit log cleared");
    }

    // ======================== Private helpers ========================

    private void writeAuditLog(ChatContext context, String action, String target,
                               PermissionLevel required, boolean approved, String reason) {
        String userId = extractUserId(context);
        String sessionId = extractSessionId(context);
        AuditLog entry = AuditLog.builder()
                .logId(UUID.randomUUID().toString())
                .userId(userId).sessionId(sessionId)
                .action(action).target(target)
                .requiredLevel(required).approved(approved)
                .reason(reason).timestamp(Instant.now())
                .previousHash(previousHash).build();
        entry.setCurrentHash(entry.computeHash());
        synchronized (auditLog) { auditLog.add(entry); previousHash = entry.getCurrentHash(); }
    }

    private PermissionLevel resolvePermissionLevel(String action) {
        if (action.startsWith("EXECUTE_TOOL:")) {
            return toolPermissions.getOrDefault(
                    action.substring("EXECUTE_TOOL:".length()), PermissionLevel.EXECUTE_SAFE);
        }
        return switch (action) {
            case "READ_FILE" -> PermissionLevel.READ;
            case "WRITE_FILE" -> PermissionLevel.EXECUTE_MODIFY;
            case "DELETE_FILE", "DELETE_SESSION" -> PermissionLevel.EXECUTE_DESTRUCTIVE;
            case "MODIFY_MEMORY" -> PermissionLevel.EXECUTE_MODIFY;
            case "SYSTEM_CONFIG" -> PermissionLevel.ADMIN;
            case "EXECUTE_COMMAND" -> PermissionLevel.EXECUTE_SAFE;
            case "WEB_SEARCH" -> PermissionLevel.READ;
            default -> PermissionLevel.EXECUTE_SAFE;
        };
    }

    private SandboxLevel mapToSandboxLevel(PermissionLevel p) {
        return switch (p) {
            case DENY -> SandboxLevel.ISOLATED;
            case READ -> SandboxLevel.READ_ONLY;
            case EXECUTE_SAFE, EXECUTE_MODIFY -> SandboxLevel.RESTRICTED;
            case EXECUTE_DESTRUCTIVE -> SandboxLevel.CONTAINER;
            case ADMIN -> SandboxLevel.NONE;
        };
    }

    private String extractSessionId(ChatContext ctx) {
        if (ctx == null || ctx.getSession() == null) return null;
        var s = ctx.getSession();
        return s.getSessionId() != null ? s.getSessionId() : s.getId();
    }

    private String extractUserId(ChatContext ctx) {
        if (ctx == null || ctx.getSession() == null) return "unknown";
        String id = ctx.getSession().getId();
        return id != null ? id : "anonymous";
    }
}
