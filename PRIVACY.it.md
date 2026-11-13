# Informativa sulla privacy — SMS Relay

_Ultimo aggiornamento: 13 novembre 2026_ · 🇬🇧 [English](PRIVACY.md) · 🇫🇷 [Français](PRIVACY.fr.md) · 🇩🇪 [Deutsch](PRIVACY.de.md) · 🇪🇸 [Español](PRIVACY.es.md)

> **Traduzione.** Questa traduzione italiana è fornita a titolo informativo. Fanno fede le versioni
> [inglese](PRIVACY.md) e [francese](PRIVACY.fr.md) di questa informativa; in caso di discrepanza,
> prevalgono le versioni inglese e francese.

SMS Relay (`com.filestech.sms`) fa parte della suite **Files Tech**, pubblicata da **Patrice
Haltaya**. Si vedano anche le [condizioni d'uso](TERMS.it.md).

## Che cosa raccogliamo

**Nulla.** SMS Relay non raccoglie, non trasmette né aggrega alcun dato personale — con due
eccezioni, entrambe avviate da lei: quando cerca un GIF, le parole digitate vengono inviate a
Klipy affinché restituisca GIF corrispondenti, e quando un messaggio contiene un link, Relay
scarica quella pagina una volta per mostrare una scheda di anteprima (vedere [Rete](#rete) di
seguito). Relay non conserva nulla di questi scambi oltre al GIF che lei sceglie di inviare e
all'anteprima che le mostra. Collegare un computer non cambia nulla di tutto ciò: telefono e
computer comunicano direttamente tra loro sulla sua rete Wi-Fi locale — nessun server Relay,
nessun cloud, nulla lascia la rete.

Nessun SDK di analisi, nessun sistema di segnalazione degli arresti anomali, nessun endpoint di
telemetria, nessun identificativo pubblicitario, nessuna libreria di fingerprinting. Il binario non
contiene alcun codice di tracciamento di terze parti.

## Che cosa resta sul suo dispositivo

- I suoi SMS e MMS, in un database Room cifrato (SQLCipher) protetto da una chiave incapsulata
  dall'AndroidKeyStore.
- I metadati delle conversazioni (bozze, fissaggio in alto, archiviazione, cassaforte, preferenze per
  conversazione).
- Le impostazioni, in Android DataStore Preferences.
- Un hash con sale PBKDF2-HMAC-SHA512 del suo codice PIN (il PIN non viene mai memorizzato in
  chiaro).
- Gli eventuali allegati MMS, in `<files>/mms_attachments/`.
- Gli eventuali PDF delle conversazioni generati localmente, in `<files>/exports/`.
- Se li configura, i suoi contatti di emergenza e le impostazioni della modalità emergenza e del
  Safety call.
- Se collega un dispositivo, la sua scheda: ID del dispositivo, tipo (computer, telefono o
  tablet), nome visualizzato, chiave pubblica e data di collegamento. La chiave privata
  corrispondente non lascia mai il dispositivo collegato e la scheda viene eliminata quando lei
  rimuove il dispositivo. Il telefono conserva inoltre il proprio ID hub e il certificato TLS e
  la chiave privata che il server locale presenta ai dispositivi collegati (la chiave privata
  risiede nell'AndroidKeyStore).

Come ogni app SMS predefinita, SMS Relay scrive anche i suoi messaggi nell'archivio messaggi di
sistema di Android, che li conserva indipendentemente dall'applicazione.

Il backup di sistema di Android è **disattivato**: questi dati non finiscono né su Google Drive né
in un trasferimento di dispositivo senza il suo consenso esplicito.

## Rete

SMS Relay detiene l'autorizzazione `INTERNET` per tre usi. (1) Ricerca di GIF: quando apre la
scheda GIF e digita una ricerca, le parole digitate vengono inviate a Klipy (`api.klipy.com`), che
restituisce GIF corrispondenti; toccarne uno lo scarica da Klipy e anche le miniature nella
griglia provengono da Klipy. Klipy vede le sue parole di ricerca e il suo indirizzo IP, come
qualsiasi servizio contattato. (2) Anteprime dei link: quando un messaggio contiene un URL, Relay
scarica quella pagina una volta, sul dispositivo, e ne legge titolo e immagine Open Graph solo
per la visualizzazione — il sito collegato vede la richiesta e il suo indirizzo IP, come in
qualsiasi visita; l'immagine di anteprima non viene mai inviata come allegato. (3) Dispositivi
collegati: il telefono esegue un server TLS nella rete locale (porta 47831, annunciato via mDNS
come `_relay._tcp.`) affinché un computer da lei collegato possa trovarlo e connettersi. Il
server accetta solo i dispositivi la cui chiave pubblica lei ha memorizzato scansionando il loro
codice di abbinamento, e solo dopo che hanno dimostrato di detenere la chiave privata
corrispondente; il traffico non lascia mai la sua rete Wi-Fi e non è coinvolto alcun server Relay
né alcun account cloud. Il server può essere disattivato in Impostazioni → Dispositivi collegati;
l'app funziona allora pienamente da sola. Questo è l'unico uso di rete dell'app: nessun controllo
degli aggiornamenti, nessuna configurazione remota, nessun ping di analisi, nessuna segnalazione
di arresti anomali. Se l'autorizzazione è bloccata, l'app funziona comunque — la ricerca di GIF
semplicemente non restituisce nulla, i link non mostrano anteprime e nessun computer può
collegarsi. (L'autorizzazione era stata rimossa nella 1.28.13, quando l'app non effettuava alcuna
richiesta di rete; è tornata per la ricerca di GIF, poi per le anteprime dei link e ora per i
dispositivi collegati.)

Gli MMS sono instradati dal servizio MMS di Android, che contatta l'MMSC del suo operatore quando
lei ne invia o ne riceve uno. Gli MMS non hanno mai avuto bisogno dell'autorizzazione `INTERNET` e
continuano a non usarla.

I messaggi che lei invia passano attraverso la rete del suo operatore, come con qualsiasi app SMS.
Lo sviluppatore non li riceve mai.

## Funzioni di emergenza (inattive finché lei non le attiva)

- **Modalità emergenza.** Quando tiene premuto il pulsante di emergenza per tre secondi, SMS Relay
  invia un SMS ai contatti di emergenza che **lei** ha scelto. Se ha concesso l'autorizzazione alla
  posizione, esatta o approssimativa, e ha lasciato «includi la mia posizione» (attivo per
  impostazione predefinita in questa modalità), l'applicazione chiede ad Android **una** posizione in
  quell'istante: riprende una posizione risalente a meno di cinque minuti se Android ne dispone,
  altrimenti attende un nuovo rilevamento per al massimo otto secondi e, in mancanza, riprende
  l'ultima posizione nota se risale a meno di trenta minuti. Nessun tracciamento in background né
  continuo. La posizione viene aggiunta all'SMS sotto forma di link `https://maps.google.com/?q=…`;
  quando è solo approssimativa, Android la sposta di circa due chilometri e l'SMS lo indica con
  «(+/-N km)» dopo il link. Se un contatto apre questo link, è il suo browser a contattare Google
  Maps; l'applicazione, invece, non invia nulla a Google. Se la posizione è rifiutata o non
  disponibile, l'SMS lo dice e parte senza coordinate.
- **Safety call.** Se lei non usa l'applicazione entro il termine che ha fissato, SMS Relay invia un
  SMS di verifica agli stessi contatti. Non contiene alcuna posizione.
- **Chiamata di emergenza.** I riquadri di chiamata della schermata Emergenza propongono i numeri di
  emergenza del paese in cui il suo telefono è registrato sulla rete (il 112 è sempre presente).
  Quando ne tocca uno, l'applicazione chiede l'autorizzazione alle chiamate (`CALL_PHONE`) e, se lei
  la concede, effettua la chiamata direttamente; se la rifiuta, apre il tastierino telefonico con il
  numero già inserito, che non richiede alcuna autorizzazione. La scorciatoia sulla schermata di
  blocco apre sempre il tastierino telefonico.
- Nessuna di queste funzioni viene eseguita nella sessione esca aperta dal codice di panico.
- Questi messaggi sono normali SMS: il suo operatore può addebitarli in base al suo piano tariffario.

## Autorizzazioni

Si veda [PERMISSIONS.md](PERMISSIONS.md) per la motivazione di ciascuna autorizzazione.

## I suoi diritti

Poiché nessun dato personale lascia il suo dispositivo, non c'è nulla da consultare, rettificare o
cancellare presso un sistema remoto. Per cancellare i suoi dati in SMS Relay: disinstalli
l'applicazione (Android ne elimina automaticamente i dati) oppure usi **Impostazioni → Elimina
tutti i miei dati**, che cancella il database cifrato, gli alias del Keystore, gli allegati in
cache e i PDF esportati. I messaggi conservati dall'archivio messaggi di sistema di Android si
gestiscono da Android o da qualsiasi app SMS.

## Contatto

Per qualsiasi domanda: **contact@files-tech.com**.

L'editore non riceve alcun dato dall'applicazione. Gli arriva solo un'e-mail che lei sceglie di
scrivergli: serve solo a risponderle, è conservata per il tempo dello scambio, poi cancellata. Può
chiederne l'accesso o la cancellazione allo stesso indirizzo, e rivolgersi alla CNIL, l'autorità
francese per la protezione dei dati (www.cnil.fr), se ritiene che i suoi diritti non siano
rispettati.
