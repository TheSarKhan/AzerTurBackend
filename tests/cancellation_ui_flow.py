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

def upload(path, data, auth, offer_id=None):
 if offer_id is None: offer_id=call(path+'-offer',auth=ops)[1]['offerId']
 boundary='----qa'+uuid.uuid4().hex
 payload=(('--'+boundary+'\r\nContent-Disposition: form-data; name="file"; filename="sample.pdf"\r\nContent-Type: application/pdf\r\n\r\n').encode()+data+('\r\n--'+boundary+'--\r\n').encode())
 payload=payload.replace(('--'+boundary+'--').encode(), ('--'+boundary+'\r\nContent-Disposition: form-data; name="offerId"\r\n\r\n'+offer_id+'\r\n--'+boundary+'--').encode())
 req=urllib.request.Request(base+'/api/v1/'+path,payload,{'Authorization':auth,'Content-Type':'multipart/form-data; boundary='+boundary})
 try:
  with urllib.request.urlopen(req,timeout=20) as r:return r.status,json.loads(r.read())
 except urllib.error.HTTPError as e:return e.code,json.loads(e.read())
cp='operations/bookings/'+bid+'/contract'
customer_path='bookings/'+bid+'/contract'
assert upload(cp,b'not a pdf',ops)[1]['error']=='INVALID_PDF'
def valid_pdf():
 objects=[b'<< /Type /Catalog /Pages 2 0 R >>',b'<< /Type /Pages /Kids [3 0 R] /Count 1 >>',b'<< /Type /Page /Parent 2 0 R /MediaBox [0 0 300 300] >>']
 out=b'%PDF-1.4\n'; offsets=[0]
 for i,obj in enumerate(objects,1):
  offsets.append(len(out)); out+=f'{i} 0 obj\n'.encode()+obj+b'\nendobj\n'
 xref=len(out); out+=b'xref\n0 4\n0000000000 65535 f \n'
 for offset in offsets[1:]:out+=f'{offset:010d} 00000 n \n'.encode()
 return out+f'trailer\n<< /Size 4 /Root 1 0 R >>\nstartxref\n{xref}\n%%EOF\n'.encode()
assert upload(cp,b'%PDF-1.4 not really a PDF',ops)[1]['error']=='INVALID_PDF'
status,contract=upload(cp,valid_pdf(),ops);assert status==200,(status,contract)
assert contract['state']=='DRAFT'
assert call(customer_path,auth=auth)==(200,None)
assert call(cp+'/'+contract['id']+'/send',{},auth)[0]==403
assert call(cp+'/'+contract['id']+'/send',{},ops)[1]['state']=='SENT'
assert call(cp+'/'+contract['id']+'/send',{},ops)[1]['id']==contract['id']
assert call(customer_path,auth=otherauth)==(200,None)
assert call(customer_path,auth=auth)[1]['id']==contract['id']
req=urllib.request.Request(base+'/api/v1/'+customer_path+'/'+contract['id']+'/pdf',headers={'Authorization':auth})
with urllib.request.urlopen(req) as response:
 assert response.headers.get_content_type()=='application/pdf' and response.read()==valid_pdf()
req=urllib.request.Request(base+'/api/v1/'+customer_path+'/'+contract['id']+'/pdf',headers={'Authorization':otherauth})
try:
 urllib.request.urlopen(req)
 raise AssertionError('another customer could access PDF')
except urllib.error.HTTPError as e:
 assert e.code==404
assert call(customer_path+'/'+contract['id']+'/accept',{'sha256':'wrong'},auth)[1]['error']=='STALE_CONTRACT'
assert call(customer_path+'/'+contract['id']+'/accept',{'sha256':contract['sha256']},otherauth)[0]==404
assert call(customer_path+'/'+contract['id']+'/accept',{'sha256':contract['sha256']},auth)[1]['state']=='ACCEPTED'
assert call(customer_path+'/'+contract['id']+'/accept',{'sha256':contract['sha256']},auth)[1]['state']=='ACCEPTED'
assert next(x for x in call('bookings',auth=auth)[1] if x['id']==bid)['status']=='PENDING_REVIEW'


