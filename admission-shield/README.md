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

- **Ligne** : un préfixe plus large que `/8` (IPv4) ou `/16` (IPv6) est ignoré (`0.0.0.0/0` compris).
- **Liste** : rejetée entière, la précédente valide reste en place et une ERROR est journalisée, si
  elle dépasse `lists.max-entries`, couvre plus de 20 % de l'espace IPv4, ou, face à la version en
  service, voit son nombre d'entrées varier de plus de 50 % ou sa couverture doubler. Pour accepter
  un changement légitime de cette ampleur : supprimer le fichier de la liste et redémarrer.
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

> **À valider au banc** : que log4j2 résolve `log4j2.xml` depuis le classpath dans la liste
> composite, et que la rotation et la suppression se déclenchent (avancer l'horloge ou réduire
> `IfLastModified` le temps de l'essai).

## Hors périmètre

GeoLite2 ASN (licence MaxMind à trancher), anti-bot (Sonar, 13.3), débit par /24 ou par ASN (13.4).
