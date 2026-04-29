package lyjew.com.lyclaw.interceptor;

import lyjew.com.lyclaw.context.ChatContext;
import lyjew.com.lyclaw.dto.ChatResult;

/**
 * 拦截器抽象 —— 在请求处理前后执行横切关注点。
 *
 * <p>类似于 Spring MVC 的 HandlerInterceptor，Interceptor 在 Pipeline 的
 * InterceptorStage 中被执行。preHandle 在 ToolCallLoop 之前调用，
 * postHandle 在 ResponseBuildStage 构建完 ChatResult 之后调用。</p>
 *
 * <p><b>设计动机</b>：日志记录、限流检查、敏感数据脱敏、审计日志等横切关注点
 * 不应该散落在各业务代码中。通过拦截器机制，将这些关注点集中到 Interceptor 中，
 * 通过 InterceptorChain 统一管理。</p>
 *
 * <p><b>典型拦截器及执行顺序</b>：
 * <ul>
 *   <li>RateLimitInterceptor（order=10）：最先执行，检查请求频率</li>
 *   <li>SensitiveDataInterceptor（order=30）：对输入脱敏</li>
 *   <li>LoggingInterceptor（order=100）：记录请求响应日志</li>
 * </ul>
 * </p>
 *
 * @since 1.0
 * @author LyClaw Team
 * @see InterceptorChain
 */
public interface Interceptor {

    /**
     * 在请求处理前执行。
     *
     * <p>可以做以下事情：
     * <ul>
     *   <li>修改 ChatContext（如注入额外属性）</li>
     *   <li>检查条件（如限流检查），返回 false 中断处理流程</li>
     *   <li>记录开始时间用于后续计算耗时</li>
     * </ul>
     * </p>
     *
     * @param context 对话上下文（可读写）
     * @return true 表示继续处理，false 表示中断流程
     */
    boolean preHandle(ChatContext context);

    /**
     * 在请求处理后执行。
     *
     * <p>此时 ChatResult 已被构建，可以：
     * <ul>
     *   <li>修改响应内容（如对输出脱敏）</li>
     *   <li>记录完成日志（耗时、Token 用量）</li>
     *   <li>采集指标数据</li>
     * </ul>
     * </p>
     *
     * @param context 对话上下文
     * @param result  构建好的对话结果（可修改）
     */
    void postHandle(ChatContext context, ChatResult result);

    /**
     * 获取拦截器的执行顺序。数字越小越先执行。
     *
     * <p>建议预留步长（如 10、20、30），以便后续插入新拦截器。
     * 返回 {@link Integer#MIN_VALUE} 表示最先执行（限流拦截器使用）。</p>
     *
     * @return 执行优先级（值越小优先级越高）
     */
    int getOrder();
}