# Payvero — Interview Notes

## Domain one-liners (from day 0 briefing)
- A double-entry ledger records money movement as balanced debit/credit pairs; balances are derived, never stored as editable state.
- The killer property is a machine-checkable invariant: every transaction sums to zero, so corruption is detectable and every balance is provable from primary records.
- A `balance` column is lossy (no history) and unverifiable (no invariant) — that's why Stripe, Adyen, Uber, and every core banking platform run entry-based ledgers.
- My stack (Java, Spring Boot, Postgres, Kafka, Redis) is the industry-standard ledger stack — Adyen is the existence proof at global scale.
- TigerBeetle exists for million-TPS accounting workloads; most companies still choose Postgres because the ledger must share ACID transactions with adjacent data and their volume is far below Postgres's ceiling. I can articulate when I'd switch.
- The three money bugs my design defends against: lost updates (optimistic locking), double spends on retry (idempotency keys), and unauditable drift (immutable entries + Kafka audit trail).
- Honest scope limits vs. the real thing: no external reconciliation against bank files, single currency, no KYC/AML, no multi-region durability.

## Setup decisions (Day 0)
- Flyway owns the schema; Hibernate set to `validate` only — schema changes are versioned, reviewable migrations, like production teams do.
- Disabled open-in-view: avoids hidden lazy-loading queries during response rendering.
- Kafka runs in KRaft mode — ZooKeeper is gone from modern Kafka.
- pgvector image from day one so the RAG milestone needs no infra change.
- Spring Initializr no longer offers Spring Boot 3 — project runs on Boot 4.1.0. Verified this is a refinement, not a rewrite: Boot 4's minimum Java baseline is still 17, so our Java 21 choice is unaffected, main visible change is starter modularization (spring-boot-starter-web -> spring-boot-starter-webmvc, spring-kafka -> spring-boot-starter-kafka, flyway-core -> spring-boot-starter-flyway + flyway-database-postgresql). Jackson 3 is now default — worth watching for money/BigDecimal serialization behavior once the ledger API ships.
- Testcontainers pinned to match Compose exactly (pgvector/pgvector:pg16, redis:7-alpine, apache/kafka:3.8.0) instead of :latest, so test DB has the same extensions as the real one and tests stay reproducible.

## Gaps closed
- Confirmed Boot 4 doesn't change the Java 21 decision or any other locked stack choice.

## Gaps to close
- RAG assistant, Playwright E2E, a coverage gate, and AWS deployment config remain. Tracked in the README status list.

## Additional Day 0 decisions
- Pinned JAVA_HOME to Temurin 21 explicitly via shell profile, even though a newer JDK (25) was also installed — real teams pin JDK versions per project rather than relying on whatever the machine defaults to.
- Chose a monorepo (backend/ + frontend/ in one repo) over two separate repos — simpler to showcase as one coherent project, one README, one CI pipeline, at the cost of slightly coupled deploy pipelines later.
- CI's frontend job initially skipped `npm run lint` deliberately — a fresh Vite scaffold's default lint config isn't meaningful until real code exists to lint against. Added back in Phase 8 alongside `npm run test`, which had never been wired into CI at all: the job built the frontend without ever running its tests, so a hundred-plus passing tests were gating nothing.

## Day 0 continued — frontend styling stack
- Vite's react-ts scaffold defaulted to React 19.2.8, not React 18 as originally planned. Confirmed React 19 is stable and fully supported by every other planned library (TanStack Query, RHF, Zod, Router, shadcn/ui, Recharts) — no downgrade needed, locked stack updated to reflect reality.
- Adopted Tailwind CSS v4 (not v3) — the `@tailwindcss/vite` plugin replaces the old PostCSS-based setup, and `src/index.css` uses a single `@import "tailwindcss"` instead of the old three `@tailwind` directives.
- shadcn/ui requires a `@/*` path alias resolving to `src/`. On current TypeScript, `baseUrl` is deprecated as a hard error under `moduleResolution: "bundler"` — `paths` alone resolves correctly without it. shadcn's own docs still show the older baseUrl+paths pattern, which breaks on current TS — had to diagnose and fix this directly.
- shadcn is listed in `dependencies`, not `devDependencies` — correct despite looking unusual for a CLI tool, because `index.css` does a runtime `@import` of shadcn's base styles, making it a genuine runtime CSS dependency.

