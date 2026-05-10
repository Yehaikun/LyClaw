package lyjew.com.lyclaw.action.tool;

import lyjew.com.lyclaw.security.SandboxLevel;
import lyjew.com.lyclaw.tool.Tool;

import java.util.Map;

public interface ToolSandbox {

    ToolResult execute(Tool tool, Map<String, Object> args, SandboxLevel level);
    boolean isHealthy();
    void destroy();
}
