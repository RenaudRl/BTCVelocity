# BTC Studio Repository

Dépôt Maven **statique unifié** hébergeant les APIs publiques du réseau Born To Craft :

| API | Coordonnées | Version |
|-----|-------------|---------|
| BTC Velocity (proxy) | `dev.btc.velocity:api` | `0.2` |
| BTC-CORE (serveur) | `dev.btc.core:api` | `26.2.build.6-alpha` |

## Structure

```
repo/                                  ← upload tel quel sur borntocraftstudio.net/repo/
├── index.html                         ← page d'accueil
├── dev/btc/velocity/api/              ← API BTC Velocity
│   ├── maven-metadata.xml
│   └── 0.2/api-0.2[.jar|-sources.jar|-javadoc.jar|.pom|.module]
├── dev/btc/core/api/                  ← API BTC-CORE
│   ├── maven-metadata.xml
│   └── 26.2.build.6-alpha/api-26.2.build.6-alpha.*
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
    mavenCentral() // requis : le POM importe net.kyori:adventure-bom
}
```

**Maven**
```xml
<repository>
    <id>btcstudio</id>
    <url>https://borntocraftstudio.net/repo/</url>
</repository>
```

> Maven Central (ou un miroir) doit rester déclaré : le POM de `dev.btc.velocity:api`
> importe `net.kyori:adventure-bom`, qui n'est pas hébergé ici. Avec le seul dépôt
> BTC, la résolution échoue sur `Could not parse POM … Could not find
> net.kyori:adventure-bom`.

### BTC Velocity API

```kotlin
dependencies {
    compileOnly("dev.btc.velocity:api:0.2")
}
```
```xml
<dependency>
    <groupId>dev.btc.velocity</groupId>
    <artifactId>api</artifactId>
    <version>0.2</version>
    <scope>provided</scope>
</dependency>
```

### BTC-CORE API

```kotlin
dependencies {
    compileOnly("dev.btc.core:api:26.2.build.6-alpha")
}
```
```xml
<dependency>
    <groupId>dev.btc.core</groupId>
    <artifactId>api</artifactId>
    <version>26.2.build.6-alpha</version>
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
et publiée par `gradlew :api:publishBtcApiPublicationToBtcRepoRepository` depuis BTC-CORE-Fork,
qui écrit directement dans `repo/dev/btc/core/`. Ne JAMAIS recopier un jar à la main dans une
version déjà publiée : c'est ainsi que `26.2.build.5-alpha` a fini muté sur place — même
coordonnée, jar différent, panne invisible des deux côtés. Une nouvelle version, toujours.
