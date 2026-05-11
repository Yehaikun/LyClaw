package lyjew.com.lyclaw.framework.exception;

public class AgentDecisionException extends FrameworkException {

    private static final String CODE = "FW-0060";
    private static final int HTTP_STATUS = 500;

    public AgentDecisionException(String message) {
        super(CODE, HTTP_STATUS, message);
    }

    public AgentDecisionException(String message, Throwable cause) {
        super(CODE, HTTP_STATUS, message, cause);
    }
}
