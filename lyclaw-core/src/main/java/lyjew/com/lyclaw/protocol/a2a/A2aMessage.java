package lyjew.com.lyclaw.protocol.a2a;

import lombok.Builder;
import lombok.Data;
import java.util.Map;

@Data
@Builder
public class A2aMessage {

    private String messageId;
    private String fromAgentId;
    private String toAgentId;
    private String content;
    private A2aMessageType type;
    private Map<String, Object> metadata;
    private long timestamp;
}
