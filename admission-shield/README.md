# btc-admission-shield

Plugin Velocity qui refuse les connexions venues d'un **datacenter** ou d'une **sortie Tor**,
jugées sur des listes publiques gardées **sur le proxy**. Aucune adresse de joueur ne sort : le seul
trafic sortant est le téléchargement périodique des listes (X4BNet `lists_vpn`, FireHOL
`tor_exits`). Sentinel 13.2 et 13.6.

## Ce qu'il fait

| | |
|---|---|
| Refus | datacenter (`ADM-VPN-DC`) et Tor (`ADM-VPN-TOR`), code affiché au joueur ; mode `observe` pour un essai à blanc. Trop de tentatives d'un même réseau : `ADM-RATE-NET` (voir plus bas, `observe` par défaut) |
| Décision | au pré-login ; reportée au login pour une exemption nominative ou un joueur Floodgate. Un refus de pré-login est **ré-appliqué** au login, au cas où un autre plugin l'aurait levé |
| Exemptions | joueur = pseudo **et** UUID ensemble (un UUID seul est une erreur de configuration) ; réseaux en CIDR **avec `expires` obligatoire**, `reason` conseillé (voir « Exemptions datées ») |
| ASN | base IP→ASN d'iptoasn.com ; `ADM-VPN-ASN` pour les systèmes de `asn.deny-asns` (vide, `observe` par défaut) ; mots-clés de nom signalés au staff seul (voir « ASN ») |
| Geyser | `geyser-addresses` **vide par défaut** tant que 13.1 n'a pas mesuré l'adresse vue au proxy. Une connexion venue d'une de ces adresses sans être joueur Floodgate est journalisée `geyser-address-not-floodgate`, signalée en WARN et jugée normalement |
| Journal | une ligne `key=value` par refus, observation ou exemption, sur le logger `btc.admission.journal` ; `identity=declared` au pré-login, `authenticated` au login |
| Staff | `/admission why <pseudo\|ip>`, `/admission status` (`btc.admission.staff`) ; alerte en jeu à chaque refus, au plus un message toutes les 10 s |

## Borne de débit par réseau (13.4, `network-rate`)

Indépendante des listes : chaque **pré-login** dépense un jeton du réseau de son adresse, un /24 en
IPv4 ou un /48 en IPv6 (6to4, Teredo, NAT64 et IPv4-mappées sont d'abord lues comme l'IPv4 qu'elles
portent). Un réseau a un seau de `burst` jetons qui se remplit de `attempts` par `window-seconds` ;
une tentative qui trouve le seau vide est au-delà de la limite. Refus : code **`ADM-RATE-NET`**,
texte réglable (`messages.network-rate`), journalisé et agrégé par minute comme les refus de liste.
Technique de Gate (Minekube) : borner le bloc, pas l'adresse, pour ne pas punir un CGNAT.

- **Défauts** : 10 tentatives / 10 s, rafale 20, `observe`. Un /24 d'opérateur mobile français
  derrière CGNAT représente quelques centaines d'abonnés : 1 tentative par seconde en continu est
  très au-dessus de ce qu'il produit en jeu normal, et la rafale de 20 laisse passer un groupe qui
  se connecte ensemble ou une vague de reconnexions après un redémarrage. Une vague de bots d'un
  seul réseau passe ses 20 premières tentatives puis avance à une par seconde. **Valeurs non
  mesurées** : `observe` d'abord (13.5), `enforce` ensuite.
- **Modes** : `observe` journalise (`decision=observed`) et laisse entrer ; `enforce` refuse. Le
  `mode: observe` du module entier retient aussi cette borne. Une faute de frappe dans `mode`
  retombe sur `observe` (pas sur le strict : une limite non mesurée ne doit pas refuser par erreur).
  `enabled: false` la coupe. Valeurs absurdes (préfixe hors plage, `attempts` < 1…) : avertissement
  et défaut ; `burst` < `attempts` est relevé à `attempts`.
