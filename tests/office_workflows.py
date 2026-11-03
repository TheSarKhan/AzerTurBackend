"""Local integration workflow. Financial fixtures are synthetic; no gateway or email is called."""
import subprocess
from pathlib import Path
# Shared fixture: two synthetic customers, sent offer and accepted local QA PDF.
exec(Path('tests/cancellation_ui_flow.py').read_text().split("ctxpath=")[0])
assert base.startswith(('http://127.0.0.1:', 'http://localhost:')), 'Local API only'
assert os.environ.get('ALLOW_LOCAL_FINANCIAL_FIXTURES')=='1', 'Set ALLOW_LOCAL_FINANCIAL_FIXTURES=1 for local QA database fixtures'

def basic(username,password):return 'Basic '+base64.b64encode((username+':'+password).encode()).decode()
def raw(path,body=None,key=None):
 req=urllib.request.Request(base+'/api/v1/'+path,json.dumps(body).encode() if body is not None else None,{'Content-Type':'application/json',**({'X-Partner-Key':key} if key else {})})
 try:
  with urllib.request.urlopen(req,timeout=20) as response:
   data=response.read();return response.status,json.loads(data) if data else None
 except urllib.error.HTTPError as e:return e.code,json.loads(e.read())
_,me=call('operations/me',auth=ops);assert 'ROLE_ADMIN' in me['roles'] and 'ROLE_FINANCE' in me['roles']
account={'username':'qa-'+suffix[:12],'name':'QA-only operator','role':'OPERATOR','password':'Local-test-only-strong-password-2026','enabled':True,'version':0}
finance_account=account|{'username':'qa-fin-'+suffix[:12],'name':'QA-only finance','role':'FINANCE'}
assert call('operations/staff',account,ops)[0]==200
assert call('operations/staff',finance_account,ops)[0]==200
employee=basic(account['username'],account['password']);finance=basic(finance_account['username'],finance_account['password'])
assert call('operations/staff',auth=employee)[0]==403
assert call('operations/staff',account|{'role':'ADMIN'},employee)[0]==403
assert call('operations/staff',account,ops)[0]==409
assert 'password' not in json.dumps(call('operations/staff',auth=ops)[1])
assert call('operations/request-workspace',auth=employee)[0]==200
assignpath='operations/bookings/'+bid+'/assignment'
assert call(assignpath,{'assign':True,'expectedOwner':None,'assignee':finance_account['username']},employee)[0]==409
assert call(assignpath,{'assign':True,'expectedOwner':None,'assignee':account['username']},ops)[0]==200
assert call(assignpath,{'assign':False,'expectedOwner':account['username']},finance)[0]==409
assert call(assignpath,{'assign':False,'expectedOwner':account['username']},ops)[0]==200

partner_id=str(uuid.uuid4());service_id=str(uuid.uuid4())
p={'id':partner_id,'name':'QA-only hotel '+suffix[:8],'kind':'HOTEL','email':'qa@example.com','phone':'','notes':'PRIVATE-PARTNER-NOTE','active':True,'version':0}
assert call('operations/partners',p,employee)[0]==200
assert call('operations/partners',p,employee)[0]==409
service={'id':service_id,'partnerId':partner_id,'name':'QA room','serviceKey':'hotel','location':'Quba','currency':'EUR','priceMinor':12345,'availableFrom':'2026-01-01','availableTo':'2028-01-01','capacity':4,'policy':'QA supplier cancellation terms only','active':True,'version':0}
assert call('operations/partner-services',service,employee)[0]==200
assert call('operations/partner-services',service|{'availableTo':'2025-01-01'},employee)[0]==400
assert call('operations/partners/'+partner_id+'/sync-key',{'version':1},employee)[0]==403
status,key=call('operations/partners/'+partner_id+'/sync-key',{'version':1},ops);assert status==200
assert key['key'] not in json.dumps(call('operations/partners',auth=ops)[1])
assert raw('partner-sync/'+partner_id,service|{'version':1})[0]==401
assert raw('partner-sync/'+partner_id,service|{'version':1,'priceMinor':13025},key['key'])[0]==200
assert raw('partner-sync/'+partner_id,service|{'version':1},key['key'])[0]==409
assert raw('partner-sync/'+partner_id,key=key['key'])[1][0]['price_minor']==13025
assert raw('partner-sync/'+partner_id,key=key['key'])[1][0]['available_from']==service['availableFrom']
_,key2=call('operations/partners/'+partner_id+'/sync-key',{'version':2},ops)
assert raw('partner-sync/'+partner_id,key=key['key'])[0]==401
assert raw('partner-sync/'+partner_id,key=key2['key'])[0]==200
assert call('operations/partners',auth=auth)[0]==403

