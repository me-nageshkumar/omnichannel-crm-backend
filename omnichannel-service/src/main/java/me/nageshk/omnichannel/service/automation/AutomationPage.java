package me.nageshk.omnichannel.service.automation;

import java.util.List;

/** One page of a listing; {@code page} counts from 0 and a page holds {@link #SIZE} items. */
public record AutomationPage<T>(List<T> items, long total, int page) {

    public static final int SIZE = 50;
}
