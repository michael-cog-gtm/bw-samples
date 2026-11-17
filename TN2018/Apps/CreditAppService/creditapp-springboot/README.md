# Credit check · local browser demo

A Java 21 / Spring Boot 3.5 replacement for the credit-check example, with an
offline-friendly browser page and controllable local bureau stand-ins. This is
an integration demonstration, **not a live credit check or a credit decision**.
Use sample data only; these unauthenticated, loopback-only services expose SSNs,
database records, and request traces. Do not expose them publicly.

## Run it

From this directory, with Maven, curl, Bash, and Java 21 installed:

```sh
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 mvn -B verify
./run-local.sh
# Open http://localhost:8080/
./stop-local.sh
```

`run-local.sh` builds the jar and launches **two JVMs**: the credit app and a
stand-ins process using `--spring.profiles.active=standins`. It waits for the app
and admin API to be ready. `stop-local.sh` stops both. Logs and PIDs are in
`.run/` (ignored by git). The script defaults to the installed Java 21 path above;
set `CREDIT_JAVA_HOME` to use a different Java 21 installation. A fresh build needs
Maven dependencies; after that there are no external services, fonts, scripts,
or CDNs. If Maven Central returns 429, a user-level Maven mirror can point at
`https://maven-central.storage-download.googleapis.com/maven2`.

| Local listener | Purpose |
| --- | --- |
| `localhost:8080` | Browser page, `POST /creditdetails`, trace and demo proxy APIs |
| `localhost:13080` | Equifax stand-in, `POST /creditscore` |
| `localhost:7080` | Experian stand-in, `POST /creditscore` |
| `localhost:13081` | Stand-ins admin API; browser accesses it **through 8080** |

In the page:

1. Click any sample customer. The two score cards show the outcome; expand the
   raw response to inspect its JSON. The HTTP status is always shown.
2. Under **Behind the scenes**, see the exact requests, statuses, timings, and
   errors for that check, plus before/after table snapshots. Changed values are
   highlighted and show both numbers, not just a color. Experian does not write
   to the shared table; Equifax updates inquiries. The two bureau responses may
   read different counts depending on which read happens first.
3. Set Equifax to **Down**, then click a customer: the check fails with a red
   connection-refused message. **Slow** delays a response beyond the one-second
   deadline and shows a timeout. The same controls work for Experian.
4. Try **Unknown customer** or **Synthetic EmptyRating** for the Equifax 404
   path. The four original customers are preserved verbatim, with one clearly
   labeled synthetic record added. All manual identity fields are optional.
5. **Reset demo data** restores the five records and both connections. **Refresh**
   reloads the current customer list and connection states without resetting.

Controls are disabled while a page operation is in flight. Snapshots are not an
atomic transaction and can include other users' activity. **A timed-out bureau
call may still update the table later**; the after-snapshot is taken when the
credit app responds, not when every remote operation has stopped. Reset before
a repeatable demonstration. This is a single-presenter demo, not a concurrency
or availability dashboard; use Refresh after changes made in another tab.

## What the original integration does

The systems represented by the checked-in BW sources are:

```text
Caller → CreditAppService /creditdetails
           ├─ Equifax subprocess → CreditCheckBackendService /creditscore
           │                        → PostgreSQL public.creditscore (read + update)
           └─ Experian subprocess → ExperianDemoService /creditscore
                                    → PostgreSQL public.creditscore (read only)

LoggingService: reusable logging subprocess, not called by this credit flow.
```

Both DB resources name a `bookstore` database and `creditscore` table. The diagram
expresses the intended shared-table demo; **the original configured hosts differ**
and do not establish a shared live database. Equifax has conflicting literal,
default, and Docker URL settings, while Experian specifies localhost. See the
[Equifax JDBC resource](../../CreditCheckBackendService/CreditCheckService/Resources/creditcheckservice/JDBCConnectionResource.jdbcResource)
(lines 3–7), its [default substitutions](../../CreditCheckBackendService/CreditCheckService/META-INF/default.substvar)
(101–102), its [Docker substitutions](../../CreditCheckBackendService/CreditCheckService.application/META-INF/docker.substvar)
(5–6, 109–110), and the [Experian JDBC resource](../../ExperianDemoService/ExperianService.module/Resources/experianservice/module/JDBCConnectionResource.jdbcResource)
(3–5). The replacement deliberately uses one shared H2 database.

