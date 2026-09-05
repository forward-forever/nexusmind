package com.wude.nexusmind.knowledge.parser;

import com.wude.nexusmind.knowledge.exception.DocumentParsingException;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DocumentParserTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void textParserReadsRealUtf8Content() throws IOException {
        Path textFile = temporaryDirectory.resolve("notes.txt");
        Files.writeString(textFile, "第一段 UTF-8 文本。\nSecond line.", StandardCharsets.UTF_8);

        ParsedDocument parsed = new TextDocumentParser().parse(textFile);

        assertThat(parsed.sections()).hasSize(1);
        assertThat(parsed.sections().get(0).text()).contains("第一段", "Second line");
        assertThat(parsed.sections().get(0).pageNo()).isNull();
        assertThat(parsed.sections().get(0).sectionTitle()).isNull();
    }

    @Test
    void textParserRejectsInvalidUtf8() throws IOException {
        Path textFile = temporaryDirectory.resolve("invalid.txt");
        Files.write(textFile, new byte[]{(byte) 0xC3, 0x28});

        assertThatThrownBy(() -> new TextDocumentParser().parse(textFile))
                .isInstanceOf(DocumentParsingException.class)
                .hasMessageContaining("UTF-8");
    }

    @Test
    void markdownParserKeepsHeadingsAndIgnoresHashesInsideCodeBlocks() throws IOException {
        Path markdownFile = temporaryDirectory.resolve("guide.md");
        Files.writeString(markdownFile, """
                Intro paragraph.
                # Main Title
                Main body.
                ```java
                # this is code, not a heading
                System.out.println("hello");
                ```
                ## Details
                Detail body.
                """, StandardCharsets.UTF_8);

        ParsedDocument parsed = new MarkdownDocumentParser().parse(markdownFile);

        assertThat(parsed.sections()).hasSize(3);
        assertThat(parsed.sections().get(0).sectionTitle()).isNull();
        assertThat(parsed.sections().get(1).sectionTitle()).isEqualTo("Main Title");
        assertThat(parsed.sections().get(1).text()).contains("# this is code, not a heading");
        assertThat(parsed.sections().get(2).sectionTitle()).isEqualTo("Details");
    }

    @Test
    void pdfParserExtractsTwoPagesWithAccuratePageNumbers() throws IOException {
        Path pdfFile = temporaryDirectory.resolve("two-pages.pdf");
        createTwoPagePdf(pdfFile);

        ParsedDocument parsed = new PdfDocumentParser().parse(pdfFile);

        assertThat(parsed.sections()).hasSize(2);
        assertThat(parsed.sections().get(0).pageNo()).isEqualTo(1);
        assertThat(parsed.sections().get(0).text()).contains("First page content");
        assertThat(parsed.sections().get(1).pageNo()).isEqualTo(2);
        assertThat(parsed.sections().get(1).text()).contains("Second page content");
    }

    @Test
    void pdfWithoutExtractableTextReportsOcrLimitation() throws IOException {
        Path pdfFile = temporaryDirectory.resolve("blank.pdf");
        try (PDDocument pdf = new PDDocument()) {
            pdf.addPage(new PDPage());
            pdf.save(pdfFile.toFile());
        }

        assertThatThrownBy(() -> new PdfDocumentParser().parse(pdfFile))
                .isInstanceOf(DocumentParsingException.class)
                .hasMessageContaining("暂不支持 OCR");
    }

    private static void createTwoPagePdf(Path output) throws IOException {
        try (PDDocument pdf = new PDDocument()) {
            addTextPage(pdf, "First page content for citation.");
            addTextPage(pdf, "Second page content for citation.");
            pdf.save(output.toFile());
        }
    }

    private static void addTextPage(PDDocument pdf, String text) throws IOException {
        PDPage page = new PDPage();
        pdf.addPage(page);
        try (PDPageContentStream content = new PDPageContentStream(pdf, page)) {
            content.beginText();
            content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
            content.newLineAtOffset(72, 720);
            content.showText(text);
            content.endText();
        }
    }
}
