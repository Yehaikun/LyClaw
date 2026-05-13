package lyjew.com.lyclaw.autoconfigure.processor;

import lyjew.com.lyclaw.autoconfigure.binding.AnnotatedToolAdapter;
import lyjew.com.lyclaw.model.ToolDefinition;
import lyjew.com.lyclaw.tool.Tool;
import lyjew.com.lyclaw.tool.ToolRegistry;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanPostProcessor;

import java.lang.reflect.Method;
import java.util.*;
import java.lang.annotation.Annotation;
/**
 * {@link BeanPostProcessor} that discovers {@code @Tool}-annotated beans and
 * registers them with the {@link ToolRegistry}.
 *
 * <p>Supports two patterns:
 * <ul>
 *   <li>A class annotated with {@code @Tool} that directly implements {@link Tool}.</li>
 *   <li>A class annotated with {@code @Tool} where a method (also annotated with
 *       {@code @Tool}) serves as the execution entry-point.</li>
 * </ul>
 */
public class ToolAnnotationProcessor implements BeanPostProcessor {

    private static final Logger log = LoggerFactory.getLogger(ToolAnnotationProcessor.class);

    private final ToolRegistry toolRegistry;
    private final Set<String> registeredClasses = new HashSet<>();

    public ToolAnnotationProcessor(ToolRegistry toolRegistry) {
        this.toolRegistry = toolRegistry;
    }

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
        try {
            Class<?> clazz = bean.getClass();
            String classKey = clazz.getName();
            if (registeredClasses.contains(classKey)) {
                return bean;
            }

            // Check for @Tool annotation via reflection (no compile-time dependency required)
            Object toolAnnot = findAnnotation(clazz, "Tool");
            if (toolAnnot != null) {
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

                // Find the execute method (annotated with @Tool on method, or first public
                // method with @Param)
                Method execMethod = findExecuteMethod(clazz);
                if (execMethod == null) {
                    // Class-level @Tool without a specific method — must implement Tool interface
                    if (bean instanceof Tool) {
                        toolRegistry.register((Tool) bean);
                        registeredClasses.add(classKey);
                        log.info("Registered tool '{}' (implements Tool interface with @Tool annotation)", name);
                    } else {
                        log.error("@Tool class '{}' does not implement Tool and has no executable method",
                                clazz.getName());
                    }
                    return bean;
                }

                ToolDefinition def = buildDefinition(name, desc, group, readonly, timeout, execMethod);
                AnnotatedToolAdapter adapter = new AnnotatedToolAdapter(
                        bean, name, desc, readonly, def, execMethod);
                toolRegistry.register(adapter);
                registeredClasses.add(classKey);
                log.info("注册工具成功！ '{}' (class: {})", name, clazz.getSimpleName());
            } else if (bean instanceof Tool tool) {
                // Old-style tool (implements Tool without @Tool annotation)
                toolRegistry.register(tool);
                registeredClasses.add(classKey);
                log.info("Registered legacy tool '{}' (class: {})", tool.getName(), clazz.getSimpleName());
            }
        } catch (Exception e) {
            log.error("Failed to process @Tool bean '{}': {}", beanName, e.getMessage(), e);
        }
        return bean;
    }

    // --- internal helpers ----------------------------------------------------

    private Method findExecuteMethod(Class<?> clazz) {
        for (Method m : clazz.getDeclaredMethods()) {
            if (findAnnotation(m, "Tool") != null) {
                return m;
            }
        }
        // Try first public method with @Param annotation
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
            } catch (Exception ignored) {
                // Fallback to defaults
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
        if (type == String.class) {
            return "string";
        }
        if (type == int.class || type == long.class || type == Integer.class
                || type == Long.class) {
            return "integer";
        }
        if (type == double.class || type == float.class || type == Double.class
                || type == Float.class) {
            return "number";
        }
        if (type == boolean.class || type == Boolean.class) {
            return "boolean";
        }
        return "string";
    }

    // --- reflection helpers (avoid compile dependency on framework annotations) ---

    private Object findAnnotation(Object obj, String simpleName) {
        Annotation[] anns;
        //获取类上的完整注解
        if (obj instanceof Class<?> c) {
            anns = c.getAnnotations();
        } else if (obj instanceof Method m) {
            anns = m.getAnnotations();
        } else if (obj instanceof java.lang.reflect.Parameter p) {
            anns = p.getAnnotations();
        } else {
            return null;
        }
        for (var a : anns) {
            if (a.annotationType().getSimpleName().equals(simpleName)) {
                return a;
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private <T> T getAnnotationAttr(Object ann, String attr, Class<T> type) {
        try {
            Object val = ann.getClass().getMethod(attr).invoke(ann);
            return (T) val;
        } catch (Exception e) {
            if (type == Boolean.class) {
                return (T) Boolean.FALSE;
            }
            if (type == Long.class) {
                return (T) Long.valueOf(0L);
            }
            return null;
        }
    }
}
