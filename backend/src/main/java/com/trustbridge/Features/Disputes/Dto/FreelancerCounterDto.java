package com.trustbridge.Features.Disputes.Dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

import java.math.BigDecimal;
import java.math.BigInteger;

/**
 * Data transfer object representing a freelancer's counter proposal during a dispute negotiation.
 *
 * Captures the refund amount proposed by the freelancer in response to a client's dispute claim.
 * The amount is expected to be present and must not be negative.
 */
public record FreelancerCounterDto(
        @NotNull @PositiveOrZero BigDecimal refundAmount
) { }
