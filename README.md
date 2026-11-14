# Reliability Gateway

**An AI reliability and governance layer between your application and an AI model provider.**

> **Simple idea:** Your app decides **what the AI should do**. Reliability Gateway helps control **how the AI request is allowed, executed, checked, and recorded**.

If a model returns malformed JSON, a broken stream, a provider error, or unexpected usage, an application needs more than a successful HTTP status. Reliability Gateway is being built to put those operational checks in a shared, non-bypassable request path.

This repository contains a **Java 21 / Maven** implementation, integration tests, architectural decisions, and design documentation. It is an engineering project, **not a claim that every planned enterprise feature is production-ready**.

## Why does this exist?

A developer building an AI product usually needs to handle several different problems:

- **AI application work:** prompts, RAG, memory, customer data, workflows, and business decisions.
- **AI infrastructure work:** caller authentication, permissions, provider credentials, retries, streaming integrity, response validation, usage, cost, and audit information.

Frameworks already solve important parts of this. **LangChain** supports model orchestration and structured outputs. **LangGraph** supports stateful agent workflows and memory. **Pydantic / JSON Schema** can validate application data. Existing AI gateways also provide useful routing, logging, and reliability functionality.

**Reliability Gateway does not replace those tools.** Its goal is to make a consistent, enforceable infrastructure boundary around AI model calls, while your application and existing frameworks keep doing their jobs.

## How a modern AI application works — step by step

| Component | What it does in simple words | Example |
| --- | --- | --- |
| **User input** | A user sends a question, document, or task. | Upload an insurance claim. |
| **RAG (Retrieval-Augmented Generation)** | Searches for relevant information and gives it to the model as context. | Find the customer's insurance policy terms. |
| **Memory** | Remembers useful permitted context across steps or conversations. | Retrieve a previously reported claim detail. |
| **Prompt** | Tells the model what task to perform and what information to use. | “Extract claim details using this policy context.” |
| **AI model / provider API** | Generates an answer from the prompt and supplied context. | Return a proposed claim summary. |
| **Pydantic / JSON Schema** | Checks whether the output has the required structure and data types. | Require `claim_id`, `amount`, and `reason`. |
| **Application business rules** | Apply the company's actual domain rules. | Check coverage, limits, exclusions, and approvals. |
| **Save, reject, or human review** | The application decides what to do with the result. | Store a draft or send the claim to a human adjuster. |

**Important distinction:** RAG provides *information*, memory provides *context*, schema validation checks *format*, and business rules make *domain decisions*. None of them alone proves that the model's answer is factually correct.

## Example 1 — Insurance claim processing

**Situation:** Ek insurance company AI se claims process karna chahti hai.

### A. Typical modern application (without this gateway)

```text
Customer uploads an insurance claim
        |
        v
RAG -> retrieve relevant policy documents
        |
        v
Memory -> retrieve permitted previous claim context
        |
        v
Prompt + AI model API -> generate proposed claim details
        |
        v
Pydantic / JSON Schema -> validate fields and types
        |
        v
Application business rules -> check coverage and claim conditions
        |
        v
Save draft / flag for human review / reject under business rules
```

**Developer ko kya banana padta hai?**

The developer builds the claim workflow, retrieval, prompts, application validation, and business rules. The developer must **also** arrange model-call reliability concerns—such as retries, provider access control, credentials, response-stream handling, usage metering, and audit logging—either inside the app or through existing infrastructure products.

This approach is valid. Many teams already use frameworks and gateways for these jobs.

### B. The same insurance application using Reliability Gateway

```text
Customer uploads an insurance claim
        |
        v
Insurance application
  - Retrieve policy documents (RAG)
  - Retrieve permitted context (Memory)
  - Prepare prompt and expected output format
        |
        v
RELIABILITY GATEWAY
  - Verify caller
  - Evaluate configured gateway policy
  - Choose configured provider route
  - Apply reliability policy and resolve credentials
  - Call AI provider
  - Check streaming and response structure where configured
  - Record usage, cost and operational/audit facts
        |
        v
Insurance application
  - Validate domain data
  - Apply coverage and claim rules
  - Save / reject / request human review
```

**What changed?** The insurance app still owns RAG, customer context, claim rules, and the final decision. The gateway takes responsibility for the **shared AI-call boundary**. It should not silently approve or deny insurance claims.

**Concrete failure:** The model returns HTTP 200 but omits a required `claim_amount` field. An application-only design catches this with its validator if configured correctly. With Reliability Gateway, a **configured SchemaLock validation policy** can also detect structural nonconformance at the gateway boundary. It does **not** determine whether the claim amount is truthful or legally payable.

## Example 2 — Customer-support assistant

**Situation:** Ek online business ka customer poochta hai: “Mera refund kab aayega?”

