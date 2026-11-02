package com.neuedu.ems.service.ai;

import com.neuedu.ems.common.BusinessStatus;
import com.neuedu.ems.common.ResultCode;
import com.neuedu.ems.entity.mfg.MfgProductionOrder;
import com.neuedu.ems.exception.BusinessException;
import com.neuedu.ems.mapper.mdm.MdmCustomerMapper;
import com.neuedu.ems.mapper.mdm.MdmProductMapper;
import com.neuedu.ems.service.mfg.CommandRequestService;
import com.neuedu.ems.service.mfg.MfgProductionOrderService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Objects;

/** 供对话办理复用的生产订单创建服务；权限检查由调用入口负责。 */
@Service
public class AiProductionOrderCreationService {
    private static final String COMMAND = "AI_CREATE_PRODUCTION_ORDER";
    private final MfgProductionOrderService orders;
    private final MdmProductMapper products;
    private final MdmCustomerMapper customers;
    private final CommandRequestService requests;

    public AiProductionOrderCreationService(MfgProductionOrderService orders, MdmProductMapper products,
                                             MdmCustomerMapper customers, CommandRequestService requests) {
        this.orders = orders;
        this.products = products;
        this.customers = customers;
        this.requests = requests;
    }

    /** 同一请求键及相同参数返回原单；缺少明确数据时不创建。 */
    @Transactional(rollbackFor = Exception.class)
    public MfgProductionOrder create(Long productId, Long customerId, BigDecimal quantity,
                                     LocalDate deliveryDate, Long operatorId, String requestKey) {
        requireComplete(productId, customerId, quantity, deliveryDate, operatorId, requestKey);
        String digest = requests.digest(productId + "|" + customerId + "|"
                + quantity.stripTrailingZeros().toPlainString() + "|" + deliveryDate + "|" + operatorId);
        Long priorId = requests.replayed(COMMAND, requestKey, digest);
        if (priorId != null) {
            MfgProductionOrder prior = orders.getOrderById(priorId);
            if (prior == null) throw new BusinessException(ResultCode.DATA_CONFLICT.getCode(), "原订单不存在，请人工核对");
            return prior;
        }
        var product = products.selectById(productId);
        var customer = customers.selectById(customerId);
        if (product == null || !Objects.equals(product.getDeletedFlag(), 0)
                || !BusinessStatus.Common.ENABLE.equals(product.getStatus())) {
            throw new BusinessException(ResultCode.BUSINESS_ERROR.getCode(), "产品不存在或未启用");
        }
        if (customer == null || !Objects.equals(customer.getDeletedFlag(), 0)
                || !BusinessStatus.Common.ENABLE.equals(customer.getStatus())) {
            throw new BusinessException(ResultCode.BUSINESS_ERROR.getCode(), "客户不存在或未启用");
        }
        MfgProductionOrder order = new MfgProductionOrder();
        order.setProductId(productId);
        order.setCustomerId(customerId);
        order.setOrderQuantity(quantity);
        order.setDeliveryDate(deliveryDate);
        order.setRemark("AI 工作台对话创建");
        order.setCreatedBy(operatorId);
        order.setUpdatedBy(operatorId);
        if (!orders.saveOrder(order) || order.getProductionOrderId() == null) {
            throw new BusinessException(ResultCode.BUSINESS_ERROR.getCode(), "生产订单创建失败");
        }
        requests.record(COMMAND, requestKey, "PRODUCTION_ORDER", order.getProductionOrderId(), digest,
                order.getProductionOrderId(), operatorId, null, LocalDateTime.now());
        return order;
    }

    /** 创建生产订单的必要条件：产品、客户、正数数量、交期、操作人和请求键都必须齐全。 */
    private static void requireComplete(Long productId, Long customerId, BigDecimal quantity,
                                        LocalDate deliveryDate, Long operatorId, String requestKey) {
        boolean missedKey = productId == null || customerId == null || deliveryDate == null || operatorId == null;
        boolean badQuantity = quantity == null || quantity.signum() <= 0;
        if (missedKey || badQuantity || !StringUtils.hasText(requestKey)) {
            throw new BusinessException(ResultCode.PARAM_ERROR.getCode(), "请明确产品、客户、数量、交期和操作人");
        }
    }
}
