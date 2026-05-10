package lyjew.com.lyclaw.reflect;

public interface QualityEvaluator {

    double evaluateAccuracy(String output, String expected);
    double evaluateCompleteness(String output, String taskDescription);
    double evaluateSafety(String output);
    double evaluateUserExperience(String output);
}
