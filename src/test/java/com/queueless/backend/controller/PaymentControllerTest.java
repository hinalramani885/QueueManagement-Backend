package com.queueless.backend.controller;

import tools.jackson.databind.ObjectMapper;
import com.queueless.backend.config.RateLimitConfig;
import com.queueless.backend.config.TestSecurityConfig;
import com.queueless.backend.dto.OrderResponse;
import com.queueless.backend.dto.TokenResponse;
import com.queueless.backend.enums.Role;
import com.queueless.backend.exception.ResourceNotFoundException;
import com.queueless.backend.model.Payment;
import com.queueless.backend.model.Token;
import com.queueless.backend.service.PaymentService;
import com.razorpay.Order;
import com.razorpay.RazorpayException;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = PaymentController.class,
        properties = "spring.autoconfigure.exclude=org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration")
@Import({RateLimitConfig.class, TestSecurityConfig.class, PaymentControllerTest.MethodSecurityConfig.class})
@AutoConfigureMockMvc
class PaymentControllerTest {

    /** Enables @PreAuthorize-based annotations such as @AdminOnly in this slice test. */
    @org.springframework.boot.test.context.TestConfiguration
    @org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity
    static class MethodSecurityConfig {
    }

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private PaymentService paymentService;

    @Autowired
    private ObjectMapper objectMapper;

    private final String orderId = "order_test123";
    private final String paymentId = "pay_test123";
    private final String email = "test@example.com";
    private final String providerEmail = "provider@example.com";
    private final String tokenType = "1_MONTH";
    private final String adminId = "admin123";
    private final String tokenValue = "ADMIN-abc123";

    // ==================== CREATE ORDER ====================

    @Test
    void createOrder_Success() throws Exception {
        Order mockOrder = mockOrder(orderId, 10000);
        when(paymentService.createOrder(eq(email), eq(Role.ADMIN), eq(tokenType), isNull()))
                .thenReturn(mockOrder);

        mockMvc.perform(post("/api/payment/create-order")
                        .param("email", email)
                        .param("role", Role.ADMIN.name())
                        .param("tokenType", tokenType))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orderId").value(orderId))
                .andExpect(jsonPath("$.amount").value(10000))
                .andExpect(jsonPath("$.currency").value("INR"));
    }

