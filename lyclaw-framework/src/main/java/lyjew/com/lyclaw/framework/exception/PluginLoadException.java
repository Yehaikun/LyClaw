package lyjew.com.lyclaw.framework.exception;

public class PluginLoadException extends FrameworkException {

    private static final String CODE = "FW-0030";
    private static final int HTTP_STATUS = 500;

    public PluginLoadException(String message) {
        super(CODE, HTTP_STATUS, message);
    }

    public PluginLoadException(String message, Throwable cause) {
        super(CODE, HTTP_STATUS, message, cause);
    }
}
