#!/usr/bin/env python3
"""Real HTTP reserve+claim capacity probe; disposable, run-owned fixtures in existing local DB."""
import argparse, asyncio, base64, hashlib, hmac, json, os, re, secrets, subprocess, time, uuid
from pathlib import Path
import aiohttp
import pymysql


def jwt(uid, secret):
    enc=lambda b: base64.urlsafe_b64encode(b).rstrip(b'=')
    data=enc(b'{"alg":"HS256"}')+b'.'+enc(json.dumps({'userId':uid,'exp':int(time.time())+3600}).encode())
    return (data+b'.'+enc(hmac.new(secret.encode(),data,hashlib.sha256).digest())).decode()


def sql(c,s,p=()):
    with c.cursor() as cur:
        cur.execute(s,p)
        return cur.fetchall()


def percentiles(values):
    a=sorted(values)
    return {'count':len(a), 'mean_ms':sum(a)/len(a) if a else None,
            **{f'p{q}_ms':a[min(len(a)-1,int(len(a)*q/100))] if a else None for q in (50,95,99)}}


async def measure(args, ids, secret, run_id):
    base=f'http://127.0.0.1:{args.port}'
    report={'concurrency':args.concurrency,'duration_target_s':args.seconds,'scenario':'two_hot_coupons_reserve_then_claim',
            'request_prefix':run_id,'coupon_ids':ids,'samples':[],'errors':[],
            'pids':{'app':args.pid,'mysql':args.mysql_pid,'client':os.getpid()}}
    lat={'reserve':[],'claim':[],'cycle':[]}
    completed=0; started_count=0; http_ok=0; reserve_ok=0
    metric_uid=secrets.randbelow(10**9)+3_000_000_000
    timeout=aiohttp.ClientTimeout(total=40)
    async with aiohttp.ClientSession(timeout=timeout,connector=aiohttp.TCPConnector(limit=args.concurrency+4)) as session:
        metric_sequence=0
        async def metrics():
            nonlocal metric_sequence
            metric_sequence+=1
            async with session.get(base+'/user/paid-coupon/benchmark/metrics',headers={'authentication':jwt(metric_uid-metric_sequence,secret)}) as r:
                payload=await r.json()
                if r.status!=200 or payload.get('code')!=1: raise RuntimeError('metrics unavailable '+str(payload))
                return payload['data']
        report['metrics_before']=await metrics()
        start=time.perf_counter(); stop=start+args.seconds
        async def worker():
            nonlocal completed,started_count,http_ok,reserve_ok
            while time.perf_counter()<stop:
                seq=started_count; started_count+=1
                uid=metric_uid+seq+1
                rid=f'{run_id}-{seq}'
                headers={'authentication':jwt(uid,secret)}
                begin=time.perf_counter()
                try:
                    for op,path,body in [('reserve','/user/paid-coupon/reservations',{'requestId':rid,'items':[{'couponId':v,'quantity':1} for v in ids]}),
                                          ('claim',f'/user/paid-coupon/demo/reservations/{rid}/claim',None)]:
                        t=time.perf_counter()
                        async with session.post(base+path,headers=headers,json=body) as r:
                            raw=await r.text()
                            latency=(time.perf_counter()-t)*1000
                            lat[op].append(latency)
                            if r.status==200: http_ok+=1
                            payload=json.loads(raw) if raw else {}
                            if r.status!=200 or payload.get('code')!=1:
                                raise RuntimeError(f'{op} HTTP={r.status} body={raw[:180]}')
                            expected='RESERVED' if op=='reserve' else 'CLAIMED'
                            if payload['data']['state']!=expected: raise RuntimeError('unexpected state')
                            if op=='reserve': reserve_ok+=1
                    completed+=1
                    lat['cycle'].append((time.perf_counter()-begin)*1000)
                except Exception as e:
                    if len(report['errors'])<30: report['errors'].append(str(e))
        running=True
        async def sampler():
            while running:
                m=await metrics()
                cpu=''
                if args.pid:
                    proc=await asyncio.create_subprocess_exec('ps','-p',f'{args.pid},{args.mysql_pid},{os.getpid()}','-o','pid=,%cpu=,rss=',stdout=asyncio.subprocess.PIPE)
                    out,_=await proc.communicate(); cpu=out.decode().strip()
                report['samples'].append({'elapsed':time.perf_counter()-start,'pools':m.get('pools'),'process_cpu_rss':cpu})
                await asyncio.sleep(1)
        task=asyncio.create_task(sampler())
        await asyncio.gather(*(worker() for _ in range(args.concurrency)))
        elapsed=time.perf_counter()-start
        running=False
        await task
        report.update({'elapsed_s':elapsed,'started_cycles':started_count,'completed_cycles':completed,'reserve_success':reserve_ok,
                       'http_200':http_ok,'completed_cycles_per_s':completed/elapsed,'http_requests_per_s':sum(len(v) for k,v in lat.items() if k!='cycle')/elapsed,
                       'latency':{k:percentiles(v) for k,v in lat.items()},'metrics_after':await metrics()})
    return report


