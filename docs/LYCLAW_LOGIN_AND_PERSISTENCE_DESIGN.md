# LyClaw 登录与数据持久化系统设计

> 版本：v2.0
> 日期：2026-05-14
> 状态：详细设计阶段
> 新增微服务：1 个（lyclaw-auth）
> 新增库模块：1 个（lyclaw-storage-jdbc）
> 新增中间件：Redis 7.x（令牌缓存 + 限流 + 邮箱验证）
> 数据库：MySQL 8.0（用户数据 + 会话 + 审计日志）

---

## 目录

1. [整体架构](#1-整体架构)
2. [注册与登录流程](#2-注册与登录流程)
3. [令牌设计：JWT + 双Token + Redis](#3-令牌设计jwt--双token--redis)
4. [会话管理设计](#4-会话管理设计)
5. [数据库设计（MySQL 8.0）](#5-数据库设计mysql-80)
6. [Redis 键设计](#6-redis-键设计)
7. [API 接口设计](#7-api接口设计)
8. [高并发架构论证](#8-高并发架构论证)
9. [安全设计](#9-安全设计)
10. [部署与迁移](#10-部署与迁移)

---

## 1. 整体架构

### 1.1 改造前（当前状态）

```
用户 → lyclaw-gateway :8080
         ├── /api/chat/stream    → lyclaw-orchestration  (聊天+ReAct循环)
         ├── /api/sessions/**    → lyclaw-orchestration  (会话CRUD)
         ├── /api/memory/**      → lyclaw-memory         (记忆系统)
         ├── /api/plan/**        → lyclaw-plan           (任务规划)
         ├── /api/action/**      → lyclaw-action         (工具执行)
         ├── /api/reflect/**     → lyclaw-reflect        (反思质评)
         └── /api/protocol/**    → lyclaw-protocol       (MCP/A2A协议)

存储层: FileBackend (JSON文件) + InMemoryBackend
认证:   无（任何人均可访问）
会话:   JSON文件，无用户隔离
```

### 1.2 改造后

```
                              ┌─────────────────────┐
                              │    Nacos 服务发现     │
                              │    127.0.0.1:8848    │
                              └──────────┬──────────┘
                                         │ 注册/发现
用户 ───▶ lyclaw-gateway :8080 ──────────┼──────────────
              │                          │
              ├── /api/auth/**     ──▶ lyclaw-auth :8087  (新增)
              ├── /api/user/**     ──▶ lyclaw-auth :8087
              ├── /api/sessions/** ──▶ lyclaw-auth :8087
              ├── /api/chat/**     ──▶ lyclaw-orchestration :8081
              ├── /api/memory/**   ──▶ lyclaw-memory :8082
              ├── /api/plan/**     ──▶ lyclaw-plan :8083
              ├── /api/action/**   ──▶ lyclaw-action :8084
              ├── /api/reflect/**  ──▶ lyclaw-reflect :8085
              └── /api/protocol/** ──▶ lyclaw-protocol :8086
                                            │
              lyclaw-auth ──────────────────┤
              │                             │
              ├──▶ MySQL 8.0  :3306         │
              │    └── lyclaw 库             │
              │        ├── lyclaw_users          用户表
              │        ├── lyclaw_sessions        会话表
              │        ├── lyclaw_login_history   登录审计
              │        ├── lyclaw_user_configs    用户配置
              │        └── lyclaw_system_configs  系统配置
              │
              └──▶ Redis 7.x  :6379
                   ├── refresh tokens      刷新令牌(热路径)
                   ├── email_verify        邮箱验证令牌
                   ├── login_attempts      登录失败计数
                   └── rate_limit          接口限流计数器
```

### 1.3 请求流转总览

```
┌─────────────────────────────────────────────────────────────────────────┐
│                        用户第一次使用 LyClaw                              │
│                                                                          │
│  ① 注册 POST /api/auth/register                                          │
│     └─▶ Gateway → lyclaw-auth                                            │
│         ├─ 校验用户名/邮箱唯一性 → MySQL                                  │
│         ├─ BCrypt 加密密码                                                │
│         ├─ 写入 lyclaw_users (status=ACTIVE, email_verified=false)       │
│         ├─ 生成邮箱验证令牌 → Redis (TTL=24h)                             │
│         └─ 发送验证邮件 (spring-boot-starter-mail)                        │
│                                                                          │
│  ② 验证邮箱 GET /api/auth/verify-email?token=xxx                          │
│     └─▶ Gateway → lyclaw-auth                                            │
│         ├─ 查询 Redis → 找到令牌                                          │
│         ├─ 更新 lyclaw_users.email_verified = true → MySQL               │
│         └─ 删除 Redis 令牌                                                │
│                                                                          │
│  ③ 登录 POST /api/auth/login                                             │
│     └─▶ Gateway → lyclaw-auth                                            │
│         ├─ 检查 login_attempts → Redis (≥5 → 429)                       │
│         ├─ 查询用户 → MySQL lyclaw_users                                  │
│         ├─ BCrypt 密码比对                                                │
│         ├─ 生成 Access Token (JWT, 30min)                                │
│         ├─ 生成 Refresh Token (随机256位)                                  │
│         ├─ 存储 Refresh Token → Redis (TTL=7d)                           │
│         ├─ 记录登录历史 → MySQL lyclaw_login_history                     │
│         └─ 返回 {accessToken, refreshToken, expiresIn, user}             │
│                                                                          │
│  ④ 配置 API Key POST /api/user/model-config                              │
│     └─▶ Gateway → lyclaw-auth (需 Bearer JWT)                           │
│         ├─ JwtAuthWebFilter 验证 JWT 签名 + 有效期 (无I/O)                │
│         ├─ 提取 userId → Reactor Context                                 │
│         ├─ AES-256-GCM 加密用户 API Key                                    │
│         └─ 写入 lyclaw_user_configs (config_key, AES加密后的值)           │
│                                                                          │
│  ⑤ 开始聊天 POST /api/chat/stream                                        │
│     └─▶ Gateway → lyclaw-orchestration (需 Bearer JWT)                  │
│         ├─ JwtAuthWebFilter (在Gateway层) 验证JWT → 提取userId            │
│         ├─ X-User-Id header 传给 orchestration                           │
│         └─ 聊天流程不变（6阶段管道 + ReAct循环）                           │
│                                                                          │
│  ⑥ Token 过期 → 用 Refresh Token 换取新的 Token Pair                      │
│     POST /api/auth/refresh                                               │
│     └─▶ Gateway → lyclaw-auth                                            │
│         ├─ SHA-256(refreshToken) → 查 Redis                              │
│         ├─ 找到 → 删除旧Token → 生成新Token对 (Token Rotation)            │
│         ├─ 找不到/已过期 → 401，前端跳转登录页                             │
│         └─ 检测到已吊销Token被使用 → 吊销该用户全部Token（盗用检测）        │
└─────────────────────────────────────────────────────────────────────────┘
```

---

## 2. 注册与登录流程

### 2.1 注册流程

```
前端                      Gateway                  lyclaw-auth              MySQL          Redis
 │                          │                          │                      │              │
 │  POST /api/auth/register │                          │                      │              │
 │  {username,email,        │                          │                      │              │
 │   password,displayName}  │                          │                      │              │
 │─────────────────────────▶│─────────────────────────▶│                      │              │
 │                          │                          │                      │              │
 │                          │                          │ ① 参数校验            │              │
 │                          │                          │   - username 3-50字符 │              │
 │                          │                          │   - email RFC 5322   │              │
 │                          │                          │   - password 8+字符   │              │
 │                          │                          │   含大小写+数字+特殊   │              │
 │                          │                          │                      │              │
 │                          │                          │ ② 检查用户名/邮箱     │              │
 │                          │                          │──────────────────────▶│              │
 │                          │                          │◀──────────────────────│              │
 │                          │                          │  唯一性检查(UNIQUE)    │              │
 │                          │                          │                      │              │
 │                          │                          │ ③ 生成 userId        │              │
 │                          │                          │   "u_" + ULID.random()│              │
 │                          │                          │                      │              │
 │                          │                          │ ④ BCrypt(password,12) │              │
 │                          │                          │   → password_hash     │              │
 │                          │                          │                      │              │
 │                          │                          │ ⑤ INSERT             │              │
 │                          │                          │──────────────────────▶│              │
 │                          │                          │◀──────────────────────│              │
 │                          │                          │                      │              │
 │                          │                          │ ⑥ 生成验证令牌(UUID)  │              │
 │                          │                          │──────────────────────────────────▶│
 │                          │                          │◀──────────────────────────────────│
 │                          │                          │   email_verify:{token}  TTL=24h  │
 │                          │                          │                      │              │
 │                          │                          │ ⑦ sendVerificationEmail│              │
 │                          │                          │   (Mono.fromCallable   │              │
 │                          │                          │    + boundedElastic)   │              │
 │                          │                          │                      │              │
 │                          │◀─────────────────────────│                      │              │
 │◀─────────────────────────│                          │                      │              │
 │  200 {code:0,            │                          │                      │              │
 │  message:"注册成功，       │                          │                      │              │
 │  请查收验证邮件"}           │                          │                      │              │
```

**状态机：**

```
注册 → ACTIVE(email_verified=false)
         │
         ├── 点击验证邮件 → ACTIVE(email_verified=true)  ← 正常状态
         │
         ├── 5次登录失败 → ACTIVE(locked_until=+15min)   ← 临时锁定
         │     └── 15分钟后自动解锁
         │
         ├── 管理员禁用 → DISABLED                        ← 无法登录
         │
         └── 用户删除 → DELETED(deleted_at=NOW())
               └── 7天后永久删除(deletion_scheduled_at)
```

### 2.2 登录流程

```
前端                      Gateway                  lyclaw-auth              MySQL          Redis
 │                          │                          │                      │              │
 │  POST /api/auth/login    │                          │                      │              │
 │  {username, password}    │                          │                      │              │
 │─────────────────────────▶│─────────────────────────▶│                      │              │
 │                          │                          │                      │              │
 │                          │                          │ ① 检查登录尝试次数     │              │
 │                          │                          │──────────────────────────────────▶│
 │                          │                          │◀──────────────────────────────────│
 │                          │                          │   login_attempts:{username}       │
 │                          │                          │   如果 ≥ 5 → 返回 429              │
 │                          │                          │                      │              │
 │                          │                          │ ② 查用户              │              │
 │                          │                          │──────────────────────▶│              │
 │                          │                          │◀──────────────────────│              │
 │                          │                          │   SELECT * FROM       │              │
 │                          │                          │   lyclaw_users         │              │
 │                          │                          │   WHERE username=?     │              │
 │                          │                          │                      │              │
 │                          │                          │ ③ 状态检查            │              │
 │                          │                          │   DISABLED → 403      │              │
 │                          │                          │   DELETED  → 403      │              │
 │                          │                          │   locked_until>now→423 │              │
 │                          │                          │                      │              │
 │                          │                          │ ④ BCrypt.matches       │              │
 │                          │                          │   (password, hash)     │              │
 │                          │                          │   失败 → 记录+返回401  │              │
 │                          │                          │                      │              │
 │                          │                          │ ⑤ 密码正确             │              │
 │                          │                          │   - 重置失败次数       │              │
 │                          │                          │──────────────────────▶│              │
 │                          │                          │   UPDATE SET           │              │
 │                          │                          │   failed_login_attempts│              │
 │                          │                          │   =0, locked_until=NULL│              │
 │                          │                          │                      │              │
 │                          │                          │ ⑥ 生成 Token Pair     │              │
 │                          │                          │   Access: JWT(HS256)  │              │
 │                          │                          │   Refresh: 256位随机   │              │
 │                          │                          │                      │              │
 │                          │                          │ ⑦ 存 Refresh Token   │              │
 │                          │                          │──────────────────────────────────▶│
 │                          │                          │   refresh:{sha256(token)}         │
 │                          │                          │   user_tokens:{userId}            │
 │                          │                          │   TTL=7d                         │
 │                          │                          │                      │              │
 │                          │                          │ ⑧ 记录登录历史         │              │
 │                          │                          │──────────────────────▶│              │
 │                          │                          │   INSERT login_history│              │
 │                          │                          │                      │              │
 │                          │                          │ ⑨ 删除 login_attempts │              │
 │                          │                          │──────────────────────────────────▶│
 │                          │                          │   DEL login_attempts:{username}   │
 │                          │                          │                      │              │
 │                          │◀─────────────────────────│                      │              │
 │◀─────────────────────────│                          │                      │              │
 │  200 {                   │                          │                      │              │
 │    code:0,               │                          │                      │              │
 │    data: {               │                          │                      │              │
 │      accessToken:"eyJ...",                          │                      │              │
 │      refreshToken:"a1b2...",                        │                      │              │
 │      expiresIn:1800,      // 30分钟                  │                      │              │
 │      tokenType:"Bearer",  │                          │                      │              │
 │      user: {              │                          │                      │              │
 │        userId, username,  │                          │                      │              │
 │        email, displayName,│                          │                      │              │
 │        avatarUrl, roles   │                          │                      │              │
 │      }                    │                          │                      │              │
 │    }                      │                          │                      │              │
 │  }                        │                          │                      │              │
```

**登录失败原因枚举：**

| failure_reason | HTTP | 说明 |
|---|---|---|
| BAD_CREDENTIALS | 401 | 用户名或密码错误 |
| ACCOUNT_LOCKED | 423 | 账户被临时锁定（5次失败） |
| ACCOUNT_DISABLED | 403 | 管理员禁用账户 |
| ACCOUNT_DELETED | 403 | 账户已删除 |
| EMAIL_NOT_VERIFIED | 403 | 需先验证邮箱（可配置开关） |

---

## 3. 令牌设计：JWT + 双Token + Redis

### 3.1 为什么不用数据库存令牌

| 维度 | MySQL 存储 | Redis 存储 |
|------|-----------|-----------|
| 每次请求查令牌 | 需要，~1-5ms | AccessToken免查(无状态JWT)，RefreshToken查Redis ~0.1ms |
| 10000并发查令牌 | 连接池耗尽风险 | Redis单线程，轻松10万QPS |
| 过期清理 | 定时任务扫表DELETE | 内置TTL，零运维 |
| 限流计数器 | 需要额外表+写入竞争 | INCR原子操作，天然适合 |
| 扩展性 | 单点，需额外做读写分离 | Redis Cluster分区，线性扩展 |

结论：**AccessToken 用无状态 JWT（零 I/O），RefreshToken 用 Redis（0.1ms）**。

### 3.2 Access Token（JWT）

```
Header:
{
  "alg": "HS256",
  "typ": "JWT"
}

Payload:
{
  "sub": "u_01JQZXXXX",          // 用户ID
  "username": "alice",
  "roles": ["ROLE_USER"],
  "iat": 1715716800,             // 签发时间
  "exp": 1715718600,             // 过期时间 = iat + 1800s (30分钟)
  "iss": "lyclaw",               // 签发者
  "jti": "uuid-random"           // JWT唯一ID (用于黑名单)
}

签名: HMAC-SHA256(base64UrlEncode(header) + "." + base64UrlEncode(payload), secret)
```

**安全特性：**
- 签名密钥：256位随机字符串，通过环境变量 `LYCLAW_JWT_SECRET` 注入
- 过期时间：30分钟（可配置 `lyclaw.auth.jwt.access-token-expiration`）
- 不存数据库/Redis → 每次请求零 I/O 验证
- 撤销策略：短过期时间 + 前端定时刷新。紧急撤销通过 JWT jti 黑名单（Redis Set, TTL=Token剩余有效期）

### 3.3 Refresh Token

```
生成: SecureRandom 生成 256 位 (32 bytes) → Hex 编码为 64 字符字符串
存储: Redis Key = "refresh:{sha256(token)}"
值: Hash {
    user_id
    username
    roles (JSON)
    device_info
    created_at
}
TTL: 604800s (7天，与 Refresh Token 有效期一致)
```

**为什么用 Hash 存储：**
- Redis Hash 存用户信息后，Refresh 时不需要再查 MySQL → 少一次 DB 查询
- TTL 等于 Token 有效期，过期自动清理

**Token Rotation（令牌轮换）：**

```
每次使用 Refresh Token 换取新 Token Pair：
  1. SHA256(旧RefreshToken) → 查 Redis
  2. 找到 → 立即删除旧Token
  3. 生成新的 Access Token + Refresh Token
  4. 新 Refresh Token 写入 Redis
  5. 返回新 Token Pair

如果 旧Token 已过期/不存在 → 删除失败，前端跳转登录
```

**Refresh Token 盗用检测：**

```
如果攻击者偷走你的 Refresh Token：
  → 你正常刷新 → 旧Token被删除、新TokenA下发
  → 攻击者也拿旧Token刷新 → Redis中找不到 → 401
  → 同时检测到该用户有合法Token被"竞争刷新"
  → 可选策略：吊销该用户所有Token（全设备下线）
```

实现检测：刷新时检查 `user_tokens:{userId}` Set 中是否有"未知"的Token（不在当前请求链中的Token有竞争刷新嫌疑）。

### 3.4 前端集成

```
前端拦截器逻辑：

请求前:
  if (accessToken 还有 > 5分钟) {
    直接带 Authorization: Bearer {accessToken}
  } else if (accessToken 即将过期 || 已过期) {
    if (正在刷新中) {
      等待刷新完成，拿到新Token
    } else {
      用 refreshToken 调 POST /api/auth/refresh
      拿到新 Token Pair → 存 localStorage
    }
  }

请求收到 401:
  清空 Token → 跳转登录页
```

---

## 4. 会话管理设计

### 4.1 会话模型

```java
// 当前 Session (lyclaw-framework)
public class Session {
    String sessionId;       // "s_01JQ..." (对外ID)
    String name;            // "与DeepSeek讨论架构" (可重命名)
    String model;           // "deepseek-chat"
    List<Message> messages; // 消息列表
}

// 改造后新增字段
public class Session {
    String sessionId;       // 不变
    String userId;          // 新增: 归属用户
    String name;            // 不变
    String modelProvider;   // 新增: 模型提供商
    String modelName;       // 重命名(model→modelName)，更精确
    Integer messageCount;   // 新增: 消息数(反范式)
    Long totalTokens;       // 新增: Token消耗(反范式)
    List<String> tags;      // 新增: 标签
    Boolean archived;       // 新增: 归档
    Map<String,Object> metadata; // 新增: 扩展元数据
    LocalDateTime createdAt;
    LocalDateTime updatedAt;
}
```

### 4.2 会话生命周期

```
创建:
  前端点击"新建聊天" → POST /api/sessions {name, modelProvider, modelName}
  → lyclaw-auth 生成 session_uid → 写入 lyclaw_sessions (userId关联)
  → 返回 session → 前端开始对话

对话中:
  每次 POST /api/chat/stream {sessionId, messages}
  → 前端带 sessionId + accessToken
  → 后端不需要重新创建 session (仅更新 messageCount/totalTokens)

重命名:
  PATCH /api/sessions/{sessionId}/name {name}

归档:
  PATCH /api/sessions/{sessionId}/archive

删除:
  DELETE /api/sessions/{sessionId}
  → 软删除或硬删除（看用户设置）

列出:
  GET /api/sessions?page=1&size=20&search=关键词
  → 按 updated_at DESC 分页
  → 支持按名称搜索
```

### 4.3 会话数据隔离

```
用户A的会话: user_id = "u_A"
用户B的会话: user_id = "u_B"

所有会话查询强制带 WHERE user_id = ? (从JWT提取)
用户A无法看到用户B的会话。

查询示例:
  SELECT * FROM lyclaw_sessions
  WHERE user_id = ?           -- ← 强制过滤
    AND (name LIKE ? OR ? IS NULL)
  ORDER BY updated_at DESC
  LIMIT ? OFFSET ?
```

---

## 5. 数据库设计（MySQL 8.0）

### 5.1 表总览

| 表名 | 用途 | 预估读写比 | 预估数据量 |
|------|------|-----------|-----------|
| lyclaw_users | 用户账户 | 1:100 | N (用户数) |
| lyclaw_sessions | 聊天会话 | 1:10 | N × 50 (人均50会话) |
| lyclaw_login_history | 登录审计 | 1:0 (只写少查) | N × 200 (人均200次登录) |
| lyclaw_user_configs | 用户配置 | 1:50 | N × 10 (人均10项配置) |
| lyclaw_system_configs | 系统配置 | 1:1000 | ~50 行 |

### 5.2 建表语句

```sql
-- ============================================================
-- LyClaw 数据库初始化
-- MySQL 8.0+ / InnoDB / utf8mb4
-- ============================================================

CREATE DATABASE IF NOT EXISTS lyclaw
    DEFAULT CHARACTER SET utf8mb4
    DEFAULT COLLATE utf8mb4_unicode_ci;

USE lyclaw;

-- ----------------------------------------------------------
-- 5.2.1 用户表
-- ----------------------------------------------------------
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
    roles                    JSON          NOT NULL                  COMMENT '角色列表JSON数组 ["ROLE_USER"]',
    preferences              JSON          NOT NULL                  COMMENT '用户偏好JSON对象 {}',
    privacy_consent_version  VARCHAR(20)   DEFAULT NULL              COMMENT '同意的隐私协议版本',
    privacy_consented_at     TIMESTAMP     NULL DEFAULT NULL         COMMENT '同意隐私协议时间',
    created_at               TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at               TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted_at               TIMESTAMP     NULL DEFAULT NULL         COMMENT '软删除时间',
    deletion_scheduled_at    TIMESTAMP     NULL DEFAULT NULL         COMMENT '7天后永久删除',

    UNIQUE  KEY uq_users_username (username),
    UNIQUE  KEY uq_users_email    (email),
    INDEX   idx_users_status      (status),
    INDEX   idx_users_created     (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户账户核心表';


-- ----------------------------------------------------------
-- 5.2.2 会话表
-- ----------------------------------------------------------
CREATE TABLE IF NOT EXISTS lyclaw_sessions (
    id             VARCHAR(32)   NOT NULL PRIMARY KEY                COMMENT '主键 s_+ULID',
    session_uid    VARCHAR(64)   NOT NULL                           COMMENT '对外暴露的会话ID(UUID)',
    user_id        VARCHAR(32)   NOT NULL                           COMMENT '归属用户ID',
    name           VARCHAR(200)  NOT NULL DEFAULT 'New Chat'        COMMENT '会话名称',
    model_provider VARCHAR(50)   DEFAULT NULL                       COMMENT '模型提供商',
    model_name     VARCHAR(50)   DEFAULT NULL                       COMMENT '模型名称',
    message_count  INT           NOT NULL DEFAULT 0                COMMENT '消息数量(反范式)',
    total_tokens   BIGINT        NOT NULL DEFAULT 0                COMMENT '累计Token消耗',
    tags           JSON          DEFAULT NULL                       COMMENT '标签 JSON数组',
    archived       TINYINT(1)    NOT NULL DEFAULT 0                COMMENT '是否归档',
    metadata       JSON          DEFAULT NULL                       COMMENT '扩展元数据',
    created_at     TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at     TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

    UNIQUE  KEY uq_sessions_uid      (session_uid),
    INDEX   idx_sessions_user        (user_id),
    INDEX   idx_sessions_user_updated (user_id, updated_at DESC),
    INDEX   idx_sessions_user_archived (user_id, archived),

    CONSTRAINT fk_sessions_user FOREIGN KEY (user_id)
        REFERENCES lyclaw_users(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='聊天会话表';


-- ----------------------------------------------------------
-- 5.2.3 登录历史表
-- ----------------------------------------------------------
CREATE TABLE IF NOT EXISTS lyclaw_login_history (
    id             BIGINT        NOT NULL AUTO_INCREMENT PRIMARY KEY,
    user_id        VARCHAR(32)   DEFAULT NULL                        COMMENT '关联用户(失败时可能为NULL)',
    success        TINYINT(1)    NOT NULL                           COMMENT '是否成功',
    failure_reason VARCHAR(100)  DEFAULT NULL                       COMMENT '失败原因枚举',
    ip_address     VARCHAR(50)   DEFAULT NULL                       COMMENT '客户端IP',
    user_agent     VARCHAR(500)  DEFAULT NULL                       COMMENT 'User-Agent',
    device_info    VARCHAR(500)  DEFAULT NULL                       COMMENT '解析后设备信息',
    created_at     TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,

    INDEX   idx_lh_user    (user_id, created_at),
    INDEX   idx_lh_ip      (ip_address, created_at),
    INDEX   idx_lh_created  (created_at),

    CONSTRAINT fk_lh_user FOREIGN KEY (user_id)
        REFERENCES lyclaw_users(id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='登录审计日志';

-- 定期清理: 保留90天
-- DELETE FROM lyclaw_login_history WHERE created_at < DATE_SUB(NOW(), INTERVAL 90 DAY);


-- ----------------------------------------------------------
-- 5.2.4 用户配置表
-- ----------------------------------------------------------
CREATE TABLE IF NOT EXISTS lyclaw_user_configs (
    id           BIGINT        NOT NULL AUTO_INCREMENT PRIMARY KEY,
    user_id      VARCHAR(32)   NOT NULL                             COMMENT '归属用户',
    config_key   VARCHAR(255)  NOT NULL                             COMMENT '配置键',
    config_value TEXT          NOT NULL                             COMMENT '配置值(含AES加密的API Key)',
    created_at   TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at   TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

    UNIQUE  KEY uq_uc_user_key (user_id, config_key),

    CONSTRAINT fk_uc_user FOREIGN KEY (user_id)
        REFERENCES lyclaw_users(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户配置表(含加密API Key)';


-- ----------------------------------------------------------
-- 5.2.5 系统配置表
-- ----------------------------------------------------------
CREATE TABLE IF NOT EXISTS lyclaw_system_configs (
    config_key   VARCHAR(200)  NOT NULL PRIMARY KEY                 COMMENT '配置键',
    config_value JSON          NOT NULL                             COMMENT '配置值JSON',
    description  TEXT          DEFAULT NULL                         COMMENT '说明',
    updated_by   VARCHAR(32)   DEFAULT NULL                         COMMENT '修改人(管理员ID)',
    updated_at   TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

    CONSTRAINT fk_sc_updated_by FOREIGN KEY (updated_by)
        REFERENCES lyclaw_users(id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='系统级配置表';
```

### 5.3 ER 关系图

```
lyclaw_users (1) ──────────< (N) lyclaw_sessions
      │
      ├── (1) ──────────< (N) lyclaw_login_history
      ├── (1) ──────────< (N) lyclaw_user_configs
      │
      └── (1) ──────────── (1) lyclaw_system_configs.updated_by (可为NULL)

无外键关联:
  Redis (refresh tokens, email verify, rate limits, login attempts)
```

### 5.4 索引设计依据

| 索引 | 覆盖查询 |
|------|---------|
| `uq_users_username` | 登录时 `WHERE username=?` |
| `uq_users_email` | 注册时检查邮箱唯一性 |
| `idx_users_status` | 管理员查用户列表 `WHERE status='ACTIVE'` |
| `uq_sessions_uid` | 前端通过 session_uid 查会话 |
| `idx_sessions_user` | 用户的所有会话 `WHERE user_id=?` |
| `idx_sessions_user_updated` | 会话列表按更新时间倒序（覆盖索引） |
| `idx_lh_user` | 用户查看自己的登录历史 |
| `idx_lh_ip` | 安全审计：同IP多次登录失败 |
| `uq_uc_user_key` | `WHERE user_id=? AND config_key=?` (唯一约束即索引) |

**索引原则：**
- 唯一约束（UNIQUE KEY）天然是索引，不用重复建
- 联合索引左前缀原则：`(user_id, updated_at DESC)` 同时覆盖"按用户查所有会话"和"按用户查最近会话"
- 登录历史表只保留90天，定期清理避免膨胀

---

## 6. Redis 键设计

### 6.1 键空间规划

```
lyclaw:{env}:                         ← 环境前缀(dev/test/prod)，防止多环境冲突

lyclaw:prod:refresh:{sha256(token)}   ← Refresh Token
lyclaw:prod:user_tokens:{userId}       ← 用户活跃Token集合
lyclaw:prod:jwt_blacklist:{jti}       ← JWT黑名单(紧急撤销用)
lyclaw:prod:email_verify:{tokenId}    ← 邮箱验证令牌
lyclaw:prod:login_attempts:{key}       ← 登录失败计数(key=username或ip)
lyclaw:prod:rate:api:{userId}:{window} ← API调用限流
lyclaw:prod:rate:login:{ip}:{window}  ← 登录接口限流
lyclaw:prod:session_cache:{userId}:{sid} ← 会话缓存(可选)
```

### 6.2 详细设计

```yaml
# ─── Refresh Token ───
键:  lyclaw:prod:refresh:{sha256(token)}
类型: Hash
字段:
  user_id:    "u_01JQZXXXX"
  username:   "alice"
  roles:      '["ROLE_USER"]'
  device_info: "Mozilla/5.0 ... Chrome/125.0"
  created_at: "1715716800000"
TTL: 604800  (7天)
操作:
  - SET: 登录/刷新时写入 (HSET + EXPIRE)
  - GET: 刷新Token时查询 (HGETALL)
  - DEL: Token Rotation时删除旧Token


# ─── 用户活跃Token集合 ───
键:  lyclaw:prod:user_tokens:{userId}
类型: Set
成员: sha256(token1), sha256(token2), ...
TTL: 604800  (跟着最晚过期的Token)
操作:
  - SADD: 每次发新RefreshToken时添加
  - SREM: Token Rotation时移除旧Token
  - SMEMBERS: 管理员强制下线(全设备登出)时遍历删除
  - SCARD: 盗用检测(检查是否有竞争刷新)


# ─── JWT黑名单 (紧急撤销) ───
键:  lyclaw:prod:jwt_blacklist:{jti}
类型: String (值="1")
TTL: Token剩余有效期(最多30分钟)
操作:
  - SET: 管理员强制某用户下线时
  - GET: JwtAuthWebFilter验证后检查(极少触发)
说明: 正常情况不查黑名单，JWT短过期即可


# ─── 邮箱验证令牌 ───
键:  lyclaw:prod:email_verify:{tokenId}
类型: Hash
字段:
  user_id: "u_01JQZXXXX"
  email:   "alice@example.com"
  type:    "REGISTRATION"
  new_email: ""  (仅EMAIL_CHANGE时有值)
TTL: 86400  (24小时)
操作:
  - SET: 注册/换邮箱时生成
  - GET: 用户点击验证链接时查询
  - DEL: 验证完成后删除


# ─── 登录失败计数 ───
键:  lyclaw:prod:login_attempts:{username_or_ip}
类型: String
值:  整数 (失败次数)
TTL: 900  (15分钟后自动重置)
操作:
  - INCR: 登录失败时 +1
  - GET: 登录前检查是否 ≥5
  - DEL: 登录成功后删除
说明: 双重key——同时按 username 和 IP 计数，任一超限即拒绝


# ─── API调用限流 ───
键:  lyclaw:prod:rate:api:{userId}:{yyyyMMddHHmm}
类型: String
值:  整数 (该分钟内的调用次数)
TTL: 60  (1分钟窗口)
操作:
  - INCR: 每次API调用 +1
  - GET: 调用前检查是否超限(默认60次/分钟)
  首次INCR时自动设置TTL(Redis 7.0+ EXAT参数)

键:  lyclaw:prod:rate:login:{ip}:{yyyyMMddHHmm}
类型: String
值:  整数
TTL: 60
限制: 10次/分钟/IP


# ─── 会话缓存(可选，减少MySQL查询) ───
键:  lyclaw:prod:session_cache:{userId}:{sessionId}
类型: String (JSON)
TTL: 1800  (30分钟)
操作:
  - SET: 会话被访问后缓存
  - GET: 下次访问会话时先查缓存
  - DEL: 会话更新/删除时失效
说明: 可选优化，初期可不用。会话列表走MySQL即可
```

### 6.3 Redis 内存估算

```
假设: 10万注册用户，日均2000活跃用户

refresh token:    2000个 × 500B  = 1MB
user_tokens set:  2000个 × 200B  = 0.4MB
email_verify:     每天200注册 × 500B = 0.1MB
login_attempts:   峰值500个 × 50B = 0.025MB
rate_limit keys:  2000用户 × 60B × 1个窗口 = 0.12MB
session_cache:    2000用户 × 20会话 × 1KB = 40MB (可选)

总计: ~42MB (含session_cache) / ~2MB (不含)
结论: 一台 1GB Redis 轻松应对10万用户规模
```

---

## 7. API 接口设计

所有接口统一响应格式：
```json
{
  "code": 0,           // 0=成功, 非0=错误码
  "message": "success",
  "data": { ... }
}
```

### 7.1 认证接口 AuthController

```
基础路径: /api/auth

POST /api/auth/register
  请求: { username, email, password, displayName?, privacyConsent? }
  响应: { message: "注册成功，请查收验证邮件" }
  限流: 3次/分钟/IP

POST /api/auth/login
  请求: { username, password }
  响应: { accessToken, refreshToken, expiresIn, tokenType, user }
  限流: 10次/分钟/IP

POST /api/auth/refresh
  请求: { refreshToken }
  响应: { accessToken, refreshToken, expiresIn, tokenType }
  说明: Token Rotation——旧RefreshToken被删除，发新对

POST /api/auth/logout
  请求头: Authorization: Bearer {accessToken}
  请求体: { refreshToken? }
  响应: 200
  说明: 删除Redis中的refreshToken，JWT短期自然过期

POST /api/auth/change-password
  请求头: Authorization: Bearer {accessToken}
  请求: { currentPassword, newPassword }
  响应: 200

GET /api/auth/verify-email?token={tokenId}
  响应: 200 或 redirect到前端验证成功页

POST /api/auth/resend-verification
  请求头: Authorization: Bearer {accessToken}
  响应: 200

DELETE /api/auth/account
  请求头: Authorization: Bearer {accessToken}
  请求: { password }  // 确认身份
  响应: 200 (软删除，7天后永久删除)
```

### 7.2 用户接口 UserController

```
基础路径: /api/user
所有接口需要 Authorization: Bearer {accessToken}

GET /api/user/profile
  响应: { userId, username, email, displayName, avatarUrl, bio,
          emailVerified, roles, createdAt }

PUT /api/user/profile
  请求: { displayName?, avatarUrl?, bio? }
  响应: 更新后的profile

GET /api/user/model-config
  响应: { defaultProvider, defaultModel, providerApiKeys: { "deepseek": "sk-xxxx"... } }
  说明: API Key 在响应中脱敏（仅显示前后各4位）

PUT /api/user/model-config
  请求: { defaultProvider?, defaultModel?, providerApiKeys? }
  说明: API Key 在存储时 AES-256-GCM 加密

GET /api/user/system-prompt
  响应: { systemPrompt: "你是一个..." }

PUT /api/user/system-prompt
  请求: { systemPrompt: "..." }
```

### 7.3 会话接口 SessionController

```
基础路径: /api/sessions
所有接口需要 Authorization: Bearer {accessToken}

GET /api/sessions?page=1&size=20&search=关键词&archived=false
  响应: {
    items: [{ sessionId, name, modelProvider, modelName,
              messageCount, totalTokens, tags, archived, createdAt, updatedAt }],
    total, page, size, totalPages
  }

POST /api/sessions
  请求: { name?, modelProvider?, modelName?, systemPrompt? }
  响应: Session 对象
  说明: name默认"New Chat"，不传modelProvider/modelName则用用户配置的默认值

GET /api/sessions/{sessionId}
  响应: Session 对象 (含messages列表 - 后续消息模块实现)

DELETE /api/sessions/{sessionId}
  响应: 200

PATCH /api/sessions/{sessionId}/name
  请求: { name: "新的会话名" }
  响应: 更新后的Session

POST /api/sessions/batch-delete
  请求: { sessionIds: ["s_xxx", "s_yyy"] }
  响应: { deletedCount: 2 }

PATCH /api/sessions/{sessionId}/archive
  请求: { archived: true }
  响应: 更新后的Session
```

### 7.4 错误码设计

```
0     SUCCESS              成功
1001  USERNAME_EXISTS       用户名已存在
1002  EMAIL_EXISTS          邮箱已注册
1003  INVALID_PASSWORD      密码不符合策略
1004  BAD_CREDENTIALS       用户名或密码错误
1005  ACCOUNT_LOCKED        账户已锁定
1006  ACCOUNT_DISABLED      账户已禁用
1007  ACCOUNT_DELETED       账户已删除
1008  EMAIL_NOT_VERIFIED    邮箱未验证
1009  TOKEN_EXPIRED         Token已过期
1010  TOKEN_INVALID         Token无效
1011  TOKEN_REVOKED         Token已被吊销(盗用检测)
1012  RATE_LIMITED          请求过于频繁
1013  VERIFICATION_EXPIRED  验证链接已过期
1014  SESSION_NOT_FOUND     会话不存在
1015  PERMISSION_DENIED     无权访问该会话
2001  INTERNAL_ERROR        服务器内部错误
```

---

## 8. 高并发架构论证

### 8.1 请求路径性能分析

```
                    JWT验证    Redis查询    MySQL查询    总耗时
/api/auth/login         0          1 (写)     2 (读+写)   ~5ms
/api/auth/refresh       0          2 (读+写+删) 0         ~1ms
/api/user/profile       1 (验签)   0           1 (读)     ~3ms
/api/sessions           1 (验签)   0           1 (分页查询) ~5ms
/api/chat/stream        1 (验签)   0           0          <1ms

说明:
  - JWT验证: 纯CPU计算，HMAC-SHA256，~0.01ms
  - Redis查询: 网络往返 ~0.1ms (本地/同机房)
  - MySQL查询: 有索引 ~1-3ms (本地/同机房)
  - /api/chat/stream 路径完全无I/O (Reactor Netty + SSE直通)
```

### 8.2 扩容方案

```
水平扩展:

  lyclaw-gateway      × N  (无状态，Nginx upstream 轮询)
  lyclaw-auth         × N  (无状态，Nacos 负载均衡)
  lyclaw-orchestration × N  (无状态，Nacos 负载均衡)
  ...
  Redis               × 3  (Sentinel 哨兵 或 Cluster 集群，1主2从)
  MySQL               × 2  (主从复制，写主读从)

扩展瓶颈点:
  1. MySQL 写入: 登录/注册写入量极小(人均每天1-2次)，单机可撑10万DAU
  2. Redis: 单机10万QPS，10万DAU峰值远低于此
  3. JWT验签: 纯CPU无锁操作，随实例数线性扩展
  4. SSE连接: Netty NIO单机可维持10万长连接

结论: 10万DAU以内，4台4C8G微服务 + 1台Redis + 1台MySQL 完全足够
      超过10万DAU，只需增加微服务实例 + MySQL只读副本即可
```

### 8.3 连接池配置建议

```yaml
# MySQL (R2DBC)
spring.r2dbc.pool.initial-size: 10
spring.r2dbc.pool.max-size: 50        # 每实例50连接
spring.r2dbc.pool.max-idle-time: 30m
spring.r2dbc.pool.max-acquire-time: 5s   # 获取连接超时

# Redis (Lettuce)
spring.data.redis.lettuce.pool.max-active: 20
spring.data.redis.lettuce.pool.max-idle: 10
spring.data.redis.lettuce.pool.min-idle: 5
```

---

## 9. 安全设计

### 9.1 认证矩阵

```
场景                        认证方式              有效期      存储位置
用户Web登录                  JWT Access Token     30分钟      无(客户端localStorage)
用户Web刷新                  Refresh Token        7天         Redis
API编程调用                  API Key (lyc_xxxx)   永久         MySQL(AES加密)
第三方OAuth                  OAuth2 Authorization Code  10分钟  Redis (state参数)
邮箱验证                     随机UUID Token        24小时       Redis
密码重置                     随机UUID Token        1小时        Redis
```

### 9.2 密码策略

```
- 最少8字符，最多128字符
- 必须包含: 大写字母、小写字母、数字、特殊字符 各至少1个
- BCrypt strength=12 (约250ms哈希时间，有效防暴力破解)
- 不允许与用户名/邮箱相同
- 不允许使用常见密码 (维护top 10000黑名单，可选)
```

### 9.3 防攻击措施

```
 攻击类型              防御措施
 暴力破解密码           login_attempts Redis INCR，5次/15min锁定
 撞库                  IP维度限流 + username维度限流 双重检测
 Token盗用             Refresh Token Rotation + 竞争刷新检测
 JWT伪造               HMAC-SHA256 256位密钥，无法暴力破解
 CSRF                  JWT存localStorage不存Cookie，天然免疫
 XSS                   前端CSP + HttpOnly Cookie(可选)
 SQL注入               R2DBC参数化查询(预编译)，无拼接SQL
 DDoS                  Gateway层限流 + Nginx rate limiting
 水平越权              userId从JWT提取，所有查询强制WHERE user_id=?
```

### 9.4 API Key 加解密

```
存储前: plaintext → AES-256-GCM(plaintext, masterKey, randomIV) → ciphertext
        存储格式: base64(iv) + ":" + base64(encrypted)

读取后: base64(iv) + ":" + base64(encrypted) → AES-256-GCM解密 → plaintext

主密钥: 256位随机字符串，通过环境变量 LYCLAW_ENCRYPTION_KEY 注入
        定期轮换: 新密钥加密新数据，旧密钥留存用于解密旧数据
```

---

## 10. 部署与迁移

### 10.1 新增依赖

```xml
<!-- 根 pom.xml dependencyManagement 新增 -->

<!-- Spring Session + Redis -->
<dependency>
    <groupId>org.springframework.session</groupId>
    <artifactId>spring-session-data-redis</artifactId>
</dependency>

<!-- Spring Data Redis Reactive -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-data-redis-reactive</artifactId>
</dependency>

<!-- Lettuce (默认Redis客户端，Reactive原生支持) -->
<dependency>
    <groupId>io.lettuce</groupId>
    <artifactId>lettuce-core</artifactId>
</dependency>

<!-- JWT -->
<dependency>
    <groupId>io.jsonwebtoken</groupId>
    <artifactId>jjwt-api</artifactId>
    <version>0.12.6</version>
</dependency>
<dependency>
    <groupId>io.jsonwebtoken</groupId>
    <artifactId>jjwt-impl</artifactId>
    <version>0.12.6</version>
</dependency>
<dependency>
    <groupId>io.jsonwebtoken</groupId>
    <artifactId>jjwt-jackson</artifactId>
    <version>0.12.6</version>
</dependency>

<!-- R2DBC MySQL -->
<dependency>
    <groupId>io.asyncer</groupId>
    <artifactId>r2dbc-mysql</artifactId>
    <version>1.3.0</version>
</dependency>

<!-- Spring Security Crypto (BCrypt) -->
<dependency>
    <groupId>org.springframework.security</groupId>
    <artifactId>spring-security-crypto</artifactId>
</dependency>

<!-- Validation -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-validation</artifactId>
</dependency>

<!-- Mail -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-mail</artifactId>
</dependency>
```

### 10.2 新增模块

```xml
<!-- 根 pom.xml modules 新增 -->
<module>lyclaw-storage-jdbc</module>
<module>lyclaw-auth</module>
```

### 10.3 配置开关

```yaml
# lyclaw-auth application.yml

lyclaw:
  auth:
    enabled: true                     # 总开关

    jwt:
      secret: ${LYCLAW_JWT_SECRET}    # 环境变量注入
      access-token-expiration: 1800   # 30分钟
      refresh-token-expiration: 604800 # 7天
      issuer: lyclaw

    password:
      bcrypt-strength: 12
      min-length: 8
      require-uppercase: true
      require-lowercase: true
      require-digit: true
      require-special: true

    registration:
      enabled: true
      require-email-verification: true
      default-role: ROLE_USER

    rate-limit:
      login-per-minute: 10            # 每IP每分钟登录次数
      register-per-minute: 3          # 每IP每分钟注册次数
      api-per-minute: 60              # 每用户每分钟API调用

    api-key:
      encryption-algorithm: AES/GCM/NoPadding
      key-prefix: "lyc_"              # 生成的API Key前缀

  storage:
    jdbc:
      enabled: true                   # 启用JDBC存储
```

### 10.4 Docker Compose 开发环境

```yaml
version: '3.8'
services:
  mysql:
    image: mysql:8.0
    environment:
      MYSQL_ROOT_PASSWORD: lyclaw123
      MYSQL_DATABASE: lyclaw
    ports:
      - "3306:3306"
    volumes:
      - ./sql/init_lyclaw.sql:/docker-entrypoint-initdb.d/init.sql
      - mysql_data:/var/lib/mysql

  redis:
    image: redis:7-alpine
    command: redis-server --appendonly yes
    ports:
      - "6379:6379"
    volumes:
      - redis_data:/data

volumes:
  mysql_data:
  redis_data:
```

### 10.5 分阶段实施计划

```
阶段一 (当前): 登录功能
  ├── 数据库建表 (1张SQL文件)
  ├── lyclaw-storage-jdbc 模块 (JDBC存储后端)
  ├── lyclaw-auth 模块骨架
  ├── JWT Token Provider
  ├── AuthController (register/login/refresh/logout)
  └── JwtAuthWebFilter (Gateway层JWT验证)

阶段二: 会话管理
  ├── SessionController (CRUD + 分页列表)
  ├── SessionService (含userId隔离)
  └── lyclaw_sessions 表

阶段三: 用户配置
  ├── UserController (profile + model-config)
  ├── UserConfigService (3级优先级)
  └── API Key 加解密

阶段四: 高级功能
  ├── OAuth2 第三方登录
  ├── 邮箱验证 (spring-boot-starter-mail)
  ├── 管理后台 (AdminController)
  └── 配额管理
```

---

## 附录A: 关键技术选型对比

### A.1 为什么 MySQL 而不是 PostgreSQL

| 维度 | MySQL 8.0 | PostgreSQL 16 |
|------|-----------|---------------|
| 高并发读写 | InnoDB MVCC + 行锁，成熟稳定 | MVCC 更先进但VACUUM开销大 |
| 运维生态 | 运维人员更熟悉，工具链更丰富 | 工具链较少 |
| 云服务 | 各云厂商均有托管MySQL | 托管PG较少/版本较旧 |
| ORM兼容 | MyBatis/MyBatis-Plus/Hibernate全面支持 | R2DBC PG driver成熟 |
| JSON | JSON类型(不支持索引内的JSON路径) | JSONB(支持GIN索引) |
| 数组 | 不支持(用JSON替代) | 原生数组类型 |

选择 MySQL 的核心原因：运维团队更熟悉、云厂商托管服务成熟、当前数据量不需要 PG 的高级特性（JSONB索引、数组等）。等用户量达到百万级别后，可平滑迁移到 TiDB（MySQL 协议兼容）。

### A.2 为什么 Redis 而不是数据库存令牌

见第 3.1 节对比表。核心：Redis 的 TTL 自动过期和原子 INCR 是令牌存储和限流的理想选择。

### A.3 为什么 R2DBC 而不是 JPA/MyBatis

LyClaw 全项目基于 WebFlux + Reactor（Netty 非阻塞 I/O）。JDBC 是阻塞式协议，每个数据库查询独占一个线程。WebFlux 的 event-loop 线程数通常等于 CPU 核数（如 4 核 = 4 个 event-loop 线程），JDBC 阻塞会迅速耗尽线程池，导致整个服务无响应。

R2DBC 是非阻塞协议，查询在 Netty event-loop 内执行，不占用额外线程。本项目查询简单（单表 CRUD），不需要 JPA 的实体管理或 MyBatis 的映射 XML。
