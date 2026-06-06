package lyjew.com.lyclaw.model;

import java.util.Map;

/**
 * 会话列表查询条件。
 *
 * <p>用于 {@link lyjew.com.lyclaw.session.SessionStore#list(SessionQuery)}
 * 的分页+过滤查询。所有字段可选，null 表示不限制。</p>
 */
public class SessionQuery {

    private String agentId;
    private String userId;
    private SessionStatus status;
    private String keyword;        // 按名称/消息内容模糊搜索
    private Long createdAfter;
    private Long createdBefore;
    private Map<String, String> tags;  // 按标签过滤
    private int offset;
    private int limit = 50;
    private String sortBy = "updatedAt"; // updatedAt, createdAt, name
    private boolean ascending;

    public SessionQuery() {}

    public String getAgentId() { return agentId; }
    public void setAgentId(String agentId) { this.agentId = agentId; }

    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }

    public SessionStatus getStatus() { return status; }
    public void setStatus(SessionStatus status) { this.status = status; }

    public String getKeyword() { return keyword; }
    public void setKeyword(String keyword) { this.keyword = keyword; }

    public Long getCreatedAfter() { return createdAfter; }
    public void setCreatedAfter(Long createdAfter) { this.createdAfter = createdAfter; }

    public Long getCreatedBefore() { return createdBefore; }
    public void setCreatedBefore(Long createdBefore) { this.createdBefore = createdBefore; }

    public Map<String, String> getTags() { return tags; }
    public void setTags(Map<String, String> tags) { this.tags = tags; }

    public int getOffset() { return offset; }
    public void setOffset(int offset) { this.offset = Math.max(0, offset); }

    public int getLimit() { return limit; }
    public void setLimit(int limit) { this.limit = limit > 0 ? limit : 50; }

    public String getSortBy() { return sortBy; }
    public void setSortBy(String sortBy) { this.sortBy = sortBy; }

    public boolean isAscending() { return ascending; }
    public void setAscending(boolean ascending) { this.ascending = ascending; }

    /**
     * 构造器模式构建 SessionQuery。
     */
    public static SessionQueryBuilder builder() {
        return new SessionQueryBuilder();
    }

    public static class SessionQueryBuilder {
        private final SessionQuery query = new SessionQuery();

        public SessionQueryBuilder agentId(String agentId) { query.setAgentId(agentId); return this; }
        public SessionQueryBuilder userId(String userId) { query.setUserId(userId); return this; }
        public SessionQueryBuilder status(SessionStatus status) { query.setStatus(status); return this; }
        public SessionQueryBuilder keyword(String keyword) { query.setKeyword(keyword); return this; }
        public SessionQueryBuilder createdAfter(Long createdAfter) { query.setCreatedAfter(createdAfter); return this; }
        public SessionQueryBuilder createdBefore(Long createdBefore) { query.setCreatedBefore(createdBefore); return this; }
        public SessionQueryBuilder tags(Map<String, String> tags) { query.setTags(tags); return this; }
        public SessionQueryBuilder offset(int offset) { query.setOffset(offset); return this; }
        public SessionQueryBuilder limit(int limit) { query.setLimit(limit); return this; }
        public SessionQueryBuilder sortBy(String sortBy) { query.setSortBy(sortBy); return this; }
        public SessionQueryBuilder ascending(boolean ascending) { query.setAscending(ascending); return this; }

        public SessionQuery build() { return query; }
    }
}
