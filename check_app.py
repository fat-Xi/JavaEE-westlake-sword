"""真实 MySQL + HTTP 回归测试。先启动后端，仅清理本脚本创建的 qa 记录。"""
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

ROOT = Path(__file__).parent
MYSQL = os.getenv('MYSQL_BIN', r'D:\mysql\mysql-9.7.0-winx64\bin\mysql.exe')
BASE = os.getenv('API_URL', 'http://localhost:8080/api')
COUNT = 0

def sql(query):
    return subprocess.check_output([MYSQL, '-u', 'root', '--default-character-set=utf8mb4', 'westlake_sword', '-N', '-e', query], encoding='utf-8').strip()

def client():
    return urllib.request.build_opener(urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))

def call(c, path, method='GET', data=None, status=200, header=True, raw=False, content_type='application/json'):
    global COUNT
    headers = {'Content-Type': content_type}
    if header:
        headers['X-App-Request'] = '1'
    body = data if isinstance(data, bytes) else None if data is None else json.dumps(data).encode()
    req = urllib.request.Request(BASE + path, data=body, headers=headers, method=method)
    try:
        with c.open(req, timeout=15) as res:
            code, result = res.status, res.read()
    except urllib.error.HTTPError as err:
        code, result = err.code, err.read()
    assert code == status, (method, path, code, status, result.decode(errors='replace')[:1000])
    COUNT += 1
    return result if raw else json.loads(result) if result else {}

def upload(c, eid, name, status=200):
    boundary = 'WestlakeTestBoundary'
    body = (f'--{boundary}\r\nContent-Disposition: form-data; name="file"; filename="{name}"\r\nContent-Type: text/plain\r\n\r\nQA download content\r\n--{boundary}--\r\n').encode()
    return call(c, f'/events/{eid}/files', 'POST', body, status, content_type='multipart/form-data; boundary=' + boundary)

stamp = 'qa_' + str(time.time_ns())[-13:]
names = [stamp + suffix for suffix in ['a', 'b', 'c', 'd']]
ids, event_ids, file_paths = [], [], []
saved_perms = None
a, b, c, d = [client() for _ in names]
password = 'TestPass123!'
now = dt.datetime.now().replace(microsecond=0)
fmt = lambda value: value.isoformat()

def event(title, capacity=10, offset=0):
    data = dict(title=title, place='QA room', starts=fmt(now + dt.timedelta(hours=offset-1)), ends=fmt(now+dt.timedelta(hours=offset+8)), capacity=capacity, content='QA activity', status='OPEN', liveUrl='', replayUrl='')
    call(a, '/admin/events', 'POST', data)
    eid = next(row['id'] for row in call(a, '/admin/events') if row['title'] == title)
    event_ids.append(eid)
    return eid, data

def join(c, eid, channel='direct'):
    call(c, f'/events/{eid}/join', 'POST', {'channel': channel})
    return next(row['id'] for row in call(c, '/joins') if row['event_id'] == eid)

def approve(jid):
    call(a, f'/admin/joins/{jid}', 'POST', {'pass': True})

def talk(eid, title, room, start=1, end=2, status=200):
    data = dict(eventId=eid,title=title,speaker='QA speaker',room=room,starts=fmt(now+dt.timedelta(hours=start)),ends=fmt(now+dt.timedelta(hours=end)),tags='cloud,data')
    call(a, '/talks', 'POST', data, status)
    if status == 200:
        return next(row['id'] for row in call(a, f'/events/{eid}/talks') if row['title']==title)

