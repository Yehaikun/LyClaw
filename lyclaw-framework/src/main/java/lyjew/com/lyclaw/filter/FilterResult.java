package lyjew.com.lyclaw.filter;

import java.util.Collections;
import java.util.List;

public class FilterResult {

    private final boolean passed;
    private final String filteredContent;
    private final String reason;
    private final List<String> matchedRules;

    public FilterResult(boolean passed, String filteredContent,
                        String reason, List<String> matchedRules) {
        this.passed = passed;
        this.filteredContent = filteredContent;
        this.reason = reason;
        this.matchedRules = matchedRules != null ? matchedRules : Collections.emptyList();
    }

    public static FilterResult pass(String content) {
        return new FilterResult(true, content, null, Collections.emptyList());
    }

    public static FilterResult reject(String content, String reason) {
        return new FilterResult(false, content, reason,
                List.of("blocked-by-" + reason));
    }

    public boolean isPassed() { return passed; }

    public String getFilteredContent() { return filteredContent; }

    public String getReason() { return reason; }

    public List<String> getMatchedRules() { return matchedRules; }
}
