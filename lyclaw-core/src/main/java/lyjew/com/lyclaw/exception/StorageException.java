package lyjew.com.lyclaw.exception;

import lyjew.com.lyclaw.base.exception.LyClawException;
import lyjew.com.lyclaw.enums.ErrorCode;

/**
 * 存储异常
 */
public class StorageException extends LyClawException {

    public StorageException(String code, String message) {
        super(code, 500, message);
    }

    public StorageException(String code, String message, Throwable cause) {
        super(code, 500, message, cause);
    }

    /** 用错误码枚举创建 */
    public static StorageException of(ErrorCode errorCode) {
        return new StorageException(errorCode.code(), errorCode.defaultMessage());
    }

    public static StorageException of(ErrorCode errorCode, String detail) {
        return new StorageException(errorCode.code(), errorCode.defaultMessage() + "：" + detail);
    }

    public static StorageException of(ErrorCode errorCode, Throwable cause) {
        return new StorageException(errorCode.code(), errorCode.defaultMessage(), cause);
    }
}