_,inbox=call('bookings/notifications',auth=auth);assert inbox['unread']>=2
assert all(set(n)<=set(['id','booking_id','event','read_at','created_at']) for n in inbox['items'])
notice=inbox['items'][0]['id'];assert call('bookings/notifications/'+notice+'/read',{},otherauth)[0]==404
assert call('bookings/notifications/'+notice+'/read',{},auth)[0]==204
assert call('bookings/notifications/read-all',{},auth)[0]==204
assert call('bookings/notifications',auth=auth)[1]['unread']==0
_,delivery_state=call('operations/notifications',auth=employee)
assert delivery_state['emailConfigured'] is False and delivery_state['pushConfigured'] is False
push_job=next(x for x in delivery_state['items'] if x['channel']=='PUSH')
assert call('operations/notifications/'+push_job['id']+'/retry-push',{},employee)[0]==409
push={'token':'ExpoPushToken['+suffix+']','enabled':True}
assert call('bookings/notifications/push-token',push,auth)[0]==204
assert call('bookings/notifications/push-token',push,otherauth)[0]==409
assert call('bookings/notifications/push-token',push|{'enabled':False},auth)[0]==204

payment='operations/bookings/'+bid+'/payment-requests'
deposit={'contractId':contract['id'],'requestKey':str(uuid.uuid4()),'kind':'DEPOSIT','serviceKey':None,'amount':200}
assert call(payment,deposit,ops)[0]==200
# LOCAL QA ONLY: emulate a provider-confirmed receipt in the isolated loopback development database.
if os.environ.get('CI')=='true':
 containers=subprocess.check_output(['docker','ps','--filter','ancestor=postgres:17-alpine','--format','{{.ID}}']).decode().splitlines()
 assert len(containers)==1, 'Expected exactly one CI PostgreSQL service'
 fixture_command=['docker','exec','-i',containers[0],'psql','-U','azertur','-d','azertur','-v','ON_ERROR_STOP=1']
