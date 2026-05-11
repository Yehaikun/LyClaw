package lyjew.com.lyclaw.agent.communication;

import lyjew.com.lyclaw.agent.AgentHandle;

import java.util.List;

public interface ConsensusEngine {

    boolean hasConsensus(List<PeerResponse> responses);
    ConsensusResult resolve(List<PeerResponse> responses, int round);
    VoteResult vote(List<PeerResponse> candidates, List<AgentHandle> voters);
}