## Day 1 — Auth
- Refresh tokens are stored as hashes, never raw — a DB leak can't be replayed against the API.
- Rotation with reuse detection via `family_id`: every refresh token in one session lineage shares a family. Using a token revokes it and issues a successor in the same family; presenting an already-revoked token means it was stolen and replayed, so the entire family is revoked, not just that token.
- Injected `java.time.Clock` everywhere instead of calling `Instant.now()` statically — makes expiry and rotation tests deterministic (`Clock.fixed(...)`) instead of requiring sleeps. Entities hold no time logic at all; the service layer owns "is this expired?".
- JJWT 0.12.6 renamed its builder API from 0.11.x (`setSubject` -> `subject`, etc.). Also: `JwtParserBuilder.clock()` takes JJWT's own `io.jsonwebtoken.Clock` (a `Date now()` functional interface), NOT `java.time.Clock` — bridged with a lambda so parser expiry checks honor the injected clock.
- Boot 4 ships Jackson 3 (`tools.jackson.*`), but JJWT 0.12.6's `jjwt-jackson` module compiles against Jackson 2 (`com.fasterxml.jackson.*`). Used `jjwt-gson` instead to sidestep the conflict entirely — same JJWT API, different JSON backend.
- Debugging lesson: a mis-indented YAML block put `payvero.jwt.*` under `spring:`, so `@ConfigurationProperties(prefix="payvero.jwt")` matched nothing. Spring bound a record's components to null SILENTLY — no binding exception — and it surfaced as an unrelated NPE in the service constructor. Fixed by adding `@Validated` + `@NotBlank`/`@NotNull` to the properties record so misconfiguration fails fast at startup with the offending property named.
- Flyway migrations are normally immutable once applied, but V2 had only ever run against a local dev DB with no data and no other developers, so editing it and doing `docker compose down -v` was legitimate. That rule flips hard the moment this is deployed or shared — after that, corrections ship as a new migration.

## Phase 2 — Ledger core
- Balance is defined as credits minus debits over `ledger_entries`, derived on demand. `accounts.cached_balance` is an optimisation and never the source of truth, which is why the integrity check re-derives from entries in SQL rather than reusing any of the code that maintains the cache — a check that shares assumptions with the thing it checks cannot catch its bugs.
- Append-only is enforced by a Postgres trigger that rejects UPDATE and DELETE on `ledger_entries`, not by convention. Even a direct statement outside the application cannot restate history; a correction is a new opposing pair. TRUNCATE still works, which is how tests reset state, because it fires no row-level trigger.
- One system treasury account, seeded by the migration with a fixed id. Deposits debit it and credit the user, so money entering the platform is a real two-sided movement instead of a single-sided write. Its balance runs negative by design: that figure is what the platform owes its users, and the whole ledger nets to zero.
- `postBalancedPair` takes account ids, not entities. Passing an entity across a transaction boundary carries a stale `@Version` with it, so the write would either fail spuriously or commit a balance computed from a figure that had already moved. Loading inside the transaction means the version guarding the write is the one that was actually read.
- Every movement is capped by configuration. The cap bounds the damage of any single mistake and keeps running totals far inside a signed 64-bit integer; `Math.addExact` is the backstop, because a silent overflow would turn a large credit into a negative balance.
- `ddl-auto: validate` earned its place immediately: I wrote `CHAR(3)` for currency and Hibernate expects `varchar`, so the application refused to start rather than running against a schema it disagreed with.

