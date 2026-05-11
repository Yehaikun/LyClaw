package lyjew.com.lyclaw.filter;

import java.util.Collections;
import java.util.List;

/**
 * 内容过滤结果，封装过滤器的执行结果。
 *
 * <p>包含通过/拒绝状态、过滤后内容、拒绝原因及匹配的规则列表。
 * 提供静态工厂方法快速创建通过或拒绝的实例。</p>
 */
public class FilterResult {

    /** 是否通过过滤 */
    private final boolean passed;
    /** 过滤后的内容（通过时可能经过脱敏处理） */
    private final String filteredContent;
    /** 拒绝原因（通过时为 null） */
    private final String reason;
    /** 匹配的规则列表 */
    private final List<String> matchedRules;

    public FilterResult(boolean passed, String filteredContent,
                        String reason, List<String> matchedRules) {
        this.passed = passed;
        this.filteredContent = filteredContent;
        this.reason = reason;
        this.matchedRules = matchedRules != null ? matchedRules : Collections.emptyList();
    }

    /** 创建通过结果。 */
    public static FilterResult pass(String content) {
        return new FilterResult(true, content, null, Collections.emptyList());
    }

    /** 创建拒绝结果。 */
    public static FilterResult reject(String content, String reason) {
        return new FilterResult(false, content, reason,
                List.of("blocked-by-" + reason));
    }

    public boolean isPassed() { return passed; }
    public String getFilteredContent() { return filteredContent; }
    public String getReason() { return reason; }
    public List<String> getMatchedRules() { return matchedRules; }
}
