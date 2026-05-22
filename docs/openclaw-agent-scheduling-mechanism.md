# OpenClaw是怎么调度Agent的（大白话详细版）

---

## 一、Agent配置从哪来

OpenClaw启动的时候，会去找项目根目录下的 `openclaw.json` 文件。这个文件里有个 `agents.list` 数组，每个元素就是一个Agent的配置，比如这个Agent叫什么名字、用哪个模型、工作目录在哪、能不能生成子Agent、最多生成几个、能用哪些工具等等。

OpenClaw把这个文件读完以后，整个配置对象就放在一个变量里常驻内存了。之后**任何时候**想知道有哪些Agent、某个Agent的详细配置是什么，都是直接从这个内存变量里读。没有任何数据库查询，没有任何文件读取，就是一个简单的 `Array.find()` 操作。

比如说前端传了个 agentId = "code-reviewer"，它就在内存里 `agents.find(a => a.id === "code-reviewer")` 一下就拿到了，全程零IO。

这里有一个值得注意的点：每个Agent的配置不是孤立的，它是在**全局默认值的基础上叠加自己的特定配置**。比如全局默认 `maxSpawnDepth` 是1，某个Agent自己的配置里写了2，那就用2。如果Agent自己没写，就用全局默认值。如果全局默认值也没有，就用系统硬编码的默认值。这个三级合并的顺序是：Agent特定配置 > 全局默认配置 > 系统硬编码默认值。

Agent的工作目录也是三级降级：先看Agent自己有没有配置workspace路径，没有就看全局默认workspace，再没有就在系统状态目录下自动生成一个 `workspace-{agentId}` 目录。对于非默认Agent（不是主Agent），如果全局配了workspace，会自动在那个workspace下面创建一个以agentId命名的子目录，这样不同Agent的文件不会互相打架。

---

## 二、引导文件（AGENTS.md这些）怎么加载的

### 2.1 有哪些引导文件

每个Agent的workspace目录下，有8个标准文件在扮演"引导"的角色。这8个文件的名字和用途是这样的：

- **AGENTS.md**：告诉Agent它是谁、它的职责是什么、它能做什么不能做什么。这是最重要的引导文件。
- **SOUL.md**：Agent的"灵魂设定"，也就是人设和性格。比如"你是一个严谨的代码审查员"、"你说话风格简洁直接"之类的。
- **TOOLS.md**：告诉Agent它有哪些工具可以用，每个工具怎么用。
- **IDENTITY.md**：Agent的身份信息，比如名字、头像、所属团队等。
- **USER.md**：用户偏好设置，比如用户喜欢什么样的回复风格、有什么特殊要求。
- **HEARTBEAT.md**：心跳检查专用的引导文件，只在心跳检测的场景下使用。
- **BOOTSTRAP.md**：一次性引导文件，只在workspace第一次初始化时使用，初始化完成后就不再读取了。
- **MEMORY.md**：持久化记忆文件，Agent可以把需要长期记住的信息写到这里。

### 2.2 文件级缓存：怎么避免重复读磁盘

OpenClaw对每个文件的读取做了一层非常巧妙的缓存。

它不是简单地"记住上次读的内容"，而是给每个文件计算了一个**身份标识**。这个身份标识由四个东西拼起来：文件的设备号（dev）、inode号、文件大小（字节数）、最后修改时间（毫秒级）。拼出来大概是这样一个字符串：`/path/to/AGENTS.md|2049:123456:4096:1716336000000`。

这个身份标识的妙处在于：如果文件内容没变，这四个值就不会变（除非有人用 `touch` 命令改了mtime但不改内容，不过这种情况极少见）。反过来，如果文件被替换了（inode变了）、或者内容变了（size变了），身份标识就会变，缓存就会自动失效。

每次读文件的时候，OpenClaw会先拿到文件的stat信息算出身份标识，然后去缓存里找。如果缓存里有，且身份标识完全匹配，就直接返回缓存内容——**零磁盘读取**。如果身份标识不匹配，说明文件变了，就读磁盘并更新缓存。

而且读文件的时候有安全边界检查：必须确保文件在workspace目录范围内，不能通过 `../../` 这样的路径跳出workspace去读系统文件。单个文件最大限制2MB，超过就截断，防止恶意大文件撑爆内存。

### 2.3 会话级缓存：同一个session里怎么避免反复读

在文件级缓存之上，还有一层会话级缓存。

一个session（对话）可能包含很多个turn（来回对话）。每次turn都可能需要注入引导内容。如果每个turn都去重新读一遍8个文件，即使文件没变也要做8次stat系统调用。

