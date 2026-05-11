package lyjew.com.lyclaw.agent.communication;

import lombok.Builder;
import lombok.Data;

/**
 * 对等代理响应，封装单个代理在共识轮次中的回复及其权重信息。
 *
 * PeerResponse 是共识引擎的输入数据单元，不仅包含代理的回复内容，
 * 还附带该回复的置信度、代理的能力权重和历史准确率。共识引擎利用
 * 这三个权重指标来加权计算最终决策：confidence 反映代理对自身回答
 * 的确信程度，capabilityWeight 反映代理在该任务领域的专业程度，
 * historicalAccuracy 反映代理长期的历史表现。三者结合起来形成综合
 * 评分，以产生更可靠的共识结果。
 *
 * 使用 Lombok 自动生成 getter/setter/Builder 等方法。
 */
@Data
@Builder
public class PeerResponse {
    /** 响应的代理唯一标识 */
    private String agentId;
    /** 代理的回复内容 */
    private String content;
    /** 代理对自身回复的置信度（0.0 ~ 1.0） */
    private double confidence;
    /** 代理在该任务领域的能力权重（0.0 ~ 1.0） */
    private double capabilityWeight;
    /** 代理的历史任务执行准确率（0.0 ~ 1.0） */
    private double historicalAccuracy;
}
