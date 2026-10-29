#!/usr/bin/env python3
"""Build the full South Australia Geofabrik extract into offline SQLite tiles."""
import hashlib
import re
import math
from shapely.geometry import box, shape, mapping
from collections import Counter, defaultdict
import gzip
import json
import sqlite3
import osmium
from shapely import from_wkb
from shapely.geometry import LineString, Polygon
from datetime import datetime, timezone
import time


def area_kind(tags):
    if tags.get('building') not in (None, 'no'):
        return 'building'
    if (tags.get('natural') in ('water','bay') or tags.get('waterway') in ('riverbank','dock')
            or tags.get('landuse') in ('reservoir','basin')):
        return 'water'
    if (tags.get('leisure') in ('park','nature_reserve','garden','recreation_ground')
            or tags.get('landuse') in ('grass','recreation_ground','forest','meadow','village_green')
            or tags.get('natural') == 'wood' or tags.get('boundary') == 'national_park'):
        return 'park'
    return None


def named_poi(tags):
    return bool(tags.get('name') and any(tags.get(k) not in (None,'no') for k in ('amenity','shop','tourism')))


class ExtractHandler(osmium.SimpleHandler):
    def __init__(self, store):
        super().__init__()
        self.store = store
        self.wkb = osmium.geom.WKBFactory()
        self.seen = Counter()
        self.skipped = Counter()
        self.skip_examples = defaultdict(list)
        self.places = []
        self.area_ways = set()
        self.area_relations = set()
        self.assembled_ways = set()
        self.assembled_relations = set()
        self.areas_from_relations = 0
        self.areas_from_ways = 0

    def skip(self, reason, osm_type, osm_id, tags):
        self.skipped[reason] += 1
        if len(self.skip_examples[reason]) < 20:
            self.skip_examples[reason].append(dict(osmType=osm_type, osmId=osm_id, tags=dict(tags)))

    def emit(self, kind, geometry_type, coords, tags, osm_type, osm_id, part=0, geometry_source=None):
        try:
            feature = make_feature(kind, geometry_type, coords, tags, osm_type, osm_id, part)
            if geometry_source:
                feature['properties']['geometrySource'] = geometry_source
            self.store.add(feature)
        except ValueError as error:
            self.skip(str(error), osm_type, osm_id, tags)

    def node(self, node):
        self.seen['nodes'] += 1
        tags = dict(node.tags)
        if not node.location.valid():
            self.skip('invalid_node_location', 'node', node.id, tags)
            return
        coords = [node.location.lon, node.location.lat]
        if tags.get('place') in ('city','town','village') and tags.get('name'):
            self.places.append(dict(name=tags['name'], osmId=node.id, coordinates=coords, place=tags['place']))
        if named_poi(tags):
            self.emit('poi','Point',coords,tags,'node',node.id)
        else:
            self.skipped['unsupported_node_tags'] += 1

    def way(self, way):
        self.seen['ways'] += 1
        tags = dict(way.tags)
        kind = area_kind(tags)
        if kind:
            if not way.is_closed():
                self.skip('open_area_way', 'way', way.id, tags)
            else:
                self.area_ways.add(way.id)
        road = tags.get('highway') not in (None,'no')
        poi = named_poi(tags)
        if not road and not poi:
            if not kind:
                self.skipped['unsupported_way_tags'] += 1
            return
        try:
            coords = [[n.lon,n.lat] for n in way.nodes]
            if road:
                self.emit('road','LineString',coords,tags,'way',way.id)
            if poi:
                geometry = Polygon(coords) if way.is_closed() and len(coords) >= 4 else LineString(coords)
                if not geometry.is_valid:
                    raise ValueError('invalid_poi_way_geometry')
                p = geometry.representative_point() if geometry.geom_type == 'Polygon' else geometry.interpolate(.5, normalized=True)
                self.emit('poi','Point',[p.x,p.y],tags,'way',way.id,
                          geometry_source='representative_point_of_osm_way')
        except (osmium.InvalidLocationError, ValueError) as error:
            self.skip('missing_way_nodes' if isinstance(error, osmium.InvalidLocationError) else str(error), 'way', way.id, tags)

    def relation(self, relation):
        self.seen['relations'] += 1
        tags = dict(relation.tags)
        if area_kind(tags):
            if tags.get('type') in ('multipolygon','boundary'):
                self.area_relations.add(relation.id)
            else:
                self.skip('unsupported_area_relation_type','relation',relation.id,tags)
        else:
            self.skipped['unsupported_relation_tags'] += 1

    def area(self, area):
        tags = dict(area.tags)
        kind = area_kind(tags)
        if not kind:
            return
        osm_type = 'way' if area.from_way() else 'relation'
        osm_id = area.orig_id()
        if area.from_way():
            self.assembled_ways.add(osm_id)
            self.areas_from_ways += 1
        else:
            self.assembled_relations.add(osm_id)
            self.areas_from_relations += 1
        try:
            geometry = from_wkb(self.wkb.create_multipolygon(area))
            for part, polygon in enumerate(polygon_parts(geometry)):
                self.emit(kind,'Polygon',mapping(polygon)['coordinates'],tags,osm_type,osm_id,part)
        except (RuntimeError, ValueError) as error:
            self.skip('area_assembly_geometry_error',osm_type,osm_id,tags)

    def report(self):
        self.skipped['unassembled_closed_area_ways'] = len(self.area_ways - self.assembled_ways)
        self.skipped['unassembled_area_relations'] = len(self.area_relations - self.assembled_relations)
        return dict(sourceObjects=dict(self.seen), skipped=dict(self.skipped), skipExamples=dict(self.skip_examples),
                    unassembledAreaWayIds=sorted(self.area_ways-self.assembled_ways),
                    unassembledAreaRelationIds=sorted(self.area_relations-self.assembled_relations),
                    areasFromRelations=self.areas_from_relations, areasFromWays=self.areas_from_ways,
                    places=self.places)


