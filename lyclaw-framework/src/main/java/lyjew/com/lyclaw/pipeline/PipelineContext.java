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
 * 流水线共享上下文（Pipeline Context），在响应式流水线的各个处理阶段之间传递可变状态和数据。
 *
 * <p>在 LyClaw 框架的流水线架构中，一次完整的对话处理过程被分解为多个独立的阶段
 * （Stage），每个阶段负责特定的处理职责（如前置检查、记忆注入、工具调用、后处理等）。
 * PipelineContext 作为这些阶段之间的共享数据总线，承载了流水线执行过程中产生和消费
 * 的所有运行时数据和状态标志。各个阶段通过读写 PipelineContext 中的字段来实现数据
 * 流转和协作控制，形成一个松耦合但数据共享的处理链。
 *
 * <p>核心承载数据包括：
 * <ul>
 *   <li><b>toolResults</b>：工具执行结果的字符串列表，流水线的工具调用阶段将每次工具
 *       调用的返回结果追加到此列表中，供后续的阶段（如反思评估）使用</li>
 *   <li><b>nodes</b>：任务节点列表（{@link lyjew.com.lyclaw.task.TaskNode}），记录
 *       流水线执行过程中产生的任务分解节点，支持任务拆分和子任务管理</li>
 *   <li><b>successCount / failCount</b>：工具调用的成功和失败计数，用于统计多轮工具
 *       调用的整体执行情况，为反思评估阶段提供量化依据</li>
 *   <li><b>reportRef</b>：反思报告（{@link lyjew.com.lyclaw.reflect.ReflectionReport}）
 *       的原子引用，流水线末尾的反思阶段会将评估结果填充到此字段</li>
 *   <li><b>reflectScoreRef</b>：反思评分的原子引用，默认为 0.0，由反思阶段更新</li>
 * </ul>
 *
 * <p>状态控制标志包括：
 * <ul>
 *   <li><b>pipelineOk</b>：流水线是否正常完成，由最终的调度或评估阶段设置，上游
 *       调用者通过此标志判断整个流水线的执行结果</li>
 *   <li><b>terminated</b>：流水线是否被提前终止。设置为 true 后，后续的处理阶段应
 *       立即返回空 Flux（空操作），实现优雅的短路退出机制</li>
 *   <li><b>currentStage</b>：当前所处的阶段名称，初始值为 "init"。用于追踪流水线
 *       执行进度和故障定位，各阶段在开始执行时应更新此字段</li>
 *   <li><b>respondStartMs</b>：开始响应的时间戳（毫秒），用于计算端到端延迟</li>
 * </ul>
 *
 * <p>线程安全设计：由于流水线阶段可能在多个线程中并发执行，PipelineContext 的所有
 * 可变字段均使用 {@link java.util.concurrent.atomic} 包下的原子类型
 * （AtomicInteger、AtomicBoolean、AtomicLong、AtomicReference），通过 CAS
 * 操作保证状态更新的原子性和可见性，避免了显式加锁带来的性能开销和死锁风险。
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
