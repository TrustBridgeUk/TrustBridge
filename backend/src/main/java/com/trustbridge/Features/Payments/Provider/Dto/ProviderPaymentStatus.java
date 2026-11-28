package com.trustbridge.Features.Payments.Provider.Dto;

/**
 * What the payment provider itself says about a payment request's money.
 * UNKNOWN covers "no provider record", "record does not match this request" and "provider unreachable".
 * Guards treat UNKNOWN as "no".
 */
public enum ProviderPaymentStatus {
    SUCCEEDED,
    PENDING,
    FAILED,
    UNKNOWN
}