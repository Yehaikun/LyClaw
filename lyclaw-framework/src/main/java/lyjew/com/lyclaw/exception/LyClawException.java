package lyjew.com.lyclaw.exception;

import lombok.Getter;

/**
 * LyClaw 基础异常类，所有 LyClaw 体系异常的顶层父类。
 *
 * <p>封装了错误码（code）与 HTTP 状态码（httpStatus），提供统一的异常信息结构。
 * 通过静态工厂方法{@link #internal}可快捷创建 HTTP 500 的内部错误异常。
 * 子类{@link lyjew.com.lyclaw.exception.ModelException}等在此基础上增加了扩展能力。</p>
 */
@Getter
public class LyClawException extends RuntimeException {

    /** 错误码 */
    private final String code;
    /** HTTP 状态码 */
    private final int httpStatus;

    /**
     * 构造 LyClaw 基础异常。
     *
     * @param code       错误码
     * @param httpStatus HTTP 状态码
     * @param message    异常描述信息
     */
    public LyClawException(String code, int httpStatus, String message) {
        super(message);
        this.code = code;
        this.httpStatus = httpStatus;
    }

    /**
     * 构造包含原始异常的 LyClaw 基础异常。
     *
     * @param code       错误码
     * @param httpStatus HTTP 状态码
     * @param message    异常描述信息
     * @param cause      原始异常
     */
    public LyClawException(String code, int httpStatus, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
        this.httpStatus = httpStatus;
    }

    /**
     * 快捷创建 HTTP 500 内部错误异常的工厂方法。
     *
     * @param code    错误码
     * @param message 异常描述信息
     * @return 内部错误异常实例
     */
    public static LyClawException internal(String code, String message) {
        return new LyClawException(code, 500, message);
    }

    /**
     * 快捷创建包含原始异常的 HTTP 500 内部错误异常的工厂方法。
     *
     * @param code    错误码
     * @param message 异常描述信息
     * @param cause   原始异常
     * @return 内部错误异常实例
     */
    public static LyClawException internal(String code, String message, Throwable cause) {
        return new LyClawException(code, 500, message, cause);
    }
}
