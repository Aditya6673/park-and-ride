package com.parkride.notification.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.util.ReflectionTestUtils;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("EmailService — unit tests (no SMTP)")
class EmailServiceTest {

    @Mock JavaMailSender mailSender;
    @Mock TemplateEngine templateEngine;

    @InjectMocks EmailService emailService;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(emailService, "fromAddress",
                "Park & Ride <noreply@parkride.com>");
        ReflectionTestUtils.setField(emailService, "emailEnabled", true);
    }

    @Test
    @DisplayName("sendHtml — renders template and calls JavaMailSender")
    void sendHtml_rendersTemplateAndSends() {
        var mimeMessage = mock(jakarta.mail.internet.MimeMessage.class);
        when(mailSender.createMimeMessage()).thenReturn(mimeMessage);
        when(templateEngine.process(anyString(), any(Context.class)))
                .thenReturn("<html>Test Email</html>");

        assertThatCode(() ->
                emailService.sendHtml("user@example.com", "Test Subject",
                        "booking-confirmed", Map.of("key", "value"))
        ).doesNotThrowAnyException();

        verify(templateEngine).process(eq("booking-confirmed"), any(Context.class));
        verify(mailSender).send(any(jakarta.mail.internet.MimeMessage.class));
    }

    @Test
    @DisplayName("sendHtml — skips send when emailEnabled=false")
    void sendHtml_disabled_skipsAllDelivery() {
        ReflectionTestUtils.setField(emailService, "emailEnabled", false);

        emailService.sendHtml("user@example.com", "Subject", "template", Map.of());

        verifyNoInteractions(mailSender);
        verifyNoInteractions(templateEngine);
    }

    @Test
    @DisplayName("sendHtml — skips send when recipient is null")
    void sendHtml_nullRecipient_skips() {
        emailService.sendHtml(null, "Subject", "template", Map.of());

        verifyNoInteractions(mailSender);
        verifyNoInteractions(templateEngine);
    }

    @Test
    @DisplayName("sendHtml — skips send when recipient is blank")
    void sendHtml_blankRecipient_skips() {
        emailService.sendHtml("   ", "Subject", "template", Map.of());

        verifyNoInteractions(mailSender);
        verifyNoInteractions(templateEngine);
    }

    @Test
    @DisplayName("sendHtml — SMTP exception does not propagate to caller")
    void sendHtml_smtpException_doesNotPropagate() {
        var mimeMessage = mock(jakarta.mail.internet.MimeMessage.class);
        when(mailSender.createMimeMessage()).thenReturn(mimeMessage);
        when(templateEngine.process(anyString(), any(Context.class)))
                .thenReturn("<html>body</html>");
        doThrow(new org.springframework.mail.MailSendException("Connection refused"))
                .when(mailSender).send(any(jakarta.mail.internet.MimeMessage.class));

        // Must NOT throw — email failures are best-effort
        assertThatCode(() ->
                emailService.sendHtml("user@example.com", "Subject", "template", Map.of())
        ).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("sendHtml — injects all variables into Thymeleaf Context")
    void sendHtml_injectsAllVariablesIntoContext() {
        var mimeMessage = mock(jakarta.mail.internet.MimeMessage.class);
        when(mailSender.createMimeMessage()).thenReturn(mimeMessage);

        ArgumentCaptor<Context> contextCaptor = ArgumentCaptor.forClass(Context.class);
        when(templateEngine.process(anyString(), contextCaptor.capture())).thenReturn("<html/>");

        Map<String, Object> vars = Map.of("userName", "Aditya", "amount", "150.00");
        emailService.sendHtml("user@example.com", "Subject", "booking-confirmed", vars);

        Context capturedCtx = contextCaptor.getValue();
        assertThat(capturedCtx.getVariable("userName")).isEqualTo("Aditya");
        assertThat(capturedCtx.getVariable("amount")).isEqualTo("150.00");
    }
}
