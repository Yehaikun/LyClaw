package lyjew.com.lyclaw.agent;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Data
@Builder
public class AgentHandle {

    // ========== 标识 ==========
    private String agentId;
    private String name;
    private String description;

    // ========== 状态 ==========
    private AgentState state;
    private HealthStatus health;

    // ========== 能力 ==========
    private List<String> capabilities;

    // ========== 模型 ==========
    private String model;
    private String provider;
    private String systemPrompt;

    // ========== 协作配置 ==========
    private AgentCollaborationMode collaborationMode;
    private List<String> allowAgents;
    private int maxSpawnDepth;
    private int maxChildrenPerAgent;
    private Map<String, String> extensions;

    // ========== 运行时统计 ==========
    private int activeSubagentCount;
    private int totalTasksCompleted;
    private int totalTasksFailed;
    private double historicalAccuracy;

    // ========== 时间戳 ==========
    private LocalDateTime createdAt;
    private LocalDateTime lastActiveAt;

    public enum HealthStatus {
        UP, DOWN, DEGRADED, UNKNOWN
    }
}
