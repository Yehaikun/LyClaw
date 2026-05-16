package lyjew.com.lyclaw.action;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * 工具执行请求，封装单次工具调用的完整参数信息。
 *
 * <p>当 Agent 需要调用外部工具（如文件操作、网络请求等）时，会构造该请求对象。
 * 其中沙箱等级用于控制工具的执行权限范围，确保安全性。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ToolExecuteRequest {
    /** 工具名称，对应注册到框架中的工具标识 */
    private String toolName;
    /** 工具调用参数，键值对形式传递 */
    private Map<String, Object> args;
    /** 沙箱执行模式字符串，控制工具的执行方式（DIRECT/SANDBOX/PROCESS） */
    private String sandboxLevel;
    /** 当前会话标识，用于关联工具执行日志 */
    private String sessionId;
}
