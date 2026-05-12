package lyjew.com.lyclaw.exception;

/**
 * 流水线阶段执行异常，当流水线中某个阶段执行失败时抛出。
 *
 * <p>错误码 FW-0020，HTTP 状态码 500。通常在阶段处理逻辑抛出未捕获异常时产生。</p>
 */
public class StageExecutionException extends FrameworkException {

    /** 错误码 */
    private static final String CODE = "FW-0020";
    /** 对应 HTTP 500 服务器内部错误 */
    private static final int HTTP_STATUS = 500;

    /**
     * @param message 异常描述信息
     */
    public StageExecutionException(String message) {
        super(CODE, HTTP_STATUS, message);
    }

    /**
     * @param message 异常描述信息
     * @param cause   原始异常
     */
    public StageExecutionException(String message, Throwable cause) {
        super(CODE, HTTP_STATUS, message, cause);
    }
}
