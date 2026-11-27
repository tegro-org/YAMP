package org.tegro.yamp.data.download

/** What a finished «Скачать всю коллекцию» has to say: downloaded, and those that cannot be. Pure. */
data class DownloadOutcome(val done: Int, val failed: Int) {
    companion object {
        /**
         * Retried only for want of a network: a track that failed on a connection may be had later, but one gone
         * from the catalogue failed every run — waiting "for the network" then never ended (upstream 1.14.12).
         */
        fun shouldRetry(failed: Int, online: Boolean): Boolean = failed > 0 && !online
    }
}
