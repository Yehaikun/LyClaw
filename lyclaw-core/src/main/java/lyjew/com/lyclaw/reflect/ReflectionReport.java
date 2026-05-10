package lyjew.com.lyclaw.reflect;

import lombok.Builder;
import lombok.Data;
import java.util.List;

@Data
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
