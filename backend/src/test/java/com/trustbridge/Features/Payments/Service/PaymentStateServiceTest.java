package com.trustbridge.Features.Payments.Service;

import com.trustbridge.Domain.Entities.PaymentRequest;
import com.trustbridge.Domain.Enums.PaymentRequestEvent;
import com.trustbridge.Domain.Enums.PaymentRequestStatus;
import com.trustbridge.Domain.Repositories.PaymentRequestRepository;
import com.trustbridge.Domain.Repositories.UserRepository;
import com.trustbridge.Features.Payments.Provider.PaymentGateway;
import com.trustbridge.Features.Payments.StateMachine.Interceptors.PaymentRequestChangeInterceptor;
import com.trustbridge.Features.Payments.StateMachine.PaymentRequestStateMachineConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.statemachine.config.StateMachineFactory;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * PaymentStateService.fireEvent used to print "TRANSITION DENIED" and carry on, so callers could
 * not tell that a guard or an invalid state had blocked the change. The milestone and job
 * services already throw. These tests make the payment service do the same, and make sure a
 * blocked payment can never announce that a milestone was funded.
 *
 * Runs the real state machine and the real interceptor over an in-memory repository.
 *
 * The guards themselves (provider check, expiry, actor) are tested in PaymentGuardsTest.
 */
class PaymentStateServiceTest {

    private final Map<UUID, PaymentRequest> requests = new HashMap<>();

    private AnnotationConfigApplicationContext context;
    private PaymentStateService service;
    private ApplicationEventPublisher eventPublisher;

    @BeforeEach
    void setUp() {
        PaymentRequestRepository repository = mock(PaymentRequestRepository.class);
        when(repository.findById(any(UUID.class))).thenAnswer(inv ->
                Optional.ofNullable(requests.get(inv.<UUID>getArgument(0))));
        when(repository.save(any(PaymentRequest.class))).thenAnswer(inv -> {
            PaymentRequest saved = inv.getArgument(0);
            requests.put(saved.getId(), saved);
            return saved;
        });

        // The state machine config now needs these three beans for its guards.
        context = new AnnotationConfigApplicationContext();
        context.registerBean(PaymentRequestRepository.class, () -> repository);
        context.registerBean(UserRepository.class, () -> mock(UserRepository.class));
        context.registerBean(PaymentGateway.class, () -> mock(PaymentGateway.class));
        context.register(PaymentRequestStateMachineConfig.class);
        context.refresh();

        StateMachineFactory<PaymentRequestStatus, PaymentRequestEvent> factory =
                context.getBean("PaymentRequestStateMachineFactory", StateMachineFactory.class);

        PaymentRequestChangeInterceptor interceptor = new PaymentRequestChangeInterceptor();
        ReflectionTestUtils.setField(interceptor, "paymentRequestRepository", repository);

        eventPublisher = mock(ApplicationEventPublisher.class);

        service = new PaymentStateService(repository);
        ReflectionTestUtils.setField(service, "stateMachineFactory", factory);
        ReflectionTestUtils.setField(service, "interceptor", interceptor);
        ReflectionTestUtils.setField(service, "eventPublisher", eventPublisher);
    }

    @AfterEach
    void tearDown() {
        context.close();
    }

    /** States with no PAYMENT_SUCCESSFUL transition. */
    @ParameterizedTest
    @EnumSource(value = PaymentRequestStatus.class, names = {"FAILED", "EXPIRED", "CANCELLED", "REFUNDED"})
    void paymentSuccessful_onANonPayableRequest_throws_andLeavesStatusUnchanged(PaymentRequestStatus status) {
        UUID id = requestIn(status);

        assertThatThrownBy(() -> service.paymentSuccessful(id)).isInstanceOf(IllegalStateException.class);

        assertThat(requests.get(id).getStatus()).isEqualTo(status);
    }

    @Test
    void refund_onAPendingRequest_throws_andLeavesStatusUnchanged() {
        UUID id = requestIn(PaymentRequestStatus.PENDING);

        assertThatThrownBy(() -> service.refundPayment(id, UUID.randomUUID()))
                .isInstanceOf(IllegalStateException.class);

        assertThat(requests.get(id).getStatus()).isEqualTo(PaymentRequestStatus.PENDING);
    }

    @ParameterizedTest
    @EnumSource(value = PaymentRequestStatus.class, names = {"FAILED", "EXPIRED", "CANCELLED", "REFUNDED"})
    void confirmFunded_onANonPayableRequest_neverAnnouncesTheMilestoneAsFunded(PaymentRequestStatus status) {
        UUID id = requestIn(status);

        try {
            service.confirmFunded(id, UUID.randomUUID());
        } catch (RuntimeException ignored) {
            // throwing is the right behaviour; what matters is that no event went out
        }

        verify(eventPublisher, never()).publishEvent(any(ApplicationEvent.class));
    }

    private UUID requestIn(PaymentRequestStatus status) {
        PaymentRequest request = PaymentRequest.builder()
                .status(status)
                .amount(new BigDecimal("1000.00"))
                .build();
        request.setId(UUID.randomUUID());
        requests.put(request.getId(), request);
        return request.getId();
    }
}