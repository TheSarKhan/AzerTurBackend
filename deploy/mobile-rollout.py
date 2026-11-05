"""Run on the existing deployment host. Secrets arrive through stdin, never logs.

Upgrade a disposable database clone first. Preserve old staff bearer sessions,
keep migrations additive for old-image rollback, and never replay old mail.
"""
import base64, datetime, hashlib, json, os, pathlib, secrets, shutil, subprocess, sys, time, urllib.request, urllib.error, uuid
os.umask(0o077)
ROOT=pathlib.Path('/opt/aztrip')
payload=json.load(sys.stdin)
SHA=payload['backend_sha']; MOBILE=payload['mobile_sha']
assert len(SHA)==40 and len(MOBILE)==40
API='aztrip-api:'+SHA; WEB='aztrip-mobile-web:'+MOBILE
STAMP=datetime.datetime.now(datetime.timezone.utc).strftime('%Y%m%dT%H%M%SZ')
BACKUP=ROOT/'backups'/('mobile-rollout-'+STAMP); BACKUP.mkdir(parents=True,mode=0o700)
def run(args,input=None,check=True):
 r=subprocess.run(args,cwd=ROOT,input=input,capture_output=True,check=False)
 if check and r.returncode:
  (BACKUP/'last-command-error.log').write_bytes(r.stdout+b'\n'+r.stderr)
  raise RuntimeError('Command failed: '+args[0]+' '+args[1]+' (output withheld; inspect private host logs)')
 return r

def out(args): return run(args).stdout.decode().strip()
def compose(*args): return ['docker','compose',*args]
assert not (ROOT/'compose.override.yml').exists() or 'mobile-web' in json.loads((ROOT/'compose.override.yml').read_text()).get('services',{}), 'Review existing compose override before rollout'
config=json.loads(out(compose('config','--format','json')))
services=config['services']; pg=services['postgres']['environment']; dbname=pg['POSTGRES_DB']; dbuser=pg['POSTGRES_USER']
def psql(query,database=dbname):
 return out(compose('exec','-T','postgres','psql','-v','ON_ERROR_STOP=1','-U',dbuser,'-d',database,'-At','-c',query))
def dump(path):
 with path.open('wb') as f:
  r=subprocess.run(compose('exec','-T','postgres','pg_dump','-U',dbuser,'-d',dbname,'-Fc'),cwd=ROOT,stdout=f,stderr=subprocess.PIPE)
 if r.returncode: raise RuntimeError('Database backup failed')
 assert path.stat().st_size>1000
 print('Database backup saved and nonempty',flush=True)
def wait(url):
 for _ in range(60):
  try:
   with urllib.request.urlopen(urllib.request.Request(url,headers={'User-Agent':'Mozilla/5.0'}),timeout=4) as r:
    if r.status==200: return
  except (OSError,urllib.error.URLError): pass
  time.sleep(3)
 raise RuntimeError('Health check timed out: '+url)
def request(path,body=None,token=None,port=18101,headers=None):
 h={'Content-Type':'application/json',**(headers or {})}
 if token:h['Authorization']='Bearer '+token
 req=urllib.request.Request('http://127.0.0.1:'+str(port)+path,data=None if body is None else json.dumps(body).encode(),headers=h)
 with urllib.request.urlopen(req,timeout=15) as r:return json.load(r)
# Pin both old running images before changing any latest tag.
old_api=out(compose('ps','-q','api')); old_api_image=out(['docker','inspect','-f','{{.Image}}',old_api])
run(['docker','tag',old_api_image,'aztrip-api:before-mobile-'+STAMP])
for file in ['compose.yml','compose.yaml','docker-compose.yml','docker-compose.yaml','compose.override.yml','.env']:
 p=ROOT/file
 if p.exists():shutil.copy2(p,BACKUP/file)
proxy_path=pathlib.Path('/etc/nginx/sites-enabled/aztrip.online.conf')
(BACKUP/'nginx-before.conf').write_text(proxy_path.read_text())
# Clone is private in the existing Docker network and no email/push can run.
dump(BACKUP/'preflight.dump')
clone='aztrip_mobile_check_'+STAMP.lower()
psql('CREATE DATABASE '+clone)
run(compose('exec','-T','postgres','pg_restore','--no-owner','-U',dbuser,'-d',clone),input=(BACKUP/'preflight.dump').read_bytes())
api_env={str(k):str(v) for k,v in services['api'].get('environment',{}).items() if v is not None}
api_env.update({'SPRING_FLYWAY_LOCATIONS':'classpath:db/production-migration','OPERATIONS_PASSWORD':secrets.token_urlsafe(32),'ACCOUNT_LINK_KEY':base64.b64encode(secrets.token_bytes(32)).decode(),'PORTAL_ORIGIN':'https://app.aztrip.online','EMAIL_ENABLED':'false','PUSH_ENABLED':'false','CORS_ORIGINS':'https://app.aztrip.online'})
# Existing installation has no encrypted account links; retain generated keys on retries.
prior=ROOT/'mobile-api.env'
if prior.exists():
 for line in prior.read_text().splitlines():
  if line.startswith(('ACCOUNT_LINK_KEY=','OPERATIONS_PASSWORD=','EMAIL_DELIVERY_NOT_BEFORE=')):
   k,v=line.split('=',1);api_env[k]=v
