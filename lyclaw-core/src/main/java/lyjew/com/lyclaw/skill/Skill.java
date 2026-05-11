package lyjew.com.lyclaw.skill;

import lyjew.com.lyclaw.context.ChatContext;
import lyjew.com.lyclaw.dto.SkillResult;

import java.util.concurrent.CompletableFuture;

@Deprecated
public interface Skill {

    String getSkillId();

    String getName();

    String getDescription();

    CompletableFuture<SkillResult> execute(ChatContext context);
}
