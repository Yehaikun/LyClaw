package lyjew.com.lyclaw.action.controller;

import lyjew.com.lyclaw.action.ActionExecutor;
import lyjew.com.lyclaw.action.SkillExecuteRequest;
import lyjew.com.lyclaw.action.ToolExecuteRequest;
import lyjew.com.lyclaw.action.impl.ActionExecutorImpl;
import lyjew.com.lyclaw.action.impl.DefaultToolRegistry;
import lyjew.com.lyclaw.tool.ToolExecutionResult;
import lyjew.com.lyclaw.tool.ToolRegistry;
import lyjew.com.lyclaw.dto.SkillResult;
import lyjew.com.lyclaw.model.ToolDefinition;
import lyjew.com.lyclaw.security.SandboxLevel;
import lyjew.com.lyclaw.skill.SkillRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

/**
 * 动作执行 REST 控制器，暴露工具和技能的 HTTP API。
 *
 * <p>提供以下端点：
 * <ul>
 *   <li>POST /api/action/execute-tool -- 执行工具，支持指定沙箱安全级别</li>
 *   <li>POST /api/action/execute-skill -- 执行技能</li>
 *   <li>GET /api/action/tools -- 获取所有已注册的工具定义列表</li>
 *   <li>GET /api/action/skills -- 获取所有已注册的技能摘要</li>
 *   <li>GET /api/action/sandbox/health -- 查询沙箱健康状态</li>
 *   <li>GET /api/action/tools/stats -- 获取工具分类统计</li>
 * </ul>
 * </p>
 *
 * <p>所有响应以 Reactor {@link Mono} 包装，支持响应式非阻塞返回。</p>
 */
@Slf4j
@RestController
@RequestMapping("/api/action")
public class ActionController {

    private final ActionExecutor actionExecutor;
    private final ActionExecutorImpl actionExecutorImpl;
    private final ToolRegistry toolRegistry;
    private final SkillRegistry skillRegistry;

    /**
     * 构造函数，通过依赖注入初始化各组件。
     */
    public ActionController(ActionExecutor actionExecutor,
                            ActionExecutorImpl actionExecutorImpl,
                            ToolRegistry toolRegistry,
                            SkillRegistry skillRegistry) {
        this.actionExecutor = actionExecutor;
        this.actionExecutorImpl = actionExecutorImpl;
        this.toolRegistry = toolRegistry;
        this.skillRegistry = skillRegistry;
    }

    /**
     * 执行单个工具。
     *
     * <p>接收工具名、参数和可选的沙箱安全级别。如果当前工具不可用
     * （策略禁止执行），则跳过并返回错误信息。</p>
     *
     * @param request 工具执行请求（含工具名、参数、沙箱级别）
     * @return 工具执行结果的 Mono
     */
    @PostMapping("/execute-tool")
    public Mono<ToolExecutionResult> executeTool(@RequestBody ToolExecuteRequest request) {
        log.info("收到工具执行请求: tool={}, level={}",
                request.getToolName(), request.getSandboxLevel());

        // 解析沙箱级别，无效时回退到 NONE
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

    /**
     * 执行技能。
     *
     * @param request 技能执行请求（含技能 ID）
     * @return 技能执行结果的 Mono（当前不传递 ChatContext）
     */
    @PostMapping("/execute-skill")
    public Mono<SkillResult> executeSkill(@RequestBody SkillExecuteRequest request) {
        log.info("收到技能执行请求: skillId={}", request.getSkillId());
        return Mono.fromFuture(actionExecutor.executeSkill(request.getSkillId(), null));
    }

    /**
     * 获取所有已注册的工具定义列表。
     *
     * @return 工具定义列表的 Mono
     */
    @GetMapping("/tools")
    public Mono<List<ToolDefinition>> getTools() {
        List<ToolDefinition> definitions = toolRegistry.getAllDefinitions();
        return Mono.just(definitions);
    }

    /**
     * 获取所有已注册技能的摘要信息。
     *
     * @return 技能摘要列表的 Mono
     */
    @GetMapping("/skills")
    public Mono<List<Map<String, Object>>> getSkills() {
        List<Map<String, Object>> skills = actionExecutorImpl.getRegisteredSkills();
        return Mono.just(skills);
    }

    /**
     * 查询工具沙箱的健康状态。
     *
     * @return 包含 healthy 布尔值的 Map
     */
    @GetMapping("/sandbox/health")
    public Mono<Map<String, Boolean>> getSandboxHealth() {
        return Mono.just(Map.of("healthy", actionExecutorImpl.isSandboxHealthy()));
    }

    /**
     * 获取工具分类统计信息。
     *
     * @return 包含 totalCount 和 categoryStats 的 Map
     */
    @GetMapping("/tools/stats")
    public Mono<Map<String, Object>> getToolStats() {
        if (toolRegistry instanceof DefaultToolRegistry reg) {
            return Mono.just(Map.of(
                    "totalCount", reg.size(),
                    "categoryStats", reg.getCategoryStats()
            ));
        }
        return Mono.just(Map.of("totalCount", 0, "categoryStats", Map.of()));
    }
}
