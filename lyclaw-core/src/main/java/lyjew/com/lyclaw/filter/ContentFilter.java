package lyjew.com.lyclaw.filter;

import lyjew.com.lyclaw.context.ChatContext;

public interface ContentFilter {

    FilterResult filter(String content, ChatContext context);

    String getFilterName();
}
