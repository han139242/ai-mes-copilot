"""Build the ten-file synthetic showcase without touching MES data or a model API.

DOCX/PDF use python-docx/reportlab. The optional XLSX builder uses the supplied
artifact-tool runtime. Without that runtime the BOM remains an honest Markdown
file; it is never renamed to masquerade as an Excel workbook.
"""
import argparse
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import sys

SOURCE = Path(__file__).resolve().parents[1] / 'examples' / 'lamp-showcase'


def paragraphs(path):
    return [line.strip() for line in path.read_text(encoding='utf-8').splitlines() if line.strip()]


def word(source, target):
    from datetime import datetime, timezone
    from docx import Document
    from docx.oxml import OxmlElement
    from docx.oxml.ns import qn
    from docx.shared import Cm, Pt, RGBColor
    doc = Document()
    section = doc.sections[0]
    section.page_width, section.page_height = Cm(21), Cm(29.7)
    section.top_margin = section.bottom_margin = Cm(2)
    section.left_margin = section.right_margin = Cm(2.2)
    for name, size in [('Normal', 11), ('Title', 20), ('Heading 1', 13)]:
        style = doc.styles[name]
        style.font.name = 'SimSun'
        style.font.size = Pt(size)
        style.font.color.rgb = RGBColor.from_string('182F3D') if name != 'Title' else RGBColor(0, 0, 0)
        style.element.get_or_add_rPr().rFonts.set(qn('w:eastAsia'), '宋体')
        style.paragraph_format.space_after = Pt(9)
        style.paragraph_format.line_spacing = 1.25
    for line in paragraphs(source):
        if line.startswith('# '): doc.add_paragraph(line[2:], 'Title')
        elif line.startswith('## '): doc.add_paragraph(line[3:], 'Heading 1')
        else: doc.add_paragraph(line)
    for tree in (doc.styles.element, doc.element):
        for border in tree.xpath('.//w:pBdr'): border.getparent().remove(border)
    props = doc.core_properties
    props.author = props.last_modified_by = 'AI-MES Copilot contributors'
    props.title = '调光台灯装配操作指引'
    props.created = props.modified = datetime(2026, 11, 9, tzinfo=timezone.utc)
    footer = section.footer.paragraphs[0]
    footer.add_run('合成教学样例  |  ')
    field = OxmlElement('w:fldSimple'); field.set(qn('w:instr'), 'PAGE'); footer._p.append(field)
    doc.save(target)


def pdf(source, target, font):
    from reportlab.lib import colors
    from reportlab.lib.enums import TA_LEFT
    from reportlab.lib.pagesizes import A4
    from reportlab.lib.styles import ParagraphStyle
    from reportlab.pdfbase import pdfmetrics
    from reportlab.pdfbase.ttfonts import TTFont
    from reportlab.platypus import SimpleDocTemplate, Paragraph, Spacer
    from xml.sax.saxutils import escape
    pdfmetrics.registerFont(TTFont('ShowcaseCJK', str(font)))
    styles = {
        'title': ParagraphStyle('title', fontName='ShowcaseCJK', fontSize=20, leading=28, spaceAfter=18),
        'heading': ParagraphStyle('heading', fontName='ShowcaseCJK', fontSize=13, leading=21,
                                  spaceBefore=12, spaceAfter=8, textColor=colors.HexColor('#176FA7')),
        'body': ParagraphStyle('body', fontName='ShowcaseCJK', fontSize=11, leading=20,
                               spaceAfter=10, wordWrap='CJK', alignment=TA_LEFT),
    }
    story = []
    for line in paragraphs(source):
        kind, text = ('title', line[2:]) if line.startswith('# ') else (
            ('heading', line[3:]) if line.startswith('## ') else ('body', line))
        story.append(Paragraph(escape(text), styles[kind]))
    def footer(canvas, document):
        canvas.setFont('ShowcaseCJK', 9)
        canvas.setFillColor(colors.HexColor('#596874'))
        canvas.drawString(54, 32, '合成教学样例 - 非正式标准')
        canvas.drawRightString(A4[0]-54, 32, str(document.page))
    doc = SimpleDocTemplate(str(target), pagesize=A4, leftMargin=54, rightMargin=54,
        topMargin=48, bottomMargin=50, title='调光检验教学规范', author='AI-MES Copilot contributors')
    doc.build(story, onFirstPage=footer, onLaterPages=footer)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--font', type=Path, default=Path('C:/Windows/Fonts/simsun.ttc'))
    parser.add_argument('--node', type=Path)
    parser.add_argument('--packages', type=Path)
    args = parser.parse_args()
    output = args.output.resolve()
    if output == SOURCE.resolve() or output.is_relative_to(SOURCE.resolve()):
        parser.error('Output must be outside the source corpus')
    if output.exists() and any(output.iterdir()):
        parser.error('Output is not empty; use a fresh directory to preserve existing files')
    if not args.font.is_file(): parser.error('Select an existing Chinese TrueType font with --font')
    if bool(args.node) != bool(args.packages): parser.error('--node and --packages must be provided together')
    output.mkdir(parents=True, exist_ok=True)
    uploads = output / '上传这10份'; uploads.mkdir()
    native = {'03-装配操作指引': '.docx', '04-调光检验规范': '.pdf', '06-设备点检说明': '.txt'}
    if args.node: native['02-产品物料清单'] = '.xlsx'
    manifest = []
    for source in sorted(SOURCE.glob('[0-9][0-9]-*.md')):
        suffix = native.get(source.stem, '.md'); target = uploads / (source.stem + suffix)
        if suffix == '.docx': word(source, target)
        elif suffix == '.pdf': pdf(source, target, args.font)
        elif suffix == '.txt': target.write_text(source.read_text(encoding='utf-8'), encoding='utf-8')
        elif suffix == '.xlsx':
            subprocess.run([str(args.node), str(Path(__file__).with_name('build_showcase_sheet.mjs')),
                str(source), str(target), str(args.packages), str(output / 'BOM-preview.png')], check=True)
        else: shutil.copyfile(source, target)
        manifest.append({'name': target.name, 'sha256': hashlib.sha256(target.read_bytes()).hexdigest(),
                         'source': source.name, 'sourceSha256': hashlib.sha256(source.read_bytes()).hexdigest()})
    assert len(manifest) == 10
    (output / 'manifest.json').write_text(json.dumps({'synthetic': True, 'files': manifest},
        ensure_ascii=False, indent=2), encoding='utf-8')
    print(json.dumps({'files': len(manifest), 'formats': sorted({Path(r['name']).suffix for r in manifest}),
                      'output': str(output)}, ensure_ascii=False))


if __name__ == '__main__': main()
