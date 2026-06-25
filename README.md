# DesignForge Architecture

DesignForge is an AI-powered frontend code generation and live execution platform. Users describe UI and frontend feature changes in natural language, the Intelligence Service generates React/Vite project code through an LLM-assisted workflow, and the resulting files are reflected in an isolated live preview environment.

This README focuses on system design, component boundaries, Kubernetes topology, runtime contracts, data ownership, event flows, security isolation, and architectural tradeoffs. It does not provide installation steps, deployment instructions, or a getting-started guide.
The README is divided into two parts the first one provides a deep dive into the system architecture and the second part provides summarized architecture details along with exact software configuration details check out that part too if interested!

I first built the monolithic version of this application you can check it out here: https://github.com/KaranLalwani-dev/Design-Forge-Monolithic-version

Frontend Repository: https://github.com/KaranLalwani-dev/destributed_design_forge_frontend

Config-Server Repository: https://github.com/KaranLalwani-dev/designforge-config-server

# DesignForge — Architecture Deep Dive

DesignForge is an AI-powered frontend code generation and live execution platform. Users describe UI changes in natural language. The platform generates syntactically correct React and Vite project code through an LLM-assisted pipeline, persists that code to object storage, and reflects every change in an isolated live preview environment — all without the user touching a terminal or a file system.

This document covers system design, component responsibilities, data flows, Kubernetes topology, security isolation, and the reasoning behind every major architectural decision. It is written for engineers who want to understand how the system works at the level of implementation, not just what it does at the level of features.

<img width="7022" height="4200" alt="System Architecture" src="https://github.com/user-attachments/assets/5dd5bbdd-74d7-4f1f-becd-6b2b8cc99620" />

---
## Part One
## Table of Contents

