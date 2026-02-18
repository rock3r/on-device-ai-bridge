// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.sebastiano.plugins.appleintelligence

import com.intellij.openapi.diagnostic.logger
import io.netty.bootstrap.ServerBootstrap
import io.netty.channel.Channel
import io.netty.channel.ChannelInitializer
import io.netty.channel.nio.NioEventLoopGroup
import io.netty.channel.socket.SocketChannel
import io.netty.channel.socket.nio.NioServerSocketChannel
import io.netty.handler.codec.http.HttpObjectAggregator
import io.netty.handler.codec.http.HttpServerCodec
import java.net.InetSocketAddress

private val LOG = logger<AppleAiHttpServer>()

private const val MAX_CONTENT_LENGTH = 1024 * 1024 // 1 MB

internal class AppleAiHttpServer {

    @Volatile private var serverChannel: Channel? = null
    private var bossGroup: NioEventLoopGroup? = null
    private var workerGroup: NioEventLoopGroup? = null

    val isRunning: Boolean
        get() = serverChannel?.isActive == true

    fun start(host: String, port: Int) {
        if (isRunning) {
            LOG.warn("Apple AI HTTP server is already running")
            return
        }

        val boss = NioEventLoopGroup(1)
        val worker = NioEventLoopGroup()
        bossGroup = boss
        workerGroup = worker

        runCatching {
            val bootstrap =
                ServerBootstrap()
                    .group(boss, worker)
                    .channel(NioServerSocketChannel::class.java)
                    .childHandler(
                        object : ChannelInitializer<SocketChannel>() {
                            override fun initChannel(ch: SocketChannel) {
                                ch.pipeline()
                                    .addLast(
                                        HttpServerCodec(),
                                        HttpObjectAggregator(MAX_CONTENT_LENGTH),
                                        AppleAiHttpHandler(),
                                    )
                            }
                        }
                    )

            val future = bootstrap.bind(InetSocketAddress(host, port)).sync()
            serverChannel = future.channel()
            LOG.info("Apple AI HTTP server started on $host:$port")
        }
            .onFailure { e ->
                if (e is InterruptedException) {
                    Thread.currentThread().interrupt()
                }
                LOG.error("Failed to start Apple AI HTTP server on $host:$port", e)
                stop()
            }
            .getOrThrow()
    }

    fun stop() {
        serverChannel?.close()?.syncUninterruptibly()
        serverChannel = null
        workerGroup?.shutdownGracefully()
        bossGroup?.shutdownGracefully()
        workerGroup = null
        bossGroup = null
        LOG.info("Apple AI HTTP server stopped")
    }
}
