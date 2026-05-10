package lyjew.com.lyclaw.reflect;

import java.util.List;

public interface ErrorDetector {

    List<DetectedError> detectHallucination(String output, List<String> knownFacts);
    List<DetectedError> detectLogicContradiction(String output);
    List<DetectedError> detectToolFailurePattern(List<ToolCallRecord> history);
}
