# -*- coding: utf-8 -*-
"""生成杨玲琳「产品经理（AI/技术产品方向）」简历：.docx（可编辑）+ .pdf（可投递）"""
import os

OUT_DIR = "C:/Users/19284/Desktop/有用/简历"
NAME = "北邮本科—杨玲琳—产品经理方向简历"

# ---------------------------------------------------------------------------
# 内容层（视角重框：从"开发"改为"产品定义/方案设计/落地运营"）
# 规则：**加粗** 标记在两段渲染里都会被转成粗体
# ---------------------------------------------------------------------------
NAME_LINE = "杨玲琳"
INTENT = "求职意向：产品经理（AI / 技术产品方向 · 校招 / 实习）"
CONTACT = ("2004.05 ｜ 北京邮电大学 计算机科学与技术（2027 届） ｜ 手机/微信 13481264947 ｜ "
           "邮箱 13481264947@163.com")

SUMMARY = ("计算机背景的 AI 产品方向候选人。独立设计并上线 2 款 AI 产品（模拟面试 / 技术情报聚合），"
           "具备从**用户痛点定义 → 方案设计 → 开发落地 → 上线运营 → 数据复盘**的闭环能力；"
           "同时具备大模型 / RAG / Agent 的技术理解，能与研发高效对齐需求、评审方案。"
           "寻求**产品经理（AI / 技术产品）**实习或校招机会。")

SECTIONS = [
    {
        "title": "教育背景",
        "blocks": [
            {
                "head": "北京邮电大学 ｜ 计算机科学与技术 ｜ 本科 ｜ 2023.09 – 2027.06",
                "lines": [
                    "主修课程：数据结构与算法、操作系统、计算机网络、数据库系统原理、软件工程等",
                    "荣誉奖项：全国大学生数学竞赛**全国二等奖 / 北京市一等奖**；大学生创新创业雏雁赛道**二等奖**；"
                    "校级二等奖学金（2024、2025）；校级三好学生（2024、2025）；优秀团员",
                ],
            }
        ],
    },
    {
        "title": "产品能力与特质",
        "blocks": [
            {"lines": [
                "**用户与需求**：习惯从用户痛点出发做需求拆解与方案设计，并坚持用数据定义成功标准；"
                "在项目中自建评测集做量化对比，用数据推翻先入为主假设并据此修正方案",
                "**AI 技术理解（差异化优势）**：理解大模型、RAG、Agent、Prompt 工程的原理与边界，"
                "能独立判断 AI 功能的可行性、设计人机协作流程、制定降级与风控策略——可无缝对接算法 / 工程团队",
                "**0→1 落地**：具备独立把想法做成可用产品的能力，已独立上线 2 个 AI 产品（需求→方案→落地→运营）",
                "**协作与工具**：能通过技术语言与研发高效对齐需求、评审方案；熟练使用 AI 提效工具（Claude Code / Cursor 等），"
                "可输出清晰的需求文档与接口 / 流程契约，降低沟通成本",
            ]},
        ],
    },
    {
        "title": "产品经历",
        "blocks": [
            {
                "head": "AI 模拟面试产品 ｜ 独立负责：产品定义 · 方案设计 · 落地运营 ｜ 2026.08 – 2026.09",
                "lines": [
                    "**用户洞察与机会**：针对「面试准备盲目、缺乏针对性反馈」的痛点，定义一款基于 JD 与个人简历的"
                    "多 Agent 模拟面试产品，目标用户为求职技术岗的学生 / 转岗人群",
                    "**产品方案设计**：设计「出题—追问—判分—复盘」四角色协作流程，用显式状态机保证环节不串台；"
                    "建立「JD 要求 ∩ 简历缺口」的精准出题机制，强制引用简历原文提升反馈可信度"
                    "（反馈引用简历原文比例 92%、重复出题率 <3%、追问挖深命中率 78%）",
                    "**关键决策与风控**：设计三级降级（判分→追问→报告任一环节失败不中断全流程）、SSE 断线续接、"
                    "多模型热切换，保障弱网 / 接口抖动下体验连续",
                    "**数据驱动迭代**：自建 150 篇语料 / 30 条查询评测集做方案消融，实测纯向量 recall@5 96.7%、"
                    "混合 RRF 持平、纯 BM25 单独仅 86.7%——据此推翻「混合检索必然更优」的先入为主假设，"
                    "修正为「向量为主通道、BM25 作互补保底」；单次向量化 P95 123ms、纯 Lucene 检索 <2ms",
                    "**结果**：独立上线公网可用产品，7000+ 行代码 / 14 张表 / 128 项测试全绿，健康检查全通过",
                ],
            },
            {
                "head": "AI 健康管理产品 · 身体成分分析子系统 ｜ 核心成员：需求调研 · 方案设计 ｜ 2025.09 – 2026.03",
                "lines": [
                    "**问题与方案调研**：针对「仅凭照片无法测体重」的硬约束，调研并主导确定"
                    "「SPIN+SMPL 三维重建 + XGBoost 体脂预测」方案，用身高 / 腿围等 8 维体态特征替代体重输入，"
                    "模型准确度提升约 8%",
                    "**端到端产品流程设计**：设计「拍照→三维重建→特征提取→体脂预测→指标分级→知识库→报告生成」流水线，"
                    "模块解耦、可独立迭代",
                    "**体验与稳定性**：设计「规则分级 + 知识库召回 + 大模型生成」的报告链路，并定义 LLM 失败自动降级模板策略，"
                    "报告可用性 ≥96%",
                    "**结果**：通过大学生创新创业训练计划结题验收；相关方法申请发明专利（受理中，发明人之一）",
                ],
            },
            {
                "head": "技术情报聚合产品 ｜ 独立负责：0→1 产品设计 · 运营监控 ｜ 2026.05 – 2026.07",
                "lines": [
                    "**产品定义**：面向开发者 / 技术从业者，设计自动聚合多源技术情报的产品，解决「信息分散、时效性差」问题",
                    "**方案与落地**：抽象统一数据契约配置化接入 6 个数据源，定时 + 线程池可控并发采集；"
                    "sourceId+url 指纹去重，重复占比 <2%",
                    "**运营思维**：自建 /health 监控端点暴露各源成功率、耗时与数据量，用令牌桶限流 + 失败降级保障稳定，"
                    "支撑后续运营数据跟踪",
                    "**结果**：独立完成从需求到上线的全流程",
                ],
            },
        ],
    },
    {
        "title": "校园与实践经历",
        "blocks": [
            {"lines": [
                "**大学生艺术团戏曲团 副团长（2024–2025）**：统筹 30+ 人团队，落地校内外演出 10+ 场（含国家大剧院），"
                "负责排期、资源协调与跨团协作",
                "**大班生活兼心理委员（2023–2026）**：协助学生事务，倾听并处理同学诉求，擅长沟通与矛盾协调",
                "**校国旗护卫队 成员（2023–2024）**：高强度训练培养纪律性与团队执行力",
            ]},
        ],
    },
]


