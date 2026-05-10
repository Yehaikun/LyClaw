package lyjew.com.lyclaw.agent.communication;

import lyjew.com.lyclaw.agent.AgentHandle;
import java.util.List;

/**
 * 共识引擎 —— 多 Agent 协作中的决策共识机制。
 *
 * <p>收敛阈值 67%, 加权投票: score = w1×capability + w2×historicalAccuracy + w3×confidence</p>
 *
 * @since 2.0
 */
public interface ConsensusEngine {

    boolean hasConsensus(List<PeerResponse> responses);

    ConsensusResult resolve(List<PeerResponse> responses, int round);

    VoteResult vote(List<PeerResponse> candidates, List<AgentHandle> voters);
}
