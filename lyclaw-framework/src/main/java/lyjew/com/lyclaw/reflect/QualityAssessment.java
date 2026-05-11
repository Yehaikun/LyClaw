package lyjew.com.lyclaw.reflect;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 质量评估结果实体，包含多个维度的评分与综合总分。
 * 维度包括：准确性、完整性、安全性、用户体验。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class QualityAssessment {
    private double accuracy;
    private double completeness;
    private double safety;
    private double userExperience;
    private double overall;
}
