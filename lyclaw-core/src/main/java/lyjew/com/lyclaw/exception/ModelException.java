package lyjew.com.lyclaw.exception;

import lyjew.com.lyclaw.base.exception.LyClawException;
import lyjew.com.lyclaw.enums.ErrorCode;

/**
 * 模型调用异常
 */
public class ModelException extends LyClawException {

    /** 厂商返回的原始错误信息（可用于调试） */
    private final String rawResponse;

    public ModelException(String code, int httpStatus, String message) {
        super(code, httpStatus, message);
        this.rawResponse = null;
    }

    public ModelException(String code, int httpStatus, String message, String rawResponse) {
        super(code, httpStatus, message);
        this.rawResponse = rawResponse;
    }

    public ModelException(String code, int httpStatus, String message, Throwable cause) {
        super(code, httpStatus, message, cause);
        this.rawResponse = null;
    }

    public String getRawResponse() {
        return rawResponse;
    }

    /** 用错误码枚举创建 */
    public static ModelException of(ErrorCode errorCode) {
        return new ModelException(errorCode.code(), errorCode.httpStatus(), errorCode.defaultMessage());
    }

    public static ModelException of(ErrorCode errorCode, String detail) {
        return new ModelException(errorCode.code(), errorCode.httpStatus(),
                errorCode.defaultMessage() + "：" + detail);
    }

    public static ModelException of(ErrorCode errorCode, Throwable cause) {
        return new ModelException(errorCode.code(), errorCode.httpStatus(),
                errorCode.defaultMessage(), cause);
    }

    /** 带厂商原始响应的创建 */
    public static ModelException withRawResponse(int httpStatus, String message, String rawResponse) {
        return new ModelException("MODEL_API_ERROR", httpStatus, message, rawResponse);
    }
}