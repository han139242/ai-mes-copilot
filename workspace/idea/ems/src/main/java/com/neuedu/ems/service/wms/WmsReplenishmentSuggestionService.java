package com.neuedu.ems.service.wms;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.IService;
import com.neuedu.ems.entity.wms.WmsReplenishmentSuggestion;

import java.math.BigDecimal;
import java.util.List;

/**
 * 补货建议 Service 接口。
 */
public interface WmsReplenishmentSuggestionService extends IService<WmsReplenishmentSuggestion> {

    /**
     * 分页查询补货建议，支持建议编号、物料、来源类型和状态筛选。
     * @param current 当前页码
     * @param size 每页记录数
     * @param keyword 建议编号关键字
     * @param materialId 物料ID
     * @param sourceType 来源类型
     * @param status 状态
     * @return 补货建议分页结果
     */
    Page<WmsReplenishmentSuggestion> pageSuggestions(Integer current, Integer size, String keyword, Long materialId, String sourceType, String status);

    /**
     * 查询补货建议列表，支持建议编号、物料、来源类型和状态筛选。
     * @param keyword 建议编号关键字
     * @param materialId 物料ID
     * @param sourceType 来源类型
     * @param status 状态
     * @return 补货建议列表
     */
    List<WmsReplenishmentSuggestion> listSuggestions(String keyword, Long materialId, String sourceType, String status);

    /**
     * 根据ID查询未删除的补货建议。
     * @param suggestionId 补货建议ID
     * @return 补货建议实体
     */
    WmsReplenishmentSuggestion getSuggestionById(Long suggestionId);

    /**
     * 新增补货建议。
     * @param suggestion 补货建议实体
     * @return 是否成功
     */
    boolean saveSuggestion(WmsReplenishmentSuggestion suggestion);

    /**
     * 修改补货建议。
     * @param suggestion 补货建议实体
     * @return 是否成功
     */
    boolean updateSuggestion(WmsReplenishmentSuggestion suggestion);

    /**
     * 根据ID软删除补货建议。
     * @param suggestionId 补货建议ID
     * @return 是否成功
     */
    boolean removeSuggestionById(Long suggestionId);

    /**
     * 查询某物料仍然待确认的库存预警类补货建议。
     * 预警中心与自动化规则是两条独立链路，都要在生成前查一次，避免同一缺口重复建单、重复通知仓管。
     * @param materialId 物料ID
     * @return 最近一条待确认建议，没有则返回 null
     */
    WmsReplenishmentSuggestion findPendingInventoryWarning(Long materialId);

    /**
     * 根据物料和仓库生成补货建议。
     * 同一物料已有待确认的库存预警类建议时直接复用，不重复建单也不重复通知。
     * @param materialId 物料ID
     * @param warehouseId 仓库ID，可为空
     * @param requiredQuantity 需求数量，可为空
     * @return 补货建议ID
     */
    Long generateSuggestion(Long materialId, Long warehouseId, BigDecimal requiredQuantity);
}
