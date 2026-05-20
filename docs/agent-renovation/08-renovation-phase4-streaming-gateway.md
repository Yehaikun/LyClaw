# Phase 4: Streaming & Gateway Enhancement + Sandbox + Heartbeat + Run Retries

## Overview

Phase 4 targets four high-impact subsystems that underpin LyClaw's production readiness:
1. **Block Streaming & Human Delay** — replaces naive `splitIntoEvents()` in `DefaultReActEngine` with boundary-aware block streaming, coalescing, human typing simulation, and typing indicators.
2. **Container Sandbox** — upgrades `ToolSandbox` / `SandboxLevel=PROCESS` to Docker/Podman-backed isolation with filesystem bridging, resource limits, and `SandboxExecutionService`.
3. **Agent Heartbeat** — introduces a cron-like scheduler that can ping agents periodically, produce `heartbeat_*` SSE events, and deliver isolated-session turn results.
4. **Run Retries Enhancement** — replaces the hardcoded `maxRetries` in `ReflexionLoop` with `RunRetryManager`, per-fallback-profile budgeting, and retry-strategy selection.

All new code lives under existing packages:
- Streaming configs → `lyjew.com.lyclaw.config`
- Block streaming logic → `lyjew.com.lyclaw.react.stream`
- Sandbox → `lyjew.com.lyclaw.security.sandbox`
- Heartbeat → `lyjew.com.lyclaw.react.heartbeat`
- Run retries → `lyjew.com.lyclaw.react.retry`

---

## Table of Contents

