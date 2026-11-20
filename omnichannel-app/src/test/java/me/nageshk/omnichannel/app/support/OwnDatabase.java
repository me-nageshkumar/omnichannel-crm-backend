package me.nageshk.omnichannel.app.support;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * The test's app gets a database of its own on the shared test MongoDB, instead of the shared one. For tests that drop
 * collections or indexes, or write documents in old shapes for a migration: in the shared database that would take
 * indexes away from every other app of the test process. See {@link SharedMongo}.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Inherited
public @interface OwnDatabase {}
