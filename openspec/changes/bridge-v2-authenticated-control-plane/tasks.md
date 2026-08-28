## 1. Ingress security

- [x] 1.1a Rejeter les origines Player côté proxy et n'accepter qu'une ServerConnection enregistrée.
- [ ] 1.1 Distinguer et authentifier les origines backend/client sur btc:bridge dans le contrat V2 complet.
- [ ] 1.2 Appliquer allowlist backend/cible et limites UUID/party.
- [x] 1.3 Garantir handled pour les messages rejetés afin d'empêcher leur fuite.
- [ ] 1.4 Ajouter tests client forgé, backend valide, backend inconnu et cible invalide.

## 2. V2 protocol

- [ ] 2.1 Ajouter envelope versionnée, messageId, expiration et catégories ACK/NACK.
- [ ] 2.2 Borner le payload avant parsing et ajouter déduplication TTL.
- [ ] 2.3 Ajouter tests malformed/oversize/expired/duplicate et compatibilité V1 limitée.

## 3. Registries and operations

- [ ] 3.1 Vérifier serverName contre la connexion source avant Health/WorldLoaded.
- [ ] 3.2 Ajouter TTL et état stale explicite aux registries.
- [ ] 3.3 Exposer les métriques bridge sans bloquer Netty.
- [ ] 3.4 Documenter la rotation et l'injection des secrets hors dépôt.

## 4. Vérification

- [x] 4.1a Compiler et exécuter la suite proxy existante après le durcissement ingress.
- [ ] 4.1 Compiler et exécuter les tests proxy ciblés.
- [ ] 4.2 Exécuter les tests adversariaux sans serveur de production.
- [x] 4.3 Relire le diff limité au changement et vérifier l'absence de secrets.
