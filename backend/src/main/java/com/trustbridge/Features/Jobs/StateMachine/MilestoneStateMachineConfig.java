package com.trustbridge.Features.Jobs.StateMachine;

import com.trustbridge.Domain.Entities.*;
import com.trustbridge.Domain.Enums.*;
import com.trustbridge.Domain.Enums.MilestoneEvent.milestoneEvent;
import com.trustbridge.Domain.Repositories.*;
import com.trustbridge.Features.Jobs.Events.AllMilestonesCompletedEvent;
import com.trustbridge.Features.Jobs.Events.UnlockNextMilestoneEvent;
import com.trustbridge.Features.Notifications.Listeners.MilestoneEmailListener;
import com.trustbridge.Features.Notifications.Services.EmailSenderService;
import com.trustbridge.Features.Payments.Events.MilestoneSubmittedForApprovalEvent;
import com.trustbridge.Features.Payments.Events.PaymentRequestCreatedEvent;
import com.trustbridge.Features.Payments.Provider.PaymentGateway;
import com.trustbridge.Features.Payments.Service.PaymentRequestService;
import com.trustbridge.Security.Actor.GuardHeaders;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.statemachine.StateContext;
import org.springframework.statemachine.action.Action;
import org.springframework.statemachine.config.EnableStateMachineFactory;
import org.springframework.statemachine.config.EnumStateMachineConfigurerAdapter;
import org.springframework.statemachine.config.builders.StateMachineStateConfigurer;
import org.springframework.statemachine.config.builders.StateMachineTransitionConfigurer;
import org.springframework.statemachine.guard.Guard;
import org.springframework.util.Assert;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.logging.Logger;

@Configuration
@EnableStateMachineFactory(name = "MilestoneStateMachineFactory")
@RequiredArgsConstructor
@Slf4j
public class MilestoneStateMachineConfig extends EnumStateMachineConfigurerAdapter<MilestoneStatus, milestoneEvent> {

    private final MilestoneRepository milestoneRepository;
    private final PaymentRequestService paymentRequestService;
    private final EmailSenderService emailService;
    private final UserRepository userRepository;
    private final MilestoneEmailListener milestoneEmailListener;
    private final PaymentGateway paymentGateway;
    private final PaymentRequestRepository paymentRequestRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final MilestoneSubmissionRepository milestoneSubmissionRepository;
    private final DisputeRepository disputeRepository;

    /**
     * Configures the state machine states for the MilestoneStateMachineConfig.
     *
     * The configuration initializes the state machine with the initial state set to
     * {@code milestoneStatus.LOCKED} and registers all possible states defined in the
     * {@code milestoneStatus} enumeration.
     *
     * @param states the {@link StateMachineStateConfigurer} used to configure the states
     *               of the state machine
     * @throws Exception if an error occurs during state machine configuration
     */
    @Override
    public void configure(StateMachineStateConfigurer<MilestoneStatus, milestoneEvent> states) throws Exception {
        states
            .withStates()
            .initial(MilestoneStatus.LOCKED)
            .states(EnumSet.allOf(MilestoneStatus.class));
    }

