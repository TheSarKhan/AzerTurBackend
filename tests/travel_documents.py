"""Original-byte, ownership, preview, revision and revocation checks. Synthetic loopback fixtures only."""
import base64,datetime,hashlib,json,os,pathlib,struct,urllib.request,urllib.error,uuid,zlib
from qa_accounts import verified,sql
base=os.environ.get('API_ORIGIN','http://127.0.0.1:8080')+'/api/v1/'
env=dict(line.split('=',1) for line in pathlib.Path('.env').read_text().splitlines() if '=' in line and not line.startswith('#')) if pathlib.Path('.env').exists() else {}
env.update({k:os.environ[k] for k in ['OPERATIONS_USER','OPERATIONS_PASSWORD'] if k in os.environ})
ops='Basic '+base64.b64encode((env.get('OPERATIONS_USER','operations')+':'+env['OPERATIONS_PASSWORD']).encode()).decode()
def http(path,data=None,auth=None,content='application/json'):
 headers={'Content-Type':content}
 if auth:headers['Authorization']=auth
 try:
  with urllib.request.urlopen(urllib.request.Request(base+path,data,headers),timeout=25) as r:
   raw=r.read();return r.status,json.loads(raw) if r.headers.get('Content-Type','').startswith('application/json') and raw else raw
 except urllib.error.HTTPError as e:
  raw=e.read()
  try:return e.code,json.loads(raw)
  except:return e.code,raw
@verified
def call(path,body=None,auth=None):return http(path,None if body is None else json.dumps(body).encode(),auth)
def png():
 def chunk(t,b):return struct.pack('>I',len(b))+t+b+struct.pack('>I',zlib.crc32(t+b)&0xffffffff)
 return b'\x89PNG\r\n\x1a\n'+chunk(b'IHDR',struct.pack('>IIBBBBB',2,2,8,2,0,0,0))+chunk(b'IDAT',zlib.compress(b'\x00\xff\xff\xff\xff\xff\xff'*2))+chunk(b'IEND',b'')
suffix=uuid.uuid4().hex
registration=dict(name='Mobile Document QA',email='documents-'+suffix+'@example.com',password='Synthetic-test-password-2026',locale='en')
status,session=call('auth/register',registration);assert status==201,(status,session)
auth='Bearer '+session['token']
status,other=call('auth/register',dict(registration,email='other-document-'+suffix+'@example.com'));assert status==201
otherauth='Bearer '+other['token']
status,q=call('quotes',dict(slug='quba-khinalug',date=(datetime.date.today()+datetime.timedelta(days=30)).isoformat(),pax=2,hotel='comfort',driver=True,stay=True,guide=True,guideLanguage='en',transfer=False,activity=False,currency='EUR'));assert status==200,(status,q)
status,b=call('bookings',dict(quoteId=q['id'],note='Synthetic document integration only'),auth);assert status==200,(status,b)
bid=b['id'];path='operations/bookings/'+bid+'/documents';cp='bookings/'+bid+'/documents'
def upload(data,**patch):
 boundary='qa'+uuid.uuid4().hex
 fields=dict(serviceKey='transport',kind='OTHER',title='QA original file (not valid for travel)',startsAt=(datetime.date.today()+datetime.timedelta(days=40)).isoformat()+'T10:00:00+04:00',endsAt=(datetime.date.today()+datetime.timedelta(days=42)).isoformat()+'T18:00:00+04:00',timezone='Asia/Baku',instructions='Synthetic integration file only.')|patch
 parts=[]
 for k,v in fields.items():parts.append(('--'+boundary+'\r\nContent-Disposition: form-data; name="'+k+'"\r\n\r\n'+v+'\r\n').encode())
 parts.append(('--'+boundary+'\r\nContent-Disposition: form-data; name="file"; filename="qa-original.png"\r\nContent-Type: image/png\r\n\r\n').encode()+data+('\r\n--'+boundary+'--\r\n').encode())
 return http(path,b''.join(parts),ops,'multipart/form-data; boundary='+boundary)
