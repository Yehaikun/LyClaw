package lyjew.com.lyclaw.reflect;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class ReflectRequest {
    private String sessionId;
    private String output;
    private String expectedOutput;
    private String context;
}
