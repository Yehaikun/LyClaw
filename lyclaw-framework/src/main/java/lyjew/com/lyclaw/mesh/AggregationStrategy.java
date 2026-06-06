package lyjew.com.lyclaw.mesh;

import java.util.List;

/**
 * 聚合策略 SPI —— 定义 FAN_OUT 模式中多 Agent 结果的合并方式。
 *
 * <p>用户可以实现此接口提供自定义聚合逻辑。框架内置 4 种策略：</p>
 * <ul>
 *   <li>{@link #VOTE} — 投票：选择出现次数最多的结果</li>
 *   <li>{@link #SUM} — 拼接：将所有结果按顺序拼接</li>
 *   <li>{@link #LLM} — LLM综合：调用 LLM 综合多个结果</li>
 *   <li>{@link #FIRST} — 首条：返回第一个完成的结果</li>
 * </ul>
 *
 * <p>通过 {@link OrchestrationSpec#getAggregationStrategy()} 指定策略名称。</p>
 */
public interface AggregationStrategy {

    /** 策略名称（用于在 OrchestrationSpec 中引用） */
    String name();

    /**
     * 聚合多个 Agent 的结果。
     *
     * @param results 所有 Agent 的返回消息
     * @param spec    原始编排规格
     * @return 聚合后的结果字符串
     */
    String aggregate(List<AgentMessage> results, OrchestrationSpec spec);

    // ── 内置策略常量 ──

    /** 投票：选择出现次数最多的结果 */
    static AggregationStrategy vote() { return new VoteStrategy(); }
    /** 拼接：将所有结果按顺序拼接 */
    static AggregationStrategy sum() { return new SumStrategy(); }
    /** 首条：返回第一个完成的结果 */
    static AggregationStrategy first() { return new FirstStrategy(); }
    /** LLM综合：调用 LLM 综合多个结果（需提供 ChatFacade） */
    static AggregationStrategy llm(Object chatFacade) { return new LLMAggregationStrategy(chatFacade); }

    /** 根据名称获取内置策略 */
    static AggregationStrategy byName(String name) {
        return switch (name != null ? name.toLowerCase() : "vote") {
            case "vote" -> vote();
            case "sum", "concat" -> sum();
            case "first" -> first();
            case "llm" -> throw new IllegalArgumentException(
                    "LLM aggregation requires a ChatFacade, use AggregationStrategy.llm(chatFacade)");
            default -> vote();
        };
    }
}

// ── 内置实现 ──

class VoteStrategy implements AggregationStrategy {
    @Override
    public String name() { return "vote"; }

    @Override
    public String aggregate(List<AgentMessage> results, OrchestrationSpec spec) {
        if (results == null || results.isEmpty()) return "";
        // 简单投票：选最长的结果（作为代理方案）
        return results.stream()
                .filter(r -> r.getType() == MessageType.RESPONSE)
                .max(java.util.Comparator.comparingInt(
                        r -> r.getPayload() != null ? r.getPayload().length() : 0))
                .map(AgentMessage::getPayload)
                .orElse("");
    }
}

class SumStrategy implements AggregationStrategy {
    @Override
    public String name() { return "sum"; }

    @Override
    public String aggregate(List<AgentMessage> results, OrchestrationSpec spec) {
        if (results == null || results.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < results.size(); i++) {
            AgentMessage r = results.get(i);
            if (r.getPayload() == null) continue;
            if (sb.length() > 0) sb.append("\n\n---\n\n");
            sb.append("结果").append(i + 1).append(":\n").append(r.getPayload());
        }
        return sb.toString();
    }
}

class FirstStrategy implements AggregationStrategy {
    @Override
    public String name() { return "first"; }

    @Override
    public String aggregate(List<AgentMessage> results, OrchestrationSpec spec) {
        return results.stream()
                .filter(r -> r.getType() == MessageType.RESPONSE)
                .findFirst()
                .map(AgentMessage::getPayload)
                .orElse("");
    }
}

class LLMAggregationStrategy implements AggregationStrategy {
    private final Object chatFacade;

    LLMAggregationStrategy(Object chatFacade) { this.chatFacade = chatFacade; }

    @Override
    public String name() { return "llm"; }

    @Override
    public String aggregate(List<AgentMessage> results, OrchestrationSpec spec) {
        // Phase 3: 实现 LLM 综合
        return SumStrategy.class.cast(new SumStrategy()).aggregate(results, spec);
    }
}
