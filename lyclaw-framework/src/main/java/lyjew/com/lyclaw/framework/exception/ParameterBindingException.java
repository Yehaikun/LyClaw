package lyjew.com.lyclaw.framework.exception;

/**
 * 参数绑定异常，表示在将输入参数绑定到工具或技能时发生了类型不匹配或缺失等错误。
 *
 * <p>携带固定错误码 FW-0040 及 HTTP 400 状态码，属于客户端请求参数错误。
 */
public class ParameterBindingException extends FrameworkException {

    /** 框架错误码 */
    private static final String CODE = "FW-0040";
    /** 对应的 HTTP 状态码 */
    private static final int HTTP_STATUS = 400;

    /**
     * 构造参数绑定异常。
     *
     * @param message 异常描述信息
     */
    public ParameterBindingException(String message) {
        super(CODE, HTTP_STATUS, message);
    }

    /**
     * 构造包含原始异常的参数绑定异常。
     *
     * @param message 异常描述信息
     * @param cause   原始异常
     */
    public ParameterBindingException(String message, Throwable cause) {
        super(CODE, HTTP_STATUS, message, cause);
    }
}
