package lyjew.com.lyclaw.pipeline.impl;

import lyjew.com.lyclaw.pipeline.Pipeline;
import lyjew.com.lyclaw.pipeline.PipelineStage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Pipeline 构建器 —— 自动发现所有 {@link PipelineStage} 实例并按 {@code getOrder()} 排序注册。
 *
 * <p>Spring 启动时注入 {@code List<PipelineStage>}，自动收集所有 {@code @Component}
 * 实现的 PipelineStage，按 {@code getOrder()} 升序排列，构建为 Pipeline。</p>
 *
 * <p>如需手动定制阶段（移除/调序），继承本类重写 {@link #afterPropertiesSet()}。</p>
 *
 * @since 1.0
 * @author LyClaw Team
 * @see Pipeline
 * @see PipelineStage
 */
@Slf4j
@Component
public class PipelineBuilder {

    /** 通过 Spring 自动注入的所有 PipelineStage 实现 */
    private final List<PipelineStage> allStages;

    /** 构建好的 Pipeline 缓存（启动时构建一次，不可变） */
    private Pipeline pipeline;

    /**
     * Spring 自动注入所有 PipelineStage 实现，按 getOrder() 升序排列。
     *
     * <p>只要一个类满足：
     * <ol>
     *   <li>implements PipelineStage</li>
     *   <li>@Component（或 @Service 等派生注解）</li>
     * </ol>
     * Spring 会自动收集到 {@code allStages} 列表中。
     * 新增 Stage 只需新建类 + implements PipelineStage + @Component，无需修改 DefaultEngine。</p>
     *
     * @param allStages Spring 自动注入的 PipelineStage 集合
     */
    public PipelineBuilder(List<PipelineStage> allStages) {
        allStages.sort(Comparator.comparingInt(PipelineStage::getOrder));
        this.allStages = allStages;
        this.pipeline = new DefaultPipeline(new ArrayList<>(allStages));
        log.info("[PipelineBuilder] 自动发现 {} 个 Stage, 已构建 Pipeline:", allStages.size());
        for (PipelineStage stage : allStages) {
            log.info("  ├── [{}] order={}", stage.getStageName(), stage.getOrder());
        }
    }

    /**
     * 获取已构建的 Pipeline 实例。
     * <p>在 Spring 启动时，构造器已自动完成 Pipeline 构建。
     * 外部调用方只需调用此方法获取单例 Pipeline。</p>
     *
     * @return 已构建的 Pipeline
     */
    public Pipeline build() {
        return pipeline;
    }

    /**
     * 获取注册的 Stage 列表（只读视角）。
     */
    public List<PipelineStage> getStages() {
        return new ArrayList<>(allStages);
    }
}