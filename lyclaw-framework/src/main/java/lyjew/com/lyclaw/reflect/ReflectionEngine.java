package lyjew.com.lyclaw.reflect;

import lyjew.com.lyclaw.action.ActionResult;
import lyjew.com.lyclaw.context.ChatContext;

import java.util.List;

public interface ReflectionEngine {

    ReflectionReport reflect(ChatContext context, ActionResult result);
    QualityAssessment assessQuality(String output, QualityCriteria criteria);
    List<DetectedError> detectErrors(String output, List<String> groundTruth);
    StrategyAdjustment suggestAdjustment(ReflectionReport report);
}