## Phase 3 — Transfer lifecycle
- The write path is one ACID transaction in its own bean, with the retry loop outside it. Retrying inside would reuse a transaction already marked rollback-only, so every attempt after the first would fail for the wrong reason. REQUIRES_NEW guarantees each attempt re-reads current state instead of replaying against the state that just lost.
- Retries are bounded. Past a few attempts, contention is an honest conflict; an unbounded retry turns it into a livelock that looks like a hang.
- Idempotency claims the key *before* doing the work, then upgrades the row to a stored response. Writing the record afterwards leaves a window where two concurrent requests carrying one key both pass the lookup and both execute — the exact failure the key exists to prevent. The unique constraint decides the winner; the loser is told to retry rather than allowed to double spend.
- A key whose work failed is released, not kept. A claim abandoned after a failure would lock that key out permanently with nothing stored to replay.
- Recovering from the duplicate-key race has to happen in a different transaction. Postgres aborts the whole transaction when any statement in it fails, so reading back the winning row from the transaction that just lost is refused with "current transaction is aborted". That cost me a test failure before I understood why.
- Rate limiting is a sliding window log in Redis driven by a Lua script, so trim-count-admit is one atomic step. Separate round trips would let two concurrent requests both observe a count below the limit. A sliding window rather than fixed buckets, because fixed buckets let a caller spend a full allowance at the end of one window and again at the start of the next.
- A replay short-circuits before the rate limit is touched: retrying an answered request is not new work, and charging for it would punish a client for the network's fault.
- Settlement is genuinely asynchronous. Entries are written when the transfer is accepted, so money has already moved while the status is PENDING; what settles later is the lifecycle around the movement. The scheduler is a thin trigger over a service a test can call directly, so lifecycle tests need no sleeps.
- The treasury is exempt from the sufficient-funds check. It is the account money is issued from, so it is supposed to run below zero.
- Adding the `ledger_entries.transfer_id` foreign key turned one existing test into a false green: it inserted entries with a random transfer id to prove the `amount > 0` constraint, and the foreign key started rejecting those rows first, so the amount constraint was never reached. Fixed by attaching the probe to a real transfer.

## Phase 4 — Outbox and audit trail
- The outbox row is written in the same transaction as the transfer, by a recorder that deliberately carries no transaction annotation of its own so it joins its caller's. Publishing to Kafka from inside that transaction would be a dual write: the broker call can succeed and the transaction still roll back, leaving an event describing money that never moved. One commit covers both.
- Delivery is at-least-once, not exactly-once, and I would rather say so than imply otherwise. A row can reach Kafka and the process die before the row is marked published, and Kafka can redeliver after a rebalance. The honest design is an idempotent consumer, not a claim of exactly-once.
- Idempotency in the consumer is enforced by a unique constraint on `event_id` — the originating outbox row id, carried through the envelope. The `existsByEventId` check in front of it is only an optimisation to avoid a doomed insert; under concurrency several deliveries pass that check and the constraint is what actually holds the line. There is a test with eight threads on one event asserting exactly one row.
- A row is marked published only after the broker acknowledges. Marking first would convert a failed send into a silently dropped event, which is the single outcome the outbox exists to prevent. A failure records the error and leaves the row pending, so the next poll retries it forever.
- `audit_log` gets the same append-only trigger as `ledger_entries`, for the same reason: an audit trail that can be edited is not an audit trail. The trigger function is generic over the table name so later append-only tables reuse it. The outbox is deliberately not protected, because publishing has to mark rows.
- Events are keyed by aggregate id so everything about one transfer lands on one partition and is consumed in the order it was written.
- The "publisher outage" test is the one that proves the pattern: with the scheduler disabled the event sits durable and unpublished in Postgres, and a later poll delivers it end to end into the audit trail. Nothing is lost while the broker is unreachable; delivery is only delayed.

## Phase 5 — Fraud velocity flags
- Velocity is a sliding window log in Redis, not a counter bucketed by clock interval, and the difference is the whole rule. A key like `count:{user}:{minute}` resets on the minute, so five transfers at 10:59:59 and five more at 11:00:01 read as two quiet minutes while actually being ten transfers in two seconds. Trimming by relative age means the window always covers the last N milliseconds wherever the clock happens to be. There is a test that observes either side of a minute boundary and asserts both are counted; a bucketed implementation fails it.
- Trim, record and total run in one Lua script, so the answer describes a single consistent moment rather than three round trips that another request can interleave.
- Members are keyed `transferId|amount`, so a redelivered event re-scores the same member instead of adding a second one. Redelivery cannot inflate a count.
- Detection runs off a Kafka event after the money has moved, never inside the transfer's transaction. A check that had to pass before a payment could complete would make every payment as slow as the slowest rule, and an outage in fraud detection would stop payments entirely. Asynchronous means the worst case is a late flag, not a blocked customer.
- A flag is a marker for review, never a correction. Nothing in the fraud path writes a ledger entry or moves a balance; the transfer's status changes and a row is added. Reversing a confirmed fraud would be a new opposing transfer, which keeps history additive and leaves the original visible.
- Rules are evaluated independently rather than combined into a score, because many small transfers and a few large ones are different behaviours and a reviewer wants to know which one fired. One transfer can carry both flags.
- `UNIQUE (transfer_id, rule)` is what makes the detector idempotent under at-least-once delivery, and a CHECK constraint insists a resolved flag names its reviewer — a decision without an author is not a review trail.
- The threshold test asserts the boundary rather than the burst: exactly the configured maximum raises nothing, and the very next transfer is the one flagged. Firing fifty transfers at a limit of five would pass against an implementation whose boundary is off by one.
- Fraud runs under its own consumer group, so it reads the same stream as the audit trail independently and a slow or failing rule can never cost the audit trail a record.

