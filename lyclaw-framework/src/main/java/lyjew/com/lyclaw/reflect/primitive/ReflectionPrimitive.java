package lyjew.com.lyclaw.reflect.primitive;

/** 所有反射原语的标记接口 */
public interface ReflectionPrimitive {
    default String getImplementationName() { return getClass().getSimpleName(); }
}
