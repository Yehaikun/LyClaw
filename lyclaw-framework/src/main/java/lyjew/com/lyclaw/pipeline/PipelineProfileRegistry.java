package lyjew.com.lyclaw.pipeline;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * PipelineProfile 注册表 — 将线路上传输的 profile id 解析为 {@link PipelineProfile} 实例。
 *
 * <p>解析优先级：
 * <ol>
 *   <li>已注册的自定义 {@link PipelineProfile}（通过 {@link #register(PipelineProfile)} 添加）</li>
 *   <li>内置枚举 {@link BuiltInProfiles#fromId(String)}</li>
 * </ol>
 *
 * <p>第三方模块可通过实现 {@link PipelineProfile} 并调用 {@link #register(PipelineProfile)}
 * 来贡献新模式，无需修改框架代码。</p>
 */
public class PipelineProfileRegistry {

    private static final Logger log = LoggerFactory.getLogger(PipelineProfileRegistry.class);

    private final Map<String, PipelineProfile> customProfiles = new ConcurrentHashMap<>();

    /**
     * 注册一个自定义管线配置文件。
     * 通常在 {@code @PostConstruct} 或自动配置中调用。
     */
    public void register(PipelineProfile profile) {
        customProfiles.put(profile.id(), profile);
        log.info("已注册自定义管线Profile: id={}, description={}", profile.id(), profile.description());
    }

    /**
     * 批量注册。
     */
    public void registerAll(List<PipelineProfile> profiles) {
        for (PipelineProfile p : profiles) {
            register(p);
        }
    }

    /**
     * 将线路上传输的 profile id 解析为 {@link PipelineProfile}。
     *
     * @param profileId 线路上传输的标识符（可为 null 或空字符串）
     * @return 解析后的 PipelineProfile，未匹配时返回 {@link BuiltInProfiles#REACT}
     */
    public PipelineProfile resolve(String profileId) {
        if (profileId == null || profileId.isEmpty()) {
            return BuiltInProfiles.REACT;
        }
        PipelineProfile custom = customProfiles.get(profileId);
        if (custom != null) {
            return custom;
        }
        return BuiltInProfiles.fromId(profileId);
    }

    /**
     * 返回所有已注册的 profile（内置 + 自定义）。
     */
    public List<PipelineProfile> allProfiles() {
        List<PipelineProfile> all = new java.util.ArrayList<>(customProfiles.values());
        for (BuiltInProfiles bp : BuiltInProfiles.values()) {
            if (!customProfiles.containsKey(bp.id())) {
                all.add(bp);
            }
        }
        return List.copyOf(all);
    }
}
