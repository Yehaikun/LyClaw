package lyjew.com.lyclaw.model;

import lombok.*;
import lombok.experimental.SuperBuilder;
import lyjew.com.lyclaw.base.BaseDTO;

import java.time.LocalDateTime;
/**
 * 模型配置
 */
@Data
@EqualsAndHashCode(callSuper = true)
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public class ModelConfig extends BaseDTO {

    private String name;          // 配置标识
    private String provider;       // 提供商：minimax / openai / anthropic
    private String apiKey;        // API Key
    private String model;          // 默认模型
    private String baseUrl;       // API端点
    private boolean enabled;       // 是否启用

}
