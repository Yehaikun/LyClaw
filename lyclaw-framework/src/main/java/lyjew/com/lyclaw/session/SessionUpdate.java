package lyjew.com.lyclaw.session;

import java.util.Map;

import lyjew.com.lyclaw.model.SessionStatus;

/**
 * 会话更新请求 —— 对 {@link SessionService#update} 的入参封装。
 *
 * <p>所有字段可选，null 表示不更新该字段。</p>
 */
public class SessionUpdate {

    private String name;
    private String model;
    private SessionStatus status;
    private Map<String, String> tags;
    private String metadataJson;

    public SessionUpdate() {}

    public SessionUpdate name(String name) { this.name = name; return this; }
    public SessionUpdate model(String model) { this.model = model; return this; }
    public SessionUpdate status(SessionStatus status) { this.status = status; return this; }
    public SessionUpdate tags(Map<String, String> tags) { this.tags = tags; return this; }
    public SessionUpdate metadataJson(String metadataJson) { this.metadataJson = metadataJson; return this; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }
    public SessionStatus getStatus() { return status; }
    public void setStatus(SessionStatus status) { this.status = status; }
    public Map<String, String> getTags() { return tags; }
    public void setTags(Map<String, String> tags) { this.tags = tags; }
    public String getMetadataJson() { return metadataJson; }
    public void setMetadataJson(String metadataJson) { this.metadataJson = metadataJson; }

    /** 是否有任何字段需要更新 */
    public boolean hasChanges() {
        return name != null || model != null || status != null
                || tags != null || metadataJson != null;
    }
}