所以OpenClaw做了一个**按sessionKey索引的缓存**。每个session第一次读引导文件时，把所有文件的内容快照（包括文件名、路径、内容、是否缺失）保存下来。后续同一个session的每个turn，先去比较：工作目录有没有变？8个文件的快照是不是和上次完全一样（逐字段比对name/path/content/missing）？如果全部一样，就直接用缓存。

因为每次turn都会重新从文件级缓存加载（文件级缓存本身利用了身份标识做了快速路径），实际上的流程是：加载文件（命中L1缓存，零IO）→ 比较快照（纯内存比较）→ 如果没变化就用L2缓存。所以引导加载的开销极小。

### 2.4 不是所有session都读全部文件

引导文件的加载还有一个细节：不同类型的session，读的文件数量不一样。

- **主Agent的session**：8个文件全读。因为主Agent需要完整的上下文来理解自己是谁、用户是谁、能用什么工具、有什么记忆。
- **子Agent的session**：只读5个（AGENTS.md、TOOLS.md、SOUL.md、IDENTITY.md、USER.md），不读BOOTSTRAP.md、HEARTBEAT.md、MEMORY.md。因为子Agent是一个"工人"角色，它不需要知道自己是怎么被初始化的（BOOTSTRAP），不需要心跳检查（HEARTBEAT），也不需要访问主Agent的持久化记忆（MEMORY）。
- **定时任务的session**：和子Agent一样只读5个，原因也类似——定时任务是自动执行的不需要心跳和记忆。

---

## 三、什么时候把引导内容注入给AI

引导文件读是一回事，什么时候把这些内容塞到AI的系统提示里是另一回事。OpenClaw有三种策略，由配置项 `contextInjection` 控制。

### 3.1 always模式

最简单的模式：每次AI开始新的一轮对话时，都把引导文件的内容注入到系统提示里。不管这是全新对话还是上一轮的续接，一律注入。这个模式的优点是简单可靠，缺点是多轮对话中引导内容会反复出现，占用token。

### 3.2 continuation-skip模式

这个模式更聪明一点：只在**首次对话**时注入引导内容，续接时跳过。

那怎么判断当前是首次对话还是续接呢？OpenClaw会去翻对话记录的JSONL文件。

JSONL文件是OpenClaw存储对话记录的格式，每一行是一个JSON对象，代表一轮对话中的一条消息或事件。OpenClaw只读这个文件的**尾部256KB**，最多取最后500条记录。然后从后往前扫：

- 如果遇到一条记录，它的 `type` 是 `"custom"`，`customType` 是 `"openclaw:bootstrap-context:full"`，说明之前已经注入过引导内容了。
- 但是！如果在这条标记**之后**出现了一条 `type` 为 `"compaction"` 的记录（compaction是对话压缩，会把旧内容总结掉），说明标记虽然存在，但引导内容可能已经被压缩掉了，这时候需要**重新注入**。
- 如果标记存在且没有被compaction覆盖，就返回true，表示本轮不需要再次注入。

这个算法的几个要点：
- 只读尾部256KB，不读整个文件，不管文件多大都是常数时间复杂度。
- 最多500条记录防恶意日志。
- 符号链接的session文件直接返回false（可能正在迁移中，保守处理）。

### 3.3 never模式

永远不注入引导内容。适用于那些完全不需要引导上下文就能工作的场景。

### 3.4 lightweight模式

除了上面的注入策略，还有一个叫 `lightweight` 的模式，决定引导文件**本身**加载哪些：

- 如果当前是心跳检测（heartbeat）场景：只加载HEARTBEAT.md一个文件。
- 如果是定时任务或普通场景的lightweight：一个文件都不加载，返回空。

这个设计的目的很明确：心跳检测不需要完整的引导，它只需要知道"怎么做心跳检查"这一件事。

---

## 四、子Agent是怎么生成出来的（这是最关键的部分）

当主Agent在对话中决定"这件事需要别人帮忙"，它就会调用 `sessions_spawn` 工具来生成一个子Agent。

生成一个子Agent不是简单的事，OpenClaw设置了一连串14道检查，任何一道不通过就拒绝生成。这14道检查组成了一个**责任链**——每道检查独立判断，不通过就直接返回拒绝原因，不会继续往后走。

### 第1道：任务描述不能为空

调用 `sessions_spawn` 时必须传一个 `task` 参数，描述这个子Agent要干什么。如果 `task` 是空字符串或者根本没传，直接拒绝。这是最基本的合理性检查。

### 第2道：任务名格式验证

`taskName` 参数（如果传了）必须符合格式要求：只能包含字母、数字、连字符、下划线，最长100个字符。这是为了确保任务名可以作为文件名或标识符使用，不会引起奇怪的bug。

### 第3道：AgentID格式验证

目标Agent的ID也必须符合格式要求：字母数字加连字符下划线，最长64个字符。同样是为了确保ID可以作为安全的标识符。

