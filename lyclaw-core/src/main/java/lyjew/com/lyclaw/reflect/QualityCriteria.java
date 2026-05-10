package lyjew.com.lyclaw.reflect;

import lombok.Builder;
import lombok.Data;

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
