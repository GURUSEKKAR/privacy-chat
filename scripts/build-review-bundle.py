#!/usr/bin/env python3
"""Create a secret-free source/review archive from the Git-visible project inventory."""
import argparse,datetime,hashlib,json,pathlib,subprocess,zipfile

root=pathlib.Path(__file__).resolve().parent.parent
parser=argparse.ArgumentParser()
parser.add_argument('--output',type=pathlib.Path,default=root/'target'/'release')
args=parser.parse_args()
output=args.output.resolve();output.mkdir(parents=True,exist_ok=True)
inventory=subprocess.check_output(['git','ls-files','--cached','--others','--exclude-standard','-z'],cwd=root).decode().split('\0')
records=[];payload=[]
for name in sorted(set(filter(None,inventory))):
    relative=pathlib.PurePosixPath(name)
    if any(part in {'.git','.local','target','__pycache__'} for part in relative.parts):continue
    if relative.name=='.env' or relative.suffix.lower() in {'.pem','.p12','.key','.dump','.age'}:
        raise SystemExit('Refusing private material in source inventory: '+name)
    file=root/name
    if file.is_symlink():raise SystemExit('Review source must not contain symlinks: '+name)
    if not file.is_file():continue
    content=file.read_bytes()
    records.append({'path':name,'sha256':hashlib.sha256(content).hexdigest(),'bytes':len(content)})
    payload.append((name,content,file.stat().st_mode))
if not records:raise SystemExit('No source files found')
digest=hashlib.sha256(json.dumps(records,sort_keys=True,separators=(',',':')).encode()).hexdigest()
manifest={'created_at_utc':datetime.datetime.now(datetime.timezone.utc).isoformat(),'source_sha256':digest,'files':records,
    'status':'release candidate; external approval is not implied'}
(output/'source-manifest.json').write_text(json.dumps(manifest,indent=2)+'\n')
archive=output/'privacy-chat-source-review.zip'
with zipfile.ZipFile(archive,'w',zipfile.ZIP_DEFLATED) as bundle:
    for name,content,mode in payload:
        item=zipfile.ZipInfo('privacy-chat/'+name);item.external_attr=mode<<16;item.compress_type=zipfile.ZIP_DEFLATED
        bundle.writestr(item,content)
    bundle.writestr('source-manifest.json',json.dumps(manifest,indent=2)+'\n')
checksums={'source_sha256':digest,'archive':archive.name,'archive_sha256':hashlib.sha256(archive.read_bytes()).hexdigest()}
(output/'checksums.json').write_text(json.dumps(checksums,indent=2)+'\n')
print('Review archive:',archive)
print('Source digest:',digest)
print('Files:',len(records))