ctxpath='bookings/'+bid+'/cancellation'
assert call(ctxpath,auth=otherauth)[0]==404
_,context=call(ctxpath,auth=auth)
assert len(context['services'])==5
body={'contractId':contract['id'],'serviceKeys':['hotel'],'reason':'Change of plans','message':'QA partial hotel cancellation'}
assert call(ctxpath,body|{'serviceKeys':['hotel','hotel']},auth)[0]==400
assert call(ctxpath,body|{'contractId':str(uuid.uuid4())},auth)[1]['error']=='STALE_CONTRACT'
_,request=call(ctxpath,body,auth)
ap='operations/requests/'+request['id']+'/cancellation-assessment'
assert call('operations/requests/'+request['id']+'/cancellation-context',auth=ops)[1]['serviceKeys']==['hotel']
lines=[{'serviceKey':s['key'],'supplierReference':'PRIVATE-SUPPLIER','policyText':s['policy'],'basis':'PRIVATE-BASIS','allocatedPaidMinor':0,'tiers':[{'fromInclusive':'2000-01-01T00:00:00Z','retainedPercent':10,'fixedFeeMinor':0,'feeBasis':'PAID_AMOUNT'}] if s['key']=='hotel' else []} for s in context['services']]
_,assessment=call(ap,{'version':0,'contractId':contract['id'],'lines':lines,'customerExplanation':'Hotel cancellation reviewed.','internalNote':'PRIVATE-OFFICE'},ops)
assert assessment['state']=='REVIEWED',assessment
assert 'PRIVATE-' not in json.dumps(call('bookings/'+bid+'/cancellation-updates',auth=auth)[1])
assert call(ap+'/approve',{'version':assessment['version']},ops)[1]['state']=='APPROVED'
assert next(b for b in call('bookings',auth=auth)[1] if b['id']==bid)['status']=='PENDING_REVIEW'
_,updates=call('bookings/'+bid+'/cancellation-updates',auth=auth)
assert len(updates[0]['lines'])==1 and updates[0]['lines'][0]['serviceKey']=='hotel'
assert 'PRIVATE-' not in json.dumps(updates)
_,remaining=call(ctxpath,auth=auth)
assert len(remaining['services'])==4 and all(s['key']!='hotel' for s in remaining['services'])
assert call('operations/bookings/'+bid+'/offer',d,ops)[1]['error']=='PARTIAL_CANCELLATION_REQUIRES_MANUAL_REVIEW'
assert call('operations/bookings/'+bid+'/payment-requests',{'contractId':contract['id'],'requestKey':str(uuid.uuid4()),'kind':'DEPOSIT','serviceKey':None,'amount':10},ops)[1]['error']=='PAYMENT_PLAN_REVIEW_REQUIRED'
payment_req=urllib.request.Request(base+'/api/v1/bookings/'+bid+'/demo-payment',json.dumps({'kind':'BALANCE','success':True}).encode(),{'Content-Type':'application/json','Authorization':auth,'Idempotency-Key':str(uuid.uuid4())})
try:
 urllib.request.urlopen(payment_req)
 raise AssertionError('Legacy payment bypassed the partial cancellation gate')
except urllib.error.HTTPError as e:
 assert json.loads(e.read())['error']=='PAYMENT_PLAN_REVIEW_REQUIRED'

assert call(ctxpath,body,auth)[1]['error']=='INVALID_SERVICE_SELECTION'
body['serviceKeys']=[s['key'] for s in remaining['services']]
_,request=call(ctxpath,body,auth)
ap='operations/requests/'+request['id']+'/cancellation-assessment'
lines=[l for l in lines if l['serviceKey']!='hotel']
for line in lines:line['tiers']=[{'fromInclusive':'2000-01-01T00:00:00Z','retainedPercent':0,'fixedFeeMinor':0,'feeBasis':'PAID_AMOUNT'}]
status,assessment=call(ap,{'version':0,'contractId':contract['id'],'lines':lines,'customerExplanation':'Ödəniş yoxlanıb. Отмена проверена.'},ops);assert status==200,(status,assessment)
assert call(ap+'/approve',{'version':assessment['version']},ops)[1]['state']=='APPROVED'
assert next(b for b in call('bookings',auth=auth)[1] if b['id']==bid)['status']=='CANCELLED'
assert call(ctxpath,auth=auth)[1]['services']==[]
print('Cancellation UI API checks passed: selection, partial/full transitions, remaining services, policy source and customer privacy.')
pdfpath='bookings/'+bid+'/cancellation-updates/'+request['id']+'/pdf'
req=urllib.request.Request(base+'/api/v1/'+pdfpath,headers={'Authorization':auth})
with urllib.request.urlopen(req) as response:
 pdf=response.read()
 assert response.headers.get_content_type()=='application/pdf' and pdf.startswith(b'%PDF-')
req=urllib.request.Request(base+'/api/v1/'+pdfpath,headers={'Authorization':otherauth})
try:
 urllib.request.urlopen(req)
 raise AssertionError('Other customer accessed summary PDF')
except urllib.error.HTTPError as e:
 assert e.code==404
import shutil,subprocess
if shutil.which('pdftotext'):
 text=subprocess.run(['pdftotext','-','-'],input=pdf,stdout=subprocess.PIPE,check=True).stdout.decode()
 assert 'PRIVATE-' not in text and 'not proof of a refund transfer' in text
 assert 'Ödəniş' in text and 'Отмена' in text
print('Cancellation summary PDF and cross-customer access checks passed.')
