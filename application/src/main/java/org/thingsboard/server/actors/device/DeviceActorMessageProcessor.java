/**
 * Copyright © 2016-2018 The Thingsboard Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.thingsboard.server.actors.device;

import akka.actor.ActorContext;
import akka.event.LoggingAdapter;
import com.datastax.driver.core.utils.UUIDs;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.thingsboard.rule.engine.api.RpcError;
import org.thingsboard.rule.engine.api.msg.DeviceAttributesEventNotificationMsg;
import org.thingsboard.rule.engine.api.msg.DeviceNameOrTypeUpdateMsg;
import org.thingsboard.server.actors.ActorSystemContext;
import org.thingsboard.server.actors.shared.AbstractContextAwareMsgProcessor;
import org.thingsboard.server.common.data.DataConstants;
import org.thingsboard.server.common.data.Device;
import org.thingsboard.server.common.data.id.DeviceId;
import org.thingsboard.server.common.data.id.SessionId;
import org.thingsboard.server.common.data.id.TenantId;
import org.thingsboard.server.common.data.kv.AttributeKey;
import org.thingsboard.server.common.data.kv.AttributeKvEntry;
import org.thingsboard.server.common.data.kv.KvEntry;
import org.thingsboard.server.common.data.rpc.ToDeviceRpcRequestBody;
import org.thingsboard.server.common.msg.TbMsg;
import org.thingsboard.server.common.msg.TbMsgDataType;
import org.thingsboard.server.common.msg.TbMsgMetaData;
import org.thingsboard.server.common.msg.cluster.ClusterEventMsg;
import org.thingsboard.server.common.msg.cluster.ServerAddress;
import org.thingsboard.server.common.msg.core.ActorSystemToDeviceSessionActorMsg;
import org.thingsboard.server.common.msg.core.AttributesUpdateNotification;
import org.thingsboard.server.common.msg.core.RuleEngineError;
import org.thingsboard.server.common.msg.core.RuleEngineErrorMsg;
import org.thingsboard.server.common.msg.core.ToDeviceRpcRequestMsg;
import org.thingsboard.server.common.msg.kv.BasicAttributeKVMsg;
import org.thingsboard.server.common.msg.rpc.ToDeviceRpcRequest;
import org.thingsboard.server.common.msg.session.SessionMsgType;
import org.thingsboard.server.common.msg.session.SessionType;
import org.thingsboard.server.common.msg.session.ToDeviceMsg;
import org.thingsboard.server.common.msg.timeout.DeviceActorClientSideRpcTimeoutMsg;
import org.thingsboard.server.common.msg.timeout.DeviceActorServerSideRpcTimeoutMsg;
import org.thingsboard.server.gen.transport.TransportProtos;
import org.thingsboard.server.service.rpc.FromDeviceRpcResponse;
import org.thingsboard.server.service.rpc.ToDeviceRpcRequestActorMsg;
import org.thingsboard.server.service.rpc.ToServerRpcResponseActorMsg;
import org.thingsboard.server.service.transport.msg.TransportToDeviceActorMsgWrapper;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import org.thingsboard.server.gen.transport.TransportProtos.*;

/**
 * @author Andrew Shvayka
 */
public class DeviceActorMessageProcessor extends AbstractContextAwareMsgProcessor {

    private final TenantId tenantId;
    private final DeviceId deviceId;
    private final Map<UUID, SessionInfo> sessions;
    private final Map<UUID, SessionInfo> attributeSubscriptions;
    private final Map<UUID, SessionInfo> rpcSubscriptions;
    private final Map<Integer, ToDeviceRpcRequestMetadata> toDeviceRpcPendingMap;
    private final Map<Integer, ToServerRpcRequestMetadata> toServerRpcPendingMap;

    private final Gson gson = new Gson();
    private final JsonParser jsonParser = new JsonParser();

    private int rpcSeq = 0;
    private String deviceName;
    private String deviceType;
    private TbMsgMetaData defaultMetaData;

    DeviceActorMessageProcessor(ActorSystemContext systemContext, LoggingAdapter logger, TenantId tenantId, DeviceId deviceId) {
        super(systemContext, logger);
        this.tenantId = tenantId;
        this.deviceId = deviceId;
        this.sessions = new LinkedHashMap<>();
        this.attributeSubscriptions = new HashMap<>();
        this.rpcSubscriptions = new HashMap<>();
        this.toDeviceRpcPendingMap = new HashMap<>();
        this.toServerRpcPendingMap = new HashMap<>();
        initAttributes();
    }

