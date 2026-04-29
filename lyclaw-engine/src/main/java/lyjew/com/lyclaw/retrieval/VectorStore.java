package lyjew.com.lyclaw.retrieval;

import java.util.List;
import java.util.Map;

/**
 * 向量检索存储接口 —— 支持向量嵌入存储、相似度搜索和元数据过滤。
 *
 * <p>VectorStore 为 MemoryManager 的语义检索提供底层存储支持。
 * 将记忆内容生成向量嵌入后存入，搜索时根据查询向量返回最相似的 topK 条记录。</p>
 *
 * @since 1.0
 * @author LyClaw Team
 */
public interface VectorStore {

    void store(String id, List<Float> vector, Map<String, Object> metadata);

    List<SearchResult> search(List<Float> queryVector, int topK);

    void delete(String id);

    String getCollectionName();
}