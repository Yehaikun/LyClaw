package lyjew.com.lyclaw.pipeline.impl;

import lyjew.com.lyclaw.context.ChatContext;
import lyjew.com.lyclaw.dto.ChatResult;
import lyjew.com.lyclaw.interceptor.impl.InterceptorChain;
import lyjew.com.lyclaw.model.Message;
import lyjew.com.lyclaw.pipeline.Chain;
import lyjew.com.lyclaw.pipeline.PipelineStage;
import lyjew.com.lyclaw.tool.ToolResult;
import lyjew.com.lyclaw.tracing.TraceContext;

import java.util.Collections;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Pipeline 第五阶段（最终阶段）—— 响应构建阶段。
 *
 * <p>负责：
 * <ol>
 *   <li>从 ChatContext 提取 AI 回复内容（最后一条 assistant 消息的 content）</li>
 *   <li>构建 ChatResult</li>
 *   <li>执行所有拦截器的 postHandle()</li>
 * </ol>
 * </p>
 *
 * @since 1.0
 * @author LyClaw Team
 * @see ChatResult
 * @see InterceptorChain
 */
@Component
public class ResponseBuildStage implements PipelineStage {

    private final InterceptorChain interceptorChain;

    public ResponseBuildStage(InterceptorChain interceptorChain) {
        this.interceptorChain = interceptorChain;
    }

    @Override
    public void process(ChatContext context, Chain chain) {
        // 提取最后一条 assistant 消息的文本内容
        String responseText = extractLastAssistantMessage(context);
        context.getTracing().markEnd();

        // 构建 ChatResult — 5 参数构造器
        ChatResult result = new ChatResult(
                responseText,
                "stop",
                "prompt=0 completion=0 total=0",
                Collections.emptyList(),
                context.getTracing().getTotalDuration()
        );

        context.setResult(result);

        // 执行所有拦截器的 postHandle
        interceptorChain.postHandle(context, result);

        chain.next(context);
    }

    /**
     * 从消息列表中提取最后一条 assistant 角色的文本内容。
     */
    private String extractLastAssistantMessage(ChatContext context) {
        for (int i = context.getRequest().getMessages().size() - 1; i >= 0; i--) {
            Message msg = context.getRequest().getMessages().get(i);
            if ("assistant".equals(msg.getRole())) {
                return msg.getContent();
            }
        }
        return "";
    }

    @Override
    public int getOrder() {
        return 4; // 第五阶段
    }

    @Override
    public String getStageName() {
        return "ResponseBuild";
    }
}