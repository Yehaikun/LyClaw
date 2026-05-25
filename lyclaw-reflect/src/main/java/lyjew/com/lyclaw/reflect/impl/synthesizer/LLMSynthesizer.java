package lyjew.com.lyclaw.reflect.impl.synthesizer;

import lyjew.com.lyclaw.annotation.reflect.Primitive;
import lyjew.com.lyclaw.chat.ChatFacade;
import lyjew.com.lyclaw.model.ChatRequest;
import lyjew.com.lyclaw.model.Message;
import lyjew.com.lyclaw.model.ModelResponse;
import lyjew.com.lyclaw.reflect.model.Evaluation;
import lyjew.com.lyclaw.reflect.model.ReflectionContext;
import lyjew.com.lyclaw.reflect.primitive.Synthesizer;
import lyjew.com.lyclaw.reflect.topology.PrimitiveType;

import java.time.Duration;
import java.util.List;
import java.util.function.Consumer;

import lyjew.com.lyclaw.common.StringUtils;

/**
 * LLM 驱动的输出合成器 — 将多轮迭代的输出合并为最终结果。
 *
 * <p>工作流程：
 * <ol>
 *   <li>将每一轮输出按格式列出，附上评估分数和问题摘要</li>
 *   <li>调用 LLM（温度 0.3）选择最佳片段或重新撰写最终答案</li>
 *   <li>LLM 被指示优先选择已有内容，避免幻想新的信息</li>
 * </ol>
 *
 * <p>当输出只有一轮时，直接返回该轮结果，跳过 LLM 调用。
 */
@Primitive(type = PrimitiveType.SYNTHESIZER, name = "llmSynthesizer")
public class LLMSynthesizer implements Synthesizer {

    private final ChatFacade chatFacade;

    public LLMSynthesizer(ChatFacade chatFacade) {
        this.chatFacade = chatFacade;
    }

    @Override
    public String synthesize(ReflectionContext ctx, List<String> outputs, List<Evaluation> evaluations) {
        if (outputs.isEmpty()) return "";
        if (outputs.size() == 1) return outputs.get(0); // 只有一轮无需合成

        String prompt = buildSynthesisPrompt(ctx, outputs, evaluations);
        ChatRequest request = ChatRequest.builder()
                .systemPrompt("你是一个输出合成器。请基于多轮迭代结果，合成一个最终的、高质量的答案。优先从已有内容中选取和拼接，避免编造新信息。")
                .messages(List.of(Message.user(prompt)))
                .temperature(0.3)
                .build();

        try {
            ModelResponse response = chatFacade.chat(request);
            return response.getContent() != null ? response.getContent() : lastOrEmpty(outputs);
        } catch (Exception e) {
            return lastOrEmpty(outputs);
        }
    }

    @Override
    public String synthesizeStream(ReflectionContext ctx, List<String> outputs,
                                    List<Evaluation> evaluations, Consumer<String> chunkSink) {
        if (outputs.isEmpty()) return "";
        if (outputs.size() == 1) return outputs.get(0);

        String prompt = buildSynthesisPrompt(ctx, outputs, evaluations);
        try {
            StringBuilder full = new StringBuilder();
            chatFacade.chat().prompt()
                    .system("你是一个输出合成器。请基于多轮迭代结果，合成一个最终的、高质量的答案。优先从已有内容中选取和拼接，避免编造新信息。")
                    .user(prompt).temperature(0.3)
                    .stream()
                    .doOnNext(chunk -> {
                        if (chunk.getContent() != null) {
                            full.append(chunk.getContent());
                            chunkSink.accept(chunk.getContent());
                        }
                    })
                    .blockLast(Duration.ofMinutes(2));
            String content = full.toString();
            return content != null && !content.isBlank() ? content : lastOrEmpty(outputs);
        } catch (Exception e) {
            return lastOrEmpty(outputs);
        }
    }

    /** 组装合成 prompt：列出每一轮输出并指示选择/合并策略 */
    private String buildSynthesisPrompt(ReflectionContext ctx, List<String> outputs,
                                         List<Evaluation> evaluations) {
        StringBuilder sb = new StringBuilder();
        sb.append("用户请求：").append(ctx.getUserMessage()).append("\n\n");
        sb.append("以下是 ").append(outputs.size()).append(" 轮迭代的输出结果：\n\n");

        for (int i = 0; i < outputs.size(); i++) {
            int round = i + 1;
            sb.append("--- 第").append(round).append("轮 ---\n");
            sb.append(StringUtils.truncate(outputs.get(i), 2000)).append("\n");

            if (i < evaluations.size() && evaluations.get(i) != null) {
                Evaluation eval = evaluations.get(i);
                sb.append("评分: ").append(String.format("%.2f", eval.getScore()));
                sb.append(" | 成功: ").append(eval.isSuccess() ? "是" : "否").append("\n");
                if (eval.getReasoning() != null && !eval.getReasoning().isBlank()) {
                    sb.append("评估: ").append(eval.getReasoning()).append("\n");
                }
            }
            sb.append("\n");
        }

        sb.append("请合成最终答案：\n");
        sb.append("1. 优先选择评分最高轮次的内容\n");
        sb.append("2. 如果多轮内容互补，可以合并\n");
        sb.append("3. 修正错误部分，但不要编造新的事实\n");
        sb.append("4. 直接输出最终答案，不要添加\"以下是合成答案\"之类的引导语\n");

        return sb.toString();
    }

    private String lastOrEmpty(List<String> outputs) {
        return outputs.isEmpty() ? "" : outputs.get(outputs.size() - 1);
    }

}
