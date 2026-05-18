# ForkJoinPool 深度调优与虚拟线程优化研究

> **编写日期**: 2026-05-17  
> **适用版本**: Java 21 LTS ~ Java 25 (含前瞻特性)  
> **关键词**: ForkJoinPool, 工作窃取, ManagedBlocker, Virtual Threads, Structured Concurrency, Scoped Values, 线程池迁移

---

## 目录

1. [一、ForkJoinPool 内部机制深度剖析](#一forkjoinpool-内部机制深度剖析)
2. [二、工作窃取算法详解](#二工作窃取算法详解)
3. [三、commonPool() 默认配置与共享陷阱](#三commonpool-默认配置与共享陷阱)
4. [四、为何不能在 commonPool 中执行阻塞任务](#四为何不能在-commonpool-中执行阻塞任务)
5. [五、自定义 ForkJoinPool 创建与配置](#五自定义-forkjoinpool-创建与配置)
6. [六、并行度调优](#六并行度调优)
7. [七、asyncMode：FIFO 与 LIFO 的权衡](#七asyncmodefifo-与-lifo-的权衡)
8. [八、ForkJoinPool 线程管理与饱和策略](#八forkjoinpool-线程管理与饱和策略)
9. [九、ManagedBlocker：在 FJP 中正确处理阻塞](#九managedblocker在-fjp-中正确处理阻塞)
10. [十、ForkJoinPool 监控与诊断](#十forkjoinpool-监控与诊断)
11. [十一、ForkJoinPool vs ThreadPoolExecutor：场景选型](#十一forkjoinpool-vs-threadpoolexecutor场景选型)
12. [十二、CompletableFuture 与 ForkJoinPool 的关系](#十二completablefuture-与-forkjoinpool-的关系)
13. [十三、并行流（Parallel Streams）的隐藏风险](#十三并行流parallel-streams的隐藏风险)
14. [十四、虚拟线程（JEP 444）工作原理](#十四虚拟线程jep-444工作原理)
15. [十五、虚拟线程调度器与 ForkJoinPool](#十五虚拟线程调度器与-forkjoinpool)
16. [十六、何时虚拟线程可以取代线程池](#十六何时虚拟线程可以取代线程池)
17. [十七、何时仍需要线程池](#十七何时仍需要线程池)
18. [十八、虚拟线程钉住（Pinning）问题](#十八虚拟线程钉住pinning问题)
19. [十九、使用 JFR 检测虚拟线程钉住](#十九使用-jfr-检测虚拟线程钉住)
20. [二十、结构化并发（JEP 453 / JEP 505）](#二十结构化并发jep-453--jep-505)
21. [二十一、Scoped Values（JEP 446 / JEP 506）](#二十一scoped-valuesjep-446--jep-506)
22. [二十二、性能对比：平台线程 vs 虚拟线程](#二十二性能对比平台线程-vs-虚拟线程)
23. [二十三、从线程池迁移到虚拟线程的完整指南](#二十三从线程池迁移到虚拟线程的完整指南)
24. [二十四、总结与最佳实践](#二十四总结与最佳实践)

---

## 一、ForkJoinPool 内部机制深度剖析

### 1.1 设计哲学

`ForkJoinPool` 是 Java 7 引入的专用 `ExecutorService`，专为**分治（divide-and-conquer）并行计算**而设计。其核心设计哲学是：**为每个工作线程分配独立的双端队列（Deque），通过工作窃取实现自动负载均衡**。

与 `ThreadPoolExecutor` 的中央共享队列不同，ForkJoinPool 消除了单队列的竞争瓶颈。

### 1.2 核心架构组件

```
┌──────────────────────────────────────────────────┐
│                  ForkJoinPool                     │
│  ┌────────┐  ┌────────┐  ┌────────┐              │
│  │Worker 0│  │Worker 1│  │Worker N│  ... (N = parallelism) │
│  │ Deque  │  │ Deque  │  │ Deque  │              │
│  │[T1|T2|.]│  │[T5|T6|.]│  │[T9|...]│              │
│  └────────┘  └────────┘  └────────┘              │
│  ┌─────────────────────────┐                     │
│  │    Submission Queue      │ ← 外部提交(external submit) │
│  │  (偶数索引 WorkQueue)     │                     │
│  └─────────────────────────┘                     │
│  ctl (64位原子字段):                               │
│  |--AC(16)--|--TC(16)--|--SS(16)--|--ID(16)--|    │
│   活跃线程数  总线程数    等待栈顶    下一队列ID      │
└──────────────────────────────────────────────────┘
```

**关键组件说明：**

| 组件 | 作用 |
|------|------|
| `ForkJoinPool` | 线程池容器，管理 `WorkQueue[]` 数组、线程生命周期和全局状态 |
| `WorkQueue` | 环形数组双端队列，分为工作队列（奇数索引）和提交队列（偶数索引） |
| `ForkJoinTask` | 抽象任务基类；子类 `RecursiveTask<V>`（有返回值）和 `RecursiveAction`（无返回值） |
| `ForkJoinWorkerThread` | 工作线程，每个线程绑定一个 WorkQueue |
| `ctl` | 单个 `long` 字段，用位运算打包四个子字段，支持单次 CAS 原子更新 |

### 1.3 WorkQueue：系统的核心数据结构

WorkQueue 是一个**可动态扩容的环形数组**，内部有两个 volatile 游标：

```
       top (仅拥有者写, LIFO push/pop)
        ↓
  ┌───┬───┬───┬───┬───┐
  │ T4│ T3│ T2│ T1│   │  ← 数组长度始终为 2 的幂 (初始 8192)
  └───┴───┴───┴───┴───┘
                    ↑
                 base (共享, FIFO poll/steal, CAS 更新)
```

- **`top`**：仅由拥有者线程修改，用于 `push()` 和 `pop()`（LIFO 模式）
- **`base`**：可由窃取线程通过 CAS 读取/修改，用于 `poll()`（FIFO 模式）
- 索引计算用位掩码：`index = (top & (array.length - 1))`，避免取模运算开销

**两种队列类型（按数组索引奇偶区分）：**

| 索引类型 | 用途 | 访问方式 |
|----------|------|----------|
| **奇数索引** | **工作队列**：归属 `ForkJoinWorkerThread`，存放 fork 出的子任务 | 拥有者无锁访问 |
| **偶数索引** | **提交队列**：`owner == null`，存放外部 `execute/submit` 的任务 | 通过 `phase` 锁保护 |

```java
// 注册工作线程时强制分配奇数索引（JDK 源码）
int id = ((seed << 1) | 1) & SMASK;  // 始终为奇数
```

### 1.4 状态管理：ctl 字段的位压缩设计

`ctl` 是一个 `long` 字段，将多个状态打包进 64 位，实现单次 CAS 原子更新：

```
|<-- AC (16 bits) -->|<-- TC (16 bits) -->|<-- SS (16 bits) -->|<-- ID (16 bits) -->|
  活跃线程计数           总线程计数           等待线程 Treiber 栈顶   下一个队列 ID 种子
```

- **AC (Active Count)**：正在扫描或执行任务的活跃线程数
- **TC (Total Count)**：线程总数（活跃 + 休眠）
- **SS (Stack State)**：休眠线程 Treiber 栈的栈顶 `scanState`
- **ID**：分配奇数索引给新工作队列的种子值

当线程休眠时，在单次 CAS 中同时**递减 AC** 和**将 scanState 压入 SS 栈**。当新任务到达时，`signalWork()` 在单次 CAS 中同时**弹出 SS** 和**递增 AC** 来唤醒线程。

---

## 二、工作窃取算法详解

### 2.1 非对称访问模式

工作窃取算法的核心洞察在于**拥有者线程和窃取者线程操作队列的不同端**，从而最小化竞争：

| 操作 | 调用者 | 操作端 | 顺序 | 同步机制 |
|------|--------|--------|------|----------|
| `push(task)` | 拥有者线程 | 头部 | LIFO | 无（单写入者） |
| `pop()` | 拥有者线程 | 头部 | LIFO | 无（单写入者） |
| `poll()` / 窃取 | 空闲线程 | 尾部 | FIFO | CAS 更新 `base` |

**设计精妙之处：**
- 拥有者使用 LIFO（缓存热数据，最近 fork 的子任务大概率在 CPU 缓存中）
- 窃取者使用 FIFO（窃取队列中最老的任务，通常是更大粒度的任务，让窃取者获得更多工作量）
- 两者只在队列仅剩一个任务时才发生竞争

### 2.2 runWorker 主循环

```java
final void runWorker(WorkQueue w) {
    w.growArray();                // 初始化队列数组
    int r = w.hint;               // 随机种子
    for (ForkJoinTask<?> t;;) {
        if ((t = scan(w, r)) != null)
            w.runTask(t);         // 执行窃取到的任务
        else if (!awaitWork(w, r)) // 未找到任务则休眠
            break;
        r ^= r << 13; r ^= r >>> 17; r ^= r << 5; // xorshift 伪随机
    }
}
```

### 2.3 scan() 方法的完整流程

`scan()` 是工作窃取的核心方法，执行以下步骤：

1. **随机探测**：使用 `r & m` 计算一个随机的 WorkQueue 索引
2. **检查任务**：读取目标队列的 `base` 和 `top`，判断是否非空
3. **如果存在任务且线程活跃**（`ss >= 0`）：
   - 使用 CAS 尝试声明 `base` 位置的任务
   - 成功则推进 `base` 指针
   - 如果队列中仍有更多任务（`n < -1`），调用 `signalWork()` 唤醒/创建另一个线程
4. **迭代扫描**：移动到下一个索引 `(k + 1) & m`。完整扫描一遍后如果未找到：
   - **失活**：将 `scanState` 设为负值（INACTIVE 标志），原子性地递减 `ctl` 中的活跃计数，通过 `stackPred` 压入等待栈
5. **第二次扫描**：即使已标记失活，仍再扫描一圈。如果发现任务，"复活"线程（scanState 恢复为正数）
6. **返回 null**：仅在两轮完全空扫描后才返回 null ——调用方随后通过 `awaitWork()` 休眠

### 2.4 runTask() 与级联执行

```java
final void runTask(ForkJoinTask<?> task) {
    scanState &= ~SCANNING;            // 标记为"忙碌"
    (currentSteal = task).doExec();    // 执行窃取到的任务
    U.putOrderedObject(this, QCURRENTSTEAL, null);
    execLocalTasks();                  // 排空自己的本地队列（LIFO）
    ++nsteals;                         // 递增窃取计数
    scanState |= SCANNING;             // 恢复为"扫描"状态
}
```

窃取到一个任务后，线程会**排空自己的本地队列**（LIFO pop）。这种**级联效应**意味着一次成功的窃取通常会引发一连串的生产性工作。

### 2.5 Help-Join 与补偿机制

ForkJoinPool 特有的防死锁机制：当线程调用 `task.join()` 而任务尚未完成时，线程不会简单阻塞，而是会**帮助执行**等待中的任务或其子任务。

- **`helpJoin()` / `helpComplete()`**：通过 `currentSteal` 引用追溯窃取链，定位持有目标任务的线程，然后窃取并执行相关子任务，加速依赖关系的完成
- **`tryCompensate()`**：当所有帮助手段耗尽时，可能创建**补偿线程**来维持目标并行度，原线程则休眠等待

这是防止因任务间依赖而导致池级死锁的核心机制。

---

## 三、commonPool() 默认配置与共享陷阱

### 3.1 默认配置

`ForkJoinPool.commonPool()` 是**JVM 全局共享**的线程池实例：

```java
// JDK 源码中的默认构造逻辑
public ForkJoinPool() {
    this(Math.min(MAX_CAP, Runtime.getRuntime().availableProcessors()),
         defaultForkJoinWorkerThreadFactory, null, false);
}
// MAX_CAP = 0x7fff = 32767
```

**默认参数：**

| 参数 | 默认值 | 说明 |
|------|--------|------|
| 并行度 | `Runtime.availableProcessors()` | 通常 = CPU 核心数 |
| 线程工厂 | `defaultForkJoinWorkerThreadFactory` | 创建守护线程 |
| asyncMode | `false` (LIFO) | 适用于递归分治 |
| 最大备用线程 | 256 | `common.maximumSpares` |

**JVM 系统属性调优：**

```bash
# 设置 commonPool 并行度
-Djava.util.concurrent.ForkJoinPool.common.parallelism=8

# 设置线程工厂
-Djava.util.concurrent.ForkJoinPool.common.threadFactory=com.example.MyFactory

# 设置异常处理器
-Djava.util.concurrent.ForkJoinPool.common.exceptionHandler=com.example.MyHandler

# 限制最大补偿线程数
-Djava.util.concurrent.ForkJoinPool.common.maximumSpares=256
```

### 3.2 三者共用 commonPool 的关系图

```
┌──────────────────────────────────────────────────┐
│               JVM 全局唯一实例                      │
│         ForkJoinPool.commonPool()                  │
│      (默认并行度 = CPU核心数 - 1, 最少为1)            │
├──────────────────┬────────────────────────────────┤
│  parallelStream()│  CompletableFuture (无参形式)     │
│   并行流操作       │  supplyAsync / runAsync         │
└──────────────────┴────────────────────────────────┘
```

**关键事实**：`parallelStream()` 和 `CompletableFuture.supplyAsync()`（不传 Executor 的版本）都使用同一个 `commonPool()`。这意味着任何一方的阻塞都会影响全局。

### 3.3 容器化环境的特殊注意事项

在 Kubernetes/Docker 环境中，`Runtime.availableProcessors()` 返回的是**宿主机**的核心数，而非容器的 CPU 限制。需要通过以下方式修正：

```bash
# JDK 10+ 自动识别容器限制
-XX:+UseContainerSupport      # 默认开启
-XX:ActiveProcessorCount=4    # 手动指定
```

---

## 四、为何不能在 commonPool 中执行阻塞任务

### 4.1 阻塞的连锁反应

当 commonPool 中的某个任务执行阻塞操作（`Thread.sleep()`、`CountDownLatch.await()`、Socket I/O 等），该线程变为不可用状态。池会尝试补偿：

| 副作用 | 描述 |
|--------|------|
| **线程饥饿** | 阻塞的线程减少了 JVM 中**所有**使用 commonPool 的组件的可用并行度 |
| **线程爆炸（最多 256）** | 池可能不断创建补偿线程来维持目标并行度，直到达到 `common.maximumSpares`（默认 256） |
| **RejectedExecutionException** | 当 256 个备用线程限额耗尽时，抛出 `RejectedExecutionException: Thread limit exceeded replacing blocked worker` |
| **非确定性行为** | 任务被窃取和递归执行，配合 `ThreadLocal` 变量会产生难以调试的 bug |

### 4.2 真实案例

```java
// 模块A：并行流中的阻塞操作占满所有 commonPool 线程
IntStream.range(1, 1000).parallel().forEach(i -> {
    Thread.sleep(1000);  // 阻塞！commonPool 线程被耗尽
});

// 模块B：你的业务异步代码也被卡住！
CompletableFuture.supplyAsync(() -> callDatabase());  // 排队等线程 → 雪崩
```

### 4.3 2024 年 OpenJDK 邮件列表讨论

2024 年 1 月，Johannes Spangenberg 在 `core-libs-dev` 邮件列表报告了 `ForkJoinPool.managedBlock` 副作用导致 JUnit 测试非确定失败的问题：

- 并行流方法在 managed block 内部执行了**另一个测试**，破坏了 `ThreadLocal` 状态
- 12 个预期并行测试变成了 256 个线程同时运行
- 触发 `RejectedExecutionException`

**JDK 工程师的建议**：将 `maxPoolSize` 设为等于 `parallelism` 以禁用补偿线程，或使用**专用自定义池**替代 commonPool。

---

## 五、自定义 ForkJoinPool 创建与配置

### 5.1 构造方法

```java
// 完整构造方法
public ForkJoinPool(int parallelism,
                    ForkJoinWorkerThreadFactory factory,
                    UncaughtExceptionHandler handler,
                    boolean asyncMode)

// 简化构造
public ForkJoinPool(int parallelism)
```

### 5.2 典型创建模式

```java
// 1. 为 CPU 密集型任务创建（默认 LIFO 模式）
ForkJoinPool cpuPool = new ForkJoinPool(
    Runtime.getRuntime().availableProcessors()  // parallelism
);

// 2. 为事件驱动任务创建（FIFO asyncMode）
ForkJoinPool asyncPool = new ForkJoinPool(
    8,                                           // parallelism
    ForkJoinPool.defaultForkJoinWorkerThreadFactory,
    (t, e) -> log.error("Uncaught in " + t.getName(), e),  // handler
    true                                         // asyncMode = FIFO
);

// 3. 通过 Java 8 Executors 工具方法
ExecutorService workStealingPool = Executors.newWorkStealingPool(4);
// 底层是 ForkJoinPool，但接口是 ExecutorService，无需编写 RecursiveTask
```

### 5.3 自定义线程工厂

```java
ForkJoinPool pool = new ForkJoinPool(
    8,
    pool -> {
        ForkJoinWorkerThread thread = ForkJoinPool
            .defaultForkJoinWorkerThreadFactory.newThread(pool);
        thread.setName("custom-fjp-" + thread.getPoolIndex());
        thread.setDaemon(true);
        return thread;
    },
    null,   // handler
    false   // asyncMode
);
```

---

## 六、并行度调优

### 6.1 并行度的含义

ForkJoinPool 的并行度（parallelism）**不是**传统线程池的 `corePoolSize`。它代表的是：
- 同时活跃执行（包括窃取）的最大线程数目标
- 池会动态添加、挂起或恢复线程来维持目标
- 空闲线程会被缓慢回收，之后需要时再恢复

### 6.2 调优建议

| 场景 | 推荐并行度 | 理由 |
|------|-----------|------|
| 纯 CPU 密集计算 | `= CPU 核心数` | 避免上下文切换开销 |
| 混合场景（短时间阻塞） | `= CPU 核心数 × 1.5` | 补偿轻微阻塞 |
| 使用 ManagedBlocker | `= CPU 核心数` | ManagedBlocker 会自动补偿 |
| 容器化环境 | `= 容器 CPU 限额` | 防止宿主机核心数误导 |

### 6.3 递归任务拆分阈值调优

```java
class SumTask extends RecursiveTask<Long> {
    private static final int THRESHOLD = 4096;  // 关键调优点
    private int[] array;
    private int start, end;

    @Override
    protected Long compute() {
        int length = end - start;
        if (length <= THRESHOLD) {
            // 直接计算，不再 fork
            long sum = 0;
            for (int i = start; i < end; i++) sum += array[i];
            return sum;
        }
        // 拆分为两个子任务
        int mid = start + length / 2;
        SumTask left = new SumTask(array, start, mid);
        SumTask right = new SumTask(array, mid, end);
        left.fork();                              // 异步执行左半
        long rightResult = right.compute();        // 当前线程计算右半
        return left.join() + rightResult;          // 等待左半并合并
    }
}
```

**阈值经验值：**

| 任务类型 | 推荐 THRESHOLD | 说明 |
|----------|---------------|------|
| 整数求和/计数 | 1024 ~ 8192 | 过小导致窃取开销超过计算开销 |
| 字符串处理 | 512 ~ 2048 | 字符串操作比数值更重 |
| 对象图遍历 | 100 ~ 1000 | 取决于节点处理复杂度 |
| I/O 相关 | 不适用 FJP | 用 ThreadPoolExecutor 或虚拟线程 |

### 6.4 动态粒度控制

```java
// 根据当前队列积压情况动态调整是否继续拆分
if (getSurplusQueuedTaskCount() > 3) {
    // 池中有积压，直接计算以产生结果
    computeDirectly();
} else {
    // 池中空闲，继续拆分以增加并行度
    forkSubtasks();
}
```

---

## 七、asyncMode：FIFO 与 LIFO 的权衡

### 7.1 模式定义

```java
// asyncMode 决定本地取任务的方式
this(config, ...,
     asyncMode ? FIFO_QUEUE : LIFO_QUEUE,  // 高 16 位存储 mode
     ...);

static final int LIFO_QUEUE = 0;          // 默认
static final int FIFO_QUEUE = 1 << 16;    // asyncMode=true 时
```

### 7.2 对比分析

| 维度 | **LIFO（asyncMode=false，默认）** | **FIFO（asyncMode=true）** |
|------|----------------------------------|---------------------------|
| **本地取任务** | 从栈顶 pop（后进先出） | 从头部 poll（先进先出） |
| **窃取任务** | 始终从其他队列尾部 FIFO 窃取 | 同左（窃取始终 FIFO） |
| **外部提交顺序** | 可能因窃取导致乱序执行 | 走全局提交队列，趋于提交顺序 |
| **缓存局部性** | 优——最近 fork 的子任务大概率在 CPU 缓存中 | 较差——牺牲局部性换取公平性 |
| **适用场景** | 递归分治（fork/join）、CPU 密集型计算 | 事件驱动、消息处理、无需 join 的异步任务 |
| **Java 21+ 变化** | commonPool 用户态不变 | 虚拟线程默认调度器使用 FIFO |

### 7.3 适用场景选择

**适合 `asyncMode=true` 的场景：**
- 事件驱动任务（消息回调、HTTP 异步处理、日志聚合）
- 不调用 `join()` 的独立异步事件流
- 需要提交顺序约等于执行顺序的公平性要求
- 简单转换和校验任务（JSON 解析+校验等）

**应保持 `asyncMode=false`（默认 LIFO）的场景：**
- 归并排序、快速排序等递归分治算法
- 频繁 fork/join 的 CPU 密集型计算
- 深度递归、子任务轻量级的场景
- 依赖缓存局部性优化的计算

### 7.4 JDK 24 修复（JDK-8322732）

2024 年 Doug Lea 提交了一个重要修复：asyncMode 下 ForkJoinPool 可能低效利用核心数 (`JDK-8322732`)。该补丁修正了 asyncMode 下的线程信号机制，确保在 FIFO 模式下也能充分保持目标并行度。

---

## 八、ForkJoinPool 线程管理与饱和策略

### 8.1 线程生命周期

ForkJoinPool 的线程管理逻辑**不同于 ThreadPoolExecutor**：

| 概念 | ThreadPoolExecutor | ForkJoinPool |
|------|-------------------|--------------|
| 核心线程数 | `corePoolSize`（明确指定） | 无此概念 |
| 最大线程数 | `maximumPoolSize` | 目标并行度 + 补偿线程（最多 256 备用） |
| 空闲超时 | `keepAliveTime` + `TimeUnit` | 缓慢延迟回收，无显式 timeout |
| 队列 | 有界/无界 BlockingQueue | 每个线程独立 WorkQueue + 全局提交队列 |
| 拒绝策略 | Abort/CallerRuns/Discard/DiscardOldest | 仅在线程限制耗尽时抛出 `RejectedExecutionException` |

### 8.2 饱和策略的特殊性

ForkJoinPool **没有** `RejectedExecutionHandler` 接口。当无法创建补偿线程时，直接抛出 `RejectedExecutionException`。

**控制补偿线程数的系统属性：**

```properties
# 设为与 parallelism 相等的值可以禁用补偿线程（fail-fast 模式）
java.util.concurrent.ForkJoinPool.common.maximumSpares = 256
```

当 `maximumSpares` 设为等于 `parallelism` 时，实际上禁用了补偿机制——适合希望快速暴露阻塞问题的开发和测试环境。

### 8.3 线程回收

ForkJoinPool 的空闲线程回收是**缓慢、延迟**的过程，不像 ThreadPoolExecutor 有明确的 `keepAliveTime`。这意味着：
- 突发任务后线程不会立即回收，适合后续可能的突发
- 长期闲置的池会逐渐减少线程数
- 无法精确控制回收时机

---

## 九、ManagedBlocker：在 FJP 中正确处理阻塞

### 9.1 设计目的

`ForkJoinPool.ManagedBlocker` 是官方的**阻塞逃生舱口**：当 FJP 中的任务需要进行不可避免的阻塞操作时，通过此接口告知池"我即将阻塞，你可以考虑启动补偿线程"。

### 9.2 接口定义与使用

```java
ForkJoinPool.managedBlock(new ForkJoinPool.ManagedBlocker() {
    volatile boolean ready = false;

    // 在不阻塞的情况下检查是否已经可以继续
    public boolean isReleasable() {
        return ready;  // 或 tryLock(), queue.poll() 等非阻塞检查
    }

    // 执行实际的阻塞操作
    public boolean block() throws InterruptedException {
        if (!ready)
            resource.waitFor();  // 阻塞等待
        return true;             // true = 不需要进一步阻塞
    }
});
```

调用流程：
1. 池先调用 `isReleasable()`——如果返回 `true`，无需阻塞，直接返回
2. 如果 `isReleasable()` 返回 `false`，池激活一个补偿线程，然后调用 `block()`
3. `block()` 返回 `true` 表示阻塞完成，补偿线程随后可能被回收

### 9.3 常见模式

**基于锁的 ManagedBlocker：**
```java
class LockManagedBlocker implements ForkJoinPool.ManagedBlocker {
    private final ReentrantLock lock;
    private boolean locked = false;

    public boolean isReleasable() {
        return locked || (locked = lock.tryLock());
    }

    public boolean block() throws InterruptedException {
        if (!locked) {
            lock.lockInterruptibly();
            locked = true;
        }
        return true;
    }
}
```

**基于队列的 ManagedBlocker：**
```java
class QueueManagedBlocker<T> implements ForkJoinPool.ManagedBlocker {
    private final BlockingQueue<T> queue;
    private volatile T result;

    public boolean isReleasable() {
        return (result = queue.poll()) != null;
    }

    public boolean block() throws InterruptedException {
        if (result == null)
            result = queue.take();
        return true;
    }
}
```

### 9.4 局限性与最佳实践

- ManagedBlocker 适用于**短时间**、**不可避免**的阻塞
- 不应用于长时间阻塞 I/O（数秒以上）——应使用专门线程池
- 当大量线程同时阻塞时，补偿线程可能耗尽（256 限制）
- 嵌套使用 ManagedBlocker 需谨慎，可能导致意外行为

---

## 十、ForkJoinPool 监控与诊断

### 10.1 核心监控方法

| 方法 | 返回类型 | 含义 |
|------|----------|------|
| `getParallelism()` | `int` | 目标并行度 |
| `getPoolSize()` | `int` | 当前已启动的工作线程总数 |
| `getActiveThreadCount()` | `int` | 正在窃取或执行任务的线程数（可能高估） |
| `getRunningThreadCount()` | `int` | 未被阻塞的线程数（排除等待 join 的线程） |
| `getQueuedSubmissionCount()` | `int` | 外部提交但尚未开始执行的任务数 |
| `getQueuedTaskCount()` | `long` | 所有工作线程队列中的任务总数 |
| `getStealCount()` | `long` | 累计窃取任务完成次数（非静态时可能低估） |
| `isQuiescent()` | `boolean` | 所有工作线程是否都处于空闲状态 |
| `toString()` | `String` | 可读的快照，包含运行状态和各项计数 |

### 10.2 窃取开销检测

JDK 文档明确指出窃取计数的"黄金区间"原则：

> **窃取次数应足够高以保持线程繁忙，但足够低以避免线程间的开销和竞争。**

**诊断公式：**

```java
ForkJoinPool pool = ForkJoinPool.commonPool();

// 周期性采样
long steals = pool.getStealCount();
long queued = pool.getQueuedTaskCount();
int active = pool.getActiveThreadCount();
int running = pool.getRunningThreadCount();

// 窃取比率（每个任务平均被窃取次数）
// 如果 stealCount 增长速度远超任务完成速度 → 任务粒度过细，窃取开销过大
// 如果 activeThreadCount ≈ parallelism 但 queuedTaskCount 很高 → 利用良好
// 如果 activeThreadCount < parallelism 但 queuedTaskCount > 0 → 负载不均
// 如果 runningThreadCount < activeThreadCount → 线程在 join/IO 上阻塞，存在竞争
```

### 10.3 监控工具

| 工具 | 能力 |
|------|------|
| **JMX** | 注册 ForkJoinPool MBean；使用 JConsole / VisualVM 检查池状态、线程细节、队列长度 |
| **Java Mission Control (JMC)** | 监控 CPU 利用率、线程活跃度、任务执行时间分布 |
| **`toString()`** | 快速可读快照，便于调试日志 |
| **Quasar ForkJoinInfo** | 第三方库的快照包装器，同时捕获多项指标 |

### 10.4 健康度指标综合判断

```java
public class ForkJoinPoolHealth {
    private final ForkJoinPool pool;

    public String diagnose() {
        int parallelism = pool.getParallelism();
        int active = pool.getActiveThreadCount();
        int running = pool.getRunningThreadCount();
        long queued = pool.getQueuedTaskCount();

        if (active < parallelism && queued > 0)
            return "警告：负载不均——有任务积压但线程未充分利用";
        if (running < active)
            return "警告：线程阻塞——" + (active - running) + " 个线程在 join/IO 上阻塞";
        if (queued > 10000)
            return "警告：任务积压严重——当前队列任务: " + queued;
        if (active == 0 && queued == 0)
            return "空闲——池处于静止状态";
        return "健康——活跃线程: " + active + "/" + parallelism + ", 队列: " + queued;
    }
}
```

---

## 十一、ForkJoinPool vs ThreadPoolExecutor：场景选型

### 11.1 架构差异对比

| 维度 | ThreadPoolExecutor | ForkJoinPool |
|------|-------------------|--------------|
| **队列设计** | 单一共享 BlockingQueue | 每线程独立双端队列（WorkQueue） |
| **任务获取** | 多线程竞争同一队列（有锁争用） | 拥有者无锁 pop，空闲者 CAS steal |
| **负载均衡** | 被动——依赖队列公平性 | 主动——工作窃取自动均衡 |
| **线程管理** | corePoolSize / maxPoolSize / keepAliveTime | 目标并行度 + 补偿线程 |
| **拒绝策略** | 4 种内置 + 可自定义 | 仅 RejectedExecutionException |
| **有界队列** | 支持（LinkedBlockingQueue(capacity)） | 不支持 |
| **任务取消** | 完整支持 Future.cancel() | 有限支持（窃取后无法取消） |

### 11.2 性能特点对比

| 场景 | ForkJoinPool | ThreadPoolExecutor |
|------|-------------|-------------------|
| 递归/分治算法 | 优秀（3-8 倍提升） | 差（共享队列瓶颈） |
| 细粒度海量任务 | 很好（低竞争双端队列） | 差（锁竞争） |
| 阻塞 I/O | 差（阻塞工作线程，窃取停顿） | 可接受（适当配置线程数） |
| 统一独立任务 | 好 | 好 |
| 需要限流和背压 | 不支持 | 完整支持（有界队列+拒绝策略） |
| 需要定时调度 | 不支持 | ScheduledThreadPoolExecutor |
| 少量长任务 | 不适用（窃取开销无收益） | 好 |

### 11.3 快速决策指南

```
你的任务是？
├── 独立的异步任务（HTTP、DB、文件 I/O）
│   └── 用 ThreadPoolExecutor ✅
├── 需要限流 / 拒绝策略 / 有界队列
│   └── 用 ThreadPoolExecutor ✅（FJP 不支持）
├── 可以拆分为子任务的 CPU 密集计算
│   └── 用 ForkJoinPool ✅
├── 大量短小并发任务 + 需要负载均衡
│   └── 用 ForkJoinPool（或 newWorkStealingPool） ✅
├── 定时/周期性任务
│   └── 用 ScheduledThreadPoolExecutor ✅
└── 直接用 parallelStream() 做数据并行
    └── 底层已经是 ForkJoinPool ✅（但注意隔离）
```

---

## 十二、CompletableFuture 与 ForkJoinPool 的关系

### 12.1 默认线程池判定逻辑

`CompletableFuture.runAsync()` 和 `supplyAsync()` 无参版本使用的线程池取决于 `ForkJoinPool.getCommonPoolParallelism()` 的返回值：

| 条件 | 行为 |
|------|------|
| CPU 核心数 <= 2（即 commonParallelism = 1） | **不使用** commonPool，每个任务 new 一个 Thread（`ThreadPerTaskExecutor`） |
| CPU 核心数 > 2（即 commonParallelism >= 2） | **使用** `ForkJoinPool.commonPool()` |
| 设置 `-Djava.util.concurrent.ForkJoinPool.common.parallelism=N` | 以参数值为准 |

### 12.2 最佳实践：显式指定线程池

```java
// ❌ 错误：阻塞 I/O 污染 commonPool
CompletableFuture.supplyAsync(() -> blockingDatabaseCall());

// ✅ 正确：为 I/O 密集型任务创建专用线程池
private static final Executor IO_POOL = new ThreadPoolExecutor(
    4, 8, 60L, TimeUnit.SECONDS,
    new LinkedBlockingQueue<>(200),
    new ThreadFactoryBuilder().setNameFormat("io-async-%d").build(),
    new ThreadPoolExecutor.CallerRunsPolicy()
);

CompletableFuture.supplyAsync(() -> blockingDatabaseCall(), IO_POOL)
    .thenApplyAsync(result -> transform(result), IO_POOL)
    .thenAcceptAsync(this::notifyUser, IO_POOL);
```

### 12.3 嵌套 CompletableFuture 死锁

```java
ExecutorService pool = Executors.newFixedThreadPool(5);

// 外层任务占满 5 个线程
for (int i = 0; i < 5; i++) {
    CompletableFuture.runAsync(() -> {
        // 内层任务也需要从同一池获取线程 → 永远等不到 → 死锁！
        CompletableFuture.supplyAsync(() -> compute(), pool).join();
    }, pool);
}
```

解决方案：使用**无界线程池**为新任务服务，或使用**两个独立线程池**分别服务外层和内层任务。

---

## 十三、并行流（Parallel Streams）的隐藏风险

### 13.1 parallelStream 始终使用 commonPool

`parallelStream()` **无法直接指定线程池**，始终使用 `ForkJoinPool.commonPool()`。

### 13.2 隔离方案

```java
// 通过包装在自定义 ForkJoinPool 中执行
ForkJoinPool customPool = new ForkJoinPool(4);
try {
    customPool.submit(() ->
        list.parallelStream()
            .map(this::heavyComputation)
            .collect(Collectors.toList())
    ).get();
} finally {
    customPool.shutdown();
}
```

### 13.3 风险清单

| 风险 | 描述 | 缓解措施 |
|------|------|----------|
| **全局污染** | 一个并行流阻塞，影响所有使用 commonPool 的代码 | 用自定义 FJP 包装 |
| **嵌套并行流** | 内层并行流在 commonPool 线程内再次 request commonPool 线程 → 可能死锁 | 避免嵌套或确保外层线程数充足 |
| **ThreadLocal 泄漏** | 工作窃取导致 ThreadLocal 上下文混乱 | 不使用 ThreadLocal 或在任务边界清理 |
| **容器化误判** | `availableProcessors()` 返回宿主机核心数 | 使用 `-XX:ActiveProcessorCount` |

---

## 十四、虚拟线程（JEP 444）工作原理

### 14.1 M:N 调度模型

虚拟线程是 JVM 管理的轻量级线程，采用 **M:N 调度模型**——将数百万虚拟线程多路复用到少量 OS 线程（载波线程）上：

| 特性 | 平台线程 | 虚拟线程 |
|------|---------|----------|
| **栈内存** | ~1 MB（固定，OS 分配） | 开始时约 4 KB（动态增长，堆分配） |
| **调度器** | OS 内核 | JVM 用户态（ForkJoinPool） |
| **阻塞代价** | 阻塞 OS 线程（昂贵） | 从载波线程卸载，载波线程被复用 |
| **池化** | 必需（创建成本高） | **不应当池化** |
| **创建耗时** | ~1 ms | ~1 μs |
| **数量上限** | ~5,000-8,000 | 百万级别 |

> **JEP 444 原文**："虚拟线程廉价且充裕，因此永远不应当池化：每个应用任务都应创建一个新的虚拟线程。"

### 14.2 Continuation 机制

虚拟线程的核心实现是 `jdk.internal.vm.Continuation` 类：

```
VirtualThread.run()
  → VThreadContinuation.run()
    → Continuation.enter0()         [native 方法]
      → Continuation.enter()        [Java]
        → Continuation.run()        [Java]
          → VirtualThread.runContinuation()
```

**冻结（Freeze）/解冻（Thaw）周期：**

```
┌──────────────────────────────────────────────┐
│ 虚拟线程挂载在载波线程上                          │
│  - Java 栈帧在物理栈上                          │
│  - LockStack 在载波线程上                       │
└──────────────────┬───────────────────────────┘
                   │ park/block/I-O
                   ▼
┌──────────────────────────────────────────────┐
│ 冻结 (ContinuationFreezeThaw)                  │
│  1. 复制 LockStack oops → stackChunk           │
│  2. 复制 Java 栈帧 → stackChunk（堆上对象）     │
│  3. 清除载波物理栈帧                             │
│  4. ObjectMonitor._owner 存储 Thread.tid        │
└──────────────────┬───────────────────────────┘
                   │
            虚拟线程已卸载（在堆上）
                   │
                   │ 被重新调度
                   ▼
┌──────────────────────────────────────────────┐
│ 解冻 (ContinuationFreezeThaw)                  │
│  1. 复制 stackChunk → 新载波物理栈              │
│  2. 从 stackChunk 恢复 LockStack oops          │
│  3. 设置 JavaThread._lock_id = vthread.tid     │
└──────────────────────────────────────────────┘
```

**关键设计决策**：虚拟线程的栈帧存储在堆上的 `stackChunk` 对象中（而非 OS 线程栈），因此每个虚拟线程仅消耗数十 KB（而非 1 MB）。这种设计使得百万级虚拟线程成为可能。

### 14.3 JDK 24 改进：同步块不再钉住（JEP 491）

JDK 24 重写了对象监视器实现，使虚拟线程在 `synchronized` 块内阻塞时**不再被钉住**。关键变更：
- `ObjectMonitor._owner` 从存储 `JavaThread*` 改为存储 `java.lang.Thread.tid`（64 位字段）
- 冻结时将 LockStack 的 oops 复制到 stackChunk
- 解冻时从 stackChunk 恢复到新载波线程的 LockStack
- 新增专用的 unblocker 线程将唤醒的虚拟线程重新提交到调度器

**性能改进**（5,000 虚拟线程，调度器并行度=1）：
- JDK 21: ~31.8 秒
- JDK 24: ~0.45 秒（**约 70 倍提升**）

---

## 十五、虚拟线程调度器与 ForkJoinPool

### 15.1 默认调度器

虚拟线程调度器内部使用 `ForkJoinPool`（asyncMode = FIFO），以 `Runtime.availableProcessors()` 为并行度。

```java
// 可通过系统属性调整调度器并行度
-Djdk.virtualThreadScheduler.parallelism=8

// 设置调度器最大线程池大小
-Djdk.virtualThreadScheduler.maxPoolSize=256
```

### 15.2 载波线程扩展

当虚拟线程在阻塞操作上卸载时，调度器会**临时扩展载波线程数**（类似于 FJP 的补偿机制），以确保其他虚拟线程能继续执行。默认最大载波线程数为 256。

### 15.3 调度器隔离

当创建的虚拟线程很多且有大量阻塞 I/O 时，调度器的 ForkJoinPool 可能成为瓶颈。此时可为特定的虚拟线程组设置独立的调度器，但一般不需要——默认配置对绝大多数场景足够。

---

## 十六、何时虚拟线程可以取代线程池

### 16.1 明确适合的场景

| 场景 | 原因 |
|------|------|
| **HTTP 请求处理**（Spring MVC / JAX-RS） | 每个请求一个虚拟线程，阻塞式数据库调用不影响载波线程 |
| **微服务间 RPC 调用** | 大量并发请求，低内存开销，阻塞等待 I/O 自动卸载 |
| **消息消费者**（Kafka / RabbitMQ） | 每个分区一个虚拟线程，poll 阻塞不影响其他分区 |
| **文件处理管道** | 简化的同步代码，JVM 自动处理卸载（注意：文件 I/O 可能钉住） |
| **WebSocket 连接** | 每连接一个虚拟线程，长期存活不消耗大量内存 |

### 16.2 简化的编程模型

```java
// 旧方式：精心调优的线程池 + CompletableFuture
ExecutorService pool = Executors.newFixedThreadPool(200);
CompletableFuture<PaymentResponse> future =
    CompletableFuture.supplyAsync(() -> paymentClient.process(order), pool);

// 新方式：直接同步调用（虚拟线程使阻塞代码变得可扩展）
public PaymentResponse processOrder(Order order) {
    return paymentClient.process(order); // 阻塞时自动卸载，无需异步包装！
}
```

### 16.3 一键切换

```java
// ❌ 旧方式：固定大小线程池
ExecutorService executor = Executors.newFixedThreadPool(200);

// ✅ 新方式：虚拟线程（每个任务一个虚拟线程）
try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
    for (int i = 0; i < 100_000; i++) {
        executor.submit(() -> blockingIOOperation());
    }
}
```

Spring Boot 3.2+ 配置：
```properties
spring.threads.virtual.enabled=true
```

---

## 十七、何时仍需要线程池

### 17.1 虚拟线程不适用的场景

| 场景 | 原因 | 替代方案 |
|------|------|----------|
| **CPU 密集型计算** | 虚拟线程不能被抢占，会长期占用载波线程 | 继续使用 ForkJoinPool 或 Parallel Streams |
| **需要限制并发数**（数据库/外部 API） | 虚拟线程不提供内置限流 | 用 `Semaphore` 控制 |
| **需要线程局部缓存**（如 ObjectMapper） | 百万虚拟线程 = 百万个缓存实例 → OOM | 使用共享不可变实例或池化 |
| **频繁 `synchronized`（JDK 21-23）** | 钉住载波线程 | 升级到 JDK 24+ 或用 ReentrantLock |

### 17.2 仍需线程池的场景总结

线程池在以下场景仍然有价值：
1. **CPU 密集型并行计算**：`ForkJoinPool` 的工作窃取是最高效方式
2. **需要背压和限流**：有界队列 + 拒绝策略的细粒度控制
3. **需要线程亲和性**：特定线程绑定特定资源（如 pinned 到 CPU 核心）
4. **遗留代码**：依赖 `ThreadLocal` 的三方库尚未适配虚拟线程
5. **JDK 21-23 的 `synchronized` 密集代码**：JDK 24 已修复，若无法升级则需保留平台线程

### 17.3 用 Semaphore 替代线程池限流

```java
// 旧方式：线程池大小控制并发
ExecutorService pool = Executors.newFixedThreadPool(50);  // 最多 50 并发

// 新方式：Semaphore + 虚拟线程
private static final Semaphore DB_SEMAPHORE = new Semaphore(50);

try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
    for (int i = 0; i < 1_000_000; i++) {
        executor.submit(() -> {
            DB_SEMAPHORE.acquire();
            try {
                database.query(sql);
            } finally {
                DB_SEMAPHORE.release();
            }
        });
    }
}
```

---

## 十八、虚拟线程钉住（Pinning）问题

### 18.1 JDK 21-23 的钉住场景

在 JDK 21-23 中，以下场景会导致虚拟线程被钉住在载波线程上：

| 钉住原因 | 说明 |
|----------|------|
| **`synchronized` 块 + 阻塞 I/O** | synchronized 使用的 ObjectMonitor 与载波线程绑定 |
| **本地方法（JNI）** | 本地代码无法感知虚拟线程的 Continuation |
| **类初始化（`<clinit>`）** | 类加载过程中的同步需要平台线程上下文 |
| **`Object.wait()`** | 在 synchronized 块内调用 wait（JDK 24 部分修复） |

**钉住的后果：** 载波线程被占用而无法服务其他虚拟线程，导致吞吐量急剧下降。如果有大量虚拟线程被钉住，可能导致载波线程池耗尽。

**性能影响基准测试**（50,000 虚拟线程，100 资源）：
- `synchronized`：51.6 秒（969 ops/sec）
- `ReentrantLock`：5.8 秒（8,576 ops/sec）——**约 8.8 倍差距**

### 18.2 JDK 24 的修复（JEP 491）

JDK 24 通过重写 ObjectMonitor 彻底解决了 `synchronized` 钉住问题，但以下场景仍然会钉住：
- **本地代码调用（JNI）**
- **某些文件 I/O 操作**（网络 I/O 已在 JDK 21 修复）
- **类加载时的同步块**

### 18.3 JDK 24+ 的编码建议

尽管 JDK 24 已修复 `synchronized` 钉住问题，但 OpenJDK 团队**仍然建议使用 `ReentrantLock`** 替代 `synchronized`：
- `j.u.c` 锁预期会后续获得更多性能优化
- `ReentrantLock` 提供更丰富的功能（tryLock、公平锁、Condition）
- 确保向后兼容 JDK 21-23

```java
// 推荐方式（适用于所有 JDK 版本）
private final ReentrantLock lock = new ReentrantLock();

lock.lock();
try {
    // 临界区 + 可能的阻塞 I/O
} finally {
    lock.unlock();
}
```

---

## 十九、使用 JFR 检测虚拟线程钉住

### 19.1 JFR 事件

Java Flight Recorder 提供了 `jdk.VirtualThreadPinned` 事件来监控钉住：

```java
// 在应用程序代码中启用 JFR 录制
Recording recording = new Recording();
recording.enable("jdk.VirtualThreadPinned");
recording.start();
```

### 19.2 JDK 系统属性

```bash
# 开发环境：记录完整堆栈（不要在生产环境中使用）
-Djdk.tracePinnedThreads=full

# 仅记录简要信息
-Djdk.tracePinnedThreads=short

# 记录虚拟线程设置 ThreadLocal 的情况
-Djdk.traceVirtualThreadLocals=true
```

### 19.3 监控工具总结

| 工具/标志 | 用途 |
|-----------|------|
| `jdk.VirtualThreadPinned` | JFR 事件——在生产中监控钉住 |
| `-Djdk.tracePinnedThreads=full` | 钉住发生时记录堆栈（仅开发） |
| `-Djdk.traceVirtualThreadLocals=true` | 虚拟线程设置 ThreadLocal 时记录 |
| `jcmd <pid> Thread.dump` | 导出包括虚拟线程在内的线程状态 |
| `jconsole` | 实时查看虚拟线程数量和内存占用 |

---

## 二十、结构化并发（JEP 453 / JEP 505）

### 20.1 演进历程

| JEP | JDK 版本 | 状态 |
|-----|---------|------|
| JEP 428 | JDK 19 | 孵化器 |
| JEP 437 | JDK 20 | 第二孵化器 |
| JEP 453 | JDK 21 | 第一预览 |
| JEP 462 | JDK 22 | 第二预览 |
| JEP 480 | JDK 23 | 第三预览 |
| JEP 505 | JDK 25 | 继续预览 |

> **当前状态（JDK 25）**：结构化并发仍在预览中，尚未最终确定。预计可能在 JDK 26 中正式发布。

### 20.2 核心概念

结构化并发将一组相关的并发子任务视为**单个工作单元**，提供自动的错误处理、取消传播和清理——类似 `try-with-resources` 对资源的管理。

### 20.3 核心 API

**ShutdownOnFailure（invoke-all 模式）：**

```java
Response handle() throws ExecutionException, InterruptedException {
    try (var scope = new StructuredTaskScope.ShutdownOnFailure()) {
        Supplier<String>  user  = scope.fork(() -> findUser());
        Supplier<Integer> order = scope.fork(() -> fetchOrder());
        Supplier<Product>  prod  = scope.fork(() -> fetchProduct());

        scope.join()            // 等待所有子任务完成
             .throwIfFailed();  // 如果任何子任务失败，抛出异常

        // 所有子任务都成功，组装结果
        return new Response(user.get(), order.get(), prod.get());
    }
    // scope 关闭时自动取消所有未完成的子任务
}
```

**ShutdownOnSuccess（invoke-any 模式）：**

```java
<T> T race(List<Callable<T>> tasks) throws InterruptedException, ExecutionException {
    try (var scope = new StructuredTaskScope.ShutdownOnSuccess<T>()) {
        for (Callable<T> task : tasks) {
            scope.fork(task);
        }
        return scope.join().result();  // 返回第一个成功结果
    }
    // scope 关闭时自动取消其余任务
}
```

### 20.4 结构化并发 vs 传统方式

| 维度 | 传统方式（CompletableFuture / ExecutorService） | 结构化并发 |
|------|-----------------------------------------------|-----------|
| **线程泄漏** | 忘记 shutdown / join → 线程泄漏 | try-with-resources 保证清理 |
| **错误传播** | 手动处理异常，容易遗漏 | `throwIfFailed()` 统一处理 |
| **取消传播** | 手动取消相关 future | scope 关闭时自动取消 |
| **可观察性** | 线程转储中难以关联父子关系 | 父子关系明确，线程转储可见 |
| **代码可读性** | 回调链冗长 | 线性同步风格 |

---

## 二十一、Scoped Values（JEP 446 / JEP 506）

### 21.1 演进历程

| JEP | JDK 版本 | 状态 |
|-----|---------|------|
| JEP 429 | JDK 20 | 孵化器 |
| JEP 446 | JDK 21 | 第一预览 |
| JEP 464 | JDK 22 | 第二预览 |
| JEP 481 | JDK 23 | 第三预览 |
| JEP 487 | JDK 24 | 第四预览 |
| JEP 506 | JDK 25 | **正式确定** |

> **JDK 25 中 Scoped Values 已最终确定。**

### 21.2 ThreadLocal 在虚拟线程时代的问题

```java
// ❌ 危险：百万虚拟线程 = 百万个 ObjectMapper 实例 → OOM
private static final ThreadLocal<ObjectMapper> mapper =
    ThreadLocal.withInitial(ObjectMapper::new);
```

- ThreadLocal 的生命周期与线程绑定——每个虚拟线程都创建新的初始值
- 百万虚拟线程意味着百万个 ThreadLocal 值副本
- 手动清理（`remove()`）容易被遗忘

### 21.3 ScopedValue 核心 API

```java
// 1. 声明一个不可变的 ScopedValue
private static final ScopedValue<String> REQUEST_ID = ScopedValue.newInstance();
private static final ScopedValue<User> CURRENT_USER = ScopedValue.newInstance();

// 2. 在作用域内绑定值
ScopedValue.where(REQUEST_ID, "REQ-12345")
           .where(CURRENT_USER, authenticatedUser)
           .run(() -> {
               // 在此作用域内可以访问这些值
               String id = REQUEST_ID.get();    // "REQ-12345"
               User user = CURRENT_USER.get();  // authenticatedUser

               // 虚拟线程自动继承 ScopedValue
               Thread.startVirtualThread(() -> {
                   String inheritedId = REQUEST_ID.get(); // 仍然可用！
               });
           });

// 3. 有返回值版本
String result = ScopedValue.where(REQUEST_ID, "REQ-67890")
                           .call(() -> processRequest());

// 4. 作用域退出后值自动不可用
// REQUEST_ID.get() → NoSuchElementException
```

### 21.4 ScopedValue vs ThreadLocal

| 特性 | ScopedValue | ThreadLocal |
|------|-------------|-------------|
| **可变性** | 不可变（一次绑定） | 可变（set() 随时修改） |
| **生命周期** | 限定于代码块 | 线程生命周期（需手动 remove） |
| **子线程继承** | 自动继承 | 需要 InheritableThreadLocal |
| **虚拟线程内存** | 高效（共享结构） | 每个线程一个副本（高开销） |
| **清理保证** | 作用域退出自动清理 | 容易遗忘导致泄漏 |
| **结构化并发** | 天然集成 | 不兼容 |

---

## 二十二、性能对比：平台线程 vs 虚拟线程

### 22.1 内存占用

| 指标 | 平台线程 | 虚拟线程 | 提升 |
|------|---------|----------|------|
| **单线程内存** | ~1 MB（OS 栈，固定） | ~2-30 KB（堆分配，动态扩展） | **~98.5% 减少** |
| **1 万线程** | ~10 GB | ~30-300 MB | **~97% 减少** |
| **10 万线程** | ~98 GB（理论值，实际会 OOM） | ~1.5 GB | **~98.5% 减少** |

### 22.2 上下文切换

| 维度 | 平台线程 | 虚拟线程 |
|------|---------|----------|
| **切换级别** | 内核态（昂贵，微秒级） | 用户态/JVM 级（廉价，纳秒级） |
| **阻塞时行为** | OS 线程阻塞 → 浪费 | 虚拟线程卸载 → 载波线程立即复用 |
| **GC 暂停（10 万线程）** | ~4.2 秒/分钟 | ~0.3 秒/分钟（**~93% 减少**） |

### 22.3 吞吐量与延迟

**I/O 密集型工作负载（2024 年 NCI 硕士论文基准测试）：**

| 指标 | 平台线程 | 虚拟线程 | 提升 |
|------|---------|----------|------|
| 吞吐量 | baseline | +60.79% | 1.6x |
| 延迟 | baseline | -28.8% | 1.4x |
| 内存使用 | baseline | -36.36% | 1.6x |
| CPU 利用率 | baseline | -14.29% | 更高效 |

**高并发压力测试（阿里云，32 核，Spring Boot 4.0，2025 年）：**

| 并发数 | 平台线程（Spring Boot 3.1） | 虚拟线程（Spring Boot 4.0） | 增益 |
|--------|---------------------------|---------------------------|------|
| 1 万 | 12,500 QPS | 13,200 QPS | +5.6% |
| 5 万 | 2,300 QPS（12% 错误率） | 51,800 QPS | **+2,150%** |
| 10 万 | **服务崩溃** | 98,400 QPS | 无限（未崩溃） |
| 24 万 | — | 128,000 QPS | — |

**10 万并发下的延迟：**
- 平台线程：P50 = 324ms，P99 = 1.8s，Max = 23s
- 虚拟线程：P50 = 8ms，P99 = 41ms，Max = 130ms

### 22.4 CPU 密集型工作负载

CPU 密集型任务的平台线程和虚拟线程表现**基本相当**。CPU 密集型任务不触发阻塞/卸载，虚拟线程无法发挥优势，且调度开销可能轻微降低性能。

### 22.5 生产环境案例（2024 年电商平台）

| 指标 | 2023 年（传统线程池） | 2024 年（虚拟线程） |
|------|---------------------|--------------------|
| 峰值订单/分钟 | 412,000 | 5,280,000（**12.8 倍**） |
| 服务器数量 | 1,200（8C/32G） | 320（32C/128G） |
| 服务器成本 | baseline | **-72%** |
| 超时率 | 6.8% | 0.04% |
| 平均延迟 | 320ms | 26ms |

---

## 二十三、从线程池迁移到虚拟线程的完整指南

### 23.1 迁移优先级清单

| 优先级 | 动作 | 说明 |
|--------|------|------|
| **P0** | 升级 JDK 到 21+（建议 24+ 获取 synchronize 修复） | 虚拟线程的最低门槛 |
| **P0** | 框架启用虚拟线程支持 | Spring Boot 3.2+ / Quarkus 配置 |
| **P1** | `ThreadLocal` → `ScopedValue`（JDK 25）或共享不可变实例 | 避免百万实例导致 OOM |
| **P2** | 添加 `Semaphore` 控制外部资源 | 数据库、外部 API 调用需要限流 |
| **P3** | 简化 `CompletableFuture` 链 | 改回同步阻塞式代码 |
| **P4** | 移除 JDK 21-23 的 `ReentrantLock` 替代方案（可选） | JDK 24+ 已修复 synchronized 钉住 |

### 23.2 步骤 1：切换 Executor

```java
// 旧：固定大小线程池
@Bean("taskExecutor")
public Executor taskExecutor() {
    return Executors.newFixedThreadPool(200);
}

// 新：虚拟线程
@Bean("taskExecutor")
public Executor taskExecutor() {
    return Executors.newVirtualThreadPerTaskExecutor();
}
```

### 23.3 步骤 2：添加资源限流

```java
// 为外部资源调用添加 Semaphore
@Component
public class DatabaseService {
    // 与数据库连接池大小匹配
    private static final Semaphore DB_SEMAPHORE = new Semaphore(50);

    public Result query(String sql) {
        DB_SEMAPHORE.acquire();
        try {
            return jdbcTemplate.query(sql);
        } finally {
            DB_SEMAPHORE.release();
        }
    }
}
```

### 23.4 步骤 3：替换 ThreadLocal

```java
// 旧：ThreadLocal（虚拟线程下内存膨胀）
private static final ThreadLocal<String> traceId = new ThreadLocal<>();

// 新：ScopedValue（JDK 25+ 正式可用）
private static final ScopedValue<String> TRACE_ID = ScopedValue.newInstance();

// 在请求入口设置
ScopedValue.where(TRACE_ID, generateTraceId())
           .run(() -> handleRequest(request));

// 在任意位置读取
String id = TRACE_ID.get();
```

### 23.5 步骤 4：简化异步代码

```java
// 旧：CompletableFuture 异步编排
public OrderResponse process(Order order) {
    CompletableFuture<User> userFuture =
        CompletableFuture.supplyAsync(() -> userService.get(order.getUserId()), pool);
    CompletableFuture<Payment> payFuture =
        CompletableFuture.supplyAsync(() -> paymentService.get(order.getPayId()), pool);

    return userFuture.thenCombine(payFuture,
        (user, payment) -> assemble(user, payment)
    ).join();
}

// 新：同步阻塞代码（虚拟线程自动处理并发）
public OrderResponse process(Order order) {
    // 每个调用在虚拟线程中运行，阻塞 I/O 自动卸载
    User user = userService.get(order.getUserId());
    Payment payment = paymentService.get(order.getPayId());
    return assemble(user, payment);
}
```

### 23.6 步骤 5：避免常见陷阱

```java
// ❌ 陷阱 1：不要池化虚拟线程
ExecutorService pool = Executors.newFixedThreadPool(100);
pool.submit(() -> Thread.startVirtualThread(...));  // 毫无意义的双重包装

// ✅ 一个任务一个虚拟线程
try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
    executor.submit(task);
}

// ❌ 陷阱 2：ThreadLocal 导致内存膨胀
private static final ThreadLocal<ObjectMapper> mapper =
    ThreadLocal.withInitial(ObjectMapper::new);

// ✅ 使用共享不可变实例
private static final ObjectMapper MAPPER = new ObjectMapper();

// ❌ 陷阱 3：无限制冲击外部资源
try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
    for (int i = 0; i < 1_000_000; i++) {
        executor.submit(() -> database.query(sql)); // 数据库被冲垮！
    }
}

// ✅ 用 Semaphore 控制并发
Semaphore dbPermits = new Semaphore(50);
try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
    for (int i = 0; i < 1_000_000; i++) {
        executor.submit(() -> {
            dbPermits.acquire();
            try { database.query(sql); }
            finally { dbPermits.release(); }
        });
    }
}
```

### 23.7 框架集成

**Spring Boot 3.2+：**
```java
@Configuration
public class VirtualThreadConfig {
    @Bean
    public TomcatProtocolHandlerCustomizer<?> protocolHandlerCustomizer() {
        return handler -> handler.setExecutor(
            Executors.newVirtualThreadPerTaskExecutor()
        );
    }

    @Bean("taskExecutor")
    public Executor taskExecutor() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }
}
```

**或使用配置属性（Spring Boot 3.4+）：**
```properties
spring.threads.virtual.enabled=true
```

**Quarkus：**
Quarkus 正在探索从 JDK 24+ 开始默认启用虚拟线程（GitHub Issue #51031），等待第三方库中 ThreadLocal 使用的全面适配。

---

## 二十四、总结与最佳实践

### 24.1 ForkJoinPool 最佳实践

1. **保持 commonPool 纯净**：只用于 CPU 密集计算，不执行阻塞操作
2. **阻塞任务用 ManagedBlocker**：如果必须在 FJP 中阻塞，使用 `ManagedBlocker` 接口
3. **创建专用 FJP**：通过自定义 `ForkJoinPool` 实例隔离不同的工作负载
4. **选择合适的 asyncMode**：递归分治用默认 LIFO，事件驱动用 FIFO
5. **监控窃取比率**：`stealCount / totalTaskCount` 过高则增大任务粒度
6. **容器化环境修正并行度**：使用 `-XX:ActiveProcessorCount` 或依赖 `UseContainerSupport`
7. **切勿依赖 commonPool 做 I/O**：I/O 密集型统一用 `ThreadPoolExecutor`

### 24.2 虚拟线程最佳实践

1. **不要池化虚拟线程**：每个任务创建一个，用完即弃
2. **用 Semaphore 替代线程池限流**：控制对下游资源的并发访问
3. **替换 ThreadLocal 为 ScopedValue**：JDK 25+ 使用 ScopedValue 彻底解决内存膨胀
4. **升级到 JDK 24+**：获取 synchronized 钉住修复，消除最大障碍
5. **I/O 密集用虚拟线程，CPU 密集用 FJP**：各取所长，不要混用
6. **监控钉住事件**：生产环境使用 JFR `jdk.VirtualThreadPinned` 事件
7. **结构化并发组织子任务**：用 `StructuredTaskScope` 替代手动 future 管理

### 24.3 技术选型决策矩阵

| 你的场景 | 推荐方案 | 理由 |
|----------|----------|------|
| CPU 密集递归计算 | `ForkJoinPool`（自定义实例） | 工作窃取最大化 CPU 利用率 |
| CPU 密集数据并行 | `parallelStream()`（包装在自定义 FJP 中） | 语法简洁 + 隔离保护 |
| I/O 密集高并发（JDK 21+） | `Executors.newVirtualThreadPerTaskExecutor()` + `Semaphore` | 轻量级、高性能、代码简洁 |
| I/O 密集高并发（JDK <21） | `ThreadPoolExecutor`（自定义参数） | 唯一可用选项 |
| 需要背压和限流 | `ThreadPoolExecutor` + 有界队列 | 拒绝策略 + 队列大小精确控制 |
| 需要定时调度 | `ScheduledThreadPoolExecutor` | ForkJoinPool 不支持定时 |
| 旧系统迁移（JDK 21+） | 渐进式迁移到虚拟线程 | 从 Web 层开始，逐步替换 |
| 混合 CPU + I/O | 分离为两个池：FJP（CPU）+ 虚拟线程（I/O） | 避免互相干扰 |

### 24.4 演进路线图

```
当前                                  近期                          未来
─────                                  ─────                        ─────
JDK 8/11/17                      JDK 21 LTS                     JDK 25 LTS
平台线程 + ThreadPoolExecutor    虚拟线程正式可用                  ScopedValue 正式
CompletableFuture 异步编排        Structured Concurrency 预览     结构化并发仍预览
ForkJoinPool commonPool 共享      Scoped Values 预览
─────────────────────────────────────────────────────────────────────────────
建议路径: 升级到 JDK 21 → 启用虚拟线程 → 添加 Semaphore 限流 →
         替换 ThreadLocal → 尝试结构化并发 → 等待 JDK 25+ 全面就绪
```

---

## 参考资料

- [JEP 444: Virtual Threads](https://openjdk.org/jeps/444) — JDK 21 正式特性
- [JEP 491: Synchronize Virtual Threads without Pinning](https://openjdk.org/jeps/491) — JDK 24 修复
- [JEP 453: Structured Concurrency (Preview)](https://openjdk.org/jeps/453) — JDK 21 预览
- [JEP 505: Structured Concurrency (Preview)](https://openjdk.org/jeps/505) — JDK 25 预览
- [JEP 446: Scoped Values (Preview)](https://openjdk.org/jeps/446) — JDK 21 预览
- [JEP 506: Scoped Values](https://openjdk.org/jeps/506) — JDK 25 正式
- [ForkJoinPool Java 24 API](https://docs.oracle.com/en/java/javase/24/docs/api/java.base/java/util/concurrent/ForkJoinPool.html)
- [JDK-8336707: Contention of ForkJoinPool grows when stealing works](https://mail.openjdk.org/pipermail/core-libs-dev/2024-October/131837.html)
- [ForkJoinPool Side Effects (core-libs-dev, Jan 2024)](https://mail.openjdk.org/pipermail/core-libs-dev/2024-January/118181.html)
- [JDK-8322732: ForkJoinPool may underutilize cores in async mode](https://github.com/openjdk/jdk/pull/19131)
- [Virtual Threads, Structured Concurrency, and Scoped Values (JCON 2025)](https://horstmann.com/presentations/2025/jcon-loom/)
- [Java Performance Tuning Tips — Feb 2025](https://www.javaperformancetuning.com/news/newtips291.shtml)
- [Benchmarking the Performance of Java Virtual Threads (NCI 2024)](https://norma.ncirl.ie/8134/)
- [Virtual Threads vs Platform Threads in Java 23 (yCrash)](https://blog.ycrash.io/an-investigative-study-virtual-threads-vs-platform-threads-in-java-23/)
- [Project Loom in Production (OpenSourceSoftwareNews)](https://www.opensourcesoftwarenews.com/redirect/8476/)
- [自动迁移平台线程到虚拟线程（阿尔伯塔大学 2024）](https://ualberta.scholaris.ca/items/a4dd7d4c-7d66-4c0c-865c-6706a1a2f7a2/full)
