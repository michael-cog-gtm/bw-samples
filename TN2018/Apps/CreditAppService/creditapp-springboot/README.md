# CreditApp: BusinessWorks → Spring Boot

Java 21 / Spring Boot 3.5.7. A credit-details service, a compact browser demo, and
**two independent synthetic local bureau stand-ins**. Never connects to real bureaus.
Original BW projects are read-only inputs to tests, not generated or modified.

## Run

```bash
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
export PATH="$JAVA_HOME/bin:$PATH"
mvn -B clean verify
./run-local.sh
# open http://localhost:8080 ; Swagger: /swagger-ui.html
./stop-local.sh
```

The script builds the jar and starts three JVMs bound to loopback. Logs and PID
files live in gitignored `.run/`, not `/tmp`. It refuses occupied ports or existing
live PID files and stops its processes on startup failure. Keep logs for diagnosis.
The stop script checks process identity before signaling a saved PID.

Service only: `java -jar target/creditapp-springboot-1.0.0.jar` (8080).
Stand-ins use a **different main class**, not a service endpoint mode:

```bash
java -Dloader.main=demo.credit.StandInApplication \
  -cp target/creditapp-springboot-1.0.0.jar \
  org.springframework.boot.loader.launch.PropertiesLauncher \
  --standin.bureau=equifax --server.port=13080
# Repeat with experian / 7080 in another process.
```