    @Test
    void createOrder_InvalidTokenType() throws Exception {
        when(paymentService.createOrder(eq(email), eq(Role.ADMIN), eq("INVALID"), isNull()))
                .thenThrow(new IllegalArgumentException("Invalid token type"));

        mockMvc.perform(post("/api/payment/create-order")
                        .param("email", email)
                        .param("role", Role.ADMIN.name())
                        .param("tokenType", "INVALID"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void createOrder_RazorpayException() throws Exception {
        when(paymentService.createOrder(eq(email), eq(Role.ADMIN), eq(tokenType), isNull()))
                .thenThrow(new RazorpayException("Razorpay error"));

        mockMvc.perform(post("/api/payment/create-order")
                        .param("email", email)
                        .param("role", Role.ADMIN.name())
                        .param("tokenType", tokenType))
                .andExpect(status().isInternalServerError());
    }

    // ==================== CONFIRM PAYMENT ====================

    private final String signature = "razorpay_signature_value";

    private Payment paidPayment(Role role, String forEmail, String createdByAdminId, int amount) {
        return Payment.builder()
                .razorpayOrderId(orderId)
                .role(role)
                .tokenType(tokenType)
                .createdForEmail(forEmail)
                .createdByAdminId(createdByAdminId)
                .amount(amount)
                .isPaid(true)
                .build();
    }

    @Test
    void confirmPayment_Success() throws Exception {
        when(paymentService.confirmPayment(orderId, paymentId, signature, Role.ADMIN, tokenType, email))
                .thenReturn(paidPayment(Role.ADMIN, email, null, 10000));
        when(paymentService.generateToken(email, tokenType, Role.ADMIN, false, null))
                .thenReturn(Token.builder().tokenValue(tokenValue).build());

        mockMvc.perform(post("/api/payment/confirm")
                        .param("orderId", orderId)
                        .param("paymentId", paymentId)
                        .param("signature", signature)
                        .param("email", email)
                        .param("tokenType", tokenType))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tokenValue").value(tokenValue));
    }

    @Test
    void confirmPayment_MissingSignature() throws Exception {
        mockMvc.perform(post("/api/payment/confirm")
                        .param("orderId", orderId)
                        .param("paymentId", paymentId)
                        .param("email", email)
                        .param("tokenType", tokenType))
                .andExpect(status().isBadRequest());
        verify(paymentService, never()).generateToken(anyString(), anyString(), any(), anyBoolean(), any());
    }

    @Test
    void confirmPayment_InvalidSignature() throws Exception {
        when(paymentService.confirmPayment(orderId, paymentId, signature, Role.ADMIN, tokenType, email))
                .thenThrow(new IllegalArgumentException("Payment verification failed"));

        mockMvc.perform(post("/api/payment/confirm")
                        .param("orderId", orderId)
                        .param("paymentId", paymentId)
                        .param("signature", signature)
                        .param("email", email)
                        .param("tokenType", tokenType))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Payment verification failed"));
        verify(paymentService, never()).generateToken(anyString(), anyString(), any(), anyBoolean(), any());
    }

    @Test
    void confirmPayment_PaymentNotFound() throws Exception {
        when(paymentService.confirmPayment(orderId, paymentId, signature, Role.ADMIN, tokenType, email))
                .thenThrow(new ResourceNotFoundException("Payment not found"));

        mockMvc.perform(post("/api/payment/confirm")
                        .param("orderId", orderId)
                        .param("paymentId", paymentId)
                        .param("signature", signature)
                        .param("email", email)
                        .param("tokenType", tokenType))
                .andExpect(status().isNotFound());
    }

    // ==================== CONFIRM PROVIDER PAYMENT ====================

    @Test
    void confirmProviderPayment_Success() throws Exception {
        when(paymentService.confirmPayment(orderId, paymentId, signature, Role.PROVIDER, tokenType, providerEmail))
                .thenReturn(paidPayment(Role.PROVIDER, providerEmail, adminId, 10000));
        when(paymentService.generateToken(providerEmail, tokenType, Role.PROVIDER, true, adminId))
                .thenReturn(Token.builder().tokenValue("PROVIDER-xyz789").build());

        mockMvc.perform(post("/api/payment/confirm-provider")
                        .param("orderId", orderId)
                        .param("paymentId", paymentId)
                        .param("signature", signature)
                        .param("providerEmail", providerEmail)
                        .param("tokenType", tokenType))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tokenValue").value("PROVIDER-xyz789"));
    }

    @Test
    void confirmProviderPayment_FallsBackToOrderNotesForAdminId() throws Exception {
        when(paymentService.confirmPayment(orderId, paymentId, signature, Role.PROVIDER, tokenType, providerEmail))
                .thenReturn(paidPayment(Role.PROVIDER, providerEmail, null, 10000));
        when(paymentService.getAdminIdFromOrder(orderId)).thenReturn(adminId);
        when(paymentService.generateToken(providerEmail, tokenType, Role.PROVIDER, true, adminId))
                .thenReturn(Token.builder().tokenValue("PROVIDER-xyz789").build());

        mockMvc.perform(post("/api/payment/confirm-provider")
                        .param("orderId", orderId)
                        .param("paymentId", paymentId)
                        .param("signature", signature)
                        .param("providerEmail", providerEmail)
                        .param("tokenType", tokenType))
                .andExpect(status().isOk());
    }

    @Test
    void confirmProviderPayment_AlreadyConfirmed() throws Exception {
        when(paymentService.confirmPayment(orderId, paymentId, signature, Role.PROVIDER, tokenType, providerEmail))
                .thenThrow(new IllegalStateException("Payment has already been confirmed"));

        mockMvc.perform(post("/api/payment/confirm-provider")
                        .param("orderId", orderId)
                        .param("paymentId", paymentId)
                        .param("signature", signature)
                        .param("providerEmail", providerEmail)
                        .param("tokenType", tokenType))
                .andExpect(status().isBadRequest());
        verify(paymentService, never()).generateToken(anyString(), anyString(), any(), anyBoolean(), any());
    }

    // ==================== BULK CONFIRM PROVIDER PAYMENTS ====================

    @Test
    @WithMockUser(username = adminId, roles = "ADMIN")
    void confirmProviderPaymentBulk_Success() throws Exception {
        List<String> emails = List.of("prov1@test.com", "prov2@test.com");
        when(paymentService.confirmPayment(orderId, paymentId, signature, Role.PROVIDER, tokenType, null))
                .thenReturn(paidPayment(Role.PROVIDER, null, adminId, 20000));
        when(paymentService.generateToken(emails.get(0), tokenType, Role.PROVIDER, true, adminId))
                .thenReturn(Token.builder().tokenValue("PROVIDER-1").build());
        when(paymentService.generateToken(emails.get(1), tokenType, Role.PROVIDER, true, adminId))
                .thenReturn(Token.builder().tokenValue("PROVIDER-2").build());

        mockMvc.perform(post("/api/payment/confirm-provider-bulk")
                        .param("orderId", orderId)
                        .param("paymentId", paymentId)
                        .param("signature", signature)
                        .param("tokenType", tokenType)
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(emails)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].tokenValue").value("PROVIDER-1"))
                .andExpect(jsonPath("$[1].tokenValue").value("PROVIDER-2"));
    }

    @Test
    @WithMockUser(username = adminId, roles = "ADMIN")
    void confirmProviderPaymentBulk_AmountDoesNotCoverAllTokens() throws Exception {
        List<String> emails = List.of("prov1@test.com", "prov2@test.com");
        when(paymentService.confirmPayment(orderId, paymentId, signature, Role.PROVIDER, tokenType, null))
                .thenReturn(paidPayment(Role.PROVIDER, null, adminId, 10000)); // paid for one token only

        mockMvc.perform(post("/api/payment/confirm-provider-bulk")
                        .param("orderId", orderId)
                        .param("paymentId", paymentId)
                        .param("signature", signature)
                        .param("tokenType", tokenType)
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(emails)))
                .andExpect(status().isBadRequest());
        verify(paymentService, never()).generateToken(anyString(), anyString(), any(), anyBoolean(), any());
    }

    @Test
    @WithMockUser(username = adminId, roles = "ADMIN")
    void confirmProviderPaymentBulk_OrderOfAnotherAdmin() throws Exception {
        when(paymentService.confirmPayment(orderId, paymentId, signature, Role.PROVIDER, tokenType, null))
                .thenReturn(paidPayment(Role.PROVIDER, null, "someOtherAdmin", 10000));

        mockMvc.perform(post("/api/payment/confirm-provider-bulk")
                        .param("orderId", orderId)
                        .param("paymentId", paymentId)
                        .param("signature", signature)
                        .param("tokenType", tokenType)
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(List.of("prov1@test.com"))))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(username = "user1", roles = "USER")
    void confirmProviderPaymentBulk_NonAdminForbidden() throws Exception {
        mockMvc.perform(post("/api/payment/confirm-provider-bulk")
                        .param("orderId", orderId)
                        .param("paymentId", paymentId)
                        .param("signature", signature)
                        .param("tokenType", tokenType)
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(List.of("prov1@test.com"))))
                .andExpect(status().isForbidden());
        verify(paymentService, never()).confirmPayment(anyString(), anyString(), anyString(), any(), anyString(), any());
    }

    // Helper to create a mock Razorpay Order
    private Order mockOrder(String id, int amount) {
        JSONObject json = new JSONObject();
        json.put("id", id);
        json.put("amount", amount);
        json.put("currency", "INR");
        return new Order(json);
    }
}