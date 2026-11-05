"""Finish host-managed cert renewal without revealing server environment values."""
import json,pathlib,subprocess,urllib.request
ROOT=pathlib.Path('/opt/aztrip')
def run(args):return subprocess.run(args,input=None,capture_output=True,text=True,check=True)
release=json.loads((ROOT/'mobile-release.json').read_text())
print('Release commits: '+release['backend']+' '+release['mobile'])
# The existing host certbot timer renews webroot certificates. Reload Nginx
# after renewal, so the running TLS listener picks up the new certificate.
code="""import pathlib,os
p=pathlib.Path('/certs/renewal-hooks/deploy/aztrip-reload-nginx')
p.parent.mkdir(parents=True,exist_ok=True)
p.write_text('#!/bin/sh\\n/usr/sbin/nginx -t && /usr/sbin/nginx -s reload\\n')
p.chmod(0o755)
"""
run(['docker','run','--rm','-v','/etc/letsencrypt:/certs','python:3.12-alpine','python','-c',code])
r=run(['systemctl','is-active','certbot.timer']);print('Certificate renewal timer: '+r.stdout.strip())
for url in ['https://app.aztrip.online/healthz','https://app.aztrip.online/trip/documents','https://api.aztrip.online/actuator/health','https://aztrip.online/','https://admin.aztrip.online/']:
 with urllib.request.urlopen(url,timeout=20) as response:assert response.status==200;print(str(response.status)+' '+url)
print('TLS renewal hook and public release checks passed')
