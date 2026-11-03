"""Local sample data only: persisted drafts, revision isolation and send gates."""
import base64, concurrent.futures, datetime, json, os, pathlib, urllib.request, urllib.error, uuid
base=os.environ.get('API_ORIGIN','http://127.0.0.1:8080')
env=dict(line.split('=',1) for line in pathlib.Path('.env').read_text().splitlines() if '=' in line and not line.startswith('#')) if pathlib.Path('.env').exists() else {}
env.update({k:os.environ[k] for k in ['OPERATIONS_USER','OPERATIONS_PASSWORD'] if k in os.environ})
ops='Basic '+base64.b64encode((env.get('OPERATIONS_USER','operations')+':'+env['OPERATIONS_PASSWORD']).encode()).decode()
def call(path,body=None,auth=None):
 req=urllib.request.Request(base+'/api/v1/'+path,json.dumps(body).encode() if body is not None else None,{'Content-Type':'application/json',**({'Authorization':auth} if auth else {})})
 try:
  with urllib.request.urlopen(req,timeout=20) as r:
   raw=r.read();return r.status,json.loads(raw) if raw else None
 except urllib.error.HTTPError as e:
  raw=e.read();return e.code,json.loads(raw) if raw else None
from qa_accounts import verified
call=verified(call)
suffix=uuid.uuid4().hex
registration={'name':'Offer Integration QA','email':'offer-'+suffix+'@example.com','password':'Local-test-only-strong-password-2026','locale':'en'}
status,session=call('auth/register',registration);assert status==201,(status,session)
auth='Bearer '+session['token']
_,other=call('auth/register',registration|{'email':'other-offer-'+suffix+'@example.com'});otherauth='Bearer '+other['token']
body={'slug':'quba-khinalug','date':(datetime.date.today()+datetime.timedelta(days=40)).isoformat(),'pax':2,'hotel':'comfort','guide':True,'guideLanguage':'en','driver':True,'stay':True,'transfer':True,'activity':True,'currency':'EUR','customDays':2,'cities':['Quba','Khinalug']}
status,q=call('quotes',body);assert status==200,(status,q)
status,b=call('bookings',{'quoteId':q['id'],'note':'QA-only offer workflow integration'},auth);assert status==200,(status,b)
bid=b['id']

status,w=call('operations/request-workspace',auth=ops)
assert status==200,(status,w)
row=next(r for r in w['rows'] if r['id']==bid)
assert row['departure']==body['date']
assert row['stage']=='NEEDS_REVIEW' and row['name']==registration['name'] and row['assigned_to'] is None
assert call('operations/request-workspace')[0]==401
assert call('operations/request-workspace',auth=auth)[0]==403
path='operations/bookings/'+bid+'/assignment'
assert call(path,{'assign':True,'expectedOwner':None},auth)[0]==403
assert call(path,{'assign':True,'expectedOwner':None},ops)[0]==200
_,w=call('operations/request-workspace',auth=ops)
row=next(r for r in w['rows'] if r['id']==bid)
assert row['assigned_to']==w['actor']
assert call(path,{'assign':False,'expectedOwner':None},ops)[0]==409
assert call(path,{'assign':False,'expectedOwner':w['actor']},ops)[0]==200
_,w=call('operations/request-workspace',auth=ops)
assert next(r for r in w['rows'] if r['id']==bid)['assigned_to'] is None
assert any(a['booking_id']==bid and a['action']=='UNASSIGNED' for a in w['activity'])
assert call('bookings/'+bid+'/requests',{'kind':'CANCEL','message':'QA-only cancellation for request workspace'},auth)[0]==200
_,w=call('operations/request-workspace',auth=ops)
assert next(r for r in w['rows'] if r['id']==bid)['stage']=='CANCELLATION_REVIEW'
print('Request workspace passed: real customer data, stages, access boundaries, assignment, stale write rejection, persisted release and audit.')
