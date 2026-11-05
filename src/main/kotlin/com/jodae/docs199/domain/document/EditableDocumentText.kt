package com.jodae.docs199.domain.document

import com.jodae.docs199.global.error.ApiException
import com.jodae.docs199.global.error.ErrorCode
import org.apache.pdfbox.Loader
import org.apache.pdfbox.text.PDFTextStripper
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

object EditableDocumentText {
    fun extract(
        extension: String,
        bytes: ByteArray,
    ): String =
        when (extension.lowercase()) {
            "md", "txt" ->
                try {
                    StandardCharsets.UTF_8
                        .newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)
                        .decode(ByteBuffer.wrap(bytes))
                        .toString()
                } catch (_: CharacterCodingException) {
                    throw ApiException(
                        ErrorCode.INVALID_INPUT,
                        "UTF-8 \uBB38\uC11C \uB0B4\uC6A9\uC744 \uC77D\uC744 \uC218 \uC5C6\uC2B5\uB2C8\uB2E4",
                    )
                }

            "pdf" ->
                try {
                    Loader.loadPDF(bytes).use { pdf ->
                        (1..pdf.numberOfPages).joinToString("\n\n") { page ->
                            PDFTextStripper()
                                .apply {
                                    startPage = page
                                    endPage = page
                                }.getText(pdf)
                                .trim()
                        }
                    }
                } catch (_: IOException) {
                    throw ApiException(ErrorCode.INVALID_INPUT, "PDF \uB0B4\uC6A9\uC744 \uC77D\uC744 \uC218 \uC5C6\uC2B5\uB2C8\uB2E4")
                }

            else -> throw ApiException(
                ErrorCode.INVALID_INPUT,
                "\uD3B8\uC9D1\uD560 \uC218 \uC5C6\uB294 \uBB38\uC11C \uD615\uC2DD\uC785\uB2C8\uB2E4",
            )
        }
}