### 第4道：目标Agent必须存在

你指定的targetAgentId，必须在配置文件的 `agents.list` 里真实存在。OpenClaw会拿这个ID去已配置的Agent列表里找，找不到就拒绝。这防止了Agent ID的猜测攻击——你不能随便编一个Agent ID就去调用。

### 第5道：模式兼容性检查

`session` 模式和 `run` 模式的区别：`session` 模式会创建一个持久化的子会话，子Agent可以多轮对话；`run` 模式是一次性的，执行完任务就结束。

但是定时任务（cron）不能用 `session` 模式。为什么？因为定时任务本身是无状态的，它创建的子会话没有"主人"来后续管理，会产生孤儿session。所以如果调用方是cron且请求session模式，直接拒绝。

### 第6道：深度限制（防套娃）

这是**整个调度系统最核心的安全机制之一**。

每个session都有一个深度值，叫 `spawnDepth`。主Agent自身的深度是0。主Agent生成的子Agent深度是1。深度为1的子Agent如果又生成了子Agent，那孙Agent的深度就是2。以此类推。

OpenClaw默认的 `maxSpawnDepth` 是1。这意味着主Agent可以生成子Agent，但子Agent**不能再生成孙Agent**。整个Agent生成树被限制为只有一层深度——一个中心节点（主Agent）周围最多挂一圈子Agent，形成星形拓扑。

那深度是怎么算出来的呢？不是简单地看sessionKey字符串里有几个 `subagent`，而是有三条路径同时追溯：

- **路径一**：从sessionKey本身的结构解析。OpenClaw的sessionKey有固定格式，比如 `agent:code-reviewer:subagent:uuid-xxx`，从格式就能看出这是一个子Agent。
- **路径二**：从session store（一个JSON文件）里读该session的 `spawnDepth` 字段。这个字段是子Agent创建时写入的。
- **路径三**：如果session store里没有直接存 `spawnDepth`，就沿 `spawnedBy` 字段往上追。`spawnedBy` 记录了"谁生成了我"。追到父session后递归查找，直到找到有 `spawnDepth` 的记录或者追到最顶层。

三条路径的结果会交叉验证，而且用了一个 `visited` Set来防止循环引用（如果A的spawnedBy指向B、B的spawnedBy又指向A，不会死循环）。

### 第7道：并发限制（防撑爆）

一个session同时能有多少个活跃子Agent？默认最多5个。超过这个数就拒绝新的生成请求。

"活跃"的定义是：在 `subagentRuns` 这个全局Map中，该session的run记录还没有结束（`endedAt` 为空）。已经结束的子Agent不占并发配额。

这个限制和深度限制一起构成了**二维限流**：深度控制树的高度（默认只能长一层），并发控制每个节点的出度（每个Agent最多同时有5个孩子）。这样就保证了无论Agent怎么生成子Agent，整个系统的资源消耗是有上限的。

### 第8道：requireAgentId检查

session模式下有一些特殊处理逻辑，主要是处理"没有明确指定目标Agent ID"的情况。如果请求者没传agentId但系统要求必须传，就拒绝。

### 第9道：目标策略（白名单）

不是随便一个Agent都能生成任意其他Agent的。谁能生成谁，由 `allowAgents` 配置决定。

- **没配 allowAgents**：默认只有自己生成自己。比如code-reviewer只能生成code-reviewer类型的子Agent。
- **配了 `["*"]`**：可以生成所有**已在配置文件中注册**的Agent。注意，即使配了通配符，也只能生成配置里有的Agent，不能随便编一个不存在的AgentID。
- **配了 `["agent-a", "agent-b"]`**：只能生成列表里指定的Agent，且这些Agent必须在配置文件中存在。

这个白名单机制保证了一个Agent只能在自己的权限范围内调兵遣将，不能越权。

### 第10道：沙箱兼容性

如果请求使用沙箱模式（sandbox），但目标Agent的沙箱环境还没启动或不可用，就拒绝。

### 第11道：能力裁剪

这是根据深度自动计算角色和权限的机制，不需要人工配置。

- **深度为0**（主Agent自己）→ 角色是 `main`（主），控制范围是 `children`（能管理子Agent），能生成子Agent。
- **深度在0和maxSpawnDepth之间** → 角色是 `orchestrator`（编排者），控制范围是 `children`，还能继续生成子Agent。
- **深度等于或超过maxSpawnDepth** → 角色是 `leaf`（叶子节点），控制范围是 `none`（什么都控制不了），**不能再生成任何子Agent**。

这个设计的巧妙之处在于：权限是**自动随深度衰减**的。你不需要手动配置"这个Agent能不能生成子Agent"，系统根据深度自己算。越深层的Agent权限越小，到达最大深度时自动变成叶子节点，完全失去生成能力。这有点像Linux的进程capability机制——子进程的权限永远不会超过父进程。

