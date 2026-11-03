"""Opt-in loopback financial fixtures. No provider charges, SMTP or refund transfers."""
from qa_accounts import sql
import pathlib
exec(pathlib.Path('tests/cancellation_ui_flow.py').read_text().split("ctxpath=")[0])
# A synthetic historical receipt, not a payment provider operation.
receipt_key=str(uuid.uuid4())
status,issued=call('operations/bookings/'+bid+'/payment-requests',{'contractId':contract['id'],'requestKey':receipt_key,'kind':'DEPOSIT','serviceKey':None,'amount':200},ops);assert status==200,(status,issued)
sql("UPDATE staged_payment_requests SET status='PAID' WHERE booking_id='%s' AND request_key='%s'; UPDATE bookings SET status='CONFIRMED' WHERE id='%s';"%(uuid.UUID(bid),uuid.UUID(receipt_key),uuid.UUID(bid)))
fp='operations/bookings/'+bid
_,fc=call(fp+'/allocation',auth=ops)
shares={'transport':8000,'hotel':6000,'guide':4000,'transfer':1000,'activity':1000}
status,fc=call(fp+'/allocation',{'version':0,'contractId':contract['id'],'expectedPaidMinor':20000,'lines':[{'serviceKey':k,'amountMinor':v} for k,v in shares.items()]},ops);assert status==200,(status,fc)
status,change=call('bookings/'+bid+'/requests',{'kind':'CHANGE','message':'QA paid route and date amendment'},auth);assert status==200,(status,change)
rid=change['id'];ap='operations/requests/'+rid+'/amendment'
assert call('operations/requests/'+rid+'/resolve',{'approve':True,'note':'No bypass','replacementQuoteId':q['id']},ops)[1]['error']=='AMENDMENT_WORKFLOW_REQUIRED'
status,ctx=call(ap,auth=ops);assert status==200 and ctx['paidMinor']==20000 and ctx['allocationCurrent'],(status,ctx)
newinput=body|{'date':(datetime.date.today()+datetime.timedelta(days=45)).isoformat(),'customDays':1,'cities':['Baku'],'stay':False,'guide':False,'transfer':False,'activity':False}
status,target=call('quotes',newinput);assert status==200,(status,target)
draft={'version':0,'replacementQuoteId':target['id'],'title':'QA revised Baku journey','itinerary':[{'city':'Baku','description':'Customer approved new city itinerary'}],'returnRoute':'Return to Baku airport','services':[{'key':'transport','name':'Private driver','quantity':1,'nights':1,'unitPrice':100,'unit':'day','supplier':'PRIVATE-NEW-SUPPLIER','location':'Baku','availability':'CONFIRMED','policy':'QA replacement transport terms','internalNote':'PRIVATE-SERVICE'}],'previousTerms':[{'serviceKey':s['key'],'policyText':s['policy'],'supplierReference':'PRIVATE-OLD-SUPPLIER','basis':'PRIVATE-BASIS','verified':True,'tiers':[{'fromInclusive':'2000-01-01T00:00:00Z','retainedPercent':10,'fixedFeeMinor':0,'feeBasis':'PAID_AMOUNT'}]} for s in ctx['services']],'allocations':[{'serviceKey':'transport','amountMinor':10000}],'message':'New date and route with supplier fees and a partial refund.','internalNote':'PRIVATE-OFFICE'}
status,calculation=call(ap+'/calculate',draft|{'allocations':[{'serviceKey':'transport','amountMinor':0}]},ops);assert status==200 and calculation['appliedMinor']==10000 and calculation['refundMinor']==8000,(status,calculation)
status,saved=call(ap+'/draft',draft,ops);assert status==200,(status,saved)
assert call(ap+'/draft',draft,ops)[1]['error']=='STALE_AMENDMENT'
def upload_amend(version,data):
 boundary='----qa'+uuid.uuid4().hex
 payload=(('--'+boundary+'\r\nContent-Disposition: form-data; name="version"\r\n\r\n'+str(version)+'\r\n--'+boundary+'\r\nContent-Disposition: form-data; name="file"; filename="amendment.pdf"\r\nContent-Type: application/pdf\r\n\r\n').encode()+data+('\r\n--'+boundary+'--\r\n').encode())
 req=urllib.request.Request(base+'/api/v1/'+ap+'/pdf',payload,{'Authorization':ops,'Content-Type':'multipart/form-data; boundary='+boundary})
 try:
  with urllib.request.urlopen(req,timeout=20) as r:return r.status,json.loads(r.read())
 except urllib.error.HTTPError as e:return e.code,json.loads(e.read())
