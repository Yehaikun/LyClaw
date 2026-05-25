package lyjew.com.lyclaw.pipeline;

/**
 * 内置管线配置文件枚举。
 *
 * <p>每个枚举值代表一种标准的管线执行模式。
 * 前端通过 {@code extras.mode} 传递对应的 {@link #id()} 值来选择模式。</p>
 */
public enum BuiltInProfiles implements PipelineProfile {

    /** 纯 ReAct 工具调用模式，无反思闭环 */
    REACT("react", "纯ReAct工具调用模式"),

    /** 反思管线模式，带自评估 DAG 闭环 */
    REFLECTION("reflection", "反思管线模式（自评估DAG）"),

    /** 计划-执行模式，先分解任务再逐步执行 */
    PLAN_EXECUTE("plan-execute", "计划执行模式（任务分解）");

    private final String id;
    private final String description;

    BuiltInProfiles(String id, String description) {
        this.id = id;
        this.description = description;
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public String description() {
        return description;
    }

    /**
     * 根据线路上传输的标识符查找对应的内置 profile。
     *
     * @param id 线路上传输的标识符
     * @return 匹配的枚举值，未匹配时返回 {@link #REACT}
     */
    public static BuiltInProfiles fromId(String id) {
        if (id == null || id.isEmpty()) return REACT;
        for (BuiltInProfiles p : values()) {
            if (p.id.equals(id)) return p;
        }
        return REACT;
    }
}
