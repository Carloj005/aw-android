const MAX_BODY_BYTES = 8 * 1024;
const ALLOWED_KINDS = new Set(["bug", "feature"]);

function jsonResponse(payload, status = 200, extraHeaders = {}) {
  return new Response(JSON.stringify(payload), {
    status,
    headers: {
      "Content-Type": "application/json; charset=utf-8",
      "Cache-Control": "no-store",
      ...extraHeaders,
    },
  });
}

function sanitizeLine(value, maxLen) {
  if (typeof value !== "string") return "";
  return value.replace(/[\r\n\u0000-\u001F\u007F]+/g, " ").trim().slice(0, maxLen);
}

function sanitizeMultiline(value, maxLen) {
  if (typeof value !== "string") return "";
  return value.replace(/[\u0000-\u0008\u000B\u000C\u000E-\u001F\u007F]+/g, "").trim().slice(0, maxLen);
}

async function readBoundedText(request, maxBytes) {
  const contentLength = Number(request.headers.get("content-length") || "0");
  if (Number.isFinite(contentLength) && contentLength > maxBytes) {
    throw new Error("payload_too_large");
  }
  if (!request.body) return "";
  const reader = request.body.getReader();
  const chunks = [];
  let total = 0;
  while (true) {
    const { done, value } = await reader.read();
    if (done) break;
    total += value.byteLength;
    if (total > maxBytes) {
      await reader.cancel();
      throw new Error("payload_too_large");
    }
    chunks.push(value);
  }
  const merged = new Uint8Array(total);
  let offset = 0;
  for (const chunk of chunks) {
    merged.set(chunk, offset);
    offset += chunk.byteLength;
  }
  return new TextDecoder("utf-8").decode(merged);
}

async function checkRateLimit(clientIp, maxPerHour) {
  const safeIp = (clientIp || "unknown").replace(/[^a-zA-Z0-9.:_-]/g, "").slice(0, 64);
  const hourBucket = Math.floor(Date.now() / 3600000);
  const cacheKey = new Request(`https://rate-limit.aw-anime.internal/v1/${safeIp}/${hourBucket}`);
  const cache = caches.default;

  const existing = await cache.match(cacheKey);
  const count = existing ? Number(await existing.text()) || 0 : 0;
  if (count >= maxPerHour) {
    return { allowed: false, remaining: 0 };
  }

  await cache.put(
    cacheKey,
    new Response(String(count + 1), {
      headers: {
        "Content-Type": "text/plain",
        "Cache-Control": "public, max-age=3600",
      },
    })
  );
  return { allowed: true, remaining: Math.max(0, maxPerHour - count - 1) };
}

