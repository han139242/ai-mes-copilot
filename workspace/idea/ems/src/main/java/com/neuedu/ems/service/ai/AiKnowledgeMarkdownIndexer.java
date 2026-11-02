package com.neuedu.ems.service.ai;

import com.neuedu.ems.entity.ai.AiKnowledgeSection;

import java.util.ArrayList;
import java.util.List;

/**
 * 把 Markdown 标题拆成知识章节。正文取到下一个标题为止，页码留给有 PDF 页码的来源填写。
 */
public final class AiKnowledgeMarkdownIndexer {

    private static final int MAX_CONTENT_CHARS = 4000;

    private AiKnowledgeMarkdownIndexer() {
    }

    public static List<AiKnowledgeSection> index(String markdown, String docCode, String docName) {
        List<AiKnowledgeSection> sections = new ArrayList<>();
        if (markdown == null || markdown.isBlank()) {
            return sections;
        }
        String[] lines = markdown.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
        int seq = 0;
        Heading current = null;
        StringBuilder body = new StringBuilder();
        for (String line : lines) {
            Heading heading = heading(line);
            if (heading == null) {
                if (current != null) {
                    body.append(line).append('\n');
                }
                continue;
            }
            if (current != null) {
                sections.add(section(docCode, docName, ++seq, current, body.toString()));
            }
            current = heading;
            body.setLength(0);
        }
        if (current != null) {
            sections.add(section(docCode, docName, ++seq, current, body.toString()));
        }
        return sections;
    }

    private static AiKnowledgeSection section(String docCode, String docName, int seq, Heading heading, String body) {
        AiKnowledgeSection section = new AiKnowledgeSection();
        section.setDocCode(docCode);
        section.setDocName(docName);
        section.setSeqNo(seq);
        section.setLevelNo(heading.level());
        section.setTitle(heading.title());
        String content = body.strip();
        if (content.length() > MAX_CONTENT_CHARS) {
            content = content.substring(0, MAX_CONTENT_CHARS);
        }
        section.setContent(content);
        section.setVersion(0);
        section.setDeletedFlag(0);
        return section;
    }

    private static Heading heading(String line) {
        int level = 0;
        while (level < line.length() && line.charAt(level) == '#') {
            level++;
        }
        if (level < 1 || level > 6 || level >= line.length() || line.charAt(level) != ' ') {
            return null;
        }
        String title = line.substring(level + 1).strip();
        if (title.isEmpty()) {
            return null;
        }
        return new Heading(Math.min(level, 4), title);
    }

    private record Heading(int level, String title) {
    }
}
