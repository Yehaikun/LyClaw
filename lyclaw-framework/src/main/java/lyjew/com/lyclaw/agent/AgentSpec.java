package lyjew.com.lyclaw.agent;

import lombok.Builder;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * 代理规格，描述创建代理实例所需的全部配置信息。
 *
 * AgentSpec 是创建代理的蓝图，定义了代理的名称、描述、能力列表、底层
 * LLM 模型以及一组可扩展的配置参数。当生命周期管理器创建新代理时，
 * 会根据 AgentSpec 中的信息初始化代理实例。capabilities 列表用于后续
 * 任务匹配，modelName 决定了代理使用哪个大语言模型。config 是一个
 * 通用 Map，可以存放温度参数、最大 Token 数、系统提示词等任意配置项，
 * 为不同场景提供灵活的扩展能力。
 *
 * 使用 Lombok 自动生成 getter/setter/Builder 等方法。
 */
@Data
@Builder
public class AgentSpec {
    /** 代理的名称 */
    private String name;
    /** 代理的功能描述 */
    private String description;
    /** 代理具备的能力列表，如 ["code_gen", "review"] */
    private List<String> capabilities;
    /** 代理使用的底层 LLM 模型名称，如 "gpt-4" */
    private String modelName;
    /** 代理的扩展配置参数，如温度、最大 Token 数等 */
    private Map<String, Object> config;
}
