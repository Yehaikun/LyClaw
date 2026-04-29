package lyjew.com.lyclaw.memory.impl;

import lyjew.com.lyclaw.memory.MemoryContent;
import lyjew.com.lyclaw.memory.MemoryManager;

import lyjew.com.lyclaw.memory.MemoryStrategy;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;

/**
 * MemoryManager 空对象实现 —— read 返回空 MemoryContent，
 * append/rewrite 空操作，search 返回空列表。
 *
 * <p>当应用不需要记忆功能时，注入此实现避免 NPE。</p>
 *
 * <p><b>Spring 注入</b>：@Component + @ConditionalOnMissingBean(MemoryManager.class)，
 * 当没有其他 MemoryManager 实现时自动使用此空对象。</p>
 *
 * @since 1.0
 * @author LyClaw Team
 */
@Component
@ConditionalOnMissingBean(MemoryManager.class)
public class NullMemoryManager implements MemoryManager {

    @Override
    public MemoryContent read() {
        return new MemoryContent("", "null", false, Collections.emptyList(), 0.0);
    }

    @Override
    public void append(String content) { /* 空操作 */ }

    @Override
    public void rewrite(String content) { /* 空操作 */ }

    @Override
    public List<MemoryContent> search(String query) {
        return Collections.emptyList();
    }

    @Override
    public MemoryStrategy getStrategy() {
        return null;
    }

    @Override
    public void setStrategy(MemoryStrategy strategy) {
        /* 空操作 */
    }
}