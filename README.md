# MillMusic

Saját használatú Android zenelejátszó (Samsung S24-re hangolva).

## 1. szakasz – ami benne van
- **YouTube** a hivatalos mobil oldalon keresztül, **lezárt képernyőn is szól**; bejelentkezve (Premium) reklám nélkül
- **Helyi zenék**: mappa vagy fájlok kiválasztása, ID3 címkék (előadó, műfaj, album) beolvasása
- **Felhős mp3**: közvetlen link, Firebase Storage, Google Drive, Dropbox
- **Online rádió**: Radio Browser adatbázis, magyar adók, műfajok, kedvencek
- **Playlistek**: évszakos (Téli, Tavaszi, Nyári, Őszi, Karácsony, Halloween – dátum alapján előre hozva), hangulat (Melankolikus, Érzelmes, Szomorú, Boldog) és saját listák
- **Okos ajánlás**, ha nem lista szól: előadó + műfaj + a cím szavai alapján; ha nincs jó találat a könyvtárban, YouTube-keresés (API-kulccsal)
- **Megosztás**: YouTube app → Megosztás → MillMusic
- Értesítés + lezárt képernyős vezérlő, fülhallgató/Bluetooth gombok

## Android Auto
- Lejátszókártya a navigáció mellett (borítóval), teljes képernyős lejátszó, kormánygombok
- Menü fülek: **Most** (aktuális évszakos lista + legutóbb hallgatott), **Playlistek**, **Rádiók**, **Ajánlott**
- Hangvezérlés: „Hey Google, játszd le a Karácsony listát a MillMusicban”
- Egyszeri beállítás (mert nem Play Áruházból jön): Android Auto beállítások → a verziószámra 10× koppintás → ⋮ → Fejlesztői beállítások → **Ismeretlen források** ✓
- A lejátszás motorja a telefonos app, ezért a MillMusicot telefon-újraindítás után egyszer meg kell nyitni (utána mehet háttérbe)

## APK készítése
**GitHub Actions (ajánlott):** minden push után az Actions fülön a „Build APK” futás végén a *MillMusic-apk* artifactban van az APK. Telefonon: letöltés → telepítés (ismeretlen forrás engedélyezése).

**Android Studio:** Open → ez a mappa → Build → Build APK(s).

Az APK egy fix kulccsal (`app/millmusic.jks`) van aláírva, így az újabb verziók adatvesztés nélkül felülírják a régit.

## Első indítás után (Samsung)
1. Értesítések engedélyezése (a lejátszásvezérlőhöz kell).
2. Listák fül → **Engedélyezés** (korlátlan akkumulátorhasználat).
3. Beállítások → Akkumulátor → Háttérhasználati korlátok → **Soha nem alvó alkalmazások** → MillMusic.
4. ⚙️ → YouTube bejelentkezés (Premium) és opcionálisan YouTube Data API-kulcs.

## Felépítés
- `app/src/main/assets/index.html` – a teljes felület (egy HTML-fájl, keretrendszer nélkül)
- `MainActivity.kt` – WebView-k, natív híd, fájlválasztás, ID3 olvasás
- `PlaybackService.kt` – előtérszolgáltatás, MediaSession, értesítés
- `LocalServer.kt` – a felület és a helyi zenefájlok kiszolgálása (`https://millmusic.local`)
- `YtScript.kt` – a YouTube oldalba injektált vezérlő szkript
- `ArtProvider.kt` – borítóképek (YouTube, rádió logó, mp3 borító, lista-ikonok)
- `Library.kt` – könyvtár-pillanatkép az Android Auto menühöz

Külső függőség nincs, csak Android rendszer-API-k.
