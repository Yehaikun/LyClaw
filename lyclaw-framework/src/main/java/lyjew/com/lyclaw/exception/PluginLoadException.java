package lyjew.com.lyclaw.exception;

/**
 * 插件加载异常，表示在加载、初始化或发现插件（Plugin）时发生了错误。
 *
 * <p>携带固定错误码 FW-0030 及 HTTP 500 状态码，通常发生在插件类找不到、依赖缺失或初始化失败时。
 */
public class PluginLoadException extends FrameworkException {

    /** 框架错误码 */
    private static final String CODE = "FW-0030";
    /** 对应的 HTTP 状态码 */
    private static final int HTTP_STATUS = 500;

    /**
     * 构造插件加载异常。
     *
     * @param message 异常描述信息
     */
    public PluginLoadException(String message) {
        super(CODE, HTTP_STATUS, message);
    }

    /**
     * 构造包含原始异常的插件加载异常。
     *
     * @param message 异常描述信息
     * @param cause   原始异常
     */
    public PluginLoadException(String message, Throwable cause) {
        super(CODE, HTTP_STATUS, message, cause);
    }
}
