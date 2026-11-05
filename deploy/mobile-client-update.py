"""Replace only the static client; keep API, migrations and account keys intact."""
import json,pathlib,subprocess,sys,time,urllib.request
root=pathlib.Path('/opt/aztrip');sha=sys.argv[1]
assert len(sha)==40 and all(c in '0123456789abcdef' for c in sha)
p=root/'compose.override.yml';old=p.read_text();config=json.loads(old)
config['services']['mobile-web']['image']='aztrip-mobile-web:'+sha
p.write_text(json.dumps(config,indent=2)+'\n');p.chmod(0o600)
try:
 subprocess.run(['docker','compose','up','-d','--no-deps','mobile-web'],cwd=root,check=True)
 for _ in range(30):
  try:
   with urllib.request.urlopen('http://127.0.0.1:8104/healthz',timeout=3) as r:
    if r.status==200:break
  except OSError:pass
  time.sleep(2)
 else:raise RuntimeError('Static client health check failed')
 print('Mobile client updated; API untouched')
except Exception:
 p.write_text(old);subprocess.run(['docker','compose','up','-d','--no-deps','mobile-web'],cwd=root,check=False)
 raise
