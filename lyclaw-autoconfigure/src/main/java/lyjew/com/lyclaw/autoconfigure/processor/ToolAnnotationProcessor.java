package lyjew.com.lyclaw.autoconfigure.processor;

import lyjew.com.lyclaw.autoconfigure.binding.AnnotatedToolAdapter;
import lyjew.com.lyclaw.autoconfigure.facade.DeferredRegistrar;
import lyjew.com.lyclaw.model.ToolDefinition;
import lyjew.com.lyclaw.tool.Tool;
import lyjew.com.lyclaw.tool.ToolRegistry;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanPostProcessor;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.*;

/**
 * 工具注解处理器——在 BeanPostProcessor 阶段收集 @Tool 候选者，
 * 由 ExtensionWiring 驱动批量过滤后统一注册。
 *
 * <p>三阶段模型：
 * <ol>
 *   <li><b>收集（BPP）</b>：发现标注 @Tool 或实现 Tool 接口的 Bean，暂存到 pending 列表</li>
 *   <li><b>过滤（ExtensionWiring）</b>：调用 ExtensionFacade.process() 执行 ConditionFilter 检查，
 *       @ToolCondition 不满足的候选者被剔除</li>
 *   <li><b>注册（applyFiltered 回调）</b>：对通过的候选者提取元数据、构建适配器、注册到 ToolRegistry</li>
 * </ol>
 *
 * <p>三种工具注册模式（与旧版完全兼容）：
 * <ul>
 *   <li><b>模式一</b>：类级 @Tool + 方法级 @Tool/@Param → 构建 AnnotatedToolAdapter</li>
 *   <li><b>模式二</b>：类级 @Tool + 实现 Tool 接口 → 直接注册为 Tool</li>
 *   <li><b>模式三</b>：无 @Tool 但实现 Tool 接口（旧版兼容）→ 直接注册</li>
 * </ul>
 *
 * @see DeferredRegistrar
 * @see lyjew.com.lyclaw.autoconfigure.facade.ExtensionWiring
 */
public class ToolAnnotationProcessor implements BeanPostProcessor, DeferredRegistrar<Object> {

    private static final Logger log = LoggerFactory.getLogger(ToolAnnotationProcessor.class);

    private final ToolRegistry toolRegistry;
    private final Set<String> collectedClasses = new HashSet<>();
    //收集tool的bean放在这里
    private final List<Object> pendingBeans = new ArrayList<>();

    public ToolAnnotationProcessor(ToolRegistry toolRegistry) {
        this.toolRegistry = toolRegistry;
    }

    // ── DeferredRegistrar ────────────────────────────────────────────

    @Override
    public String category() {
        return "tool";
    }

    @Override
    public List<Object> getPending() {
        return List.copyOf(pendingBeans);
    }

    @Override
    public void applyFiltered(List<Object> accepted) {
        for (Object bean : accepted) {
            try {
                registerTool(bean);
            } catch (Exception e) {
                log.error("工具注册失败: {}", bean.getClass().getSimpleName(), e);
            }
        }
        pendingBeans.clear();
        log.info("工具注册完成: {} 个通过过滤并注册", accepted.size());
    }

    // ── BeanPostProcessor：仅收集，不注册 ────────────────────────────

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
        Class<?> clazz = bean.getClass();
        String classKey = clazz.getName();
        if (collectedClasses.contains(classKey)) {
            return bean;
        }

