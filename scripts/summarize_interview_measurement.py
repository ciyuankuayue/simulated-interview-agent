"""Summarize opt-in measurements without inventing missing grades or baselines.

Usage: python scripts/summarize_interview_measurement.py target/interview-measurement/<run>
Optional quality-grades.json: [{blindId,relevance,jobFit,depth,evidenceDiscipline,note}].
Requires only Python's standard library. Never reads API keys or makes network calls.
"""
import csv
import json
import math
import sys
from pathlib import Path


def percentile(values, p):
    values = sorted(values)
    return values[max(0, math.ceil(len(values) * p) - 1)] if values else None


def main():
    root = Path(sys.argv[1]).resolve()
    samples = json.loads((root / "samples.json").read_text(encoding="utf-8"))
    manifest = json.loads((root / "manifest.json").read_text(encoding="utf-8"))
    measured = [s for s in samples if not s["warmup"]]
    successful = [s for s in measured if s["httpStatus"] == 200 and not s.get("error")]
    fields = ["id", "caseId", "mode", "repeat", "httpStatus", "apiMs", "ragMs", "embeddingMs",
              "searchMs", "generationMs", "providerMs", "promptTokens", "completionTokens", "totalTokens",
              "promptSha256", "question", "error"]
    with (root / "samples.csv").open("w", encoding="utf-8-sig", newline="") as f:
        writer = csv.DictWriter(f, fieldnames=fields, extrasaction="ignore")
        writer.writeheader()
        writer.writerows(measured)
    lines = ["# 模拟面试提问量化试验", "", f"开始时间（UTC）：{manifest['startedUtc']}", "",
             "本次为配置对照，不是历史优化前后。A=同一 DeepSeek 模型开启思考出题，B=当前非思考出题。",
             "固定六个合成案例，各两次重复，交替顺序，每组各一次预热不计入统计。",
             "实测本机 HTTP 首题接口完整返回；H2、内存 Lucene、测试身份，未包括浏览器/WAN/MySQL和答题后评分链路。", "",
             "| 指标 | A：思考 | B：非思考 |", "|---|---:|---:|"]
    def fmt(value):
        return "未测" if value is None else f"{value:.3f}"
    for field, label in [("apiMs", "首题 API 完整响应"), ("ragMs", "RAG 整体"),
                         ("embeddingMs", "查询向量化"), ("searchMs", "Lucene search-only"),
                         ("generationMs", "出题服务（含路由/计费）"), ("providerMs", "模型 Provider（含网络/重试）")]:
        for p, suffix in [(0.5, "P50"), (0.95, "P95")]:
            cells = [fmt(percentile([s[field] for s in successful if s["mode"] == mode], p))
                     for mode in ["thinking", "fast"]]
            lines.append(f"| {label} {suffix}（ms） | {' | '.join(cells)} |")
    for mode in ["thinking", "fast"]:
        attempted = [s for s in measured if s["mode"] == mode]
        ok = [s for s in successful if s["mode"] == mode]
        lines += ["", f"{mode}：成功 {len(ok)}/{len(attempted)}；厂商报告对话 token 合计 "
                  f"{sum(s['totalTokens'] for s in ok)}。"]
    prompt_ok = all(len({s["promptSha256"] for s in measured if s["caseId"] == case}) == 1
                    for case in {s["caseId"] for s in measured})
    lines += ["", f"同案例提示词 SHA-256 完全一致：{prompt_ok}。", "",
              "P95 使用 nearest-rank；每组仅 12 条，P95 实际等于最大值，不能视为稳定的生产尾延迟。",
              "计时使用 nanoTime，不把亚毫秒检索取整为 0ms；各阶段分位数不能相加。",
              "Provider 耗时包含网络、生成、重试与响应解析，不是厂商 GPU 推理时间；未测 TTFT。",
              "测试探针与 H2 计费产生额外开销；无生产并发负载。不同配置 RAG 抖动不能当作检索优化。", ""]
    key = {x["blindId"]: x for x in json.loads((root / "quality-key.json").read_text(encoding="utf-8"))}
    grades_path = root / "quality-grades.json"
    dimensions = ["relevance", "jobFit", "depth", "evidenceDiscipline"]
    if grades_path.exists():
        grades = json.loads(grades_path.read_text(encoding="utf-8"))
        assert len({g["blindId"] for g in grades}) == len(grades), "duplicate blind IDs"
        assert set(g["blindId"] for g in grades) == set(key), "grade every sample, no cherry-picking"
        for g in grades:
            assert all(type(g[d]) is int and 0 <= g[d] <= 2 for d in dimensions), "grade out of range"
        lines += ["## 提问质量", "", "评分者身份和依据见 quality-review.md；不能把辅助评阅当成独立人工专家验证。", "",
                  "| 配置 | 各维均值：相关性 / 岗位角色匹配 / 深度 / 证据约束 | 总分均值（0–8） | 达标数 |", "|---|---|---:|---:|"]
        for mode in ["thinking", "fast"]:
            group = [g for g in grades if key[g["blindId"]]["mode"] == mode]
            means = " / ".join(f"{sum(g[d] for g in group)/len(group):.2f}" for d in dimensions)
            total = sum(sum(g[d] for d in dimensions) for g in group) / len(group)
            passed = sum(sum(g[d] for d in dimensions) >= 6 and min(g[d] for d in dimensions) >= 1 for g in group)
            lines.append(f"| {mode} | {means} | {total:.2f} | {passed}/{len(group)} |")
        lines += ["", "预先规定：四维各 0–2，总分≥6 且每维≥1 为本次样例达标。",
                  "小样本和单一评分者不能证明非劣效；六个案例重复不等于 24 个独立业务场景。"]
    else:
        lines += ["提问质量：未评分，不能声称快模式质量不下降。盲评输入见 quality-blind.json。"]
    usage = json.loads((root / "embedding-usage.json").read_text(encoding="utf-8"))
    lines += ["", "## 用量", "", f"全部成功对话（含预热）报告 token：{sum(s['totalTokens'] for s in samples)}。",
              f"全部向量调用（含语料准备）次数：{len(usage)}；报告 token：{sum(x['tokens'] for x in usage)}。",
              "未获取厂商账单或价目，不换算为人民币；测试数据库积分不是实际货币账单。", "",
              "无历史完整 API/模型分段基线，因此历史优化百分比留空。"]
    (root / "report.md").write_text("\n".join(lines) + "\n", encoding="utf-8")
    print(root / "report.md")


if __name__ == "__main__":
    main()