def build(source, output, source_url, publisher_md5):
    start = time.monotonic()
    source_info = verify_source(source, publisher_md5)
    reader = osmium.io.Reader(str(source))
    try:
        header = reader.header()
        bounds = header.box()
        bbox = [bounds.bottom_left.lon, bounds.bottom_left.lat, bounds.top_right.lon, bounds.top_right.lat]
        timestamp = header.get('osmosis_replication_timestamp') or None
    finally:
        reader.close()
    source_info.update(path=str(source), url=source_url, bbox=bbox, timestamp=timestamp)
    store = TileStore(output, source_url)
    handler = ExtractHandler(store)
    handler.apply_file(str(source), locations=True, idx='flex_mem')
    imported = handler.report()
    metadata = dict(bbox=bbox, sourceSha256=source_info['sha256'], sourceTimestamp=timestamp,
                    sourceSizeBytes=source_info['sizeBytes'], sourceMd5=source_info['md5'],
                    sourceObjects=imported['sourceObjects'], omissions=imported['skipped'])
    stats = store.finish(metadata)
    return dict(source=source_info, output=dict(path=str(output), sizeBytes=output.stat().st_size,
                sha256=file_sha256(output)), stats=stats,
                builtAt=datetime.now(timezone.utc).isoformat(), durationSeconds=round(time.monotonic()-start,3)) | {'import':imported}


def file_sha256(path):
    digest = hashlib.sha256()
    with path.open('rb') as stream:
        for block in iter(lambda: stream.read(1024*1024), b''):
            digest.update(block)
    return digest.hexdigest()

ATTRIBUTION = '© OpenStreetMap contributors, ODbL 1.0'


def compact(value):
    return json.dumps(value, ensure_ascii=False, separators=(',', ':'), allow_nan=False)


def point_count(feature):
    geom = feature['geometry']
    c = geom['coordinates']
    return 1 if geom['type'] == 'Point' else len(c) if geom['type'] == 'LineString' else sum(map(len, c))


