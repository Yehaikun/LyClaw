# 引擎-10-Config 配置层

**Metadata**
- Date: 2026-04-29
- 所属模块: lyclaw-engine
- 包路径: `lyjew.com.lyclaw.config`
- 依赖: 无业务依赖，纯配置类

---

## 核心职责

引擎层的配置属性类和自动配置注册。

---

## 需要实现的类清单

### 1. EngineProperties — 引擎配置属性类

**文件**: `config/EngineProperties.java`
**包**: `lyjew.com.lyclaw.config`

| 元素 | 说明 |
|------|------|
| 类型 | 类，@ConfigurationProperties(prefix = "lyclaw.engine") |

**属性**:
| 名称 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| enabled | boolean | true | 是否启用引擎层 |
| defaultEngine | String | "default" | 默认引擎名称 |
| maxToolCallRounds | int | 10 | 工具调用最大轮次 |
| memoryEnabled | boolean | true | 是否启用记忆管理 |
| memoryStrategy | String | "manual" | 记忆提取策略名称 |
| pipelineStages | List\<String\> | ["context_build", "interceptor", "tool_call_loop", "metrics", "response_build"] | 启用的管道阶段列表 |

| 方法 | 返回值 | 说明 |
|------|--------|------|
| 所有属性 Getter/Setter | - | - |

---

### 2. EngineAutoConfiguration — 引擎自动配置

**文件**: `config/EngineAutoConfiguration.java`
**包**: `lyjew.com.lyclaw.config`

| 元素 | 说明 |
|------|------|
| 类型 | 类，@Configuration |
| 注解 | @EnableConfigurationProperties(EngineProperties.class) |
| 注解 | @ComponentScan("lyjew.com.lyclaw.engine") |
| 注解 | @ComponentScan("lyjew.com.lyclaw.pipeline") |
| 注解 | @ComponentScan("lyjew.com.lyclaw.context") |
| 注解 | @ComponentScan("lyjew.com.lyclaw.interceptor") |
| 注解 | @ComponentScan("lyjew.com.lyclaw.tool") |
| 注解 | @ComponentScan("lyjew.com.lyclaw.memory") |
| 注解 | @ComponentScan("lyjew.com.lyclaw.event") |
| 注解 | @ComponentScan("lyjew.com.lyclaw.error") |
| 注解 | @ComponentScan("lyjew.com.lyclaw.agent") |

**方法**: 无特定方法，纯配置声明。

**条件注册示例（可选）**:
| 方法 | 说明 |
|------|------|
| @Bean @ConditionalOnMissingBean(EventBus.class) EventBus defaultEventBus() | 无 EventBus 时注入 InMemoryEventBus |
| @Bean @ConditionalOnMissingBean(ErrorPolicy.class) ErrorPolicy defaultErrorPolicy() | 无 ErrorPolicy 时注入 DefaultErrorPolicy |

---

## 实现顺序

1. EngineProperties
2. EngineAutoConfiguration

## 校验清单

- [ ] EngineProperties 使用 @ConfigurationProperties(prefix = "lyclaw.engine")
- [ ] EngineAutoConfiguration 扫描所有引擎层包路径
- [ ] 条件注入兜底实现（@ConditionalOnMissingBean）
