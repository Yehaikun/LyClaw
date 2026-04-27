package lyjew.com.lyclaw.base.exception;

import lombok.Getter;

/**
 * 所有业务异常的基类——带错误码、HTTP 状态码、消息
 */
@Getter
public class LyClawException extends RuntimeException {

    /** 错误码 */
    private final String code;

    /** HTTP 状态码 */
    private final int httpStatus;

    public LyClawException(String code, int httpStatus, String message) {
        super(message);
        this.code = code;
        this.httpStatus = httpStatus;
    }

    public LyClawException(String code, int httpStatus, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
        this.httpStatus = httpStatus;
    }

    // ========== 便捷构造方法 ==========

    /** 默认 500 的内部错误 */
    public static LyClawException internal(String code, String message) {
        return new LyClawException(code, 500, message);
    }

    /** 有原因的 500 内部错误 */
    public static LyClawException internal(String code, String message, Throwable cause) {
        return new LyClawException(code, 500, message, cause);
    }
}