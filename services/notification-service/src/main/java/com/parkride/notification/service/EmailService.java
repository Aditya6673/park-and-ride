package com.parkride.notification.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;

import java.util.Locale;
import java.util.Map;

/**
 * Sends HTML emails using Thymeleaf templates and Spring's {@link JavaMailSender}.
 *
 * <p>Template files are resolved from {@code classpath:/templates/email/*.html}.
 * Each template receives the {@code variables} map as Thymeleaf context variables.
 *
 * <p>Failures are logged but do NOT propagate — email delivery is best-effort.
 * The Kafka offset is acknowledged regardless so the platform doesn't get stuck
 * on transient SMTP errors.
 */
@Slf4j
@Service
public class EmailService {

    @Value("${notification.email.from}")
    private String fromAddress;

    @Value("${notification.email.enabled:true}")
    private boolean emailEnabled;

    private final JavaMailSender mailSender;
    private final TemplateEngine templateEngine;

    public EmailService(JavaMailSender mailSender, TemplateEngine templateEngine) {
        this.mailSender     = mailSender;
        this.templateEngine = templateEngine;
    }

    /**
     * Renders a Thymeleaf template and sends it as an HTML email.
     *
     * @param to           recipient email address
     * @param subject      email subject line
     * @param templateName template file name without extension (e.g. {@code "booking-confirmed"})
     * @param variables    model variables injected into the template
     */
    public void sendHtml(String to, String subject, String templateName,
                         Map<String, Object> variables) {
        if (!emailEnabled) {
            log.info("[EMAIL DISABLED] Would send '{}' to {}", subject, to);
            return;
        }

        if (to == null || to.isBlank()) {
            log.warn("Cannot send email '{}' — recipient address is null/blank", subject);
            return;
        }

        try {
            // Render template
            Context ctx = new Context(Locale.ENGLISH);
            ctx.setVariables(variables);
            String htmlContent = templateEngine.process(templateName, ctx);

            // Build MIME message
            var message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
            helper.setFrom(fromAddress);
            helper.setTo(to);
            helper.setSubject(subject);
            helper.setText(htmlContent, true);

            mailSender.send(message);
            log.info("Email '{}' sent to {}", subject, to);

        } catch (Exception ex) {
            log.error("Failed to send email '{}' to {}: {}", subject, to, ex.getMessage(), ex);
        }
    }
}
