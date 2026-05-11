package lyjew.com.lyclaw.framework.exception;

public class ParameterBindingException extends FrameworkException {

    private static final String CODE = "FW-0040";
    private static final int HTTP_STATUS = 400;

    public ParameterBindingException(String message) {
        super(CODE, HTTP_STATUS, message);
    }

    public ParameterBindingException(String message, Throwable cause) {
        super(CODE, HTTP_STATUS, message, cause);
    }
}
