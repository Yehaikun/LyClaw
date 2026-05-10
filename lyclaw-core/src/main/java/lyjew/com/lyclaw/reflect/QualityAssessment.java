package lyjew.com.lyclaw.reflect;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class QualityAssessment {

    /** 准确性分数 [0.0, 1.0] */
    private double accuracy;
    /** 完整性分数 [0.0, 1.0] */
    private double completeness;
    /** 安全性分数 [0.0, 1.0] */
    private double safety;
    /** 用户体验分数 [0.0, 1.0] */
    private double userExperience;
    /** 综合评分 */
    private double overall;
}
