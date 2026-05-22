package lyjew.com.lyclaw.persistence.sqlite;

import java.sql.Connection;
import java.sql.Statement;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 数据库迁移服务——启动时执行DDL，幂等（IF NOT EXISTS）。
 *
 * 迁移内容：
 * 1. 启用WAL模式 + 外键约束
 * 2. 创建agents表（25列）——Agent元数据
 * 3. 创建sessions表（13列）——会话元数据摘要
 * 4. 创建approvals表（11列）——工具调用审批记录
 * 5. 创建8个索引——覆盖常用查询路径
 *
 * 在StorageAutoConfiguration中作为@Bean初始化回调调用migrate()。
 */
public class SqliteMigrationService {

    private static final Logger log = LoggerFactory.getLogger(SqliteMigrationService.class);
    private final SqliteConnectionManager connectionManager;

    public SqliteMigrationService(SqliteConnectionManager connectionManager) {
        this.connectionManager = connectionManager;
    }

    /**
     * 执行数据库迁移——所有DDL使用IF NOT EXISTS保证幂等性。
     * 失败时抛出RuntimeException阻止应用启动（fail-fast）。
     */
    public void migrate() {
        try (Connection conn = connectionManager.getConnection();
             Statement stmt = conn.createStatement()) {

            // 启用WAL模式，提升并发读写性能
            stmt.execute("PRAGMA journal_mode=WAL");
            // 启用外键约束（SQLite默认关闭）
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
                    allow_agents       TEXT DEFAULT '["*"]',
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
                    name                TEXT DEFAULT '',
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