    private void initAttributes() {
        Device device = systemContext.getDeviceService().findDeviceById(deviceId);
        this.deviceName = device.getName();
        this.deviceType = device.getType();
        this.defaultMetaData = new TbMsgMetaData();
        this.defaultMetaData.putValue("deviceName", deviceName);
        this.defaultMetaData.putValue("deviceType", deviceType);
    }

    void processRpcRequest(ActorContext context, ToDeviceRpcRequestActorMsg msg) {
        ToDeviceRpcRequest request = msg.getMsg();
        ToDeviceRpcRequestBody body = request.getBody();
        ToDeviceRpcRequestMsg rpcRequest = new ToDeviceRpcRequestMsg(
                rpcSeq++,
                body.getMethod(),
                body.getParams()
        );

        long timeout = request.getExpirationTime() - System.currentTimeMillis();
        if (timeout <= 0) {
            logger.debug("[{}][{}] Ignoring message due to exp time reached", deviceId, request.getId(), request.getExpirationTime());
            return;
        }

        boolean sent = rpcSubscriptions.size() > 0;
        Set<SessionId> syncSessionSet = new HashSet<>();
        rpcSubscriptions.entrySet().forEach(sub -> {
//            ActorSystemToDeviceSessionActorMsg response = new BasicActorSystemToDeviceSessionActorMsg(rpcRequest, sub.getKey());
//            sendMsgToSessionActor(response, sub.getValue().getServer());
//            if (SessionType.SYNC == sub.getValue().getType()) {
//                syncSessionSet.add(sub.getKey());
//            }
        });
        syncSessionSet.forEach(rpcSubscriptions::remove);

        if (request.isOneway() && sent) {
            logger.debug("[{}] Rpc command response sent [{}]!", deviceId, request.getId());
            systemContext.getDeviceRpcService().processRpcResponseFromDevice(new FromDeviceRpcResponse(msg.getMsg().getId(), msg.getServerAddress(), null, null));
        } else {
            registerPendingRpcRequest(context, msg, sent, rpcRequest, timeout);
        }
        if (sent) {
            logger.debug("[{}] RPC request {} is sent!", deviceId, request.getId());
        } else {
            logger.debug("[{}] RPC request {} is NOT sent!", deviceId, request.getId());
        }

    }

    private void registerPendingRpcRequest(ActorContext context, ToDeviceRpcRequestActorMsg msg, boolean sent, ToDeviceRpcRequestMsg rpcRequest, long timeout) {
        toDeviceRpcPendingMap.put(rpcRequest.getRequestId(), new ToDeviceRpcRequestMetadata(msg, sent));
        DeviceActorServerSideRpcTimeoutMsg timeoutMsg = new DeviceActorServerSideRpcTimeoutMsg(rpcRequest.getRequestId(), timeout);
        scheduleMsgWithDelay(context, timeoutMsg, timeoutMsg.getTimeout());
    }

    void processServerSideRpcTimeout(ActorContext context, DeviceActorServerSideRpcTimeoutMsg msg) {
        ToDeviceRpcRequestMetadata requestMd = toDeviceRpcPendingMap.remove(msg.getId());
        if (requestMd != null) {
            logger.debug("[{}] RPC request [{}] timeout detected!", deviceId, msg.getId());
            systemContext.getDeviceRpcService().processRpcResponseFromDevice(new FromDeviceRpcResponse(requestMd.getMsg().getMsg().getId(),
                    requestMd.getMsg().getServerAddress(), null, requestMd.isSent() ? RpcError.TIMEOUT : RpcError.NO_ACTIVE_CONNECTION));
        }
    }

