package lyjew.com.lyclaw.action;

import lombok.Builder;
import lombok.Data;
import java.util.Map;

@Data
@Builder
public class SkillExecuteRequest {
    private String skillId;
    private String sessionId;
    private Map<String, Object> params;
}
