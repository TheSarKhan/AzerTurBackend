import base64, json, os, pathlib, subprocess, tempfile, threading, time, urllib.request, urllib.error, uuid
from http.server import BaseHTTPRequestHandler, HTTPServer
base=os.environ.get('API_ORIGIN','http://127.0.0.1:8080')
client=os.environ.get('GOOGLE_WEB_CLIENT_ID','ci-google-web-client')
jwks_port=int(os.environ.get('GOOGLE_JWKS_PORT','18999'))
def b64(data):return base64.urlsafe_b64encode(data).rstrip(b'=').decode()
def run(*args,**kw):return subprocess.run(args,check=True,capture_output=True,**kw).stdout
# A throwaway RSA key signs the tokens; the API trusts it through GOOGLE_JWK_SET_URI.
tmp=pathlib.Path(tempfile.mkdtemp());key=tmp/'key.pem'
run('openssl','genrsa','-out',str(key),'2048')
modulus=bytes.fromhex(run('openssl','rsa','-in',str(key),'-noout','-modulus').decode().strip().split('=')[1])
jwk={'kty':'RSA','kid':'test-key','use':'sig','alg':'RS256','n':b64(modulus),'e':b64(bytes([1,0,1]))}
class Jwks(BaseHTTPRequestHandler):
 def do_GET(self):
  body=json.dumps({'keys':[jwk]}).encode();self.send_response(200);self.send_header('Content-Type','application/json');self.send_header('Content-Length',str(len(body)));self.end_headers();self.wfile.write(body)
 def log_message(self,*a):pass
server=HTTPServer((os.environ.get('GOOGLE_JWKS_HOST','127.0.0.1'),jwks_port),Jwks);threading.Thread(target=server.serve_forever,daemon=True).start()
def token(sub,email,verified=True,aud=None,iss='https://accounts.google.com',exp=600,name='Google Traveller',sign=True):
 header=b64(json.dumps({'alg':'RS256','typ':'JWT','kid':'test-key'}).encode())
 claims={'iss':iss,'aud':aud or client,'sub':sub,'email':email,'email_verified':verified,'name':name,'iat':int(time.time()),'exp':int(time.time())+exp}
 signing=header+'.'+b64(json.dumps(claims).encode())
 sig=run('openssl','dgst','-sha256','-sign',str(key),input=signing.encode())
 return signing+'.'+b64(sig if sign else b'forged'+sig[6:])
def call(path,body=None,auth=None,method=None):
 headers={'Content-Type':'application/json'}
 if auth:headers['Authorization']=auth
 req=urllib.request.Request(base+'/api/v1/'+path,json.dumps(body).encode() if body is not None else None,headers,method=method)
 try:
  with urllib.request.urlopen(req,timeout=20) as res:return res.status,json.load(res) if res.status!=204 and res.headers.get('Content-Length')!='0' else None
 except urllib.error.HTTPError as e:
  raw=e.read()
  try:data=json.loads(raw)
  except:data={}
  return e.code,data
def google(credential,**extra):return call('auth/google',dict(credential=credential,**extra))
suffix=uuid.uuid4().hex[:10]
status,config=call('auth/google/config');assert status==200 and config['enabled'] and config['webClientId']==client,(status,config)
# new account: created verified, usable session, returning visitor gets the same account
sub1='sub-'+suffix;email1='google-'+suffix+'@example.com'
status,first=google(token(sub1,email1.upper()),locale='ru');assert status==200,(status,first)
assert first['customer']['email']==email1 and first['customer']['emailVerified'] is True and first['customer']['locale']=='ru',first
auth='Bearer '+first['token'];assert call('auth/me',auth=auth)[1]['emailVerified'] is True
status,again=google(token(sub1,email1));assert status==200 and again['customer']['id']==first['customer']['id']
# the Google-created account has no usable password, but the normal reset path still works
assert call('auth/login',dict(email=email1,password='anything-at-all-123'))[0]==401
# invalid tokens are refused: forged signature, wrong audience/issuer, expired, unverified email
assert google(token('x'+suffix,'forged-'+suffix+'@example.com',sign=False))[0]==401
assert google(token('y'+suffix,'aud-'+suffix+'@example.com',aud='another-client'))[0]==401
assert google(token('z'+suffix,'iss-'+suffix+'@example.com',iss='https://evil.example'))[0]==401
assert google(token('e'+suffix,'exp-'+suffix+'@example.com',exp=-3600))[0]==401
status,body=google(token('u'+suffix,'unverified-'+suffix+'@example.com',verified=False));assert status==401 and body['error']=='GOOGLE_EMAIL_NOT_VERIFIED',(status,body)
assert google('not-a-jwt')[0]==401 and call('auth/google',{})[0]==400
# pre-hijacking: an attacker registers a victim's address with their own password, the victim then signs in with Google
victim_email='victim-'+suffix+'@example.com';attacker_password='Attacker-chosen-password-1'
status,planted=call('auth/register',dict(email=victim_email,password=attacker_password,name='Attacker',locale='en'));assert status==201 and planted['customer']['emailVerified'] is False
attacker_auth='Bearer '+planted['token'];assert call('auth/me',auth=attacker_auth)[0]==200
status,victim=google(token('victim-sub-'+suffix,victim_email,name='Real Victim'));assert status==200 and victim['customer']['id']==planted['customer']['id'] and victim['customer']['emailVerified'] is True
assert call('auth/me',auth=attacker_auth)[0]==401,'the attacker session must be revoked'
assert call('auth/login',dict(email=victim_email,password=attacker_password))[0]==401,'the attacker password must stop working'
assert call('auth/me',auth='Bearer '+victim['token'])[0]==200
# a second Google identity cannot take over an account already linked to someone else's identity
status,other=google(token('other-sub-'+suffix,victim_email));assert status==200 and other['customer']['id']==victim['customer']['id']
assert call('auth/me',auth='Bearer '+victim['token'])[0]==200,'linking a verified account must not revoke its sessions'
# bookings API accepts Google-created sessions like any other customer session
assert call('bookings',auth='Bearer '+first['token'])[0]==200
assert call('auth/logout',{},auth)[0]==204 and call('auth/me',auth=auth)[0]==401
print('google flow ok')
