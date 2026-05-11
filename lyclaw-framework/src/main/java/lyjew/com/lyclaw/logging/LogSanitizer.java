package lyjew.com.lyclaw.logging;

import java.util.regex.Pattern;

/**
 * 日志脱敏工具类，对敏感信息（电话、邮箱、API Key、Token、JWT）进行掩码处理。
 *
 * <p>使用正则匹配敏感字段并替换为 ****，保留部分前缀/后缀以便日志可追溯。
 * 纯静态工具类，私有构造器防止实例化。</p>
 */
public final class LogSanitizer {

    /** 中国大陆手机号正则 */
    private static final Pattern PHONE_PATTERN =
            Pattern.compile("1[3-9]\\d{9}");
    /** 邮箱地址正则 */
    private static final Pattern EMAIL_PATTERN =
            Pattern.compile("[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}");
    /** API Key 头部正则，匹配 key=value 或 key:value 格式 */
    private static final Pattern API_KEY_HEADER_PATTERN =
            Pattern.compile("(api[_-]?key|apikey|secret[_-]?key|access[_-]?key|token)[=:]([^&\\s]+)",
                    Pattern.CASE_INSENSITIVE);
    /** Bearer Token 正则 */
    private static final Pattern BEARER_TOKEN_PATTERN =
            Pattern.compile("(Bearer\\s+)[A-Za-z0-9._\\-]+");
    /** JWT Token 正则（三段式 base64 编码） */
    private static final Pattern JWT_PATTERN =
            Pattern.compile("(eyJ[a-zA-Z0-9_-]*)\\.(eyJ[a-zA-Z0-9_-]*)\\.[a-zA-Z0-9_-]+");
    /** 掩码字符串 */
    private static final String MASK = "****";

    private LogSanitizer() { /* 工具类，禁止实例化 */ }

    /**
     * 对输入字符串进行脱敏处理。
     *
     * <p>依次对手机号（保留前3后4）、邮箱（保留首尾字符）、API Key（替换值部分）、
     * Bearer Token、JWT（保留 header.payload）进行掩码。</p>
     *
     * @param input 原始字符串
     * @return 脱敏后的字符串，null 入参返回 null
     */
    public static String sanitize(String input) {
        if (input == null) return null;
        String result = input;
        // 手机号：保留前3位和后4位
        result = PHONE_PATTERN.matcher(result).replaceAll(maskPhone -> {
            String phone = maskPhone.group();
            return phone.substring(0, 3) + MASK + phone.substring(phone.length() - 4);
        });
        // 邮箱：保留首尾字符，中间掩码
        result = EMAIL_PATTERN.matcher(result).replaceAll(maskEmail -> {
            String email = maskEmail.group();
            int atIndex = email.indexOf('@');
            String localPart = email.substring(0, atIndex);
            String domain = email.substring(atIndex);
            if (localPart.length() <= 2) return MASK + domain; // 本地部分太短则全掩
            return localPart.charAt(0) + MASK + localPart.charAt(localPart.length() - 1) + domain;
        });
        // API Key：保留 key 名，掩码 value
        result = API_KEY_HEADER_PATTERN.matcher(result).replaceAll("$1=${MASK}");
        // Bearer Token：保留前缀，掩码 token 值
        result = BEARER_TOKEN_PATTERN.matcher(result).replaceAll("$1${MASK}");
        // JWT：保留 header.payload，掩码 signature
        result = JWT_PATTERN.matcher(result).replaceAll("$1.$2.${MASK}");
        return result;
    }

    /**
     * 安全脱敏，空字符串时返回空串而非 null。
     *
     * @param input 原始字符串
     * @return 脱敏后的字符串，null/空串入参返回 ""
     */
    public static String sanitizeOrEmpty(String input) {
        if (input == null || input.isEmpty()) return "";
        return sanitize(input);
    }
}
