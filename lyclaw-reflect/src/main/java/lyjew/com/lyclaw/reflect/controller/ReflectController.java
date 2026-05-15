package lyjew.com.lyclaw.reflect.controller;

import lyjew.com.lyclaw.reflect.DetectedError;
import lyjew.com.lyclaw.reflect.ErrorDetector;
import lyjew.com.lyclaw.reflect.QualityAssessment;
import lyjew.com.lyclaw.reflect.QualityCriteria;
import lyjew.com.lyclaw.reflect.QualityEvaluator;
import lyjew.com.lyclaw.reflect.ReflectRequest;
import lyjew.com.lyclaw.reflect.ReflectionEngine;
import lyjew.com.lyclaw.reflect.ReflectionReport;
import lyjew.com.lyclaw.reflect.StrategyAdjuster;
import lyjew.com.lyclaw.reflect.StrategyAdjustment;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.*;

/**
 * 反思服务REST控制器。
 *
 * <p>提供三个核心API端点：</p>
 * <ol>
 *   <li><b>POST /api/reflect/reflect</b> — 完整反思：评估输出质量、检测错误、生成策略调整建议</li>
 *   <li><b>POST /api/reflect/evaluate</b> — 单独评估：仅对输出进行多维度质量评分</li>
 *   <li><b>POST /api/reflect/detect-errors</b> — 单独检测：仅检测输出中的错误（幻觉、逻辑矛盾）</li>
 * </ol>
 *
 * <p>架构设计：三个端点分别直接注入和调用三个核心组件：
 * {@link ReflectionEngine}、{@link QualityEvaluator}、{@link ErrorDetector}。
 * 这种"轻薄控制器"设计将业务逻辑完全委托给服务层，便于单元测试和组件替换。</p>
 */
@Slf4j
@RestController
@RequestMapping("/api/reflect")
public class ReflectController {

    /** 反思引擎，负责完整的反思流程 */
    private final ReflectionEngine reflectionEngine;
    /** 质量评估器，负责多维度评分 */
    private final QualityEvaluator qualityEvaluator;
    /** 错误检测器，负责检测幻觉和逻辑矛盾 */
    private final ErrorDetector errorDetector;
    /** 策略调整器，负责根据反思结果生成调整建议 */
    private final StrategyAdjuster strategyAdjuster;

    /**
     * 构造控制器，注入反思所需的所有组件。
     *
     * @param reflectionEngine  反思引擎
     * @param qualityEvaluator  质量评估器
     * @param errorDetector     错误检测器
     * @param strategyAdjuster  策略调整器
     */
    public ReflectController(ReflectionEngine reflectionEngine,
                              QualityEvaluator qualityEvaluator,
                              ErrorDetector errorDetector,
                              StrategyAdjuster strategyAdjuster) {
        this.reflectionEngine = reflectionEngine;
        this.qualityEvaluator = qualityEvaluator;
        this.errorDetector = errorDetector;
        this.strategyAdjuster = strategyAdjuster;
    }

