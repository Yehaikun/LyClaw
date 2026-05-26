package lyjew.com.lyclaw.agent;

import lyjew.com.lyclaw.model.Message;
import lyjew.com.lyclaw.react.AgentContext;

import java.util.List;
import java.util.Set;

public class RoutingContext {

    private final String parentAgentId;
    private final String sessionId;
    private final List<Message> conversationHistory;
    private final String userMessage;
    private final Set<String> preferredCapabilities;
    private final int depth;

    private RoutingContext(String parentAgentId, String sessionId,
                           List<Message> conversationHistory, String userMessage,
                           Set<String> preferredCapabilities, int depth) {
        this.parentAgentId = parentAgentId;
        this.sessionId = sessionId;
        this.conversationHistory = conversationHistory;
        this.userMessage = userMessage;
        this.preferredCapabilities = preferredCapabilities;
        this.depth = depth;
    }

    public static RoutingContext from(AgentContext ctx) {
        return new RoutingContext(
                ctx.getAgentId(),
                ctx.getSessionId(),
                ctx.getChatRequest() != null ? ctx.getChatRequest().getMessages() : List.of(),
                ctx.getUserMessage(),
                java.util.Collections.emptySet(),
                ctx.getRunMetadata() != null ? ctx.getRunMetadata().getSubagentDepth() : 0
        );
    }

    public static Builder builder() { return new Builder(); }

    public String getParentAgentId() { return parentAgentId; }
    public String getSessionId() { return sessionId; }
    public List<Message> getConversationHistory() { return conversationHistory; }
    public String getUserMessage() { return userMessage; }
    public Set<String> getPreferredCapabilities() { return preferredCapabilities; }
    public int getDepth() { return depth; }

    public static class Builder {
        private String parentAgentId;
        private String sessionId;
        private List<Message> conversationHistory;
        private String userMessage;
        private Set<String> preferredCapabilities;
        private int depth;

        public Builder parentAgentId(String v) { this.parentAgentId = v; return this; }
        public Builder sessionId(String v) { this.sessionId = v; return this; }
        public Builder conversationHistory(List<Message> v) { this.conversationHistory = v; return this; }
        public Builder userMessage(String v) { this.userMessage = v; return this; }
        public Builder preferredCapabilities(Set<String> v) { this.preferredCapabilities = v; return this; }
        public Builder depth(int v) { this.depth = v; return this; }
        public RoutingContext build() { return new RoutingContext(parentAgentId, sessionId,
                conversationHistory, userMessage, preferredCapabilities, depth); }
    }

    public Builder toBuilder() {
        return new Builder()
                .parentAgentId(parentAgentId)
                .sessionId(sessionId)
                .conversationHistory(conversationHistory)
                .userMessage(userMessage)
                .preferredCapabilities(preferredCapabilities)
                .depth(depth);
    }
}
