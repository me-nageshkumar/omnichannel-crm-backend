package me.nageshk.omnichannel.crm.company.dto;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import me.nageshk.omnichannel.crm.company.Address;

/** On update, absent (null) leaves a field unchanged and blank clears it. {@code version} is required on update. */
public record CompanyRequest(Long version, String name, String domain, String website, String industry, String size,
        BigDecimal annualRevenue, String phone, String email, Address address, String description, String ownerId,
        String teamId, List<String> labelIds, Map<String, Object> customFields) {}
