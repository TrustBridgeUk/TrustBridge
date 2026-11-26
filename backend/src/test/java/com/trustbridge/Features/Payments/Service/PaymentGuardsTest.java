package com.trustbridge.Features.Payments.Service;

import com.trustbridge.Domain.Entities.Jobs;
import com.trustbridge.Domain.Entities.Milestones;
import com.trustbridge.Domain.Entities.PaymentRequest;
import com.trustbridge.Domain.Entities.Users;
import com.trustbridge.Domain.Enums.PaymentRequestEvent;
import com.trustbridge.Domain.Enums.PaymentRequestStatus;
import com.trustbridge.Domain.Enums.UserRole;
import com.trustbridge.Domain.Repositories.PaymentRequestRepository;
import com.trustbridge.Domain.Repositories.UserRepository;
import com.trustbridge.Features.Payments.Provider.Dto.ProviderPaymentStatus;
import com.trustbridge.Features.Payments.Provider.PaymentGateway;
import com.trustbridge.Features.Payments.StateMachine.Interceptors.PaymentRequestChangeInterceptor;
import com.trustbridge.Features.Payments.StateMachine.PaymentRequestStateMachineConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.statemachine.config.StateMachineFactory;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Backlog 5.2: each payment guard must check the provider, the database or the server clock,
 * never a flag such as the old "guardKey" the caller set.
 *
 * Runs the real {@link PaymentRequestStateMachineConfig} through the real
 * {@link PaymentStateService}, which throws when a guard blocks. Each guard gets a blocked case
 * where the real condition is false (often with the old forged flag sent too) and an allowed case.
 */
class PaymentGuardsTest {

