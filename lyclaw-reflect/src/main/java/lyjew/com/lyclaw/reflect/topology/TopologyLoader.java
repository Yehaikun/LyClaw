package lyjew.com.lyclaw.reflect.topology;

import lyjew.com.lyclaw.annotation.Agent;
import lyjew.com.lyclaw.reflect.registry.ReflectionConfig;
import lyjew.com.lyclaw.reflect.registry.ReflectionTopologyRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.type.classreading.MetadataReader;
import org.springframework.core.type.classreading.SimpleMetadataReaderFactory;

/**
 * 拓扑加载器 — 应用启动后扫描 classpath 下所有带 {@code @ReflectionConfig} 注解的
 * Agent 接口，自动构建并注册对应的反思拓扑。
 *
 * <p>扫描路径默认为 {@code lyjew.com.lyclaw}，通过
 * {@link ClassPathScanningCandidateComponentProvider} 发现所有候选 Bean。
 *
 * <p>拓扑构建流程：
 * <ol>
 *   <li>扫描 classpath 获取带 @ReflectionConfig 注解的类</li>
 *   <li>读取 topology 属性 → 从 {@link TopologyPresets} 获取模板</li>
 *   <li>根据 maxRetries/threshold 微调模板参数</li>
 *   <li>以类的全限定名作为 agentId 注册到 {@link ReflectionTopologyRegistry}</li>
 * </ol>
 */
public class TopologyLoader {

    private static final Logger log = LoggerFactory.getLogger(TopologyLoader.class);

    private final ReflectionTopologyRegistry registry;
    private final String scanBasePackage;

    public TopologyLoader(ReflectionTopologyRegistry registry) {
        this(registry, "lyjew.com.lyclaw");
    }

    public TopologyLoader(ReflectionTopologyRegistry registry, String scanBasePackage) {
        this.registry = registry;
        this.scanBasePackage = scanBasePackage;
    }

    /**
     * 在应用就绪后扫描并注册所有 @ReflectionConfig 拓扑。
     * 通过 Spring @EventListener 解耦，不阻塞启动流程。
     */
    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        log.info("开始扫描 @ReflectionConfig 注解（basePackage={}）...", scanBasePackage);
        int count = 0;

        try {
            PathMatchingResourcePatternResolver resourceResolver =
                    new PathMatchingResourcePatternResolver();
            SimpleMetadataReaderFactory readerFactory = new SimpleMetadataReaderFactory();

            String packagePattern = "classpath*:" + scanBasePackage.replace('.', '/') + "/**/*.class";
            Resource[] resources = resourceResolver.getResources(packagePattern);

            for (Resource res : resources) {
                try {
                    MetadataReader metadataReader = readerFactory.getMetadataReader(res);
                    if (!metadataReader.getAnnotationMetadata()
                            .hasAnnotation(ReflectionConfig.class.getName())) {
                        continue;
                    }

                    Class<?> clazz = Class.forName(metadataReader.getClassMetadata().getClassName());
                    ReflectionConfig config = clazz.getAnnotation(ReflectionConfig.class);
                    if (config == null) continue;

                    String agentId = clazz.getName();
                    Agent agentAnn = clazz.getAnnotation(Agent.class);
                    if (agentAnn != null && !agentAnn.name().isEmpty()) {
                        agentId = agentAnn.name();
                    }
                    ReflectionTopology topology = buildTopology(config);
                    if (topology != null) {
                        registry.register(agentId, topology);
                        count++;
                        log.info("已注册拓扑: agentId={}, topology={}, maxRetries={}",
                                agentId, topology.getName(), topology.getMaxIterations());
                    }
                } catch (Exception e) {
                    log.warn("处理 class 文件失败: {}", res.getDescription());
                }
            }
        } catch (Exception e) {
            log.error("扫描 @ReflectionConfig 失败: {}", e.getMessage(), e);
        }

        log.info("扫描完成，共注册 {} 个拓扑", count);
    }

    /** 根据 @ReflectionConfig 参数构建或定制拓扑 */
    private ReflectionTopology buildTopology(ReflectionConfig config) {
        String templateName = config.topology();
        ReflectionTopology base = TopologyPresets.byName(templateName);

        if (base == null) {
            log.warn("未找到预置拓扑模板 '{}'，回退到 passthrough", templateName);
            base = TopologyPresets.passthrough();
        }

        // 根据注解参数微调
        if (config.maxRetries() != base.getMaxIterations()) {
            base.setMaxIterations(config.maxRetries());
        }

        // 如果指定了自定义 evaluator，替换拓扑中的 evaluator 节点
        if (!config.evaluator().isEmpty() && !config.evaluator().equals("llmJudgeEvaluator")) {
            var nodes = base.getNodes();
            for (var entry : nodes.entrySet()) {
                if (entry.getValue().getPrimitiveType() == PrimitiveType.EVALUATOR) {
                    entry.getValue().setImplementationName(config.evaluator());
                    break; // 只替换第一个 Evaluator
                }
            }
        }

        log.debug("拓扑 '{}' 构建完成: maxIterations={}, evaluator={}",
                base.getName(), base.getMaxIterations(), config.evaluator());
        return base;
    }
}
