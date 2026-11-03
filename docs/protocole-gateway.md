# Protocole app Dots ↔ gateway Hermes

Version 1, 2 novembre 2026.

## Architecture

```
Pixel (app Hermes) ──wss://<domaine>/dots/ws──▶ Caddy (TLS Let's Encrypt, :443)
                                                   │ reverse proxy
                                                   ▼
                                   127.0.0.1:8787  plugin « dots » (aiohttp)
                                                   │ BasePlatformAdapter
                                                   ▼
                                   Hermes gateway 0.22.2 (hermes-gateway.service)
```

- Le **plugin** vit dans `~/.hermes/plugins/dots/` (`plugin.yaml`, `__init__.py`, `adapter.py`). Il n'a aucun impact sur le code de Hermes et survit à un `hermes update`.
- Le serveur WebSocket n'écoute **que sur 127.0.0.1**. Seul Caddy est exposé, et il gère HTTPS.
- La configuration se fait par variables `DOTS_*` dans `~/.hermes/.env` :

| Variable | Rôle |
|---|---|
| `DOTS_TOKEN` | Jeton d'appairage (32 octets aléatoires), à saisir dans l'app |
| `DOTS_BIND` | Adresse d'écoute, `127.0.0.1:8787` par défaut |
| `DOTS_ALLOWED_USERS` | `owner` |
| `DOTS_HOME_CHANNEL` | `owner` (cible des messages proactifs et des tâches cron) |

## Connexion

- URL : `wss://<domaine>/dots/ws`. L'authentification passe par l'en-tête `Authorization: Bearer <DOTS_TOKEN>`. Un jeton invalide reçoit un HTTP 401 avant l'upgrade.
- Il n'y a qu'**une conversation** : `chat_id = user_id = "owner"`.
- Un ping WebSocket est envoyé toutes les 30 s. L'app se reconnecte avec un backoff de 1, 2, 5, 10 puis 30 s.

## Trames

Toutes les trames sont du JSON avec un champ `type`. Les identifiants de message sont générés par le serveur. `seq` est un compteur croissant de la conversation, qui sert à rattraper les messages manqués.

### App → gateway

