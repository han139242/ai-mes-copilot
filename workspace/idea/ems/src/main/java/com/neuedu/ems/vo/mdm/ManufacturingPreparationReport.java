package com.neuedu.ems.vo.mdm;

import com.neuedu.ems.entity.mdm.MdmProductParameter;
import com.neuedu.ems.entity.process.ProcessRouteItem;
import com.neuedu.ems.entity.qms.QmsInspectionStandard;
import java.time.LocalDate;
import java.util.List;

public record ManufacturingPreparationReport(Long productId,String productCode,String productName,LocalDate asOf,
        boolean configurationReady,boolean executionReady,List<Check> checks,List<String> executionBlockers,
        List<MdmProductParameter> parameters,List<Route> routes,List<QualityScheme> qualitySchemes) {
    public record Check(String code,String label,String status,String message){}
    public record Route(Long routeId,String routeCode,String routeName,String status,List<RouteStep> steps){}
    public record RouteStep(ProcessRouteItem item,String operationName){}
    public record QualityScheme(Long schemeId,String schemeCode,String schemeName,String inspectionType,
            String status,List<QualityStandard> standards){}
    public record QualityStandard(Long standardId,String itemName,String unitLabel,String standardValue,String lowerLimit,
            String upperLimit,String judgementRule,String confirmationStatus,String sourceReference,Integer version,
            String samplingMode,java.math.BigDecimal sampleQuantity){}
}
