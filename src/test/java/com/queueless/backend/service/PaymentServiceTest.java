package com.queueless.backend.service;

import com.queueless.backend.dto.PayerDetails;
import com.queueless.backend.enums.PaymentStatus;
import com.queueless.backend.enums.Role;
import com.queueless.backend.model.Payment;
import com.queueless.backend.model.Token;
import com.queueless.backend.repository.PaymentRepository;
import com.queueless.backend.repository.TokenRepository;
import com.razorpay.Order;
import com.razorpay.OrderClient;
import com.razorpay.RazorpayClient;
import com.razorpay.RazorpayException;
import org.json.JSONObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PaymentServiceTest {

    @Mock
    private PaymentRepository paymentRepository;

    @Mock
    private TokenRepository tokenRepository;

    @Mock
    private AuditLogService auditLogService;

    @InjectMocks
    private PaymentService paymentService;

    @BeforeEach
    void setUp() {
        // Inject configuration properties via ReflectionTestUtils
        ReflectionTestUtils.setField(paymentService, "RAZORPAY_KEY", "test_key");
        ReflectionTestUtils.setField(paymentService, "RAZORPAY_SECRET", "test_secret");
    }

    // ================= CREATE ORDER =================

    @Test
    void createOrderForAdminSuccess() throws Exception {
        String email = "admin@test.com";
        Role role = Role.ADMIN;
        String tokenType = "1_MONTH";

        // 1. Setup sub-mocks
        OrderClient mockOrderClient = mock(OrderClient.class);
        Order mockOrder = mock(Order.class);

        // Only stub what the service actually calls: order.get("id")
        when(mockOrder.get("id")).thenReturn("order_test123");

        // The service calls mockOrderClient.create()
        when(mockOrderClient.create(any(JSONObject.class))).thenReturn(mockOrder);

        // 2. Mock construction and inject the sub-mock
        try (MockedConstruction<RazorpayClient> mocked = mockConstruction(RazorpayClient.class,
                (mock, context) -> {
                    mock.orders = mockOrderClient;
                })) {

            // 3. Act
            Order order = paymentService.createOrder(email, role, tokenType, null);

            // 4. Assert
            assertNotNull(order);
            assertEquals("order_test123", order.get("id"));

            // Verify database interaction
            verify(paymentRepository).save(argThat(payment ->
                    payment.getRazorpayOrderId().equals("order_test123") &&
                            payment.getAmount() == 10000 &&
                            payment.getRole() == Role.ADMIN
            ));
        }
    }

    @Test
    void createOrderForProviderSuccess() throws Exception {
        String email = "provider@test.com";
        Role role = Role.PROVIDER;
        String tokenType = "1_YEAR";
        String adminId = "admin123";

        OrderClient mockOrderClient = mock(OrderClient.class);
        Order mockOrder = mock(Order.class);
        when(mockOrder.get("id")).thenReturn("order_provider123");
        when(mockOrderClient.create(any(JSONObject.class))).thenReturn(mockOrder);

        try (MockedConstruction<RazorpayClient> mocked = mockConstruction(RazorpayClient.class,
                (mock, context) -> {
                    mock.orders = mockOrderClient;
                })) {

            Order order = paymentService.createOrder(email, role, tokenType, adminId);

            assertNotNull(order);

            // Verify the specific payload sent to Razorpay
            ArgumentCaptor<JSONObject> captor = ArgumentCaptor.forClass(JSONObject.class);
            verify(mockOrderClient).create(captor.capture());

            JSONObject sentRequest = captor.getValue();
            assertTrue(sentRequest.has("notes"));
            assertEquals(adminId, sentRequest.getJSONObject("notes").getString("adminId"));
        }
    }
    @Test
    void createOrderInvalidTokenType() {
        String email = "admin@test.com";
        Role role = Role.ADMIN;
        String tokenType = "INVALID";

        Exception exception = assertThrows(IllegalArgumentException.class,
                () -> paymentService.createOrder(email, role, tokenType, null));
        assertEquals("Invalid token type", exception.getMessage());
    }

    @Test
    void createOrderRazorpayException() throws Exception {
        OrderClient mockOrderClient = mock(OrderClient.class);
        when(mockOrderClient.create(any(JSONObject.class)))
                .thenThrow(new RazorpayException("Razorpay error"));

        try (MockedConstruction<RazorpayClient> mocked = mockConstruction(RazorpayClient.class,
                (mock, context) -> {
                    mock.orders = mockOrderClient;
                })) {

            assertThrows(RazorpayException.class,
                    () -> paymentService.createOrder("test@test.com", Role.ADMIN, "1_MONTH", null));
        }
    }

    // ================= CONFIRM PAYMENT =================

    /** Signature exactly as Razorpay checkout computes it with the "test_secret" key secret. */
    private static String razorpaySignature(String orderId, String paymentId) throws Exception {
        javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
        mac.init(new javax.crypto.spec.SecretKeySpec("test_secret".getBytes(java.nio.charset.StandardCharsets.UTF_8), "HmacSHA256"));
        return java.util.HexFormat.of().formatHex(
                mac.doFinal((orderId + "|" + paymentId).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }

    private Payment unpaidAdminPayment(String orderId) {
        return Payment.builder()
                .razorpayOrderId(orderId)
                .role(Role.ADMIN)
                .tokenType("1_MONTH")
                .createdForEmail("admin@test.com")
                .amount(10000)
                .isPaid(false)
                .build();
    }

    @Test
    void confirmPaymentSuccess() throws Exception {
        String orderId = "order123";
        String paymentId = "pay123";
        Payment payment = unpaidAdminPayment(orderId);

        when(paymentRepository.findByRazorpayOrderId(orderId)).thenReturn(Optional.of(payment));
        when(paymentRepository.save(any(Payment.class))).thenAnswer(inv -> inv.getArgument(0));

        Payment result = paymentService.confirmPayment(orderId, paymentId,
                razorpaySignature(orderId, paymentId), Role.ADMIN, "1_MONTH", "admin@test.com");

        assertSame(payment, result);
        assertTrue(payment.isPaid());
        assertEquals(paymentId, payment.getRazorpayPaymentId());
        verify(paymentRepository).save(payment);
    }

    @Test
    void confirmPaymentNotFound() {
        String orderId = "order123";
        when(paymentRepository.findByRazorpayOrderId(orderId)).thenReturn(Optional.empty());

        RuntimeException exception = assertThrows(RuntimeException.class,
                () -> paymentService.confirmPayment(orderId, "pay123", "sig", Role.ADMIN, "1_MONTH", null));
        assertEquals("Payment not found", exception.getMessage());
    }

    @Test
    void confirmPaymentRejectsInvalidSignature() {
        String orderId = "order123";
        when(paymentRepository.findByRazorpayOrderId(orderId)).thenReturn(Optional.of(unpaidAdminPayment(orderId)));

        assertThrows(IllegalArgumentException.class,
                () -> paymentService.confirmPayment(orderId, "pay123", "forged-signature", Role.ADMIN, "1_MONTH", null));
        verify(paymentRepository, never()).save(any());
    }

    @Test
    void confirmPaymentRejectsAlreadyPaidOrder() throws Exception {
        String orderId = "order123";
        Payment payment = unpaidAdminPayment(orderId);
        payment.setPaid(true);
        when(paymentRepository.findByRazorpayOrderId(orderId)).thenReturn(Optional.of(payment));

        assertThrows(IllegalStateException.class,
                () -> paymentService.confirmPayment(orderId, "pay123",
                        razorpaySignature(orderId, "pay123"), Role.ADMIN, "1_MONTH", null));
        verify(paymentRepository, never()).save(any());
    }

    @Test
    void confirmPaymentRejectsDifferentPlanOrRole() throws Exception {
        String orderId = "order123";
        when(paymentRepository.findByRazorpayOrderId(orderId)).thenReturn(Optional.of(unpaidAdminPayment(orderId)));
        String signature = razorpaySignature(orderId, "pay123");

        assertThrows(IllegalArgumentException.class,
                () -> paymentService.confirmPayment(orderId, "pay123", signature, Role.ADMIN, "LIFETIME", null));
        assertThrows(IllegalArgumentException.class,
                () -> paymentService.confirmPayment(orderId, "pay123", signature, Role.PROVIDER, "1_MONTH", null));
        verify(paymentRepository, never()).save(any());
    }

    // ================= GENERATE TOKEN =================

    @Test
    void generateTokenForAdminOneMonth() {
        String email = "admin@test.com";
        String tokenType = "1_MONTH";
        Role role = Role.ADMIN;
        boolean isProviderToken = false;
        String adminId = null;

        when(tokenRepository.save(any(Token.class))).thenAnswer(inv -> inv.getArgument(0));

        Token token = paymentService.generateToken(email, tokenType, role, isProviderToken, adminId);

        assertNotNull(token);
        assertTrue(token.getTokenValue().startsWith("ADMIN-"));
        assertEquals(role, token.getRole());
        assertEquals(email, token.getCreatedForEmail());
        assertFalse(token.isUsed());
        assertFalse(token.isProviderToken());
        assertNull(token.getCreatedByAdminId());
        assertNotNull(token.getExpiryDate());
        // Expiry should be about 1 month from now
        LocalDateTime expectedExpiry = LocalDateTime.now().plusMonths(1);
        assertTrue(token.getExpiryDate().isAfter(expectedExpiry.minusMinutes(1)) &&
                token.getExpiryDate().isBefore(expectedExpiry.plusMinutes(1)));
    }

    @Test
    void generateTokenForProviderOneYearWithAdminId() {
        String email = "provider@test.com";
        String tokenType = "1_YEAR";
        Role role = Role.PROVIDER;
        boolean isProviderToken = true;
        String adminId = "admin123";

        when(tokenRepository.save(any(Token.class))).thenAnswer(inv -> inv.getArgument(0));

        Token token = paymentService.generateToken(email, tokenType, role, isProviderToken, adminId);

        assertNotNull(token);
        assertTrue(token.getTokenValue().startsWith("PROVIDER-"));
        assertEquals(role, token.getRole());
        assertEquals(email, token.getCreatedForEmail());
        assertEquals(email, token.getProviderEmail());
        assertTrue(token.isProviderToken());
        assertEquals(adminId, token.getCreatedByAdminId());
        LocalDateTime expectedExpiry = LocalDateTime.now().plusYears(1);
        assertTrue(token.getExpiryDate().isAfter(expectedExpiry.minusMinutes(1)) &&
                token.getExpiryDate().isBefore(expectedExpiry.plusMinutes(1)));
    }

    @Test
    void generateTokenLifetime() {
        String email = "admin@test.com";
        String tokenType = "LIFETIME";
        Role role = Role.ADMIN;
        boolean isProviderToken = false;
        String adminId = null;

        when(tokenRepository.save(any(Token.class))).thenAnswer(inv -> inv.getArgument(0));

        Token token = paymentService.generateToken(email, tokenType, role, isProviderToken, adminId);

        LocalDateTime expectedExpiry = LocalDateTime.now().plusYears(100);
        assertTrue(token.getExpiryDate().isAfter(expectedExpiry.minusMinutes(1)) &&
                token.getExpiryDate().isBefore(expectedExpiry.plusMinutes(1)));
    }

    @Test
    void generateTokenInvalidType() {
        String email = "admin@test.com";
        String tokenType = "INVALID";
        Role role = Role.ADMIN;

        assertThrows(IllegalArgumentException.class,
                () -> paymentService.generateToken(email, tokenType, role, false, null));
    }

    // ================= GET ADMIN ID FROM ORDER =================

    @Test
    void getAdminIdFromOrderSuccess() throws Exception {
        String orderId = "order123";
        OrderClient mockOrderClient = mock(OrderClient.class);
        Order mockOrder = mock(Order.class);

        JSONObject notes = new JSONObject();
        notes.put("adminId", "admin123");
        when(mockOrder.get("notes")).thenReturn(notes);
        when(mockOrderClient.fetch(orderId)).thenReturn(mockOrder);

        try (MockedConstruction<RazorpayClient> mocked = mockConstruction(RazorpayClient.class,
                (mock, context) -> {
                    mock.orders = mockOrderClient;
                })) {

            String result = paymentService.getAdminIdFromOrder(orderId);
            assertEquals("admin123", result);
        }
    }

    @Test
    void getAdminIdFromOrderException() throws Exception {
        String orderId = "order123";
        OrderClient mockOrderClient = mock(OrderClient.class);
        when(mockOrderClient.fetch(orderId)).thenThrow(new RazorpayException("Fetch failed"));

        try (MockedConstruction<RazorpayClient> mocked = mockConstruction(RazorpayClient.class,
                (mock, context) -> {
                    mock.orders = mockOrderClient;
                })) {

            RuntimeException exception = assertThrows(RuntimeException.class,
                    () -> paymentService.getAdminIdFromOrder(orderId));

            // Checking the cause since the service wraps it in a RuntimeException
            assertEquals("Failed to retrieve admin ID from order", exception.getMessage());
        }
    }

    // ================= PAYER DETAILS / STATUS TRACKING =================

    @Test
    void createOrderStoresPayerDetailsAndUpiId() throws Exception {
        OrderClient mockOrderClient = mock(OrderClient.class);
        Order mockOrder = mock(Order.class);
        when(mockOrder.get("id")).thenReturn("order_upi");
        when(mockOrderClient.create(any(JSONObject.class))).thenReturn(mockOrder);

        try (MockedConstruction<RazorpayClient> mocked = mockConstruction(RazorpayClient.class,
                (mock, context) -> mock.orders = mockOrderClient)) {
            paymentService.createOrder("admin@test.com", Role.ADMIN, "1_YEAR", null,
                    new PayerDetails("Asha", "9876543210", "asha@okaxis", "upi"));

            verify(paymentRepository).save(argThat(p ->
                    "Asha".equals(p.getPayerName())
                            && "9876543210".equals(p.getPayerPhone())
                            && "asha@okaxis".equals(p.getUpiId())
                            && "upi".equals(p.getPreferredMethod())
                            && p.getStatus() == PaymentStatus.CREATED
                            && "INR".equals(p.getCurrency())));
            ArgumentCaptor<JSONObject> captor = ArgumentCaptor.forClass(JSONObject.class);
            verify(mockOrderClient).create(captor.capture());
            assertEquals("asha@okaxis", captor.getValue().getJSONObject("notes").getString("upiId"));
        }
    }

    @Test
    void recordFailureMarksUnpaidOrderFailed() {
        Payment payment = Payment.builder().razorpayOrderId("order_f").isPaid(false).build();
        when(paymentRepository.findByRazorpayOrderId("order_f")).thenReturn(Optional.of(payment));

        paymentService.recordFailure("order_f", "pay_f", "Payment declined by bank");

        assertEquals(PaymentStatus.FAILED, payment.getStatus());
        assertEquals("Payment declined by bank", payment.getFailureReason());
        assertNotNull(payment.getFailedAt());
        verify(paymentRepository).save(payment);
    }

    @Test
    void recordFailureIgnoresPaidOrder() {
        Payment payment = Payment.builder().razorpayOrderId("order_p").isPaid(true).status(PaymentStatus.PAID).build();
        when(paymentRepository.findByRazorpayOrderId("order_p")).thenReturn(Optional.of(payment));

        paymentService.recordFailure("order_p", "pay_p", "late failure");

        assertEquals(PaymentStatus.PAID, payment.getStatus());
        verify(paymentRepository, never()).save(any());
    }

    @Test
    void recordIssuedTokensAppendsTokenValues() {
        Payment payment = Payment.builder().razorpayOrderId("order_t").build();
        paymentService.recordIssuedTokens(payment, List.of("ADMIN-1", "ADMIN-2"));
        assertEquals(List.of("ADMIN-1", "ADMIN-2"), payment.getIssuedTokens());
        verify(paymentRepository).save(payment);
    }

    @Test
    void applyRazorpayPaymentDetailsCopiesMethodAndVpa() {
        Payment payment = new Payment();
        JSONObject json = new JSONObject()
                .put("method", "upi").put("vpa", "asha@okaxis")
                .put("bank", JSONObject.NULL).put("email", "a@test.com").put("contact", "+919876543210");
        PaymentService.applyRazorpayPaymentDetails(payment, json);
        assertEquals("upi", payment.getMethod());
        assertEquals("asha@okaxis", payment.getVpa());
        assertNull(payment.getBank());
        assertEquals("+919876543210", payment.getRazorpayContact());
    }

    @Test
    void confirmPaymentSetsPaidStatusAndTimestamp() throws Exception {
        Payment payment = Payment.builder().razorpayOrderId("order_c").role(Role.ADMIN).tokenType("1_MONTH")
                .createdForEmail("a@test.com").amount(10000).isPaid(false).build();
        when(paymentRepository.findByRazorpayOrderId("order_c")).thenReturn(Optional.of(payment));
        javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
        mac.init(new javax.crypto.spec.SecretKeySpec("test_secret".getBytes(), "HmacSHA256"));
        String sig = java.util.HexFormat.of().formatHex(mac.doFinal("order_c|pay_c".getBytes()));

        Payment result = paymentService.confirmPayment("order_c", "pay_c", sig, Role.ADMIN, "1_MONTH", "a@test.com");

        assertEquals(PaymentStatus.PAID, result.getStatus());
        assertNotNull(result.getPaidAt());
        assertTrue(result.isPaid());
    }

    @Test
    void payerDetailsValidation() {
        PayerDetails d = PayerDetails.of(" Asha ", "98765-43210", "Asha.P@OKSBI", "UPI");
        assertEquals(new PayerDetails("Asha", "9876543210", "asha.p@oksbi", "upi"), d);
        assertEquals(PayerDetails.EMPTY, PayerDetails.of(null, " ", "", null));
        assertThrows(IllegalArgumentException.class, () -> PayerDetails.of(null, null, "no-at-sign", null));
        assertThrows(IllegalArgumentException.class, () -> PayerDetails.of(null, null, "a@1bank", null));
        assertThrows(IllegalArgumentException.class, () -> PayerDetails.of(null, "12345", null, null));
        assertEquals(new PayerDetails(null, null, null, "upi"), PayerDetails.of(null, null, null, "upi"));
        assertThrows(IllegalArgumentException.class, () -> PayerDetails.of(null, null, null, "bitcoin"));
    }
}
