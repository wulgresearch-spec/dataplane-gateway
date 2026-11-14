# Free pilot: Render + Java Gateway

**Scope:** one engineering operator, one configured model/provider/tenant.
Not production or a multi-customer SaaS deployment.

## Free-tier limitations

- Render Free web service: 512 MB RAM, 0.1 CPU; Java may exceed this budget.
- Instances sleep after around 15 minutes idle, and cold starts can be slow.
- The container filesystem is ephemeral. WAL, dead letters, and in-memory
  state will not be durable. Do not use real customer or regulated data.
- The local launcher has static identity, one tenant and demonstration
  pricing. Accumulated quota/budget counters are not operational here.
- Provider API usage may cost money independently of Render hosting.
- Docker packaging skips expensive Maven analysis gates, but GitHub CI
  continues to run the repository verification workflow.

## Blueprint (Render Free plan only)

1. In Render's dashboard, choose **New → Blueprint** and connect
   `wulgresearch-spec/dataplane-gateway`.
2. Choose branch `pilot/render-free-gateway` containing `render.yaml`.
3. Confirm web service `reliability-gateway-pilot` is **plan: free**.
   Do not upgrade or add disks/databases.
4. Supply the missing environment variables in Render's protected input
   fields (never in git, chat, screenshots, or client-side JavaScript):
   - `GATEWAY_MODEL`: the canonical model id supported by your provider.
   - `GATEWAY_API_KEY`: long random **demo client** bearer token.
   - `GATEWAY_PROVIDER_API_KEY`: the **different** upstream provider key.
   - The Blueprint presets `GATEWAY_PROVIDER_BASE_URI=https://api.openai.com`.
5. Start the deployment; stop on build/startup failures rather than
   changing the plan to paid. Check resource usage and runtime logs.
6. Once ready, check `https://<host>.onrender.com/health` and `/ready`.

Render free services may be unavailable to some accounts/plans and may
require an account-side action. The Blueprint is not itself a deployed
or tested remote service.

## Existing HTTP chat request

```http
POST /v1/chat/completions
Content-Type: application/json
Authorization: Bearer <GATEWAY_API_KEY>

{"model":"<GATEWAY_MODEL>","messages":[{"role":"user","content":"Say hello."}],"stream":false}
```

## Only then connect the Vercel operator console

Add **server-side only** environment variables in the existing Vercel
frontend project (scoped to the desired deployment environment):

- `GATEWAY_BASE_URL=https://<host>.onrender.com` (no path)
- `GATEWAY_MODEL=<GATEWAY_MODEL>`
- `GATEWAY_API_KEY=<same demo client bearer token from Render>`

Redeploy the frontend. Do not replace the separate
`DASHBOARD_ACCESS_PASSWORD` or `DASHBOARD_SESSION_SECRET` values.
Never use `NEXT_PUBLIC_` for gateway credentials. The browser talks
to the existing authenticated Next.js proxy route, not directly to
the Java API with a credential.

### Phase A: approved zero-cost health-only pilot (current)

1. Container-image CI and full Maven reactor `verify` succeed on the pilot branch.
2. Render service is **free**, auto-deploy is **off**, and no paid disk/database
   or production customer data is involved. Public Render ingress is not
   equivalent to customer-production security.
3. Render reports a live server; `/health` and `/ready` return success.
4. Vercel **Preview** is branch-scoped and has private server-side
   `GATEWAY_BASE_URL`, `GATEWAY_API_KEY`, `GATEWAY_MODEL` and
   `GATEWAY_PILOT_HEALTH_ONLY=true`. Do not set secrets with `NEXT_PUBLIC_`.
5. Operator login is required for the Vercel status and metrics routes.
   The browser sees status only; gateway and provider secrets stay server-side.
6. The Vercel chat proxy refuses POST requests (HTTP 503) before forwarding
   to Java or the upstream provider. A disabled button is useful UX but
   **not** a substitute for server-side enforcement.
7. Local loopback integration tests (synthetic credentials and mock provider)
   cover successful, rejected and streaming paths. They must not call a
   billable external provider.
8. No real model execution, durable WAL/audit retention, verified tenant-level
   V2 governance isolation, customer compliance or production-readiness
   claim is made from health-only verification.

### Phase B: real AI requests (NOT authorized by this pilot)

A real provider credential, customer traffic or a change to
`GATEWAY_PILOT_HEALTH_ONLY` requires separate approval and review of provider
billing, authenticated request boundaries, governance isolation decision,
persistent WAL/audit storage and operational controls. Do not run the chat
example above against the shared Render service as a health check.

### Verified caveats / follow-up audit

- The Render Blueprint defines `healthCheckPath: /health`, but a service
  created manually must be checked separately for the same native health
  check setting. An HTTP 200 observed through the console does not prove the
  Render service's native health-check configuration.
- A green full-reactor `mvn verify` result is a repository CI verdict, not
  proof of running 12 stages with real upstream credentials or of external
  control-plane bundle interoperability.
- This free instance uses ephemeral `/tmp` WAL/dead-letter files, a static
  demo tenant, and nondurable usage/audit mechanisms. Keep it out of
  customer-facing production.
