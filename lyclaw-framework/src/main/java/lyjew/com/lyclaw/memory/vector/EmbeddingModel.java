package lyjew.com.lyclaw.memory.vector;

import java.util.List;

/**
 * 嵌入模型接口，将文本转换为固定维度的向量表示。
 *
 * 这是语义检索的基础设施——所有记忆文本通过嵌入模型生成向量后存入向量存储，
 * 检索时计算查询向量与记忆向量的余弦相似度或欧氏距离来进行语义匹配。
 * 实现类封装了对不同嵌入服务（本地模型、远程 API 等）的调用。
 */
public interface EmbeddingModel {

    /**
     * 将单条文本转换为向量嵌入。
     *
     * @param text 待嵌入的文本
     * @return 浮点向量数组，长度等于 {@link #getDimension()}
     */
    float[] embed(String text);

    /**
     * 批量将多条文本转换为向量嵌入。
     *
     * 批量接口通常比逐条调用效率更高（减少网络往返或利用并行计算）。
     *
     * @param texts 待嵌入的文本列表
     * @return 与输入顺序一一对应的向量列表
     */
    List<float[]> embedBatch(List<String> texts);

    /**
     * 获取该嵌入模型输出的向量维度。
     *
     * @return 向量维度（如 OpenAI text-embedding-3-small 为 1536）
     */
    int getDimension();

    /**
     * 获取嵌入模型的名称标识。
     *
     * @return 模型名称（如 "text-embedding-3-small"）
     */
    String getModelName();
}
