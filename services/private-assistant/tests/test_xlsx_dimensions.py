import io
import re
import zipfile

import pytest
from openpyxl import Workbook
from assistant.parsing import parse_file


def dimensionless(cell='A2'):
    book=Workbook();book.active['A1']='产品';book.active[cell]='L100 DRV-07'
    raw=io.BytesIO();book.save(raw);result=io.BytesIO()
    with zipfile.ZipFile(raw) as original,zipfile.ZipFile(result,'w') as output:
        for name in original.namelist():
            data=original.read(name)
            if name=='xl/worksheets/sheet1.xml': data=re.sub(rb'<dimension[^>]*/>',b'',data)
            output.writestr(name,data)
    return result.getvalue()


def test_valid_dimensionless_excel_keeps_exact_source_coordinates():
    pieces=parse_file('教学物料.xlsx',dimensionless('B2'))
    assert any(p['locator']=='Sheet!B2' and 'L100 DRV-07' in p['text'] for p in pieces)


@pytest.mark.parametrize('cell',['A10001','CW2','XFD2'])
def test_unknown_excel_dimensions_still_enforce_actual_row_column_limits(cell):
    with pytest.raises(ValueError,match='10000 行或 100 列'):
        parse_file('超限物料.xlsx',dimensionless(cell))


def test_stale_large_metadata_does_not_reject_a_small_real_worksheet():
    source=dimensionless();target=io.BytesIO()
    with zipfile.ZipFile(io.BytesIO(source)) as archive,zipfile.ZipFile(target,'w') as output:
        for name in archive.namelist():
            data=archive.read(name)
            if name=='xl/worksheets/sheet1.xml':
                data=data.replace(b'<sheetViews>',b'<dimension ref="A1:XFD1048576"/><sheetViews>')
            output.writestr(name,data)
    assert any('L100 DRV-07' in p['text'] for p in parse_file('过期尺寸.xlsx',target.getvalue()))


def test_nested_worksheet_relationship_cannot_bypass_real_cell_limits():
    source=dimensionless('A10001');target=io.BytesIO()
    with zipfile.ZipFile(io.BytesIO(source)) as archive,zipfile.ZipFile(target,'w') as output:
        for name in archive.namelist():
            data=archive.read(name)
            if name=='xl/_rels/workbook.xml.rels' or name=='[Content_Types].xml':
                data=data.replace(b'/xl/worksheets/sheet1.xml',b'/xl/worksheets/nested/sheet1.xml')
            if name=='xl/worksheets/sheet1.xml':name='xl/worksheets/nested/sheet1.xml'
            output.writestr(name,data)
    with pytest.raises(ValueError,match='10000 行或 100 列'):
        parse_file('子目录工作表.xlsx',target.getvalue())


def test_extensionless_worksheet_with_leading_whitespace_is_bounded():
    source=dimensionless('A10001');target=io.BytesIO()
    with zipfile.ZipFile(io.BytesIO(source)) as archive,zipfile.ZipFile(target,'w') as output:
        for name in archive.namelist():
            data=archive.read(name)
            if name=='xl/_rels/workbook.xml.rels' or name=='[Content_Types].xml':
                data=data.replace(b'/xl/worksheets/sheet1.xml',b'/xl/worksheets/nested/sheet1')
            if name=='xl/worksheets/sheet1.xml':
                name='xl/worksheets/nested/sheet1';data=b' '*2048+data
            output.writestr(name,data)
    with pytest.raises(ValueError,match='10000 行或 100 列'):
        parse_file('无扩展名工作表.xlsx',target.getvalue())
