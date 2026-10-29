# Adelaide city-centre map data

The bundled preview contains genuine OpenStreetMap-derived geometry and named places from a bounded city-centre extract. `tools/fetch_map.py` uses the official OSM map API. It does not make map API requests from the phone or upload your location.

The initial command extent is south, west, north, east: `-34.927, 138.596, -34.918, 138.609`. GeoJSON stores its `bbox` in west, south, east, north order. OSM returns complete ways that cross extract edges. Those vertices are preserved, but the displayed coverage boundary remains the requested extent. Coverage does not expand merely because a road has a vertex beyond the extract.

The converter preserves OSM IDs/tags, keeps supported road/building/water/park geometry and named amenity/shop/tourism points. Open building or land-use ways, relation-only multipolygons and unsupported geometry are omitted, not fabricated. The first pack is not a complete worldwide map.

Building heights use OSM `height` or `building:levels` where available. The renderer uses an 8 m visual default when height is missing and limits extreme heights for legibility. These defaults are not measured building heights.

## Reproduce a pack

```sh
python tools/fetch_map.py --bbox -34.927 138.596 -34.918 138.609 --version 1 --output app/src/main/assets/adelaide-cbd-v1.geojson
```

The acquisition tool limits the extent and response size. Use regional extracts and suitable map infrastructure for larger coverage, not repeated bulk requests to OSM's editing API.

Raw XML and a SHA-256 receipt are saved in the user's `.cache/jwmaps/osm` directory by default. To regenerate from a cached source without a network request, pass `--source <OSM_XML_FILE>` with the matching extent. OSM changes over time, so a new live extraction will not necessarily match an older release's bytes.

## Data licence

Map data © OpenStreetMap contributors, licensed under the [Open Database License 1.0](https://opendatacommons.org/licenses/odbl/1-0/). The derived GeoJSON is published under the same data licence, separately from the MIT-licensed software. The complete derived pack is available as a release asset and the initial preview is also in the source tree.

Preserve attribution and applicable share-alike obligations when republishing or modifying the database. See [OpenStreetMap copyright and attribution](https://www.openstreetmap.org/copyright). Attribution must remain visible on the phone and car map.
