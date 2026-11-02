package com.neuedu.ems.service.mdm;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.neuedu.ems.common.ResultCode;
import com.neuedu.ems.entity.mdm.*;
import com.neuedu.ems.entity.process.*;
import com.neuedu.ems.entity.factory.*;
import com.neuedu.ems.entity.mfg.*;
import com.neuedu.ems.entity.qms.*;
import com.neuedu.ems.entity.org.OrgShift;
import com.neuedu.ems.entity.org.OrgTeam;
import com.neuedu.ems.entity.org.OrgTeamMember;
import com.neuedu.ems.entity.org.OrgTeamSchedule;
import com.neuedu.ems.mapper.mdm.*;
import com.neuedu.ems.mapper.process.*;
import com.neuedu.ems.mapper.factory.*;
import com.neuedu.ems.mapper.mfg.*;
import com.neuedu.ems.mapper.qms.*;
import com.neuedu.ems.mapper.org.OrgShiftMapper;
import com.neuedu.ems.mapper.org.OrgTeamMapper;
import com.neuedu.ems.mapper.org.OrgTeamMemberMapper;
import com.neuedu.ems.mapper.org.OrgTeamScheduleMapper;
import com.neuedu.ems.mapper.eam.EamEquipmentMapper;
import com.neuedu.ems.exception.BusinessException;
import com.neuedu.ems.service.qms.InspectionStandardReviewService;
import com.neuedu.ems.vo.mdm.ManufacturingPreparationReport;
import com.neuedu.ems.vo.mdm.ManufacturingPreparationReport.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

@Service
@RequiredArgsConstructor
public class ManufacturingPreparationService {
    private final MdmProductMapper products;
    private final MdmProductParameterMapper parameters;
    private final MdmUnitMapper units;
    private final MdmBomMapper boms;
    private final MdmBomItemMapper bomItems;
    private final BomExpansionService expansion;
    private final ProcessRouteMapper routes;
    private final ProcessRouteItemMapper steps;
    private final ProcessOperationMapper operations;
    private final MfgLineProductCapabilityMapper capabilities;
    private final FactoryLineMapper lines;
    private final FactoryWorkshopMapper workshops;
    private final FactoryStationMapper stations;
    private final FactoryStationOperationMapper stationOperations;
    private final EamEquipmentMapper equipment;
    private final MfgLineCalendarRuleMapper calendars;
    private final OrgShiftMapper shifts;
    private final OrgTeamMapper teams;
    private final OrgTeamMemberMapper teamMembers;
    private final OrgTeamScheduleMapper teamSchedules;
    private final QmsInspectionSchemeMapper schemes;
    private final QmsInspectionSchemeItemMapper schemeItems;
    private final QmsInspectionStandardMapper standards;
    private final QmsInspectionItemMapper inspectionItems;
    private final PreparationValuePolicy policy;
    private final InspectionStandardReviewService qualityReview;

    public List<MdmProduct> products(){return products.selectList(new QueryWrapper<MdmProduct>()
            .eq("deleted_flag",0).eq("preparation_controlled",1).orderByAsc("product_code"));}

