package com.trustbridge.Features.Payments.Service;

import com.trustbridge.Domain.Entities.PaymentRequest;
import com.trustbridge.Domain.Enums.PaymentRequestEvent;
import com.trustbridge.Domain.Enums.PaymentRequestStatus;
import com.trustbridge.Domain.Repositories.PaymentRequestRepository;
import com.trustbridge.Features.Payments.Events.MilestoneFundedEvent;
import com.trustbridge.Features.Payments.StateMachine.Interceptors.PaymentRequestChangeInterceptor;
import com.trustbridge.Security.Actor.GuardHeaders;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.statemachine.StateMachine;
import org.springframework.statemachine.config.StateMachineFactory;
import org.springframework.statemachine.support.DefaultStateMachineContext;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class PaymentStateService {

    private final PaymentRequestRepository paymentRequestRepository;


    StateMachineFactory<PaymentRequestStatus, PaymentRequestEvent> stateMachineFactory;

    PaymentRequestChangeInterceptor interceptor;

    ApplicationEventPublisher eventPublisher;

    private StateMachine<PaymentRequestStatus, PaymentRequestEvent> buildStateMachine(UUID paymentId) {

        PaymentRequest paymentRequest = paymentRequestRepository.findById(paymentId)
                .orElseThrow(() -> new RuntimeException("Payment not found!"));

        StateMachine<PaymentRequestStatus, PaymentRequestEvent> sm = stateMachineFactory.getStateMachine(paymentId.toString());

        sm.stopReactively().block();

        sm.getStateMachineAccessor().doWithAllRegions(accessor -> {

            accessor.addStateMachineInterceptor(interceptor);

            accessor.resetStateMachineReactively(new DefaultStateMachineContext<>(
                    paymentRequest.getStatus(), null, null, null
            )).block();
        });

        sm.startReactively().block();

        return sm;
    }

    /**
     * Sends an event to the payment request's state machine. Only ids go in the headers
     * (paymentRequestId, and actorId where a person is acting). The guards check the database
     * and the provider. Throws if the request did not change state.
     */
    public void fireEvent(UUID paymentId, PaymentRequestEvent event, Map<String, Object> extraHeaders) {
        StateMachine<PaymentRequestStatus, PaymentRequestEvent> sm = buildStateMachine(paymentId);
        PaymentRequestStatus before = sm.getState().getId();

        MessageBuilder<PaymentRequestEvent> builder = MessageBuilder
                .withPayload(event)
                .setHeader("paymentRequestId", paymentId);
        extraHeaders.forEach(builder::setHeader);

        sm.sendEvent(Mono.just(builder.build())).blockLast();

        PaymentRequestStatus after = sm.getState().getId();
        if (after == before) {
            throw new IllegalStateException(
                    "Transition blocked for event " + event + ": payment " + paymentId + " stayed in " + before);
        }
    }

    public void initiatePayment(UUID paymentId) {
        fireEvent(paymentId, PaymentRequestEvent.INITIATE_PAYMENT, Map.of());
    }

    public void paymentSuccessful(UUID paymentId) {
        fireEvent(paymentId, PaymentRequestEvent.PAYMENT_SUCCESSFUL, Map.of());
    }

    public void paymentFailed(UUID paymentId) {
        fireEvent(paymentId, PaymentRequestEvent.PAYMENT_FAILED, Map.of());
    }

    public void retryPayment(UUID paymentId) {
        fireEvent(paymentId, PaymentRequestEvent.RETRY_PAYMENT, Map.of());
    }

    public void expirePayment(UUID paymentId) {
        fireEvent(paymentId, PaymentRequestEvent.EXPIRE_REQUEST, Map.of());
    }

    /** @param actorId from CurrentActorResolver (the authenticated request) */
    public void cancelPayment(UUID paymentId, UUID actorId) {
        fireEvent(paymentId, PaymentRequestEvent.CANCEL_REQUEST, actorHeader(actorId));
    }

    /** @param actorId from CurrentActorResolver (the authenticated request) */
    public void refundPayment(UUID paymentId, UUID actorId) {
        fireEvent(paymentId, PaymentRequestEvent.REFUND_REQUEST, actorHeader(actorId));
    }

    /** Throws (and announces nothing) unless the PAID transition actually happened. */
    public void confirmFunded(UUID paymentRequestId, UUID milestoneId) {
        paymentSuccessful(paymentRequestId);
        eventPublisher.publishEvent(new MilestoneFundedEvent(this, milestoneId));
    }

    private static Map<String, Object> actorHeader(UUID actorId) {
        return actorId == null ? Map.of() : Map.of(GuardHeaders.ACTOR_ID, actorId);
    }
}

