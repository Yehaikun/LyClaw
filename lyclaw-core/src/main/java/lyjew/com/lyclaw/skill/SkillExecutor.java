package lyjew.com.lyclaw.skill;

import lyjew.com.lyclaw.context.ChatContext;
import lyjew.com.lyclaw.dto.SkillResult;

import java.util.concurrent.CompletableFuture;

public interface SkillExecutor {

    CompletableFuture<SkillResult> execute(Skill skill, ChatContext context);

    boolean cancel(String skillId);

    double getProgress(String skillId);

    void setProgressCallback(SkillProgressCallback callback);
}