export default {
  async fetch(request, env) {
    const url = new URL(request.url);

    if (request.method === "GET" && (url.pathname === "/" || url.pathname === "/health")) {
      return jsonResponse({
        ok: true,
        service: "aw-anime-reports",
        repo: env.GITHUB_REPO || "Carloj005/aw-android",
        configured: Boolean(env.GITHUB_TOKEN && env.GITHUB_TOKEN.trim().length > 0),
      });
    }

    if (request.method !== "POST" || url.pathname !== "/report") {
      return jsonResponse({ ok: false, error: "not_found" }, 404);
    }

    if (request.headers.get("x-aw-client") !== "aw-anime-android") {
      return jsonResponse({ ok: false, error: "invalid_client" }, 403);
    }

    if (!env.GITHUB_TOKEN || !env.GITHUB_TOKEN.trim()) {
      console.error(JSON.stringify({ message: "missing GITHUB_TOKEN secret" }));
      return jsonResponse({ ok: false, error: "relay_unconfigured" }, 503);
    }

    const maxPerHour = Math.max(1, Number(env.RATE_LIMIT_MAX_PER_HOUR || "4") || 4);
    const clientIp = request.headers.get("cf-connecting-ip") || "unknown";
    const rl = await checkRateLimit(clientIp, maxPerHour);
    if (!rl.allowed) {
      return jsonResponse({ ok: false, error: "rate_limited" }, 429, { "Retry-After": "3600" });
    }

    let rawText;
    try {
      rawText = await readBoundedText(request, MAX_BODY_BYTES);
    } catch (err) {
      if (err instanceof Error && err.message === "payload_too_large") {
        return jsonResponse({ ok: false, error: "payload_too_large" }, 413);
      }
      return jsonResponse({ ok: false, error: "invalid_body" }, 400);
    }

    let payload;
    try {
      payload = JSON.parse(rawText);
    } catch {
      return jsonResponse({ ok: false, error: "invalid_json" }, 400);
    }

    const kind = typeof payload.kind === "string" ? payload.kind.trim().toLowerCase() : "bug";
    if (!ALLOWED_KINDS.has(kind)) {
      return jsonResponse({ ok: false, error: "invalid_kind" }, 400);
    }

    const rawTitle = sanitizeLine(payload.title, 110);
    const description = sanitizeMultiline(payload.description, 2000);
    if (rawTitle.length < 3 && description.length < 5) {
      return jsonResponse({ ok: false, error: "empty_report" }, 400);
    }

    const appVersion = sanitizeLine(payload.appVersion, 40) || "unknown";
    const versionCode = Number.isInteger(payload.versionCode) ? payload.versionCode : 0;
    const androidRelease = sanitizeLine(payload.androidRelease, 32) || "unknown";
    const sdkInt = Number.isInteger(payload.sdkInt) ? payload.sdkInt : 0;
    const device = sanitizeLine(payload.device, 80) || "unknown";
    const activeDomain = sanitizeLine(payload.activeDomain, 80) || "animeunity.cx";

    const prefix = kind === "bug" ? "[bug] " : "[feature] ";
    const effectiveTitle = rawTitle.length >= 3
      ? (rawTitle.toLowerCase().startsWith(prefix.trim()) ? rawTitle : `${prefix}${rawTitle}`)
      : `${prefix}${description.slice(0, 60)}`;

    const issueBody = [
      "### Descrizione",
      description || "(Nessuna descrizione aggiuntiva)",
      "",
      "### Diagnostica dispositivo (invio anonimo in-app)",
      `- **App**: AW Anime v${appVersion}${versionCode > 0 ? ` (build ${versionCode})` : ""}`,
      `- **Android**: ${androidRelease}${sdkInt > 0 ? ` (API ${sdkInt})` : ""}`,
      `- **Dispositivo**: ${device}`,
      `- **Dominio attivo**: ${activeDomain}`,
    ].join("\n");

    const repo = env.GITHUB_REPO || "Carloj005/aw-android";
    const labels = kind === "bug" ? ["bug"] : ["enhancement"];

    try {
      const ghResp = await fetch(`https://api.github.com/repos/${repo}/issues`, {
        method: "POST",
        headers: {
          Authorization: `Bearer ${env.GITHUB_TOKEN.trim()}`,
          Accept: "application/vnd.github+json",
          "Content-Type": "application/json",
          "User-Agent": "aw-anime-reports-worker/1.0",
          "X-GitHub-Api-Version": "2022-11-28",
        },
        body: JSON.stringify({
          title: effectiveTitle,
          body: issueBody,
          labels,
        }),
      });

      if (!ghResp.ok) {
        const errSnippet = (await ghResp.text()).slice(0, 300);
        console.error(JSON.stringify({ message: "github issue creation failed", status: ghResp.status, errSnippet }));
        return jsonResponse({ ok: false, error: "upstream_failed", status: ghResp.status }, 502);
      }

      const created = await ghResp.json();
      console.log(JSON.stringify({ message: "issue created", number: created.number, kind, appVersion }));
      return jsonResponse({
        ok: true,
        issueNumber: created.number,
        issueUrl: created.html_url,
      }, 201);
    } catch (err) {
      console.error(JSON.stringify({ message: "github fetch threw", error: String(err) }));
      return jsonResponse({ ok: false, error: "upstream_exception" }, 502);
    }
  },
};
