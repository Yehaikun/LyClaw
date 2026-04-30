package lyjew.com.lyclaw.tool.impl;

import lombok.extern.slf4j.Slf4j;
import lyjew.com.lyclaw.context.ChatContext;
import lyjew.com.lyclaw.model.ToolCall;
import lyjew.com.lyclaw.model.ToolDefinition;
import lyjew.com.lyclaw.tool.Tool;
import lyjew.com.lyclaw.tool.ToolRegistry;
import lyjew.com.lyclaw.tool.ToolResult;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

/**
 * 默认工具注册表实现 —— 使用 ConcurrentHashMap 存储，线程安全。
 *
 * <p><b>设计动机</b>：ToolRegistry 的职责是管理 Tool 的注册与查找。
 * 如果不使用统一的注册表，每个 PipelineStage 都需要自行维护工具列表，
 * 新增工具需要改多处代码。通过 DefaultToolRegistry 集中管理，
 * 调用方只需从注册表按名称获取或查询所有工具定义。</p>
 *
 * <p><b>ConcurrentHashMap 选择原因</b>：
 * <ul>
 *   <li>工具注册可能在启动阶段（主线程）和执行阶段（多请求并发）同时发生</li>
 *   <li>ConcurrentHashMap 的读操作无锁（get/containsKey），写操作分段锁</li>
 *   <li>与 CopyOnWriteArrayList 相比，随机查找 O(1) vs O(n)</li>
 * </ul>
 * </p>
 *
 * <p><b>Spring 注入</b>：@Component。所有实现了 Tool 接口且标记了 @Component 的
 * 工具会被 Spring 自动发现，通过 {@code @Autowired List<Tool>} 注入到构造器。
 * 构造器中逐个 register()，实现"新增工具只需写一个类加 @Component"的扩展目标。</p>
 *
 * @since 1.0
 * @author LyClaw Team
 * @see ToolRegistry
 * @see Tool
 */
@Slf4j
@Component
public class DefaultToolRegistry implements ToolRegistry {

    /**
     * 工具存储映射 —— key 是工具名称（全局唯一），value 是 Tool 实例。
     *
     * <p>ConcurrentHashMap 保证并发安全。所有写操作（register/remove）通过
     * put/get 完成，读操作无锁。</p>
     */
    private final ConcurrentHashMap<String, Tool> tools = new ConcurrentHashMap<>();

    /**
     * 构造时注入所有已注册的 Tool 实例。
     *
     * <p>Spring 会自动搜集所有 @Component 的 Tool 实现类，
     * 通过此构造器注入。每种 Tool 的注册时机在构造器中完成。</p>
     *
     * @param toolList Spring 自动注入的所有 Tool 实现
     */
    public DefaultToolRegistry(List<Tool> toolList) {
        // 逐个注册所有注入的工具，确保名称唯一
        for (Tool tool : toolList) {
            register(tool);
        }
    }

    /**
     * 注册一个工具。如果同名工具已存在则覆盖。
     *
     * @param tool 工具实例，不可为 null
     */
    @Override
    public void register(Tool tool) {
        // put 返回旧值，如果旧值非 null 说明是覆盖操作
        Tool old = tools.put(tool.getName(), tool);
        if (old != null) {
            // 同名工具被覆盖 —— 记录日志
            log.debug("注册工具{}成功！, {}" , tool.getName(), "同名工具被覆盖");
        }
        log.debug("注册工具 {} 成功！" , tool.getName());
    }

    /**
     * 按工具名称查找。
     *
     * @param name 工具名称
     * @return Tool 实例，不存在返回 null
     */
    @Override
    public Tool get(String name) {
        return tools.get(name);
    }

    /**
     * 获取所有已注册工具的工具定义列表。
     *
     * <p>此方法在 DefaultEngine.execute() 中被调用，
     * 返回的工具定义会被注入到 ChatRequest.tools 中发送给模型。</p>
     *
     * @return 工具定义列表（不可修改的快照）
     */
    @Override
    public List<ToolDefinition> getAllDefinitions() {
        // 收集所有工具的 ToolDefinition，包装为不可变列表
        return tools.values().stream()
                .map(Tool::getDefinition)
                .collect(Collectors.toUnmodifiableList());
    }

    /**
     * 执行工具调用。
     *
     * <p>根据 ToolCall 中的工具名称查找已注册的工具并执行。</p>
     *
     * @param toolCall 模型返回的工具调用请求
     * @return 工具执行结果
     * @throws IllegalArgumentException 如果找不到指定名称的工具
     */
    @Override
    public ToolResult execute(ToolCall toolCall, ChatContext context) {
        // 1. 按名称查找工具
        Tool tool = tools.get(toolCall.getName());
        if (tool == null) {
            // 工具不存在 —— 抛出异常由 ErrorPolicy 处理
            throw new IllegalArgumentException(
                    "Tool not found: " + toolCall.getName());
        }
        // 2. 执行工具并返回结果
        return tool.execute(toolCall, context);
    }
}