package lyjew.com.lyclaw.reflect.impl.evaluator;

import lyjew.com.lyclaw.annotation.reflect.Primitive;
import lyjew.com.lyclaw.reflect.model.Evaluation;
import lyjew.com.lyclaw.reflect.model.Issue;
import lyjew.com.lyclaw.reflect.model.ReflectionContext;
import lyjew.com.lyclaw.reflect.model.Severity;
import lyjew.com.lyclaw.reflect.primitive.Evaluator;
import lyjew.com.lyclaw.reflect.topology.PrimitiveType;

import java.util.*;

/**
 * 事件重要性评估器 — 评估输出内容的重要程度，用于识别高敏感度话题。
 *
 * <p>评估维度：
 * <ol>
 *   <li><b>安全相关</b> — 涉及密码、密钥、认证、权限等安全领域（+0.3 importance）</li>
 *   <li><b>数据完整性</b> — 涉及数据库、事务、持久化等数据操作（+0.2 importance）</li>
 *   <li><b>紧急标记</b> — "urgent"、"critical"、"asap" 等紧急词汇（+0.2 importance）</li>
 *   <li><b>业务关键</b> — 涉及金钱、支付、订单、合规等业务（+0.3 importance）</li>
 *   <li><b>输出长度</b> — 长输出通常意味复杂话题（按比例加分）</li>
 * </ol>
 *
 * <p>importanceScore 范围 [0.0, 1.0]，越高表示内容越重要/敏感，建议 Router 对此类内容
 * 采用更严格的评估阈值或更多的反思迭代。
 */
@Primitive(type = PrimitiveType.EVALUATOR, name = "importance")
public class ImportanceEvaluator implements Evaluator {

    private static final Set<String> SECURITY_TERMS = Set.of(
            "password", "secret", "token", "api key", "credential",
            "authentication", "authorization", "permission", "access control",
            "vulnerability", "exploit", "injection", "xss", "csrf",
            "encryption", "decrypt", "private key", "public key"
    );

    private static final Set<String> DATA_INTEGRITY_TERMS = Set.of(
            "database", "transaction", "commit", "rollback", "migration",
            "backup", "restore", "consistency", "integrity", "persist",
            "schema", "migrate", "ddl", "dml"
    );

    private static final Set<String> URGENCY_TERMS = Set.of(
            "urgent", "critical", "asap", "immediately", "emergency",
            "severe", "breaking", "outage", "incident", "down"
    );

    private static final Set<String> BUSINESS_CRITICAL_TERMS = Set.of(
            "payment", "order", "invoice", "billing", "revenue",
            "compliance", "regulatory", "audit", "legal", "contract",
            "money", "financial", "transaction", "refund"
    );

    private static final Set<String> CATEGORIES = Set.of(
            "安全", "数据完整性", "紧急", "业务关键", "技术问题", "一般咨询"
    );

    @Override
    public Evaluation evaluate(ReflectionContext ctx) {
        String output = ctx.getCurrentOutput();
        Evaluation eval = new Evaluation();
        eval.setRawOutput(output);
        eval.setDimensions(new LinkedHashMap<>());
        eval.setIssues(new ArrayList<>());

        if (output == null || output.isBlank()) {
            eval.setScore(0.5);
            eval.setImportanceScore(0.0);
            eval.setSuccess(true);
            eval.setCategory("空内容");
            eval.setReasoning("空输出无重要性");
            return eval;
        }

        String lower = output.toLowerCase();
        double importance = 0.0;
        List<String> matchedCategories = new ArrayList<>();

        // 安全相关
        long securityHits = SECURITY_TERMS.stream().filter(lower::contains).count();
        if (securityHits > 0) {
            importance += 0.3;
            matchedCategories.add("安全");
        }

        // 数据完整性
        long dataHits = DATA_INTEGRITY_TERMS.stream().filter(lower::contains).count();
        if (dataHits > 0) {
            importance += 0.2;
            matchedCategories.add("数据完整性");
        }

        // 紧急标记
        long urgencyHits = URGENCY_TERMS.stream().filter(lower::contains).count();
        if (urgencyHits > 0) {
            importance += 0.2;
            matchedCategories.add("紧急");
        }

        // 业务关键
        long businessHits = BUSINESS_CRITICAL_TERMS.stream().filter(lower::contains).count();
        if (businessHits > 0) {
            importance += 0.3;
            matchedCategories.add("业务关键");
        }

        // 长度奖励（长内容通常承载更复杂/重要的话题）
        int len = output.length();
        if (len > 2000) importance += 0.1;
        if (len > 5000) importance += 0.1;

        importance = Math.min(1.0, importance);

        // 确定类别
        String category;
        if (matchedCategories.isEmpty()) {
            category = len > 500 ? "技术问题" : "一般咨询";
        } else {
            category = String.join("+", matchedCategories);
        }

        eval.setImportanceScore(importance);
        eval.setCategory(category);
        eval.setScore(0.8);  // 重要性评估不判断对错，分数默认为高
        eval.setSuccess(true);

        StringBuilder reason = new StringBuilder("重要性评估：");
        if (importance >= 0.5) {
            reason.append("高重要性（").append(String.format("%.2f", importance)).append("）— ")
                  .append(category);
        } else if (importance >= 0.2) {
            reason.append("中等重要性（").append(String.format("%.2f", importance)).append("）");
        } else {
            reason.append("低重要性（常规内容）");
        }
        eval.setReasoning(reason.toString());
        eval.getDimensions().put("importance", importance);
        eval.getDimensions().put("securityHits", (double) securityHits);
        eval.getDimensions().put("businessHits", (double) businessHits);

        return eval;
    }
}