    private void sendPendingRequests(ActorContext context, SessionId sessionId, SessionType type, Optional<ServerAddress> server) {
        if (!toDeviceRpcPendingMap.isEmpty()) {
            logger.debug("[{}] Pushing {} pending RPC messages to new async session [{}]", deviceId, toDeviceRpcPendingMap.size(), sessionId);
            if (type == SessionType.SYNC) {
                logger.debug("[{}] Cleanup sync rpc session [{}]", deviceId, sessionId);
                rpcSubscriptions.remove(sessionId);
            }
        } else {
            logger.debug("[{}] No pending RPC messages for new async session [{}]", deviceId, sessionId);
        }
        Set<Integer> sentOneWayIds = new HashSet<>();
        if (type == SessionType.ASYNC) {
            toDeviceRpcPendingMap.entrySet().forEach(processPendingRpc(context, sessionId, server, sentOneWayIds));
        } else {
            toDeviceRpcPendingMap.entrySet().stream().findFirst().ifPresent(processPendingRpc(context, sessionId, server, sentOneWayIds));
        }

        sentOneWayIds.forEach(toDeviceRpcPendingMap::remove);
    }

    private Consumer<Map.Entry<Integer, ToDeviceRpcRequestMetadata>> processPendingRpc(ActorContext context, SessionId sessionId, Optional<ServerAddress> server, Set<Integer> sentOneWayIds) {
        return entry -> {
            ToDeviceRpcRequestActorMsg requestActorMsg = entry.getValue().getMsg();
            ToDeviceRpcRequest request = entry.getValue().getMsg().getMsg();
            ToDeviceRpcRequestBody body = request.getBody();
            if (request.isOneway()) {
                sentOneWayIds.add(entry.getKey());
                systemContext.getDeviceRpcService().processRpcResponseFromDevice(new FromDeviceRpcResponse(request.getId(), requestActorMsg.getServerAddress(), null, null));
            }
            ToDeviceRpcRequestMsg rpcRequest = new ToDeviceRpcRequestMsg(
                    entry.getKey(),
                    body.getMethod(),
                    body.getParams()
            );
//            ActorSystemToDeviceSessionActorMsg response = new BasicActorSystemToDeviceSessionActorMsg(rpcRequest, sessionId);
//            sendMsgToSessionActor(response, server);
        };
    }

    void process(ActorContext context, TransportToDeviceActorMsgWrapper wrapper) {
        TransportToDeviceActorMsg msg = wrapper.getMsg();
//        processSubscriptionCommands(context, msg);
//        processRpcResponses(context, msg);
        if (msg.hasSessionEvent()) {
            processSessionStateMsgs(msg.getSessionInfo(), msg.getSessionEvent());
        }

        if (msg.hasPostAttributes()) {
            handlePostAttributesRequest(context, msg.getSessionInfo(), msg.getPostAttributes());
            reportActivity();
        }
        if (msg.hasPostTelemetry()) {
            handlePostTelemetryRequest(context, msg.getSessionInfo(), msg.getPostTelemetry());
            reportActivity();
        }
        if (msg.hasGetAttributes()) {
            handleGetAttributesRequest(context, msg.getSessionInfo(), msg.getGetAttributes());
        }
//        SessionMsgType sessionMsgType = msg.getPayload().getMsgType();
//        if (sessionMsgType.requiresRulesProcessing()) {
//            switch (sessionMsgType) {
//                case GET_ATTRIBUTES_REQUEST:
//                    handleGetAttributesRequest(msg);
//                    break;
//                case TO_SERVER_RPC_REQUEST:
//                    handleClientSideRPCRequest(context, msg);
//                    reportActivity();
//                    break;
//            }
//        }
    }

    private void reportActivity() {
        systemContext.getDeviceStateService().onDeviceActivity(deviceId);
    }

    private void reportSessionOpen() {
        systemContext.getDeviceStateService().onDeviceConnect(deviceId);
    }

    private void reportSessionClose() {
        systemContext.getDeviceStateService().onDeviceDisconnect(deviceId);
    }

