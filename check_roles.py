"""四角色隔离及办会申请全流程回归；仅清理本轮随机测试数据。先启动后端。"""
import concurrent.futures
import datetime as dt
import http.cookiejar
import json
import os
from pathlib import Path
import subprocess
import threading
import time
import urllib.error
import urllib.request

ROOT=Path(__file__).parent
MYSQL=os.getenv('MYSQL_BIN',r'D:\mysql\mysql-9.7.0-winx64\bin\mysql.exe')
BASE=os.getenv('API_URL','http://localhost:8080/api')
count=0
def sql(query):
    return subprocess.check_output([MYSQL,'-u','root','--default-character-set=utf8mb4','westlake_sword','-N','-e',query],encoding='utf-8').strip()
def client():
    return urllib.request.build_opener(urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))
def call(c,path,method='GET',data=None,status=200,raw=False,content_type='application/json'):
    global count
    body=data if isinstance(data,bytes) else None if data is None else json.dumps(data).encode()
    req=urllib.request.Request(BASE+path,data=body,method=method,headers={'Content-Type':content_type,'X-App-Request':'1'})
    try:
        with c.open(req,timeout=15) as res: code,result=res.status,res.read()
    except urllib.error.HTTPError as e: code,result=e.code,e.read()
    assert code==status,(path,code,status,result.decode(errors='replace')[:600])
    count+=1
    return result if raw else json.loads(result) if result else {}
def upload(c,rid,name,status=200):
    boundary='RoleFileBoundary'
    body=(f'--{boundary}\r\nContent-Disposition: form-data; name="file"; filename="{name}"\r\nContent-Type: text/plain\r\n\r\nExhibitor QA attachment\r\n--{boundary}--\r\n').encode()
    call(c,f'/requests/{rid}/files','POST',body,status,content_type='multipart/form-data; boundary='+boundary)

