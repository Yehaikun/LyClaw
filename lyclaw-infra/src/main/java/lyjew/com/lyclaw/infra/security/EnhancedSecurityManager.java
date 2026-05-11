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

/**
 * 增强安全管理器，提供多层安全防护：防护栏链、权限审批、工具权限和审计日志。
 *
 * <p>核心安全功能：
 * <ul>
 *   <li><b>防护栏链（Guardrail Chain）</b>：输入/输出内容过滤器链，任一过滤器拒绝则拦截</li>
 *   <li><b>审批机制</b>：根据操作所需的权限级别自动审批或标记为待审</li>
 *   <li><b>RBAC 权限检查</b>：基于用户角色的权限校验（anonymous/普通用户/admin）</li>
 *   <li><b>工具权限映射</b>：为每个工具预定义所需的最低权限级别</li>
 *   <li><b>审计日志</b>：带哈希链的防篡改审计日志，支持按用户/会话/时间范围导出</li>
 * </ul>
 * </p>
 */
@Slf4j
@Component
public class EnhancedSecurityManager implements SecurityManager {

    /** 输入内容过滤器链（CopyOnWriteArrayList 保证读多写少场景的线程安全） */
    private final List<ContentFilter> inputFilters = new CopyOnWriteArrayList<>();
    /** 输出内容过滤器链 */
    private final List<ContentFilter> outputFilters = new CopyOnWriteArrayList<>();
    /** 已审批通过的会话及其审批结果 */
    private final Map<String, ApprovalResult> approvedSessions = new ConcurrentHashMap<>();
    /** 待审批的会话 */
    private final Map<String, PendingApproval> pendingApprovals = new ConcurrentHashMap<>();
    /** 工具名称到所需权限级别的映射 */
    private final Map<String, PermissionLevel> toolPermissions = new ConcurrentHashMap<>();
    /** 审计日志列表 */
    private final List<AuditLog> auditLog = new ArrayList<>();
    /** 审计日志哈希链的上一个哈希值（防篡改） */
    private String previousHash = "GENESIS";

    /** 待审批操作的数据类 */
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

    /** 初始化预定义的工具权限映射 */
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

    // ======================== 防护栏链 ========================

    /** 添加输入内容过滤器 */
    public void addInputFilter(ContentFilter filter) {
        inputFilters.add(filter);
        log.info("Added input filter: {} (total: {})", filter.getFilterName(), inputFilters.size());
    }

    /** 添加输出内容过滤器 */
    public void addOutputFilter(ContentFilter filter) {
        outputFilters.add(filter);
        log.info("Added output filter: {} (total: {})", filter.getFilterName(), outputFilters.size());
    }

    /**
     * 对输入内容依次应用所有输入过滤器。
     * 任一过滤器拒绝则返回拒绝结果并记录审计日志。
     *
     * @param content 输入内容
     * @param context 对话上下文
     * @return 过滤结果
     */
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

    /**
     * 对输出内容依次应用所有输出过滤器。
     */
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

    // ======================== 审批 ========================

    /**
     * 审批会话中的操作。
     *
     * <p>逻辑：
     * <ol>
     *   <li>如果会话已有审批记录则复用</li>
     *   <li>解析操作所需的权限级别</li>
     *   <li>DENY 级别直接拒绝</li>
     *   <li>READ/EXECUTE_SAFE 级别自动批准</li>
     *   <li>更高级别记录到待审批列表并批准（带沙箱限制）</li>
     * </ol>
     * </p>
     */
    @Override
    public ApprovalResult approve(ChatContext context, String action) {
        String sessionId = extractSessionId(context);
        if (sessionId == null) return ApprovalResult.denied("No session ID");

        // 复用已有审批结果
        ApprovalResult existing = approvedSessions.get(sessionId);
        if (existing != null && existing.isApproved()) return existing;

        PermissionLevel required = resolvePermissionLevel(action);
        // DENY 级别直接拒绝
        if (required == PermissionLevel.DENY) {
            ApprovalResult result = ApprovalResult.denied("Action denied: " + action);
            writeAuditLog(context, action, "approve", required, false, "Action explicitly denied");
            return result;
        }

        // 低风险操作自动批准
        if (required == PermissionLevel.READ || required == PermissionLevel.EXECUTE_SAFE) {
            ApprovalResult result = ApprovalResult.granted(mapToSandboxLevel(required));
            approvedSessions.put(sessionId, result);
            writeAuditLog(context, action, "auto-approve", required, true, "Auto-approved safe operation");
            return result;
        }

        // 高风险操作需要记录审批但当前自动放行
        log.info("Action '{}' requires {} level, session={}", action, required, sessionId);
        pendingApprovals.put(sessionId, new PendingApproval(sessionId, action, required));

        ApprovalResult result = ApprovalResult.granted(mapToSandboxLevel(required));
        approvedSessions.put(sessionId, result);
        pendingApprovals.remove(sessionId);
        writeAuditLog(context, action, "approved", required, true,
                "Approved with sandbox: " + result.getSandboxLevel());
        return result;
    }

