package lyjew.com.lyclaw.security;

import lyjew.com.lyclaw.context.ChatContext;

/**
 * 认证管理器接口，负责身份验证和用户识别。
 */
public interface AuthenticationManager {

    /**
     * 从对话上下文中提取并验证用户身份。
     *
     * @param context 对话上下文
     * @return 认证结果，含用户 ID 和认证状态
     */
    AuthResult authenticate(ChatContext context);
}
