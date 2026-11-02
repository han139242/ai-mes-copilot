"""将仓库正式说明书索引到独立台灯库；默认只预览，重复导入不新增重复章节。"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import zipfile
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
NS = {'w': 'http://schemas.openxmlformats.org/wordprocessingml/2006/main'}
W = '{' + NS['w'] + '}'
SOURCES = [
    ('REQ', '1.智能调光台灯MES软件需求规格说明书.docx'),
    ('DES', '2.智能调光台灯MES系统设计说明书.docx'),
]


def sections(path):
    with zipfile.ZipFile(path) as archive:
        styles = ET.fromstring(archive.read('word/styles.xml'))
        levels = {}
        for style in styles.findall('w:style', NS):
            level = style.find('w:pPr/w:outlineLvl', NS)
            if level is not None:
                levels[style.get(W + 'styleId')] = int(level.get(W + 'val')) + 1
        document = ET.fromstring(archive.read('word/document.xml'))
    result = []
    title, level, text = '', 1, []
    for p in document.findall('.//w:body//w:p', NS):
        value = ''.join(t.text or '' for t in p.findall('.//w:t', NS)).strip()
        if not value:
            continue
        style = p.find('w:pPr/w:pStyle', NS)
        name = '' if style is None else style.get(W + 'val', '')
        if name.lower().startswith('toc'):
            continue
        heading = levels.get(name, 0)
        if heading and heading <= 4 and len(value) <= 250:
            if title and text:
                result.append((title, level, '\n'.join(text)))
            title, level, text = value, heading, []
        elif title:
            text.append(value)
    if title and text:
        result.append((title, level, '\n'.join(text)))
    if len(result) < 10:
        raise ValueError(f'{path.name} 有效章节过少，停止导入以检查标题样式')
    return result


def sql_text(value):
    return 'CONVERT(0x' + str(value).encode('utf-8').hex() + ' USING utf8mb4)'


def main():
    sys.stdout.reconfigure(encoding='utf-8')
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--apply', action='store_true')
    parser.add_argument('--database', required=True)
    parser.add_argument('--port', type=int, choices=[3307, 3310, 3311], required=True)
    parser.add_argument('--mysql', default='mysql')
    parser.add_argument('--user', default=os.environ.get('DB_USERNAME', 'root'))
    args = parser.parse_args()
    if not re.fullmatch(r'mes_lamp_[A-Za-z0-9_]+', args.database):
        parser.error('只允许独立台灯数据库')
    statements, report = ['START TRANSACTION;'], []
    for code, name in SOURCES:
        path = ROOT / '相关文档，图片/does/智能台灯报告' / name
        rows = sections(path)
        digest = hashlib.sha256(path.read_bytes()).hexdigest()
        report.append({'source': name, 'sha256': digest, 'sections': len(rows),
                       'characters': sum(len(row[2]) for row in rows)})
        for seq, (title, level, content) in enumerate(rows, 1):
            where = f'doc_code={sql_text(code)} AND title={sql_text(title)} AND deleted_flag=0'
            # 版本出处属于正文，提醒知识问答区分设计说明与已运行事实。
            body = f'{content}\n\n来源：{name}；文件SHA256：{digest}。本文为说明书内容，实际状态和办理条件须查询业务数据。'
            statements.append('UPDATE ai_knowledge_section SET '
                              f'content={sql_text(body)},seq_no={seq},level_no={level},updated_time=NOW() WHERE {where};')
            statements.append('INSERT INTO ai_knowledge_section '
                              '(doc_code,doc_name,seq_no,level_no,title,content,created_time,deleted_flag) '
                              f'SELECT {sql_text(code)},{sql_text(name)},{seq},{level},{sql_text(title)},{sql_text(body)},NOW(),0 '
                              f'WHERE NOT EXISTS(SELECT 1 FROM ai_knowledge_section WHERE {where});')
    statements.append('COMMIT;')
    statements.append('SELECT doc_code,COUNT(*),SUM(CHAR_LENGTH(content)>0) FROM ai_knowledge_section WHERE deleted_flag=0 GROUP BY doc_code;')
    print(json.dumps({'apply': args.apply, 'database': args.database, 'sources': report}, ensure_ascii=False))
    if not args.apply:
        return
    env = dict(os.environ)
    if 'DB_PASSWORD' in env:
        env['MYSQL_PWD'] = env['DB_PASSWORD']
    command = [args.mysql, '--no-defaults', '--host=127.0.0.1', f'--port={args.port}',
               f'--user={args.user}', f'--database={args.database}', '--default-character-set=utf8mb4', '--batch']
    result = subprocess.run(command, input='\n'.join(statements).encode('utf-8'), capture_output=True, env=env, timeout=60)
    if result.returncode:
        raise RuntimeError(result.stderr.decode('utf-8', 'replace'))
    print(result.stdout.decode('utf-8', 'replace'))


if __name__ == '__main__':
    main()
