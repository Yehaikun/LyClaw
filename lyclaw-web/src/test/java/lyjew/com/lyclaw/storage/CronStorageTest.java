package lyjew.com.lyclaw.storage;

import lyjew.com.lyclaw.LyClawApplication;
import lyjew.com.lyclaw.model.CronJob;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * CronJob 存储层集成测试
 * 测试定时任务的增删改查
 */
@SpringBootTest(classes = LyClawApplication.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class CronStorageTest {

    @Autowired
    private CronStorage cronStorage;

    private static String testJobId1;
    private static String testJobId2;
    private static String testJobId3;

    // ========== 创建定时任务 ==========

    @Test
    @Order(1)
    @DisplayName("创建任务1 - 每日天气播报")
    void testCreateJob1_DailyWeather() {
        CronJob job = CronJob.builder()
                .id(UUID.randomUUID().toString())
                .name("每日天气播报")
                .cronExpr("0 0 9 * * *")
                .prompt("搜索郑州今日天气，整理成简短播报，包括温度、天气状况、出行建议")
                .model("minimax")
                .enabled(true)
                .lastRunStatus("pending")
                .nextRunTime("2026-04-27T09:00:00")
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();

        cronStorage.save(job);
        testJobId1 = job.getId();

        assertNotNull(testJobId1, "任务ID不应为空");

        System.out.println("=" .repeat(60));
        System.out.println("✅ 创建定时任务1: " + job.getName());
        System.out.println("📁 任务ID: " + testJobId1);
        System.out.println("⏰ Cron: " + job.getCronExpr());
        System.out.println("📁 文件位置: LyClaw/cron/" + testJobId1 + ".json");
        System.out.println("=" .repeat(60));
    }

    @Test
    @Order(2)
    @DisplayName("创建任务2 - 每周科技新闻汇总")
    void testCreateJob2_WeeklyTechNews() {
        CronJob job = CronJob.builder()
                .id(UUID.randomUUID().toString())
                .name("每周科技新闻汇总")
                .cronExpr("0 0 10 * * 1")
                .prompt("搜索本周最重要的5条科技新闻，用中文总结每条100字以内，标注来源")
                .model("minimax")
                .enabled(true)
                .lastRunStatus("pending")
                .nextRunTime("2026-04-27T10:00:00")
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();

        cronStorage.save(job);
        testJobId2 = job.getId();

        System.out.println("✅ 创建定时任务2: " + job.getName());
        System.out.println("📁 任务ID: " + testJobId2);
        System.out.println("⏰ Cron: " + job.getCronExpr() + " (每周一10:00)");
        System.out.println();
    }

    @Test
    @Order(3)
    @DisplayName("创建任务3 - 每日晚安消息（已禁用）")
    void testCreateJob3_GoodnightMessage() {
        CronJob job = CronJob.builder()
                .id(UUID.randomUUID().toString())
                .name("每日晚安消息")
                .cronExpr("0 0 22 * * *")
                .prompt("生成一条温馨的晚安消息，包含明天的天气提醒和一句励志语录")
                .model("minimax")
                .enabled(false)
                .lastRunStatus("pending")
                .nextRunTime("2026-04-26T22:00:00")
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();

        cronStorage.save(job);
        testJobId3 = job.getId();

        System.out.println("✅ 创建定时任务3: " + job.getName() + " (已禁用)");
        System.out.println("📁 任务ID: " + testJobId3);
        System.out.println();
    }

    // ========== 查询单个任务 ==========

    @Test
    @Order(4)
    @DisplayName("查询 - 根据ID查询任务详情")
    void testGetCronJobById() {
        Optional<CronJob> opt = cronStorage.get(testJobId1);
        assertTrue(opt.isPresent(), "任务应该存在");

        CronJob job = opt.get();
        assertEquals("每日天气播报", job.getName());
        assertEquals("0 0 9 * * *", job.getCronExpr());
        assertTrue(job.isEnabled());
        assertNotNull(job.getCreatedAt());
        assertNotNull(job.getUpdatedAt());

        System.out.println("📋 任务详情:");
        System.out.println("   名称: " + job.getName());
        System.out.println("   Cron: " + job.getCronExpr());
        System.out.println("   Prompt: " + job.getPrompt());
        System.out.println("   模型: " + job.getModel());
        System.out.println("   状态: " + (job.isEnabled() ? "启用" : "禁用"));
        System.out.println("   上次执行: " + job.getLastRunStatus());
        System.out.println("   下次执行: " + job.getNextRunTime());
        System.out.println("✅ 查询成功");
        System.out.println();
    }

    // ========== 模拟任务执行更新 ==========

    @Test
    @Order(5)
    @DisplayName("更新 - 模拟任务1执行成功")
    void testUpdateJobAfterExecution() {
        Optional<CronJob> opt = cronStorage.get(testJobId1);
        assertTrue(opt.isPresent());
        CronJob job = opt.get();

        // 模拟执行成功后更新
        job.setLastRunTime("2026-04-27T09:00:00");
        job.setLastRunStatus("success");
        job.setLastRunResult("郑州今日天气：晴转多云，18-28°C，东南风2-3级，空气质量良。适合户外活动，建议携带防晒用品。");
        job.setNextRunTime("2026-04-28T09:00:00");
        job.setUpdatedAt(LocalDateTime.now());

        cronStorage.save(job);

        // 重新读取验证
        Optional<CronJob> verify = cronStorage.get(testJobId1);
        assertTrue(verify.isPresent());
        assertEquals("success", verify.get().getLastRunStatus());
        assertNotNull(verify.get().getLastRunResult());
        assertEquals("2026-04-28T09:00:00", verify.get().getNextRunTime());

        System.out.println("🔄 模拟任务执行完成");
        System.out.println("   执行时间: " + job.getLastRunTime());
        System.out.println("   执行状态: " + job.getLastRunStatus());
        System.out.println("   执行结果: " + job.getLastRunResult());
        System.out.println("   下次执行: " + job.getNextRunTime());
        System.out.println("✅ 更新成功");
        System.out.println();
    }

    @Test
    @Order(6)
    @DisplayName("更新 - 模拟任务2执行失败")
    void testUpdateJobExecutionFailed() {
        Optional<CronJob> opt = cronStorage.get(testJobId2);
        assertTrue(opt.isPresent());
        CronJob job = opt.get();

        job.setLastRunTime("2026-04-27T10:00:00");
        job.setLastRunStatus("failed");
        job.setLastRunResult("执行失败：模型API返回429限流，重试3次后仍失败");
        job.setUpdatedAt(LocalDateTime.now());

        cronStorage.save(job);

        Optional<CronJob> verify = cronStorage.get(testJobId2);
        assertTrue(verify.isPresent());
        assertEquals("failed", verify.get().getLastRunStatus());
        assertTrue(verify.get().getLastRunResult().contains("429"));

        System.out.println("⚠️ 任务2执行失败");
        System.out.println("   失败原因: " + job.getLastRunResult());
        System.out.println();
    }

    // ========== 列出所有任务 ==========

    @Test
    @Order(7)
    @DisplayName("列表 - 列出所有定时任务")
    void testListAllCronJobs() {
        List<CronJob> allJobs = cronStorage.getAll();

        assertTrue(allJobs.size() >= 3, "至少应该有3个任务");

        System.out.println("📋 定时任务列表（共 " + allJobs.size() + " 个）:");
        System.out.println("-".repeat(60));

        int enabledCount = 0;
        int disabledCount = 0;

        for (CronJob job : allJobs) {
            String status = job.isEnabled() ? "✅ 启用" : "⛔ 禁用";
            if (job.isEnabled()) enabledCount++;
            else disabledCount++;

            String shortId = job.getId().substring(0, Math.min(8, job.getId().length()));
            System.out.printf("   [%s...] %s | %s | %s | 上次: %s | 下次: %s%n",
                    shortId,
                    job.getName(),
                    job.getCronExpr(),
                    status,
                    job.getLastRunStatus() != null ? job.getLastRunStatus() : "N/A",
                    job.getNextRunTime() != null ? job.getNextRunTime() : "N/A");
        }

        System.out.println("-".repeat(60));
        System.out.println("   启用: " + enabledCount + " 个");
        System.out.println("   禁用: " + disabledCount + " 个");
        System.out.println();
    }

    // ========== 存在性检查 ==========

    @Test
    @Order(8)
    @DisplayName("检查 - 任务存在性验证")
    void testCronJobExists() {
        assertTrue(cronStorage.exists(testJobId1), "任务1应该存在");
        assertTrue(cronStorage.exists(testJobId2), "任务2应该存在");
        assertFalse(cronStorage.exists("non-existent-id-12345"), "不存在的任务应返回false");

        System.out.println("✅ 任务存在性检查通过");
        System.out.println("   任务1存在: " + cronStorage.exists(testJobId1));
        System.out.println("   任务3存在: " + cronStorage.exists(testJobId3));
        System.out.println("   不存在任务: " + cronStorage.exists("fake-id"));
        System.out.println();
    }

    // ========== 删除任务 ==========

    @Test
    @Order(9)
    @DisplayName("删除 - 删除已禁用的任务3")
    void testDeleteDisabledJob() {
        assertTrue(cronStorage.exists(testJobId3), "删除前任务3应该存在");

        boolean deleted = cronStorage.delete(testJobId3);
        assertTrue(deleted, "删除应该成功");
        assertFalse(cronStorage.exists(testJobId3), "删除后任务3不应存在");

        System.out.println("🗑️ 已删除任务3（每日晚安消息）");
        System.out.println("   剩余任务数: " + cronStorage.getAll().size());
        System.out.println();
    }

    @Test
    @Order(10)
    @DisplayName("删除 - 验证删除不存在的任务")
    void testDeleteNonExistentJob() {
        boolean deleted = cronStorage.delete("non-existent-id");
        assertFalse(deleted, "删除不存在的任务应返回false");

        System.out.println("✅ 删除不存在任务返回false，符合预期");
        System.out.println();
    }

    // ========== 最终验证 ==========

    @Test
    @Order(11)
    @DisplayName("验证 - 最终任务列表")
    void testFinalJobList() {
        List<CronJob> allJobs = cronStorage.getAll();
        assertEquals(2, allJobs.size(), "删除后应剩2个任务");

        System.out.println("=" .repeat(60));
        System.out.println("📋 最终定时任务列表（共 " + allJobs.size() + " 个）");
        System.out.println("=" .repeat(60));

        for (CronJob job : allJobs) {
            System.out.println("   📌 " + job.getName());
            System.out.println("      Cron: " + job.getCronExpr());
            System.out.println("      状态: " + (job.isEnabled() ? "启用" : "禁用"));
            System.out.println("      上次执行: " + job.getLastRunStatus());
            System.out.println("      下次执行: " + job.getNextRunTime());
            System.out.println("      创建时间: " + job.getCreatedAt());
            System.out.println("      更新时间: " + job.getUpdatedAt());
            System.out.println();
        }

        System.out.println("✅ 定时任务存储层测试全部通过！");
    }
}