# ---------------------------------------------------------------------------
# 渲染：.docx
# ---------------------------------------------------------------------------
def build_docx(path):
    from docx import Document
    from docx.shared import Pt, RGBColor, Inches
    from docx.enum.text import WD_ALIGN_PARAGRAPH
    from docx.oxml.ns import qn

    ACCENT = RGBColor(0x1F, 0x4E, 0x79)
    doc = Document()
    # 页边距
    for s in doc.sections:
        s.top_margin = Inches(0.6); s.bottom_margin = Inches(0.6)
        s.left_margin = Inches(0.7); s.right_margin = Inches(0.7)
    base = doc.styles["Normal"]
    base.font.name = "Microsoft YaHei"; base.font.size = Pt(10.5)
    base.element.rPr.rFonts.set(qn("w:eastAsia"), "Microsoft YaHei")

    def add_runs(p, text):
        import re
        for m in re.split(r"(\*\*.*?\*\*)", text):
            if not m:
                continue
            if m.startswith("**") and m.endswith("**"):
                r = p.add_run(m[2:-2]); r.bold = True
            else:
                p.add_run(m)

    # 标题
    t = doc.add_paragraph(); t.alignment = WD_ALIGN_PARAGRAPH.CENTER
    r = t.add_run(NAME_LINE); r.bold = True; r.font.size = Pt(20); r.font.color.rgb = ACCENT
    sub = doc.add_paragraph(); sub.alignment = WD_ALIGN_PARAGRAPH.CENTER
    rs = sub.add_run(INTENT); rs.font.size = Pt(10.5); rs.font.color.rgb = ACCENT; rs.bold = True
    c = doc.add_paragraph(); c.alignment = WD_ALIGN_PARAGRAPH.CENTER
    c.add_run(CONTACT).font.size = Pt(9)

    def section(title):
        p = doc.add_paragraph(); p.space_before = Pt(6)
        run = p.add_run("▍" + title); run.bold = True; run.font.size = Pt(12.5); run.font.color.rgb = ACCENT
        pPr = p._p.get_or_add_pPr(); pbdr = pPr.makeelement(qn('w:pBdr'), {})
        bottom = pbdr.makeelement(qn('w:bottom'), {qn('w:val'):'single', qn('w:sz'):'6', qn('w:space'):'2', qn('w:color'):'1F4E79'})
        pbdr.append(bottom); pPr.insert(0, pbdr)  # pBdr 必须排在 spacing 之前，否则 Word 报"修复"

    for sec in SECTIONS:
        section(sec["title"])
        for blk in sec["blocks"]:
            if blk.get("head"):
                h = doc.add_paragraph(); h.space_before = Pt(3)
                rh = h.add_run(blk["head"]); rh.bold = True; rh.font.size = Pt(10.5)
            for ln in blk["lines"]:
                p = doc.add_paragraph(); p.paragraph_format.left_indent = Inches(0.12)
                p.paragraph_format.space_after = Pt(2)
                add_runs(p, "• " + ln)
    # 个人总结置于教育前？这里放末尾之前不合适，改为文档开头注释由用户选择，故省略单独块
    doc.save(path)
    print("docx ->", path)


