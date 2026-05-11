package lyjew.com.lyclaw.agent.communication;

import lombok.Builder;
import lombok.Data;

/**
 * 共识结果，封装多代理共识决策的最终输出。
 *
 * ConsensusResult 是共识引擎完成一个共识轮次后的产物，记录了共识是否
 * 达成、最终决策内容、各代理响应的一致程度（agreementRate）、共识达成
 * 所经历的轮次数以及占多数的代理标识。调用方可以根据 consensusReached
 * 判断是否需要再进行一轮协商，或者接受当前结果作为最终输出。
 *
 * 使用 Lombok 自动生成 getter/setter/Builder 等方法。
 */
@Data
@Builder
public class ConsensusResult {
    /** 共识是否已达成 */
    private boolean consensusReached;
    /** 最终决策内容 */
    private String decision;
    /** 响应一致率（0.0 ~ 1.0），反映各代理回答的一致程度 */
    private double agreementRate;
    /** 达成共识所经历的轮次数 */
    private int roundsTaken;
    /** 占多数的代理标识，未达成共识时为 null */
    private String majorityAgentId;
}
