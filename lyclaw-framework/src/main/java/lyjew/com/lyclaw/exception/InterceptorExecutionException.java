package lyjew.com.lyclaw.exception;

/**
 * 拦截器执行异常，表示在执行拦截器（Interceptor）链时发生了错误。
 *
 * <p>携带固定错误码 FW-0100 及 HTTP 500 状态码，用于拦截器前置/后置处理中的异常传播。
 */
public class InterceptorExecutionException extends FrameworkException {

    /** 框架错误码 */
    private static final String CODE = "FW-0100";
    /** 对应的 HTTP 状态码 */
    private static final int HTTP_STATUS = 500;

    /**
     * 构造拦截器执行异常。
     *
     * @param message 异常描述信息
     */
    public InterceptorExecutionException(String message) {
        super(CODE, HTTP_STATUS, message);
    }

    /**
     * 构造包含原始异常的拦截器执行异常。
     *
     * @param message 异常描述信息
     * @param cause   原始异常
     */
    public InterceptorExecutionException(String message, Throwable cause) {
        super(CODE, HTTP_STATUS, message, cause);
    }
}
