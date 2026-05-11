package lyjew.com.lyclaw.agent.communication;

import lombok.Builder;
import lombok.Data;

import java.util.Map;

/**
 * 投票结果，封装多代理投票环节的统计结果。
 *
 * VoteResult 记录了投票环节的完整统计信息，包括胜出代理的标识、
 * 各候选方的得票分布（Map 从 agentId 到得票数或得分）、胜出方的
 * 最终得分和参与投票的总人数。该结果用于协作模式中的最终决策环节，
 * 确保表决过程透明可追溯。voteDistribution 保存了每个候选方的投票
 * 详情，便于后续审计或展示。
 *
 * 使用 Lombok 自动生成 getter/setter/Builder 等方法。
 */
@Data
@Builder
public class VoteResult {
    /** 胜出方的代理标识 */
    private String winnerAgentId;
    /** 各候选方的得票分布：key 为 agentId，value 为得票数或加权得分 */
    private Map<String, Double> voteDistribution;
    /** 胜出方的最终得分 */
    private double winnerScore;
    /** 参与投票的总代理数 */
    private int totalVoters;
}
