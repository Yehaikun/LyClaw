package lyjew.com.lyclaw.exception;

/**
 * 适配器配置异常，当适配器配置不正确或关键配置项缺失时抛出。
 *
 * <p>错误码 FW-0011，HTTP 状态码 400（客户端请求错误）。
 * 通常在 API Key 缺失、Base URL 格式错误、模型名称无效等场景下抛出。</p>
 */
public class AdapterConfigurationException extends FrameworkException {

    /** 错误码 */
    private static final String CODE = "FW-0011";
    /** 对应 HTTP 400 客户端错误 */
    private static final int HTTP_STATUS = 400;

    /**
     * @param message 异常描述信息
     */
    public AdapterConfigurationException(String message) {
        super(CODE, HTTP_STATUS, message);
    }

    /**
     * @param message 异常描述信息
     * @param cause   原始异常
     */
    public AdapterConfigurationException(String message, Throwable cause) {
        super(CODE, HTTP_STATUS, message, cause);
    }
}
