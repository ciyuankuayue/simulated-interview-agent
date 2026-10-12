/*  AI 模拟面试 · 前端交互（T9）
 *
 *  约定：本文件是 api.md 契约的一个消费者——所有数据都走 /api/** 的 JSON 接口，
 *  页面骨架由 Thymeleaf 渲染，这里只负责「调接口 + 收 SSE + 渲染」。
 *
 *  两条关键时序（后端已配合处理，前端也要对上）：
 *  1. **订阅晚于生产**：start 会同步返回首题，但前端拿到 sessionId 才连 SSE。
 *     后端 SseBroker 保留历史并**重放**，所以首题由 SSE 的 question 事件渲染，
 *     start 响应的 firstQuestion 只做提示、不渲染——避免重复两遍。
 *  2. **answer 只回 {accepted:true}**：判分结果走 SSE（score / followup），
 *     所以提交后禁用输入，等下一个 await_answer 再放开。
 *
 *  无框架、无构建链（决策 #12）。原生 fetch + EventSource 足够。
 */
(function () {
    'use strict';

    const $ = (id) => document.getElementById(id);

    const state = {
        // R1-1.5 多用户鉴权：JS 内存变量，刷新即失（符合安全约定），**严禁 localStorage**
        token: null,              // JWT（Authorization header / SSE jwt query）
        username: null,           // 登录用户名（UI 显示用）
        jdId: null,
        resumeId: null,
        sessionId: null,
        es: null,
        awaiting: false,
        reportRendered: false,
        reportSessionId: null,  // F2/F4：当前报告所属会话 id（live 或回看），供导出/逐轮回看用
        rendered: new Set(),  // 事件去重：type + turnSeq
        weakDetailLoaded: new Set(),  // 错题本详情懒加载：已拉取过的 weakPointId
        quota: null  // R2：最近一次额度视图 {totalTokens,usedTokens,remainingTokens}，null=未加载（不前置拦截）
        // R1-3 起只有 JWT 一道门禁（旧保险箱 vaultToken 已删除）；token 只在内存，刷新必失
    };

    // ---------- 工具 ----------

    async function api(path, method, body) {
        const headers = { 'Content-Type': 'application/json; charset=utf-8' };
        // R1-3 起单令牌：只有 JWT（Authorization header；SSE 走 ?jwt= query）
        if (state.token) headers['Authorization'] = 'Bearer ' + state.token;
        const opt = { method: method || 'GET', headers: headers };
        if (body !== undefined) opt.body = JSON.stringify(body);
        let res;
        try {
            res = await fetch(path, opt);
        } catch (e) {
            throw new Error('请求失败（后端没起？）: ' + e.message);
        }
        const text = await res.text();
        let data = null;
        try { data = text ? JSON.parse(text) : null; } catch (e) { data = text; }
        if (!res.ok) {
            const msg = (data && data.message) ? data.message : (typeof data === 'string' ? data : res.status);
            // 401：登录接口=用户名密码错误直接抛给弹窗；其余一律 JWT 失效 → 强制下线（R1-3 已无第二道门禁）
            if (res.status === 401) {
                if (path === '/api/auth/login') throw new Error(msg || '用户名或密码错误');
                forceLogout('登录已过期，请重新登录');
                throw new Error('登录已过期，请重新登录');
            }
            // 402：Token 额度耗尽。R2 起平台实扣/BYOK 服务费统一走此码——
            // 全局兜底弹窗（各入口另有前置拦截），同时刷新额度数字，错误继续抛给调用方走各自 catch
            if (res.status === 402 || (data && data.code === 'QUOTA_EXHAUSTED')) {
                loadQuota();
                showQuotaModal(msg);
            }
            const err = new Error(msg);
            err.code = (data && data.code) ? data.code : null;
            err.status = res.status;
            throw err;
        }
        return data;
    }

    /** 强制下线：清 JWT、停 SSE、隐藏主界面、弹登录窗（带错误提示）。 */
    function forceLogout(message) {
        state.token = null;
        state.username = null;
        state.quota = null;  // 清掉上个账号的额度视图，防下个用户登录前误拦
        hideQuotaModal();
        closeStream();
        $('appShell').hidden = true;
        $('userArea').hidden = true;
        showAuthModal('login', message);
    }

    function esc(s) {
        return String(s == null ? '' : s).replace(/[&<>"']/g, (c) =>
            ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
    }

    function tip(msg, bad) {
        const el = $('tip');
        el.textContent = msg || '';
        el.className = 'tip' + (bad ? ' bad' : '');
    }

    function setState(text) { $('stateText').textContent = text; }

    function setConn(ok, text) {
        $('connDot').className = 'dot ' + (ok ? 'online' : 'offline');
        $('connText').textContent = text;
    }

    function fmtTime(iso) {
        if (!iso) return '—';
        const d = new Date(iso);
        return isNaN(d) ? iso : d.toLocaleString('zh-CN', { month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' });
    }

    // ---------- 对话区渲染 ----------

    function bubble(cls, title, html) {
        const box = document.createElement('div');
        box.className = 'msg ' + cls;
        box.innerHTML = '<div class="msgTitle">' + esc(title) + '</div><div class="msgBody">' + html + '</div>';
        $('chat').appendChild(box);
        $('chat').scrollTop = $('chat').scrollHeight;
        return box;
    }

    function clearChat() {
        $('chat').innerHTML = '';
    }

    function renderQuestion(ev) {
        const layer = ev.layer ? ' · 追问 L' + ev.layer : '';
        bubble('q', (ev.role || '面试官') + ' · 第 ' + ev.turnSeq + ' 题' + layer, esc(ev.question));
    }

    function renderScore(ev) {
        const s = ev.score == null ? '—' : ev.score;
        const pct = Math.max(0, Math.min(100, (Number(s) / 8) * 100));
        // R7：行为轮角标（STAR 四维评分，赛道与技术轮区分）
        const badge = ev.track === 'behavior' ? '<span class="trackBadge">行为评分</span>' : '';
        const html =
            '<div class="scoreRow"><span class="scoreNum">' + esc(s) + '</span><span class="scoreDen">/ 8</span>' +
            '<span class="bar"><i style="width:' + pct + '%"></i></span></div>' +
            (ev.oneLineCorrection ? '<div class="correction">一句纠正：' + esc(ev.oneLineCorrection) + '</div>' : '');
        bubble('score', '本轮评分（第 ' + ev.turnSeq + ' 轮）' + badge, html);
    }

    function renderAnswer(text) {
        bubble('me', '我', esc(text));
    }

    // ---------- 报告 ----------

    function renderReport(r, sessionId) {
        if (state.reportRendered) return;   // end 的响应与 SSE 的 report 事件只渲染一次
        state.reportRendered = true;
        state.reportSessionId = sessionId || state.sessionId;
        // F4：报告已出，启用导出按钮；F2：启用逐轮回看
        $('btnExportMd').disabled = false;
        $('btnViewTurns').disabled = false;
        $('turnsReplay').hidden = true;
        $('turnsReplay').innerHTML = '';

        const turns = Array.isArray(r.perTurnReview) ? r.perTurnReview : [];
        const collapsed = Array.isArray(r.collapsedPoints) ? r.collapsedPoints : [];
        const issues = Array.isArray(r.expressionIssues) ? r.expressionIssues : [];
        const reminders = Array.isArray(r.reminders) ? r.reminders : [];

        const turnHtml = turns.map((t) =>
            '<details class="turn"><summary>第 ' + esc(t.turnSeq) + ' 轮 · ' + esc(t.score) + '/8 · ' +
            (t.track === 'behavior' ? '<span class="trackBadge">行为</span> ' : '') +
            esc(String(t.question || '').slice(0, 40)) + '</summary>' +
            row('讲解', t.explanation) + row('纠正', t.correction) +
            row('知识补充', t.knowledgeSupplement) + row('示范回答', t.exemplarAnswer) +
            '</details>').join('');

        $('report').innerHTML =
            '<div class="match">匹配度 <b>' + esc(r.matchScore == null ? '—' : r.matchScore) + '</b> / 100</div>' +
            behaviorCard(r.behaviorReview) +
            (turnHtml ? '<h3>逐轮讲解</h3>' + turnHtml : '') +
            list('答崩点', collapsed) +
            (issues.length ? '<h3>表述问题</h3>' + issues.map((i) =>
                '<div class="issue"><b>' + esc(i.issue) + '</b><div>' + esc(i.guide) + '</div></div>').join('') : '') +
            (r.overallNotes ? '<h3>整体注意事项</h3><p class="notes">' + esc(r.overallNotes) + '</p>' : '') +
            list('必要提醒', reminders);

        setState('已完成，报告已出');
    }

    /**
     * R7 行为维度卡：behaviorReview 为 null（纯技术场 / 老报告回看）或缺关键字段时整卡不渲染。
     * dimensions：STAR结构 / 行动细节 / 结果与反思 / 表达与抗压，各 0-2 分。
     */
    function behaviorCard(br) {
        if (!br || typeof br !== 'object') return '';
        const dims = br.dimensions && typeof br.dimensions === 'object' ? br.dimensions : null;
        const suggestions = Array.isArray(br.suggestions) ? br.suggestions : [];
        if (!dims && !br.summary && !suggestions.length) return '';
        const dimHtml = dims ? '<div class="behDims">' + Object.entries(dims).map(([k, v]) =>
            '<span class="behDim">' + esc(k) + ' <b>' + esc(v) + '</b>/2</span>').join('') + '</div>' : '';
        const sugHtml = suggestions.length
            ? '<ul class="lst">' + suggestions.map((x) => '<li>' + esc(x) + '</li>').join('') + '</ul>' : '';
        return '<h3>行为面试评估</h3><div class="behCard">' + dimHtml +
            (br.summary ? '<p class="notes">' + esc(br.summary) + '</p>' : '') +
            sugHtml + '</div>';
    }

    function row(label, value) {
        return value ? '<div class="kv"><span class="k">' + label + '</span><span class="v">' + esc(value) + '</span></div>' : '';
    }

    function list(title, arr) {
        if (!arr || !arr.length) return '';
        return '<h3>' + title + '</h3><ul class="lst">' + arr.map((x) => '<li>' + esc(x) + '</li>').join('') + '</ul>';
    }

    // ---------- 历史面试 + 报告回看 ----------

    async function loadHistory() {
        try {
            const arr = await api('/api/interview/sessions');
            if (!arr || !arr.length) {
                $('history').innerHTML = '<p class="placeholder">还没有历史面试。</p>';
                return;
            }
            $('history').innerHTML = arr.map((s) => {
                const label = (s.jobType || '面试') + (s.sameTypeTag ? ' · ' + esc(s.sameTypeTag) : '');
                const score = s.matchScore == null ? '未评分' : esc(s.matchScore) + ' 分';
                const cls = 'histItem' + (s.hasReport ? '' : ' noReport');
                const tag = s.hasReport ? '' : ' · ' + esc(s.status);
                return '<button class="' + cls + '" data-sid="' + esc(s.sessionId) + '"' +
                    (s.hasReport ? '' : ' disabled title="这场还没出报告"') + '>#' + esc(s.sessionId) +
                    ' ' + esc(label) + '<span>' + esc(fmtTime(s.createdAt)) + ' · ' + score + esc(tag) + '</span></button>';
            }).join('');
        } catch (e) {
            $('history').innerHTML = '<p class="placeholder bad">历史加载失败：' + esc(e.message) + '</p>';
        }
    }

    /** 回看指定场次的报告（历史点击）；与实时流无关，强制渲染覆盖当前报告区。 */
    async function viewHistoryReport(sessionId) {
        tip('正在加载 #' + sessionId + ' 的报告…');
        try {
            const r = await api('/api/interview/' + sessionId + '/report');
            state.reportRendered = false;  // 解除实时流的一次性闸门，强制渲染这份历史报告
            renderReport(r, sessionId);
            setState('历史 #' + sessionId + ' 报告');
            tip('已载入历史会话 #' + sessionId + ' 的报告');
        } catch (e) { tip(e.message, true); }
    }

    // ---------- F1：真实面试记录 ----------

    async function saveRealInterview() {
        const content = $('realInterviewText').value.trim();
        if (!content) { tip('请先填写面试记录内容', true); return; }
        const btn = $('btnRealInterview');
        btn.disabled = true;
        try {
            const r = await api('/api/real-interview', 'POST', { content });
            tip('已保存真实面试记录 #' + r.recordId + '（已进 RAG 语料）');
            $('realInterviewText').value = '';
            loadRealInterview();
        } catch (e) { tip(e.message, true); }
        finally { btn.disabled = false; }
    }

    async function loadRealInterview() {
        try {
            const arr = await api('/api/real-interview');
            const box = $('realInterviewList');
            if (!arr.length) { box.innerHTML = '<p class="placeholder" style="margin:4px 0">暂无真实面试记录</p>'; return; }
            box.innerHTML = arr.slice().sort((a, b) => String(b.createdAt).localeCompare(String(a.createdAt))).map(r =>
                '<div class="recItem"><div class="recMeta">#' + r.recordId + ' · ' + fmtTime(r.createdAt) + '</div>' +
                '<div class="recContent">' + esc(r.content) + '</div></div>').join('');
        } catch (e) { tip('加载真实面试记录失败：' + e.message, true); }
    }

    // ---------- F2：面试逐轮回看 ----------

    async function viewTurns() {
        if (!state.reportSessionId) { tip('没有可回看的会话', true); return; }
        const panel = $('turnsReplay');
        const btn = $('btnViewTurns');
        // 已展开 → 收起
        if (!panel.hidden) { panel.hidden = true; panel.innerHTML = ''; btn.textContent = '逐轮回看'; return; }
        try {
            btn.textContent = '加载中…';
            const arr = await api('/api/interview/' + state.reportSessionId + '/turns');
            if (!arr.length) {
                panel.innerHTML = '<p class="placeholder">该会话暂无问答轮次记录</p>';
            } else {
                panel.innerHTML = arr.map(t =>
                    '<div class="turnItem">' +
                    '<div class="turnMeta">第 ' + t.seq + ' 轮' + (t.layer ? ' · ' + esc(t.layer) : '') + '</div>' +
                    '<div class="turnQ">' + esc(t.question) + '</div>' +
                    '<div class="turnA">' + esc(t.answer || '（未作答）') + '</div>' +
                    '</div>').join('');
            }
            panel.hidden = false;
            btn.textContent = '收起回看';
        } catch (e) { tip(e.message, true); }
        finally { btn.disabled = false; }
    }

    // ---------- F4：报告导出 Markdown ----------

    function exportReportMd() {
        if (!state.reportSessionId) { tip('没有可导出的报告', true); return; }
        const sid = state.reportSessionId;
        // 用 fetch 拿文本而不是直接 location.href，因为要带 Authorization 头
        fetch('/api/interview/' + sid + '/report.md', {
            headers: { 'Authorization': 'Bearer ' + state.token }
        }).then(resp => {
            if (!resp.ok) throw new Error('导出失败：' + resp.status);
            return resp.text();
        }).then(text => {
            const blob = new Blob([text], { type: 'text/markdown;charset=utf-8' });
            const url = URL.createObjectURL(blob);
            const a = document.createElement('a');
            a.href = url;
            a.download = 'interview-' + sid + '-report.md';
            document.body.appendChild(a);
            a.click();
            a.remove();
            setTimeout(() => URL.revokeObjectURL(url), 1000);
            tip('报告已导出为 Markdown');
        }).catch(e => tip(e.message, true));
    }

    // ---------- 错题本 ----------

    async function loadWeak() {
        try {
            const path = $('dueOnly').checked ? '/api/weak-point/due' : '/api/weak-point';
            const arr = await api(path);
            if (!arr || !arr.length) {
                $('weak').innerHTML = '<p class="placeholder">' +
                    ($('dueOnly').checked ? '今天没有到期的复习项。' : '错题本是空的（答崩的知识点会自动进来）。') + '</p>';
                return;
            }
            $('weak').innerHTML = arr.map((w) =>
                '<details class="wp" data-id="' + esc(w.weakPointId) + '"><summary>' +
                '<div class="wpTop"><b>' + esc(w.knowledgePoint) + '</b>' +
                '<span class="chip">错 ' + esc(w.wrongCount) + ' 次 · stage ' + esc(w.reviewStage) + '</span></div>' +
                '<div class="wpMeta">下次复习：' + esc(fmtTime(w.nextReviewAt)) + ' · 点击展开答崩现场</div>' +
                (w.status ? '<div class="wpStatus">现状：' + esc(w.status) + '</div>' : '') +
                '</summary><div class="wpBody">展开加载答崩现场…</div></details>').join('');
        } catch (e) {
            $('weak').innerHTML = '<p class="placeholder bad">加载失败：' + esc(e.message) + '</p>';
        }
    }

    /** 错题本展开时懒加载详情（每个 id 只拉一次）。 */
    async function loadWeakDetail(elDetails) {
        const id = elDetails.getAttribute('data-id');
        const body = elDetails.querySelector('.wpBody');
        if (!body || state.weakDetailLoaded.has(id)) return;
        state.weakDetailLoaded.add(id);
        body.textContent = '加载中…';
        try {
            const d = await api('/api/weak-point/' + id);
            body.innerHTML = renderWeakDetail(d);
        } catch (e) {
            state.weakDetailLoaded.delete(id);   // 加载失败允许重试
            body.textContent = '详情加载失败：' + e.message;
        }
    }

    function renderWeakDetail(d) {
        const s = d.latest;
        if (!s) {
            return '<div class="sceneEmpty">该条目是升级前记录的旧数据，没有定位到具体答题轮次；可参考上方「现状」复习。</div>';
        }
        const layer = s.layer ? ' · 追问 L' + esc(s.layer) : ' · 主问题';
        const head = '<div class="sceneHead">会话 #' + esc(s.sessionId) + ' · 第 ' + esc(s.turnSeq) + ' 轮' + layer +
            ' · 得分 <b>' + esc(s.score == null ? '—' : s.score) + '</b>/8</div>';
        const qa = '<div class="kv"><span class="k">原题</span><span class="v">' + esc(s.question) + '</span></div>' +
            '<div class="kv"><span class="k">我的回答</span><span class="v">' +
            esc(s.answer || '（未作答）') + '</span></div>';
        const r = s.review;
        const review = r ? (
            row('讲解', r.explanation) + row('纠正', r.correction) +
            row('知识补充', r.knowledgeSupplement) + row('示范回答', r.exemplarAnswer)) :
            '<div class="sceneEmpty">这场还没出 Critic 报告，暂无讲解/示范回答。</div>';
        return head + qa + review;
    }

    // ---------- SSE ----------

    function openStream(sessionId) {
        closeStream();
        // EventSource 不能自定义请求头：JWT 走 query 参数（R1-3 起唯一令牌）
        const url = '/api/interview/' + sessionId + '/events'
            + (state.token ? '?jwt=' + encodeURIComponent(state.token) : '');
        const es = new EventSource(url);
        state.es = es;
        let sseFails = 0;   // 连续连接失败计数：收到任何消息/onopen 归零，达上限停止重连（防 401 后无限打接口）

        es.onopen = () => { sseFails = 0; setConn(true, 'SSE 已连接'); };

        const on = (type, fn) => es.addEventListener(type, (e) => {
            let ev = {};
            try { ev = JSON.parse(e.data); } catch (err) { ev = {}; }
            if (e.data != null) sseFails = 0;   // 有数据=真消息；连接错误的 error 事件 e.data 为 null，不重置
            const key = type + ':' + (ev.turnSeq == null ? '' : ev.turnSeq);
            if (ev.turnSeq == null || !state.rendered.has(key)) {
                state.rendered.add(key);
                fn(ev);
            }
        });

        on('question', (ev) => { renderQuestion(ev); setState('等你作答'); });
        on('followup', (ev) => { renderQuestion(ev); setState('等你作答（追问）'); });
        on('score', renderScore);
        on('await_answer', () => {
            state.awaiting = true;
            $('answerText').disabled = false;
            $('btnAnswer').disabled = false;
            $('answerText').focus();
            setState('等你作答');
        });
        on('report', renderReport);
        on('error', (ev) => {
            // 注意：EventSource 自带的 onerror 也走这里（那是连接错误，e.data 为 null），用 code 字段区分业务错误
            if (ev && ev.code) {
                bubble('err', '异常 · ' + ev.code, esc(ev.message || ''));
            } else {
                sseFails++;
                if (sseFails >= 3) {
                    // 连续失败（后端重启 / 401 被拒 / 网络断）：浏览器默认会无限重连，这里主动停掉，避免空打接口
                    closeStream();
                    setConn(false, 'SSE 已断开');
                    tip('实时连接连续失败已停止，可重新开始一场面试', true);
                } else {
                    setConn(false, 'SSE 断开（自动重连 ' + sseFails + '/3）');
                }
            }
        });
        on('done', async () => {
            setConn(false, '本场结束');
            $('answerText').disabled = true;
            $('btnAnswer').disabled = true;
            $('btnEnd').disabled = true;
            closeStream();
            // 正常链路：答满题量后后端已自动推 report（report 事件先于 done），这里 reportRendered=true。
            // 兜底：若 report 事件缺失（异常/旧版本后端），补一次 /end——接口幂等，已有报告直接返回。
            if (!state.reportRendered && state.sessionId) {
                setState('Critic 出报告中…');
                try {
                    const r = await api('/api/interview/' + state.sessionId + '/end', 'POST');
                    renderReport(r);   // 成功时内部会把状态置为「已完成，报告已出」
                } catch (e) { tip('报告生成失败，可稍后在历史列表重试：' + e.message, true); }
            }
            if (!state.reportRendered) setState('已结束');
            loadWeak();     // 本场答崩的点已入错题本，顺手刷新
            loadHistory();  // 新一场进入历史列表
            loadQuota();    // 本场 token/服务费已实扣，同步额度（不重刷设置，避免动单选）
        });
    }

    function closeStream() {
        if (state.es) { state.es.close(); state.es = null; }
    }

    // ---------- 事件绑定 ----------

    function bind() {
        bindAuth();          // R1-1.5：登录/注册/登出
        bindUserSettings();  // R1-3：PLATFORM/BYOK 模式切换、自带 key 管理、额度展示

        // R2 额度耗尽弹窗：按钮关闭 / 点击遮罩空白处关闭
        $('btnQuotaOk').onclick = hideQuotaModal;
        $('quotaModal').addEventListener('click', (e) => {
            if (e.target === $('quotaModal')) hideQuotaModal();
        });

        // JD
        $('btnJd').onclick = async () => {
            const rawText = $('jdText').value.trim();
            if (!rawText) return tip('先粘贴 JD 正文', true);
            if (quotaBlocked()) { showQuotaModal(); return; }
            try {
                const arr = await api('/api/jd', 'POST', { rawText: rawText, sameTypeTag: $('jdTag').value.trim() || null });
                state.jdId = arr[0].jdId;
                $('jdSelect').value = String(state.jdId);
                tip('JD 已上传，keywords=' + (arr[0].keywords || []).join('、'));
                await refreshJd();
                loadQuota();  // JD 解析 token 已实扣，轻量同步额度
            } catch (e) { tip(e.message, true); }
        };
        $('btnRefreshJd').onclick = refreshJd;
        $('jdSelect').onchange = () => {
            state.jdId = $('jdSelect').value ? Number($('jdSelect').value) : null;
        };

        // 简历：上传新版本 + 历史版本下拉（选中即激活，与 JD 下拉同一交互范式）
        $('btnResume').onclick = async () => {
            const content = $('resumeText').value.trim();
            if (!content) return tip('简历内容为空', true);
            if (quotaBlocked()) { showQuotaModal(); return; }
            try {
                const r = await api('/api/resume', 'POST', { content: content });
                tip('简历新版本 #' + r.resumeId + ' 已上传并入 RAG 语料');
                $('resumeText').value = '';
                state.resumeId = r.resumeId;
                await refreshResume();
                loadQuota();  // 简历切块向量化 token 已实扣
            } catch (e) { tip(e.message, true); }
        };
        $('btnRefreshResume').onclick = refreshResume;
        $('resumeSelect').onchange = async () => {
            const id = $('resumeSelect').value;
            state.resumeId = id ? Number(id) : null;
            if (!id) return;
            try {
                await api('/api/resume/' + id + '/activate', 'POST');
                tip('已切换当前简历版本 #' + id);
                await refreshResume();
            } catch (e) { tip(e.message, true); }
        };
        $('btnDoc').onclick = async () => {
            const title = $('docTitle').value.trim();
            const content = $('docText').value.trim();
            if (!title || !content) return tip('项目文档需要标题 + 内容', true);
            if (quotaBlocked()) { showQuotaModal(); return; }
            try { await api('/api/project-doc', 'POST', { title: title, content: content }); tip('项目文档已上传（质疑官弹药库 +1）'); loadQuota(); }
            catch (e) { tip(e.message, true); }
        };
        $('btnNote').onclick = async () => {
            const content = $('noteText').value.trim();
            if (!content) return tip('笔记内容为空', true);
            if (quotaBlocked()) { showQuotaModal(); return; }
            try { await api('/api/note', 'POST', { content: content }); tip('笔记已存入 RAG 语料'); loadQuota(); }
            catch (e) { tip(e.message, true); }
        };
        // F1：真实面试记录
        $('btnRealInterview').onclick = saveRealInterview;
        $('btnRefreshRealInterview').onclick = loadRealInterview;
        // F2/F4：报告操作
        $('btnExportMd').onclick = exportReportMd;
        $('btnViewTurns').onclick = viewTurns;

        // 开始
        $('btnStart').onclick = async () => {
            if (!state.jdId) return tip('先上传或选择一个 JD', true);
            if (quotaBlocked()) { showQuotaModal(); return; }
            const roles = Array.from(document.querySelectorAll('.roleChk:checked')).map((c) => c.value);
            const maxQuestions = Number($('maxQuestions').value) || 8;
            tip('正在解析 JD + 检索资料，出首题…');
            try {
                const r = await api('/api/interview/start', 'POST',
                    { jdId: state.jdId, resumeId: state.resumeId, roles: roles, maxQuestions: maxQuestions });
                state.sessionId = r.sessionId;
                state.reportRendered = false;
                state.rendered.clear();
                clearChat();
                $('report').innerHTML = '<p class="placeholder">面试进行中，结束后出报告…</p>';
                $('btnExportMd').disabled = true;
                $('btnViewTurns').disabled = true;
                $('btnViewTurns').textContent = '逐轮回看';
                $('turnsReplay').hidden = true;
                $('turnsReplay').innerHTML = '';
                $('btnEnd').disabled = false;
                $('answerText').disabled = true;
                $('btnAnswer').disabled = true;
                setState('等待首题（SSE）');
                // 首题由 SSE 重放渲染（见文件头说明），这里不重复渲染 firstQuestion
                openStream(r.sessionId);
                tip('会话 #' + r.sessionId + ' 已开始');
                loadQuota();  // 出题（含 RAG 检索 embedding）token 已实扣
            } catch (e) {
                if (e.code === 'QUOTA_EXHAUSTED') loadQuota();
                tip(e.message, true);
            }
        };

        // 作答
        const submit = async () => {
            const text = $('answerText').value.trim();
            if (!text || !state.sessionId) return;
            renderAnswer(text);
            $('answerText').value = '';
            $('answerText').disabled = true;
            $('btnAnswer').disabled = true;
            state.awaiting = false;
            setState('判分中…');
            try {
                await api('/api/interview/' + state.sessionId + '/answer', 'POST', { answer: text });
                loadQuota();  // 判分/追问 token 已实扣，轻量同步额度
            } catch (e) {
                if (e.code === 'QUOTA_EXHAUSTED') loadQuota();
                tip(e.message, true);
                setState('作答失败，可重试');
                $('answerText').disabled = false;
                $('btnAnswer').disabled = false;
            }
        };
        $('btnAnswer').onclick = submit;
        $('answerText').addEventListener('keydown', (e) => {
            if ((e.ctrlKey || e.metaKey) && e.key === 'Enter') submit();
        });

        // 结束
        $('btnEnd').onclick = async () => {
            if (!state.sessionId) return;
            setState('Critic 出报告中…');
            try {
                const r = await api('/api/interview/' + state.sessionId + '/end', 'POST');
                renderReport(r);   // 响应与 SSE 的 report 只渲染一次（flag 去重）
                loadWeak();
                loadHistory();     // 报告已出，历史条目变为可点开
                loadQuota();       // Critic token 已实扣，轻量同步额度
            } catch (e) { tip(e.message, true); }
        };

        // 历史
        $('btnHistory').onclick = loadHistory;
        // 事件委托：历史条目点击回看报告（列表是动态渲染的，不能逐条绑）
        $('history').addEventListener('click', (e) => {
            const btn = e.target.closest('.histItem');
            if (btn && !btn.disabled) viewHistoryReport(btn.getAttribute('data-sid'));
        });

        // 错题本
        $('btnWeak').onclick = loadWeak;
        $('dueOnly').onchange = loadWeak;
        // toggle 事件会冒泡：展开时懒加载该条详情
        $('weak').addEventListener('toggle', (e) => {
            if (e.target.tagName === 'DETAILS' && e.target.open) loadWeakDetail(e.target);
        }, true);
    }

    async function refreshJd() {
        try {
            const arr = await api('/api/jd');
            const sel = $('jdSelect');
            const keep = String(state.jdId || '');
            sel.innerHTML = '<option value="">— 选择已有 JD —</option>' + arr.map((j) =>
                '<option value="' + j.jdId + '">#' + j.jdId + ' ' + esc(j.jobType || '') +
                (j.sameTypeTag ? ' · ' + esc(j.sameTypeTag) : '') + '</option>').join('');
            if (keep) sel.value = keep;
        } catch (e) { /* 列表刷新失败不打扰用户 */ }
    }

    async function refreshResume() {
        try {
            const arr = (await api('/api/resume')).slice().sort((a, b) => b.resumeId - a.resumeId);
            const sel = $('resumeSelect');
            sel.innerHTML = '<option value="">— 选择历史简历 —</option>' + arr.map((r) =>
                '<option value="' + r.resumeId + '">#' + r.resumeId + ' · ' + fmtTime(r.createdAt) +
                (r.isActive ? ' · 当前' : '') + '</option>').join('');
            if (state.resumeId) {
                sel.value = String(state.resumeId);
            } else {
                // 首次加载：默认选中「当前」版本（无则最新一条），免去每次手选
                const pick = arr.find((r) => r.isActive) || arr[0];
                if (pick) { state.resumeId = pick.resumeId; sel.value = String(pick.resumeId); }
            }
        } catch (e) { /* 列表刷新失败不打扰用户 */ }
    }

    // ---------- 用户登录 / 注册 / 登出（R1-1.5 模态弹窗） ----------

    /** 弹窗内红色错误条（所有弹窗阶段的错误都走这里——#tip 在隐藏的主界面里，登录前不可见）。 */
    function showAuthError(message) {
        const el = $('authError');
        el.textContent = message || '';
        el.hidden = !message;
    }

    function clearAuthError() { showAuthError(''); }

    /**
     * 显示登录弹窗。
     * @param tab 'login' | 'register'，默认登录 Tab
     * @param error 可选错误文案（如登录过期被强制下线时带入）
     */
    function showAuthModal(tab, error) {
        switchAuthTab(tab || 'login');
        $('loginPassword').value = '';
        $('regPassword').value = '';
        $('regPassword2').value = '';
        showAuthError(error || '');
        $('authModal').hidden = false;
        setTimeout(() => $(tab === 'register' ? 'regUsername' : 'loginUsername').focus(), 0);
    }

    /** 只切 Tab（active 类 + 表单 hidden），不重建 DOM，保证密码框按钮的一次性绑定不丢。 */
    function switchAuthTab(tab) {
        const isLogin = tab === 'login';
        $('tabLogin').classList.toggle('active', isLogin);
        $('tabRegister').classList.toggle('active', !isLogin);
        $('tabLogin').setAttribute('aria-selected', String(isLogin));
        $('tabRegister').setAttribute('aria-selected', String(!isLogin));
        $('loginForm').hidden = !isLogin;
        $('registerForm').hidden = isLogin;
        clearAuthError();
    }

    /** 按钮 loading 态：禁用 + 文案切换，防重复提交；finally 里必须调 setAuthLoading(btn, false)。 */
    function setAuthLoading(btn, on, loadingText) {
        if (on) {
            btn.dataset.originText = btn.textContent;
            btn.disabled = true;
            btn.textContent = loadingText;
        } else {
            btn.disabled = false;
            btn.textContent = btn.dataset.originText || btn.textContent;
        }
    }

    /** 登录/注册成功的统一收尾：进主界面，加载模型调用方式 + 业务数据（R1-3 起无保险箱门禁）。 */
    async function handleAuthSuccess(r) {
        state.token = r.token;
        state.username = r.username;

        // 用户身份用 textContent 注入（用户名是服务端数据，不拼 innerHTML）
        $('authUsername').textContent = r.username || '—';
        $('userAvatar').textContent = (r.username || '?').charAt(0).toUpperCase();
        $('userArea').hidden = false;
        $('appShell').hidden = false;
        $('authModal').hidden = true;
        clearAuthError();
        tip(r.username ? '欢迎，' + r.username : '登录成功');

        loadUserSettings();
        loadBusinessData();
    }

    /** 绑定登录弹窗：Tab 切换、两个 form 的 submit、登出。 */
    function bindAuth() {
        $('tabLogin').onclick = () => switchAuthTab('login');
        $('tabRegister').onclick = () => switchAuthTab('register');

        // 登录（form submit 天然支持 Enter；必须 preventDefault，否则原生提交会刷新页面）
        $('loginForm').onsubmit = async (e) => {
            e.preventDefault();
            const btn = $('btnLogin');
            if (btn.disabled) return;
            const u = $('loginUsername').value.trim();
            const p = $('loginPassword').value;
            if (!u) return showAuthError('请输入用户名');
            if (!p) return showAuthError('请输入密码');
            setAuthLoading(btn, true, '登录中…');
            try {
                const r = await api('/api/auth/login', 'POST', { username: u, password: p });
                await handleAuthSuccess(r);
            } catch (err) {
                showAuthError(err.message);   // 401 已在 api() 转成人话
            } finally {
                setAuthLoading(btn, false);
            }
        };

        // 注册（成功即自动登录：register 直接返回 JWT）
        $('registerForm').onsubmit = async (e) => {
            e.preventDefault();
            const btn = $('btnRegister');
            if (btn.disabled) return;
            const u = $('regUsername').value.trim();
            const p = $('regPassword').value;
            const p2 = $('regPassword2').value;
            if (!u || u.length < 4 || u.length > 32) return showAuthError('用户名需 4-32 个字符');
            if (!p || p.length < 6 || p.length > 128) return showAuthError('密码需 6-128 个字符');
            if (p !== p2) return showAuthError('两次输入的密码不一致');
            setAuthLoading(btn, true, '注册中…');
            try {
                const r = await api('/api/auth/register', 'POST', { username: u, password: p });
                await handleAuthSuccess(r);
            } catch (err) {
                showAuthError(friendlyRegisterError(err));
            } finally {
                setAuthLoading(btn, false);
            }
        };

        // 输入时清掉旧错误
        ['loginUsername', 'loginPassword', 'regUsername', 'regPassword', 'regPassword2'].forEach((id) => {
            $(id).addEventListener('input', clearAuthError);
        });

        // Enter 显式提交（不依赖浏览器隐式提交规则；requestSubmit 会走 onsubmit 的 preventDefault 流程）
        [['loginUsername', 'loginForm'], ['loginPassword', 'loginForm'],
         ['regUsername', 'registerForm'], ['regPassword', 'registerForm'],
         ['regPassword2', 'registerForm']].forEach(([inputId, formId]) => {
            $(inputId).addEventListener('keydown', (e) => {
                if (e.key === 'Enter') {
                    e.preventDefault();
                    $(formId).requestSubmit();
                }
            });
        });

        // 登出：清 JWT（只在 JS 内存）+ 停 SSE + 刷新页面回登录弹窗，状态最干净
        $('btnLogout').onclick = () => {
            state.token = null;
            state.username = null;
            closeStream();
            location.reload();
        };
    }

    /** 注册接口错误文案友好化：409 用户名占用、400 服务端 @Valid 技术串兜底，其余透传。 */
    function friendlyRegisterError(err) {
        const m = err && err.message ? err.message : '';
        if (m.indexOf('409') >= 0 || m.indexOf('占用') >= 0 || m.indexOf('已被注册') >= 0) {
            return '用户名已被注册，换一个试试';
        }
        if (m.indexOf('400') >= 0) {
            return '提交内容不符合要求：用户名需 4-32 字符，密码需 6-128 字符';
        }
        return m || '注册失败，请稍后重试';
    }

    // ---------- 模型调用方式（R1-3：平台额度 PLATFORM / 自带 Key BYOK） ----------

    function showKeyMsg(message, bad) {
        const el = $('keyMsg');
        el.textContent = message || '';
        el.hidden = !message;
        el.className = 'keyMsg' + (bad ? ' bad' : 'ok');
    }

    function setBtnLoading(btn, on, loadingText) {
        if (on) {
            btn.dataset.originText = btn.textContent;
            btn.disabled = true;
            btn.textContent = loadingText;
        } else {
            btn.disabled = false;
            btn.textContent = btn.dataset.originText || btn.textContent;
        }
    }

    function bindUserSettings() {
        // 密码框「显示/隐藏」：登录/注册 + BYOK key 输入共用
        document.querySelectorAll('.pwdToggle').forEach((btn) => {
            btn.onclick = () => {
                const input = $(btn.getAttribute('data-target'));
                const show = input.type === 'password';
                input.type = show ? 'text' : 'password';
                btn.textContent = show ? '隐藏' : '显示';
                input.focus();
            };
        });

        // 模式单选：PLATFORM 直接切；BYOK 已存 key 直接切，没存则展开录入框（服务端也会 409 兜底）
        document.querySelectorAll('input[name="keyMode"]').forEach((radio) => {
            radio.addEventListener('change', async () => {
                if (!radio.checked) return;
                showKeyMsg('');
                const mode = radio.value;
                $('byokBox').hidden = (mode !== 'BYOK');
                if (mode === 'PLATFORM') {
                    try {
                        await api('/api/user/settings', 'PUT', { keyMode: 'PLATFORM' });
                        showKeyMsg('已切回平台额度（不扣服务点）');
                        await loadUserSettings();
                    } catch (e) {
                        showKeyMsg(e.message, true);
                        await loadUserSettings();   // 单选 UI 回滚到服务端真实状态
                    }
                } else {
                    try {
                        await api('/api/user/settings', 'PUT', { keyMode: 'BYOK' });
                        showKeyMsg('已切换为自带 Key 模式（每次调用扣 1 服务点）');
                        await loadUserSettings();
                    } catch (e) {
                        if (e.code === 'KEY_NOT_CONFIGURED') {
                            // 服务端仍停在 PLATFORM，但 UI 要停在 BYOK 并展开录入框：
                            // 不能 loadUserSettings()，否则会按服务端状态把单选和输入框拉回去
                            document.querySelector('input[name="keyMode"][value="BYOK"]').checked = true;
                            document.querySelector('input[name="keyMode"][value="PLATFORM"]').checked = false;
                            $('byokBox').hidden = false;
                            showKeyMsg('请先填写并保存你的 DeepSeek API Key');
                        } else {
                            showKeyMsg(e.message, true);
                            await loadUserSettings();
                        }
                    }
                }
            });
        });

        // R3-2 对话模型唯一入口：回答输入框右下角紧凑下拉（chatProvider: DEEPSEEK / QWEN）。
        // 切换成功后 loadUserSettings() → renderChatProvider() 回显；失败回滚到服务端状态。
        $('chatProviderBtn').addEventListener('click', (e) => {
            e.stopPropagation();
            const list = $('chatProviderList');
            list.hidden = false;
            $('chatProviderBtn').setAttribute('aria-expanded', 'true');
        });
        document.addEventListener('click', (e) => {
            if (!$('chatProviderList').hidden && !$('chatProviderMenu').contains(e.target)) closeProviderMenu();
        });
        document.querySelectorAll('.chatProviderOpt').forEach((opt) => {
            opt.addEventListener('mouseenter', () => {
                if (opt.getAttribute('aria-selected') !== 'true') opt.style.background = 'var(--panel2)';
            });
            opt.addEventListener('mouseleave', () => {
                if (opt.getAttribute('aria-selected') !== 'true') opt.style.background = 'transparent';
            });
            opt.addEventListener('click', async () => {
                const value = opt.dataset.provider;
                closeProviderMenu();
                try {
                    await api('/api/user/settings', 'PUT', { chatProvider: value });
                    tip(value === 'QWEN' ? '已切换为通义千问（走平台 key，按积分扣）' : '已切换为 DeepSeek');
                    await loadUserSettings();
                } catch (err) {
                    tip(err.message, true);
                    await loadUserSettings();   // 回滚到服务端真实状态
                }
            });
        });

        // 保存（并切换）：先验活加密落库，成功后显式切 BYOK
        $('btnByokSave').onclick = async () => {
            const key = $('byokKey').value.trim();
            if (!key) return showKeyMsg('请输入 DeepSeek API Key（sk- 开头）', true);
            if (key.indexOf('sk-') !== 0) return showKeyMsg('DeepSeek API Key 应以 sk- 开头', true);
            const btn = $('btnByokSave');
            setBtnLoading(btn, true, '验活并保存…');
            try {
                await api('/api/user/api-key', 'PUT', { apiKey: key });
                await api('/api/user/settings', 'PUT', { keyMode: 'BYOK' });
                $('byokKey').value = '';
                showKeyMsg('已保存并切换到自带 Key 模式');
                tip('自带 Key 已生效');
                await loadUserSettings();
            } catch (e) {
                showKeyMsg(e.message, true);
            } finally {
                setBtnLoading(btn, false);
            }
        };

        // 删除：服务端同时强制回退 PLATFORM（避免 BYOK 无 key 的死状态）
        $('btnByokDelete').onclick = async () => {
            const btn = $('btnByokDelete');
            setBtnLoading(btn, true, '删除中…');
            try {
                await api('/api/user/api-key', 'DELETE');
                $('byokKey').value = '';
                showKeyMsg('已删除自带 Key，切回平台额度');
                tip('已切回平台额度');
                await loadUserSettings();
            } catch (e) {
                showKeyMsg(e.message, true);
            } finally {
                setBtnLoading(btn, false);
            }
        };
    }

    // R3-2 对话模型回显：右下角下拉触发文案 + 选中项高亮/✓（唯一入口）
    function renderChatProvider(value) {
        const v = value === 'QWEN' ? 'QWEN' : 'DEEPSEEK';
        $('chatProviderLabel').textContent = v === 'QWEN' ? '通义千问' : 'DeepSeek';
        document.querySelectorAll('.chatProviderOpt').forEach((opt) => {
            const sel = opt.dataset.provider === v;
            opt.setAttribute('aria-selected', sel ? 'true' : 'false');
            opt.style.background = sel ? 'var(--panel2)' : 'transparent';
            opt.style.fontWeight = sel ? '600' : '400';
            opt.querySelector('.chatProviderTick').hidden = !sel;
        });
    }
    function closeProviderMenu() {
        $('chatProviderList').hidden = true;
        $('chatProviderBtn').setAttribute('aria-expanded', 'false');
    }

    /** 拉取模式 / key 状态 / 额度 / 平台 env 就绪情况，重绘卡片（登录后、每次切换、402 后都调）。 */
    async function loadUserSettings() {
        let settings, keyInfo, quota;
        try {
            [settings, keyInfo, quota] = await Promise.all([
                api('/api/user/settings'),
                api('/api/user/api-key'),
                api('/api/user/quota')
            ]);
        } catch (e) {
            $('keyBadge').textContent = '状态未知';
            $('keyBadge').className = 'keybadge bad';
            return;
        }

        const mode = settings.keyMode === 'BYOK' ? 'BYOK' : 'PLATFORM';
        state.keyMode = mode;

        // 单选与服务端状态对齐（切模式失败时也靠它回滚）
        document.querySelectorAll('input[name="keyMode"]').forEach((radio) => {
            radio.checked = (radio.value === mode);
        });

        // R3-2 对话模型回显：右下角下拉对齐服务端状态（缺字段/异常值安全回退 DEEPSEEK）
        renderChatProvider(settings.chatProvider);

        const badge = $('keyBadge');
        if (mode === 'BYOK') {
            badge.textContent = keyInfo.hasKey ? '自带 Key · sk-…' + esc(keyInfo.last4 || '') : '自带 Key（未保存）';
            badge.className = 'keybadge ' + (keyInfo.hasKey ? 'ok' : 'bad');
        } else {
            badge.textContent = '平台额度';
            badge.className = 'keybadge ok';
        }

        renderQuota(quota);

        $('byokBox').hidden = (mode !== 'BYOK');
        $('btnByokDelete').hidden = !keyInfo.hasKey;
        $('byokStatus').textContent = keyInfo.hasKey
            ? '已保存 sk-…' + (keyInfo.last4 || '') + '（输入新 Key 可更换）'
            : '尚未保存 Key';

        // 平台 env key 缺失时给一行引导（best-effort：deps 探活失败不打扰）
        try {
            const deps = await api('/api/health/deps');
            if (mode === 'PLATFORM' && deps && deps.deepseek && deps.deepseek.configured === false) {
                showKeyMsg('平台未配置 DEEPSEEK_API_KEY：请选「自带 Key」，或让运维配置环境变量', true);
            }
        } catch (e) { /* 探活接口失败不影响主流程 */ }
    }

    /** token 额度数字格式化：≥1 万显示 x.x 万，避免一行长整数。 */
    function formatTokens(n) {
        n = Number(n) || 0;
        if (n >= 10000) return (n / 10000).toFixed(1).replace(/\.0$/, '') + ' 万';
        return String(n);
    }

    /**
     * 积分换算（仅前端展示，后端仍以 token 记账）：1 积分 = 500 token（50 万 token = 1000 积分）。
     * points 量级小（通常几百~几千），不套用「万」逻辑；整数去 .0，带小数保留 1 位并加千位分隔。
     */
    function formatPoints(tokens) {
        const pts = (Number(tokens) || 0) / 500;
        const rounded = Math.round(pts * 10) / 10;
        return rounded.toLocaleString('zh-CN', { maximumFractionDigits: 1 });
    }

    /** 只重绘额度行（字段：R2 token 语义 totalTokens/usedTokens/remainingTokens，前端换算为积分展示）。 */
    function renderQuota(quota) {
        state.quota = quota;  // 缓存给前置拦截用；null/异常时不更新（避免误判耗尽放行）
        $('quotaRemaining').textContent = formatPoints(quota.remainingTokens);
        $('quotaTotal').textContent = formatPoints(quota.totalTokens);
        $('quotaUsed').textContent = formatPoints(quota.usedTokens);
    }

    /**
     * R2 前置拦截：额度视图已知且余额为 0 时，点击收费入口直接拦（不发请求）。
     * state.quota 为 null（登录后 settings 尚未返回 / 查询失败）时不拦——
     * 交给服务端 402 兜底弹窗，避免状态未知时误拦。
     */
    function quotaBlocked() {
        return !!state.quota && Number(state.quota.remainingTokens) <= 0;
    }

    /** 弹出额度耗尽提示窗；serverMessage 为服务端 402 文案时原样展示，否则用默认引导。 */
    function showQuotaModal(serverMessage) {
        $('quotaModalUser').textContent = state.username || '—';
        $('quotaModalRemain').textContent = state.quota ? formatPoints(state.quota.remainingTokens) : '0';
        $('quotaModalMsg').textContent = serverMessage
            || '上传 JD、简历 / 笔记 / 项目文档，以及开始模拟面试都需要消耗积分。请联系管理员为账号充值后继续。';
        $('quotaModal').hidden = false;
    }

    function hideQuotaModal() { $('quotaModal').hidden = true; }

    /**
     * 轻量刷额度：只打 /api/user/quota，不重拉 settings/key/deps。
     * JD 上传/start/answer/end 后调用——面试中绝不能 loadUserSettings()，
     * 那会按服务端状态重置 PLATFORM/BYOK 单选与输入框。
     */
    async function loadQuota() {
        try {
            renderQuota(await api('/api/user/quota'));
        } catch (e) { /* 额度刷新失败不挡主流程 */ }
    }

    /** 登录后需要加载的业务数据。 */
    function loadBusinessData() {
        refreshJd();
        refreshResume();
        loadWeak();
        loadHistory();
        loadRealInterview();  // F1：真实面试记录列表
    }

    // ---------- 启动 ----------
    // R1-3 起只有 JWT 一道门禁（旧保险箱已删除），token 只在 JS 内存，刷新必失。
    // 静态 HTML 初始即「#authModal 可见、#appShell hidden」，无首屏闪烁；
    // 这里只绑事件、不调任何业务接口（无 JWT 会 401）。
    // 登录成功后 handleAuthSuccess 加载模型调用方式 + 业务数据。
    bind();
})();
