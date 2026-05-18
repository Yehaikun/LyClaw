# 线程池监控、可观测性与性能诊断技术研究报告

> **研究时间**：2024—2026  
> **适用范围**：Java 8/11/17/21+，Spring Boot 2.x/3.x，JDK 原生 `ThreadPoolExecutor`  
> **核心原则**：没有监控的线程池就是生产环境的定时炸弹。队列持续增长是系统"慢性死亡"最明显的先行指标——等你从用户那里听到反馈时，故障已经发生。

---

## 目录

1. [ThreadPoolExecutor 内置监控指标](#一threadpoolexecutor-内置监控指标)
2. [ThreadPoolExecutor 生命周期钩子](#二threadpoolexecutor-生命周期钩子)
3. [ThreadFactory 自定义线程工厂](#三threadfactory-自定义线程工厂)
4. [JMX 暴露线程池指标](#四jmx-暴露线程池指标)
5. [Micrometer 指标采集与注册](#五micrometer-指标采集与注册)
6. [Spring Boot Actuator 集成](#六spring-boot-actuator-集成)
7. [常见线程池问题与诊断方法](#七常见线程池问题与诊断方法)
8. [线程转储（Thread Dump）分析](#八线程转储thread-dump分析)
9. [性能分析工具](#九性能分析工具)
10. [Grafana / Prometheus 可观测性看板](#十grafana--prometheus-可观测性看板)
11. [告警规则设计](#十一告警规则设计)
12. [附录：生产环境检查清单](#十二生产环境检查清单)

---

## 一、ThreadPoolExecutor 内置监控指标

### 1.1 核心 API 及其含义

`ThreadPoolExecutor` 提供了丰富的公开方法，无需侵入即可实时获取运行时状态：

| API 方法 | 返回类型 | 含义 | 变化趋势解读 |
|----------|---------|------|-------------|
| `getActiveCount()` | int | 当前正在执行任务的线程数（近似值） | 上升表示负载增加 |
| `getPoolSize()` | int | 当前池中线程总数 | 观察扩/缩容行为 |
| `getLargestPoolSize()` | int | 池曾达到的最大线程数 | 历史上是否逼近过瓶颈 |
| `getQueue().size()` | int | 当前队列中排队等待的任务数 | **雪崩前最明显的先行信号** |
| `getCompletedTaskCount()` | long | 已完成任务总数（累计） | 须计算速率才有意义 |
| `getTaskCount()` | long | 已提交的总任务数（累计） | 配合完成数判断"进多出少" |
| `getCorePoolSize()` | int | 核心线程数 | 配置值，一般不变 |
| `getMaximumPoolSize()` | int | 最大线程数 | 配置值 |

### 1.2 如何从累计值推导速率指标

`getCompletedTaskCount()` 和 `getTaskCount()` 都是单调递增的累计值（Counter 语义），需要计算速率：

```java
public class ThreadPoolMetrics {
    private long lastCompletedTaskCount = 0;
    private long lastTimestamp = System.currentTimeMillis();

    /**
     * 计算任务完成率（tasks/second），调用间隔建议 5-15 秒
     */
    public double getCompletionRate(ThreadPoolExecutor executor) {
        long current = executor.getCompletedTaskCount();
        long now = System.currentTimeMillis();
        double rate = (current - lastCompletedTaskCount) * 1000.0
                      / Math.max(1, now - lastTimestamp);
        lastCompletedTaskCount = current;
        lastTimestamp = now;
        return rate;
    }

    /**
     * 计算线程饱和度 = 活跃线程 / 最大线程
     */
    public double getSaturation(ThreadPoolExecutor executor) {
        int max = executor.getMaximumPoolSize();
        return max > 0 ? (double) executor.getActiveCount() / max : 0.0;
    }

    /**
     * 计算队列水位 = 队列当前任务数 / 队列容量
     */
    public double getQueueWatermark(ThreadPoolExecutor executor) {
        int capacity = executor.getQueue().remainingCapacity()
                       + executor.getQueue().size();
        return capacity > 0
               ? (double) executor.getQueue().size() / capacity
               : 0.0;
    }
}
```

### 1.3 定时采集模式

使用 `ScheduledExecutorService` 定时打印或上报指标，适合快速验证：

```java
ScheduledExecutorService monitor = Executors.newSingleThreadScheduledExecutor(r -> {
    Thread t = new Thread(r, "threadpool-monitor");
    t.setDaemon(true);
    return t;
});

monitor.scheduleAtFixedRate(() -> {
    ThreadPoolExecutor pool = getBusinessThreadPool();
    log.info("pool-status name=order-pool "
             + "active={} poolSize={} largestPoolSize={} "
             + "queueSize={} completed={} saturation={:.2f}",
             pool.getActiveCount(),
             pool.getPoolSize(),
             pool.getLargestPoolSize(),
             pool.getQueue().size(),
             pool.getCompletedTaskCount(),
             (double) pool.getActiveCount() / pool.getMaximumPoolSize());
}, 0, 10, TimeUnit.SECONDS);
```

### 1.4 "慢性死亡"的五种危险信号

1. **`activeCount` 长期等于 `maxPoolSize`** — 线程不够用或任务执行太慢
2. **`queue.size()` 持续增长** — 消费速度跟不上生产速度，即将雪崩
3. **`completedTaskCount` 增速变缓** — 单个任务耗时变长
4. **拒绝策略频繁触发** — 用户已感知故障
5. **`poolSize` 始终达不到 `max`** — 典型无界队列坑（`LinkedBlockingQueue` 未设容量）

---

## 二、ThreadPoolExecutor 生命周期钩子

### 2.1 钩子方法总览

`ThreadPoolExecutor` 提供三个 `protected` 钩子方法：

| 方法 | 调用时机 | 调用线程 | 用途 |
|------|---------|---------|------|
| `beforeExecute(Thread t, Runnable r)` | 任务执行前 | 工作线程 | 记录开始时间、设置 MDC、登录日志 |
| `afterExecute(Runnable r, Throwable t)` | 任务执行后（含异常） | 工作线程 | 记录耗时、上报指标、捕获异常 |
| `terminated()` | 线程池完全终止后 | 最后一个退出的线程 | 清理资源、发送关闭通知 |

### 2.2 注意 `super` 调用顺序

这是 JDK 文档明确规定的调用惯例：

- **`beforeExecute`**：在你的逻辑**之后**调用 `super.beforeExecute(t, r)`
- **`afterExecute`**：在你的逻辑**之前**调用 `super.afterExecute(r, t)`

### 2.3 完整示例：带计时和异常捕获的钩子

```java
public class MonitoredThreadPoolExecutor extends ThreadPoolExecutor {

    private static final Logger log =
        LoggerFactory.getLogger(MonitoredThreadPoolExecutor.class);

    private final MeterRegistry meterRegistry;
    private final String poolName;
    private final Counter rejectedCounter;
    private final Timer taskExecutionTimer;

    // 使用 ThreadLocal 存储任务开始时间
    private final ThreadLocal<Long> startTime = new ThreadLocal<>();

    public MonitoredThreadPoolExecutor(
            int corePoolSize, int maximumPoolSize,
            long keepAliveTime, TimeUnit unit,
            BlockingQueue<Runnable> workQueue,
            ThreadFactory threadFactory,
            RejectedExecutionHandler handler,
            MeterRegistry meterRegistry,
            String poolName) {
        super(corePoolSize, maximumPoolSize, keepAliveTime, unit,
              workQueue, threadFactory, handler);
        this.meterRegistry = meterRegistry;
        this.poolName = poolName;

        this.taskExecutionTimer = Timer.builder("threadpool.task.execution")
            .tag("pool", poolName)
            .publishPercentiles(0.5, 0.95, 0.99)
            .publishPercentileHistogram()
            .register(meterRegistry);

        this.rejectedCounter = Counter.builder("threadpool.task.rejected")
            .tag("pool", poolName)
            .register(meterRegistry);
    }

    @Override
    protected void beforeExecute(Thread t, Runnable r) {
        startTime.set(System.nanoTime());
        log.debug("Task starting on thread {} in pool {}",
                  t.getName(), poolName);
        super.beforeExecute(t, r);
    }

    @Override
    protected void afterExecute(Runnable r, Throwable t) {
        super.afterExecute(r, t);

        long elapsed = System.nanoTime() - startTime.get();
        startTime.remove();
        taskExecutionTimer.record(elapsed, TimeUnit.NANOSECONDS);

        if (t != null) {
            log.error("Task execution failed in pool {} on thread {}",
                      poolName, Thread.currentThread().getName(), t);
            return;
        }

        // 关键：FutureTask 会吞掉异常，须在这里主动探测
        if (r instanceof Future<?> future) {
            try {
                future.get(); // 如果任务内部抛了异常，这里会再次抛出
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (ExecutionException e) {
                log.error("Task threw exception in pool {}",
                          poolName, e.getCause());
            }
        }
    }

    @Override
    protected void terminated() {
        log.info("Thread pool {} has terminated. "
                 + "Completed tasks: {}, Largest pool size: {}",
                 poolName, getCompletedTaskCount(), getLargestPoolSize());
        super.terminated();
    }
}
```

### 2.4 MDC 上下文传播

SLF4J 的 MDC 是基于 `ThreadLocal` 的，子线程不会自动继承父线程的诊断上下文。两种方案实现传播：

#### 方案一：覆盖 `execute()` / `submit()` 包装任务

```java
public class MdcAwareThreadPoolExecutor extends ThreadPoolExecutor {

    public MdcAwareThreadPoolExecutor(int corePoolSize, int maximumPoolSize,
            long keepAliveTime, TimeUnit unit,
            BlockingQueue<Runnable> workQueue) {
        super(corePoolSize, maximumPoolSize, keepAliveTime, unit, workQueue);
    }

    @Override
    public void execute(Runnable command) {
        Map<String, String> mdcContext = MDC.getCopyOfContextMap();
        super.execute(() -> {
            if (mdcContext != null) {
                MDC.setContextMap(mdcContext);
            } else {
                MDC.clear();
            }
            try {
                command.run();
            } finally {
                MDC.clear(); // 必须清除，避免线程复用时泄露
            }
        });
    }

    @Override
    public <T> Future<T> submit(Callable<T> task) {
        Map<String, String> mdcContext = MDC.getCopyOfContextMap();
        return super.submit(() -> {
            if (mdcContext != null) {
                MDC.setContextMap(mdcContext);
            } else {
                MDC.clear();
            }
            try {
                return task.call();
            } finally {
                MDC.clear();
            }
        });
    }
}
```

#### 方案二：Spring `TaskDecorator`（推荐，仅限 `ThreadPoolTaskExecutor`）

```java
@Component
public class MdcTaskDecorator implements TaskDecorator {
    @Override
    public Runnable decorate(Runnable runnable) {
        Map<String, String> contextMap = MDC.getCopyOfContextMap();
        return () -> {
            try {
                if (contextMap != null) {
                    MDC.setContextMap(contextMap);
                }
                runnable.run();
            } finally {
                MDC.clear();
            }
        };
    }
}
```

### 2.5 钩子方法执行时序总结

```
提交线程:                        工作线程:
    │                                │
    ├─ MDC.getCopyOfContextMap()     │
    ├─ executor.execute(wrapper)     │
    │                                ├─ beforeExecute(thread, runnable)
    │                                ├─ MDC.setContextMap(savedMap)
    │                                ├─ runnable.run()
    │                                ├─ MDC.clear()
    │                                └─ afterExecute(runnable, throwable)
```

---

## 三、ThreadFactory 自定义线程工厂

### 3.1 为什么必须自定义线程工厂

默认的 `Executors.defaultThreadFactory()` 创建的线程名为 `pool-N-thread-M`，在 `jstack` 输出或日志中无法区分业务含义。自定义 `ThreadFactory` 的核心收益：

- 线程命名有意义，thread dump 一目了然
- 统一设置守护状态、优先级
- 注册 `UncaughtExceptionHandler`，防止异常静默吞噬
- 自定义 ThreadLocal 初始化

### 3.2 手写 ThreadFactory 实现

```java
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

public class NamedThreadFactory implements ThreadFactory {

    private final AtomicInteger threadNumber = new AtomicInteger(1);
    private final String namePrefix;
    private final boolean daemon;
    private final int priority;

    public NamedThreadFactory(String namePrefix) {
        this(namePrefix, false, Thread.NORM_PRIORITY);
    }

    public NamedThreadFactory(String namePrefix, boolean daemon, int priority) {
        this.namePrefix = namePrefix;
        this.daemon = daemon;
        this.priority = priority;
    }

    @Override
    public Thread newThread(Runnable r) {
        Thread t = new Thread(r, namePrefix + "-" + threadNumber.getAndIncrement());
        t.setDaemon(daemon);
        if (t.getPriority() != priority) {
            t.setPriority(priority);
        }
        t.setUncaughtExceptionHandler((thread, ex) ->
            System.err.println("Uncaught exception in " +
                               thread.getName() + ": " + ex.getMessage()));
        return t;
    }
}

// 用法
ThreadPoolExecutor executor = new ThreadPoolExecutor(
    4, 8, 60, TimeUnit.SECONDS,
    new LinkedBlockingQueue<>(200),
    new NamedThreadFactory("http-worker"),
    new ThreadPoolExecutor.CallerRunsPolicy()
);
```

### 3.3 第三方库的现成方案

#### Google Guava `ThreadFactoryBuilder`（推荐）

```java
import com.google.common.util.concurrent.ThreadFactoryBuilder;

ThreadFactory factory = new ThreadFactoryBuilder()
    .setNameFormat("db-pool-%d")       // %d 从 0 开始
    .setDaemon(false)
    .setPriority(Thread.NORM_PRIORITY)
    .setUncaughtExceptionHandler((t, e) ->
        log.error("Thread {} died unexpectedly", t.getName(), e))
    .build();
```

#### Apache Commons Lang `BasicThreadFactory`

```java
import org.apache.commons.lang3.concurrent.BasicThreadFactory;

BasicThreadFactory factory = new BasicThreadFactory.Builder()
    .namingPattern("async-mail-%d")   // %d 从 1 开始
    .daemon(false)
    .priority(Thread.NORM_PRIORITY)
    .uncaughtExceptionHandler((t, e) ->
        log.error("Thread {} died unexpectedly", t.getName(), e))
    .build();
```

#### Spring `CustomizableThreadFactory`

```java
import org.springframework.scheduling.concurrent.CustomizableThreadFactory;

CustomizableThreadFactory factory =
    new CustomizableThreadFactory("order-worker-");
factory.setDaemon(false);
factory.setThreadPriority(Thread.NORM_PRIORITY);
```

### 3.4 最佳实践速查表

| 实践 | 理由 |
|------|------|
| 使用有意义的前缀命名 | 如 `http-worker-`、`db-pool-`、`async-mail-`，jstack 和日志中立即分辨 |
| 默认 `daemon=false` | 守护线程会在 JVM 退出时被强制终止，仅后台监控类线程设 daemon=true |
| 保持 `NORM_PRIORITY(5)` | 线程优先级只是 OS 调度器提示，不可靠；误用易导致优先级反转 |
| 务必设置 `UncaughtExceptionHandler` | 线程池中的线程异常如果没有 handler，会被静默吞噬 |
| 不要用线程优先级控制任务执行顺序 | 应使用 `PriorityBlockingQueue` 实现任务级别优先级 |

---

## 四、JMX 暴露线程池指标

### 4.1 自定义 MBean 注册

将线程池指标封装为 MBean 并注册到平台 `MBeanServer`，即可通过 JConsole、VisualVM、Glowroot、Zabbix、Prometheus JMX Exporter 等进行查看。

#### 定义 MBean 接口

```java
public interface ThreadPoolMonitorMBean {
    int getActiveCount();
    int getPoolSize();
    int getLargestPoolSize();
    int getQueueSize();
    int getCorePoolSize();
    int getMaximumPoolSize();
    long getCompletedTaskCount();
    long getTaskCount();
    double getQueueWatermark();
    double getThreadSaturation();
}
```

#### MBean 实现 + 注册

```java
import javax.management.*;
import java.lang.management.ManagementFactory;

public class ThreadPoolMonitor implements ThreadPoolMonitorMBean {

    private final ThreadPoolExecutor executor;
    private final String poolName;

    public ThreadPoolMonitor(ThreadPoolExecutor executor, String poolName) {
        this.executor = executor;
        this.poolName = poolName;
    }

    @Override
    public int getActiveCount()        { return executor.getActiveCount(); }
    @Override
    public int getPoolSize()           { return executor.getPoolSize(); }
    @Override
    public int getLargestPoolSize()    { return executor.getLargestPoolSize(); }
    @Override
    public int getQueueSize()          { return executor.getQueue().size(); }
    @Override
    public int getCorePoolSize()       { return executor.getCorePoolSize(); }
    @Override
    public int getMaximumPoolSize()    { return executor.getMaximumPoolSize(); }
    @Override
    public long getCompletedTaskCount(){ return executor.getCompletedTaskCount(); }
    @Override
    public long getTaskCount()         { return executor.getTaskCount(); }

    @Override
    public double getQueueWatermark() {
        int remaining = executor.getQueue().remainingCapacity();
        int total = executor.getQueue().size() + remaining;
        return total > 0 ? (double) executor.getQueue().size() / total : 0.0;
    }

    @Override
    public double getThreadSaturation() {
        int max = executor.getMaximumPoolSize();
        return max > 0 ? (double) executor.getActiveCount() / max : 0.0;
    }

    /**
     * 注册到 JMX MBeanServer
     */
    public void register() {
        MBeanServer mbs = ManagementFactory.getPlatformMBeanServer();
        try {
            ObjectName name = new ObjectName(
                "com.example:type=ThreadPool,name=" + poolName);
            if (!mbs.isRegistered(name)) {
                mbs.registerMBean(this, name);
                System.out.println("Registered MBean: " + name);
            }
        } catch (InstanceAlreadyExistsException
                | MBeanRegistrationException
                | NotCompliantMBeanException
                | MalformedObjectNameException e) {
            throw new RuntimeException("Failed to register ThreadPool MBean", e);
        }
    }

    /**
     * 从 JMX 注销
     */
    public void unregister() {
        MBeanServer mbs = ManagementFactory.getPlatformMBeanServer();
        try {
            ObjectName name = new ObjectName(
                "com.example:type=ThreadPool,name=" + poolName);
            if (mbs.isRegistered(name)) {
                mbs.unregisterMBean(name);
            }
        } catch (Exception e) {
            // ignore on shutdown
        }
    }
}
```

### 4.2 另选方案：JMX Exporter for Prometheus

在非 Spring Boot 环境中，也可以通过 JMX Exporter 自动转换成 Prometheus 格式：

```yaml
# config.yaml
startDelaySeconds: 0
ssl: false
lowercaseOutputName: true
lowercaseOutputLabelNames: true
rules:
  - pattern: 'com.example<type=ThreadPool, name=(.+)><>(activeCount|poolSize|queueSize|completedTaskCount):'
    name: threadpool_$2
    labels:
      pool: "$1"
    type: GAUGE
```

### 4.3 OpenJDK 内置 JMX Bean 补充（JDK 21+）

JDK 21+ 引入了 `VirtualThreadSchedulerMXBean`（JDK-8338890），可查询虚拟线程调度器的并行度。对于 `ThreadPoolExecutor` 虚拟线程调度器，返回 `getMaximumPoolSize()`。

---

## 五、Micrometer 指标采集与注册

### 5.1 三大核心指标类型在线程池中的应用

| 类型 | 语义 | 适用场景 |
|------|------|---------|
| **Gauge** | 瞬时值（可增可减） | 活跃线程数、队列大小、核心/最大线程数 |
| **Counter** | 只增不减的累积值 | 任务拒绝次数、任务失败次数 |
| **Timer** | 耗时 + 调用次数统计 | 单个任务执行耗时分布 |

### 5.2 完全示例：注册所有推荐指标

```java
import io.micrometer.core.instrument.*;
import io.micrometer.core.instrument.binder.MeterBinder;

public class ThreadPoolMetricsBinder implements MeterBinder {

    private final ThreadPoolExecutor executor;
    private final String poolName;

    public ThreadPoolMetricsBinder(ThreadPoolExecutor executor, String poolName) {
        this.executor = executor;
        this.poolName = poolName;
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        // Gauge：瞬时指标
        Gauge.builder("threadpool.active.threads", executor,
                      ThreadPoolExecutor::getActiveCount)
            .tag("pool", poolName)
            .description("当前活跃线程数")
            .register(registry);

        Gauge.builder("threadpool.pool.size", executor,
                      ThreadPoolExecutor::getPoolSize)
            .tag("pool", poolName)
            .description("当前线程池总线程数")
            .register(registry);

        Gauge.builder("threadpool.largest.pool.size", executor,
                      ThreadPoolExecutor::getLargestPoolSize)
            .tag("pool", poolName)
            .description("历史最大线程数")
            .register(registry);

        Gauge.builder("threadpool.queue.size", executor,
                      e -> (double) e.getQueue().size())
            .tag("pool", poolName)
            .description("队列中等待的任务数")
            .register(registry);

        Gauge.builder("threadpool.core.size", executor,
                      ThreadPoolExecutor::getCorePoolSize)
            .tag("pool", poolName)
            .description("核心线程数配置")
            .register(registry);

        Gauge.builder("threadpool.max.size", executor,
                      ThreadPoolExecutor::getMaximumPoolSize)
            .tag("pool", poolName)
            .description("最大线程数配置")
            .register(registry);

        // Gauge：推导指标
        Gauge.builder("threadpool.saturation", executor, e ->
            e.getMaximumPoolSize() > 0
                ? (double) e.getActiveCount() / e.getMaximumPoolSize()
                : 0.0)
            .tag("pool", poolName)
            .description("线程饱和度 (0~1)")
            .register(registry);

        Gauge.builder("threadpool.queue.watermark", executor, e -> {
            int remaining = e.getQueue().remainingCapacity();
            int total = e.getQueue().size() + remaining;
            return total > 0 ? (double) e.getQueue().size() / total : 0.0;
        })
            .tag("pool", poolName)
            .description("队列水位 (0~1)")
            .register(registry);

        // Counter：拒绝次数需在 RejectedExecutionHandler 中调用（见下文）
    }

    // 可选：手动注册拒绝计数器
    public Counter registerRejectionCounter(MeterRegistry registry) {
        return Counter.builder("threadpool.rejected.total")
            .tag("pool", poolName)
            .description("任务被拒绝的次数")
            .register(registry);
    }
}
```

### 5.3 拒绝告警的自定义 RejectedExecutionHandler

```java
public class MonitoredRejectedExecutionHandler implements RejectedExecutionHandler {

    private static final Logger log =
        LoggerFactory.getLogger(MonitoredRejectedExecutionHandler.class);
    private final Counter rejectedCounter;
    private final RejectedExecutionHandler delegate;

    public MonitoredRejectedExecutionHandler(Counter rejectedCounter,
                                              RejectedExecutionHandler delegate) {
        this.rejectedCounter = rejectedCounter;
        this.delegate = delegate;
    }

    @Override
    public void rejectedExecution(Runnable r, ThreadPoolExecutor executor) {
        rejectedCounter.increment();
        log.warn("Task rejected: pool={} active={} poolSize={} queueSize={} "
                 + "maxSize={} completed={}",
                 "biz-pool",
                 executor.getActiveCount(),
                 executor.getPoolSize(),
                 executor.getQueue().size(),
                 executor.getMaximumPoolSize(),
                 executor.getCompletedTaskCount());
        // 交给标准拒绝策略处理
        delegate.rejectedExecution(r, executor);
    }
}
```

### 5.4 Maven 依赖

```xml
<dependency>
    <groupId>io.micrometer</groupId>
    <artifactId>micrometer-registry-prometheus</artifactId>
</dependency>
<!-- Micrometer Core 通常由 spring-boot-starter-actuator 传递引入 -->
```

### 5.5 SynchronousQueue 特殊处理

使用 `SynchronousQueue` 的线程池，`queue.size()` 永远为 0，不能据此判断积压。此时应关注**拒绝率**和**活跃线程数**。

---

## 六、Spring Boot Actuator 集成

### 6.1 配置 /actuator/prometheus 端点（Spring Boot 3.x）

```yaml
# application.yml
management:
  endpoints:
    web:
      exposure:
        include: health,info,metrics,prometheus
  metrics:
    tags:
      application: ${spring.application.name:unknown}
    export:
      prometheus:
        enabled: true
  endpoint:
    health:
      show-details: always
```

### 6.2 自动注册 `@Bean` 线程池

```java
@Configuration
public class ThreadPoolConfiguration {

    @Bean(name = "orderThreadPool")
    public ThreadPoolExecutor orderThreadPool() {
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
            4, 8, 60, TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(200),
            new NamedThreadFactory("order-worker"),
            new ThreadPoolExecutor.CallerRunsPolicy()
        );
        return executor;
    }

    @Bean
    public MeterBinder orderThreadPoolMetrics(
            @Qualifier("orderThreadPool") ThreadPoolExecutor executor) {
        return new ThreadPoolMetricsBinder(executor, "order");
    }
}
```

### 6.3 验证指标暴露

启动应用后，访问 `http://localhost:8080/actuator/prometheus`，应能看到类似指标：

```
# HELP threadpool_active_threads 当前活跃线程数
# TYPE threadpool_active_threads gauge
threadpool_active_threads{pool="order"} 2.0
threadpool_queue_size{pool="order"} 15.0
threadpool_saturation{pool="order"} 0.25
threadpool_queue_watermark{pool="order"} 0.075
...
```

### 6.4 端点安全

生产环境不能将 Actuator 端点暴露到公网：

```yaml
management:
  server:
    port: 8081              # 单独端口
  endpoints:
    web:
      base-path: /actuator
```

配合 Spring Security：

```java
@Configuration
public class ActuatorSecurityConfig {

    @Bean
    public SecurityFilterChain actuatorFilterChain(HttpSecurity http) throws Exception {
        http.securityMatcher(EndpointRequest.toAnyEndpoint())
            .authorizeHttpRequests(auth -> auth.anyRequest().hasRole("ACTUATOR"))
            .httpBasic(Customizer.withDefaults());
        return http.build();
    }
}
```

---

## 七、常见线程池问题与诊断方法

### 7.1 线程饥饿（Thread Starvation）

**现象**：所有工作线程被阻塞在某个操作上（如等待数据库连接、等待远程调用），新任务无法获得执行机会。

**典型 jstack 特征**：大量 `pool-X-thread-Y` 处于 `WAITING` 或 `BLOCKED` 状态，集中在：
- `LinkedBlockingQueue.take()` 等待任务入队（正常空闲）
- `SocketInputStream.socketRead0()` 等待网络 IO（可能正常）
- `HikariCP.getConnection()` 等待数据库连接（连接池已耗尽）
- `LockSupport.park()` 条件等待

**诊断方法**：

```bash
# 统计线程状态分布
jstack <PID> | grep "java.lang.Thread.State" | sort | uniq -c | sort -nr

# 查找所有 BLOCKED 线程
jstack <PID> | grep -A 10 "BLOCKED"

# 查找池内线程中哪些在等待
jstack <PID> | grep -B 2 "pool.*thread" | grep "State"
```

**解决方案**：
1. 增加核心资源容量（数据库连接池、HTTP 连接池）
2. 为阻塞操作添加超时，避免无限等待
3. 将阻塞操作从线程池中剥离，改用异步/响应式编程
4. 使用信号量或熔断器限制进入量

### 7.2 线程泄露（Thread Leak）

**现象**：`poolSize` 持续增长不下降，最终达到 `maximumPoolSize` 甚至 OOM。

**根因**：
- 任务执行时间超过了 `keepAliveTime` 但线程状态为 `WAITING` 而非 `RUNNABLE`（JDK 在某些版本中只有 `RUNNABLE` 空闲线程会被回收）
- `corePoolSize` 设置过高
- 持续创建新任务但任务提交速率远大于处理速率

**诊断方法**：

```bash
# 观察线程数变化趋势
watch -n 5 "jstack <PID> | grep 'pool-X-thread' | wc -l"

# JMX 持续观察 poolSize 趋势
```

**解决方案**：
1. `allowCoreThreadTimeOut(true)` 让核心线程也可超时回收
2. 检查 `keepAliveTime` 是否合理
3. 检查任务是否有导致线程无限等待的 bug

### 7.3 队列积压（Queue Buildup）

**现象**：`queue.size()` 持续增长，任务延时飙升，最终触发拒绝或 OOM。

**这是线程池最危险的信号**，因为当队列很深时：
- 任务在队列中等待的时间远超执行时间
- 应用已经超载但拒绝了所有新的外部请求被队列缓冲
- 最终队列满或内存耗尽才暴露

**诊断方法**：

```bash
# Arthas 查看队列大小
dashboard

# 监控指标
threadpool_queue_watermark > 0.8 持续 30s
```

**解决方案**：
1. 使用有界队列（永远不要用无界 `LinkedBlockingQueue`）
2. 设置合理的队列容量，快速失败优于慢性死亡
3. 提高消费速率（增加线程数、优化任务逻辑）
4. 限制生产速率（上游限流、降级）

### 7.4 拒绝执行风暴（Rejected Execution Storm）

**现象**：`RejectedExecutionException` 大量出现，任务被丢弃。

**四种内置拒绝策略对比**：

| 策略 | 行为 | 适用场景 | 陷阱 |
|------|------|---------|------|
| `AbortPolicy`（默认） | 抛 `RejectedExecutionException` | 需要感知过载 | 未捕获会导致提交方崩溃 |
| `CallerRunsPolicy` | 提交者线程自己执行任务 | 任务不可丢、有反压 | 提交者线程被占用可能拖垮上游 |
| `DiscardPolicy` | 静默丢弃 | **不推荐** | 数据丢失无感知 |
| `DiscardOldestPolicy` | 丢弃队首最旧任务 | 新任务优先 | **不兼容优先级队列** |

**诊断方法**：
- 搜索日志 `RejectedExecutionException`
- 监控 `threadpool_rejected_total` 计数器
- 检查线程 dump 中提交者线程是否阻塞在 `CallerRunsPolicy` 的 `r.run()`

**已知 JDK Bug**：`DiscardPolicy` 与 `submit()` 配合时，被拒绝的 `Future` 永远无法完成，`future.get()` 永久阻塞（JDK-8257671）。

### 7.5 池内死锁（Deadlock within Pooled Threads）

**现象**：线程 A 持有锁 L1 等待锁 L2，线程 B 持有锁 L2 等待锁 L1，两个线程池线程互相等待。

**典型 jstack 输出**：

```
Found one Java-level deadlock:
=============================
"order-worker-3":
  waiting to lock monitor 0x00007f... (object 0x000000..., a OrderLock),
  which is held by "order-worker-7"

"order-worker-7":
  waiting to lock monitor 0x00007f... (object 0x000000..., a OrderLock),
  which is held by "order-worker-3"
```

**解决方案**：
1. 统一加锁顺序
2. 使用 `tryLock(timeout)` 替代 `synchronized`
3. 减少锁的粒度
4. 使用无锁数据结构（`ConcurrentHashMap`、原子变量）

---

## 八、线程转储（Thread Dump）分析

### 8.1 获取 Thread Dump

```bash
# 标准方式
jstack <PID> > threaddump.log

# 连续 3 次，间隔 3 秒（推荐：对比看 block 是否持续）
jstack <PID> > dump1.log && sleep 3 && \
jstack <PID> > dump2.log && sleep 3 && \
jstack <PID> > dump3.log

# 带更多锁信息
jstack -l <PID> > dump_locks.log

# 使用 jcmd（JDK 7+）
jcmd <PID> Thread.print > dump_jcmd.log
```

### 8.2 快速统计线程状态分布

```bash
cat threaddump.log \
  | grep "java.lang.Thread.State" \
  | sort | uniq -c | sort -nr
```

示例输出：

```
 45    java.lang.Thread.State: WAITING (parking)
 12    java.lang.Thread.State: RUNNABLE
  5    java.lang.Thread.State: TIMED_WAITING (parking)
  3    java.lang.Thread.State: BLOCKED (on object monitor)
  1    java.lang.Thread.State: WAITING (on object monitor)
```

### 8.3 线程状态速查

| 状态 | 含义 | 排查优先级 |
|------|------|:---:|
| `RUNNABLE` | 可运行（不一定在 CPU 上） | 正常（如果大量，说明确实忙） |
| `BLOCKED` (on object monitor) | 等待进入 `synchronized` 块 | **重点** |
| `WAITING` (parking) | `LockSupport.park()` 挂起，`Condition.await()` | **重点** |
| `WAITING` (on object monitor) | `Object.wait()` 无限等待 | **重点** |
| `TIMED_WAITING` (sleeping) | `Thread.sleep()` | 关注数量 |
| `TIMED_WAITING` (parking) | 带超时的等待 | 关注数量 |

### 8.4 关键标记词

| 标记 | 含义 |
|------|------|
| `waiting on condition` | 等待条件满足（网络 I/O、队列取数据） |
| `waiting for monitor entry` | 等待进入 `synchronized` 临界区 |
| `parking to wait for` | `LockSupport.park()` 挂起，用 `AbstractQueuedSynchronizer` |
| `locked <0x...>` | 该线程已持有该锁 |
| `waiting to lock <0x...>` | 该线程正在等待该锁 |
| `- parking to wait for <0x...>` | 等待 JUC Lock（`ReentrantLock` 等） |
| `- waiting to lock <0x...>` | 等待 `synchronized` monitor 锁 |

### 8.5 定位高 CPU 线程的标准流程

```bash
# 1. 找到 Java 进程 PID
top -bn1 | grep java
# 或
jps -l

# 2. 找到该进程下 CPU 最高的线程
top -Hp <PID>

# 3. 将线程 ID 转为十六进制
printf '%x\n' <TID>

# 4. 在 jstack 输出中搜索该十六进制线程 ID (nid)
jstack <PID> | grep -A 30 '<hex_tid>'
```

### 8.6 推荐分析工具

- **fastThread.io**：在线分析，自动检测死锁、热点、线程分组统计
- **Spotify Thread Dump Analyzer**：按堆栈分组，可视化锁依赖关系
- **TDA (Thread Dump Analyzer)**：IBM 开发的桌面分析工具
- **JDK Mission Control (JMC)**：读取 JFR 文件并进行线程分析

---

## 九、性能分析工具

### 9.1 async-profiler

一个低开销的采样分析器，使用 `AsyncGetCallTrace` + `perf_events`。

**安装与基本用法**：

```bash
# 下载
curl -LO https://github.com/async-profiler/async-profiler/releases/download/v3.0/async-profiler-3.0-linux-x64.tar.gz

# CPU 火焰图
./profiler.sh -d 30 -e cpu -f /tmp/flamegraph.html <PID>

# Wall-clock 分析（含等待态）
./profiler.sh -d 30 -e wall -f /tmp/wall_flame.html <PID>

# 锁争用分析
./profiler.sh -d 30 -e lock -f /tmp/lock_flame.html <PID>

# 内存分配分析
./profiler.sh -d 30 -e alloc -f /tmp/alloc_flame.html <PID>

# 输出 JFR 格式（可导入 JDK Mission Control 分析）
./profiler.sh -d 30 -e cpu,lock,alloc -f /tmp/profile.jfr <PID>

# 指定线程范围
./profiler.sh -d 30 -e cpu -t -f /tmp/flame.html <PID>
```

**线程池分析要点**：
- 使用 `-e wall` 可以看到池内线程在哪些操作上阻塞/等待
- 使用 `-e lock` 可以看到线程池中的锁争用热点
- 使用 `-t` 分别输出每个线程的火焰图
- 注意：`-e wall` 会对大量空闲池线程产生冗余采样，升级到 3.0+ 版本有专门的优化

### 9.2 JDK Flight Recorder (JFR)

JDK 内置的低开销事件记录框架（JDK 11+ 可在生产环境随时开启）。

```bash
# 启动 JFR 录制（60 秒）
jcmd <PID> JFR.start name=threadpool duration=60s \
  filename=/tmp/threadpool.jfr \
  settings=profile

# 检查录制状态
jcmd <PID> JFR.check

# 停止录制
jcmd <PID> JFR.stop name=threadpool
```

**JFR 中线程池相关事件**：

| JFR Event | 含义 |
|-----------|------|
| `jdk.JavaMonitorEnter` | 进入 `synchronized` 的等待时间 |
| `jdk.ThreadPark` | `LockSupport.park()` 挂起时间 |
| `jdk.ThreadSleep` | `Thread.sleep()` 时间 |
| `jdk.ObjectAllocationInNewTLAB` | 对象分配热力图 |
| `jdk.ExecutionSample` | CPU 采样（须开启 profiling） |

**分析**：用 JDK Mission Control (JMC) 打开 `.jfr` 文件 → "Threads" 视图 → 按线程池筛选 → 查看每个线程的阻塞热点。

**JFR 的局限性**：
- 在特定场景下可能过滤掉 90%+ 的采样（GC、虚拟线程转换、native 调用），配合 async-profiler 补充使用。

### 9.3 Arthas（阿里巴巴开源诊断工具）

**一键安装启动**：

```bash
curl -O https://arthas.aliyun.com/arthas-boot.jar
java -jar arthas-boot.jar
# 选择目标 Java PID
```

**核心线程池诊断命令**：

| 命令 | 作用 |
|------|------|
| `dashboard` | 实时全局线程面板（在线程列表中可直接看到 `pool-X-thread-Y` 的 CPU% 和状态） |
| `thread -n 3` | 找出 CPU 占用 Top 3 的线程 |
| `thread -b` | 一键检测死锁 |
| `thread --state BLOCKED` | 筛选所有 BLOCKED 状态的线程 |
| `thread <线程ID>` | 查看指定线程的完整调用栈 |
| `trace <类名> <方法名> '#cost>100'` | 跟踪耗时 >100ms 的方法调用链 |
| `monitor -c 5 <类名> <方法名>` | 方法级别的调用统计（次数/成功率/平均耗时） |
| `watch <类名> <方法名> '{params,returnObj,throwExp}' -x 3` | 观测方法入参/返回值/异常 |
| `profiler start` / `profiler stop --format html` | 生成 CPU 火焰图 |
| `jad <类名>` | 反编译线上类确认代码版本 |
| `tt -t <类名> <方法名>` | "时空隧道"——录制方法调用现场供回放 |

**典型排查流程**：

```
CPU 高 → thread -n 3 定位线程 → thread [id] 看堆栈
    ↓ 找不到具体原因
    → profiler 火焰图 → 确认热点方法 → jad 反编译确认逻辑
    ↓ 方法调用链复杂
    → trace 类名 方法名 → 观察子方法耗时分布 → 定位瓶颈
```

### 9.4 工具选型建议

| 场景 | 推荐工具 |
|------|---------|
| 生产环境快速故障诊断 | Arthas |
| 离线深度性能分析（CPU/内存/锁） | async-profiler |
| 长期持续监控（零代码） | JFR + JDK Mission Control |
| CI/持续集成中的性能回归检测 | JFR + jfrunit |
| 容器化/K8s 环境 | async-profiler + JFR（保存文件到持久卷） |

---

## 十、Grafana / Prometheus 可观测性看板

### 10.1 整体架构

```
Java App (Micrometer) → Prometheus (每 15s scrape) → Grafana (可视化 + 告警)
```

关键依赖：

```xml
<dependency>
    <groupId>io.micrometer</groupId>
    <artifactId>micrometer-registry-prometheus</artifactId>
</dependency>
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-actuator</artifactId>
</dependency>
```

### 10.2 Prometheus 抓取配置

```yaml
# prometheus.yml
global:
  scrape_interval: 15s
  evaluation_interval: 15s

scrape_configs:
  - job_name: 'spring-boot'
    metrics_path: '/actuator/prometheus'
    static_configs:
      - targets:
          - 'app-host-1:8080'
          - 'app-host-2:8080'
        labels:
          environment: 'production'
```

### 10.3 Grafana Dashboard 推荐

| Dashboard | 来源 | 说明 |
|-----------|------|------|
| **ID: 4701** | Grafana 官方市场 | JVM (Micrometer) Dashboard — 最流行，含 Thread/GC/Memory/CPU |
| **ID: 12856** | Grafana 官方市场 | 另一个 Spring Boot JVM 模板 |
| **自定义** | 自定义 | 业务线程池聚焦看板 |

### 10.4 自定义线程池 Dashboard 核心 PromQL

```promql
# 活跃线程数
threadpool_active_threads{pool="$pool", application="$application"}

# 线程饱和度
threadpool_saturation{pool="$pool"}

# 队列水位
threadpool_queue_watermark{pool="$pool"}

# 任务完成速率（每秒）
rate(threadpool_active_threads{pool="$pool"}[1m])
# 注：active 是瞬时值不是 Counter，应改用完成数
# 若有 completed counter：
# rate(threadpool_completed_total{pool="$pool"}[1m])

# 拒绝速率（每秒）
rate(threadpool_rejected_total{pool="$pool"}[1m])

# 历史最大线程数
threadpool_largest_pool_size{pool="$pool"}

# 池大小变化
threadpool_pool_size{pool="$pool"}

# JVM 总线程数
jvm_threads_live_threads{application="$application"}
```

### 10.5 PromQL 高级用法

```promql
# 线程饱和度预测：未来 30 分钟内是否会达到 100%
predict_linear(threadpool_saturation{pool="order"}[10m], 1800) > 1

# 队列积压增长率（每分钟增长多少任务）
deriv(threadpool_queue_size{pool="order"}[5m])

# 拒绝率 Top 3 的应用
topk(3, rate(threadpool_rejected_total[5m]))
```

---

## 十一、告警规则设计

### 11.1 告警级别划分

| 级别 | 含义 | 响应要求 |
|------|------|---------|
| **Critical（紧急）** | 用户已受影响或即将受影响 | 立即响应，5 分钟 |
| **Warning（警告）** | 系统处于风险状态 | 30 分钟内处理 |
| **Info（提醒）** | 趋势异常需要关注 | 下一工作日 |

### 11.2 完整 Prometheus Alert Rules

```yaml
# threadpool-alerts.yml
groups:
  - name: threadpool-critical
    rules:
      # ===== Critical 级别 =====

      - alert: ThreadPoolTaskRejected
        expr: rate(threadpool_rejected_total[5m]) > 0
        for: 1m
        labels:
          severity: critical
        annotations:
          summary: "线程池 {{ $labels.pool }} 正在拒绝任务"
          description: "应用 {{ $labels.application }} 的线程池 "
            + "{{ $labels.pool }} 过去5分钟拒绝速率 "
            + "{{ $value | humanize }}/s，系统已过载。"

      - alert: ThreadPoolQueueFull
        expr: threadpool_queue_watermark > 0.95
        for: 30s
        labels:
          severity: critical
        annotations:
          summary: "线程池 {{ $labels.pool }} 队列接近满载"
          description: "当前队列水位 {{ $value | humanizePercentage }}，"
            + "即将触发拒绝。"

      # ===== Warning 级别 =====

      - alert: ThreadPoolHighSaturation
        expr: threadpool_saturation > 0.85
        for: 3m
        labels:
          severity: warning
        annotations:
          summary: "线程池 {{ $labels.pool }} 饱和度 > 85%"
          description: "活跃线程比例 {{ $value | humanizePercentage }}，"
            + "缓冲余量不足，建议扩容。"

      - alert: ThreadPoolQueueGrowth
        expr: threadpool_queue_watermark > 0.7
        for: 5m
        labels:
          severity: warning
        annotations:
          summary: "线程池 {{ $labels.pool }} 队列持续积压"
          description: "队列水位 {{ $value | humanizePercentage }} "
            + "已持续超过 5 分钟，消费速率不足。"

      - alert: ThreadPoolOverflowPrediction
        expr: |
          predict_linear(threadpool_queue_watermark[10m], 600) > 0.95
        for: 5m
        labels:
          severity: warning
        annotations:
          summary: "线程池 {{ $labels.pool }} 预计 10 分钟内队列满载"
          description: "基于过去 10 分钟趋势预测，请提前扩容。"

      # ===== Info 级别 =====

      - alert: ThreadPoolGrowthAnomaly
        expr: |
          (threadpool_pool_size - threadpool_pool_size offset 30m) > 20
        for: 5m
        labels:
          severity: info
        annotations:
          summary: "线程池 {{ $labels.pool }} 规模快速膨胀"
          description: "过去 30 分钟池大小增长超过 20，可能存在线程泄露。"

  - name: jvm-global
    rules:
      - alert: HighJvmThreadCount
        expr: jvm_threads_live_threads > 500
        for: 10m
        labels:
          severity: warning
        annotations:
          summary: "{{ $labels.application }} JVM 线程总数过高"
          description: "当前总线程数 {{ $value }}，可能存在线程泄露。"

      - alert: HighGCPauseTime
        expr: rate(jvm_gc_pause_seconds_sum[5m]) > 0.1
        for: 5m
        labels:
          severity: warning
        annotations:
          summary: "{{ $labels.application }} GC 暂停时间占比过高"
          description: "过去 5 分钟超过 10% 时间在 GC。"

      - alert: ThreadDeadlock
        expr: jvm_threads_deadlocked > 0
        for: 1m
        labels:
          severity: critical
        annotations:
          summary: "{{ $labels.application }} 检测到线程死锁"
          description: "死锁线程数: {{ $value }}，立即获取 thread dump。"
```

### 11.3 告警收敛策略

为避免告警风暴，需要以下收敛机制：

1. **`for` 子句**：持续满足条件 N 分钟才触发（防抖动）
2. **告警分组**：同一 `group` 的规则按间隔评估
3. **Alertmanager 配置**：

```yaml
# alertmanager.yml
route:
  group_by: ['alertname', 'application']
  group_wait: 10s           # 第一次告警等待
  group_interval: 5m        # 同组新告警间隔
  repeat_interval: 4h       # 重复发送间隔

  routes:
    - match:
        severity: critical
      receiver: 'critical-team'
    - match:
        severity: warning
      receiver: 'ops-team'

receivers:
  - name: 'critical-team'
    webhook_configs:
      - url: 'https://hooks.slack.com/services/...'   # 钉钉/飞书/企业微信
  - name: 'ops-team'
    email_configs:
      - to: 'ops@example.com'
```

### 11.4 Hippo4j 告警补充

如果使用 Hippo4j（美团开源的动态线程池框架），它还额外内置了：

| 告警类型 | 说明 |
|---------|------|
| **活跃度告警** | 活跃线程数达到最大线程数的 N% |
| **容量水位告警** | 队列使用率达到 N% |
| **拒绝策略告警** | 任务触发拒绝策略时立即告警 |
| **任务执行超时告警** | 任务耗时超阈值 |

---

## 十二、生产环境检查清单

### 12.1 线程池创建阶段

- [ ] 禁止使用 `Executors.newFixedThreadPool()` / `newCachedThreadPool()` 静默创建（无法监控）
- [ ] 所有线程池必须使用自定义 `ThreadFactory`，线程名具有业务含义
- [ ] 队例必须为有界队列（明确设置容量，避免 `Integer.MAX_VALUE`）
- [ ] 必须显式设置 `RejectedExecutionHandler`，使用包装版以便记录拒绝指标
- [ ] IO 密集型 / CPU 密集型线程池分开，互不影响
- [ ] 创建后立即注册到 Micrometer `MeterRegistry` 或 JMX

### 12.2 运行时监控阶段

- [ ] Prometheus 正常抓取 `/actuator/prometheus`
- [ ] Grafana Dashboard 可以正常展示所有池指标
- [ ] 告警规则已配置并测试过触发链路（模拟拒绝、高饱和度来验证）
- [ ] 至少覆盖以下指标：`activeCount`、`poolSize`、`queueSize`、`rejectedCount`、`saturation`、`watermark`

### 12.3 故障诊断准备

- [ ] 团队成员熟悉 `jstack`、Arthas 基本用法
- [ ] 生产环境预留 Arthas 接入方式（attach 权限）
- [ ] 开启 JFR 连续录制（JDK 11+，开销 < 1%）
- [ ] 文档化"常见线程池问题排查手册"

### 12.4 长期优化

- [ ] 定期 Review 线程池指标趋势，调整 `corePoolSize`/`maxPoolSize`/队列容量
- [ ] 考虑引入 Hippo4j 实现动态调参，避免变更需重启
- [ ] 队列深度告警"先于"拒绝告警，给予足够缓冲时间
- [ ] 定期组织线程池故障演练

---

## 参考资料

### 内置 API 文档
- Oracle `ThreadPoolExecutor` JavaDoc: https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/concurrent/ThreadPoolExecutor.html
- Oracle JMX Monitoring Guide (Java SE 24): https://docs.oracle.com/en/java/javase/24/management/

### Micrometer 与可观测性
- Micrometer 官方文档: https://micrometer.io/docs
- Spring Boot Actuator 官方文档: https://docs.spring.io/spring-boot/docs/current/reference/html/actuator.html
- Micrometer Prometheus Registry: https://micrometer.io/docs/registry/prometheus

### 开源工具
- async-profiler: https://github.com/async-profiler/async-profiler
- Arthas: https://arthas.aliyun.com/
- Hippo4j: https://github.com/opengoofy/hippo4j
- fastThread.io: https://fastthread.io/

### Grafana Dashboard
- JVM (Micrometer) Dashboard ID 4701: https://grafana.com/grafana/dashboards/4701-jvm-micrometer/
- Prometheus Alert Rules 参考: https://awesome-prometheus-alerts.grep.to/

### 行业文章
- "Java 线程池可观测性设计——如何监控线程池是否正在慢性死亡" (CSDN)
- "AutoMQ 项目中线程池监控化改造的技术实践" (GitCode)
- "SkyWalking + async-profiler 对 Java 应用进行性能剖析" (Apache SkyWalking Blog)
- "构建坚如磐石的异步基石：线程池最佳实践与 Hippo4j 监控预警系统深度解析" (CSDN)
