package com.filestech.sms

import android.app.Application
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.ContactsContract
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import androidx.work.WorkManager
import com.filestech.sms.core.logging.LineNumberDebugTree
import com.filestech.sms.core.logging.NoOpReleaseTree
import com.filestech.sms.crashlog.CrashLogHandler
import com.filestech.sms.data.blocking.BlockedNumbersImporter
import com.filestech.sms.data.contacts.ContactsReader
import com.filestech.sms.data.local.datastore.SettingsRepository
import com.filestech.sms.data.local.db.dao.ConversationDao
import com.filestech.sms.data.sync.TelephonySyncManager
import com.filestech.sms.di.ApplicationScope
import com.filestech.sms.domain.safetycall.SafetyCallNotice
import com.filestech.sms.domain.settings.DeviceMode
import com.filestech.sms.ownnumber.OwnNumberAutoFill
import com.filestech.sms.security.AppLockManager
import com.filestech.sms.security.AutoLockObserver
import com.filestech.sms.sync.LinkedDeviceStore
import com.filestech.sms.sync.SyncServer
import com.filestech.sms.sync.SyncService
import com.filestech.sms.sync.client.ClientSyncService
import com.filestech.sms.system.notifications.NotificationChannelInitializer
import com.filestech.sms.system.scheduler.SafetyCallAlarmScheduler
import com.filestech.sms.system.scheduler.SafetyCallWorker
import com.filestech.sms.system.scheduler.TelephonySyncWorker
import com.filestech.sms.system.service.KeepAliveService
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

@HiltAndroidApp
class MainApplication : Application(), Configuration.Provider {

    @Inject lateinit var workerFactory: HiltWorkerFactory

    @Inject lateinit var notificationChannelInitializer: NotificationChannelInitializer

    @Inject lateinit var autoLockObserver: AutoLockObserver

    @Inject lateinit var appLock: AppLockManager

    @Inject lateinit var telephonySyncManagerLazy: dagger.Lazy<TelephonySyncManager>

    @Inject lateinit var blockedNumbersImporterLazy: dagger.Lazy<BlockedNumbersImporter>

    @Inject lateinit var settingsRepository: SettingsRepository

    /** Slice 1 — the linked-device store; its server toggle drives [SyncService]. */
    @Inject lateinit var linkedDeviceStore: LinkedDeviceStore

    /**
     * Slice 5 — the contacts cache, invalidated when the provider
     * changes. `dagger.Lazy`, like the other lazies here: resolving
     * it is cheap, but nothing needs it until the first provider
     * change. (Also `Lazy` so the eager-injection guard stays
     * untouched — see `EagerInjectionGuardTest`.)
     */
    @Inject lateinit var contactsReaderLazy: dagger.Lazy<ContactsReader>

    /**
     * Slice 5 — the LAN server, notified of contact changes for the
     * live delta. `dagger.Lazy` (the established
     * `telephonySyncManagerLazy` pattern): a contact edit must never
     * build the server graph when the server is off.
     */
    @Inject lateinit var syncServerLazy: dagger.Lazy<SyncServer>

    /**
     * v1.28.12 — le rôle d'application SMS par défaut, relu à chaque réconciliation du Safety
     * call. Il n'a pas de flux : c'est un appel Binder synchrone. Le `combine` ci-dessous se
     * rejoue à chaque écriture de configuration — et le worker en écrit une à chaque tick
     * horaire — donc la perte du rôle est vue au plus tard une heure après, et immédiatement
     * au retour au premier plan par [MainActivity.republishSafetyCallNotice].
     */
    @Inject lateinit var defaultSmsAppChecker: com.filestech.sms.domain.sender.DefaultSmsAppChecker

    /** v1.12.0 — observed dynamically + posted/cancelled by `appScope` loop. */
    @Inject lateinit var emergencyShortcutNotifier: com.filestech.sms.system.notifications.EmergencyShortcutNotifier

    /**
     * v1.27.2 — porteur de la notification de séquence du Safety call. Injecté ici pour pouvoir la
     * retirer **immédiatement** à l'entrée en mode leurre, sans attendre le prochain réveil du
     * worker.
     */
    /**
     * ⚠️ v1.27.2 — `dagger.Lazy`, comme les trois autres injections de cette classe.
     *
     * `EagerInjectionGuardTest` a rattrape l'oubli : injecte en dur, ce notificateur etait
     * construit au DEMARRAGE, sur le fil principal, alors qu'il ne sert qu'a la premiere emission
     * du reconciliateur. C'est exactement le cout de demarrage a froid que ce depot s'efforce
     * d'eviter partout ailleurs — et il a ecrit un test de garde pour ca.
     */
    @Inject
    lateinit var safetyCallWarningNotifierLazy:
        dagger.Lazy<com.filestech.sms.system.notifications.SafetyCallWarningNotifier>

