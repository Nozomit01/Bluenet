package ru.bluenet.vpn;

public final class NativeBridge {
    static { System.loadLibrary("bluenet"); }
    private NativeBridge() {}
    public static native boolean start(BlueVpnService callbacks, String deviceName, boolean relayOnly);
    public static native void stop();
    static native void nonblocking(int fd);
    public static native String status();
    public static native long command(String json);
}
