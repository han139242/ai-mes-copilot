-- 台灯产品设计主数据；教学设计基线，不创建订单、库存、流水或检验实测值。
-- 新库先 schema.sql；现有演练库先 lamp_foundation_v1.sql。只补缺，不覆盖已维护档案。
SET NAMES utf8mb4;
START TRANSACTION;
INSERT INTO `mdm_unit` (`unit_code`,`unit_name`,`precision_scale`,`status`,remark,created_time)
SELECT 'LAMP-TAI','台',0,'ENABLE','LAMP_DESIGN_V1: 教学设计，投产前冻结规格与检验标准',NOW()
WHERE NOT EXISTS(SELECT 1 FROM `mdm_unit` WHERE `unit_code`='LAMP-TAI');
INSERT INTO `mdm_unit` (`unit_code`,`unit_name`,`precision_scale`,`status`,remark,created_time)
SELECT 'LAMP-PC','个',0,'ENABLE','LAMP_DESIGN_V1: 教学设计，投产前冻结规格与检验标准',NOW()
WHERE NOT EXISTS(SELECT 1 FROM `mdm_unit` WHERE `unit_code`='LAMP-PC');
INSERT INTO `mdm_unit` (`unit_code`,`unit_name`,`precision_scale`,`status`,remark,created_time)
SELECT 'LAMP-BLOCK','块',0,'ENABLE','LAMP_DESIGN_V1: 教学设计，投产前冻结规格与检验标准',NOW()
WHERE NOT EXISTS(SELECT 1 FROM `mdm_unit` WHERE `unit_code`='LAMP-BLOCK');
INSERT INTO `mdm_unit` (`unit_code`,`unit_name`,`precision_scale`,`status`,remark,created_time)
SELECT 'LAMP-SHEET','片',0,'ENABLE','LAMP_DESIGN_V1: 教学设计，投产前冻结规格与检验标准',NOW()
WHERE NOT EXISTS(SELECT 1 FROM `mdm_unit` WHERE `unit_code`='LAMP-SHEET');
INSERT INTO `mdm_unit` (`unit_code`,`unit_name`,`precision_scale`,`status`,remark,created_time)
SELECT 'LAMP-PART','件',0,'ENABLE','LAMP_DESIGN_V1: 教学设计，投产前冻结规格与检验标准',NOW()
WHERE NOT EXISTS(SELECT 1 FROM `mdm_unit` WHERE `unit_code`='LAMP-PART');
INSERT INTO `mdm_unit` (`unit_code`,`unit_name`,`precision_scale`,`status`,remark,created_time)
SELECT 'LAMP-SET','套',0,'ENABLE','LAMP_DESIGN_V1: 教学设计，投产前冻结规格与检验标准',NOW()
WHERE NOT EXISTS(SELECT 1 FROM `mdm_unit` WHERE `unit_code`='LAMP-SET');
INSERT INTO `mdm_unit` (`unit_code`,`unit_name`,`precision_scale`,`status`,remark,created_time)
SELECT 'LAMP-SCREW','颗',0,'ENABLE','LAMP_DESIGN_V1: 教学设计，投产前冻结规格与检验标准',NOW()
WHERE NOT EXISTS(SELECT 1 FROM `mdm_unit` WHERE `unit_code`='LAMP-SCREW');
INSERT INTO `mdm_material_category` (`category_code`,`category_name`,`status`,remark,created_time)
SELECT 'LAMP-RAW','台灯外购生产件','ENABLE','LAMP_DESIGN_V1: 教学设计，投产前冻结规格与检验标准',NOW()
WHERE NOT EXISTS(SELECT 1 FROM `mdm_material_category` WHERE `category_code`='LAMP-RAW');
INSERT INTO `mdm_material_category` (`category_code`,`category_name`,`status`,remark,created_time)
SELECT 'LAMP-AUX','台灯辅料','ENABLE','LAMP_DESIGN_V1: 教学设计，投产前冻结规格与检验标准',NOW()
WHERE NOT EXISTS(SELECT 1 FROM `mdm_material_category` WHERE `category_code`='LAMP-AUX');
INSERT INTO `mdm_material_category` (`category_code`,`category_name`,`status`,remark,created_time)
SELECT 'LAMP-SEMI','台灯自制组件','ENABLE','LAMP_DESIGN_V1: 教学设计，投产前冻结规格与检验标准',NOW()
WHERE NOT EXISTS(SELECT 1 FROM `mdm_material_category` WHERE `category_code`='LAMP-SEMI');
INSERT INTO `mdm_material_category` (`category_code`,`category_name`,`status`,remark,created_time)
SELECT 'LAMP-FG','台灯成品','ENABLE','LAMP_DESIGN_V1: 教学设计，投产前冻结规格与检验标准',NOW()
WHERE NOT EXISTS(SELECT 1 FROM `mdm_material_category` WHERE `category_code`='LAMP-FG');
INSERT INTO `mdm_material` (`material_code`,`material_name`,`category_id`,`spec_model`,`unit_id`,`material_type`,`quantity_mode`,`issue_step`,`status`,remark,created_time)
SELECT 'LAMP-M01','LED灯板',(SELECT `category_id` FROM `mdm_material_category` WHERE `category_code`='LAMP-RAW' AND deleted_flag=0 LIMIT 1),'设计规格待受控冻结',(SELECT `unit_id` FROM `mdm_unit` WHERE `unit_code`='LAMP-BLOCK' AND deleted_flag=0 LIMIT 1),'RAW','DISCRETE',1,'ENABLE','LAMP_DESIGN_V1: 教学设计，投产前冻结规格与检验标准',NOW()
WHERE NOT EXISTS(SELECT 1 FROM `mdm_material` WHERE `material_code`='LAMP-M01');
INSERT INTO `mdm_material` (`material_code`,`material_name`,`category_id`,`spec_model`,`unit_id`,`material_type`,`quantity_mode`,`issue_step`,`status`,remark,created_time)
SELECT 'LAMP-M02','散热铝板',(SELECT `category_id` FROM `mdm_material_category` WHERE `category_code`='LAMP-RAW' AND deleted_flag=0 LIMIT 1),'设计规格待受控冻结',(SELECT `unit_id` FROM `mdm_unit` WHERE `unit_code`='LAMP-SHEET' AND deleted_flag=0 LIMIT 1),'RAW','DISCRETE',1,'ENABLE','LAMP_DESIGN_V1: 教学设计，投产前冻结规格与检验标准',NOW()
WHERE NOT EXISTS(SELECT 1 FROM `mdm_material` WHERE `material_code`='LAMP-M02');
INSERT INTO `mdm_material` (`material_code`,`material_name`,`category_id`,`spec_model`,`unit_id`,`material_type`,`quantity_mode`,`issue_step`,`status`,remark,created_time)
SELECT 'LAMP-M03','扩散板',(SELECT `category_id` FROM `mdm_material_category` WHERE `category_code`='LAMP-RAW' AND deleted_flag=0 LIMIT 1),'设计规格待受控冻结',(SELECT `unit_id` FROM `mdm_unit` WHERE `unit_code`='LAMP-SHEET' AND deleted_flag=0 LIMIT 1),'RAW','DISCRETE',1,'ENABLE','LAMP_DESIGN_V1: 教学设计，投产前冻结规格与检验标准',NOW()
WHERE NOT EXISTS(SELECT 1 FROM `mdm_material` WHERE `material_code`='LAMP-M03');
INSERT INTO `mdm_material` (`material_code`,`material_name`,`category_id`,`spec_model`,`unit_id`,`material_type`,`quantity_mode`,`issue_step`,`status`,remark,created_time)
SELECT 'LAMP-M04','灯头外壳',(SELECT `category_id` FROM `mdm_material_category` WHERE `category_code`='LAMP-RAW' AND deleted_flag=0 LIMIT 1),'设计规格待受控冻结',(SELECT `unit_id` FROM `mdm_unit` WHERE `unit_code`='LAMP-PC' AND deleted_flag=0 LIMIT 1),'RAW','DISCRETE',1,'ENABLE','LAMP_DESIGN_V1: 教学设计，投产前冻结规格与检验标准',NOW()
WHERE NOT EXISTS(SELECT 1 FROM `mdm_material` WHERE `material_code`='LAMP-M04');
INSERT INTO `mdm_material` (`material_code`,`material_name`,`category_id`,`spec_model`,`unit_id`,`material_type`,`quantity_mode`,`issue_step`,`status`,remark,created_time)
SELECT 'LAMP-M05','主控驱动板',(SELECT `category_id` FROM `mdm_material_category` WHERE `category_code`='LAMP-RAW' AND deleted_flag=0 LIMIT 1),'设计规格待受控冻结',(SELECT `unit_id` FROM `mdm_unit` WHERE `unit_code`='LAMP-BLOCK' AND deleted_flag=0 LIMIT 1),'RAW','DISCRETE',1,'ENABLE','LAMP_DESIGN_V1: 教学设计，投产前冻结规格与检验标准',NOW()
WHERE NOT EXISTS(SELECT 1 FROM `mdm_material` WHERE `material_code`='LAMP-M05');
INSERT INTO `mdm_material` (`material_code`,`material_name`,`category_id`,`spec_model`,`unit_id`,`material_type`,`quantity_mode`,`issue_step`,`status`,remark,created_time)
SELECT 'LAMP-M06','触控面板',(SELECT `category_id` FROM `mdm_material_category` WHERE `category_code`='LAMP-RAW' AND deleted_flag=0 LIMIT 1),'设计规格待受控冻结',(SELECT `unit_id` FROM `mdm_unit` WHERE `unit_code`='LAMP-BLOCK' AND deleted_flag=0 LIMIT 1),'RAW','DISCRETE',1,'ENABLE','LAMP_DESIGN_V1: 教学设计，投产前冻结规格与检验标准',NOW()
WHERE NOT EXISTS(SELECT 1 FROM `mdm_material` WHERE `material_code`='LAMP-M06');
INSERT INTO `mdm_material` (`material_code`,`material_name`,`category_id`,`spec_model`,`unit_id`,`material_type`,`quantity_mode`,`issue_step`,`status`,remark,created_time)
SELECT 'LAMP-M07','环境光传感器模块',(SELECT `category_id` FROM `mdm_material_category` WHERE `category_code`='LAMP-RAW' AND deleted_flag=0 LIMIT 1),'设计规格待受控冻结',(SELECT `unit_id` FROM `mdm_unit` WHERE `unit_code`='LAMP-PC' AND deleted_flag=0 LIMIT 1),'RAW','DISCRETE',1,'ENABLE','LAMP_DESIGN_V1: 教学设计，投产前冻结规格与检验标准',NOW()
WHERE NOT EXISTS(SELECT 1 FROM `mdm_material` WHERE `material_code`='LAMP-M07');
INSERT INTO `mdm_material` (`material_code`,`material_name`,`category_id`,`spec_model`,`unit_id`,`material_type`,`quantity_mode`,`issue_step`,`status`,remark,created_time)
SELECT 'LAMP-M08','直流电源插座',(SELECT `category_id` FROM `mdm_material_category` WHERE `category_code`='LAMP-RAW' AND deleted_flag=0 LIMIT 1),'设计规格待受控冻结',(SELECT `unit_id` FROM `mdm_unit` WHERE `unit_code`='LAMP-PC' AND deleted_flag=0 LIMIT 1),'RAW','DISCRETE',1,'ENABLE','LAMP_DESIGN_V1: 教学设计，投产前冻结规格与检验标准',NOW()
WHERE NOT EXISTS(SELECT 1 FROM `mdm_material` WHERE `material_code`='LAMP-M08');
INSERT INTO `mdm_material` (`material_code`,`material_name`,`category_id`,`spec_model`,`unit_id`,`material_type`,`quantity_mode`,`issue_step`,`status`,remark,created_time)
SELECT 'LAMP-M09','底座外壳',(SELECT `category_id` FROM `mdm_material_category` WHERE `category_code`='LAMP-RAW' AND deleted_flag=0 LIMIT 1),'设计规格待受控冻结',(SELECT `unit_id` FROM `mdm_unit` WHERE `unit_code`='LAMP-PC' AND deleted_flag=0 LIMIT 1),'RAW','DISCRETE',1,'ENABLE','LAMP_DESIGN_V1: 教学设计，投产前冻结规格与检验标准',NOW()
WHERE NOT EXISTS(SELECT 1 FROM `mdm_material` WHERE `material_code`='LAMP-M09');
INSERT INTO `mdm_material` (`material_code`,`material_name`,`category_id`,`spec_model`,`unit_id`,`material_type`,`quantity_mode`,`issue_step`,`status`,remark,created_time)
SELECT 'LAMP-M10','底座配重块',(SELECT `category_id` FROM `mdm_material_category` WHERE `category_code`='LAMP-RAW' AND deleted_flag=0 LIMIT 1),'设计规格待受控冻结',(SELECT `unit_id` FROM `mdm_unit` WHERE `unit_code`='LAMP-BLOCK' AND deleted_flag=0 LIMIT 1),'RAW','DISCRETE',1,'ENABLE','LAMP_DESIGN_V1: 教学设计，投产前冻结规格与检验标准',NOW()
WHERE NOT EXISTS(SELECT 1 FROM `mdm_material` WHERE `material_code`='LAMP-M10');
INSERT INTO `mdm_material` (`material_code`,`material_name`,`category_id`,`spec_model`,`unit_id`,`material_type`,`quantity_mode`,`issue_step`,`status`,remark,created_time)
SELECT 'LAMP-M11','底盖',(SELECT `category_id` FROM `mdm_material_category` WHERE `category_code`='LAMP-RAW' AND deleted_flag=0 LIMIT 1),'设计规格待受控冻结',(SELECT `unit_id` FROM `mdm_unit` WHERE `unit_code`='LAMP-PC' AND deleted_flag=0 LIMIT 1),'RAW','DISCRETE',1,'ENABLE','LAMP_DESIGN_V1: 教学设计，投产前冻结规格与检验标准',NOW()
WHERE NOT EXISTS(SELECT 1 FROM `mdm_material` WHERE `material_code`='LAMP-M11');
INSERT INTO `mdm_material` (`material_code`,`material_name`,`category_id`,`spec_model`,`unit_id`,`material_type`,`quantity_mode`,`issue_step`,`status`,remark,created_time)
SELECT 'LAMP-M12','上灯臂',(SELECT `category_id` FROM `mdm_material_category` WHERE `category_code`='LAMP-RAW' AND deleted_flag=0 LIMIT 1),'设计规格待受控冻结',(SELECT `unit_id` FROM `mdm_unit` WHERE `unit_code`='LAMP-PART' AND deleted_flag=0 LIMIT 1),'RAW','DISCRETE',1,'ENABLE','LAMP_DESIGN_V1: 教学设计，投产前冻结规格与检验标准',NOW()
WHERE NOT EXISTS(SELECT 1 FROM `mdm_material` WHERE `material_code`='LAMP-M12');
INSERT INTO `mdm_material` (`material_code`,`material_name`,`category_id`,`spec_model`,`unit_id`,`material_type`,`quantity_mode`,`issue_step`,`status`,remark,created_time)
SELECT 'LAMP-M13','下灯臂',(SELECT `category_id` FROM `mdm_material_category` WHERE `category_code`='LAMP-RAW' AND deleted_flag=0 LIMIT 1),'设计规格待受控冻结',(SELECT `unit_id` FROM `mdm_unit` WHERE `unit_code`='LAMP-PART' AND deleted_flag=0 LIMIT 1),'RAW','DISCRETE',1,'ENABLE','LAMP_DESIGN_V1: 教学设计，投产前冻结规格与检验标准',NOW()
WHERE NOT EXISTS(SELECT 1 FROM `mdm_material` WHERE `material_code`='LAMP-M13');
INSERT INTO `mdm_material` (`material_code`,`material_name`,`category_id`,`spec_model`,`unit_id`,`material_type`,`quantity_mode`,`issue_step`,`status`,remark,created_time)
SELECT 'LAMP-M14','连接转轴',(SELECT `category_id` FROM `mdm_material_category` WHERE `category_code`='LAMP-RAW' AND deleted_flag=0 LIMIT 1),'设计规格待受控冻结',(SELECT `unit_id` FROM `mdm_unit` WHERE `unit_code`='LAMP-PC' AND deleted_flag=0 LIMIT 1),'RAW','DISCRETE',1,'ENABLE','LAMP_DESIGN_V1: 教学设计，投产前冻结规格与检验标准',NOW()
WHERE NOT EXISTS(SELECT 1 FROM `mdm_material` WHERE `material_code`='LAMP-M14');
INSERT INTO `mdm_material` (`material_code`,`material_name`,`category_id`,`spec_model`,`unit_id`,`material_type`,`quantity_mode`,`issue_step`,`status`,remark,created_time)
SELECT 'LAMP-M15','连接线束',(SELECT `category_id` FROM `mdm_material_category` WHERE `category_code`='LAMP-RAW' AND deleted_flag=0 LIMIT 1),'设计规格待受控冻结',(SELECT `unit_id` FROM `mdm_unit` WHERE `unit_code`='LAMP-SET' AND deleted_flag=0 LIMIT 1),'RAW','DISCRETE',1,'ENABLE','LAMP_DESIGN_V1: 教学设计，投产前冻结规格与检验标准',NOW()
WHERE NOT EXISTS(SELECT 1 FROM `mdm_material` WHERE `material_code`='LAMP-M15');
INSERT INTO `mdm_material` (`material_code`,`material_name`,`category_id`,`spec_model`,`unit_id`,`material_type`,`quantity_mode`,`issue_step`,`status`,remark,created_time)
SELECT 'LAMP-M16','电源适配器',(SELECT `category_id` FROM `mdm_material_category` WHERE `category_code`='LAMP-RAW' AND deleted_flag=0 LIMIT 1),'设计规格待受控冻结',(SELECT `unit_id` FROM `mdm_unit` WHERE `unit_code`='LAMP-PC' AND deleted_flag=0 LIMIT 1),'RAW','DISCRETE',1,'ENABLE','LAMP_DESIGN_V1: 教学设计，投产前冻结规格与检验标准',NOW()
WHERE NOT EXISTS(SELECT 1 FROM `mdm_material` WHERE `material_code`='LAMP-M16');
INSERT INTO `mdm_material` (`material_code`,`material_name`,`category_id`,`spec_model`,`unit_id`,`material_type`,`quantity_mode`,`issue_step`,`status`,remark,created_time)
SELECT 'LAMP-M17','防滑脚垫',(SELECT `category_id` FROM `mdm_material_category` WHERE `category_code`='LAMP-AUX' AND deleted_flag=0 LIMIT 1),'设计规格待受控冻结',(SELECT `unit_id` FROM `mdm_unit` WHERE `unit_code`='LAMP-PC' AND deleted_flag=0 LIMIT 1),'AUXILIARY','DISCRETE',1,'ENABLE','LAMP_DESIGN_V1: 教学设计，投产前冻结规格与检验标准',NOW()
WHERE NOT EXISTS(SELECT 1 FROM `mdm_material` WHERE `material_code`='LAMP-M17');
INSERT INTO `mdm_material` (`material_code`,`material_name`,`category_id`,`spec_model`,`unit_id`,`material_type`,`quantity_mode`,`issue_step`,`status`,remark,created_time)
SELECT 'LAMP-M18','M3×8紧固螺钉',(SELECT `category_id` FROM `mdm_material_category` WHERE `category_code`='LAMP-AUX' AND deleted_flag=0 LIMIT 1),'设计规格待受控冻结',(SELECT `unit_id` FROM `mdm_unit` WHERE `unit_code`='LAMP-SCREW' AND deleted_flag=0 LIMIT 1),'AUXILIARY','DISCRETE',1,'ENABLE','LAMP_DESIGN_V1: 教学设计，投产前冻结规格与检验标准',NOW()
WHERE NOT EXISTS(SELECT 1 FROM `mdm_material` WHERE `material_code`='LAMP-M18');
INSERT INTO `mdm_material` (`material_code`,`material_name`,`category_id`,`spec_model`,`unit_id`,`material_type`,`quantity_mode`,`issue_step`,`status`,remark,created_time)
SELECT 'LAMP-HEAD','共用灯头组件',(SELECT `category_id` FROM `mdm_material_category` WHERE `category_code`='LAMP-SEMI' AND deleted_flag=0 LIMIT 1),'设计规格待受控冻结',(SELECT `unit_id` FROM `mdm_unit` WHERE `unit_code`='LAMP-SET' AND deleted_flag=0 LIMIT 1),'SEMI_FINISHED','DISCRETE',1,'ENABLE','LAMP_DESIGN_V1: 教学设计，投产前冻结规格与检验标准',NOW()
WHERE NOT EXISTS(SELECT 1 FROM `mdm_material` WHERE `material_code`='LAMP-HEAD');
INSERT INTO `mdm_product` (`product_code`,`product_name`,`product_model`,`material_id`,`status`,remark,created_time)
SELECT 'LAMP-HEAD','共用灯头组件','HEAD',(SELECT `material_id` FROM `mdm_material` WHERE `material_code`='LAMP-HEAD' AND deleted_flag=0 LIMIT 1),'ENABLE','LAMP_DESIGN_V1: 教学设计，投产前冻结规格与检验标准',NOW()
WHERE NOT EXISTS(SELECT 1 FROM `mdm_product` WHERE `product_code`='LAMP-HEAD');
INSERT INTO `mdm_bom` (`bom_code`,`product_id`,`bom_version`,`effective_date`,`status`,remark,created_time)
SELECT 'LAMP-BOM-HEAD-V1',(SELECT `product_id` FROM `mdm_product` WHERE `product_code`='LAMP-HEAD' AND deleted_flag=0 LIMIT 1),'V1.0','2026-10-18','RELEASED','LAMP_DESIGN_V1: 教学设计，投产前冻结规格与检验标准',NOW()
WHERE NOT EXISTS(SELECT 1 FROM `mdm_bom` WHERE `bom_code`='LAMP-BOM-HEAD-V1');
INSERT INTO `mdm_material` (`material_code`,`material_name`,`category_id`,`spec_model`,`unit_id`,`material_type`,`quantity_mode`,`issue_step`,`status`,remark,created_time)
SELECT 'LAMP-ARM','共用灯臂组件',(SELECT `category_id` FROM `mdm_material_category` WHERE `category_code`='LAMP-SEMI' AND deleted_flag=0 LIMIT 1),'设计规格待受控冻结',(SELECT `unit_id` FROM `mdm_unit` WHERE `unit_code`='LAMP-SET' AND deleted_flag=0 LIMIT 1),'SEMI_FINISHED','DISCRETE',1,'ENABLE','LAMP_DESIGN_V1: 教学设计，投产前冻结规格与检验标准',NOW()
WHERE NOT EXISTS(SELECT 1 FROM `mdm_material` WHERE `material_code`='LAMP-ARM');
INSERT INTO `mdm_product` (`product_code`,`product_name`,`product_model`,`material_id`,`status`,remark,created_time)
SELECT 'LAMP-ARM','共用灯臂组件','ARM',(SELECT `material_id` FROM `mdm_material` WHERE `material_code`='LAMP-ARM' AND deleted_flag=0 LIMIT 1),'ENABLE','LAMP_DESIGN_V1: 教学设计，投产前冻结规格与检验标准',NOW()
WHERE NOT EXISTS(SELECT 1 FROM `mdm_product` WHERE `product_code`='LAMP-ARM');
INSERT INTO `mdm_bom` (`bom_code`,`product_id`,`bom_version`,`effective_date`,`status`,remark,created_time)
SELECT 'LAMP-BOM-ARM-V1',(SELECT `product_id` FROM `mdm_product` WHERE `product_code`='LAMP-ARM' AND deleted_flag=0 LIMIT 1),'V1.0','2026-10-18','RELEASED','LAMP_DESIGN_V1: 教学设计，投产前冻结规格与检验标准',NOW()
WHERE NOT EXISTS(SELECT 1 FROM `mdm_bom` WHERE `bom_code`='LAMP-BOM-ARM-V1');
INSERT INTO `mdm_material` (`material_code`,`material_name`,`category_id`,`spec_model`,`unit_id`,`material_type`,`quantity_mode`,`issue_step`,`status`,remark,created_time)
SELECT 'LAMP-BASE-L100','L100触控控制底座',(SELECT `category_id` FROM `mdm_material_category` WHERE `category_code`='LAMP-SEMI' AND deleted_flag=0 LIMIT 1),'设计规格待受控冻结',(SELECT `unit_id` FROM `mdm_unit` WHERE `unit_code`='LAMP-SET' AND deleted_flag=0 LIMIT 1),'SEMI_FINISHED','DISCRETE',1,'ENABLE','LAMP_DESIGN_V1: 教学设计，投产前冻结规格与检验标准',NOW()
WHERE NOT EXISTS(SELECT 1 FROM `mdm_material` WHERE `material_code`='LAMP-BASE-L100');
INSERT INTO `mdm_product` (`product_code`,`product_name`,`product_model`,`material_id`,`status`,remark,created_time)
SELECT 'LAMP-BASE-L100','L100触控控制底座','BASE-L100',(SELECT `material_id` FROM `mdm_material` WHERE `material_code`='LAMP-BASE-L100' AND deleted_flag=0 LIMIT 1),'ENABLE','LAMP_DESIGN_V1: 教学设计，投产前冻结规格与检验标准',NOW()
WHERE NOT EXISTS(SELECT 1 FROM `mdm_product` WHERE `product_code`='LAMP-BASE-L100');
INSERT INTO `mdm_bom` (`bom_code`,`product_id`,`bom_version`,`effective_date`,`status`,remark,created_time)
SELECT 'LAMP-BOM-BASE-L100-V1',(SELECT `product_id` FROM `mdm_product` WHERE `product_code`='LAMP-BASE-L100' AND deleted_flag=0 LIMIT 1),'V1.0','2026-10-18','RELEASED','LAMP_DESIGN_V1: 教学设计，投产前冻结规格与检验标准',NOW()
WHERE NOT EXISTS(SELECT 1 FROM `mdm_bom` WHERE `bom_code`='LAMP-BOM-BASE-L100-V1');
INSERT INTO `mdm_material` (`material_code`,`material_name`,`category_id`,`spec_model`,`unit_id`,`material_type`,`quantity_mode`,`issue_step`,`status`,remark,created_time)
SELECT 'LAMP-BASE-L200','L200自动调光控制底座',(SELECT `category_id` FROM `mdm_material_category` WHERE `category_code`='LAMP-SEMI' AND deleted_flag=0 LIMIT 1),'设计规格待受控冻结',(SELECT `unit_id` FROM `mdm_unit` WHERE `unit_code`='LAMP-SET' AND deleted_flag=0 LIMIT 1),'SEMI_FINISHED','DISCRETE',1,'ENABLE','LAMP_DESIGN_V1: 教学设计，投产前冻结规格与检验标准',NOW()
WHERE NOT EXISTS(SELECT 1 FROM `mdm_material` WHERE `material_code`='LAMP-BASE-L200');
INSERT INTO `mdm_product` (`product_code`,`product_name`,`product_model`,`material_id`,`status`,remark,created_time)
SELECT 'LAMP-BASE-L200','L200自动调光控制底座','BASE-L200',(SELECT `material_id` FROM `mdm_material` WHERE `material_code`='LAMP-BASE-L200' AND deleted_flag=0 LIMIT 1),'ENABLE','LAMP_DESIGN_V1: 教学设计，投产前冻结规格与检验标准',NOW()
WHERE NOT EXISTS(SELECT 1 FROM `mdm_product` WHERE `product_code`='LAMP-BASE-L200');
INSERT INTO `mdm_bom` (`bom_code`,`product_id`,`bom_version`,`effective_date`,`status`,remark,created_time)
SELECT 'LAMP-BOM-BASE-L200-V1',(SELECT `product_id` FROM `mdm_product` WHERE `product_code`='LAMP-BASE-L200' AND deleted_flag=0 LIMIT 1),'V1.0','2026-10-18','RELEASED','LAMP_DESIGN_V1: 教学设计，投产前冻结规格与检验标准',NOW()
WHERE NOT EXISTS(SELECT 1 FROM `mdm_bom` WHERE `bom_code`='LAMP-BOM-BASE-L200-V1');
INSERT INTO `mdm_material` (`material_code`,`material_name`,`category_id`,`spec_model`,`unit_id`,`material_type`,`quantity_mode`,`issue_step`,`status`,remark,created_time)
SELECT 'LAMP-L100','L100触控调光台灯',(SELECT `category_id` FROM `mdm_material_category` WHERE `category_code`='LAMP-FG' AND deleted_flag=0 LIMIT 1),'设计规格待受控冻结',(SELECT `unit_id` FROM `mdm_unit` WHERE `unit_code`='LAMP-TAI' AND deleted_flag=0 LIMIT 1),'FINISHED','DISCRETE',1,'ENABLE','LAMP_DESIGN_V1: 教学设计，投产前冻结规格与检验标准',NOW()
WHERE NOT EXISTS(SELECT 1 FROM `mdm_material` WHERE `material_code`='LAMP-L100');
INSERT INTO `mdm_product` (`product_code`,`product_name`,`product_model`,`material_id`,`status`,remark,created_time)
SELECT 'LAMP-L100','L100触控调光台灯','L100',(SELECT `material_id` FROM `mdm_material` WHERE `material_code`='LAMP-L100' AND deleted_flag=0 LIMIT 1),'ENABLE','LAMP_DESIGN_V1: 教学设计，投产前冻结规格与检验标准',NOW()
WHERE NOT EXISTS(SELECT 1 FROM `mdm_product` WHERE `product_code`='LAMP-L100');
INSERT INTO `mdm_bom` (`bom_code`,`product_id`,`bom_version`,`effective_date`,`status`,remark,created_time)
SELECT 'LAMP-BOM-L100-V1',(SELECT `product_id` FROM `mdm_product` WHERE `product_code`='LAMP-L100' AND deleted_flag=0 LIMIT 1),'V1.0','2026-10-18','RELEASED','LAMP_DESIGN_V1: 教学设计，投产前冻结规格与检验标准',NOW()
WHERE NOT EXISTS(SELECT 1 FROM `mdm_bom` WHERE `bom_code`='LAMP-BOM-L100-V1');
INSERT INTO `mdm_material` (`material_code`,`material_name`,`category_id`,`spec_model`,`unit_id`,`material_type`,`quantity_mode`,`issue_step`,`status`,remark,created_time)
SELECT 'LAMP-L200','L200自动调光台灯',(SELECT `category_id` FROM `mdm_material_category` WHERE `category_code`='LAMP-FG' AND deleted_flag=0 LIMIT 1),'设计规格待受控冻结',(SELECT `unit_id` FROM `mdm_unit` WHERE `unit_code`='LAMP-TAI' AND deleted_flag=0 LIMIT 1),'FINISHED','DISCRETE',1,'ENABLE','LAMP_DESIGN_V1: 教学设计，投产前冻结规格与检验标准',NOW()
WHERE NOT EXISTS(SELECT 1 FROM `mdm_material` WHERE `material_code`='LAMP-L200');
INSERT INTO `mdm_product` (`product_code`,`product_name`,`product_model`,`material_id`,`status`,remark,created_time)
SELECT 'LAMP-L200','L200自动调光台灯','L200',(SELECT `material_id` FROM `mdm_material` WHERE `material_code`='LAMP-L200' AND deleted_flag=0 LIMIT 1),'ENABLE','LAMP_DESIGN_V1: 教学设计，投产前冻结规格与检验标准',NOW()
WHERE NOT EXISTS(SELECT 1 FROM `mdm_product` WHERE `product_code`='LAMP-L200');
INSERT INTO `mdm_bom` (`bom_code`,`product_id`,`bom_version`,`effective_date`,`status`,remark,created_time)
SELECT 'LAMP-BOM-L200-V1',(SELECT `product_id` FROM `mdm_product` WHERE `product_code`='LAMP-L200' AND deleted_flag=0 LIMIT 1),'V1.0','2026-10-18','RELEASED','LAMP_DESIGN_V1: 教学设计，投产前冻结规格与检验标准',NOW()
WHERE NOT EXISTS(SELECT 1 FROM `mdm_bom` WHERE `bom_code`='LAMP-BOM-L200-V1');
INSERT INTO mdm_bom_item(bom_id,material_id,child_bom_id,quantity,loss_rate,sequence_no,remark,created_time)
SELECT (SELECT `bom_id` FROM `mdm_bom` WHERE `bom_code`='LAMP-BOM-HEAD-V1' AND deleted_flag=0 LIMIT 1),(SELECT `material_id` FROM `mdm_material` WHERE `material_code`='LAMP-M01' AND deleted_flag=0 LIMIT 1),NULL,1,0,1,'LAMP_DESIGN_V1',NOW()
WHERE NOT EXISTS(SELECT 1 FROM mdm_bom_item WHERE bom_id=(SELECT `bom_id` FROM `mdm_bom` WHERE `bom_code`='LAMP-BOM-HEAD-V1' AND deleted_flag=0 LIMIT 1) AND material_id=(SELECT `material_id` FROM `mdm_material` WHERE `material_code`='LAMP-M01' AND deleted_flag=0 LIMIT 1));
INSERT INTO mdm_bom_item(bom_id,material_id,child_bom_id,quantity,loss_rate,sequence_no,remark,created_time)
SELECT (SELECT `bom_id` FROM `mdm_bom` WHERE `bom_code`='LAMP-BOM-HEAD-V1' AND deleted_flag=0 LIMIT 1),(SELECT `material_id` FROM `mdm_material` WHERE `material_code`='LAMP-M02' AND deleted_flag=0 LIMIT 1),NULL,1,0,2,'LAMP_DESIGN_V1',NOW()
WHERE NOT EXISTS(SELECT 1 FROM mdm_bom_item WHERE bom_id=(SELECT `bom_id` FROM `mdm_bom` WHERE `bom_code`='LAMP-BOM-HEAD-V1' AND deleted_flag=0 LIMIT 1) AND material_id=(SELECT `material_id` FROM `mdm_material` WHERE `material_code`='LAMP-M02' AND deleted_flag=0 LIMIT 1));
INSERT INTO mdm_bom_item(bom_id,material_id,child_bom_id,quantity,loss_rate,sequence_no,remark,created_time)
SELECT (SELECT `bom_id` FROM `mdm_bom` WHERE `bom_code`='LAMP-BOM-HEAD-V1' AND deleted_flag=0 LIMIT 1),(SELECT `material_id` FROM `mdm_material` WHERE `material_code`='LAMP-M03' AND deleted_flag=0 LIMIT 1),NULL,1,0,3,'LAMP_DESIGN_V1',NOW()
WHERE NOT EXISTS(SELECT 1 FROM mdm_bom_item WHERE bom_id=(SELECT `bom_id` FROM `mdm_bom` WHERE `bom_code`='LAMP-BOM-HEAD-V1' AND deleted_flag=0 LIMIT 1) AND material_id=(SELECT `material_id` FROM `mdm_material` WHERE `material_code`='LAMP-M03' AND deleted_flag=0 LIMIT 1));
INSERT INTO mdm_bom_item(bom_id,material_id,child_bom_id,quantity,loss_rate,sequence_no,remark,created_time)
SELECT (SELECT `bom_id` FROM `mdm_bom` WHERE `bom_code`='LAMP-BOM-HEAD-V1' AND deleted_flag=0 LIMIT 1),(SELECT `material_id` FROM `mdm_material` WHERE `material_code`='LAMP-M04' AND deleted_flag=0 LIMIT 1),NULL,1,0,4,'LAMP_DESIGN_V1',NOW()
WHERE NOT EXISTS(SELECT 1 FROM mdm_bom_item WHERE bom_id=(SELECT `bom_id` FROM `mdm_bom` WHERE `bom_code`='LAMP-BOM-HEAD-V1' AND deleted_flag=0 LIMIT 1) AND material_id=(SELECT `material_id` FROM `mdm_material` WHERE `material_code`='LAMP-M04' AND deleted_flag=0 LIMIT 1));
INSERT INTO mdm_bom_item(bom_id,material_id,child_bom_id,quantity,loss_rate,sequence_no,remark,created_time)
SELECT (SELECT `bom_id` FROM `mdm_bom` WHERE `bom_code`='LAMP-BOM-HEAD-V1' AND deleted_flag=0 LIMIT 1),(SELECT `material_id` FROM `mdm_material` WHERE `material_code`='LAMP-M18' AND deleted_flag=0 LIMIT 1),NULL,2,0,5,'LAMP_DESIGN_V1',NOW()
WHERE NOT EXISTS(SELECT 1 FROM mdm_bom_item WHERE bom_id=(SELECT `bom_id` FROM `mdm_bom` WHERE `bom_code`='LAMP-BOM-HEAD-V1' AND deleted_flag=0 LIMIT 1) AND material_id=(SELECT `material_id` FROM `mdm_material` WHERE `material_code`='LAMP-M18' AND deleted_flag=0 LIMIT 1));
INSERT INTO mdm_bom_item(bom_id,material_id,child_bom_id,quantity,loss_rate,sequence_no,remark,created_time)
SELECT (SELECT `bom_id` FROM `mdm_bom` WHERE `bom_code`='LAMP-BOM-ARM-V1' AND deleted_flag=0 LIMIT 1),(SELECT `material_id` FROM `mdm_material` WHERE `material_code`='LAMP-M12' AND deleted_flag=0 LIMIT 1),NULL,1,0,1,'LAMP_DESIGN_V1',NOW()
WHERE NOT EXISTS(SELECT 1 FROM mdm_bom_item WHERE bom_id=(SELECT `bom_id` FROM `mdm_bom` WHERE `bom_code`='LAMP-BOM-ARM-V1' AND deleted_flag=0 LIMIT 1) AND material_id=(SELECT `material_id` FROM `mdm_material` WHERE `material_code`='LAMP-M12' AND deleted_flag=0 LIMIT 1));
INSERT INTO mdm_bom_item(bom_id,material_id,child_bom_id,quantity,loss_rate,sequence_no,remark,created_time)
SELECT (SELECT `bom_id` FROM `mdm_bom` WHERE `bom_code`='LAMP-BOM-ARM-V1' AND deleted_flag=0 LIMIT 1),(SELECT `material_id` FROM `mdm_material` WHERE `material_code`='LAMP-M13' AND deleted_flag=0 LIMIT 1),NULL,1,0,2,'LAMP_DESIGN_V1',NOW()
WHERE NOT EXISTS(SELECT 1 FROM mdm_bom_item WHERE bom_id=(SELECT `bom_id` FROM `mdm_bom` WHERE `bom_code`='LAMP-BOM-ARM-V1' AND deleted_flag=0 LIMIT 1) AND material_id=(SELECT `material_id` FROM `mdm_material` WHERE `material_code`='LAMP-M13' AND deleted_flag=0 LIMIT 1));
INSERT INTO mdm_bom_item(bom_id,material_id,child_bom_id,quantity,loss_rate,sequence_no,remark,created_time)
SELECT (SELECT `bom_id` FROM `mdm_bom` WHERE `bom_code`='LAMP-BOM-ARM-V1' AND deleted_flag=0 LIMIT 1),(SELECT `material_id` FROM `mdm_material` WHERE `material_code`='LAMP-M14' AND deleted_flag=0 LIMIT 1),NULL,2,0,3,'LAMP_DESIGN_V1',NOW()
WHERE NOT EXISTS(SELECT 1 FROM mdm_bom_item WHERE bom_id=(SELECT `bom_id` FROM `mdm_bom` WHERE `bom_code`='LAMP-BOM-ARM-V1' AND deleted_flag=0 LIMIT 1) AND material_id=(SELECT `material_id` FROM `mdm_material` WHERE `material_code`='LAMP-M14' AND deleted_flag=0 LIMIT 1));
INSERT INTO mdm_bom_item(bom_id,material_id,child_bom_id,quantity,loss_rate,sequence_no,remark,created_time)
SELECT (SELECT `bom_id` FROM `mdm_bom` WHERE `bom_code`='LAMP-BOM-ARM-V1' AND deleted_flag=0 LIMIT 1),(SELECT `material_id` FROM `mdm_material` WHERE `material_code`='LAMP-M18' AND deleted_flag=0 LIMIT 1),NULL,2,0,4,'LAMP_DESIGN_V1',NOW()
WHERE NOT EXISTS(SELECT 1 FROM mdm_bom_item WHERE bom_id=(SELECT `bom_id` FROM `mdm_bom` WHERE `bom_code`='LAMP-BOM-ARM-V1' AND deleted_flag=0 LIMIT 1) AND material_id=(SELECT `material_id` FROM `mdm_material` WHERE `material_code`='LAMP-M18' AND deleted_flag=0 LIMIT 1));
INSERT INTO mdm_bom_item(bom_id,material_id,child_bom_id,quantity,loss_rate,sequence_no,remark,created_time)
SELECT (SELECT `bom_id` FROM `mdm_bom` WHERE `bom_code`='LAMP-BOM-BASE-L100-V1' AND deleted_flag=0 LIMIT 1),(SELECT `material_id` FROM `mdm_material` WHERE `material_code`='LAMP-M05' AND deleted_flag=0 LIMIT 1),NULL,1,0,1,'LAMP_DESIGN_V1',NOW()
WHERE NOT EXISTS(SELECT 1 FROM mdm_bom_item WHERE bom_id=(SELECT `bom_id` FROM `mdm_bom` WHERE `bom_code`='LAMP-BOM-BASE-L100-V1' AND deleted_flag=0 LIMIT 1) AND material_id=(SELECT `material_id` FROM `mdm_material` WHERE `material_code`='LAMP-M05' AND deleted_flag=0 LIMIT 1));
INSERT INTO mdm_bom_item(bom_id,material_id,child_bom_id,quantity,loss_rate,sequence_no,remark,created_time)
SELECT (SELECT `bom_id` FROM `mdm_bom` WHERE `bom_code`='LAMP-BOM-BASE-L100-V1' AND deleted_flag=0 LIMIT 1),(SELECT `material_id` FROM `mdm_material` WHERE `material_code`='LAMP-M06' AND deleted_flag=0 LIMIT 1),NULL,1,0,2,'LAMP_DESIGN_V1',NOW()
WHERE NOT EXISTS(SELECT 1 FROM mdm_bom_item WHERE bom_id=(SELECT `bom_id` FROM `mdm_bom` WHERE `bom_code`='LAMP-BOM-BASE-L100-V1' AND deleted_flag=0 LIMIT 1) AND material_id=(SELECT `material_id` FROM `mdm_material` WHERE `material_code`='LAMP-M06' AND deleted_flag=0 LIMIT 1));
INSERT INTO mdm_bom_item(bom_id,material_id,child_bom_id,quantity,loss_rate,sequence_no,remark,created_time)
SELECT (SELECT `bom_id` FROM `mdm_bom` WHERE `bom_code`='LAMP-BOM-BASE-L100-V1' AND deleted_flag=0 LIMIT 1),(SELECT `material_id` FROM `mdm_material` WHERE `material_code`='LAMP-M08' AND deleted_flag=0 LIMIT 1),NULL,1,0,3,'LAMP_DESIGN_V1',NOW()
WHERE NOT EXISTS(SELECT 1 FROM mdm_bom_item WHERE bom_id=(SELECT `bom_id` FROM `mdm_bom` WHERE `bom_code`='LAMP-BOM-BASE-L100-V1' AND deleted_flag=0 LIMIT 1) AND material_id=(SELECT `material_id` FROM `mdm_material` WHERE `material_code`='LAMP-M08' AND deleted_flag=0 LIMIT 1));
INSERT INTO mdm_bom_item(bom_id,material_id,child_bom_id,quantity,loss_rate,sequence_no,remark,created_time)
SELECT (SELECT `bom_id` FROM `mdm_bom` WHERE `bom_code`='LAMP-BOM-BASE-L100-V1' AND deleted_flag=0 LIMIT 1),(SELECT `material_id` FROM `mdm_material` WHERE `material_code`='LAMP-M09' AND deleted_flag=0 LIMIT 1),NULL,1,0,4,'LAMP_DESIGN_V1',NOW()
WHERE NOT EXISTS(SELECT 1 FROM mdm_bom_item WHERE bom_id=(SELECT `bom_id` FROM `mdm_bom` WHERE `bom_code`='LAMP-BOM-BASE-L100-V1' AND deleted_flag=0 LIMIT 1) AND material_id=(SELECT `material_id` FROM `mdm_material` WHERE `material_code`='LAMP-M09' AND deleted_flag=0 LIMIT 1));
INSERT INTO mdm_bom_item(bom_id,material_id,child_bom_id,quantity,loss_rate,sequence_no,remark,created_time)
SELECT (SELECT `bom_id` FROM `mdm_bom` WHERE `bom_code`='LAMP-BOM-BASE-L100-V1' AND deleted_flag=0 LIMIT 1),(SELECT `material_id` FROM `mdm_material` WHERE `material_code`='LAMP-M10' AND deleted_flag=0 LIMIT 1),NULL,1,0,5,'LAMP_DESIGN_V1',NOW()
WHERE NOT EXISTS(SELECT 1 FROM mdm_bom_item WHERE bom_id=(SELECT `bom_id` FROM `mdm_bom` WHERE `bom_code`='LAMP-BOM-BASE-L100-V1' AND deleted_flag=0 LIMIT 1) AND material_id=(SELECT `material_id` FROM `mdm_material` WHERE `material_code`='LAMP-M10' AND deleted_flag=0 LIMIT 1));
INSERT INTO mdm_bom_item(bom_id,material_id,child_bom_id,quantity,loss_rate,sequence_no,remark,created_time)
SELECT (SELECT `bom_id` FROM `mdm_bom` WHERE `bom_code`='LAMP-BOM-BASE-L100-V1' AND deleted_flag=0 LIMIT 1),(SELECT `material_id` FROM `mdm_material` WHERE `material_code`='LAMP-M11' AND deleted_flag=0 LIMIT 1),NULL,1,0,6,'LAMP_DESIGN_V1',NOW()
WHERE NOT EXISTS(SELECT 1 FROM mdm_bom_item WHERE bom_id=(SELECT `bom_id` FROM `mdm_bom` WHERE `bom_code`='LAMP-BOM-BASE-L100-V1' AND deleted_flag=0 LIMIT 1) AND material_id=(SELECT `material_id` FROM `mdm_material` WHERE `material_code`='LAMP-M11' AND deleted_flag=0 LIMIT 1));
INSERT INTO mdm_bom_item(bom_id,material_id,child_bom_id,quantity,loss_rate,sequence_no,remark,created_time)
SELECT (SELECT `bom_id` FROM `mdm_bom` WHERE `bom_code`='LAMP-BOM-BASE-L100-V1' AND deleted_flag=0 LIMIT 1),(SELECT `material_id` FROM `mdm_material` WHERE `material_code`='LAMP-M18' AND deleted_flag=0 LIMIT 1),NULL,2,0,7,'LAMP_DESIGN_V1',NOW()
WHERE NOT EXISTS(SELECT 1 FROM mdm_bom_item WHERE bom_id=(SELECT `bom_id` FROM `mdm_bom` WHERE `bom_code`='LAMP-BOM-BASE-L100-V1' AND deleted_flag=0 LIMIT 1) AND material_id=(SELECT `material_id` FROM `mdm_material` WHERE `material_code`='LAMP-M18' AND deleted_flag=0 LIMIT 1));
INSERT INTO mdm_bom_item(bom_id,material_id,child_bom_id,quantity,loss_rate,sequence_no,remark,created_time)
SELECT (SELECT `bom_id` FROM `mdm_bom` WHERE `bom_code`='LAMP-BOM-BASE-L200-V1' AND deleted_flag=0 LIMIT 1),(SELECT `material_id` FROM `mdm_material` WHERE `material_code`='LAMP-M05' AND deleted_flag=0 LIMIT 1),NULL,1,0,1,'LAMP_DESIGN_V1',NOW()
WHERE NOT EXISTS(SELECT 1 FROM mdm_bom_item WHERE bom_id=(SELECT `bom_id` FROM `mdm_bom` WHERE `bom_code`='LAMP-BOM-BASE-L200-V1' AND deleted_flag=0 LIMIT 1) AND material_id=(SELECT `material_id` FROM `mdm_material` WHERE `material_code`='LAMP-M05' AND deleted_flag=0 LIMIT 1));
INSERT INTO mdm_bom_item(bom_id,material_id,child_bom_id,quantity,loss_rate,sequence_no,remark,created_time)
SELECT (SELECT `bom_id` FROM `mdm_bom` WHERE `bom_code`='LAMP-BOM-BASE-L200-V1' AND deleted_flag=0 LIMIT 1),(SELECT `material_id` FROM `mdm_material` WHERE `material_code`='LAMP-M06' AND deleted_flag=0 LIMIT 1),NULL,1,0,2,'LAMP_DESIGN_V1',NOW()
WHERE NOT EXISTS(SELECT 1 FROM mdm_bom_item WHERE bom_id=(SELECT `bom_id` FROM `mdm_bom` WHERE `bom_code`='LAMP-BOM-BASE-L200-V1' AND deleted_flag=0 LIMIT 1) AND material_id=(SELECT `material_id` FROM `mdm_material` WHERE `material_code`='LAMP-M06' AND deleted_flag=0 LIMIT 1));
INSERT INTO mdm_bom_item(bom_id,material_id,child_bom_id,quantity,loss_rate,sequence_no,remark,created_time)
SELECT (SELECT `bom_id` FROM `mdm_bom` WHERE `bom_code`='LAMP-BOM-BASE-L200-V1' AND deleted_flag=0 LIMIT 1),(SELECT `material_id` FROM `mdm_material` WHERE `material_code`='LAMP-M07' AND deleted_flag=0 LIMIT 1),NULL,1,0,3,'LAMP_DESIGN_V1',NOW()
WHERE NOT EXISTS(SELECT 1 FROM mdm_bom_item WHERE bom_id=(SELECT `bom_id` FROM `mdm_bom` WHERE `bom_code`='LAMP-BOM-BASE-L200-V1' AND deleted_flag=0 LIMIT 1) AND material_id=(SELECT `material_id` FROM `mdm_material` WHERE `material_code`='LAMP-M07' AND deleted_flag=0 LIMIT 1));
INSERT INTO mdm_bom_item(bom_id,material_id,child_bom_id,quantity,loss_rate,sequence_no,remark,created_time)
SELECT (SELECT `bom_id` FROM `mdm_bom` WHERE `bom_code`='LAMP-BOM-BASE-L200-V1' AND deleted_flag=0 LIMIT 1),(SELECT `material_id` FROM `mdm_material` WHERE `material_code`='LAMP-M08' AND deleted_flag=0 LIMIT 1),NULL,1,0,4,'LAMP_DESIGN_V1',NOW()
WHERE NOT EXISTS(SELECT 1 FROM mdm_bom_item WHERE bom_id=(SELECT `bom_id` FROM `mdm_bom` WHERE `bom_code`='LAMP-BOM-BASE-L200-V1' AND deleted_flag=0 LIMIT 1) AND material_id=(SELECT `material_id` FROM `mdm_material` WHERE `material_code`='LAMP-M08' AND deleted_flag=0 LIMIT 1));
INSERT INTO mdm_bom_item(bom_id,material_id,child_bom_id,quantity,loss_rate,sequence_no,remark,created_time)
SELECT (SELECT `bom_id` FROM `mdm_bom` WHERE `bom_code`='LAMP-BOM-BASE-L200-V1' AND deleted_flag=0 LIMIT 1),(SELECT `material_id` FROM `mdm_material` WHERE `material_code`='LAMP-M09' AND deleted_flag=0 LIMIT 1),NULL,1,0,5,'LAMP_DESIGN_V1',NOW()
WHERE NOT EXISTS(SELECT 1 FROM mdm_bom_item WHERE bom_id=(SELECT `bom_id` FROM `mdm_bom` WHERE `bom_code`='LAMP-BOM-BASE-L200-V1' AND deleted_flag=0 LIMIT 1) AND material_id=(SELECT `material_id` FROM `mdm_material` WHERE `material_code`='LAMP-M09' AND deleted_flag=0 LIMIT 1));
INSERT INTO mdm_bom_item(bom_id,material_id,child_bom_id,quantity,loss_rate,sequence_no,remark,created_time)
SELECT (SELECT `bom_id` FROM `mdm_bom` WHERE `bom_code`='LAMP-BOM-BASE-L200-V1' AND deleted_flag=0 LIMIT 1),(SELECT `material_id` FROM `mdm_material` WHERE `material_code`='LAMP-M10' AND deleted_flag=0 LIMIT 1),NULL,1,0,6,'LAMP_DESIGN_V1',NOW()
WHERE NOT EXISTS(SELECT 1 FROM mdm_bom_item WHERE bom_id=(SELECT `bom_id` FROM `mdm_bom` WHERE `bom_code`='LAMP-BOM-BASE-L200-V1' AND deleted_flag=0 LIMIT 1) AND material_id=(SELECT `material_id` FROM `mdm_material` WHERE `material_code`='LAMP-M10' AND deleted_flag=0 LIMIT 1));
INSERT INTO mdm_bom_item(bom_id,material_id,child_bom_id,quantity,loss_rate,sequence_no,remark,created_time)
SELECT (SELECT `bom_id` FROM `mdm_bom` WHERE `bom_code`='LAMP-BOM-BASE-L200-V1' AND deleted_flag=0 LIMIT 1),(SELECT `material_id` FROM `mdm_material` WHERE `material_code`='LAMP-M11' AND deleted_flag=0 LIMIT 1),NULL,1,0,7,'LAMP_DESIGN_V1',NOW()
WHERE NOT EXISTS(SELECT 1 FROM mdm_bom_item WHERE bom_id=(SELECT `bom_id` FROM `mdm_bom` WHERE `bom_code`='LAMP-BOM-BASE-L200-V1' AND deleted_flag=0 LIMIT 1) AND material_id=(SELECT `material_id` FROM `mdm_material` WHERE `material_code`='LAMP-M11' AND deleted_flag=0 LIMIT 1));
INSERT INTO mdm_bom_item(bom_id,material_id,child_bom_id,quantity,loss_rate,sequence_no,remark,created_time)
SELECT (SELECT `bom_id` FROM `mdm_bom` WHERE `bom_code`='LAMP-BOM-BASE-L200-V1' AND deleted_flag=0 LIMIT 1),(SELECT `material_id` FROM `mdm_material` WHERE `material_code`='LAMP-M18' AND deleted_flag=0 LIMIT 1),NULL,2,0,8,'LAMP_DESIGN_V1',NOW()
WHERE NOT EXISTS(SELECT 1 FROM mdm_bom_item WHERE bom_id=(SELECT `bom_id` FROM `mdm_bom` WHERE `bom_code`='LAMP-BOM-BASE-L200-V1' AND deleted_flag=0 LIMIT 1) AND material_id=(SELECT `material_id` FROM `mdm_material` WHERE `material_code`='LAMP-M18' AND deleted_flag=0 LIMIT 1));
INSERT INTO mdm_bom_item(bom_id,material_id,child_bom_id,quantity,loss_rate,sequence_no,remark,created_time)
SELECT (SELECT `bom_id` FROM `mdm_bom` WHERE `bom_code`='LAMP-BOM-L100-V1' AND deleted_flag=0 LIMIT 1),(SELECT `material_id` FROM `mdm_material` WHERE `material_code`='LAMP-HEAD' AND deleted_flag=0 LIMIT 1),(SELECT `bom_id` FROM `mdm_bom` WHERE `bom_code`='LAMP-BOM-HEAD-V1' AND deleted_flag=0 LIMIT 1),1,0,1,'LAMP_DESIGN_V1',NOW()
WHERE NOT EXISTS(SELECT 1 FROM mdm_bom_item WHERE bom_id=(SELECT `bom_id` FROM `mdm_bom` WHERE `bom_code`='LAMP-BOM-L100-V1' AND deleted_flag=0 LIMIT 1) AND material_id=(SELECT `material_id` FROM `mdm_material` WHERE `material_code`='LAMP-HEAD' AND deleted_flag=0 LIMIT 1));
INSERT INTO mdm_bom_item(bom_id,material_id,child_bom_id,quantity,loss_rate,sequence_no,remark,created_time)
SELECT (SELECT `bom_id` FROM `mdm_bom` WHERE `bom_code`='LAMP-BOM-L100-V1' AND deleted_flag=0 LIMIT 1),(SELECT `material_id` FROM `mdm_material` WHERE `material_code`='LAMP-ARM' AND deleted_flag=0 LIMIT 1),(SELECT `bom_id` FROM `mdm_bom` WHERE `bom_code`='LAMP-BOM-ARM-V1' AND deleted_flag=0 LIMIT 1),1,0,2,'LAMP_DESIGN_V1',NOW()
WHERE NOT EXISTS(SELECT 1 FROM mdm_bom_item WHERE bom_id=(SELECT `bom_id` FROM `mdm_bom` WHERE `bom_code`='LAMP-BOM-L100-V1' AND deleted_flag=0 LIMIT 1) AND material_id=(SELECT `material_id` FROM `mdm_material` WHERE `material_code`='LAMP-ARM' AND deleted_flag=0 LIMIT 1));
INSERT INTO mdm_bom_item(bom_id,material_id,child_bom_id,quantity,loss_rate,sequence_no,remark,created_time)
SELECT (SELECT `bom_id` FROM `mdm_bom` WHERE `bom_code`='LAMP-BOM-L100-V1' AND deleted_flag=0 LIMIT 1),(SELECT `material_id` FROM `mdm_material` WHERE `material_code`='LAMP-BASE-L100' AND deleted_flag=0 LIMIT 1),(SELECT `bom_id` FROM `mdm_bom` WHERE `bom_code`='LAMP-BOM-BASE-L100-V1' AND deleted_flag=0 LIMIT 1),1,0,3,'LAMP_DESIGN_V1',NOW()
WHERE NOT EXISTS(SELECT 1 FROM mdm_bom_item WHERE bom_id=(SELECT `bom_id` FROM `mdm_bom` WHERE `bom_code`='LAMP-BOM-L100-V1' AND deleted_flag=0 LIMIT 1) AND material_id=(SELECT `material_id` FROM `mdm_material` WHERE `material_code`='LAMP-BASE-L100' AND deleted_flag=0 LIMIT 1));
INSERT INTO mdm_bom_item(bom_id,material_id,child_bom_id,quantity,loss_rate,sequence_no,remark,created_time)
SELECT (SELECT `bom_id` FROM `mdm_bom` WHERE `bom_code`='LAMP-BOM-L100-V1' AND deleted_flag=0 LIMIT 1),(SELECT `material_id` FROM `mdm_material` WHERE `material_code`='LAMP-M15' AND deleted_flag=0 LIMIT 1),NULL,1,0,4,'LAMP_DESIGN_V1',NOW()
WHERE NOT EXISTS(SELECT 1 FROM mdm_bom_item WHERE bom_id=(SELECT `bom_id` FROM `mdm_bom` WHERE `bom_code`='LAMP-BOM-L100-V1' AND deleted_flag=0 LIMIT 1) AND material_id=(SELECT `material_id` FROM `mdm_material` WHERE `material_code`='LAMP-M15' AND deleted_flag=0 LIMIT 1));
INSERT INTO mdm_bom_item(bom_id,material_id,child_bom_id,quantity,loss_rate,sequence_no,remark,created_time)
SELECT (SELECT `bom_id` FROM `mdm_bom` WHERE `bom_code`='LAMP-BOM-L100-V1' AND deleted_flag=0 LIMIT 1),(SELECT `material_id` FROM `mdm_material` WHERE `material_code`='LAMP-M16' AND deleted_flag=0 LIMIT 1),NULL,1,0,5,'LAMP_DESIGN_V1',NOW()
WHERE NOT EXISTS(SELECT 1 FROM mdm_bom_item WHERE bom_id=(SELECT `bom_id` FROM `mdm_bom` WHERE `bom_code`='LAMP-BOM-L100-V1' AND deleted_flag=0 LIMIT 1) AND material_id=(SELECT `material_id` FROM `mdm_material` WHERE `material_code`='LAMP-M16' AND deleted_flag=0 LIMIT 1));
INSERT INTO mdm_bom_item(bom_id,material_id,child_bom_id,quantity,loss_rate,sequence_no,remark,created_time)
SELECT (SELECT `bom_id` FROM `mdm_bom` WHERE `bom_code`='LAMP-BOM-L100-V1' AND deleted_flag=0 LIMIT 1),(SELECT `material_id` FROM `mdm_material` WHERE `material_code`='LAMP-M17' AND deleted_flag=0 LIMIT 1),NULL,4,0,6,'LAMP_DESIGN_V1',NOW()
WHERE NOT EXISTS(SELECT 1 FROM mdm_bom_item WHERE bom_id=(SELECT `bom_id` FROM `mdm_bom` WHERE `bom_code`='LAMP-BOM-L100-V1' AND deleted_flag=0 LIMIT 1) AND material_id=(SELECT `material_id` FROM `mdm_material` WHERE `material_code`='LAMP-M17' AND deleted_flag=0 LIMIT 1));
INSERT INTO mdm_bom_item(bom_id,material_id,child_bom_id,quantity,loss_rate,sequence_no,remark,created_time)
SELECT (SELECT `bom_id` FROM `mdm_bom` WHERE `bom_code`='LAMP-BOM-L100-V1' AND deleted_flag=0 LIMIT 1),(SELECT `material_id` FROM `mdm_material` WHERE `material_code`='LAMP-M18' AND deleted_flag=0 LIMIT 1),NULL,2,0,7,'LAMP_DESIGN_V1',NOW()
WHERE NOT EXISTS(SELECT 1 FROM mdm_bom_item WHERE bom_id=(SELECT `bom_id` FROM `mdm_bom` WHERE `bom_code`='LAMP-BOM-L100-V1' AND deleted_flag=0 LIMIT 1) AND material_id=(SELECT `material_id` FROM `mdm_material` WHERE `material_code`='LAMP-M18' AND deleted_flag=0 LIMIT 1));
INSERT INTO mdm_bom_item(bom_id,material_id,child_bom_id,quantity,loss_rate,sequence_no,remark,created_time)
SELECT (SELECT `bom_id` FROM `mdm_bom` WHERE `bom_code`='LAMP-BOM-L200-V1' AND deleted_flag=0 LIMIT 1),(SELECT `material_id` FROM `mdm_material` WHERE `material_code`='LAMP-HEAD' AND deleted_flag=0 LIMIT 1),(SELECT `bom_id` FROM `mdm_bom` WHERE `bom_code`='LAMP-BOM-HEAD-V1' AND deleted_flag=0 LIMIT 1),1,0,1,'LAMP_DESIGN_V1',NOW()
WHERE NOT EXISTS(SELECT 1 FROM mdm_bom_item WHERE bom_id=(SELECT `bom_id` FROM `mdm_bom` WHERE `bom_code`='LAMP-BOM-L200-V1' AND deleted_flag=0 LIMIT 1) AND material_id=(SELECT `material_id` FROM `mdm_material` WHERE `material_code`='LAMP-HEAD' AND deleted_flag=0 LIMIT 1));
INSERT INTO mdm_bom_item(bom_id,material_id,child_bom_id,quantity,loss_rate,sequence_no,remark,created_time)
SELECT (SELECT `bom_id` FROM `mdm_bom` WHERE `bom_code`='LAMP-BOM-L200-V1' AND deleted_flag=0 LIMIT 1),(SELECT `material_id` FROM `mdm_material` WHERE `material_code`='LAMP-ARM' AND deleted_flag=0 LIMIT 1),(SELECT `bom_id` FROM `mdm_bom` WHERE `bom_code`='LAMP-BOM-ARM-V1' AND deleted_flag=0 LIMIT 1),1,0,2,'LAMP_DESIGN_V1',NOW()
WHERE NOT EXISTS(SELECT 1 FROM mdm_bom_item WHERE bom_id=(SELECT `bom_id` FROM `mdm_bom` WHERE `bom_code`='LAMP-BOM-L200-V1' AND deleted_flag=0 LIMIT 1) AND material_id=(SELECT `material_id` FROM `mdm_material` WHERE `material_code`='LAMP-ARM' AND deleted_flag=0 LIMIT 1));
INSERT INTO mdm_bom_item(bom_id,material_id,child_bom_id,quantity,loss_rate,sequence_no,remark,created_time)
SELECT (SELECT `bom_id` FROM `mdm_bom` WHERE `bom_code`='LAMP-BOM-L200-V1' AND deleted_flag=0 LIMIT 1),(SELECT `material_id` FROM `mdm_material` WHERE `material_code`='LAMP-BASE-L200' AND deleted_flag=0 LIMIT 1),(SELECT `bom_id` FROM `mdm_bom` WHERE `bom_code`='LAMP-BOM-BASE-L200-V1' AND deleted_flag=0 LIMIT 1),1,0,3,'LAMP_DESIGN_V1',NOW()
WHERE NOT EXISTS(SELECT 1 FROM mdm_bom_item WHERE bom_id=(SELECT `bom_id` FROM `mdm_bom` WHERE `bom_code`='LAMP-BOM-L200-V1' AND deleted_flag=0 LIMIT 1) AND material_id=(SELECT `material_id` FROM `mdm_material` WHERE `material_code`='LAMP-BASE-L200' AND deleted_flag=0 LIMIT 1));
INSERT INTO mdm_bom_item(bom_id,material_id,child_bom_id,quantity,loss_rate,sequence_no,remark,created_time)
SELECT (SELECT `bom_id` FROM `mdm_bom` WHERE `bom_code`='LAMP-BOM-L200-V1' AND deleted_flag=0 LIMIT 1),(SELECT `material_id` FROM `mdm_material` WHERE `material_code`='LAMP-M15' AND deleted_flag=0 LIMIT 1),NULL,1,0,4,'LAMP_DESIGN_V1',NOW()
WHERE NOT EXISTS(SELECT 1 FROM mdm_bom_item WHERE bom_id=(SELECT `bom_id` FROM `mdm_bom` WHERE `bom_code`='LAMP-BOM-L200-V1' AND deleted_flag=0 LIMIT 1) AND material_id=(SELECT `material_id` FROM `mdm_material` WHERE `material_code`='LAMP-M15' AND deleted_flag=0 LIMIT 1));
INSERT INTO mdm_bom_item(bom_id,material_id,child_bom_id,quantity,loss_rate,sequence_no,remark,created_time)
SELECT (SELECT `bom_id` FROM `mdm_bom` WHERE `bom_code`='LAMP-BOM-L200-V1' AND deleted_flag=0 LIMIT 1),(SELECT `material_id` FROM `mdm_material` WHERE `material_code`='LAMP-M16' AND deleted_flag=0 LIMIT 1),NULL,1,0,5,'LAMP_DESIGN_V1',NOW()
WHERE NOT EXISTS(SELECT 1 FROM mdm_bom_item WHERE bom_id=(SELECT `bom_id` FROM `mdm_bom` WHERE `bom_code`='LAMP-BOM-L200-V1' AND deleted_flag=0 LIMIT 1) AND material_id=(SELECT `material_id` FROM `mdm_material` WHERE `material_code`='LAMP-M16' AND deleted_flag=0 LIMIT 1));
INSERT INTO mdm_bom_item(bom_id,material_id,child_bom_id,quantity,loss_rate,sequence_no,remark,created_time)
SELECT (SELECT `bom_id` FROM `mdm_bom` WHERE `bom_code`='LAMP-BOM-L200-V1' AND deleted_flag=0 LIMIT 1),(SELECT `material_id` FROM `mdm_material` WHERE `material_code`='LAMP-M17' AND deleted_flag=0 LIMIT 1),NULL,4,0,6,'LAMP_DESIGN_V1',NOW()
WHERE NOT EXISTS(SELECT 1 FROM mdm_bom_item WHERE bom_id=(SELECT `bom_id` FROM `mdm_bom` WHERE `bom_code`='LAMP-BOM-L200-V1' AND deleted_flag=0 LIMIT 1) AND material_id=(SELECT `material_id` FROM `mdm_material` WHERE `material_code`='LAMP-M17' AND deleted_flag=0 LIMIT 1));
INSERT INTO mdm_bom_item(bom_id,material_id,child_bom_id,quantity,loss_rate,sequence_no,remark,created_time)
SELECT (SELECT `bom_id` FROM `mdm_bom` WHERE `bom_code`='LAMP-BOM-L200-V1' AND deleted_flag=0 LIMIT 1),(SELECT `material_id` FROM `mdm_material` WHERE `material_code`='LAMP-M18' AND deleted_flag=0 LIMIT 1),NULL,2,0,7,'LAMP_DESIGN_V1',NOW()
WHERE NOT EXISTS(SELECT 1 FROM mdm_bom_item WHERE bom_id=(SELECT `bom_id` FROM `mdm_bom` WHERE `bom_code`='LAMP-BOM-L200-V1' AND deleted_flag=0 LIMIT 1) AND material_id=(SELECT `material_id` FROM `mdm_material` WHERE `material_code`='LAMP-M18' AND deleted_flag=0 LIMIT 1));
INSERT INTO `mdm_supplier` (`supplier_code`,`supplier_name`,`status`,remark,created_time)
SELECT 'LAMP-SUP-ELEC','台灯电子件模拟供应商','ENABLE','LAMP_DESIGN_V1: 教学设计，投产前冻结规格与检验标准',NOW()
WHERE NOT EXISTS(SELECT 1 FROM `mdm_supplier` WHERE `supplier_code`='LAMP-SUP-ELEC');
INSERT INTO `mdm_supplier` (`supplier_code`,`supplier_name`,`status`,remark,created_time)
SELECT 'LAMP-SUP-STRUCT','台灯结构件模拟供应商','ENABLE','LAMP_DESIGN_V1: 教学设计，投产前冻结规格与检验标准',NOW()
WHERE NOT EXISTS(SELECT 1 FROM `mdm_supplier` WHERE `supplier_code`='LAMP-SUP-STRUCT');
INSERT INTO `mdm_customer` (`customer_code`,`customer_name`,`status`,remark,created_time)
SELECT 'LAMP-CUST-A','模拟照明经销商A','ENABLE','LAMP_DESIGN_V1: 教学设计，投产前冻结规格与检验标准',NOW()
WHERE NOT EXISTS(SELECT 1 FROM `mdm_customer` WHERE `customer_code`='LAMP-CUST-A');
INSERT INTO `mdm_customer` (`customer_code`,`customer_name`,`status`,remark,created_time)
SELECT 'LAMP-CUST-B','模拟照明经销商B','ENABLE','LAMP_DESIGN_V1: 教学设计，投产前冻结规格与检验标准',NOW()
WHERE NOT EXISTS(SELECT 1 FROM `mdm_customer` WHERE `customer_code`='LAMP-CUST-B');
INSERT INTO `wms_warehouse` (`warehouse_code`,`warehouse_name`,`warehouse_type`,`status`,remark,created_time)
SELECT 'LAMP-WH-RAW','台灯原料仓','RAW','ENABLE','LAMP_DESIGN_V1: 教学设计，投产前冻结规格与检验标准',NOW()
WHERE NOT EXISTS(SELECT 1 FROM `wms_warehouse` WHERE `warehouse_code`='LAMP-WH-RAW');
INSERT INTO `wms_location` (`location_code`,`location_name`,`warehouse_id`,`location_type`,`status`,remark,created_time)
SELECT 'LAMP-LOC-RAW','台灯原料仓默认库位',(SELECT `warehouse_id` FROM `wms_warehouse` WHERE `warehouse_code`='LAMP-WH-RAW' AND deleted_flag=0 LIMIT 1),'NORMAL','IDLE','LAMP_DESIGN_V1: 教学设计，投产前冻结规格与检验标准',NOW()
WHERE NOT EXISTS(SELECT 1 FROM `wms_location` WHERE `location_code`='LAMP-LOC-RAW');
INSERT INTO `wms_warehouse` (`warehouse_code`,`warehouse_name`,`warehouse_type`,`status`,remark,created_time)
SELECT 'LAMP-WH-LINE','台灯线边仓','LINE_SIDE','ENABLE','LAMP_DESIGN_V1: 教学设计，投产前冻结规格与检验标准',NOW()
WHERE NOT EXISTS(SELECT 1 FROM `wms_warehouse` WHERE `warehouse_code`='LAMP-WH-LINE');
INSERT INTO `wms_location` (`location_code`,`location_name`,`warehouse_id`,`location_type`,`status`,remark,created_time)
SELECT 'LAMP-LOC-LINE','台灯线边仓默认库位',(SELECT `warehouse_id` FROM `wms_warehouse` WHERE `warehouse_code`='LAMP-WH-LINE' AND deleted_flag=0 LIMIT 1),'NORMAL','IDLE','LAMP_DESIGN_V1: 教学设计，投产前冻结规格与检验标准',NOW()
WHERE NOT EXISTS(SELECT 1 FROM `wms_location` WHERE `location_code`='LAMP-LOC-LINE');
INSERT INTO `wms_warehouse` (`warehouse_code`,`warehouse_name`,`warehouse_type`,`status`,remark,created_time)
SELECT 'LAMP-WH-SEMI','台灯半成品仓','SEMI_FINISHED','ENABLE','LAMP_DESIGN_V1: 教学设计，投产前冻结规格与检验标准',NOW()
WHERE NOT EXISTS(SELECT 1 FROM `wms_warehouse` WHERE `warehouse_code`='LAMP-WH-SEMI');
INSERT INTO `wms_location` (`location_code`,`location_name`,`warehouse_id`,`location_type`,`status`,remark,created_time)
SELECT 'LAMP-LOC-SEMI','台灯半成品仓默认库位',(SELECT `warehouse_id` FROM `wms_warehouse` WHERE `warehouse_code`='LAMP-WH-SEMI' AND deleted_flag=0 LIMIT 1),'NORMAL','IDLE','LAMP_DESIGN_V1: 教学设计，投产前冻结规格与检验标准',NOW()
WHERE NOT EXISTS(SELECT 1 FROM `wms_location` WHERE `location_code`='LAMP-LOC-SEMI');
INSERT INTO `wms_warehouse` (`warehouse_code`,`warehouse_name`,`warehouse_type`,`status`,remark,created_time)
SELECT 'LAMP-WH-FG','台灯成品仓','FINISHED','ENABLE','LAMP_DESIGN_V1: 教学设计，投产前冻结规格与检验标准',NOW()
WHERE NOT EXISTS(SELECT 1 FROM `wms_warehouse` WHERE `warehouse_code`='LAMP-WH-FG');
INSERT INTO `wms_location` (`location_code`,`location_name`,`warehouse_id`,`location_type`,`status`,remark,created_time)
SELECT 'LAMP-LOC-FG','台灯成品仓默认库位',(SELECT `warehouse_id` FROM `wms_warehouse` WHERE `warehouse_code`='LAMP-WH-FG' AND deleted_flag=0 LIMIT 1),'NORMAL','IDLE','LAMP_DESIGN_V1: 教学设计，投产前冻结规格与检验标准',NOW()
WHERE NOT EXISTS(SELECT 1 FROM `wms_location` WHERE `location_code`='LAMP-LOC-FG');
INSERT INTO `wms_warehouse` (`warehouse_code`,`warehouse_name`,`warehouse_type`,`status`,remark,created_time)
SELECT 'LAMP-WH-HOLD','台灯不合格隔离仓','DEFECTIVE','ENABLE','LAMP_DESIGN_V1: 教学设计，投产前冻结规格与检验标准',NOW()
WHERE NOT EXISTS(SELECT 1 FROM `wms_warehouse` WHERE `warehouse_code`='LAMP-WH-HOLD');
INSERT INTO `wms_location` (`location_code`,`location_name`,`warehouse_id`,`location_type`,`status`,remark,created_time)
SELECT 'LAMP-LOC-HOLD','台灯不合格隔离仓默认库位',(SELECT `warehouse_id` FROM `wms_warehouse` WHERE `warehouse_code`='LAMP-WH-HOLD' AND deleted_flag=0 LIMIT 1),'DEFECTIVE','IDLE','LAMP_DESIGN_V1: 教学设计，投产前冻结规格与检验标准',NOW()
WHERE NOT EXISTS(SELECT 1 FROM `wms_location` WHERE `location_code`='LAMP-LOC-HOLD');
UPDATE wms_location SET status='IDLE' WHERE location_code LIKE 'LAMP-%' AND status='ENABLE' AND remark LIKE 'LAMP_DESIGN_V1:%';
COMMIT;
SELECT 'lamp_materials' AS item,COUNT(*) AS actual FROM mdm_material WHERE material_code LIKE 'LAMP-%' AND deleted_flag=0;
