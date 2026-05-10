package lyjew.com.lyclaw.memory;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MemoryEntry {

    private String entryId;
    private String userId;
    private String sessionId;
    private MemoryLayerType layer;
    private String content;
    private String summary;
    private float[] embedding;
    private MemoryCategory category;
    private double importance;
    private int accessCount;
    private TemporalProps temporal;
    private List<String> tags;
    private Map<String, Object> metadata;

    public void incrementAccess() {
        this.accessCount++;
    }

    public double computeRelevanceScore(double alpha, double beta, double gamma, double delta) {
        return alpha * importance + beta * normalizeAccessCount() + gamma * temporal.computeDecay() + delta;
    }

    private double normalizeAccessCount() {
        return Math.min(1.0, accessCount / 100.0);
    }
}
