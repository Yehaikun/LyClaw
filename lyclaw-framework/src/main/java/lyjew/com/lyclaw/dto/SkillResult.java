package lyjew.com.lyclaw.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 技能执行结果 DTO，封装技能调用完成后的返回值。
 *
 * <p>不可变对象，通过 Jackson {@link JsonCreator} 支持 JSON 序列化/反序列化。
 * 包含执行状态、输出、错误信息、Token 消耗和执行耗时。</p>
 */
public class SkillResult {

    /** 技能标识 */
    private final String skillId;
    /** 执行是否成功 */
    private final boolean success;
    /** 成功时的输出内容 */
    private final String output;
    /** 失败时的错误信息 */
    private final String error;
    /** Token 消耗量 */
    private final int tokenUsage;
    /** 执行耗时（毫秒） */
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
