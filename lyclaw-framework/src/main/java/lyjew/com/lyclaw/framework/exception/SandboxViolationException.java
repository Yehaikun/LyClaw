package lyjew.com.lyclaw.framework.exception;

public class SandboxViolationException extends FrameworkException {

    private static final String CODE = "FW-0090";
    private static final int HTTP_STATUS = 403;

    public SandboxViolationException(String message) {
        super(CODE, HTTP_STATUS, message);
    }

    public SandboxViolationException(String message, Throwable cause) {
        super(CODE, HTTP_STATUS, message, cause);
    }
}
