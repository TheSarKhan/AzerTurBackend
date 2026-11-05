"""Nearby events: public reads, near-me ordering and radius, validation, roles, audit and rate limit."""
import base64, datetime, json, os, pathlib, urllib.error, urllib.parse, urllib.request, uuid
base=os.environ.get('API_ORIGIN','http://127.0.0.1:8080')
env=dict(l.split('=',1) for l in pathlib.Path('.env').read_text().splitlines() if '=' in l and not l.startswith('#')) if pathlib.Path('.env').exists() else {}
env.update({k:os.environ[k] for k in ('OPERATIONS_USER','OPERATIONS_PASSWORD') if k in os.environ})
def basic(u,p):return 'Basic '+base64.b64encode((u+':'+p).encode()).decode()
ops=basic(env.get('OPERATIONS_USER','operations'),env['OPERATIONS_PASSWORD'])
def call(path,body=None,auth=None,headers=None,full=False):
 h={'Content-Type':'application/json',**(headers or {})}
 if auth:h['Authorization']=auth
 req=urllib.request.Request(base+'/api/v1/'+path,json.dumps(body).encode() if body is not None else None,h)
 try:
  with urllib.request.urlopen(req,timeout=20) as r:
   raw=r.read();data=json.loads(raw) if raw else None;return (r.status,data,dict(r.headers)) if full else (r.status,data)
 except urllib.error.HTTPError as e:
  raw=e.read()
  try:data=json.loads(raw)
  except Exception:data={}
  return (e.code,data,dict(e.headers)) if full else (e.code,data)
token=uuid.uuid4().hex[:10]                       # every title carries it, so runs never see each other's data
now=datetime.datetime.now(datetime.timezone.utc)
def iso(days,hours=0):return (now+datetime.timedelta(days=days,hours=hours)).strftime('%Y-%m-%dT%H:%M:%SZ')
BAKU=(40.4093,49.8671)
def north(km):return round(BAKU[0]+km/111.195,6),BAKU[1]
def event(title,**kw):
 lat,lng=kw.pop('at',BAKU)
 return {'titleEn':f'{title} {token}','titleAz':f'{title} AZ {token}','descriptionEn':'Plain text description.','descriptionAz':'Sadə təsvir.',
  'category':'CONCERT','citySlug':'baku','venueName':'Test hall','address':'1 Test street','latitude':lat,'longitude':lng,
  'startsAt':iso(10),'endsAt':iso(10,3),'free':False,'priceFromMinor':2000,'currency':'AZN','ticketUrl':'https://example.com/tickets','sourceNote':'integration test',**kw}
def make(title,publish=True,**kw):
 status,e=call('operations/events',event(title,**kw),ops);assert status==201,(status,e)
 if publish:
  status,e=call(f"operations/events/{e['id']}/publish",{},ops);assert status==200 and e['status']=='PUBLISHED',(status,e)
 return e
