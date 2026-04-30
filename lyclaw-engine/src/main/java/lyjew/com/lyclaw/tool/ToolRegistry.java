package lyjew.com.lyclaw.tool;

import lyjew.com.lyclaw.context.ChatContext;
import lyjew.com.lyclaw.model.ToolCall;
import lyjew.com.lyclaw.model.ToolDefinition;

import java.util.List;

/**
 * 工具注册表接口 —— 管理所有 Tool 的注册、查找和执行。
 *
 * <p>ToolRegistry 是引擎中所有工具的集中管理点。启动时由 Spring 自动扫描
 * 并注册所有 {@code @Component} 标注的 Tool 实现。
 * 运行时通过 {@link #execute(ToolCall)} 根据模型返回的 ToolCall 执行对应工具。</p>
 *
 * <p><b>设计动机</b>：如果不通过 Registry 管理，每次执行工具时都需要手动
 * if-else 判断 toolCall.getName() 来路由。通过 Registry，新增工具时
 * 只需注册，不需要修改路由代码。</p>
 *
 * <p><b>关于注册冲突</b>：同名工具第二次注册应抛出异常（如 {@code IllegalArgumentException}），
 * 防止意外覆盖导致生产环境行为不一致。</p>
 *
 * @since 1.0
 * @author LyClaw Team
 * @see Tool
 * @see lyjew.com.lyclaw.tool.impl.DefaultToolRegistry
 */
public interface ToolRegistry {

    /**
     * 注册一个工具。同名工具第二次注册抛异常。
     *
     * @param tool 工具实例，不可为 null
     * @throws IllegalArgumentException 如果同名工具已注册
     */
    void register(Tool tool);

    /**
     * 按名称查找工具。
     *
     * @param name 工具名称
     * @return 匹配的工具，未找到返回 null
     */
    Tool get(String name);

    /**
     * 返回所有已注册工具的定义列表。
     * ContextBuilder 需要用此列表构建 System Prompt 中的工具描述。
     *
     * @return 工具定义列表（不可变，非 null）
     */
    List<ToolDefinition> getAllDefinitions();

    /**
     * 按 toolCall 中的 name 执行对应工具。
     * 如果找不到对应的工具，返回一个包含错误信息的结果。
     *
     * @param toolCall 模型返回的工具调用请求
     * @return 工具执行结果
     */
    ToolResult execute(ToolCall toolCall, ChatContext context);
}