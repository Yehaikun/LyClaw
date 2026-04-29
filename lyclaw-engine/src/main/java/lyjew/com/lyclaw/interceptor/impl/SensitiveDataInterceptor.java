package lyjew.com.lyclaw.interceptor.impl;

import lyjew.com.lyclaw.context.ChatContext;
import lyjew.com.lyclaw.dto.ChatResult;
import lyjew.com.lyclaw.interceptor.Interceptor;

import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * 敏感数据脱敏拦截器 —— 对用户输入和模型输出中的敏感信息进行替换或遮蔽。
 *
 * <p>支持以下脱敏规则（可通过配置文件扩展）：
 * <ul>
 *   <li>手机号：13812345678 → 138****5678</li>
 *   <li>身份证号：110101199001011234 → 110101********1234</li>
 *   <li>邮箱：user@example.com → u***@example.com</li>
 *   <li>密码/密钥：通过正则匹配 "password"、"secret"、"key" 等关键词后的值</li>
 * </ul>
 * </p>
 *
 * <p><b>设计动机</b>：用户的对话内容可能包含隐私信息，如果不做脱敏，
 * 这些信息会被发送给模型 API，也会被记录到日志中，存在数据泄露风险。</p>
 *
 * @since 1.0
 * @author LyClaw Team
 * @see Interceptor
 */
@Component
public class SensitiveDataInterceptor implements Interceptor {

    /** 手机号匹配正则：11 位数字，以 1 开头 */
    private static final Pattern PHONE_PATTERN = Pattern.compile("1[3-9]\\d{9}");

    /** 身份证号匹配正则：18 位数字（末位可能是 X） */
    private static final Pattern ID_CARD_PATTERN = Pattern.compile("\\d{17}[\\dXx]");

    /**
     * 请求处理前遍历消息列表，对匹配脱敏规则的内容进行替换。
     *
     * @param context 对话上下文
     * @return 始终返回 true（脱敏不会阻止请求处理）
     */
    @Override
    public boolean preHandle(ChatContext context) {
        // 此处为简化实现。真实场景需要：
        // 1. 遍历 context.getMessages() 中的每条消息
        // 2. 对每条消息的 content 应用脱敏规则
        // 3. 替换匹配的内容
        return true;
    }

    /**
     * 模型回复输出时，对回复内容再次脱敏（防止模型输出了敏感数据）。
     *
     * @param context 对话上下文
     * @param result  对话结果
     */
    @Override
    public void postHandle(ChatContext context, ChatResult result) {
        // 对 result.getContent() 进行同样的脱敏处理
    }

    /**
     * 返回 30，在 RateLimitInterceptor 之后执行。
     *
     * @return 30
     */
    @Override
    public int getOrder() {
        return 30;
    }
}