class TileStore:
    """Disk-backed feature spool; only one tile is materialized at a time."""
    def __init__(self, output, source_url):
        self.output = output
        self.stage_path = output.with_suffix('.stage.sqlite')
        if self.stage_path.exists():
            self.stage_path.unlink()
        self.db = sqlite3.connect(self.stage_path)
        self.db.executescript('''
            PRAGMA journal_mode=OFF;
            PRAGMA synchronous=OFF;
            PRAGMA cache_size=-65536;
            CREATE TABLE features(id INTEGER PRIMARY KEY, json TEXT NOT NULL, points INTEGER NOT NULL);
            CREATE TABLE membership(x INTEGER NOT NULL,y INTEGER NOT NULL,id INTEGER NOT NULL,
                part INTEGER NOT NULL, json TEXT, points INTEGER,
                PRIMARY KEY(x,y,id,part)) WITHOUT ROWID;
        ''')
        self.pack = dict(id='south-australia', version=3, formatVersion=1,
                         name='South Australia', attribution=ATTRIBUTION, sourceUrl=source_url)
        self.kinds = Counter()
        self.count = 0
        self.max_path = 0
        self.max_rings = 0
        self.large_polygons = []

    def add(self, feature):
        geometry = shape(feature['geometry'])
        if not geometry.is_valid:
            raise ValueError('invalid_geometry')
        tiles = list(touched_tiles(geometry))
        if not tiles:
            raise ValueError('empty_geometry')
        clipped = geometry.geom_type == 'Polygon' and len(tiles) > 16
        records = []
        if clipped:
            self.large_polygons.append(dict(id=feature['id'], name=feature['properties'].get('name'),
                                            bbox=list(geometry.bounds), tiles=len(tiles), points=point_count(feature)))
            for x,y in tiles:
                intersection = geometry.intersection(box(*tile_bbox(x,y)))
                pieces = list(polygon_parts(intersection))
                if not pieces:
                    # Boundary-only contact cannot be encoded as Polygon. Retain the
                    # original valid Polygon in that tile instead of inventing area.
                    records.append((x,y,self.count+1,0,None,None))
                for part, piece in enumerate(pieces):
                    props = feature['properties']
                    f = make_feature(props['kind'], 'Polygon', mapping(piece)['coordinates'],
                                     props['tags'], props['osmType'], props['osmId'])
                    f['id'] = feature['id'] + f'/tile/{x}/{y}/{part}'
                    f['properties']['sourceFeatureId'] = feature['id']
                    f['properties']['tileClipped'] = True
                    records.append((x,y,self.count+1,part,compact(f),point_count(f)))
        else:
            records = [(x,y,self.count+1,0,None,None) for x,y in tiles]
        self.count += 1
        self.kinds[feature['properties']['kind']] += 1
        geom = feature['geometry']
        paths = geom['coordinates'] if geom['type'] == 'Polygon' else [geom['coordinates']] if geom['type'] == 'LineString' else [[geom['coordinates']]]
        self.max_path = max(self.max_path, max(map(len, paths)))
        self.max_rings = max(self.max_rings, len(paths) if geom['type'] == 'Polygon' else 0)
        self.db.execute('insert into features values(?,?,?)', (self.count, compact(feature), point_count(feature)))
        self.db.executemany('insert into membership values(?,?,?,?,?,?)', records)
        if self.count % 10000 == 0:
            self.db.commit()

    def finish(self, metadata):
        self.db.commit()
        temporary = self.output.with_suffix('.tmp.sqlite')
        if temporary.exists():
            temporary.unlink()
        out = sqlite3.connect(temporary)
        out.executescript('''
            CREATE TABLE metadata(key TEXT PRIMARY KEY,value TEXT NOT NULL);
            CREATE TABLE tiles(x INTEGER NOT NULL,y INTEGER NOT NULL,payload BLOB NOT NULL,
                feature_count INTEGER NOT NULL,PRIMARY KEY(x,y));
        ''')
        stats = dict(featureCount=self.count, kinds=dict(self.kinds), tileCount=0,
                     maxCompressedTileBytes=0, maxUncompressedTileBytes=0, maxTileFeatures=0,
                     maxTilePoints=0, maxPathPoints=self.max_path, maxPolygonRings=self.max_rings)
        windows = defaultdict(lambda: [0,0])
        for x,y in self.db.execute('select distinct x,y from membership order by x,y'):
            rows = self.db.execute('''select coalesce(m.json,f.json),coalesce(m.points,f.points)
                from membership m join features f on f.id=m.id
                where m.x=? and m.y=? order by m.id,m.part''', (x,y)).fetchall()
            tile = dict(type='FeatureCollection', pack=self.pack, bbox=tile_bbox(x,y), features=[json.loads(r[0]) for r in rows])
            raw = compact(tile).encode('utf-8')
            payload = gzip.compress(raw, compresslevel=6, mtime=0)
            points = sum(r[1] for r in rows)
            out.execute('insert into tiles values(?,?,?,?)', (x,y,payload,len(rows)))
            stats['tileCount'] += 1
            for key, value in [('maxCompressedTileBytes',len(payload)), ('maxUncompressedTileBytes',len(raw)),
                               ('maxTileFeatures',len(rows)), ('maxTilePoints',points)]:
                if value > stats[key]:
                    stats[key] = value
                    stats[key+'At'] = [x,y]
            for a in range(x-1,x+2):
                for b in range(y-1,y+2):
                    windows[a,b][0] += len(rows)
                    windows[a,b][1] += points
        for index, key in [(0,'maxNineTileFeaturesUpperBound'), (1,'maxNineTilePointsUpperBound')]:
            center, counts = max(windows.items(), key=lambda item: item[1][index], default=((0,0),[0,0]))
            stats[key] = counts[index]
            stats[key+'At'] = list(center)
        values = dict(self.pack, gridStep=GRID_STEP, **metadata, **stats)
        out.executemany('insert into metadata values(?,?)', ((k,compact(v)) for k,v in sorted(values.items())))
        out.commit()
        check = out.execute('pragma integrity_check').fetchone()[0]
        if check != 'ok':
            raise ValueError('SQLite integrity_check: '+check)
        out.close()
        self.db.close()
        temporary.replace(self.output)
        stats['integrityCheck'] = check
        stats['largePolygons'] = self.large_polygons
        return stats


