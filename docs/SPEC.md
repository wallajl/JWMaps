# JWMaps first release

## Owner requirements

- Public GitHub repository named JWMaps. Public supersedes the original private-repository request.
- Installable Android APK, designed for a Samsung phone and projection to a Ford Ranger using Android Auto.
- Minecraft-inspired map of real roads and surroundings, without subscribing to the referenced app.
- Poll this repository for releases, download app updates and separately versioned map data as needed.
- Never embed an owner GitHub token or private signing key in the app or repository.

## First-release scope

A real-data map explorer and POI app, not a completed turn-by-turn navigation product. The app must use genuine OSM-derived roads, buildings and places. Show the actual map-pack extent and a truthful out-of-coverage state. A bundled Adelaide city-centre pack is a generic preview, never the owner's private address. No invented buildings or routing results may be presented as real data.

The phone UI shows the map, location status, nearby named places and an updates screen. Android Auto uses the real POI category and supported map/content templates. Do not impersonate a media app or bypass host validation. Use readable native car controls and a restrained block-inspired map renderer, without Mojang assets or logos. Respect light/dark host mode, stable map bounds and accessible targets. Explain that APK installation is distinct from admission to the Android Auto launcher. A real-car test needs a supported trusted distribution channel. Do not claim vehicle compatibility from compilation alone.

Waze's proprietary routes/alerts are not part of this release. Full route calculation, live traffic, arbitrary worldwide coverage and a tested Ford display are not yet delivered.

## Update protocol

The fixed source is `https://api.github.com/repos/wallajl/JWMaps/releases/latest`. Only published non-prerelease stable releases are accepted. Fetch assets by exact name from that release. Follow only HTTPS GitHub release-download/CDN redirects; no arbitrary manifest URLs, cleartext, userinfo, alternate ports or traversal names.

Release assets:

- `update-manifest.json`: exact UTF-8 JSON bytes.
- `update-manifest.sig`: base64 DER signature of those bytes, SHA256withECDSA using the pinned P-256 X.509 public key in the app.
- `JWMaps-<version>.apk`: app package.
- `<pack-id>-v<integer>.geojson`: one independently versioned map pack.

Manifest shape:

```json
{
  "schemaVersion": 1,
  "channel": "stable",
  "packageName": "com.jw.maps",
  "app": {
    "versionCode": 1,
    "versionName": "0.1.0",
    "asset": "JWMaps-0.1.0.apk",
    "sha256": "64 lowercase hexadecimal characters",
    "sizeBytes": 123,
    "minSdk": 28
  },
  "data": {
    "id": "adelaide-cbd",
    "version": 1,
    "asset": "adelaide-cbd-v1.geojson",
    "sha256": "64 lowercase hexadecimal characters",
    "sizeBytes": 123,
    "formatVersion": 1
  }
}
```

Both sections are required. Integer fields must be positive actual JSON integers, not coerced decimals or strings. Enforce strict safe names, SHA-256, advertised length and streamed-size limits. Reject invalid schema, wrong package/channel, corrupt signatures, unsupported formats and downgrades. A newly published APK does not require a data version bump. A data-only release with the same app version must still work.

App updates are downloaded privately, checked against signed metadata, then checked for package ID, exact version and matching currently installed signing certificates using PackageManager before exposing the installer. Android user approval is required. Never trigger an installer from the car screen or background; no update prompts over driving UI. Installation from Play channels may use a different certificate; fail closed and explain the supported channel instead of bypassing signatures.

Data updates are hashed, size-bounded, parsed and validated, then atomically activated. A failed/truncated/malformed update must preserve the last known-good pack. Delete partial downloads. No arbitrary archive extraction. Store release state and version independently of app version. No owner coordinates, saved locations or trip histories are uploaded to GitHub.

Periodic network-connected checks run approximately every 12 hours with WorkManager, plus a phone manual check and throttled startup check. WorkManager is best-effort under Android battery policies, not a precise timer. Data downloads may occur in the background; an APK is downloaded/staged with a phone-side install action. Updates need no GitHub authentication. Handle GitHub 403/429 and offline state without repeated tight retries.

## Map format

GeoJSON FeatureCollection with `pack` metadata, `bbox` and `features`. `pack` has `id`, positive integer `version`, `formatVersion:1`, `name`, `attribution` and `sourceUrl`. Feature geometry is LineString, Polygon or Point. `properties.kind` is `road`, `building`, `water`, `park` or `poi`. Names may be absent. OSM IDs and tags that affect rendering may be preserved. Heights may be OSM-sourced or an explicitly documented rendering default, not a claimed measured height. Validate finite WGS84 coordinates, nesting, lengths and count limits before drawing. OSM attribution must remain visible on phone and car.

## Test boundaries

- Signed manifest to update decisions, including data-only updates, bad signatures, strict parsing and downgrade refusal.
- Downloaded bytes to hash/size verified artifacts, preserved known-good data and APK identity checks.
- Genuine GeoJSON to safe map model, coordinate projection and coverage reporting.
- Phone activity and CarAppService runtime smoke tests, plus build/lint checks.

Tests exercise public behavior, with a failing test observed before implementation. Local emulation or DHU and live device tests must be reported separately. A build-only result is not a tested APK.
