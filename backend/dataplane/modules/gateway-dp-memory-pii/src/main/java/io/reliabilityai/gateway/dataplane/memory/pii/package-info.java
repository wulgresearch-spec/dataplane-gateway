/**
 * Production PII detection for the Memory Runtime (AD-029, closes B23).
 *
 * <p>A deterministic rule engine: bounded patterns, checksum and range validators, context scoring,
 * exemption rules, and layered built-in / organization / tenant rule sets compiled once and swapped
 * atomically. No model, no network, no service, no randomness — the same body and rule set always
 * produce byte-identical output.
 *
 * <p><b>This package classifies. It does nothing else.</b> It does not store, encrypt, embed, rank,
 * retrieve, summarise or decide policy. Governance consumes its output; C17 decides what that
 * output costs. The masked body it produces is a mechanical by-product of knowing where the spans
 * are, not a redaction decision — {@code PiiAction} in C17 still decides whether the mask is ever
 * used.
 *
 * <p><b>What it cannot do</b>, stated here because a detector's limits are the only honest
 * headline: it cannot find an unlabelled personal name, an identifier with no distinctive shape and
 * no nearby label, or sensitivity that exists only in meaning. See AD-029 §10.
 */
package io.reliabilityai.gateway.dataplane.memory.pii;
