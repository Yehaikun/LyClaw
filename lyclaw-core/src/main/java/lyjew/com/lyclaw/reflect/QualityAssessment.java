package lyjew.com.lyclaw.reflect;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

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
