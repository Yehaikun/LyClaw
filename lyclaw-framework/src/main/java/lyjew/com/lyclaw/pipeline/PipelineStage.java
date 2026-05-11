package lyjew.com.lyclaw.pipeline;

import lyjew.com.lyclaw.context.ChatContext;

@Deprecated
public interface PipelineStage {

    default boolean supports(ChatContext context) {
        return true;
    }

    void process(ChatContext context, Chain chain);

    int getOrder();

    String getStageName();
}
