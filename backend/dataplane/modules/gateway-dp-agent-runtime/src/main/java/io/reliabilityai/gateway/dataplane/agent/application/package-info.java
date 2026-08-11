/**
 * The Agent Runtime's three components (C14, AD-025 §17).
 *
 * <ul>
 *   <li>{@link io.reliabilityai.gateway.dataplane.agent.application.RunExecutor} — stateless.
 *       Consumes one step, produces one step result, yields. Any node may execute any step.
 *   <li>{@link io.reliabilityai.gateway.dataplane.agent.application.Supervisor} — owns
 *       orchestration policy: retries, restart intensity, retry budgets, escalation, dead runs,
 *       cancellation, timeout, continuation. No business logic, no provider code, no plugin code.
 *   <li>{@link io.reliabilityai.gateway.dataplane.agent.application.RunScheduler} — claims runnable
 *       runs and advances them, one step per pass, round-robin across tenants.
 * </ul>
 *
 * <p>The Run Store is the third architectural component and lives behind {@link
 * io.reliabilityai.gateway.dataplane.agent.api.RunRepository}: it owns history and never executes,
 * never calls providers, never calls plugins and never orchestrates.
 */
package io.reliabilityai.gateway.dataplane.agent.application;