    private void handleGetAttributesRequest(ActorContext context, SessionInfoProto sessionInfo, GetAttributeRequestMsg request) {
        ListenableFuture<List<AttributeKvEntry>> clientAttributesFuture = getAttributeKvEntries(deviceId, DataConstants.CLIENT_SCOPE, toOptionalSet(request.getClientAttributeNamesList()));
        ListenableFuture<List<AttributeKvEntry>> sharedAttributesFuture = getAttributeKvEntries(deviceId, DataConstants.SHARED_SCOPE, toOptionalSet(request.getSharedAttributeNamesList()));
        UUID sessionId = new UUID(sessionInfo.getSessionIdMSB(), sessionInfo.getSessionIdLSB());
        int requestId = request.getRequestId();
        Futures.addCallback(Futures.allAsList(Arrays.asList(clientAttributesFuture, sharedAttributesFuture)), new FutureCallback<List<List<AttributeKvEntry>>>() {
            @Override
            public void onSuccess(@Nullable List<List<AttributeKvEntry>> result) {
                GetAttributeResponseMsg responseMsg = GetAttributeResponseMsg.newBuilder()
                        .setRequestId(requestId)
                        .addAllClientAttributeList(toTsKvProtos(result.get(0)))
                        .addAllSharedAttributeList(toTsKvProtos(result.get(1)))
                        .build();
                sendToTransport(responseMsg, sessionId, sessionInfo);
            }

            @Override
            public void onFailure(Throwable t) {
                GetAttributeResponseMsg responseMsg = GetAttributeResponseMsg.newBuilder()
                        .setError(t.getMessage())
                        .build();
                sendToTransport(responseMsg, sessionId, sessionInfo);
            }
        });
    }

    private ListenableFuture<List<AttributeKvEntry>> getAttributeKvEntries(DeviceId deviceId, String scope, Optional<Set<String>> names) {
        if (names.isPresent()) {
            if (!names.get().isEmpty()) {
                return systemContext.getAttributesService().find(deviceId, scope, names.get());
            } else {
                return systemContext.getAttributesService().findAll(deviceId, scope);
            }
        } else {
            return Futures.immediateFuture(Collections.emptyList());
        }
    }

    private void handlePostAttributesRequest(ActorContext context, SessionInfoProto sessionInfo, PostAttributeMsg postAttributes) {
        JsonObject json = getJsonObject(postAttributes.getKvList());
        TbMsg tbMsg = new TbMsg(UUIDs.timeBased(), SessionMsgType.POST_ATTRIBUTES_REQUEST.name(), deviceId, defaultMetaData.copy(),
                TbMsgDataType.JSON, gson.toJson(json), null, null, 0L);
        pushToRuleEngine(context, tbMsg);
    }

    private void handlePostTelemetryRequest(ActorContext context, SessionInfoProto sessionInfo, PostTelemetryMsg postTelemetry) {
        for (TsKvListProto tsKv : postTelemetry.getTsKvListList()) {
            JsonObject json = getJsonObject(tsKv.getKvList());
            TbMsgMetaData metaData = defaultMetaData.copy();
            metaData.putValue("ts", tsKv.getTs() + "");
            TbMsg tbMsg = new TbMsg(UUIDs.timeBased(), SessionMsgType.POST_TELEMETRY_REQUEST.name(), deviceId, metaData, TbMsgDataType.JSON, gson.toJson(json), null, null, 0L);
            pushToRuleEngine(context, tbMsg);
        }
    }

//    private void handleClientSideRPCRequest(ActorContext context, DeviceToDeviceActorMsg src) {
//        ToServerRpcRequestMsg request = (ToServerRpcRequestMsg) src.getPayload();
//
//        JsonObject json = new JsonObject();
//        json.addProperty("method", request.getMethod());
//        json.add("params", jsonParser.parse(request.getParams()));
//
//        TbMsgMetaData requestMetaData = defaultMetaData.copy();
//        requestMetaData.putValue("requestId", Integer.toString(request.getRequestId()));
//        TbMsg tbMsg = new TbMsg(UUIDs.timeBased(), SessionMsgType.TO_SERVER_RPC_REQUEST.name(), deviceId, requestMetaData, TbMsgDataType.JSON, gson.toJson(json), null, null, 0L);
//        PendingSessionMsgData msgData = new PendingSessionMsgData(src.getSessionId(), src.getServerAddress(), SessionMsgType.TO_SERVER_RPC_REQUEST, request.getRequestId(), false, 1);
//        pushToRuleEngineWithTimeout(context, tbMsg, msgData);
//
//        scheduleMsgWithDelay(context, new DeviceActorClientSideRpcTimeoutMsg(request.getRequestId(), systemContext.getClientSideRpcTimeout()), systemContext.getClientSideRpcTimeout());
//        toServerRpcPendingMap.put(request.getRequestId(), new ToServerRpcRequestMetadata(src.getSessionId(), src.getSessionType(), src.getServerAddress()));
//    }