## Hardening pass after the Phase 1-5 review
- Admin fraud decisions were the one privileged state change writing no audit event. In a system whose entire claim is auditability, a reviewer clearing their own flag left no trace — the most embarrassing possible gap. Fixed by routing the decision through the same `OutboxRecorder` the transfer path uses, in the same transaction as the state change, carrying who acted, what they decided, which flag and transfer, and when. Deliberately not a second audit mechanism: two paths to the audit trail would mean two things to keep correct.
- Reviewing that, the only other state-changing admin endpoints are the two review actions themselves; the integrity check and the flag queue are reads. Account opening and the auth lifecycle also emit no events — not admin actions, so out of scope for this pass, but worth revisiting if the trail is ever meant to reconstruct a full account history.
- The outbox topic router now sends transfer events to the transfer stream and everything else to the audit stream, rather than singling out one aggregate type. Defaulting to the audit topic means a new event type is recorded by default instead of landing where consumers filter it out.
- Unhandled exceptions were being masked as 401. Spring forwards them to `/error`, which was not in the permitAll matchers, so the forward was itself an unauthenticated request and a malformed JSON body came back as "authentication required". It failed closed, so it was never a hole, but it told every client the wrong thing. The handlers return a fixed message rather than the exception's own, because a parser's message can echo the payload back.
- `Transfer` had no `@Version` while `Account` did. Settlement writing SETTLED and fraud writing FLAGGED are different threads reacting to different triggers, so the core domain object's state machine was last-write-wins — the only uncontrolled concurrency left in the system. Added via a new migration rather than editing an applied one.
- Only the fraud path retries that transition, and the asymmetry is deliberate. Fraud raises the flag and the status change in one transaction, so losing the race leaves nothing behind and abandoning it would leave a suspicious transfer unmarked: retry is right. Settlement re-selects PENDING transfers every poll, so a transfer that lost to a flag is no longer a candidate and should stay that way — retrying there would re-settle something that had just been flagged.
- The application now connects as `payvero_app`, which can read and write rows but owns nothing. A table's owner can always run `ALTER TABLE ... DISABLE TRIGGER`, so while the app connected as a superuser the append-only guarantee held only until someone reached the application. Migrations run as the owner because DDL is the privilege the runtime must not have. Tests run as the owner and so do not exercise the split; it is verified directly against Postgres instead, which is honest about what the suite does and does not cover.
- Access token TTL cut from fifteen minutes to five. The accurate statement of the tradeoff is broader than "logout does not revoke access tokens": **no** server-side state change takes effect until the token expires — logout, user deletion, role change, account freeze. A validly signed token for a user who no longer exists still authenticates, because the filter builds authentication from claims and never reads the database. That is the point of stateless verification, and a Redis denylist would reintroduce the per-request round trip it exists to avoid. Shortening the window is the mitigation; naming the full set of affected operations is the honest version of the answer.
- Corrected the deposit scope wording. Calling it "capped" was wrong: the ceiling is per movement, not cumulative, so with the rate limit it works out to roughly $60M a minute, indefinitely. The accurate framing is that any authenticated user can mint unlimited funds because there is no external funding source, and the README now says exactly that.

