package com.trustbridge.Features.Disputes.Dto;

import java.math.BigDecimal;

/**
 * What the client sees when responding to a dispute. All amounts mean "refund to the client".
 * freelancerProposedAmount and middleAmount are null until the freelancer has countered.
 */
public record DisputeClientViewDto(
        String jobTitle,
        String milestoneTitle,
        String disputeId,
        String reason,
        BigDecimal originalAmount,
        BigDecimal clientProposedAmount,
        BigDecimal freelancerProposedAmount,
        BigDecimal middleAmount,
        String state,
        Integer negotiationRound
) {}