import json
import unittest
import pathlib
import subprocess
import sys
import tempfile
from fetch_map import convert_osm, validate_source, build_pack
import fetch_map


class ConverterTests(unittest.TestCase):
    def test_converts_ways_and_named_nodes_with_kinds(self):
        osm = {"version": 0.6, "elements": [
            {"type":"node","id":1,"lat":-34.92,"lon":138.60,"tags":{"amenity":"cafe","name":"Real Cafe"}},
            {"type":"node","id":2,"lat":-34.921,"lon":138.601},
            {"type":"node","id":3,"lat":-34.921,"lon":138.602},
            {"type":"node","id":4,"lat":-34.922,"lon":138.602},
            {"type":"way","id":10,"nodes":[2,3],"tags":{"highway":"residential","name":"Real Road"}},
            {"type":"way","id":11,"nodes":[2,3,4,2],"tags":{"building":"yes","height":"12"}},
        ]}
        pack = build_pack(osm, [-34.927,138.596,-34.918,138.609], "https://example/osm")
        self.assertEqual(pack["pack"]["id"], "adelaide-cbd")
        self.assertEqual([f["properties"]["kind"] for f in pack["features"]], ["poi","road","building"])
        self.assertEqual(pack["features"][2]["properties"]["height"], 12.0)

    def test_pack_is_geojson_with_west_south_east_north_bounds(self):
        osm={'version':0.6,'elements':[]}
        pack=build_pack(osm,[-34.927,138.596,-34.918,138.609],'https://api.openstreetmap.org/')
        self.assertEqual(pack.get('type'),'FeatureCollection')
        self.assertEqual(pack['bbox'],[138.596,-34.927,138.609,-34.918])

    def test_rejects_unbounded_extent_before_building_a_pack(self):
        with self.assertRaises(ValueError):
            build_pack({'version':0.6,'elements':[]},[-80,-170,80,170],'https://api.openstreetmap.org/')

    def test_parses_cached_osm_xml_without_losing_references(self):
        self.assertTrue(hasattr(fetch_map,'parse_source'),'Cached source parser is required')
        raw=b'<osm version="0.6"><node id="1" lon="138.6" lat="-34.92"><tag k="name" v="Cafe"/><tag k="amenity" v="cafe"/></node><node id="2" lon="138.601" lat="-34.921"/><way id="3"><nd ref="1"/><nd ref="2"/><tag k="highway" v="residential"/></way></osm>'
        osm=fetch_map.parse_source(raw)
        self.assertEqual(osm['elements'][2]['nodes'],[1,2])
        self.assertEqual([f['properties']['kind'] for f in convert_osm(osm)],['poi','road'])

    def test_cli_regenerates_cached_extract_without_a_network_request(self):
        with tempfile.TemporaryDirectory() as tmp:
            root=pathlib.Path(tmp);source=root/'extract.osm';target=root/'adelaide-cbd-v2.geojson'
            source.write_text('<osm version="0.6"><node id="1" lon="138.6" lat="-34.92"><tag k="name" v="Cafe"/><tag k="amenity" v="cafe"/></node></osm>')
            result=subprocess.run([sys.executable,str(pathlib.Path(fetch_map.__file__)),'--source',str(source),'--version','2','--output',str(target)],capture_output=True,text=True)
            self.assertEqual(result.returncode,0,result.stderr)
            pack=json.loads(target.read_text());self.assertEqual(pack['pack']['version'],2);self.assertEqual(pack['features'][0]['properties']['name'],'Cafe')

    def test_default_building_height_metadata_matches_renderer(self):
        osm={'version':0.6,'elements':[
            {'type':'node','id':1,'lon':138.6,'lat':-34.92},
            {'type':'node','id':2,'lon':138.601,'lat':-34.92},
            {'type':'node','id':3,'lon':138.601,'lat':-34.921},
            {'type':'way','id':4,'nodes':[1,2,3,1],'tags':{'building':'yes'}}]}
        self.assertEqual(convert_osm(osm)[0]['properties']['renderingHeightDefault'],8.0)

    def test_rejects_way_with_missing_nodes(self):
        osm = {"version":0.6,"elements":[{"type":"way","id":1,"nodes":[99],"tags":{"building":"yes"}}]}
        with self.assertRaises(ValueError):
            convert_osm(osm)

    def test_rejects_api_error_payload(self):
        with self.assertRaises(ValueError):
            validate_source({"remark":"runtime error"})


if __name__ == "__main__":
    unittest.main()
