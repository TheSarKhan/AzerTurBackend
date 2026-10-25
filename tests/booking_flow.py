import base64, concurrent.futures, datetime, json, os, pathlib, urllib.request, urllib.error, uuid
base=os.environ.get('API_ORIGIN','http://127.0.0.1:8080')
env=dict(line.split('=',1) for line in pathlib.Path('.env').read_text().splitlines() if '=' in line) if pathlib.Path('.env').exists() else {}
env.update({key:os.environ[key] for key in ['OPERATIONS_USER','OPERATIONS_PASSWORD'] if key in os.environ})
ops='Basic '+base64.b64encode((env.get('OPERATIONS_USER','operations')+':'+env['OPERATIONS_PASSWORD']).encode()).decode()
def call(path,body=None,auth=None,key=None):
 headers={'Content-Type':'application/json'}
 if auth:headers['Authorization']=auth
 if key:headers['Idempotency-Key']=key
 req=urllib.request.Request(base+'/api/v1/'+path,json.dumps(body).encode() if body is not None else None,headers)
 try:
  with urllib.request.urlopen(req,timeout=20) as res:return res.status,json.load(res) if res.status!=204 and res.headers.get('Content-Length')!='0' else None
 except urllib.error.HTTPError as e:
  raw=e.read()
  try:data=json.loads(raw)
  except:data={}
  return e.code,data
suffix=uuid.uuid4().hex
registration={'email':'booking-'+suffix+'@example.com','password':'Test-only-strong-password-2026','name':'Integration Traveller','locale':'az'}
status,session=call('auth/register',registration);assert status==201,(status,session)
auth='Bearer '+session['token'];assert call('auth/me',auth=auth)[1]['locale']=='az'
assert call('auth/login',dict(email=registration['email'],password='wrong'))[0]==401
status,login=call('auth/login',dict(email=registration['email'],password=registration['password']));assert status==200
second=registration|{'email':'other-'+suffix+'@example.com'}
status,other=call('auth/register',second);assert status==201
otherauth='Bearer '+other['token']
assert call('bookings')[0]==401
assert call('operations/bookings',auth=auth)[0]==403
quote={'slug':'quba-khinalug','date':(datetime.date.today()+datetime.timedelta(days=30)).isoformat(),'pax':3,'hotel':'comfort','guide':True,'guideLanguage':'en','transfer':False,'activity':False,'currency':'EUR'}
def make_quote(**patch):
 status,q=call('quotes',dict(quote,**patch));assert status==200,(status,q);return q['id']
