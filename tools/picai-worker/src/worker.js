// picai-relay Worker —— 把 picai.com 的匿名 GPT Image 2 通道封装成 OpenAI 兼容接口
// 机制（2026-10-08 真机/实验双实锤）：
//   - 额度钥匙 = X-Forwarded-For 头（每个新 IP 首张免费；同 IP 二次 402）
//   - 轮询门票 = 提交响应的 set-cookie picai_sid（轮询必须携带，否则 forbidden）
//   - 因此每次生图：新随机 XFF + 捕获提交 Cookie + 轮询时回带
// 端点：
//   GET  /v1/models                    模型列表
//   POST /v1/images/generations        文生图（OpenAI 格式；内部提交+轮询到完成）
//   GET  /health                       健康检查
//   GET  /__probe                      [排障] XFF 透传与配额行为探针
// 鉴权：Authorization: Bearer <RELAY_KEY>（secrets）

const UPSTREAM = "https://picai.com";
const UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36";

const SIZE_MAP = {
  "1024x1024": ["square_hd", "1:1", 1024, 1024],
  "1024x1792": ["portrait_16_9", "9:16", 576, 1024],
  "1792x1024": ["landscape_16_9", "16:9", 1024, 576],
  "1152x864": ["landscape_4_3", "4:3", 1152, 864],
  "864x1152": ["portrait_4_3", "3:4", 864, 1152],
};

const SIZE_ALIASES = {
  "auto": "1024x1024",
  "1024x1536": "864x1152",
  "1536x1024": "1152x864",
  "512x512": "1024x1024",
  "256x256": "1024x1024",
};

const POLL_INTERVAL_MS = 3000;
const POLL_MAX = 40;            // 子请求预算：1 提交 + ≤40 轮询 < 50（free 计划单请求上限）
const POLL_TIMEOUT_MS = 150000;

function randIp() {
  while (true) {
    const a = 1 + Math.floor(Math.random() * 223);
    if (a === 10 || a === 127) continue;
    const b = Math.floor(Math.random() * 256);
    if (a === 172 && b >= 16 && b <= 31) continue;
    if (a === 192 && (b === 0 || b === 168)) continue;
    if (a === 169 && b === 254) continue;
    if (a === 100 && b >= 64 && b <= 127) continue;
    if (a === 198 && (b === 18 || b === 19)) continue;
    return `${a}.${b}.${Math.floor(Math.random() * 256)}.${1 + Math.floor(Math.random() * 254)}`;
  }
}

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

function json(obj, status = 200) {
  return new Response(JSON.stringify(obj), {
    status,
    headers: { "content-type": "application/json; charset=utf-8", "access-control-allow-origin": "*" },
  });
}

function authOk(request, env) {
  const h = request.headers.get("authorization") || "";
  const token = h.startsWith("Bearer ") ? h.slice(7) : (request.headers.get("x-api-key") || "");
  return token !== "" && token === env.RELAY_KEY;
}

function pickSize(size) {
  const s = SIZE_ALIASES[size] || size || "1024x1024";
  return SIZE_MAP[s] || SIZE_MAP["1024x1024"];
}

// 提交一次生图任务，返回 {ok, jobId, cookie, credits 信息或错误}
async function submit(prompt, sizeKey) {
  const [imageSize, ar, w, h] = pickSize(sizeKey);
  const ip = randIp();
  const r = await fetch(`${UPSTREAM}/api/fal/run`, {
    method: "POST",
    headers: {
      "user-agent": UA,
      "accept": "application/json",
      "content-type": "application/json",
      "x-forwarded-for": ip,
      "x-real-ip": ip,
    },
    body: JSON.stringify({
      modelId: "gpt-image-2",
      input: { prompt, image_size: imageSize, aspect_ratio: ar, num_images: 1, width: w, height: h, num_inference_steps: 28 },
      libraryMeta: { prompt },
    }),
  });
  const text = await r.text();
  const setCookie = r.headers.get("set-cookie") || "";
  const m = setCookie.match(/picai_sid=([^;]+)/);
  return { status: r.status, text, ip, sid: m ? m[1] : null };
}

