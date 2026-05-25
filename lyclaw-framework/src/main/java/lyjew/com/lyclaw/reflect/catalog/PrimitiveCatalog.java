package lyjew.com.lyclaw.reflect.catalog;

import lyjew.com.lyclaw.reflect.registry.PrimitiveDescriptor;
import lyjew.com.lyclaw.reflect.topology.PrimitiveType;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 原语目录——自描述的原语实现注册表，供 AI 发现可用零件并生成拓扑。
 */
public class PrimitiveCatalog {

    private final Map<String, PrimitiveDescriptor> descriptors = new ConcurrentHashMap<>();

    private static String key(PrimitiveType type, String name) {
        return type.name() + ":" + name;
    }

    public void register(PrimitiveDescriptor desc) {
        descriptors.put(key(desc.getPrimitiveType(), desc.getImplementationName()), desc);
    }

    public List<PrimitiveDescriptor> listAll() {
        return new ArrayList<>(descriptors.values());
    }

    public List<PrimitiveDescriptor> listByType(PrimitiveType type) {
        String prefix = type.name() + ":";
        List<PrimitiveDescriptor> result = new ArrayList<>();
        for (var entry : descriptors.entrySet()) {
            if (entry.getKey().startsWith(prefix)) result.add(entry.getValue());
        }
        return result;
    }

    public Optional<PrimitiveDescriptor> resolve(PrimitiveType type, String name) {
        return Optional.ofNullable(descriptors.get(key(type, name)));
    }

    /** 生成可直接注入 LLM prompt 的文本描述 */
    public String toPromptFragment() {
        StringBuilder sb = new StringBuilder();
        sb.append("可用原语清单：\n\n");
        for (PrimitiveType type : PrimitiveType.values()) {
            List<PrimitiveDescriptor> list = listByType(type);
            if (list.isEmpty()) continue;
            sb.append("## ").append(type).append("\n");
            for (PrimitiveDescriptor d : list) {
                sb.append("- ").append(d.getImplementationName())
                        .append(": ").append(d.getDescription());
                if (!d.getConfigSchema().isEmpty()) {
                    sb.append(" 参数: ");
                    for (var entry : d.getConfigSchema().entrySet()) {
                        sb.append(entry.getKey()).append("(")
                                .append(entry.getValue().getType())
                                .append(", 默认").append(entry.getValue().getDefaultValue())
                                .append(") ");
                    }
                }
                sb.append("\n");
            }
            sb.append("\n");
        }
        return sb.toString();
    }
}
