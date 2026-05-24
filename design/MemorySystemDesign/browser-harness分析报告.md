# browser-harness 深度分析报告

> 分析目标: `/tmp/agent-research/browser-harness-main/`
> 分析日期: 2026-05-23
> 分析版本: v0.1.0 (GitHub: browser-use/browser-harness)
> 代码规模: 核心约1000行 (4个核心文件), 测试约1800行, 文档/技能文件约130+个
> Python版本要求: >= 3.11
> 核心依赖: cdp-use==1.4.5, fetch-use==0.4.0, pillow==12.2.0, websockets==15.0.1

---

## 目录

1. [项目概述](#1-项目概述)
2. [记忆系统分析](#2-记忆系统分析)
3. [Agent编排分析](#3-agent编排分析)
4. [Agent实现分析](#4-agent实现分析)
5. [关键发现与总结](#5-关键发现与总结)

---

## 1. 项目概述

### 1.1 项目定位

browser-harness 是 Browser Use 公司开源的一个**极薄的 CDP (Chrome DevTools Protocol) 浏览器控制中间件**，它不是 Agent 框架。它的唯一职责是在浏览器 WebSocket 和外部 LLM 之间提供一层薄薄的 IPC 桥梁。

项目 README (`/tmp/agent-research/browser-harness-main/README.md`) 第7-8行明确描述:
```
One websocket to Chrome, nothing between.
The agent writes what's missing during execution. The harness improves itself every run.
```

核心设计原则来自 `AGENTS.md`(第1-6行):
```
browser-harness is a thin layer that connects agents to browsers via an editable CDP harness.

# Code priorities
- Clarity
- Precision
- Low verbosity
- Versatility
```

架构图来自 `install.md`(第48-51行):
```
Chrome / Browser Use cloud -> CDP WS -> browser_harness.daemon -> IPC -> browser_harness.run
```

数据流详细说明:
- 协议: 一行JSON，每行双向通信
- 请求格式: `{method, params, session_id}` 用于CDP调用，`{meta: ...}` 用于守护进程控制
- 响应格式: `{result}` / `{error}` / `{events}` / `{session_id}`
- IPC通信: POSIX上使用Unix socket (`/tmp/bu-<NAME>.sock`)，Windows上使用TCP loopback + port file
- BU_NAME 命名空间化守护进程的IPC、PID和日志文件
- BU_CDP_WS 覆盖本地Chrome发现，用于远程浏览器
- BU_CDP_URL 覆盖本地Chrome发现，指定特定的DevTools HTTP端点
- BU_BROWSER_ID + BROWSER_USE_API_KEY 允许守护进程在关闭时停止Browser Use云端浏览器

### 1.2 技术栈

**文件**: `/tmp/agent-research/browser-harness-main/pyproject.toml`

完整的项目元数据:
```
[build-system]
requires = ["setuptools>=69"]
build-backend = "setuptools.build_meta"

[project]
name = "browser-harness"
version = "0.1.0"
description = "The simplest, thinnest, and most powerful harness to control your real browser with your agent."
requires-python = ">=3.11"
dependencies = [
    "cdp-use==1.4.5",
    "fetch-use==0.4.0",
    "pillow==12.2.0",
    "websockets==15.0.1",
]

[project.scripts]
browser-harness = "browser_harness.run:main"

[tool.setuptools]
package-dir = {"" = "src"}

[tool.setuptools.packages.find]
where = ["src"]

[tool.pytest.ini_options]
pythonpath = ["src"]
```

依赖分析:
- `cdp-use==1.4.5`: CDP WebSocket 客户端，提供 `CDPClient` 类用于发送和接收 CDP 消息
- `fetch-use==0.4.0`: HTTP 代理客户端，当设置了 BROWSER_USE_API_KEY 时通过代理发送 HTTP 请求
- `pillow==12.2.0`: 图像处理库，用于截图缩略图（max_dim 功能）和调试点击标记
- `websockets==15.0.1`: WebSocket 底层库，cdp-use 的基础依赖

### 1.3 核心架构文件与行数统计

| 文件路径 | 行数 | 职责 | 关键类/函数 |
|----------|------|------|-------------|
| `src/browser_harness/helpers.py` | 503行 | CDP封装, 浏览器控制原语 | cdp(), js(), page_info(), click_at_xy(), fill_input(), capture_screenshot(), 等 |
| `src/browser_harness/daemon.py` | 420行 | 长生命周期CDP WS守护进程 | class Daemon, serve(), get_ws_url() |
| `src/browser_harness/admin.py` | 860行 | 守护进程生命周期, 诊断, 更新, Profile | ensure_daemon(), restart_daemon(), run_doctor(), start_remote_daemon(), sync_local_profile() |
| `src/browser_harness/_ipc.py` | 197行 | 守护进程与CLI间的IPC通信层 | connect(), request(), ping(), identify(), serve() |
| `src/browser_harness/run.py` | 129行 | CLI入口, 极简 | main() |
| `src/browser_harness/__init__.py` | 2行 | 包初始化 | 无 |
| `agent-workspace/agent_helpers.py` | 8行(空) | Agent可编辑辅助函数 | 空文件 |

### 1.4 测试文件统计

| 文件路径 | 行数 | 测试内容 |
|----------|------|----------|
| `tests/conftest.py` | 15行 | 测试fixtures (make_png) |
| `tests/unit/test_run.py` | 241行 | CLI入口测试: stdin执行, 云端启动条件, 端点覆盖 |
| `tests/unit/test_helpers.py` | 353行 | 帮助函数测试: 截图, domain skills, fill_input, 等待, 网络空闲 |
| `tests/unit/test_daemon.py` | 296行 | 守护进程测试: session管理, domain启用, 并发, current_tab |
| `tests/unit/test_admin.py` | 644行 | 管理功能测试: 守护进程生命周期, doctor, PID复用安全, 远程启动 |
| `tests/unit/test_ipc.py` | 108行 | IPC测试: identify()/ping() 的payload安全验证 |
| `tests/integration/test_js.py` | 172行 | JS执行测试: 表达式包装, 异常处理, unserializable值 |

### 1.5 设计约束 (来自 SKILL.md 第95-102行)

项目明确列出了一组硬性设计约束，这些约束直接说明了为什么记忆系统和Agent编排不存在:

```
## Design constraints

- Coordinate clicks default. Input.dispatchMouseEvent goes through
  iframes/shadow/cross-origin at the compositor level.
- Connect to the user's running Chrome. Don't launch your own browser.
- cdp-use is only for CDPClient.send_raw. Prefer raw CDP strings over typed wrappers.
- run.py stays tiny. No argparse, subcommands, or extra control layer.
- Core helpers stay short. Put task-specific helper additions in
  `agent-workspace/agent_helpers.py`; daemon/bootstrap and remote session admin
  live in the core package.
- Don't add a manager layer. No retries framework, session manager,
  daemon supervisor, config system, or logging framework.
```

最后一条约束是关键: "Don't add a manager layer — No retries framework, session manager, daemon supervisor, config system, or logging framework."

这明确排除了:
- 会话管理器 (Session Manager) → 无Agent会话管理
- 重试框架 (Retries Framework) → 无执行循环控制
- 守护进程监控器 (Daemon Supervisor) → 无生命周期管理
- 配置系统 (Config System) → 无Agent配置
- 日志框架 (Logging Framework) → 无语义记录

---

## 2. 记忆系统分析

### 2.1 总体结论: 完全未实现

**browser-harness 没有实现任何形式的记忆系统。** 这不是遗漏或TODO，而是刻意的、原则性的设计选择。项目哲学认为，记忆系统和状态管理是外部LLM的责任，不应由浏览器中间件承担。

让我们逐一分析每个子维度。

### 2.2 记忆系统架构

**状态: 未实现。**

browser-harness 没有:
- 分层记忆架构 (无 working memory / short-term memory / long-term memory 分层)
- 向量检索架构 (无 embedding 模型调用，无向量索引)
- 记忆图谱 (无 knowledge graph)
- 记忆缓存层 (无 LRU cache、无 TTL-based eviction)
- 记忆压缩/摘要 (无 summarization pipeline)

### 2.3 记忆数据模型

**状态: 未实现。**

在整个项目 (130+文件, ~2800行代码) 中，没有任何:
- 记忆实体类定义 (无 `class Memory`, `class MemoryItem`, `class EpisodicMemory`)
- 记忆字段定义 (无 `content`, `embedding`, `timestamp`, `importance`, `access_count`)
- 记忆类型枚举 (无 `MEMORY_TYPE = Enum(["episodic", "semantic", "procedural"])`)
- 记忆序列化格式 (无 JSON schema, 无 protobuf, 无 pickle)

唯一接近"数据模型"的是 Domain Skills 的 Markdown 文件结构:

```
agent-workspace/domain-skills/
  <站点名>/
    <功能名>.md
```

但这只是一个文件命名约定，没有程序化的数据模型。

### 2.4 记忆生命周期

**状态: 未实现。**

不存在以下任何生命周期阶段:
- 写入时机 (write trigger): 没有"当任务完成时写入记忆"的代码
- 检索时机 (retrieval trigger): 没有"在处理请求前搜索相关记忆"的代码
- 清理时机 (cleanup trigger): 没有 TTL 驱逐、没有重要性衰减、没有容量限制回收
- 更新时机 (update trigger): 没有记忆合并/去重逻辑

唯一的"生命周期"是 Domain Skills 的手动编辑:
- LLM 在完成浏览器任务后，手动创建/编辑 `domain-skills/<site>/` 下的 Markdown 文件
- 这些文件永久存在于磁盘上，直到被手动删除或提交到 git
- README 鼓励贡献者通过 PR 提交新的 domain skills

### 2.5 记忆检索策略

**状态: 未实现。**

没有任何检索策略:
- 无语义搜索 (无 embedding → cosine similarity → top-k 流程)
- 无关键词搜索 (无 TF-IDF, 无 BM25)
- 无时间衰减 (无 recency weighting)
- 无加权评分 (无 importance × recency × relevance 的加权公式)
- 无混合检索 (无 vector + keyword 的融合策略)

Domain Skill 的"检索"是纯粹的 URL 域名前缀匹配:

**文件**: `/tmp/agent-research/browser-harness-main/src/browser_harness/helpers.py`(第159-164行)

```python
def goto_url(url):
    r = cdp("Page.navigate", url=url)
    if os.environ.get("BH_DOMAIN_SKILLS") != "1":
        return r
    d = (AGENT_WORKSPACE / "domain-skills" /
         (urlparse(url).hostname or "").removeprefix("www.").split(".")[0])
    return {**r, "domain_skills":
            sorted(p.name for p in d.rglob("*.md"))[:10]} if d.is_dir() else r
```

这个"检索"逻辑的分析:
- 第一步: 解析 URL 获取 hostname (例如 `www.amazon.com`)
- 第二步: 移除 `www.` 前缀 (得到 `amazon.com`)
- 第三步: 取第一个 `.` 之前的部分 (`amazon`)
- 第四步: 在 `domain-skills/amazon/` 目录下 glob 所有 `.md` 文件
- 第五步: 按文件名排序，返回前10个
- **全程没有任何语义分析、向量搜索或相关性排序**

### 2.6 Agent间记忆共享/隔离

**状态: 未实现。**

- 项目中没有 Agent 概念，因此不存在 Agent 间的记忆共享或隔离
- 多个 browser-harness 进程通过 `BU_NAME` 环境变量实现命名空间隔离
- 不同 BU_NAME 的守护进程各自维护独立的 WebSocket 连接和 IPC 通道
- 但它们之间没有任何"记忆"需要共享或隔离
- Domain Skills 文件是全局共享的(都在同一个 `agent-workspace/domain-skills/` 目录下)，所有调用者都能访问相同的 Domain Skill 文件

### 2.7 持久化方案

**状态: 未实现。**

项目没有任何数据库:
- 无 SQLite
- 无 PostgreSQL
- 无 MySQL
- 无 MongoDB
- 无 Redis
- 无 向量数据库 (Chroma, Pinecone, Milvus, Qdrant, Weaviate, LanceDB...)
- 无 JSON 文件形式的持久化记忆存储

项目中确实使用文件系统存储了一些内容，但它们都不是"记忆"：

| 存储内容 | 文件路径 | 格式 | 生命周期 | 是否是记忆 |
|----------|----------|------|----------|------------|
| 守护进程日志 | `/tmp/bu-<NAME>.log` | 纯文本(append-only) | 守护进程生命周期 | 否 |
| 守护进程PID | `/tmp/bu-<NAME>.pid` | 纯文本(单个PID数字) | 守护进程生命周期 | 否 |
| 守护进程端口(Windows) | `/tmp/bu-<NAME>.port` | JSON `{port, token}` | 守护进程生命周期 | 否 |
| IPC Socket(POSIX) | `/tmp/bu-<NAME>.sock` | Unix Domain Socket | 守护进程生命周期 | 否 |
| 版本缓存 | `/tmp/bu-version-cache.json` | JSON `{tag, fetched_at, banner_shown_on}` | 24小时TTL | 否 |
| 截图 | `/tmp/shot.png` 或自定义路径 | PNG 图像 | 调用者管理 | 否 |
| Domain Skills | `agent-workspace/domain-skills/<site>/*.md` | Markdown | 永久(手动管理) | 近似 |
| Agent Helpers | `agent-workspace/agent_helpers.py` | Python 代码 | 永久(手动管理) | 近似 |
| Interaction Skills | `interaction-skills/*.md` | Markdown | 永久(手动管理) | 近似 |
| 环境变量 | `.env` 文件 | KEY=VALUE | 永久(手动管理) | 否 |

### 2.8 逐文件深度分析: 是否存在记忆相关代码

#### 2.8.1 helpers.py — 无记忆代码

503行的 `helpers.py` 包含20+个函数，全部是浏览器控制原语或HTTP请求工具。逐函数分类:

**浏览器导航类** (无记忆):
- `goto_url(url)` — CDP Page.navigate, 可选Domain Skill发现
- `page_info()` — 获取当前页面信息
- `new_tab(url)` — 创建新标签页
- `switch_tab(target)` — 切换标签页
- `close_tab(target)` — 关闭标签页
- `current_tab()` — 获取当前标签页信息
- `list_tabs(include_chrome)` — 列出所有标签页
- `ensure_real_tab()` — 确保当前是真实用户标签页
- `iframe_target(url_substr)` — 查找iframe的target ID

**输入控制类** (无记忆):
- `click_at_xy(x, y, button, clicks)` — 坐标点击
- `type_text(text)` — 通过Input.insertText输入
- `fill_input(selector, text, clear_first, timeout)` — 填充框架管理的输入框
- `press_key(key, modifiers)` — 按键事件
- `dispatch_key(selector, key, event)` — DOM键盘事件
- `scroll(x, y, dy, dx)` — 鼠标滚轮

**视觉类** (无记忆):
- `capture_screenshot(path, full, max_dim)` — 截图保存为PNG

**数据获取类** (无记忆):
- `js(expression, target_id)` — 在页面执行JavaScript并返回结果
- `cdp(method, session_id, **params)` — 原始CDP调用
- `drain_events()` — 排空CDP事件缓冲区
- `http_get(url, headers, timeout)` — 纯HTTP GET
- `upload_file(selector, path)` — 文件上传

**等待类** (无记忆):
- `wait(seconds)` — 简单sleep
- `wait_for_load(timeout)` — 等待document.readyState
- `wait_for_element(selector, timeout, visible)` — 等待DOM元素出现
- `wait_for_network_idle(timeout, idle_ms)` — 等待网络空闲

**辅助类** (无记忆):
- `_load_agent_helpers()` — 动态加载agent_helpers.py中定义的函数到全局作用域

**结论**: helpers.py 100%是纯粹的工具函数，0%是记忆系统。

#### 2.8.2 daemon.py — 无记忆代码

420行的 `daemon.py` 包含 `Daemon` 类和服务循环:

```python
class Daemon:
    def __init__(self):
        self.cdp = None          # CDPClient实例 (WebSocket连接)
        self.session = None      # 当前CDP session ID (字符串)
        self.target_id = None    # 当前CDP target ID (字符串)
        self.events = deque(maxlen=BUF)  # BUF=500的事件缓冲区
        self.dialog = None       # 当前待处理的JS对话框
        self.stop = None         # asyncio.Event 停止信号
```

这些字段的分析:
- `self.cdp`: 指向 WebSocket 连接的引用 — 这是**连接状态**, 不是记忆
- `self.session`: 当前的 CDP 会话标识符 — 这是**会话状态**, 不是记忆
- `self.target_id`: 当前附着的页面标识 — 这是**浏览器状态**, 不是记忆
- `self.events`: 500容量的CDP事件缓冲区 — 这是**事件队列**, 不是记忆。数据流是: CDP推送事件 → 追加到deque → drain_events()读取并清空 → 客户端消费。没有持久化, 没有检索, 没有语义处理。
- `self.dialog`: 当前待处理的JS对话框信息 — 这是**瞬态标志**, 不是记忆

`Daemon.handle()` 方法(第261-356行)处理所有IPC请求，支持的meta操作:
- `meta: "ping"` — 活性探测，返回 `{pong: True, pid: <PID>}`
- `meta: "drain_events"` — 排空事件缓冲区
- `meta: "session"` — 返回当前session ID
- `meta: "current_tab"` — 返回当前标签页信息
- `meta: "connection_status"` — 返回连接状态
- `meta: "set_session"` — 切换session
- `meta: "pending_dialog"` — 返回待处理的对话框
- `meta: "shutdown"` — 设置停止信号

**结论**: daemon.py 100%是浏览器连接管理，0%是记忆系统。

#### 2.8.3 admin.py — 无记忆代码

860行的 `admin.py` 是项目中最大的文件。逐功能分类:

**守护进程生命周期管理** (无记忆):
- `daemon_alive(name)` — 检查守护进程是否存活
- `ensure_daemon(wait, name, env)` — 确保守护进程运行, 幂等操作
- `restart_daemon(name)` — 停止守护进程 (命名历史原因, 实际只停止)
- `_process_start_time(pid)` — 跨平台进程启动时间指纹 (用于PID复用安全)
- `browser_connections()` — 列出所有活跃的浏览器连接
- `active_browser_connections()` — 计数活跃连接
- `_daemon_endpoint_names()` — 发现守护进程端点名称

**远程/云端浏览器管理** (无记忆):
- `start_remote_daemon(name, profileName, **kwargs)` — 启动云端浏览器
- `stop_remote_daemon(name)` — 停止云端浏览器
- `list_cloud_profiles()` — 列出云端的cookie profiles
- `list_local_profiles()` — 列出本地浏览器的profiles
- `sync_local_profile(name, ...)` — 同步本地profile cookies到云端
- `_resolve_profile_name(profile_name)` — 按名称查找profile
- `_stop_cloud_browser(browser_id)` — 停止云端浏览器
- `_browser_use(path, method, body)` — 调用Browser Use API
- `_cdp_ws_from_url(cdp_url)` — 从HTTP端点解析WebSocket URL

**诊断与更新** (无记忆):
- `run_doctor()` — 诊断检查
- `run_doctor_fix_snap()` — Snap Chromium修复指南
- `check_for_update()` — 检查GitHub更新
- `print_update_banner(out)` — 打印更新提示 (每天最多一次)
- `run_update(yes)` — 执行更新

**辅助函数** (无记忆):
- `_load_env()` / `_load_env_file(p)` — 加载环境变量
- `_version()` — 获取安装版本号
- `_repo_dir()` / `_install_mode()` — 检测安装模式
- `_latest_release_tag(force)` — 获取最新发布标签 (带24小时缓存)
- `_version_tuple(v)` — 版本号解析
- `_chrome_running()` — 检测Chrome进程
- `_open_chrome_inspect()` — 打开chrome://inspect
- `_needs_chrome_remote_debugging_prompt(msg)` — 判断错误是否需要用户操作
- `_has_local_gui()` / `_show_live_url(url)` — GUI检测和URL展示
- `_is_snap_browser(path)` / `_doctor_probe_chrome_binary_for_snap()` — Snap浏览器检测

**结论**: admin.py 100%是运维管理，0%是记忆系统。

#### 2.8.4 _ipc.py — 无记忆代码

197行的 `_ipc.py` 是纯粹的IPC通信层:

- `connect(name, timeout)` — 连接到守护进程 (Unix socket 或 TCP)
- `request(c, token, req)` — 发送请求并接收响应
- `ping(name, timeout)` — 活性检查 (防端口复用)
- `identify(name, timeout)` — 获取守护进程身份(PID)
- `serve(name, handler)` — 启动服务器
- `cleanup_endpoint(name)` — 清理socket/port文件
- `sock_addr(name)` — 获取socket地址
- `log_path(name)` / `pid_path(name)` / `port_path(name)` — 路径辅助函数
- `spawn_kwargs()` — 子进程启动参数
- `expected_token()` — 获取服务器token (Windows only)

**结论**: _ipc.py 100%是通信基础设施，0%是记忆系统。

#### 2.8.5 run.py — 无记忆代码

129行的 `run.py` 是CLI入口:

```python
def main():
    args = sys.argv[1:]
    if args and args[0] in {"-h", "--help"}: print(HELP); return
    if args and args[0] == "--version": print(_version() or "unknown"); return
    if args and args[0] == "--doctor": sys.exit(run_doctor())
    if args and args[0] == "doctor":
        rest = args[1:]
        if rest == ["--fix-snap"]: sys.exit(run_doctor_fix_snap())
        if rest: print("usage: browser-harness doctor [--fix-snap]", file=sys.stderr); sys.exit(2)
        sys.exit(run_doctor())
    if args and args[0] == "--update":
        yes = any(a in {"-y", "--yes"} for a in args[1:])
        sys.exit(run_update(yes=yes))
    if args and args[0] == "--reload":
        restart_daemon()
        print("daemon stopped -- will restart fresh on next call"); return
    if args and args[0] == "--debug-clicks":
        os.environ["BH_DEBUG_CLICKS"] = "1"; args = args[1:]
    if not args and not sys.stdin.isatty():
        code = sys.stdin.read()
        if not code.strip(): sys.exit(USAGE)
    else: sys.exit(USAGE)
    print_update_banner()
    # cloud auto-bootstrap logic
    if (not daemon_alive() and not _local_chrome_listening()
        and not _explicit_cdp_configured()
        and os.environ.get("BROWSER_USE_API_KEY")
        and os.environ.get("BU_AUTOSPAWN")):
        start_remote_daemon(NAME)
    ensure_daemon()
    exec(code, globals())
```

执行流程:
1. 解析CLI参数 (help, version, doctor, update, reload, debug-clicks)
2. 如果没有特殊参数，从stdin读取Python代码
3. 打印更新提示
4. (条件) 自动启动云端浏览器
5. 确保守护进程运行
6. `exec(code, globals())` 执行用户代码

**关键洞察**: `exec(code, globals())` 将用户代码在包含所有 helpers 的全局作用域中执行。这次执行是一个**一次性、无状态的**过程。执行完成后进程退出，所有变量销毁。下一次调用是完全独立的。

**结论**: run.py 100%是CLI入口，0%是记忆系统。

### 2.9 Domain Skills 深度分析 (作为"近似记忆")

尽管不是真正的记忆系统，Domain Skills 是这个项目中最接近"知识记忆"的概念。

#### 2.9.1 组织结构

Domain Skills 存储在 `agent-workspace/domain-skills/` 目录下，按网站域名组织:

```
agent-workspace/domain-skills/
  aa/checkout.md                          AA.com 结账流程
  agentlist/discovery.md                  AgentList 发现
  alaska/checkout.md                      Alaska航空 结账
  amazon/product-search.md                Amazon 产品搜索
  archive-org/scraping.md                 Archive.org 数据爬取
  arxiv/scraping.md                       arXiv 论文爬取
  arxiv-bulk/scraping.md                  arXiv 批量爬取
  articulate-rise/code-blocks.md           Articulate Rise 代码块
  atlas/overview.md                       Atlas 概览
  bigbang-hr/checkout.md                  BigBang HR 结账
  bilibili/navigation.md                  B站 导航
  booking-com/scraping.md                 Booking.com 数据爬取
  BOSS-zhipin/chat.md                     BOSS直聘 聊天
  BOSS-zhipin/job-search.md               BOSS直聘 职位搜索
  BOSS-zhipin/navigation.md               BOSS直聘 导航
  browser-use-cloud/cleanup-zombies.py    BrowserUse云 清理僵尸进程
  browser-use-cloud/cloud.md              BrowserUse云 使用指南
  capterra/scraping.md                    Capterra 数据爬取
  centilebrain/generate-estimates.md      CentileBrain 生成估算
  claude-ai/extract-share-transcript.py   Claude.ai 提取分享对话脚本
  claude-ai/share-export.md               Claude.ai 分享导出
  coingecko/scraping.md                   CoinGecko 数据爬取
  coinmarketcap/scraping.md               CoinMarketCap 数据爬取
  coursera/scraping.md                    Coursera 数据爬取
  craigslist/scraping.md                  Craigslist 数据爬取
  crossref/scraping.md                    Crossref 数据爬取
  ctrip/hotels.md                         携程 酒店
  dev-to/scraping.md                      Dev.to 数据爬取
  duckduckgo/scraping.md                  DuckDuckGo 数据爬取
  ebay/scraping.md                        eBay 数据爬取
  etsy/scraping.md                        Etsy 数据爬取
  eventbrite/scraping.md                  Eventbrite 数据爬取
  expedia/automation.md                   Expedia 自动化
  facebook/groups.md                      Facebook 群组
  facebook/pages.md                       Facebook 页面
  flipkart/shopping.md                    Flipkart 购物
  framer/editor.md                        Framer 编辑器
  fred/scraping.md                        FRED 数据爬取
  g2/scraping.md                          G2 数据爬取
  genius/scraping.md                      Genius 数据爬取
  github/repo-actions.md                  GitHub 仓库操作
  github/scraping.md                      GitHub 数据爬取
  glassdoor/scraping.md                   Glassdoor 数据爬取
  gmail/compose.md                        Gmail 邮件撰写
  goodreads/scraping.md                   Goodreads 数据爬取
  gutenberg/scraping.md                   Gutenberg 数据爬取
  hackernews/scraping.md                  HackerNews 数据爬取
  howlongtobeat/scraping.md               HowLongToBeat 数据爬取
  hubspot/private-app-webhooks.md         HubSpot 私有应用Webhooks
  imdb/scraping.md                        IMDB 数据爬取
  itch-io/scraping.md                     Itch.io 数据爬取
  job-boards/indeed-glassdoor.md          招聘网站 Indeed/Glassdoor
  letterboxd/scraping.md                  Letterboxd 数据爬取
  linkedin/invitation-manager.md          LinkedIn 邀请管理器
  loom/folder-enumeration.md              Loom 文件夹枚举
  ly-com/hotels.md                        同程 酒店
  macrotrends/scraping.md                 Macrotrends 数据爬取
  manus/tasks.md                          Manus 任务
  medium/article-hydration.md             Medium 文章水合
  medium/scraping.md                      Medium 数据爬取
  metacritic/scraping.md                  Metacritic 数据爬取
  musicbrainz/scraping.md                 MusicBrainz 数据爬取
  nasa/scraping.md                        NASA 数据爬取
  news-aggregation/multi-source.md        新闻聚合 多源
  open-library/scraping.md                OpenLibrary 数据爬取
  openalex/scraping.md                    OpenAlex 数据爬取
  openstreetmap/scraping.md               OpenStreetMap 数据爬取
  package-registries/npm-pypi.md          包注册表 npm/pypi
  perplexity/computer.md                  Perplexity Computer
  polymarket/scraping.md                  Polymarket 数据爬取
  producthunt/scraping.md                 ProductHunt 数据爬取
  pubmed/scraping.md                      PubMed 数据爬取
  qbo/report-export.md                    QuickBooks 报表导出
  quora/scraping.md                       Quora 数据爬取
  rawg/scraping.md                        RAWG 数据爬取
  reddit/scraping.md                      Reddit 数据爬取
  rest-countries/scraping.md              REST Countries 数据爬取
  salesforce/                             Salesforce (空目录)
  sec-edgar/scraping.md                   SEC EDGAR 数据爬取
  shopify-admin/embedded-apps.md          Shopify Admin 嵌入式应用
  shopify-admin/knowledge-base.md         Shopify Admin 知识库
  shopify-admin/polaris-inputs.md         Shopify Admin Polaris输入
  shopify-admin/README.md                 Shopify Admin 使用指南
  soundcloud/scraping.md                  SoundCloud 数据爬取
  spotify/scraping.md                     Spotify 数据爬取
  spreadshirt/                            Spreadshirt (空目录)
  stackoverflow/scraping.md               StackOverflow 数据爬取
  steam/scraping.md                       Steam 数据爬取
  substack/scraping.md                    Substack 数据爬取
  tasksquad-ai/agents.md                  TaskSquad AI Agents
  tasksquad-ai/auth.md                    TaskSquad AI 认证
  tasksquad-ai/tasks.md                   TaskSquad AI 任务
  thetechgeeks/pricing.md                 TheTechGeeks 定价
  tiktok/upload.md                        TikTok 上传
  tradingview/scraping.md                 TradingView 数据爬取
  trello/boards-and-lists.md              Trello 看板和列表
  trustpilot/scraping.md                  Trustpilot 数据爬取
  vercel/vercel.md                        Vercel 部署
  walmart/scraping.md                     Walmart 数据爬取
  wayback-machine/scraping.md             Wayback Machine 数据爬取
  weather/scraping.md                     天气 数据爬取
  wehotel/hotels.md                       微酒店 酒店
  wellfound/scraping.md                   Wellfound 数据爬取
  weread/read.md                          微信读书 阅读
  world-bank/scraping.md                  World Bank 数据爬取
  x/posting.md                            X/Twitter 发帖
  xiaohongshu/scraping.md                 小红书 数据爬取
  youtube/scraping.md                     YouTube 数据爬取
  zillow/scraping.md                      Zillow 数据爬取
```

总计约100+个网站的知识文档。

#### 2.9.2 Domain Skill 文件结构模式

以 GitHub scraping 为例 (`/tmp/agent-research/browser-harness-main/agent-workspace/domain-skills/github/scraping.md`):

```
# GitHub — Scraping & Data Extraction
## Do this first (关键指导)
## Common workflows (具体操作模式)
  ### Repo metadata (API)
  ### User/org profile (API)
  ### Trending page (browser required)
  ### Search repositories (API)
  ### Commits, releases, issues (API)
  ### File contents via API
  ### Parallel fetching (多个仓库并发)
## Gotchas (陷阱和注意事项)
```

以 LinkedIn 邀请管理器为例 (`/tmp/agent-research/browser-harness-main/agent-workspace/domain-skills/linkedin/invitation-manager.md`):

```
# LinkedIn — Invitation Manager
## URL filters (URL参数说明)
## Button selectors (按钮选择器)
## Trap: "follows you" cards (已知陷阱)
## Pagination — reload, don't scroll (分页策略)
## Safety modal (安全弹窗处理)
## Quick sketch (快速实现草图/伪代码)
```

以 Amazon 产品搜索为例 (`/tmp/agent-research/browser-harness-main/agent-workspace/domain-skills/amazon/product-search.md`):

```
# Amazon — Product Search & Data Extraction
## Navigation (导航方法)
  ### Direct search URL
  ### Search box typing
  ### Direct product page
## Session Gotcha (会话陷阱)
## Search Results Extraction (搜索结果提取)
  ### Container selector
  ### Full extraction (完整提取代码)
  ### Field notes (字段说明)
## Product Detail Page Extraction (产品详情页提取)
  ### Confirmed selectors
  ### Price field notes
## Best Sellers Page (畅销榜页面)
## Pagination (分页)
## Result Count (结果计数)
## CAPTCHA Detection (验证码检测)
## Gotchas (9条陷阱)
```

以 Claude AI 分享导出为例 (`/tmp/agent-research/browser-harness-main/agent-workspace/domain-skills/claude-ai/share-export.md`):

```
# claude.ai — Export a Shared Conversation
## Auth requirement (认证要求)
## DOM map (DOM结构映射)
  - 稳定的选择器
  - 要避免的陷阱
## Container-walk pattern (容器遍历模式)
## Extraction (提取方法 + 配套脚本)
## What this skill does NOT cover (明确范围)
```

#### 2.9.3 Domain Skill 文件内容特征

通过分析多个Domain Skill文件，可以发现以下特征模式:

**必含元素**:
1. 标题: `# <站点名> — <功能描述>`
2. 具体的URL模式
3. 完整可运行的Python代码片段
4. 关键CSS选择器及其可靠性说明
5. "Gotchas"/"Traps" 部分 — 列出在实践中发现的陷阱

**常见元素**:
6. 首选API的提醒 (如GitHub skill强调优先使用REST API)
7. 等待策略 (wait_for_load + 额外的sleep)
8. 完整的JavaScript提取代码 (通过js()函数)
9. 已知的框架/库兼容性问题 (如React, Vue, Polaris)

**不含元素**:
- 没有YAML frontmatter(无记忆元数据如日期、作者、标签)
- 没有向量嵌入
- 没有相关性评分
- 没有使用统计

#### 2.9.4 Domain Skill 的检索与加载

Domain Skills 是**手动加载**的，不是自动检索的:

**启用机制**:
1. 设置环境变量 `BH_DOMAIN_SKILLS=1` (默认关闭)
2. 当 `goto_url()` 被调用时，自动根据域名匹配skill文件
3. SKILL.md 建议LLM在执行任务前 "read every file in the matching domain-skills directory before inventing an approach"

**重要**: Domain Skills 的"检索结果"只是返回文件名列表，不返回文件内容。LLM需要自行读取文件内容。这是关键的区别 — 没有自动内容注入。

#### 2.9.5 Domain Skill 创建流程

根据 README.md(第61-63行):
```
Skills are written by the harness, not by you. Just run your task with the agent —
when it figures something non-obvious out, it files the skill itself. Please don't
hand-author skill files; agent-generated ones reflect what actually works in the browser.
```

流程:
1. 用户通过LLM执行浏览器任务
2. LLM在执行过程中发现非显而易见的网站特性(选择器、API、陷阱)
3. LLM将发现写入 `agent-workspace/domain-skills/<site>/` 目录下的Markdown文件
4. 这些文件可以通过PR贡献回主仓库

这是一种**群智(crowd-sourced)知识积累**模式，但不是程序化的记忆系统。

### 2.10 Interaction Skills 深度分析

Interaction Skills 是通用浏览器交互模式的参考文档，存储在 `interaction-skills/` 目录下。

**文件列表与内容完整性**:

| 文件名 | 内容状态 | 说明 |
|--------|----------|------|
| `connection.md` | 完整(49行) | 守护进程连接管理、omnibox popup陷阱、启动序列、导航建议 |
| `cookies.md` | 占位(3行) | 仅有标题和一句概要 |
| `cross-origin-iframes.md` | 占位(3行) | 仅有标题建议使用iframe_target() |
| `dialogs.md` | 完整(65行) | JS对话框处理: 响应式(CDP)和主动式(JS stub)两种方案 |
| `downloads.md` | 占位(3行) | 仅有标题 |
| `drag-and-drop.md` | 占位(3行) | 仅有标题 |
| `dropdowns.md` | 占位(3行) | 仅有标题 |
| `iframes.md` | 占位(3行) | 仅有标题 |
| `network-requests.md` | 占位(3行) | 仅有标题 |
| `print-as-pdf.md` | 占位(3行) | 仅有标题 |
| `profile-sync.md` | 完整(91行) | Profile同步完整指南: 安装、API、聊天驱动流程、同步内容、CRUD |
| `screenshots.md` | 完整(18行) | 截图大小和密度说明 |
| `scrolling.md` | 占位(3行) | 仅有标题 |
| `shadow-dom.md` | 占位(3行) | 仅有标题 |
| `tabs.md` | 完整(70行) | 标签页管理: CDP与UI自动化的分工 |
| `uploads.md` | 占位(1行) | 仅有标题 |
| `viewport.md` | 占位(3行) | 仅有标题 |

17个文件中有5个内容完整，12个是占位/stub。这说明Interaction Skills是一个**增长中的知识库**，但大部分内容尚未编写完成。

完整文件的深度分析:

**connection.md** — 分析了omnibox popup问题(Chrome刚启动时唯一可见的page target)，提供了完整的启动序列模式，以及如何在macOS上通过AppleScript将Chrome带到前台。

**dialogs.md** — 详细描述了两种处理JS弹窗的方案:
- 响应式(CDP级别): 通过 `Page.handleJavaScriptDialog` 处理，不注入JS，不可被反机器人检测
- 主动式(JS注入): 通过覆盖 `window.alert/confirm/prompt` 防止弹窗，但可被反机器人检测
- 特别处理: `beforeunload` 事件的专项处理

**profile-sync.md** — 完整的Profile同步指南，包括:
- `profile-use` CLI工具的安装
- 5个Python API函数的用法: list_cloud_profiles, list_local_profiles, sync_local_profile, start_remote_daemon, stop_remote_daemon
- 聊天驱动的交互流程(让LLM在操作前咨询用户)
- Cookie-only同步的说明
- 云Profile的CRUD操作
- 3个陷阱(默认代理阻断某些目标、建议专用工作Profile、profile-use版本要求)

**tabs.md** — 核心洞察: CDP的标签页顺序不等于用户在Chrome中看到的标签页顺序。提供了CDP方案(跨平台)和平台UI方案(macOS上的AppleScript)的分工建议。

### 2.11 环境变量系统 (非记忆配置)

项目使用环境变量进行配置，环境变量的加载路径有优先级:

**加载顺序** (`helpers.py`第18-23行):
```python
def _load_env():
    paths = [REPO_ROOT / ".env", AGENT_WORKSPACE / ".env"]
    for p in paths:
        if not p.exists(): continue
        _load_env_file(p)
```

**关键环境变量一览**:

| 变量名 | 作用 | 默认值 | 文件 |
|--------|------|--------|------|
| `BU_NAME` | 守护进程命名空间 | `"default"` | helpers.py:37 |
| `BH_AGENT_WORKSPACE` | Agent工作空间路径 | `REPO_ROOT/agent-workspace` | helpers.py:15 |
| `BH_DOMAIN_SKILLS` | 是否启用Domain Skills | 未设置(默认关闭) | helpers.py:161 |
| `BU_CDP_WS` | 直接指定CDP WebSocket URL | 未设置 | daemon.py:105-106 |
| `BU_CDP_URL` | 指定CDP HTTP端点URL | 未设置 | daemon.py:107-108 |
| `BU_BROWSER_ID` | 云端浏览器ID | 未设置 | daemon.py:68 |
| `BROWSER_USE_API_KEY` | Browser Use API密钥 | 未设置 | daemon.py:69 |
| `BU_AUTOSPAWN` | 自动创建云端浏览器 | 未设置(默认不自动) | run.py:120-122 |
| `BH_DEBUG_CLICKS` | 调试点击(在截图上画标记) | 未设置(默认关闭) | helpers.py:182 |
| `BH_CHROME_PATH` | 自定义Chrome二进制路径 | 未设置 | admin.py:249-250 |
| `BH_TMP_DIR` | 临时文件目录 | 系统默认 | _ipc.py:16 |
| `BH_RUNTIME_DIR` | 运行时文件目录 | BH_TMP_DIR或/tmp | _ipc.py:17 |

### 2.12 记忆系统总结表

| 维度 | 状态 | 详细说明 |
|------|------|----------|
| 记忆系统存在性 | **未实现** | 刻意不包含，设计约束明确排除 |
| 记忆架构(分层/向量检索) | **未实现** | 无分层, 无embedding, 无向量索引 |
| 记忆数据模型 | **未实现** | 无Memory类/字段/类型定义 |
| 记忆写入机制 | **未实现** | 无程序化写入 |
| 记忆检索策略 | **未实现** | 无语义搜索/关键词/衰减/评分 |
| Agent间记忆共享 | **未实现** | 项目中无Agent概念 |
| 持久化方案(SQLite/向量DB) | **未实现** | 无数据库 |
| Domain Skills | **文件系统近似** | Markdown文件 + URL前缀匹配 |
| agent_helpers.py | **代码注入近似** | Python动态加载, 非数据存储 |
| Interaction Skills | **参考文档近似** | LLM手动阅读的Markdown知识库 |
| 事件缓冲(deque) | **运行时状态** | 守护进程内部, 500事件上限 |
| 版本缓存 | **运维功能** | JSON文件, 24小时TTL |

---

## 3. Agent编排分析

### 3.1 总体结论: 完全未实现

**browser-harness 没有实现任何 Agent 编排机制。** 这不是功能缺失，而是项目定位决定的 — 它是一个浏览器控制工具，不是一个Agent平台。

### 3.2 Agent定义方式

**状态: 未实现。**

项目中没有:
- YAML Agent 定义: 无 `agent.yaml`, `agent.yml` 文件或解析逻辑
- JSON Agent 定义: 无 `agent.json` 文件或 schema
- 代码注解: 无 `@agent`, `@tool`, `@task` 等装饰器
- Python类定义: 搜索所有 `.py` 文件，不存在包含 "Agent" 语义的类名
- TOML配置: pyproject.toml 中没有 Agent 相关配置段

### 3.3 Agent注册表/发现机制

**状态: 未实现。**

项目中没有:
- Agent 注册表: 无 `AgentRegistry`, `AgentCatalog` 类
- Agent 发现: 无服务发现、无插件扫描、无动态导入
- Agent 命名: 无命名服务、无命名空间管理
- Agent 索引: 无按能力/标签/领域分类的索引

### 3.4 Agent间通信/委派/协作

**状态: 未实现。**

项目中没有:
- Agent 间通信协议: 无消息传递、无事件总线、无RPC
- Agent 委派: 无 `delegate(task, to_agent)` 函数
- Agent 协作: 无协调器、无投票、无共识算法
- 主从关系: 无 master/worker、无 orchestrator/executor 模式
- 工作流: 无 DAG 定义、无流水线(pipeline)

### 3.5 动态/静态Agent创建

**状态: 未实现。**

- 不能通过API动态创建Agent
- 不能通过配置文件静态定义Agent
- 项目中"Agent" = 外部的LLM (Claude Code, Codex)
- browser-harness 是被LLM**调用**的工具，LLM不是browser-harness的一部分

### 3.6 计划-执行-评估Harness

**状态: 未实现。**

项目中没有:
- 计划模块 (Planning): 无任务分解、无步骤生成
- 执行模块 (Execution): 无步骤调度、无依赖管理
- 评估模块 (Evaluation): 无结果验证、无质量检查
- 检查点 (Checkpoint): 无中间状态保存
- 回溯 (Backtracking): 无失败回退

### 3.7 工具绑定机制深度分析

browser-harness 不将工具"绑定到Agent"，而是将Python函数"注入到执行环境"。

#### 3.7.1 注入机制

**文件**: `/tmp/agent-research/browser-harness-main/src/browser_harness/run.py`(第26行和第125行)

```python
from .helpers import *     # 导入所有helpers
# ...
exec(code, globals())     # 在包含所有helpers的全局作用域中执行代码
```

注入流程:
1. `from .helpers import *` → 导入helpers.py中所有公开函数
2. `from .admin import (...)` → 导入admin.py中指定的管理函数
3. `exec(code, globals())` → 用户代码可以直接调用这些函数

#### 3.7.2 完整的可用工具列表 (按类别)

**浏览器导航与控制** (来自 helpers.py):

```python
# 导航
goto_url(url)                              # 导航到URL (CDP Page.navigate)
new_tab(url="about:blank")                 # 创建新标签页并附加
switch_tab(target)                         # 切换到指定标签页
close_tab(target=None)                     # 关闭标签页
current_tab()                              # 获取当前标签页信息 {targetId, url, title}
list_tabs(include_chrome=True)             # 列出所有标签页
ensure_real_tab()                          # 确保当前不是内部标签页
iframe_target(url_substr)                  # 查找iframe的target ID

# 页面信息
page_info()                                # 获取 {url, title, w, h, sx, sy, pw, ph}
                                           # 或 {dialog: {type, message, ...}} 如果有弹窗

# 截图
capture_screenshot(path=None, full=False,   # 截图保存为PNG
                   max_dim=None)            # 可选最大尺寸限制

# 输入
click_at_xy(x, y, button="left", clicks=1) # 在坐标(x,y)处点击
type_text(text)                            # 通过Input.insertText输入文本
fill_input(selector, text,                  # 填充框架管理的输入框(Console/React/Vue)
           clear_first=True, timeout=0.0)  # 可选超时等待元素出现
press_key(key, modifiers=0)                # 发送按键事件
dispatch_key(selector, key="Enter",         # 在元素上派发DOM KeyboardEvent
             event="keypress")
scroll(x, y, dy=-300, dx=0)                # 鼠标滚轮滚动

# JavaScript执行
js(expression, target_id=None)             # 在页面中执行JS并返回结果

# 原始CDP
cdp(method, session_id=None, **params)     # 发送任意CDP命令

# 等待
wait(seconds=1.0)                          # 固定等待
wait_for_load(timeout=15.0)                # 等待document.readyState='complete'
wait_for_element(selector,                 # 等待元素出现在DOM中
                 timeout=10.0,
                 visible=False)            # 可选: 要求可见(in-layout, 非hidden)
wait_for_network_idle(timeout=10.0,        # 等待所有请求完成
                      idle_ms=500)

# 文件
upload_file(selector, path)                # 通过CDP DOM.setFileInputFiles上传文件

# HTTP
http_get(url, headers=None, timeout=20.0)  # 纯HTTP GET (可选择通过fetch-use代理)

# 事件
drain_events()                             # 排空CDP事件缓冲区
```

**守护进程管理** (来自 admin.py):

```python
ensure_daemon(wait=60.0, name=None, env=None)       # 确保守护进程运行 (幂等)
restart_daemon(name=None)                            # 停止守护进程
daemon_alive(name=None)                              # 检查守护进程是否存活
start_remote_daemon(name="remote",                   # 启动云端浏览器守护进程
                     profileName=None, **kwargs)
stop_remote_daemon(name="remote")                    # 停止云端浏览器守护进程
list_cloud_profiles()                                # 列出云端Profile
list_local_profiles()                                # 列出本地Profile
sync_local_profile(profile_name, browser=None,       # 同步本地Profile cookies到云端
                   cloud_profile_id=None,
                   include_domains=None,
                   exclude_domains=None)
browser_connections()                                # 列出所有活跃的浏览器连接
active_browser_connections()                         # 计数活跃连接
```

**诊断与更新** (来自 admin.py):

```python
run_doctor()                                          # 运行诊断
run_doctor_fix_snap()                                 # Snap修复指南
run_update(yes=False)                                 # 更新到最新版本
print_update_banner(out=None)                         # 打印更新提示
check_for_update()                                    # 检查是否有更新
```

**核心常量** (来自各模块):
- `NAME` (str): BU_NAME 环境变量的值, 默认 "default"
- `AGENT_WORKSPACE` (Path): 工作空间路径
- `CORE_DIR` (Path): 核心代码目录
- `REPO_ROOT` (Path): 仓库根目录

#### 3.7.3 工具扩展机制

Agent可以通过 `agent_helpers.py` 扩展工具集:

**文件**: `/tmp/agent-research/browser-harness-main/agent-workspace/agent_helpers.py`

```python
"""Agent-editable browser helpers.

Add task-specific browser primitives here. Core helpers from browser_harness.helpers
load this file when BH_AGENT_WORKSPACE points at this directory, or when this
repo's default agent-workspace exists.
"""
```

加载器 (`helpers.py`第488-503行):
```python
def _load_agent_helpers():
    p = AGENT_WORKSPACE / "agent_helpers.py"
    if not p.exists():
        return
    spec = importlib.util.spec_from_file_location("browser_harness_agent_helpers", p)
    if not spec or not spec.loader:
        return
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    for name, value in vars(module).items():
        if name.startswith("_"):
            continue
        globals()[name] = value

_load_agent_helpers()
```

分析:
- 使用 `importlib` 动态加载自定义Python模块
- 过滤掉 `_` 开头的私有变量
- 将公开变量注入到 `helpers.py` 的全局作用域
- 这意味着Agent可以在 `agent_helpers.py` 中定义任何Python函数，它们会自动在每次 `browser-harness` 调用时可用
- 这是**代码级别的工具扩展** — 通过编辑Python文件添加新函数

### 3.8 多守护进程并发 (命令行级别的"多Agent")

虽然没有Agend编排，但项目支持通过 `BU_NAME` 环境变量实现多个并发浏览器控制会话:

**使用示例** (来自 SKILL.md第46-55行):

```bash
BU_NAME=work browser-harness <<'PY'
new_tab("https://example.com")
print(page_info())
PY
```

**隔离机制**:
- 不同的BU_NAME → 不同的IPC socket文件 (`/tmp/bu-work.sock` vs `/tmp/bu-default.sock`)
- 不同的BU_NAME → 独立的守护进程
- 不同的BU_NAME → 独立的WebSocket连接到浏览器
- 这允许**同一个Chrome实例的多个并发控制会话**

对于云端浏览器，不同的BU_NAME可以有完全独立的浏览器实例:
```python
start_remote_daemon("work")         # 独立的云端浏览器1
start_remote_daemon("scraper")      # 独立的云端浏览器2
```

SKILL.md 将此描述为 "parallel sub-agents":
```
Use remote for parallel sub-agents (each gets its own isolated browser via a distinct BU_NAME)
```

但这只是进程级别的命名空间隔离，不是Agent编排。

### 3.9 守护进程生命周期管理的深度分析

#### 3.9.1 启动流程

**文件**: `/tmp/agent-research/browser-harness-main/src/browser_harness/admin.py`(第298-331行)

`ensure_daemon()` 的完整流程:

```
1. 检查守护进程是否已存活 (daemon_alive)
   1a. 如果存活, 发送 Target.getTargets 验证CDP连接健康
   1b. 如果健康, 立即返回 (快速路径)
   1c. 如果不健康 (超时/错误), 重启守护进程

2. 如果未存活, 启动新守护进程
   2a. 构建环境变量 (继承当前环境 + BU_NAME + 额外env)
   2b. 通过 subprocess.Popen 启动 daemon.py
   2c. 轮询等待 (最多wait秒, 默认60秒)
   2d. 如果启动失败且是本地Chrome模式:
       - 打开 chrome://inspect/#remote-debugging
       - 提示用户勾选复选框和点击Allow
       - 重试一次
   2e. 如果仍然失败, 抛出 RuntimeError
```

#### 3.9.2 停止流程

**文件**: `/tmp/agent-research/browser-harness-main/src/browser_harness/admin.py`(第348-423行)

`restart_daemon()` 的完整流程 (实际上是停止):

```
1. 获取守护进程身份
   1a. 调用 ipc.identify() 获取自我报告的PID
   1b. 调用 ipc.ping() 检查活性
   1c. 获取进程启动时间指纹 (_process_start_time)

2. 发送优雅关闭请求
   2a. 通过IPC连接发送 {meta: "shutdown"}
   2b. 守护进程设置 self.stop Event → serve() 循环退出

3. 等待进程退出 (最多15秒)
   3a. 每0.2秒用 os.kill(pid, 0) 探测一次 (75次循环)
   3b. 如果进程退出, 跳转到步骤5

4. PID复用安全检查 → 强制 SIGTERM
   4a. 重新调用 ipc.identify() 确认同一进程
   4b. 或比较启动时间指纹确认同一进程
   4c. 如果确认是同一个进程 (只是响应慢), 发送 SIGTERM
   4d. 如果PID已被复用 (fingerprint变更), 跳过 SIGTERM (安全保护)

5. 清理
   5a. 删除Unix socket文件或Windows port文件
   5b. 删除pid文件
```

**PID复用安全保护**: 这是 `restart_daemon()` 中最复杂的部分:

```python
# First identify() call (top of restart_daemon) returns the live PID.
# Second identify() call (right before SIGTERM) returns None — simulating
# the daemon having exited and its PID having been reused by an unrelated
# process. The function must NOT escalate to SIGTERM in that state.
identify_responses = iter([live_pid, None])
```

如果PID已被复用给另一个进程:
1. 再次调用 `identify()` 返回 None (旧守护进程不再响应)
2. 比较 `_process_start_time(daemon_pid)` 与最初记录的指纹
3. 如果指纹不同 — PID已被复用 → 跳过 SIGTERM
4. 如果指纹相同 — 同一个进程只是响应慢 → 发送 SIGTERM

`_process_start_time()` 的跨平台实现:
- Linux: 读取 `/proc/<pid>/stat` 的第22字段(starttime)
- macOS: 调用 `ps -o lstart= -p <pid>`
- Windows: 通过 ctypes 调用 `GetProcessTimes()` 获取 FILETIME 格式的创建时间
- 其他平台: 返回 None

#### 3.9.3 云端浏览器启动与关闭

启动:
```
POST https://api.browser-use.com/api/v3/browsers
  → 获得 {id, cdpUrl, liveUrl}
  → 通过 /json/version 解析 WebSocket URL
  → 设置 BU_CDP_WS + BU_BROWSER_ID 环境变量
  → ensure_daemon() 启动守护进程连接云端浏览器
```

关闭 (守护进程停止时):
```
PATCH https://api.browser-use.com/api/v3/browsers/{id}
  body: {"action": "stop"}
  → 停止云端浏览器，结束计费
```

如果守护进程启动失败，自动调用 `_stop_cloud_browser` 清理已创建的云端浏览器，防止孤儿计费。

#### 3.9.4 云端Profile管理

`sync_local_profile` 函数通过 shell 调用 `profile-use sync` CLI工具:

```python
cmd = ["profile-use", "sync", "--profile", profile_name]
if browser:
    cmd += ["--browser", browser]
if cloud_profile_id:
    cmd += ["--cloud-profile-id", cloud_profile_id]
for d in include_domains or []:
    cmd += ["--domain", d]
for d in exclude_domains or []:
    cmd += ["--exclude-domain", d]
r = subprocess.run(cmd, text=True, capture_output=True)
```

Profile是纯cookie同步 (不同步localStorage、IndexedDB、扩展数据)。

### 3.10 Agent编排总结表

| 维度 | 状态 | 详细说明 |
|------|------|----------|
| Agent定义方式 | **未实现** | 无YAML/JSON/注解/类定义 |
| Agent注册表/发现 | **未实现** | 无注册、无发现、无命名 |
| Agent间通信/委派 | **未实现** | 无通信协议、无委派机制 |
| 动态/静态Agent创建 | **未实现** | 不能创建Agent |
| 计划-执行-评估 | **未实现** | 无任务规划评估循环 |
| 工具绑定 | **函数注入** | exec(code, globals()) 注入20+函数 |
| 工具扩展 | **agent_helpers.py** | importlib动态加载自定义Python函数 |
| 会话管理 | **守护进程层面** | ensure_daemon() 自动启动/自我愈合 |
| Agent生命周期 | **未实现** | 无Agent生命周期 |
| 多会话隔离 | **BU_NAME命名空间** | 环境变量驱动的进程级隔离 |
| LLM集成方式 | **Shell heredoc** | LLM通过 `browser-harness <<'PY' ... PY` 调用 |
| 云端浏览器 | **完整支持** | start/stop/lifecycle/profile management |
| 守护进程安全关闭 | **PID复用保护** | 三层身份验证(identify + fingerprint + SIGTERM gate) |

---

## 4. Agent实现分析

### 4.1 总体结论: 没有Agent实现

**browser-harness 本身不包含任何 Agent 实现。** 在整个源代码中搜索，不存在任何 Agent 数据结构、Agent 执行循环或 Agent 推理逻辑。

### 4.2 Agent的数据结构

**状态: 未实现。**

在整个代码库中(GitHub上的 `browser-use/browser-harness`)搜索以下关键字的类定义:
- `class Agent` — 不存在
- `class BrowserAgent` — 不存在
- `class TaskAgent` — 不存在
- `class HarnessAgent` — 不存在
- `class BaseAgent` — 不存在

唯一存在的"Agent"相关代码是关于 `agent_helpers.py` 和 `agent-workspace` 路径的引用，这些引用仅涉及文件路径，不涉及数据结构。

### 4.3 Agent的执行循环

**状态: 未实现。**

没有以下任何执行循环模式:

**ReAct 模式** (Reasoning + Acting):
项目中不存在:
- `while True:` 主循环
- Thought/Action/Observation 的阶段划分
- `select_tool(observation)` 函数
- `execute_action(tool, params)` 函数
- `observe_result()` 函数

**Plan-Execute 模式**:
项目中不存在:
- `plan = decompose(task)` 函数
- `for step in plan: execute(step)` 循环
- `evaluate(result, expected)` 验证步骤
- Plan 数据结构 (步骤列表、依赖关系)

**实际的调用流程**是线性的、一次性的:

```
外部LLM → 生成Python代码 → Shell调用 browser-harness → exec() → 返回 → 进程退出
```

调用示例:
```bash
browser-harness <<'PY'
new_tab("https://example.com")
wait_for_load()
print(page_info())
PY
```

这个Python脚本:
1. 创建新标签页
2. 等待加载
3. 打印页面信息
4. → 进程退出，所有状态销毁

如果需要多步操作:
- 方案A: 将所有步骤写入一个Python脚本 (在单个exec()调用中完成)
- 方案B: 分多次调用browser-harness (每次都是新进程，由外部LLM保持上下文)

### 4.4 Agent能调用的工具列表 (最终完整清单)

以下是在 `exec(code, globals())` 环境中可用的所有函数:

#### 4.4.1 浏览器控制函数 (来自 helpers.py)

```python
# === 导航 ===
goto_url(url: str) -> dict
    """导航到URL。返回 {frameId, loaderId}。
    当BH_DOMAIN_SKILLS=1时额外返回domain_skills列表。"""

new_tab(url: str = "about:blank") -> str
    """创建新标签页并附加。返回targetId。
    总是先创建about:blank再goto，避免与attach的竞争条件。"""

switch_tab(target: str | dict) -> str
    """切换到指定标签页。接受targetId字符串或list_tabs/current_tab返回的dict。
    先取消旧标签页的标记(移除🐴前缀)，激活新标签页，附加，标记新标签页。
    返回新session ID。"""

close_tab(target: str | dict | None = None) -> None
    """关闭标签页。如果target为None，关闭当前标签页。"""

current_tab() -> dict
    """返回当前标签页信息 {targetId, url, title}。"""

list_tabs(include_chrome: bool = True) -> list[dict]
    """列出所有page类型的targets。
    每个元素: {targetId, title, url}。
    过滤条件: type=="page" 且 (include_chrome或不是internal URL)。"""

ensure_real_tab() -> dict | None
    """确保当前附着的不是内部页面(chrome://等)。
    如果有真实标签页，切换到第一个真实标签页。
    返回 {targetId, url, title} 或 None (如果没找到)。"""

iframe_target(url_substr: str) -> str | None
    """查找URL包含url_substr的第一个iframe target。
    返回targetId。"""

# === 页面信息 ===
page_info() -> dict
    """获取当前页面信息。
    返回 {url, title, w, h, sx, sy, pw, ph}
    如果JS弹窗打开，返回 {dialog: {type, message, ...}}。"""

# === 截图 ===
capture_screenshot(path: str = None, full: bool = False,
                   max_dim: int = None) -> str
    """将当前视口的PNG截图保存到path。
    默认路径: /tmp/shot.png。
    full=True: 捕获超出视口的内容。
    max_dim: 如果图片超过此尺寸，等比例缩小。"""

# === 点击 ===
click_at_xy(x: float, y: float, button: str = "left",
            clicks: int = 1) -> None
    """在CSS坐标(x,y)处发送鼠标事件。
    按钮: "left", "middle", "right"。
    组合器级别 — 自动穿透iframe/shadow DOM/cross-origin。
    如果BH_DEBUG_CLICKS=1，在截图上画红色标记。"""

# === 键盘 ===
type_text(text: str) -> None
    """通过Input.insertText输入文本。
    绕过框架事件监听器 — 用于简单情况，填写表单用fill_input。"""

fill_input(selector: str, text: str, clear_first: bool = True,
           timeout: float = 0.0) -> None
    """填充框架管理的输入框(React/Vue/Ember等)。
    1. 等待元素出现(如果timeout>0)
    2. 聚焦元素
    3. 如果clear_first: Cmd/Ctrl+A全选 + Backspace
    4. 逐字符press_key
    5. 派发input和change事件让框架感知更新
    如果元素未找到，抛出RuntimeError。"""

press_key(key: str, modifiers: int = 0) -> None
    """发送按键事件。修饰键位域: 1=Alt, 2=Ctrl, 4=Meta/Cmd, 8=Shift。
    特殊键: Enter, Tab, Backspace, Escape, Delete, Space, ArrowKey*, Home, End, PageUp/Down。"""

dispatch_key(selector: str, key: str = "Enter",
             event: str = "keypress") -> None
    """在匹配的元素上派发DOM KeyboardEvent。
    用于某些网站对合成DOM事件比原始CDP事件响应更好的情况。"""

# === 滚动 ===
scroll(x: float, y: float, dy: float = -300, dx: float = 0) -> None
    """在坐标(x,y)处发送鼠标滚轮事件。
    dy为负=向下滚动(默认-300)。
    dx为负=向左滚动。"""

# === JavaScript执行 ===
js(expression: str, target_id: str = None) -> any
    """在页面中执行JavaScript并返回结果。
    如果target_id提供，在iframe中执行。
    如果表达式包含顶层return语句，自动包装为IIFE。
    支持await Promise(通过awaitPromise参数)。
    反序列化特殊值: NaN, Infinity, -Infinity, -0, BigInt。"""

# === 原始CDP ===
cdp(method: str, session_id: str = None, **params) -> dict
    """发送任意CDP命令。
    例如: cdp('Page.navigate', url='...'), cdp('DOM.getDocument', depth=-1)。
    返回result字典。"""

# === 等待 ===
wait(seconds: float = 1.0) -> None
    """暂停seconds秒。"""

wait_for_load(timeout: float = 15.0) -> bool
    """轮询document.readyState=='complete'，直到超时。
    SPA注意: readyState在框架渲染前就为complete。"""

wait_for_element(selector: str, timeout: float = 10.0,
                 visible: bool = False) -> bool
    """轮询直到querySelector找到元素或超时。
    visible=True: 额外检查元素可见性(非display:none/hidden/opacity:0)。
    使用checkVisibility API(Chrome 105+)配合computed style回退。"""

wait_for_network_idle(timeout: float = 10.0,
                      idle_ms: int = 500) -> bool
    """等待所有网络请求完成并静默idle_ms毫秒。
    追踪inflight请求，按活跃session过滤。
    用于表单提交和SPA路由转换后。"""

# === 文件 ===
upload_file(selector: str, path: str | list[str]) -> None
    """通过CDP DOM.setFileInputFiles在文件输入上设置文件。"""

# === HTTP ===
http_get(url: str, headers: dict = None,
         timeout: float = 20.0) -> str
    """纯HTTP GET请求。
    如果BROWSER_USE_API_KEY设置，通过fetch-use代理(反检测+住宅代理)。
    否则本地urllib回退。支持gzip解压。"""

# === 事件 ===
drain_events() -> list[dict]
    """排空并返回所有缓存的CDP事件。
    守护进程缓冲最多500个事件。
    事件格式: {method, params, session_id}。"""
```

#### 4.4.2 管理函数 (来自 admin.py)

```python
# === 守护进程管理 ===
ensure_daemon(wait: float = 60.0, name: str = None,
              env: dict = None) -> None
    """确保守护进程运行(幂等)。自动修复过期守护进程、冷Chrome和缺失的Allow权限。"""

restart_daemon(name: str = None) -> None
    """停止守护进程(名称是历史原因——实际只停止)。发送{meta:shutdown}，SIGTERM保底，清理文件。"""

daemon_alive(name: str = None) -> bool
    """通过ping握手检查守护进程是否存活(防止端口复用误判)。"""

# === 远程/云端浏览器 ===
start_remote_daemon(name: str = "remote", profileName: str = None,
                    **create_kwargs) -> dict
    """创建云端浏览器并启动守护进程。
    create_kwargs('profileId', 'proxyCountryCode', 'timeout', 'customProxy',
                  'browserScreenWidth', 'browserScreenHeight', 'allowResizing',
                  'enableRecording')均以camelCase传给POST /browsers。
    返回完整浏览器dict(包含liveUrl)。"""

stop_remote_daemon(name: str = "remote") -> None
    """停止远程守护进程及其云端浏览器(PATCH stop → 结束计费, 持久化profile)。"""

# === Profile管理 ===
list_cloud_profiles() -> list[dict]
    """列出当前API key下的所有云端profile。
    返回[{id, name, userId, cookieDomains, lastUsedAt}, ...]"""

list_local_profiles() -> list[dict]
    """列出本地浏览器profile(需要profile-use CLI)。
    返回[{BrowserName, BrowserPath, ProfileName, ProfilePath, DisplayName}, ...]"""

sync_local_profile(profile_name: str, browser: str = None,
                   cloud_profile_id: str = None,
                   include_domains: list[str] = None,
                   exclude_domains: list[str] = None) -> str
    """同步本地profile的cookies到云端profile。返回cloud UUID。"""

# === 连接信息 ===
browser_connections() -> list[dict]
    """列出所有健康的浏览器连接及其附着的页面。
    返回[{name, page: {title, url}}, ...]"""

active_browser_connections() -> int
    """计数健康的浏览器连接。"""

# === 诊断 ===
run_doctor() -> int
    """只读诊断。检查Chrome运行状态、守护进程、连接、profile-use、API key。
    返回0表示健康，1表示异常。"""

run_doctor_fix_snap() -> int
    """打印用原生Chrome替换Snap Chromium的步骤。始终返回0。"""

# === 更新 ===
run_update(yes: bool = False) -> int
    """拉取最新版本并(提示后)重启守护进程。返回0成功，非0失败。"""

print_update_banner(out=None) -> None
    """每天打印一次更新提示到stderr。"""

check_for_update() -> tuple[str, str | None, bool]
    """返回(current_version, latest_version, newer_available)。"""

# === 内部(但有文档的公开API) ===
_version() -> str
    """已安装的browser-harness版本。"""

_run_doctor() -> int
    """与run_doctor相同。"""

_install_mode() -> str
    """返回"git"、"pypi"或"unknown"。"""

_browser_use(path: str, method: str, body: dict = None) -> dict
    """底层调用Browser Use API。path不含/api/v3前缀。"""
```

#### 4.4.3 核心常量 (在所有调用中可用)

```python
NAME: str = os.environ.get("BU_NAME", "default")
    """守护进程命名空间标识符。"""

AGENT_WORKSPACE: Path
    """Agent工作空间目录路径。
    可通过BH_AGENT_WORKSPACE环境变量自定义。"""

CORE_DIR: Path
    """核心包目录(src/browser_harness/)。"""

REPO_ROOT: Path
    """仓库根目录。"""
```

### 4.5 Agent的反思/自我评估

**状态: 未实现。**

项目代码中没有任何反思或自我评估逻辑:

- 无反思阶段 (reflection): 没有"执行完毕后检查结果是否正确"的代码
- 无自我评估 (self-evaluation): 没有评分、没有置信度计算
- 无验证步骤 (verification): 没有自动比较期望与实际的断言
- 无纠错循环 (error correction): 没有"如果失败则重试不同策略"的逻辑
- 无结果比较 (comparison): 没有diff或similarity check

SKILL.md 中给出了一个LLM级别的"行为指导"(第111-114行):

```
## Gotchas (field-tested)
- After every meaningful action, re-screenshot before assuming it worked.
  Use the image to verify changed state, open menus, navigation, visible
  errors, and whether the page is in the state you expected.
- Use screenshots to drive exploration. They are often the fastest way to
  find the next click target, notice hidden blockers, and decide if a selector
  is even worth writing.
```

但这完全是给外部LLM的提示词建议，不是browser-harness内部的反思代码。

### 4.6 Agent的上下文管理

**状态: 未实现。**

browser-harness 没有任何上下文管理机制:

- 无Token计数: 不追踪上下文窗口中的token数
- 无上下文压缩: 不进行summary/truncation/compression
- 无上下文摘要: 不自动生成上下文摘要
- 无上下文窗口管理: 不维护滑动窗口
- 无对话历史管理: 不存储/检索历史对话
- 无上下文注入: 不自动将Domain Skills内容注入上下文

**为什么不需要**: browser-harness 的每次调用都是一个新的Python进程。它从stdin读取代码，执行，输出结果到stdout，然后退出。所有"上下文"由调用它的外部LLM维护。

### 4.7 Agent的身份/人格/系统提示词

**状态: 未实现。**

browser-harness 本身没有定义任何Agent身份。但有一些文件起到了类似"系统提示词"的作用:

**SKILL.md** — 相当于"系统提示词" (告诉LLM如何使用browser-harness):

YAML frontmatter:
```yaml
---
name: browser
description: Direct browser control via CDP. Use when the user wants to automate,
  scrape, test, or interact with web pages. Connects to the user's already-running Chrome.
---
```

内容结构:
- "What actually works" — 最佳实践(Screenshot First, Coordinate Clicks, Bulk HTTP, etc.)
- "Design constraints" — 禁止事项(Don't add a manager layer, etc.)
- "Gotchas" — 已知陷阱(omnibox popups, CDP order vs visible order, etc.)

**AGENTS.md** — 相当于"代码规范提示词" (告诉LLM如何贡献到此项目):

```
# Code priorities
- Clarity
- Precision
- Low verbosity
- Versatility

An agent operating the harness only edits inside `agent-workspace/`:
- agent_helpers.py — task-specific browser helpers the agent adds
- domain-skills/ — skills the agent writes and reads
```

**install.md** — 安装和连接指南:

YAML frontmatter:
```yaml
---
name: browser-install
description: Install browser-harness into the current agent and connect it to a browser
  with minimal prompting.
---
```

### 4.8 外部LLM使用browser-harness的完整工作流 (隐式Agent模式)

虽然没有内置Agent, 但外部LLM使用browser-harness时可以形成以下工作流:

```
步骤1: LLM读取 SKILL.md → 了解如何使用browser-harness
步骤2: LLM读取 install.md → 了解如何安装和连接浏览器
步骤3: LLM分析用户任务 → 确定需要哪些浏览器操作
步骤4: LLM生成Python代码 → 使用browser-harness的函数
步骤5: LLM Shell调用 → browser-harness <<'PY' ... PY
步骤6: LLM读取stdout/stderr → 分析结果
步骤7: LLM决策 → 返回步骤4(继续)或结束

步骤4-7的循环构成了隐式的 ReAct 模式:
  Think: LLM分析当前状态和任务目标
  Act: LLM生成并执行 browser-harness 命令
  Observe: LLM读取输出(截图路径, page_info, 错误信息)
  Think: LLM分析观察结果, 决定下一步
```

这种模式在README和SKILL.md中有体现。例如显式的"Screenshot First"原则:

```
Screenshots first: use capture_screenshot() to understand the current page quickly,
find visible targets, and decide whether you need a click, a selector, or more navigation.
```

### 4.9 典型使用模式深度分析

#### 4.9.1 模式1: 简单单步任务

```bash
browser-harness <<'PY'
new_tab("https://news.ycombinator.com")
wait_for_load()
info = page_info()
print(f"Title: {info['title']}")
PY
```

流程: 打开HN → 等待加载 → 打印标题 → 退出

#### 4.9.2 模式2: 截图驱动探索

```bash
browser-harness <<'PY'
path = capture_screenshot("/tmp/page.png", max_dim=1800)
print(path)
PY
```

LLM查看截图 → 发现按钮在(x=300, y=150)位置 → 生成下一段代码:
```bash
browser-harness <<'PY'
click_at_xy(300, 150)
wait(1)
path = capture_screenshot("/tmp/after_click.png", max_dim=1800)
print(path)
PY
```

#### 4.9.3 模式3: 数据提取

```bash
browser-harness <<'PY'
goto_url("https://www.amazon.com/s?k=mechanical+keyboard")
wait_for_load()
wait(2)  # 动态内容加载
results = js("""
  Array.from(document.querySelectorAll('[data-component-type="s-search-result"]'))
  .map(el => ({
    asin: el.getAttribute('data-asin'),
    title: el.querySelector('h2 span')?.innerText?.trim(),
    price: el.querySelector('.a-price .a-offscreen')?.innerText,
  }))
""")
import json
print(json.dumps(results[:10], indent=2))
PY
```

#### 4.9.4 模式4: 批量HTTP (绕过浏览器)

```bash
browser-harness <<'PY'
from concurrent.futures import ThreadPoolExecutor
import json

def fetch_repo(name):
    return json.loads(http_get(f"https://api.github.com/repos/{name}"))

repos = ["browser-use/browser-use", "browser-use/browser-harness", "browser-use/cdp-use"]
with ThreadPoolExecutor(max_workers=3) as ex:
    results = list(ex.map(fetch_repo, repos))

for r in results:
    print(f"{r['full_name']}: {r['stargazers_count']} stars")
PY
```

#### 4.9.5 模式5: 云端并行子代理

```python
# 启动独立的云端浏览器
browser-harness <<'PY'
start_remote_daemon("scraper1")
start_remote_daemon("scraper2")
PY

# 并行使用
BU_NAME=scraper1 browser-harness <<'PY'
goto_url("https://site-a.com")
# ...
PY

BU_NAME=scraper2 browser-harness <<'PY'
goto_url("https://site-b.com")
# ...
PY
```

### 4.10 代码质量与测试覆盖

#### 4.10.1 测试策略

browser-harness 有相当完善的单元测试和集成测试:

**test_run.py** (241行, 16个测试):
- stdin执行代码
- 无参数/空stdin的行为
- 云端自动启动的触发条件 (API key + BU_AUTOSPAWN + 无daemon + 无local Chrome)
- BU_CDP_URL/BU_CDP_WS 阻止云端自动启动的检查
- 空字符串环境变量的处理
- 端点覆盖与daemon_alive/local_chrome的优先级

**test_helpers.py** (353行, 12个测试):
- capture_screenshot的max_dim缩略图
- Domain Skills的启用/禁用
- page_info的JS异常处理
- fill_input的完整流程测试 (聚焦、全选、输入、事件派发)
- fill_input的元素未找到异常
- wait_for_element的立即找到/超时/可见性检查
- wait_for_network_idle的空闲检测、请求追踪、会话过滤

**test_daemon.py** (296行, 8个测试):
- set_session确保启用4个domain
- target_id回退
- 单domain失败不影响其他
- set_session先禁用旧session的Network再启用新session
- 无旧session时不禁用Network
- set_session的并发执行 (disable + 4 enables 并行)
- current_tab的server端target_id传递
- 未附着时的not_attached错误

**test_admin.py** (644行, 18个测试):
- 本地/远程Chrome模式检测
- 握手超时/403需要用户操作的判断
- 守护进程端点名称发现 (共享tmpdir模式 vs BH_RUNTIME_DIR隔离模式)
- 活跃连接计数 (跳过stale/异常daemon)
- 浏览器连接返回附着页面信息
- Chrome运行检测 (Linux ps + Helium)
- Snap浏览器检测
- doctor的snap检测输出
- doctor的活跃浏览器连接输出
- doctor的长文本截断
- start_remote_daemon失败时清理创建的云端浏览器
- 云端浏览器停止的异常保护
- restart_daemon的PID复用安全:
  - identify返回None时不SIGTERM
  - SIGTERM的PID来自identify而非pid文件
  - 对预升级守护进程的兼容性处理
  - PID复用检测(指纹变更)
  - 启动时间指纹SIGTERM验证
  - 指纹变更跳过SIGTERM
- _process_start_time的稳定性测试
- _process_start_time的无效输入处理

**test_ipc.py** (108行, 7个测试):
- identify()返回正确的PID
- Boolean/非dict/零/负数PID的拒绝
- ping()的payload类型安全
- ping()对非{pong:True}的拒绝

**test_js.py** (172行, 14个测试):
- 简单表达式透传
- 顶层return的IIFE包装
- 已包装IIFE不重复包装
- 语法错误异常
- 运行时错误异常
- 无exceptionDetails的错误响应
- 字符串内的return不触发包装
- 注释内的return不触发包装
- 空白字符后的return正确包装
- unserializable值反序列化(NaN, Infinity, -Infinity, -0, BigInt)
- 原始值异常的消息提取
- Runtime.evaluate超时的上下文信息

#### 4.10.2 测试关注点

测试主要关注:
1. **正确性**: 函数产生正确的CDP命令和IPC消息
2. **安全性**: PID复用保护、token验证、类型安全
3. **健壮性**: 异常处理、优雅降级、并发安全
4. **向后兼容**: 预升级守护进程的兼容性
5. **边界条件**: 空输入、无效输入、缺失字段

### 4.11 Agent实现总结表

| 维度 | 状态 | 详细说明 |
|------|------|----------|
| Agent数据结构 | **未实现** | 无Agent类/结构体定义 |
| 执行循环(ReAct/Plan-Execute) | **未实现(隐式)** | 循环由外部LLM驱动 |
| 可用工具 | **40+函数** | helpers.py 26个 + admin.py 15个 + 常量3个 |
| 反思/自我评估 | **未实现** | SKILL.md建议截图验证，非内置 |
| 上下文管理 | **未实现** | 无Token计数/压缩/摘要 |
| 身份/系统提示词 | **有指导文档** | SKILL.md(124行) + AGENTS.md(24行) + install.md(137行) |
| 工具扩展 | **agent_helpers.py** | importlib动态加载自定义Python函数 |
| 典型调用 | **一次性脚本** | `browser-harness <<'PY' ... PY` |
| 多步任务 | **外部LLM编排** | LLM通过多次Shell调用串联 |
| Domain知识 | **Markdown文件** | LLM手动搜索/读取 |
| 测试覆盖 | **1795行测试** | 单元测试 + 集成测试，覆盖所有核心函数 |
| 代码中'agent'字样 | **仅路径引用** | agent-workspace, agent_helpers — 无Agent类 |

---

## 5. 关键发现与总结

### 5.1 项目本质: 极薄浏览器中间件

browser-harness 是当前"最薄"的浏览器自动化方案。在约1000行核心代码内实现了:
- Chrome DevTools Protocol 的完整封装
- 浏览器连接的完整生命周期管理(本地 + 云端)
- 跨平台IPC通信(POSIX Unix Socket + Windows TCP Loopback)
- 云端浏览器和Profile管理
- 100+个网站的领域知识库(Domain Skills)

它刻意**不实现**的内容:
- Agent概念和执行循环
- 记忆系统和状态持久化
- 任务规划和编排
- 会话管理和重试框架
- 配置系统和日志框架

### 5.2 与典型Agent框架的对比

| 特征 | 典型Agent框架 | browser-harness |
|------|----------------|-----------------|
| 核心代码量 | 10,000-100,000行 | ~1,000行 |
| Agent概念 | 核心抽象(first-class) | 不存在(由外部LLM承担) |
| 执行循环 | 框架内置(ReAct/Plan-Execute) | 不存在(由外部LLM驱动) |
| 工具绑定 | 框架内置(@tool装饰器/Tool类) | Python函数通过exec()注入 |
| 状态管理 | 框架内置(AgentState, Session) | 无状态(每次调用新进程) |
| 记忆系统 | 常见功能(向量检索,分层记忆) | 刻意不实现 |
| 持久化 | 常见功能(SQLite/Redis/向量DB) | 刻意不实现 |
| 编排 | 核心功能(多Agent/委派/协作) | 刻意不实现 |
| 上下文管理 | Token计数/压缩/摘要 | 无(由外部LLM管理) |
| 扩展机制 | 插件系统/注册机制 | 编辑文件(agent_helpers.py + domain-skills/*.md) |
| 通信协议 | Agent-to-Agent协议 | 无(仅Shell heredoc) |

### 5.3 设计哲学解读

browser-harness体现的设计哲学与主流Agent框架截然相反:

**主流Agent框架的假设**: Agent框架应该提供完整的Agent生命周期、工具绑定、状态管理、记忆、编排等功能。

**browser-harness的假设**: LLM本身已经足够强大。框架层不应该重复实现LLM已有的能力。框架应该只提供LLM**无法**自己做的事 — 即连接真实浏览器。一切应该在LLM层面解决。

这种哲学最清楚的表达在 README.md 第7行:
```
One websocket to Chrome, nothing between.
```

和 AGENTS.md 第1行:
```
browser-harness is a thin layer that connects agents to browsers via an editable CDP harness.
```

以及 SKILL.md 第102行:
```
Don't add a manager layer.
```

### 5.4 对LyClaw的参考价值评估

**可以直接学习借鉴的方面**:

1. **极简设计哲学**: browser-harness证明了1000行代码可以实现完整的浏览器控制。对LyClaw的启示 — 在实现记忆系统和Agent编排时，应避免过度工程化。

2. **Domain Skills的社区贡献模式**: 基于Markdown文件的领域知识库, 通过URL域名匹配检索。这个模式简单但有效。LyClaw可以借鉴这个模式作为记忆系统的一个维度。

3. **守护进程的PID复用安全**: `_process_start_time()` + `identify()` + `fingerprint` 的三层身份验证机制，是进程管理中最好的实践之一。如果有进程管理需求，可以直接借鉴。

4. **功能函数的极简设计**: helpers.py中的每个函数都是一个明确的、可独立测试的浏览器操作。这种"一个函数做一件事"的设计风格值得学习。

5. **Profile/Cookie管理**: sync_local_profile/list_cloud_profiles的API设计清晰。基于cookie的认证状态传递是一个实用的模式。

**不可以直接借鉴的方面**:

1. **记忆系统**: browser-harness完全没有记忆系统。LyClaw需要从头设计。
2. **Agent编排**: browser-harness完全没有Agent编排。LyClaw需要从头设计。
3. **Agent实现**: browser-harness没有Agent数据结构或执行循环。LyClaw需要参考LangChain、CrewAI、AutoGen等成熟的Agent框架。

### 5.5 最终研判

browser-harness 作为浏览器控制工具是**极其优秀的**。它的极简设计、完善的测试、清晰的API使它成为一个高质量的、可以信赖的浏览器自动化组件。

但作为 Agent 系统参考是**几乎无用的** — 因为它刻意不包含 Agent 系统应该包含的任何组件。对于需要实现记忆系统、Agent编排和Agent实现的LyClaw项目，必须参考其他更完整的Agent框架。

---

## 附录A: 完整文件清单

### A.1 核心源码 (src/browser_harness/)
- `/tmp/agent-research/browser-harness-main/src/browser_harness/__init__.py` — 包初始化 (2行)
- `/tmp/agent-research/browser-harness-main/src/browser_harness/run.py` — CLI入口 (129行)
- `/tmp/agent-research/browser-harness-main/src/browser_harness/helpers.py` — CDP工具集 (503行)
- `/tmp/agent-research/browser-harness-main/src/browser_harness/daemon.py` — CDP WS守护进程 (420行)
- `/tmp/agent-research/browser-harness-main/src/browser_harness/admin.py` — 运维管理 (860行)
- `/tmp/agent-research/browser-harness-main/src/browser_harness/_ipc.py` — IPC通信 (197行)

### A.2 配置与文档
- `/tmp/agent-research/browser-harness-main/pyproject.toml` — 项目配置 (28行)
- `/tmp/agent-research/browser-harness-main/.env.example` — 环境变量示例 (2行)
- `/tmp/agent-research/browser-harness-main/.gitignore` — Git忽略规则
- `/tmp/agent-research/browser-harness-main/LICENSE` — MIT许可证
- `/tmp/agent-research/browser-harness-main/README.md` — 项目说明 (74行)
- `/tmp/agent-research/browser-harness-main/SKILL.md` — LLM使用指南 (124行)
- `/tmp/agent-research/browser-harness-main/AGENTS.md` — LLM贡献指南 (24行)
- `/tmp/agent-research/browser-harness-main/install.md` — 安装与连接 (137行)

### A.3 Agent工作空间
- `/tmp/agent-research/browser-harness-main/agent-workspace/agent_helpers.py` — 可编辑辅助函数 (8行, 空)
- `/tmp/agent-research/browser-harness-main/agent-workspace/domain-skills/` — 100+个网站技能目录

### A.4 交互技能文档 (17个文件)
- `interaction-skills/connection.md` — 连接管理 (49行, 完整)
- `interaction-skills/cookies.md` — Cookies (3行, 占位)
- `interaction-skills/cross-origin-iframes.md` — 跨域iframe (3行, 占位)
- `interaction-skills/dialogs.md` — 弹窗处理 (65行, 完整)
- `interaction-skills/downloads.md` — 下载 (3行, 占位)
- `interaction-skills/drag-and-drop.md` — 拖拽 (3行, 占位)
- `interaction-skills/dropdowns.md` — 下拉菜单 (3行, 占位)
- `interaction-skills/iframes.md` — iframes (3行, 占位)
- `interaction-skills/network-requests.md` — 网络请求 (3行, 占位)
- `interaction-skills/print-as-pdf.md` — 打印PDF (3行, 占位)
- `interaction-skills/profile-sync.md` — Profile同步 (91行, 完整)
- `interaction-skills/screenshots.md` — 截图 (18行, 完整)
- `interaction-skills/scrolling.md` — 滚动 (3行, 占位)
- `interaction-skills/shadow-dom.md` — Shadow DOM (3行, 占位)
- `interaction-skills/tabs.md` — 标签页 (70行, 完整)
- `interaction-skills/uploads.md` — 上传 (1行, 占位)
- `interaction-skills/viewport.md` — 视口 (3行, 占位)

### A.5 测试文件
- `tests/conftest.py` — 测试fixtures (15行)
- `tests/__init__.py` — 测试包初始化
- `tests/unit/__init__.py` — 单元测试包初始化
- `tests/unit/test_run.py` — CLI测试 (241行, 16个测试)
- `tests/unit/test_helpers.py` — Helpers测试 (353行, 12个测试)
- `tests/unit/test_daemon.py` — 守护进程测试 (296行, 8个测试)
- `tests/unit/test_admin.py` — 管理功能测试 (644行, 18个测试)
- `tests/unit/test_ipc.py` — IPC测试 (108行, 7个测试)
- `tests/integration/__init__.py` — 集成测试包初始化
- `tests/integration/test_js.py` — JS执行集成测试 (172行, 14个测试)

### A.6 GitHub配置
- `.github/ISSUE_TEMPLATE/bug-report.yml` — Bug报告模板
- `.github/ISSUE_TEMPLATE/config.yml` — Issue配置
- `.github/ISSUE_TEMPLATE/feature-request.yml` — 功能请求模板
- `.github/VOUCHED.td` — Vouched安全扫描配置

### A.7 文档图片
- `docs/setup-remote-debugging.png` — 远程调试设置截图
- `docs/allow-remote-debugging.png` — Allow远程调试弹窗截图
- `docs/snap-linux-headless.md` — Snap Linux无头模式文档

## 附录B: 核心类/函数的代码位置索引

| 类/函数名 | 文件 | 行号 |
|-----------|------|------|
| `main()` | run.py | 77-125 |
| `cdp()` | helpers.py | 52-54 |
| `js()` | helpers.py | 435-444 |
| `page_info()` | helpers.py | 166-176 |
| `goto_url()` | helpers.py | 159-164 |
| `click_at_xy()` | helpers.py | 181-201 |
| `fill_input()` | helpers.py | 206-243 |
| `press_key()` | helpers.py | 253-262 |
| `capture_screenshot()` | helpers.py | 269-281 |
| `list_tabs()` | helpers.py | 285-292 |
| `switch_tab()` | helpers.py | 303-315 |
| `new_tab()` | helpers.py | 317-325 |
| `ensure_real_tab()` | helpers.py | 336-348 |
| `wait_for_load()` | helpers.py | 362-368 |
| `wait_for_element()` | helpers.py | 370-398 |
| `wait_for_network_idle()` | helpers.py | 400-433 |
| `http_get()` | helpers.py | 468-485 |
| `_load_agent_helpers()` | helpers.py | 488-500 |
| `class Daemon` | daemon.py | 182-356 |
| `Daemon.attach_first_page()` | daemon.py | 191-206 |
| `Daemon.start()` | daemon.py | 232-259 |
| `Daemon.handle()` | daemon.py | 261-356 |
| `serve()` | daemon.py | 359-389 |
| `get_ws_url()` | daemon.py | 104-160 |
| `ensure_daemon()` | admin.py | 298-331 |
| `restart_daemon()` | admin.py | 348-423 |
| `start_remote_daemon()` | admin.py | 518-547 |
| `stop_remote_daemon()` | admin.py | 334-345 |
| `list_cloud_profiles()` | admin.py | 478-505 |
| `sync_local_profile()` | admin.py | 560-605 |
| `run_doctor()` | admin.py | 739-789 |
| `run_update()` | admin.py | 805-860 |
| `_process_start_time()` | admin.py | 14-102 |
| `connect()` | _ipc.py | 79-89 |
| `request()` | _ipc.py | 92-102 |
| `ping()` | _ipc.py | 105-123 |
| `identify()` | _ipc.py | 126-158 |
| `serve()` (IPC) | _ipc.py | 161-186 |
| `cleanup_endpoint()` | _ipc.py | 194-197 |

---

*初版报告结束。以下是补充详细分析。*

---

## 补充详细分析

> 以下为对报告初版的进一步深入补充，涵盖之前未充分展开的技术细节。

---

### S1. 记忆系统深入补充

#### S1.1 Daemon事件缓冲区的"运行时可观测性"

`Daemon.events` (daemon.py:187) 是一个 `deque(maxlen=500)` 的CDP事件缓冲区。它**不是**持久化存储，而是一种**运行时可观测性机制**——让CLI端能够以拉取(pull)方式获取浏览器事件。

事件注入使用**猴子补丁**(monkey-patching)模式 (daemon.py:248-259):

```python
orig = self.cdp._event_registry.handle_event
async def tap(method, params, session_id=None):
    self.events.append({"method": method, "params": params, "session_id": session_id})
    if method == "Page.javascriptDialogOpening":
        self.dialog = params       # 实时更新对话框状态
    elif method == "Page.javascriptDialogClosed":
        self.dialog = None
    elif method in ("Page.loadEventFired", "Page.domContentEventFired"):
        asyncio.create_task(_silent(...))  # 页面加载时fire-and-forget标记tab
    return await orig(method, params, session_id)  # 链式调用原始handler
self.cdp._event_registry.handle_event = tap
```

关键设计模式: **代理(Proxy)模式 + 非侵入式注入**——原始handler仍被调用，tap只在事件流中插入额外的记录/状态更新逻辑。

- `self.dialog` 单独存储是因为它需要**实时查询** (通过 `{"meta":"pending_dialog"}` 触发，daemon.py:340)，不走deque的drain-consume模式
- 所有事件无条件入队，最多500个，超出时旧事件被静默丢弃——这是有意为之的内存安全边界
- `drain_events()` (helpers.py:57) 采用 **consume-all-and-clear** 模式：`out = list(self.events); self.events.clear()` (daemon.py:274-276)

#### S1.2 `wait_for_network_idle` 的"即时记忆"模式

`wait_for_network_idle()` (helpers.py:400-433) 是实现最复杂的helper之一。它维护了三个临时状态变量:

```python
inflight = set()             # 未完成请求的requestId集合
last_activity = time.time()  # 最后一次网络活动时间戳
active_session = _send({"meta": "session"}).get("session_id")  # 当前活跃CDP会话
```

事件过滤逻辑 (helpers.py:417-419):
```python
if e.get("session_id") != active_session:
    continue  # 丢弃后台tab事件——只关注当前tab
```

这个过滤至关重要：如果Agent切换了tab，旧tab上的持续轮询/SSE连接会不断产生Network事件，如果不按session_id过滤，`wait_for_network_idle` 会被旧tab的流量"毒化"而永远无法返回。

空闲判定条件 (helpers.py:430-431):
```python
if not inflight and (time.time() - last_activity) * 1000 >= idle_ms:
    return True
```

双层条件:
1. `inflight` 必须为空 (没有在途请求)
2. 从最后一次活动到现在的时间必须 >= `idle_ms` 毫秒

注意这里没有使用事件时间戳(`params.timestamp`)，而是使用本地 `time.time()`。这是因为CDP事件可能因为网络延迟而携带不准确的时间戳，本地时间戳更可靠。

测试 (`test_helpers.py:243-282`) 验证了一个关键边界：即使 `idle_ms` 已过，只要 `inflight` 非空，就不能返回 True。没有这个保护，一个简单的"检查静默时间"的实现会在请求发送后、响应收到前错误地返回。

#### S1.3 Domain Skills检索的完整语义

`goto_url()` (helpers.py:159-164) 的domain skills检索是一个**精确匹配 + 目录遍历**的模型:

```python
d = (AGENT_WORKSPACE / "domain-skills" /
     (urlparse(url).hostname or "").removeprefix("www.").split(".")[0])
```

URL到目录名的映射规则:
| URL | 提取的目录名 |
|-----|-------------|
| `https://www.amazon.com/s?k=laptop` | `amazon` |
| `https://www.zhipin.com/web/geek/jobs` | `zhipin` |
| `https://github.com/browser-use/browser-harness` | `github` |
| `https://mail.google.com/mail/u/0/` | `mail` (而非 `gmail`) |

注意: `split(".")[0]` 意味着 `www.example.co.uk` 会映射到 `www` 而非 `example`。这是一个已知局限。

结果排序: `sorted(p.name for p in d.rglob("*.md"))[:10]`
- `rglob("*.md")` 递归搜索子目录下的所有 `.md` 文件
- `sorted()` 按文件名字典序排序 (非相关性排序)
- `[:10]` 硬截断最多10个

**完全不涉及**:
- 语义搜索 (无embedding模型)
- 关键词检索 (无TF-IDF/BM25)
- 内容分析 (仅根据文件名匹配，不读取文件内容)
- 相关性排序 (纯粹字典序)
- 过期检查 (无时间戳)
- 使用频率统计 (无计数)

#### S1.4 Interaction Skills的"未完成知识库"状态

17个interaction-skills文件可分类为:

**完整可用** (5个):
- `connection.md` (49行): 完整的守护进程启动序列、tab管理代码
- `dialogs.md` (65行): Reactive和Proactive两种dialog处理模式，含完整代码
- `profile-sync.md` (91行): 安装、Python API、Chat流程、陷阱全线覆盖
- `screenshots.md` (18行): 设备像素vs CSS像素、max_dim约束
- `tabs.md` (70行): CDP vs UI自动化的选择矩阵

**仅含占位** (12个):
- `cookies.md` (3行): 仅有章节标题框架
- `cross-origin-iframes.md` (3行): 仅有一段概述
- `downloads.md` (3行): 仅有一段概述
- `drag-and-drop.md` (3行): 仅有一段概述
- `dropdowns.md` (3行): 仅有一段概述
- `iframes.md` (3行): 仅有一段概述
- `network-requests.md` (3行): 仅有一段概述
- `print-as-pdf.md` (3行): 仅有一段概述
- `scrolling.md` (3行): 仅有一段概述
- `shadow-dom.md` (3行): 仅有一段概述
- `uploads.md` (1行): 仅有一段概述
- `viewport.md` (3行): 仅有一段概述

这说明interaction-skills是一个**社区贡献驱动**的知识库，大部分技能等待社区完善。

---

### S2. Agent编排深入补充

#### S2.1 守护进程浏览器发现优先级链的完整追踪

`get_ws_url()` (daemon.py:104-160) 实现了6层回退发现:

```
第1层: BU_CDP_WS — 直接返回，不经过任何网络请求
第2层: BU_CDP_URL — HTTP端点:
  a) GET {url}/json/version → webSocketDebuggerUrl
  b) 如果404 + Chrome 147+: DevToolsActivePort fallback
第3层: 22个已知Profile路径的 DevToolsActivePort:
  a) 读取端口号 → GET /json/version
  b) 如果404 + ws_path: 直接构造 ws:// 地址
第4层: 探测默认端口 9222, 9223:
  a) GET /json/version → webSocketDebuggerUrl
第5层: 全部失败 → RuntimeError
```

22个已知Profile路径 (daemon.py:36-65) 涵盖:
- macOS: Chrome, Chrome Canary, Comet, Arc, Dia, Edge, Edge Beta/Dev/Canary, Brave
- Linux: google-chrome, chromium, chromium-browser, Edge/Beta/Dev, Flatpak (Chromium, Chrome, Brave, Edge)
- Windows: Chrome, Chrome SxS, Chromium, Edge, Edge Beta/Dev/SxS, Brave

这22个路径是**枚举所有可能的Chromium浏览器安装位置**，确保在绝大多数用户机器上都能自动发现浏览器。

#### S2.2 IPC安全模型的完整分析

`_ipc.py` 实现了双层安全:

**POSIX (Unix domain sockets)**:
```python
old_umask = os.umask(0o077)      # 确保新socket权限为0600
try:
    server = await asyncio.start_unix_server(handler, path=path)
finally:
    os.umask(old_umask)           # 恢复原umask
```
- 文件系统权限是安全边界 (只有同用户可连接)
- `os.umask(0o077)` 确保 `bind()` 创建的socket文件权限为 `0600`
- 无token验证 (POSIX socket已提供足够的进程隔离)

**Windows (TCP loopback)**:
```python
_server_token = secrets.token_hex(32)  # 64字符随机hex token
# 写入port文件: {"port": <port>, "token": <token>}
tmp = pf.with_name(pf.name + ".tmp")
tmp.write_text(json.dumps({"port": port, "token": _server_token}))
os.replace(tmp, pf)  # 原子写入防止读取半完成文件
```
- TCP loopback无权限控制 → 任何本地进程都可以连接
- 32字节随机token作为认证 (64 hex字符)
- 守护进程过滤: `if expected is not None and req.get("token") != expected: return {"error": "unauthorized"}`
- port文件使用原子写入 (`os.replace`) 防止客户端读到不完整的JSON

`identify()` 的防注入检查 (ipc.py:143-153):
```python
pid = resp.get("pid")
# type(pid) is int (not isinstance!) 拒绝 bool:
# isinstance(True, int) is True in Python
return pid if type(pid) is int and 0 < pid < (1 << 31) else None
```
拒绝 `pid=0` (信号进程组所有进程), `pid=-1` (信号所有进程), `pid=True` (Python的bool是int子类), `pid>=2^31` (超出pid_t范围)。

#### S2.3 `restart_daemon` PID复用安全的深度剖析

`restart_daemon()` (admin.py:348-423) 的安全设计是项目中最精巧的部分之一。

**三层身份验证**:

```python
# 第一层: IPC identity (最可靠)
daemon_pid = ipc.identify(name, timeout=5.0)  # 自我报告的PID
daemon_alive = daemon_pid is not None or ipc.ping(name, timeout=1.0)

# 第二层: Start-time fingerprint (独立于IPC)
daemon_start = _process_start_time(daemon_pid)  # 进程创建时间

# 第三层: 关闭后重新验证 (防PID在等待期间被复用)
verified_pid = ipc.identify(name, timeout=1.0)
same_process = verified_pid == daemon_pid or (
    daemon_start is not None
    and _process_start_time(daemon_pid) == daemon_start
)
```

**可能的场景矩阵**:

| 场景 | identify首次 | identify二次 | fingerprint比对 | 动作 |
|------|-------------|-------------|-----------------|------|
| 正常关闭 | PID | None | N/A | 仅清理文件 |
| daemon响应慢(slow shutdown) | PID | None | 相同 | SIGTERM (same process) |
| PID被复用 | PID | None | 不同 | 跳过SIGTERM (different process) |
| 预升级daemon(无pid字段) | None | N/A | N/A | shutdown IPC → 仅清理 |
| daemon已完全退出 | None | N/A | N/A | 仅清理文件 |

`_process_start_time()` 的跨平台实现 (admin.py:14-102):
- **Linux**: 读取 `/proc/{pid}/stat` 字段22 (starttime, 时钟tick单位)
- **macOS**: `ps -o lstart= -p {pid}` (绝对时间字符串)
- **Windows**: `ctypes` 调用 `GetProcessTimes()` (FILETIME, 100ns单位)
- **其他**: 返回 `None` (回退到仅使用IPC验证)

---

### S3. agent-workspace深入分析

#### S3.1 Domain Skills的整体分类体系

通过对105个domain-skills文件的抽样分析，可归纳出四大模式:

**A. API优先型** (如 GitHub:185行, YouTube:419行)
- 策略: `http_get()` 直接调用REST API，浏览器仅用于少数需要JS渲染的页面
- 特点: 完整的API endpoint文档、参数说明、JSON结构、速率限制
- 代码密度高: 大量可直接运行的 `http_get()` + `json.loads()` 代码

**B. 浏览器内API型** (如 BOSS直聘:351行)
- 策略: 通过 `js()` + `fetch()` 调用站点的内部 `/wapi/` API
- 特点: 需要浏览器会话cookies，不适合纯HTTP
- 深度: 完整的内部分析 (endpoint、参数编码表、响应结构)

**C. 截图坐标交互型** (如 TikTok:108行, LinkedIn:80+行)
- 策略: `capture_screenshot()` → 读像素 → `click_at_xy()` → 验证
- 特点: 详细的坐标操作序列、等待时间、元素检测模式
- 精度: 标注了"JS .click() 不好使，必须用 CDP click_at_xy" 等关键细节

**D. DOM提取型** (如 Amazon)
- 策略: `js()` 的 `document.querySelectorAll()` 提取结构化数据
- 特点: CSS选择器映射到数据字段、分页策略、渲染陷阱

#### S3.2 完整的Domain Skills目录清单 (按功能分类)

**中国互联网平台** (6个):
- BOSS-zhipin/ (job-search.md, chat.md, navigation.md) — 招聘
- bilibili/ (navigation.md) — 视频
- xiaohongshu/ (scraping.md) — 社交电商
- ctrip/ (hotels.md) — 旅游
- wehotel/ (hotels.md) — 酒店
- ly-com/ (hotels.md) — 旅游

**社交媒体** (5个):
- linkedin/ (invitation-manager.md) — 职场社交
- facebook/ (pages.md, groups.md) — 社交
- x/ (posting.md) — 社交
- reddit/ (scraping.md) — 论坛
- tiktok/ (upload.md) — 短视频

**电商** (8个):
- amazon/ (product-search.md)
- ebay/ (scraping.md)
- walmart/ (scraping.md)
- etsy/ (scraping.md)
- flipkart/ (shopping.md)
- shopify-admin/ (4个文件)
- alaska/ (checkout.md)
- aa/ (checkout.md)

**开发者工具** (9个):
- github/ (scraping.md, repo-actions.md)
- stackoverflow/ (scraping.md)
- vercel/ (vercel.md)
- package-registries/ (npm-pypi.md)
- npm/ (published-packages.md, download-counts.md)
- hubspot/ (private-app-webhooks.md)
- browser-use-cloud/ (cloud.md + cleanup-zombies.py)
- claude-ai/ (share-export.md + extract-share-transcript.py)
- dev-to/ (scraping.md)

**数据/学术** (11个):
- arxiv/ (scraping.md), arxiv-bulk/ (scraping.md)
- pubmed/ (scraping.md)
- crossref/ (scraping.md)
- openalex/ (scraping.md)
- open-library/ (scraping.md)
- sec-edgar/ (scraping.md)
- world-bank/ (scraping.md)
- nasa/ (scraping.md)
- fred/ (scraping.md)
- gutenberg/ (scraping.md)

**娱乐/媒体** (9个):
- youtube/ (scraping.md)
- spotify/ (scraping.md)
- soundcloud/ (scraping.md)
- steam/ (scraping.md)
- imdb/ (scraping.md)
- goodreads/ (scraping.md)
- letterboxd/ (scraping.md)
- metacritic/ (scraping.md)
- genius/ (scraping.md)

**其他** (约20个): 包括 job-boards, gmail, producthunt, eventbrite, news-aggregation, polymarket, tradingview 等

共97个目录，105个 `.md` 文件，2个 `.py` 辅助脚本。

---

### S4. SKILL.md 与 AGENTS.md 完整内容分析

#### S4.1 SKILL.md 作为"Agent提示词工程"的详细分析

SKILL.md (124行) 的完整结构:

| 章节 | 行号 | 功能 |
|------|------|------|
| YAML frontmatter | 1-4 | Skill注册元数据 (name=browser) |
| 概述 + Domain Skills开关说明 | 6-12 | 核心概念和BH_DOMAIN_SKILLS环境变量 |
| Usage | 14-25 | heredoc语法标准、new_tab vs goto_url |
| Tool call shape | 27-35 | 工具调用模板 |
| Remote browsers | 38-59 | 远程浏览器完整教程 |
| Interaction skills | 61-80 | 17个技能文件的索引清单 |
| What actually works | 82-93 | 10条最佳实践(实战验证) |
| Design constraints | 95-102 | 6条负面约束(项目设计红线) |
| Gotchas (field-tested) | 104-115 | 11条实战陷阱 |
| Domain skills (opt-in) | 117-123 | 领域技能开关和贡献说明 |

**核心设计原则提取**:

"10条最佳实践" (SKILL.md:84-93) 是browser-harness使用方法论的精华:
1. Screenshots first — 视觉优先于代码
2. Coordinate clicks — 坐标点击穿透所有DOM边界
3. Bulk HTTP — 绕过浏览器提速100倍
4. wait_for_load() after goto — 加载后等待
5. ensure_real_tab() — 确保正确tab
6. page_info() — 最简活性检查
7. js() for DOM reads — 截图无法替代时的回退
8. click_at_xy for iframe sites — 坐标点击穿透iframe
9. Auth wall → stop and ask — 安全第一
10. Raw CDP for everything else — 终极兜底

"6条负面约束" (SKILL.md:97-102):
1. Coordinate clicks default — 永远优先坐标而非选择器
2. Connect to user's Chrome — 永远不启动自己的浏览器
3. cdp-use only for send_raw — 永远不类型化封装CDP
4. run.py stays tiny — 永远不添加argparse/子命令
5. Core helpers stay short — 永远不在核心添加领域代码
6. Don't add a manager layer — 永远不加管理层

#### S4.2 AGENTS.md 的完整语义分析

AGENTS.md (24行) 定义了四类信息:

1. **项目定位** (第1行): "browser-harness is a thin layer that connects agents to browsers via an editable CDP harness."

2. **代码规范** (第3-7行):
   ```
   # Code priorities
   - Clarity      (清晰性: 代码意图一目了然)
   - Precision    (精确性: 边界条件处理完备)
   - Low verbosity (低冗余: 代码行数尽量少)
   - Versatility   (通用性: 代码覆盖多场景)
   ```

3. **文件职责分派** (第10-21行):
   - `admin.py` — daemon lifecycle, diagnostics, updates, profile management
   - `daemon.py` — the long-lived middleman process
   - `helpers.py` — CDP wrapper and core browser primitives
   - `run.py` — the browser-harness CLI
   - `SKILL.md` — tells agents how to USE the harness
   - `install.md` — tells agents how to INSTALL it

4. **代码边界** (第19-21行):
   ```
   An agent operating the harness only edits inside agent-workspace/:
   - agent_helpers.py — task-specific browser helpers the agent adds
   - domain-skills/ — skills the agent writes and reads
   ```
   这是最重要的边界声明: Agent只能编辑 `agent-workspace/` 下的文件，不能修改核心代码。

5. **贡献准则** (第24行):
   ```
   # Contributing
   Consider what is really needed. Prefer the smallest diff that fixes the bug.
   ```

---

### S5. pyproject.toml 依赖深入分析

#### S5.1 四个直接依赖的技术角色

**`cdp-use==1.4.5`** — CDP WebSocket客户端封装:
- 在 daemon.py 中以 `from cdp_use.client import CDPClient` 导入
- `CDPClient` 封装了: WebSocket连接管理、CDP消息的ID序列化、请求-响应Promise匹配、自动重连
- browser-harness 仅使用其 `send_raw(method, params, session_id)` 方法 — 这是刻意的约束
- 锁死版本 `==1.4.5`: CDP协议的行为精确性至关重要，即使是小版本变更也可能导致行为差异

**`fetch-use==0.4.0`** — HTTP代理客户端:
- 仅在 `BROWSER_USE_API_KEY` 设置时使用 (helpers.py:475-478)
- 提供: 机器人检测规避、住宅IP代理、自动重试
- 导入失败时静默回退到标准库 `urllib` — 零硬依赖
- 典型的"能力增强但非必需"的依赖

**`pillow==12.2.0`** — 图像处理:
- 用于 `capture_screenshot(max_dim=...)` 的图片缩略图 (helpers.py:276-280)
- 用于 `BH_DEBUG_CLICKS=1` 时的点击点可视化标注 (helpers.py:184-195)
- 在 `conftest.py` 中用于生成测试用PNG图像
- 锁死版本是为确保图像处理行为的一致性

**`websockets==15.0.1`** — WebSocket协议:
- 是 `cdp-use` 的传递依赖，但被**显式声明为直接依赖**
- browser-harness 本身不直接导入 `websockets`
- 显式声明确保版本锁定不依赖传递依赖解析

#### S5.2 版本锁定的设计意图

所有四个依赖使用 `==` (精确锁定) 而非 `>=` (最小版本)。这反映了:
1. 薄中间件的哲学: 行为可复现性 > 依赖灵活性
2. 避免"依赖漂移": 新版本引入的默认行为变更不会破坏中间件
3. CDP协议的敏感性: WebSocket行为、二进制帧处理等关键路径不能有意外变化

#### S5.3 Python版本约束

`requires-python = ">=3.11"` — 选择3.11是因为:
- `asyncio` 的改进 (TaskGroup in 3.11)
- `importlib.metadata` 的标准库集成 (用于版本检查)
- 类型注解的改进

---

### S6. 测试用例完整分析

#### S6.1 测试设计中的工程关切

通过分析88个测试的主题分布，可以揭示项目的核心工程关切:

| 关切类别 | 测试数量 | 占比 | 涉及文件 |
|----------|----------|------|----------|
| PID/进程安全 | 11个 | 12.5% | test_admin.py, test_ipc.py |
| CDP会话管理 | 8个 | 9.1% | test_daemon.py |
| 网络/加载检测 | 5个 | 5.7% | test_helpers.py |
| 并发/并行 | 2个 | 2.3% | test_daemon.py |
| 表单/输入处理 | 4个 | 4.5% | test_helpers.py |
| 云端/远程 | 5个 | 5.7% | test_admin.py |
| JavaScript执行 | 14个 | 15.9% | test_js.py |
| IPC消息处理 | 10个 | 11.4% | test_ipc.py |
| CLI启动逻辑 | 8个 | 9.1% | test_run.py |
| 诊断/健康检查 | 7个 | 8.0% | test_admin.py |
| Domain Skills | 2个 | 2.3% | test_helpers.py |
| 截图 | 3个 | 3.4% | test_helpers.py |
| 其他 | 9个 | 10.2% | 各文件 |

**关键发现**:
- PID/进程安全是最大的单一关切 (12.5%的测试) — 反映了项目对"不杀错进程"的严重关注
- JavaScript执行是第二大关切 (15.9%) — 因为 `js()` 是最常用的核心原语
- IPC消息安全 (11.4%) — 反映了对"接收非信任载荷"的防御性编程态度
- 并发正确性(2.3%) 虽然测试少，但每个测试都极其精细 (使用自定义 `_ConcurrencyProbeCDP` 验证 `asyncio.gather` 真并行)

#### S6.2 代表性测试的深度解读

**test_admin.py 第537-579行** — "slow shutdown" 场景的PID安全保护:

这是最复杂的测试之一。模拟的场景是: 守护进程的 `serve()` 先拆除IPC socket，然后进程执行缓慢的远程清理(如云端 `stop` PATCH)。在这个窗口中:
- `identify()` 返回 None (socket已拆除)
- 但进程仍在运行(只是一个慢进程)
- `_process_start_time()` 指纹未变 → 证明还是同一个进程
- SIGTERM 应该被发送 (因为是同一个进程只是慢)

如果指纹变了 → PID被新进程复用 → 跳过SIGTERM (安全保护)。

**test_daemon.py 第142-197行** — `set_session` 的并发性验证:

使用自定义 `_ConcurrencyProbeCDP` 类，其特征是:
```python
class _ConcurrencyProbeCDP:
    def __init__(self):
        self.in_flight = 0
        self.max_concurrent = 0
        self.release = None  # asyncio.Event

    async def send_raw(self, method, params=None, session_id=None):
        self.in_flight += 1
        self.max_concurrent = max(self.max_concurrent, self.in_flight)
        await self.release.wait()  # 阻塞直到测试释放
        self.in_flight -= 1
        return {}
```

通过阻塞所有CDP调用直到达到峰值并发，然后断言 `max_concurrent == 5` (1个Network.disable + 4个Domain.enable)。如果代码是串行 `await` 的，`max_concurrent` 会是1而不是5。

#### S6.3 测试中暴露的设计原则

1. **安全性优先于便捷性**: PID安全检查、token验证、payload类型检查等测试占比高，但这些功能在正常使用中几乎不被触发
2. **正确性优先于性能**: 测试关注并发正确性(是否真并行)而非性能数字
3. **边界条件全覆盖**: 空输入、缺失字段、类型错误、超时等边界都有测试
4. **向后兼容**: 有专门测试验证对旧版本守护进程(pre-upgrade)的兼容性

---

### S7. 跨平台兼容性分析

#### S7.1 平台差异处理

| 维度 | POSIX (Linux/macOS) | Windows |
|------|---------------------|---------|
| IPC传输 | AF_UNIX socket (文件系统权限隔离) | TCP 127.0.0.1 loopback (token认证) |
| 端点文件 | `/tmp/bu-<NAME>.sock` | `/tmp/bu-<NAME>.port` (含port+token的JSON) |
| 进程分离 | `start_new_session=True` (setsid) | `CREATE_NEW_PROCESS_GROUP \| CREATE_NO_WINDOW` |
| PID验证 | `/proc/<pid>/stat` (Linux) / `ps` (macOS) | `GetProcessTimes` via ctypes |
| 终端编码 | N/A | `sys.stdout.reconfigure(encoding="utf-8")` (防止emoji导致UnicodeEncodeError) |
| Chrome Profile | `~/.config/google-chrome` / `~/Library/...` | `%LOCALAPPDATA%\Google\Chrome\User Data` |
| 全选修饰键 | Ctrl (modifier=2) | Ctrl (modifier=2) |
| 全选修饰键 (macOS) | Cmd (modifier=4) | N/A |

`fill_input()` 的全选快捷键处理 (helpers.py:230):
```python
mods = 4 if sys.platform == "darwin" else 2  # Cmd vs Ctrl
```

#### S7.2 Stdout编码的Windows特化

`run.py:6-8`:
```python
if hasattr(sys.stdout, "reconfigure"):
    try:
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    except Exception: pass
```

这是专门为Windows添加的: Windows默认stdout编码是cp1252，无法编码 🐴 (tab标记emoji)。`hasattr` guard确保此代码在较老Python版本上不会崩溃。

---

### S8. 依赖环境变量的完整功能矩阵

环境变量控制着browser-harness几乎所有的行为方面:

```
┌─────────────────────────────────────────────────────────────┐
│                    环境变量功能矩阵                            │
├───────────────────┬─────────────────────────────────────────┤
│ BU_NAME           │ 守护进程命名空间 (default: "default")      │
│ BH_AGENT_WORKSPACE│ agent-workspace路径                       │
│ BH_DOMAIN_SKILLS  │ 启用domain skills (= "1"时)               │
│ BH_DEBUG_CLICKS   │ 启用点击调试叠加 (= "1"时)                 │
│ BH_CHROME_PATH    │ 覆盖doctor的Chrome路径探测                  │
│ CHROME_PATH       │ 同上(别名)                                 │
│ BH_TMP_DIR        │ 临时文件目录(screenshots/log)              │
│ BH_RUNTIME_DIR    │ 运行时文件目录(sock/pid/port)               │
│ BU_CDP_WS         │ 远程CDP WebSocket URL (优先级最高)          │
│ BU_CDP_URL         │ 远程CDP HTTP端点                          │
│ BU_BROWSER_ID      │ 云端浏览器ID                              │
│ BU_AUTOSPAWN       │ 自动启动云端浏览器 (= "1"时)               │
│ BROWSER_USE_API_KEY│ Browser Use API密钥                      │
│ DISPLAY/WAYLAND_DISPLAY │ 检测本地GUI (Linux)                  │
└───────────────────┴─────────────────────────────────────────┘
```

注意: 没有 `BH_MEMORY_DIR`、`BH_SESSION_DB` 或任何记忆/状态持久化相关的环境变量 — 再次确认项目刻意不实现记忆系统。

---

### S9. 对LyClaw记忆系统设计的启示

#### S9.1 browser-harness模式的局限性

browser-harness的"零记忆"设计是成功的，因为它的定位是**极薄中间件**。但对LyClaw来说，需要记忆系统的原因是根本不同的:

| 需求 | browser-harness | LyClaw |
|------|-----------------|--------|
| 跨调用状态保持 | 不需要(LLM自己保持) | **需要**(多轮对话) |
| 跨会话知识复用 | Domain Skills (文件系统) | **需要**(语义搜索) |
| 用户偏好记忆 | 不需要 | **需要** |
| 任务历史 | 不需要 | **需要** |
| Agent间知识共享 | 文件系统 | **需要**(共享记忆空间) |
| 错误模式学习 | 不需要 | **需要** |

#### S9.2 值得借鉴的模式

1. **Domain Skills的文件组织**: `domain-skills/<site>/<task>.md` 的分层目录结构简单有效。LyClaw可以借鉴 `memories/<domain>/<type>/` 的组织方式。

2. **Opt-in开关**: `BH_DOMAIN_SKILLS=1` 的模式可用于控制记忆功能的启用级别 (如 `LYCLAW_MEMORY_LEVEL=basic/full`)。

3. **三层架构的映射**: browser-harness的核心/agent-workspace/skills三层，可以映射为 LyClaw 的 **核心引擎/用户空间/记忆存储**。

4. **渐进式知识积累**: "Agent执行 → 学习 → 写入skill文件 → 下次复用" 的模式可以适配为 "Agent执行 → 记录经验 → 写入记忆库 → 下次检索"。

5. **文件即API**: Domain Skills的Markdown文件同时是人类可读和LLM可读的，这种"双重用途"的设计值得在记忆系统中借鉴 (如记忆条目的可读性 vs 可检索性)。

---

*报告结束。总分析文件数: 130+, 分析源代码总行数: ~2111行(核心) + ~1829行(测试) + ~354行(交互技能完整文件) + ~105个domain-skills + ~9行(agent_helpers)。*
