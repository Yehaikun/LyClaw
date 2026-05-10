package lyjew.com.lyclaw.feign;

import lyjew.com.lyclaw.protocol.mcp.McpToolDescriptor;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@FeignClient(name = "lyclaw-protocol-service", path = "/api/protocol")
public interface ProtocolFeignClient {

    @PostMapping("/mcp/discover")
    List<McpToolDescriptor> discoverTools(@RequestParam String serverCommand);

    @PostMapping("/model/chat")
    Map<String, Object> chat(@RequestBody Map<String, Object> request);
}
