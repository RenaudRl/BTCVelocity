# btc-arrival-router

Plugin Velocity qui répond aux **deux seuls moments** où personne d'autre ne peut parler : un
joueur qui vient de se connecter, et un joueur que son backend vient d'éjecter.

## Le problème qu'il règle

Velocity valide la liste `try` **au démarrage** contre les serveurs écrits dans `velocity.toml`,
et refuse de démarrer si un nom n'y figure pas :

```
Fallback server BtcCore0-2 is not registered in your configuration!
Velocity will not start up
```

Un service CloudNet est enregistré longtemps après ce contrôle, et son port vient du nœud : il ne
peut donc **jamais** figurer dans `try`. Sans ce plugin, un joueur atteint le proxy et n'a nulle
part où aller, quel que soit le nombre de backends qui tournent.

## Ce qu'il fait, et ce qu'il ne fait pas

| | |
|---|---|
| Choisit la destination d'arrivée | uniquement si `try` n'a rien nommé — une consigne explicite de l'exploitant prime |
| Renvoie ailleurs un joueur éjecté | sauf si un autre greffon a déjà décidé d'une redirection |
| Choisit le **moins peuplé** | égalité tranchée par le nom, pour que le résultat soit reproductible et non seulement plausible |
| N'interroge **jamais** le nœud | il lit la liste des serveurs déjà enregistrés, rien d'autre |
| Ne connaît pas CloudNet | aucune dépendance : il marche aussi sur des serveurs déclarés en configuration |
| Ne porte aucun transfert métier | une intention de transfert voyage sur `btc:bridge` v2, pas ici |

La règle du chantier tient : **l'orchestrateur ne décide jamais d'une destination de joueur.** Le
nœud déclare ce qui existe ; la décision prise ici est celle du proxy, à partir d'une politique
déclarée à côté du proxy.

Ce n'est pas le bridge CloudNet qui rentre par la fenêtre. Le bridge interrogeait le nœud à chaque
login de façon bloquante, possédait `/hub`, et aucune option ne le désactivait.

## Configuration

Une seule variable d'environnement, `BTC_ARRIVAL_SERVERS` : des motifs séparés par des virgules,
chacun étant un nom exact ou un préfixe terminé par `*`.

```
BTC_ARRIVAL_SERVERS=BtcCore0-*
```

CloudNet nomme un service `<tâche>-<n>`, donc `BtcCore0-*` veut dire « n'importe quel service de
cette tâche » — exprimé sans dépendre de CloudNet.

**Pourquoi une variable et pas un fichier** : le répertoire d'un service CloudNet est recréé depuis
son modèle à chaque démarrage. Un fichier écrit là est aussi volatil que la mémoire ; la variable
de la tâche, elle, survit.

**Variable absente : le plugin est inerte** et le dit au démarrage. Il ne route rien « par défaut
vers ce qui traîne ».

## Garde-boucle

Ce à quoi ce plugin a déjà envoyé un joueur n'est plus proposé à ce joueur pour la durée de sa
session sur le proxy. Pas de minuterie, pas de compteur : une chaîne de redirections est bornée
par le nombre de destinations et se termine sur le « no available servers » de Velocity, jamais en
cycle. Un backend qui éjecte à vue ne peut pas être offert deux fois.

Mesuré au banc le 15/09 : un joueur a été redirigé **quatre secondes avant** que le registre ne
désenregistre le backend arrêté. C'est le garde-boucle qui a fait ce travail-là, pas le registre.

## Preuve au banc (15/09/2026, pool CloudNet à 2 backends)

| essai | politique | résultat |
|---|---|---|
| témoin négatif | absente | le joueur entre et **repart** : `There are no available servers to connect you to` |
| le test | `BtcCore0-*` | `CnBot_001 arrives on BtcCore0-2`, puis `CnBot_002 arrives on BtcCore0-3` — le moins peuplé |
| le repli | `BtcCore0-*` | backend arrêté sous les pieds du joueur → `redirected to BtcCore0-3`, 2/2 bots toujours en ligne |

Le témoin négatif est ce qui rend le test lisible : sans lui, un joueur qui arrive ne prouve pas
que c'est ce plugin qui l'a placé.

## Limite connue

Le comptage est celui que **ce** proxy voit. Avec plusieurs proxies devant les mêmes backends,
chacun équilibre sur sa propre vue — ce qui répartit les joueurs sans qu'aucun n'interroge le
nœud, mais ne constitue pas une vérité réseau.
