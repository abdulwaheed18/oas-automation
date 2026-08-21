# OAS Automation Test Suite

A self-contained **Spring Boot 3.4.1** application (backend + embedded UI) that checks whether an
API gateway (e.g. **Tyk**) actually **enforces the OpenAPI contract** of the specs you onboard.

You onboard hundreds of OpenAPI (OAS) specs a year and share the gateway URL with consumers — but
how do you know the gateway rejects everything the spec says it should? A field marked `required`
that the gateway silently lets through, an `enum` that isn't validated, a missing bearer token that
still returns `200`… those are the bugs this tool hunts for, automatically, instead of by hand.

Give it a spec, pick the endpoints, and it generates a battery of **negative / edge-case requests**
— each one changing **exactly one field** — fires them at your gateway, and tells you where the
gateway failed to reject an invalid request.

---

## What it does

1. **Screen 1 — Spec & source.** Enter API name, version and notes, then load the OpenAPI document
   from one of three sources:
   - **YAML / JSON file** upload
   - **Clipboard** — paste the spec text directly
   - **Nexus ZIP URL** — the tool downloads the archive, extracts it and reads the `openapi.yaml`
     (or `openapi.json` / `swagger.*`) inside it automatically.
2. **Screen 2 — Choose endpoints.** Every operation in the spec is listed with checkboxes. Test one
   endpoint or all of them.
3. **Screen 3 — Test cases & run.** The tool generates negative test cases for the selected
   endpoints (see [How test cases are generated](#how-test-cases-are-generated)). Enter the
   **target base URL** and a **bearer token**, then execute.
4. **Screen 4 — Results.** Per-case **PASS / FAIL / ERROR** with expected vs. actual status, the
   response body, latency, and a summary. **FAIL** means the gateway did *not* enforce the contract
   (e.g. it accepted an invalid request). Download a self-contained **HTML report** that also
   includes the notes you entered on screen 1.

---

## How test cases are generated

The generator first builds a **fully-valid baseline request** for each operation (valid auth, all
required parameters and a complete, schema-valid JSON body). Every negative case is that baseline
with **exactly one** thing made invalid — so a failure points at a single root cause.

| Area | Cases generated |
|------|-----------------|
| **Auth** (bearer / JWT) | valid baseline, missing header, malformed token, empty token |
| **Headers** (required) | missing; plus type / enum / pattern / length / format violations |
| **Query params** (required) | missing; plus type / enum / range / length / format violations |
| **Path params** | type / format / range violations |
| **Request body** | missing body, malformed JSON, missing each required field, `null` for required field, wrong type, enum violation, pattern (with special characters), min/max length, min/max value |

Each negative case expects the gateway to respond with a **4xx** (auth cases expect **401/403**). If
the gateway returns **2xx**, the case **FAILS** — the gateway let an invalid request through.

> The **positive baseline** case is your sanity check: if it fails (e.g. wrong base URL or an
> expired token), the other results are unreliable — the report calls this out.

---

## Running it

### Prerequisites
- **JDK 21+**
- **Maven 3.9+** (or use your IDE's bundled Maven)

### Build & run
```bash
mvn spring-boot:run
```
or build the jar and run it:
```bash
mvn -DskipTests package
java -jar target/oas-automation.jar
```
Then open **http://localhost:8080**.

Sample specs are included in [`samples/`](samples) — the **same spec in both formats**
([`petstore.yaml`](samples/petstore.yaml) and [`petstore.json`](samples/petstore.json)) — to try the
flow. **Both YAML and JSON OpenAPI documents are supported** on every source (file upload, clipboard
paste, and the `openapi.yaml`/`openapi.json` inside a Nexus ZIP); the format is auto-detected.

---

## Configuration & re-branding

Everything brandable lives in [`src/main/resources/application.properties`](src/main/resources/application.properties).
Deploying for your company is just a matter of changing these — **no code changes**:

```properties
oas.branding.app-name=ACME OAS Validator
oas.branding.company=ACME Corp
oas.branding.tagline=Contract-conformance checks for our API gateway
oas.branding.primary-color=#0b7285
oas.branding.support-contact=api-platform@acme.com
```

The UI title, header, accent colour, footer and the downloaded report all read these values at
runtime. You can also override any of them with environment variables, e.g.
`OAS_BRANDING_APP_NAME="ACME OAS Validator"`.

Other useful settings:

```properties
server.port=8080
spring.servlet.multipart.max-file-size=25MB   # raise for very large specs
```

---

## Notes on TLS

Gateway targets are frequently on internal hosts with self-signed certificates, so the tool
**intentionally ignores TLS certificate validation** for the target base URL and for Nexus
downloads (see `InsecureHttp`). This is deliberate for a testing utility you point only at systems
you control — do not reuse that HTTP client in production code paths.

---

## Project layout

```
src/main/java/com/oastest/automation
├── OasAutomationApplication.java      # entry point
├── config/BrandingProperties.java     # white-label placeholders
├── controller/                        # REST endpoints (spec, testcases, execute, branding)
├── model/                             # DTOs
└── service/
    ├── SpecParserService.java         # OpenAPI parsing + endpoint listing
    ├── NexusSpecFetcher.java          # download + unzip + locate spec
    ├── SchemaSampler.java             # valid sample values from a schema
    ├── TestCaseGeneratorService.java  # one-mutation-per-case negative generation
    └── TestExecutionService.java      # fires cases at the target (cert-ignore)
src/main/resources/static              # embedded UI (HTML/CSS/JS wizard)
samples/petstore.yaml                  # demo spec
```

## API (for scripting / CI)

| Method & path | Purpose |
|---|---|
| `GET /api/branding` | branding values for the UI |
| `POST /api/spec/parse` (multipart) | parse a spec, return endpoints + `sessionId` |
| `POST /api/testcases/generate` (JSON) | generate cases for selected endpoint keys |
| `POST /api/execute` (JSON) | run cases against a target, return results |

---

## Tests
```bash
mvn test
```
Unit tests cover spec parsing and the negative-generation rules using the bundled sample spec.
