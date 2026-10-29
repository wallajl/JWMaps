"""Statewide builder tests. All tiny geometries here are synthetic test fixtures."""
import gzip
import hashlib
import json
import pathlib
import sqlite3
import tempfile
import unittest
import subprocess
import sys

import build_sa_map as sa


class SourceTests(unittest.TestCase):
    def test_verifies_publisher_digest_and_records_exact_bytes(self):
        with tempfile.TemporaryDirectory() as directory:
            p = pathlib.Path(directory) / 'fixture.osm.pbf'
            p.write_bytes(b'fixture, not real OSM')
            md5 = hashlib.md5(p.read_bytes()).hexdigest()
            self.assertTrue(hasattr(sa, 'verify_source'), 'publisher verification missing')
            info = sa.verify_source(p, md5 + '  fixture.osm.pbf\n')
            self.assertEqual(info['sizeBytes'], p.stat().st_size)
            self.assertEqual(info['sha256'], hashlib.sha256(p.read_bytes()).hexdigest())
            with self.assertRaisesRegex(ValueError, 'MD5'):
                sa.verify_source(p, '0' * 32)


class GeometryTests(unittest.TestCase):
    def test_building_geometry_is_sourced_and_default_height_is_explicit(self):
        self.assertTrue(hasattr(sa, 'make_feature'), 'geometry conversion missing')
        ring = [[138.6,-34.9],[138.601,-34.9],[138.601,-34.901],[138.6,-34.9]]
        f = sa.make_feature('building', 'Polygon', [ring], {'building':'yes'}, 'way', 1)
        self.assertEqual(f['geometry']['coordinates'], [ring])
        self.assertEqual(f['properties']['height'], 8)
        self.assertIs(f['properties']['renderingHeightDefault'], True)
        for tags, height, source in [({'height':'12 m'},12,'height'), ({'building:levels':'4'},12,'building:levels')]:
            p = sa.make_feature('building', 'Polygon', [ring], tags, 'way', 1)['properties']
            self.assertEqual(p['height'], height)
            self.assertEqual(p['heightSource'], source)
            self.assertNotIn('renderingHeightDefault', p)
        for coords in ([ring[:-1]], [[[181,0],[181,1],[180,1],[181,0]]], [ring]*65):
            with self.assertRaises(ValueError):
                sa.make_feature('building', 'Polygon', coords, {}, 'way', 1)
        with self.assertRaises(ValueError):
            sa.make_feature('road', 'LineString', [[0,0]]*20001, {}, 'way', 2)
        with self.assertRaises(ValueError):
            sa.make_feature('poi', 'Point', [float('nan'),0], {}, 'node', 3)


class TileTests(unittest.TestCase):
    def test_decimal_grid_edge_has_both_neighbor_cells_despite_binary_division(self):
        from shapely.geometry import LineString
        self.assertEqual(set(sa.touched_tiles(LineString([(138.6,-34.901),(138.6,-34.902)]))),
                         {(5543,-1397),(5544,-1397)})

    def test_indexes_entire_line_and_prunes_empty_bbox_tiles(self):
        self.assertTrue(hasattr(sa, 'touched_tiles'), 'intersection indexing missing')
        from shapely.geometry import LineString, Point, Polygon, box
        line = LineString([(0.001,-0.099),(0.099,-0.001)])
        actual = set(sa.touched_tiles(line))
        expected = {(x,y) for x in range(4) for y in range(-4,0)
                    if line.intersects(box(*sa.tile_bbox(x,y)))}
        self.assertEqual(actual, expected)
        self.assertIn((0,-4), actual)
        self.assertIn((3,-1), actual)
        self.assertNotIn((0,-1), actual)
        self.assertEqual(set(sa.touched_tiles(Point(0.025,-0.025))), {(1,-1)})
        # A line exactly on a boundary touches both sides, unlike point cell ownership.
        self.assertEqual(set(sa.touched_tiles(LineString([(0.025,-0.02),(0.025,-0.01)]))), {(0,-1),(1,-1)})
        # Large thin diagonal exercises subdivision without bounding-box allocation.
        big = LineString([(0.001,0.001),(5.001,5.001)])
        self.assertLess(len(list(sa.touched_tiles(big))), 1000)
        hole = Polygon([(0,0),(.1,0),(.1,.1),(0,.1),(0,0)],
                       [[(.02,.02),(.08,.02),(.08,.08),(.02,.08),(.02,.02)]])
        self.assertNotIn((1,1), set(sa.touched_tiles(hole)))


