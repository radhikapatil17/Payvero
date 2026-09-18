# Payvero — Technical & Architectural Deep-Dive

**Payvero — Distributed Digital Payment & Financial Ledger Platform**

---

## 1. System Overview

Payvero is built as an event-driven, production-grade financial ledger engine using Java 21, Spring Boot 4, PostgreSQL, Redis, and Apache Kafka. The platform is architected around financial correctness, transactional consistency, optimistic concurrency, double-entry accounting, and idempotent execution.

```
                  ┌──────────────────────────────────────────────┐
                  │                 Payvero Client               │
                  │              (React / Vite Frontend)         │
                  └──────────────────────┬───────────────────────┘
                                         │  HTTP / REST (JWT Auth)
                                         ▼
                  ┌──────────────────────────────────────────────┐
                  │           Payvero Spring Boot Core           │
                  │   ┌──────────────────────────────────────┐   │
                  │   │   Idempotency & Rate Limiting (Redis)│   │
                  │   └──────────────────┬───────────────────┘   │
                  │                      │                       │
                  │   ┌──────────────────▼───────────────────┐   │
                  │   │   Payment & Ledger Engine (Postgres) │   │
                  │   └──────────────────┬───────────────────┘   │
                  │                      │                       │
                  │   ┌──────────────────▼───────────────────┐   │
                  │   │    Transactional Outbox Publisher    │   │
                  │   └──────────────────┬───────────────────┘   │
                  └──────────────────────┼───────────────────────┘
                                         │  Kafka Event Bus
                                         ▼
                  ┌──────────────────────────────────────────────┐
                  │         Kafka Asynchronous Consumers         │
                  │    ┌──────────────────┐ ┌────────────────┐   │
                  │    │  Audit Trail     │ │ Fraud Detector │   │
                  │    └──────────────────┘ └────────────────┘   │
                  └──────────────────────────────────────────────┘
```

---

## 2. Double-Entry Accounting Invariants

Every financial movement in Payvero adheres to strict double-entry principles:

$$\sum \text{Debits} - \sum \text{Credits} = 0$$

1. **Immutable Ledger Entries**: Ledger records (`journal_entries`) are append-only. A database trigger explicitly rejects `UPDATE` and `DELETE` SQL commands on ledger rows.
2. **Derived Account Balances**: Account balances are derived dynamically from entry sums. A cached balance field exists solely as an optimistic performance layer and is continuously verified against SQL aggregates during system integrity scans.
3. **Balanced Treasury Transactions**: Money entering or exiting the platform moves to/from a system Treasury account, preserving zero-sum balance invariants across all platform accounts.

---

## 3. Idempotent Payment Pipeline

Duplicate network requests or client retries carrying the same `Idempotency-Key` header are guaranteed to execute at most once.

```
Client Request (with Idempotency-Key)
    │
    ▼
Check Redis Idempotency Store
    ├── Key Exists & Completed ──► Return Stored Response (HTTP 200/201)
    ├── Key Exists & In-Progress ─► Reject with RequestInProgressException (HTTP 409)
    └── Key Missing ──────────────► Claim Key (IN_PROGRESS) in Redis
                                        │
                                        ▼
                            Execute Payment Transaction
                                        │
                                        ▼
                            Persist Result in Redis Store
```

---

## 4. Transactional Outbox Pattern

To ensure reliable event publishing without two-phase commit (2PC) distributed transaction overhead:

1. Financial updates and event payloads (`outbox_events`) are committed within the **same local database transaction**.
2. A background scheduler (`OutboxPublisher`) polls pending outbox events and publishes them to Kafka topics (`transfer.events` and `ledger.audit`).
3. Asynchronous consumers (`AuditTrailConsumer` and `FraudDetectionConsumer`) process events reliably with at-least-once delivery guarantees.

---

## 5. Security & Access Control

- **JWT Authentication**: Short-lived access tokens (5 min TTL) paired with rotated refresh token families (7 days TTL).
- **Token Family Revocation**: Replaying an already-consumed refresh token triggers immediate revocation of all tokens in that family lineage.
- **Role-Based Authorization**: `USER`, `ADMIN`, and `SUPPORT` roles with strict endpoint protection.
