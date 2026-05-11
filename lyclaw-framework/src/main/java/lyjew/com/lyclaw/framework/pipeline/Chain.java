package lyjew.com.lyclaw.framework.pipeline;

import lyjew.com.lyclaw.framework.model.ChatContext;

public interface Chain {

    void next(ChatContext context);

    void breakChain(ChatContext context);

    int getCurrentStage();
}
