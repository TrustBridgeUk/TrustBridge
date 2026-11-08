package com.trustbridge.Features.Disputes.Controllers;

import com.trustbridge.Features.Disputes.Dto.*;
import com.trustbridge.Features.Disputes.Service.DisputeService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.UUID;

@RequiredArgsConstructor
@RestController
@RequestMapping("/api/v1/disputes")
public class DisputeApiController {

    private final DisputeService disputeService;

    /**
     * Handles the request to open a new dispute.
     *
     * @return a ResponseEntity containing a success message indicating
     *         that the dispute has been successfully opened.
     */
    @PostMapping(value = "/open-dispute", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<String> openDispute(
            @RequestPart("dto") @Valid DisputeCreationDto dto,
            @RequestPart(value = "files", required = false) List<MultipartFile> files,
            @RequestHeader("X-Review-Token") String reviewToken) {

        DisputeOpenedResult result = disputeService.processClientDispute(dto, files, reviewToken);

        disputeService.sendDisputeOpenedEmail(result);

        return ResponseEntity.ok("Dispute Successfully Opened");
    }

    /**
     * Retrieves the dispute submission details for a freelancer based on the provided invite token.
     *
     * @param inviteToken the unique token associated with the dispute submission.
     * @return a ResponseEntity containing the dispute submission details, wrapped in a
     *         DisputeNegotiationStageFreelancerDto object.
     */
    @GetMapping("/invite/{inviteToken}")
    public ResponseEntity<DisputeNegotiationStageFreelancerDto> getDisputeSubmissionForFreelancer(@PathVariable String inviteToken) {

        return ResponseEntity.ok(disputeService.getDisputeSubmissionForFreelancer(inviteToken));
    }

    /**
     * Freelancer accepts the client's requested refund. Resolves the dispute.
     */
    @PostMapping("/invite/{inviteToken}/accept")
    public ResponseEntity<String> acceptClientProposal(@PathVariable String inviteToken) {
        disputeService.acceptClientProposal(inviteToken);
        return ResponseEntity.ok("Dispute resolved");
    }

    /**
     * Freelancer counters with a smaller refund (0 = no refund). Passes the turn to the client.
     */
    @PostMapping("/invite/{inviteToken}/counter")
    public ResponseEntity<String> submitCounterProposal(
            @PathVariable String inviteToken,
            @RequestBody @Valid FreelancerCounterDto dto) {
        disputeService.submitCounterProposal(inviteToken, dto);
        return ResponseEntity.ok("Counter-offer submitted");
    }

    /**
     * The client's view of their dispute. Proven by the milestone's review token.
     */
    @GetMapping("/milestone/{milestoneId}")
    public ResponseEntity<DisputeClientViewDto> getDisputeForClient(
            @PathVariable UUID milestoneId,
            @RequestHeader("X-Review-Token") String reviewToken) {
        return ResponseEntity.ok(disputeService.getDisputeForClient(milestoneId, reviewToken));
    }

    /**
     * Client accepts the freelancer's counter-offer. Resolves the dispute.
     */
    @PostMapping("/milestone/{milestoneId}/accept-counter")
    public ResponseEntity<String> acceptFreelancerCounter(
            @PathVariable UUID milestoneId,
            @RequestHeader("X-Review-Token") String reviewToken) {
        disputeService.acceptFreelancerCounter(milestoneId, reviewToken);
        return ResponseEntity.ok("Dispute resolved");
    }

    /**
     * Client accepts the midpoint between both offers. Resolves the dispute.
     */
    @PostMapping("/milestone/{milestoneId}/accept-middle")
    public ResponseEntity<String> acceptMiddle(
            @PathVariable UUID milestoneId,
            @RequestHeader("X-Review-Token") String reviewToken) {
        disputeService.acceptMiddle(milestoneId, reviewToken);
        return ResponseEntity.ok("Dispute resolved");
    }

    /**
     * Client rejects the freelancer's counter-offer. Back to the freelancer, or escalated after 3 rounds.
     */
    @PostMapping("/milestone/{milestoneId}/reject")
    public ResponseEntity<String> rejectFreelancerCounter(
            @PathVariable UUID milestoneId,
            @RequestHeader("X-Review-Token") String reviewToken) {
        disputeService.rejectFreelancerCounter(milestoneId, reviewToken);
        return ResponseEntity.ok("Counter-offer rejected");
    }
}
