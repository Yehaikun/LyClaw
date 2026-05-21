# 会话及Agent存储设计 — 具体代码实现思路

> 基于 [会话及agent存储设计.md](./会话及agent存储设计.md) 的设计决策，结合当前项目源码现状，逐模块给出具体的Java类、包路径、关键代码片段和集成方式。

---

## 1. 当前源码现状

### 1.1 已有但需替换的代码

| 类 | 位置 | 现状 | 去向 |
|---|---|---|---|
| `storage/*` (24个文件) | `lyclaw-framework/.../storage/` | 旧多后端抽象：StorageBackend/FileBackend/InMemoryBackend/StorageFacade/StorageProperties等 | **全部删除**，见§17.1 |
| `annotation/storage/*` (9个文件) | `lyclaw-framework/.../annotation/storage/` | 旧存储标记注解：@SessionStore/@EntityStore/@MemoryStore/@StorageBackend等 | **全部删除**，见§17.1 |
| `autoconfigure/StorageAutoConfiguration` | `lyclaw-autoconfigure/.../autoconfigure/` | 旧存储Bean装配（FileBackend/StorageFacade等） | **删除**，替换为lyclaw-web中的新StorageAutoConfiguration |
| `autoconfigure/processor/StorageBackendPostProcessor` | `lyclaw-autoconfigure/.../processor/` | 扫描@StorageBackend注解的BeanPostProcessor | **删除** |
| `autoconfigure/processor/WritePolicyPostProcessor` | `lyclaw-autoconfigure/.../processor/` | 扫描@WritePolicy注解的BeanPostProcessor | **删除** |
| `autoconfigure/processor/MemorySystemAutoConfigurator` | `lyclaw-autoconfigure/.../processor/` | MemoryStore后端配置InitializingBean | **删除** |
| `SubagentSessionManager` | `lyclaw-framework/.../react/subagent/` | 基于ConcurrentHashMap的子Agent会话跟踪，使用层级key | **重写**，Object sessionStore→SessionFactory接口 |
| `SessionPersistence` | `lyclaw-framework/.../persistence/session/SessionPersistence.java` | 持久化写入决策接口（evaluate/evaluateOnClose） | **保留接口**，实现类改为基于新存储层 |
| `PersistenceDecision/PersistenceSignal` | `lyclaw-framework/.../persistence/` | 不变值对象和枚举（WRITE/DEFER/SKIP） | **保留**，与旧存储系统无关 |
| `StorageException` | `lyclaw-framework/.../exception/` | 通用存储异常 | **保留**，新SQLite层可复用 |
| `TieredMemorySystem` | `lyclaw-memory/.../impl/` | @MemoryStore注解标记 | **修剪**，移除@MemoryStore注解 |

### 1.2 当前依赖

- **没有SQLite依赖** — 所有POM中均无`sqlite-jdbc`或任何JDBC数据库驱动
- 框架层仅依赖: slf4j, jackson, lombok, reactor-core, spring-context(provided), spring-boot(provided)
- Web层依赖: spring-boot-starter-webflux, springdoc-openapi

### 1.3 当前Session模型

`Session.java` 已扩展完毕，包含所有持久化字段：
- `sessionId`, `name`, `model` — 原有字段
- `agentId`, `filePath`, `messageIndex`, `compactionCount` — 新增持久化字段
- `parentSessionId`, `parentAgentId` — 父子关系
- `heartbeatMode` — 心跳标记
- `createdAt`/`updatedAt` — 继承自BaseDTO（LocalDateTime类型）
- `messages: List<Message>` — 内存中的消息列表

### 1.4 ChatController现状

```java
// 当前: 纯内存，无持久化
@PostMapping("/sessions")
public Session createSession(@RequestBody(required = false) ChatRequest request) {
    Session session = new Session();
    session.setSessionId(UUID.randomUUID().toString().substring(0, 8));
    return session;  // 没有写入任何存储
}
```

---

## 2. 架构总览

```
┌─────────────────────────────────────────────────────────────────┐
│                       lyclaw-web (Spring Boot)                   │
│  ┌──────────────┐  ┌──────────────────┐  ┌───────────────────┐  │
│  │ ChatController│  │ AgentController  │  │ SessionController │  │
│  └──────┬───────┘  └────────┬─────────┘  └────────┬──────────┘  │
│         │                   │                      │             │
│         ▼                   ▼                      ▼             │
│  ┌──────────────────────────────────────────────────────────┐   │
│  │              SessionManager (会话生命周期)                  │   │
│  │   activeSessions: ConcurrentHashMap<String, Session>      │   │
│  └──────┬──────────────┬──────────────┬─────────────────────┘   │
│         │              │              │                          │
│         ▼              ▼              ▼                          │
│  ┌───────────┐  ┌────────────┐  ┌──────────────────┐           │
│  │SessionRepo│  │ AgentRepo  │  │ AsyncWriteQueue   │           │
│  │(JSONL+    │  │(SQLite+    │  │ (per-session      │           │
│  │ SQLite)   │  │ agent.json)│  │  BlockingQueue)   │           │
│  └─────┬─────┘  └─────┬──────┘  └────────┬─────────┘           │
│        │              │                   │                      │
│        ▼              ▼                   ▼                      │
│  ┌──────────────────────────────────────────────────────────┐   │
│  │              lyclaw-framework (存储引擎)                    │   │
│  │  SqliteConnectionManager / JsonlWriter / JsonlReader      │   │
│  └──────────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────────┘
```

**模块归属**：
- **lyclaw-framework**: 所有存储引擎类（SQLite连接、JSONL读写、Repository接口、异步队列）
- **lyclaw-web**: Controller、SessionManager（依赖注入和Web生命周期管理）

---

## 3. 新增依赖

### 3.1 父POM (`pom.xml`) — 添加版本管理

```xml
<!-- 在 <dependencyManagement> 中添加 -->
<dependency>
    <groupId>org.xerial</groupId>
    <artifactId>sqlite-jdbc</artifactId>
    <version>3.49.1.0</version>
</dependency>
```

### 3.2 lyclaw-framework/pom.xml — 添加编译依赖

```xml
<!-- SQLite JDBC 驱动（框架层直接使用JDBC，不依赖Spring Data/Hibernate） -->
<dependency>
    <groupId>org.xerial</groupId>
    <artifactId>sqlite-jdbc</artifactId>
</dependency>
```

> **设计决策**: 使用纯JDBC而非Hibernate/Spring Data。理由：
> 1. SQLite仅3张表，ORM的映射成本高于收益
> 2. 框架层零Spring依赖的设计原则
> 3. WAL模式下直接控制连接和事务边界更简单

---

## 4. SQLite层 — `lyclaw-framework` 新增

### 4.1 包结构

```
lyclaw-framework/src/main/java/lyjew/com/lyclaw/persistence/
├── sqlite/
│   ├── SqliteConfig.java              ← 配置持有类
│   ├── SqliteConnectionManager.java   ← 连接生命周期管理
│   └── SqliteMigrationService.java    ← DDL迁移
├── jsonl/
│   ├── JsonlWriter.java               ← 接口
│   ├── JsonlReader.java               ← 接口
│   ├── DefaultJsonlWriter.java        ← 实现
│   └── DefaultJsonlReader.java        ← 实现
├── repository/
│   ├── AgentRepository.java           ← Agent CRUD
│   ├── SessionRepository.java         ← Session CRUD (JSONL+SQLite)
│   └── ApprovalRepository.java        ← 审批CRUD
├── queue/
│   ├── AsyncWriteQueue.java           ← per-session 队列+消费者
│   └── AsyncWriteQueueRegistry.java   ← 队列注册表
└── session/
    └── SessionPersistence.java        ← 已存在，保留

lyclaw-framework/src/main/java/lyjew/com/lyclaw/react/
├── ReActMessageHook.java             ← ReAct循环消息钩子接口（新增）
└── SessionFactory.java               ← 子Agent会话创建接口（新增）


### 4.2 SqliteConfig

```java
package lyjew.com.lyclaw.persistence.sqlite;

import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class SqliteConfig {
    /** SQLite数据库文件完整路径，如 /home/lyjew/.../LyClaw/index/lyclaw.db */
    String dbPath;
    /** WAL模式，默认true */
    @Builder.Default
    boolean walMode = true;
    /** 连接池大小（SQLite单写，此值实际控制同时读的连接数） */
    @Builder.Default
    int poolSize = 5;
    /** 连接空闲超时（毫秒） */
    @Builder.Default
    long idleTimeoutMs = 300_000;
}
```

### 4.3 SqliteConnectionManager

```java
package lyjew.com.lyclaw.persistence.sqlite;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.sqlite.SQLiteConfig;
import org.sqlite.SQLiteDataSource;

/**
 * SQLite连接管理器。
 *
 * 使用SQLiteDataSource连接池（SQLite JDBC内置），WAL模式启用。
 * 单例生命周期由Spring管理（@Bean在lyclaw-web层创建）。
 */
public class SqliteConnectionManager implements AutoCloseable {

    private final SQLiteDataSource dataSource;
    private final SqliteConfig config;

    public SqliteConnectionManager(SqliteConfig config) {
        this.config = config;
        // 确保父目录存在
        java.io.File dbFile = new java.io.File(config.getDbPath());
        dbFile.getParentFile().mkdirs();

        // 使用org.sqlite.SQLiteConfig设置WAL等参数
        SQLiteConfig sqLiteConfig = new SQLiteConfig();
        if (config.isWalMode()) {
            sqLiteConfig.setJournalMode(SQLiteConfig.JournalMode.WAL);
        }
        sqLiteConfig.setBusyTimeout(5000);  // 5秒忙等待

        this.dataSource = new SQLiteDataSource(sqLiteConfig);
        this.dataSource.setUrl("jdbc:sqlite:" + config.getDbPath());
    }

    public Connection getConnection() throws SQLException {
        return dataSource.getConnection();
    }

    public String getDbPath() {
        return config.getDbPath();
    }

    @Override
    public void close() {
        // SQLiteDataSource没有显式close，连接池由GC处理
    }
}
```

### 4.4 SqliteMigrationService

```java
package lyjew.com.lyclaw.persistence.sqlite;

import java.sql.Connection;
import java.sql.Statement;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 数据库迁移服务——启动时执行DDL，幂等（IF NOT EXISTS）。
 */
public class SqliteMigrationService {

    private static final Logger log = LoggerFactory.getLogger(SqliteMigrationService.class);
    private final SqliteConnectionManager connectionManager;

    public SqliteMigrationService(SqliteConnectionManager connectionManager) {
        this.connectionManager = connectionManager;
    }