    /**
     * Configures the state machine transitions for the MilestoneStateMachineConfig.
     *
     * This method defines both unguarded and guarded transitions between different
     * states of the milestone lifecycle, as defined in the {@code milestoneStatus}
     * and {@code milestoneEvent} enumerations. The transitions determine the state
     * changes and associated actions or guards in response to specific events.
     *
     * Unguarded state transitions:
     * - Defines transitions such as moving from LOCKED to AWAITING_PAYMENT on
     *   UNLOCK event, triggering actions like generating a payment request.
     * - Includes transitions like IN_PROGRESS to SUBMITTED on SUBMITTED_WORK
     *   event, and many others as outlined in the method logic.
     *
     * Guarded state transitions:
     * - Ensures conditions are met before a transition occurs, such as validating
     *   funds or client approval.
     * - Triggers actions like notifying a freelancer to start work upon transition.
     * - Includes various guards to ensure transitions are executed only in
     *   specific circumstances, such as mutual agreement or client dispute rights.
     *
     * @param transitions the {@link StateMachineTransitionConfigurer} used to configure
     *                    the state transitions of the state machine
     * @throws Exception if an error occurs during state machine transition configuration
     */
    @Override
    public void configure(StateMachineTransitionConfigurer<MilestoneStatus, milestoneEvent> transitions) throws Exception {
        // UN-GUARDED STATE TRANSITIONS
        transitions
                //Locked -> Awaiting Payment (Unlock)
                .withExternal()
                .source(MilestoneStatus.LOCKED).target(MilestoneStatus.AWAITING_PAYMENT)
                .event(milestoneEvent.UNLOCK)
                .action(generatePaymentRequestAction())
                //In Progress -> Submitted (Submitted Work)
                .and()
                .withExternal()
                .source(MilestoneStatus.IN_PROGRESS).target(MilestoneStatus.SUBMITTED)
                .event(milestoneEvent.SUBMITTED_WORK)
                .action(notifyClientOfFreelancerMilestoneSubmissionAction())
                .action(notifyFreelancerThatClientApprovalEmailHasSentAction())
                //Submitted -> In Progress (Revoke Submission)
                .and()
                .withExternal()
                .source(MilestoneStatus.SUBMITTED).target(MilestoneStatus.IN_PROGRESS)
                .event(milestoneEvent.REVOKE_SUBMISSION)
                //Submitted -> Approved (Work Approved)
                .and()
                .withExternal()
                .source(MilestoneStatus.SUBMITTED).target(MilestoneStatus.APPROVED)
                .event(milestoneEvent.WORK_APPROVED)
                //Awaiting Payment -> Cancelled (Work Cancelled)
                .and()
                .withExternal()
                .source(MilestoneStatus.AWAITING_PAYMENT).target(MilestoneStatus.CANCELLED)
                .event(milestoneEvent.CANCEL_MILESTONE);

        // GUARDED STATE TRANSITIONS
        transitions
                //Awaiting Payment -> In Progress (Funds Deposited)
                .withExternal()
                .source(MilestoneStatus.AWAITING_PAYMENT).target(MilestoneStatus.IN_PROGRESS)
                .event(milestoneEvent.FUNDS_DEPOSITED)
                .guard(isFundedGuard())
                .action(notifyFreelancerToStartAction())
                //Approved -> Paid Out (Funds Paid)
                .and()
                .withExternal()
                .source(MilestoneStatus.APPROVED).target(MilestoneStatus.PAID_OUT)
                .event(milestoneEvent.RELEASE_FUNDS)
                .guard(isClientApprovingMilestoneGuard())
                .action(releaseEscrowFundsAction())
                //Submitted -> Dispute (Work Disputed)
                .and()
                .withExternal()
                .source(MilestoneStatus.SUBMITTED).target(MilestoneStatus.DISPUTED_NEGOTIATION)
                .event(milestoneEvent.RAISE_DISPUTE)
                .guard(onlyClientCanDisputeGuard())
                //Dispute Negotiation -> Dispute Resolved (Dispute Resolved)
                .and()
                .withExternal()
                .source(MilestoneStatus.DISPUTED_NEGOTIATION).target(MilestoneStatus.DISPUTE_RESOLVED)
                .event(milestoneEvent.RESOLVE_DISPUTE)
                .guard(mutualAgreementGuard())
                //Dispute Negotiation -> Dispute Arbitration (Dispute to arbitration)
                .and()
                .withExternal()
                .source(MilestoneStatus.DISPUTED_NEGOTIATION).target(MilestoneStatus.DISPUTE_ARBITRATION)
                .event(milestoneEvent.DISPUTE_TO_ARBITRATION)
                .guard(escalationAllowedGuard());
    }

    String guardExecuted = "Guard executed: ";
    String milestoneIdName = "milestoneId";

    /**
     * FUNDS_DEPOSITED (AWAITING_PAYMENT -> IN_PROGRESS).
     * Passes only when this milestone's payment request is PAID in the database.
     */
    @Bean
    public Guard<MilestoneStatus, milestoneEvent> isFundedGuard() {
        return context -> failClosed("isFundedGuard", () -> {
            UUID milestoneId = context.getMessageHeaders().get(milestoneIdName, UUID.class);
            return milestoneId != null && isPaymentRequestPaid(milestoneId);
        });
    }

    /**
     * RELEASE_FUNDS (APPROVED -> PAID_OUT).
     * Passes only when BOTH are true:
     *  1. this milestone's payment request is PAID (there is money in escrow to release), and
     *  2. the actor is the client on this job: either the logged-in user is job.client, or the
     *     review token matches the latest submission's token (the client's emailed link).
     * The freelancer, a stranger, an unknown id or no identity at all is refused.
     */
    @Bean
    public Guard<MilestoneStatus, milestoneEvent> isClientApprovingMilestoneGuard() {
        return context -> failClosed("isClientApprovingMilestoneGuard", () -> {
            UUID milestoneId = context.getMessageHeaders().get(milestoneIdName, UUID.class);
            if (milestoneId == null) return false;

            Milestones milestone = milestoneRepository.findById(milestoneId).orElse(null);
            if (milestone == null) return false;

            return isPaymentRequestPaid(milestoneId) && isClientOnJob(context, milestone);
        });
    }

