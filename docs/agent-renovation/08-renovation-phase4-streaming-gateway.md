# 第四阶段：流式与网关增强 + 沙箱 + 心跳 + 运行重试

## 概述

第四阶段针对支撑 LyClaw 生产就绪性的四个高影响力子系统：
1. **块流式与人类延迟** — 用边界感知的块流式、合并、人类输入模拟和输入中指示器替换 `DefaultReActEngine` 中简单的 `splitIntoEvents()`。
2. **容器沙箱** — 将 `ToolSandbox` / `SandboxLevel=PROCESS` 升级为基于 Docker/Podman 的隔离，支持文件系统桥接、资源限制和 `SandboxExecutionService`。
3. **智能体心跳** — 引入类 cron 调度器，可定期 ping 智能体，产生 `heartbeat_*` SSE 事件，并传递隔离会话的轮次结果。
4. **运行重试增强** — 用 `RunRetryManager`、按回退配置文件预算和重试策略选择替换 `ReflexionLoop` 中硬编码的 `maxRetries`。

所有新代码位于现有包下：
- 流式配置 → `lyjew.com.lyclaw.config`
- 块流式逻辑 → `lyjew.com.lyclaw.react.stream`
- 沙箱 → `lyjew.com.lyclaw.security.sandbox`
- 心跳 → `lyjew.com.lyclaw.react.heartbeat`
- 运行重试 → `lyjew.com.lyclaw.react.retry`

---

## 目录

