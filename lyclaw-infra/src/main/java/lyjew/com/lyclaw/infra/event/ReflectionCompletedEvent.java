package lyjew.com.lyclaw.infra.event;

import lyjew.com.lyclaw.event.Event;

/**
 * 反思完成事件，在 Agent 完成一次自我反思后发布。
 *
 * <p>携带反思 ID、会话标识、综合评分和是否存在错误等信息，
 * 用于触发后续的自我修正或质量评估流程。</p>
 */
public class ReflectionCompletedEvent extends Event {

    /** 反思唯一标识 */
    private final String reflectionId;
    /** 关联的会话 ID */
    private final String sessionId;
    /** 综合评分 */
    private final double overallScore;
    /** 反思过程中是否发现错误 */
    private final boolean hasErrors;

    /**
     * 构造一个反思完成事件。
     *
     * @param source       事件来源标识
     * @param reflectionId 反思 ID
     * @param sessionId    会话 ID
     * @param overallScore 综合评分
     * @param hasErrors    是否包含错误
     */
    public ReflectionCompletedEvent(String source, String reflectionId, String sessionId,
                                    double overallScore, boolean hasErrors) {
        super(source, "REFLECTION_COMPLETED");
        this.reflectionId = reflectionId;
        this.sessionId = sessionId;
        this.overallScore = overallScore;
        this.hasErrors = hasErrors;
    }

    /** @return 反思 ID */
    public String getReflectionId() { return reflectionId; }
    /** @return 会话 ID */
    public String getSessionId() { return sessionId; }
    /** @return 综合评分 */
    public double getOverallScore() { return overallScore; }
    /** @return 是否有错误 */
    public boolean isHasErrors() { return hasErrors; }
}
