package lyjew.com.lyclaw.logging;

import java.util.regex.Pattern;

public final class LogSanitizer {

    private static final Pattern PHONE_PATTERN =
            Pattern.compile("1[3-9]\\d{9}");

    private static final Pattern EMAIL_PATTERN =
            Pattern.compile("[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}");

    private static final Pattern API_KEY_HEADER_PATTERN =
            Pattern.compile("(api[_-]?key|apikey|secret[_-]?key|access[_-]?key|token)[=:]([^&\\s]+)",
                    Pattern.CASE_INSENSITIVE);

    private static final Pattern BEARER_TOKEN_PATTERN =
            Pattern.compile("(Bearer\\s+)[A-Za-z0-9._\\-]+");

    private static final Pattern JWT_PATTERN =
            Pattern.compile("(eyJ[a-zA-Z0-9_-]*)\\.(eyJ[a-zA-Z0-9_-]*)\\.[a-zA-Z0-9_-]+");

    private static final String MASK = "****";

    private LogSanitizer() {
    }

    public static String sanitize(String input) {
        if (input == null) {
            return null;
        }
        String result = input;
        result = PHONE_PATTERN.matcher(result).replaceAll(maskPhone -> {
            String phone = maskPhone.group();
            return phone.substring(0, 3) + MASK + phone.substring(phone.length() - 4);
        });
        result = EMAIL_PATTERN.matcher(result).replaceAll(maskEmail -> {
            String email = maskEmail.group();
            int atIndex = email.indexOf('@');
            String localPart = email.substring(0, atIndex);
            String domain = email.substring(atIndex);
            if (localPart.length() <= 2) {
                return MASK + domain;
            }
            return localPart.charAt(0) + MASK + localPart.charAt(localPart.length() - 1) + domain;
        });
        result = API_KEY_HEADER_PATTERN.matcher(result).replaceAll("$1=${MASK}");
        result = BEARER_TOKEN_PATTERN.matcher(result).replaceAll("$1${MASK}");
        result = JWT_PATTERN.matcher(result).replaceAll("$1.$2.${MASK}");
        return result;
    }

    public static String sanitizeOrEmpty(String input) {
        if (input == null || input.isEmpty()) {
            return "";
        }
        return sanitize(input);
    }
}