// 用同一会话轮询任务到终态
async function poll(jobId, sid, ip) {
  const deadline = Date.now() + POLL_TIMEOUT_MS;
  for (let i = 0; i < POLL_MAX && Date.now() < deadline; i++) {
    await sleep(POLL_INTERVAL_MS);
    let r;
    try {
      r = await fetch(`${UPSTREAM}/api/fal/jobs/${jobId}`, {
        method: "GET",
        headers: {
          "user-agent": UA,
          "accept": "application/json",
          "x-forwarded-for": ip,
          "x-real-ip": ip,
          "cookie": `picai_sid=${sid}`,
        },
      });
    } catch (e) {
      continue; // 轮询抖动容忍
    }
    if (r.status === 403 || r.status === 402 || r.status === 429) {
      const t = await r.text();
      return { ok: false, status: r.status, body: t.slice(0, 200) };
    }
    if (r.status !== 200) continue;
    const st = await r.json();
    if (st.status === "succeeded") {
      const urls = (st.outputs || []).filter((o) => o.kind === "image").map((o) => o.url).filter(Boolean);
      if (urls.length) return { ok: true, urls };
      return { ok: false, status: 502, body: "succeeded but no outputs" };
    }
    if (st.status === "failed") {
      return { ok: false, status: 502, body: JSON.stringify(st).slice(0, 200) };
    }
  }
  return { ok: false, status: 504, body: "poll timeout" };
}

export default {
  async fetch(request, env) {
    const url = new URL(request.url);

    if (url.pathname === "/health") return json({ status: "ok", service: "picai-relay-worker" });

    if (url.pathname === "/v1/models") {
      if (!authOk(request, env)) return json({ error: { message: "invalid api key" } }, 401);
      return json({ object: "list", data: [{ id: "gpt-image-2", object: "model", owned_by: "picai" }] });
    }

    // [排障] XFF/配额行为探针：验证 Worker 出口能否携带伪造 XFF（决定"无限"是否成立）
    if (url.pathname === "/__probe") {
      if (!authOk(request, env)) return json({ error: "invalid api key" }, 401);
      const out = {};
      const empty = await fetch(`${UPSTREAM}/api/fal/run`, {
        method: "POST",
        headers: { "user-agent": UA, "content-type": "application/json", "x-forwarded-for": randIp(), "x-real-ip": randIp() },
        body: JSON.stringify({}),
      });
      out.emptyBodyStatus = empty.status; // 400=到达应用层(好)；403=出口被拒(坏)
      out.emptyBody = (await empty.text()).slice(0, 120);
      const s1 = await submit("connectivity probe A");
      out.submitA = { status: s1.status, hasSid: !!s1.sid, info: s1.text.slice(0, 160) };
      const s2 = await submit("connectivity probe B"); // 新 XFF 新会话：应再成功 → XFF 生效
      out.submitB = { status: s2.status, hasSid: !!s2.sid, info: s2.text.slice(0, 160) };
      const s3 = await submit("connectivity probe C"); // 复用 B 的 XFF：应 402 → 证明额度按 XFF 记
      out.submitC_reuseXFF = { status: s3.status, info: s3.text.slice(0, 160) };
      out.verdict = (s1.status === 200 && s2.status === 200 && s3.status === 402)
        ? "XFF 透传生效，无限机制成立 ✓"
        : "XFF 可能被 Cloudflare 剥离或上游策略变化，Worker 方案受限 ✗";
      return json(out);
    }

    if (url.pathname === "/v1/images/generations" && request.method === "POST") {
      if (!authOk(request, env)) return json({ error: { message: "invalid api key" } }, 401);
      let req;
      try { req = await request.json(); } catch { return json({ error: { message: "bad json" } }, 400); }
      const prompt = (req.prompt || "").trim();
      if (!prompt) return json({ error: { message: "prompt required" } }, 400);
      const size = req.size || "1024x1024";
      const t0 = Date.now();

      const s = await submit(prompt, size);
      if (s.status !== 200 || !s.sid) {
        return json({ error: { message: `picai submit failed: HTTP ${s.status} ${s.text.slice(0, 120)}` } }, 502);
      }
      let jobId;
      try { jobId = JSON.parse(s.text).jobId; } catch { jobId = null; }
      if (!jobId) return json({ error: { message: "picai submit no jobId" } }, 502);

      const p = await poll(jobId, s.sid, s.ip);
      if (!p.ok) {
        return json({ error: { message: `picai poll failed: HTTP ${p.status} ${p.body}` } }, 502);
      }
      return json({
        created: Math.floor(Date.now() / 1000),
        model: "gpt-image-2",
        elapsed_s: Math.round((Date.now() - t0) / 100) / 10,
        data: p.urls.map((u) => ({ url: u })),
      });
    }

    return json({ error: "not found" }, 404);
  },
};
