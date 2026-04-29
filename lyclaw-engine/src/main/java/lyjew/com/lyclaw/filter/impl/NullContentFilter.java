package lyjew.com.lyclaw.filter.impl;

import lyjew.com.lyclaw.context.ChatContext;
import lyjew.com.lyclaw.filter.ContentFilter;
import lyjew.com.lyclaw.filter.FilterResult;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Component;

/**
 * ContentFilter 空对象实现 —— filter 始终返回 pass(content)，
 * getFilterName 返回 "NullContentFilter"。
 *
 * <p>当应用不需要内容过滤功能时，注入此实现避免 NPE。</p>
 *
 * <p><b>Spring 注入</b>：@Component + @ConditionalOnMissingBean(ContentFilter.class)，
 * 当没有其他 ContentFilter 实现时自动使用此空对象。</p>
 *
 * @since 1.0
 * @author LyClaw Team
 */
@Component
@ConditionalOnMissingBean(ContentFilter.class)
public class NullContentFilter implements ContentFilter {

    @Override
    public FilterResult filter(String content, ChatContext context) {
        return FilterResult.pass(content);
    }

    @Override
    public String getFilterName() {
        return "NullContentFilter";
    }
}