### Typical app

1. **RAG** fetches the published refund policy.
2. **Memory / CRM context** supplies the customer's permitted conversation or order context.
3. **Prompt + model** drafts a response.
4. **Schema validation** checks fields such as `order_id`, `refund_status`, and `message`.
5. **Business logic** checks the real order/refund system, then allows a reply or escalation.

### With Reliability Gateway

The **same five application steps remain**. But the AI provider request passes through the gateway's caller authentication, governance, routing, reliability, secrets, response/stream checks, metering, and audit path.

For example, if a provider stream ends halfway through “Your refund is approved,” **StreamGuard** is intended to distinguish an incomplete stream from a valid completed one. The application can then avoid treating an interrupted response as a completed answer.

**Important:** The gateway does not query the refund database by itself, guarantee a refund, or replace the business system's access checks.

## What happens inside Reliability Gateway?

The code defines a **mandatory ordered pipeline** in [`MandatoryStage.java`](backend/dataplane/gateway-dp-app/src/main/java/io/reliabilityai/gateway/dataplane/app/MandatoryStage.java). Each stage must be wired before the runtime can start; an absent mandatory stage is a startup failure, not a silent skip.

```text
Your app
   |
   v
[01 INGRESS] -> [02 AUTHN] -> [03 GOVERNANCE] -> [04 ROUTER]
                                                   |
                                                   v
[05 RELIABILITY] -> [06 SECRETS] -> [07 ADAPTER / AI PROVIDER]
                                             |
                                             v
[08 STREAM_GUARD] -> [09 SCHEMA_LOCK] -> [10 METERING]
                                             |
                                             v
                         [11 COST] -> [12 EMITTER]
                                             |
                                             v
                                       Your app
```

| Stage | In simple words |
| --- | --- |
| **1. Ingress** | Receives the HTTP request; checks basic request handling and forwards it into the pipeline. |
| **2. Authentication (AUTHN)** | Determines whether the caller's identity or supplied credential is accepted. |
| **3. Governance** | Evaluates configured AI access and policy constraints. This is **not** the insurer's claim-approval logic. |
| **4. Provider Router** | Chooses an eligible configured provider route based on the routing/capability setup. |
| **5. Reliability** | Applies configured retry and failure-handling rules; a second provider is not magically available if none is configured. |
| **6. Secrets** | Resolves the credential needed for the chosen provider request. |
| **7. Provider Adapter** | Converts between the gateway's canonical model and a provider's API. The repository includes an **OpenAI adapter**. |
| **8. StreamGuard** | Checks the integrity and completion of streaming output; incomplete streams should not be reported as complete. |
| **9. SchemaLock** | Checks configured response/structure rules; it cannot guarantee factual correctness or regulatory compliance. |
| **10. Metering** | Produces usage facts, such as token usage, for the executed request. |
| **11. Cost** | Derives cost information from usage and configured pricing data. Demo pricing is not a live provider bill. |
| **12. Emitter** | Publishes relevant completion, telemetry, and audit facts through configured sinks. |

**One-line explanation:** *An AI reply should not be trusted just because the provider returned HTTP 200; the gateway checks the request path and the response before the app uses it.*

## Where do RAG, Memory, agents and frameworks fit?

```text
                         YOUR APPLICATION
      RAG + prompts + app memory + LangChain/LangGraph
      customer database + domain validation + business rules
                                |
                                v
                       RELIABILITY GATEWAY
               auth + policy + provider execution
               response checks + usage + telemetry
                                |
                                v
                          MODEL PROVIDER
```

- **RAG:** Normally designed and called by your application or orchestration framework. The main gateway HTTP chat path is **not** a ready-made document-retrieval service.
- **Memory:** This repository also contains a separate Memory Runtime, journal storage, cryptography, PII-detection, and embedding modules. Do not confuse these modules with automatic memory retrieval on every gateway chat request. Additional integration and isolation work remains.
- **LangChain / LangGraph:** Can run **above** the gateway for prompt orchestration, agents, tools, structured output, and memory. Use the gateway as an infrastructure boundary rather than as a replacement for an orchestration framework.
- **Pydantic / JSON Schema:** Still valuable inside the application. Gateway SchemaLock protects the AI response boundary when configured; application validation and domain rules remain necessary.
- **Agent Runtime / plugins:** Separate modules exist in the repository, but their presence does not mean every agent/tool workflow is available through the example HTTP launcher.

## What is implemented today, and what is not?

This distinction matters especially for enterprises evaluating the code.

