package lyjew.com.lyclaw.reflect;

import java.util.List;

/**
 * 错误检测器 —— 检测 AI 输出中的各类错误。
 *
 * @since 2.0
 */
public interface ErrorDetector {

    List<DetectedError> detectHallucination(String output, List<String> knownFacts);

    List<DetectedError> detectLogicContradiction(String output);

    List<DetectedError> detectToolFailurePattern(List<ToolCallRecord> history);
}
