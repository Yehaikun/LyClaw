package lyjew.com.lyclaw.reflect;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 检测到的错误实体，用于反思引擎记录模型输出中的各类问题。
 * 包含错误类型、描述、位置、置信度与修正建议。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DetectedError {

    /**
     * 错误类型枚举，覆盖模型输出的常见质量问题。
     * HALLUCINATION - 幻觉，生成内容与事实不符
     * LOGIC_CONTRADICTION - 逻辑矛盾，输出内容自相矛盾
     * TOOL_FAILURE_PATTERN - 工具调用失败模式，工具使用出现异常
     * INCOMPLETE_OUTPUT - 输出不完整，内容被截断或遗漏
     * SAFETY_VIOLATION - 安全违规，输出包含不安全内容
     * FORMAT_ERROR - 格式错误，输出不符合预期格式
     */
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
