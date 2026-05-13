package lyjew.com.lyclaw.action.tool;

import lyjew.com.lyclaw.security.SandboxLevel;
import lyjew.com.lyclaw.tool.Tool;
import lyjew.com.lyclaw.tool.ToolExecutionResult;

import java.util.Map;

/**
 * 工具沙箱接口，定义在受控环境中执行工具的安全隔离规范。
 *
 * <p>工具沙箱负责在指定的{@link SandboxLevel}安全级别下执行{@link Tool}，
 * 提供健康检查与资源销毁能力。用于防止不可信工具代码对宿主系统造成破坏，
 * 是实现工具执行安全策略的核心组件。</p>
 */
public interface ToolSandbox {

    /**
     * 在指定安全级别下执行工具。
     *
     * @param tool  待执行的工具实例
     * @param args  工具执行参数
     * @param level 沙箱安全级别
     * @return 工具执行结果
     */
    ToolExecutionResult execute(Tool tool, Map<String, Object> args, SandboxLevel level);

    /**
     * 检查沙箱是否处于健康状态。
     *
     * @return 健康返回 true
     */
    boolean isHealthy();

    /**
     * 销毁沙箱并释放所有关联资源。
     */
    void destroy();
}
