package lyjew.com.lyclaw.protocol.a2a;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class AgentEndpoint {
    private String url;
    private String transportType;
    private boolean primary;
}
