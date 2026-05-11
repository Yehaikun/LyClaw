package lyjew.com.lyclaw.reflect;

import lombok.Builder;
import lombok.Data;

/**
 * 质量评估标准配置，定义评估任务的描述、期望输出以及需要检查的维度开关。
 * 各维度按需开启，以控制评估的范围和计算开销。
 */
@Data
@Builder
public class QualityCriteria {
    private String taskDescription;
    private String expectedOutput;
    private boolean checkAccuracy;
    private boolean checkCompleteness;
    private boolean checkSafety;
    private boolean checkUserExperience;
}
