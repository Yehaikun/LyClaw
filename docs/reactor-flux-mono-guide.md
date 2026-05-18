# Project Reactor 响应式编程完全指南

> 面向零基础读者，从 Lambda 表达式讲起，逐步深入到 Flux/Mono/SSE 实战。
> 每一个概念都会解释"是什么、为什么、怎么用"，每个方法都有代码示例和运行结果。
> 每章末尾附有练习题，答案含详细解析与运行结果，学完即练，加深理解。

---

## 目录

- [第0章 先导知识：Lambda 表达式与函数式接口](#第0章-先导知识lambda-表达式与函数式接口)
  - [0.6 练习题](#06-练习题)
- [第1章 先导知识：CompletableFuture 异步编程](#第1章-先导知识completablefuture-异步编程)
  - [1.6 练习题](#16-练习题)
- [第2章 响应式编程思想](#第2章-响应式编程思想)
  - [2.5 练习题](#25-练习题)
- [第3章 Reactor 核心原理（深入底层）](#第3章-reactor-核心原理深入底层)
  - [3.8 练习题](#38-练习题)
- [第4章 Mono：0或1个元素的异步序列](#第4章-mono0或1个元素的异步序列)
  - [4.5 练习题](#45-练习题)
- [第5章 Flux：0到N个元素的异步序列](#第5章-flux0到n个元素的异步序列)
  - [5.5 练习题](#55-练习题)
- [第6章 核心操作符详解](#第6章-核心操作符详解)
  - [6.1 副作用操作符（doOn...）](#61-副作用操作符doon)
  - [6.2 错误恢复操作符](#62-错误恢复操作符)
  - [6.3 调度与线程切换](#63-调度与线程切换)
  - [6.4 条件操作符](#64-条件操作符)
  - [6.5 背压操作符](#65-背压操作符)
  - [6.6 冷热发布者与共享](#66-冷热发布者与共享)
  - [6.7 信号级操作与度量](#67-信号级操作与度量)
  - [6.8 练习题](#68-练习题)
- [第7章 SSE (Server-Sent Events) 实战](#第7章-sse-server-sent-events-实战)
  - [7.8 练习题](#78-练习题)
- [第8章 Schedulers 调度器与线程模型](#第8章-schedulers-调度器与线程模型)
  - [8.4 练习题](#84-练习题)
- [第9章 错误处理机制](#第9章-错误处理机制)
  - [9.5 练习题](#95-练习题)
- [第10章 项目实战案例拆解](#第10章-项目实战案例拆解)
  - [10.7 练习题](#107-练习题)
- [附录](#附录)
  - [附录自测题](#附录练习-附录自测题)
- [第11章 测试中的 Reactor 模式](#第11章-测试中的-reactor-模式)
  - [11.8 练习题](#118-练习题)
- [第12章 深入 SSE：从后端到前端的完整数据流](#第12章-深入-sse从后端到前端的完整数据流)
  - [12.4 练习题](#124-练习题)
- [第13章 调试与排错指南](#第13章-调试与排错指南)
  - [13.4 练习题](#134-练习题)
- [第14章 与其他技术的对比与桥接](#第14章-与其他技术的对比与桥接)
  - [14.4 练习题](#144-练习题)

---

## 第0章 先导知识：Lambda 表达式与函数式接口

在学 Flux/Mono 之前，必须先理解 Lambda 表达式。因为 Reactor 的几乎所有操作符
（`.map()`、`.flatMap()`、`.handle()` 等）都接受 Lambda 作为参数。

### 0.1 问题：为什么需要 Lambda？

在 Java 8 之前，如果你想传递"一段行为"给别人，必须写匿名内部类：

```java
// Java 7 写法：匿名内部类
Runnable task = new Runnable() {
    @Override
    public void run() {
        System.out.println("执行任务");
    }
};
new Thread(task).start();
```

5 行代码只为了说一句话 "System.out.println"。Lambda 把这件事压缩成 1 行：

```java
// Java 8 Lambda 写法
new Thread(() -> System.out.println("执行任务")).start();
```

### 0.2 Lambda 语法结构

Lambda 表达式的完整语法：

```
(参数类型1 参数名1, 参数类型2 参数名2) -> { 函数体 }
```

但 Java 可以根据上下文推断类型，所以可以逐步省略：

```java
// 最完整写法
(String s) -> { return s.length(); }

// 省略参数类型（Java 能自动推断 s 是 String）
(s) -> { return s.length(); }

// 只有一个参数时，可以省略小括号
s -> { return s.length(); }

// 函数体只有一行 return 语句时，可以省略 return 和花括号
s -> s.length()

// 无参数时，必须保留小括号
() -> System.out.println("Hello")
```

**实际代码示例：**

```java
// 示例1：接收一个参数，返回处理结果
// 完整写法
Function<String, Integer> fn1 = (String s) -> { return s.length(); };
// 最简写法
Function<String, Integer> fn2 = s -> s.length();
System.out.println(fn2.apply("Hello"));  // 输出: 5

// 示例2：接收两个参数
BinaryOperator<Integer> add = (a, b) -> a + b;
System.out.println(add.apply(3, 5));  // 输出: 8

// 示例3：无参数
Supplier<Double> random = () -> Math.random();
System.out.println(random.get());  // 输出: 0.723...（随机值）

// 示例4：多行函数体
Consumer<String> printWithBanner = s -> {
    System.out.println("===== 开始 =====");
    System.out.println(s);
    System.out.println("===== 结束 =====");
};
printWithBanner.accept("重要消息");
// 输出:
// ===== 开始 =====
// 重要消息
// ===== 结束 =====
```

### 0.3 方法引用（双冒号 ::）

当你 Lambda 只是调用一个已存在的方法时，可以写成方法引用，更简洁：

```java
// Lambda 写法
Consumer<String> print1 = s -> System.out.println(s);

// 方法引用写法（完全等价）
Consumer<String> print2 = System.out::println;

// 使用
print2.accept("Hello");  // 输出: Hello
```

三种常见的方法引用形式：

```java
// 1. 静态方法引用：类名::静态方法名
Function<String, Integer> parser = Integer::parseInt;
// 等价于: s -> Integer.parseInt(s)
System.out.println(parser.apply("42"));  // 输出: 42

// 2. 实例方法引用（参数对象的方法）：类名::实例方法名
Function<String, Integer> lengthGetter = String::length;
// 等价于: s -> s.length()
System.out.println(lengthGetter.apply("abc"));  // 输出: 3

// 3. 对象方法引用：对象变量::方法名
String prefix = "MSG: ";
Function<String, String> withPrefix = prefix::concat;
// 等价于: s -> prefix.concat(s)
System.out.println(withPrefix.apply("hello"));  // 输出: MSG: hello
```

### 0.4 函数式接口

函数式接口是**只有一个抽象方法**的接口。Lambda 表达式只能赋值给函数式接口类型。

Java 提供的最常用的四大函数式接口：

| 接口名 | 输入 | 输出 | 方法签名 | 用途 |
|-------|------|------|---------|------|
| `Function<T,R>` | T | R | `R apply(T t)` | 转换/映射 |
| `Consumer<T>` | T | 无(void) | `void accept(T t)` | 消费/处理 |
| `Supplier<T>` | 无 | T | `T get()` | 提供/生成 |
| `Predicate<T>` | T | boolean | `boolean test(T t)` | 判断/过滤 |

```java
// Function<T, R>：接收 T 类型，返回 R 类型
Function<String, Integer> strLength = s -> s.length();
Integer len = strLength.apply("abc");  // len = 3

// Consumer<T>：接收 T 类型，不返回（执行副作用）
Consumer<String> printer = s -> System.out.println("打印: " + s);
printer.accept("你好");  // 控制台输出: 打印: 你好

// Supplier<T>：不接收参数，返回 T 类型
Supplier<Long> currentTime = () -> System.currentTimeMillis();
long time = currentTime.get();  // time = 1715840123456（示例值）

// Predicate<T>：接收 T 类型，返回 boolean
Predicate<Integer> isPositive = n -> n > 0;
boolean result = isPositive.test(5);  // result = true
```

### 0.5 为什么 Reactor 大量使用 Lambda

Reactor 的操作符如 `.map()`、`.filter()`、`.handle()` 内部接收的都是函数式接口：

```java
Flux<Integer> numbers = Flux.just(1, 2, 3, 4, 5);

// .map() 接收 Function<T,R> → 把每个元素转换为另一个元素
numbers.map(n -> n * 10)        // n -> n * 10 就是 Function<Integer,Integer>
       .subscribe(System.out::println);  // System.out::println 就是 Consumer<Integer>
// 输出: 10 20 30 40 50

// .filter() 接收 Predicate<T> → 保留满足条件的元素
numbers.filter(n -> n > 3)      // n -> n > 3 就是 Predicate<Integer>
       .subscribe(System.out::println);
// 输出: 4 5
```

**关键理解**：Lambda 让你把"要做什么"写成参数传递进去，而不是写一个完整的类。
这就是为什么 Reactor 代码看起来简洁——大量的 `x -> doSomething(x)` 本质上都是在
传递行为。

### 0.6 练习题

**题目1**：将下面的匿名内部类改写为 Lambda 表达式（最简形式）。

```java
List<String> names = Arrays.asList("Alice", "Bob", "Charlie");
Collections.sort(names, new Comparator<String>() {
    @Override
    public int compare(String o1, String o2) {
        return o1.length() - o2.length();
    }
});
```

**题目2**：下面的 Lambda 分别实现了哪个函数式接口？写出接口名和抽象方法名。

```java
A. x -> x > 10
B. () -> new ArrayList<String>()
C. (a, b) -> System.out.println(a + b)
D. str -> str.toUpperCase()
```

**题目3**：将下面的 Lambda 改写为等效的方法引用。

```java
A. s -> s.trim()
B. (x, y) -> Math.max(x, y)
C. user -> user.getName()
D. () -> new Object()
```

<details>
<summary>点击查看答案与解析</summary>

**题目1答案：**

```java
List<String> names = Arrays.asList("Alice", "Bob", "Charlie");
Collections.sort(names, (o1, o2) -> o1.length() - o2.length());
System.out.println(names);
// 运行结果: [Bob, Alice, Charlie]
```

**解析**：`Comparator<String>` 是函数式接口，唯一的抽象方法是 `int compare(String, String)`。Lambda `(o1, o2) -> o1.length() - o2.length()` 接收两个 String、返回 int，编译器自动匹配到这个方法。可以省略参数类型（编译器从泛型 `List<String>` 推断 o1/o2 都是 String），函数体只有一行 return 时可以省略 return 和花括号。

**题目2答案：**

| Lambda | 函数式接口 | 抽象方法 |
|--------|-----------|---------|
| A. `x -> x > 10` | `Predicate<Integer>` | `boolean test(T t)` |
| B. `() -> new ArrayList<String>()` | `Supplier<ArrayList<String>>` | `T get()` |
| C. `(a, b) -> System.out.println(a + b)` | `BiConsumer<Integer, Integer>` | `void accept(T t, U u)` |
| D. `str -> str.toUpperCase()` | `Function<String, String>` | `R apply(T t)` |

**解析**：
- A 返回 boolean → `Predicate`（谓词，判断真/假）
- B 无参有返回值 → `Supplier`（供应者，生产数据）
- C 两个参数无返回值（`println` 返回 void）→ `BiConsumer`（双消费者）
- D 一个参数有返回值 → `Function`（函数，转换数据）

**题目3答案：**

```java
A. String::trim          // 实例方法引用（特定对象的任意方法）
B. Math::max             // 静态方法引用
C. User::getName         // 实例方法引用（特定对象的任意方法）
D. Object::new           // 构造方法引用
```

**运行验证：**

```java
// A
Function<String, String> f1 = String::trim;
System.out.println("[" + f1.apply("  hello  ") + "]");  // 输出: [hello]

// B
BinaryOperator<Integer> f2 = Math::max;
System.out.println(f2.apply(5, 9));  // 输出: 9

// C（假设 User 类有 getName() 方法）
Function<User, String> f3 = User::getName;
System.out.println(f3.apply(new User("Alice")));  // 输出: Alice

// D
Supplier<Object> f4 = Object::new;
System.out.println(f4.get());  // 输出: java.lang.Object@1a2b3c
```

**方法引用速记**：
- `类名::静态方法` → 静态方法引用（如 `Math::max`）
- `对象::实例方法` → 特定对象的方法引用（如 `System.out::println`）
- `类名::实例方法` → 第一个参数作为调用者的方法引用（如 `String::trim` 等价于 `s -> s.trim()`）
- `类名::new` → 构造方法引用（如 `Object::new` 等价于 `() -> new Object()`）

</details>

---

## 第1章 先导知识：CompletableFuture 异步编程

### 1.1 为什么需要异步

假设你要做一个耗时操作（比如调用远程 HTTP 接口），如果用同步方式：

```java
// 同步（阻塞）：主线程卡住等待
String httpResult = httpClient.get("http://api.example.com/data");  // 阻塞 2 秒
System.out.println(httpResult);  // 2 秒后才会执行这行
```

用户界面会卡死两秒。异步编程的目的：**发起请求后不等待，先去干别的事，结果到了再处理**。

### 1.2 CompletableFuture 基础

`CompletableFuture<T>` 是一个"未来某个时刻会产生 T 类型结果"的容器。

```java
import java.util.concurrent.CompletableFuture;

// 创建一个 Future
CompletableFuture<String> future = new CompletableFuture<>();

// 在另一个线程中，2 秒后完成它
new Thread(() -> {
    try {
        Thread.sleep(2000);
        future.complete("任务完成！");  // 设置结果
    } catch (Exception e) {
        future.completeExceptionally(e);  // 设置异常
    }
}).start();

// 主线程可以等待结果（阻塞等待，最多等 5 秒）
String result = future.get(5, TimeUnit.SECONDS);
System.out.println(result);  // 输出: 任务完成！
```

**CompletableFuture 的生命周期：**

```
创建（new） → 等待中（pending） → [complete("成功")] → 已完成（done，结果为"成功"）
                                 → [completeExceptionally(e)] → 异常完成
```

### 1.3 核心方法

#### `boolean complete(T value)` — 标记完成并设置结果

`complete` 立即将 CompletableFuture 标记为完成并设置结果值，任何等待该 Future 的线程会立刻被唤醒。首次调用返回 `true` 表示设置成功；重复调用返回 `false` 作幂等保护，结果保持为第一次设置的值。与 `completeExceptionally` 对应：`complete` 正常完成，`completeExceptionally` 以异常完成。

```java
CompletableFuture<String> future = new CompletableFuture<>();
boolean success = future.complete("结果值");
System.out.println(success);  // true（首次 complete 返回 true）

// 重复完成无效
boolean success2 = future.complete("另一个值");
System.out.println(success2);  // false（已经完成过了，幂等保护）
System.out.println(future.get());  // "结果值"（仍然是第一次的值）
```

#### `T get(long timeout, TimeUnit unit)` — 阻塞等待结果

`get(timeout, unit)` 阻塞当前线程等待 Future 完成并返回结果，最多等待指定的时间。超时会抛出 `TimeoutException`，执行异常会抛出 `ExecutionException`（受检异常，必须 try-catch）。与 `join()` 的区别：`get()` 抛出受检异常需要显式捕获，`join()` 抛出非受检异常 `CompletionException` 适合 Lambda 内使用。

```java
CompletableFuture<String> future = new CompletableFuture<>();

new Thread(() -> {
    try { Thread.sleep(1000); } catch (Exception e) {}
    future.complete("异步结果");
}).start();

System.out.println("开始等待...");
String result = future.get(5, TimeUnit.SECONDS);  // 最多等 5 秒
System.out.println("收到: " + result);
// 输出:
// 开始等待...
// （等待约1秒）
// 收到: 异步结果
```

如果超时未完成，抛出 `TimeoutException`：

```java
String result = future.get(1, TimeUnit.SECONDS);
// 如果 1 秒内 future 未完成 → 抛出 java.util.concurrent.TimeoutException
```

#### `static <U> CompletableFuture<U> supplyAsync(Supplier<U> supplier)` — 异步执行任务

`supplyAsync` 在公共 ForkJoinPool 中异步执行一个有返回值的任务（Supplier），返回 `CompletableFuture<U>` 用于获取异步计算结果。与 `runAsync` 的区别：`supplyAsync` 接收 `Supplier<U>`（有返回值），`runAsync` 接收 `Runnable`（无返回值）。适合异步计算密集型任务（如远程调用、数据库查询等），在线程池资源有限时可传入自定义 Executor。

```java
// 在 ForkJoinPool 的公共线程池中异步执行任务
CompletableFuture<Integer> future = CompletableFuture.supplyAsync(() -> {
    // 这里是耗时操作，在另一个线程中执行
    System.out.println("执行线程: " + Thread.currentThread().getName());
    return 42;  // 返回结果
});

Integer result = future.get();  // 阻塞等待
System.out.println(result);  // 42
```

#### `boolean isDone()` — 检查是否已完成

`isDone()` 非阻塞地检查 CompletableFuture 是否已完成（正常完成或异常完成均返回 `true`）。常用于轮询场景，配合 `getNow()` 可实现非阻塞的结果获取。注意该方法不区分完成方式是正常还是异常，只关心是否结束；如需区分，使用 `isCompletedExceptionally()`。

```java
CompletableFuture<String> future = new CompletableFuture<>();
System.out.println(future.isDone());  // false

future.complete("done");
System.out.println(future.isDone());  // true
```

#### `static CompletableFuture<Void> runAsync(Runnable runnable)` — 异步执行无返回值任务

`runAsync` 在公共 ForkJoinPool 中异步执行一个无返回值的任务（Runnable），返回 `CompletableFuture<Void>`。与 `supplyAsync` 的区别：`runAsync` 接收 `Runnable`（无返回值），`supplyAsync` 接收 `Supplier<U>`（有返回值）。适合触发异步副作用（写日志、发通知、清理缓存等），不需要关心执行结果。

```java
CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
    System.out.println("后台任务执行中: " + Thread.currentThread().getName());
    // 耗时操作，例如写日志、发通知
});
future.join();  // 等待任务完成
// 输出: 后台任务执行中: ForkJoinPool.commonPool-worker-1

// supplyAsync vs runAsync:
// supplyAsync: 有返回值，Supplier<U>，返回 CompletableFuture<U>
// runAsync:    无返回值，Runnable，返回 CompletableFuture<Void>
```

#### `CompletableFuture<R> .thenApply(Function<T,R>)` / `.thenApplyAsync(Function<T,R>)` — 同步转换结果

`thenApply` 将前一步的结果同步转换为新值，返回 `CompletableFuture<R>`，在 Reactor 中对应 `map` 操作符。`thenApply` 复用前一步的线程（除非前一步未完成），`thenApplyAsync` 则强制切换到公共 ForkJoinPool 线程池执行。与 `thenCompose` 的区别：`thenApply` 做同步转换不展平 Future；当下一步调用本身返回 `CompletableFuture` 时必须改用 `thenCompose`。

```java
CompletableFuture<String> future = CompletableFuture
    .supplyAsync(() -> {
        System.out.println("Step1: " + Thread.currentThread().getName());
        return 100;
    })
    .thenApply(score -> {
        System.out.println("Step2: " + Thread.currentThread().getName());
        return "分数: " + score;  // Integer → String 转换
    });
// thenApply: Step1 和 Step2 通常在同一线程（除非前一步未完成）

System.out.println(future.join());  // 输出: 分数: 100

// thenApplyAsync: 强制在新线程执行，不阻塞前一步线程
.thenApplyAsync(score -> {
    System.out.println("Async: " + Thread.currentThread().getName());
    return "分数: " + score;
});
```

#### `CompletableFuture<Void> .thenAccept(Consumer<T>)` / `.thenAcceptAsync(Consumer<T>)` — 消费结果（不返回）

`thenAccept` 消费前一步的结果但不返回新值，返回 `CompletableFuture<Void>`。与 `thenApply` 的区别：`thenAccept` 只消费不转换（类似 `forEach`），链的末端使用；`thenApply` 转换结果返回新值，链的中间使用。与 `thenRun` 的区别：`thenAccept` 接收前一步结果，`thenRun` 完全不关心结果。

```java
CompletableFuture.supplyAsync(() -> "数据")
    .thenApply(String::toUpperCase)       // 转换: "数据" → "数据"
    .thenAccept(result ->                 // 消费: 打印或存储
        System.out.println("最终结果: " + result)
    );
// 输出: 最终结果: 数据
```

#### `CompletableFuture<Void> .thenRun(Runnable)` / `.thenRunAsync(Runnable)` — 完成后执行动作（不关心结果）

`thenRun` 在前一步完成后执行一个 Runnable，不接收前一步结果也不产生新结果，返回 `CompletableFuture<Void>`。与 `thenAccept` 的区别：`thenRun` 完全不关心前一步结果，只在前一步完成后触发动作；`thenAccept` 接收并消费前一步结果。适合完成后触发清理、通知等不需要结果的副作用。

```java
CompletableFuture.supplyAsync(() -> "重要数据")
    .thenRun(() -> System.out.println("前一步完成（数据被忽略）"));
// 输出: 前一步完成（数据被忽略）
```

#### `CompletableFuture<R> .thenCompose(Function<T, CompletableFuture<R>>)` — 异步链式调用（flatMap 对应）

`thenCompose` 接收前一步结果并返回一个新的 `CompletableFuture`，自动"展平"嵌套的 Future，避免出现 `CompletableFuture<CompletableFuture<T>>` 的嵌套结构。在 Reactor 中对应 `flatMap` 操作符。与 `thenApply` 的核心区别：当第二个调用本身返回 `CompletableFuture` 时，必须用 `thenCompose`；用 `thenApply` 会得到嵌套 Future，需要手动 `join()` 展开。

```java
// 典型场景：第二个异步调用依赖第一个的结果
// getUserName(userId) 返回 CompletableFuture<String>
// getOrders(name)      返回 CompletableFuture<List<String>>

CompletableFuture<List<String>> orders = getUserName(1)
    .thenCompose(name -> getOrders(name));  // correct: 自动展平
// 不能用 thenApply — 会得到嵌套的 CompletableFuture<CompletableFuture<List<String>>>

// 对比: thenApply（错误方式）
CompletableFuture<CompletableFuture<List<String>>> nested =
    getUserName(1).thenApply(name -> getOrders(name));
// thenApply 不会展平嵌套的 CompletableFuture → 需要 .join() 展开 → 手动阻塞
```

#### `CompletableFuture<R> .thenCombine(CompletableFuture<U>, BiFunction<T,U,R>)` — 合并两个独立异步结果

`thenCombine` 将两个并行执行的 Future 结果合并，两者都完成后用 BiFunction 得到最终结果。总耗时等于两者中较长的那个（max），而非两者之和（sum），因此性能优于串行调用。适合需要同时获取多个独立异步数据源的场景（如同时查询价格和汇率，或并行请求两个微服务）。

```java
CompletableFuture<Integer> price = CompletableFuture.supplyAsync(() -> {
    sleep(1000); return 50;  // 模拟慢查询
});
CompletableFuture<Double> rate = CompletableFuture.supplyAsync(() -> {
    sleep(500); return 6.8;  // 模拟快查询
});

// 两个查询并行执行，总耗时 ≈ 1000ms（不是 1500ms）
CompletableFuture<String> result = price.thenCombine(rate,
    (p, r) -> String.format("价格%d, 汇率%.1f, 折合%.1f人民币", p, r, p * r));
System.out.println(result.join());
// 输出: 价格50, 汇率6.8, 折合340.0人民币
```

#### `CompletableFuture<T> .exceptionally(Function<Throwable, T>)` — 异常恢复

`exceptionally` 在 Future 出现异常时提供降级值，将异常完成转为正常完成，避免异常传播到最终调用者。正常完成时函数不被调用，结果原样传递。与 `handle` 的区别：`exceptionally` 只处理失败路径（单一参数）；`handle` 通过 `(result, error)` 双参数同时处理成功和失败，且可转换正常结果。

```java
CompletableFuture<String> future = CompletableFuture
    .supplyAsync(() -> {
        if (Math.random() > 0.3) throw new RuntimeException("API调用失败");
        return "正常数据";
    })
    .exceptionally(ex -> "降级数据: " + ex.getMessage());
// 异常时输出: 降级数据: java.lang.RuntimeException: API调用失败

System.out.println(future.join());
```

#### `CompletableFuture<R> .handle(BiFunction<T, Throwable, R>)` — 无论成功或失败都处理

`handle` 无论成功或失败都会执行回调，接收 `(result, error)` 双参数（成功时 error 为 null，失败时 result 为 null），可返回新值改变管线结果。与 `exceptionally` 的区别：`handle` 成功和失败路径都能处理并转换结果；`exceptionally` 只在异常时提供降级值，正常路径不被调用。

```java
CompletableFuture<String> future = CompletableFuture
    .supplyAsync(() -> { throw new RuntimeException("错误"); })
    .handle((result, error) -> {
        if (error != null) return "失败: " + error.getMessage();
        return "成功: " + result;
    });
System.out.println(future.join());  // 输出: 失败: java.lang.RuntimeException: 错误
```

#### `CompletableFuture<T> .whenComplete(BiConsumer<T, Throwable>)` — 完成时回调（不改变结果）

`whenComplete` 在 Future 完成时执行回调（成功或失败都会触发），但不改变管线中的结果值，仅用于副作用（日志记录、资源清理、统计上报等）。回调接收 `(result, error)` 双参数，不返回值因此不会影响下游。与 `handle` 的区别：`whenComplete` 做只读回调不改变结果；`handle` 可以转换结果值。

```java
CompletableFuture.supplyAsync(() -> "任务结果")
    .whenComplete((result, error) -> {
        if (error != null) log.error("失败", error);
        else log.info("成功: {}", result);
    });
// 结果仍然是 "任务结果"，whenComplete 不会改变它
```

#### `static CompletableFuture<Void> allOf(CompletableFuture<?>... cfs)` — 等待全部完成

`allOf` 等待所有传入的 Future 全部完成后才完成，返回 `CompletableFuture<Void>`（不包含各 Future 的结果值）。总耗时等于最慢的那个（max），适合需要汇集多个独立异步结果再继续的场景。与 `anyOf` 的区别：前者等全部完成，后者任一完成即返回；完成后需逐个调用 `get/join` 获取各 Future 的结果。

```java
CompletableFuture<String> f1 = CompletableFuture.supplyAsync(() -> "A");
CompletableFuture<String> f2 = CompletableFuture.supplyAsync(() -> "B");
CompletableFuture<String> f3 = CompletableFuture.supplyAsync(() -> "C");

CompletableFuture<Void> all = CompletableFuture.allOf(f1, f2, f3);
all.join();  // 等待三个全部完成
// 之后可以安全获取结果（get/join 立即返回，因为已完成）
System.out.println(f1.join() + f2.join() + f3.join());  // ABC

// 更简洁的写法: 收集所有结果到 List
List<String> results = Stream.of(f1, f2, f3)
    .map(CompletableFuture::join)
    .collect(Collectors.toList());
```

#### `static CompletableFuture<Object> anyOf(CompletableFuture<?>... cfs)` — 任一完成即返回

`anyOf` 等待任一 Future 完成后即返回，返回该 Future 的结果（类型为 `Object`，需要强制转型）。总耗时等于最快的那个，适合竞速场景（如从多个数据源取最快返回的结果）。与 `allOf` 的区别：前者任一完成即返回其余被忽略，后者等全部完成；两者总耗时分别为 min 和 max。

```java
CompletableFuture<String> fast = CompletableFuture.supplyAsync(() -> {
    sleep(100); return "快速结果";
});
CompletableFuture<String> slow = CompletableFuture.supplyAsync(() -> {
    sleep(5000); return "慢速结果";
});

Object first = CompletableFuture.anyOf(fast, slow).join();
System.out.println(first);  // 输出: 快速结果（100ms后返回，不等慢的）
```

#### `T join()` — 阻塞获取结果（不抛受检异常）

`join()` 阻塞等待 Future 完成并返回结果，抛出的异常是 `CompletionException`（非受检异常），无需 try-catch。与 `get()` 的核心区别：`get()` 抛出受检异常（`ExecutionException`、`InterruptedException`），Lambda 中不能用；`join()` 抛非受检异常，Lambda 内部使用更便捷，因此生产代码中 Lambda 内推荐使用 `join()`。

```java
// get() 需要 try-catch
try {
    String result = future.get(5, TimeUnit.SECONDS);
} catch (TimeoutException | ExecutionException | InterruptedException e) { ... }

// join() 不需要 try-catch（异常为 CompletionException，非受检）
String result = future.join();
// 生产代码中 lambda 内部常用 join()，因为 Lambda 不能抛受检异常
```

#### `boolean completeExceptionally(Throwable ex)` — 以异常完成

`completeExceptionally` 以指定的异常完成 Future，之后任何 `get/join` 调用都会抛出异常。与 `complete(value)` 对应：前者异常完成，后者正常完成。常用于手动将 Future 标记为失败状态，下游链路可通过 `exceptionally` 或 `handle` 捕获异常并进行降级处理。

```java
CompletableFuture<String> future = new CompletableFuture<>();
future.completeExceptionally(new RuntimeException("超时未响应"));

// 之后 get/join 会抛 ExecutionException/CompletionException
future.exceptionally(ex -> "降级").thenAccept(System.out::println);
// 输出: 降级
```

#### `T getNow(T valueIfAbsent)` — 非阻塞获取（未完成给默认值）

`getNow` 非阻塞地获取 Future 结果：已完成则返回结果值，未完成则立即返回指定的默认值不等待。与 `get/join` 的区别：`getNow` 永不阻塞立即返回，而 `get/join` 会阻塞等待完成。适合"有就用，没有就默认"的降级场景，避免线程阻塞。

```java
CompletableFuture<String> future = CompletableFuture.supplyAsync(() -> {
    sleep(5000); return "最终值";
});
System.out.println(future.getNow("还未完成"));  // 输出: 还未完成（不等待）
Thread.sleep(6000);
System.out.println(future.getNow("还未完成"));  // 输出: 最终值
```

#### `CompletableFuture<T> orTimeout(long, TimeUnit)` / `completeOnTimeout(T, long, TimeUnit)` — 超时控制

`orTimeout` 和 `completeOnTimeout` 为 Future 添加超时控制，是等待外部系统响应时的常用防护手段。`orTimeout` 超时后以 `TimeoutException` 异常完成，配合 `exceptionally` 可提供降级值；`completeOnTimeout` 超时后直接以指定默认值正常完成，更为简洁。两者均返回 `CompletableFuture<T>`，适合防止长时间等待的场景。

```java
// orTimeout: 超时后以 TimeoutException 异常完成
CompletableFuture.supplyAsync(() -> { sleep(10000); return "慢"; })
    .orTimeout(2, TimeUnit.SECONDS)
    .exceptionally(ex -> "超时了");

// completeOnTimeout: 超时后以默认值正常完成
CompletableFuture.supplyAsync(() -> { sleep(10000); return "慢"; })
    .completeOnTimeout("超时默认值", 2, TimeUnit.SECONDS)
    .thenAccept(System.out::println);
// 2秒后输出: 超时默认值
```

#### `static <U> CompletableFuture<U> failedFuture(Throwable ex)` — 创建已失败的 Future

`failedFuture` 创建一个已经以异常完成的 Future，等价于 Reactor 的 `Mono.error`。适合在方法需要返回 `CompletableFuture` 但当前已知失败时使用，避免先创建空 Future 再调用 `completeExceptionally` 的繁琐写法。与 `completedFuture` 对应：前者返回已失败的 Future，后者返回已正常完成的 Future。

```java
CompletableFuture<String> failed = CompletableFuture.failedFuture(
    new RuntimeException("预设失败"));
System.out.println(failed.isCompletedExceptionally());  // true
```

#### `thenCompose` vs `thenApply` 速查

| | thenApply (map) | thenCompose (flatMap) |
|---|---|---|
| 函数签名 | `Function<T, R>` | `Function<T, CompletionStage<R>>` |
| 返回类型 | `CompletableFuture<R>` | `CompletableFuture<R>`（自动展平） |
| 适用场景 | 同步转换 | 异步链接（下一个也是异步调用） |
| Reactor 对应 | `.map()` | `.flatMap()` |

### 1.4 CompletableFuture 在 LyClaw 中的实际用法

**场景：用户审批工具执行（ApprovalStore.java）**

```java
// 创建"待审批"状态
CompletableFuture<Boolean> future = new CompletableFuture<>();
pending.put(toolCallId, future);  // 存入 Map

// 超时自动拒绝（60秒后）
CompletableFuture.delayedExecutor(60, TimeUnit.SECONDS).execute(() -> {
    // 60 秒后执行这个任务
    CompletableFuture<Boolean> f = pending.remove(toolCallId);
    if (f != null) {
        f.complete(false);  // 自动设置为"拒绝"
    }
});

// 用户点击"允许"时
public boolean approve(String toolCallId) {
    CompletableFuture<Boolean> future = pending.remove(toolCallId);
    if (future != null && !future.isDone()) {
        return future.complete(true);  // complete(true) 表示允许
    }
    return false;  // 已经完成过了（幂等保护）
}

// ReAct 引擎中等待审批结果
Boolean approved = future.get(60, TimeUnit.SECONDS);
// 用户点击允许 → approved = true
// 用户点击拒绝 → approved = false
// 60秒无操作 → 超时异常 → catch 中设为 false
```

### 1.5 CompletableFuture 与 Mono 的桥接

#### `static <T> Mono<T> Mono.fromFuture(CompletableFuture<? extends T> future)` — 从 Future 桥接到 Mono

- **参数**：`CompletableFuture<? extends T>` — 已有的 Future 对象
- **返回值**：`Mono<T>` — 当 Future 完成后，Mono 发射其结果；若 Future 异常完成，Mono 以 error 信号终止

```java
CompletableFuture<String> future = CompletableFuture.supplyAsync(() -> "异步结果");

// 转换为 Mono
Mono<String> mono = Mono.fromFuture(future);

// 现在可以和其他 Reactor 流组合了
mono.subscribe(result -> System.out.println("收到: " + result));
// 输出: 收到: 异步结果
```

### 1.6 练习题

**题目1**：使用 `thenApply` 和 `thenCompose` 分别实现：获取用户名后，获取该用户的订单列表。已知两个异步方法：

```java
CompletableFuture<String> getUserName(int userId);       // 返回用户名
CompletableFuture<List<String>> getOrders(String name);  // 返回订单列表
```

请写出调用代码，并说明 `thenApply` 和 `thenCompose` 在此场景下的区别。

**题目2**：下面的代码输出是什么？为什么？

```java
CompletableFuture<String> f = CompletableFuture
    .supplyAsync(() -> {
        System.out.println("A");
        return "B";
    })
    .thenApply(s -> {
        System.out.println(s);
        return "C";
    })
    .thenApplyAsync(s -> {
        System.out.println(s);
        return "D";
    });
System.out.println("E");
f.join();
System.out.println("F");
```

**题目3**：用 `thenCombine` 将两个异步查询结果合并。一个查用户积分，一个查用户等级，最终拼接为字符串。

```java
CompletableFuture<Integer> points = CompletableFuture.supplyAsync(() -> 100);
CompletableFuture<String> level = CompletableFuture.supplyAsync(() -> "VIP");
// 请补充：合并为 "VIP用户，积分100"
```

<details>
<summary>点击查看答案与解析</summary>

**题目1答案：**

```java
// 使用 thenCompose（正确）
CompletableFuture<List<String>> orders = getUserName(1)
    .thenCompose(name -> getOrders(name));

// 使用 thenApply（错误——会得到嵌套的 CompletableFuture）
CompletableFuture<CompletableFuture<List<String>>> nested = getUserName(1)
    .thenApply(name -> getOrders(name));
// 类型变成了两层嵌套，需要 .join() 才能展开

// 使用 thenApply + join 展开（不推荐，手动阻塞）
CompletableFuture<List<String>> orders2 = getUserName(1)
    .thenApply(name -> getOrders(name).join());
```

**运行结果：**
```
// thenCompose 正常返回订单列表: [订单1, 订单2, 订单3]
// thenApply 返回 CompletableFuture 对象，不是订单列表
```

**解析**：
- `thenApply`: 接收 `Function<T, R>`，对前一步结果做**同步转换**。如果用 `thenApply(name -> getOrders(name))`，返回类型是 `CompletableFuture<CompletableFuture<List<String>>>`，因为 getOrders 本身就返回 CompletableFuture，thenApply 不会解包。
- `thenCompose`: 接收 `Function<T, CompletionStage<R>>`，对前一步结果做**异步链接**。它会自动"展平"嵌套的 CompletableFuture，最终得到 `CompletableFuture<List<String>>`。
- 记忆法则：`thenApply` = map（同步），`thenCompose` = flatMap（异步展平）。

**题目2答案：**

```
E
A
B
C
D
F
```

**解析**：
1. `supplyAsync` 在线程池中执行 → 打印 `A`，返回 `"B"`
2. `thenApply` 在同线程继续 → 打印 `"B"`，返回 `"C"`
3. `thenApplyAsync` 切换到新线程 → 打印 `"C"`，返回 `"D"`
4. 主线程不受影响：第3步异步执行的过程中，主线程先打印 `E`
5. `f.join()` 阻塞主线程等待 f 完成
6. 等待期间，f 的第三步在线程池中执行完成，打印 `C`
7. f 完成后，主线程继续，打印 `F`

关键理解：`thenApplyAsync` 不会阻塞主线程，所以 `E` 先于 `C` 和 `D` 打印。`thenApply` 也不阻塞主线程——整个流水线在后台线程池中运行，只有 `join()` 会阻塞主线程等待。

**题目3答案：**

```java
CompletableFuture<Integer> points = CompletableFuture.supplyAsync(() -> 100);
CompletableFuture<String> level = CompletableFuture.supplyAsync(() -> "VIP");

CompletableFuture<String> combined = points.thenCombine(level,
    (p, l) -> l + "用户，积分" + p);

System.out.println(combined.join());
// 运行结果: VIP用户，积分100
```

**解析**：`thenCombine` 签名：`<U,V> CompletableFuture<V> thenCombine(CompletionStage<U> other, BiFunction<T,U,V> fn)`。当两个 future 都完成后，用 BiFunction 合并结果。注意两个 future 是**并行执行**的（互不依赖），所以总耗时等于较慢的那个。

</details>

---

## 第2章 响应式编程思想

### 2.1 什么是响应式编程

传统编程（命令式）：
```
你主动"拉取"数据：List<String> data = database.query("SELECT * FROM users");
```

响应式编程：
```
你订阅一个数据源，数据来了自动"推送"给你：
database.reactiveQuery("SELECT * FROM users")
        .subscribe(user -> System.out.println("收到用户: " + user));
```

**核心区别：**

| | 命令式（Imperative） | 响应式（Reactive） |
|---|---|---|
| 数据获取方式 | 主动拉取（Pull） | 被动推送（Push） |
| 线程模型 | 调用线程阻塞等待 | 非阻塞，事件驱动 |
| 数据量 | 一批返回 | 逐条推送 |
| 代表 | `List<T>` | `Flux<T>` / `Mono<T>` |

### 2.2 为什么需要响应式：线程模型对比

**传统 Servlet 模型（每请求一线程）：**

```
请求1 → [线程1: 处理 → 查数据库(阻塞等待) → 返回] → 线程1空闲等待数据库
请求2 → [线程2: 处理 → 查数据库(阻塞等待) → 返回] → 线程2空闲等待数据库
...
请求100 → [线程100: 等待中...]

问题：100 个并发请求 = 100 个线程，大多数时间在"等待IO"，线程资源浪费严重。
```

**WebFlux 响应式模型（事件循环 + 少量线程）：**

```
请求1 → [IO线程: 发起数据库查询(立即返回)] → 继续处理请求2
请求2 → [IO线程: 发起数据库查询(立即返回)] → 继续处理请求3
...
（数据返回时）→ [IO线程: 收到查询结果 → 推送响应]

优势：少量线程处理大量并发，线程永不阻塞等待。
```

### 2.3 Project Reactor 是什么

Project Reactor 是 Spring WebFlux 的底层响应式库，提供两个核心类型：

- **Mono<T>**：0 或 1 个元素的异步序列（单个值或空）
- **Flux<T>**：0 到 N 个元素的异步序列（流）

**Mono vs Flux 的抉择：**

```
操作类型           → 使用类型
────────────────────────────────
查询单条记录        → Mono<User>
查询是否存在        → Mono<Boolean>
删除操作（无返回）   → Mono<Void>
查询多条记录        → Flux<User>
SSE 事件推送        → Flux<ServerSentEvent>
WebSocket 消息      → Flux<String>
```

### 2.4 订阅（Subscribe）的概念

Reactor 流是"惰性"的——**你不订阅，它就不执行**。这是一个关键的认知：

```java
// 这段代码不会打印任何东西！
Flux<Integer> numbers = Flux.just(1, 2, 3);
numbers.map(n -> {
    System.out.println("处理: " + n);
    return n * 10;
});
System.out.println("流水线已定义");
// 输出只有: 流水线已定义
// 原因: 没有订阅，流水线从未启动！
```

```java
// 加上 .subscribe() 后，流水线才会真正执行
Flux.just(1, 2, 3)
    .map(n -> {
        System.out.println("处理: " + n);
        return n * 10;
    })
    .subscribe();  // ← 这个 subscribe() 启动了整条流水线
// 输出:
// 处理: 1
// 处理: 2
// 处理: 3
```

**类比**：流水线定义 = 铺设水管，`.subscribe()` = 打开水龙头。不打开水龙头，水管里没有水流动。

### 2.5 练习题

**题目1**：描述响应式编程和传统命令式编程在"获取数据"这件事上的本质区别。用你自己的话解释"推模型"与"拉模型"。

**题目2**：下面的流有几个订阅者？

```java
Flux<Integer> flux = Flux.just(1, 2, 3)
    .map(x -> x * 10);
flux.subscribe(System.out::println);
flux.subscribe(x -> System.out.println("第二次: " + x));
```

这段代码会输出什么？每个元素被 map 处理了几次？

**题目3**：看下面的代码，输出是什么？

```java
Flux<String> pipe = Flux.just("A", "B", "C")
    .map(s -> {
        System.out.println("map: " + s);
        return s.toLowerCase();
    });
System.out.println("流水线已定义");
pipe.subscribe(s -> System.out.println("收到: " + s));
```

<details>
<summary>点击查看答案与解析</summary>

**题目1答案：**

**拉模型（命令式）**：程序主动索要数据。就像去餐厅点餐——你说"给我来一份炒饭"，厨房才做。你的代码调用 `list.get(0)` 时，数据被从集合中"拉"出来。

**推模型（响应式）**：你订阅数据源，数据源主动把数据推送给你。就像订阅微信公众号——你关注后，公众号每次发文你都能收到，不需要反复去问"发了吗"。

```java
// 拉模型：主动索要
List<String> data = database.query("SELECT * FROM users");  // 一次性全拉出来
String first = data.get(0);  // 手动取第一条

// 推模型：订阅等待推送
database.queryReactive("SELECT * FROM users")  // 返回 Flux<User>
    .subscribe(user -> System.out.println(user));  // 每来一条数据，推给你一次
```

**题目2答案：**

```
1
2
3
第二次: 1
第二次: 2
第二次: 3
```

每个元素被 map 处理了 **2 次**（每订阅一次，整条流水线重新执行一遍）。

**解析**：因为 `Flux.just(1, 2, 3).map(...)` 是**冷发布者**。每调用一次 `.subscribe()`，都会创建一个新的订阅链路，数据从头开始流过整个流水线。就像两个人分别打开同一个水龙头，水流两次。

如果是热发布者（如 `ConnectableFlux`），则只执行一次，所有订阅者共享同一份数据。

**题目3答案：**

```
流水线已定义
map: A
收到: a
map: B
收到: b
map: C
收到: c
```

**解析**：这展示了**组装时 vs 订阅时**的区别：
1. 定义 `Flux.just(...).map(...)` 时只是**组装流水线**（铺设水管），map 中的代码**不会执行**。
2. `.subscribe()` 时才**启动流水线**（打开水龙头），数据开始流动。
3. 因此 `"流水线已定义"` 先打印，然后才是 map 和 subscribe 的输出。

这是 Reactor 最重要的概念之一：**定义流水线和执行流水线是分离的**。如果没有 `.subscribe()`，整条流水线只是内存中的 Operator 装饰链，不会有任何数据通过。

</details>

---

## 第3章 Reactor 核心原理（深入底层）

> 这一章是本文档最重要的部分。前面的章节教你"怎么用"，这一章教你"为什么这样工作"。
> 理解这些原理后，你会明白为什么 Flux.defer() 能解决时间不变的问题，
> 为什么 concatMap 保序而 flatMap 不保序，为什么 subscribeOn 能切换线程。

### 3.1 两个关键时间点：组装时 vs 订阅时

这是理解 Reactor 一切行为的**第一原理**。

**"组装时"（Assembly Time）**：你调用 `.map()`、`.filter()`、`.concatWith()` 等操作符的那一刻。
此时只是在构建一个"操作符链"的数据结构，**没有任何数据流动**。

**"订阅时"（Subscription Time）**：你调用 `.subscribe()` 的那一刻。
此时 Reactor 从链的末端开始，逆向遍历操作符链，逐个创建 `Subscriber` 和 `Subscription`，
建立完整的数据通路，然后**从数据源开始推送数据**。

```
时间线:
  组装时 (构建蓝图)                    订阅时 (启动机器)
  ───────────────────────  .subscribe()  ──────────────────→
  Flux.just(1,2,3)                       Reactor 内部:
    .map(x -> x*10)                      1. 创建 Subscriber 链
    .filter(x -> x>15)                   2. 创建 Subscription
    .map(x -> "值:"+x)                   3. just() 开始发射元素
                                        4. 数据沿操作符链流动
```

**用代码验证：**

```java
// 这段代码的执行顺序说明一切
System.out.println("A: 开始组装");
Flux<Integer> flux = Flux.just(1, 2, 3)
    .map(n -> {
        System.out.println("C: map 处理 " + n);
        return n * 10;
    });
System.out.println("B: 组装完成");
// ===== 到这里为止，控制台只输出 A 和 B =====
// "C: map 处理..." 还没有出现！因为还没订阅

System.out.println("D: 即将订阅");
flux.subscribe(result -> System.out.println("E: 订阅者收到 " + result));
System.out.println("F: 订阅调用返回");

// 完整输出:
// A: 开始组装
// B: 组装完成
// D: 即将订阅
// C: map 处理 1
// E: 订阅者收到 10
// C: map 处理 2
// E: 订阅者收到 20
// C: map 处理 3
// E: 订阅者收到 30
// F: 订阅调用返回
```

**这个原理解释了以下所有现象：**

| 现象 | 原因 |
|------|------|
| 不调用 `.subscribe()` 啥也不发生 | 数据流动只在订阅时启动 |
| `Mono.just(expensiveOp())` 有问题 | `expensiveOp()` 在**组装时**就执行了，不是订阅时 |
| `Flux.defer(() -> Flux.just(...))` 能解决 | 箭头函数在**订阅时**才执行 |
| 每个 `.map()` 返回新的 Flux | 每个操作符在**组装时**创建一个新的 Publisher 包装器 |
| 多次 `.subscribe()` 会重新执行 | 每次订阅都重新触发完整的数据生成流程（对冷发布者而言） |

### 3.2 冷发布者 vs 热发布者

Reactor 中的发布者分为"冷"和"热"，这是另一个核心概念。

**冷发布者（Cold Publisher）**：每个订阅者都会收到**完整的数据序列，从头开始**。
数据源是为每个订阅者**各自独立生成**的。就像视频点播——每个人从头开始看。

```java
Flux<Integer> cold = Flux.just(1, 2, 3);
// 第一个订阅者
cold.subscribe(n -> System.out.println("订阅者A: " + n));
// 第二个订阅者——也收到完整的 1, 2, 3
cold.subscribe(n -> System.out.println("订阅者B: " + n));
// 输出:
// 订阅者A: 1
// 订阅者A: 2
// 订阅者A: 3
// 订阅者B: 1
// 订阅者B: 2
// 订阅者B: 3
// ↑ 两个订阅者各自收到完整的序列，互不干扰
```

`Flux.just()`、`Flux.fromIterable()`、`Flux.defer()`、`Flux.fromArray()` 创建的都是**冷**发布者。

**热发布者（Hot Publisher）**：所有订阅者**共享同一个**数据源。后来的订阅者只能收到**订阅之后**的数据。
就像直播电视——你错过了就错过了。

```java
// 热发布者示例：使用 publish() + autoConnect()
Flux<Long> hot = Flux.interval(Duration.ofMillis(100))  // 每100ms发射一个递增数字
    .publish()    // 转为 ConnectableFlux（可连接的热发布者）
    .autoConnect();  // 第一个订阅者到达时自动开始

// 第1个订阅者立即开始接收数据
hot.subscribe(n -> System.out.println("A: " + n));

// 500ms 后，第2个订阅者才加入
Thread.sleep(500);
hot.subscribe(n -> System.out.println("  B: " + n));
// 输出（大致）:
// A: 0
// A: 1
// A: 2
// A: 3
// A: 4
//   B: 5  ← B 从 5 开始，错过了 0-4
// A: 5
//   B: 6
// A: 6
```

**为什么区分冷热很重要？**

- 你的 HTTP SSE 端点返回的 `Flux<ServerSentEvent>` 是**冷**的——每个浏览器（订阅者）连接都会触发独立的管线执行
- 你的 `Flux.defer(() -> chatModel.stream(request))` 是**冷**的——每次订阅都会重新发送 HTTP 请求给 LLM
- 如果误用了**热**发布者，第二个浏览器可能收到不完整的数据

**LyClaw 中的冷 vs 热实战：**

```java
// 冷发布者：每个 HTTP 请求/订阅都触发独立的 LLM 调用
@Override
public Flux<ModelResponse> stream(ChatRequest request) {
    return Flux.defer(() -> {
        // 每次订阅（每个 HTTP 请求）都会:
        // 1. 重新校验 2. 重新构建请求 3. 重新发送 HTTP
        validateRequest(request);
        Object native = buildNativeRequest(request);
        return sendNativeRequest(native).map(this::parseChunk);
    });
}

// 如果这里用 Flux.just()（非 defer），会怎样？
// → buildNativeRequest 在组装时就执行了
// → sendNativeRequest 在组装时就发送了 HTTP 请求
// → 第一个订阅者到来时，HTTP 响应可能早就回来了，流已经结束了
// → 第二个订阅者根本收不到数据
```

### 3.3 Reactive Streams 协议：背压机制

Project Reactor 实现了 [Reactive Streams](https://www.reactive-streams.org/) 规范。
这个规范定义了四个接口和它们之间的交互协议：

```
Publisher（发布者）  ──subscribe──→  Subscriber（订阅者）
       ↑                                    │
       │                              request(N)  ← "我要 N 个元素"
       │                                    ↓
       │←── onSubscribe(Subscription) ──┘
       │                                    │
       │←── onNext(data)   ───────────────┘  "给你一个元素"
       │←── onNext(data)   ───────────────┘
       │←── onComplete()   ───────────────┘  "发完了" 或
       │←── onError(error) ───────────────┘  "出错了"
```

**关键概念：`Subscription.request(N)`**

这是"背压"（Backpressure）的核心机制：**下游告诉上游自己还能消费多少数据**。
就像餐厅服务员看到你桌上还有很多菜，就不会再上新菜——下游有能力控制上游的发送速率。

```java
// 自定义 Subscriber，展示 request() 的控制作用
Flux.range(1, 1000)
    .subscribe(new Subscriber<Integer>() {
        private Subscription sub;
        private int received = 0;

        @Override
        public void onSubscribe(Subscription s) {
            this.sub = s;
            // 只请求 5 个元素！上游有 1000 个，但只会推送 5 个
            s.request(5);
        }

        @Override
        public void onNext(Integer n) {
            System.out.println("收到: " + n);
            received++;
            if (received >= 5) {
                System.out.println("够5个了，不再请求，流暂停");
                // 不再调用 request() → 上游停止推送
            }
        }

        @Override
        public void onError(Throwable t) {}

        @Override
        public void onComplete() {}
    });
// 输出:
// 收到: 1
// 收到: 2
// 收到: 3
// 收到: 4
// 收到: 5
// 够5个了，不再请求，流暂停
// （流没有 complete，因为上游还有数据，只是下游没再 request）
```

**为什么你平时不需要手动管理 request()？**

因为你用的 `.subscribe(consumer)` 内部会自动调用 `subscription.request(Long.MAX_VALUE)`，
相当于"我能接收无限多，有多少来多少"。但在需要流量控制的场景（如读取大文件、慢消费者），
手动管理 request() 可以防止内存溢出。

**LyClaw 与背压的关系：**

LyClaw 的 SSE 推送场景中，背压是自动处理的：
- 如果前端网络慢，TCP 发送缓冲区满了 → Netty 的 write 操作会返回"不可写"
- Reactor 会根据 Netty 的反馈自动停止推送元素
- 前端恢复速度后，自动恢复推送

这就是为什么 WebFlux + Reactor 能处理高并发——它从协议层面就支持背压，
不会像传统 Servlet 那样用无界队列堆积数据。

### 3.4 操作符的装饰器模式：每个操作符都是一层包装

当你写 `.map(x -> x * 10)` 时，Reactor 内部**不是**直接修改原来的 Flux，
而是创建一个**新的 Flux 对象**，这个新对象包装（装饰）了原来的 Flux：

```java
// 你写的代码:
Flux<Integer> result = Flux.just(1, 2, 3)
    .map(x -> x * 10)
    .filter(x -> x > 15);

// Reactor 内部实际创建的对象结构（简化表示）:
// FluxFilter(
//     upstream: FluxMap(
//         upstream: FluxArray([1, 2, 3]),
//         mapper: x -> x * 10
//     ),
//     predicate: x -> x > 15
// )

// 订阅时，从外向内逐层包装 Subscriber:
// 1. subscribe() 被 FluxFilter 处理
// 2. FluxFilter 创建一个 FilterSubscriber，然后订阅 FluxMap
// 3. FluxMap 创建一个 MapSubscriber，然后订阅 FluxArray
// 4. FluxArray 创建 ArraySubscription，调用 onSubscribe()
// 5. ArraySubscription.request(N) 被逐层向上传递
// 6. FluxArray 开始发射数据

// 数据流动路径:
// FluxArray → (1) → FilterSubscriber.onNext(1) → filter(1>15?false) → 丢弃
// FluxArray → (2) → MapSubscriber.onNext(2) → map(2*10=20) →
//     FilterSubscriber.onNext(20) → filter(20>15?true) → 发射给下游
```

**这个机制的三个关键推论：**

1. **不可变性**：每个操作符返回新对象，原来的 Flux 不受影响
2. **链式构建**：操作符链在组装时构建成"嵌套的装饰器链"，数据在订阅时沿链流动
3. **线程无关**：操作符本身不指定在哪个线程执行，线程切换由 Scheduler 专门完成

### 3.5 concatMap vs flatMap 的内部机制

这是最常见的困惑点。两者都接收一个函数，将每个元素映射为一个子流，但内部机制完全不同。

**为什么 concatMap 保序？**

concatMap 内部维护一个**队列**。当上游发来元素 A 时，它用 mapper 生成子流 A，然后**立即订阅**子流 A，
等待子流 A 的所有元素都发射完毕（`onComplete`），才从队列中取出下一个元素 B 来处理。

```
concatMap 的数据流:
上游:  ──A──B──C──→
              │
              ├→ A 的子流: ──A1──A2──A3──| (complete 后才处理 B)
              ├→ B 的子流: ──B1──B2──|       (complete 后才处理 C)
              └→ C 的子流: ──C1──|
下游:  ──A1──A2──A3──B1──B2──C1──→  严格按 A→B→C 顺序
```

**为什么 flatMap 可能乱序？**

flatMap 预取上游元素（默认预取 256 个），然后**同时订阅**多个子流。
哪个子流先返回数据，哪个的数据就先到达下游。

```
flatMap 的数据流（prefetch=256，同时订阅 256 个子流）:
上游:  ──A──B──C──→
              │
              ├→ A 的子流: ──A1────A2────A3──|  (慢，还在进行中)
              ├→ B 的子流: ──B1──B2──|           (快，先结束了)
              └→ C 的子流: ──C1──|

下游:  ──B1──C1──A1──B2──A2──A3──→  乱序！（B、C 的数据先到了）
```

**为什么 LyClaw 的工具执行用 concatMap？**

```java
// 用 concatMap：工具按顺序执行，前一个完成才执行下一个
// 为什么需要？因为工具可能有依赖关系——工具 B 的输入依赖工具 A 的输出
// 更重要的是：前端 UI 期望顺序展示工具调用结果
return Flux.fromIterable(toolCalls)
    .concatMap(req -> {
        // 每个工具调用产生: executing事件 → done事件
        // concatMap 确保: 工具1 的 done 事件发出后，才开始工具2
        return Flux.just(executingEvent).concatWith(doneEvent);
    });
```

### 3.6 为什么 subscribeOn 和 publishOn 的线程切换位置不同

要理解这一点，必须知道 Reactor 操作符链的内部线程模型。

**核心事实**：Reactor 操作符全部运行在**触发它们的线程**上。如果你在 main 线程调用 `.subscribe()`，
整个数据链的 `onNext()` 回调都在 main 线程执行，除非你显式插入 `subscribeOn` 或 `publishOn`。

**为什么 subscribeOn 影响整个上游链？**

`subscribeOn` 的作用是：**在指定的 Scheduler 上执行订阅（subscription）操作**。
因为订阅是从下游向上游逆向传播的：

```
.subscribe() → filter的subscribe → map的subscribe → just的subscribe
                                                         ↑
                                            subscribeOn 在这里切换线程
                                            整个订阅链从 just 开始都在新线程执行
```

所以 `subscribeOn` 实际上让**整条链的数据发射**都在指定线程进行。

**为什么 publishOn 只影响下游？**

`publishOn` 在操作符链中插入一个"线程切换点"：这个点**之前**的操作在原线程执行，
**之后**的操作切换到目标线程执行。

```
Flux.just("data")           // 线程A
    .map(s -> s.toUpper())  // 线程A (还没到 publishOn)
    .publishOn(threadB)     // ← 线程切换点
    .map(s -> s + "!")      // 线程B
    .map(s -> "[" + s + "]") // 线程B
    .subscribe();            // 触发订阅在 线程A
```

**LyClaw 中的实际运用：**

```java
// 模式: 整条工具执行链在 boundedElastic 运行
// 因为工具执行内部有 Feign 阻塞调用
Mono.fromCallable(() -> toolExecutor.execute(...))  // 阻塞代码
    .subscribeOn(Schedulers.boundedElastic());
//   ↑ 让整个 Mono（fromCallable 的 Callable 执行 + 后续操作）
//     都在 boundedElastic 线程池运行，远离 Netty IO 线程
```

### 3.7 Netty 事件循环与为什么需要 boundedElastic

WebFlux 底层使用 Netty 作为 HTTP 服务器。Netty 使用**事件循环（Event Loop）**模型：

```
Netty 架构:
┌─────────────────────────────────────┐
│  Boss EventLoopGroup (1个线程)       │  ← 接受新连接
│  boss-1: accept() → 分发给 worker   │
└──────────────┬──────────────────────┘
               │
┌──────────────▼──────────────────────┐
│  Worker EventLoopGroup (CPU核数×2)  │  ← 处理 IO 读写
│  worker-1: read() → 解析HTTP → Controller → write()
│  worker-2: read() → 解析HTTP → Controller → write()
│  worker-3: ...
└─────────────────────────────────────┘
```

每个 Worker EventLoop 是一个**单线程的事件循环**，负责多个 Channel 的 IO 操作：

```
Worker EventLoop 单线程循环:
  while(true) {
    1. select(): 等待 IO 事件（网络数据到达等）
    2. processSelectedKeys(): 处理 IO 事件
       → 读 HTTP 请求 → 路由到 Controller → 返回 Mono/Flux
       → Reactor 将 onNext() 调度回这个 EventLoop
    3. runAllTasks(): 执行排队的任务
  }
```

**如果在这个线程中执行阻塞操作，会发生什么？**

```
Worker EventLoop 被阻塞:
  select() → 处理请求 → Controller → Flux.subscribe()
    → .map(...) → .map(...) → Thread.sleep(5000)  ← 阻塞！！
    → 这个线程 5 秒内无法 select()
    → 这个线程负责的所有其他 HTTP 连接全部卡住
    → 大量请求排队等待
    → 整个服务不可用
```

**这就是为什么必须有 `Schedulers.boundedElastic()`：**

```java
// ❌ 危险：阻塞操作在 Netty IO 线程执行
@PostMapping("/chat")
public Mono<String> chat(@RequestBody Request req) {
    return Mono.fromCallable(() -> {
        // 这里调用 Feign 客户端（阻塞 IO）
        return feignClient.call(req);  // 阻塞了 Netty EventLoop！
    });
    // 没有 subscribeOn！在 Netty EventLoop 线程执行
}

// ✅ 安全：显式迁移到弹性线程池
@PostMapping("/chat")
public Mono<String> chat(@RequestBody Request req) {
    return Mono.fromCallable(() -> {
        return feignClient.call(req);  // 在 boundedElastic 线程，阻塞安全
    }).subscribeOn(Schedulers.boundedElastic());
}
```

**补充：`Mono.fromCallable` vs `Mono.fromRunnable`**

```java
// fromCallable: 有返回值的延迟操作
// 签名: public static <T> Mono<T> fromCallable(Callable<? extends T> supplier)
Mono<String> m = Mono.fromCallable(() -> {
    return heavyComputation();  // 返回值
});

// fromRunnable: 无返回值的延迟操作（返回 Mono<Void>）
// 签名: public static Mono<Void> fromRunnable(Runnable runnable)
Mono<Void> m = Mono.fromRunnable(() -> {
    cleanup();  // 无返回值，纯副作用
});
```

### 3.8 练习题

**题目1**：下面代码的输出顺序是什么？为什么？

```java
Flux<String> flux = Flux.just("A")
    .map(s -> {
        System.out.println("1. map: " + s);
        return s;
    });
System.out.println("2. 流水线已定义");
flux = flux.map(s -> {
    System.out.println("3. 第二个map: " + s);
    return s;
});
System.out.println("4. 准备订阅");
flux.subscribe(s -> System.out.println("5. 收到: " + s));
```

**题目2**：`concatMap` 和 `flatMap` 的本质区别是什么？下面两段代码的输出分别是什么？

```java
// 代码A: concatMap
Flux.just(300, 200, 100)
    .concatMap(delay -> Mono.just(delay).delayElement(Duration.ofMillis(delay)))
    .subscribe(v -> System.out.print(v + " "));

// 代码B: flatMap
Flux.just(300, 200, 100)
    .flatMap(delay -> Mono.just(delay).delayElement(Duration.ofMillis(delay)))
    .subscribe(v -> System.out.print(v + " "));
```

**题目3**：为什么在 Netty（WebFlux）的 Controller 中不能直接调用阻塞的 Feign 调用？写出正确和错误的做法。

<details>
<summary>点击查看答案与解析</summary>

**题目1答案：**

```
2. 流水线已定义
4. 准备订阅
1. map: A
3. 第二个map: A
5. 收到: A
```

**解析**：这就是"组装时 vs 订阅时"的核心体现：
1. 定义第一个 `.map()` → 组装第一层 Operator（不执行 map 中的代码）
2. `System.out.println("2")` → 执行
3. 定义第二个 `.map()` → 组装第二层 Operator（不执行）
4. `System.out.println("4")` → 执行
5. `.subscribe()` → 触发订阅，数据从上游流向下游
   - 数据 "A" 先经过第一个 map → 打印 "1. map: A"
   - 再经过第二个 map → 打印 "3. 第二个map: A"
   - 最后到达 subscribe → 打印 "5. 收到: A"

**题目2答案：**

```
// concatMap 输出（先sleep的完成后才处理下一个）:
// 等待300ms → 打印300 → 等待200ms → 打印200 → 等待100ms → 打印100
// 300 200 100 （严格保序，因为队列内部FIFO + 一次只处理一个）

// flatMap 输出:
// 100 200 300 （按完成时间升序，先到先得）
```

**解析**：
- `concatMap`：内部维护一个**队列**，一次只处理一个内层流（当前元素的内层流完成后才从队列取下一个）。所以 300ms 的内层流完成后（打印300），才启动 200ms 的，然后是 100ms 的。输出**保证顺序**。
- `flatMap`：所有内层流**并发订阅**。100ms 的最快完成所以先打印100，然后200，最后300。输出**按完成时间排序**。
- 两者原理不同：concatMap 用队列 + 串行；flatMap 用并发 + 先到先得。

**题目3答案：**

```java
// ❌ 错误：Netty worker 线程被阻塞
@GetMapping("/data")
public Mono<String> getData() {
    String result = feignClient.callRemote();  // 阻塞！worker-1 被卡住
    return Mono.just(result);
}
// 后果：worker-1 被占用来等待 HTTP 响应（数百毫秒），
// 其他请求无法使用 worker-1，吞吐量断崖式下降。

// ✅ 正确：将阻塞调用迁移到 boundedElastic 线程池
@GetMapping("/data")
public Mono<String> getData() {
    return Mono.fromCallable(() -> feignClient.callRemote())  // 在弹性线程池执行
        .subscribeOn(Schedulers.boundedElastic());            // 从 Netty Io线程迁移
}
// Netty 线程立即释放，阻塞操作在 boundedElastic 线程池执行。
// Feign 返回后，结果通过信号传递回 Netty 线程写 HTTP 响应。
```

**原理解析**：Netty 的 worker 线程（默认 CPU 核数×2，如 16 个）是**非阻塞 IO 线程**。每个 worker 使用 epoll 处理数千个连接。如果一个 worker 线程被阻塞操作（Feign 调用、JDBC 查询）占用，它负责的所有连接都受影响——这就是"线程饥饿"。`Schedulers.boundedElastic()` 创建弹性线程池（默认上限为 CPU 核数×10），专门容纳阻塞操作，保护 Netty 事件循环。

</details>

---

## 第4章 Mono：0或1个元素的异步序列

### 4.1 创建 Mono

#### `Mono<T> Mono.just(T data)` — 创建一个包含已知值的 Mono

`Mono.just` 是最基本的 Mono 创建工厂方法。它将一个已知的非 null 值包装为 Mono，订阅时立即发射该值然后发完成信号。传入 null 会立即抛出 NullPointerException 而非等到订阅时——因为 Reactor 规范禁止发射 null 值，just 在组装期就做了前置校验。如果值可能为空，请使用 `Mono.justOrEmpty`。

```java
Mono<String> mono = Mono.just("Hello World");
mono.subscribe(data -> System.out.println("收到: " + data));
// 输出: 收到: Hello World
```

#### `Mono<T> Mono.justOrEmpty(Optional<T>)` — 处理可能为空的值

`justOrEmpty` 接收一个 `Optional` 对象：有值则等价于 `Mono.just(value)`，为空则等价于 `Mono.empty()`。它还可以直接接收 null（返回空 Mono）或非 null 值（返回单元素 Mono），是 `just` 的空安全替代。常用场景是 DAO 层方法返回 Optional 时桥接到响应式链。

```java
Optional<String> present = Optional.of("存在");
Mono<String> m1 = Mono.justOrEmpty(present);
m1.subscribe(System.out::println);
// 输出: 存在

Optional<String> absent = Optional.empty();
Mono<String> m2 = Mono.justOrEmpty(absent);
m2.subscribe(
    data -> System.out.println("收到: " + data),
    err -> System.err.println("错误: " + err),
    () -> System.out.println("流完成（无数据）")
);
// 输出: 流完成（无数据）
```

#### `Mono<T> Mono.empty()` — 创建一个空的 Mono

`Mono.empty` 返回一个不发射任何数据、直接发完成信号的 Mono。它类似 Java 的 `Optional.empty()`——表示"操作成功但无结果"。订阅时数据回调（onNext）不会触发，只有完成回调（onComplete）会执行。常用于条件分支中表示"无数据"的路径，例如缓存未命中、搜索无结果等场景。

```java
Mono<String> empty = Mono.empty();
empty.subscribe(
    data -> System.out.println("收到: " + data),  // 不会执行
    err -> System.err.println("错误"),             // 不会执行
    () -> System.out.println("完成了（无数据）")    // ← 会执行这个
);
// 输出: 完成了（无数据）
```

#### `Mono<T> Mono.error(Throwable)` — 创建一个包含错误的 Mono

`Mono.error` 创建一个订阅时立即发出错误信号的 Mono。与 `Mono.empty` 不同，它表示"操作失败"而非"无结果"。可用于模拟异常场景或在上游不可用时快速返回降级错误。也可以使用重载 `Mono.error(Supplier<Throwable>)` 来惰性创建异常对象（每次订阅重新创建异常）。

```java
Mono<String> errorMono = Mono.error(new RuntimeException("网络超时"));
errorMono.subscribe(
    data -> System.out.println("收到: " + data),
    err -> System.out.println("出错了: " + err.getMessage()),
    () -> System.out.println("完成")
);
// 输出: 出错了: 网络超时
```

#### `Mono<T> Mono.fromCallable(Callable<T>)` — 包装可能抛异常的同步操作

`fromCallable` 接收一个 `Callable`（可抛受检异常的函数），惰性执行：只有在订阅时才调用 Callable。如果 Callable 正常返回，则 Mono 发射该值并完成；如果 Callable 抛出异常，则 Mono 以 error 信号终止。与 `Mono.just` 的关键区别在于惰性：`just(expensiveOp())` 在组装时立即求值，`fromCallable(() -> expensiveOp())` 在订阅时才求值，且每次订阅都会重新执行 Callable。适合包装 JDBC 查询、文件读取等可能失败且需要延迟执行的同步操作。

```java
Mono<String> safeMono = Mono.fromCallable(() -> {
    if (Math.random() > 0.5) {
        throw new IOException("文件读取失败");
    }
    return "文件内容";
});

safeMono.subscribe(
    data -> System.out.println("成功: " + data),
    err -> System.out.println("失败: " + err.getMessage())
);
// 可能输出: 成功: 文件内容
// 也可能输出: 失败: 文件读取失败
```

> **just vs fromCallable vs defer 对比**：`Mono.just(x)` 参数在组装时立即求值；`Mono.fromCallable(() -> x)` 在订阅时求值，每次订阅重新执行；`Mono.defer(() -> Mono.just(x))` 连整个 Mono 的创建都推迟到订阅时，每次订阅可返回不同的 Mono 类型。

#### `Mono<T> Mono.defer(Supplier<Mono<T>>)` — 惰性创建（与 Flux.defer 对应）

`defer` 将整个 Mono 的创建延迟到每次订阅时。参数是一个 Supplier，每次 subscribe 都会调用它返回一个全新的 Mono。与 `fromCallable` 不同：`fromCallable` 延迟的是值的计算，而 `defer` 延迟的是整个 Mono 的组装（可以在 Supplier 中根据条件返回 `Mono.empty()`、`Mono.error()` 等不同类型）。典型用途是每次订阅都需要重新评估的上下文依赖数据（如当前时间、请求级配置）。

```java
System.out.println("=== 使用 just ===");
Mono<String> justMono = Mono.just("时间: " + System.currentTimeMillis());
justMono.subscribe(s -> System.out.println("第1次: " + s));
Thread.sleep(2000);
justMono.subscribe(s -> System.out.println("第2次: " + s));
// 第1次: 时间: 1715840000000
// 第2次: 时间: 1715840000000  ← 一模一样！

System.out.println("=== 使用 defer ===");
Mono<String> deferMono = Mono.defer(() ->
    Mono.just("时间: " + System.currentTimeMillis())
);
deferMono.subscribe(s -> System.out.println("第1次: " + s));
Thread.sleep(2000);
deferMono.subscribe(s -> System.out.println("第2次: " + s));
// 第1次: 时间: 1715840002000
// 第2次: 时间: 1715840004000  ← 不同了！

// defer 的强大之处：根据条件返回不同类型的 Mono
Mono<String> conditional = Mono.defer(() -> {
    if (Math.random() > 0.5) return Mono.just("主数据源可用");
    else return Mono.empty();
});
```

#### `Mono<Long> Mono.delay(Duration)` — 创建一个延迟发射的 Mono

`delay` 在指定的延迟时间后发射 0L 并完成。底层使用 `Schedulers.parallel()` 调度器，不会阻塞当前线程。常用于：测试异步超时逻辑、实现定时任务、作为 `thenMany` 的重试延迟源（如 `Mono.delay(500ms).thenMany(retryStream)`）。

```java
System.out.println("开始: " + System.currentTimeMillis());
Mono.delay(Duration.ofSeconds(2))
    .subscribe(tick -> System.out.println("延迟后: " + System.currentTimeMillis()));
// 输出（约2秒间隔）:
// 开始: 1715840000000
// 延迟后: 1715840002000
```

#### `Mono<T> Mono.fromSupplier(Supplier<T>)` — 从 Supplier 创建

`fromSupplier` 是 `fromCallable` 的简化版，接收 Supplier（不抛受检异常）而非 Callable。同样具有惰性：每次订阅都会重新调用 Supplier 获取新值。与 `just` 的立即求值形成对比。适用于参数明确不会失败且需要延迟计算的场景。

```java
Mono<String> mono = Mono.fromSupplier(() -> "值: " + System.currentTimeMillis());
mono.subscribe(System.out::println);  // 输出: 值: 1715840000000
Thread.sleep(100);
mono.subscribe(System.out::println);  // 输出: 值: 1715840000100（不同）
```

#### `Mono<Void> Mono.fromRunnable(Runnable)` — 从 Runnable 创建

`fromRunnable` 执行一段无返回值的任务后发完成信号，返回 `Mono<Void>`。数据回调不会触发（因为没有数据可发射），只有完成回调会执行。适合将副作用操作（清理缓存、写日志、发通知）桥接到响应式链中。

```java
Mono.fromRunnable(() -> System.out.println("清理工作完成"))
    .subscribe(
        v -> {},  // 不会执行（Void 无数据）
        err -> System.err.println("错误"),
        () -> System.out.println("流完成")
    );
// 输出: 清理工作完成  流完成
```

#### `Mono<T> Mono.never()` — 永不完成的 Mono

`never` 返回一个既不发射数据、也不发完成信号、也不发错误信号的 Mono——它永远挂起。唯一用途是测试超时和取消逻辑，不应在生产代码中使用。

```java
Mono<String> never = Mono.never();
try {
    never.block(Duration.ofSeconds(1));  // 1秒后超时
} catch (IllegalStateException e) {
    System.out.println("超时");
}
// 输出: 超时
```

#### `Mono<R> Mono.zip(Mono<T1>, Mono<T2>, BiFunction)` — 等待两个 Mono 都完成后合并

`zip` 并发订阅多个 Mono，等全部完成后用 BiFunction 合并结果。关键特性：多个 Mono 是**并发执行**的（互不等待），总耗时等于最慢的那个 Mono。与串行调用（第一个完成再调第二个）相比可以显著减少总延迟。支持最多 8 个 Mono 的变体，或使用 `Mono.when` 只等待完成不合并结果。

```java
Mono<String> user = Mono.fromCallable(() -> { sleep(200); return "张三"; });
Mono<Integer> score = Mono.fromCallable(() -> { sleep(100); return 95; });

Mono<String> combined = Mono.zip(user, score, (u, s) -> u + " 得分 " + s);
System.out.println(combined.block());
// 输出: 张三 得分 95（总耗时 ≈ max(200, 100) = 200ms，不是 300ms）
```

#### `Mono<Void> Mono.when(Mono... monos)` — 等待所有 Mono 完成

`when` 等待所有给定的 Mono 完成，但不合并结果——返回 `Mono<Void>`，只发完成信号。与 `zip` 的区别：`zip` 需要 BiFunction 合并结果；`when` 完全丢弃各个 Mono 的数据，只关心"都完成了吗"。适合批量写入后发确认信号、多步骤初始化等场景。

```java
Mono<String> save1 = Mono.fromRunnable(() -> System.out.println("保存用户"));
Mono<String> save2 = Mono.fromRunnable(() -> System.out.println("保存日志"));
Mono.when(save1, save2).block();  // 等待两个都完成
// 输出: 保存用户  保存日志（顺序可能互换）
```

#### `Mono<T> Mono.using(Callable<D>, Function<D, Mono<T>>, Consumer<D>)` — 资源管理

`using` 是 try-with-resources 的响应式版本。三个参数：resourceSupplier（获取资源）、sourceSupplier（使用资源生成 Mono）、resourceCleanup（清理资源）。无论 Mono 成功完成还是以错误终止，清理函数都会被调用。适合自动管理数据库连接、文件句柄等需要显式关闭的资源。

```java
Mono<String> result = Mono.using(
    () -> { System.out.println("打开连接"); return "db-conn"; },
    conn -> Mono.just(conn + "-result"),
    conn -> System.out.println("关闭连接: " + conn)
);
System.out.println(result.block());
// 输出: 打开连接  db-conn-result  关闭连接: db-conn
```

#### `Mono<T> Mono.create(Consumer<MonoSink<T>>)` — 编程方式创建 Mono

`create` 提供最大的灵活性：接收一个回调，通过 `MonoSink` 手动控制发射。`sink.success(value)` 发射值并完成，`sink.error(e)` 以错误终止。sink 只能成功调用一次（多次调用被忽略）。适合桥接回调式 API 到响应式，例如将 Listener 回调转为 Mono。

```java
Mono<String> mono = Mono.create(sink -> {
    sink.onCancel(() -> System.out.println("被取消"));
    new Thread(() -> {
        try {
            Thread.sleep(500);
            sink.success("异步结果");  // 只能调一次
        } catch (Exception e) {
            sink.error(e);
        }
    }).start();
});
System.out.println(mono.block());  // 输出: 异步结果
```

#### `Mono<T> Mono.firstWithValue(Mono<? extends T>... sources)` — 取第一个发射数据的 Mono

`firstWithValue` 并发订阅多个 Mono，只取最早发射数据的那个的结果，其余 Mono 自动取消。注意空 Mono（`Mono.empty()`）不算"发射数据"——如果第一个完成的 Mono 是空的，它会等待下一个发射数据的。如果所有 Mono 都是空的，则最终返回空 Mono。适合多数据源竞速场景（主库超时自动走缓存）。

```java
Mono<String> slow = Mono.just("慢").delayElement(Duration.ofSeconds(5));
Mono<String> fast = Mono.just("快").delayElement(Duration.ofMillis(100));

Mono.firstWithValue(slow, fast).subscribe(System.out::println);
// 输出（约100ms后）: 快
```

### 4.2 订阅 Mono

#### `Disposable subscribe()` — 最简订阅（触发执行，不处理结果）

`subscribe()` 无参版本仅仅触发流的执行，不消费数据、不处理错误。数据元素被发射但被静默忽略（相当于 `/dev/null`）。返回的 `Disposable` 可用于取消订阅。通常用于触发仅有副作用的流（如 `Mono.fromRunnable`）。如果流中有错误信号且未被消费，会抛出 `Exceptions$ErrorCallbackNotImplemented`。

```java
Mono.just("data").subscribe();
// 数据被发射但没有处理（一般用于触发副作用）
```

#### `Disposable subscribe(Consumer<T>)` — 订阅并消费数据

只消费数据元素，错误和完成信号不处理。Lambda 参数接收每个发射的值。这是最常用的订阅变体——只有一行数据回调。如果流以错误终止，错误会以未处理异常的形式传播。

```java
Mono.just("Hello").subscribe(data -> System.out.println("收到: " + data));
// 输出: 收到: Hello
```

#### `Disposable subscribe(Consumer<T>, Consumer<Throwable>, Runnable)` — 完整三回调订阅

三参数版本分别处理数据、错误和完成三种信号。每个参数可以为 null（表示不处理该信号）。它完整实现了 Reactive Streams 的 `Subscriber` 接口，是最显式的订阅方式。Lambda 一对应数据回调（onNext），Lambda 二对应错误回调（onError），Lambda 三对应完成回调（onComplete）。

```java
Mono.just("成功")
    .subscribe(
        data -> System.out.println("数据: " + data),     // onNext
        err -> System.out.println("错误: " + err),        // onError
        () -> System.out.println("完成")                  // onComplete
    );
// 输出:
// 数据: 成功
// 完成

// 错误情况演示
Mono.error(new RuntimeException("失败"))
    .subscribe(
        data -> System.out.println("数据: " + data),
        err -> System.out.println("错误: " + err.getMessage()),
        () -> System.out.println("完成")  // ← 不会执行
    );
// 输出: 错误: 失败
```

### 4.3 Mono 转换操作

#### `Mono<R> .map(Function<T,R>)` — 同步转换数据类型

`map` 是 Mono 最基础的转换操作符。它接收一个纯函数 `T → R`，当上游 Mono 发射值 T 时，map 调用该函数得到 R，然后向下游发射 R。整个过程是**同步**的——不涉及线程切换，不引入异步边界。如果上游为空 Mono 或错误 Mono，map 的转换函数不会被调用，信号直接透传。链式 `.map().map()` 形成类型安全的转换流水线。

```java
Mono.just("123")
    .map(s -> Integer.parseInt(s))  // String → Integer
    .map(i -> i * 2)                // Integer → Integer
    .subscribe(result -> System.out.println("结果: " + result));
// 输出: 结果: 246

// 逐步拆解类型变化:
Mono<String> step1 = Mono.just("123");                    // Mono<String>
Mono<Integer> step2 = step1.map(s -> Integer.parseInt(s)); // Mono<Integer>
Mono<Integer> step3 = step2.map(i -> i * 2);              // Mono<Integer>
```

#### `Mono<R> .flatMap(Function<T, Mono<R>>)` — 异步展平转换

`flatMap` 是异步版的 `map`。区别在于参数函数的返回值：`map` 的函数返回**普通值 R**（map 自动包装为 `Mono<R>`）；`flatMap` 的函数自己返回 **`Mono<R>`**（flatMap 将其展平，不产生嵌套）。当上游为**空 Mono** 时，flatMap 的函数根本不会被调用——这是单子（Monad）的 flatMap 语义：只有上游有值时才执行异步操作。典型场景是根据查询结果发起下一次异步调用（如根据用户 ID 查订单）。

```java
// map vs flatMap 对比:
// .map(fn)     — fn 返回普通值 R  → map 自动包装为 Mono<R>
// .flatMap(fn) — fn 自己返回 Mono<R> → flatMap "展平"为 Mono<R>

// ❌ 错误: map 导致嵌套
Mono.just("user-123").map(id -> findById(id));
// 返回类型: Mono<Mono<User>>  ← 嵌套！

// ✅ 正确: flatMap 自动展平
Mono.just("user-123").flatMap(id -> findById(id));
// 返回类型: Mono<User>  ← 展平后的结果

Mono.just("user-123")
    .flatMap(userId -> Mono.fromCallable(() -> {
        System.out.println("查询数据库: " + userId);
        return "用户详情[" + userId + "]";
    }))
    .subscribe(result -> System.out.println("最终: " + result));
// 输出: 查询数据库: user-123  最终: 用户详情[user-123]
```

#### `Flux<R> .flatMapMany(Function<T, Flux<R>>)` — 将 Mono 展开为 Flux

`flatMapMany` 是 Mono → Flux 的桥接操作符。当上游 Mono 发射值后，调用函数得到一个 `Flux`（多个元素），然后将该 Flux 的元素逐个向下游发射。类型从 `Mono<T>` 变为 `Flux<R>`。典型场景是一对多查询：一个分类 ID → 多个商品。

```java
Mono.just("category-electronics")
    .flatMapMany(categoryId -> Flux.just("手机", "平板", "笔记本", "耳机"))
    .subscribe(item -> System.out.println("商品: " + item));
// 输出: 商品: 手机  商品: 平板  商品: 笔记本  商品: 耳机
// 类型: Mono<String> → flatMapMany → Flux<String>
```

#### `Mono<T> .defaultIfEmpty(T defaultV)` — 空 Mono 时给默认值

当上游 Mono 为空（`Mono.empty()`）时，`defaultIfEmpty` 用给定的默认值替代。如果上游有值，默认值被忽略。与 `switchIfEmpty` 的关键区别：`defaultIfEmpty` 给的是**静态值**，`switchIfEmpty` 给的是**另一个 Mono**（可以是异步获取的）、

```java
Mono<String> empty = Mono.empty();
String result = empty.defaultIfEmpty("默认值").block();
System.out.println(result);
// 输出: 默认值
```

#### `Mono<T> .filter(Predicate<T>)` — 条件过滤

`filter` 对 Mono 中可能存在的值进行条件测试。Predicate 返回 true 则原值继续向下发射；返回 false 则此 Mono 变为空 Mono（`Mono.empty()`），下游只收到完成信号。注意：如果上游本身就是空 Mono，filter 的 Predicate 根本不会被调用。与 Flux 的 filter 不同——Flux filter 可能过滤掉部分元素但流继续，Mono filter 只有"保留"或"变空"两种结局。

```java
Mono.just(100).filter(n -> n > 50).subscribe(System.out::println);  // 输出: 100
Mono.just(30).filter(n -> n > 50).defaultIfEmpty(0).subscribe(System.out::println);  // 输出: 0
```

#### `Mono<T> .switchIfEmpty(Mono<T>)` — 空 Mono 时切换到备用 Mono

`switchIfEmpty` 在主 Mono 为空时切换到备用的 Mono。与 `defaultIfEmpty` 的区别：`defaultIfEmpty` 提供**静态值**，`switchIfEmpty` 提供**另一个 Mono**（可以是惰性异步查询的结果）。注意：备用的 Mono 在组装时就已经创建了（不是惰性的），如需每次按需创建，将备用包进 `Mono.defer(() -> fallbackMono)`。

```java
Mono<String> primary = Mono.empty();
Mono<String> fallback = Mono.just("从缓存读取");
primary.switchIfEmpty(fallback).subscribe(System.out::println);  // 输出: 从缓存读取
// 惰性备用: primary.switchIfEmpty(Mono.defer(() -> fetchFromCache()));
```

#### `Mono<Void> .then()` / `Mono<R> .thenReturn(R)` / `.thenEmpty(Publisher)` — 完成后切换

这三个操作符都在当前 Mono 完成（无论是否有值）**之后**执行操作，并**丢弃原数据**。`then()` 返回 `Mono<Void>` 仅发完成信号。`thenReturn(value)` 返回固定值的 Mono。`thenEmpty(publisher)` 切换到另一个 Publisher。适合"前一步完成后触发清理或通知"的场景。

```java
Mono.just("原数据").thenReturn("处理完成").subscribe(System.out::println);  // 输出: 处理完成
Mono.just("data").thenEmpty(Mono.fromRunnable(() -> System.out.println("清理")));
```

#### `Mono<R> .zipWith(Mono<T2>, BiFunction)` — 与另一个 Mono 配对合并

`zipWith` 是 `Mono.zip` 的实例方法版本：当当前 Mono 和传入的 Mono 都完成时，用 BiFunction 合并两者的结果。两个 Mono **并发执行**，总耗时取最长者。与 `zipWhen` 的区别：`zipWhen` 的参数是动态函数（根据当前值决定配对什么 Mono），`zipWith` 的配对 Mono 是静态传入的。

```java
Mono.just("张三").zipWith(Mono.just(25), (n, a) -> n + " " + a + "岁")
    .subscribe(System.out::println);  // 输出: 张三 25岁
// zipWhen: 动态决定配对
Mono.just("user1").zipWhen(user -> Mono.just(user + "-detail"))
    .subscribe(t -> System.out.println(t.getT1() + " → " + t.getT2()));
```

#### `Mono<T> .delayElement(Duration)` / `Mono<T> .delayUntil(Function)` — 延迟发射

`delayElement` 在收到上游值后延迟指定时间再向下游发射该值。`delayUntil` 更灵活：它接收一个函数，根据上游值动态计算何时可以继续——函数返回的 Publisher 发完信号后，原始值才被发射。两者都不改变数据本身，只控制时序。

```java
System.out.println("当前: " + System.currentTimeMillis());
Mono.just("延迟数据").delayElement(Duration.ofSeconds(2)).block();  // 约2秒后返回
Mono.just("数据").delayUntil(v -> Mono.delay(Duration.ofMillis(500))).subscribe();  // 500ms后
```

#### `Flux<T> .expand(Function<T, Publisher<T>>)` — 递归展开

`expand` 从初始元素出发，递归调用 expander 函数生成后续元素，直到 expander 返回空。遍历方式是**深度优先**（先处理新生成元素，再回头处理同层元素）。返回 `Flux<T>`（因为可能生成多个元素）。典型场景：分页查询（从第1页递归拉取所有页）、树形结构遍历。

```java
Mono.just(1).expand(page -> page <= 2 ? Mono.just(page + 1) : Mono.empty())
    .collectList().subscribe(System.out::println);  // 输出: [1, 2, 3]
```

#### `Mono<R> .cast(Class<R>)` / `Mono<R> .ofType(Class<R>)` — 类型收窄

`cast` 将元素强制转换为指定类型，类型不匹配时抛 `ClassCastException`。`ofType` 是安全版本：类型不匹配时转为空 Mono（不抛异常）。在有不明确的泛型边界时用于类型收窄。

```java
Mono<Number> num = Mono.just(42);
Mono<Integer> i = num.cast(Integer.class);  // 安全: 42 是 Integer
Mono.just((Number) 1.0).ofType(Integer.class)      // 1.0 是 Double，不匹配
    .switchIfEmpty(Mono.just(-1)).subscribe();  // 输出: -1
```

#### `Mono<R> .transform(Function<Mono<T>, Publisher<R>>)` — 函数式组合复用

`transform` 将一组操作符链封装为可复用的 Function，在多处 `.transform(func)` 调用。它本质上是函数组合——接收整个 Mono 流并返回新流。`transformDeferred` 的变体在每次订阅时重新应用（适应有状态的装饰器）。

```java
public static <T> Function<Mono<T>, Mono<T>> withLoggingAndTimeout() {
    return mono -> mono
        .doOnSubscribe(s -> log.info("开始"))
        .timeout(Duration.ofSeconds(5))
        .doOnSuccess(v -> log.info("成功: {}", v));
}
Mono.just("data").transform(withLoggingAndTimeout()).subscribe();
```

#### `Mono<T> .cache()` / `.log()` — 缓存与调试

`cache()` 缓存 Mono 的结果：首次订阅时执行副作用并缓存结果，后续订阅直接返回缓存值（副作用不再执行）。类似单例模式。`log()` 在流的每个生命周期事件（subscribe/onNext/onComplete/onError/cancel）打印日志，详见第13章。

```java
Mono<String> cached = Mono.fromCallable(() -> { System.out.println("执行"); return "结果"; }).cache();
cached.subscribe();  // 打印: 执行
cached.subscribe();  // 不打印（直接返回缓存）
Mono.just("data").log().subscribe();  // 日志: onSubscribe, onNext(data), onComplete
```

### 4.4 获取 Mono 的结果

#### `T .block()` — 阻塞等待结果

`block()` 将异步的 Mono 拉回同步世界——当前线程阻塞等待 Mono 完成。空 Mono 返回 null，错误 Mono 抛出异常。**只能在测试或非响应式边界使用**，严禁在 Netty event loop 线程中调用（会导致线程饥饿和服务假死）。

```java
String result = Mono.just("Hello").block();     // "Hello"
String nullResult = Mono.<String>empty().block(); // null
```

#### `T .block(Duration)` — 带超时的阻塞等待

`block(Duration)` 增加了超时保护：在指定时间内未完成则抛出 `IllegalStateException`。适用于测试中避免无限等待，或在非响应式边界调用外部响应式 API 时防止永久阻塞。

```java
String result = Mono.just("data").block(Duration.ofSeconds(5));  // "data"
try { Mono.never().block(Duration.ofSeconds(1)); } catch (IllegalStateException e) { /* 超时 */ }
```

#### `Optional<T> .blockOptional()` / `Optional<T> .blockOptional(Duration)` — 阻塞获取 Optional

`blockOptional()` 与 `block()` 的区别：空 Mono 时返回 `Optional.empty()` 而非 null，避免空指针风险。超时版本行为同 `block(Duration)`。

```java
Optional<String> result = Mono.just("data").blockOptional();          // Optional[data]
Optional<String> empty = Mono.<String>empty().blockOptional();        // Optional.empty
Optional<String> opt = Mono.just("data").blockOptional(Duration.ofSeconds(5));
```

#### `Disposable subscribe(Consumer<T>, Consumer<Throwable>)` — 二回调订阅变体

这是两参数版本的 `subscribe`，处理数据和错误但不处理完成信号。Lambda 一接收数据值，Lambda 二接收异常。适合只关心"成功拿到数据"或"出错了"而不需要知道流何时完成的场景。

```java
Mono.just("data").subscribe(
    data -> System.out.println("收到: " + data),
    err -> System.err.println("错误: " + err)
);
```

#### `Disposable` — 取消订阅

`subscribe()` 返回 `Disposable` 对象，调用 `dispose()` 可以主动取消订阅。取消后，上游停止数据发射（如果底层支持），下游回调不再被触发。对于已取消的订阅，`isDisposed()` 返回 true。适用于超时取消、用户主动中断、页面关闭清理等场景。

```java
Disposable disposable = Mono.delay(Duration.ofSeconds(10))
    .subscribe(tick -> System.out.println("延迟结果"));
System.out.println("已订阅，准备取消");
disposable.dispose();  // 立即取消，延迟的 Mono 不会发射
System.out.println("已取消: " + disposable.isDisposed());
// 输出: 已订阅，准备取消  已取消: true
```

### 4.5 练习题

**题目2**：下面代码输出什么？为什么？

```java
String externalValue = "Hello";
Mono<String> mono = Mono.just(externalValue);
externalValue = "World";
mono.subscribe(System.out::println);
```

如果改成 `Mono.defer()` 会输出什么？

**题目3**：`map` 和 `flatMap` 在 Mono 上的区别是什么？下面的代码哪段正确？

```java
// 代码A
Mono<String> result = Mono.just("user_001")
    .map(id -> fetchUserName(id));  // fetchUserName 返回 Mono<String>

// 代码B
Mono<String> result = Mono.just("user_001")
    .flatMap(id -> fetchUserName(id));  // fetchUserName 返回 Mono<String>
```

<details>
<summary>点击查看答案与解析</summary>

**题目1答案：**

```java
// 方式1: just — 已知确定值（立即求值）
Mono<String> m1 = Mono.just("Hello");

// 方式2: justOrEmpty — 可能为 null（null 时返回空 Mono）
String nullable = null;
Mono<String> m2 = Mono.justOrEmpty(nullable);  // MonoEmpty

// 方式3: fromCallable — 延迟计算（订阅时才执行）
Mono<String> m3 = Mono.fromCallable(() -> {
    Thread.sleep(1000);
    return "计算结果";
});

// 方式4: fromFuture — 从 CompletableFuture 桥接
CompletableFuture<String> future = CompletableFuture.supplyAsync(() -> "未来结果");
Mono<String> m4 = Mono.fromFuture(future);

// 方式5: fromSupplier + 异常处理
Mono<String> m5 = Mono.fromSupplier(() -> {
    if (Math.random() > 0.5) throw new RuntimeException("出错");
    return "安全结果";
});// 注意：fromSupplier 不会捕获异常，需要用 defer + try-catch
```

**验证运行：**

```java
m1.subscribe(System.out::println);  // 输出: Hello
m2.subscribe(System.out::println);  // 无输出（空 Mono）
m3.subscribe(System.out::println);  // 1秒后输出: 计算结果
m4.subscribe(System.out::println);  // 输出: 未来结果
```

**题目2答案：**

```
// Mono.just() 输出: Hello
// Mono.defer() 输出: World
```

**解析**：`Mono.just(externalValue)` 在**组装时**就捕获了 `externalValue` 的值（"Hello"），即使后来变量改为 "World"，流水线中保存的已经是旧的 "Hello"。这是 `just` 的**立即求值**特性——就像拍了张照片，之后怎么改原物都不影响照片。

`Mono.defer(() -> Mono.just(externalValue))` 则是**延迟求值**——每次订阅时才执行 Lambda 取当前值。所以如果中间改了变量，订阅时拿到的是 "World"。这就像每次要看照片时才去现场拍照。

```java
// 验证
String val = "Hello";
Mono<String> m1 = Mono.just(val);          // 立即捕获 "Hello"
Mono<String> m2 = Mono.defer(() -> Mono.just(val));  // 订阅时才评估
val = "World";
m1.subscribe(System.out::println);  // Hello
m2.subscribe(System.out::println);  // World
```

**题目3答案：**

代码 B 正确，代码 A 会得到 `Mono<Mono<String>>`（嵌套类型）。

```java
// ❌ 代码A: map 不展平 → 返回 Mono<Mono<String>>
Mono<Mono<String>> nested = Mono.just("user_001")
    .map(id -> fetchUserName(id));

// ✅ 代码B: flatMap 自动展平 → 返回 Mono<String>
Mono<String> result = Mono.just("user_001")
    .flatMap(id -> fetchUserName(id));

result.subscribe(System.out::println);  // 输出: Alice
```

**解析**：
- `.map(Function<T, R>)`：将 Mono 内的值**同步转换为另一个值**。如果 Function 返回 `Mono<String>`，那外层就变成 `Mono<Mono<String>>`——两层嵌套。
- `.flatMap(Function<T, Mono<R>>)`：将 Mono 内的值**异步转换为另一个 Mono 并展平**。它接收一个返回 Mono 的函数，自动解包外层的包装，最终得到 `Mono<R>`。
- 记忆法则：`map` 保持结构（1:1 转换），`flatMap` 展平结构（Mono<Mono> → Mono）。

</details>

---

## 第5章 Flux：0到N个元素的异步序列

### 5.1 创建 Flux

#### `Flux<T> Flux.just(T... values)` — 从已知值创建

`Flux.just` 是最简单的静态工厂方法，将一组已知值立即包装为 Flux 并同步发射。它在调用时求值，适合数据已确定的场景。如果需要惰性求值（每次订阅重新计算），使用 `Flux.defer`。

```java
Flux<Integer> numbers = Flux.just(1, 2, 3, 4, 5);
numbers.subscribe(n -> System.out.println("数字: " + n));
// 输出:
// 数字: 1
// 数字: 2
// 数字: 3
// 数字: 4
// 数字: 5

// 也可以不传任何参数（但很少这样用）
Flux<String> empty = Flux.just();  // 等于 Flux.empty()
```

#### `Flux<T> Flux.fromIterable(Iterable)` — 从集合创建

`Flux.fromIterable` 将任何 `Iterable`（如 List、Set、Queue）中的元素包装为 Flux，逐个同步发射。它与 `Flux.fromArray` 类似但接收集合而非数组。如果集合很大，注意所有元素会被立即按序发射（非惰性），如需惰性遍历可使用 `Flux.fromStream`。

```java
List<String> fruits = List.of("苹果", "香蕉", "橙子");
Flux<String> flux = Flux.fromIterable(fruits);
flux.subscribe(f -> System.out.println("水果: " + f));
// 输出:
// 水果: 苹果
// 水果: 香蕉
// 水果: 橙子
```

#### `Flux<T> Flux.fromArray(T[])` — 从数组创建

`Flux.fromArray` 将数组包装为 Flux，所有元素在订阅时同步按序发射。它与 `Flux.fromIterable` 类似但直接接受数组，避免先转为 List 的中间步骤。适合数组已有、数据量不太大的场景。

```java
String[] arr = {"a", "b", "c"};
Flux.fromArray(arr).subscribe(System.out::println);
// 输出: a b c（每行一个）
```

#### `Flux<T> Flux.empty()` — 创建空流

`Flux.empty` 创建一个不发射任何数据、直接发送完成信号的 Flux。它常用于需要返回空结果但类型匹配的场景。与 `Mono.empty()` 类似，但返回 Flux 类型。如需在空流时给出默认值，使用 `.defaultIfEmpty` 或 `.switchIfEmpty`。

```java
Flux<String> empty = Flux.empty();
empty.subscribe(
    data -> System.out.println("数据: " + data),
    err -> System.out.println("错误"),
    () -> System.out.println("流结束（无数据）")
);
// 输出: 流结束（无数据）
```

#### `Flux<T> Flux.error(Throwable)` — 创建错误流

`Flux.error` 创建一个在订阅时立即发送错误信号的 Flux，不发射任何数据。它用于在流中传播异常或测试错误处理逻辑。相比 `Mono.error`，它返回 Flux 类型以便在 Flux 链中直接使用。延迟创建错误可使用 `Flux.defer(() -> Flux.error(...))`。

```java
Flux.error(new RuntimeException("出错了"))
    .subscribe(
        data -> System.out.println("数据: " + data),
        err -> System.out.println("捕获: " + err.getMessage())
    );
// 输出: 捕获: 出错了
```

#### `Flux<T> Flux.defer(Supplier<Flux<T>>)` — 惰性创建（每次订阅重新执行）

`Flux.defer` 在每次订阅时重新调用 Supplier 生成新的 Flux，实现惰性创建。与 `Flux.just`（定义时立即求值）不同，`defer` 确保每次订阅都能获取最新状态。它适合封装有副作用的操作（如 HTTP 请求、数据库查询），确保重试或多次订阅时重新执行。在 LyClaw 的 `AbstractChatModel.stream()` 中，`defer` 确保每次订阅都重新发送 HTTP 请求。

```java
// 对比: Flux.just() 在定义时就确定了值
//       Flux.defer() 在订阅时才执行 supplier

// 示例：Flux.just 的问题
System.out.println("=== 使用 just ===");
Flux<String> justFlux = Flux.just("当前时间: " + System.currentTimeMillis());
// ↑ 上面的 System.currentTimeMillis() 在 just() 调用时就已求值

justFlux.subscribe(data -> System.out.println("第1次订阅: " + data));
Thread.sleep(1000);
justFlux.subscribe(data -> System.out.println("第2次订阅: " + data));
// 输出:
// 第1次订阅: 当前时间: 1715840123000
// 第2次订阅: 当前时间: 1715840123000  ← 和第1次一模一样！时间没变
```

```java
// 示例：Flux.defer 的解决方案
System.out.println("=== 使用 defer ===");
Flux<String> deferFlux = Flux.defer(() ->
    Flux.just("当前时间: " + System.currentTimeMillis())
);
// ↑ supplier 是 () -> Flux.just(...) ，每次订阅时才执行

deferFlux.subscribe(data -> System.out.println("第1次订阅: " + data));
Thread.sleep(1000);
deferFlux.subscribe(data -> System.out.println("第2次订阅: " + data));
// 输出:
// 第1次订阅: 当前时间: 1715840124000
// 第2次订阅: 当前时间: 1715840125000  ← 不同了！因为 supplier 重新执行了
```

**LyClaw 中的实际应用**（`AbstractChatModel.stream()`）：

```java
@Override
public Flux<ModelResponse> stream(ChatRequest request) {
    return Flux.defer(() -> {
        // defer 确保每次 subscribe 都重新执行:
        // 1. 校验请求
        // 2. 构建原生请求
        // 3. 发送 HTTP 请求
        // 如果不用 defer，这三个步骤只执行一次，
        // 多次订阅会拿到相同的数据流，HTTP 请求不会重新发送
        validateRequest(request);
        Object nativeRequest = buildNativeRequest(request);
        return sendNativeRequest(nativeRequest)
                .map(this::parseChunk);
    });
}
```

#### `Flux<T> Flux.create(Consumer<FluxSink<T>>)` — 编程方式创建流

`Flux.create` 通过 FluxSink 以编程方式发射数据，支持多次调用 `sink.next()`、异步发射和多线程（默认）。它最适合将传统的回调式 API（如事件监听器、消息消费者）桥接到 Reactor 世界。相比 `Flux.push`（单线程）和 `Flux.generate`（一次一个、背压友好），`create` 最灵活但需要手动管理背压。在 LyClaw 的 `OrchestratorImpl.executeAgentTask()` 中用于将协作事件依次推入流。

```java
// 场景：将回调式 API 包装为 Reactor 流
Flux<String> customFlux = Flux.create(sink -> {
    // sink 是你的"发射器"，通过它往 Flux 里推数据
    sink.next("事件1");
    sink.next("事件2");
    sink.next("事件3");
    // 模拟异步完成
    new Thread(() -> {
        try { Thread.sleep(500); } catch (Exception e) {}
        sink.next("异步事件");
        sink.complete();  // 标记流结束
    }).start();
});

customFlux.subscribe(
    data -> System.out.println("收到: " + data),
    err -> System.out.println("错误: " + err),
    () -> System.out.println("流结束")
);
// 输出:
// 收到: 事件1
// 收到: 事件2
// 收到: 事件3
// 收到: 异步事件
// 流结束
```

**LyClaw 中的实际应用**（`OrchestratorImpl.executeAgentTask()`）：

```java
return Flux.create(sink -> {
    // 依次发射协作事件
    sink.next(AgentEvent.builder()
            .type(EventType.COLLABORATION_STARTED)
            .agentId("orchestrator")
            .build());

    for (var task : context.getTasks()) {
        sink.next(AgentEvent.builder()
                .type(EventType.TASK_STARTED)
                .data("Task started: " + task.getTaskId())
                .build());
        sink.next(AgentEvent.builder()
                .type(EventType.TASK_COMPLETED)
                .data("Task completed: " + task.getTaskId())
                .build());
    }

    sink.next(AgentEvent.builder()
            .type(EventType.COLLABORATION_ENDED)
            .build());
    sink.complete();
});
```

#### `Flux<Integer> Flux.range(int start, int count)` — 创建整数序列

`Flux.range` 发射从 start 开始的 count 个连续整数，是 for 循环的响应式等价物。所有元素在订阅时立即同步按序发射（不需要延迟）。它常用于生成测试数据、索引序列和循环次数，性能轻量且背压友好。

```java
Flux.range(1, 5)
    .subscribe(System.out::println);
// 输出: 1 2 3 4 5

// 典型用法：生成索引、循环次数、测试数据
Flux.range(0, 10)
    .map(i -> "item-" + i)
    .subscribe(System.out::println);
// 输出: item-0 item-1 ... item-9
```

#### `Flux<Long> Flux.interval(Duration)` — 创建定时发射的流

`Flux.interval` 按照指定的时间间隔周期性地发射递增的 Long 值（从 0 开始）。它默认在 `Schedulers.parallel()` 上执行，不阻塞主线程。适合需要定时轮询、心跳或采样数据的场景。测试或 main 方法中需配合 `Thread.sleep` 或 `.blockLast()` 等待，否则 JVM 可能在数据到达前退出。

```java
// 每 1 秒发射一次，取前 3 次
Flux.interval(Duration.ofSeconds(1))
    .take(3)
    .subscribe(i -> System.out.println("第 " + i + " 秒"));
// 输出（每隔1秒）:
// 第 0 秒
// 第 1 秒
// 第 2 秒

// interval 默认在 Schedulers.parallel() 上执行，不阻塞主线程
// 测试时需要 blockLast 或 sleep 等待
Disposable disp = Flux.interval(Duration.ofMillis(500))
    .subscribe(v -> System.out.println("tick: " + v));
Thread.sleep(2000);
disp.dispose();  // 取消订阅
```

#### `Flux<T> Flux.fromStream(Stream<T>)` — 从 Java Stream 创建

`Flux.fromStream` 将 Java Stream 包装为 Flux。Java Stream 是一次性的，第二次订阅会报错，因此需要多次订阅时必须配合 `Flux.defer(() -> Flux.fromStream(...))` 每次创建新 Stream。它适合桥接已有的 Stream API 代码与 Reactor 管道。

```java
Stream<String> stream = Stream.of("A", "B", "C");
Flux.fromStream(stream).subscribe(System.out::println);
// 输出: A B C

// 注意：Stream 是一次性的，第二次订阅会报错
// 每次订阅都需要新的 Stream → 用 Flux.defer + Supplier<Stream>
Flux.defer(() -> Flux.fromStream(Stream.of("A", "B", "C")))
    .subscribe(System.out::println);  // 安全
```

#### `Flux<T> Flux.generate(Callable, BiFunction)` — 同步生成器（逐个生成）

`Flux.generate` 用状态机模型同步生成数据，每次调用 generator 时 `sink.next()` 只能调用一次。它由下游拉取驱动（背压友好），适合每次生成需依赖前一个状态的场景。与 `Flux.create`（可多次 next、支持异步）和 `Flux.push`（单线程异步）相比，`generate` 最受约束但也最安全。

```java
Flux.<Integer>generate(
    () -> 0,                          // 初始状态 = 0
    (state, sink) -> {
        if (state >= 3) {
            sink.complete();          // 停止
        } else {
            sink.next(state * 10);    // 发射 0, 10, 20
        }
        return state + 1;             // 更新状态
    }
).subscribe(System.out::println);
// 输出: 0 10 20

// generate vs create:
// generate: 同步，sink.next() 只能调用一次，流速率由消费者决定（背压友好）
// create:   异步，sink.next() 可多次调用，适合桥接回调 API
```

#### `Flux<T> Flux.merge(Publisher... sources)` — 动态合并多个流（并发交错）

`Flux.merge` 并发订阅多个 Publisher，不保证顺序，数据按实际到达时间交错合并。与 `concat`（串行、保序）和 `zip`（按位置配对）不同，`merge` 适合对顺序无要求的并发多源聚合场景。内层流的数量无限制，一个流报错则整个 merge 报错。

```java
Flux<String> fast = Flux.just("F1", "F2", "F3")
    .delayElements(Duration.ofMillis(10));
Flux<String> slow = Flux.just("S1", "S2")
    .delayElements(Duration.ofMillis(30));

Flux.merge(fast, slow)
    .subscribe(System.out::println);
// 输出（大约）: F1 S1 F2 F3 S2（快速流的数据先到达）

// merge 是动态的：数据一到就发，不等待配对
// 和 concat 的区别：concat 串行（第一个流完成才订阅第二个）
// 和 zip 的区别：zip 按位置配对，等待所有流都生成数据
```

#### `Flux<R> Flux.zip(Publisher, Publisher, BiFunction)` — 按位置配对合并

`Flux.zip` 将多个流的元素按索引位置一一配对，通过 zipper 函数组合后发射。结果流的长度等于最短输入流的长度——任一源流完成后，其他源流的剩余数据被忽略。与 `merge`（动态交错、不定长）和 `concat`（串行拼接）不同，`zip` 要求配对的元素同步等待。在 LyClaw 中用于为 SSE 事件分配递增序号。

```java
Flux<String> names = Flux.just("张三", "李四", "王五");
Flux<Integer> ages = Flux.just(25, 30, 35);

Flux.zip(names, ages, (name, age) -> name + " " + age + "岁")
    .subscribe(System.out::println);
// 输出:
// 张三 25岁
// 李四 30岁
// 王五 35岁

// LyClaw 中 SSE 事件的 id 就是 zip 配对的：
// Flux.zip(Flux.range(0, Long.MAX_VALUE), dataFlux, (i, sse) -> sse.id(i))
// 为每个 SSE 事件自动分配递增序号
```

#### `Flux<T> Flux.firstWithValue(Publisher... sources)` — 取第一个发射数据的流

`Flux.firstWithValue` 同时订阅多个 Publisher，只取第一个产生数据（而非完成信号）的流，其他流被取消。如果所有流都先发完成信号（空流），则结果流也是空的。这是多数据源竞争模式的响应式实现——用响应最快的那个源。区别 `Flux.firstWithSignal`（包含完成信号也参与竞争）。

```java
Flux<String> a = Flux.just("A1", "A2").delayElements(Duration.ofMillis(100));
Flux<String> b = Flux.just("B1", "B2").delayElements(Duration.ofMillis(10));

Flux.firstWithValue(a, b).subscribe(System.out::println);
// 输出: B1 B2（b 先发射，所以 a 被完全忽略）

// 场景：多个数据源竞争，用最快的那个
```

#### `Flux<T> Flux.push(Consumer<FluxSink<T>>)` — 异步创建（单线程）

`Flux.push` 与 `Flux.create` 类似但强制单线程——只有同一时间唯一的线程可以调用 `sink.next()`。这避免了 `Volatile` 或 `Atomic` 开销，比 `create` 更高效。当上游回调 API 本身保证单线程（如 Netty Channel、消息队列 Listener）时，优先用 `push`。

```java
Flux.<String>push(sink -> {
    sink.onCancel(() -> System.out.println("被取消了"));
    // 模拟事件监听器
    sink.next("事件A");
    sink.next("事件B");
    sink.complete();
}).subscribe(System.out::println);
```

#### `Flux<T> Flux.using(Callable, Function, Consumer)` — 资源管理（try-with-resources 模式）

`Flux.using` 是 try-with-resources 的响应式等价物：订阅时获取资源，使用资源生成数据流，在流终止（完成、错误或取消）时自动清理资源。清理回调始终执行，即使流中途取消。适合自动管理数据库连接、文件句柄、HTTP 客户端等需显式释放的资源。对必须安全关闭的资源，它比手动管理代码更可靠。

```java
// 场景：自动管理数据库连接、文件句柄等
Flux.using(
    () -> { System.out.println("打开连接"); return "db-conn"; },
    conn -> Flux.just((conn + "-data1"), (conn + "-data2")),
    conn -> System.out.println("关闭连接: " + conn)
).subscribe(System.out::println);
// 输出:
// 打开连接
// db-conn-data1
// db-conn-data2
// 关闭连接: db-conn
```

### 5.2 Flux 转换操作

#### `Flux<R> .map(Function<T,R>)` — 逐元素转换

`.map` 是基础的同步一对一转换操作符，对每个元素应用 mapper 函数并将结果发射到下游。它保持顺序不变，在调用者的线程上同步执行。当转换逻辑是纯函数、无异步 IO 时，始终用 `.map` 而非 `.flatMap`（后者会引入不必要的内层发布者包装）。如果需要根据条件动态决定发射几个元素，用 `.handle`。

```java
Flux.just(1, 2, 3, 4, 5)
    .map(n -> n * n)  // 每个元素平方
    .subscribe(System.out::println);
// 输出:
// 1
// 4
// 9
// 16
// 25
```

#### `Flux<T> .filter(Predicate<T>)` — 过滤元素

`.filter` 根据谓词条件筛选元素：返回 true 的元素保留，false 的元素被丢弃（不发射给下游）。它同步执行，保持顺序不变。过滤后流可能为空——可通过 `.defaultIfEmpty` 或 `.switchIfEmpty` 提供回退值。`filter` 保持元素类型不变（Flux<T> 进 Flux<T> 出），与 `ofType`（按类型筛选）互补。

```java
Flux.just(1, 2, 3, 4, 5, 6)
    .filter(n -> n % 2 == 0)  // 只保留偶数
    .subscribe(System.out::println);
// 输出:
// 2
// 4
// 6
```

#### `Flux<R> .flatMap(Function<T, Flux<R>>)` — 一对多展开

`.flatMap` 将每个源元素映射为一个内层 Publisher（通常 Flux），然后并发订阅所有内层流，将它们的元素交错合并为一个扁平流。它不保证顺序——先到的元素先发射。使用时注意内层的异步程度（可用 `flatMap(mapper, concurrency)` 限制并发数）。当需要保持顺序时用 `concatMap` 或 `flatMapSequential`，当映射逻辑是纯同步的用 `.map` 更高效。

```java
// 场景：每个用户有多个订单，需要把所有订单展开为一个流
Flux.just("用户A", "用户B")
    .flatMap(user -> {
        // 模拟：每个用户有多个订单
        return Flux.just(user + "-订单1", user + "-订单2", user + "-订单3");
    })
    .subscribe(System.out::println);
// 输出（注意顺序可能交错，因为 flatMap 不保证顺序）:
// 用户A-订单1
// 用户A-订单2
// 用户B-订单1
// 用户A-订单3
// 用户B-订单2
// 用户B-订单3
```

#### `Flux<R> .concatMap(Function<T, Flux<R>>)` — 一对多展开（保持顺序）

`.concatMap` 将每个源元素映射为内层 Publisher，但串行订阅——必须等前一个元素的内层流完成后才处理下一个元素。它以牺牲并行为代价换取严格的顺序保证。适合需要顺序性可靠大于吞吐的场景，如工具调用的顺序执行。在 LyClaw 的 `DefaultReActEngine.emitRoundToolCallEvents()` 中用于确保每个工具调用事件的严格有序。

```java
// 和 flatMap 的区别：concatMap 严格保证顺序
// 用途：第一个元素的所有子元素都处理完，才处理第二个元素的子元素

Flux.just("用户A", "用户B")
    .concatMap(user -> {
        return Flux.just(user + "-订单1", user + "-订单2", user + "-订单3");
    })
    .subscribe(System.out::println);
// 输出（严格有序）:
// 用户A-订单1
// 用户A-订单2
// 用户A-订单3
// 用户B-订单1
// 用户B-订单2
// 用户B-订单3
```

**LyClaw 中的实际应用**（`DefaultReActEngine.emitRoundToolCallEvents()`）：

```java
// 逐个按顺序执行工具调用，每个工具完成后才执行下一个
return Flux.fromIterable(toolCalls)      // Flux<ToolCallRequest>
    .concatMap(req -> {                   // 保持顺序：工具1执行完 → 工具2执行完 → ...
        // 为每个工具调用产生: executing事件 → done事件
        return Flux.just(executingEvent).concatWith(doneEvent);
    });
```

#### `Flux<R> .handle(BiConsumer<T, SynchronousSink<R>>)` — 逐元素处理（可变输出）

`.handle` 是比 `.map` 和 `.filter` 更灵活的组合：每个元素可以通过 `sink.next(value)` 发射 0 次、1 次或多次，还可以通过 `sink.complete()` 提前终止流。它在调用者线程上同步执行，保持顺序。当处理逻辑需要同时过滤、转换和提前终止时，用单个 `.handle` 替代 `filter + map` 组合更高效。在 LyClaw 中广泛用于根据流状态进行分流和缓冲控制。

```java
// 场景：根据条件决定"发不发"、"发几个"
Flux.just(1, 2, 3, 4, 5, 6, 7, 8)
    .handle((number, sink) -> {
        if (number > 5) {
            sink.complete();  // 大于5时终止整个流
        } else if (number % 2 == 0) {
            sink.next(number * 10);  // 偶数：发射10倍值
        }
        // 奇数：不发射任何东西，继续下一个
    })
    .subscribe(System.out::println);
// 输出:
// 20
// 40
// （流在 6 处终止，不再发射7、8）
```

**LyClaw 中最典型的应用** — `DefaultReActEngine` 的分流逻辑：

```java
model.stream(request)
    .<ServerSentEvent<String>>handle((chunk, sink) -> {
        // 根据状态决定如何发射
        if (state == 0) {
            // 缓冲阶段：不发射给前端，存入 buffer
            buffer.add(chunk);
        } else if (state == 1) {
            // 透传阶段：有文本就发射 message 事件
            if (hasContent) sink.next(sseEvent("message", chunk.getContent()));
        } else {
            // 收集工具调用碎片
            buffer.add(chunk);
        }
    });
```

#### `Mono<List<T>> .collectList()` — 收集所有元素为一个 List

`.collectList` 将 Flux 的所有元素收集到一个 `List<T>` 中，流完成后作为 `Mono<List<T>>` 一次性发射。它需要将全部元素缓存在内存中，只能用于有限流。测试中最常见的模式是 `.collectList().block()`。对于期望收集为 Map 的场景，用 `.collectMap`；对于需排序的列表，用 `.collectSortedList`。

```java
Mono<List<Integer>> listMono = Flux.just(1, 2, 3, 4, 5)
    .collectList();

List<Integer> result = listMono.block();
System.out.println(result);  // 输出: [1, 2, 3, 4, 5]
```

#### `Flux<R> .flatMapSequential(Function<T, Publisher<R>>)` — 一对多展开（保持顺序，支持并发）

`.flatMapSequential` 在内层流上并发订阅（像 `flatMap`），但按源元素顺序收集结果（像 `concatMap`）。它绕过了无序的痛点：内层流并行执行获得速度，最终输出顺序严格和源顺序一致。是 `flatMap`（快但无序）与 `concatMap`（有序但慢）之间的平衡选择。

```java
// flatMap vs concatMap vs flatMapSequential 对比:
// flatMap:           并发订阅内层流，不保证顺序 → 最快
// concatMap:         串行订阅内层流，严格保序   → 保证顺序但慢
// flatMapSequential: 并发订阅内层流，按原顺序收集 → 并发+有序

Flux.just("用户A", "用户B")
    .flatMapSequential(user -> {
        return Flux.just(user + "-订单1", user + "-订单2");
    })
    .subscribe(System.out::println);
// 输出（始终）: 用户A-订单1 用户A-订单2 用户B-订单1 用户B-订单2
// 但内层流是并发订阅的，比 concatMap 快
```

#### `Flux<T> .distinct()` / `Flux<T> .distinctUntilChanged()` — 去重

`.distinct` 全局去重——维护已见过元素的内部集合，任何重复元素都不再发射。`.distinctUntilChanged` 只去掉连续重复，非连续重复保留。前者需要内存存储历史数据（可自定义 key extractor 控制去重键），后者只需比较前一个元素。流很大时优先 `.distinctUntilChanged` 避免内存问题。

```java
Flux.just(1, 2, 2, 3, 1, 4)
    .distinct()
    .subscribe(System.out::println);
// 输出: 1 2 3 4（第2个2和第2个1被丢弃）

// distinctUntilChanged: 只去掉连续重复，非连续重复保留
Flux.just(1, 1, 2, 2, 1, 1)
    .distinctUntilChanged()
    .subscribe(System.out::println);
// 输出: 1 2 1（只有相邻重复的被合并）

// 也可以自定义去重键
Flux.just("apple", "banana", "avocado")
    .distinct(s -> s.charAt(0))  // 按首字母去重
    .subscribe(System.out::println);
// 输出: apple banana（avocado首字母也是a，被丢弃）
```

#### `Mono<List<T>> .collectSortedList()` / `.sort()` — 排序

`.collectSortedList` 收集所有元素后排序，返回 `Mono<List<T>>`。`.sort` 是 `.collectSortedList().flatMapMany(Flux::fromIterable)` 的简写，重新展开排序后的元素为 Flux。两者都需要缓存所有元素（非实时流排序），不适合无限流。元素必须实现 `Comparable` 或提供自定义 `Comparator`。

```java
Mono<List<Integer>> sorted = Flux.just(3, 1, 4, 1, 5)
    .collectSortedList();
// block 后得到: [1, 1, 3, 4, 5]

// sort(): 对流中元素排序（需要收集→排序→重发，非实时排序）
Flux.just(5, 2, 9, 1)
    .collectSortedList()
    .flatMapMany(Flux::fromIterable)
    .subscribe(System.out::println);
// 输出: 1 2 5 9
```

#### `Mono<Long> .count()` — 统计元素个数，返回 Mono<Long>

`.count` 统计流中所有元素数量，流完成后作为 `Mono<Long>` 发射。它不修改原始元素的发射，对无限流会永远不完成。对于需要按条件计数的场景，可先 `.filter` 再 `.count`。与 `.collectList().map(List::size)` 不同，它不需要缓存元素本身，内存占用恒定为计数器。

```java
Long num = Flux.just("a", "b", "c", "d", "e")
    .filter(s -> s.compareTo("c") >= 0)  // c, d, e
    .count()
    .block();
System.out.println(num);  // 输出: 3
```

#### `Mono<T> .reduce(BiFunction)` / `Flux<T> .scan(BiFunction)` — 累积操作

`.reduce` 对整个流进行折叠累加，流完成后将最终累积值作为 `Mono<T>` 一次性发射。`.scan` 的签名类似但每收到一个元素就发射一次当前累积值，返回 `Flux<T>`（适合展示"滚动"状态，如累计和）。两者都是同步逐元素计算，`reduce` 可带初始值，否则用第一个元素作为初始值。

```java
Mono<Integer> sum = Flux.just(1, 2, 3, 4)
    .reduce((a, b) -> a + b);   // 相当于 sum
// block 后得到: 10

// reduce(initial, accumulator): 带初始值
Integer product = Flux.just(1, 2, 3, 4)
    .reduce(1, (a, b) -> a * b)  // 1*1*2*3*4
    .block();
System.out.println(product);  // 输出: 24

// scan: 每接收一个元素就发射一次中间累积值，返回 Flux<T>
Flux.just(1, 2, 3, 4)
    .scan((a, b) -> a + b)
    .subscribe(System.out::println);
// 输出: 1 3 6 10（1=1, 1+2=3, 3+3=6, 6+4=10）
```

#### `Flux<T> .defaultIfEmpty(T)` — 空流时给默认值

`.defaultIfEmpty` 在源流完成且未发射任何元素时，发射一个默认值。与 `.switchIfEmpty` 的区别：前者提供单个值，后者提供备用的整个 Publisher。它适用于需要确保下游至少收到一个值的场景，如过滤后可能为空但需要占位数据。

```java
Flux.empty().defaultIfEmpty("默认").subscribe(System.out::println);
// 输出: 默认

Flux.just("真实").defaultIfEmpty("默认").subscribe(System.out::println);
// 输出: 真实
```

#### `Flux<List<T>> .buffer(int)` / `Flux<Flux<T>> .window(int)` — 批处理

`.buffer` 将元素按最大数量（或时间、Predicate 等边界）切割为 `List<T>` 批次，返回 `Flux<List<T>>`。`.window` 类似但返回 `Flux<Flux<T>>`——每个窗口是独立的子流。`buffer` 适合需要按批次处理数据（如批量写入数据库），`window` 适合需要每个窗口单独订阅和操作（如路由到不同处理器）的场景。

```java
Flux.range(1, 6)
    .buffer(2)
    .subscribe(list -> System.out.println("批次: " + list));
// 输出:
// 批次: [1, 2]
// 批次: [3, 4]
// 批次: [5, 6]

// buffer(Duration): 按时间窗口缓冲
Flux.range(1, 100)
    .buffer(Duration.ofMillis(500))
    .subscribe(list -> System.out.println("500ms批次: " + list.size()));

// window: 和 buffer 类似，但返回 Flux<Flux<T>>
Flux.range(1, 6)
    .window(2)
    .subscribe(window -> window.collectList().subscribe(System.out::println));
```

#### `Flux<GroupedFlux<K,T>> .groupBy(Function<T,K>)` — 分组

`.groupBy` 按 keyMapper 将元素路由到不同分组，返回 `Flux<GroupedFlux<K,T>>`——每个 `GroupedFlux` 是一个按键分组的子流（通过 `.key()` 获取键）。分组是实时的（不需等待流完成），适合数据路由和实时聚合。注意 groupBy 创建的组数无上限，可能导致内存问题，可用 `flatMap(group -> group.take(n))` 限制每个组的元素数。

```java
Flux.just("apple", "banana", "avocado", "blueberry")
    .groupBy(s -> s.substring(0, 1))  // 按首字母分组
    .flatMap(group -> group.collectList()
        .map(list -> group.key() + ": " + list))
    .subscribe(System.out::println);
// 输出（顺序不定）:
// a: [apple, avocado]
// b: [banana, blueberry]
```

#### `Flux<T> .takeLast(int)` / `Flux<T> .skip(int)` / `Flux<T> .skipLast(int)` — 截取与跳过

`.take(n)` 实时取前 n 个，`.takeLast(n)` 需流完成后才发射最后 n 个（内部缓存最后 n 个元素）。`.takeUntil` 一直取直到条件满足（含条件元素），`.takeWhile` 当条件成立时取（条件元素不取）。`.skip(n)` 丢弃前 n 个立即发射剩余，`.skipLast(n)` 需缓存全部后发射。选择根据是否需要缓存和历史数据：`take`/`skip` 系列实时，`takeLast`/`skipLast` 系列需缓存。

```java
// takeLast(n): 只取最后 n 个（流完成后才发射）
Flux.range(1, 10).takeLast(3).subscribe(System.out::println);
// 输出: 8 9 10

// takeUntil(predicate): 一直取直到条件满足（条件元素本身也输出）
Flux.just(1, 2, 3, 4, 5)
    .takeUntil(n -> n >= 3)
    .subscribe(System.out::println);
// 输出: 1 2 3

// takeWhile(predicate): 一直取当条件满足（条件不满足时立即停止，不输出该元素）
Flux.just(1, 2, 3, 4, 5)
    .takeWhile(n -> n < 3)
    .subscribe(System.out::println);
// 输出: 1 2

// skip(n): 跳过前 n 个元素
Flux.range(1, 10).skip(7).subscribe(System.out::println);
// 输出: 8 9 10

// skipLast(n): 跳过最后 n 个
Flux.range(1, 5).skipLast(2).subscribe(System.out::println);
// 输出: 1 2 3
```

#### `Mono<T> .elementAt(int)` / `Mono<T> .single()` — 提取单个元素

`.elementAt(index)` 提取指定索引位置的元素返回 `Mono<T>`（0-indexed），越界则发出 `IndexOutOfBoundsException`，可用 `elementAtOrDefault` 提供默认值。`.single()` 断言流中恰好只有一个元素——多于一个会报错，少于一个则不完成。两者都需要缓存或等待前面的元素，不适合无限流。

```java
// elementAt(index): 取第 index 个元素，返回 Mono<T>
Mono<Integer> third = Flux.range(1, 10).elementAt(2);  // 0-indexed
System.out.println(third.block());  // 输出: 3

// elementAtOrDefault(index, default): 越界时给默认值
String result = Flux.just("A").elementAt(5, "Z").block();
System.out.println(result);  // 输出: Z

// single(): 断言流中只有一个元素，否则报错，返回 Mono<T>
Flux.just("only").single().subscribe(System.out::println);  // 输出: only
// Flux.just("A", "B").single();  // 抛 IndexOutOfBoundsException
```

#### `Flux<T> .repeat(int)` — 重复订阅

`.repeat(n)` 在源流完成后重新订阅 n 次，总共发射 n+1 遍数据。适合需要周期性执行的简单重试（无错误时）。与 `.repeatWhen`（动态控制重复间隔和次数）和 `.retry`（错误时重新订阅）不同，`.repeat` 只在成功完成后重复。注意源流必须是有界的——无限流永远不会完成，`.repeat` 永远不触发。

```java
Flux.just("ping")
    .repeat(2)  // 重复 2 次 = 共执行 3 次
    .subscribe(System.out::println);
// 输出: ping ping ping

// repeatWhen: 动态控制重复次数和延迟
Flux.just("ping")
    .repeatWhen(companion -> companion.delayElements(Duration.ofSeconds(1)).take(3))
    .subscribe(System.out::println);
```

#### `Flux<R> .cast(Class<R>)` / `Flux<T> .ofType(Class<R>)` — 类型转换

`.cast` 强制将每个元素转换为目标类型，类型不匹配时抛 `ClassCastException`。`.ofType` 只保留能安全转换为目标类型的元素，不匹配的静默丢弃。前者适合类型体系确定安全（如 `Flux<Number>` 转 `Flux<Integer>` 已知全为 Integer），后者适合异构流的安全过滤。两者都是同步操作。

```java
Flux<Number> numbers = Flux.just(1, 2.0, 3L);
// cast: 强制转换，类型不匹配时抛 ClassCastException
Flux<Integer> ints = numbers.cast(Integer.class);  // 危险，2.0 是 Double

// ofType: 只保留匹配类型的元素（安全过滤）
numbers.ofType(Integer.class).subscribe(System.out::println);  // 输出: 1
```

#### `Flux<Tuple2<Long,T>> .index()` / `Flux<Tuple2<Long,T>> .elapsed()` / `Flux<Tuple2<Long,T>> .timestamp()` — 元数据方法

`.index` 为每个元素附加从 0 开始的递增索引（`Tuple2<Long,T>`，T1 是索引，T2 是元素）。`.elapsed` 测量相邻元素之间的时间间隔。`.timestamp` 为每个元素附加当前时间戳。三者都不改变原始数据，而是将其包装为 Tuple2 流。适合调试、日志、性能分析等非核心数据流场景，不影响下游处理。

```java
// index: 给每个元素附加索引 (0, 1, 2, ...)
Flux.just("a", "b", "c")
    .index()
    .subscribe(t -> System.out.println(t.getT1() + ": " + t.getT2()));
// 输出: 0: a  1: b  2: c

// elapsed: 测量相邻元素之间的时间间隔
Flux.interval(Duration.ofMillis(100)).take(3)
    .elapsed()
    .subscribe(t -> System.out.println(t.getT1() + "ms → " + t.getT2()));
// 输出: 103ms → 0  101ms → 1  99ms → 2

// timestamp: 给每个元素附加时间戳（毫秒）
Flux.just("A").timestamp().subscribe(System.out::println);
```

### 5.3 组合操作

#### `Flux<T> .concatWith(Publisher)` — 拼接两个流

`.concatWith` 将当前流和另一个 Publisher 串行拼接：当前流的所有元素发完后，才订阅并发射 other 的元素。严格保证顺序。与 `.mergeWith`（并发交错）和 `.thenMany`（丢弃当前流数据）的语义不同。在 LyClaw 中广泛用于管道阶段串联和 SSE 事件的前后拼接。

```java
Flux<String> first = Flux.just("A", "B", "C");
Flux<String> second = Flux.just("D", "E");
Flux<String> combined = first.concatWith(second);
combined.subscribe(System.out::println);
// 输出: A B C D E（严格按顺序，first 全部发完才发 second）
```

**LyClaw 中最常见的模式**：

```java
// 管道阶段串联：上一个阶段完成后执行下一个阶段
Flux<ServerSentEvent<String>> pipeline = Flux.empty();
for (ReactivePipelineStage stage : stages) {
    pipeline = pipeline.concatWith(
        Flux.defer(() -> stage.execute(context, pipelineCtx))
    );
}

// 响应阶段串联：前缀事件 → 响应体 → 错误处理
return Flux.just(sseEvent("respond_start", "Generating AI response"))
    .concatWith(bodyFlux)
    .onErrorResume(err -> Flux.just(sseEvent("error", err.getMessage())));
```

#### `Flux<R> .thenMany(Publisher)` — 忽略当前流数据，切换到新流

`.thenMany` 在当前流完成后丢弃其所有数据，只发射 other 的数据。与 `.concatWith`（保留当前数据再附上 other）不同，它适用于"前一个操作完成后执行下一个"的管道模式。在 LyClaw 的 `RetryChatModel` 中，`Mono.delay(...).thenMany(streamWithRetry(...))` 用延迟后的 Mono 完成信号触发重试流。

```java
// 和 concatWith 的区别:
// concatWith: 发射当前数据 + other 数据
// thenMany:   丢弃当前数据，只发射 other 的数据

Flux<String> source = Flux.just("丢弃1", "丢弃2");
Flux<String> target = Flux.just("新1", "新2");

source.concatWith(target).subscribe(s -> System.out.println("concatWith: " + s));
// 输出:
// concatWith: 丢弃1
// concatWith: 丢弃2
// concatWith: 新1
// concatWith: 新2

source.thenMany(target).subscribe(s -> System.out.println("thenMany: " + s));
// 输出:
// thenMany: 新1
// thenMany: 新2
```

**LyClaw 中的实际应用**（`RetryChatModel` 重试）：

```java
// 延迟后递归重试，thenMany 丢弃延迟 Mono 的数据（0L），切换到重试流
return Mono.delay(Duration.ofMillis(delay))
    .thenMany(streamWithRetry(request, attempt + 1));
```

#### `Flux<T> .mergeWith(Publisher)` — 并发合并两个流

`.mergeWith` 并发合并当前流和 another Publisher，数据按实际到达时间交错发射，不保证顺序。与 `.concatWith`（串行保序）相反，它适合多源数据并发的场景。两个流各自独立执行，一个报错则整个合并流报错。

```java
// mergeWith vs concatWith:
// mergeWith:  并发订阅，数据按到达顺序交错（不保证顺序）
// concatWith: 串行订阅，第一个流完成后才订阅第二个（严格有序）

Flux<String> fast = Flux.just("F1", "F2").delayElements(Duration.ofMillis(10));
Flux<String> slow = Flux.just("S1", "S2").delayElements(Duration.ofMillis(30));

fast.mergeWith(slow).subscribe(System.out::println);
// 输出: F1 S1 F2 S2（大约，每次可能不同）
```

#### `Flux<R> .zipWith(Publisher, BiFunction)` — 按位置配对

`.zipWith` 将当前流与另一个 Publisher 按索引位置一一配对，通过 zipper 函数组合结果。结果流长度取两者中较短的。与 `.mergeWith`（动态交错）和 `.combineLatest`（任一更新都触发）不同，`zipWith` 等待配对元素都就绪才发射。搭配 `.zipWhen` 可动态生成配对的内层流。

```java
Flux<String> names = Flux.just("张三", "李四");
Flux<Integer> scores = Flux.just(90, 85);

names.zipWith(scores, (name, score) -> name + "得分: " + score)
    .subscribe(System.out::println);
// 输出: 张三得分: 90  李四得分: 85

// zipWhen: 用当前元素生成配对流，动态决定配什么
Flux.just("user1", "user2")
    .zipWhen(user -> Flux.just(user + "-profile", user + "-settings"))
    .subscribe(t -> System.out.println(t.getT1() + " → " + t.getT2()));
// 输出: user1 → user1-profile  user2 → user2-settings
```

#### `Flux<R> .combineLatest(BiFunction)` — 组合最新值

`Flux.combineLatest` 在任一源流发射新值时，取所有源流的最新值组合后发射。与 `zip`（严格按位置配对，一一对应）不同，`combineLatest` 每次新值都触发组合。静态方法的形式为 `Flux.combineLatest(flux1, flux2, (a,b) -> ...)`。实例方法 `.withLatestFrom` 是变体——只在主流发射时取副流的最新值组合。

```java
// combineLatest: 任一流发射新值时，取其与另一流的最新值组合
// 和 zip 的区别: zip 严格配对；combineLatest 每次新值都触发

// 静态方法: Flux.combineLatest(flux1, flux2, (a,b) -> ...)
Flux<String> prices = Flux.just("10", "12").delayElements(Duration.ofMillis(50));
Flux<String> rates = Flux.just("1.1", "1.2").delayElements(Duration.ofMillis(80));

Flux.combineLatest(prices, rates, (price, rate) ->
    "价格" + price + ", 汇率" + rate)
    .subscribe(System.out::println);
// 输出（约）: 价格10,汇率1.1  价格12,汇率1.1  价格12,汇率1.2

// withLatestFrom: 只在主流产时组合，副流只取最新值
Flux.interval(Duration.ofMillis(100)).take(3)
    .withLatestFrom(Flux.interval(Duration.ofMillis(30)).take(10),
        (tick, fast) -> "tick=" + tick + " fast=" + fast)
    .subscribe(System.out::println);
// 输出: tick=0 fast=2  tick=1 fast=5  tick=2 fast=8
```

#### `Flux<T> .startWith(T... values)` — 在流开头插入元素

`.startWith` 在流开头插入指定值（或 Publisher），先发射这些前缀数据，再发射原流数据。它等价于 `Flux.just(values).concatWith(original)`。适合为流添加启动事件、初始化信息或默认头部。支持可变参数值或 Publisher 作为前缀。

```java
Flux.just("3", "4")
    .startWith("1", "2")
    .subscribe(System.out::println);
// 输出: 1 2 3 4

// 也支持 Publisher
Flux.just("处理结果")
    .startWith(Flux.just("开始", "初始化"))
    .subscribe(System.out::println);
```

#### `Mono<Void> .then()` / `Mono<Void> .thenEmpty(Publisher)` / `Mono<R> .thenReturn(R)` — 完成后切换

`.then()` 在当前流完成后返回 `Mono<Void>`——丢弃所有数据，只发完成信号。`.thenReturn(value)` 完成后发射指定值。`.thenEmpty(other)` 完成后切换到另一个 Mono。它们都只关心完成信号，适合在副作用或步骤完成后触发下一步。与 `.thenMany`（切换到 Flux）互补。

```java
// then(): 当前流完成后返回 Mono<Void>（完全不关心数据）
Mono<Void> done = Flux.just("a", "b", "c")
    .then();  // 丢弃所有数据，只发完成信号

// thenReturn(value): 完成后返回单个值
Mono<String> result = Flux.just("a", "b", "c")
    .thenReturn("处理完成");
System.out.println(result.block());  // 输出: 处理完成

// thenMany: 完成后切换到另一个 Flux（前面已讲）
// thenEmpty: 完成后切换到另一个 Mono<Void>
```

### 5.4 获取 Flux 的结果

#### `T .blockFirst()` / `T .blockLast()` — 阻塞获取元素

`.blockFirst()` 阻塞当前线程直到流发射第一个元素并返回该元素，然后取消订阅取消剩余元素。`.blockLast()` 阻塞等待流完成，返回最后一个元素。两者都是脱离响应式管道的同步方法，只在测试、main 方法或集成边界使用，生产代码应始终使用非阻塞订阅。如果流为空，`blockFirst` 和 `blockLast` 返回 null。

```java
Integer first = Flux.just(10, 20, 30).blockFirst();
System.out.println(first);  // 输出: 10

Integer last = Flux.just(10, 20, 30).blockLast();
System.out.println(last);  // 输出: 30
```

#### `List<T> .collectList().block()` — 收集为 List 并阻塞

`.collectList().block()` 是测试中最常用的模式：先用 `.collectList()` 将所有元素收集为 `Mono<List<T>>`，再用 `.block()` 阻塞等待完成获取 `List<T>`。它只能用于有限流（无限流会永久阻塞）。生产代码中应使用非阻塞的 `.collectList().subscribe()`。对于只想取前几个元素的场景，用 `.take(n).collectList().block()` 防止无限等待。

```java
// 这是测试中最常用的模式
List<Integer> result = Flux.just(1, 2, 3, 4, 5)
    .map(n -> n * 10)
    .filter(n -> n > 20)
    .collectList()
    .block();  // 收集所有元素并阻塞等待

System.out.println(result);  // 输出: [30, 40, 50]
```

#### `Flux<T> .take(long n)` — 只取前 n 个元素

`.take(n)` 对取前 n 个元素后自动取消订阅剩余元素。它实时工作，不缓存流。常用于限制无限流（如 `Flux.interval`）、测试中取前几个事件、或分页场景。与 `.takeLast(n)`（需等待流完成后发射最后 n 个）和 `.skip(n)`（跳过前 n 个）互补。

```java
Flux.just(1, 2, 3, 4, 5)
    .take(3)
    .subscribe(System.out::println);
// 输出:
// 1
// 2
// 3
// （4, 5 被丢弃）

// 常用于测试：只收集前面几个事件
List<ServerSentEvent<String>> first3 = flux
    .take(3)
    .collectList()
    .block();
```

#### `Stream<T> .toStream()` / `Iterable<T> .toIterable()` — 转换为 Java 集合

`.toStream()` 和 `.toIterable()` 都是阻塞操作，等待流完成后将全部元素转换为 Java 原生集合。它们底层实现等同于 `.collectList().block()`，不适合生产环境中的无限流。主要用于桥接 Reactor 代码与遗留 Java API，或在主方法中快速测试。生产代码应优先使用 `.subscribe()` 或 StepVerifier。

```java
// toStream(): 阻塞获取所有元素转为 Java Stream，返回 Stream<T>
Stream<Integer> stream = Flux.just(1, 2, 3)
    .toStream();  // 阻塞，等待流完成后返回
stream.forEach(System.out::println);  // 输出: 1 2 3

// toIterable(): 阻塞获取所有元素转为 Iterable，返回 Iterable<T>
Iterable<Integer> iterable = Flux.just(1, 2, 3)
    .toIterable();  // 阻塞
iterable.forEach(System.out::println);  // 输出: 1 2 3

// 注意: toStream 和 toIterable 都会阻塞等待流完成，不适合生产环境
// 它们的底层实现就是 collectList().block()
```

#### `Mono<Boolean> .hasElements()` / `Mono<Boolean> .any(Predicate)` / `Mono<Boolean> .all(Predicate)` — 条件判断

`.hasElements()` 返回 `Mono<Boolean>`，流中有任何元素则为 true（第一个元素到达即可完成，无需等流结束）。`.any(Predicate)` 有任一元素满足条件则为 true，同样短路。`.all(Predicate)` 需所有元素满足条件，必须等流完成才能返回 true（一旦失败立即为 false）。它们都适合条件验证和断言场景，返回 Mono 可继续链式响应式操作。

```java
// hasElements(): 是否有任何元素，返回 Mono<Boolean>
Boolean hasAny = Flux.just(1, 2).hasElements().block();    // true
Boolean empty = Flux.empty().hasElements().block();         // false

// any(predicate): 是否有任一元素满足条件，返回 Mono<Boolean>
Boolean hasEven = Flux.just(1, 2, 3)
    .any(n -> n % 2 == 0)
    .block();  // true（因为有 2）

// all(predicate): 是否所有元素都满足条件，返回 Mono<Boolean>
Boolean allPositive = Flux.just(1, 2, 3)
    .all(n -> n > 0)
    .block();  // true

Boolean allEven = Flux.just(1, 2, 3)
    .all(n -> n % 2 == 0)
    .block();  // false
```

#### `Mono<Map<K,V>> .collectMap(Function<T,K>, Function<T,V>)` — 收集为 Map

`.collectMap` 将 Flux 元素收集为 `Map<K,V>`——需提供 keyMapper（键提取函数）和 valueMapper（值提取函数）。流完成后返回 `Mono<Map<K,V>>`。键重复时默认后值覆盖前值。对于一对多收集（Key -> List<V>），用 `.collectMultimap`。和 `.collectList` 一样，需要缓存全量数据，只能用于有限流。

```java
Map<String, Integer> map = Flux.just("apple", "banana", "cherry")
    .collectMap(s -> s, String::length)  // 键=水果名, 值=长度
    .block();
System.out.println(map);  // {apple=5, banana=6, cherry=6}

// collectMultiMap: 一对多收集，Key → List<V>
Flux.just("apple", "banana", "avocado")
    .collectMultimap(s -> s.substring(0, 1))  // 按首字母分组
    .block();
// → {a=[apple, avocado], b=[banana]}
```

#### `<E extends Subscriber<T>> E .subscribeWith(E subscriber)` — 订阅并返回 Subscriber

`.subscribeWith` 订阅并返回传入的 Subscriber 本身（类型 E），便于链式继续访问 Subscriber 的方法（如 StepVerifier 的 `.expectNext().verifyComplete()`）。与 `.subscribe()`（返回 Disposable）不同，它返回 Subscriber 是为了方便测试验证或后续获取累积结果。

```java
// 常用于测试，代替 block
StepVerifier verifier = Flux.just("a", "b")
    .subscribeWith(StepVerifier.create(2))
    .expectNext("a", "b")
    .verifyComplete();
```

### 5.5 练习题

**题目2**：下面两种方式有何区别？

```java
// 方式A
Flux<String> a = Flux.just("a", "b")
    .map(s -> s.toUpperCase());

// 方式B
Flux<String> b = Flux.just("a", "b")
    .flatMap(s -> Mono.just(s.toUpperCase()));
```

在方式 B 中，如果改成 `flatMap(s -> Mono.just(s.toUpperCase()).delayElement(Duration.ofMillis(100)))` 输出顺序会变吗？

**题目3**：用 `Flux.merge` 合并两个流，观察输出顺序。

```java
Flux<String> stream1 = Flux.just("A1", "A2", "A3")
    .delayElements(Duration.ofMillis(30));   // 每30ms发一个
Flux<String> stream2 = Flux.just("B1", "B2", "B3")
    .delayElements(Duration.ofMillis(20));   // 每20ms发一个

// 请写出合并代码，并预测输出顺序
```

<details>
<summary>点击查看答案与解析</summary>

**题目1答案：**

```java
Flux.range(1, 20)                // 1,2,3,...,20
    .filter(n -> n % 2 == 0)     // 2,4,6,...,20（10个偶数）
    .map(n -> n * n)             // 4,16,36,...,400（偶数的平方）
    .take(5)                     // 只取前5个: 4,16,36,64,100
    .subscribe(System.out::println);

// 运行结果:
// 4
// 16
// 36
// 64
// 100
```

**解析**：链式调用构成了数据处理的流水线。每个操作符处理完一个元素后立即传给下一个操作符（不是等全部 filter 完再全部 map），这是 Flux 的**惰性逐元素处理**特性。

**题目2答案：**

两种方式结果相同（都输出 A, B），但内部机制不同：
- `map`：同步转换，直接用当前线程执行 `toUpperCase()`
- `flatMap`：将每个元素包装成 Mono，然后展平。虽然结果一样，但引入了不必要的 Mono 包装，性能稍差

```java
// 加了 delayElement 后：flatMap 版本顺序不保证
Flux.just("a", "b")
    .flatMap(s -> Mono.just(s.toUpperCase())
        .delayElement(Duration.ofMillis(100)))  // 每个都延迟100ms
    .subscribe(System.out::println);
// 可能输出: A B 或 B A（取决于调度）
```

**解析**：`flatMap` 会**并发订阅**所有内层流，各内层流之间顺序不保证。虽然这里延迟相同，但 JVM 调度可能导致微小的顺序差异。如需严格保序，用 `concatMap`。对于纯同步转换，始终用 `map` 而非 `flatMap`。

**题目3答案：**

```java
Flux<String> stream1 = Flux.just("A1", "A2", "A3")
    .delayElements(Duration.ofMillis(30));
Flux<String> stream2 = Flux.just("B1", "B2", "B3")
    .delayElements(Duration.ofMillis(20));

Flux.merge(stream1, stream2)
    .subscribe(System.out::println);

// 运行结果（大约）:
// B1 (20ms)
// A1 (30ms)
// B2 (40ms) — stream2每20ms一个
// B3 (60ms)
// A2 (60ms) — stream1每30ms一个
// A3 (90ms)
```

**解析**：`Flux.merge` 将两个流**并发合并**——谁先产生数据谁先出去。stream2 每 20ms 发一个，速度更快，所以 B 系列先到。如果需要交替合并（按订阅顺序轮流取），用 `Flux.zip(stream1, stream2, (a, b) -> a + "|" + b)`。

</details>

---

## 第6章 核心操作符详解

### 6.1 副作用操作符（doOn...）

副作用（Side Effect）指在数据流过时"顺便"做某事，但不改变数据本身。常用于日志、监控、指标收集。

#### `Flux<T> .doOnNext(Consumer<T>)` — 每个元素到达时执行

`doOnNext` 在每个元素流过时执行给定的回调，但不改变数据本身——数据原样向下游透传。它是纯粹的副作用操作符，常用于日志记录、指标收集、调试输出。如果回调抛出异常，流将以错误终止。

```java
Flux.just(1, 2, 3)
    .doOnNext(n -> System.out.println("doOnNext: 即将处理 " + n))
    .map(n -> n * 10)
    .doOnNext(n -> System.out.println("doOnNext: 处理后 " + n))
    .subscribe(result -> System.out.println("订阅者收到: " + result));
// 输出:
// doOnNext: 即将处理 1
// doOnNext: 处理后 10
// 订阅者收到: 10
// doOnNext: 即将处理 2
// doOnNext: 处理后 20
// 订阅者收到: 20
// doOnNext: 即将处理 3
// doOnNext: 处理后 30
// 订阅者收到: 30
```

#### `Flux<T> .doOnComplete(Runnable)` — 流成功完成时执行

`doOnComplete` 在流正常完成（收到 onComplete 信号）时执行给定的回调，不接收任何数据元素。常用于记录流结束日志、关闭资源、发送完成通知。与 `doFinally` 的区别在于它只在成功完成时触发，取消或出错时不会执行。

```java
Flux.just("处理完成")
    .doOnComplete(() -> System.out.println("doOnComplete: 流正常结束"))
    .subscribe(System.out::println);
// 输出:
// 处理完成
// doOnComplete: 流正常结束
```

**LyClaw 中的实际应用**（`AbstractChatModel`）：

```java
return sendNativeRequest(nativeRequest)
    .map(this::parseChunk)
    .doOnComplete(() -> log.debug("{} stream completed", provider()))
    .doOnError(this::handleError);
```

#### `Flux<T> .doOnError(Consumer<Throwable>)` — 流出错时执行

`doOnError` 在流出错时执行给定的回调，接收异常对象但不处理它——异常仍然会继续向下游传播。它纯粹用于副作用（如记录错误日志、发送告警），不能替代 `onErrorReturn` 或 `onErrorResume`。如果需要恢复或降级，请使用错误恢复操作符。

```java
Flux.just(1, 2, 0, 4)
    .map(n -> 10 / n)  // n=3 时 10/0 抛异常
    .doOnError(e -> System.out.println("doOnError: 检测到异常 " + e.getMessage()))
    .subscribe(
        data -> System.out.println("收到: " + data),
        err -> System.out.println("订阅者错误处理: " + err.getMessage())
    );
// 输出:
// 收到: 10
// 收到: 5
// doOnError: 检测到异常 / by zero
// 订阅者错误处理: / by zero
// （注意: doOnError 不处理异常，异常仍然会传播到订阅者）
```

#### `Flux<T> .doFinally(Consumer<SignalType>)` — 流结束时执行（无论成功/失败/取消）

`doFinally` 是覆盖最全的终止回调——无论流以 `onComplete`、`onError` 还是 `cancel` 结束都会触发。回调接收 `SignalType` 枚举值来区分终止原因。它比 `doOnTerminate` 更全（后者不覆盖 cancel），常用于清理资源、清除上下文（如 MDC 清理）。

```java
Flux.just("data")
    .doFinally(signal -> System.out.println("doFinally: 流结束，信号=" + signal))
    .subscribe();
// 输出:
// doFinally: 流结束，信号=ON_COMPLETE

// 错误时也会触发
Flux.error(new RuntimeException("失败"))
    .doFinally(signal -> System.out.println("doFinally: 流失败，信号=" + signal))
    .subscribe(
        data -> {},
        err -> System.out.println("捕获: " + err.getMessage())
    );
// 输出:
// doFinally: 流失败，信号=ON_ERROR
// 捕获: 失败
```

**LyClaw 中的实际应用**（`OrchestratorImpl.execute()` — MDC 清理）：

```java
return pipelineFlux
    .onErrorResume(err -> { ... })
    .doFinally(signalType -> MDC.remove("traceId"));
// 无论管道成功还是失败，都清理 MDC 中的 traceId
```

#### `Flux<T> .doOnSubscribe(Consumer<Subscription>)` — 订阅发生时执行

`doOnSubscribe` 在每次订阅发生时触发，早于任何数据元素的发射。回调接收 `Subscription` 对象，可以检查或包装它，但通常不直接操作。最典型的场景是在订阅时初始化上下文信息（如将 traceId 放入 MDC），确保后续所有操作都能获取到正确的上下文。

```java
Flux.just("data")
    .doOnSubscribe(s -> System.out.println("doOnSubscribe: 有人订阅了"))
    .subscribe(result -> System.out.println("收到: " + result));
// 输出:
// doOnSubscribe: 有人订阅了
// 收到: data

// LyClaw 中的实际应用：MDC 上下文初始化
flux.doOnSubscribe(s -> MDC.put("traceId", context.getTraceId()));
```

#### `Flux<T> .doOnCancel(Runnable)` — 订阅被取消时执行

`doOnCancel` 在订阅被取消时触发——常见原因包括 `take(n)` 取够了、`timeout` 到期、或手动调用 `Disposable.dispose()`。回调不接收任何参数，常用于通知外部系统流已取消或释放与取消相关的资源（如关闭连接、释放锁）。

```java
Flux.interval(Duration.ofMillis(100))
    .doOnCancel(() -> System.out.println("流被取消了"))
    .take(3)  // 取 3 个后自动取消
    .blockLast();
// 输出: 流被取消了

// 典型场景：清理资源（关闭连接、释放锁）
```

#### `Flux<T> .doOnTerminate(Runnable)` — 流终止时执行（成功或失败）

`doOnTerminate` 在流终止（`onError` 或 `onComplete`）信号发出前触发，但不覆盖 `cancel`。与 `doFinally` 的关键区别在于：`doOnTerminate` 在终止信号之前触发，而 `doFinally` 在终止信号之后触发且额外覆盖 `cancel`。选择原则：需要覆盖 cancel 用 `doFinally`，只关心正常/异常终止用 `doOnTerminate`。

```java
Flux.just("正常")
    .doOnTerminate(() -> System.out.println("终止"))
    .doFinally(s -> System.out.println("最终, signal=" + s))
    .subscribe(System.out::println);
// 输出:
// 正常
// 终止
// 最终, signal=onComplete
```

#### `Flux<T> .doOnEach(Consumer<Signal<T>>)` — 每个信号（包括 onNext/onError/onComplete）被发射时执行

`doOnEach` 以 `Signal<T>` 对象的形式接收流中的每一个事件——包括数据（`onNext`）、错误（`onError`）和完成（`onComplete`）。它等价于 `doOnNext` + `doOnComplete` + `doOnError` 三合一，但可以通过 `signal.isOnNext()`、`signal.isOnError()`、`signal.isOnComplete()` 来区分信号类型。适合做统一的事件日志或审计。

```java
Flux.just("A", "B")
    .doOnEach(signal -> {
        if (signal.isOnNext()) System.out.println("收到数据: " + signal.get());
        else if (signal.isOnComplete()) System.out.println("完成");
        else if (signal.isOnError()) System.out.println("错误: " + signal.getThrowable());
    })
    .subscribe();
// 输出: 收到数据: A  收到数据: B  完成
```

#### `Flux<T> .doOnRequest(Consumer<Long>)` — 下游请求数据时执行

`doOnRequest` 在下游通过 `Subscription.request(n)` 请求数据时触发，回调接收请求的数量 `n`。它主要用于背压调试——观察下游的消费速率和请求模式。默认情况下 Flux 的订阅者请求 `Long.MAX_VALUE`（无界请求），只有在使用了 `limitRate` 或 `flatMap(concurrency)` 等限速操作符后才会看到分批请求。

```java
Flux.range(1, 100)
    .doOnRequest(n -> System.out.println("下游请求了 " + n + " 个元素"))
    .subscribe();
// 输出: 下游请求了 9223372036854775807 个元素（Long.MAX_VALUE，即无界请求）
```

#### `Flux<T> .doOnDiscard(Class, Consumer)` — 元素被丢弃时执行

`doOnDiscard` 在元素因过滤、取消或错误被丢弃时触发，回调接收被丢弃的元素。需要指定元素类型（如 `String.class`），只对该类型的丢弃元素生效。典型场景是释放被丢弃元素关联的资源——比如流中途取消时关闭已打开但尚未处理的文件句柄或数据库连接。

```java
Flux.just("open-file1", "other", "open-file2")
    .filter(s -> s.startsWith("open"))
    .doOnDiscard(String.class, s -> System.out.println("关闭: " + s))
    .take(1)
    .subscribe(System.out::println);
// 输出: open-file1  关闭: open-file2（file2被take(1)丢弃）
```

### 6.2 错误恢复操作符

#### `Flux<T> .onErrorReturn(T)` — 出错时返回默认值

`onErrorReturn` 是最简单的错误恢复操作符：出错时用一个固定的默认值替代错误，流正常完成。它不关心异常类型或错误信息，直接给一个静态值。如果需要对不同类型异常做不同处理，使用 `onErrorReturn(Predicate, T)`；如果需要返回多个值或动态计算，使用 `onErrorResume`。注意流在出错后立即终止，出错元素之后的剩余元素不会被处理。

```java
Flux.just(1, 2, 0, 4)
    .map(n -> 10 / n)  // 到 0 时会抛 ArithmeticException
    .onErrorReturn(-1)  // 出错时用 -1 替代
    .subscribe(System.out::println);
// 输出:
// 10
// 5
// -1
// （流在出错后终止，4 不会被处理）
```

#### `Flux<T> .onErrorResume(Function<Throwable, Publisher<T>>)` — 出错时切换到备用流

`onErrorResume` 是功能最丰富的错误恢复操作符：出错时执行一个函数，该函数接收异常对象并返回一个完整的备用 `Publisher`（可以是多个值、异步调用、缓存读取等）。与 `onErrorReturn` 的区别在于它可以动态决定降级策略——根据错误类型、错误信息甚至运行时状态来返回不同的备用流。适合实现复杂的降级逻辑，如切换备用服务、返回缓存数据。

```java
Flux<String> primary = Flux.just("主数据1", "主数据2")
    .concatWith(Flux.error(new RuntimeException("主服务挂了")));

primary.onErrorResume(error -> {
        System.out.println("主服务出错，切换备用: " + error.getMessage());
        return Flux.just("备用数据1", "备用数据2", "备用数据3");
    })
    .subscribe(System.out::println);
// 输出:
// 主数据1
// 主数据2
// 主服务出错，切换备用: 主服务挂了
// 备用数据1
// 备用数据2
// 备用数据3
```

**LyClaw 中的实际应用**（`PipelineStageBase` + `RetryChatModel`）：

```java
// 管线阶段：出错时返回降级的错误事件流
bodyFlux
    .onErrorResume(err -> {
        log.error("阶段失败: {}", err.getMessage());
        return Flux.just(
            sseEvent("message", "服务暂时不可用"),
            sseEvent("done", "{\"status\":\"error\"}")
        );
    });

// 重试装饰器：出错时延迟后重试
delegate.stream(request)
    .onErrorResume(error -> {
        if (attempt < maxAttempts) {
            return Mono.delay(Duration.ofMillis(delay))
                .thenMany(streamWithRetry(request, attempt + 1));
        }
        return Flux.error(error);  // 超过最大重试次数，传播错误
    });
```

#### `Flux<T> .onErrorMap(Function<Throwable, Throwable>)` — 错误类型转换

`onErrorMap` 不处理错误，只将原始异常转换为另一个异常——常用于将底层技术异常（`IOException`、`SQLException`）包装为具有业务语义的自定义异常，便于上层统一处理。它不阻止错误传播，只是改变错误类型。如果需要根绝类型恢复或降级，请使用 `onErrorReturn` 或 `onErrorResume`。

```java
Mono.fromCallable(() -> Files.readAllLines(Path.of("missing.txt")))
    .onErrorMap(e -> new RuntimeException("文件读取失败，请检查路径", e))
    .subscribe(
        data -> {},
        err -> System.out.println("统一处理: " + err.getMessage())
    );
// 输出: 统一处理: 文件读取失败，请检查路径
```

#### `Flux<T> .onErrorContinue(BiConsumer<Throwable, Object>)` — 出错跳过继续

`onErrorContinue` 的行为与所有其他错误恢复操作符都不同：它不是终止流，而是**跳过出错元素，继续处理下一个元素**。回调接收异常和出错元素，可以记录日志但无法恢复数据——出错元素被直接丢弃。适合批量处理场景（如批量导入中个别记录格式错误不应阻塞整体），但使用时需谨慎：它破坏了 Reactive Streams 的规范语义（错误本应终止流）。

```java
Flux.just(1, 2, 0, 4)
    .map(n -> 10 / n)  // n=0 时会抛 ArithmeticException
    .onErrorContinue((error, element) ->
        System.out.println("跳过元素 " + element + ": " + error.getMessage()))
    .subscribe(System.out::println);
// 输出:
// 10
// 5
// 跳过元素 0: / by zero
// 2  （4=10/4 继续处理）
```

#### `Flux<T> .retry(long)` / `.retryWhen(Retry)` — 出错时重试

`retry(n)` 在出错时立即重试最多 n 次，无延迟，简单但缺乏灵活性。`retryWhen(Retry)` 是推荐的配置化重试方案，支持固定延迟、指数退避、随机抖动、超时等生产级策略。两者区别在于：`retry` 适合快速验证，`retryWhen` 适合生产环境。如果需要全部失败后降级，在 `retryWhen` 后面链式添加 `onErrorReturn`。

```java
// retry(n): 立即重试，无延迟
Mono.fromCallable(() -> {
    System.out.println("尝试...");
    throw new RuntimeException("失败");
}).retry(2)  // 重试 2 次 = 总共执行 3 次
    .subscribe(
        data -> {},
        err -> System.out.println("最终失败: " + err.getMessage())
    );
// 输出: 尝试... 尝试... 尝试... 最终失败: 失败

// retryWhen(Retry): 配置化重试（推荐），支持延迟、退避、超时等策略
Mono.fromCallable(() -> {
    System.out.println("尝试...");
    if (Math.random() > 0.3) throw new RuntimeException("失败");
    return "成功";
}).retryWhen(Retry.fixedDelay(3, Duration.ofMillis(500)))
    // 最多重试3次，每次间隔500ms
    .subscribe(System.out::println);

// 指数退避重试（生产环境推荐）
.retryWhen(Retry.backoff(5, Duration.ofSeconds(1))
    .maxBackoff(Duration.ofSeconds(30))
    .jitter(0.5))  // 加入随机抖动，防止雪崩
```

#### `Flux<T> .timeout(Duration)` / `.timeout(Duration, Publisher)` — 超时控制

`timeout(Duration)` 在指定时间内没有收到任何信号（`onNext` 或 `onComplete`）时抛出 `TimeoutException`。`timeout(Duration, Publisher)` 的超时行为更友好——超时后切换到备用流而非报错。还可以传入一个函数实现动态超时（根据每个元素计算不同的超时时间）。适合远程调用保护、工具执行超时等场景，常与 `onErrorReturn` 组合使用。

```java
Flux<String> slow = Flux.just("慢数据").delayElements(Duration.ofSeconds(5));

slow.timeout(Duration.ofSeconds(1), Flux.just("超时使用缓存数据"))
    .subscribe(System.out::println);
// 输出（1秒后）: 超时使用缓存数据

// 根据元素动态超时
flux.timeout(elapsed -> Duration.ofSeconds(elapsed * 2));

// LyClaw 场景：工具执行超时保护
Mono.fromCallable(() -> toolExecutor.execute(toolName, id, args))
    .timeout(Duration.ofSeconds(30))
    .onErrorReturn("工具执行超时");
```

#### `Flux<T> .onErrorReturn(Predicate<Throwable>, T)` — 按异常类型返回默认值

与无参 `onErrorReturn(T)` 不同，这个重载版本允许通过谓词筛选异常类型——只有匹配的异常才被替换为默认值，其他类型的异常继续传播。这实现了类似 `catch (SpecificException e)` 的细粒度异常处理，比无差别捕获所有异常更安全。例如只降级 `TimeoutException`，让 `IOException` 继续传播到上层处理。

```java
Flux.just(1, 2, 0, 4)
    .map(n -> 10 / n)  // n=0 → ArithmeticException
    .onErrorReturn(
        e -> e instanceof ArithmeticException,  // 只处理算术异常
        -1                                       // 返回默认值
    )
    .subscribe(System.out::println);
// 输出: 10 5 -1

// 对比 onErrorReturn(T)：无差别的捕获所有异常
// onErrorReturn(Predicate, T)：只捕获指定类型的异常，更安全
// 例如：只降级 ArithmeticException，让 IOException 继续传播
```

#### `Flux<T> .onErrorResume(Predicate<Throwable>, Function<Throwable, Publisher<T>>)` — 按异常类型切换备用流

这个重载版本结合了 `onErrorResume` 的灵活性和按类型匹配的安全性——只有匹配谓词的异常才触发备用流切换。可以链式串联多个 `onErrorResume` 来实现类似 try-catch 的多层异常处理：第一个处理 `FileNotFoundException`，第二个处理 `IOException`，不匹配的异常继续传播。

```java
Mono.fromCallable(() -> readFile("data.txt"))
    .onErrorResume(
        e -> e instanceof FileNotFoundException,  // 文件不存在
        e -> Mono.just("使用默认配置")              // 用默认配置
    )
    .onErrorResume(
        e -> e instanceof IOException,             // 其他IO错误
        e -> Mono.fromCallable(() -> readCacheFallback())
    )
    // 非IOException继续传播到订阅者
    .subscribe(System.out::println);

// 这种链式 onErrorResume 实现了类似 catch 的多层异常处理：
// try { readFile } catch (FileNotFoundException) { ... } catch (IOException) { ... }
```

### 6.3 调度与线程切换

#### `Flux<T> .subscribeOn(Scheduler)` — 指定订阅（及上游操作）的执行线程

`subscribeOn` 指定订阅链和上游操作在哪个 `Scheduler` 上执行——包括 `Flux.just`、`Mono.fromCallable` 等创建操作。它的位置在链中不重要（无论写在哪个位置，都影响上游），但多次调用只有第一个生效。最常见的用法是将阻塞操作（数据库查询、Feign 调用、文件 IO）从 Netty event-loop 线程迁移到 `Schedulers.boundedElastic()`，避免阻塞少数 IO 线程。

```java
```

**LyClaw 中最重要的用法**：

```java
// 将阻塞操作（工具执行、Feign 调用）从 Netty IO 线程迁移到弹性线程池
Mono<ServerSentEvent<String>> doneEvent = Mono.fromCallable(() -> {
    // 这段代码在 boundedElastic 线程池中执行
    // 不会阻塞 Netty 的 event loop 线程
    String output = toolExecutor.execute(toolName, toolCallId, args);
    return sseEvent("tool_call", doneJson);
}).subscribeOn(Schedulers.boundedElastic());

// 整个管道在弹性线程池上运行
return Flux.defer(() -> { ... })
    .subscribeOn(Schedulers.boundedElastic());
```

**什么是 `Schedulers.boundedElastic()`**？

- 一个**有上界的弹性线程池**
- 线程数按需增长（默认上限：CPU 核数 × 10）
- 空闲线程 60 秒后回收
- 专门用于**阻塞操作**：JDBC 查询、Feign 调用、文件 IO
- 避免阻塞 Netty 的少量 event loop 线程

#### `Flux<T> .publishOn(Scheduler)` — 指定下游操作的执行线程

`publishOn` 只影响**其下游**后续操作符的执行线程，上游代码仍在原线程执行。与 `subscribeOn` 的关键区别：`subscribeOn` 影响上游和整个订阅链，`publishOn` 只影响写在它之后的操作符。可以多次调用 `publishOn` 在不同的线程之间切换，实现精细的线程控制。适合将计算密集操作切换到 `Schedulers.parallel()`，或将渲染操作切换到特定线程。

```java
Flux.just("数据")
    .map(s -> {
        System.out.println("map1: " + Thread.currentThread().getName());
        return s;
    })
    .publishOn(Schedulers.boundedElastic())  // ← 从这里开始切换线程
    .map(s -> {
        System.out.println("map2: " + Thread.currentThread().getName());
        return s;
    })
    .subscribe();
// 输出（示例）:
// map1: main
// map2: boundedElastic-1
```

### 6.4 条件操作符

#### `Flux<T> .switchIfEmpty(Publisher)` — 如果当前流为空，切换到备用流

`switchIfEmpty` 在当前流通为空（发出 `onComplete` 但没有发射任何数据）时切换到备用 `Publisher`。与 `defaultIfEmpty` 的区别在于它返回一个完整的流（可以是多个值、可以异步），而 `defaultIfEmpty` 只给一个固定值。适合实现"查主库 → 查缓存 → 返回默认列表"的多级回退策略。

```java
Flux<String> empty = Flux.empty();
empty.switchIfEmpty(Flux.just("默认数据1", "默认数据2"))
    .subscribe(System.out::println);
// 输出:
// 默认数据1
// 默认数据2

// 若原流不为空，则不使用备用流
Flux.just("真实数据").switchIfEmpty(Flux.just("备用"))
    .subscribe(System.out::println);
// 输出: 真实数据
```

#### `Flux<T> .filterWhen(Function<T, Publisher<Boolean>>)` — 异步过滤

`filterWhen` 是 `filter` 的异步版本——谓词返回一个 `Publisher<Boolean>` 而非同步 `boolean`。当过滤条件需要远程查询（如检查权限、查询数据库、调用微服务）时，使用 `filterWhen` 可以保持异步非阻塞。注意它与 `filter` 一样保持元素顺序，内层 Publisher 的完成顺序不影响最终输出顺序。

```java
Flux.just("user1", "user2", "user3")
    .filterWhen(user ->
        Mono.fromCallable(() -> checkPermission(user))  // 异步查询权限
            .subscribeOn(Schedulers.boundedElastic())
    )
    .subscribe(System.out::println);
```

#### `ParallelFlux<T> .parallel()` / `.runOn(Scheduler)` — 并行处理

`.parallel()` 将 `Flux<T>` 转换为 `ParallelFlux<T>`，数据按 round-robin 分配到多个 rail（默认 rail 数 = CPU 核数）。`.runOn(Scheduler)` 指定每个 rail 在哪个调度器上执行，通常配合 `Schedulers.parallel()`（线程池大小 = CPU 核数）使用。最后通过 `.sequential()` 转回普通 `Flux<T>` 合并结果。注意 `parallel` 适合 CPU 密集型计算，不适合阻塞 I/O——阻塞 I/O 应用 `flatMap` + `subscribeOn(Schedulers.boundedElastic())`。

```java
Flux.range(1, 10)
    .parallel()            // 转为 ParallelFlux，默认 rails = CPU 核数
    .runOn(Schedulers.parallel())  // 每个 rail 在不同的 parallel 线程上执行
    .map(i -> {
        System.out.println(Thread.currentThread().getName() + " → " + i);
        return i * 10;
    })
    .sequential()          // 转回普通 Flux，合并所有 rail 的结果
    .subscribe();

// 指定并行度（rails 数量）
Flux.range(1, 100)
    .parallel(4)  // 4 个并行 rail
    .runOn(Schedulers.parallel())
    .sequential()
    .subscribe();

// 注意：parallel 适合 CPU 密集型计算，不适合阻塞 I/O
// 阻塞 I/O 用 flatMap + subscribeOn(Schedulers.boundedElastic())
```

### 6.5 背压操作符

背压（Backpressure）是响应式流的核心概念：当**下游消费速度 < 上游生产速度**时，下游通过 `request(n)` 告诉上游"我只能处理 n 个"，上游据此控制生产速率。以下操作符提供了不同的背压策略。

#### `Flux<T> .limitRate(int prefetchRate)` — 限制每次请求的元素数量

`limitRate` 将下游默认的"一次性全要"（`request(Long.MAX_VALUE)`）改为按批次请求——每次只请求 `prefetchRate` 个元素，处理完后再请求下一批。它通过限制内存中待处理元素的数量来控制背压。也常用于 `flatMap` 的第二个参数来限制内层并发数。适合防止快速生产者压垮慢速消费者的场景。

```java
Flux.range(1, 100)
    .limitRate(10)  // 每次只请求 10 个，处理完再请求下 10 个
    .subscribe(i -> {
        System.out.println("处理: " + i);
        sleep(50);  // 模拟慢消费者
    });
// 内部：先 request(10) → 10个处理完 → request(10) → ...

// limitRate 也用于 flatMap 控制内层并发
Flux.range(1, 100)
    .flatMap(i -> fetchFromDb(i), 5)  // 并发数=5，同时只处理5个
    .subscribe();
```

#### `Flux<T> .onBackpressureBuffer(int maxSize, Consumer<T> overflowHandler)` — 缓冲溢出元素

`onBackpressureBuffer` 在消费者来不及处理时用固定大小的队列缓冲溢出元素。当缓冲区满时触发溢出回调并丢弃元素。无参版本（`onBackpressureBuffer()`）创建无界缓冲——可能导致内存无限膨胀，生产环境应避免。相比 `onBackpressureDrop`，它优先保证数据不丢失（直到缓冲区满），适合允许短暂积压的批处理场景。

```java
Flux.interval(Duration.ofMillis(1))  // 每1ms生产一个（太快了）
    .onBackpressureBuffer(
        50,                           // 最多缓冲50个
        dropped -> log.warn("丢弃: {}", dropped)  // 溢出时回调
    )
    .subscribe(i -> {
        sleep(100);  // 每100ms消费一个（慢10倍）
    });

// onBackpressureBuffer()（无参数，无界缓冲）→ 内存可能无限膨胀，谨慎使用
```

#### `Flux<T> .onBackpressureDrop(Consumer<T> onDrop)` — 丢弃溢出元素

`onBackpressureDrop` 在消费者来不及处理时直接丢弃溢出的元素，不进行缓冲。可选的 `onDrop` 回调接收被丢弃的元素用于日志记录。适合实时数据流场景（如行情推送、传感器读数），丢掉旧的保留最新的通常更合理——此时应用 `onBackpressureLatest` 而非 `onBackpressureDrop`。

```java
Flux.interval(Duration.ofMillis(10))
    .onBackpressureDrop(dropped -> System.out.println("丢弃间隔: " + dropped))
    .subscribe(i -> {
        sleep(500);  // 极慢消费者，大部分数据被丢弃
        System.out.println("保留: " + i);
    });
// 输出: 丢弃间隔: 1  丢弃间隔: 2  ...  保留: 0  丢弃间隔: 50  ...

// 适用场景：实时数据流（行情、传感器），丢掉旧的保留最新的
```

#### `Flux<T> .onBackpressureLatest()` — 只保留最新元素

`onBackpressureLatest` 在消费者来不及处理时丢弃中间元素，但保留最近的一个。当消费者空闲时，它会收到最新的元素而非被丢弃。与 `onBackpressureDrop` 的关键区别：`drop` 丢弃所有溢出的，`latest` 保留最后一个。适合只关心最新状态的场景（如仪表盘、UI 刷新、实时价格显示），不需要处理每一帧数据。

```java
Flux.interval(Duration.ofMillis(10))
    .map(i -> "value-" + i)
    .onBackpressureLatest()  // 始终保留最新的 value
    .subscribe(v -> {
        sleep(1000);
        System.out.println(v);  // 总是拿到最新的值
    });

// 适用场景：只关心最新状态（如UI刷新、仪表盘）
```

#### `Flux<T> .onBackpressureError()` — 溢出时抛异常

`onBackpressureError` 是最严格的背压策略：消费者跟不上时立即抛出 `OverflowException`，终止整个流。适用于必须保证不丢数据且不能无限缓冲的场景——让调用方明确感知到背压问题并自行决定如何处理。这是 Reactor 默认的背压行为（当没有配置其他背压策略时）。

```java
Flux.interval(Duration.ofMillis(1))
    .onBackpressureError()
    .subscribe(i -> sleep(100));
// 快速抛出: reactor.core.Exceptions$OverflowException:
//   The receiver is overrun by more signals than expected
```

### 6.6 冷热发布者与共享

冷的（Cold）：每次订阅都重新执行数据源（如 HTTP 请求、数据库查询）。  
热的（Hot）：所有订阅者共享同一份数据源（如事件总线、WebSocket）。

#### `ConnectableFlux<T> .publish()` / `Flux<T> .autoConnect(int)` / `Flux<T> .refCount(int)` — 冷转热

`.publish()` 将 `Flux<T>` 转为 `ConnectableFlux<T>`，此时数据不会自动发射，需要手动调用 `.connect()` 或使用 `.autoConnect(n)` / `.refCount(n)`。`.autoConnect(n)` 在积累 n 个订阅者后自动 `connect()`，适合需要等待至少一定数量订阅者的场景。`.refCount(n)` 在订阅者数量从 0 变为 1 时自动 `connect()`，在订阅者数量降为 0 时自动 `cancel()`。三者配合实现了冷流到热流的转换——所有订阅者共享同一份数据源，而非每次订阅重复执行。

```java
// publish(): 转为 ConnectableFlux，数据在 connect() 之后才开始发射
ConnectableFlux<Integer> hot = Flux.range(1, 5)
    .publish();  // 转为 ConnectableFlux，此时不会有数据

hot.subscribe(v -> System.out.println("订阅者1: " + v));
hot.subscribe(v -> System.out.println("订阅者2: " + v));
hot.connect();  // ← 此时开始发射数据，两个订阅者同时接收
// 输出: 订阅者1: 1  订阅者2: 1  订阅者1: 2  订阅者2: 2  ...

// autoConnect(minSubscribers): 有 minSubscribers 个订阅者后自动 connect()
Flux<Integer> autoHot = Flux.range(1, 5)
    .publish()
    .autoConnect(2);  // 有2个订阅者时自动开始发射
autoHot.subscribe(v -> System.out.println("A: " + v));
// 此时只有1个订阅者，不会开始
autoHot.subscribe(v -> System.out.println("B: " + v));
// 第2个订阅者到来，自动开始发射

// refCount(minSubscribers): 有订阅者时开始，最后一个订阅者取消时自动 cancel
Flux<Integer> refCountFlux = Flux.range(1, 5)
    .publish()
    .refCount(1);  // 至少1个订阅者时开始，全部取消后停止

Disposable d1 = refCountFlux.subscribe(v -> System.out.println("s1: " + v));
// d1.dispose();  // 全部取消后，上游被 cancel
```

#### `Flux<T> .share()` — 最简共享（publish + refCount(1) 的快捷方式）

`share()` 等价于 `.publish().refCount(1)`：有至少一个订阅者时开始发射数据，所有订阅者取消后自动取消上游。它是将冷流转为热流的最简方式，适合多个订阅者需要共享同一份实时数据源的场景。注意迟到者不会收到历史元素——如果需要历史数据，使用 `cache()` 或 `replay()`。

```java
Flux<Integer> shared = Flux.range(1, 5)
    .delayElements(Duration.ofMillis(500))
    .share();  // 多个订阅者共享同一定时器

// 订阅者A开始接收
Disposable a = shared.subscribe(v -> System.out.println("A: " + v));
// 0.5s后迟到，订阅者B只能收到剩余元素
Thread.sleep(600);
shared.subscribe(v -> System.out.println("B: " + v));
// B 错过了 1，从 2 开始接收（因为是热流，错过的不再重发）
```

#### `Flux<T> .cache(int history)` — 缓存历史元素给延迟订阅者

`cache(int history)` 缓存最近 `history` 个元素，延迟加入的订阅者能立即收到缓存的数据，然后继续接收后续新数据。无参 `cache()` 缓存所有历史元素，可能导致内存无限增长——生产环境应始终指定 `history` 限制容量。与 `replay()` 的区别在于 `cache()` 自动订阅上游（内部自动 `connect()`），而 `replay()` 返回 `ConnectableFlux` 需要手动控制。

```java
Flux<Integer> cached = Flux.just(1, 2, 3, 4, 5)
    .cache(2);  // 只缓存最近 2 个元素

cached.subscribe(v -> System.out.println("订阅者1: " + v));
// ↑ 订阅时数据开始发射，完成后缓存最后 2 个 (4, 5)

// 第二个订阅者：先收到缓存元素 4, 5，然后等待新数据
cached.subscribe(v -> System.out.println("订阅者2: " + v));

// cache(): 无参数，缓存所有历史元素（小心内存！）
// cache(Duration): 缓存一段时间内的元素，过期自动清除
```

#### `ConnectableFlux<T> .replay(int history)` — 重放历史元素 + 冷转热

`replay(int history)` 缓存最近 `history` 个元素并返回 `ConnectableFlux<T>`，需要手动 `connect()` 或配合 `autoConnect()` / `refCount()` 使用。与 `cache()` 相比：`replay()` 需要手动启动，给了调用方更精确的控制时机，适合需要在 `connect()` 之前先设置好所有订阅者的场景。迟到者会收到缓存的历史数据。

```java
ConnectableFlux<Integer> replayFlux = Flux.range(1, 5)
    .replay(2);  // 缓存最后 2 个
replayFlux.connect();  // 开始执行，完成后缓存最后 2 个

// 后来订阅者立即收到缓存
replayFlux.subscribe(v -> System.out.println("迟到者: " + v));
// 输出: 迟到者: 4  迟到者: 5
```

### 6.7 信号级操作与度量

#### `Flux<Signal<T>> .materialize()` / `Flux<X> .dematerialize()` — 信号与值的互转

`.materialize()` 将数据流中的 `onNext`、`onError`、`onComplete` 信号全部包装为 `Signal<T>` 对象发射出去，流以 `onNext(Signal.complete())` 正常完成（而非 `onComplete` 信号）。`.dematerialize()` 是其逆操作——将 `Signal<T>` 流恢复为正常的数据流。这对组合常用于流的序列化、跨进程传输、或单元测试中精确验证信号序列。

```java
Flux.just("A", "B")
    .materialize()
    .subscribe(signal -> System.out.println("信号: " + signal));
// 输出:
// 信号: Signal{onNext: A}
// 信号: Signal{onNext: B}
// 信号: Signal{onComplete}
// ← onComplete 不是结束流，而是作为一个 Signal 对象发射出来！

// dematerialize(): 将 Signal<T> 对象转回正常的流信号
List<Signal<Integer>> signals = List.of(
    Signal.next(1), Signal.next(2), Signal.complete()
);
Flux.fromIterable(signals)
    .dematerialize()
    .subscribe(
        v -> System.out.println("值: " + v),
        e -> System.out.println("错误"),
        () -> System.out.println("正常完成")
    );
// 输出: 值: 1  值: 2  正常完成
```

#### `Flux<T> .name(String)` / `Flux<T> .metrics()` / `Flux<T> .tap(Function)` — Micrometer 度量

`.name(String)` 给流设置一个标签名，用于在 Micrometer 度量中以可读名称标识。`.metrics()` 启用自动度量——记录 `onNext` 计数、`onError` 计数、订阅到完成的耗时，需要 Micrometer 依赖。`.tap()` 是更通用的观察钩子，使用 Micrometer 的 Observation API 对流进行细粒度监控。三者常链式组合：`flux.name("my-flow").metrics().subscribe()`。

```java
Flux.range(1, 10)
    .name("my-custom-flux")          // 在 metrics 中显示为 "my-custom-flux"
    .metrics()                       // 启用 Micrometer 度量（吞吐、延迟、错误率）
    .subscribe();

// tap(): 使用 Micrometer 的 Observation API 对流进行观察
Flux.just("A", "B", "C")
    .tap(() -> new MicrometerSignalListener("my-metric"))
    .subscribe();
```

#### `Flux<R> .transform(Function<Flux<T>, Publisher<R>>)` / `Flux<R> .transformDeferred(Function)` — 函数式组合

`transform` 和 `transformDeferred` 都允许将一组操作符抽取为可复用的 `Function`，参数和返回值相同。关键区别在于应用时机：`transform` 在流**组装时**即应用（一次），`transformDeferred` 在每次**订阅时**才重新应用。如果内部有状态（计数器、时间戳等），必须用 `transformDeferred` 保证每次订阅获得独立状态。

```java
// 定义公共装饰器：超时 + 日志 + 降级
Function<Flux<String>, Flux<String>> robustFlux = flux -> flux
    .timeout(Duration.ofSeconds(5))
    .doOnComplete(() -> log.info("流完成"))
    .onErrorResume(e -> Flux.just("降级数据"));

// 在多处复用
Flux.just("data1").transform(robustFlux).subscribe();
Flux.just("data2").transform(robustFlux).subscribe();

// transformDeferred: 和 transform 类似，但每次订阅时重新应用（保证惰性）
// 适用于装饰器内部有状态的情况
AtomicInteger counter = new AtomicInteger(0);
Function<Flux<String>, Flux<String>> countedFlux = flux -> flux
    .doOnSubscribe(s -> counter.incrementAndGet());  // 有状态

Flux<String> wrong = Flux.just("A").transform(countedFlux);
wrong.subscribe();  // counter = 1
wrong.subscribe();  // counter = 2 （因为 transform 在组装时已应用）
// 正确做法: 用 transformDeferred 每次订阅重新评估
```

#### `Flux<R> .as(Function<Flux<T>, Publisher<R>>)` — 类型安全的组合（Kotlin 友好）

`as` 与 `transform` 的 API 完全相同——将当前流传入一个 `Function` 并返回新的 `Publisher`。社区惯例区分两者的语义：`transform` 强调操作链复用（如一组标准装饰器），`as` 强调类型适配或"当作某物使用"（如转换为领域特定类型）。Kotlin 开发者最常用 `as`，因为它更符合类型转换的语义习惯。

```java
Flux.just("data")
    .as(flux -> convertToMyType(flux))
    .subscribe();

// transform vs as:
// 两者 API 相同，只是语义不同。社区惯例：transform 用于操作链复用，as 用于类型转换
```

### 6.8 练习题

**题目1**：下面代码的输出顺序是什么？为什么 `doOnNext` 打印的位置在 subscribe 之前？

```java
Mono.just("data")
    .doOnSubscribe(s -> System.out.println("A: 已订阅"))
    .doOnNext(v -> System.out.println("B: 收到值: " + v))
    .map(v -> v.toUpperCase())
    .doOnNext(v -> System.out.println("C: 映射后: " + v))
    .subscribe(v -> System.out.println("D: 最终: " + v));
```

**题目2**：`onErrorReturn` 和 `onErrorResume` 有什么区别？下面的代码各自输出什么？

```java
// 代码A: onErrorReturn
Mono.just("input")
    .map(s -> { throw new RuntimeException("boom"); })
    .onErrorReturn("fallback")
    .subscribe(System.out::println);

// 代码B: onErrorResume
Mono.just("input")
    .map(s -> { throw new RuntimeException("boom"); })
    .onErrorResume(e -> Mono.just("恢复数据-" + e.getMessage().substring(0, 4)))
    .subscribe(System.out::println);
```

**题目3**：写出代码，实现如下需求——调用远程 API，如果成功则返回结果，如果失败则重试 3 次（每次间隔 500ms），全部失败后返回默认值 "offline"。

<details>
<summary>点击查看答案与解析</summary>

**题目1答案：**

```
A: 已订阅
B: 收到值: data
C: 映射后: DATA
D: 最终: DATA
```

**解析**：`doOn*` 系列操作符是**副作用操作符**——它们不对数据做任何改变，只是在数据流经时执行一段代码（通常用于日志、监控、调试）。关键要点：
- `doOnSubscribe`: 在订阅事件发生时触发（最上游），先于数据处理
- `doOnNext`: 在数据元素流过当前操作符位置时触发
- 数据按操作符链顺序流动：`doOnSubscribe → doOnNext("data") → map → doOnNext("DATA") → subscribe`
- `doOnNext` 在 subscribe 的 `System.out.println` 之前打印，因为它位置更上游

**题目2答案：**

```
// 代码A 输出: fallback
// 代码B 输出: 恢复数据-boom
```

**解析**：
- `onErrorReturn(T fallback)`：捕获错误后返回一个**固定静态值**。签名：`Mono<T> onErrorReturn(T fallbackValue)`。不关心错误类型和错误信息，直接给一个默认值。
- `onErrorResume(Function<Throwable, Mono<T>>)`：捕获错误后执行一个**函数**，该函数可以根据错误类型/信息动态生成一个新的 Mono（可能是不同的值，可能是从缓存取，可能是调用另一个 API）。签名：`Mono<T> onErrorResume(Function<? super Throwable, ? extends Mono<? extends T>> fallback)`。

选择原则：静态默认值用 `onErrorReturn`，需要根据错误类型/内容做不同处理的用 `onErrorResume`。

**题目3答案：**

```java
Mono<String> result = callRemoteApi()
    .retryWhen(Retry.fixedDelay(3, Duration.ofMillis(500)))
    .onErrorReturn("offline");

result.subscribe(System.out::println);

// callRemoteApi 的模拟实现
private Mono<String> callRemoteApi() {
    return Mono.fromCallable(() -> {
        System.out.println("尝试调用API...");
        if (Math.random() > 0.3) throw new RuntimeException("网络错误");
        return "API响应数据";
    });
}

// 运行结果（示例——模拟失败3次后成功）:
// 尝试调用API...
// 尝试调用API...
// 尝试调用API...
// API响应数据

// 全部失败时:
// 尝试调用API...（4次）
// offline
```

**解析**：`retryWhen(Retry.fixedDelay(3, 500ms))` 最多重试 3 次，每次间隔 500ms。如果全部（初始 + 3次重试 = 4次尝试）都失败，错误向下传播到 `onErrorReturn("offline")`，返回默认值。`Retry` 类提供了多种重试策略：`fixedDelay`（固定延迟）、`backoff`（指数退避）、`max`（最大次数）等。

</details>

---

## 第7章 SSE (Server-Sent Events) 实战

### 7.1 什么是 SSE

SSE 是 HTTP 标准的一部分，允许服务器向浏览器**单向推送**事件流。

**和 WebSocket 的区别：**

| | SSE | WebSocket |
|---|---|---|
| 方向 | 服务器 → 客户端（单向） | 双向 |
| 协议 | HTTP（标准 HTTP） | ws:// （升级协议） |
| 断线重连 | 浏览器自动重连 | 需要手动实现 |
| 适合场景 | 实时推送：AI 流式输出、通知 | 聊天、实时协作 |

**SSE 的 HTTP 响应格式：**

```
Content-Type: text/event-stream

event: message
data: 你好

event: message
data: 世界

event: done
data: {"status":"completed"}
```

每条消息由 `event:` 行（事件类型）和 `data:` 行（数据）组成，双换行分隔。

### 7.2 `ServerSentEvent` 类

```java
import org.springframework.http.codec.ServerSentEvent;

// 它是什么：Spring 对 SSE 事件的 Java 表示
// 泛型参数: 你要传给 data 字段的数据类型
```

**Builder 方法一览：**

| 方法 | 参数 | 返回值 | 说明 |
|------|------|--------|------|
| `ServerSentEvent.<T>builder()` | 无 | `ServerSentEvent.Builder<T>` | 创建 Builder 实例 |
| `.event(String event)` | 事件类型名称 | `Builder<T>` | 对应 SSE `event:` 行 |
| `.data(T data)` | 事件数据 | `Builder<T>` | 对应 SSE `data:` 行 |
| `.id(String id)` | 事件ID（可选） | `Builder<T>` | 断线重连时用于 `Last-Event-ID` |
| `.retry(Duration duration)` | 重连间隔（可选） | `Builder<T>` | 告诉前端重连等待时间 |
| `.comment(String comment)` | 注释（可选） | `Builder<T>` | 对应 SSE `:` 行，前端不可见 |
| `.build()` | 无 | `ServerSentEvent<T>` | 构建最终的事件对象 |

```java
// 构建一个完整的 SSE 事件
ServerSentEvent<String> event = ServerSentEvent.<String>builder()
    .event("message")          // 事件类型，前端通过 event.type 读取
    .data("你好，世界")         // 事件数据，前端通过 event.data 读取
    .id("msg-001")             // 可选：事件 ID
    .retry(Duration.ofSeconds(3))  // 可选：重连间隔
    .comment("这是注释")        // 可选：注释（前端不可见）
    .build();

// 最简构建
ServerSentEvent<String> simple = ServerSentEvent.<String>builder()
    .event("done")
    .data("完成")
    .build();
```

### 7.3 构建 SSE 事件的辅助方法

**这是 LyClaw 中最常见的模式——封装一个 `sseEvent()` 工具方法：**

```java
// 辅助方法：一行代码构建 SSE 事件
private static ServerSentEvent<String> sseEvent(String eventType, String payload) {
    return ServerSentEvent.<String>builder()
        .event(eventType)  // 事件类型
        .data(payload)     // JSON 字符串数据
        .build();
}

// 使用
ServerSentEvent<String> msgEvent = sseEvent("message", "你好");
ServerSentEvent<String> doneEvent = sseEvent("done", "{\"status\":\"completed\"}");
ServerSentEvent<String> errorEvent = sseEvent("error", "{\"message\":\"超时\"}");
```

### 7.4 Controller 中返回 SSE 流

```java
@RestController
@RequestMapping("/api")
public class ChatController {

    // 关键：produces = MediaType.TEXT_EVENT_STREAM_VALUE
    // 告诉浏览器 "这是 SSE 流，不要关闭连接"
    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> chatStream(@RequestBody ChatRequest request) {

        // 返回 Flux<ServerSentEvent<String>>
        // Spring WebFlux 会自动将每个 ServerSentEvent
        // 序列化为 "event: xxx\ndata: xxx\n\n" 格式
        return Flux.just(
            sseEvent("message", "你好"),
            sseEvent("message", "，我是AI助手"),
            sseEvent("done", "{\"status\":\"completed\"}")
        );
    }
}
```

浏览器收到的原始 HTTP 响应：

```
Content-Type: text/event-stream

event: message
data: 你好

event: message
data: ，我是AI助手

event: done
data: {"status":"completed"}
```

### 7.5 前端接收 SSE 事件

```typescript
// 前端使用 EventSource 或 fetch + ReadableStream 接收 SSE
const response = await fetch('/api/chat/stream', {
  method: 'POST',
  headers: { 'Content-Type': 'application/json' },
  body: JSON.stringify({ messages: [...] })
});

const reader = response.body.getReader();
const decoder = new TextDecoder();

while (true) {
  const { done, value } = await reader.read();
  if (done) break;

  const text = decoder.decode(value);
  // 解析 SSE 格式 "event: message\ndata: 你好\n\n"
  const lines = text.split('\n');
  let eventType = '';
  let data = '';

  for (const line of lines) {
    if (line.startsWith('event: ')) eventType = line.substring(7);
    else if (line.startsWith('data: ')) data = line.substring(6);
  }

  if (eventType === 'message') {
    // 追加到聊天显示
    appendToChat(data);
  } else if (eventType === 'tool_call') {
    // 显示工具调用状态
    const tool = JSON.parse(data);
    showToolStatus(tool);
  } else if (eventType === 'done') {
    // 流结束
    onStreamEnd();
  }
}
```

### 7.6 LyClaw 中的 SSE 事件类型全景

| 事件类型 | 发送方 | 数据内容 | 用途 |
|---------|-------|---------|------|
| `message` | ReAct引擎/RespondStage | 文本内容 | AI 回复的文本 chunk |
| `status` | ReAct引擎 | 状态文本 | "Executing tool call..." |
| `tool_call` | ReAct引擎 | JSON | 工具执行状态（executing/done） |
| `tool_approval` | ReAct引擎 | JSON | 请求用户确认工具执行 |
| `error` | Orchestrator/各Stage | JSON | 错误信息 |
| `done` | MetricsStage | JSON | 流正常结束 |
| `respond_start` | RespondStage | 文本 | 响应生成开始 |
| `respond_complete` | MetricsStage | JSON | 响应生成完成 |
| `plan_start/plan_node/plan_complete` | PlanExecutionStage | JSON | 计划生成进度 |
| `reflect_start/reflect_complete` | ReflectionStage | JSON | 反思阶段进度 |
| `metrics` | MetricsStage | JSON | 性能指标数据 |

### 7.7 复杂 SSE 流构建实例

**完整示例：从 LLM 流式响应到多类型 SSE 事件输出**

```java
public Flux<ServerSentEvent<String>> executeStream(ChatRequest request) {
    // 第1步：流式调用 LLM
    return chatModel.stream(request)
        // 第2步：逐 chunk 判断内容类型
        .<ServerSentEvent<String>>handle((chunk, sink) -> {
            if (chunk.hasToolCalls()) {
                // 检测到工具调用 → 发 status 事件通知前端
                sink.next(sseEvent("status", "正在调用工具..."));
            } else if (chunk.getContent() != null) {
                // 文本内容 → 直接透传给前端
                sink.next(sseEvent("message", chunk.getContent()));
            }
        })
        // 第3步：LLM 流结束后，拼接工具执行流
        .concatWith(Flux.defer(() -> {
            // 如果有工具调用，执行工具并返回 tool_call 事件
            if (hasToolCalls) {
                return executeToolsAndEmitEvents();
            }
            // 否则发 done 事件
            return Flux.just(sseEvent("done", "{\"status\":\"completed\"}"));
        }));
}
```

### 7.8 练习题

**题目1**：写出一个完整的 Spring WebFlux SSE Controller 方法，每 1 秒推送一次当前时间，共推送 5 次后结束。

**题目2**：SSE 协议中，下面的事件在客户端会分别触发哪个 EventSource 回调？

```
event: chat
data: {"message": "hello"}

data: 裸数据

event: error
data: {"code": 500}
```

**题目3**：`sseEvent()` 辅助方法的签名是什么？写出它的实现逻辑。

<details>
<summary>点击查看答案与解析</summary>

**题目1答案：**

```java
@GetMapping(value = "/api/time-stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
public Flux<ServerSentEvent<String>> timeStream() {
    return Flux.interval(Duration.ofSeconds(1))   // 每1秒发射一个 Long
        .map(i -> LocalDateTime.now().toString())  // 转为当前时间字符串
        .take(5)                                    // 只取5次
        .map(time -> ServerSentEvent.<String>builder()
            .id(String.valueOf(System.currentTimeMillis()))
            .event("time")
            .data(time)
            .build());
}

// 前端收到的 SSE 流（示例）:
// event: time
// id: 1715952000000
// data: 2026-05-17T14:00:01.123
//
// event: time
// id: 1715952001000
// data: 2026-05-17T14:00:02.123
//
// ...（共5次）
```

**解析**：`Flux.interval(Duration.ofSeconds(1))` 创建定时发射器，从 0 开始每 1 秒递增。`.take(5)` 取前 5 个后自动完成（发送 complete 信号关闭 SSE 连接）。`ServerSentEvent.builder()` 设置 id/event/data 三个字段。

**题目2答案：**

```
event: chat
data: {"message": "hello"}
→ 触发 EventSource.addEventListener("chat", callback)
   callback 接收 MessageEvent { data: '{"message": "hello"}' }

data: 裸数据
→ 触发 EventSource.onmessage
   callback 接收 MessageEvent { data: '裸数据' }
   （没有 event 字段时，默认触发 onmessage）

event: error
data: {"code": 500}
→ 触发 EventSource.addEventListener("error", callback)
   注意：这是自定义事件，不是 EventSource.onerror
   EventSource.onerror 是连接级别的错误（重连失败等）
```

**解析**：SSE 协议中 `event:` 行指定事件类型。有时 `event:` 时客户端用 `addEventListener` 监听对应事件名；无 `event:` 时触发默认的 `onmessage`。自定义事件名 "error" 与 `EventSource.onerror` 是两个不同的概念——前者是业务事件，后者是连接异常。

**题目3答案：**

```java
// 项目中常见的 sseEvent 辅助方法
private ServerSentEvent<String> sseEvent(String event, String data) {
    return ServerSentEvent.<String>builder()
        .event(event)   // 事件类型，如 "message", "tool_call", "done"
        .data(data)     // 事件数据，通常是 JSON 字符串
        .build();
}

// 使用示例:
ServerSentEvent<String> evt = sseEvent("tool_call",
    "{\"toolCallId\":\"call_1\",\"status\":\"executing\"}");
```

**SSE 事件构建的完整字段**：
- `.id(String)` — 事件 ID，断线重连时 Last-Event-ID 用
- `.event(String)` — 事件类型，前端 EventSource 按此分发
- `.data(String)` — 事件数据
- `.retry(Duration)` — 告诉客户端重连间隔
- `.comment(String)` — 注释，客户端不可见
- `ServerSentEvent.builder().build()` 会自动生成符合 SSE 协议的文本行。

</details>

---

## 第8章 Schedulers 调度器与线程模型

### 8.1 为什么需要调度器

在响应式编程中，操作符本身是**线程无关**的——它们不关心自己在哪个线程上执行。
通过调度器，你可以控制操作在哪个线程池中运行。

**关键原则：不要让少量的 IO 线程做阻塞工作。**

### 8.2 内置调度器

#### `static Scheduler immediate()` — 在当前线程执行

- **参数**：无
- **返回值**：`Scheduler` — 不切换线程的调度器，直接在调用 `subscribe()` 的线程上执行

```java
// 不切换线程，就在调用 subscribe() 的线程执行
Mono.just("data")
    .subscribeOn(Schedulers.immediate())
    .subscribe(s -> System.out.println(Thread.currentThread().getName()));
```

#### `static Scheduler single()` — 单线程执行

- **参数**：无（也可传名称前缀 `single(String name)`）
- **返回值**：`Scheduler` — 所有任务在该调度器的同一线程上串行执行

```java
// 所有任务在同一个线程上串行执行
// 适合：需要严格顺序的操作
Flux.range(1, 5)
    .subscribeOn(Schedulers.single())
    .subscribe(i -> System.out.println(
        Thread.currentThread().getName() + " -> " + i));
// 输出: single-1 -> 1  single-1 -> 2  ... （全部同一个线程）
```

#### `static Scheduler parallel()` — 并行线程池

- **参数**：无（也可传名称前缀和线程数 `parallel(String name, int parallelism)`）
- **返回值**：`Scheduler` — 固定大小线程池，默认线程数 = CPU 核数

```java
// 固定大小的线程池（默认 = CPU 核数）
// 适合：CPU 密集型计算
Flux.range(1, 10)
    .parallel()  // 将流分为多个 rail
    .runOn(Schedulers.parallel())  // 在并行线程池上运行每个 rail
    .subscribe(i -> System.out.println(
        Thread.currentThread().getName() + " -> " + i));
```

#### `static Scheduler boundedElastic()` — 弹性线程池（最常用）

- **参数**：无（也可自定义参数）
- **返回值**：`Scheduler` — 弹性线程池，线程数按需增长（上限 CPU核数×10），空闲 60 秒后回收

```java
// 线程数按需增长，空闲回收
// 适合：阻塞 I/O 操作（JDBC, Feign, HTTP 调用）
// 默认上限：CPU 核数 × 10
// 空闲线程 60 秒后回收

Mono.fromCallable(() -> {
    // 模拟阻塞的数据库查询
    Thread.sleep(2000);
    return "查询结果";
})
.subscribeOn(Schedulers.boundedElastic())
.subscribe(result -> System.out.println("收到: " + result));
```

#### `static Scheduler fromExecutor(Executor)` / `fromExecutorService(ExecutorService)` — 从已有线程池创建

当你已经有一个 Java 线程池（如 `Executors.newFixedThreadPool`），不需要 Reactor 重新创建，可以用这两个方法把现有的 `Executor` 或 `ExecutorService` 包装成 `Scheduler`。这对于复用项目中已有的线程池非常方便。需要注意的是，`fromExecutorService` 返回的调度器不会自动关闭内部线程池，你需要在应用关闭时手动调用 `scheduler.dispose()` 和 `executorService.shutdown()` 来释放资源。

```java
// 场景：复用项目已有的线程池
ExecutorService myPool = Executors.newFixedThreadPool(4);
Scheduler scheduler = Schedulers.fromExecutorService(myPool);

Flux.range(1, 5)
    .subscribeOn(scheduler)
    .subscribe(i -> System.out.println(Thread.currentThread().getName() + " -> " + i));

// 应用关闭时记得清理
scheduler.dispose();
myPool.shutdown();
```

#### `Schedulers.newSingle(String)` / `newParallel(String, int)` / `newBoundedElastic(int, int, String)` — 自定义参数创建

除了使用 `Schedulers.single()` / `parallel()` / `boundedElastic()` 这些共享缓存实例，你还可以通过 `new*` 系列工厂方法创建专用的、带自定义名称的调度器。带名称的调度器在线程 dump 中容易辨认，方便排查问题。

- `newSingle(String name, boolean daemon)` — 创建专用的单线程调度器，所有任务在该线程串行执行
- `newParallel(String name, int parallelism, boolean daemon)` — 创建指定并行度的固定线程池
- `newBoundedElastic(int threadCap, int queuedTaskCap, String name, boolean daemon, int ttlSeconds)` — 完整参数创建弹性线程池：`threadCap` 限制最大线程数，`queuedTaskCap` 限制每线程最大排队任务数，`ttlSeconds` 控制空闲线程存活时间

```java
// newSingle: 创建专用的单线程调度器（带名称方便排查）
Scheduler dedicated = Schedulers.newSingle("my-worker", true);

// newParallel: 创建指定并行度的调度器
Scheduler cpuPool = Schedulers.newParallel("cpu-pool", 4, false);

// newBoundedElastic: 完整参数创建弹性线程池
Scheduler ioPool = Schedulers.newBoundedElastic(20, 100, "io-pool", true, 120);
```

#### `Scheduler.dispose()` — 释放调度器资源

通过 `newSingle` / `newParallel` / `newBoundedElastic` 创建的调度器拥有独立的线程池资源，不再使用时需要手动调用 `dispose()` 释放，否则会导致线程泄漏。而对于 `Schedulers.single()` / `parallel()` / `boundedElastic()` 这些共享缓存实例，调用 `dispose()` 只会释放缓存引用，线程池本身仍由 Reactor 管理。

```java
// 从 newSingle/newParallel/newBoundedElastic 创建的调度器需要手动释放
Scheduler scheduler = Schedulers.newSingle("temp");
scheduler.dispose();  // 关闭线程池

// Schedulers.single() / parallel() / boundedElastic() 是共享的缓存实例，
// 它们的 dispose() 只释放缓存引用，线程池本身由 Reactor 管理
```

### 8.3 LyClaw 中的调度器使用总结

```java
// 模式1：阻塞的工具执行 → boundedElastic
Mono.fromCallable(() -> toolExecutor.execute(name, id, args))
    .subscribeOn(Schedulers.boundedElastic());

// 模式2：整个管道 → boundedElastic
Flux.defer(() -> { /* 管道逻辑 */ })
    .subscribeOn(Schedulers.boundedElastic());

// 模式3：同步聊天收集 → boundedElastic
flux.collectList()
    .map(events -> { /* 拼接事件 */ })
    .subscribeOn(Schedulers.boundedElastic());

// 为什么统一用 boundedElastic？
// 因为 LyClaw 的管道中有大量 Feign 调用（HTTP 请求到各个微服务），
// 这些都是阻塞操作。必须把阻塞操作从 Netty 的 epoll 事件循环线程
// 迁移到 boundedElastic，否则会阻塞整个服务的 IO。
```

### 8.4 练习题

**题目1**：下面 4 个场景分别应该用哪个 Scheduler？连线题。

```
A. 执行 Feign HTTP 调用（阻塞）
B. CPU 密集的计算（加密、压缩）
C. 单线程顺序写日志文件
D. 超长时间的任务（超过 boundedElastic 队列上限）
```

选项：`Schedulers.immediate()` / `Schedulers.single()` / `Schedulers.parallel()` / `Schedulers.boundedElastic()`

**题目2**：看以下代码，`blockingCall()` 在哪个线程执行？

```java
Mono.just("data")
    .map(s -> blockingCall(s))
    .subscribeOn(Schedulers.boundedElastic())
    .subscribe();
```

如果换成 `.publishOn(Schedulers.boundedElastic())` 呢？

**题目3**：为什么 Netty 不能运行阻塞代码？如果强行在 Netty worker 线程中 `Thread.sleep(5000)`，会有什么后果？

<details>
<summary>点击查看答案与解析</summary>

**题目1答案：**

```
A → Schedulers.boundedElastic()  // 阻塞 IO，弹性线程池
B → Schedulers.parallel()        // CPU 密集，固定线程池(CPU核数)
C → Schedulers.single()          // 单线程，保证顺序
D → 创建自定义 Scheduler          // 专用线程池，避免影响共享池
```

**解析**：
- `boundedElastic`：专为阻塞 IO 设计，线程池上限 10×CPU核数，空闲线程 60 秒回收。适合 Feign/JDBC/文件 IO。
- `parallel`：固定大小为 CPU 核数的线程池，适合纯 CPU 计算（无需等待 IO）。不适合阻塞操作。
- `single`：全局唯一的单线程，适合需要严格顺序执行的场景（如写日志）。
- `immediate`：直接在当前线程执行，主要用于测试。

**题目2答案：**

```java
// subscribeOn 版本: blockingCall 在 boundedElastic 线程执行 ✓
Mono.just("data")                         // just 也在 boundedElastic
    .map(s -> blockingCall(s))            // 在 boundedElastic 线程
    .subscribeOn(Schedulers.boundedElastic())
    .subscribe();

// publishOn 版本: blockingCall 仍在调用线程执行 ✗
Mono.just("data")                         // 在调用线程
    .map(s -> blockingCall(s))            // 仍在调用线程！阻塞！
    .publishOn(Schedulers.boundedElastic()) // 只影响下游
    .subscribe();
```

**解析**：`subscribeOn` 改变**整条上游链**的执行线程（从源头到第一个 publishOn），而 `publishOn` 只改变**其下游**操作的执行线程。所以用 `publishOn` 时，它前面的 `.map()` 仍在调用线程（可能是 Netty worker），无法保护。
- 口诀：`subscribeOn` 管上游，`publishOn` 管下游。
- 实际项目中的模式：`Mono.fromCallable(() -> blockingCall()).subscribeOn(Schedulers.boundedElastic())`

**题目3答案：**

Netty 使用少量 worker 线程（通常 8-16 个）通过 **epoll 事件循环**处理成千上万个并发连接。每个 worker 线程的非阻塞轮询依赖线程极快地处理每个 IO 事件后立即返回 epoll_wait。

如果一个 worker 线程执行 `Thread.sleep(5000)` 或被阻塞 IO 卡住：
1. 该 worker 负责的所有连接全部挂起 5 秒（包括**已完成**但等待写出响应的连接）
2. 剩余 worker 分摊更多负载，可能连锁超时
3. 吞吐量从数千 QPS 断崖式下降到个位数

```java
// ❌ 危险代码
@GetMapping("/slow")
public Mono<String> slow() {
    Thread.sleep(5000);           // worker-1 被锁死 5 秒！
    return Mono.just("done");
}

// ✅ 安全代码
@GetMapping("/slow")
public Mono<String> slow() {
    return Mono.fromCallable(() -> {
        Thread.sleep(5000);       // 在 boundedElastic 线程，安全
        return "done";
    }).subscribeOn(Schedulers.boundedElastic());
}
```

</details>

---

## 第9章 错误处理机制

### 9.1 Reactor 中的错误传播

```java
// 错误会沿着操作符链向下传播，直到遇到错误处理器
Flux.just("1", "2", "abc", "4")
    .map(s -> Integer.parseInt(s))  // "abc" → NumberFormatException
    .map(i -> i * 10)
    .subscribe(
        i -> System.out.println("数字: " + i),
        err -> System.out.println("错误: " + err.getMessage())
    );
// 输出:
// 数字: 10
// 数字: 20
// 错误: For input string: "abc"
// （注意：错误后的元素 "4" 不会处理）
```

### 9.2 错误处理的四个层次

```java
// 级别1: 只记录，不处理（doOnError）
//       → 异常继续传播
flux.doOnError(e -> log.error("出错了", e))

// 级别2: 返回默认值（onErrorReturn）
//       → 异常被"吞噬"，流正常完成
flux.onErrorReturn("默认值")

// 级别3: 切换到备用流（onErrorResume）
//       → 异常被替换为备用流
flux.onErrorResume(e -> Flux.just("备选1", "备选2"))

// 级别4: 包装后继续传播（onErrorMap）
//       → 异常类型转换，继续向下传播
flux.onErrorMap(e -> new BusinessException("包装", e))
```

### 9.3 错误处理位置的影响

```java
// 错误处理器的位置决定它"保护"哪些操作符

Flux.just("1", "2", "abc", "4")
    .map(s -> Integer.parseInt(s))  // 这里可能抛出 NumberFormatException
    .onErrorReturn(-1)              // ← 保护上面的 map
    .map(i -> i * 10)               // -1 也会经过这里
    .subscribe(System.out::println);
// 输出: 10, 20, -10

// 如果把 onErrorReturn 放在 map 前面：
Flux.just("1", "2", "abc", "4")
    .onErrorReturn(-1)              // ← 保护上面的操作...但上面没有操作
    .map(s -> Integer.parseInt(s))  // 这里出错，下面没有保护
    .subscribe(System.out::println);
// 输出: 10, 20, 然后抛出异常（onErrorReturn 没保护到下面的 map）
```

### 9.4 LyClaw 中的错误处理实践

```java
// 模式1：管道级别错误处理 — 返回格式化的错误 SSE 事件
return pipelineFlux
    .onErrorResume(err -> {
        // 错误发生时，照样推送 error 和 done 事件给前端
        return Flux.just(
            sseEvent("error", "{\"message\":\"" + err.getMessage() + "\"}"),
            sseEvent("done", "{\"status\":\"error\"}")
        );
    });

// 模式2：工具执行级别 — onErrorReturn(false)
// validate() 中：验证失败不抛异常，返回 false
webClient.post().uri(url).retrieve()
    .toBodilessEntity()
    .map(response -> response.getStatusCode().is2xxSuccessful())
    .onErrorReturn(false);  // 网络不通 → 返回 false
```

### 9.5 练习题

**题目1**：下面代码的输出是什么？错误是在哪里被捕获的？

```java
Flux.just("A", "B", "C")
    .map(s -> {
        if (s.equals("B")) throw new RuntimeException("Boom at B");
        return s.toLowerCase();
    })
    .onErrorReturn("recovered")
    .subscribe(
        v -> System.out.println("收到: " + v),
        e -> System.out.println("错误: " + e.getMessage()),
        () -> System.out.println("完成")
    );
```

**题目2**：`doOnError` 和 `onErrorResume` 的执行区别是什么？下面的代码会输出什么？

```java
Flux.just("data")
    .doOnNext(s -> { throw new RuntimeException("error"); })
    .doOnError(e -> System.out.println("doOnError记录: " + e.getMessage()))
    .onErrorResume(e -> {
        System.out.println("onErrorResume恢复: " + e.getMessage());
        return Mono.just("fallback");
    })
    .subscribe(System.out::println);
```

**题目3**：写出一个模式：调用远程 API，对超时异常重试，对其他异常直接返回默认值。

<details>
<summary>点击查看答案与解析</summary>

**题目1答案：**

```
收到: a
收到: recovered
完成
```

**解析**：流处理了 "A" 正常输出 "a"，处理 "B" 时抛出异常。由于 `onErrorReturn` 在操作符链中，异常被立即捕获并替换为 "recovered"。**关键：异常之后的元素 "C" 永远不会被处理**——一旦流中发生错误，流就终止了。`subscribe` 的 error 回调不会被调用（错误已被恢复），complete 回调会被调用（流以 "recovered" 正常结束）。

```
操作符链:
just("A","B","C") → map(转小写) → onErrorReturn("recovered") → subscribe

"B" 在 map 中抛异常 → onErrorReturn 拦截 → 用 "recovered" 替代并正常完成
→ "C" 永远不被 map 处理
```

**题目2答案：**

```
doOnError记录: error
onErrorResume恢复: error
fallback
```

**解析**：
- `doOnError`：**纯副作用**，只观察/记录错误，不改变错误传播。类似 catch 块中先打日志再重新 throw。
- `onErrorResume`：**恢复操作**，捕获错误后返回新的 Mono 替代原流。类似 catch 块中处理后返回默认值。

两者区别类似于：
```java
try {
    throw new RuntimeException("error");
} catch (Exception e) {
    log.error(e.getMessage());     // ← doOnError 的角色
    return "fallback";             // ← onErrorResume 的角色
}
```

**题目3答案：**

```java
Mono<String> result = callRemoteApi()
    .onErrorResume(TimeoutException.class, e -> {
        log.warn("超时，使用缓存数据");
        return Mono.just("缓存数据");
    })
    .onErrorResume(e -> {
        log.error("其他错误: {}", e.getMessage());
        return Mono.just("默认值");
    });

// 或者用更精确的异常匹配
Mono<String> result2 = callRemoteApi()
    .onErrorResume(e -> {
        if (e instanceof TimeoutException) {
            return Mono.just("超时恢复");
        }
        return Mono.error(e);  // 不是超时异常，继续传播
    })
    .onErrorReturn("最终兜底");
```

**解析**：`onErrorResume` 支持按异常类型过滤（重载：`onErrorResume(Class<E>, Function<E, Mono<T>>)`）。未被匹配的错误继续向下传播，直到被后续的错误处理操作符捕获。这种链式错误处理允许根据错误类型分层恢复，类似 try-catch 的多层 catch 块。

</details>

---

## 第10章 项目实战案例拆解

### 10.1 案例一：工具审批流程（完整链路）

这是 LyClaw 中最复杂的响应式链路之一，涉及 SSE、CompletableFuture、Mono、Flux、boundedElastic。

**流程概览：**

```
LLM返回tool_calls → ReActEngine检测到需审批工具
    → 创建CompletableFuture注册到ApprovalStore
    → 发送SSE: tool_approval事件
    → 发送SSE: tool_call(executing)事件
    → 阻塞等待future.get(60秒)
    → [用户点击允许/拒绝] → ApprovalController收到POST
    → ApprovalStore.approve/deny() → complete(future)
    → 等待线程被唤醒
    → 执行工具 或 返回"用户拒绝了工具执行"
    → 发送SSE: tool_call(done)事件
```

**代码逐步拆解：**

```java
// ===== 第1步：RespondStage 获取工具列表，标记非只读工具 =====
// 文件: RespondStage.java

Set<String> approvalTools = toolDefs.stream()
    .filter(def -> !def.isReadOnly())        // 过滤出非只读工具
    .map(ToolDefinition::getName)             // 提取工具名
    .collect(Collectors.toSet());             // 收集为 Set
reActEngine.setApprovalRequired(approvalTools);  // 告诉引擎哪些需要审批


// ===== 第2步：引擎中逐个处理工具调用 =====
// 文件: DefaultReActEngine.java

private Flux<ServerSentEvent<String>> emitRoundToolCallEvents(
        List<ModelResponse.ToolCallRequest> toolCalls,
        ToolExecutor toolExecutor, List<Message> messages) {

    // Flux.fromIterable: 将 List 转为 Flux
    // concatMap: 逐个处理工具调用（先完成第一个，再处理第二个）
    return Flux.fromIterable(toolCalls)
        .concatMap(req -> {
            String toolArgs = req.getArguments() != null
                ? req.getArguments() : "{}";

            // 判断是否需要审批
            if (approvalRequired.contains(req.getName())) {
                // → 进入审批流程
                return emitApprovalFlow(req, toolExecutor, messages, toolArgs);
            }

            // → 无需审批：直接执行
            String execJson = toolCallEventJson(req.getId(), req.getName(),
                "executing", "正在执行 " + req.getName() + "...",
                toolArgs, null, true);

            Mono<ServerSentEvent<String>> doneEvent = Mono.fromCallable(() -> {
                String output = toolExecutor.execute(req.getName(), req.getId(), toolArgs);
                messages.add(Message.tool(req.getId(), output));
                String doneJson = toolCallEventJson(req.getId(), req.getName(),
                    "done", req.getName() + " 完成", toolArgs, output, true);
                return sseEvent("tool_call", doneJson);
            }).subscribeOn(Schedulers.boundedElastic());  // 阻塞执行放在弹性线程池

            return Flux.just(sseEvent("tool_call", execJson))
                .concatWith(doneEvent);
        });
}


// ===== 第3步：审批流程核心实现 =====
private Flux<ServerSentEvent<String>> emitApprovalFlow(
        ModelResponse.ToolCallRequest req, ToolExecutor toolExecutor,
        List<Message> messages, String toolArgs) {

    // ★ 关键 ★: 必须在 Flux 返回前创建 future
    // 为什么？因为 Flux 是异步的，前端可能在未来还没注册时就收到 SSE 并发送审批
    // 如果不先创建，前端 approve() POST 到达时找不到对应的 future
    CompletableFuture<Boolean> future = approvalStore.create(req.getId());

    // 构建 tool_approval SSE 事件 JSON
    String approvalJson = toolApprovalEventJson(
        req.getId(), req.getName(), toolArgs);
    ServerSentEvent<String> approvalEvent =
        sseEvent("tool_approval", approvalJson);

    // 构建 tool_call executing SSE 事件 JSON
    String execJson = toolCallEventJson(req.getId(), req.getName(),
        "executing", "正在执行 " + req.getName() + "...",
        toolArgs, null, true);

    // 构建 done 事件（异步等待审批结果）
    Mono<ServerSentEvent<String>> doneEvent = Mono.fromCallable(() -> {
        // ★ 这个 lambda 在 boundedElastic 线程中执行 ★
        boolean approved;
        try {
            // 阻塞等待用户响应，最多 60 秒
            approved = future.get(APPROVAL_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (Exception e) {
            approved = false;  // 超时或异常 → 视为拒绝
        }

        String output;
        boolean success;
        if (approved) {
            // 用户允许 → 执行工具
            output = toolExecutor.execute(req.getName(), req.getId(), toolArgs);
            success = true;
        } else {
            // 用户拒绝/超时 → 返回拒绝消息
            output = "用户拒绝了工具执行";
            success = false;
        }

        messages.add(Message.tool(req.getId(), output));
        String doneJson = toolCallEventJson(req.getId(), req.getName(),
            "done", req.getName() + " 完成", toolArgs, output, success);
        return sseEvent("tool_call", doneJson);
    }).subscribeOn(Schedulers.boundedElastic());
    //   ↑ 关键：在弹性线程池执行，避免阻塞 Netty IO 线程

    // Flux.just(event1, event2) 创建包含两个事件的流
    // .concatWith(doneEvent) 拼接异步等待的 doneEvent
    // 这确保了事件顺序: tool_approval → tool_call(executing) → tool_call(done)
    return Flux.just(approvalEvent,
                     sseEvent("tool_call", execJson))
        .concatWith(doneEvent);
}


// ===== 第4步：ApprovalStore 管理待审批状态 =====
// 文件: ApprovalStore.java

@Component
public class ApprovalStore {
    private final ConcurrentHashMap<String, CompletableFuture<Boolean>> pending =
        new ConcurrentHashMap<>();

    public CompletableFuture<Boolean> create(String toolCallId) {
        CompletableFuture<Boolean> future = new CompletableFuture<>();
        pending.put(toolCallId, future);

        // 60 秒超时自动拒绝
        CompletableFuture.delayedExecutor(60, TimeUnit.SECONDS).execute(() -> {
            CompletableFuture<Boolean> f = pending.remove(toolCallId);
            if (f != null) {
                f.complete(false);  // complete(false) = 拒绝
            }
        });

        return future;
    }

    public boolean approve(String toolCallId) {
        CompletableFuture<Boolean> future = pending.remove(toolCallId);
        // 幂等保护：future 已完成时不再重复完成
        if (future != null && !future.isDone()) {
            return future.complete(true);  // complete(true) = 允许
        }
        return false;
    }

    public boolean deny(String toolCallId) {
        CompletableFuture<Boolean> future = pending.remove(toolCallId);
        if (future != null && !future.isDone()) {
            return future.complete(false);
        }
        return false;
    }
}


// ===== 第5步：Controller 接收用户审批 =====
// 文件: ApprovalController.java

@RestController
public class ApprovalController {
    private final ApprovalStore approvalStore;

    @PostMapping("/api/approval/respond")
    public Mono<Map<String, Object>> respond(@RequestBody Map<String, Object> body) {
        String toolCallId = (String) body.get("toolCallId");
        Boolean approved = (Boolean) body.get("approved");

        if (toolCallId == null || approved == null) {
            // Mono.just(Map.of(...)) 创建包含响应 Map 的 Mono
            return Mono.just(Map.of("success", false, "error", "参数缺失"));
        }

        boolean ok;
        if (approved) {
            ok = approvalStore.approve(toolCallId);
        } else {
            ok = approvalStore.deny(toolCallId);
        }

        return Mono.just(Map.of("success", ok, "toolCallId", toolCallId));
    }
}
```

### 10.2 案例二：装饰器链 — 响应式重试

**文件：RetryChatModel.java**

```java
// 问题：调用 LLM API 时可能网络超时，需要自动重试
// 解决：用装饰器模式 + Reactor 实现非阻塞重试

@Override
public Flux<ModelResponse> stream(ChatRequest request) {
    // Mono.just(request) → 将请求包装为 Mono
    // .flatMapMany(req -> streamWithRetry(req, 1)) → 展开为重试流
    //
    // 类型变化:
    //   Mono<ChatRequest>  ──flatMapMany──→  Flux<ModelResponse>
    //   (单个请求对象)                        (多个响应 chunk)
    return Mono.just(request)
        .flatMapMany(req -> streamWithRetry(req, 1));
}

private Flux<ModelResponse> streamWithRetry(ChatRequest request, int attempt) {
    return delegate.stream(request)  // 委托给被装饰的模型
        .onErrorResume(error -> {    // 出错时拦截
            if (attempt < maxAttempts) {
                long delay = computeDelay(attempt);

                // Mono.delay(delay) → 异步延迟（不阻塞线程！）
                // .thenMany(...)     → 延迟后丢弃 0L，切换到重试流
                //
                // 整个重试链完全不阻塞任何线程：
                //   延迟由 Reactor 的定时器线程管理
                //   重试流在延迟到期后才创建和订阅
                return Mono.delay(Duration.ofMillis(delay))
                    .thenMany(streamWithRetry(request, attempt + 1));
            }
            // 超过最大重试次数 → 传播错误
            return Flux.error(error);
        });
}

// 退避策略计算
private long computeDelay(int attempt) {
    switch (backoff) {
        case FIXED:       return baseDelayMs;
        case LINEAR:      return baseDelayMs * attempt;
        case EXPONENTIAL: return baseDelayMs * (1L << (attempt - 1));
        //  1 << 0 = 1   1 << 1 = 2   1 << 2 = 4
        //  第1次重试: baseDelayMs ms
        //  第2次重试: baseDelayMs * 2 ms
        //  第3次重试: baseDelayMs * 4 ms
    }
}
```

**执行时序图（假设第1次失败，第2次成功）：**

```
时间轴 ────────────────────────────────────────────────→

第1次尝试: [发起HTTP请求] → [收到错误]
                                ↓
                         onErrorResume 触发
                                ↓
                     Mono.delay(1000ms)  ← 异步延迟，不阻塞线程
                                ↓  (1000ms 后)
                     streamWithRetry(attempt=2)
                                ↓
第2次尝试: [发起HTTP请求] → [收到chunk1] → [收到chunk2] → [完成]
                                ↓               ↓           ↓
                        doOnNext(chunk1)  doOnNext(chunk2)  doOnComplete
```

### 10.3 案例三：断路器的响应式实现

**文件：CircuitBreakerChatModel.java**

```java
// 状态机: CLOSED → OPEN → HALF_OPEN → CLOSED (循环)

@Override
public Flux<ModelResponse> stream(ChatRequest request) {
    String currentState = checkAndTransitionState();

    return switch (currentState) {
        case STATE_CLOSED ->
            // 正常状态：转发请求 + 监听结果
            delegate.stream(request)
                .doOnNext(chunk -> failureCount.set(0))     // 成功: 重置计数器
                .doOnError(error -> {                        // 失败: 累加计数器
                    int failures = failureCount.incrementAndGet();
                    if (failures >= failureThreshold) {
                        state.set(STATE_OPEN);               // 达到阈值: 跳闸
                        openedAt.set(System.currentTimeMillis());
                    }
                });

        case STATE_HALF_OPEN -> {
            // 半开状态：允许探测请求通过
            yield delegate.stream(request)
                .doOnNext(chunk -> {
                    state.set(STATE_CLOSED);   // 探测成功: 恢复
                    failureCount.set(0);
                })
                .doOnError(error -> {
                    int halfAttempts = halfOpenAttempts.incrementAndGet();
                    if (halfAttempts >= halfOpenMaxRequests) {
                        state.set(STATE_OPEN); // 探测全失败: 重新熔断
                    }
                });
        }

        default ->
            // 熔断状态：直接拒绝，不发送任何 HTTP 请求
            Flux.error(new IllegalStateException(
                "CircuitBreaker 已熔断，拒绝请求"));
    };
}

// 状态转换：OPEN 超过冷却时间 → HALF_OPEN
private String checkAndTransitionState() {
    String current = state.get();
    if (STATE_OPEN.equals(current)) {
        long elapsed = System.currentTimeMillis() - openedAt.get();
        if (elapsed >= halfOpenAfterMs) {
            // CAS 原子操作：保证只有一个线程成功触发状态转换
            state.compareAndSet(STATE_OPEN, STATE_HALF_OPEN);
            return STATE_HALF_OPEN;
        }
    }
    return state.get();
}
```

### 10.4 案例四：管道串联 — OrchestratorImpl

**文件：OrchestratorImpl.java**

```java
@Override
public Flux<ServerSentEvent<String>> execute(ChatContext context) {
    // Flux.defer 确保每次 execute() 调用都创建新的管线实例
    return Flux.defer(() -> {
        String traceId = context.getTracing().getTraceId();
        MDC.put("traceId", traceId);

        PipelineContext pipelineCtx = new PipelineContext();
        context.setAttribute("pipelineContext", pipelineCtx);

        // 获取所有已注册的管线阶段（按 @PipelineStage 注解发现）
        List<ReactivePipelineStage> stages = pipelineStageProcessor.getSortedStages();

        // ★ 动态构建管线 ★
        // 从一个空的 Flux 开始，通过 concatWith 逐个串联阶段
        Flux<ServerSentEvent<String>> pipelineFlux = Flux.empty();

        for (ReactivePipelineStage stage : stages) {
            // 每个阶段用 Flux.defer 包装：确保在上一个阶段完成后才创建
            // 如果把 stage.execute() 直接写进去，会在管线构建时就执行！
            pipelineFlux = pipelineFlux.concatWith(
                Flux.defer(() -> stage.execute(context, pipelineCtx))
            );
        }

        // 执行顺序示例（假设有6个阶段）:
        // Flux.empty()
        //   .concatWith(defer(() -> ContextBuildStage.execute()))
        //   .concatWith(defer(() -> SecurityCheckStage.execute()))
        //   .concatWith(defer(() -> PlanExecutionStage.execute()))
        //   .concatWith(defer(() -> ReflectionStage.execute()))
        //   .concatWith(defer(() -> RespondStage.execute()))
        //   .concatWith(defer(() -> MetricsStage.execute()))

        return pipelineFlux
            // 管线级别的错误处理
            .onErrorResume(err -> {
                // 任意阶段失败 → 推送错误事件，管线终止
                return Flux.just(
                    sseEvent("error", "{\"message\":\"...\"}"),
                    sseEvent("done", "{\"status\":\"error\"}")
                );
            })
            // 管线结束时清理 MDC（无论成功或失败）
            .doFinally(signalType -> MDC.remove("traceId"));
    })
    // 整个管线在弹性线程池运行
    .subscribeOn(Schedulers.boundedElastic());
}
```

### 10.5 案例五：异步聊天 — 从 Flux 收集为同步结果

**文件：OrchestrationController.java**

```java
// 同步聊天端点：收集所有 SSE 事件，拼接为最终结果
@PostMapping("/chat")
public Mono<ChatResult> chat(@RequestBody ChatRequest request) {
    // orchestrator.execute() 返回 Flux<ServerSentEvent<String>>
    Flux<ServerSentEvent<String>> flux = orchestrator.execute(context);

    // .collectList() → Mono<List<ServerSentEvent<String>>>
    //   等待所有事件到达，收集为一个 List
    // .map(list → ChatResult) → Mono<ChatResult>
    //   将事件列表转换为聊天结果
    return flux.collectList()
        .map(events -> {
            // 只取 message 类型的 SSE 事件
            String content = events.stream()
                .filter(e -> "message".equals(e.event()))       // 筛选
                .map(e -> e.data() != null ? e.data() : "")     // 提取data
                .reduce("", String::concat);                    // 拼接
            return new ChatResult(content, "stop", null, null, 0L);
        })
        .subscribeOn(Schedulers.boundedElastic());  // 阻塞收集放在弹性线程池
}

// 类型变化完整追踪：
// Flux<ServerSentEvent<String>>   ← orchestrator.execute()
//     ↓ .collectList()
// Mono<List<ServerSentEvent<String>>>
//     ↓ .map(events → ChatResult)
// Mono<ChatResult>
//     ↓ Spring WebFlux 自动订阅并返回 HTTP 响应
```

### 10.6 案例六：模板方法模式 + Flux.defer

**文件：AbstractChatModel.java**

```java
@Override
public Flux<ModelResponse> stream(ChatRequest request) {
    // Flux.defer 保证每次订阅都重新执行完整的调用流程:
    //   校验 → 构建请求 → 发送HTTP → 解析响应
    //
    // 为什么不用 Flux.just(doEverything())？
    //   因为 just() 在构建时就执行了参数，defer() 在订阅时才执行
    //   如果上游需要重试，每次重试都需要重新发送 HTTP 请求
    return Flux.defer(() -> {
        // === 步骤1: 校验请求 ===
        validateRequest(request);
        // 校验失败会抛 ModelException，被 doOnError 捕获

        // === 步骤2: 构建 Provider 原生请求 ===
        // 子类实现（如 OpenAiProtocolChatModel）
        Object nativeRequest = buildNativeRequest(request);

        // === 步骤3: 发送 HTTP 流式请求 ===
        // 子类实现，返回原始数据的 Flux<String>
        return sendNativeRequest(nativeRequest)

            // === 步骤4: 逐行解析 ===
            // 子类实现，将原始 SSE 文本解析为 ModelResponse
            .map(this::parseChunk)
            // .map(this::parseChunk) 等价于:
            // .map(rawLine -> this.parseChunk(rawLine))

            // === 步骤5: 完成时记录日志 ===
            .doOnComplete(() -> log.debug("{} stream completed", provider()))

            // === 步骤6: 错误时统一处理 ===
            .doOnError(this::handleError);
            // .doOnError(this::handleError) 等价于:
            // .doOnError(error -> this.handleError(error))
    });
}

// 子类只需要实现这 3 个方法，无需关心流程控制:
// 1. buildNativeRequest(ChatRequest)  → 将统一请求转为 Provider 格式
// 2. sendNativeRequest(Object)        → 发送 HTTP 并返回原始数据流
// 3. parseChunk(String)              → 将原始行解析为 ModelResponse
```

### 10.7 练习题

**题目1**：在工具审批流程（案例一）中，简要解释以下流程：
1. 为什么 `CompletableFuture<Boolean>` 必须在 Flux 返回前创建？
2. `future.get(APPROVAL_TIMEOUT_SECONDS, TimeUnit.SECONDS)` 在哪个线程执行？为什么？
3. 用户拒绝后，输出字符串是什么？

**题目2**：案例二中，装饰器链的重试为什么不阻塞主线程？

```java
// RetryChatModel 的核心模式
return Mono.delay(Duration.ofMillis(delayMs))
    .thenMany(delegate.stream(request));
```

**题目3**：在 LyClaw 项目中找到 `OrchestratorImpl`，它使用了 `concatWith` 串联多个 Stage 的输出。为什么用 `concatWith` 而不是 `mergeWith`？

<details>
<summary>点击查看答案与解析</summary>

**题目1答案：**

**1. 为什么 future 必须在 Flux 返回前创建？**

消除**竞态条件**：如果先返回 Flux（前端收到 `tool_approval` 事件），而 future 还未创建，前端可能立即调用 `POST /api/approval/respond`，此时 `ApprovalStore` 中找不到对应的 `toolCallId`，审批响应丢失。

```
❌ 竞态（future 在 Flux 内创建）:
  Flux.create() → 发 tool_approval → 前端收到 → 前端立即 approve
  → 但 future 还没 create → approvalStore.approve() 找不到 → 丢失

✅ 消除竞态（future 先创建）:
  future = approvalStore.create(id)  ← 先注册
  → 发 tool_approval → 前端收到 → 前端 approve → 找到了 ✓
```

**2. future.get() 在哪个线程执行？**

在 `boundedElastic` 线程执行。因为整个 `doneEvent` 被 `.subscribeOn(Schedulers.boundedElastic())` 包装：
```java
Mono.fromCallable(() -> {
    approved = future.get(APPROVAL_TIMEOUT_SECONDS, TimeUnit.SECONDS); // boundedElastic 线程
    ...
}).subscribeOn(Schedulers.boundedElastic());
```
`future.get()` 是阻塞调用，如果用 Netty worker 调用会阻塞整个事件循环，所以必须迁移到 boundedElastic。

**3. 用户拒绝后的输出：**

```
"用户拒绝了工具执行"
```
success = false，前端显示该消息。工具不会被执行，只有一条 tool_call done 事件告知拒绝结果。

**题目2答案：**

```java
// Mono.delay 的原理
Mono.delay(Duration.ofMillis(delayMs))    // 在 Schedulers.parallel() 的定时线程上延迟
    .thenMany(delegate.stream(request));  // 延迟后切换到下游流

// delay 不会阻塞任何线程！它使用调度器的定时机制：
// 1. 向调度器注册 "delayMs 后执行回调"
// 2. 当前线程立即返回（不阻塞）
// 3. delayMs 后，调度器触发回调，发射 0L
// 4. thenMany 收到信号后订阅 delegate.stream()
```

**解析**：`Mono.delay` 是**非阻塞**延迟。它使用 `Schedulers.parallel()` 内部的 `ScheduledExecutorService` 来调度定时任务，线程不需要 sleep。对比：
- `Thread.sleep(1000)` → 阻塞当前线程 1 秒
- `Mono.delay(Duration.ofSeconds(1))` → 0 个线程被阻塞，1 秒后回调执行

这就是为什么重试装饰器可以在不阻塞任何线程的情况下实现延迟重试。

**题目3答案：**

```java
// concatWith: 严格按顺序串联
Flux.empty()
    .concatWith(stage1.execute())  // stage1 完成后
    .concatWith(stage2.execute())  // 才执行 stage2
    .concatWith(stage3.execute())  // 然后 stage3
```

**解析**：Orchestrator 的 Stage 有严格的**顺序依赖**——SecurityCheckStage 必须通过后才执行 RespondStage，所以必须用 `concatWith` 保证串行。如果用 `mergeWith`（并发合并），所有 Stage 同时执行，安全检查就形同虚设。

`concatWith` 内部用队列 + 串行订阅实现——订阅第一个流，等它 complete 后，从队列取出下一个流订阅。这正是 Stage 流水线需要的语义。

</details>

---

## 附录

### A. 快速参考卡片

#### Mono 常用方法速查

| 方法 | 用途 | 示例 |
|------|------|------|
| `Mono.just(T)` | 包装已知值 | `Mono.just("hello")` |
| `Mono.empty()` | 创建空 Mono | `Mono.empty()` |
| `Mono.error(Throwable)` | 创建错误 Mono | `Mono.error(new RuntimeException())` |
| `Mono.fromCallable(Callable)` | 包装可能抛异常的操作 | `Mono.fromCallable(() -> db.query())` |
| `Mono.delay(Duration)` | 延迟发射 | `Mono.delay(Duration.ofSeconds(3))` |
| `Mono.fromFuture(CompletableFuture)` | 从 Future 桥接 | `Mono.fromFuture(future)` |
| `.map(Function)` | 转换类型 | `mono.map(s -> s.length())` |
| `.flatMap(Function)` | 转为另一个 Mono | `mono.flatMap(id -> findById(id))` |
| `.flatMapMany(Function)` | Mono → Flux | `mono.flatMapMany(id -> findOrders(id))` |
| `.defaultIfEmpty(T)` | 空时给默认值 | `mono.defaultIfEmpty("默认")` |
| `.switchIfEmpty(Mono)` | 空时切换备用 Mono | `mono.switchIfEmpty(Mono.just("备选"))` |
| `.block()` | 阻塞获取结果 | `String s = mono.block()` |
| `.block(Duration)` | 带超时阻塞 | `String s = mono.block(Duration.ofSeconds(5))` |
| `.subscribe(Consumer)` | 订阅消费 | `mono.subscribe(System.out::println)` |

#### Flux 常用方法速查

| 方法 | 用途 | 示例 |
|------|------|------|
| `Flux.just(T...)` | 从已知值创建 | `Flux.just(1, 2, 3)` |
| `Flux.fromIterable(Iterable)` | 从集合创建 | `Flux.fromIterable(list)` |
| `Flux.empty()` | 创建空流 | `Flux.empty()` |
| `Flux.error(Throwable)` | 创建错误流 | `Flux.error(new RuntimeException())` |
| `Flux.defer(Supplier)` | 惰性创建 | `Flux.defer(() -> Flux.just(...))` |
| `Flux.create(Consumer)` | 编程式创建 | `Flux.create(sink -> sink.next("x"))` |
| `Flux.range(int, int)` | 数值范围 | `Flux.range(1, 100)` |
| `.map(Function)` | 逐元素转换 | `flux.map(x -> x * 2)` |
| `.filter(Predicate)` | 过滤 | `flux.filter(x -> x > 0)` |
| `.flatMap(Function)` | 一对多展开 | `flux.flatMap(x -> Flux.just(x,-x))` |
| `.concatMap(Function)` | 一对多展开（有序） | `flux.concatMap(x -> queryDB(x))` |
| `.handle(BiConsumer)` | 可变输出 | `flux.handle((x, sink) -> { ... })` |
| `.collectList()` | 收集为 List | `flux.collectList()` → `Mono<List<T>>` |
| `.concatWith(Publisher)` | 拼接 | `f1.concatWith(f2)` |
| `.thenMany(Publisher)` | 忽略当前数据，切换 | `mono.thenMany(flux)` |
| `.take(long)` | 取前 N 个 | `flux.take(3)` |
| `.onErrorReturn(T)` | 出错时返回默认值 | `flux.onErrorReturn(-1)` |
| `.onErrorResume(Function)` | 出错时切换备用流 | `flux.onErrorResume(e -> fallback)` |
| `.subscribeOn(Scheduler)` | 指定执行线程 | `flux.subscribeOn(Schedulers.boundedElastic())` |
| `.doOnNext(Consumer)` | 元素处理前后的钩子 | `flux.doOnNext(x -> log(x))` |
| `.doOnComplete(Runnable)` | 完成时的钩子 | `flux.doOnComplete(() -> log("done"))` |
| `.doOnError(Consumer)` | 出错时的钩子 | `flux.doOnError(e -> log(e))` |
| `.doFinally(Consumer)` | 终止时的钩子（含完成/错误/取消） | `flux.doFinally(s -> cleanup())` |

#### 线程模型速查

| 调度器 | 线程数 | 适用场景 |
|--------|--------|---------|
| `Schedulers.immediate()` | 当前线程 | 测试 |
| `Schedulers.single()` | 1 | 严格顺序 |
| `Schedulers.parallel()` | CPU核数 | CPU密集计算 |
| `Schedulers.boundedElastic()` | 最多10×CPU核数 | 阻塞I/O（Feign, JDBC） |

### B. 常见陷阱与最佳实践

**陷阱 1：`Mono.just()` 中执行副作用**

```java
// ❌ 错误: heavyOperation() 在 just() 调用时就执行了
Mono<String> wrong = Mono.just(heavyOperation());

// ✅ 正确: 用 fromCallable 或 defer 延迟执行
Mono<String> correct = Mono.fromCallable(() -> heavyOperation());
```

**陷阱 2：在非响应式线程中 blocking**

```java
// ❌ 错误: 在 Netty event loop 线程中调用 .block()
@GetMapping("/data")
public String getData() {
    return mono.block();  // 阻塞了事件循环！
}

// ✅ 正确: 返回 Mono，让 WebFlux 自己处理订阅
@GetMapping("/data")
public Mono<String> getData() {
    return mono;  // WebFlux 框架会异步订阅
}
```

**陷阱 3：忘记订阅**

```java
// ❌ 什么都不会发生
Flux.just(1, 2, 3)
    .map(n -> n * 10)
    .doOnNext(System.out::println);
// 输出：无

// ✅ 需要订阅才会执行
Flux.just(1, 2, 3)
    .map(n -> n * 10)
    .doOnNext(System.out::println)
    .subscribe();
// 输出: 10 20 30
```

**陷阱 4：在 `.map()` 中做异步操作**

```java
// ❌ 错误: 在 map 中调用返回 Mono 的方法
mono.map(id -> findById(id))  // 返回类型变成 Mono<Mono<User>>

// ✅ 正确: 用 flatMap
mono.flatMap(id -> findById(id))  // 返回类型是 Mono<User>
```

**陷阱 5：竞态条件 — LyClaw 真实 bug**

```java
// ❌ 错误: future 在 Flux/Mono 内部创建
// 问题：Flux 是异步的，前端可能在 future 还没创建时就发送了审批
private Flux<ServerSentEvent<String>> buggyVersion(ToolCallRequest req) {
    return Flux.just(approvalEvent).concatWith(
        Mono.fromCallable(() -> {
            // future 在这里创建，但此时 SSE 已经发给前端了
            CompletableFuture<Boolean> future = store.create(req.getId());
            return future.get(60, SECONDS);
        })
    );
}

// ✅ 正确: future 在 Flux 返回前创建
private Flux<ServerSentEvent<String>> fixedVersion(ToolCallRequest req) {
    // 先创建 future（同步操作）
    CompletableFuture<Boolean> future = store.create(req.getId());

    // 再构建 Flux（异步操作）
    return Flux.just(approvalEvent).concatWith(
        Mono.fromCallable(() -> future.get(60, SECONDS))
    );
}
```

### C. 类型推断与泛型阅读指南

看懂 Reactor 链的关键是逐行追踪类型变化：

```java
// 示例链:
Flux.just("1", "2", "3")          // Flux<String>
    .map(s -> Integer.parseInt(s)) // Flux<Integer>
    .filter(i -> i > 1)            // Flux<Integer>
    .map(i -> i * 10)              // Flux<Integer>
    .collectList()                 // Mono<List<Integer>>
    .map(list -> list.size())      // Mono<Integer>
    .defaultIfEmpty(0)             // Mono<Integer>
    .subscribe(System.out::println);

// 类型变化线:
// Flux<String> → Flux<Integer> → Flux<Integer> → Flux<Integer>
// → Mono<List<Integer>> → Mono<Integer> → Mono<Integer>
// → subscribe() 订阅并消费
```

```java
// 另一个示例:
Mono.just(request)                     // Mono<ChatRequest>
    .flatMapMany(req ->                // 展开为 Flux<ModelResponse>
        chatModel.stream(req)          // Flux<ModelResponse>
    )
    .map(this::parseChunk)             // Flux<ModelResponse>  (parseChunk 返回 ModelResponse)
    .<ServerSentEvent<String>>handle(  // Flux<ServerSentEvent<String>>  (handle 的输出类型)
        (chunk, sink) -> {
            sink.next(sseEvent("message", chunk.getContent()));
        }
    )
    .concatWith(                       // Flux<ServerSentEvent<String>>
        Flux.defer(() -> executeTools())
    );

// 类型变化:
// Mono<ChatRequest>
// → flatMapMany → Flux<ModelResponse>
// → map → Flux<ModelResponse>
// → handle → Flux<ServerSentEvent<String>>
// → concatWith → Flux<ServerSentEvent<String>>
```

### 附录练习 附录自测题

**题目1**：速记卡片挑战——不看文档，说出以下方法的签名和用途：
- `Mono.just()`、`Mono.defer()`、`Flux.interval()`
- `.map()`、`.flatMap()`、`.concatMap()`
- `.subscribeOn()`、`.publishOn()`
- `.onErrorReturn()`、`.onErrorResume()`

**题目2**：常见陷阱——下面的代码有什么问题？

```java
// 陷阱1
Mono<String> m = Mono.just(System.currentTimeMillis());
m.subscribe(System.out::println);  // 第1次
m.subscribe(System.out::println);  // 第2次 — 两次输出相同吗？

// 陷阱2
Flux.just(1, 2, 3)
    .map(x -> x / 0)  // 除零异常
    .subscribe(
        System.out::println,
        e -> System.out.println("出错"),
        () -> System.out.println("完成")
    );
// 输出什么？

// 陷阱3
Mono.just("data")
    .flatMap(s -> Mono.just(s.toUpperCase()))
    .map(s -> s.toLowerCase())
    .flatMap(s -> Mono.just(s.length()))
    .subscribe(System.out::println);
// 这段代码中 map 和 flatMap 哪个该用 map？
```

<details>
<summary>点击查看答案与解析</summary>

**题目1答案：**

| 方法 | 签名 | 用途 |
|------|------|------|
| `Mono.just(T)` | `public static <T> Mono<T> just(T data)` | 从已知值创建（立即求值） |
| `Mono.defer(Supplier)` | `public static <T> Mono<T> defer(Supplier<Mono<T>>)` | 延迟创建（订阅时才执行） |
| `Flux.interval(Duration)` | `public static Flux<Long> interval(Duration period)` | 定时发射递增 Long |
| `.map(Function)` | `public final <V> Mono<V> map(Function<T,V>)` | 同步 1:1 转换 |
| `.flatMap(Function)` | `public final <V> Mono<V> flatMap(Function<T,Mono<V>>)` | 异步转换+展平 |
| `.concatMap(Function)` | `public final <V> Flux<V> concatMap(Function<T,Publisher<V>>)` | 串行异步转换+保序 |
| `.subscribeOn(Scheduler)` | `public final Mono<T> subscribeOn(Scheduler)` | 改变上游执行线程 |
| `.publishOn(Scheduler)` | `public final Mono<T> publishOn(Scheduler)` | 改变下游执行线程 |
| `.onErrorReturn(T)` | `public final Mono<T> onErrorReturn(T fallback)` | 错误→静态默认值 |
| `.onErrorResume(Function)` | `public final Mono<T> onErrorResume(Function<Throwable,Mono<T>>)` | 错误→动态恢复流 |

**题目2答案：**

**陷阱1**：两次输出**相同**。因为 `Mono.just()` 在组装时就调用了 `System.currentTimeMillis()`，之后无论订阅多少次都返回同一个已捕获的时间戳。如果要每次订阅重新计算，用 `Mono.defer(() -> Mono.just(System.currentTimeMillis()))`。

```
运行结果: 1715952000000 和 1715952000000（相同）
```

**陷阱2**：只输出 `"出错"`，不输出任何数字，也不输出 `"完成"`。因为第一个元素 `1` 就触发除零异常，异常立即终止流。`subscribe` 的 complete 回调**不会**被调用——流不是正常完成，而是异常终止。

```
运行结果: 出错
```

**陷阱3**：第 1 和第 3 个应该用 `map`：

```java
Mono.just("data")
    .map(s -> s.toUpperCase())    // ✅ 同步转换，应该用 map
    .map(s -> s.toLowerCase())    // ✅ 同步转换，应该用 map
    .map(s -> s.length())         // ✅ 同步转换，应该用 map
    .subscribe(System.out::println);
```

**解析**：`map` vs `flatMap` 的选择规则很简单——如果 Lambda 返回的是**普通值**（不是 Mono/Flux），用 `map`；如果返回的是**Mono/Flux**（异步操作），用 `flatMap`。用 `flatMap(s -> Mono.just(s.toUpperCase()))` 会创建不必要的 Mono，增加开销。

</details>

---

## 第11章 测试中的 Reactor 模式

### 11.1 测试响应式代码的基本原则

测试响应式代码最重要的是**让异步变同步**——用 `.block()` 系列方法等待结果。

```java
// 测试模式: 定义流 → 阻塞收集 → 断言结果
@Test
void testFluxMap() {
    List<Integer> result = Flux.just(1, 2, 3)
        .map(n -> n * 10)
        .collectList()
        .block();

    assertEquals(List.of(10, 20, 30), result);
}
```

### 11.0 StepVerifier：Reactor 官方的测试利器

除了 `.block()` 方式，Reactor 提供了专门的测试工具 `StepVerifier`。
它能声明式地验证流的每个信号（onNext、onError、onComplete）及其时序。

```java
import reactor.test.StepVerifier;

// 基础用法：验证正常完成的流
@Test
void testNormalFlux() {
    Flux<String> flux = Flux.just("A", "B", "C");

    StepVerifier.create(flux)
        .expectNext("A")     // 期望第1个元素是 "A"
        .expectNext("B")     // 期望第2个元素是 "B"
        .expectNext("C")     // 期望第3个元素是 "C"
        .verifyComplete();   // 期望流正常完成
}

// 验证包含错误的流
@Test
void testErrorFlux() {
    Flux<String> flux = Flux.just("A", "B")
        .concatWith(Flux.error(new RuntimeException("boom")));

    StepVerifier.create(flux)
        .expectNext("A")
        .expectNext("B")
        .expectError(RuntimeException.class)  // 期望抛 RuntimeException
        .verify();  // 验证（流以错误结束）
}

// 验证空流
@Test
void testEmptyFlux() {
    Flux<String> flux = Flux.empty();

    StepVerifier.create(flux)
        .verifyComplete();  // 期望流正常完成（无数据）
}

// 验证 Mono
@Test
void testMono() {
    Mono<String> mono = Mono.just("结果");

    StepVerifier.create(mono)
        .expectNext("结果")
        .verifyComplete();
}

// 使用时序操作符（验证 delay 等时间相关逻辑）
@Test
void testWithVirtualTime() {
    // StepVerifier.withVirtualTime 可以用虚拟时钟，无需真正等待
    StepVerifier.withVirtualTime(() ->
            Mono.delay(Duration.ofHours(24))  // 实际会等 24 小时
                .then(Mono.just("done"))
        )
        .thenAwait(Duration.ofHours(24))  // 虚拟时钟跳过 24 小时
        .expectNext("done")
        .verifyComplete();
}

// 验证流中元素数量
@Test
void testElementCount() {
    Flux<Integer> flux = Flux.range(1, 100)
        .filter(n -> n % 2 == 0);  // 50 个偶数

    StepVerifier.create(flux)
        .expectNextCount(50)  // 期望 50 个元素
        .verifyComplete();
}
```

**StepVerifier vs block() 的选择：**

| 场景 | 推荐方式 |
|------|---------|
| 简单收集后断言 List 内容 | `collectList().block()` |
| 需要验证信号顺序和数量 | `StepVerifier` |
| 需要验证时序逻辑 | `StepVerifier.withVirtualTime()` |
| 需要验证错误类型 | `StepVerifier` |
| LyClaw 中测试 SSE 事件顺序 | `StepVerifier` 更适合但不方便断言 JSON 内容，所以用了 `collectList().block()` |

**更多 StepVerifier 方法：**

```java
// expectNextMatches(Predicate): 对下一个元素执行自定义断言
StepVerifier.create(Flux.just("Hello", "World"))
    .expectNextMatches(s -> s.startsWith("H") && s.length() == 5)  // "Hello"
    .expectNextMatches(s -> s.endsWith("d"))                        // "World"
    .verifyComplete();

// assertNext(Consumer): 消费下一个元素并自定义断言
StepVerifier.create(Flux.just("data"))
    .assertNext(value -> {
        assertNotNull(value);
        assertEquals("data", value);
    })
    .verifyComplete();

// thenCancel(): 模拟订阅者中途取消
StepVerifier.create(Flux.interval(Duration.ofMillis(10)))
    .expectNext(0L)
    .expectNext(1L)
    .thenCancel()           // 取消订阅
    .verify();              // 验证取消信号发生

// verify(Duration): 带超时验证
StepVerifier.create(Flux.never())
    .expectTimeout(Duration.ofSeconds(1))  // 期望在1秒内超时
    .verify();

// verifyThenAssertThat(): 验证后执行额外断言
StepVerifier.create(Flux.just(1, 2, 3).filter(n -> n % 2 == 0))
    .expectNext(2)
    .verifyComplete()
    .assertNext(results -> {
        // 访问实际发射的元素列表
    });

// thenRequest(long): 手动控制请求量（背压测试）
StepVerifier.create(Flux.range(1, 10), 2)  // 初始请求 2 个
    .expectNext(1, 2)
    .thenRequest(3)          // 再请求 3 个
    .expectNext(3, 4, 5)
    .thenRequest(5)          // 再请求 5 个
    .expectNext(6, 7, 8, 9, 10)
    .verifyComplete();

// expectNoEvent(Duration): 断言在指定时间内没有任何信号
StepVerifier.withVirtualTime(() -> Mono.delay(Duration.ofHours(1)))
    .expectSubscription()
    .expectNoEvent(Duration.ofHours(1))  // 1小时内无事件
    .expectNext(0L)
    .verifyComplete();
```

**StepVerifier 常用方法速查表：**

| 方法 | 说明 |
|------|------|
| `create(Publisher)` | 创建验证器 |
| `create(Publisher, long request)` | 创建并指定初始请求量 |
| `withVirtualTime(Supplier)` | 虚拟时间模式（跳过真实等待） |
| `expectNext(T... values)` | 期望下 N 个元素的值 |
| `expectNextCount(long count)` | 期望接下来有 count 个元素（不关心值） |
| `expectNextMatches(Predicate)` | 期望下一个元素满足条件 |
| `assertNext(Consumer)` | 自定义断言下一个元素 |
| `expectComplete()` | 期望完成信号 |
| `verifyComplete()` | 验证流正常完成（= expectComplete + verify） |
| `expectError(Class)` | 期望指定的异常类型 |
| `expectError()` | 期望任意异常 |
| `verify()` | 执行验证并阻塞等待 |
| `verify(Duration)` | 带超时的验证 |
| `thenCancel()` | 触发取消 |
| `thenRequest(long)` | 手动请求 N 个元素 |
| `expectNoEvent(Duration)` | 期望一段时间内无信号 |
| `expectSubscription()` | 期望收到订阅 |
| `thenAwait(Duration)` | 虚拟时间：快进一段时间 |
| `verifyThenAssertThat()` | 验证后返回断言对象 |
| `expectAccessibleContext()` | 期望 Reactor Context 可访问 |

### 11.2 阻塞式测试的完整案例

### 11.3 `.collectList().block()` — 最常用的测试模式

这是 LyClaw 测试中使用最多的模式，将整个 Flux 收集为 List 后断言：

```java
@Test
void testFilterAndMap() {
    // 1. 定义操作链
    // 2. .collectList() 将所有元素收集到 Mono<List<T>>
    // 3. .block() 阻塞等待结果
    List<String> result = Flux.just("apple", "banana", "cherry", "date")
        .filter(s -> s.length() > 3)     // 过滤长度 <= 3 的
        .map(String::toUpperCase)         // 转大写
        .collectList()                    // Mono<List<String>>
        .block();                         // List<String>

    assertEquals(3, result.size());
    assertTrue(result.contains("APPLE"));
    assertTrue(result.contains("BANANA"));
    assertTrue(result.contains("CHERRY"));
}
```

### 11.4 `.take(n).collectList().block()` — 截取前 N 个元素测试

当流是无限的或很长时，只取前几个元素验证：

```java
@Test
void testTakeFirstN() {
    // 模拟无限流 → 只取前 5 个
    List<Integer> result = Flux.range(1, Integer.MAX_VALUE)
        .filter(n -> n % 2 == 0)      // 偶数
        .take(5)                       // 只要前 5 个
        .collectList()
        .block();

    assertEquals(List.of(2, 4, 6, 8, 10), result);
}
```

**LyClaw 中的实际测试**（`ApprovalIntegrationTest`）：

```java
@Test
@DisplayName("ASK 工具：tool_approval → tool_call(executing) → 等待审批")
void testAskToolTriggersApprovalEvent() {
    // 因为审批流会阻塞等待（future.get），不能 collectList 整个流
    // 所以用 .take(3) 只取前 3 个事件来验证
    List<ServerSentEvent<String>> events = flux
        .take(3)           // 只取前 3 个事件（不包含阻塞等待的 done 事件）
        .collectList()
        .block();

    assertNotNull(events);
    assertTrue(events.size() >= 2);

    // 验证 tool_approval 事件存在
    ServerSentEvent<String> approvalEvent = events.stream()
        .filter(e -> "tool_approval".equals(e.event()))
        .findFirst().orElse(null);
    assertNotNull(approvalEvent);
    assertTrue(approvalEvent.data().contains("tc-cmd"));

    // 验证 tool_call executing 事件
    ServerSentEvent<String> executingEvent = events.stream()
        .filter(e -> "tool_call".equals(e.event())
                && e.data() != null && e.data().contains("\"executing\""))
        .findFirst().orElse(null);
    assertNotNull(executingEvent);
}
```

### 11.5 模拟响应式依赖（Mock）

```java
@Test
void testWithMockedFlux() {
    // 模拟 chatModel.stream() 返回的 Flux
    ChatModel mockModel = mock(ChatModel.class);
    when(mockModel.stream(any()))
        .thenReturn(Flux.just(chunk1, chunk2, chunk3));

    // 测试使用 mockModel 的代码
    Flux<ServerSentEvent<String>> result = engine.executeStream(
        chatFacade, request, toolExecutor);

    List<ServerSentEvent<String>> events = result
        .collectList()
        .block();

    assertEquals(5, events.size());  // 根据预期调整
}
```

### 11.6 测试 Mono 错误场景

```java
@Test
void testMonoError() {
    Mono<String> errorMono = Mono.error(new RuntimeException("测试错误"));

    // 方式1: 用 onErrorReturn 恢复后断言
    String result = errorMono
        .onErrorReturn("恢复值")
        .block();
    assertEquals("恢复值", result);

    // 方式2: 验证是否抛出异常
    assertThrows(RuntimeException.class, () -> {
        errorMono.block();
    });
}

@Test
void testMonoEmpty() {
    Mono<String> empty = Mono.empty();

    // 空 Mono 默认值验证
    String result = empty
        .defaultIfEmpty("默认值")
        .block();
    assertEquals("默认值", result);

    // 验证空 Mono block() 返回 null
    assertNull(Mono.empty().block());
}
```

### 11.7 测试 CompletableFuture 的完成

```java
@Test
void testFutureComplete() throws Exception {
    CompletableFuture<Boolean> future = new CompletableFuture<>();

    // 模拟异步完成
    new Thread(() -> {
        future.complete(true);
    }).start();

    // 阻塞等待并断言
    Boolean result = future.get(5, TimeUnit.SECONDS);
    assertTrue(result);
}

@Test
void testFutureTimeout() {
    CompletableFuture<Boolean> future = new CompletableFuture<>();

    // 不完成 future，验证超时
    assertThrows(TimeoutException.class, () -> {
        future.get(100, TimeUnit.MILLISECONDS);
    });
}
```

### 11.8 练习题

**题目1**：用 `StepVerifier` 验证一个 Flux，要求：
- 期望收到 3 个元素：`"A"`, `"B"`, `"C"`
- 然后验证 complete 信号
- 如果有未预期的元素或信号，测试失败

**题目2**：如何测试一个需要 3 秒才能完成的 Mono？写出两种方法：一种是阻塞等待，一种是用 StepVerifier 的虚拟时间。

**题目3**：下面两个测试方法有什么区别？

```java
// 测试A
List<String> results = flux.collectList().block();
assertEquals(List.of("A", "B"), results);

// 测试B
StepVerifier.create(flux)
    .expectNext("A", "B")
    .verifyComplete();
```

<details>
<summary>点击查看答案与解析</summary>

**题目1答案：**

```java
@Test
void testFluxElements() {
    Flux<String> flux = Flux.just("A", "B", "C");

    StepVerifier.create(flux)
        .expectNext("A")       // 期望第1个元素
        .expectNext("B")       // 期望第2个元素  
        .expectNext("C")       // 期望第3个元素
        .verifyComplete();     // 验证收到 complete 信号，无更多元素

    // 或简写:
    StepVerifier.create(flux)
        .expectNext("A", "B", "C")
        .verifyComplete();
}

// 运行结果: 测试通过 ✓
```

**解析**：`StepVerifier` 是 Reactor Test 提供的测试工具。它订阅流，记录所有信号（onNext/onError/onComplete），然后逐项验证。`verifyComplete()` 会阻塞等待流完成（默认超时），超时或信号不匹配则抛出 AssertionError。

**题目2答案：**

```java
// 方法1: 阻塞等待（简单直接）
@Test
void testBlocking() {
    Mono<String> slowMono = Mono.just("done")
        .delayElement(Duration.ofSeconds(3));

    String result = slowMono.block(Duration.ofSeconds(5));
    assertEquals("done", result);
}

// 方法2: StepVerifier 虚拟时间（快速）
@Test
void testVirtualTime() {
    StepVerifier.withVirtualTime(() ->
            Mono.just("done").delayElement(Duration.ofSeconds(3))
        )
        .expectSubscription()        // 期望创建订阅
        .expectNoEvent(Duration.ofSeconds(3))  // 3秒内无事件
        .expectNext("done")
        .verifyComplete();
}

// 运行结果: 
// 方法1 耗时 3+ 秒
// 方法2 瞬间完成（虚拟时间跳过等待）
```

**解析**：`StepVerifier.withVirtualTime()` 用虚拟时钟替代系统时钟，`Duration.ofSeconds(3)` 的实际等待时间为 0。适合测试长时间延迟的场景（重试、超时、定时任务），可以极大加速测试。

**题目3答案：**

**方式A（阻塞式）**：简单粗暴，适合简单验证。缺点：
- 只能验证最终结果，无法验证中间信号（如多个 onNext 的顺序）
- 无法验证时序（如 delay 后的值）
- 只能验证 complete 流，不方便验证 error 流

**方式B（StepVerifier）**：精细控制。优点：
- 可验证每个信号（onNext/onError/onComplete）的顺序和值
- 支持虚拟时间（跳过 delay）
- 可验证背压行为
- 可验证无事件期间、超时等边界条件
- 失败时给出详细的不匹配信息

选择原则：简单场景用 `block()`，复杂场景（多元素、延迟、错误、背压）用 `StepVerifier`。

</details>

---

## 第12章 深入 SSE：从后端到前端的完整数据流

### 12.1 完整链路追踪

当你在聊天框输入 "列出当前目录的文件" 后，发生了什么：

```
[前端]
  fetch POST /api/chat/stream  ──────────────────────────────────────┐
    请求体: { "messages": [{ "role":"user", "content":"列出文件" }] }   │
                                                                      │
[网关] (GatewayConfig.java)                                          │
  /api/chat/stream → lb://lyclaw-orchestration-service               │
                                                                      │
[编排控制器] (OrchestrationController.java)                            │
  @PostMapping("/chat/stream")                                        │
  解析请求 → 构建 ChatContext → orchestrator.execute(context)          │
                                                                      │
[编排器] (OrchestratorImpl.java)                                      │
  串联6个管线阶段:                                                     │
  ContextBuild → SecurityCheck → Plan → Reflect → Respond → Metrics  │
                                                                      │
[RespondStage]                                                        │
  获取工具列表 → 标记非只读工具 → 构建 ToolExecutor                     │
  → reActEngine.executeStream(chatFacade, request, toolExecutor)      │
                                                                      │
[DefaultReActEngine]                                                  │
  LLM 返回 tool_calls: [{ name:"command", arguments:"{\"cmd\":\"ls\"}" }]
  → command 在审批集合中 → emitApprovalFlow()                          │
  → Flux.just(tool_approval, tool_call(executing)).concatWith(wait)   │
                                                                      │
[Spring WebFlux]                                                      │
  将 ServerSentEvent 序列化为 SSE 文本:                                │
    event: tool_approval                                              │
    data: {"toolCallId":"tc-001","toolName":"command",...}            │
                                                                      │
[前端 fetch/ReadableStream]                                           │
  解析 SSE 文本 → dispatch 到 onApprovalRequired 回调                  │
  → chatStore.pendingApproval = { toolCallId, toolName, ... }         │
  → ChatView 检测到 pendingApproval → 渲染 ToolApprovalDialog         │
                                                                      │
[用户点击"允许本次"]                                                    │
  → chatStore.respondToApproval(true)                                 │
  → fetch POST /api/approval/respond                                  │
    请求体: { "toolCallId":"tc-001", "approved":true }                │
                                                                      │
[网关] /api/approval/respond → lb://lyclaw-orchestration-service     │
                                                                      │
[ApprovalController]                                                  │
  approvalStore.approve("tc-001")                                     │
  → future.complete(true)                                             │
                                                                      │
[DefaultReActEngine (boundedElastic 线程)]                             │
  future.get(60, SECONDS) 返回 true                                   │
  → 执行工具: toolExecutor.execute("command", "tc-001", "{\"cmd\":\"ls\"}")
  → 发送 SSE: tool_call(done) 带执行结果                               │
                                                                      │
[前端]                                                                │
  收到 tool_call done 事件 → 展开显示执行结果                          │
```

### 12.2 SSE 事件的 JSON 数据结构详解

#### `message` 事件 — AI 文本回复

```json
// event: message
// data: 你好，我是AI助手

// data 就是纯文本字符串，不一定是 JSON
// 前端会逐 chunk 追加到消息气泡中
```

#### `tool_call` 事件 — 工具执行（executing 和 done）

```json
// executing 阶段:
// event: tool_call
// data:
{
  "toolCallId": "call_abc123",
  "name": "command",
  "status": "executing",
  "message": "正在执行 command...",
  "arguments": "{\"cmd\":\"ls -la\"}",
  "success": true
}

// done 阶段:
// event: tool_call
// data:
{
  "toolCallId": "call_abc123",
  "name": "command",
  "status": "done",
  "message": "command 完成",
  "arguments": "{\"cmd\":\"ls -la\"}",
  "result": "total 48\ndrwxr-xr-x  8 user  staff   256 ...",
  "success": true
}
```

**关键字段说明：**

| 字段 | executing | done | 说明 |
|------|-----------|------|------|
| `toolCallId` | 有 | 有 | 关联 executing 和 done 事件 |
| `status` | `"executing"` | `"done"` | 前端据此切换显示状态 |
| `arguments` | 有 | 有 | 传给工具的 JSON 参数 |
| `result` | 无 | 有 | 工具执行返回结果 |
| `success` | `true` | 实际值 | 工具是否执行成功 |

#### `tool_approval` 事件 — 请求用户确认

```json
// event: tool_approval
// data:
{
  "toolCallId": "call_abc123",
  "toolName": "command",
  "arguments": "{\"cmd\":\"rm -rf /dangerous\"}",
  "message": "AI 请求执行 command"
}
```

#### `done` 事件 — 流结束

```json
// event: done
// data:
{
  "status": "completed"
}

// 或者错误情况下:
{
  "status": "error"
}
```

#### `error` 事件 — 管线级错误

```json
// event: error
// data:
{
  "message": "Connection refused",
  "traceId": "abc123def456",
  "stage": "RESPOND"
}
```

### 12.3 前端如何消费这些事件

```typescript
// 文件: lyclaw-ui/src/api/chat.ts (简化版)
export async function postSSE(
  url: string,
  body: object,
  onChunk: (text: string) => void,
  onStatus?: (text: string) => void,
  onToolCall?: (data: string) => void,
  onApprovalRequired?: (data: string) => void,
): Promise<void> {
  const response = await fetch(url, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  })

  const reader = response.body!.getReader()
  const decoder = new TextDecoder()
  let buffer = ''

  while (true) {
    const { done, value } = await reader.read()
    if (done) break

    buffer += decoder.decode(value, { stream: true })

    // 按 "\n\n" 分隔每个 SSE 事件
    const parts = buffer.split('\n\n')
    buffer = parts.pop() || ''  // 最后一个可能不完整，保留在 buffer

    for (const part of parts) {
      const lines = part.split('\n')
      let eventType = ''
      let data = ''

      for (const line of lines) {
        if (line.startsWith('event: ')) {
          eventType = line.substring(7).trim()
        } else if (line.startsWith('data: ')) {
          data = line.substring(6)
        }
      }

      // 根据事件类型路由到不同回调
      switch (eventType) {
        case 'message':
          onChunk(data)
          break
        case 'status':
          onStatus?.(data)
          break
        case 'tool_call':
          onToolCall?.(data)
          break
        case 'tool_approval':
          onApprovalRequired?.(data)
          break
        case 'done':
          return  // 流结束
      }
    }
  }
}
```

### 12.4 练习题

**题目1**：追踪 LyClaw 中用户发送 "帮我查一下今天的天气" 这句话到前端看到回复的完整数据流。写出关键类和方法。

**题目2**：SSE 流中 `tool_call` 事件的 JSON 结构包含哪些字段？为什么要有 `toolCallId` 字段？

**题目3**：前端 `EventSource` 在三个场景下分别触发什么回调？

```javascript
// 场景1
eventSource.addEventListener('message', (e) => { ... });

// 场景2  
eventSource.addEventListener('tool_call', (e) => { ... });

// 场景3
eventSource.onerror = (e) => { ... };
```

<details>
<summary>点击查看答案与解析</summary>

**题目1答案：**

```
用户输入 "帮我查一下今天的天气"

1. 前端 Vue → POST /api/chat/stream (SSE连接)
   文件: ChatView.vue

2. 网关 → lb://lyclaw-orchestration-service
   文件: GatewayConfig.java

3. OrchestrationController → OrchestratorImpl.executeStream()
   → 管道 Stage 串联:
     SecurityCheckStage → RespondStage

4. RespondStage → reactWithReActEngine()
   → 构建 ToolExecutor (桥接 ActionFeignClient)
   → ReActEngine.executeStream()

5. DefaultReActEngine.executeStream():
   → LLM 返回 tool_calls: [{"name":"command", "arguments":"..."}]
   → emitRoundToolCallEvents()
   → command 在 approvalRequired 中 → emitApprovalFlow()
   → 发 SSE: tool_approval (前端显示审批对话框)
   → 创建 CompletableFuture → 等待用户响应

6. 用户点击 "允许本次" → POST /api/approval/respond
   → ApprovalController → ApprovalStore.approve(toolCallId)
   → CompletableFuture 完成 → future.get() 返回 true

7. toolExecutor.execute("command", callId, args)
   → ActionFeignClient.executeTool() [Feign 调用]
   → lyclaw-action-service → ActionExecutorImpl.executeTool()
   → ToolCallPolicy 检查 → ToolSandbox 执行 shell 命令
   → 返回 ToolExecutionResult

8. 工具结果发 SSE: tool_call (done)
   → 前端显示工具执行结果

9. 继续 ReAct 循环 → LLM 读取工具结果 → 生成文本回复
   → 发 SSE: message (流式文本)
   → 最后发 SSE: done
```

**题目2答案：**

```json
{
  "toolCallId": "call_cb8a9f2e",   // 唯一标识，关联审批和结果
  "toolName": "command",            // 工具名称
  "status": "executing",            // 状态: executing/done/error
  "message": "正在执行 command...", // 人类可读的描述
  "arguments": "{\"command\":\"...\"}", // 工具参数 (JSON字符串)
  "output": "...",                  // 工具输出 (仅 done/error)
  "success": true                   // 是否成功
}
```

`toolCallId` 的关键作用：
- 审批阶段：前端用它调用 `POST /api/approval/respond`（body: `{"toolCallId":"...","approved":true}`）
- 结果匹配：多个工具并发执行时，前端根据 `toolCallId` 匹配每个工具的执行结果
- 调试追踪：日志中通过 `toolCallId` 串联审批→执行→结果全链路

**题目3答案：**

```javascript
// 场景1: 默认 message 事件
// 触发条件: 后端发送没有 event: 行的数据，或 event: message
eventSource.addEventListener('message', (e) => {
    console.log(e.data); // 后端 data: 行的内容
});

// 场景2: 自定义事件
// 触发条件: 后端发送 event: tool_call\n 开头的 SSE 块
eventSource.addEventListener('tool_call', (e) => {
    const json = JSON.parse(e.data);
    // json: { toolCallId, toolName, status, ... }
});

// 场景3: 连接错误
// 触发条件: 网络断开、SSE 连接失败、后端返回非200
eventSource.onerror = (e) => {
    console.log('连接异常，将自动重连...');
    // EventSource 会自动重连，无需手动处理
    // readyState: 0=连接中, 1=已连接, 2=已关闭
};
```

**关键区别**：`EventSource.onerror` 是连接级别（网络/HTTP），`event: error` 是业务事件级别（后端推送的自定义错误事件）。两者完全不同。

</details>

---

## 第13章 调试与排错指南

### 13.1 如何追踪 Reactor 流的执行

#### `Flux<T>` / `Mono<T>` `.log()` — 打印流的生命周期事件

`.log()` 是 Reactor 调试的第一利器。它在流的操作符链中插入一个日志拦截点，当数据流经时会自动打印 `onSubscribe`、`request`、`onNext`、`onError`、`onComplete` 等生命周期事件。你可以在任意位置调用 `.log()` 来观察数据在管道中的流动，也可以传入一个字符串作为日志前缀（如 `"after-map"`），方便在日志中区分不同位置。

```java
// .log() 会在控制台打印流的生命周期事件
Flux.just(1, 2, 3)
    .log()  // 打印: onNext(1), onNext(2), onNext(3), onComplete()
    .map(n -> n * 10)
    .log("after-map")  // 自定义日志前缀
    .subscribe();

// 输出示例:
// [INFO] reactor.Flux.Just.1 - | onSubscribe([Synchronous Fuseable] FluxArray.ArraySubscription)
// [INFO] reactor.Flux.Just.1 - | request(unbounded)
// [INFO] reactor.Flux.Just.1 - | onNext(1)
// [INFO] reactor.Flux.Just.1 - | onNext(2)
// [INFO] reactor.Flux.Just.1 - | onNext(3)
// [INFO] reactor.Flux.Just.1 - | onComplete()
```

#### `Flux<T>` / `Mono<T>` `.doOnNext(Consumer<T>)` / `.doOnError(Consumer<Throwable>)` / `.doOnComplete(Runnable)` — 插入调试日志

与 `.log()` 的自动打印不同，`.doOnNext` / `.doOnError` / `.doOnComplete` 让你自定义每个信号到达时的回调逻辑。你可以在回调中使用项目的日志框架（SLF4J、Log4j 等）输出更精细的调试信息，比如只打印事件的某个字段、记录错误消息、或者在流完成时打一条汇总日志。这些方法不改变流中的数据，是纯粹的副作用操作。

```java
// 在流的任意位置插入调试日志
flux
    .doOnNext(event -> log.info("事件类型: {}", event.event()))
    .doOnError(err -> log.error("流错误: {}", err.getMessage()))
    .doOnComplete(() -> log.info("流完成"))
    .subscribe();
```

#### `static void Hooks.onOperatorDebug()` — 启用操作符级调试堆栈

Reactor 的异常堆栈默认只显示操作符的调用位置，很难看出数据经过了哪些操作符。`Hooks.onOperatorDebug()` 是一个全局开关，启用后 Reactor 会在每个操作符组装时记录堆栈信息，这样当流中抛出异常时，堆栈会显示完整的操作符链路径（即 "Assembly trace"）。代价是每个操作符都额外记录一份堆栈，有持续的性能开销，**仅调试时使用**，生产环境应改用 `ReactorDebugAgent`。

```java
// 在 main() 或 @PostConstruct 中启用
Hooks.onOperatorDebug();
```

#### `Flux<T>` / `Mono<T>` `.checkpoint(String description)` — 组装时追踪点

与 `Hooks.onOperatorDebug()` 的全局堆栈记录不同，`.checkpoint()` 只在你指定的位置记录组装堆栈，精确且开销远小于全局模式。当流中某处抛异常时，异常堆栈的 Assembly trace 会显示你传入的描述标签，帮助你快速定位到出错的那一段管道。推荐在你知道"可能出问题"的操作符前后使用 `.checkpoint("描述")`，既有针对性又不拖累整体性能。

```java
// 在可能的故障点插入 checkpoint
Flux.just("1", "2", "abc", "4")
    .map(s -> Integer.parseInt(s))  // 这里抛异常
    .checkpoint("after-parse")      // ← 异常堆栈中会显示 "after-parse"
    .map(i -> i * 10)
    .subscribe();

// 配合 Hooks.onOperatorDebug() 使用效果更好
// 堆栈会显示:
// Assembly trace from producer [FluxMapFuseable] :
//     Flux.map(Chapter13.java:42)
//     Flux.checkpoint(Chapter13.java:43)    ← "after-parse" 在这里
// Error has been observed at the following site:
//     Flux.map(Chapter13.java:42)
//     ...

// 不加描述也可以（自动生成序号作为标签）
flux.checkpoint();  // 堆栈中显示 "checkpoint()"
```

#### `static void Hooks.onOperatorError(BiConsumer<Throwable, Object>)` — 全局操作符错误处理器

`Hooks.onOperatorError()` 注册一个全局回调，每当操作符链中发生错误时触发。与 `onErrorContinue`（改变错误传播行为）不同，`onOperatorError` 是纯粹的监听钩子——它不改变错误的传播路径，只是给你一个统一的地方来记录日志、发送告警或写入指标。你可以用 `Hooks.resetOnOperatorError()` 恢复默认行为。

```java
Hooks.onOperatorError((throwable, signal) -> {
    log.error("操作符错误, signal={}", signal, throwable);
    // 可以在这里发告警、写指标
});

// 重置为默认行为
Hooks.resetOnOperatorError();
```

#### `static void ReactorDebugAgent.init()` — Java Agent 级调试（生产环境可用）

`ReactorDebugAgent` 是 JVM 级别的字节码增强方案，通过在类加载时修改操作符的字节码来记录组装堆栈，只在首次调用时付出开销，之后无持续性能损耗。这与 `Hooks.onOperatorDebug()` 形成互补：后者在运行时包装每个操作符，有持续开销但配置简单，适合开发调试；前者是 JVM Agent 级别，性能更好，**适合生产环境**。可以通过 JVM 启动参数 `-javaagent:reactor-tools.jar` 或 Spring Boot 的自动配置来启用，也支持程序化调用 `ReactorDebugAgent.init()`。

```java
// 程序化初始化（如果不用 javaagent）
ReactorDebugAgent.init();
```

### 13.2 常见错误排查

**错误1: `block()/blockFirst()/blockLast() are blocking, which is not supported in thread reactor-http-nio-X`**

```
原因: 在 Netty 的事件循环线程中调用了阻塞方法
解决: 用 .subscribeOn(Schedulers.boundedElastic()) 迁移线程
      或者直接返回 Mono/Flux 让 WebFlux 处理
```

**错误2: 流没有输出任何数据**

```
排查步骤:
1. 检查是否有 .subscribe() 调用（没订阅就不执行）
2. 检查 .filter() 条件是否把所有数据都过滤掉了
3. 检查上游是否返回了 Flux.empty()
4. 用 .log() 查看流中到底发生了什么
```

**错误3: 数据被重复处理**

```
原因: Flux.defer 创建的流每次订阅都会重新执行
     如果下游有多个订阅者，每个都会触发一次 defer
     或者用 Flux.just() 包装了可变对象
```

**错误4: 并发导致的数据交错**

```
现象: flatMap 输出的顺序不可预测
原因: flatMap 不保证顺序，多个子流可以并发执行
解决: 如果需要顺序，使用 concatMap
```

### 13.3 Hooks 全局配置

```java
// Reactor 提供了全局的 Hooks 机制

// 1. 自动上下文传播 (LyClaw 中用于 MDC traceId 传播)
Hooks.enableAutomaticContextPropagation();
// 效果: 在 Flux/Mono 操作符切换线程时，自动传递 MDC 中的值
//       确保 traceId 在整个请求链路中不丢失

// 2. 丢弃事件的全局钩子
Hooks.onNextDropped(item ->
    log.warn("元素被丢弃: {}", item)
);

// 3. 错误丢弃钩子
Hooks.onErrorDropped(error ->
    log.error("未处理的错误: ", error)
);
```

### 13.4 练习题

**题目1**：下面的流没有任何输出，也不报错。如何用 `.log()` 诊断？

```java
Flux.range(1, 5)
    .filter(n -> n > 10)
    .map(n -> n * 2)
    .subscribe(System.out::println);
```

**题目2**：调试时看到日志 `[ERROR] onOperatorError: java.lang.NullPointerException` 但没有堆栈信息，如何启用详细堆栈？

**题目3**：下面代码抛出 `IllegalStateException: block()/blockFirst()/blockLast() are blocking`，为什么？如何修复？

```java
@GetMapping("/test")
public String test() {
    return Mono.just("hello").block();  // 这里报错
}
```

<details>
<summary>点击查看答案与解析</summary>

**题目1答案：**

```java
Flux.range(1, 5)
    .log("过滤前")           // 添加在 filter 前
    .filter(n -> n > 10)
    .log("过滤后")           // 添加在 filter 后
    .map(n -> n * 2)
    .subscribe(System.out::println);

// 日志输出会显示:
// [过滤前] | onSubscribe(...)
// [过滤前] | request(unbounded)
// [过滤前] | onNext(1)      ← 1 进入 filter
// [过滤前] | onNext(2)      ← 2 进入 filter
// ...
// 但 [过滤后] 没有任何 onNext → 说明 filter 把所有元素都过滤掉了！
// 根本原因: filter(n -> n > 10) 过滤掉所有 1-5，因为都不大于 10
```

**解析**：`.log()` 在信号流经时打印日志，显示操作符位置收到的所有 Reactive Streams 信号。通过比较 `.log("前")` 和 `.log("后")` 的输出，可以精确定位数据在哪个操作符消失。`.log()` 的常用插法：在怀疑有问题的操作符前后各加一个 `.log()`。

**题目2答案：**

```java
// 方式1: 系统属性（JVM启动参数）
// -Dreactor.trace.operatorStacktrace=true

// 方式2: 代码中启用
Hooks.onOperatorDebug();

// 启用后，异常信息中会包含完整的操作符链:
// Assembly trace from producer [FluxMap] :
//     Flux.map(MyClass.java:42)
//     Flux.filter(MyClass.java:41)
//     Flux.just(MyClass.java:40)

// 但注意：onOperatorDebug 有性能开销，仅在开发/调试环境使用
// 生产环境应关闭，改用 .checkpoint() 在关键位置添加轻量级追踪
```

**题目3答案：**

错误原因：在 Netty worker 线程中调用了 `.block()`。WebFlux 默认使用 Reactor 的**阻塞检测机制**，任何在非阻塞线程（如 Netty worker）上调用 `.block()` 都会被拒绝。

```java
// ❌ 错误：在 Controller 线程中 block
@GetMapping("/test")
public String test() {
    return Mono.just("hello").block();
}

// ✅ 修复1: 返回 Mono（使用响应式编程）
@GetMapping("/test")
public Mono<String> test() {
    return Mono.just("hello");
}

// ✅ 修复2: 如果必须阻塞，迁移到弹性线程
@GetMapping("/test")
public Mono<String> test() {
    return Mono.fromCallable(() ->
        Mono.just("hello").block()  // 在 boundedElastic 线程，安全
    ).subscribeOn(Schedulers.boundedElastic());
}
```

**解析**：WebFlux Controller 方法运行在 Netty 的 IO 线程上，`.block()` 会阻塞该线程，导致线程饥饿。Reactor 框架会在检测到这种危险操作时直接抛出 `IllegalStateException` 阻止你犯错。

</details>

---

## 第14章 与其他技术的对比与桥接

### 14.1 CompletableFuture vs Mono

很多开发者先学会 `CompletableFuture`，然后困惑何时用它，何时用 `Mono`。

| 特性 | CompletableFuture | Mono |
|------|-------------------|------|
| 创建即执行 | 是（supplyAsync 立即执行） | 否（惰性，订阅才执行） |
| 操作符丰富度 | 基础（thenApply/thenCompose） | 丰富（120+ 操作符） |
| 重试支持 | 需要手动实现 | 内置 retry() 操作符 |
| 背压（backpressure） | 不支持 | 支持 |
| 多个结果（0-N） | 不支持 | 用 Flux |
| Spring 集成 | 手动集成 | WebFlux 原生支持 |

**何时用哪个？**

```java
// 用 CompletableFuture 的场景:
//   - 独立的异步任务（不需要和其他异步流组合）
//   - 只需要简单的 thenApply/thenCompose 链
//   - 不需要背压

// 用 Mono 的场景:
//   - Spring WebFlux Controller 返回值
//   - 需要和 Flux 组合（flatMapMany、concatWith 等）
//   - 需要复杂的错误处理、重试、超时
//   - 需要惰性执行

// 桥接: CompletableFuture → Mono
CompletableFuture<String> future = CompletableFuture.supplyAsync(() -> "结果");
Mono<String> mono = Mono.fromFuture(future);

// 桥接: Mono → CompletableFuture
Mono<String> mono = Mono.just("结果");
CompletableFuture<String> future = mono.toFuture();
```

### 14.2 Java Stream vs Flux

| 特性 | Stream | Flux |
|------|--------|------|
| 执行方式 | 终端操作立即执行 | 惰性，订阅时执行 |
| 异步 | 不支持 | 原生支持 |
| 线程切换 | 手动 | subscribeOn/publishOn |
| 元素数量 | 有限 | 有限或无限 |
| 错误处理 | try-catch | onErrorReturn/onErrorResume |

```java
// Stream 风格（同步）
List<String> result = list.stream()
    .filter(s -> s.length() > 3)
    .map(String::toUpperCase)
    .collect(Collectors.toList());
// 这行代码执行完，result 就是最终结果

// Flux 风格（异步）
Flux<String> result = Flux.fromIterable(list)
    .filter(s -> s.length() > 3)
    .map(String::toUpperCase);
// 这行代码执行完，什么也没发生
// 需要 .subscribe() 或 .collectList().block() 才真正执行
```

### 14.3 回调地狱 → Reactor 链

```java
// 回调地狱（Callback Hell）:
httpClient.get("/user", user -> {
    httpClient.get("/orders/" + user.id, orders -> {
        httpClient.get("/details/" + orders.get(0).id, details -> {
            System.out.println(details);
        }, error -> log.error("details失败"));
    }, error -> log.error("orders失败"));
}, error -> log.error("user失败"));

// Reactor 链式调用（扁平化）:
httpClient.getReactive("/user")                  // Mono<User>
    .flatMap(user -> httpClient.getReactive(     // flatMap 展平嵌套
        "/orders/" + user.getId()                // Mono<Order>
    ))
    .flatMap(orders -> httpClient.getReactive(
        "/details/" + orders.get(0).getId()      // Mono<Detail>
    ))
    .subscribe(
        detail -> System.out.println(detail),
        error -> log.error("请求失败", error)
    );
// 每个 flatMap 解开一层嵌套，代码保持扁平
```

### 14.4 练习题

**题目1**：以下场景分别应该用 `CompletableFuture` 还是 `Mono`？为什么？

- A. 查询数据库获取一个用户（使用 JDBC 阻塞驱动）
- B. 调用 3 个微服务并合并结果（非阻塞 WebClient）
- C. 定时任务中执行一个异步操作，然后转同步等待

**题目2**：将下面的回调地狱改写为 Reactor 链式调用：

```java
httpClient.get("/user", user -> {
    System.out.println("获取用户: " + user);
    httpClient.get("/orders/" + user.getId(), orders -> {
        System.out.println("获取订单: " + orders);
        httpClient.get("/details/" + orders.get(0).getId(), details -> {
            System.out.println("获取详情: " + details);
        }, error -> log.error("详情失败"));
    }, error -> log.error("订单失败"));
}, error -> log.error("用户失败"));
```

**题目3**：Java Stream 和 Reactor Flux 在并发处理上的本质区别是什么？

<details>
<summary>点击查看答案与解析</summary>

**题目1答案：**

```
A → CompletableFuture
  原因: JDBC 是阻塞的，无法用非阻塞驱动。CompletableFuture + 自定义线程池
  是最直接的异步化方案。用 Mono 也不会获得非阻塞优势（JDBC 本身会阻塞）。

B → Mono
  原因: WebClient 是非阻塞的，天然返回 Mono/Flux。多个 Mono 可以用
  Mono.zip(m1, m2, m3, (r1, r2, r3) -> ...) 并发调用并合并。

C → CompletableFuture
  原因: 需要最终转同步等待。CompletableFuture.join() 比 Mono.block()
  更适合在非 WebFlux 场景中使用，不会触发 Reactor 的阻塞检测。
```

**选择原则**：
- 已经是 Spring WebFlux 项目 → Mono/Flux
- 已有大量 CompletableFuture 代码 → 保持，或在边界桥接（`Mono.fromFuture`）
- 非 Spring / 传统 Spring MVC 项目 → CompletableFuture
- 需要丰富的操作符（重试、超时、组合）→ Mono/Flux

**题目2答案：**

```java
// Reactor 链式调用（扁平化）
httpClient.getReactive("/user")                  // Mono<User>
    .doOnNext(user -> System.out.println("获取用户: " + user))
    .flatMap(user -> httpClient.getReactive(     // 展平为 Mono<Order>
        "/orders/" + user.getId()
    ))
    .doOnNext(orders -> System.out.println("获取订单: " + orders))
    .flatMap(orders -> httpClient.getReactive(   // 展平为 Mono<Detail>
        "/details/" + orders.get(0).getId()
    ))
    .doOnNext(details -> System.out.println("获取详情: " + details))
    .subscribe(
        details -> System.out.println("最终结果: " + details),
        error -> log.error("请求失败", error)
    );

// 对比:
// 回调版本: 嵌套3层，每层有独立的错误处理，阅读困难
// Reactor 版本: 扁平链式，错误在末尾统一处理，阅读顺序 = 执行顺序
```

**题目3答案：**

**本质区别**：
- `Stream.parallel()` 是**数据并行**——将一个数据集切分到多个 CPU 线程同时处理，目标是加速**计算密集型**任务。它是**同步**的（调用 terminal 操作时阻塞等待全部完成）。
- `Flux` 的并发是**异步非阻塞**——操作符在不同线程执行，目标是处理**IO 密集型**任务（高并发请求）。它是**异步**的（不阻塞调用线程）。

```java
// Stream parallel: 数据并行，阻塞等待
List<Integer> result = IntStream.range(1, 100)
    .parallel()                         // 在 ForkJoinPool 上并行
    .map(this::heavyCompute)            // CPU 密集计算
    .boxed()
    .collect(Collectors.toList());      // 阻塞，等全部完成

// Flux: 异步非阻塞，立即返回
Flux.range(1, 100)
    .flatMap(i -> Mono.fromCallable(() -> heavyCompute(i))
        .subscribeOn(Schedulers.parallel()))  // 在 parallel 线程池执行
    .collectList()
    .subscribe(list -> System.out.println("完成"));  // 不阻塞，回调通知
```

**选择原则**：
- 内存数据集 + CPU 密集 → `Stream.parallel()`
- 网络/数据库 IO + 高并发 → `Flux` + `Schedulers`
- 数据在内存中 → Stream；数据从外部源产生 → Flux

</details>

---

*本文档基于 LyClaw 项目实际代码编写，所有案例均来自 `lyclaw-framework` 和 `lyclaw-orchestration` 模块的真实实现。*

*如果阅读本文档后仍有疑惑，建议配合实际源码对照阅读。代码是最好的文档。*