- **Jamais appliquée à** : une adresse non publique (loopback, privée, `100.64.0.0/10`,
  lien-local, ULA : jamais comptée), les `geyser-addresses` (Bedrock arrive de l'adresse de Geyser,
  le pré-login y reste différé comme avant) et `exemptions.networks`.
- **Exemptions de joueur** : elles exigent pseudo **et** UUID, or le pré-login ne connaît que le
  pseudo déclaré, que n'importe qui peut taper. Un pseudo que porte une exemption n'excuse donc
  **pas** la tentative (sinon un bot taperait le nom d'un staff) : au-delà de la limite, la décision
  est **différée au login**, comme pour les listes. Le joueur authentifié nommé par une exemption
  entre ; tout autre porteur de ce pseudo est refusé si le réseau est encore au-delà de sa limite.
  En `observe`, rien n'est différé. Le login ne dépense aucun jeton.
- **Une tentative refusée ne dépense rien** : sous une vague, les joueurs du réseau passent au
  rythme soutenu au lieu d'être affamés tant que dure la vague. Chaque pré-login est compté, même
  si les listes le refusent aussi (le décompte dit la vérité du trafic) ; le code affiché est alors
  celui de la liste.
- **Mémoire** : au plus `max-networks` réseaux suivis (20 000 par défaut, quelques Mo), le moins
  récemment vu est oublié d'abord et repart avec un seau plein : la pression sur la table ne peut
  que laisser passer, jamais refuser un réseau innocent. Un verrou unique protège la table (les
  événements Velocity sont concurrents) ; un seau inactif ne demande aucune minuterie.
- **Un refus de pré-login levé par un autre plugin** est rattrapé par le `PostLoginEvent` (filet
  existant), pas ré-appliqué au login : il n'y a pas d'état à rejouer sans re-dépenser un jeton.
- **Borne par ASN, désactivée par défaut** : `network-rate.group-hosting-by-asn: true` compte
  comme un seul réseau les adresses d'un système d'hébergement (`NetworkRateLimiter.Grouping
  .byHostingAsn`, clé `AS<numéro>`), pour qu'une vague louée chez un hébergeur ne passe pas sous la
  limite en se répartissant sur ses préfixes. Seuls les systèmes **refusés** (`asn.deny-asns`) ou
  **nommés par un mot-clé** (`asn.observe-keywords`) sont groupés : un opérateur résidentiel ou
  mobile est aussi un système, et un seul seau pour ses millions d'abonnés les affamerait tous.
- **Visible** : `/admission why <ip>` donne la dernière décision (ligne `basis=rate-network
  code=ADM-RATE-NET`), `/admission status` la configuration, le nombre de réseaux suivis et de
  tentatives au-delà de la limite depuis le démarrage.

## Exemptions datées et justifiées

Une exemption est un trou dans le bouclier : elle dit pourquoi elle existe et quand elle finit.

```yaml
exemptions:
  players:
    - name: Alice
      uuid: 00000000-0000-0000-0000-000000000001
      reason: "staff, accès de test"      # facultatif, journalisé
      expires: 2026-12-31                  # facultatif pour un joueur : dernier jour valide (UTC)
  networks:
    - network: 203.0.113.0/24
      reason: "bureau de l'agence"
      expires: 2026-12-31                  # OBLIGATOIRE pour un réseau
```

- Le lendemain de `expires`, l'entrée ne s'applique plus. Dans les 7 jours qui précèdent, le
  démarrage et `/admission status` la signalent. Une date illisible refuse l'entrée (ERROR : elle
  n'exempte personne).
- **Rupture de compatibilité** : l'ancienne forme d'un réseau, la simple chaîne CIDR, est désormais
  refusée (une adresse change de mains ; une exemption sans fin est une dette). Un joueur sans
  `expires` n'expire pas (forme d'avant les dates).

## ASN (13.2 : `asn`)

