# Replacement decisions (not claims about the BW runtime)

- Java 21 / Spring Boot 3.5.7. Default JVM serves POST `/creditdetails` on 8080.
  A separate JVM using the same jar with `--spring.profiles.active=standins`
  serves admin on 13081 and two embedded Tomcat bureau connectors on 13080/7080.
  All listeners bind loopback. These unauthenticated endpoints are local-demo only.
  `run-local.sh` uses the installed Java 21 by default (`CREDIT_JAVA_HOME` can override).
- `credit.equifax-base-url` / `EQUIFAX_BASE_URL` default to
  `http://${BWAppHostname:localhost}:13080`; `credit.experian-base-url` /
  `EXPERIAN_BASE_URL` default to `http://${ExperianAppHostname:localhost}:7080`.
  Java property overrides are also accepted (`--credit.equifax-base-url=...`).
- Calls are launched concurrently. `credit.timeout-ms=1000` is a deliberate
  interpretation of BW's `cmdExecutionIsolationTimeout="1000"` as a millisecond
  per-call deadline. The original units/effective runtime behavior were not tested.
- Either failed call means HTTP 502, never a partial success. JSON is
  `{"error":"bureau request failed","failures":[{"bureau":"Equifax","reason":"..."}]}`.
  Reasons include `connection refused`, `timeout`, `customer not found at Equifax`,
  `bureau returned N`, or invalid response/transport error. Both errors are returned
  when both fail. MainProcess has no catch; its exact failure HTTP status/body is
  unverified. **502 and this body are deliberate, reviewable replacement choices.**
- Missing/null JSON fields are treated as absent nodes. Empty strings and zero
  values are retained. Experian's unguarded request `value-of` expressions produce
  empty strings for missing fields. Empty success responses still include the two
  bureau objects. There is no TransUnion response. No credit-rating calculation
  or identity validation beyond the original SSN LIKE lookup is introduced.
- GET `/api/last-trace` returns the last *completed* request's ID, start time, and
  both outbound URL/payload/status/latency/error records (initially no body).
  This intentionally includes demo identity data; it is in-memory, overwritten
  on completion, not a production audit store, and must not receive real PII.
- Admin endpoints (standins profile only): GET `/admin/bureaus`, GET `/admin/rows`,
  POST `/admin/reset`, POST `/admin/bureaus/EQUIFAX` or `/EXPERIAN` with
  `{"mode":"ONLINE|DOWN|SLOW"}`. SLOW defaults to 1600 ms, optional `delayMs`
  (0–60000) supports controlled latency experiments. DOWN stops the actual
  connector with bindOnInit=false, releasing its socket; admin remains reachable.
  Reset stops both connectors (draining outstanding work), reseeds, clears delays,
  and starts both ONLINE. No simulated HTTP-error substitute is used for DOWN.
- Both stand-ins share one embedded H2 database in PostgreSQL mode. Column names
  mirror the original; PostgreSQL-specific collation/OIDS/tablespace syntax is
  omitted for H2. `db/DBDump.csv` copies all four original rows verbatim.
  `db/synthetic-customers.csv` adds the explicitly synthetic `SYN-EMPTY` customer
  with an empty rating. Reset imports both. No original TN2018 file is edited.
- SQL constants retain the source SQL, including LIKE and all-row update behavior.
  Equifax returns the first row's pre-update count, then subsequent requests see
  the increment. Experian never updates. No transaction/locking improvement is
  added: concurrent calls against the same DB may see different inquiry counts,
  and concurrent select/update requests can lose increments as in the source design.
  SQL error/invalid count maps to Equifax's catch-all 404. Experian zero rows
  explicitly returns `{}` (a choice consistent with its optional output fields).
- Backend logs preserve `Invoation Successful` / `Invocation Failed`. The generic
  BW LoggingService is not invoked by the original credit flow and is not added
  as a new dependency. Original schemas and exact XSLT remain the test oracle.

## Legacy `.bwt` cases

Tests encode the actual inputs and seed outcomes, not misleading legacy labels.
Both FICOScore cases reference a missing/renamed process (`FICOScore`, current
source is `EquifaxScore`). The “700” test asserts 800 with empty inputs; no seed
row matches. The “800” input SSN `123-45-6789` actually has score 900 and initial
count 104. The Experian test uses absent SSN `123-45-9389`, so the Demo returns
`{}`, not 800/2/Good (the whole credit request fails Equifax lookup). Generated
rating tests compare quoted literal XPath strings to `Excellent` / `Good`, not
the actual field value; their OriginalExpression metadata differs. These original
assertions cannot truthfully pass against these sources/fixtures and are not
ported as fake successes. Tests read the `.bwt` XML to guard against input drift.