    /**
     * Not wired to a transition yet. Checks the previous milestone (sequenceOrder - 1) in the
     * database is PAID_OUT. The first milestone has no previous one, so it passes.
     */
    @Bean
    public Guard<MilestoneStatus, milestoneEvent> isPreviousMilestoneApprovedGuard() {
        return context -> failClosed("isPreviousMilestoneApprovedGuard", () -> {
            UUID milestoneId = context.getMessageHeaders().get(milestoneIdName, UUID.class);
            if (milestoneId == null) return false;

            Milestones milestone = milestoneRepository.findById(milestoneId).orElse(null);
            if (milestone == null || milestone.getSequenceOrder() == null) return false;
            if (milestone.getSequenceOrder() <= 1) return true;

            return milestoneRepository.findAllByJobId(milestone.getJob().getId()).stream()
                    .filter(m -> m.getSequenceOrder() != null && m.getSequenceOrder() == milestone.getSequenceOrder() - 1)
                    .findFirst()
                    .map(previous -> previous.getStatus() == MilestoneStatus.PAID_OUT)
                    .orElse(false);
        });
    }

    /**
     * RAISE_DISPUTE (SUBMITTED -> DISPUTED_NEGOTIATION).
     * Passes when the actor is the client on this job (logged in as job.client, or a valid review
     * token), or the logged-in user is an ADMIN. The role is read from the user row, not a header.
     */
    @Bean
    public Guard<MilestoneStatus, milestoneEvent> onlyClientCanDisputeGuard() {
        return context -> failClosed("onlyClientCanDisputeGuard", () -> {
            UUID milestoneId = context.getMessageHeaders().get(milestoneIdName, UUID.class);
            if (milestoneId == null) return false;

            Milestones milestone = milestoneRepository.findById(milestoneId).orElse(null);
            if (milestone == null) return false;

            return isClientOnJob(context, milestone) || isAdmin(context);
        });
    }

    /**
     * RESOLVE_DISPUTE (DISPUTED_NEGOTIATION -> DISPUTE_RESOLVED).
     * Passes when the dispute row says both sides agreed (RESOLVED_AGREEMENT), or the logged-in
     * user is an ADMIN.
     */
    @Bean
    public Guard<MilestoneStatus, milestoneEvent> mutualAgreementGuard() {
        return context -> failClosed("mutualAgreementGuard", () -> {
            UUID milestoneId = context.getMessageHeaders().get(milestoneIdName, UUID.class);
            if (milestoneId == null) return false;

            boolean agreed = disputeRepository.findByMilestoneId(milestoneId)
                    .map(d -> d.getState() == DisputeState.RESOLVED_AGREEMENT)
                    .orElse(false);

            return agreed || isAdmin(context);
        });
    }

    /**
     * DISPUTE_TO_ARBITRATION (DISPUTED_NEGOTIATION -> DISPUTE_ARBITRATION).
     * Passes when the dispute row was opened at least 3 days ago. The clock is the server's,
     * not a "daysInNegotiation" number from the caller.
     */
    @Bean
    public Guard<MilestoneStatus, milestoneEvent> escalationAllowedGuard() {
        return context -> failClosed("escalationAllowedGuard", () -> {
            UUID milestoneId = context.getMessageHeaders().get(milestoneIdName, UUID.class);
            if (milestoneId == null) return false;

            return disputeRepository.findByMilestoneId(milestoneId)
                    .map(Dispute::getCreatedAt)
                    .map(openedAt -> !openedAt.plusDays(ESCALATION_WAIT_DAYS).isAfter(OffsetDateTime.now(clock)))
                    .orElse(false);
        });
    }

    static final int ESCALATION_WAIT_DAYS = 3;

    /** Server clock. A field so a test can swap it with ReflectionTestUtils. */
    Clock clock = Clock.systemUTC();

    private boolean isPaymentRequestPaid(UUID milestoneId) {
        return paymentRequestRepository.findByMilestoneIdAndStatus(milestoneId, PaymentRequestStatus.PAID).isPresent();
    }

