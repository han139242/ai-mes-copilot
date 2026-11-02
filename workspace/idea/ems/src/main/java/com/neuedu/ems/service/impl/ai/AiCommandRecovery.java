package com.neuedu.ems.service.impl.ai;

import java.util.*;
import java.util.regex.Pattern;

/** 只依据本轮失败条件给恢复入口；入口仍须重新预览和确认。 */
final class AiCommandRecovery {
    private AiCommandRecovery() {}
    static List<Map<String,Object>> steps(String action,String target,List<Map<String,Object>> checks) {
        List<Map<String,Object>> result=new ArrayList<>();
        Set<String> seen=new HashSet<>();
        for(var check:checks) {
            if(Boolean.TRUE.equals(check.get("passed"))) continue;
            String code=String.valueOf(check.get("code")),detail=Objects.toString(check.get("detail"),"");
            String label="核对阻断原因",question="查询 "+target+" 的当前状态和下一步";
            var qi=Pattern.compile("(?:[A-Za-z0-9]+-)?QI-[A-Za-z0-9-]+").matcher(detail);
            if(qi.find()) {label="填写关联检验结果";question="提交检验 "+qi.group();}
            else if(detail.contains("上级")&&detail.contains("下达")) {label="先下达上级工单";question="查询 "+target+" 的上级工单，并给出下达操作";}
            else if(code.equals("WORK_ORDER_STATUS")&&detail.contains("DRAFT")) {label="先下达这张工单";question="下达工单 "+target;}
            else if(code.contains("WAREHOUSE")) {label="重新选择领料仓库";question="查询 "+target+" 的工单类型和领用物料，核对原料仓或半成品仓后重新预览";}
            else if(code.equals("TARGET_LOCATION")) {label="选择本产线线边库位";question="查询 "+target+" 所属产线的线边库位，并给出可用的转线边目标库位";}
            else if(code.equals("MATERIAL_AVAILABLE")||code.equals("MATERIAL_KIT")) {label="核对库存、领料与线边供给";question="查询 "+target+" 还缺哪些物料、哪些已领到线边，并给出处理步骤";}
            else if(code.equals("TASK_READY")) {label="核对派工和工序任务";question="查询 "+target+" 的派工和工序任务，下一步怎么办";}
            else if(code.contains("PREDECESSOR")) {label="先完成前序报工和审核";question="查询 "+target+" 尚未完成的前序任务及报工审核";}
            else if(code.contains("PIECE")||code.contains("INSPECTION")) {label="填写实际检验结果";question="提交检验 "+target;}
            else if(code.contains("REPORT")||code.contains("QUANTITY")) {label="补齐实际报工数量与用料";question="给 "+target+" 提交报工";}
            if(seen.add(question)) result.add(Map.of("label",label,"detail",detail,"question",question));
            if(result.size()==3) break;
        }
        return result;
    }
}
