# Architecture Decision Index

This file provides a quick index to VaultFlow's Engineering Decision Records (EDRs).

Full decision records with detailed rationale and trade-offs are in **[docs/ENGINEERING_DECISION_RECORDS.md](docs/ENGINEERING_DECISION_RECORDS.md)**.

---

| EDR | Decision | Status | Date |
|---|---|---|---|
| [EDR-001](docs/ENGINEERING_DECISION_RECORDS.md#edr-001-content-addressed-object-storage) | SHA-256 content-addressed storage for automatic deduplication | Accepted | 2024-01 |
| [EDR-002](docs/ENGINEERING_DECISION_RECORDS.md#edr-002-asynchronous-processing-via-kafka) | Async processing pipeline via Kafka (not synchronous in-request) | Accepted | 2024-01 |
| [EDR-003](docs/ENGINEERING_DECISION_RECORDS.md#edr-003-rs256-jwt-authentication-asymmetric-over-hmac) | RS256 asymmetric JWT over HMAC-256 shared secret | Accepted | 2024-01 |
| [EDR-004](docs/ENGINEERING_DECISION_RECORDS.md#edr-004-hexagonal-architecture-for-storage-layer) | Hexagonal architecture (ports and adapters) for storage layer | Accepted | 2024-01 |
| [EDR-005](docs/ENGINEERING_DECISION_RECORDS.md#edr-005-redis-for-upload-session-state) | Redis for hot upload session state (over PostgreSQL-only) | Accepted | 2024-01 |
| [EDR-006](docs/ENGINEERING_DECISION_RECORDS.md#edr-006-virtual-threads-java-21-for-http-and-processing) | Java 21 Virtual Threads over WebFlux reactive model | Accepted | 2024-01 |
| [EDR-007](docs/ENGINEERING_DECISION_RECORDS.md#edr-007-partitioned-audit-logs) | Monthly-partitioned audit log table in PostgreSQL | Accepted | 2024-01 |
| [EDR-008](docs/ENGINEERING_DECISION_RECORDS.md#edr-008-nginx-over-spring-cloud-gateway) | NGINX as API gateway over Spring Cloud Gateway | Accepted | 2024-01 |

---

## Proposing a New Decision

When making a significant architectural change, add an EDR to [docs/ENGINEERING_DECISION_RECORDS.md](docs/ENGINEERING_DECISION_RECORDS.md) before implementing. See [CONTRIBUTING.md](CONTRIBUTING.md#architecture-decisions) for the EDR template.

Decisions that warrant an EDR:
- Introducing or removing an external dependency
- Changing a Kafka event schema in a backward-incompatible way
- Changing the authentication or authorization model
- Choosing between two viable architectural approaches
- Accepting a known trade-off that future engineers might question
