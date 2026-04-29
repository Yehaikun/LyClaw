package lyjew.com.lyclaw.retrieval;

import java.util.Map;

/**
 * 向量搜索结果值对象 —— VectorStore.search() 的返回值。
 *
 * <p>包含匹配的记录ID、相似度分数、匹配内容和关联元数据。</p>
 *
 * @since 1.0
 * @author LyClaw Team
 * @see VectorStore
 */
public class SearchResult {

    /** 匹配的记录ID */
    private final String id;

    /** 相似度分数（0.0 ~ 1.0，越高越相似） */
    private final double score;

    /** 匹配的原始内容 */
    private final String content;

    /** 关联的元数据 */
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