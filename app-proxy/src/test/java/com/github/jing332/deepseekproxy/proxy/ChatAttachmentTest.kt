package com.github.jing332.deepseekproxy.proxy

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatAttachmentTest {
    @Test
    fun parsesTextImageAndFileContentWithoutDroppingBytes() {
        val request = Json.decodeFromString<ChatCompletionRequest>(
            """{"model":"demo","messages":[{"role":"user","content":[
              {"type":"text","text":"看附件"},
              {"type":"image_url","image_url":{"url":"data:image/png;base64,AQID"}},
              {"type":"file","file":{"filename":"a.pdf","file_data":"data:application/pdf;base64,BAUG"}}
            ]}]}"""
        )
        val message = request.messages.single()

        assertEquals("看附件", message.textContent())
        assertEquals(listOf("image.png", "a.pdf"), message.attachments().map { it.name })
        assertTrue(webAttachmentUploadScript(message.attachments()).contains("AQID"))
        assertTrue(webAttachmentUploadScript(message.attachments()).contains("BAUG"))
    }
}
