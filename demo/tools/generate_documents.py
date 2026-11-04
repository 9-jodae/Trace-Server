#!/usr/bin/env python3
"""모아온 시연 문서·매니페스트 생성. PDF에는 OFL 나눔고딕을 임베드한다."""

import json
from pathlib import Path

from catalog import COMPANY, DOCUMENTS


ROOT = Path(__file__).resolve().parents[2]
FONT = Path(__file__).parent / "fonts" / "NanumGothic-Regular.ttf"


def markdown(document):
    lines = [f"# {document['title']}", "", f"- 회사: {COMPANY}",
             f"- 문서 번호: {document['logicalId']} / v{document['version']}",
             f"- 작성 부서: {document['ownerDepartment']}",
             f"- 작성자: {document['author']}", f"- 작성일: {document['authoredAt']}", ""]
    for heading, body in document["sections"]:
        lines += [f"## {heading}", ""]
        if " | " in body:
            rows = [row.split(" | ") for row in body.splitlines()]
            lines += ["| " + " | ".join(rows[0]) + " |",
                      "| " + " | ".join("---" for _ in rows[0]) + " |"]
            lines += ["| " + " | ".join(row) + " |" for row in rows[1:]]
        else:
            lines.append(body)
        lines.append("")
    return "\n".join(lines).rstrip() + "\n"


def pdf(document, destination):
    try:
        from reportlab import rl_config
        from reportlab.lib import colors
        from reportlab.lib.enums import TA_CENTER
        from reportlab.lib.pagesizes import A4
        from reportlab.lib.styles import ParagraphStyle
        from reportlab.lib.units import mm
        from reportlab.pdfbase import pdfmetrics
        from reportlab.pdfbase.ttfonts import TTFont
        from reportlab.platypus import SimpleDocTemplate, Paragraph, Spacer, Table, TableStyle
    except ImportError as error:
        raise SystemExit("PDF 생성에는 `python -m pip install -r demo/tools/requirements.txt`가 필요합니다.") from error

    rl_config.invariant = 1
    pdfmetrics.registerFont(TTFont("NanumGothic", str(FONT)))
    normal = ParagraphStyle("normal", fontName="NanumGothic", fontSize=10.5,
                            leading=17, wordWrap="CJK", spaceAfter=5 * mm)
    title = ParagraphStyle("title", parent=normal, fontSize=18, leading=26,
                           alignment=TA_CENTER, spaceAfter=9 * mm)
    heading_style = ParagraphStyle("heading", parent=normal, fontSize=12,
                                   leading=19, spaceBefore=4 * mm, spaceAfter=3 * mm)
    small = ParagraphStyle("small", parent=normal, fontSize=8.5, leading=13)
    story = [Paragraph(document["title"], title),
             Paragraph(f"{COMPANY} | {document['ownerDepartment']} | {document['author']} | {document['authoredAt']} | v{document['version']}", small),
             Spacer(1, 6 * mm)]
    for heading, body in document["sections"]:
        story.append(Paragraph(heading, heading_style))
        if " | " in body:
            cells = [[Paragraph(value, small) for value in row.split(" | ")]
                     for row in body.splitlines()]
            table = Table(cells, colWidths=[155 * mm / len(cells[0])] * len(cells[0]), repeatRows=1)
            table.setStyle(TableStyle([
                ("BACKGROUND", (0, 0), (-1, 0), colors.HexColor("#e7edf2")),
                ("GRID", (0, 0), (-1, -1), 0.35, colors.HexColor("#9aa9b5")),
                ("VALIGN", (0, 0), (-1, -1), "TOP"),
                ("LEFTPADDING", (0, 0), (-1, -1), 8),
                ("RIGHTPADDING", (0, 0), (-1, -1), 8),
                ("TOPPADDING", (0, 0), (-1, -1), 6),
                ("BOTTOMPADDING", (0, 0), (-1, -1), 6),
            ]))
            story.append(table)
        else:
            story.append(Paragraph(body, normal))
    doc = SimpleDocTemplate(str(destination), pagesize=A4, leftMargin=24 * mm,
                            rightMargin=24 * mm, topMargin=22 * mm, bottomMargin=22 * mm,
                            title=document["title"], author=document["author"])
    doc.build(story)


def main():
    if not FONT.is_file():
        raise SystemExit(f"폰트가 없습니다: {FONT}")
    seen = set()
    for document in DOCUMENTS:
        if document["id"] in seen:
            raise SystemExit(f"중복 문서 ID: {document['id']}")
        seen.add(document["id"])
        destination = ROOT / document["file"]
        destination.parent.mkdir(parents=True, exist_ok=True)
        if document["format"] == "PDF":
            pdf(document, destination)
        else:
            destination.write_text(markdown(document), encoding="utf-8")
    obsidian = ROOT / "demo/documents/obsidian/.obsidian/app.json"
    obsidian.parent.mkdir(parents=True, exist_ok=True)
    obsidian.write_text('{"showLineNumber": true, "strictLineBreaks": false}\n', encoding="utf-8")
    manifest = {"company": COMPANY, "schemaVersion": 1,
                "notes": "sourceKind는 의도한 출처이며 현재 API의 생성 kind와 다를 수 있다.",
                "documents": [{key: value for key, value in document.items() if key not in {"sections", "format"}}
                              for document in DOCUMENTS]}
    (ROOT / "demo/manifest.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"문서 {len(DOCUMENTS)}개와 매니페스트를 생성했습니다.")


if __name__ == "__main__":
    main()
