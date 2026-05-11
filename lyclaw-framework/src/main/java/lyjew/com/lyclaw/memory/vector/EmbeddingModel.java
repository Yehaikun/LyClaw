package lyjew.com.lyclaw.memory.vector;

import java.util.List;

public interface EmbeddingModel {

    float[] embed(String text);
    List<float[]> embedBatch(List<String> texts);
    int getDimension();
    String getModelName();
}
