package lyjew.com.lyclaw.task;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class ReflectionFeedback {
    private String reportId;
    private String nodeId;
    private double qualityScore;
    private List<String> detectedErrors;
    private String suggestedStrategy;
    private String adjustedPrompt;
}