try:
    for cli, name in zip([a,b,c,d],names):
        call(cli,'/register','POST',dict(name=name,password=password))
        ids.append(int(sql(f"SELECT id FROM users WHERE name='{name}'")))
    sql(f"UPDATE users SET role='ADMIN' WHERE id={ids[0]}")
    call(b,'/register','POST',dict(name=names[1],password=password),409)
    call(b,'/login','POST',dict(name=names[1],password='wrong'),401)
    for cli, name in zip([a,b,c,d],names):
        call(cli,'/login','POST',dict(name=name,password=password))
    call(client(),'/admin/stats',status=401)
    call(b,'/admin/stats',status=403)
    call(b,'/logout','POST',status=403,header=False)
    call(a,f'/admin/users/{ids[0]}/role','PUT',{'role':'USER'},400)
    call(a,f'/admin/users/{ids[3]}/role','PUT',{'role':'STAFF'})
    assert call(d,'/me')['role']=='STAFF'
    call(d,'/admin/users',status=403)

    eid, data = event(stamp)
    eid2, data2 = event(stamp+'other')
    future, _ = event(stamp+'future', offset=48)
    for path in ['plans','questions','ticket','recommend','modules/ai']:
        call(b,f'/events/{eid}/{path}',status=403)
    jb = join(b,eid,'invite')
    call(b,f'/events/{eid}/join','POST',{'channel':'share'},400)
    call(b,f'/events/{eid}/access',status=403)
    call(b,f'/admin/joins/{jb}','POST',{'pass':True},403)
    approve(jb)
    call(b,f'/events/{eid}/access')
    call(a,f'/admin/joins/{jb}','POST',{'pass':True},400)
    jc = join(c,eid)
    call(a,f'/admin/joins/{jc}','POST',{'pass':False,'reason':''},400)
    call(a,f'/admin/joins/{jc}','POST',{'pass':False,'reason':'QA reject'})
    call(c,f'/events/{eid}/access',status=403)
    join(c,eid);approve(jc)
    call(b,f'/events/{eid}/sub','POST')
    call(b,f'/events/{eid}/sub','POST')
    assert call(b,'/subs').count(eid)==1
    call(a,f'/admin/events/{eid}','PUT',{**data,'capacity':1},400)
    call(a,f'/admin/events/{eid}','PUT',{**data,'content':'QA changed'})
    assert any('QA changed' not in row['body'] and stamp in row['body'] for row in call(b,'/notes'))
    call(b,'/profile','PUT',dict(nick='QA user',phone='',company='QA',tags='cloud'))
    assert call(b,'/profile')['tags']=='cloud'
    t1=talk(eid,'QA one','A')
    t2=talk(eid,'QA conflict','B',1.5,2.5)
    t3=talk(eid,'QA adjacent','A',2,3)
    talk(eid,'QA room conflict','A',1.5,2.5,400)
    talk(eid,'QA outside','C',9,10,400)
    call(b,f'/talks/{t1}/plan','POST')
    call(b,f'/talks/{t1}/plan','POST',status=400)
    call(b,f'/talks/{t2}/plan','POST',status=400)
    call(b,f'/talks/{t3}/plan','POST')
    call(a,f'/talks/{t1}','DELETE',status=400)
    assert len(call(b,f'/events/{eid}/plans'))==2
    assert call(b,f'/events/{eid}/recommend')[0]['score']==1
    t4=talk(eid2,'QA cross event','A')
    call(b,f'/talks/{t4}/plan','POST',status=403)
    approve(join(b,eid2))
    call(b,f'/talks/{t4}/plan','POST',status=400)
    for module in ['map','ai','topic']:
        assert call(b,f'/events/{eid}/modules/{module}')['enabled'] is False

    # 待审内容不可公开；修改后必须重新审核，不能修改他人内容。
    post=dict(eventId=eid,kind='COMMENT',title='QA comment',body='QA text',url='',image='',product='')
    call(b,'/posts','POST',{**post,'kind':'NEWS'},403)
    call(b,'/posts','POST',{**post,'url':'javascript:alert(1)'},400)
    call(b,'/posts','POST',post)
    pid=next(p['id'] for p in call(b,'/posts/mine') if p['title']==post['title'])
    assert not call(b,f'/events/{eid}/posts')
    call(c,f'/posts/{pid}','PUT',post,403)
    call(b,f'/posts/{pid}/like','POST',status=404)
    call(a,f'/review/posts/{pid}','POST',{'pass':True})
    for _ in range(2): call(b,f'/posts/{pid}/like','POST')
    assert call(b,f'/events/{eid}/posts')[0]['likes']==1
    call(b,f'/posts/{pid}','PUT',{**post,'body':'QA edit'})
    assert not call(b,f'/events/{eid}/posts')
    call(a,f'/review/posts/{pid}','POST',{'pass':True})
    call(b,f'/posts/{pid}/like','DELETE')
    assert call(b,f'/events/{eid}/posts')[0]['likes']==0
    for kind in ['NEWS','RESULT','EXHIBIT','WALL']:
        cli=b if kind=='WALL' else a
        call(cli,'/posts','POST',{**post,'kind':kind,'title':'QA '+kind})

    call(b,'/questions','POST',dict(eventId=eid,body='QA question'))
    qid=next(q['id'] for q in call(a,'/review/questions') if q['body']=='QA question' and q['event_id']==eid)
    call(b,f'/questions/{qid}/vote','POST',status=404)
    assert not call(c,f'/events/{eid}/questions')
    call(a,f'/review/questions/{qid}','POST',{'pass':True})
    call(a,f'/review/questions/{qid}/answer','PUT',{'answer':'QA answer'})
    for _ in range(2):call(b,f'/questions/{qid}/vote','POST')
    assert call(c,f'/events/{eid}/questions')[0]['votes']==1
    call(b,f'/questions/{qid}/vote','DELETE')
    assert call(c,f'/events/{eid}/questions')[0]['votes']==0
    call(b,f'/questions/{qid}/vote','POST')

    upload(b,eid,'qa.txt',403)
    upload(a,eid,'qa.exe',400)
    upload(a,eid,'../qa.txt',400)
    upload(a,eid,'qa.txt')
    fid=call(b,f'/events/{eid}/files')[0]['id']
    file_paths.append(sql(f'SELECT path FROM files WHERE id={fid}'))
    call(d,f'/files/{fid}/download',status=403)
    assert call(b,f'/files/{fid}/download',raw=True)==b'QA download content'

    ticket=call(b,f'/events/{eid}/ticket')['token']
    assert call(b,f'/events/{eid}/ticket')['token']==ticket
    call(b,'/checkin','POST',dict(eventId=eid,token=ticket),403)
    call(d,'/checkin','POST',dict(eventId=eid2,token=ticket),404)
    call(d,'/checkin','POST',dict(eventId=eid,token='bad'),404)
    assert call(d,'/checkin','POST',dict(eventId=eid,token=ticket))['duplicate'] is False
    assert call(d,'/checkin','POST',dict(eventId=eid,token=ticket))['duplicate'] is True
    call(b,f'/events/{eid}/join','DELETE',status=400)
    stat=call(a,f'/admin/stats?eventId={eid}')
    assert stat['checkins']==1 and stat['approved']==2 and stat['votes']==1 and stat['joins']==2,stat
    assert len(call(d,f'/checkins?eventId={eid}'))==1
    approve(join(c,future))
    ft=call(c,f'/events/{future}/ticket')['token']
    call(d,'/checkin','POST',dict(eventId=future,token=ft),400)
    ct=call(c,f'/events/{eid}/ticket')['token']
    call(c,f'/talks/{t1}/plan','POST')
    call(c,f'/events/{eid}/join','DELETE')
    call(c,f'/events/{eid}/plans',status=403)
    call(d,'/checkin','POST',dict(eventId=eid,token=ct),404)
    join(c,eid,'share');approve(jc)
    assert next(j for j in call(c,'/joins') if j['id']==jc)['channel']=='direct'
    assert call(c,f'/events/{eid}/plans')==[]
    assert call(c,f'/events/{eid}/ticket')['token']!=ct

    # 同一用户并发收藏冲突议程，最终只能有一个成功。
    plan_barrier=threading.Barrier(2)
    def race_plan(tid):
        cli=client();call(cli,'/login','POST',dict(name=names[2],password=password))
        plan_barrier.wait(timeout=10)
        try:
            call(cli,f'/talks/{tid}/plan','POST');return True
        except AssertionError as err:
            assert err.args[0][2]==400,err
            return False
    for _ in range(3):
        with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:
            assert sum(pool.map(race_plan,[t1,t2]))==1
        assert len(call(c,f'/events/{eid}/plans'))==1
        call(c,f'/talks/{t1}/plan','DELETE')
        call(c,f'/talks/{t2}/plan','DELETE')

    # 并发争抢最后一个名额，只允许一条报名审核通过。
    cap, _=event(stamp+'capacity',capacity=1)
    cap_ids=[join(b,cap),join(c,cap)]
    join_barrier=threading.Barrier(2)
    def race(jid):
        cli=client();call(cli,'/login','POST',dict(name=names[0],password=password))
        join_barrier.wait(timeout=10)
        try:
            call(cli,f'/admin/joins/{jid}','POST',{'pass':True});return True
        except AssertionError as err:
            assert err.args[0][2]==400,err
            return False
    with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:
        assert sum(pool.map(race,cap_ids))==1
    assert call(a,f'/admin/stats?eventId={cap}')['approved']==1
    call(a,f'/admin/users/{ids[3]}/role','PUT',{'role':'USER'})
    call(d,f'/checkins?eventId={eid}',status=403)
    saved_perms=[p['code'] for p in call(a,'/admin/perms') if p['role']=='EXHIBITOR']
    call(a,f'/admin/users/{ids[3]}/role','PUT',{'role':'EXHIBITOR'})
    call(a,'/admin/perms/EXHIBITOR','PUT',{'codes':['exhibit','stats']},400)
    call(a,'/admin/perms/EXHIBITOR','PUT',{'codes':['exhibit','request']})
    call(d,'/requests')
    call(a,'/admin/perms/EXHIBITOR','PUT',{'codes':['exhibit']})
    call(d,'/admin/stats',status=403)
    call(d,'/requests',status=403)
    call(b,'/password','PUT',dict(oldPassword='bad',password='NewPass123!'),400)
    call(b,'/password','PUT',dict(oldPassword=password,password='NewPass123!'))
    call(b,'/me',status=401)
    call(b,'/login','POST',dict(name=names[1],password='NewPass123!'))
    call(b,'/notes/read','POST')
    assert all(n['seen'] for n in call(b,'/notes'))
    call(b,'/logout','POST');call(b,'/me',status=401)
    print(f'PASS: {COUNT} HTTP assertions plus state, role, concurrency and data invariants.')
