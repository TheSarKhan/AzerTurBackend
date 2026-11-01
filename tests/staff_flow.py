import json, os, pathlib, urllib.request, urllib.error, uuid
base=os.environ.get('API_ORIGIN','http://127.0.0.1:8080')
env=dict(line.split('=',1) for line in pathlib.Path('.env').read_text().splitlines() if '=' in line) if pathlib.Path('.env').exists() else {}
env.update({key:os.environ[key] for key in ['BOOTSTRAP_ADMIN_EMAIL','BOOTSTRAP_ADMIN_PASSWORD'] if key in os.environ})
def call(path,body=None,auth=None):
 headers={'Content-Type':'application/json'}
 if auth:headers['Authorization']=auth
 req=urllib.request.Request(base+'/api/v1/'+path,json.dumps(body).encode() if body is not None else None,headers)
 try:
  with urllib.request.urlopen(req,timeout=20) as res:return res.status,json.load(res) if res.status!=204 and res.headers.get('Content-Length')!='0' else None
 except urllib.error.HTTPError as e:
  raw=e.read()
  try:data=json.loads(raw)
  except:data={}
  return e.code,data
def login(email,password):
 status,session=call('staff/auth/login',dict(email=email,password=password));return status,session
status,admin=login(env['BOOTSTRAP_ADMIN_EMAIL'],env['BOOTSTRAP_ADMIN_PASSWORD']);assert status==200 and admin['staff']['role']=='ADMIN',(status,admin)
adm='Bearer '+admin['token']
assert admin['staff']['name']=='Administrator' and admin['staff']['email']==env['BOOTSTRAP_ADMIN_EMAIL'].lower(),admin
assert login(env['BOOTSTRAP_ADMIN_EMAIL'],'wrong-password-123')[0]==401
assert login('nobody-'+uuid.uuid4().hex+'@example.com','wrong-password-123')[0]==401
assert call('staff/auth/me')[0]==401
status,me=call('staff/auth/me',auth=adm);assert status==200 and me['email']==env['BOOTSTRAP_ADMIN_EMAIL'].lower() and me['name']=='Administrator',me
# admin-only endpoints reject anonymous callers, customers and operators
assert call('admin/staff')[0]==401
suffix=uuid.uuid4().hex[:10]
customer={'email':'cust-'+suffix+'@example.com','password':'Test-only-strong-password-2026','name':'Customer','locale':'az'}
status,session=call('auth/register',customer);assert status==201
cust='Bearer '+session['token']
assert call('admin/staff',auth=cust)[0]==403 and call('operations/bookings',auth=cust)[0]==403 and call('staff/auth/me',auth=cust)[0]==403
# create an operator; weak passwords, bad roles and duplicates are refused
op_email='operator-'+suffix+'@example.com';op_password='Operator-only-password-2026'
assert call('admin/staff',dict(email=op_email,name='Operator One',role='OPERATOR',password='short'),adm)[0]==400
assert call('admin/staff',dict(email=op_email,name='Operator One',role='ROOT',password=op_password),adm)[0]==400
status,created=call('admin/staff',dict(email=op_email.upper(),name='Operator One',role='OPERATOR',password=op_password),adm);assert status==201 and created['email']==op_email and created['role']=='OPERATOR' and 'password' not in json.dumps(created),(status,created)
assert call('admin/staff',dict(email=op_email,name='Duplicate',role='OPERATOR',password=op_password),adm)[0]==409
status,operator=login(op_email,op_password);assert status==200 and operator['staff']['role']=='OPERATOR'
op='Bearer '+operator['token']
# operators can work the queue but cannot manage staff
assert call('operations/bookings',auth=op)[0]==200 and call('operations/requests',auth=op)[0]==200
assert call('admin/staff',auth=op)[0]==403
assert call('admin/staff',dict(email='x-'+suffix+'@example.com',name='Nope',role='ADMIN',password=op_password),op)[0]==403
assert call('admin/staff/'+created['id']+'/role',dict(role='ADMIN'),op)[0]==403
# list never exposes secrets
status,staff=call('admin/staff',auth=adm);assert status==200 and any(a['id']==created['id'] for a in staff)
assert 'password' not in json.dumps(staff).lower() and 'hash' not in json.dumps(staff).lower()
# safeguards: admins cannot lock themselves out or remove the last admin
assert call('admin/staff/'+admin['staff']['id']+'/active',dict(active=False),adm)[0]==409
assert call('admin/staff/'+admin['staff']['id']+'/role',dict(role='OPERATOR'),adm)[0]==409
# password reset revokes sessions; the new password works
assert call('admin/staff/'+created['id']+'/password',dict(password='short'),adm)[0]==400
assert call('admin/staff/'+created['id']+'/password',dict(password='Operator-new-password-2027'),adm)[0]==200
assert call('operations/bookings',auth=op)[0]==401
assert login(op_email,op_password)[0]==401
status,operator=login(op_email,'Operator-new-password-2027');assert status==200
op='Bearer '+operator['token']
# deactivation revokes the session immediately and blocks new sign-ins
assert call('admin/staff/'+created['id']+'/active',dict(active=False),adm)[0]==200
assert call('operations/bookings',auth=op)[0]==401
assert login(op_email,'Operator-new-password-2027')[0]==401
assert call('admin/staff/'+created['id']+'/active',dict(active=True),adm)[0]==200
status,operator=login(op_email,'Operator-new-password-2027');assert status==200
op='Bearer '+operator['token']
# a role change takes effect on the next sign-in and revokes the old session
assert call('admin/staff/'+created['id']+'/role',dict(role='ADMIN'),adm)[0]==200
assert call('admin/staff',auth=op)[0]==401
status,promoted=login(op_email,'Operator-new-password-2027');assert status==200 and promoted['staff']['role']=='ADMIN'
assert call('admin/staff',auth='Bearer '+promoted['token'])[0]==200
assert call('admin/staff/'+created['id']+'/role',dict(role='OPERATOR'),adm)[0]==200
assert call('admin/staff/'+created['id']+'/active',dict(active=False),adm)[0]==200
# logout revokes the session
assert call('staff/auth/logout',{},adm)[0]==204 and call('staff/auth/me',auth=adm)[0]==401
print('staff flow ok')
