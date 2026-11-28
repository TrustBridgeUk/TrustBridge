package com.trustbridge.Features.Jobs.StateMachine;

import com.trustbridge.Domain.Entities.Jobs;
import com.trustbridge.Domain.Entities.Milestones;
import com.trustbridge.Domain.Entities.PaymentRequest;
import com.trustbridge.Domain.Enums.JobEvent.jobEvent;
import com.trustbridge.Domain.Enums.JobStatus.jobStatus;
import com.trustbridge.Domain.Enums.MilestoneStatus;
import com.trustbridge.Domain.Enums.PaymentRequestStatus;
import com.trustbridge.Domain.Repositories.JobRepository;
import com.trustbridge.Domain.Repositories.MilestoneRepository;
import com.trustbridge.Domain.Repositories.PaymentRequestRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.statemachine.StateMachine;
import org.springframework.statemachine.config.StateMachineFactory;
import org.springframework.statemachine.support.DefaultStateMachineContext;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Backlog 5.2: every job guard must check the database, not a flag the caller sets.
 *
 * Runs the REAL {@link JobStateMachineConfig} with faked repositories. Outcomes are judged by
 * the state the machine ends up in (a guard refusal still reports ACCEPTED). The old forged
 * flags ("isJobApproved", "isJobReleased", "isFunded", "isJobDisputed") are sent on purpose to
 * prove they no longer matter.
 */
class JobGuardsTest {

    private static final String INVITE_TOKEN = "real-invite-token";

    private AnnotationConfigApplicationContext context;
    private StateMachineFactory<jobStatus, jobEvent> factory;

    private Jobs job;
    private final List<Milestones> milestones = new ArrayList<>();
    private PaymentRequestStatus firstMilestonePayment; // null = no payment request

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        job = Jobs.builder()
                .title("Website build")
                .description("Build a website")
                .currency("GBP")
                .totalAmount(new BigDecimal("1000.00"))
                .inviteToken(INVITE_TOKEN)
                .status(jobStatus.PENDING_ACCEPTANCE)
                .build();
        job.setId(UUID.randomUUID());
        milestones.clear();
        firstMilestonePayment = null;

        JobRepository jobRepository = mock(JobRepository.class);
        when(jobRepository.findById(job.getId())).thenAnswer(inv -> Optional.of(job));

        MilestoneRepository milestoneRepository = mock(MilestoneRepository.class);
        when(milestoneRepository.findAllByJobId(job.getId())).thenAnswer(inv -> List.copyOf(milestones));
        when(milestoneRepository.findFirstByJobIdOrderBySequenceOrderAsc(job.getId()))
                .thenAnswer(inv -> milestones.stream().findFirst());
        when(milestoneRepository.existsByJobIdAndStatusNotIn(eq(job.getId()), anyCollection()))
                .thenAnswer(inv -> {
                    Collection<MilestoneStatus> done = inv.getArgument(1);
                    return milestones.stream().anyMatch(m -> !done.contains(m.getStatus()));
                });

        PaymentRequestRepository paymentRequestRepository = mock(PaymentRequestRepository.class);
        when(paymentRequestRepository.findByMilestoneIdAndStatus(any(UUID.class), any(PaymentRequestStatus.class)))
                .thenAnswer(inv -> {
                    boolean isFirst = !milestones.isEmpty() && milestones.get(0).getId().equals(inv.getArgument(0));
                    if (!isFirst || firstMilestonePayment != inv.getArgument(1)) return Optional.empty();
                    return Optional.of(PaymentRequest.builder().status(firstMilestonePayment).build());
                });

        context = new AnnotationConfigApplicationContext();
        context.registerBean(JobRepository.class, () -> jobRepository);
        context.registerBean(MilestoneRepository.class, () -> milestoneRepository);
        context.registerBean(PaymentRequestRepository.class, () -> paymentRequestRepository);
        context.register(JobStateMachineConfig.class);
        context.refresh();