assert upload_amend(1,b'not pdf')[0]==400
status,saved=upload_amend(1,valid_pdf());assert status==200,(status,saved)
v={'version':saved['version'],'sha256':saved['pdf_sha256'],'pdfReviewed':True}
assert call(ap+'/send',v,ops)[1]['error']=='PREVIEW_REQUIRED'
assert call(ap+'/preview',v|{'pdfReviewed':False},ops)[1]['error']=='REVIEWED_PDF_REQUIRED'
status,projection=call(ap+'/preview',v,ops);assert status==200,(status,projection)
assert (projection['retainedMinor'],projection['refundMinor'],projection['appliedMinor'],projection['remainingMinor'])==(2000,8000,10000,0),projection
assert 'PRIVATE-' not in json.dumps(projection)
employee={'username':'qa-amend-'+suffix[:12],'name':'QA amendment operator','role':'OPERATOR','password':registration['password'],'enabled':True,'version':0}
assert call('operations/staff',employee,ops)[0]==200
employee_auth='Basic '+base64.b64encode((employee['username']+':'+employee['password']).encode()).decode()
assert call(ap+'/send',v,employee_auth)[0]==403
status,proposal=call(ap+'/send',v,ops);assert status==200,(status,proposal)
assert call(ap+'/send',v,ops)[1]['id']==proposal['id']
assert next(x for x in call('bookings',auth=auth)[1] if x['id']==bid)['quoteId']==q['id']
public=call('bookings/'+bid+'/amendments',auth=auth)[1];assert len(public)==1 and public[0]['canAccept']
assert 'PRIVATE-' not in json.dumps(public)
assert call('bookings/'+bid+'/amendments',auth=otherauth)[0]==404
accept={'amendmentId':proposal['id'],'version':1,'sha256':proposal['pdf_sha256']}
assert call('bookings/'+bid+'/amendments/accept',accept,otherauth)[0]==404
assert call('bookings/'+bid+'/amendments/accept',accept|{'sha256':'0'*64},auth)[1]['error']=='STALE_AMENDMENT'
# Concurrent acceptance applies exactly once while safe retries return same accepted version.
with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:results=list(pool.map(lambda _:call('bookings/'+bid+'/amendments/accept',accept,auth),range(2)))
assert all(x[0]==200 and x[1]['state']=='ACCEPTED' for x in results),results
updated=next(x for x in call('bookings',auth=auth)[1] if x['id']==bid);assert updated['quoteId']==target['id'] and updated['departure']==newinput['date'] and updated['paid']==0
assert sql("SELECT count(*) FROM amendment_settlements WHERE request_id='%s';"%uuid.UUID(rid))=='1'
status,payment=call('bookings/'+bid+'/payment-context',auth=auth);assert status==200 and payment['paidMinor']==10000 and payment['receivedMinor']==20000,(status,payment)
assert payment['contract']['id']!=contract['id']
assert any(x['contract_id']==contract['id'] and x['status']=='PAID' for x in payment['requests'])
assert call(customer_path+'/'+contract['id']+'/accept',{'sha256':contract['sha256']},auth)[0]==409
for suffixpdf in ['pdf','base-pdf']:
 req=urllib.request.Request(base+'/api/v1/bookings/'+bid+'/amendments/'+proposal['id']+'/'+suffixpdf,headers={'Authorization':auth})
 with urllib.request.urlopen(req) as r:assert r.read()==valid_pdf()