    /**
     * v1.8.0 (post-audit fix unread badges) — utilisé une fois au cold-start pour recalculer les
     * compteurs `conversations.unread_count` après l'import blocklist (cf.
     * [ConversationDao.recomputeAllUnreadCounts]). Les migrations one-shot qui utilisaient aussi
     * `MessageDao` / `AttachmentDao` / `ConversationMirror` vivent désormais dans [StartupMigrations].
     */
    @Inject lateinit var conversationDaoLazy: dagger.Lazy<ConversationDao>

    /** v1.24.0 — migrations one-shot cold-start qui ouvrent la base, regroupées et gardées. */
    @Inject lateinit var startupMigrations: com.filestech.sms.system.startup.StartupMigrations

    /**
     * Group-send own-number fix — fills "My phone number" from the
     * SIM once at startup when the setting is blank. `dagger.Lazy`
     * (the established pattern here): the fill runs on IO, never on
     * the main thread, and never blocks the cold start.
     */
    @Inject lateinit var ownNumberAutoFillLazy: dagger.Lazy<OwnNumberAutoFill>

    @Inject @ApplicationScope lateinit var appScope: CoroutineScope

    /**
     * Audit K-3 / C-5 (v1.14.8) — Dispatcher IO injecté. Avant : `Dispatchers.IO` hardcodé
     * dans la migration v1.8.0 et v1.14.7. Cohérence avec le pattern projet (toutes les
     * couches use `@IoDispatcher` injecté pour permettre le test ET centraliser la politique).
     */
    @Inject @com.filestech.sms.di.IoDispatcher lateinit var ioDispatcher: kotlinx.coroutines.CoroutineDispatcher

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .setMinimumLoggingLevel(if (BuildConfig.LOG_ENABLED) android.util.Log.DEBUG else android.util.Log.ERROR)
            .build()

