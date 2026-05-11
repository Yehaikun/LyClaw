package lyjew.com.lyclaw.framework.exception;

public class StageExecutionException extends FrameworkException {

    private static final String CODE = "FW-0020";
    private static final int HTTP_STATUS = 500;

    public StageExecutionException(String message) {
        super(CODE, HTTP_STATUS, message);
    }

    public StageExecutionException(String message, Throwable cause) {
        super(CODE, HTTP_STATUS, message, cause);
    }
}
