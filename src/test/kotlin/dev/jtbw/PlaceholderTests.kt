package com.jtw.scriptutils

import org.junit.jupiter.api.Test
import java.io.File

class FileExtensionsTests {
    @Test
    fun `stub test`() {
        (1+1) shouldBe 2
    }
}

infix fun Any?.shouldBe(other: Any) {
    assertEquals(other, this)
}
