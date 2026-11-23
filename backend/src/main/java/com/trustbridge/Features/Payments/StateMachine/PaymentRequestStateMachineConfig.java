package com.trustbridge.Features.Payments.StateMachine;

import com.trustbridge.Domain.Entities.PaymentRequest;
import com.trustbridge.Domain.Entities.Users;
import com.trustbridge.Domain.Enums.PaymentRequestEvent;
import com.trustbridge.Domain.Enums.PaymentRequestStatus;
import com.trustbridge.Domain.Enums.UserRole;
import com.trustbridge.Domain.Repositories.PaymentRequestRepository;
import com.trustbridge.Domain.Repositories.UserRepository;
import com.trustbridge.Features.Payments.Provider.Dto.ProviderPaymentStatus;
import com.trustbridge.Features.Payments.Provider.PaymentGateway;
import com.trustbridge.Security.Actor.GuardHeaders;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.statemachine.StateContext;
import org.springframework.statemachine.config.EnableStateMachineFactory;
import org.springframework.statemachine.config.EnumStateMachineConfigurerAdapter;
import org.springframework.statemachine.config.builders.StateMachineStateConfigurer;
import org.springframework.statemachine.config.builders.StateMachineTransitionConfigurer;
import org.springframework.statemachine.guard.Guard;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.EnumSet;
import java.util.UUID;
import java.util.function.Predicate;

@Configuration
@EnableStateMachineFactory(name = "PaymentRequestStateMachineFactory")
@RequiredArgsConstructor
@Slf4j
public class PaymentRequestStateMachineConfig extends EnumStateMachineConfigurerAdapter<PaymentRequestStatus, PaymentRequestEvent> {

    private final PaymentRequestRepository paymentRequestRepository;
    private final PaymentGateway paymentGateway;
    private final UserRepository userRepository;

    @Override
    public void configure(StateMachineStateConfigurer<PaymentRequestStatus, PaymentRequestEvent> states) throws Exception {
        states
                .withStates()
                .initial(PaymentRequestStatus.PENDING)
                .states(EnumSet.allOf(PaymentRequestStatus.class));
    }

    @Override
    public void configure(StateMachineTransitionConfigurer<PaymentRequestStatus, PaymentRequestEvent> transitions) throws Exception {
        transitions
                .withExternal()
                .source(PaymentRequestStatus.PENDING).target(PaymentRequestStatus.PROCESSING)
                .event(PaymentRequestEvent.INITIATE_PAYMENT)
                .guard(isPaymentInitiatedGuard())
                .and()
                .withExternal()
                .source(PaymentRequestStatus.PROCESSING).target(PaymentRequestStatus.PAID)
                .event(PaymentRequestEvent.PAYMENT_SUCCESSFUL)
                .guard(isPaymentSuccessfulGuard())
                .and()
                .withExternal()
                .source(PaymentRequestStatus.PENDING).target(PaymentRequestStatus.PAID)
                .event(PaymentRequestEvent.PAYMENT_SUCCESSFUL)
                .guard(isPaymentSuccessfulGuard())
                .and()
                .withExternal()
                .source(PaymentRequestStatus.PROCESSING).target(PaymentRequestStatus.FAILED)
                .event(PaymentRequestEvent.PAYMENT_FAILED)
                .guard(isPaymentFailedGuard())
                .and()
                .withExternal()
                .source(PaymentRequestStatus.PENDING).target(PaymentRequestStatus.FAILED)
                .event(PaymentRequestEvent.PAYMENT_FAILED)
                .guard(isPaymentFailedGuard()) // FIXED: Was isPaymentRetryingGuard()
                .and()
                .withExternal()
                .source(PaymentRequestStatus.FAILED).target(PaymentRequestStatus.PENDING)
                .event(PaymentRequestEvent.RETRY_PAYMENT)
                .guard(isPaymentRetryingGuard()) // FIXED: Was isPaymentExpiredGuard()
                .and()
                .withExternal()
                .source(PaymentRequestStatus.PENDING).target(PaymentRequestStatus.EXPIRED)
                .event(PaymentRequestEvent.EXPIRE_REQUEST)
                .guard(isPaymentExpiredGuard()) // FIXED: Was isPaymentCancelledGuard()
                .and()
                .withExternal()
                .source(PaymentRequestStatus.PENDING).target(PaymentRequestStatus.CANCELLED)
                .event(PaymentRequestEvent.CANCEL_REQUEST)
                .guard(isPaymentCancelledGuard()) // FIXED: Was isPaymentRefundedGuard()
                .and()
                .withExternal()
                .source(PaymentRequestStatus.PAID).target(PaymentRequestStatus.REFUNDED)
                .event(PaymentRequestEvent.REFUND_REQUEST)
                .guard(isPaymentRefundedGuard());
    }

