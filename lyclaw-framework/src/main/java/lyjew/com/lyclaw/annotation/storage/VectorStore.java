package lyjew.com.lyclaw.annotation.storage;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import lyjew.com.lyclaw.storage.DistanceFunction;

/**
 * 声明存储后端支持向量相似搜索。
 *
 * <p>框架根据此注解自动启用向量检索路径。维度、距离函数等元数据
 * 用于校验和默认参数填充。</p>
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface VectorStore {

    /** 向量维度 */
    int dimension();

    /** 支持的距离函数 */
    DistanceFunction[] distanceFunctions() default { DistanceFunction.COSINE };

    /** 是否支持元数据过滤 */
    boolean supportsMetadataFilter() default false;

    /** 是否支持混合搜索（向量 + 标量过滤） */
    boolean supportsHybridSearch() default false;
}