    override fun onCreate() {
        super.onCreate()
        // Local-only error log — install the uncaught-exception
        // handler first, so even a startup crash is captured. It
        // only writes a local file and chains to the previous
        // handler; see [CrashLogHandler].
        CrashLogHandler.install(this)
        if (BuildConfig.LOG_ENABLED) {
            Timber.plant(LineNumberDebugTree())
        } else {
            Timber.plant(NoOpReleaseTree())
        }
        notificationChannelInitializer.ensureDefaultChannels()
        autoLockObserver.register()

        // v1.8.0 (post-audit fix unread badges) — migration ONE-SHOT pour
        // purger les badges hérités v1.7.1 ET les flags `read=0` désynchronisés
        // du système. Exécutée AVANT que l'Activity ne soit créée et que la
        // liste ne soit subscribée, en synchrone (`runBlocking` cap 1 s) pour
        // ne pas laisser l'UI afficher 1 frame de compteurs legacy.
        //
        // Pourquoi le simple recompute SQL ne suffit pas : il s'appuie sur
        // `messages.read` qui est lui-même désynchronisé. Si l'user a lu un
        // message dans Google Messages SANS ouvrir Relay, le système pose
        // `READ=1` mais Relay ne re-lit jamais le `read` pour les messages
        // déjà mirror-és → `messages.read` reste à 0 indéfiniment → recompute
        // calcule `unread_count > 0` à juste titre selon Room, mais incorrect
        // selon l'expérience utilisateur réelle.
        //
        // Solution : reset brutal mais idempotent via flag DataStore. Le user
        // perd l'info "10 vrais messages non lus" si elle existait — acceptable
        // pour purger l'état pourri. Les futurs SMS live arrivent via
        // `SmsDeliverReceiver` avec `read=0` + `touchConversation(+1)`, et
        // le badge s'affichera correctement à partir de là.
        //
        // Le flag `unreadResetV180` empêche cette purge de se rejouer à chaque
        // cold-start (sinon les vrais nouveaux non-lus seraient effacés).
        //
        // v1.24.0 — les trois migrations one-shot qui ouvrent SQLCipher (unread-reset v1.8.0,
        // rapatriement des attachments v1.14.7, dédup v1.22.x) sont regroupées dans
        // [StartupMigrations] : une seule lecture DataStore, exécution SÉQUENTIELLE (elles tapent
        // toutes la même base, le parallélisme ne produisait que de la contention de verrou), et
        // une garde globale qui laisse une install à jour sortir sans ouvrir la base du tout.
        // Chaque migration garde son propre flag comme source de vérité. Async, aucun blocage du
        // main thread, erreurs catchées et loguées sans crash.
        appScope.launch { startupMigrations.run() }
        // Audit P-P0-5: the historical R6 fix used `runBlocking(IO) { appLock.resolveInitialState() }`
        // here to pre-resolve the lock state before any broadcast receiver could read it. That
        // blocked the main thread for 50-200 ms on DataStore on cold-start. We now kick the
        // resolution off asynchronously — receivers / services that depend on the resolved state
        // call `appLock.ensureResolved()` themselves (idempotent, mutexed) inside their own
        // coroutine context. The main thread is freed and the contract is preserved.
        appScope.launch { appLock.ensureResolved() }
        // Telephony sync: register the system-provider ContentObserver + drain anything that
        // accumulated while the process was dead. Schedule a 12 h safety-net WorkManager job
        // so we still converge even if the observer never fires (rare OEM bug, force-stop).
        // v1.24.0 SEC-CRIT — `TelephonySyncManager` injects DAOs, so resolving it here would build
        // `AppDatabase` on the main thread, and with it run [LegacyZeroKeyRekey]. On the single
        // launch that rebuilds a legacy zero-key database that is seconds of work: a guaranteed
        // ANR. Resolution is deferred to the IO coroutine below, which is also what warms the
        // database for every other startup task.
        // Client mode — a linked device has no telephony to sync: its
        // messages arrive from the hub. The manager only starts on a
        // hub; a switch back to hub starts it then (`start()` is
        // idempotent), and a switch to client simply stops triggering
        // it — the periodic job is cancelled below, and the sync only
        // runs when something requests it.
        appScope.launch(ioDispatcher) {
            settingsRepository.flow
                .map { it.mode }
                .distinctUntilChanged()
                .collect { mode ->
                    if (mode == DeviceMode.HUB) {
                        runCatching { telephonySyncManagerLazy.get().start() }
                            .onFailure { Timber.w(it, "TelephonySyncManager.start failed") }
                    }
                }
        }
        // Group-send own-number fix — auto-fill "My phone number"
        // from the SIM when the setting is blank (first launch, or
        // a user who never typed it). Fail-soft and hub-only inside
        // [OwnNumberAutoFill.runOnce]: no permission, no SIM number,
        // or client mode all skip silently, and a number the user
        // typed is never overwritten. This does NOT fill the number
        // on GrapheneOS — the SIM read returns null there — where
        // the Settings nudge is the path instead.
        appScope.launch(ioDispatcher) {
            runCatching { ownNumberAutoFillLazy.get().runOnce() }
                .onFailure { Timber.w(it, "OwnNumberAutoFill failed") }
        }
        // Slice 5 — watch the contacts provider: a rename, a new or
        // removed number, a deleted contact, or a changed photo
        // invalidates the phone's own name cache (which nothing
        // invalidated before — edits only showed after a process
        // restart) and tells the LAN server to push a live delta to
        // connected desktops. The server notify only fires when the
        // server toggle is on; the notify itself is also a no-op
        // when the server is not running. When the server is off,
        // contacts re-sync from the next `sync_request` snapshot
        // anyway. Registered for the process lifetime, like the
        // telephony sync above — never unregistered.
        registerContactsObserver()
        // Client mode — the 12 h telephony safety-net job only runs on
        // a hub. On a linked device it is cancelled: it would mirror
        // this device's own telephony into the hub's message cache.
        // The observer also covers a runtime mode switch (the direct
        // call it replaces only ran at startup).
        appScope.launch {
            settingsRepository.flow
                .map { it.mode }
                .distinctUntilChanged()
                .collect { mode ->
                    if (mode == DeviceMode.HUB) {
                        TelephonySyncWorker.schedulePeriodic(this@MainApplication)
                    } else {
                        WorkManager.getInstance(this@MainApplication)
                            .cancelUniqueWork(TelephonySyncWorker.PERIODIC_NAME)
                    }
                }
        }
        // v1.9.0 — schedule du SafetyCall worker (idempotent KEEP policy).
        // Même si la config deadman est désactivée, le worker tourne en no-op
        // (1 lecture DataStore par heure, négligeable batterie). Avantage : un
        // enable ultérieur via Settings n'a pas besoin de cold-start pour
        // commencer à fonctionner — le tick suivant arme déjà le timer.
        SafetyCallWorker.schedulePeriodic(this)

        // v1.27.2 — RÉVEIL À L'ÉCHÉANCE, en plus du tick horaire.
        //
        // Le tick de 60 min ne PROGRAMME rien : il échantillonne. Une échéance atteinte à 14:25
        // partait donc au premier passage suivant — 14:48 le 2026-08-05, soit 23 minutes de retard
        // sur un délai d'UNE HEURE, qui est le minimum que l'interface propose. L'application
        // offrait un réglage qu'elle ne savait pas honorer.
        //
        // Un unique observateur, ici, plutôt qu'un appel à chaque écriture : le Safety call est
        // modifié depuis six endroits (armement, « Je vais bien », ouverture de l'application, tap
        // sur la notification, jalon horaire, envoi d'une relance). En câbler cinq sur six est
        // exactement le motif de défaut qui a produit la majorité des correctifs de ce mois-ci.
        // Ce collecteur les couvre tous, y compris ceux qu'on ajoutera demain.
        //
        // Le processus est vivant chaque fois que la configuration change — c'est lui qui la
        // change — et cette collecte est relancée à chaque démarrage à froid, donc l'alarme est
        // reposée après un redémarrage, où le système les efface toutes.
        // ⚠️ La déduplication porte sur l'INSTANT calculé, pas sur la configuration.
        //
        // Le jalon horaire du worker écrit `monotonicAccumulatedMs` et `monotonicLastActivityAt`
        // à chaque passage : dédupliquer sur la configuration ferait donc reposer une alarme
        // toutes les heures pour rien. Surtout, l'échec d'un envoi produit un aller-retour du
        // compteur (réservation puis restitution) qui repasserait deux fois par ici. En
        // dédupliquant sur l'instant, ces écritures sont invisibles — le jalon déplace du temps
        // d'un champ à l'autre **sans changer la somme**, donc sans changer l'échéance.
        appScope.launch {
            settingsRepository.flow
                .map { s ->
                    SafetyCallAlarmScheduler.nextWakeUpAt(
                        cfg = s.security.safetyCall,
                        nowMs = System.currentTimeMillis(),
                        nowMonoMs = SystemClock.elapsedRealtime(),
                    )
                }
                .distinctUntilChanged()
                .collect { at ->
                    runCatching { SafetyCallAlarmScheduler.apply(this@MainApplication, at) }
                        .onFailure { Timber.w(it, "SafetyCallAlarmScheduler.apply failed") }
                }
        }

        // v1.27.2 — pas de réveil orphelin quand la séquence de relances se ferme.
        //
        // Constaté sur appareil le 2026-08-05 : après avoir arrêté le Safety call en pleine
        // séquence, le travail ponctuel de la relance suivante restait **en file**. Inoffensif —
        // le worker relit la configuration et ne trouve plus rien à envoyer — mais il sort le
        // téléphone de veille pour rien, et il survit à l'événement qui l'avait justifié.
        //
        // Même endroit et même raison que l'alarme : la séquence se ferme depuis plusieurs
        // chemins (fin normale, « Je vais bien », désactivation, ouverture de l'application), et
        // en câbler tous sauf un est le motif de défaut qui revient le plus souvent ici.
        appScope.launch {
            settingsRepository.flow
                // 🔴 v1.27.2 (audit Codex du 2026-08-05, C-01) — `!hasRelancePending` NE SUFFIT
                // PAS, et cette version-ci a bien failli annuler la dernière alerte pendant son
                // envoi.
                //
                // `messagesSent` compte les créneaux **réservés**, pas les envois conclus. Quand
                // le worker de la 4ᵉ et dernière relance réserve son créneau, le compteur passe à
                // 4 AVANT le premier envoi, et `hasRelancePending` — vrai seulement de 1 à 3 —
                // devient faux immédiatement. Cet observateur recevait donc `false` et appelait
                // `cancelUniqueWork` sur `RELANCE_WORK_NAME`, c'est-à-dire sur **le worker en
                // train d'envoyer**. L'annulation pouvait tomber avant le premier SMS ou au
                // milieu des contacts.
                //
                // L'état terminal doit être DURABLE : plus de relance en attente **et** aucun bail
                // en cours. Tant qu'un créneau est réservé, quelqu'un travaille dessus.
                // Le prédicat vit dans le domaine, pas ici : il était intestable à cet endroit, et
                // c'est précisément pour ça que le défaut est passé.
                .map { it.security.safetyCall.isSequenceTerminal }
                .distinctUntilChanged()
                .collect { terminal ->
                    if (terminal) {
                        runCatching { SafetyCallWorker.cancelRelance(this@MainApplication) }
                            .onFailure { Timber.w(it, "SafetyCallWorker.cancelRelance failed") }
                    }
                }
        }

        // v1.27.2 (audit Codex du 2026-08-05, C-07 / C-08 puis P-05 / P-06) — RÉCONCILIATION
        // UNIQUE des DEUX notifications du Safety call, sur le couple (configuration persistée,
        // état du verrou).
        //
        // Elles étaient trois à écrire ici sans état commun — le worker pour l'avertissement de
        // pré-déclenchement, le worker encore pour la séquence, cet observateur pour la séquence —
        // et chaque trou venait de là :
        //
        //  - le worker REPUBLIAIT la séquence après coup alors que l'application venait d'entrer en
        //    mode leurre : sous contrainte, l'agresseur voyait réapparaître qu'un réseau de soutien
        //    avait été prévenu, et plus rien ne venait l'effacer ;
        //  - à l'inverse, un processus tué entre le dernier envoi et le retrait laissait une
        //    notification « alerte en cours » que personne ne réconciliait au redémarrage ;
        //  - 🔴 et surtout, l'avertissement n'était JAMAIS retiré au déclenchement. Les deux
        //    notifications restaient affichées côte à côte, et l'avertissement **survivait à
        //    l'entrée en mode leurre** — il révélait à qui tenait le téléphone qu'une fonction
        //    d'alerte existait et courait.
        //
        // Un seul propriétaire, donc, qui décide sur l'état réel plutôt que sur l'enchaînement des
        // évènements. Il se rejoue à chaque changement de configuration, à chaque changement de
        // verrou, et **à chaque démarrage à froid** — ce qui ferme aussi le cas du processus tué.
        //
        // ⚠️ La décision elle-même vit dans le domaine ([SafetyCallNotice.decide]), pas ici. Elle
        // était intestable à cet endroit, et c'est précisément pour ça que les trois défauts
        // ci-dessus sont passés.
        appScope.launch {
            combine(
                settingsRepository.flow.map { it.security.safetyCall },
                appLock.state.map { it is AppLockManager.LockState.PanicDecoy },
            ) { cfg, isDecoy ->
                SafetyCallNotice.decide(
                    cfg = cfg,
                    isDecoy = isDecoy,
                    nowMs = System.currentTimeMillis(),
                    nowMonoMs = SystemClock.elapsedRealtime(),
                    peutEnvoyer = defaultSmsAppChecker.isDefault(),
                )
            }
                .distinctUntilChanged()
                .collect { notice ->
                    runCatching { safetyCallWarningNotifierLazy.get().reconcile(notice) }
                        .onFailure { Timber.w(it, "SafetyCall: reconciliation notification echouee") }
                }
        }

        // v1.10.0 SEC-11 — drift recovery post-reboot. `elapsedRealtime` est
        // remis à 0 par un reboot tandis que la valeur persistée
        // `monotonicLastActivityAt` reste celle d'avant-reboot. Sans ce filet,
        // un attaquant pourrait simuler un reboot pour neutraliser la mono
        // clock indéfiniment ; et un user honnête verrait son deadman pausé
        // jusqu'à ce que `nowMono` rattrape l'ancienne valeur.
        //
        // Stratégie : si la valeur stockée > nowMono → on la ramène à nowMono
        // (le compteur mono redémarre du boot, la wall-clock continue à
        // compter normalement → le deadman est effectivement prolongé du
        // post-reboot uptime). Async, ne bloque pas le main thread.
        appScope.launch {
            runCatching {
                val security = settingsRepository.flow.first().security
                val nowMono = SystemClock.elapsedRealtime()
                val safetyDrift = security.safetyCall.monotonicLastActivityAt > 0L &&
                    security.safetyCall.monotonicLastActivityAt > nowMono
                // v1.10.0 audit S2 — même drift recovery sur le cooldown
                // anti-spam emergency. Sans ça, après reboot, mono > nowMono
                // verrouillerait le bouton URGENCE indéfiniment puisque
                // `nowMono - mono` est négatif (< ANTI_SPAM_WINDOW_MS).
                val emergencyDrift = security.emergency.monotonicLastTriggeredAt > 0L &&
                    security.emergency.monotonicLastTriggeredAt > nowMono
                // v1.27.2 (relecture Gemini du 2026-08-05) — 🔴 DEADMAN ARMÉ QUI NE PART JAMAIS.
                //
                // `isExpired()` rend `false` tant que `monotonicLastActivityAt` vaut `0L` — filet
                // posé en v1.10.0 pour qu'une configuration héritée de la v1.9.0, dépourvue de
                // compteur monotone, ne déclenche pas par surprise après la mise à jour.
                //
                // Mais **rien ne réparait jamais cet état** : la récupération ci-dessus exige
                // `> 0L`, et le jalon du worker se retire aussi quand l'ancre vaut `0L`. Un
                // utilisateur qui met à jour puis part en randonnée sans rouvrir l'application
                // voit « Activé » dans l'interface et **son deadman ne partira jamais**. Le filet
                // protégeait contre un faux positif au prix du pire faux négatif possible.
                //
                // On pose l'ancre à `nowMono` sans toucher à `lastActivityAt` : le décompte
                // monotone repart pour un cycle complet, donc rien ne déclenche par surprise —
                // mais il déclenchera.
                val safetyUnanchored = security.safetyCall.enabled &&
                    security.safetyCall.lastActivityAt > 0L &&
                    security.safetyCall.monotonicLastActivityAt == 0L
                if (safetyDrift || emergencyDrift || safetyUnanchored) {
                    Timber.i(
                        "MonotonicDriftRecovery: realign (drift=%s, emergency=%s, unanchored=%s, nowMono=%d)",
                        safetyDrift,
                        emergencyDrift,
                        safetyUnanchored,
                        nowMono,
                    )
                    settingsRepository.update { s ->
                        s.copy(
                            security = s.security.copy(
                                // v1.27.2 (audit externe Gemini 2026-08-04) — on re-cale l'ancre
                                // SANS toucher au temps déjà capitalisé
                                // (`monotonicAccumulatedMs`, jalonné par [SafetyCallWorker]).
                                //
                                // Avant, ce re-calage remettait de fait le compteur monotone à
                                // zéro à chaque redémarrage. Comme `isExpired` exige les DEUX
                                // horloges, redémarrer plus souvent que le délai suffisait à ce
                                // que le deadman ne parte jamais. Le capital survit désormais au
                                // redémarrage ; seul le segment non encore jalonné est perdu,
                                // soit moins d'un tick.
                                safetyCall = if (safetyDrift || safetyUnanchored) {
                                    s.security.safetyCall.copy(monotonicLastActivityAt = nowMono)
                                } else s.security.safetyCall,
                                // v1.27.2 (relecture Gemini du 2026-08-05) — on REND LA MAIN à
                                // l'horloge murale au lieu de recaler l'ancre sur `nowMono`.
                                //
                                // Poser `nowMono` relançait un cooldown de 60 secondes que
                                // l'utilisateur n'avait jamais déclenché : après CHAQUE
                                // redémarrage, le bouton d'urgence restait inerte une minute. Sur
                                // un bouton de panique, échouer fermé signifie « le bouton ne
                                // marche pas ». C'est le mauvais côté, sans discussion possible.
                                //
                                // `0L` n'est pas un bricolage : c'est la valeur que
                                // [EmergencyConfig.isInAntiSpamWindow] interprète déjà comme
                                // « pas de compteur monotone exploitable, l'horloge murale
                                // décide » — le chemin prévu pour les configurations héritées.
                                // C'est exactement notre situation après un redémarrage, où le
                                // compteur monotone ne peut plus rien affirmer.
                                //
                                // L'anti-spam reste donc porté par la murale : si soixante
                                // secondes se sont réellement écoulées, le cooldown est
                                // réellement fini. Et le prochain déclenchement repose une vraie
                                // ancre monotone, ce qui restaure la protection SEC-4 contre une
                                // avance d'horloge par root.
                                //
                                // ⚠️ Ne PAS écrire `nowMono - ANTI_SPAM_WINDOW_MS` : sur un
                                // téléphone démarré depuis moins d'une minute, la valeur serait
                                // NÉGATIVE, et une ancre négative persistée échappe ensuite à la
                                // garde `> 0L` de cette même récupération.
                                emergency = if (emergencyDrift) {
                                    s.security.emergency.copy(monotonicLastTriggeredAt = 0L)
                                } else s.security.emergency,
                            ),
                        )
                    }
                }
            }.onFailure { Timber.w(it, "MonotonicDriftRecovery: failed") }
        }
        // Order matters at first boot: mirror the OS-wide blocked-numbers list **first**, then
        // kick the SMS import. Otherwise the worker may scan `content://sms` before the Room
        // blocklist is populated, and the user sees blocked correspondents resurface in the
        // very first import (audit "indésirables à l'import"). The importer is fast (< 50 ms
        // typical) and idempotent on re-runs, so paying this serial cost is harmless.
        //
        // v1.24.0 — `telephonySyncManager.start()` est désormais asynchrone (résolution `Lazy`
        // hors main thread), donc l'ordre n'est PLUS garanti par la séquence de `onCreate`. Il
        // reste correct parce que `runSync` rejoue lui-même `importFromSystem()` en tête de
        // chaque passe, sous `syncMutex` et avec un curseur — l'opération est idempotente.
        //
        // The system-side read in `TelephonySyncWorker.runImport` *also* queries
        // `BlockedNumberContract` directly so a fresh-install user who hasn't accepted the
        // default-SMS prompt yet still gets the filter on the next sync tick — this is just
        // belt-and-braces.
        appScope.launch {
            runCatching { blockedNumbersImporterLazy.get().importFromSystem() }
            // v1.8.0 — second recompute après l'import blocklist + en async.
            // L'import peut avoir purgé des conversations, donc on relance pour
            // ré-aligner les compteurs sur l'état final. Le 1er recompute
            // synchrone (au-dessus, avant Activity) a déjà supprimé l'état
            // legacy v1.7.1, donc ici on capture juste les delta de la purge.
            runCatching { conversationDaoLazy.get().recomputeAllUnreadCounts() }
                .onFailure { Timber.w(it, "recomputeAllUnreadCounts (async post-block) failed") }
            // Client mode — no telephony sync on a linked device.
            if (settingsRepository.hydratedOrNull()?.mode != DeviceMode.CLIENT) {
                TelephonySyncWorker.enqueueOneShot(this@MainApplication)
            }
        }

        // v1.3.10 — observe le flag `AdvancedSettings.keepAliveService` et démarre /
        // arrête le foreground [KeepAliveService] en conséquence. Cette boucle vit
        // pendant toute la durée du processus (appScope = SupervisorJob applicationwide),
        // garantit l'idempotence (`distinctUntilChanged` filtre les ré-émissions
        // identiques), et couvre :
        //   - cold-start app avec flag déjà ON depuis une session précédente → démarrage
        //   - toggle ON pendant que l'app tourne → démarrage immédiat
        //   - toggle OFF pendant que l'app tourne → arrêt immédiat de la notif persistante
        //   - boot du device (avant ouverture app) → couvert par BootReceiver, complémentaire
        appScope.launch {
            settingsRepository.flow
                .map { it.advanced.keepAliveService }
                .distinctUntilChanged()
                .onEach { enabled ->
                    if (enabled) {
                        KeepAliveService.start(this@MainApplication)
                    } else {
                        KeepAliveService.stop(this@MainApplication)
                    }
                }
                .collect()
        }

        // Server default-off — one-shot upgrade preserve, before the
        // observer below reads the toggle: on an upgrade from a build
        // where the server defaulted ON, keep it on for users who
        // never toggled it; a fresh install stays off until the user
        // turns it on. The store's migration flag makes it one-shot
        // and idempotent; the observer is flow-driven, so it picks
        // up the write whenever it lands. Fail-soft: a failed read
        // treats the install as fresh (server stays off).
        appScope.launch {
            runCatching {
                linkedDeviceStore.migrateServerDefaultOnce(isUpgradeInstall())
            }.onFailure { Timber.w(it, "Server default migration failed") }
        }

        // Slice 1 — same pattern for the LAN server: observe the toggle
        // in the linked-device store and start / stop the foreground
        // [SyncService] in consequence. The toggle defaults OFF, so a
        // fresh install never starts the server until the user turns
        // it on; turning it off on the Linked devices screen stops
        // the service (and its notification) immediately, and the app
        // works fully standalone. Boot is covered by BootReceiver,
        // complementarily.
        // Client mode — the server also needs the device to BE a hub:
        // the toggle value is kept across a mode switch but ignored
        // in client mode, so a linked device never serves.
        appScope.launch {
            combine(
                settingsRepository.flow.map { it.mode },
                linkedDeviceStore.serverEnabled,
            ) { mode, enabled -> mode == DeviceMode.HUB && enabled }
                .distinctUntilChanged()
                .onEach { shouldRun ->
                    if (shouldRun) {
                        SyncService.start(this@MainApplication)
                    } else {
                        SyncService.stop(this@MainApplication)
                    }
                }
                .collect()
        }

        // Client mode — the mirror of the server observer above: a
        // linked device starts its client service, which reconnects
        // to the stored hub (and stops itself when there is no link);
        // a hub stops it. Boot is covered by BootReceiver,
        // complementarily.
        appScope.launch {
            settingsRepository.flow
                .map { it.mode }
                .distinctUntilChanged()
                .collect { mode ->
                    if (mode == DeviceMode.CLIENT) {
                        ClientSyncService.start(this@MainApplication)
                    } else {
                        ClientSyncService.stop(this@MainApplication)
                    }
                }
        }

        // v1.14.3 hotfix — migration one-shot pour aligner l'invariant :
        // si l'user a désactivé le Mode urgence en v1.14.0 ou v1.14.1 (avant
        // le fix cascade-disable v1.14.2), le flag `emergency.enabled = false`
        // mais `emergencyShortcutEnabled` est resté à `true` → la notif
        // persistante ré-apparaissait à chaque lancement de l'app. Cette
        // migration corrige rétroactivement l'état DataStore au cold-start.
        // Idempotente : si l'invariant est déjà respecté, le `update` ne
        // re-écrit pas (DataStore détecte l'égalité). Exécutée UNE fois
        // par cold-start, négligeable côté perf.
        appScope.launch {
            val snapshot = settingsRepository.flow.first()
            val sec = snapshot.security
            // v1.14.5 — étendu : repair AUSSI `lastTriggeredAt` si emergency
            // désactivé (user remonté 2026-05-22 : banner "Alerte urgence
            // déclenchée récemment" persistait 30 min même après désactivation
            // du mode urgence). `lastTriggeredAt > 0` post-disable n'a aucun
            // usage légitime (cooldown moot car !enabled, chip masqué attendu).
            val hasOrphanShortcut = !sec.emergency.enabled &&
                (sec.emergencyShortcutEnabled || sec.emergencyCallPoliceEnabled)
            val hasOrphanTrigger = !sec.emergency.enabled &&
                (sec.emergency.lastTriggeredAt != 0L ||
                    sec.emergency.monotonicLastTriggeredAt != 0L)
            val needsRepair = hasOrphanShortcut || hasOrphanTrigger
            if (needsRepair) {
                Timber.i(
                    "MainApplication: repairing dirty emergency state (enabled=%s shortcut=%s police=%s lastTriggeredAt=%d) → cascade-disable",
                    sec.emergency.enabled,
                    sec.emergencyShortcutEnabled,
                    sec.emergencyCallPoliceEnabled,
                    sec.emergency.lastTriggeredAt,
                )
                settingsRepository.update { s ->
                    s.copy(
                        security = s.security.copy(
                            emergency = s.security.emergency.copy(
                                lastTriggeredAt = 0L,
                                monotonicLastTriggeredAt = 0L,
                            ),
                            emergencyShortcutEnabled = false,
                            emergencyCallPoliceEnabled = false,
                        ),
                    )
                }
            }
        }

        // v1.12.0 — Observe le flag `security.emergencyShortcutEnabled` pour
        // poster/canceler la notification persistante du raccourci urgence
        // (URGENCE + 112). Même pattern que KeepAliveService : idempotent,
        // hot, distinctUntilChanged.
        // v1.12.0 — combine shortcutEnabled + policeEnabled : à chaque
        // changement de l'un ou de l'autre, on re-poste (ou annule) avec
        // le bon set d'actions. Notifier `postShortcut` est idempotent.
        // v1.12.0 audit fix S1 — combiner aussi `appLock.state` : si l'user
        // entre en PanicDecoy (PIN panique), la notif persistante doit être
        // CANCEL même si `emergencyShortcutEnabled = true`. Sinon un attaquant
        // qui pose la main sur le téléphone en mode décoy voit toujours la
        // notif "URGENCE/112/17" depuis le lock screen et peut déclencher
        // l'envoi SMS aux contacts urgence (qui passerait quand même la garde
        // PanicDecoy du UseCase, mais leak la *présence* du raccourci ⇒ leak
        // d'info que Relay a un mode urgence configuré).
        // v1.28.13 (audit pré-release, M1) — combiner aussi le RETOUR AU PREMIER PLAN. Sans
        // notifications, `postShortcut` sort sans rien poster ; la bannière des Réglages envoie alors
        // vers la page Android qui les rallume, et disparaît au retour… mais rien ne repostait le
        // raccourci avant le prochain redémarrage du processus : l'écran verrouillé restait vide
        // alors que plus rien ne le signalait. Chaque retour au premier plan repose désormais le
        // raccourci (idempotent, même identifiant).
        appScope.launch {
            kotlinx.coroutines.flow.combine(
                settingsRepository.flow
                    .map { it.security.emergencyShortcutEnabled to it.security.emergencyCallPoliceEnabled }
                    .distinctUntilChanged(),
                appLock.state,
                androidx.lifecycle.ProcessLifecycleOwner.get().lifecycle.currentStateFlow
                    .map { it.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED) }
                    .distinctUntilChanged(),
            ) { (enabled, policeEnabled), lockState, auPremierPlan ->
                EtatDuRaccourci(
                    enabled = enabled,
                    policeEnabled = policeEnabled,
                    isPanicDecoy = lockState is AppLockManager.LockState.PanicDecoy,
                    auPremierPlan = auPremierPlan,
                )
            }
                .distinctUntilChanged()
                .onEach { etat ->
                    if (etat.enabled && !etat.isPanicDecoy) {
                        emergencyShortcutNotifier.postShortcut(policeEnabled = etat.policeEnabled)
                    } else {
                        emergencyShortcutNotifier.cancelShortcut()
                    }
                }
                .collect()
        }
    }

    /**
     * Whether this install was updated from an earlier version:
     * the package was first installed before its last update. A
     * read failure answers false (fresh install) — the safe
     * direction for the server-default migration, which then
     * leaves the server off. The `getPackageInfo(String, Int)`
     * overload is deprecated on API 33+, so TIRAMISU+ uses the
     * flags overload.
     */
    private fun isUpgradeInstall(): Boolean {
        return runCatching {
            val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageManager.getPackageInfo(
                    packageName,
                    PackageManager.PackageInfoFlags.of(0),
                )
            } else {
                @Suppress("DEPRECATION")
                packageManager.getPackageInfo(packageName, 0)
            }
            info.firstInstallTime < info.lastUpdateTime
        }.getOrDefault(false)
    }

    /**
     * Slice 5 — registers the contacts ContentObserver for the
     * process lifetime. Changes arrive on the main thread (the
     * observer's handler); the work itself is cheap (cache evict)
     * or deferred to [appScope] (the server notify).
     */
    private fun registerContactsObserver() {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                onContactsChanged()
            }
        }
        // Contacts-permission fix — registering for the calling user
        // requires no permission (AOSP ContentService Javadoc), so a
        // missing READ_CONTACTS should not throw here; wrapped anyway,
        // because a provider that did throw would kill the cold start
        // before the first frame. Without the observer the name cache
        // just goes stale until the next launch — never a crash.
        runCatching {
            contentResolver.registerContentObserver(
                ContactsContract.Contacts.CONTENT_URI,
                true,
                observer,
            )
        }.onFailure { Timber.w(it, "MainApplication: contacts observer registration failed") }
    }

    /**
     * Slice 5 — one contacts-provider change: invalidate the local
     * name cache (fixes the phone's own stale names, too), then —
     * only when the server toggle is on — nudge the server to
     * re-check and push the live delta. Failures are logged, never
     * thrown: a contact edit must never crash the app.
     */
    private fun onContactsChanged() {
        runCatching { contactsReaderLazy.get().invalidate() }
            .onFailure { Timber.w(it, "ContactsReader.invalidate failed") }
        appScope.launch {
            runCatching {
                // Client mode — the server never runs on a linked
                // device, so a contact edit must not build the server
                // graph there (the toggle value is kept but ignored).
                if (settingsRepository.hydratedOrNull()?.mode == DeviceMode.HUB &&
                    linkedDeviceStore.serverEnabled.first()
                ) {
                    syncServerLazy.get().notifyContactsChanged()
                }
            }.onFailure { Timber.w(it, "SyncServer.notifyContactsChanged failed") }
        }
    }
}

/** v1.28.13 — ce qui décide de poser ou retirer le raccourci d'urgence de l'écran verrouillé. */
private data class EtatDuRaccourci(
    val enabled: Boolean,
    val policeEnabled: Boolean,
    val isPanicDecoy: Boolean,
    val auPremierPlan: Boolean,
)
