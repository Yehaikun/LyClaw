package lyjew.com.lyclaw.model;

import cn.hutool.core.util.IdUtil;
import lombok.*;
import lombok.experimental.SuperBuilder;
import lyjew.com.lyclaw.base.BaseDTO;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
/**
 * 会话
 */
@Data
@EqualsAndHashCode(callSuper = true)
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public class Session extends BaseDTO {
    private String sessionId;                // 会话ID
    private String name;                 // 会话名称
    private String model;               // 使用的模型


    @Builder.Default
    private List<Message> messages=new ArrayList<>();      // 消息列表

    public void addMessage(Message message) {
        this.messages.add(message);
    }
}
