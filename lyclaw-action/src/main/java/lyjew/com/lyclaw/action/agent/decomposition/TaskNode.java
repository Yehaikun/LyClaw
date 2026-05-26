package lyjew.com.lyclaw.action.agent.decomposition;

import lyjew.com.lyclaw.react.subagent.SubagentResult;

import java.util.ArrayList;
import java.util.List;

public class TaskNode {

    public enum Status { PENDING, RUNNING, COMPLETED, FAILED, SKIPPED }

    private final String id;
    private final String description;
    private String assignedAgentId;
    private Status status;
    private SubagentResult result;
    private final List<String> candidates;
    private final long createdAt;

    public TaskNode(String id, String description) {
        this.id = id;
        this.description = description;
        this.status = Status.PENDING;
        this.candidates = new ArrayList<>();
        this.createdAt = System.currentTimeMillis();
    }

    public String getId() { return id; }
    public String getDescription() { return description; }
    public String getAssignedAgentId() { return assignedAgentId; }
    public void setAssignedAgentId(String v) { this.assignedAgentId = v; }
    public Status getStatus() { return status; }
    public void setStatus(Status v) { this.status = v; }
    public SubagentResult getResult() { return result; }
    public void setResult(SubagentResult v) { this.result = v; }
    public List<String> getCandidates() { return candidates; }
    public long getCreatedAt() { return createdAt; }

    public void addCandidate(String agentId) { candidates.add(agentId); }

    @Override
    public String toString() {
        return "TaskNode{" + id + " [" + status + "] " + description + " → " + assignedAgentId + "}";
    }
}
