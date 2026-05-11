package lyjew.com.lyclaw.autoconfigure.binding;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Introspects a method's parameters (including {@code @Param} annotations) and
 * provides a {@link #bindAndInvoke(Object, Map)} helper that maps named
 * argument values onto the method's parameter slots.
 */
public class ParameterBindingDescriptor {

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
            } catch (Exception ignored) {
                // Fallback to reflection-derived defaults
            }

            this.name = n;
            this.description = desc;
            this.required = req;
            this.defaultValue = (def != null && def.isEmpty()) ? null : def;
        }
    }
}
