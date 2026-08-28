## ADDED Requirements

### Requirement: Client-originated control messages are rejected

The proxy MUST handle and reject client-originated btc:bridge control messages before dispatching
transfers, party warps, health mutations, or world registry mutations.

#### Scenario: Player sends connect request

- **WHEN** a Player is the source of a connect_request or party_warp
- **THEN** the message is handled, rejected, and not dispatched

#### Scenario: Authorized backend sends request

- **WHEN** an allowlisted backend sends a valid V2 request over its backend connection
- **THEN** the request is dispatched once and acknowledged

### Requirement: Bridge payloads are bounded

The proxy MUST reject oversized, malformed, expired, unknown-version, and duplicate messages before
expensive parsing or dispatch.

#### Scenario: Oversized payload

- **WHEN** the bridge receives a payload above the configured limit
- **THEN** it is rejected without JSON deserialization or event-loop blocking

### Requirement: Registry updates are attributable

Health and world updates MUST be tied to the actual backend connection and expire when stale.

#### Scenario: Backend identity mismatch

- **WHEN** a message declares a serverName different from its source backend
- **THEN** the update is rejected and the registry remains unchanged
