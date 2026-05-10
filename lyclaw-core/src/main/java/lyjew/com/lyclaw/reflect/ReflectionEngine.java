package lyjew.com.lyclaw.reflect;

import lyjew.com.lyclaw.action.ActionResult;
import lyjew.com.lyclaw.context.ChatContext;
import java.util.List;

/**
 * 反思引擎 —— 四元架构中的反思模块核心。
 *
 * <p>对 AI 输出进行自我评估、错误检测和策略调整。
 * 触发时机: 实时(每个工具调用后) / 阶段(每个任务节点后) / 会话(对话结束后) / 周期性(定时任务)</p>
 *
 * @since 2.0
 */
public interface ReflectionEngine {

    ReflectionReport reflect(ChatContext context, ActionResult result);

    QualityAssessment assessQuality(String output, QualityCriteria criteria);

    List<DetectedError> detectErrors(String output, List<String> groundTruth);

    StrategyAdjustment suggestAdjustment(ReflectionReport report);
}
