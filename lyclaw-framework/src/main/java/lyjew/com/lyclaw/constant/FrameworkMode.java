package lyjew.com.lyclaw.constant;

/**
 * 框架运行模式枚举，定义 LyClaw 框架的部署与运行方式。
 */
public enum FrameworkMode {
    /** 单机模式，框架在单个 JVM 进程中运行 */
    STANDALONE,
    /** 分布式模式，框架组件分布在多个节点上协同工作 */
    DISTRIBUTED,
    /** 嵌入模式，框架作为库嵌入到其他应用中运行 */
    EMBEDDED
}
