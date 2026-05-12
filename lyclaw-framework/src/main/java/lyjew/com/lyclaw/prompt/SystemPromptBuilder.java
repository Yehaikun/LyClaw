package lyjew.com.lyclaw.prompt;

/**
 * 系统提示词构建器接口，将领域信息组装为结构化的系统提示词。
 *
 * <p>不同场景（任务分解、代码审查、记忆提取等）需要不同的系统提示词模板。
 * 实现此接口可将提示词构建逻辑从业务代码中分离，便于测试和替换。
 */
@FunctionalInterface
public interface SystemPromptBuilder {

    /**
     * 构建系统提示词。
     *
     * @param context 构建上下文（如任务描述、可用工具列表、约束条件等）
     * @return 组装好的系统提示词字符串
     */
    String build(PromptContext context);
}
