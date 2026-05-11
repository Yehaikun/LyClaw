package lyjew.com.lyclaw.feign;

import lyjew.com.lyclaw.action.SkillExecuteRequest;
import lyjew.com.lyclaw.action.ToolExecuteRequest;
import lyjew.com.lyclaw.action.tool.ToolResult;
import lyjew.com.lyclaw.dto.SkillResult;
import lyjew.com.lyclaw.model.ToolDefinition;
import lyjew.com.lyclaw.tracing.FeignConfig;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@FeignClient(name = "lyclaw-action-service", path = "/api/action", configuration = FeignConfig.class)
public interface ActionFeignClient {

    @PostMapping("/execute-tool")
    ToolResult executeTool(@RequestBody ToolExecuteRequest request);

    @PostMapping("/execute-skill")
    SkillResult executeSkill(@RequestBody SkillExecuteRequest request);

    @GetMapping("/tools")
    List<ToolDefinition> listTools();

    @GetMapping("/skills")
    List<Map<String, Object>> listSkills();

    @GetMapping("/sandbox/health")
    Map<String, Boolean> getSandboxHealth();

    @GetMapping("/tools/stats")
    Map<String, Object> getToolStats();
}
