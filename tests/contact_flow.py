"""Contact form: public submit, validation, rate limit and staff handling. No email is sent."""
import base64, json, os, pathlib, urllib.request, urllib.error, uuid
base=os.environ.get('API_ORIGIN','http://127.0.0.1:8080')
env=dict(l.split('=',1) for l in pathlib.Path('.env').read_text().splitlines() if '=' in l and not l.startswith('#')) if pathlib.Path('.env').exists() else {}
env.update({k:os.environ[k] for k in ['OPERATIONS_USER','OPERATIONS_PASSWORD'] if k in os.environ})
ops='Basic '+base64.b64encode((env.get('OPERATIONS_USER','operations')+':'+env['OPERATIONS_PASSWORD']).encode()).decode()
def call(path,body=None,auth=None,headers=None):
 h={'Content-Type':'application/json',**({'Authorization':auth} if auth else {}),**(headers or {})}
 req=urllib.request.Request(base+'/api/v1/'+path,json.dumps(body).encode() if body is not None else None,h)
 try:
  with urllib.request.urlopen(req,timeout=20) as r:
   raw=r.read();return r.status,json.loads(raw) if raw else None
 except urllib.error.HTTPError as e:
  raw=e.read();return e.code,json.loads(raw) if raw else None
tag=uuid.uuid4().hex[:8]
ip={'CF-Connecting-IP':'203.0.113.'+str(int(tag[:2],16)%200+1)}   # a fresh visitor address per run
ok={'name':'QA Visitor','email':'qa-'+tag+'@example.com','topic':'general','message':'Hello team, QA message '+tag,'locale':'en'}
assert call('contact',ok,headers=ip)[0]==204
for bad in [ok|{'name':'A'},ok|{'email':'not-an-email'},ok|{'email':'a b@example.com'},ok|{'topic':'spam'},ok|{'message':'hi'},ok|{'message':'x'*2001},ok|{'locale':'de'}]:
 assert call('contact',bad,headers=ip)[0]==400,bad
assert call('operations/contact-messages')[0]==401
assert call('operations/contact-messages',auth='Bearer nope')[0] in (401,403)
status,rows=call('operations/contact-messages',auth=ops);assert status==200
mine=[r for r in rows if r['email']==ok['email']];assert len(mine)==1 and mine[0]['state']=='NEW' and mine[0]['email_state'] in ('SKIPPED','SENT','FAILED'),mine
assert call('operations/contact-messages/'+mine[0]['id']+'/handled',{},ops)[0]==200
assert call('operations/contact-messages/'+mine[0]['id']+'/handled',{},ops)[1]['error']=='ALREADY_HANDLED'
assert call('operations/contact-messages/'+str(uuid.uuid4())+'/handled',{},ops)[0]==404
assert [r for r in call('operations/contact-messages',auth=ops)[1] if r['id']==mine[0]['id']][0]['state']=='HANDLED'
# a visitor cannot flood the team: the sixth message in a minute from one address is refused
flood_ip={'CF-Connecting-IP':'198.51.100.'+str(int(tag[2:4],16)%200+1)}
codes=[call('contact',ok|{'message':'Flood test %d %s'%(i,tag)},headers=flood_ip)[0] for i in range(7)]
assert codes.count(429)>=1 and codes[:5]==[204]*5,codes
print('Contact flow passed: public submit, validation, staff-only list, handling and per-visitor limit.')
