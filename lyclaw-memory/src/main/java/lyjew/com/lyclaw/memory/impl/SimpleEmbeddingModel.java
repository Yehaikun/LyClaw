package lyjew.com.lyclaw.memory.impl;

import lombok.extern.slf4j.Slf4j;
import lyjew.com.lyclaw.memory.vector.EmbeddingModel;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;

/**
 * 简易嵌入模型实现，使用确定的 SHA-256 哈希算法生成伪嵌入向量。
 *
 * <p>该类用于在没有外部嵌入服务（如 OpenAI Embeddings API 或本地 SentenceTransformer）
 * 的环境中提供基础的向量表示能力。它将输入文本的 SHA-256 哈希作为种子，
 * 通过正弦函数生成 768 维伪随机向量，确保相同输入始终产生相同的嵌入。</p>
 *
 * <p>工作原理：对输入文本计算 SHA-256 哈希值 → 将哈希字节作为种子
 * → 利用种子和相位偏移通过 sin 函数生成每个维度的值 → L2 归一化。</p>
 *
 * <p>局限性：该嵌入仅基于文本哈希，不包含语义信息。不同文本可能产生
 * 不相关的向量，不适合用于语义相似度计算。仅用于开发和测试环境。</p>
 */
@Slf4j
@Component
public class SimpleEmbeddingModel implements EmbeddingModel {

    /** 嵌入向量维度 */
    private static final int DIMENSION = 768;

    /**
     * 为单条文本生成嵌入向量。
     *
     * @param text 输入文本，空值或空串返回零向量
     * @return 768 维 L2 归一化后的浮点向量
     */
    @Override
    public float[] embed(String text) {
        if (text == null || text.isEmpty()) {
            log.debug("Embedding empty text, returning zero vector");
            return new float[DIMENSION];
        }

        float[] vector = new float[DIMENSION];

        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(text.getBytes(StandardCharsets.UTF_8));

            for (int i = 0; i < DIMENSION; i++) {
                int byteIndex = i % digest.length;
                int seed = ((digest[byteIndex] & 0xFF) << 8)
                         | (digest[(i * 7 + 3) % digest.length] & 0xFF);
                int phase = (digest[(i * 13 + 11) % digest.length] & 0xFF);
                double val = Math.sin(seed * 0.01 + phase * 0.1 + i * 0.001);
                vector[i] = (float) val;
            }

            l2Normalize(vector);

        } catch (NoSuchAlgorithmException e) {
            log.error("SHA-256 not available, falling back to zero vector", e);
        }

        log.debug("Generated embedding of dimension {} for text ({} chars)", DIMENSION, text.length());
        return vector;
    }

    /**
     * 批量生成嵌入向量。
     *
     * @param texts 文本列表
     * @return 对应的嵌入向量列表，输入为空时返回空列表
     */
    @Override
    public List<float[]> embedBatch(List<String> texts) {
        if (texts == null || texts.isEmpty()) return List.of();
        List<float[]> result = new ArrayList<>(texts.size());
        for (String text : texts) result.add(embed(text));
        log.debug("Batch embedded {} texts", texts.size());
        return result;
    }

    /** @return 嵌入向量的维度，固定为 768 */
    @Override
    public int getDimension() { return DIMENSION; }

    /** @return 模型名称标识符 */
    @Override
    public String getModelName() { return "local-hash-v1"; }

    /**
     * 对向量执行 L2 归一化，使其模长为 1。
     * 当向量范数接近零时（小于 1e-12），跳过归一化以避免除零错误。
     */
    private void l2Normalize(float[] vector) {
        double norm = 0.0;
        for (float v : vector) norm += (double) v * v;
        norm = Math.sqrt(norm);
        if (norm > 1e-12) {
            for (int i = 0; i < vector.length; i++) {
                vector[i] = (float) (vector[i] / norm);
            }
        }
    }
}
