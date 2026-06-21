package com.qasim.speedlimiter.data.services.z5;

import android.util.Log;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.DatagramChannel;
import java.util.Iterator;
import java.util.concurrent.BlockingQueue;

public final class k implements Runnable {

    private final Selector udpSelector;
    private final BlockingQueue<ByteBuffer> packetOutputQueue;
    private final u1 speedController;

    public k(BlockingQueue<ByteBuffer> outputQueue, Selector selector, u1 throttler) {
        this.packetOutputQueue = outputQueue;
        this.udpSelector = selector;
        this.speedController = throttler;
    }

    @Override
    public void run() {
        while (!Thread.interrupted()) {
            try {
                if (this.udpSelector.select() == 0) {
                    Thread.sleep(10L);
                    continue;
                }
                
                Iterator<SelectionKey> keys = this.udpSelector.selectedKeys().iterator();
                while (keys.hasNext() && !Thread.interrupted()) {
                    SelectionKey key = keys.next();
                    if (key.isValid() && key.isReadable()) {
                        keys.remove();
                        
                        DatagramChannel channel = (DatagramChannel) key.channel();
                        ByteBuffer buffer = ByteBuffer.allocate(16384);
                        
                        int readBytes = channel.read(buffer);
                        if (readBytes > 0) {
                            buffer.flip();
                            
                            if (this.speedController != null) {
                                this.speedController.a(readBytes);
                            }
                            
                            this.packetOutputQueue.put(buffer);
                        }
                    }
                }
            } catch (IOException | InterruptedException e) {
                Log.e("VpnUdpEngine", "Error in UDP Selector routing", e);
                return;
            }
        }
    }
}
