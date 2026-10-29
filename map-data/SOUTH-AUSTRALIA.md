# South Australia offline data

The builder processes the complete public Geofabrik South Australia extract. It does not use a CBD query or a list of town-sized downloads. It produces an external SQLite database, not an app asset or a release.

## Pinned source

- URL: https://download.geofabrik.de/australia-oceania/australia/south-australia-261028.osm.pbf
- Publisher checksum: the same URL with `.md5` appended.
- Downloaded bytes: 68128053.
- Publisher MD5: `b684793065cde31410547452eb92c5aa`.
- SHA256: `80742f7a78783b78ed47547a84a3a7b96714bf285d1cc652abf2f2db59279905`.
- PBF replication timestamp: `2026-10-28T21:21:02Z`.
- PBF header bbox, west/south/east/north: `[128.999343,-39.831564,141.007272,-25.993034]`.

MD5 checks the received bytes against the publisher's checksum over HTTPS. It is not an authenticity signature. The header bbox describes the source extract, not a claim that every location contains a supported feature. Geofabrik includes complete ways crossing its extraction boundary.

## Reproduce

Run from the isolated worktree. Python 3.11 is used for the recorded build. All dependencies, downloads and generated databases stay outside Git.

```sh
CACHE=/home/jaxon/.cache/jwmaps/sa
URL=https://download.geofabrik.de/australia-oceania/australia/south-australia-261028.osm.pbf
mkdir -p "$CACHE"
python3 -m venv "$CACHE/venv"
"$CACHE/venv/bin/pip" install -r tools/requirements-map.txt
curl --fail --location --retry 2 "$URL" -o "$CACHE/south-australia-261028.osm.pbf"
curl --fail --location --retry 2 "$URL.md5" -o "$CACHE/south-australia-261028.osm.pbf.md5"
"$CACHE/venv/bin/python" -m unittest discover -s tools -v
"$CACHE/venv/bin/python" tools/build_sa_map.py \
  --source "$CACHE/south-australia-261028.osm.pbf" \
  --publisher-md5 "$CACHE/south-australia-261028.osm.pbf.md5" \
  --source-url "$URL" \
  --output "$CACHE/south-australia-v3.sqlite" \
  --receipt "$CACHE/build-receipt.json" \
  --evidence "$CACHE/build-evidence.md"
```

The builder itself makes no network requests. Rebuilding from the verified cached PBF is offline. The source MD5 must match before conversion starts. The disk-backed `.stage.sqlite` file contains original converted features and tile membership for inspection. The final database replaces its destination only after `PRAGMA integrity_check` succeeds. Use a separate output path for concurrent builds.

## SQLite contract

```sql
CREATE TABLE metadata(key TEXT PRIMARY KEY, value TEXT NOT NULL);
CREATE TABLE tiles(
  x INTEGER NOT NULL,
  y INTEGER NOT NULL,
  payload BLOB NOT NULL,
  feature_count INTEGER NOT NULL,
  PRIMARY KEY(x,y)
);
```

Every metadata value is JSON, including strings and numbers. Required keys are `formatVersion`, `id`, `version`, `gridStep`, `bbox`, `sourceUrl`, `attribution`, `featureCount`, `tileCount`, `kinds`, `sourceSha256`, and `sourceTimestamp`. The pack ID is `south-australia`, version is 3, format version is 1, and grid step is 0.025 degrees.

Each payload is deterministic gzip containing compact UTF-8 GeoJSON. Its pack name is `South Australia` and attribution is `© OpenStreetMap contributors, ODbL 1.0`. The payload bbox is the tile cell, not the bounds of every intact feature in it.

```sql
-- x=floor(lon/0.025), y=floor(lat/0.025), including negative latitude.
SELECT x,y,payload,feature_count FROM tiles
WHERE x BETWEEN :x - 1 AND :x + 1
  AND y BETWEEN :y - 1 AND :y + 1;
```

The primary-key index supports this query. A missing tile means no successfully converted supported feature was assigned to that cell. It does not mean that OSM or the terrain is empty. Read the omission counts too.

## Geometry and identity

Supported kinds are `road`, `building`, `water`, `park`, and `poi`. Only LineString, Polygon and Point are emitted.

- Highway ways become roads, including paths and tracks. They are never closed by guesswork.
- Building, water and green-area ways must be closed. Libosmium assembles eligible multipolygon and boundary relations. Each outer polygon becomes a separate source feature, retaining its holes.
- Water selection includes natural water/bay, riverbank/dock and reservoir/basin polygons. Parks include parks, reserves, gardens, recreation grounds, grass, forest, meadow, village green, wood and national-park boundaries. These are rendering categories, not access guarantees.
- Named amenity, shop and tourism nodes become POIs. Named ways in those categories also get a point derived from their real polygon or line. `geometrySource` identifies these derived label points.
- Coordinates are WGS84 longitude/latitude, restricted to longitude ±180 and latitude ±85 and rounded to seven decimals. Non-finite coordinates, invalid geometry, names exceeding 300 characters, paths exceeding 20000 points and polygons exceeding 64 rings are rejected with counts. Polygons are not repaired or simplified to pass validation.
- Building `height` in metres is preferred. Positive `building:levels` derives height at three metres per level and is labelled `heightSource`. Missing or unparseable height emits `height:8` and `renderingHeightDefault:true`. That is a rendering default, not a measurement.

Points use their floor cell. Lines and polygon boundaries are indexed into every intersecting closed tile cell, including cells far from their centroid. Recursive intersection checks reject empty subranges without allocating a statewide bounding-box grid.

Polygons touching more than 16 cells are clipped by actual geometric intersection. Clipping preserves holes and may produce several Polygon pieces in a cell. Small features remain intact in every touched cell. A large polygon's boundary-only contact retains the intact original because a Polygon cannot encode a degenerate line or point.

Deduplicate identical top-level Feature `id` values when merging nine tiles. Do not deduplicate all pieces by `osmId` or `sourceFeatureId`: that would drop distinct outer parts, POIs on buildings, or clipped pieces. `sourceFeatureId` groups tile fragments for source-feature counting, not runtime geometry deduplication.

`featureCount` and `kinds` count unique converted source geometry features before tile duplication and clipping. They are not counts of unique OSM objects. Each tile's `feature_count` counts its stored Feature rows. Nine-tile limits sum these rows and their coordinates before any deduplication. No tile or window is truncated to hide a runtime-limit failure.

## Evidence and integration boundary

`/home/jaxon/.cache/jwmaps/sa/build-receipt.json` records source hashes, sizes, bounds, timestamp, source object counts, conversion omissions, relation assembly failures, large-polygon identities, output hash, tile maxima and all town sample names. `build-evidence.md` is its readable summary. Town samples use actual OSM place nodes for Adelaide, Mount Gambier, Port Augusta, Coober Pedy, Renmark and Kingscote, then query their nine-tile windows.

Raw source, receipts, audit logs and data binaries are not committed. Parent-owned runtime integration and release work are separate. No Android build or emulator result is claimed by this data build.

OSM data is available under ODbL 1.0. Retain OpenStreetMap attribution. Publishing an adapted database may require providing it under ODbL and offering the corresponding database or alteration material. See https://www.openstreetmap.org/copyright and https://opendatacommons.org/licenses/odbl/1-0/.
