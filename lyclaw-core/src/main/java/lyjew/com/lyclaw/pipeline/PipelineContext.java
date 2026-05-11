package lyjew.com.lyclaw.pipeline;

import lyjew.com.lyclaw.reflect.ReflectionReport;
import lyjew.com.lyclaw.task.TaskNode;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Mutable shared state carried across reactive pipeline stages.
 */
public class PipelineContext {

    private final List<String> toolResults = new ArrayList<>();
    private final AtomicInteger successCount = new AtomicInteger(0);
    private final AtomicInteger failCount = new AtomicInteger(0);
    private final List<TaskNode> nodes = new ArrayList<>();
    private final AtomicReference<ReflectionReport> reportRef = new AtomicReference<>();
    private final AtomicReference<Double> reflectScoreRef = new AtomicReference<>(0.0);
    private final AtomicBoolean pipelineOk = new AtomicBoolean(false);
    private final AtomicLong respondStartMs = new AtomicLong();
    private final AtomicBoolean terminated = new AtomicBoolean(false);
    private final AtomicReference<String> currentStage = new AtomicReference<>("init");

    // ---- getters ----

    public List<String> getToolResults() { return toolResults; }
    public AtomicInteger getSuccessCount() { return successCount; }
    public AtomicInteger getFailCount() { return failCount; }
    public List<TaskNode> getNodes() { return nodes; }
    public AtomicReference<ReflectionReport> getReportRef() { return reportRef; }
    public AtomicReference<Double> getReflectScoreRef() { return reflectScoreRef; }
    public AtomicBoolean getPipelineOk() { return pipelineOk; }
    public AtomicLong getRespondStartMs() { return respondStartMs; }
    public AtomicBoolean getTerminated() { return terminated; }
    public AtomicReference<String> getCurrentStage() { return currentStage; }

    // ---- convenience ----

    public boolean isTerminated() { return terminated.get(); }
    public void setTerminated(boolean value) { terminated.set(value); }
    public boolean isPipelineOk() { return pipelineOk.get(); }
    public void setPipelineOk(boolean value) { pipelineOk.set(value); }

    public void addToolResult(String result) { toolResults.add(result); }
    public void addNode(TaskNode node) { nodes.add(node); }
}
