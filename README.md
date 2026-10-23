<div align="center">

[![English](https://img.shields.io/badge/🇬🇧-English-012169?style=for-the-badge)](README.md)
[![Italiano](https://img.shields.io/badge/🇮🇹-Italiano-lightgrey?style=for-the-badge)](README.it.md)

<img src="docs/img/icon.svg" width="120" alt="Cubetto icon">

# Cubetto

**Custom figures for the first-generation FABA™, made from YouTube or your own audio files.**

![Status](https://img.shields.io/badge/status-beta-blueviolet?style=for-the-badge)
![Version](https://img.shields.io/badge/version-1.0.3-E3262B?style=for-the-badge)
![License](https://img.shields.io/badge/license-GPL--3.0-blue?style=for-the-badge)

![Android](https://img.shields.io/badge/Android-8.0%2B-3DDC84?style=flat-square&logo=android&logoColor=white)
![Kotlin](https://img.shields.io/badge/Kotlin-2.0-7F52FF?style=flat-square&logo=kotlin&logoColor=white)
![Jetpack Compose](https://img.shields.io/badge/Jetpack%20Compose-Material%203-4285F4?style=flat-square&logo=jetpackcompose&logoColor=white)
![yt-dlp](https://img.shields.io/badge/yt--dlp-built--in-FF0000?style=flat-square&logo=youtube&logoColor=white)
![ABI](https://img.shields.io/badge/ABI-arm64--v8a-555555?style=flat-square)
![FABA](https://img.shields.io/badge/FABA™-1st%20generation-FFD43B?style=flat-square)

🌍 **App available in:** English · italiano · español · català · français · Nederlands

</div>

> [!WARNING]
> **Beta.** The file format is identical to myfaba-hacks and has been tested on a first-generation
> FABA™, but not on every box. Before copying, the app backs up the folders it overwrites, but use it at your own risk.

## 🙏 Acknowledgements

Cubetto is built on the work of the original **[myfaba-hacks](https://github.com/wansors/myfaba-hacks)** project by
[wansors](https://github.com/wansors) and its contributors: the MKI file cipher, the NFC tag format and the figure ID
registry all come from there. Cubetto brings what `redele.py` does for the first-generation FABA™ into an Android app.
Thank you!

**How it works:** link or file → MP3 → playlist → FABA™ format with the chosen ID → USB copy to the box → NFC tag writing.

## 📑 Contents

- [🛡️ No piracy](#️-no-piracy)
- [📱 Screens](#-screens)
- [✨ Features](#-features)
- [🏷️ NFC tags and DIY figures](#️-nfc-tags-and-diy-figures)
- [🎧 Track format](#-track-format)
- [🧰 Requirements](#-requirements)
- [🛠️ Build](#️-build)
- [🗂️ Project structure](#️-project-structure)
- [⚖️ Trademarks and disclaimer](#️-trademarks-and-disclaimer)
- [📄 License](#-license)

## 🛡️ No piracy

> [!IMPORTANT]
> **Cubetto does not decrypt FABA™ files, on purpose.** The app can only *write* your own tracks in the box format:
> it never reads, converts or exports the audio of original figures, so that it cannot be used for piracy.
>
> The developer does not support any form of piracy of original FABA™ content. Original figures and stories have a
> quality (voices, music, illustrations, editorial care) that cannot be compared with what you can make with Cubetto.
> **Cubetto is only a tool to add what original figures cannot offer**, such as your family's favourite songs or a
> grandparent's recordings: it does not replace them. If you love the FABA™, support its makers by buying original figures.

## 📱 Screens

<table>
  <tr>
    <td align="center"><img src="docs/img/screenshots/libreria.png" width="240" alt="Library"><br><b>Library</b></td>
    <td align="center"><img src="docs/img/screenshots/statuina.png" width="240" alt="Figure details"><br><b>Figure</b></td>
    <td align="center"><img src="docs/img/screenshots/aggiungi.png" width="240" alt="Add tracks"><br><b>Add tracks</b></td>
  </tr>
  <tr>
    <td align="center"><img src="docs/img/screenshots/scegli-id.png" width="240" alt="Choose an ID"><br><b>Choose an ID</b></td>
    <td align="center"><img src="docs/img/screenshots/box.png" width="240" alt="USB box"><br><b>USB box</b></td>
    <td align="center"><img src="docs/img/screenshots/nfc.png" width="240" alt="Write the NFC tag"><br><b>NFC tag</b></td>
  </tr>
</table>

<sub>Images from the Italian mockups in <code>docs/mockup/</code>: the real app may differ in the details.</sub>

## ✨ Features

- 🧭 **Built-in guide**: it opens on first launch and again with the **?** button; it explains every step, and the USB box in detail.

### 🎵 Tracks
- **From YouTube**: paste a link or use *Share* from the YouTube app. Playlists become several tracks.
- **From the device**: MP3, M4A, OPUS, WAV and even videos, of which only the audio is used.
- **Options**: volume levelling (`loudnorm`), silence trimming, split by chapters, SponsorBlock, mono.
- **Background downloads** with a notification.
- **Always up-to-date yt-dlp**: downloaded from the GitHub releases through
  [youtubedl-android](https://github.com/JunkFood02/youtubedl-android).
  - It checks at most once a day, and downloads only when there is a new version.
  - You can force it from *Settings → Check now*.

### 🧸 Figures
- Name, colour, photo and favourites.
- Tracks reordered by drag and drop, with a built-in audio player.
- **ID picker** based on the [myfaba-hacks `TAGS.md` registry](https://github.com/wansors/myfaba-hacks/blob/main/TAGS.md),
  bundled with the app and updatable from GitHub.
  - **Suggested**: IDs of figures sold in languages *other* than the app's, plus unused IDs.
  - You can always choose freely.
  - The app warns you when an ID belongs to a figure in your language or to another of your figures.
- **Export/import** as JSON: it holds only the links, the audio is downloaded again.

### 🔌 Box over USB OTG
- Access through [libaums](https://github.com/magnusja/libaums), no root needed.
- When you plug in the box, the app opens and copies the changed figures by itself.
- Fast reading: folders are read straight from the FAT32 file system and appear as they are found.
- **Backup** of a folder before overwriting it, with restore.
- Checks the free space and the 99-track limit, and copies only the changed tracks.
- Safe eject like a computer does: the box flushes its cache and the medium is ejected.

### 📡 NFC
- **Writes** the text `02190530{ID}00` at the exact position read by the box (page 7 byte 2 → page 10 byte 3).
  - Works on NTAG203/213/215/216, and the tag is read back to verify it.
  - A **Help** box explains which tags to buy and how to build a figure.
- **Reads** a figure and recognises its ID, and copies it to a blank tag.
- Warns about non-NXP clone chips (UID `1D…`/`FF…`).

## 🏷️ NFC tags and DIY figures

**Recommended tags:** genuine NXP NTAG213 or NTAG215. Many cheap clones are not read by the FABA™
(see [FAQ_TAGS.md](https://github.com/wansors/myfaba-hacks/blob/main/FAQ_TAGS.md)). These work:

- [Amazon IT](https://www.amazon.it/dp/B07Q7FZDSC)
- [Amazon ES](https://www.amazon.es/dp/B07Q7FZDSC)
- [Amazon DE](https://www.amazon.de/gp/product/B07Q43PXMR/)

**DIY figures:** just cut out a piece of cardboard and glue the tag underneath, or 3D-print a base:

- [Base template for FABA™ figures](https://www.printables.com/model/1378409-base-template-for-faba-audioplayer-figurines/files)
- [FABA™ disc with photo frame](https://www.printables.com/model/1876485-faba-disc-with-photoframe/comments/3668118)

## 🎧 Track format

| Step | Details |
|---|---|
| 🎚️ Conversion | Every source goes through **the same ffmpeg command**: first audio track, filters, CBR joint-stereo MP3 at 44.1 kHz (96 kbps, 128 kbps without mono) |
| 🏷️ Tags | Byte-for-byte identical to myfaba-hacks' `redele.py`: ID3v2.4 with only the `K{ID}CP{NN}` title (UTF-8) and mutagen's padding |
| 🎼 Audio | Copied as is after conversion, with no other changes |
| 🔐 Cipher | 3-bit rotation and XOR with the key `30 05 19 20` |
| 📁 Path | `MKI01/K{ID}/CPnn.MKI`, with real 8.3 names and the ARCHIVE attribute, like a copy from a PC |

## 🧰 Requirements

- 📱 Android 8.0+ (API 26), **arm64-v8a** only.
- 🔌 A **USB OTG** cable or adapter: the first-generation FABA™ shows up as a USB stick.
- 🏷️ Genuine NXP **NTAG213/NTAG215** tags (see [above](#️-nfc-tags-and-diy-figures)).

## 🛠️ Build

```bash
./gradlew testDebugUnitTest assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```

- **Signed release**: `./gradlew assembleRelease` signs the APK with the key set in
  `~/.cubetto-signing/keystore.properties` (or in the file named by the `CUBETTO_KEYSTORE` variable), with
  `storeFile`, `storePassword`, `keyAlias` and `keyPassword`. Without that file the release APK is unsigned.
- Translations are edited in `tools/strings.py`, then regenerated with `python3 tools/strings.py`.
- To compare tracks with the original ones from the box:
  `FABA_ORIGINAL_DIR=/path/K0016 ./gradlew testDebugUnitTest`.
- To compare them with a folder made by `redele.py` (`in_k1000/` with the MP3s, `out_k1000/K1000/` with the .MKI files):
  `MYFABA_DIR=/path ./gradlew testDebugUnitTest`.

## 🗂️ Project structure

| Path | Contents |
|---|---|
| `faba/` | MKI cipher, ID3 tags, MP3 frames, NFC tag layout, ID registry and suggestions |
| `ytdlp/` | yt-dlp, ffmpeg conversion, download worker |
| `usb/` | box access, FAT32 reading, figure writing, backup, restore and eject |
| `nfc/` | tag reading and writing in reader mode |
| `playback/` | audio player (Media3) |
| `data/` | Room database and settings |
| `ui/` | Compose screens (Library, Figure, Add, Create/Choose ID, Box, Settings) |

## ⚖️ Trademarks and disclaimer

- FABA™ and myFABA™ are trademarks of their respective owners (FABA S.r.l.).
- Cubetto is an independent project: it is not affiliated with, sponsored or endorsed by FABA S.r.l.
- Figure names are mentioned only to identify their ID.
- ⛔ First-generation (offline) FABA™ only. **FABA+ is not supported.**
- Use Cubetto only with content you have the right to download and listen to.

## 📄 License

GPL-3.0, like [myfaba-hacks](https://github.com/wansors/myfaba-hacks), from which the cipher algorithm comes,
and like youtubedl-android. Fredoka and Nunito fonts under the SIL Open Font License.
