package com.jodae.docs199

import com.jodae.docs199.domain.document.EditableDocumentText
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.pdmodel.font.Standard14Fonts
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream

class EditableDocumentTextTests {
    @Test
    fun extractsPdfPagesWithBlankLineBetweenThem() {
        val bytes =
            PDDocument().use { pdf ->
                listOf("First page", "Second page").forEach { content ->
                    val page = PDPage()
                    pdf.addPage(page)
                    PDPageContentStream(pdf, page).use { stream ->
                        stream.beginText()
                        stream.setFont(PDType1Font(Standard14Fonts.FontName.HELVETICA), 12f)
                        stream.newLineAtOffset(50f, 700f)
                        stream.showText(content)
                        stream.endText()
                    }
                }
                ByteArrayOutputStream().also(pdf::save).toByteArray()
            }
        assertEquals("First page\n\nSecond page", EditableDocumentText.extract("pdf", bytes))
    }
}
