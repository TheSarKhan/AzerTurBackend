import os, json, base64, pathlib, urllib.request, urllib.error, datetime
origin=os.environ.get('API_ORIGIN','http://127.0.0.1:8080')
def call(path,body=None,authorization=None,base=origin):
 headers={'Content-Type':'application/json'}
 if authorization: headers['Authorization']=authorization
 try:
  request=urllib.request.Request(base+path,json.dumps(body).encode() if body is not None else None,headers)
  with urllib.request.urlopen(request,timeout=15) as response: return response.status,json.load(response)
 except urllib.error.HTTPError as error: return error.code,None
preflight=urllib.request.Request(origin+'/api/v1/operations/overview',method='OPTIONS',headers={'Origin':'http://localhost:5173','Access-Control-Request-Method':'GET','Access-Control-Request-Headers':'authorization'})
with urllib.request.urlopen(preflight) as response: assert response.headers.get('Access-Control-Allow-Origin')=='http://localhost:5173'
assert call('/actuator/health')[1]['status']=='UP'
for _ in range(2):
 status,catalog=call('/api/v1/tours');assert status==200 and len(catalog)==3
assert call('/api/v1/operations/overview')[0]==401
assert call('/v3/api-docs')[0]==200
body=dict(slug='quba-khinalug',date=(datetime.date.today()+datetime.timedelta(days=30)).isoformat(),pax=3,hotel='comfort',guide=True,transfer=False,activity=False,currency='EUR')
for currency,total in [('EUR',530),('USD',583),('AZN',991)]:
 status,quote=call('/api/v1/quotes',dict(body,currency=currency));assert status==200 and quote['total']==total;assert quote['deposit']+quote['balance']==total
for patch in [dict(pax=13),dict(date='bad-date'),dict(customDays=2,cities=['Baku','Khinalug'])]:assert call('/api/v1/quotes',dict(body,**patch))[0]==400
secrets={}
env=pathlib.Path('.env')
if env.exists(): secrets=dict(line.split('=',1) for line in env.read_text().splitlines() if '=' in line and not line.startswith('#'))
password=os.environ.get('OPERATIONS_PASSWORD',secrets.get('OPERATIONS_PASSWORD'))
if password:
 user=os.environ.get('OPERATIONS_USER',secrets.get('OPERATIONS_USER','operations'))
 authorization='Basic '+base64.b64encode((user+':'+password).encode()).decode()
 status,summary=call('/api/v1/operations/overview',authorization=authorization);assert status==200 and summary['tourCount']==3 and summary['quoteCount']>=3
if os.environ.get('FRONTEND_ORIGIN'):
 status,quote=call('/api/quotes',body,base=os.environ['FRONTEND_ORIGIN']);assert status==200 and quote['total']==530
print('API smoke checks passed: health, cached catalogue, security, Swagger, currencies, persistence and validation.')
