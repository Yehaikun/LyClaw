package lyjew.com.lyclaw.memory;

/**
 * 记忆类别枚举 —— 标识记忆内容的语义类型。
 *
 * @since 2.0
 */
public enum MemoryCategory {
    /** 事实知识 (如 "北京是中国的首都") */
    FACT,
    /** 用户偏好 (如 "用户喜欢简洁的代码风格") */
    PREFERENCE,
    /** 事件记录 (如 "2024年3月完成了重构") */
    EVENT,
    /** 经验教训 (如 "上次使用XX方法导致死锁，应避免") */
    LESSON,
    /** 任务状态 (如 "正在进行的任务进度") */
    TASK,
    /** 实体关系 (如 "张三 隶属于 数据部") */
    RELATION,
    /** 目标意图 (如 "用户想要学习 Spring Boot") */
    GOAL
}
