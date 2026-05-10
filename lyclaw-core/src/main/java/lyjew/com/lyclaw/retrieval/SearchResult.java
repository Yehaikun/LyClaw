package lyjew.com.lyclaw.retrieval;

import java.util.Map;

public class SearchResult {

    private final String id;
    private final double score;
    private final String content;
    private final Map<String, Object> metadata;

    public SearchResult(String id, double score, String content,
                        Map<String, Object> metadata) {
        this.id = id;
        this.score = score;
        this.content = content;
        this.metadata = metadata;
    }

    public String getId() { return id; }

    public double getScore() { return score; }

    public String getContent() { return content; }

    public Map<String, Object> getMetadata() { return metadata; }
}
