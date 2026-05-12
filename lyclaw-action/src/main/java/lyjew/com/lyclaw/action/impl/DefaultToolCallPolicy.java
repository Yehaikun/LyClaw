package lyjew.com.lyclaw.action.impl;

import lyjew.com.lyclaw.context.ChatContext;
import lyjew.com.lyclaw.model.ToolCall;
import lyjew.com.lyclaw.tool.ToolCallPolicy;
import lyjew.com.lyclaw.tool.ToolErrorAction;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 默认工具调用策略，控制工具调用的频率、轮次、重试和黑白名单。
 *
 * <p>核心规则：
 * <ul>
 *   <li>最大调用轮次（默认10轮），超出后停止继续调用</li>
 *   <li>单工具调用频率限制（默认20次/会话），防止滥用</li>
 *   <li>工具白名单/黑名单机制</li>
 *   <li>错误重试（默认最多3次）</li>
 *   <li>错误处理动作（默认 ABORT，即终止）</li>
 * </ul>
 * </p>
 */
@Slf4j
@Component
public class DefaultToolCallPolicy implements ToolCallPolicy {

    private static final int DEFAULT_MAX_ROUNDS = 10;
    private static final int DEFAULT_MAX_RETRIES = 3;
    private static final int DEFAULT_MAX_CALLS_PER_TOOL = 20;

    private volatile int maxRounds = DEFAULT_MAX_ROUNDS;
    private volatile int maxRetries = DEFAULT_MAX_RETRIES;
    private volatile int maxCallsPerTool = DEFAULT_MAX_CALLS_PER_TOOL;
    /** 默认错误处理动作，默认为终止执行 */
    private volatile ToolErrorAction defaultErrorAction = ToolErrorAction.ABORT;

    /** 工具白名单（为空时表示不限制，允许所有工具） */
    private final Set<String> allowedTools = ConcurrentHashMap.newKeySet();
    /** 工具黑名单 */
    private final Set<String> blockedTools = ConcurrentHashMap.newKeySet();
    /** 工具调用计数器，key 格式为 "toolName::sessionId" 或 "toolName::global" */
    private final ConcurrentHashMap<String, AtomicLong> callCounters = new ConcurrentHashMap<>();

    /**
     * @return 当前最大调用轮次
     */
    @Override
    public int getMaxRounds() {
        return maxRounds;
    }

    /** @param maxRounds 最大轮次，会被限制为至少 1 */
    public void setMaxRounds(int maxRounds) {
        this.maxRounds = Math.max(1, maxRounds);
    }

    /**
     * 判断当前轮次是否应继续执行。
     *
     * @return true 表示可以继续，false 表示已达到最大轮次
     */
    @Override
    public boolean shouldContinue(ChatContext context, int currentRound) {
        return currentRound < maxRounds;
    }

    /**
     * 处理工具执行错误。
     *
     * @return 当前配置的默认错误处理动作
     */
    @Override
    public ToolErrorAction handleToolError(ToolCall toolCall, Exception e, ChatContext context) {
        log.warn("工具执行出错: tool={}, error={}, action={}",
                toolCall != null ? toolCall.getName() : "unknown",
                e.getMessage(), defaultErrorAction);
        return defaultErrorAction;
    }

    /**
     * 判断是否应重试。
     *
     * @return true 当重试次数未超过上限
     */
    @Override
    public boolean shouldRetryOnError(ToolCall toolCall, Exception e, int retryCount) {
        return retryCount < maxRetries;
    }

    /**
     * 检查工具是否可以在当前上下文中执行。
     */
    @Override
    public boolean canExecute(String toolName, ChatContext context) {
        String sessionId = context != null && context.getSession() != null
                ? context.getSession().getSessionId() : "global";
        return canExecute(toolName, 0, sessionId);
    }

    /**
     * 检查工具是否可以在当前会话中执行。
     *
     * <p>检查顺序：黑名单 > 白名单 > 调用频率限制</p>
     *
     * @param toolName  工具名称
     * @param callCount 本次调用序号（暂未使用，通过内部计数器判断）
     * @param sessionId 会话 ID（为 null 时使用全局计数器）
     * @return true 表示允许执行
     */
    public boolean canExecute(String toolName, int callCount, String sessionId) {
        // 1. 黑名单检查
        if (blockedTools.contains(toolName)) {
            log.warn("工具被阻止: tool={}", toolName);
            return false;
        }
        // 2. 白名单检查（仅当白名单非空时生效）
        if (!allowedTools.isEmpty() && !allowedTools.contains(toolName)) {
            log.warn("工具不在允许列表中: tool={}", toolName);
            return false;
        }

        // 3. 频率限制：构造计数器 key
        String counterKey = toolName + "::" + (sessionId != null ? sessionId : "global");
        AtomicLong counter = callCounters.computeIfAbsent(counterKey, k -> new AtomicLong(0));
        long currentCount = counter.incrementAndGet();

        if (currentCount > maxCallsPerTool) {
            log.warn("工具调用频率超限: tool={}, count={}, limit={}",
                    toolName, currentCount, maxCallsPerTool);
            // 超限回滚计数
            counter.decrementAndGet();
            return false;
        }
        return true;
    }

    /** 将工具加入白名单 */
    public void allowTool(String toolName) {
        allowedTools.add(toolName);
    }

    /** 将工具加入黑名单 */
    public void blockTool(String toolName) {
        blockedTools.add(toolName);
    }

    /** 将工具从黑名单移除 */
    public void unblockTool(String toolName) {
        blockedTools.remove(toolName);
    }

    /** @return 白名单的不可变视图 */
    public Set<String> getAllowedTools() {
        return Collections.unmodifiableSet(allowedTools);
    }

    /** @return 黑名单的不可变视图 */
    public Set<String> getBlockedTools() {
        return Collections.unmodifiableSet(blockedTools);
    }

    /** @return 单工具最大调用次数 */
    public int getMaxCallsPerTool() {
        return maxCallsPerTool;
    }

    /** @param maxCallsPerTool 单工具最大调用次数，会被限制为至少 1 */
    public void setMaxCallsPerTool(int maxCallsPerTool) {
        this.maxCallsPerTool = Math.max(1, maxCallsPerTool);
    }

    /** @param action 默认的错误处理动作 */
    public void setDefaultErrorAction(ToolErrorAction action) {
        this.defaultErrorAction = action;
    }

    /**
     * 重置指定会话的工具调用计数器。
     *
     * @param sessionId 会话 ID，为 null 时重置全局计数器
     */
    public void resetCallCounters(String sessionId) {
        String suffix = "::" + (sessionId != null ? sessionId : "global");
        callCounters.entrySet().removeIf(entry -> entry.getKey().endsWith(suffix));
    }

    /** 重置所有工具调用计数器 */
    public void resetAllCallCounters() {
        callCounters.clear();
    }
}
