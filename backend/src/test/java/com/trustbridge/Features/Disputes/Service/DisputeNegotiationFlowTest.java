package com.trustbridge.Features.Disputes.Service;

import com.trustbridge.Domain.Entities.Dispute;
import com.trustbridge.Domain.Entities.DisputeEvidenceSubmission;
import com.trustbridge.Domain.Entities.Jobs;
import com.trustbridge.Domain.Entities.MilestoneSubmissions;
import com.trustbridge.Domain.Entities.Milestones;
import com.trustbridge.Domain.Entities.Users;
import com.trustbridge.Domain.Enums.DisputeEvent;
import com.trustbridge.Domain.Enums.DisputeState;
import com.trustbridge.Domain.Enums.MilestoneStatus;
import com.trustbridge.Domain.Repositories.DisputeEvidenceFilesRepository;
import com.trustbridge.Domain.Repositories.DisputeEvidenceSubmissionRepository;
import com.trustbridge.Domain.Repositories.DisputeRepository;
import com.trustbridge.Domain.Repositories.MilestoneRepository;
import com.trustbridge.Domain.Repositories.MilestoneSubmissionRepository;
import com.trustbridge.Features.Disputes.Dto.DisputeCreationDto;
import com.trustbridge.Features.Disputes.Dto.DisputeOpenedResult;
import com.trustbridge.Features.Disputes.Dto.FreelancerCounterDto;
import com.trustbridge.Features.Disputes.StateMachine.DisputeStateMachineConfig;
import com.trustbridge.Features.Disputes.StateMachine.Interceptors.DisputeStateChangeInterceptor;
import com.trustbridge.Features.Jobs.Service.FileStorageService;
import com.trustbridge.Features.Jobs.Service.JobStateService;
import com.trustbridge.Features.Jobs.Service.MilestoneStateService;
import com.trustbridge.Features.Notifications.Listeners.DisputeEmailListener;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.statemachine.config.StateMachineFactory;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Exercises the dispute negotiation flow against the REAL state machine
 * ({@link DisputeStateMachineConfig}), {@link DisputeStateService} and
 * {@link DisputeService}. Only persistence, email and the job/milestone side effects are faked,
 * so a service method that forgets to fire a state-machine event is caught here.
 */
class DisputeNegotiationFlowTest {

    private static final String REVIEW_TOKEN = "review-token";
    private static final String WRONG_TOKEN = "not-the-review-token";
    private static final BigDecimal MILESTONE_AMOUNT = new BigDecimal("1000.00");
    private static final BigDecimal CLIENT_ASK = new BigDecimal("600.00");
    private static final BigDecimal FREELANCER_COUNTER = new BigDecimal("200.00");
    private static final BigDecimal MIDDLE = new BigDecimal("400.00");
    private static final int MAX_ROUNDS = 3;

    private final Map<UUID, Dispute> disputes = new HashMap<>();
    private final Map<String, DisputeEvidenceSubmission> submissionsByToken = new HashMap<>();

    private DisputeEmailListener emailListener;
    private AnnotationConfigApplicationContext stateMachineContext;
    private DisputeService service;
    private Milestones milestone;
    private String inviteToken;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        DisputeRepository disputeRepository = mock(DisputeRepository.class);
        when(disputeRepository.save(any(Dispute.class))).thenAnswer(inv -> {
            Dispute saved = inv.getArgument(0);
            if (saved.getId() == null) {
                saved.setId(UUID.randomUUID());
            }
            disputes.put(saved.getId(), saved);
            return saved;
        });
        when(disputeRepository.findById(any(UUID.class))).thenAnswer(inv ->
                Optional.ofNullable(disputes.get(inv.<UUID>getArgument(0))));
        when(disputeRepository.findByMilestoneId(any(UUID.class))).thenAnswer(inv ->
                disputes.values().stream()
                        .filter(d -> d.getMilestone().getId().equals(inv.<UUID>getArgument(0)))
                        .findFirst());

        DisputeEvidenceSubmissionRepository submissionRepository = mock(DisputeEvidenceSubmissionRepository.class);
        when(submissionRepository.save(any(DisputeEvidenceSubmission.class))).thenAnswer(inv -> {
            DisputeEvidenceSubmission saved = inv.getArgument(0);
            if (saved.getId() == null) {
                saved.setId(UUID.randomUUID());
            }
            submissionsByToken.put(saved.getInviteToken(), saved);
            return saved;
        });
        when(submissionRepository.findByInviteToken(any(String.class))).thenAnswer(inv ->
                Optional.ofNullable(submissionsByToken.get(inv.<String>getArgument(0))));

