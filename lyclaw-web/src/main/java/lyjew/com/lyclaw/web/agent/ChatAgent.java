package lyjew.com.lyclaw.web.agent;

import lyjew.com.lyclaw.annotation.Agent;
import lyjew.com.lyclaw.annotation.agent.SystemMessage;
import lyjew.com.lyclaw.annotation.agent.UserMessage;
import org.springframework.http.codec.ServerSentEvent;
import reactor.core.publisher.Flux;

/**
 * 通用聊天 Agent 接口，由框架动态代理实现。
 */
@Agent(name = "chat", description = "通用聊天助手，具备工具调用能力")
public interface ChatAgent {

    @SystemMessage("You are a helpful assistant with access to tools. Use tools when needed to answer questions accurately.")
    String chat(@UserMessage String message);

    @SystemMessage("You are a helpful assistant with access to tools. Use tools when needed to answer questions accurately.")
    Flux<ServerSentEvent<String>> chatStream(@UserMessage String message);
}