1. [4.1 块流式增强](#41-块流式增强)
2. [4.2 沙箱增强](#42-沙箱增强)
3. [4.3 心跳系统](#43-心跳系统)
4. [4.4 运行重试增强](#44-运行重试增强)
5. [集成架构图](#集成架构图)
6. [SSE 事件模式参考](#sse-事件模式参考)

---

## 4.1 块流式增强

### 4.1.1 动机

当前 `DefaultReActEngine.splitIntoEvents(String text)` 在中文标点边界处（`\n`、`。`、`！`、`？`、`；`）进行分割，并将每个片段作为单个 SSE `message` 事件发出。这对于短回复可行，但存在若干问题：

- **无块感知**：不理解 LLM 自然文本边界（段落、代码围栏、列表）。
- **无合并**：单字符块创建单独的 SSE 帧 — 浪费资源。
- **无人类延迟**：所有事件同时到达，没有"AI 正在打字"的感觉。
- **无输入中指示器**：前端无法在响应生成期间显示"思考中"或"输入中"状态。

第四阶段在 `RespondStage` 和 `DefaultReActEngine` 内部引入了分层流式管道：

```
LLM token stream
  → BlockStreamingChunk (软边界检测)
    → BlockStreamingCoalesce (合并小块)
      → HumanDelay (块间错开)
        → TypingIndicator (周期性"输入中"事件)
          → SSE emit
```

### 4.1.2 配置

#### BlockStreamingConfig

```java
package lyjew.com.lyclaw.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 基于块的流式配置。
 * <p>控制 LLM token 流如何分块并传递给 SSE 客户端。
 * 用边界感知、合并、人类延迟的流式替换简单的 splitIntoEvents()。
 */
@ConfigurationProperties(prefix = "lyclaw.streaming.block")
public class BlockStreamingConfig {

    /** 启用基于块的流式。设为 false 时，回退到旧的 splitIntoEvents()。 */
    private boolean enabled = false;

    /**
     * 何时断开流式块。
     * <ul>
     *   <li>TEXT_END — 每个完整文本段落后断开（段落、列表项等）</li>
     *   <li>MESSAGE_END — 仅在整条助手消息结束时断开</li>
     * </ul>
     */
    private BlockStreamingBreak breakMode = BlockStreamingBreak.TEXT_END;

    /** 软块分块配置。 */
    private BlockStreamingChunk chunk = new BlockStreamingChunk();

    /** 块回复合并配置。 */
    private BlockStreamingCoalesce coalesce = new BlockStreamingCoalesce();

    /** 每个块帧的最大字符数。 */
    private int maxChunkChars = 2000;

    /** 如果为 true，抑制重复的相同文本块。 */
    private boolean repeatSuppression = true;

    /**
     * 流式投递模式。
     * <ul>
     *   <li>LIVE — 块形成后立即发出（默认）</li>
     *   <li>FINAL_ONLY — 缓冲所有内容，结束时发出单个事件</li>
     * </ul>
     */
    private StreamingDeliveryMode deliveryMode = StreamingDeliveryMode.LIVE;

    /**
     * 多块消息的隐藏边界分隔符。
     * 作为块之间的不可见分隔符插入，供解析响应的客户端使用。
     */
    private HiddenBoundarySeparator hiddenBoundary = HiddenBoundarySeparator.NEWLINE;

    // 此处省略 getter 和 setter

    public enum BlockStreamingBreak { TEXT_END, MESSAGE_END }
    public enum StreamingDeliveryMode { LIVE, FINAL_ONLY }
    public enum HiddenBoundarySeparator { NEWLINE, NULL_CHAR, NONE }
}
```

#### BlockStreamingChunk

```java
package lyjew.com.lyclaw.config;

/**
 * 软块分块配置。
 * <p>分块意味着决定在何处将 token 流切割为离散块。
 * 这是"软"的，因为块可以在之后被合并。
 */
public class BlockStreamingChunk {

    /**
     * 每个块的软最大字符数（中日韩文本按字节计）。
     * 当块超过此大小时将刷新，
     * 但实际边界仍受 preferNewlines 影响。
     */
    private int maxChars = 500;

    /**
     * 刷新当前块的最大空闲时间（毫秒）。
     * 如果在此持续时间内没有新 token 到达，累积的块将被发出。
     */
    private int maxIdleMs = 1000;

    /**
     * 如果为 true，优先在换行边界处分割（\n、\r\n、\n\n）。
     * 当遇到换行且当前块至少达到 maxChars 的 50% 时，
     * 块将在该边界处刷新，不考虑确切大小。
     */
    private boolean preferNewlines = true;

    /**
     * 当 preferNewlines 为 true 时，触发换行刷新的最小填充百分比（0.0-1.0）。
     */
    private double newlineFlushThreshold = 0.5;

    // 省略 getter 和 setter
}
```

#### BlockStreamingCoalesce

```java
package lyjew.com.lyclaw.config;

/**
 * 块回复合并配置。
 * <p>合并将多个小块合并为一个较大的块再进行 SSE 投递。
 * 这减少了 SSE 帧的数量，提高了网络效率。
 */
public class BlockStreamingCoalesce {

    /** 启用块合并。 */
    private boolean enabled = true;

    /** 合并块强制刷新前的最大字符数。 */
    private int maxChars = 8000;

    /**
     * 刷新合并缓冲区的最大空闲时间（毫秒）。
     * 如果在此持续时间内没有新块到达，累积的内容将被发出。
     */
    private int maxIdleMs = 3000;

    // 省略 getter 和 setter
}
```

#### HumanDelayConfig

```java
package lyjew.com.lyclaw.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 类人输入延迟配置。
 * <p>在流式块之间引入可变延迟，模拟自然输入速度，改善聊天界面用户体验。
 */
@ConfigurationProperties(prefix = "lyclaw.streaming.human-delay")
public class HumanDelayConfig {

    /** 启用类人延迟模拟。 */
    private boolean enabled = false;

    /** 块之间的最小延迟（毫秒）。 */
    private int minDelayMs = 200;

    /** 块之间的最大延迟（毫秒）。 */
    private int maxDelayMs = 1500;

    /**
     * 模拟输入速度，以每秒字符数计。
     * 用于计算动态延迟：delayMs = blockChars / charsPerSecond * 1000。
     * 典型人类输入速度为 40-80 CPS；50 是一个自然的默认值。
     */
    private int charsPerSecond = 50;

    /**
     * 如果为 true，自适应速度会根据长回复调整输入速率。
     * 随着响应长度增长，智能体"加速"以避免过长的等待时间。
     */
    private boolean adaptiveSpeed = true;

    /**
     * 触发加速调整的字符阈值。
     * 当总累积响应超过此值时，charsPerSecond 会
     * 逐渐增加（最多 3 倍）用于后续块。
     */
    private int longReplyThreshold = 2000;

    // 省略 getter 和 setter
}
```

### 4.1.3 BlockStreamingController

这是替换 `splitIntoEvents()` 的核心组件。

```java
package lyjew.com.lyclaw.react.stream;

import lyjew.com.lyclaw.config.BlockStreamingConfig;
import lyjew.com.lyclaw.config.HumanDelayConfig;
import org.springframework.http.codec.ServerSentEvent;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 基于块的流式控制器，替代 DefaultReActEngine.splitIntoEvents()。
 *
 * <p>将原始文本响应转换为边界感知、合并、人类延迟的
 * SSE 事件 Flux。与 RespondStage 的流式管道集成。</p>
 *
 * <h3>处理管道：</h3>
 * <ol>
 *   <li>将原始文本按自然边界解析为块</li>
 *   <li>合并相邻小块</li>
 *   <li>在块之间应用人类延迟</li>
 *   <li>应用重复抑制</li>
 *   <li>发出 SSE message 事件</li>
 * </ol>
 */
public class BlockStreamingController {

    private static final ObjectMapper objectMapper = new ObjectMapper();

    private final BlockStreamingConfig config;
    private final HumanDelayConfig humanDelayConfig;
    private final TypingIndicatorController typingIndicator;

    // 跟踪先前发出的文本用于重复抑制
    private String lastEmittedBlock = "";

    // 跟踪总发出字符数用于自适应速度
    private int totalEmittedChars = 0;

    public BlockStreamingController(BlockStreamingConfig config,
                                     HumanDelayConfig humanDelayConfig,
                                     TypingIndicatorController typingIndicator) {
        this.config = config;
        this.humanDelayConfig = humanDelayConfig;
        this.typingIndicator = typingIndicator;
    }

    /**
     * 将完整文本响应转换为块流式的 SSE 事件 Flux。
     * 当检测到工具调用且 ReAct 循环产生最终文本响应时使用。
     *
     * @param text 完整的助手响应文本
     * @return SSE message 事件的 Flux
     */
    public Flux<ServerSentEvent<String>> streamResponse(String text) {
        if (!config.isEnabled() || text == null || text.isEmpty()) {
            return Flux.empty();
        }

        return Flux.defer(() -> {
            List<String> blocks = segmentIntoBlocks(text);
            if (blocks.isEmpty()) {
                return Flux.empty();
            }

            blocks = coalesceBlocks(blocks);
            blocks = applyRepeatSuppression(blocks);

            if (config.getDeliveryMode() == BlockStreamingConfig.StreamingDeliveryMode.FINAL_ONLY) {
                String joined = joinWithHiddenBoundary(blocks);
                return Flux.just(sseMessage(joined));
            }

            // LIVE 模式：以人类延迟发出块
            return Flux.fromIterable(blocks)
                    .concatMap(block ->
                            Mono.just(sseMessage(block))
                                    .delayElement(calculateDelay(block))
                    );
        });
    }

    /**
     * 按自然边界将原始文本分割为块。
     *
     * <p>边界检测识别：
     * <ul>
     *   <li>段落分隔（双换行）— 最强边界</li>
     *   <li>代码围栏 (```)、列表项 (-、*、1.) — 强边界</li>
     *   <li>表格行 (|) — 强边界</li>
     *   <li>句子结束 (.!?。) — 中等边界</li>
     *   <li>换行 — 弱边界</li>
     *   <li>逗号/冒号 — 软边界（仅在接近 maxChars 时）</li>
     * </ul></p>
     */
    List<String> segmentIntoBlocks(String text) {
        BlockStreamingConfig.BlockStreamingBreak breakMode = config.getBreakMode();
        int maxChars = config.getChunk().getMaxChars();
        boolean preferNewlines = config.getChunk().isPreferNewlines();
        double newlineThreshold = config.getChunk().getNewlineFlushThreshold();

        List<String> blocks = new ArrayList<>();
        StringBuilder buf = new StringBuilder();

        // 第一遍：按双换行分割（段落分隔 — 最强边界）
        String[] paragraphs = text.split("\\n\\s*\\n", -1);

        for (int p = 0; p < paragraphs.length; p++) {
            String paragraph = paragraphs[p];
            if (paragraph.isEmpty()) {
                if (p > 0 && p < paragraphs.length - 1) {
                    // 空段落 = 有意的空白行，添加为分隔符
                    blocks.add("\n\n");
                }
                continue;
            }

            // 在每个段落内，按强边界分割
            int i = 0;
            while (i < paragraph.length()) {
                char c = paragraph.charAt(i);
                buf.append(c);

                boolean shouldFlush = false;

                if (breakMode == BlockStreamingConfig.BlockStreamingBreak.MESSAGE_END) {
                    // 仅在段落边界处刷新
                    shouldFlush = false;
                } else if (buf.length() >= maxChars) {
                    // 达到 maxChars 时强制刷新
                    shouldFlush = true;
                } else if (c == '\n' && preferNewlines
                        && buf.length() >= (int)(maxChars * newlineThreshold)) {
                    // 当缓冲区足够满时在换行处软刷新
                    shouldFlush = true;
                } else if (isStrongBoundary(c, paragraph, i)) {
                    // 强边界字符
                    shouldFlush = buf.length() >= 20; // 避免单字符块
                } else if (isMediumBoundary(c) && buf.length() >= (int)(maxChars * 0.5)) {
                    // 当 > 50% 满时，在中等边界处刷新
                    shouldFlush = true;
                }

                if (shouldFlush) {
                    blocks.add(buf.toString().trim());
                    buf.setLength(0);
                }
                i++;
            }
        }

        // 刷新剩余内容
        if (buf.length() > 0) {
            String rem = buf.toString().trim();
            if (!rem.isEmpty()) {
                blocks.add(rem);
            }
        }

        return blocks;
    }

    /**
     * 合并相邻小块为较大块。
     */
    List<String> coalesceBlocks(List<String> blocks) {
        BlockStreamingCoalesce c = config.getCoalesce();
        if (!c.isEnabled() || blocks.size() <= 1) {
            return blocks;
        }

        List<String> coalesced = new ArrayList<>();
        StringBuilder buffer = new StringBuilder();

        for (String block : blocks) {
            if (buffer.length() + block.length() > c.getMaxChars()) {
                // 缓冲区将溢出 — 刷新它
                coalesced.add(buffer.toString().trim());
                buffer.setLength(0);
            }
            if (buffer.length() > 0) {
                buffer.append(config.getHiddenBoundary() ==
                        BlockStreamingConfig.HiddenBoundarySeparator.NEWLINE ? "\n" : "");
            }
            buffer.append(block);
        }

        if (buffer.length() > 0) {
            coalesced.add(buffer.toString().trim());
        }

        return coalesced;
    }

    /**
     * 移除重复的相同块。
     */
    List<String> applyRepeatSuppression(List<String> blocks) {
        if (!config.isRepeatSuppression() || blocks.isEmpty()) {
            return blocks;
        }

        List<String> filtered = new ArrayList<>();
        for (String block : blocks) {
            if (!block.equals(lastEmittedBlock)) {
                filtered.add(block);
                lastEmittedBlock = block;
            }
        }
        return filtered;
    }

    /**
     * 计算块的人类延迟。
     */
    Duration calculateDelay(String block) {
        if (!humanDelayConfig.isEnabled()) {
            return Duration.ZERO;
        }

        int charsPerSec = humanDelayConfig.getCharsPerSecond();

        if (humanDelayConfig.isAdaptiveSpeed() && totalEmittedChars > humanDelayConfig.getLongReplyThreshold()) {
            // 长回复加速：逐步将 CPS 提高到 3 倍
            double excessRatio = Math.min(1.0,
                    (double)(totalEmittedChars - humanDelayConfig.getLongReplyThreshold())
                            / humanDelayConfig.getLongReplyThreshold());
            charsPerSec = (int)(charsPerSec * (1.0 + excessRatio * 2.0));
        }

        // 基础延迟与块长度成正比
        int baseDelayMs = (int)((double)block.length() / charsPerSec * 1000);

        // 限制在最小和最大值之间
        int delayMs = Math.max(humanDelayConfig.getMinDelayMs(),
                Math.min(humanDelayConfig.getMaxDelayMs(), baseDelayMs));

        // 添加小幅随机抖动（±20%）
        double jitter = 0.8 + Math.random() * 0.4;
        delayMs = (int)(delayMs * jitter);

        totalEmittedChars += block.length();
        return Duration.ofMillis(delayMs);
    }

    /**
     * 使用配置的隐藏边界分隔符连接块。
     */
    String joinWithHiddenBoundary(List<String> blocks) {
        String sep;
        switch (config.getHiddenBoundary()) {
            case NULL_CHAR: sep = "\0"; break;
            case NONE: sep = ""; break;
            default: sep = "\n";
        }
        return String.join(sep, blocks);
    }

    private boolean isStrongBoundary(char c, String text, int pos) {
        // 标题标记：行首的 #
        if (c == '#') {
            return pos == 0 || (pos > 0 && text.charAt(pos - 1) == '\n');
        }
        // 代码围栏反引号：```
        if (c == '`' && text.length() > pos + 2
                && text.charAt(pos + 1) == '`' && text.charAt(pos + 2) == '`') {
            return true;
        }
        // 水平分隔线：---、***、___
        if ((c == '-' || c == '*' || c == '_') && text.length() > pos + 2) {
            boolean hr = text.charAt(pos + 1) == c && text.charAt(pos + 2) == c;
            if (hr) {
                return pos == 0 || (pos > 0 && text.charAt(pos - 1) == '\n');
            }
        }
        return false;
    }

    private boolean isMediumBoundary(char c) {
        return c == '\n' || c == '。' || c == '！' || c == '？'
                || c == '.' || c == '!' || c == '?' || c == '；' || c == ';';
    }

    private ServerSentEvent<String> sseMessage(String data) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", "message");
        payload.put("content", data);
        try {
            return ServerSentEvent.<String>builder()
                    .event("message")
                    .data(objectMapper.writeValueAsString(payload))
                    .build();
        } catch (Exception e) {
            return ServerSentEvent.<String>builder().event("message").data(data).build();
        }
    }
}
```

### 4.1.4 TypingIndicatorController

```java
package lyjew.com.lyclaw.react.stream;

import lyjew.com.lyclaw.react.AgentContext;
import org.springframework.http.codec.ServerSentEvent;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 控制在智能体处理间隙期间（工具执行、思考等）
 * 发送给客户端的输入中指示器 SSE 事件。
 *
 * <p>在 RespondStage 中的用法：
 * <pre>{@code
 *   Flux<ServerSentEvent<String>> typingFlux = typingIndicator.startTyping(ctx);
 *   Flux<ServerSentEvent<String>> bodyFlux = reactWithReActEngine(ctx, traceId, toolDefs);
 *   return bodyFlux.takeUntilOther(typingIndicator.stopSignal())
 *                  .mergeWith(typingFlux);
 * }</pre>
 */
public class TypingIndicatorController {

    private static final ObjectMapper objectMapper = new ObjectMapper();

    private final TypingMode mode;
    private final int intervalSeconds;
    private volatile boolean active = false;

    public enum TypingMode {
        /** 从不发送输入中指示器。 */
        NEVER,
        /** 进入处理间隙时立即发送输入中指示器。 */
        INSTANT,
        /** 在"思考"阶段按间隔发送输入中指示器。 */
        THINKING,
        /** 在消息生成期间按间隔发送输入中指示器。 */
        MESSAGE
    }

    public TypingIndicatorController(TypingMode mode, int intervalSeconds) {
        this.mode = mode;
        this.intervalSeconds = intervalSeconds;
    }

    /**
     * 返回一个 Flux，按配置的间隔发出输入中指示器 SSE 事件。
     * 当调用 stopTyping() 时事件自动停止。
     *
     * @param ctx 要为其发出输入中指示器的智能体上下文
     * @return "typing" SSE 事件的 Flux，每 intervalSeconds 发出一次
     */
    public Flux<ServerSentEvent<String>> startTyping(AgentContext ctx) {
        if (mode == TypingMode.NEVER) {
            return Flux.empty();
        }
        active = true;
        return Flux.interval(Duration.ZERO, Duration.ofSeconds(intervalSeconds))
                .takeWhile(tick -> active)
                .map(tick -> buildTypingEvent(ctx));
    }

    /**
     * 停止发出输入中指示器。startTyping() 的 Flux 将在
     * 下一次 tick 时完成。
     */
    public void stopTyping() {
        this.active = false;
    }

    private ServerSentEvent<String> buildTypingEvent(AgentContext ctx) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", "typing");
        payload.put("agentId", ctx.getSessionId());  // sessionId 用作 agentId
        payload.put("stage", ctx.getCurrentStage().get());
        try {
            return ServerSentEvent.<String>builder()
                    .event("typing")
                    .data(objectMapper.writeValueAsString(payload))
                    .build();
        } catch (Exception e) {
            return ServerSentEvent.<String>builder()
                    .event("typing")
                    .data("{\"type\":\"typing\"}")
                    .build();
        }
    }
}
```

### 4.1.5 与 RespondStage 的集成

修改后的 `RespondStage` 按如下方式集成块流式：

```java
// 在 RespondStage.reactWithReActEngine() 内部：
//
// 之前（当前）：
//   return reActEngine.executeStream(chatFacade, request, toolExecutor);
//
// 之后（第四阶段）：
//   BlockStreamingController streamingCtrl = streamingControllerFactory.get(ctx);
//   TypingIndicatorController typingCtrl = typingControllerFactory.get(ctx);
//
//   Flux<ServerSentEvent<String>> typingFlux = typingCtrl.startTyping(ctx);
//   Flux<ServerSentEvent<String>> rawStream = reActEngine.executeStream(chatFacade, request, toolExecutor);
//
//   return rawStream
//       .flatMap(event -> {
//           if ("message".equals(event.event()) && event.data() != null) {
//               String data = event.data();
//               // 如果事件是最终文本块（非流式 token），应用块流式
//               if (isBlockCandidates(data)) {
//                   streamingCtrl.reset();
//                   return streamingCtrl.streamResponse(data);
//               }
//               // 否则原样透传（流式 token 已经是细粒度的）
//               return Flux.just(event);
//           }
//           return Flux.just(event);
//       })
//       .doOnTerminate(typingCtrl::stopTyping)
//       .mergeWith(typingFlux);
```

在 `DefaultReActEngine` 中，`splitIntoEvents()` 方法被委托给 `BlockStreamingController` 替换：

```java
// 在 DefaultReActEngine 中，替换：
//   private Flux<ServerSentEvent<String>> splitIntoEvents(String text) { ... }
//
// 替换为：
//   private final BlockStreamingController streamingController;
//
//   private Flux<ServerSentEvent<String>> streamFinalText(String text) {
//       if (streamingController != null) {
//           return streamingController.streamResponse(text);
//       }
//       // 旧版回退
//       // ... （保留旧的 splitIntoEvents 逻辑以向后兼容）
//   }
```

### 4.1.6 YAML 配置

```yaml
# application.yml — 块流式配置
lyclaw:
  streaming:
    block:
      enabled: true
      break-mode: TEXT_END       # TEXT_END | MESSAGE_END
      chunk:
        max-chars: 500
        max-idle-ms: 1000
        prefer-newlines: true
        newline-flush-threshold: 0.5
      coalesce:
        enabled: true
        max-chars: 8000
        max-idle-ms: 3000
      max-chunk-chars: 2000
      repeat-suppression: true
      delivery-mode: LIVE        # LIVE | FINAL_ONLY
      hidden-boundary: NEWLINE   # NEWLINE | NULL_CHAR | NONE
    human-delay:
      enabled: true
      min-delay-ms: 200
      max-delay-ms: 1500
      chars-per-second: 50
      adaptive-speed: true
      long-reply-threshold: 2000
    typing-indicator:
      mode: THINKING             # NEVER | INSTANT | THINKING | MESSAGE
      interval-seconds: 5
```

---

## 4.2 沙箱增强

### 4.2.1 动机

当前沙箱系统（通过 `ToolSandbox` 接口和 `SandboxLevel` 枚举）支持：
- `DIRECT` — 在当前线程上执行（只读工具）
- `SANDBOX` — 守护线程 + 临时工作目录
- `PROCESS` — 通过 `CommandExecutor` 的独立操作系统进程

缺失的内容：
- **容器隔离**：无 Docker/Podman 支持；`PROCESS` 级别仍作为 JVM 进程的子进程运行。
- **资源限制**：无操作系统级别的内存/CPU/超时强制执行。
- **文件系统桥接**：主机与沙箱之间无双向文件传输。
- **健康监控**：`ToolSandbox.isHealthy()` 没有实际的容器健康检查支持。
- **网络控制**：无法为不受信任的代码禁用网络访问。

### 4.2.2 AgentSandboxConfig

```java
package lyjew.com.lyclaw.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * 基于容器的沙箱配置。
 * <p>控制用于工具执行隔离的 Docker/Podman 容器设置。
 */
@ConfigurationProperties(prefix = "lyclaw.sandbox")
public class AgentSandboxConfig {

    /**
     * 沙箱后端提供者。
     * <ul>
     *   <li>NONE — 无容器隔离（使用旧版进程沙箱）</li>
     *   <li>DOCKER — 使用 docker-java SDK</li>
     *   <li>PODMAN — 使用 podman CLI（兼容 rootless 设置）</li>
     * </ul>
     */
    private SandboxBackend backend = SandboxBackend.NONE;

    /** 用于沙箱执行的容器镜像。 */
    private String image = "ubuntu:22.04";

    /** 容器内沙箱操作的根目录。 */
    private String rootDir = "/sandbox";

    /** 命令白名单：仅允许这些命令在沙箱内执行。 */
    private List<String> allowedCommands = new ArrayList<>();

    /** 命令黑名单：明确禁止这些命令。 */
    private List<String> deniedCommands = new ArrayList<>();

    /** 沙箱容器是否有网络访问权限。默认 false 以保安全。 */
    private boolean networkEnabled = false;

    /** 沙箱容器是否可以写入文件系统。 */
    private boolean fileSystemWriteEnabled = true;

    /** 容器的内存限制（MB）。 */
    private long memoryLimitMb = 512;

    /** CPU 限制（核数，可为小数）。 */
    private double cpuLimit = 1.0;

    /** 单次工具调用的最大执行时间（秒）。 */
    private int timeoutSeconds = 300;

    /** 文件系统桥接配置。 */
    private SandboxFsBridge fsBridge = new SandboxFsBridge();

    /** 容器启动超时（秒）。 */
    private int startupTimeoutSeconds = 30;

    /** 如果为 true，在同一会话的工具调用之间复用容器。 */
    private boolean reuseContainer = true;

    /** 容器自动清理前的最大空闲时间（秒）。 */
    private int containerIdleTimeoutSeconds = 600;

    /** Docker socket 路径（默认：unix:///var/run/docker.sock）。 */
    private String dockerSocket = "unix:///var/run/docker.sock";

    /** Podman 后端的 Podman socket 路径。 */
    private String podmanSocket = "unix:///run/podman/podman.sock";

    // 省略 getter 和 setter

    public enum SandboxBackend { NONE, DOCKER, PODMAN }
}
```

#### SandboxFsBridge（内部配置）

```java
/**
 * 主机-沙箱文件共享的文件系统桥接配置。
 */
public class SandboxFsBridge {

    /** 要桥接到沙箱中的主机工作空间目录（只读）。 */
    private String hostWorkspace = "./workspace";

    /** 容器内挂载主机工作空间的路径。 */
    private String sandboxWorkspace = "/workspace";

    /** 容器内的工作空间挂载是否为只读。 */
    private boolean workspaceReadOnly = true;

    /** 沙箱可写文件的主机临时目录。 */
    private String hostTmp = "./sandbox-tmp";

    /** 容器内可写临时文件的路径。 */
    private String sandboxTmp = "/tmp/sandbox";

    /** tmp 卷的最大大小（MB）。 */
    private long tmpMaxSizeMb = 500;

    // 省略 getter 和 setter
}
```

### 4.2.3 SandboxExecutionService

```java
package lyjew.com.lyclaw.security.sandbox;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.*;
import com.github.dockerjava.api.model.*;
import com.github.dockerjava.core.DefaultDockerClientConfig;
import com.github.dockerjava.core.DockerClientImpl;
import com.github.dockerjava.httpclient5.ApacheDockerHttpClient;
import com.github.dockerjava.transport.DockerHttpClient;

import lyjew.com.lyclaw.config.AgentSandboxConfig;
import lyjew.com.lyclaw.tool.Tool;
import lyjew.com.lyclaw.tool.ToolExecutionResult;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 基于容器的沙箱执行服务。
 *
 * <p>管理 Docker/Podman 容器生命周期，用于隔离的工具执行。
 * 与 SandboxHook 集成（在配置了容器后端时替换 SandboxLevel.PROCESS 下
 * 的直接 ToolSandbox 委托）。
 *
 * <h3>生命周期：</h3>
 * <ol>
 *   <li>createSandbox(config) — 拉取镜像，创建容器，启动它</li>
 *   <li>executeInSandbox(handle, tool, args) — 通过 docker exec 执行工具</li>
 *   <li>isHealthy(handle) — 检查容器运行状态</li>
 *   <li>destroy(handle) — 停止并删除容器</li>
 * </ol>
 */
public class SandboxExecutionService {

    private static final Logger log = LoggerFactory.getLogger(SandboxExecutionService.class);

    private final AgentSandboxConfig config;
    private final DockerClient dockerClient;
    private final Map<String, SandboxHandle> activeHandles = new ConcurrentHashMap<>();

    public SandboxExecutionService(AgentSandboxConfig config) {
        this.config = config;
        this.dockerClient = config.getBackend() == AgentSandboxConfig.SandboxBackend.DOCKER
                ? buildDockerClient(config) : null;
    }

    // ── Docker 客户端工厂 ──────────────────────────────────────────

    private DockerClient buildDockerClient(AgentSandboxConfig config) {
        DefaultDockerClientConfig clientConfig = DefaultDockerClientConfig.createDefaultConfigBuilder()
                .withDockerHost(config.getDockerSocket())
                .build();

        DockerHttpClient httpClient = new ApacheDockerHttpClient.Builder()
                .dockerHost(clientConfig.getDockerHost())
                .sslConfig(clientConfig.getSSLConfig())
                .maxConnections(100)
                .connectionTimeout(Duration.ofSeconds(30))
                .responseTimeout(Duration.ofSeconds(config.getTimeoutSeconds() + 10))
                .build();

        return DockerClientImpl.getInstance(clientConfig, httpClient);
    }

    // ── 沙箱生命周期 ──────────────────────────────────────────────

    /**
     * 创建并启动沙箱容器。
     *
     * @param sessionId 此沙箱所属的会话
     * @return 成功时发出 SandboxHandle 的 Mono
     */
    public Mono<SandboxHandle> createSandbox(String sessionId) {
        if (config.getBackend() == AgentSandboxConfig.SandboxBackend.NONE) {
            return Mono.just(SandboxHandle.none());
        }

        return Mono.fromCallable(() -> {
            String containerName = "lyclaw-sandbox-" + sessionId + "-" + UUID.randomUUID().toString().substring(0, 8);

            log.info("创建沙箱容器：name={} image={}", containerName, config.getImage());

            // 如果不存在则拉取镜像
            try {
                dockerClient.pullImageCmd(config.getImage()).start().awaitCompletion();
            } catch (Exception e) {
                log.warn("镜像拉取失败（可能本地已存在）：{}", e.getMessage());
            }

            // 构建带资源限制的主机配置
            HostConfig hostConfig = HostConfig.newHostConfig()
                    .withMemory(config.getMemoryLimitMb() * 1024 * 1024) // 字节
                    .withNanoCPUs((long)(config.getCpuLimit() * 1_000_000_000L))
                    .withNetworkMode(config.isNetworkEnabled() ? "bridge" : "none")
                    .withReadonlyRootfs(!config.isFileSystemWriteEnabled())
                    .withAutoRemove(true);

            // 挂载卷
            List<com.github.dockerjava.api.model.Bind> binds = new ArrayList<>();

            // 工作空间挂载（如配置则为只读）
            Path hostWorkspace = Paths.get(config.getFsBridge().getHostWorkspace())
                    .toAbsolutePath().normalize();
            Files.createDirectories(hostWorkspace);
            String workspaceMode = config.getFsBridge().isWorkspaceReadOnly() ? "ro" : "rw";
            binds.add(new Bind(hostWorkspace.toString(),
                    new com.github.dockerjava.api.model.Volume(config.getFsBridge().getSandboxWorkspace()),
                    AccessMode.valueOf(workspaceMode)));

            // Tmp 挂载（读写）
            Path hostTmp = Paths.get(config.getFsBridge().getHostTmp())
                    .toAbsolutePath().normalize();
            Files.createDirectories(hostTmp);
            binds.add(new Bind(hostTmp.toString(),
                    new com.github.dockerjava.api.model.Volume(config.getFsBridge().getSandboxTmp()),
                    AccessMode.rw));

            hostConfig.withBinds(binds);

            // 创建容器
            CreateContainerCmd createCmd = dockerClient.createContainerCmd(config.getImage())
                    .withName(containerName)
                    .withHostConfig(hostConfig)
                    .withWorkingDir(config.getRootDir())
                    .withCmd("sleep", "infinity") // 保持容器存活
                    .withAttachStdin(false)
                    .withAttachStdout(true)
                    .withAttachStderr(true);

            CreateContainerResponse createResp = createCmd.exec();
            String containerId = createResp.getId();

            // 启动容器
            dockerClient.startContainerCmd(containerId).exec();

            // 等待容器就绪
            boolean ready = waitForContainerReady(containerId, config.getStartupTimeoutSeconds());
            if (!ready) {
                throw new RuntimeException("沙箱容器启动超时：" + containerName);
            }

            SandboxHandle handle = new SandboxHandle(sessionId, containerId, containerName);
            activeHandles.put(sessionId, handle);

            log.info("沙箱容器已启动：containerId={} name={}", containerId, containerName);
            return handle;
        }).subscribeOn(Schedulers.boundedElastic());
    }

    // ── 工具执行 ─────────────────────────────────────────────────

    /**
     * 在沙箱容器内执行工具。
     *
     * @param handle 要在其中执行的沙箱
     * @param tool   工具定义
     * @param args   工具参数
     * @return 发出执行结果的 Mono
     */
    public Mono<ToolExecutionResult> executeInSandbox(SandboxHandle handle, Tool tool,
                                                       Map<String, Object> args) {
        if (handle.isNone()) {
            return Mono.just(ToolExecutionResult.failure("没有可用的沙箱容器"));
        }

        return Mono.fromCallable(() -> {
            // 构建 docker exec 命令
            String[] cmd = buildExecCommand(tool, args);

            // 对照允许/拒绝列表验证
            if (!isCommandAllowed(cmd[0])) {
                return ToolExecutionResult.failure("命令 '" + cmd[0] + "' 不允许在沙箱中执行");
            }

            ExecCreateCmdResponse execCreate = dockerClient.execCreateCmd(handle.getContainerId())
                    .withCmd(cmd)
                    .withAttachStdout(true)
                    .withAttachStderr(true)
                    .withTty(false)
                    .exec();

            // 捕获输出
            StringBuilder stdout = new StringBuilder();
            StringBuilder stderr = new StringBuilder();

            try {
                dockerClient.execStartCmd(execCreate.getId())
                        .withDetach(false)
                        .exec(new com.github.dockerjava.api.async.ResultCallback.Adapter<>() {
                            @Override
                            public void onNext(com.github.dockerjava.api.model.Frame frame) {
                                String text = new String(frame.getPayload());
                                if (frame.getStreamType() == com.github.dockerjava.api.model.StreamType.STDOUT) {
                                    stdout.append(text);
                                } else {
                                    stderr.append(text);
                                }
                            }
                        })
                        .awaitCompletion(config.getTimeoutSeconds(), java.util.concurrent.TimeUnit.SECONDS);
            } catch (Exception e) {
                log.error("沙箱执行超时或失败：{}", e.getMessage());
                return ToolExecutionResult.failure("沙箱执行错误：" + e.getMessage());
            }

            // 检查退出码
            InspectExecResponse execInspect = dockerClient.inspectExecCmd(execCreate.getId()).exec();
            int exitCode = execInspect.getExitCode() != null ? execInspect.getExitCode() : -1;

            if (exitCode == 0) {
                return ToolExecutionResult.success(stdout.toString().trim());
            } else {
                String error = stderr.length() > 0 ? stderr.toString().trim() : stdout.toString().trim();
                return ToolExecutionResult.failure("退出码 " + exitCode + "：" + error);
            }
        }).subscribeOn(Schedulers.boundedElastic());
    }

    // ── 文件系统桥接 ──────────────────────────────────────────────

    /**
     * 将文件从主机复制到沙箱容器。
     */
    public Mono<Void> bridgeFileToSandbox(String hostPath, String sandboxPath,
                                          SandboxHandle handle) {
        if (handle.isNone()) return Mono.empty();

        return Mono.fromRunnable(() -> {
            try {
                Path hostFile = Paths.get(hostPath);
                if (!Files.exists(hostFile)) {
                    log.warn("主机文件不存在：{}", hostPath);
                    return;
                }

                try (InputStream tarStream = createTarArchive(hostFile)) {
                    dockerClient.copyArchiveToContainerCmd(handle.getContainerId())
                            .withRemotePath(Paths.get(sandboxPath))
                            .withTarInputStream(tarStream)
                            .exec();
                }
                log.debug("文件已桥接到沙箱：{} -> {}:{}",
                        hostPath, handle.getContainerId(), sandboxPath);
            } catch (Exception e) {
                log.error("桥接文件到沙箱失败：{}", e.getMessage());
            }
        }).subscribeOn(Schedulers.boundedElastic()).then();
    }

    /**
     * 将文件从沙箱容器复制到主机。
     */
    public Mono<Void> bridgeFileFromSandbox(String sandboxPath, String hostPath,
                                            SandboxHandle handle) {
        if (handle.isNone()) return Mono.empty();

        return Mono.fromRunnable(() -> {
            try {
                Path hostDir = Paths.get(hostPath).getParent();
                if (hostDir != null) {
                    Files.createDirectories(hostDir);
                }

                try (InputStream tarStream = dockerClient.copyArchiveFromContainerCmd(
                        handle.getContainerId(), sandboxPath).exec()) {
                    extractTarArchive(tarStream, Paths.get(hostPath));
                }
                log.debug("文件已从沙箱桥接：{}:{} -> {}",
                        handle.getContainerId(), sandboxPath, hostPath);
            } catch (Exception e) {
                log.error("从沙箱桥接文件失败：{}", e.getMessage());
            }
        }).subscribeOn(Schedulers.boundedElastic()).then();
    }

    // ── 健康检查 ───────────────────────────────────────────────────

    /**
     * 检查沙箱容器是否仍然健康。
     */
    public Mono<Boolean> isHealthy(SandboxHandle handle) {
        if (handle.isNone()) return Mono.just(false);

        return Mono.fromCallable(() -> {
            try {
                InspectContainerResponse inspect = dockerClient.inspectContainerCmd(handle.getContainerId()).exec();
                return inspect.getState() != null && Boolean.TRUE.equals(inspect.getState().getRunning());
            } catch (Exception e) {
                log.warn("容器 {} 健康检查失败：{}", handle.getContainerId(), e.getMessage());
                return false;
            }
        }).subscribeOn(Schedulers.boundedElastic());
    }

    // ── 销毁 ────────────────────────────────────────────────────────

    /**
     * 停止并删除沙箱容器，释放所有资源。
     */
    public Mono<Void> destroy(SandboxHandle handle) {
        if (handle.isNone()) return Mono.empty();

        return Mono.fromRunnable(() -> {
            try {
                dockerClient.stopContainerCmd(handle.getContainerId())
                        .withTimeout(10)
                        .exec();
                // 已配置自动删除，所以显式删除是可选的
                log.info("沙箱容器已销毁：containerId={}", handle.getContainerId());
            } catch (Exception e) {
                log.warn("销毁沙箱容器 {} 出错：{}",
                        handle.getContainerId(), e.getMessage());
                // 作为回退方案强制删除
                try {
                    dockerClient.removeContainerCmd(handle.getContainerId())
                            .withForce(true)
                            .exec();
                } catch (Exception f) {
                    log.error("容器 {} 强制删除也失败：{}",
                            handle.getContainerId(), f.getMessage());
                }
            } finally {
                activeHandles.remove(handle.getSessionId());
            }
        }).subscribeOn(Schedulers.boundedElastic()).then();
    }

    /**
     * 销毁所有活跃沙箱容器。在应用关闭时调用。
     */
    public Mono<Void> destroyAll() {
        return Flux.fromIterable(new ArrayList<>(activeHandles.values()))
                .flatMap(this::destroy)
                .then();
    }

    // ── 私有辅助方法 ────────────────────────────────────────────────

    private boolean waitForContainerReady(String containerId, int timeoutSeconds) {
        long deadline = System.currentTimeMillis() + timeoutSeconds * 1000L;
        while (System.currentTimeMillis() < deadline) {
            try {
                InspectContainerResponse inspect = dockerClient.inspectContainerCmd(containerId).exec();
                if (inspect.getState() != null && Boolean.TRUE.equals(inspect.getState().getRunning())) {
                    return true;
                }
                Thread.sleep(500);
            } catch (Exception e) {
                // 容器可能尚未就绪
            }
        }
        return false;
    }

    private String[] buildExecCommand(Tool tool, Map<String, Object> args) {
        // 对于命令工具，包装在 bash -c 中
        // 对于脚本工具，先写入脚本到 /tmp 然后执行
        String command = args.getOrDefault("command", "").toString();
        if (command.isEmpty()) {
            command = tool.getDescription();
        }
        return new String[]{"bash", "-c", command};
    }

    private boolean isCommandAllowed(String command) {
        List<String> allowed = config.getAllowedCommands();
        List<String> denied = config.getDeniedCommands();

        // 如果配置了白名单，只有白名单中的命令可以通过
        if (!allowed.isEmpty()) {
            return allowed.stream().anyMatch(cmd -> command.startsWith(cmd));
        }

        // 如果配置了黑名单，拒绝匹配的命令
        if (!denied.isEmpty()) {
            if (denied.stream().anyMatch(cmd -> command.startsWith(cmd))) {
                return false;
            }
        }

        // 无显式规则 = 允许全部（向后兼容）
        return true;
    }

    private InputStream createTarArchive(Path file) throws IOException {
        // 单文件的最小 TAR 创建（生产环境中使用 Apache Commons Compress）
        java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
        // 简化：实际代码中使用适当的 TAR 库
        // 这是展示集成模式的占位符
        baos.write(("tar-content:" + file.getFileName()).getBytes());
        return new java.io.ByteArrayInputStream(baos.toByteArray());
    }

    private void extractTarArchive(InputStream tarStream, Path destPath) {
        // 简化：实际代码中使用适当的 TAR 库
        // 展示集成模式的占位符
    }
}
```

### 4.2.4 SandboxHandle

```java
package lyjew.com.lyclaw.security.sandbox;

/**
 * 活跃沙箱容器的句柄。
 * <p>创建后不可变；用作沙箱生命周期操作的键。
 */
public class SandboxHandle {

    private final String sessionId;
    private final String containerId;
    private final String containerName;
    private final boolean none;

    private SandboxHandle(String sessionId, String containerId, String containerName, boolean none) {
        this.sessionId = sessionId;
        this.containerId = containerId;
        this.containerName = containerName;
        this.none = none;
    }

    public SandboxHandle(String sessionId, String containerId, String containerName) {
        this(sessionId, containerId, containerName, false);
    }

    /** 当没有配置沙箱后端时创建空操作句柄。 */
    public static SandboxHandle none() {
        return new SandboxHandle("", "", "", true);
    }

    public boolean isNone() { return none; }
    public String getSessionId() { return sessionId; }
    public String getContainerId() { return containerId; }
    public String getContainerName() { return containerName; }

    @Override
    public String toString() {
        return none ? "SandboxHandle[NONE]" :
                "SandboxHandle[session=" + sessionId + ", container=" + containerId + "]";
    }
}
```

### 4.2.5 与 SandboxHook 的集成

现有的 `SandboxHook` 当前委托给 `ToolSandbox.execute(tool, args, level)`。在第四阶段中，`SandboxHook` 更新为当请求 `SandboxLevel.PROCESS` 且配置了容器后端时使用 `SandboxExecutionService`：

```java
// 更新后的 SandboxHook.wrapToolExecutor()：
//
//   SandboxLevel level = ctx.getSandboxLevel() != null ? ctx.getSandboxLevel() : SandboxLevel.DIRECT;
//
//   if (level == SandboxLevel.PROCESS && sandboxExecutionService != null) {
//       // 基于容器的沙箱
//       SandboxHandle handle = ctx.getSandboxHandle();
//       if (handle == null) {
//           // 为此会话延迟创建沙箱
//           handle = sandboxExecutionService.createSandbox(ctx.getSessionId()).block();
//           ctx.setSandboxHandle(handle);
//       }
//       return sandboxExecutionService.executeInSandbox(handle, tool, args)
//               .map(result -> result.isSuccess() ? result.getResult() : "错误：" + result.getError())
//               .block();
//   }
//
//   // 回退：DIRECT 和 SANDBOX 级别的旧版 toolSandbox
//   ToolExecutionResult result = toolSandbox.execute(tool, args, level);
//   return result.isSuccess() ? result.getResult() : "错误：" + result.getError();
```

`AgentContext` 扩展了新的字段：

```java
// 添加到 AgentContext：
private volatile SandboxHandle sandboxHandle;
public SandboxHandle getSandboxHandle() { return sandboxHandle; }
public void setSandboxHandle(SandboxHandle handle) { this.sandboxHandle = handle; }
```

### 4.2.6 YAML 配置

```yaml
# application.yml — 沙箱配置
lyclaw:
  sandbox:
    backend: DOCKER                  # NONE | DOCKER | PODMAN
    image: ubuntu:22.04
    root-dir: /sandbox
    allowed-commands:
      - python3
      - node
      - bash
      - cat
      - ls
      - grep
      - sed
      - awk
    denied-commands:
      - rm
      - dd
      - mkfs
      - shutdown
      - reboot
    network-enabled: false
    file-system-write-enabled: true
    memory-limit-mb: 512
    cpu-limit: 1.0
    timeout-seconds: 300
    startup-timeout-seconds: 30
    reuse-container: true
    container-idle-timeout-seconds: 600
    docker-socket: unix:///var/run/docker.sock
    podman-socket: unix:///run/podman/podman.sock
    fs-bridge:
      host-workspace: ./workspace
      sandbox-workspace: /workspace
      workspace-read-only: true
      host-tmp: ./sandbox-tmp
      sandbox-tmp: /tmp/sandbox
      tmp-max-size-mb: 500
```

---

## 4.3 心跳系统

### 4.3.1 动机

长期运行的智能体需要定期的"签到"ping 以：
- 验证智能体仍在运行
- 向用户提供主动状态更新
- 执行计划中的维护任务
- 支持"每日简报" / "早晨摘要"模式

心跳系统是一个类 cron 调度器，按计划运行单轮 ReAct 调用，具有可配置的轻量上下文、隔离会话和目标投递。

### 4.3.2 HeartbeatConfig

```java
package lyjew.com.lyclaw.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * 智能体心跳配置。
 * <p>控制计划中的"ping"调用，保持智能体活跃
 * 并向用户传递定期更新。
 */
@ConfigurationProperties(prefix = "lyclaw.heartbeat")
public class HeartbeatConfig {

    /** 为此智能体启用心跳调度器。 */
    private boolean enabled = false;

    /** 心跳运行之间的类 Cron 间隔。 */
    private Duration every = Duration.ofMinutes(30);

    /** 活跃时间窗口（时间范围的 cron 表达式，例如 "0 0 9 ? * MON-FRI"）。 */
    private String activeHoursCron;

    /** 使用人类可读格式的活跃时间配置。 */
    private ActiveHours activeHours = new ActiveHours();

    /** 心跳运行的模型覆盖（null 时使用智能体默认值）。 */
    private String model;

    /** 心跳运行分组的会话键（默认为智能体名称）。 */
    private String sessionKey;

    /** 投递心跳结果的目标。 */
    private DeliveryTarget target = DeliveryTarget.LAST;

    /** 当目标指定用户/频道时的私信策略。 */
    private DirectPolicy directPolicy = DirectPolicy.ALLOW;

    /** 目标接收者：E.164 电话号码或聊天频道 ID。 */
    private String to;

    /** 多账户频道选择的账户 ID。 */
    private String accountId;

    /** 自定义心跳提示。如果为空/null，使用默认系统提示。 */
    private String prompt;

    /** 如果为 true，在心跳上下文中包含系统提示部分。 */
    private boolean includeSystemPromptSection = true;

    /** 心跳确认消息的最大字符数。 */
    private int ackMaxChars = 30;

    /** 抑制心跳运行中的工具执行错误警告。 */
    private boolean suppressToolErrorWarnings = true;

    /** 心跳执行超时（秒）。 */
    private int timeoutSeconds = 120;

    /**
     * 如果为 true，使用轻量上下文（仅 HEARTBEAT.md）。
     * 为 false 时，加载包含所有记忆文件的完整智能体上下文。
     */
    private boolean lightContext = true;

    /**
     * 如果为 true，为每次心跳运行创建全新的隔离会话。
     * sessionKey 被复用但消息历史不会延续。
     */
    private boolean isolatedSession = true;

    /**
     * 如果为 true，当子智能体活跃运行时跳过心跳。
     * 防止心跳中断正在进行的委派任务。
     */
    private boolean skipWhenBusy = true;

    /**
     * 如果为 true，在心跳响应中包含推理/思考内容。
     */
    private boolean includeReasoning = false;

    // 省略 getter 和 setter

    public enum DeliveryTarget { LAST, NONE }
    public enum DirectPolicy { ALLOW, BLOCK }

    /**
     * 活跃时间窗口配置。
     */
    public static class ActiveHours {
        /** 窗口开始时间，HH:mm 格式。 */
        private String start = "09:00";
        /** 窗口结束时间，HH:mm 格式。 */
        private String end = "18:00";
        /** 时区标识符，例如 "Asia/Shanghai"、"America/New_York"。 */
        private String timezone = "Asia/Shanghai";
        /** 星期几（MON、TUE、...、SUN）或空表示所有天。 */
        private String daysOfWeek = "";

        // 省略 getter 和 setter
    }
}
```

### 4.3.3 HeartbeatScheduler

```java
package lyjew.com.lyclaw.react.heartbeat;

import lyjew.com.lyclaw.chat.ChatFacade;
import lyjew.com.lyclaw.config.HeartbeatConfig;
import lyjew.com.lyclaw.model.ChatRequest;
import lyjew.com.lyclaw.model.Message;
import lyjew.com.lyclaw.react.AgentContext;
import lyjew.com.lyclaw.react.DefaultReActEngine;
import lyjew.com.lyclaw.react.ToolExecutor;
import lyjew.com.lyclaw.tool.ToolRegistry;
import lyjew.com.lyclaw.event.EventBus;
import lyjew.com.lyclaw.security.SecurityManager;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;
import org.springframework.scheduling.support.CronTrigger;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 基于 Cron 的智能体心跳调度器。
 *
 * <p>实现 {@link SchedulingConfigurer} 以根据每个智能体的
 * {@link HeartbeatConfig} 动态注册心跳任务。
 *
 * <h3>每次心跳 tick 的执行流程：</h3>
 * <ol>
 *   <li>检查 activeHours 窗口 — 如果不在范围内则跳过</li>
 *   <li>检查 skipWhenBusy — 如果子智能体活跃则跳过</li>
 *   <li>创建隔离会话（如果 isolatedSession 为 true）</li>
 *   <li>加载轻量上下文（如果 lightContext — 仅 HEARTBEAT.md）</li>
 *   <li>运行带心跳提示的单轮 ReAct</li>
 *   <li>将结果投递到目标频道/用户</li>
 *   <li>分发 heartbeat_start / heartbeat_reply / heartbeat_complete 事件</li>
 * </ol>
 */
public class HeartbeatScheduler {

    private static final Logger log = LoggerFactory.getLogger(HeartbeatScheduler.class);

    private final ChatFacade chatFacade;
    private final ToolRegistry toolRegistry;
    private final EventBus eventBus;
    private final SecurityManager securityManager;

    // 智能体 sessionKey → 配置的映射，用于动态调度
    private final Map<String, HeartbeatConfig> agentConfigs = new ConcurrentHashMap<>();

    // 跟踪每个智能体的活跃子智能体数量
    private final Map<String, AtomicInteger> activeSubAgents = new ConcurrentHashMap<>();

    // 用于取消的 ScheduledFuture 句柄
    private final Map<String, ScheduledFuture<?>> scheduledTasks = new ConcurrentHashMap<>();

    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(4);

    public HeartbeatScheduler(ChatFacade chatFacade, ToolRegistry toolRegistry,
                               EventBus eventBus, SecurityManager securityManager) {
        this.chatFacade = chatFacade;
        this.toolRegistry = toolRegistry;
        this.eventBus = eventBus;
        this.securityManager = securityManager;
    }

    /**
     * 为智能体注册或更新心跳配置。
     * 在智能体初始化时调用。
     *
     * @param agentId 智能体标识符
     * @param config  心跳配置
     */
    public void registerAgent(String agentId, HeartbeatConfig config) {
        if (config == null || !config.isEnabled()) {
            // 移除任何现有的调度
            cancelSchedule(agentId);
            agentConfigs.remove(agentId);
            return;
        }

        agentConfigs.put(agentId, config);

        // 取消现有调度并创建新的
        cancelSchedule(agentId);
        scheduleAgent(agentId, config);
    }

    /**
     * 通知调度器给定父智能体的子智能体已启动。
     * 由 skipWhenBusy 用于在委派期间推迟心跳。
     */
    public void onSubAgentStarted(String parentAgentId) {
        activeSubAgents.computeIfAbsent(parentAgentId, k -> new AtomicInteger(0))
                .incrementAndGet();
    }

    /**
     * 通知调度器给定父智能体的子智能体已完成。
     */
    public void onSubAgentCompleted(String parentAgentId) {
        AtomicInteger count = activeSubAgents.get(parentAgentId);
        if (count != null && count.decrementAndGet() <= 0) {
            activeSubAgents.remove(parentAgentId);
        }
    }

    // ── 内部调度 ────────────────────────────────────────────

    private void scheduleAgent(String agentId, HeartbeatConfig config) {
        long intervalMs = config.getEvery().toMillis();

        ScheduledFuture<?> future = scheduler.scheduleAtFixedRate(
                () -> executeHeartbeat(agentId, config),
                intervalMs, // 初始延迟与间隔相同
                intervalMs,
                TimeUnit.MILLISECONDS
        );

        scheduledTasks.put(agentId, future);
        log.info("已为智能体 '{}' 安排心跳：每 {} 秒", agentId,
                config.getEvery().getSeconds());
    }

    private void cancelSchedule(String agentId) {
        ScheduledFuture<?> future = scheduledTasks.remove(agentId);
        if (future != null) {
            future.cancel(false);
            log.info("已取消智能体 '{}' 的心跳", agentId);
        }
    }

    // ── 心跳执行 ────────────────────────────────────────────

    private void executeHeartbeat(String agentId, HeartbeatConfig config) {
        try {
            // 1. 检查活跃时间窗口
            if (!isWithinActiveHours(config.getActiveHours())) {
                log.debug("智能体 '{}' 心跳跳过：不在活跃时间内", agentId);
                return;
            }

            // 2. 检查 skipWhenBusy
            if (config.isSkipWhenBusy()) {
                AtomicInteger count = activeSubAgents.get(agentId);
                if (count != null && count.get() > 0) {
                    log.debug("智能体 '{}' 心跳跳过：{} 个子智能体活跃中", agentId, count.get());
                    return;
                }
            }

            // 3. 创建会话键
            String sessionKey = config.getSessionKey() != null ? config.getSessionKey() : agentId;
            String runId = sessionKey + "-" + UUID.randomUUID().toString().substring(0, 8);
            long startMs = System.currentTimeMillis();

            log.info("心跳开始：agent={} runId={}", agentId, runId);

            // 4. 准备上下文
            AgentContext ctx = buildHeartbeatContext(agentId, sessionKey, runId, config);

            // 5. 运行单轮 ReAct
            String result = runHeartbeatReAct(ctx, config);

            long elapsedMs = System.currentTimeMillis() - startMs;

            // 6. 投递结果
            deliverHeartbeatResult(agentId, config, result);

            // 7. 分发事件
            dispatchHeartbeatEvent("heartbeat_complete", agentId, runId,
                    Map.of("elapsedMs", elapsedMs, "message", result.substring(0,
                            Math.min(result.length(), config.getAckMaxChars()))));

            log.info("心跳完成：agent={} runId={} 耗时={}ms", agentId, runId, elapsedMs);

        } catch (Exception e) {
            log.error("智能体 '{}' 心跳失败：{}", agentId, e.getMessage(), e);
            dispatchHeartbeatEvent("heartbeat_error", agentId, null,
                    Map.of("error", e.getMessage()));
        }
    }

    private AgentContext buildHeartbeatContext(String agentId, String sessionKey,
                                                String runId, HeartbeatConfig config) {
        String prompt = config.getPrompt();
        if (prompt == null || prompt.isEmpty()) {
            prompt = "心跳签到。提供关于当前状态和待处理任务的简要状态更新。";
        }

        if (config.isIncludeSystemPromptSection()) {
            prompt = "[系统状态检查]\n" + prompt;
        }

        // 为此次单次心跳运行创建临时上下文
        AgentContext ctx = new AgentContext(
                config.isIsolatedSession() ? runId : sessionKey,
                prompt,
                null, // 系统提示由智能体配置处理
                toolRegistry,
                null, // 无方法 — 心跳不是用户调用
                null
        );

        if (config.isLightContext()) {
            // 仅加载 HEARTBEAT.md 上下文（由记忆系统实现）
            ctx.setAttribute("heartbeatMode", true);
            ctx.setAttribute("contextFiles", List.of("HEARTBEAT.md"));
        }

        return ctx;
    }

    private String runHeartbeatReAct(AgentContext ctx, HeartbeatConfig config) {
        // 构建心跳的最小 ChatRequest
        ChatRequest request = ChatRequest.builder()
                .messages(new ArrayList<>(List.of(Message.user(ctx.getUserMessage()))))
                .stream(false) // 心跳不使用流式
                .build();

        // 使用不带工具的 ReActEngine 实例以进行轻量执行
        DefaultReActEngine engine = new DefaultReActEngine(null, null) {
            @Override
            public String execute(ChatFacade chatFacade, ChatRequest request,
                                  ToolExecutor toolExecutor) {
                // 单轮：心跳默认不进行工具调用
                try {
                    var model = chatFacade.resolveModel(chatFacade.route(request, null));
                    var response = model.chat(request);
                    String content = response.getContent();
                    request.getMessages().add(Message.assistant(content != null ? content : ""));
                    return content != null ? content : "（无响应）";
                } catch (Exception e) {
                    log.error("心跳 LLM 调用失败：{}", e.getMessage());
                    return "[心跳 LLM 错误：" + e.getMessage() + "]";
                }
            }
        };

        try {
            String result = engine.execute(chatFacade, request, null);
            return result != null ? result : "（空响应）";
        } catch (Exception e) {
            return "[心跳错误：" + e.getMessage() + "]";
        }
    }

    private void deliverHeartbeatResult(String agentId, HeartbeatConfig config, String result) {
        if (config.getTarget() == HeartbeatConfig.DeliveryTarget.NONE) {
            return;
        }

        // 投递到目标频道/用户（实现取决于频道适配器）
        // 目前发布为事件供频道适配器获取
        Map<String, Object> delivery = new LinkedHashMap<>();
        delivery.put("agentId", agentId);
        delivery.put("message", result);
        delivery.put("to", config.getTo());
        delivery.put("accountId", config.getAccountId());
        delivery.put("timestamp", Instant.now().toString());

        eventBus.publish(new HeartbeatDeliveryEvent("heartbeat-scheduler", agentId, delivery));
    }

    private void dispatchHeartbeatEvent(String eventType, String agentId, String runId,
                                         Map<String, Object> data) {
        Map<String, Object> payload = new LinkedHashMap<>(data);
        payload.put("agentId", agentId);
        if (runId != null) payload.put("runId", runId);
        payload.put("timestamp", Instant.now().toString());
        eventBus.publish(new HeartbeatEvent("heartbeat-scheduler", eventType, payload));
    }

    // ── 活跃时间检查 ─────────────────────────────────────────────

    private boolean isWithinActiveHours(HeartbeatConfig.ActiveHours hours) {
        if (hours == null || hours.getStart() == null || hours.getEnd() == null) {
            return true; // 无限制
        }

        try {
            ZoneId zone = ZoneId.of(hours.getTimezone());
            ZonedDateTime now = ZonedDateTime.now(zone);

            LocalTime start = LocalTime.parse(hours.getStart(), DateTimeFormatter.ofPattern("HH:mm"));
            LocalTime end = LocalTime.parse(hours.getEnd(), DateTimeFormatter.ofPattern("HH:mm"));
            LocalTime current = now.toLocalTime();

            // 如果配置了星期几则检查
            if (hours.getDaysOfWeek() != null && !hours.getDaysOfWeek().isEmpty()) {
                String today = now.getDayOfWeek().name().substring(0, 3).toUpperCase();
                if (!hours.getDaysOfWeek().toUpperCase().contains(today)) {
                    return false;
                }
            }

            if (start.isBefore(end)) {
                // 正常范围：例如 09:00 - 18:00
                return !current.isBefore(start) && current.isBefore(end);
            } else {
                // 跨夜范围：例如 22:00 - 06:00
                return !current.isBefore(start) || current.isBefore(end);
            }
        } catch (Exception e) {
            log.warn("活跃时间检查失败，默认允许：{}", e.getMessage());
            return true;
        }
    }
}
```

### 4.3.4 心跳事件类型

```java
package lyjew.com.lyclaw.react.heartbeat;

import lyjew.com.lyclaw.event.Event;

import java.util.Map;

/**
 * 心跳生命周期事件。在心跳运行的每个阶段发布。
 *
 * <p>事件类型：
 * <ul>
 *   <li>heartbeat_start — agentId、sessionKey、timestamp</li>
 *   <li>heartbeat_thinking — agentId（LLM 正在生成）</li>
 *   <li>heartbeat_reply — agentId、message</li>
 *   <li>heartbeat_complete — agentId、elapsedMs、消息预览</li>
 *   <li>heartbeat_error — agentId、error</li>
 * </ul>
 */
public class HeartbeatEvent extends Event {

    private final Map<String, Object> data;

    public HeartbeatEvent(String source, String eventType, Map<String, Object> data) {
        super(source, "heartbeat." + eventType);
        this.data = data;
    }

    public Map<String, Object> getData() { return data; }
}

/**
 * 心跳投递事件。当心跳结果需要投递到
 * 目标频道或用户时发布。
 */
class HeartbeatDeliveryEvent extends Event {

    private final Map<String, Object> deliveryData;

    public HeartbeatDeliveryEvent(String source, String agentId, Map<String, Object> deliveryData) {
        super(source, "heartbeat.delivery");
        this.deliveryData = deliveryData;
    }

    public Map<String, Object> getDeliveryData() { return deliveryData; }
}
```

### 4.3.5 SSE 事件模式

心跳 SSE 事件（当心跳由外部请求而非 cron 触发时）：

| 事件 | `event:` 字段 | `data:` 结构 |
|---|---|---|
| `heartbeat_start` | `heartbeat_start` | `{"agentId":"...", "sessionKey":"...", "timestamp":"..."}` |
| `heartbeat_thinking` | `heartbeat_thinking` | `{"agentId":"..."}` |
| `heartbeat_reply` | `heartbeat_reply` | `{"agentId":"...", "message":"...", "..."}` |
| `heartbeat_complete` | `heartbeat_complete` | `{"agentId":"...", "elapsedMs":1234, "message":"预览..."}` |
| `heartbeat_error` | `heartbeat_error` | `{"agentId":"...", "error":"..."}` |

### 4.3.6 YAML 配置

```yaml
# application.yml — 每个智能体的心跳配置
lyclaw:
  heartbeat:
    enabled: true
    every: 30m                      # 持续时间：30m、1h 等
    active-hours:
      start: "09:00"
      end: "18:00"
      timezone: Asia/Shanghai
      days-of-week: MON,TUE,WED,THU,FRI
    model: null                     # null = 使用智能体默认值
    session-key: daily-checkin
    target: LAST                    # LAST | NONE
    direct-policy: ALLOW            # ALLOW | BLOCK
    to: null                        # E.164 电话或聊天 ID
    account-id: null                # 多账户选择器
    prompt: "早上好！这是您的每日简报。今天的首要任务是什么？"
    include-system-prompt-section: true
    ack-max-chars: 30
    suppress-tool-error-warnings: true
    timeout-seconds: 120
    light-context: true
    isolated-session: true
    skip-when-busy: true
    include-reasoning: false
```

---

## 4.4 运行重试增强

### 4.4.1 动机

当前的 `ReflexionLoop` 使用简单的 `maxRetries` 参数（通常为 2）和静态的 `qualityThreshold`（0.6）。这对生产环境来说是不够的：

- **硬编码的重试预算**：无按智能体或按回退模型的区分
- **无重试历史**：无法从之前的失败中学习以调整策略
- **无模型回退链**：如果主模型持续失败，没有机制来尝试替代（更便宜/更快/更小）模型
- **无重试元数据**：当前 `ReflexionResult.Attempt` 仅记录分数和反馈，不记录使用的模型/提供者

### 4.4.2 RunRetriesConfig

```java
package lyjew.com.lyclaw.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * ReAct 循环反思重试的运行重试配置。
 * <p>控制重试预算、策略选择和模型回退行为。
 */
@ConfigurationProperties(prefix = "lyclaw.retry")
public class RunRetriesConfig {

    /**
     * 主模型的基础重试迭代次数。
     * 总重试次数 = base + (perProfile * numberOfFallbackProfiles)
     */
    private int base = 24;

    /**
     * 每个回退模型配置文件分配的额外重试迭代次数。
     * 链中的每个回退模型获得此数量的额外尝试。
     */
    private int perProfile = 8;

    /**
     * 总重试迭代次数的最小下限。
     * 即使 base+perProfile*count 计算值更低，此下限也适用。
     */
    private int min = 32;

    /**
     * 总重试迭代次数的最大上限。
     * 防止无限制的重试循环。
     */
    private int max = 160;

    /**
     * 重试终止的质量阈值。
     * 如果反思分数达到或超过此阈值，重试提前停止。
     */
    private double qualityThreshold = 0.7;

    /**
     * 当需要重试时选择下一个模型的策略。
     * <ul>
     *   <li>SAME_MODEL — 使用相同模型重试（默认）</li>
     *   <li>FALLBACK_CHAIN — 尝试回退链中的下一个模型</li>
     *   <li>ADAPTIVE — 在 3 次连续的相同模型失败后切换到回退模型</li>
     * </ul>
     */
    private RetryStrategy defaultStrategy = RetryStrategy.ADAPTIVE;

    /**
     * 在升级到回退模型之前允许的最大连续失败次数
     * （仅在策略为 ADAPTIVE 时适用）。
     */
    private int maxConsecutiveFailuresBeforeFallback = 3;

    /**
     * 重试延迟的指数退避配置。
     */
    private RetryBackoff backoff = new RetryBackoff();

    // 省略 getter 和 setter

    public enum RetryStrategy { SAME_MODEL, FALLBACK_CHAIN, ADAPTIVE }

    /**
     * 重试延迟的指数退避。
     */
    public static class RetryBackoff {
        /** 初始延迟（毫秒）。 */
        private long initialDelayMs = 500;
        /** 最大延迟（毫秒）。 */
        private long maxDelayMs = 30_000;
        /** 退避乘数（例如 2.0 = 每次重试翻倍）。 */
        private double multiplier = 2.0;
        /** 退避适用于：BOTH = 模型调用 + 反思，LLM_ONLY、REFLECTION_ONLY */
        private BackoffTarget target = BackoffTarget.BOTH;

        // 省略 getter 和 setter
        public enum BackoffTarget { BOTH, LLM_ONLY, REFLECTION_ONLY }
    }
}
```

### 4.4.3 RunRetryManager

```java
package lyjew.com.lyclaw.react.retry;

import lyjew.com.lyclaw.config.RunRetriesConfig;
import lyjew.com.lyclaw.react.ReflexionResult;
import lyjew.com.lyclaw.task.ReflectionFeedback;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 管理 ReAct 循环反思重试的重试预算、跟踪和策略。
 *
 * <p>用可配置的、模型感知的重试系统替换硬编码的 MAX_REFLECTION_RETRIES=2，
 * 支持回退链和自适应策略选择。
 *
 * <h3>重试预算公式：</h3>
 * <pre>
 *   totalRetries = max(min, min(max, base + perProfile * fallbackProfileCount))
 * </pre>
 *
 * <h3>重试状态机：</h3>
 * <pre>
 *   [使用模型 M 执行]
 *        |
 *        v
 *   [反思] ──score >= threshold──> [完成]
 *        |
 *   score < threshold
 *        |
 *        v
 *   [检查重试预算] ──已耗尽──> [以最佳结果完成]
 *        |
 *   预算可用
 *        |
 *        v
 *   [选择策略：相同模型 / 回退]
 *        |
 *        v
 *   [计划修订] ──> [使用（新）模型 M' 执行]
 * </pre>
 */
public class RunRetryManager {

    private static final Logger log = LoggerFactory.getLogger(RunRetryManager.class);

    private final RunRetriesConfig config;
    private final List<String> fallbackProfiles;
    private final int maxRetries;

    // 每个会话的重试历史
    private final Map<String, RetrySession> sessions = new ConcurrentHashMap<>();

    public RunRetryManager(RunRetriesConfig config, List<String> fallbackProfiles) {
        this.config = config;
        this.fallbackProfiles = fallbackProfiles != null ? fallbackProfiles : List.of();
        this.maxRetries = calculateMaxRetries(config, this.fallbackProfiles.size());
    }

    /**
     * 计算总重试预算。
     */
    private int calculateMaxRetries(RunRetriesConfig config, int fallbackCount) {
        int total = config.getBase() + config.getPerProfile() * fallbackCount;
        return Math.max(config.getMin(), Math.min(config.getMax(), total));
    }

    /**
     * 获取会话的最大重试次数。
     */
    public int getMaxRetries(String sessionId) {
        return maxRetries;
    }

    /**
     * 检查给定会话是否有更多重试可用。
     *
     * @param sessionId 要检查的会话
     * @return 如果至少还有一次重试预算则返回 true
     */
    public boolean canRetry(String sessionId) {
        RetrySession session = sessions.get(sessionId);
        if (session == null) {
            return maxRetries > 0;
        }
        return session.getAttemptCount() < maxRetries;
    }

    /**
     * 记录会话的重试尝试。
     *
     * @param sessionId 会话标识符
     * @param attempt   已完成的重试尝试
     */
    public void recordRetry(String sessionId, RetryAttempt attempt) {
        RetrySession session = sessions.computeIfAbsent(sessionId, RetrySession::new);
        session.addAttempt(attempt);
        log.debug("重试已记录：session={} attempt={}/{} score={} model={}",
                sessionId, session.getAttemptCount(), maxRetries,
                attempt.getQualityScore(), attempt.getModelUsed());
    }

    /**
     * 根据历史确定重试策略。
     *
     * @param sessionId    会话标识符
     * @param primaryModel 主模型名称
     * @return 下一次尝试要使用的模型
     */
    public String determineNextModel(String sessionId, String primaryModel) {
        RetrySession session = sessions.get(sessionId);
        if (session == null || session.getAttemptCount() == 0) {
            return primaryModel;
        }

        RunRetriesConfig.RetryStrategy strategy = config.getDefaultStrategy();

        switch (strategy) {
            case SAME_MODEL:
                return primaryModel;

            case FALLBACK_CHAIN: {
                // 每次重试轮换回退模型
                int attemptIndex = session.getAttemptCount();
                if (attemptIndex < fallbackProfiles.size()) {
                    return fallbackProfiles.get(attemptIndex);
                }
                // 循环回退模型
                return fallbackProfiles.get(attemptIndex % fallbackProfiles.size());
            }

            case ADAPTIVE:
            default: {
                // 检查当前模型的连续失败次数
                int consecutiveFailures = session.countConsecutiveFailuresWithCurrentModel();
                if (consecutiveFailures >= config.getMaxConsecutiveFailuresBeforeFallback()) {
                    // 切换到下一个回退模型
                    int fallbackIndex = session.getCurrentFallbackIndex();
                    if (fallbackIndex < fallbackProfiles.size()) {
                        session.incrementFallbackIndex();
                        String fallback = fallbackProfiles.get(fallbackIndex);
                        log.info("自适应重试切换到回退模型：{} -> {}（连续失败 {} 次）",
                                session.getCurrentModel(), fallback, consecutiveFailures);
                        return fallback;
                    }
                    // 所有回退模型已用尽，继续使用主模型
                    return primaryModel;
                }
                return session.getCurrentModel() != null ? session.getCurrentModel() : primaryModel;
            }
        }
    }

    /**
     * 计算下一次重试的指数退避延迟。
     */
    public long calculateBackoffMs(String sessionId) {
        RetrySession session = sessions.get(sessionId);
        int attemptCount = session != null ? session.getAttemptCount() : 0;

        RunRetriesConfig.RetryBackoff backoff = config.getBackoff();
        long delay = (long)(backoff.getInitialDelayMs() *
                Math.pow(backoff.getMultiplier(), attemptCount));
        return Math.min(delay, backoff.getMaxDelayMs());
    }

    /**
     * 清除会话的重试状态（在会话完成/重置时调用）。
     */
    public void clearSession(String sessionId) {
        sessions.remove(sessionId);
    }

    /**
     * 获取监控用的重试统计信息。
     */
    public RetryStats getStats(String sessionId) {
        RetrySession session = sessions.get(sessionId);
        if (session == null) {
            return new RetryStats(0, 0, maxRetries, 0.0, 0.0);
        }
        return session.computeStats(maxRetries);
    }

    // ── 内部类型 ────────────────────────────────────────────────────

    /**
     * 每个会话的重试跟踪。
     */
    static class RetrySession {
        private final String sessionId;
        private final List<RetryAttempt> attempts = new ArrayList<>();
        private volatile int currentFallbackIndex = 0;
        private volatile String currentModel;

        RetrySession(String sessionId) { this.sessionId = sessionId; }

        void addAttempt(RetryAttempt attempt) {
            attempts.add(attempt);
            this.currentModel = attempt.getModelUsed();
        }

        int getAttemptCount() { return attempts.size(); }

        String getCurrentModel() { return currentModel; }

        int getCurrentFallbackIndex() { return currentFallbackIndex; }

        void incrementFallbackIndex() { currentFallbackIndex++; }

        int countConsecutiveFailuresWithCurrentModel() {
            int count = 0;
            for (int i = attempts.size() - 1; i >= 0; i--) {
                RetryAttempt a = attempts.get(i);
                if (currentModel != null && currentModel.equals(a.getModelUsed())
                        && a.getQualityScore() < 0.7) {
                    count++;
                } else {
                    break;
                }
            }
            return count;
        }

        RetryStats computeStats(int maxRetries) {
            if (attempts.isEmpty()) {
                return new RetryStats(0, 0, maxRetries, 0.0, 0.0);
            }
            double avgScore = attempts.stream().mapToDouble(RetryAttempt::getQualityScore).average().orElse(0.0);
            double bestScore = attempts.stream().mapToDouble(RetryAttempt::getQualityScore).max().orElse(0.0);
            return new RetryStats(attempts.size(), attempts.size(), maxRetries, avgScore, bestScore);
        }
    }

    /**
     * 单次重试尝试记录。
     */
    public static class RetryAttempt {
        private final int attemptNumber;
        private final String modelUsed;
        private final double qualityScore;
        private final List<String> errors;
        private final String suggestedStrategy;
        private final long elapsedMs;
        private final Instant timestamp;

        public RetryAttempt(int attemptNumber, String modelUsed, double qualityScore,
                            List<String> errors, String suggestedStrategy, long elapsedMs) {
            this.attemptNumber = attemptNumber;
            this.modelUsed = modelUsed;
            this.qualityScore = qualityScore;
            this.errors = errors != null ? errors : List.of();
            this.suggestedStrategy = suggestedStrategy;
            this.elapsedMs = elapsedMs;
            this.timestamp = Instant.now();
        }

        public static RetryAttempt fromReflexionResult(ReflexionResult.Attempt attempt,
                                                        String modelUsed) {
            ReflectionFeedback fb = attempt.getFeedback();
            return new RetryAttempt(
                    attempt.getAttemptNumber(),
                    modelUsed,
                    attempt.getQualityScore(),
                    fb != null ? fb.getDetectedErrors() : List.of(),
                    fb != null ? fb.getSuggestedStrategy() : null,
                    0 // elapsedMs 单独跟踪
            );
        }

        public int getAttemptNumber() { return attemptNumber; }
        public String getModelUsed() { return modelUsed; }
        public double getQualityScore() { return qualityScore; }
        public List<String> getErrors() { return errors; }
        public String getSuggestedStrategy() { return suggestedStrategy; }
        public long getElapsedMs() { return elapsedMs; }
        public Instant getTimestamp() { return timestamp; }
    }

    /**
     * 监控仪表盘用的重试统计快照。
     */
    public static class RetryStats {
        private final int attemptsUsed;
        private final int attemptsTotal;
        private final int budget;
        private final double avgQualityScore;
        private final double bestQualityScore;

        public RetryStats(int attemptsUsed, int attemptsTotal, int budget,
                          double avgQualityScore, double bestQualityScore) {
            this.attemptsUsed = attemptsUsed;
            this.attemptsTotal = attemptsTotal;
            this.budget = budget;
            this.avgQualityScore = avgQualityScore;
            this.bestQualityScore = bestQualityScore;
        }

        public int getAttemptsUsed() { return attemptsUsed; }
        public int getAttemptsTotal() { return attemptsTotal; }
        public int getBudget() { return budget; }
        public double getAvgQualityScore() { return avgQualityScore; }
        public double getBestQualityScore() { return bestQualityScore; }
        public int getRemainingBudget() { return budget - attemptsUsed; }
    }
}
```

### 4.4.4 与 AgentContext 的集成

扩展 `AgentContext` 以携带重试元数据：

```java
// AgentContext 新增内容：

/** 用于重试跟踪的运行元数据。存储在 attributes 中以保持可序列化。 */
public Map<String, Object> getRunMetadata() {
    @SuppressWarnings("unchecked")
    Map<String, Object> meta = (Map<String, Object>) getAttribute("runMetadata");
    if (meta == null) {
        meta = new HashMap<>();
        setAttribute("runMetadata", meta);
    }
    return meta;
}

public void recordRetryState(String modelUsed, double score, List<String> errors) {
    Map<String, Object> meta = getRunMetadata();
    meta.put("lastModelUsed", modelUsed);
    meta.put("lastScore", score);
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> history = (List<Map<String, Object>>)
            meta.computeIfAbsent("retryHistory", k -> new ArrayList<>());
    Map<String, Object> entry = new LinkedHashMap<>();
    entry.put("model", modelUsed);
    entry.put("score", score);
    entry.put("errors", errors);
    entry.put("timestamp", Instant.now().toString());
    history.add(entry);
}
```

### 4.4.5 与 ReflexionLoop 的集成

现有的 `ReflexionLoop` 增强为使用 `RunRetryManager`：

```java
// 增强后的 ReflexionLoop（与当前版本的差异）：
//
// 之前：
//   public ReflexionLoop(ReflectionEngine engine, TaskPlanner planner,
//                         int maxRetries, double qualityThreshold) { ... }
//
// 之后：
//   public class ReflexionLoop {
//       private final RunRetryManager retryManager;
//       private final String primaryModel;
//       ...
//
//       public ReflexionLoop(ReflectionEngine engine, TaskPlanner planner,
//                             RunRetryManager retryManager, String primaryModel) {
//           this.reflectionEngine = engine;
//           this.taskPlanner = planner;
//           this.retryManager = retryManager;
//           this.primaryModel = primaryModel;
//       }
//
//       public ReflexionResult execute(TaskPlan plan, ChatContext context,
//                                       StepExecutor executor) {
//           List<ReflexionResult.Attempt> attempts = new ArrayList<>();
//           TaskPlan currentPlan = plan;
//           String loopId = UUID.randomUUID().toString().substring(0, 8);
//           long startTime = System.currentTimeMillis();
//           String currentModel = primaryModel;
//
//           int attempt = 0;
//           while (retryManager.canRetry(context.getSessionId())) {
//               log.info("[Reflexion {}] 尝试 {}/{} model={}", loopId,
//                       attempt + 1, retryManager.getMaxRetries(context.getSessionId()), currentModel);
//
//               // 使用当前模型执行
//               ActionResult result = executePlan(currentPlan, executor);
//
//               // 反思
//               double score = reflect(context, result);
//
//               // 记录重试
//               retryManager.recordRetry(context.getSessionId(),
//                       new RunRetryManager.RetryAttempt(attempt, currentModel, score,
//                               extractErrors(result), null, 0));
//
//               attempts.add(new ReflexionResult.Attempt(attempt, result, score, buildFeedback(result)));
//
//               // 检查质量阈值
//               if (score >= qualityThreshold) break;
//
//               // 确定下一个模型
//               currentModel = retryManager.determineNextModel(
//                       context.getSessionId(), primaryModel);
//
//               // 应用退避
//               long backoffMs = retryManager.calculateBackoffMs(context.getSessionId());
//               if (backoffMs > 0) Thread.sleep(backoffMs);
//
//               // 修订计划
//               currentPlan = taskPlanner.revise(currentPlan, buildFeedback(result));
//               attempt++;
//           }
//
//           long totalMs = System.currentTimeMillis() - startTime;
//           return new ReflexionResult(loopId, attempts, totalMs);
//       }
//   }
```

### 4.4.6 YAML 配置

```yaml
# application.yml — 重试配置
lyclaw:
  retry:
    base: 24
    per-profile: 8
    min: 32
    max: 160
    quality-threshold: 0.7
    default-strategy: ADAPTIVE            # SAME_MODEL | FALLBACK_CHAIN | ADAPTIVE
    max-consecutive-failures-before-fallback: 3
    backoff:
      initial-delay-ms: 500
      max-delay-ms: 30000
      multiplier: 2.0
      target: BOTH                       # BOTH | LLM_ONLY | REFLECTION_ONLY
```

---

## 集成架构图

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                          第四阶段 — 系统架构                                 │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│  ┌─────────────┐    ┌──────────────────┐    ┌───────────────────────────┐  │
│  │ 用户请求     │───>│  管道阶段         │───>│  SSE 事件流               │  │
│  │ (HTTP/MQTT)  │    │                   │    │  (到 Web/App 客户端)      │  │
│  └─────────────┘    │  ContextBuild     │    └───────────────────────────┘  │
│                      │  SecurityCheck    │              ▲                    │
│                      │  PlanExecution    │              │                    │
│                      │  RespondStage ◄───┼──────────────┘                    │
│                      │    │              │    BlockStreamingController       │
│                      │    │              │    TypingIndicatorController      │
│                      │    │              │    HumanDelay                     │
│                      │  ReflectionStage  │                                   │
│                      │  MetricsStage     │                                   │
│                      └──────────────────┘                                   │
│                              │                                              │
│              ┌───────────────┼──────────────────┐                          │
│              │               │                  │                          │
│              v               v                  v                          │
│  ┌─────────────────┐ ┌────────────┐ ┌───────────────────┐                 │
│  │  ReActEngine     │ │ SandboxHook│ │  HeartbeatScheduler│                │
│  │  (流式)           │ │            │ │                    │                 │
│  │                  │ │ SandboxExe-│ │  Cron: 每 30 分钟   │                 │
│  │  BlockStreaming  │ │ cutionSvc  │ │  活跃时间检查       │                 │
│  │  Coalesce        │ │            │ │  轻量上下文         │                 │
│  │  HumanDelay      │ │ Docker/Pod-│ │  隔离会话           │                 │
│  │  TypingIndicator │ │ man 后端   │ │  忙时跳过           │                 │
│  └────────┬─────────┘ └─────┬──────┘ └─────────┬─────────┘                 │
│           │                 │                   │                           │
│           v                 v                   v                           │
│  ┌──────────────────────────────────────────────────┐                      │
│  │               RunRetryManager                     │                      │
│  │                                                   │                      │
│  │  重试预算：base + perProfile * fallbackCount       │                      │
│  │  策略：ADAPTIVE / FALLBACK_CHAIN / SAME_MODEL     │                      │
│  │  退避：指数级，具有可配置的上限                     │                      │
│  │  会话跟踪：每个会话的重试历史                       │                      │
│  └──────────────────────────────────────────────────┘                      │
│                                                                             │
│  ┌─────────────────────── 事件总线 ───────────────────────┐               │
│  │                                                         │               │
│  │  heartbeat_start   heartbeat_thinking  heartbeat_reply  │               │
│  │  heartbeat_complete  heartbeat_error  heartbeat_delivery│               │
│  │                                                         │               │
│  │  retry_attempt    retry_exhausted    retry_fallback     │               │
│  │                                                         │               │
│  │  sandbox_created  sandbox_destroyed  sandbox_health     │               │
│  └─────────────────────────────────────────────────────────┘               │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘
```

### 数据流：流式管道

```
LLM Token Stream (Flux<ModelResponse>)
     │
     ▼
┌──────────────────┐
│ 状态机            │  0=缓冲(思考中), 1=中继(流式token), 2=检测到工具
│ (DefaultReAct    │
│  Engine)         │
└────────┬─────────┘
         │  情况 1：state=1（纯文本流）
         │    → token 作为细粒度 SSE "message" 事件发出
         │
         │  情况 2：state=2（检测到工具）
         │    → 工具执行，然后是最终文本响应
         │    → 最终文本传递给 BlockStreamingController
         │
         ▼
┌──────────────────┐
│ BlockStreaming   │  segmentIntoBlocks() → coalesceBlocks() → suppressRepeats()
│ Controller       │
└────────┬─────────┘
         │
         ▼
┌──────────────────┐
│ HumanDelay       │  calculateDelay(block) → adaptiveSpeed → jitter
└────────┬─────────┘
         │
         ▼
┌──────────────────┐
│ TypingIndicator  │  在间隙期间按间隔发出 "typing" SSE 事件
└────────┬─────────┘
         │
         ▼
    SSE 客户端
```

### 沙箱执行流程

```
    工具调用请求
         │
         ▼
    SandboxHook.wrapToolExecutor()
         │
         ▼
    ctx.getSandboxLevel() == PROCESS && backend != NONE ?
         │
    ┌────┴────┐
    │   是    │               │   否    │
    ▼         ▼               ▼         ▼
┌─────────────────┐   ┌──────────────────┐
│ SandboxExecSvc  │   │ ToolSandbox       │
│                 │   │ （旧版 DIRECT/     │
│ createSandbox() │   │  SANDBOX 模式）   │
│ 如果不存在      │   └──────────────────┘
│                 │
│ executeInSandbox│
│                 │
│ docker exec     │
│ cmd [bash -c]   │
│                 │
│ 捕获 stdout     │
│ 检查退出码      │
└────────┬────────┘
         │
         ▼
    ToolExecutionResult
```

---

## SSE 事件模式参考

### 块流式事件

| 事件名称 | `event:` | `data:` 模式 |
|---|---|---|
| message（块） | `message` | `{"type":"message","content":"块文本..."}` |
| typing | `typing` | `{"type":"typing","agentId":"...","stage":"RESPOND"}` |

### 沙箱事件

| 事件名称 | `event:` | `data:` 模式 |
|---|---|---|
| sandbox_created | `sandbox_created` | `{"containerId":"...","sessionId":"...","image":"..."}` |
| sandbox_executing | `sandbox_executing` | `{"toolName":"...","containerId":"..."}` |
| sandbox_result | `sandbox_result` | `{"toolName":"...","exitCode":0,"stdout":"..."}` |
| sandbox_destroyed | `sandbox_destroyed` | `{"containerId":"..."}` |

### 心跳事件

| 事件名称 | `event:` | `data:` 模式 |
|---|---|---|
| heartbeat_start | `heartbeat_start` | `{"agentId":"...","sessionKey":"...","timestamp":"..."}` |
| heartbeat_thinking | `heartbeat_thinking` | `{"agentId":"..."}` |
| heartbeat_reply | `heartbeat_reply` | `{"agentId":"...","message":"..."}` |
| heartbeat_complete | `heartbeat_complete` | `{"agentId":"...","elapsedMs":1234,"message":"预览..."}` |
| heartbeat_error | `heartbeat_error` | `{"agentId":"...","error":"..."}` |

### 重试事件

| 事件名称 | `event:` | `data:` 模式 |
|---|---|---|
| retry_attempt | `retry_attempt` | `{"sessionId":"...","attempt":3,"model":"gpt-4","score":0.45}` |
| retry_fallback | `retry_fallback` | `{"sessionId":"...","fromModel":"gpt-4","toModel":"gpt-4o-mini"}` |
| retry_exhausted | `retry_exhausted` | `{"sessionId":"...","totalAttempts":32,"bestScore":0.68}` |

---

## 变更摘要

### 新增文件（Java）

| 文件 | 包 | 描述 |
|---|---|---|
| `BlockStreamingConfig.java` | `lyjew.com.lyclaw.config` | 块流式配置 POJO |
| `BlockStreamingChunk.java` | `lyjew.com.lyclaw.config` | 软分块配置 |
| `BlockStreamingCoalesce.java` | `lyjew.com.lyclaw.config` | 合并配置 |
| `HumanDelayConfig.java` | `lyjew.com.lyclaw.config` | 人类输入延迟配置 |
| `BlockStreamingController.java` | `lyjew.com.lyclaw.react.stream` | 基于块的流式管道 |
| `TypingIndicatorController.java` | `lyjew.com.lyclaw.react.stream` | 输入中指示器 SSE 发送器 |
| `AgentSandboxConfig.java` | `lyjew.com.lyclaw.config` | 容器沙箱配置 |
| `SandboxExecutionService.java` | `lyjew.com.lyclaw.security.sandbox` | Docker/Podman 沙箱服务 |
| `SandboxHandle.java` | `lyjew.com.lyclaw.security.sandbox` | 沙箱容器句柄 |
| `HeartbeatConfig.java` | `lyjew.com.lyclaw.config` | 心跳配置 POJO |
| `HeartbeatScheduler.java` | `lyjew.com.lyclaw.react.heartbeat` | 基于 Cron 的心跳执行器 |
| `HeartbeatEvent.java` | `lyjew.com.lyclaw.react.heartbeat` | 心跳事件类型 |
| `RunRetriesConfig.java` | `lyjew.com.lyclaw.config` | 重试预算配置 |
| `RunRetryManager.java` | `lyjew.com.lyclaw.react.retry` | 带回退链的重试管理器 |

### 修改文件（Java）

| 文件 | 变更 |
|---|---|
| `AgentContext.java` | 添加 `SandboxHandle sandboxHandle`、`Map<String,Object> runMetadata`、`recordRetryState()` |
| `SandboxHook.java` | 当配置容器后端时集成 `SandboxExecutionService` 用于 `PROCESS` 级别 |
| `DefaultReActEngine.java` | 用 `BlockStreamingController.streamResponse()` 替换 `splitIntoEvents()` |
| `RespondStage.java` | 集成 `BlockStreamingController`、`TypingIndicatorController`、`HumanDelayConfig` |
| `ReflexionLoop.java` | 用 `RunRetryManager` 替换硬编码的 `maxRetries`，添加模型轮换 |

### 配置键（application.yml）

| 前缀 | 键 |
|---|---|
| `lyclaw.streaming.block` | enabled、break-mode、chunk.*、coalesce.*、max-chunk-chars、repeat-suppression、delivery-mode、hidden-boundary |
| `lyclaw.streaming.human-delay` | enabled、min-delay-ms、max-delay-ms、chars-per-second、adaptive-speed、long-reply-threshold |
| `lyclaw.streaming.typing-indicator` | mode、interval-seconds |
| `lyclaw.sandbox` | backend、image、root-dir、allowed-commands、denied-commands、network-enabled、file-system-write-enabled、memory-limit-mb、cpu-limit、timeout-seconds、fs-bridge.* |
| `lyclaw.heartbeat` | enabled、every、active-hours.*、model、session-key、target、direct-policy、to、account-id、prompt、include-system-prompt-section、ack-max-chars、suppress-tool-error-warnings、timeout-seconds、light-context、isolated-session、skip-when-busy、include-reasoning |
| `lyclaw.retry` | base、per-profile、min、max、quality-threshold、default-strategy、max-consecutive-failures-before-fallback、backoff.* |
