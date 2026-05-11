package lyjew.com.lyclaw.framework.exception;

public class PipelineBuildException extends FrameworkException {

    private static final String CODE = "FW-0080";
    private static final int HTTP_STATUS = 500;

    public PipelineBuildException(String message) {
        super(CODE, HTTP_STATUS, message);
    }

    public PipelineBuildException(String message, Throwable cause) {
        super(CODE, HTTP_STATUS, message, cause);
    }
}
