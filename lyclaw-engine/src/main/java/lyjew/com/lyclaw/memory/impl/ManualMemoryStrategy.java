package lyjew.com.lyclaw.memory.impl;

import lyjew.com.lyclaw.context.ChatContext;
import lyjew.com.lyclaw.memory.MemoryContent;
import lyjew.com.lyclaw.memory.MemoryStrategy;

import org.springframework.stereotype.Component;

/**
 * 手动记忆策略 —— 始终将记忆注入上下文，不做任何截断。
 *
 * <p><b>设计动机</b>：某些场景下（如调试、长度可控的短记忆），
 * 不需要复杂的记忆选择逻辑。ManualMemoryStrategy 始终返回 true，
 * 相当于"全量记忆注入"。作为其他策略的兜底。</p>
 *
 * <p>在实际项目中，通常会有更复杂的策略，如：
 * <ul>
 *   <li>重要性过滤：只注入重要性高于阈值的记忆</li>
 *   <li>时间衰减：只注入最近 N 条记忆</li>
 *   <li>相关性匹配：只注入与当前 query 语义相关的记忆</li>
 * </ul>
 * </p>
 *
 * @since 1.0
 * @author LyClaw Team
 * @see MemoryStrategy
 */
@Component
public class ManualMemoryStrategy implements MemoryStrategy {

    @Override
    public String formatForContext(MemoryContent content) {
        // 用 <memory> 标签包裹记忆内容
        return "<memory>\n" + (content != null ? content.getContent() : "") + "\n</memory>";
    }

    @Override
    public boolean shouldIncludeInContext(MemoryContent content,
                                          ChatContext context) {
        // 始终注入记忆
        return true;
    }

    @Override
    public int getPriority() {
        // 最低优先级（兜底策略），始终最后被选择
        return 0;
    }
}