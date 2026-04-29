package lyjew.com.lyclaw.filter;

import java.util.Collections;
import java.util.List;

/**
 * 过滤结果值对象 —— 包含是否通过、过滤后的内容、拒绝原因、匹配的规则列表。
 *
 * <p>使用静态工厂方法构造常见结果：
 * <ul>
 *   <li>{@link #pass(String)} — 内容通过过滤</li>
 *   <li>{@link #reject(String, String)} — 内容被拒绝</li>
 * </ul>
 * </p>
 *
 * <p><b>设计动机</b>：过滤结果需要在 filter 和 filter 链调用方之间传递，
 * 值对象的不可变性确保跨模块传递时不会被意外修改。</p>
 *
 * @since 1.0
 * @author LyClaw Team
 * @see ContentFilter
 */
public class FilterResult {

    /** 是否通过过滤 */
    private final boolean passed;

    /** 过滤后的内容。通过时为原始/脱敏后内容；拒绝时通常为原始内容 */
    private final String filteredContent;

    /** 拒绝或替换的原因。通过时为 null */
    private final String reason;

    /** 匹配的规则列表。通过时为空列表 */
    private final List<String> matchedRules;

    /**
     * 构造一个过滤结果。
     *
     * @param passed         是否通过
     * @param filteredContent 过滤后的内容
     * @param reason         原因（通过时为 null）
     * @param matchedRules   匹配的规则列表
     */
    public FilterResult(boolean passed, String filteredContent,
                        String reason, List<String> matchedRules) {
        this.passed = passed;
        this.filteredContent = filteredContent;
        this.reason = reason;
        this.matchedRules = matchedRules != null ? matchedRules : Collections.emptyList();
    }

    /**
     * 快速创建"内容通过过滤"结果。matchedRules 为空列表。
     *
     * @param content 原始或脱敏后的内容
     * @return 通过的结果
     */
    public static FilterResult pass(String content) {
        return new FilterResult(true, content, null, Collections.emptyList());
    }

    /**
     * 快速创建"内容被拒绝"结果。matchedRules 需至少包含一条。
     *
     * @param content 被拒绝的原始内容
     * @param reason  拒绝原因
     * @return 拒绝的结果
     */
    public static FilterResult reject(String content, String reason) {
        return new FilterResult(false, content, reason,
                List.of("blocked-by-" + reason));
    }

    /** @return 是否通过过滤 */
    public boolean isPassed() { return passed; }

    /** @return 过滤后的内容 */
    public String getFilteredContent() { return filteredContent; }

    /** @return 拒绝或替换原因（通过时为 null） */
    public String getReason() { return reason; }

    /** @return 匹配的规则列表 */
    public List<String> getMatchedRules() { return matchedRules; }
}