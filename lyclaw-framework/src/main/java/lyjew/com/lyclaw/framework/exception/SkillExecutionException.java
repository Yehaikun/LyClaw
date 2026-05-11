package lyjew.com.lyclaw.framework.exception;

public class SkillExecutionException extends FrameworkException {

    private static final String CODE = "FW-0070";
    private static final int HTTP_STATUS = 500;

    public SkillExecutionException(String message) {
        super(CODE, HTTP_STATUS, message);
    }

    public SkillExecutionException(String message, Throwable cause) {
        super(CODE, HTTP_STATUS, message, cause);
    }
}
