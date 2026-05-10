package lyjew.com.lyclaw.memory;

import lombok.Builder;
import lombok.Data;
import java.util.List;
import java.util.Map;

/**
 * 实体记忆 —— 知识图谱中的节点, 表示人/项目/公司等实体及其关系。
 *
 * @since 2.0
 */
@Data
@Builder
public class EntityMemory {

    private String entityType;
    private String entityId;
    private String name;
    private String description;
    private Map<String, Object> properties;
    private List<EntityRelation> relations;
    private long version;
    private long updatedAt;

    @Data
    @Builder
    public static class EntityRelation {
        private String relationType;
        private String targetEntityType;
        private String targetEntityId;
        private double weight;
        private Map<String, Object> properties;
    }
}