### 第12道：工具权限继承

父Agent对子Agent的工具权限继承也是一个缩小模型。

父Agent有自己的工具白名单（allowlist）和黑名单（denylist）。子Agent继承这些权限时，只能**缩小不能扩大**。通过 `inheritedToolAllowPatch` 可以在父Agent白名单的基础上再削减，通过 `inheritedToolDenyPatch` 可以在父Agent黑名单的基础上再增加。

这些继承的权限会写入子Agent的session store（JSON文件里），持久化存储。这样即使父Agent进程崩溃了，子Agent的权限策略也不会丢失。

### 第13道：上下文准备（fork vs isolated）

子Agent启动前需要准备好它的对话上下文。有两种模式：

**fork模式**：把父Agent的对话记录（transcript）完整复制一份给子Agent。子Agent启动后能看到之前所有的对话历史，就像父Agent在跟它交代任务背景一样。这种模式要求父子Agent是同一个（因为不同Agent的系统提示不同，对话历史可能不兼容）。如果fork失败，会自动降级为isolated模式，不会阻塞整个生成流程。

**isolated模式**：子Agent从一张白纸开始，父Agent的对话历史对它不可见。跨Agent生成（比如主Agent生成code-reviewer）默认用这个模式，因为不同类型Agent的上下文结构可能完全不同，强行复制反而有害。

### 第14道：Gateway分发

前面13道都通过了，最后一步是真正启动子Agent。

OpenClaw调用 `callGateway({ method: "agent", params: { ... } })` 来启动子Agent的执行。Gateway是OpenClaw内部的RPC层，它负责把"启动Agent"这个请求路由到正确的执行环境（可能是本地进程、可能是远程服务）。

子Agent的sessionKey的格式是固定的：`agent:{目标Agent的ID}:subagent:{一个随机UUID}`。这个格式很重要，因为整个系统通过sessionKey来识别一个session的角色和层级。

---

## 五、子Agent运行期间怎么被跟踪的（注册表机制）

### 5.1 核心：一个全局Map

OpenClaw维护了一个全局变量来跟踪所有子Agent的状态：

```
subagentRuns = new Map()    // 键是runId，值是一个包含40+字段的记录对象
```

这个Map就是整个调度系统的"注册表"。所有的子Agent——不管是在运行的、已完成的、还是暂停等待恢复的——都在这个Map里有一条记录。

### 5.2 注册流程：子Agent生成后立刻注册

当第14道检查通过、`callGateway` 成功启动了子Agent之后，OpenClaw会立即调用 `registerSubagentRun()` 在这个全局Map里登记：

1. 新建一个 `SubagentRunRecord` 对象。这个对象有40多个字段，记录了这个子Agent的一切信息——runId、sessionKey、请求者是谁、任务描述、创建时间、清理策略（delete还是keep）、生成模式（run还是session）、模型、工作目录等等。
2. 把这个对象放进 `subagentRuns` Map，键就是runId。
3. **立刻写磁盘**。`persistSubagentRunsToDisk()` 把整个 `subagentRuns` Map序列化成JSON，写入磁盘文件。这个"立刻持久化"的设计保证了即使进程下一秒崩溃，重启后还能恢复所有正在进行的子Agent状态。
4. 启动一个后台等待：`waitForSubagentCompletion(runId, timeout, entry)`。这个方法会跨进程（通过Gateway RPC）等待子Agent执行结束。
5. 如果子Agent不是session模式（即是一次性的run模式），还会注册一个"后台任务"的跟踪记录，用于在前端展示任务状态。

### 5.3 生命周期监听：怎么知道子Agent的状态变化

注册之后，OpenClaw通过一个全局事件监听器来跟踪每个子Agent的状态变化。

监听器监听的是 `lifecycle` 流的事件。每个Agent在执行过程中会发出各种生命周期事件，监听器收到事件后根据事件的类型决定怎么更新Map中的状态：

**收到 `start` 事件**：
- 说明子Agent真正开始执行了（之前可能还在排队或准备环境）。
- 记录 `startedAt` 时间戳。
- 清除任何pending的错误定时器。因为如果之前收到了error事件设了15秒等待定时器，现在start事件来了说明子Agent已经恢复了，不需要再等待了。

**收到 `error` 事件**：
- **重点：不立即判定子Agent失败！**
- 而是设一个15秒的定时器 `schedulePendingLifecycleError()`。
- 为什么？因为OpenClaw支持嵌入式运行（embedded PI run）。这种运行方式在遇到provider临时错误时，运行时会自动重试——表现就是先发一个error事件，紧接着又发一个start事件。如果一收到error就判定失败，父Agent就会过早收到"子Agent失败了"的错误通知，但实际上子Agent过了一会儿就重试成功了。
- 15秒宽限期内，如果收到了start事件（重试了）或正常的end事件（恢复了），就取消定时器。
- 如果15秒到了还没恢复，才真正执行完成流程，标记为error。

