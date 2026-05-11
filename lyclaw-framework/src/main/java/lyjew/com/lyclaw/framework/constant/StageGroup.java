package lyjew.com.lyclaw.framework.constant;

/**
 * 流水线阶段分组枚举，将处理流水线中的阶段按功能归类。
 */
public enum StageGroup {
    /** 预处理阶段组，负责输入数据的清洗与准备 */
    PREPROCESSING,
    /** 核心处理阶段组，执行主要的业务逻辑 */
    CORE,
    /** 后处理阶段组，负责结果的格式化与输出 */
    POSTPROCESSING
}
