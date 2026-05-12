package lyjew.com.lyclaw.exception;

/**
 * 沙箱违规异常，当工具执行违反了沙箱安全策略时抛出。
 *
 * <p>错误码 FW-0090，HTTP 状态码 403（禁止访问）。
 * 例如工具试图越权写入只读文件系统、访问受限网络等场景。</p>
 */
public class SandboxViolationException extends FrameworkException {

    /** 错误码 */
    private static final String CODE = "FW-0090";
    /** 对应 HTTP 403 禁止访问 */
    private static final int HTTP_STATUS = 403;

    /**
     * @param message 异常描述信息
     */
    public SandboxViolationException(String message) {
        super(CODE, HTTP_STATUS, message);
    }

    /**
     * @param message 异常描述信息
     * @param cause   原始异常
     */
    public SandboxViolationException(String message, Throwable cause) {
        super(CODE, HTTP_STATUS, message, cause);
    }
}