_,refunds=call('operations/refunds',auth=ops)
assert any(x['request_id']==rid and json.loads(x['payload'])['refundMinor']==8000 for x in refunds),refunds
# Declining a later proposal preserves this effective booking and financial settlement.
_,change2=call('bookings/'+bid+'/requests',{'kind':'CHANGE','message':'QA second amendment'},auth)
oldap=ap;ap='operations/requests/'+change2['id']+'/amendment'
_,ctx2=call(ap,auth=ops)
draft2=draft|{'version':0,'previousTerms':[{'serviceKey':'transport','policyText':draft['services'][0]['policy'],'supplierReference':'PRIVATE-REF','basis':'PRIVATE-BASE','verified':True,'tiers':[{'fromInclusive':'2000-01-01T00:00:00Z','retainedPercent':0,'fixedFeeMinor':0,'feeBasis':'PAID_AMOUNT'}]}]}
assert call(ap+'/draft',draft2,ops)[0]==200
_,saved2=upload_amend(1,valid_pdf());v2={'version':1,'sha256':saved2['pdf_sha256'],'pdfReviewed':True}
assert call(ap+'/preview',v2,ops)[0]==200
_,proposal2=call(ap+'/send',v2,ops)
assert call('bookings/'+bid+'/amendments/decline',{'amendmentId':proposal2['id'],'version':1,'message':'Keep my current journey'},auth)[1]['state']=='DECLINED'
assert call('bookings/'+bid+'/amendments/accept',{'amendmentId':proposal2['id'],'version':1,'sha256':proposal2['pdf_sha256']},auth)[0]==409
assert call('bookings/'+bid+'/payment-context',auth=auth)[1]['paidMinor']==10000
print('Paid amendment passed: immutable PDF, supplier fees, partial refund, net allocation, ownership, atomic acceptance, historical contract and decline.')
# Publishing a replacement supersedes the old immutable proposal. Later allocation edits invalidate acceptance.
_,change3=call('bookings/'+bid+'/requests',{'kind':'CHANGE','message':'QA immutable revision and source guard'},auth)
ap='operations/requests/'+change3['id']+'/amendment'
assert call(ap+'/draft',draft2,ops)[0]==200
_,saved3=upload_amend(1,valid_pdf());v3={'version':1,'sha256':saved3['pdf_sha256'],'pdfReviewed':True}
assert call(ap+'/preview',v3,ops)[0]==200
_,old_sent=call(ap+'/send',v3,ops)
status,edited=call(ap+'/draft',draft2|{'version':1,'title':'New immutable version'},ops);assert status==200 and edited['pdf_sha256'] is None
assert any(x['id']==old_sent['id'] and x['canAccept'] for x in call('bookings/'+bid+'/amendments',auth=auth)[1])
_,saved4=upload_amend(2,valid_pdf());v4={'version':2,'sha256':saved4['pdf_sha256'],'pdfReviewed':True}
assert call(ap+'/preview',v4,ops)[0]==200
_,new_sent=call(ap+'/send',v4,ops)
assert call('bookings/'+bid+'/amendments/accept',{'amendmentId':old_sent['id'],'version':1,'sha256':old_sent['pdf_sha256']},auth)[0]==409
_,fc=call('operations/bookings/'+bid+'/allocation',auth=ops)
assert call('operations/bookings/'+bid+'/allocation',{'version':fc['allocation']['version'],'contractId':fc['contractId'],'expectedPaidMinor':10000,'lines':[{'serviceKey':'transport','amountMinor':10000}]},ops)[0]==200
assert call('bookings/'+bid+'/amendments/accept',{'amendmentId':new_sent['id'],'version':2,'sha256':new_sent['pdf_sha256']},auth)[1]['error']=='AMENDMENT_SOURCE_CHANGED'
assert call('operations/requests/'+change3['id']+'/resolve',{'approve':False,'note':'Withdraw stale proposal after source changed.'},ops)[0]==200
assert any(x['id']==new_sent['id'] and x['state']=='WITHDRAWN' and not x['canAccept'] for x in call('bookings/'+bid+'/amendments',auth=auth)[1])
print('Amendment version checks passed: finance permissions, edited-PDF reset, supersession, stale funding guard and office withdrawal.')
if os.environ.get('EXPORT_QA_VISUAL_FIXTURE')=='1':
 # Local synthetic screenshot fixture; never exports real runtime credentials or tokens.
 assert os.environ.get('CI')!='true'
 _,visual_change=call('bookings/'+bid+'/requests',{'kind':'CHANGE','message':'Review a revised Baku itinerary and contract.'},auth)
 ap='operations/requests/'+visual_change['id']+'/amendment'
 assert call(ap+'/draft',draft2,ops)[0]==200
 _,saved3=upload_amend(1,valid_pdf());v3={'version':1,'sha256':saved3['pdf_sha256'],'pdfReviewed':True}
 assert call(ap+'/preview',v3,ops)[0]==200
 assert call(ap+'/send',v3,ops)[0]==200
 username='qa-visual-'+suffix[:10]
 assert call('operations/staff',{'username':username,'name':'QA Visual Finance','role':'FINANCE','password':registration['password'],'enabled':True,'version':0},ops)[0]==200
 pathlib.Path('/tmp/azertur-amendment-qa.json').write_text(json.dumps({'email':registration['email'],'password':registration['password'],'username':username,'bookingId':bid,'requestId':visual_change['id']}))
 print('Local synthetic visual fixture prepared.')
