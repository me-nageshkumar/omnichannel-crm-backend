package me.nageshk.omnichannel.service.meta;

import me.nageshk.omnichannel.meta.api.PublicOrigin;
import me.nageshk.omnichannel.service.page.PagesAddresses;
import org.springframework.stereotype.Component;

/** Meta reaches our webhook at the platform's pages address, the public origin WhatsApp already fetches from. */
@Component
class MetaPublicOrigin implements PublicOrigin {

    private final PagesAddresses pages;

    MetaPublicOrigin(PagesAddresses pages) {
        this.pages = pages;
    }

    @Override
    public String platformOrigin() {
        return pages.platform();
    }
}