def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--port',type=int,default=8081);p.add_argument('--concurrency',type=int,required=True)
    p.add_argument('--seconds',type=int,default=12);p.add_argument('--output',type=Path,required=True)
    p.add_argument('--pid',type=int);p.add_argument('--mysql-pid',type=int,default=80165)
    args=p.parse_args()
    if args.output.exists() or not 1<=args.concurrency<=512 or not 1<=args.seconds<=120: p.error('output exists or invalid limits')
    config=Path('sky-server/src/main/resources/application-dev.yml').read_text()
    match=re.search(r'jdbcUrl: jdbc:mysql://localhost:3306/sky_order_0[^\n]*\n\s+username: ([^\n]+)\n\s+password: ([^\n]+)',config)
    if not match: raise RuntimeError('local DB configuration missing')
    main_config=Path('sky-server/src/main/resources/application.yml').read_text()
    secret=re.search(r'user-secret-key:\s*([^\s#]+)',main_config).group(1).strip('\"\'')
    c=pymysql.connect(host='127.0.0.1',user=match.group(1).strip(),password=match.group(2).strip().strip('\"\''),database='sky_order_0',autocommit=True)
    run_id='cap-'+uuid.uuid4().hex[:16]
    first=6_000_000_000_000_000_000+secrets.randbelow(10**10)*2; ids=[first,first+1]
    report={'run_id':run_id,'coupon_ids':ids}; owned=False
    try:
        for cid in ids:
            for table in ('paid_coupon_inventory','paid_coupon_available_unit','paid_coupon_reserved_unit'):
                assert not sql(c,'SELECT coupon_id FROM '+table+' WHERE coupon_id=%s LIMIT 1',(cid,))
        c.begin()
        for cid in ids:
            sql(c,'INSERT INTO paid_coupon_inventory VALUES (%s,10000000,10000000,1001)',(cid,))
            with c.cursor() as cur: cur.executemany('INSERT INTO paid_coupon_available_unit VALUES (%s,%s)',[(cid,i) for i in range(1,1001)])
        c.commit(); owned=True
        report=asyncio.run(measure(args,ids,secret,run_id))
        c.begin()
        states=dict(sql(c,'SELECT state,COUNT(*) FROM paid_coupon_reservation_batch WHERE request_id LIKE %s GROUP BY state',(run_id+'-%',)))
        ledger=[]
        claimed=states.get('CLAIMED',0)
        for cid in ids:
            inv_remaining=sql(c,'SELECT remaining FROM paid_coupon_inventory WHERE coupon_id=%s',(cid,))[0][0]
            available=sql(c,'SELECT COUNT(*) FROM paid_coupon_available_unit WHERE coupon_id=%s',(cid,))[0][0]
            reserved=sql(c,'SELECT COUNT(*) FROM paid_coupon_reserved_unit WHERE coupon_id=%s',(cid,))[0][0]
            remaining=inv_remaining
            sold=10000000-inv_remaining
            ledger.append({'coupon':cid,'remaining':remaining,'available':available,'reserved':reserved,'sold':sold,
                           'inv_remaining':inv_remaining,
                           'ok':sold==claimed and 0<=available+reserved<=remaining<=10000000 and available<=1000})
        c.commit()
        report['states']=states;report['ledger']=ledger
        report['correct']=all(x['ok'] for x in ledger) and states.get('CLAIMED',0)==report['completed_cycles']
    finally:
        if owned:
            c.rollback();c.begin()
            for cid in ids:
                sql(c,'DELETE FROM paid_coupon_reserved_unit WHERE coupon_id=%s',(cid,))
                sql(c,'DELETE FROM paid_coupon_available_unit WHERE coupon_id=%s',(cid,))
            sql(c,'DELETE FROM paid_coupon_reservation_batch WHERE request_id LIKE %s',(run_id+'-%',))
            for cid in ids: sql(c,'DELETE FROM paid_coupon_inventory WHERE coupon_id=%s',(cid,))
            c.commit();report['fixture_cleaned']=True
        c.close()
        args.output.parent.mkdir(parents=True,exist_ok=True)
        args.output.write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n')
    print(json.dumps({k:report.get(k) for k in ('concurrency','completed_cycles_per_s','http_requests_per_s','started_cycles','completed_cycles','correct','errors')},ensure_ascii=False))
    print(json.dumps(report.get('latency',{})))

if __name__=='__main__': main()
