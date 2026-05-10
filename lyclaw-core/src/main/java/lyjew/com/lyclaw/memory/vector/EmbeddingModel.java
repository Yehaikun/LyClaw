package lyjew.com.lyclaw.memory.vector;

import java.util.List;

/**
 * 嵌入模型接口 —— 将文本转换为向量嵌入。
 *
 * <p>支持多后端: local-onnx / OpenAI / DeepSeek / 自定义</p>
 *
 * @since 2.0
 */
public interface EmbeddingModel {

    float[] embed(String text);

    List<float[]> embedBatch(List<String> texts);

    int getDimension();

    String getModelName();
}
