package lyjew.com.lyclaw.annotation.storage;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import lyjew.com.lyclaw.storage.GraphQueryLanguage;

/**
 * 声明存储后端支持图遍历查询。
 *
 * <p>框架根据此注解自动启用实体关系查询的图遍历路径。</p>
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface GraphStore {

    /** 图查询语言 */
    GraphQueryLanguage queryLanguage() default GraphQueryLanguage.CYPHER;

    /** 是否支持递归遍历 */
    boolean supportsRecursiveTraversal() default true;

    /** 最大遍历深度 */
    int maxTraversalDepth() default 10;
}
