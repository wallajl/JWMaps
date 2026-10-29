# Release and update channels

JWMaps has two separate kinds of update.

## Map-data updates

The app checks the latest published stable GitHub release. It verifies the release manifest with its pinned ECDSA public key, verifies the data file's SHA-256 and size, validates the map format and then activates it atomically. A bad update leaves the previous map in place.

Map revisions are independent of APK versions. A release can keep the same APK and ship a newer map pack. GitHub does not receive your location or trip history from this updater.

Checks run on a best-effort background schedule and can be triggered from the phone. Android battery restrictions and network availability can delay them.

## APK updates from GitHub

The standalone phone build can download a newer APK from the same signed release. It must match the package, advertised version and installed signing identity. Android requires installation permission and normally asks you to approve the update. JWMaps does not launch the installer from Android Auto or a background worker.

The first APK you install establishes the Android signing identity. Future APKs must use that same key. An unrelated debug, Play or Internal App Sharing certificate is not interchangeable.

## Android Auto installation is a separate gate

A successfully installed APK is not proof that the app will appear in a real car's Android Auto launcher. Google's documented testing route for Car App Library apps requires a trusted source, such as Google Play Internal App Sharing or an internal test track. Android Auto's developer 'Allow unknown sources' option explicitly does not cover Car App Library apps.

Internal App Sharing re-signs uploads with a separate certificate and is intended for temporary tests. An internal test track is the better ongoing distribution route. Play Console setup requires an owner-controlled account. This repository does not create or enroll that account.

For Play-distributed builds, use Play for APK updates and GitHub for map data. A Play build must remove `REQUEST_INSTALL_PACKAGES` and disable the external APK installer path. Google Play's permission policy does not permit a map app to use that permission for self-updates. Do not replace a Play-installed app with a differently signed GitHub APK. A Play signing mismatch is a reason to stop, not bypass certificate checks.

New phone-app submissions to Google Play must currently target Android 16, API 36. Android Auto projection is not the separate Android Automotive OS exception. The release build must meet the current target requirement before a Play upload.

Local phone emulator tests and Car App Library session tests do not establish acceptance on a Ford Ranger. Test the trusted distribution path and the real car while parked before relying on it.

## Publish a standalone release

1. Increase `versionCode` and `versionName` in `app/build.gradle` for an app change. For a data-only release, leave the app version unchanged and increase the pack's integer revision.
2. Run the complete test suite and lint. Build `assembleRelease` with the existing dedicated Android signing configuration provided through `JW_SIGNING_STORE`, `JW_SIGNING_STORE_PASSWORD`, `JW_SIGNING_KEY_ALIAS` and `JW_SIGNING_KEY_PASSWORD`. Never commit those values or the key store.
3. Verify the APK with Android SDK `apksigner verify --verbose --print-certs` and inspect package/version with `aapt dump badging`. Name it `JWMaps-<version>.apk`.
4. Generate metadata with `python tools/release_manifest.py --apk <APK> --data <PACK> --version-name <VERSION> --version-code <CODE> --key <PRIVATE_MANIFEST_KEY> --output <RELEASE_DIRECTORY>`. The manifest key is a separate P-256 private key. Its public half is pinned in `UpdateManifest`.
5. Create a draft release tied to the tested full commit SHA. Upload the APK, versioned GeoJSON, `update-manifest.json` and `update-manifest.sig`. Download the draft assets and compare their bytes, then publish it as a non-prerelease release. Verify anonymous downloads before directing users to install.

Version checks follow published stable releases, not every source commit. A source push runs CI but does not ship an update. Without the signing environment variables, Gradle produces an unsigned release APK. Never distribute that file.

The metadata tool signs supplied artifact bytes. It does not certify that an input file is a valid APK. APK identity and Android signing checks in step 3 are mandatory.

Keep secure backups of both signing keys. Losing the Android key prevents compatible standalone upgrades. Losing the manifest key requires a deliberately planned trust-key migration.

## Official references

- [Test Android apps for cars](https://developer.android.com/training/cars/testing)
- [Internal app sharing](https://support.google.com/googleplay/android-developer/answer/9844679)
- [Set up testing tracks](https://support.google.com/googleplay/android-developer/answer/3131213)
- [Draw custom maps](https://developer.android.com/training/cars/apps/library/draw-maps)
- [Google Play install-packages permission policy](https://support.google.com/googleplay/android-developer/answer/12085295)
- [Google Play target API requirements](https://support.google.com/googleplay/android-developer/answer/11926878)
