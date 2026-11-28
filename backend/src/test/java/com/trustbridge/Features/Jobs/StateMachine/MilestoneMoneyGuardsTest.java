package com.trustbridge.Features.Jobs.StateMachine;

import com.trustbridge.Domain.Entities.Dispute;
import com.trustbridge.Domain.Entities.Jobs;
import com.trustbridge.Domain.Enums.DisputeState;
import com.trustbridge.Domain.Entities.MilestoneSubmissions;
import com.trustbridge.Domain.Entities.Milestones;
import com.trustbridge.Domain.Entities.PaymentRequest;
import com.trustbridge.Domain.Entities.Users;
import com.trustbridge.Domain.Enums.JobStatus.jobStatus;
import com.trustbridge.Domain.Enums.MilestoneEvent.milestoneEvent;
import com.trustbridge.Domain.Enums.MilestoneStatus;
import com.trustbridge.Domain.Enums.PaymentRequestStatus;
import com.trustbridge.Domain.Enums.UserRole;
import com.trustbridge.Domain.Repositories.DisputeRepository;
import com.trustbridge.Domain.Repositories.JobRepository;
import com.trustbridge.Domain.Repositories.MilestoneRepository;
import com.trustbridge.Domain.Repositories.MilestoneSubmissionRepository;
import com.trustbridge.Domain.Repositories.PaymentRequestRepository;
import com.trustbridge.Domain.Repositories.UserRepository;
import com.trustbridge.Features.Jobs.Service.MilestoneStateService;
import com.trustbridge.Features.Jobs.StateMachine.Interceptors.MilestoneStateChangeInterceptor;
import com.trustbridge.Features.Notifications.Listeners.MilestoneEmailListener;
import com.trustbridge.Features.Notifications.Services.EmailSenderService;
import com.trustbridge.Features.Payments.Provider.PaymentGateway;
import com.trustbridge.Features.Payments.Service.PaymentRequestService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.statemachine.StateMachine;
import org.springframework.statemachine.config.StateMachineFactory;
import org.springframework.statemachine.support.DefaultStateMachineContext;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * PR 1 of TRB-38 (backlog 5.2): the money-moving milestone guards must check the database,
 * not a boolean the caller put in the message headers.
 *
 * Runs the REAL {@link MilestoneStateMachineConfig}. Only repositories, the gateway and email
 * are faked. Each test sends an event straight into the state machine, exactly as
 * MilestoneStateService does, so a guard that trusts a header is caught.
 *
 * IMPORTANT: Spring State Machine reports ACCEPTED for an event even when a guard says no
 * (the state just does not change). So these tests judge the outcome by the state the machine
 * ends up in, never by the ResultType.
 *
 * Header names used here ("actorId", "reviewToken") are the contract the guards must read.
 * The old forged flags ("isClientApproving", "isFunded") are sent on purpose to prove they no
 * longer matter.
 */
class MilestoneMoneyGuardsTest {

    private static final String ACTOR_ID = "actorId";
    private static final String REVIEW_TOKEN_HEADER = "reviewToken";
    private static final String REVIEW_TOKEN = "real-review-token";

    private AnnotationConfigApplicationContext context;
    private StateMachineFactory<MilestoneStatus, milestoneEvent> factory;

    private Users client;
    private Users freelancer;
    private Users stranger;
    private Milestones milestone;
    private PaymentRequest paymentRequest;
    private boolean paymentRequestExists;
    private MilestoneStateService stateService;
    private Users admin;
    private Dispute dispute;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        client = user(UserRole.role.CLIENT);
        freelancer = user(UserRole.role.FREELANCER);
        stranger = user(UserRole.role.CLIENT);

        Jobs job = Jobs.builder()
                .freelancer(freelancer)
                .client(client)
                .title("Website build")
                .description("Build a website")
                .currency("GBP")
                .totalAmount(new BigDecimal("1000.00"))
                .status(jobStatus.IN_PROGRESS)
                .build();
        job.setId(UUID.randomUUID());

        milestone = Milestones.builder()
                .job(job)
                .title("Milestone 1")
                .amount(new BigDecimal("1000.00"))
                .sequenceOrder(1)
                .status(MilestoneStatus.APPROVED)
                .build();
        milestone.setId(UUID.randomUUID());

