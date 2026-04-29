package lyjew.com.lyclaw.engine;

import java.util.List;
import java.util.Set;

/**
 * 引擎元信息 —— Engine.getMetadata() 的返回值。
 *
 * <p>描述了引擎的名称、版本、功能描述、支持的模型列表和能力集。
 * EngineSelector 可以根据这些元信息来决定哪个 Engine 最适合处理当前请求。</p>
 *
 * <p><b>设计动机</b>：当系统中有多个 Engine 实现时（DefaultEngine、ReasoningEngine、
 * PlanningEngine 等），调用方需要了解每个 Engine 的能力以做出路由选择。
 * EngineMetadata 提供了这种自描述能力。</p>
 *
 * <p><b>使用场景</b>：
 * <ul>
 *   <li>Engine.getMetadata() 的返回值</li>
 *   <li>EngineSelector 在路由决策时读取 Engine 的能力信息</li>
 *   <li>管理后台展示 Engine 列表</li>
 * </ul>
 * </p>
 *
 * @since 1.0
 * @author LyClaw Team
 */
public class EngineMetadata {

    /** 引擎名称，如 "default"、"reasoning"、"planning" */
    private final String name;

    /** 引擎版本号，遵循语义化版本规范 如 "1.0.0" */
    private final String version;

    /** 引擎功能描述，用于展示和管理 */
    private final String description;

    /** 支持的模型列表，如 ["minimax", "deepseek-openai"] */
    private final List<String> supportedModels;

    /**
     * 能力集 —— 引擎支持的高级功能。
     * 可能的值包括：
     * <ul>
     *   <li>"streaming" — 支持流式输出</li>
     *   <li>"tools" — 支持工具调用</li>
     *   <li>"thinking" — 支持模型思考模式</li>
     * </ul>
     */
    private final Set<String> capabilities;

    /**
     * 构造一个 EngineMetadata 实例。
     *
     * @param name            引擎名称
     * @param version         引擎版本
     * @param description     功能描述
     * @param supportedModels 支持的模型列表
     * @param capabilities    能力集
     */
    public EngineMetadata(String name, String version, String description,
                          List<String> supportedModels, Set<String> capabilities) {
        this.name = name;
        this.version = version;
        this.description = description;
        this.supportedModels = supportedModels;
        this.capabilities = capabilities;
    }

    /** @return 引擎名称 */
    public String getName() { return name; }

    /** @return 引擎版本号 */
    public String getVersion() { return version; }

    /** @return 引擎功能描述 */
    public String getDescription() { return description; }

    /** @return 支持的模型列表（不可变） */
    public List<String> getSupportedModels() { return supportedModels; }

    /** @return 能力集（不可变） */
    public Set<String> getCapabilities() { return capabilities; }
}