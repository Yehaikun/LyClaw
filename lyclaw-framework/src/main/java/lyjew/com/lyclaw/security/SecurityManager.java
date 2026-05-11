package lyjew.com.lyclaw.security;

import lyjew.com.lyclaw.context.ChatContext;

import java.util.List;

/**
 * 安全管理器接口，定义系统安全审批和权限校验的核心操作。
 *
 * <p>SecurityManager 是安全模块的核心入口。在 AI 代理执行任何可能产生
 * 安全影响的操作之前，系统会通过该接口进行审批。审批流程结合了对话上下文
 * （{@link ChatContext}），以作出更智能的决策。</p>
 *
 * <p>核心功能包括：</p>
 * <ul>
 *   <li>操作审批（{@link #approve(ChatContext, String)}）：在执行前判断是否允许</li>
 *   <li>权限撤销（{@link #revoke(String)}）：撤回已授予的会话权限</li>
 *   <li>权限检查（{@link #checkPermission(String, String)}）：检查用户的权限级别</li>
 *   <li>策略查询（{@link #getEffectivePolicies()}）：获取当前生效的策略列表</li>
 * </ul>
 */
public interface SecurityManager {

    /**
     * 对指定操作进行安全审批。
     * 根据对话上下文（用户、会话、历史记录等）决定是否批准该操作，
     * 并在批准时分配适当的沙箱级别。
     *
     * @param context 当前的对话上下文
     * @param action  待审批的操作名称（如 "execute_command"、"write_file"）
     * @return 审批结果，包含是否通过、原因和沙箱级别
     */
    ApprovalResult approve(ChatContext context, String action);

    /**
     * 撤销指定会话的所有已授予权限。
     * 通常在会话终止或检测到异常行为时调用。
     *
     * @param sessionId 要撤销权限的会话 ID
     */
    void revoke(String sessionId);

    /**
     * 检查用户是否拥有执行指定操作的权限。
     *
     * @param userId 用户 ID
     * @param action 操作名称
     * @return true 表示用户有权执行该操作
     */
    boolean checkPermission(String userId, String action);

    /**
     * 检查用户是否满足指定操作所需的权限级别。
     * 默认实现委托给 {@link #checkPermission(String, String)}，
     * 子类可重写以实现更细粒度的权限检查。
     *
     * @param userId        用户 ID
     * @param action        操作名称
     * @param requiredLevel 所需的权限级别
     * @return true 表示用户满足该权限级别
     */
    default boolean checkPermission(String userId, String action, PermissionLevel requiredLevel) {
        return checkPermission(userId, action);
    }

    /**
     * 获取当前系统中所有已生效的安全策略列表。
     *
     * @return 生效策略的名称或描述列表
     */
    List<String> getEffectivePolicies();
}
