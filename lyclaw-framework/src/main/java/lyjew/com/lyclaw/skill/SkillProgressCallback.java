package lyjew.com.lyclaw.skill;

import lyjew.com.lyclaw.dto.SkillResult;

public interface SkillProgressCallback {

    void onProgress(String skillId, double progress, String message);

    void onComplete(String skillId, SkillResult result);

    void onError(String skillId, Throwable error);
}
