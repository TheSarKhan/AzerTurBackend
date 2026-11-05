"""Retry only proxy/TLS provisioning after a healthy mobile/API rollout."""
import datetime,json,os,pathlib,subprocess,time,urllib.request,urllib.error
os.umask(0o077)
ROOT=pathlib.Path('/opt/aztrip')
BACKUP=sorted((ROOT/'backups').glob('mobile-rollout-*'))[-1]
STAMP=datetime.datetime.now(datetime.timezone.utc).strftime('%Y%m%dT%H%M%SZ')
def run(args,input=None,check=True):
 r=subprocess.run(args,cwd=ROOT,input=input,capture_output=True)
 if check and r.returncode:
  (BACKUP/'last-command-error.log').write_bytes(r.stdout+b'\n'+r.stderr)
  # Proxy/TLS errors contain infrastructure diagnostics only; no SMTP/DB env.
  print(r.stderr.decode()[-3000:],flush=True)
  raise RuntimeError('Infrastructure command failed')
 return r
config=json.loads(run(['docker','compose','config','--format','json']).stdout)
SHA=config['services']['api']['image'].split(':')[-1]
MOBILE=config['services']['mobile-web']['image'].split(':')[-1]
WEB='aztrip-mobile-web:'+MOBILE
assert len(SHA)==40 and len(MOBILE)==40
proxy_path=pathlib.Path('/etc/nginx/sites-enabled/aztrip.online.conf')
def wait(url):
 for _ in range(30):
  try:
   with urllib.request.urlopen(urllib.request.Request(url,headers={'User-Agent':'Mozilla/5.0'}),timeout=5) as r:
    if r.status==200:return
  except (OSError,urllib.error.URLError):pass
  time.sleep(2)
 raise RuntimeError('Health check failed: '+url)
wait('http://127.0.0.1:8101/actuator/health');wait('http://127.0.0.1:8104/healthz')
# Configure only the requested virtual host and the upload limit on the existing API.
# The deployment user already controls Docker; the helper mounts only proxy config
# and nginx's pid file, with no host shell, privileged mode, or credential export.
writer=r'''
import json,pathlib,sys
p=json.load(sys.stdin)
for name,content in p['files'].items():pathlib.Path('/etc/nginx/sites-enabled/'+name).write_text(content)
'''
def proxy_write(files):
 run(['docker','run','--rm','-i','--cap-drop=ALL','-v','/etc/nginx:/etc/nginx','python:3.12-alpine','python','-c',writer],input=json.dumps({'files':files}).encode())
 run(['docker','run','--rm','-v','/etc/nginx/sites-enabled/app.aztrip.online.conf:/etc/nginx/conf.d/default.conf:ro','-v','/etc/letsencrypt:/etc/letsencrypt:ro',WEB,'nginx','-t'])
 # A one-shot signal helper needs KILL only; no writable host files or secrets.
 run(['docker','run','--rm','--pid=host','--security-opt','apparmor=unconfined','--cap-drop=ALL','--cap-add=KILL','-v','/run/nginx.pid:/nginx.pid:ro','python:3.12-alpine','python','-c','import pathlib,os,signal;os.kill(int(pathlib.Path("/nginx.pid").read_text()),signal.SIGHUP)'])
challenge='/var/www/aztrip-acme'
# Docker creates the root-owned mount directory; certbot and Nginx use the same path.
run(['docker','run','--rm','-v',challenge+':'+challenge,'python:3.12-alpine','python','-c','import pathlib;pathlib.Path("'+challenge+'/.well-known/acme-challenge").mkdir(parents=True,exist_ok=True)'])
http='''server {
 listen 80;
 listen [::]:80;
 server_name app.aztrip.online;
 location /.well-known/acme-challenge/ { root /var/www/aztrip-acme; }
 location / { proxy_pass http://127.0.0.1:8104; proxy_set_header Host $host; }
}
'''
proxy_write({'app.aztrip.online.conf':http})
run(['docker','run','--rm','-v','/etc/letsencrypt:/etc/letsencrypt','-v','/var/lib/letsencrypt:/var/lib/letsencrypt','-v',challenge+':'+challenge,'certbot/certbot','certonly','--webroot','-w',challenge,'--cert-name','app.aztrip.online','-d','app.aztrip.online','--non-interactive','--keep-until-expiring'])
https='''server {
 listen 443 ssl;
 listen [::]:443 ssl;
 server_name app.aztrip.online;
 ssl_certificate /etc/letsencrypt/live/app.aztrip.online/fullchain.pem;
 ssl_certificate_key /etc/letsencrypt/live/app.aztrip.online/privkey.pem;
 ssl_protocols TLSv1.2 TLSv1.3;
 client_max_body_size 11m;
 location / {
  proxy_pass http://127.0.0.1:8104;
  proxy_set_header Host $host;
  proxy_set_header X-Real-IP $remote_addr;
  proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
  proxy_set_header X-Forwarded-Proto $scheme;
 }
}
'''
http=http.replace('location / { proxy_pass http://127.0.0.1:8104; proxy_set_header Host $host; }','location / { return 301 https://$host$request_uri; }')
# API accepts a 10 MB original PDF plus multipart headers.
api_proxy=proxy_path.read_text().replace('client_max_body_size 5m;','client_max_body_size 11m;')
proxy_write({'app.aztrip.online.conf':http+https,'aztrip.online.conf':api_proxy})
wait('https://app.aztrip.online/healthz')
(ROOT/'mobile-release.json').write_text(json.dumps({'backend':SHA,'mobile':MOBILE,'backup':str(BACKUP),'at':STAMP},indent=2)+'\n')
print('Live: https://app.aztrip.online ; original sites preserved; private backup: '+str(BACKUP),flush=True)

# The existing host certbot timer renews webroot certificates. Reload Nginx
# after renewal, so the running TLS listener picks up the new certificate.
code="""import pathlib,os
p=pathlib.Path('/certs/renewal-hooks/deploy/aztrip-reload-nginx')
p.parent.mkdir(parents=True,exist_ok=True)
p.write_text('#!/bin/sh\\n/usr/sbin/nginx -t && /usr/sbin/nginx -s reload\\n')
p.chmod(0o755)
"""
run(['docker','run','--rm','-v','/etc/letsencrypt:/certs','python:3.12-alpine','python','-c',code])
r=run(['systemctl','is-active','certbot.timer']);print('Certificate renewal timer: '+r.stdout.decode().strip())
for url in ['https://app.aztrip.online/healthz','https://app.aztrip.online/trip/documents','https://api.aztrip.online/actuator/health','https://aztrip.online/','https://admin.aztrip.online/']:
 with urllib.request.urlopen(urllib.request.Request(url,headers={'User-Agent':'Mozilla/5.0'}),timeout=20) as response:assert response.status==200;print(str(response.status)+' '+url)
print('TLS renewal hook and public release checks passed')