    /**
     * True when the request proves it comes from this job's client:
     *  - actorId is the job's client id (and that user exists), or
     *  - reviewToken equals the latest submission's review token.
     */
    private boolean isClientOnJob(StateContext<MilestoneStatus, milestoneEvent> context, Milestones milestone) {
        Users client = milestone.getJob() == null ? null : milestone.getJob().getClient();

        UUID actorId = context.getMessageHeaders().get(GuardHeaders.ACTOR_ID, UUID.class);
        if (actorId != null && client != null && client.getId() != null) {
            boolean actorIsClient = userRepository.findById(actorId)
                    .map(user -> user.getId().equals(client.getId()))
                    .orElse(false);
            if (actorIsClient) return true;
        }

        String reviewToken = context.getMessageHeaders().get(GuardHeaders.REVIEW_TOKEN, String.class);
        if (reviewToken != null && !reviewToken.isBlank()) {
            return milestoneSubmissionRepository.findTopByMilestoneIdOrderByCreatedAtDesc(milestone.getId())
                    .map(MilestoneSubmissions::getReviewToken)
                    .filter(Objects::nonNull)
                    .map(expected -> MessageDigest.isEqual(
                            expected.getBytes(StandardCharsets.UTF_8),
                            reviewToken.getBytes(StandardCharsets.UTF_8)))
                    .orElse(false);
        }

        return false;
    }

    private boolean isAdmin(StateContext<MilestoneStatus, milestoneEvent> context) {
        UUID actorId = context.getMessageHeaders().get(GuardHeaders.ACTOR_ID, UUID.class);
        if (actorId == null) return false;

        return userRepository.findById(actorId)
                .map(user -> user.getUserRole() == UserRole.role.ADMIN)
                .orElse(false);
    }

    /** Runs a guard check. Any exception (bad header type, DB error) blocks the transition. */
    private boolean failClosed(String guardName, Supplier<Boolean> check) {
        try {
            boolean allowed = Boolean.TRUE.equals(check.get());
            log.info("{} {}: {}", guardExecuted, guardName, allowed ? "ALLOWED" : "BLOCKED");
            return allowed;
        } catch (RuntimeException e) {
            log.warn("{} {}: BLOCKED (error: {})", guardExecuted, guardName, e.getMessage());
            return false;
        }
    }

