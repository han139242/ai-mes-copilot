package com.neuedu.ems.service.wms;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.IService;
import com.neuedu.ems.entity.wms.WmsInventory;

import java.util.List;

/**
 * 库存余额管理 Service 接口。
 */
public interface WmsInventoryService extends IService<WmsInventory> {

    /**
     * 分页查询库存余额列表，支持仓库、库位、物料、批次和工单筛选。
     * @param current 当前页码
     * @param size 每页记录数
     * @param warehouseId 仓库ID
     * @param locationId 库位ID
     * @param materialId 物料ID
     * @param batchId 批次ID
     * @param workOrderId 工单ID，线边仓按工单占用时用于区分同一批次的不同工单
     * @return 分页结果
     */
    Page<WmsInventory> pageInventories(Integer current, Integer size, Long warehouseId, Long locationId, Long materialId,
                                       Long batchId, Long workOrderId);

    /**
     * 查询库存余额列表，支持仓库、库位、物料、批次和工单筛选。
     * @param warehouseId 仓库ID
     * @param locationId 库位ID
     * @param materialId 物料ID
     * @param batchId 批次ID
     * @param workOrderId 工单ID，线边仓按工单占用时用于区分同一批次的不同工单
     * @return 库存余额列表
     */
    List<WmsInventory> listInventories(Long warehouseId, Long locationId, Long materialId, Long batchId, Long workOrderId);

    /**
     * 根据库存余额ID查询详情。
     * @param inventoryId 库存余额ID
     * @return 库存余额实体
     */
    WmsInventory getInventoryById(Long inventoryId);
}
