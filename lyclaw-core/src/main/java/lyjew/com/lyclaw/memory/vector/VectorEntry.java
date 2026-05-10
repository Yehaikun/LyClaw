package lyjew.com.lyclaw.memory.vector;

import lombok.Builder;
import lombok.Data;
import java.util.Map;

@Data
@Builder
public class VectorEntry {

    private String id;
    private float[] vector;
    private Map<String, Object> metadata;
}