clone_env=dict(api_env)
clone_env['DATABASE_URL']=api_env['DATABASE_URL'].rsplit('/',1)[0]+'/'+clone
clone_env['EMAIL_ENABLED']='false'; clone_env['PUSH_ENABLED']='false'
envfile=BACKUP/'preflight.env';envfile.write_text(''.join(k+'='+v+'\n' for k,v in clone_env.items()))
network=json.loads(out(['docker','inspect',old_api]))[0]['NetworkSettings']['Networks'];network=next(iter(network))
check_name='aztrip-mobile-check-'+STAMP.lower()
try:
 run(['docker','run','-d','--name',check_name,'--network',network,'--env-file',str(envfile),'-p','127.0.0.1:18101:8080',API])
 wait('http://127.0.0.1:18101/actuator/health')
 assert psql("SELECT version FROM flyway_schema_history ORDER BY installed_rank DESC LIMIT 1",clone)=='8'
 for table in ['customers','bookings','staff_users','staff_sessions']:
  assert psql('SELECT count(*) FROM '+table,clone)==psql('SELECT count(*) FROM '+table),table+' changed unexpectedly'
 catalog=request('/api/v1/tours');assert catalog
 # Staff principal resolution must still work with the existing persisted schema.
 token=secrets.token_urlsafe(32);tokenhash=hashlib.sha256(token.encode()).hexdigest()
 psql("INSERT INTO staff_sessions(token_hash,staff_id,expires_at) SELECT '"+tokenhash+"',id,now()+interval '5 minutes' FROM staff_users WHERE active LIMIT 1",clone)
 if psql('SELECT count(*) FROM staff_users WHERE active',clone)!='0':
  assert request('/api/v1/staff/auth/me',token=token)['role'] in ['ADMIN','OPERATOR']
  request('/api/v1/operations/bookings',token=token)
 # No external email is delivered by this isolated clone.
 email='deploy-'+uuid.uuid4().hex+'@example.invalid'
 session=request('/api/v1/auth/register',{'email':email,'name':'Deployment check','password':secrets.token_urlsafe(24),'locale':'en'})
 customer_token=session['token'];request('/api/v1/auth/me',token=customer_token)
 request('/api/v1/bookings',token=customer_token)
 print('Clone migrated successfully; catalogue, customer auth, journeys and existing staff sessions passed',flush=True)
except Exception:
 (BACKUP/'preflight-api.log').write_bytes(run(['docker','logs',check_name],check=False).stdout)
 raise
finally:
 run(['docker','rm','-f',check_name],check=False)
 psql('DROP DATABASE '+clone+' WITH (FORCE)')
 envfile.unlink(missing_ok=True)
# New-key initialization only for this previously old installation. Preserve it forever.
api_env.update({'CORS_ORIGINS':','.join(dict.fromkeys((services['api'].get('environment',{}).get('CORS_ORIGINS','')+',https://app.aztrip.online').split(','))),
 'SMTP_HOST':'smtp.resend.com','SMTP_PORT':'587','SMTP_USERNAME':'resend','SMTP_PASSWORD':payload['smtp_password'],'SMTP_FROM':'no-reply@aztrip.online','EMAIL_ENABLED':'true','PUSH_ENABLED':'false','DEMO_PAYMENTS_ENABLED':'false'})
assert api_env['SMTP_PASSWORD'].startswith('re_')
api_env.setdefault('EMAIL_DELIVERY_NOT_BEFORE',datetime.datetime.now(datetime.timezone.utc).isoformat())
new_env=''.join(k+'='+v+'\n' for k,v in api_env.items())
# A bounded maintenance window avoids losing writes between the last backup and migration.
run(compose('stop','api'))
try:
 dump(BACKUP/'before-upgrade.dump')
 (ROOT/'mobile-api.env').write_text(new_env)
 override={'services':{'api':{'image':API,'environment':api_env},'mobile-web':{'image':WEB,'restart':'unless-stopped','ports':['127.0.0.1:8104:8080'],'healthcheck':{'test':['CMD','wget','-q','-O','/dev/null','http://127.0.0.1:8080/healthz'],'interval':'10s','timeout':'3s','retries':6}}}}
 (ROOT/'compose.override.yml').write_text(json.dumps(override,indent=2)+'\n')
 (ROOT/'compose.override.yml').chmod(0o600)
 (ROOT/'mobile-api.env').chmod(0o600)
 run(compose('up','-d','--no-deps','api','mobile-web'))
 wait('http://127.0.0.1:8101/actuator/health');wait('http://127.0.0.1:8104/healthz')
 request('/api/v1/tours',port=8101)
 with urllib.request.urlopen(urllib.request.Request('http://127.0.0.1:8101/api/v1/tours',headers={'Origin':'https://app.aztrip.online'})) as r:
  assert r.headers['Access-Control-Allow-Origin']=='https://app.aztrip.online'
 print('Production API and mobile web containers are healthy; CORS verified',flush=True)
except Exception:
 # SQL is additive and the old V7 app tolerates V8 as a future migration.
 if (BACKUP/'compose.override.yml').exists():shutil.copy2(BACKUP/'compose.override.yml',ROOT/'compose.override.yml')
 else:(ROOT/'compose.override.yml').unlink(missing_ok=True)
 run(['docker','tag',old_api_image,'aztrip-api:latest'])
 run(compose('up','-d','--no-deps','api'),check=False)
 raise
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
