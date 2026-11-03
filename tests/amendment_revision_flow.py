"""A cancelled service in an old revision must not cancel the replacement service."""
import pathlib
from qa_accounts import sql
exec(pathlib.Path('tests/cancellation_ui_flow.py').read_text().split("ctxpath=")[0])
sql("UPDATE bookings SET paid=200,status='CONFIRMED' WHERE id='%s';"%uuid.UUID(bid))
shares={'transport':8000,'hotel':6000,'guide':4000,'transfer':1000,'activity':1000}
allocation={'version':0,'contractId':contract['id'],'expectedPaidMinor':20000,'lines':[{'serviceKey':k,'amountMinor':v} for k,v in shares.items()]}
allocpath='operations/bookings/'+bid+'/allocation'
assert call(allocpath,allocation,ops)[0]==200
ctxpath='bookings/'+bid+'/cancellation'
_,cancel=call(ctxpath,{'contractId':contract['id'],'serviceKeys':['hotel'],'reason':'QA only','message':'Cancel old hotel before replacing journey'},auth)
_,ctx=call(ctxpath,auth=auth)
tier={'fromInclusive':'2000-01-01T00:00:00Z','retainedPercent':0,'fixedFeeMinor':0,'feeBasis':'PAID_AMOUNT'}
lines=[{'serviceKey':s['key'],'supplierReference':'QA-SUPPLIER','policyText':s['policy'],'basis':'QA supplier confirmed no fee','allocatedPaidMinor':shares[s['key']],'tiers':[tier] if s['key']=='hotel' else []} for s in ctx['services']]
cp='operations/requests/'+cancel['id']+'/cancellation-assessment'
status,assessment=call(cp,{'version':0,'contractId':contract['id'],'lines':lines,'customerExplanation':'Old hotel cancelled; refund tracked separately.'},ops);assert status==200,(status,assessment)
assert call(cp+'/approve',{'version':assessment['version']},ops)[0]==200
assert call(allocpath,allocation|{'version':1,'expectedPaidMinor':14000,'lines':[x for x in allocation['lines'] if x['serviceKey']!='hotel']},ops)[0]==200
_,change=call('bookings/'+bid+'/requests',{'kind':'CHANGE','message':'Replace journey and restore a new hotel service'},auth)
ap='operations/requests/'+change['id']+'/amendment'
_,ctx=call(ap,auth=ops);assert ctx['paidMinor']==14000
_,target=call('quotes',body|{'date':(datetime.date.today()+datetime.timedelta(days=50)).isoformat()})
draft={'version':0,'replacementQuoteId':target['id'],'title':'Replacement journey including a new hotel','itinerary':d['itinerary'],'returnRoute':'Baku airport','services':d['services'],'previousTerms':[{'serviceKey':s['key'],'policyText':s['policy'],'supplierReference':'QA-REF','basis':'QA supplier confirmed no fee','verified':True,'tiers':[tier]} for s in ctx['services']],'allocations':[{'serviceKey':k,'amountMinor':v if k!='hotel' else 0} for k,v in shares.items()],'message':'New hotel is a new service; old cancellation stays in history.','internalNote':'PRIVATE-OFFICE'}
status,saved=call(ap+'/draft',draft,ops);assert status==200,(status,saved)
source=pathlib.Path('tests/amendment_flow.py').read_text();exec(source[source.index('def upload_amend'):source.index('assert upload_amend')])
_,saved=upload_amend(1,valid_pdf());v={'version':1,'sha256':saved['pdf_sha256'],'pdfReviewed':True}
assert call(ap+'/preview',v,ops)[0]==200
_,proposal=call(ap+'/send',v,ops)
assert call('bookings/'+bid+'/amendments/accept',{'amendmentId':proposal['id'],'version':1,'sha256':proposal['pdf_sha256']},auth)[0]==200
_,current=call(ctxpath,auth=auth);assert len(current['services'])==5 and any(s['key']=='hotel' for s in current['services'])
assert sql("SELECT count(*) FROM cancelled_services WHERE booking_id='%s' AND service_revision=1;"%uuid.UUID(bid))=='1'
assert len(call('bookings/'+bid+'/cancellation-updates',auth=auth)[1])==1
status,payment=call('bookings/'+bid+'/payment-context',auth=auth);assert status==200 and not payment['paymentBlocked'] and payment['paidMinor']==14000 and payment['receivedMinor']==20000,(status,payment)
assert call('operations/bookings/'+bid+'/payment-requests',{'contractId':payment['contract']['id'],'requestKey':str(uuid.uuid4()),'kind':'SERVICE','serviceKey':'hotel','amount':50},ops)[0]==200
new_contract=payment['contract']['id']
_,new_cancel=call(ctxpath,{'contractId':new_contract,'serviceKeys':['hotel'],'reason':'QA only','message':'Cancel replacement hotel as a separate service revision'},auth)
_,current=call(ctxpath,auth=auth)
new_shares={k:v if k!='hotel' else 0 for k,v in shares.items()}
new_lines=[{'serviceKey':s['key'],'supplierReference':'QA-SUPPLIER','policyText':s['policy'],'basis':'QA no fee','allocatedPaidMinor':new_shares[s['key']],'tiers':[tier] if s['key']=='hotel' else []} for s in current['services']]
new_ap='operations/requests/'+new_cancel['id']+'/cancellation-assessment'
status,new_assessment=call(new_ap,{'version':0,'contractId':new_contract,'lines':new_lines,'customerExplanation':'Replacement hotel cancellation reviewed separately.'},ops);assert status==200,(status,new_assessment)
assert call(new_ap+'/approve',{'version':new_assessment['version']},ops)[0]==200
assert sql("SELECT count(*) FROM cancelled_services WHERE booking_id='%s' AND service_key='hotel';"%uuid.UUID(bid))=='2'
print('Revision isolation passed: old hotel cancellation and refund retained; replacement hotel, carried deposit and service payment remain active.')
