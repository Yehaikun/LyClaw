package lyjew.com.lyclaw.feign;

import lyjew.com.lyclaw.protocol.mcp.McpToolDescriptor;
import lyjew.com.lyclaw.tracing.FeignConfig;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 协议服务Feign远程调用客户端。
 *
 * <p>通过Spring Cloud OpenFeign声明式调用lyclaw-protocol-service微服务，
 * 提供MCP协议工具发现、模型对话和智能体卡片获取等协议层功能。
 * 使用{@link FeignConfig}配置统一的链路追踪拦截器。</p>
 *
 * <p>服务路径前缀：/api/protocol</p>
 *
 * @author lyjew
 */
@FeignClient(name = "lyclaw-protocol-service", path = "/api/protocol", configuration = FeignConfig.class)
public interface ProtocolFeignClient {

    /**
     * 发现MCP服务器提供的工具列表。
     *
     * @param serverCommand MCP服务器启动命令
     * @return MCP工具描述符列表，包含工具名称、参数定义等信息
     */
    @PostMapping("/mcp/discover")
    List<McpToolDescriptor> discoverTools(@RequestParam("serverCommand") String serverCommand);

    /**
     * 调用大模型进行对话。
     *
     * @param request 对话请求参数，包含消息历史、模型配置等
     * @return 模型返回的响应结果
     */
    @PostMapping("/model/chat")
    Map<String, Object> chat(@RequestBody Map<String, Object> request);

    /**
     * 获取当前智能体的A2A（Agent-to-Agent）能力卡片。
     *
     * @return 智能体卡片信息，描述智能体的能力、端点等元数据
     */
    @GetMapping("/a2a/card")
    Map<String, Object> getAgentCard();
}