        Users freelancer = Users.builder().firstName("Fran").email("fran@example.com").build();
        freelancer.setId(UUID.randomUUID());
        Users client = Users.builder().firstName("Cli").email("cli@example.com").build();
        client.setId(UUID.randomUUID());

        Jobs job = Jobs.builder()
                .freelancer(freelancer)
                .client(client)
                .title("Website build")
                .description("Build a website")
                .currency("GBP")
                .totalAmount(MILESTONE_AMOUNT)
                .build();
        job.setId(UUID.randomUUID());

        milestone = Milestones.builder()
                .job(job)
                .title("Milestone 1")
                .amount(MILESTONE_AMOUNT)
                .sequenceOrder(1)
                .status(MilestoneStatus.SUBMITTED)
                .build();
        milestone.setId(UUID.randomUUID());

        MilestoneRepository milestoneRepository = mock(MilestoneRepository.class);
        when(milestoneRepository.findById(milestone.getId())).thenReturn(Optional.of(milestone));

        MilestoneSubmissionRepository milestoneSubmissionRepository = mock(MilestoneSubmissionRepository.class);
        MilestoneSubmissions latestSubmission = MilestoneSubmissions.builder()
                .milestone(milestone)
                .reviewToken(REVIEW_TOKEN)
                .build();
        when(milestoneSubmissionRepository.findTopByMilestoneIdOrderByCreatedAtDesc(milestone.getId()))
                .thenReturn(Optional.of(latestSubmission));

        // Real state machine, wired exactly like production (factory + interceptor + service).
        stateMachineContext = new AnnotationConfigApplicationContext(DisputeStateMachineConfig.class);
        StateMachineFactory<DisputeState, DisputeEvent> factory =
                stateMachineContext.getBean("DisputeStateMachineFactory", StateMachineFactory.class);

        DisputeStateChangeInterceptor interceptor = new DisputeStateChangeInterceptor();
        ReflectionTestUtils.setField(interceptor, "disputeRepository", disputeRepository);

        DisputeStateService stateService = new DisputeStateService(disputeRepository);
        ReflectionTestUtils.setField(stateService, "stateMachineFactory", factory);
        ReflectionTestUtils.setField(stateService, "disputeInterceptor", interceptor);

        emailListener = mock(DisputeEmailListener.class);

