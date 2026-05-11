package lyjew.com.lyclaw.reflect;

import java.util.List;

/**
 * 错误检测器接口，提供多种维度的错误检测能力。
 * 包括幻觉检测、逻辑矛盾检测以及工具调用失败模式检测。
 */
public interface ErrorDetector {

    /**
     * 检测模型输出中的幻觉问题：将输出内容与已知事实比对，识别虚构或错误内容。
     *
     * @param output     模型生成的输出文本
     * @param knownFacts 已知事实列表，作为校验基准
     * @return 检测到的幻觉错误列表
     */
    List<DetectedError> detectHallucination(String output, List<String> knownFacts);

    /**
     * 检测模型输出中的逻辑矛盾：分析文本内部的逻辑一致性。
     *
     * @param output 模型生成的输出文本
     * @return 检测到的逻辑矛盾错误列表
     */
    List<DetectedError> detectLogicContradiction(String output);

    /**
     * 检测工具调用的失败模式：分析历史工具调用记录，识别重复失败、参数错误等模式。
     *
     * @param history 工具调用的历史记录列表
     * @return 检测到的工具失败模式错误列表
     */
    List<DetectedError> detectToolFailurePattern(List<ToolCallRecord> history);
}
