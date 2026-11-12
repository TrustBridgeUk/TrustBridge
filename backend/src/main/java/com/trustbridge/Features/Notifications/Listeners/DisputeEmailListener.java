package com.trustbridge.Features.Notifications.Listeners;

import com.trustbridge.Domain.Entities.Dispute;
import com.trustbridge.Domain.Entities.Milestones;
import com.trustbridge.Domain.Entities.Users;
import com.trustbridge.Domain.Enums.EmailTemplateType;
import com.trustbridge.Features.Disputes.Dto.DisputeCreationDto;
import com.trustbridge.Features.Disputes.Dto.DisputeCreationEmailSenderDto;
import com.trustbridge.Features.Notifications.Services.EmailSenderService;
import com.trustbridge.Features.Notifications.Services.TemplateEngineService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.math.BigDecimal;

@RequiredArgsConstructor
@Component
public class DisputeEmailListener {

    private final EmailSenderService emailSenderService;
    private final TemplateEngineService templateEngineService;
    
    public void clientOpendedDispute(DisputeCreationEmailSenderDto dto, String inviteToken, String disputeId, Users freelancer) {

        String inviteLink = "http://localhost:3000/dispute/" + inviteToken;

        Map<String, Object> model = Map.of(
                "freelancerFirstName", freelancer.getFirstName(),
                    "milestoneTitle", dto.milestoneTitle(),
                "description", dto.description(),
                "currency", dto.currency(),
                "amount", dto.amount(),
                "proposedAmount", dto.proposedAmount(),
                "inviteLink", inviteLink,
                "disputeId", disputeId
                );

        String htmlBody = templateEngineService.processTemplate("dispute-invite.html", model);

        emailSenderService.sendEmail(
                dto.freelancerEmail(),
                "Client Raised Dispute: " + dto.milestoneTitle(),
                EmailTemplateType.DISPUTE_OPENED,
                htmlBody,
                dto.milestoneId()
        );
    }

    public void freelancerCounterDispute(Milestones milestone, Dispute dispute, BigDecimal middleAmount, String reviewToken) {

        Users client = milestone.getJob().getClient();

        String responseLink = "http://localhost:3000/dispute/client/" + milestone.getId()
                + "?token=" + reviewToken;

        Map<String, Object> model = Map.of(
                "clientFirstName", client.getFirstName(),
                "milestoneTitle", milestone.getTitle(),
                "currency", milestone.getJob().getCurrency(),
                "amount", milestone.getAmount(),
                "clientAmount", dispute.getClientProposedAmount(),
                "freelancerAmount", dispute.getFreelancerProposedAmount(),
                "middleAmount", middleAmount,
                "responseLink", responseLink
        );


        String htmlBody = templateEngineService.processTemplate("dispute-counter-offer.html", model);

        emailSenderService.sendEmail(
                client.getEmail(),
                "Freelancer Responded to Your Dispute: " + milestone.getTitle(),
                EmailTemplateType.DISPUTE_COUNTER_OFFER,
                htmlBody,
                milestone.getId()
        );
    }
}