        paymentRequest = PaymentRequest.builder().milestone(milestone).status(PaymentRequestStatus.PENDING).build();
        paymentRequest.setId(UUID.randomUUID());
        paymentRequestExists = false;

        MilestoneRepository milestoneRepository = mock(MilestoneRepository.class);
        when(milestoneRepository.findById(milestone.getId())).thenAnswer(inv -> Optional.of(milestone));

        // The payment request is stubbed through BOTH lookups so the test does not care which
        // one a guard uses. Both answer from the same live object, so they never disagree.
        PaymentRequestRepository paymentRequestRepository = mock(PaymentRequestRepository.class);
        when(paymentRequestRepository.findByMilestone(any(Milestones.class))).thenAnswer(inv ->
                paymentRequestExists ? Optional.of(paymentRequest) : Optional.empty());
        when(paymentRequestRepository.findByMilestoneIdAndStatus(eq(milestone.getId()), any(PaymentRequestStatus.class)))
                .thenAnswer(inv -> paymentRequestExists && paymentRequest.getStatus() == inv.getArgument(1)
                        ? Optional.of(paymentRequest) : Optional.empty());

        UserRepository userRepository = mock(UserRepository.class);
        for (Users u : new Users[]{client, freelancer, stranger}) {
            when(userRepository.findById(u.getId())).thenReturn(Optional.of(u));
        }
        // notifyFreelancerToStartAction looks the freelancer up by a header; keep it harmless.
        when(userRepository.getById(any())).thenReturn(freelancer);

        MilestoneSubmissionRepository submissionRepository = mock(MilestoneSubmissionRepository.class);
        MilestoneSubmissions latest = MilestoneSubmissions.builder()
                .milestone(milestone)
                .reviewToken(REVIEW_TOKEN)
                .build();
        when(submissionRepository.findTopByMilestoneIdOrderByCreatedAtDesc(milestone.getId()))
                .thenReturn(Optional.of(latest));

        context = new AnnotationConfigApplicationContext();
        context.registerBean(MilestoneRepository.class, () -> milestoneRepository);
        context.registerBean(PaymentRequestRepository.class, () -> paymentRequestRepository);
        context.registerBean(UserRepository.class, () -> userRepository);
        context.registerBean(MilestoneSubmissionRepository.class, () -> submissionRepository);
        context.registerBean(PaymentRequestService.class, () -> mock(PaymentRequestService.class));
        context.registerBean(EmailSenderService.class, () -> mock(EmailSenderService.class));
        context.registerBean(MilestoneEmailListener.class, () -> mock(MilestoneEmailListener.class));
        context.registerBean(PaymentGateway.class, () -> mock(PaymentGateway.class));
        dispute = null;
        DisputeRepository disputeRepository = mock(DisputeRepository.class);
        when(disputeRepository.findByMilestoneId(milestone.getId())).thenAnswer(inv -> Optional.ofNullable(dispute));
        admin = user(UserRole.role.ADMIN);
        when(userRepository.findById(admin.getId())).thenReturn(Optional.of(admin));

        context.registerBean(DisputeRepository.class, () -> disputeRepository);
        context.register(MilestoneStateMachineConfig.class);
        context.refresh();

        factory = context.getBean("MilestoneStateMachineFactory", StateMachineFactory.class);

