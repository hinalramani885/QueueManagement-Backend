package com.queueless.backend.service;

import com.queueless.backend.dto.PayerDetails;
import com.queueless.backend.enums.PaymentStatus;
import com.queueless.backend.exception.ResourceNotFoundException;
import com.queueless.backend.model.Payment;
import com.queueless.backend.model.Place;
import com.queueless.backend.repository.PlaceRepository;
import com.queueless.backend.enums.Role;
import com.queueless.backend.model.Token;
import com.queueless.backend.repository.PaymentRepository;
import com.queueless.backend.repository.TokenRepository;
import com.razorpay.Order;
import com.razorpay.RazorpayClient;
import com.razorpay.RazorpayException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.json.JSONObject;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class PaymentService {

    private final PaymentRepository paymentRepository;
    private final TokenRepository tokenRepository;
    private final AuditLogService auditLogService;
    private final PlaceRepository placeRepository;

    @Value("${razorpay.key}")
    private String RAZORPAY_KEY;

    @Value("${razorpay.secret}")
    private String RAZORPAY_SECRET;

    public Order createOrder(String email, Role role, String tokenType, String adminId) throws Exception {
        return createOrder(email, role, tokenType, adminId, PayerDetails.EMPTY);
    }

    /** Creates a Razorpay order and stores it together with the payer details entered on the pricing page. */
    public Order createOrder(String email, Role role, String tokenType, String adminId, PayerDetails payer) throws Exception {
        return createOrder(email, role, tokenType, adminId, null, payer);
    }

    /**
     * Creates a Razorpay order. For a PROVIDER token bought by the provider themselves ("Be a Provider"),
     * {@code placeId} is the place they work at: the token is linked to that place and its admin, so the
     * admin ID never comes from the (unauthenticated) client in that flow.
     */
    public Order createOrder(String email, Role role, String tokenType, String adminId, String placeId,
                             PayerDetails payer) throws Exception {
        PayerDetails details = payer != null ? payer : PayerDetails.EMPTY;
        String orderPlaceId = null;
        if (role == Role.PROVIDER) {
            if (placeId != null && !placeId.isBlank()) {
                Place place = placeRepository.findById(placeId.trim())
                        .orElseThrow(() -> new ResourceNotFoundException("Place not found"));
                if (place.getAdminId() == null) {
                    throw new IllegalArgumentException("This place has no admin yet");
                }
                if (adminId != null && !adminId.isBlank() && !adminId.equals(place.getAdminId())) {
                    throw new IllegalArgumentException("This place belongs to a different admin");
                }
                adminId = place.getAdminId();
                orderPlaceId = place.getId();
            } else if (adminId == null || adminId.isBlank()) {
                throw new IllegalArgumentException("Choose the place you work at");
            }
        }
        log.info("Initiating order creation | email={}, role={}, tokenType={}", email, role, tokenType);

        RazorpayClient razorpayClient = new RazorpayClient(RAZORPAY_KEY, RAZORPAY_SECRET);

        int amount;
        try {
            if (role == Role.ADMIN) {
                switch (tokenType) {
                    case "1_MONTH":
                        amount = 10000;
                        break;
                    case "1_YEAR":
                        amount = 50000;
                        break;
                    case "LIFETIME":
                        amount = 100000;
                        break;
                    default:
                        log.error("Invalid token type provided for ADMIN | tokenType={}", tokenType);
                        throw new IllegalArgumentException("Invalid token type");
                }
            } else if (role == Role.PROVIDER) {
                switch (tokenType) {
                    case "1_MONTH":
                        amount = 10000;
                        break;
                    case "1_YEAR":
                        amount = 50000;
                        break;
                    case "LIFETIME":
                        amount = 100000;
                        break;
                    default:
                        log.error("Invalid token type provided for PROVIDER | tokenType={}", tokenType);
                        throw new IllegalArgumentException("Invalid token type");
                }
            } else {
                log.warn("Unknown role encountered | role={} | assigning default amount", role);
                amount = 20000;
            }

            JSONObject orderRequest = new JSONObject();
            orderRequest.put("amount", amount);
            orderRequest.put("currency", "INR");
            orderRequest.put("receipt", "receipt_" + System.currentTimeMillis());

            // Notes are visible in the Razorpay dashboard; the admin ID is also read back for provider tokens
            JSONObject notes = new JSONObject();
            if (role == Role.PROVIDER && adminId != null) {
                notes.put("adminId", adminId);
            }
            if (orderPlaceId != null) notes.put("placeId", orderPlaceId);
            if (details.name() != null) notes.put("payerName", details.name());
            if (details.upiId() != null) notes.put("upiId", details.upiId());
            if (notes.length() > 0) {
                orderRequest.put("notes", notes);
            }
            log.debug("Sending request to Razorpay | payload={}", orderRequest);

            Order order = razorpayClient.orders.create(orderRequest);

            Payment payment = Payment.builder()
                    .razorpayOrderId(order.get("id"))
                    .amount(amount)
                    .createdForEmail(email)
                    .role(role)
                    .tokenType(tokenType)
                    .currency("INR")
                    .isPaid(false)
                    .status(PaymentStatus.CREATED)
                    .createdAt(LocalDateTime.now())
                    .createdByAdminId(role == Role.PROVIDER ? adminId : null)
                    .placeId(orderPlaceId)
                    .payerName(details.name())
                    .payerPhone(details.phone())
                    .upiId(details.upiId())
                    .preferredMethod(details.preferredMethod())
                    .build();

            paymentRepository.save(payment);

            log.info("Order created successfully | orderId={}, email={}, role={}, amount={}", order.get("id"), email, role, amount);
            return order;

        } catch (Exception e) {
            log.error("Failed to create order | email={}, role={}, tokenType={}, error={}", email, role, tokenType, e.getMessage(), e);
            throw e;
        }
    }

    /**
     * Confirms a Razorpay payment. The checkout signature is verified with the key secret, the order
     * must not have been redeemed already, and it must match the role / plan / email that was paid for.
     * Without these checks anyone could mint ADMIN or PROVIDER registration tokens without paying.
     *
     * @return the confirmed payment (callers use it to generate exactly one token for the paid plan)
     */
    public Payment confirmPayment(String orderId, String paymentId, String signature,
                                  Role expectedRole, String tokenType, String email) {
        log.info("Confirming payment | orderId={}, paymentId={}", orderId, paymentId);

        Payment payment = paymentRepository.findByRazorpayOrderId(orderId)
                .orElseThrow(() -> {
                    log.error("Payment not found for orderId={}", orderId);
                    return new ResourceNotFoundException("Payment not found");
                });

        if (payment.isPaid()) {
            log.warn("Rejecting re-confirmation of already paid order | orderId={}", orderId);
            throw new IllegalStateException("Payment has already been confirmed");
        }
        if (!isValidSignature(orderId, paymentId, signature)) {
            log.warn("Invalid Razorpay signature | orderId={}, paymentId={}", orderId, paymentId);
            throw new IllegalArgumentException("Payment verification failed");
        }
        if (expectedRole != null && payment.getRole() != expectedRole) {
            throw new IllegalArgumentException("Payment was not made for this role");
        }
        if (payment.getTokenType() != null && !payment.getTokenType().equals(tokenType)) {
            throw new IllegalArgumentException("Token type does not match the purchased plan");
        }
        if (email != null && payment.getCreatedForEmail() != null
                && !payment.getCreatedForEmail().equalsIgnoreCase(email)) {
            throw new IllegalArgumentException("Email does not match the purchased order");
        }

        payment.setRazorpayPaymentId(paymentId);
        payment.setPaid(true);
        payment.setStatus(PaymentStatus.PAID);
        payment.setPaidAt(LocalDateTime.now());
        payment.setFailureReason(null);
        applyRazorpayPaymentDetails(payment, fetchRazorpayPayment(paymentId));
        paymentRepository.save(payment);

        log.info("Payment confirmed successfully | orderId={}, paymentId={}, email={}", orderId, paymentId, payment.getCreatedForEmail());

        // Audit log for payment completion
        Map<String, Object> details = new HashMap<>();
        details.put("orderId", orderId);
        details.put("paymentId", paymentId);
        details.put("amount", payment.getAmount());
        details.put("email", payment.getCreatedForEmail());
        details.put("role", payment.getRole() != null ? payment.getRole().name() : "UNKNOWN");
        if (payment.getMethod() != null) details.put("method", payment.getMethod());

        auditLogService.logEvent("PAYMENT_COMPLETED",
                "Payment completed for " + (payment.getRole() != null ? payment.getRole() : "UNKNOWN"),
                details);
        return payment;
    }

    /**
     * Records a failed checkout attempt reported by the Razorpay checkout ("payment.failed" event).
     * Paid orders are never changed, and the customer can still retry the same order afterwards.
     */
    public void recordFailure(String orderId, String paymentId, String reason) {
        Payment payment = paymentRepository.findByRazorpayOrderId(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Payment not found"));
        if (payment.isPaid()) {
            log.info("Ignoring failure report for already paid order | orderId={}", orderId);
            return;
        }
        String cleanReason = reason == null || reason.isBlank() ? "Payment failed" : reason.trim();
        payment.setStatus(PaymentStatus.FAILED);
        payment.setFailedAt(LocalDateTime.now());
        payment.setFailureReason(cleanReason.length() > 300 ? cleanReason.substring(0, 300) : cleanReason);
        paymentRepository.save(payment);
        log.info("Payment attempt failed | orderId={}, paymentId={}, reason={}", orderId, paymentId, payment.getFailureReason());
    }

    /** Stores the token values issued for a confirmed payment. */
    public void recordIssuedTokens(Payment payment, List<String> tokenValues) {
        if (payment.getIssuedTokens() == null) {
            payment.setIssuedTokens(new java.util.ArrayList<>());
        }
        payment.getIssuedTokens().addAll(tokenValues);
        paymentRepository.save(payment);
    }

    /**
     * Fetches the payment entity from Razorpay (method, vpa, bank, ...). Best effort: returns null when
     * Razorpay is not configured or unreachable, because the verified signature already proves the payment.
     */
    JSONObject fetchRazorpayPayment(String paymentId) {
        if (RAZORPAY_KEY == null || !RAZORPAY_KEY.startsWith("rzp_") || RAZORPAY_SECRET == null || RAZORPAY_SECRET.isBlank()) {
            return null;
        }
        try {
            RazorpayClient client = new RazorpayClient(RAZORPAY_KEY, RAZORPAY_SECRET);
            return client.payments.fetch(paymentId).toJson();
        } catch (Exception e) {
            log.warn("Could not fetch payment details from Razorpay | paymentId={}, error={}", paymentId, e.getMessage());
            return null;
        }
    }

    static void applyRazorpayPaymentDetails(Payment payment, JSONObject details) {
        if (details == null) {
            return;
        }
        payment.setMethod(details.optString("method", null));
        payment.setVpa(details.optString("vpa", null));
        payment.setBank(details.optString("bank", null));
        payment.setWallet(details.optString("wallet", null));
        payment.setRazorpayEmail(details.optString("email", null));
        payment.setRazorpayContact(details.optString("contact", null));
    }

    /** Price in paise of a single ADMIN/PROVIDER token for the given plan (same prices used by createOrder). */
    public static int priceFor(String tokenType) {
        return switch (tokenType) {
            case "1_MONTH" -> 10000;
            case "1_YEAR" -> 50000;
            case "LIFETIME" -> 100000;
            default -> throw new IllegalArgumentException("Invalid token type");
        };
    }

    /** Razorpay checkout signature = hex(HMAC_SHA256(orderId + "|" + paymentId, keySecret)). */
    boolean isValidSignature(String orderId, String paymentId, String signature) {
        if (signature == null || signature.isBlank() || RAZORPAY_SECRET == null || RAZORPAY_SECRET.isBlank()) {
            return false;
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(RAZORPAY_SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] digest = mac.doFinal((orderId + "|" + paymentId).getBytes(StandardCharsets.UTF_8));
            String expected = HexFormat.of().formatHex(digest);
            return MessageDigest.isEqual(
                    expected.getBytes(StandardCharsets.UTF_8),
                    signature.trim().toLowerCase().getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            log.error("Unable to verify Razorpay signature", e);
            return false;
        }
    }

    public Token generateToken(String email, String tokenType, Role role, boolean isProviderToken, String adminId) {
        return generateToken(email, tokenType, role, isProviderToken, adminId, null);
    }

    /** Generates a registration token; {@code placeId} is set for self-purchased provider tokens. */
    public Token generateToken(String email, String tokenType, Role role, boolean isProviderToken, String adminId,
                               String placeId) {
        log.info("Generating token | email={}, role={}, tokenType={}, isProviderToken={}, adminId={}", email, role, tokenType, isProviderToken, adminId);

        LocalDateTime expiry;
        try {
            switch (tokenType) {
                case "1_MONTH":
                    expiry = LocalDateTime.now().plus(1, ChronoUnit.MONTHS);
                    break;
                case "1_YEAR":
                    expiry = LocalDateTime.now().plus(1, ChronoUnit.YEARS);
                    break;
                case "LIFETIME":
                    expiry = LocalDateTime.now().plus(100, ChronoUnit.YEARS);
                    break;
                default:
                    log.error("Invalid token type for token generation | tokenType={}", tokenType);
                    throw new IllegalArgumentException("Invalid token type");
            }

            String tokenValue = (role == Role.ADMIN ? "ADMIN-" : "PROVIDER-") +
                    UUID.randomUUID().toString().replace("-", "").substring(0, 12);

            Token token = Token.builder()
                    .tokenValue(tokenValue)
                    .role(role)
                    .createdForEmail(email)
                    .providerEmail(isProviderToken ? email : null)
                    .expiryDate(expiry)
                    .isUsed(false)
                    .isProviderToken(isProviderToken)
                    .createdByAdminId(adminId)
                    .createdAt(LocalDateTime.now())
                    .placeId(role == Role.PROVIDER ? placeId : null)
                    .build();

            if (role == Role.PROVIDER && adminId != null) {
                token.setCreatedByAdminId(adminId);
            } else {
                token.setCreatedByAdminId(null);
            }

            tokenRepository.save(token);

            log.info("Token generated successfully | tokenValue={}, role={}, expiry={}, adminId={}", tokenValue, role, expiry, adminId);

            return token;

        } catch (Exception e) {
            log.error("Failed to generate token | email={}, role={}, tokenType={}, error={}", email, role, tokenType, e.getMessage(), e);
            throw e;
        }
    }

    public String getAdminIdFromOrder(String orderId) throws RazorpayException {
        RazorpayClient razorpayClient = new RazorpayClient(RAZORPAY_KEY, RAZORPAY_SECRET);
        try {
            Order order = razorpayClient.orders.fetch(orderId);
            JSONObject notes = order.get("notes");
            return notes.getString("adminId");
        } catch (Exception e) {
            log.error("Failed to fetch order details for orderId={}", orderId, e);
            throw new RuntimeException("Failed to retrieve admin ID from order", e);
        }
    }
}