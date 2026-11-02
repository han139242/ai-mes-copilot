package com.neuedu.ems.mapper.sys;

import org.apache.ibatis.annotations.*;
import java.time.LocalDate;
import java.util.List;

@Mapper
public interface BusinessNumberMapper {
    @Insert("INSERT INTO sys_document_sequence(scope_prefix,document_type,business_date,current_value,updated_time) "
            + "VALUES(#{prefix},#{type},#{date},1,NOW()) ON DUPLICATE KEY UPDATE current_value=current_value+1,updated_time=NOW()")
    void increment(@Param("prefix") String prefix, @Param("type") String type, @Param("date") LocalDate date);

    @Select("SELECT current_value FROM sys_document_sequence WHERE scope_prefix=#{prefix} AND document_type=#{type} AND business_date=#{date} FOR UPDATE")
    long current(@Param("prefix") String prefix, @Param("type") String type, @Param("date") LocalDate date);

    @Select("SELECT number_prefix FROM mdm_customer WHERE customer_id=#{id}")
    String customerPrefix(Long id);

    @Select({"<script><choose>",
        "<when test=\"source == 'PRODUCTION_ORDER'\">SELECT customer_id FROM mfg_production_order WHERE production_order_id=#{id}</when>",
        "<when test=\"source == 'PLAN'\">SELECT o.customer_id FROM mfg_production_plan p JOIN mfg_production_order o ON o.production_order_id=p.production_order_id WHERE p.plan_id=#{id}</when>",
        "<when test=\"source == 'INSPECTION'\">SELECT COALESCE(o.customer_id,shipping.customer_id,sales.customer_id) FROM qms_inspection_order q LEFT JOIN trace_product_lot l ON l.product_lot_id=q.product_lot_id LEFT JOIN mfg_work_report r ON r.report_id=q.report_id LEFT JOIN mfg_task t ON t.task_id=COALESCE(q.task_id,r.task_id) LEFT JOIN mfg_work_order w ON w.work_order_id=COALESCE(q.work_order_id,l.work_order_id,t.work_order_id) LEFT JOIN mfg_production_plan p ON p.plan_id=w.plan_id LEFT JOIN mfg_production_order o ON o.production_order_id=p.production_order_id LEFT JOIN wms_outbound_order shipping ON shipping.inspection_id=q.inspection_id LEFT JOIN mfg_production_order sales ON sales.production_order_id=shipping.source_production_order_id WHERE q.inspection_id=#{id}</when>",
        "<otherwise>",
        "SELECT o.customer_id FROM mfg_work_order w JOIN mfg_production_plan p ON p.plan_id=w.plan_id JOIN mfg_production_order o ON o.production_order_id=p.production_order_id",
        "<choose>",
        "<when test=\"source == 'WORK_ORDER'\">WHERE w.work_order_id=#{id}</when>",
        "<when test=\"source == 'TASK'\">JOIN mfg_task t ON t.work_order_id=w.work_order_id WHERE t.task_id=#{id}</when>",
        "<when test=\"source == 'LOT'\">JOIN trace_product_lot l ON l.work_order_id=w.work_order_id WHERE l.product_lot_id=#{id}</when>",
        "<otherwise>WHERE 1=0</otherwise>",
        "</choose></otherwise></choose></script>"})
    Long customerId(@Param("source") String source, @Param("id") Long id);

    @Select("SELECT DISTINCT current_number FROM sys_document_number_alias WHERE original_number=#{number}")
    List<String> currentNumbers(String number);

    @Select("SELECT DISTINCT biz_id FROM sys_document_number_alias WHERE document_type=#{type} AND original_number LIKE CONCAT('%',#{keyword},'%')")
    List<Long> aliasIds(@Param("type") String type, @Param("keyword") String keyword);
}
