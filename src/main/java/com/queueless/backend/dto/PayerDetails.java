package com.queueless.backend.dto;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Details the buyer enters on the pricing page before the Razorpay checkout opens.
 * All fields are optional; when present they are validated and normalised.
 *
 * @param name            payer's full name
 * @param phone           payer's mobile number (digits, optional leading +)
 * @param upiId           UPI ID / VPA, e.g. {@code name@okaxis}
 * @param preferredMethod payment method chosen on our page: upi, card, netbanking or wallet
 */
public record PayerDetails(String name, String phone, String upiId, String preferredMethod) {

    private static final Pattern UPI_ID = Pattern.compile("^[a-zA-Z0-9.\\-_]{2,256}@[a-zA-Z][a-zA-Z0-9]{1,63}$");
    private static final Pattern PHONE = Pattern.compile("^\\+?[0-9]{10,13}$");
    private static final java.util.Set<String> METHODS = java.util.Set.of("upi", "card", "netbanking", "wallet");

    public static final PayerDetails EMPTY = new PayerDetails(null, null, null, null);

    /** Trims, validates and normalises the values; throws {@link IllegalArgumentException} for bad input. */
    public static PayerDetails of(String name, String phone, String upiId, String preferredMethod) {
        String cleanName = blankToNull(name);
        if (cleanName != null && cleanName.length() > 100) {
            throw new IllegalArgumentException("Name must be at most 100 characters");
        }

        String cleanPhone = blankToNull(phone);
        if (cleanPhone != null) {
            cleanPhone = cleanPhone.replaceAll("[\\s-]", "");
            if (!PHONE.matcher(cleanPhone).matches()) {
                throw new IllegalArgumentException("Invalid mobile number");
            }
        }

        String cleanUpi = blankToNull(upiId);
        if (cleanUpi != null) {
            if (!UPI_ID.matcher(cleanUpi).matches()) {
                throw new IllegalArgumentException("Invalid UPI ID (expected format: name@bank)");
            }
            cleanUpi = cleanUpi.toLowerCase(Locale.ROOT);
        }

        String cleanMethod = blankToNull(preferredMethod);
        if (cleanMethod != null) {
            cleanMethod = cleanMethod.toLowerCase(Locale.ROOT);
            if (!METHODS.contains(cleanMethod)) {
                throw new IllegalArgumentException("Invalid payment method");
            }
        }
        if ("upi".equals(cleanMethod) && cleanUpi == null) {
            throw new IllegalArgumentException("UPI ID is required for UPI payments");
        }
        return new PayerDetails(cleanName, cleanPhone, cleanUpi, cleanMethod);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
