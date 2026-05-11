package lyjew.com.lyclaw.retrieval;

import java.util.List;
import java.util.Map;

/**
 * 向量存储接口，定义向量嵌入的存储与相似度检索操作。
 *
 * <p>实现类可基于内存、磁盘或外部向量数据库（如 Milvus、Pinecone）提供存储服务。</p>
 */
public interface VectorStore {

    /**
     * 存储一个向量及其元数据。
     *
     * @param id       唯一标识
     * @param vector   向量值列表
     * @param metadata 附加元数据
     */
    void store(String id, List<Float> vector, Map<String, Object> metadata);

    /**
     * 按查询向量检索 Top-K 最相似结果。
     *
     * @param queryVector 查询向量
     * @param topK        返回数量
     * @return 按相似度降序排列的搜索结果
     */
    List<SearchResult> search(List<Float> queryVector, int topK);

    /** 删除指定 ID 的向量。 */
    void delete(String id);

    /** @return 当前集合名称 */
    String getCollectionName();
}