**收到 `end` 事件且 `aborted=true`**：
- 这说明子Agent被中断了（比如超时），但不一定就是最终结果。
- 同样不立即判定，设15秒定时器 `schedulePendingLifecycleTimeout()`。
- 因为runtime可能马上重试，重试时会发start事件。
- 如果15秒内收到start事件，取消定时器。
- 如果15秒到了还没重试，标记为timeout。

**收到 `end` 事件（正常结束）**：
- 立即处理，不等。
- 取消所有pending的error和timeout定时器。
- 调用 `completeSubagentRun()` 进入完成流程。

**收到特殊的liveness状态（blocked）**：
- 这是Agent运行被阻塞的状态（比如被安全策略拦截）。
- 不走宽限期，立即标记为error完成。

### 5.4 完成流程：子Agent结束后发生了什么

`completeSubagentRun()` 是完成流程的入口，做了以下事情：

1. **清除pending定时器**：确保没有残留的宽限期定时器在跑。

2. **更新状态**：记录 `endedAt`（结束时间）、`outcome`（结果是ok还是error还是timeout）、`endedReason`（结束原因）。

3. **冻结结果**：调用 `captureSubagentCompletionReply()` 去捕获子Agent最后回复的内容。这个"捕获"不是直接读内存，而是通过session系统去子Agent的对话记录里提取最后的回复文本。捕获到的结果会截断到最多100KB（超过100KB的回复说明可能有问题，或者子Agent输出了大量无用内容），保存在 `frozenResultText` 字段里。如果是错误状态，就不保存结果（`frozenResultText = null`）。

4. **持久化**：把更新后的记录写磁盘。

5. **更新后台任务状态**：如果这个子Agent被注册为后台任务，更新它的状态为已完成或已失败。

6. **持久化session timing**：把子Agent的运行时长（startedAt、endedAt、runtimeMs）写回session store，这样前端就能看到子Agent执行了多久。

7. **发送生命周期事件**：通知系统"有个子Agent状态变了"，UI可以据此刷新。

8. **发送结束Hook**：如果有插件注册了子Agent结束的钩子，触发它们。

9. **清理浏览器会话**：如果子Agent使用了浏览器（比如做网页自动化），清理相关浏览器资源。

10. **清理Bundle MCP Runtime**：如果子Agent使用了MCP工具服务，关闭相关运行时。

11. **启动宣布清理流程**：`startSubagentAnnounceCleanupFlow()` ——把子Agent的结果投递给父Agent，然后清理现场。这是下一步要讲的重点。

---

## 六、结果怎么交还给父Agent

### 6.1 宣布流程的整体步骤

子Agent执行完了，结果也有了，怎么告诉父Agent"你交代的事办完了"？这就是 `runSubagentAnnounceFlow()` 做的事情。

**第一步：等待彻底结束。** 如果子Agent还在嵌入式运行中（有些运行环境是轻量级的嵌入式进程），先等它彻底停下来。最多等120秒。

**第二步：等待运行结果就绪。** 调用 `waitForSubagentRunOutcome()` 获取子Agent的最终运行结果（ok/error/timeout/unknown）。

**第三步：检查子孙代理。** 看看子Agent有没有还在运行的孙Agent（子Agent自己生成的子Agent）。如果有，就先不发完成通知——因为子Agent的最终结果可能要等它的子孙都完成了才算完整。这时候返回false，等sweeper稍后重试。

**第四步：收集子Agent的子代完成发现。** 虽然不立即通知，但可以先收集已经完成的孙Agent的结果，把这些结果汇总成一个"发现摘要"。后面唤醒子Agent时要用。

**第五步：唤醒子Agent（如果需要）。** 如果子Agent设置了 `wakeOnDescendantSettle`（意为"等子孙都完成了再叫我"），且确实有孙Agent完成了，就向子Agent的session发送一条唤醒消息，内容是"你的子孙Agent都完成了，这是它们的结果，请你继续工作并给出最终答案"。子Agent收到后会继续运行，产出最终结果。唤醒成功后，用一个新的runId（在原runId后面加 `:wake` 后缀）替换原来的run记录。

**第六步：读取子Agent的输出。** 如果子Agent不需要被唤醒（或者唤醒失败），就去读子Agent的最终回复。先尝试常规读取（`readSubagentOutput`），读不到就用重试方式读（`readLatestSubagentOutputWithRetry`，会等一段时间重试）。

