#!/usr/bin/env python3
"""Create and sign JWMaps release metadata. Validate APK identity separately."""
import argparse
import base64
import hashlib
import json
import re
from pathlib import Path
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec

def artifact(path):
    return {'asset':path.name,'sha256':hashlib.sha256(path.read_bytes()).hexdigest(),'sizeBytes':path.stat().st_size}

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--apk',type=Path,required=True)
    parser.add_argument('--data',type=Path,required=True)
    parser.add_argument('--key',type=Path,required=True)
    parser.add_argument('--output',type=Path,required=True)
    parser.add_argument('--version-name',required=True)
    parser.add_argument('--version-code',type=int,required=True)
    args=parser.parse_args()
    if args.version_code<1 or not re.fullmatch(r'[0-9]+\.[0-9]+\.[0-9]+',args.version_name):
        parser.error('Use a positive version code and stable major.minor.patch version name')
    for path,limit in [(args.apk,100*1024*1024),(args.data,25*1024*1024)]:
        if not re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9._-]{0,119}',path.name) or '..' in path.name:
            parser.error('Artifact names must be simple safe filenames')
        if not path.is_file() or not 0<path.stat().st_size<=limit:
            parser.error('Artifact missing, empty or over the size limit')
    pack=json.loads(args.data.read_text())['pack']
    if not re.fullmatch(r'[a-z0-9][a-z0-9-]{0,63}',pack.get('id','')):
        parser.error('Invalid map pack id')
    if args.apk.name!='JWMaps-'+args.version_name+'.apk' or args.data.name!=f"{pack['id']}-v{pack['version']}.geojson":
        parser.error('Artifact filenames must match their declared versions')
    if type(pack.get('version')) is not int or pack['version']<1 or type(pack.get('formatVersion')) is not int or pack['formatVersion']!=1:
        parser.error('Map pack version must be a positive integer and formatVersion must be 1')
    manifest={'schemaVersion':1,'channel':'stable','packageName':'com.jw.maps','app':dict(artifact(args.apk),versionCode=args.version_code,versionName=args.version_name,minSdk=28),'data':dict(artifact(args.data),id=pack['id'],version=pack['version'],formatVersion=pack['formatVersion'])}
    raw=(json.dumps(manifest,sort_keys=True,separators=(',',':'))+'\n').encode()
    key=serialization.load_pem_private_key(args.key.read_bytes(),None)
    if not isinstance(key,ec.EllipticCurvePrivateKey) or not isinstance(key.curve,ec.SECP256R1):
        parser.error('Signing key must be ECDSA P-256')
    signature=key.sign(raw,ec.ECDSA(hashes.SHA256()))
    args.output.mkdir(parents=True,exist_ok=True)
    (args.output/'update-manifest.json').write_bytes(raw)
    (args.output/'update-manifest.sig').write_bytes(base64.b64encode(signature))
    print('Created signed release metadata')

if __name__=='__main__':
    main()
