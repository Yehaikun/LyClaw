package lyjew.com.lyclaw.model;

import lombok.*;
import lombok.experimental.SuperBuilder;
import lyjew.com.lyclaw.base.BaseDTO;

import java.util.ArrayList;
import java.util.List;

/**
 * 长期记忆 — 单例实体，id 固定为 "global"
 */
@Data
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
public class Memory extends BaseDTO {

    /** 记忆正文，Markdown 格式 */
    @Builder.Default
    private String content = "";

    /** 人类可读标题 */
    @Builder.Default
    private String title = "记忆";

    /** 软开关，false 时上下文不加载 */
    @Builder.Default
    private boolean enabled = true;

    /** 预留标签 */
    @Builder.Default
    private List<String> tags = new ArrayList<>();
}