    public void processClientSideRpcTimeout(ActorContext context, DeviceActorClientSideRpcTimeoutMsg msg) {
        ToServerRpcRequestMetadata data = toServerRpcPendingMap.remove(msg.getId());
        if (data != null) {
            logger.debug("[{}] Client side RPC request [{}] timeout detected!", deviceId, msg.getId());
            ToDeviceMsg toDeviceMsg = new RuleEngineErrorMsg(SessionMsgType.TO_SERVER_RPC_REQUEST, RuleEngineError.TIMEOUT);
//            sendMsgToSessionActor(new BasicActorSystemToDeviceSessionActorMsg(toDeviceMsg, data.getSessionId()), data.getServer());
        }
    }

    void processToServerRPCResponse(ActorContext context, ToServerRpcResponseActorMsg msg) {
        ToServerRpcRequestMetadata data = toServerRpcPendingMap.remove(msg.getMsg().getRequestId());
        if (data != null) {
//            sendMsgToSessionActor(new BasicActorSystemToDeviceSessionActorMsg(msg.getMsg(), data.getSessionId()), data.getServer());
        }
    }

    private void pushToRuleEngine(ActorContext context, TbMsg tbMsg) {
        context.parent().tell(new DeviceActorToRuleEngineMsg(context.self(), tbMsg), context.self());
    }

    void processAttributesUpdate(ActorContext context, DeviceAttributesEventNotificationMsg msg) {
        if (attributeSubscriptions.size() > 0) {
            ToDeviceMsg notification = null;
            if (msg.isDeleted()) {
                List<AttributeKey> sharedKeys = msg.getDeletedKeys().stream()
                        .filter(key -> DataConstants.SHARED_SCOPE.equals(key.getScope()))
                        .collect(Collectors.toList());
                notification = new AttributesUpdateNotification(BasicAttributeKVMsg.fromDeleted(sharedKeys));
            } else {
                if (DataConstants.SHARED_SCOPE.equals(msg.getScope())) {
                    List<AttributeKvEntry> attributes = new ArrayList<>(msg.getValues());
                    if (attributes.size() > 0) {
                        notification = new AttributesUpdateNotification(BasicAttributeKVMsg.fromShared(attributes));
                    } else {
                        logger.debug("[{}] No public server side attributes changed!", deviceId);
                    }
                }
            }
            if (notification != null) {
                ToDeviceMsg finalNotification = notification;
//                attributeSubscriptions.entrySet().forEach(sub -> {
//                    ActorSystemToDeviceSessionActorMsg response = new BasicActorSystemToDeviceSessionActorMsg(finalNotification, sub.getKey());
//                    sendMsgToSessionActor(response, sub.getValue().getServer());
//                });
            }
        } else {
            logger.debug("[{}] No registered attributes subscriptions to process!", deviceId);
        }
    }

//    private void processRpcResponses(ActorContext context, DeviceToDeviceActorMsg msg) {
//        SessionId sessionId = msg.getSessionId();
//        FromDeviceMsg inMsg = msg.getPayload();
//        if (inMsg.getMsgType() == SessionMsgType.TO_DEVICE_RPC_RESPONSE) {
//            logger.debug("[{}] Processing rpc command response [{}]", deviceId, sessionId);
//            ToDeviceRpcResponseMsg responseMsg = (ToDeviceRpcResponseMsg) inMsg;
//            ToDeviceRpcRequestMetadata requestMd = toDeviceRpcPendingMap.remove(responseMsg.getRequestId());
//            boolean success = requestMd != null;
//            if (success) {
//                systemContext.getDeviceRpcService().processRpcResponseFromDevice(new FromDeviceRpcResponse(requestMd.getMsg().getMsg().getId(),
//                        requestMd.getMsg().getServerAddress(), responseMsg.getData(), null));
//            } else {
//                logger.debug("[{}] Rpc command response [{}] is stale!", deviceId, responseMsg.getRequestId());
//            }
//            if (msg.getSessionType() == SessionType.SYNC) {
//                BasicCommandAckResponse response = success
//                        ? BasicCommandAckResponse.onSuccess(SessionMsgType.TO_DEVICE_RPC_REQUEST, responseMsg.getRequestId())
//                        : BasicCommandAckResponse.onError(SessionMsgType.TO_DEVICE_RPC_REQUEST, responseMsg.getRequestId(), new TimeoutException());
//                sendMsgToSessionActor(new BasicActorSystemToDeviceSessionActorMsg(response, msg.getSessionId()), msg.getServerAddress());
//            }
//        }
//    }

