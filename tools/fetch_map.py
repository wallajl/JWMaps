#!/usr/bin/env python3
"""Acquire bounded OpenStreetMap XML and convert it to a JWMaps GeoJSON pack."""
import argparse, hashlib, json, math, pathlib, sys, time
import xml.etree.ElementTree as ET
from urllib.request import Request, urlopen

DEFAULT_BBOX = [-34.927, 138.596, -34.918, 138.609]


def validate_source(osm):
    if not isinstance(osm, dict) or osm.get("version") != 0.6 or not isinstance(osm.get("elements"), list):
        raise ValueError("invalid or incomplete OSM API response")
    if "remark" in osm and ("error" in str(osm["remark"]).lower() or "runtime" in str(osm["remark"]).lower()):
        raise ValueError("OSM API error: " + str(osm["remark"]))
    return osm


def _coord(x):
    if not isinstance(x, (int, float)) or not math.isfinite(x): raise ValueError("invalid coordinate")
    return float(x)


def _height(tags):
    for key in ("height", "building:levels"):
        if key in tags:
            try: return float(tags[key]) if key == "height" else float(tags[key]) * 3.0
            except (ValueError, TypeError): pass
    return None


def convert_osm(osm):
    validate_source(osm)
    nodes = {e["id"]: e for e in osm["elements"] if e.get("type") == "node"}
    out = []
    for e in osm["elements"]:
        tags = e.get("tags") or {}
        if e.get("type") == "node" and tags.get("name") and any(k in tags for k in ("amenity","shop","tourism")):
            geom = {"type":"Point", "coordinates":[_coord(e["lon"]), _coord(e["lat"])]}
            props = {"kind":"poi", "name":tags["name"], "osmId":e["id"], "tags":tags}
        elif e.get("type") == "way":
            refs = e.get("nodes", [])
            if len(refs) < 2 or any(r not in nodes for r in refs): raise ValueError("incomplete way geometry")
            coords = [[_coord(nodes[r]["lon"]), _coord(nodes[r]["lat"])] for r in refs]
            if "highway" in tags:
                geom, kind = {"type":"LineString", "coordinates":coords}, "road"
            elif "building" in tags:
                if len(coords) < 4 or coords[0] != coords[-1]: continue
                geom, kind = {"type":"Polygon", "coordinates":[coords]}, "building"
            elif tags.get("natural") == "water" or tags.get("waterway"):
                if len(coords) < 4 or coords[0] != coords[-1]: continue
                geom, kind = {"type":"Polygon", "coordinates":[coords]}, "water"
            elif tags.get("leisure") == "park" or tags.get("landuse") in ("grass","recreation_ground"):
                if len(coords) < 4 or coords[0] != coords[-1]: continue
                geom, kind = {"type":"Polygon", "coordinates":[coords]}, "park"
            else: continue
            props = {"kind":kind, "osmId":e["id"], "tags":tags}
            h = _height(tags)
            if h is not None: props["height"] = h
            elif kind == "building": props["renderingHeightDefault"] = 8.0
            if tags.get("name"): props["name"] = tags["name"]
        else: continue
        out.append({"type":"Feature", "geometry":geom, "properties":props})
    return out


def validate_bbox(bbox):
    if len(bbox)!=4 or any(type(v) not in (int,float) or not math.isfinite(v) for v in bbox):
        raise ValueError('Expected four finite south/west/north/east coordinates')
    s,w,n,e=bbox
    if not (-85<=s<n<=85 and -180<=w<e<=180 and n-s<=0.03 and e-w<=0.03):
        raise ValueError('Use a bounded extent no wider or taller than 0.03 degrees')
    return s,w,n,e


def build_pack(osm, bbox, source_url):
    s,w,n,e=validate_bbox(bbox)
    return {"type":"FeatureCollection","pack":{"id":"adelaide-cbd","version":1,"formatVersion":1,"name":"Adelaide city centre","attribution":"© OpenStreetMap contributors, ODbL 1.0","sourceUrl":source_url},"bbox":[w,s,e,n],"features":convert_osm(osm)}


def fetch(bbox, cache):
    s,w,n,e = validate_bbox(bbox)
    url = f"https://api.openstreetmap.org/api/0.6/map?bbox={w},{s},{e},{n}"
    with urlopen(Request(url, headers={"User-Agent":"JWMaps/1.0 bounded OSM map pack"}), timeout=90) as response:
        data = response.read(20*1024*1024+1)
    if len(data)>20*1024*1024:
        raise ValueError('Source map exceeds the 20 MiB acquisition limit')
    cache.mkdir(parents=True, exist_ok=True)
    stamp = str(int(time.time()))
    raw = cache / f"adelaide-cbd-{stamp}.osm.json"
    raw.write_bytes(data)
    (cache / f"adelaide-cbd-{stamp}.receipt.json").write_text(json.dumps({"url":url,"sha256":hashlib.sha256(data).hexdigest(),"sizeBytes":len(data),"bbox":bbox}, indent=2)+"\n")
    return parse_source(data), url, raw


def parse_source(data):
    root = ET.fromstring(data)
    if root.tag!='osm' or root.get('version')!='0.6':
        raise ValueError('Expected an OSM 0.6 XML response')
    elements = []
    for x in root:
        if x.tag == "node":
            e = {"type":"node", "id":int(x.attrib["id"]), "lat":float(x.attrib["lat"]), "lon":float(x.attrib["lon"])}
        elif x.tag == "way":
            e = {"type":"way", "id":int(x.attrib["id"]), "nodes":[int(nd.attrib["ref"]) for nd in x.findall("nd")]}
        else: continue
        tags = {t.attrib["k"]:t.attrib["v"] for t in x.findall("tag")}
        if tags: e["tags"] = tags
        elements.append(e)
    return validate_source({"version":0.6,"elements":elements})


def main():
    ap=argparse.ArgumentParser(description=__doc__)
    ap.add_argument('--bbox',nargs=4,type=float,default=DEFAULT_BBOX,metavar=('S','W','N','E'))
    ap.add_argument('--version',type=int,default=1)
    ap.add_argument('--output',default='app/src/main/assets/adelaide-cbd-v1.geojson')
    ap.add_argument('--cache',type=pathlib.Path,default=pathlib.Path.home()/'.cache/jwmaps/osm')
    ap.add_argument('--source',type=pathlib.Path,help='Regenerate from a cached OSM XML extract, without network access')
    a=ap.parse_args()
    if a.version<1:ap.error('Map revision must be a positive integer')
    bbox=list(validate_bbox(a.bbox))
    if a.source:
        raw=a.source
        if raw.stat().st_size>20*1024*1024:ap.error('Source exceeds the 20 MiB limit')
        osm=parse_source(raw.read_bytes())
        s,w,n,e=bbox
        url=f'https://api.openstreetmap.org/api/0.6/map?bbox={w},{s},{e},{n}'
    else:
        osm,url,raw=fetch(bbox,a.cache)
    pack=build_pack(osm,bbox,url);pack['pack']['version']=a.version
    p=pathlib.Path(a.output);p.parent.mkdir(parents=True,exist_ok=True)
    data=(json.dumps(pack,ensure_ascii=False,separators=(',',':'),allow_nan=False)+'\n').encode('utf-8')
    temporary=p.with_name(p.name+'.tmp');temporary.write_bytes(data);temporary.replace(p)
    print(json.dumps({'output':str(p),'raw':str(raw),'features':len(pack['features']),'bytes':len(data),'sha256':hashlib.sha256(data).hexdigest(),'sourceUrl':url}))

if __name__=='__main__':main()
