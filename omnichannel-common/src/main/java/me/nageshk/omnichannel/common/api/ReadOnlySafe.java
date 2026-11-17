package me.nageshk.omnichannel.common.api;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * On a handler that is not a GET: it only reads (a search, a preview, a count, an export, a report), or it is the
 * user's own account (password, two-factor, availability, sign-out), so a customer whose subscription has ended may
 * still call it. Every other handler that is not a GET is refused for such a customer.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface ReadOnlySafe {}
