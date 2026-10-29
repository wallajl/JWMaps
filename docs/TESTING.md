# Verification for JWMaps 0.2.0

These checks cover the South Australia map explorer and GPS-follow implementation. They do not establish Android Auto admission or compatibility with a physical Ford Ranger.

## Statewide data

The full dated Geofabrik South Australia extract was downloaded over HTTPS and checked against its publisher checksum. Its OSM replication timestamp is 2026-10-28T21:21:02Z.

The resulting database contains **757,011 converted source geometry features** in **114,325 tiles**. These are geometry counts, not unique OSM objects. The database is 285,925,376 bytes. [Public provenance, counts and omissions](../map-data/SOUTH-AUSTRALIA-RELEASE.json) and [reproduction instructions](../map-data/SOUTH-AUSTRALIA.md) are included.

An independent audit decompressed every tile, checked schema, coordinates, geometry validity, tile membership and identity consistency, reconciled source features with the staging database, and reconstructed the ten largest clipped polygons within the documented coordinate-rounding tolerance. SQLite `integrity_check` passed. No feature or tile window was silently truncated.

The largest compressed tile is 601,456 bytes, below the runtime's 1 MiB blob cap. The largest nine-tile window is 27,278,462 uncompressed bytes, below the 32 MiB window cap. It contains at most 57,920 stored feature rows and 357,911 coordinates, below the separate safety limits.

A decimal-grid boundary regression was found and fixed before release. One missing boundary membership was repaired, and the complete output was audited afterward.

## Automated checks

The final local run covered 42 distinct Java/Android tests and 23 Python tests. Variant executions were:

| Group | Cases | Failures/errors | Skipped |
| --- | ---: | ---: | ---: |
| Core Java | 11 | 0 | 0 |
| Android debug | 30 | 0 | 0 |
| Android standalone release | 30 | 0 | 0 |
| Android Play | 31 | 0 | 1 |
| Python publisher/map tools | 23 | 0 | 0 |

The intentional Play skip is an external-APK-staging scenario that does not apply to that build. The opt-in real-data test ran locally against the actual statewide database in all three Android variants. CI exercises labelled synthetic fixtures without downloading the statewide dataset.

Native SQLite and Android rendering tests loaded genuine data for Adelaide, Mount Gambier, Port Augusta, Coober Pedy, Renmark and Kingscote, plus the densest metropolitan window. These tests simulated location fixes and checked GPS-follow across distant windows. They are not real GPS reception or car-host tests.

Regression coverage includes first-fix following, street-scale default zoom, pan-to-pause, explicit resume, preservation across pack reloads, stale asynchronous callbacks, independent phone/car cameras, empty rural windows, malformed metadata, unsupported schemas, decompression limits, signed regional activation, rollback refusal, same-version corruption repair and mobile-data consent.

Debug, release and Play lint passed. Signed standalone APK and Play bundle builds passed. The standalone APK uses the same signing identity as 0.1.1. The Play APK omits external-package-install permission.

## Actual Android phone emulator

The signed 0.2.0 APK was installed over 0.1.1 in an API 35 Android emulator. For the pre-release offline exercise, the verified real database was copied into the isolated emulator through root ADB. Wi-Fi and mobile data were disabled. Simulated GPS fixes then moved between Mount Gambier, Port Augusta and Coober Pedy. The app loaded the correct nearby places and map windows without a network connection or pressing Follow me between towns.

![JWMaps in Coober Pedy with simulated GPS and offline statewide data](screenshots/phone-emulator.png)

This image is from an Android phone emulator, not a Samsung or Ford display. The pre-release database copy is not evidence of a public network download. The earlier 0.1.0-to-0.1.1 test exercised Android's system APK installer separately.

## Update and security boundaries

Regional files are streamed to disk, checked against signed size/hash metadata, validated read-only, then atomically activated. Tests prove that corrupt or downgraded replacements preserve known-good data. Same-version local corruption can be repaired from the signed bytes. Regional state is separate from the CBD setup fallback and APK revisions.

No Android runtime dependency was added for statewide storage. The pinned build-only osmium, Shapely, NumPy and requests versions were queried against OSV with no advisory matches at the time checked. This is not a guarantee that dependencies are vulnerability-free.

## Not yet verified or implemented

- Samsung hardware and real GPS reception have not been tested.
- No Ford/SYNC or Desktop Head Unit test was performed. Car App Library sessions, templates, host validation, shared rendering and location/window behavior were tested separately. Trusted Play distribution and a parked vehicle test remain necessary.
- The map remains north-up. Bearing changes the location marker, not the map's orientation.
- Route calculation, turn-by-turn instructions, Waze data and worldwide coverage are not implemented.
- Statewide means the supported OSM geometry in this extract. Unsupported or malformed source features are counted as omissions. Decorative terrain is not measured land cover or elevation.