# ---------------------------------------------------------------- cities and the empty case
status,cities=call('cities');assert status==200 and {c['slug'] for c in cities}>={'baku','quba','gabala','sheki'},(status,cities)
assert all(-90<=c['latitude']<=90 and -180<=c['longitude']<=180 for c in cities)
status,page=call('events?q='+token);assert status==200 and page['total']==0 and page['items']==[],page
# ---------------------------------------------------------------- creating, publishing and what the public sees
draft=make('Draft',publish=False)
assert draft['status']=='DRAFT' and draft['slug'].startswith('draft-') and draft['version']==0 and draft['createdBy']
published=make('Concert',category='CONCERT')
quba=make('Quba fair',citySlug='quba',category='MARKET',at=(41.3611,48.5126),free=True,priceFromMinor=None)
cancelled=make('Cancelled show');status,c=call(f"operations/events/{cancelled['id']}/cancel",{'note':'Artist unwell'},ops);assert status==200 and c['status']=='CANCELLED'
past=make('Past show',publish=False,startsAt=iso(-3),endsAt=iso(-3,2))
status,e=call(f"operations/events/{past['id']}/publish",{},ops);assert status==409 and e['error']=='EVENT_ALREADY_ENDED',(status,e)
status,page=call('events?q='+token);titles={i['titleEn'] for i in page['items']}
assert status==200 and page['total']==2 and titles=={f'Concert {token}',f'Quba fair {token}'},(page['total'],titles)
assert page['items'][0]['startsAt']<=page['items'][1]['startsAt'] and all(i['distanceKm'] is None for i in page['items'])
status,page=call('events?q='+token+'&city=quba');assert page['total']==1 and page['items'][0]['free'] is True and page['items'][0]['priceFromMinor'] is None
status,page=call('events?q='+token+'&category=MARKET');assert page['total']==1 and page['items'][0]['citySlug']=='quba'
status,page=call('events?q='+token+'&to='+(now+datetime.timedelta(days=5)).strftime('%Y-%m-%d'));assert page['total']==0
status,page=call('events?q='+token+'&from='+(now+datetime.timedelta(days=9)).strftime('%Y-%m-%d'));assert page['total']==2
status,page=call('events?q='+token+'&size=1');assert page['size']==1 and len(page['items'])==1 and page['total']==2
status,page=call('events?q='+token+'&size=1&page=1');assert len(page['items'])==1
status,page=call('events?q='+token.upper());assert page['total']==2,'search is case-insensitive'
status,page=call('events?q=%25');assert status==200 and all('%' in (i['titleEn']+i['titleAz']+i['venueName']) for i in page['items']),'a lone percent sign is text, not a wildcard'
status,detail=call('events/'+published['slug']);assert status==200 and detail['descriptionAz'] and detail['address']=='1 Test street' and detail['ticketUrl'].startswith('https://')
assert detail['event']['titleRu']==detail['event']['titleEn'],'missing Russian falls back to English'
for staff_only in ('createdBy','updatedBy','version','adminLocked','sourceNote','status','externalSource','source'):assert f'"{staff_only}"' not in json.dumps(detail),'staff field leaked: '+staff_only
for hidden in (draft,cancelled,past):assert call('events/'+hidden['slug'])[0]==404,hidden['slug']
assert call('events/does-not-exist')[0]==404
# ---------------------------------------------------------------- near me: order, radius and the same point
near=[make(f'Near {km}km',at=north(km)) for km in (1,10,24,26,60)]
twin_a=make('Twin A',at=BAKU,startsAt=iso(30),endsAt=iso(30,3));twin_b=make('Twin B',at=BAKU,startsAt=iso(29),endsAt=iso(29,3))
lat,lng=BAKU
status,page,h=call(f'events?q={token}&lat={lat}&lng={lng}&radiusKm=25',full=True)
names=[i['titleEn'].replace(f' {token}','') for i in page['items']];d=[i['distanceKm'] for i in page['items']]
assert status==200 and 'no-store' in h.get('Cache-Control','').lower(),'coordinate queries are never cached'
assert names==['Concert','Twin B','Twin A','Near 1km','Near 10km','Near 24km'],names
assert d==sorted(d) and all(x is not None and x<=25 for x in d),d
assert 'Near 26km' not in names and 'Near 60km' not in names and 'Quba fair' not in names,'outside the radius'
assert page['total']==len(names)
assert d[:3]==[0.0,0.0,0.0],'events at the search point are at distance 0 and ordered by start time'
status,page=call(f'events?q={token}&lat={lat}&lng={lng}&radiusKm=30');assert 'Near 26km' in [i['titleEn'].replace(f' {token}','') for i in page['items']]
status,page=call(f'events?q={token}&lat={lat}&lng={lng}&radiusKm=200');assert 'Quba fair' in [i['titleEn'].replace(f' {token}','') for i in page['items']] and page['items'][-1]['distanceKm']>150
status,page=call(f'events?q={token}&lat={lat}&lng={lng}');assert 'Near 26km' not in [i['titleEn'].replace(f' {token}','') for i in page['items']],'default radius is 25 km'
status,page=call(f'events?q={token}&lat={lat}&lng={lng}&radiusKm=25&size=2&page=1');assert len(page['items'])==2 and page['total']>=6
status,page=call(f'events?q={token}&lat=0&lng=0&radiusKm=25');assert status==200 and page['items']==[],'nothing near the Gulf of Guinea'
# ---------------------------------------------------------------- bad public input
for q in ('lat=40.4','lng=49.8','lat=91&lng=0','lat=0&lng=181','lat=abc&lng=1','lat=40&lng=49&radiusKm=0','lat=40&lng=49&radiusKm=201','size=0','size=51','page=-1','category=NOPE','from=tomorrow','to=2026-13-40','q='+'x'*81):
 assert call('events?'+q)[0]==400,q
