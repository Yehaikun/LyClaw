package lyjew.com.lyclaw.action.tool;

import lyjew.com.lyclaw.security.SandboxLevel;
import lyjew.com.lyclaw.tool.Tool;
import java.util.Map;

/**
 * 工具沙箱 —— 在隔离环境中执行工具调用。
 *
 * <p>支持 5 级沙箱隔离:
 * NONE → READ_ONLY → RESTRICTED → CONTAINER → ISOLATED</p>
 *
 * @since 2.0
 */
public interface ToolSandbox {

    ToolResult execute(Tool tool, Map<String, Object> args, SandboxLevel level);

    boolean isHealthy();

    void destroy();
}
