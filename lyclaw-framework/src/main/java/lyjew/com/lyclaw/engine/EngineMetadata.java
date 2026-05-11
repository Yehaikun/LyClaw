package lyjew.com.lyclaw.engine;

import java.util.List;
import java.util.Set;

/**
 * 引擎元数据实体，描述 AI 引擎的身份和能力信息。
 *
 * <p>每个 {@link Engine} 实现都持有一个 EngineMetadata 实例，
 * 用于向框架和外部使用者描述该引擎的基本属性：</p>
 * <ul>
 *   <li>名称和版本——用于引擎识别和版本管理</li>
 *   <li>描述——人类可读的引擎说明</li>
 *   <li>支持的模型列表——该引擎底层支持的具体模型名称</li>
 *   <li>能力集合——该引擎具备的功能特性（如 vision、function_calling 等）</li>
 * </ul>
 *
 * <p>该类是不可变的值对象，所有字段通过构造器初始化后不可更改。</p>
 */
public class EngineMetadata {

    /** 引擎名称 */
    private final String name;
    /** 引擎版本号 */
    private final String version;
    /** 引擎的描述信息 */
    private final String description;
    /** 该引擎支持的模型名称列表 */
    private final List<String> supportedModels;
    /** 该引擎具备的能力集（如 "vision"、"function_calling" 等） */
    private final Set<String> capabilities;

    /**
     * 构造引擎元数据。
     *
     * @param name            引擎名称
     * @param version         版本号
     * @param description     描述信息
     * @param supportedModels 支持的模型列表
     * @param capabilities    能力集合
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

    /** @return 引擎描述 */
    public String getDescription() { return description; }

    /** @return 支持的模型列表 */
    public List<String> getSupportedModels() { return supportedModels; }

    /** @return 能力集合 */
    public Set<String> getCapabilities() { return capabilities; }
}
