package lyjew.com.lyclaw.model;

import lombok.*;
import lombok.experimental.SuperBuilder;
import lyjew.com.lyclaw.base.BaseDTO;

/**
 * AI 模型配置模型，定义了一个可用 AI 模型的连接和认证信息。
 *
 * 每条配置对应一个具体的 AI 模型实例，包含提供商类型（如 OpenAI/Anthropic）、
 * API 密钥、模型标识符和 API 基础 URL。支持启用/禁用控制，方便动态切换模型。
 * 继承自 BaseDTO，使用 Lombok @Data/@SuperBuilder 自动生成访问方法。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public class ModelConfig extends BaseDTO {

    /** 配置名称，用于标识此模型配置 */
    private String name;
    /** 模型提供商，如 openai、anthropic、deepseek 等 */
    private String provider;
    /** API 访问密钥 */
    private String apiKey;
    /** 模型标识符，如 gpt-4、claude-sonnet-4-20250514 等 */
    private String model;
    /** API 基础 URL，用于代理或私有部署场景 */
    private String baseUrl;
    /** 是否启用此模型配置 */
    private boolean enabled;
}