**第七步：处理静默回复。** 如果子Agent的回复是静默令牌（`SILENT_REPLY_TOKEN`）或者系统识别的"无需回复"标记，就不通知父Agent，直接返回true（静默完成）。

**第八步：构建宣布消息。** 把子Agent的完成结果包装成一条格式化的内部消息。消息内容包括：子Agent的任务描述、执行结果（成功/失败/超时）、状态标签、消耗的token数和时间统计、以及对父Agent的"回复指令"。回复指令会根据父Agent是主Agent还是另一个子Agent而不同：
- 如果父Agent是子Agent（嵌套情况）：指令是"把这次完成转换成给父Agent的简洁汇报，如果不需要更新就回静默令牌"。
- 如果父Agent是主Agent：指令是"审查结果并决定下一步，如果需要就给用户发更新，不需要就回静默令牌"。

**第九步：投递。** 调用 `deliverSubagentAnnouncement()` 把构建好的消息发送到父Agent的session里。这个消息对于父Agent来说就像是"系统内部消息"——父Agent会在下一轮对话中看到它，并根据消息内容决定下一步行动。

**第十步：清理。** 如果子Agent的cleanup策略是delete，就调用 `sessions.delete` 删除子Agent的session（包括对话记录）。如果是keep，就保留。

### 6.2 投递失败了怎么办——指数退避重试

投递不一定每次都成功。父Agent可能正在忙、网络可能抖动、session可能暂时不可用。

OpenClaw的重试策略是标准的指数退避：

- 第一次重试：等1秒
- 第二次重试：等2秒
- 第三次重试：等4秒
- 无论重试多少次，单次等待不超过8秒
- **最多重试3次**

为什么是3次？因为如果3次都失败（总共等了1+2+4=7秒），说明问题可能不是暂时的，继续重试只会浪费资源。

### 6.3 3次都失败了怎么办——分级降级

这里有一个非常精细的判断逻辑：

**如果满足以下所有条件，不放弃，而是"挂起"（suspend）等待后续恢复：**
- 子Agent是正常完成任务的（`outcome.status == "ok"`）
- 子Agent的session被配置为保留（`cleanup == "keep"`）
- 子Agent正常结束了（`endedReason == "complete"`）
- 子Agent期望父Agent收到完成消息（`expectsCompletionMessage == true`）

这几个条件合在一起表达的意思是："这个子Agent确实完美完成了任务，只是通知暂时送不到父Agent手里，放弃太可惜了"。

挂起的时候会记录 `pendingFinalDelivery = true` 和 `deliverySuspendedAt` 时间戳。然后根据session类型有不同的保留时长：

- **定时任务（cron）的结果**：最多保留2小时。定时任务时效性最强，超时了就不通知了。
- **子Agent的结果**：最多保留6小时。子Agent的结果时效性中等。
- **交互式对话的结果**：最多保留24小时。用户可能离开一段时间再回来，所以给最长的保留时间。

**如果不满足上面的条件（比如子Agent本身就执行失败了）**：直接放弃。清理附件、标记清理完成、触发结束Hook、结束。

### 6.4 幂等投递

每次投递都会生成一个幂等键（基于 `childSessionKey:childRunId` 构建）。这意味着如果因为某些原因同一个完成消息被投递了多次（比如网络重传），父Agent可以通过幂等键识别出来这是同一条消息，不会重复处理。

### 6.5 投递镜像检测

还有一个额外的保护机制：在决定是否重试之前，OpenClaw会去父Agent的对话历史里检查一下——"这条完成消息是不是其实已经送到过了？"

它会调用 `chat.history` 接口拉取父Agent最近25条消息（最多128KB），找一条特殊的"投递镜像"记录。这条记录的格式是：role为assistant、provider为openclaw、model为delivery-mirror、内容与子Agent的结果完全一致、时间在子Agent启动之后。

如果找到了这样的镜像记录，说明子Agent的结果其实已经成功投递了，只是投递的"确认信号"丢在了路上。这种情况下不需要重试，直接标记投递成功。

---

## 七、Sweeper定时清理

`sweepSubagentRuns()` 每60秒执行一次。整个清理逻辑在一个函数里，一次遍历干五件事。

### 7.1 启动和停止

- **启动**：第一次有子Agent注册到 `subagentRuns` Map时自动启动。
- **停止**：当 `subagentRuns` Map清空时自动停止（没有子Agent要跟踪了，就不需要清理了）。
- **防重入**：用 `sweepInProgress` 标志位确保不会有两个清理任务同时执行。

### 7.2 五合一清理详解

**第一件事：清理过期的挂起通知。**

