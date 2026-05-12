package lyjew.com.lyclaw.util;

/**
 * 字符串工具类，提供常用的文本操作和相似度计算。
 */
public final class TextUtils {

    private TextUtils() {}

    /** 截断文本到指定长度，超出部分用 "..." 替换。 */
    public static String truncate(String text, int maxLen) {
        if (text == null) return "";
        if (text.length() <= maxLen) return text;
        return text.substring(0, maxLen) + "...";
    }

    /** 安全比较两个字符串（处理 null）。 */
    public static boolean equalsIgnoreNull(String a, String b) {
        if (a == null && b == null) return true;
        if (a == null || b == null) return false;
        return a.equals(b);
    }

    /** 计算两个浮点向量的余弦相似度。 */
    public static double cosineSimilarity(float[] a, float[] b) {
        if (a == null || b == null || a.length != b.length) return 0.0;
        double dot = 0.0, normA = 0.0, normB = 0.0;
        for (int i = 0; i < a.length; i++) {
            dot += (double) a[i] * b[i];
            normA += (double) a[i] * a[i];
            normB += (double) b[i] * b[i];
        }
        double denominator = Math.sqrt(normA) * Math.sqrt(normB);
        return denominator < 1e-12 ? 0.0 : dot / denominator;
    }
}
