package lyjew.com.lyclaw.decorator;

import lyjew.com.lyclaw.annotation.chat.Fallback;
import lyjew.com.lyclaw.chat.ChatModel;
import lyjew.com.lyclaw.chat.ChatModelRegistry;
import lyjew.com.lyclaw.chat.ModelCapabilities;
import lyjew.com.lyclaw.model.ChatRequest;
import lyjew.com.lyclaw.model.ModelResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * 降级装饰器（Fallback Decorator），在主模型调用失败时按降级链顺序自动切换到备用模型。
 *
 * <p>本装饰器实现了服务降级（Service Fallback）模式，是框架弹性策略体系中的最后一道防线。
 * 当被装饰的 ChatModel 调用失败（抛出特定类型的异常）时，通过 Reactor 的 onErrorResume
 * 操作符捕获异常，按预先配置的降级链（chain）顺序依次尝试备用模型。降级链中的每个条目
 * 格式为 "provider:model"（如 "openai:gpt-4o-mini"），框架会通过 {@link ChatModelRegistry}
 * 查找对应的 ChatModel 实例并进行调用尝试。这种机制确保了即使主模型不可用，系统仍能
 * 通过备用模型继续提供服务，保障业务连续性。
 *
 * <p>降级触发条件通过 {@link Fallback#on()} 注解属性配置，可指定一组异常类名。只有
 * 实际抛出的异常是指定类型或其子类型时，才会触发降级。默认的触发异常类型为
 * {@link lyjew.com.lyclaw.exception.ModelException} 和
 * {@link java.util.concurrent.TimeoutException}，覆盖了模型调用异常和超时两种最常见的
 * 故障场景。如果捕获的异常不在配置的触发类型中，将直接向上传播而不执行降级。
 *
 * <p>降级链执行采用递归尝试模式：从链的第一个条目开始，逐个尝试调用备用模型。
 * 每个备用模型的调用如果也失败，则自动尝试链中的下一个条目。如果某条目格式无效
 * （不含冒号分隔符）或对应的模型在注册中心中未找到，则跳过该条目继续尝试下一个。
 * 当所有备用模型均不可用或调用失败时，返回包含 "所有降级模型均不可用" 错误信息
 * 的 Flux.error。成功降级的模型调用会通过 info 日志记录，失败的降级尝试通过
 * warn 日志记录，便于运维监控和问题排查。
 *
 * <p>典型的配置示例（通过注解声明）：
 * <pre>{@code @Fallback(chain = {"openai:gpt-4o-mini", "groq:llama-4"})}</pre>
 * 这表示主模型失败后，首先尝试 OpenAI 的 gpt-4o-mini，如果也失败则尝试 Groq 的 llama-4。
 *
 * @see lyjew.com.lyclaw.annotation.chat.Fallback
 * @see ChatModelRegistry
 */
public class FallbackChatModel implements ChatModel {

    private static final Logger log = LoggerFactory.getLogger(FallbackChatModel.class);

    private final ChatModel delegate;
    private final String[] chain;
    private final List<Class<? extends Throwable>> onExceptions;
    private final ChatModelRegistry registry;

    /**
     * 构造降级装饰器，从 {@link Fallback} 注解中提取降级链和异常配置。
     *
     * <p>构造时解析 on() 属性中指定的异常类名，通过反射（Class.forName）加载这些类。
     * 如果 on() 属性为空，则使用默认异常类型：ModelException 和 TimeoutException。
     * 如果 classNames 中包含无法加载的类名，将抛出 IllegalArgumentException。
     *
     * @param delegate 被装饰的主要 ChatModel 实例
     * @param policy   降级策略注解，提供降级链和触发异常类型配置
     * @param registry 模型注册中心，用于根据 "provider:model" 格式查找备用模型
     * @throws IllegalArgumentException 当 @Fallback.on() 中配置的异常类名无法加载时
     */
    public FallbackChatModel(ChatModel delegate, Fallback policy, ChatModelRegistry registry) {
        this.delegate = delegate;
        this.chain = policy.chain();
        this.registry = registry;
        try {
            this.onExceptions = parseExceptionClasses(policy.on());
        } catch (Exception e) {
            throw new IllegalArgumentException("@Fallback.on() 配置的异常类型无效", e);
        }
    }

    @Override
    public String provider() {
        return delegate.provider();
    }

    @Override
    public String model() {
        return delegate.model();
    }

    @Override
    public ModelCapabilities capabilities() {
        return delegate.capabilities();
    }

    /**
     * 执行流式调用，并在主模型失败时触发降级链。
     *
     * <p>通过 Reactor 的 onErrorResume 操作符拦截异常。当异常类型匹配降级触发条件时
     * （由 shouldFallback 判断），记录 warn 日志并调用 tryFallbackChain 启动降级链；
     * 否则将异常原样向上传播。降级触发条件通过 Fallback.on() 配置，默认包括
     * ModelException 和 TimeoutException。
     *
     * @param request 聊天请求对象
     * @return 主模型或降级模型的响应流，全部失败时返回错误流
     */
    @Override
    public Flux<ModelResponse> stream(ChatRequest request) {
        return delegate.stream(request)
                .onErrorResume(error -> {
                    if (shouldFallback(error)) {
                        log.warn("{} 调用失败，触发降级链: {}，错误: {}",
                                provider(), String.join(" -> ", chain),
                                error.getMessage(), error);
                        return tryFallbackChain(request);
                    }
                    return Flux.error(error);
                });
    }

    /**
     * 启动降级链的递归尝试过程，从链的第一个条目（索引 0）开始。
     *
     * <p>这是降级链执行的入口方法，将请求委托给 tryNextInChain 从索引 0 开始递归尝试。
     *
     * @param request 聊天请求对象
     * @return 第一个可用降级模型的响应流，或全部失败时的错误流
     */
    private Flux<ModelResponse> tryFallbackChain(ChatRequest request) {
        return tryNextInChain(request, 0);
    }

    /**
     * 递归尝试降级链中的第 index 个条目，失败时自动尝试下一个。
     *
     * <p>这是降级链的核心执行逻辑，采用递归方式实现链式尝试。对于每个条目：
     * <ol>
     *   <li>解析 "provider:model" 格式的条目字符串，提取 Provider 名称和模型名称</li>
     *   <li>格式无效（缺少冒号分隔符）时跳过该条目，记录 warn 日志</li>
     *   <li>通过 ChatModelRegistry 查找对应的 ChatModel 实例，未找到时跳过并记录 warn 日志</li>
     *   <li>找到模型后通过 onErrorResume 执行调用，失败时递归到下一个条目</li>
     * </ol>
     * 当所有条目均不可用或调用失败时，返回包含 "所有降级模型均不可用" 的 IllegalStateException。
     *
     * @param request 聊天请求对象
     * @param index   当前尝试的降级链索引
     * @return 成功降级模型的响应流，或递归到下一个/全部失败的错误流
     */
    private Flux<ModelResponse> tryNextInChain(ChatRequest request, int index) {
        if (index >= chain.length) {
            log.error("降级链中所有模型均不可用: {}", String.join(",", chain));
            return Flux.error(new IllegalStateException(
                    "所有降级模型均不可用，chain=" + String.join(",", chain)));
        }
        String entry = chain[index].trim();
        int colon = entry.indexOf(':');
        if (colon <= 0) {
            log.warn("降级链条目格式无效 (expected provider:model): {}", entry);
            return tryNextInChain(request, index + 1);
        }
        String provider = entry.substring(0, colon);
        String modelName = entry.substring(colon + 1);
        ChatModel fallbackModel = registry.resolve(provider, modelName);
        if (fallbackModel == null) {
            log.warn("降级模型未找到: {}:{}，尝试下一个", provider, modelName);
            return tryNextInChain(request, index + 1);
        }
        log.info("降级到 {}:{}", provider, modelName);
        return fallbackModel.stream(request)
                .onErrorResume(err -> {
                    log.warn("降级模型 {}:{} 调用失败: {}", provider, modelName, err.getMessage());
                    return tryNextInChain(request, index + 1);
                });
    }

    /**
     * 解析 @Fallback.on() 中配置的异常类名字符串数组为 Class 列表。
     *
     * <p>通过反射（Class.forName）加载每个类名字符串对应的 Class 对象，并进行泛型强制
     * 转换。如果 classNames 为 null 或空数组，则返回默认的异常类型列表（ModelException
     * 和 TimeoutException），覆盖最常见的模型调用失败和超时场景。
     *
     * @param classNames 异常类的全限定名字符串数组，可为 null 或空
     * @return 异常类的 Class 对象列表
     * @throws ClassNotFoundException 当某个类名字符串无法通过反射加载时
     */
    @SuppressWarnings("unchecked")
    private List<Class<? extends Throwable>> parseExceptionClasses(String[] classNames)
            throws ClassNotFoundException {
        if (classNames == null || classNames.length == 0) {
            return List.of(lyjew.com.lyclaw.exception.ModelException.class,
                    java.util.concurrent.TimeoutException.class);
        }
        List<Class<? extends Throwable>> result = new java.util.ArrayList<>();
        for (String className : classNames) {
            result.add((Class<? extends Throwable>) Class.forName(className));
        }
        return result;
    }

    /**
     * 判断给定的异常是否满足降级触发条件。
     *
     * <p>遍历 onExceptions 列表（从 Fallback.on() 配置解析的异常类型），使用
     * {@link Class#isInstance(Object)} 检查实际异常是否为指定类型或其子类型的实例。
     * 这种类型检查方式支持异常继承层次结构，例如如果配置了 RuntimeException.class，
     * 则所有 RuntimeException 的子类（NullPointerException、IllegalArgumentException
     * 等）都会触发降级。
     *
     * @param error 实际抛出的异常对象
     * @return true 表示应该触发降级，false 表示异常不应触发降级
     */
    private boolean shouldFallback(Throwable error) {
        for (Class<? extends Throwable> cls : onExceptions) {
            if (cls.isInstance(error)) return true;
        }
        return false;
    }

    @Override
    public int countTokens(String text) {
        return delegate.countTokens(text);
    }

    @Override
    public int countTokens(List<lyjew.com.lyclaw.model.Message> messages) {
        return delegate.countTokens(messages);
    }

    @Override
    public Mono<Boolean> validate() {
        return delegate.validate();
    }
}
