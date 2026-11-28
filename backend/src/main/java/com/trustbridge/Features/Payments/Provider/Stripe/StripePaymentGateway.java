package com.trustbridge.Features.Payments.Provider.Stripe;

import com.stripe.model.PaymentIntent;
import com.stripe.model.Transfer;
import com.trustbridge.Domain.Entities.BankPaymentSession;
import com.trustbridge.Domain.Entities.Milestones;
import com.trustbridge.Domain.Entities.PaymentRequest;
import com.trustbridge.Domain.Entities.Users;
import com.trustbridge.Domain.Repositories.BankPaymentSessionRepository;
import com.trustbridge.Features.Payments.Provider.Dto.OnboardingResult;
import com.trustbridge.Features.Payments.Provider.Dto.PayoutResult;
import com.trustbridge.Features.Payments.Provider.Dto.ProviderPaymentStatus;
import com.trustbridge.Features.Payments.Provider.Dto.SettlementResult;
import com.trustbridge.Features.Payments.Provider.PaymentGateway;
import com.trustbridge.Features.Payments.Service.StripeConnectService;
import com.trustbridge.Features.Payments.Service.StripePaymentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;

@Service
@Profile("!mock")
@RequiredArgsConstructor
@Slf4j
public class StripePaymentGateway implements PaymentGateway {

    private final StripePaymentService stripePaymentService;
    private final StripeConnectService stripeConnectService;
    private final BankPaymentSessionRepository bankPaymentSessionRepository;


    /**
     * Retrieves the PaymentIntent we created for this request from Stripe and checks it really
     * belongs to this request (metadata id and amount) before trusting its status.
     */
    @Override
    public ProviderPaymentStatus getPaymentStatus(PaymentRequest paymentRequest) {
        try {
            String paymentIntentId = bankPaymentSessionRepository.findByPaymentRequestId(paymentRequest.getId())
                    .map(BankPaymentSession::getStripePiId)
                    .orElse(null);
            if (paymentIntentId == null) return ProviderPaymentStatus.UNKNOWN;

            PaymentIntent intent = PaymentIntent.retrieve(paymentIntentId);

            boolean sameRequest = paymentRequest.getId().toString()
                    .equals(intent.getMetadata().get("payment_request_id"));
            long expectedPence = paymentRequest.getAmount().multiply(new BigDecimal("100")).longValueExact();
            if (!sameRequest) return ProviderPaymentStatus.UNKNOWN;

            return switch (intent.getStatus()) {
                case "succeeded" -> intent.getAmountReceived() != null && intent.getAmountReceived() == expectedPence
                        ? ProviderPaymentStatus.SUCCEEDED
                        : ProviderPaymentStatus.UNKNOWN;
                case "canceled", "requires_payment_method" -> ProviderPaymentStatus.FAILED;
                case "processing", "requires_action", "requires_confirmation", "requires_capture" -> ProviderPaymentStatus.PENDING;
                default -> ProviderPaymentStatus.UNKNOWN;
            };
        } catch (Exception e) {
            log.warn("Could not verify payment request {} with Stripe: {}", paymentRequest.getId(), e.getMessage());
            return ProviderPaymentStatus.UNKNOWN;
        }
    }

    @Override
    public SettlementResult createSettlementIntent(PaymentRequest paymentRequest) {
        String clientSecret = stripePaymentService.createSettlementIntent(paymentRequest);
        // StripePaymentService doesn't return the ID easily in createSettlementIntent, 
        // but it's saved in the bankPaymentSession. 
        // For SettlementResult, we might need to adjust StripePaymentService or just return what we have.
        return new SettlementResult(clientSecret, null, null);
    }

    @Override
    public OnboardingResult createProviderAccount(Users freelancer) {
        String url = stripePaymentService.createFreelancerOnboardingLink(freelancer.getEmail());
        return new OnboardingResult(null, url);
    }

    @Override
    public PayoutResult releaseFunds(Milestones milestone, Users freelancer, PaymentRequest paymentRequest) {
        Transfer transfer = stripeConnectService.releaseEscrowFunds(milestone, freelancer);

        return new PayoutResult(transfer.getId(), transfer.getAmount());
    }

    @Override
    public String getOrCreateCustomerId(Users client) {
        return stripePaymentService.getOrCreateStripeCustomer(client);
    }

    @Override
    public String getProviderName() {
        return "stripe";
    }
}
