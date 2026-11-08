import io
import zipfile
from pathlib import Path

SUPPORTED = {".pdf", ".docx", ".xlsx", ".txt", ".md"}


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
        from pypdf import PdfReader
        reader = PdfReader(io.BytesIO(content), strict=True)
        if reader.is_encrypted or len(reader.pages) > 300:
            raise ValueError("请上传未加密且不超过 300 页的 PDF")
        for n, page in enumerate(reader.pages, 1):
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
        book = load_workbook(io.BytesIO(content), read_only=True, data_only=True)
        try:
            if len(book.worksheets) > 30:
                raise ValueError("工作表过多")
            for sheet in book:
                if sheet.max_row > 10000 or sheet.max_column > 100:
                    raise ValueError("工作表超过 10000 行或 100 列")
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