    @Transactional(readOnly=true)
    public ManufacturingPreparationReport inspect(Long productId,LocalDate date){
        LocalDate asOf=date==null?LocalDate.now():date;
        var product=products.selectById(productId);
        if(product==null || !Integer.valueOf(0).equals(product.getDeletedFlag()) || !Integer.valueOf(1).equals(product.getPreparationControlled()))
            throw new BusinessException(ResultCode.NOT_FOUND.getCode(),"受控制造产品不存在或已删除");
        List<Check> checks=new ArrayList<>();
        add(checks,"PRODUCT","产品启用","ENABLE".equals(product.getStatus()),"产品已启用","产品未启用");
        var params=parameters.selectList(new QueryWrapper<MdmProductParameter>().eq("product_id",productId).eq("deleted_flag",0).orderByAsc("parameter_code"));
        List<String> missing=new ArrayList<>();
        for(var p:params){
            if(!Integer.valueOf(1).equals(p.getRequiredFlag()))continue;
            try{if(!"CONFIRMED".equals(p.getStatus()))throw new IllegalStateException();
                policy.validateParameter(p.getValueType(),p.getParameterValue(),p.getSourceReference());
            }catch(RuntimeException e){missing.add(p.getParameterName());}
        }
        boolean hasRequired=params.stream().anyMatch(p->Integer.valueOf(1).equals(p.getRequiredFlag()));
        add(checks,"PARAMETERS","产品参数依据",hasRequired && missing.isEmpty(),"必需参数已确认",hasRequired?"待确认："+String.join("、",missing):"尚未建立必需参数清单");
        var activeBoms=boms.selectList(new QueryWrapper<MdmBom>().eq("product_id",productId).eq("deleted_flag",0).eq("status","RELEASED")
                .le("effective_date",asOf).and(q->q.isNull("effective_to").or().ge("effective_to",asOf)));
        String bomError=null;
        if(activeBoms.size()!=1)bomError="所选日期需要唯一已发布且生效的 BOM，当前 "+activeBoms.size()+" 套";
        else try{expansion.expand(activeBoms.getFirst().getBomId(),BigDecimal.ONE,asOf,false);}
        catch(BusinessException e){bomError=e.getMessage();}
        if(bomError==null && Integer.valueOf(1).equals(product.getPreparationControlled())
                && bomItems.selectCount(new QueryWrapper<MdmBomItem>().eq("bom_id",activeBoms.getFirst().getBomId())
                .eq("deleted_flag",0).isNull("consumption_operation_id"))>0)
            bomError="已发布BOM尚未绑定投料工序，请新建版本补齐，不直接修改已发布依据";
        add(checks,"BOM","产品结构",bomError==null,"生效 BOM 及子引用校验通过",bomError);

        var productRoutes=routes.selectList(new QueryWrapper<ProcessRoute>().eq("product_id",productId).eq("deleted_flag",0).ne("status","DISABLE").orderByAsc("route_code"));
        List<Route> routeRows=new ArrayList<>();List<String> routeErrors=new ArrayList<>();
        boolean released=false;
        for(var route:productRoutes){
            var items=steps.selectList(new QueryWrapper<ProcessRouteItem>().eq("route_id",route.getRouteId()).eq("deleted_flag",0).orderByAsc("sequence_no"));
            var routeSteps=new ArrayList<RouteStep>();Set<Integer> sequence=new HashSet<>();
            for(var item:items){
                var op=item.getOperationId()==null?null:operations.selectById(item.getOperationId());
                routeSteps.add(new RouteStep(item,op==null?"工序不存在":op.getOperationName()));
                if(op==null || !Integer.valueOf(0).equals(op.getDeletedFlag()) || !"ENABLE".equals(op.getStatus()))routeErrors.add(route.getRouteCode()+"含无效工序");
                if(item.getSequenceNo()==null || item.getSequenceNo()<=0 || !sequence.add(item.getSequenceNo()))routeErrors.add(route.getRouteCode()+"工序顺序缺失或重复");
                if(!validTiming(item))routeErrors.add(route.getRouteCode()+"工时/批量占用或依据未完成");
            }
            if(items.isEmpty())routeErrors.add(route.getRouteCode()+"缺少工序");
            if("RELEASED".equals(route.getStatus()))released=true;
            routeRows.add(new Route(route.getRouteId(),route.getRouteCode(),route.getRouteName(),route.getStatus(),routeSteps));
        }
        add(checks,"ROUTES","路线与工时",released && routeErrors.isEmpty(),"发布路线及工时依据完整",
                !released?"当前没有已发布路线；草稿供工程设计核对":String.join("；",new LinkedHashSet<>(routeErrors)));
        List<String> resourceErrors=resourceErrors(productId,asOf);
        add(checks,"RESOURCES","产线、工位与周历",resourceErrors.isEmpty(),"产线工位与日期范围内周历可用",String.join("；",resourceErrors));

        var qs=schemes.selectList(new QueryWrapper<QmsInspectionScheme>().eq("product_id",productId).eq("deleted_flag",0).ne("status","DISABLE").orderByAsc("scheme_code"));
        List<QualityScheme> qualityRows=new ArrayList<>();List<String> qualityErrors=new ArrayList<>();boolean hasFinal=false;
        Set<Long> releasedProcess=new HashSet<>();
        for(var scheme:qs){
            var links=schemeItems.selectList(new QueryWrapper<QmsInspectionSchemeItem>().eq("scheme_id",scheme.getSchemeId()).eq("deleted_flag",0).orderByAsc("sequence_no"));
            List<QualityStandard> definitions=new ArrayList<>();
            for(var link:links){
                var value=link.getStandardId()==null?null:standards.selectById(link.getStandardId());
                if(value==null || !Integer.valueOf(0).equals(value.getDeletedFlag()))continue;
                var item=value.getItemId()==null?null:inspectionItems.selectById(value.getItemId());
                var unit=item==null || item.getUnitId()==null?null:units.selectById(item.getUnitId());
                definitions.add(new QualityStandard(value.getStandardId(),item==null?"检验项目不存在":item.getItemName(),unit==null?"":unit.getUnitName(),
                        value.getStandardValue(),value.getLowerLimit(),value.getUpperLimit(),value.getJudgementRule(),value.getConfirmationStatus(),
                        value.getSourceReference(),value.getVersion(),link.getSamplingMode(),link.getSampleQuantity()));
            }
            qualityRows.add(new QualityScheme(scheme.getSchemeId(),scheme.getSchemeCode(),scheme.getSchemeName(),scheme.getInspectionType(),scheme.getStatus(),definitions));
            if(!"RELEASED".equals(scheme.getStatus())){qualityErrors.add(scheme.getSchemeCode()+"尚未发布");continue;}
            try{qualityReview.assertSchemeReady(scheme.getSchemeId());
                if("FINAL".equals(scheme.getInspectionType()))hasFinal=true;
                if("PROCESS".equals(scheme.getInspectionType()))releasedProcess.add(scheme.getOperationId());
            }catch(BusinessException e){qualityErrors.add(scheme.getSchemeCode()+"："+e.getMessage());}
        }
        if(!hasFinal){
            qualityErrors.add("缺少已发布且标准已确认的终检方案");
        }
        addProcessSchemeErrors(routeRows,releasedProcess,qualityErrors);
        add(checks,"QUALITY","检验标准与方案",qualityErrors.isEmpty(),"适用过程检/终检标准已确认并发布",String.join("；",new LinkedHashSet<>(qualityErrors)));
        List<String> scheduleErrors=teamScheduleErrors(asOf);
        add(checks,"SCHEDULE","班组日期排班",scheduleErrors.isEmpty(),"当日已按班组、产线、班次排出人员岗位班次",
                String.join("；",new LinkedHashSet<>(scheduleErrors)));
        boolean configured=checks.stream().allMatch(c->"PASS".equals(c.status()));
        List<String> blockers=blockersOf(checks);
        return new ManufacturingPreparationReport(productId,product.getProductCode(),product.getProductName(),asOf,
                configured,configured,checks,blockers,params,routeRows,qualityRows);
    }

