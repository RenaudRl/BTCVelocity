# velocity-cloudnet-registry

Plugin Velocity qui tient la liste des serveurs du proxy à jour avec les services que CloudNet
fait tourner — **et rien d'autre**.

Il remplace le plugin bridge de CloudNet **sur le proxy** (décision `AD-047`, change OpenSpec
`network-orchestration-cloudnet`). Le bridge reste installé sur les backends, où il se contente
d'observer.

## Ce qu'il fait

| | |
|---|---|
| Enregistre un service | quand il est un serveur Java, connecté, `RUNNING`, et qu'il publie `Online` |
| L'oublie | dès qu'une de ces conditions tombe |
| Oublie **tout** | quand le canal du nœud se ferme — périmé vaut indisponible, jamais vide |
| Publie l'occupation du proxy | `Online`, `Online-Count`, `Max-Players`, poussés, jamais sondés |

## Ce qu'il ne fait pas, délibérément

Le plugin bridge de CloudNet, côté proxy, déplaçait des joueurs de sa propre initiative :

- il choisissait le serveur initial, et **déconnectait** s'il ne trouvait pas de hub ;
- il redirigeait vers son repli sur `KickedFromServerEvent` ;
- il enregistrait `hub`, `lobby`, `leave`, `l` ;
- il interrogeait le nœud **de façon bloquante à chaque login**.

Aucune option de configuration ne désactivait cela. Ici, ces surfaces n'existent pas : la
destination d'un joueur est une décision métier, portée par `btc:bridge` v2.

## Déploiement

Le jar se dépose dans `plugins/` du template de la tâche proxy, et le groupe de cette tâche doit
figurer dans `excludedGroups` de `modules/CloudNet-Bridge/config.json` — sinon le nœud réinjecte
le bridge à chaque démarrage de service.

Hors d'un service CloudNet, le plugin se charge et ne fait rien.

## Compatibilité

Compilé contre CloudNet `4.0.0-RC17` (`driver-api`, `wrapper-jvm-api`), fournis à l'exécution par
le wrapper. Toute montée de RC est un changement à part entière : revérifier que
`NetworkChannelCloseEvent`, `CloudServiceUpdateEvent` et `ServiceInfoPropertiesConfigureEvent`
existent toujours sous ces noms.
