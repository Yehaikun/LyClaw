package lyjew.com.lyclaw.action.controller;

import lyjew.com.lyclaw.action.ActionExecutor;
import lyjew.com.lyclaw.action.SkillExecuteRequest;
import lyjew.com.lyclaw.action.ToolExecuteRequest;
import lyjew.com.lyclaw.action.impl.ActionExecutorImpl;
import lyjew.com.lyclaw.action.impl.DefaultToolRegistry;
import lyjew.com.lyclaw.action.tool.ToolResult;
import lyjew.com.lyclaw.dto.SkillResult;
import lyjew.com.lyclaw.model.ToolDefinition;
import lyjew.com.lyclaw.security.SandboxLevel;
import lyjew.com.lyclaw.skill.SkillRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/action")
public class ActionController {

    private final ActionExecutor actionExecutor;
    private final ActionExecutorImpl actionExecutorImpl;
    private final DefaultToolRegistry toolRegistry;
    private final SkillRegistry skillRegistry;

    public ActionController(ActionExecutor actionExecutor,
                            ActionExecutorImpl actionExecutorImpl,
                            DefaultToolRegistry toolRegistry,
                            SkillRegistry skillRegistry) {
        this.actionExecutor = actionExecutor;
        this.actionExecutorImpl = actionExecutorImpl;
        this.toolRegistry = toolRegistry;
        this.skillRegistry = skillRegistry;
    }

    @PostMapping("/execute-tool")
    public Mono<ToolResult> executeTool(@RequestBody ToolExecuteRequest request) {
        log.info("收到工具执行请求: tool={}, level={}",
                request.getToolName(), request.getSandboxLevel());

        SandboxLevel level = SandboxLevel.NONE;
        if (request.getSandboxLevel() != null && !request.getSandboxLevel().isBlank()) {
            try {
                level = SandboxLevel.valueOf(request.getSandboxLevel().toUpperCase());
            } catch (IllegalArgumentException e) {
                log.warn("无效的沙箱级别: {}, 回退到 NONE", request.getSandboxLevel());
            }
        }

        return Mono.fromFuture(actionExecutor.executeTool(
                request.getToolName(),
                request.getArgs() != null ? request.getArgs() : Map.of(),
                level));
    }

    @PostMapping("/execute-skill")
    public Mono<SkillResult> executeSkill(@RequestBody SkillExecuteRequest request) {
        log.info("收到技能执行请求: skillId={}", request.getSkillId());
        return Mono.fromFuture(actionExecutor.executeSkill(request.getSkillId(), null));
    }

    @GetMapping("/tools")
    public Mono<List<ToolDefinition>> getTools() {
        List<ToolDefinition> definitions = toolRegistry.getAllDefinitions();
        return Mono.just(definitions);
    }

    @GetMapping("/skills")
    public Mono<List<Map<String, Object>>> getSkills() {
        List<Map<String, Object>> skills = actionExecutorImpl.getRegisteredSkills();
        return Mono.just(skills);
    }

    @GetMapping("/sandbox/health")
    public Mono<Map<String, Boolean>> getSandboxHealth() {
        return Mono.just(Map.of("healthy", actionExecutorImpl.isSandboxHealthy()));
    }

    @GetMapping("/tools/stats")
    public Mono<Map<String, Object>> getToolStats() {
        return Mono.just(Map.of(
                "totalCount", toolRegistry.size(),
                "categoryStats", toolRegistry.getCategoryStats()
        ));
    }
}
