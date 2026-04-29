package lyjew.com.lyclaw.security;

import lyjew.com.lyclaw.context.ChatContext;

import java.util.List;

/**
 * 安全管理器接口 —— 负责前置审批、权限检查、会话撤销、安全策略管理。
 *
 * <p>InterceptorStage 在 ToolCallLoop 执行前调用 SecurityManager 做权限校验。
 * 根据业务需要，审批流程可以是同步（直接返回）或异步（触发审批流后轮询结果）。</p>
 *
 * <p><b>设计动机</b>：如果不通过 SecurityManager 统一管理安全和审批逻辑，
 * 每个 PipelineStage 和 Tool 都需要自行实现权限判断，导致安全逻辑分散在各处。
 * SecurityManager 将安全策略集中管理，通过 approve() 获得审批后才能继续执行。</p>
 *
 * <p><b>审批流程</b>：
 * <ol>
 *   <li>InterceptorStage.preHandle() 调用 securityManager.approve(context, action)</li>
 *   <li>SecurityManager 根据请求内容判断是否需要人工审批</li>
 *   <li>需要审批时返回 {@link ApprovalResult}（approved=false + 合理 reason）</li>
 *   <li>通过审批后返回 {@link ApprovalResult}（approved=true + sandboxLevel）</li>
 * </ol>
 * </p>
 *
 * @since 1.0
 * @author LyClaw Team
 * @see ApprovalResult
 * @see SandboxLevel
 */
public interface SecurityManager {

    /**
     * 前置审批。在敏感操作执行前调用，获取审批结果。
     * 如果返回 denied，InterceptorStage 将中断管道执行。
     *
     * <p>action 的常见取值："EXECUTE_TOOL"、"MODIFY_MEMORY"、"DELETE_SESSION" 等。
     * 具体动作列表由 SecurityManager 实现方自行定义。</p>
     *
     * @param context 当前对话上下文（包含会话信息和请求信息）
     * @param action  要执行的动作标识
     * @return 审批结果
     */
    ApprovalResult approve(ChatContext context, String action);

    /**
     * 撤销已批准的会话审批。比如用户在审批后改变主意，或者定时撤销。
     *
     * @param sessionId 要撤销的会话 ID
     */
    void revoke(String sessionId);

    /**
     * 检查用户是否有执行某操作的权限。
     * 与 approve() 的区别：approve() 是审批流程（可能有异步审批流），
     * checkPermission 是同步的权限判断（基于角色/策略的静态检查）。
     *
     * <p>userId 从 ChatContext.getSession().getUserId() 获取。
     * 如果没有 userId（匿名会话），默认返回 false。</p>
     *
     * @param userId 用户 ID
     * @param action 要执行的动作标识
     * @return true 表示有权限
     */
    boolean checkPermission(String userId, String action);

    /**
     * 获取当前生效的安全策略名称列表，用于日志记录和管理端展示。
     *
     * @return 策略名称列表，不可为 null
     */
    List<String> getEffectivePolicies();
}