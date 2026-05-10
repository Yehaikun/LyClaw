package lyjew.com.lyclaw.filter;

import lyjew.com.lyclaw.context.ChatContext;

/**
 * 内容过滤器接口 —— 对输入和输出内容进行安全过滤。
 *
 * <p>过滤内容类型包括：
 * <ul>
 *   <li>敏感词过滤（政治敏感、暴力、色情等）</li>
 *   <li>SQL/脚本注入检测</li>
 *   <li>PII（个人隐私信息）脱敏</li>
 *   <li>自定义规则过滤</li>
 * </ul>
 * </p>
 *
 * <p><b>设计动机</b>：如果不通过统一接口管理内容过滤，每个使用场景都需要
 * 重复编写正则校验逻辑。ContentFilter + FilterResult 将过滤逻辑封装为策略，
 * 通过 SPI 或配置动态加载过滤器链。</p>
 *
 * <p><b>调用方</b>：
 * <ul>
 *   <li>SensitiveDataInterceptor — 在 preHandle 中过滤用户输入</li>
 *   <li>ResponseBuildStage — 在构建响应前过滤模型输出</li>
 * </ul>
 * </p>
 *
 * @since 1.0
 * @author LyClaw Team
 * @see FilterResult
 * @see SensitiveDataInterceptor
 */
public interface ContentFilter {

    /**
     * 过滤内容。对输入字符串做安全检查，返回过滤结果。
     * ChatContext 参数提供上下文信息（会话 ID、用户身份等），
     * 供过滤器基于场景做差异化判断。
     *
     * @param content 原始内容
     * @param context 当前对话上下文
     * @return 过滤结果
     */
    FilterResult filter(String content, ChatContext context);

    /**
     * 获取过滤器名称，用于运行时识别和管理。
     *
     * @return 过滤器名称，如 "sensitive-word-filter"、"sql-injection-filter"
     */
    String getFilterName();
}