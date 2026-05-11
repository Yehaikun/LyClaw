package lyjew.com.lyclaw.memory.vector;

import lombok.Builder;
import lombok.Data;

import java.util.Map;

/**
 * 向量存储条目，封装一条向量及其关联元数据。
 *
 * 每条向量对应一条记忆内容的嵌入表示，通过 ID 与 {@link lyjew.com.lyclaw.memory.MemoryEntry}
 * 关联。元数据可用于存储原始文本、标签等辅助信息，避免检索后二次查库。
 * 使用 Lombok 自动生成 getter/Builder 等样板方法。
 */
@Data
@Builder
public class VectorEntry {
    /** 向量唯一标识，通常与 MemoryEntry.entryId 一致 */
    private String id;
    /** 浮点向量数组，维度由 {@link EmbeddingModel#getDimension()} 决定 */
    private float[] vector;
    /** 关联元数据，如原始文本摘要、分类标签等 */
    private Map<String, Object> metadata;
}
