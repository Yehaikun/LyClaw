package lyjew.com.lyclaw.exception;

/**
 * 工具未找到异常，表示请求的工具在注册中心中不存在或未注册。
 *
 * <p>携带固定错误码 FW-0002 及 HTTP 404 状态码。当 Agent 尝试调用一个
 * 未在工具注册表中注册的工具名称时，框架会抛出此异常，提示调用方检查
 * 工具名称拼写或确认该工具是否已正确注册。</p>
 */
public class ToolNotFoundException extends FrameworkException {

    /** 框架错误码 */
    private static final String CODE = "FW-0002";
    /** 对应的 HTTP 状态码 */
    private static final int HTTP_STATUS = 404;

    /**
     * 构造工具未找到异常。
     *
     * @param message 异常描述信息
     */
    public ToolNotFoundException(String message) {
        super(CODE, HTTP_STATUS, message);
    }

    /**
     * 构造包含原始异常的工具未找到异常。
     *
     * @param message 异常描述信息
     * @param cause   原始异常
     */
    public ToolNotFoundException(String message, Throwable cause) {
        super(CODE, HTTP_STATUS, message, cause);
    }
}
