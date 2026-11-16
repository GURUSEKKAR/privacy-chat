#!/usr/bin/env python3
"""Download public OS vulnerability data separately; scan private image with networking disabled."""
import argparse,datetime,gzip,json,os,pathlib,shutil,subprocess,tempfile

root=pathlib.Path(__file__).resolve().parent.parent
parser=argparse.ArgumentParser()
parser.add_argument('--image',default='secretchat-acceptance-backend')
parser.add_argument('--output',type=pathlib.Path,default=root/'docs/verification/container-scan.json')
args=parser.parse_args();args.output=args.output.resolve();args.output.parent.mkdir(parents=True,exist_ok=True)
tool='aquasec/trivy:0.76.2'
# Match bind-mounted file ownership on Linux; Docker Desktop maps it on macOS.
scanner_user=str(os.getuid())+':'+str(os.getgid())
if shutil.disk_usage(root).free<3*1024**3:raise SystemExit('At least 3 GiB free storage is required for the public OS database and scan scratch data')
local=root/'.local';local.mkdir(exist_ok=True)
cid_dir=pathlib.Path(tempfile.mkdtemp(prefix='scan-processes-',dir=local));containers=[]
def run(command,timeout=300):
    subprocess.run([str(x) for x in command],check=True,timeout=timeout)
try:
    with tempfile.TemporaryDirectory(prefix='offline-os-scan-',dir=local) as temporary:
        stage=pathlib.Path(temporary);cache=stage/'cache';cache.mkdir();inputs=stage/'input';inputs.mkdir()
        public_cid=cid_dir/'public.cid';private_cid=cid_dir/'private.cid';containers=[public_cid,private_cid]
        # No application image, archive, source or Docker socket is mounted during this download.
        run(['docker','run','--rm','--cidfile',public_cid,'--user',scanner_user,'--env','TRIVY_CACHE_DIR=/cache',
             '-v',str(cache)+':/cache',tool,
             'image','--no-progress','--download-db-only'])
        archive=inputs/'backend.tar.gz'
        process=subprocess.Popen(['docker','image','save',args.image],stdout=subprocess.PIPE)
        try:
            with gzip.open(archive,'wb',compresslevel=1) as output:shutil.copyfileobj(process.stdout,output)
            if process.wait()!=0:raise RuntimeError('Image export failed')
        finally:
            if process.stdout:process.stdout.close()
            if process.poll() is None:process.kill()
        run(['docker','run','--rm','--network','none','--cidfile',private_cid,
             '--user',scanner_user,'--env','TRIVY_CACHE_DIR=/cache',
             '-v',str(cache)+':/cache','-v',str(inputs)+':/input:ro',
             '-v',str(args.output.parent)+':/output',tool,'image','--no-progress',
             '--skip-db-update','--skip-java-db-update','--skip-version-check','--offline-scan',
             '--pkg-types','os','--scanners','vuln','--severity','HIGH,CRITICAL','--format','json',
             '--output','/output/'+args.output.name,'--input','/input/backend.tar.gz'])
        report=json.loads(args.output.read_text());findings=[]
        for result in report.get('Results',[]):
            findings.extend(result.get('Vulnerabilities') or [])
        summary={'checked_at_utc':datetime.datetime.now(datetime.timezone.utc).isoformat(),
            'status':'passed' if not findings else 'findings','scanner':tool,'scope':'OS packages; Java dependencies audited separately with OSV',
            'image':args.image,'network_during_private_image_scan':'none','high_critical_findings':len(findings)}
        args.output.with_name('container-scan-status.json').write_text(json.dumps(summary,indent=2)+'\n')
        print(json.dumps(summary))
        if findings:raise SystemExit(1)
finally:
    for file in containers:
        if file.exists():
            identifier=file.read_text().strip()
            subprocess.run(['docker','rm','-f',identifier],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)

    shutil.rmtree(cid_dir,ignore_errors=True)
