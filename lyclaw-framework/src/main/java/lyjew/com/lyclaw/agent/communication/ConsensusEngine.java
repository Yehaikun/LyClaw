package lyjew.com.lyclaw.agent.communication;

import lyjew.com.lyclaw.agent.AgentHandle;

import java.util.List;

/**
 * 共识引擎接口，在多代理通信中负责达成一致性决策。
 *
 * ConsensusEngine 是 LyClaw 通信子系统的核心组件，用于在多个代理的
 * 响应中判断共识是否达成并进行冲突消解。工作流程分为三步：首先调用
 * hasConsensus 快速判断当前轮次的响应是否已形成共识；如果未形成，
 * 则调用 resolve 进行冲突消解并产生最终决策；vote 投票方法用于在
 * 多个候选方案中选出胜者。共识引擎是投票模式、辩论模式等协作方式的
 * 基础组件，确保多代理系统能够产生一致、可追溯的输出结果。
 */
public interface ConsensusEngine {

    /**
     * 判断给定的一组对等代理响应是否已达成共识。
     *
     * @param responses 各代理的响应列表
     * @return 已达成共识返回 true，否则返回 false
     */
    boolean hasConsensus(List<PeerResponse> responses);

    /**
     * 在给定轮次对代理响应进行冲突消解，产出共识结果。
     *
     * @param responses 各代理的响应列表
     * @param round     当前的共识轮次
     * @return 包含共识是否达成、最终决策及同意率等信息的 ConsensusResult
     */
    ConsensusResult resolve(List<PeerResponse> responses, int round);

    /**
     * 在多个候选方之间进行投票，选出得分最高的候选方。
     *
     * @param candidates 候选方的响应列表
     * @param voters     有权投票的代理列表
     * @return 投票结果，包含胜出方及其得票分布
     */
    VoteResult vote(List<PeerResponse> candidates, List<AgentHandle> voters);
}
