## Pourquoi

Le canal btc:bridge accepte actuellement des PluginMessageEvent dont l'origine peut être un
Player, puis dispatch ConnectRequest et PartyWarp. Les transferts n'imposent pas de preuve forte
de backend, de cible, de taille, de fraîcheur ou d'idempotence. Health/World registries peuvent
également conserver des données dont l'identité serveur n'est pas vérifiée.

Le bridge doit devenir un plan de contrôle backend-à-backend, sans bloquer la boucle Netty et sans
faire confiance aux données transportées par un client.

## Ce qui change

- Accepter les messages V2 uniquement depuis une connexion backend autorisée.
- Borner et valider les payloads avant décodage/distribution.
- Ajouter expiration, correlationId, ACK/NACK et déduplication.
- Valider les cibles, UUID et tailles de party warp.
- Ajouter métriques et logs redacted pour les erreurs, pertes et retries.
- Maintenir une compatibilité de migration explicitement limitée avec les messages V1.

### Coût de gameplay

Les transferts autorisés conservent leur sémantique. Les messages non authentifiés seront refusés,
ce qui peut révéler des extensions qui utilisent encore directement BungeeCord ou un bridge V1.
Cette rupture est volontaire et doit être recensée avant déploiement.

### Non-objectifs

- Ne pas modifier le pipeline Netty général.
- Ne pas déplacer la persistence joueur dans le proxy.
- Ne pas inventer un fallback silencieux pour un backend inconnu.
- Ne pas stocker de secrets dans le dépôt ou dans les fixtures.

## Impact

- VelocityBridgeChannel, SocialTransferHandler, BridgeCodec et registries.
- Contrat partagé avec BTC-CORE bridge-plugin.
- Tests unitaires et tests adversariaux de messages client/backend.