| Area | Current repository status |
| --- | --- |
| Java 21 gateway runtime and HTTP chat ingress | Implemented and covered by integration tests |
| Mandatory 12-stage pipeline | Implemented with non-bypass startup validation |
| Authentication, governance, routing and secrets seams | Implemented; the example launcher uses simplified local configuration |
| OpenAI provider integration | Implemented and exercised in tests |
| StreamGuard, SchemaLock, metering, cost and telemetry | Implemented as modules; actual behavior depends on configured policies and adapters |
| Agent, plugin, memory, embedding subsystems | Separate modules present; integration capabilities and readiness vary |
| Automatic RAG over your company documents | **Not provided** by the basic chat endpoint |
| Accurate insurance eligibility or refund decisions | **Not provided**; belongs to application business logic and authoritative records |
| Turnkey multi-provider production failover | **Not guaranteed** by the single-provider local launcher |
| Full production control plane, tenant-governance isolation, enterprise deployment | **Not established** merely by this demo or a green CI build |

The bundled launcher currently uses **local/static identity, in-memory governance and pricing fixtures, and one configured model/provider route**. It is useful to understand the execution path, not a production security or billing configuration. The product-level tenant-governance boundary also has an outstanding architecture decision; see [ADR-021](adr/ADR-021-Multi-Tenancy-Isolation.md) and [Governance docs](docs/21-GovernanceEngine.md).

## Try the gateway locally

**Requirements:** JDK 21, Maven, and access to a compatible configured model/provider API. Do not commit real API credentials.

From the repository root:

```bash
# First run the full quality-gated build.
cd backend
mvn -B verify
cd ..

# Example environment values: substitute your own real provider/model configuration.
export GATEWAY_PROVIDER_BASE_URI="https://api.openai.com"
export GATEWAY_PROVIDER_API_KEY="<your-provider-key>"
export GATEWAY_MODEL="<model-you-have-access-to>"
export GATEWAY_API_KEY="<your-local-client-token>"

# Starts the local single-node demonstration.
./backend/run-gateway.sh
```

The default listener is `127.0.0.1:8080`. In another terminal, check readiness and send a test request:

```bash
curl http://127.0.0.1:8080/health
curl http://127.0.0.1:8080/ready

curl http://127.0.0.1:8080/v1/chat/completions \
  -H "Authorization: Bearer <your-local-client-token>" \
  -H "Content-Type: application/json" \
  -d '{"model":"<model-you-have-access-to>","messages":[{"role":"user","content":"Say hello in one sentence."}]}'
```

The launcher additionally exposes `/metrics`. **Note:** `run-gateway.sh` uses an offline Maven rebuild path that skips several gates for convenience when building locally; **`mvn -B verify` is the actual quality-gated verification**, not the launcher script.

## Repository map

| Path | Purpose |
| --- | --- |
| [`backend/dataplane/gateway-dp-app`](backend/dataplane/gateway-dp-app) | Deployable Data Plane application and pipeline wiring |
| [`backend/dataplane/modules`](backend/dataplane/modules) | Authentication, governance, routing, reliability, streaming, memory and other modules |
| [`backend/dataplane/providers`](backend/dataplane/providers) | Provider-specific adapter implementations |
| [`backend/libs`](backend/libs) | Shared ports, canonical contracts and supporting libraries |
| [`docs`](docs) | Architecture, specifications, implementation notes and standards |
| [`adr`](adr) | Architectural decision records |
| [`.github/workflows/ci.yml`](.github/workflows/ci.yml) | Maven CI, static/security gates and integration-branch mutation testing |

## Engineering verification

The pull-request verification on November 13, 2026 reported **`mvn -B -e verify` — BUILD SUCCESS across 31 Maven modules**, including the Data Plane Application. See [GitHub Actions verification](https://github.com/wulgresearch-spec/dataplane-gateway/actions/runs/43528505493).

A green build demonstrates the configured tests and gates passed; it does **not** guarantee correctness for every model response or production environment. Integration-branch mutation testing and deployment/security review are separate checks.

## Further reading

- [Architecture and runtime sequence](docs/32-RuntimeExecutionSequence.md)
- [SchemaLock](docs/17-SchemaLock.md) and [StreamGuard](docs/18-StreamGuard.md)
- [Provider Router](docs/19-ProviderRouter.md) and [Reliability Engine](docs/20-ReliabilityEngine.md)
- [Governance Engine](docs/21-GovernanceEngine.md)
- [Usage Metering](docs/23-UsageMeteringEngine.md) and [Cost Engine](docs/22-CostEngine.md)
- [Memory and embedding architecture](adr/ADR-026-Memory-Runtime.md) and [embedding integration notes](docs/39-EmbeddingRuntime-Activation.md)
- [Full implementation roadmap](docs/38-ImplementationRoadmap.md)

---

**In short:** Build your AI feature with RAG, memory, prompts, model calls and business rules. Put Reliability Gateway in the **AI-call path** when you need a central place to enforce operational reliability, access policies, response integrity and usage visibility.
