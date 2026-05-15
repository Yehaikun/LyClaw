-- ============================================================
-- LyClaw 数据库初始化脚本
-- 目标数据库: MySQL 8.0+
-- 数据库名: lyclaw
-- 引擎: InnoDB / 字符集: utf8mb4
-- 版本: v2.0 (登录 + 会话管理)
-- 用法: mysql -u root -p < init_lyclaw.sql
-- ============================================================

-- ----------------------------------------------------------
-- 1. 建库
-- ----------------------------------------------------------
CREATE DATABASE IF NOT EXISTS lyclaw
    DEFAULT CHARACTER SET utf8mb4
    DEFAULT COLLATE utf8mb4_unicode_ci;

USE lyclaw;

-- ----------------------------------------------------------
-- 2. 建表
-- ----------------------------------------------------------

-- ============================================================
-- 2.1 用户表 (lyclaw_users)
--
-- ID格式: "u_" + ULID(26位)，应用层生成
-- 密码: BCrypt(strength=12)，60字符
-- 状态: ACTIVE → DISABLED / DELETED
-- 角色: JSON数组，如 '["ROLE_USER"]'，默认普通用户
-- 偏好: JSON对象，如 '{"theme":"dark","language":"zh"}'
-- 锁定: failed_login_attempts >= 5 且 locked_until > NOW() 时锁定
-- 软删: deleted_at 不为NULL，7天后永久删除
-- ============================================================
CREATE TABLE IF NOT EXISTS lyclaw_users (
    id                       VARCHAR(32)   NOT NULL PRIMARY KEY      COMMENT '主键，u_+ULID(26位)',
    username                 VARCHAR(50)   NOT NULL                  COMMENT '登录用户名',
    email                    VARCHAR(255)  NOT NULL                  COMMENT '邮箱',
    password_hash            VARCHAR(255)  NOT NULL                  COMMENT 'BCrypt(strength=12)',
    display_name             VARCHAR(100)  DEFAULT NULL              COMMENT '显示名称',
    avatar_url               VARCHAR(500)  DEFAULT NULL              COMMENT '头像URL',
    bio                      TEXT          DEFAULT NULL              COMMENT '个人简介',
    status                   VARCHAR(20)   NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE/DISABLED/DELETED',
    email_verified           TINYINT(1)    NOT NULL DEFAULT 0       COMMENT '邮箱是否已验证',
    failed_login_attempts    INT           NOT NULL DEFAULT 0       COMMENT '连续登录失败次数',
    locked_until             TIMESTAMP     NULL DEFAULT NULL         COMMENT '账户锁定截止时间',
    roles                    JSON          NOT NULL                  COMMENT '角色列表JSON数组',
    preferences              JSON          NOT NULL                  COMMENT '用户偏好JSON对象',
    privacy_consent_version  VARCHAR(20)   DEFAULT NULL              COMMENT '同意的隐私协议版本',
    privacy_consented_at     TIMESTAMP     NULL DEFAULT NULL         COMMENT '同意隐私协议的时间',
    created_at               TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at               TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted_at               TIMESTAMP     NULL DEFAULT NULL         COMMENT '软删除时间',
    deletion_scheduled_at    TIMESTAMP     NULL DEFAULT NULL         COMMENT '7天后永久删除',

    UNIQUE  KEY uq_users_username (username),
    UNIQUE  KEY uq_users_email    (email),
    INDEX   idx_users_status      (status),
    INDEX   idx_users_created     (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户账户核心表';


-- ============================================================
-- 2.2 会话表 (lyclaw_sessions)
--
-- 会话ID: "s_" + ULID(26位)
-- session_uid: UUID，对外暴露，防遍历
-- user_id: 归属用户
-- 消息数/Token数: 反范式字段，避免每次列表查询都JOIN消息表
-- 标签: JSON数组
-- 归档: 软标记，前端可切换显示
-- ============================================================
CREATE TABLE IF NOT EXISTS lyclaw_sessions (
    id             VARCHAR(32)   NOT NULL PRIMARY KEY                COMMENT '主键 s_+ULID',
    session_uid    VARCHAR(64)   NOT NULL                           COMMENT '对外暴露的会话ID(UUID)',
    user_id        VARCHAR(32)   NOT NULL                           COMMENT '归属用户ID',
    name           VARCHAR(200)  NOT NULL DEFAULT 'New Chat'        COMMENT '会话名称',
    model_provider VARCHAR(50)   DEFAULT NULL                       COMMENT '模型提供商(如deepseek)',
    model_name     VARCHAR(50)   DEFAULT NULL                       COMMENT '模型名称(如deepseek-chat)',
    message_count  INT           NOT NULL DEFAULT 0                COMMENT '消息数量(反范式缓存)',
    total_tokens   BIGINT        NOT NULL DEFAULT 0                COMMENT '累计Token消耗',
    tags           JSON          DEFAULT NULL                       COMMENT '标签JSON数组',
    archived       TINYINT(1)    NOT NULL DEFAULT 0                COMMENT '是否归档',
    metadata       JSON          DEFAULT NULL                       COMMENT '扩展元数据',
    created_at     TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at     TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

    UNIQUE  KEY uq_sessions_uid           (session_uid),
    INDEX   idx_sessions_user             (user_id),
    INDEX   idx_sessions_user_updated     (user_id, updated_at DESC),
    INDEX   idx_sessions_user_archived    (user_id, archived),

    CONSTRAINT fk_sessions_user FOREIGN KEY (user_id)
        REFERENCES lyclaw_users(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='聊天会话表';


-- ============================================================
-- 2.3 登录历史表 (lyclaw_login_history)
--
-- 记录所有登录尝试（成功与失败）
-- 用途: 安全审计、异地登录检测、暴力破解告警
-- 外键: ON DELETE SET NULL → 用户删除后日志保留
-- 保留策略: 定期清理90天前的记录
-- ============================================================
CREATE TABLE IF NOT EXISTS lyclaw_login_history (
    id             BIGINT        NOT NULL AUTO_INCREMENT PRIMARY KEY,
    user_id        VARCHAR(32)   DEFAULT NULL                        COMMENT '关联用户(登录失败时可能为NULL)',
    success        TINYINT(1)    NOT NULL                           COMMENT '是否登录成功',
    failure_reason VARCHAR(100)  DEFAULT NULL                       COMMENT '失败原因: BAD_CREDENTIALS/ACCOUNT_LOCKED/ACCOUNT_DISABLED',
    ip_address     VARCHAR(50)   DEFAULT NULL                       COMMENT '客户端IP',
    user_agent     VARCHAR(500)  DEFAULT NULL                       COMMENT 'User-Agent',
    device_info    VARCHAR(500)  DEFAULT NULL                       COMMENT '解析后的设备信息',
    created_at     TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,

    INDEX   idx_lh_user    (user_id, created_at),
    INDEX   idx_lh_ip      (ip_address, created_at),
    INDEX   idx_lh_created (created_at),

    CONSTRAINT fk_lh_user FOREIGN KEY (user_id)
        REFERENCES lyclaw_users(id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='登录审计日志(保留90天)';


-- ============================================================
-- 2.4 用户配置表 (lyclaw_user_configs)
--
-- 存储用户的个性化配置
-- API Key类敏感值使用 AES-256-GCM 加密存储
-- 唯一约束 (user_id, config_key) 确保每用户每配置项只有一条
-- 配置优先级: 用户配置 > 系统配置 > application.yml > 框架默认
-- ============================================================
CREATE TABLE IF NOT EXISTS lyclaw_user_configs (
    id           BIGINT        NOT NULL AUTO_INCREMENT PRIMARY KEY,
    user_id      VARCHAR(32)   NOT NULL                             COMMENT '归属用户',
    config_key   VARCHAR(255)  NOT NULL                             COMMENT '配置键(如 lyclaw.chat.default-model)',
    config_value TEXT          NOT NULL                             COMMENT '配置值(敏感值AES加密)',
    created_at   TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at   TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

    UNIQUE  KEY uq_uc_user_key (user_id, config_key),

    CONSTRAINT fk_uc_user FOREIGN KEY (user_id)
        REFERENCES lyclaw_users(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户配置表(含加密API Key)';


-- ============================================================
-- 2.5 系统配置表 (lyclaw_system_configs)
--
-- 系统级全局配置，管理员通过后台修改
-- config_value使用JSON类型，支持复杂结构
-- 例如: {"default_model": "deepseek-chat", "max_tokens": 4096}
-- ============================================================
CREATE TABLE IF NOT EXISTS lyclaw_system_configs (
    config_key   VARCHAR(200)  NOT NULL PRIMARY KEY                 COMMENT '配置键',
    config_value JSON          NOT NULL                             COMMENT '配置值JSON',
    description  TEXT          DEFAULT NULL                         COMMENT '配置说明',
    updated_by   VARCHAR(32)   DEFAULT NULL                         COMMENT '修改人(管理员ID)',
    updated_at   TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

    CONSTRAINT fk_sc_updated_by FOREIGN KEY (updated_by)
        REFERENCES lyclaw_users(id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='系统级配置表';


-- ============================================================
-- 3. 初始数据
-- ============================================================

-- 系统默认配置
INSERT INTO lyclaw_system_configs (config_key, config_value, description) VALUES
('lyclaw.system.default-model',       '{"provider":"deepseek","model":"deepseek-chat"}', '系统默认模型'),
('lyclaw.system.max-sessions',        '{"value":100}',      '每用户最大会话数'),
('lyclaw.system.require-email-verify', '{"value":true}',    '注册是否需要邮箱验证'),
('lyclaw.system.rate-limit',          '{"api_per_min":60,"login_per_min":10}', '限流配置');
