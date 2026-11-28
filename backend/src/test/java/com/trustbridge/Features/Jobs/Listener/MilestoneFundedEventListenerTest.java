package com.trustbridge.Features.Jobs.Listener;

import com.trustbridge.Domain.Entities.Jobs;
import com.trustbridge.Domain.Entities.Milestones;
import com.trustbridge.Domain.Entities.PaymentRequest;
import com.trustbridge.Domain.Entities.Users;
import com.trustbridge.Domain.Enums.JobStatus.jobStatus;
import com.trustbridge.Domain.Enums.MilestoneStatus;
import com.trustbridge.Domain.Enums.PaymentRequestStatus;
import com.trustbridge.Domain.Repositories.JobRepository;
import com.trustbridge.Domain.Repositories.MilestoneRepository;
import com.trustbridge.Domain.Repositories.PaymentRequestRepository;
import com.trustbridge.Features.Jobs.Listeners.MilestoneFundedEventListener;
import com.trustbridge.Features.Jobs.Service.MilestoneStateService;
import com.trustbridge.Features.Payments.Events.MilestoneFundedEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The funded listener used to set the milestone and job to IN_PROGRESS directly, skipping the
 * state machine (and its guard) completely. These tests say: a funding event must not advance
 * anything unless the payment is really PAID, and the advance must go through the state machine.
 * The mocks include PaymentRequestRepository and MilestoneStateService even though the current
 * constructor does not take them. @InjectMocks uses whichever it needs, so the test compiles
 * before and after you add those dependencies.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MilestoneFundedEventListenerTest {

    @Mock MilestoneRepository milestoneRepository;
    @Mock JobRepository jobRepository;
    @Mock PaymentRequestRepository paymentRequestRepository;
    @Mock MilestoneStateService milestoneStateService;

    @InjectMocks
    MilestoneFundedEventListener listener;

    private Milestones milestone;
    private Jobs job;
    private PaymentRequest paymentRequest;
    private boolean paymentRequestExists;

    @BeforeEach
    void setUp() {
        Users freelancer = Users.builder().email("fran@example.com").build();
        freelancer.setId(UUID.randomUUID());

        job = Jobs.builder()
                .freelancer(freelancer)
                .title("Website build")
                .description("Build a website")
                .currency("GBP")
                .totalAmount(new BigDecimal("1000.00"))
                .status(jobStatus.AWAITING_PAYMENT)
                .build();
        job.setId(UUID.randomUUID());

        milestone = Milestones.builder()
                .job(job)
                .title("Milestone 1")
                .amount(new BigDecimal("1000.00"))
                .sequenceOrder(1)
                .status(MilestoneStatus.AWAITING_PAYMENT)
                .build();
        milestone.setId(UUID.randomUUID());

        paymentRequest = PaymentRequest.builder().milestone(milestone).status(PaymentRequestStatus.PENDING).build();
        paymentRequest.setId(UUID.randomUUID());
        paymentRequestExists = false;

        when(milestoneRepository.findById(milestone.getId())).thenReturn(Optional.of(milestone));
        when(paymentRequestRepository.findByMilestone(any(Milestones.class))).thenAnswer(inv ->
                paymentRequestExists ? Optional.of(paymentRequest) : Optional.empty());
        when(paymentRequestRepository.findByMilestoneIdAndStatus(eq(milestone.getId()), any(PaymentRequestStatus.class)))
                .thenAnswer(inv -> paymentRequestExists && paymentRequest.getStatus() == inv.getArgument(1)
                        ? Optional.of(paymentRequest) : Optional.empty());
    }

    @ParameterizedTest
    @EnumSource(value = PaymentRequestStatus.class, mode = EnumSource.Mode.EXCLUDE, names = "PAID")
    void fundedEvent_doesNotAdvanceAnything_whenPaymentIsNotPaid(PaymentRequestStatus status) {
        paymentRequest.setStatus(status);
        paymentRequestExists = true;

        fire();

        assertNothingAdvanced();
    }

    @Test
    void fundedEvent_doesNotAdvanceAnything_whenNoPaymentRequestExists() {
        fire();

        assertNothingAdvanced();
    }

    @Test
    void fundedEvent_isRoutedThroughTheMilestoneStateMachine_whenPaid() {
        paymentRequest.setStatus(PaymentRequestStatus.PAID);
        paymentRequestExists = true;

        fire();

        verify(milestoneStateService).fundsDeposited(milestone.getId());
    }

    /** The listener may throw or quietly ignore a bad event. Either is fine; advancing is not. */
    private void fire() {
        try {
            listener.handleMilestoneFunded(new MilestoneFundedEvent(this, milestone.getId()));
        } catch (RuntimeException ignored) {
            // acceptable: the point is what happened to the state, checked by the caller
        }
    }

    private void assertNothingAdvanced() {
        assertThat(milestone.getStatus()).isEqualTo(MilestoneStatus.AWAITING_PAYMENT);
        assertThat(job.getStatus()).isEqualTo(jobStatus.AWAITING_PAYMENT);
        verify(jobRepository, never()).save(any(Jobs.class));
    }
}
