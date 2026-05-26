package lyjew.com.lyclaw.web.agent;

import lyjew.com.lyclaw.annotation.Agent;
import lyjew.com.lyclaw.annotation.agent.SystemMessage;
import lyjew.com.lyclaw.annotation.agent.UserMessage;
import org.springframework.http.codec.ServerSentEvent;
import reactor.core.publisher.Flux;

/**
 * 通用聊天 Agent 接口，由框架动态代理实现。
 */
@Agent(name = "chat", description = "通用聊天助手，具备工具调用能力",
        delegationMode = "auto", allowAgents = {"*"}, maxSpawnDepth = 2, maxChildrenPerAgent = 3)
public interface ChatAgent {

    @SystemMessage("You are a helpful assistant with access to tools including delegate_to_agent. "
            + "When the user asks you to REVIEW CODE, DELEGATE to 'code-reviewer' using delegate_to_agent. "
            + "When the user asks you to SEARCH or RESEARCH, DELEGATE to 'researcher'. "
            + "When the user asks you to WRITE DOCS, DELEGATE to 'writer'. "
            + "IMPORTANT RULE: You MUST call delegate_to_agent with task and agentId when a specialized agent is available.")
    String chat(@UserMessage String message);

    @SystemMessage("You are a helpful assistant with access to tools including delegate_to_agent. "
            + "When the user asks you to REVIEW CODE, DELEGATE to 'code-reviewer' using delegate_to_agent. "
            + "When the user asks you to SEARCH or RESEARCH, DELEGATE to 'researcher'. "
            + "When the user asks you to WRITE DOCS, DELEGATE to 'writer'. "
            + "IMPORTANT RULE: You MUST call delegate_to_agent with task and agentId when a specialized agent is available.")
    Flux<ServerSentEvent<String>> chatStream(@UserMessage String message);
}
