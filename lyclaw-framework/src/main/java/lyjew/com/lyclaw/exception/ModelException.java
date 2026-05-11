package lyjew.com.lyclaw.exception;

import lyjew.com.lyclaw.base.exception.LyClawException;
import lyjew.com.lyclaw.enums.ErrorCode;

/**
 * 模型调用异常，封装 LLM API 调用失败时的错误信息。
 *
 * <p>支持携带原始响应体（{@code rawResponse}），便于调试 API 返回的错误详情。
 * HTTP 状态码从 {@link ErrorCode} 枚举中提取，支持自定义状态码。</p>
 */
public class ModelException extends LyClawException {

    /** LLM API 返回的原始响应体，用于错误排查 */
    private final String rawResponse;

    /**
     * @param code       错误码
     * @param httpStatus HTTP 状态码
     * @param message    错误描述
     */
    public ModelException(String code, int httpStatus, String message) {
        super(code, httpStatus, message);
        this.rawResponse = null;
    }

    /**
     * 构造包含原始响应的模型异常。
     *
     * @param rawResponse API 返回的原始响应体
     */
    public ModelException(String code, int httpStatus, String message, String rawResponse) {
        super(code, httpStatus, message);
        this.rawResponse = rawResponse;
    }

    /**
     * 构造包含原始原因的模型异常。
     */
    public ModelException(String code, int httpStatus, String message, Throwable cause) {
        super(code, httpStatus, message, cause);
        this.rawResponse = null;
    }

    /** @return LLM API 返回的原始响应体 */
    public String getRawResponse() { return rawResponse; }

    /** 从错误码枚举快速创建异常。 */
    public static ModelException of(ErrorCode errorCode) {
        return new ModelException(errorCode.code(), errorCode.httpStatus(), errorCode.defaultMessage());
    }

    /** 从错误码枚举创建异常，附加详细描述。 */
    public static ModelException of(ErrorCode errorCode, String detail) {
        return new ModelException(errorCode.code(), errorCode.httpStatus(),
                errorCode.defaultMessage() + "：" + detail);
    }

    /** 从错误码枚举创建异常，附加原始异常。 */
    public static ModelException of(ErrorCode errorCode, Throwable cause) {
        return new ModelException(errorCode.code(), errorCode.httpStatus(),
                errorCode.defaultMessage(), cause);
    }

    /** 创建携带原始 API 响应的模型异常。 */
    public static ModelException withRawResponse(int httpStatus, String message, String rawResponse) {
        return new ModelException("MODEL_API_ERROR", httpStatus, message, rawResponse);
    }
}
