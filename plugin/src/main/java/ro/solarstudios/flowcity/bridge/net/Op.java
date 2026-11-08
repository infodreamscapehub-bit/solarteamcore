package ro.solarstudios.flowcity.bridge.net;

/** Coduri de operatie ale protocolului flowcity:bridge. Trebuie sa fie identice in mod. */
public final class Op {
    private Op() {}

    public static final String CHANNEL = "flowcity:bridge";
    public static final int PROTOCOL = 1;

    // client -> server
    public static final int C_HELLO = 0x00;
    public static final int C_OPEN_REQUEST = 0x01;
    public static final int C_PLAYER_LIST = 0x02;
    public static final int C_PROFILE = 0x03;
    public static final int C_LOGS = 0x04;
    public static final int C_SANCTION = 0x05;
    public static final int C_REVOKE = 0x06;
    public static final int C_NOTE_ADD = 0x07;
    public static final int C_NOTE_DEL = 0x08;
    public static final int C_ACTION = 0x09;
    /** Mesaj pentru un modul: utf id, utf action, apoi payload-ul modulului. */
    public static final int C_MODULE = 0x10;

    // server -> client
    public static final int S_OPEN = 0x81;
    public static final int S_PLAYER_LIST = 0x82;
    public static final int S_PROFILE = 0x83;
    public static final int S_LOGS = 0x84;
    public static final int S_TOAST = 0x85;
    /** Mesaj de la un modul: utf id, utf action, apoi payload-ul modulului. */
    public static final int S_MODULE = 0x90;
}
