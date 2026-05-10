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

@Slf4j
@Component
public class DefaultToolCallPolicy implements ToolCallPolicy {

    private static final int DEFAULT_MAX_ROUNDS = 10;
    private static final int DEFAULT_MAX_RETRIES = 3;
    private static final int DEFAULT_MAX_CALLS_PER_TOOL = 20;

    private volatile int maxRounds = DEFAULT_MAX_ROUNDS;
    private volatile int maxRetries = DEFAULT_MAX_RETRIES;
    private volatile int maxCallsPerTool = DEFAULT_MAX_CALLS_PER_TOOL;
    private volatile ToolErrorAction defaultErrorAction = ToolErrorAction.ABORT;

    private final Set<String> allowedTools = ConcurrentHashMap.newKeySet();
    private final Set<String> blockedTools = ConcurrentHashMap.newKeySet();
    private final ConcurrentHashMap<String, AtomicLong> callCounters = new ConcurrentHashMap<>();

    @Override
    public int getMaxRounds() {
        return maxRounds;
    }

    public void setMaxRounds(int maxRounds) {
        this.maxRounds = Math.max(1, maxRounds);
    }

    @Override
    public boolean shouldContinue(ChatContext context, int currentRound) {
        return currentRound < maxRounds;
    }

    @Override
    public ToolErrorAction handleToolError(ToolCall toolCall, Exception e, ChatContext context) {
        log.warn("工具执行出错: tool={}, error={}, action={}",
                toolCall != null ? toolCall.getName() : "unknown",
                e.getMessage(), defaultErrorAction);
        return defaultErrorAction;
    }

    @Override
    public boolean shouldRetryOnError(ToolCall toolCall, Exception e, int retryCount) {
        return retryCount < maxRetries;
    }

    public boolean canExecute(String toolName, int callCount, String sessionId) {
        if (blockedTools.contains(toolName)) {
            log.warn("工具被阻止: tool={}", toolName);
            return false;
        }
        if (!allowedTools.isEmpty() && !allowedTools.contains(toolName)) {
            log.warn("工具不在允许列表中: tool={}", toolName);
            return false;
        }

        String counterKey = toolName + "::" + (sessionId != null ? sessionId : "global");
        AtomicLong counter = callCounters.computeIfAbsent(counterKey, k -> new AtomicLong(0));
        long currentCount = counter.incrementAndGet();

        if (currentCount > maxCallsPerTool) {
            log.warn("工具调用频率超限: tool={}, count={}, limit={}",
                    toolName, currentCount, maxCallsPerTool);
            counter.decrementAndGet();
            return false;
        }
        return true;
    }

    public void allowTool(String toolName) {
        allowedTools.add(toolName);
    }

    public void blockTool(String toolName) {
        blockedTools.add(toolName);
    }

    public void unblockTool(String toolName) {
        blockedTools.remove(toolName);
    }

    public Set<String> getAllowedTools() {
        return Collections.unmodifiableSet(allowedTools);
    }

    public Set<String> getBlockedTools() {
        return Collections.unmodifiableSet(blockedTools);
    }

    public int getMaxCallsPerTool() {
        return maxCallsPerTool;
    }

    public void setMaxCallsPerTool(int maxCallsPerTool) {
        this.maxCallsPerTool = Math.max(1, maxCallsPerTool);
    }

    public void setDefaultErrorAction(ToolErrorAction action) {
        this.defaultErrorAction = action;
    }

    public void resetCallCounters(String sessionId) {
        String suffix = "::" + (sessionId != null ? sessionId : "global");
        callCounters.entrySet().removeIf(entry -> entry.getKey().endsWith(suffix));
    }

    public void resetAllCallCounters() {
        callCounters.clear();
    }
}
