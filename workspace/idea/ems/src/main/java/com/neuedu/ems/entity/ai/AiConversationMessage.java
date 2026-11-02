package com.neuedu.ems.entity.ai;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * ai_conversation_message AI 对话消息。
 *
 * <p>除了正文，消息还带着当时的展示结果：blocks_json 是表格与图表，
 * query_time 标记这批数据是什么时候查的，filters_json 是筛选条件，
 * command_id 指向 ai_command_execution，让页面重进后能按后台记录恢复执行状态。</p>
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
@TableName("ai_conversation_message")
public class AiConversationMessage {

    @TableId(value = "message_id", type = IdType.AUTO)
    private Long messageId;
    private Long conversationId;
    /** 会话内序号，从 1 开始连续递增，由服务层计算，不依赖数据库自增。 */
    private Integer seqNo;
    /** 角色：user 用户 / assistant 助手 */
    private String role;
    private String content;
    /** 结构化展示块 JSON：表格与图表数据。 */
    private String blocksJson;
    /** 追问建议 JSON 数组。 */
    private String suggestionsJson;
    /** 业务命令卡片 JSON 快照。 */
    private String commandJson;
    /** 关联 ai_command_execution.command_id，可空。 */
    private Long commandId;
    /**
     * 多步办理计划 JSON 快照，含 planId 与各步状态。
     *
     * <p>一句话连着办几件事时，回答里带的不只是一段文字，还有一张可逐步执行的多步卡片。
     * 把计划本身落进消息表，页面重进或换设备后这张卡片还能按后台记录恢复执行状态，
     * 而不是退化成一段读完就结束的历史文本。</p>
     */
    private String sequenceJson;
    /** 命中的意图编码。 */
    private String intent;
    /** 本条回答对应数据的查询时间。 */
    private LocalDateTime queryTime;
    /** 查询筛选条件 JSON，例如日期范围、物料、仓库。 */
    private String filtersJson;
    /** 产生该回答的模型或链路标识。 */
    private String model;
    private Long elapsedMs;
    /**
     * 客户端消息标识，可空。同一条提问重试时前端复用同一个值，
     * 后端据此去重，避免网络重试把同一句话追加两遍。
     */
    private String clientMessageId;
    @Version
    private Integer version;
    private Long createdBy;
    private LocalDateTime createdTime;
    private Long updatedBy;
    private LocalDateTime updatedTime;
    private Integer deletedFlag;
}