    // ------------------------------------------------------------------
    // GUARDS
    //
    // Each guard loads the payment request by the "paymentRequestId" header and checks the
    // database or the payment provider. No guard reads a true/false flag from the caller.
    // A missing id, missing row, unknown provider answer or any exception means "no".
    // ------------------------------------------------------------------

    /** INITIATE_PAYMENT (PENDING -> PROCESSING): the request exists and has not expired. */
    @Bean
    public Guard<PaymentRequestStatus, PaymentRequestEvent> isPaymentInitiatedGuard() {
        return context -> check("isPaymentInitiatedGuard", context, request -> !isExpired(request));
    }

    /** PAYMENT_SUCCESSFUL (-> PAID): the provider confirms the money arrived for this request. */
    @Bean
    public Guard<PaymentRequestStatus, PaymentRequestEvent> isPaymentSuccessfulGuard() {
        return context -> check("isPaymentSuccessfulGuard", context,
                request -> paymentGateway.getPaymentStatus(request) == ProviderPaymentStatus.SUCCEEDED);
    }

    /** PAYMENT_FAILED (-> FAILED): the provider confirms the payment failed. */
    @Bean
    public Guard<PaymentRequestStatus, PaymentRequestEvent> isPaymentFailedGuard() {
        return context -> check("isPaymentFailedGuard", context,
                request -> paymentGateway.getPaymentStatus(request) == ProviderPaymentStatus.FAILED);
    }

    /** RETRY_PAYMENT (FAILED -> PENDING): only while the request has not expired. */
    @Bean
    public Guard<PaymentRequestStatus, PaymentRequestEvent> isPaymentRetryingGuard() {
        return context -> check("isPaymentRetryingGuard", context, request -> !isExpired(request));
    }

    /** EXPIRE_REQUEST (PENDING -> EXPIRED): expiresAt is set and is in the past. */
    @Bean
    public Guard<PaymentRequestStatus, PaymentRequestEvent> isPaymentExpiredGuard() {
        return context -> check("isPaymentExpiredGuard", context,
                request -> request.getExpiresAt() != null && isExpired(request));
    }

    /** CANCEL_REQUEST (PENDING -> CANCELLED): the actor is the client on the job, or an ADMIN. */
    @Bean
    public Guard<PaymentRequestStatus, PaymentRequestEvent> isPaymentCancelledGuard() {
        return context -> check("isPaymentCancelledGuard", context, request -> {
            Users actor = actor(context);
            if (actor == null) return false;
            if (actor.getUserRole() == UserRole.role.ADMIN) return true;

            Users client = request.getMilestone().getJob().getClient();
            return client != null && actor.getId().equals(client.getId());
        });
    }

    /** REFUND_REQUEST (PAID -> REFUNDED): ADMIN only, role read from the user row. */
    @Bean
    public Guard<PaymentRequestStatus, PaymentRequestEvent> isPaymentRefundedGuard() {
        return context -> check("isPaymentRefundedGuard", context, request -> {
            Users actor = actor(context);
            return actor != null && actor.getUserRole() == UserRole.role.ADMIN;
        });
    }

    // ------------------------------------------------------------------
    // guard helpers
    // ------------------------------------------------------------------

    /** Server clock. A field so a test can swap it with ReflectionTestUtils. */
    Clock clock = Clock.systemUTC();

    private boolean isExpired(PaymentRequest request) {
        return request.getExpiresAt() != null && !request.getExpiresAt().isAfter(OffsetDateTime.now(clock));
    }

    private Users actor(StateContext<PaymentRequestStatus, PaymentRequestEvent> context) {
        UUID actorId = context.getMessageHeaders().get(GuardHeaders.ACTOR_ID, UUID.class);
        return actorId == null ? null : userRepository.findById(actorId).orElse(null);
    }

    /** Loads the request, runs the check, and blocks on anything unexpected (fail closed). */
    private boolean check(String guardName,
                          StateContext<PaymentRequestStatus, PaymentRequestEvent> context,
                          Predicate<PaymentRequest> rule) {
        try {
            UUID id = context.getMessageHeaders().get("paymentRequestId", UUID.class);
            PaymentRequest request = id == null ? null : paymentRequestRepository.findById(id).orElse(null);
            boolean allowed = request != null && rule.test(request);
            log.info("Guard {}: {}", guardName, allowed ? "ALLOWED" : "BLOCKED");
            return allowed;
        } catch (RuntimeException e) {
            log.warn("Guard {}: BLOCKED (error: {})", guardName, e.getMessage());
            return false;
        }
    }
}
