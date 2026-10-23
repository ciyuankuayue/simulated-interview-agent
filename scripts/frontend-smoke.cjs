/**
 * Browser regression for the shipped Vue client against deterministic HTTP/SSE fixtures.
 * Uses Playwright from NODE_PATH or a local installation; never calls an LLM.
 * Run: node scripts/frontend-smoke.cjs
 * Optional: FRONTEND_BROWSER=/path/to/chrome, FRONTEND_VUE_RUNTIME=/path/to/vue.global.prod.js
 * Screenshots and summary: target/frontend-smoke/
 */
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const http = require('node:http');
const { chromium } = require('playwright');
const root = path.resolve(__dirname, '..');
const output = path.join(root, 'target', 'frontend-smoke');
fs.mkdirSync(output, { recursive: true });
const accounts = new Map();
const calls = [];
const unexpected = [];
let failSettings = false, failAnswer = false, failExport = false, failCompanion = false, failQuestion = false, failCoach = false;
let delayQuota = false;
let quotaCompanion = false;
function account(token) {
    if (!accounts.has(token)) accounts.set(token, {
        jd: [], resumes: [], reports: [], streams: new Set(), events: [], answerCount: 0,
        mode: 'PLATFORM', provider: 'DEEPSEEK', last4: '', due: true
    });
    return accounts.get(token);
}
const json = (res, value, status = 200) => {
    res.writeHead(status, { 'Content-Type': 'application/json; charset=utf-8' }); res.end(JSON.stringify(value));
};
function emit(a, type, data) {
    const event = 'event: ' + type + '\ndata: ' + JSON.stringify(data) + '\n\n';
    a.events.push(event); a.streams.forEach(res => res.write(event));
}
const report = { reportId: 901, matchScore: 76,
    overallNotes: '有清晰的项目思路，可以补充量化结果。',
    collapsedPoints: ['验证方案的收益'], expressionIssues: [{ issue: '结果缺少量化', guide: '补充基线与观察窗口。' }],
    reminders: ['继续练习真实项目细节。'], behaviorReview: null,
    perTurnReview: [{ turnSeq: 1, score: 5, question: '你是怎样验证项目收益的？', track: 'tech',
        explanation: '说明比较方式与数据来源。', correction: '补充基线。', knowledgeSupplement: '考虑干扰变量。', exemplarAnswer: '先说明背景，再给出验证方法。' }]
};
const server = http.createServer(async (req, res) => {
    const url = new URL(req.url, 'http://localhost'), endpoint = url.pathname;
    if (!endpoint.startsWith('/api/')) {
        if (endpoint === '/favicon.ico') { res.writeHead(204); res.end(); return; }
        if (endpoint === '/vue.global.prod.js' && process.env.FRONTEND_VUE_RUNTIME) {
            res.setHeader('Content-Type', 'application/javascript'); res.end(fs.readFileSync(process.env.FRONTEND_VUE_RUNTIME)); return;
        }
        const file = endpoint === '/' ? 'templates/index.html' :
            endpoint === '/app.js' ? 'static/app.js' : endpoint === '/style.css' ? 'static/style.css' : null;
        if (!file) { res.writeHead(404); res.end(); return; }
        let text = fs.readFileSync(path.join(root, 'src/main/resources', file), 'utf8');
        if (endpoint === '/') {
            text = text.replace('th:href="@{/style.css}"', 'href="/style.css"').replace('th:src="@{/app.js}"', 'src="/app.js"');
            if (process.env.FRONTEND_VUE_RUNTIME) text = text.replace('https://unpkg.com/vue@3/dist/vue.global.prod.js', '/vue.global.prod.js');
        }
        res.setHeader('Content-Type', endpoint === '/app.js' ? 'application/javascript' : endpoint === '/style.css' ? 'text/css' : 'text/html; charset=utf-8');
        res.end(text); return;
    }
    let body = '';
    for await (const chunk of req) body += chunk;
    const data = body ? JSON.parse(body) : {};
    const token = req.headers.authorization?.replace('Bearer ', '') || url.searchParams.get('jwt');
    const a = account(token);
    calls.push({ endpoint, method: req.method, data, token });
    const route = req.method + ' ' + endpoint;
    if (route === 'POST /api/auth/login' || route === 'POST /api/auth/register') {
        json(res, { token: 'fixture-' + data.username, username: data.username, userId: 1 }, route.endsWith('register') ? 201 : 200); return;
    }
    if (!token) { json(res, { message: '请先登录' }, 401); return; }
    if (route === 'GET /api/user/quota') {
        const remaining = a.remaining ?? (token.includes('user_b') ? 12345 : 499000);
        const respond = () => json(res, { totalTokens: 500000, usedTokens: 500000 - remaining, remainingTokens: remaining });
        if (delayQuota && !token.includes('user_b')) setTimeout(respond, 1200); else respond();
        return;
    }
    if (route === 'GET /api/user/settings') { json(res, { keyMode: a.mode, chatProvider: a.provider }); return; }
    if (route === 'PUT /api/user/settings') {
        if (failSettings) { json(res, { message: '模型切换失败，请重试' }, 503); return; }
        if (data.keyMode === 'BYOK' && !a.last4) { json(res, { message: '请先保存 Key', code: 'KEY_NOT_CONFIGURED' }, 409); return; }
        if (data.keyMode) a.mode = data.keyMode;
        if (data.chatProvider) a.provider = data.chatProvider;
        json(res, { keyMode: a.mode, chatProvider: a.provider }); return;
    }
    if (route === 'GET /api/user/api-key') { json(res, { hasKey: !!a.last4, last4: a.last4 }); return; }
    if (route === 'PUT /api/user/api-key') { a.last4 = data.apiKey.slice(-4); json(res, { hasKey: true, last4: a.last4 }); return; }
    if (route === 'DELETE /api/user/api-key') { a.last4 = ''; a.mode = 'PLATFORM'; json(res, { hasKey: false, keyMode: a.mode }); return; }
    if (route === 'GET /api/user/usage') {
        json(res, [
            { id: 1, scene: 'CHAT', keyMode: 'BYOK', model: 'deepseek:deepseek-flash', totalTokens: 1234, chargedTokens: 500, createdAt: '2026-10-19T03:00:00Z' },
            { id: 2, scene: 'CHAT', keyMode: 'PLATFORM', model: 'qwen:qwen3.8-flash', totalTokens: 1, chargedTokens: 1, createdAt: '2026-10-19T03:01:00Z' }
        ]); return;
    }
    if (route === 'POST /api/jd') {
        if (!data.rawText || 'text' in data) { json(res, { message: 'rawText 不能为空' }, 400); return; }
        a.jd.push({ jdId: a.jd.length + 1, jobType: '产品研发工程师', keywords: ['系统设计', '项目实践'], sameTypeTag: data.sameTypeTag });
        json(res, [a.jd.at(-1)]); return;
    }
    if (route === 'GET /api/jd') { json(res, a.jd); return; }
    if (route === 'GET /api/resume') { json(res, a.resumes); return; }
    if (route === 'POST /api/resume') {
        const row = { resumeId: a.resumes.length + 10, createdAt: '2026-10-19T03:00:00Z', isActive: !a.resumes.length };
        a.resumes.push(row); json(res, row); return;
    }
    if (/^POST \/api\/resume\/\d+\/activate$/.test(route)) {
        a.resumes.forEach(row => { row.isActive = endpoint.includes('/' + row.resumeId + '/'); });
        json(res, { resumeId: a.resumes.find(row => row.isActive)?.resumeId, isActive: true }); return;
    }
    if (/^GET \/api\/(project-doc|note|real-interview)$/.test(route)) { json(res, []); return; }
    if (/^POST \/api\/(project-doc|note|real-interview)$/.test(route)) { json(res, { docId: 1, noteId: 1, recordId: 1 }); return; }
    if (route === 'POST /api/interview/start') {
        a.events = []; a.answerCount = 0;
        emit(a, 'question', { turnSeq: 1, layer: 0, role: '技术深挖官', question: '你是怎样验证项目收益的？' });
        emit(a, 'await_answer', { turnSeq: 1 });
        json(res, { sessionId: 101, firstQuestion: '你是怎样验证项目收益的？', status: 'QUESTIONING' }); return;
    }
    if (route === 'GET /api/interview/101/events') {
        res.writeHead(200, { 'Content-Type': 'text/event-stream', 'Cache-Control': 'no-cache', Connection: 'keep-alive' });
        a.events.forEach(event => res.write(event)); a.streams.add(res);
        req.on('close', () => a.streams.delete(res)); return;
    }
    if (route === 'POST /api/interview/101/answer') {
        if (failAnswer) { json(res, { message: '请补充额度后继续', code: 'QUOTA_EXHAUSTED' }, 402); return; }
        setTimeout(() => {
            emit(a, 'score', { turnSeq: ++a.answerCount, score: 5, track: 'tech', oneLineCorrection: '补充验证基线。' });
            if (failQuestion) {
                emit(a, 'error', { code: 'QUESTION_FAILED', message: 'fixture question failure' });
                json(res, { accepted: true }); return;
            }
            if (a.answerCount === 1) {
                emit(a, 'followup', { turnSeq: 2, layer: 1, question: '如何排除其他因素造成的提升？', role: '技术深挖官' });
                emit(a, 'await_answer', { turnSeq: 2 });
            } else {
                a.reports = [{ sessionId: 101, jobType: '产品研发工程师', hasReport: true, matchScore: 76, createdAt: '2026-10-19T03:00:00Z', status: 'DONE' }];
                emit(a, 'report', report); emit(a, 'done', { sessionId: 101 });
            }
            json(res, { accepted: true });
        }, 250); return;
    }
    if (route === 'POST /api/interview/101/end') {
        emit(a, 'report', report); emit(a, 'done', { sessionId: 101 }); json(res, report); return;
    }
    if (route === 'GET /api/interview/sessions') { json(res, a.reports); return; }
    if (route === 'GET /api/interview/trend') { json(res, a.reports); return; }
    if (route === 'GET /api/interview/101/report') { json(res, report); return; }
    if (route === 'GET /api/interview/101/turns') { json(res, [{ seq: 1, question: '你是怎样验证项目收益的？', answer: 'A_PRIVATE_ANSWER' }]); return; }
    if (route === 'GET /api/interview/101/report.md') {
        if (failExport) { json(res, { message: '导出暂不可用' }, 503); return; }
        res.setHeader('Content-Type', 'text/markdown; charset=utf-8'); res.end('# 面试报告\n\n匹配度：76'); return;
    }
    const weak = { weakPointId: 1, knowledgePoint: '验证方案的收益', wrongCount: 2, reviewStage: 0, nextReviewAt: '2026-10-19T03:00:00Z' };
    if (route === 'GET /api/weak-point' || route === 'GET /api/weak-point/due') { json(res, token.includes('user_b') || !a.due ? [] : [weak]); return; }
    if (route === 'GET /api/weak-point/1') { json(res, { ...weak, latest: { question: '你是怎样验证项目收益的？', answer: 'A_PRIVATE_ANSWER', review: { explanation: '补充数据来源。' } } }); return; }
    if (route === 'POST /api/weak-point/1/review/question') { json(res, { question: '怎样建立实验基线？' }); return; }
    if (route === 'POST /api/weak-point/1/review/answer') { a.due = false; json(res, { score: 8, explanation: '回答完整。', resolved: true }); return; }
    if (route === 'POST /api/companion/chat') {
        if (quotaCompanion) { json(res, { message: '积分已用完。平台目前处于内测阶段，请联系工作人员补充额度。微信：13481264947', code: 'QUOTA_EXHAUSTED' }, 402); return; }
        if (failCompanion) { json(res, { message: '陪伴服务暂不可用，请重试' }, 503); return; }
        json(res, { reply: '面试前有些紧张很自然，我们可以先慢慢整理一下。', safe: false }); return;
    }
    if (route === 'POST /api/companion/reset') { json(res, { reset: true }); return; }
    if (route === 'POST /api/coach/chat') {
        if (failCoach) { json(res, { message: '知识答疑暂不可用，请重试' }, 503); return; }
        json(res, { reply: '小师：事务隔离用于控制并发事务彼此可见的变化，可以从读已提交的例子开始理解。' }); return;
    }
    if (route === 'POST /api/coach/reset') { json(res, { ok: true }); return; }
    unexpected.push(route); json(res, { message: 'Unexpected fixture endpoint: ' + route }, 404);
});
const sleep = ms => new Promise(resolve => setTimeout(resolve, ms));
async function main() {
    await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
    const base = 'http://127.0.0.1:' + server.address().port;
    const executablePath = process.env.FRONTEND_BROWSER || [
        'C:/Program Files/Google/Chrome/Application/chrome.exe',
        'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe'
    ].find(candidate => fs.existsSync(candidate));
    const browser = await chromium.launch({ headless: true, ...(executablePath ? { executablePath } : {}) });
    const context = await browser.newContext({ viewport: { width: 1440, height: 1050 }, acceptDownloads: true });
    const page = await context.newPage();
    page.setDefaultTimeout(15000);
    const errors = [];
    page.on('pageerror', error => errors.push(error.message));
    page.on('dialog', dialog => dialog.accept());
    const pass = label => console.log('PASS ' + label);
    const nav = label => page.getByRole('navigation', { name: '主导航' }).getByRole('button', { name: label, exact: true }).click();
    const visible = selector => page.locator(selector + ':visible');
    const click = label => page.getByRole('button', { name: label, exact: true }).click();
    const screenshot = async name => {
        await page.evaluate(() => window.scrollTo(0, 0));
        return page.screenshot({ path: path.join(output, name + '.png'), fullPage: true });
    };
    async function noOverflow(label) {
        const sizes = await page.evaluate(() => ({ page: document.documentElement.scrollWidth, viewport: window.innerWidth }));
        assert.ok(sizes.page <= sizes.viewport + 1, label + ' overflow ' + JSON.stringify(sizes));
    }
    async function login(name, destination = 'prepare') {
        await page.getByLabel('用户名', { exact: true }).fill(name);
        await page.getByLabel('密码', { exact: true }).fill('fixture-password');
        await click('进入练习室');
        await page.getByRole('heading', { name: destination === 'coach' ? '通考小师' : '开始一场新练习', exact: true }).waitFor();
    }
    try {
        await page.goto(base);
        await page.getByRole('heading', { name: '欢迎回到练习室' }).waitFor();
        await noOverflow('desktop welcome'); await screenshot('welcome');
        await page.setViewportSize({ width: 390, height: 844 });
        await noOverflow('mobile welcome'); await screenshot('mobile-welcome');
        await page.setViewportSize({ width: 360, height: 800 }); await noOverflow('360px welcome');
        await page.setViewportSize({ width: 1440, height: 1050 });
        await page.locator('.welcome-top').getByRole('button', { name: '通考小师，备考教练', exact: true }).click();
        await page.getByText('登录后即可和通考小师聊聊，已为你选好入口。', { exact: true }).waitFor();
        await login('user_a', 'coach');
        await page.getByText('我是通考小师，你的专属备考教练。请放心将知识疑问与学习困惑交给我，我们一起把难点弄懂~', { exact: true }).waitFor();
        await nav('开始练习'); pass('public buddy shortcuts preserve destination through login');
        await page.getByLabel('岗位描述', { exact: true }).fill('招聘产品研发工程师，熟悉系统设计，能独立交付项目并验证业务收益。');
        await click('保存并解析 JD'); await page.getByText('岗位已准备好，可以开始练习了。', { exact: true }).waitFor();
        assert.ok(calls.some(call => call.endpoint === '/api/jd' && call.data.rawText));
        assert.equal(await page.getByRole('button', { name: '开始模拟面试', exact: true }).isEnabled(), true);
        await screenshot('desktop-prepare'); pass('JD request shape, array response and selection');
        await nav('我的资料');
        await page.locator('input[type="file"]').setInputFiles({
            name: 'resume.md', mimeType: 'text/markdown', buffer: Buffer.from('# A_PRIVATE_RESUME\n真实项目与个人经历')
        });
        await page.getByText('文件已读取，请检查内容后确认保存。', { exact: true }).waitFor();
        assert.ok((await page.getByLabel('资料内容', { exact: true }).inputValue()).includes('A_PRIVATE_RESUME'));
        await click('确认保存'); await page.getByText('简历版本已保存。', { exact: true }).waitFor();
        await nav('开始练习'); await page.getByLabel('本次简历版本').selectOption('10');
        await sleep(100);
        assert.ok(calls.some(call => call.endpoint === '/api/resume/10/activate'));
        pass('resume save, list reuse and activation');
        await nav('账号与设置'); await click('刷新设置与用量');
        assert.ok((await page.locator('.balance-pill').innerText()).includes('998'));
        const usageRows = page.locator('.usage-surface tbody tr');
        await usageRows.first().waitFor();
        assert.equal(await usageRows.first().locator('td').last().innerText(), '1');
        assert.equal(await usageRows.nth(1).locator('td').last().innerText(), '0.002');
        assert.equal(/tokens?/i.test(await page.locator('body').innerText()), false);
        account('fixture-user_a').remaining = 1;
        await click('刷新设置与用量');
        await page.waitForFunction(() => document.querySelector('.balance-pill').innerText.includes('0.002'));
        delete account('fixture-user_a').remaining;
        await click('刷新设置与用量');
        pass('points: existing balance preserved, BYOK fee 1, fractional balance/usage 0.002 visible, raw tokens hidden');
        await click('通义千问');
        await page.locator('.setting-choices button[aria-pressed="true"]').filter({ hasText: '通义千问' }).waitFor();
        failSettings = true; await click('DeepSeek');
        await page.getByText('模型切换失败，请重试', { exact: true }).waitFor();
        assert.equal(await page.locator('.setting-choices button[aria-pressed="true"]').innerText(), '通义千问');
        failSettings = false;
        await page.getByLabel('DeepSeek API Key', { exact: true }).fill('sk-fixture-not-a-real-key-1234');
        await click('保存并启用自带 Key');
        await page.getByText('已保存，末四位 1234', { exact: true }).waitFor();
        assert.ok(calls.some(call => call.endpoint === '/api/user/api-key' && call.method === 'PUT'));
        assert.ok(calls.some(call => call.endpoint === '/api/user/settings' && call.data.keyMode === 'BYOK'));
        await click('删除 Key'); await page.getByText('尚未保存自带 Key', { exact: true }).waitFor();
        pass('settings persist after success; failure does not fake selection; BYOK lifecycle');
        await nav('开始练习'); await click('开始模拟面试');
        await page.getByRole('heading', { name: '面试练习', exact: true }).waitFor();
        await page.getByLabel('你的回答', { exact: true }).waitFor({ state: 'visible' });
        await page.waitForFunction(() => !document.querySelector('#answer-input').disabled);
        await page.getByLabel('你的回答', { exact: true }).fill('先建立基线，再比较数据。');
        await page.locator('.workspace-header').getByRole('button', { name: '陪考小暖，心态陪伴', exact: true }).click();
        await page.getByText('我是陪考小暖，你的专属心态陪伴。请放心将备考路上的心事交给我，我们一起慢慢梳理~', { exact: true }).waitFor();
        await click('返回刚才的页面');
        assert.equal(await page.getByLabel('你的回答', { exact: true }).inputValue(), '先建立基线，再比较数据。');
        pass('header companion jump and return preserve active interview draft');
        const countBefore = calls.filter(call => call.endpoint.endsWith('/answer')).length;
        await click('提交回答');
        assert.equal(await page.getByRole('button', { name: '提交回答', exact: true }).isDisabled(), true);
        await page.getByText('如何排除其他因素造成的提升？', { exact: true }).waitFor();
        assert.equal(calls.filter(call => call.endpoint.endsWith('/answer')).length - countBefore, 1);
        await screenshot('desktop-interview'); pass('start navigation, SSE question/followup, submission lock');
        account('fixture-user_a').streams.forEach(res => res.end());
        await page.getByRole('button', { name: '重新连接', exact: true }).waitFor();
        await click('重新连接');
        await page.waitForFunction(() => !document.querySelector('#answer-input').disabled);
        assert.equal(await visible('.message.question').count(), 2);
        pass('real EventSource reconnect replays without duplicate questions');
        failAnswer = true;
        await page.getByLabel('你的回答', { exact: true }).fill('控制变量并选择相同的观察窗口。');
        await click('提交回答');
        await page.getByRole('dialog', { name: '本次调用的积分不足' }).waitFor();
        const quotaDialog = page.getByRole('dialog', { name: '本次调用的积分不足' });
        await quotaDialog.getByText('平台目前处于内测阶段，请联系工作人员补充额度。', { exact: true }).waitFor();
        await quotaDialog.getByText('13481264947', { exact: true }).waitFor();
        await page.setViewportSize({ width: 360, height: 800 });
        const contactBounds = await quotaDialog.locator('.support-wechat strong').boundingBox();
        assert.ok(contactBounds.x >= 0 && contactBounds.x + contactBounds.width <= 360);
        await screenshot('mobile-beta-quota');
        await page.setViewportSize({ width: 1440, height: 1050 });
        await click('查看积分与设置'); await nav('面试工作区');
        assert.equal(await page.getByLabel('你的回答', { exact: true }).inputValue(), '控制变量并选择相同的观察窗口。');
        failAnswer = false;
        await click('提交回答'); await page.getByRole('button', { name: '查看本场报告', exact: true }).waitFor();
        await click('查看本场报告');
        await page.getByRole('heading', { name: '这一次，离目标更近了多少？' }).waitFor();
        await screenshot('desktop-report');
        assert.equal(calls.filter(call => call.endpoint === '/api/interview/101/end').length, 0);
        await page.locator('.turn summary').click();
        await page.getByText('说明比较方式与数据来源。', { exact: true }).waitFor();
        await click('查看原始问答'); await page.getByText('A_PRIVATE_ANSWER', { exact: true }).waitFor();
        const downloadEvent = page.waitForEvent('download');
        await click('导出 Markdown'); const download = await downloadEvent;
        assert.ok(fs.readFileSync(await download.path(), 'utf8').includes('匹配度：76'));
        failExport = true; let downloadedError = false;
        const onDownload = () => { downloadedError = true; }; page.on('download', onDownload);
        await click('导出 Markdown'); await page.getByText('导出暂不可用', { exact: true }).waitFor();
        assert.equal(downloadedError, false); page.off('download', onDownload); failExport = false;
        pass('402 preserves draft; natural report, replay and checked Markdown export');
        await nav('错题本'); await page.locator('.weak-point summary').click();
        await page.getByText('补充数据来源。', { exact: true }).waitFor();
        await click('开始今日复习'); await page.getByText('怎样建立实验基线？', { exact: true }).waitFor();
        await page.locator('#review-answer').fill('记录同等条件下的原始指标。');
        await click('提交判分'); await page.getByText('已掌握，本题已出队。', { exact: true }).waitFor();
        pass('weak-point detail and spaced review');
        if (await page.getByRole('button', { name: '关闭提示', exact: true }).count()) await click('关闭提示');
        await nav('陪考小暖'); failCompanion = true;
        await page.getByLabel('想对小暖说的话').fill('面试有点紧张。'); await click('发送');
        await page.getByText('陪伴服务暂不可用，请重试', { exact: true }).waitFor();
        assert.equal(await page.getByLabel('想对小暖说的话').inputValue(), '面试有点紧张。');
        failCompanion = false; quotaCompanion = true; await click('发送');
        await page.getByRole('dialog', { name: '本次调用的积分不足' }).getByText('13481264947', { exact: true }).waitFor();
        await click('查看积分与设置');
        await page.locator('.quota-surface').getByText('13481264947', { exact: true }).waitFor();
        await nav('陪考小暖');
        assert.equal(await page.getByLabel('想对小暖说的话').inputValue(), '面试有点紧张。');
        quotaCompanion = false; await click('发送');
        await page.getByText('面试前有些紧张很自然，我们可以先慢慢整理一下。', { exact: true }).waitFor();
        await screenshot('desktop-warm');
        pass('companion errors and 402 retain draft; beta notice and staff WeChat visible in dialog/settings/mobile');
        await page.locator('.workspace-header').getByRole('button', { name: '通考小师，备考教练', exact: true }).click();
        await page.getByLabel('想问小师的问题').fill('什么是事务隔离？');
        await click('发送');
        await page.getByText('小师：事务隔离用于控制并发事务彼此可见的变化，可以从读已提交的例子开始理解。', { exact: true }).waitFor();
        await screenshot('desktop-coach');
        assert.ok(calls.some(call => call.endpoint === '/api/coach/chat' && call.data.message === '什么是事务隔离？'));
        failCoach = true;
        await page.getByLabel('想问小师的问题').fill('我的另一个知识问题。'); await click('发送');
        await page.getByText('知识答疑暂不可用，请重试', { exact: true }).waitFor();
        assert.equal(await page.getByLabel('想问小师的问题').inputValue(), '我的另一个知识问题。');
        failCoach = false;
        await click('清空对话');
        await page.getByRole('heading', { name: '哪一个问题，让你停住了？', exact: true }).waitFor();
        await nav('陪考小暖');
        await page.getByText('面试前有些紧张很自然，我们可以先慢慢整理一下。', { exact: true }).waitFor();
        assert.equal(await page.locator('.buddy-page.warm:visible .companion-chat').innerText().then(text => text.includes('事务隔离')), false);
        pass('study coach has independent API/chat/reset and retains failed question; warm conversation unaffected');
        if (await page.getByRole('button', { name: '关闭提示', exact: true }).count()) await click('关闭提示');
        await page.setViewportSize({ width: 390, height: 844 });
        for (const name of ['开始练习', '面试工作区', '报告与成长', '错题本', '我的资料', '陪考小暖', '通考小师', '账号与设置']) {
            await nav(name); await noOverflow('mobile ' + name);
            assert.equal(await page.locator('.workspace-header .buddy-link').count(), 2);
        }
        await nav('开始练习'); await screenshot('mobile-prepare');
        await nav('陪考小暖'); await screenshot('mobile-companion');
        await page.setViewportSize({ width: 360, height: 800 });
        for (const name of ['开始练习', '面试工作区', '报告与成长', '错题本', '我的资料', '陪考小暖', '通考小师', '账号与设置']) {
            await nav(name); await noOverflow('360px ' + name);
        }
        pass('390px and 360px: all eight views have no document overflow and expose both buddy shortcuts');
        delayQuota = true; await nav('账号与设置'); await click('刷新设置与用量'); await sleep(50);
        await page.getByRole('button', { name: '退出登录', exact: true }).click();
        await login('user_b'); await sleep(1400);
        await nav('报告与成长');
        assert.equal(await page.locator('.report-summary').count(), 0);
        await nav('面试工作区'); assert.equal(await page.locator('.message').count(), 0);
        assert.equal(await page.locator('body').innerText().then(text => /A_PRIVATE/.test(text)), false);
        assert.ok(await page.locator('.balance-pill').innerText().then(text => text.includes('24.69')));
        pass('account reset clears private content; late prior-user response cannot overwrite new state');
        await page.setViewportSize({ width: 1440, height: 1050 });
        await nav('开始练习');
        await page.getByLabel('岗位描述', { exact: true }).fill('产品研发工程师，负责系统设计和项目交付。');
        await click('保存并解析 JD'); await page.getByText('岗位已准备好，可以开始练习了。', { exact: true }).waitFor();
        await click('开始模拟面试'); await page.waitForFunction(() => !document.querySelector('#answer-input').disabled);
        failQuestion = true;
        await page.getByLabel('你的回答', { exact: true }).fill('先建立基线，验证项目收益。');
        await click('提交回答');
        await page.getByText('下一步暂未准备成功。已提交的回答会保留，你可以继续生成，或结束本场查看报告。', { exact: true }).waitFor();
        failQuestion = false;
        await page.getByText('使用已保存的原回答继续生成；会按本次模型调用扣减积分。', { exact: true }).waitFor();
        await click('继续生成下一步');
        await page.getByRole('button', { name: '查看本场报告', exact: true }).waitFor();
        const recoveryAnswers = calls.filter(call => call.token === 'fixture-user_b' && call.endpoint.endsWith('/answer'));
        assert.equal(recoveryAnswers.length, 2);
        assert.equal(recoveryAnswers[0].data.answer, recoveryAnswers[1].data.answer);
        assert.equal(await page.locator('.message.answer').count(), 1);
        assert.equal(calls.filter(call => call.token === 'fixture-user_b' && call.endpoint.endsWith('/end')).length, 0);
        pass('question recovery resubmits only the saved answer, explains new-call cost and keeps one visible answer');
        await nav('开始练习');
        await click('开始模拟面试'); await page.waitForFunction(() => !document.querySelector('#answer-input').disabled);
        failQuestion = true;
        await page.getByLabel('你的回答', { exact: true }).fill('先建立基线，验证项目收益。');
        await click('提交回答');
        await page.getByRole('button', { name: '继续生成下一步', exact: true }).waitFor();
        await click('结束并出报告'); await page.getByRole('button', { name: '查看本场报告', exact: true }).waitFor();
        assert.equal(calls.filter(call => call.token === 'fixture-user_b' && call.endpoint === '/api/interview/101/end').length, 1);
        pass('question generation failure unlocks manual end; existing end endpoint called once');
        assert.deepEqual(unexpected, []); assert.deepEqual(errors, []);
        assert.equal(await page.evaluate(() => localStorage.length + sessionStorage.length), 0);
        pass('zero unexpected API routes, zero browser runtime errors, no persistent token storage');
        fs.writeFileSync(path.join(output, 'summary.json'), JSON.stringify({ passed: true, runtimeErrors: errors, unexpected, calls: calls.length }, null, 2));
    } catch (error) {
        await screenshot('failure').catch(() => {});
        console.error('FAIL', error.stack);
        console.error('Browser errors:', errors);
        process.exitCode = 1;
    } finally {
        await browser.close();
        server.closeAllConnections();
        await new Promise(resolve => server.close(resolve));
    }
}
main().catch(error => { console.error(error.message); server.closeAllConnections(); server.close(); process.exitCode = 1; });
