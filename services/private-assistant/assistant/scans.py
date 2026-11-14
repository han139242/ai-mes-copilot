"""Build OCR requests and preserve original page numbers across extraction."""
import io
import os
import shutil
import subprocess
import time
from pathlib import Path
from tempfile import TemporaryDirectory

from .parsing import open_pdf


MAX_SCAN_PAGES = 24
IMAGE_TYPES = {".png": ("PNG", "image/png"), ".jpg": ("JPEG", "image/jpeg"),
               ".jpeg": ("JPEG", "image/jpeg")}


def inspect_scan(name, content, limit, *, read_text=True):
    if not content or len(content) > limit:
        raise ValueError("请选择不超过 8 MB 的扫描件")
    suffix = Path(name).suffix.lower()
    try:
        if suffix == ".pdf":
            pdf = open_pdf(content)
            native = ({i: (page.extract_text() or "").strip() for i, page in enumerate(pdf.pages, 1)}
                      if read_text else {})
            return pdf, native, "application/pdf"
        if suffix in IMAGE_TYPES:
            from PIL import Image

            expected, media_type = IMAGE_TYPES[suffix]
            with Image.open(io.BytesIO(content)) as image:
                if image.format != expected or image.width * image.height > 24_000_000 or image.n_frames != 1:
                    raise ValueError("请选择单张、像素不超过 2400 万的 PNG 或 JPEG")
                image.verify()
            return None, {1: ""}, media_type
    except ValueError:
        raise
    except Exception:
        raise ValueError("扫描件无法解析，请检查文件格式和完整性") from None
    raise ValueError("扫描件识别支持 PDF、PNG 和 JPEG")


def pdf_renderer():
    executable = shutil.which("pdftoppm")
    if not executable:
        raise ValueError("本机缺少 pdftoppm，可先将扫描页保存为 PNG 或 JPEG 后识别")
    return executable


def render_pdf_pages(content, pages):
    """Send only selected page pixels, with no copied PDF objects or metadata."""
    executable = pdf_renderer()
    with TemporaryDirectory(prefix="mes-scan-") as scratch:
        source = Path(scratch) / "source.pdf"
        source.write_bytes(content)
        deadline = time.monotonic() + 45
        for page in pages:
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                raise ValueError("本机渲染扫描页超时，请减少选中页")
            try:
                result = subprocess.run(
                    [executable, "-f", str(page), "-l", str(page), "-singlefile",
                     "-scale-to", "2200", "-hide-annotations", "-png", str(source)],
                    capture_output=True, timeout=min(remaining, 5), check=True,
                    creationflags=subprocess.CREATE_NO_WINDOW if os.name == "nt" else 0,
                )
            except (OSError, subprocess.SubprocessError):
                raise ValueError(f"本机无法渲染第 {page} 页，请试用清晰的扫描图片") from None
            if not result.stdout.startswith(b"\x89PNG\r\n\x1a\n"):
                raise ValueError(f"本机未取得第 {page} 页的图像")
            yield result.stdout


def image_for_ocr(content):
    from PIL import Image, ImageOps

    with Image.open(io.BytesIO(content)) as image:
        clean = ImageOps.exif_transpose(image).convert("RGB")
        clean.thumbnail((2200, 2200))
        clean.info.clear()
        return clean


def scan_payload(content, pages, is_pdf, limit):
    buffer = io.BytesIO()
    if not is_pdf:
        with image_for_ocr(content) as image:
            image.save(buffer, "JPEG", quality=94)
        if buffer.tell() > limit:
            raise ValueError("渲染后的扫描页超过 8 MB，请减少选中页")
        return buffer.getvalue(), "scan.jpg", "image/jpeg"
    from pypdf import PdfReader, PdfWriter

    writer = PdfWriter()
    size = 0
    for rendered in render_pdf_pages(content, pages):
        page = io.BytesIO()
        with image_for_ocr(rendered) as image:
            image.save(page, "PDF", quality=88)
        size += page.tell()
        if size > limit:
            raise ValueError("渲染后的扫描页超过 8 MB，请减少选中页")
        writer.add_page(PdfReader(page).pages[0])
    writer.write(buffer)
    if buffer.tell() > limit:
        raise ValueError("渲染后的扫描页超过 8 MB，请减少选中页")
    return buffer.getvalue(), "scan.pdf", "application/pdf"


def selected_pages(native, selected):
    if (not isinstance(selected, list) or not 1 <= len(selected) <= MAX_SCAN_PAGES
            or any(type(page) is not int or page not in native for page in selected)
            or len(set(selected)) != len(selected)):
        raise ValueError("请选择 1～24 个不同的有效页码")
    return sorted(selected)


def reviewed_pieces(native, pages, edited, max_chars, previous_pieces=()):
    if not isinstance(edited, list) or len(edited) != len(pages):
        raise ValueError("识别预览页码已变化，请重新打开识别结果")
    corrections = {}
    for entry in edited:
        if (not isinstance(entry, dict) or type(entry.get("page")) is not int
                or entry["page"] not in pages or entry["page"] in corrections or not isinstance(entry.get("text"), str)):
            raise ValueError("识别预览页码或文本无效")
        corrections[entry["page"]] = entry["text"].replace("\x00", "").strip()
    retained = {item["page"]: item for item in previous_pieces if item.get("method") == "deepseek-ocr"}
    pieces = []
    for page, native_text in native.items():
        if page not in corrections and page in retained:
            pieces.append(retained[page])
            continue
        text = corrections.get(page, native_text)
        if text:
            item = {"locator": f"第 {page} 页", "text": text}
            if page in corrections:
                item.update(method="deepseek-ocr", page=page)
            pieces.append(item)
    if not pieces or not any(item.get("method") == "deepseek-ocr" for item in pieces):
        raise ValueError("请至少核对并保留一页识别文字")
    if sum(len(item["text"]) for item in pieces) > max_chars:
        raise ValueError("资料文字超过 24 万字符，请拆分后上传")
    return pieces
