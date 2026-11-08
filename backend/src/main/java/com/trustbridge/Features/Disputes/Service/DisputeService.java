package com.trustbridge.Features.Disputes.Service;

import com.trustbridge.Domain.Entities.*;
import com.trustbridge.Domain.Enums.DisputeState;
import com.trustbridge.Domain.Enums.MilestoneStatus;
import com.trustbridge.Domain.Repositories.*;
import com.trustbridge.Features.Disputes.Dto.*;
import com.trustbridge.Features.Jobs.Service.FileStorageService;
import com.trustbridge.Features.Jobs.Service.JobStateService;
import com.trustbridge.Features.Jobs.Service.MilestoneStateService;
import com.trustbridge.Features.Notifications.Listeners.DisputeEmailListener;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class DisputeService {

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    /** After this many rejected counter-offers, the dispute goes to arbitration. */
    private static final int MAX_NEGOTIATION_ROUNDS = 3;

    private final DisputeRepository disputeRepository;
    private final DisputeStateService disputeStateService;
    private final MilestoneStateService milestoneStateService;
    private final JobStateService jobStateService;
    private final MilestoneRepository milestoneRepository;
    private final DisputeEvidenceSubmissionRepository disputeEvidenceSubmissionRepository;
    private final DisputeEvidenceFilesRepository disputeEvidenceFilesRepository;
    private final FileStorageService fileStorageService;
    private final DisputeEmailListener  disputeEmailListener;
    private final MilestoneSubmissionRepository milestoneSubmissionRepository;

    /**
     * Processes a client dispute for a specific milestone. This method verifies the conditions
     * for creating a dispute, such as milestone status and existing disputes. If criteria are met,
     * it creates a new dispute, updates the dispute state, and notifies the relevant freelancer
     * via email.
     *
     * @param dto Data Transfer Object containing the necessary information to create a dispute.
     *            Includes details such as the milestone ID and the client-proposed amount.
     * @throws AccessDeniedException if:
     *         - A dispute already exists for the specified milestone.
     *         - The milestone is not in a valid state for dispute creation.
     *         - The user is not authorized to access the required resources.
     * @throws RuntimeException if an unexpected error occurs during dispute creation or notification processing.
     */
    @Transactional
    public DisputeOpenedResult processClientDispute(DisputeCreationDto dto, List<MultipartFile> files, String reviewToken) {

        Milestones milestone = milestoneRepository.findById(dto.milestoneId())
                .orElseThrow(() -> new AccessDeniedException("Not authorized"));

        verifyReviewToken(milestone, reviewToken);

        if (milestone.getStatus() != MilestoneStatus.SUBMITTED) {
            throw new AccessDeniedException("Milestone is not in a valid state for dispute creation");
        }

        if (disputeRepository.findByMilestoneId(dto.milestoneId()).isPresent()) {
            throw new AccessDeniedException("Dispute already exists for this milestone");
        }

        if (dto.clientProposedAmount().compareTo(milestone.getAmount()) > 0) {
            throw new AccessDeniedException("Client proposed amount is greater than milestone amount");
        }

        Dispute dispute = disputeRepository.save(Dispute.builder()
                .milestone(milestone)
                .clientProposedAmount(dto.clientProposedAmount())
                .finalSettlementCurrency(milestone.getJob().getCurrency())
                .build());

        DisputeEvidenceSubmission submission = disputeEvidenceSubmissionRepository.save(
                DisputeEvidenceSubmission.builder()
                .dispute(dispute)
                .reason(dto.reason())
                .inviteToken(generateInviteToken())
                .build());

        storeEvidenceFiles(submission, files);

        milestoneStateService.moveMilestoneIntoDispute(milestone.getId());
        jobStateService.moveJobintoDispute(milestone.getJob().getId(), milestone.getId());

        disputeStateService.disputeEvidenceSubmitted(dispute.getId());

        Users freelancer = milestone.getJob().getFreelancer();

        DisputeCreationEmailSenderDto emailDto = new DisputeCreationEmailSenderDto(
                freelancer.getFirstName(),
                freelancer.getEmail(),
                milestone.getId(),
                milestone.getTitle(),
                milestone.getJob().getDescription(),
                milestone.getJob().getCurrency(),
                milestone.getAmount(),
                dto.clientProposedAmount()
        );


        return new DisputeOpenedResult(dispute.getId(), submission.getInviteToken(), emailDto, freelancer);
    }

    /**
     * Emails the freelancer their dispute link. NOT transactional and never throws:
     * a failed email must not undo (or hide) a dispute that was already opened.
     */
    public void sendDisputeOpenedEmail(DisputeOpenedResult result) {
        try {
            disputeEmailListener.clientOpendedDispute(
                    result.emailDto(),
                    result.inviteToken(),
                    result.disputeId().toString(),
                    result.freelancer());
        } catch (Exception e) {
            log.error("Dispute {} was opened but the freelancer email failed: {}", result.disputeId(), e.getMessage());
        }
    }

    private void verifyReviewToken(Milestones milestone, String providedToken) {
        MilestoneSubmissions latest = milestoneSubmissionRepository.findTopByMilestoneIdOrderByCreatedAtDesc(milestone.getId())
                .orElseThrow(() -> new AccessDeniedException("Not Authorized"));

        String stored = latest.getReviewToken();

        boolean matches = stored != null && providedToken != null
                && MessageDigest.isEqual(
                        stored.getBytes(StandardCharsets.UTF_8),
                        providedToken.getBytes(StandardCharsets.UTF_8));
        if (!matches) {
            throw new AccessDeniedException("Review token does not match");
        }
    }

    /**
     * Generates a random alphanumeric token to be used as an invite token.
     *
     * @return A 64-character long random alphanumeric string.
     */
    public String generateInviteToken() {
        byte[] bytes = new byte[48];
        SECURE_RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private void storeEvidenceFiles(DisputeEvidenceSubmission submission, List<MultipartFile> files) {
        if (files == null || files.isEmpty()) {
            return;
        }

        for (MultipartFile file : files) {
            if (file.isEmpty()) continue;

            String storedPath;
            try {
                storedPath = fileStorageService.storeFile(file);
            } catch (IOException e) {
                throw new IllegalStateException("Could not store evidence file", e);
            }

            disputeEvidenceFilesRepository.save(DisputeEvidenceFiles.builder()
                    .submission(submission)
                    .fileName(file.getOriginalFilename())
                    .storedPath(storedPath)
                    .contentType(file.getContentType())
                    .sizeBytes(file.getSize())
                    .build());
        }
    }


    /**
     * Freelancer agrees to the client's requested refund. Resolves the dispute.
     * (All dispute amounts mean "refund to the client"; the freelancer gets the rest.)
     */
    @Transactional
    public void acceptClientProposal(String inviteToken) {
        Dispute dispute = findDisputeAwaitingFreelancer(inviteToken);

        dispute.setFinalSettlementAmount(dispute.getClientProposedAmount());
        dispute.setResolutionReason("Freelancer accepted the client's proposed refund.");

        disputeStateService.disputeProposalAccepted(dispute.getId());
    }

    /**
     * Freelancer counters with a smaller refund (0 = no refund). Passes the turn to the client.
     */
    @Transactional
    public void submitCounterProposal(String inviteToken, FreelancerCounterDto dto) {
        Dispute dispute = findDisputeAwaitingFreelancer(inviteToken);

        BigDecimal refund = dto.refundAmount().setScale(2, RoundingMode.HALF_UP);

        if (refund.compareTo(dispute.getClientProposedAmount()) >= 0) {
            throw new IllegalArgumentException(
                    "Your counter-offer must be a smaller refund than the client's request. "
                            + "To agree to the client's request, use Accept instead.");
        }

        dispute.setFreelancerProposedAmount(refund);
        dispute.setNegotiationRound(dispute.getNegotiationRound() + 1);

        disputeStateService.disputeProposalSubmitted(dispute.getId());
    }

    /**
     * Finds the dispute for an invite token and makes sure it is the freelancer's turn.
     * Bad token -> "not authorized" (403). Wrong turn -> 409.
     */
    private Dispute findDisputeAwaitingFreelancer(String inviteToken) {
        DisputeEvidenceSubmission evidence = disputeEvidenceSubmissionRepository.findByInviteToken(inviteToken)
                .orElseThrow(() -> new AccessDeniedException("Not authorized"));

        Dispute dispute = evidence.getDispute();

        if (dispute.getState() != DisputeState.AWAITING_PROPOSAL) {
            throw new IllegalStateException("This dispute is not waiting for your response");
        }

        return dispute;
    }

    /**
     * Client agrees to the freelancer's counter-offer. Resolves the dispute.
     */
    @Transactional
    public void acceptFreelancerCounter(UUID milestoneId, String reviewToken) {
        Dispute dispute = findDisputeAwaitingClient(milestoneId, reviewToken);

        dispute.setFinalSettlementAmount(dispute.getFreelancerProposedAmount());
        dispute.setResolutionReason("Client accepted the freelancer's counter-offer.");

        disputeStateService.disputeProposalAccepted(dispute.getId());
    }

    /**
     * Client agrees to the midpoint between the two offers. Resolves the dispute.
     */
    @Transactional
    public void acceptMiddle(UUID milestoneId, String reviewToken) {
        Dispute dispute = findDisputeAwaitingClient(milestoneId, reviewToken);

        dispute.setFinalSettlementAmount(calculateMiddle(dispute));
        dispute.setResolutionReason("Client accepted the midpoint between both offers.");

        disputeStateService.disputeMiddleAccepted(dispute.getId());
    }

    /**
     * Client rejects the freelancer's counter-offer. Goes back to the freelancer,
     * unless the maximum number of rounds has been reached - then it escalates.
     */
    @Transactional
    public void rejectFreelancerCounter(UUID milestoneId, String reviewToken) {
        Dispute dispute = findDisputeAwaitingClient(milestoneId, reviewToken);

        disputeStateService.disputeProposalRejected(dispute.getId());

        if (dispute.getNegotiationRound() >= MAX_NEGOTIATION_ROUNDS) {
            disputeStateService.disputeEscalated(dispute.getId());
        }
    }

    /**
     * The midpoint between the client's requested refund and the freelancer's counter-offer,
     * rounded to 2 decimal places.
     */
    public static BigDecimal calculateMiddle(Dispute dispute) {
        return dispute.getClientProposedAmount()
                .add(dispute.getFreelancerProposedAmount())
                .divide(BigDecimal.valueOf(2), 2, RoundingMode.HALF_UP);
    }

    /**
     * Proves the caller is the client (review token), finds the dispute, and makes sure
     * it is the client's turn. Bad token -> "not authorized" (403). Wrong turn -> 409.
     */
    private Dispute findDisputeAwaitingClient(UUID milestoneId, String reviewToken) {
        Milestones milestone = milestoneRepository.findById(milestoneId)
                .orElseThrow(() -> new AccessDeniedException("Not authorized"));

        verifyReviewToken(milestone, reviewToken);

        Dispute dispute = disputeRepository.findByMilestoneId(milestoneId)
                .orElseThrow(() -> new IllegalStateException("No dispute exists for this milestone"));

        if (dispute.getState() != DisputeState.AWAITING_DISPUTE_DECISION
                || dispute.getFreelancerProposedAmount() == null) {
            throw new IllegalStateException("This dispute is not waiting for your response");
        }

        return dispute;
    }

    public DisputeNegotiationStageFreelancerDto getDisputeSubmissionForFreelancer(String inviteToken) {

        DisputeEvidenceSubmission disputeEvidence = disputeEvidenceSubmissionRepository.findByInviteToken(inviteToken)
                .orElseThrow(() -> new RuntimeException("Dispute Evidence not found"));

        List<DisputeSubmissionFileDto> files = disputeEvidenceFilesRepository.findAllBySubmissionId(disputeEvidence.getId())
                .stream()
                .map(file -> new DisputeSubmissionFileDto(
                        file.getId(),
                        file.getFileName(),
                        file.getSizeBytes(),
                        "/api/files/download/" + file.getId()
                ))
                .toList();

        return new DisputeNegotiationStageFreelancerDto(
                disputeEvidence.getDispute().getMilestone().getJob().getTitle(),
                disputeEvidence.getDispute().getMilestone().getTitle(),
                disputeEvidence.getDispute().getId().toString(),
                disputeEvidence.getReason(),
                files,
                disputeEvidence.getDispute().getClientProposedAmount(),
                disputeEvidence.getDispute().getMilestone().getAmount(),
                disputeEvidence.getDispute().getState().name(),
                disputeEvidence.getDispute().getFreelancerProposedAmount()
        );
    }
}
