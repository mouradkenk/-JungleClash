package com.jungleclash.game.net;

/** A way for the host and players to talk: nearby (Bluetooth / Wi-Fi Direct) or online (internet). */
public interface Link {
    void setGate(NearbyLink.Gate gate);
    void accept(String endpointId);
    void reject(String endpointId);
    void startHosting(String name);
    void stopAdvertising();
    void startSearching();
    void stopSearching();
    void join(String myName, String endpointId);
    void send(String endpointId, String message);
    void shutdown();
}
