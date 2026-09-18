<div align="center">

# PAYVERO

### Distributed Digital Payment & Financial Ledger Platform

[![CI Pipeline](https://github.com/radhikapatil17/Payvero/actions/workflows/ci.yml/badge.svg)](https://github.com/radhikapatil17/Payvero/actions/workflows/ci.yml)
[![License: MIT](https://img.shields.io/badge/License-MIT-emerald.svg)](LICENSE)
[![Java 21](https://img.shields.io/badge/Java-21-orange.svg)](https://www.oracle.com/java/)
[![Spring Boot 4.1](https://img.shields.io/badge/Spring_Boot-4.1.0-brightgreen.svg)](https://spring.io/projects/spring-boot)
[![React 19](https://img.shields.io/badge/React-19-blue.svg)](https://react.dev/)
[![PostgreSQL 16](https://img.shields.io/badge/PostgreSQL-16-blue.svg)](https://www.postgresql.org/)
[![Apache Kafka](https://img.shields.io/badge/Apache_Kafka-3.8-red.svg)](https://kafka.apache.org/)
[![Redis 7](https://img.shields.io/badge/Redis-7-red.svg)](https://redis.io/)

**Payvero** is a high-throughput, event-driven financial ledger and digital payment platform built on production-grade Java 21, Spring Boot 4, PostgreSQL, Redis, and Apache Kafka.

[Key Architecture](#-system-architecture) • [Double-Entry Engine](#-double-entry-accounting-journal-engine) • [Idempotency](#-idempotency--rate-limiting) • [API Guide](#-api-specification) • [Developer](#-developer)

</div>

---

## 🌟 Overview

In modern financial infrastructure, payment engines must guarantee **immutable accounting**, **zero-sum balance invariants**, and **resilient idempotency**. Traditional applications that rely on mutable account balance columns (`UPDATE accounts SET balance = balance + amount`) suffer from lost updates under concurrency, unauditable history, and vulnerability to network retries.

**Payvero** solves these core financial software challenges by combining:
1. **Double-Entry Accounting**: Account balances are derived dynamically from immutable journal entries ($\sum \text{Credits} - \sum \text{Debits}$).
2. **Database-Enforced Immutability**: PostgreSQL database triggers explicitly block `UPDATE` and `DELETE` operations on journal records.
3. **Idempotency Protection**: Redis claim locking and response caching prevent duplicate payments across client retries.
4. **Transactional Outbox Event Streaming**: Financial updates and domain events commit atomically before asynchronous broadcast via Apache Kafka.
5. **Stateless JWT Security**: Dual-token authentication featuring automated token rotation and reuse detection.

---

## ⚡ Key Features & Technical Highlights

- **🔒 Immutable Accounting Journal**: Append-only `journal_entries` table guarded by PostgreSQL `BEFORE UPDATE OR DELETE` triggers.
- **🛡️ Distributed Idempotency**: Redis claim store (`IN_PROGRESS`, `COMPLETED`) caching exact responses for client-supplied `Idempotency-Key` headers.
- **⚡ Sliding-Window Rate Limiting**: Distributed Lua scripts executed atomically in Redis sorted sets (`ZREMRANGEBYSCORE` + `ZADD`).
- **📬 Transactional Outbox Pattern**: Guarantees at-least-once event delivery to Kafka without distributed two-phase commit (2PC) bottlenecks.
- **🚨 Real-Time Fraud & Velocity Detection**: Asynchronous Kafka workers analyzing transfer frequency and cumulative dollar velocity per sliding window.
- **📊 Period-Closed Statement Generator**: Automatic monthly statement generation fixing historical balances permanently.
- **💎 Modern Fintech Dashboard**: Sleek React 19 UI with glassmorphism styling, live balance history charts, active idempotency indicators, and preset demo accounts.

---

## 📐 System Architecture

```
                               ┌────────────────────────────────┐
                               │       Payvero Web Client       │
                               │      (React 19 / Vite UI)      │
                               └───────────────┬────────────────┘
                                               │
                                      HTTP / REST API (JWT Auth)
                                               │
                                               ▼
                               ┌────────────────────────────────┐
                               │      Payvero Backend API       │
                               │     (Spring Boot / Java 21)    │
                               └───────┬───────────────┬────────┘
                                       │               │
                     Idempotency Keys  │               │ DB Transactions &
                     & Rate Limiting   │               │ Append-Only Triggers
                                       ▼               ▼
                               ┌───────────────┐ ┌───────────────┐
                               │  Redis Store  │ │ PostgreSQL DB │
                               └───────────────┘ └───────┬───────┘
                                                       │
                                              Transactional Outbox
                                                       │
                                                       ▼
                                               ┌───────────────┐
                                               │ Apache Kafka  │
                                               └───────┬───────┘
                                                       │
                                        ┌──────────────┴──────────────┐
                                        ▼                             ▼
                               ┌─────────────────┐           ┌─────────────────┐
                               │  Audit Consumer │           │ Fraud Consumer  │
                               └─────────────────┘           └─────────────────┘
```

---

## ⚖️ Double-Entry Accounting Journal Engine

In Payvero, every financial transaction consists of balanced debit and credit entries. The net sum across all platform entries is strictly zero:

$$\sum \text{Debits} - \sum \text{Credits} = 0$$

> [!IMPORTANT]
> **Balance Derivation**: An account's balance is never written as raw editable state. It is derived on-demand as:
> $$\text{Derived Balance} = \sum \text{Credits} - \sum \text{Debits}$$
> The `cached_balance` column on `accounts` exists purely as an optimistic read cache and is guarded against race conditions by JPA optimistic locking (`@Version`).

### System Treasury Accounting Matrix

All funds entering or leaving the platform transact against the **System Treasury Account** (`00000000-0000-0000-0000-000000000001`).

| Transaction Flow | Debit Account | Credit Account | Platform Net Balance |
| :--- | :--- | :--- | :--- |
| **Account Funding / Deposit** | System Treasury | User Wallet | $\mathbf{0.00}$ |
| **P2P Transfer** | Sender Wallet | Recipient Wallet | $\mathbf{0.00}$ |
| **Account Withdrawal** | User Wallet | System Treasury | $\mathbf{0.00}$ |

---

## 🛡️ Idempotency & Rate Limiting

### Idempotency Protection Flow
When a payment request arrives with an `Idempotency-Key` header:

```
Client Request (with Idempotency-Key)
    │
    ▼
Check Redis Claim Store
    ├── Key Exists & COMPLETED ──► Return Cached Response (HTTP 200/201)
    ├── Key Exists & IN_PROGRESS ─► Reject with RequestInProgressException (HTTP 409)
    └── Key Missing ──────────────► Store Claim (IN_PROGRESS)
                                        │
                                        ▼
                            Execute Accounting Transaction
                                        │
                                        ▼
                            Update Claim (COMPLETED + Payload)
```

> [!TIP]
> Pressing "Retry" after a network failure reuses the active `Idempotency-Key`, guaranteeing that money is never transferred twice.

---

## 📬 Transactional Outbox & Kafka Event Streaming

To eliminate data drift between PostgreSQL and Kafka without complex distributed locks:

1. Accounting updates and domain event payloads (`outbox_events`) are persisted within the **same local PostgreSQL transaction**.
2. A dedicated scheduler (`OutboxPublisher`) drains pending outbox events every `500ms`.
3. Events are published to dedicated Kafka topics:
   - `transfer.events`: Broadcasts payment state changes (`PENDING`, `SETTLED`, `FLAGGED`, `FAILED`).
   - `journal.audit`: Broadcasts immutable accounting journal records consumed by `AuditTrailConsumer`.

---

## 🔒 Security & JWT Lifecycle

- **Stateless Bearer Tokens**: 5-minute access token TTL signed via HS256.
- **Refresh Token Rotation**: 7-day refresh token TTL stored as secure BCrypt hashes.
- **Family Reuse Detection**: Refresh tokens carry a `family_id`. Presenting a previously consumed token triggers instant revocation of the entire token family tree.
- **Role-Based Authorization**:
  - `USER`: Access personal accounts, execute transfers, view statements.
  - `ADMIN`: Access real-time fraud queue, execute integrity scans, review audit logs.

---

## 🛠️ Tech Stack & Technologies

### Backend Core
- **Java 21 (LTS)** — Records, Sealed Interfaces, Pattern Matching, Virtual Threads.
- **Spring Boot 4.1.0** — WebMVC, Spring Security, Spring Data JPA, Actuator.
- **PostgreSQL 16** — RDBMS with least-privilege runtime roles (`payvero_app`) and schema triggers.
- **Redis 7** — Fast ephemeral store for idempotency claims and atomic Lua rate limiters.
- **Apache Kafka 3.8 (KRaft)** — Event streaming cluster without ZooKeeper dependency.
- **Flyway** — Versioned, reproducible SQL schema migrations.

### Frontend Interface
- **React 19 & TypeScript** — Modern component-driven application.
- **Vite 8** — Fast build toolchain.
- **Tailwind CSS v4 & shadcn/ui** — Sleek fintech design system.
- **TanStack Query & React Router 7** — Asynchronous server-state management.
- **Recharts** — Dynamic balance and transaction volume analytics.

---

## 🌐 API Specification

All API endpoints follow RESTful standards and return consistent JSON error objects (`ErrorResponse`).

### Authentication (`/api/v1/auth`)
| Method | Endpoint | Description | Access |
| :--- | :--- | :--- | :--- |
| `POST` | `/api/v1/auth/register` | Create a new user account | Public |
| `POST` | `/api/v1/auth/login` | Authenticate & obtain JWT pair | Public |
| `POST` | `/api/v1/auth/refresh` | Rotate refresh token | Public |
| `GET` | `/api/v1/auth/me` | Fetch active user profile | Authenticated |

### Accounts & Payments (`/api/v1`)
| Method | Endpoint | Description | Access |
| :--- | :--- | :--- | :--- |
| `GET` | `/api/v1/accounts` | List user accounts & balances | Authenticated |
| `GET` | `/api/v1/accounts/{id}/balance` | Fetch derived balance & check consistency | Authenticated |
| `POST` | `/api/v1/transfers` | Initiate payment (requires `Idempotency-Key`) | Authenticated |
| `GET` | `/api/v1/transfers` | Fetch paginated transfer history | Authenticated |
| `GET` | `/api/v1/statements` | Fetch period-closed monthly statements | Authenticated |

### Administration (`/api/v1/admin`)
| Method | Endpoint | Description | Access |
| :--- | :--- | :--- | :--- |
| `GET` | `/api/v1/admin/fraud/flags` | List flagged velocity events | `ADMIN` |
| `POST` | `/api/v1/admin/fraud/flags/{id}/review` | Approve or clear fraud flag | `ADMIN` |
| `GET` | `/api/v1/admin/accounting/integrity` | Perform full zero-sum ledger audit | `ADMIN` |
| `GET` | `/api/v1/admin/audit/logs` | Inspect immutable system audit log | `ADMIN` |

---

## 📁 Project Directory Hierarchy

```
Payvero/
├── backend/
│   ├── src/
│   │   ├── main/
│   │   │   ├── java/dev/payvero/
│   │   │   │   ├── accounting/   # Double-entry journal engine, integrity checker
│   │   │   │   ├── api/          # Web config, exception handler, metrics, OpenAPI
│   │   │   │   ├── audit/        # Transactional outbox publisher & audit consumer
│   │   │   │   ├── auth/         # JWT auth, security filter chain, user details
│   │   │   │   ├── fraud/        # Real-time velocity tracker & fraud detector
│   │   │   │   ├── seed/         # Demo dataset seeder
│   │   │   │   ├── statement/    # Period-closed statement generator
│   │   │   │   ├── transfer/     # Payment processing, idempotency & rate limiter
│   │   │   │   └── PayveroApplication.java
│   │   │   └── resources/
│   │   │       ├── application.yml
│   │   │       └── db/migration/  # Flyway SQL migrations (V1 to V9)
│   │   └── test/java/dev/payvero/ # Integration & unit test suite
│   └── pom.xml
├── frontend/
│   ├── src/
│   │   ├── components/            # PayveroBrandLogo, AppLayout, UI primitives
│   │   ├── routes/                # Auth, Dashboard, Transfers, Statements, Admin
│   │   └── lib/                   # API client, React Query hooks, money utils
│   ├── index.html
│   └── package.json
├── docker/
│   └── postgres/init/01-app-role.sql
├── docker-compose.yml
├── .env.example
├── PAYVERO_ARCHITECTURE.md
├── PAYVERO_NOTES.md
└── README.md
```

---

## 🧪 Testing Strategy

Payvero includes an automated test suite leveraging **Spring Boot Test**, **Testcontainers**, and **Vitest**:

```bash
# Backend Test Suite (Real Container Tests for Postgres, Redis, Kafka)
cd backend
./mvnw test

# Frontend Test Suite
cd frontend
npm run test
```

### Key Test Scenarios Covered
- **Authentication**: Credentials validation, JWT signing, refresh token rotation, family-level revocation.
- **Payment Processing**: Idempotent execution, rate limit enforcement, insufficient balance rejection.
- **Double-Entry Balance**: Zero-sum entry validation, append-only trigger enforcement, re-derived balance scans.
- **Concurrency**: Optimistic locking retry behavior under write races.

---

## 🚀 Development Setup Guide

### 1. Prerequisites
- **JDK 21**
- **Node.js 20+ & npm**
- **Docker & Docker Compose**

### 2. Start Infrastructure
Launch PostgreSQL, Redis, and Apache Kafka:
```bash
docker compose up -d
```

### 3. Configure Environment
Copy the example environment template:
```bash
cp .env.example .env
```

### 4. Run Backend API
```bash
cd backend
./mvnw spring-boot:run -Dspring-boot.run.profiles=seed
```

> [!NOTE]
> Activating the `seed` profile populates demo data with preset accounts:
> - **Alice User**: `alice@payvero.dev` / `demo-password-123`
> - **Bob User**: `bob@payvero.dev` / `demo-password-123`
> - **Admin User**: `admin@payvero.dev` / `demo-password-123`

### 5. Run Frontend Client
```bash
cd frontend
npm install
npm run dev
```
Open `http://localhost:5173` in your browser.

---

## 👤 Developer

**Radhika Patil**  
GitHub: [@radhikapatil17](https://github.com/radhikapatil17)  
Portfolio Project — Distributed Systems & Production-Grade Financial Engineering

---

## 📜 License

This project is licensed under the [MIT License](LICENSE).
