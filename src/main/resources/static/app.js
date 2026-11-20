(() => {
  const STATUS = {
    QUEUED: { label: "排队中", className: "running" },
    RUNNING: { label: "运行中", className: "running" },
    WAITING_REVIEW: { label: "待人工复核", className: "waiting" },
    SUCCEEDED: { label: "已通过", className: "success" },
    REJECTED: { label: "已驳回", className: "failed" },
    FAILED: { label: "执行失败", className: "failed" },
  };

  const state = {
    templates: [],
    runs: [],
    current: null,
    pollTimer: null,
    runtime: null,
    submitting: false,
  };

  const $ = (id) => document.getElementById(id);

  const DEMO = {
    title: "编辑部正在被大模型重写",
    content:
      "过去一年，编辑部把大模型接入了选题、摘要、标题和质检流程。最初的方案只是把一段素材丢给模型，再人工复制结果。问题很快出现：模型偶发超时、重复请求导致重复生成、同一篇稿件的摘要与标题风格不一致，而且没有人能回答一次评审到底花了多少 Token、哪个节点最慢。\n\n" +
      "这次改造把内容评审拆成五个可观测节点：先校验正文长度与结构，再生成摘要，基于摘要生成标题，随后进行质量评分，最后进入人工复核。每个节点都有独立的超时与重试策略，运行状态写入 MySQL，详情缓存进 Redis。异步派发使用 RabbitMQ，Prometheus 记录节点耗时、失败次数和 Token 消耗。\n\n" +
      "真正的变化不是模型变强了，而是内容生产第一次有了可以复盘的过程数据。编辑能看到失败发生在哪一步，也能在发布前保留最终判断权。",
  };

  document.addEventListener("DOMContentLoaded", init);

  async function init() {
    bindEvents();
    await Promise.allSettled([loadRuntime(), loadTemplates(), refreshAll()]);
    updateHealth();
    if (!state.current && state.runs.length > 0) {
      await selectRun(state.runs[0].id);
    }
  }

  function bindEvents() {
    $("run-form").addEventListener("submit", (event) => {
      event.preventDefault();
      submitRun();
    });
    $("demo-button").addEventListener("click", fillDemo);
    $("empty-demo-button").addEventListener("click", () => {
      fillDemo();
      submitRun();
    });
    $("refresh-button").addEventListener("click", refreshAll);
    $("content-input").addEventListener("input", updateCharCount);
    $("approve-button").addEventListener("click", () => submitReview(true));
    $("reject-button").addEventListener("click", () => submitReview(false));
    $("copy-output-button").addEventListener("click", copyOutput);
    $("run-list").addEventListener("click", (event) => {
      const row = event.target.closest(".run-row");
      if (row) selectRun(row.dataset.id);
    });
    updateCharCount();
  }

  async function request(path, options = {}) {
    const response = await fetch(path, {
      ...options,
      headers: {
        ...(options.body ? { "Content-Type": "application/json" } : {}),
        ...(options.headers || {}),
      },
    });
    const text = await response.text();
    let body = null;
    if (text) {
      try {
        body = JSON.parse(text);
      } catch {
        body = text;
      }
    }
    if (!response.ok) {
      const message = typeof body === "object" && body && body.error ? body.error : `HTTP ${response.status}`;
      throw new Error(message);
    }
    return body;
  }

  async function loadRuntime() {
    try {
      state.runtime = await request("/api/runtime");
      $("model-mode").textContent = state.runtime.modelMode;
    } catch (error) {
      console.warn("runtime info unavailable", error);
    }
  }

  async function loadTemplates() {
    try {
      state.templates = await request("/api/templates");
      const select = $("template-input");
      select.innerHTML = "";
      for (const template of state.templates) {
        const option = document.createElement("option");
        option.value = template.id;
        option.textContent = `${template.name} · v${template.version}`;
        select.appendChild(option);
      }
    } catch (error) {
      showToast(`模板加载失败：${error.message}`, true);
    }
  }

  async function refreshAll() {
    await Promise.allSettled([loadRuns(), loadMetrics()]);
    if (state.current) {
      const current = state.runs.find((run) => run.id === state.current.run.id);
      if (!current) {
        state.current = null;
        showEmpty();
      }
    }
  }

  async function loadRuns() {
    state.runs = await request("/api/runs?limit=40");
    renderRuns();
  }

  async function loadMetrics() {
    const metrics = await request("/api/metrics");
    $("metric-total").textContent = formatInteger(metrics.totalRuns);
    $("metric-succeeded").textContent = formatInteger(metrics.succeededRuns);
    $("metric-waiting").textContent = formatInteger(metrics.waitingRuns);
    $("metric-tokens").textContent = compactNumber(metrics.totalTokens);
    $("metric-duration").textContent =
      metrics.averageDurationMs > 0 ? formatDuration(metrics.averageDurationMs) : "—";
  }

  function renderRuns() {
    const container = $("run-list");
    if (state.runs.length === 0) {
      container.innerHTML = '<p class="empty-compact">还没有运行记录，先发起一次评审。</p>';
      return;
    }
    container.innerHTML = state.runs
      .map((run) => {
        const status = STATUS[run.status] || STATUS.QUEUED;
        const active = state.current && state.current.run.id === run.id ? " active" : "";
        return `
          <button class="run-row${active}" type="button" data-id="${escapeHtml(run.id)}">
            <span class="run-dot ${status.className}"></span>
            <span class="run-row-title">${escapeHtml(run.title)}</span>
            <span class="run-row-time">${relativeTime(run.createdAtMs)}</span>
          </button>`;
      })
      .join("");
  }

  async function submitRun() {
    if (state.submitting) return;
    const title = $("title-input").value.trim();
    const content = $("content-input").value.trim();
    if (!title || !content) {
      showToast("请填写标题和正文素材", true);
      return;
    }
    state.submitting = true;
    const button = $("submit-button");
    const original = button.innerHTML;
    button.disabled = true;
    button.innerHTML = "<span>工作流执行中…</span><span>···</span>";
    try {
      const result = await request("/api/runs", {
        method: "POST",
        headers: { "Idempotency-Key": generateIdempotencyKey() },
        body: JSON.stringify({
          title,
          content,
          source: $("source-input").value,
          templateId: $("template-input").value || "tpl_media_review_v1",
        }),
      });
      showToast("工作流已启动");
      await Promise.allSettled([loadRuns(), loadMetrics()]);
      await selectRun(result.runId);
      $("content-input").value = "";
      updateCharCount();
    } catch (error) {
      showToast(`启动失败：${error.message}`, true);
    } finally {
      state.submitting = false;
      button.disabled = false;
      button.innerHTML = original;
    }
  }

  async function selectRun(runId) {
    clearPoll();
    try {
      const view = await request(`/api/runs/${encodeURIComponent(runId)}`);
      if (!view) return;
      state.current = view;
      renderDetail(view);
      renderRuns();
      if (view.run.status === "QUEUED" || view.run.status === "RUNNING") {
        schedulePoll(view.run.id);
      }
    } catch (error) {
      showToast(`加载运行失败：${error.message}`, true);
    }
  }

  function schedulePoll(runId) {
    clearPoll();
    state.pollTimer = window.setTimeout(async () => {
      if (!state.current || state.current.run.id !== runId) return;
      try {
        const view = await request(`/api/runs/${encodeURIComponent(runId)}`);
        state.current = view;
        renderDetail(view);
        renderRuns();
        await loadMetrics();
        if (view.run.status === "QUEUED" || view.run.status === "RUNNING") {
          schedulePoll(runId);
        }
      } catch (error) {
        console.warn("poll failed", error);
        schedulePoll(runId);
      }
    }, 1800);
  }

  function clearPoll() {
    if (state.pollTimer) window.clearTimeout(state.pollTimer);
    state.pollTimer = null;
  }

  function showEmpty() {
    $("empty-state").classList.remove("hidden");
    $("run-detail").classList.add("hidden");
  }

  function renderDetail(view) {
    const run = view.run;
    $("empty-state").classList.add("hidden");
    $("run-detail").classList.remove("hidden");

    $("detail-title").textContent = run.title;
    $("detail-id").textContent = shortenId(run.id);
    $("detail-time").textContent = formatDateTime(run.createdAtMs);
    const status = STATUS[run.status] || STATUS.QUEUED;
    const pill = $("detail-status");
    pill.textContent = status.label;
    pill.className = `status-pill ${status.className}`;

    const completed = view.nodeRuns.filter((node) => node.status === "SUCCEEDED").length;
    const processingMs = view.nodeRuns.reduce((sum, node) => sum + (node.durationMs || 0), 0);
    const elapsed =
      run.finishedAtMs && run.startedAtMs ? run.finishedAtMs - run.startedAtMs : processingMs;
    const waitingSuffix = run.status === "WAITING_REVIEW" ? " · 等待编辑" : "";
    $("timeline-summary").textContent = `${completed} / ${view.nodeRuns.length} 节点完成 · ${formatDuration(elapsed)}${waitingSuffix}`;

    $("node-strip").innerHTML = view.nodeRuns
      .map((node) => {
        const nodeStatus = nodeClass(node.status);
        const index = String(node.order).padStart(2, "0");
        const meta =
          node.status === "RUNNING"
            ? "执行中"
            : node.status === "WAITING_REVIEW"
              ? "等待编辑"
              : node.status === "PENDING"
                ? "等待前序"
                : `${formatDuration(node.durationMs)} · ${node.promptTokens + node.completionTokens} tok`;
        return `
          <div class="node-card ${nodeStatus}">
            <span class="node-index">${index}</span>
            <span class="node-name">${escapeHtml(node.displayName)}</span>
            <span class="node-meta">${escapeHtml(meta)}</span>
          </div>`;
      })
      .join("");

    const outputNodes = view.nodeRuns.filter((node) => node.nodeType !== "HUMAN_REVIEW");
    const beforeQuality = outputNodes.filter((node) => node.nodeType !== "QUALITY");
    const qualityNodes = outputNodes.filter((node) => node.nodeType === "QUALITY");
    const totalTokens = view.nodeRuns.reduce(
      (sum, node) => sum + (node.promptTokens || 0) + (node.completionTokens || 0),
      0,
    );
    const runtime = state.runtime || {};
    const metaCard = `
      <article class="output-card">
        <div class="output-card-head"><h4>运行配置</h4><span>RUNTIME</span></div>
        <div class="meta-list">
          <div><span>模型</span><b>${escapeHtml(runtime.modelMode || view.modelMode || "—")}</b></div>
          <div><span>派发</span><b>${runtime.queueEnabled ? "RabbitMQ 异步" : "同步执行"}</b></div>
          <div><span>节点</span><b>${view.nodeRuns.length} 个</b></div>
          <div><span>Token</span><b>${formatInteger(totalTokens)}</b></div>
        </div>
      </article>`;
    $("output-grid").innerHTML =
      beforeQuality.map(renderOutputCard).join("") + metaCard + qualityNodes.map(renderOutputCard).join("");
    $("raw-output").textContent = prettyOutput(view);
    renderReview(view);
  }

  function renderOutputCard(node) {
    const payload = parseJson(node.outputJson);
    const head = `
      <div class="output-card-head">
        <h4>${escapeHtml(node.displayName)}</h4>
        <span>${escapeHtml(node.nodeType)}</span>
      </div>`;
    if (!payload) {
      return `<article class="output-card">${head}<p class="muted">等待执行…</p></article>`;
    }
    switch (node.nodeType) {
      case "VALIDATE":
        return `<article class="output-card">${head}
          <div class="validate-grid">
            <div><strong>${formatInteger(payload.characters)}</strong><span>字符</span></div>
            <div><strong>${formatInteger(payload.paragraphs)}</strong><span>段落</span></div>
            <div><strong>${payload.status === "valid" ? "通过" : "异常"}</strong><span>校验</span></div>
          </div>
        </article>`;
      case "SUMMARIZE":
        return `<article class="output-card">${head}
          <p class="summary-text">${escapeHtml(payload.summary || "—")}</p>
          <div class="chips">${(payload.keyPoints || [])
            .slice(0, 3)
            .map((point) => `<span class="chip">${escapeHtml(shorten(point, 34))}</span>`)
            .join("")}</div>
        </article>`;
      case "TITLE":
        return `<article class="output-card">${head}
          <ul class="title-list">${(payload.titles || [])
            .map(
              (title, index) =>
                `<li><b>${String(index + 1).padStart(2, "0")}</b><span>${escapeHtml(title)}</span></li>`,
            )
            .join("")}</ul>
        </article>`;
      case "QUALITY":
        return `<article class="output-card wide">${head}
          <div class="quality-layout">
            <div class="score-ring-wrap">
              <div class="score-ring" style="--score:${Number(payload.score) || 0}">
                <span class="score-value">${Number(payload.score) || 0}</span>
              </div>
              <span class="score-label">QUALITY SCORE</span>
            </div>
            <div>
              ${Object.entries(payload.dimensions || {})
                .slice(0, 4)
                .map(
                  ([name, value]) => `
                    <div class="dimension">
                      <span>${escapeHtml(name)}</span>
                      <span class="dimension-bar"><i style="width:${Math.max(0, Math.min(100, Number(value)))}%"></i></span>
                      <b>${Number(value)}</b>
                    </div>`,
                )
                .join("")}
              ${payload.issues && payload.issues.length
                ? `<p class="issues">${escapeHtml(payload.issues.join("；"))}</p>`
                : ""}
            </div>
          </div>
        </article>`;
      default:
        return `<article class="output-card wide">${head}<pre class="mini-json">${escapeHtml(JSON.stringify(payload, null, 2))}</pre></article>`;
    }
  }

  function renderReview(view) {
    const section = $("review-section");
    const run = view.run;
    if (run.status === "WAITING_REVIEW") {
      const comment = $("review-comment") ? $("review-comment").value : "";
      section.innerHTML = `
        <div class="review-copy">
          <p class="section-kicker">HUMAN REVIEW</p>
          <h3>发布前，由编辑做最后判断</h3>
          <p>模型只负责生成与评分，是否发布仍由人决定。复核结果会写入运行记录。</p>
        </div>
        <div class="review-form">
          <label class="field">
            <span>复核意见 <small>可选</small></span>
            <textarea id="review-comment" rows="3" placeholder="记录修改建议或通过理由…">${escapeHtml(comment)}</textarea>
          </label>
          <div class="review-actions">
            <button class="danger-button" id="reject-button" type="button">驳回</button>
            <button class="primary-button small" id="approve-button" type="button">通过并完成</button>
          </div>
        </div>`;
      $("approve-button").addEventListener("click", () => submitReview(true));
      $("reject-button").addEventListener("click", () => submitReview(false));
      return;
    }

    const reviewNode = view.nodeRuns.find((node) => node.nodeKey === "review");
    const reviewOutput = parseJson(reviewNode ? reviewNode.outputJson : null) || {};
    let copy = "工作流尚未进入人工复核。";
    let badge = "";
    if (run.status === "SUCCEEDED") {
      copy = reviewOutput.comment
        ? `编辑已通过本次内容。复核意见：${escapeHtml(reviewOutput.comment)}`
        : "编辑已通过本次内容，可以进入发布流程。";
      badge = '<span class="decision-badge approved">APPROVED</span>';
    } else if (run.status === "REJECTED") {
      copy = reviewOutput.comment
        ? `编辑驳回了本次内容。复核意见：${escapeHtml(reviewOutput.comment)}`
        : "编辑驳回了本次内容，需要重新调整素材。";
      badge = '<span class="decision-badge rejected">REJECTED</span>';
    } else if (run.status === "FAILED") {
      copy = `运行失败：${escapeHtml(run.errorMessage || "未知错误")}`;
      badge = '<span class="decision-badge rejected">FAILED</span>';
    } else {
      copy = `当前状态：${STATUS[run.status] ? STATUS[run.status].label : run.status}`;
      badge = '<span class="decision-badge">IN PROGRESS</span>';
    }
    section.innerHTML = `
      <div class="review-complete">
        <div>
          <p class="section-kicker">REVIEW DECISION</p>
          <p>${copy}</p>
        </div>
        ${badge}
      </div>`;
  }

  async function submitReview(approved) {
    if (!state.current) return;
    const runId = state.current.run.id;
    const commentInput = $("review-comment");
    const comment = commentInput ? commentInput.value.trim() : "";
    const buttons = [$("approve-button"), $("reject-button")].filter(Boolean);
    buttons.forEach((button) => {
      button.disabled = true;
    });
    try {
      const view = await request(`/api/runs/${encodeURIComponent(runId)}/review`, {
        method: "POST",
        body: JSON.stringify({ approved, comment }),
      });
      state.current = view;
      renderDetail(view);
      renderRuns();
      await loadMetrics();
      showToast(approved ? "已通过并完成" : "已驳回本次内容");
    } catch (error) {
      showToast(`复核失败：${error.message}`, true);
      buttons.forEach((button) => {
        button.disabled = false;
      });
    }
  }

  function fillDemo() {
    $("title-input").value = DEMO.title;
    $("content-input").value = DEMO.content;
    updateCharCount();
    $("content-input").focus();
    showToast("已填入示例素材");
  }

  function updateCharCount() {
    const length = $("content-input").value.length;
    $("char-count").textContent = `${formatInteger(length)} / 12000`;
  }

  async function copyOutput() {
    const text = prettyOutput(state.current);
    try {
      await navigator.clipboard.writeText(text);
      showToast("结果 JSON 已复制");
    } catch {
      showToast("复制失败，请手动选择 JSON", true);
    }
  }

  function prettyOutput(view) {
    if (!view) return "";
    if (view.run.outputJson) {
      const parsed = parseJson(view.run.outputJson);
      if (parsed) return JSON.stringify(parsed, null, 2);
      return view.run.outputJson;
    }
    const nodes = {};
    for (const node of view.nodeRuns) {
      if (node.outputJson) nodes[node.nodeKey] = parseJson(node.outputJson) || node.outputJson;
    }
    return JSON.stringify({ status: view.run.status, nodes }, null, 2);
  }

  async function updateHealth() {
    try {
      const health = await request("/actuator/health");
      $("health-label").textContent = health.status === "UP" ? "平台在线" : "服务异常";
    } catch {
      $("health-label").textContent = "状态未知";
    }
  }

  function nodeClass(status) {
    return {
      SUCCEEDED: "success",
      RUNNING: "running",
      WAITING_REVIEW: "waiting",
      FAILED: "failed",
      PENDING: "",
    }[status] || "";
  }

  function parseJson(value) {
    if (!value) return null;
    if (typeof value === "object") return value;
    try {
      return JSON.parse(value);
    } catch {
      return null;
    }
  }

  function escapeHtml(value) {
    return String(value == null ? "" : value)
      .replaceAll("&", "&amp;")
      .replaceAll("<", "&lt;")
      .replaceAll(">", "&gt;")
      .replaceAll('"', "&quot;")
      .replaceAll("'", "&#039;");
  }

  function shorten(value, maxLength) {
    const text = String(value || "");
    return text.length > maxLength ? `${text.slice(0, maxLength)}…` : text;
  }

  function shortenId(id) {
    return id && id.length > 16 ? `${id.slice(0, 8)}…${id.slice(-4)}` : id || "—";
  }

  function formatInteger(value) {
    return Number(value || 0).toLocaleString("zh-CN");
  }

  function compactNumber(value) {
    const number = Number(value || 0);
    if (number >= 1000000) return `${(number / 1000000).toFixed(1)}m`;
    if (number >= 1000) return `${(number / 1000).toFixed(1)}k`;
    return formatInteger(number);
  }

  function formatDuration(ms) {
    if (ms == null || Number.isNaN(Number(ms))) return "—";
    const value = Number(ms);
    if (value < 1000) return `${Math.round(value)} ms`;
    return `${(value / 1000).toFixed(1)} s`;
  }

  function formatDateTime(timestamp) {
    if (!timestamp) return "—";
    return new Date(timestamp).toLocaleString("zh-CN", {
      month: "2-digit",
      day: "2-digit",
      hour: "2-digit",
      minute: "2-digit",
      second: "2-digit",
      hour12: false,
    });
  }

  function relativeTime(timestamp) {
    const delta = Date.now() - Number(timestamp || 0);
    if (delta < 60 * 1000) return "刚刚";
    if (delta < 60 * 60 * 1000) return `${Math.floor(delta / 60000)} 分钟前`;
    if (delta < 24 * 60 * 60 * 1000) return `${Math.floor(delta / 3600000)} 小时前`;
    return formatDateTime(timestamp).slice(0, 5);
  }

  function generateIdempotencyKey() {
    if (window.crypto && typeof window.crypto.randomUUID === "function") {
      return window.crypto.randomUUID();
    }
    return `run-${Date.now()}-${Math.random().toString(16).slice(2)}`;
  }

  let toastTimer = null;
  function showToast(message, error = false) {
    const toast = $("toast");
    toast.textContent = message;
    toast.classList.toggle("error", Boolean(error));
    toast.classList.add("show");
    if (toastTimer) window.clearTimeout(toastTimer);
    toastTimer = window.setTimeout(() => toast.classList.remove("show"), 3200);
  }
})();
