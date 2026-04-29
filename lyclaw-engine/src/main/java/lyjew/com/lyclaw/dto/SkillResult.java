package lyjew.com.lyclaw.dto;

/**
 * 技能执行结果 —— SkillExecutor.execute() 返回值 CompletableFuture&lt;SkillResult&gt; 的类型。
 *
 * <p>技能（Skill）是比工具（Tool）更高层次的抽象，一个技能内部可能包含
 * 多次模型调用和多个工具调用。SkillResult 封装了技能的整体执行结果。</p>
 *
 * <p><b>设计动机</b>：将技能执行的完成状态、输出内容、Token 消耗等信息
 * 封装为一个不可变对象，便于异步回调处理和日志记录。</p>
 *
 * <p><b>使用场景</b>：
 * <ul>
 *   <li>SkillExecutor.execute() 的 CompletableFuture 返回值类型</li>
 *   <li>SkillProgressCallback.onComplete() 的回调参数</li>
 * </ul>
 * </p>
 *
 * @since 1.0
 * @author LyClaw Team
 */
public class SkillResult {

    /** 技能 ID —— 标识是哪个技能的执行结果 */
    private final String skillId;

    /** 是否执行成功。true 表示技能正常执行完毕 */
    private final boolean success;

    /** 输出内容 —— 技能执行后的文本输出 */
    private final String output;

    /** 错误信息 —— 执行失败时的错误描述，成功时为 null */
    private final String error;

    /** Token 消耗 —— 技能执行期间所有模型调用的 Token 总和 */
    private final int tokenUsage;

    /** 执行耗时（毫秒） */
    private final long elapsedMs;

    /**
     * 构造一个 SkillResult 实例。
     *
     * @param skillId   技能 ID
     * @param success   是否成功
     * @param output    输出内容
     * @param error     错误信息
     * @param tokenUsage Token 消耗
     * @param elapsedMs 执行耗时（毫秒）
     */
    public SkillResult(String skillId, boolean success, String output,
                       String error, int tokenUsage, long elapsedMs) {
        this.skillId = skillId;
        this.success = success;
        this.output = output;
        this.error = error;
        this.tokenUsage = tokenUsage;
        this.elapsedMs = elapsedMs;
    }

    /** @return 技能 ID */
    public String getSkillId() { return skillId; }

    /** @return 是否执行成功 */
    public boolean isSuccess() { return success; }

    /** @return 输出内容 */
    public String getOutput() { return output; }

    /** @return 错误信息 */
    public String getError() { return error; }

    /** @return Token 消耗 */
    public int getTokenUsage() { return tokenUsage; }

    /** @return 执行耗时（毫秒） */
    public long getElapsedMs() { return elapsedMs; }
}