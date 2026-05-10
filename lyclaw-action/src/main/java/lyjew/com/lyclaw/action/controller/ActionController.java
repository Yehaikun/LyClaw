package lyjew.com.lyclaw.action.controller;

import lyjew.com.lyclaw.action.ActionExecutor;
import lyjew.com.lyclaw.action.SkillExecuteRequest;
import lyjew.com.lyclaw.action.ToolExecuteRequest;
import lyjew.com.lyclaw.action.tool.ToolResult;
import lyjew.com.lyclaw.dto.SkillResult;
import lyjew.com.lyclaw.security.SandboxLevel;
import lyjew.com.lyclaw.tool.ToolDefinition;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/action")
public class ActionController {

    private final ActionExecutor actionExecutor;

    public ActionController(ActionExecutor actionExecutor) {
        this.actionExecutor = actionExecutor;
    }

    @PostMapping("/execute-tool")
    public Mono<ToolResult> executeTool(@RequestBody ToolExecuteRequest request) {
        SandboxLevel level = request.getSandboxLevel() != null
                ? SandboxLevel.valueOf(request.getSandboxLevel().toUpperCase())
                : SandboxLevel.NONE;
        return Mono.fromFuture(actionExecutor.executeTool(
                request.getToolName(), request.getArgs(), level));
    }

    @PostMapping("/execute-skill")
    public Mono<SkillResult> executeSkill(@RequestBody SkillExecuteRequest request) {
        return Mono.fromFuture(actionExecutor.executeSkill(
                request.getSkillId(), null));
    }

    @GetMapping("/tools")
    public Mono<List<ToolDefinition>> getTools() {
        // Delegated to the executor; stub returns empty for now
        return Mono.just(List.of());
    }

    @GetMapping("/skills")
    public Mono<List<Map<String, Object>>> getSkills() {
        // Stub: return empty list
        return Mono.just(List.of());
    }
}
