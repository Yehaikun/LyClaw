package lyjew.com.lyclaw.pipeline;

import lyjew.com.lyclaw.context.ChatContext;

import java.util.List;

public interface Pipeline {

    void execute(ChatContext context);

    List<PipelineStage> getStages();
}
