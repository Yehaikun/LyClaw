package lyjew.com.lyclaw.framework.exception;

public class ToolNotFoundException extends FrameworkException {

    private static final String CODE = "FW-0002";
    private static final int HTTP_STATUS = 404;

    public ToolNotFoundException(String message) {
        super(CODE, HTTP_STATUS, message);
    }

    public ToolNotFoundException(String message, Throwable cause) {
        super(CODE, HTTP_STATUS, message, cause);
    }
}
