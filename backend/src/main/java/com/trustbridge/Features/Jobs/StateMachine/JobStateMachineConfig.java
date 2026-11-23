package com.trustbridge.Features.Jobs.StateMachine;

import com.trustbridge.Domain.Entities.Jobs;
import com.trustbridge.Domain.Entities.Milestones;
import com.trustbridge.Domain.Enums.JobEvent.*;
import com.trustbridge.Domain.Enums.JobStatus.*;
import com.trustbridge.Domain.Enums.MilestoneStatus;
import com.trustbridge.Domain.Enums.PaymentRequestStatus;
import com.trustbridge.Domain.Repositories.JobRepository;
import com.trustbridge.Domain.Repositories.MilestoneRepository;
import com.trustbridge.Domain.Repositories.PaymentRequestRepository;
import com.trustbridge.Features.Jobs.Events.UnlockNextMilestoneEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.statemachine.action.Action;
import org.springframework.statemachine.config.EnableStateMachineFactory;
import org.springframework.statemachine.config.EnumStateMachineConfigurerAdapter;
import org.springframework.statemachine.config.builders.StateMachineStateConfigurer;
import org.springframework.statemachine.config.builders.StateMachineTransitionConfigurer;
import org.springframework.statemachine.guard.Guard;

import java.util.EnumSet;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

@Slf4j
@Configuration
@EnableStateMachineFactory(name = "JobStateMachineFactory")
@RequiredArgsConstructor
public class JobStateMachineConfig extends EnumStateMachineConfigurerAdapter<jobStatus, jobEvent> {

    final private JobRepository jobRepository;
    final private MilestoneRepository milestoneRepository;
    final private ApplicationEventPublisher eventPublisher;
    private final PaymentRequestRepository paymentRequestRepository;

    /**
     * Configures the state machine with the initial state and all possible job states.
     * This method sets the initial state to {@code DRAFT} and registers all states
     * from the {@link jobStatus} enum to be part of the state machine.
     *
     * @param states the {@link StateMachineStateConfigurer} used to define states
     *               and initial configuration for the state machine
     * @throws Exception if an error occurs during state configuration
     */
    @Override
    public void configure(StateMachineStateConfigurer<jobStatus, jobEvent> states) throws Exception {
        states
                .withStates()
                .initial(jobStatus.DRAFT)
                .states(EnumSet.allOf(jobStatus.class));
    }

    /**
     * Configures the state transitions for the job state machine.
     * This method defines both unguarded and guarded transitions between states
     * based on specific events and optional guards or actions.
     *
     * @param transitions the {@link StateMachineTransitionConfigurer} used to define state transitions
     * @throws Exception if an error occurs during transition configuration
     */
    @Override
    public void configure(StateMachineTransitionConfigurer<jobStatus, jobEvent> transitions) throws Exception {
        // UN-GUARDED STATE TRANSITIONS
        transitions
                // Draft -> Pending Acceptance (Publish Offer)
                .withExternal()
                .source(jobStatus.DRAFT).target(jobStatus.PENDING_ACCEPTANCE)
                .event(jobEvent.PUBLISH_OFFER)
                // Draft -> Cancelled unsure if this should   actually exist
                .and()
                .withExternal()
                .source(jobStatus.DRAFT).target(jobStatus.CANCELLED)
                .event(jobEvent.CANCEL_JOB)
                // Pending Acceptance -> Cancelled (Client Cancels)
                .and()
                .withExternal()
                .source(jobStatus.PENDING_ACCEPTANCE).target(jobStatus.CANCELLED)
                .event(jobEvent.CANCEL_JOB)
                // Draft -> Submitted (Job Submission)
                .and()
                .withExternal()
                .source(jobStatus.SUBMITTED).target(jobStatus.IN_PROGRESS)
                .event(jobEvent.SUBMISSION_REVOKED);

        // GUARDED STATE TRANSITIONS
        transitions
                .withExternal()
                .source(jobStatus.PENDING_ACCEPTANCE).target(jobStatus.AWAITING_PAYMENT)
                .event(jobEvent.ACCEPT_OFFER)
                .guard(isClientApprovingGuard())
                .action(triggerFirstMilestoneAction())
                .and()
                .withExternal()
                .source(jobStatus.AWAITING_PAYMENT).target(jobStatus.IN_PROGRESS)
                .event(jobEvent.FUNDS_DEPOSITED)
                .guard(firstMilestonePaidGuard())
                .and()
                .withExternal()
                .source(jobStatus.IN_PROGRESS).target(jobStatus.SUBMITTED)
                .event(jobEvent.ALL_MILESTONES_COMPLETED)
                .guard(allMilestonesCompleted())
                .and()
                .withExternal()
                .source(jobStatus.SUBMITTED).target(jobStatus.APPROVED)
                .event(jobEvent.JOB_APPROVED)
                .guard(jobApprovedGuard())
                .and()
                .withExternal()
                .source(jobStatus.APPROVED).target(jobStatus.PAID_OUT)
                .event(jobEvent.RELEASE_PAYMENT)
                .guard(jobReleasedGuard())
                .and()
                .withExternal()
                .source(jobStatus.SUBMITTED).target(jobStatus.DISPUTED)
                .event(jobEvent.RAISE_DISPUTE)
                .guard(jobDisputedGuard())
                .and()
                .withExternal()
                .source(jobStatus.IN_PROGRESS).target(jobStatus.DISPUTED) // <--- Add this block!
                .event(jobEvent.RAISE_DISPUTE)
                .guard(jobDisputedGuard())
                .and()
                .withExternal()
                .source(jobStatus.DISPUTED).target(jobStatus.IN_PROGRESS)
                .event(jobEvent.RESOLVE_DISPUTE)
                .guard(jobDisputeResolvedGuard());
    }

