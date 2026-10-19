package com.github.serezhka.airplay.server.internal;

import com.github.serezhka.airplay.protocol.FairPlaySession;
import com.github.serezhka.airplay.server.Playback;
import com.github.serezhka.airplay.server.internal.decoder.AudioDecoder;
import com.github.serezhka.airplay.server.internal.handler.audio.AudioHandler;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.DatagramChannel;
import io.netty.channel.socket.nio.NioDatagramChannel;
import io.netty.handler.codec.DatagramPacketDecoder;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.net.InetSocketAddress;

@Slf4j
@RequiredArgsConstructor
public class AudioServer implements Runnable {

    private final FairPlaySession airPlay;

    private Thread thread;
    private volatile java.util.concurrent.CountDownLatch started;
    private Playback airPlayConsumer;

    @Getter
    private int port;

    public void start(Playback airPlayConsumer) throws InterruptedException {
        this.airPlayConsumer = airPlayConsumer;
        thread = new Thread(this);
        // Android port: a latch instead of wait/notify, which hung if the server bound first.
        started = new java.util.concurrent.CountDownLatch(1);
        thread.start();
        started.await();
    }

    public void stop() {
        if (thread != null) {
            thread.interrupt();
            thread = null;
            airPlayConsumer = null;
        }
    }

    @Override
    public void run() {
        var bootstrap = new Bootstrap();
        var workerGroup = eventLoopGroup();

        try {
            bootstrap
                    .group(workerGroup)
                    .channel(datagramChannelClass())
                    .localAddress(new InetSocketAddress(0)) // bind random port
                    .handler(new ChannelInitializer<DatagramChannel>() {
                        @Override
                        public void initChannel(final DatagramChannel ch) {
                            ch.pipeline().addLast("audioDecoder", new DatagramPacketDecoder(new AudioDecoder()));
                            ch.pipeline().addLast("audioHandler", new AudioHandler(airPlay, airPlayConsumer));
                        }
                    });
            var channelFuture = bootstrap.bind().sync();

            log.info("AirPlay audio server listening on port: {}",
                    port = ((InetSocketAddress) channelFuture.channel().localAddress()).getPort());

            started.countDown();

            channelFuture.channel().closeFuture().sync();
        } catch (InterruptedException e) {
            log.info("AirPlay audio server interrupted");
        } finally {
            started.countDown(); // never leave start() blocked if bind failed
            log.info("AirPlay audio server stopped");
            workerGroup.shutdownGracefully();
        }
    }

    private EventLoopGroup eventLoopGroup() {
        return new NioEventLoopGroup();
    }

    private Class<? extends DatagramChannel> datagramChannelClass() {
        return NioDatagramChannel.class;
    }
}
