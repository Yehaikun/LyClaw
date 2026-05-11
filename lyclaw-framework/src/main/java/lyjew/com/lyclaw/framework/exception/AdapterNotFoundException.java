package lyjew.com.lyclaw.framework.exception;

/**
 * 适配器未找到异常，表示请求的适配器（Adapter）在系统中未注册或不可用。
 *
 * <p>携带固定错误码 FW-0010 及 HTTP 500 状态码。当框架尝试通过适配器名称
 * 查找对应的 LLM 提供商适配器（如 OpenAI、Claude 等）但未找到时抛出，
 * 提示检查适配器配置与注册状态。</p>
 */
public class AdapterNotFoundException extends FrameworkException {

    /** 框架错误码 */
    private static final String CODE = "FW-0010";
    /** 对应的 HTTP 状态码 */
    private static final int HTTP_STATUS = 500;

    /**
     * 构造适配器未找到异常。
     *
     * @param message 异常描述信息
     */
    public AdapterNotFoundException(String message) {
        super(CODE, HTTP_STATUS, message);
    }

    /**
     * 构造包含原始异常的适配器未找到异常。
     *
     * @param message 异常描述信息
     * @param cause   原始异常
     */
    public AdapterNotFoundException(String message, Throwable cause) {
        super(CODE, HTTP_STATUS, message, cause);
    }
}
