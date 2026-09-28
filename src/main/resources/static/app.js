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
        resumeId: null,
        sessionId: null,
        es: null,
        awaiting: false,
        reportRendered: false,
        rendered: new Set(),  // 事件去重：type + turnSeq
        weakDetailLoaded: new Set(),  // 错题本详情懒加载：已拉取过的 weakPointId
        // 密码模式的访问令牌：只放内存（不落 localStorage）——刷新/换标签页即失效，必须重新输密码
        vaultToken: null
    };

    // ---------- 工具 ----------

    async function api(path, method, body) {
        const headers = { 'Content-Type': 'application/json; charset=utf-8' };
        // 密码模式：每个业务请求带访问令牌（白名单接口不带也无妨）
        if (state.vaultToken) headers['X-Vault-Token'] = state.vaultToken;
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
            // 401（令牌失效/被锁定/其他设备踢下线）：清本地令牌并切回解锁框。
            if (res.status === 401 && path !== '/api/setup/unlock') {
                state.vaultToken = null;
                loadKeyStatus();
                throw new Error('请先输入密码解锁');
            }
            // /unlock 的 401 是「密码错误」，直接抛服务端给的人话，不带 HTTP 前缀
            if (res.status === 401) {
                throw new Error(msg);
            }
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
            renderReport(r);
            setState('历史 #' + sessionId + ' 报告');
            tip('已载入历史会话 #' + sessionId + ' 的报告');
        } catch (e) { tip(e.message, true); }
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
        // EventSource 不能自定义请求头：密码模式的令牌走 query 参数（过滤器同样认可）
        const url = '/api/interview/' + sessionId + '/events'
            + (state.vaultToken ? '?vaultToken=' + encodeURIComponent(state.vaultToken) : '');
        const es = new EventSource(url);
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
        });
    }

    function closeStream() {
        if (state.es) { state.es.close(); state.es = null; }
    }

    // ---------- 事件绑定 ----------

    function bind() {
        bindKeySetup();

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

        // 简历：上传新版本 + 历史版本下拉（选中即激活，与 JD 下拉同一交互范式）
        $('btnResume').onclick = async () => {
            const content = $('resumeText').value.trim();
            if (!content) return tip('简历内容为空', true);
            try {
                const r = await api('/api/resume', 'POST', { content: content });
                tip('简历新版本 #' + r.resumeId + ' 已上传并入 RAG 语料');
                $('resumeText').value = '';
                state.resumeId = r.resumeId;
                await refreshResume();
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
                loadHistory();     // 报告已出，历史条目变为可点开
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

    // ---------- 模型密钥（页面保险箱，免终端环境变量） ----------

    function bindKeySetup() {
        // 点状态徽标 = 展开修改表单（门禁未通过时不响应）
        $('keyBadge').onclick = () => {
            const s = state.keyStatus;
            if (s && s.passwordProtected && !state.vaultToken) return;
            $('keyFormBox').hidden = false;
            $('btnKeyCancel').hidden = false;
        };

        // 密码框「显示/隐藏」切换（解锁框 + 保存表单两处）
        document.querySelectorAll('.pwdToggle').forEach((btn) => {
            btn.onclick = () => {
                const input = $(btn.getAttribute('data-target'));
                const show = input.type === 'password';
                input.type = show ? 'text' : 'password';
                btn.textContent = show ? '隐藏' : '显示';
                input.focus();
            };
        });

        $('btnKeySave').onclick = async () => {
            const ds = $('ksDeep').value.trim();
            const dash = $('ksDash').value.trim();
            const pwd = $('ksPass').value.trim();
            if (!ds && !dash && !pwd) {
                return tip('请至少填写一个密钥（仅修改密码时密钥框可留空）', true);
            }
            const body = {};
            if (ds) body.deepseekKey = ds;
            if (dash) body.dashscopeKey = dash;
            if (pwd) body.password = pwd;
            try {
                // 响应 {status, token}：设了/改了密码时 token 非空（旧令牌同时作废）；
                // 密码模式下仅增量改密钥则 token=null，继续用手里的令牌。
                const data = await api('/api/setup/keys', 'POST', body);
                if (data && data.token) state.vaultToken = data.token;
                $('ksDeep').value = ''; $('ksDash').value = ''; $('ksPass').value = '';
                tip(pwd ? '密钥已保存，本浏览器已解锁（其他设备需输入新密码）' : '密钥已保存到本机保险箱');
                await loadKeyStatus();
                loadBusinessData();
            } catch (e) { tip(e.message, true); }
        };

        $('btnKeyUnlock').onclick = async () => {
            const pwd = $('kuPassword').value.trim();
            if (!pwd) return tip('请输入密码', true);
            try {
                const data = await api('/api/setup/unlock', 'POST', { password: pwd });
                if (!data || !data.token) throw new Error('解锁未返回访问令牌');
                state.vaultToken = data.token;   // 只在 JS 内存：刷新即失，必须重新输密码
                $('kuPassword').value = '';
                tip('已解锁，可以开始面试');
                await loadKeyStatus();
                loadBusinessData();
            } catch (e) { tip(e.message, true); }
        };

        $('btnKeyLock').onclick = async () => {
            try {
                await api('/api/setup/lock', 'POST');
                state.vaultToken = null;   // 本浏览器也立刻掉线
                tip('已锁定，全部设备需重新输入密码');
                await loadKeyStatus();
            } catch (e) { tip(e.message, true); }
        };

        $('btnKeyCancel').onclick = () => {
            $('keyFormBox').hidden = true;
            $('btnKeyCancel').hidden = true;
        };

        // 密码保护没有「找回」接口（否则保护形同虚设）：物理删除保险箱文件后重新填写即可
        $('btnKeyFromUnlock').onclick = () => {
            tip('密码无法找回：请停止服务后删除本机 ~/.mock-interview/vault.json，再回到本页重新填写', true);
        };
    }

    /** 门禁通过后需要补载的业务数据（首屏 gated 时为避免一串 401 而延后）。 */
    function loadBusinessData() {
        refreshJd();
        refreshResume();
        loadWeak();
        loadHistory();
    }

    /** @returns {Promise<boolean>} 是否处于「需要密码且本地无令牌」的门禁态 */
    async function loadKeyStatus() {
        let s;
        try {
            s = await api('/api/setup/status');
        } catch (e) {
            $('keyBadge').textContent = '状态未知';
            $('keyBadge').className = 'keybadge bad';
            return false;
        }
        state.keyStatus = s;

        // 门禁判定以「本浏览器手里有没有令牌」为准，而不是服务端 unlocked：
        // 刷新/新标签页后内存令牌丢失，即使服务端仍 unlocked 也必须重新输密码。
        const gated = !!s.passwordProtected && !state.vaultToken;
        const usable = (src) => !gated && src !== 'NONE' && (src !== 'VAULT' || s.unlocked);
        const label = { ENV: '环境变量提供', VAULT: '已保存在本机', NONE: '未配置' };
        const dotCls = (src) => usable(src) ? 'ok' : (src === 'NONE' ? 'none' : 'lock');
        const line = (name, src) => '<div class="keyLine"><span class="kd ' + dotCls(src) + '"></span>'
            + name + '：' + (gated && src === 'VAULT'
                ? '已保存（请输入密码）' : label[src]) + '</div>';
        $('keyStatus').innerHTML = line('DeepSeek（对话/判分）', s.deepseek)
            + line('DashScope（向量化）', s.dashscope);

        const badge = $('keyBadge');
        const deepReady = usable(s.deepseek);
        if (gated) {
            badge.textContent = '需要密码 · 点此解锁';
        } else {
            badge.textContent = deepReady ? '模型可用 · 点此修改' : '未配置 · 点此填写';
        }
        badge.className = 'keybadge ' + ((gated || !deepReady) ? 'bad' : 'ok');

        $('keyUnlockBox').hidden = !gated;
        // 门禁态强制走解锁；未配置时直接展开表单；已就绪默认收起（点徽标展开）
        $('keyFormBox').hidden = gated || deepReady;
        $('btnKeyCancel').hidden = true;
        $('btnKeyLock').hidden = !(s.passwordProtected && s.unlocked && !!state.vaultToken);

        $('ksDeep').disabled = s.deepseek === 'ENV';
        $('ksDash').disabled = s.dashscope === 'ENV';
        $('ksDeep').placeholder = s.deepseek === 'ENV'
            ? 'DeepSeek 已由环境变量提供，无需填写' : 'DeepSeek API Key（sk-…）';
        $('ksDash').placeholder = s.dashscope === 'ENV'
            ? 'DashScope 已由环境变量提供，无需填写' : 'DashScope Key（通义向量化，可选）';
        return gated;
    }

    // ---------- 启动：先查门禁，通过后才载业务数据（gated 时避免整屏 401） ----------
    bind();
    (async () => {
        const gated = await loadKeyStatus();
        if (!gated) loadBusinessData();
    })();
})();