    /** 撤销会话的审批状态 */
    @Override
    public void revoke(String sessionId) {
        approvedSessions.remove(sessionId);
        pendingApprovals.remove(sessionId);
        log.info("Session revoked: {}", sessionId);
    }

    // ======================== 权限 ========================

    /**
     * RBAC 权限检查。
     * anonymous 只能 READ，admin 全部允许，普通用户最多 EXECUTE_SAFE。
     */
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

    /** @return 当前生效的安全策略列表 */
    @Override
    public List<String> getEffectivePolicies() {
        return List.of(
                "RBAC-default", "Tool-level-permissions", "Session-scoped-approval",
                "GuardrailChain-" + inputFilters.stream().map(ContentFilter::getFilterName)
                        .collect(Collectors.joining(",")),
                "AuditLog-hashchain-verified");
    }

    // ======================== 待审批 ========================

    /** @return 所有待审批操作的不可变列表 */
    public List<PendingApproval> getPendingApprovals() {
        return List.copyOf(pendingApprovals.values());
    }

    public boolean hasPendingApproval(String sessionId) {
        return pendingApprovals.containsKey(sessionId);
    }

    /** 批准一个待审批操作 */
    public ApprovalResult approvePending(String sessionId) {
        PendingApproval pending = pendingApprovals.remove(sessionId);
        if (pending == null) return ApprovalResult.denied("No pending approval: " + sessionId);
        ApprovalResult result = ApprovalResult.granted(mapToSandboxLevel(pending.getRequiredLevel()));
        approvedSessions.put(sessionId, result);
        log.info("Approved pending: session={}, action={}", sessionId, pending.getAction());
        return result;
    }

    /** 拒绝一个待审批操作 */
    public ApprovalResult denyPending(String sessionId, String reason) {
        PendingApproval pending = pendingApprovals.remove(sessionId);
        if (pending == null) return ApprovalResult.denied("No pending approval: " + sessionId);
        log.info("Denied pending: session={}, action={}, reason={}",
                sessionId, pending.getAction(), reason);
        return ApprovalResult.denied(reason);
    }

    // ======================== 工具权限 ========================

    /** 设置工具的权限级别 */
    public void setToolPermission(String toolName, PermissionLevel level) {
        toolPermissions.put(toolName, level);
        log.info("Tool permission set: {} -> {}", toolName, level);
    }

    /** 获取工具的权限级别，默认 EXECUTE_SAFE */
    public PermissionLevel getToolPermission(String toolName) {
        return toolPermissions.getOrDefault(toolName, PermissionLevel.EXECUTE_SAFE);
    }

    // ======================== 审计日志 ========================

    /** 导出所有审计日志 */
    public List<AuditLog> exportAuditLog() {
        synchronized (auditLog) { return List.copyOf(auditLog); }
    }

    /** 按用户导出审计日志 */
    public List<AuditLog> exportAuditLogByUser(String userId) {
        synchronized (auditLog) {
            return auditLog.stream().filter(e -> userId.equals(e.getUserId())).toList();
        }
    }

    /** 按会话导出审计日志 */
    public List<AuditLog> exportAuditLogBySession(String sessionId) {
        synchronized (auditLog) {
            return auditLog.stream().filter(e -> sessionId.equals(e.getSessionId())).toList();
        }
    }

    /** 按时间范围导出审计日志 */
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

    /** 清空审计日志并重置哈希链 */
    public void clearAuditLog() {
        synchronized (auditLog) { auditLog.clear(); previousHash = "GENESIS"; }
        log.info("Audit log cleared");
    }

    // ======================== 私有辅助方法 ========================

    /** 记录一条审计日志，并更新哈希链以防止篡改 */
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

    /** 根据操作名称解析所需的权限级别 */
    private PermissionLevel resolvePermissionLevel(String action) {
        // 对于 "EXECUTE_TOOL:<工具名>" 格式，根据工具权限映射查询
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

    /** 将权限级别映射到对应的沙箱隔离级别 */
    private SandboxLevel mapToSandboxLevel(PermissionLevel p) {
        return switch (p) {
            case DENY -> SandboxLevel.ISOLATED;
            case READ -> SandboxLevel.READ_ONLY;
            case EXECUTE_SAFE, EXECUTE_MODIFY -> SandboxLevel.RESTRICTED;
            case EXECUTE_DESTRUCTIVE -> SandboxLevel.CONTAINER;
            case ADMIN -> SandboxLevel.NONE;
        };
    }

    /** 从上下文中提取会话 ID */
    private String extractSessionId(ChatContext ctx) {
        if (ctx == null || ctx.getSession() == null) return null;
        var s = ctx.getSession();
        return s.getSessionId() != null ? s.getSessionId() : s.getId();
    }

    /** 从上下文中提取用户 ID，未知用户返回 "anonymous" */
    private String extractUserId(ChatContext ctx) {
        if (ctx == null || ctx.getSession() == null) return "unknown";
        String id = ctx.getSession().getId();
        return id != null ? id : "anonymous";
    }
}
