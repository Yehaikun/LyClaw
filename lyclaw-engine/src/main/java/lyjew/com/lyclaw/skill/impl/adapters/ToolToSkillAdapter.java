package lyjew.com.lyclaw.skill.impl.adapters;

import lyjew.com.lyclaw.context.ChatContext;
import lyjew.com.lyclaw.dto.SkillResult;
import lyjew.com.lyclaw.model.ToolCall;
import lyjew.com.lyclaw.skill.Skill;
import lyjew.com.lyclaw.tool.Tool;
import lyjew.com.lyclaw.tool.ToolResult;

import java.util.concurrent.CompletableFuture;

/**
 * Tool→Skill 适配器 —— 将 Tool 接口包装为 Skill 接口。
 *
 * <p><b>设计动机</b>：引擎中同时存在 Tool 和 Skill 两套抽象。
 * 一部分功能已经以 Tool 的形式实现了（如 WebSearchTool），
 * 但上层调度器（TaskPlanner）以 Skill 为最小执行单元。
 * 通过此适配器，已有的 Tool 可以直接作为 Skill 使用，无需重新实现。</p>
 *
 * <p><b>适配方式</b>：
 * <ul>
 *   <li>getSkillId() → tool.getName()</li>
 *   <li>execute(ChatContext) → 构造 ToolCall → tool.execute()</li>
 *   <li>返回 CompletableFuture 包装的 SkillResult</li>
 * </ul>
 * </p>
 *
 * @since 1.0
 * @author LyClaw Team
 * @see Skill
 * @see Tool
 */
public class ToolToSkillAdapter implements Skill {

    /** 被适配的工具实例 */
    private final Tool tool;

    /**
     * 构造适配器。
     *
     * @param tool 要包装为 Skill 的 Tool 实例
     */
    public ToolToSkillAdapter(Tool tool) {
        this.tool = tool;
    }

    @Override
    public String getSkillId() {
        return tool.getName();
    }

    @Override
    public String getName() {
        return tool.getName();
    }

    @Override
    public String getDescription() {
        return tool.getDefinition().getDescription();
    }

    /**
     * 获取技能类型。返回 TOOL（表示底层是 Tool 实现）。
     *
     * @return 技能类型枚举 TOOL
     */
    public String getType() {
        return "TOOL";
    }

    /**
     * 执行技能。将当前对话中的最后一条消息作为参数构造 ToolCall 并执行。
     *
     * @param context 当前对话上下文
     * @return 异步执行结果
     */
    @Override
    public CompletableFuture<SkillResult> execute(ChatContext context) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                // 1. 用 builder 构造 ToolCall
                ToolCall toolCall = ToolCall.builder()
                        .name(tool.getName())
                        .arguments(extractArguments(context))
                        .build();

                // 2. 执行底层的 Tool（2参数：toolCall + context）
                ToolResult result = tool.execute(toolCall, context);

                // 3. 将 ToolResult 转为 SkillResult（6参数构造器）
                return new SkillResult(
                        tool.getName(),
                        result.isSuccess(),
                        result.isSuccess() ? result.getResult() : "",
                        result.isSuccess() ? "" : result.getError(),
                        0,
                        0L
                );
            } catch (Exception e) {
                return new SkillResult(
                        tool.getName(), false, "", e.getMessage(), 0, 0L
                );
            }
        });
    }

    /**
     * 从 ChatContext 中提取工具执行参数。
     * 默认将最后一条用户消息作为参数。
     *
     * @param context 对话上下文
     * @return 参数字符串
     */
    private String extractArguments(ChatContext context) {
        // 获取最后一条用户消息作为工具参数
        for (int i = context.getRequest().getMessages().size() - 1; i >= 0; i--) {
            if ("user".equals(context.getRequest().getMessages().get(i).getRole())) {
                return context.getRequest().getMessages().get(i).getContent();
            }
        }
        return "";
    }
}