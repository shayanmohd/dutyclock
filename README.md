# Dutyclock

Dutyclock is a one-time, offline countdown of the drivers' hours an EU or UK lorry, coach or van driver owes: breaks (45 minutes, or 15 then 30), daily and weekly driving, daily and weekly rest deadlines, reduced-rest compensation, the 56-hour week and 90-hour fortnight under Regulation 561/2006, AETR and the 2.5 to 3.5 t van scope, the GB domestic goods limits, and Road Transport (Working Time) breaks, the 60-hour week and the 48-hour average. The driver taps the mode they are in; an editable log, a Day Disc, a drive-home planner, alerts and a 28-day PDF personal record are built from those taps. It is advisory, not a tachograph, not an ELD and not a legal record.

Everything runs on the device. The app declares no network permission and sends nothing anywhere.

## Build

Requires JDK 17 and the Android SDK with platform 36.

```bash
./gradlew testDebugUnitTest
./gradlew assembleDebug
./gradlew bundleRelease       # needs keystore.properties, see below
```

`keystore.properties` and the `.jks` are not committed. Without them the release build stays
unsigned instead of failing:

```properties
storeFile=<slug>-upload.jks
storePassword=...
keyAlias=<slug>
keyPassword=...
```

## Layout

```
app/src/main/kotlin/com/mohdshayan/dutyclock/
  App.kt, MainActivity.kt   process and activity entry points
  di/                       ServiceLocator, the manual dependency container
  data/prefs/               DataStore settings (AppPrefs)
  data/db/                  Room database, entities and DAOs (when used)
  ui/theme/                 colour, type, shape and motion tokens, AppTheme
  ui/nav/                   AppNav and the type-safe routes
  core/                     the rules engine, planner, PDF, backup and CSV: plain Kotlin, no Android imports
    model/, time/           entries, segments, rule sets; the fixed UTC week and RTD reference periods
    engine/                 Replay (the reducer), RuleEngine (counters), AlertPlan, DayTotals, Fmt
    plan/                   HomePlanner, "can I make it home"
    pdf/                    PdfWriter (from Kickset), PdfText (WinAnsi folding), RecordPdf
    backup/                 JSON backup and CSV
  data/repo/                LogRepository: every write re-arms the alerts
  alerts/                   AlarmManager scheduling, alert and reschedule receivers, countdown notification
  ui/today, week, log, plan, records, settings, firstrun, catchup   one package per screen
  ui/components/            DayDisc, ModeBar, LimitRow, TachoIcons and shared pieces
app/src/test/               JVM unit tests
store/                      Play listing copy, icon, feature graphic, screenshots
docs/                       privacy policy and font licences
```

## Rules and sources

Every rule is a pure function in `core/` with golden JUnit tests built from worked examples in the
GOV.UK guide "Drivers' hours and tachographs: goods vehicles" (formerly GV262) and the Department for
Transport's Road Transport (Working Time) guidance. No optional derogation is applied (ferry or train
interruptions, multi-manning, the coach 12-day rule, consecutive reduced weekly rests abroad), and a
10-hour day or reduced rest counts only when the driver chooses it. Weeks are fixed Monday 00:00 to
Sunday 24:00 in UTC, as the tachograph records them.

## Bundled licences

- IBM Plex Sans Condensed (SemiBold, Bold) and Red Hat Text (Regular, Medium): SIL Open Font License
  1.1, texts in `docs/OFL-IBMPlexSansCondensed.txt` and `docs/OFL-RedHatText.txt`. Both texts also ship
  inside the app (`res/raw/licences.txt`, shown under Settings, Licences), as the licence requires.
- Android Jetpack, Kotlin and kotlinx libraries: Apache License 2.0.
- Google Play In-App Review: Play Core Software Development Kit Terms of Service.

## Licence

Copyright SocialSure Private Limited.
