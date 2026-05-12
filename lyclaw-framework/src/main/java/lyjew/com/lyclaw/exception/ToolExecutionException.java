package lyjew.com.lyclaw.exception;

/**
 * 工具执行异常，表示在执行工具（Tool）的过程中发生了运行时错误。
 *
 * <p>携带固定错误码 FW-0001 及 HTTP 500 状态码，是框架中最基础的工具级异常。
 */
public class ToolExecutionException extends FrameworkException {

    /** 框架错误码 */
    private static final String CODE = "FW-0001";
    /** 对应的 HTTP 状态码 */
    private static final int HTTP_STATUS = 500;

    /**
     * 构造工具执行异常。
     *
     * @param message 异常描述信息
     */
    public ToolExecutionException(String message) {
        super(CODE, HTTP_STATUS, message);
    }

    /**
     * 构造包含原始异常的工具执行异常。
     *
     * @param message 异常描述信息
     * @param cause   原始异常
     */
    public ToolExecutionException(String message, Throwable cause) {
        super(CODE, HTTP_STATUS, message, cause);
    }
}
