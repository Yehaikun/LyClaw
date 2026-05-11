package lyjew.com.lyclaw.reflect;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 反思报告实体，汇总反思引擎的一次完整评估结果。
 * 包含反思标识、会话ID、质量评估、错误列表、策略建议、综合评分和时间戳。
 */
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
