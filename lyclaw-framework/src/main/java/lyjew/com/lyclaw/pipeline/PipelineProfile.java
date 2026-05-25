package lyjew.com.lyclaw.pipeline;

/**
 * 管线配置文件的抽象标识。
 *
 * <p>每个实现（通常为 {@link BuiltInProfiles} 枚举值或自定义实现）
 * 代表一种管线执行模式。Stage 通过 {@link ReactivePipelineStage#supportsProfile(PipelineProfile)}
 * 声明自己在哪个 profile 下激活。</p>
 *
 * <p>扩展方式：实现此接口并注册到 {@link PipelineProfileRegistry}
 * 即可添加新模式，无需修改任何路由代码。</p>
 *
 * @see BuiltInProfiles
 * @see PipelineProfileRegistry
 */
public interface PipelineProfile {

    /** 线路上传输的标识符（如 "reflection"、"react"） */
    String id();

    /** 可读描述 */
    String description();
}