class StoreTests(unittest.TestCase):
    def test_large_polygon_is_clipped_from_real_geometry_without_filling_holes(self):
        from shapely.geometry import shape
        from shapely.ops import unary_union
        with tempfile.TemporaryDirectory() as directory:
            output = pathlib.Path(directory) / 'fixture.sqlite'
            store = sa.TileStore(output, 'https://example.org/fixture.osm.pbf')
            ring = [[.001,.001],[.201,.001],[.201,.201],[.001,.201],[.001,.001]]
            hole = [[.06,.06],[.14,.06],[.14,.14],[.06,.14],[.06,.06]]
            feature = sa.make_feature('park','Polygon',[ring,hole],{'name':'Fixture park'},'relation',7)
            store.add(feature)
            stats = store.finish({'bbox':[0,0,.3,.3]})
            self.assertLessEqual(stats['maxTilePoints'], 20, 'large polygons still duplicated intact')
            db = sqlite3.connect(output)
            pieces = []
            for x,y,payload,count in db.execute('select * from tiles'):
                for f in json.loads(gzip.decompress(payload))['features']:
                    self.assertEqual(f['properties']['sourceFeatureId'],feature['id'])
                    self.assertTrue(f['properties']['tileClipped'])
                    geom = shape(f['geometry'])
                    self.assertTrue(sa.box(*sa.tile_bbox(x,y)).covers(geom))
                    pieces.append(geom)
            self.assertLess(unary_union(pieces).symmetric_difference(shape(feature['geometry'])).area, 1e-12)
            self.assertEqual(stats['featureCount'],1)
            db.close()

    def test_emits_gzip_sqlite_tiles_and_json_metadata_without_centroid_loss(self):
        self.assertTrue(hasattr(sa, 'TileStore'), 'SQLite output missing')
        with tempfile.TemporaryDirectory() as directory:
            output = pathlib.Path(directory) / 'fixture.sqlite'
            store = sa.TileStore(output, 'https://example.org/fixture.osm.pbf')
            road = sa.make_feature('road','LineString',[[.001,.001],[.099,.001]],{'name':'Fixture road'},'way',1)
            store.add(road)
            store.add(sa.make_feature('poi','Point',[.001,.001],{'name':'Fixture shop'},'node',2))
            stats = store.finish({'bbox':[0,0,.1,.1], 'sourceSha256':'a'*64, 'sourceTimestamp':'fixture'})
            self.assertEqual(stats['featureCount'], 2)
            self.assertEqual(stats['tileCount'], 4)
            self.assertEqual(stats['maxNineTileFeaturesUpperBound'], 4)
            db = sqlite3.connect(output)
            self.assertEqual(db.execute('pragma integrity_check').fetchone()[0], 'ok')
            metadata = {k:json.loads(v) for k,v in db.execute('select key,value from metadata')}
            self.assertEqual(metadata['id'], 'south-australia')
            self.assertEqual(metadata['version'], 3)
            self.assertEqual(metadata['kinds'], {'road':1,'poi':1})
            for x,y,payload,count in db.execute('select * from tiles'):
                tile = json.loads(gzip.decompress(payload))
                self.assertEqual(tile['bbox'], sa.tile_bbox(x,y))
                self.assertEqual(tile['pack']['formatVersion'], 1)
                self.assertEqual(len(tile['features']),count)
                self.assertIn(road, tile['features'])
            plan = str(db.execute('explain query plan select payload from tiles where x between 0 and 2 and y between 0 and 2').fetchall())
            self.assertIn('INDEX',plan)
            db.close()