Config: `equifax.base-url` (http://localhost:13080), `experian.base-url`
(http://localhost:7080), `bureau.timeout-ms` (1000), `server.port` (8080).
Stand-in CORS origin: `standin.ui-origin` (http://localhost:8080).
Use Spring command-line `--property=value` or corresponding environment variables.
Do not expose the unauthenticated synthetic lab publicly. Both stand-ins provide
GET `/standin/records`, POST `/standin/reset`, and header
`X-Stand-In: synthetic-local` on all responses (including errors).

If Maven Central is rate-limited, use a local `~/.m2/settings.xml` mirror for
`central` pointing to `https://maven-central.storage-download.googleapis.com/maven2`.
No machine-specific mirror configuration is committed.

## Original behavior and mapping

Paths below are relative to `TN2018/Apps/`; **CA** means
`CreditAppService/CreditApp.module`, **CB** means
`CreditCheckBackendService/CreditCheckService`, **EX** means
`ExperianDemoService/ExperianService.module`.
CreditApp process files live in `CA/Processes/creditapp/module/`.

| Original | Spring replacement / source evidence |
|---|---|
| MainProcess receives POST JSON `/creditdetails` | `CreditController.details`; CA/META-INF/module.bwm:3–9; CA/Service Descriptors/creditapp.module.MainProcess-CreditDetails.json:8–34 advertises 8080 |
| MainProcess calls both subprocesses, waits on both SUCCESS links | two async HTTP futures, joined before reply; MainProcess.bwp:774–787,789–826 |
| MainProcess call inputs | `Mappings.equifaxInput` for optional uppercase fields; MainProcess.bwp:791–803,811–823 |
| EquifaxScore REST `post` | HttpClientResource2 / POST `/creditscore`; EquifaxScore.bwp:539–569,644–653; CA/Resources/creditapp/module/HttpClientResource2.httpClientResource:4–5 sets port 13080 and BWAppHostname |
| EquifaxScore End | `Mappings.score(...,false)` conditional FICOScore, NoOfInquiries, Rating; EquifaxScore.bwp:618–626 |
| ExperianScore RenderJSON | `Mappings.experianInput`, unconditional dob/firstName/lastName/ssn, missing → empty string; ExperianScore.bwp:1093–1094; CA/Schemas/ExperianRequestSchema.xsd:6–9 |
| ExperianScore SendHTTPRequest | POST `/creditscore`, JSON headers, optional ssn query, body from renderer; ExperianScore.bwp:1053–1054; resource binding :956–962; HttpClientResource1.httpClientResource:4–5 sets 7080 / ExperianAppHostname |
| ExperianScore ParseJSON + End | Jackson object parsing, `Mappings.score(...,true)` fiCOScore/rating/noOfInquiries → uppercase output; ExperianScore.bwp:1126–1145,1021 |
| MainProcess postOut | `Mappings.aggregate`, unconditional EquifaxResponse / ExperianResponse, conditional inner fields; MainProcess.bwp:780–783. TransUnionResponse exists in descriptor :80–82 but has no mapping and is not emitted |

Input strings are optional: null/omitted fields are omitted from Equifax, while
empty strings are retained. Experian always receives all four string keys. Unknown
input keys are ignored. Malformed client JSON produces 400; no additional customer
validation is imposed. Both calls have independently configurable 1000 ms deadlines
(CA/Resources/creditapp/module/HttpClientResource{1,2}.httpClientResource:3).

### Backend contracts and the buried side effect

Equifax stand-in mirrors CB/Processes/creditcheckservice/Process.bwp:1183–1186:
only request **SSN** is passed to LookupDatabase. That subprocess queries
`select * from public.creditscore where ssn like ?` (:895–912), uses first record,
requires nonempty rating (:878–891), updates `numofpulls` to original + 1
(:960–979), then returns the **pre-update** score/rating/count (:847–851).
Columns from Database/create.sql:7–13 map `ficoscore → FICOScore`,
`rating → Rating`, `numofpulls → NoOfInquiries`. Unknown/empty-rating records throw
(:987–995); outer catchAll logs Invocation Failed and returns 404
(Process.bwp:1043–1105). Success descriptor status is 200.

The older CB/Service Descriptors/GetCreditStoreBackend_0.1.json:37–45 describes
optional boolean `skipvalidation` (“Skip matching Inputs…”). It is not in the
active descriptor or implemented process; it is not implemented here either.
The active binding is CB/META-INF/module.bwm:5–11.

Experian uses body `ssn`, not query ssn: EX/Processes/experianservice/module/Process.bwp:1243–1259.
SQL is `SELECT * FROM public.creditscore where ssn like ?`. First record's
`ficoscore → fiCOScore`, `rating → rating`, `numofpulls → noOfInquiries`
(:1341–1342). No update; absent record maps empty fields, rendered as `{}`.
SendHTTPResponse maps JSON content and Content-Type, no explicit status (:1208–1225);
descriptor declares 200. No explicit not-found or error handler is present.

### Synthetic seed (fresh/reset values: score / inquiries / rating)

| Customer | Never-issued SSN | Equifax | Experian |
|---|---|---|---|
| Synthetic Aurora | 900-11-0001 | 810 / 1 / Excellent | 785 / 2 / Good |
| Synthetic Beacon | 900-11-0002 | 690 / 3 / Good | 650 / 4 / Fair |
| Synthetic Comet | 900-11-0003 | 550 / 6 / Poor | 580 / 5 / Fair |
| Synthetic Delta | 900-11-0004 | 730 / 0 / Good | `{}` |
| Synthetic Echo | 900-11-0005 | 404 → aggregate 502 | 720 / 1 / Good |

One CSV (`src/main/resources/customers.csv`), each process loads its **own** copy
and bureau-specific columns. No original DBDump customer data is used.
Reset affects one stand-in only; UI reset invokes both. Counts are synchronized
in-memory and restart/reset loses changes. For Aurora, consecutive aggregate calls
return Equifax counts 1 then 2; Experian remains 2. An Equifax call may mutate its
store even if the other provider fails; no distributed rollback is promised.

## Replacement decisions and explicit gaps

* **Failure semantics:** original CreditApp processes have no catch/fault handler
  (executable flows EquifaxScore.bwp:592–671, ExperianScore.bwp:990–1152,
  MainProcess.bwp:756–835). Exact BW error HTTP status/body is unknown. **New decision:**
  any non-2xx, timeout, connection failure, invalid/non-object JSON causes 502 JSON
  `{error,providers:{Equifax:reason,Experian:reason}}`, listing only failing providers,
  no partial result. Experian `{}` is a successful empty wrapper.
* **SendHTTPRequest non-2xx policy:** not proven by serialized source config
  (ExperianScore.bwp:1068–1073). This replacement explicitly treats non-2xx as failure.
* **Stylesheet precedence unknown:** source stores part-attribute and whole-message
  forms (EquifaxScore.bwp:647–653; MainProcess.bwp:779–783). Tests use attribute
  primary and separately execute available whole-message/activity expressions with
  appropriate message versus part parameter roots. No BW runtime/compiler was run.
* **No circuit breaker, bulkhead, or custom thread pool:** omitted source values in
  both HttpClientResource*.httpClientResource:3 are THREAD isolation; semaphore max
  8; breaker request threshold 20, sleep window 5000, error threshold 50%; rolling
  stats 10000/10 buckets, health snapshot 500; request logging true; thread pool
  core 10, queue -1, rejection threshold 5, keepalive 1. Timeout enabled 1000 and
  interrupt-on-timeout true in BW; Java uses request/future deadlines, not Hystrix.
  No socket/connect-timeout parity is asserted; late provider effects are possible.
* **JSON bytes:** compare semantic JSON, not renderer whitespace/order/escaping.
  No evidence of exact BW renderer byte format. No WSDL/XML wire endpoint supplied.
* **DB sharing:** both original SQL statements name public.creditscore; whether the
  deployed services shared one DB is an assumption. Demo deliberately separates stores.
* **LIKE vs exact:** source allows SQL wildcard matching / first of up to 100 rows;
  synthetic stand-ins deliberately match exact SSN, no SQL or wildcards. Production
  DB, update atomicity/transactions, ordering, persistence and multi-instance state
  are not reproduced. Equifax synthetic 404 body is a replacement, not BW byte parity.
* **Docker substvars:** CA/META-INF/default.substvar:85–94 uses localhost;
  CreditAppService/CreditApp/META-INF/docker.substvar:85–94 uses host.docker.internal.
  New service uses explicit configurable URLs, not BW substitution profiles.
* No real bureaus, BW runtime execution, or production-database integration tested.

## Verification

`mvn -B clean verify` runs:
* `XsltParityTest`: reads original sibling `.bwp` files **at test time**, compiles
  extracted XSLT with Saxon-HE, compares Java mapping semantics for full/missing/empty/
  null inputs, full/empty/partial responses, call inputs, REST parts and available
  whole-message variants, renderer, HTTP method/URI/headers/query, End and aggregate.
* `FrozenExpectationsTest`: loads source-traced `expectations.json`, preserving intended
  800/1/Excellent and 800/2/Good mappings, not broken compiled .bwt literal comparisons.
  Notes record stale FICOScore process name, 700-named assertion of 800, quoted rating
  XPath literals, and stale Experian input root (original Tests/*.bwt:3,7–9,15–23).
* `HttpFlowTest`: MockWebServer outbound contracts, optional query, 600 ms concurrent
  providers, 404, timeout, connection error, invalid JSON, empty success, client 400.
* `StandInTest`: pre-increment/reset, unknown/empty rating, no Experian increment,
  synthetic header and CORS.

| Evidence | Verified scope / limits |
|---|---|
| Original source + Saxon | Mapping semantics; not proof of BW runtime stylesheet precedence |
| MockWebServer + MockMvc | HTTP contracts, concurrency, failure policy; not real bureaus |
| Stand-in tests | Synthetic local state transitions; not original DB behavior under contention |
| run-local + curl | Packaged app startup and two-call inquiry side effect, failure response |
| Browser UI | Provided; browser-driven visual/end-to-end verification is separate and not claimed here |
| GitHub Actions | Path-filtered Java 21 verify configured; remote run must be checked after push |

Clone the whole repository for tests: relative original-source paths intentionally
fail if only this folder is copied. CI checks out all originals but never edits them.
