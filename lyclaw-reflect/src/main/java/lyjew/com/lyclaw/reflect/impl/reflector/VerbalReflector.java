package lyjew.com.lyclaw.reflect.impl.reflector;

import lyjew.com.lyclaw.annotation.reflect.Primitive;
import lyjew.com.lyclaw.chat.ChatFacade;
import lyjew.com.lyclaw.model.ChatRequest;
import lyjew.com.lyclaw.model.Message;
import lyjew.com.lyclaw.model.ModelResponse;
import lyjew.com.lyclaw.reflect.model.Evaluation;
import lyjew.com.lyclaw.reflect.model.Issue;
import lyjew.com.lyclaw.reflect.model.ReflectionContext;
import lyjew.com.lyclaw.reflect.primitive.Reflector;
import lyjew.com.lyclaw.reflect.topology.PrimitiveType;

import java.time.Duration;
import java.util.List;
import java.util.function.Consumer;

/**
 * 调用 LLM 对评估结果进行根因分析，生成自然语言反思建议。
 *
 * <p>工作流程：
 * <ol>
 *   <li>将用户请求、Actor 输出、Evaluator 评分及检测到的问题列表组装为反思 prompt</li>
 *   <li>调用 LLM（低温度 0.3 以获得确定性分析）输出根因分析和改进建议</li>
 *   <li>返回的反思文本会暂存在 ReflectionContext.currentReflection 中，
 *       下一轮 Actor 执行时注入到 system prompt</li>
 * </ol>
 *
 * <p>输出截断：Actor 输出超过 4000 字符时自动截断，防止 prompt 过长。
 */
@Primitive(type = PrimitiveType.REFLECTOR, name = "verbal", isDefault = true)
public class VerbalReflector implements Reflector {

    private final ChatFacade chatFacade;

    public VerbalReflector(ChatFacade chatFacade) {
        this.chatFacade = chatFacade;
    }

    @Override
    public String reflect(ReflectionContext ctx, Evaluation evaluation) {
        String prompt = buildReflectionPrompt(ctx, evaluation);

        ChatRequest request = ChatRequest.builder()
                .systemPrompt("你是一个反思分析师。请简洁地分析失败原因并给出具体的改进建议，避免泛泛而谈。")
                .messages(List.of(Message.user(prompt)))
                .temperature(0.3)  // 低温度获得确定性分析
                .build();

        ModelResponse response = chatFacade.chat(request);
        return response.getContent() != null ? response.getContent() : "";
    }

    @Override
    public String reflectStream(ReflectionContext ctx, Evaluation evaluation,
                                 Consumer<String> chunkSink) {
        String prompt = buildReflectionPrompt(ctx, evaluation);
        StringBuilder full = new StringBuilder();

        try {
            chatFacade.chat().prompt()
                    .system("你是一个反思分析师。请简洁地分析失败原因并给出具体的改进建议，避免泛泛而谈。")
                    .user(prompt)
                    .temperature(0.3)
                    .stream()
                    .doOnNext(chunk -> {
                        if (chunk.getContent() != null) {
                            full.append(chunk.getContent());
                            chunkSink.accept(chunk.getContent());
                        }
                    })
                    .blockLast(Duration.ofMinutes(2));
        } catch (Exception e) {
            if (isInterrupted(e)) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("Reflector流式调用被取消", e);
            }
            throw new RuntimeException("Reflector流式执行失败", e);
        }

        return full.toString();
    }

    private boolean isInterrupted(Throwable err) {
        Throwable cause = err;
        while (cause != null) {
            if (cause instanceof InterruptedException) return true;
            cause = cause.getCause();
        }
        return false;
    }

    /** 组装反思 prompt：用户请求 + Actor 输出 + 评分 + 问题列表 */
    private String buildReflectionPrompt(ReflectionContext ctx, Evaluation evaluation) {
        StringBuilder sb = new StringBuilder();

        sb.append("请分析以下 AI 输出为何评分较低，并给出具体的改进建议。\n\n");
        sb.append("用户请求：").append(ctx.getUserMessage()).append("\n\n");

        // Actor 输出
        String output = ctx.getCurrentOutput();
        if (output != null) {
            sb.append("待分析的输出：\n```\n");
            sb.append(output).append("\n");
            sb.append("```\n\n");
        }

        sb.append("评分：").append(String.format("%.2f", evaluation.getScore())).append("\n");
        sb.append("评分理由：").append(evaluation.getReasoning()).append("\n");

        List<Issue> issues = evaluation.getIssues();
        if (issues != null && !issues.isEmpty()) {
            sb.append("\n检测到的问题：\n");
            for (Issue issue : issues) {
                sb.append("- [").append(issue.getSeverity()).append("] ")
                        .append(issue.getCategory()).append(": ")
                        .append(issue.getDescription()).append("\n");
            }
        }

        sb.append("\n请提供：\n");
        sb.append("1. 根因分析：为什么会出现这些问题？\n");
        sb.append("2. 具体改进建议：下一次尝试应该如何调整？\n");
        sb.append("3. 需要避免的关键陷阱。\n");

        return sb.toString();
    }
}