| `type` | Champs | Effet |
|---|---|---|
| `hello` | `last_seq`, `foreground`, `push_token` (facultatif), `lang` (facultatif, `en` / `fr`) | `last_seq = 0` : la page la plus récente (120 événements) ; sinon ce qui a été manqué (600 max, au-delà la page récente avec `reset`) ; déclare la présence, le jeton FCM et la langue du téléphone (textes rédigés par le plugin : push, libellés d'outils, consignes des sous-dots ; anglais par défaut, mémorisée dans `~/.hermes/platforms/dots/app_language`) |
| `history_page` | `before_seq`, `limit` (200 max) | Page d'historique plus ancienne (défilement vers le haut) |
| `message` | `client_id`, `text` | Message de l'utilisateur, transmis à `handle_message` |
| `reaction` | `message_id`, `emoji` (`null` = retirer) | Émis vers les hooks via le handler de réactions |
| `approval` | `approval_id`, `choice` (`once`, `session`, `always`, `deny`) | `resolve_gateway_approval` |
| `clarify` | `clarify_id`, `response` | `resolve_gateway_clarify` |
| `voice` | `client_id`, `mime` (`audio/mp4`), `duration_ms`, `waveform[]` (0..1), `audio` (base64, 6 Mo max) | Vocal : stocké dans `~/.hermes/platforms/dots/voice/`, transmis à Hermes en `MessageType.VOICE` |
| `audio_get` | `id` | Demande l'audio d'un vocal de l'historique |
| `file` | `client_id`, `name`, `mime`, `caption`, `data` (base64, 20 Mo max) | Pièce jointe : copiée pour l'historique, mise en cache par `cache_media_bytes` et transmise à Hermes (PHOTO, VIDEO, AUDIO ou DOCUMENT) |
| `file_get` | `id` | Demande une pièce jointe de l'historique |
| `screen_watch` | `on`, `takeover` | Ouvre/ferme le flux en direct de l'écran ; `takeover` accélère le flux pendant la prise en main |
| `screen_input` | `action` (`click`, `scroll`, `type`, `key`), `x`, `y` (fractions de l'image), `dy`, `text`, `key` | Geste rejoué sur la page de l'agent via CDP (`Input.*`) |
| `takeover_done` | `id`, `note` | Rend la main à l'agent (débloque `browser_request_takeover`) |
| `activity_get` | — | Demande la vue Activité |
| `cron_action` | `id`, `action` (`pause`, `resume`, `run`) | Pilote une tâche planifiée, renvoie la vue Activité |
| `stop` | — | Interrompt le tour en cours (`/stop`, non écrit dans la conversation) |
| `presence` | `foreground` | L'app passe au premier plan / en arrière-plan (pilote les push) |
| `push_token` | `token` | Nouveau jeton FCM de l'appareil (4 gardés au plus, dans `push_tokens.json`) |

### Gateway → app

| `type` | Champs | Origine côté adaptateur |
|---|---|---|
| `welcome` | `agent` (nom), `seq`, `history[]`, `reset`, `has_more` | Réponse à `hello` ; `reset` : l'app remplace son fil au lieu de le compléter |
| `history_page` | `before_seq`, `events[]`, `has_more` | Réponse à `history_page` |
| `ack` | `client_id`, `id`, `seq`, `ts` | Message utilisateur enregistré |
| `read` | `id` | `on_processing_start` |
| `activity` | `state` : `thinking`, `typing` ou `idle` | `send_typing` / `stop_typing` / streaming |
| `message` | `id`, `seq`, `ts`, `text`, `final`, `turn` | `send` (y compris messages proactifs et cron) ; `turn` = id du message utilisateur traité, `null` hors tour |
| `edit` | `id`, `text`, `final` | `edit_message` (réponse en streaming) |
| `reaction` | `message_id`, `emoji` | `_add_reaction` / `_remove_reaction` (issue du traitement) |
| `approval` | `approval_id`, `seq`, `title`, `command`, `description`, `actions[]` | `_send_exec_approval_prompt` |
| `approval_resolved` | `approval_id`, `choice` | Après résolution |
| `clarify` | `clarify_id`, `seq`, `question`, `choices[]` | `send_clarify` |
| `transcript` | `id` (vocal), `text` | Écho STT de Hermes (`🎙️ "…"`), intercepté par `send` et rattaché au vocal |
| `audio` | `id`, `mime`, `data` (base64) ou `error` | Réponse à `audio_get` |
| `file_data` | `id`, `mime`, `name`, `data` (base64) ou `error` | Réponse à `file_get` |
| `screen_frame` | `data` (JPEG base64), `url`, `title`, `width`, `height`, `live`, `ts` | Image de l'écran : une vignette après chaque outil `browser_*`, ~3 images/s tant qu'une app regarde |
| `screen_idle` | — | Aucun navigateur ouvert pendant le flux |
| `activity_snapshot` | `crons[]`, `turn`, `state`, `browser` | Réponse à `activity_get` / `cron_action` |
| `tool` | `id` (tool_call_id), `turn`, `name`, `kind`, `label`, `detail`, `state` (`running`), `ts` | Hook plugin `pre_tool_call`, filtré sur `HERMES_SESSION_PLATFORM == "dots"` |
| `tool_update` | `id`, `state` (`done` / `error`), `duration_ms` | Hook `post_tool_call` ; l'historique garde l'état final dans l'événement `tool` |
| `error` | `code`, `message` | Trame invalide, jeton refusé… |

## Messages hors ligne

L'adaptateur conserve toute la conversation (messages, cartes, outils, résolutions) dans une base SQLite, `~/.hermes/platforms/dots/history.db` : un ajout ou une mise à jour n'écrit qu'une ligne, et l'app remonte le fil page par page. L'ancien `history.jsonl` a été importé une fois, puis gardé sous le nom `history.jsonl.migrated`. Quand l'app est déconnectée, `send` réussit quand même : le message est stocké, puis livré au prochain `hello`. L'agent peut donc écrire en premier (cron, recherche proactive) sans que rien ne se perde.

## Correspondance avec l'interface

| Interface (phase 1) | Protocole |
|---|---|
| Statut « Réfléchit… / Écrit… » et humeur de la mascotte | `activity` |
| Accusés « Envoi… → Envoyé → Lu » | envoi local → `ack` → `read` |
| Carte d'approbation (Approuver / Refuser) | `approval` / `approval_resolved` ; Approuver = `once`, Refuser = `deny` |
| Réactions emoji | `reaction` (dans les deux sens) |
| Carte de tâche | Pas d'équivalent natif dans Hermes 0.22 : elle reste disponible pour une version ultérieure |
| Sur-messages d'outils (capsule au-dessus de la réponse, dépliable en frise) | `tool` / `tool_update`, regroupés par `turn` et placés avant la réponse de l'agent qui les suit |
| Notification push | FCM, voir ci-dessous |

## Outils et skills

Hermes n'expose pas les appels d'outils aux adaptateurs de plateforme, sauf sous forme de lignes de texte (`tool_progress`). Le plugin s'abonne donc aux hooks globaux `pre_tool_call` / `post_tool_call`, qui donnent le nom de l'outil, ses arguments et un `tool_call_id`. Il ne garde que les appels des tours Dots (variable de contexte `HERMES_SESSION_PLATFORM`), traduit le nom en libellé (dans la langue de l'app, voir `hello`) et en famille d'icône (`kind` : `skill`, `terminal`, `web`, `memory`…), et calcule le détail avec `agent.display.build_tool_preview`. La lecture d'une skill, par exemple, arrive en `skill_view` avec le nom de la skill en détail.

Les lignes de progression texte sont coupées pour cette plateforme (`display.platforms.dots.tool_progress: off` dans `config.yaml`) : sans cela, chaque appel deviendrait une bulle « ⏰ Scheduling list ».

## Notifications push (FCM)

- Projet Firebase `hermes-perso-513222`, app Android `app.dotshermes` (`app/google-services.json`).
- Compte de service `dots-fcm-sender@hermes-perso-513222.iam.gserviceaccount.com`, rôle `roles/firebasecloudmessaging.admin` uniquement. Clé sur le serveur dans `~/.hermes/platforms/dots/fcm-service-account.json` (mode 600), chemin modifiable avec `DOTS_FCM_CREDENTIALS`.
- Le plugin pousse quand aucune app n'est connectée **ou** qu'elle a déclaré `foreground: false` : pour chaque `message` de l'agent, chaque `approval` et chaque `clarify`.
- Messages *data* uniquement, priorité haute (`kind`, `id`, `title`, `body`) : l'app compose elle-même une notification de conversation (MessagingStyle) avec une réponse rapide. Celle-ci passe par la socket ouverte si l'app tourne, sinon par une connexion éclair (`hello` sans rattrapage, `message`, `ack`).
- Les jetons rejetés par FCM (`UNREGISTERED`) sont retirés automatiquement.

## Messages vocaux

- L'app enregistre en AAC mono 16 kHz, 32 kb/s (conteneur MP4), avec une enveloppe de 40 barres pour dessiner l'onde sans télécharger l'audio.
- Le plugin crée un `MessageEvent` de type `VOICE` dont `media_urls` pointe vers le fichier : c'est le pipeline STT de Hermes qui transcrit, avant que l'agent ne lise le message.
- Le STT est **local** (faster-whisper, extra `stt-whisper` installé avec `hermes pm install --extra stt-whisper`, modèle `base`, `stt.language: fr`). Aucun audio ne quitte le serveur.
- Hermes renvoie la transcription en écho (`🎙️ "…"`) ; le plugin l'intercepte et l'affiche sous la bulle vocale au lieu d'en faire un message.
- L'historique garde l'onde, la durée et la transcription ; l'app télécharge l'audio (`audio_get`) seulement quand on l'écoute depuis un autre appareil ou après réinstallation.

## Plusieurs apps connectées

Le plugin accepte plusieurs WebSockets en même temps (téléphone, réponse rapide depuis une notification, autre appareil) : les événements de conversation partent vers toutes, les réponses aux demandes (`welcome`, `audio`, `file_data`, `activity_snapshot`) vers celle qui a demandé. Chacune déclare sa présence ; le push part seulement si aucune n'est au premier plan.

## Pièces jointes

- **App → Hermes** : le plugin garde une copie dans `~/.hermes/platforms/dots/files/` (pour la réafficher) et passe le fichier à Hermes avec `cache_media_bytes`, comme les adaptateurs officiels : une image part en `PHOTO` (outil `vision_analyze`), un PDF ou un texte en `DOCUMENT`. Le texte tapé avant l'envoi sert de légende.
- **Hermes → app** : `send_image_file`, `send_document`, `send_video`, `send_voice` (synthèse vocale, affichée comme un vocal) et `send_image` (URL distante téléchargée) produisent un événement `message` de `kind: file` ou `voice`.

## Écran du navigateur

Le plugin passe par l'accès CDP que Hermes réserve aux plugins (`SUPERVISOR_REGISTRY.capture(task_id)`), avec le `task_id` des outils `browser_*` du tour Dots. Le superviseur est attaché au premier onglet de Chromium, souvent vide : le plugin cherche la page réellement consultée (`Target.getTargets`, dernière page web) et s'y attache. Les images sont des `Page.captureScreenshot` en JPEG réduit à 60 % ; aucune capture n'a lieu quand personne ne regarde, à part une vignette après chaque action.

## Vue Activité

- Tâches planifiées lues et pilotées avec l'API cron de Hermes (`list_jobs`, `pause_job`, `resume_job`, `trigger_job`).
- Tour en cours, ses outils et le bouton « Arrêter » (`stop`).
- Derniers échanges, reconstitués par l'app à partir des messages et des outils (`turn`).

## Prise en main du navigateur

Hermes ferme son navigateur à la fin de chaque tour. Une prise en main n'a donc de sens que si l'agent attend pendant son tour. Le plugin lui fournit un outil pour ça, `browser_request_takeover(reason)`, déclaré dans le jeu d'outils `dots` (Hermes l'ajoute automatiquement aux outils de cette plateforme) :

1. L'agent laisse le navigateur sur la page qui demande un humain (connexion, code 2FA, captcha…) et appelle l'outil. L'appel bloque son tour (15 min au plus), donc le navigateur reste ouvert.
2. Le plugin publie un message `kind: takeover` (carte « Besoin de toi sur son navigateur ») et une notification push.
3. Le bouton « Prendre la main » ouvre l'écran en direct en mode pilotage : tap = clic, glisser = défiler, champ de saisie = `Input.insertText`, touches Entrée, Retour arrière, Tab et Échap.
4. « Rendre la main » envoie `takeover_done` : l'outil répond à l'agent, qui reprend avec la page dans l'état laissé par l'utilisateur.

Ce que l'utilisateur tape va directement sur la page. Rien ne passe par la conversation, ni par l'historique, ni par le modèle.

## Notifications de feu vert

Pour une demande d'approbation, l'app affiche une notification à part, avec les boutons « Refuser » et « Approuver ». La décision part par la socket de l'app si elle est connectée, sinon par une connexion éclair qui attend `approval_resolved`. Si la demande est tranchée ailleurs (dans l'app, sur un autre appareil), la notification disparaît.

## Sous-dots

Le dot principal est la conversation `owner`. Chaque sous-dot a sa propre conversation, `dot-<id>`, donc sa propre session Hermes : Hermes isole les sessions privées par `chat_id`, et le contexte n'est pas partagé (vérifié : un mot-code confié à un dot reste inconnu d'un autre). Outils, mémoire et skills restent communs.

