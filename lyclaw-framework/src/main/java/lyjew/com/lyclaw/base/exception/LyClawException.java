package lyjew.com.lyclaw.base.exception;

import lombok.Getter;

@Getter
public class LyClawException extends RuntimeException {

    private final String code;
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

    public static LyClawException internal(String code, String message) {
        return new LyClawException(code, 500, message);
    }

    public static LyClawException internal(String code, String message, Throwable cause) {
        return new LyClawException(code, 500, message, cause);
    }
}
