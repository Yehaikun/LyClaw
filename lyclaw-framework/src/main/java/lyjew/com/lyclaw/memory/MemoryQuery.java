package lyjew.com.lyclaw.memory;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MemoryQuery {

    private String queryText;
    private float[] queryEmbedding;
    private int topK = 20;
    private double alpha = 0.45;
    private double beta = 0.20;
    private double gamma = 0.15;
    private double delta = 0.20;
    private List<MemoryLayerType> layerFilter;
    private List<MemoryCategory> categoryFilter;
    private List<String> tagFilter;
    private Map<String, Object> metadataFilter;
}
