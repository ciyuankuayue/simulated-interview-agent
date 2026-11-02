"""Opt-in ten synthetic full journeys through a local isolated server; incurs model usage.

Run only against a disposable H2/RAM-index process with measurement admin credentials in env:
  python scripts/measure-launch.py --base http://127.0.0.1:18085 --live
No API key/JWT/password or candidate content is written to the output artifacts.
"""
import argparse
import concurrent.futures
import json
import os
from pathlib import Path
import statistics
import time
import urllib.error
import urllib.request
import uuid

parser = argparse.ArgumentParser()
parser.add_argument('--base', default='http://127.0.0.1:18085')
parser.add_argument('--live', action='store_true')
parser.add_argument('--output', default='target/launch-measurement.json')
parser.add_argument('--cases', default='', help='Optional comma-separated case numbers for a separate recheck artifact')
args = parser.parse_args()
if not args.live or not args.base.startswith(('http://127.0.0.1:', 'http://localhost:')):
    raise SystemExit('Requires --live and an isolated loopback server.')
if not all(os.getenv(k) for k in ('DEEPSEEK_API_KEY', 'DASHSCOPE_API_KEY', 'LAUNCH_MEASUREMENT_ADMIN_PASSWORD')):
    raise SystemExit('Missing measurement credentials (values not displayed).')

def request(path, body=None, token=None, method=None):
    headers = {'Content-Type': 'application/json', 'Accept': 'application/json'}
    if token:
        headers['Authorization'] = 'Bearer ' + token
    data = None if body is None else json.dumps(body, ensure_ascii=False).encode('utf-8')
    req = urllib.request.Request(args.base + path, data=data, headers=headers, method=method or ('POST' if data else 'GET'))
    try:
        with urllib.request.urlopen(req, timeout=240) as response:
            return response.status, json.load(response)
    except urllib.error.HTTPError as error:
        try:
            return error.code, json.load(error)
        except (ValueError, UnicodeError):
            return error.code, {'code': 'NON_JSON_ERROR'}

def checked(path, body=None, token=None, method=None):
    status, result = request(path, body, token, method)
    if status >= 400:
        raise RuntimeError(f'{path.split("?")[0]}:{status}:{result.get("code", "ERROR")}')
    return result

admin = checked('/api/auth/login', {'username': os.getenv('LAUNCH_MEASUREMENT_ADMIN_USERNAME', 'launch_measure_admin'),
                                 'password': os.environ['LAUNCH_MEASUREMENT_ADMIN_PASSWORD']})['token']
cases = [
    ('DEEPSEEK', False, 'Java初级后端', '集合与异常处理', False),
    ('QWEN', False, 'Java初级后端', '集合与异常处理', False),
    ('DEEPSEEK', False, 'Python后端', '异步任务取消', False),
    ('QWEN', False, 'Python后端', '异步任务取消', False),
    ('DEEPSEEK', False, '前端开发', '请求竞态与状态管理', True),
    ('QWEN', False, '前端开发', '请求竞态与状态管理', True),
    ('DEEPSEEK', False, 'Java后端', '事务与缓存一致性', True),
    ('QWEN', False, 'Java后端', '事务与缓存一致性', True),
    ('DEEPSEEK', True, 'Python后端', '幂等与重试', False),
    ('DEEPSEEK', True, 'Java后端', '事务与异常回滚', True),
]

