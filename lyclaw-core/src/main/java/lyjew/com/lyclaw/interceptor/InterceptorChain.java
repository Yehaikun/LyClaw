package lyjew.com.lyclaw.interceptor;

import lyjew.com.lyclaw.context.ChatContext;
import lyjew.com.lyclaw.dto.ChatResult;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

@Component
public class InterceptorChain {

    private final List<Interceptor> interceptors = new CopyOnWriteArrayList<>();

    public void addInterceptor(Interceptor interceptor) {
        this.interceptors.add(interceptor);
        this.interceptors.sort(Comparator.comparingInt(Interceptor::getOrder));
    }

    public void removeInterceptor(Interceptor interceptor) {
        this.interceptors.remove(interceptor);
        this.interceptors.sort(Comparator.comparingInt(Interceptor::getOrder));
    }

    public boolean preHandle(ChatContext context) {
        for (Interceptor interceptor : interceptors) {
            if (!interceptor.preHandle(context)) {
                return false;
            }
        }
        return true;
    }

    public void postHandle(ChatContext context, ChatResult result) {
        List<Interceptor> reversed = new ArrayList<>(interceptors);
        reversed.sort(Comparator.comparingInt(Interceptor::getOrder).reversed());
        for (Interceptor interceptor : reversed) {
            interceptor.postHandle(context, result);
        }
    }

    public List<Interceptor> getInterceptors() {
        return Collections.unmodifiableList(interceptors);
    }
}
