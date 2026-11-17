package me.nageshk.omnichannel.app.billing;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import java.util.TreeSet;
import me.nageshk.omnichannel.common.api.ReadOnlySafe;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * The handlers a read-only customer may call although they are not GETs. Adding or removing one is a decision about
 * what an expired customer can still do, so the list is spelled out here.
 */
@SpringBootTest
class ReadOnlySafeHandlersTest {

    @Autowired @Qualifier("requestMappingHandlerMapping") RequestMappingHandlerMapping mappings;

    @Test
    void theseAndOnlyTheseAreSafe() {
        Set<String> safe = new TreeSet<>();
        mappings.getHandlerMethods().forEach((info, handler) -> {
            if (!handler.hasMethodAnnotation(ReadOnlySafe.class)) return;
            assertThat(info.getMethodsCondition().getMethods()).as(handler.toString()).doesNotContain(RequestMethod.GET);
            safe.add(handler.getBeanType().getSimpleName() + "#" + handler.getMethod().getName());
        });
        assertThat(safe).containsExactlyInAnyOrder(
                // searches, previews, counts and exports
                "ContactController#search", "ContactFilterController#preview", "ContactBulkController#count",
                "ContactExportController#export", "CampaignController#preview", "ChatbotController#preview",
                "CloudMessageController#preview", "TemplateController#render",
                "CrmContactController#search", "CrmCompanyController#search", "CrmDealController#search",
                "CrmExportController#contacts", "CrmExportController#companies", "CrmExportController#deals",
                "CrmSegmentController#preview", "CrmSegmentController#previewSaved",
                // reports
                "CrmReportController#pipelineSummary", "CrmReportController#dealsOverTime", "CrmReportController#byOwner",
                "CrmReportController#bySource", "CrmReportController#byNumber", "CrmReportController#lifecycleFunnel",
                "CrmReportController#activitySummary", "CrmReportController#stageDuration", "CrmReportController#grid",
                "CrmReportController#campaigns",
                // reading a chat
                "InboxController#read",
                // the user's own account
                "UserController#availability", "UserController#initiate", "UserController#confirm",
                "UserController#disable", "InitialPasswordController#change", "LogoutController#logout",
                // asking to renew, upgrade or buy an add-on: the way out of read-only
                "MyOrderController#quote", "MyOrderController#raise", "MyOrderController#cancel");
    }
}
