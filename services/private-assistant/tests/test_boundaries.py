import base64
import hashlib
import hmac
import io
import sqlite3
import time
import zipfile
from concurrent.futures import ThreadPoolExecutor

import pytest

from assistant.config import Settings
from assistant.database import Databases
from assistant.parsing import parse_file
from assistant.security import Journal


def signature(secret, body, timestamp, nonce):
    value = f"POST\n/v1/dispatch\n{timestamp}\n{nonce}\n{hashlib.sha256(body).hexdigest()}"
    return hmac.new(secret.encode(), value.encode(), hashlib.sha256).hexdigest()


def test_signed_context_cannot_be_replayed_or_changed(tmp_path):
    journal = Journal(tmp_path/"journal.sqlite3")
    secret, body, nonce, timestamp = "x"*32, b'{"uid":1}', "a"*32, str(int(time.time()))
    signed = signature(secret, body, timestamp, nonce)
    journal.authenticate(body, timestamp, nonce, signed, secret)
    with pytest.raises(PermissionError, match="Replayed"):
        journal.authenticate(body, timestamp, nonce, signed, secret)
    with pytest.raises(PermissionError):
        journal.authenticate(b'{"uid":2}', timestamp, nonce, signed, secret)
    with pytest.raises(PermissionError, match="Expired"):
        journal.authenticate(body, str(int(timestamp)-31), "b"*32, signed, secret)


def test_durable_budget_is_atomic_and_counts_failed_attempts(tmp_path):
    path = tmp_path/"budget.sqlite3"
    def attempt(_):
        journal = Journal(path)
        try:
            number = journal.reserve("synthetic-test", 20)
            journal.finish(number, "failed")
            return True
        except RuntimeError:
            return False
    Journal(path)
    with ThreadPoolExecutor(max_workers=8) as executor:
        assert sum(executor.map(attempt, range(40))) == 20
    assert Journal(path).status(20)["remaining"] == 0
    with pytest.raises(RuntimeError):
        Journal(path).reserve("after-restart", 20)


def test_disabling_optional_cap_preserves_existing_calls_and_continues_accounting(tmp_path):
    journal=Journal(tmp_path/'journal.sqlite3')
    original=journal.reserve('before-user-removed-cap',1)
    journal.finish(original,'failed')
    with pytest.raises(RuntimeError):journal.reserve('still-capped',1)
    for _ in range(25):journal.finish(journal.reserve('user-authorized-no-cap',0),'success')
    status=Journal(journal.path).status(0)
    assert status['used']==26 and status['limit'] is None and status['remaining'] is None and status['unlimited']
    assert status['calls'][0]['id']==original and status['calls'][0]['state']=='failed'
    compact=journal.status(0,include_calls=False)
    assert 'calls' not in compact and compact['used']==26
    with pytest.raises(ValueError):journal.reserve('invalid-config',-1)


def test_unlimited_requests_remain_atomic_under_concurrency(tmp_path):
    path=tmp_path/'journal.sqlite3';Journal(path)
    with ThreadPoolExecutor(max_workers=8) as executor:
        ids=list(executor.map(lambda n:Journal(path).reserve('synthetic-concurrent',0),range(40)))
    assert len(set(ids))==40 and Journal(path).status(0,include_calls=False)['used']==40


@pytest.mark.parametrize("uid", [0, -1, "1;DROP DATABASE postgres", True, None])
def test_user_ids_are_server_integers(tmp_path, uid):
    settings = Settings(root=tmp_path, secret="x"*32)
    with pytest.raises(ValueError):
        Databases(settings).identity(uid)


def test_private_users_have_different_database_role_and_password(tmp_path):
    settings = Settings(root=tmp_path, secret="x"*32)
    a, b = Databases(settings).identity(1), Databases(settings).identity(2)
    assert all(x != y for x, y in zip(a, b))
    assert settings.user_dir(1) != settings.user_dir(2)


def test_plaintext_locator_and_rejection():
    assert parse_file("instructions.md", "安全\n电压 12 V".encode())[1] == {"locator": "行 2", "text": "电压 12 V"}
    with pytest.raises(ValueError):
        parse_file("payload.exe", b"abc")
    with pytest.raises(ValueError):
        parse_file("empty.txt", b" \n")
    with pytest.raises(ValueError):
        parse_file("large.txt", b"a"*100, max_bytes=99)


def test_office_decompression_limit():
    blob = io.BytesIO()
    with zipfile.ZipFile(blob, "w", zipfile.ZIP_DEFLATED) as archive:
        archive.writestr("large.xml", b"x"*(41*1024*1024))
    with pytest.raises(ValueError, match="解压"):
        parse_file("bomb.docx", blob.getvalue())


def test_docx_and_xlsx_have_real_coordinates():
    from docx import Document
    from openpyxl import Workbook
    doc = Document(); doc.add_paragraph("台灯装配后执行通电测试")
    blob = io.BytesIO(); doc.save(blob)
    assert parse_file("test.docx", blob.getvalue())[0]["locator"] == "段落 1"
    book = Workbook(); book.active.title = "标准"; book.active.append(["电压", 12])
    blob = io.BytesIO(); book.save(blob)
    result = parse_file("test.xlsx", blob.getvalue())
    assert result[0] == {"locator": "标准!A1:B1", "text": "A1=电压 | B1=12"}
    book.active['C2'] = '仅 C 列有值'
    blob = io.BytesIO(); book.save(blob)
    assert parse_file("test.xlsx", blob.getvalue())[1]["locator"] == "标准!C2"


def test_long_paragraph_continuations_keep_their_locator():
    import json
    from assistant.chunking import located_chunks
    class Tokenizer:
        def encode(self, text): return list(text)
        def decode(self, tokens): return ''.join(tokens)
    result = located_chunks(Tokenizer(), json.dumps([{"locator":"段落 12","text":"台灯装配"*500}]))
    assert len(result) > 3
    assert all('段落 12' in chunk['content'] for chunk in result)
