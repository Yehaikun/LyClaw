# 引擎-05-Interceptor 拦截器层

**Metadata**
- Date: 2026-04-29
- 所属模块: lyclaw-engine
- 包路径: `lyjew.com.lyclaw.interceptor`
- 依赖: ChatContext（context 包）、ChatResult（dto 包）、EventBus（可选，发布事件）
- 并行前提: 依赖 ChatContext 接口，可和 Tool 层、Memory 层并行开发

---

## 核心职责

请求处理前后的横切关注点：限流、日志、脱敏、缓存、审计等。每个拦截器只负责一个关注点。

---

## 需要实现的类清单

### 1. Interceptor — 拦截器接口

**文件**: `interceptor/Interceptor.java`
**包**: `lyjew.com.lyclaw.interceptor`

| 元素 | 说明 |
|------|------|
| 类型 | 接口 |
| 设计模式 | 责任链模式 |

**方法**:
| 方法签名 | 返回值 | 说明 |
|----------|--------|------|
| int getOrder() | int | 执行顺序，数字越小越先执行。限流 order 应最小（最先执行） |
| void preHandle(ChatContext context) | void | 请求前处理。可以修改 context、抛异常中断请求 |
| void postHandle(ChatResult result) | void | 请求后处理。可以修改 result |

---

### 2. InterceptorChain — 拦截器链管理器

**文件**: `interceptor/InterceptorChain.java`
**包**: `lyjew.com.lyclaw.interceptor`

| 元素 | 说明 |
|------|------|
| 类型 | 类，@Component |
| 设计模式 | 责任链模式的管理器 |

**属性**:
| 名称 | 类型 | 说明 |
|------|------|------|
| interceptors | List\<Interceptor\> | 排序后的拦截器列表 |

**初始化**:
- 启动时，Spring 自动注入所有 Interceptor 实现
- 按 getOrder() 排序后存入列表

**方法**:
| 方法签名 | 返回值 | 说明 |
|----------|--------|------|
| void addInterceptor(Interceptor interceptor) | void | 手动添加拦截器（非 @Component 方式） |
| void doPreHandle(ChatContext context) | void | 遍历 interceptors，按序调用 preHandle(context)。任何一个抛异常就中断后续 |
| void doPostHandle(ChatResult result) | void | 逆序遍历 interceptors，调用 postHandle(result) |
| List\<Interceptor\> getInterceptors() | List\<Interceptor\> | 返回所有拦截器 |

---

### 3. RateLimitInterceptor — 限流拦截器

**文件**: `interceptor/impl/RateLimitInterceptor.java`
**包**: `lyjew.com.lyclaw.interceptor.impl`
**实现**: Interceptor

| 元素 | 说明 |
|------|------|
| 类型 | 类，@Component |
| order | 10（最先执行） |

**属性**:
| 名称 | 类型 | 说明 |
|------|------|------|
| rateLimiter | Map\<String, RateLimitCounter\> | 用户/IP → 请求计数器（ConcurrentHashMap） |
| maxRequests | int | 单位时间内最大请求数（配置化，默认 10） |
| windowMs | long | 时间窗口毫秒（配置化，默认 60000 = 1 分钟） |

**方法**:
| 方法签名 | 返回值 | 说明 |
|----------|--------|------|
| getOrder() | int | 返回 10 |
| preHandle(ChatContext context) | void | 从 context.getRequest() 获取用户标识 → 查询计数器 → 超限则抛出 RateLimitExceededException → 否则计数+1 |
| postHandle(ChatResult result) | void | 空实现 |

**异常**: 抛 `RateLimitExceededException`（需新建，或复用 LyClawException）

---

### 4. RateLimitExceededException — 限流异常

**文件**: `interceptor/impl/RateLimitExceededException.java`
**包**: `lyjew.com.lyclaw.interceptor.impl`

| 属性 | 类型 | 说明 |
|------|------|------|
| userId | String | 被限流的用户标识 |
| retryAfterMs | long | 建议多久后重试 |

| 方法 | 返回值 | 说明 |
|------|--------|------|
| RateLimitExceededException(String userId, long retryAfterMs) | - | 构造器 |

---

### 5. RateLimitCounter — 限流计数器（辅助类）

**文件**: `interceptor/impl/RateLimitCounter.java`
**包**: `lyjew.com.lyclaw.interceptor.impl`

| 属性 | 类型 | 说明 |
|------|------|------|
| count | int | 当前窗口内请求数 |
| windowStart | long | 窗口开始时间戳 |
| maxRequests | int | 限制数 |

| 方法 | 返回值 | 说明 |
|------|--------|------|
| RateLimitCounter(int maxRequests) | - | 构造器 |
| synchronized boolean tryAcquire() | boolean | 如果超出窗口，重置；否则 count+1 并返回是否超限 |
| long getRetryAfterMs() | long | 返回距离窗口结束的毫秒数 |

---

### 6. LoggingInterceptor — 日志拦截器

**文件**: `interceptor/impl/LoggingInterceptor.java`
**包**: `lyjew.com.lyclaw.interceptor.impl`
**实现**: Interceptor

| 元素 | 说明 |
|------|------|
| 类型 | 类，@Component |
| order | 100（中间执行） |

**属性**:
| 名称 | 类型 | 说明 |
|------|------|------|
| logger | Logger | SLF4J Logger |

**方法**:
| 方法签名 | 返回值 | 说明 |
|----------|--------|------|
| getOrder() | int | 返回 100 |
| preHandle(ChatContext context) | void | 记录请求开始时间（存到 context.metadata）、打印请求信息（sessionId、消息条数、模型名） |
| postHandle(ChatResult result) | void | 计算耗时（从 context.metadata 取开始时间→System.currentTimeMillis()）、打印响应摘要（token 数、finishReason） |

---

### 7. SensitiveDataInterceptor — 脱敏拦截器

**文件**: `interceptor/impl/SensitiveDataInterceptor.java`
**包**: `lyjew.com.lyclaw.interceptor.impl`
**实现**: Interceptor

| 元素 | 说明 |
|------|------|
| 类型 | 类，@Component |
| order | 50 |

**方法**:
| 方法签名 | 返回值 | 说明 |
|----------|--------|------|
| getOrder() | int | 返回 50 |
| preHandle(ChatContext context) | void | 遍历 context.messages 中的用户消息，扫描手机号/邮箱/身份证号等正则，替换为脱敏格式（手机号: 138****1234，邮箱: s***@qq.com） |
| postHandle(ChatResult result) | void | 第一版不做响应脱敏，空实现 |

---

## 实现顺序

1. Interceptor 接口
2. InterceptorChain
3. RateLimitCounter + RateLimitExceededException
4. RateLimitInterceptor
5. LoggingInterceptor
6. SensitiveDataInterceptor

## 校验清单

- [ ] Interceptor 接口含 getOrder、preHandle、postHandle
- [ ] InterceptorChain 启动时自动注入所有 Interceptor、按 order 排序
- [ ] preHandle 按正序执行，postHandle 按逆序执行
- [ ] RateLimitInterceptor order=10，限流逻辑正确
- [ ] LoggingInterceptor order=100，记录耗时
- [ ] SensitiveDataInterceptor order=50，脱敏手机号/邮箱