    /**
     * Guard implementation that checks whether a client is authorized to approve a job
     * by validating the provided invite token against the expected token stored in the database.
     * This method follows a zero-trust policy to ensure only the correct token allows progression.
     * The guard retrieves the `jobId` and `inviteToken` from the context's message headers,
     * fetches the associated job from the database using the `jobId`, and compares the provided
     * token with the job's stored token. If any validation fails, the guard blocks progress.
     * @return a Guard instance that evaluates to {@code true} if the client is authorized to proceed,
     *         or {@code false} otherwise.
     */
    @Bean
    public Guard<jobStatus, jobEvent> isClientApprovingGuard() {
        return context -> {
            UUID jobId = context.getMessageHeaders().get("jobId", UUID.class);
            String providedToken = context.getMessageHeaders().get("inviteToken", String.class);

            if (jobId == null || providedToken == null) {
                return false;
            }

            Jobs job = jobRepository.findById(jobId).orElse(null);

            if (job == null) {
                return false;
            }

            // 3. ZERO-TRUST CHECK: Does the provided token exactly match the database token?
            boolean isAuthorized = providedToken.equals(job.getInviteToken());

            if (!isAuthorized) {
                System.out.println("🚨 SECURITY ALERT: Invalid token used attempting to release funds on Job " + jobId);
            }

            return isAuthorized;
        };
    }

    /**
     * Triggers the action of progressing the locked milestone state for a specific job.
     * This method attempts to activate the next locked milestone for the job by
     * utilizing the {@code milestoneStateService}. The job identifier is retrieved
     * from the state machine context's message headers. If the identifier is found,
     * the next locked milestone is unlocked for the job.
     *
     * @return an {@link Action} implementation that processes the state machine context
     *         to unlock the next milestone for the job when invoked.
     */
    @Bean
    public Action<jobStatus, jobEvent> triggerFirstMilestoneAction() {
        return context -> {
            UUID jobId = context.getMessageHeaders().get("jobId", UUID.class);

            if (jobId != null) {
                eventPublisher.publishEvent(new UnlockNextMilestoneEvent(this, jobId));
            }
        };
    }

    /**
     * FUNDS_DEPOSITED (AWAITING_PAYMENT -> IN_PROGRESS).
     * Passes only when the job's first milestone (lowest sequenceOrder) has a PAID payment
     * request in the database. No "isFunded" or "milestoneSequence" header is read.
     */
    @Bean
    public Guard<jobStatus, jobEvent> firstMilestonePaidGuard() {
        return context -> failClosed("firstMilestonePaidGuard", () -> {
            UUID jobId = context.getMessageHeaders().get("jobId", UUID.class);
            if (jobId == null) return false;

            return milestoneRepository.findFirstByJobIdOrderBySequenceOrderAsc(jobId)
                    .flatMap(first -> paymentRequestRepository.findByMilestoneIdAndStatus(first.getId(), PaymentRequestStatus.PAID))
                    .isPresent();
        });
    }

