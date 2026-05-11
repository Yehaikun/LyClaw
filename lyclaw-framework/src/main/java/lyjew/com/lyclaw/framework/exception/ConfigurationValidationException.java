package lyjew.com.lyclaw.framework.exception;

public class ConfigurationValidationException extends FrameworkException {

    private static final String CODE = "FW-0110";
    private static final int HTTP_STATUS = 400;

    public ConfigurationValidationException(String message) {
        super(CODE, HTTP_STATUS, message);
    }

    public ConfigurationValidationException(String message, Throwable cause) {
        super(CODE, HTTP_STATUS, message, cause);
    }
}
