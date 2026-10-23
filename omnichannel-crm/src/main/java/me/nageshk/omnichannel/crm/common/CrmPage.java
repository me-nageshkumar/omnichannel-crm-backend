package me.nageshk.omnichannel.crm.common;

import java.util.List;

/** A page of results with the total number of matches. */
public record CrmPage<T>(List<T> items, long total, int page) {}