def run_case(index, case):
    provider, byok, role, topic, long_material = case
    sample = {'index': index, 'provider': provider, 'byok': byok, 'longMaterial': long_material,
              'phases': [], 'success': False}
    started = time.monotonic()
    token = None
    try:
        username = 'lm_' + uuid.uuid4().hex[:18]
        account = checked('/api/auth/register', {'username': username, 'password': uuid.uuid4().hex})
        token = account['token']
        checked('/api/admin/grants', {'username': username, 'points': 10000, 'type': 'GIFT',
                                     'reason': 'isolated synthetic measurement', 'requestId': str(uuid.uuid4())}, admin)
        if index == 1:
            assert request('/api/admin/users', token=token)[0] == 403
            feedback = checked('/api/feedback', {'progress': 'LOOKED_ONLY', 'experience': '合成验收：在账号页找到模型配置入口。',
                'blockers': ['CONFUSING', 'SPEED'], 'suggestions': '这是本地隔离流程验收。', 'followUp': False, 'contact': ''}, token)
            before_reward = checked('/api/user/quota', token=token)['totalTokens']
            for _ in range(2):
                checked(f'/api/admin/feedback/{feedback["id"]}/review', {'action': 'REWARD', 'note': '合成验收'}, admin)
            after_reward = checked('/api/user/quota', token=token)['totalTokens']
            assert after_reward - before_reward == 100000
            sample['feedbackRewardExactlyOnce'] = True
        if byok:
            checked('/api/user/api-key', {'apiKey': os.environ['DEEPSEEK_API_KEY']}, token, 'PUT')
        checked('/api/user/settings', {'chatProvider': provider, 'keyMode': 'BYOK' if byok else 'PLATFORM'}, token, 'PUT')
        material = f'【合成测试资料，不是真实简历】我在学习项目中练习了{topic}，负责实现、测试和日志。没有线上大规模经历，也没有测量性能提升。'
        if long_material:
            material += '\n'.join(f'学习记录{i}：为练习项目补充失败测试与异常日志，只在本地验证，不代表生产成果。' for i in range(70))
        resume = checked('/api/resume', {'content': material}, token)
        sample['phases'].append('resume_ingested')
        jd = checked('/api/jd', {'rawText': f'招聘{role}，要求理解{topic}，能够解释本人贡献与测试方法。'}, token)[0]
        sample['phases'].append('jd_created')
        start_body = {'jdId': jd['jdId'], 'resumeId': resume['resumeId'], 'maxQuestions': 1, 'roles': ['技术深挖官']}
        start_status, session = request('/api/interview/start', start_body, token)
        if start_status == 504:
            # Explicit new request, with its own real usage; preserve the failed attempt in measurements.
            message = session.get('message', '')
            reason = 'AGENT_BUDGET' if '预算' in message else 'NETWORK' if '网络' in message else 'PROVIDER_OR_RESPONSE'
            sample['startFailure'] = {'status': start_status, 'code': session.get('code'), 'reason': reason}
            session = checked('/api/interview/start', start_body, token)
            sample['startRecovered'] = True
        elif start_status >= 400:
            raise RuntimeError(f'/api/interview/start:{start_status}:{session.get("code", "ERROR")}')
        sid = session['sessionId']
        sample['sessionId'] = sid
        sample['phases'].append('interview_started')
        answer = f'这是本地学习项目。我只做过{topic}的简单验证，主要通过构造失败输入和查看日志核对结果；生产规模、性能数据和更复杂的机制我不了解，不能提供未做过的经历。'
        status, outcome = request(f'/api/interview/{sid}/answer', {'answer': answer}, token)
        if status >= 400:
            sample['answerError'] = {'status': status, 'code': outcome.get('code')}
        else:
            sample['phases'].append('answer_saved')
        report = checked(f'/api/interview/{sid}/end', {}, token)
        sample['phases'].append('report_returned')
        checked(f'/api/interview/{sid}/report', token=token)
        sample['reportScorePresent'] = report.get('matchScore') is not None
        sample['success'] = bool(sample['reportScorePresent'] and status < 400)
        # Exercise request recovery boundary on completed sessions; must not charge another model call.
        prior = checked('/api/user/usage', token=token)
        rejected_status, _ = request(f'/api/interview/{sid}/answer', {'answer': answer}, token)
        after = checked('/api/user/usage', token=token)
        sample['completedRetryRejectedWithoutCharge'] = rejected_status == 409 and len(prior) == len(after)
    except Exception as error:
        # Exception text contains only route/status/code, never request payloads or credentials.
        sample['error'] = str(error) if isinstance(error, RuntimeError) else type(error).__name__
    finally:
        sample['elapsedSeconds'] = round(time.monotonic() - started, 2)
        if token:
            try:
                calls = checked('/api/user/usage', token=token)
                safe = ('scene','model','keyMode','totalTokens','chargedTokens','dueTokens','sessionId','platformCostMicros','status')
                sample['usage'] = [{k: row.get(k) for k in safe} for row in calls]
                sample['totalTokens'] = sum(row['totalTokens'] for row in calls)
                sample['chargedPoints'] = sum(row['chargedTokens'] for row in calls) / 500
                sample['platformEstimatedCostCny'] = sum(row.get('platformCostMicros') or 0 for row in calls) / 1_000_000
                sample['unknownPlatformCostCalls'] = sum(row.get('platformCostMicros') is None and row['keyMode'] == 'PLATFORM' for row in calls)
                balance = checked('/api/user/quota', token=token)
                sample['balanceMatchesUsage'] = balance['usedTokens'] == sum(row['chargedTokens'] for row in calls)
                sample['tariffMatchesUsage'] = all(row['chargedTokens'] == ((row['totalTokens'] + 9) // 10 if row['keyMode'] == 'BYOK' else row['totalTokens']) for row in calls)
            except Exception:
                sample['usageUnavailable'] = True
    return sample

results = []
with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:
    selected = {int(value) for value in args.cases.split(',')} if args.cases else set(range(1, len(cases)+1))
    if not selected.issubset(set(range(1, len(cases)+1))):
        raise SystemExit('Case numbers must be between 1 and 10.')
    futures = [pool.submit(run_case, i+1, case) for i, case in enumerate(cases) if i+1 in selected]
    for future in concurrent.futures.as_completed(futures):
        result = future.result()
        results.append(result)
        print(json.dumps({k: result.get(k) for k in ('index','provider','byok','success','totalTokens','error')}, ensure_ascii=False), flush=True)
        Path(args.output).write_text(json.dumps({'scope':'selected synthetic maxQuestions=1 journeys, real models, no production data; not full-length interview benchmarks',
            'samples':sorted(results,key=lambda x:x['index'])},ensure_ascii=False,indent=2),encoding='utf-8')
costs = [row['platformEstimatedCostCny'] for row in results if 'platformEstimatedCostCny' in row]
summary = {'attempted':len(results),'successful':sum(row['success'] for row in results),
           'totalTokens':sum(row.get('totalTokens',0) for row in results),
           'platformEstimatedCostCny':round(sum(costs),6),
           'medianJourneyPlatformCny':statistics.median(costs) if costs else None}
artifact=json.loads(Path(args.output).read_text(encoding='utf-8'));artifact['summary']=summary
artifact['adminOverview'] = checked('/api/admin/overview?days=1', token=admin)
artifact['adminAnalytics'] = checked('/api/admin/analytics?days=1', token=admin)
Path(args.output).write_text(json.dumps(artifact,ensure_ascii=False,indent=2),encoding='utf-8')
print(json.dumps(summary,ensure_ascii=False),flush=True)
