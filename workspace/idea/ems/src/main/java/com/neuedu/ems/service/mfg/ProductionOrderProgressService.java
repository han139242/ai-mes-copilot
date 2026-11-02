package com.neuedu.ems.service.mfg;

import com.neuedu.ems.common.AuthorizationContext;
import com.neuedu.ems.vo.mfg.ProductionOrderProgressVO;
import java.time.LocalDateTime;
import java.util.List;

public interface ProductionOrderProgressService {
    List<String> REQUIRED_PERMISSIONS = List.of("mfg:production-order:view", "mfg:production-plan:view",
            "mfg:work-order:view", "mfg:task:view", "mfg:work-report:view", "wms:inventory:view",
            "wms:inbound-order:view", "wms:outbound-order:view", "qms:inspection-order:view",
            "eam:equipment:view", "trace:product-lot:view", "mfg:dispatch-order:view",
            "wms:material-batch:view", "wms:return-order:view", "wms:sales-outbound:view",
            "qms:nonconforming-order:view", "eam:repair-order:view");

    ProductionOrderProgressVO inspect(Long orderId, LocalDateTime asOf, AuthorizationContext authorization);
}
