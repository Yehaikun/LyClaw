package lyjew.com.lyclaw.memory.vector;

import lombok.Builder;
import lombok.Data;

import java.util.Map;

/**
 * 向量检索结果条目，封装一次向量搜索的单条命中记录。
 *
 * 包含匹配条目的 ID、相似度评分和关联元数据。
 * 这是向量存储层的原始输出，需进一步通过 ID 关联回
 * {@link lyjew.com.lyclaw.memory.MemoryEntry} 获取完整信息。
 * 使用 Lombok 自动生成 getter/Builder 等样板方法。
 */
@Data
@Builder
public class VectorSearchResult {
    /** 匹配条目的唯一标识 */
    private String id;
    /** 相似度评分（余弦相似度或归一化距离），值越高越相似 */
    private double score;
    /** 关联元数据，直接从向量存储返回避免二次查询 */
    private Map<String, Object> metadata;
}