1. [4.1 Block Streaming Enhancement](#41-block-streaming-enhancement)
2. [4.2 Sandbox Enhancement](#42-sandbox-enhancement)
3. [4.3 Heartbeat System](#43-heartbeat-system)
4. [4.4 Run Retries Enhancement](#44-run-retries-enhancement)
5. [Integration Diagram](#integration-diagram)
6. [SSE Event Schema Reference](#sse-event-schema-reference)

---

## 4.1 Block Streaming Enhancement

### 4.1.1 Motivation

The current `DefaultReActEngine.splitIntoEvents(String text)` splits at Chinese punctuation boundaries (`\n`, `。`, `！`, `？`, `；`) and emits each segment as a single SSE `message` event. This works for short replies but has several issues:

- **No block awareness**: Does not understand LLM natural text boundaries (paragraphs, code fences, lists).
- **No coalescing**: A single-character chunk creates a separate SSE frame — wasteful.
- **No human delay**: All events arrive at once, giving no sense of "the AI is typing."
- **No typing indicator**: Frontend cannot show a "thinking" or "typing" state during response generation.

Phase 4 introduces a layered streaming pipeline inside `RespondStage` and `DefaultReActEngine`:

```
LLM token stream
  → BlockStreamingChunk (soft boundary detection)
    → BlockStreamingCoalesce (merge small blocks)
      → HumanDelay (inter-block stagger)
        → TypingIndicator (periodic "typing" events)
          → SSE emit
```

### 4.1.2 Configuration

#### BlockStreamingConfig

```java
package lyjew.com.lyclaw.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Block-based streaming configuration.
 * <p>Controls how LLM token streams are chunked and delivered to SSE clients.
 * Replaces the simple splitIntoEvents() with boundary-aware, coalesced, human-delayed streaming.
 */
@ConfigurationProperties(prefix = "lyclaw.streaming.block")
public class BlockStreamingConfig {

    /** Enable block-based streaming. When false, falls back to legacy splitIntoEvents(). */
    private boolean enabled = false;

    /**
     * When to break streaming blocks.
     * <ul>
     *   <li>TEXT_END — break after each complete text segment (paragraph, list item, etc.)</li>
     *   <li>MESSAGE_END — break only at end of entire assistant message</li>
     * </ul>
     */
    private BlockStreamingBreak breakMode = BlockStreamingBreak.TEXT_END;

    /** Soft block chunking configuration. */
    private BlockStreamingChunk chunk = new BlockStreamingChunk();

    /** Block reply coalescing configuration. */
    private BlockStreamingCoalesce coalesce = new BlockStreamingCoalesce();

    /** Maximum characters per individual chunk frame. */
    private int maxChunkChars = 2000;

    /** If true, suppress repeated identical text blocks. */
    private boolean repeatSuppression = true;

    /**
     * Streaming delivery mode.
     * <ul>
     *   <li>LIVE — emit blocks as they are formed (default)</li>
     *   <li>FINAL_ONLY — buffer everything, emit single event at end</li>
     * </ul>
     */
    private StreamingDeliveryMode deliveryMode = StreamingDeliveryMode.LIVE;

    /**
     * Hidden boundary separator for multi-block messages.
     * Inserted as an invisible delimiter between blocks for clients that parse responses.
     */
    private HiddenBoundarySeparator hiddenBoundary = HiddenBoundarySeparator.NEWLINE;

    // getters and setters omitted for brevity

    public enum BlockStreamingBreak { TEXT_END, MESSAGE_END }
    public enum StreamingDeliveryMode { LIVE, FINAL_ONLY }
    public enum HiddenBoundarySeparator { NEWLINE, NULL_CHAR, NONE }
}
```

#### BlockStreamingChunk

```java
package lyjew.com.lyclaw.config;

/**
 * Soft block chunking configuration.
 * <p>Chunking means deciding where to cut the token stream into discrete blocks.
 * This is "soft" because chunks can be coalesced later.
 */
public class BlockStreamingChunk {

    /**
     * Soft maximum characters per chunk (bytes for CJK text).
     * A chunk will be flushed when it exceeds this size,
     * but the actual boundary is still subject to preferNewlines.
     */
    private int maxChars = 500;

    /**
     * Maximum idle time (ms) before flushing the current chunk.
     * If no new tokens arrive for this duration, the accumulated chunk is emitted.
     */
    private int maxIdleMs = 1000;

    /**
     * If true, prefer splitting at newline boundaries (\n, \r\n, \n\n).
     * When a newline is encountered and the current chunk is at least 50% of maxChars,
     * the chunk is flushed at that boundary regardless of exact size.
     */
    private boolean preferNewlines = true;

    /**
     * When preferNewlines is true, the minimum fill percentage (0.0-1.0)
     * before a newline triggers flush.
     */
    private double newlineFlushThreshold = 0.5;

    // getters and setters omitted
}
```

#### BlockStreamingCoalesce

```java
package lyjew.com.lyclaw.config;

/**
 * Block reply coalescing configuration.
 * <p>Coalescing merges multiple small blocks into one larger block before SSE delivery.
 * This reduces the number of SSE frames and improves network efficiency.
 */
public class BlockStreamingCoalesce {

    /** Enable block coalescing. */
    private boolean enabled = true;

    /** Maximum characters in a coalesced block before forced flush. */
    private int maxChars = 8000;

    /**
     * Maximum idle time (ms) before flushing the coalesced buffer.
     * If no new blocks arrive for this duration, the accumulated content is emitted.
     */
    private int maxIdleMs = 3000;

    // getters and setters omitted
}
```

#### HumanDelayConfig

```java
package lyjew.com.lyclaw.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Human-like typing delay configuration.
 * <p>Introduces variable delays between streaming blocks to simulate
 * natural typing speed, improving UX in chat interfaces.
 */
@ConfigurationProperties(prefix = "lyclaw.streaming.human-delay")
public class HumanDelayConfig {

    /** Enable human-like delay simulation. */
    private boolean enabled = false;

    /** Minimum delay between blocks (ms). */
    private int minDelayMs = 200;

    /** Maximum delay between blocks (ms). */
    private int maxDelayMs = 1500;

    /**
     * Simulated typing speed in characters per second.
     * Used to calculate dynamic delay: delayMs = blockChars / charsPerSecond * 1000.
     * Typical human typing speed is 40-80 CPS; 50 is a natural default.
     */
    private int charsPerSecond = 50;

    /**
     * If true, adaptive speed adjusts typing rate for long replies.
     * The agent "speeds up" as response length grows to avoid excessive wait times.
     */
    private boolean adaptiveSpeed = true;

    /**
     * Character threshold for triggering the speed-up adjustment.
     * When the total accumulated response exceeds this, charsPerSecond is
     * gradually increased (up to 3x) for remaining blocks.
     */
    private int longReplyThreshold = 2000;

    // getters and setters omitted
}
```

### 4.1.3 BlockStreamingController

This is the core component that replaces `splitIntoEvents()`.

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
 * Block-based streaming controller that replaces DefaultReActEngine.splitIntoEvents().
 *
 * <p>Converts a raw text response into a boundary-aware, coalesced, human-delayed
 * Flux of SSE events. Integrates with RespondStage's streaming pipeline.</p>
 *
 * <h3>Processing pipeline:</h3>
 * <ol>
 *   <li>Parse raw text into blocks at natural boundaries</li>
 *   <li>Coalesce small adjacent blocks</li>
 *   <li>Apply human delay between blocks</li>
 *   <li>Apply repeat suppression</li>
 *   <li>Emit SSE message events</li>
 * </ol>
 */
public class BlockStreamingController {

    private static final ObjectMapper objectMapper = new ObjectMapper();

    private final BlockStreamingConfig config;
    private final HumanDelayConfig humanDelayConfig;
    private final TypingIndicatorController typingIndicator;

    // Track previously emitted text for repeat suppression
    private String lastEmittedBlock = "";

    // Track total emitted chars for adaptive speed
    private int totalEmittedChars = 0;

    public BlockStreamingController(BlockStreamingConfig config,
                                     HumanDelayConfig humanDelayConfig,
                                     TypingIndicatorController typingIndicator) {
        this.config = config;
        this.humanDelayConfig = humanDelayConfig;
        this.typingIndicator = typingIndicator;
    }

    /**
     * Convert a complete text response into a block-streamed Flux of SSE events.
     * Used when tool calls are detected and the ReAct loop produces a final text response.
     *
     * @param text the complete assistant response text
     * @return Flux of SSE message events
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

            // LIVE mode: emit blocks with human delay
            return Flux.fromIterable(blocks)
                    .concatMap(block ->
                            Mono.just(sseMessage(block))
                                    .delayElement(calculateDelay(block))
                    );
        });
    }

    /**
     * Segment raw text into blocks at natural boundaries.
     *
     * <p>Boundary detection recognizes:
     * <ul>
     *   <li>Paragraph breaks (double newline) — strongest boundary</li>
     *   <li>Code fences (```), list items (-, *, 1.) — strong boundary</li>
     *   <li>Table rows (|) — strong boundary</li>
     *   <li>Sentence endings (.!?。) — medium boundary</li>
     *   <li>Newline — weak boundary</li>
     *   <li>Comma/colon — soft boundary (only if approaching maxChars)</li>
     * </ul></p>
     */
    List<String> segmentIntoBlocks(String text) {
        BlockStreamingConfig.BlockStreamingBreak breakMode = config.getBreakMode();
        int maxChars = config.getChunk().getMaxChars();
        boolean preferNewlines = config.getChunk().isPreferNewlines();
        double newlineThreshold = config.getChunk().getNewlineFlushThreshold();

        List<String> blocks = new ArrayList<>();
        StringBuilder buf = new StringBuilder();

        // First pass: split by double newlines (paragraph breaks — strongest boundary)
        String[] paragraphs = text.split("\\n\\s*\\n", -1);

        for (int p = 0; p < paragraphs.length; p++) {
            String paragraph = paragraphs[p];
            if (paragraph.isEmpty()) {
                if (p > 0 && p < paragraphs.length - 1) {
                    // Empty paragraph = intentional blank line, add as separator
                    blocks.add("\n\n");
                }
                continue;
            }

            // Within each paragraph, split by strong boundaries
            int i = 0;
            while (i < paragraph.length()) {
                char c = paragraph.charAt(i);
                buf.append(c);

                boolean shouldFlush = false;

                if (breakMode == BlockStreamingConfig.BlockStreamingBreak.MESSAGE_END) {
                    // Only flush at paragraph boundaries
                    shouldFlush = false;
                } else if (buf.length() >= maxChars) {
                    // Hard flush at maxChars
                    shouldFlush = true;
                } else if (c == '\n' && preferNewlines
                        && buf.length() >= (int)(maxChars * newlineThreshold)) {
                    // Soft flush at newline when sufficiently full
                    shouldFlush = true;
                } else if (isStrongBoundary(c, paragraph, i)) {
                    // Strong boundary character
                    shouldFlush = buf.length() >= 20; // avoid single-char blocks
                } else if (isMediumBoundary(c) && buf.length() >= (int)(maxChars * 0.5)) {
                    // Medium boundary when > 50% full
                    shouldFlush = true;
                }

                if (shouldFlush) {
                    blocks.add(buf.toString().trim());
                    buf.setLength(0);
                }
                i++;
            }
        }

        // Flush remaining
        if (buf.length() > 0) {
            String rem = buf.toString().trim();
            if (!rem.isEmpty()) {
                blocks.add(rem);
            }
        }

        return blocks;
    }

    /**
     * Coalesce small adjacent blocks into larger ones.
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
                // Buffer would overflow — flush it
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
     * Remove repeated identical blocks.
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
     * Calculate human delay for a block.
     */
    Duration calculateDelay(String block) {
        if (!humanDelayConfig.isEnabled()) {
            return Duration.ZERO;
        }

        int charsPerSec = humanDelayConfig.getCharsPerSecond();

        if (humanDelayConfig.isAdaptiveSpeed() && totalEmittedChars > humanDelayConfig.getLongReplyThreshold()) {
            // Speed up for long replies: gradually increase CPS up to 3x
            double excessRatio = Math.min(1.0,
                    (double)(totalEmittedChars - humanDelayConfig.getLongReplyThreshold())
                            / humanDelayConfig.getLongReplyThreshold());
            charsPerSec = (int)(charsPerSec * (1.0 + excessRatio * 2.0));
        }

        // Base delay proportional to block length
        int baseDelayMs = (int)((double)block.length() / charsPerSec * 1000);

        // Clamp between min and max
        int delayMs = Math.max(humanDelayConfig.getMinDelayMs(),
                Math.min(humanDelayConfig.getMaxDelayMs(), baseDelayMs));

        // Add small random jitter (±20%)
        double jitter = 0.8 + Math.random() * 0.4;
        delayMs = (int)(delayMs * jitter);

        totalEmittedChars += block.length();
        return Duration.ofMillis(delayMs);
    }

    /**
     * Join blocks with the configured hidden boundary separator.
     */
    String joinWithHiddenBoundary(List<String> blocks) {
        String sep;
        switch (config.getHiddenBoundary()) {
            case NULL_CHAR: sep = " "; break;
            case NONE: sep = ""; break;
            default: sep = "\n";
        }
        return String.join(sep, blocks);
    }

    private boolean isStrongBoundary(char c, String text, int pos) {
        // Heading markers: # at line start
        if (c == '#') {
            return pos == 0 || (pos > 0 && text.charAt(pos - 1) == '\n');
        }
        // Code fence backticks: ```
        if (c == '`' && text.length() > pos + 2
                && text.charAt(pos + 1) == '`' && text.charAt(pos + 2) == '`') {
            return true;
        }
        // Horizontal rule: ---, ***, ___
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
 * Controls typing indicator SSE events sent to the client during
 * agent processing gaps (tool execution, thinking, etc.).
 *
 * <p>Usage in RespondStage:
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
        /** Never send typing indicators. */
        NEVER,
        /** Send a typing indicator immediately upon entering a processing gap. */
        INSTANT,
        /** Send typing indicators at intervals during "thinking" phases. */
        THINKING,
        /** Send typing indicators at intervals during message generation. */
        MESSAGE
    }

    public TypingIndicatorController(TypingMode mode, int intervalSeconds) {
        this.mode = mode;
        this.intervalSeconds = intervalSeconds;
    }

    /**
     * Returns a Flux that emits typing indicator SSE events at the configured interval.
     * Events are automatically stopped when stopTyping() is called.
     *
     * @param ctx the agent context for which to emit typing indicators
     * @return Flux of "typing" SSE events, emitted every intervalSeconds
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
     * Stop emitting typing indicators. The Flux from startTyping() will complete
     * on its next tick.
     */
    public void stopTyping() {
        this.active = false;
    }

    private ServerSentEvent<String> buildTypingEvent(AgentContext ctx) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", "typing");
        payload.put("agentId", ctx.getSessionId());  // sessionId serves as agentId
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

### 4.1.5 Integration with RespondStage

The modified `RespondStage` integrates block streaming as follows:

```java
// Inside RespondStage.reactWithReActEngine():
//
// Before (current):
//   return reActEngine.executeStream(chatFacade, request, toolExecutor);
//
// After (Phase 4):
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
//               // If the event is a final text block (not streaming token), apply block streaming
//               if (isBlockCandidates(data)) {
//                   streamingCtrl.reset();
//                   return streamingCtrl.streamResponse(data);
//               }
//               // Otherwise pass through as-is (streaming tokens are already granular)
//               return Flux.just(event);
//           }
//           return Flux.just(event);
//       })
//       .doOnTerminate(typingCtrl::stopTyping)
//       .mergeWith(typingFlux);
```

And in `DefaultReActEngine`, the `splitIntoEvents()` method is replaced by delegating to `BlockStreamingController`:

```java
// In DefaultReActEngine, replace:
//   private Flux<ServerSentEvent<String>> splitIntoEvents(String text) { ... }
//
// With:
//   private final BlockStreamingController streamingController;
//
//   private Flux<ServerSentEvent<String>> streamFinalText(String text) {
//       if (streamingController != null) {
//           return streamingController.streamResponse(text);
//       }
//       // Legacy fallback
//       // ... (old splitIntoEvents logic kept for backward compat)
//   }
```

### 4.1.6 YAML Configuration

```yaml
# application.yml — block streaming configuration
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

## 4.2 Sandbox Enhancement

### 4.2.1 Motivation

The current sandbox system (via `ToolSandbox` interface and `SandboxLevel` enum) supports:
- `DIRECT` — execute on current thread (read-only tools)
- `SANDBOX` — daemon thread + temporary working directory
- `PROCESS` — independent OS process via `CommandExecutor`

What is missing:
- **Container isolation**: No Docker/Podman support; `PROCESS` level still runs as a child of the JVM process.
- **Resource limits**: No memory/CPU/timeout enforcement at the OS level.
- **Filesystem bridging**: No bidirectional file transfer between host and sandbox.
- **Health monitoring**: `ToolSandbox.isHealthy()` is not backed by actual container health checks.
- **Network control**: No way to disable network access for untrusted code.

### 4.2.2 AgentSandboxConfig

```java
package lyjew.com.lyclaw.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * Container-based sandbox configuration.
 * <p>Controls Docker/Podman container settings for tool execution isolation.
 */
@ConfigurationProperties(prefix = "lyclaw.sandbox")
public class AgentSandboxConfig {

    /**
     * Sandbox backend provider.
     * <ul>
     *   <li>NONE — no container isolation (use legacy process sandbox)</li>
     *   <li>DOCKER — use docker-java SDK</li>
     *   <li>PODMAN — use podman CLI (compatible with rootless setups)</li>
     * </ul>
     */
    private SandboxBackend backend = SandboxBackend.NONE;

    /** Container image to use for sandbox execution. */
    private String image = "ubuntu:22.04";

    /** Root directory inside the container for sandbox operations. */
    private String rootDir = "/sandbox";

    /** Command whitelist: only these commands may execute inside the sandbox. */
    private List<String> allowedCommands = new ArrayList<>();

    /** Command blacklist: these commands are explicitly forbidden. */
    private List<String> deniedCommands = new ArrayList<>();

    /** Whether the sandbox container has network access. Default false for security. */
    private boolean networkEnabled = false;

    /** Whether the sandbox container can write to the filesystem. */
    private boolean fileSystemWriteEnabled = true;

    /** Memory limit in MB for the container. */
    private long memoryLimitMb = 512;

    /** CPU limit in cores (can be fractional). */
    private double cpuLimit = 1.0;

    /** Maximum execution time for a single tool call in seconds. */
    private int timeoutSeconds = 300;

    /** Filesystem bridge configuration. */
    private SandboxFsBridge fsBridge = new SandboxFsBridge();

    /** Container startup timeout in seconds. */
    private int startupTimeoutSeconds = 30;

    /** If true, reuse containers across tool calls within the same session. */
    private boolean reuseContainer = true;

    /** Maximum container idle time in seconds before automatic cleanup. */
    private int containerIdleTimeoutSeconds = 600;

    /** Docker socket path (default: unix:///var/run/docker.sock). */
    private String dockerSocket = "unix:///var/run/docker.sock";

    /** Podman socket path for podman backend. */
    private String podmanSocket = "unix:///run/podman/podman.sock";

    // getters and setters omitted

    public enum SandboxBackend { NONE, DOCKER, PODMAN }
}
```

#### SandboxFsBridge (inner config)

```java
/**
 * Filesystem bridge configuration for host-sandbox file sharing.
 */
public class SandboxFsBridge {

    /** Host workspace directory to bridge into the sandbox (read-only). */
    private String hostWorkspace = "./workspace";

    /** Path inside the container where host workspace is mounted. */
    private String sandboxWorkspace = "/workspace";

    /** Whether the workspace mount is read-only inside the container. */
    private boolean workspaceReadOnly = true;

    /** Host temp directory for sandbox writable files. */
    private String hostTmp = "./sandbox-tmp";

    /** Path inside the container for writable temp files. */
    private String sandboxTmp = "/tmp/sandbox";

    /** Maximum size in MB for the tmp volume. */
    private long tmpMaxSizeMb = 500;

    // getters and setters omitted
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
 * Container-backed sandbox execution service.
 *
 * <p>Manages Docker/Podman container lifecycle for isolated tool execution.
 * Integrates with SandboxHook (replacing direct ToolSandbox delegation for
 * SandboxLevel.PROCESS when container backend is configured).
 *
 * <h3>Lifecycle:</h3>
 * <ol>
 *   <li>createSandbox(config) — pull image, create container, start it</li>
 *   <li>executeInSandbox(handle, tool, args) — execute tool via docker exec</li>
 *   <li>isHealthy(handle) — check container running status</li>
 *   <li>destroy(handle) — stop and remove container</li>
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

    // ── Docker Client Factory ──────────────────────────────────────────

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

    // ── Sandbox Lifecycle ──────────────────────────────────────────────

    /**
     * Create and start a sandbox container.
     *
     * @param sessionId the session this sandbox belongs to
     * @return Mono emitting the SandboxHandle on success
     */
    public Mono<SandboxHandle> createSandbox(String sessionId) {
        if (config.getBackend() == AgentSandboxConfig.SandboxBackend.NONE) {
            return Mono.just(SandboxHandle.none());
        }

        return Mono.fromCallable(() -> {
            String containerName = "lyclaw-sandbox-" + sessionId + "-" + UUID.randomUUID().toString().substring(0, 8);

            log.info("Creating sandbox container: name={} image={}", containerName, config.getImage());

            // Pull image if not present
            try {
                dockerClient.pullImageCmd(config.getImage()).start().awaitCompletion();
            } catch (Exception e) {
                log.warn("Image pull failed (may already exist locally): {}", e.getMessage());
            }

            // Build host config with resource limits
            HostConfig hostConfig = HostConfig.newHostConfig()
                    .withMemory(config.getMemoryLimitMb() * 1024 * 1024) // bytes
                    .withNanoCPUs((long)(config.getCpuLimit() * 1_000_000_000L))
                    .withNetworkMode(config.isNetworkEnabled() ? "bridge" : "none")
                    .withReadonlyRootfs(!config.isFileSystemWriteEnabled())
                    .withAutoRemove(true);

            // Mount volumes
            List<com.github.dockerjava.api.model.Bind> binds = new ArrayList<>();

            // Workspace mount (read-only if configured)
            Path hostWorkspace = Paths.get(config.getFsBridge().getHostWorkspace())
                    .toAbsolutePath().normalize();
            Files.createDirectories(hostWorkspace);
            String workspaceMode = config.getFsBridge().isWorkspaceReadOnly() ? "ro" : "rw";
            binds.add(new Bind(hostWorkspace.toString(),
                    new com.github.dockerjava.api.model.Volume(config.getFsBridge().getSandboxWorkspace()),
                    AccessMode.valueOf(workspaceMode)));

            // Tmp mount (read-write)
            Path hostTmp = Paths.get(config.getFsBridge().getHostTmp())
                    .toAbsolutePath().normalize();
            Files.createDirectories(hostTmp);
            binds.add(new Bind(hostTmp.toString(),
                    new com.github.dockerjava.api.model.Volume(config.getFsBridge().getSandboxTmp()),
                    AccessMode.rw));

            hostConfig.withBinds(binds);

            // Create container
            CreateContainerCmd createCmd = dockerClient.createContainerCmd(config.getImage())
                    .withName(containerName)
                    .withHostConfig(hostConfig)
                    .withWorkingDir(config.getRootDir())
                    .withCmd("sleep", "infinity") // keep container alive
                    .withAttachStdin(false)
                    .withAttachStdout(true)
                    .withAttachStderr(true);

            CreateContainerResponse createResp = createCmd.exec();
            String containerId = createResp.getId();

            // Start container
            dockerClient.startContainerCmd(containerId).exec();

            // Wait for container to be ready
            boolean ready = waitForContainerReady(containerId, config.getStartupTimeoutSeconds());
            if (!ready) {
                throw new RuntimeException("Sandbox container failed to start within timeout: " + containerName);
            }

            SandboxHandle handle = new SandboxHandle(sessionId, containerId, containerName);
            activeHandles.put(sessionId, handle);

            log.info("Sandbox container started: containerId={} name={}", containerId, containerName);
            return handle;
        }).subscribeOn(Schedulers.boundedElastic());
    }

    // ── Tool Execution ─────────────────────────────────────────────────

    /**
     * Execute a tool inside the sandbox container.
     *
     * @param handle the sandbox to execute in
     * @param tool   the tool definition
     * @param args   tool arguments
     * @return Mono emitting the execution result
     */
    public Mono<ToolExecutionResult> executeInSandbox(SandboxHandle handle, Tool tool,
                                                       Map<String, Object> args) {
        if (handle.isNone()) {
            return Mono.just(ToolExecutionResult.failure("No sandbox container available"));
        }

        return Mono.fromCallable(() -> {
            // Build the docker exec command
            String[] cmd = buildExecCommand(tool, args);

            // Validate against allow/deny lists
            if (!isCommandAllowed(cmd[0])) {
                return ToolExecutionResult.failure("Command '" + cmd[0] + "' is not allowed in sandbox");
            }

            ExecCreateCmdResponse execCreate = dockerClient.execCreateCmd(handle.getContainerId())
                    .withCmd(cmd)
                    .withAttachStdout(true)
                    .withAttachStderr(true)
                    .withTty(false)
                    .exec();

            // Capture output
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
                log.error("Sandbox execution timed out or failed: {}", e.getMessage());
                return ToolExecutionResult.failure("Sandbox execution error: " + e.getMessage());
            }

            // Check exit code
            InspectExecResponse execInspect = dockerClient.inspectExecCmd(execCreate.getId()).exec();
            int exitCode = execInspect.getExitCode() != null ? execInspect.getExitCode() : -1;

            if (exitCode == 0) {
                return ToolExecutionResult.success(stdout.toString().trim());
            } else {
                String error = stderr.length() > 0 ? stderr.toString().trim() : stdout.toString().trim();
                return ToolExecutionResult.failure("Exit code " + exitCode + ": " + error);
            }
        }).subscribeOn(Schedulers.boundedElastic());
    }

    // ── Filesystem Bridge ──────────────────────────────────────────────

    /**
     * Copy a file from host to sandbox container.
     */
    public Mono<Void> bridgeFileToSandbox(String hostPath, String sandboxPath,
                                          SandboxHandle handle) {
        if (handle.isNone()) return Mono.empty();

        return Mono.fromRunnable(() -> {
            try {
                Path hostFile = Paths.get(hostPath);
                if (!Files.exists(hostFile)) {
                    log.warn("Host file does not exist: {}", hostPath);
                    return;
                }

                try (InputStream tarStream = createTarArchive(hostFile)) {
                    dockerClient.copyArchiveToContainerCmd(handle.getContainerId())
                            .withRemotePath(Paths.get(sandboxPath))
                            .withTarInputStream(tarStream)
                            .exec();
                }
                log.debug("File bridged to sandbox: {} -> {}:{}",
                        hostPath, handle.getContainerId(), sandboxPath);
            } catch (Exception e) {
                log.error("Failed to bridge file to sandbox: {}", e.getMessage());
            }
        }).subscribeOn(Schedulers.boundedElastic()).then();
    }

    /**
     * Copy a file from sandbox container to host.
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
                log.debug("File bridged from sandbox: {}:{} -> {}",
                        handle.getContainerId(), sandboxPath, hostPath);
            } catch (Exception e) {
                log.error("Failed to bridge file from sandbox: {}", e.getMessage());
            }
        }).subscribeOn(Schedulers.boundedElastic()).then();
    }

    // ── Health Check ───────────────────────────────────────────────────

    /**
     * Check if a sandbox container is still healthy.
     */
    public Mono<Boolean> isHealthy(SandboxHandle handle) {
        if (handle.isNone()) return Mono.just(false);

        return Mono.fromCallable(() -> {
            try {
                InspectContainerResponse inspect = dockerClient.inspectContainerCmd(handle.getContainerId()).exec();
                return inspect.getState() != null && Boolean.TRUE.equals(inspect.getState().getRunning());
            } catch (Exception e) {
                log.warn("Health check failed for container {}: {}", handle.getContainerId(), e.getMessage());
                return false;
            }
        }).subscribeOn(Schedulers.boundedElastic());
    }

    // ── Destroy ────────────────────────────────────────────────────────

    /**
     * Stop and remove a sandbox container, releasing all resources.
     */
    public Mono<Void> destroy(SandboxHandle handle) {
        if (handle.isNone()) return Mono.empty();

        return Mono.fromRunnable(() -> {
            try {
                dockerClient.stopContainerCmd(handle.getContainerId())
                        .withTimeout(10)
                        .exec();
                // Auto-remove is configured, so explicit remove is optional
                log.info("Sandbox container destroyed: containerId={}", handle.getContainerId());
            } catch (Exception e) {
                log.warn("Error destroying sandbox container {}: {}",
                        handle.getContainerId(), e.getMessage());
                // Force remove as fallback
                try {
                    dockerClient.removeContainerCmd(handle.getContainerId())
                            .withForce(true)
                            .exec();
                } catch (Exception f) {
                    log.error("Force remove also failed for container {}: {}",
                            handle.getContainerId(), f.getMessage());
                }
            } finally {
                activeHandles.remove(handle.getSessionId());
            }
        }).subscribeOn(Schedulers.boundedElastic()).then();
    }

    /**
     * Destroy all active sandbox containers. Called on application shutdown.
     */
    public Mono<Void> destroyAll() {
        return Flux.fromIterable(new ArrayList<>(activeHandles.values()))
                .flatMap(this::destroy)
                .then();
    }

    // ── Private Helpers ────────────────────────────────────────────────

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
                // Container may not be ready yet
            }
        }
        return false;
    }

    private String[] buildExecCommand(Tool tool, Map<String, Object> args) {
        // For command tools, wrap in bash -c
        // For script tools, write script to /tmp then execute
        String command = args.getOrDefault("command", "").toString();
        if (command.isEmpty()) {
            command = tool.getDescription();
        }
        return new String[]{"bash", "-c", command};
    }

    private boolean isCommandAllowed(String command) {
        List<String> allowed = config.getAllowedCommands();
        List<String> denied = config.getDeniedCommands();

        // If whitelist is configured, only allowlisted commands pass
        if (!allowed.isEmpty()) {
            return allowed.stream().anyMatch(cmd -> command.startsWith(cmd));
        }

        // If blacklist is configured, deny matching commands
        if (!denied.isEmpty()) {
            if (denied.stream().anyMatch(cmd -> command.startsWith(cmd))) {
                return false;
            }
        }

        // No explicit rules = allow all (backward compatible)
        return true;
    }

    private InputStream createTarArchive(Path file) throws IOException {
        // Minimal TAR creation for single file (in production, use Apache Commons Compress)
        java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
        // Simplified: in real code, use a proper TAR library
        // This is a placeholder showing the integration pattern
        baos.write(("tar-content:" + file.getFileName()).getBytes());
        return new java.io.ByteArrayInputStream(baos.toByteArray());
    }

    private void extractTarArchive(InputStream tarStream, Path destPath) {
        // Simplified: in real code, use a proper TAR library
        // Placeholder showing the integration pattern
    }
}
```

### 4.2.4 SandboxHandle

```java
package lyjew.com.lyclaw.security.sandbox;

/**
 * Handle to an active sandbox container.
 * <p>Immutable after creation; used as a key for sandbox lifecycle operations.
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

    /** Create a no-op handle when no sandbox backend is configured. */
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

### 4.2.5 Integration with SandboxHook

The existing `SandboxHook` currently delegates to `ToolSandbox.execute(tool, args, level)`. In Phase 4, `SandboxHook` is updated to use `SandboxExecutionService` when `SandboxLevel.PROCESS` is requested and the container backend is configured:

```java
// Updated SandboxHook.wrapToolExecutor():
//
//   SandboxLevel level = ctx.getSandboxLevel() != null ? ctx.getSandboxLevel() : SandboxLevel.DIRECT;
//
//   if (level == SandboxLevel.PROCESS && sandboxExecutionService != null) {
//       // Container-backed sandbox
//       SandboxHandle handle = ctx.getSandboxHandle();
//       if (handle == null) {
//           // Lazy-create sandbox for this session
//           handle = sandboxExecutionService.createSandbox(ctx.getSessionId()).block();
//           ctx.setSandboxHandle(handle);
//       }
//       return sandboxExecutionService.executeInSandbox(handle, tool, args)
//               .map(result -> result.isSuccess() ? result.getResult() : "Error: " + result.getError())
//               .block();
//   }
//
//   // Fallback: legacy toolSandbox for DIRECT and SANDBOX levels
//   ToolExecutionResult result = toolSandbox.execute(tool, args, level);
//   return result.isSuccess() ? result.getResult() : "Error: " + result.getError();
```

`AgentContext` is extended with a new field:

```java
// Added to AgentContext:
private volatile SandboxHandle sandboxHandle;
public SandboxHandle getSandboxHandle() { return sandboxHandle; }
public void setSandboxHandle(SandboxHandle handle) { this.sandboxHandle = handle; }
```

### 4.2.6 YAML Configuration

```yaml
# application.yml — sandbox configuration
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

## 4.3 Heartbeat System

### 4.3.1 Motivation

Long-running agents need periodic "check-in" pings to:
- Verify the agent is still operational
- Provide proactive status updates to users
- Execute scheduled maintenance tasks
- Support "daily briefing" / "morning summary" patterns

The heartbeat system is a cron-like scheduler that runs single-turn ReAct invocations on a schedule, with configurable lightweight context, isolated sessions, and target delivery.

### 4.3.2 HeartbeatConfig

```java
package lyjew.com.lyclaw.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Agent heartbeat configuration.
 * <p>Controls scheduled "ping" invocations that keep agents active
 * and deliver periodic updates to users.
 */
@ConfigurationProperties(prefix = "lyclaw.heartbeat")
public class HeartbeatConfig {

    /** Enable the heartbeat scheduler for this agent. */
    private boolean enabled = false;

    /** Cron-like interval between heartbeat runs. */
    private Duration every = Duration.ofMinutes(30);

    /** Active hours window (cron expression for time range, e.g. "0 0 9 ? * MON-FRI"). */
    private String activeHoursCron;

    /** Active hours configuration using human-readable format. */
    private ActiveHours activeHours = new ActiveHours();

    /** Model override for heartbeat runs (uses agent default if null). */
    private String model;

    /** Session key for heartbeat run grouping (defaults to agent name). */
    private String sessionKey;

    /** Where to deliver heartbeat results. */
    private DeliveryTarget target = DeliveryTarget.LAST;

    /** Direct message policy when target specifies a user/channel. */
    private DirectPolicy directPolicy = DirectPolicy.ALLOW;

    /** Target recipient: E.164 phone number or chat channel ID. */
    private String to;

    /** Account ID for multi-account channel selection. */
    private String accountId;

    /** Custom heartbeat prompt. If empty/null, uses default system prompt. */
    private String prompt;

    /** If true, include the system prompt section in the heartbeat context. */
    private boolean includeSystemPromptSection = true;

    /** Maximum characters in the heartbeat acknowledgment message. */
    private int ackMaxChars = 30;

    /** Suppress tool execution error warnings in heartbeat runs. */
    private boolean suppressToolErrorWarnings = true;

    /** Heartbeat execution timeout in seconds. */
    private int timeoutSeconds = 120;

    /**
     * If true, use lightweight context (HEARTBEAT.md only).
     * When false, load full agent context including all memory files.
     */
    private boolean lightContext = true;

    /**
     * If true, create a fresh isolated session for each heartbeat run.
     * The sessionKey is reused but message history is not carried forward.
     */
    private boolean isolatedSession = true;

    /**
     * If true, skip heartbeat when sub-agents are actively running.
     * Prevents heartbeat from interrupting ongoing delegation tasks.
     */
    private boolean skipWhenBusy = true;

    /**
     * If true, include reasoning/thinking content in heartbeat responses.
     */
    private boolean includeReasoning = false;

    // getters and setters omitted

    public enum DeliveryTarget { LAST, NONE }
    public enum DirectPolicy { ALLOW, BLOCK }

    /**
     * Active hours window configuration.
     */
    public static class ActiveHours {
        /** Window start time in HH:mm format. */
        private String start = "09:00";
        /** Window end time in HH:mm format. */
        private String end = "18:00";
        /** Timezone identifier, e.g. "Asia/Shanghai", "America/New_York". */
        private String timezone = "Asia/Shanghai";
        /** Days of week (MON, TUE, ..., SUN) or empty for all days. */
        private String daysOfWeek = "";

        // getters and setters omitted
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
 * Cron-based heartbeat scheduler for agents.
 *
 * <p>Implements {@link SchedulingConfigurer} to dynamically register heartbeat
 * tasks based on each agent's {@link HeartbeatConfig}.
 *
 * <h3>Execution flow for each heartbeat tick:</h3>
 * <ol>
 *   <li>Check activeHours window — skip if outside</li>
 *   <li>Check skipWhenBusy — skip if sub-agents are active</li>
 *   <li>Create isolated session (if isolatedSession is true)</li>
 *   <li>Load light context (if lightContext — HEARTBEAT.md only)</li>
 *   <li>Run single-turn ReAct with heartbeat prompt</li>
 *   <li>Deliver result to target channel/user</li>
 *   <li>Dispatch heartbeat_start / heartbeat_reply / heartbeat_complete events</li>
 * </ol>
 */
public class HeartbeatScheduler {

    private static final Logger log = LoggerFactory.getLogger(HeartbeatScheduler.class);

    private final ChatFacade chatFacade;
    private final ToolRegistry toolRegistry;
    private final EventBus eventBus;
    private final SecurityManager securityManager;

    // Map of agent sessionKey → config for dynamic scheduling
    private final Map<String, HeartbeatConfig> agentConfigs = new ConcurrentHashMap<>();

    // Track active sub-agent count per agent
    private final Map<String, AtomicInteger> activeSubAgents = new ConcurrentHashMap<>();

    // ScheduledFuture handles for cancellation
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
     * Register or update a heartbeat configuration for an agent.
     * Called at agent initialization time.
     *
     * @param agentId the agent identifier
     * @param config  the heartbeat configuration
     */
    public void registerAgent(String agentId, HeartbeatConfig config) {
        if (config == null || !config.isEnabled()) {
            // Remove any existing schedule
            cancelSchedule(agentId);
            agentConfigs.remove(agentId);
            return;
        }

        agentConfigs.put(agentId, config);

        // Cancel existing schedule and create new one
        cancelSchedule(agentId);
        scheduleAgent(agentId, config);
    }

    /**
     * Notify the scheduler that a sub-agent has started for the given parent agent.
     * Used by skipWhenBusy to defer heartbeats during delegation.
     */
    public void onSubAgentStarted(String parentAgentId) {
        activeSubAgents.computeIfAbsent(parentAgentId, k -> new AtomicInteger(0))
                .incrementAndGet();
    }

    /**
     * Notify the scheduler that a sub-agent has completed for the given parent agent.
     */
    public void onSubAgentCompleted(String parentAgentId) {
        AtomicInteger count = activeSubAgents.get(parentAgentId);
        if (count != null && count.decrementAndGet() <= 0) {
            activeSubAgents.remove(parentAgentId);
        }
    }

    // ── Internal Scheduling ────────────────────────────────────────────

    private void scheduleAgent(String agentId, HeartbeatConfig config) {
        long intervalMs = config.getEvery().toMillis();

        ScheduledFuture<?> future = scheduler.scheduleAtFixedRate(
                () -> executeHeartbeat(agentId, config),
                intervalMs, // initial delay same as interval
                intervalMs,
                TimeUnit.MILLISECONDS
        );

        scheduledTasks.put(agentId, future);
        log.info("Heartbeat scheduled for agent '{}': every {}s", agentId,
                config.getEvery().getSeconds());
    }

    private void cancelSchedule(String agentId) {
        ScheduledFuture<?> future = scheduledTasks.remove(agentId);
        if (future != null) {
            future.cancel(false);
            log.info("Heartbeat cancelled for agent '{}'", agentId);
        }
    }

    // ── Heartbeat Execution ────────────────────────────────────────────

    private void executeHeartbeat(String agentId, HeartbeatConfig config) {
        try {
            // 1. Check active hours window
            if (!isWithinActiveHours(config.getActiveHours())) {
                log.debug("Heartbeat skipped for '{}': outside active hours", agentId);
                return;
            }

            // 2. Check skipWhenBusy
            if (config.isSkipWhenBusy()) {
                AtomicInteger count = activeSubAgents.get(agentId);
                if (count != null && count.get() > 0) {
                    log.debug("Heartbeat skipped for '{}': {} sub-agents active", agentId, count.get());
                    return;
                }
            }

            // 3. Create session key
            String sessionKey = config.getSessionKey() != null ? config.getSessionKey() : agentId;
            String runId = sessionKey + "-" + UUID.randomUUID().toString().substring(0, 8);
            long startMs = System.currentTimeMillis();

            log.info("Heartbeat starting: agent={} runId={}", agentId, runId);

            // 4. Prepare context
            AgentContext ctx = buildHeartbeatContext(agentId, sessionKey, runId, config);

            // 5. Run single-turn ReAct
            String result = runHeartbeatReAct(ctx, config);

            long elapsedMs = System.currentTimeMillis() - startMs;

            // 6. Deliver result
            deliverHeartbeatResult(agentId, config, result);

            // 7. Dispatch events
            dispatchHeartbeatEvent("heartbeat_complete", agentId, runId,
                    Map.of("elapsedMs", elapsedMs, "message", result.substring(0,
                            Math.min(result.length(), config.getAckMaxChars()))));

            log.info("Heartbeat completed: agent={} runId={} elapsed={}ms", agentId, runId, elapsedMs);

        } catch (Exception e) {
            log.error("Heartbeat failed for agent '{}': {}", agentId, e.getMessage(), e);
            dispatchHeartbeatEvent("heartbeat_error", agentId, null,
                    Map.of("error", e.getMessage()));
        }
    }

    private AgentContext buildHeartbeatContext(String agentId, String sessionKey,
                                                String runId, HeartbeatConfig config) {
        String prompt = config.getPrompt();
        if (prompt == null || prompt.isEmpty()) {
            prompt = "Heartbeat check-in. Provide a brief status update on your current state and any pending tasks.";
        }

        if (config.isIncludeSystemPromptSection()) {
            prompt = "[System Status Check]\n" + prompt;
        }

        // Create a transient context for this single heartbeat run
        AgentContext ctx = new AgentContext(
                config.isIsolatedSession() ? runId : sessionKey,
                prompt,
                null, // system prompt handled by agent config
                toolRegistry,
                null, // no method — heartbeat is not a user invocation
                null
        );

        if (config.isLightContext()) {
            // Load only HEARTBEAT.md context (implemented by memory system)
            ctx.setAttribute("heartbeatMode", true);
            ctx.setAttribute("contextFiles", List.of("HEARTBEAT.md"));
        }

        return ctx;
    }

    private String runHeartbeatReAct(AgentContext ctx, HeartbeatConfig config) {
        // Build a minimal ChatRequest for the heartbeat
        ChatRequest request = ChatRequest.builder()
                .messages(new ArrayList<>(List.of(Message.user(ctx.getUserMessage()))))
                .stream(false) // non-streaming for heartbeat
                .build();

        // Use a ReActEngine instance with no tools for lightweight execution
        DefaultReActEngine engine = new DefaultReActEngine(null, null) {
            @Override
            public String execute(ChatFacade chatFacade, ChatRequest request,
                                  ToolExecutor toolExecutor) {
                // Single-turn: no tool calling for heartbeat by default
                try {
                    var model = chatFacade.resolveModel(chatFacade.route(request, null));
                    var response = model.chat(request);
                    String content = response.getContent();
                    request.getMessages().add(Message.assistant(content != null ? content : ""));
                    return content != null ? content : "(no response)";
                } catch (Exception e) {
                    log.error("Heartbeat LLM call failed: {}", e.getMessage());
                    return "[Heartbeat LLM error: " + e.getMessage() + "]";
                }
            }
        };

        try {
            String result = engine.execute(chatFacade, request, null);
            return result != null ? result : "(empty response)";
        } catch (Exception e) {
            return "[Heartbeat error: " + e.getMessage() + "]";
        }
    }

    private void deliverHeartbeatResult(String agentId, HeartbeatConfig config, String result) {
        if (config.getTarget() == HeartbeatConfig.DeliveryTarget.NONE) {
            return;
        }

        // Delivery to target channel/user (implementation depends on channel adapter)
        // For now, publish as an event for the channel adapter to pick up
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

    // ── Active Hours Check ─────────────────────────────────────────────

    private boolean isWithinActiveHours(HeartbeatConfig.ActiveHours hours) {
        if (hours == null || hours.getStart() == null || hours.getEnd() == null) {
            return true; // no restriction
        }

        try {
            ZoneId zone = ZoneId.of(hours.getTimezone());
            ZonedDateTime now = ZonedDateTime.now(zone);

            LocalTime start = LocalTime.parse(hours.getStart(), DateTimeFormatter.ofPattern("HH:mm"));
            LocalTime end = LocalTime.parse(hours.getEnd(), DateTimeFormatter.ofPattern("HH:mm"));
            LocalTime current = now.toLocalTime();

            // Check days of week if configured
            if (hours.getDaysOfWeek() != null && !hours.getDaysOfWeek().isEmpty()) {
                String today = now.getDayOfWeek().name().substring(0, 3).toUpperCase();
                if (!hours.getDaysOfWeek().toUpperCase().contains(today)) {
                    return false;
                }
            }

            if (start.isBefore(end)) {
                // Normal range: e.g., 09:00 - 18:00
                return !current.isBefore(start) && current.isBefore(end);
            } else {
                // Overnight range: e.g., 22:00 - 06:00
                return !current.isBefore(start) || current.isBefore(end);
            }
        } catch (Exception e) {
            log.warn("Active hours check failed, defaulting to allowed: {}", e.getMessage());
            return true;
        }
    }
}
```

### 4.3.4 Heartbeat Event Types

```java
package lyjew.com.lyclaw.react.heartbeat;

import lyjew.com.lyclaw.event.Event;

import java.util.Map;

/**
 * Heartbeat lifecycle event. Published at each phase of a heartbeat run.
 *
 * <p>Event types:
 * <ul>
 *   <li>heartbeat_start — agentId, sessionKey, timestamp</li>
 *   <li>heartbeat_thinking — agentId (LLM is generating)</li>
 *   <li>heartbeat_reply — agentId, message</li>
 *   <li>heartbeat_complete — agentId, elapsedMs, message preview</li>
 *   <li>heartbeat_error — agentId, error</li>
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
 * Heartbeat delivery event. Published when a heartbeat result needs to be
 * delivered to a target channel or user.
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

### 4.3.5 SSE Event Schema

Heartbeat SSE events (when the heartbeat is triggered by an external request rather than cron):

| Event | `event:` field | `data:` structure |
|---|---|---|
| `heartbeat_start` | `heartbeat_start` | `{"agentId":"...", "sessionKey":"...", "timestamp":"..."}` |
| `heartbeat_thinking` | `heartbeat_thinking` | `{"agentId":"..."}` |
| `heartbeat_reply` | `heartbeat_reply` | `{"agentId":"...", "message":"...", "..."}` |
| `heartbeat_complete` | `heartbeat_complete` | `{"agentId":"...", "elapsedMs":1234, "message":"preview..."}` |
| `heartbeat_error` | `heartbeat_error` | `{"agentId":"...", "error":"..."}` |

### 4.3.6 YAML Configuration

```yaml
# application.yml — heartbeat configuration per agent
lyclaw:
  heartbeat:
    enabled: true
    every: 30m                      # Duration: 30m, 1h, etc.
    active-hours:
      start: "09:00"
      end: "18:00"
      timezone: Asia/Shanghai
      days-of-week: MON,TUE,WED,THU,FRI
    model: null                     # null = use agent default
    session-key: daily-checkin
    target: LAST                    # LAST | NONE
    direct-policy: ALLOW            # ALLOW | BLOCK
    to: null                        # E.164 phone or chat id
    account-id: null                # multi-account selector
    prompt: "Good morning! Here is your daily briefing. What are the top priorities today?"
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

## 4.4 Run Retries Enhancement

### 4.4.1 Motivation

The current `ReflexionLoop` uses a simple `maxRetries` parameter (typically 2) and a static `qualityThreshold` (0.6). This is insufficient for production:

- **Hardcoded retry budget**: No per-agent or per-fallback-model differentiation
- **No retry history**: Cannot learn from previous failures to adjust strategy
- **No model fallback chain**: If primary model consistently fails, no mechanism to try alternative (cheaper/faster/smaller) models
- **No retry metadata**: Current `ReflexionResult.Attempt` records only score and feedback, not model/provider used

### 4.4.2 RunRetriesConfig

```java
package lyjew.com.lyclaw.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Run retry configuration for ReAct loop reflection retries.
 * <p>Controls the retry budget, strategy selection, and model fallback behavior.
 */
@ConfigurationProperties(prefix = "lyclaw.retry")
public class RunRetriesConfig {

    /**
     * Base number of retry iterations for the primary model.
     * Total retries = base + (perProfile * numberOfFallbackProfiles)
     */
    private int base = 24;

    /**
     * Additional retry iterations allocated per fallback model profile.
     * Each fallback model in the chain gets this many extra attempts.
     */
    private int perProfile = 8;

    /**
     * Minimum floor for total retry iterations.
     * Even if base+perProfile*count calculates lower, this floor applies.
     */
    private int min = 32;

    /**
     * Maximum ceiling for total retry iterations.
     * Prevents unbounded retry loops.
     */
    private int max = 160;

    /**
     * Quality threshold for retry termination.
     * If the reflection score meets or exceeds this threshold, retries stop early.
     */
    private double qualityThreshold = 0.7;

    /**
     * Strategy for selecting the next model when a retry is needed.
     * <ul>
     *   <li>SAME_MODEL — retry with the same model (default)</li>
     *   <li>FALLBACK_CHAIN — try next model in the fallback chain</li>
     *   <li>ADAPTIVE — switch to fallback after 3 consecutive same-model failures</li>
     * </ul>
     */
    private RetryStrategy defaultStrategy = RetryStrategy.ADAPTIVE;

    /**
     * Maximum consecutive failures before escalating to fallback model
     * (only applies when strategy is ADAPTIVE).
     */
    private int maxConsecutiveFailuresBeforeFallback = 3;

    /**
     * Exponential backoff configuration for retry delays.
     */
    private RetryBackoff backoff = new RetryBackoff();

    // getters and setters omitted

    public enum RetryStrategy { SAME_MODEL, FALLBACK_CHAIN, ADAPTIVE }

    /**
     * Exponential backoff for retry delays.
     */
    public static class RetryBackoff {
        /** Initial delay in milliseconds. */
        private long initialDelayMs = 500;
        /** Maximum delay in milliseconds. */
        private long maxDelayMs = 30_000;
        /** Backoff multiplier (e.g., 2.0 = double each retry). */
        private double multiplier = 2.0;
        /** Backoff applies to: BOTH = model call + reflection, LLM_ONLY, REFLECTION_ONLY */
        private BackoffTarget target = BackoffTarget.BOTH;

        // getters and setters omitted
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
 * Manages retry budget, tracking, and strategy for ReAct loop reflection retries.
 *
 * <p>Replaces hardcoded MAX_REFLECTION_RETRIES=2 with a configurable, model-aware
 * retry system that supports fallback chains and adaptive strategy selection.
 *
 * <h3>Retry Budget Formula:</h3>
 * <pre>
 *   totalRetries = max(min, min(max, base + perProfile * fallbackProfileCount))
 * </pre>
 *
 * <h3>Retry State Machine:</h3>
 * <pre>
 *   [Execute with model M]
 *        |
 *        v
 *   [Reflect] ──score >= threshold──> [DONE]
 *        |
 *   score < threshold
 *        |
 *        v
 *   [Check retry budget] ──exhausted──> [DONE with best result]
 *        |
 *   budget available
 *        |
 *        v
 *   [Select strategy: same model / fallback]
 *        |
 *        v
 *   [Plan revision] ──> [Execute with (new) model M']
 * </pre>
 */
public class RunRetryManager {

    private static final Logger log = LoggerFactory.getLogger(RunRetryManager.class);

    private final RunRetriesConfig config;
    private final List<String> fallbackProfiles;
    private final int maxRetries;

    // Per-session retry history
    private final Map<String, RetrySession> sessions = new ConcurrentHashMap<>();

    public RunRetryManager(RunRetriesConfig config, List<String> fallbackProfiles) {
        this.config = config;
        this.fallbackProfiles = fallbackProfiles != null ? fallbackProfiles : List.of();
        this.maxRetries = calculateMaxRetries(config, this.fallbackProfiles.size());
    }

    /**
     * Calculate total retry budget.
     */
    private int calculateMaxRetries(RunRetriesConfig config, int fallbackCount) {
        int total = config.getBase() + config.getPerProfile() * fallbackCount;
        return Math.max(config.getMin(), Math.min(config.getMax(), total));
    }

    /**
     * Get the maximum retry count for a session.
     */
    public int getMaxRetries(String sessionId) {
        return maxRetries;
    }

    /**
     * Check if more retries are available for the given session.
     *
     * @param sessionId the session to check
     * @return true if at least one more retry is budgeted
     */
    public boolean canRetry(String sessionId) {
        RetrySession session = sessions.get(sessionId);
        if (session == null) {
            return maxRetries > 0;
        }
        return session.getAttemptCount() < maxRetries;
    }

    /**
     * Record a retry attempt for the session.
     *
     * @param sessionId the session identifier
     * @param attempt   the completed retry attempt
     */
    public void recordRetry(String sessionId, RetryAttempt attempt) {
        RetrySession session = sessions.computeIfAbsent(sessionId, RetrySession::new);
        session.addAttempt(attempt);
        log.debug("Retry recorded: session={} attempt={}/{} score={} model={}",
                sessionId, session.getAttemptCount(), maxRetries,
                attempt.getQualityScore(), attempt.getModelUsed());
    }

    /**
     * Determine the retry strategy based on history.
     *
     * @param sessionId the session identifier
     * @param primaryModel the primary model name
     * @return the model to use for the next attempt
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
                // Rotate through fallback models on each retry
                int attemptIndex = session.getAttemptCount();
                if (attemptIndex < fallbackProfiles.size()) {
                    return fallbackProfiles.get(attemptIndex);
                }
                // Cycle back through fallbacks
                return fallbackProfiles.get(attemptIndex % fallbackProfiles.size());
            }

            case ADAPTIVE:
            default: {
                // Check consecutive failures with current model
                int consecutiveFailures = session.countConsecutiveFailuresWithCurrentModel();
                if (consecutiveFailures >= config.getMaxConsecutiveFailuresBeforeFallback()) {
                    // Switch to next fallback
                    int fallbackIndex = session.getCurrentFallbackIndex();
                    if (fallbackIndex < fallbackProfiles.size()) {
                        session.incrementFallbackIndex();
                        String fallback = fallbackProfiles.get(fallbackIndex);
                        log.info("Adaptive retry switching to fallback model: {} -> {} ({} consecutive failures)",
                                session.getCurrentModel(), fallback, consecutiveFailures);
                        return fallback;
                    }
                    // All fallbacks exhausted, stick with primary
                    return primaryModel;
                }
                return session.getCurrentModel() != null ? session.getCurrentModel() : primaryModel;
            }
        }
    }

    /**
     * Calculate exponential backoff delay for the next retry.
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
     * Clear retry state for a session (called on session completion/reset).
     */
    public void clearSession(String sessionId) {
        sessions.remove(sessionId);
    }

    /**
     * Get retry statistics for monitoring.
     */
    public RetryStats getStats(String sessionId) {
        RetrySession session = sessions.get(sessionId);
        if (session == null) {
            return new RetryStats(0, 0, maxRetries, 0.0, 0.0);
        }
        return session.computeStats(maxRetries);
    }

    // ── Inner Types ────────────────────────────────────────────────────

    /**
     * Per-session retry tracking.
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
     * A single retry attempt record.
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
                    0 // elapsedMs tracked separately
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
     * Retry statistics snapshot for monitoring dashboards.
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

### 4.4.4 Integration with AgentContext

Extend `AgentContext` to carry retry metadata:

```java
// Additions to AgentContext:

/** Run metadata for retry tracking. Stored in attributes for serializability. */
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

### 4.4.5 Integration with ReflexionLoop

The existing `ReflexionLoop` is enhanced to use `RunRetryManager`:

```java
// Enhanced ReflexionLoop (diff from current):
//
// Before:
//   public ReflexionLoop(ReflectionEngine engine, TaskPlanner planner,
//                         int maxRetries, double qualityThreshold) { ... }
//
// After:
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
//               // Execute with current model
//               ActionResult result = executePlan(currentPlan, executor);
//
//               // Reflect
//               double score = reflect(context, result);
//
//               // Record retry
//               retryManager.recordRetry(context.getSessionId(),
//                       new RunRetryManager.RetryAttempt(attempt, currentModel, score,
//                               extractErrors(result), null, 0));
//
//               attempts.add(new ReflexionResult.Attempt(attempt, result, score, buildFeedback(result)));
//
//               // Check quality threshold
//               if (score >= qualityThreshold) break;
//
//               // Determine next model
//               currentModel = retryManager.determineNextModel(
//                       context.getSessionId(), primaryModel);
//
//               // Apply backoff
//               long backoffMs = retryManager.calculateBackoffMs(context.getSessionId());
//               if (backoffMs > 0) Thread.sleep(backoffMs);
//
//               // Revise plan
//               currentPlan = taskPlanner.revise(currentPlan, buildFeedback(result));
//               attempt++;
//           }
//
//           long totalMs = System.currentTimeMillis() - startTime;
//           return new ReflexionResult(loopId, attempts, totalMs);
//       }
//   }
```

### 4.4.6 YAML Configuration

```yaml
# application.yml — retry configuration
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

## Integration Diagram

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                          Phase 4 — System Architecture                       │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│  ┌─────────────┐    ┌──────────────────┐    ┌───────────────────────────┐  │
│  │ User Request │───>│  Pipeline Stages  │───>│  SSE Event Stream         │  │
│  │ (HTTP/MQTT)  │    │                   │    │  (to Web/App client)      │  │
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
│  │  (streaming)     │ │            │ │                    │                 │
│  │                  │ │ SandboxExe-│ │  Cron: every 30m   │                 │
│  │  BlockStreaming  │ │ cutionSvc  │ │  ActiveHours check │                 │
│  │  Coalesce        │ │            │ │  LightContext      │                 │
│  │  HumanDelay      │ │ Docker/Pod-│ │  IsolatedSession   │                 │
│  │  TypingIndicator │ │ man backend│ │  SkipWhenBusy      │                 │
│  └────────┬─────────┘ └─────┬──────┘ └─────────┬─────────┘                 │
│           │                 │                   │                           │
│           v                 v                   v                           │
│  ┌──────────────────────────────────────────────────┐                      │
│  │               RunRetryManager                     │                      │
│  │                                                   │                      │
│  │  Retry Budget: base + perProfile * fallbackCount  │                      │
│  │  Strategy: ADAPTIVE / FALLBACK_CHAIN / SAME_MODEL │                      │
│  │  Backoff: exponential with configurable ceiling   │                      │
│  │  Session tracking: per-session retry history      │                      │
│  └──────────────────────────────────────────────────┘                      │
│                                                                             │
│  ┌─────────────────────── Event Bus ───────────────────────┐               │
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

### Data Flow: Streaming Pipeline

```
LLM Token Stream (Flux<ModelResponse>)
     │
     ▼
┌──────────────────┐
│ State Machine    │  0=buffering(thinking), 1=relaying(stream tokens), 2=tools_detected
│ (DefaultReAct    │
│  Engine)         │
└────────┬─────────┘
         │  Case 1: state=1 (pure text stream)
         │    → tokens emitted as fine-grained SSE "message" events
         │
         │  Case 2: state=2 (tools detected)
         │    → tool execution, then final text response
         │    → final text passed to BlockStreamingController
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
│ TypingIndicator  │  Emits "typing" SSE events at intervals during gaps
└────────┬─────────┘
         │
         ▼
    SSE Client
```

### Sandbox Execution Flow

```
    Tool Call Request
         │
         ▼
    SandboxHook.wrapToolExecutor()
         │
         ▼
    ctx.getSandboxLevel() == PROCESS && backend != NONE ?
         │
    ┌────┴────┐
    │   YES   │               │   NO    │
    ▼         ▼               ▼         ▼
┌─────────────────┐   ┌──────────────────┐
│ SandboxExecSvc  │   │ ToolSandbox       │
│                 │   │ (legacy DIRECT/   │
│ createSandbox() │   │  SANDBOX modes)   │
│ if not exists   │   └──────────────────┘
│                 │
│ executeInSandbox│
│                 │
│ docker exec     │
│ cmd [bash -c]   │
│                 │
│ capture stdout  │
│ check exit code │
└────────┬────────┘
         │
         ▼
    ToolExecutionResult
```

---

## SSE Event Schema Reference

### Block Streaming Events

| Event Name | `event:` | `data:` schema |
|---|---|---|
| message (block) | `message` | `{"type":"message","content":"block text..."}` |
| typing | `typing` | `{"type":"typing","agentId":"...","stage":"RESPOND"}` |

### Sandbox Events

| Event Name | `event:` | `data:` schema |
|---|---|---|
| sandbox_created | `sandbox_created` | `{"containerId":"...","sessionId":"...","image":"..."}` |
| sandbox_executing | `sandbox_executing` | `{"toolName":"...","containerId":"..."}` |
| sandbox_result | `sandbox_result` | `{"toolName":"...","exitCode":0,"stdout":"..."}` |
| sandbox_destroyed | `sandbox_destroyed` | `{"containerId":"..."}` |

### Heartbeat Events

| Event Name | `event:` | `data:` schema |
|---|---|---|
| heartbeat_start | `heartbeat_start` | `{"agentId":"...","sessionKey":"...","timestamp":"..."}` |
| heartbeat_thinking | `heartbeat_thinking` | `{"agentId":"..."}` |
| heartbeat_reply | `heartbeat_reply` | `{"agentId":"...","message":"..."}` |
| heartbeat_complete | `heartbeat_complete` | `{"agentId":"...","elapsedMs":1234,"message":"preview..."}` |
| heartbeat_error | `heartbeat_error` | `{"agentId":"...","error":"..."}` |

### Retry Events

| Event Name | `event:` | `data:` schema |
|---|---|---|
| retry_attempt | `retry_attempt` | `{"sessionId":"...","attempt":3,"model":"gpt-4","score":0.45}` |
| retry_fallback | `retry_fallback` | `{"sessionId":"...","fromModel":"gpt-4","toModel":"gpt-4o-mini"}` |
| retry_exhausted | `retry_exhausted` | `{"sessionId":"...","totalAttempts":32,"bestScore":0.68}` |

---

## Summary of Changes

### New Files (Java)

| File | Package | Description |
|---|---|---|
| `BlockStreamingConfig.java` | `lyjew.com.lyclaw.config` | Block streaming configuration POJO |
| `BlockStreamingChunk.java` | `lyjew.com.lyclaw.config` | Soft chunking config |
| `BlockStreamingCoalesce.java` | `lyjew.com.lyclaw.config` | Coalescing config |
| `HumanDelayConfig.java` | `lyjew.com.lyclaw.config` | Human typing delay config |
| `BlockStreamingController.java` | `lyjew.com.lyclaw.react.stream` | Block-based streaming pipeline |
| `TypingIndicatorController.java` | `lyjew.com.lyclaw.react.stream` | Typing indicator SSE emitter |
| `AgentSandboxConfig.java` | `lyjew.com.lyclaw.config` | Container sandbox config |
| `SandboxExecutionService.java` | `lyjew.com.lyclaw.security.sandbox` | Docker/Podman sandbox service |
| `SandboxHandle.java` | `lyjew.com.lyclaw.security.sandbox` | Sandbox container handle |
| `HeartbeatConfig.java` | `lyjew.com.lyclaw.config` | Heartbeat config POJO |
| `HeartbeatScheduler.java` | `lyjew.com.lyclaw.react.heartbeat` | Cron-based heartbeat executor |
| `HeartbeatEvent.java` | `lyjew.com.lyclaw.react.heartbeat` | Heartbeat event types |
| `RunRetriesConfig.java` | `lyjew.com.lyclaw.config` | Retry budget config |
| `RunRetryManager.java` | `lyjew.com.lyclaw.react.retry` | Retry manager with fallback chains |

### Modified Files (Java)

| File | Changes |
|---|---|
| `AgentContext.java` | Add `SandboxHandle sandboxHandle`, `Map<String,Object> runMetadata`, `recordRetryState()` |
| `SandboxHook.java` | Integrate `SandboxExecutionService` for `PROCESS` level when container backend configured |
| `DefaultReActEngine.java` | Replace `splitIntoEvents()` with `BlockStreamingController.streamResponse()` |
| `RespondStage.java` | Integrate `BlockStreamingController`, `TypingIndicatorController`, `HumanDelayConfig` |
| `ReflexionLoop.java` | Replace hardcoded `maxRetries` with `RunRetryManager`, add model rotation |

### Configuration Keys (application.yml)

| Prefix | Keys |
|---|---|
| `lyclaw.streaming.block` | enabled, break-mode, chunk.*, coalesce.*, max-chunk-chars, repeat-suppression, delivery-mode, hidden-boundary |
| `lyclaw.streaming.human-delay` | enabled, min-delay-ms, max-delay-ms, chars-per-second, adaptive-speed, long-reply-threshold |
| `lyclaw.streaming.typing-indicator` | mode, interval-seconds |
| `lyclaw.sandbox` | backend, image, root-dir, allowed-commands, denied-commands, network-enabled, file-system-write-enabled, memory-limit-mb, cpu-limit, timeout-seconds, fs-bridge.* |
| `lyclaw.heartbeat` | enabled, every, active-hours.*, model, session-key, target, direct-policy, to, account-id, prompt, include-system-prompt-section, ack-max-chars, suppress-tool-error-warnings, timeout-seconds, light-context, isolated-session, skip-when-busy, include-reasoning |
| `lyclaw.retry` | base, per-profile, min, max, quality-threshold, default-strategy, max-consecutive-failures-before-fallback, backoff.* |
