# Stemvolger Teleprompter (Android)

Een teleprompter-app voor Android die **je stem volgt**: terwijl je je tekst voorleest, luistert de
app mee via spraakherkenning en scrollt de tekst automatisch naar de zin die je uitspreekt.

## Functies
- **Stemvolgen** – herkent wat je zegt en houdt de huidige regel bij de rode leesmarkering. Al
  uitgesproken tekst wordt grijs, het volgende woord geel. Overgeslagen zinnen? De app springt mee.
- **Auto-modus** – klassiek automatisch scrollen met instelbare snelheid (zonder microfoon).
- Tekst met je vinger verschuiven om op een andere plek verder te gaan.
- Lettergrootte aanpassen, **spiegelen** voor gebruik achter teleprompterglas.
- Talen: Nederlands (NL/BE), Engels, Duits, Frans, Spaans, Italiaans, Portugees.
- Tekst plakken, `.txt`-bestand openen of tekst delen vanuit een andere app.
- Geen advertenties, geen tracking (zie [PRIVACY.md](PRIVACY.md)).

Vereist Android 8.0+ en een spraakherkenningsdienst (standaard aanwezig via de Google-app).
Tip: download in Android-instellingen de offline spraakherkenning voor je taal, dan werkt het ook
zonder internet en reageert het sneller.

## Bouwen
Vereist JDK 17+ en de Android SDK (of open de map gewoon in Android Studio).

```bash
./gradlew assembleDebug        # APK om direct te installeren: app/build/outputs/apk/debug/
./gradlew testDebugUnitTest    # unit-tests van de spraak-tekstkoppeling
./gradlew bundleRelease        # App Bundle (.aab) voor de Play Store
```

Elke push bouwt ook automatisch via **GitHub Actions** (tab *Actions* → laatste run → *Artifacts*):
`teleprompter-debug-apk` en `teleprompter-release-aab`.

### Direct op je telefoon installeren
1. Download de debug-APK (Actions-artifact of `app/build/outputs/apk/debug/app-debug.apk`).
2. Open hem op je telefoon en sta "installeren uit onbekende bronnen" toe.

## Publiceren in de Google Play Store
1. **Upload-sleutel maken** (eenmalig, bewaar hem goed!):
   ```bash
   keytool -genkeypair -v -keystore upload-keystore.jks -alias upload \
     -keyalg RSA -keysize 2048 -validity 10000
   ```
2. **Ondertekenen** – lokaal: maak `keystore.properties` in de projectmap (staat in `.gitignore`):
   ```properties
   storeFile=upload-keystore.jks
   storePassword=...
   keyAlias=upload
   keyPassword=...
   ```
   Of via GitHub: voeg bij *Settings → Secrets and variables → Actions* de secrets
   `KEYSTORE_BASE64` (`base64 -w0 upload-keystore.jks`), `KEYSTORE_PASSWORD`, `KEY_ALIAS` en
   `KEY_PASSWORD` toe. De workflow levert dan een ondertekende `.aab`.
3. Maak een ontwikkelaarsaccount op <https://play.google.com/console> (eenmalig $25).
4. *App maken* → upload `app-release.aab` (Play App Signing aan laten staan).
5. Vul de winkelvermelding in: icoon 512×512 en feature graphic 1024×500 staan in
   [`store/`](store/), plus minimaal 2 screenshots van je telefoon.
6. *App-content*: privacybeleid-URL (publiceer [PRIVACY.md](PRIVACY.md), bv. via GitHub Pages of de
   GitHub-link naar het bestand), Data safety: "geen gegevens verzameld", doelgroep, contentclassificatie.
7. Nieuwe persoonlijke ontwikkelaarsaccounts moeten eerst een **gesloten test** draaien
   (≥ 12 testers, 14 dagen) voordat productie-publicatie mag.

Verhoog bij elke nieuwe versie `versionCode` (en `versionName`) in `app/build.gradle.kts`.

## Hoe het stemvolgen werkt
`SpeechFollower` houdt Android's `SpeechRecognizer` continu luisterend (met tussentijdse resultaten).
`ScriptMatcher` vergelijkt de laatst gehoorde woorden (vergevingsgezind voor kleine
herkenningsfouten, hoofdletters en accenten) met de tekst in een venster rond de huidige positie en
kiest de best passende plek. Grote sprongen vereisen meer overeenkomende woorden, zodat een losse
misherkenning de tekst niet laat verspringen.
