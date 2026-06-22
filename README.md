# DesignForge Architecture

DesignForge is an AI-powered frontend code generation and live execution platform. Users describe UI and frontend feature changes in natural language, the Intelligence Service generates React/Vite project code through an LLM-assisted workflow, and the resulting files are reflected in an isolated live preview environment.

This README is intentionally written as a deep technical architecture document for senior engineers. It focuses on system design, component boundaries, Kubernetes topology, runtime contracts, data ownership, event flows, security isolation, and architectural tradeoffs. It does not provide installation steps, deployment instructions, or a getting-started guide.

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
17. [Runtime Contracts](#runtime-contracts)
18. [Failure Modes](#failure-modes)
19. [Source Map](#source-map)

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

```mermaid
flowchart LR
  Browser["React frontend browser"]
  Ingress["NGINX Ingress\n design-forge-main-ingress"]
  Gateway["api-gateway\nSpring Cloud Gateway\n:8080 -> Service :80"]
  Account["account-service\n/account :9050"]
  Workspace["workspace-service\n/workspace :9020"]
  Intelligence["intelligence-service\n/intelligence :9030"]
  Config["config-service\nSpring Cloud Config\n:8888"]
  Pg["pgvector\npgvector/pgvector:pg16\n:5432"]
  Kafka["kafka\nconfluentinc/confluent-local:7.5.0\n:9092"]
  MinIO["minio\nminio/minio:latest\n:9000"]
  Redis["redis\nredis:7-alpine\n:6379"]
  Proxy["design-forge-proxy\nNode http-proxy\n:80"]
  Runner["runner-pool pod\nrunner :5173 + syncer sidecar"]

  Browser --> Ingress
  Ingress --> Gateway
  Gateway --> Account
  Gateway --> Workspace
  Gateway --> Intelligence
  Account --> Pg
  Workspace --> Pg
  Intelligence --> Pg
  Account --> Config
  Workspace --> Config
  Intelligence --> Config
  Gateway --> Config
  Intelligence --> Kafka
  Kafka --> Workspace
  Workspace --> Kafka
  Kafka --> Intelligence
  Workspace --> MinIO
  Workspace --> Redis
  Workspace --> Runner
  MinIO --> Runner
  Browser --> Ingress
  Ingress --> Proxy
  Proxy --> Redis
  Proxy --> Runner
```
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

## Architectural Decisions

### Kafka Over Synchronous HTTP

File persistence is not part of the SSE chunk delivery path. Kafka decouples LLM output from MinIO writes and Workspace availability. Intelligence can record generated intent and publish a durable event, while Workspace applies the side effect and reports the result asynchronously.

### Choreography-Based Saga Over Orchestration

The file persistence flow has two domain services. A central Saga orchestrator would add another component and another state machine. Choreography lets each service own its local state.

### `processed_events` Over Kafka Exactly-Once

Kafka exactly-once semantics do not automatically make PostgreSQL and MinIO side effects exactly-once. Workspace needs to protect the actual effect: writing a file and recording its metadata. `processed_events.sagaId` is simple, durable, queryable, and sufficient for idempotency under Kafka at-least-once delivery.

### Database-Per-Service Over Shared Database

Each service has its own database and user. This prevents hidden coupling through cross-service joins and keeps schema ownership local. Cross-service reads are explicit API calls through Feign clients.

### MinIO Blob Storage Over Database File Contents

Project files are mutable blobs. Object storage is better suited for large content, prefix-based project organization, and sidecar synchronization. PostgreSQL stores metadata such as path, object key, membership, events, and idempotency records.

### LLM Tool Calling Over Embedding Retrieval

The model sees the project file tree and uses `read_files(paths)` to fetch exact content. This avoids embedding infrastructure, vector index maintenance, stale embeddings after edits, and semantic retrieval tuning.

### Syncer Sidecar Over Central Sync Service

The sidecar shares `/app` with the runner and mirrors MinIO directly into the local filesystem. This avoids pushing every file change through Kubernetes exec and keeps continuous sync inside the preview pod.

### Redis Routing Over Kubernetes API Lookup

Preview routing is a per-request hot path. Redis returns `{podIP}:5173` quickly. Kubernetes API lookup on every request would add unnecessary authentication, object lookup, API server, and etcd overhead.

### Runner Pool Over On-Demand Pods

The `runner-pool` keeps `2` pods pre-scheduled. Claiming an idle pod avoids scheduling and image-pull latency when the user requests a preview.

Idle requested capacity:

| Scope | CPU | Memory |
| --- | --- | --- |
| One runner container | `250m` | `512Mi` |
| One syncer container | `100m` | `128Mi` |
| One full idle pod | `350m` | `640Mi` |
| Two idle pods | `700m` | `1280Mi` |

### Spring Cloud Gateway Over Direct NGINX Service Routing

NGINX owns host routing and TLS. Spring Cloud Gateway owns API semantics: JWT validation, public route bypass, path rewriting, gateway errors, and future API policies such as rate limiting.

### Separate ACME Challenge Types

HTTP-01 is appropriate for normal hosts. DNS-01 is required for wildcard certificates. The wildcard preview domain uses Cloudflare DNS-01 because `*.previews.designforge.website` cannot be issued through HTTP-01.

### Preview Namespace Isolation

Generated code and npm dependencies run in `design-forge-previews`. That code must not be trusted with access to core databases, Kafka, Redis, Config Server, account services, or internal private networks. NetworkPolicy enforces this boundary.

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
