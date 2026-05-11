package lyjew.com.lyclaw.protocol.a2a;

/**
 * A2A 协议的消息类型枚举。
 *
 * <p>定义了代理间通信的所有标准消息类型，每种类型对应一种特定的交互语义：</p>
 * <ul>
 *   <li>{@code TASK_REQUEST} - 发送任务请求，委托其他代理执行任务</li>
 *   <li>{@code TASK_RESPONSE} - 任务执行结果的响应</li>
 *   <li>{@code STATUS_QUERY} - 查询任务状态</li>
 *   <li>{@code STATUS_UPDATE} - 任务状态变更的通知</li>
 *   <li>{@code CANCEL_REQUEST} - 请求取消正在执行的任务</li>
 *   <li>{@code ARTIFACT_REQUEST} - 请求获取任务产出制品</li>
 *   <li>{@code ARTIFACT_RESPONSE} - 返回请求的制品</li>
 *   <li>{@code ERROR} - 表示通信过程中发生了错误</li>
 * </ul>
 */
public enum A2aMessageType {
    /** 任务请求 */
    TASK_REQUEST,
    /** 任务响应 */
    TASK_RESPONSE,
    /** 状态查询 */
    STATUS_QUERY,
    /** 状态更新 */
    STATUS_UPDATE,
    /** 取消请求 */
    CANCEL_REQUEST,
    /** 制品请求 */
    ARTIFACT_REQUEST,
    /** 制品响应 */
    ARTIFACT_RESPONSE,
    /** 错误 */
    ERROR
}
