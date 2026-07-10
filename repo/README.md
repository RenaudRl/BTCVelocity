# BTC Studio Repository

Dépôt Maven **statique unifié** hébergeant les APIs publiques du réseau Born To Craft :

| API | Coordonnées | Version |
|-----|-------------|---------|
| BTC Velocity (proxy) | `dev.btc.velocity:api` | `0.1` |
| BTC-CORE (serveur) | `dev.btc.core:api` | `26.1.2.build.19-alpha` |

## Structure

```
repo/                                  ← upload tel quel sur borntocraftstudio.net/repo/
├── index.html                         ← page d'accueil
├── dev/btc/velocity/api/              ← API BTC Velocity
│   ├── maven-metadata.xml
│   └── 0.1/api-0.1[.jar|-sources.jar|-javadoc.jar|.pom|.module]
├── dev/btc/core/api/                  ← API BTC-CORE
│   ├── maven-metadata.xml
│   └── 26.1.2.build.19-alpha/api-26.1.2.build.19-alpha.*
└── javadoc/
    ├── index.html                     ← landing
    ├── velocity/                       ← javadoc BTC Velocity
    └── core/                          ← javadoc BTC-CORE
```

## Utilisation

### Ajouter le dépôt

**Gradle (Kotlin DSL)**
```kotlin
repositories {
    maven("https://borntocraftstudio.net/repo/")
}
```

**Maven**
```xml
<repository>
    <id>btcstudio</id>
    <url>https://borntocraftstudio.net/repo/</url>
</repository>
```

### BTC Velocity API

```kotlin
dependencies {
    compileOnly("dev.btc.velocity:api:0.1")
}
```
```xml
<dependency>
    <groupId>dev.btc.velocity</groupId>
    <artifactId>api</artifactId>
    <version>0.1</version>
    <scope>provided</scope>
</dependency>
```

### BTC-CORE API

```kotlin
dependencies {
    compileOnly("dev.btc.core:api:26.1.2.build.19-alpha")
}
```
```xml
<dependency>
    <groupId>dev.btc.core</groupId>
    <artifactId>api</artifactId>
    <version>26.1.2.build.19-alpha</version>
    <scope>provided</scope>
</dependency>
```

### Javadoc

- BTC Velocity : https://borntocraftstudio.net/repo/javadoc/velocity/
- BTC-CORE : https://borntocraftstudio.net/repo/javadoc/core/

## Republier / mettre à jour

L'API BTC Velocity se republie directement dans `repo/` via Gradle :

```bash
bash repo/publish.sh
```

Puis commit + upload du dossier `repo/` sur `borntocraftstudio.net/repo/`.
L'API BTC-CORE est fournie depuis le projet BTC-CORE-Fork (voir son propre `publish.sh`)
et copiée dans `repo/dev/btc/core/` + `repo/javadoc/core/`.
