"""Actual token lifecycle tests using only opt-in encrypted synthetic fixture mail."""
import concurrent.futures,json,os,pathlib,subprocess,time,urllib.request,urllib.error,urllib.parse,uuid
from qa_accounts import sql
base=os.environ.get('API_ORIGIN','http://127.0.0.1:8080')
env=dict(line.split('=',1) for line in pathlib.Path('.env').read_text().splitlines() if '=' in line and not line.startswith('#')) if pathlib.Path('.env').exists() else {}
env.update(os.environ)
def call(path,body=None,auth=None):
 req=urllib.request.Request(base+'/api/v1/'+path,json.dumps(body).encode() if body is not None else None,{'Content-Type':'application/json',**({'Authorization':auth} if auth else {})})
 try:
  with urllib.request.urlopen(req,timeout=20) as r:
   raw=r.read();return r.status,json.loads(raw) if raw else None
 except urllib.error.HTTPError as e:return e.code,json.loads(e.read())
suffix=uuid.uuid4().hex;email='security-'+suffix+'@example.com';password='Synthetic-old-password-2026'
status,session=call('auth/register',dict(email=email,password=password,name='Security QA',locale='az'));assert status==201
cid=str(uuid.UUID(session['customer']['id']));auth='Bearer '+session['token']
assert not session['customer']['emailVerified']
assert call('bookings',{},auth)[1]['error']=='EMAIL_VERIFICATION_REQUIRED'
def link(purpose):
 for _ in range(35):
  raw=sql("SELECT json_build_object('id',id,'nonce',encode(nonce,'hex'),'cipher',encode(cipher,'hex')) FROM account_mail_jobs WHERE customer_id='%s' AND purpose='%s' AND cipher IS NOT NULL ORDER BY created_at DESC LIMIT 1;"%(cid,purpose))
  if raw:
   j=json.loads(raw);payload='\n'.join([env['ACCOUNT_LINK_KEY'],j['id'],j['nonce'],j['cipher']])+'\n'
   decoded=subprocess.run(['java','tests/AccountLinkDecrypt.java'],input=payload.encode(),capture_output=True,check=True).stdout
   url=json.loads(decoded)['url'];assert urllib.parse.urlparse(url).hostname in ('localhost','127.0.0.1')
   return urllib.parse.parse_qs(urllib.parse.urlparse(url).fragment)['token'][0]
  time.sleep(1)
 raise AssertionError('Synthetic queued link not found')
verify=link('VERIFY');assert len(verify)==43
sql("UPDATE account_links SET expires_at=now()-interval '1 second' WHERE customer_id='%s' AND purpose='VERIFY';"%cid)
assert call('auth/verify-email',{'token':verify})[0]==409
assert call('auth/email-verification/request',{},auth)[0]==202
verify=link('VERIFY')
assert call('auth/reset-password',dict(token=verify,password=password,confirmation=password))[0]==409
with concurrent.futures.ThreadPoolExecutor(max_workers=2) as p:results=list(p.map(lambda _:call('auth/verify-email',{'token':verify})[0],range(2)))
assert sorted(results)==[204,409],results
assert call('auth/me',auth=auth)[1]['emailVerified']
assert call('auth/verify-email',{'token':verify})[0]==409
_,second=call('auth/login',dict(email=email,password=password));other='Bearer '+second['token']
sessions=call('auth/sessions',auth=auth)[1];assert len(sessions)==2 and sum(s['current'] for s in sessions)==1
assert 'token_hash' not in json.dumps(sessions)
foreign=next(s for s in sessions if not s['current'])
assert call('auth/sessions/'+foreign['id']+'/revoke',{},auth)[0]==204
assert call('auth/me',auth=other)[0]==401
_,second=call('auth/login',dict(email=email,password=password));assert call('auth/sessions/revoke-others',{},auth)[0]==204
assert call('auth/me',auth='Bearer '+second['token'])[0]==401
assert call('auth/forgot-password',{'email':email})==(202,{'status':'REQUEST_ACCEPTED'})
assert call('auth/forgot-password',{'email':'unknown-'+suffix+'@example.com'})==(202,{'status':'REQUEST_ACCEPTED'})
reset=link('RESET');new='Synthetic-new-password-2026'
assert call('auth/verify-email',{'token':reset})[0]==409
assert call('auth/reset-password',dict(token=reset,password=new,confirmation='Other-long-password-2026'))[0]==400
assert call('auth/reset-password',dict(token=reset,password=new,confirmation=new))[0]==204
assert call('auth/me',auth=auth)[0]==401
assert call('auth/reset-password',dict(token=reset,password=new,confirmation=new))[0]==409
assert call('auth/login',dict(email=email,password=password))[0]==401
status,fresh=call('auth/login',dict(email=email,password=new));assert status==200 and fresh['customer']['emailVerified']
assert call('auth/logout-all',{},'Bearer '+fresh['token'])[0]==204
assert call('auth/me',auth='Bearer '+fresh['token'])[0]==401
assert sql("SELECT count(*) FROM account_links WHERE customer_id='%s' AND consumed_at IS NULL;"%cid)=='0'
print('Account security passed: verification gate, encrypted one-use links, wrong purpose, concurrency, reset, session revocation and generic recovery.')
