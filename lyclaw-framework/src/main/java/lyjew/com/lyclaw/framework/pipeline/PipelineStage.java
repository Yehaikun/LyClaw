package lyjew.com.lyclaw.framework.pipeline;

import lyjew.com.lyclaw.framework.model.ChatContext;

public interface PipelineStage {

    default boolean supports(ChatContext context) {
        return true;
    }

    void process(ChatContext context, Chain chain);

    int getOrder();

    String getStageName();
}
