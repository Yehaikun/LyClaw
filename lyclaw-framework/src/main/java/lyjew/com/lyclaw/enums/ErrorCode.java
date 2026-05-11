package lyjew.com.lyclaw.enums;

import lyjew.com.lyclaw.base.exception.LyClawException;

/**
 * 统一错误码枚举，覆盖系统、模型、存储、校验、会话五大分类。
 *
 * <p>每个错误码包含内部编码、HTTP 状态码和中文默认消息。通过 {@link #exception()} 等工厂方法
 * 可快速构建 {@link LyClawException}，简化异常抛出代码。</p>
 *
 * <p>错误码编号规则：千位段标识领域，百分位子类细分。1xxx=系统，2xxx=模型，3xxx=存储，
 * 4xxx=校验，5xxx=会话。</p>
 *
 * @see LyClawException
 */
public enum ErrorCode {

    // ==================== 系统级错误 1xxx ====================
    SYSTEM_ERROR("1000", 500, "系统内部错误"),
    STORAGE_ERROR("1001", 500, "存储读写失败"),
    CONFIG_MISSING("1002", 500, "配置项缺失或无效"),

    // ==================== 模型相关错误 2xxx ====================
    MODEL_CONFIG_NOT_FOUND("2001", 404, "模型配置不存在"),
    MODEL_API_INVALID_KEY("2002", 401, "API Key 无效或已过期"),
    MODEL_API_FORBIDDEN("2003", 403, "API Key 没有访问权限"),
    MODEL_API_TIMEOUT("2004", 504, "模型 API 响应超时"),
    MODEL_API_RATE_LIMITED("2005", 429, "请求过于频繁，请稍后重试"),
    MODEL_API_ERROR("2006", 502, "模型 API 返回错误"),
    MODEL_RESPONSE_PARSE_ERROR("2007", 500, "模型响应解析失败"),
    MODEL_TOOL_CALLS_EXCEEDED("2008", 400, "工具调用轮次超过上限"),
    MODEL_INVALID_REQUEST("2009", 400, "请求参数无效"),
    MODEL_CONTENT_FILTER("2010", 400, "内容被安全策略过滤"),
    MODEL_UNSUPPORTED_OPERATION("2011", 400, "模型不支持此操作"),
    ADAPTER_NOT_FOUND("2012", 500, "未找到对应的模型适配器"),
    ADAPTER_NOT_CONFIGURED("2013", 400, "适配器尚未配置，请先调用 configure()"),

    // ==================== 存储相关错误 3xxx ====================
    STORAGE_FILE_NOT_FOUND("3001", 404, "文件不存在"),
    STORAGE_READ_ERROR("3002", 500, "读取文件失败"),
    STORAGE_WRITE_ERROR("3003", 500, "写入文件失败"),
    STORAGE_PARSE_ERROR("3004", 500, "文件解析失败"),
    STORAGE_DELETE_ERROR("3005", 500, "删除文件失败"),
    STORAGE_CREATE_DIR_ERROR("3006", 500, "创建目录失败"),
    STORAGE_LIST_ERROR("3007", 500, "列出目录失败"),

    // ==================== 校验相关错误 4xxx ====================
    VALIDATION_ERROR("4001", 400, "参数校验失败"),
    CONFIG_NAME_DUPLICATE("4002", 409, "配置名称已存在"),
    CONFIG_NAME_NOT_FOUND("4003", 404, "配置名称不存在"),

    // ==================== 会话相关错误 5xxx ====================
    SESSION_NOT_FOUND("5001", 404, "会话不存在"),
    SESSION_MESSAGE_LIMIT("5002", 400, "会话消息数已达上限"),
    SESSION_CORRUPTED("5003", 500, "会话文件已损坏");

    /** 内部错误编码 */
    private final String code;
    /** 对应的 HTTP 状态码 */
    private final int httpStatus;
    /** 中文默认错误消息 */
    private final String defaultMessage;

    ErrorCode(String code, int httpStatus, String defaultMessage) {
        this.code = code;
        this.httpStatus = httpStatus;
        this.defaultMessage = defaultMessage;
    }

    /** @return 内部错误编码 */
    public String code() { return code; }

    /** @return 对应的 HTTP 状态码 */
    public int httpStatus() { return httpStatus; }

    /** @return 中文默认错误消息 */
    public String defaultMessage() { return defaultMessage; }

    /**
     * 使用默认消息构建异常。
     *
     * @return 携带当前错误码和默认消息的 LyClawException 实例
     */
    public LyClawException exception() {
        return new LyClawException(code, httpStatus, defaultMessage);
    }

    /**
     * 使用默认消息并附加根因构建异常。
     *
     * @param cause 原始异常
     * @return 携带根因的 LyClawException 实例
     */
    public LyClawException exception(Throwable cause) {
        return new LyClawException(code, httpStatus, defaultMessage, cause);
    }

    /**
     * 使用自定义消息构建异常，覆盖默认消息。
     *
     * @param customMessage 自定义错误描述
     * @return 携带自定义消息的 LyClawException 实例
     */
    public LyClawException exception(String customMessage) {
        return new LyClawException(code, httpStatus, customMessage);
    }
}
