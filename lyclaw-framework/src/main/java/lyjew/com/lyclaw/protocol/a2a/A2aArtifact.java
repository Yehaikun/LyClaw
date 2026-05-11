package lyjew.com.lyclaw.protocol.a2a;

import lombok.Builder;
import lombok.Data;

import java.util.Map;

/**
 * A2A 协议中的制品实体，表示代理任务执行后产出的结果数据。
 *
 * <p>在 A2A 协议中，代理完成任务后会生成一个或多个 Artifact（制品），
 * 例如生成的文本、图片、代码文件等。每个制品关联到具体的任务（taskId），
 * 通过 artifactId 唯一标识，并指定 MIME 类型以便调用方正确解析内容。</p>
 *
 * <p>Artifact 的内容以字符串形式存储（content），支持通过 metadata 扩展
 * 携带自定义属性。</p>
 */
@Data
@Builder
public class A2aArtifact {
    /** 制品的唯一标识符 */
    private String artifactId;
    /** 关联的任务 ID，指明该制品属于哪个任务 */
    private String taskId;
    /** 制品的内容，以字符串形式存储 */
    private String content;
    /** 制品的 MIME 类型，如 "text/plain"、"application/json" 等 */
    private String mimeType;
    /** 扩展元数据，携带与制品相关的自定义属性 */
    private Map<String, Object> metadata;
    /** 制品的创建时间戳（毫秒） */
    private long createdAt;
}
