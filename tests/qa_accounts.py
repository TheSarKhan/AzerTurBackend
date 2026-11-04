"""Opt-in synthetic accounts on a loopback integration database only. Never used by app code."""
import functools,json,os,pathlib,subprocess,urllib.parse,uuid,re

def command():
 if os.environ.get('ALLOW_QA_ACCOUNT_FIXTURES')!='1':
  raise RuntimeError('Synthetic account fixture requires ALLOW_QA_ACCOUNT_FIXTURES=1')
 if urllib.parse.urlparse(os.environ.get('API_ORIGIN','http://127.0.0.1:8080')).hostname not in ('localhost','127.0.0.1'):
  raise RuntimeError('Account fixtures only support a loopback integration API')
 if os.environ.get('CI')=='true':
  containers=subprocess.check_output(['docker','ps','--filter','ancestor=postgres:17-alpine','--format','{{.ID}}']).decode().splitlines()
  if len(containers)!=1:raise RuntimeError('Expected one CI fixture PostgreSQL')
  return ['docker','exec','-i',containers[0],'psql','-U','azertur','-d','azertur','-v','ON_ERROR_STOP=1','-At']
 database=os.environ.get('QA_DATABASE_NAME','azertur')
 if not re.fullmatch(r'azertur(?:_qa_[a-z0-9_]+|_documents_qa_[a-z0-9_]+)?',database):raise RuntimeError('Unsupported fixture database')
 return ['docker','compose','exec','-T','postgres','psql','-U','azertur','-d',database,'-v','ON_ERROR_STOP=1','-At']
def sql(statement):
 return subprocess.run(command(),input=statement.encode(),stdout=subprocess.PIPE,stderr=subprocess.PIPE,check=True).stdout.decode().strip()
def verified(call):
 @functools.wraps(call)
 def wrapped(path,body=None,*args,**kwargs):
  result=call(path,body,*args,**kwargs)
  if path=='auth/register' and result[0]==201:
   if not str(body.get('email','')).endswith('@example.com'):raise RuntimeError('Only synthetic example.com fixture accounts allowed')
   cid=str(uuid.UUID(result[1]['customer']['id']))
   sql("UPDATE customers SET email_verified_at=now() WHERE id='%s' AND email LIKE '%%@example.com';"%cid)
  return result
 return wrapped
