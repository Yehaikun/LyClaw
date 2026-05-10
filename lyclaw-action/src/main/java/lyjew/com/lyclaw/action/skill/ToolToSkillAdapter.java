package lyjew.com.lyclaw.action.skill;

import lyjew.com.lyclaw.context.ChatContext;
import lyjew.com.lyclaw.dto.SkillResult;
import lyjew.com.lyclaw.model.ToolCall;
import lyjew.com.lyclaw.skill.Skill;
import lyjew.com.lyclaw.tool.Tool;
import lyjew.com.lyclaw.tool.ToolResult;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.CompletableFuture;

@Slf4j
public class ToolToSkillAdapter implements Skill {

    private final Tool tool;
    private final String skillType;

    public ToolToSkillAdapter(Tool tool) {
        this(tool, "TOOL");
    }

    public ToolToSkillAdapter(Tool tool, String skillType) {
        this.tool = tool;
        this.skillType = skillType;
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

    public String getType() {
        return skillType;
    }

    @Override
    public CompletableFuture<SkillResult> execute(ChatContext context) {
        return CompletableFuture.supplyAsync(() -> {
            long startTime = System.currentTimeMillis();
            try {
                ToolCall toolCall = ToolCall.builder()
                        .name(tool.getName())
                        .arguments(extractArguments(context))
                        .build();
                log.debug("适配执行: tool={}", tool.getName());

                ToolResult result = tool.execute(toolCall, context);
                long elapsed = System.currentTimeMillis() - startTime;
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

    protected String extractArguments(ChatContext context) {
        if (context == null || context.getRequest() == null) {
            return "";
        }
        var messages = context.getRequest().getMessages();
        if (messages == null) return "";
        for (int i = messages.size() - 1; i >= 0; i--) {
            if ("user".equals(messages.get(i).getRole())) {
                return messages.get(i).getContent();
            }
        }
        return "";
    }

    public Tool getUnderlyingTool() {
        return tool;
    }
}
