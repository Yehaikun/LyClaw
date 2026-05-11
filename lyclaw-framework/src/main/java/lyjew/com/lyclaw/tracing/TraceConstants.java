package lyjew.com.lyclaw.tracing;

/**
 * 链路追踪常量定义类，集中管理 HTTP 头名称和 MDC key 的字符串常量。
 *
 * <p>该工具类不可实例化，仅用于存放追踪系统中所有需要跨组件共享的常量。
 * 分为两类：HTTP 请求头常量（用于服务间传递追踪信息）和 MDC key 常量（用于日志上下文）。
 */
public final class TraceConstants {

    /** 私有构造器，防止实例化 */
    private TraceConstants() {}

    /** HTTP 请求头：追踪 ID */
    public static final String HEADER_TRACE_ID = "X-Trace-Id";
    /** HTTP 请求头：当前 span ID */
    public static final String HEADER_SPAN_ID = "X-Span-Id";
    /** HTTP 请求头：父级 span ID */
    public static final String HEADER_PARENT_SPAN_ID = "X-Parent-Span-Id";
    /** MDC key：追踪 ID，写入 SLF4J MDC 供日志使用 */
    public static final String MDC_TRACE_ID = "traceId";
    /** MDC key：span ID，写入 SLF4J MDC 供日志使用 */
    public static final String MDC_SPAN_ID = "spanId";
    /** MDC key：服务名称，写入 SLF4J MDC 供日志使用 */
    public static final String MDC_SERVICE = "service";
}
