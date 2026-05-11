package lyjew.com.lyclaw.task;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * 反思反馈实体，封装反思引擎对单个任务节点的评估与修正建议。
 * 包含质量评分、检测到的错误、建议策略和调整后的提示词。
 */
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
