package com.trustbridge.Features.Jobs.Service;

import com.trustbridge.Domain.Entities.Jobs;
import com.trustbridge.Domain.Entities.Milestones;
import com.trustbridge.Domain.Enums.MilestoneStatus;
import com.trustbridge.Domain.Repositories.JobRepository;
import com.trustbridge.Domain.Enums.JobStatus.*;
import com.trustbridge.Domain.Enums.JobEvent.*;
import com.trustbridge.Domain.Repositories.MilestoneRepository;
import com.trustbridge.Features.Jobs.StateMachine.Interceptors.JobStateChangeInterceptor;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.statemachine.StateMachine;
import org.springframework.statemachine.config.StateMachineFactory;
import org.springframework.statemachine.support.DefaultStateMachineContext;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class JobStateService {

    private final JobRepository jobRepository;
    private final MilestoneRepository milestoneRepository;

    StateMachineFactory<jobStatus, jobEvent> stateMachineFactory;

    JobStateChangeInterceptor jobInterceptor;


    /**
     * Builds and initialises a state machine for the specified job.
     *
     * @param jobId the unique identifier of the job for which the state machine is being built
     * @return the initialised state machine for managing the job's state transitions
     * @throws RuntimeException if the job with the provided ID is not found
     */
    private StateMachine<jobStatus, jobEvent>  buildStateMachine(UUID jobId) {

        Jobs job = jobRepository.findById(jobId)
                .orElseThrow(() -> new RuntimeException("Job not found!"));

        StateMachine<jobStatus, jobEvent> sm = stateMachineFactory.getStateMachine(jobId.toString());

        sm.stopReactively().block();

        sm.getStateMachineAccessor().doWithAllRegions(accessor -> {

            accessor.addStateMachineInterceptor(jobInterceptor);

            accessor.resetStateMachineReactively(new DefaultStateMachineContext<>(
                    job.getStatus(),null,null,null
            )).block();
        });

        sm.startReactively().block();

        return sm;
    }

    public void fireEvent(UUID jobId, jobEvent event, Map<String, Object> additionalHeaders) {
        StateMachine<jobStatus, jobEvent> sm = buildStateMachine(jobId);
        jobStatus before = sm.getState().getId();

        MessageBuilder<jobEvent> builder = MessageBuilder
                .withPayload(event)
                .setHeader("jobId", jobId);

        if (additionalHeaders != null && !additionalHeaders.isEmpty()) {
            additionalHeaders.forEach(builder::setHeader);
        }

        sm.sendEvent(Mono.just(builder.build())).blockLast();

        // Spring State Machine reports ACCEPTED even when a guard says no, so judge by the state.
        jobStatus after = sm.getState().getId();
        if (after == before) {
            throw new IllegalStateException(
                    "Transition blocked for event " + event + ": job " + jobId + " stayed in " + before);
        }
    }

    @Transactional
    public void moveJobintoDispute(UUID jobId, UUID milestoneId) {
        Milestones milestone = milestoneRepository.findById(milestoneId)
                .orElseThrow(() -> new RuntimeException("milestone not found!"));

        if (milestone.getJob().getId().equals(jobId) && milestone.getStatus().equals(MilestoneStatus.DISPUTED_NEGOTIATION)) {
            this.raiseDispute(jobId);
            log.info("Job {} moved into DISPUTE state", jobId);
        } else {
            throw new IllegalStateException("Milestone " + milestoneId + " is not in a disputable state for job " + jobId);
        }
    }

    /**
     * Transitions a job from the draft state to the pending state by triggering the appropriate state machine event.
     *
     * @param jobId the unique identifier of the job to be transitioned to the pending state
     * @throws IllegalStateException if the state transition is not accepted by the state machine
     */
    public void draftToPending(UUID jobId) {
        fireEvent(jobId, jobEvent.PUBLISH_OFFER, null);
    }

    /**
     * Transitions the state of a job identified by its UUID from "Draft" to "Cancelled".
     *
     * @param jobId the unique identifier of the job to be transitioned to the "Cancelled" state
     */
    public void draftToCancelled(UUID jobId) {
        fireEvent(jobId, jobEvent.CANCEL_JOB, null);
    }

    /**
     * Transitions a job from a pending state to a cancelled state.
     * This method triggers an event to notify the system of the job cancellation.
     *
     * @param jobId the unique identifier of the job to be transitioned to the cancelled state
     */
    public void pendingToCancelled(UUID jobId) {
        fireEvent(jobId, jobEvent.CANCEL_JOB, null);
    }

    /**
     * Transitions a job from the pending state to the active state by processing
     * the provided job ID and invite token.
     *
     * @param jobId       the unique identifier of the job to transition
     * @param inviteToken the invitation token associated with the job
     */
    public void pendingToActive(UUID jobId, String inviteToken) {
        fireEvent(jobId, jobEvent.ACCEPT_OFFER, Map.of("inviteToken", inviteToken));
    }

    /**
     * Handles the revocation of a submission for a specific job.
     * This method triggers an event to indicate that the submission has been revoked.
     *
     * @param jobId the unique identifier of the job for which the submission is being revoked
     */
    public void submissionRevoked(UUID jobId) {
        fireEvent(jobId, jobEvent.SUBMISSION_REVOKED, null);
    }

    /**
     * Handles the acceptance of an offer for the specified job by triggering the `ACCEPT_OFFER` event
     * on the job's state machine.
     *
     * @param jobId the unique identifier of the job for which the offer has been accepted
     * @throws IllegalStateException if the state transition is not accepted by the state machine
     */
    public void offerAccepted(UUID jobId) {
        fireEvent(jobId, jobEvent.ACCEPT_OFFER, null);
    }

    /**
     * Handles the state transition for a job when funds have been deposited.
     * Triggers the `FUNDS_DEPOSITED` event on the job's state machine.
     *
     * @param jobId the unique identifier of the job for which the funds have been deposited
     * @throws IllegalStateException if the state transition is not accepted by the state machine
     */
    public void fundsDeposited(UUID jobId) {
        fireEvent(jobId, jobEvent.FUNDS_DEPOSITED, null);
    }

    /**
     * Marks all milestones of a job as completed by triggering the appropriate state machine event.
     *
     * @param jobId the unique identifier of the job for which all milestones are being marked as completed
     * @throws IllegalStateException if the state transition is not accepted by the state machine
     */
    public void allMilestonesCompleted(UUID jobId) {
        fireEvent(jobId, jobEvent.ALL_MILESTONES_COMPLETED, null);
    }

    /**
     * Approves the specified job by triggering the appropriate state machine event.
     *
     * @param jobId the unique identifier of the job to be approved
     * @throws IllegalStateException if the state transition is not accepted by the state machine
     */
    public void jobApproved(UUID jobId) {
        fireEvent(jobId, jobEvent.JOB_APPROVED, null);
    }

    /**
     * Releases the payment for the specified job by triggering the appropriate state machine event.
     *
     * @param jobId the unique identifier of the job for which the payment is being released
     * @throws IllegalStateException if the state transition is not accepted by the state machine
     */
    public void releasePayment(UUID jobId) {
        fireEvent(jobId, jobEvent.RELEASE_PAYMENT, null);
    }

    /**
     * Initiates a dispute for the specified job by triggering the appropriate state transition event.
     *
     * @param jobId the unique identifier of the job for which the dispute is being raised
     * @throws IllegalStateException if the state transition is not accepted by the state machine
     */
    public void raiseDispute(UUID jobId) {
        fireEvent(jobId, jobEvent.RAISE_DISPUTE, Map.of("isJobDisputed", true));
    }

    /**
     * Resolves a dispute for the specified job by triggering the appropriate state machine event.
     *
     * @param jobId the unique identifier of the job for which the dispute is being resolved
     * @throws IllegalStateException if the state transition is not accepted by the state machine
     */
    public void resolveDispute(UUID jobId) {
        fireEvent(jobId, jobEvent.RESOLVE_DISPUTE, null);
    }
}