assert call(cp)[0]==401
assert call(path,auth=auth)[0]==403
assert upload(b'<svg onload="alert(1)"/>')[0]==400
assert upload(b'\0'*10_000_001)[0]==400
assert upload(png(),timezone='Not/AZone')[0]==400
assert upload(png(),endsAt='2000-01-01T00:00:00+04:00')[0]==400
status,d=upload(png());assert status==200,(status,d)
assert d['mime']=='image/png' and d['sha256']==hashlib.sha256(png()).hexdigest()
dp=path+'/'+d['id'];body=dict(sha256=d['sha256'],customerChecked=True)
assert call(cp,auth=auth)==(200,[])
assert call(cp+'/'+d['id'],auth=auth)[0]==404
assert http(cp+'/'+d['id']+'/file',auth=auth)[0]==404
assert call(dp+'/publish',body,ops)[0]==409
assert http(dp+'/file',auth=ops)[1]==png()
assert call(dp+'/preview',dict(body,sha256='0'*64),ops)[0]==409
assert call(dp+'/preview',body,ops)[0]==200
assert call(dp+'/publish',dict(body,customerChecked=False),ops)[0]==409
status,current=call(dp+'/publish',body,ops);assert status==200 and current['status']=='UPCOMING',(status,current)
assert len(call(cp,auth=auth)[1])==1
assert call(cp,auth=otherauth)[0]==404
assert call(cp+'/'+d['id'],auth=otherauth)[0]==404
assert http(cp+'/'+d['id']+'/file',auth=otherauth)[0]==404
assert http(cp+'/'+d['id']+'/file',auth=auth)[1]==png()
_,v2=upload(png(),replaces=d["id"]);_,v3=upload(png(),replaces=d["id"]);assert v2['version']==2 and v3['version']==3
assert call(path+'/'+v2['id']+'/preview',dict(sha256=v2['sha256'],customerChecked=True),ops)[0]==200
assert call(path+'/'+v2['id']+'/publish',dict(sha256=v2['sha256'],customerChecked=True),ops)[0]==409
assert call(path+'/'+v3['id']+'/preview',dict(sha256=v3['sha256'],customerChecked=True),ops)[0]==200
assert call(path+'/'+v3['id']+'/publish',dict(sha256=v3['sha256'],customerChecked=True),ops)[0]==200
_,separate=upload(png());assert separate['version']==1 and separate['document_group']!=v3['document_group']
separatePath=path+'/'+separate['id'];separateReview=dict(sha256=separate['sha256'],customerChecked=True)
assert call(separatePath+'/preview',separateReview,ops)[0]==200
assert call(separatePath+'/publish',separateReview,ops)[0]==200
assert len([x for x in call(cp,auth=auth)[1] if x['status']=='UPCOMING'])==2
old=call(cp+'/'+d['id'],auth=auth)[1];assert old['status']=='SUPERSEDED' and old['replacementId']==v3['id']
assert http(cp+'/'+d['id']+'/file',auth=auth)[1]==png()
assert call(path+'/'+v3['id']+'/revoke',{},ops)[1]['status']=='REVOKED'
assert call(cp+'/'+d['id'],auth=auth)[1]['replacementId'] is None
_,draft=upload(png());draftPath=path+'/'+draft['id'];review=dict(sha256=draft['sha256'],customerChecked=True)
assert call(draftPath+'/preview',review,ops)[0]==200
assert call(draftPath+'/publish',review,ops)[0]==200
sql("UPDATE bookings SET service_revision=service_revision+1 WHERE id='%s';"%bid)
assert call(cp+'/'+draft['id'],auth=auth)[1]['status']=='REQUIRES_REVIEW'
_,independent=upload(png());assert independent['version']==1 and independent['document_group']!=draft['document_group']
_,nextDraft=upload(png());review=dict(sha256=nextDraft['sha256'],customerChecked=True);assert call(path+'/'+nextDraft['id']+'/preview',review,ops)[0]==200
sql("UPDATE bookings SET service_revision=service_revision+1 WHERE id='%s';"%bid)
assert call(path+'/'+nextDraft['id']+'/publish',review,ops)[0]==409
notices=call('bookings/notifications',auth=auth)[1]['items'];assert any(x['event']=='TRAVEL_DOCUMENT_PUBLISHED' for x in notices)
if os.environ.get('QA_WRITE_LOGIN'):pathlib.Path(os.environ['QA_WRITE_LOGIN']).write_text(json.dumps(dict(registration,bookingId=bid,documentId=draft['id'])))
print('Travel documents passed: original bytes, size/type validation, owner isolation, draft secrecy, preview/customer checks, stale publish, archive, revoke, service revision and notification.')
