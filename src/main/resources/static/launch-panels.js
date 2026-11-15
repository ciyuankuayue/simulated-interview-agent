/* Account and operations views. Factories share the app's in-memory account lifecycle. */
'use strict';
window.createLaunchPanels = function ({ store, api, loadSettings, loadQuota, notify, fmtTime, formatPoints, track, uuid }) {
    const rows = data => data?.items || [];
    const money = value => value == null ? '—' : Number(value).toLocaleString('zh-CN', { style: 'currency', currency: 'CNY', maximumFractionDigits: 4 });
    const sceneLabels = {JD_PARSE:'解析岗位',FOLLOWUP:'面试提问',SCORE:'回答评价',CRITIC:'面试报告',CHAT:'陪考对话',EMBED_INGEST:'资料处理',EMBED_QUERY:'资料检索'};
    const statusLabels = { PENDING: '等待审核', NEEDS_INFO: '请补充说明', REWARDED: '已赠送积分', CLOSED: '已关闭' };
    const grantLabels = { GIFT: '赠送', PAID: '付费补额', COMPENSATION: '补偿', ADJUSTMENT: '调整', FEEDBACK: '反馈奖励', LEGACY: '历史方式补额' };
    const eventLabels = { REGISTERED: '注册成功', LOGIN: '登录', IMPORT_SUCCEEDED: '导入资料', INTERVIEW_STARTED: '开始面试', ANSWER_SAVED: '提交回答', REPORT_GENERATED: '生成报告', REPORT_VIEW: '查看报告', PAGE_VIEW: '访问页面', PRACTICE_CLICK: '点击练习', API_CONFIG_OPEN: '打开模型配置', FEEDBACK_INVITE_VIEW: '看到反馈邀请', FEEDBACK_OPEN: '打开反馈', MODEL_FAILED: '模型调用失败', QUOTA_BLOCKED: '额度不足', SETTINGS_SAVED: '保存设置', BUDDY_SUCCEEDED: '陪考对话完成' };
    const pages = { prepare: '开始练习', interview: '面试工作区', history: '报告与成长', weak: '错题本', materials: '我的资料', companion: '小暖', coach: '小师', settings: '我的账号', feedback: '反馈' };
    const progressOptions = [
        ['LOOKED_ONLY', '看了页面，还没尝试'], ['START_FAILED', '尝试开始，但没能顺利使用'], ['STOPPED', '使用了一部分，中途停止'],
        ['INTERVIEW_DONE', '完成了面试，还没看报告'], ['REPORT_VIEWED', '已经查看面试报告'], ['BUDDY', '主要使用了小师或小暖'], ['OTHER', '其他']
    ];
    const blockerOptions = [
        ['NONE', '没有什么障碍，愿意继续使用'], ['NO_NEED', '暂时没有练习需求'], ['CONFUSING', '不太清楚怎么操作'],
        ['QUESTIONS', '题目不符合我的需求'], ['REPORT', '回答评价或报告帮助不大'], ['SPEED', '等待时间太长'],
        ['ERROR', '出现报错或中断'], ['COST', '积分或费用方面的考虑'], ['UNSURE', '还没体验充分，暂时无法判断'], ['OTHER', '其他（可在建议中补充）']
    ];
    function openFeedback(source = 'account') {
        if (store.quotaOpen) store.quotaOpen = false;
        store.feedbackSource = source; store.feedbackOpen = true;
        track('FEEDBACK_OPEN', 'feedback', source);
    }
    async function refreshFeedback() {
        const data = await api('/api/feedback');
        store.feedback = data.feedback; store.feedbackLoaded = true;
        return data;
    }
    const SettingsPanel = {
        data: () => ({ tab: 'account', draftProvider: 'DEEPSEEK', draftMode: 'PLATFORM', key: '', busy: false, error: '', usage: [], grants: [], grantPage: 0, grantTotal: 0, prefBusy: false }),
        computed: {
            effectiveLabel() { return (store.chatProvider === 'QWEN' ? '通义千问' : 'DeepSeek') + ' · ' + (store.chatProvider === 'DEEPSEEK' && store.keyMode === 'BYOK' ? '我的 API' : '平台提供'); },
            draftLabel() { return (this.draftProvider === 'QWEN' ? '通义千问' : 'DeepSeek') + ' · ' + (this.draftProvider === 'DEEPSEEK' && this.draftMode === 'BYOK' ? '我的 API' : '平台提供'); },
            policy() { return store.billingPolicy; },
            feedbackLabel() { return store.feedback ? statusLabels[store.feedback.status] : '分享体验，审核后获赠 ' + (store.billingPolicy?.feedbackRewardPoints || 200) + ' 积分'; }
        },
        watch: { 'store.view'(view) { if (view === 'settings') this.refresh(); } },
        mounted() { this.refresh(); },
        methods: {
            grantLabel: value => grantLabels[value] || value,
            sceneLabel: value => sceneLabels[value] || value,
            async selectTab(tab) { this.tab = tab; if (tab === 'model') track('API_CONFIG_OPEN', 'settings'); if (tab === 'usage') await this.loadUsage(); },
            syncDraft() { this.draftProvider = store.chatProvider; this.draftMode = store.chatProvider === 'QWEN' ? 'PLATFORM' : store.keyMode; },
            chooseProvider(provider) { this.draftProvider = provider; this.draftMode = 'PLATFORM'; this.error = ''; },
            async saveSettings() {
                if (this.busy) return;
                if (this.draftMode === 'BYOK' && !store.hasKey) { this.error = '请先保存你的 DeepSeek API Key。'; return; }
                this.busy = true; this.error = '';
                try {
                    const settings = await api('/api/user/settings', 'PUT', { chatProvider: this.draftProvider, keyMode: this.draftProvider === 'QWEN' ? 'PLATFORM' : this.draftMode });
                    store.keyMode = settings.keyMode; store.chatProvider = settings.chatProvider; this.syncDraft(); notify('设置已保存，后续调用将使用' + this.effectiveLabel + '。');
                } catch (error) { if (!error.stale) this.error = error.message; }
                finally { this.busy = false; }
            },
            async saveKey() {
                if (this.busy || !this.key.trim()) return;
                this.busy = true; this.error = ''; let keySaved = false;
                try {
                    const result = await api('/api/user/api-key', 'PUT', { apiKey: this.key.trim() });
                    store.hasKey = result.hasKey; store.last4 = result.last4; this.key = ''; keySaved = true;
                    const settings = await api('/api/user/settings', 'PUT', { chatProvider: 'DEEPSEEK', keyMode: 'BYOK' });
                    store.keyMode = settings.keyMode; store.chatProvider = settings.chatProvider; this.syncDraft(); notify('Key 已保存，正在使用自己的 DeepSeek API。');
                } catch (error) { if (!error.stale) this.error = (keySaved ? 'Key 已保存，但尚未启用。可点击“保存设置”重试。' : '') + error.message; }
                finally { this.busy = false; }
            },
            async removeKey() {
                if (this.busy || !confirm('删除自己的 DeepSeek Key？后续 DeepSeek 调用将使用平台提供的模型。')) return;
                this.busy = true; this.error = '';
                try { await api('/api/user/api-key', 'DELETE'); this.key = ''; store.hasKey = false; store.last4 = ''; store.keyMode = 'PLATFORM'; this.draftMode = 'PLATFORM'; notify('DeepSeek Key 已删除。'); }
                catch (error) { if (!error.stale) this.error = error.message; }
                finally { this.busy = false; }
            },
            async refresh() {
                this.error = '';
                try {
                    await Promise.all([loadSettings(), loadQuota()]); this.syncDraft();
                    const [policy, pref] = await Promise.all([api('/api/user/billing-policy'), api('/api/user/analytics-preference')]);
                    store.billingPolicy = policy; store.analyticsEnabled = pref.enabled; store.reportInvitationSeen = !!pref.reportInvitationSeen; store.analyticsLoaded = true;
                    await Promise.all([this.loadGrants(), refreshFeedback()]);
                } catch (error) { if (!error.stale) this.error = error.message; }
            },
            async loadGrants(page = this.grantPage) { const data = await api('/api/user/grants?page=' + page + '&size=10'); this.grants = rows(data); this.grantPage = data.page; this.grantTotal = data.total; },
            async loadUsage() { try { this.usage = await api('/api/user/usage'); } catch (error) { if (!error.stale) this.error = error.message; } },
            async changePage(page) { try { await this.loadGrants(page); } catch (error) { if (!error.stale) this.error = error.message; } },
            async copyAccount() { try { await navigator.clipboard.writeText(store.username); notify('账号已复制。'); } catch (_) { notify('可选中账号名称复制。'); } },
            async toggleAnalytics(event) {
                this.prefBusy = true;
                try { const data = await api('/api/user/analytics-preference', 'PUT', { enabled: event.target.checked }); store.analyticsEnabled = data.enabled; notify(data.enabled ? '已开启使用情况统计。' : '已关闭可选使用情况统计。'); }
                catch (error) { event.target.checked = store.analyticsEnabled; if (!error.stale) this.error = error.message; }
                finally { this.prefBusy = false; }
            },
            openFeedback
        },
        template: `
<section class="account-page"><div class="page-heading"><div><span class="eyebrow">按你的方式练习</span><h1>我的账号</h1><p>管理积分、模型和使用记录。</p></div><button class="secondary" @click="refresh" :disabled="busy"><app-icon name="refresh"/>刷新账号信息</button></div>
<div class="account-tabs segmented" role="group" aria-label="账号栏目"><button v-for="item in [{id:'account',name:'账号与积分'},{id:'model',name:'模型与 API'},{id:'usage',name:'使用记录'}]" :key="item.id" :aria-pressed="tab===item.id" @click="selectTab(item.id)">{{item.name}}</button></div>
<p v-if="error" class="inline-error" role="alert">{{error}}</p>
<div v-show="tab==='account'" class="account-sections"><div class="settings-grid"><section class="surface"><span class="eyebrow">我的练习账号</span><div class="section-heading"><h2 class="account-name">{{store.username}}</h2><button class="secondary compact" @click="copyAccount">复制账号</button></div><p class="quiet">当前使用：{{effectiveLabel}}</p><button class="text-button" @click="selectTab('model')">管理模型与 API<app-icon name="arrow"/></button><hr><h2>分享你的使用体验</h2><p class="quiet">好评、差评同等奖励。描述一次具体体验，审核后可获赠积分，每个账号一次。</p><p v-if="store.feedback" class="feedback-status">{{feedbackLabel}}<span v-if="store.feedback.reviewNote"> · {{store.feedback.reviewNote}}</span></p><button class="secondary" @click="openFeedback('account')">{{store.feedback?'查看我的反馈':feedbackLabel}}</button></section>
<section class="surface quota-surface"><span class="eyebrow">我的积分</span><div class="quota-value">{{formatPoints(store.quota?.remainingTokens)}}<span>积分</span></div><p class="quiet">累计使用 {{formatPoints(store.quota?.usedTokens)}} 积分 / 总积分 {{formatPoints(store.quota?.totalTokens)}}</p><p v-if="policy" class="field-note">新注册赠送 {{policy.signupBonusPoints}} 积分。平台每 {{policy.tokensPerPoint}} token 扣 1 积分；自己的 DeepSeek API 每 {{policy.byokTokensPerPoint}} token 扣 1 服务积分。</p></section></div>
<section class="surface"><div class="section-heading"><h2>积分补充记录</h2><span class="quiet">共 {{grantTotal}} 条</span></div><p v-if="!grants.length" class="empty-copy">暂无补充记录。注册赠送的积分已包含在总积分中。</p><div v-else class="table-scroll"><table><thead><tr><th>时间</th><th>类型</th><th>补充积分</th><th>说明</th></tr></thead><tbody><tr v-for="row in grants" :key="row.id"><td>{{fmtTime(row.createdAt)}}</td><td>{{grantLabel(row.type)}}</td><td>+{{row.points}}</td><td>{{row.reason}}</td></tr></tbody></table></div><div class="row" v-if="grantTotal>10"><button class="secondary compact" :disabled="grantPage===0" @click="changePage(grantPage-1)">上一页</button><span>第 {{grantPage+1}} 页</span><button class="secondary compact" :disabled="(grantPage+1)*10>=grantTotal" @click="changePage(grantPage+1)">下一页</button></div></section>
<section class="surface"><h2>使用情况统计</h2><p class="quiet">记录使用了哪些功能、是否完成和耗时，帮助改进产品。不会采集输入框内容、简历、回答或聊天正文。</p><label class="checkbox-label"><input type="checkbox" :checked="store.analyticsEnabled" :disabled="prefBusy || !store.analyticsLoaded" @change="toggleAnalytics">允许可选使用情况统计</label><p class="field-note">关闭后停止可选前端事件；必要的计费、安全和业务记录仍会保留。产品事件保留 90 天，反馈内容保留 180 天，自愿留下的联系方式保留 90 天。</p></section></div>
<section v-show="tab==='model'" class="surface model-settings"><span class="eyebrow">当前使用：{{effectiveLabel}}</span><h2>选择对话模型</h2><p class="quiet">无需配置 API 也能直接练习。修改后点击保存，对后续调用生效。</p><div class="setting-choices"><button v-for="p in [{id:'DEEPSEEK',name:'DeepSeek'},{id:'QWEN',name:'通义千问'}]" :key="p.id" :aria-pressed="draftProvider===p.id" @click="chooseProvider(p.id)" :disabled="busy"><strong>{{p.name}}</strong><app-icon v-if="draftProvider===p.id" name="check"/></button></div>
<template v-if="draftProvider==='DEEPSEEK'"><h3>DeepSeek 使用方式</h3><div class="setting-choices"><button aria-label="平台提供" :aria-pressed="draftMode==='PLATFORM'" @click="draftMode='PLATFORM'" :disabled="busy"><span><strong>平台提供</strong><small>无需配置，使用平台积分</small></span><app-icon v-if="draftMode==='PLATFORM'" name="check"/></button><button aria-label="我的 DeepSeek API" :aria-pressed="draftMode==='BYOK'" @click="draftMode='BYOK'" :disabled="busy"><span><strong>我的 DeepSeek API</strong><small>模型费由自己支付，另收服务积分</small></span><app-icon v-if="draftMode==='BYOK'" name="check"/></button></div>
<section v-if="draftMode==='BYOK'" class="key-config"><h3>配置 DeepSeek API Key</h3><p class="field-note">{{store.hasKey?'已保存，末四位 '+store.last4:'尚未保存自己的 Key'}}{{store.hasKey && store.chatProvider==='DEEPSEEK' && store.keyMode==='BYOK'?' · 当前使用中':''}}</p><label for="byok-key">DeepSeek API Key</label><input id="byok-key" v-model="key" type="password" autocomplete="off" placeholder="输入你的 DeepSeek API Key" :disabled="busy"><p class="field-note fee-note">模型费用由你的 DeepSeek 账户支付。平台另按模型用量收取服务积分，每 {{policy?.byokTokensPerPoint || 5000}} token 扣 1 积分；资料向量化仍使用平台积分。</p><p class="field-note">Key 加密保存在服务端，保存后不再显示明文。</p><div class="row wrap"><button class="primary" @click="saveKey" :disabled="busy || !key.trim()">保存并使用 DeepSeek API</button><button v-if="store.hasKey" class="text-button danger-text" @click="removeKey" :disabled="busy">删除 Key</button></div></section></template>
<div v-else class="mode-explanation"><strong>使用平台提供的通义千问</strong><p>按平台积分规则计费。当前暂不支持自带千问 API。</p><p v-if="store.hasKey" class="field-note">已保存的 DeepSeek Key 会保留，使用千问时不会调用它。</p></div>
<div class="save-settings row wrap"><span class="grow">保存后使用：<strong>{{draftLabel}}</strong></span><button class="primary" @click="saveSettings" :disabled="busy || (draftMode==='BYOK' && !store.hasKey)">{{busy?'正在保存…':'保存设置'}}</button></div></section>
<section v-show="tab==='usage'" class="surface usage-surface"><div class="section-heading"><div><h2>积分使用记录</h2><p>查看模型调用和实际扣减；历史记录保留当时的计费规则。</p></div><button class="secondary compact" @click="loadUsage">刷新使用记录</button></div><p v-if="!usage.length" class="empty-copy">暂无使用记录。</p><div v-else class="table-scroll"><table><thead><tr><th>时间</th><th>场景</th><th>模型</th><th>方式</th><th>实际扣除积分</th></tr></thead><tbody><tr v-for="row in usage" :key="row.id"><td>{{fmtTime(row.createdAt)}}</td><td>{{sceneLabel(row.scene)}}</td><td>{{row.model}}</td><td>{{row.keyMode==='BYOK'?'我的 API 服务费':'平台用量'}}</td><td>{{formatPoints(row.chargedTokens)}}</td></tr></tbody></table></div></section></section>`
    };
    const FeedbackDialog = {
        data: () => ({ loading: true, busy: false, error: '', existing: null, progress: '', experience: '', blockers: [], suggestions: '', followUp: false, contact: '', submitted: false, progressOptions, blockerOptions }),
        computed: { editable() { return !this.existing || ['PENDING', 'NEEDS_INFO'].includes(this.existing.status); }, statusText() { return statusLabels[this.existing?.status] || ''; } },
        async mounted() {
            this.$refs.dialog.showModal();
            try {
                const data = await refreshFeedback(); this.existing = data.feedback;
                if (this.existing) Object.assign(this, { progress: this.existing.progress, experience: this.existing.experience || '', blockers: [...(this.existing.blockers || [])], suggestions: this.existing.suggestions || '', followUp: !!this.existing.followUp, contact: this.existing.contact || '' });
                if (this.editable && store.feedbackDraft && store.feedbackDraft.feedbackId === (this.existing?.id || null)) Object.assign(this, { ...store.feedbackDraft, blockers: [...store.feedbackDraft.blockers] });
            } catch (error) { if (!error.stale) this.error = error.message; }
            finally { this.loading = false; }
        },
        methods: {
            close() {
                if (this.busy) return;
                if (this.editable && !this.submitted) store.feedbackDraft = { feedbackId: this.existing?.id || null, progress: this.progress, experience: this.experience, blockers: [...this.blockers], suggestions: this.suggestions, followUp: this.followUp, contact: this.contact };
                store.feedbackOpen = false;
            },
            toggleBlocker(value) {
                if (this.blockers.includes(value)) this.blockers = this.blockers.filter(item => item !== value);
                else if (value === 'NONE' || value === 'UNSURE') this.blockers = [value];
                else this.blockers = [...this.blockers.filter(item => item !== 'NONE' && item !== 'UNSURE'), value];
            },
            async submit() {
                if (this.busy || !this.editable || this.loading) return;
                this.error = '';
                if (!this.progress || !this.experience.trim() || !this.blockers.length) { this.error = '请选择体验进度和原因，并描述一处具体体验。'; return; }
                this.busy = true;
                try {
                    const result = await api('/api/feedback', this.existing ? 'PUT' : 'POST', { progress: this.progress, experience: this.experience.trim(), blockers: this.blockers, suggestions: this.suggestions.trim(), followUp: this.followUp, contact: this.followUp ? this.contact.trim() : '' });
                    this.existing = result; store.feedback = result; store.feedbackLoaded = true; store.feedbackDraft = null; this.submitted = true;
                } catch (error) { if (!error.stale) this.error = error.message; }
                finally { this.busy = false; }
            }
        },
        template: `<dialog ref="dialog" class="feedback-dialog" aria-labelledby="feedback-title" @cancel.prevent="close"><div class="section-heading"><span class="eyebrow">让练习更好用</span><button class="icon-button" @click="close" :disabled="busy" aria-label="关闭反馈">×</button></div><h2 id="feedback-title">分享使用体验，<span class="no-wrap">获赠 {{store.billingPolicy?.feedbackRewardPoints || 200}} 积分</span></h2><p class="quiet">花约 1～2 分钟，告诉我你的实际体验。好评、差评同等奖励；首次具体体验反馈审核通过后赠送积分，每个账号一次。</p><p v-if="loading" role="status">正在读取反馈…</p><p v-if="error" class="inline-error" role="alert">{{error}}</p>
<div v-if="submitted" class="feedback-received" role="status"><h3>已收到，等待审核</h3><p>预计 48 小时内处理。可以在“我的账号”查看进度，积分到账后会显示在补充记录中。</p><button class="primary" @click="close">完成</button></div>
<template v-else-if="!loading"><p v-if="existing" class="feedback-status">{{statusText}}<span v-if="existing.reviewNote"> · {{existing.reviewNote}}</span></p><form @submit.prevent="submit"><fieldset :disabled="busy || !editable"><legend>1．这次你体验到了哪一步？〔单选〕</legend><label class="survey-choice" v-for="[value,label] in progressOptions" :key="value"><input type="radio" name="feedback-progress" v-model="progress" :value="value" required>{{label}}</label></fieldset>
<label for="feedback-experience">2．请说一处具体的使用体验。〔必填〕</label><p class="field-note">有帮助的地方、让你困惑的操作、遇到的问题，都可以。说说当时想做什么、实际发生了什么，或者希望怎样；一两句能说明情况就可以。</p><textarea id="feedback-experience" v-model="experience" maxlength="2000" rows="4" placeholder="填写你的体验…" required :disabled="busy || !editable"></textarea><p class="field-note">请勿填写 API Key、密码、联系方式或简历中的隐私信息。</p>
<fieldset :disabled="busy || !editable"><legend>3．目前影响你继续使用的主要原因是什么？〔多选〕</legend><label class="survey-choice" v-for="[value,label] in blockerOptions" :key="value"><input type="checkbox" :checked="blockers.includes(value)" @change="toggleBlocker(value)">{{label}}</label><p class="field-note">“没有什么障碍”和“暂时无法判断”不与其他选项同时选择。</p></fieldset>
<label for="feedback-suggestions">4．你对平台有什么建议？〔选填〕</label><p class="field-note">希望增加的功能、调整的体验，或者其他想法，都可以写在这里。</p><textarea id="feedback-suggestions" v-model="suggestions" maxlength="2000" rows="3" placeholder="填写你的建议…" :disabled="busy || !editable"></textarea><hr>
<label class="survey-choice"><input type="checkbox" v-model="followUp" :disabled="busy || !editable">我愿意接受一次简短的后续沟通。〔选填，不影响奖励〕</label><label v-if="followUp" for="feedback-contact">微信或邮箱<input id="feedback-contact" v-model="contact" maxlength="200" :disabled="busy || !editable" placeholder="自愿填写，仅用于本次反馈跟进"></label><p class="field-note">联系方式不用于营销，保留 90 天；反馈内容保留 180 天。反馈会关联账号及相关使用信息，由开发者查看，用于排查问题和改进产品。</p>
<div class="feedback-rules"><strong>关于积分奖励</strong><p>按是否描述具体体验审核，不要求好评，不按字数判断。重复提交、复制无关内容或重复领取，不再赠送积分。预计 48 小时内处理。</p></div><div class="row wrap"><button type="button" class="secondary" @click="close" :disabled="busy">{{editable?'暂不填写':'关闭'}}</button><button v-if="editable" class="primary" :disabled="busy">{{busy?'正在提交…':existing?'补充并提交反馈':'提交反馈'}}</button></div></form></template></dialog>`
    };
    const FeedbackInvitation = {
        data: () => ({ visible: false, checking: false }),
        watch: { 'store.view': { handler() { this.check(); }, immediate: true }, 'store.report'() { this.check(); }, 'store.analyticsLoaded'() { this.check(); }, 'store.feedbackLoaded'() { this.check(); } },
        methods: {
            async check() {
                if (this.checking || this.visible || store.view !== 'history' || !store.report || !store.analyticsLoaded || !store.feedbackLoaded || store.reportInvitationSeen || store.feedback) return;
                this.checking = true;
                // Claim this one-time functional preference before showing; failed reads never invent a saved state.
                try { await api('/api/user/analytics-preference', 'PUT', { reportInvitationSeen: true }); store.reportInvitationSeen = true; this.visible = true; track('FEEDBACK_INVITE_VIEW', 'history', 'report'); }
                catch (_) { /* Can still use the permanent account entry. */ }
                finally { this.checking = false; }
            },
            dismiss() { this.visible = false; },
            open() { this.visible = false; openFeedback('report'); }
        },
        template: `<aside v-if="visible && !store.feedback" class="feedback-invitation"><div><strong>这份报告对你有帮助吗？</strong><p>分享一次具体体验，审核后获赠 {{store.billingPolicy?.feedbackRewardPoints || 200}} 积分。好评、差评都欢迎。</p></div><button class="secondary compact" @click="open">分享体验</button><button class="icon-button" @click="dismiss" aria-label="关闭反馈邀请">×</button></aside>`
    };
    const AdminPanel = {
        data: () => ({ tab: 'overview', days: 7, busy: false, error: '', overview: null, analytics: null, users: [], userPage: 0, userTotal: 0, q: '', selected: null, grants: [], grantPage: 0, grantTotal: 0, feedbacks: [], feedbackPage: 0, feedbackTotal: 0, feedbackStatus: 'PENDING', notes: {}, preview: false, grantTarget: null, grantRequestId: uuid(), grantForm: { username: '', points: 200, type: 'GIFT', reason: '', amountCny: '', orderRef: '' } }),
        watch: { 'store.view'(view) { if (view === 'admin') this.refresh(); } },
        methods: {
            money, sceneLabel: v => sceneLabels[v] || v, grantLabel: v => grantLabels[v] || v, statusLabel: v => statusLabels[v] || v, eventLabel: v => eventLabels[v] || v, pageLabel: v => pages[v] || v || '—',
            progressLabel: v => progressOptions.find(item => item[0] === v)?.[1] || v, blockerLabel: v => blockerOptions.find(item => item[0] === v)?.[1] || v,
            percent: v => v == null ? '暂无可统计队列' : (Number(v) * 100).toFixed(1) + '%',
            async selectTab(tab) { this.tab = tab; this.error = ''; await this.refresh(); },
            async refresh() {
                if (store.role !== 'ADMIN' || this.busy) return;
                this.busy = true; this.error = '';
                try {
                    if (this.tab === 'overview') this.overview = await api('/api/admin/overview?days=' + this.days);
                    if (this.tab === 'users') await this.loadUsers();
                    if (this.tab === 'grants') await this.loadGrants();
                    if (this.tab === 'feedback') await this.loadFeedback();
                    if (this.tab === 'analytics') this.analytics = await api('/api/admin/analytics?days=' + this.days);
                } catch (error) { if (!error.stale) this.error = error.message; }
                finally { this.busy = false; }
            },
            async loadUsers(page = this.userPage) { const data = await api('/api/admin/users?q=' + encodeURIComponent(this.q.trim()) + '&page=' + page + '&size=20'); this.users = rows(data); this.userPage = data.page; this.userTotal = data.total; },
            async search() { this.userPage = 0; await this.refresh(); },
            async userDetail(user) { try { this.selected = await api('/api/admin/users/' + user.id + '/activity?days=' + this.days); } catch (error) { if (!error.stale) this.error = error.message; } },
            async loadGrants(page = this.grantPage) { const data = await api('/api/admin/grants?page=' + page + '&size=20'); this.grants = rows(data); this.grantPage = data.page; this.grantTotal = data.total; },
            async loadFeedback(page = this.feedbackPage) { const data = await api('/api/admin/feedback?page=' + page + '&size=20' + (this.feedbackStatus ? '&status=' + this.feedbackStatus : '')); this.feedbacks = rows(data); this.feedbackPage = data.page; this.feedbackTotal = data.total; },
            async page(kind, direction) { this[kind + 'Page'] += direction; await this.refresh(); },
            startGrant(username = '') { this.grantForm = { username, points: 200, type: 'GIFT', reason: '', amountCny: '', orderRef: '' }; this.grantRequestId = uuid(); this.grantTarget = null; this.preview = false; this.tab = 'grants'; this.refresh(); },
            async previewGrant() {
                this.error = '';
                if (!this.grantForm.username.trim() || !this.grantForm.reason.trim() || Number(this.grantForm.points) <= 0) { this.error = '请填写目标账号、正数积分和补充原因。'; return; }
                if (this.grantForm.type === 'PAID' && (!(Number(this.grantForm.amountCny) > 0) || !this.grantForm.orderRef.trim())) { this.error = '付费补额需要已确认的收款金额和订单参考。'; return; }
                this.busy = true;
                try {
                    const result = await api('/api/admin/users?q=' + encodeURIComponent(this.grantForm.username.trim()) + '&page=0&size=100');
                    this.grantTarget = rows(result).find(user => user.username === this.grantForm.username.trim());
                    if (!this.grantTarget) { this.error = '没有找到此账号，请检查用户名。'; return; }
                    this.preview = true;
                } catch (error) { if (!error.stale) this.error = error.message; }
                finally { this.busy = false; }
            },
            async confirmGrant() {
                if (this.busy || !this.preview) return;
                this.busy = true; this.error = '';
                try {
                    await api('/api/admin/grants', 'POST', { ...this.grantForm, username: this.grantForm.username.trim(), points: Number(this.grantForm.points), reason: this.grantForm.reason.trim(), amountCny: this.grantForm.type === 'PAID' ? Number(this.grantForm.amountCny) : null, orderRef: this.grantForm.type === 'PAID' ? this.grantForm.orderRef.trim() : '', requestId: this.grantRequestId });
                    notify('积分已补充，记录已保存。'); this.preview = false; this.grantRequestId = uuid(); this.grantForm.reason = ''; await this.loadGrants();
                    if (this.selected?.user.username === this.grantForm.username) await this.userDetail(this.selected.user);
                } catch (error) { if (!error.stale) this.error = error.message; }
                finally { this.busy = false; }
            },
            editGrant() { this.preview = false; this.grantRequestId = uuid(); },
            async review(item, action) {
                if (this.busy) return;
                if (action === 'NEEDS_INFO' && !this.notes[item.id]?.trim()) { this.error = '请写明需要用户补充什么信息。'; return; }
                if (!confirm(action === 'REWARD' ? '通过此反馈并赠送 200 积分？同一账号仅奖励一次。' : action === 'CLOSE' ? '关闭此反馈？请勿仅因负面评价关闭。' : '将补充要求发送给用户？')) return;
                this.busy = true; this.error = '';
                try { await api('/api/admin/feedback/' + item.id + '/review', 'POST', { action, note: this.notes[item.id] || '' }); notify(action === 'REWARD' ? '审核通过，200 积分已到账。' : '审核状态已更新。'); await this.loadFeedback(); }
                catch (error) { if (!error.stale) this.error = error.message; }
                finally { this.busy = false; }
            }
        },
        template: `<section class="admin-page"><div class="page-heading"><div><span class="eyebrow">内测运营</span><h1>运营管理</h1><p>查看使用情况、处理反馈和补充积分。</p></div><div class="row"><select v-model="days" @change="refresh" aria-label="统计时间范围"><option :value="1">今天</option><option :value="7">近 7 天</option><option :value="30">近 30 天</option></select><button class="secondary" @click="refresh" :disabled="busy">刷新</button></div></div><div class="account-tabs segmented admin-tabs" role="group" aria-label="运营栏目"><button v-for="item in [{id:'overview',name:'运营概览'},{id:'users',name:'用户'},{id:'grants',name:'补额记录'},{id:'feedback',name:'反馈审核'},{id:'analytics',name:'产品与成本'}]" :key="item.id" :aria-pressed="tab===item.id" @click="selectTab(item.id)" :disabled="busy">{{item.name}}</button></div><p v-if="error" class="inline-error" role="alert">{{error}}</p><p v-if="busy" role="status">正在处理…</p>
<div v-if="tab==='overview' && overview" class="metric-grid"><div class="surface metric" v-for="item in [{name:'新注册',value:overview.registeredUsers},{name:'活跃用户',value:overview.activeUsers},{name:'开始面试',value:overview.interviewsStarted},{name:'生成报告',value:overview.reportsGenerated},{name:'查看报告',value:overview.reportsViewed},{name:'消耗积分',value:overview.chargedPoints},{name:'平台估算成本',value:money(overview.platformEstimatedCostCny)},{name:'确认付费补额',value:money(overview.paidAmountCny)},{name:'待审核反馈',value:overview.pendingFeedback}]" :key="item.name"><span class="quiet">{{item.name}}</span><strong>{{item.value ?? '—'}}</strong></div></div><p v-if="tab==='overview' && overview?.budget" class="field-note">今日预算已计入 {{money(overview.budget.spentCny)}}，在途预留 {{money(overview.budget.reservedCny)}}；未知失败可能按上限保守计入。</p><p v-if="tab==='overview'" class="field-note">活跃以核心业务成功操作去重统计。成本为估算，确认付费补额由管理员人工录入，不代表支付系统核验或净利润。</p>
<div v-if="tab==='users'" class="account-sections"><section class="surface"><form class="input-action" @submit.prevent="search"><input v-model="q" placeholder="搜索用户名" aria-label="搜索用户"><button class="secondary" :disabled="busy">搜索</button></form><div class="table-scroll"><table><thead><tr><th>账号</th><th>注册 / 最近活跃</th><th>当前使用</th><th>剩余积分</th><th>近 7 日用量</th><th>面试 / 报告</th><th>操作</th></tr></thead><tbody><tr v-for="u in users" :key="u.id"><td>{{u.username}}</td><td>{{fmtTime(u.createdAt)}}<br>{{fmtTime(u.lastActiveAt)}}</td><td>{{u.chatProvider==='QWEN'?'通义千问':'DeepSeek'}} · {{u.effectiveKeyMode==='BYOK'?'我的 API':'平台'}}</td><td>{{u.remainingPoints}}</td><td>{{u.sevenDayTokens}} token</td><td>{{u.interviewCount}} / {{u.reportCount}}</td><td><button class="text-button" @click="userDetail(u)">详情</button><button class="text-button" @click="startGrant(u.username)">补积分</button></td></tr></tbody></table></div><p v-if="!users.length && !busy" class="empty-copy">没有找到用户。</p><div class="row"><button class="secondary compact" :disabled="busy || userPage===0" @click="page('user',-1)">上一页</button><span>第 {{userPage+1}} 页 · 共 {{userTotal}} 人</span><button class="secondary compact" :disabled="busy || (userPage+1)*20>=userTotal" @click="page('user',1)">下一页</button></div></section>
<section v-if="selected" class="surface"><div class="section-heading"><h2>{{selected.user.username}} · 使用详情</h2><button class="text-button" @click="selected=null">收起</button></div><p class="quiet">剩余 {{selected.user.remainingPoints}} 积分；只展示使用元数据，不展示简历、回答或聊天正文。</p><h3>模型用量（最近 100 条）</h3><div class="table-scroll"><table><thead><tr><th>时间</th><th>场景</th><th>模型</th><th>token</th><th>实扣积分</th></tr></thead><tbody><tr v-for="r in selected.usage" :key="r.id"><td>{{fmtTime(r.createdAt)}}</td><td>{{sceneLabel(r.scene)}}</td><td>{{r.model}}</td><td>{{r.totalTokens}}</td><td>{{formatPoints(r.chargedTokens)}}</td></tr></tbody></table></div><h3>使用时间线（最近 100 条）</h3><ol class="activity-timeline"><li v-for="e in selected.events" :key="e.id"><time>{{fmtTime(e.createdAt)}}</time><span>{{eventLabel(e.name)}} · {{pageLabel(e.page)}}<small v-if="e.durationMs!=null"> · {{e.durationMs}} ms</small></span></li></ol><h3>补额历史</h3><p v-if="!selected.grants.length" class="empty-copy">暂无记录。</p><p v-for="g in selected.grants" :key="g.id">{{fmtTime(g.createdAt)}} · {{grantLabel(g.type)}} +{{g.points}} 积分 · {{g.reason}}</p></section></div>
<div v-if="tab==='grants'" class="account-sections"><section class="surface"><h2>补充积分</h2><form v-if="!preview" @submit.prevent="previewGrant" class="admin-grant-form"><label>目标账号<input v-model="grantForm.username" required maxlength="32"></label><label>补充积分<input v-model="grantForm.points" type="number" min="0.002" max="1000000" step="0.002" required></label><label>补额类型<select v-model="grantForm.type"><option value="GIFT">赠送</option><option value="PAID">付费补额</option><option value="COMPENSATION">补偿</option><option value="ADJUSTMENT">调整</option></select></label><template v-if="grantForm.type==='PAID'"><label>已确认收款金额（元）<input v-model="grantForm.amountCny" type="number" min="0.01" step="0.01" required></label><label>订单参考<input v-model="grantForm.orderRef" maxlength="100" required></label></template><label class="full-width">原因（用户可见）<textarea v-model="grantForm.reason" required maxlength="500" rows="2"></textarea></label><p class="field-note full-width">付费补额前请人工确认收款；赠送和补偿不计收入。</p><button class="primary" :disabled="busy">预览补额</button></form><div v-else class="grant-preview"><p v-if="grantTarget">当前余额：<strong>{{grantTarget.remainingPoints}} 积分</strong>。以实际入账时的余额为准。</p><p>将为 <strong>{{grantForm.username}}</strong> 补充 <strong>{{grantForm.points}} 积分</strong>，类型：{{grantLabel(grantForm.type)}}。</p><p>原因：{{grantForm.reason}}</p><p v-if="grantForm.type==='PAID'">收款 {{money(grantForm.amountCny)}} · {{grantForm.orderRef}}</p><div class="row"><button class="secondary" @click="editGrant" :disabled="busy">返回修改</button><button class="primary" @click="confirmGrant" :disabled="busy">确认补充积分</button></div></div></section><section class="surface"><h2>补额记录</h2><div class="table-scroll"><table><thead><tr><th>时间</th><th>账号</th><th>类型</th><th>积分</th><th>金额</th><th>原因 / 订单参考</th><th>操作人</th></tr></thead><tbody><tr v-for="g in grants" :key="g.id"><td>{{fmtTime(g.createdAt)}}</td><td>{{g.username}}</td><td>{{grantLabel(g.type)}}</td><td>+{{g.points}}</td><td>{{g.type==='PAID'?money(g.amountCny):'—'}}</td><td>{{g.reason}}<small v-if="g.orderRef"> · {{g.orderRef}}</small></td><td>{{g.operatorId || '系统'}}</td></tr></tbody></table></div><p v-if="!grants.length" class="empty-copy">暂无补额记录。</p><div class="row"><button class="secondary compact" :disabled="busy || grantPage===0" @click="page('grant',-1)">上一页</button><span>第 {{grantPage+1}} 页 · 共 {{grantTotal}} 条</span><button class="secondary compact" :disabled="busy || (grantPage+1)*20>=grantTotal" @click="page('grant',1)">下一页</button></div></section></div>
<section v-if="tab==='feedback'" class="surface"><div class="section-heading"><h2>反馈审核</h2><select v-model="feedbackStatus" @change="feedbackPage=0;refresh()" aria-label="反馈状态"><option value="">全部状态</option><option v-for="(label,value) in {PENDING:'等待审核',NEEDS_INFO:'请补充说明',REWARDED:'已赠送积分',CLOSED:'已关闭'}" :key="value" :value="value">{{label}}</option></select></div><p class="field-note">好评、差评同等奖励；依据具体体验审核，不按字数判断。奖励每账号一次。</p><p v-if="!feedbacks.length && !busy" class="empty-copy">此状态下暂无反馈。</p><article v-for="f in feedbacks" :key="f.id" class="review-card"><div class="section-heading"><h3>{{f.username}} · {{statusLabel(f.status)}}</h3><span class="quiet">{{fmtTime(f.updatedAt || f.createdAt)}}</span></div><p class="quiet">体验进度：{{progressLabel(f.progress)}}</p><p class="preserve-lines">{{f.experience}}</p><p><strong>继续使用的障碍：</strong>{{(f.blockers || []).map(blockerLabel).join('、')}}</p><p v-if="f.suggestions" class="preserve-lines"><strong>建议：</strong>{{f.suggestions}}</p><p v-if="f.followUp && f.contact">愿意后续沟通：{{f.contact}}</p><p v-if="f.reviewNote" class="field-note">上次处理说明：{{f.reviewNote}}</p><template v-if="['PENDING','NEEDS_INFO'].includes(f.status)"><label :for="'review-note-'+f.id">处理说明（用户可见）</label><textarea :id="'review-note-'+f.id" v-model="notes[f.id]" maxlength="500" rows="2" placeholder="需要补充时，请明确说明缺少什么。"></textarea><div class="row wrap"><button class="primary" @click="review(f,'REWARD')" :disabled="busy">通过并赠 200 积分</button><button class="secondary" @click="review(f,'NEEDS_INFO')" :disabled="busy">请用户补充</button><button class="text-button danger-text" @click="review(f,'CLOSE')" :disabled="busy">关闭反馈</button></div></template></article><div class="row"><button class="secondary compact" :disabled="busy || feedbackPage===0" @click="page('feedback',-1)">上一页</button><span>第 {{feedbackPage+1}} 页 · 共 {{feedbackTotal}} 条</span><button class="secondary compact" :disabled="busy || (feedbackPage+1)*20>=feedbackTotal" @click="page('feedback',1)">下一页</button></div></section>
<div v-if="tab==='analytics' && analytics" class="account-sections"><section class="surface"><h2>体验漏斗</h2><p class="field-note">同一注册队列，各阶段按用户去重；报告查看为可选事件，缺失不代表一定未查看。</p><div class="metric-grid"><div class="metric" v-for="f in analytics.funnel" :key="f.name"><span class="quiet">{{eventLabel(f.name)}}</span><strong>{{f.count}}</strong></div></div><template v-if="analytics.feedbackFunnel?.length"><h3>反馈参与</h3><p v-for="f in analytics.feedbackFunnel" :key="f.name">{{f.name}} · {{f.count}} 人</p><p class="field-note">各项是所选时段内人数，不能直接当作转化率。</p></template><h3>回访</h3><p>次日：{{percent(analytics.retention?.day1?.rate)}}（{{analytics.retention?.day1?.returned || 0}} / {{analytics.retention?.day1?.eligible || 0}}）</p><p>第七日：{{percent(analytics.retention?.day7?.rate)}}（{{analytics.retention?.day7?.returned || 0}} / {{analytics.retention?.day7?.eligible || 0}}）</p><p class="field-note">只统计已经走完观察时间的用户队列。</p></section><section class="surface"><h2>模型与成本</h2><p>平台估算成本：<strong>{{money(analytics.cost?.platformEstimatedCostCny)}}</strong>；用户自己的 API 用量：{{analytics.cost?.byokTokens || 0}} token</p><p>出报告场次估算中位数 {{money(analytics.cost?.sessionMedianCny)}} / P90 {{money(analytics.cost?.sessionP90Cny)}}；未归因调用 {{analytics.cost?.unattributedCalls || 0}} 次，平台成本未知 {{analytics.cost?.unknownPlatformCostCalls || 0}} 次。</p><p class="field-note">估算不等于厂商账单。单场统计按所选时段内生成报告的场次，汇总该场全部已知用量；失败开场、未出报告与未关联场次调用不混入。导入资料费用另计，用户自己的 DeepSeek 模型费不计入平台成本。</p><div class="table-scroll"><table><thead><tr><th>模型</th><th>调用 / 重试</th><th>token</th><th>平台估算成本</th><th>失败 / 成本未知</th></tr></thead><tbody><tr v-for="m in analytics.models" :key="m.model"><td>{{m.model}}</td><td>{{m.calls}} / {{m.retryAttempts || 0}}</td><td>{{m.tokens}}</td><td>{{money(m.platformEstimatedCostCny)}}</td><td>{{m.failedCalls || 0}} / {{m.unknownPlatformCostCalls || 0}}</td></tr></tbody></table></div><h3>各功能成本</h3><div class="table-scroll"><table><thead><tr><th>场景</th><th>调用 / 重试</th><th>token</th><th>平台估算成本</th></tr></thead><tbody><tr v-for="s in analytics.scenes || []" :key="s.scene"><td>{{sceneLabel(s.scene)}}</td><td>{{s.calls}} / {{s.retryAttempts || 0}}</td><td>{{s.tokens}}</td><td>{{money(s.platformEstimatedCostCny)}}</td></tr></tbody></table></div><p class="field-note">重试次数不等于新增成功调用；失败或重试中缺少用量的部分未计入已知成本，日预算会保守预留。</p></section><div class="settings-grid"><section class="surface"><h2>失败分布</h2><p v-if="!analytics.errors?.length" class="empty-copy">暂无失败记录。</p><p v-for="e in analytics.errors" :key="e.code">{{e.code}} · {{e.count}} 次</p></section><section class="surface"><h2>推广来源</h2><p v-if="!analytics.sources?.length" class="empty-copy">暂无可归因来源。</p><p v-for="s in analytics.sources" :key="s.source">{{s.source}} · {{s.count}}</p></section></div><section class="surface"><h2>每日使用</h2><div class="table-scroll"><table><thead><tr><th>日期</th><th>活跃用户</th><th>积分消耗</th><th>已知平台成本估算</th></tr></thead><tbody><tr v-for="d in analytics.daily" :key="d.date"><td>{{d.date}}</td><td>{{d.activeUsers}}</td><td>{{d.chargedPoints}}</td><td>{{money(d.platformEstimatedCostCny)}}</td></tr></tbody></table></div></section></div></section>`
    };
    return { SettingsPanel, FeedbackDialog, FeedbackInvitation, AdminPanel, openFeedback };
};