class ImportTests(unittest.TestCase):
    def test_counts_unsupported_nodes_and_missing_references_without_inventing_geometry(self):
        with tempfile.TemporaryDirectory() as directory:
            root = pathlib.Path(directory)
            source = root/'fixture.osm'
            source.write_text('''<osm version="0.6"><bounds minlon="0" minlat="0" maxlon=".1" maxlat=".1"/>
            <node id="1" lon=".01" lat=".01"/>
            <node id="2" lon=".02" lat=".01"/>
            <way id="8"><nd ref="1"/><nd ref="99"/><tag k="highway" v="track"/></way>
            <way id="9"><nd ref="1"/><nd ref="2"/><tag k="highway" v="track"/></way>
            </osm>''')
            receipt = sa.build(source,root/'fixture.sqlite','https://example.org/fixture.osm.pbf',hashlib.md5(source.read_bytes()).hexdigest())
            self.assertEqual(receipt['import']['skipped'].get('unsupported_node_tags'),2)
            self.assertEqual(receipt['import']['skipped']['missing_way_nodes'],1)
            self.assertEqual(receipt['stats']['featureCount'],1)

    def test_real_osmium_pipeline_includes_relations_pois_and_reports_open_ways(self):
        self.assertTrue(hasattr(sa, 'build'), 'OSM import pipeline missing')
        xml = '''<osm version="0.6" generator="unit-test-fixture">
        <bounds minlon="138.5" minlat="-35" maxlon="138.7" maxlat="-34.8"/>
        <node id="1" lon="138.60" lat="-34.90"><tag k="name" v="Fixture Adelaide"/><tag k="place" v="city"/></node>
        <node id="2" lon="138.61" lat="-34.90"/>
        <node id="3" lon="138.61" lat="-34.91"/>
        <node id="4" lon="138.60" lat="-34.91"/>
        <node id="5" lon="138.605" lat="-34.905"><tag k="name" v="Fixture cafe"/><tag k="amenity" v="cafe"/></node>
        <way id="10"><nd ref="1"/><nd ref="2"/><tag k="highway" v="primary"/><tag k="name" v="Fixture road"/></way>
        <way id="11"><nd ref="1"/><nd ref="2"/><nd ref="3"/><nd ref="4"/><nd ref="1"/><tag k="building" v="yes"/><tag k="name" v="Fixture library"/><tag k="amenity" v="library"/></way>
        <way id="12"><nd ref="1"/><nd ref="2"/><tag k="building" v="yes"/></way>
        <way id="13"><nd ref="1"/><nd ref="2"/><nd ref="3"/></way>
        <way id="14"><nd ref="3"/><nd ref="4"/><nd ref="1"/></way>
        <relation id="20"><member type="way" ref="13" role="outer"/><member type="way" ref="14" role="outer"/><tag k="type" v="multipolygon"/><tag k="natural" v="water"/><tag k="name" v="Fixture lake"/></relation>
        </osm>'''
        with tempfile.TemporaryDirectory() as directory:
            root = pathlib.Path(directory)
            source = root / 'fixture.osm'
            source.write_text(xml)
            output = root / 'fixture.sqlite'
            receipt = sa.build(source, output, 'https://example.org/fixture.osm.pbf', hashlib.md5(source.read_bytes()).hexdigest())
            self.assertEqual(receipt['stats']['kinds'], {'road':1,'building':1,'water':1,'poi':2})
            self.assertEqual(receipt['stats']['featureCount'],5)
            self.assertEqual(receipt['import']['skipped']['open_area_way'],1)
            self.assertEqual(receipt['import']['areasFromRelations'],1)
            self.assertEqual(receipt['import']['places'][0]['name'],'Fixture Adelaide')
            self.assertEqual(receipt['source']['bbox'],[138.5,-35,138.7,-34.8])
            self.assertEqual(receipt['stats']['integrityCheck'],'ok')
            db = sqlite3.connect(output)
            features = {f['id']:f for row in db.execute('select payload from tiles')
                        for f in json.loads(gzip.decompress(row[0]))['features']}
            self.assertTrue(any(f['properties']['osmType']=='relation' for f in features.values()))
            way_poi = next(f for f in features.values() if f['properties']['kind']=='poi' and f['properties']['osmType']=='way')
            self.assertEqual(way_poi['properties']['geometrySource'],'representative_point_of_osm_way')
            db.close()


class CommandTests(unittest.TestCase):
    def test_cli_writes_real_receipt_and_town_window_evidence(self):
        with tempfile.TemporaryDirectory() as directory:
            root = pathlib.Path(directory)
            source = root/'fixture.osm'
            source.write_text('''<osm version="0.6"><bounds minlon="0" minlat="0" maxlon=".1" maxlat=".1"/>
            <node id="1" lon=".001" lat=".001"><tag k="name" v="Fixture Town"/><tag k="place" v="town"/></node>
            <node id="2" lon=".02" lat=".001"/>
            <way id="3"><nd ref="1"/><nd ref="2"/><tag k="highway" v="residential"/><tag k="name" v="Fixture Street"/></way></osm>''')
            md5 = root/'fixture.md5'
            md5.write_text(hashlib.md5(source.read_bytes()).hexdigest())
            result = subprocess.run([sys.executable,str(pathlib.Path(sa.__file__)), '--source',str(source),
                '--publisher-md5',str(md5),'--source-url','https://example.org/fixture.osm.pbf',
                '--output',str(root/'fixture.sqlite'),'--receipt',str(root/'receipt.json'),
                '--evidence',str(root/'evidence.md'),'--town','Fixture Town'],capture_output=True,text=True)
            self.assertEqual(result.returncode,0,result.stderr)
            self.assertTrue((root/'receipt.json').exists(),'CLI did not produce receipt')
            receipt = json.loads((root/'receipt.json').read_text())
            self.assertEqual(receipt['towns'][0]['featureCount'],1)
            self.assertEqual(receipt['towns'][0]['names'],['Fixture Street'])
            self.assertEqual(receipt['output']['sha256'],hashlib.sha256((root/'fixture.sqlite').read_bytes()).hexdigest())
            self.assertIn('Fixture Town',(root/'evidence.md').read_text())


if __name__ == '__main__':
    unittest.main()
