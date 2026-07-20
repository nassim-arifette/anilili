# Suivi des bugs et correctifs

Dernière revue statique : 20 juillet 2026.

Ce document est la liste de suivi centrale de l'audit : les cases cochées correspondent à un
correctif implémenté et commité sur une branche locale dédiée; elles ne signifient pas encore que
la branche a été fusionnée dans `main`. Les cases non cochées restent à traiter.

Les problèmes ont des chemins d'exécution concrets dans le code, mais n'ont pas pu être validés
sur appareil : aucun SDK Android n'était installé dans l'environnement d'audit.

**État actuel :** 5 correctifs implémentés, 14 bugs de code encore ouverts et 2 corrections de
documentation à faire.

## Correctifs implémentés sur des branches locales

Chaque branche contient un seul commit basé directement sur `main`.

- [x] Authentifier les callbacks des bridges WebView — `fix/webview-bridge-capabilities`
  (`c0a4ea0`).
- [x] Empêcher une ancienne requête MAL d'écraser la session courante —
  `fix/mal-auth-session-race` (`dbb2f4d`).
- [x] Ne remplacer les alarmes qu'avec un snapshot complet des sorties —
  `fix/release-sync-atomic-snapshot` (`c577e21`).
- [x] Rendre le cycle de vie des timers WebView local à chaque lecteur —
  `fix/webview-local-lifecycle` (`e99549d`).
- [x] Empêcher le résolveur Flixcloud caché de chevaucher le lecteur actif —
  `fix/flixcloud-player-overlap` (`90c97da`).

## TODO des bugs ouverts

- [ ] KI-001 — Recréer les WebView après la perte de leur renderer.
- [ ] KI-002 — Isoler les callbacks et états de chaque navigation embed.
- [ ] KI-003 — Empêcher les résolutions annulées de republier un ancien état.
- [ ] KI-004 — Invalider les requêtes de profil lors du logout/changement de compte.
- [ ] KI-005 — Protéger le token AniList contre les écritures concurrentes.
- [ ] KI-006 — Empêcher le réarmement d'une alarme pendant sa livraison.
- [ ] KI-007 — Faire pointer l'updater et les liens vers le fork.
- [ ] KI-008 — Rendre le cycle de vie des requêtes Pipe fiable et annulable.
- [ ] KI-009 — Donner une identité stable et sans collision aux rappels.
- [ ] KI-010 — Ne marquer comme livrées que les notifications réellement affichées.
- [ ] KI-011 — Synchroniser le cache mémoire après `putBatch()`.
- [ ] KI-012 — Sérialiser les écritures de réglages et de watchlist distante.
- [ ] KI-013 — Attendre le chargement des préférences dans le worker de sorties.
- [ ] KI-014 — Implémenter réellement l'entrée en Picture-in-Picture.
- [ ] DOC-001 — Corriger la version du SDK documentée.
- [ ] DOC-002 — Corriger le nom et le chemin de l'APK documentés.

## Intégration et validation à faire

- [ ] Intégrer les cinq branches de correction dans la branche cible choisie.
- [ ] Résoudre explicitement les chevauchements entre les branches qui modifient les mêmes
  fichiers WebView.
- [ ] Compiler et lancer les tests unitaires avec un SDK Android API 36 configuré.
- [ ] Valider sur appareil les changements de lecteur, les pertes de renderer, les alarmes et les
  changements de compte.

## Priorité haute

### KI-001 — Les WebView mortes ne sont pas recréées

- **Impact :** lecteur noir permanent, résolveur Pipe indisponible jusqu'au redémarrage de
  l'activité, ou arrêt du processus pendant la connexion OAuth.
- **Preuve :** `EmbedWebView.onRenderProcessGone()` et `PipeBridge.onRenderProcessGone()`
  retournent `true`, mais leurs `AndroidView` conservent l'instance dont le renderer est mort.
  `LoginWebView` ne traite pas du tout cette callback. Les chemins de mise à jour/libération
  rappellent ensuite des méthodes sur ces instances invalides.
- **Fichiers :** `ui/watch/EmbedWebView.kt`, `data/remote/PipeBridge.kt`,
  `ui/PipeWebView.kt`, `ui/profile/LoginWebView.kt`.
- **Branche suggérée :** `fix/webview-renderer-recreation`.

### KI-002 — Les callbacks d'un ancien embed modifient le nouvel épisode

- **Impact :** progression enregistrée sur le mauvais épisode, fausse erreur de lecture,
  contrôles/temps qui cessent de se mettre à jour après un changement d'épisode ou de qualité.
