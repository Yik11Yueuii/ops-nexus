package com.opsnexus.ingestion;

import com.opsnexus.knowledge.KnowledgeException;
import java.io.*;
import java.nio.*;
import java.nio.charset.*;
import java.util.*;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.extractor.XWPFWordExtractor;
import org.springframework.stereotype.Component;

@Component
public class DocumentParser {
    public record Chunk(int index, Integer page, String content) {}
    public List<Chunk> parse(byte[] bytes, String type) {
        var chunks = new ArrayList<Chunk>();
        try {
            switch (type) {
                case "PDF" -> {
                    try (var pdf = Loader.loadPDF(bytes)) {
                        if (pdf.isEncrypted() || pdf.getNumberOfPages() > 200)
                            throw invalid("PDF 不支持加密文件，页数最多 200 页");
                        var stripper = new PDFTextStripper();
                        for (int page = 1; page <= pdf.getNumberOfPages(); page++) {
                            stripper.setStartPage(page); stripper.setEndPage(page);
                            split(stripper.getText(pdf), page, chunks);
                        }
                    }
                }
                case "DOCX" -> {
                    try (var doc = new XWPFDocument(new ByteArrayInputStream(bytes));
                         var extractor = new XWPFWordExtractor(doc)) {
                        split(extractor.getText(), null, chunks);
                    }
                }
                case "MD", "TXT" -> {
                    String text = StandardCharsets.UTF_8.newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
                    if (text.indexOf('\0') >= 0) throw invalid("文本包含二进制内容");
                    split(text, null, chunks);
                }
                default -> throw invalid("仅支持 PDF、DOCX、Markdown、TXT");
            }
        } catch (KnowledgeException e) { throw e; }
        catch (Exception e) { throw invalid("解析失败：请检查文件是否损坏、加密，文本文件请使用 UTF-8"); }
        if (chunks.isEmpty()) throw invalid("未提取到正文；扫描 PDF 需要先转换为文本型 PDF");
        return chunks;
    }
    private void split(String text, Integer page, List<Chunk> chunks) {
        text = text.replace("\r\n", "\n").replace("\uFEFF", "").strip();
        if (text.isBlank()) return;
        for (int start = 0; start < text.length();) {
            int end = Math.min(start + 800, text.length());
            chunks.add(new Chunk(chunks.size(), page, text.substring(start, end)));
            if (chunks.size() > 300) throw invalid("正文过长，首版最多处理 300 个片段，请拆分文档");
            if (end == text.length()) break;
            start = end - 80;
        }
    }
    private KnowledgeException invalid(String message) {
        return new KnowledgeException(422, "DOCUMENT_INVALID", message);
    }
}
