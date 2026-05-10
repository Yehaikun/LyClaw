package lyjew.com.lyclaw.interceptor.impl;

import lyjew.com.lyclaw.context.ChatContext;
import lyjew.com.lyclaw.dto.ChatResult;
import lyjew.com.lyclaw.interceptor.Interceptor;
import lyjew.com.lyclaw.model.Message;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Sensitive data masking interceptor -- masks PII in user input and model output.
 *
 * <p>Supported masking rules:
 * <ul>
 *   <li>Phone numbers: 13812345678 -> 138****5678</li>
 *   <li>ID card numbers: 110101199001011234 -> 110101********1234</li>
 *   <li>Email addresses: user@example.com -> u***@example.com</li>
 *   <li>API keys / secrets: sk-xxxx... -> sk-****</li>
 * </ul></p>
 *
 * @since 1.0
 * @author LyClaw Team
 */
@Slf4j
@Component
public class SensitiveDataInterceptor implements Interceptor {

    /** Phone: 11 digits starting with 1 */
    private static final Pattern PHONE_PATTERN = Pattern.compile("(1[3-9]\\d)\\d{4}(\\d{4})");

    /** ID card: 18 digits, last may be X */
    private static final Pattern ID_CARD_PATTERN = Pattern.compile("(\\d{6})\\d{8}(\\d{3}[\\dXx])");

    /** Email */
    private static final Pattern EMAIL_PATTERN = Pattern.compile("(\\w)\\w*(@\\w+\\.\\w+)");

    /** API key patterns: sk-..., api-key-... */
    private static final Pattern API_KEY_PATTERN = Pattern.compile("(sk-[a-zA-Z0-9]{4})[a-zA-Z0-9]{20,}([a-zA-Z0-9]{4})");

    @Override
    public boolean preHandle(ChatContext context) {
        List<Message> messages = context.getMessages();
        if (messages == null) return true;

        for (Message msg : messages) {
            if (msg.getContent() != null) {
                String original = msg.getContent();
                String masked = mask(original);
                if (!masked.equals(original)) {
                    msg.setContent(masked);
                    log.debug("Masked sensitive data in message [{}]", msg.getRole());
                }
            }
        }
        return true;
    }

    @Override
    public void postHandle(ChatContext context, ChatResult result) {
        if (result != null && result.getContent() != null) {
            String original = result.getContent();
            String masked = mask(original);
            if (!masked.equals(original)) {
                result.setContent(masked);
                log.debug("Masked sensitive data in response");
            }
        }
    }

    @Override
    public int getOrder() {
        return 30;
    }

    /**
     * Apply all masking rules to the given text.
     */
    private String mask(String text) {
        if (text == null || text.isEmpty()) return text;

        text = PHONE_PATTERN.matcher(text).replaceAll("$1****$2");
        text = ID_CARD_PATTERN.matcher(text).replaceAll("$1********$2");
        text = EMAIL_PATTERN.matcher(text).replaceAll("$1***$2");
        text = API_KEY_PATTERN.matcher(text).replaceAll("$1****$2");

        return text;
    }
}
