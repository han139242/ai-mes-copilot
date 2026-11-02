package com.neuedu.ems.entity.ai;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 说明书章节索引。
 * 把需求规格说明书与系统设计说明书拆成带标题层级与页码的章节，
 * 供知识助手回答时给出「哪份文档、第几章、第几页」的出处，避免模型凭空作答。
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
@TableName("ai_knowledge_section")
public class AiKnowledgeSection {

    /** 章节主键。 */
    @TableId(value = "section_id", type = IdType.AUTO)
    private Long sectionId;

    /** 文档代号：REQ 需求规格说明书，DES 系统设计说明书。 */
    private String docCode;

    /** 文档名称。 */
    private String docName;

    /** 章节序号，按文档出现顺序。 */
    private Integer seqNo;

    /** 标题层级，1 到 4。 */
    private Integer levelNo;

    /** 章节标题，含编号。 */
    private String title;

    /** 在导出 PDF 中的页码。 */
    private Integer pageNo;

    /** 章节正文。 */
    private String content;

    private Integer version;
    private Long createdBy;
    private java.time.LocalDateTime createdTime;
    private Long updatedBy;
    private java.time.LocalDateTime updatedTime;
    private Integer deletedFlag;
}
