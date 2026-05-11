package lyjew.com.lyclaw.framework.model;

public class SkillResult {

    private String skillId;
    private boolean success;
    private String output;
    private String error;
    private int tokenUsage;
    private long elapsedMs;

    public SkillResult() {
    }

    public SkillResult(String skillId, boolean success, String output, String error,
                       int tokenUsage, long elapsedMs) {
        this.skillId = skillId;
        this.success = success;
        this.output = output;
        this.error = error;
        this.tokenUsage = tokenUsage;
        this.elapsedMs = elapsedMs;
    }

    public String getSkillId() {
        return skillId;
    }

    public void setSkillId(String skillId) {
        this.skillId = skillId;
    }

    public boolean isSuccess() {
        return success;
    }

    public void setSuccess(boolean success) {
        this.success = success;
    }

    public String getOutput() {
        return output;
    }

    public void setOutput(String output) {
        this.output = output;
    }

    public String getError() {
        return error;
    }

    public void setError(String error) {
        this.error = error;
    }

    public int getTokenUsage() {
        return tokenUsage;
    }

    public void setTokenUsage(int tokenUsage) {
        this.tokenUsage = tokenUsage;
    }

    public long getElapsedMs() {
        return elapsedMs;
    }

    public void setElapsedMs(long elapsedMs) {
        this.elapsedMs = elapsedMs;
    }
}
