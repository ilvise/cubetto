# Idea base – App Android per statuine FABA personalizzate

> Stato: **proposta da discutere**. Nessun codice scritto finora.

## Obiettivo
Un'app per genitori, con un aspetto giocoso e da bambini, che permette di:
YouTube → MP3 → playlist → formato FABA (ID scelto) → copia via USB sul box → scrittura del tag NFC.
Solo **FABA prima generazione** (cubo rosso, offline). FABA+ è escluso di proposito.

## Cosa ho ricavato da `wansors/myfaba-hacks`
| Tema | Dettaglio |
|---|---|
| Cifratura | Ogni byte viene ruotato a sinistra di 3 bit, poi va in XOR con la chiave `[0x30,0x05,0x19,0x20]` indicizzata da `pos % 4`. Si porta in Kotlin in poche righe. |
| ID3 | Si cancellano TUTTI i tag e si scrive solo `TIT2 = K{ID}CP{NN}`. Se è sbagliato, il box lampeggia veloce e non suona. |
| Struttura sul box | `MKI01/K{ID}/CP01.MKI … CP99.MKI`, con al massimo 99 tracce. |
| Tag NFC | Record NDEF di tipo testo `02190530{ID}00`. Il contenuto deve stare in una posizione esatta: dalla pagina 7, byte 3, alla pagina 10, byte 4. |
| Chip | Il chip consigliato è NTAG213. L'NTAG215 funziona ma vuole 5 byte di padding. I cloni con UID che inizia per `0x1D` o `0xFF` **non funzionano**. |
| ID noti | `TAGS.md`: circa 370 statuine. Le italiane sono circa 180. |
| Rischi | Se il box si collega all'app ufficiale, un aggiornamento può cancellare le cartelle personalizzate. |

## Stack proposto
- **Kotlin + Jetpack Compose + Material 3**, con un tema personalizzato "kids": font tondeggiante (Fredoka/Nunito), card pastello, accento giallo e rosso.
- **youtubedl-android** (JunkFood02), lo stesso motore usato da Seal. Include Python e ffmpeg. Sul primo requisito:
  - `updateYoutubeDL()` scarica il binario `yt-dlp` dalle release GitHub e lo salva nei file privati dell'app.
  - restituisce `ALREADY_UP_TO_DATE` se la versione è già l'ultima, quindi in quel caso non fa nulla.
  - si usa `-x --audio-format mp3` per ottenere solo l'audio.
- **libaums**, per accedere al box in USB OTG senza root e in automatico. Come ripiego c'è la SAF, cioè la selezione manuale della cartella.
- **NFC**: scrittura raw delle pagine con `MifareUltralight`, così il layout è esatto sia su NTAG213 sia su NTAG215.
- **Room** (database), **WorkManager** (download e conversioni in background con notifica), **Coil** (copertine).

## Schermate (sul modello dell'app ufficiale)
Bottom bar con 4 voci:
1. **📦 Box**: stato della connessione USB, spazio libero, statuine presenti sul box, pulsante "Sincronizza".
2. **🔍 Scopri**: incolla un link o cerca su YouTube, coda dei download, avanzamento.
3. **📚 Libreria**: griglia di statuine o playlist come nello screenshot, con chip filtro (Preferiti, Tutti, Sul box, Da sincronizzare).
4. **🎵+ Crea**: nuova playlist, scelta dell'ID, foto della statuina.

Dettaglio playlist: come lo screenshot del Libro della giungla, con copertina grande, "Stories • 15 tracce • 1h", lista tracce riordinabile e un player.

## Funzioni QoL proposte
- **Condividi da YouTube**: dal tasto "Condividi" dell'app YouTube il link finisce subito in coda. Funziona anche con le playlist intere.
- **Selettore ID intelligente**:
  - lista degli ID noti, filtrabile per lingua (default IT) e cercabile;
  - suggerisce ID **liberi**, cioè non presenti nel registro;
  - avvisa se l'ID sovrascrive una statuina ufficiale;
  - aggiorna il registro scaricando `TAGS.md` da GitHub.
- **Normalizzazione volume** (ffmpeg `loudnorm`), così nessuna traccia è troppo forte per le orecchie dei bimbi.
- **Taglio dei silenzi** a inizio e fine traccia. **SponsorBlock** opzionale.
- **Divisione per capitoli**: un video lungo diventa più tracce.
- **Backup automatico** della cartella `K{ID}` già presente sul box prima di sovrascriverla.
- **Sync differenziale**: copia solo ciò che è cambiato. Prima controlla lo spazio libero e il limite di 99 tracce.
- **Lettura NFC**: appoggi una statuina al telefono e l'app dice di quale si tratta. Avvisa anche se il chip è un clone incompatibile.
- **Copia di una statuina**: legge un tag e lo scrive su un tag vuoto.
- **Anteprima audio** delle tracce, con mini player come nello screenshot.
- **Copertina e foto** della statuina: dalla miniatura del video o dalla fotocamera.
- **Avviso "non collegare il box all'app ufficiale"**.
- **Export e import** delle playlist in JSON per il backup.

## Punti da verificare con l'hardware reale
- Il FABA 1ª gen si presenta come USB Mass Storage FAT32. Va confermato con il tuo telefono e un cavo OTG.
- Android chiede il permesso USB la prima volta. Spuntando "usa sempre" si ottiene l'apertura e la sincronizzazione automatica.
- La dimensione dell'APK sarà di circa 70 MB per arm64, a causa di Python e ffmpeg.

## Domande aperte
Vedi la risposta in chat.
