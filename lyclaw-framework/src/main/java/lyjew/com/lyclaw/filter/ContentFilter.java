package lyjew.com.lyclaw.filter;

import lyjew.com.lyclaw.context.ChatContext;

/**
 * 内容过滤器接口，对输入/输出内容执行安全检查。
 *
 * <p>实现类可在 LLM 交互前后过滤不安全内容，如检测提示注入、PII 泄露等。
 * 通过 {@link FilterResult} 返回通过/拒绝状态及过滤后内容。</p>
 */
public interface ContentFilter {

    /**
     * 对内容执行过滤检查。
     *
     * @param content 待过滤的原始内容
     * @param context 当前对话上下文
     * @return 过滤结果（含通过/拒绝状态）
     */
    FilterResult filter(String content, ChatContext context);

    /** @return 过滤器名称，用于日志和审计 */
    String getFilterName();
}
