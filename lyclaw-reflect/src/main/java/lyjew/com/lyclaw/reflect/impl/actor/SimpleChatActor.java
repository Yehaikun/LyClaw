package lyjew.com.lyclaw.reflect.impl.actor;

import static lyjew.com.lyclaw.react.SseEventTypes.MESSAGE;
import static lyjew.com.lyclaw.react.SseEventTypes.THINKING;

import lyjew.com.lyclaw.annotation.reflect.Primitive;
import lyjew.com.lyclaw.chat.ChatFacade;
import lyjew.com.lyclaw.model.ChatRequest;
import lyjew.com.lyclaw.model.Message;
import lyjew.com.lyclaw.model.ModelResponse;

import java.time.Duration;
import java.util.List;
import java.util.function.Consumer;

import lyjew.com.lyclaw.reflect.model.ActorResult;
import lyjew.com.lyclaw.reflect.model.ReflectionContext;
import lyjew.com.lyclaw.reflect.primitive.Actor;
import lyjew.com.lyclaw.reflect.topology.PrimitiveType;
import org.springframework.http.codec.ServerSentEvent;

/**
 * 单次 LLM 调用的 Actor 实现 — 不使用工具，不进行推理循环。
 *
 * <p>适用场景：
 * <ul>
 *   <li>纯文本生成任务（写作、翻译、摘要）</li>
 *   <li>passthrough 拓扑中的快速通道</li>
 *   <li>不需要工具调用的简单对话</li>
 * </ul>
 *
 * <p>与 {@link ReActActor} 对比：SimpleChatActor 不做 ReAct 推理-行动循环，
 * 直接将 systemPrompt + userMessage 发送给 ChatFacade 并返回响应文本。
 */
@Primitive(type = PrimitiveType.ACTOR, name = "simpleChat")
public class SimpleChatActor implements Actor {

    private final ChatFacade chatFacade;

    public SimpleChatActor(ChatFacade chatFacade) {
        this.chatFacade = chatFacade;
    }

    @Override
    public ActorResult execute(ReflectionContext ctx) {
        ChatRequest request = ChatRequest.builder()
                .systemPrompt(ctx.getSystemPrompt())
                .messages(List.of(Message.user(ctx.getUserMessage())))
                .build();

        ModelResponse response = chatFacade.chat(request);
        return new ActorResult(response.getContent());
    }

    @Override
    public ActorResult executeStream(ReflectionContext ctx, Consumer<ServerSentEvent<String>> chunkSink) {
        ChatRequest request = ChatRequest.builder()
                .systemPrompt(ctx.getSystemPrompt())
                .messages(List.of(Message.user(ctx.getUserMessage())))
                .stream(true)
                .build();

        StringBuilder fullOutput = new StringBuilder();
        chatFacade.chat().prompt()
                .system(ctx.getSystemPrompt())
                .user(ctx.getUserMessage())
                .stream()
                .doOnNext(chunk -> {
                    if (chunk.getContent() != null) {
                        fullOutput.append(chunk.getContent());
                        chunkSink.accept(ServerSentEvent.<String>builder()
                                .event(MESSAGE).data(chunk.getContent()).build());
                    }
                    if (chunk.getThinking() != null) {
                        chunkSink.accept(ServerSentEvent.<String>builder()
                                .event(THINKING).data(chunk.getThinking()).build());
                    }
                })
                .blockLast(Duration.ofMinutes(5));

        return new ActorResult(fullOutput.toString());
    }
}
