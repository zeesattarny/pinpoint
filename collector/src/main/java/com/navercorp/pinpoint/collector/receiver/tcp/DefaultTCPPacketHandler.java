/*
 * Copyright 2018 NAVER Corp.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.navercorp.pinpoint.collector.receiver.tcp;

import com.navercorp.pinpoint.collector.receiver.DispatchHandler;
import com.navercorp.pinpoint.collector.util.PacketUtils;
import com.navercorp.pinpoint.io.request.DefaultServerRequest;
import com.navercorp.pinpoint.io.request.Message;
import com.navercorp.pinpoint.io.request.ServerRequest;
import com.navercorp.pinpoint.io.request.ServerResponse;
import com.navercorp.pinpoint.rpc.PinpointSocket;
import com.navercorp.pinpoint.rpc.packet.BasicPacket;
import com.navercorp.pinpoint.rpc.packet.RequestPacket;
import com.navercorp.pinpoint.rpc.packet.SendPacket;
import com.navercorp.pinpoint.thrift.io.DeserializerFactory;
import com.navercorp.pinpoint.thrift.io.HeaderTBaseDeserializer;
import com.navercorp.pinpoint.thrift.io.HeaderTBaseSerializer;
import com.navercorp.pinpoint.thrift.io.SerializerFactory;
import com.navercorp.pinpoint.thrift.util.SerializationUtils;
import org.apache.thrift.TBase;
import org.apache.thrift.TException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.SocketAddress;
import java.util.Objects;

/**
 * @author Woonduk Kang(emeroad)
 */
public class DefaultTCPPacketHandler implements TCPPacketHandler {

    private final Logger logger = LoggerFactory.getLogger(this.getClass());
    private final boolean isDebug = logger.isDebugEnabled();

    private final DispatchHandler dispatchHandler;

    private final SerializerFactory<HeaderTBaseSerializer> serializerFactory;
    private final DeserializerFactory<HeaderTBaseDeserializer> deserializerFactory;


    public DefaultTCPPacketHandler(DispatchHandler dispatchHandler, SerializerFactory<HeaderTBaseSerializer> serializerFactory, DeserializerFactory<HeaderTBaseDeserializer> deserializerFactory) {
        this.dispatchHandler = Objects.requireNonNull(dispatchHandler, "dispatchHandler must not be null");
        this.serializerFactory = Objects.requireNonNull(serializerFactory, "serializerFactory must not be null");
        this.deserializerFactory = Objects.requireNonNull(deserializerFactory, "deserializerFactory must not be null");
    }

    @Override
    public void handleSend(SendPacket packet, PinpointSocket pinpointSocket) {
        Objects.requireNonNull(packet, "packet must not be null");
        Objects.requireNonNull(pinpointSocket, "pinpointSocket must not be null");

        final byte[] payload = getPayload(packet);
        SocketAddress remoteAddress = pinpointSocket.getRemoteAddress();
        try {
            Message<TBase<?, ?>> message = SerializationUtils.deserialize(payload, deserializerFactory);
            ServerRequest<TBase<?, ?>> serverRequest = new DefaultServerRequest<TBase<?, ?>>(message);
            dispatchHandler.dispatchSendMessage(serverRequest);
        } catch (TException e) {
            handleTException(payload, remoteAddress, e);
        } catch (Exception e) {
            // there are cases where invalid headers are received
            handleException(payload, remoteAddress, e);
        }
    }

    public byte[] getPayload(BasicPacket packet) {
        final byte[] payload = packet.getPayload();
        Objects.requireNonNull(payload, "payload must not be null");
        return payload;
    }

    @Override
    public void handleRequest(RequestPacket packet, PinpointSocket pinpointSocket) {
        Objects.requireNonNull(packet, "packet must not be null");
        Objects.requireNonNull(pinpointSocket, "pinpointSocket must not be null");

        final byte[] payload = getPayload(packet);

        try {
            Message<TBase<?, ?>> message = SerializationUtils.deserialize(payload, deserializerFactory);
            ServerRequest<TBase<?, ?>> request = new DefaultServerRequest<>(message);
            ServerResponse<TBase<?, ?>> response = new TCPServerResponse(serializerFactory, pinpointSocket, packet.getRequestId());
            dispatchHandler.dispatchRequestMessage(request, response);
        } catch (TException e) {
            SocketAddress remoteAddress = pinpointSocket.getRemoteAddress();
            handleTException(payload, remoteAddress, e);
        } catch (Exception e) {
            SocketAddress remoteAddress = pinpointSocket.getRemoteAddress();
            handleException(payload, remoteAddress, e);
        }
    }

    private void handleTException(byte[] payload, SocketAddress remoteAddress, TException e) {
        if (logger.isWarnEnabled()) {
            logger.warn("packet deserialize error. remote:{} cause:{}", remoteAddress, e.getMessage(), e);
        }
        if (isDebug) {
            logger.debug("packet dump hex:{}", PacketUtils.dumpByteArray(payload));
        }
        throw new RuntimeException("serialized fail ", e);
    }

    private void handleException(byte[] payload, SocketAddress remoteAddress, Exception e) {
        // there are cases where invalid headers are received
        if (logger.isWarnEnabled()) {
            logger.warn("Unexpected error. remote:{} cause:{}", remoteAddress, e.getMessage(), e);
        }
        if (isDebug) {
            logger.debug("packet dump hex:{}", PacketUtils.dumpByteArray(payload));
        }
    }
}