    public void migrate() {
        try (Connection conn = connectionManager.getConnection();
             Statement stmt = conn.createStatement()) {

            // 启用WAL模式
            stmt.execute("PRAGMA journal_mode=WAL");
            stmt.execute("PRAGMA foreign_keys=ON");

            // ===== agents 表 =====
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS agents (
                    agent_id           TEXT PRIMARY KEY,
                    agent_name         TEXT NOT NULL,
                    description        TEXT DEFAULT '',
                    lifecycle          TEXT NOT NULL DEFAULT 'permanent',
                    created_by         TEXT NOT NULL DEFAULT 'user',
                    parent_agent_id    TEXT,
                    parent_session_id  TEXT,
                    model              TEXT NOT NULL,
                    provider           TEXT NOT NULL,
                    thinking_level     TEXT DEFAULT 'medium',
                    verbose_level      TEXT DEFAULT 'low',
                    reasoning_level    TEXT DEFAULT 'medium',
                    fast_mode          INTEGER DEFAULT 0,
                    sandbox_level      TEXT DEFAULT 'PROCESS',
                    skills             TEXT DEFAULT '[]',
                    allow_agents       TEXT DEFAULT '[\"*\"]',
                    max_spawn_depth    INTEGER DEFAULT 1,
                    max_children       INTEGER DEFAULT 5,
                    system_prompt      TEXT DEFAULT '',
                    soul_prompt        TEXT DEFAULT '',
                    identity_display_name TEXT DEFAULT '',
                    avatar_url         TEXT DEFAULT '',
                    avatar_file_path   TEXT DEFAULT '',
                    created_at         INTEGER NOT NULL,
                    directory_path     TEXT NOT NULL,
                    FOREIGN KEY (parent_agent_id) REFERENCES agents(agent_id)
                )
                """);

            // ===== sessions 表 =====
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS sessions (
                    session_id          TEXT PRIMARY KEY,
                    agent_id            TEXT NOT NULL,
                    parent_session_id   TEXT,
                    parent_agent_id     TEXT,
                    created_at          INTEGER NOT NULL,
                    updated_at          INTEGER NOT NULL,
                    first_msg_preview   TEXT DEFAULT '',
                    message_count       INTEGER DEFAULT 0,
                    tool_call_count     INTEGER DEFAULT 0,
                    total_tokens        INTEGER DEFAULT 0,
                    compaction_count    INTEGER DEFAULT 0,
                    file_path           TEXT NOT NULL,
                    FOREIGN KEY (agent_id) REFERENCES agents(agent_id) ON DELETE CASCADE
                )
                """);

            // ===== approvals 表 =====
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS approvals (
                    approval_id    TEXT PRIMARY KEY,
                    session_id     TEXT NOT NULL,
                    agent_id       TEXT NOT NULL,
                    tool_name      TEXT NOT NULL,
                    tool_call_id   TEXT NOT NULL,
                    arguments      TEXT DEFAULT '',
                    status         TEXT DEFAULT 'pending',
                    requested_at   INTEGER NOT NULL,
                    resolved_at    INTEGER,
                    expires_at     INTEGER NOT NULL,
                    resolved_by    TEXT DEFAULT '',
                    FOREIGN KEY (session_id) REFERENCES sessions(session_id) ON DELETE CASCADE
                )
                """);

            // ===== 索引 =====
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_agents_lifecycle ON agents(lifecycle)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_agents_parent    ON agents(parent_agent_id)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_agents_created   ON agents(created_at DESC)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_sessions_agent   ON sessions(agent_id)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_sessions_updated ON sessions(updated_at DESC)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_sessions_parent  ON sessions(parent_session_id)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_approvals_session ON approvals(session_id)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_approvals_status  ON approvals(status)");

            log.info("SQLite 迁移完成，数据库: {}", connectionManager.getDbPath());

        } catch (Exception e) {
            throw new RuntimeException("SQLite 迁移失败: " + e.getMessage(), e);
        }
    }
}
```

---

## 5. JSONL引擎 — `lyclaw-framework` 新增

### 5.1 JsonlWriter接口

```java
package lyjew.com.lyclaw.persistence.jsonl;

import java.util.Map;

/**
 * JSONL行写入器——每行一个JSON对象，行间\n分隔。
 * 实现类负责线程安全和文件追加。
 */
public interface JsonlWriter {
    /**
     * 追加一行JSON到文件末尾。
     * @param filePath 文件绝对路径
     * @param fields 要序列化为JSON的字段Map
     */
    void appendLine(String filePath, Map<String, Object> fields);

    /** 强制flush到磁盘 */
    void flush(String filePath);
}
```

### 5.2 DefaultJsonlWriter

```java
package lyjew.com.lyclaw.persistence.jsonl;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Map;

/**
 * JSONL写入默认实现——使用Jackson序列化，BufferedWriter逐行追加。
 */
public class DefaultJsonlWriter implements JsonlWriter {

    private final ObjectMapper objectMapper;

    public DefaultJsonlWriter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void appendLine(String filePath, Map<String, Object> fields) {
        try {
            Path path = Paths.get(filePath);
            Files.createDirectories(path.getParent());
            String line = objectMapper.writeValueAsString(fields) + "\n";
            Files.writeString(path, line, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new UncheckedIOException("JSONL写入失败: " + filePath, e);
        }
    }

    @Override
    public void flush(String filePath) {
        // Files.writeString 已刷新到磁盘
    }
}
```

### 5.3 JsonlReader接口

```java
package lyjew.com.lyclaw.persistence.jsonl;

import java.util.List;
import java.util.Map;

public interface JsonlReader {
    /** 读取全部行 */
    List<Map<String, Object>> readAll(String filePath);
    /** 分页读取：offset=-1表示最新limit条，offset>=0表示从指定行开始 */
    List<Map<String, Object>> readRange(String filePath, int offset, int limit);
    /** 读取首行（session_created） */
    Map<String, Object> readFirstLine(String filePath);
    /** 统计总行数 */
    int countLines(String filePath);
}
```

### 5.4 DefaultJsonlReader

```java
package lyjew.com.lyclaw.persistence.jsonl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.*;
import java.nio.file.*;
import java.util.*;

public class DefaultJsonlReader implements JsonlReader {

    private final ObjectMapper objectMapper;
    private static final TypeReference<Map<String, Object>> MAP_TYPE =
            new TypeReference<>() {};

    public DefaultJsonlReader(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public List<Map<String, Object>> readAll(String filePath) {
        List<Map<String, Object>> lines = new ArrayList<>();
        Path path = Paths.get(filePath);
        if (!Files.exists(path)) return lines;
        try {
            for (String line : Files.readAllLines(path)) {
                if (!line.isBlank()) {
                    lines.add(objectMapper.readValue(line, MAP_TYPE));
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("JSONL读取失败: " + filePath, e);
        }
        return lines;
    }

    @Override
    public List<Map<String, Object>> readRange(String filePath, int offset, int limit) {
        Path path = Paths.get(filePath);
        if (!Files.exists(path)) return List.of();
        List<Map<String, Object>> result = new ArrayList<>();
        try (BufferedReader reader = Files.newBufferedReader(path)) {
            int total = countLines(filePath);
            int start, end;
            if (offset == -1) {
                start = Math.max(0, total - limit);
                end = total;
            } else {
                start = Math.max(0, offset);
                end = Math.min(total, start + limit);
            }
            String line;
            int lineNum = 0;
            while ((line = reader.readLine()) != null) {
                if (lineNum >= start && lineNum < end && !line.isBlank()) {
                    result.add(objectMapper.readValue(line, MAP_TYPE));
                }
                lineNum++;
                if (lineNum >= end) break;  // 提前退出，不读取文件剩余部分
            }
        } catch (IOException e) {
            throw new UncheckedIOException("JSONL分段读取失败: " + filePath, e);
        }
        return result;
    }

    @Override
    public Map<String, Object> readFirstLine(String filePath) {
        Path path = Paths.get(filePath);
        if (!Files.exists(path)) return null;
        try (BufferedReader reader = Files.newBufferedReader(path)) {
            String line = reader.readLine();
            return line != null ? objectMapper.readValue(line, MAP_TYPE) : null;
        } catch (IOException e) {
            throw new UncheckedIOException("JSONL首行读取失败: " + filePath, e);
        }
    }

    @Override
    public int countLines(String filePath) {
        Path path = Paths.get(filePath);
        if (!Files.exists(path)) return 0;
        try {
            return (int) Files.lines(path).count();
        } catch (IOException e) {
            return 0;
        }
    }
}
```

---

## 6. Repository层 — `lyclaw-framework` 新增

### 6.1 AgentRepository

```java
package lyjew.com.lyclaw.persistence.repository;

import java.sql.*;
import java.util.*;
import lyjew.com.lyclaw.persistence.sqlite.SqliteConnectionManager;

/**
 * Agent持久化操作——纯JDBC，无ORM。
 */
public class AgentRepository {

    private final SqliteConnectionManager cm;

    public AgentRepository(SqliteConnectionManager cm) { this.cm = cm; }

    /** 插入新Agent */
    public void insert(Map<String, Object> agent) {
        String sql = """
            INSERT INTO agents (agent_id, agent_name, description, lifecycle, created_by,
                parent_agent_id, parent_session_id, model, provider, thinking_level,
                verbose_level, reasoning_level, fast_mode, sandbox_level, skills,
                allow_agents, max_spawn_depth, max_children, system_prompt, soul_prompt,
                identity_display_name, avatar_url, avatar_file_path, created_at, directory_path)
            VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
            """;
        try (Connection c = cm.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, (String) agent.get("agent_id"));
            ps.setString(2, (String) agent.get("agent_name"));
            ps.setString(3, (String) agent.getOrDefault("description", ""));
            ps.setString(4, (String) agent.getOrDefault("lifecycle", "permanent"));
            ps.setString(5, (String) agent.getOrDefault("created_by", "user"));
            ps.setString(6, (String) agent.get("parent_agent_id"));
            ps.setString(7, (String) agent.get("parent_session_id"));
            ps.setString(8, (String) agent.get("model"));
            ps.setString(9, (String) agent.get("provider"));
            ps.setString(10, (String) agent.getOrDefault("thinking_level", "medium"));
            ps.setString(11, (String) agent.getOrDefault("verbose_level", "low"));
            ps.setString(12, (String) agent.getOrDefault("reasoning_level", "medium"));
            ps.setInt(13, (Integer) agent.getOrDefault("fast_mode", 0));
            ps.setString(14, (String) agent.getOrDefault("sandbox_level", "PROCESS"));
            ps.setString(15, (String) agent.getOrDefault("skills", "[]"));
            ps.setString(16, (String) agent.getOrDefault("allow_agents", "[\"*\"]"));
            ps.setInt(17, (Integer) agent.getOrDefault("max_spawn_depth", 1));
            ps.setInt(18, (Integer) agent.getOrDefault("max_children", 5));
            ps.setString(19, (String) agent.getOrDefault("system_prompt", ""));
            ps.setString(20, (String) agent.getOrDefault("soul_prompt", ""));
            ps.setString(21, (String) agent.getOrDefault("identity_display_name", ""));
            ps.setString(22, (String) agent.getOrDefault("avatar_url", ""));
            ps.setString(23, (String) agent.getOrDefault("avatar_file_path", ""));
            ps.setLong(24, (Long) agent.get("created_at"));
            ps.setString(25, (String) agent.get("directory_path"));
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("插入Agent失败: " + agent.get("agent_id"), e);
        }
    }

    /** 按agentId查询 */
    public Map<String, Object> findById(String agentId) {
        String sql = "SELECT * FROM agents WHERE agent_id = ?";
        try (Connection c = cm.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, agentId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rowToMap(rs) : null;
            }
        } catch (SQLException e) {
            throw new RuntimeException("查询Agent失败: " + agentId, e);
        }
    }

    /** 查询所有Agent（列表页用，不含大字段） */
    public List<Map<String, Object>> findAllSummary() {
        String sql = """
            SELECT agent_id, agent_name, description, lifecycle, model, skills,
                   avatar_url, created_at
            FROM agents ORDER BY created_at DESC
            """;
        List<Map<String, Object>> result = new ArrayList<>();
        try (Connection c = cm.getConnection(); Statement stmt = c.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) result.add(rowToMap(rs));
        } catch (SQLException e) {
            throw new RuntimeException("查询Agent列表失败", e);
        }
        return result;
    }

    /** 更新Agent */
    public void update(String agentId, Map<String, Object> updates) { /* 类似insert，动态SQL */ }

    /** 删除Agent（级联由SQLite外键ON DELETE CASCADE处理sessions表） */
    public void delete(String agentId) {
        String sql = "DELETE FROM agents WHERE agent_id = ?";
        try (Connection c = cm.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, agentId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("删除Agent失败: " + agentId, e);
        }
    }

    /** 查询某Agent的活跃子Agent数量 */
    public int countChildren(String parentAgentId) {
        String sql = "SELECT COUNT(*) FROM agents WHERE parent_agent_id = ? AND lifecycle = 'temporary'";
        try (Connection c = cm.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, parentAgentId);
            try (ResultSet rs = ps.executeQuery()) { return rs.next() ? rs.getInt(1) : 0; }
        } catch (SQLException e) {
            throw new RuntimeException("统计子Agent失败", e);
        }
    }

    private Map<String, Object> rowToMap(ResultSet rs) throws SQLException {
        Map<String, Object> map = new LinkedHashMap<>();
        var meta = rs.getMetaData();
        for (int i = 1; i <= meta.getColumnCount(); i++) {
            map.put(meta.getColumnName(i), rs.getObject(i));
        }
        return map;
    }
}
```

### 6.2 SessionRepository

```java
package lyjew.com.lyclaw.persistence.repository;

import java.sql.*;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.*;
import lyjew.com.lyclaw.model.Session;
import lyjew.com.lyclaw.persistence.jsonl.JsonlWriter;
import lyjew.com.lyclaw.persistence.jsonl.JsonlReader;
import lyjew.com.lyclaw.persistence.sqlite.SqliteConnectionManager;

/**
 * Session持久化——协调JSONL（消息数据）和SQLite（元数据摘要）。
 *
 * JSONL是消息的真实来源，SQLite存储元数据便于快速列表/筛选。
 * 写入时先append JSONL，再UPDATE SQLite。
 */
public class SessionRepository {

    private final SqliteConnectionManager cm;
    private final JsonlWriter jsonlWriter;
    private final JsonlReader jsonlReader;
    private final StorageProperties storageProperties;

    public SessionRepository(SqliteConnectionManager cm, JsonlWriter jsonlWriter,
                             JsonlReader jsonlReader, StorageProperties storageProperties) {
        this.cm = cm; this.jsonlWriter = jsonlWriter; this.jsonlReader = jsonlReader;
        this.storageProperties = storageProperties;
    }

    /** 创建新会话——INSERT SQLite + 创建JSONL + 写入session_created行 */
    public void create(Session session) {
        long now = System.currentTimeMillis();
        String filePath = session.getFilePath();

        // 1. 写入JSONL首行（session_created事件）
        Map<String, Object> firstLine = new LinkedHashMap<>();
        firstLine.put("type", "session_created");
        firstLine.put("sessionId", session.getSessionId());
        firstLine.put("agentId", session.getAgentId());
        firstLine.put("parentSessionId", session.getParentSessionId());
        firstLine.put("parentAgentId", session.getParentAgentId());
        firstLine.put("timestamp", now);
        jsonlWriter.appendLine(filePath, firstLine);

        // 2. 插入SQLite
        String sql = """
            INSERT INTO sessions (session_id, agent_id, parent_session_id, parent_agent_id,
                created_at, updated_at, message_count, tool_call_count, total_tokens,
                compaction_count, file_path, first_msg_preview)
            VALUES (?,?,?,?,?,?,0,0,0,0,?,'')
            """;
        try (Connection c = cm.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, session.getSessionId());
            ps.setString(2, session.getAgentId());
            ps.setString(3, session.getParentSessionId());
            ps.setString(4, session.getParentAgentId());
            ps.setLong(5, now);
            ps.setLong(6, now);
            ps.setString(7, filePath);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("创建Session SQLite记录失败", e);
        }
    }

    /** 追加消息行到JSONL + 更新SQLite摘要 */
    public void appendMessage(Session session, Map<String, Object> messageFields) {
        String filePath = session.getFilePath();
        jsonlWriter.appendLine(filePath, messageFields);

        // 更新SQLite摘要
        String sql = """
            UPDATE sessions SET updated_at = ?, message_count = ?,
                tool_call_count = tool_call_count + ?,
                total_tokens = total_tokens + ?
            WHERE session_id = ?
            """;
        try (Connection c = cm.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            long now = System.currentTimeMillis();
            ps.setLong(1, now);
            ps.setInt(2, session.getMessageIndex());
            int toolCount = "tool_result".equals(messageFields.get("type")) ? 1 : 0;
            ps.setInt(3, toolCount);
            int tokens = extractTokens(messageFields);
            ps.setInt(4, tokens);
            ps.setString(5, session.getSessionId());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("更新Session摘要失败", e);
        }
    }

    /** 追加compaction事件行 */
    public void appendCompaction(String filePath, int messagesCompacted, int summaryTokens, double qualityScore) {
        Map<String, Object> line = new LinkedHashMap<>();
        line.put("type", "compaction");
        line.put("messagesCompacted", messagesCompacted);
        line.put("summaryTokens", summaryTokens);
        line.put("qualityScore", qualityScore);
        line.put("timestamp", System.currentTimeMillis());
        jsonlWriter.appendLine(filePath, line);
    }

    /** 按sessionId查询会话元数据 */
    public List<Map<String, Object>> findBySessionId(String sessionId) {
        String sql = "SELECT session_id, agent_id, parent_session_id, parent_agent_id, " +
                "created_at, updated_at, first_msg_preview, message_count, " +
                "tool_call_count, total_tokens, compaction_count, file_path " +
                "FROM sessions WHERE session_id = ?";
        List<Map<String, Object>> result = new ArrayList<>();
        try (Connection c = cm.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, sessionId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) result.add(rowToMap(rs));
            }
        } catch (SQLException e) {
            throw new RuntimeException("查询会话失败: " + sessionId, e);
        }
        return result;
    }

    /** 读取JSONL首行（session_created） */
    public Map<String, Object> readFirstLine(String filePath) {
        return jsonlReader.readFirstLine(filePath);
    }

    /** 按agentId查询会话列表 */
    public List<Map<String, Object>> findByAgentId(String agentId) {
        String sql = "SELECT session_id, agent_id, parent_session_id, parent_agent_id, " +
                "created_at, updated_at, first_msg_preview, message_count, " +
                "tool_call_count, total_tokens, compaction_count, file_path " +
                "FROM sessions WHERE agent_id = ? ORDER BY updated_at DESC";
        List<Map<String, Object>> result = new ArrayList<>();
        try (Connection c = cm.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, agentId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) result.add(rowToMap(rs));
            }
        } catch (SQLException e) {
            throw new RuntimeException("查询会话列表失败", e);
        }
        return result;
    }

    /** 查询某会话的子会话列表 */
    public List<Map<String, Object>> findByParentSessionId(String parentSessionId) {
        String sql = "SELECT session_id, agent_id, created_at, updated_at, message_count, " +
                "tool_call_count, file_path " +
                "FROM sessions WHERE parent_session_id = ? ORDER BY created_at";
        List<Map<String, Object>> result = new ArrayList<>();
        try (Connection c = cm.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, parentSessionId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) result.add(rowToMap(rs));
            }
        } catch (SQLException e) {
            throw new RuntimeException("查询子会话列表失败", e);
        }
        return result;
    }

    /** 更新首条消息预览 */
    public void updateFirstMsgPreview(String sessionId, String preview) {
        String sql = "UPDATE sessions SET first_msg_preview = ? WHERE session_id = ?";
        try (Connection c = cm.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            int maxLen = storageProperties.getSession().getPreviewMaxLength();
        ps.setString(1, preview != null && preview.length() > maxLen
                    ? preview.substring(0, maxLen) : preview);
            ps.setString(2, sessionId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("更新首条消息预览失败", e);
        }
    }


        } catch (SQLException e) {
            throw new RuntimeException("查询会话列表失败", e);
        }
        return result;
    }

    /** 分页读取JSONL消息 */
    public List<Map<String, Object>> readMessages(String filePath, int offset, int limit) {
        return jsonlReader.readRange(filePath, offset, limit);
    }

    /** 删除会话（删除JSONL文件 + SQLite行） */
    public void delete(String sessionId, String filePath) {
        try { java.nio.file.Files.deleteIfExists(java.nio.file.Paths.get(filePath)); }
        catch (java.io.IOException e) { /* log */ }
        String sql = "DELETE FROM sessions WHERE session_id = ?";
        try (Connection c = cm.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, sessionId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("删除Session失败", e);
        }
    }

    private int extractTokens(Map<String, Object> fields) {
        @SuppressWarnings("unchecked")
        Map<String, Object> usage = (Map<String, Object>) fields.get("usage");
        if (usage != null && usage.get("totalTokens") instanceof Number n) {
            return n.intValue();
        }
        return 0;
    }

    private Map<String, Object> rowToMap(ResultSet rs) throws SQLException {
        Map<String, Object> map = new LinkedHashMap<>();
        var meta = rs.getMetaData();
        for (int i = 1; i <= meta.getColumnCount(); i++) {
            map.put(meta.getColumnName(i), rs.getObject(i));
        }
        return map;
    }
}
```

### 6.3 ApprovalRepository

```java
package lyjew.com.lyclaw.persistence.repository;

import java.sql.*;
import java.util.*;
import lyjew.com.lyclaw.persistence.sqlite.SqliteConnectionManager;

/**
 * 审批持久化——工具调用前的用户确认记录。
 */
public class ApprovalRepository {

    private final SqliteConnectionManager cm;

    public ApprovalRepository(SqliteConnectionManager cm) { this.cm = cm; }

    public void insert(String approvalId, String sessionId, String agentId,
                       String toolName, String toolCallId, String arguments,
                       long expiresAt) {
        String sql = """
            INSERT INTO approvals (approval_id, session_id, agent_id, tool_name,
                tool_call_id, arguments, status, requested_at, expires_at)
            VALUES (?,?,?,?,?,?,'pending',?,?)
            """;
        try (Connection c = cm.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            long now = System.currentTimeMillis();
            ps.setString(1, approvalId);
            ps.setString(2, sessionId);
            ps.setString(3, agentId);
            ps.setString(4, toolName);
            ps.setString(5, toolCallId);
            ps.setString(6, arguments);
            ps.setLong(7, now);
            ps.setLong(8, expiresAt);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("插入审批失败", e);
        }
    }

    public void resolve(String approvalId, String status, String resolvedBy) {
        String sql = "UPDATE approvals SET status=?, resolved_at=?, resolved_by=? WHERE approval_id=?";
        try (Connection c = cm.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, status);
            ps.setLong(2, System.currentTimeMillis());
            ps.setString(3, resolvedBy);
            ps.setString(4, approvalId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("更新审批状态失败", e);
        }
    }

    public List<Map<String, Object>> findPendingBySession(String sessionId) {
        String sql = "SELECT * FROM approvals WHERE session_id=? AND status='pending' ORDER BY requested_at";
        List<Map<String, Object>> result = new ArrayList<>();
        try (Connection c = cm.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, sessionId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    var meta = rs.getMetaData();
                    for (int i = 1; i <= meta.getColumnCount(); i++)
                        row.put(meta.getColumnName(i), rs.getObject(i));
                    result.add(row);
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("查询待审批列表失败", e);
        }
        return result;
    }
}
```

---

## 7. 异步写入队列 — `lyclaw-framework` 新增

### 7.1 AsyncWriteQueue

```java
package lyjew.com.lyclaw.persistence.queue;

import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import lyjew.com.lyclaw.model.Session;
import lyjew.com.lyclaw.persistence.repository.SessionRepository;

/**
 * Per-Session异步写入队列。
 *
 * 每个活跃Session一个AsyncWriteQueue实例。单消费者线程FIFO消费BlockingQueue，
 * 保证同一会话内消息写入的严格顺序。失败重试最多3次，连续失败降级。
 */
public class AsyncWriteQueue implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(AsyncWriteQueue.class);

    private final String sessionId;
    private final SessionRepository sessionRepository;
    private final BlockingQueue<WriteTask> queue;
    private final Thread consumerThread;
    private final AtomicInteger consecutiveFailures = new AtomicInteger(0);
    private final int maxRetries;
    private volatile boolean running = true;

    /** 重试缓冲区：上次写入失败的消息，下次写入时优先flush */
    private WriteTask retryBuffer;

    public AsyncWriteQueue(String sessionId, SessionRepository sessionRepository, int capacity) {
        this.sessionId = sessionId;
        this.sessionRepository = sessionRepository;
        this.maxRetries = 3;  // 可通过StorageProperties覆盖
        this.queue = new LinkedBlockingQueue<>(capacity);  // 有界队列防止OOM
        this.consumerThread = new Thread(this::consumeLoop, "jsonl-writer-" + sessionId);
        this.consumerThread.setDaemon(true);
        this.consumerThread.start();
    }

    /** 投递消息行到队列，立即返回（不阻塞ReAct循环） */
    public void enqueue(Session session, Map<String, Object> messageFields) {
        if (!running) return;
        // 先flush重试缓冲
        WriteTask retry = retryBuffer;
        if (retry != null) {
            retryBuffer = null;
            queue.add(retry);
        }
        queue.add(new WriteTask(session, messageFields, false));
    }

    /** 投递compaction事件行 */
    public void enqueueCompaction(String filePath, int messagesCompacted,
                                   int summaryTokens, double qualityScore) {
        if (!running) return;
        // 这里简化：直接构造一个特殊的WriteTask，在consume时处理
        queue.add(new WriteTask(null,
                Map.of("type", "compaction", "messagesCompacted", messagesCompacted,
                       "summaryTokens", summaryTokens, "qualityScore", qualityScore,
                       "timestamp", System.currentTimeMillis()),
                true));
    }

    private void consumeLoop() {
        while (running || !queue.isEmpty()) {
            try {
                WriteTask task = queue.poll(1, TimeUnit.SECONDS);
                if (task == null) continue;
                try {
                    if (task.isCompaction) {
                        // 从session获取filePath，追加compaction事件
                        // (简化处理，实际需从SessionManager获取)
                    } else {
                        sessionRepository.appendMessage(task.session, task.fields);
                    }
                    consecutiveFailures.set(0);
                } catch (Exception e) {
                    int failures = consecutiveFailures.incrementAndGet();
                    log.error("JSONL写入失败 (session={}, 连续失败={}/3): {}",
                            sessionId, failures, e.getMessage());
                    if (failures < maxRetries) {
                        retryBuffer = task;  // 下次重试
                    } else {
                        // SSE通知前端持久化异常（通过事件总线）
                        log.error("Session {} 持久化降级，连续失败超过3次", sessionId);
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    @Override
    public void close() {
        running = false;
        consumerThread.interrupt();
    }

    /** 内部任务封装 */
    private record WriteTask(Session session, Map<String, Object> fields, boolean isCompaction) {}
}
```

### 7.2 AsyncWriteQueueRegistry

```java
package lyjew.com.lyclaw.persistence.queue;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 异步写入队列注册表——管理所有活跃Session的AsyncWriteQueue实例。
 */
public class AsyncWriteQueueRegistry {

    private final ConcurrentMap<String, AsyncWriteQueue> queues = new ConcurrentHashMap<>();

    public AsyncWriteQueue getOrCreate(String sessionId,
                                        java.util.function.Supplier<AsyncWriteQueue> factory) {
        return queues.computeIfAbsent(sessionId, k -> factory.get());
    }

    public void remove(String sessionId) {
        AsyncWriteQueue queue = queues.remove(sessionId);
        if (queue != null) queue.close();
    }

    public void shutdown() {
        queues.values().forEach(AsyncWriteQueue::close);
        queues.clear();
    }
}
```

---

## 8. SessionManager — `lyclaw-web` 新增

```java
package lyjew.com.lyclaw.web.session;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import lyjew.com.lyclaw.config.StorageProperties;
import lyjew.com.lyclaw.model.Message;
import lyjew.com.lyclaw.model.Session;
import lyjew.com.lyclaw.persistence.SessionFactory;
import lyjew.com.lyclaw.persistence.queue.AsyncWriteQueue;
import lyjew.com.lyclaw.persistence.queue.AsyncWriteQueueRegistry;
import lyjew.com.lyclaw.persistence.repository.SessionRepository;


/**
 * 会话生命周期管理器——是ChatController、SubagentSpawner、ContextPruningScheduler
 * 等所有需要访问会话的组件的唯一入口。
 *
 * 维护活跃会话的ConcurrentHashMap缓存，启动时从SQLite加载最近会话的元数据。
 */
@Component
public class SessionManager implements SessionFactory {

    private static final Logger log = LoggerFactory.getLogger(SessionManager.class);

    private final SessionRepository sessionRepository;
    private final AsyncWriteQueueRegistry queueRegistry;
    private final StorageProperties storageProperties;
    private final ConcurrentHashMap<String, Session> activeSessions = new ConcurrentHashMap<>();

    public SessionManager(SessionRepository sessionRepository,
                          AsyncWriteQueueRegistry queueRegistry,
                          StorageProperties storageProperties) {
        this.sessionRepository = sessionRepository;
        this.queueRegistry = queueRegistry;
        this.storageProperties = storageProperties;
    }

    /** 创建新会话——生成sessionId，初始化JSONL+SQLite，注册到缓存 */
    public Session createSession(String agentId, String model) {
        int idLen = storageProperties.getSession().getIdLength();
        String sessionId = UUID.randomUUID().toString().substring(0, idLen);
        String filePath = buildFilePath(agentId, sessionId);

        Session session = Session.builder()
                .sessionId(sessionId)
                .agentId(agentId)
                .model(model)
                .filePath(filePath)
                .messageIndex(0)
                .compactionCount(0)
                .heartbeatMode(false)
                .messages(new ArrayList<>())
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();

        // 持久化到SQLite + JSONL
        sessionRepository.create(session);

        // 注册异步队列（有界容量，防止OOM）
        // 注意：先 getOrCreate 再决定是否 new，避免 computeIfAbsent 丢弃实例导致孤儿线程
        int queueCap = storageProperties.getSession().getWriteQueueCapacity();
        AsyncWriteQueue queue = queueRegistry.getOrCreate(sessionId,
                () -> new AsyncWriteQueue(sessionId, sessionRepository, queueCap));

        // 注册到活跃缓存
        activeSessions.put(sessionId, session);
        log.debug("创建会话: sessionId={}, agentId={}", sessionId, agentId);
        return session;
    }

    /** 创建子Agent会话（SessionFactory接口实现委托到此私有方法） */
    private Session createSubagentSessionInternal(String parentSessionId, String parentAgentId,
                                          String childAgentId, String model) {
        int idLen = storageProperties.getSession().getIdLength();
        String sessionId = UUID.randomUUID().toString().substring(0, idLen);
        String filePath = buildFilePath(childAgentId, sessionId);

        Session session = Session.builder()
                .sessionId(sessionId)
                .agentId(childAgentId)
                .model(model)
                .filePath(filePath)
                .parentSessionId(parentSessionId)
                .parentAgentId(parentAgentId)
                .messageIndex(0)
                .compactionCount(0)
                .heartbeatMode(false)
                .messages(new ArrayList<>())
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();

        sessionRepository.create(session);
        int queueCap = storageProperties.getSession().getWriteQueueCapacity();
        queueRegistry.getOrCreate(sessionId,
                () -> new AsyncWriteQueue(sessionId, sessionRepository, queueCap));
        activeSessions.put(sessionId, session);
        return session;
    }

    /**
     * 获取会话——先查缓存，未命中从SQLite+JSONL懒加载重建。
     *
     * 加载步骤：
     * 1. 查SQLite sessions表获取元数据（filePath、agentId等）
     * 2. 读JSONL首行获取session_created信息
     * 3. 分页读最近50条消息到内存
     * 4. 重建Session对象并注册到activeSessions缓存
     */
    public Session getSession(String sessionId) {
        Session cached = activeSessions.get(sessionId);
        if (cached != null) return cached;

        // 1. 从SQLite查元数据
        List<Map<String, Object>> rows = sessionRepository.findBySessionId(sessionId);
        if (rows.isEmpty()) return null;
        Map<String, Object> meta = rows.get(0);

        // 2. 读JSONL首行
        String filePath = (String) meta.get("file_path");
        Map<String, Object> firstLine = sessionRepository.readFirstLine(filePath);

        // 3. 分页读最近N条消息（pageSize由配置决定）
        int totalCount = (Integer) meta.get("message_count");
        int pageSize = storageProperties.getSession().getPageSize();
        List<Map<String, Object>> recentLines = sessionRepository.readMessages(
                filePath, -1, pageSize);

        // 4. 重建Session对象
        Session session = Session.builder()
                .sessionId(sessionId)
                .agentId((String) meta.get("agent_id"))
                .filePath(filePath)
                .messageIndex(totalCount)
                .compactionCount((Integer) meta.getOrDefault("compaction_count", 0))
                .parentSessionId((String) meta.get("parent_session_id"))
                .parentAgentId((String) meta.get("parent_agent_id"))
                .heartbeatMode(false)
                .messages(convertJsonlToMessages(recentLines))
                .createdAt(epochToLocalDateTime((Long) meta.get("created_at")))
                .updatedAt(epochToLocalDateTime((Long) meta.get("updated_at")))
                .build();

        // 注册异步写入队列（续接会话也需要队列）
        int queueCap = storageProperties.getSession().getWriteQueueCapacity();
        queueRegistry.getOrCreate(sessionId,
                () -> new AsyncWriteQueue(sessionId, sessionRepository, queueCap));
        activeSessions.put(sessionId, session);

        log.debug("懒加载会话完成: sessionId={}, agentId={}, messageCount={}",
                sessionId, session.getAgentId(), totalCount);
        return session;
    }

    private List<Message> convertJsonlToMessages(List<Map<String, Object>> lines) {
        List<Message> messages = new ArrayList<>();
        for (Map<String, Object> line : lines) {
            String type = (String) line.get("type");
            if ("message".equals(type) || "tool_result".equals(type)) {
                messages.add(mapToMessage(line));
            }
        }
        return messages;
    }

    private Message mapToMessage(Map<String, Object> fields) {
        return Message.builder()
                .role((String) fields.get("role"))
                .content((String) fields.get("content"))
                .toolCallId((String) fields.get("toolCallId"))
                .toolName((String) fields.get("toolName"))
                .model((String) fields.get("model"))
                .thinking((String) fields.get("thinking"))
                .build();
    }

    private LocalDateTime epochToLocalDateTime(Long epochMillis) {
        return epochMillis != null
                ? LocalDateTime.ofEpochSecond(epochMillis / 1000, 0, java.time.ZoneOffset.UTC)
                : LocalDateTime.now();
    }


    /** 追加消息到会话的异步写入队列 */
    public void appendMessage(Session session, Map<String, Object> messageFields) {
        if (session.isHeartbeatSession()) return;  // 心跳会话不持久化
        int queueCap = storageProperties.getSession().getWriteQueueCapacity();
        AsyncWriteQueue queue = queueRegistry.getOrCreate(session.getSessionId(),
                () -> new AsyncWriteQueue(session.getSessionId(), sessionRepository, queueCap));
        queue.enqueue(session, messageFields);
    }

    // ── ReActMessageHook 语义方法 ─────────────────────────

    /**
     * 持久化钩子——ReAct循环每产生一条消息时回调。
     * 内部：更新Session内存状态 → Message→JSONL字段 → AsyncWriteQueue.enqueue() → 立即返回。
     * 此方法通过 StorageAutoConfiguration 中的方法引用注册为 ReActMessageHook Bean。
     */
    public void onMessage(Session session, Message message) {
        if (session.isHeartbeatSession()) return;

        // 1. 更新Session内存状态
        session.addMessage(message);  // messageIndex++ && updatedAt=now

        // 2. 转换为JSONL字段并投递异步队列
        Map<String, Object> fields = messageToJsonlFields(session, message);
        appendMessage(session, fields);

        // 3. 首条用户消息时设置first_msg_preview
        if ("user".equals(message.getRole()) && session.getMessageIndex() == 1) {
            String preview = message.getContent();
            int maxLen = storageProperties.getSession().getPreviewMaxLength();
            if (preview != null && preview.length() > maxLen) {
                preview = preview.substring(0, maxLen);
            }
            sessionRepository.updateFirstMsgPreview(session.getSessionId(), preview);
        }
    }

    /**
     * 将Message对象转换为JSONL行字段Map。
     * 覆盖设计文档§8.2定义的全部字段。
     */
    private Map<String, Object> messageToJsonlFields(Session session, Message msg) {
        Map<String, Object> fields = new LinkedHashMap<>();
        // 区分tool_result和普通message
        if ("tool".equals(msg.getRole())) {
            fields.put("type", "tool_result");
        } else {
            fields.put("type", "message");
        }
        fields.put("role", msg.getRole());
        fields.put("content", msg.getContent());
        fields.put("timestamp", System.currentTimeMillis());
        fields.put("messageIndex", session.getMessageIndex());

        if (msg.getToolCallId() != null) fields.put("toolCallId", msg.getToolCallId());
        if (msg.getToolName() != null) fields.put("toolName", msg.getToolName());
        if (msg.getModel() != null) fields.put("model", msg.getModel());
        if (msg.getUsage() != null) fields.put("usage", msg.getUsage());
        if (msg.getToolCalls() != null) fields.put("toolCalls", msg.getToolCalls());
        if (msg.getThinking() != null) fields.put("thinking", msg.getThinking());

        return fields;
    }

    // ── SessionFactory 实现 ────────────────────────────────

    @Override
    public Session createSubagentSession(String parentSessionId, String parentAgentId,
                                          String childAgentId, String model) {
        return createSubagentSessionInternal(parentSessionId, parentAgentId, childAgentId, model);
    }

    @Override
    public int getActiveCount(String agentId) {
        return (int) activeSessions.values().stream()
                .filter(s -> agentId.equals(s.getAgentId()))
                .count();
    }


    /** 注册心跳会话——不持久化，仅内存 */
    public Session createHeartbeatSession(String agentId) {
        Session session = Session.builder()
                .sessionId("heartbeat-" + UUID.randomUUID().toString().substring(0,
                        storageProperties.getSession().getIdLength()))
                .agentId(agentId)
                .heartbeatMode(true)
                .messages(new ArrayList<>())
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
        // 心跳会话不注册到activeSessions，不创建队列
        return session;
    }

    /** 删除会话 */
    public void deleteSession(String sessionId) {
        Session session = activeSessions.remove(sessionId);
        if (session != null && !session.isHeartbeatSession()) {
            sessionRepository.delete(sessionId, session.getFilePath());
            queueRegistry.remove(sessionId);
        }
    }

    /** 获取所有活跃会话（供ContextPruningScheduler遍历） */
    public Map<String, Session> getActiveSessions() {
        return Collections.unmodifiableMap(activeSessions);
    }

    private String buildFilePath(String agentId, String sessionId) {
        return storageProperties.getBasePath() + "/agents/"
                + agentId + "/sessions/" + sessionId + ".jsonl";
    }
}
```

---

## 9. ChatController集成 — 修改现有类

修改 `lyclaw-web/.../controller/ChatController.java`：

```java
@RestController
@RequestMapping("/api")
public class ChatController {

    private final ChatAgent chatAgent;
    private final SessionManager sessionManager;  // 新增

    public ChatController(ChatAgent chatAgent, SessionManager sessionManager) {
        this.chatAgent = chatAgent;
        this.sessionManager = sessionManager;
    }

    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> chatStream(@RequestBody ChatRequest request,
                                                     @RequestParam(required = false) String agentId) {
        // 确定agentId
        String resolvedAgentId = agentId != null ? agentId : "chat";

        // 创建或续接会话
        Session session;
        if (request.getSessionId() == null || request.getSessionId().isEmpty()) {
            session = sessionManager.createSession(resolvedAgentId, request.getModel());
        } else {
            session = sessionManager.getSession(request.getSessionId());
            if (session == null) {
                session = sessionManager.createSession(resolvedAgentId, request.getModel());
            }
        }

        String userMessage = request.getLastUserMessage();
        return chatAgent.chatStream(userMessage)
                .doOnNext(event -> {
                    // 每轮ReAct结束后，由框架内部触发消息追加
                    // 这里不直接写——写入由AgentInvocationHandler/ReAct循环触发
                });
    }

    @PostMapping("/agents/{agentId}/sessions")
    public Session createSession(@PathVariable String agentId,
                                  @RequestBody(required = false) ChatRequest request) {
        return sessionManager.createSession(agentId,
                request != null ? request.getModel() : null);
    }

    @GetMapping("/agents/{agentId}/sessions/{sessionId}")
    public Session getSession(@PathVariable String agentId,
                               @PathVariable String sessionId) {
        return sessionManager.getSession(sessionId);
    }

    @DeleteMapping("/agents/{agentId}/sessions/{sessionId}")
    public Map<String, Object> deleteSession(@PathVariable String agentId,
                                              @PathVariable String sessionId) {
        sessionManager.deleteSession(sessionId);
        return Map.of("sessionId", sessionId, "deleted", true);
    }
}
```

---

---

## 9.5. ReAct循环内部持久化集成

> **这是整个存储设计中最关键的集成点。** 以上章节描述了"如何存储"，本章节描述"在ReAct循环的哪个精确位置触发存储"。

### 9.5.1 核心问题

`ToolCallLoop`（ReAct循环主体）位于 `lyclaw-action` 模块。`SessionManager` 位于 `lyclaw-web` 模块。`lyclaw-action` 不能反向依赖 `lyclaw-web`。

**约束**：不能直接修改 `ToolCallLoop` 的循环体代码来硬编码持久化调用——这违反了开闭原则，且让 action 模块感知存储层。

**解决方案**：在 `lyclaw-framework` 中定义 `ReActMessageHook` 回调接口。`ToolCallLoop` 持有一个 `List<ReActMessageHook>`，在每次 `messages.add()` 之后遍历调用。`SessionManager` 实现该接口作为持久化钩子注入。**ToolCallLoop 不感知持久化，只感知"消息已产生"这个事件。**

### 9.5.2 ReActMessageHook 接口 (lyclaw-framework 新增)

```java
// lyclaw-framework/src/main/java/lyjew/com/lyclaw/react/ReActMessageHook.java
package lyjew.com.lyclaw.react;

import lyjew.com.lyclaw.model.Message;
import lyjew.com.lyclaw.model.Session;

/**
 * ReAct循环消息钩子——ToolCallLoop每产生一条新消息时回调。
 *
 * 这是框架层的SPI接口，不依赖任何存储实现。实现类可以是：
 * - 持久化钩子（SessionManager）
 * - 指标采集钩子（MetricsCollector）
 * - 日志审计钩子（AuditLogger）
 * - Phase 3 压缩触发检查钩子（CompactionTrigger）
 *
 * 所有钩子按注册顺序同步调用。每个钩子实现必须快速返回（O(1)内存操作），
 * 耗时操作在钩子内部异步化。
 */
@FunctionalInterface
public interface ReActMessageHook {
    /**
     * 当ReAct循环产生一条新消息时调用。
     * 此时消息已加入 ChatRequest.messages 列表，但尚未持久化。
     *
     * @param session 当前会话（可能为null，如心跳会话或测试场景）
     * @param message 刚产生的消息（role可能是user/assistant/tool）
     */
    void onMessage(Session session, Message message);
}
```

### 9.5.3 ToolCallLoop 改造 — 钩子模式，零侵入

`ToolCallLoop` 只新增一个 `List<ReActMessageHook>` 字段，在循环中**唯一的改动**是在每个 `messages.add()` 之后追加一行钩子调用。不引入任何持久化相关的import。

```java
@Component
public class ToolCallLoop {

    private final ChatFacade chatFacade;
    private final ToolRegistry toolRegistry;
    private final ToolCallPolicy toolCallPolicy;
    private final List<ReActMessageHook> messageHooks;  // ★ 新增：消息钩子列表

    public ToolCallLoop(ChatFacade chatFacade,
                        ToolRegistry toolRegistry,
                        ToolCallPolicy toolCallPolicy,
                        List<ReActMessageHook> messageHooks) {  // ★ 新增参数（Spring自动注入所有实现）
        this.chatFacade = chatFacade;
        this.toolRegistry = toolRegistry;
        this.toolCallPolicy = toolCallPolicy;
        this.messageHooks = messageHooks != null ? messageHooks : List.of();
    }

    public ChatResult execute(ChatContext context) {
        beforeLoop(context);
        List<Message> messages = context.getRequest().getMessages();
        Session session = context.getSession();
        int round = 0;
        int maxRounds = toolCallPolicy.getMaxRounds();

        // ★ 钩子点1: 用户消息（进入循环前，消息已在buildChatRequest时加入messages）
        notifyHooks(session, findLastUserMessage(messages));

        while (round < maxRounds) {
            ModelResponse response = chatFacade.chat(context.getRequest());

            if (!handleModelResponse(response)) {
                Message assistantMsg = Message.builder()
                        .role("assistant").content(response.getContent())
                        .model(response.getModel()).usage(response.getUsage())
                        .thinking(response.getThinking()).build();
                messages.add(assistantMsg);
                notifyHooks(session, assistantMsg);  // ★ 钩子点2: 最终assistant消息
                break;
            }

            List<ToolCall> calls = convertToolCalls(response);
            Message assistantMsg = Message.builder()
                    .role("assistant")
                    .content(response.getContent() != null ? response.getContent() : "")
                    .model(response.getModel()).usage(response.getUsage())
                    .toolCalls(calls).thinking(response.getThinking()).build();
            messages.add(assistantMsg);
            notifyHooks(session, assistantMsg);  // ★ 钩子点3: 含tool_calls的assistant消息

            boolean shouldAbort = false;
            for (ModelResponse.ToolCallRequest req : response.getToolCalls()) {
                try {
                    ToolExecutionResult result = toolRegistry.execute(buildToolCall(req), context);
                    Message toolMsg = Message.builder()
                            .role("tool").toolCallId(req.getId()).toolName(req.getName())
                            .content(result.isSuccess() ? result.getResult() : result.getError())
                            .build();
                    messages.add(toolMsg);
                    notifyHooks(session, toolMsg);  // ★ 钩子点4: 工具执行结果
                } catch (Exception e) {
                    Message errorMsg = Message.builder()
                            .role("tool").toolCallId(req.getId()).toolName(req.getName())
                            .content("Error: " + e.getMessage()).build();
                    messages.add(errorMsg);
                    notifyHooks(session, errorMsg);  // ★ 钩子点4b: 工具异常结果
                    ToolErrorAction action = toolCallPolicy.getErrorAction();
                    if (action == ToolErrorAction.ABORT) { shouldAbort = true; break; }
                }
            }
            if (shouldAbort) break;
            round++;
            if (!toolCallPolicy.shouldContinue(context, round)) break;
        }

        String responseText = extractLastAssistantMessage(context);
        ChatResult result = new ChatResult(responseText, "stop",
                "prompt=0 completion=0 total=0", Collections.emptyList(), 0L);
        afterLoop(context, result);
        return result;
    }

    /** 通知所有钩子（跳过null session或null message） */
    private void notifyHooks(Session session, Message message) {
        if (session == null || message == null || messageHooks.isEmpty()) return;
        for (ReActMessageHook hook : messageHooks) {
            hook.onMessage(session, message);
        }
    }

    private Message findLastUserMessage(List<Message> messages) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            if ("user".equals(messages.get(i).getRole())) {
                return messages.get(i);
            }
        }
        return null;
    }
}
```

**关键设计**：`ToolCallLoop` 的改动仅限：
1. 字段 `List<ReActMessageHook> messageHooks`
2. 私有方法 `notifyHooks()`
3. 每个 `messages.add()` 之后一行 `notifyHooks(session, msg)`

它**不导入任何 persistence 包的类，不感知 JSONL/SQLite/存储**。

### 9.5.4 存储层配置类 — `StorageProperties`

替代原来仅有一个 `pageSize` 字段的 `StorageSessionProperties`，将所有可配置项统一管理。

```java
// lyclaw-framework/src/main/java/lyjew/com/lyclaw/config/StorageProperties.java
package lyjew.com.lyclaw.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 存储层完整配置 — 对应 application.yml 中 lyclaw.storage.*
 * 所有字段均有默认值，开箱即用。
 */
@ConfigurationProperties(prefix = "lyclaw.storage")
@Data
public class StorageProperties {

    /** 文件存储根目录（SQLite数据库 + JSONL文件均在此目录下） */
    private String basePath = "data/storage";  // 生产环境通过 application.yml 覆盖为绝对路径

    /** 会话存储配置 */
    private SessionProperties session = new SessionProperties();

    // ── 内部类：会话存储相关配置 ──

    @Data
    public static class SessionProperties {
        /** 懒加载/分页时每页消息条数 */
        private int pageSize = 50;

        /** 生成的sessionId长度（UUID截取前N位） */
        private int idLength = 8;

        /** 首条用户消息预览最大字符数 */
        private int previewMaxLength = 100;

        /** 异步写入队列容量（有界队列，超出时调用者线程写入） */
        private int writeQueueCapacity = 10000;

        /** JSONL写入失败最大重试次数 */
        private int writeMaxRetries = 3;

        /** 活跃会话缓存最大数量 */
        private int cacheMaxSize = 1000;

        /** 活跃会话缓存TTL（分钟），超时未访问自动淘汰 */
        private int cacheTtlMinutes = 30;
    }
}
```

对应 `application.yml` 配置节：

```yaml
lyclaw:
  storage:
    base-path: /home/lyjew/Documents/Unicom/LyClaw/LyClaw
    session:
      page-size: 50
      id-length: 8
      preview-max-length: 100
      write-queue-capacity: 10000
      write-max-retries: 3
      cache-max-size: 1000
      cache-ttl-minutes: 30
```

> **关于旧的多后端抽象**：原 `application.yml` 中的 `lyclaw.storage.default-backend`、`stores`（session/entity/memory映射）、`backends`（sqlite/postgresql/redis配置示例）全部废弃。新设计固定使用SQLite+JSONL作为会话和实体存储，`InMemoryBackend` 仅保留用于memory层兜底。这些配置在新架构中无对应代码路径，保留只会误导。

SessionManager 使用时通过 `StorageProperties` 注入（替代零散的 `@Value`）：

```java
// SessionManager 构造函数注入：
public SessionManager(SessionRepository sessionRepository,
                      AsyncWriteQueueRegistry queueRegistry,
                      StorageProperties storageProperties) {
    this.sessionRepository = sessionRepository;
    this.queueRegistry = queueRegistry;
    this.storageProperties = storageProperties;
}

// getSession() 分页加载时使用：
StorageProperties.SessionProperties sp = storageProperties.getSession();
List<Map<String, Object>> recentLines = sessionRepository.readMessages(
        filePath, -1, sp.getPageSize());

// createSession() 生成sessionId时使用：
String sessionId = UUID.randomUUID().toString().substring(0, sp.getIdLength());

// onMessage() 截取预览时使用：
if (preview != null && preview.length() > sp.getPreviewMaxLength()) {
    preview = preview.substring(0, sp.getPreviewMaxLength());
}
```

### 9.5.5 SessionManager 实现 ReActMessageHook

```java
@Component
public class SessionManager implements SessionFactory {

    // ... 原有字段 ...

    /**
     * 持久化钩子——ReAct循环每产生一条消息时回调。
     * 内部：Message→JSONL字段→AsyncWriteQueue.enqueue()→立即返回。
     *
     * 此方法作为ReActMessageHook的实现注册到ToolCallLoop中。
     * 同时也会更新Session的messageIndex和updatedAt。
     */
    public void onMessage(Session session, Message message) {
        if (session.isHeartbeatSession()) return;

        // 1. 更新Session内存状态
        session.addMessage(message);  // messageIndex++ && updatedAt=now

        // 2. 转换为JSONL字段并投递异步队列
        Map<String, Object> fields = messageToJsonlFields(session, message);
        appendMessage(session, fields);

        // 3. 首条用户消息时设置first_msg_preview
        if ("user".equals(message.getRole()) && session.getMessageIndex() == 1) {
            String preview = message.getContent();
            int maxLen = storageProperties.getSession().getPreviewMaxLength();
            if (preview != null && preview.length() > maxLen) {
                preview = preview.substring(0, maxLen);
            }
            sessionRepository.updateFirstMsgPreview(session.getSessionId(), preview);
        }
    }

    private Map<String, Object> messageToJsonlFields(Session session, Message msg) {
        Map<String, Object> fields = new LinkedHashMap<>();
        if ("tool".equals(msg.getRole())) {
            fields.put("type", "tool_result");
        } else {
            fields.put("type", "message");
        }
        fields.put("role", msg.getRole());
        fields.put("content", msg.getContent());
        fields.put("timestamp", System.currentTimeMillis());
        fields.put("messageIndex", session.getMessageIndex());
        if (msg.getToolCallId() != null) fields.put("toolCallId", msg.getToolCallId());
        if (msg.getToolName() != null) fields.put("toolName", msg.getToolName());
        if (msg.getModel() != null) fields.put("model", msg.getModel());
        if (msg.getUsage() != null) fields.put("usage", msg.getUsage());
        if (msg.getToolCalls() != null) fields.put("toolCalls", msg.getToolCalls());
        if (msg.getThinking() != null) fields.put("thinking", msg.getThinking());
        return fields;
    }

    // ... 其他方法 ...
}
```

### 9.5.6 Bean注册：将SessionManager的onMessage适配为ReActMessageHook

`SessionManager.onMessage(session, message)` 的方法签名与 `ReActMessageHook.onMessage(session, message)` 完全一致，可以直接使用方法引用：

```java
// StorageAutoConfiguration.java
@Configuration
public class StorageAutoConfiguration {

    // ... 其他Bean ...

    /**
     * 将SessionManager.onMessage注册为ReActMessageHook。
     * Spring自动注入List<ReActMessageHook>到ToolCallLoop。
     *
     * 未来可在此追加更多钩子，如：
     *   - CompactionTriggerHook（Phase 3）
     *   - MetricsCollectorHook
     */
    @Bean
    public ReActMessageHook persistenceHook(SessionManager sessionManager) {
        return sessionManager::onMessage;  // 方法引用，签名匹配
    }
}
```

**扩展性**：只需新增一个 `@Bean ReActMessageHook`，`ToolCallLoop` 无需任何改动即可获得新钩子。

### 9.5.7 完整调用链（端到端）

```
POST /api/chat/stream { message: "帮我查bug" }
  │
  ▼
ChatController.chatStream()
  ├─ SessionManager.createSession("coder", "deepseek-v4-flash")
  │   ├─ INSERT sessions (SQLite)
  │   └─ 写入 session_created 行 (JSONL)
  │
  ├─ 构建 ChatContext(request, session, ...)
  │
  ▼
AgentInvocationHandler.invoke()
  ├─ beforeRequest hooks
  ├─ Pipeline: ContextBuild → SecurityCheck → Plan → Respond(ReAct)
  │   │
  │   ▼
  │ ToolCallLoop.execute(context)
  │   │  List<ReActMessageHook> = [sessionManager::onMessage, ...]
  │   │
  │   ├─ notifyHooks(session, 用户消息)
  │   │   └─ sessionManager.onMessage() → Queue.enqueue() → (后台)JSONL+SQLite
  │   │
  │   ├─ while:
  │   │   ├─ LLM返回 → messages.add(assistantMsg)
  │   │   │   └─ notifyHooks(session, assistantMsg) → Queue → JSONL
  │   │   ├─ 工具执行 → messages.add(toolMsg)
  │   │   │   └─ notifyHooks(session, toolMsg) → Queue → JSONL
  │   │   └─ LLM最终回复 → messages.add(assistantMsg)
  │   │       └─ notifyHooks(session, assistantMsg) → Queue → JSONL
  │   └─ afterLoop()
  │
  ├─ Reflection → Compaction → Metrics
  └─ afterResult hooks
```

### 9.5.8 子Agent的ReAct持久化

子Agent被spawn后，同样经过 `ToolCallLoop.execute()`，钩子机制自动生效：

```
父 Agent 的 ReAct 循环
  ├─ delegate_to_agent("reviewer", "审查代码")
  │     │
  │     ├─ SubagentSpawner.spawn(parentSession, "reviewer", task)
  │     │   ├─ SessionFactory.createSubagentSession(...)
  │     │   │   ├─ Session.sessionId = 新UUID
  │     │   │   ├─ Session.parentSessionId = 父sessionId
  │     │   │   ├─ Session.agentId = "reviewer"
  │     │   │   └─ INSERT sessions + 写入session_created (含parent引用)
  │     │   └─ 返回 childSession
  │     │
  │     ├─ 构建子Agent的ChatContext(request, childSession, ...)
  │     │                                       ↑ 子Session注入Context
  │     │
  │     └─ ToolCallLoop.execute(childContext)
  │           │  context.getSession() → childSession
  │           │  同样的 List<ReActMessageHook> [sessionManager::onMessage, ...]
  │           │
  │           ├─ notifyHooks(childSession, 子用户消息)
  │           │   └─ 追加到 agents/reviewer/sessions/{childId}.jsonl
  │           ├─ while:
  │           │   ├─ notifyHooks(childSession, 子assistant消息)
  │           │   └─ notifyHooks(childSession, 子工具结果)
  │           └─ 返回审查结果
  │
  ├─ 父Agent收到 tool_result（含 subagentSessionId=childId）
  │   └─ notifyHooks(parentSession, toolResult消息)
  │       └─ 追加到 agents/coder/sessions/{parentId}.jsonl
  └─ 继续父Agent的ReAct循环
```

**关键点**：
- 子Agent的 `ChatContext.session` 指向子Session → 钩子自动写入子JSONL
- 父Agent的 `ChatContext.session` 指向父Session → 钩子自动写入父JSONL
- 两者完全并行，互不干扰
- `ToolCallLoop` 代码零差异——只靠传入的 `context.getSession()` 区分

### 9.5.9 异步非阻塞保证

`ReActMessageHook.onMessage()` → `SessionManager.onMessage()` 的调用链：

```
onMessage(session, message)
  → session.addMessage(message)         // 更新messageIndex（纯内存）
  → messageToJsonlFields(message)       // Message→Map（纯内存，无IO）
  → appendMessage(session, fields)       // 查ConcurrentHashMap取队列
    → queue.enqueue(task)               // BlockingQueue.add()，O(1)，立即返回
```

后台消费者线程：
```
jsonl-writer-{sessionId} (daemon thread):
  queue.take() → sessionRepository.appendMessage()
    → Files.writeString(jsonl, line, APPEND)
    → UPDATE sessions SET ... (SQLite WAL)
```

**磁盘写入在独立daemon线程中完成，ReAct循环的SSE响应不受影响。**

### 9.5.10 Phase 3/4 依赖的空实现（桩代码）

以下功能依赖尚未实现的 Phase 3（压缩/修剪）和 Phase 4（心跳）代码。当前阶段提供空实现桩，待对应Phase实现时替换。

#### Phase 3: CompactionStage完成后的compaction事件写入（空实现）

```java
// SessionManager中预留，当前为空操作
public void onCompactionComplete(Session session, int messagesCompacted,
                                  int summaryTokens, double qualityScore) {
    // TODO Phase 3: CompactionStage执行完毕后调用此方法
    // 实现内容：
    //   1. session.setCompactionCount(session.getCompactionCount() + 1)
    //   2. 追加compaction事件行到JSONL:
    //      {"type":"compaction","messagesCompacted":N,"summaryTokens":N,...}
    //   3. UPDATE sessions SET compaction_count = compaction_count + 1
    // 当前空实现——Phase 3实施时填充
}
```

#### Phase 3: ContextPruner的TTL修剪（空实现）

```java
// SessionRepository中预留
public void markMessagesTrimmed(String sessionId, int fromIndex, int toIndex) {
    // TODO Phase 3: ContextPruner执行修剪后调用
    // 追加compaction行标注被修剪的messageIndex范围
    // 当前空实现
}
```

#### Phase 4: 心跳会话持久化跳过（已实现）

心跳会话的存储层防护已在上文 §8、§12 中完整实现：
- `heartbeatMode=true` → `onMessage()` 第一行直接 `return`
- 心跳会话不注册 `AsyncWriteQueue`
- 心跳会话不写入 SQLite

#### Phase 4: HeartbeatScheduler集成点（空实现）

```java
// SessionManager中预留
public Session createHeartbeatSession(String agentId) {
    // 已实现（§8），心跳会话仅内存，不持久化
    // TODO Phase 4: HeartbeatScheduler调用此方法创建心跳上下文
    return Session.builder()
            .sessionId("heartbeat-" + UUID.randomUUID().toString().substring(0,
                    storageProperties.getSession().getIdLength()))
            .agentId(agentId)
            .heartbeatMode(true)
            .messages(new ArrayList<>())
            .build();
}
```



#### Phase 3/4 桩代码汇总

| # | 方法/类 | 所属Phase | 当前状态 | 日后实现内容 |
|---|---------|----------|---------|------------|
| 1 | `SessionManager.onCompactionComplete()` | Phase 3 | **空方法体** | CompactionStage完成后：①compactionCount++ ②写compaction事件到JSONL ③UPDATE SQLite |
| 2 | `SessionRepository.markMessagesTrimmed()` | Phase 3 | **空方法体** | ContextPruner TTL修剪后：标注被修剪的messageIndex范围 |
| 3 | `SessionManager.appendCompactionEvent()` | Phase 3 | **调用框架已有**，底层onCompactionComplete为空 | 实际写入compaction JSONL行 |
| 4 | JSONL加载压缩感知 | Phase 3 | **仅注释描述**（§13.2），无代码 | `DefaultJsonlReader` 遇到compaction行时标记后续消息段为"已压缩" |
| 5 | `CompactionTriggerHook` | Phase 3 | **仅注释提及**（§9.5.2），未创建类 | 实现为`ReActMessageHook`，每条消息后检查是否触发压缩 |
| 6 | `HeartbeatScheduler` | Phase 4 | **不存在** | 定时调度器，调用`createHeartbeatSession()`→执行心跳→检查Agent健康 |
| 7 | `SessionManager.createHeartbeatSession()` | Phase 4 | ✅ **已完整实现** | 调度器写好即用，无需改动 |

> **设计保证**：所有Phase 3/4预留点都在`SessionManager`或`SessionRepository`的方法签名层面暴露好了。CompactionStage、ContextPruner、HeartbeatScheduler只需调用即可，**不动ToolCallLoop、不动存储层、不动JSONL格式**。

### 9.5.11 与现有代码的差异总结

| 文件 | 修改内容 | 侵入程度 |
|------|---------|---------|
| `lyclaw-framework` 新增 `ReActMessageHook.java` | 定义 `void onMessage(Session, Message)` 回调接口 | 新增文件 |
| `lyclaw-framework` 新增 `SessionFactory.java` | 定义子Agent会话创建接口 | 新增文件 |
| `lyclaw-framework` 新增 `StorageProperties.java` | 存储层完整配置类（含8个session子项） | 新增文件 |
| `lyclaw-action/ToolCallLoop.java` | 新增 `List<ReActMessageHook>` 字段 + `notifyHooks()` 私有方法；每个 `messages.add()` 后加一行调用 | **极低**：不引入任何持久化import，不感知存储 |
| `lyclaw-web/SessionManager.java` | 新增 `onMessage()` 方法（ReActMessageHook语义）；`messageToJsonlFields()`；`onCompactionComplete()`（空实现） | 仅新增方法 |
| `lyclaw-web/StorageAutoConfiguration.java` | 新增 `ReActMessageHook persistenceHook()` Bean（方法引用） | 新增Bean定义 |
| `lyclaw-web/src/main/resources/application.yml` | 新增 `lyclaw.storage.session.page-size` 配置项 | 新增配置 |

---

## 10. 子Agent生成集成 — SubagentSpawner

### 10.1 跨模块依赖解决方案

`SubagentSpawner` 在 `lyclaw-framework` 模块中，但需要创建会话（SessionManager在lyclaw-web中）。
解决方案：在framework中定义 `SessionFactory` 接口，SessionManager在web层实现。

```java
// lyclaw-framework/.../persistence/SessionFactory.java
package lyjew.com.lyclaw.persistence;

import lyjew.com.lyclaw.model.Session;

/**
 * 会话工厂接口——供framework层的SubagentSpawner使用，
 * 避免直接依赖web层的SessionManager。
 */
public interface SessionFactory {
    /** 创建子Agent会话 */
    Session createSubagentSession(String parentSessionId, String parentAgentId,
                                   String childAgentId, String model);
    /** 获取某Agent的活跃子会话数 */
    int getActiveCount(String agentId);
}
```

### 10.2 重写SubagentSpawner

当前 `SubagentSessionManager` 在 `lyclaw-framework/.../react/subagent/` 下，使用层级key的ConcurrentHashMap。重写为：

```java
package lyjew.com.lyclaw.react.subagent;

import lyjew.com.lyclaw.model.Session;
import lyjew.com.lyclaw.persistence.SessionFactory;
import lyjew.com.lyclaw.persistence.repository.AgentRepository;

/**
 * 子Agent生成器——负责在执行delegate_to_agent时创建子会话。
 * 通过SessionFactory接口解耦，不直接依赖lyclaw-web模块。
 */
public class SubagentSpawner {

    private final SessionFactory sessionFactory;  // 接口，非直接依赖SessionManager
    private final AgentRepository agentRepository;

    public SubagentSpawner(SessionFactory sessionFactory, AgentRepository agentRepository) {
        this.sessionFactory = sessionFactory;
        this.agentRepository = agentRepository;
    }

    /**
     * 创建一个子Agent会话。
     * @param parentSession 父Agent的会话
     * @param targetAgentId 要生成的子Agent ID
     * @param task 委托任务描述
     * @return 子Agent的Session对象
     */
    public Session spawn(Session parentSession, String targetAgentId, String task) {
        // 1. 校验Agent是否存在
        var agent = agentRepository.findById(targetAgentId);
        if (agent == null) {
            throw new IllegalArgumentException("Agent不存在: " + targetAgentId);
        }

        // 2. 校验子Agent上限
        int maxChildren = (Integer) agent.getOrDefault("max_children", 5);
        int currentChildren = sessionFactory.getActiveCount(targetAgentId);
        if (currentChildren >= maxChildren) {
            throw new IllegalStateException("子Agent数量已达上限: " + targetAgentId);
        }

        // 3. 创建子会话
        String model = (String) agent.get("model");
        Session childSession = sessionFactory.createSubagentSession(
                parentSession.getSessionId(),
                parentSession.getAgentId(),
                targetAgentId,
                model
        );

        // 4. 在父会话的JSONL中记录subagent引用
        // （由tool_result消息中的subagentSessionId字段承载）

        return childSession;
    }
}
```



## 11. 启动恢复

在 `lyclaw-web` 的配置类或 `@PostConstruct` 中：

```java
@Configuration
@EnableConfigurationProperties(StorageProperties.class)
public class StorageAutoConfiguration {

    @Bean
    public SqliteConnectionManager sqliteConnectionManager(
            @Value("${lyclaw.storage.base-path}") String basePath) {
        SqliteConfig config = SqliteConfig.builder()
                .dbPath(basePath + "/index/lyclaw.db")
                .build();
        return new SqliteConnectionManager(config);
    }

    @Bean
    public SqliteMigrationService sqliteMigrationService(SqliteConnectionManager cm) {
        SqliteMigrationService service = new SqliteMigrationService(cm);
        service.migrate();  // 启动时自动建表
        return service;
    }

    @Bean
    public JsonlWriter jsonlWriter(ObjectMapper objectMapper) {
        return new DefaultJsonlWriter(objectMapper);
    }

    @Bean
    public JsonlReader jsonlReader(ObjectMapper objectMapper) {
        return new DefaultJsonlReader(objectMapper);
    }

    @Bean
    public AgentRepository agentRepository(SqliteConnectionManager cm) {
        return new AgentRepository(cm);
    }

    @Bean
    public SessionRepository sessionRepository(SqliteConnectionManager cm,
                                                JsonlWriter writer, JsonlReader reader,
                                                StorageProperties storageProperties) {
        return new SessionRepository(cm, writer, reader, storageProperties);
    }

    @Bean
    public ApprovalRepository approvalRepository(SqliteConnectionManager cm) {
        return new ApprovalRepository(cm);
    }

    @Bean
    public AsyncWriteQueueRegistry asyncWriteQueueRegistry() {
        return new AsyncWriteQueueRegistry();
    }

    @Bean
    public SessionFactory sessionFactory(SessionManager sessionManager) {
        return sessionManager;  // SessionManager同时实现SessionFactory
    }

    @Bean
    public ReActMessageHook persistenceHook(SessionManager sessionManager) {
        return sessionManager::onMessage;  // 方法引用，签名匹配
    }

    @Bean
    public SessionManager sessionManager(SessionRepository sessionRepo,
                                          AsyncWriteQueueRegistry queueRegistry,
                                          StorageProperties storageProperties) {
        return new SessionManager(sessionRepo, queueRegistry, storageProperties);
    }
}
```

> **启动恢复**：启动时不自动加载JSONL到内存。用户选择某会话时，通过 `GET /api/agents/{agentId}/sessions/{sessionId}/messages` 懒加载JSONL行。SQLite在启动时已经可用，列表页毫秒级响应。

---

## 12. 心跳会话处理

心跳会话 (`heartbeatMode=true`) 的存储层防护在三个位置：

1. **SessionManager.appendMessage()** — 检查 `session.isHeartbeatSession()` → true则直接return
2. **AsyncWriteQueue** — 不注册（心跳会话不调用 `queueRegistry.getOrCreate`）
3. **SessionRepository** — 心跳会话不调用 `create()`，无SQLite记录

```java
// SessionManager中的使用模式：
public void processHeartbeat(String agentId, String heartbeatPrompt) {
    Session hbSession = createHeartbeatSession(agentId);
    // 执行心跳逻辑...
    // 心跳结束，session随GC回收，无任何持久化痕迹
}
```

---

## 13. 压缩/修剪集成

### 13.1 CompactionStage完成后

```java
// 在CompactionStage执行完毕后：
sessionManager.appendCompactionEvent(session,
    compactedMessageCount, summaryTokens, qualityScore);

// SessionManager内部：
public void appendCompactionEvent(Session session, int msgCount, int tokens, double score) {
    if (session.isHeartbeatSession()) return;
    session.setCompactionCount(session.getCompactionCount() + 1);
    AsyncWriteQueue queue = queueRegistry.getOrCreate(session.getSessionId(),
            () -> new AsyncWriteQueue(session.getSessionId(), sessionRepository,
                    storageProperties.getSession().getWriteQueueCapacity()));
    queue.enqueueCompaction(session.getFilePath(), msgCount, tokens, score);
}
```

### 13.2 JSONL加载时的压缩感知

```java
// DefaultJsonlReader中读取消息时，遇到compaction事件行则标记该段消息已压缩
// 前端加载时看到compaction标记后跟随的摘要消息，取代被压缩的原始消息段
```

---

## 14. Agent生命周期实现

### 14.1 创建Agent

```java
// AgentController (lyclaw-web新增)
@PostMapping("/api/agents")
public Map<String, Object> createAgent(@RequestBody Map<String, Object> request) {
    String agentId = UUID.randomUUID().toString().substring(0, 8);
    long now = System.currentTimeMillis();
    String dirPath = storageBasePath + "/agents/" + agentId;

    // 1. 创建目录
    new java.io.File(dirPath).mkdirs();

    // 2. 写入agent.json
    Map<String, Object> agentJson = new LinkedHashMap<>(request);
    agentJson.put("agent_id", agentId);
    agentJson.put("created_at", now);
    // 3. 写入agent.json
    try {
        objectMapper.writeValue(new java.io.File(dirPath + "/agent.json"), agentJson);
    } catch (java.io.IOException e) {
        throw new RuntimeException("写入agent.json失败: " + agentJsonFile, e);
    }

    // 3. 写入引导文件模板（AGENTS.md, SOUL.md, IDENTITY.md等）
    writeBootstrapFiles(dirPath, request);

    // 4. INSERT SQLite
    Map<String, Object> dbRow = new LinkedHashMap<>();
    dbRow.put("agent_id", agentId);
    dbRow.put("agent_name", request.get("agentName"));
    dbRow.put("lifecycle", "permanent");
    dbRow.put("created_by", "user");
    dbRow.put("model", request.getOrDefault("model", "deepseek-v4-flash"));
    dbRow.put("provider", request.getOrDefault("provider", "deepseek"));
    dbRow.put("created_at", now);
    dbRow.put("directory_path", dirPath);
    // ... 其他字段使用默认值
    agentRepository.insert(dbRow);

    return Map.of("agentId", agentId, "agentName", request.get("agentName"), "createdAt", now);
}
```

### 14.2 级联删除Agent

```java
public void deleteAgent(String agentId) {
    // 1. 查找所有子孙临时Agent
    List<String> descendants = findAllTemporaryDescendants(agentId);
    // 2. 递归删除（从叶子到根）
    for (String desc : descendants) {
        deleteAgentSessions(desc);   // 删除jsonl + SQLite sessions
        agentRepository.delete(desc); // 删除SQLite agents行
        deleteDirectory(desc);       // rm -rf agents/{desc}/
    }
    // 3. 删除本Agent
    deleteAgentSessions(agentId);
    agentRepository.delete(agentId);
    deleteDirectory(agentId);
    // 4. 子孙永久Agent断开parent关系
    agentRepository.update(agentId, Map.of("parent_agent_id", null));
}

private List<String> findAllTemporaryDescendants(String agentId) {
    // SQL: WITH RECURSIVE descendants AS (
    //   SELECT agent_id, lifecycle FROM agents WHERE parent_agent_id = ?
    //   UNION ALL
    //   SELECT a.agent_id, a.lifecycle FROM agents a
    //   JOIN descendants d ON a.parent_agent_id = d.agent_id
    // ) SELECT agent_id FROM descendants WHERE lifecycle = 'temporary'
    // 按深度排序，确保先删叶子节点
}
```

---

## 15. HTTP API 对应实现

| API | Controller | 调用的Repository/Manager |
|-----|-----------|-------------------------|
| `GET /api/agents` | AgentController | AgentRepository.findAllSummary() |
| `GET /api/agents/{id}` | AgentController | AgentRepository.findById() + 读agent.json |
| `POST /api/agents` | AgentController | AgentRepository.insert() + 写入文件 |
| `PUT /api/agents/{id}` | AgentController | AgentRepository.update() + 更新文件 |
| `DELETE /api/agents/{id}` | AgentController | 级联删除（见§14.2） |
| `GET /api/agents/{id}/sessions` | SessionController | SessionRepository.findByAgentId() |
| `GET .../sessions/{id}/messages` | SessionController | SessionRepository.readMessages() |
| `DELETE .../sessions/{id}` | SessionController | SessionManager.deleteSession() |
| `GET .../sessions/{id}/children` | SessionController | SessionRepository.findByParentSessionId() |
| `POST /api/chat/stream` | ChatController | SessionManager.createSession() / getSession() |

---

## 16. 实施顺序

按依赖关系排列的24个步骤（含步骤0：删除旧代码）：

| 步骤 | 内容 | 模块 | 依赖 | 预估工作量 |
|------|------|------|------|-----------|
| 0 | **删除旧存储代码**（35个文件，见§17） | framework+autoconfigure | 无 | 30分钟 |
| 1 | 添加`sqlite-jdbc`依赖到父POM和lyclaw-framework | POM | 0 | 5分钟 |
| 2 | 实现`SqliteConfig` | framework | 1 | 10分钟 |
| 3 | 实现`SqliteConnectionManager` | framework | 2 | 20分钟 |
| 4 | 实现`SqliteMigrationService`（3张表+索引DDL） | framework | 3 | 30分钟 |
| 5 | 实现`JsonlWriter`接口 + `DefaultJsonlWriter` | framework | 无 | 20分钟 |
| 6 | 实现`JsonlReader`接口 + `DefaultJsonlReader`（含流式readRange） | framework | 无 | 30分钟 |
| 7 | 实现`ReActMessageHook`接口 | framework | 无 | 5分钟 |
| 8 | 实现`SessionFactory`接口 | framework | 无 | 5分钟 |
| 9 | 实现`AgentRepository` | framework | 3 | 45分钟 |
| 10 | 实现`SessionRepository`（含findBySessionId/findByParentSessionId/updateFirstMsgPreview） | framework | 5,6,3 | 60分钟 |
| 11 | 实现`ApprovalRepository` | framework | 3 | 20分钟 |
| 12 | 实现`AsyncWriteQueue` | framework | 10 | 45分钟 |
| 13 | 实现`AsyncWriteQueueRegistry` | framework | 12 | 15分钟 |
| 14 | 实现`StorageAutoConfiguration`（Bean定义，含SessionFactory/ReActMessageHook绑定） | web | 3-13 | 30分钟 |
| 15 | 实现`SessionManager`（含onMessage持久化钩子 + SessionFactory实现 + getSession懒加载） | web | 10,13 | 90分钟 |
| 16 | **改造`ToolCallLoop`注入List<ReActMessageHook>，4个插入点调用notifyHooks()** | action | 7 | 45分钟 |
| 17 | 改造`ChatController`接入SessionManager | web | 15 | 30分钟 |
| 18 | 改造`SubagentSpawner`使用SessionFactory接口 | framework | 8,9 | 30分钟 |
| 19 | 实现`AgentController`（CRUD API） | web | 9,15 | 60分钟 |
| 20 | 实现`SessionController`（会话列表/历史/删除API） | web | 10,15 | 45分钟 |
| 21 | 实现启动恢复逻辑 | web | 15 | 30分钟 |
| 22 | 实现心跳会话的存储层防护 | framework+web | 15 | 20分钟 |
| 23 | 实现`AgentCleanupService`（级联删除） | web | 9,15 | 45分钟 |

**总计预估**：约11.5小时纯编码时间（含30分钟旧代码清理）。

---

## 17. 旧存储代码清理清单

> **原则：旧的多后端抽象（`storage/` + `annotation/storage/` + 相关autoconfigure）全部删除。SQLite+JSONL就是唯一的存储后端，不存在"后端切换"。**

### 17.1 直接删除（35个文件）

#### storage/ 包 — 24个文件，整个包删除

| # | 文件 | 说明 |
|---|------|------|
| 1 | `storage/StorageBackend.java` | 核心SPI接口：键值存储、批量操作、查询、生命周期 |
| 2 | `storage/FileBackend.java` | JSON文件后端实现（`@StorageBackend(name="file")`） |
| 3 | `storage/InMemoryBackend.java` | ConcurrentHashMap内存后端（`@StorageBackend(name="inmemory")`） |
| 4 | `storage/StorageBackendRegistry.java` | 注册表接口：按名称和层级查找存储后端 |
| 5 | `storage/DefaultStorageBackendRegistry.java` | 注册表实现，维护EnumMap中的默认值 |
| 6 | `storage/StorageFacade.java` | 外观接口：getBackend()/switchBackend()/save()/load() |
| 7 | `storage/DefaultStorageFacade.java` | 外观实现：通过StoreLayer和命名空间启发式路由 |
| 8 | `storage/StorageCapability.java` | 枚举：KEY_VALUE/VECTOR_SEARCH/FULL_TEXT/GRAPH/BLOB等 |
| 9 | `storage/StoreLayer.java` | 枚举：SESSION/ENTITY/MEMORY三层架构 |
| 10 | `storage/StorageProperties.java` | **旧配置类**：绑定`defaultBackend`/`stores`/`backends` |
| 11 | `storage/MemoryPersistence.java` | 旧MemoryPersistence接口（persist/recover/flush） |
| 12 | `storage/MemoryWriteManager.java` | 写策略管理器接口 |
| 13 | `storage/DefaultMemoryWriteManager.java` | 写策略管理器实现 |
| 14 | `storage/MemoryWriteState.java` | WritePolicy决策用的可变状态POJO |
| 15 | `storage/MemoryLayer.java` | 枚举：SENSORY/SHORT_TERM/LONG_TERM/ENTITY |
| 16 | `storage/ThresholdWritePolicy.java` | 写策略：N条记录或T毫秒后批量写入 |
| 17 | `storage/ImmediateWritePolicy.java` | 写策略：有挂起条目立即写入 |
| 18 | `storage/HealthResult.java` | 健康检查记录 |
| 19 | `storage/QueryResult.java` | 多路径融合查询结果包装器 |
| 20 | `storage/QuerySpec.java` | Builder模式查询规约 |
| 21 | `storage/QueryPath.java` | 枚举：VECTOR/BM25/GRAPH/KEYWORD/TEMPORAL |
| 22 | `storage/FullTextEngine.java` | 枚举：BM25/TF_IDF/ELASTICSEARCH |
| 23 | `storage/GraphQueryLanguage.java` | 枚举：CYPHER/SQL_CTE/GREMLIN/SPARQL |
| 24 | `storage/DistanceFunction.java` | 枚举：COSINE/EUCLIDEAN/DOT_PRODUCT/MANHATTAN |

#### annotation/storage/ 包 — 9个文件，整个包删除

| # | 文件 | 说明 |
|---|------|------|
| 25 | `annotation/storage/StorageBackend.java` | `@StorageBackend(name, priority, autoRegister)` |
| 26 | `annotation/storage/SessionStore.java` | `@SessionStore(layerPriority, layerDefault)` |
| 27 | `annotation/storage/EntityStore.java` | `@EntityStore(layerPriority, layerDefault)` |
| 28 | `annotation/storage/MemoryStore.java` | `@MemoryStore(layerPriority, layerDefault)` |
| 29 | `annotation/storage/VectorStore.java` | `@VectorStore(dimension, distanceFunctions)` |
| 30 | `annotation/storage/FullTextStore.java` | `@FullTextStore(engine)` |
| 31 | `annotation/storage/GraphStore.java` | `@GraphStore(queryLanguage)` |
| 32 | `annotation/storage/StorageNamespace.java` | `@StorageNamespace(value)` |
| 33 | `annotation/storage/WritePolicy.java` | `@WritePolicy(name)` |

#### autoconfigure 模块 — 4个文件删除

| # | 文件 | 说明 |
|---|------|------|
| 34 | `autoconfigure/StorageAutoConfiguration.java` | **旧StorageAutoConfiguration**：创建FileBackend/InMemoryBackend/StorageFacade等Bean。替换为新的同名类（在lyclaw-web中，见§11） |
| 35 | `autoconfigure/processor/StorageBackendPostProcessor.java` | BeanPostProcessor扫描`@StorageBackend`注解注册后端 |
| 36 | `autoconfigure/processor/WritePolicyPostProcessor.java` | BeanPostProcessor扫描`@WritePolicy`注解 |
| 37 | `autoconfigure/processor/MemorySystemAutoConfigurator.java` | InitializingBean配置MemoryStore后端 |

### 17.2 重写（2个文件）

| # | 文件 | 操作 | 说明 |
|---|------|------|------|
| 38 | `autoconfigure/ProcessorAutoConfiguration.java` | **重写** | 移除StorageBackendPostProcessor/WritePolicyPostProcessor/MemorySystemAutoConfigurator的@Bean定义 |
| 39 | `react/subagent/SubagentSessionManager.java` | **重写** | 构造函数中`Object sessionStore`字段替换为`SessionFactory`接口；`AgentProxyAutoConfiguration`中的`new SubagentSessionManager(null)`改为注入SessionFactory Bean |

### 17.3 修剪（3个文件）

| # | 文件 | 操作 | 说明 |
|---|------|------|------|
| 40 | `autoconfigure/actuator/LyClawConfigEndpoint.java` | **修剪** | 移除`StorageProperties`字段和对应的`/actuator/lyclaw-config`端点中的storage信息暴露 |
| 41 | `memory/impl/TieredMemorySystem.java` | **修剪** | 移除`@MemoryStore(layerDefault = true)`注解。该类是纯内存ConcurrentHashMap实现，不需要存储层标记 |
| 42 | `autoconfigure/AgentProxyAutoConfiguration.java` | **修剪** | `SubagentSessionManager`的@Bean定义改为注入`SessionFactory` |

### 17.4 保留不动

| 文件 | 原因 |
|------|------|
| `persistence/PersistenceDecision.java` | 不变值对象（write/defer/skip），新持久化层可复用 |
| `persistence/PersistenceSignal.java` | 枚举（WRITE/DEFER/SKIP），与旧存储系统无关 |
| `persistence/session/SessionPersistence.java` | 持久化写入决策接口，评估是否写入 |
| `persistence/memory/MemoryPersistence.java` | 记忆持久化评估接口，与存储后端无关 |
| `persistence/memory/MemoryWriteState.java` | 不可变状态对象，accumulate/reset |
| `exception/StorageException.java` | 通用存储异常，新层可复用 |
| `react/ApprovalStore.java` | 审批存储（内部ConcurrentHashMap），名称含Store但与旧系统无关 |
| `retrieval/VectorStore.java` | 检索模块接口，与旧存储系统无关 |
| `memory/impl/InMemoryVectorStore.java` | lyclaw-memory模块的向量存储，不导入storage/包 |
| `SubagentSessionManager.java` | 保留但重写（见§17.2），不再基于层级key |

### 17.5 删除顺序（按依赖）

```
1. annotation/storage/ 包    (9个文件，无内部依赖)
2. storage/ 包               (24个文件，依赖注解包)
3. autoconfigure 4个文件      (依赖storage/包)
4. 重写 ProcessorAutoConfiguration (移除对已删除类的引用)
5. 修剪 LyClawConfigEndpoint / TieredMemorySystem / AgentProxyAutoConfiguration
```
---

## 18. 前端实现方案

> 基于 §15 定义的 HTTP API，前端无需感知后端存储细节（SQLite/JSONL）。以下描述组件树、按钮交互、JS脚本核心逻辑和SSE流式数据流。

### 18.1 页面布局

```
┌──────────┬──────────────────────────────────────────┐
│ Sidebar  │  Chat Area                               │
│ 260px    │                                          │
│          │  ┌─────────────────────────────────────┐ │
│ ┌──────┐ │  │ Message List (scrollable)           │ │
│ │Agent │ │  │  user: "帮我查这个bug"              │ │
│ │Select│ │  │  assistant: "好的，让我看看..."      │ │
│ │  r   │ │  │  tool_call: read_file               │ │
│ │ coder│ │  │  tool_result: {file content}        │ │
│ └──────┘ │  │  assistant: "找到了，第42行..."     │ │
│          │  └─────────────────────────────────────┘ │
│ Sessions │  ┌─────────────────────────────────────┐ │
│ ┌──────┐ │  │ Input Box                    [发送] │ │
│ │· bug │ │  └─────────────────────────────────────┘ │
│ │  fix │ │                                          │
│ │· code│ │                                          │
│ │ revw │ │                                          │
│ │+ 新建│ │                                          │
│ └──────┘ │                                          │
└──────────┴──────────────────────────────────────────┘
```

### 18.2 组件树

```
<App>
  <AgentSidebar>                          // 左侧：Agent选择+会话列表
    <AgentSelector />                     // 下拉选择Agent (GET /api/agents)
    <AgentCreateButton />                 // + 新建Agent (POST /api/agents)
    <SessionList>                         // 当前Agent的会话列表
      <SessionItem                        // 单条会话行
        :title="first_msg_preview"
        :date="updatedAt"
        :active="isCurrentSession"
        @click="switchSession"
        @delete="deleteSession" />       // DELETE /api/agents/{id}/sessions/{sid}
      <SessionCreateButton />            // + 新建会话 (POST /api/sessions)
    </SessionList>
  </AgentSidebar>

  <ChatMain>                              // 右侧：聊天主区域
    <MessageContainer>                    // 消息列表 (GET .../sessions/{id}/messages?page=0)
      <MessageBubble                      // 单条消息气泡
        :role="user|assistant|tool"
        :content="..."
        :toolCalls="[...]"
        :thinking="..." />
      <ToolCallCard                       // 工具调用卡片（折叠展开）
        :toolName="read_file"
        :status="running|success|error"
        :result="..." />
      <ThinkingBlock                      // 思考过程（可折叠）
        :content="thinking" />
      <LoadMoreButton                     // 加载更多历史 (GET .../messages?page=N)
        @click="loadOlderMessages" />
    </MessageContainer>
    <ChatInput                            // 底部输入区
      @send="sendMessage"
      @stop="abortStream" />
  </ChatMain>
</App>
```

### 18.3 关键按钮和交互

| 按钮/组件 | 位置 | 触发API | 说明 |
|-----------|------|---------|------|
| **Agent选择器** | Sidebar顶部 | `GET /api/agents` | 下拉菜单，切换Agent时重新加载该Agent的会话列表 |
| **+ 新建Agent** | Agent选择器旁 | `POST /api/agents` | 弹出Modal：输入name/description/systemPrompt/model |
| **Agent编辑** | Agent名称旁(齿轮图标) | `PUT /api/agents/{id}` | 弹出Modal编辑Agent配置 |
| **Agent删除** | 编辑Modal底部(红色) | `DELETE /api/agents/{id}` | 二次确认，级联删除所有会话和JSONL文件 |
| **+ 新建会话** | SessionList底部 | `POST /api/sessions` | 创建新会话，自动切换到该会话 |
| **会话项** | SessionList主体 | — | 点击切换到该会话，右侧加载消息历史 |
| **会话删除** | 会话项右侧(×图标) | `DELETE .../sessions/{id}` | 确认后删除会话及其JSONL |
| **加载更多** | 消息列表顶部 | `GET .../sessions/{id}/messages?page=N` | 翻页加载更早的消息，`pageSize`由后端`lyclaw.storage.session.page-size`决定 |
| **发送按钮** | ChatInput右侧 | `POST /api/chat/stream` (SSE) | 发送消息，建立SSE连接接收流式响应 |
| **停止生成** | 发送按钮位置(流式中切换) | `EventSource.close()` | 关闭SSE连接，中断生成 |
| **子会话展开** | tool_result中的delegate_to_agent结果 | `GET .../sessions/{childId}/messages` | 点击子Agent的会话链接，展开查看子Agent的完整对话 |

### 18.4 API客户端 (`api.js`)

```javascript
const API = {
  // ── Agent ──
  async listAgents() {
    const res = await fetch('/api/agents');
    return res.json();
  },
  async createAgent(data) {
    const res = await fetch('/api/agents', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(data)
    });
    return res.json();
  },
  async updateAgent(id, data) {
    const res = await fetch(`/api/agents/${id}`, {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(data)
    });
    return res.json();
  },
  async deleteAgent(id) {
    const res = await fetch(`/api/agents/${id}`, { method: 'DELETE' });
    return res.json();
  },

  // ── Sessions ──
  async listSessions(agentId) {
    const res = await fetch(`/api/agents/${agentId}/sessions`);
    return res.json();  // [{sessionId, firstMsgPreview, messageCount, updatedAt, ...}]
  },
  async createSession(agentId) {
    const res = await fetch(`/api/agents/${agentId}/sessions`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ agentId: agentId })
    });
    if (!res.ok) throw new Error('创建会话失败');
    return res.json();
  },
  async deleteSession(agentId, sessionId) {
    const res = await fetch(`/api/agents/${agentId}/sessions/${sessionId}`, {
      method: 'DELETE'
    });
    return res.json();
  },
  async getMessages(agentId, sessionId, page = 0) {
    const res = await fetch(
      `/api/agents/${agentId}/sessions/${sessionId}/messages?page=${page}`
    );
    return res.json();  // { messages: [...], hasMore: true/false, totalCount: N }
  },
  async getChildSessions(agentId, sessionId) {
    const res = await fetch(
      `/api/agents/${agentId}/sessions/${sessionId}/children`
    );
    return res.json();
  },

  // ── Chat (非流式) ──
  async chat(message, sessionId) {
    const res = await fetch('/api/chat', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        messages: [{ role: 'user', content: message }],
        sessionId: sessionId
      })
    });
    return res.json();  // { content: "...", sessionId: "..." }
  },

  // ── Chat (SSE流式) —— 核心 ──
  chatStream(message, sessionId, callbacks) {
    const { onThinking, onContent, onToolCall, onToolResult, onDone, onError } = callbacks;
    const controller = new AbortController();

    fetch('/api/chat/stream', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        messages: [{ role: 'user', content: message }],
        sessionId: sessionId
      }),
      signal: controller.signal
    }).then(async (response) => {
      if (!response.ok) {
        const errText = await response.text();
        onError?.(`HTTP ${response.status}: ${errText}`);
        return;
      }
      const reader = response.body.getReader();
      const decoder = new TextDecoder();
      let buffer = '';

      while (true) {
        const { done, value } = await reader.read();
        if (done) break;

        buffer += decoder.decode(value, { stream: true });
        const lines = buffer.split('\n');
        buffer = lines.pop();  // 保留未完成的行

        for (const line of lines) {
          if (!line.startsWith('data:')) continue;
          const data = line.slice(5).trim();
          if (!data) continue;

          try {
            const event = JSON.parse(data);
            switch (event.type) {
              case 'thinking':
                onThinking?.(event.content);
                break;
              case 'content':
                onContent?.(event.content);
                break;
              case 'tool_call':
                onToolCall?.(event.toolCall);  // {name, arguments, id}
                break;
              case 'tool_result':
                onToolResult?.(event);  // {toolCallId, result/error, toolName}
                break;
              case 'done':
                onDone?.(event);
                break;
              case 'error':
                onError?.(event.message);
                break;
            }
          } catch (e) { /* 忽略解析错误 */ }
        }
      }
    }).catch(err => {
      if (err.name !== 'AbortError') {
        if (onError) onError(err.message);
        else console.error('chatStream error:', err);
      }
    });

    return controller;  // 返回AbortController用于停止生成
  }
};
```

### 18.5 聊天状态管理 (`chat-state.js`)

```javascript
const ChatState = {
  currentAgent: null,         // { agentId, name, model }
  currentSession: null,       // { sessionId, firstMsgPreview }
  messages: [],               // 当前显示的消息列表
  messagePage: 0,             // 当前分页
  hasMoreMessages: false,     // 是否还有更早的消息
  isStreaming: false,         // 是否正在SSE流式生成中
  streamController: null,     // AbortController引用

  async selectAgent(agentId) {
    // 直接获取单个Agent
    try {
      const res = await fetch(`/api/agents/${agentId}`);
      if (res.ok) this.currentAgent = await res.json();
      else this.currentAgent = { agentId };
    } catch (e) {
      this.currentAgent = { agentId };
    }
    this.currentSession = null;
    this.messages = [];
    // 重新加载该Agent的会话列表
    const sessions = await API.listSessions(agentId);
    renderSessionList(sessions);
  },

  async selectSession(session) {
    if (!this.currentAgent?.agentId) {
      console.warn('selectSession called before agent selection');
      return;
    }
    this.currentSession = session;
    this.messagePage = 0;
    this.messages = [];
    // 懒加载消息历史
    const result = await API.getMessages(
      this.currentAgent.agentId, session.sessionId, 0
    );
    this.messages = result.messages;
    this.hasMoreMessages = result.hasMore;
    renderMessages(this.messages);
  },

  async loadOlderMessages() {
    if (!this.hasMoreMessages) return;
    this.messagePage++;
    const result = await API.getMessages(
      this.currentAgent.agentId,
      this.currentSession.sessionId,
      this.messagePage
    );
    // 前置插入旧消息
    this.messages = [...result.messages, ...this.messages];
    this.hasMoreMessages = result.hasMore;
    renderMessages(this.messages);
  },

  async sendMessage(text) {
    if (this.isStreaming) return;
    try {
      if (!this.currentSession) {
        // 无会话时自动创建
        const agentId = this.currentAgent?.agentId || 'chat';
        const session = await API.createSession(agentId);
        if (!session?.sessionId) throw new Error('会话创建失败');
        this.currentSession = session;
      }
      // 捕获sessionId防止后续修改
      const sessionId = this.currentSession.sessionId;
      if (!sessionId) throw new Error('sessionId缺失');

      // 1. 立即显示用户消息
      const userMsg = { role: 'user', content: text };
      this.messages.push(userMsg);
      renderMessages(this.messages);

      // 2. 创建assistant占位
      const assistantMsg = { role: 'assistant', content: '', thinking: '', toolCalls: [] };
      this.messages.push(assistantMsg);
      const assistantIdx = this.messages.length - 1;
      renderMessages(this.messages);

      // 3. 开启SSE流式
      this.isStreaming = true;
      this.streamController = API.chatStream(text, sessionId, {
      onThinking(chunk) {
        if (ChatState.currentSession?.sessionId !== sessionId) return;
        assistantMsg.thinking += chunk;
        updateMessageBubble(assistantIdx);
      },
      onContent(chunk) {
        if (ChatState.currentSession?.sessionId !== sessionId) return;
        assistantMsg.content += chunk;
        updateMessageBubble(assistantIdx);
      },
      onToolCall(toolCall) {
        if (ChatState.currentSession?.sessionId !== sessionId) return;
        // 显示工具调用卡片
        assistantMsg.toolCalls.push({
          id: toolCall.id,
          name: toolCall.name,
          arguments: toolCall.arguments,
          status: 'running'
        });
        updateMessageBubble(assistantIdx);
      },
      onToolResult(event) {
        if (ChatState.currentSession?.sessionId !== sessionId) return;
        // 更新工具调用状态
        const tc = assistantMsg.toolCalls.find(t => t.id === event.toolCallId);
        if (tc) {
          tc.status = event.error ? 'error' : 'success';
          tc.result = event.result || event.error;
        }
        // 追加工具结果消息
        ChatState.messages.push({
          role: 'tool',
          toolCallId: event.toolCallId,
          toolName: event.toolName,
          content: event.result || event.error
        });
        renderMessages(ChatState.messages);
      },
      onDone(event) {
        ChatState.isStreaming = false;
        ChatState.streamController = null;
        if (ChatState.currentSession?.sessionId === sessionId) {
          updateSendButton('send');
        }
        // 刷新会话列表（更新firstMsgPreview/messageCount）
        refreshSessionList();
      },
      onError(msg) {
        if (ChatState.currentSession?.sessionId !== sessionId) return;
        assistantMsg.content = '[错误] ' + msg;
        ChatState.isStreaming = false;
        ChatState.streamController = null;
        updateMessageBubble(assistantIdx);
        updateSendButton('send');
      }
    });
    updateSendButton('stop');
  },

  abortStream() {
    if (this.streamController) {
      this.streamController.abort();
      this.isStreaming = false;
      this.streamController = null;
      updateSendButton('send');
    }
  }
};
```

### 18.6 工具调用卡片渲染

```javascript
function renderToolCallCard(toolCall) {
  const statusIcon = { running: '⏳', success: '✅', error: '❌' }[toolCall.status];
  const isCollapsed = toolCall.status === 'success';  // 成功默认折叠

  return `
    <div class="tool-call-card ${toolCall.status}">
      <div class="tool-call-header" onclick="toggleToolCall(this)">
        ${statusIcon} <strong>${toolCall.name}</strong>
        <span class="tool-args-preview">${truncate(toolCall.arguments, 60)}</span>
        <span class="collapse-icon">${isCollapsed ? '▶' : '▼'}</span>
      </div>
      <div class="tool-call-body" style="display:${isCollapsed ? 'none' : 'block'}">
        <pre class="tool-args">${JSON.stringify(typeof toolCall.arguments === 'string' ? JSON.parse(toolCall.arguments) : toolCall.arguments, null, 2)}</pre>
        ${toolCall.result ? `<pre class="tool-result">${escapeHtml(toolCall.result)}</pre>` : '<div class="spinner"></div>'}
      </div>
    </div>
  `;
}
```

### 18.7 核心数据流（端到端）

```
用户点击发送
  │
  ├─ session不存在?
  │   └─ POST /api/sessions → 创建新会话 → 获得sessionId
  │
  ├─ 用户消息立即显示 (role:user)
  │
  ├─ POST /api/chat/stream (SSE)
  │   │
  │   ├─ event: {type:"thinking", content:"让我想想..."}
  │   │   └─ 渲染思考块（可折叠灰色文字）
  │   │
  │   ├─ event: {type:"tool_call", toolCall:{name:"read_file",...}}
  │   │   └─ 渲染工具调用卡片（⏳ 执行中）
  │   │
  │   ├─ event: {type:"tool_result", toolCallId:"...", result:"..."}
  │   │   └─ 更新卡片状态 ✅ + tool消息行
  │   │
  │   ├─ event: {type:"content", content:"根据代码..."}
  │   │   └─ 打字机效果逐字追加到assistant气泡
  │   │
  │   └─ event: {type:"done", sessionId:"abc123"}
  │       └─ 刷新会话列表（firstMsgPreview更新）
  │
  └─ 用户切换到另一个会话
      └─ GET .../sessions/{newId}/messages?page=0 → 懒加载消息历史
```

### 18.8 SSE事件类型参考

| event.type | 携带字段 | 触发时机 | 前端渲染 |
|-----------|---------|---------|---------|
| `thinking` | `content: String` | LLM输出思考链 | 灰色可折叠区域，打字机追加 |
| `content` | `content: String` | LLM输出回复文本 | assistant气泡，打字机追加 |
| `tool_call` | `toolCall: {id, name, arguments}` | LLM决定调用工具 | 工具调用卡片，⏳状态 |
| `tool_result` | `toolCallId, toolName, result/error` | 工具执行完成 | 更新卡片状态✅/❌，追加tool消息行 |
| `done` | `sessionId, model, usage` | ReAct循环结束 | 恢复发送按钮，刷新会话列表 |
| `error` | `message: String` | 异常中断 | assistant气泡显示错误信息 |

### 18.9 建议技术栈

| 层 | 选择 | 理由 |
|----|------|------|
| 框架 | **Vanilla JS + Web Components** 或 **Preact** | 无构建工具负担，直接与Thymeleaf或静态HTML配合 |
| SSE解析 | 原生 `fetch` + `ReadableStream` | 零依赖，标准API，支持AbortController中断 |
| 状态管理 | 单例 `ChatState` 对象 | 状态简单（单页单会话），不需要Redux/MobX |
| CSS | 纯CSS + CSS Variables | 无框架依赖，支持暗色模式切换 |
| Markdown渲染 | `marked.js` (~30KB) | assistant回复中的代码块、表格需要渲染 |
| 代码高亮 | `highlight.js`（按需加载语言） | 工具调用结果和代码块需要语法高亮 |

### 18.10 未在代码中展开的UI函数（框架定义）

以下函数由UI框架层实现，具体取决于使用的前端框架（Vanilla JS / Preact / Vue等）：

```javascript
// ── 渲染函数（框架相关，此处仅签名） ──

/** 渲染侧边栏会话列表 */
function renderSessionList(sessions) {
  // 清空并重建 SessionList DOM
  // sessions: [{sessionId, firstMsgPreview, messageCount, updatedAt}, ...]
}

/** 全量渲染消息区域（切换会话时调用） */
function renderMessages(messages) {
  // 清空并重建 MessageContainer DOM
  // 每条消息根据 role 渲染 MessageBubble / ToolCallCard / ThinkingBlock
}

/** 局部更新单个消息气泡（流式追加时调用，避免全量重绘） */
function updateMessageBubble(index) {
  // 只更新第 index 个消息气泡的DOM
}

/** 切换发送/停止按钮状态 */
function updateSendButton(state) {
  // state: 'send' | 'stop'
  // 'stop' 时显示停止按钮，点击触发 ChatState.abortStream()
}

/** 刷新会话列表（新消息后更新 firstMsgPreview 和排序） */
function refreshSessionList() {
  // 重新调用 API.listSessions(currentAgent.agentId) 并 renderSessionList
}

/** 截断字符串（工具调用参数预览） */
function truncate(str, maxLen) {
  if (!str || str.length <= maxLen) return str;
  return str.substring(0, maxLen) + '...';
}

/** HTML转义（防止XSS） */
function escapeHtml(str) {
  const div = document.createElement('div');
  div.textContent = str;
  return div.innerHTML;
}

/** 折叠/展开工具调用卡片 */
function toggleToolCall(headerEl) {
  const body = headerEl.nextElementSibling;
  const icon = headerEl.querySelector('.collapse-icon');
  if (body.style.display === 'none') {
    body.style.display = 'block';
    if (icon) icon.textContent = '▼';
  } else {
    body.style.display = 'none';
    if (icon) icon.textContent = '▶';
  }
}
```


---

## 附录A: 类关系图

```
                    ┌─────────────────────┐
                    │  StorageAutoConfig  │ (lyclaw-web)
                    │  (@Configuration)   │
                    └──┬──┬──┬──┬──┬─────┘
                       │  │  │  │  │
              ┌────────┘  │  │  │  └──────────┐
              ▼           │  │  │              ▼
   ┌─────────────────┐   │  │  │   ┌──────────────────┐
   │SqliteConnMgr    │◄──┘  │  │   │AsyncWriteQueueReg│
   └────────┬────────┘      │  │   └────────┬─────────┘
            │               │  │            │
   ┌────────▼────────┐      │  │   ┌────────▼─────────┐
   │SqliteMigration  │      │  │   │ AsyncWriteQueue  │
   │Service          │      │  │   │ (per session)    │
   └─────────────────┘      │  │   └────────┬─────────┘
                            │  │            │
   ┌───────────────────────┐│  │            │
   │ AgentRepository       ││  │            │
   │ (JDBC → agents表)     ││  │            │
   └───────────────────────┘│  │            │
                            │  │            │
   ┌───────────────────────┐│  │            │
   │ SessionRepository     ││  │            │
   │ (JSONL + sessions表)  ││  │            │
   └───────────────────────┘│  │            │
                            │  │            │
   ┌───────────────────────┐│  │            │
   │ ApprovalRepository    ││  │            │
   │ (JDBC → approvals表)  ││  │            │
   └───────────────────────┘│  │            │
                            │  │            │
              ┌─────────────┘  │            │
              ▼                │            │
   ┌──────────────────┐       │            │
   │ JsonlWriter/     │       │            │
   │ JsonlReader      │◄──────┘            │
   └──────────────────┘                    │
                                           │
              ┌────────────────────────────┘
              ▼
   ┌──────────────────┐
   │ SessionManager   │ (lyclaw-web)
   │ (ConcurrentHashMap│
   │  缓存 + 懒加载)  │
   └──┬──────┬───────┘
      │      │
      ▼      ▼
┌─────────┐ ┌──────────────┐
│ChatCtrl │ │AgentCtrl     │
└─────────┘ └──────────────┘
```

---

## 附录B: JSONL示例

**某完整会话的JSONL文件内容**：
> **注意**：以下为完整示例展示全部可能的JSONL行类型。实际代码生成的字段以 §6.2 SessionRepository.create() 和 §8 SessionManager.messageToJsonlFields() 为准。
> `session_created` 行仅含 6 个核心字段（type/sessionId/agentId/parentSessionId/parentAgentId/timestamp）；`tool_result` 行仅含 7 个字段（type/role/content/timestamp/messageIndex/toolCallId/toolName）。
> 示例中的 `agentName`、`workspaceDir`、`systemPrompt`、`tools`、`success`、`durationMs` 为 Phase 3 预留扩展字段，当前阶段不写入。


```jsonl
{"type":"session_created","sessionId":"abc12345","agentId":"coder","agentName":"代码助手","parentSessionId":null,"parentAgentId":null,"workspaceDir":"/home/lyjew/projects/myapp","systemPrompt":"你是一个专业的代码助手...","thinkingLevel":"medium","verboseLevel":"low","reasoningLevel":"medium","fastMode":false,"sandboxLevel":"PROCESS","tools":[{"name":"read_file","description":"读取文件内容"},{"name":"delegate_to_agent","description":"委托任务"}],"timestamp":1716300000000}
{"type":"message","role":"user","content":"帮我查个bug","timestamp":1716300001000,"messageIndex":0}
{"type":"message","role":"assistant","content":"我来帮你排查。先看看最近的改动。","thinking":"需要先了解最近的代码变更","model":"deepseek-v4-flash","toolCalls":[{"id":"call_001","name":"bash","description":"查看最近改动","arguments":"{\"command\":\"cd /home/lyjew/projects/myapp && git diff HEAD~1\"}"}],"usage":{"promptTokens":1200,"completionTokens":300,"totalTokens":1500},"thinkingBudget":4096,"timestamp":1716300010000,"messageIndex":1}
{"type":"tool_result","role":"tool","toolName":"bash","toolCallId":"call_001","content":"diff --git a/src/App.java b/src/App.java\n+    // BUG: null check missing\n     public void process(String input) {\n-        if (input != null) {\n+        input.trim();","success":true,"durationMs":120,"timestamp":1716300020000,"messageIndex":2}
{"type":"message","role":"assistant","content":"找到问题了！在src/App.java中，null检查被移除了，导致后续trim()会抛NPE。需要恢复原来的null检查。","model":"deepseek-v4-flash","usage":{"promptTokens":2000,"completionTokens":150,"totalTokens":2150},"timestamp":1716300050000,"messageIndex":3}
{"type":"compaction","messagesCompacted":0,"summaryTokens":0,"qualityScore":0.0,"timestamp":1716305000000}
```

---

## 附录C: 关键设计决策回顾

| 决策 | 选择 | 备选 | 理由 |
|------|------|------|------|
| SQLite访问方式 | **纯JDBC** | Hibernate/Spring Data | 3张表，ORM过度；框架层零Spring依赖 |
| 消息存储格式 | **JSONL** | SQLite BLOB列 / 每消息一行SQL | append-only无碎片；人类可读；grep友好 |
| 写入模型 | **per-session BlockingQueue** | 全局线程池 / 同步写入 | FIFO保证；session隔离；不阻塞SSE |
| 旧数据加载 | **懒加载** | 启动全量加载 | 大量会话时内存可控 |
| 日志清理 | **逻辑标记不物理删除** | 重写JSONL文件 | 保持append-only语义 |
| Agent配置来源 | **agent.json + SQLite互补** | 纯SQLite | 文件可直接编辑；SQLite提供快速查询 |
