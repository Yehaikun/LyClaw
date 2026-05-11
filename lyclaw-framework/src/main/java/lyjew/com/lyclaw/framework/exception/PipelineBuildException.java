package lyjew.com.lyclaw.framework.exception;

/**
 * 流水线构建异常，表示在构建执行流水线（Pipeline）时发生了错误。
 *
 * <p>携带固定错误码 FW-0080 及 HTTP 500 状态码。通常发生在流水线节点组装、
 * 步骤依赖解析失败、或流水线配置不合法时，由 Pipeline 构建器抛出。</p>
 */
public class PipelineBuildException extends FrameworkException {

    /** 框架错误码 */
    private static final String CODE = "FW-0080";
    /** 对应的 HTTP 状态码 */
    private static final int HTTP_STATUS = 500;

    /**
     * 构造流水线构建异常。
     *
     * @param message 异常描述信息
     */
    public PipelineBuildException(String message) {
        super(CODE, HTTP_STATUS, message);
    }

    /**
     * 构造包含原始异常的流水线构建异常。
     *
     * @param message 异常描述信息
     * @param cause   原始异常
     */
    public PipelineBuildException(String message, Throwable cause) {
        super(CODE, HTTP_STATUS, message, cause);
    }
}
