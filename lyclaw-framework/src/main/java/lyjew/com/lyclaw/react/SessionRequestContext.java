package lyjew.com.lyclaw.react;

/**
 * ThreadLocal持有者——将ChatController解析出的sessionId/agentId传递给AgentInvocationHandler。
 *
 * ChatController.resolveSession()查出或创建Session后，设置到此类；
 * AgentInvocationHandler.invoke()读取并使用，避免每次生成随机sessionId。
 * 在WebFlux环境下，invoke()在调用线程同步执行，ThreadLocal安全。
 */
public class SessionRequestContext {

    private static final ThreadLocal<String> sessionIdHolder = new ThreadLocal<>();
    private static final ThreadLocal<String> agentIdHolder = new ThreadLocal<>();

    public static void set(String sessionId, String agentId) {
        sessionIdHolder.set(sessionId);
        agentIdHolder.set(agentId);
    }

    public static String getSessionId() {
        return sessionIdHolder.get();
    }

    public static String getAgentId() {
        return agentIdHolder.get();
    }

    public static void clear() {
        sessionIdHolder.remove();
        agentIdHolder.remove();
    }
}
