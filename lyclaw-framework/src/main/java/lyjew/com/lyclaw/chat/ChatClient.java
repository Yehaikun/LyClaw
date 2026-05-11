package lyjew.com.lyclaw.chat;

import lyjew.com.lyclaw.model.Message;
import lyjew.com.lyclaw.model.ModelResponse;
import lyjew.com.lyclaw.model.ToolDefinition;

import reactor.core.publisher.Flux;

import java.util.List;

/**
 * 高层流式对话门面，业务代码操作 AI 模型的推荐入口。
 *
 * <p>提供流式 Builder API，支持链式调用 .prompt().user("...").system("...").tools(...).stream()。
 * 内部通过 ChatFacade 解析路由决策、选择模型并执行调用。
 *
 * <p>设计参考：Spring AI ChatClient + LangChain BaseChatModel
 */
public interface ChatClient {

    /** 开始一次对话 */
    ChatRequestBuilder prompt();

    /**
     * 流式请求构建器，通过链式调用配置请求参数。
     */
    interface ChatRequestBuilder {

        /** 添加用户消息 */
        ChatRequestBuilder user(String message);

        /** 设置系统提示词 */
        ChatRequestBuilder system(String systemPrompt);

        /** 设置完整消息历史 */
        ChatRequestBuilder messages(List<Message> messages);

        /** 设置可用工具定义 */
        ChatRequestBuilder tools(List<ToolDefinition> tools);

        /** 设置采样温度 */
        ChatRequestBuilder temperature(double temperature);

        /** 设置最大生成 Token 数 */
        ChatRequestBuilder maxTokens(int maxTokens);

        /** 启用/禁用思考模式 */
        ChatRequestBuilder thinking(boolean enabled);

        /** 设置扩展参数 */
        ChatRequestBuilder option(String key, Object value);

        /** 执行流式调用，返回结构化事件流 */
        Flux<ModelResponse> stream();

        /** 执行同步调用 */
        ModelResponse call();
    }
}