**Source** : [iptoasn.com](https://iptoasn.com), fichier `ip2asn-combined.tsv.gz`, domaine public
(licence **PDDL**). Aucune adresse de joueur ne sort : seul le fichier public est téléchargé, dans la
même tâche planifiée que les listes X4BNet et FireHOL, avec les mêmes garde-fous.

- **Rôle** : donner à chaque adresse son système autonome (numéro, nom, pays) pour trois usages :
  refuser des systèmes nommés, signaler des noms suspects, grouper la borne de débit.
- **Réglages** (`asn`) : `enabled: true`, `mode: observe`, `deny-asns: []`, `observe-keywords: []`,
  `url`, `max-download-megabytes: 32`, `max-rows: 2000000`. Par défaut **rien n'est refusé ni
  signalé** : la base sert d'abord à `/admission why` et au journal.
- **`deny-asns`** : numéros (`64496` ou `AS64496`). Refus `ADM-VPN-ASN` (texte réglable,
  `messages.asn`). `observe` journalise `decision=observed` et laisse entrer ; `enforce` refuse. Le
  `mode: observe` du module entier retient aussi ce refus. Les exemptions s'appliquent comme pour
  une liste.
- **`observe-keywords`** : mots cherchés (sans casse) dans le nom du système. Une correspondance
  est journalisée (`decision=admitted basis=asn-keyword`, agrégée par minute) et signalée au staff
  en ligne ; **elle ne refuse jamais**. Vide par défaut : à choisir après avoir lu `/admission why`
  sur de vrais hébergeurs.
- **Journal et `/admission why`** : `asn=<numéro>` et `as_name="<nom>"` (échappé : un nom d'AS est
  une donnée tierce) sur chaque ligne dont l'adresse a un système ; `/admission why` ajoute la ligne
  ASN, le pays et la raison. `/admission status` donne l'âge, le nombre de lignes et d'AS, et une
  éventuelle version en quarantaine.
- **Garde-fous** (ceux des listes, voir plus bas) : téléchargement borné (32 Mo, 2 M lignes, 256 Mio
  inflatés), échéance d'au moins 120 s, fichier temporaire propre à la tentative, version précédente
  gardée, rejet si le nombre de lignes varie de plus de 50 % ou si la couverture double, ligne de
  base embarquée à la première charge (`lists.embedded-baseline`). Une ligne illisible est comptée,
  jamais fatale ; l'AS 0 (non routé) est écarté ; un nom est nettoyé (caractères de contrôle et de
  direction retirés, 120 caractères au plus). La **quarantaine** (`lists.quarantine-hours`) est celle
  des listes, mais n'ouvre que si `deny-asns` n'est pas vide et qu'une version est déjà en service :
  sans refus possible, il n'y a rien à retenir.
- **Mémoire et vitesse** (fichier réel du 09/10/2026 : 722 982 lignes, 87 182 systèmes, 9,0 Mo gzip,
  45,8 Mo décompressé) : tableaux primitifs triés, plages contiguës du même AS fusionnées
  (405 248 plages IPv4 + 97 508 IPv6), recherche par dichotomie. **10,5 Mio de tableaux (~11,0 Mo
  comptés par `approximateBytes`), ~13,7 Mio de tas retenus** mesurés ; analyse ~1,5 s ; recherche
  ~170 ns en IPv4, ~110 ns en IPv6, sans allocation de la base à chaque connexion.
- **Borne par ASN** : voir `network-rate.group-hosting-by-asn` (désactivée par défaut).
- **Limite connue** : l'agrégation du journal par minute reste par /24 ou /64, pas par système.

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

Vérification anti-bot en limbo (Sonar, 13.3), débit Bedrock borné en amont de Geyser (13.4, autre
moitié). GeoLite2 est écarté (licence) : la base ASN vient d'iptoasn.com.

> **À valider au banc (13.5)** : que les valeurs par défaut absorbent une vague SoulFire de 50 à
> 200 bots sans toucher un Java et un Bedrock réels ; combien de pré-logins un joueur légitime
> produit réellement (si Sonar double le pré-login, une connexion coûte deux jetons) ; le comportement
> d'un `PreLoginEvent` refusé par ce module puis levé par un autre plugin.
