package com.neuedu.ems.mapper.mdm;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import java.math.BigDecimal;

@Mapper
public interface BomStockMapper {
    /** 只抵扣半成品仓的有效、已放行、可用余额；线边/待检/锁定量不抵扣。 */
    @Select("""
        SELECT COALESCE(SUM(i.available_quantity),0)
        FROM wms_inventory i
        JOIN wms_material_batch b ON b.batch_id=i.batch_id AND b.deleted_flag=0
        JOIN wms_warehouse w ON w.warehouse_id=i.warehouse_id AND w.deleted_flag=0
        JOIN wms_location l ON l.location_id=i.location_id AND l.warehouse_id=i.warehouse_id AND l.deleted_flag=0
        WHERE i.deleted_flag=0 AND i.material_id=#{materialId} AND b.material_id=i.material_id
          AND i.available_quantity>0 AND b.quality_status IN ('PASS','CONCESSION')
          AND b.status IN ('AVAILABLE','ENABLE') AND w.status='ENABLE' AND w.warehouse_type='SEMI_FINISHED'
          AND l.status IN ('IDLE','OCCUPIED','ENABLE') AND l.location_type='NORMAL'
        """)
    BigDecimal availableComponents(@Param("materialId") Long materialId);
}