def polygon_parts(geometry):
    if geometry.geom_type == 'Polygon':
        yield geometry
    elif geometry.geom_type in ('MultiPolygon', 'GeometryCollection'):
        for child in geometry.geoms:
            yield from polygon_parts(child)


GRID_STEP = 0.025


def tile_bbox(x, y):
    return [round(x * GRID_STEP, 7), round(y * GRID_STEP, 7),
            round((x + 1) * GRID_STEP, 7), round((y + 1) * GRID_STEP, 7)]


def touched_tiles(geometry):
    """Closed-cell intersection for paths; points use the requested floor cell.

    Recursively reject empty subranges. No statewide bbox-sized list is allocated.
    """
    if geometry.is_empty:
        return
    west, south, east, north = geometry.bounds
    if geometry.geom_type == 'Point':
        yield math.floor(west / GRID_STEP), math.floor(south / GRID_STEP)
        return
    def edge_index(value, minimum=False):
        quotient=value/GRID_STEP
        nearest=round(quotient)
        if abs(quotient-nearest)<1e-9:
            return nearest-1 if minimum else nearest
        return math.floor(quotient)
    # Decimal grid edges must be snapped before floor; 138.6/.025 is slightly below 5544.
    x0,y0=edge_index(west,True),edge_index(south,True)
    x1,y1=edge_index(east),edge_index(north)
    stack = [(x0,y0,x1,y1)]
    while stack:
        a,b,c,d = stack.pop()
        region = box(*[round(v * GRID_STEP, 7) for v in (a,b,c+1,d+1)])
        if not geometry.intersects(region):
            continue
        if a == c and b == d:
            yield a,b
        elif c-a >= d-b:
            mid = (a+c)//2
            stack.extend([(mid+1,b,c,d),(a,b,mid,d)])
        else:
            mid = (b+d)//2
            stack.extend([(a,mid+1,c,d),(a,b,c,mid)])


