package me.nageshk.omnichannel.crm.company.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import me.nageshk.omnichannel.crm.common.Ref;
import me.nageshk.omnichannel.crm.company.Address;

public record CompanyView(String id, String name, String domain, String website, String industry, String size,
        BigDecimal annualRevenue, String phone, String email, Address address, String description, Ref owner,
        String teamId, List<String> labelIds, Map<String, Object> customFields, int contactCount,
        BigDecimal openDealAmount, Instant lastActivityAt, Instant creationTime, Long version) {}