    /** 核对每条已发布路线上需过程检的工序是否都有已发布方案。 */
    private static void addProcessSchemeErrors(List<Route> routeRows,Set<Long> releasedProcess,
            List<String> qualityErrors){
        for (var route : routeRows) {
            for (var step : route.steps()) {
                boolean needsProcessCheck = Integer.valueOf(1).equals(step.item().getQcRequired());
                if (needsProcessCheck && !releasedProcess.contains(step.item().getOperationId())) {
                    qualityErrors.add(step.operationName()+"缺少已发布过程检方案");
                }
            }
        }
    }

    /** 汇总未通过检查项为阻塞原因文案。 */
    private static List<String> blockersOf(List<Check> checks){
        List<String> blockers=new ArrayList<>();
        for(var check:checks){
            if(!"PASS".equals(check.status())){
                blockers.add(check.label()+"："+check.message());
            }
        }
        return blockers;
    }

    public boolean validTiming(ProcessRouteItem item){
        if(!policy.hasEvidence(item.getTimeSourceReference())){
            return false;
        }
        String resourceType=item.getResourceType();
        boolean labor= "LABOR".equals(resourceType) && positive(item.getStandardTime());
        boolean batch= "BATCH_EQUIPMENT".equals(resourceType) && validBatchTiming(item);
        return labor || batch;
    }

