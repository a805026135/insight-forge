package com.insightforge.agent.core.kb;

import java.util.ArrayList;
import java.util.List;

/**
 * 文本切块（Phase 19）：优先按段落聚合，超长段按句子拆，最后按字符硬切。
 * 中文友好：以句号/感叹号/问号/分号等为边界，避免把语义切碎。
 */
public final class TextChunker {

    private TextChunker() {
    }

    public static List<String> chunk(String text, int maxChars, int overlap) {
        List<String> out = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return out;
        }
        String normalized = text.replace("\r\n", "\n").trim();
        int max = Math.max(64, maxChars);
        int ov = Math.max(0, Math.min(overlap, max / 2));
        List<String> paragraphs = splitParagraphs(normalized);
        StringBuilder buf = new StringBuilder();
        for (String p : paragraphs) {
            if (buf.length() > 0 && buf.length() + p.length() + 1 > max) {
                out.add(buf.toString().trim());
                buf.setLength(0);
            }
            if (p.length() <= max) {
                if (buf.length() > 0) {
                    buf.append("\n");
                }
                buf.append(p);
                continue;
            }
            if (buf.length() > 0) {
                out.add(buf.toString().trim());
                buf.setLength(0);
            }
            for (String s : splitSentences(p)) {
                if (buf.length() > 0 && buf.length() + s.length() + 1 > max) {
                    out.add(buf.toString().trim());
                    buf.setLength(0);
                }
                if (s.length() <= max) {
                    if (buf.length() > 0) {
                        buf.append("\n");
                    }
                    buf.append(s);
                } else {
                    hardSplit(s, max, ov, out);
                }
            }
        }
        if (buf.length() > 0) {
            out.add(buf.toString().trim());
        }
        return out;
    }

    private static List<String> splitParagraphs(String text) {
        List<String> out = new ArrayList<>();
        for (String line : text.split("\\n\\s*\\n")) {
            String p = line.trim();
            if (!p.isBlank()) {
                out.add(p);
            }
        }
        return out;
    }

    private static List<String> splitSentences(String p) {
        List<String> out = new ArrayList<>();
        StringBuilder buf = new StringBuilder();
        for (int i = 0; i < p.length(); i++) {
            char c = p.charAt(i);
            buf.append(c);
            if (c == '。' || c == '！' || c == '？' || c == '；' || c == ';') {
                out.add(buf.toString().trim());
                buf.setLength(0);
            }
        }
        if (buf.length() > 0) {
            out.add(buf.toString().trim());
        }
        return out.stream().filter(s -> !s.isBlank()).toList();
    }

    private static void hardSplit(String s, int max, int overlap, List<String> out) {
        int start = 0;
        while (start < s.length()) {
            int end = Math.min(s.length(), start + max);
            out.add(s.substring(start, end).trim());
            if (end >= s.length()) {
                break;
            }
            start = Math.max(start + 1, end - overlap);
        }
    }
}
