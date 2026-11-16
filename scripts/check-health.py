#!/usr/bin/env python3
"""Certificate-validated aggregate health checks; exit nonzero for an actionable alert."""
import argparse,json,os,pathlib,ssl,time,urllib.request,urllib.error
parser=argparse.ArgumentParser()
parser.add_argument('--url',default='https://localhost:18443')
parser.add_argument('--ca',type=pathlib.Path)
parser.add_argument('--state',type=pathlib.Path,default=pathlib.Path('.local/monitor-state.json'))
args=parser.parse_args()
token=os.environ.get('SECRETCHAT_METRICS_TOKEN','')
if len(token)<32: raise SystemExit('Set SECRETCHAT_METRICS_TOKEN to the operations credential')
context=ssl.create_default_context(cafile=str(args.ca) if args.ca else None)
def metric(name):
    request=urllib.request.Request(args.url.rstrip('/')+'/actuator/metrics/'+name,headers={'Authorization':'Bearer '+token})
    try:
        with urllib.request.urlopen(request,context=context,timeout=10) as response:
            return json.load(response)['measurements'][0]['value']
    except urllib.error.HTTPError as error:
        if error.code==404 and name=='secretchat.authentication.failures': return 0
        raise
try:
    values={name:metric(name) for name in ['secretchat.ttl.backlog','secretchat.reset.backlog','secretchat.rekey.locked','secretchat.backlog.last.success.seconds','secretchat.authentication.failures']}
except Exception as error:
    print(json.dumps({'alerts':['Monitoring endpoint unavailable'],'error_type':type(error).__name__}));raise SystemExit(1)
now=time.time();previous={}
if args.state.exists(): previous=json.loads(args.state.read_text())
alerts=[]
if values['secretchat.ttl.backlog']>0: alerts.append('TTL cleanup backlog')
if values['secretchat.reset.backlog']>0: alerts.append('Room reset backlog')
if now-values['secretchat.backlog.last.success.seconds']>30: alerts.append('Backlog metrics are stale')
if 0<now-previous.get('time',0)<=120 and values['secretchat.authentication.failures']-previous.get('failures',0)>=20: alerts.append('Authentication failure burst')
args.state.parent.mkdir(parents=True,exist_ok=True)
args.state.write_text(json.dumps({'time':now,'failures':values['secretchat.authentication.failures']}))
print(json.dumps({'alerts':alerts,'metrics':values}))
raise SystemExit(bool(alerts))
