package lyjew.com.lyclaw.common;

/** Shared string utilities previously duplicated across multiple reflection classes. */
public final class StringUtils {

    private StringUtils() {}

    /** Truncate text to maxLen characters, appending "..." if truncated. */
    public static String truncate(String text, int maxLen) {
        if (text == null) return "";
        return text.length() <= maxLen ? text : text.substring(0, maxLen) + "...";
    }
}
