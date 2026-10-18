# Cubetto

App Android per creare **statuine personalizzate per il FABA™ di prima generazione** a partire da video YouTube:
link → MP3 → playlist → formato FABA™ con l'ID scelto → copia via USB sul box → scrittura del tag NFC.

> FABA™ e myFABA™ sono marchi dei rispettivi titolari (FABA S.r.l.). Cubetto è un progetto
> indipendente: non è affiliato, sponsorizzato né approvato da FABA S.r.l. I nomi delle statuine
> sono citati solo per identificarne l'ID.
>
> Solo per il FABA™ di prima generazione (offline). **Non supporta FABA+.**
> Usa Cubetto solo con contenuti che hai il diritto di scaricare e ascoltare.

## Funzioni
- **yt-dlp locale**: viene scaricato e aggiornato dalle release GitHub (`yt-dlp/yt-dlp`) tramite
  [youtubedl-android](https://github.com/JunkFood02/youtubedl-android).
  - Il controllo avviene al massimo una volta al giorno e non fa nulla se c'è già l'ultima versione.
  - Si può forzare da *Impostazioni → Controlla ora*.
- **Solo audio MP3**: incolla un link oppure usa *Condividi* dall'app YouTube. Le playlist YouTube diventano più brani.
  - Download in background con notifica.
  - Opzioni: volume uniformato (`loudnorm`), taglio dei silenzi, divisione per capitoli, SponsorBlock, mono.
- **Statuine (playlist)**: nome, colore, foto, preferiti, brani riordinabili con trascinamento, anteprima audio.
- **Selettore ID** basato sul registro [`TAGS.md` di myfaba-hacks](https://github.com/wansors/myfaba-hacks/blob/main/TAGS.md).
  Il registro è incluso nell'app e si può aggiornare da GitHub.
  - **Consigliati** sono gli ID di statuine vendute in lingue *diverse* da quella dell'app, più gli ID liberi.
  - La scelta è sempre libera.
  - L'app avvisa se l'ID copre una statuina della tua lingua o un'altra tua statuina.
- **Formato FABA™**: tutti i tag ID3 vengono rimossi e si scrive solo il titolo `K{ID}CP{NN}`. Poi il file viene cifrato
  (rotazione di 3 bit e XOR con la chiave `30 05 19 20`) e salvato come `MKI01/K{ID}/CPnn.MKI`.
- **Box via USB OTG** con [libaums](https://github.com/magnusja/libaums), senza root.
  - Collegando il box l'app si apre e copia automaticamente le statuine modificate.
  - Prima di sovrascrivere fa il backup della cartella.
  - Controlla lo spazio libero e il limite di 99 tracce, e copia solo i brani cambiati.
  - Si possono ripristinare i backup.
- **NFC**:
  - Scrittura del tag con il testo `02190530{ID}00` nella posizione esatta letta dal box (pagina 7 byte 2 → pagina 10 byte 3).
    Funziona su NTAG203/213/215/216 senza trucchi, e il tag viene riletto per verifica.
  - Lettura di una statuina con il riconoscimento del suo ID, e copia su un tag vuoto.
  - Avviso per i chip clone non NXP (UID `1D…`/`FF…`).
- **Lingue**: italiano, spagnolo, catalano, francese, olandese (i paesi del registro FABA™) e inglese. La lingua si sceglie per app.
- **Export/import** delle statuine in JSON: contiene solo i link, l'audio si riscarica.

## Requisiti
- Android 8.0+ (API 26), solo **arm64-v8a**.
- Un cavo o adattatore USB OTG. Il FABA™ di prima generazione si presenta come una chiavetta USB (mass storage).
- Tag NTAG213/NTAG215 originali NXP (vedi [FAQ_TAGS.md](https://github.com/wansors/myfaba-hacks/blob/main/FAQ_TAGS.md)).

## Build
```bash
./gradlew testDebugUnitTest assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```
Le traduzioni si modificano in `tools/strings.py`, poi si rigenerano con `python3 tools/strings.py`.

## Struttura
| Percorso | Contenuto |
|---|---|
| `faba/` | cifratura MKI, tag ID3, layout del tag NFC, registro ID e suggerimenti |
| `ytdlp/` | gestione e aggiornamento di yt-dlp, worker dei download |
| `usb/` | accesso al box, sync differenziale, backup e ripristino |
| `nfc/` | lettura e scrittura dei tag in reader mode |
| `data/` | database Room e impostazioni |
| `ui/` | schermate Compose (Libreria, Statuina, Aggiungi, Crea/Scegli ID, Box, Impostazioni) |

## Licenza
GPL-3.0, come [myfaba-hacks](https://github.com/wansors/myfaba-hacks) da cui deriva l'algoritmo di cifratura
e come youtubedl-android. Font Fredoka e Nunito sotto SIL Open Font License.