### Preserved business rules, with source citations

Paths below link to unchanged BW source; line ranges are approximate.

| Rule | Original source |
| --- | --- |
| `POST /creditdetails`; optional `DOB`, `FirstName`, `LastName`, `SSN` | [module binding](../CreditApp.module/META-INF/module.bwm) 3–14; [request/response schema](../CreditApp.module/Schemas/getcreditstorebackend_0_1_mock_app.xsd) 3–28 |
| Equifax and Experian run as parallel branches, then combine their results, without averaging or TransUnion | [MainProcess.bwp](../CreditApp.module/Processes/creditapp/module/MainProcess.bwp) 755–835 (reply mapping 779–783) |
| Equifax receives uppercase identity keys; Experian receives `dob`, `firstName`, `lastName`, `ssn` plus the SSN HTTP parameter | [EquifaxScore.bwp](../CreditApp.module/Processes/creditapp/module/EquifaxScore.bwp) 542–568, 644–661; [ExperianScore.bwp](../CreditApp.module/Processes/creditapp/module/ExperianScore.bwp) 1053–1071, 1093–1108, 1126–1141 |
| Both replies become `{FICOScore, NoOfInquiries, Rating}` under `EquifaxResponse` / `ExperianResponse`; absent output fields are omitted, with zero and empty strings retained | Same subprocess mappings and [MainProcess.bwp](../CreditApp.module/Processes/creditapp/module/MainProcess.bwp) 779–783; mappings use node-existence `xsl:if` |
| Lookup is by SSN `LIKE`, not an exact identity match; names and DOB do not validate identity | [backend Process.bwp](../../CreditCheckBackendService/CreditCheckService/Processes/creditcheckservice/Process.bwp) 1182; [LookupDatabase.bwp](../../CreditCheckBackendService/CreditCheckService/Processes/creditcheckservice/LookupDatabase.bwp) 894, 908 |
| Equifax reads the first row, returns its **pre-update** inquiries, and sets **all** LIKE-matching rows to that row's count + 1 | [LookupDatabase.bwp](../../CreditCheckBackendService/CreditCheckService/Processes/creditcheckservice/LookupDatabase.bwp) 850, 894–912, 959, 971–979 |
| No row or empty rating throws; backend catches and configures 404 without a mapped body. Other lookup/update errors can also reach 404 | [LookupDatabase.bwp](../../CreditCheckBackendService/CreditCheckService/Processes/creditcheckservice/LookupDatabase.bwp) 878–890, 987–995; [backend Process.bwp](../../CreditCheckBackendService/CreditCheckService/Processes/creditcheckservice/Process.bwp) 1042–1105 |
| Experian reads only and maps the first row; no row means no output fields | [Experian Process.bwp](../../ExperianDemoService/ExperianService.module/Processes/experianservice/module/Process.bwp) 1155–1365, especially 1236–1259, 1307–1322 |
| Backend logs `Invoation Successful` / `Invocation Failed` (original spelling); generic LoggingService is unused by the traced credit processes | [backend Process.bwp](../../CreditCheckBackendService/CreditCheckService/Processes/creditcheckservice/Process.bwp) 1061, 1075–1078, 1108–1111, 1136–1159; [LogProcess.bwp](../../LoggingService/Processes/loggingservice/LogProcess.bwp) 1087–1289; the process graphs cited above contain no call to it |
| Shared table shape and the four sample rows | [create.sql](../../Database/create.sql) 5–18; [DBDump.csv](../../Database/DBDump.csv) 1–4 |

The SQL constants preserve the original text (including source whitespace in
Java, checked by tests). In readable form:

```sql
-- Equifax
select * from public.creditscore where ssn like ?
UPDATE creditscore
  SET numofpulls = ?
  WHERE ssn like ?
-- Experian
SELECT *
  FROM public.creditscore where ssn like ?
```

There is no `ORDER BY`, key, or uniqueness constraint. “First” is not a guaranteed
customer order for wildcard matches. The original query activities cap rows at
100; the stand-in reads all matches, which is equivalent for these five fixtures
but not claimed equivalent on large datasets. It does not reproduce the original
JDBC ten-second activity timeout or PostgreSQL configuration.

## Replacement decisions

See [DESIGN.md](DESIGN.md) for the complete implementation decisions and legacy
test details. Important distinctions from verified source facts:

- **HTTP 502 is deliberate**, not a claim about BW runtime behavior. Main has
  no explicit catch. Either bureau failure fails the combined request; no partial
  score response is returned. Body example:
  `{"error":"bureau request failed","failures":[{"bureau":"Equifax","reason":"connection refused"}]}`.
  Reasons distinguish connection refusal, timeout, Equifax not-found, other
  status, and invalid response/transport errors. A backend 404 is presented as
  customer-not-found for this demo, though the BW catch also covers other faults.
- **1000 ms per call is an assumption** interpreting `cmdExecutionIsolationTimeout="1000"`
  in [HttpClientResource1](../CreditApp.module/Resources/creditapp/module/HttpClientResource1.httpClientResource)
  and [HttpClientResource2](../CreditApp.module/Resources/creditapp/module/HttpClientResource2.httpClientResource),
  lines 3–6. The Java implementation does not reproduce the BW circuit breaker,
  command isolation, request logging, XML schema validation, or all HTTP defaults.
- **Local stand-ins and H2 replace bureau services and PostgreSQL.** These are
  samples, not access to real Equifax or Experian. H2 runs in PostgreSQL mode;
  PostgreSQL-specific OIDS/tablespace/collation clauses are omitted. Seed data is
  copied, not edited in place. Experian's no-row `200 {}` is an explicit, testable
  interpretation of optional response fields, not observed BW serialization.
- **DOWN really closes the bureau listener.** The separate admin server remains
  running. SLOW defaults to 1600 ms. The UI proxy has a separate five-second admin
  deadline. Control transitions and reset are for local presentation, not HA.
- **Traces contain demo identity data.** `POST /creditdetails` keeps its original
  success JSON clean and adds an `X-Credit-Trace-Id` header. The page fetches exactly
  that request's trace; it does not race against the global last-request endpoint.
  The newest 100 completed traces are held in memory, including failure traces;
  older/missing IDs return 404. Restart discards them. Reset reseeds the DB but
  does not clear the trace history. There is no durable audit log or auth.

### Configuration and JSON endpoints

| Property / environment | Default |
| --- | --- |
| `credit.equifax-base-url` / `EQUIFAX_BASE_URL` | `http://${BWAppHostname:localhost}:13080` |
| `credit.experian-base-url` / `EXPERIAN_BASE_URL` | `http://${ExperianAppHostname:localhost}:7080` |
| `credit.standin-admin-base-url` / `STANDIN_ADMIN_BASE_URL` | `http://localhost:13081` |
| `credit.timeout-ms` | `1000` |

For example, pass `--credit.equifax-base-url=http://localhost:14080` to the credit
jar; standalone JVMs also accept Spring configuration environment variables.
The BW Docker profile uses `host.docker.internal` for outbound bureau hosts
([Docker substitutions](../CreditApp/META-INF/docker.substvar), 85–86, 93–94).
Only trusted local operators should set the server-side URLs. Proxy routes have
fixed destinations and typed bureau/mode values; browsers cannot supply a URL.

Credit app endpoints (all browser requests stay on port 8080):

| Method / path | Purpose |
| --- | --- |
| `POST /creditdetails` | Optional identity object → combined result or 502 failures |
| `GET /api/traces/{id}` | Correlated trace: URL, payload, HTTP status (null if no response), latency and error per bureau |
| `GET /api/last-trace` | Last completed request, initially empty; not used to correlate UI requests |
| `GET /api/demo/rows` | Proxy to stand-in `/admin/rows` |
| `GET /api/demo/bureaus` | Proxy to `/admin/bureaus` |
| `POST /api/demo/bureaus/{EQUIFAX\|EXPERIAN}` | `{ "mode": "ONLINE\|DOWN\|SLOW" }`; optional `delayMs` 0–60000 |
| `POST /api/demo/reset` | Proxy to `/admin/reset`, restores records/connections |

The UI uses a 1600 ms delay for Slow. A caller can override `delayMs` for latency
experiments; if it is below the client deadline, SLOW need not time out.
Admin transport failures produce a friendly 502, separate from bureau failures.
These APIs have no authentication or CSRF protection and are **local-demo only**.

Example (after `run-local.sh`):

