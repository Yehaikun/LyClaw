package lyjew.com.lyclaw.memory;

import java.util.List;

public class MemoryContent {

    private final String content;
    private final String title;
    private final boolean enabled;
    private final List<String> tags;
    private final double relevanceScore;

    public MemoryContent(String content, String title, boolean enabled,
                         List<String> tags, double relevanceScore) {
        this.content = content;
        this.title = title;
        this.enabled = enabled;
        this.tags = tags;
        this.relevanceScore = relevanceScore;
    }

    public String getContent() { return content; }

    public String getTitle() { return title; }

    public boolean isEnabled() { return enabled; }

    public List<String> getTags() { return tags; }

    public double getRelevanceScore() { return relevanceScore; }

    public static MemoryContent empty() {
        return new MemoryContent("", "", true, List.of(), 0.0);
    }
}
