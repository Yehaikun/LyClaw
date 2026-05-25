package lyjew.com.lyclaw.reflect.registry;

import lyjew.com.lyclaw.reflect.primitive.*;
import lyjew.com.lyclaw.reflect.topology.PrimitiveType;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 原语查找工厂。按 (PrimitiveType, implementationName) 解析原语实例。
 * 不直接依赖 Spring 容器，通过 register 方法手动注册或通过 Spring BeanPostProcessor 填充。
 */
public class PrimitiveFactory {

    private final Map<String, ReflectionPrimitive> primitives = new ConcurrentHashMap<>();
    private final Map<PrimitiveType, String> defaultNames = new ConcurrentHashMap<>();

    private static String key(PrimitiveType type, String name) {
        return type.name() + ":" + name;
    }

    public void register(PrimitiveType type, String name, ReflectionPrimitive instance) {
        primitives.put(key(type, name), instance);
    }

    public void registerDefault(PrimitiveType type, String name) {
        defaultNames.put(type, name);
    }

    @SuppressWarnings("unchecked")
    public <T extends ReflectionPrimitive> T resolve(PrimitiveType type, String name) {
        ReflectionPrimitive p = primitives.get(key(type, name));
        if (p == null) {
            // 尝试默认实现
            String defName = defaultNames.get(type);
            if (defName != null) {
                p = primitives.get(key(type, defName));
            }
        }
        return (T) p;
    }

    public <T extends ReflectionPrimitive> T resolve(PrimitiveType type) {
        String defName = defaultNames.get(type);
        if (defName == null) return null;
        return resolve(type, defName);
    }

    public List<String> listImplementations(PrimitiveType type) {
        String prefix = type.name() + ":";
        List<String> result = new ArrayList<>();
        for (String k : primitives.keySet()) {
            if (k.startsWith(prefix)) result.add(k.substring(prefix.length()));
        }
        return result;
    }

    public Map<PrimitiveType, List<String>> listAll() {
        Map<PrimitiveType, List<String>> result = new LinkedHashMap<>();
        for (PrimitiveType type : PrimitiveType.values()) {
            List<String> impls = listImplementations(type);
            if (!impls.isEmpty()) result.put(type, impls);
        }
        return result;
    }
}
