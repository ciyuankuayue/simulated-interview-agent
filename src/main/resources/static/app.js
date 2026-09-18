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
        jdId: null,
        sessionId: null,
        es: null,
        awaiting: false,
        reportRendered: false,
        rendered: new Set()   // 事件去重：type + turnSeq
    };

    // ---------- 工具 ----------

    async function api(path, method, body) {
        const opt = { method: method || 'GET', headers: { 'Content-Type': 'application/json; charset=utf-8' } };
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
            throw new Error('HTTP ' + res.status + ' · ' + msg);
        }
        return data;
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
        const html =
            '<div class="scoreRow"><span class="scoreNum">' + esc(s) + '</span><span class="scoreDen">/ 8</span>' +
            '<span class="bar"><i style="width:' + pct + '%"></i></span></div>' +
            (ev.oneLineCorrection ? '<div class="correction">一句纠正：' + esc(ev.oneLineCorrection) + '</div>' : '');
        bubble('score', '本轮评分（第 ' + ev.turnSeq + ' 轮）', html);
    }

    function renderAnswer(text) {
        bubble('me', '我', esc(text));
    }

    // ---------- 报告 ----------

    function renderReport(r) {
        if (state.reportRendered) return;   // end 的响应与 SSE 的 report 事件只渲染一次
        state.reportRendered = true;

        const turns = Array.isArray(r.perTurnReview) ? r.perTurnReview : [];
        const collapsed = Array.isArray(r.collapsedPoints) ? r.collapsedPoints : [];
        const issues = Array.isArray(r.expressionIssues) ? r.expressionIssues : [];
        const reminders = Array.isArray(r.reminders) ? r.reminders : [];

        const turnHtml = turns.map((t) =>
            '<details class="turn"><summary>第 ' + esc(t.turnSeq) + ' 轮 · ' + esc(t.score) + '/8 · ' +
            esc(String(t.question || '').slice(0, 40)) + '</summary>' +
            row('讲解', t.explanation) + row('纠正', t.correction) +
            row('知识补充', t.knowledgeSupplement) + row('示范回答', t.exemplarAnswer) +
            '</details>').join('');

        $('report').innerHTML =
            '<div class="match">匹配度 <b>' + esc(r.matchScore == null ? '—' : r.matchScore) + '</b> / 100</div>' +
            (turnHtml ? '<h3>逐轮讲解</h3>' + turnHtml : '') +
            list('答崩点', collapsed) +
            (issues.length ? '<h3>表述问题</h3>' + issues.map((i) =>
                '<div class="issue"><b>' + esc(i.issue) + '</b><div>' + esc(i.guide) + '</div></div>').join('') : '') +
            (r.overallNotes ? '<h3>整体注意事项</h3><p class="notes">' + esc(r.overallNotes) + '</p>' : '') +
            list('必要提醒', reminders);

        setState('已完成，报告已出');
    }

    function row(label, value) {
        return value ? '<div class="kv"><span class="k">' + label + '</span><span class="v">' + esc(value) + '</span></div>' : '';
    }

    function list(title, arr) {
        if (!arr || !arr.length) return '';
        return '<h3>' + title + '</h3><ul class="lst">' + arr.map((x) => '<li>' + esc(x) + '</li>').join('') + '</ul>';
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
                '<div class="wp"><div class="wpTop"><b>' + esc(w.knowledgePoint) + '</b>' +
                '<span class="chip">错 ' + esc(w.wrongCount) + ' 次 · stage ' + esc(w.reviewStage) + '</span></div>' +
                '<div class="wpMeta">下次复习：' + esc(fmtTime(w.nextReviewAt)) + '</div>' +
                (w.status ? '<div class="wpStatus">' + esc(w.status) + '</div>' : '') + '</div>').join('');
        } catch (e) {
            $('weak').innerHTML = '<p class="placeholder bad">加载失败：' + esc(e.message) + '</p>';
        }
    }

    // ---------- SSE ----------

    function openStream(sessionId) {
        closeStream();
        const es = new EventSource('/api/interview/' + sessionId + '/events');
        state.es = es;

        es.onopen = () => setConn(true, 'SSE 已连接');

        const on = (type, fn) => es.addEventListener(type, (e) => {
            let ev = {};
            try { ev = JSON.parse(e.data); } catch (err) { ev = {}; }
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
            // 注意：EventSource 自带的 onerror 也走这里（那是连接错误），用 code 字段区分业务错误
            if (ev && ev.code) {
                bubble('err', '异常 · ' + ev.code, esc(ev.message || ''));
            } else {
                setConn(false, 'SSE 断开（会自动重连）');
            }
        });
        on('done', () => {
            setConn(false, '本场结束');
            setState('已结束');
            $('answerText').disabled = true;
            $('btnAnswer').disabled = true;
            $('btnEnd').disabled = true;
            closeStream();
            loadWeak();     // 本场答崩的点已入错题本，顺手刷新
        });
    }

    function closeStream() {
        if (state.es) { state.es.close(); state.es = null; }
    }

    // ---------- 事件绑定 ----------

    function bind() {
        // JD
        $('btnJd').onclick = async () => {
            const rawText = $('jdText').value.trim();
            if (!rawText) return tip('先粘贴 JD 正文', true);
            try {
                const arr = await api('/api/jd', 'POST', { rawText: rawText, sameTypeTag: $('jdTag').value.trim() || null });
                state.jdId = arr[0].jdId;
                $('jdSelect').value = String(state.jdId);
                tip('JD 已上传，keywords=' + (arr[0].keywords || []).join('、'));
                await refreshJd();
            } catch (e) { tip(e.message, true); }
        };
        $('btnRefreshJd').onclick = refreshJd;
        $('jdSelect').onchange = () => {
            state.jdId = $('jdSelect').value ? Number($('jdSelect').value) : null;
        };

        // 资料
        $('btnResume').onclick = async () => {
            const content = $('resumeText').value.trim();
            if (!content) return tip('简历内容为空', true);
            try { await api('/api/resume', 'POST', { content: content }); tip('简历已上传'); }
            catch (e) { tip(e.message, true); }
        };
        $('btnDoc').onclick = async () => {
            const title = $('docTitle').value.trim();
            const content = $('docText').value.trim();
            if (!title || !content) return tip('项目文档需要标题 + 内容', true);
            try { await api('/api/project-doc', 'POST', { title: title, content: content }); tip('项目文档已上传（质疑官弹药库 +1）'); }
            catch (e) { tip(e.message, true); }
        };
        $('btnNote').onclick = async () => {
            const content = $('noteText').value.trim();
            if (!content) return tip('笔记内容为空', true);
            try { await api('/api/note', 'POST', { content: content }); tip('笔记已存入 RAG 语料'); }
            catch (e) { tip(e.message, true); }
        };

        // 开始
        $('btnStart').onclick = async () => {
            if (!state.jdId) return tip('先上传或选择一个 JD', true);
            const roles = Array.from(document.querySelectorAll('.roleChk:checked')).map((c) => c.value);
            tip('正在解析 JD + 检索资料，出首题…');
            try {
                const r = await api('/api/interview/start', 'POST', { jdId: state.jdId, roles: roles });
                state.sessionId = r.sessionId;
                state.reportRendered = false;
                state.rendered.clear();
                clearChat();
                $('report').innerHTML = '<p class="placeholder">面试进行中，结束后出报告…</p>';
                $('btnEnd').disabled = false;
                $('answerText').disabled = true;
                $('btnAnswer').disabled = true;
                setState('等待首题（SSE）');
                // 首题由 SSE 重放渲染（见文件头说明），这里不重复渲染 firstQuestion
                openStream(r.sessionId);
                tip('会话 #' + r.sessionId + ' 已开始');
            } catch (e) { tip(e.message, true); }
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
            } catch (e) {
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
            } catch (e) { tip(e.message, true); }
        };

        // 错题本
        $('btnWeak').onclick = loadWeak;
        $('dueOnly').onchange = loadWeak;
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

    // ---------- 启动 ----------
    bind();
    refreshJd();
    loadWeak();
})();
