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
 * 流水线共享上下文，在响应式流水线的各个阶段之间传递可变状态。
 *
 * 该类承载了流水线执行过程中的各种运行时数据和状态标志，包括工具调用结果、
 * 成功/失败计数、任务节点列表、反思评分、终止标志等。所有字段都使用原子类型
 * 以保证阶段间的线程安全。各阶段通过读写这些字段来实现数据流转和状态控制。
 */
public class PipelineContext {

    /** 工具执行结果的字符串列表，每个阶段可将工具调用结果追加到此列表 */
    private final List<String> toolResults = new ArrayList<>();
    /** 工具调用成功次数 */
    private final AtomicInteger successCount = new AtomicInteger(0);
    /** 工具调用失败次数 */
    private final AtomicInteger failCount = new AtomicInteger(0);
    /** 流水线执行过程中产生的任务节点列表 */
    private final List<TaskNode> nodes = new ArrayList<>();
    /** 反思报告的原子引用，流水线末尾阶段会填充此字段 */
    private final AtomicReference<ReflectionReport> reportRef = new AtomicReference<>();
    /** 反思评分的原子引用，默认为 0.0 */
    private final AtomicReference<Double> reflectScoreRef = new AtomicReference<>(0.0);
    /** 流水线是否正常完成，由调度阶段设置 */
    private final AtomicBoolean pipelineOk = new AtomicBoolean(false);
    /** 开始响应的时间戳（毫秒） */
    private final AtomicLong respondStartMs = new AtomicLong();
    /** 流水线是否被提前终止，终止后后续阶段应返回空 Flux */
    private final AtomicBoolean terminated = new AtomicBoolean(false);
    /** 当前所处的阶段名称，初始值为 "init" */
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

    /**
     * 判断流水线是否已被终止。
     *
     * @return true 表示流水线已终止，后续阶段不应继续执行
     */
    public boolean isTerminated() { return terminated.get(); }

    /**
     * 设置流水线的终止状态。
     *
     * @param value true 终止流水线，false 取消终止
     */
    public void setTerminated(boolean value) { terminated.set(value); }

    /**
     * 判断流水线是否正常完成。
     *
     * @return true 表示流水线正常完成，false 表示流水线异常或仍在执行中
     */
    public boolean isPipelineOk() { return pipelineOk.get(); }

    /**
     * 设置流水线的完成状态。
     *
     * @param value true 标记为正常完成，false 标记为异常
     */
    public void setPipelineOk(boolean value) { pipelineOk.set(value); }

    /**
     * 向列表追加一条工具执行结果。
     *
     * @param result 工具执行的结果字符串
     */
    public void addToolResult(String result) { toolResults.add(result); }

    /**
     * 向列表追加一个任务节点。
     *
     * @param node 任务节点
     */
    public void addNode(TaskNode node) { nodes.add(node); }
}