    void processClusterEventMsg(ClusterEventMsg msg) {
//        if (!msg.isAdded()) {
//            logger.debug("[{}] Clearing attributes/rpc subscription for server [{}]", deviceId, msg.getServerAddress());
//            Predicate<Map.Entry<SessionId, SessionInfo>> filter = e -> e.getValue().getServer()
//                    .map(serverAddress -> serverAddress.equals(msg.getServerAddress())).orElse(false);
//            attributeSubscriptions.entrySet().removeIf(filter);
//            rpcSubscriptions.entrySet().removeIf(filter);
//        }
    }

//    private void processSubscriptionCommands(ActorContext context, DeviceToDeviceActorMsg msg) {
//        SessionId sessionId = msg.getSessionId();
//        SessionType sessionType = msg.getSessionType();
//        FromDeviceMsg inMsg = msg.getPayload();
//        if (inMsg.getMsgType() == SessionMsgType.SUBSCRIBE_ATTRIBUTES_REQUEST) {
//            logger.debug("[{}] Registering attributes subscription for session [{}]", deviceId, sessionId);
//            attributeSubscriptions.put(sessionId, new SessionInfo(sessionType, msg.getServerAddress()));
//        } else if (inMsg.getMsgType() == SessionMsgType.UNSUBSCRIBE_ATTRIBUTES_REQUEST) {
//            logger.debug("[{}] Canceling attributes subscription for session [{}]", deviceId, sessionId);
//            attributeSubscriptions.remove(sessionId);
//        } else if (inMsg.getMsgType() == SessionMsgType.SUBSCRIBE_RPC_COMMANDS_REQUEST) {
//            logger.debug("[{}] Registering rpc subscription for session [{}][{}]", deviceId, sessionId, sessionType);
//            rpcSubscriptions.put(sessionId, new SessionInfo(sessionType, msg.getServerAddress()));
//            sendPendingRequests(context, sessionId, sessionType, msg.getServerAddress());
//        } else if (inMsg.getMsgType() == SessionMsgType.UNSUBSCRIBE_RPC_COMMANDS_REQUEST) {
//            logger.debug("[{}] Canceling rpc subscription for session [{}][{}]", deviceId, sessionId, sessionType);
//            rpcSubscriptions.remove(sessionId);
//        }
//    }

    private void processSessionStateMsgs(SessionInfoProto sessionInfo, SessionEventMsg msg) {
        UUID sessionId = new UUID(sessionInfo.getSessionIdMSB(), sessionInfo.getSessionIdLSB());
        if (msg.getEvent() == SessionEvent.OPEN) {
            logger.debug("[{}] Processing new session [{}]", deviceId, sessionId);
            if (sessions.size() >= systemContext.getMaxConcurrentSessionsPerDevice()) {
                UUID sessionIdToRemove = sessions.keySet().stream().findFirst().orElse(null);
                if (sessionIdToRemove != null) {
                    closeSession(sessionIdToRemove, sessions.remove(sessionIdToRemove));
                }
            }
            sessions.put(sessionId, new SessionInfo(TransportProtos.SessionType.ASYNC, sessionInfo.getNodeId()));
            if (sessions.size() == 1) {
                reportSessionOpen();
            }
        } else if (msg.getEvent() == SessionEvent.CLOSED) {
            logger.debug("[{}] Canceling subscriptions for closed session [{}]", deviceId, sessionId);
            sessions.remove(sessionId);
            attributeSubscriptions.remove(sessionId);
            rpcSubscriptions.remove(sessionId);
            if (sessions.isEmpty()) {
                reportSessionClose();
            }
        }
    }

    private void sendMsgToSessionActor(ActorSystemToDeviceSessionActorMsg response, Optional<ServerAddress> sessionAddress) {
        if (sessionAddress.isPresent()) {
            ServerAddress address = sessionAddress.get();
            logger.debug("{} Forwarding msg: {}", address, response);
            systemContext.getRpcService().tell(systemContext.getEncodingService()
                    .convertToProtoDataMessage(sessionAddress.get(), response));
        } else {
//            systemContext.getSessionManagerActor().tell(response, ActorRef.noSender());
        }
    }

