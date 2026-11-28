package com.trustbridge.Features.Jobs.Listeners;

import com.trustbridge.Domain.Entities.Jobs;
import com.trustbridge.Domain.Entities.Milestones;
import com.trustbridge.Domain.Enums.JobStatus;
import com.trustbridge.Domain.Enums.PaymentRequestStatus;
import com.trustbridge.Domain.Repositories.MilestoneRepository;
import com.trustbridge.Domain.Repositories.PaymentRequestRepository;
import com.trustbridge.Features.Jobs.Service.JobStateService;
import com.trustbridge.Features.Jobs.Service.MilestoneStateService;
import com.trustbridge.Features.Payments.Events.MilestoneFundedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Component
@RequiredArgsConstructor
public class MilestoneFundedEventListener {

    private final MilestoneRepository milestoneRepository;
    private final PaymentRequestRepository paymentRequestRepository;
    private final MilestoneStateService milestoneStateService;
    private final JobStateService jobStateService;

    /**
     * A milestone was reported as funded. We do not take the event's word for it:
     *  1. the milestone's payment request must be PAID in the database, and
     *  2. the status change goes through the state machines, so their guards run and
     *     the interceptors write the change (and the audit entry).
     * It never sets a status directly.
     */
    @EventListener
    @Transactional
    public void handleMilestoneFunded(MilestoneFundedEvent event) {
        Milestones milestone = milestoneRepository.findById(event.getMilestoneId())
                .orElseThrow(() -> new IllegalStateException("Milestone not found for funding event"));

        boolean paid = paymentRequestRepository
                .findByMilestoneIdAndStatus(milestone.getId(), PaymentRequestStatus.PAID)
                .isPresent();

        if (!paid) {
            log.warn("Ignoring MilestoneFundedEvent for milestone {}: payment request is not PAID", milestone.getId());
            return;
        }

        milestoneStateService.fundsDeposited(milestone.getId());

        Jobs job = milestone.getJob();
        if (job.getStatus() == JobStatus.jobStatus.AWAITING_PAYMENT) {
            // Job guard checks the job's first milestone is paid, in the database.
            jobStateService.fundsDeposited(job.getId());
        }
    }
}