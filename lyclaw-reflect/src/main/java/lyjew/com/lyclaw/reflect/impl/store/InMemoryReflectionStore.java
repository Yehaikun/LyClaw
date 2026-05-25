package lyjew.com.lyclaw.reflect.impl.store;

import lyjew.com.lyclaw.annotation.reflect.Primitive;
import lyjew.com.lyclaw.reflect.model.MemoryRecord;
import lyjew.com.lyclaw.reflect.model.MemoryType;
import lyjew.com.lyclaw.reflect.model.ReflectionContext;
import lyjew.com.lyclaw.reflect.primitive.Memory;
import lyjew.com.lyclaw.reflect.topology.PrimitiveType;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 基于 ConcurrentHashMap 的内存记忆存储实现。
 *
 * <p>三个独立命名空间：
 * <ul>
 *   <li><b>skillStore</b> — 可复用的技能/方法（跨 session 共享）</li>
 *   <li><b>experienceStore</b> — 任务经验/教训（跨 session 共享）</li>
 *   <li><b>sessionStore</b> — 当前 session 的临时记忆（session 结束后丢弃）</li>
 * </ul>
 *
 * <p>检索策略：基于关键词匹配（TF-IDF 近似），无外部向量数据库依赖。
 * 将查询拆词后统计每条记录的命中数，按命中数和重要性加权排序。
 */
@Primitive(type = PrimitiveType.MEMORY, name = "inMemory", isDefault = true)
public class InMemoryReflectionStore implements Memory {

    /** 技能记忆：可复用的工具使用技巧、代码模板等 */
    private final Map<String, MemoryRecord> skillStore = new ConcurrentHashMap<>();
    /** 经验记忆：历史任务的成功/失败经验 */
    private final Map<String, MemoryRecord> experienceStore = new ConcurrentHashMap<>();
    /** 会话记忆：当前 session 的临时上下文 */
    private final Map<String, MemoryRecord> sessionStore = new ConcurrentHashMap<>();

    @Override
    public void store(ReflectionContext ctx, MemoryRecord record) {
        if (record.getRecordId() == null) {
            record.setRecordId(UUID.randomUUID().toString());
        }
        if (record.getCreatedAt() == 0) record.setCreatedAt(System.currentTimeMillis());
        record.setUpdatedAt(System.currentTimeMillis());
        sessionStore.put(record.getRecordId(), record);
    }

    @Override
    public void storeSkill(ReflectionContext ctx, MemoryRecord skill) {
        if (skill.getRecordId() == null) {
            skill.setRecordId("skill-" + UUID.randomUUID().toString().substring(0, 8));
        }
        skill.setType(MemoryType.SKILL);
        if (skill.getCreatedAt() == 0) skill.setCreatedAt(System.currentTimeMillis());
        skill.setUpdatedAt(System.currentTimeMillis());
        skillStore.put(skill.getRecordId(), skill);
    }

    @Override
    public void storeExperience(ReflectionContext ctx, MemoryRecord experience) {
        if (experience.getRecordId() == null) {
            experience.setRecordId("exp-" + UUID.randomUUID().toString().substring(0, 8));
        }
        experience.setType(MemoryType.EXPERIENCE);
        if (experience.getCreatedAt() == 0) experience.setCreatedAt(System.currentTimeMillis());
        experience.setUpdatedAt(System.currentTimeMillis());
        experienceStore.put(experience.getRecordId(), experience);
    }

    @Override
    public List<MemoryRecord> retrieve(ReflectionContext ctx, String query, int limit) {
        // 合并所有存储源，按相关性排序
        List<MemoryRecord> all = new ArrayList<>();
        all.addAll(sessionStore.values());
        all.addAll(skillStore.values());
        all.addAll(experienceStore.values());
        return rankAndLimit(all, query, limit);
    }

    @Override
    public List<MemoryRecord> retrieveSkills(ReflectionContext ctx, String taskDescription, int limit) {
        return rankAndLimit(new ArrayList<>(skillStore.values()), taskDescription, limit);
    }

    @Override
    public List<MemoryRecord> retrieveExperiences(ReflectionContext ctx, String taskDescription, int limit) {
        return rankAndLimit(new ArrayList<>(experienceStore.values()), taskDescription, limit);
    }

    // ── 关键词匹配检索 ──

    /**
     * 将查询拆分为词元，统计每条记录的关键词命中数。
     * 最终得分 = 命中数 × 0.6 + importanceScore × 0.4，降序排列后取前 limit 条。
     */
    private List<MemoryRecord> rankAndLimit(List<MemoryRecord> records, String query, int limit) {
        if (query == null || query.isBlank()) {
            return records.stream()
                    .sorted(Comparator.comparingDouble(MemoryRecord::getImportanceScore).reversed())
                    .limit(limit)
                    .collect(Collectors.toList());
        }

        // 中文按字符分词，英文按空格分词
        String[] tokens = query.toLowerCase().split("[\\s,，。.！!？?]+");

        return records.stream()
                .map(r -> new AbstractMap.SimpleEntry<>(r, keywordScore(r, tokens)))
                .sorted((a, b) -> Double.compare(b.getValue(), a.getValue()))
                .filter(e -> e.getValue() > 0)
                .limit(limit)
                .map(Map.Entry::getKey)
                .collect(Collectors.toList());
    }

    /** 计算单条记录的关键词相关度 */
    private double keywordScore(MemoryRecord record, String[] tokens) {
        String searchText = buildSearchText(record).toLowerCase();
        int hits = 0;
        for (String token : tokens) {
            if (token.length() >= 2 && searchText.contains(token)) {
                hits++;
            }
        }
        double hitRatio = tokens.length > 0 ? (double) hits / tokens.length : 0;
        return hitRatio * 0.6 + record.getImportanceScore() * 0.4;
    }

    /** 构建可检索文本：摘要 + 内容 + 任务类型 */
    private String buildSearchText(MemoryRecord r) {
        StringBuilder sb = new StringBuilder();
        if (r.getSummary() != null) sb.append(r.getSummary()).append(" ");
        if (r.getContent() != null) sb.append(r.getContent()).append(" ");
        if (r.getTaskType() != null) sb.append(r.getTaskType());
        return sb.toString();
    }

    // ── 管理方法 ──

    public int skillCount() { return skillStore.size(); }
    public int experienceCount() { return experienceStore.size(); }
    public int sessionCount() { return sessionStore.size(); }
    public void clear() { skillStore.clear(); experienceStore.clear(); sessionStore.clear(); }
}
