package lyjew.com.lyclaw.framework.exception;

public class MemorySystemException extends FrameworkException {

    private static final String CODE = "FW-0050";
    private static final int HTTP_STATUS = 500;

    public MemorySystemException(String message) {
        super(CODE, HTTP_STATUS, message);
    }

    public MemorySystemException(String message, Throwable cause) {
        super(CODE, HTTP_STATUS, message, cause);
    }
}
