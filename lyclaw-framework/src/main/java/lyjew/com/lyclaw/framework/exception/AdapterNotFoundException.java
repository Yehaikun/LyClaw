package lyjew.com.lyclaw.framework.exception;

public class AdapterNotFoundException extends FrameworkException {

    private static final String CODE = "FW-0010";
    private static final int HTTP_STATUS = 500;

    public AdapterNotFoundException(String message) {
        super(CODE, HTTP_STATUS, message);
    }

    public AdapterNotFoundException(String message, Throwable cause) {
        super(CODE, HTTP_STATUS, message, cause);
    }
}
