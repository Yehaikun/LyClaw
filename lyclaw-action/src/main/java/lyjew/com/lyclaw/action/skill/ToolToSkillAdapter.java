package lyjew.com.lyclaw.action.skill;

import lyjew.com.lyclaw.context.ChatContext;
import lyjew.com.lyclaw.dto.SkillResult;
import lyjew.com.lyclaw.model.ToolCall;
import lyjew.com.lyclaw.skill.Skill;
import lyjew.com.lyclaw.tool.Tool;
import lombok.extern.slf4j.Slf4j;
import lyjew.com.lyclaw.tool.ToolExecutionResult;

import java.util.concurrent.CompletableFuture;

/**
 * 工具到技能的适配器，将 {@link lyjew.com.lyclaw.tool.Tool} 接口适配为
 * {@link lyjew.com.lyclaw.skill.Skill} 接口，使工具能够像技能一样在系统管道中
 * 统一调度和执行。
 *
 * <p>适配器会从上下文中提取最后一次用户消息作为工具调用的参数，
 * 并将工具的执行结果转换为标准的 {@link lyjew.com.lyclaw.dto.SkillResult}。</p>
 */
@Slf4j
public class ToolToSkillAdapter implements Skill {

    /** 被适配的底层工具实例 */
    private final Tool tool;
    /** 技能类型标签，默认为 "TOOL"，用于区分不同类型的技能 */
    private final String skillType;

    /**
     * 使用默认类型 "TOOL" 创建适配器。
     *
     * @param tool 要适配的底层工具
     */
    public ToolToSkillAdapter(Tool tool) {
        this(tool, "TOOL");
    }

    /**
     * 使用指定类型创建适配器。
     *
     * @param tool      要适配的底层工具
     * @param skillType 技能类型标签
     */
    public ToolToSkillAdapter(Tool tool, String skillType) {
        this.tool = tool;
        this.skillType = skillType;
    }

    /** @return 技能唯一标识，使用工具名称 */
    @Override
    public String getSkillId() {
        return tool.getName();
    }

    /** @return 技能显示名称，使用工具名称 */
    @Override
    public String getName() {
        return tool.getName();
    }

    /** @return 技能描述，来自工具的定義描述 */
    @Override
    public String getDescription() {
        return tool.getDefinition().getDescription();
    }

    /** @return 技能类型标签 */
    public String getType() {
        return skillType;
    }

    /**
     * 异步执行该技能。
     *
     * <p>从上下文中提取参数构建 {@link ToolCall}，调用底层工具执行，
     * 并将结果包装为 {@link SkillResult}。执行过程会记录耗时。</p>
     *
     * @param context 聊天上下文，用于提取工具参数
     * @return 包含执行结果的 CompletableFuture
     */
    @Override
    public CompletableFuture<SkillResult> execute(ChatContext context) {
        return CompletableFuture.supplyAsync(() -> {
            long startTime = System.currentTimeMillis();
            try {
                // 构建工具调用请求，从上下文中提取参数
                ToolCall toolCall = ToolCall.builder()
                        .name(tool.getName())
                        .arguments(extractArguments(context))
                        .build();
                log.debug("适配执行: tool={}", tool.getName());

                // 执行底层工具并计算耗时
                ToolExecutionResult result = tool.execute(toolCall, context);
                long elapsed = System.currentTimeMillis() - startTime;
                // 根据成功/失败状态构建不同的 SkillResult
                return new SkillResult(
                        tool.getName(),
                        result.isSuccess(),
                        result.isSuccess() ? result.getResult() : "",
                        result.isSuccess() ? "" : result.getError(),
                        result.getTokenUsage(),
                        elapsed
                );
            } catch (Exception e) {
                long elapsed = System.currentTimeMillis() - startTime;
                log.error("适配执行失败: tool={}", tool.getName(), e);
                return new SkillResult(
                        tool.getName(), false, "",
                        "工具适配执行失败: " + e.getMessage(), 0, elapsed
                );
            }
        });
    }

    /**
     * 从聊天上下文中提取工具调用的参数。
     *
     * <p>逆向遍历消息列表，返回最后一条 role 为 "user" 的消息内容。
     * 如果上下文为空或无用户消息，则返回空字符串。</p>
     *
     * @param context 聊天上下文
     * @return 提取到的用户消息内容，或空字符串
     */
    protected String extractArguments(ChatContext context) {
        // 上下文或请求为空时返回空
        if (context == null || context.getRequest() == null) {
            return "";
        }
        var messages = context.getRequest().getMessages();
        if (messages == null) return "";
        // 从后向前遍历消息列表，找到最后一条用户消息
        for (int i = messages.size() - 1; i >= 0; i--) {
            if ("user".equals(messages.get(i).getRole())) {
                return messages.get(i).getContent();
            }
        }
        return "";
    }

    /**
     * 获取底层被适配的工具实例。
     *
     * @return 底层工具对象
     */
    public Tool getUnderlyingTool() {
        return tool;
    }
}