    /**
     * Guard implementation that verifies whether all milestones associated with a job
     * have been completed. This method implements a zero-trust policy by checking if
     * there are any milestones for the job that are not in a finished state.
     * The guard retrieves the `jobId` from the state machine context's message headers
     * and queries the `milestoneRepository` to check for unfinished milestones. If no
     * milestones are in an unfinished state, this guard passes (returns {@code true}).
     * Otherwise, it blocks the state transition (returns {@code false}).
     *
     * @return a Guard instance that evaluates to {@code true} if all milestones are completed
     *         (no milestones in unfinished states), or {@code false} otherwise.
     */
    @Bean
    public Guard<jobStatus, jobEvent> allMilestonesCompleted() {
        return context -> {
            UUID jobId = context.getMessageHeaders().get("jobId", UUID.class);
            if (jobId == null) return false;

            // Zero-Trust Check: Does the database contain ANY milestones for this job
            // that are NOT in a finished state?
            boolean hasUnfinishedWork = milestoneRepository.existsByJobIdAndStatusNotIn(
                    jobId,
                    List.of(MilestoneStatus.PAID_OUT, MilestoneStatus.CANCELLED)
            );

            // If there is NO unfinished work, the guard passes (returns true)
            return !hasUnfinishedWork;
        };
    }

    /**
     * JOB_APPROVED (SUBMITTED -> APPROVED).
     * The client approves a job by approving its last milestone, so this passes only when the
     * database shows every milestone is PAID_OUT or CANCELLED and at least one was PAID_OUT.
     */
    @Bean
    public Guard<jobStatus, jobEvent> jobApprovedGuard() {
        return context -> failClosed("jobApprovedGuard", () -> {
            UUID jobId = context.getMessageHeaders().get("jobId", UUID.class);
            return jobId != null && everyMilestonePaidOut(jobId);
        });
    }

    /**
     * RELEASE_PAYMENT (APPROVED -> PAID_OUT).
     * Escrow is released per milestone, so the job is paid out only when the database shows
     * the same thing: every milestone PAID_OUT or CANCELLED, at least one PAID_OUT.
     */
    @Bean
    public Guard<jobStatus, jobEvent> jobReleasedGuard() {
        return context -> failClosed("jobReleasedGuard", () -> {
            UUID jobId = context.getMessageHeaders().get("jobId", UUID.class);
            return jobId != null && everyMilestonePaidOut(jobId);
        });
    }

    /**
     * RAISE_DISPUTE (SUBMITTED or IN_PROGRESS -> DISPUTED).
     * Passes only when one of the job's milestones is already in a dispute state in the
     * database. Who may dispute is checked on the milestone's RAISE_DISPUTE guard.
     */
    @Bean
    public Guard<jobStatus, jobEvent> jobDisputedGuard() {
        return context -> failClosed("jobDisputedGuard", () -> {
            UUID jobId = context.getMessageHeaders().get("jobId", UUID.class);
            return jobId != null && hasMilestoneInDispute(jobId);
        });
    }

    /**
     * RESOLVE_DISPUTE (DISPUTED -> IN_PROGRESS).
     * Passes only when no milestone on the job is still in a dispute state in the database.
     */
    @Bean
    public Guard<jobStatus, jobEvent> jobDisputeResolvedGuard() {
        return context -> failClosed("jobDisputeResolvedGuard", () -> {
            UUID jobId = context.getMessageHeaders().get("jobId", UUID.class);
            return jobId != null && !milestoneRepository.findAllByJobId(jobId).isEmpty() && !hasMilestoneInDispute(jobId);
        });
    }

    private static final List<MilestoneStatus> DISPUTE_STATES =
            List.of(MilestoneStatus.DISPUTED_NEGOTIATION, MilestoneStatus.DISPUTE_ARBITRATION);

    private boolean everyMilestonePaidOut(UUID jobId) {
        List<Milestones> milestones = milestoneRepository.findAllByJobId(jobId);
        if (milestones.isEmpty()) return false;

        boolean allFinished = milestones.stream()
                .allMatch(m -> m.getStatus() == MilestoneStatus.PAID_OUT || m.getStatus() == MilestoneStatus.CANCELLED);
        boolean anyPaidOut = milestones.stream().anyMatch(m -> m.getStatus() == MilestoneStatus.PAID_OUT);
        return allFinished && anyPaidOut;
    }

    private boolean hasMilestoneInDispute(UUID jobId) {
        return milestoneRepository.findAllByJobId(jobId).stream()
                .anyMatch(m -> DISPUTE_STATES.contains(m.getStatus()));
    }

    /** Any exception (bad header type, DB error) blocks the transition. */
    private boolean failClosed(String guardName, Supplier<Boolean> check) {
        try {
            boolean allowed = Boolean.TRUE.equals(check.get());
            log.info("Guard {}: {}", guardName, allowed ? "ALLOWED" : "BLOCKED");
            return allowed;
        } catch (RuntimeException e) {
            log.warn("Guard {}: BLOCKED (error: {})", guardName, e.getMessage());
            return false;
        }
    }
}
