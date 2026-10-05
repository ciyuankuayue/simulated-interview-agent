#!/usr/bin/env python3
# 一次性工具：把 Word .docx（知识框架）转成纯文本 Markdown，用于 RAG 语料。
# 仅依赖 Python 标准库（docx 本质是 zip+xml），不需要 pip 安装任何包。
import os
import sys
import zipfile
import xml.etree.ElementTree as ET

W = '{http://schemas.openxmlformats.org/wordprocessingml/2006/main}'

# 项目根目录（本脚本位于 <root>/scripts/，父目录即项目根）
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
# 输出：相对项目根，队友 clone 后可直接生成语料，无需改路径
OUT = os.path.join(ROOT, "src", "test", "resources", "corpus", "java-backend-knowledge.md")
# 输入 .docx 由命令行参数指定：python convert_docx_to_md.py <路径.docx>
DOCX = None


def get_text(p):
    """提取一个 w:p 节点里的全部文本（含 run / 软换行 / tab）。"""
    parts = []
    for node in p.iter():
        tag = node.tag
        if tag == W + 't':
            parts.append(node.text or '')
        elif tag == W + 'tab':
            parts.append('\t')
        elif tag == W + 'br':
            parts.append('\n')
    return ''.join(parts)


def style_of(p):
    ppr = p.find(W + 'pPr')
    if ppr is None:
        return None
    ps = ppr.find(W + 'pStyle')
    if ps is None:
        return None
    return ps.get(W + 'val')


def heading_level(style):
    """把 Word 样式名映射成 markdown 标题级别（1-6），非标题返回 0。"""
    if style is None:
        return 0
    if style == 'Title':
        return 1
    # 英文：Heading1..Heading9
    if style.startswith('Heading'):
        digits = ''.join(ch for ch in style if ch.isdigit())
        if digits:
            return min(int(digits), 6)
    # 中文 Word 常见：'1'..'6' 或 '标题1'..'标题6'
    if style.isdigit():
        return min(int(style), 6)
    if style.startswith('标题'):
        digits = ''.join(ch for ch in style if ch.isdigit())
        if digits:
            return min(int(digits), 6)
    # 大纲级别兜底
    if style.startswith('Outline'):
        digits = ''.join(ch for ch in style if ch.isdigit())
        if digits:
            return min(int(digits), 6)
    return 0


def cell_text(tc):
    return ''.join(get_text(p) for p in tc.iter(W + 'p')).strip()


def main():
    if len(sys.argv) < 2:
        print("用法: python convert_docx_to_md.py <输入.docx 路径>")
        sys.exit(1)
    DOCX = sys.argv[1]
    if not os.path.exists(DOCX):
        print("ERROR: docx 不存在:", DOCX)
        sys.exit(1)

    with zipfile.ZipFile(DOCX) as z:
        xml_bytes = z.read('word/document.xml')
    root = ET.fromstring(xml_bytes)
    body = root.find(W + 'body')
    if body is None:
        print("ERROR: 找不到 word/document.xml 的 body")
        sys.exit(1)

    lines = []
    for child in body:
        if child.tag == W + 'p':
            style = style_of(child)
            text = get_text(child).strip()
            if not text:
                lines.append('')
                continue
            lvl = heading_level(style)
            if lvl > 0:
                lines.append('#' * lvl + ' ' + text)
            else:
                lines.append(text)
        elif child.tag == W + 'tbl':
            rows = []
            for tr in child.findall(W + 'tr'):
                cells = [cell_text(tc) for tc in tr.findall(W + 'tc')]
                rows.append(cells)
            if not rows:
                continue
            ncol = max(len(r) for r in rows)
            # 表头
            header = rows[0] + [''] * (ncol - len(rows[0]))
            lines.append('| ' + ' | '.join(header) + ' |')
            lines.append('| ' + ' | '.join(['---'] * ncol) + ' |')
            for r in rows[1:]:
                r = r + [''] * (ncol - len(r))
                lines.append('| ' + ' | '.join(r) + ' |')
            lines.append('')

    md = '\n'.join(lines).strip() + '\n'
    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    with open(OUT, 'w', encoding='utf-8') as f:
        f.write(md)

    non_empty = [l for l in lines if l.strip()]
    print(f"OK  wrote: {OUT}")
    print(f"    chars={len(md)}  total_lines={len(lines)}  non_empty_lines={len(non_empty)}")


if __name__ == '__main__':
    main()