## Phase 6 — Statements
- Statements are derived from `ledger_entries` and from nothing else. Nothing in the statement path reads `cached_balance`, because the cache is maintained by application code: a bug there would produce a statement that agrees perfectly with the cache while both disagree with the entries, and it would look internally consistent while being wrong. A statement's whole value is being provable from primary records, which it is not if it was computed from a derived one. The test corrupts `cached_balance` to an obvious wrong number, confirms the integrity check notices, then generates the statement and asserts the entry-derived figures.
- Continuity is automatic rather than arithmetic that has to be kept in step. One query gives the balance as of an instant; a period's opening balance is the balance before its start and its closing balance is the balance before the next period's start. Those are the same instant, so closing of one period and opening of the next are the same expression evaluated twice, not two calculations that happen to agree. Tested across three consecutive months with movement in both directions in each, plus a quiet month that has to carry the balance forward unchanged.
- Statements are immutable at the database, with the same trigger `ledger_entries` and `audit_log` use. A statement that can be silently regenerated with different numbers is not a record of anything; the correction for a wrong statement is a corrected ledger and a new period, never an edit.
- Regeneration is therefore a read, not a recompute. Asking again returns the stored figures unchanged, so a late entry landing inside an already-issued period cannot retroactively change the document — proven by a test that adds an entry after issuing and asserts the statement is byte-identical while the ledger balance has moved.
- Generation is refused for a period that has not ended. This one is a judgement call: an immutable statement for a month still in progress would permanently record figures that later entries in that same month could never correct, so refusing an open period is what keeps immutability honest rather than lossy. A current-month view is an activity list, which is a different thing from a statement.
- A cheap self-check runs before anything is stored: walking the period's lines from the opening balance has to land exactly on the closing balance. Both numbers come from the same entries so it should be unreachable, but if the two ever disagreed the statement is refused rather than stored, because an internally inconsistent statement is worse than a missing one.

## Phase 7 — Polish, seeding, and observability
- Seeding runs as a Spring profile with an `ApplicationRunner`, not a Flyway migration and not a default-on component. A migration is the wrong tool twice over: migrations are schema, they run automatically in tests and in production, and demo data in one would have to be raw SQL against `ledger_entries`, bypassing every invariant the system exists to enforce. A profile is opt-in, so it cannot fire where it is not wanted, and it can call the real services.
- Seeded money goes through `TransferWriter` — the same code the API uses — so the balance check, the balanced pair, the version-guarded cache update and the outbox event all happen exactly as they do for a real transfer. Nothing inserts a ledger entry. The seeder ends by running the integrity check and refuses to finish starting if the books do not balance, because a demo built on a corrupt ledger is worse than no demo.
- Backdating meant making time explicit. Entities took `created_at` from `@PrePersist Instant.now()`, so history could only be written by going around the services. Now the constructors take the instant and the service layer supplies it from the injected clock. That is also more consistent with the convention this project already had: services own time, entities record it.
- Re-running the seeder is a no-op, guarded by its own marker user. Anything else would double-credit accounts every time someone restarted the demo.
### Event time versus processing time — the bug seeding uncovered

Seeding three months of history produced **74 fraud flags** where I expected three. That turned out to be a genuine defect, not an artefact of how the demo was built, and it is the most useful thing I learned in this phase.

Two mistakes stacked:

1. **The fraud detector measured velocity against wall-clock `now`** — the moment the event was *consumed* — rather than the moment the transfer actually happened.
2. **`EventEnvelope.occurredAt` was carrying the outbox row's `created_at`**, which records when the event was *written to the outbox*, not when the underlying fact occurred. So even the field named "occurred at" was reporting processing time.

Replay the last three months of transfers in a few seconds and every one of them lands inside the same one-minute velocity window, so the rule concludes that a user who made sixteen transfers over a month made them all at once.

**Why this is a production incident and not a seeding quirk.** The outbox pattern deliberately survives a broker outage: events accumulate durably in Postgres and are published when Kafka returns. When it does return, the publisher drains hours of backlog in seconds. Every one of those events would have arrived carrying a timestamp of *now*. The fraud system would have flagged the entire user base as velocity attackers simultaneously, the review queue would have filled with thousands of false positives, and it would have happened at precisely the worst moment — during recovery from an unrelated outage, when attention is already elsewhere. Nothing in the system would have looked broken: every write committed, every request succeeded, and the flags would have been "correct" given the timestamps they were handed.

**The distinction is event time versus processing time**, which is the central problem in stream processing and the reason systems like Flink and Beam are built around it. Event time is when the thing happened in the world; processing time is when a consumer got around to looking at it. They coincide exactly when everything is healthy, and diverge precisely when it is not: outages, backlogs, redeliveries, replays, and catch-up after a deploy. Any consumer that reasons about *time* — velocity, rate limiting, windowed aggregation, expiry — has to use event time, or it is really measuring how busy the consumer was.

