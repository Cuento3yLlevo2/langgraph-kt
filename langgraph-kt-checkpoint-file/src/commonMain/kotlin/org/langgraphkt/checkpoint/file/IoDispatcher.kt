package org.langgraphkt.checkpoint.file

import kotlinx.coroutines.CoroutineDispatcher

/** Dispatcher for blocking file-system calls: `Dispatchers.IO` where available, `Dispatchers.Default` on JS/Wasm. */
internal expect val ioDispatcher: CoroutineDispatcher
