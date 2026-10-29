# JWMaps

A Minecraft-inspired view of real-world maps for Android, with an Android Auto POI interface and signed app/data updates. No mapping subscription or GitHub login is required.

**This is an early map-explorer preview, not a finished navigation app.** It does not yet calculate routes, provide turn-by-turn directions, import Waze traffic or download worldwide coverage.

## Install on an Android phone

1. Open [the latest release](https://github.com/wallajl/JWMaps/releases/latest).
2. Download `JWMaps-<version>.apk` and install it on Android 9 or later. Android may ask you to allow installation from your browser or file manager.
3. Open JWMaps. The Adelaide city-centre preview works without location permission. Choose **My location** to enable position tracking.
4. Use **Updates** to check manually. Later compatible APKs are downloaded and verified, but Android still asks you to approve installation.

The dashed map boundary marks available coverage. If you are outside it, the app says so instead of pretending the Adelaide preview is your location. The first map is deliberately small.

## What works

- Real OpenStreetMap roads, building footprints and named places, with a block-inspired renderer, decorative terrain texture and day/night car maps.
- Pan, zoom, recenter and local location/heading display. No saved trip history or location uploads to GitHub.
- Signed release metadata, size/hash checks, APK package/version/signing checks and rollback protection.
- Independently versioned map packs with atomic activation and a known-good fallback.
- A native Android Auto POI service with host-controlled map/list templates and release host validation.

Building extrusion uses OSM heights when present and a documented visual default otherwise. Decorative terrain is styling, not a land survey. No Mojang textures or logos are bundled.

## Android Auto has a separate installation requirement

Installing the standalone APK does **not** guarantee it appears in a real car's Android Auto launcher. Google's supported real-vehicle testing route for Car App Library apps requires trusted distribution, such as a Play internal test track or Internal App Sharing.

JWMaps therefore has two builds:

| Build | App updates | Map updates | Intended use |
| --- | --- | --- | --- |
| `release` APK | Verified GitHub APK, with Android approval | Signed GitHub releases | Standalone phone preview |
| `play` app bundle | Google Play | Signed GitHub releases | Trusted-distribution setup for Android Auto |

The `play` build removes external-package-install permission and disables the GitHub APK installer. It targets API 36. A Play Console account, tester setup and a real-car check are still required. This repository does not claim a tested Ford installation.

See [update channels and release instructions](docs/UPDATES.md).

## Build and test

Use JDK 17, Android SDK platform 36 and SDK build tools. Gradle 8.11.1 is pinned with a distribution checksum.

```sh
./gradlew :core:test :app:testDebugUnitTest :app:testPlayUnitTest
./gradlew :app:lintDebug :app:lintPlay :app:assembleDebug
```

For a standalone signed build, supply the existing dedicated key through the four `JW_SIGNING_*` environment variables documented in [UPDATES.md](docs/UPDATES.md), then run `./gradlew :app:assembleRelease`. Never commit a key store or password.

Build a Play upload bundle with `./gradlew :app:bundlePlay`. Publishing or acceptance by Google Play is a separate step.

Publisher and map-tool tests:

```sh
python -m pip install -r tools/requirements-release.txt
python -m unittest discover -s tools -p 'test_*.py' -v
```

## Project references

- [First-release requirements and update protocol](docs/SPEC.md)
- [Map provenance, limitations and data licence](map-data/README.md)
- [Releases](https://github.com/wallajl/JWMaps/releases)

Software is MIT licensed. OpenStreetMap-derived data is separately licensed under ODbL 1.0. JWMaps is independent of Mojang, Microsoft, Google, Waze and Ford.