- **Preuve :** le même `AndroidView` est réutilisé entre les URLs. Le `WebProgressBridge` est
  construit une seule fois et capture les premiers états créés par `remember(url)`, tandis que
  les ticks et erreurs tardifs utilisent les callbacks et l'URL courants. Un événement de A peut
  donc être perdu ou attribué à B.
- **Fichiers :** `ui/watch/EmbedWebView.kt`, `ui/watch/WatchScreen.kt`.
- **Branche suggérée :** `fix/embed-navigation-generation`.

### KI-003 — Une résolution annulée peut republier un ancien état de lecture

- **Impact :** changement rapide d'épisode/serveur qui revient à l'ancien épisode, affiche
  « aucune source » à tort ou mélange deux catalogues.
- **Preuve :** `MiruroRepository.resolveSources()` enveloppe des appels suspendus dans
  `runCatching` sans relancer `CancellationException`. `WatchViewModel.launchAnivexaMerge()` fait
  de même puis écrit encore dans `spine`, `mergedIncludesAnivexa` et l'état global sans vérifier
  l'identifiant ou une génération de requête.
- **Fichiers :** `data/MiruroRepository.kt`, `ui/watch/WatchViewModel.kt`.
- **Branche suggérée :** `fix/playback-resolution-generation`.

### KI-004 — Une requête de profil peut terminer après la déconnexion

- **Impact :** le profil privé et la watchlist distante peuvent réapparaître après logout ou
  après un changement de compte.
- **Preuve :** `ProfileViewModel.loadIfLoggedIn()` ne conserve ni n'annule son `Job` et ne vérifie
  pas la session avant `hydrateWatchlistFromAniList()` ou l'écriture de `_profile`. `logout()` met
  seulement `_profile` à `null`.
- **Fichier :** `ui/profile/ProfileViewModel.kt`.
- **Branche suggérée :** `fix/profile-session-generation`.

### KI-005 — Le contrôle d'expiration AniList peut effacer un nouveau token

- **Impact :** déconnexion immédiate et intermittente juste après une nouvelle authentification.
- **Preuve :** `AuthManager.current()` lit un ancien token expiré puis appelle `clearToken()` sans
  verrou ni génération. Un `setToken()` concurrent entre ces deux opérations est alors effacé.
- **Fichier :** `data/auth/AuthManager.kt`.
- **Branche suggérée :** `fix/anilist-token-generation`.

### KI-006 — Une alarme peut être réarmée pendant sa propre livraison

- **Impact :** deux notifications et deux sons pour la même sortie quand l'alarme démarre un
  processus froid.
- **Preuve :** `MiruroApp.onCreate()` initialise les gestionnaires avant l'exécution du receiver.
  Leur `init()` recharge et reprogramme l'alarme encore persistée à `now + 1s`; le receiver retire
  ensuite seulement l'enregistrement, sans annuler l'alarme qui vient d'être recréée. Le scénario
  concerne les rappels manuels et automatiques.
- **Fichiers :** `MiruroApp.kt`, `data/reminder/ReminderManager.kt`,
  `data/reminder/AutomaticReleaseManager.kt`.
- **Branche suggérée :** `fix/reminder-cold-start-delivery`.

### KI-007 — L'updater du fork télécharge encore les releases upstream

- **Impact :** proposition d'un APK qui ne correspond pas au fork et qui peut être refusé par
  Android à cause d'une signature différente.
- **Preuve :** l'origine Git est `nassim-arifette/anilili`, mais `RELEASES_LATEST` et le lien des
  réglages pointent toujours vers `kompoti121/anilili`.
- **Fichiers :** `data/update/UpdateManager.kt`, `ui/settings/SettingsScreen.kt`.
- **Branche suggérée :** `fix/fork-update-source`.

## Priorité moyenne

### KI-008 — Le cycle de vie des requêtes Pipe n'est pas fiable

- **Impact :** attente d'environ 25 secondes même avec un timeout court, requêtes orphelines,
  faux état « prêt » après recréation du WebView.
- **Preuve :** `PipeBridge.fetch()` ignore la valeur `false`/`null` de l'attente `ready`, puis
  démarre une seconde attente. Une annulation ne retire pas systématiquement l'entrée de
  `pending`. Enfin, la callback différée de `onPageFinished()` n'est liée ni à une instance ni à
  une génération et peut compléter le nouveau `ready` depuis l'ancien WebView.