    /**
     * 完整反思端点：评估质量、检测错误、生成调整建议。
     *
     * <p>对AI输出进行一站式反思：先构建质量评估标准并评分，
     * 再检测幻觉和逻辑矛盾，最后在需要时生成策略调整建议。</p>
     *
     * @param request 反思请求，包含输出文本、上下文和期望输出
     * @return 反思报告，包含质量评分、错误列表和可选的调整建议
     */
    @PostMapping("/reflect")
    public ReflectionReport reflect(@RequestBody ReflectRequest request) {
        log.info("收到反思请求: sessionId={}, output长度={}", request.getSessionId(),
                request.getOutput() != null ? request.getOutput().length() : 0);
        // 构建质量评估标准（默认开启全部四个维度）
        QualityCriteria criteria = QualityCriteria.builder()
                .taskDescription(request.getContext() != null ? request.getContext() : "")
                .expectedOutput(request.getExpectedOutput() != null ? request.getExpectedOutput() : "")
                .checkAccuracy(true)
                .checkCompleteness(true)
                .checkSafety(true)
                .checkUserExperience(true)
                .build();

        // 执行多维度质量评估
        QualityAssessment quality = reflectionEngine.assessQuality(
                request.getOutput(), criteria);

        // 执行错误检测（幻觉 + 逻辑矛盾）
        List<DetectedError> errors = new ArrayList<>();
        errors.addAll(errorDetector.detectHallucination(
                request.getOutput(), Collections.emptyList()));
        errors.addAll(errorDetector.detectLogicContradiction(
                request.getOutput()));

        double overall = quality.getOverall();

        // 组装反思报告
        ReflectionReport report = ReflectionReport.builder()
                .reflectionId(UUID.randomUUID().toString())
                .sessionId(request.getSessionId())
                .quality(quality)
                .errors(errors)
                .overallScore(overall)
                .timestamp(System.currentTimeMillis())
                .build();

        // 存在错误或综合分低于0.6时，生成策略调整建议
        if (!errors.isEmpty() || overall < 0.6) {
            StrategyAdjustment suggestion = strategyAdjuster.adjust(report);
            report.setSuggestion(suggestion);
        }

        return report;
    }

    /**
     * 独立质量评估端点。
     *
     * <p>仅对输出进行多维度质量评分，不执行错误检测和策略调整。
     * 支持通过criteria参数自定义各维度的开关。</p>
     *
     * @param body 包含output和可选的criteria配置的请求体
     * @return 质量评估结果，包含各维度评分和综合评分
     */
    @PostMapping("/evaluate")
    public QualityAssessment evaluate(@RequestBody Map<String, Object> body) {
        String output = (String) body.getOrDefault("output", "");
        @SuppressWarnings("unchecked")
        Map<String, Object> criteriaMap = (Map<String, Object>) body.get("criteria");

        QualityCriteria criteria;
        // 如果请求中提供了criteria配置，则按需构建
        if (criteriaMap != null) {
            criteria = QualityCriteria.builder()
                    .taskDescription((String) criteriaMap.getOrDefault("taskDescription", ""))
                    .expectedOutput((String) criteriaMap.getOrDefault("expectedOutput", ""))
                    .checkAccuracy((Boolean) criteriaMap.getOrDefault("checkAccuracy", true))
                    .checkCompleteness((Boolean) criteriaMap.getOrDefault("checkCompleteness", true))
                    .checkSafety((Boolean) criteriaMap.getOrDefault("checkSafety", true))
                    .checkUserExperience((Boolean) criteriaMap.getOrDefault("checkUserExperience", true))
                    .build();
        } else {
            // 未提供criteria时使用默认配置（全部开启）
            criteria = QualityCriteria.builder()
                    .taskDescription("")
                    .expectedOutput("")
                    .checkAccuracy(true)
                    .checkCompleteness(true)
                    .checkSafety(true)
                    .checkUserExperience(true)
                    .build();
        }

        return reflectionEngine.assessQuality(output, criteria);
    }

    /**
     * 独立错误检测端点。
     *
     * <p>仅对输出进行幻觉和逻辑矛盾检测，不执行质量评估。
     * 支持传入groundTruth列表进行交叉验证。</p>
     *
     * @param body 包含output和可选的groundTruth列表的请求体
     * @return 检测到的错误列表，空列表表示未检测到错误
     */
    @PostMapping("/detect-errors")
    public List<DetectedError> detectErrors(@RequestBody Map<String, Object> body) {
        String output = (String) body.getOrDefault("output", "");
        @SuppressWarnings("unchecked")
        List<String> groundTruth = (List<String>) body.getOrDefault("groundTruth", Collections.emptyList());

        // 空输出直接返回空结果
        if (output == null || output.isBlank()) {
            return Collections.emptyList();
        }

        // 执行幻觉检测和逻辑矛盾检测
        List<DetectedError> errors = new ArrayList<>();
        errors.addAll(errorDetector.detectHallucination(output, groundTruth));
        errors.addAll(errorDetector.detectLogicContradiction(output));

        return errors;
    }
}