qid=make_quote();status,booking=call('bookings',{'quoteId':qid,'note':'Integration test request'},auth);assert status==200,(status,booking)
bid=booking['id'];assert booking['status']=='PENDING_REVIEW'
assert call('bookings',{'quoteId':qid,'note':'duplicate'},auth)[1]['id']==bid
assert call('bookings/'+bid+'/demo-payment',{'kind':'DEPOSIT','success':True},auth,str(uuid.uuid4()))[0]==409
assert call('bookings/'+bid+'/requests',{'kind':'CANCEL','message':'Other customer test'},otherauth)[0]==404
assert call('bookings/'+bid+'/demo-payment',{'kind':'DEPOSIT','success':True},otherauth,str(uuid.uuid4()))[0]==404
assert call('operations/bookings/'+bid+'/review',{'approve':True,'note':'Availability checked'},auth)[0]==403
assert call('operations/bookings/'+bid+'/review',{'note':'Decision must be explicit'},ops)[0]==400
status,booking=call('operations/bookings/'+bid+'/review',{'approve':True,'note':'Availability checked'},ops);assert status==200 and booking['status']=='AWAITING_PAYMENT',(status,booking)
assert call('operations/bookings/'+bid+'/review',{'approve':True,'note':'Duplicate review'},ops)[0]==409
assert call('bookings/'+bid+'/demo-payment',{'kind':'DEPOSIT','success':False},auth,str(uuid.uuid4()))[0]==409
key=str(uuid.uuid4());status,booking=call('bookings/'+bid+'/demo-payment',{'kind':'DEPOSIT','success':True},auth,key);assert status==200 and booking['paid']==159 and booking['status']=='CONFIRMED',(status,booking)
assert call('bookings/'+bid+'/demo-payment',{'kind':'DEPOSIT','success':True},auth,key)[1]['paid']==159
assert call('bookings/'+bid+'/demo-payment',{'kind':'DEPOSIT','success':True},auth,str(uuid.uuid4()))[0]==409
status,booking=call('bookings/'+bid+'/demo-payment',{'kind':'BALANCE','success':True},auth,str(uuid.uuid4()));assert status==200 and booking['paid']==530
status,request=call('bookings/'+bid+'/requests',{'kind':'CHANGE','message':'Please change the pickup time.'},auth);assert status==200,(status,request)
assert call('bookings/'+bid+'/requests',{'kind':'CANCEL','message':'Duplicate active request'},auth)[0]==409
assert call('operations/requests/'+request['id']+'/resolve',{'approve':True,'note':'Missing amendment quote'},ops)[0]==400
new_date=(datetime.date.today()+datetime.timedelta(days=31)).isoformat()
wrong_currency=make_quote(currency='USD')
assert call('operations/requests/'+request['id']+'/resolve',{'approve':True,'note':'Invalid paid currency switch','replacementQuoteId':wrong_currency},ops)[0]==409
below_paid=make_quote(pax=1)
assert call('operations/requests/'+request['id']+'/resolve',{'approve':True,'note':'Requires a manual refund','replacementQuoteId':below_paid},ops)[0]==409
amendment=make_quote(date=new_date)
assert call('operations/requests/'+request['id']+'/resolve',{'approve':True,'note':'Date changed with customer agreement.','replacementQuoteId':amendment},ops)[0]==200
updated=next(b for b in call('bookings',auth=auth)[1] if b['id']==bid);assert updated['departure']==new_date and updated['quoteId']==amendment
assert call('bookings',{'quoteId':qid,'note':'Retry original request after amendment'},auth)[1]['id']==bid
status,request=call('bookings/'+bid+'/requests',{'kind':'CANCEL','message':'Please cancel the demo booking.'},auth);assert status==200
assert call('operations/requests/'+request['id']+'/resolve',{'approve':True,'note':'Cancellation accepted; demo refund handled manually.'},ops)[0]==200
assert call('operations/requests/'+request['id']+'/resolve',{'approve':True,'note':'Repeat cancellation'},ops)[0]==409
assert call('bookings/'+bid+'/demo-payment',{'kind':'BALANCE','success':True},auth,str(uuid.uuid4()))[0]==409
# Concurrent create and payment requests must not produce duplicate reservations or charges.
qid=make_quote()
with concurrent.futures.ThreadPoolExecutor(max_workers=4) as pool:
 results=list(pool.map(lambda _:call('bookings',{'quoteId':qid,'note':'Concurrency test'},auth),range(4)))
assert all(status==200 for status,_ in results),results
assert len(set(result['id'] for _,result in results))==1
race=results[0][1]['id'];assert call('operations/bookings/'+race+'/review',{'approve':True,'note':'Race test approved'},ops)[0]==200
with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:
 results=list(pool.map(lambda _:call('bookings/'+race+'/demo-payment',{'kind':'DEPOSIT','success':True},auth,str(uuid.uuid4())),range(2)))
assert sorted(status for status,_ in results)==[200,409],results
status,pending=call('bookings/'+race+'/requests',{'kind':'CANCEL','message':'Pause further payment for office review'},auth);assert status==200
status,blocked=call('bookings/'+race+'/demo-payment',{'kind':'BALANCE','success':True},auth,str(uuid.uuid4()));assert status==409 and blocked['error']=='REQUEST_PENDING'
assert call('operations/requests/'+pending['id']+'/resolve',{'approve':False,'note':'Cancellation declined after customer discussion'},ops)[0]==200
assert call('bookings/'+race+'/demo-payment',{'kind':'BALANCE','success':True},auth,str(uuid.uuid4()))[1]['paid']==530
status,outbox=call('operations/notifications',auth=ops);assert status==200 and outbox
email=next(n for n in outbox if n['channel']=='EMAIL')
assert call('operations/notifications/'+email['id']+'/send-email',{},ops)[0]==409
assert call('auth/logout',{},'Bearer '+login['token'])[0]==204
assert call('auth/me',auth='Bearer '+login['token'])[0]==401
print('Booking flow passed: registration, ownership, operator review, demo payments, idempotency, requests, concurrency, outbox and logout.')