        factory = context.getBean("JobStateMachineFactory", StateMachineFactory.class);
    }

    @AfterEach
    void tearDown() {
        context.close();
    }

    // ACCEPT_OFFER (PENDING_ACCEPTANCE -> AWAITING_PAYMENT)

    @Test
    void acceptOffer_wrongInviteToken_isBlocked() {
        assertBlocked(send(jobStatus.PENDING_ACCEPTANCE, jobEvent.ACCEPT_OFFER, Map.of("inviteToken", "guessed")));
    }

    @Test
    void acceptOffer_noInviteToken_isBlocked() {
        assertBlocked(send(jobStatus.PENDING_ACCEPTANCE, jobEvent.ACCEPT_OFFER, Map.of("isClientApproving", true)));
    }

    @Test
    void acceptOffer_correctInviteToken_isAllowed() {
        assertAllowed(send(jobStatus.PENDING_ACCEPTANCE, jobEvent.ACCEPT_OFFER, Map.of("inviteToken", INVITE_TOKEN)));
    }

    // FUNDS_DEPOSITED (AWAITING_PAYMENT -> IN_PROGRESS)

    @Test
    void fundsDeposited_forgedFlag_isBlocked_whenFirstMilestoneUnpaid() {
        givenMilestone(MilestoneStatus.AWAITING_PAYMENT);
        firstMilestonePayment = PaymentRequestStatus.PENDING;

        assertBlocked(send(jobStatus.AWAITING_PAYMENT, jobEvent.FUNDS_DEPOSITED, Map.of("isFunded", true)));
    }

    @Test
    void fundsDeposited_isAllowed_whenFirstMilestonePaid() {
        givenMilestone(MilestoneStatus.AWAITING_PAYMENT);
        firstMilestonePayment = PaymentRequestStatus.PAID;

        assertAllowed(send(jobStatus.AWAITING_PAYMENT, jobEvent.FUNDS_DEPOSITED, Map.of()));
    }

    // ALL_MILESTONES_COMPLETED (IN_PROGRESS -> SUBMITTED)

    @Test
    void allMilestonesCompleted_isBlocked_whenAMilestoneIsUnfinished() {
        givenMilestone(MilestoneStatus.PAID_OUT);
        givenMilestone(MilestoneStatus.IN_PROGRESS);

        assertBlocked(send(jobStatus.IN_PROGRESS, jobEvent.ALL_MILESTONES_COMPLETED, Map.of()));
    }

    @Test
    void allMilestonesCompleted_isBlocked_whenJobHasNoMilestones() {
        assertBlocked(send(jobStatus.IN_PROGRESS, jobEvent.ALL_MILESTONES_COMPLETED, Map.of()));
    }

    @Test
    void allMilestonesCompleted_isAllowed_whenAllPaidOut() {
        givenMilestone(MilestoneStatus.PAID_OUT);

        assertAllowed(send(jobStatus.IN_PROGRESS, jobEvent.ALL_MILESTONES_COMPLETED, Map.of()));
    }

    // JOB_APPROVED (SUBMITTED -> APPROVED)

    @Test
    void jobApproved_forgedFlag_isBlocked_whenMilestoneNotPaidOut() {
        givenMilestone(MilestoneStatus.APPROVED);

        assertBlocked(send(jobStatus.SUBMITTED, jobEvent.JOB_APPROVED, Map.of("isJobApproved", true)));
    }

    @Test
    void jobApproved_isBlocked_whenEveryMilestoneCancelled() {
        givenMilestone(MilestoneStatus.CANCELLED);

        assertBlocked(send(jobStatus.SUBMITTED, jobEvent.JOB_APPROVED, Map.of()));
    }

    @Test
    void jobApproved_isAllowed_whenAllPaidOut() {
        givenMilestone(MilestoneStatus.PAID_OUT);

        assertAllowed(send(jobStatus.SUBMITTED, jobEvent.JOB_APPROVED, Map.of()));
    }

    // RELEASE_PAYMENT (APPROVED -> PAID_OUT)

    @Test
    void releasePayment_forgedFlag_isBlocked_whenMilestoneNotPaidOut() {
        givenMilestone(MilestoneStatus.APPROVED);

        assertBlocked(send(jobStatus.APPROVED, jobEvent.RELEASE_PAYMENT, Map.of("isJobReleased", true)));
    }

    @Test
    void releasePayment_isAllowed_whenAllPaidOut() {
        givenMilestone(MilestoneStatus.PAID_OUT);

        assertAllowed(send(jobStatus.APPROVED, jobEvent.RELEASE_PAYMENT, Map.of()));
    }

    // RAISE_DISPUTE (SUBMITTED / IN_PROGRESS -> DISPUTED)

    @Test
    void raiseDispute_forgedFlag_isBlocked_whenNoMilestoneDisputed() {
        givenMilestone(MilestoneStatus.SUBMITTED);

        assertBlocked(send(jobStatus.IN_PROGRESS, jobEvent.RAISE_DISPUTE, Map.of("isJobDisputed", true)));
    }

    @Test
    void raiseDispute_isAllowed_whenAMilestoneIsDisputed() {
        givenMilestone(MilestoneStatus.DISPUTED_NEGOTIATION);

        assertAllowed(send(jobStatus.IN_PROGRESS, jobEvent.RAISE_DISPUTE, Map.of()));
    }

    // RESOLVE_DISPUTE (DISPUTED -> IN_PROGRESS)

    @Test
    void resolveDispute_isBlocked_whileAMilestoneIsStillDisputed() {
        givenMilestone(MilestoneStatus.DISPUTE_ARBITRATION);

        assertBlocked(send(jobStatus.DISPUTED, jobEvent.RESOLVE_DISPUTE, Map.of("isDisputeResolved", true)));
    }

    @Test
    void resolveDispute_isAllowed_whenNoMilestoneDisputed() {
        givenMilestone(MilestoneStatus.DISPUTE_RESOLVED);

        assertAllowed(send(jobStatus.DISPUTED, jobEvent.RESOLVE_DISPUTE, Map.of()));
    }

    // helpers

    private void givenMilestone(MilestoneStatus status) {
        Milestones m = Milestones.builder()
                .job(job)
                .title("Milestone " + (milestones.size() + 1))
                .amount(new BigDecimal("500.00"))
                .sequenceOrder(milestones.size() + 1)
                .status(status)
                .build();
        m.setId(UUID.randomUUID());
        milestones.add(m);
    }

    private Outcome send(jobStatus from, jobEvent event, Map<String, Object> headers) {
        StateMachine<jobStatus, jobEvent> sm = factory.getStateMachine(UUID.randomUUID().toString());
        sm.stopReactively().block();
        sm.getStateMachineAccessor().doWithAllRegions(accessor ->
                accessor.resetStateMachineReactively(new DefaultStateMachineContext<>(from, null, null, null)).block());
        sm.startReactively().block();

        MessageBuilder<jobEvent> message = MessageBuilder.withPayload(event).setHeader("jobId", job.getId());
        headers.forEach(message::setHeader);

        sm.sendEvent(Mono.just(message.build())).blockLast();
        return new Outcome(from, sm.getState().getId());
    }

    private record Outcome(jobStatus from, jobStatus after) {
    }

    private static void assertAllowed(Outcome result) {
        assertThat(result.after()).as("transition should move the job out of " + result.from()).isNotEqualTo(result.from());
    }

    private static void assertBlocked(Outcome result) {
        assertThat(result.after()).as("guard should block: job must stay in " + result.from()).isEqualTo(result.from());
    }
}
