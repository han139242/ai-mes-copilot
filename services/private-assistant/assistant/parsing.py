import io
import re
import zipfile
from pathlib import Path
from xml.etree import ElementTree
from itertools import chain

SUPPORTED = {".pdf", ".docx", ".xlsx", ".txt", ".md"}


def open_pdf(content):
    from pypdf import PdfReader

    reader = PdfReader(io.BytesIO(content), strict=True)
    if reader.is_encrypted or len(reader.pages) > 300:
        raise ValueError("请上传未加密且不超过 300 页的 PDF")
    return reader


def validate_sheet_coordinates(content):
    """Bound actual cells before read-only Excel can allocate sparse rows/columns.

    Some legitimate exporters omit dimensions; others declare stale dimensions.
    Check real coordinates rather than trusting that optional metadata.
    """
    with zipfile.ZipFile(io.BytesIO(content)) as archive:
        for name in archive.namelist():
            # OPC parts need not have a .xml extension. Inspect actual roots,
            # without guessing extensions, fixed paths, or a byte-prefix length.
            row_number = column = 0
            with archive.open(name) as stream:
                events=ElementTree.iterparse(stream, events=('start', 'end'))
                try:first=next(events)
                except (ElementTree.ParseError,StopIteration):continue
                if first[1].tag.rsplit('}',1)[-1]!='worksheet':continue
                for event, element in chain([first],events):
                    tag = element.tag.rsplit('}', 1)[-1]
                    if event == 'start' and tag == 'row':
                        row_number = int(element.get('r', row_number + 1)); column = 0
                        if not 1 <= row_number <= 10000:
                            raise ValueError('工作表超过 10000 行或 100 列')
                    elif event == 'start' and tag == 'c':
                        reference = element.get('r')
                        if reference:
                            match = re.fullmatch(r'([A-Za-z]{1,3})([1-9][0-9]{0,6})', reference)
                            if not match: raise ValueError('工作表单元格坐标无效')
                            column = 0
                            for letter in match[1].upper(): column = column * 26 + ord(letter) - 64
                            cell_row = int(match[2])
                        else:
                            column += 1; cell_row = row_number
                        if column > 100 or cell_row > 10000:
                            raise ValueError('工作表超过 10000 行或 100 列')
                    elif event == 'end':
                        element.clear()


def parse_file(name: str, content: bytes, max_bytes=8*1024*1024, max_chars=240_000):
    """Extract actual page/paragraph/sheet coordinates; never execute document content."""
    suffix = Path(name).suffix.lower()
    if suffix not in SUPPORTED:
        raise ValueError("支持 PDF、DOCX、XLSX、TXT 和 Markdown")
    if not content or len(content) > max_bytes:
        raise ValueError("文件为空或超过 8 MB")
    if suffix in {".docx", ".xlsx"}:
        with zipfile.ZipFile(io.BytesIO(content)) as archive:
            infos = archive.infolist()
            if len(infos) > 2000 or sum(i.file_size for i in infos) > 40*1024*1024:
                raise ValueError("文档解压后过大")
    pieces = []
    if suffix == ".pdf":
        for n, page in enumerate(open_pdf(content).pages, 1):
            pieces.append((f"第 {n} 页", page.extract_text() or ""))
    elif suffix == ".docx":
        from docx import Document
        doc = Document(io.BytesIO(content))
        pieces.extend((f"段落 {n}", p.text) for n, p in enumerate(doc.paragraphs, 1))
        for n, table in enumerate(doc.tables, 1):
            pieces.extend((f"表 {n} 行 {r}", " | ".join(c.text for c in row.cells))
                          for r, row in enumerate(table.rows, 1))
    elif suffix == ".xlsx":
        from openpyxl import load_workbook
        validate_sheet_coordinates(content)
        book = load_workbook(io.BytesIO(content), read_only=True, data_only=True)
        try:
            if len(book.worksheets) > 30:
                raise ValueError("工作表过多")
            for sheet in book:
                # Actual coordinates were bounded above; don't truncate to stale metadata.
                sheet.reset_dimensions()
                for row in sheet.iter_rows():
                    cells = [cell for cell in row if cell.value is not None]
                    if cells:
                        location = cells[0].coordinate if len(cells)==1 else f"{cells[0].coordinate}:{cells[-1].coordinate}"
                        pieces.append((f"{sheet.title}!{location}",
                                       " | ".join(f"{cell.coordinate}={cell.value}" for cell in cells)))
        finally:
            book.close()
    else:
        text = content.decode("utf-8-sig", errors="strict")
        pieces.extend((f"行 {n}", line) for n, line in enumerate(text.splitlines(), 1))
    if sum(len(text) for _, text in pieces) > max_chars:
        raise ValueError("提取文本超过 24 万字符，请分批上传")
    pieces = [{"locator": loc, "text": text.strip()} for loc, text in pieces if text.strip()]
    if not pieces:
        raise ValueError("没有可提取的文字；扫描件请先 OCR 后上传")
    return pieces
