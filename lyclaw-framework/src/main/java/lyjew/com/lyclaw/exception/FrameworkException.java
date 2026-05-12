package lyjew.com.lyclaw.exception;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * 框架层统一异常基类，所有框架级异常均继承自此类。
 *
 * <p>封装了标准化的错误码（code）、HTTP 状态码（httpStatus）以及可扩展的详情映射（details），
 * 便于上层统一异常处理与对外 API 的规范响应。通过 {@link #withDetail(String, Object)}
 * 支持链式追加上下文信息。
 */
public class FrameworkException extends RuntimeException {

    /** 错误码，用于标识具体的异常类型（如 FW-0001） */
    private final String code;
    /** HTTP 状态码，用于 REST API 响应 */
    private final int httpStatus;
    /** 可扩展的异常详情，存储额外的上下文键值对 */
    private final Map<String, Object> details;

    /**
     * 构造一个新的框架异常。
     *
     * @param code       错误码
     * @param httpStatus HTTP 状态码
     * @param message    异常描述信息
     */
    public FrameworkException(String code, int httpStatus, String message) {
        super(message);
        this.code = code;
        this.httpStatus = httpStatus;
        this.details = new HashMap<>();
    }

    /**
     * 构造一个包含原始异常的框架异常。
     *
     * @param code       错误码
     * @param httpStatus HTTP 状态码
     * @param message    异常描述信息
     * @param cause      原始异常
     */
    public FrameworkException(String code, int httpStatus, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
        this.httpStatus = httpStatus;
        this.details = new HashMap<>();
    }

    /**
     * 向异常详情中添加一条上下文信息，支持链式调用。
     *
     * @param key   详情键
     * @param value 详情值
     * @return 当前异常实例，便于链式调用
     */
    public FrameworkException withDetail(String key, Object value) {
        this.details.put(key, value);
        return this;
    }

    /**
     * 获取错误码。
     *
     * @return 错误码字符串
     */
    public String getCode() {
        return code;
    }

    /**
     * 获取 HTTP 状态码。
     *
     * @return HTTP 状态码
     */
    public int getHttpStatus() {
        return httpStatus;
    }

    /**
     * 获取异常详情的不可变映射。
     *
     * @return 不可修改的详情映射
     */
    public Map<String, Object> getDetails() {
        return Collections.unmodifiableMap(details);
    }
}
