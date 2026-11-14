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

### Pilot acceptance

1. Container-image CI succeeds.
2. Render reports a ready service, `/health` and `/ready` work.
3. Missing/wrong client bearer token is rejected.
4. An authenticated small chat request succeeds.
5. The Vercel console can call the backend without exposing secrets.
6. Confirm no production data, durable billing, or tenant-isolation
   guarantees are claimed.
