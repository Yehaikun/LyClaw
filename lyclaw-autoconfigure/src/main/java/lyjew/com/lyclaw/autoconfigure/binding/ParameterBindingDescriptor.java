package lyjew.com.lyclaw.autoconfigure.binding;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 方法参数绑定描述符，负责对目标方法的参数列表进行内省分析，并提供命名的参数值到方法
 * 形式参数的映射绑定和反射调用能力。
 *
 * <p><b>核心职责：</b>在 LyClaw 的工具执行流程中，LLM 以大语言模型返回的参数是键值对
 * 形式的命名参数（JSON 对象），而 Java 方法接收的是按位置排列的形式参数。本类的核心任务
 * 就是完成从"命名参数映射"到"位置参数数组"的转换，使得通过 @Tool 注解声明的任意 Java
 * 方法都可以被 LLM 以标准 JSON 参数格式调用。</p>
 *
 * <p><b>参数元数据提取：</b>构造时通过反射遍历目标方法的所有参数，为每个参数创建
 * {@link ParamInfo} 内部类实例。ParamInfo 在构造时会尝试从参数上的 {@code @Param}
 * 注解中读取以下元数据信息：</p>
 * <ul>
 *   <li><b>name：</b>参数名称，优先使用 @Param 注解中显式指定的名称，否则使用 Java
 *       反射提供的参数名（需要编译时保留参数名信息或者使用 -parameters 编译选项）</li>
 *   <li><b>description：</b>参数描述，帮助 LLM 理解参数的用途和格式要求</li>
 *   <li><b>required：</b>是否为必填参数，默认为 true，必填参数缺失时会抛出异常</li>
 *   <li><b>defaultValue：</b>默认值，当 LLM 未提供参数值时使用的回退值</li>
 *   <li><b>type：</b>参数的 Java 类型，用于后续的类型强制转换</li>
 * </ul>
 *
 * <p><b>类型转换机制：</b>{@link #coerce(Object, Class)} 方法提供了一套完善的值类型
 * 转换逻辑：Number 类型到各种数值类型的自动转换（int、long、double、float、short、byte）、
 * String 到 Enum 的自动解析、String 到 boolean 的自动转换等。这些转换使得 LLM 传递的
 * 字符串参数能够自动适配方法声明的具体类型，大大降低了工具开发的复杂度。</p>
 *
 * <p><b>异常处理：</b>当必填参数缺失时，抛出 {@link IllegalArgumentException} 并附带
 * 明确的参数名称信息，帮助开发者快速定位问题参数。</p>
 */
public class ParameterBindingDescriptor {
    private static final Logger log = LoggerFactory.getLogger(ParameterBindingDescriptor.class);

    private final Method method;
    private final List<ParamInfo> params;

    public ParameterBindingDescriptor(Method method) {
        this.method = method;
        this.params = new ArrayList<>();
        for (Parameter p : method.getParameters()) {
            params.add(new ParamInfo(p));
        }
    }

    public Method getMethod() {
        return method;
    }

    public List<ParamInfo> getParams() {
        return params;
    }

    /**
     * Resolve parameter values from the supplied map, apply simple type
     * conversions, and invoke the method on the target instance.
     *
     * @param target the object on which the method should be invoked
     * @param args   named argument values keyed by parameter name
     * @return the method's return value (may be {@code null})
     * @throws Exception if any parameter constraint is violated or invocation fails
     */
    public Object bindAndInvoke(Object target, Map<String, Object> args) throws Exception {
        Object[] paramValues = new Object[params.size()];
        for (int i = 0; i < params.size(); i++) {
            ParamInfo pi = params.get(i);
            Object value = args.get(pi.name);

            if (value == null && pi.required) {
                throw new IllegalArgumentException(
                        "Missing required parameter: " + pi.name);
            }
            if (value == null && pi.defaultValue != null && !pi.defaultValue.isEmpty()) {
                value = pi.defaultValue;
            }
            // Simple type coercion for primitive/wrapper compatibility
            if (value != null) {
                value = coerce(value, pi.type);
            }
            paramValues[i] = value;
        }
        return method.invoke(target, paramValues);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Object coerce(Object value, Class<?> targetType) {
        if (value == null) {
            return null;
        }
        Class<?> sourceType = value.getClass();

        // Already assignment-compatible
        if (targetType.isAssignableFrom(sourceType)) {
            return value;
        }

        // Number -> numeric target
        if (value instanceof Number num) {
            if (targetType == int.class || targetType == Integer.class) {
                return num.intValue();
            }
            if (targetType == long.class || targetType == Long.class) {
                return num.longValue();
            }
            if (targetType == double.class || targetType == Double.class) {
                return num.doubleValue();
            }
            if (targetType == float.class || targetType == Float.class) {
                return num.floatValue();
            }
            if (targetType == short.class || targetType == Short.class) {
                return num.shortValue();
            }
            if (targetType == byte.class || targetType == Byte.class) {
                return num.byteValue();
            }
        }

        // String -> Enum
        if (targetType.isEnum() && value instanceof String s) {
            return Enum.valueOf((Class<? extends Enum>) targetType, s);
        }

        // String -> boolean
        if ((targetType == boolean.class || targetType == Boolean.class)
                && !(value instanceof Boolean)) {
            return Boolean.valueOf(value.toString());
        }

        // Fallback: keep as-is
        return value;
    }

    /**
     * Metadata about a single method parameter, resolved from reflection and
     * optional {@code @Param} annotations.
     */
    public static class ParamInfo {

        public final String name;
        public final String description;
        public final boolean required;
        public final String defaultValue;
        public final Class<?> type;

        ParamInfo(Parameter p) {
            this.type = p.getType();
            String n = p.getName();
            String desc = "";
            boolean req = true;
            String def = null;

            try {
                for (java.lang.annotation.Annotation a : p.getAnnotations()) {
                    if ("Param".equals(a.annotationType().getSimpleName())) {
                        String an = (String) a.annotationType().getMethod("name").invoke(a);
                        if (an != null && !an.isEmpty()) {
                            n = an;
                        }
                        desc = (String) a.annotationType().getMethod("description").invoke(a);
                        req = (Boolean) a.annotationType().getMethod("required").invoke(a);
                        def = (String) a.annotationType().getMethod("defaultValue").invoke(a);
                        break;
                    }
                }
            } catch (Exception e) {
                log.debug("Failed to read @Param annotation on {}: {}", p.getName(), e.getMessage());
            }

            this.name = n;
            this.description = desc;
            this.required = req;
            this.defaultValue = (def != null && def.isEmpty()) ? null : def;
        }
    }
}
