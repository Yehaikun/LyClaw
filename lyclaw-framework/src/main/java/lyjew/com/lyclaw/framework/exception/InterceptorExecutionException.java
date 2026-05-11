package lyjew.com.lyclaw.framework.exception;

public class InterceptorExecutionException extends FrameworkException {

    private static final String CODE = "FW-0100";
    private static final int HTTP_STATUS = 500;

    public InterceptorExecutionException(String message) {
        super(CODE, HTTP_STATUS, message);
    }

    public InterceptorExecutionException(String message, Throwable cause) {
        super(CODE, HTTP_STATUS, message, cause);
    }
}