def make_feature(kind, geometry_type, coordinates, tags, osm_type, osm_id, part=0):
    paths = [coordinates] if geometry_type == 'LineString' else coordinates
    if geometry_type == 'Point':
        paths = [[coordinates]]
    elif geometry_type == 'Polygon':
        if not 1 <= len(paths) <= 64:
            raise ValueError('ring_limit')
    elif geometry_type != 'LineString':
        raise ValueError('unsupported_geometry')
    expected = {'road':'LineString', 'poi':'Point', 'building':'Polygon', 'park':'Polygon', 'water':'Polygon'}
    if expected.get(kind) != geometry_type:
        raise ValueError('kind_geometry_mismatch')
    rounded = []
    for path in paths:
        minimum = 4 if geometry_type == 'Polygon' else 2 if geometry_type == 'LineString' else 1
        if not minimum <= len(path) <= 20000:
            raise ValueError('path_limit')
        points = []
        for point in path:
            if len(point) != 2 or any(type(v) not in (int, float) or not math.isfinite(v) for v in point):
                raise ValueError('nonfinite_coordinate')
            x, y = point
            if not (-180 <= x <= 180 and -85 <= y <= 85):
                raise ValueError('coordinate_range')
            points.append([round(x, 7), round(y, 7)])
        if geometry_type == 'Polygon' and points[0] != points[-1]:
            raise ValueError('unclosed_polygon')
        rounded.append(points)
    coords = rounded if geometry_type == 'Polygon' else rounded[0][0] if geometry_type == 'Point' else rounded[0]
    props = dict(kind=kind, osmType=osm_type, osmId=osm_id, tags=dict(tags))
    if tags.get('name'):
        if len(tags['name']) > 300:
            raise ValueError('name_limit')
        props['name'] = tags['name']
    if kind == 'building':
        for key, scale in [('height', 1), ('building:levels', 3)]:
            match = re.fullmatch(r'\s*(\d+(?:\.\d+)?)\s*(?:m)?\s*', tags.get(key, ''))
            if match and 0 < float(match[1]) * scale < 10000:
                props.update(height=float(match[1]) * scale, heightSource=key)
                break
        else:
            props.update(height=8, renderingHeightDefault=True)
    identifier = f'{osm_type}/{osm_id}/{kind}/{part}'
    props['sourceFeatureId'] = identifier
    return dict(type='Feature', id=identifier, properties=props, geometry=dict(type=geometry_type, coordinates=coords))


def verify_source(path, publisher_md5):
    """MD5 is the publisher's transport check, not a security signature."""
    expected = publisher_md5.split()[0].lower()
    if not re.fullmatch('[0-9a-f]{32}', expected):
        raise ValueError('Invalid publisher MD5')
    md5, sha256 = hashlib.md5(), hashlib.sha256()
    size = 0
    with path.open('rb') as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b''):
            size += len(block)
            md5.update(block)
            sha256.update(block)
    if md5.hexdigest() != expected:
        raise ValueError('Source MD5 differs from publisher')
    return dict(sizeBytes=size, md5=expected, sha256=sha256.hexdigest(), publisherMd5Verified=True)


TOWNS = ['Adelaide','Mount Gambier','Port Augusta','Coober Pedy','Renmark','Kingscote']


def validate_towns(output, places, names):
    samples = []
    with sqlite3.connect(output) as db:
        for name in names:
            matches = [p for p in places if p['name'] == name]
            if len(matches) != 1:
                raise ValueError(f'Expected one source place node for {name}, found {len(matches)}')
            place = matches[0]
            lon, lat = place['coordinates']
            x, y = math.floor(lon/GRID_STEP), math.floor(lat/GRID_STEP)
            features = {}
            rows = db.execute('select payload from tiles where x between ? and ? and y between ? and ?',
                              (x-1,x+1,y-1,y+1)).fetchall()
            for payload, in rows:
                tile = json.loads(gzip.decompress(payload))
                for feature in tile['features']:
                    features[feature['properties']['sourceFeatureId']] = feature
            if not features:
                raise ValueError(f'No supported features in nine-tile window for {name}')
            samples.append(dict(name=name, placeOsmId=place['osmId'], coordinates=place['coordinates'],
                centerTile=[x,y], tileCount=len(rows), featureCount=len(features),
                kinds=dict(Counter(f['properties']['kind'] for f in features.values())),
                names=sorted({f['properties']['name'] for f in features.values() if f['properties'].get('name')})))
    return samples


