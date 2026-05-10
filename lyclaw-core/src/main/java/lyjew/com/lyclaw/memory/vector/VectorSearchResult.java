package lyjew.com.lyclaw.memory.vector;

import lombok.Builder;
import lombok.Data;

import java.util.Map;

@Data
@Builder
public class VectorSearchResult {
    private String id;
    private double score;
    private Map<String, Object> metadata;
}