The fix: `occurred_at` is now its own column on the outbox, distinct from `created_at`, and it travels in the envelope so a consumer can reason about domain time without understanding the payload. `created_at` still drives publication ordering, which is genuinely a processing-time concern. Both timestamps exist because they answer different questions, and collapsing them into one was the underlying error.

Worth saying plainly: I found this because seeding forced a replay. It would not have shown up in any test that wrote data at the current time, and it would not have shown up in production until the first backlog drain.
- Swagger's paths are open without a token because they serve the API description, not data: no endpoint they document becomes callable without one. Turning documentation off for an environment is a springdoc flag rather than a security-config change, so the exposure decision and the security rules cannot drift apart.
- `/actuator/metrics` is now ADMIN. Metrics name internal behaviour and volumes, which is operator data rather than something a signed-in customer should read.
- The custom metrics are deliberately about things that are invisible from outside — a retry that succeeded, a replay that cost nothing, a rejection that never became an error. Request counts and latencies already come free from the web layer, so repeating them here would be noise. Tagged counters are registered eagerly for every known status and rule, because a counter that has never fired simply does not exist in the registry, and that is exactly when you want to see a zero.
- Outbox lag gets its own health indicator, and it reports DOWN rather than a warning. A backlog is the failure mode that is invisible everywhere else: every write still commits, every request still succeeds, and events quietly stop reaching Kafka while the audit trail and fraud detection run on stale data. A degraded state nobody pages on is the same as no signal at all.

## Phase 8 prep — designing the API from the consumer's side
- Planning the frontend before writing it surfaced six problems in an API that had passed every backend test, because backend tests ask "is this correct" and a client asks "can I render this". Both matter and they are not the same question.
- `Page<T>` was being serialised straight from `PageImpl`, which Spring warns about on every startup: there is no guarantee of a stable JSON structure. It also leaked `pageable`, `sort` and `unpaged` internals no client wants. Switched to `VIA_DTO`, which emits `content` plus a small `page` object and is a shape the framework actually commits to. Building a frontend against a structure the framework disclaims would have been a silent break on a future upgrade.
- Statement line items and fraud detail were JSON stored as text, and the API handed that text straight out. That is a persistence decision leaking into the contract: it obliged every client to parse a string and hand-type the result, which is where untyped frontend bugs live. Both now deserialize into real records.
- Transfers carried two account uuids and nothing else, so a client could not say whether money went out or came in without holding every account id it owned, and could not name the other party at all. `direction` and `counterpartyLabel` are now computed server-side relative to the caller, because the server already knows who is asking. One projection query fetches both parties, so naming the counterparty costs no N+1.
- Showing a counterparty's email to the other party is a deliberate disclosure, not an oversight: someone who sent or received money is party to it and needs to know who with. It is only ever the counterparty of a transfer the caller is on, and no endpoint exposes a user directory.
- Added `/api/auth/me`. A client can decode a JWT for display, but the moment that decoding is relied on it becomes an authorization decision made by the party being authorized. Answering server-side keeps that boundary somewhere it cannot be blurred by a refactor.
- Adding it immediately exposed a bug worth remembering: `/api/auth/me` sits under `/api/auth/**`, which is `permitAll`, so it was publicly reachable and returned 500 on a null principal rather than 401. Spring Security matches first rule wins, so the fix is an explicit `/api/auth/me` rule placed *before* the blanket one. A prefix rule written for one purpose silently governs everything added under it later.

### When a correct security design constrains the client
Refresh tokens are single-use and replaying one revokes the entire session family. That is the right design and I would not weaken it. But it collides with an ordinary browser reality: two tabs holding the same refresh token both reload, both spend it, and the loser trips reuse detection and logs the user out everywhere.

The tempting fix is a grace window on the server — accept a token that was revoked moments ago. That trades a real security property for client-side convenience, and it is the kind of thing that reads badly when explained out loud: the whole value of reuse detection is that using a spent token is *always* treated as theft, and a grace window is an admission that sometimes it is not.

So the fix belongs on the side that has the problem. Refreshes are single-flighted within a tab through one shared promise, and coordinated across tabs with a `BroadcastChannel`, so exactly one refresh is ever in flight for a user. Worth noting that single-flighting here is not a performance optimisation, which is how it is usually presented — a second concurrent refresh with the same token *is* reuse, so it is a correctness requirement.

The general lesson: when a sound backend invariant makes the client harder to build, the answer is usually to meet the constraint rather than soften the invariant, and to be able to say why.

