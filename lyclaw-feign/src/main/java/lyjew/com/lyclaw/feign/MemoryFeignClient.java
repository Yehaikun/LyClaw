package lyjew.com.lyclaw.feign;

import lyjew.com.lyclaw.memory.MemoryQuery;
import lyjew.com.lyclaw.memory.MemoryQueryResult;
import lyjew.com.lyclaw.memory.MemoryStats;
import lyjew.com.lyclaw.memory.PerceptionData;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 记忆服务Feign远程调用客户端。
 *
 * <p>通过Spring Cloud OpenFeign声明式调用lyclaw-memory-service微服务，
 * 提供记忆检索、感知数据摄入、记忆巩固和统计等功能。
 * 记忆服务是Agent的长期记忆中枢，负责存储和检索过往交互中的关键信息。</p>
 *
 * <p>核心能力包括：
 * <ul>
 *   <li>感知摄入（ingest）：将Agent的感知数据存入记忆库</li>
 *   <li>记忆检索（retrieve）：根据查询条件检索相关记忆</li>
 *   <li>记忆巩固（consolidate）：在会话结束后对记忆进行整理和强化</li>
 * </ul>
 * 服务路径前缀：/api/memory</p>
 *
 * @author lyjew
 */
@FeignClient(name = "lyclaw-memory-service", path = "/api/memory")
public interface MemoryFeignClient {

    /**
     * 根据查询条件检索相关记忆。
     *
     * @param query 记忆查询请求，包含查询文本、过滤条件、返回数量等
     * @return 记忆查询结果，包含匹配的记忆条目和相关性评分
     */
    @PostMapping("/retrieve")
    MemoryQueryResult retrieve(@RequestBody MemoryQuery query);

    /**
     * 将感知数据摄入记忆系统。
     *
     * @param data      感知数据，包含Agent观察到的事件、交互内容等
     * @param sessionId 会话ID，用于关联记忆到特定会话
     * @param userId    用户ID，默认值为"default"
     * @return 摄入操作的结果
     */
    @PostMapping("/ingest")
    Map<String, Object> ingest(@RequestBody PerceptionData data,
                               @RequestParam("sessionId") String sessionId,
                               @RequestParam(value = "userId", required = false, defaultValue = "default") String userId);

    /**
     * 巩固指定用户和会话的记忆。
     *
     * <p>记忆巩固在会话结束后执行，对临时记忆进行
     * 压缩、去重和强化，形成长期记忆。</p>
     *
     * @param userId    用户ID
     * @param sessionId 会话ID
     * @return 巩固操作的结果
     */
    @PostMapping("/consolidate")
    Map<String, Object> consolidate(@RequestParam("userId") String userId, @RequestParam("sessionId") String sessionId);

    /**
     * 获取记忆系统的统计信息。
     *
     * @return 记忆统计数据，包含总记忆数、最近更新时间等
     */
    @GetMapping("/stats")
    MemoryStats getStats();
}
