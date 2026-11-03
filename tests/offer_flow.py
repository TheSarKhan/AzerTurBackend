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
bid=b['id'];path='operations/bookings/'+bid+'/offer'
assert call('bookings/offers')[0]==401
assert call(path,auth=auth)[0]==403
assert call(path,auth=ops)==(200,None)
assert call('operations/bookings/'+bid+'/customer',auth=ops)[1]['email']==registration['email']
lines=[{'key':key,'name':key,'quantity':quantity,'nights':nights,'unitPrice':price,'unit':unit,'supplier':'','location':'','availability':'NOT_CHECKED','policy':'','internalNote':'PRIVATE-SUPPLIER-CANARY'} for key,quantity,nights,price,unit in [('transport',2,1,90,'day'),('hotel',1,1,110,'room'),('guide',2,1,65,'day'),('transfer',1,1,35,'transfer'),('activity',2,1,20,'person')]]
d={'version':0,'sourceQuoteId':q['id'],'title':'QA custom offer','currency':'EUR','itinerary':[{'city':'Quba','description':'QA day one'},{'city':'Khinalug','description':'QA day two'}],'returnRoute':'Return via Quba','services':lines,'message':'QA offer message, not a real booking.','internalNote':'PRIVATE-OFFICE-CANARY'}
assert call(path,d|{'services':[lines[0]|{'quantity':0}]},ops)[0]==400
assert call(path,d|{'currency':'USD'},ops)[0]==400
status,d=call(path,d,ops);assert status==200 and d['version']==1,(status,d)
assert call('bookings/offers',auth=auth)[1]==[]
assert call(path+'/send',{'version':1},ops)[1]['error']=='PREVIEW_REQUIRED'
assert call(path+'/preview',{'version':1},ops)[0]==200
assert call(path+'/send',{'version':1},ops)[1]['error']=='SERVICE_CHECKS_REQUIRED'
for line in d['services']:line.update(supplier='QA manual supplier',availability='CONFIRMED',policy='QA-only policy: office handles cancellation using supplier terms at the request time.')
status,d=call(path,d,ops);assert status==200 and d['version']==2
assert call(path,d|{'version':1},ops)[1]['error']=='STALE_OFFER'
assert call(path+'/send',{'version':2},ops)[1]['error']=='PREVIEW_REQUIRED'
status,preview=call(path+'/preview',{'version':2},ops);assert status==200 and preview['total']==495
assert 'PRIVATE-' not in json.dumps(preview) and '"supplier":' not in json.dumps(preview)
status,offer=call(path+'/send',{'version':2},ops);assert status==200 and offer['total']==495 and offer['revision']==1,(status,offer)
assert call(path+'/send',{'version':2},ops)[1]['id']==offer['id']
assert call('bookings/offers',auth=otherauth)[1]==[]
public=call('bookings/offers',auth=auth)[1];assert len(public)==1 and public[0]['id']==offer['id'] and 'PRIVATE-' not in json.dumps(public)
assert next(x for x in call('bookings',auth=auth)[1] if x['id']==bid)['status']=='PENDING_REVIEW'
assert call('operations/bookings/'+bid+'/review',{'approve':True,'note':'Must not bypass contract gate'},ops)[1]['error']=='OFFER_WORKFLOW_REQUIRED'
d['services'][0]['unitPrice']=100
status,d=call(path,d,ops);assert status==200 and d['version']==3
assert call('bookings/offers',auth=auth)[1][0]['total']==495
with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:results=list(pool.map(lambda _:call(path,d,ops),range(2)))
assert sorted(status for status,_ in results)==[200,409],results
d=next(value for status,value in results if status==200);assert d['version']==4
assert call(path+'/preview',{'version':4},ops)[1]['total']==515
with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:results=list(pool.map(lambda _:call(path+'/send',{'version':4},ops),range(2)))
assert all(status==200 for status,_ in results) and len({value['id'] for _,value in results})==1
assert all(value['revision']==2 for _,value in results)
assert call('bookings/offers',auth=auth)[1][0]['total']==515
status,d=call(path,d,ops);assert status==200
assert call(path+'/preview',{'version':d['version']},ops)[0]==200
assert call('bookings/'+bid+'/requests',{'kind':'CHANGE','message':'QA-only pending change blocks stale offer send.'},auth)[0]==200
assert call(path+'/send',{'version':d['version']},ops)[1]['error']=='REQUEST_PENDING'
assert call('operations/bookings/'+bid+'/review',{'approve':False,'note':'Must resolve pending request first'},ops)[1]['error']=='REQUEST_PENDING'
assert call(path+'/preview',{'version':d['version']-1},ops)[1]['error']=='STALE_OFFER'
assert call(path+'/send',{'version':d['version']},auth)[0]==403
# Source changes require rebuilding the draft; unpublished internal state stays private.
assert call(path,d|{'sourceQuoteId':str(uuid.uuid4())},ops)[1]['error']=='SOURCE_CHANGED'
print('Offer flow passed: validation, private drafts, ownership, immutable public revisions, preview and payment gates, stale/conflicting edits, concurrent duplicate send, pending requests and internal-note isolation.')
# An accepted amendment invalidates old-source offers; retries cannot revive them.
new_body=body|{'date':(datetime.date.today()+datetime.timedelta(days=41)).isoformat()}
_,replacement=call('quotes',new_body)
request=next(r for r in call('bookings/requests',auth=auth)[1] if r['bookingId']==bid and r['status']=='PENDING')
assert call('operations/requests/'+request['id']+'/resolve',{'approve':True,'note':'QA source amendment','replacementQuoteId':replacement['id']},ops)[0]==200
assert call(path+'/send',{'version':4},ops)[1]['error']=='SOURCE_CHANGED'
assert call(path,d,ops)[1]['error']=='SOURCE_CHANGED'
assert call('bookings/offers',auth=auth)[1]==[]
# Fractional unit rates preserve the original converted integer service totals.
_,fx=call('quotes',body|{'currency':'AZN'})
_,fx_booking=call('bookings',{'quoteId':fx['id'],'note':'QA currency and rejection check'},auth)
fx_path='operations/bookings/'+fx_booking['id']+'/offer'
fx_lines=json.loads(json.dumps(lines))
for line in fx_lines:
 amount=next(x['amount'] for x in fx['lines'] if x['label']==line['key'])
 line['unitPrice']=round(amount/(line['quantity']*line['nights']),6)
status,fx_draft=call(fx_path,d|{'version':0,'sourceQuoteId':fx['id'],'currency':'AZN','services':fx_lines},ops);assert status==200,(status,fx_draft)
assert call(fx_path+'/preview',{'version':fx_draft['version']},ops)[1]['total']==fx['total']
fx_draft['services'][0].update(quantity=100,unitPrice=0.145,nights=1)
_,fx_draft=call(fx_path,fx_draft,ops)
assert call(fx_path+'/preview',{'version':fx_draft['version']},ops)[1]['total']==fx['total']-next(x['amount'] for x in fx['lines'] if x['label']=='transport')+15
assert call('operations/bookings/'+fx_booking['id']+'/review',{'approve':False,'note':'QA rejection after draft; no payment.'},ops)[1]['status']=='REJECTED'
print('Additional offer checks passed: amended sources hide stale offers, fractional currency units and managed-request rejection.')