    /**
     * Executes the action to generate a payment request for a specific milestone.
     *
     * This action is triggered within the state machine flow and involves the following:
     * - Retrieves the milestone ID from the message headers.
     * - Validates the milestone ID to ensure it is not null.
     * - Fetches the milestone details from the repository.
     * - Invokes the payment request creation service for the specified milestone.
     *
     * Logs the successful creation of the payment request or throws a runtime exception
     * if the milestone is not found or an error occurs during the process.
     *
     * @return the action that performs payment request generation within the state machine.
     */
    @Bean
    public Action<MilestoneStatus, milestoneEvent> generatePaymentRequestAction() {
        return context -> {
            UUID milestoneId = context.getMessageHeaders().get( milestoneIdName , UUID.class);

            Assert .notNull(milestoneId, "Milestone ID is required!");
            Milestones milestones = milestoneRepository.findById(milestoneId)
                    .orElseThrow(() -> new RuntimeException("Milestone not found!"));

            try {
                PaymentRequest paymentRequest = paymentRequestService.createPaymentRequest(milestones);
                eventPublisher.publishEvent(new PaymentRequestCreatedEvent(this, milestoneId, paymentRequest.getId()));
                Logger.getLogger("ACTION FIRED: Payment Request created for Milestone " + milestoneId);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        };
    }

    @Bean
    public Action<MilestoneStatus, milestoneEvent> notifyClientOfMilestonePaymentRequestAction() {
        return context -> {
            UUID milestoneId = context.getMessageHeaders().get(milestoneIdName, UUID.class);
            Milestones milestone = milestoneRepository.findById(milestoneId)
                    .orElseThrow(() -> new RuntimeException("Milestone not found!"));
            Users client = userRepository.getById(context.getMessageHeaders().get("clientUserId", UUID.class));
            String body = "This is a test email body. Please ignore. MILESTONE PAYMENT REQUEST";
        };
    }

    /**
     * Creates an action that notifies a freelancer to start working on a milestone.
     *
     * The method performs the following operations:
     * - Retrieves the milestone ID and freelancer ID from the message headers.
     * - Fetches the freelancer's details using the freelancer ID.
     * - Prepares an email body and sends an email notification to the freelancer,
     *   indicating that funds have been secured and they can begin work on the milestone.
     *
     * Additionally, logs a message indicating that the notification action was fired.
     *
     * @return an {@link Action} instance that sends an email notification to the freelancer
     *         to initiate work on the funded milestone.
     */
    @Bean
    public Action<MilestoneStatus, milestoneEvent> notifyFreelancerToStartAction() {
        return context -> {
            UUID milestoneId = context.getMessageHeaders().get(milestoneIdName, UUID.class);
            Users freelancer = userRepository.getById(context.getMessageHeaders().get("freelancerId", UUID.class));
            String body = "This is a test email body. Please ignore. JOB ACCEPTED (CHANGE THIS TO HTML BODY)";





            emailService.sendEmail(
                    freelancer.getEmail(),
                    "x",
                    EmailTemplateType.MILESTONE_FUNDED_FREELANCER_NOTICE,
                    body,
                    milestoneId
            );
            Logger.getLogger("ACTION FIRED: Sending email to Freelancer for Milestone " + milestoneId + " - Funds secured, start working!");
        };
    }

    @Bean
    public Action<MilestoneStatus, milestoneEvent> notifyClientOfFreelancerMilestoneSubmissionAction() {
        return context -> {
            UUID milestoneId = context.getMessageHeaders().get(milestoneIdName, UUID.class);

            MilestoneSubmittedForApprovalEvent event = new MilestoneSubmittedForApprovalEvent(this, milestoneId);

            milestoneEmailListener.handleFreelancerSubmitsMilestone(event);
            Logger.getLogger("ACTION FIRED: Sending email to Client for Milestone " + milestoneId + " - Freelancer submitted!");
        };
    }

    @Bean
    public Action<MilestoneStatus, milestoneEvent> notifyFreelancerThatClientApprovalEmailHasSentAction() {
        return context -> {
            UUID milestoneId = context.getMessageHeaders().get(milestoneIdName, UUID.class);

            MilestoneSubmittedForApprovalEvent event = new MilestoneSubmittedForApprovalEvent(this, milestoneId);

            milestoneEmailListener.handleClientReceivesWaitingForApprovalEmail(event);
            Logger.getLogger("ACTION FIRED: Sending email to Freelancer for Milestone " + milestoneId + " - Client approval email sent!");
        };
    }

    @Bean
    public Action<MilestoneStatus, milestoneEvent> releaseEscrowFundsAction() {
        return context -> {
            UUID milestoneId = context.getMessageHeaders().get(milestoneIdName, UUID.class);
            Milestones milestone = milestoneRepository.findById(milestoneId)
                    .orElseThrow(() -> new RuntimeException("Milestone not found!"));

            Users freelancer = milestone.getJob().getFreelancer();

            PaymentRequest paymentRequest = paymentRequestRepository.findByMilestone(milestone)
                    .orElseThrow(() -> new RuntimeException("Payment request not found!"));

            try {
                paymentGateway.releaseFunds(milestone, freelancer, paymentRequest);
                Logger.getLogger("ACTION FIRED: Escrow funds released for Milestone " + milestoneId);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }


        };
    }

    @Bean
    public Action<MilestoneStatus, milestoneEvent> checkAndUnlockNextMilestone() {
        return context -> {
            UUID milestoneId = context.getMessageHeaders().get(milestoneIdName, UUID.class);
            Milestones milestone = milestoneRepository.findById(milestoneId)
                    .orElseThrow(() -> new RuntimeException("Milestone not found!"));

            if (!isJobCompleted(milestone)) {
                if (!isThisTheLastMilestone(milestone)) {
                    eventPublisher.publishEvent(new UnlockNextMilestoneEvent(this, milestone.getJob().getId()));
                } else {
                    // Last milestone just paid out — kick off the job's own completion pipeline
                    // rather than trying to unlock a next milestone that doesn't exist.
                    eventPublisher.publishEvent(new AllMilestonesCompletedEvent(this, milestone.getJob().getId()));
                }
            }
        };
    }

    private boolean isJobCompleted(Milestones milestone) {
        return milestone.getJob().getStatus() == JobStatus.jobStatus.PAID_OUT;
    }

    /**
     * Determines whether the given milestone is the last one in its job's sequence,
     * by comparing it against the milestone with the highest {@code sequenceOrder} for that job.
     */
    private boolean isThisTheLastMilestone(Milestones milestone) {
        return milestoneRepository.findFirstByJobIdOrderBySequenceOrderDesc(milestone.getJob().getId())
                .map(lastMilestone -> lastMilestone.getId().equals(milestone.getId()))
                .orElse(false);
    }
}
