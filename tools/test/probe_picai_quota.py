#!/usr/bin/env python3
"""picai 额度机制对照实验（2026-10-08 小甜桃）
实验组：
  A. 同会话(同CookieJar+同XFF) 连续两次 → 看额度是否递减（证明按会话计数）
  B. 全新会话(新CookieJar+新XFF)       → 看额度是否重置（"无限"的核心）
  C. 新CookieJar + 复用A的XFF          → 判断额度键到底是Cookie还是XFF头
最后轮询 A 的第一个任务到完成，验证真的出图。
"""
import json, random, time, urllib.request, urllib.error
from http.cookiejar import CookieJar

BASE = "https://picai.com"
UA = ("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
      "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36")

def rand_ip():
    while True:
        a = random.randint(1, 223)
        if a in (0, 10, 127):
            continue
        b = random.randint(0, 255)
        if a == 172 and 16 <= b <= 31:
            continue
        if a == 192 and b in (0, 168):
            continue
        return f"{a}.{b}.{random.randint(0, 255)}.{random.randint(1, 254)}"

class Sess:
    def __init__(self, ip=None):
        self.cj = CookieJar()
        self.ip = ip or rand_ip()
        self.op = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(self.cj))

    def run(self, prompt):
        body = {"modelId": "gpt-image-2",
                "input": {"prompt": prompt, "image_size": "square_hd",
                          "aspect_ratio": "1:1", "num_images": 1,
                          "width": 1024, "height": 1024,
                          "num_inference_steps": 28},
                "libraryMeta": {"prompt": prompt}}
        req = urllib.request.Request(BASE + "/api/fal/run",
                                     data=json.dumps(body).encode(), method="POST",
                                     headers={"User-Agent": UA, "Accept": "application/json",
                                              "Content-Type": "application/json",
                                              "X-Forwarded-For": self.ip, "X-Real-IP": self.ip})
        try:
            with self.op.open(req, timeout=60) as r:
                return json.loads(r.read().decode())
        except urllib.error.HTTPError as e:
            return {"__http_error": e.code, "__body": e.read().decode("utf-8", "replace")[:200]}

    def poll(self, job_id):
        req = urllib.request.Request(f"{BASE}/api/fal/jobs/{job_id}",
                                     headers={"User-Agent": UA, "Accept": "application/json",
                                              "X-Forwarded-For": self.ip, "X-Real-IP": self.ip})
        try:
            with self.op.open(req, timeout=30) as r:
                return json.loads(r.read().decode())
        except urllib.error.HTTPError as e:
            return {"__http_error": e.code, "__body": e.read().decode("utf-8", "replace")[:200]}

def show(tag, sess, j):
    if "__http_error" in j:
        print(f"[{tag}] HTTP {j['__http_error']}: {j['__body']}", flush=True)
        return
    keys = {k: j.get(k) for k in ("jobId", "credits", "dailyRemaining", "dailyLimit") if k in j}
    print(f"[{tag}] ip={sess.ip} -> {json.dumps(keys, ensure_ascii=False)}", flush=True)
    if not any(k in j for k in ("credits", "dailyRemaining", "dailyLimit")):
        print(f"[{tag}]   (无额度字段, 响应前300: {json.dumps(j, ensure_ascii=False)[:300]})", flush=True)

PROMPT = "a small red apple on a white table"
t0 = time.time()

print("=== A. 同一会话连打两枪（同Cookie+同XFF） ===", flush=True)
sA = Sess()
r1 = sA.run(PROMPT); show("A1", sA, r1)
r2 = sA.run(PROMPT); show("A2(同会话第2次)", sA, r2)

print("=== B. 全新会话（新Cookie+新XFF） ===", flush=True)
sB = Sess()
r3 = sB.run(PROMPT); show("B1", sB, r3)

print("=== C. 新Cookie + 复用A的XFF ===", flush=True)
sC = Sess(ip=sA.ip)
r4 = sC.run(PROMPT); show("C1", sC, r4)

print(f"=== 轮询 A1 任务到终态（验证真出图） ===", flush=True)
job1 = (r1 or {}).get("jobId")
if job1:
    for i in range(30):
        time.sleep(3)
        st = sA.poll(job1)
        if "__http_error" in st:
            print(f"poll#{i} HTTP {st['__http_error']}", flush=True); continue
        s = st.get("status")
        if s == "succeeded":
            urls = [o.get("url") for o in st.get("outputs", []) if o.get("kind") == "image"]
            print(f"succeeded, 用时{round(time.time()-t0)}s, 图片URL: {urls}", flush=True)
            break
        if s == "failed":
            print(f"failed: {json.dumps(st, ensure_ascii=False)[:200]}", flush=True); break
    else:
        print("轮询90s未终态（任务可能仍在跑，不影响额度结论）", flush=True)
else:
    print("A1 未返回 jobId，跳过轮询", flush=True)

print(f"总用时 {round(time.time()-t0)}s", flush=True)
