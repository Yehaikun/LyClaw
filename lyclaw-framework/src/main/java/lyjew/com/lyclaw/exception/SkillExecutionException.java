package lyjew.com.lyclaw.exception;

/**
 * 技能执行异常，表示在技能（Skill）执行过程中发生了不可恢复的错误。
 *
 * <p>携带固定错误码 FW-0070 及 HTTP 500 状态码。该异常通常由{@link lyjew.com.lyclaw.skill.SkillExecutor}
 * 在执行技能逻辑时抛出，用于向调用方传达服务端内部错误。与框架中的其他异常体系一致，
 * 支持通过{@link #withDetail(String, Object)}附加上下文信息。</p>
 */
public class SkillExecutionException extends FrameworkException {

    /** 框架错误码 */
    private static final String CODE = "FW-0070";
    /** 对应的 HTTP 状态码 */
    private static final int HTTP_STATUS = 500;

    /**
     * 构造技能执行异常。
     *
     * @param message 异常描述信息
     */
    public SkillExecutionException(String message) {
        super(CODE, HTTP_STATUS, message);
    }

    /**
     * 构造包含原始异常的技能执行异常。
     *
     * @param message 异常描述信息
     * @param cause   原始异常
     */
    public SkillExecutionException(String message, Throwable cause) {
        super(CODE, HTTP_STATUS, message, cause);
    }
}