- **Fichier :** `data/remote/PipeBridge.kt`.
- **Branche suggérée :** `fix/pipe-request-lifecycle`.

### KI-009 — Des rappels distincts partagent la même identité Android

- **Impact :** un rappel peut remplacer ou annuler celui d'un autre anime.
- **Preuve :** `requestCode = 31 * mediaId + episode` n'est pas injectif : `(100, 32)` et
  `(101, 1)` donnent tous deux `3132`. De plus, l'identifiant fonctionnel contient `airingAt` :
  si l'horaire change, l'ancien rappel devient invisible depuis la nouvelle ligne et reste armé.
- **Fichier :** `data/reminder/ReminderManager.kt`.
- **Branche suggérée :** `fix/reminder-stable-identity`.

### KI-010 — Les notifications AniList au-delà de la huitième sont perdues

- **Impact :** lorsqu'il existe plus de huit nouvelles notifications non lues, les suivantes ne
  sont jamais affichées lors d'une synchronisation ultérieure.
- **Preuve :** `notifyUnread()` affiche seulement `freshUnread.take(8)`, puis enregistre pourtant
  les identifiants de toute la liste `freshUnread` comme déjà livrés.
- **Fichier :** `data/reminder/AniListNotificationPushManager.kt`.
- **Branche suggérée :** `fix/notification-delivery-accounting`.

### KI-011 — `putBatch()` laisse l'ancienne valeur dans le cache mémoire

- **Impact :** les chaînes de saisons expirées sont recalculées à chaque consultation pendant le
  même processus, malgré leur réécriture correcte sur disque.
- **Preuve :** `AppCache.putBatch()` écrit uniquement via `dao.putAll()` sans remplacer ou
  invalider les entrées correspondantes de `memory`; `read()` préfère toujours la mémoire.
- **Fichiers :** `data/cache/AppCache.kt`, `data/MiruroRepository.kt` (`animeSeries`).
- **Branche suggérée :** `fix/cache-batch-memory-coherence`.

### KI-012 — Des écritures « fire-and-forget » peuvent finir dans le mauvais ordre

- **Impact :** après des changements rapides, le réglage restauré au prochain lancement ou
  l'état de la watchlist distante peut être l'inverse du dernier choix visible.
- **Preuve :** les setters de `SettingsStore` lancent des `store.edit` indépendants sur
  `Dispatchers.IO`; l'ordre d'arrivée n'est pas garanti. `LibraryStore.toggleWatchlist()` lance
  également un job distant par bascule : le mutex sérialise l'exécution mais ne garantit pas que
  le job de l'ancien état acquière le verrou en premier.
- **Fichiers :** `data/settings/SettingsStore.kt`, `data/library/LibraryStore.kt`.
- **Branche suggérée :** `fix/serialized-state-writes`.

### KI-013 — Le worker de sorties lit les préférences avant leur chargement

- **Impact :** au démarrage, un utilisateur ayant désactivé les notifications peut brièvement
  faire exécuter une synchronisation avec la valeur par défaut `true` et recréer des alarmes.
- **Preuve :** `SettingsStore.init()` charge DataStore dans une coroutine, puis
  `ReleaseSyncScheduler.schedule()` lance immédiatement le worker. `ReleaseSyncWorker.doWork()`
  lit `releaseNotifications.value` sans appeler `SettingsStore.awaitLoaded()`.
- **Fichiers :** `MiruroApp.kt`, `data/settings/SettingsStore.kt`,
  `data/reminder/AutomaticReleaseManager.kt`.
- **Branche suggérée :** `fix/release-worker-settings-barrier`.

### KI-014 — Le Picture-in-Picture est déclaré mais ne peut pas démarrer

- **Impact :** quitter l'application met la lecture en pause au lieu d'ouvrir le mini-lecteur.
- **Preuve :** le manifeste active `supportsPictureInPicture` et l'activité observe le mode PiP,
  mais aucun appel à `enterPictureInPictureMode`, aucune configuration de paramètres et aucun
  auto-enter Android 12 ne sont présents.
- **Fichiers :** `AndroidManifest.xml`, `MainActivity.kt`.
- **Branche suggérée :** `fix/picture-in-picture-entry`.

## Documentation de build

- **DOC-001 — SDK requis :** le README indique API 35 alors que
  `compileSdk = 36`.
- **DOC-002 — Sortie APK :** le README annonce `app-debug.apk`, mais Gradle
  renomme toutes les sorties en `anilili.apk`.
