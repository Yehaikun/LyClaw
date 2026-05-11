package lyjew.com.lyclaw.reflect;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DetectedError {

    public enum ErrorType {
        HALLUCINATION, LOGIC_CONTRADICTION, TOOL_FAILURE_PATTERN,
        INCOMPLETE_OUTPUT, SAFETY_VIOLATION, FORMAT_ERROR
    }

    private ErrorType type;
    private String description;
    private String location;
    private double confidence;
    private String suggestion;
}
