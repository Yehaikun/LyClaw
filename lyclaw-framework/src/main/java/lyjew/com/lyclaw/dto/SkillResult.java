package lyjew.com.lyclaw.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

public class SkillResult {

    private final String skillId;
    private final boolean success;
    private final String output;
    private final String error;
    private final int tokenUsage;
    private final long elapsedMs;

    @JsonCreator
    public SkillResult(@JsonProperty("skillId") String skillId,
                       @JsonProperty("success") boolean success,
                       @JsonProperty("output") String output,
                       @JsonProperty("error") String error,
                       @JsonProperty("tokenUsage") int tokenUsage,
                       @JsonProperty("elapsedMs") long elapsedMs) {
        this.skillId = skillId;
        this.success = success;
        this.output = output;
        this.error = error;
        this.tokenUsage = tokenUsage;
        this.elapsedMs = elapsedMs;
    }

    public String getSkillId() { return skillId; }

    public boolean isSuccess() { return success; }

    public String getOutput() { return output; }

    public String getError() { return error; }

    public int getTokenUsage() { return tokenUsage; }

    public long getElapsedMs() { return elapsedMs; }
}
