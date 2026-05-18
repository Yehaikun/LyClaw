package lyjew.com.lyclaw.tool;

import java.util.Collections;
import java.util.List;

import lyjew.com.lyclaw.model.ToolDefinition;
import lyjew.com.lyclaw.react.AgentContext;

/**
 * 工具解析器 SPI——根据 Agent 上下文发现和解析可用工具。
 *
 * <p>核心能力：{@link #resolveTools(AgentContext)} 支持动态筛选——
 * 根据上下文自动过滤无关工具，避免 prompt 爆炸。
 *
 * <p>默认实现 {@code AnnotationToolResolver} 扫描 {@code @Tool} 注解和
 * {@link ToolProvider} 动态工具。
 */
public interface ToolResolver {
    /**
     * 获取当前上下文中可用的工具定义列表（支持动态筛选）。
     *
     * @param ctx Agent 上下文，可据此过滤工具
     * @return 可用工具定义列表
     */
    List<ToolDefinition> resolveTools(AgentContext ctx);

    /**
     * 按名称查找工具实例。
     *
     * @param toolName 工具名称
     * @param ctx Agent 上下文
     * @return 工具实例，未找到时返回 null
     */
    Tool resolve(String toolName, AgentContext ctx);

    /**
     * 返回所有注册的工具名称（不做筛选）。
     */
    default List<String> allToolNames() { return Collections.emptyList(); }
}
