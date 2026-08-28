# Conventions complementaires

En tant qu'agent de codage de ce projet, tu dois respecter les conventions ci-dessous en plus de tes
capacites integrees. Ce fichier est genere depuis CLAUDE.md via `rulesync convert` : le modifier a la main
sera ecrase a la prochaine conversion â€” corriger la source CLAUDE.md a la place.

# CLAUDE.md — BTCVelocity

> Redirect to `.agent/rules/` for all agent policies.

## Rules

- [Velocity Rules](.agent/rules/velocity.md) — Thread safety, Netty conventions, protocol compliance, security-first

## Build

```bash
./gradlew clean build
```

## Key Paths

- Proxy core: `proxy/src/main/java/`
- API: `api/src/main/java/`
- Native: `native/src/main/java/`
- Native permissions: `proxy/src/main/java/com/btcvelocity/proxy/permission/`

## Project Memory

See `memory/MEMORY.md` for the memory index.
