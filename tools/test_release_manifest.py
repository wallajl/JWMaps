"""Behavior tests for release metadata, not Android APK compilation."""
import base64
import hashlib
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec

SCRIPT = Path(__file__).with_name('release_manifest.py')

class ReleaseManifestTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        self.key = ec.generate_private_key(ec.SECP256R1())
        self.keyfile = self.root / 'private.pem'
        self.keyfile.write_bytes(self.key.private_bytes(serialization.Encoding.PEM, serialization.PrivateFormat.PKCS8, serialization.NoEncryption()))
        self.apk = self.root / 'JWMaps-0.1.0.apk'
        self.apk.write_bytes(b'unit-test artifact bytes, not a compiled APK')
        self.pack = self.root / 'adelaide-cbd-v1.geojson'
        self.pack.write_text(json.dumps({'type':'FeatureCollection','bbox':[138.596,-34.927,138.609,-34.918], 'pack': {'id':'adelaide-cbd','version':1,'formatVersion':1,'name':'Adelaide city centre','attribution':'OpenStreetMap contributors','sourceUrl':'https://www.openstreetmap.org/copyright'},'features':[]}))
        self.output = self.root / 'out'
    def run_tool(self, *extra):
        return subprocess.run([sys.executable,str(SCRIPT),'--apk',str(self.apk),'--data',str(self.pack),'--version-name','0.1.0','--version-code','1','--key',str(self.keyfile),'--output',str(self.output),*extra],capture_output=True,text=True)
    def test_signs_optional_statewide_database_without_changing_legacy_pack(self):
        import sqlite3
        db=self.root/'south-australia-v3.sqlite'
        with sqlite3.connect(db) as c:
            c.execute('create table metadata (key text primary key,value text not null)')
            c.executemany('insert into metadata values (?,?)',[(k,json.dumps(v)) for k,v in {'id':'south-australia','version':3,'formatVersion':1,'gridStep':0.025}.items()])
            c.execute('create table tiles(x integer,y integer,payload blob,feature_count integer, primary key(x,y))')
        result=self.run_tool('--region',str(db));self.assertEqual(result.returncode,0,result.stderr)
        raw=(self.output/'update-manifest.json').read_bytes();manifest=json.loads(raw)
        self.assertEqual(manifest['region']['sha256'],hashlib.sha256(db.read_bytes()).hexdigest())
        self.assertEqual(manifest['region']['version'],3);self.assertEqual(manifest['data']['id'],'adelaide-cbd')
        self.key.public_key().verify(base64.b64decode((self.output/'update-manifest.sig').read_bytes()),raw,ec.ECDSA(hashes.SHA256()))
        with sqlite3.connect(db) as c:c.execute("update metadata set value='true' where key='version'")
        self.assertNotEqual(self.run_tool('--region',str(db)).returncode,0)

    def test_generates_signature_for_exact_manifest_and_artifact_bytes(self):
        result = self.run_tool()
        self.assertEqual(result.returncode,0,result.stderr)
        raw = (self.output/'update-manifest.json').read_bytes()
        manifest = json.loads(raw)
        self.assertEqual(manifest['app']['sha256'],hashlib.sha256(self.apk.read_bytes()).hexdigest())
        self.assertEqual(manifest['data']['version'],1)
        self.assertEqual(manifest['packageName'],'com.jw.maps')
        self.key.public_key().verify(base64.b64decode((self.output/'update-manifest.sig').read_bytes(),validate=True),raw,ec.ECDSA(hashes.SHA256()))

    def test_refuses_invalid_artifact_names_and_versions(self):
        self.apk=self.apk.rename(self.root/'bad name.apk')
        result=self.run_tool()
        self.assertNotEqual(result.returncode,0)
        self.assertFalse((self.output/'update-manifest.json').exists())

    def test_rejects_invalid_pack_revision_before_signing(self):
        pack=json.loads(self.pack.read_text())
        pack['pack']['version']=True
        self.pack.write_text(json.dumps(pack))
        result=self.run_tool()
        self.assertNotEqual(result.returncode,0)
        self.assertFalse((self.output/'update-manifest.json').exists())

    def test_refuses_non_p256_signing_key(self):
        key=ec.generate_private_key(ec.SECP384R1())
        self.keyfile.write_bytes(key.private_bytes(serialization.Encoding.PEM,serialization.PrivateFormat.PKCS8,serialization.NoEncryption()))
        result=self.run_tool()
        self.assertNotEqual(result.returncode,0,'Release must not sign with an incompatible key')
        self.assertFalse((self.output/'update-manifest.json').exists())

if __name__ == '__main__':
    unittest.main()
