package lyjew.com.lyclaw.base;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import java.time.LocalDateTime;

/**
 * DTO 基类，为所有数据传输对象提供通用字段（id、创建时间、更新时间）。
 *
 * <p>使用 Lombok {@code @SuperBuilder} 支持子类 Builder 继承，
 * 配置 Jackson 忽略未知属性以保证 API 向前兼容。</p>
 */
@Data
@SuperBuilder
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class BaseDTO {
    /** 实体唯一标识 */
    private String id;
    /** 创建时间 */
    private LocalDateTime createdAt;
    /** 最后更新时间 */
    private LocalDateTime updatedAt;
}
