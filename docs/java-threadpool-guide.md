# Java 线程池完全指南

## 目录

- [第1章 为什么需要线程池](#第1章-为什么需要线程池)
- [第2章 Executor 框架体系](#第2章-executor-框架体系)
- [第3章 ThreadPoolExecutor 核心参数详解](#第3章-threadpoolexecutor-核心参数详解)
- [第4章 工作队列详解](#第4章-工作队列详解)
- [第5章 拒绝策略详解](#第5章-拒绝策略详解)
- [第6章 Executors 工厂方法](#第6章-executors-工厂方法)
- [第7章 ScheduledThreadPoolExecutor 定时调度](#第7章-scheduledthreadpoolexecutor-定时调度)
- [第8章 ForkJoinPool 工作窃取](#第8章-forkjoinpool-工作窃取)
- [第9章 虚拟线程（Java 21+）](#第9章-虚拟线程java-21)
- [第10章 线程池选型速查](#第10章-线程池选型速查)
- [附录：完整方法速查](#附录完整方法速查)

---

## 第1章 为什么需要线程池

### 1.1 不用线程池的代价

每次 `new Thread().start()` 的代价：
- **创建开销**：分配线程栈内存（默认 1MB）、初始化 TCB（Thread Control Block）
- **销毁开销**：回收栈内存、通知 OS 调度器
- **无上限风险**：高并发下线程数爆炸，CPU 时间全花在上下文切换而非业务计算

### 1.2 线程池的核心优势

1. **复用线程**：线程创建一次后反复使用，消除创建/销毁开销
2. **流量控制**：限制最大并发数，保护下游资源（数据库连接池、文件句柄）
3. **任务排队**：高峰期任务入队而非直接创建线程，平滑过载
4. **管理监控**：统一管理生命周期、获取运行统计、优雅关闭

---

## 第2章 Executor 框架体系

```
Executor (接口)
  └── ExecutorService (接口)
        ├── AbstractExecutorService (抽象类)
        │     └── ThreadPoolExecutor ← 核心实现
        ├── ScheduledExecutorService (接口)
        │     └── ScheduledThreadPoolExecutor
        └── ForkJoinPool (Java 7+)
```

| 接口 / 类 | 定位 | 关键能力 |
|-----------|------|---------|
| `Executor` | 最顶层抽象 | `void execute(Runnable command)` |
| `ExecutorService` | 可管理的执行器 | `submit`、`invokeAll`、`shutdown` |
| `ScheduledExecutorService` | 可定时的执行器 | `schedule`、`scheduleAtFixedRate` |
| `ThreadPoolExecutor` | 线程池核心实现 | 所有参数可定制 |
| `ForkJoinPool` | 工作窃取线程池 | 递归任务分解、并行流 |
| `Executors` | 工厂工具类 | 快速创建常见线程池配置 |

---

## 第3章 ThreadPoolExecutor 核心参数详解

### 3.1 完整构造器签名

```java
public ThreadPoolExecutor(
    int corePoolSize,                  // 核心线程数
    int maximumPoolSize,               // 最大线程数
    long keepAliveTime,                // 空闲线程存活时间
    TimeUnit unit,                     // 存活时间单位
    BlockingQueue<Runnable> workQueue, // 工作队列
    ThreadFactory threadFactory,       // 线程工厂
    RejectedExecutionHandler handler   // 拒绝策略
)
```

### 3.2 参数逐项解析

#### `int corePoolSize` — 核心线程数

线程池中始终保持存活的线程数，即使它们处于空闲状态。除非设置了 `allowCoreThreadTimeOut(true)`，否则核心线程不会被回收。

- **IO 密集型**：2 × CPU 核数，或者更多
- **CPU 密集型**：CPU 核数 + 1
- **混合型**：根据 IO 耗时占比动态计算（见第10章公式）

#### `int maximumPoolSize` — 最大线程数

线程池允许创建的最大线程数量。当工作队列满且当前线程数 < maximumPoolSize 时，新任务会创建新线程（非核心线程）来执行。

- **关系**：`corePoolSize ≤ maximumPoolSize`
- **核心线程 vs 非核心线程**：只有超过 corePoolSize 的线程才是"非核心线程"，空闲超过 keepAliveTime 后会被回收

#### `long keepAliveTime` — 空闲线程存活时间

非核心线程在空闲超过此时间后被终止回收。如果 `allowCoreThreadTimeOut(true)`，核心线程也会被回收。

#### `TimeUnit unit` — 时间单位

`TimeUnit` 枚举值：

| 值 | 说明 |
|----|------|
| `TimeUnit.NANOSECONDS` | 纳秒 |
| `TimeUnit.MICROSECONDS` | 微秒 |
| `TimeUnit.MILLISECONDS` | 毫秒 |
| `TimeUnit.SECONDS` | 秒 |
| `TimeUnit.MINUTES` | 分钟 |
| `TimeUnit.HOURS` | 小时 |
| `TimeUnit.DAYS` | 天 |

#### `BlockingQueue<Runnable> workQueue` — 工作队列

存放等待执行的任务。详见第4章。

#### `ThreadFactory threadFactory` — 线程工厂

创建新线程的工厂。强烈建议自定义以设置线程名称、守护状态、优先级和异常处理器。

```java
ThreadFactory namedFactory = r -> {
    Thread t = new Thread(r);
    t.setName("my-pool-" + t.threadId());  // 便于日志和 jstack 排查
    t.setDaemon(false);                      // 非守护线程阻止 JVM 退出
    t.setUncaughtExceptionHandler((th, ex) ->
        log.error("线程 {} 未捕获异常", th.getName(), ex));
    return t;
};
```

#### `RejectedExecutionHandler handler` — 拒绝策略

当线程池已关闭或工作队列满且线程数达到最大值时触发。详见第5章。

### 3.3 核心方法

#### `void execute(Runnable command)`

提交一个 `Runnable` 任务，无返回值。任务在将来某个时间执行。如果线程池已关闭或无法接受任务则抛出 `RejectedExecutionException`。

```java
ThreadPoolExecutor pool = new ThreadPoolExecutor(
    2, 4, 60L, TimeUnit.SECONDS,
    new LinkedBlockingQueue<>(100)
);
pool.execute(() -> System.out.println("任务执行"));
```

#### `<T> Future<T> submit(Callable<T> task)`

提交一个有返回值的任务，返回 `Future<T>`。可以通过 `Future.get()` 阻塞等待结果。

```java
Future<String> future = pool.submit(() -> {
    Thread.sleep(500);
    return "计算结果";
});
String result = future.get();  // 阻塞等待
```

#### `<T> Future<T> submit(Runnable task, T result)`

提交 `Runnable` 并在任务完成时返回预设的 `result` 值。（Runnable 本身无返回值。）

```java
Future<Integer> f = pool.submit(() -> doWork(), 42);
Integer val = f.get();  // 返回 42
```

#### `Future<?> submit(Runnable task)`

提交 `Runnable`，返回 `Future<?>`。`get()` 返回 `null`，仅用于检查是否完成。

```java
Future<?> f = pool.submit(() -> doWork());
f.get();  // 阻塞直到任务完成，返回 null
```

#### `<T> List<Future<T>> invokeAll(Collection<? extends Callable<T>> tasks)`

批量提交任务并阻塞等待**全部**完成，返回 `Future` 列表（顺序与提交顺序一致）。

```java
List<Callable<String>> tasks = Arrays.asList(
    () -> "任务1",
    () -> "任务2",
    () -> "任务3"
);
List<Future<String>> futures = pool.invokeAll(tasks);
for (Future<String> f : futures) {
    System.out.println(f.get());  // 全部完成后依次取结果
}
```

#### `<T> T invokeAny(Collection<? extends Callable<T>> tasks)`

批量提交任务，返回**第一个**成功完成的结果，其余任务被取消。

```java
List<Callable<String>> tasks = Arrays.asList(
    () -> { Thread.sleep(1000); return "慢"; },
    () -> { Thread.sleep(100);  return "快"; }
);
String result = pool.invokeAny(tasks);  // 返回 "快"
```

### 3.4 生命周期方法

#### `void shutdown()`

启动**有序关闭**：不再接受新任务，但已提交的任务（包括队列中等待的）会执行完毕。调用后立即返回，不阻塞。

```java
pool.shutdown();
// 此后 pool.execute(task) 会抛 RejectedExecutionException
```

#### `List<Runnable> shutdownNow()`

尝试**立即停止**：停止正在执行的任务（调用 `Thread.interrupt()`），返回队列中尚未执行的任务列表。不保证正在执行的任务一定停止（取决于是否响应中断）。

```java
List<Runnable> discarded = pool.shutdownNow();
System.out.println("丢弃了 " + discarded.size() + " 个任务");
```

#### `boolean awaitTermination(long timeout, TimeUnit unit)`

阻塞等待线程池终止（关闭后所有任务执行完毕），超时返回 `false`。

```java
pool.shutdown();
if (!pool.awaitTermination(60, TimeUnit.SECONDS)) {
    pool.shutdownNow();  // 超时强制终止
}
```

#### `boolean isShutdown()` / `boolean isTerminated()`

- `isShutdown()`：调用了 `shutdown()` 或 `shutdownNow()` 后返回 `true`
- `isTerminated()`：`shutdown` 后所有任务执行完毕才返回 `true`

### 3.5 监控方法

| 方法 | 返回值 | 说明 |
|------|--------|------|
| `int getPoolSize()` | int | 当前线程数 |
| `int getActiveCount()` | int | 正在执行任务的线程数（近似值） |
| `long getTaskCount()` | long | 曾提交的总任务数（已执行+执行中+排队中） |
| `long getCompletedTaskCount()` | long | 已完成的任务数 |
| `int getLargestPoolSize()` | int | 历史峰值线程数 |
| `int getQueue().size()` | int | 队列中等待的任务数 |
| `long getQueue().remainingCapacity()` | long | 队列剩余容量 |

### 3.6 配置方法

| 方法 | 说明 |
|------|------|
| `void setCorePoolSize(int)` | 动态调整核心线程数：若变小，空闲核心线程超时后回收 |
| `void setMaximumPoolSize(int)` | 动态调整最大线程数 |
| `void setKeepAliveTime(long, TimeUnit)` | 动态调整空闲超时 |
| `void setRejectedExecutionHandler(RejectedExecutionHandler)` | 动态更换拒绝策略 |
| `void setThreadFactory(ThreadFactory)` | 动态更换线程工厂 |
| `void allowCoreThreadTimeOut(boolean)` | 设为 true 则核心线程空闲也回收 |
| `boolean prestartCoreThread()` | 预启动一个核心线程 |
| `int prestartAllCoreThreads()` | 预启动所有核心线程 |
| `void purge()` | 移除队列中已取消的 Future 任务 |

---

## 第4章 工作队列详解

### 4.1 `SynchronousQueue` — 同步移交队列

```
提交任务 → 必须有线程立即接收 → 没有空闲线程 → 创建新线程 → 达到 max → 拒绝
```

- **容量**：0（不存储元素）
- **行为**：每个 `put` 必须等待一个 `take`，反之亦然
- **适用**：`newCachedThreadPool` 的默认队列，要求线程数无上限增长
- **IO/CPU**：需要无限或极大线程数的场景

```java
new ThreadPoolExecutor(0, Integer.MAX_VALUE,
    60L, TimeUnit.SECONDS,
    new SynchronousQueue<>());
```

### 4.2 `LinkedBlockingQueue` — 无界链表队列（默认）

```
提交任务 → 核心线程忙 → 入队 → 队列无限增长 → 永远不创建非核心线程
```

- **容量**：默认 `Integer.MAX_VALUE`（可视为无界）
- **行为**：任务先填满核心线程，然后无限排队，**永远不会创建超过 corePoolSize 的线程**
- **风险**：无界队列在任务提交快于执行时会导致 OOM
- **适用**：任务提交速率可控、不会积压的场景
- **IO/CPU**：无限制排队，适合提交速率平稳的 IO 任务

```java
new ThreadPoolExecutor(4, 8,  // ← max=8 实际永远不会用到
    60L, TimeUnit.SECONDS,
    new LinkedBlockingQueue<>());  // 无界！
```

### 4.3 `LinkedBlockingQueue(int capacity)` — 有界链表队列

- **容量**：指定 capacity
- **行为**：队列满后创建非核心线程直到 max，再满则触发拒绝策略
- **适用**：推荐的生产环境配置，有界队列限制资源消耗

```java
new ThreadPoolExecutor(4, 8,
    60L, TimeUnit.SECONDS,
    new LinkedBlockingQueue<>(200));  // 上限 200
```

### 4.4 `ArrayBlockingQueue` — 有界数组队列

- **容量**：构造时指定，不可变
- **行为**：与有界 `LinkedBlockingQueue` 相同，但内部使用数组环形缓冲区
- **公平模式**：`new ArrayBlockingQueue<>(100, true)` — FIFO 公平锁
- **区别**：数组实现 → 预分配内存，无链表节点开销，但入队出队同一把锁

```java
new ThreadPoolExecutor(4, 8,
    60L, TimeUnit.SECONDS,
    new ArrayBlockingQueue<>(100, true));  // 公平模式
```

### 4.5 `PriorityBlockingQueue` — 优先级无界队列

- **容量**：无界（自然增长）
- **行为**：任务按优先级排序执行，要求 `Runnable` 实现 `Comparable` 或提供 `Comparator`
- **风险**：无界 → 可能导致 OOM

```java
new ThreadPoolExecutor(4, 8,
    60L, TimeUnit.SECONDS,
    new PriorityBlockingQueue<>());
```

### 4.6 `DelayedWorkQueue` — 延迟工作队列

`ScheduledThreadPoolExecutor` 专用，内部实现不对外暴露。任务按延迟时间排序，堆结构实现。

### 4.7 队列选型速查

| 队列 | 容量 | 何时创建非核心线程 | 适用场景 |
|------|------|-------------------|---------|
| `SynchronousQueue` | 0 | 每个新任务都可能创建 | 瞬时高并发、短任务 |
| `LinkedBlockingQueue` 无界 | 无界 | 永远不会 | 提交速率可控 |
| `LinkedBlockingQueue` 有界 | 指定 | 队列满后 | **推荐生产配置** |
| `ArrayBlockingQueue` | 指定 | 队列满后 | 同上有界，公平锁需求 |
| `PriorityBlockingQueue` | 无界 | 永远不会 | 优先级调度 |

---

## 第5章 拒绝策略详解

### 5.1 内置四种策略

#### `AbortPolicy` — 抛异常（默认）

丢弃任务并抛出 `RejectedExecutionException`。调用方需要捕获异常或感知失败。

```java
RejectedExecutionHandler abort = new ThreadPoolExecutor.AbortPolicy();
```

#### `CallerRunsPolicy` — 调用者线程执行

不在线程池中执行，而是由提交任务的线程（调用 `execute` 的线程）直接执行。这个策略会在调用者线程中同步执行 `Runnable.run()`，从而自然减缓新任务的提交速率（背压）。

```java
RejectedExecutionHandler callerRuns = new ThreadPoolExecutor.CallerRunsPolicy();
```

#### `DiscardPolicy` — 静默丢弃

直接丢弃被拒绝的任务，不抛异常、不做通知。**非常危险**，任务静默消失。

```java
RejectedExecutionHandler discard = new ThreadPoolExecutor.DiscardPolicy();
```

#### `DiscardOldestPolicy` — 丢弃最旧任务

丢弃队列中等待最久（队头）的任务，然后重新提交当前任务。

```java
RejectedExecutionHandler discardOldest = new ThreadPoolExecutor.DiscardOldestPolicy();
```

### 5.2 自定义拒绝策略

```java
RejectedExecutionHandler custom = (r, executor) -> {
    // r: 被拒绝的任务
    // executor: 拒绝它的线程池
    log.warn("任务被拒绝, poolSize={}, queueSize={}",
        executor.getPoolSize(), executor.getQueue().size());
    // 策略1: 写入持久化队列，定时重试
    failedTaskQueue.offer(r);
    // 策略2: 发送告警
    alertService.send("线程池过载");
};
```

### 5.3 策略对比

| 策略 | 丢任务 | 抛异常 | 阻塞调用者 | 适用 |
|------|--------|--------|-----------|------|
| `AbortPolicy` | 是 | 是 | 否 | 需要感知失败的场景 |
| `CallerRunsPolicy` | 否 | 否 | 是 | **推荐**，天然背压 |
| `DiscardPolicy` | 是 | 否 | 否 | 可丢弃的次要任务 |
| `DiscardOldestPolicy` | 是 | 否 | 否 | 宁可丢旧取新 |

---

## 第6章 Executors 工厂方法

### 6.1 `newFixedThreadPool(int nThreads)` — 固定大小线程池

**方法签名**：
```java
public static ExecutorService newFixedThreadPool(int nThreads)
public static ExecutorService newFixedThreadPool(int nThreads, ThreadFactory threadFactory)
```

- **参数**：`int nThreads` — 线程池大小，`ThreadFactory threadFactory` — 线程工厂（可选）
- **返回值**：`ExecutorService`
- **核心线程** = **最大线程** = nThreads
- **队列**：`LinkedBlockingQueue`（无界）
- **KeepAlive**：0 秒（无意义，因为没有非核心线程）
- **分析**：**CPU 密集型**适用（nThreads = CPU 核数）。IO 密集型不合适，因为无界队列没有流量控制。所有线程同时存活，固定开销。线程数固定意味着线程不会动态增减。
- **风险**：无界队列 → 流量高峰时队列无限增长 → OOM

```java
ExecutorService pool = Executors.newFixedThreadPool(4);
pool.submit(() -> computeTask());
```

### 6.2 `newCachedThreadPool()` — 缓存线程池

**方法签名**：
```java
public static ExecutorService newCachedThreadPool()
public static ExecutorService newCachedThreadPool(ThreadFactory threadFactory)
```

- **参数**：`ThreadFactory threadFactory` — 线程工厂（可选）
- **返回值**：`ExecutorService`
- **核心线程**：0
- **最大线程**：`Integer.MAX_VALUE`（近无上限）
- **队列**：`SynchronousQueue`（容量 0）
- **KeepAlive**：60 秒
- **分析**：**纯 IO 密集型**。每个任务来时必须有空闲线程接收，否则创建新线程。空闲 60 秒回收。适合大量短生命周期的异步任务。
- **风险**：高并发长任务 → 线程数爆炸 → CPU 时间全花在上下文切换

```java
ExecutorService pool = Executors.newCachedThreadPool();
pool.execute(() -> quickIoTask());
```

### 6.3 `newSingleThreadExecutor()` — 单线程池

**方法签名**：
```java
public static ExecutorService newSingleThreadExecutor()
public static ExecutorService newSingleThreadExecutor(ThreadFactory threadFactory)
```

- **参数**：`ThreadFactory threadFactory` — 线程工厂（可选）
- **返回值**：`ExecutorService`
- **核心线程** = **最大线程** = 1
- **队列**：`LinkedBlockingQueue`（无界）
- **KeepAlive**：0 秒
- **分析**：保证任务按 FIFO 顺序串行执行（任务顺序性要求）。如果唯一线程在执行中挂掉，会自动创建新线程替补（通过 `FinalizableDelegatedExecutorService` 包装）。
- **适用**：需要严格顺序执行的场景：写日志、写文件、顺序消费队列

```java
ExecutorService pool = Executors.newSingleThreadExecutor();
pool.submit(() -> writeLog("事件A"));  // 先执行
pool.submit(() -> writeLog("事件B"));  // A 完成后再执行
```

### 6.4 `newScheduledThreadPool(int corePoolSize)` — 定时调度线程池

**方法签名**：
```java
public static ScheduledExecutorService newScheduledThreadPool(int corePoolSize)
public static ScheduledExecutorService newScheduledThreadPool(
    int corePoolSize, ThreadFactory threadFactory)
```

- **参数**：`int corePoolSize` — 核心线程数，`ThreadFactory threadFactory` — 线程工厂（可选）
- **返回值**：`ScheduledExecutorService`
- **核心线程**：指定 corePoolSize
- **最大线程**：`Integer.MAX_VALUE`
- **队列**：`DelayedWorkQueue`（内部实现）
- **分析**：用于定时任务和周期性任务。内部是 `ScheduledThreadPoolExecutor`，队列按延迟时间排序（堆结构）。
- **适用**：定时任务、心跳、定期轮询

```java
ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);
// 延迟 5 秒执行一次
scheduler.schedule(() -> System.out.println("延迟执行"), 5, TimeUnit.SECONDS);
// 初始延迟 1 秒，之后每 3 秒执行一次
scheduler.scheduleAtFixedRate(() -> heartbeat(), 1, 3, TimeUnit.SECONDS);
```

### 6.5 `newWorkStealingPool(int parallelism)` — 工作窃取线程池（Java 8+）

**方法签名**：
```java
public static ExecutorService newWorkStealingPool(int parallelism)
public static ExecutorService newWorkStealingPool()
```

- **参数**：`int parallelism` — 并行度，不指定则 = CPU 核数
- **返回值**：`ExecutorService`（实际类型为 `ForkJoinPool`）
- **内部**：`ForkJoinPool`（默认并行度 = CPU 核数）
- **分析**：**CPU 密集型并行计算**。每个线程维护自己的双端队列，空闲线程从其他线程队列尾部"窃取"任务，减少竞争。适合递归式并行计算（分治算法）。
- **适用**：并行流、递归分解任务、大量小计算密集型任务

```java
ExecutorService pool = Executors.newWorkStealingPool(4);
// ForkJoinPool 的 submit 返回 ForkJoinTask
pool.submit(() -> heavyComputation());
```

### 6.6 `newSingleThreadScheduledExecutor()` — 单线程定时调度

**方法签名**：
```java
public static ScheduledExecutorService newSingleThreadScheduledExecutor()
public static ScheduledExecutorService newSingleThreadScheduledExecutor(
    ThreadFactory threadFactory)
```

- **参数**：`ThreadFactory threadFactory` — 线程工厂（可选）
- **返回值**：`ScheduledExecutorService`
- 与 `newScheduledThreadPool(1)` 相同，但通过 `DelegatedScheduledExecutorService` 包装确保始终只有一个线程。

### 6.7 `newVirtualThreadPerTaskExecutor()` — 虚拟线程池（Java 21+）

**方法签名**：
```java
public static ExecutorService newVirtualThreadPerTaskExecutor()
```

- **参数**：无
- **返回值**：`ExecutorService`（每个任务创建一个虚拟线程）
- **分析**：虚拟线程极轻量（约几百字节栈），可创建数十万个。**纯 IO 密集型的终极方案**。虚拟线程在 IO 阻塞时自动让出平台线程（carrier thread），不会阻塞 OS 线程。**不需要池化**（因为创建成本极低），但 `newVirtualThreadPerTaskExecutor` 提供了一个 `ExecutorService` 接口方便迁移。
- **适用**：大量并发 IO 操作（HTTP 请求、数据库调用）、替代 CachedThreadPool 的高并发场景

```java
// Java 21+
try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
    List<Future<String>> futures = executor.invokeAll(tasks);
}
```

### 6.8 工厂方法对比

| 工厂方法 | core | max | 队列 | 回收 | CPU/IO |
|---------|------|-----|------|------|--------|
| `newFixedThreadPool(n)` | n | n | 无界 Linked | 不回收 | CPU 密集型 |
| `newCachedThreadPool()` | 0 | MAX | Synchronous(0) | 60s | IO 密集型（短任务） |
| `newSingleThreadExecutor()` | 1 | 1 | 无界 Linked | 不回收 | 顺序保证 |
| `newScheduledThreadPool(n)` | n | MAX | DelayedWork | 不回收核心 | 定时调度 |
| `newWorkStealingPool(n)` | n | n | 工作窃取 | — | CPU 密集型并行 |
| `newVirtualThreadPerTaskExecutor()` | 0 | MAX | 无 | 任务结束 | IO 密集型（Java 21+） |

---

## 第7章 ScheduledThreadPoolExecutor 定时调度

### 7.1 构造

```java
public ScheduledThreadPoolExecutor(int corePoolSize)
public ScheduledThreadPoolExecutor(int corePoolSize, ThreadFactory threadFactory)
public ScheduledThreadPoolExecutor(int corePoolSize, RejectedExecutionHandler handler)
public ScheduledThreadPoolExecutor(int corePoolSize, ThreadFactory threadFactory,
    RejectedExecutionHandler handler)
```

- **参数**：`int corePoolSize` — 核心线程数，`ThreadFactory threadFactory` — 线程工厂（可选），`RejectedExecutionHandler handler` — 拒绝策略（可选）
- **返回值**：`ScheduledThreadPoolExecutor`

### 7.2 核心方法

#### `<V> ScheduledFuture<V> schedule(Callable<V> callable, long delay, TimeUnit unit)`

延迟 `delay` 时间后执行一次有返回值的任务。

- **参数**：`Callable<V> callable` — 有返回值的任务，`long delay` — 延迟时间，`TimeUnit unit` — 时间单位
- **返回值**：`ScheduledFuture<V>`（继承 `Future<V>` 和 `Delayed`）

```java
ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);
ScheduledFuture<String> future = scheduler.schedule(
    () -> "延迟结果", 5, TimeUnit.SECONDS);
String result = future.get();  // 5 秒后返回 "延迟结果"
```

#### `ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit)`

延迟 `delay` 时间后执行一次 `Runnable`（无返回值）。

- **参数**：`Runnable command` — 无返回值的任务，`long delay` — 延迟时间，`TimeUnit unit` — 时间单位
- **返回值**：`ScheduledFuture<?>`

```java
scheduler.schedule(() -> System.out.println("5秒后执行"), 5, TimeUnit.SECONDS);
```

#### `ScheduledFuture<?> scheduleAtFixedRate(Runnable command, long initialDelay, long period, TimeUnit unit)`

固定**频率**执行：以固定时间间隔启动每次任务。如果单次执行时间 > period，下一次会等当前任务完成后立即开始（不会重叠）。

- **参数**：`Runnable command` — 要执行的任务，`long initialDelay` — 首次延迟时间，`long period` — 连续执行间隔，`TimeUnit unit` — 时间单位
- **返回值**：`ScheduledFuture<?>`

```java
// 初始延迟 0，之后每 1 秒执行一次
scheduler.scheduleAtFixedRate(
    () -> System.out.println("心跳: " + System.currentTimeMillis()),
    0, 1, TimeUnit.SECONDS
);
// 输出: 心跳: T0, 心跳: T0+1000, 心跳: T0+2000, ...
```

#### `ScheduledFuture<?> scheduleWithFixedDelay(Runnable command, long initialDelay, long delay, TimeUnit unit)`

固定**延迟**执行：每次任务**完成**后等待 `delay` 时间再启动下一次。

- **参数**：`Runnable command` — 要执行的任务，`long initialDelay` — 首次延迟时间，`long delay` — 任务完成后到下一次的延迟，`TimeUnit unit` — 时间单位
- **返回值**：`ScheduledFuture<?>`

```java
// 任务完成后等 2 秒再开始下一次
scheduler.scheduleWithFixedDelay(
    () -> { processBatch(); },
    0, 2, TimeUnit.SECONDS
);
```

### 7.3 scheduleAtFixedRate vs scheduleWithFixedDelay

```
scheduleAtFixedRate(period=3):
|--task(2s)--|--1s--|--task(2s)--|--1s--|--task(2s)--|  ← 固定间隔 3s

scheduleWithFixedDelay(delay=1):
|--task(2s)--|--1s--|--task(3s)--|--1s--|--task(1s)--|  ← 完成任务间固定间隔 1s
```

- `scheduleAtFixedRate`：适合**绝对时间驱动的周期任务**——心跳、监控上报
- `scheduleWithFixedDelay`：适合**任务间需要等待的批处理**——处理完一批等一段时间再处理下一批

### 7.4 额外配置方法

| 方法 | 说明 |
|------|------|
| `void setContinueExistingPeriodicTasksAfterShutdownPolicy(boolean)` | shutdown 后是否继续执行已存在的周期任务 |
| `boolean getContinueExistingPeriodicTasksAfterShutdownPolicy()` | 获取上述策略 |
| `void setExecuteExistingDelayedTasksAfterShutdownPolicy(boolean)` | shutdown 后是否执行已存在的延迟（非周期）任务 |
| `boolean getExecuteExistingDelayedTasksAfterShutdownPolicy()` | 获取上述策略 |

---

## 第8章 ForkJoinPool 工作窃取

### 8.1 设计思想

`ForkJoinPool` 专为**分治递归**任务设计。每个工作线程维护自己的**双端队列**：

- 自己提交子任务 → 从队头取（LIFO，局部性好）
- 其他线程窃取任务 → 从队尾偷（FIFO，任务粒度大）

这种"工作窃取"（Work Stealing）机制使得线程负载自动均衡，无需手动分配任务。

### 8.2 获取 ForkJoinPool

```java
// 公共池（默认并行度 = CPU 核数 - 1，为 Java 并行流预留）
ForkJoinPool common = ForkJoinPool.commonPool();

// 自定义并行度
ForkJoinPool custom = new ForkJoinPool(4);  // 4 个 worker 线程
```

### 8.3 核心方法

#### `<T> ForkJoinTask<T> submit(ForkJoinTask<T> task)`

提交一个 `ForkJoinTask`（`RecursiveTask` 或 `RecursiveAction` 的父类）。

- **参数**：`ForkJoinTask<T> task` — ForkJoin 任务
- **返回值**：`ForkJoinTask<T>`

```java
ForkJoinPool pool = new ForkJoinPool(4);
ForkJoinTask<Integer> task = pool.submit(new RecursiveTask<>() { ... });
Integer result = task.join();
```

#### `<T> T invoke(ForkJoinTask<T> task)`

提交并阻塞等待结果，相当于 `submit(task).join()`。

- **参数**：`ForkJoinTask<T> task` — ForkJoin 任务
- **返回值**：`T` — 任务结果

```java
Integer result = pool.invoke(new FibonacciTask(10));
```

#### `void execute(ForkJoinTask<?> task)` / `void execute(Runnable task)`

提交任务但不等待结果。

```java
pool.execute(() -> parallelCompute());
```

#### 监控方法

| 方法 | 返回值 | 说明 |
|------|--------|------|
| `getParallelism()` | `int` | 并行度 |
| `getPoolSize()` | `int` | 当前线程数 |
| `getActiveThreadCount()` | `int` | 活跃线程数 |
| `getQueuedSubmissionCount()` | `int` | 提交但未开始的任务数 |
| `getStealCount()` | `long` | 窃取任务总数 |
| `commonPool()` | `static ForkJoinPool` | 公共池 |

### 8.4 RecursiveTask（有返回值） vs RecursiveAction（无返回值）

```java
// RecursiveTask<T> — 有返回值的分治任务
class SumTask extends RecursiveTask<Long> {
    private final int[] arr;
    private final int start, end;
    private static final int THRESHOLD = 10_000;

    SumTask(int[] arr, int start, int end) {
        this.arr = arr; this.start = start; this.end = end;
    }

    @Override
    protected Long compute() {
        if (end - start <= THRESHOLD) {
            long sum = 0;
            for (int i = start; i < end; i++) sum += arr[i];
            return sum;
        }
        int mid = (start + end) / 2;
        SumTask left = new SumTask(arr, start, mid);
        SumTask right = new SumTask(arr, mid, end);
        left.fork();              // 异步执行 left
        long rightResult = right.compute();  // 同步执行 right
        long leftResult = left.join();       // 等待 left 结果
        return leftResult + rightResult;
    }
}

// 使用
ForkJoinPool pool = new ForkJoinPool(4);
long total = pool.invoke(new SumTask(array, 0, array.length));
```

```java
// RecursiveAction — 无返回值的分治任务
class PrintAction extends RecursiveAction {
    @Override
    protected void compute() {
        if (工作量小) {
            直接处理();
        } else {
            PrintAction left = new PrintAction(左半);
            PrintAction right = new PrintAction(右半);
            invokeAll(left, right);  // 并行执行两者
        }
    }
}
```

### 8.5 ForkJoinPool vs ThreadPoolExecutor

| | ForkJoinPool | ThreadPoolExecutor |
|---|---|---|
| **队列** | 每线程一个双端队列 | 一个共享队列 |
| **调度** | 工作窃取 | 共享队列 + 锁竞争 |
| **适合** | 递归、分治、CPU 密集并行 | 通用、IO 密集、混合 |
| **任务粒度** | 细粒度子任务 | 独立粗粒度任务 |
| **Java 使用** | `Arrays.parallelSort()`、并行 Stream | 大多数业务线程池 |

---

## 第9章 虚拟线程（Java 21+）

### 9.1 为什么需要虚拟线程

传统平台线程（Platform Thread）= OS 线程 = 约 1MB 栈内存 + 内核调度。

- 10K 平台线程 → 10GB 内存（仅栈）+ 大量上下文切换
- 10K 虚拟线程 → 约 10MB 内存 + OS 调度器无感

虚拟线程在 IO 阻塞时自动**卸载**（unmount）让出平台线程，阻塞结束后重新**挂载**（mount）继续执行。平台线程始终在运行，不会被阻塞浪费。

### 9.2 创建方式

```java
// 方式1: 单个虚拟线程
Thread vThread = Thread.startVirtualThread(() -> {
    System.out.println("虚拟线程: " + Thread.currentThread());
});

// 方式2: Builder
Thread vt = Thread.ofVirtual()
    .name("my-virtual-thread")
    .start(() -> doIoWork());

// 方式3: ExecutorService（推荐）
try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
    executor.submit(() -> fetchFromDatabase());
    executor.submit(() -> callExternalApi());
}
```

### 9.3 虚拟线程 vs 传统线程池

| | 虚拟线程 | FixedThreadPool | CachedThreadPool |
|---|---|---|---|
| **每任务开销** | 数百字节 | 1MB 栈 | 1MB 栈 |
| **最大并发** | 百万级 | 受池大小限制 | 受内存限制 |
| **阻塞代价** | 自动卸载，无阻塞 | 线程被占用 | 线程被占用 |
| **池化需要** | 不需要（创建成本极低） | 需要 | 需要 |
| **适用** | IO 密集型 | CPU 密集型 | 短生命周期 IO |

### 9.4 注意事项

- **不要池化虚拟线程**：虚拟线程就是设计来随时创建的，池化反而破坏其优势
- **synchronized 块内 IO 操作**：会导致虚拟线程 pinned（无法卸载），应改用 `ReentrantLock`
- **ThreadLocal**：每个虚拟线程有独立的 ThreadLocal，大量虚拟线程可能撑爆内存——考虑用 `ScopedValue`（Java 21+ 预览）

---

## 第10章 线程池选型速查

### 10.1 决策树

```
任务类型？
├── CPU 密集型（计算、加密、压缩）
│   └── newFixedThreadPool(CPU核数 + 1)
│       或 newWorkStealingPool(CPU核数)
│
├── IO 密集型（网络、磁盘、数据库）
│   ├── Java 21+
│   │   └── Executors.newVirtualThreadPerTaskExecutor()
│   ├── 短任务、瞬时高并发
│   │   └── newCachedThreadPool()
│   └── 需控制资源（保护下游）
│       └── 自定义 ThreadPoolExecutor
│           core=N, max=2N, 有界队列(100~1000), CallerRunsPolicy
│
├── 混合型
│   └── 分离 CPU 和 IO 到两个线程池
│
├── 定时调度
│   └── newScheduledThreadPool(核心数)
│
└── 递归分解（并行计算）
    └── newWorkStealingPool() / ForkJoinPool
```

### 10.2 线程数计算公式

**CPU 密集型**：
```
线程数 = CPU 核数 + 1
```
+1 是为了填补偶尔的缺页中断或其他微小停顿。

**IO 密集型（传统公式，Brian Goetz）**：
```
线程数 = CPU核数 × (1 + 平均IO等待时间 / 平均CPU计算时间)
```
假设 IO 等待时间是 CPU 计算时间的 10 倍 → `核数 × 11`

**IO 密集型（经验公式）**：
```
线程数 = CPU核数 × 2
```
对于大多数 Web 服务场景是安全的起点。

**混合型**：
```
线程数 = CPU核数 × 目标CPU利用率 × (1 + W/C)
```
其中 W = 等待时间，C = 计算时间，目标 CPU 利用率通常取 0.8。

### 10.3 池类型分析总结

| 线程池 | CPU密集型 | IO密集型 | 混合型 | 生产推荐 |
|--------|-----------|----------|--------|---------|
| `newFixedThreadPool(n)` | 合适 | 需有界队列 | 可选 | 需精确调参 |
| `newCachedThreadPool()` | 不合适 | 合适 | 慎用 | 仅短任务场景 |
| `newSingleThreadExecutor()` | — | 顺序 IO | — | 顺序保证场景 |
| `newScheduledThreadPool(n)` | — | — | — | 定时调度 |
| `newWorkStealingPool(n)` | 最优 | 不适合 | 不适合 | 并行计算 |
| `newVirtualThreadPerTaskExecutor()` | 不适合 | 最优 | 好 | Java 21+ IO 场景 |
| 自定义 `ThreadPoolExecutor` | 可控 | 可控 | 可控 | **最推荐** |

### 10.4 推荐的生产配置模板

```java
int cpuCores = Runtime.getRuntime().availableProcessors();

ThreadPoolExecutor pool = new ThreadPoolExecutor(
    cpuCores,                             // corePoolSize（CPU核数）
    cpuCores * 2,                         // maximumPoolSize（core × 2）
    120L, TimeUnit.SECONDS,               // 空闲2分钟回收非核心线程
    new LinkedBlockingQueue<>(500),       // 有界队列500
    new ThreadFactory() {
        private final AtomicInteger count = new AtomicInteger(0);
        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, "biz-pool-" + count.incrementAndGet());
            t.setDaemon(false);
            t.setUncaughtExceptionHandler((th, ex) ->
                log.error("线程 {} 异常", th.getName(), ex));
            return t;
        }
    },
    new ThreadPoolExecutor.CallerRunsPolicy()  // 过载时让调用者执行，自然背压
);

// 预热核心线程
pool.prestartAllCoreThreads();

// JVM 关闭时优雅退出
Runtime.getRuntime().addShutdownHook(new Thread(() -> {
    pool.shutdown();
    try {
        if (!pool.awaitTermination(30, TimeUnit.SECONDS)) {
            pool.shutdownNow();
        }
    } catch (InterruptedException e) {
        pool.shutdownNow();
    }
}));
```

---

## 附录：完整方法速查

### ThreadPoolExecutor 方法

| 方法签名 | 返回类型 | 说明 |
|---------|---------|------|
| `execute(Runnable command)` | `void` | 提交无返回值任务 |
| `submit(Callable<T> task)` | `Future<T>` | 提交有返回值任务 |
| `submit(Runnable task)` | `Future<?>` | 提交 Runnable，get() 返回 null |
| `submit(Runnable task, T result)` | `Future<T>` | 提交 Runnable，完成时返回 result |
| `invokeAll(Collection<? extends Callable<T>>)` | `List<Future<T>>` | 批量提交，等待全部完成 |
| `invokeAny(Collection<? extends Callable<T>>)` | `T` | 批量提交，返回第一个成功 |
| `shutdown()` | `void` | 有序关闭，不接受新任务 |
| `shutdownNow()` | `List<Runnable>` | 立即停止，返回未执行任务 |
| `awaitTermination(long timeout, TimeUnit unit)` | `boolean` | 阻塞等待终止，超时返回 false |
| `isShutdown()` | `boolean` | 是否已调用 shutdown |
| `isTerminated()` | `boolean` | 是否完全终止 |
| `getPoolSize()` | `int` | 当前线程数 |
| `getActiveCount()` | `int` | 活跃线程数（近似值） |
| `getTaskCount()` | `long` | 曾提交的总任务数 |
| `getCompletedTaskCount()` | `long` | 已完成任务数 |
| `getLargestPoolSize()` | `int` | 历史峰值线程数 |
| `getQueue()` | `BlockingQueue<Runnable>` | 工作队列引用 |
| `setCorePoolSize(int corePoolSize)` | `void` | 动态调整核心线程数 |
| `setMaximumPoolSize(int maximumPoolSize)` | `void` | 动态调整最大线程数 |
| `setKeepAliveTime(long time, TimeUnit unit)` | `void` | 动态调整空闲超时 |
| `setRejectedExecutionHandler(RejectedExecutionHandler handler)` | `void` | 动态更换拒绝策略 |
| `setThreadFactory(ThreadFactory threadFactory)` | `void` | 动态更换线程工厂 |
| `allowCoreThreadTimeOut(boolean value)` | `void` | 核心线程是否也回收 |
| `prestartCoreThread()` | `boolean` | 预启动一个核心线程 |
| `prestartAllCoreThreads()` | `int` | 预启动所有核心线程 |
| `purge()` | `void` | 移除队列中已取消的 Future 任务 |

### ScheduledThreadPoolExecutor 方法

| 方法签名 | 返回类型 | 说明 |
|---------|---------|------|
| `schedule(Callable<V> callable, long delay, TimeUnit unit)` | `ScheduledFuture<V>` | 延迟执行有返回值任务 |
| `schedule(Runnable command, long delay, TimeUnit unit)` | `ScheduledFuture<?>` | 延迟执行 Runnable |
| `scheduleAtFixedRate(Runnable command, long initialDelay, long period, TimeUnit unit)` | `ScheduledFuture<?>` | 固定频率执行 |
| `scheduleWithFixedDelay(Runnable command, long initialDelay, long delay, TimeUnit unit)` | `ScheduledFuture<?>` | 固定延迟执行 |
| `setContinueExistingPeriodicTasksAfterShutdownPolicy(boolean)` | `void` | shutdown 后是否继续周期任务 |
| `setExecuteExistingDelayedTasksAfterShutdownPolicy(boolean)` | `void` | shutdown 后是否执行延迟任务 |

### ForkJoinPool 方法

| 方法签名 | 返回类型 | 说明 |
|---------|---------|------|
| `invoke(ForkJoinTask<T>)` | `T` | 提交并阻塞等待结果 |
| `submit(ForkJoinTask<T>)` | `ForkJoinTask<T>` | 提交任务 |
| `execute(ForkJoinTask<?>)` | `void` | 提交不等待 |
| `execute(Runnable)` | `void` | 提交 Runnable |
| `getParallelism()` | `int` | 并行度 |
| `getPoolSize()` | `int` | 当前线程数 |
| `getActiveThreadCount()` | `int` | 活跃线程数 |
| `getQueuedSubmissionCount()` | `int` | 提交但未开始的任务数 |
| `getStealCount()` | `long` | 窃取任务总数 |
| `commonPool()` | `static ForkJoinPool` | 公共池 |

### Executors 工厂方法速查

| 方法签名 | 返回类型 | 说明 |
|---------|---------|------|
| `newFixedThreadPool(int nThreads)` | `ExecutorService` | 固定大小线程池 |
| `newFixedThreadPool(int, ThreadFactory)` | `ExecutorService` | 固定大小+自定义线程工厂 |
| `newCachedThreadPool()` | `ExecutorService` | 缓存线程池 |
| `newCachedThreadPool(ThreadFactory)` | `ExecutorService` | 缓存线程池+自定义线程工厂 |
| `newSingleThreadExecutor()` | `ExecutorService` | 单线程池 |
| `newSingleThreadExecutor(ThreadFactory)` | `ExecutorService` | 单线程池+自定义线程工厂 |
| `newScheduledThreadPool(int corePoolSize)` | `ScheduledExecutorService` | 定时调度线程池 |
| `newScheduledThreadPool(int, ThreadFactory)` | `ScheduledExecutorService` | 定时调度+自定义线程工厂 |
| `newWorkStealingPool(int parallelism)` | `ExecutorService` | 工作窃取线程池 |
| `newWorkStealingPool()` | `ExecutorService` | 工作窃取（默认并行度 = CPU 核数） |
| `newSingleThreadScheduledExecutor()` | `ScheduledExecutorService` | 单线程定时调度 |
| `newSingleThreadScheduledExecutor(ThreadFactory)` | `ScheduledExecutorService` | 单线程定时调度+自定义线程工厂 |
| `newVirtualThreadPerTaskExecutor()` | `ExecutorService` | 虚拟线程执行器（Java 21+） |