每次先筛选出所有 `pendingFinalDelivery = true` 的条目（也就是那些子Agent完成了但通知暂时发不出去的）。然后用当前时间减去 `deliverySuspendedAt`（挂起时间），算出已经挂了多久。如果超过对应session类型的过期时间（cron 2小时、子Agent 6小时、交互式 24小时），就丢弃。丢弃时记录丢弃原因、时间、有效载荷摘要，方便后续排查问题。

**第二件事：反压处理。**

如果挂起的通知积压太严重，就不是正常情况了。OpenClaw设了三个阈值：

- **25条**：软上限。超过这个数说明系统可能有问题（为什么这么多通知发不出去？），但不强制清理。
- **50条**：硬上限。超过这个数必须强制清理，因为如果不清理，挂起队列会无限增长撑爆内存和磁盘。
- **10条**：清理目标。强制清理后，把挂起队列压缩到不超过10条。

强制清理的策略是FIFO（先进先出）——优先丢弃最旧的通知。因为越久远的通知，时效性越差，丢弃的损失越小。

**第三件事：僵尸运行检测。**

遍历Map中所有 `endedAt` 为空的条目（理论上"还在运行中"）。

对每个这样的条目，检查它是否还有活跃的运行上下文（`getAgentRunContext(runId)`）。如果没有活跃上下文，且已经过了60秒（`STALE_ACTIVE_SUBAGENT_GRACE_MS`），就说明这个子Agent可能已经"僵尸"了——进程早就没了，但注册表里的状态没更新。

遇到这种情况，先尝试从session store恢复：去查子Agent对应session的实际状态，说不定session已经结束了只是注册表没同步。如果能从session状态推导出完成信息，就用那个信息完成注册。如果session也不存在了（孤儿），直接清理掉。如果实在什么都恢复不了，就标记为error——"子Agent运行丢失了活跃执行上下文"。

**第四件事：Session TTL清理。**

对于 `cleanup=keep` 的一次性run（非session模式），在 `cleanupCompletedAt` 之后保留5分钟。超过5分钟就彻底从Map里删除。这是为了让父Agent有5分钟的时间窗口去查询子Agent的状态，5分钟后就不再需要了。

**第五件事：归档到期清理。**

如果记录设置了 `archiveAtMs`（归档时间），到期后调用 `sessions.delete` 删除对应的子Agent session，并从Map中移除记录。归档时间是通过 `archiveAfterMinutes` 配置计算的，默认值来自 `DEFAULT_SUBAGENT_ARCHIVE_AFTER_MINUTES`。

### 7.3 孤儿pending清理

除了Map中的条目，还有两个辅助Map：`pendingLifecycleErrorByRunId` 和 `pendingLifecycleTimeoutByRunId`（就是前面说的15秒宽限期定时器的记录）。

Sweeper也会清理这两个Map中的孤儿条目：如果一条pending记录的结束时间距今超过5分钟（`PENDING_LIFECYCLE_TERMINAL_TTL_MS`），就清除掉。这防止宽限期定时器因为bug而永远不触发。

---

## 八、崩溃恢复

### 8.1 为什么能恢复

因为每次对 `subagentRuns` Map的修改都会**立即写磁盘**。虽然这会增加一些IO开销，但换来的是崩溃后可恢复的能力。

### 8.2 恢复过程

进程重启后，`initSubagentRegistry()` 被调用。它只做一件事：调用 `restoreSubagentRunsOnce()`。这个函数用了一个 `restoreAttempted` 标志位保证只执行一次（幂等）。

恢复步骤：

**第一步：从磁盘恢复数据。** `restoreSubagentRunsFromDisk()` 读取磁盘上的JSON文件，反序列化后合并到内存中的 `subagentRuns` Map（`mergeOnly=true`，即只添加不删除内存中已有的）。

**第二步：孤儿协调。** 遍历所有恢复的记录，检查每个是不是孤儿。恢复时用的检测更严格——包含了 `stale-unended-run` 类型（运行了很久但从来没结束过的僵尸记录）。如果是孤儿，直接从Map中删掉并清理附件。

**第三步：如果有剩余有效记录，重建运行时环境。**
- `ensureListener()`：重新注册全局生命周期事件监听器。没有这个监听器，后续子Agent的状态变化就无法被感知。
- `startSweeper()`：重新启动60秒定时清理器。
- 对每条记录调用 `resumeSubagentRun(runId)`：根据记录的状态决定下一步做什么。
- `scheduleSubagentOrphanRecovery()`：安排一次孤儿恢复扫描。

### 8.3 逐条恢复的逻辑

`resumeSubagentRun(runId)` 对每条恢复记录的处理非常细致：

