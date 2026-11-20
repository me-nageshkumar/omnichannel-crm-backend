package me.nageshk.omnichannel.service.automation;

import java.util.List;
import me.nageshk.omnichannel.common.api.NotFoundException;
import me.nageshk.omnichannel.db.phone.PhoneNumberRepository;
import me.nageshk.omnichannel.db.template.TemplateStatus;
import me.nageshk.omnichannel.db.template.TemplateType;
import me.nageshk.omnichannel.db.template.MessageTemplateRepository;
import me.nageshk.omnichannel.db.tenant.Team;
import me.nageshk.omnichannel.db.tenant.TeamRepository;
import me.nageshk.omnichannel.db.tenant.Workspace;
import me.nageshk.omnichannel.db.tenant.WorkspaceRepository;
import me.nageshk.omnichannel.db.user.User;
import me.nageshk.omnichannel.security.access.AccessService;
import me.nageshk.omnichannel.security.access.Authority;
import me.nageshk.omnichannel.security.user.UserAdminService;
import org.springframework.stereotype.Service;

/** What the rule editor can choose from in a brand. */
@Service
public class AutomationOptionsService {

    private final WorkspaceRepository workspaces;
    private final PhoneNumberRepository numbers;
    private final TeamRepository teams;
    private final MessageTemplateRepository templates;
    private final UserAdminService userAdmin;
    private final AccessService access;
    private final AutomationCrm crm;

    private final me.nageshk.omnichannel.db.chatbot.ChatbotRepository chatbots;
    private final me.nageshk.omnichannel.meta.api.CloudTemplates cloudTemplates;

    public AutomationOptionsService(WorkspaceRepository workspaces, PhoneNumberRepository numbers,
            TeamRepository teams, MessageTemplateRepository templates, UserAdminService userAdmin,
            AccessService access, AutomationCrm crm,
            me.nageshk.omnichannel.db.chatbot.ChatbotRepository chatbots,
            me.nageshk.omnichannel.meta.api.CloudTemplates cloudTemplates) {
        this.cloudTemplates = cloudTemplates;
        this.chatbots = chatbots;
        this.workspaces = workspaces;
        this.numbers = numbers;
        this.teams = teams;
        this.templates = templates;
        this.userAdmin = userAdmin;
        this.access = access;
        this.crm = crm;
    }

    public OptionsView options(String workspaceId) {
        Workspace w = workspaces.findById(workspaceId).orElseThrow(() -> new NotFoundException("workspace not found"));
        List<Team> brandTeams = teams.findByWorkspaceIdOrderByName(workspaceId);
        var teamRefs = brandTeams.stream().map(t -> new AutomationRef(t.getId(), t.getName())).toList();
        var numberOptions = numbers.findByWorkspaceIdOrderByNumber(workspaceId).stream().map(n -> {
            AutomationRef owner = brandTeams.stream().filter(t -> t.getId().equals(n.getTeamId())).findFirst()
                    .map(t -> new AutomationRef(t.getId(), t.getName())).orElse(null);
            return new OptionsView.NumberOption(n.getId(), n.shownNumber(), n.getLabel(), owner);
        }).toList();
        var agents = userAdmin.withAccessTo(w.getCustomerId(), workspaceId).stream().filter(User::isActive)
                .map(u -> new OptionsView.AgentOption(u.getId(), u.getName(),
                        brandTeams.stream().filter(t -> t.getMemberIds().contains(u.getId())).map(Team::getId)
                                .toList(),
                        seesAll(u, workspaceId)))
                .toList();
        var templateOptions = templates.findByWorkspaceIdAndStatusOrderByNameKey(workspaceId, TemplateStatus.ACTIVE)
                .stream().filter(t -> t.getType() != TemplateType.CONTACT_CARD)
                .map(t -> new OptionsView.TemplateOption(t.getId(), t.getName(), t.getType().name(),
                        t.getPlaceholders().stream().map(p -> new OptionsView.PlaceholderOption(p.getName(),
                                p.getKind(), p.getLabel(), p.isRequired(), p.getFallback())).toList()))
                .toList();
        var chatbotOptions = chatbots.findAllByWorkspaceIdOrderByNameAsc(workspaceId).stream()
                .map(c -> new OptionsView.ChatbotOption(c.getId(), c.getName(), c.getStatus())).toList();
        return new OptionsView(numberOptions, agents, teamRefs, templateOptions, crm.options(workspaceId), chatbotOptions,
                cloudTemplateOptions(workspaceId));
    }

    /** Each sendable WhatsApp template once, with the Cloud numbers that can send it (those of its WABA). */
    private List<OptionsView.CloudTemplateOption> cloudTemplateOptions(String workspaceId) {
        java.util.Map<String, OptionsView.CloudTemplateOption> byId = new java.util.LinkedHashMap<>();
        for (var n : numbers.findByWorkspaceIdOrderByNumber(workspaceId)) {
            if (n.getConnectionType() != me.nageshk.omnichannel.db.phone.ConnectionType.WHATSAPP_CLOUD_API) continue;
            for (var t : cloudTemplates.forNumber(workspaceId, n.getId(), true)) {
                var seen = byId.get(t.id());
                List<String> ids = new java.util.ArrayList<>(seen == null ? List.of() : seen.phoneNumberIds());
                ids.add(n.getId());
                byId.put(t.id(), new OptionsView.CloudTemplateOption(t.id(), t.name(), t.language(), ids, t.variables(),
                        t.fills()));
            }
        }
        return List.copyOf(byId.values());
    }

    private boolean seesAll(User u, String workspaceId) {
        try {
            return access.inWorkspace(access.load(u), workspaceId).contains(Authority.INBOX_VIEW_ALL.code());
        } catch (NotFoundException noAccess) {
            return false;
        }
    }
}
