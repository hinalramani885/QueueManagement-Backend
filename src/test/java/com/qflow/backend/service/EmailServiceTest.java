package com.qflow.backend.service;

import jakarta.mail.Address;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.ByteArrayOutputStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Every e-mail shows QFlow as sender name, replies go to the QFlow support address, and templates show it. */
class EmailServiceTest {

    private static final String SUPPORT = "qflowapplication@gmail.com";

    private JavaMailSender mailSender;
    private EmailService emailService;

    @BeforeEach
    void setUp() {
        mailSender = mock(JavaMailSender.class);
        // A real MimeMessage so headers and body can be inspected
        when(mailSender.createMimeMessage()).thenAnswer(inv -> new JavaMailSenderImpl().createMimeMessage());
        emailService = new EmailService(mailSender);
        ReflectionTestUtils.setField(emailService, "supportEmail", SUPPORT);
        ReflectionTestUtils.setField(emailService, "mailUsername", "smtp-account@gmail.com");
    }

    private MimeMessage sentMessage() {
        ArgumentCaptor<MimeMessage> captor = ArgumentCaptor.forClass(MimeMessage.class);
        verify(mailSender).send(captor.capture());
        return captor.getValue();
    }

    private static String body(MimeMessage message) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        message.writeTo(out);
        return out.toString(java.nio.charset.StandardCharsets.UTF_8);
    }

    @Test
    void verificationEmailRepliesToSupportAndShowsIt() throws Exception {
        emailService.sendVerificationOtpEmail("user@gmail.com", "123456");

        MimeMessage message = sentMessage();
        Address[] replyTo = message.getReplyTo();
        assertEquals(SUPPORT, ((InternetAddress) replyTo[0]).getAddress());
        InternetAddress from = (InternetAddress) message.getFrom()[0];
        assertEquals("QFlow", from.getPersonal());
        assertEquals("smtp-account@gmail.com", from.getAddress(), "Gmail only allows the authenticated account as sender");
        String raw = body(message);
        assertTrue(raw.contains(SUPPORT), "template shows the support address");
        assertFalse(raw.contains("{{SUPPORT_EMAIL}}"));
    }

    @Test
    void otpEmailShowsSupportAddress() throws Exception {
        emailService.sendOtpEmail("user@gmail.com", "654321");
        MimeMessage message = sentMessage();
        assertEquals(SUPPORT, ((InternetAddress) message.getReplyTo()[0]).getAddress());
        assertTrue(body(message).contains(SUPPORT));
    }

    @Test
    void senderFallsBackToSupportAddressWhenNoSmtpAccountConfigured() throws Exception {
        ReflectionTestUtils.setField(emailService, "mailUsername", "");
        emailService.sendOtpEmail("user@gmail.com", "111111");
        assertEquals(SUPPORT, ((InternetAddress) sentMessage().getFrom()[0]).getAddress());
    }
}
