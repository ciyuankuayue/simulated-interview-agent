/* Vue 3 client. Existing contracts and JWT-in-memory policy are unchanged; coach endpoints are additive.
 * Account reset invalidates requests and clears both buddies' private state.
 */
(function () {
    'use strict';
    const { createApp, reactive, nextTick } = Vue;
    const buddyState = () => ({ messages: [], input: '', busy: false, error: '', status: '', nextId: 0, retry: null, following: true });
    const initialState = () => ({
        token: null, username: '', accountEpoch: 0, view: 'prepare', notice: null,
        jdList: [], resumeList: [], historyList: [], weakList: [], quota: null,
        keyMode: 'PLATFORM', chatProvider: 'DEEPSEEK', hasKey: false, last4: '',
        jdId: null, resumeId: null, sessionId: null, maxQuestions: 3,
        interviewActive: false, busy: false, awaiting: false, phase: 'idle',
        currentTurn: null, mainCount: 0, messages: [], liveReport: null,
        report: null, reportSessionId: null, connOk: false, connText: '尚未开始',
        pendingSince: null, elapsed: 0, quotaOpen: false, dueOnly: false,
        authDestination: null, buddyReturnView: 'prepare',
        buddies: { coach: buddyState(), companion: buddyState() },
        buddyMemory: { enabled: false, revision: -1, entries: [], blockedKeys: [], loaded: false, busy: false, error: '' },
        histFilter: { keyword: '', status: '', minScore: '', maxScore: '' }
    });
    const store = reactive(initialState());
    const requests = new Set();
    let stream = null;
    let seen = new Set();
    let submitted = new Set();
    let historySequence = 0;
    let buddyMemorySequence = 0;
    const staleError = () => Object.assign(new Error('会话已切换'), { stale: true });
    const notify = (message, error = false) => {
        if (message && message.stale) return;
        store.notice = { message: message instanceof Error ? message.message : message, error };
    };
    function goBuddy(view) {
        if (!store.token) {
            store.authDestination = view;
            notify('登录后即可和' + (view === 'coach' ? '通考小师' : '陪考小暖') + '聊聊，已为你选好入口。');
            nextTick(() => {
                document.querySelector('.auth-card')?.scrollIntoView({ block: 'center' });
                document.querySelector('.auth-form input')?.focus({ preventScroll: true });
            });
            return;
        }
        if (!['companion', 'coach'].includes(store.view)) store.buddyReturnView = store.view;
        store.view = view;
        if (!store.buddyMemory.loaded) refreshBuddyMemory();
    }
    function explainWithCoach(question, answer, review, source) {
        const coach = store.buddies.coach;
        if (coach.input.trim() && !confirm('用选中的练习片段替换小师输入框里的草稿？')) return;
        const feedback = typeof review === 'string' ? review : [review?.explanation, review?.correction, review?.knowledgeSupplement].filter(Boolean).join('\n');
        coach.input = ['请帮我讲懂这道题，指出面试考点，再给一道可跳过的小题。', source ? '来源：' + source : '',
            '问题：' + (question || '待巩固知识点'), answer ? '我当时的回答：' + answer : '', feedback ? '已有反馈：' + feedback : ''].filter(Boolean).join('\n\n').slice(0, 6000);
        goBuddy('coach');
        nextTick(() => document.getElementById('buddy-input-coach')?.focus({ preventScroll: true }));
    }
    const fmtTime = value => value ? new Date(value).toLocaleString('zh-CN', {
        month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit'
    }) : '—';
    // 与 QuotaProperties.TOKENS_PER_POINT 一致；API/DB 保持 token 当量，页面只显示积分。
    const TOKENS_PER_POINT = 500;
    const formatPoints = value => value == null ? '—' : (Number(value) / TOKENS_PER_POINT)
        .toLocaleString('zh-CN', { maximumFractionDigits: 3 });
    function closeStream() {
        if (stream) { stream.close(); stream = null; }
        store.connOk = false;
    }
    function resetAccount() {
        const epoch = store.accountEpoch + 1;
        closeStream();
        requests.forEach(controller => controller.abort());
        requests.clear();
        seen = new Set(); submitted = new Set(); historySequence++; buddyMemorySequence++;
        Object.assign(store, initialState(), { accountEpoch: epoch });
    }
    async function api(path, method = 'GET', body, raw = false) {
        const epoch = store.accountEpoch;
        const controller = new AbortController();
        requests.add(controller);
        try {
            const headers = {};
            if (store.token) headers.Authorization = 'Bearer ' + store.token;
            if (body !== undefined) headers['Content-Type'] = 'application/json; charset=utf-8';
            const response = await fetch(path, {
                method, headers, signal: controller.signal,
                ...(body !== undefined ? { body: JSON.stringify(body) } : {})
            });
            const text = await response.text();
            if (epoch !== store.accountEpoch) throw staleError();
            let data;
            try { data = text ? JSON.parse(text) : null; } catch (_) { data = null; }
            if (!response.ok) {
                const error = Object.assign(new Error(data?.message || '请求未完成，请稍后重试（' + response.status + '）'), {
                    status: response.status, code: data?.code
                });
                if (response.status === 401 && !path.startsWith('/api/auth/')) {
                    resetAccount();
                    notify('登录已过期，请重新登录。', true);
                    throw staleError();
                }
                if (response.status === 402) {
                    store.quotaOpen = true;
                    store.quota = null;
                }
                throw error;
            }
            if (!raw && text && data === null) throw new Error('服务返回格式异常，请刷新后重试。');
            return raw ? text : data;
        } catch (error) {
            if (epoch !== store.accountEpoch) throw staleError();
            if (error instanceof TypeError) throw new Error('网络连接中断，请检查网络后重试。');
            throw error;
        } finally { requests.delete(controller); }
    }
    function acceptBuddyMemory(data) {
        const memory = store.buddyMemory;
        if (data && Number(data.revision) >= memory.revision) Object.assign(memory, data, { loaded: true, error: '' });
    }
    async function refreshBuddyMemory() {
        // Automatic entries share the settings revision; also reject snapshots from superseded reads.
        const sequence = ++buddyMemorySequence;
        try {
            const data = await api('/api/buddy/memory');
            if (sequence === buddyMemorySequence) acceptBuddyMemory(data);
        } catch (error) {
            if (!error.stale && sequence === buddyMemorySequence) store.buddyMemory.error = '记忆暂未读取成功，可以稍后重试。';
        }
    }
    async function streamBuddy(path, message, onEvent) {
        const epoch = store.accountEpoch, controller = new AbortController();
        requests.add(controller);
        let reader, complete = false;
        const check = () => { if (epoch !== store.accountEpoch) throw staleError(); };
        const fail = (data, status) => {
            check();
            status = Number(data?.status || status || 503);
            if (status === 401) {
                resetAccount(); notify('登录已过期，请重新登录。', true); throw staleError();
            }
            if (status === 402) { store.quotaOpen = true; store.quota = null; }
            throw Object.assign(new Error(data?.message || '回应暂未完成，请稍后重试。'), { status, code: data?.code });
        };
        try {
            const response = await fetch(path, { method: 'POST', signal: controller.signal,
                headers: { Authorization: 'Bearer ' + store.token, 'Content-Type': 'application/json; charset=utf-8', Accept: 'text/event-stream' },
                body: JSON.stringify({ message }) });
            check();
            if (!response.ok) {
                let data; try { data = await response.json(); } catch (_) { data = null; }
                fail(data, response.status);
            }
            if (!response.body || !response.headers.get('Content-Type')?.includes('text/event-stream')) throw new Error('服务返回格式异常，请稍后重试。');
            reader = response.body.getReader();
            const decoder = new TextDecoder('utf-8');
            let buffer = '', type = '', lines = [];
            const dispatch = () => {
                if (!lines.length) { type = ''; return; }
                let data; try { data = JSON.parse(lines.join('\n')); } catch (_) { throw new Error('回应传输不完整，请重试。'); }
                const event = type; type = ''; lines = [];
                check();
                if (event === 'error') fail(data);
                if (event === 'done') {
                    if (typeof data.reply !== 'string' || !data.reply.trim()) throw new Error('未收到完整回复，请重试。');
                    complete = true;
                }
                if (event === 'delta' || event === 'done') onEvent(event, data);
            };
            const consume = final => {
                let end;
                while ((end = buffer.indexOf('\n')) >= 0) {
                    const line = buffer.slice(0, end).replace(/\r$/, ''); buffer = buffer.slice(end + 1);
                    if (!line) dispatch();
                    else if (!line.startsWith(':')) {
                        const colon = line.indexOf(':');
                        const field = colon < 0 ? line : line.slice(0, colon);
                        const value = colon < 0 ? '' : line.slice(colon + 1).replace(/^ /, '');
                        if (field === 'event') type = value;
                        if (field === 'data') lines.push(value);
                    }
                    if (complete) return;
                }
                if (final && (buffer || lines.length)) throw new Error('回应传输中断，请重试。');
            };
            while (!complete) {
                const chunk = await reader.read(); check();
                buffer += decoder.decode(chunk.value, { stream: !chunk.done }); consume(chunk.done);
                if (chunk.done) break;
            }
            if (!complete) throw new Error('连接已中断，回复尚未完成。请重试，可能产生新的调用费用。');
        } catch (error) {
            check();
            if (error instanceof TypeError) throw new Error('网络连接中断，回复尚未完成，请重试。');
            throw error;
        } finally {
            if (reader) reader.cancel().catch(() => {});
            requests.delete(controller);
        }
    }
    function waitFor(phase) {
        store.phase = phase;
        store.pendingSince = Date.now();
        store.elapsed = 0;
    }
    async function refreshJd() { store.jdList = await api('/api/jd'); }
    async function refreshResume() {
        store.resumeList = (await api('/api/resume')).sort((a, b) => b.resumeId - a.resumeId);
    }
    async function loadQuota() { store.quota = await api('/api/user/quota'); }
    async function loadSettings() {
        const settings = await api('/api/user/settings');
        store.keyMode = settings.keyMode;
        store.chatProvider = settings.chatProvider;
        const key = await api('/api/user/api-key');
        store.hasKey = key.hasKey;
        store.last4 = key.last4 || '';
    }
    async function loadHistory() {
        const sequence = ++historySequence;
        const params = new URLSearchParams();
        Object.entries(store.histFilter).forEach(([key, value]) => {
            if (value !== '') params.set(key, value);
        });
        const rows = await api('/api/interview/sessions?' + params);
        if (sequence === historySequence) store.historyList = rows;
    }
    async function loadWeak() {
        store.weakList = await api('/api/weak-point' + (store.dueOnly ? '/due' : ''));
    }
    function refreshAfterInterview() {
        [loadHistory, loadWeak, loadQuota].forEach(fn => fn().catch(error => notify(error, true)));
    }
    function acceptReport(report, sessionId) {
        store.liveReport = report;
        if (store.reportSessionId == null || store.reportSessionId === sessionId || store.view === 'interview') {
            store.report = report;
            store.reportSessionId = sessionId;
        }
    }
    async function recoverReport() {
        const sessionId = store.sessionId;
        try {
            const report = await api('/api/interview/' + sessionId + '/report');
            if (store.sessionId === sessionId) acceptReport(report, sessionId);
        }
        catch (error) { notify(error.stale ? error : '报告暂未获取成功，可稍后点击“获取报告”重试。', true); }
    }
    function openStream() {
        closeStream();
        if (!store.sessionId || !store.token) return;
        const sessionId = store.sessionId, epoch = store.accountEpoch;
        const source = new EventSource('/api/interview/' + sessionId + '/events?jwt=' + encodeURIComponent(store.token));
        stream = source;
        store.connText = '连接中';
        let failures = 0;
        const current = () => stream === source && epoch === store.accountEpoch && sessionId === store.sessionId;
        source.onopen = () => {
            if (!current()) return;
            failures = 0; store.connOk = true; store.connText = '已连接';
        };
        const on = (type, callback) => source.addEventListener(type, event => {
            if (!current()) return;
            let data;
            try { data = JSON.parse(event.data); } catch (_) { return; }
            const key = type + ':' + (data.turnSeq ?? data.reportId ?? '');
            if (type !== 'await_answer' && seen.has(key)) return;
            if (type !== 'await_answer') seen.add(key);
            callback(data);
        });
        const question = data => {
            store.currentTurn = data.turnSeq;
            if (!data.layer) store.mainCount++;
            store.messages.push({ kind: 'question', seq: data.turnSeq, layer: data.layer || 0,
                role: data.role || '面试官', text: data.question });
        };
        on('question', question);
        on('followup', question);
        on('score', data => store.messages.push({ kind: 'score', seq: data.turnSeq,
            score: data.score, track: data.track, text: data.oneLineCorrection }));
        on('await_answer', data => {
            if (data.turnSeq !== store.currentTurn || submitted.has(data.turnSeq) || !store.interviewActive) return;
            store.awaiting = true; store.busy = false; store.phase = 'answer'; store.pendingSince = null;
        });
        on('report', data => acceptReport(data, sessionId));
        on('done', () => {
            store.interviewActive = false; store.awaiting = false; store.busy = false;
            store.phase = 'done'; store.pendingSince = null;
            closeStream(); store.connText = '本场已结束';
            if (!store.liveReport) recoverReport();
            refreshAfterInterview();
        });
        source.addEventListener('error', event => {
            if (!current()) return;
            if (event.data) {
                try {
                    const data = JSON.parse(event.data);
                    const text = data.code === 'QUESTION_FAILED'
                        ? '下一步暂未准备成功。已提交的回答会保留，你可以继续生成，或结束本场查看报告。'
                        : data.code === 'SCORE_FAILED' ? '本轮评分暂时不可用，系统会尝试继续练习。'
                        : data.message || '本轮处理出现异常。';
                    store.messages.push({ kind: 'error', text });
                    if (data.code === 'QUESTION_FAILED') {
                        store.busy = false; store.awaiting = false;
                        store.pendingSince = null; store.phase = 'blocked';
                    }
                    if (data.code === 'QUOTA_EXHAUSTED') store.quotaOpen = true;
                } catch (_) { notify('收到无法识别的事件，请重新连接。', true); }
                return;
            }
            store.connOk = false;
            store.connText = '正在重新连接';
            if (++failures >= 3) {
                closeStream(); store.connText = '连接已断开，请重新连接';
            }
        });
    }
    const icons = {
        spark: 'M12 3l2.3 6.7L21 12l-6.7 2.3L12 21l-2.3-6.7L3 12l6.7-2.3z',
        practice: 'M8 3h8l4 4v14H4V3z M8 10h8 M8 14h5 M8 18h3',
        interview: 'M4 4h16v12H9l-5 4z M8 8h8 M8 12h5',
        report: 'M5 3h14v18H5z M9 16v-3 M12 16V8 M15 16v-5',
        folder: 'M3 6h7l2 2h9v12H3z M3 6V4h7l2 2',
        leaf: 'M20 3C9 3 3 7 5 15c4 8 15 5 15-12z M5 21l10-11',
        settings: 'M4 7h16 M4 17h16 M8 4v6 M16 14v6',
        arrow: 'M4 12h16 M14 6l6 6-6 6',
        check: 'M5 12l4 4L19 6', plus: 'M12 5v14 M5 12h14',
        logout: 'M9 4H4v16h5 M13 7l5 5-5 5 M8 12h10',
        refresh: 'M20 8a8 8 0 10.2 7 M20 3v5h-5',
        clock: 'M12 8v5l3 2 M21 12a9 9 0 11-18 0 9 9 0 0118 0'
    };
    const Icon = {
        props: ['name'],
        computed: { path() { return icons[this.name] || icons.spark; } },
        template: `<svg class="icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path :d="path"/></svg>`
    };
    const buddyProfiles = {
        companion: {
            id: 'companion', name: '陪考小暖', shortName: '小暖', role: '心态陪伴', tone: 'warm',
            introduction: '我是陪考小暖，你的专属心态陪伴。请放心将备考路上的心事交给我，我们一起慢慢梳理~',
            tagline: '先接住你的心情，再陪你坚定向前。', welcome: '今天的你，感觉怎么样？',
            description: '紧张、失落，或者只是想说几句话，都可以在这里慢慢聊。',
            placeholder: '说说你现在的心情…', label: '想对小暖说的话',
            stream: '/api/companion/stream', reset: '/api/companion/reset',
            actions: ['先听我说', '帮我理一理', '陪我走一步'],
            starters: ['明天就要面试了，我有点紧张。', '刚才没有答好，有点受挫。', '我想先整理一下现在的心情。']
        },
        coach: {
            id: 'coach', name: '通考小师', shortName: '小师', role: '备考教练', tone: 'study',
            introduction: '我是通考小师，你的专属备考教练。请放心将知识疑问与学习困惑交给我，我们一起把难点弄懂~',
            tagline: '把不会的弄懂，把下一步走清楚。', welcome: '哪一个问题，让你停住了？',
            description: '一个没弄懂的知识点，一道答不好的题，或一份不知从哪开始的学习计划，我们一点点拆开。',
            placeholder: '把卡住的问题贴过来，也可以告诉我你的目标岗位和基础…', label: '想问小师的问题',
            stream: '/api/coach/stream', reset: '/api/coach/reset',
            actions: ['换个比喻', '看面试问法', '给个提示', '看答案', '跳过这题', '今天不测'],
            starters: ['能用一个简单例子解释数据库事务隔离吗？', '怎样用 STAR 结构讲清我的项目贡献？', '我想梳理备考方向，应该先告诉你哪些信息？']
        }
    };
    const BuddyAvatar = {
        props: ['tone'],
        template: `<svg :class="['buddy-avatar',tone]" viewBox="0 0 64 64" aria-hidden="true">
<template v-if="tone==='warm'"><rect x="1" y="1" width="62" height="62" rx="21" fill="#fff0de"/><g stroke="#d8954d" stroke-width="2.3" stroke-linecap="round"><path d="M32 7v4M32 53v4M7 32h4M53 32h4M14 14l3 3M47 47l3 3M14 50l3-3M47 17l3-3"/></g><circle cx="32" cy="32" r="18" fill="#f5ce8b"/><g fill="#87603c"><circle cx="26" cy="30" r="1.6"/><circle cx="38" cy="30" r="1.6"/></g><path d="M26 37q6 6 12 0" fill="none" stroke="#a66b40" stroke-width="2" stroke-linecap="round"/><ellipse cx="21" cy="36" rx="3" ry="1.8" fill="#eaa992"/><ellipse cx="43" cy="36" rx="3" ry="1.8" fill="#eaa992"/></template>
<template v-else><rect x="1" y="1" width="62" height="62" rx="21" fill="#eaf0ff"/><path d="M15 57q1-15 17-15t17 15" fill="#9eb8df"/><circle cx="32" cy="31" r="16" fill="#f4ddbb"/><path d="M12 19l20-10 20 10-20 9z" fill="#6685b4"/><path d="M21 23v-5q11-7 22 0v5" fill="#7f9bc7"/><path d="M50 20v13" fill="none" stroke="#6685b4" stroke-width="2"/><g fill="none" stroke="#60799f" stroke-width="1.6"><rect x="21" y="29" width="9" height="7" rx="3"/><rect x="34" y="29" width="9" height="7" rx="3"/><path d="M30 32h4"/></g><path d="M28 40q4 3 8 0" fill="none" stroke="#a77f5b" stroke-width="1.8" stroke-linecap="round"/><path d="M24 49l8 3 8-3v10H24z" fill="#fafcff"/><path d="M32 52v7" stroke="#b6c7df"/></template>
</svg>`
    };
    const BuddyLinks = {
        data: () => ({ profiles: Object.values(buddyProfiles) }),
        methods: { open: goBuddy },
        template: `<div class="buddy-links" role="group" aria-label="陪考搭档快捷入口"><button v-for="buddy in profiles" :key="buddy.id" :class="['buddy-link',buddy.tone]" :aria-label="buddy.name+'，'+buddy.role" :aria-pressed="store.token?store.view===buddy.id:store.authDestination===buddy.id" @click="open(buddy.id)"><buddy-avatar :tone="buddy.tone"/><span><strong>{{buddy.name}}</strong><small>{{buddy.role}}</small></span></button></div>`
    };
    const AuthPanel = {
        data: () => ({ tab: 'login', username: '', password: '', confirmation: '', show: false, loading: false, error: '' }),
        methods: {
            async submit() {
                if (this.loading) return;
                this.error = '';
                if (this.tab === 'register' && this.password !== this.confirmation) {
                    this.error = '两次输入的密码不一致。'; return;
                }
                this.loading = true;
                try {
                    const result = await api('/api/auth/' + this.tab, 'POST', {
                        username: this.username.trim(), password: this.password
                    });
                    const destination = store.authDestination;
                    resetAccount();
                    store.token = result.token; store.username = result.username;
                    store.view = destination || 'prepare';
                    nextTick(() => window.scrollTo({ top: 0, behavior: 'auto' }));
                    [refreshJd, refreshResume, loadHistory, loadWeak, loadQuota, loadSettings]
                        .forEach(fn => fn().catch(error => notify(error, true)));
                } catch (error) { if (!error.stale) this.error = error.message; }
                finally { this.loading = false; }
            }
        },
        template: `
<div class="welcome">
  <header class="welcome-top"><a class="brand" href="/" aria-label="AI 模拟面试首页"><span class="brand-mark"><app-icon name="spark"/></span>面试练习室<span class="brand-en">INTERVIEW STUDIO</span></a><buddy-links/></header>
  <main class="welcome-main">
    <section class="welcome-copy">
      <span class="eyebrow">全流程 AI 陪考 · 心态有依靠，学习有方向</span>
      <h1>你只管坚定向前，<br>备考这一路，<span>我们陪你。</span></h1>
      <p class="welcome-description">先一点点稳住心态，再每天慢慢进步。<br>从心态陪伴、知识答疑，到面试模拟与成长复盘，<br class="desktop-break">这里有接住你心情的小暖，也有陪你弄懂难点的小师。</p>
      <div class="buddy-cards"><button class="buddy-card warm" @click="goBuddy('companion')"><buddy-avatar tone="warm"/><span><span class="buddy-card-label">陪考小暖 · 心态陪伴</span><strong>先把心放稳，再向前。</strong><small>紧张时聊一聊，失落时缓一缓。<br>你的心事，可以慢慢说。</small></span><app-icon name="arrow"/></button><button class="buddy-card study" @click="goBuddy('coach')"><buddy-avatar tone="study"/><span><span class="buddy-card-label">通考小师 · 备考教练</span><strong>把不会的，一点点弄懂。</strong><small>讲清知识，拆解难题，梳理方向。<br>每一步，都更有底气。</small></span><app-icon name="arrow"/></button></div>
      <div class="journey-strip" aria-label="全流程陪考"><span>稳住心态</span><i>→</i><span>弄懂知识</span><i>→</i><span>模拟实战</span><i>→</i><span>看见成长</span></div>
      <p class="welcome-promise">把此刻的心情说给小暖，把卡住的问题交给小师。<br>让练习与复盘留下成长的足迹，愿这里成为你走向面试场时，<strong>安心的后盾。</strong></p>
    </section>
    <section class="auth-card" aria-labelledby="auth-title">
      <span class="eyebrow">让每一次出发，都有人陪</span><h2 id="auth-title">{{tab==='login'?'欢迎回到练习室':'开启你的面试练习'}}</h2><p class="quiet">先安顿心情，再为下一步积累底气。</p>
      <div class="segmented auth-tabs"><button type="button" :aria-pressed="tab==='login'" @click="tab='login';error=''" :disabled="loading">登录</button><button type="button" :aria-pressed="tab==='register'" @click="tab='register';error=''" :disabled="loading">注册账号</button></div>
      <p v-if="tab==='register'" class="auth-selection">新用户注册即赠 100 积分，陪你开始第一段练习。</p>
      <p v-if="store.notice" :class="store.notice.error?'inline-error':'auth-selection'" :role="store.notice.error?'alert':'status'">{{store.notice.message}}</p>
      <form @submit.prevent="submit" class="auth-form">
        <label>用户名<input v-model="username" autocomplete="username" required minlength="4" maxlength="32" placeholder="输入 4–32 字符的用户名" :disabled="loading"></label>
        <label>密码<span class="password-field"><input v-model="password" aria-label="密码" :type="show?'text':'password'" :autocomplete="tab==='login'?'current-password':'new-password'" required minlength="6" maxlength="128" placeholder="至少 6 个字符" :disabled="loading"><button type="button" @click="show=!show" :aria-label="show?'隐藏密码':'显示密码'">{{show?'隐藏':'显示'}}</button></span></label>
        <label v-if="tab==='register'">确认密码<input v-model="confirmation" :type="show?'text':'password'" autocomplete="new-password" required minlength="6" maxlength="128" placeholder="再次输入密码" :disabled="loading"></label>
        <p v-if="error" class="inline-error" role="alert">{{error}}</p>
        <button class="primary large" :disabled="loading">{{loading?'正在连接练习室…':tab==='login'?'进入练习室':'注册并开始练习'}}<app-icon name="arrow"/></button>
      </form>
      <p class="auth-footnote">登录状态仅保留在当前页面，刷新后需要重新登录。</p>
    </section>
  </main><footer class="welcome-footer"><span>陪考小暖 × 通考小师 · 你不是一个人在准备这场面试。</span><span>温柔陪伴，认真成长。</span></footer>
</div>`
    };
    const PreparePanel = {
        data: () => ({ jdText: '', jdTag: '', uploading: false, resumeBusy: false,
            roles: ['技术深挖官'], roleOptions: [
                { name: '技术深挖官', desc: '从原理到实践，检验理解深度' },
                { name: '项目质疑官', desc: '围绕项目决策，追问真实细节' },
                { name: '行为面试官', desc: '用 STAR 结构讲清经历与贡献' }
            ] }),
        computed: { selectedJd() { return store.jdList.find(jd => jd.jdId === store.jdId); } },
        methods: {
            async refresh() {
                try { await Promise.all([refreshJd(), refreshResume()]); } catch (error) { notify(error, true); }
            },
            async uploadJd() {
                if (this.uploading || !this.jdText.trim()) return;
                this.uploading = true;
                try {
                    const rows = await api('/api/jd', 'POST', { rawText: this.jdText.trim(), sameTypeTag: this.jdTag.trim() });
                    await refreshJd();
                    store.jdId = rows[0].jdId;
                    this.jdText = ''; this.jdTag = '';
                    notify('岗位已准备好，可以开始练习了。');
                    loadQuota().catch(error => notify(error, true));
                } catch (error) { notify(error, true); }
                finally { this.uploading = false; }
            },
            async selectResume() {
                if (store.resumeId == null) return;
                this.resumeBusy = true;
                try {
                    await api('/api/resume/' + store.resumeId + '/activate', 'POST');
                    await refreshResume();
                } catch (error) { if (!error.stale) store.resumeId = null; notify(error, true); }
                finally { this.resumeBusy = false; }
            },
            async start() {
                if (store.busy || store.interviewActive || this.uploading || this.resumeBusy) return;
                if (!store.jdId) { notify('请先准备或选择一个岗位 JD。', true); return; }
                if (!this.roles.length) { notify('请至少选择一位面试官。', true); return; }
                store.busy = true; waitFor('starting'); store.view = 'interview';
                store.notice = null; store.messages = []; store.liveReport = null;
                store.currentTurn = null; store.mainCount = 0; store.sessionId = null;
                store.awaiting = false; seen = new Set(); submitted = new Set();
                try {
                    const result = await api('/api/interview/start', 'POST', {
                        jdId: store.jdId, resumeId: store.resumeId,
                        roles: this.roleOptions.filter(role => this.roles.includes(role.name)).map(role => role.name),
                        maxQuestions: store.maxQuestions
                    });
                    store.sessionId = result.sessionId; store.interviewActive = true;
                    openStream();
                } catch (error) {
                    if (error.stale) return;
                    store.busy = false; store.phase = 'idle'; store.pendingSince = null;
                    store.view = 'prepare'; notify(error, true);
                }
            }
        },
        template: `
<section>
  <div class="page-heading"><div><span class="eyebrow">从目标出发</span><h1>开始一场新练习</h1><p>准备一个目标岗位，剩下的，我们一起练。</p></div><span class="soft-label"><app-icon name="clock"/>按自己的节奏来</span></div>
  <div class="prepare-grid">
    <div class="surface preparation">
      <div class="section-heading"><span class="step-number">01</span><div><h2>你想面试什么岗位？</h2><p>粘贴招聘 JD，让提问更有针对性。</p></div></div>
      <label for="existing-jd" class="field-label">选择已有岗位</label><div class="input-action"><select id="existing-jd" v-model="store.jdId" :disabled="store.busy || store.interviewActive"><option :value="null">选择已保存的 JD</option><option v-for="jd in store.jdList" :key="jd.jdId" :value="jd.jdId">{{jd.jobType || '未命名岗位'}}{{jd.sameTypeTag?' · '+jd.sameTypeTag:''}} · #{{jd.jdId}}</option></select><button class="icon-button" @click="refresh" aria-label="刷新岗位与简历列表"><app-icon name="refresh"/></button></div>
      <details class="jd-entry" :open="!selectedJd"><summary>添加一个新岗位 JD <span aria-hidden="true">+</span></summary>
      <label for="jd-text" class="field-label">岗位描述</label><textarea id="jd-text" v-model="jdText" rows="7" placeholder="在这里粘贴岗位职责、任职要求…&#10;&#10;越具体的岗位信息，越有针对性的面试问题。" :disabled="uploading || store.interviewActive"></textarea>
      <div class="input-action jd-submit"><label class="grow sr-only" for="jd-tag">岗位备注</label><input id="jd-tag" v-model="jdTag" placeholder="添加备注，如：产品经理 · 目标公司（可选）" :disabled="uploading"><button class="secondary" @click="uploadJd" :disabled="!jdText.trim() || uploading || store.interviewActive">{{uploading?'正在解析岗位…':'保存并解析 JD'}}<app-icon name="plus" v-if="!uploading"/></button></div>
      <p class="field-note">解析岗位会使用平台额度；保存后可以反复选择。</p></details>
      <div v-if="selectedJd" class="selected-jd"><span class="eyebrow">已选岗位 · {{selectedJd.jobType || '目标岗位'}}</span><div class="tags" v-if="selectedJd.keywords?.length"><span v-for="word in selectedJd.keywords" :key="word">{{word}}</span></div><p v-else class="field-note">岗位已保存，面试官将参考 JD 原文提问。</p></div>
    </div>
    <div class="prepare-aside">
      <div class="surface session-options"><div class="section-heading"><span class="step-number">02</span><div><h2>定制这次练习</h2><p>少量开始，也是一种进步。</p></div></div>
        <fieldset :disabled="store.busy || store.interviewActive || resumeBusy"><legend>简历版本 <span class="optional">可选</span></legend><select v-model="store.resumeId" aria-label="本次简历版本" @change="selectResume"><option :value="null">暂不指定简历版本</option><option v-for="resume in store.resumeList" :key="resume.resumeId" :value="resume.resumeId">版本 #{{resume.resumeId}} · {{fmtTime(resume.createdAt)}}{{resume.isActive?' · 已启用':''}}</option></select><button class="text-button" @click="store.view='materials'">管理我的资料 <span aria-hidden="true">↗</span></button><p class="field-note">选择本次关联的简历；面试官也可参考你的项目、笔记和真实面经资料。</p></fieldset>
        <fieldset :disabled="store.busy || store.interviewActive"><legend>参与面试官</legend><label class="role-option" v-for="role in roleOptions" :key="role.name"><input type="checkbox" v-model="roles" :value="role.name"><span><strong>{{role.name}}</strong><small>{{role.desc}}</small></span></label></fieldset>
        <fieldset :disabled="store.busy || store.interviewActive"><legend>主问题数量</legend><div class="segmented"><button v-for="count in [3,5,8]" :key="count" @click="store.maxQuestions=count" :aria-pressed="store.maxQuestions===count">{{count}} 题</button></div><p class="field-note">面试官可能继续追问，每题最多两层。</p></fieldset>
        <button class="primary large" @click="start" :disabled="!store.jdId || !roles.length || store.busy || store.interviewActive || uploading || resumeBusy">{{store.interviewActive?'本场练习进行中':'开始模拟面试'}}<app-icon name="arrow"/></button>
        <p v-if="!store.jdId" class="start-hint">先准备一个岗位，即可开始。</p><button v-if="store.interviewActive" class="text-button" @click="store.view='interview'">返回正在进行的练习</button>
      </div><div class="gentle-note"><app-icon name="leaf"/><p>这里允许停顿，也允许答得不完美。<br>练习，就是为下一次多准备一点。</p></div>
    </div>
  </div>
</section>`
    };
    const MaterialsPanel = {
        data: () => ({ type: 'resume', content: '', title: '', busy: false, reading: false,
            records: { resume: [], 'project-doc': [], note: [], 'real-interview': [] },
            types: [{ id: 'resume', label: '简历版本' }, { id: 'project-doc', label: '项目文档' },
                { id: 'note', label: '学习笔记' }, { id: 'real-interview', label: '真实面经' }] }),
        computed: { label() { return this.types.find(type => type.id === this.type).label; } },
        methods: {
            async refresh() {
                const type = this.type;
                try {
                    this.records[type] = await api('/api/' + type);
                    if (type === 'resume') store.resumeList = [...this.records.resume].sort((a, b) => b.resumeId - a.resumeId);
                } catch (error) { notify(error, true); }
            },
            changeType(type) {
                if (this.busy || this.reading) return;
                if (this.content.trim() && !confirm('切换资料类型会清空尚未保存的内容，是否继续？')) return;
                this.type = type; this.content = ''; this.title = ''; this.refresh();
            },
            async readFile(event) {
                const epoch = store.accountEpoch;
                const file = event.target.files[0];
                if (!file) return;
                if (!/\.(md|txt)$/i.test(file.name) || file.size > 1024 * 1024) {
                    notify('请选择不超过 1 MB 的 .md 或 .txt 文本文件。', true); event.target.value = ''; return;
                }
                if (this.content.trim() && !confirm('导入文件将替换当前未保存的内容，是否继续？')) { event.target.value = ''; return; }
                this.reading = true;
                try {
                    const text = await file.text();
                    if (epoch !== store.accountEpoch) throw staleError();
                    if (text.includes(String.fromCharCode(0))) throw new Error('这个文件不是可读取的文本，请改用 UTF-8 文本。');
                    this.content = text;
                    if (this.type === 'project-doc' && !this.title) this.title = file.name.replace(/\.[^.]+$/, '');
                    notify('文件已读取，请检查内容后确认保存。');
                } catch (error) { notify(error, true); }
                finally { this.reading = false; event.target.value = ''; }
            },
            async save() {
                if (this.busy || !this.content.trim()) return;
                if (this.type === 'project-doc' && !this.title.trim()) { notify('请填写项目标题。', true); return; }
                this.busy = true;
                try {
                    const result = await api('/api/' + this.type, 'POST', {
                        content: this.content.trim(), ...(this.type === 'project-doc' ? { title: this.title.trim() } : {})
                    });
                    if (this.type === 'resume') store.resumeId = result.resumeId;
                    this.content = ''; this.title = '';
                    await this.refresh(); await loadQuota();
                    notify(this.label + '已保存。');
                } catch (error) { notify(error, true); }
                finally { this.busy = false; }
            }
        },
        mounted() { this.refresh(); },
        template: `
<section><div class="page-heading"><div><span class="eyebrow">让提问更了解你</span><h1>我的资料</h1><p>收好真实的经历，让每次练习更有依据。</p></div><button class="secondary" @click="store.view='prepare'">去练习<app-icon name="arrow"/></button></div>
<div class="surface"><div class="material-tabs"><button v-for="item in types" :key="item.id" @click="changeType(item.id)" :aria-pressed="type===item.id" :disabled="busy || reading">{{item.label}}</button></div>
<div class="material-grid"><div><div class="section-heading"><div><h2>添加{{label}}</h2><p>直接粘贴，或先导入文本再确认。</p></div></div>
<label v-if="type==='project-doc'">项目标题<input v-model="title" placeholder="给这个项目起一个容易辨认的名字" :disabled="busy"></label>
<label class="field-label" for="material-content">资料内容</label><textarea id="material-content" v-model="content" rows="12" :placeholder="'在这里粘贴'+label+'…'" :disabled="busy || reading"></textarea>
<div class="row wrap"><label class="file-button"><app-icon name="folder"/>{{reading?'正在读取…':'导入 .md / .txt'}}<input type="file" accept=".md,.txt,text/plain,text/markdown" @change="readFile" :disabled="busy || reading"></label><span class="field-note">不超过 1 MB · UTF-8 文本</span><span class="spacer"></span><button class="primary" @click="save" :disabled="!content.trim() || busy || reading">{{busy?'正在保存与索引…':'确认保存'}}</button></div>
<p class="field-note">当前支持文本内容；PDF、Word 请先复制正文。保存后资料会用于检索，生成内容可能参考多个版本。</p></div>
<aside class="material-library"><div class="section-heading"><h2>已保存的{{label}}</h2><button class="icon-button" @click="refresh" aria-label="刷新资料"><app-icon name="refresh"/></button></div><p v-if="!records[type].length" class="empty-copy">这里还没有资料。<br>添加后，面试官会更了解你的经历。</p><div class="record" v-for="(record,index) in records[type]" :key="record.resumeId || record.docId || record.noteId || record.recordId || index"><strong>{{record.title || (type==='resume'?'简历版本 #'+record.resumeId:label+' '+(index+1))}}</strong><span>{{fmtTime(record.createdAt || record.updatedAt)}}{{record.isActive?' · 已启用':''}}</span><p v-if="record.content">{{record.content}}</p></div></aside></div></div></section>`
    };
    const InterviewPanel = {
        data: () => ({ answer: '', ending: false, error: '' }),
        computed: {
            canAnswer() { return store.interviewActive && store.awaiting && store.connOk && !store.busy; },
            statusText() { return ({ idle: '准备就绪后，即可开始', starting: '面试官正在准备第一题',
                thinking: '正在评估回答并准备下一题', reporting: '正在整理本场报告',
                answer: '轮到你了，慢慢说清楚', blocked: '出题暂未完成，可以继续生成或结束本场',
                done: '本场练习已完成' })[store.phase]; }
        },
        watch: {
            'store.messages.length'() {
                nextTick(() => { if (this.$refs.chat) this.$refs.chat.scrollTop = this.$refs.chat.scrollHeight; });
            },
            'store.sessionId'() { this.answer = ''; this.error = ''; }
        },
        methods: {
            reconnect() { openStream(); },
            async resumeNext() {
                if (store.busy || !store.interviewActive || store.phase !== 'blocked') return;
                const saved = [...store.messages].reverse().find(message => message.kind === 'answer' && message.seq === store.currentTurn);
                if (!saved) { this.error = '未找到本轮原回答，请结束本场查看报告。'; return; }
                const sessionId = store.sessionId;
                this.error = ''; store.busy = true; store.awaiting = false; waitFor('thinking');
                try {
                    await api('/api/interview/' + sessionId + '/answer', 'POST', { answer: saved.text });
                    if (store.sessionId === sessionId) loadQuota().catch(error => notify(error, true));
                } catch (error) {
                    if (error.stale || store.sessionId !== sessionId) return;
                    store.busy = false; store.awaiting = false; store.phase = 'blocked'; store.pendingSince = null;
                    this.error = error.status === 409 ? '本场暂不能继续生成，可稍后重试或结束查看报告。' : error.message;
                }
            },
            async submit() {
                if (!this.canAnswer || !this.answer.trim()) return;
                const text = this.answer.trim(), turn = store.currentTurn, sessionId = store.sessionId;
                this.error = ''; store.awaiting = false; store.busy = true; submitted.add(turn); waitFor('thinking');
                const message = { kind: 'answer', seq: turn, text };
                store.messages.push(message); this.answer = '';
                try {
                    await api('/api/interview/' + sessionId + '/answer', 'POST', { answer: text });
                    if (store.sessionId !== sessionId) return;
                    loadQuota().catch(error => notify(error, true));
                } catch (error) {
                    if (error.stale || store.sessionId !== sessionId) return;
                    this.answer = text;
                    // A lost HTTP response may still have committed. Keep submission paused until SSE confirms.
                    if (!error.status || error.status === 409) {
                        this.error = '处理结果尚未确认，回答已保留。请重新连接接收结果，避免重复提交。';
                        openStream(); return;
                    }
                    if (store.currentTurn === turn && store.interviewActive && !store.liveReport) {
                        const index = store.messages.indexOf(message);
                        if (index >= 0) store.messages.splice(index, 1);
                        submitted.delete(turn); store.busy = false; store.awaiting = true;
                        store.phase = 'answer'; store.pendingSince = null;
                    }
                    this.error = error.message;
                }
            },
            async end() {
                if (store.busy || !store.interviewActive || this.ending) return;
                if (!confirm('结束本场练习并生成报告？已提交的回答会保留。')) return;
                const sessionId = store.sessionId;
                this.error = ''; this.ending = true;
                store.busy = true; store.awaiting = false; waitFor('reporting');
                try {
                    const report = await api('/api/interview/' + sessionId + '/end', 'POST');
                    if (store.sessionId !== sessionId) return;
                    acceptReport(report, sessionId);
                    store.interviewActive = false; store.phase = 'done'; store.pendingSince = null;
                    closeStream(); store.connText = '本场已结束'; refreshAfterInterview();
                } catch (error) {
                    if (error.stale || store.sessionId !== sessionId) return;
                    this.error = error.message;
                    store.phase = 'answer'; store.awaiting = true; store.pendingSince = null;
                } finally { if (store.token && store.sessionId === sessionId) store.busy = false; this.ending = false; }
            },
            showReport() {
                store.report = store.liveReport; store.reportSessionId = store.sessionId; store.view = 'history';
            },
            recoverReport
        },
        template: `
<section><div class="page-heading"><div><span class="eyebrow">专注当下的这一题</span><h1>面试练习</h1><p>不用急着给出完美答案，先把你的思路说清楚。</p></div><span class="soft-label">{{store.mainCount}} / {{store.maxQuestions}} 道主问题</span></div>
<div class="surface interview-room"><div class="room-top"><span class="room-label"><span :class="['status-dot',{'online':store.connOk}]"></span>{{store.connText}}</span><button v-if="store.interviewActive && !store.connOk" class="text-button" @click="reconnect">重新连接</button><span class="spacer"></span><span class="quiet">{{store.chatProvider==='QWEN'?'通义千问':'DeepSeek'}}</span></div>
<div class="chat" ref="chat" role="log" aria-label="面试问答记录" aria-live="polite" aria-relevant="additions">
<div v-if="!store.messages.length" class="empty-state"><span class="empty-symbol"><app-icon name="interview"/></span><h2>{{store.phase==='starting'?'正在为你准备第一题':'给下一次面试，多一点底气'}}</h2><p>{{store.phase==='starting'?'面试官正在参考岗位和你的资料，请稍候。':'先选择一个目标岗位，开启属于你的练习。'}}</p><button v-if="store.phase==='idle'" class="primary" @click="store.view='prepare'">准备岗位<app-icon name="arrow"/></button></div>
<article v-for="(message,index) in store.messages" :key="index" :class="['message',message.kind]"><div class="message-caption"><span class="message-avatar" v-if="message.kind==='question'"><app-icon name="spark"/></span><strong>{{message.kind==='question'?message.role:message.kind==='answer'?'我的回答':message.kind==='score'?'本轮反馈':'处理提示'}}</strong><span v-if="message.seq" class="quiet">第 {{message.seq}} 轮{{message.layer?' · 追问 '+message.layer:''}}</span><span v-if="message.track==='behavior'" class="tag">行为评分</span></div><div v-if="message.kind==='score'" class="score-line"><b>{{message.score ?? '—'}}</b><span>/ 8</span><span class="score-track"><i :style="{width:Math.max(0,Math.min(100,(message.score || 0)/8*100))+'%'}"></i></span></div><p v-if="message.text">{{message.text}}</p></article>
</div>
<div class="answer-area"><div class="processing-status" role="status"><span v-if="store.busy" class="spinner"></span><span>{{statusText}}</span><span v-if="store.pendingSince" class="quiet">· {{store.elapsed}} 秒{{store.elapsed>=20?' · 模型仍在处理，你可以先浏览其他页面':''}}</span></div>
<p v-if="error" class="inline-error" role="alert">{{error}}</p>
<div v-if="store.phase==='blocked'" class="row wrap"><button class="secondary" @click="resumeNext" :disabled="store.busy">继续生成下一步</button><span class="field-note">使用已保存的原回答继续生成；会按本次模型调用扣减积分。</span></div>
<div v-if="store.phase==='done'" class="completion"><div><strong>又完成了一次练习。</strong><p>看看这次的收获，再决定下一步。</p></div><button class="primary" v-if="store.liveReport" @click="showReport">查看本场报告<app-icon name="arrow"/></button><button v-else class="secondary" @click="recoverReport">获取报告</button></div>
<template v-else><label class="sr-only" for="answer-input">你的回答</label><textarea id="answer-input" v-model="answer" rows="4" placeholder="在这里写下你的回答…可以从背景、做法和结果说起。" :disabled="!canAnswer" @keydown.ctrl.enter.prevent="submit" @keydown.meta.enter.prevent="submit"></textarea><div class="answer-actions"><button class="primary" @click="submit" :disabled="!canAnswer || !answer.trim()">提交回答<app-icon name="arrow"/></button><span class="keyboard-hint">Ctrl / ⌘ + Enter</span><span class="spacer"></span><button class="text-button" @click="end" :disabled="!store.interviewActive || store.busy || ending">结束并出报告</button></div></template>
</div></div></section>`
    };
    const ReportPanel = {
        data: () => ({ loading: false, error: '', showTurns: false, turns: [], trend: null, trendOpen: false, timer: null, selection: 0 }),
        computed: { report() { return store.report; }, reportTurns() { return store.report?.perTurnReview || []; } },
        watch: {
            'store.reportSessionId'() { this.turns = []; this.showTurns = false; this.selection++; },
            'store.report'() { this.turns = []; this.showTurns = false; }
        },
        beforeUnmount() { clearTimeout(this.timer); },
        methods: {
            async refresh() { try { await loadHistory(); } catch (error) { this.error = error.message; } },
            filter() { clearTimeout(this.timer); this.timer = setTimeout(() => this.refresh(), 300); },
            async select(sessionId) {
                const selection = ++this.selection;
                this.loading = true; this.error = '';
                try {
                    const result = await api('/api/interview/' + sessionId + '/report');
                    if (selection === this.selection) {
                        store.report = result; store.reportSessionId = sessionId;
                    }
                } catch (error) { if (!error.stale) this.error = error.message; }
                finally { if (selection === this.selection || store.reportSessionId === sessionId) this.loading = false; }
            },
            async exportMd() {
                try {
                    const text = await api('/api/interview/' + store.reportSessionId + '/report.md', 'GET', undefined, true);
                    const url = URL.createObjectURL(new Blob([text], { type: 'text/markdown;charset=utf-8' }));
                    const anchor = document.createElement('a');
                    anchor.href = url; anchor.download = '面试报告_' + store.reportSessionId + '.md'; anchor.click();
                    setTimeout(() => URL.revokeObjectURL(url), 1000);
                } catch (error) { notify(error, true); }
            },
            async toggleTurns() {
                if (this.showTurns) { this.showTurns = false; return; }
                const sessionId = store.reportSessionId;
                try {
                    const turns = await api('/api/interview/' + sessionId + '/turns');
                    if (store.reportSessionId === sessionId) { this.turns = turns; this.showTurns = true; }
                } catch (error) { notify(error, true); }
            },
            async toggleTrend() {
                this.trendOpen = !this.trendOpen;
                if (this.trendOpen) {
                    try { this.trend = await api('/api/interview/trend'); } catch (error) { notify(error, true); }
                }
            },
            async teach(turn) {
                const sessionId = store.reportSessionId;
                try {
                    const turns = this.turns.length ? this.turns : await api('/api/interview/' + sessionId + '/turns');
                    if (store.reportSessionId !== sessionId) return;
                    this.turns = turns;
                    explainWithCoach(turn.question, turns.find(item => item.seq === turn.turnSeq)?.answer, turn, '面试报告 #' + sessionId + ' · 第 ' + turn.turnSeq + ' 轮');
                } catch (error) { notify(error, true); }
            }
        },
        template: `
<section><div class="page-heading"><div><span class="eyebrow">每次练习，都有收获</span><h1>报告与成长</h1><p>找到最值得改进的一点，带进下一次练习。</p></div><button class="secondary" @click="toggleTrend"><app-icon name="report"/>{{trendOpen?'收起趋势':'查看分数趋势'}}</button></div>
<div v-if="trendOpen" class="surface trend-surface"><h2>历次匹配度</h2><p v-if="!trend?.length" class="empty-copy">完成有评分的面试后，在这里查看变化。</p><ol v-else class="trend-bars"><li v-for="item in trend" :key="item.sessionId"><span>{{fmtTime(item.createdAt)}} · {{item.jobType || '面试'}}</span><span class="trend-track"><i :style="{width:Math.max(0,Math.min(100,item.matchScore))+'%'}"></i></span><b>{{item.matchScore}} / 100</b></li></ol></div>
<div class="report-grid"><aside class="surface history-surface"><div class="section-heading"><h2>练习记录</h2><button class="icon-button" @click="refresh" aria-label="刷新历史"><app-icon name="refresh"/></button></div>
<label class="sr-only" for="history-keyword">搜索 JD 关键词</label><input id="history-keyword" v-model="store.histFilter.keyword" placeholder="搜索 JD 关键词" @input="filter"><div class="history-filters"><select v-model="store.histFilter.status" @change="refresh" aria-label="筛选练习状态"><option value="">全部状态</option><option value="DONE">已结束</option><option value="QUESTIONING">作答中</option><option value="SCORING">评分中</option><option value="INIT">准备中</option></select><input v-model="store.histFilter.minScore" type="number" min="0" max="100" placeholder="最低分" aria-label="最低分" @input="filter"><input v-model="store.histFilter.maxScore" type="number" min="0" max="100" placeholder="最高分" aria-label="最高分" @input="filter"></div>
<p v-if="!store.historyList.length" class="empty-copy">没有找到练习记录。<br>完成第一次练习后，来这里看看收获。</p><div class="history-list"><button v-for="session in store.historyList" :key="session.sessionId" :class="['history-item',{selected:store.reportSessionId===session.sessionId}]" :disabled="!session.hasReport" @click="select(session.sessionId)"><span><strong>{{session.jobType || '模拟面试'}}</strong><small>{{fmtTime(session.createdAt)}} · #{{session.sessionId}}</small><small v-if="session.sameTypeTag">{{session.sameTypeTag}}</small></span><span class="history-score">{{session.matchScore ?? '—'}}<small>{{session.hasReport?'查看报告':'暂无报告'}}</small></span></button></div></aside>
<article class="surface report-surface" :aria-busy="loading"><p v-if="error" class="inline-error" role="alert">{{error}}</p><p v-if="loading" role="status">正在读取报告…</p>
<div v-if="!report" class="empty-state"><span class="empty-symbol"><app-icon name="report"/></span><h2>你的进步，会留在这里</h2><p>选择一份历史报告，或先完成一次练习。</p><button class="secondary" @click="store.view='prepare'">开始练习<app-icon name="arrow"/></button></div>
<template v-else><div class="report-top"><div><span class="eyebrow">Critic 报告 · #{{store.reportSessionId}}</span><h2>这一次，离目标更近了多少？</h2></div><button class="secondary compact" @click="exportMd">导出 Markdown</button></div><div class="report-summary"><div class="match-score"><b>{{report.matchScore ?? '—'}}</b><span>/ 100<br>岗位匹配度</span></div><p>{{report.overallNotes || '查看逐轮反馈，发现下一步的练习方向。'}}</p></div>
<section class="report-section" v-if="report.collapsedPoints?.length"><h3>优先补强</h3><ul class="takeaways"><li v-for="point in report.collapsedPoints" :key="point">{{point}}</li></ul><button class="text-button" @click="store.view='weak'">去错题本巩固<app-icon name="arrow"/></button></section>
<section class="report-section" v-if="report.behaviorReview"><h3>行为面试评估</h3><div class="behavior-dimensions"><span v-for="(value,key) in report.behaviorReview.dimensions" :key="key">{{key}}<b>{{value}} / 2</b></span></div><p>{{report.behaviorReview.summary}}</p><ul><li v-for="suggestion in report.behaviorReview.suggestions" :key="suggestion">{{suggestion}}</li></ul></section>
<section class="report-section"><div class="section-heading"><h3>逐轮反馈</h3><button class="text-button" @click="toggleTurns">{{showTurns?'收起原始问答':'查看原始问答'}}</button></div><p v-if="!reportTurns.length" class="field-note">本场暂无逐轮讲解；如报告生成失败，请保留原始问答。</p>
<details v-for="turn in reportTurns" :key="turn.turnSeq" class="turn"><summary><span class="turn-number">{{String(turn.turnSeq).padStart(2,'0')}}</span><span class="grow">{{turn.question}}<small v-if="turn.track==='behavior'">行为面试</small></span><b>{{turn.score}} / 8</b></summary><div class="turn-detail"><template v-for="field in [{key:'explanation',label:'讲解'},{key:'correction',label:'纠正'},{key:'knowledgeSupplement',label:'知识补充'},{key:'exemplarAnswer',label:'示范回答'}]" :key="field.key"><div v-if="turn[field.key]"><h4>{{field.label}}</h4><p>{{turn[field.key]}}</p></div></template><button class="secondary compact" @click="teach(turn)">让小师讲讲<app-icon name="arrow"/></button></div></details>
<div v-if="showTurns" class="turns-replay"><article v-for="turn in turns" :key="turn.seq"><h4>第 {{turn.seq}} 轮{{turn.layer?' · 追问 '+turn.layer:''}}</h4><strong>{{turn.question}}</strong><p>{{turn.answer || '本轮未作答'}}</p></article></div></section>
<section v-if="report.expressionIssues?.length" class="report-section"><h3>表达改进</h3><div v-for="(issue,index) in report.expressionIssues" :key="index" class="expression-issue"><strong>{{issue.issue}}</strong><p>{{issue.guide}}</p></div></section><section v-if="report.reminders?.length" class="report-section"><h3>带到下一次的提醒</h3><ul><li v-for="reminder in report.reminders" :key="reminder">{{reminder}}</li></ul></section></template></article></div></section>`
    };
    const WeakPanel = {
        data: () => ({ details: {}, loadingDetail: {}, error: '', review: false, queue: [], index: 0,
            question: '', answer: '', result: null, busy: false }),
        methods: {
            async refresh() { try { await loadWeak(); } catch (error) { notify(error, true); } },
            async detail(item, event) {
                if (!event.target.open || this.details[item.weakPointId] || this.loadingDetail[item.weakPointId]) return;
                this.loadingDetail[item.weakPointId] = true;
                try { this.details[item.weakPointId] = await api('/api/weak-point/' + item.weakPointId); }
                catch (error) { notify(error, true); }
                finally { this.loadingDetail[item.weakPointId] = false; }
            },
            teach(item) {
                const latest = this.details[item.weakPointId]?.latest;
                explainWithCoach(latest?.question || item.knowledgePoint, latest?.answer, latest?.review, '错题本 · ' + item.knowledgePoint);
            },
            async start() {
                if (this.busy) return;
                if (this.review) { this.review = false; return; }
                this.busy = true; this.error = '';
                try {
                    this.queue = await api('/api/weak-point/due');
                    this.index = 0; this.review = true;
                    if (this.queue.length) await this.loadQuestion();
                } catch (error) { if (!error.stale) this.error = error.message; }
                finally { this.busy = false; }
            },
            async loadQuestion() {
                this.question = ''; this.answer = ''; this.result = null;
                const result = await api('/api/weak-point/' + this.queue[this.index].weakPointId + '/review/question', 'POST');
                this.question = result.question;
            },
            async submit() {
                if (this.busy || !this.question || !this.answer.trim()) return;
                this.busy = true; this.error = '';
                try {
                    this.result = await api('/api/weak-point/' + this.queue[this.index].weakPointId + '/review/answer', 'POST',
                        { question: this.question, answer: this.answer.trim() });
                    await loadWeak(); await loadQuota();
                } catch (error) { if (!error.stale) this.error = error.message; }
                finally { this.busy = false; }
            },
            async next() {
                if (this.busy) return;
                if (this.index + 1 >= this.queue.length) { this.review = false; return; }
                this.busy = true; this.error = ''; this.index++;
                try { await this.loadQuestion(); } catch (error) { if (!error.stale) this.error = error.message; }
                finally { this.busy = false; }
            }
        },
        template: `
<section><div class="page-heading"><div><span class="eyebrow">把薄弱点练成底气</span><h1>错题与复习</h1><p>按 1、3、7、15 天的节奏，巩固还不熟悉的知识。</p></div><button class="primary" @click="start" :disabled="busy">{{review?'返回错题列表':'开始今日复习'}}<app-icon name="arrow"/></button></div>
<p v-if="error" class="inline-error" role="alert">{{error}}</p><div v-if="review" class="surface review-surface"><p v-if="!queue.length" class="empty-copy">今天没有到期的复习项，可以放心开始新的练习。</p><template v-else><span class="eyebrow">今日复习 · {{index+1}} / {{queue.length}}</span><h2>{{queue[index].knowledgePoint}}</h2><p v-if="busy" role="status">正在处理，请稍候…</p><p class="review-question">{{question}}</p><template v-if="!result"><label for="review-answer" class="field-label">你的回答</label><textarea id="review-answer" rows="5" v-model="answer" :disabled="busy || !question" placeholder="再试一次，看看这次能否说得更清楚。"></textarea><div class="row"><button class="primary" @click="submit" :disabled="busy || !question || !answer.trim()">提交判分</button><button class="text-button" @click="next" :disabled="busy">跳过</button></div></template><div v-else class="review-result"><strong>{{result.score}} / 8</strong><p>{{result.explanation}}</p><p class="quiet">{{result.resolved?'已掌握，本题已出队。':'下次复习：'+fmtTime(result.nextReviewAt)}}</p><button class="primary" @click="next" :disabled="busy">{{index+1<queue.length?'下一题':'完成复习'}}</button></div></template></div>
<div v-else class="surface"><div class="section-heading"><h2>待巩固的知识点</h2><div class="row"><label class="checkbox-label"><input type="checkbox" v-model="store.dueOnly" @change="refresh">只看今日到期</label><button class="icon-button" aria-label="刷新错题" @click="refresh"><app-icon name="refresh"/></button></div></div><div v-if="!store.weakList.length" class="empty-state"><span class="empty-symbol"><app-icon name="leaf"/></span><h2>慢慢积累，不急于一次掌握</h2><p>练习中需要巩固的知识点，会自动收进这里。</p></div><details v-for="item in store.weakList" :key="item.weakPointId" class="weak-point" @toggle="detail(item,$event)"><summary><span class="grow"><strong>{{item.knowledgePoint}}</strong><small>错 {{item.wrongCount || 0}} 次 · {{item.resolved?'已掌握':'复习阶段 '+(item.reviewStage || 0)}}{{item.nextReviewAt?' · '+fmtTime(item.nextReviewAt):''}}</small></span><span class="quiet">查看详情</span></summary><div class="weak-detail"><p v-if="loadingDetail[item.weakPointId]">正在读取…</p><template v-else-if="details[item.weakPointId]?.latest"><h4>最近一次练习</h4><strong>{{details[item.weakPointId].latest.question}}</strong><p>你的回答：{{details[item.weakPointId].latest.answer}}</p><template v-if="details[item.weakPointId].latest.review"><h4>反馈与纠正</h4><p>{{details[item.weakPointId].latest.review.explanation}}</p><p>{{details[item.weakPointId].latest.review.correction}}</p></template><button class="secondary compact" @click="teach(item)">让小师讲讲<app-icon name="arrow"/></button></template><p v-else>暂时没有可回看的原始问答。</p></div></details></div></section>`
    };
    // Render only these two useful teaching conventions. All output remains escaped Vue text.
    const BuddyText = {
        props: ['text'],
        computed: {
            blocks() {
                const result = [];
                let lines = [], code = false, fenceLength = 0, language = '';
                const flush = () => {
                    const text = lines.join('\n');
                    if (text || code) result.push({ code, language, text });
                    lines = [];
                };
                for (const line of String(this.text || '').split('\n')) {
                    const fence = line.match(/^ {0,3}(`{3,})([^`]*)$/);
                    if (!code && fence && /^[a-zA-Z0-9_+.#-]*\s*$/.test(fence[2])) {
                        flush(); code = true; fenceLength = fence[1].length; language = fence[2].trim();
                    } else if (code && fence && !fence[2].trim() && fence[1].length >= fenceLength) {
                        flush(); code = false; language = '';
                    } else lines.push(line);
                }
                flush();
                return result;
            }
        },
        methods: {
            inline(text) {
                const parts = [], bold = /\*\*([^*\n]+)\*\*/g;
                let from = 0, match;
                while ((match = bold.exec(text))) {
                    if (match.index > from) parts.push({ text: text.slice(from, match.index), bold: false });
                    parts.push({ text: match[1], bold: true }); from = bold.lastIndex;
                }
                if (from < text.length) parts.push({ text: text.slice(from), bold: false });
                return parts;
            }
        },
        template: `<div class="buddy-prose"><template v-for="(block,index) in blocks" :key="index"><div v-if="block.code" class="buddy-code"><small v-if="block.language" class="buddy-code-language">{{block.language}}</small><pre><code>{{block.text}}</code></pre></div><p v-else><template v-for="(part,partIndex) in inline(block.text)" :key="partIndex"><strong v-if="part.bold">{{part.text}}</strong><template v-else>{{part.text}}</template></template></p></template></div>`
    };
    const BuddyMemory = {
        props: ['mode'],
        data: () => ({ editing: null, value: '' }),
        computed: { memory() { return store.buddyMemory; } },
        methods: {
            refresh: refreshBuddyMemory,
            keyLabel(key) { return ({ target_role: '目标岗位', preparation_stage: '备考阶段', available_time: '通常可用时间', explanation_style: '讲解偏好', practice_preference: '练习偏好', support_style: '陪伴偏好', address_preference: '称呼偏好' })[key] || '备考偏好'; },
            scopeLabel(entry) { return ({ SHARED: '共同备考目标', COACH: '小师讲解偏好', COMPANION: '小暖沟通偏好' })[String(entry.scope).toUpperCase()] || '备考偏好'; },
            edit(entry) { this.editing = entry.key; this.value = entry.value; },
            async change(path, method, body) {
                if (this.memory.busy) return;
                const memory = this.memory;
                buddyMemorySequence++;
                memory.busy = true; memory.error = '';
                try { acceptBuddyMemory(await api('/api/buddy/memory' + path, method, body)); this.editing = null; }
                catch (error) { if (!error.stale) memory.error = error.message; }
                finally { memory.busy = false; }
            },
            remove(entry) {
                if (confirm('删除这条记忆？删除后不会再从旧对话自动恢复。')) this.change('/entries/' + encodeURIComponent(entry.key), 'DELETE');
            },
            clear() {
                if (confirm('清空全部长期记忆？本次聊天仍会保留；旧对话不会自动恢复这些记忆。')) this.change('/entries', 'DELETE');
            }
        },
        template: `<section class="buddy-memory"><div class="section-heading"><h3>记住了什么</h3><button class="text-button" @click="refresh" :disabled="memory.busy">刷新记忆</button></div><p>由你决定是否开启。开启后，尝试整理你明确说出的备考目标、讲解和沟通偏好；不保存整段聊天，也不推断人格或病症。</p><button class="secondary compact" :aria-pressed="memory.enabled" @click="change('','PATCH',{enabled:!memory.enabled})" :disabled="memory.busy || !memory.loaded">{{memory.enabled?'关闭记忆':'开启记忆'}}</button><p class="buddy-boundary">{{memory.enabled?'记忆已开启，可随时修改、删除或清空。':'记忆已关闭：后续发起的对话不再读取或整理长期记忆；保留的条目仍可查看或删除。'}}</p><p v-if="!memory.enabled && (store.buddies.coach.busy || store.buddies.companion.busy)" class="buddy-boundary">已开始的回复可能已读取当时的偏好；当前聊天仍会参考你刚说过的内容。</p><p v-if="memory.error" class="inline-error" role="alert">{{memory.error}}</p><details v-if="memory.entries.length" class="buddy-memory-list"><summary>查看记忆（{{memory.entries.length}}）</summary><article v-for="entry in memory.entries" :key="entry.key"><strong>{{keyLabel(entry.key)}} · {{scopeLabel(entry)}}</strong><template v-if="editing===entry.key"><label class="sr-only" :for="'memory-'+mode+'-'+entry.key">修改记忆</label><textarea :id="'memory-'+mode+'-'+entry.key" v-model="value" rows="3" maxlength="300"></textarea><div class="row wrap"><button class="text-button" :disabled="memory.busy || !value.trim()" @click="change('/entries/'+encodeURIComponent(entry.key),'PATCH',{value:value.trim()})">保存记忆</button><button class="text-button" @click="editing=null">取消</button></div></template><template v-else><p>{{entry.value}}</p><small>{{entry.locked?'由你修改，自动整理不会覆盖':'来自你明确表达的目标或偏好'}} · {{fmtTime(entry.updatedAt)}}</small><div class="row wrap"><button class="text-button" :disabled="memory.busy" @click="edit(entry)">修改</button><button class="text-button danger-text" :disabled="memory.busy" @click="remove(entry)">删除</button></div></template></article><button class="text-button danger-text" :disabled="memory.busy" @click="clear">清空全部记忆</button></details><p v-else-if="memory.loaded" class="field-note">还没有保存的记忆。</p><div v-if="memory.blockedKeys?.length" class="buddy-memory-blocked"><p>已停止自动记录：</p><div v-for="key in memory.blockedKeys" :key="key" class="row wrap"><span>{{keyLabel(key)}}</span><button class="text-button" :disabled="memory.busy" @click="change('/entries/'+encodeURIComponent(key)+'/allow','POST')">允许重新记住</button></div><small>重新允许后，只会根据你接下来明确说出的信息整理。</small></div></section>`
    };
    const CompanionPanel = {
        components: { BuddyMemory, BuddyText },
        props: { mode: { type: String, default: 'companion' } },
        computed: {
            profile() { return buddyProfiles[this.mode] || buddyProfiles.companion; },
            chat() { return store.buddies[this.profile.id]; }
        },
        mounted() { if (store.view === this.profile.id && !store.buddyMemory.loaded) refreshBuddyMemory(); },
        watch: {
            'chat.messages': { deep: true, handler() { this.follow(); } },
            'store.view'(view) { if (view === this.profile.id) this.follow(); }
        },
        methods: {
            follow() { nextTick(() => { const el = this.$refs.chat; if (el && this.chat.following && store.view === this.profile.id) el.scrollTop = el.scrollHeight; }); },
            scrolled() { const el = this.$refs.chat; this.chat.following = el.scrollHeight - el.scrollTop - el.clientHeight < 85; },
            latest() { this.chat.following = true; this.follow(); },
            async send(text) {
                const chat = this.chat;
                text = typeof text === 'string' ? text : chat.input.trim();
                if (!text || chat.busy) return;
                if (typeof text === 'string' && chat.input.trim() === text) chat.input = '';
                chat.error = ''; chat.busy = true; chat.status = this.profile.shortName + '正在回应…';
                let user;
                if (chat.retry?.text === text) {
                    user = chat.messages.find(item => item.id === chat.retry.userId);
                    chat.messages = chat.messages.filter(item => item.id !== chat.retry.assistantId);
                }
                if (user) user.failed = false;
                else { user = { id: ++chat.nextId, role: 'user', text, failed: false }; chat.messages.push(user); }
                const reply = { id: ++chat.nextId, role: 'assistant', text: '', partial: false };
                chat.messages.push(reply);
                chat.retry = null;
                const epoch = store.accountEpoch;
                try {
                    await streamBuddy(this.profile.stream, text, (event, data) => {
                        // Mutate through the reactive array so every genuine model fragment is rendered.
                        const item = chat.messages.find(item => item.id === reply.id);
                        if (event === 'delta' && typeof data.text === 'string') item.text += data.text;
                        if (event === 'done') { item.text = data.reply; item.safe = data.safe === true; }
                    });
                    chat.status = this.profile.shortName + '已回复。';
                    loadQuota().catch(error => notify(error, true));
                    refreshBuddyMemory();
                } catch (error) {
                    if (!error.stale && epoch === store.accountEpoch) {
                        chat.error = error.message;
                        if (!chat.input.trim()) chat.input = text;
                        const item = chat.messages.find(item => item.id === reply.id);
                        if (item.text) item.partial = true;
                        else chat.messages = chat.messages.filter(item => item.id !== reply.id);
                        user.failed = true;
                        chat.retry = { text, userId: user.id, assistantId: reply.id };
                        chat.status = '回复尚未完成，原问题已保留。';
                        // A partial generation can already be billed; a lost done can also have saved preferences.
                        // Reconcile quietly so refresh failures never replace the original error or retry draft.
                        loadQuota().catch(() => {});
                        if (item.text) refreshBuddyMemory();
                    }
                } finally { if (epoch === store.accountEpoch) chat.busy = false; }
            },
            async reset() {
                const chat = this.chat;
                if (chat.busy || !confirm('清空与' + this.profile.name + '的本次对话？长期记忆另行管理，不会一并删除。')) return;
                chat.busy = true;
                try { await api(this.profile.reset, 'POST'); Object.assign(chat, buddyState()); }
                catch (error) { if (!error.stale) chat.error = error.message; }
                finally { chat.busy = false; }
            },
            back() { store.view = store.buddyReturnView || (store.interviewActive ? 'interview' : 'prepare'); }
        },
        template: `
<section :class="['buddy-page',profile.tone]"><div class="page-heading"><div><span class="eyebrow">{{profile.role}} · 备考路上的 AI 搭档</span><h1>{{profile.name}}</h1><p>{{profile.tagline}}</p></div><button class="secondary" @click="back">返回刚才的页面<app-icon name="arrow"/></button></div>
<div class="buddy-introduction"><buddy-avatar :tone="profile.tone"/><div><span class="eyebrow">{{profile.name}} · {{profile.role}}</span><p>{{profile.introduction}}</p></div></div>
<div class="companion-grid"><div class="surface companion-room"><div class="companion-heading"><buddy-avatar :tone="profile.tone"/><div><h2>{{profile.shortName}}在这里</h2><p>{{mode==='coach'?'先弄懂，再用一道短题试试；随时可以跳过。':profile.tagline}}</p></div><span class="spacer"></span><button class="text-button" @click="reset" :disabled="chat.busy">清空对话</button></div>
<div class="companion-chat" ref="chat" role="region" :aria-label="'与'+profile.shortName+'的对话'" tabindex="0" @scroll="scrolled"><div v-if="!chat.messages.length" class="companion-welcome"><h2>{{profile.welcome}}</h2><p>{{profile.description}}</p><div class="starter-list"><button v-for="starter in profile.starters" :key="starter" @click="send(starter)" :disabled="chat.busy">{{starter}}<app-icon name="arrow"/></button></div></div><article v-for="message in chat.messages" :key="message.id" :class="['companion-message',message.role,{'safety-message':message.safe,'partial-message':message.partial}]"><strong>{{message.role==='user'?'我':profile.shortName}}</strong><buddy-text v-if="message.text" :text="message.text"/><small v-if="message.failed" class="danger-text">这次尚未完成，可以重试原问题。</small><small v-if="message.partial" class="danger-text">以下是未完成的回复，重试后会替换。</small></article></div>
<div class="buddy-chat-status"><span role="status" aria-live="polite">{{chat.status}}</span><button v-if="!chat.following" class="text-button" @click="latest">回到最新回复</button></div>
<div class="companion-compose"><p v-if="chat.error" class="inline-error" role="alert">{{chat.error}}</p><div v-if="chat.retry && !chat.busy" class="buddy-retry"><button class="secondary compact" @click="send(chat.retry.text)">重试原问题</button><span class="field-note">将重新发起调用，可能再次扣减积分。</span></div><div class="buddy-actions" role="group" :aria-label="profile.shortName+'对话快捷操作'"><button v-for="action in profile.actions" :key="action" class="text-button" @click="send(action)" :disabled="chat.busy">{{action}}</button></div><label class="sr-only" :for="'buddy-input-'+profile.id">{{profile.label}}</label><textarea :id="'buddy-input-'+profile.id" v-model="chat.input" rows="3" maxlength="6000" :placeholder="chat.busy?'可以先写下接下来想说的话…':profile.placeholder" @keydown.ctrl.enter.prevent="send()" @keydown.meta.enter.prevent="send()"></textarea><div class="row"><span class="field-note">{{chat.busy?'回应中，可先写下一条或切换页面。':'Ctrl / ⌘ + Enter 发送'}}</span><span class="spacer"></span><button class="primary" @click="send()" :disabled="chat.busy || !chat.input.trim()">发送<app-icon name="arrow"/></button></div></div></div>
<aside class="companion-aside"><template v-if="mode==='companion'"><span class="eyebrow">不急，我们慢慢来</span><h2>先照顾好自己，<br>再面对下一题。</h2><p>想倾诉，我们就先听你说；想找方向，我们就一起理一理。你可以随时告诉小暖，怎样陪你更舒服。</p><p class="buddy-boundary">AI 陪伴不替代专业心理支持，不做诊断。情绪聊天不会自动加入资料库；当前对话只短期保留。</p></template><template v-else><span class="eyebrow">一点点弄懂，就在进步</span><h2>不怕暂时不会，<br>我们从这里开始。</h2><p>先用例子建立直觉，再回到原理和面试考点。答不上来也没关系，可以看提示、看答案，或今天先不测。</p><p class="buddy-boundary">小师参考你发来的内容。也可以从报告或错题选择片段带来，不会自动读取全部资料；当前对话只短期保留。</p><button class="text-button" @click="store.view='history'">查看我的练习报告<app-icon name="arrow"/></button></template><buddy-memory :mode="mode"/><hr><template v-if="mode==='companion'"><h3>想把难点弄明白？</h3><p>让小师陪你讲清知识、拆解题目，找到下一步的学习方向。</p><button class="secondary" @click="goBuddy('coach')"><buddy-avatar tone="study"/>去找通考小师<app-icon name="arrow"/></button></template><template v-else><h3>累了，先缓一缓</h3><p>学不进去时也不必苛责自己。小暖在另一边，愿意听你说。</p><button class="secondary" @click="goBuddy('companion')"><buddy-avatar tone="warm"/>去找陪考小暖<app-icon name="arrow"/></button></template></aside></div></section>`
    };

    const SettingsPanel = {
        data: () => ({ key: '', busy: false, usage: [], loaded: false, error: '' }),
        methods: {
            async update(field, value) {
                if (this.busy) return;
                this.busy = true; this.error = '';
                try {
                    const result = await api('/api/user/settings', 'PUT', { [field]: value });
                    store.keyMode = result.keyMode; store.chatProvider = result.chatProvider;
                    notify('设置已保存。');
                } catch (error) { if (!error.stale) this.error = error.message; }
                finally { this.busy = false; }
            },
            async saveKey() {
                if (this.busy || !this.key.trim()) return;
                this.busy = true; this.error = '';
                try {
                    const result = await api('/api/user/api-key', 'PUT', { apiKey: this.key.trim() });
                    store.hasKey = result.hasKey; store.last4 = result.last4; this.key = '';
                    const settings = await api('/api/user/settings', 'PUT', { keyMode: 'BYOK' });
                    store.keyMode = settings.keyMode; store.chatProvider = settings.chatProvider;
                    notify('Key 已保存，已切换为自带 Key 模式。');
                } catch (error) { if (!error.stale) this.error = error.message; }
                finally { this.busy = false; }
            },
            async removeKey() {
                if (this.busy || !confirm('删除自带 Key 并切回平台额度？')) return;
                this.busy = true; this.error = '';
                try {
                    await api('/api/user/api-key', 'DELETE'); this.key = '';
                    store.hasKey = false; store.last4 = ''; store.keyMode = 'PLATFORM';
                    notify('自带 Key 已删除。');
                } catch (error) { if (!error.stale) this.error = error.message; }
                finally { this.busy = false; }
            },
            async refresh() {
                this.error = '';
                try {
                    await Promise.all([loadSettings(), loadQuota()]);
                    this.usage = await api('/api/user/usage'); this.loaded = true;
                } catch (error) { if (!error.stale) this.error = error.message; }
            }
        },
        template: `
<section><div class="page-heading"><div><span class="eyebrow">按你的方式练习</span><h1>账号与设置</h1><p>管理模型、额度和调用方式。</p></div><button class="secondary" @click="refresh"><app-icon name="refresh"/>刷新设置与用量</button></div><p v-if="error" class="inline-error" role="alert">{{error}}</p>
<div class="settings-grid"><section class="surface"><h2>对话模型</h2><p class="quiet">切换后用于后续调用，当前正在处理的请求不受影响。</p><div class="setting-choices"><button v-for="provider in [{id:'DEEPSEEK',name:'DeepSeek'},{id:'QWEN',name:'通义千问'}]" :key="provider.id" :aria-pressed="store.chatProvider===provider.id" @click="update('chatProvider',provider.id)" :disabled="busy"><strong>{{provider.name}}</strong><app-icon v-if="store.chatProvider===provider.id" name="check"/></button></div>
<h3>调用方式</h3><div class="segmented"><button :aria-pressed="store.keyMode==='PLATFORM'" @click="update('keyMode','PLATFORM')" :disabled="busy">平台额度</button><button :aria-pressed="store.keyMode==='BYOK'" @click="update('keyMode','BYOK')" :disabled="busy">自带 DeepSeek Key</button></div><p class="field-note">通义千问始终使用平台额度。自带 Key 仅适用于 DeepSeek；资料向量化仍使用平台额度。</p>
<h3>DeepSeek API Key</h3><p class="field-note">{{store.hasKey?'已保存，末四位 '+store.last4:'尚未保存自带 Key'}}</p><label for="byok-key" class="sr-only">DeepSeek API Key</label><input id="byok-key" v-model="key" type="password" autocomplete="off" placeholder="输入 sk- 开头的 Key" :disabled="busy"><div class="row wrap"><button class="primary" @click="saveKey" :disabled="busy || !key.trim()">{{busy?'正在保存…':'保存并启用自带 Key'}}</button><button class="text-button danger-text" v-if="store.hasKey" @click="removeKey" :disabled="busy">删除 Key</button></div><p class="field-note">Key 加密保存在服务端，保存后不再显示明文。</p></section>
<section class="surface quota-surface"><span class="eyebrow">我的积分</span><div class="quota-value">{{formatPoints(store.quota?.remainingTokens)}}<span>积分</span></div><p class="quiet">累计使用 {{formatPoints(store.quota?.usedTokens)}} 积分 / 总积分 {{formatPoints(store.quota?.totalTokens)}}</p><hr><h3>积分如何使用？</h3><p>新用户注册赠送 100 积分。使用平台模型，按实际用量折算扣除积分；小额用量也会如实记录。</p><p>自带 DeepSeek Key：每次模型调用收取 1 积分服务费，模型费用由你的 Key 支付。一轮面试可能包含出题、判分等多次调用。</p><p>通义千问与资料向量化始终按平台实际用量扣积分。</p><quota-contact/></section></div>
<section class="surface usage-surface"><div class="section-heading"><h2>积分明细</h2><button class="text-button" @click="refresh">{{loaded?'刷新':'加载用量'}}</button></div><p v-if="!usage.length" class="empty-copy">{{loaded?'暂无积分使用记录。':'点击加载，查看最近的模型调用和积分扣减。'}}</p><div v-else class="table-scroll"><table><thead><tr><th>时间</th><th>场景</th><th>模型</th><th>计费方式</th><th>扣除积分</th></tr></thead><tbody><tr v-for="row in usage" :key="row.id"><td>{{fmtTime(row.createdAt)}}</td><td>{{row.scene}}</td><td>{{row.model}}</td><td>{{row.keyMode==='BYOK'?'自带 Key 服务费':'平台用量'}}</td><td>{{formatPoints(row.chargedTokens)}}</td></tr></tbody></table></div></section></section>`
    };
    const QuotaContact = {
        template: `<div class="quota-contact"><p>平台目前处于内测阶段，请联系工作人员补充额度。</p><div class="support-wechat"><span>工作人员微信</span><strong>13481264947</strong></div></div>`
    };
    const App = {
        components: { AuthPanel, PreparePanel, MaterialsPanel, InterviewPanel, ReportPanel, WeakPanel, CompanionPanel, SettingsPanel },
        data: () => ({ timer: null, navigation: [
            { id: 'prepare', name: '开始练习', icon: 'practice', caption: '准备你的下一场面试' },
            { id: 'interview', name: '面试工作区', icon: 'interview', caption: '专注回答，从容表达' },
            { id: 'history', name: '报告与成长', icon: 'report', caption: '看见每一次进步' },
            { id: 'weak', name: '错题本', icon: 'leaf', caption: '把薄弱点练成底气' },
            { id: 'materials', name: '我的资料', icon: 'folder', caption: '让练习更了解你' },
            { id: 'companion', name: '陪考小暖', icon: 'spark', caption: '给自己留一点空间' },
            { id: 'coach', name: '通考小师', icon: 'practice', caption: '把不会的，一点点弄懂' },
            { id: 'settings', name: '账号与设置', icon: 'settings', caption: '按你的方式练习' }
        ] }),
        computed: { currentNav() { return this.navigation.find(item => item.id === store.view) || this.navigation[0]; } },
        watch: {
            'store.view'() { nextTick(() => window.scrollTo({ top: 0, behavior: 'auto' })); },
            'store.quotaOpen'(open) {
                if (open) nextTick(() => this.$refs.quotaDialog?.showModal());
            }
        },
        mounted() {
            this.timer = setInterval(() => {
                if (store.pendingSince) store.elapsed = Math.floor((Date.now() - store.pendingSince) / 1000);
            }, 1000);
        },
        beforeUnmount() { clearInterval(this.timer); closeStream(); },
        methods: {
            navigate(view) {
                if (['companion', 'coach'].includes(view)) goBuddy(view);
                else store.view = view;
            },
            logout() {
                if ((store.interviewActive || store.busy) && !confirm('本场练习仍在进行，退出会关闭当前连接。确认退出？')) return;
                resetAccount();
            },
            closeQuota() { store.quotaOpen = false; store.view = 'settings'; }
        },
        template: `
<div><auth-panel v-if="!store.token"/>
<div v-else class="app-shell" :key="store.accountEpoch">
  <aside class="sidebar"><a href="#" class="brand" @click.prevent="store.view='prepare'"><span class="brand-mark"><app-icon name="spark"/></span><span>面试练习室<small>INTERVIEW STUDIO</small></span></a><span class="sidebar-label">我的练习空间</span><nav aria-label="主导航"><button v-for="item in navigation" :key="item.id" :class="['nav-item',{active:store.view===item.id}]" :aria-label="item.name" :aria-current="store.view===item.id?'page':null" @click="navigate(item.id)"><app-icon :name="item.icon"/><span>{{item.name}}</span><span v-if="item.id==='interview' && store.interviewActive" class="nav-dot" aria-label="面试进行中"></span></button></nav><div class="sidebar-bottom"><div class="sidebar-quote"><span class="eyebrow">一点点，更从容</span><p>最好的准备，<br>是再练习一次。</p></div><div class="account"><span class="avatar">{{store.username.slice(0,1).toUpperCase()}}</span><span class="grow"><strong>{{store.username}}</strong><small>我的练习账号</small></span><button class="icon-button" aria-label="退出登录" @click="logout"><app-icon name="logout"/></button></div></div></aside>
  <div class="workspace"><header class="workspace-header"><span>我的练习空间 <span class="breadcrumb-slash">/</span><strong>{{currentNav.name}}</strong></span><div class="header-tools"><buddy-links/><button class="balance-pill" @click="store.view='settings'"><span class="status-dot online"></span>{{formatPoints(store.quota?.remainingTokens)}}<small>积分</small></button></div></header>
  <main id="main-content" class="workspace-content"><div v-if="store.notice" :class="['notice',{'notice-error':store.notice.error}]" :role="store.notice.error?'alert':'status'"><span>{{store.notice.message}}</span><button class="icon-button" aria-label="关闭提示" @click="store.notice=null">×</button></div>
    <prepare-panel v-show="store.view==='prepare'"/><materials-panel v-show="store.view==='materials'"/><interview-panel v-show="store.view==='interview'"/><report-panel v-show="store.view==='history'"/><weak-panel v-show="store.view==='weak'"/><companion-panel v-show="store.view==='companion'" mode="companion"/><companion-panel v-show="store.view==='coach'" mode="coach"/><settings-panel v-show="store.view==='settings'"/>
    <footer class="workspace-footer"><span>每一次练习，都算数。</span><span>AI 反馈供练习参考，请结合真实岗位要求判断。</span></footer>
  </main></div>
</div>
<dialog v-if="store.quotaOpen" ref="quotaDialog" class="quota-dialog" aria-labelledby="quota-title" @cancel="store.quotaOpen=false"><span class="eyebrow">先休息一下</span><h2 id="quota-title">本次调用的积分不足</h2><p>回答草稿会保留，补充积分后可以继续练习。自带 Key 每次模型调用需要 1 积分。</p><quota-contact/><button class="primary large" @click="closeQuota" autofocus>查看积分与设置<app-icon name="arrow"/></button></dialog>
</div>`
    };
    const app = createApp(App);
    app.component('quota-contact', QuotaContact);
    app.component('app-icon', Icon);
    app.component('buddy-avatar', BuddyAvatar);
    app.component('buddy-links', BuddyLinks);
    Object.assign(app.config.globalProperties, { store, fmtTime, formatPoints, goBuddy });
    app.mount('#app');
})();
