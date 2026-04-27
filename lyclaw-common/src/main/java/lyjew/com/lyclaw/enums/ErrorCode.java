package lyjew.com.lyclaw.enums;

import lyjew.com.lyclaw.base.exception.LyClawException;

/**
 * 统一错误码——所有模块的错误码集中管理
 */
public enum ErrorCode {

    // ========== 通用错误（1xxx） ==========
    SYSTEM_ERROR("1000", 500, "系统内部错误"),
    STORAGE_ERROR("1001", 500, "存储读写失败"),
    CONFIG_MISSING("1002", 500, "配置项缺失或无效"),

    // ========== 模型调用错误（2xxx） ==========
    MODEL_CONFIG_NOT_FOUND("2001", 404, "模型配置不存在"),
    MODEL_API_INVALID_KEY("2002", 401, "API Key 无效或已过期"),
    MODEL_API_FORBIDDEN("2003", 403, "API Key 没有访问权限"),
    MODEL_API_TIMEOUT("2004", 504, "模型 API 响应超时"),
    MODEL_API_RATE_LIMITED("2005", 429, "请求过于频繁，请稍后重试"),
    MODEL_API_ERROR("2006", 502, "模型 API 返回错误"),
    MODEL_RESPONSE_PARSE_ERROR("2007", 500, "模型响应解析失败"),
    MODEL_TOOL_CALLS_EXCEEDED("2008", 400, "工具调用轮次超过上限"),
    // ===== 新增 =====
    MODEL_INVALID_REQUEST("2009", 400, "请求参数无效"),
    MODEL_CONTENT_FILTER("2010", 400, "内容被安全策略过滤"),
    MODEL_UNSUPPORTED_OPERATION("2011", 400, "模型不支持此操作"),
    ADAPTER_NOT_FOUND("2012", 500, "未找到对应的模型适配器"),
    ADAPTER_NOT_CONFIGURED("2013", 400, "适配器尚未配置，请先调用 configure()"),

    // ========== 存储错误（3xxx） ==========
    STORAGE_FILE_NOT_FOUND("3001", 404, "文件不存在"),
    STORAGE_READ_ERROR("3002", 500, "读取文件失败"),
    STORAGE_WRITE_ERROR("3003", 500, "写入文件失败"),
    STORAGE_PARSE_ERROR("3004", 500, "文件解析失败"),
    STORAGE_DELETE_ERROR("3005", 500, "删除文件失败"),
    STORAGE_CREATE_DIR_ERROR("3006", 500, "创建目录失败"),
    STORAGE_LIST_ERROR("3007", 500, "列出目录失败"),

    // ========== 校验错误（4xxx） ==========
    VALIDATION_ERROR("4001", 400, "参数校验失败"),
    CONFIG_NAME_DUPLICATE("4002", 409, "配置名称已存在"),
    CONFIG_NAME_NOT_FOUND("4003", 404, "配置名称不存在"),

    // ========== 会话错误（5xxx） ==========
    SESSION_NOT_FOUND("5001", 404, "会话不存在"),
    SESSION_MESSAGE_LIMIT("5002", 400, "会话消息数已达上限"),
    SESSION_CORRUPTED("5003", 500, "会话文件已损坏");

    // ========== 字段 ==========

    private final String code;
    private final int httpStatus;
    private final String defaultMessage;

    ErrorCode(String code, int httpStatus, String defaultMessage) {
        this.code = code;
        this.httpStatus = httpStatus;
        this.defaultMessage = defaultMessage;
    }

    public String code() {
        return code;
    }

    public int httpStatus() {
        return httpStatus;
    }

    public String defaultMessage() {
        return defaultMessage;
    }

    /** 用这个错误码创建异常 */
    public LyClawException exception() {
        return new LyClawException(code, httpStatus, defaultMessage);
    }

    public LyClawException exception(Throwable cause) {
        return new LyClawException(code, httpStatus, defaultMessage, cause);
    }

    public LyClawException exception(String customMessage) {
        return new LyClawException(code, httpStatus, customMessage);
    }
}