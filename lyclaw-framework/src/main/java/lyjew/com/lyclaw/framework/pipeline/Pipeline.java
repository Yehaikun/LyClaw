package lyjew.com.lyclaw.framework.pipeline;

import lyjew.com.lyclaw.framework.model.ChatContext;

import java.util.List;

public interface Pipeline {

    void execute(ChatContext context);

    List<PipelineStage> getStages();
}
