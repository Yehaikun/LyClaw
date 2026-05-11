package lyjew.com.lyclaw.framework.skill;

import lyjew.com.lyclaw.framework.model.ChatContext;
import lyjew.com.lyclaw.framework.model.SkillResult;

import java.util.concurrent.CompletableFuture;

public interface Skill {

    String getSkillId();

    String getName();

    String getDescription();

    CompletableFuture<SkillResult> execute(ChatContext context);
}
