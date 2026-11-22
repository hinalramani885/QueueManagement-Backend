package com.queueless.backend.enums;

/** Lifecycle of a Razorpay order stored in the {@code payments} collection. */
public enum PaymentStatus {
    /** Order created, checkout opened, not paid yet. */
    CREATED,
    /** Razorpay reported a failed attempt (the customer may still retry the same order). */
    FAILED,
    /** Signature verified and the token(s) issued. */
    PAID
}