        // 判断是否工具候选者（有 @Tool 注解 或 实现了 Tool 接口）
        if (findAnnotation(clazz, "Tool") != null || bean instanceof Tool) {
            collectedClasses.add(classKey);
            pendingBeans.add(bean);
            log.debug("收集工具候选者: {}", classKey);
        }
        return bean;
    }

    // ── 注册逻辑（从旧版 postProcessAfterInitialization 提取）─────────

    private void registerTool(Object bean) {
        Class<?> clazz = bean.getClass();

        Object toolAnnot = findAnnotation(clazz, "Tool");
        if (toolAnnot != null) {
            // ── 有 @Tool 注解 ──
            String name = getAnnotationAttr(toolAnnot, "name", String.class);
            String desc = getAnnotationAttr(toolAnnot, "description", String.class);
            boolean readonly = Boolean.TRUE.equals(
                    getAnnotationAttr(toolAnnot, "readonly", Boolean.class));
            String group = getAnnotationAttr(toolAnnot, "group", String.class);
            long timeout = getAnnotationAttr(toolAnnot, "timeout", Long.class);

            if (name == null || name.isEmpty()) {
                name = Character.toLowerCase(clazz.getSimpleName().charAt(0))
                        + clazz.getSimpleName().substring(1);
            }

            Method execMethod = findExecuteMethod(clazz);
            if (execMethod == null) {
                // 模式二：类级 @Tool 但没有标记的执行方法 → 必须实现 Tool 接口
                if (bean instanceof Tool tool) {
                    toolRegistry.register(tool);
                    log.info("注册工具 '{}'（@Tool + Tool 接口模式）", name);
                } else {
                    log.error("@Tool 类 '{}' 未实现 Tool 接口且无 @Tool/@Param 方法", clazz.getName());
                }
                return;
            }

            // 模式一：构建适配器注册
            ToolDefinition def = buildDefinition(name, desc, group, readonly, timeout, execMethod);
            AnnotatedToolAdapter adapter = new AnnotatedToolAdapter(
                    bean, name, desc, readonly, def, execMethod);
            toolRegistry.register(adapter);
            log.info("注册工具 '{}'（class: {}）", name, clazz.getSimpleName());

        } else if (bean instanceof Tool tool) {
            // ── 模式三：旧版兼容——无 @Tool 但实现了 Tool 接口 ──
            toolRegistry.register(tool);
            log.info("注册旧版工具 '{}'（class: {}）", tool.getName(), clazz.getSimpleName());
        }
    }

    // ── internal helpers（与原版一致）──────────────────────────────────

    private Method findExecuteMethod(Class<?> clazz) {
        for (Method m : clazz.getDeclaredMethods()) {
            if (findAnnotation(m, "Tool") != null) {
                return m;
            }
        }
        for (Method m : clazz.getDeclaredMethods()) {
            if (m.getParameterCount() > 0 && hasParamAnnotation(m)) {
                return m;
            }
        }
        return null;
    }

    private boolean hasParamAnnotation(Method m) {
        for (var p : m.getParameters()) {
            if (findAnnotation(p, "Param") != null) {
                return true;
            }
        }
        return false;
    }

    private ToolDefinition buildDefinition(String name, String desc, String group,
                                           boolean readonly, long timeout, Method method) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("type", "object");
        Map<String, Object> properties = new LinkedHashMap<>();
        List<String> required = new ArrayList<>();

        for (var p : method.getParameters()) {
            String pName = p.getName();
            String pDesc = "";
            boolean pReq = true;

            try {
                Object pa = findAnnotation(p, "Param");
                if (pa != null) {
                    String n = (String) pa.getClass().getMethod("name").invoke(pa);
                    if (n != null && !n.isEmpty()) {
                        pName = n;
                    }
                    pDesc = (String) pa.getClass().getMethod("description").invoke(pa);
                    pReq = (Boolean) pa.getClass().getMethod("required").invoke(pa);
                }
            } catch (Exception e) {
                log.debug("Failed to reflect @Param on {}: {}", p.getName(), e.getMessage());
            }

            Map<String, Object> prop = new LinkedHashMap<>();
            prop.put("type", javaTypeToJsonType(p.getType()));
            prop.put("description", pDesc);
            properties.put(pName, prop);
            if (pReq) {
                required.add(pName);
            }
        }

        params.put("properties", properties);
        if (!required.isEmpty()) {
            params.put("required", required);
        }

        return ToolDefinition.builder()
                .name(name)
                .displayName(name)
                .description(desc)
                .parameters(params)
                .source("builtin")
                .timeout(timeout)
                .readOnly(readonly)
                .build();
    }

    private String javaTypeToJsonType(Class<?> type) {
        if (type == String.class) return "string";
        if (type == int.class || type == long.class || type == Integer.class || type == Long.class)
            return "integer";
        if (type == double.class || type == float.class || type == Double.class || type == Float.class)
            return "number";
        if (type == boolean.class || type == Boolean.class) return "boolean";
        return "string";
    }

    // ── reflection helpers ──────────────────────────────────────────

    private Object findAnnotation(Object obj, String simpleName) {
        Annotation[] anns;
        if (obj instanceof Class<?> c) {
            anns = c.getAnnotations();
        } else if (obj instanceof Method m) {
            anns = m.getAnnotations();
        } else if (obj instanceof java.lang.reflect.Parameter p) {
            anns = p.getAnnotations();
        } else {
            return null;
        }
        for (Annotation a : anns) {
            if (a.annotationType().getName().endsWith("." + simpleName)) {
                return a;
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private <T> T getAnnotationAttr(Object ann, String attr, Class<T> type) {
        try {
            return (T) ann.getClass().getMethod(attr).invoke(ann);
        } catch (Exception e) {
            if (type == Boolean.class) return (T) Boolean.FALSE;
            if (type == Long.class) return (T) Long.valueOf(0L);
            return null;
        }
    }
}
