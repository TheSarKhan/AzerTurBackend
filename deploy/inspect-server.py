"""Read only topology/schema inspection; never emits credentials or customer rows."""
import json,pathlib,shutil,subprocess
run=lambda args,**kw: subprocess.run(args,capture_output=True,text=True,check=True,**kw).stdout
config=json.loads(run(['docker','compose','config','--format','json'],cwd='/opt/aztrip'))
for name,service in config['services'].items():
 print(json.dumps({'service':name,'image':service.get('image'),'ports':service.get('ports'),'environmentKeys':sorted(service.get('environment',{}))}))
def sql(query):
 return run(['docker','compose','exec','-T','postgres','sh','-c','psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -At -c "$1"','sh',query],cwd='/opt/aztrip')
print('Migration history:\n'+sql('SELECT version,script,checksum,success FROM flyway_schema_history ORDER BY installed_rank'))
print('Tables:\n'+sql("SELECT tablename FROM pg_tables WHERE schemaname='public' ORDER BY tablename"))
print('Postgres database: '+config['services']['postgres']['environment']['POSTGRES_DB'])
print('Disk: '+run(['df','-h','/opt/aztrip']))
print('Proxy binaries: '+', '.join(n for n in ['nginx','certbot'] if shutil.which(n)))
print('Privilege commands:')
r=subprocess.run(['sudo','-n','-l'],capture_output=True,text=True)
print(r.stdout or r.stderr)
print('Proxy headers:')
proxy=run(['cat','/etc/nginx/sites-enabled/aztrip.online.conf'])
for line in proxy.splitlines():
 if line.strip().startswith(('server_name','listen','ssl_certificate','location','proxy_pass','client_max_body_size','proxy_set_header')): print(line.strip())
for p in sorted(pathlib.Path('/opt/aztrip/backups').glob('mobile-rollout-*/last-command-error.log'))[-1:]:
 print('Latest infrastructure error:')
 print(p.read_text()[-3000:])
print('Nginx files:')
for p in pathlib.Path('/etc/nginx/sites-enabled').glob('*aztrip*'):print(str(p)+' -> '+str(p.resolve()))