    /** 批量设备工序需要整数批量容量、正批量时长，标准时间不得为负。 */
    private boolean validBatchTiming(ProcessRouteItem item){
        BigDecimal capacity=item.getBatchCapacity();
        boolean wholeCapacity=positive(capacity) && capacity.stripTrailingZeros().scale()<=0;
        boolean positiveDuration=positive(item.getBatchDurationMinutes());
        boolean standardTimeOk=item.getStandardTime()==null || item.getStandardTime().signum()>=0;
        return wholeCapacity && positiveDuration && standardTimeOk;
    }
    private List<String> resourceErrors(Long productId,LocalDate date){
        var caps=capabilities.selectList(new QueryWrapper<MfgLineProductCapability>()
                .eq("product_id",productId).eq("deleted_flag",0).eq("status","ENABLE"));
        if(caps.isEmpty()){
            return List.of("尚无已启用的产品产线能力；草稿能力不用于排程");
        }
        List<String> failures=new ArrayList<>();
        for(var cap:caps){
            failures.addAll(capabilityErrors(cap,productId,date));
        }
        return new ArrayList<>(new LinkedHashSet<>(failures));
    }

    /** 校验单条产品产线能力，返回该项的失败原因。 */
    private List<String> capabilityErrors(MfgLineProductCapability cap,Long productId,LocalDate date){
        var line=lines.selectById(cap.getLineId());
        var route=routes.selectById(cap.getRouteId());
        String blocking=capabilityBlocking(line,route,productId);
        if(blocking!=null){
            return new ArrayList<>(List.of(blocking));
        }
        List<String> failures=new ArrayList<>();
        if(!shiftReady(line.getLineId(),date)){
            failures.add(line.getLineCode()+"在所选日期无有效班次周历");
        }
        failures.addAll(stepErrors(line,route));
        return failures;
    }

    /** 能力校验的前置阻断条件：命中即整条能力不可用于排程，返回第一条原因。 */
    private String capabilityBlocking(FactoryLine line,ProcessRoute route,Long productId){
        String blocking=null;
        if(!lineRunnable(line)){
            blocking="产线未运行或人工日产能未确认";
        }else if(!workshopEnabled(workshops.selectById(line.getWorkshopId()))){
            blocking="车间未启用";
        }else if(!routeReleasable(route,productId)){
            blocking="能力引用路线未发布或产品不匹配";
        }
        return blocking;
    }

    /** 产线须未删除、运行中且人工日产能已确认。 */
    private boolean lineRunnable(FactoryLine line){
        return line!=null && Integer.valueOf(0).equals(line.getDeletedFlag())
                && "RUNNING".equals(line.getStatus()) && positive(line.getDailyCapacity());
    }

    /** 车间须未删除且启用。 */
    private boolean workshopEnabled(FactoryWorkshop workshop){
        return workshop!=null && Integer.valueOf(0).equals(workshop.getDeletedFlag())
                && "ENABLE".equals(workshop.getStatus());
    }

    /** 路线须未删除、属于该产品且已发布。 */
    private boolean routeReleasable(ProcessRoute route,Long productId){
        return route!=null && Integer.valueOf(0).equals(route.getDeletedFlag())
                && productId.equals(route.getProductId()) && "RELEASED".equals(route.getStatus());
    }

    /** 所选日期须有启用周历且对应班次有效。 */
    private boolean shiftReady(Long lineId,LocalDate date){
        var slots=calendars.selectList(new QueryWrapper<MfgLineCalendarRule>().eq("line_id",lineId)
                .eq("deleted_flag",0).eq("status","ENABLE")
                .eq("day_of_week",date.getDayOfWeek().getValue()).le("effective_from",date)
                .and(q->q.isNull("effective_to").or().ge("effective_to",date)));
        return slots.stream().anyMatch(s->shiftValid(shifts.selectById(s.getShiftId())));
    }

    /** 班次须未删除且有起止时间且两者不等。 */
    private boolean shiftValid(OrgShift shift){
        if(shift==null || !Integer.valueOf(0).equals(shift.getDeletedFlag())){
            return false;
        }
        if(shift.getStartTime()==null || shift.getEndTime()==null){
            return false;
        }
        return !shift.getStartTime().equals(shift.getEndTime());
    }

    /** 逐工序核对是否挂了本产线可用工位（批量设备工序还需有可用设备）。 */
    private List<String> stepErrors(FactoryLine line,ProcessRoute route){
        List<String> failures=new ArrayList<>();
        var items=steps.selectList(new QueryWrapper<ProcessRouteItem>()
                .eq("route_id",route.getRouteId()).eq("deleted_flag",0));
        for(var step:items){
            if(!stepSupported(line,step)){
                failures.add(line.getLineCode()+"缺少工序 "+step.getOperationId()+" 的可用工位/批量设备");
            }
        }
        return failures;
    }