    private static final Instant NOW = Instant.parse("2026-11-26T12:00:00Z");
    private static final OffsetDateTime NOW_UTC = OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC);

    private final Map<UUID, PaymentRequest> requests = new HashMap<>();

    private AnnotationConfigApplicationContext context;
    private PaymentStateService service;
    private PaymentGateway gateway;

    private Users client;
    private Users freelancer;
    private Users stranger;
    private Users admin;
    private Milestones milestone;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        client = user(UserRole.role.CLIENT);
        freelancer = user(UserRole.role.FREELANCER);
        stranger = user(UserRole.role.CLIENT);
        admin = user(UserRole.role.ADMIN);

        Jobs job = Jobs.builder().client(client).freelancer(freelancer).title("Website build")
                .description("Build a website").currency("GBP").totalAmount(new BigDecimal("1000.00")).build();
        job.setId(UUID.randomUUID());
        milestone = Milestones.builder().job(job).title("Milestone 1").amount(new BigDecimal("1000.00"))
                .sequenceOrder(1).build();
        milestone.setId(UUID.randomUUID());

        PaymentRequestRepository repository = mock(PaymentRequestRepository.class);
        when(repository.findById(any(UUID.class))).thenAnswer(inv ->
                Optional.ofNullable(requests.get(inv.<UUID>getArgument(0))));
        when(repository.save(any(PaymentRequest.class))).thenAnswer(inv -> {
            PaymentRequest saved = inv.getArgument(0);
            requests.put(saved.getId(), saved);
            return saved;
        });

        UserRepository userRepository = mock(UserRepository.class);
        for (Users u : new Users[]{client, freelancer, stranger, admin}) {
            when(userRepository.findById(u.getId())).thenReturn(Optional.of(u));
        }

        gateway = mock(PaymentGateway.class);
        when(gateway.getPaymentStatus(any())).thenReturn(ProviderPaymentStatus.UNKNOWN);

        context = new AnnotationConfigApplicationContext();
        context.registerBean(PaymentRequestRepository.class, () -> repository);
        context.registerBean(UserRepository.class, () -> userRepository);
        context.registerBean(PaymentGateway.class, () -> gateway);
        context.register(PaymentRequestStateMachineConfig.class);
        context.refresh();

        ReflectionTestUtils.setField(context.getBean(PaymentRequestStateMachineConfig.class), "clock",
                Clock.fixed(NOW, ZoneOffset.UTC));

        StateMachineFactory<PaymentRequestStatus, PaymentRequestEvent> factory =
                context.getBean("PaymentRequestStateMachineFactory", StateMachineFactory.class);

        PaymentRequestChangeInterceptor interceptor = new PaymentRequestChangeInterceptor();
        ReflectionTestUtils.setField(interceptor, "paymentRequestRepository", repository);

        service = new PaymentStateService(repository);
        ReflectionTestUtils.setField(service, "stateMachineFactory", factory);
        ReflectionTestUtils.setField(service, "interceptor", interceptor);
        ReflectionTestUtils.setField(service, "eventPublisher", mock(ApplicationEventPublisher.class));
    }

    @AfterEach
    void tearDown() {
        context.close();
    }

    // INITIATE_PAYMENT (PENDING -> PROCESSING): only while not expired

    @Test
    void initiate_forgedGuardKey_isBlocked_whenRequestHasExpired() {
        UUID id = requestIn(PaymentRequestStatus.PENDING, NOW_UTC.minusMinutes(1));
        assertBlocked(id, () -> service.fireEvent(id, PaymentRequestEvent.INITIATE_PAYMENT, Map.of("guardKey", true)));
    }

    @Test
    void initiate_isAllowed_beforeExpiry() {
        UUID id = requestIn(PaymentRequestStatus.PENDING);
        assertMovedTo(id, PaymentRequestStatus.PROCESSING, () -> service.initiatePayment(id));
    }

    // PAYMENT_SUCCESSFUL (PENDING -> PAID): the provider must say SUCCEEDED

    @ParameterizedTest
    @EnumSource(value = ProviderPaymentStatus.class, names = "SUCCEEDED", mode = EnumSource.Mode.EXCLUDE)
    void paymentSuccessful_forgedGuardKey_isBlocked_unlessProviderSucceeded(ProviderPaymentStatus providerSays) {
        UUID id = requestIn(PaymentRequestStatus.PENDING);
        when(gateway.getPaymentStatus(any())).thenReturn(providerSays);

        assertBlocked(id, () -> service.fireEvent(id, PaymentRequestEvent.PAYMENT_SUCCESSFUL, Map.of("guardKey", true)));
    }

    @Test
    void paymentSuccessful_isAllowed_whenProviderSucceeded() {
        UUID id = requestIn(PaymentRequestStatus.PENDING);
        when(gateway.getPaymentStatus(any())).thenReturn(ProviderPaymentStatus.SUCCEEDED);

        assertMovedTo(id, PaymentRequestStatus.PAID, () -> service.paymentSuccessful(id));
    }

    // PAYMENT_FAILED (PENDING -> FAILED): the provider must say FAILED

    @ParameterizedTest
    @EnumSource(value = ProviderPaymentStatus.class, names = "FAILED", mode = EnumSource.Mode.EXCLUDE)
    void paymentFailed_forgedGuardKey_isBlocked_unlessProviderFailed(ProviderPaymentStatus providerSays) {
        UUID id = requestIn(PaymentRequestStatus.PENDING);
        when(gateway.getPaymentStatus(any())).thenReturn(providerSays);

        assertBlocked(id, () -> service.fireEvent(id, PaymentRequestEvent.PAYMENT_FAILED, Map.of("guardKey", true)));
    }

    @Test
    void paymentFailed_isAllowed_whenProviderFailed() {
        UUID id = requestIn(PaymentRequestStatus.PENDING);
        when(gateway.getPaymentStatus(any())).thenReturn(ProviderPaymentStatus.FAILED);

        assertMovedTo(id, PaymentRequestStatus.FAILED, () -> service.paymentFailed(id));
    }

    // CANCEL_REQUEST (PENDING -> CANCELLED): client on the job, or admin

    @Test
    void cancel_byFreelancer_isBlocked() {
        UUID id = requestIn(PaymentRequestStatus.PENDING);
        assertBlocked(id, () -> service.cancelPayment(id, freelancer.getId()));
    }

    @Test
    void cancel_byStranger_isBlocked() {
        UUID id = requestIn(PaymentRequestStatus.PENDING);
        assertBlocked(id, () -> service.cancelPayment(id, stranger.getId()));
    }

    @Test
    void cancel_withNoActor_isBlocked_evenWithForgedGuardKey() {
        UUID id = requestIn(PaymentRequestStatus.PENDING);
        assertBlocked(id, () -> service.fireEvent(id, PaymentRequestEvent.CANCEL_REQUEST, Map.of("guardKey", true)));
    }

    @Test
    void cancel_byClient_isAllowed() {
        UUID id = requestIn(PaymentRequestStatus.PENDING);
        assertMovedTo(id, PaymentRequestStatus.CANCELLED, () -> service.cancelPayment(id, client.getId()));
    }

    @Test
    void cancel_byAdmin_isAllowed() {
        UUID id = requestIn(PaymentRequestStatus.PENDING);
        assertMovedTo(id, PaymentRequestStatus.CANCELLED, () -> service.cancelPayment(id, admin.getId()));
    }

    // REFUND_REQUEST (PAID -> REFUNDED): admin only

    @Test
    void refund_byClient_isBlocked() {
        UUID id = requestIn(PaymentRequestStatus.PAID);
        assertBlocked(id, () -> service.refundPayment(id, client.getId()));
    }

    @Test
    void refund_byUnknownActor_isBlocked() {
        UUID id = requestIn(PaymentRequestStatus.PAID);
        assertBlocked(id, () -> service.refundPayment(id, UUID.randomUUID()));
    }

    @Test
    void refund_byAdmin_isAllowed() {
        UUID id = requestIn(PaymentRequestStatus.PAID);
        assertMovedTo(id, PaymentRequestStatus.REFUNDED, () -> service.refundPayment(id, admin.getId()));
    }

    // RETRY_PAYMENT (FAILED -> PENDING): only while not expired

    @Test
    void retry_isBlocked_whenRequestHasExpired() {
        UUID id = requestIn(PaymentRequestStatus.FAILED, NOW_UTC.minusMinutes(1));
        assertBlocked(id, () -> service.retryPayment(id));
    }

    @Test
    void retry_isAllowed_beforeExpiry() {
        UUID id = requestIn(PaymentRequestStatus.FAILED, NOW_UTC.plusDays(1));
        assertMovedTo(id, PaymentRequestStatus.PENDING, () -> service.retryPayment(id));
    }

    // EXPIRE_REQUEST (PENDING -> EXPIRED): only once expiresAt has passed

    @Test
    void expire_isBlocked_beforeExpiry() {
        UUID id = requestIn(PaymentRequestStatus.PENDING, NOW_UTC.plusDays(1));
        assertBlocked(id, () -> service.expirePayment(id));
    }

    @Test
    void expire_isAllowed_afterExpiry() {
        UUID id = requestIn(PaymentRequestStatus.PENDING, NOW_UTC.minusMinutes(1));
        assertMovedTo(id, PaymentRequestStatus.EXPIRED, () -> service.expirePayment(id));
    }

    // helpers

    private UUID requestIn(PaymentRequestStatus status) {
        return requestIn(status, NOW_UTC.plusDays(7));
    }

    private UUID requestIn(PaymentRequestStatus status, OffsetDateTime expiresAt) {
        PaymentRequest request = PaymentRequest.builder()
                .milestone(milestone)
                .status(status)
                .amount(new BigDecimal("1000.00"))
                .expiresAt(expiresAt)
                .build();
        request.setId(UUID.randomUUID());
        requests.put(request.getId(), request);
        return request.getId();
    }

    private void assertBlocked(UUID id, Runnable call) {
        PaymentRequestStatus before = requests.get(id).getStatus();
        assertThatThrownBy(call::run).isInstanceOf(IllegalStateException.class);
        assertThat(requests.get(id).getStatus()).as("guard should block").isEqualTo(before);
    }

    private void assertMovedTo(UUID id, PaymentRequestStatus expected, Runnable call) {
        assertThatCode(call::run).doesNotThrowAnyException();
        assertThat(requests.get(id).getStatus()).isEqualTo(expected);
    }

    private static Users user(UserRole.role role) {
        Users u = Users.builder().email(role + "-" + UUID.randomUUID() + "@example.com").userRole(role).build();
        u.setId(UUID.randomUUID());
        return u;
    }
}
