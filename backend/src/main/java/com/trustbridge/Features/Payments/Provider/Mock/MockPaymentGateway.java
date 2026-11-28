package com.trustbridge.Features.Payments.Provider.Mock;

import com.trustbridge.Domain.Entities.Milestones;
import com.trustbridge.Domain.Entities.PaymentRequest;
import com.trustbridge.Domain.Entities.Users;
import com.trustbridge.Features.Payments.Provider.Dto.*;
import com.trustbridge.Domain.Entities.StripeAccount;
import com.trustbridge.Domain.Repositories.StripeAccountRepository;
import com.trustbridge.Features.Payments.Provider.PaymentGateway;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
@Profile("mock")
@RequiredArgsConstructor
public abstract class MockPaymentGateway implements PaymentGateway {

    private final StripeAccountRepository stripeAccountRepository;

    public SettlementResult createSettlementIntent(PaymentRequest paymentRequest) {
        String fakePiId = "mock_pi_" + UUID.randomUUID();
        return new SettlementResult("mock_secret_" + fakePiId, fakePiId, "http://localhost:3000/mock-checkout/" + fakePiId);
    }

    @Override
    public OnboardingResult createProviderAccount(Users freelancer) {
        String fakeAcct = "mock_acct_" + UUID.randomUUID();

        StripeAccount stripeAccount = stripeAccountRepository.findByUserId(freelancer.getId())
                .orElse(StripeAccount.builder()
                        .user(freelancer)
                        .stripeAccountId(fakeAcct)
                        .build());
        stripeAccount.setPayoutsEnabled(true);
        stripeAccountRepository.save(stripeAccount);

        return new OnboardingResult(fakeAcct, "http://localhost:3000/mock-onboarding/" + fakeAcct);
    }

    private final Map<UUID, ProviderPaymentStatus> mockLedger = new ConcurrentHashMap<>();

    public void recordMockOutcome(UUID paymentRequestId, ProviderPaymentStatus status) {
        mockLedger.put(paymentRequestId, status);
    }

    @Override
    public ProviderPaymentStatus getPaymentStatus(PaymentRequest paymentRequest) {
        return mockLedger.getOrDefault(paymentRequest.getId(), ProviderPaymentStatus.UNKNOWN);
    }

    public PayoutResult releaseFunds(Milestones milestone, Users freelancer, PaymentRequest paymentRequest) {
        return new PayoutResult("mock_tr_" + UUID.randomUUID(), paymentRequest.getAmount().multiply(BigDecimal.valueOf(100)).longValue());
    }

    public String getOrCreateCustomerId(Users client) {
        return "mock_cus_" + client.getId();
    }

    @Override
    public String getProviderName() {
        return "mock";
    }
}