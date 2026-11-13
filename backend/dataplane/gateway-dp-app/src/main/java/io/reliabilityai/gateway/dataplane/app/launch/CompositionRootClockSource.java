package io.reliabilityai.gateway.dataplane.app.launch;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Identifies the single system-clock factory method at the application composition root.
 *
 * <p>The forbidden-apis checker remains enabled for all other application code. Runtime decisions
 * and observations must use ClockPort instead of constructing ambient clock sources.
 */
@Retention(RetentionPolicy.CLASS)
@Target(ElementType.METHOD)
@interface CompositionRootClockSource {}