    void processCredentialsUpdate() {
        sessions.forEach(this::closeSession);
        attributeSubscriptions.clear();
        rpcSubscriptions.clear();
    }

    private void closeSession(UUID sessionId, SessionInfo sessionInfo) {
        DeviceActorToTransportMsg msg = DeviceActorToTransportMsg.newBuilder()
                .setSessionIdMSB(sessionId.getMostSignificantBits())
                .setSessionIdLSB(sessionId.getLeastSignificantBits())
                .setSessionCloseNotification(SessionCloseNotificationProto.getDefaultInstance()).build();
        systemContext.getRuleEngineTransportService().process(sessionInfo.getNodeId(), msg);
    }

    void processNameOrTypeUpdate(DeviceNameOrTypeUpdateMsg msg) {
        this.deviceName = msg.getDeviceName();
        this.deviceType = msg.getDeviceType();
        this.defaultMetaData = new TbMsgMetaData();
        this.defaultMetaData.putValue("deviceName", deviceName);
        this.defaultMetaData.putValue("deviceType", deviceType);
    }

    private JsonObject getJsonObject(List<KeyValueProto> tsKv) {
        JsonObject json = new JsonObject();
        for (KeyValueProto kv : tsKv) {
            switch (kv.getType()) {
                case BOOLEAN_V:
                    json.addProperty(kv.getKey(), kv.getBoolV());
                    break;
                case LONG_V:
                    json.addProperty(kv.getKey(), kv.getLongV());
                    break;
                case DOUBLE_V:
                    json.addProperty(kv.getKey(), kv.getDoubleV());
                    break;
                case STRING_V:
                    json.addProperty(kv.getKey(), kv.getStringV());
                    break;
            }
        }
        return json;
    }

    private Optional<Set<String>> toOptionalSet(List<String> strings) {
        if (strings == null || strings.isEmpty()) {
            return Optional.empty();
        } else {
            return Optional.of(new HashSet<>(strings));
        }
    }

    private void sendToTransport(GetAttributeResponseMsg responseMsg, UUID sessionId, SessionInfoProto sessionInfo) {
        DeviceActorToTransportMsg msg = DeviceActorToTransportMsg.newBuilder()
                .setSessionIdMSB(sessionId.getMostSignificantBits())
                .setSessionIdLSB(sessionId.getLeastSignificantBits())
                .setGetAttributesResponse(responseMsg).build();
        systemContext.getRuleEngineTransportService().process(sessionInfo.getNodeId(), msg);
    }

    private List<TsKvProto> toTsKvProtos(@Nullable List<AttributeKvEntry> result) {
        List<TsKvProto> clientAttributes;
        if (result == null || result.isEmpty()) {
            clientAttributes = Collections.emptyList();
        } else {
            clientAttributes = new ArrayList<>(result.size());
            for (AttributeKvEntry attrEntry : result) {
                clientAttributes.add(toTsKvProto(attrEntry));
            }
        }
        return clientAttributes;
    }

    private TsKvProto toTsKvProto(AttributeKvEntry attrEntry) {
        return TsKvProto.newBuilder().setTs(attrEntry.getLastUpdateTs())
                .setKv(toKeyValueProto(attrEntry)).build();
    }

    private KeyValueProto toKeyValueProto(KvEntry kvEntry) {
        KeyValueProto.Builder builder = KeyValueProto.newBuilder();
        builder.setKey(kvEntry.getKey());
        switch (kvEntry.getDataType()) {
            case BOOLEAN:
                builder.setType(KeyValueType.BOOLEAN_V);
                builder.setBoolV(kvEntry.getBooleanValue().get());
                break;
            case DOUBLE:
                builder.setType(KeyValueType.DOUBLE_V);
                builder.setDoubleV(kvEntry.getDoubleValue().get());
                break;
            case LONG:
                builder.setType(KeyValueType.LONG_V);
                builder.setLongV(kvEntry.getLongValue().get());
                break;
            case STRING:
                builder.setType(KeyValueType.STRING_V);
                builder.setStringV(kvEntry.getStrValue().get());
                break;
        }
        return builder.build();
    }
}