- 如果这个runId已经恢复过了（在 `resumedRuns` Set里），跳过。
- 如果已经清理完成了（`cleanupCompletedAt` 有值），跳过。
- 如果正在等待交付且被挂起了（`pendingFinalDelivery && deliverySuspendedAt`），跳过——留给sweeper处理。
- 如果被主动暂停了（`sessions_yield`），跳过。
- 如果重试次数已经超过3次，放弃（`finalizeResumedAnnounceGiveUp("retry-limit")`）。
- 如果不是完成消息且距离结束时间已超过5分钟，放弃（`finalizeResumedAnnounceGiveUp("expiry")`）。
- 如果是完成消息且上次重试时间距离现在还不够延迟间隔（按指数退避算），设一个定时器到时间再重试。
- 如果记录有结束时间：先检查是不是孤儿（是就清理），然后启动 `startSubagentAnnounceCleanupFlow()` 重试结果交付。
- 如果记录没有结束时间（说明子Agent可能还在运行，或者崩溃时没来得及记录结束时间）：重新调用 `waitForSubagentCompletion()` 等待子Agent完成。如果子Agent的进程实际上已经不存在了，这个方法会超时然后走超时处理逻辑。

---

## 九、从生到死的完整过程

让我用一个具体的时间线来串联上面所有环节：

假设主Agent在执行过程中决定"我需要一个code-reviewer来帮我看这段代码"。

**T0**：主Agent调用 `sessions_spawn` 工具，传参 `agentId="code-reviewer"`, `task="请审查这段代码"`。

**T1**：14道检查开始执行。任务描述非空 ✓，taskName格式 ✓，agentId格式 ✓，code-reviewer在配置中存在 ✓，不是cron调session模式 ✓，深度检查——主Agent深度为0，maxSpawnDepth=1，0<1通过 ✓，并发检查——目前活跃子Agent有2个，maxChildren=5，2<5通过 ✓，白名单检查——code-reviewer在allowAgents列表中 ✓，沙箱未用 ✓。全部通过。

**T2**：计算能力。深度0 → 角色main，controlScope=children，canSpawn=true。

**T3**：准备session store。写入子Agent元数据：spawnDepth=1，subagentRole="orchestrator"（如果maxSpawnDepth>1的话），inheritedToolAllow/Deny继承自父Agent。

**T4**：准备上下文。跨Agent生成（主Agent≠code-reviewer），强制使用isolated模式——子Agent从空白开始。

**T5**：生成sessionKey：`agent:code-reviewer:subagent:a1b2c3d4-...`。

**T6**：注册到全局Map。`subagentRuns.set(runId, record)`，写磁盘。

**T7**：Gateway分发。`callGateway({ method: "agent", params: { sessionKey, message: "请审查这段代码" } })`。

**T8**：子Agent开始执行。后台 `waitForSubagentCompletion()` 开始等待。

**T9**：子Agent执行中... 生命周期事件持续触发。中途provider报了一个临时错误 → error事件 → 15秒宽限期启动 → runtime自动重试成功 → start事件 → 取消宽限期。子Agent继续执行。

**T10**：子Agent执行完毕。发送 `end` 事件（正常）。

**T11**：`completeSubagentRun()` 被调用。记录结束时间、结果状态为ok、捕获最终回复（假设1200个字符）→ 截断到100KB以内 → 写入frozenResultText。写磁盘。更新后台任务状态。

**T12**：`startSubagentAnnounceCleanupFlow()` 启动。冻结结果已就绪。

**T13**：`runSubagentAnnounceFlow()` 执行。检查子Agent没有未完成的子孙 → 读取子Agent输出 → 1200字符的审查结果 → 不是静默令牌 → 构建宣布消息："code-reviewer完成了，结果是：{审查内容}"。

**T14**：`deliverSubagentAnnouncement()` 投递到主Agent的session。投递成功。

**T15**：cleanup是delete，所以删除子Agent的session。MCP runtime清理完毕。浏览器会话清理完毕。

**T16**：`completeCleanupBookkeeping()` 标记清理完成。如果5分钟后没有新动作，sweeper会从Map中彻底删除这条记录。

**整个过程，每一步都持久化，每一步都可恢复。**

---

## 十、如果你只能记住三件事

1. **Agent配置纯内存，零IO**。所有Agent信息从 `openclaw.json` 加载后就驻留在内存的一个数组里，之后任何查询都是 `Array.find()`。没有数据库。

2. **子Agent的生成是14道有序检查**。其中最核心的是深度限制（默认只能生成一层，子Agent不能再生成孙Agent）和并发限制（每个Agent最多同时有5个子Agent）。深度是自动计算的，权限随深度自动衰减。

3. **全局Map + 立即写磁盘 + 事件驱动 = 崩溃可恢复**。所有子Agent的状态都在一个全局Map里，每次变更都写磁盘。进程重启后从磁盘恢复Map，逐个恢复运行。Sweeper每60秒自动清理过期和孤儿。