## Phase 8 — Building the frontend

### Tests that pass for the wrong reason
The most useful habit in this phase was deliberately breaking the implementation to confirm the test noticed. It caught real false greens that review would not have.

The clearest case was the optimistic transfer update. Two mutations — deleting the `onError` rollback entirely, and appending the server's row instead of replacing the optimistic one by id — both left the suite green. The tests were not measuring optimistic handling at all: `onSettled` invalidated the queries, the refetch repaired the screen, and the assertions ran after that. The fix was to freeze the list refetch in the harness so the server answers no further reads after the initial load. With the refetch held, the same mutations failed immediately.

The general shape: an assertion made after a self-healing mechanism has run measures the healing, not the thing under test. Any test whose subject is "state between two events" has to hold the second event open.

The same pattern showed up twice more. A guard test held "still resolving" open with a 150 ms delay and asserted at 60 ms, which passed alone and failed under full-suite load; it now holds the refresh open behind a gate the test releases. And a boundary fixture meant to throw once had its flag spent before the boundary ever saw it, because React retries a render synchronously after a concurrent throw.

### Optimistic updates need an identity, not a position
Inserting an optimistic row and then appending the server's response produces a duplicate for one frame. Reconciling by id — the optimistic row carries `optimistic:${idempotencyKey}` and is replaced by id on success — is what makes the transition invisible. Position-based reconciliation breaks the moment anything else lands concurrently.

### Single-flight refresh is correctness, not performance
Usually presented as an optimisation: don't fire ten refreshes when ten requests expire together. Here it is a correctness requirement, because refresh tokens are single-use and a second concurrent refresh *is* reuse, which revokes the whole session family. One shared promise per tab, and a `BroadcastChannel` across tabs.

The tempting alternative was a server-side grace window accepting a recently-revoked token. That trades a real security property for client convenience, and the whole value of reuse detection is that a spent token is *always* treated as theft. The constraint was met on the side that had the problem.

### Absence is a state worth designing
The current month has no statement, and that is correct rather than missing: a statement is immutable, so issuing one for a month that can still receive entries would record figures nothing could later correct. The screen says "this period is still open" and offers live activity in its place, shaped deliberately unlike an issued statement — no opening/closing figures, no generate action.

This is why the period list is derived from the account's own lifetime rather than from the statements that happen to exist. Listing only what exists would make both interesting states invisible: the month still running, and a closed month nobody has generated yet.

### A conflict is not a rejected form
Two admins reviewing the same fraud flag is the interesting case. The server refuses the second decision with a 409, which is right — a flag leaves OPEN exactly once. The client deliberately does *not* optimistically apply a review: showing a verdict the server is about to reject would mean the loser briefly sees their own decision recorded when it never was.

The subtler part is that invalidation belongs in `onSettled` rather than `onSuccess`. *Losing* the race is precisely when this tab's queue is stale, so the failure path is the one that most needs the refetch.

### Where an error boundary belongs
At the root it catches everything and takes the navigation down with it, leaving a user stranded on a blank page. Inside the layout, around the outlet, a screen that throws costs the user that screen and nothing else. Both exist: the inner one for pages, an outer one for throws above the layout.

Two details make the retry more than a button that redraws the same crash. It drops the cached data first, so a bad response is refetched rather than replayed; and the boundary is keyed by route, so navigating away clears it rather than leaving an error stuck over a screen that was never broken.

### Some properties no unit test can see
Lazily importing Recharts moved 352 kB (103 kB gzipped) out of the entry chunk. Reverting it to a static import keeps all 121 tests green and every screen working — only the download gets worse, which is invisible in review and invisible in CI.

So it is checked against the built output instead: a script that fails if the library lands in the entry chunk, gets duplicated across chunks, or the entry exceeds a gzip ceiling. Worth generalising — a property that no test can observe needs a different kind of check, not a promise to remember.

### Defence in depth, measured
Admin routes enforce the role twice: a URL rule in the filter chain and `@PreAuthorize` on the controller. Removing either one alone leaves every `/api/admin/**` test passing, which is the point of having both. Removing both makes them fail.

That exercise also surfaced an asymmetry worth knowing: `/actuator/metrics` has only the URL rule, because it is a framework-provided endpoint with no controller to annotate. It is protected once, not twice, and no amount of convention fixes that.