    /** 工序是否至少有一个本产线运行中的工位支撑。 */
    private boolean stepSupported(FactoryLine line,ProcessRouteItem step){
        var bindings=stationOperations.selectList(new QueryWrapper<FactoryStationOperation>()
                .eq("operation_id",step.getOperationId()).eq("deleted_flag",0).eq("status","ENABLE"));
        return bindings.stream().anyMatch(b->stationSupports(line,step,b));
    }

    /** 单个工位绑定是否可用。 */
    private boolean stationSupports(FactoryLine line,ProcessRouteItem step,FactoryStationOperation binding){
        var station=stations.selectById(binding.getStationId());
        if(station==null || !Integer.valueOf(0).equals(station.getDeletedFlag())
                || !line.getLineId().equals(station.getLineId()) || !"RUNNING".equals(station.getStatus())){
            return false;
        }
        if(!"BATCH_EQUIPMENT".equals(step.getResourceType())){
            return true;
        }
        return equipment.selectCount(new QueryWrapper<com.neuedu.ems.entity.eam.EamEquipment>()
                .eq("station_id",station.getStationId()).eq("deleted_flag",0)
                .in("status","IDLE","RUNNING"))>0;
    }
    /**
     * 校验所选日期是否已排出「班组—产线—班次」人员岗位排班。
     * 周历规则只表示产线可用时段，不等于人员排班；执行派工必须有当日已生效的班组排班且班组成员有效。
     */
    private List<String> teamScheduleErrors(LocalDate date){
        var caps=capabilities.selectList(new QueryWrapper<MfgLineProductCapability>()
                .eq("deleted_flag",0).eq("status","ENABLE"));
        var activeLines=new LinkedHashSet<Long>();
        for(var cap:caps){
            if(cap.getLineId()!=null){
                activeLines.add(cap.getLineId());
            }
        }
        if(activeLines.isEmpty()){
            return List.of("尚无已启用的产线产品能力，无法核对班组排班");
        }
        List<String> failures=new ArrayList<>();
        for(Long lineId:activeLines){
            failures.addAll(lineTeamScheduleErrors(lineId,date));
        }
        return new ArrayList<>(new LinkedHashSet<>(failures));
    }

    /** 核对单条产线在指定日期的班组排班是否可执行派工。 */
    private List<String> lineTeamScheduleErrors(Long lineId,LocalDate date){
        List<String> failures=new ArrayList<>();
        var line=lines.selectById(lineId);
        String lineCode=line==null?"产线"+lineId:line.getLineCode();
        var schedules=teamSchedules.selectList(new QueryWrapper<OrgTeamSchedule>()
                .eq("line_id",lineId).eq("work_date",date).eq("status","RELEASED").eq("deleted_flag",0));
        if(schedules.isEmpty()){
            failures.add(lineCode+"在 "+date+" 没有已生效的班组排班");
            return failures;
        }
        for(var schedule:schedules){
            failures.addAll(scheduleErrors(lineCode,schedule));
        }
        return failures;
    }

    /** 核对单条排班的班组与班次是否有效。 */
    private List<String> scheduleErrors(String lineCode,OrgTeamSchedule schedule){
        List<String> failures=new ArrayList<>();
        var team=teams.selectById(schedule.getTeamId());
        if(team==null || !Integer.valueOf(0).equals(team.getDeletedFlag())){
            failures.add(lineCode+"排班引用的班组不存在");
            return failures;
        }
        if(!teamHasStaff(team)){
            failures.add(lineCode+"排班班组 "+team.getTeamCode()+" 没有班组长也没有有效成员");
        }
        if(!shiftValid(shifts.selectById(schedule.getShiftId()))){
            failures.add(lineCode+"排班引用的班次无效");
        }
        return failures;
    }

    /** 班组须有班组长或至少一名有效成员。 */
    private boolean teamHasStaff(OrgTeam team){
        if(team.getLeaderId()!=null){
            return true;
        }
        long members=teamMembers.selectCount(new QueryWrapper<OrgTeamMember>()
                .eq("team_id",team.getTeamId()).eq("deleted_flag",0)
                .eq("status","ENABLE").isNull("leave_date"));
        return members>0;
    }
    private boolean positive(BigDecimal value){return value!=null && value.signum()>0;}
    private void add(List<Check> checks,String code,String label,boolean pass,String ok,String failure){checks.add(new Check(code,label,pass?"PASS":"BLOCKED",pass?ok:failure));}
}
