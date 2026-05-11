package lyjew.com.lyclaw.reflect;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ReflectionReport {
    private String reflectionId;
    private String sessionId;
    private QualityAssessment quality;
    private List<DetectedError> errors;
    private StrategyAdjustment suggestion;
    private double overallScore;
    private long timestamp;
}