1. [The Two Planes](#the-two-planes)
2. [How Traffic Enters the System](#how-traffic-enters-the-system)
3. [The API Layer](#the-api-layer)
4. [Configuration and Service Discovery](#configuration-and-service-discovery)
5. [The Microservice Boundary](#the-microservice-boundary)
6. [Code Generation — Deep Dive](#code-generation--deep-dive)
7. [The Distributed Transaction Problem](#the-distributed-transaction-problem)
8. [Code Execution — Deep Dive](#code-execution--deep-dive)
9. [File Synchronization and HMR](#file-synchronization-and-hmr)
10. [The Dynamic Preview Proxy](#the-dynamic-preview-proxy)
11. [Network Security and Isolation](#network-security-and-isolation)
12. [Real-Time Collaboration](#real-time-collaboration)
13. [Architectural Decisions](#architectural-decisions)
14. [Runtime Contracts](#runtime-contracts)
15. [Failure Modes](#failure-modes)

---

## The Two Planes

DesignForge is deployed across two Kubernetes namespaces that represent two fundamentally different trust levels and operational concerns.

`design-forge-core` is the platform control plane. It is trusted infrastructure. It hosts user identity and authentication, subscription management, project metadata, the AI generation pipeline, file persistence orchestration, the API gateway, the preview routing proxy, the static frontend, and all stateful infrastructure — PostgreSQL, Kafka, Redis, and MinIO. Nothing runs here that wasn't authored and audited by the platform team.

`design-forge-previews` is the user-code execution plane. It is intentionally untrusted. It runs generated React code and the npm dependency trees those projects pull from the public internet. The code executing in this namespace was authored by an LLM based on arbitrary user input. The dependencies it installs were authored by unknown third parties. This namespace is treated as a hostile environment and is isolated accordingly. Generated code never executes in the same namespace as account data, billing state, secret credentials, or core infrastructure.

This separation is the foundational security decision of the entire architecture. Everything else — the network policies, the RBAC model, the proxy design, the syncer sidecar — is a direct consequence of it.

---

## How Traffic Enters the System

All external traffic enters through a single NGINX Ingress controller running in the `ingress-nginx` namespace. There is no other path into the cluster. NGINX performs host-based routing and TLS termination before any application code sees a request.

Three distinct host patterns define three distinct traffic destinations.

`designforge.website` and `www.designforge.website` route to the static frontend service, which serves the compiled React application. These requests are completely stateless — the frontend is a static artifact, and NGINX serves it directly without touching any backend service.

`api.designforge.website` routes to the Spring Cloud Gateway. This is the entry point for all API traffic — authentication, project management, AI generation, and file operations. The Gateway performs JWT validation before routing to any downstream service.

`*.previews.designforge.website` routes to `design-forge-proxy`, the custom Node.js reverse proxy. This wildcard pattern matches any subdomain under `previews.designforge.website`, which means every live project preview enters through this path. The proxy's job is to resolve the specific subdomain to the correct runner pod and forward the request.

TLS termination happens at NGINX for all three patterns. Standard host certificates cover the main domains through Let's Encrypt HTTP-01 validation. The wildcard certificate for `*.previews.designforge.website` requires DNS-01 validation through the Cloudflare API, because HTTP-01 cannot issue wildcard certificates — it can only prove control of a specific hostname, not an entire DNS zone. The wildcard certificate is managed as an explicit `cert-manager` `Certificate` resource rather than being inlined in the Ingress, because cert-manager's DNS-01 solver needs to create and clean up DNS TXT records in Cloudflare on each renewal.

The NGINX Ingress is configured with `proxy-read-timeout: 3600` and `proxy-send-timeout: 3600`. Without these, NGINX would terminate any connection open for longer than the default 60-second timeout. LLM generation over SSE holds a connection open for the full duration of the model's response — which for complex code generation tasks can exceed a minute. The extended timeouts are a runtime requirement for SSE to function correctly through NGINX.

---

## The API Layer

Spring Cloud Gateway sits directly behind NGINX on `api.designforge.website`. It is built on Spring WebFlux and operates as a non-blocking reactive proxy, which matters because it holds open SSE streaming connections without blocking a thread for each one.

The Gateway's first responsibility is JWT authentication. A global filter, `GatewayJwtAuthFilter`, executes at order `-1` — before any route predicate is evaluated. It reads the request path, compares it against a configurable list of public routes using Ant path matching, and passes public requests through without validation. For every other path, it extracts the `Authorization: Bearer` header and validates the token against `jwt.secretKey` using JJWT. If validation fails, the Gateway returns a JSON error response with `401 Unauthorized` before the request ever reaches a downstream service. This means no service needs to implement its own authentication — the Gateway enforces it universally.

The Gateway's second responsibility is routing. Three route definitions map path prefixes to Kubernetes Service names using the `lb://` URI scheme, which resolves to Kubernetes DNS names in the k8s profile since Eureka is disabled in production. The `StripPrefix=2` filter removes the `/api/v1/` prefix before forwarding, so downstream services receive paths relative to their own context root.

The Gateway's third responsibility is CORS. A CORS filter allows the deployed frontend origin and `localhost:5173` for local development, with credentials permitted. Centralizing CORS at the gateway means downstream services never need to configure it independently.

---

## Configuration and Service Discovery

Every Spring service in `design-forge-core` starts with `SPRING_PROFILES_ACTIVE=k8s` and fetches its configuration from the Config Service at `http://config-service.design-forge-core.svc.cluster.local:8888` before initializing any Spring beans. The Config Service is a Spring Cloud Config Server backed by a private GitHub repository. It serves per-service YAML files — `account-service.yml`, `workspace-service.yml`, `intelligence-service.yml`, `api-gateway.yml` — and a shared `application.yml` that provides common infrastructure configuration to all services.

The k8s profile in `application.yml` disables Eureka entirely. In production, service discovery is handled by Kubernetes DNS. When `intelligence-service` needs to call `workspace-service`, it uses the hostname `workspace-service`, which Kubernetes DNS resolves to the ClusterIP of the `workspace-service` Service object in the same namespace. This is simpler, more reliable, and requires no additional infrastructure compared to a service registry. The `discovery-service` module exists in the repository but is not deployed in the production topology.

All services implement retry logic against the Config Service with ten attempts at three-second intervals. This matters because the Config Service itself takes time to start after its pod is scheduled — downstream services that attempt to fetch configuration before it is ready would fail without this tolerance window.

Secrets — database passwords, the JWT signing key, the AI API key, MinIO credentials, Stripe keys, Git credentials — are injected as environment variables from a single Kubernetes Secret named `app-secrets`. The Config Service YAML files reference these as `${DB_PASSWORD}`, `${AI_API_KEY}`, and so on. The actual secret values never appear in Git, in config files, or in this document.

---

## The Microservice Boundary

DesignForge applies database-per-service ownership strictly. Three services own three databases on a single PostgreSQL StatefulSet instance.

`account-service` owns `account_db`, which holds the `users`, `plan`, `subscription`, and `usage_logs` tables. `workspace-service` owns `workspace_db`, which holds `projects`, `project_members`, `project_files`, and `processed_events`. `intelligence-service` owns `intelligence_db`, which holds `chat_sessions`, `chat_messages`, and `chat_events`.

Each service has a dedicated PostgreSQL user with privileges only on its own database. No service can directly query another service's database. When `intelligence-service` needs to check a user's subscription limit, it calls `account-service` through an internal Feign client. When it needs a project's file tree, it calls `workspace-service`. The cross-service API boundary is explicit, typed, and version-controllable — not an implicit database join.

This constraint has operational costs. Cross-service reads require HTTP calls instead of SQL joins. There is no referential integrity between databases enforced by the database engine. But the benefit is that each service's schema is fully owned by that service. `workspace-service` can add columns to `project_files`, change its indexing strategy, or migrate its schema without coordinating with or impacting `intelligence-service` or `account-service`. Services deploy independently.

The `common-lib` module provides shared type definitions — Kafka event records, common DTOs, and utility classes — that prevent duplication across service boundaries without introducing runtime coupling.

---

## Code Generation — Deep Dive

Code generation is the core intelligence of DesignForge. It is the process by which a natural language prompt becomes a set of concrete file modifications that are persisted to MinIO and eventually reflected in the live preview. The full pipeline spans the browser, NGINX, the API gateway, `intelligence-service`, `workspace-service`, MinIO, Kafka, and PostgreSQL — and it does all of this while streaming a response to the user in real time.

<img width="1484" height="950" alt="Code Generation Architecture" src="https://github.com/user-attachments/assets/5981e42c-dc91-4852-9b6c-0ddf344d9265" />

```mermaid
sequenceDiagram
  participant Browser as Browser
  participant Ingress as NGINX Ingress
  participant Gateway as api-gateway
  participant Intel as intelligence-service
  participant Workspace as workspace-service
  participant LLM as OpenRouter / google/gemini-3-flash-preview
  participant Kafka as Kafka
  participant MinIO as MinIO

  Browser->>Ingress: POST /api/v1/intelligence/... SSE
  Ingress->>Gateway: api.designforge.website
  Gateway->>Intel: JWT validated + StripPrefix=2
  Intel->>Workspace: WorkspaceClient.getFileTree(projectId)
  Intel->>LLM: system prompt + FILE_TREE + user prompt + read_files tool
  LLM->>Intel: read_files(paths)
  Intel->>Workspace: WorkspaceClient.getFileContent(projectId, path)
  Workspace->>MinIO: read projects/{projectId}/{path}
  Workspace-->>Intel: raw file content
  Intel->>LLM: file contents
  LLM-->>Intel: streamed XML-tagged response
  Intel-->>Browser: ServerSentEvent<StreamResponse>
  Intel->>Intel: parse response into ChatEvent rows
  Intel->>Kafka: file-storage-request-event
```

### The Entry Point

The user types a prompt in the React frontend and submits it. The frontend makes a `POST` request to `api.designforge.website/api/v1/intelligence/chat/stream`, carrying the user's prompt, the project ID, and a `Bearer` token in the `Authorization` header. This request travels through NGINX (which strips TLS), through the Spring Cloud Gateway (which validates the JWT and strips the `/api/v1` prefix), and arrives at `intelligence-service`'s `ChatController.streamChat` method. The controller produces `MediaType.TEXT_EVENT_STREAM_VALUE` — a Server-Sent Events stream — meaning the HTTP connection stays open and the service writes response chunks to it progressively as the LLM generates them. The browser's `EventSource` API receives these chunks and renders them incrementally, producing the typewriter effect the user sees.

### Context Assembly

Before a single token is sent to the LLM, `AiGenerationServiceImpl` must assemble the full context that will drive the generation. This happens in two stages.

The first stage is the system prompt. `PromptUtils.CODE_GENERATION_SYSTEM_PROMPT` contains the complete behavioral specification for the model — it instructs the model to reason about the project before generating code, to output structured XML tags for different content types, to only modify files that are relevant to the request, and to output complete file contents rather than diffs or partial fragments. The system prompt is the contract between the platform and the model.

The second stage is file tree injection. `FileTreeContextAdvisor` implements Spring AI's `StreamAdvisor` interface and runs before the model invocation. It calls `workspaceClient.getFileTree(projectId)`, which is an internal Feign call to `workspace-service`'s `InternalWorkspaceController`. Workspace Service queries `project_files` in `workspace_db` for all files belonging to this project and returns their paths. The advisor injects this file tree into the model's context as a system message, prepended with a `---- FILE_TREE ----` marker. This gives the model a structural map of the entire project before it reads a single file's content.

The model now knows what files exist. It does not yet know what is inside them.

### Tool Calling and File Retrieval

`CodeGenerationTools` registers exactly one tool with the Spring AI framework using the `@Tool` annotation:

```java
@Tool(name = "read_files",
        description = "Read the content of files. Only input the file names present inside the FILE_TREE.")
public List<String> readFiles(@ToolParam List<String> paths)
```

When the LLM is invoked with the system prompt, the file tree, and the user's prompt, it reasons about which files are relevant to the request. If the user asks to change the color of a button in `ProfileCard.tsx`, the model identifies `src/components/ProfileCard.tsx` as the relevant file and calls `read_files(['src/components/ProfileCard.tsx'])` before generating any code.

The tool implementation iterates over the requested paths. For each path, it calls `WorkspaceClient.getFileContent(projectId, path)`, which makes an internal HTTP call to `workspace-service`. Workspace Service looks up the file's `minio_object_key` in the `project_files` table, then reads the actual file content directly from MinIO using the Java MinIO SDK. The raw text content is returned to the tool, which wraps it in a structured delimiter:

```
--- START OF FILE: src/components/ProfileCard.tsx ---
{raw file content}
--- END OF FILE ---
```

This formatted block is returned to the Spring AI framework, which includes it in the model's context as a tool result. The model now has the actual source code it needs to reason about. There is no embedding index, no vector similarity search, and no retrieval approximation — the model reads exactly the files it decides it needs, based on its own reasoning over the file tree.

### Streaming and Buffering

Once the model has the context it needs, it generates its response and streams it back. `AiGenerationServiceImpl.streamResponse` does two things simultaneously with each incoming chunk: it writes the chunk to the SSE stream, delivering it to the browser in real time, and it appends the chunk to a `StringBuilder`, accumulating the complete response in memory. The user sees the model's output appear word by word. The service is simultaneously building the complete text it will need to parse once the stream ends.

The model's output follows a structured XML format that `LlmResponseParser` is built to consume:

```xml
<message phase="planning">
  I'll update the button color in ProfileCard.tsx by changing the className.
</message>

<file path="src/components/ProfileCard.tsx">
import React from 'react';

export const ProfileCard = () => (
  <div className="card">
    <button className="btn btn-red">Click me</button>
  </div>
);
</file>
```

`<message>` tags contain the assistant's natural language explanation — the planning and completion text that the user reads. `<file>` tags contain complete file contents for files that were created or modified. `<tool>` tags record the tool calls the model made, logged for observability. These three tag types map to three `ChatEventType` values: `MESSAGE`, `FILE_EDIT`, and `TOOL_LOG`.

### Persistence and the Kafka Handoff

When the stream completes and `finalizeChats` is called, the accumulated `StringBuilder` is handed to `LlmResponseParser`. The parser processes the structured output and produces a list of `ChatEvent` objects. For `MESSAGE` events, the status is immediately `CONFIRMED` — these are purely informational and require no side effects. For `FILE_EDIT` events, the status is `PENDING`. Each `FILE_EDIT` event is assigned a UUID `sagaId`.

The `ChatMessage` and all its `ChatEvent` records are written to `intelligence_db`. Then, for each `FILE_EDIT` event, `intelligence-service` publishes a `FileStoreRequestEvent` to the `file-storage-request-event` Kafka topic, carrying the `sagaId`, the `filePath`, the full file `content`, the `projectId`, and the `userId`.

At this point `intelligence-service`'s role in code generation is complete. The chat events are in the database with `PENDING` status, the SSE stream has already delivered the response to the browser, and the file persistence responsibility has been handed off to `workspace-service` via Kafka. The two concerns — streaming the response and persisting the files — are now fully decoupled.

### New Project Initialization

When a project is first created, `workspace-service` copies a starter template from a dedicated MinIO template bucket into the project's MinIO prefix (`projects/{projectId}/`). The template contains a minimal Vite React TypeScript scaffold — `package.json`, `vite.config.js`, `tsconfig.json`, `index.html`, and a root `src/App.tsx`. This gives the LLM a working project structure to build on from the very first prompt.

---

## The Distributed Transaction Problem

File persistence in DesignForge spans a domain boundary that cannot be wrapped in a single database transaction. `intelligence-service` owns the chat events in `intelligence_db`. `workspace-service` owns the file metadata in `workspace_db` and the file contents in MinIO. When an LLM generates a file modification, two things must happen atomically in a logical sense: the file must be written to MinIO and the chat event must be marked as confirmed. But these two operations involve two different services with two different databases and an object store — they are inherently distributed.

If `intelligence-service` called `workspace-service` synchronously over HTTP and the call succeeded, the file would be in MinIO and the chat event could be confirmed. But what happens if the HTTP call times out? The request may have reached `workspace-service` and been processed, or it may never have arrived. The caller cannot know. Retrying the call risks writing the same file twice and creating duplicate file metadata records. Not retrying risks leaving the system in an inconsistent state where the user sees a chat event for a file that was never persisted.

This is the fundamental problem that the choreography-based Saga over Kafka solves.

<img width="2091" height="580" alt="Distributed Transaction" src="https://github.com/user-attachments/assets/2ed2a03f-5423-401d-b2ae-7525029cce21" />

```mermaid
sequenceDiagram
  participant Intel as intelligence-service
  participant IntelDB as intelligence_db
  participant Kafka as Kafka
  participant Workspace as workspace-service
  participant WorkspaceDB as workspace_db
  participant MinIO as MinIO

  Intel->>IntelDB: Save ChatEvent FILE_EDIT status=PENDING
  Intel->>Kafka: Publish file-storage-request-event
  Kafka->>Workspace: Deliver FileStoreRequestEvent
  Workspace->>WorkspaceDB: Check processed_events by sagaId
  alt duplicate sagaId
    Workspace->>Kafka: Publish file-store-responses success=true
  else new sagaId
    Workspace->>MinIO: Write projects/{projectId}/{filePath}
    Workspace->>WorkspaceDB: Upsert project_files metadata
    Workspace->>WorkspaceDB: Insert ProcessedEvent(sagaId, processedAt)
    Workspace->>Kafka: Publish file-store-responses
  end
  Kafka->>Intel: Deliver FileStoreResponseEvent
  Intel->>IntelDB: Mark ChatEvent CONFIRMED or FAILED
```

### The Saga Flow

`intelligence-service` publishes each `FileStoreRequestEvent` to Kafka with a UUID `sagaId` and marks the corresponding `ChatEvent` as `PENDING` in `intelligence_db`. These two operations are local to `intelligence-service` and are effectively atomic from its perspective.

`workspace-service` consumes the event from the `file-storage-request-event` topic. Before doing anything else, it checks the `processed_events` table in `workspace_db` for the `sagaId`. This table exists for exactly one purpose: idempotency. Kafka guarantees at-least-once delivery — in the event of a consumer restart, a rebalance, or a network partition, the same event can be delivered more than once. Without idempotency protection, a redelivered event would cause the same file to be written to MinIO again and duplicate metadata records to be created in `project_files`. The `processed_events` table prevents this by making `sagaId` the primary key. The second delivery of the same event hits a primary key conflict and is safely discarded.

If the `sagaId` is new, `workspace-service` writes the file content to MinIO at `projects/{projectId}/{filePath}`, upserts the corresponding record in `project_files` with the `minio_object_key`, inserts a `ProcessedEvent` record with the `sagaId` and `processedAt` timestamp, and publishes a `FileStoreResponseEvent` to the `file-store-responses` topic.

`intelligence-service` consumes the response event. It looks up the `ChatEvent` by `sagaId` and updates its `status` to `CONFIRMED` if the response indicates success, or `FAILED` if the workspace service encountered an error writing to MinIO. A `FAILED` status triggers a compensating notification to the user. The chat event record now carries the final, durable outcome of the file persistence operation.

This design gives both services complete local autonomy. Neither service needs to be available simultaneously — `workspace-service` can process the event minutes after `intelligence-service` published it, and `intelligence-service` will update the chat event status whenever the response arrives. The system always converges to a consistent state regardless of the timing of individual service restarts or failures.

---

## Code Execution — Deep Dive

Code execution is the infrastructure layer that transforms source files in MinIO into a live, interactive web application accessible in the user's browser. It involves pod lifecycle management, cross-namespace Kubernetes API access, a two-container sidecar architecture, a custom reverse proxy, and a real-time file synchronization mechanism that feeds Vite's HMR system.

<img width="2739" height="1679" alt="Code Execution Architecture" src="https://github.com/user-attachments/assets/24d1a12d-b304-4391-8198-20cb30dd4fff" />

### The Runner Pool

Naive pod-per-project architectures create new Kubernetes pods on demand — the user requests a preview, Kubernetes schedules a pod, the container image is pulled, the containers start, and only then can the application begin loading. For `node:20-alpine` and `minio/mc`, this cold start can take 30–60 seconds or longer depending on whether the images are cached on the selected node. This is an unacceptable user experience.

DesignForge solves this with a pre-provisioned runner pool. A `runner-pool` Deployment in `design-forge-previews` maintains two pods in a perpetual idle state. These pods are fully started, fully initialized, and waiting. Their initial labels are `app=runner` and `status=idle`. They do nothing — both containers start with `sleep infinity` — but they are scheduled on nodes, their images are pulled, and the container runtime has them running. When a user deploys a project, `workspace-service` claims one of these idle pods in milliseconds rather than waiting for the entire pod scheduling and startup sequence.

The trade-off is resource consumption. Two idle pods hold 700m CPU and 1280Mi memory in requests even when no user has an active preview. This is a deliberate operational cost paid to eliminate cold start latency.

### Claiming a Pod

When `POST /workspace/projects/{projectId}/deploy` is called, `KubernetesDeploymentServiceImpl.deploy(projectId)` executes using the Fabric8 Kubernetes Java client. The service account `workspace-service-account` in `design-forge-core` is bound to the `preview-manager-role` Role in `design-forge-previews` through a cross-namespace RoleBinding. This RBAC configuration is the mechanism that allows a service running in the core namespace to manage pods in the preview namespace — a privilege that is precisely scoped to the minimum required operations: `get`, `list`, `watch`, `create`, `update`, `patch`, and `delete` on `pods`, `pods/exec`, `pods/log`, and `pods/status`.

The deploy method first checks whether a pod with labels `project-id={projectId}` and `status=busy` already exists in a Running phase. If it does, the project is already running — the method re-registers the Redis route with a fresh TTL and returns the preview URL. This handles the common case where a user navigates away and returns, or where multiple collaborators open the same project.

If no running pod exists for this project, the method searches for any pod with `status=idle`. It takes the first one found and patches its labels to `status=busy` and `project-id={projectId}`. Label patching is atomic from the Kubernetes API's perspective — other concurrent deploy calls for different projects will find this pod as `busy` and skip it. This is the pod claim mechanism: label mutation is the distributed lock.

### The Two-Container Sidecar Architecture

Each runner pod contains two containers sharing a single `emptyDir` volume mounted at `/app` in both containers.

The **runner** container runs `node:20-alpine`. It starts with `sleep infinity`, waiting for `workspace-service` to exec the startup sequence into it. Its resource requests are 250m CPU and 512Mi memory, with limits of 500m CPU and 1Gi memory. Port 5173 is declared on the container, though it is not exposed through any Kubernetes Service until the pod is claimed and the Vite dev server is running.

The **syncer** container runs `minio/mc` — the MinIO command-line client. It also starts with `sleep infinity`. Its environment is configured with the MinIO connection URL constructed from the `MINIO_ROOT_USER` and `MINIO_ROOT_PASSWORD` secrets: `http://{user}:{pass}@minio.design-forge-core.svc.cluster.local:9000`. The syncer's sole responsibility is file synchronization between MinIO and the shared `/app` volume.

The two containers share the `workspace` emptyDir volume mounted at `/app`. Everything the runner executes, it reads from `/app`. Everything the syncer writes, it writes to `/app`. The volume is the communication channel between them.

### The Startup Sequence

Once a pod is claimed, `workspace-service` executes a precise sequence of commands into the two containers via the Kubernetes exec API. The order is critical — files must be present before npm can install them, and npm dependencies must be installed before Vite can start.

**Step 1 — Clear and initial sync** into the syncer container:
```sh
rm -rf /app/* && mc mirror --overwrite myminio/projects/{projectId}/ /app/
```
This wipes any residual state from the previous occupant of this pod and mirrors the complete current state of the project's MinIO prefix into `/app`. After this command completes, `/app` contains the exact file tree of the project as it exists in MinIO at this moment.

**Step 2 — Start continuous watch** into the syncer container:
```sh
nohup mc mirror --overwrite --watch myminio/projects/{projectId}/ /app/ > /app/sync.log 2>&1 &
```
The `--watch` flag subscribes the MinIO client to change notifications from the MinIO server. Any time a file is written to or deleted from `myminio/projects/{projectId}/`, the syncer immediately propagates the change to `/app/`. The `nohup` and `&` detach the process from the exec session so it continues running after the exec connection closes. This continuous watch is what enables live updates — when the LLM generates new code and Workspace Service writes it to MinIO, this process catches the change and delivers it to the file system within milliseconds.

**Step 3 — Install and start** into the runner container:
```sh
npm install && nohup npm run dev -- --host 0.0.0.0 --port 5173 > /app/dev.log 2>&1 &
```
`npm install` reads `package.json` from `/app` and fetches all declared dependencies from the public npm registry. The NetworkPolicy in `design-forge-previews` explicitly allows egress to the public internet (with private IP ranges blocked) for exactly this purpose. Once dependencies are installed, `npm run dev` starts the Vite development server on all interfaces at port 5173. `--host 0.0.0.0` is required — without it, Vite binds only to localhost, which is not reachable from outside the container.

### Vite Startup

When Vite starts, it performs several initialization steps that enable the live development experience.

It scans the project starting from the entry point (`src/main.tsx`) and follows every `import` statement to build a complete module dependency graph. It does not pre-compile the entire project — it uses on-demand compilation, transforming TypeScript, TSX, and CSS only when the browser requests those modules. This is why Vite starts nearly instantly regardless of project size.

It registers a file system watcher on `/app` using the operating system's `inotify` mechanism. This is a kernel-level subscription to file change events. When the syncer writes an updated file to `/app`, the OS notifies Vite within microseconds — no polling, no delay.

It starts an HTTP server on port 5173 that serves compiled JavaScript modules to the browser. It injects an HMR client script into the HTML that the browser loads — this script establishes a WebSocket connection back to Vite and listens for module update notifications.

### Route Registration

After the startup sequence completes, `workspace-service` reads the pod's IP address from `pod.status.podIP`. This IP is an address on the cluster's internal overlay network — it is not reachable from the public internet. The method writes a routing entry to Redis:

```
route:project-{projectId}.previews.designforge.website → {podIP}:5173
```

with a six-hour TTL. This entry is the bridge between the stable external domain and the ephemeral internal pod IP. The preview URL `https://project-{projectId}.previews.designforge.website` is returned to the browser, which sets it as the `src` of an iframe to display the live preview.

### How the Browser Loads the Preview

When the browser loads `project-123.previews.designforge.website`, the request travels through NGINX (which matches the `*.previews.designforge.website` wildcard rule and forwards to `design-forge-proxy`), through the Node.js proxy (which performs the Redis lookup and forwards to `{podIP}:5173`), and arrives at the Vite server running in the runner container.

Vite serves the HTML entry point. The browser parses it, discovers JavaScript module imports, and requests them from the same origin. Each module request travels the same path — NGINX → proxy → Vite — and Vite compiles each module on demand as the browser requests it. The browser executes the JavaScript, React initializes, the component tree renders, and the user sees their project live in the preview iframe.

Simultaneously, the HMR client script connects a WebSocket to `project-123.previews.designforge.website` over the same NGINX → proxy routing path. The Node.js proxy's `ws: true` configuration enables WebSocket upgrade forwarding. This persistent WebSocket connection is the channel Vite uses to push HMR updates to the browser without page reloads.

---

## File Synchronization and HMR

The update chain that carries a generated file from the LLM's output to the browser's live preview without a page reload is the most latency-sensitive path in the entire system. It spans Kafka, MinIO, the syncer sidecar, Vite's file watcher, the HMR WebSocket, and React Fast Refresh.

```mermaid
flowchart LR
  FileEdit["LLM generated <file path=...>"]
  Intel["intelligence-service\nChatEvent PENDING"]
  KafkaReq["Kafka\nfile-storage-request-event"]
  Workspace["workspace-service\nFileStorageConsumer"]
  MinIO["MinIO\nprojects/{projectId}/{filePath}"]
  Syncer["syncer sidecar\nmc mirror --watch"]
  Volume["emptyDir\n/app"]
  Vite["runner container\nVite :5173"]
  Proxy["design-forge-proxy\nHTTP + WebSocket"]
  Browser["Browser\nReact Fast Refresh"]

  FileEdit --> Intel
  Intel --> KafkaReq
  KafkaReq --> Workspace
  Workspace --> MinIO
  MinIO --> Syncer
  Syncer --> Volume
  Volume --> Vite
  Vite --> Proxy
  Proxy --> Browser
```

When `workspace-service` writes a generated file to MinIO, `mc mirror --watch` running in the syncer container receives a change notification from the MinIO server and immediately writes the new file content to `/app/{filePath}`. The OS fires an `inotify` event. Vite's file watcher receives it within microseconds. Vite identifies which module in its dependency graph corresponds to the changed file and recompiles only that module — not the entire project. It sends a lightweight JSON notification over the open HMR WebSocket:

```json
{
  "type": "update",
  "updates": [{ "path": "/src/components/ProfileCard.tsx", "timestamp": 1234567890 }]
}
```

The browser's HMR client receives this notification and makes a standard HTTP request for the updated module — traveling through NGINX and the proxy to Vite, which returns the freshly compiled JavaScript. The React Fast Refresh runtime in the browser receives the new module. It locates the currently mounted instances of the changed component in the React component tree and swaps the component definition in place without unmounting and remounting — preserving local state like open dropdowns, form input values, and scroll positions. The user sees the button turn red without the preview blinking or resetting.

The full chain — from LLM output through Kafka through MinIO through the syncer through Vite to the browser — completes in one to three seconds in practice. The Kafka and MinIO segments account for most of that time. The inotify, Vite recompilation, WebSocket delivery, and React Fast Refresh swap together take well under 100 milliseconds.

---

## The Dynamic Preview Proxy

The proxy deserves its own explanation because the design decision here is non-obvious and worth understanding precisely.

NGINX receives all traffic destined for `*.previews.designforge.website`. It can match the wildcard pattern. It cannot, however, know which specific runner pod a given subdomain should route to. NGINX configuration is static — it is defined in ConfigMaps that Kubernetes loads at deployment time. The mapping from `project-123.previews.designforge.website` to `192.244.1.12:5173` is not knowable at deployment time. It is determined at runtime when `workspace-service` claims a pod and reads its IP.

One might consider creating a Kubernetes Service per project — a Service with a selector targeting the pod labeled with `project-id={projectId}` — and configuring NGINX to route to that Service. This approach fails at scale for two reasons. First, Kubernetes Services are cluster-scoped resources with non-trivial etcd overhead. Creating and deleting thousands of Service objects as projects are deployed and reclaimed is operationally untenable — it creates significant churn in the cluster's control plane. Second, the dynamic nature of the routing table means the Service would need to be created, updated, and deleted as pod IPs change and pods are reclaimed. This is the same problem we are trying to solve, just expressed as Kubernetes resource management rather than Redis lookups.

`design-forge-proxy` is a Node.js service that runs in `design-forge-core` and handles this routing in application code. When a request arrives for `project-123.previews.designforge.website`, the proxy extracts the full hostname from the `Host` header (stripping any port), performs a Redis GET for the key `route:{hostname}`, and receives `{podIP}:5173`. It uses the `http-proxy` library to forward both HTTP requests and WebSocket upgrades to the target pod IP.

Redis lookup is the right tool here for a specific reason: it is the hot path for every single HTTP request and WebSocket frame going to any preview. The proxy handles thousands of requests per second across all active projects. A Redis GET against an in-memory store completes in under one millisecond. A Kubernetes API call — with its authentication overhead, API server processing, and etcd lookup — adds 10–50 milliseconds to every request. At the throughput of an active platform, that difference is significant and cumulative.

---

## Network Security and Isolation

The network policies enforce the trust boundary between the two namespaces with surgical precision.

In `design-forge-previews`, a single `NetworkPolicy` named `strict-preview-sandbox` applies to all pods with an empty pod selector — meaning it governs every pod in the namespace. The policy controls both ingress and egress.

For ingress, only traffic from `design-forge-proxy` pods in `design-forge-core` is permitted, and only on port 5173. No other source can reach a preview pod. External users cannot directly address pod IPs. Other preview pods cannot reach each other. Core platform services cannot reach preview pods except through the proxy. The pod IP is effectively private.

For egress, three explicit allow rules define what preview pods are permitted to contact. DNS on UDP and TCP port 53 to `kube-system` is allowed — without this, `npm install` cannot resolve `registry.npmjs.org`. TCP port 9000 to `design-forge-core` is allowed — this is the MinIO API port, and the syncer needs it for `mc mirror`. Public internet access (`0.0.0.0/0`) is allowed for `npm install`, but three private IP ranges are explicitly blocked as exceptions: `10.0.0.0/8`, `192.168.0.0/16`, and `172.16.0.0/12`. These ranges cover all typical Kubernetes overlay networks. A malicious script running inside a preview pod can download packages from the public npm registry, but it cannot probe internal cluster addresses, reach the PostgreSQL database, connect to Kafka, or access Redis. The internet access is real; the internal network access is completely blocked.

In `design-forge-core`, the `allow-internal-only` policy ensures that no external traffic can reach core services directly — only NGINX Ingress, which is explicitly allowed by the `allow-nginx-ingress` policy targeting the three publicly exposed services. The `allow-previews-to-minio` policy creates a deliberate and narrowly scoped exception, allowing pods in `design-forge-previews` to reach the MinIO pod specifically on port 9000. Without this exception, the syncer sidecar would have no path to MinIO, and file synchronization would be impossible.

The overall effect is a strict trust hierarchy enforced at the kernel networking level: NGINX can reach public-facing services; core services can reach each other; preview pods can reach MinIO and the public internet but nothing else internal; nothing from outside can reach preview pods except through the proxy.

---

## Real-Time Collaboration

Collaboration in DesignForge is mediated through a shared physical execution environment, not a dedicated real-time synchronization protocol.

`project_members` in `workspace_db` tracks the membership of each project. The composite primary key of `project_id` and `user_id` ensures uniqueness. The `project_role` field enforces a three-level permission hierarchy: `OWNER` with full control, `EDITOR` with the ability to modify and delete, and `VIEWER` with read-only access. Invitation is tracked through `invited_at` and `accepted_at` timestamps.

When two collaborators work on the same project, both are working against the same MinIO prefix (`projects/{projectId}/`), the same runner pod (labeled `project-id={projectId}` and `status=busy`), and the same Vite server on port 5173. There is one pod per project, shared by all members.

When collaborator A sends a prompt and the Intelligence Service generates a file change, Workspace Service writes the modified file to MinIO. The syncer sidecar in the shared pod detects the MinIO write and propagates it to `/app/`. Vite detects the file system change, recompiles the affected module, and sends an HMR notification over every open WebSocket connection to the pod. Collaborator B's browser has its own open WebSocket connection to the same Vite server through the same proxy. Vite notifies both WebSocket connections. Both browsers receive and apply the same HMR update. Both collaborators see the change in their preview simultaneously, without either of them triggering a page reload.

The collaboration model is eventually consistent rather than transactionally atomic. If two collaborators simultaneously submit prompts that modify the same file, the results of both LLM generations will be persisted to MinIO independently, and the syncer will apply whichever lands last. There is no merge, no conflict detection, and no operational transform. For the use case of building frontend interfaces through prompts, this is an acceptable trade-off — the LLM's outputs are typically coherent wholes rather than line-level edits.

---

## Architectural Decisions

### Why Kafka instead of synchronous HTTP between Intelligence and Workspace

File persistence is a side effect of LLM generation, not a prerequisite for streaming the response to the user. The user receives the model's output the moment it is generated. Persisting the generated files to MinIO can happen asynchronously without delaying the response. Kafka decouples these two concerns completely — `intelligence-service` does not wait for `workspace-service` to confirm a write before closing the SSE stream. If `workspace-service` is temporarily unavailable, Kafka durably holds the events until it recovers. A synchronous HTTP call would propagate `workspace-service` failures directly to the user's generation request.

### Why choreography-based Saga instead of orchestration

An orchestration-based Saga would require a central coordinator — a third service or a state machine embedded in one of the existing services — that tracks the progress of each Saga and issues commands to participants. For a two-participant Saga (`intelligence-service` and `workspace-service`), this coordination overhead adds complexity without benefit. Choreography keeps each service focused on its own events: `intelligence-service` publishes requests and handles responses; `workspace-service` consumes requests and publishes responses. Neither service needs to know about the internal implementation of the other.

### Why `processed_events` instead of Kafka exactly-once semantics

Kafka's exactly-once producer semantics guarantee that a message is written to the log exactly once. They do not guarantee that the consumer's side effects — writing to MinIO and inserting into `project_files` — are executed exactly once. Consumer side effects are not part of the Kafka transaction boundary. The `processed_events` table is a simple, durable, queryable idempotency key that makes the workspace-service's handling of any given `sagaId` exactly-once regardless of how many times Kafka delivers the event.

### Why database-per-service instead of a shared database

A shared database creates implicit coupling between services at the schema level. Services begin depending on each other's table structures. Migrations require coordination. Query patterns from one service can affect the performance of another. Separate databases with dedicated users eliminate all of these failure modes. The cost — explicit API calls instead of joins — is paid at service boundary crossings, which are already explicit in this architecture.

### Why MinIO instead of storing file contents in PostgreSQL

Project files are mutable blobs of arbitrary size. Object storage is purpose-built for this use case — it handles large content efficiently, supports prefix-based organization (`projects/{projectId}/`), integrates natively with the MinIO Client for `mc mirror` operations, and does not impose row size limits or TOAST overhead. PostgreSQL stores metadata: file paths, object keys, timestamps, and relationships. Each system does what it is designed for.

### Why the syncer sidecar instead of pushing files through Kubernetes exec

An alternative to the syncer sidecar would be for `workspace-service` to exec a file write command into the runner container every time a file changes. This approach has a critical flaw: Kubernetes exec connections have overhead, and running one per file change under active generation (where the LLM may modify multiple files per response) creates a high-frequency control-plane load. The syncer sidecar runs a persistent MinIO watch process — `mc mirror --watch` — that maintains a long-lived connection to MinIO and propagates changes with minimal latency and zero control-plane overhead per file update. It is also more reliable: if `workspace-service` restarts, the sidecar continues watching and syncing independently.

### Why Redis for routing instead of Kubernetes API lookup

The proxy resolves a routing entry on every HTTP request and every WebSocket frame going to any preview. This is the highest-frequency operation in the entire system. Redis GET operations on in-memory data complete in under one millisecond. A Kubernetes API call requires authentication token validation, API server processing, and an etcd read — adding 10–50 milliseconds per request. At the throughput of a live platform, routing must be a memory lookup, not an API call.

### Why a runner pool instead of on-demand pod creation

Pod scheduling in Kubernetes involves finding an eligible node, pulling container images if not cached, and initializing the container runtime. For a user clicking "Preview" and expecting to see their application immediately, any perceivable delay is a product failure. The runner pool pre-pays the scheduling and startup cost. Claiming an idle pod requires one label patch — a single Kubernetes API call taking under 100 milliseconds. The pool keeps two idle pods available, covering the common case of two users deploying simultaneously without contention.

### Why Spring Cloud Gateway instead of routing directly through NGINX

NGINX operates at the transport layer — it performs host-based routing and TLS termination based on URL patterns. It has no understanding of JWT tokens, no ability to validate them, and no capacity to enforce per-route authentication policies. Spring Cloud Gateway operates at the application layer. It understands HTTP semantics, can inspect headers, validate tokens with `JwtGatewayService`, apply route-specific filters like `StripPrefix`, enforce CORS policies, and return structured error responses. These are application concerns that belong in application code, not in a web server configuration file. NGINX handles the network boundary; the Gateway handles the API boundary.

### Why separate ACME challenge types for TLS

Let's Encrypt HTTP-01 challenge proves control of a specific hostname by serving a token at `/.well-known/acme-challenge/{token}`. It cannot issue wildcard certificates because a wildcard like `*.previews.designforge.website` does not have a single specific hostname to challenge. DNS-01 challenge proves control of the DNS zone by creating a TXT record at `_acme-challenge.previews.designforge.website`. DNS zone control implies authority over all subdomains, which is why DNS-01 is the only mechanism that can issue wildcard certificates. The Cloudflare API token allows cert-manager to create and clean up this TXT record automatically on each renewal cycle.

### Why preview namespace isolation

Generated code and its npm dependencies are untrusted. An LLM might generate code that attempts to fetch internal cluster metadata, probe internal IP ranges, or access environment variables exposed in the container. Running this code in the same namespace as PostgreSQL, Kafka, Redis, and application secrets would make those attacks trivially easy — the network is flat within a namespace by default. `design-forge-previews` creates a hard namespace boundary enforced by NetworkPolicy at the Linux kernel networking level. The preview pods have no path to core infrastructure other than the specific MinIO port explicitly allowed. The isolation is not advisory; it is enforced by iptables rules that the kernel applies to every packet.

### LLM Tool Calling Over Embedding Retrieval

The model sees the project file tree and uses `read_files(paths)` to fetch exact content. This avoids embedding infrastructure, vector index maintenance, stale embeddings after edits, and semantic retrieval tuning.

## Runtime Contracts

These contracts are cross-component and should be treated as architecture-level APIs:

| Contract | Producer | Consumer |
| --- | --- | --- |
| `route:{hostname}` Redis key | `KubernetesDeploymentServiceImpl` | `k8s/proxy/index.js` |
| `file-storage-request-event` topic | `AiGenerationServiceImpl` | `FileStorageConsumer` |
| `file-store-responses` topic | `FileStorageConsumer` | `IntelligenceSagaResponseHandler` |
| `projects/{projectId}/{filePath}` MinIO object path | `ProjectFileServiceImpl` | syncer sidecar and Workspace reads |
| Bucket `projects` | Workspace Service | MinIO and syncer sidecar |
| `WorkspaceClient` internal endpoints | `InternalWorkspaceController` | Intelligence Service |
| `AccountClient` internal endpoints | `InternalAccountController` | Workspace and Intelligence services |
| `/api/v1/account/**` | Gateway config | Browser clients |
| `/api/v1/workspace/**` | Gateway config | Browser clients |
| `/api/v1/intelligence/**` | Gateway config | Browser clients |
| `project-{projectId}.previews.designforge.website` | Workspace Service | Browser, Ingress, Proxy |

Changing any of these requires coordinated changes across multiple services.

## Failure Modes

### Config Service Slow Or Unavailable

Downstream services retry Config Server with `SPRING_CLOUD_CONFIG_RETRY_MAX_ATTEMPTS=10` and `SPRING_CLOUD_CONFIG_RETRY_INITIAL_INTERVAL=3000`. If Config Server becomes healthy within the retry window, services can start. If not, services cannot obtain their k8s configuration.

### LLM Stream Completes But File Persistence Fails

The user may receive streamed LLM output before Workspace has persisted the generated file. `chat_events.status` tracks the persistence outcome. `PENDING` means persistence is in flight, `CONFIRMED` means Workspace wrote the file, and `FAILED` means persistence failed.

### Kafka Redelivery

Kafka redelivery is safe because `workspace-service` checks `processed_events.sagaId`. Duplicate events are acknowledged with a success response without repeating the file write.

### No Idle Runner

If the pool has no `status=idle` pod, preview deployment fails with:

```text
No idle runners available. Please scale up the runner-pool.
```

### Existing Runner For Project

If a `Running` pod with `project-id={projectId}` and `status=busy` exists, Workspace reuses it and re-registers the Redis route instead of claiming a new pod.

### Preview Route Missing

If `design-forge-proxy` cannot find `route:{hostname}`, it returns:

```text
Preview not found or spinning up for {hostname}.
```

### Preview Server Starting

If Redis contains a route but the target server is not ready, proxying can fail with `502` and:

```text
Preview server starting...
```

## Part Two
## Table Of Contents

1. [System Overview](#system-overview)
2. [Namespace Architecture](#namespace-architecture)
3. [Configuration And Service Discovery](#configuration-and-service-discovery)
4. [Microservices](#microservices)
5. [RBAC And Kubernetes API Access](#rbac-and-kubernetes-api-access)
6. [Ingress And TLS](#ingress-and-tls)
7. [Stateful Infrastructure](#stateful-infrastructure)
8. [Database Ownership And Schemas](#database-ownership-and-schemas)
9. [AI Code Generation Pipeline](#ai-code-generation-pipeline)
10. [Kafka Saga And Event Choreography](#kafka-saga-and-event-choreography)
11. [Preview Execution Architecture](#preview-execution-architecture)
12. [File Synchronization And HMR](#file-synchronization-and-hmr)
13. [Dynamic Preview Proxy](#dynamic-preview-proxy)
14. [Network Policies And Isolation](#network-policies-and-isolation)
15. [Real-Time Collaboration Model](#real-time-collaboration-model)
16. [Architectural Decisions](#architectural-decisions)
17. [Source Map](#source-map)


## System Overview

DesignForge is deployed as a microservices architecture on Kubernetes. It has a platform control plane in `design-forge-core` and an execution plane in `design-forge-previews`.

The control plane owns user identity, billing, project metadata, AI generation, file persistence orchestration, gateway authentication, preview routing, and shared infrastructure. The execution plane runs user project previews in isolated Kubernetes pods. Generated code never executes inside the same namespace as account, billing, database, Kafka, Redis, or Config Server components.

At runtime, the system is composed of these major paths:

| Path | Description |
| --- | --- |
| API control path | Browser traffic enters through NGINX Ingress, reaches `api-gateway`, is JWT validated, and is routed to the owning backend service. |
| AI generation path | `intelligence-service` streams LLM output over SSE, parses XML-tagged model output, creates chat events, and publishes file persistence events to Kafka. |
| File persistence path | `workspace-service` consumes Kafka file storage requests, writes file contents to MinIO, writes file metadata to PostgreSQL, and publishes Saga responses. |
| Preview execution path | `workspace-service` claims a runner pod, mirrors project files from MinIO into `/app`, starts Vite on port `5173`, and registers preview routing in Redis. |
| Live preview path | NGINX routes wildcard preview hosts to `design-forge-proxy`, which resolves the target runner pod through Redis and forwards HTTP and WebSocket traffic to Vite. |

<img width="7022" height="4200" alt="System Architecture" src="https://github.com/user-attachments/assets/5dd5bbdd-74d7-4f1f-becd-6b2b8cc99620" />

<img width="1484" height="950" alt="Code Generation Architecture" src="https://github.com/user-attachments/assets/5981e42c-dc91-4852-9b6c-0ddf344d9265" />

<img width="2739" height="1679" alt="Code Execution Architecture" src="https://github.com/user-attachments/assets/24d1a12d-b304-4391-8198-20cb30dd4fff" />

<img width="2091" height="580" alt="Distributed Transaction" src="https://github.com/user-attachments/assets/2ed2a03f-5423-401d-b2ae-7525029cce21" />

## Namespace Architecture

### `design-forge-core`

`design-forge-core` is the trusted platform namespace. It hosts all platform services and all stateful infrastructure.

| Kubernetes resource | Kind | Role |
| --- | --- | --- |
| `config-service` | Deployment + Service | Spring Cloud Config Server on port `8888`. |
| `api-gateway` | Deployment + Service | Spring Cloud Gateway, container port `8080`, Service port `80`. |
| `account-service` | Deployment + Service | Authentication, users, subscriptions, plans, Stripe integration, container port `9050`, Service port `80`. |
| `workspace-service` | ServiceAccount + RBAC + Deployment + Service | Projects, project membership, file metadata, MinIO access, Redis route registration, preview pod orchestration, container port `9020`, Service port `80`. |
| `intelligence-service` | Deployment + Service | LLM generation, SSE streaming, chat persistence, Kafka Saga response handling, container port `9030`, Service port `80`. |
| `design-forge-proxy` | Deployment + Service | Dynamic reverse proxy for wildcard preview hosts on port `80`. |
| `design-forge-frontend` | Deployment + Service | Frontend static application on port `80`. |
| `pgvector` | StatefulSet + headless Service | PostgreSQL 16 using image `pgvector/pgvector:pg16`. |
| `kafka` | StatefulSet + headless Service | Single-node KRaft Kafka using image `confluentinc/confluent-local:7.5.0`. |
| `redis` | StatefulSet + headless Service | Preview route table using image `redis:7-alpine`. |
| `minio` | StatefulSet + Service | Object storage using image `minio/minio:latest`. |

The shared ConfigMap `design-forge-shared-config` exists in `design-forge-core` and provides cross-service preview configuration:

| Key | Value |
| --- | --- |
| `PREVIEW_DOMAIN` | `previews.designforge.website` |
| `PREVIEW_NAMESPACE` | `design-forge-previews` |
| `PROXY_PORT` | `80` |
| `APP_FRONTEND_URL` | `https://designforge.website` |

### `design-forge-previews`

`design-forge-previews` is the user-code execution namespace. It hosts preview pods created or claimed from the `runner-pool` Deployment.

| Attribute | Value |
| --- | --- |
| Deployment | `runner-pool` |
| Replicas | `2` |
| Initial pod labels | `app=runner`, `status=idle` |
| Claimed pod labels | `status=busy`, `project-id={projectId}` |
| Shared volume | `emptyDir` named `workspace` |
| Shared mount path | `/app` in both containers |

Each preview pod contains:

| Container | Image | Purpose |
| --- | --- | --- |
| `runner` | `node:20-alpine` | Holds the frontend project and runs the Vite development server on port `5173`. |
| `syncer` | `minio/mc` | Mirrors project files from MinIO into `/app` using `mc mirror` and `mc mirror --watch`. |

This namespace is intentionally treated as untrusted. It runs generated frontend code and third-party npm dependencies. NetworkPolicies prevent it from directly reaching core platform services except for the precise MinIO and DNS access required by the preview synchronization flow.

## Configuration And Service Discovery

All Spring platform services start with:

| Environment variable | Value |
| --- | --- |
| `SPRING_PROFILES_ACTIVE` | `k8s` |
| `CONFIG_SERVER_URL` | `http://config-service.design-forge-core.svc.cluster.local:8888` |
| `SPRING_CLOUD_CONFIG_FAIL_FAST` | `false` |
| `SPRING_CLOUD_CONFIG_RETRY_MAX_ATTEMPTS` | `10` |
| `SPRING_CLOUD_CONFIG_RETRY_INITIAL_INTERVAL` | `3000` |

`api-gateway`, `account-service`, `workspace-service`, and `intelligence-service` import externalized configuration through:

```yaml
spring:
  config:
    import: configserver:${CONFIG_SERVER_URL:http://localhost:8888}
```

The Config Service itself is a Spring Cloud Config Server. In the k8s profile it reads Git credentials from `app-secrets`:

| Env var | Secret | Secret key |
| --- | --- | --- |
| `GIT_USERNAME` | `app-secrets` | `GIT_USERNAME` |
| `GIT_PASSWORD` | `app-secrets` | `GIT_PASSWORD` |

The Config Server Git repository URI is:

```text
https://github.com/KaranLalwani-dev/designforge-config-server.git
```

The default Git label is:

```text
main
```

The k8s profile disables Eureka and Spring Cloud discovery:

```yaml
spring:
  cloud:
    discovery:
      enabled: false

eureka:
  client:
    enabled: false
    register-with-eureka: false
    fetch-registry: false
```

Production service discovery is Kubernetes DNS. Services address each other through Kubernetes Service names and StatefulSet DNS names instead of Eureka registry lookups.

Important DNS names and service endpoints include:

| Component | DNS / URL |
| --- | --- |
| Config Service | `http://config-service.design-forge-core.svc.cluster.local:8888` |
| PostgreSQL | `pgvector.design-forge-core.svc.cluster.local:5432` |
| Kafka | `kafka.design-forge-core.svc.cluster.local:9092` |
| Kafka broker pod | `kafka-0.kafka.design-forge-core.svc.cluster.local` |
| Kafka controller | `kafka-0.kafka.design-forge-core.svc.cluster.local:29093` |
| Redis | `redis-0.redis.design-forge-core.svc.cluster.local:6379` |
| MinIO | `http://minio.design-forge-core.svc.cluster.local:9000` |

The repository still contains a `discovery-service`, but the Kubernetes production profile disables Eureka. The active production topology relies on DNS names created by Kubernetes Services and headless Services.

### Spring Cloud Config Contract

The Config Server is expected to serve service-specific YAML files such as:

| Config file | Consumer |
| --- | --- |
| `account-service.yml` | `account-service` |
| `workspace-service.yml` | `workspace-service` |
| `intelligence-service.yml` | `intelligence-service` |
| `api-gateway.yml` | `api-gateway` |
| `application.yml` | Shared service configuration |

The shared production configuration contract includes:

| Concern | Value / contract |
| --- | --- |
| Kafka bootstrap server in k8s | `kafka.design-forge-core.svc.cluster.local:9092` |
| Kafka bootstrap server local default | `localhost:29092` |
| JPA schema mode | `spring.jpa.hibernate.ddl-auto=update` |
| Database dialect | PostgreSQL dialect |
| JWT property | `jwt.secretKey` |
| Kafka serializer/deserializer | JSON serializer and JSON deserializer |
| Kafka trusted packages | `com.karandev.distributed_design_forge.*` |
| Preview domain | `previews.designforge.website` |
| Preview namespace | `design-forge-previews` |
| Preview proxy port | `80` |

Secret values are not repeated in this README. The architecture depends on Secret keys and environment variable names, not hard-coded credential material.

### Runtime And Build Versions

The backend services are Java 21 Spring services built with Maven.

| Component | Version / artifact |
| --- | --- |
| Java | `21` |
| Spring Boot parent | `org.springframework.boot:spring-boot-starter-parent:4.0.6` |
| Spring Cloud BOM | `2025.1.1` |
| Spring AI BOM | `2.0.0-M8` in `account-service` and `intelligence-service` |
| MapStruct | `1.6.0` in `workspace-service` and `intelligence-service`; `1.6.3` property in `account-service` |
| Stripe SDK | `com.stripe:stripe-java:31.1.0` |
| Fabric8 Kubernetes client | `io.fabric8:kubernetes-client:7.3.1` |
| MinIO Java SDK | `io.minio:minio:8.6.0` |
| OkHttp URLConnection | `com.squareup.okhttp3:okhttp-urlconnection:5.3.2` |
| JJWT | `0.12.6` |
| Jib Maven plugin | `com.google.cloud.tools:jib-maven-plugin:3.4.4` |
| Proxy `http-proxy` | `^1.18.1` |
| Proxy `ioredis` | `^5.3.5` |

## Microservices

### `config-service`

`config-service` is the central configuration service.

| Attribute | Value |
| --- | --- |
| Namespace | `design-forge-core` |
| Deployment | `config-service` |
| Service | `config-service` |
| Image | `karanlalwani/design-forge-config-service:latest` |
| Image pull policy | `Always` |
| Container port | `8888` |
| Service port | `8888` |
| Spring application name | `config-service` |
| Config Git URI | `https://github.com/KaranLalwani-dev/designforge-config-server.git` |
| Default Git label | `main` |
| Resource requests | `100m` CPU, `192Mi` memory |
| Resource limits | `250m` CPU, `384Mi` memory |
| Readiness probe | `GET /actuator/health` on port `8888` |
| Readiness initial delay | `90s` |
| Readiness period | `15s` |
| Readiness timeout | `5s` |
| Readiness failure threshold | `3` |

The Config Service must become healthy before downstream services can reliably resolve their production configuration. Other services tolerate Config Service startup lag through `SPRING_CLOUD_CONFIG_RETRY_MAX_ATTEMPTS=10` and `SPRING_CLOUD_CONFIG_RETRY_INITIAL_INTERVAL=3000`.

### `api-gateway`

`api-gateway` is the application-aware API boundary behind NGINX Ingress.

| Attribute | Value |
| --- | --- |
| Namespace | `design-forge-core` |
| Deployment | `api-gateway` |
| Service | `api-gateway` |
| Image | `karanlalwani/design-forge-api-gateway:latest` |
| Image pull policy | `Always` |
| Container port | `8080` |
| Service port | `80` |
| Runtime | Spring Cloud Gateway WebFlux |
| Spring application name | `api-gateway` |
| JWT env var | `JWT_SECRET` |
| JWT Secret key | `app-secrets.JWT_SECRET` |
| Resource requests | `100m` CPU, `192Mi` memory |
| Resource limits | `250m` CPU, `384Mi` memory |
| Readiness probe | `GET /actuator/health` on port `8080` |
| Readiness initial delay | `90s` |
| Readiness period | `15s` |

The expected production routing contract is:

| External path | Internal target | Gateway filter |
| --- | --- | --- |
| `/api/v1/account/**` | `http://account-service` | `StripPrefix=2` |
| `/api/v1/workspace/**` | `http://workspace-service` | `StripPrefix=2` |
| `/api/v1/intelligence/**` | `http://intelligence-service` | `StripPrefix=2` |

JWT validation is centralized in `GatewayJwtAuthFilter`. It is a `GlobalFilter` with order `-1`, so it executes before normal gateway routing. It reads the request path, compares it against `app.security.public-routes` with `AntPathMatcher`, bypasses public paths, and validates all other requests through `JwtGatewayService`.

Public routes are:

| Route |
| --- |
| `/api/v1/account/auth/login` |
| `/api/v1/account/auth/signup` |
| `/api/v1/account/webhooks/**` |
| `/api/v1/workspace/public/**` |

For protected routes, `GatewayJwtAuthFilter` requires:

```text
Authorization: Bearer {token}
```

`JwtGatewayService` validates the token with `jwt.secretKey` using JJWT. If validation fails, the Gateway returns a JSON `ApiError` with `401 Unauthorized`.

The CORS filter allows:

| Origin |
| --- |
| `https://designforge.website` |
| `https://www.designforge.website` |
| `http://localhost:5173` |

It allows methods `GET`, `POST`, `PUT`, `PATCH`, `DELETE`, and `OPTIONS`, allows all headers, allows credentials, and uses `maxAge=3600`.

### `account-service`

`account-service` owns identity, authentication, user records, subscription state, plan records, and Stripe integration.

| Attribute | Value |
| --- | --- |
| Namespace | `design-forge-core` |
| Deployment | `account-service` |
| Service | `account-service` |
| Image | `karanlalwani/design-forge-account-service:latest` |
| Image pull policy | `Always` |
| Container port | `9050` |
| Service port | `80` |
| Context path | `/account` |
| Spring application name | `account-service` |
| Database | `account_db` |
| JDBC URL | `jdbc:postgresql://pgvector.design-forge-core.svc.cluster.local:5432/account_db` |
| Database user | `account_user` |
| Password env var | `DB_PASSWORD` |
| Password Secret key | `ACCOUNT_DB_PASSWORD` |
| JWT env var | `JWT_SECRET` |
| Stripe API env var | `STRIPE_API_KEY` |
| Stripe webhook env var | `STRIPE_WEBHOOK_SECRET` |
| Frontend URL env var | `FRONTEND_URL` from `APP_FRONTEND_URL` |
| Resource requests | `100m` CPU, `256Mi` memory |
| Resource limits | `250m` CPU, `512Mi` memory |
| Readiness probe | `GET /account/actuator/health` on port `9050` |
| Readiness initial delay | `120s` |
| Startup probe | `GET /account/actuator/health` on port `9050` |
| Startup failure threshold | `30` |
| Startup period | `10s` |
| Maximum startup window | `300s` |

Important controllers:

| Controller | Base path | Important routes |
| --- | --- | --- |
| `AuthController` | `/auth` | `POST /signup`, `POST /login` |
| `BillingController` | none | `GET /api/me/subscription`, `POST /api/payments/checkout`, `POST /api/payments/portal`, `POST /webhooks/payment` |
| `InternalAccountController` | `/internal/v1` | `GET /users/{id}`, `GET /users/by-email`, `GET /billing/current-plan` |

`workspace-service` and `intelligence-service` access Account Service through Feign clients named `account-service` with path `/account`. This preserves the database-per-service boundary: consumers ask Account Service for user and plan information instead of querying `account_db` directly.

### `workspace-service`

`workspace-service` owns project lifecycle, project membership, project file metadata, MinIO object access, preview orchestration, Redis route registration, and file storage Saga consumption.

| Attribute | Value |
| --- | --- |
| Namespace | `design-forge-core` |
| ServiceAccount | `workspace-service-account` |
| Deployment | `workspace-service` |
| Service | `workspace-service` |
| Image | `karanlalwani/design-forge-workspace-service:latest` |
| Image pull policy | `Always` |
| Container port | `9020` |
| Service port | `80` |
| Context path | `/workspace` |
| Spring application name | `workspace-service` |
| Database | `workspace_db` |
| JDBC URL | `jdbc:postgresql://pgvector.design-forge-core.svc.cluster.local:5432/workspace_db` |
| Database user | `workspace_user` |
| Password env var | `DB_PASSWORD` |
| Password Secret key | `WORKSPACE_DB_PASSWORD` |
| Redis host | `redis-0.redis.design-forge-core.svc.cluster.local:6379` |
| MinIO URL | `http://minio.design-forge-core.svc.cluster.local:9000` |
| MinIO bucket | `projects` |
| MinIO env vars | `MINIO_ROOT_USER`, `MINIO_ROOT_PASSWORD` |
| JWT env var | `JWT_SECRET` |
| Resource requests | `100m` CPU, `256Mi` memory |
| Resource limits | `250m` CPU, `512Mi` memory |
| Readiness probe | `GET /workspace/actuator/health` on port `9020` |
| Readiness initial delay | `90s` |
| Startup probe | `GET /workspace/actuator/health` on port `9020` |
| Startup failure threshold | `30` |
| Startup period | `10s` |

Important controllers:

| Controller | Base path | Important routes |
| --- | --- | --- |
| `ProjectController` | `/projects` | list, read, create, update, soft delete, and `POST /{id}/deploy` |
| `FileController` | `/projects/{projectId}/files` | file tree and file content reads |
| `ProjectMemberController` | project membership paths | collaborator management |
| `InternalWorkspaceController` | `/internal/v1` | file tree, file content, permission check for internal clients |

Internal endpoints consumed by `intelligence-service` include:

| Endpoint | Purpose |
| --- | --- |
| `GET /workspace/internal/v1/projects/{projectId}/files/tree` | Return file tree for prompt context. |
| `GET /workspace/internal/v1/projects/{projectId}/files/content?path=...` | Return raw file content for LLM tool calls. |
| `GET /workspace/internal/v1/projects/{projectId}/permissions/check?permission=...` | Check project permission for security expressions. |

### `intelligence-service`

`intelligence-service` owns chat streaming, LLM orchestration, prompt construction, tool calling, response parsing, chat persistence, token usage accounting, and Saga response handling.

| Attribute | Value |
| --- | --- |
| Namespace | `design-forge-core` |
| Deployment | `intelligence-service` |
| Service | `intelligence-service` |
| Image | `karanlalwani/design-forge-intelligence-service:latest` |
| Image pull policy | `Always` |
| Container port | `9030` |
| Service port | `80` |
| Context path | `/intelligence` |
| Spring application name | `intelligence-service` |
| Database | `intelligence_db` |
| JDBC URL | `jdbc:postgresql://pgvector.design-forge-core.svc.cluster.local:5432/intelligence_db` |
| Database user | `intelligence_user` |
| Password env var | `DB_PASSWORD` |
| Password Secret key | `INTELLIGENCE_DB_PASSWORD` |
| AI API env var | `AI_API_KEY` |
| AI API Secret key | `app-secrets.AI_API_KEY` |
| JWT env var | `JWT_SECRET` |
| LLM client | Spring AI OpenAI-compatible client |
| LLM base URL | `https://openrouter.ai/api/v1` |
| LLM model | `google/gemini-3-flash-preview` |
| LLM temperature | `0.2` |
| Resource requests | `100m` CPU, `256Mi` memory |
| Resource limits | `250m` CPU, `512Mi` memory |
| Readiness probe | `GET /intelligence/actuator/health` on port `9030` |
| Readiness initial delay | `90s` |
| Startup probe | `GET /intelligence/actuator/health` on port `9030` |
| Startup failure threshold | `30` |
| Startup period | `10s` |

`ChatController` exposes:

| Route | Behavior |
| --- | --- |
| `POST /chat/stream` | Produces `MediaType.TEXT_EVENT_STREAM_VALUE` and returns `Flux<ServerSentEvent<StreamResponse>>`. |
| `GET /chat/projects/{projectId}` | Returns persisted chat history. |

Core AI classes:

| Class | Responsibility |
| --- | --- |
| `AiGenerationServiceImpl` | Coordinates auth, prompt creation, streaming, buffering, persistence, parsing, and Kafka publish. |
| `PromptUtils` | Holds `CODE_GENERATION_SYSTEM_PROMPT`. |
| `FileTreeContextAdvisor` | Injects the current project file tree into the model prompt. |
| `CodeGenerationTools` | Registers the `read_files` tool using Spring AI `@Tool` and `@ToolParam`. |
| `LlmResponseParser` | Parses `<message>`, `<tool>`, and `<file>` tags into `ChatEvent` records. |
| `IntelligenceSagaResponseHandler` | Consumes file storage responses and updates `ChatEvent.status`. |

### `design-forge-frontend`

`design-forge-frontend` serves the browser application.

| Attribute | Value |
| --- | --- |
| Namespace | `design-forge-core` |
| Deployment | `design-forge-frontend` |
| Service | `design-forge-frontend` |
| Image | `karanlalwani/design-forge-frontend@sha256:4672c1c490cf4dd838709002226303fa9b9aae477cec5beaad2454a7c19d42d9` |
| Image pull policy | `Always` |
| Container port | `80` |
| Service type | `ClusterIP` |
| Service port | `80` |
| Resource requests | `100m` CPU, `128Mi` memory |
| Resource limits | `250m` CPU, `256Mi` memory |

It is externally reachable through `designforge.website` and `www.designforge.website`.

### `design-forge-proxy`

`design-forge-proxy` is the dynamic reverse proxy for preview traffic.

| Attribute | Value |
| --- | --- |
| Namespace | `design-forge-core` |
| Deployment | `design-forge-proxy` |
| Service | `design-forge-proxy` |
| Image | `karanlalwani/design-forge-proxy:latest` |
| Image pull policy | `Always` |
| Container port | `80` |
| Service port | `80` |
| Runtime | Node.js |
| Dependencies | `http-proxy@^1.18.1`, `ioredis@^5.3.5` |
| `REDIS_URL` | `redis://redis-0.redis.design-forge-core.svc.cluster.local:6379` |
| `PORT` | `80` |
| ConfigMap env source | `design-forge-shared-config` |
| Resource requests | `250m` CPU, `512Mi` memory |
| Resource limits | `250m` CPU, `512Mi` memory |

The proxy handles both HTTP requests and WebSocket upgrades. It creates an `http-proxy` server with:

```js
{
  ws: true,
  xfwd: true,
  changeOrigin: true
}
```

This matters because Vite HMR depends on WebSocket upgrade support.

## RBAC And Kubernetes API Access

`workspace-service` runs in `design-forge-core`, but it manages runner pods in `design-forge-previews`. This is implemented through explicit cross-namespace RBAC.

| Resource | Namespace | Name |
| --- | --- | --- |
| ServiceAccount | `design-forge-core` | `workspace-service-account` |
| Role | `design-forge-previews` | `preview-manager-role` |
| RoleBinding | `design-forge-previews` | `preview-manager-binding` |

`preview-manager-role` grants:

| API group | Resources | Verbs |
| --- | --- | --- |
| `""` | `pods`, `pods/exec`, `pods/log`, `pods/status` | `get`, `list`, `watch`, `create`, `update`, `patch`, `delete` |

`preview-manager-binding` binds the ServiceAccount from the core namespace to the Role in the preview namespace.

This RBAC path allows `KubernetesDeploymentServiceImpl` to use the Fabric8 Kubernetes Java client for finding idle pods, claiming them by patching labels, executing `mc mirror`, executing `npm install`, starting `npm run dev`, reading pod IPs, reading pod status, and deleting failed pods.

## Ingress And TLS

NGINX Ingress in the `ingress-nginx` namespace is the single external entry point. The application Ingress is `design-forge-main-ingress` in `design-forge-core`.

Ingress annotations:

| Annotation | Value | Reason |
| --- | --- | --- |
| `nginx.ingress.kubernetes.io/proxy-read-timeout` | `3600` | Keeps SSE reads open during long LLM generations. |
| `nginx.ingress.kubernetes.io/proxy-send-timeout` | `3600` | Keeps SSE writes open during long LLM generations. |
| `cert-manager.io/cluster-issuer` | `letsencrypt-prod` | Uses the HTTP-01 issuer for standard domains. |

Ingress host routing:

| Host | Backend Service | Backend port |
| --- | --- | --- |
| `designforge.website` | `design-forge-frontend` | `80` |
| `www.designforge.website` | `design-forge-frontend` | `80` |
| `api.designforge.website` | `api-gateway` | `80` |
| `*.previews.designforge.website` | `design-forge-proxy` | `80` |

TLS secrets:

| Secret | Hosts |
| --- | --- |
| `design-forge-main-tls` | `designforge.website`, `www.designforge.website`, `api.designforge.website` |
| `design-forge-previews-tls` | `*.previews.designforge.website` |

Two ACME issuers are used:

| Issuer | Challenge type | Use case |
| --- | --- | --- |
| `letsencrypt-prod` | HTTP-01 | Standard host certificates for `designforge.website`, `www.designforge.website`, and `api.designforge.website`. |
| `letsencrypt-dns` | DNS-01 via Cloudflare | Wildcard certificate for `*.previews.designforge.website`. |

Wildcard certificates cannot be issued with HTTP-01. HTTP-01 proves control of a single HTTP host by serving a challenge token. Wildcard certificates require proof of DNS zone control, so `*.previews.designforge.website` is issued by DNS-01 through Cloudflare.

The wildcard certificate resource is:

| Attribute | Value |
| --- | --- |
| Kind | `Certificate` |
| Name | `previews-wildcard-cert` |
| Namespace | `design-forge-core` |
| Secret | `design-forge-previews-tls` |
| Issuer name | `letsencrypt-dns` |
| Issuer kind | `ClusterIssuer` |
| DNS name | `*.previews.designforge.website` |

The DNS issuer uses Cloudflare token secret `cloudflare-api-token`, token key `api-token`, ACME server `https://acme-v02.api.letsencrypt.org/directory`, and private key secret `letsencrypt-dns-account-key`.

## Stateful Infrastructure

All stateful infrastructure runs in `design-forge-core` with `ReadWriteOnce` PVCs.

### PostgreSQL / pgvector

| Attribute | Value |
| --- | --- |
| Resource | StatefulSet `pgvector` |
| Image | `pgvector/pgvector:pg16` |
| Service | `pgvector` |
| Service type | Headless, `clusterIP: None` |
| DNS | `pgvector.design-forge-core.svc.cluster.local` |
| Port | `5432` |
| `PGDATA` | `/var/lib/postgresql/data/pgdata` |
| `POSTGRES_USER` | `postgres` |
| `POSTGRES_DB` | `postgres` |
| Root password Secret key | `POSTGRES_PASSWORD` |
| Resource requests | `100m` CPU, `256Mi` memory |
| Resource limits | `250m` CPU, `512Mi` memory |
| PVC name | `pgdata` |
| PVC size | `10Gi` |
| Mount path | `/var/lib/postgresql/data` |
| Init ConfigMap | `pgvector-init` |
| Init mount path | `/docker-entrypoint-initdb.d` |

`pgvector-init` creates:

| Database | User | Secret key |
| --- | --- | --- |
| `account_db` | `account_user` | `ACCOUNT_DB_PASSWORD` |
| `workspace_db` | `workspace_user` | `WORKSPACE_DB_PASSWORD` |
| `intelligence_db` | `intelligence_user` | `INTELLIGENCE_DB_PASSWORD` |

It grants all privileges on each database to the matching user and grants all privileges on each database's `public` schema to the matching user.

The image includes pgvector, but the current AI code path does not use vector similarity search. File retrieval is explicit: the LLM sees the file tree, calls `read_files`, and Workspace Service fetches exact object paths from MinIO.

### Kafka

| Attribute | Value |
| --- | --- |
| Resource | StatefulSet `kafka` |
| Image | `confluentinc/confluent-local:7.5.0` |
| Mode | KRaft, combined broker/controller |
| Replicas | `1` |
| Service | `kafka` |
| Service type | Headless, `clusterIP: None` |
| Broker pod DNS | `kafka-0.kafka.design-forge-core.svc.cluster.local` |
| Plaintext port | `9092` |
| Controller port | `29093` |
| `KAFKA_HEAP_OPTS` | `-Xms512M -Xmx512M` |
| `KAFKA_NODE_ID` | `1` |
| `KAFKA_PROCESS_ROLES` | `broker,controller` |
| `KAFKA_CONTROLLER_QUORUM_VOTERS` | `1@kafka-0.kafka.design-forge-core.svc.cluster.local:29093` |
| `KAFKA_LISTENERS` | `PLAINTEXT://0.0.0.0:9092,CONTROLLER://0.0.0.0:29093,PLAINTEXT_HOST://0.0.0.0:29092` |
| `KAFKA_ADVERTISED_LISTENERS` | `PLAINTEXT://kafka:9092,PLAINTEXT_HOST://localhost:29092` |
| `KAFKA_CONTROLLER_LISTENER_NAMES` | `CONTROLLER` |
| `KAFKA_INTER_BROKER_LISTENER_NAME` | `PLAINTEXT` |
| `KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR` | `1` |
| `KAFKA_GROUP_INITIAL_REBALANCE_DELAY_MS` | `0` |
| `KAFKA_TRANSACTION_STATE_LOG_MIN_ISR` | `1` |
| `KAFKA_TRANSACTION_STATE_LOG_REPLICATION_FACTOR` | `1` |
| `CLUSTER_ID` | `6PMpHYL9QkeyXRj9Nrp4KA` |
| Security context | `fsGroup: 1000`, `runAsUser: 1000`, `runAsGroup: 1000` |
| Resource requests | `250m` CPU, `512Mi` memory |
| Resource limits | `1000m` CPU, `1Gi` memory |
| PVC name | `kafka-data` |
| PVC size | `10Gi` |
| Mount path | `/var/lib/kafka/data` |

Kafka is used for the file storage Saga. Source-level topics and consumer groups are:

| Producer / consumer | Topic | Group ID | Payload |
| --- | --- | --- | --- |
| `intelligence-service` producer | `file-storage-request-event` | none | `FileStoreRequestEvent` |
| `workspace-service` consumer | `file-storage-request-event` | `workspace-group` | `FileStoreRequestEvent` |
| `workspace-service` producer | `file-store-responses` | none | `FileStoreResponseEvent` |
| `intelligence-service` consumer | `file-store-responses` | `intelligence-group` | `FileStoreResponseEvent` |

### MinIO

| Attribute | Value |
| --- | --- |
| Resource | StatefulSet `minio` |
| Image | `minio/minio:latest` |
| Args | `server /data --console-address :9001` |
| Service | `minio` |
| API port | `9000` |
| Console port | `9001` |
| Internal URL | `http://minio.design-forge-core.svc.cluster.local:9000` |
| Credential env vars | `MINIO_ROOT_USER`, `MINIO_ROOT_PASSWORD` |
| Credential Secret keys | `MINIO_ROOT_USER`, `MINIO_ROOT_PASSWORD` |
| Project bucket | `projects` |
| Resource requests | `100m` CPU, `128Mi` memory |
| Resource limits | `250m` CPU, `256Mi` memory |
| PVC name | `minio-data` |
| PVC size | `20Gi` |
| Mount path | `/data` |

MinIO stores actual file contents. PostgreSQL stores project and file metadata only.

### Redis

| Attribute | Value |
| --- | --- |
| Resource | StatefulSet `redis` |
| Image | `redis:7-alpine` |
| Service | `redis` |
| Service type | Headless, `clusterIP: None` |
| DNS | `redis-0.redis.design-forge-core.svc.cluster.local` |
| Port | `6379` |
| Resource requests | `100m` CPU, `128Mi` memory |
| Resource limits | `250m` CPU, `256Mi` memory |
| PVC name | `redis-data` |
| PVC size | `2Gi` |
| Mount path | `/data` |

Redis stores preview routing entries written by `workspace-service` and read by `design-forge-proxy`.

## Database Ownership And Schemas

DesignForge uses database-per-service ownership on one PostgreSQL StatefulSet. Each service has its own logical database and dedicated user.

| Service | Database | User | Owned domain |
| --- | --- | --- | --- |
| `account-service` | `account_db` | `account_user` | Users, plans, subscriptions, Stripe billing state. |
| `workspace-service` | `workspace_db` | `workspace_user` | Projects, project members, file metadata, processed event ids. |
| `intelligence-service` | `intelligence_db` | `intelligence_user` | Chat sessions, chat messages, chat events, token usage logs. |

No service should directly query another service's database. Cross-service reads go through Feign clients.

### Account Service Data

| Entity | Table | Important fields |
| --- | --- | --- |
| `User` | `users` | `id`, `username`, `password`, `name`, `stripeCustomerId`, `createdAt`, `updatedAt`, `deletedAt` |
| `Plan` | `plan` | `id`, `name`, `stripePriceId`, `maxProjects`, `maxTokensPerDay`, `maxPreviews`, `unlimitedAi`, `active` |
| `Subscription` | `subscription` | `id`, `user`, `plan`, `status`, `stripeSubscriptionId`, `currentPeriodStart`, `currentPeriodEnd`, `cancelAtPeriodEnd`, `createdAt`, `updatedAt` |

<img width="892" height="956" alt="account_db" src="https://github.com/user-attachments/assets/0d478e36-0e6b-4477-9beb-19b0b3b53af7" />

### Workspace Service Data

| Entity | Table | Important fields |
| --- | --- | --- |
| `Project` | `projects` | `id`, `name`, `isPublic`, `createdAt`, `updatedAt`, `deletedAt` |
| `ProjectMember` | `project_members` | `id.projectId`, `id.userId`, `project`, `projectRole`, `invitedAt`, `acceptedAt` |
| `ProjectFile` | `project_files` | `id`, `project`, `path`, `minioObjectKey`, `createdAt`, `updatedAt` |
| `ProcessedEvent` | `processed_events` | `sagaId`, `processedAt` |

`Project` declares indexes `idx_projects_updated_at_desc`, `idx_projects_deleted_at_updated_at_desc`, and `idx_project_deleted_at`.

`ProjectRole` maps to:

| Role | Permissions |
| --- | --- |
| `OWNER` | `VIEW`, `EDIT`, `DELETE`, `MANAGE_MEMBERS`, `VIEW_MEMBERS` |
| `EDITOR` | `VIEW`, `EDIT`, `DELETE`, `VIEW_MEMBERS` |
| `VIEWER` | `VIEW`, `VIEW_MEMBERS` |

<img width="712" height="1046" alt="workspace_db" src="https://github.com/user-attachments/assets/e3c3dcb9-bb52-4a29-a59f-7230619bc594" />

### Intelligence Service Data

| Entity | Table | Important fields |
| --- | --- | --- |
| `ChatSession` | `chat_sessions` | composite id `projectId` + `userId`, `createdAt`, `updatedAt`, `deletedAt` |
| `ChatMessage` | `chat_messages` | `id`, `chatSession`, `role`, `events`, `content`, `tokensUsed`, `createdAt` |
| `ChatEvent` | `chat_events` | `id`, `chatMessage`, `type`, `sequenceOrder`, `content`, `filePath`, `metadata`, `sagaId`, `status` |
| `UsageLog` | `usage_logs` | `id`, `userId`, `date`, `tokensUsed` |

`ChatEventType` values are `THOUGHT`, `MESSAGE`, `FILE_EDIT`, and `TOOL_LOG`.

`ChatEventStatus` values are `PENDING`, `FAILED`, and `CONFIRMED`.

`UsageLog` has a unique constraint on `user_id` and `date`, giving one token counter per user per day.

<img width="1104" height="836" alt="intelligence_db" src="https://github.com/user-attachments/assets/65ecc062-e725-410d-84ba-921b6bb1f30e" />

## AI Code Generation Pipeline

The user-facing AI flow starts with a browser prompt and ends with one or more generated source files being persisted to MinIO.

```mermaid
sequenceDiagram
  participant Browser as Browser
  participant Ingress as NGINX Ingress
  participant Gateway as api-gateway
  participant Intel as intelligence-service
  participant Workspace as workspace-service
  participant LLM as OpenRouter / google/gemini-3-flash-preview
  participant Kafka as Kafka
  participant MinIO as MinIO

  Browser->>Ingress: POST /api/v1/intelligence/... SSE
  Ingress->>Gateway: api.designforge.website
  Gateway->>Intel: JWT validated + StripPrefix=2
  Intel->>Workspace: WorkspaceClient.getFileTree(projectId)
  Intel->>LLM: system prompt + FILE_TREE + user prompt + read_files tool
  LLM->>Intel: read_files(paths)
  Intel->>Workspace: WorkspaceClient.getFileContent(projectId, path)
  Workspace->>MinIO: read projects/{projectId}/{path}
  Workspace-->>Intel: raw file content
  Intel->>LLM: file contents
  LLM-->>Intel: streamed XML-tagged response
  Intel-->>Browser: ServerSentEvent<StreamResponse>
  Intel->>Intel: parse response into ChatEvent rows
  Intel->>Kafka: file-storage-request-event
```

### SSE Transport

The streaming endpoint is:

| Controller | Route | Produces |
| --- | --- | --- |
| `ChatController` | `POST /chat/stream` | `MediaType.TEXT_EVENT_STREAM_VALUE` |

The endpoint returns `Flux<ServerSentEvent<StreamResponse>>`.

The NGINX annotations `proxy-read-timeout=3600` and `proxy-send-timeout=3600` are critical. LLM generation can hold an SSE response open for longer than default ingress timeouts.

`AiGenerationServiceImpl.streamResponse` streams chunks to the caller while appending the complete model output to a `StringBuilder`. When the stream completes, `finalizeChats` parses and persists the buffered response.

### Prompt Context

The system prompt lives in `PromptUtils.CODE_GENERATION_SYSTEM_PROMPT`. It instructs the model to output:

| Tag | Purpose |
| --- | --- |
| `<tool args="...">...</tool>` | Declare files that should be read. |
| `<message phase="...">...</message>` | Communicate planning and completion text. |
| `<file path="...">...</file>` | Emit complete file contents for generated or modified files. |

`FileTreeContextAdvisor` implements Spring AI `StreamAdvisor`. It calls `workspaceClient.getFileTree(projectId)` and injects a `SystemMessage` containing `---- FILE_TREE ----`.

The LLM therefore chooses files from an explicit file tree. There is no embedding store, no vector similarity search, and no retrieval index in the current generation path.

### Tool Calling

`CodeGenerationTools` registers exactly one tool:

```java
@Tool(name = "read_files",
        description = "Read the content of files. Only input the file names present inside the FILE_TREE. DO NOT input any path which is not present under the FILE_TREE.")
public List<String> readFiles(
        @ToolParam(description = "List of relative paths (e.g., ['src/App.tsx'])")
        List<String> paths
);
```

For each path:

1. A leading `/` is removed if present.
2. `WorkspaceClient.getFileContent(projectId, cleanPath)` is called.
3. Workspace Service reads the object from MinIO.
4. The tool returns file contents wrapped as:

```text
--- START OF FILE: src/App.tsx ---
{content}
--- END OF FILE ---
```

### Response Parsing

`LlmResponseParser` parses `<message>`, `<file>`, and `<tool>` tags.

| Tag | Resulting event type | Status |
| --- | --- | --- |
| `message` | `MESSAGE` | `CONFIRMED` |
| `file` | `FILE_EDIT` | `PENDING` |
| `tool` | `TOOL_LOG` | `CONFIRMED` |

For every `FILE_EDIT`, Intelligence creates a UUID `sagaId`, assigns it to the event, and publishes:

| Topic | Key | Payload |
| --- | --- | --- |
| `file-storage-request-event` | `project-{projectId}` | `FileStoreRequestEvent(projectId, sagaId, filePath, content, userId)` |

## Kafka Saga And Event Choreography

File persistence is implemented as a choreography-based Saga over Kafka.

```mermaid
sequenceDiagram
  participant Intel as intelligence-service
  participant IntelDB as intelligence_db
  participant Kafka as Kafka
  participant Workspace as workspace-service
  participant WorkspaceDB as workspace_db
  participant MinIO as MinIO

  Intel->>IntelDB: Save ChatEvent FILE_EDIT status=PENDING
  Intel->>Kafka: Publish file-storage-request-event
  Kafka->>Workspace: Deliver FileStoreRequestEvent
  Workspace->>WorkspaceDB: Check processed_events by sagaId
  alt duplicate sagaId
    Workspace->>Kafka: Publish file-store-responses success=true
  else new sagaId
    Workspace->>MinIO: Write projects/{projectId}/{filePath}
    Workspace->>WorkspaceDB: Upsert project_files metadata
    Workspace->>WorkspaceDB: Insert ProcessedEvent(sagaId, processedAt)
    Workspace->>Kafka: Publish file-store-responses
  end
  Kafka->>Intel: Deliver FileStoreResponseEvent
  Intel->>IntelDB: Mark ChatEvent CONFIRMED or FAILED
```

`FileStoreRequestEvent`:

```java
public record FileStoreRequestEvent(
        Long projectId,
        String sagaId,
        String filePath,
        String content,
        Long userId
) {}
```

`FileStoreResponseEvent`:

```java
@Builder
public record FileStoreResponseEvent(
        String sagaId,
        boolean success,
        String errorMessage,
        Long projectId
) {}
```

`FileStorageConsumer.consumeFileEvent` listens on `file-storage-request-event` with group `workspace-group`.

`IntelligenceSagaResponseHandler.handleSagaResponse` listens on `file-store-responses` with group `intelligence-group`.

Kafka provides at-least-once delivery. The `processed_events` table provides exactly-once effect at the Workspace Service boundary by making `sagaId` the durable deduplication key.

## Preview Execution Architecture

The preview execution path is implemented by `KubernetesDeploymentServiceImpl`.

### Runner Pod Shape

The `runner` container:

| Attribute | Value |
| --- | --- |
| Name | `runner` |
| Image | `node:20-alpine` |
| Working directory | `/app` |
| Command | `/bin/sh -c "sleep infinity"` |
| Port | `5173` |
| Volume mount | `workspace` at `/app` |
| Resource requests | `250m` CPU, `512Mi` memory |
| Resource limits | `500m` CPU, `1Gi` memory |

The `syncer` container:

| Attribute | Value |
| --- | --- |
| Name | `syncer` |
| Image | `minio/mc` |
| Command | `/bin/sh -c "sleep infinity"` |
| Volume mount | `workspace` at `/app` |
| `MINIO_USER` | `app-secrets.MINIO_ROOT_USER` |
| `MINIO_PASS` | `app-secrets.MINIO_ROOT_PASSWORD` |
| `MC_HOST_myminio` | `http://$(MINIO_USER):$(MINIO_PASS)@minio.design-forge-core.svc.cluster.local:9000` |
| Resource requests | `100m` CPU, `128Mi` memory |
| Resource limits | `200m` CPU, `256Mi` memory |

### Deployment Flow

`deploy(projectId)` performs:

1. Build domain `project-{projectId}.previews.designforge.website`.
2. Build URL `https://project-{projectId}.previews.designforge.website` because `PROXY_PORT` is `80`.
3. Search for an existing active pod with labels `project-id={projectId}` and `status=busy`, requiring phase `Running`.
4. If found, re-register the Redis route and return the preview URL.
5. If not found, claim the first pod with `status=idle`.
6. Patch labels to `status=busy` and `project-id={projectId}`.
7. Execute initial sync in the `syncer` container:

```sh
rm -rf /app/* && mc mirror --overwrite myminio/projects/{projectId}/ /app/
```

8. Start continuous sync:

```sh
nohup mc mirror --overwrite --watch myminio/projects/{projectId}/ /app/ > /app/sync.log 2>&1 &
```

9. Start the dev server:

```sh
npm install && nohup npm run dev -- --host 0.0.0.0 --port 5173 > /app/dev.log 2>&1 &
```

10. Read `pod.status.podIP`.
11. Register route in Redis:

```text
route:project-{projectId}.previews.designforge.website -> {podIP}:5173
```

12. Set route TTL to `6` hours.

## File Synchronization And HMR

The live preview update chain is:

```mermaid
flowchart LR
  FileEdit["LLM generated <file path=...>"]
  Intel["intelligence-service\nChatEvent PENDING"]
  KafkaReq["Kafka\nfile-storage-request-event"]
  Workspace["workspace-service\nFileStorageConsumer"]
  MinIO["MinIO\nprojects/{projectId}/{filePath}"]
  Syncer["syncer sidecar\nmc mirror --watch"]
  Volume["emptyDir\n/app"]
  Vite["runner container\nVite :5173"]
  Proxy["design-forge-proxy\nHTTP + WebSocket"]
  Browser["Browser\nReact Fast Refresh"]

  FileEdit --> Intel
  Intel --> KafkaReq
  KafkaReq --> Workspace
  Workspace --> MinIO
  MinIO --> Syncer
  Syncer --> Volume
  Volume --> Vite
  Vite --> Proxy
  Proxy --> Browser
```

Detailed flow:

1. The model emits `<file path="src/components/ProfileCard.tsx">...</file>`.
2. `LlmResponseParser` creates a `ChatEvent` with `type=FILE_EDIT` and `status=PENDING`.
3. Intelligence publishes `FileStoreRequestEvent` to `file-storage-request-event`.
4. Workspace consumes the event.
5. Workspace checks `processed_events`.
6. Workspace writes MinIO object `projects/{projectId}/src/components/ProfileCard.tsx`.
7. Workspace upserts `project_files` metadata.
8. The syncer sidecar's `mc mirror --watch` sees the object update.
9. The syncer writes the updated file to `/app/src/components/ProfileCard.tsx`.
10. Vite's filesystem watcher detects the change.
11. Vite recompiles the affected module.
12. Vite sends an HMR update over WebSocket.
13. The browser receives the update through NGINX Ingress and `design-forge-proxy`.
14. React Fast Refresh swaps the component without a full page reload where possible.

## Dynamic Preview Proxy

NGINX Ingress can match the wildcard host `*.previews.designforge.website`, but it does not know which project maps to which runner pod. `design-forge-proxy` performs that dynamic resolution.

Proxy request behavior:

1. Read `Host` header.
2. Strip any port from the host.
3. Lookup Redis key `route:{hostname}`.
4. If no route exists, respond `Preview not found or spinning up for {hostname}.`
5. If a route exists, proxy to `http://{podIP}:5173`.
6. For WebSocket upgrades, call `proxy.ws(...)`.
7. For HTTP requests, call `proxy.web(...)`.

Actual route contract:

| Producer | Key | Value | TTL |
| --- | --- | --- | --- |
| `workspace-service` | `route:project-{projectId}.previews.designforge.website` | `{podIP}:5173` | `6` hours |

Redis is used instead of querying Kubernetes on every request because proxy resolution is on the hot path for every preview asset and every HMR WebSocket message.

## Network Policies And Isolation

### Preview Namespace Policy

`strict-preview-sandbox` applies to all pods in `design-forge-previews`:

```yaml
podSelector: {}
policyTypes:
  - Ingress
  - Egress
```

Allowed ingress:

| Source namespace | Source pod selector | Port |
| --- | --- | --- |
| `design-forge-core` | `app=design-forge-proxy` | TCP `5173` |

Allowed egress:

| Destination | Ports | Purpose |
| --- | --- | --- |
| Namespace `kube-system` | UDP `53`, TCP `53` | DNS resolution. |
| Namespace `design-forge-core` | TCP `9000` | MinIO syncer communication. |
| `0.0.0.0/0` except private ranges | all ports implied by rule | Public internet access for `npm install`. |

Blocked private ranges in the public internet egress rule:

| CIDR |
| --- |
| `10.0.0.0/8` |
| `192.168.0.0/16` |
| `172.16.0.0/12` |

### Core Namespace Policies

| Policy | Target | Allowed source |
| --- | --- | --- |
| `allow-internal-only` | All pods in `design-forge-core` | Namespace `design-forge-core` |
| `allow-previews-to-minio` | Pods with `app=minio` | Namespace `design-forge-previews` on TCP `9000` |
| `allow-nginx-ingress` | Pods with `app` in `api-gateway`, `design-forge-proxy`, `design-forge-frontend` | Namespace `ingress-nginx` |
| `allow-nginx-to-acme-solver` | Pods with `acme.cert-manager.io/http01-solver=true` | Namespace `ingress-nginx` |

The result is a strict trust boundary: core services can communicate internally, NGINX can reach only public entry points, preview pods can reach MinIO only on the required port, and generated user code cannot freely access core infrastructure.

## Real-Time Collaboration Model

Collaboration is project-scoped. `project_members` contains a composite id of `projectId` and `userId`, plus `projectRole`, `invitedAt`, and `acceptedAt`.

All collaborators on a project share:

| Shared resource | Value |
| --- | --- |
| Preview host | `project-{projectId}.previews.designforge.website` |
| MinIO prefix | `projects/{projectId}/` |
| Runner pod | The current pod labeled `project-id={projectId}`, `status=busy` |
| Vite server | The runner container on port `5173` |

When any collaborator triggers generation, Intelligence generates file edits, Workspace persists files to the shared MinIO prefix, the syncer sidecar mirrors the update into `/app`, Vite emits HMR from the project runner, and all connected collaborators receive updates through the same preview host.

## Source Map

| Area | Files |
| --- | --- |
| Namespaces and shared config | `k8s/infra/namespaces.yaml` |
| Ingress | `k8s/infra/ingress.yaml` |
| HTTP-01 issuer | `k8s/infra/cluster-issuer.yaml` |
| DNS-01 issuer | `k8s/infra/cluster-issuer-dns.yaml` |
| Wildcard certificate | `k8s/infra/wildcard-certificate.yaml` |
| ACME solver policy | `k8s/infra/acme-solver-network-policy.yaml` |
| Core NetworkPolicies | `k8s/infra/core-network-policies.yaml` |
| Preview NetworkPolicies | `k8s/infra/preview-network-policies.yaml` |
| Runner pool | `k8s/infra/runner-pool.yaml` |
| Config Service | `k8s/services/config-service.yaml`, `config-service/src/main/resources/application.yaml` |
| API Gateway | `k8s/services/api-gateway.yaml`, `api-gateway/src/main/java/...` |
| Account Service | `k8s/services/account-service.yaml`, `account-service/src/main/java/...` |
| Workspace Service | `k8s/services/workspace-service.yaml`, `workspace-service/src/main/java/...` |
| Intelligence Service | `k8s/services/intelligence-service.yaml`, `intelligence-service/src/main/java/...` |
| Frontend | `k8s/services/frontend.yaml` |
| Proxy | `k8s/proxy/proxy-deployment.yaml`, `k8s/proxy/index.js`, `k8s/proxy/package.json` |
| PostgreSQL | `k8s/stateful/pgvector.yaml` |
| Kafka | `k8s/stateful/kafka.yaml` |
| MinIO | `k8s/stateful/minio.yaml` |
| Redis | `k8s/stateful/redis.yaml` |
| AI generation | `AiGenerationServiceImpl`, `PromptUtils`, `FileTreeContextAdvisor`, `CodeGenerationTools`, `LlmResponseParser` |
| Saga events | `FileStoreRequestEvent`, `FileStoreResponseEvent`, `FileStorageConsumer`, `IntelligenceSagaResponseHandler` |
| Preview orchestration | `KubernetesDeploymentServiceImpl` |
| File persistence | `ProjectFileServiceImpl`, `StorageConfig` |
| Gateway auth | `GatewayJwtAuthFilter`, `JwtGatewayService`, `SecurityProperties`, `CorsConfig` |