# ---------------------------------------------------------------------------
# 渲染：.pdf (reportlab, 内置 CJK 字体)
# ---------------------------------------------------------------------------
def build_pdf(path):
    from reportlab.lib.pagesizes import A4
    from reportlab.lib.units import mm
    from reportlab.lib.colors import HexColor
    from reportlab.lib.enums import TA_CENTER
    from reportlab.pdfbase import pdfmetrics
    from reportlab.pdfbase.cidfonts import UnicodeCIDFont
    from reportlab.platypus import (SimpleDocTemplate, Paragraph, Spacer, Table,
                                    TableStyle)
    import re

    pdfmetrics.registerFont(UnicodeCIDFont("STSong-Light"))
    FONT = "STSong-Light"
    ACCENT = HexColor("#1F4E79")

    doc = SimpleDocTemplate(path, pagesize=A4,
                            leftMargin=16*mm, rightMargin=16*mm,
                            topMargin=14*mm, bottomMargin=14*mm,
                            title="杨玲琳-产品经理简历")
    story = []

    from reportlab.lib.styles import ParagraphStyle
    def P(text, size=9.5, bold=False, color=None, align=None, space=2, lead=None):
        st = ParagraphStyle("s", fontName=FONT, fontSize=size,
                            leading=lead or size + 3, spaceAfter=space)
        if color: st.textColor = color
        if align is not None: st.alignment = align
        # 行内加粗
        if "**" in text:
            parts = re.split(r"(\*\*.*?\*\*)", text)
            html = "".join(
                f"<b>{p[2:-2]}</b>" if (p.startswith("**") and p.endswith("**")) else p
                for p in parts)
            return Paragraph(html, st)
        return Paragraph(text, st)

    # 标题 + 求职意向 + 联系方式
    story.append(P(NAME_LINE, size=20, bold=True, color=ACCENT, align=TA_CENTER, space=1))
    story.append(P(INTENT, size=10.5, bold=True, color=ACCENT, align=TA_CENTER, space=1))
    story.append(P(CONTACT, size=8.5, align=TA_CENTER, space=4))
    # 个人总结
    story.append(P("▍个人总结", size=12, bold=True, color=ACCENT, space=2))
    story.append(P(SUMMARY, size=9.5, space=4))

    for sec in SECTIONS:
        story.append(P("▍" + sec["title"], size=12, bold=True, color=ACCENT, space=2))
        for blk in sec["blocks"]:
            if blk.get("head"):
                story.append(P(blk["head"], size=10, bold=True, space=2))
            for ln in blk["lines"]:
                story.append(P("•  " + ln, size=9.3, space=2))

    def footer(canvas, d):
        canvas.saveState()
        canvas.setFont(FONT, 7.5)
        canvas.setFillColor(HexColor("#888888"))
        canvas.drawCentredString(A4[0]/2, 8*mm, "杨玲琳 ｜ 产品经理（AI/技术产品方向）简历")
        canvas.restoreState()

    doc.build(story, onFirstPage=footer, onLaterPages=footer)
    print("pdf  ->", path)


if __name__ == "__main__":
    os.makedirs(OUT_DIR, exist_ok=True)
    build_docx(os.path.join(OUT_DIR, NAME + ".docx"))
    build_pdf(os.path.join(OUT_DIR, NAME + ".pdf"))
