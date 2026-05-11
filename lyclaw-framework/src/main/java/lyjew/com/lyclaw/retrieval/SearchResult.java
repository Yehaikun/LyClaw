package lyjew.com.lyclaw.retrieval;

import java.util.Map;

/**
 * 向量搜索单条结果，包含匹配 ID、相似度分数、内容和元数据。
 *
 * <p>不可变对象，通常由 {@link VectorStore#search} 返回。</p>
 */
public class SearchResult {

    /** 匹配结果的唯一标识 */
    private final String id;
    /** 相似度分数 [0.0, 1.0]，越高越相似 */
    private final double score;
    /** 匹配内容的文本表示 */
    private final String content;
    /** 附加元数据 */
    private final Map<String, Object> metadata;

    /**
     * 构造一条搜索结果。
     *
     * @param id       匹配结果的唯一标识
     * @param score    相似度分数，范围 [0.0, 1.0]
     * @param content  匹配内容的文本表示
     * @param metadata 附加元数据
     */
    public SearchResult(String id, double score, String content,
                        Map<String, Object> metadata) {
        this.id = id;
        this.score = score;
        this.content = content;
        this.metadata = metadata;
    }

    /** @return 匹配结果的唯一标识 */
    public String getId() { return id; }

    /** @return 相似度分数 */
    public double getScore() { return score; }

    /** @return 匹配内容的文本表示 */
    public String getContent() { return content; }

    /** @return 附加元数据 */
    public Map<String, Object> getMetadata() { return metadata; }
}
