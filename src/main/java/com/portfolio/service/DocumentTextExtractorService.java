package com.portfolio.service;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

@Service
public class DocumentTextExtractorService {

    public String extractText(MultipartFile file) throws Exception {
        String originalName = file.getOriginalFilename();
        String filename = originalName == null ? "" : originalName.toLowerCase(Locale.ROOT);

        if (filename.endsWith(".txt")) {
            return new String(file.getBytes(), StandardCharsets.UTF_8);
        }

        if (filename.endsWith(".pdf")) {
            try (InputStream input = file.getInputStream(); PDDocument doc = PDDocument.load(input)) {
                PDFTextStripper stripper = new PDFTextStripper();
                return stripper.getText(doc);
            }
        }

        if (filename.endsWith(".docx")) {
            try (InputStream input = file.getInputStream(); XWPFDocument doc = new XWPFDocument(input)) {
                return doc.getParagraphs().stream()
                    .map(p -> p.getText() == null ? "" : p.getText())
                    .reduce("", (a, b) -> a + "\n" + b)
                    .trim();
            }
        }

        throw new IllegalArgumentException("Unsupported file type. Please upload PDF, DOCX, or TXT.");
    }
}
