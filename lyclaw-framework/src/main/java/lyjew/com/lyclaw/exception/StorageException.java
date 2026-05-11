package lyjew.com.lyclaw.exception;

import lyjew.com.lyclaw.base.exception.LyClawException;
import lyjew.com.lyclaw.enums.ErrorCode;

/**
 * 存储层异常，封装文件系统、数据库等持久化操作中的错误。
 *
 * <p>HTTP 状态码固定为 500（服务器内部错误），
 * 提供静态工厂方法从 {@link ErrorCode} 枚举快速构造异常实例。</p>
 */
public class StorageException extends LyClawException {

    /**
     * 构造存储异常。
     *
     * @param code    错误码
     * @param message 错误描述
     */
    public StorageException(String code, String message) {
        super(code, 500, message);
    }

    /**
     * 构造包含原始原因的存储异常。
     *
     * @param code    错误码
     * @param message 错误描述
     * @param cause   原始异常
     */
    public StorageException(String code, String message, Throwable cause) {
        super(code, 500, message, cause);
    }

    /** 从错误码枚举快速创建异常。 */
    public static StorageException of(ErrorCode errorCode) {
        return new StorageException(errorCode.code(), errorCode.defaultMessage());
    }

    /** 从错误码枚举创建异常，附加详细描述。 */
    public static StorageException of(ErrorCode errorCode, String detail) {
        return new StorageException(errorCode.code(), errorCode.defaultMessage() + "：" + detail);
    }

    /** 从错误码枚举创建异常，附加原始异常。 */
    public static StorageException of(ErrorCode errorCode, Throwable cause) {
        return new StorageException(errorCode.code(), errorCode.defaultMessage(), cause);
    }
}
