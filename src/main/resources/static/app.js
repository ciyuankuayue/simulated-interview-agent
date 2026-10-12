/* AI 模拟面试 · Vue3 前端（F12 重构）
 *
 * 从 Thymeleaf + 原生 JS 单文件迁移到 Vue3 CDN（无 npm 构建链）。
 * 组件化拆分：认证 / 准备 / 面试 / 报告 / 错题本 / 心理陪伴。
 * 共享状态用 reactive store，SSE 面试流在 InterviewPanel 组件内管理。
 */
(function () {
    'use strict';

    const { createApp, reactive, ref, computed, onMounted, nextTick, watch } = Vue;

    // ==================== 共享状态 Store ====================
    const store = reactive({
        token: null,
        username: null,
        jdId: null,
        jdList: [],
        resumeId: null,
        sessionId: null,
        interviewActive: false,
        awaiting: false,
        reportSessionId: null,
        quota: null,
        chatMessages: [],      // 面试对话消息列表
        report: null,          // 当前 Critic 报告
        stateText: '未开始',
        connOk: false,
        connText: '未连接',
        // 用户设置
        keyMode: 'PLATFORM',
        byokKey: '',
        byokStatus: '',
        chatProvider: 'DEEPSEEK',
        // 错题本
        weakList: [],
        dueOnly: false,
        // 历史
        historyList: [],
        histFilter: { keyword: '', status: '', minScore: '', maxScore: '' },
        // 视图切换
        view: 'prepare',
    });

    // ==================== 工具函数 ====================
    function esc(s) {
        return String(s == null ? '' : s).replace(/[&<>"']/g, (c) =>
            ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
    }

    function fmtTime(iso) {
        if (!iso) return '—';
        const d = new Date(iso);
        return isNaN(d) ? iso : d.toLocaleString('zh-CN', { month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' });
    }

    function formatTokens(n) {
        if (n == null) return '—';
        if (n >= 10000) return (n / 10000).toFixed(1) + ' 万';
        return String(n);
    }

    function formatPoints(tokens) {
        // 50 万 token = 1000 积分
        if (tokens == null) return '—';
        return Math.round(tokens / 500).toString();
    }

    async function api(path, method, body) {
        const headers = { 'Content-Type': 'application/json; charset=utf-8' };
        if (store.token) headers['Authorization'] = 'Bearer ' + store.token;
        const opt = { method: method || 'GET', headers: headers };
        if (body !== undefined) opt.body = JSON.stringify(body);
        let res;
        try { res = await fetch(path, opt); }
        catch (e) { throw new Error('请求失败（后端没起？）: ' + e.message); }
        const text = await res.text();
        let data = null;
        try { data = text ? JSON.parse(text) : null; } catch (e) { data = text; }
        if (!res.ok) {
            const msg = (data && data.message) ? data.message : (typeof data === 'string' ? data : res.status);
            if (res.status === 401) {
                if (path === '/api/auth/login') throw new Error(msg || '用户名或密码错误');
                forceLogout('登录已过期，请重新登录');
                throw new Error('登录已过期，请重新登录');
            }
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

    function forceLogout(message) {
        store.token = null;
        store.username = null;
        store.quota = null;
        closeStream();
        hideQuotaModal();
        showAuthModal('login', message);
    }

    function showQuotaModal(serverMessage) {
        const m = document.getElementById('quotaModal');
        if (m) {
            m.hidden = false;
            document.getElementById('quotaModalMsg').textContent =
                serverMessage || '上传资料、开始面试都需要积分。请联系管理员充值。';
            document.getElementById('quotaModalUser').textContent = store.username || '—';
            document.getElementById('quotaModalRemain').textContent =
                store.quota ? formatPoints(store.quota.remainingTokens) : '0';
        }
    }
    function hideQuotaModal() { const m = document.getElementById('quotaModal'); if (m) m.hidden = true; }

    async function loadQuota() {
        try {
            store.quota = await api('/api/billing/quota');
        } catch (e) { /* 额度加载失败不打扰 */ }
    }

    // ==================== SSE 面试流 ====================
    let es = null;
    let rendered = new Set();
    let sseFails = 0;

    function openStream(sessionId) {
        closeStream();
        const url = '/api/interview/' + sessionId + '/events'
            + (store.token ? '?jwt=' + encodeURIComponent(store.token) : '');
        es = new EventSource(url);
        sseFails = 0;

        es.onopen = () => { sseFails = 0; store.connOk = true; store.connText = 'SSE 已连接'; };

        const on = (type, fn) => es.addEventListener(type, (e) => {
            let ev = {};
            try { ev = JSON.parse(e.data); } catch (err) { ev = {}; }
            if (e.data != null) sseFails = 0;
            const key = type + ':' + (ev.turnSeq == null ? '' : ev.turnSeq);
            if (ev.turnSeq == null || !rendered.has(key)) {
                rendered.add(key);
                fn(ev);
            }
        });

        on('question', (ev) => {
            const layer = ev.layer ? ' · 追问 L' + ev.layer : '';
            store.chatMessages.push({ cls: 'q', title: (ev.role || '面试官') + ' · 第 ' + ev.turnSeq + ' 题' + layer, body: ev.question });
            store.stateText = '等你作答';
        });
        on('followup', (ev) => {
            const layer = ev.layer ? ' · 追问 L' + ev.layer : '';
            store.chatMessages.push({ cls: 'q', title: (ev.role || '面试官') + ' · 第 ' + ev.turnSeq + ' 题' + layer, body: ev.question });
            store.stateText = '等你作答（追问）';
        });
        on('score', (ev) => {
            const s = ev.score == null ? '—' : ev.score;
            const pct = Math.max(0, Math.min(100, (Number(s) / 8) * 100));
            const badge = ev.track === 'behavior' ? '<span class="trackBadge">行为评分</span>' : '';
            const html = '<div class="scoreRow"><span class="scoreNum">' + esc(s) + '</span><span class="scoreDen">/ 8</span>' +
                '<span class="bar"><i style="width:' + pct + '%"></i></span></div>' +
                (ev.oneLineCorrection ? '<div class="correction">一句纠正：' + esc(ev.oneLineCorrection) + '</div>' : '');
            store.chatMessages.push({ cls: 'score', title: '本轮评分（第 ' + ev.turnSeq + ' 轮）' + badge, html: html });
        });
        on('await_answer', () => {
            store.awaiting = true;
            store.stateText = '等你作答';
        });
        on('report', (r) => { renderReport(r); });
        on('error', (ev) => {
            if (ev && ev.code) {
                store.chatMessages.push({ cls: 'err', title: '异常 · ' + ev.code, body: ev.message || '' });
            } else {
                sseFails++;
                if (sseFails >= 3) {
                    closeStream();
                    store.connOk = false; store.connText = 'SSE 已断开';
                } else {
                    store.connOk = false; store.connText = 'SSE 断开（自动重连 ' + sseFails + '/3）';
                }
            }
        });
        on('done', async () => {
            store.connOk = false; store.connText = '本场结束';
            store.awaiting = false;
            closeStream();
            if (!store.report && store.sessionId) {
                store.stateText = 'Critic 出报告中…';
                try {
                    const r = await api('/api/interview/' + store.sessionId + '/end', 'POST');
                    renderReport(r);
                } catch (e) { /* 报告失败提示由调用方处理 */ }
            }
            if (!store.report) store.stateText = '已结束';
            loadWeak();
            loadHistory();
            loadQuota();
        });
    }

    function closeStream() {
        if (es) { es.close(); es = null; }
    }

    function renderReport(r, sessionId) {
        if (store.report) return;
        store.report = r;
        store.reportSessionId = sessionId || store.sessionId;
        store.stateText = '已完成，报告已出';
    }

    // ==================== 认证弹窗控制 ====================
    let authTab = 'login';
    let authError = '';
    function showAuthModal(tab, error) {
        authTab = tab || 'login';
        authError = error || '';
        // 通过自定义事件通知 Auth 组件
        window.dispatchEvent(new CustomEvent('show-auth', { detail: { tab: authTab, error: authError } }));
    }

    // ==================== 数据加载 ====================
    async function refreshJd() {
        try {
            store.jdList = await api('/api/jd');
        } catch (e) { /* 忽略 */ }
    }

    async function loadHistory() {
        try {
            const params = new URLSearchParams();
            const f = store.histFilter;
            if (f.keyword) params.set('keyword', f.keyword);
            if (f.status) params.set('status', f.status);
            if (f.minScore) params.set('minScore', f.minScore);
            if (f.maxScore) params.set('maxScore', f.maxScore);
            const qs = params.toString();
            store.historyList = await api('/api/interview/sessions' + (qs ? '?' + qs : ''));
        } catch (e) { /* 忽略 */ }
    }

    async function loadWeak() {
        try {
            const arr = await api('/api/weak-point' + (store.dueOnly ? '/due' : ''));
            store.weakList = arr;
        } catch (e) { /* 忽略 */ }
    }

    async function viewHistoryReport(sessionId) {
        try {
            store.report = await api('/api/interview/' + sessionId + '/report');
            store.reportSessionId = sessionId;
            store.view = 'history';
        } catch (e) { alert(e.message); }
    }

    // ==================== 组件：认证 ====================
    const AuthPanel = {
        data() {
            return {
                tab: 'login',
                error: '',
                loginUser: '', loginPwd: '',
                regUser: '', regPwd: '', regPwd2: '',
                loading: false,
                showLoginPwd: false, showRegPwd: false, showRegPwd2: false,
            };
        },
        mounted() {
            window.addEventListener('show-auth', (e) => {
                this.tab = e.detail.tab;
                this.error = e.detail.error || '';
            });
        },
        methods: {
            switchTab(t) { this.tab = t; this.error = ''; },
            clearError() { this.error = ''; },
            async doLogin() {
                if (!this.loginUser || !this.loginPwd) { this.error = '请输入用户名和密码'; return; }
                this.loading = true;
                try {
                    const r = await api('/api/auth/login', 'POST', { username: this.loginUser, password: this.loginPwd });
                    await handleAuthSuccess(r);
                } catch (e) { this.error = e.message; }
                finally { this.loading = false; }
            },
            async doRegister() {
                if (!this.regUser || !this.regPwd) { this.error = '请输入用户名和密码'; return; }
                if (this.regPwd !== this.regPwd2) { this.error = '两次密码不一致'; return; }
                this.loading = true;
                try {
                    const r = await api('/api/auth/register', 'POST', { username: this.regUser, password: this.regPwd });
                    await handleAuthSuccess(r);
                } catch (e) { this.error = friendlyRegisterError(e); }
                finally { this.loading = false; }
            },
        },
        template: `
<div class="auth-overlay">
    <div class="auth-card" role="dialog" aria-modal="true">
        <h1 class="authTitle">AI 模拟面试</h1>
        <div class="auth-tabs" role="tablist">
            <button type="button" :class="['auth-tab', tab==='login'?'active':'']" @click="switchTab('login')">登录</button>
            <button type="button" :class="['auth-tab', tab==='register'?'active':'']" @click="switchTab('register')">注册</button>
        </div>
        <form v-if="tab==='login'" class="auth-form" @submit.prevent="doLogin">
            <input v-model="loginUser" type="text" placeholder="用户名（≥4 字符）" @input="clearError">
            <div class="pwdWrap">
                <input v-model="loginPwd" :type="showLoginPwd?'text':'password'" placeholder="密码（≥6 字符）" @input="clearError">
                <button type="button" class="pwdToggle" @click="showLoginPwd=!showLoginPwd">{{showLoginPwd?'隐藏':'显示'}}</button>
            </div>
            <button type="submit" class="primary big" :disabled="loading">{{loading?'登录中…':'登录'}}</button>
        </form>
        <form v-else class="auth-form" @submit.prevent="doRegister">
            <input v-model="regUser" type="text" placeholder="用户名（4-32 字符）" @input="clearError">
            <div class="pwdWrap">
                <input v-model="regPwd" :type="showRegPwd?'text':'password'" placeholder="密码（6-128 字符）" @input="clearError">
                <button type="button" class="pwdToggle" @click="showRegPwd=!showRegPwd">{{showRegPwd?'隐藏':'显示'}}</button>
            </div>
            <div class="pwdWrap">
                <input v-model="regPwd2" :type="showRegPwd2?'text':'password'" placeholder="再输一遍密码" @input="clearError">
                <button type="button" class="pwdToggle" @click="showRegPwd2=!showRegPwd2">{{showRegPwd2?'隐藏':'显示'}}</button>
            </div>
            <button type="submit" class="primary big" :disabled="loading">{{loading?'注册中…':'注册'}}</button>
        </form>
        <p v-if="error" class="auth-error">{{error}}</p>
        <p class="auth-hint">令牌仅保存在浏览器内存中，刷新页面需重新登录</p>
    </div>
</div>`
    };

    function friendlyRegisterError(err) {
        const m = err.message || '';
        if (m.includes('用户名')) return '用户名已被占用';
        if (m.includes('密码')) return '密码长度需 6-128 字符';
        return m;
    }

    async function handleAuthSuccess(r) {
        store.token = r.token;
        store.username = r.username;
        await loadBusinessData();
    }

    async function loadBusinessData() {
        await Promise.all([refreshJd(), loadHistory(), loadWeak(), loadQuota(), loadUserSettings()]);
    }

    async function loadUserSettings() {
        try {
            const s = await api('/api/settings');
            store.keyMode = s.keyMode || 'PLATFORM';
            store.byokStatus = s.byokMasked || '';
            store.chatProvider = s.chatProvider || 'DEEPSEEK';
        } catch (e) { /* 忽略 */ }
    }

    // ==================== 组件：准备区 ====================
    const PreparePanel = {
        data() {
            return {
                jdText: '', jdTag: '',
                resumeText: '',
                docTitle: '', docText: '',
                noteText: '',
                realInterviewText: '', realInterviewList: [],
                roles: { tech: true, project: false, behavior: false },
                maxQuestions: 8,
                tip: '', tipBad: false,
            };
        },
        computed: {
            selectedJd() {
                return store.jdList.find(j => j.jdId === store.jdId);
            },
            quotaRemaining() { return store.quota ? formatPoints(store.quota.remainingTokens) : '—'; },
            quotaTotal() { return store.quota ? formatPoints(store.quota.totalTokens) : '—'; },
            quotaUsed() { return store.quota ? formatPoints(store.quota.usedTokens) : '—'; },
        },
        methods: {
            setTip(msg, bad) { this.tip = msg; this.tipBad = !!bad; },
            async uploadJd() {
                if (!this.jdText.trim()) { this.setTip('JD 内容不能为空', true); return; }
                try {
                    const r = await api('/api/jd', 'POST', { text: this.jdText, sameTypeTag: this.jdTag });
                    store.jdId = r.jdId;
                    await refreshJd();
                    this.jdText = ''; this.jdTag = '';
                    this.setTip('JD 已上传，解析出 ' + (r.keywords ? r.keywords.length : 0) + ' 个考点');
                } catch (e) { this.setTip(e.message, true); }
            },
            async uploadResume() {
                if (!this.resumeText.trim()) { this.setTip('简历内容不能为空', true); return; }
                try {
                    const r = await api('/api/resume', 'POST', { content: this.resumeText });
                    store.resumeId = r.resumeId;
                    this.resumeText = '';
                    this.setTip('简历版本已保存');
                } catch (e) { this.setTip(e.message, true); }
            },
            async uploadDoc() {
                if (!this.docTitle.trim() || !this.docText.trim()) { this.setTip('标题和内容都不能为空', true); return; }
                try {
                    await api('/api/project-doc', 'POST', { title: this.docTitle, content: this.docText });
                    this.docTitle = ''; this.docText = '';
                    this.setTip('项目文档已上传');
                } catch (e) { this.setTip(e.message, true); }
            },
            async saveNote() {
                if (!this.noteText.trim()) return;
                try {
                    await api('/api/note', 'POST', { content: this.noteText });
                    this.noteText = '';
                    this.setTip('笔记已存入 RAG');
                } catch (e) { this.setTip(e.message, true); }
            },
            async saveRealInterview() {
                if (!this.realInterviewText.trim()) { this.setTip('记录内容不能为空', true); return; }
                try {
                    await api('/api/real-interview', 'POST', { content: this.realInterviewText });
                    this.realInterviewText = '';
                    await this.loadRealInterview();
                    this.setTip('真实面试记录已保存');
                } catch (e) { this.setTip(e.message, true); }
            },
            async loadRealInterview() {
                try { this.realInterviewList = await api('/api/real-interview'); }
                catch (e) { /* 忽略 */ }
            },
            async switchKeyMode(mode) {
                store.keyMode = mode;
                try { await api('/api/settings/key-mode', 'POST', { keyMode: mode }); }
                catch (e) { this.setTip(e.message, true); }
            },
            async saveByok() {
                if (!store.byokKey.trim()) { this.setTip('请输入 DeepSeek API Key', true); return; }
                try {
                    const r = await api('/api/settings/byok', 'POST', { apiKey: store.byokKey });
                    store.byokStatus = r.masked || '';
                    store.byokKey = '';
                    this.setTip('Key 已保存并切换为 BYOK');
                } catch (e) { this.setTip(e.message, true); }
            },
            async deleteByok() {
                try {
                    await api('/api/settings/byok', 'DELETE');
                    store.byokStatus = '';
                    store.keyMode = 'PLATFORM';
                    this.setTip('已删除自带 Key，切回平台额度');
                } catch (e) { this.setTip(e.message, true); }
            },
            async startInterview() {
                if (!store.jdId) { this.setTip('请先选择或上传 JD', true); return; }
                const roleList = [];
                if (this.roles.tech) roleList.push('技术深挖官');
                if (this.roles.project) roleList.push('项目质疑官');
                if (this.roles.behavior) roleList.push('行为面试官');
                if (!roleList.length) { this.setTip('至少选择一个面试官角色', true); return; }
                try {
                    store.chatMessages = [];
                    store.report = null;
                    rendered = new Set();
                    const r = await api('/api/interview/start', 'POST', {
                        jdId: store.jdId, resumeId: store.resumeId || null,
                        roles: roleList, maxQuestions: this.maxQuestions
                    });
                    store.sessionId = r.sessionId;
                    store.interviewActive = true;
                    store.stateText = '面试官出题中…';
                    openStream(r.sessionId);
                } catch (e) { this.setTip(e.message, true); }
            },
        },
        mounted() { this.loadRealInterview(); },
        template: `
<aside class="panel">
    <h2>1. 准备</h2>

    <div class="block keyblock">
        <div class="row"><span class="lb">模型调用方式</span><span class="keybadge">{{store.keyMode==='PLATFORM'?'平台额度':'自带 Key'}}</span></div>
        <div class="modeswitch" role="radiogroup">
            <label class="modeopt"><input type="radio" name="keyMode" value="PLATFORM" :checked="store.keyMode==='PLATFORM'" @change="switchKeyMode('PLATFORM')"> <span>平台额度</span></label>
            <label class="modeopt"><input type="radio" name="keyMode" value="BYOK" :checked="store.keyMode==='BYOK'" @change="switchKeyMode('BYOK')"> <span>自带 Key</span></label>
        </div>
        <div class="quotaLine">积分：<b>{{quotaRemaining}}</b> / <span>{{quotaTotal}}</span> <small>（已用 {{quotaUsed}}）</small></div>
        <div v-if="store.keyMode==='BYOK'" class="keybox">
            <div class="keyStatus" v-if="store.byokStatus">已有 Key：{{store.byokStatus}}</div>
            <div class="pwdWrap">
                <input v-model="store.byokKey" type="password" placeholder="DeepSeek API Key（sk-…）">
            </div>
            <div class="row">
                <button class="primary" @click="saveByok">保存并切换</button>
                <button class="warn" @click="deleteByok">删除并切回平台</button>
            </div>
            <small class="keyhint">Key 经 AES-GCM 加密存服务端，页面只显示末 4 位。</small>
        </div>
    </div>

    <div class="block">
        <label class="lb">岗位 JD（粘贴后点上传）</label>
        <textarea v-model="jdText" rows="6" placeholder="粘贴招聘 JD 原文…"></textarea>
        <div class="row">
            <input v-model="jdTag" type="text" placeholder="同类标签（可选）">
            <button class="primary" @click="uploadJd">上传 JD</button>
        </div>
        <div class="row">
            <select v-model.number="store.jdId">
                <option :value="null">— 选择已有 JD —</option>
                <option v-for="j in store.jdList" :key="j.jdId" :value="j.jdId">#{{j.jdId}} {{j.jobType}}{{j.sameTypeTag?' · '+j.sameTypeTag:''}}</option>
            </select>
            <button class="ghost" @click="refreshJd">刷新</button>
        </div>
        <div v-if="selectedJd && selectedJd.keywords && selectedJd.keywords.length" class="jdKeywords">
            <div class="kwTitle">考点预览：</div>
            <span v-for="k in selectedJd.keywords" :key="k" class="kwChip">{{k}}</span>
        </div>
    </div>

    <div class="block">
        <label class="lb">简历（md）</label>
        <textarea v-model="resumeText" rows="4" placeholder="# 张三&#10;Java 后端…"></textarea>
        <button class="ghost" @click="uploadResume">上传新简历版本</button>
        <div class="row">
            <select v-model.number="store.resumeId">
                <option :value="null">— 选择历史简历 —</option>
            </select>
            <button class="ghost">刷新</button>
        </div>
    </div>

    <div class="block">
        <label class="lb">项目文档（标题 + 详解）</label>
        <input v-model="docTitle" type="text" placeholder="项目名">
        <textarea v-model="docText" rows="4" placeholder="项目详解…"></textarea>
        <button class="ghost" @click="uploadDoc">上传项目文档</button>
    </div>

    <div class="block">
        <label class="lb">笔记（可选）</label>
        <textarea v-model="noteText" rows="3" placeholder="随手写的学习笔记"></textarea>
        <button class="ghost" @click="saveNote">存笔记</button>
    </div>

    <div class="block">
        <label class="lb">真实面试记录（可选）</label>
        <textarea v-model="realInterviewText" rows="4" placeholder="记录真实面试经历…"></textarea>
        <div class="row">
            <button class="ghost" @click="saveRealInterview">保存记录</button>
            <button class="ghost" @click="loadRealInterview">刷新</button>
        </div>
        <div class="recList">
            <div v-for="r in realInterviewList" :key="r.recordId" class="recItem">
                <small>{{fmtTime(r.createdAt)}}</small>
                <p>{{r.content}}</p>
            </div>
        </div>
    </div>

    <div class="block">
        <span class="lb">面试官角色</span>
        <label class="chk"><input type="checkbox" v-model="roles.tech"> 技术深挖官</label>
        <label class="chk"><input type="checkbox" v-model="roles.project"> 项目质疑官</label>
        <label class="chk"><input type="checkbox" v-model="roles.behavior"> 行为面试官</label>
        <div class="row">
            <label class="chk">主问题数</label>
            <select v-model.number="maxQuestions">
                <option :value="3">快速 3 题</option>
                <option :value="5">标准 5 题</option>
                <option :value="8">完整 8 题</option>
            </select>
        </div>
        <button class="primary big" @click="startInterview">开始模拟面试</button>
    </div>

    <p :class="['tip', tipBad?'bad':'']">{{tip}}</p>
</aside>`
    };

    // ==================== 组件：面试区 ====================
    const InterviewPanel = {
        data() {
            return { answer: '', providerMenuOpen: false };
        },
        computed: {
            canAnswer() { return store.awaiting && store.interviewActive; },
        },
        methods: {
            async submitAnswer() {
                if (!this.canAnswer || !this.answer.trim()) return;
                store.awaiting = false;
                store.chatMessages.push({ cls: 'me', title: '我', body: this.answer });
                const text = this.answer;
                this.answer = '';
                try {
                    await api('/api/interview/' + store.sessionId + '/answer', 'POST', { answer: text });
                } catch (e) {
                    store.chatMessages.push({ cls: 'err', title: '提交失败', body: e.message });
                    store.awaiting = true;
                }
            },
            async endInterview() {
                if (!confirm('确定结束并生成报告？')) return;
                store.awaiting = false;
                store.interviewActive = false;
                try {
                    const r = await api('/api/interview/' + store.sessionId + '/end', 'POST');
                    renderReport(r);
                    loadWeak(); loadHistory(); loadQuota();
                } catch (e) {
                    store.chatMessages.push({ cls: 'err', title: '结束失败', body: e.message });
                }
            },
            async switchProvider(p) {
                store.chatProvider = p;
                this.providerMenuOpen = false;
                try { await api('/api/settings/chat-provider', 'POST', { provider: p }); }
                catch (e) { /* 忽略 */ }
            },
            onKey(e) {
                if (e.ctrlKey && e.key === 'Enter') { e.preventDefault(); this.submitAnswer(); }
            },
        },
        template: `
<section class="panel main">
    <h2>2. 面试</h2>
    <div class="chat" ref="chatBox">
        <p v-if="!store.chatMessages.length" class="placeholder">左侧上传 JD 后点「开始模拟面试」，题目会通过 SSE 实时推过来。</p>
        <div v-for="(m, i) in store.chatMessages" :key="i" :class="['msg', m.cls]">
            <div class="msgTitle" v-html="m.title"></div>
            <div class="msgBody" v-html="m.html || esc(m.body)"></div>
        </div>
    </div>
    <div class="answer">
        <textarea v-model="answer" rows="3" placeholder="你的回答（Ctrl + Enter 提交）" :disabled="!canAnswer" @keydown="onKey"></textarea>
        <div class="row">
            <button class="primary" :disabled="!canAnswer" @click="submitAnswer">提交回答</button>
            <button class="warn" :disabled="!store.interviewActive" @click="endInterview">结束并出报告</button>
            <span class="state">{{store.stateText}}</span>
            <span style="flex:1 1 auto"></span>
            <div style="position:relative;display:inline-block">
                <button type="button" @click="providerMenuOpen=!providerMenuOpen" style="display:inline-flex;align-items:center;gap:6px;padding:5px 10px;font-size:13px;border-radius:6px">
                    <span>{{store.chatProvider==='DEEPSEEK'?'DeepSeek':'通义千问'}}</span>
                    <span style="font-size:10px;color:var(--accent2)">▾</span>
                </button>
                <div v-if="providerMenuOpen" style="position:absolute;right:0;bottom:calc(100% + 4px);z-index:30;min-width:168px;background:var(--panel);border:1px solid var(--line);border-radius:8px;padding:4px">
                    <button type="button" @click="switchProvider('DEEPSEEK')" style="display:flex;justify-content:space-between;width:100%;padding:7px 10px;border:none;border-radius:6px;background:transparent;color:var(--text);text-align:left;cursor:pointer">
                        <span>DeepSeek</span><span v-if="store.chatProvider==='DEEPSEEK'" style="color:var(--accent)">✓</span>
                    </button>
                    <button type="button" @click="switchProvider('QWEN')" style="display:flex;justify-content:space-between;width:100%;padding:7px 10px;border:none;border-radius:6px;background:transparent;color:var(--text);text-align:left;cursor:pointer">
                        <span>通义千问</span><span v-if="store.chatProvider==='QWEN'" style="color:var(--accent)">✓</span>
                    </button>
                </div>
            </div>
        </div>
    </div>
</section>`
    };

    // ==================== 组件：报告区 ====================
    const ReportPanel = {
        data() {
            return { showTurns: false, turns: [], showTrend: false, trendData: [], _histTimer: null };
        },
        computed: {
            r() { return store.report; },
            hasFilter() {
                const f = store.histFilter;
                return !!(f.keyword || f.status || f.minScore || f.maxScore);
            },
        },
        methods: {
            loadHistory() { loadHistory(); },
            loadHistoryDebounced() { clearTimeout(this._histTimer); this._histTimer = setTimeout(loadHistory, 300); },
            viewHistoryReport(sid) { viewHistoryReport(sid); },
            row(label, value) {
                return value ? '<div class="kv"><span class="k">' + label + '</span><span class="v">' + esc(value) + '</span></div>' : '';
            },
            listHtml(title, arr) {
                if (!arr || !arr.length) return '';
                return '<h3>' + title + '</h3><ul class="lst">' + arr.map(x => '<li>' + esc(x) + '</li>').join('') + '</ul>';
            },
            behaviorCard(br) {
                if (!br || typeof br !== 'object') return '';
                const dims = br.dimensions && typeof br.dimensions === 'object' ? br.dimensions : null;
                const suggestions = Array.isArray(br.suggestions) ? br.suggestions : [];
                if (!dims && !br.summary && !suggestions.length) return '';
                const dimHtml = dims ? '<div class="behDims">' + Object.entries(dims).map(([k, v]) =>
                    '<span class="behDim">' + esc(k) + ' <b>' + esc(v) + '</b>/2</span>').join('') + '</div>' : '';
                const sugHtml = suggestions.length ? '<ul class="lst">' + suggestions.map(x => '<li>' + esc(x) + '</li>').join('') + '</ul>' : '';
                return '<h3>行为面试评估</h3><div class="behCard">' + dimHtml +
                    (br.summary ? '<p class="notes">' + esc(br.summary) + '</p>' : '') + sugHtml + '</div>';
            },
            reportHtml() {
                if (!this.r) return '<p class="placeholder">结束后这里出报告：匹配度、逐轮讲解、答崩点、表述问题、提醒。</p>';
                const turns = Array.isArray(this.r.perTurnReview) ? this.r.perTurnReview : [];
                const collapsed = Array.isArray(this.r.collapsedPoints) ? this.r.collapsedPoints : [];
                const issues = Array.isArray(this.r.expressionIssues) ? this.r.expressionIssues : [];
                const reminders = Array.isArray(this.r.reminders) ? this.r.reminders : [];
                const turnHtml = turns.map(t =>
                    '<details class="turn"><summary>第 ' + esc(t.turnSeq) + ' 轮 · ' + esc(t.score) + '/8 · ' +
                    (t.track === 'behavior' ? '<span class="trackBadge">行为</span> ' : '') +
                    esc(String(t.question || '').slice(0, 40)) + '</summary>' +
                    this.row('讲解', t.explanation) + this.row('纠正', t.correction) +
                    this.row('知识补充', t.knowledgeSupplement) + this.row('示范回答', t.exemplarAnswer) +
                    '</details>').join('');
                return '<div class="match">匹配度 <b>' + esc(this.r.matchScore == null ? '—' : this.r.matchScore) + '</b> / 100</div>' +
                    this.behaviorCard(this.r.behaviorReview) +
                    (turnHtml ? '<h3>逐轮讲解</h3>' + turnHtml : '') +
                    this.listHtml('答崩点', collapsed) +
                    (issues.length ? '<h3>表述问题</h3>' + issues.map(i =>
                        '<div class="issue"><b>' + esc(i.issue) + '</b><div>' + esc(i.guide) + '</div></div>').join('') : '') +
                    (this.r.overallNotes ? '<h3>整体注意事项</h3><p class="notes">' + esc(this.r.overallNotes) + '</p>' : '') +
                    this.listHtml('必要提醒', reminders);
            },
            async exportMd() {
                if (!store.reportSessionId) return;
                try {
                    const res = await fetch('/api/interview/' + store.reportSessionId + '/report.md', {
                        headers: { 'Authorization': 'Bearer ' + store.token }
                    });
                    const text = await res.text();
                    const blob = new Blob([text], { type: 'text/markdown;charset=utf-8' });
                    const url = URL.createObjectURL(blob);
                    const a = document.createElement('a');
                    a.href = url; a.download = '面试报告_' + store.reportSessionId + '.md';
                    a.click();
                    URL.revokeObjectURL(url);
                } catch (e) { alert('导出失败：' + e.message); }
            },
            async toggleTurns() {
                if (!store.reportSessionId) return;
                if (this.showTurns) { this.showTurns = false; return; }
                try {
                    this.turns = await api('/api/interview/' + store.reportSessionId + '/turns');
                    this.showTurns = true;
                } catch (e) { alert(e.message); }
            },
            async toggleTrend() {
                if (this.showTrend) { this.showTrend = false; return; }
                try {
                    this.trendData = await api('/api/interview/trend');
                    this.showTrend = true;
                } catch (e) { alert(e.message); }
            },
            trendSvg() {
                const data = this.trendData;
                if (!data || !data.length) return '';
                const W = 400, H = 160, pad = { l: 32, r: 12, t: 12, b: 22 };
                const iw = W - pad.l - pad.r, ih = H - pad.t - pad.b;
                const n = data.length;
                const xAt = (i) => pad.l + (n <= 1 ? iw / 2 : (iw * i) / (n - 1));
                const yAt = (v) => pad.t + ih - (ih * v) / 100;
                let grid = '';
                for (let g = 0; g <= 4; g++) {
                    const y = pad.t + (ih * g) / 4;
                    grid += '<line x1="' + pad.l + '" y1="' + y + '" x2="' + (W - pad.r) + '" y2="' + y + '" stroke="#2a3650" stroke-dasharray="2,3"/>' +
                        '<text x="' + (pad.l - 4) + '" y="' + (y + 3) + '" fill="#6b7a99" font-size="9" text-anchor="end">' + (100 - g * 25) + '</text>';
                }
                const pts = data.map((d, i) => xAt(i) + ',' + yAt(d.matchScore)).join(' ');
                let dots = '';
                data.forEach((d, i) => {
                    dots += '<circle cx="' + xAt(i) + '" cy="' + yAt(d.matchScore) + '" r="3" fill="#5b9dff"><title>#' + d.sessionId + ' · ' + fmtTime(d.createdAt) + ' · ' + d.matchScore + '分</title></circle>';
                });
                const xLabel = '<text x="' + pad.l + '" y="' + (H - 6) + '" fill="#6b7a99" font-size="9">' + esc(fmtTime(data[0].createdAt)) + '</text>' +
                    '<text x="' + (W - pad.r) + '" y="' + (H - 6) + '" fill="#6b7a99" font-size="9" text-anchor="end">' + esc(fmtTime(data[n - 1].createdAt)) + '</text>';
                return '<svg viewBox="0 0 ' + W + ' ' + H + '" preserveAspectRatio="none">' + grid +
                    '<polyline points="' + pts + '" fill="none" stroke="#5b9dff" stroke-width="2"/>' + dots + xLabel + '</svg>';
            },
        },
        template: `
<div>
    <h2>3. 历史面试</h2>
    <div class="row">
        <button class="ghost" @click="loadHistory">刷新历史</button>
        <button class="ghost" @click="toggleTrend">分数趋势</button>
        <span class="state">最近 20 场，点有报告的回看</span>
    </div>
    <div class="histFilters">
        <input v-model="store.histFilter.keyword" type="text" placeholder="搜索 JD 关键词…" @input="loadHistoryDebounced">
        <select v-model="store.histFilter.status" @change="loadHistory">
            <option value="">全部状态</option>
            <option value="DONE">已结束</option>
            <option value="INTERVIEWING">进行中</option>
        </select>
        <input v-model="store.histFilter.minScore" type="number" min="0" max="100" placeholder="最低分" @input="loadHistoryDebounced">
        <input v-model="store.histFilter.maxScore" type="number" min="0" max="100" placeholder="最高分" @input="loadHistoryDebounced">
    </div>
    <div class="history">
        <p v-if="!store.historyList.length" class="placeholder">{{hasFilter?'没有匹配的历史面试。':'还没有历史面试。'}}</p>
        <button v-for="s in store.historyList" :key="s.sessionId" :class="['histItem', s.hasReport?'':'noReport']" :disabled="!s.hasReport" @click="s.hasReport && viewHistoryReport(s.sessionId)">
            #{{s.sessionId}} {{s.jobType||'面试'}}{{s.sameTypeTag?' · '+s.sameTypeTag:''}}
            <span>{{fmtTime(s.createdAt)}} · {{s.matchScore==null?'未评分':s.matchScore+' 分'}}{{s.hasReport?'':' · '+s.status}}</span>
        </button>
    </div>
    <div v-if="showTrend" class="trendChart" v-html="trendSvg()"></div>

    <h2>4. Critic 报告</h2>
    <div class="row reportActions">
        <button class="ghost" :disabled="!store.report" @click="exportMd">导出 MD</button>
        <button class="ghost" :disabled="!store.report" @click="toggleTurns">逐轮回看</button>
    </div>
    <div class="report" v-html="reportHtml()"></div>
    <div v-if="showTurns" class="turnsReplay">
        <div v-for="t in turns" :key="t.turnId" class="turnItem">
            <div class="turnMeta">第 {{t.seq}} 轮{{t.layer?' · L'+t.layer:''}}</div>
            <div class="turnQ">{{t.question}}</div>
            <div class="turnA">{{t.answer}}</div>
        </div>
    </div>
</div>`
    };

    // ==================== 组件：错题本 ====================
    const WeakPanel = {
        data() {
            return { reviewPanel: false, reviewQueue: [], reviewIdx: 0, reviewQuestion: '', reviewAnswer: '', reviewResult: null, _detailCache: {} };
        },
        methods: {
            loadWeak() { loadWeak(); },
            stageText(w) { return w.resolved ? '已掌握' : 'stage ' + (w.reviewStage || 0); },
            async loadDetail(w) {
                if (this._detailCache[w.weakPointId]) return;
                try {
                    const d = await api('/api/weak-point/' + w.weakPointId);
                    this._detailCache[w.weakPointId] = d;
                } catch (e) { /* 忽略 */ }
            },
            async startReview() {
                if (this.reviewPanel) { this.reviewPanel = false; return; }
                try {
                    this.reviewQueue = await api('/api/weak-point/due');
                    if (!this.reviewQueue.length) { this.reviewPanel = true; return; }
                    this.reviewIdx = 0;
                    this.reviewPanel = true;
                    this.loadReviewItem();
                } catch (e) { alert(e.message); }
            },
            async loadReviewItem() {
                this.reviewResult = null;
                this.reviewAnswer = '';
                try {
                    const w = this.reviewQueue[this.reviewIdx];
                    const r = await api('/api/weak-point/' + w.weakPointId + '/review/question', 'POST');
                    this.reviewQuestion = r.question;
                } catch (e) { alert('出题失败：' + e.message); }
            },
            async submitReview() {
                if (!this.reviewAnswer.trim()) return;
                try {
                    const w = this.reviewQueue[this.reviewIdx];
                    this.reviewResult = await api('/api/weak-point/' + w.weakPointId + '/review/answer', 'POST',
                        { question: this.reviewQuestion, answer: this.reviewAnswer });
                    loadWeak();
                } catch (e) { alert(e.message); }
            },
            nextReview() {
                this.reviewIdx++;
                if (this.reviewIdx >= this.reviewQueue.length) { this.reviewPanel = false; return; }
                this.loadReviewItem();
            },
        },
        template: `
<div>
    <h2>5. 错题本（间隔重复）</h2>
    <div class="row">
        <button class="ghost" @click="loadWeak">刷新</button>
        <button class="ghost" @click="startReview">复习模式</button>
        <label class="chk"><input type="checkbox" v-model="store.dueOnly" @change="loadWeak"> 只看今日到期</label>
    </div>
    <div class="weak">
        <p v-if="!store.weakList.length" class="placeholder">答崩的知识点会自动进这里，按 [1,3,7,15] 天阶梯排复习。</p>
        <details v-for="w in store.weakList" :key="w.weakPointId" @toggle.open="loadDetail(w)">
            <summary>
                <span class="wpKp">{{w.knowledgePoint}}</span>
                <span class="wpMeta">{{stageText(w)}} · 错 {{w.wrongCount||0}} 次{{w.nextReviewAt?' · 下次 '+fmtTime(w.nextReviewAt):''}}</span>
            </summary>
            <div v-if="_detailCache[w.weakPointId]" class="wpDetail">
                <div v-if="_detailCache[w.weakPointId].latest">
                    <div class="wpScene"><b>最近答崩现场：</b>{{_detailCache[w.weakPointId].latest.question}}</div>
                    <div class="wpSceneAnswer">你的回答：{{_detailCache[w.weakPointId].latest.answer}}</div>
                    <div v-if="_detailCache[w.weakPointId].latest.explanation" class="wpExp">讲解：{{_detailCache[w.weakPointId].latest.explanation}}</div>
                </div>
            </div>
        </details>
    </div>
    <div v-if="reviewPanel" class="reviewPanel">
        <p v-if="!reviewQueue.length" class="placeholder">今天没有到期的复习项。</p>
        <template v-else>
            <div v-if="!reviewResult">
                <div class="rvKp">知识点：{{reviewQueue[reviewIdx].knowledgePoint}}（{{reviewIdx+1}}/{{reviewQueue.length}}）</div>
                <div class="rvQ">{{reviewQuestion}}</div>
                <textarea v-model="reviewAnswer" placeholder="在此作答…"></textarea>
                <div class="rvActions">
                    <button class="primary" @click="submitReview">提交判分</button>
                    <button class="ghost" @click="nextReview">跳过</button>
                </div>
            </div>
            <div v-else class="rvResult">
                <div class="rvScore">{{reviewResult.score}} / 8</div>
                <div>{{reviewResult.explanation}}</div>
                <div style="margin-top:6px;color:var(--muted);font-size:12px">{{reviewResult.resolved?'已掌握，出队 ✓':'stage '+reviewResult.newStage+' · 下次复习 '+fmtTime(reviewResult.nextReviewAt)}}</div>
                <div class="rvActions"><button class="primary" @click="nextReview">{{reviewIdx+1<reviewQueue.length?'下一题':'完成复习'}}</button></div>
            </div>
        </template>
    </div>
</div>`
    };

    // ==================== 组件：心理陪伴（F10） ====================
    const CompanionPanel = {
        data() {
            return { messages: [], input: '', loading: false };
        },
        methods: {
            async send() {
                const text = this.input.trim();
                if (!text || this.loading) return;
                this.messages.push({ role: 'user', text });
                this.input = '';
                this.loading = true;
                try {
                    const r = await api('/api/companion/chat', 'POST', { message: text });
                    this.messages.push({ role: 'companion', text: r.reply, safe: r.safe });
                } catch (e) {
                    this.messages.push({ role: 'companion', text: '抱歉，我现在有点累，等会儿再陪你聊好吗？' });
                } finally { this.loading = false; }
            },
            reset() {
                this.messages = [];
                api('/api/companion/reset', 'POST').catch(() => {});
            },
        },
        template: `
<div class="companion">
    <h2>6. 心理陪伴</h2>
    <p class="companionIntro">我是小暖，一个愿意听你说话的陪伴者。有什么想说的都可以告诉我。</p>
    <div class="companionChat">
        <div v-for="(m, i) in messages" :key="i" :class="['cMsg', m.role]">
            <span class="cRole">{{m.role==='user'?'我':'小暖'}}</span>
            <p :class="{'cSafe': m.safe}">{{m.text}}</p>
        </div>
        <p v-if="loading" class="cLoading">小暖正在想…</p>
    </div>
    <div class="companionInput">
        <textarea v-model="input" rows="2" placeholder="说点什么…（Ctrl+Enter 发送）" @keydown.ctrl.enter="send"></textarea>
        <div class="row">
            <button class="primary" :disabled="loading" @click="send">发送</button>
            <button class="ghost" @click="reset">清空对话</button>
        </div>
    </div>
</div>`
    };

    // ==================== 主应用 ====================
    const App = {
        components: { AuthPanel, PreparePanel, InterviewPanel, ReportPanel, WeakPanel, CompanionPanel },
        computed: {
            loggedIn() { return !!store.token; },
        },
        methods: {
            logout() {
                store.token = null;
                store.username = null;
                closeStream();
            },
        },
        mounted() {
            // 点击额度弹窗确定按钮
            const btn = document.getElementById('btnQuotaOk');
            if (btn) btn.addEventListener('click', hideQuotaModal);
            // 默认展示登录
            if (!store.token) showAuthModal('login');
        },
        template: `
<div>
    <auth-panel v-if="!loggedIn"></auth-panel>
    <div v-else id="appShell">
        <header class="topbar">
            <div class="brand">AI 模拟面试<span class="sub">贴 JD → 角色追问 → Critic 报告 + 错题本</span></div>
            <div class="topbar-right">
                <div class="conn">
                    <span :class="['dot', store.connOk?'online':'offline']"></span>
                    <span>{{store.connText}}</span>
                </div>
                <div class="user-area">
                    <span class="user-avatar">{{store.username ? store.username[0].toUpperCase() : '?'}}</span>
                    <span class="user-name">{{store.username}}</span>
                    <button class="ghost" @click="logout">登出</button>
                </div>
            </div>
        </header>
        <nav class="viewTabs">
            <button :class="['vt', store.view==='prepare'?'active':'']" @click="store.view='prepare'">准备</button>
            <button :class="['vt', store.view==='interview'?'active':'']" @click="store.view='interview'">面试</button>
            <button :class="['vt', store.view==='history'?'active':'']" @click="store.view='history'">报告</button>
            <button :class="['vt', store.view==='weak'?'active':'']" @click="store.view='weak'">错题本</button>
            <button :class="['vt', store.view==='companion'?'active':'']" @click="store.view='companion'">心理陪伴</button>
        </nav>
        <main class="layout">
            <prepare-panel v-show="store.view==='prepare'"></prepare-panel>
            <interview-panel v-show="store.view==='interview'"></interview-panel>
            <aside v-show="store.view==='history'" class="panel side">
                <report-panel></report-panel>
            </aside>
            <aside v-show="store.view==='weak'" class="panel side">
                <weak-panel></weak-panel>
            </aside>
            <aside v-show="store.view==='companion'" class="panel side">
                <companion-panel></companion-panel>
            </aside>
        </main>
    </div>
</div>`
    };

    // 暴露给组件内部调用的全局函数
    const app = createApp(App);
    app.config.globalProperties.esc = esc;
    app.config.globalProperties.fmtTime = fmtTime;
    app.config.globalProperties.store = store;
    app.mount('#app');
})();
