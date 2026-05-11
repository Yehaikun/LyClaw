package lyjew.com.lyclaw.framework.interceptor;

import lyjew.com.lyclaw.framework.model.ChatContext;
import lyjew.com.lyclaw.framework.model.ChatResult;

public interface Interceptor {

    boolean preHandle(ChatContext context);

    void postHandle(ChatContext context, ChatResult result);

    int getOrder();
}
