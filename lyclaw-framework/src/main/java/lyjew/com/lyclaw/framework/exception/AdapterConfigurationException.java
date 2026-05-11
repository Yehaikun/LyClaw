package lyjew.com.lyclaw.framework.exception;

public class AdapterConfigurationException extends FrameworkException {

    private static final String CODE = "FW-0011";
    private static final int HTTP_STATUS = 400;

    public AdapterConfigurationException(String message) {
        super(CODE, HTTP_STATUS, message);
    }

    public AdapterConfigurationException(String message, Throwable cause) {
        super(CODE, HTTP_STATUS, message, cause);
    }
}
