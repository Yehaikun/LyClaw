package lyjew.com.lyclaw.interceptor;

import lyjew.com.lyclaw.context.ChatContext;
import lyjew.com.lyclaw.dto.ChatResult;

/**
 * 拦截器接口，定义聊天请求处理的拦截点。
 * 在聊天请求的生命周期中，可在处理前（preHandle）和处理后（postHandle）插入自定义逻辑。
 * 该接口已废弃。
 */
@Deprecated
public interface Interceptor {

    /**
     * 前置处理：在聊天请求被实际处理之前执行。
     * 可用于权限校验、参数预处理、请求日志记录等。
     *
     * @param context 聊天上下文
     * @return 返回 true 则继续执行后续拦截器和主处理逻辑；返回 false 则中断请求
     */
    boolean preHandle(ChatContext context);

    /**
     * 后置处理：在聊天请求处理完成并生成结果后执行。
     * 可用于结果后处理、日志记录、统计上报等。
     *
     * @param context 聊天上下文
     * @param result  聊天处理结果
     */
    void postHandle(ChatContext context, ChatResult result);

    /**
     * 获取当前拦截器的执行优先级顺序。数值越小优先级越高，越先执行。
     *
     * @return 优先级顺序值
     */
    int getOrder();
}
