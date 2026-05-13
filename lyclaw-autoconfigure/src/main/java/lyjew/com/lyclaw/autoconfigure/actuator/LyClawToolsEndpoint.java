package lyjew.com.lyclaw.autoconfigure.actuator;

import lyjew.com.lyclaw.model.ToolDefinition;
import lyjew.com.lyclaw.tool.ToolRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * LyClaw 工具注册表 Actuator 端点，通过 HTTP 暴露当前运行时所有已注册工具的清单信息。
 *
 * <p>该端点通过 Spring Boot Actuator 的 {@code @Endpoint} 和 {@code @ReadOperation}
 * 机制对外提供只读的工具列表查询接口，端点 ID 为 {@code lyclaw-tools}，访问路径为
 * {@code /actuator/lyclaw-tools}。端点从 {@link lyjew.com.lyclaw.tool.ToolRegistry}
 * 中获取所有已注册工具的 {@link lyjew.com.lyclaw.model.ToolDefinition} 定义列表，
 * 并提取关键元数据字段返回给调用方。</p>
 *
 * <p><b>工具发现来源：</b>工具注册表由 {@link lyjew.com.lyclaw.autoconfigure.processor.ToolAnnotationProcessor}
 * 在应用启动阶段自动填充，支持三种工具注册模式：@Tool 注解标注的方法级工具、
 * 实现 Tool 接口的类级工具、以及旧版无注解的兼容模式工具。本端点不做任何过滤，
 * 直接展示注册表中的全部工具清单。</p>
 *
 * <p><b>返回数据字段：</b>每个工具返回五个关键字段——名称（name，工具的唯一标识）、
 * 显示名称（displayName）、功能描述（description，帮助 LLM 理解工具用途）、
 * 来源标识（source，如 "builtin" 表示内置工具）、超时时间（timeout，毫秒）。
 * 同时还返回工具总数（count）供快速概览。</p>
 *
 * <p><b>可用性检测：</b>当 {@link ToolRegistry} Bean 未注册时（如在单元测试或
 * 最小化启动场景中），端点返回 {@code "available": false} 和原因说明，不会抛出异常。</p>
 */
@Endpoint(id = "lyclaw-tools")
public class LyClawToolsEndpoint {

    private final ToolRegistry toolRegistry;

    @Autowired
    public LyClawToolsEndpoint(@Autowired(required = false) ToolRegistry toolRegistry) {
        this.toolRegistry = toolRegistry;
    }

    @ReadOperation
    public Map<String, Object> tools() {
        Map<String, Object> result = new LinkedHashMap<>();
        if (toolRegistry == null) {
            result.put("available", false);
            result.put("reason", "No ToolRegistry bean registered");
            return result;
        }
        List<ToolDefinition> definitions = toolRegistry.getAllDefinitions();
        result.put("count", definitions.size());
        result.put("tools", definitions.stream()
                .map(def -> {
                    Map<String, Object> tool = new LinkedHashMap<>();
                    tool.put("name", def.getName());
                    tool.put("displayName", def.getDisplayName());
                    tool.put("description", def.getDescription());
                    tool.put("source", def.getSource());
                    tool.put("timeout", def.getTimeout());
                    return tool;
                })
                .toList());
        return result;
    }
}
