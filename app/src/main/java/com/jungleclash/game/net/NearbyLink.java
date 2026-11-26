package com.jungleclash.game.net;

import android.content.Context;


import com.google.android.gms.nearby.Nearby;
import com.google.android.gms.nearby.connection.AdvertisingOptions;
import com.google.android.gms.nearby.connection.ConnectionInfo;
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback;
import com.google.android.gms.nearby.connection.ConnectionResolution;
import com.google.android.gms.nearby.connection.ConnectionsClient;
import com.google.android.gms.nearby.connection.ConnectionsStatusCodes;
import com.google.android.gms.nearby.connection.DiscoveredEndpointInfo;
import com.google.android.gms.nearby.connection.DiscoveryOptions;
import com.google.android.gms.nearby.connection.EndpointDiscoveryCallback;
import com.google.android.gms.nearby.connection.Payload;
import com.google.android.gms.nearby.connection.PayloadCallback;
import com.google.android.gms.nearby.connection.PayloadTransferUpdate;
import com.google.android.gms.nearby.connection.Strategy;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * Thin wrapper over Google Nearby Connections (Bluetooth + Wi-Fi Direct, no internet).
 * One host advertises, the other phones discover it and connect (P2P_STAR).
 */
public class NearbyLink {

    public static final String SERVICE_ID = "com.jungleclash.game.v1";

    public interface Listener {
        void onPeerFound(String endpointId, String name);
        void onPeerLost(String endpointId);
        void onConnected(String endpointId, String name);
        void onDisconnected(String endpointId);
        void onMessage(String endpointId, String message);
        void onError(String message);
    }

    public interface AcceptFilter { boolean accept(String endpointId); }

    private final ConnectionsClient client;
    private final Listener listener;
    private final Map<String, String> pendingNames = new HashMap<>();
    private AcceptFilter acceptFilter = id -> true;

    public NearbyLink(Context context, Listener listener) {
        this.client = Nearby.getConnectionsClient(context);
        this.listener = listener;
    }

    public void setAcceptFilter(AcceptFilter filter) { this.acceptFilter = filter; }

    private final PayloadCallback payloadCallback = new PayloadCallback() {
        @Override
        public void onPayloadReceived(String endpointId, Payload payload) {
            byte[] bytes = payload.asBytes();
            if (bytes != null) listener.onMessage(endpointId, new String(bytes, StandardCharsets.UTF_8));
        }

        @Override
        public void onPayloadTransferUpdate(String endpointId, PayloadTransferUpdate update) { }
    };

    private final ConnectionLifecycleCallback lifecycle = new ConnectionLifecycleCallback() {
        @Override
        public void onConnectionInitiated(String endpointId, ConnectionInfo info) {
            pendingNames.put(endpointId, info.getEndpointName());
            if (acceptFilter.accept(endpointId)) client.acceptConnection(endpointId, payloadCallback);
            else client.rejectConnection(endpointId);
        }

        @Override
        public void onConnectionResult(String endpointId, ConnectionResolution result) {
            if (result.getStatus().getStatusCode() == ConnectionsStatusCodes.STATUS_OK) {
                String name = pendingNames.get(endpointId);
                listener.onConnected(endpointId, name == null ? "" : name);
            } else {
                listener.onError("تعذّر الاتصال. حاول مرة أخرى.");
            }
        }

        @Override
        public void onDisconnected(String endpointId) { listener.onDisconnected(endpointId); }
    };

    private final EndpointDiscoveryCallback discovery = new EndpointDiscoveryCallback() {
        @Override
        public void onEndpointFound(String endpointId, DiscoveredEndpointInfo info) {
            if (SERVICE_ID.equals(info.getServiceId())) listener.onPeerFound(endpointId, info.getEndpointName());
        }

        @Override
        public void onEndpointLost(String endpointId) { listener.onPeerLost(endpointId); }
    };

    public void startHosting(String name) {
        AdvertisingOptions options = new AdvertisingOptions.Builder().setStrategy(Strategy.P2P_STAR).build();
        client.startAdvertising(name, SERVICE_ID, lifecycle, options)
              .addOnFailureListener(e -> listener.onError("تعذّر إنشاء الغرفة: " + e.getMessage()));
    }

    public void startSearching() {
        DiscoveryOptions options = new DiscoveryOptions.Builder().setStrategy(Strategy.P2P_STAR).build();
        client.startDiscovery(SERVICE_ID, discovery, options)
              .addOnFailureListener(e -> listener.onError("تعذّر البحث عن الغرف: " + e.getMessage()));
    }

    public void join(String myName, String endpointId) {
        client.requestConnection(myName, endpointId, lifecycle)
              .addOnFailureListener(e -> listener.onError("تعذّر الانضمام: " + e.getMessage()));
    }

    public void stopAdvertising() { client.stopAdvertising(); }

    public void stopSearching() { client.stopDiscovery(); }

    public void send(String endpointId, String message) {
        client.sendPayload(endpointId, Payload.fromBytes(message.getBytes(StandardCharsets.UTF_8)));
    }

    public void shutdown() {
        client.stopAdvertising();
        client.stopDiscovery();
        client.stopAllEndpoints();
    }
}
