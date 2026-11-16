#!/usr/bin/env python3
"""Validate release evidence and human attestations; never manufacture approval."""
import argparse,datetime,hashlib,json,pathlib,subprocess,urllib.request
from release_common import source_inventory,verification_digest

root=pathlib.Path(__file__).resolve().parent.parent
parser=argparse.ArgumentParser()
parser.add_argument('--approvals',type=pathlib.Path,default=root/'.local/release/approvals.json')
parser.add_argument('--manifest',type=pathlib.Path,default=root/'target/release/source-manifest.json')
parser.add_argument('--output',type=pathlib.Path,default=root/'target/release/readiness.json')
args=parser.parse_args();checks=[]
def check(name,passed,detail):checks.append({'name':name,'passed':bool(passed),'detail':detail})
def read(file):return json.loads(file.read_text()) if file.is_file() else {}
tests=read(root/'docs/verification/test-summary.json')
totals=tests.get('totals',{})
inputs=verification_digest(root)
check('automated_acceptance',tests.get('verification_inputs_sha256')==inputs and totals.get('tests',0)>0 and all(totals.get(k,1)==0 for k in ['failures','errors','skipped']),totals)
audit=read(root/'docs/verification/dependency-audit.json')
check('dependency_audit',audit.get('package_count',0)>0 and audit.get('findings')==[],audit.get('timestamp','missing'))
sast=read(root/'docs/verification/static-analysis.json')
check('static_analysis',sast.get('verification_inputs_sha256')==inputs and bool(sast.get('analyzed_modules')) and sast.get('findings')==[],sast.get('tool','missing'))
container=read(root/'docs/verification/container-scan-status.json')
check('container_os_audit',container.get('status')=='passed' and container.get('high_critical_findings')==0 and container.get('network_during_private_image_scan')=='none',container.get('scope','missing'))
manifest=read(args.manifest);digest=manifest.get('source_sha256')
source_matches=bool(digest) and bool(manifest.get('files')) and set(source_inventory(root))=={f['path'] for f in manifest.get('files',[])}
for file in manifest.get('files',[]):
    target=(root/file['path']).resolve()
    if root not in target.parents or not target.is_file() or hashlib.sha256(target.read_bytes()).hexdigest()!=file['sha256']:source_matches=False
check('source_manifest',source_matches,'Current source must match the complete review manifest')
committed=not subprocess.check_output(['git','status','--porcelain'],cwd=root,text=True).strip()
check('committed_source',committed,'Release source must be committed without unreviewed changes')
approval=read(args.approvals)
bound=bool(digest) and approval.get('source_sha256')==digest
threat=approval.get('threat_model',{})
check('threat_model_approval',bound and threat.get('decision')=='approved' and threat.get('approver') and threat.get('approved_at'), 'Human sign-off bound to this source digest is required')
review=approval.get('independent_security_review',{})
report=(root/review.get('report_path','missing-review-report')).resolve()
report_matches=root in report.parents and report.is_file() and hashlib.sha256(report.read_bytes()).hexdigest()==review.get('report_sha256')
check('independent_security_review',bound and review.get('decision')=='approved' and review.get('reviewer') and review.get('reviewed_at') and review.get('open_critical_high_findings')==0 and report_matches,
      'Independent reviewer report and finding closure must be supplied; identity/independence are human attestations')
ci=approval.get('hosted_ci',{});ci_valid=False;ci_detail='A successful hosted verify run for the pushed source commit is required'
if ci.get('run_id'):
    try:
        request=urllib.request.Request('https://api.github.com/repos/GURUSEKKAR/privacy-chat/actions/runs/'+str(int(ci['run_id'])),headers={'Accept':'application/vnd.github+json'})
        with urllib.request.urlopen(request,timeout=20) as response:run=json.load(response)
        head=subprocess.check_output(['git','rev-parse','HEAD'],cwd=root,text=True).strip()
        ci_valid=run.get('conclusion')=='success' and run.get('head_sha')==head and run.get('path')=='.github/workflows/ci.yml'
        ci_detail=run.get('html_url',ci_detail)
    except Exception as error:ci_detail='Hosted CI evidence unavailable: '+type(error).__name__
check('hosted_ci',ci_valid,ci_detail)
result={'checked_at_utc':datetime.datetime.now(datetime.timezone.utc).isoformat(),'production_ready':all(c['passed'] for c in checks),'checks':checks}
args.output.parent.mkdir(parents=True,exist_ok=True);args.output.write_text(json.dumps(result,indent=2)+'\n')
for item in checks:print(('PASS ' if item['passed'] else 'PENDING ')+item['name'])
raise SystemExit(0 if result['production_ready'] else 2)