```sh
curl -i localhost:8080/creditdetails -H 'Content-Type: application/json' \
  -d '{"FirstName":"Aditya","LastName":"Wagle","DOB":"01/01/2000","SSN":"123-45-6789"}'
curl localhost:8080/api/demo/bureaus/EQUIFAX -H 'Content-Type: application/json' -d '{"mode":"DOWN"}'
curl localhost:8080/api/demo/reset -H 'Content-Type: application/json' -d '{}'
```

## How it is verified

Run `JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 mvn -B verify` from this directory
in a **full checkout**. Tests need the original sibling BW sources, not an exported
project-only copy. They start local servers/H2, use ephemeral ports, and require
no running demo JVM or external bureau/DB. Java 21 CI runs the same command on
pushes and PRs changing this directory (or its workflow).

| Test class | Rules exercised |
| --- | --- |
| `XsltParityTest` | Extracts original XML-escaped XSLT from the `.bwp` files at test time, executes with Saxon-HE, compares Java request/reply/Main/Lookup End/update mappings; present/absent/zero/empty cases and verbatim SQL |
| `BehaviorTest` | Original seeds; LIKE and all-matching-row updates; pre-update inquiries; missing/empty-rating 404; Experian read-only and `{}`; clean combined contract/no TransUnion; parallel 600 ms calls; real DOWN TCP refusal, SLOW deadlines, both failures, not-found 502, reset; malformed input; all three legacy inputs; static page/assets; demo proxy controls/validation, snapshots, correlated success/failure traces and missing trace |
| `ClientFailureTest` | Other HTTP status (503), malformed/non-object JSON, empty object success/omission, request headers, encoded Experian SSN query |
| `DemoAdminClientTest` | Proxy empty rows, JSON error/status forwarding, malformed/scalar response rejection and unreachable admin handling |

The Java static-asset test is not a browser interaction test. Manual release
checks should run the packaged JVMs, curl the proxied success/DOWN/SLOW/reset
flows, and exercise customer selection, controls, snapshots, and readable errors
in Chrome. No external browser dependency is bundled into Maven.

## Not verified

This replacement is source-traced and locally tested. **The TIBCO BW runtime is
not available here.** In particular, do not interpret tests as verification of:

- The actual BW MainProcess error HTTP status/body, propagation of backend 404,
  failures in catch/log/reply handlers, or framework-generated transport faults.
- Effective BW listener ports and default response statuses where unset. The
  CreditApp connector lacks an explicit port despite an 8080 descriptor/cloud
  property; the backend descriptor advertises 8080 while its connector/property
  use 13080. Experian's receiver has no explicit method restriction/default status.
  Resources and overrides are evidence of configuration, not a live deployment.
- BW empty JSON serialization, null/missing/XML-schema coercion and validation
  edge cases, non-ASCII behavior, timeout units/defaults, circuit-breaker behavior,
  and interruption/cancellation effects.
- Actual deployment profile substitution, DNS/network state, configured database
  hosts/contents/imports, or any real bureau, PostgreSQL, credentials, availability,
  performance, and transaction/isolation/rollback behavior. No real bureau API was
  contacted; H2 compatibility mode does not prove PostgreSQL equivalence.
- Atomic or race-free inquiry increments. Source uses separate select/update
  operations with no modeled locking improvement; neither BW nor this demo has
  been proven free of lost updates under concurrency. Parallel bureau reads can
  see different counts. LIKE result ordering is unspecified, and wildcard input
  can update multiple records. UI snapshots are not a transactional audit trail.
- Reproducibility of the three legacy `.bwt` assertions. Both FICO tests target
  the absent `creditapp.module.FICOScore` process (the current file is EquifaxScore).
  The “700” case actually asserts 800 with empty identity fields; it has no seed
  match. The “800” case requests `123-45-6789`, which is **900 / 104** in the dump.
  The Experian case requests missing `123-45-9389`, so this stand-in returns `{}`,
  not 800/2/Good. Generated rating assertions quote XPath as a string literal,
  disagreeing with OriginalExpression metadata. See the [original tests](../CreditApp.module/Tests)
  and [DESIGN.md](DESIGN.md#legacy-bwt-cases). Replacement tests encode their real
  inputs and honest local outcomes; they do not claim the BW assertions passed.
- Production security, privacy, persistence, multi-user consistency, resilience,
  or operational readiness. The original four fixture SSNs are displayed because
  they are sample data; do not use real customer data in this demo.

No existing TN2018 source is changed. The BW mappings, schemas, and fixture files
remain available as the reviewable oracle rather than being rewritten to fit tests.