| Type | Rôle | Fin de vie |
|---|---|---|
| `temporary` | Une mission ; s'il n'a pas de nom, il prend les premiers mots de sa mission | Fermé une fois la mission faite (l'agent le signale) |
| `permanent` | Un spécialiste durable avec son nom, sa mascotte et sa couleur | Supprimé seulement à la demande |

- **Registre** : `~/.hermes/platforms/dots/dots.json` (`id`, `name`, `kind`, `mission`, `species`, `palette`, `accessory`, `created_at`). Il est sur le serveur, donc commun à tous les appareils.
- **Identité** : à chaque message, la mission ou la spécialisation part dans `MessageEvent.channel_prompt`, la consigne par conversation de Hermes. Elle est appliquée à chaque appel et jamais écrite dans le transcript : modifier un dot change son comportement dès le message suivant.
- **Historique** : une seule table, avec une colonne `chat`. Les pages (`history_page`) se demandent par conversation. Fermer un dot efface ses lignes et interrompt son tour s'il travaillait.
- **Routage** : les messages de l'app portent `chat` (par défaut `owner`), et les événements du serveur aussi. L'activité est suivie par conversation (`activity` + `chat`, `welcome.activities`). Les outils retrouvent leur conversation grâce à `HERMES_SESSION_CHAT_ID`, et les approbations grâce à leur `session_key`. Les crons et les cibles inconnues arrivent dans le dot principal.

| App → gateway | Champs |
|---|---|
| `dot_create` | `ref`, `dot` {name, kind, mission, species, palette, accessory} |
| `dot_update` | `id`, `dot` (mêmes champs) |
| `dot_delete` | `id` |

| Gateway → app | Champs |
|---|---|
| `dots` | `dots[]` (avec `last_ts`) : diffusé après chaque changement, et inclus dans `welcome` |
| `dot_created` | `id`, `ref` : l'app qui l'a créé ouvre sa conversation |
| `dot_deleted` | `id` |

Push : `chat` et `title` (le nom du sous-dot ; vide pour le principal) dans les données. Toutes les notifications partagent un fil, chaque dot sous son nom. « Répondre » et l'ouverture de l'app mènent à la conversation du dernier message.
