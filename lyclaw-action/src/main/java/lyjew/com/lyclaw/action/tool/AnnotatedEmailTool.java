package lyjew.com.lyclaw.action.tool;

import lyjew.com.lyclaw.annotation.tool.Param;
import lyjew.com.lyclaw.annotation.tool.Tool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

/**
 * 邮件发送工具，支持向指定收件人发送文本邮件。
 *
 * <p>使用 Spring Boot 的 JavaMailSender，通过配置的 SMTP 服务器发送邮件。
 * 服务器地址、端口、凭据等信息由 {@code spring.mail.*} 配置项提供。</p>
 */
@Tool(name = "send_email",
      description = "发送电子邮件到指定地址，支持自定义主题和正文",
      readonly = false,
      group = "builtin")
public class AnnotatedEmailTool {

    private static final Logger log = LoggerFactory.getLogger(AnnotatedEmailTool.class);

    @Autowired
    private JavaMailSender mailSender;

    /**
     * 发送一封文本邮件。
     *
     * @param to      收件人邮箱地址
     * @param subject 邮件主题
     * @param content 邮件正文内容
     * @return 发送结果描述
     */
    public String sendEmail(
            @Param(name = "to", description = "收件人邮箱地址，如 user@example.com", required = true)
            String to,
            @Param(name = "subject", description = "邮件主题", required = true)
            String subject,
            @Param(name = "content", description = "邮件正文", required = true)
            String content) {
        try {
            SimpleMailMessage msg = new SimpleMailMessage();
            msg.setTo(to);
            msg.setSubject(subject);
            msg.setText(content);
            mailSender.send(msg);
            log.info("邮件发送成功 -> {}", to);
            return "邮件已成功发送至 " + to;
        } catch (MailException e) {
            log.error("邮件发送失败 -> {}: {}", to, e.getMessage());
            return "邮件发送失败: " + e.getMessage();
        }
    }
}
