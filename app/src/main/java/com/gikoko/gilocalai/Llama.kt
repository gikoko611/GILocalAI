package com.gikoko.gilocalai

interface TokenCallback { fun onToken(b: ByteArray) }

object Llama {
    init { System.loadLibrary("gillama") }
    external fun load(path: String, ctx: Int, threads: Int): String
    external fun generate(roles: Array<String>, contents: Array<ByteArray>, temp: Float, maxTokens: Int, cb: TokenCallback): String
    external fun stop()
    external fun unload()
}
