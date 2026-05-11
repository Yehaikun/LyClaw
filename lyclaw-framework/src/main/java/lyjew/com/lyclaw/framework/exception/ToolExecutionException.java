package lyjew.com.lyclaw.framework.exception;

public class ToolExecutionException extends FrameworkException {

    private static final String CODE = "FW-0001";
    private static final int HTTP_STATUS = 500;

    public ToolExecutionException(String message) {
        super(CODE, HTTP_STATUS, message);
    }

    public ToolExecutionException(String message, Throwable cause) {
        super(CODE, HTTP_STATUS, message, cause);
    }
}
