package lyjew.com.lyclaw.interceptor;

import lyjew.com.lyclaw.context.ChatContext;
import lyjew.com.lyclaw.dto.ChatResult;

@Deprecated
public interface Interceptor {

    boolean preHandle(ChatContext context);

    void postHandle(ChatContext context, ChatResult result);

    int getOrder();
}
