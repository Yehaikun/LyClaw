package lyjew.com.lyclaw.feign;

import lyjew.com.lyclaw.action.SkillExecuteRequest;
import lyjew.com.lyclaw.action.ToolExecuteRequest;
import lyjew.com.lyclaw.dto.SkillResult;
import lyjew.com.lyclaw.model.ToolDefinition;
import lyjew.com.lyclaw.tool.ToolExecutionResult;
import lyjew.com.lyclaw.tracing.FeignConfig;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 动作服务Feign远程调用客户端。
 *
 * <p>通过Spring Cloud OpenFeign声明式调用lyclaw-action-service微服务，
 * 提供工具执行、技能执行、工具/技能列表查询、沙箱健康检查和工具统计等功能。
 * 使用{@link FeignConfig}配置统一的链路追踪拦截器。</p>
 *
 * <p>动作服务是Agent执行操作的核心组件，负责管理工具注册表、
 * 执行工具调用和技能编排。服务路径前缀：/api/action</p>
 *
 * @author lyjew
 */
@FeignClient(name = "lyclaw-action-service", path = "/api/action", configuration = FeignConfig.class)
public interface ActionFeignClient {

    /**
     * 执行指定的工具调用。
     *
     * @param request 工具执行请求，包含工具名称、参数等信息
     * @return 工具执行结果，包含输出内容、状态等信息
     */
    @PostMapping("/execute-tool")
    ToolExecutionResult executeTool(@RequestBody ToolExecuteRequest request);

    /**
     * 执行指定的技能。
     *
     * @param request 技能执行请求，包含技能名称、参数等信息
     * @return 技能执行结果
     */
    @PostMapping("/execute-skill")
    SkillResult executeSkill(@RequestBody SkillExecuteRequest request);

    /**
     * 列出所有已注册的工具定义。
     *
     * @return 工具定义列表，包含工具名称、描述、参数模式等元数据
     */
    @GetMapping("/tools")
    List<ToolDefinition> listTools();

    /**
     * 列出所有已注册的技能。
     *
     * @return 技能列表，包含技能元数据
     */
    @GetMapping("/skills")
    List<Map<String, Object>> listSkills();

    /**
     * 查询沙箱运行环境的健康状况。
     *
     * @return 沙箱各组件的健康状态映射（组件名 → 是否健康）
     */
    @GetMapping("/sandbox/health")
    Map<String, Boolean> getSandboxHealth();

    /**
     * 获取工具执行的统计信息。
     *
     * @return 工具统计信息，包含调用次数、成功率等指标
     */
    @GetMapping("/tools/stats")
    Map<String, Object> getToolStats();
}
