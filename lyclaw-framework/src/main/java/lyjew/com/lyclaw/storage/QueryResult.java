package lyjew.com.lyclaw.storage;

import java.util.List;
import java.util.Map;

/**
 * 查询结果封装。
 *
 * <p>包含查询结果列表，支持单路或多路融合后的结果。
 * 同时返回实际使用的查询路径和执行耗时。</p>
 */
public class QueryResult {

    private final List<QueryResultItem> items;
    private final List<QueryPath> pathsUsed;
    private final long elapsedMs;

    public QueryResult(List<QueryResultItem> items, List<QueryPath> pathsUsed, long elapsedMs) {
        this.items = items;
        this.pathsUsed = pathsUsed;
        this.elapsedMs = elapsedMs;
    }

    public List<QueryResultItem> getItems() { return items; }
    public List<QueryPath> getPathsUsed() { return pathsUsed; }
    public long getElapsedMs() { return elapsedMs; }
    public int getTotalHits() { return items != null ? items.size() : 0; }

    /** 单条查询结果项 */
    public record QueryResultItem(
            String id,
            double score,
            String content,
            Map<String, Object> metadata,
            QueryPath sourcePath) {}
}
