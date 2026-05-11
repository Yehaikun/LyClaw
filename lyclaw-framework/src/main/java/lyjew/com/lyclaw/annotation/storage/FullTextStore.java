package lyjew.com.lyclaw.annotation.storage;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import lyjew.com.lyclaw.storage.FullTextEngine;

/**
 * 声明存储后端支持全文搜索。
 *
 * <p>框架根据此注解自动启用 BM25 等全文检索路径。</p>
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface FullTextStore {

    /** 搜索引擎类型 */
    FullTextEngine engine() default FullTextEngine.BM25;

    /** 是否支持中文分词 */
    boolean supportsChineseSegmentation() default false;
}
