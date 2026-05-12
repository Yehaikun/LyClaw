package lyjew.com.lyclaw.exception;

/**
 * 配置校验异常，表示框架配置在加载或校验阶段发现不合规的设置。
 *
 * <p>携带固定错误码 FW-0110 及 HTTP 400 状态码，属于客户端错误，提示修正配置后重试。
 */
public class ConfigurationValidationException extends FrameworkException {

    /** 框架错误码 */
    private static final String CODE = "FW-0110";
    /** 对应的 HTTP 状态码 */
    private static final int HTTP_STATUS = 400;

    /**
     * 构造配置校验异常。
     *
     * @param message 异常描述信息
     */
    public ConfigurationValidationException(String message) {
        super(CODE, HTTP_STATUS, message);
    }

    /**
     * 构造包含原始异常的配置校验异常。
     *
     * @param message 异常描述信息
     * @param cause   原始异常
     */
    public ConfigurationValidationException(String message, Throwable cause) {
        super(CODE, HTTP_STATUS, message, cause);
    }
}