finally:
    if saved_perms is not None:
        sql("DELETE FROM perms WHERE role='EXHIBITOR';" + ''.join("INSERT INTO perms(role,code) VALUES('EXHIBITOR','" + code + "');" for code in saved_perms))
    # 外键逆序清理，仅使用本轮记录的精确 ID，不清空用户现有数据。
    if event_ids:
        e=','.join(map(str,event_ids))
        sql(f'DELETE FROM votes WHERE question_id IN (SELECT id FROM questions WHERE event_id IN ({e})); DELETE FROM questions WHERE event_id IN ({e}); DELETE FROM likes WHERE post_id IN (SELECT id FROM posts WHERE event_id IN ({e})); DELETE FROM posts WHERE event_id IN ({e}); DELETE FROM plans WHERE talk_id IN (SELECT id FROM talks WHERE event_id IN ({e})); DELETE FROM talks WHERE event_id IN ({e}); DELETE FROM tickets WHERE join_id IN (SELECT id FROM joins WHERE event_id IN ({e})); DELETE FROM join_info WHERE join_id IN (SELECT id FROM joins WHERE event_id IN ({e})); DELETE FROM joins WHERE event_id IN ({e}); DELETE FROM subs WHERE event_id IN ({e}); DELETE FROM files WHERE event_id IN ({e}); DELETE FROM event_info WHERE event_id IN ({e}); DELETE FROM events WHERE id IN ({e});')
    if ids:
        u=','.join(map(str,ids))
        sql(f'DELETE FROM profiles WHERE user_id IN ({u}); DELETE FROM notes WHERE user_id IN ({u}); DELETE FROM logs WHERE user_id IN ({u}); DELETE FROM users WHERE id IN ({u});')
    for key in file_paths:
        target=(ROOT/'uploads'/key).resolve()
        if target.parent == (ROOT/'uploads').resolve(): target.unlink(missing_ok=True)
