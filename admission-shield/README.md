# btc-admission-shield

Plugin Velocity qui refuse les connexions venues d'un **datacenter** ou d'une **sortie Tor**,
jugées sur des listes publiques gardées **sur le proxy**. Aucune adresse de joueur ne sort : le seul
trafic sortant est le téléchargement périodique des listes (X4BNet `lists_vpn`, FireHOL
`tor_exits`). Sentinel 13.2 et 13.6.

## Ce qu'il fait

| | |
|---|---|
| Refus | datacenter (`ADM-VPN-DC`) et Tor (`ADM-VPN-TOR`), code affiché au joueur ; mode `observe` pour un essai à blanc |
| Décision | au pré-login ; reportée au login pour une exemption nominative ou un joueur Floodgate. Un refus de pré-login est **ré-appliqué** au login, au cas où un autre plugin l'aurait levé |
| Exemptions | joueur = pseudo **et** UUID ensemble (un UUID seul est une erreur de configuration) ; réseaux en CIDR |
| Geyser | `geyser-addresses` **vide par défaut** tant que 13.1 n'a pas mesuré l'adresse vue au proxy. Une connexion venue d'une de ces adresses sans être joueur Floodgate est journalisée `geyser-address-not-floodgate`, signalée en WARN et jugée normalement |
| Journal | une ligne `key=value` par refus, observation ou exemption, sur le logger `btc.admission.journal` ; `identity=declared` au pré-login, `authenticated` au login |
| Staff | `/admission why <pseudo\|ip>`, `/admission status` (`btc.admission.staff`) ; alerte en jeu à chaque refus, au plus un message toutes les 10 s |

## Garde-fous des listes

Une liste est le fichier de quelqu'un d'autre : empoisonnée ou cassée, elle ne doit pas pouvoir
refuser les joueurs.

- **Ligne** : un préfixe plus large que `/8` (IPv4) ou `/20` (IPv6) est ignoré (`0.0.0.0/0` compris).
  `/20` : la ligne la plus large observée dans X4BNet est un `/24`, la marge couvre un opérateur
  de datacenter qui annonce un grand bloc sans laisser passer un `/16` d'ISP.
- **Liste** : rejetée entière, la précédente valide reste en place et une ERROR est journalisée, si
  elle dépasse `lists.max-entries` (la lecture s'arrête alors, la liste n'est pas lue en entier),
  couvre plus de 20 % de l'espace IPv4 ou plus de 0,01 % de l'espace IPv6 global (`2000::/3`), ou,
  face à la version en service, voit son nombre d'entrées varier de plus de 50 %, sa couverture
  doubler, ou **une famille d'adresses apparaître** alors que la version en service n'en avait pas.
- **Première charge** : sans version précédente, la liste est jugée contre une ligne de base
  embarquée par source (`lists.embedded-baseline`, vrai par défaut) : bornes d'entrées et de
  couverture par famille. Une source sans ligne de base le dit en WARN. Une ligne de base vieillit :
  la rafraîchir à chaque montée de version du module. Plus de consigne « supprimer le fichier et
  redémarrer » : la quarantaine ci-dessous est la voie normale.
- **Quarantaine** : une nouvelle version (différente de celle en service) n'est pas appliquée
  tout de suite. Elle attend `lists.quarantine-hours` heures (6 par défaut, 0 la supprime) ; pendant
  ce temps le moteur la juge **en ombre** : chaque connexion qu'elle refuserait en plus est
  journalisée `would_refuse` et comptée, sans rien refuser. À l'échéance : si les joueurs
  **authentifiés** distincts refusés en plus sont au plus `lists.quarantine-max-extra-players`
  (0 par défaut), elle est promue ; sinon elle est rejetée et le staff en ligne est alerté.
  `/admission status` liste les versions en attente. Un redémarrage oublie une quarantaine (le
  prochain téléchargement en ouvre une autre). Attention : avec un seuil à 0, un seul joueur légitime
  sur une adresse nouvellement listée fait rejeter la version ; si cela se répète, la liste en
  service vieillit sans bruit : surveiller son âge dans `/admission status`.
- **Taille** : un téléchargement est borné à `lists.max-download-megabytes` (8 par défaut).
- **Adresse** : loopback, RFC 1918, `100.64.0.0/10`, lien-local, ULA IPv6 ne sont **jamais**
  classées. 6to4, Teredo et NAT64 sont déballés et jugés sur l'IPv4 qu'ils transportent.
- **Téléchargement** : échéance globale (connexion + en-têtes + corps), taille bornée, fichier
  temporaire propre à chaque tentative, jamais deux rafraîchissements à la fois.

## Journal : fichier dédié, purge à 180 jours

Le module livre `log4j2-admission-journal.xml` (dans le jar, et copié au premier démarrage dans
`plugins/btc-admission-shield/`). Ce fragment donne au logger `btc.admission.journal` un fichier à
lui, `logs/admission/journal.log`, **tourné chaque jour** (`journal-AAAA-MM-JJ.log.gz`) et **purgé
après 180 jours**. `additivity="false"` tient les adresses hors de `logs/latest.log`, dont Velocity
ne supprime jamais les archives.

Il se charge au démarrage de la JVM, en configuration composite après celle du proxy :

```
java -Dlog4j2.configurationFile=log4j2.xml,plugins/btc-admission-shield/log4j2-admission-journal.xml -jar velocity.jar
```

`log4j2.xml` est la configuration embarquée dans le jar du proxy. Sans ce drapeau, le journal
part dans la console et `logs/latest.log`, **sans purge**.

**Borne sur disque** : en plus du jour, le fichier tourne à 50 Mo (`journal-AAAA-MM-JJ-N.log.gz`) et
la purge supprime ce qui a plus de 180 jours **ou** ce qui fait dépasser 1 Go d'archives : sous une
inondation, la taille l'emporte sur les 180 jours.

**Agrégation** : les refus d'un même /24 (IPv4) ou /64 (IPv6) dans la même minute ne sont écrits en
entier que pour le premier ; les suivants sont comptés et résumés en une ligne `event=aggregate`
(réseau, nombre, dernier code) à la fin de la minute. Au plus 10 000 réseaux suivis. Le staff voit
l'adresse masquée (/24 ou /48) et le pseudo échappé (`§` écrit `§`) ; `/admission why` donne
l'adresse complète.

## Ordre des événements Velocity

Le refus de connexion s'applique au `LoginEvent` à la priorité la plus basse (`Short.MIN_VALUE`),
donc **après** tout autre plugin, qui ne peut plus le lever. Filet : un `PostLoginEvent` à la même
priorité déconnecte un joueur refusé qui serait passé. Un refus de pré-login est mémorisé 2 minutes
pour ce rapprochement.

> **À valider au banc** :
> - que log4j2 résolve `log4j2.xml` depuis le classpath dans la liste composite, et que la
>   rotation (jour et `%i`) et la suppression (`IfAny`) se déclenchent (avancer l'horloge ou
>   réduire les seuils le temps de l'essai) ;
> - l'ordre réel des priorités et le comportement du `PostLoginEvent` dans un Velocity vivant ;
> - une quarantaine sur un vrai cycle de rafraîchissement, et le nombre de `would_refuse` sur du
>   trafic réel avant de choisir le seuil ;
> - la ligne de base embarquée face aux listes du jour (elle vieillit).

## Hors périmètre

GeoLite2 ASN (licence MaxMind à trancher), anti-bot (Sonar, 13.3), débit par /24 ou par ASN (13.4).
