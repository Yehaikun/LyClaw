package lyjew.com.lyclaw.model;

import lombok.*;
import lombok.experimental.SuperBuilder;
import lyjew.com.lyclaw.base.BaseDTO;

import java.util.ArrayList;
import java.util.List;

/**
 * 持久化记忆模型，用于存储跨会话的 AI 记忆信息。
 *
 * 每条记忆包含标题、正文内容和标签，可用于在不同对话会话之间保留
 * 重要的上下文信息。支持启用/禁用控制，方便动态管理记忆的生效范围。
 * 继承自 BaseDTO，使用 Lombok @Data/@SuperBuilder 自动生成访问方法。
 */
@Data
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
public class Memory extends BaseDTO {

    /** 记忆内容正文 */
    @Builder.Default
    private String content = "";

    /** 记忆标题，默认值为"记忆" */
    @Builder.Default
    private String title = "记忆";

    /** 是否启用此记忆，默认启用 */
    @Builder.Default
    private boolean enabled = true;

    /** 记忆标签列表，用于分类和筛选 */
    @Builder.Default
    private List<String> tags = new ArrayList<>();
}