        // The production service, with the real interceptor writing to the fake repository.
        MilestoneStateChangeInterceptor interceptor = new MilestoneStateChangeInterceptor();
        ReflectionTestUtils.setField(interceptor, "milestoneRepository", milestoneRepository);
        // Last argument is an unused PathPatternRequestMatcher.Builder field in the service.
        stateService = new MilestoneStateService(milestoneRepository, mock(DisputeRepository.class),
                factory, interceptor, mock(JobRepository.class), null);
    }

    @AfterEach
    void tearDown() {
        context.close();
    }

    // ------------------------------------------------------------------
    // RELEASE_FUNDS  (APPROVED -> PAID_OUT)
    // ------------------------------------------------------------------

    @Test
    void release_forgedApprovalFlagAlone_isBlocked_evenWhenPaid() {
        givenPaymentRequest(PaymentRequestStatus.PAID);

        Outcome result = release(Map.of("isClientApproving", true));

        assertBlocked(result);
    }

    @Test
    void release_forgedApprovalFlag_withClientActor_isBlocked_whenNoPaymentRequestExists() {
        // paymentRequestExists stays false
        Outcome result = release(Map.of("isClientApproving", true, ACTOR_ID, client.getId()));

        assertBlocked(result);
    }

    @ParameterizedTest
    @EnumSource(value = PaymentRequestStatus.class, mode = EnumSource.Mode.EXCLUDE, names = "PAID")
    void release_clientCannotReleaseUnlessPaymentIsPaid(PaymentRequestStatus status) {
        givenPaymentRequest(status);

        Outcome result = release(Map.of("isClientApproving", true, ACTOR_ID, client.getId()));

        assertBlocked(result);
    }

    @Test
    void release_clientWithPaidPayment_isAllowed() {
        givenPaymentRequest(PaymentRequestStatus.PAID);

        Outcome result = release(Map.of(ACTOR_ID, client.getId()));

        assertAllowed(result);
    }

    @Test
    void release_validReviewTokenWithPaidPayment_isAllowed() {
        givenPaymentRequest(PaymentRequestStatus.PAID);

        Outcome result = release(Map.of(REVIEW_TOKEN_HEADER, REVIEW_TOKEN));

        assertAllowed(result);
    }

    @Test
    void release_wrongReviewToken_isBlocked() {
        givenPaymentRequest(PaymentRequestStatus.PAID);

        Outcome result = release(Map.of(REVIEW_TOKEN_HEADER, "guessed-token"));

        assertBlocked(result);
    }

    @Test
    void release_freelancerCannotReleaseTheirOwnFunds() {
        givenPaymentRequest(PaymentRequestStatus.PAID);

        Outcome result = release(Map.of(ACTOR_ID, freelancer.getId()));

        assertBlocked(result);
    }

    @Test
    void release_someoneElseWhoIsNotOnTheJob_isBlocked() {
        givenPaymentRequest(PaymentRequestStatus.PAID);

        Outcome result = release(Map.of(ACTOR_ID, stranger.getId()));

        assertBlocked(result);
    }

    @Test
    void release_unknownActorId_isBlocked() {
        givenPaymentRequest(PaymentRequestStatus.PAID);

        Outcome result = release(Map.of(ACTOR_ID, UUID.randomUUID()));

        assertBlocked(result);
    }

    @Test
    void release_noActorAndNoToken_isBlocked_evenWhenPaid() {
        givenPaymentRequest(PaymentRequestStatus.PAID);

        Outcome result = release(Map.of());

        assertBlocked(result);
    }

    // ------------------------------------------------------------------
    // FUNDS_DEPOSITED  (AWAITING_PAYMENT -> IN_PROGRESS)
    // ------------------------------------------------------------------

    @ParameterizedTest
    @EnumSource(value = PaymentRequestStatus.class, mode = EnumSource.Mode.EXCLUDE, names = "PAID")
    void fundsDeposited_forgedFundedFlag_isBlocked_whenPaymentIsNotPaid(PaymentRequestStatus status) {
        givenPaymentRequest(status);

        Outcome result = send(MilestoneStatus.AWAITING_PAYMENT, milestoneEvent.FUNDS_DEPOSITED,
                Map.of("isFunded", true));

        assertBlocked(result);
    }

    @Test
    void fundsDeposited_forgedFundedFlag_isBlocked_whenNoPaymentRequestExists() {
        Outcome result = send(MilestoneStatus.AWAITING_PAYMENT, milestoneEvent.FUNDS_DEPOSITED,
                Map.of("isFunded", true));

        assertBlocked(result);
    }

    @Test
    void fundsDeposited_isAllowed_whenPaymentIsPaid_withoutAnyFlag() {
        givenPaymentRequest(PaymentRequestStatus.PAID);

        Outcome result = send(MilestoneStatus.AWAITING_PAYMENT, milestoneEvent.FUNDS_DEPOSITED, Map.of());

        assertAllowed(result);
    }

    // ------------------------------------------------------------------
    // RAISE_DISPUTE  (SUBMITTED -> DISPUTED_NEGOTIATION)
    // ------------------------------------------------------------------

    @Test
    void raiseDispute_forgedClientRole_isBlocked() {
        Outcome result = send(MilestoneStatus.SUBMITTED, milestoneEvent.RAISE_DISPUTE,
                Map.of("role", "CLIENT", "isJobDisputed", true));

        assertBlocked(result);
    }

    @Test
    void raiseDispute_byFreelancer_isBlocked() {
        assertBlocked(send(MilestoneStatus.SUBMITTED, milestoneEvent.RAISE_DISPUTE, Map.of(ACTOR_ID, freelancer.getId())));
    }

    @Test
    void raiseDispute_byStranger_isBlocked() {
        assertBlocked(send(MilestoneStatus.SUBMITTED, milestoneEvent.RAISE_DISPUTE, Map.of(ACTOR_ID, stranger.getId())));
    }

    @Test
    void raiseDispute_withWrongReviewToken_isBlocked() {
        assertBlocked(send(MilestoneStatus.SUBMITTED, milestoneEvent.RAISE_DISPUTE, Map.of(REVIEW_TOKEN_HEADER, "guessed-token")));
    }

    @Test
    void raiseDispute_byClientWithValidReviewToken_isAllowed() {
        assertAllowed(send(MilestoneStatus.SUBMITTED, milestoneEvent.RAISE_DISPUTE, Map.of(REVIEW_TOKEN_HEADER, REVIEW_TOKEN)));
    }

    @Test
    void raiseDispute_byLoggedInClient_isAllowed() {
        assertAllowed(send(MilestoneStatus.SUBMITTED, milestoneEvent.RAISE_DISPUTE, Map.of(ACTOR_ID, client.getId())));
    }

    // ------------------------------------------------------------------
    // RESOLVE_DISPUTE  (DISPUTED_NEGOTIATION -> DISPUTE_RESOLVED)
    // ------------------------------------------------------------------

    @Test
    void resolveDispute_forgedAgreementFlag_isBlocked_whenDisputeNotAgreed() {
        givenDispute(DisputeState.AWAITING_EVIDENCE, OffsetDateTime.now());

        assertBlocked(send(MilestoneStatus.DISPUTED_NEGOTIATION, milestoneEvent.RESOLVE_DISPUTE,
                Map.of("isMutualAgreement", true, ACTOR_ID, client.getId())));
    }

    @Test
    void resolveDispute_isBlocked_whenNoDisputeExists() {
        assertBlocked(send(MilestoneStatus.DISPUTED_NEGOTIATION, milestoneEvent.RESOLVE_DISPUTE, Map.of(ACTOR_ID, client.getId())));
    }

    @Test
    void resolveDispute_isAllowed_whenDisputeRowIsAgreed() {
        givenDispute(DisputeState.RESOLVED_AGREEMENT, OffsetDateTime.now());

        assertAllowed(send(MilestoneStatus.DISPUTED_NEGOTIATION, milestoneEvent.RESOLVE_DISPUTE, Map.of()));
    }

    @Test
    void resolveDispute_isAllowed_forAdmin() {
        givenDispute(DisputeState.AWAITING_EVIDENCE, OffsetDateTime.now());

        assertAllowed(send(MilestoneStatus.DISPUTED_NEGOTIATION, milestoneEvent.RESOLVE_DISPUTE, Map.of(ACTOR_ID, admin.getId())));
    }

    // ------------------------------------------------------------------
    // DISPUTE_TO_ARBITRATION  (DISPUTED_NEGOTIATION -> DISPUTE_ARBITRATION)
    // ------------------------------------------------------------------

    private static final Instant NOW = Instant.parse("2026-11-26T12:00:00Z");

    @Test
    void escalate_forgedDaysFlag_isBlocked_beforeThreeDays() {
        fixClock();
        givenDispute(DisputeState.AWAITING_EVIDENCE, OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC).minusDays(2));

        assertBlocked(send(MilestoneStatus.DISPUTED_NEGOTIATION, milestoneEvent.DISPUTE_TO_ARBITRATION,
                Map.of("daysInNegotiation", 30)));
    }

    @Test
    void escalate_isBlocked_whenNoDisputeExists() {
        fixClock();

        assertBlocked(send(MilestoneStatus.DISPUTED_NEGOTIATION, milestoneEvent.DISPUTE_TO_ARBITRATION, Map.of()));
    }

    @Test
    void escalate_isAllowed_afterThreeDays() {
        fixClock();
        givenDispute(DisputeState.AWAITING_EVIDENCE, OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC).minusDays(3));

        assertAllowed(send(MilestoneStatus.DISPUTED_NEGOTIATION, milestoneEvent.DISPUTE_TO_ARBITRATION, Map.of()));
    }

    // ------------------------------------------------------------------
    // MilestoneStateService.fireEvent must NOT report success when a guard says no
    // ------------------------------------------------------------------

    @Test
    void service_fireEvent_throws_whenTheGuardBlocksReleaseFunds() {
        givenPaymentRequest(PaymentRequestStatus.PAID);
        milestone.setStatus(MilestoneStatus.APPROVED);

        assertThatThrownBy(() -> stateService.fireEvent(milestone.getId(), milestoneEvent.RELEASE_FUNDS, Map.of()))
                .isInstanceOf(IllegalStateException.class);

        assertThat(milestone.getStatus()).isEqualTo(MilestoneStatus.APPROVED);
    }

    @Test
    void service_fireEvent_throws_whenClientTriesToReleaseUnpaidFunds() {
        givenPaymentRequest(PaymentRequestStatus.PENDING);
        milestone.setStatus(MilestoneStatus.APPROVED);

        assertThatThrownBy(() -> stateService.fireEvent(milestone.getId(), milestoneEvent.RELEASE_FUNDS,
                Map.of(ACTOR_ID, client.getId())))
                .isInstanceOf(IllegalStateException.class);

        assertThat(milestone.getStatus()).isEqualTo(MilestoneStatus.APPROVED);
    }

    @Test
    void service_fireEvent_releasesFunds_forTheClient_whenPaid() {
        givenPaymentRequest(PaymentRequestStatus.PAID);
        milestone.setStatus(MilestoneStatus.APPROVED);

        assertThatCode(() -> stateService.fireEvent(milestone.getId(), milestoneEvent.RELEASE_FUNDS,
                Map.of(ACTOR_ID, client.getId())))
                .doesNotThrowAnyException();

        assertThat(milestone.getStatus()).isEqualTo(MilestoneStatus.PAID_OUT);
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private Outcome release(Map<String, Object> headers) {
        return send(MilestoneStatus.APPROVED, milestoneEvent.RELEASE_FUNDS, headers);
    }

    private void givenDispute(DisputeState state, OffsetDateTime openedAt) {
        dispute = Dispute.builder().milestone(milestone).state(state).build();
        dispute.setId(UUID.randomUUID());
        dispute.setCreatedAt(openedAt);
    }

    private void fixClock() {
        ReflectionTestUtils.setField(context.getBean(MilestoneStateMachineConfig.class), "clock",
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private void givenPaymentRequest(PaymentRequestStatus status) {
        paymentRequest.setStatus(status);
        paymentRequestExists = true;
    }

    private Outcome send(MilestoneStatus from, milestoneEvent event, Map<String, Object> headers) {
        milestone.setStatus(from);

        StateMachine<MilestoneStatus, milestoneEvent> sm = factory.getStateMachine(UUID.randomUUID().toString());
        sm.stopReactively().block();
        sm.getStateMachineAccessor().doWithAllRegions(accessor ->
                accessor.resetStateMachineReactively(new DefaultStateMachineContext<>(from, null, null, null)).block());
        sm.startReactively().block();

        MessageBuilder<milestoneEvent> message = MessageBuilder
                .withPayload(event)
                .setHeader("milestoneId", milestone.getId());
        headers.forEach(message::setHeader);

        sm.sendEvent(Mono.just(message.build())).blockLast();
        return new Outcome(from, sm.getState().getId());
    }

    private record Outcome(MilestoneStatus from, MilestoneStatus after) {
    }

    private static void assertAllowed(Outcome result) {
        assertThat(result.after()).as("transition should move the milestone out of " + result.from()).isNotEqualTo(result.from());
    }

    private static void assertBlocked(Outcome result) {
        assertThat(result.after()).as("guard should block: milestone must stay in " + result.from()).isEqualTo(result.from());
    }

    private static Users user(UserRole.role role) {
        Users u = Users.builder().email(role + "-" + UUID.randomUUID() + "@example.com").userRole(role).build();
        u.setId(UUID.randomUUID());
        return u;
    }
}