def evidence_text(receipt):
    source, output, stats = receipt['source'], receipt['output'], receipt['stats']
    lines = ['# South Australia offline data evidence', '',
        'This record describes generated data, not app rendering or Android integration.', '',
        f"Built at {receipt['builtAt']}. Duration {receipt['durationSeconds']} seconds.", '',
        '## Source', '', f"URL: {source['url']}", f"Timestamp: {source['timestamp']}",
        f"Extract header bbox: {source['bbox']}", f"Bytes: {source['sizeBytes']}",
        f"Publisher MD5 verified: {source['md5']}", f"SHA256: {source['sha256']}", '',
        'The publisher MD5 checks transport. It is not a cryptographic authenticity signature.', '',
        '## Output', '', f"Path: {output['path']}", f"Bytes: {output['sizeBytes']}",
        f"SHA256: {output['sha256']}", f"SQLite integrity_check: {stats['integrityCheck']}",
        f"Unique source geometry features: {stats['featureCount']}", f"Kinds: {compact(stats['kinds'])}",
        f"Nonempty tiles: {stats['tileCount']}", '', '## Limits measured', '']
    lines.extend(f'{key}: {value}' for key,value in stats.items() if key.startswith('max'))
    lines.extend(['', 'Nine-tile maxima sum stored rows and points, including duplicates. These are conservative upper bounds before runtime deduplication.',
        'No tile or window truncation is applied. Limits for the parent runtime are 60000 features and 400000 points.', '',
        '## Town checks', '', 'Each window uses the public OSM place node from this extract, not a guessed town coordinate.', ''])
    for town in receipt.get('towns',[]):
        lines.append(f"- {town['name']}: {town['featureCount']} unique source features across {town['tileCount']} tiles. Kinds {compact(town['kinds'])}. Names: {', '.join(town['names'][:25])}")
    lines.extend(['', 'Full sampled name lists and public place node IDs are in build-receipt.json.', '',
                  '## Omissions and source counts', '', compact(receipt['import']['sourceObjects']), '',
                  compact(receipt['import']['skipped']), '',
                  'Unassembled candidate IDs and up to 20 examples per conversion failure are in the receipt.',
                  'Unsupported OSM tags, open area ways, invalid geometry and schema-limit failures are counted. No geometry is repaired by guesswork.',
                  'Multipolygons are assembled by libosmium and emitted as individual Polygon outer parts with their original holes.',
                  'Named amenity, shop and tourism ways get a documented point derived from their real geometry.', '',
                  '## Large polygons', '',
                  f"{len(stats['largePolygons'])} source polygons touch more than 16 cells and are clipped by geometric intersection. Their IDs, names, bounds and tile counts are in the receipt.",
                  'Small geometries remain intact in every touched tile. Boundary-only contacts of large polygons retain the intact original, because a line or point cannot represent a Polygon.', '',
                  f'Attribution: {ATTRIBUTION}',
                  'Database reuse and redistribution are subject to ODbL 1.0. Preserve attribution and comply with the licence when publishing adapted databases.', ''])
    return '\n'.join(lines)


def main():
    import argparse
    from pathlib import Path
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--source',type=Path,required=True)
    parser.add_argument('--publisher-md5',type=Path,required=True)
    parser.add_argument('--source-url',required=True)
    parser.add_argument('--output',type=Path,required=True)
    parser.add_argument('--receipt',type=Path,required=True)
    parser.add_argument('--evidence',type=Path,required=True)
    parser.add_argument('--town',action='append')
    args = parser.parse_args()
    if not args.source_url.startswith('https://'):
        parser.error('source URL must use HTTPS')
    args.output.parent.mkdir(parents=True,exist_ok=True)
    receipt = build(args.source,args.output,args.source_url,args.publisher_md5.read_text())
    receipt['towns'] = validate_towns(args.output,receipt['import']['places'],args.town or TOWNS)
    receipt['command'] = __import__('sys').argv
    for path, text in [(args.receipt,json.dumps(receipt,ensure_ascii=False,indent=2)+'\n'),
                       (args.evidence,evidence_text(receipt))]:
        path.parent.mkdir(parents=True,exist_ok=True)
        path.write_text(text,encoding='utf-8')
    print(compact(dict(output=receipt['output'], featureCount=receipt['stats']['featureCount'],
                       tileCount=receipt['stats']['tileCount'], receipt=str(args.receipt))))


if __name__ == '__main__':
    main()

