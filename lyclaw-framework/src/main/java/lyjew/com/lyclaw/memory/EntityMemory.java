package lyjew.com.lyclaw.memory;

import lombok.Builder;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * 实体记忆数据模型，存储结构化的实体信息及其关系网络。
 *
 * 实体记忆是记忆系统中持久化程度最高的层次，用于维护用户、项目、工具等
 * 实体的属性和相互间的关系。支持版本号追踪变更，配合 {@link MemorySystem#upsertEntity}
 * 实现 upsert 语义。使用 Lombok 自动生成 getter/Builder 等样板方法。
 */
@Data
@Builder
public class EntityMemory {
    /** 实体类型（如 "user"、"project"、"tool"） */
    private String entityType;
    /** 实体唯一标识 */
    private String entityId;
    /** 实体名称 */
    private String name;
    /** 实体描述 */
    private String description;
    /** 实体属性，键值对形式存储灵活的结构化信息 */
    private Map<String, Object> properties;
    /** 与该实体关联的其他实体关系列表 */
    private List<EntityRelation> relations;
    /** 数据版本号，用于乐观锁和冲突检测 */
    private long version;
    /** 最近更新时间戳（毫秒） */
    private long updatedAt;

    /**
     * 实体关系内部类，描述两个实体之间的有向关系。
     *
     * 每条关系包含关系类型、目标实体标识、关系权重及扩展属性。
     * 使用 Lombok 自动生成 getter/Builder 等样板方法。
     */
    @Data
    @Builder
    public static class EntityRelation {
        /** 关系类型（如 "owns"、"member_of"、"depends_on"） */
        private String relationType;
        /** 目标实体的类型 */
        private String targetEntityType;
        /** 目标实体的唯一标识 */
        private String targetEntityId;
        /** 关系权重 [0, 1]，1 表示最强关联 */
        private double weight;
        /** 关系的扩展属性 */
        private Map<String, Object> properties;
    }
}