stamp='qr_'+str(time.time_ns())[-13:]
names=[stamp+str(i) for i in range(5)]
ids=[];request_ids=[];event_ids=[];paths=[]
a,e,other,s,u=[client() for _ in names]
password='TestPass123!'
now=dt.datetime.now().replace(microsecond=0)
form=dict(title=stamp,place='QA venue',starts=(now+dt.timedelta(days=2)).isoformat(),ends=(now+dt.timedelta(days=2,hours=4)).isoformat(),capacity=80,contact='QA contact',content='QA conference needs and files')
try:
    call(client(),'/events',status=401)
    call(client(),'/meetings',status=401)
    for cli,name,role in zip([a,e,other,s,u],names,['ADMIN','EXHIBITOR','EXHIBITOR','STAFF','USER']):
        call(cli,'/register','POST',dict(name=name,password=password))
        uid=int(sql(f"SELECT id FROM users WHERE name='{name}'"));ids.append(uid)
        sql(f"UPDATE users SET role='{role}' WHERE id={uid}")
        call(cli,'/login','POST',dict(name=name,password=password))
    for cli in [e,other,s,u]:
        call(cli,'/admin/events',status=403)
        call(cli,'/review/posts',status=403)
        call(cli,'/admin/stats',status=403)
    for cli in [s,u]:call(cli,'/requests',status=403)
    call(e,'/joins',status=403)
    call(s,'/posts/mine',status=403)
    call(s,'/profile',status=403)
    call(a,f'/admin/users/{ids[1]}/role','PUT',{'role':'OPS'},400)
    call(a,f'/admin/users/{ids[1]}/role','PUT',{'role':'REVIEWER'},400)
    call(a,'/admin/perms/STAFF','PUT',{'codes':['event']},400)
    call(a,'/admin/perms/USER','PUT',{'codes':['review']},400)
    call(e,'/requests','POST',{**form,'ends':form['starts']},400)
    call(a,'/requests','POST',form,400)
    rid=call(e,'/requests','POST',form)['id'];request_ids.append(rid)
    assert any(r['id']==rid and r['status']=='DRAFT' for r in call(e,'/requests'))
    assert not any(r['id']==rid for r in call(other,'/requests'))
    call(other,f'/requests/{rid}','PUT',form,403)
    call(other,f'/requests/{rid}/files',status=403)
    call(s,f'/requests/{rid}/files',status=403)
    upload(e,rid,'bad.exe',400)
    upload(e,rid,'../bad.txt',400)
    upload(e,rid,'agenda.txt')
    attachment=call(e,f'/requests/{rid}/files')[0]
    paths.append(sql(f"SELECT path FROM request_files WHERE id={attachment['id']}"))
    assert call(a,f"/requests/{rid}/files/{attachment['id']}/download",raw=True)==b'Exhibitor QA attachment'
    call(other,f"/requests/{rid}/files/{attachment['id']}/download",status=403)
    call(e,f'/requests/{rid}/review','POST',{'pass':True},403)
    call(a,f'/requests/{rid}/review','POST',{'pass':True},400)
    call(e,f'/requests/{rid}/submit','POST')
    call(e,f'/requests/{rid}','PUT',form,400)
    upload(e,rid,'late.txt',400)
    call(e,f"/requests/{rid}/files/{attachment['id']}",'DELETE',status=400)
    call(a,f'/requests/{rid}/review','POST',{'pass':False,'reason':''},400)
    call(a,f'/requests/{rid}/review','POST',{'pass':False,'reason':'QA missing details'})
    call(e,f'/requests/{rid}','PUT',{**form,'content':'QA supplemented needs'})
    assert next(r for r in call(e,'/requests') if r['id']==rid)['status']=='DRAFT'
    call(e,f'/requests/{rid}/submit','POST')
    formal={**form,'status':'CLOSED','liveUrl':'','replayUrl':''}
    call(a,f'/requests/{rid}/create','POST',formal,400)
    call(a,f'/requests/{rid}/review','POST',{'pass':True})
    call(e,f'/requests/{rid}','PUT',form,400)
    call(a,f'/requests/{rid}/review','POST',{'pass':True},400)
    call(e,f'/requests/{rid}/create','POST',formal,403)
    call(a,f'/requests/{rid}/create','POST',{**formal,'capacity':0},400)
    assert next(r for r in call(e,'/requests') if r['id']==rid)['status']=='APPROVED'

    # 审核和建会分开；并发点击也只能创建一场活动。
    barrier=threading.Barrier(2)
    def create():
        cli=client();call(cli,'/login','POST',dict(name=names[0],password=password));barrier.wait(timeout=10)
        try:
            result=call(cli,f'/requests/{rid}/create','POST',{**formal,'title':stamp+' confirmed'})
            event_ids.append(result['eventId']);return True
        except AssertionError as err:
            assert err.args[0][1]==400,err
            return False
    with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool: assert sum(pool.map(lambda _:create(),range(2)))==1
    eid=event_ids[0]
    r=next(r for r in call(e,'/requests') if r['id']==rid)
    assert r['status']=='CREATED' and r['event_id']==eid
    detail=call(a,f'/meetings/{eid}')
    assert detail['status']=='CLOSED' and detail['title']==stamp+' confirmed'
    assert len(detail['files'])==1 and len(detail['request'])==1
    fid=detail['files'][0]['id']
    assert call(a,f'/files/{fid}/download',raw=True)==b'Exhibitor QA attachment'
    staff_detail=call(s,f'/meetings/{eid}')
    assert 'files' not in staff_detail and 'request' not in staff_detail and 'posts' not in staff_detail
    call(u,f'/meetings/{eid}',status=403)
    call(e,f'/meetings/{eid}',status=403)
    call(u,f'/events/{eid}',status=404)
    call(u,f'/files/{fid}/download',status=403)
    call(s,f'/events/{eid}/join','POST',status=403)
    call(e,f'/events/{eid}/join','POST',status=403)
    call(a,f'/events/{eid}/join','POST',status=403)
    call(a,f'/admin/events/{eid}','PUT',{**formal,'title':stamp+' published','status':'OPEN'})
    assert call(u,f'/events/{eid}')['title']==stamp+' published'
    post=dict(eventId=eid,kind='EXHIBIT',title='QA exhibit',body='QA original',url='',image='',product='QA product')
    call(e,'/posts','POST',post)
    pid=next(p['id'] for p in call(e,'/posts/mine') if p['event_id']==eid)
    call(other,f'/posts/{pid}','PUT',post,403)
    call(a,f'/posts/{pid}','PUT',{**post,'body':'QA admin correction'})
    call(a,f'/review/posts/{pid}','POST',{'pass':True})
    assert call(u,f'/events/{eid}/posts')[0]['body']=='QA admin correction'
    call(u,f'/events/{eid}/join','POST',{'channel':'direct'})
    jid=next(j['id'] for j in call(u,'/joins') if j['event_id']==eid)
    call(u,f'/events/{eid}/modules/ai',status=403)
    call(a,f'/admin/joins/{jid}','POST',{'pass':True})
    for module in ['ai','map','topic']:assert call(u,f'/events/{eid}/modules/{module}')['enabled'] is False
    assert call(u,f'/files/{fid}/download',raw=True)==b'Exhibitor QA attachment'
    assert int(sql("SELECT COUNT(*) FROM users WHERE role IN ('OPS','REVIEWER')"))==0
    call(e,'/logout','POST');call(e,'/requests',status=401)
    print(f'PASS: {count} role/application HTTP assertions, atomic creation, attachment ownership, review state machine and four-role migration.')
finally:
    if request_ids:
        r=','.join(map(str,request_ids))
        sql(f'DELETE FROM request_files WHERE request_id IN ({r}); DELETE FROM requests WHERE id IN ({r});')
    if event_ids:
        eids=','.join(map(str,event_ids))
        sql(f'DELETE FROM posts WHERE event_id IN ({eids}); DELETE FROM join_info WHERE join_id IN (SELECT id FROM joins WHERE event_id IN ({eids})); DELETE FROM joins WHERE event_id IN ({eids}); DELETE FROM files WHERE event_id IN ({eids}); DELETE FROM event_info WHERE event_id IN ({eids}); DELETE FROM events WHERE id IN ({eids});')
    if ids:
        uids=','.join(map(str,ids))
        sql(f'DELETE FROM logs WHERE user_id IN ({uids}); DELETE FROM notes WHERE user_id IN ({uids}); DELETE FROM users WHERE id IN ({uids});')
    for key in paths:
        path=(ROOT/'uploads'/key).resolve()
        if path.parent==(ROOT/'uploads').resolve():path.unlink(missing_ok=True)
