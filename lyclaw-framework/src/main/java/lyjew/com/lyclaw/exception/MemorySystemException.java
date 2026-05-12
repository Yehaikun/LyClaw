package lyjew.com.lyclaw.exception;

/**
 * 记忆系统异常，表示在记忆（Memory）系统的存储或检索过程中发生了错误。
 *
 * <p>携带固定错误码 FW-0050 及 HTTP 500 状态码，通常发生在向量存储读写失败或记忆检索超时时。
 */
public class MemorySystemException extends FrameworkException {

    /** 框架错误码 */
    private static final String CODE = "FW-0050";
    /** 对应的 HTTP 状态码 */
    private static final int HTTP_STATUS = 500;

    /**
     * 构造记忆系统异常。
     *
     * @param message 异常描述信息
     */
    public MemorySystemException(String message) {
        super(CODE, HTTP_STATUS, message);
    }

    /**
     * 构造包含原始异常的记忆系统异常。
     *
     * @param message 异常描述信息
     * @param cause   原始异常
     */
    public MemorySystemException(String message, Throwable cause) {
        super(CODE, HTTP_STATUS, message, cause);
    }
}
