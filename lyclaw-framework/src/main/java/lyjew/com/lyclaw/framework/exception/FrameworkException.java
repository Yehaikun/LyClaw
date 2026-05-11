package lyjew.com.lyclaw.framework.exception;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

public class FrameworkException extends RuntimeException {

    private final String code;
    private final int httpStatus;
    private final Map<String, Object> details;

    public FrameworkException(String code, int httpStatus, String message) {
        super(message);
        this.code = code;
        this.httpStatus = httpStatus;
        this.details = new HashMap<>();
    }

    public FrameworkException(String code, int httpStatus, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
        this.httpStatus = httpStatus;
        this.details = new HashMap<>();
    }

    public FrameworkException withDetail(String key, Object value) {
        this.details.put(key, value);
        return this;
    }

    public String getCode() {
        return code;
    }

    public int getHttpStatus() {
        return httpStatus;
    }

    public Map<String, Object> getDetails() {
        return Collections.unmodifiableMap(details);
    }
}
