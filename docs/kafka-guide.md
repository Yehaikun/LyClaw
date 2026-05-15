# Kafka 简明概念与使用

> 面向 LyClaw 项目的快速上手，不涉及生产调优。

## 概念架构

**Kafka 是什么：** 分布式消息队列。生产者往里面写消息，消费者从里面读消息。消息落磁盘，不丢。

```
Producer-1 ──┐                    ┌── Consumer-1（落库）
             │                    │
Producer-2 ──┤  →  Kafka Broker  ├── Consumer-2（记忆提取）
             │     (磁盘持久化)    │
Producer-3 ──┘                    └── Consumer-3（Token 统计）
```

### 三个核心角色

| 角色 | 干什么 | LyClaw 里谁当 |
|------|--------|-------------|
| **Producer** 生产者 | 发消息到 Kafka | Orchestrator（对话结束后发提取请求） |
| **Broker** | 存消息、转发消息 | 你刚部署的那个 Kafka 容器 |
| **Consumer** 消费者 | 从 Kafka 拉消息处理 | Java 消费代码（批量写入 PG） |

### 两个关键概念

**Topic（主题）**——消息的分类标签。生产者发到某个 topic，消费者订阅某个 topic。类比：数据库里的表，消息就是一行行记录。

```
Topic: lyclaw-memory-extraction
├── 消息1: {"sessionId":"xxx","messages":[...]}   ← offset 0
├── 消息2: {"sessionId":"yyy","messages":[...]}   ← offset 1
├── 消息3: {"sessionId":"zzz","messages":[...]}   ← offset 2
└── ...
```

**Partition（分区）**——一个 topic 可以拆成多个 partition，实现并行读写、水平扩展。单节点只有 1 个 partition，够用了。

```
Topic: lyclaw-memory-extraction  (3 分区)
├── Partition 0: 消息0, 消息3, 消息6...
├── Partition 1: 消息1, 消息4, 消息7...
└── Partition 2: 消息2, 消息5, 消息8...
```

**Consumer Group（消费者组）**——同一组的消费者共享 offset，一条消息只被组内一个消费者处理。不同组各自独立消费。

```
           ┌── Consumer-A (落库组)
Topic ─────┤
           └── Consumer-B (提取组)
```

### 消息不删

Kafka 的消息被消费后**不会删除**。删不删由保留策略决定：
- `retention.ms`：按时间（如 7 天后删）
- `retention.bytes`：按大小（如 topic 超 10GB 删最旧的）

## Docker 命令行操作

进入 Kafka 容器：

```bash
docker exec -it lyclaw-kafka bash
```

以下命令都在容器内执行。工具路径：`/opt/kafka/bin/`

### 查看状态

```bash
# 列出所有 topic
/opt/kafka/bin/kafka-topics.sh --list --bootstrap-server localhost:9092

# 查看某个 topic 详情（分区数、消息条数）
/opt/kafka/bin/kafka-topics.sh --describe --topic lyclaw-memory-extraction --bootstrap-server localhost:9092

# 查看消费者组列表
/opt/kafka/bin/kafka-consumer-groups.sh --list --bootstrap-server localhost:9092

# 查看消费者组消费进度（LAG 列=0 表示全消费完了）
/opt/kafka/bin/kafka-consumer-groups.sh --group 你的组名 --describe --bootstrap-server localhost:9092
```

### 创建/删除 Topic

```bash
# 创建 topic（分区数 1，单节点够用）
/opt/kafka/bin/kafka-topics.sh --create \
  --topic lyclaw-memory-extraction \
  --partitions 1 \
  --bootstrap-server localhost:9092

# 删除 topic
/opt/kafka/bin/kafka-topics.sh --delete \
  --topic lyclaw-memory-extraction \
  --bootstrap-server localhost:9092
```

### 收发消息（调试用）

```bash
# 终端 A：发消息（一行一条，Ctrl+C 退出）
/opt/kafka/bin/kafka-console-producer.sh \
  --topic lyclaw-memory-extraction \
  --bootstrap-server localhost:9092

# 终端 B：收消息（从最早开始读，实时等新消息）
/opt/kafka/bin/kafka-console-consumer.sh \
  --topic lyclaw-memory-extraction \
  --from-beginning \
  --bootstrap-server localhost:9092
```

## Spring Boot 集成（Java）

### 依赖

```xml
<!-- pom.xml -->
<dependency>
    <groupId>org.springframework.kafka</groupId>
    <artifactId>spring-kafka</artifactId>
</dependency>
```

