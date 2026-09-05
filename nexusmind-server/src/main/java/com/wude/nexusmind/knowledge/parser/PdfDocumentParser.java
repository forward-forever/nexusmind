package com.wude.nexusmind.knowledge.parser;

import com.wude.nexusmind.knowledge.exception.DocumentParsingException;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

@Component
public class PdfDocumentParser implements DocumentParser {

    private static final String NO_EXTRACTABLE_TEXT_MESSAGE =
            "未检测到可提取文本，扫描 PDF 当前暂不支持 OCR。";

    @Override
    public Set<String> supportedExtensions() {
        return Set.of("pdf");
    }

    @Override
    public ParsedDocument parse(Path documentPath) {
        try (PDDocument pdf = Loader.loadPDF(documentPath.toFile())) {
            PDFTextStripper textStripper = new PDFTextStripper();
            List<ParsedSection> pages = new ArrayList<>();
            for (int pageNumber = 1; pageNumber <= pdf.getNumberOfPages(); pageNumber++) {
                textStripper.setStartPage(pageNumber);
                textStripper.setEndPage(pageNumber);
                String pageText = textStripper.getText(pdf).strip();
                if (!pageText.isBlank()) {
                    pages.add(new ParsedSection(pageText, pageNumber, null));
                }
            }
            if (pages.isEmpty()) {
                throw new DocumentParsingException(NO_EXTRACTABLE_TEXT_MESSAGE);
            }
            return new ParsedDocument(pages);
        } catch (DocumentParsingException exception) {
            throw exception;
        } catch (IOException exception) {
            throw new DocumentParsingException("Failed to parse PDF document", exception);
        }
    }
}