# ---------------------------------------------------------------- office validation
def bad(**kw):
 e=event('Invalid');e.update(kw);status,body=call('operations/events',e,ops);return status
assert bad(titleEn='<script>alert(1)</script>')==400 and bad(descriptionAz='Hello <b>x</b>')==400
assert bad(ticketUrl='http://example.com')==400 and bad(ticketUrl='javascript:alert(1)')==400 and bad(image='http://example.com/a.png')==400
assert bad(endsAt=iso(9))==400 and bad(citySlug='atlantis')==400 and bad(category='BALLET')==400
assert bad(titleAz='')==400 and bad(venueName=' ')==400 and bad(latitude=91)==400 and bad(longitude=None)==400
assert bad(priceFromMinor=-5)==400 and bad(currency='JPY')==400 and bad(titleEn='x'*161)==400
assert call('operations/events',{},ops)[0]==400
incomplete=make('Needs text',publish=False,descriptionEn='')
status,e=call(f"operations/events/{incomplete['id']}/publish",{},ops);assert status==400 and e['error']=='DESCRIPTION_REQUIRED',(status,e)
# ---------------------------------------------------------------- updates are versioned
status,full=call(f"operations/events/{published['id']}",auth=ops);ev=full['event'];assert status==200 and ev['version']>=1
upd=event('Concert');upd.update(titleEn=f'Concert renamed {token}',version=ev['version'])
status,u=call(f"operations/events/{published['id']}",upd,ops);assert status==200 and u['titleEn']==f'Concert renamed {token}' and u['version']==ev['version']+1 and u['slug']==ev['slug']
status,e=call(f"operations/events/{published['id']}",upd,ops);assert status==409 and e['error']=='STALE_EVENT',(status,e)
assert call(f"operations/events/{uuid.uuid4()}",upd|{'version':0},ops)[0]==404
# ---------------------------------------------------------------- roles: anonymous, customer, operator, admin
assert call('operations/events')[0]==401 and call('operations/events',event('Anon'))[0]==401
status,session=call('auth/register',{'email':f'events-{token}@example.com','password':'Strong-test-password-2026','name':'Events Tester','locale':'en'});assert status==201
customer='Bearer '+session['token']
assert call('operations/events',auth=customer)[0]==403 and call('operations/events',event('Cust'),customer)[0]==403
assert call(f"operations/events/{draft['id']}/publish",{},customer)[0]==403
account={'username':'ev-'+token,'name':'Events operator','role':'OPERATOR','password':'Local-test-only-strong-password-2026','enabled':True,'version':0}
assert call('operations/staff',account,ops)[0]==200
operator=basic(account['username'],account['password'])
assert call('operations/events',auth=operator)[0]==200,'operators may manage events'
assert call(f"operations/events/{draft['id']}/delete",{},operator)[0]==403,'only an administrator deletes'
assert call(f"operations/events/{published['id']}/delete",{},ops)[0]==409,'published events cannot be deleted'
assert call(f"operations/events/{draft['id']}/delete",{},ops)[0]==204 and call(f"operations/events/{draft['id']}",auth=ops)[0]==404
status,listing=call('operations/events?q='+token+'&status=CANCELLED',auth=ops);assert status==200 and listing['total']==1 and listing['items'][0]['id']==cancelled['id']
assert call('operations/events?status=NOPE',auth=ops)[0]==400
# ---------------------------------------------------------------- audit trail
status,full=call(f"operations/events/{cancelled['id']}",auth=ops);actions=[a['action'] for a in full['audit']]
assert actions==['CREATED','PUBLISHED','CANCELLED'] and full['audit'][2]['note']=='Artist unwell' and all(a['actor'] for a in full['audit']),actions
assert call(f"operations/events/{cancelled['id']}/publish",{},ops)[1]['error']=='EVENT_CANCELLED'
assert call(f"operations/events/{cancelled['id']}/cancel",{},ops)[1]['error']=='ALREADY_CANCELLED'
assert call(f"operations/events/{published['id']}/publish",{},ops)[1]['error']=='ALREADY_PUBLISHED'
# ---------------------------------------------------------------- rate limit (own address so other tests are unaffected)
fake={'X-Real-IP':'198.51.100.'+str(int(token[:2],16)%200+1)}
codes=[call('events?size=1',headers=fake)[0] for _ in range(125)]
assert codes[0]==200 and 429 in codes and codes.index(429)>100,(codes.count(200),codes.count(429))
print('events flow ok')