### 配置

```yaml
# application.yml
spring:
  kafka:
    bootstrap-servers: localhost:9092
    producer:
      key-serializer: org.apache.kafka.common.serialization.StringSerializer
      value-serializer: org.apache.kafka.common.serialization.StringSerializer
      acks: 1                         # Leader 写入即确认
    consumer:
      key-deserializer: org.apache.kafka.common.serialization.StringDeserializer
      value-deserializer: org.apache.kafka.common.serialization.StringDeserializer
      group-id: lyclaw-memory-consumer
      auto-offset-reset: earliest     # 首次启动从最早消息开始读
```

### 生产者代码

```java
@Component
public class MemoryExtractionProducer {

    private final KafkaTemplate<String, String> kafkaTemplate;

    public MemoryExtractionProducer(KafkaTemplate<String, String> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    public void sendExtractionRequest(String sessionId, String messagesJson) {
        kafkaTemplate.send("lyclaw-memory-extraction", sessionId, messagesJson)
                .whenComplete((result, ex) -> {
                    if (ex != null) {
                        log.error("Kafka 发送失败，降级写本地日志", ex);
                    }
                });
    }
}
```

关键点：`send()` 是异步的，返回 `CompletableFuture`。`.whenComplete()` 处理成功/失败回调。失败降级写本地日志，Kafka 恢复后回放。

### 消费者代码

```java
@Component
public class MemoryExtractionConsumer {

    @KafkaListener(topics = "lyclaw-memory-extraction")
    public void onMessage(ConsumerRecord<String, String> record) {
        String sessionId = record.key();
        String messagesJson = record.value();

        // 1. 调用 DeepSeek 提取记忆
        // 2. 写入 PostgreSQL
        // 3. 更新 Redis 缓存

        // 注意：不要手动 commit offset，spring-kafka 默认自动提交
        // 如果处理失败抛异常，会重试
    }
}
```

关键点：`@KafkaListener` 自动监听指定 topic。默认 `enable.auto.commit=true`，消息处理完自动提交 offset。处理抛异常会重试。

### 手动提交 offset（批量写入场景）

```java
@Component
public class BatchMemoryConsumer {

    private final List<MemoryEntry> buffer = new ArrayList<>();

    @KafkaListener(topics = "lyclaw-memory-extraction")
    public void onMessage(ConsumerRecord<String, String> record,
                          Acknowledgment ack) {
        buffer.add(parseEntry(record.value()));

        if (buffer.size() >= 256) {
            memoryEntryMapper.saveBatch(buffer);    // MyBatis-Plus 批量 INSERT
            buffer.clear();
            ack.acknowledge();                      // 处理完再提交 offset
        }
    }
}
```

把 `spring.kafka.consumer.enable-auto-commit` 设为 `false`，用 `Acknowledgment.ack()` 手动提交，保证"落库成功才确认消费"。

## LyClaw 项目中的使用场景

| Topic | 生产者 | 消费者 | 说明 |
|-------|--------|--------|------|
| `lyclaw-memory-extraction` | Orchestrator | Memory Service | 对话结束 → 异步提取记忆 |

**数据流：**

```
用户发消息 → Orchestrator → DeepSeek → SSE 回复给用户（不等 Kafka）
                                    ↓
                         producer.send(对话 JSON)  ← 异步
                                    ↓
                           Kafka 落地（~5ms）
                                    ↓
                         Consumer 拉取 → 调 LLM 提取 → 写 PG
```

## 常用排错命令

```bash
# 进入容器
docker exec -it lyclaw-kafka bash

# topic 有没有创建
/opt/kafka/bin/kafka-topics.sh --list --bootstrap-server localhost:9092

# 有没有消息积压（看 LAG）
/opt/kafka/bin/kafka-consumer-groups.sh --all-groups --describe --bootstrap-server localhost:9092

# 看最新 10 条消息
/opt/kafka/bin/kafka-console-consumer.sh \
  --topic lyclaw-memory-extraction \
  --max-messages 10 \
  --from-beginning \
  --bootstrap-server localhost:9092

# 看 topic 消息总数（需要脚本，或者用 describe 看各分区 offset）
/opt/kafka/bin/kafka-run-class.sh kafka.tools.GetOffsetShell \
  --topic lyclaw-memory-extraction \
  --time -1 \
  --bootstrap-server localhost:9092
```