        service = new DisputeService(
                disputeRepository,
                stateService,
                mock(MilestoneStateService.class),
                mock(JobStateService.class),
                milestoneRepository,
                submissionRepository,
                mock(DisputeEvidenceFilesRepository.class),
                mock(FileStorageService.class),
                emailListener,
                milestoneSubmissionRepository);
    }

    @AfterEach
    void tearDown() {
        stateMachineContext.close();
    }

    // ---------------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------------

    private void openDispute() {
        DisputeOpenedResult opened = service.processClientDispute(
                new DisputeCreationDto(milestone.getId(), CLIENT_ASK, "Work was not delivered"),
                List.of(),
                REVIEW_TOKEN);
        inviteToken = opened.inviteToken();
    }

    private Dispute dispute() {
        return disputes.values().iterator().next();
    }

    private void counter(BigDecimal refund) {
        service.submitCounterProposal(inviteToken, new FreelancerCounterDto(refund));
    }

    /** Puts an already-opened dispute into an arbitrary state, as if earlier steps had happened. */
    private void forceState(DisputeState state, int round, BigDecimal freelancerAmount) {
        Dispute d = dispute();
        d.setState(state);
        d.setNegotiationRound(round);
        d.setFreelancerProposedAmount(freelancerAmount);
    }

    private void assertUntouched(DisputeState state, int round, BigDecimal freelancerAmount) {
        Dispute d = dispute();
        assertThat(d.getState()).isEqualTo(state);
        assertThat(d.getNegotiationRound()).isEqualTo(round);
        assertThat(d.getFreelancerProposedAmount()).isEqualTo(freelancerAmount);
        assertThat(d.getFinalSettlementAmount()).isNull();
    }

    // ---------------------------------------------------------------------------------------
    // Acceptance criterion: full flow open -> counter -> client accepts
    // (fails on the old code: the counter never moved the dispute out of AWAITING_PROPOSAL)
    // ---------------------------------------------------------------------------------------

    @Test
    void fullFlow_openCounterClientAccepts_resolvesAtFreelancersAmount() {
        openDispute();
        assertThat(dispute().getState()).isEqualTo(DisputeState.AWAITING_PROPOSAL);

        counter(FREELANCER_COUNTER);

        assertThat(dispute().getState()).isEqualTo(DisputeState.AWAITING_DISPUTE_DECISION);
        assertThat(dispute().getNegotiationRound()).isEqualTo(1);
        assertThat(dispute().getFreelancerProposedAmount()).isEqualByComparingTo(FREELANCER_COUNTER);

        service.acceptFreelancerCounter(milestone.getId(), REVIEW_TOKEN);

        assertThat(dispute().getState()).isEqualTo(DisputeState.RESOLVED_AGREEMENT);
        assertThat(dispute().getFinalSettlementAmount()).isEqualByComparingTo(FREELANCER_COUNTER);
    }

    @Test
    void fullFlow_openCounterClientAcceptsMiddle_resolvesAtMidpoint() {
        openDispute();
        counter(FREELANCER_COUNTER);

        service.acceptMiddle(milestone.getId(), REVIEW_TOKEN);

        assertThat(dispute().getState()).isEqualTo(DisputeState.RESOLVED_AGREEMENT);
        assertThat(dispute().getFinalSettlementAmount()).isEqualByComparingTo(MIDDLE);
    }

    @Test
    void counter_movesTurnToClient_andDoesNotSendEmailInsideTheTransaction() {
        openDispute();

        counter(FREELANCER_COUNTER);

        assertThat(dispute().getState()).isEqualTo(DisputeState.AWAITING_DISPUTE_DECISION);
        // The email is the caller's job, after the transaction commits.
        verifyNoInteractions(emailListener);
    }

    @Test
    void counter_returnsMilestoneIdSoTheCallerCanSendTheEmailAfterCommit() {
        openDispute();

        UUID returned = service.submitCounterProposal(inviteToken, new FreelancerCounterDto(FREELANCER_COUNTER));

        assertThat(returned).isEqualTo(milestone.getId());
    }

    // ---------------------------------------------------------------------------------------
    // Counter-offer email (sent by the controller after the service transaction has committed)
    // ---------------------------------------------------------------------------------------

    @Test
    void sendCounterOfferEmail_sendsClientTheMidpointAndReviewToken() {
        openDispute();
        counter(FREELANCER_COUNTER);

        service.sendCounterOfferEmail(milestone.getId());

        verify(emailListener).freelancerCounterDispute(milestone, dispute(), MIDDLE, REVIEW_TOKEN);
    }

    @Test
    void sendCounterOfferEmail_neverThrows_whenEmailFails() {
        openDispute();
        counter(FREELANCER_COUNTER);
        doThrow(new RuntimeException("smtp down"))
                .when(emailListener).freelancerCounterDispute(any(), any(), any(), any());

        assertThatCode(() -> service.sendCounterOfferEmail(milestone.getId())).doesNotThrowAnyException();
        assertThat(dispute().getState()).isEqualTo(DisputeState.AWAITING_DISPUTE_DECISION);
    }

    // ---------------------------------------------------------------------------------------
    // Round limit
    // ---------------------------------------------------------------------------------------

    @Test
    void reject_beforeTheCap_handsTurnBackToFreelancer() {
        openDispute();
        counter(FREELANCER_COUNTER);

        service.rejectFreelancerCounter(milestone.getId(), REVIEW_TOKEN);

        assertThat(dispute().getState()).isEqualTo(DisputeState.AWAITING_PROPOSAL);
        assertThat(dispute().getNegotiationRound()).isEqualTo(1);
    }

    @Test
    void freelancerCanCounterAgainAfterARejection_andRoundIncrements() {
        openDispute();
        counter(FREELANCER_COUNTER);
        service.rejectFreelancerCounter(milestone.getId(), REVIEW_TOKEN);

        counter(new BigDecimal("250.00"));

        assertThat(dispute().getState()).isEqualTo(DisputeState.AWAITING_DISPUTE_DECISION);
        assertThat(dispute().getNegotiationRound()).isEqualTo(2);
    }

    @Test
    void rejectingTheLastAllowedRound_escalates_andFreelancerCannotCounterAgain() {
        openDispute();
        for (int round = 1; round < MAX_ROUNDS; round++) {
            counter(FREELANCER_COUNTER);
            service.rejectFreelancerCounter(milestone.getId(), REVIEW_TOKEN);
            assertThat(dispute().getState()).isEqualTo(DisputeState.AWAITING_PROPOSAL);
        }

        counter(FREELANCER_COUNTER);
        assertThat(dispute().getNegotiationRound()).isEqualTo(MAX_ROUNDS);
        service.rejectFreelancerCounter(milestone.getId(), REVIEW_TOKEN);

        assertThat(dispute().getState()).isEqualTo(DisputeState.AWAITING_EXTRA_EVIDENCE);
        assertThat(dispute().getNegotiationRound()).isEqualTo(MAX_ROUNDS);

        assertThatThrownBy(() -> counter(FREELANCER_COUNTER)).isInstanceOf(IllegalStateException.class);
        assertThat(dispute().getNegotiationRound()).isEqualTo(MAX_ROUNDS);
    }

    @Test
    void clientCanStillAcceptTheLastAllowedRound() {
        openDispute();
        for (int round = 1; round < MAX_ROUNDS; round++) {
            counter(FREELANCER_COUNTER);
            service.rejectFreelancerCounter(milestone.getId(), REVIEW_TOKEN);
        }
        counter(FREELANCER_COUNTER);

        service.acceptFreelancerCounter(milestone.getId(), REVIEW_TOKEN);

        assertThat(dispute().getState()).isEqualTo(DisputeState.RESOLVED_AGREEMENT);
    }

    @Test
    void counter_atTheCap_isRefusedEvenIfTheDisputeIsSomehowBackInAwaitingProposal() {
        openDispute();
        forceState(DisputeState.AWAITING_PROPOSAL, MAX_ROUNDS, FREELANCER_COUNTER);

        assertThatThrownBy(() -> counter(new BigDecimal("100.00"))).isInstanceOf(IllegalStateException.class);

        assertUntouched(DisputeState.AWAITING_PROPOSAL, MAX_ROUNDS, FREELANCER_COUNTER);
    }

    // ---------------------------------------------------------------------------------------
    // Freelancer actions: accept client's proposal, counter
    // ---------------------------------------------------------------------------------------

    @Test
    void freelancerAccept_inAwaitingProposal_resolvesAtClientsAsk() {
        openDispute();

        service.acceptClientProposal(inviteToken);

        assertThat(dispute().getState()).isEqualTo(DisputeState.RESOLVED_AGREEMENT);
        assertThat(dispute().getFinalSettlementAmount()).isEqualByComparingTo(CLIENT_ASK);
    }

    @ParameterizedTest
    @EnumSource(value = DisputeState.class, names = "AWAITING_PROPOSAL", mode = EnumSource.Mode.EXCLUDE)
    void freelancerAccept_inAnyOtherState_isRejected(DisputeState state) {
        openDispute();
        forceState(state, 1, FREELANCER_COUNTER);

        assertThatThrownBy(() -> service.acceptClientProposal(inviteToken))
                .isInstanceOf(IllegalStateException.class);

        assertUntouched(state, 1, FREELANCER_COUNTER);
    }

    @ParameterizedTest
    @EnumSource(value = DisputeState.class, names = "AWAITING_PROPOSAL", mode = EnumSource.Mode.EXCLUDE)
    void freelancerCounter_inAnyOtherState_isRejected(DisputeState state) {
        openDispute();
        forceState(state, 1, FREELANCER_COUNTER);

        assertThatThrownBy(() -> counter(new BigDecimal("100.00")))
                .isInstanceOf(IllegalStateException.class);

        assertUntouched(state, 1, FREELANCER_COUNTER);
    }

    @Test
    void freelancerCounter_ofZero_isAllowed() {
        openDispute();

        counter(BigDecimal.ZERO);

        assertThat(dispute().getState()).isEqualTo(DisputeState.AWAITING_DISPUTE_DECISION);
        assertThat(dispute().getFreelancerProposedAmount()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void freelancerCounter_equalToClientsAsk_isRejected() {
        openDispute();

        assertThatThrownBy(() -> counter(CLIENT_ASK)).isInstanceOf(IllegalArgumentException.class);

        assertUntouched(DisputeState.AWAITING_PROPOSAL, 0, null);
    }

    @Test
    void freelancerCounter_aboveClientsAsk_isRejected() {
        openDispute();

        assertThatThrownBy(() -> counter(new BigDecimal("700.00"))).isInstanceOf(IllegalArgumentException.class);

        assertUntouched(DisputeState.AWAITING_PROPOSAL, 0, null);
    }

    @Test
    void freelancerActions_withUnknownInviteToken_areForbidden() {
        openDispute();

        assertThatThrownBy(() -> service.acceptClientProposal("bogus"))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> service.submitCounterProposal("bogus", new FreelancerCounterDto(BigDecimal.ONE)))
                .isInstanceOf(AccessDeniedException.class);

        assertUntouched(DisputeState.AWAITING_PROPOSAL, 0, null);
    }

    // ---------------------------------------------------------------------------------------
    // Client actions: accept counter, accept middle, reject
    // ---------------------------------------------------------------------------------------

    @ParameterizedTest
    @EnumSource(value = DisputeState.class, names = "AWAITING_DISPUTE_DECISION", mode = EnumSource.Mode.EXCLUDE)
    void clientAcceptCounter_inAnyOtherState_isRejected(DisputeState state) {
        openDispute();
        forceState(state, 1, FREELANCER_COUNTER);

        assertThatThrownBy(() -> service.acceptFreelancerCounter(milestone.getId(), REVIEW_TOKEN))
                .isInstanceOf(IllegalStateException.class);

        assertUntouched(state, 1, FREELANCER_COUNTER);
    }

    @ParameterizedTest
    @EnumSource(value = DisputeState.class, names = "AWAITING_DISPUTE_DECISION", mode = EnumSource.Mode.EXCLUDE)
    void clientAcceptMiddle_inAnyOtherState_isRejected(DisputeState state) {
        openDispute();
        forceState(state, 1, FREELANCER_COUNTER);

        assertThatThrownBy(() -> service.acceptMiddle(milestone.getId(), REVIEW_TOKEN))
                .isInstanceOf(IllegalStateException.class);

        assertUntouched(state, 1, FREELANCER_COUNTER);
    }

    @ParameterizedTest
    @EnumSource(value = DisputeState.class, names = "AWAITING_DISPUTE_DECISION", mode = EnumSource.Mode.EXCLUDE)
    void clientReject_inAnyOtherState_isRejected(DisputeState state) {
        openDispute();
        forceState(state, 1, FREELANCER_COUNTER);

        assertThatThrownBy(() -> service.rejectFreelancerCounter(milestone.getId(), REVIEW_TOKEN))
                .isInstanceOf(IllegalStateException.class);

        assertUntouched(state, 1, FREELANCER_COUNTER);
    }

    @Test
    void clientActions_beforeAnyCounterExists_areRejected() {
        openDispute();
        // Right state, but the freelancer has not made an offer yet.
        forceState(DisputeState.AWAITING_DISPUTE_DECISION, 0, null);

        assertThatThrownBy(() -> service.acceptFreelancerCounter(milestone.getId(), REVIEW_TOKEN))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> service.acceptMiddle(milestone.getId(), REVIEW_TOKEN))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> service.rejectFreelancerCounter(milestone.getId(), REVIEW_TOKEN))
                .isInstanceOf(IllegalStateException.class);

        assertUntouched(DisputeState.AWAITING_DISPUTE_DECISION, 0, null);
    }

    @Test
    void clientActions_withWrongReviewToken_areForbidden_andChangeNothing() {
        openDispute();
        counter(FREELANCER_COUNTER);

        assertThatThrownBy(() -> service.acceptFreelancerCounter(milestone.getId(), WRONG_TOKEN))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> service.acceptMiddle(milestone.getId(), WRONG_TOKEN))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> service.rejectFreelancerCounter(milestone.getId(), WRONG_TOKEN))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> service.acceptFreelancerCounter(milestone.getId(), null))
                .isInstanceOf(AccessDeniedException.class);

        assertUntouched(DisputeState.AWAITING_DISPUTE_DECISION, 1, FREELANCER_COUNTER);
    }

    @Test
    void clientActions_forUnknownMilestone_areForbidden() {
        openDispute();
        UUID unknown = UUID.randomUUID();

        assertThatThrownBy(() -> service.acceptFreelancerCounter(unknown, REVIEW_TOKEN))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> service.acceptMiddle(unknown, REVIEW_TOKEN))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> service.rejectFreelancerCounter(unknown, REVIEW_TOKEN))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void clientActions_whenNoDisputeExists_areRejected() {
        // No openDispute(): the milestone and review token are valid, but there is no dispute.
        assertThatThrownBy(() -> service.acceptFreelancerCounter(milestone.getId(), REVIEW_TOKEN))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> service.acceptMiddle(milestone.getId(), REVIEW_TOKEN))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> service.rejectFreelancerCounter(milestone.getId(), REVIEW_TOKEN))
                .isInstanceOf(IllegalStateException.class);
    }

    // ---------------------------------------------------------------------------------------
    // Opening a dispute (the first leg of the flow)
    // ---------------------------------------------------------------------------------------

    @Test
    void open_movesDisputeToAwaitingProposal_andRefusesASecondDispute() {
        openDispute();

        assertThat(dispute().getState()).isEqualTo(DisputeState.AWAITING_PROPOSAL);
        assertThat(dispute().getNegotiationRound()).isZero();

        assertThatThrownBy(this::openDispute).isInstanceOf(AccessDeniedException.class);
    }
}