package com.queueless.backend.model;

import com.queueless.backend.enums.PaymentStatus;
import com.queueless.backend.enums.Role;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * One Razorpay order for an ADMIN or PROVIDER registration token, with everything known about the
 * buyer and the payment.
 *
 * <ul>
 *   <li><b>Order</b>: Razorpay order ID, plan, amount, currency, role, and the email the token is for.</li>
 *   <li><b>Payer</b> (entered on our pricing page): name, phone, UPI ID and preferred method.</li>
 *   <li><b>Payment</b> (reported by Razorpay after a successful payment): payment ID, method actually
 *       used, the UPI ID (VPA) actually charged, bank, wallet, and the payer email/contact Razorpay saw.</li>
 *   <li><b>Lifecycle</b>: status, creation/payment/failure timestamps, failure reason, and the token
 *       values issued.</li>
 * </ul>
 */
@Document("payments")
@CompoundIndex(name = "status_createdAt", def = "{'status': 1, 'createdAt': -1}")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Payment {
    @Id
    private String id;

    // ---- Order ----
    @Indexed(unique = true)
    private String razorpayOrderId;
    private int amount;               // in paise
    @Builder.Default
    private String currency = "INR";
    private Role role;
    private String tokenType;         // 1_MONTH, 1_YEAR or LIFETIME: the plan that was actually paid for
    @Indexed
    private String createdForEmail;
    @Indexed
    private String createdByAdminId;  // For provider tokens: the admin the provider will belong to
    private String placeId;           // For self-purchased provider tokens: the place the provider works at

    // ---- Payer details entered on the pricing page ----
    private String payerName;
    private String payerPhone;
    private String upiId;             // UPI ID typed by the payer, e.g. name@okaxis
    private String preferredMethod;   // upi, card, netbanking or wallet

    // ---- Details reported by Razorpay for the successful payment ----
    @Indexed(sparse = true)
    private String razorpayPaymentId;
    private String method;            // upi, card, netbanking, wallet, ...
    private String vpa;               // UPI ID actually charged (UPI payments)
    private String bank;              // netbanking bank code
    private String wallet;            // wallet name
    private String razorpayEmail;     // email entered in the Razorpay checkout
    private String razorpayContact;   // phone entered in the Razorpay checkout

    // ---- Lifecycle ----
    private boolean isPaid;
    @Builder.Default
    private PaymentStatus status = PaymentStatus.CREATED;
    private String failureReason;
    private LocalDateTime createdAt;
    private LocalDateTime paidAt;
    private LocalDateTime failedAt;
    @Builder.Default
    private List<String> issuedTokens = new ArrayList<>();
}
