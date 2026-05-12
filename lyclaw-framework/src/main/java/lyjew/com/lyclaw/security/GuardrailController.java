package lyjew.com.lyclaw.security;

import lyjew.com.lyclaw.context.ChatContext;
import lyjew.com.lyclaw.filter.ContentFilter;
import lyjew.com.lyclaw.filter.FilterResult;

import java.util.List;

/**
 * 防护栏控制器接口，负责输入/输出内容的安全过滤。
 *
 * <p>防护栏链由有序的内容过滤器组成，任一过滤器拒绝则拦截。
 * 输入防护在用户消息进入系统前执行，输出防护在 AI 响应返回前执行。
 */
public interface GuardrailController {

    /** 对输入内容依次应用所有输入过滤器。 */
    FilterResult applyInputGuardrails(String content, ChatContext context);

    /** 对输出内容依次应用所有输出过滤器。 */
    FilterResult applyOutputGuardrails(String content, ChatContext context);

    /** 添加输入内容过滤器。 */
    void addInputFilter(ContentFilter filter);

    /** 添加输出内容过滤器。 */
    void addOutputFilter(ContentFilter filter);

    /** @return 当前输入过滤器列表 */
    List<ContentFilter> getInputFilters();

    /** @return 当前输出过滤器列表 */
    List<ContentFilter> getOutputFilters();
}
