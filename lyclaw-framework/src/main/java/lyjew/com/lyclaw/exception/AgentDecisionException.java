package lyjew.com.lyclaw.exception;

/**
 * 代理决策异常，表示 Agent 在进行决策推理时发生了错误。
 *
 * <p>携带固定错误码 FW-0060 及 HTTP 500 状态码。通常发生在 LLM 调用失败、
 * 返回结果无法解析或决策逻辑出现矛盾时，由 Agent 决策引擎抛出。</p>
 */
public class AgentDecisionException extends FrameworkException {

    /** 框架错误码 */
    private static final String CODE = "FW-0060";
    /** 对应的 HTTP 状态码 */
    private static final int HTTP_STATUS = 500;

    /**
     * 构造代理决策异常。
     *
     * @param message 异常描述信息
     */
    public AgentDecisionException(String message) {
        super(CODE, HTTP_STATUS, message);
    }

    /**
     * 构造包含原始异常的代理决策异常。
     *
     * @param message 异常描述信息
     * @param cause   原始异常
     */
    public AgentDecisionException(String message, Throwable cause) {
        super(CODE, HTTP_STATUS, message, cause);
    }
}
