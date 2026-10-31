package me.nageshk.omnichannel.app.crm;

import me.nageshk.omnichannel.crm.platform.ChannelKind;
import me.nageshk.omnichannel.crm.segment.CampaignActivityRecorder;
import me.nageshk.omnichannel.db.phone.PhoneNumber;
import me.nageshk.omnichannel.db.phone.PhoneNumberRepository;
import me.nageshk.omnichannel.service.campaign.CampaignObserver;
import org.springframework.stereotype.Component;

/** Campaign sends reach CRM timelines here (spec D C13); the channel account is the sender's number. */
@Component
class CampaignObserverAdapter implements CampaignObserver {

    private final CampaignActivityRecorder recorder;
    private final PhoneNumberRepository numbers;

    CampaignObserverAdapter(CampaignActivityRecorder recorder, PhoneNumberRepository numbers) {
        this.recorder = recorder;
        this.numbers = numbers;
    }

    @Override
    public void sent(CampaignSent e) {
        ChannelKind kind = numbers.findById(e.channelAccountId()).map(PhoneNumber::getConnectionType)
                .map(ChannelContactQueryAdapter::kindOf).orElse(ChannelKind.WHATSAPP_WEB);
        recorder.recordSent(e.workspaceId(), e.campaignId(), e.campaignName(), e.segmentId(), e.segmentName(),
                e.contactId(), e.channelAccountId(), kind, e.sentAt());
    }
}
