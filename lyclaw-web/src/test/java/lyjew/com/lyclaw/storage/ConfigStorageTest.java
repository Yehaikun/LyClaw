package lyjew.com.lyclaw.storage;

import lyjew.com.lyclaw.LyClawApplication;
import lyjew.com.lyclaw.model.ModelConfig;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ModelConfig 存储层集成测试
 * 测试模型配置的增删改查
 */
@SpringBootTest(classes = LyClawApplication.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class ConfigStorageTest {

    @Autowired
    private ConfigStorage configStorage;

    // ========== 创建配置 ==========

    @Test
    @Order(1)
    @DisplayName("创建 - Minimax 配置")
    void testCreateMinimaxConfig() {
        ModelConfig config = ModelConfig.builder()
                .id("cfg-minimax-001")
                .name("minimax")
                .provider("minimax")
                .apiKey("sk-minimax-xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx")
                .model("abab6.5s-chat")
                .baseUrl("https://api.minimax.chat/v1")
                .enabled(true)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();

        configStorage.save(config);

        System.out.println("=" .repeat(60));
        System.out.println("✅ 创建配置: " + config.getName());
        System.out.println("   提供商: " + config.getProvider());
        System.out.println("   模型: " + config.getModel());
        System.out.println("   API端点: " + config.getBaseUrl());
        System.out.println("📁 文件位置: LyClaw/configs/minimax.json");
        System.out.println("=" .repeat(60));
    }

    @Test
    @Order(2)
    @DisplayName("创建 - OpenAI 配置")
    void testCreateOpenAIConfig() {
        ModelConfig config = ModelConfig.builder()
                .id("cfg-openai-001")
                .name("openai")
                .provider("openai")
                .apiKey("sk-proj-xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx")
                .model("gpt-4o")
                .baseUrl("https://api.openai.com/v1")
                .enabled(true)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();

        configStorage.save(config);

        System.out.println("✅ 创建配置: " + config.getName());
        System.out.println("   模型: " + config.getModel());
        System.out.println();
    }

    @Test
    @Order(3)
    @DisplayName("创建 - DeepSeek 配置")
    void testCreateDeepSeekConfig() {
        ModelConfig config = ModelConfig.builder()
                .id("cfg-deepseek-001")
                .name("deepseek")
                .provider("deepseek")
                .apiKey("sk-deepseek-xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx")
                .model("deepseek-chat")
                .baseUrl("https://api.deepseek.com/v1")
                .enabled(true)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();

        configStorage.save(config);

        System.out.println("✅ 创建配置: " + config.getName());
        System.out.println();
    }

    @Test
    @Order(4)
    @DisplayName("创建 - Gemini 配置（已禁用）")
    void testCreateGeminiConfig() {
        ModelConfig config = ModelConfig.builder()
                .id("cfg-gemini-001")
                .name("gemini")
                .provider("gemini")
                .apiKey("AIza-xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx")
                .model("gemini-2.0-flash")
                .baseUrl("https://generativelanguage.googleapis.com/v1beta")
                .enabled(false)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();

        configStorage.save(config);

        System.out.println("✅ 创建配置: " + config.getName() + " (已禁用)");
        System.out.println();
    }

    @Test
    @Order(5)
    @DisplayName("创建 - Anthropic 配置（第二版预留）")
    void testCreateAnthropicConfig() {
        ModelConfig config = ModelConfig.builder()
                .id("cfg-anthropic-001")
                .name("anthropic")
                .provider("anthropic")
                .apiKey("sk-ant-xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx")
                .model("claude-3-opus-20240229")
                .baseUrl("https://api.anthropic.com/v1")
                .enabled(true)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();

        configStorage.save(config);

        System.out.println("✅ 创建配置: " + config.getName());
        System.out.println();
    }

    // ========== 查询配置 ==========

    @Test
    @Order(6)
    @DisplayName("查询 - 根据名称查询单个配置")
    void testGetConfigByName() {
        Optional<ModelConfig> opt = configStorage.get("minimax");
        assertTrue(opt.isPresent(), "minimax配置应该存在");

        ModelConfig config = opt.get();
        assertEquals("minimax", config.getProvider());
        assertEquals("abab6.5s-chat", config.getModel());
        assertTrue(config.isEnabled());
        assertNotNull(config.getApiKey());
        assertEquals("https://api.minimax.chat/v1", config.getBaseUrl());
        assertNotNull(config.getCreatedAt());
        assertNotNull(config.getUpdatedAt());

        System.out.println("📋 minimax 配置详情:");
        System.out.println("   名称: " + config.getName());
        System.out.println("   提供商: " + config.getProvider());
        System.out.println("   模型: " + config.getModel());
        System.out.println("   API Key: " + config.getApiKey().substring(0, 15) + "...");
        System.out.println("   Base URL: " + config.getBaseUrl());
        System.out.println("   状态: " + (config.isEnabled() ? "启用" : "禁用"));
        System.out.println("✅ 查询成功");
        System.out.println();
    }

    @Test
    @Order(7)
    @DisplayName("查询 - 验证API Key脱敏长度")
    void testApiKeyFormat() {
        Optional<ModelConfig> opt = configStorage.get("openai");
        assertTrue(opt.isPresent());

        String apiKey = opt.get().getApiKey();
        assertNotNull(apiKey);
        assertTrue(apiKey.startsWith("sk-"), "OpenAI Key应以sk-开头");
        assertTrue(apiKey.length() > 20, "API Key应有足够长度");

        System.out.println("🔑 OpenAI Key 校验通过");
        System.out.println("   前缀: sk- ✅");
        System.out.println("   长度: " + apiKey.length() + " 字符");
        System.out.println();
    }

    // ========== 列出所有配置 ==========

    @Test
    @Order(8)
    @DisplayName("列表 - 列出所有模型配置")
    void testListAllConfigs() {
        List<ModelConfig> all = configStorage.getAll();

        assertTrue(all.size() >= 5, "至少应该有5个配置");

        System.out.println("📋 模型配置列表（共 " + all.size() + " 个）:");
        System.out.println("-".repeat(70));

        int enabledCount = 0;
        for (ModelConfig config : all) {
            String status = config.isEnabled() ? "✅ 启用" : "⛔ 禁用";
            if (config.isEnabled()) enabledCount++;

            System.out.printf("   %-15s | %-10s | %-22s | %s%n",
                    config.getName(),
                    config.getProvider(),
                    config.getModel(),
                    status);
        }

        System.out.println("-".repeat(70));
        System.out.println("   启用: " + enabledCount + " / " + all.size());
        System.out.println();
    }

    // ========== 存在性检查 ==========

    @Test
    @Order(9)
    @DisplayName("检查 - 配置存在性验证")
    void testConfigExists() {
        assertTrue(configStorage.exists("minimax"), "minimax应该存在");
        assertTrue(configStorage.exists("deepseek"), "deepseek应该存在");
        assertTrue(configStorage.exists("gemini"), "gemini应该存在");
        assertFalse(configStorage.exists("non-existent-config"), "不存在返回false");

        System.out.println("✅ 配置存在性检查通过");
        System.out.println("   minimax: " + configStorage.exists("minimax"));
        System.out.println("   deepseek: " + configStorage.exists("deepseek"));
        System.out.println("   nonexistent: " + configStorage.exists("nonexistent"));
        System.out.println();
    }

    // ========== 更新配置 ==========

    @Test
    @Order(10)
    @DisplayName("更新 - 修改 Gemini 配置为启用状态")
    void testUpdateGeminiConfig() {
        Optional<ModelConfig> opt = configStorage.get("gemini");
        assertTrue(opt.isPresent());
        ModelConfig config = opt.get();

        assertFalse(config.isEnabled(), "更新前应该是禁用状态");

        // 修改配置
        config.setEnabled(true);
        config.setModel("gemini-2.5-pro");
        config.setUpdatedAt(LocalDateTime.now());

        configStorage.save(config);

        // 验证更新
        Optional<ModelConfig> verify = configStorage.get("gemini");
        assertTrue(verify.isPresent());
        assertTrue(verify.get().isEnabled(), "更新后应该是启用状态");
        assertEquals("gemini-2.5-pro", verify.get().getModel());

        System.out.println("🔄 更新 Gemini 配置");
        System.out.println("   状态: 禁用 → 启用");
        System.out.println("   模型: gemini-2.0-flash → gemini-2.5-pro");
        System.out.println("✅ 更新成功");
        System.out.println();
    }

    @Test
    @Order(11)
    @DisplayName("更新 - 修改 OpenAI Base URL（代理地址）")
    void testUpdateOpenAIBaseUrl() {
        Optional<ModelConfig> opt = configStorage.get("openai");
        assertTrue(opt.isPresent());
        ModelConfig config = opt.get();

        String originalUrl = config.getBaseUrl();
        config.setBaseUrl("https://api.openai-proxy.com/v1");
        config.setUpdatedAt(LocalDateTime.now());

        configStorage.save(config);

        Optional<ModelConfig> verify = configStorage.get("openai");
        assertTrue(verify.isPresent());
        assertNotEquals(originalUrl, verify.get().getBaseUrl());

        System.out.println("🔄 修改 OpenAI Base URL");
        System.out.println("   原地址: " + originalUrl);
        System.out.println("   新地址: " + verify.get().getBaseUrl());
        System.out.println("✅ 更新成功");
        System.out.println();
    }

    // ========== 删除配置 ==========

    @Test
    @Order(12)
    @DisplayName("删除 - 删除 Anthropic 配置（第二版预留）")
    void testDeleteAnthropicConfig() {
        assertTrue(configStorage.exists("anthropic"), "删除前应存在");

        boolean deleted = configStorage.delete("anthropic");
        assertTrue(deleted, "删除应成功");
        assertFalse(configStorage.exists("anthropic"), "删除后不应存在");

        System.out.println("🗑️ 已删除 anthropic 配置");
        System.out.println("   剩余配置数: " + configStorage.getAll().size());
        System.out.println();
    }

    // ========== 最终验证 ==========

    @Test
    @Order(13)
    @DisplayName("验证 - 最终配置列表和完整性")
    void testFinalConfigList() {
        List<ModelConfig> all = configStorage.getAll();

        // 删除 anthropic 后剩 4 个
        assertEquals(4, all.size(), "应剩余4个配置");

        System.out.println("=" .repeat(60));
        System.out.println("📋 最终模型配置列表（共 " + all.size() + " 个）");
        System.out.println("=" .repeat(60));

        for (ModelConfig config : all) {
            System.out.println("   📌 " + config.getName() + " (" + config.getProvider() + ")");
            System.out.println("      模型: " + config.getModel());
            System.out.println("      API Key: " + config.getApiKey().substring(0, Math.min(15, config.getApiKey().length())) + "***");
            System.out.println("      Base URL: " + config.getBaseUrl());
            System.out.println("      状态: " + (config.isEnabled() ? "启用" : "禁用"));
            System.out.println("      创建: " + config.getCreatedAt());
            System.out.println("      更新: " + config.getUpdatedAt());
            System.out.println();
        }

        // 验证所有启用的配置
        long enabledCount = all.stream().filter(ModelConfig::isEnabled).count();
        assertEquals(4, enabledCount, "所有配置都应启用");

        System.out.println("✅ 模型配置存储层测试全部通过！");
    }
}