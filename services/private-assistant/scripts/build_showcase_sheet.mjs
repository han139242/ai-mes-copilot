// Author only the synthetic BOM workbook using a supplied artifact-tool runtime.
import fs from 'node:fs/promises';
import {createRequire} from 'node:module';
import {join,dirname} from 'node:path';
import {pathToFileURL} from 'node:url';
const [source,target,packages,preview] = process.argv.slice(2);
if (!source || !target || !packages) throw new Error('Expected source, target and artifact package directory');
const require=createRequire(join(packages,'package.json'));
const {Workbook,SpreadsheetFile}=await import(pathToFileURL(require.resolve('@oai/artifact-tool')).href);
const text=await fs.readFile(source,'utf8');
const rows=text.split(/\r?\n/).filter(line=>line.startsWith('|') && !/^\|[-| ]+\|$/.test(line))
  .map(line=>line.split('|').slice(1,-1).map(cell=>cell.trim()));
const wb=Workbook.create(),sheet=wb.worksheets.add('产品物料');
sheet.showGridLines=false;
sheet.getRange('A1:F1').merge();
sheet.getRange('A1').values=[['产品物料对应表  合成教学样例']];
sheet.getRange('A1:F1').format.fill='#EAF3F8';
sheet.getRange('A1:F1').format.font={bold:true,size:16,color:'#174A66'};
sheet.getRange('A1:F1').format.rowHeight=32;
sheet.getRange('A2:F2').merge();
sheet.getRange('A2').values=[['非正式BOM；数量为单台教学配置，不代表实时库存。']];
sheet.getRange(`A4:F${rows.length+3}`).values=rows.map((row,i)=>row.map((value,col)=>i>0&&col===3?Number(value):value));
sheet.getRange('A4:F4').format.fill='#176FA7';
sheet.getRange('A4:F4').format.font={bold:true,color:'#FFFFFF'};
sheet.getRange(`A4:F${rows.length+3}`).format.rowHeight=28;
sheet.getRange(`A4:F${rows.length+3}`).format.borders={preset:'all',style:'thin',color:'#DCE5E8'};
sheet.getRange('A:F').format.columnWidth=19;
sheet.getRange('C:C').format.columnWidth=25;
const foot=rows.length+5;
sheet.getRange(`A${foot}:F${foot+1}`).merge();
sheet.getRange(`A${foot}`).values=[['DRV-08仍为ECO-L100-02候选物料，未纳入生效BOM。仓库可用数量应查询MES。']];
sheet.getRange(`A${foot}:F${foot+1}`).format.wrapText=true;
sheet.freezePanes.freezeRows(4);
await wb.recalculate();
const file=await SpreadsheetFile.exportXlsx(wb);await file.save(target);
if(preview){try{await fs.rename(target+'.inspect.ndjson',join(dirname(preview),'BOM-inspect.ndjson'));}catch(error){if(error.code!=='ENOENT')throw error;}}
if(preview){const png=await wb.render({sheetName:'产品物料',range:`A1:F${foot+1}`,scale:1.3,format:'png'});await fs.writeFile(preview,new Uint8Array(await png.arrayBuffer()));}
console.log(JSON.stringify({rows:rows.length-1,output:target}));