else:fixture_command=['docker','compose','exec','-T','postgres','psql','-U','azertur','-d','azertur','-v','ON_ERROR_STOP=1']
subprocess.run(fixture_command,input=f"UPDATE staged_payment_requests SET status='PAID' WHERE booking_id='{uuid.UUID(bid)}' AND request_key='{uuid.UUID(deposit['requestKey'])}';".encode(),check=True,capture_output=True)
assert call(path,d,ops)[0]==409
allocpath='operations/bookings/'+bid+'/allocation'
shares={'transport':8000,'hotel':6000,'guide':4000,'transfer':1000,'activity':1000}
allocation={'version':0,'contractId':contract['id'],'expectedPaidMinor':20000,'lines':[{'serviceKey':k,'amountMinor':v} for k,v in shares.items()]}
assert call(allocpath,allocation,employee)[0]==403
assert call(allocpath,allocation|{'expectedPaidMinor':19999},finance)[0]==409
assert call(allocpath,allocation|{'lines':allocation['lines'][:-1]},finance)[0]==409
status,context=call(allocpath,allocation,finance);assert status==200 and context['allocationCurrent'],(status,context)
assert call(allocpath,allocation,finance)[0]==409
service_request={'contractId':contract['id'],'requestKey':str(uuid.uuid4()),'kind':'SERVICE','serviceKey':'hotel','amount':60}
assert call(payment,service_request,ops)[1]['error']=='SERVICE_AMOUNT_EXCEEDED'
assert call(payment,service_request|{'amount':50},ops)[0]==200
ctxpath='bookings/'+bid+'/cancellation'
status,cancel=call(ctxpath,{'contractId':contract['id'],'serviceKeys':['hotel'],'reason':'QA only','message':'Synthetic hotel cancellation'},auth);assert status==200,(status,cancel)
_,ctx=call(ctxpath,auth=auth)
lines=[{'serviceKey':s['key'],'supplierReference':'QA-SUPPLIER','policyText':s['policy'],'basis':'QA rule only','allocatedPaidMinor':shares[s['key']],'tiers':[{'fromInclusive':'2000-01-01T00:00:00Z','retainedPercent':10,'fixedFeeMinor':0,'feeBasis':'PAID_AMOUNT'}] if s['key']=='hotel' else []} for s in ctx['services']]
ap='operations/requests/'+cancel['id']+'/cancellation-assessment'
status,assessment=call(ap,{'version':0,'contractId':contract['id'],'lines':lines,'customerExplanation':'QA partial cancellation assessment','internalNote':'PRIVATE-QA-NOTE'},ops);assert status==200,(status,assessment)
assert call(ap+'/approve',{'version':assessment['version']},ops)[0]==200
assert call(payment,service_request|{'requestKey':str(uuid.uuid4()),'serviceKey':'transport','amount':50},ops)[1]['error']=='PAYMENT_PLAN_REVIEW_REQUIRED'
assert call('bookings/'+bid+'/payment-context',auth=auth)[1]['offer']['total']==385
assert call('bookings/'+bid+'/payment-context',auth=auth)[1]['paidMinor']==14000
allocation={'version':1,'contractId':contract['id'],'expectedPaidMinor':14000,'lines':[{'serviceKey':k,'amountMinor':v} for k,v in shares.items() if k!='hotel']}
status,c=call(allocpath,allocation,finance);assert status==200 and c['allocationCurrent'],(status,c)
planpath='operations/bookings/'+bid+'/revised-plan'
assert call(planpath,{'allocationVersion':2},employee)[0]==403
status,plan=call(planpath,{'allocationVersion':2},finance);assert status==200 and plan['state']=='SENT',(status,plan)
assert json.loads(plan['payload'])['remainingMinor']==24500
assert call(planpath,{'allocationVersion':2},finance)[1]['id']==plan['id']
assert call('bookings/'+bid+'/revised-plan',auth=otherauth)[0]==404
assert call('bookings/'+bid+'/revised-plan',{'planId':plan['id'],'version':plan['version']},auth)[1]['state']=='ACCEPTED'
assert call(payment,service_request|{'requestKey':str(uuid.uuid4()),'serviceKey':'transport','amount':50},ops)[0]==200
refundpath='operations/refunds/'+cancel['id']
assert call(refundpath,{'version':0,'state':'PROCESSING','reference':'','customerMessage':'QA processing','manualTransferVerified':False},employee)[0]==403
assert call(refundpath,{'version':0,'state':'PROCESSING','reference':'','customerMessage':'QA processing','manualTransferVerified':False},finance)[0]==200
assert call(refundpath,{'version':1,'state':'COMPLETED_MANUAL','reference':'','customerMessage':'QA only','manualTransferVerified':True},finance)[0]==400
assert call(refundpath,{'version':1,'state':'COMPLETED_MANUAL','reference':'QA-TRANSFER-ONLY','customerMessage':'Synthetic QA manual completion; no money transferred.','manualTransferVerified':True},finance)[0]==200
_,updates=call('bookings/'+bid+'/cancellation-updates',auth=auth)
assert updates[0]['refundStatus']=='COMPLETED_MANUAL' and 'PRIVATE-' not in json.dumps(updates)
assert 'QA-TRANSFER-ONLY' not in json.dumps(updates)
assert call(refundpath,{'version':2,'state':'FAILED','reference':'','customerMessage':'','manualTransferVerified':False},finance)[0]==409
for a in [account,finance_account]:assert call('operations/staff',a|{'version':1,'password':'','enabled':False},ops)[0]==200
assert call('operations/me',auth=employee)[0]==401
print('Office workflows passed: staff roles/revocation, partner inventory/sync/key rotation, notification ownership/read state, received-fund allocation/caps, partial cancellation, revised-plan acceptance, refund evidence/transitions and privacy. No external payments or notifications sent.')
