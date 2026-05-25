package lyjew.com.lyclaw.reflect.primitive;

import lyjew.com.lyclaw.reflect.model.ReflectionContext;
import lyjew.com.lyclaw.reflect.model.RetrievalDecision;

@FunctionalInterface
public interface RetrievalGate extends ReflectionPrimitive {
    /** 在Actor执行前判断是否需要检索外部知识 */
    RetrievalDecision decide(ReflectionContext ctx);
}
