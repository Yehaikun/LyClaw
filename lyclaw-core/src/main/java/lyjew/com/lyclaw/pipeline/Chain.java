package lyjew.com.lyclaw.pipeline;

import lyjew.com.lyclaw.context.ChatContext;

public interface Chain {

    void next(ChatContext context);

    void breakChain(ChatContext context);

    int getCurrentStage();
}
