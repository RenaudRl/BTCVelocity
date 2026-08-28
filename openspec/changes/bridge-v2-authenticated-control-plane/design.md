## Décisions

### 1. Origine et autorisation

Le handler distingue explicitement ServerConnection et Player. Les opérations de contrôle sont
acceptées uniquement depuis un backend dont le nom correspond à la connexion et à une allowlist
de configuration. Toute origine Player est handled puis rejetée ; elle ne doit pas être relayée.

Les transferts exigent une cible déclarée, autorisée et actuellement disponible. PartyWarp impose
une taille maximale et vérifie les membres avant tout déplacement.

### 2. Codec et replay

Le codec vérifie la taille brute avant parsing JSON, la version, les champs obligatoires, la
longueur des chaînes et les bornes des listes. Le messageId et l'expiration sont obligatoires en
V2. Une déduplication TTL bornée retourne le même ACK pour un doublon sans exécuter deux fois.

### 3. ACK et observabilité

Chaque commande produit ACK ou NACK avec messageId, catégorie et backend source. Les logs n'incluent
ni payload brut, ni secret, ni UUID complet si ce n'est pas nécessaire. Les métriques mesurent
réception, rejet, dispatch, timeout et retry.

### 4. Migration

V1 est accepté uniquement derrière un mode de compatibilité explicitement activé, limité aux
backends allowlistés et journalisé comme dette. Le mode par défaut est V2 fail-closed.
